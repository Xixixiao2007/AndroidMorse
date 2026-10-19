package com.bh4gzk.morserunner.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders a keyed message to audio.
 *
 * Envelope shape and ramp placement are transcribed from `VCL/MorseKey.pas`:
 *
 *  - the ramp is a **Blackman-Harris step response** (`:118-146`): the kernel
 *    `a0 - a1·cos(2πx) + a2·cos(4πx) - a3·cos(6πx)` is summed and normalised, so it
 *    rises smoothly from 0 to 1 instead of using a plain raised cosine;
 *  - ramp length is `Round(2.7 × riseTime × rate)` samples (`:150`), 5 ms rise time
 *    (`:80`), i.e. 648 samples at 48 kHz;
 *  - the ramp is **inside** the element for the attack but the decay spills into the
 *    following gap (`AddOn`/`AddOff`, `:199-230`). Tone onsets therefore stay exactly
 *    one unit apart and the message keeps its speed, while each tone sounds slightly
 *    longer than its nominal length — that is what Morse Runner sounds like.
 *
 * The oscillator runs continuously across the whole message, so phase never jumps at
 * an element boundary: the only thing that changes between elements is the envelope.
 */
class CwSynthesizer(
    private val rate: Int = DEFAULT_RATE,
    private val riseTimeMs: Double = DEFAULT_RISE_TIME_MS,
) {

    /** Ramp length in samples, as upstream computes it. */
    val rampSamples: Int = (2.7 * riseTimeMs / 1000.0 * rate).roundToInt()

    private val rampOn: FloatArray = blackmanHarrisStepResponse(rampSamples)
    private val rampOff: FloatArray = FloatArray(rampSamples) { rampOn[rampSamples - 1 - it] }

    /** Number of samples the given elements occupy. */
    fun sampleCount(elements: List<MorseElement>): Int =
        elements.sumOf { (it.ms * rate / 1000.0).roundToInt() }

    /**
     * Builds the 0..1 envelope for [elements]. Returns an array of
     * [sampleCount] samples whose first and last values are 0, so playback never
     * starts or stops on a step.
     */
    fun envelope(elements: List<MorseElement>): FloatArray {
        val env = FloatArray(sampleCount(elements))
        var p = 0
        for (element in elements) {
            val length = (element.ms * rate / 1000.0).roundToInt()
            when (element) {
                is MorseElement.Tone -> {
                    // Attack inside the element.
                    for (i in 0 until rampSamples) {
                        val index = p + i
                        if (index < env.size) env[index] = maxOf(env[index], rampOn[i])
                    }
                    // Flat body up to the element boundary.
                    for (i in rampSamples until length) {
                        val index = p + i
                        if (index < env.size) env[index] = 1f
                    }
                    // Decay spills past the boundary into the gap, as upstream does.
                    for (i in 0 until rampSamples) {
                        val index = p + length + i
                        if (index < env.size) env[index] = maxOf(env[index], rampOff[i])
                    }
                }
                is MorseElement.Gap -> Unit // stays zero
            }
            p += length
        }
        // Guarantee a silent first and last sample.
        if (env.isNotEmpty()) {
            env[0] = 0f
            env[env.size - 1] = 0f
        }
        return env
    }

    /**
     * Renders [elements] as a mono float signal at [pitchHz].
     *
     * [amplitude] scales the result; 1.0 is full scale.
     */
    fun render(
        elements: List<MorseElement>,
        pitchHz: Double,
        amplitude: Double = 1.0,
    ): FloatArray {
        val env = envelope(elements)
        val out = FloatArray(env.size)
        val step = 2.0 * PI * pitchHz / rate
        for (i in env.indices) {
            out[i] = (env[i] * amplitude * sin(step * i)).toFloat()
        }
        return out
    }

    companion object {
        /** 48 kHz per spec §7; upstream runs at 11025. */
        const val DEFAULT_RATE = 48_000

        /** `VCL/MorseKey.pas:80`. */
        const val DEFAULT_RISE_TIME_MS = 5.0

        /** Blackman-Harris coefficients, `VCL/MorseKey.pas:120-122`. */
        private const val A0 = 0.35875
        private const val A1 = 0.48829
        private const val A2 = 0.14128
        private const val A3 = 0.01168

        /** `VCL/MorseKey.pas:129-146`: kernel, integrate, normalise. */
        fun blackmanHarrisStepResponse(length: Int): FloatArray {
            require(length > 0) { "ramp length must be positive: $length" }
            val ramp = FloatArray(length)
            for (i in 0 until length) {
                val x = i.toDouble() / length
                ramp[i] = (
                    A0 - A1 * cos(2 * PI * x) + A2 * cos(4 * PI * x) - A3 * cos(6 * PI * x)
                    ).toFloat()
            }
            for (i in 1 until length) ramp[i] += ramp[i - 1]
            val scale = 1f / ramp[length - 1]
            for (i in 0 until length) ramp[i] *= scale
            return ramp
        }
    }
}

/** Converts a mono float signal in -1..1 to little-endian 16-bit PCM. */
fun FloatArray.toPcm16(): ShortArray {
    val pcm = ShortArray(size)
    for (i in indices) {
        val clamped = this[i].coerceIn(-1f, 1f)
        pcm[i] = (clamped * Short.MAX_VALUE).roundToInt().toShort()
    }
    return pcm
}
