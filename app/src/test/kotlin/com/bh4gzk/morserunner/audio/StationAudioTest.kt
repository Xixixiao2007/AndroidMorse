package com.bh4gzk.morserunner.audio

import com.bh4gzk.morserunner.contest.BehaviourProfile
import com.bh4gzk.morserunner.contest.FadeProfile
import com.bh4gzk.morserunner.contest.GeneratedExchange
import com.bh4gzk.morserunner.contest.RosterStation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The audio half of a roster station: the keyed envelope plus the fade schedule the mixer
 * interpolates (spec §7's QSB and flutter).
 *
 * The point of T8 is that these are **not** dead code: the session hands a fluttering
 * station a fast schedule and a slow QSB station a slow one, and the interfering station a
 * steady carrier. "Sounds real" is the human's ear; this file is the assertion that the
 * gains exist, stay inside the fade depth, and move at the right speed.
 */
class StationAudioTest {

    private val audio = StationAudio(sampleRate = 48_000)

    private fun station(fade: FadeProfile, call: String = "DL1ABC"): RosterStation = RosterStation(
        call = call,
        speedWpm = 25,
        pitchHz = 640,
        level = 0.6,
        fade = fade,
        behaviour = BehaviourProfile.NORMAL,
        dice = emptyMap(),
        exchange = GeneratedExchange("599", "14", emptyMap()),
    )

    /** Mean absolute change between consecutive gain targets — how fast the fade moves. */
    private fun meanStep(targets: FloatArray): Double {
        var sum = 0.0
        for (i in 1 until targets.size) sum += abs(targets[i] - targets[i - 1]).toDouble()
        return sum / (targets.size - 1)
    }

    @Test
    fun `a station's voice carries a fade schedule, at the mixer's step size`() {
        val voice = audio.voice(station(FadeProfile(0.3, flutter = false)), id = 1, text = "CQ TEST", rootSeed = 7)
        val targets = voice.gainTargets
        assertTrue(targets != null, "a QSB station played without a fade: QSB would be inaudible")
        assertEquals(640.0, voice.pitchHz)
        assertEquals(FadeSchedule.stepSamples(48_000), voice.gainStepSamples)
        for ((i, gain) in targets.withIndex()) {
            assertTrue(
                gain >= 1.0 - FadeSchedule.DEFAULT_DEPTH - 1e-6 && gain <= 1.0 + 1e-6,
                "gain $i is $gain, outside the fade depth",
            )
        }
        assertTrue(targets.max() - targets.min() > 0.1, "the fade never moved: ${targets.min()}..${targets.max()}")
    }

    @Test
    fun `flutter moves the gain faster than QSB does`() {
        val slow = audio.voice(station(FadeProfile(0.3, flutter = false)), 1, "CQ TEST", 7).gainTargets!!
        val fast = audio.voice(station(FadeProfile(10.0, flutter = true)), 2, "CQ TEST", 7).gainTargets!!

        val slowStep = meanStep(slow)
        val fastStep = meanStep(fast)
        assertTrue(
            fastStep > slowStep * 2,
            "flutter's gain moved $fastStep per step against QSB's $slowStep: the fast fade is not fast",
        )
    }

    @Test
    fun `the interfering signal is a steady carrier, not a fading station`() {
        // `QrmStn.pas` has no `TQsb`: another operator's signal does not fade like the DX.
        val voice = audio.signal(id = 3, text = "QRL?   QRL?", pitchHz = 780, speedWpm = 38, level = 0.4)
        assertNull(voice.gainTargets, "the QRM signal was given a fade schedule")
        assertEquals(780.0, voice.pitchHz)
        assertEquals(0.4, voice.level)
        assertTrue(voice.envelope.isNotEmpty())

        val slow = audio.signal(id = 4, text = "QRL?   QRL?", pitchHz = 780, speedWpm = 30, level = 0.4)
        val fast = audio.signal(id = 5, text = "QRL?   QRL?", pitchHz = 780, speedWpm = 50, level = 0.4)
        assertTrue(
            fast.envelope.size < slow.envelope.size * 0.8,
            "the signal was not keyed at the interfering station's own speed",
        )
    }

    @Test
    fun `the same station and seed fade the same way`() {
        val fade = FadeProfile(0.2, flutter = false)
        val first = audio.voice(station(fade), 1, "CQ TEST", 99).gainTargets!!
        val again = audio.voice(station(fade), 2, "CQ TEST", 99).gainTargets!!
        for (i in first.indices) assertEquals(first[i], again[i], "fade step $i is not reproducible")

        val other = audio.voice(station(fade, call = "K2XYZ"), 3, "CQ TEST", 99).gainTargets!!
        // Most steps sit at full gain (the schedule is calibrated to unit mean and clipped at
        // the top), so equal *values* prove nothing; the claim is that the fades happen at
        // different times for two stations. Two independent QSB streams do.
        val difference = first.indices.sumOf { abs(first[it] - other[it]).toDouble() } / first.size
        assertTrue(difference > 0.01, "two stations faded in lockstep (mean difference $difference)")
    }
}
