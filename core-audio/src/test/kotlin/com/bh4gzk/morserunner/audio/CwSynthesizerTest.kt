package com.bh4gzk.morserunner.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CwSynthesizerTest {

    private val rate = 48_000
    private val synth = CwSynthesizer(rate = rate)
    private val timing = MorseTiming(20)          // 1U = 62.5 ms

    @Test
    fun `ramp length matches the upstream formula`() {
        // Round(2.7 * 0.005 * 48000) = 648 at the spec rate, and 149 at upstream's 11025.
        assertEquals(648, synth.rampSamples)
        assertEquals(149, CwSynthesizer(rate = 11_025).rampSamples)
    }

    @Test
    fun `blackman harris ramp rises from zero to one monotonically`() {
        val ramp = CwSynthesizer.blackmanHarrisStepResponse(648)

        assertTrue(abs(ramp.first()) < 1e-6f, "ramp starts at ${ramp.first()}")
        assertTrue(abs(ramp.last() - 1f) < 1e-6f, "ramp ends at ${ramp.last()}")
        for (i in 1 until ramp.size) {
            assertTrue(ramp[i] >= ramp[i - 1] - 1e-6f, "ramp fell at $i")
        }
    }

    @Test
    fun `envelope length is the summed element duration`() {
        val elements = MorseEncoder.encode("SOS", timing)
        assertEquals(1937.5, elements.sumOf { it.ms }, 1e-9)
        // 1937.5 ms at 48 kHz = 93 000 samples
        assertEquals(93_000, synth.sampleCount(elements))
        assertEquals(93_000, synth.envelope(elements).size)
    }

    @Test
    fun `envelope starts and ends silent and never exceeds one`() {
        val env = synth.envelope(MorseEncoder.encode("PARIS", timing))

        assertEquals(0f, env.first())
        assertEquals(0f, env.last())
        assertTrue(env.all { it in 0f..1.0001f })
        assertTrue(env.max() in 0.999f..1.001f, "peak ${env.max()}")
    }

    @Test
    fun `envelope has no step discontinuity`() {
        // A click would appear as one large jump between adjacent samples. The
        // steepest legitimate slope is inside the Blackman-Harris ramp, whose peak
        // slope is ~2.8/rampLen (kernel peak 1.0 over a mean of a0).
        val env = synth.envelope(MorseEncoder.encode("EEEE", timing))
        var maxJump = 0f
        for (i in 1 until env.size) maxJump = maxOf(maxJump, abs(env[i] - env[i - 1]))

        assertTrue(maxJump < 3.5f / synth.rampSamples, "max jump $maxJump")
    }

    @Test
    fun `rendered tone oscillates at the requested pitch`() {
        val pitch = 800.0
        val signal = synth.render(listOf(MorseElement.Tone(200.0)), pitch, amplitude = 0.5)

        assertEquals(9_600, signal.size)     // 200 ms at 48 kHz
        // Count positive-going zero crossings across the steady part.
        val from = synth.rampSamples
        val to = signal.size - synth.rampSamples
        var crossings = 0
        for (i in from + 1 until to) {
            if (signal[i - 1] <= 0f && signal[i] > 0f) crossings++
        }
        val seconds = (to - from).toDouble() / rate
        assertEquals(pitch * seconds, crossings.toDouble(), 1.0)
    }

    @Test
    fun `pcm conversion clamps and scales`() {
        val pcm = floatArrayOf(-2f, -1f, 0f, 0.5f, 1f, 2f).toPcm16()

        assertEquals(Short.MIN_VALUE + 1, pcm[0].toInt())   // clamped to -1
        assertEquals(Short.MIN_VALUE + 1, pcm[1].toInt())
        assertEquals(0, pcm[2].toInt())
        assertEquals(16_384, pcm[3].toInt())                // 0.5 * 32767 rounded
        assertEquals(32_767, pcm[4].toInt())
        assertEquals(32_767, pcm[5].toInt())                // clamped to +1
    }
}
