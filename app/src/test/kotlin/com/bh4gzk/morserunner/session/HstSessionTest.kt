package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.data.CallPool
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.morse.MorseWeight
import com.bh4gzk.morserunner.qso.QsoLog
import com.bh4gzk.morserunner.qso.QsoMessage
import com.bh4gzk.morserunner.qso.QsoState
import com.bh4gzk.morserunner.settings.SessionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T16's end-to-end check in the app's own engine: an **HST** session driven exactly as the
 * screen drives it (entry, `Enter`, the station's reply, the log).
 *
 * `HstTest`/`HstRulesTest` prove the document and the rules; this proves the app runs them and
 * that the number the operator watches is the **sum of the worked calls' Morse weights** — not
 * `1 point × prefix count`. It also pins the two inherited behaviours: the running serial (T15,
 * `Log.pas:766`) and the resume that continues it from the log (T9).
 */
class HstSessionTest {

    private val hst: ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("hst"), "hst did not load").config

    /** HST scores by the call alone; nothing here needs a DXCC lookup. */
    private val facts: CallFacts = CallFacts.Unavailable

    /** HST has no call history: `MASTER.DTA` is the pool and nothing else (`CqWpx.pas:87-97`). */
    private val pool = object : CallPool {
        override val size: Int = 3
        override fun callAt(index: Int): String = listOf("K1ABC", "W1AW", "JA1ABC")[index]
    }

    private fun session(seed: Long = 7L, log: QsoLog = QsoLog()): SessionController =
        SessionController.start(
            config = hst,
            pool = pool,
            history = null,
            autocompleteCalls = emptyList(),
            calls = facts,
            myCall = "W1AW",
            // Ini.pas:143 `ExchDefault: '5NN #'`; Main.pas:1523-1524 starts HST's NR at 1.
            myExchange = mapOf("rst" to "5NN", "nr" to "1"),
            seed = seed,
            startMs = 0L,
            durationMinutes = SessionSettings.DEFAULT_DURATION_MINUTES,
            log = log,
        )

    @Test
    fun `the session is HST, and my first serial is 001`() {
        val ui = session().uiState
        assertEquals("HST (High Speed Test)", ui.contestName)
        assertEquals(listOf("RST", "Nr."), ui.exchangeLabels)
        assertEquals("5NN001", ui.myExchange)
        val pieces = ui.stationExchange.split(' ')
        assertEquals(2, pieces.size, "the station sends its report and its serial: ${ui.stationExchange}")
        assertEquals("599", pieces[0], "CqWpx.pas:323-326 `getExch1` is the literal '599'")
        assertTrue(pieces[1].toInt() in 1..176, "the DX serial is outside Ini.pas:234's range")
    }

    @Test
    fun `CQ is upstream's base wording, because HST reuses TCqWpx`() {
        // Main.pas:505 maps scHst to TCqWpx; CqWpx.pas has no SendMsg override.
        val controller = session()
        controller.tick(0)
        controller.send(QsoMessage.CQ)
        assertEquals("CQ W1AW TEST", controller.drainCues().single().text)
    }

    @Test
    fun `two contacts score the sum of their calls' weights`() {
        // The number the operator watches. Pool: K1ABC (63), W1AW (49), JA1ABC (75) — the first
        // two callers' weights are added, and the constant multiplier does not change the total.
        val controller = session()
        val expected = mutableListOf<Int>()
        repeat(2) {
            controller.tick(0)
            val call = controller.uiState.stationCall
            work(controller)
            expected += MorseWeight.of(call)
        }

        assertEquals(2, controller.uiState.qsos)
        assertEquals(expected.sum(), controller.uiState.rawPoints, "HST's raw points are a sum of weights")
        assertEquals(1, controller.uiState.multipliers, "one constant multiplier key, not a prefix list")
        assertEquals(expected.sum(), controller.uiState.rawScore, "points × 1 is still the sum")
        assertEquals(expected.sum(), controller.uiState.verifiedScore, "both copies were clean")
        // Nothing like a point per contact: the weights are tens, not ones.
        assertTrue(controller.uiState.rawPoints > 2, "HST does not score one point per contact")
    }

    @Test
    fun `the serial advances once per logged contact`() {
        // F8 in the app's engine (Log.pas:766 `Inc(Tst.Me.NR)`); HST starts at 1.
        val controller = session()
        work(controller)
        assertEquals(1, controller.uiState.qsos)
        assertEquals("5NN002", controller.uiState.myExchange)
        work(controller)
        assertEquals(2, controller.uiState.qsos)
        assertEquals("5NN003", controller.uiState.myExchange)
    }

    @Test
    fun `a resumed session continues the serial from the log`() {
        val first = session()
        work(first)
        work(first)
        val restored = QsoLog()
        first.loggedRecords().forEach { restored.record(it) }

        val resumed = session(seed = 31L, log = restored)
        assertEquals(2, resumed.uiState.qsos, "the restored log is the session's log")
        assertEquals("5NN003", resumed.uiState.myExchange, "the next contact sends 003")
    }

    @Test
    fun `the same seed and settings replay the same HST session`() {
        fun run(): List<String> {
            val controller = session(seed = 31L)
            controller.tick(0)
            controller.editCall(controller.uiState.stationCall)
            controller.enter()
            drainMyTransmission(controller)
            val reply = controller.drainCues().single().text
            return listOf(
                controller.uiState.stationCall,
                controller.uiState.stationExchange,
                reply,
                controller.uiState.myExchange,
            )
        }
        assertEquals(run(), run(), "the HST session is not deterministic")
    }

    /**
     * Works the station on the air: copy its call and its two pieces, `Enter`, then let the
     * contact finish so the station leaves and the next caller is drawn.
     */
    private fun work(controller: SessionController) {
        controller.tick(0)
        val station = controller.uiState.stationCall
        val (rst, serial) = controller.uiState.stationExchange.split(' ')
        controller.editCall(station)
        controller.enter()
        drainMyTransmission(controller)
        controller.drainCues()

        controller.editExchange1(rst)
        controller.editExchange2(serial)
        controller.enter()
        val tu = controller.drainCues().single()
        assertEquals("TU", tu.text, "no override: upstream's base TU is the bare word")
        assertEquals(QsoState.Logged, controller.uiState.qsoState)
        controller.tick(controller.lastNowMs + tu.durationMs + 1)
    }

    /** Lets my own transmission finish, cue by cue, until the queue is empty. */
    private fun drainMyTransmission(controller: SessionController) {
        var guard = 0
        while (controller.uiState.pendingText.isNotEmpty() && guard++ < 10) {
            val cue = controller.drainCues().firstOrNull() ?: break
            controller.tick(controller.lastNowMs + cue.durationMs + 1)
        }
        controller.tick(controller.lastNowMs + 2_000)
    }
}
