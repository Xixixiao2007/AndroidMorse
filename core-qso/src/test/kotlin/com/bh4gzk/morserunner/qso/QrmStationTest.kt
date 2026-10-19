package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.SeededRandom
import com.bh4gzk.morserunner.data.CallPool
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The other operator's CW signal (spec §7's **QRM: other CW signals off-frequency**).
 *
 * Modelled on `QrmStn.pas`: `TQrmStation` is an ordinary station that transmits a fixed
 * set of interruptions — `QRL?`, a doubled `QRL?`, a long CQ, or a `QSY` addressed to the
 * operator — at 30–50 WPM, a little off the operator's frequency, with `1 + Random(5)`
 * transmissions of patience before it disappears. It never enters the QSO state machine;
 * it is noise with a callsign.
 */
class QrmStationTest {

    /** Upstream `Tst.PickCallOnly`: the call comes from the contest's own pool. */
    private val pool = object : CallPool {
        override val size: Int = 3
        override fun callAt(index: Int): String = listOf("K1ABC", "DL2XYZ", "JA3QRS")[index]
    }

    private fun create(seed: Long, hisCall: String = "W1AW", basePitchHz: Int = 600) =
        QrmStation.create(SeededRandom(seed), basePitchHz = basePitchHz, hisCall = hisCall, pool = pool)

    @Test
    fun `the seven-way message draw is upstream's case statement`() {
        // QrmStn.pas:45-49: 0 -> QRL?, 1,2 -> QRL? QRL?, 3,4,5 -> long CQ, 6 -> QSY.
        assertEquals(QrmMessage.QRL, qrmMessageFor(0))
        assertEquals(QrmMessage.QRL2, qrmMessageFor(1))
        assertEquals(QrmMessage.QRL2, qrmMessageFor(2))
        assertEquals(QrmMessage.LONG_CQ, qrmMessageFor(3))
        assertEquals(QrmMessage.LONG_CQ, qrmMessageFor(4))
        assertEquals(QrmMessage.LONG_CQ, qrmMessageFor(5))
        assertEquals(QrmMessage.QSY, qrmMessageFor(6))
    }

    @Test
    fun `the station sits off frequency by an audible amount`() {
        for (seed in 1L..200L) {
            val station = create(seed)
            val offset = abs(station.pitchHz - 600)
            assertTrue(
                offset in QrmStation.MIN_OFFSET_HZ..QrmStation.MAX_OFFSET_HZ,
                "seed $seed put the QRM at ${station.pitchHz} Hz, $offset Hz away",
            )
        }
    }

    @Test
    fun `its speed, level and patience are upstream's draws`() {
        for (seed in 1L..200L) {
            val station = create(seed)
            assertTrue(station.speedWpm in 30..50, "seed $seed: QRM speed ${station.speedWpm} WPM")
            // `5000 + 25000 * Random`, normalised by the roster's 45 000 full scale.
            assertTrue(station.level in 0.111..0.667, "seed $seed: QRM level ${station.level}")
            assertTrue(station.transmissions in 1..5, "seed $seed: patience ${station.transmissions}")
        }
    }

    @Test
    fun `the first transmission is the drawn message, the rest are long CQs`() {
        val firstMessages = mutableSetOf<QrmMessage>()
        for (seed in 1L..200L) {
            val station = create(seed)
            val call = station.call
            val longCq = "CQ CQ TEST $call $call TEST"
            val first = station.texts.first()
            val kind = when (first) {
                "QRL?" -> QrmMessage.QRL
                "QRL?   QRL?" -> QrmMessage.QRL2
                longCq -> QrmMessage.LONG_CQ
                "W1AW  QSY QSY" -> QrmMessage.QSY
                else -> error("seed $seed: unknown first transmission '$first'")
            }
            firstMessages += kind
            if (kind == QrmMessage.LONG_CQ) {
                assertEquals(longCq, first, "seed $seed: the long CQ does not name the station")
            }
            for (later in station.texts.drop(1)) {
                assertEquals(longCq, later, "seed $seed: a retry is not the long CQ")
            }
            assertEquals(station.transmissions, station.texts.size)
        }
        assertEquals(
            setOf(QrmMessage.QRL, QrmMessage.QRL2, QrmMessage.LONG_CQ, QrmMessage.QSY),
            firstMessages,
            "not every message of the draw appeared over 200 seeds",
        )
    }

    @Test
    fun `the callsigns in the text are the sender's and the operator's`() {
        val station = create(seed = 42)
        for (text in station.texts) {
            assertTrue(text.contains(station.call) || text.startsWith("QRL"), "text '$text' names no station")
            assertTrue("W1AW" !in text || text.endsWith("QSY QSY"), "text '$text' names the wrong operator")
        }
        // The QSY branch is addressed to the operator; find a seed that draws it.
        val qsy = (1L..500L).map(::create).first { it.texts.first().endsWith("QSY QSY") }
        assertEquals("W1AW  QSY QSY", qsy.texts.first())
        assertTrue(qsy.texts.first().startsWith("W1AW"), "the QSY does not address the operator")
    }

    @Test
    fun `the same seed replays the same QRM`() {
        val first = create(seed = 7)
        val again = create(seed = 7)
        assertEquals(first.call, again.call)
        assertEquals(first.speedWpm, again.speedWpm)
        assertEquals(first.pitchHz, again.pitchHz)
        assertEquals(first.level, again.level)
        assertEquals(first.texts, again.texts)
    }

    @Test
    fun `the gap between transmissions is four seconds plus or minus two`() {
        // QrmStn.pas:57: `Timeout := Round(RndGaussLim(SecondsToBlocks(4), SecondsToBlocks(2)))`.
        val random = SeededRandom(11)
        val gaps = List(1_000) { QrmStation.gapMs(random) }
        for (gap in gaps) assertTrue(gap in 2_000L..6_000L, "gap ${gap}ms outside 4 s ± 2 s")
        assertEquals(4_000.0, gaps.average(), 200.0, "the gap's mean drifted from 4 s")
    }
}
