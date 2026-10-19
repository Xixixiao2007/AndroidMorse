package com.bh4gzk.morserunner.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The block mixer (spec §7): one output stream, N stations summed, rendered in fixed
 * blocks by a dedicated thread, with nothing allocated per block.
 *
 * The proofs this file is responsible for:
 *  - a station sounds the same alone as it does through T4's single-signal path;
 *  - two stations are **summed**, not played one after the other (exact linearity);
 *  - both carriers are present at the same instant (Goertzel energy at each pitch);
 *  - the mix cannot leave full scale;
 *  - the output does not depend on how the stream is cut into blocks;
 *  - rendering allocates nothing (measured, not asserted).
 */
class CwMixerTest {

    private val rate = 48_000

    private fun steady(frames: Int) = FloatArray(frames) { 1f }

    /** Magnitude of [hz] in [samples] (Goertzel); enough to prove a tone is present. */
    private fun toneAt(samples: FloatArray, frames: Int, hz: Double): Double {
        val w = 2.0 * PI * hz / rate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until frames) {
            val s0 = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt(abs(s1 * s1 + s2 * s2 - coeff * s1 * s2)) / frames
    }

    @Test
    fun `one station through the mixer is identical to the single-signal path`() {
        val elements = MorseEncoder.encode("CQ TEST", MorseTiming(25))
        val envelope = CwSynthesizer(rate).envelope(elements)
        val expected = CwSynthesizer(rate).render(elements, pitchHz = 600.0, amplitude = 1.0)

        val mixer = CwMixer(sampleRate = rate, masterGain = 1.0)
        assertTrue(mixer.start(CwVoice(id = 1, pitchHz = 600.0, envelope = envelope, level = 1.0)))
        val actual = FloatArray(expected.size)
        mixer.render(actual, expected.size)

        assertEquals(expected.size, envelope.size, "the envelope and the rendered signal disagree")
        for (i in expected.indices) {
            assertEquals(expected[i].toDouble(), actual[i].toDouble(), 1e-6, "sample $i differs")
        }
    }

    @Test
    fun `two stations are summed, not sequenced`() {
        val frames = 8192
        val a = CwVoice(id = 1, pitchHz = 600.0, envelope = steady(frames), level = 0.3)
        val b = CwVoice(id = 2, pitchHz = 900.0, envelope = steady(frames), level = 0.3)

        val aloneA = renderOne(a, frames)
        val aloneB = renderOne(b, frames)
        val together = renderTwo(a, b, frames)

        for (i in 0 until frames) {
            assertEquals(
                (aloneA[i] + aloneB[i]).toDouble(),
                together[i].toDouble(),
                1e-7,
                "sample $i is not the sum of the two stations",
            )
        }
    }

    @Test
    fun `both carriers sound at the same instant`() {
        val frames = 48_000
        val mixer = CwMixer(sampleRate = rate, masterGain = 1.0)
        mixer.start(CwVoice(id = 1, pitchHz = 600.0, envelope = steady(frames), level = 0.5))
        mixer.start(CwVoice(id = 2, pitchHz = 900.0, envelope = steady(frames), level = 0.5))
        val out = FloatArray(frames)
        mixer.render(out, frames)

        val at600 = toneAt(out, frames, 600.0)
        val at900 = toneAt(out, frames, 900.0)
        val at750 = toneAt(out, frames, 750.0)

        assertTrue(at600 > 0.2, "600 Hz is missing from the mix: $at600")
        assertTrue(at900 > 0.2, "900 Hz is missing from the mix: $at900")
        assertTrue(at750 < 0.02, "there is energy at 750 Hz, which no station transmits: $at750")
    }

    @Test
    fun `the full pile-up stays inside full scale`() {
        val frames = 4096
        val mixer = CwMixer(sampleRate = rate, maxVoices = 8, masterGain = 1.0 / 8)
        for (id in 0 until 8) {
            mixer.start(
                CwVoice(id = id, pitchHz = 500.0 + id * 50, envelope = steady(frames), level = 1.0),
            )
        }
        val out = FloatArray(frames)
        mixer.render(out, frames)

        val peak = out.maxOf { abs(it) }
        assertTrue(peak <= 1.0f, "the mix clipped at $peak")
        assertTrue(peak > 0.25f, "eight full-level stations summed to only $peak")
    }

    @Test
    fun `an over-driven mix is clamped instead of wrapping around`() {
        val frames = 2048
        val mixer = CwMixer(sampleRate = rate, maxVoices = 8, masterGain = 1.0)
        for (id in 0 until 8) {
            mixer.start(CwVoice(id = id, pitchHz = 600.0, envelope = steady(frames), level = 1.0))
        }
        val out = FloatArray(frames)
        mixer.render(out, frames)

        assertTrue(out.maxOf { abs(it) } <= 1.0f, "the mix left full scale")
        assertTrue(out.any { it >= 1.0f - 1e-6f }, "the clamp never engaged, so this test proves nothing")
    }

    @Test
    fun `the output does not depend on how the stream is cut into blocks`() {
        val frames = 48_000
        val voice = CwVoice(
            id = 1,
            pitchHz = 712.5,
            envelope = MorseEncoder.encode("TEST DE W7SST", MorseTiming(22)).let { elements ->
                CwSynthesizer(rate).envelope(elements)
            }.let { env -> if (env.size >= frames) env else env + FloatArray(frames - env.size) },
            level = 0.8,
            gainTargets = FloatArray(40) { 0.6f + 0.4f * (it % 3) },
            gainStepSamples = 1200,
        )

        val oneBlock = FloatArray(frames)
        CwMixer(sampleRate = rate, masterGain = 1.0).let {
            it.start(voice)
            it.render(oneBlock, frames)
        }

        val chunked = FloatArray(frames)
        CwMixer(sampleRate = rate, masterGain = 1.0).let { mixer ->
            mixer.start(voice)
            var offset = 0
            while (offset < frames) {
                val n = minOf(512, frames - offset)
                val block = FloatArray(n)
                mixer.render(block, n)
                block.copyInto(chunked, offset)
                offset += n
            }
        }

        for (i in 0 until frames) {
            assertEquals(oneBlock[i].toDouble(), chunked[i].toDouble(), 1e-7, "sample $i depends on the block size")
        }
    }

    @Test
    fun `a fade target schedule modulates the level`() {
        val frames = 4800
        val voice = CwVoice(
            id = 1,
            pitchHz = 600.0,
            envelope = steady(frames),
            level = 1.0,
            gainTargets = floatArrayOf(1.0f, 1.0f, 0.2f, 0.2f),
            gainStepSamples = 1200,
        )
        val mixer = CwMixer(sampleRate = rate, masterGain = 1.0)
        mixer.start(voice)
        val out = FloatArray(frames)
        mixer.render(out, frames)

        val early = out.copyOfRange(100, 1100).maxOf { abs(it) }
        val late = out.copyOfRange(3700, 4700).maxOf { abs(it) }
        assertTrue(early > 0.9f, "the station never reached full level: $early")
        assertTrue(late < 0.3f, "the fade did not attenuate the station: $late")
    }

    @Test
    fun `a voice frees its slot when its envelope runs out`() {
        val mixer = CwMixer(sampleRate = rate, maxVoices = 2, masterGain = 1.0)
        assertEquals(0, mixer.activeVoices)
        mixer.start(CwVoice(id = 1, pitchHz = 600.0, envelope = steady(100), level = 1.0))

        // start() only queues the voice; the audio thread assigns the slot on the next
        // block, so activity becomes observable only after a render.
        val first = FloatArray(50)
        mixer.render(first, 50)
        assertEquals(1, mixer.activeVoices, "the queued voice never became active")
        assertTrue(first.any { it != 0f }, "the active voice rendered silence")

        val rest = FloatArray(150)
        mixer.render(rest, 150)

        assertEquals(0, mixer.activeVoices, "a finished voice kept its slot")
        // The last 50 samples of the envelope belong to the second block; everything
        // after them must be silence, because the voice has ended.
        assertTrue(rest.copyOfRange(50, 150).all { it == 0f }, "a finished voice kept transmitting")
    }

    @Test
    fun `stop and stopAll take effect on the next block`() {
        val mixer = CwMixer(sampleRate = rate, maxVoices = 4, masterGain = 1.0)
        mixer.start(CwVoice(id = 1, pitchHz = 600.0, envelope = steady(4800), level = 1.0))
        mixer.start(CwVoice(id = 2, pitchHz = 900.0, envelope = steady(4800), level = 1.0))
        val out = FloatArray(512)
        mixer.render(out, 512)
        assertEquals(2, mixer.activeVoices)

        assertTrue(mixer.stop(1))
        mixer.render(out, 512)
        assertEquals(1, mixer.activeVoices)

        assertTrue(mixer.stopAll())
        mixer.render(out, 512)
        assertEquals(0, mixer.activeVoices)
    }

    @Test
    fun `starting more stations than slots keeps the mixer consistent`() {
        val mixer = CwMixer(sampleRate = rate, maxVoices = 2, masterGain = 1.0)
        assertTrue(mixer.start(CwVoice(id = 1, pitchHz = 600.0, envelope = steady(4800), level = 1.0)))
        assertTrue(mixer.start(CwVoice(id = 2, pitchHz = 700.0, envelope = steady(4800), level = 1.0)))
        // start() only reports whether the *control path* accepted the command; slot
        // arbitration happens on the audio thread, where blocking is forbidden.
        assertTrue(mixer.start(CwVoice(id = 3, pitchHz = 800.0, envelope = steady(4800), level = 1.0)))
        val out = FloatArray(256)
        mixer.render(out, 256)
        assertEquals(2, mixer.activeVoices, "more stations rendered than the mixer has slots")
        assertEquals(1, mixer.droppedVoices, "the surplus station was not counted")
    }

    @Test
    fun `the render path allocates nothing`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported, "this JVM cannot measure per-thread allocation")

        val frames = 512
        val mixer = CwMixer(sampleRate = rate, maxVoices = 4, masterGain = 1.0 / 8)
        // One read-only envelope shared by all four voices, long enough to outlast
        // warm-up plus measurement (2000 + 5000 blocks x 512 = 3.58 M samples).
        val longEnvelope = FloatArray(rate * 90) { 1f }
        for (id in 0 until 4) {
            mixer.start(
                CwVoice(
                    id = id,
                    pitchHz = 600.0 + id * 40,
                    envelope = longEnvelope,
                    level = 0.8,
                    gainTargets = FloatArray(64) { 0.7f },
                    gainStepSamples = 1200,
                ),
            )
        }
        val out = FloatArray(frames)
        val thread = Thread.currentThread().id

        // Warm up: JIT tiering and first-call class loading are not the audio path.
        repeat(2_000) { mixer.render(out, frames) }

        val before = bean.getThreadAllocatedBytes(thread)
        repeat(5_000) { mixer.render(out, frames) }
        val allocated = bean.getThreadAllocatedBytes(thread) - before

        // 5000 blocks = 2.56 M samples; a single allocation per block would already be
        // thousands of objects, and per-sample boxing would be millions.
        assertTrue(allocated < 64 * 1024, "the audio path allocated $allocated bytes over 5000 blocks")
    }

    @Test
    fun `the noise bed is summed into the mix, sample for sample`() {
        // A quiet bed, so nothing reaches full scale and the sum stays linear.
        fun bed() = NoiseBed(sampleRate = rate, bandPeak = 0.003, impulseGain = 10.0, seed = 4)
        val frames = 2_048

        val noise = FloatArray(frames)
        bed().render(noise, frames)

        val voice = CwVoice(
            id = 1,
            pitchHz = 600.0,
            envelope = FloatArray(frames) { 1f },
            level = 0.3,
        )
        val station = renderOne(voice, frames)

        val mixer = CwMixer(sampleRate = rate, masterGain = 1.0, bed = bed())
        assertTrue(mixer.start(voice))
        val mixed = FloatArray(frames)
        mixer.render(mixed, frames)

        for (i in 0 until frames) {
            assertEquals(
                station[i].toDouble() + noise[i].toDouble(),
                mixed[i].toDouble(),
                1e-6,
                "sample $i is not station + band",
            )
        }
    }

    @Test
    fun `a mixer with a bed is silent only when the bed is`() {
        val frames = 1_024

        val noisy = FloatArray(frames)
        CwMixer(sampleRate = rate, masterGain = 1.0, bed = NoiseBed(sampleRate = rate, seed = 8))
            .render(noisy, frames)
        var energy = 0.0
        for (value in noisy) energy += value.toDouble() * value
        assertTrue(energy > 0.0, "the band is dead: a QRN session would sound like a clean generator")

        val silent = FloatArray(frames)
        CwMixer(sampleRate = rate, masterGain = 1.0).render(silent, frames)
        for (value in silent) assertEquals(0f, value, "a mixer without a bed must be silent")
    }

    @Test
    fun `the render path allocates nothing with a noise bed`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported, "this JVM cannot measure per-thread allocation")

        val frames = 512
        val mixer = CwMixer(
            sampleRate = rate,
            maxVoices = 4,
            masterGain = 1.0 / 8,
            bed = NoiseBed(sampleRate = rate, seed = 9),
        )
        val longEnvelope = FloatArray(rate * 90) { 1f }
        for (id in 0 until 4) {
            mixer.start(
                CwVoice(
                    id = id,
                    pitchHz = 600.0 + id * 40,
                    envelope = longEnvelope,
                    level = 0.8,
                    gainTargets = FloatArray(64) { 0.7f },
                    gainStepSamples = 1200,
                ),
            )
        }
        val out = FloatArray(frames)
        val thread = Thread.currentThread().id

        repeat(2_000) { mixer.render(out, frames) }
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(5_000) { mixer.render(out, frames) }
        val allocated = bean.getThreadAllocatedBytes(thread) - before

        assertTrue(allocated < 64 * 1024, "the audio path allocated $allocated bytes over 5000 blocks")
    }

    @Test
    fun `pcm conversion clamps instead of wrapping around`() {
        val from = floatArrayOf(0f, 0.25f, -0.25f, 2f, -2f)
        val into = ShortArray(5)
        Pcm16.convert(from, 5, into)

        assertEquals(0, into[0].toInt())
        assertEquals(8192, into[1].toInt())
        assertEquals(-8192, into[2].toInt())
        assertEquals(Short.MAX_VALUE.toInt(), into[3].toInt(), "an overdriven sample did not clamp")
        assertEquals(-Short.MAX_VALUE.toInt(), into[4].toInt(), "a negative overdrive did not clamp")
        assertEquals(32767, Pcm16.peakAbs(into, 5), "peak telemetry disagrees with the samples")
    }

    private fun renderOne(voice: CwVoice, frames: Int): FloatArray {
        val mixer = CwMixer(sampleRate = rate, masterGain = 1.0)
        mixer.start(voice)
        val out = FloatArray(frames)
        mixer.render(out, frames)
        return out
    }

    private fun renderTwo(a: CwVoice, b: CwVoice, frames: Int): FloatArray {
        val mixer = CwMixer(sampleRate = rate, masterGain = 1.0)
        mixer.start(a)
        mixer.start(b)
        val out = FloatArray(frames)
        mixer.render(out, frames)
        return out
    }
}
