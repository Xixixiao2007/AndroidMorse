package com.bh4gzk.morserunner.audio

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The band's background noise (spec §7's QRN: *impulse + band noise*).
 *
 * Upstream `Contest.pas:695-716` and `QrnStn.pas` make it in two layers: a per-sample
 * uniform bed, plus a 1 %-dense impulse train whose amplitude is 60× the bed — and, now
 * and then, a burst (`TQrnStation`) of much louder impulses for up to a second. This
 * file proves the statistics (floor, impulse rate, burst rarity), the determinism the
 * session's replay contract needs, and that the render path still allocates nothing.
 */
class NoiseBedTest {

    private val rate = 48_000

    private fun render(bed: NoiseBed, samples: Int, framesPerBlock: Int = 512): FloatArray {
        val out = FloatArray(samples)
        val block = FloatArray(framesPerBlock)
        var done = 0
        while (done < samples) {
            val frames = minOf(framesPerBlock, samples - done)
            java.util.Arrays.fill(block, 0f)
            bed.render(block, frames)
            block.copyInto(out, done, 0, frames)
            done += frames
        }
        return out
    }

    /** Normalised autocorrelation at [lag]; a tone is ~1 at lag 1, white noise ~0. */
    private fun correlation(samples: FloatArray, lag: Int): Double {
        var product = 0.0
        var energy = 0.0
        for (i in samples.indices) energy += samples[i].toDouble() * samples[i]
        for (i in 0 until samples.size - lag) product += samples[i].toDouble() * samples[i + lag]
        return product / energy
    }

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until until) sum += samples[i].toDouble() * samples[i].toDouble()
        return sqrt(sum / (until - from))
    }

    @Test
    fun `the band noise is a flat, centred bed`() {
        val bed = NoiseBed(sampleRate = rate, seed = 11)
        val samples = render(bed, rate)

        var sum = 0.0
        for (value in samples) sum += value
        val mean = sum / samples.size
        assertEquals(0.0, mean, 0.001, "the bed must be centred; a DC offset is a bug, not noise")

        // Uniform in ±bandPeak has RMS = peak / sqrt(3). The impulses sit on top, so
        // measure the quiet majority: |x| below the impulse threshold.
        val quiet = samples.filter { abs(it) < NoiseBed.DEFAULT_BAND_PEAK * 3 }.toFloatArray()
        assertTrue(quiet.size > samples.size * 0.8, "only ${quiet.size} of ${samples.size} samples are quiet")
        assertEquals(NoiseBed.DEFAULT_BAND_PEAK / sqrt(3.0), rms(quiet).toDouble(), 0.0015)

        // "Band noise", not a tone: neighbouring samples are uncorrelated. (A Goertzel bin
        // of one window is Rayleigh-distributed — a 4x ratio between two bins is ordinary
        // for white noise — so the spectrum claim is made the way it is actually true.)
        for (lag in intArrayOf(1, 2, 5, 50)) {
            val rho = correlation(samples, lag)
            assertTrue(abs(rho) < 0.02, "lag $lag correlates at $rho: that is a tone, not band noise")
        }
    }

    @Test
    fun `impulses arrive at the upstream rate and are much louder than the bed`() {
        // No bursts here: this test measures the ordinary impulse train's ceiling.
        val bed = NoiseBed(sampleRate = rate, seed = 12, burstRateHz = 0.0)
        val samples = render(bed, rate)

        val threshold = NoiseBed.DEFAULT_BAND_PEAK * 10
        val impulses = samples.count { abs(it) > threshold }
        // 1 % of upstream's 11 025 Hz clock is 110 impulses/s; the mixer runs at 48 kHz,
        // so the same *audible* rate is 110 per second of output.
        assertTrue(impulses in 60..150, "$impulses impulse samples in one second, expected ~110")

        val peak = samples.maxOf { abs(it) }
        assertTrue(peak <= NoiseBed.DEFAULT_BAND_PEAK * NoiseBed.DEFAULT_IMPULSE_GAIN + 1e-6)
        assertTrue(peak > NoiseBed.DEFAULT_BAND_PEAK * 20, "impulses are too quiet to hear")
    }

    @Test
    fun `a burst is the rare, much louder crackle`() {
        val sixtySeconds = NoiseBed(sampleRate = rate, seed = 13)
        val samples = render(sixtySeconds, rate * 60, framesPerBlock = 1_024)

        val quietPeak = NoiseBed.DEFAULT_BAND_PEAK * NoiseBed.DEFAULT_IMPULSE_GAIN
        val loud = samples.count { abs(it) > quietPeak * 2 }
        assertTrue(loud > 0, "no burst in a minute: QRN has no lightning in it")
        assertTrue(loud < samples.size / 50, "$loud loud samples: bursts stopped being rare")

        // A burst multiplies the impulse amplitude, so its samples exceed the normal peak.
        assertTrue(samples.maxOf { abs(it) } > quietPeak * 2, "the burst is not louder than the bed's clicks")

        // With bursts disabled nothing may exceed the impulse ceiling.
        val noBursts = NoiseBed(sampleRate = rate, seed = 13, burstRateHz = 0.0)
        val plain = render(noBursts, rate * 5)
        // The bed and an impulse are summed, so the ceiling is both peaks together.
        val ceiling = quietPeak + NoiseBed.DEFAULT_BAND_PEAK
        assertTrue(plain.maxOf { abs(it) } <= ceiling + 1e-6, "a burst happened with bursts disabled")
    }

    @Test
    fun `silence is possible and free of NaN`() {
        val silent = NoiseBed(sampleRate = rate, bandPeak = 0.0, impulseRateHz = 0.0, seed = 1)
        val out = FloatArray(1024) { 7f }
        silent.render(out, 1024)
        for (value in out) assertTrue(value == 7f, "an empty bed wrote $value instead of leaving the block alone")
    }

    @Test
    fun `the same seed produces the same noise, a different seed does not`() {
        val first = render(NoiseBed(sampleRate = rate, seed = 99), rate)
        val again = render(NoiseBed(sampleRate = rate, seed = 99), rate)
        for (i in first.indices) assertEquals(first[i], again[i], "sample $i differs between identical beds")

        val other = render(NoiseBed(sampleRate = rate, seed = 100), rate)
        assertTrue(first.indices.count { first[it] != other[it] } > first.size / 2, "the seed does nothing")
    }

    @Test
    fun `the flow of the noise does not depend on the block size`() {
        val whole = render(NoiseBed(sampleRate = rate, seed = 5), 48_000, framesPerBlock = 48_000)
        val chopped = render(NoiseBed(sampleRate = rate, seed = 5), 48_000, framesPerBlock = 111)
        for (i in whole.indices) {
            assertEquals(whole[i].toDouble(), chopped[i].toDouble(), 1e-9, "sample $i depends on the block cuts")
        }
    }

    @Test
    fun `the render path allocates nothing`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported, "this JVM cannot measure per-thread allocation")

        val frames = 512
        val bed = NoiseBed(sampleRate = rate, seed = 3)
        val out = FloatArray(frames)
        val thread = Thread.currentThread().id

        // The mixer hands the bed a freshly zeroed block, as the engine does.
        repeat(2_000) {
            java.util.Arrays.fill(out, 0f)
            bed.render(out, frames)
        }
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(5_000) {
            java.util.Arrays.fill(out, 0f)
            bed.render(out, frames)
        }
        val allocated = bean.getThreadAllocatedBytes(thread) - before

        assertTrue(allocated < 64 * 1024, "noise rendering allocated $allocated bytes over 5000 blocks")
    }
}
