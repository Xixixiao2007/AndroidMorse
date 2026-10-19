package com.bh4gzk.morserunner.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge between a station's QSB parameters (from the roster) and the mixer's gain
 * schedule. The randomness is *handed in*, so the schedule is reproducible from the
 * roster's seed and this module needs no generator of its own.
 */
class FadeScheduleTest {

    private val rate = 48_000
    private val twentySeconds = rate * 20

    private fun schedule(
        bandwidthHz: Double,
        samples: Int = twentySeconds,
        depth: Double = 0.9,
        seed: Long = 7L,
    ): FloatArray? {
        val source = java.util.Random(seed)
        return FadeSchedule.generate(bandwidthHz, rate, samples, depth) { source.nextDouble() }
    }

    @Test
    fun `the correlation length is upstream's for the QSB and flutter ranges`() {
        // Ceil(0.37 * 11025 / (128 * bandwidth))
        assertEquals(319, FadeSchedule.correlationSteps(0.1))
        assertEquals(54, FadeSchedule.correlationSteps(0.6))
        assertEquals(11, FadeSchedule.correlationSteps(3.0), "flutter has to fade much faster")
        // ceil(0.37 * 11025 / (128 * 33)) = ceil(0.97) = 1 step: flutter at the top of
        // its range is faster than one gain update per step can express, so it clamps.
        assertEquals(1, FadeSchedule.correlationSteps(33.0))
    }

    @Test
    fun `the step size is upstream's BufSize div 4`() {
        // round(128 / 11025 * 48000) = 557 samples = 11.61 ms
        assertEquals(557, FadeSchedule.stepSamples(rate))
        assertEquals(128.0 / 11_025.0, FadeSchedule.STEP_SECONDS, 1e-15)
    }

    @Test
    fun `no depth or no samples produces no schedule`() {
        assertNull(schedule(0.3, depth = 0.0), "a steady carrier still got a fade schedule")
        assertNull(schedule(0.3, samples = 0))
    }

    @Test
    fun `the same seed produces the same fade, a different seed does not`() {
        val first = schedule(0.2, seed = 99)!!
        val again = schedule(0.2, seed = 99)!!
        for (i in first.indices) assertEquals(first[i], again[i], "fade step $i differs")

        val other = schedule(0.2, seed = 100)!!
        val equal = first.indices.count { abs(first[it] - other[it]) < 1e-6 }
        assertTrue(equal < first.size / 2, "$equal fade steps repeated; the seed is not driving the draw")
    }

    @Test
    fun `gains stay inside the depth envelope`() {
        for (bandwidth in listOf(0.1, 0.3, 0.6)) {
            for (depth in listOf(0.5, 0.8, 1.0)) {
                val targets = schedule(bandwidth, depth = depth)!!
                val floor = 1.0 - depth
                for ((i, gain) in targets.withIndex()) {
                    assertTrue(
                        gain >= floor - 1e-6 && gain <= 1.0 + 1e-6,
                        "bandwidth $bandwidth depth $depth step $i is $gain, outside [$floor, 1]",
                    )
                }
            }
        }
    }

    @Test
    fun `the fade is slow, not noise`() {
        val targets = schedule(0.1)!!
        // A slow fade is a wandering trend: over 20 s and a 3.7 s correlation time it should
        // turn around a handful of times, while per-step noise would turn hundreds of times.
        var turns = 0
        for (i in 1 until targets.size - 1) {
            val before = targets[i] - targets[i - 1]
            val after = targets[i + 1] - targets[i]
            if (before * after < 0f) turns++
        }
        assertTrue(turns < 40, "the gain turned $turns times in 20 s — that is noise, not QSB")
        for (i in 1 until targets.size) {
            val step = abs(targets[i] - targets[i - 1])
            assertTrue(step < 0.2f, "gain jumped by $step at step $i — that is a discontinuity")
        }
    }

    @Test
    fun `the fade varies audibly over a few seconds`() {
        val targets = schedule(0.1)!!
        val min = targets.min()
        val max = targets.max()
        assertTrue(max - min > 0.4f, "gain only moved between $min and $max — not an audible fade")
        assertTrue(max > 0.85f, "the station is never near full strength: max $max")
    }

    @Test
    fun `the schedule does not open inside a fade`() {
        val targets = schedule(0.1, depth = 1.0)!!
        assertTrue(targets[0] > 0.2f, "the first fade step is ${targets[0]} — every message would open faded")
    }

    @Test
    fun `flutter fades faster than QSB`() {
        val qsb = schedule(0.1)!!
        val flutter = schedule(6.0)!!
        fun turns(values: FloatArray): Int = (1 until values.size - 1).count {
            (values[it] - values[it - 1]) * (values[it + 1] - values[it]) < 0f
        }
        assertTrue(turns(flutter) > turns(qsb) * 3, "flutter ${turns(flutter)} vs QSB ${turns(qsb)} turns")
    }

    @Test
    fun `station seeds do not collide`() {
        val calls = listOf("DL2XYZ", "JA1ABC", "W7SST", "VK2ABC", "KH6XX", "VE3ABC", "F/DJ4MZ", "K1ABC")
        val seeds = calls.map { FadeSchedule.seedFor(2026L, it) }
        assertEquals(8, seeds.toSet().size, "two stations of one session share a fade stream")
        assertEquals(FadeSchedule.seedFor(2026L, "DL2XYZ"), FadeSchedule.seedFor(2026L, "DL2XYZ"))
        assertNotEquals(FadeSchedule.seedFor(2026L, "DL2XYZ"), FadeSchedule.seedFor(2027L, "DL2XYZ"))
    }
}
