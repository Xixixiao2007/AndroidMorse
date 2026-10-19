package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.contest.CallHistoryFile
import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLoad
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.qso.QsoLog
import com.bh4gzk.morserunner.qso.QsoMessage
import com.bh4gzk.morserunner.qso.QsoState
import com.bh4gzk.morserunner.settings.SessionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T18's end-to-end check in the app's own engine: an **ARRL Field Day** session driven exactly
 * as the screen drives it (entry, `Enter`, the station's reply, the log).
 *
 * The pool is what `SessionBootstrap` hands the session: the contest's own history, loaded by the
 * two-pass club strategy (ADR-0018). Every contact is **2 points and one constant multiplier**
 * (`ArrlFd.pas:479-486`), so a session's score is a plain sum — and the CQ is `CQ FD <my>`
 * (`ArrlFd.pas:425`).
 */
class ArrlFdSessionTest {

    private val arrlfd: ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("arrlfd"), "arrlfd did not load").config

    /**
     * Twelve club stations, one per club, so the two-pass load puts every one of them in the
     * pool (a fixture with home/portable rows would make the pool seed-dependent, which the
     * load's own tests already cover).
     */
    private val fixture = buildString {
        appendLine("!!Order!!,Call,Exch1,Sect,UserText")
        val clubs = listOf("ClubA", "ClubB", "ClubC", "ClubD", "ClubE", "ClubF")
        for (i in 0 until 6) {
            appendLine("AA${i}AA,${i % 3 + 1}A,OR,${clubs[i]}")
            appendLine("BB${i}BB,${i % 2 + 1}A,OR,${clubs[i]}Z")
        }
    }

    private val pool: CallHistoryFile = assertIs<HistoryLoad.Loaded>(
        CallHistoryFile.parse(fixture, arrlfd.callHistory!!, "DL1AA", CallFacts.Unavailable, 7L),
    ).history

    private fun session(seed: Long = 7L, log: QsoLog = QsoLog()): SessionController =
        SessionController.start(
            config = arrlfd,
            pool = pool,
            history = pool,
            autocompleteCalls = emptyList(),
            calls = CallFacts.Unavailable,
            myCall = "DL1AA",
            myExchange = mapOf("class" to "3A", "section" to "OR"),
            seed = seed,
            startMs = 0L,
            durationMinutes = SessionSettings.DEFAULT_DURATION_MINUTES,
            log = log,
        )

    @Test
    fun `the session is Field Day, and my own box is my class and section`() {
        val controller = session()
        assertEquals("ARRL Field Day", controller.uiState.contestName)
        assertEquals(listOf("Class", "Section"), controller.uiState.exchangeLabels)
        assertEquals("3A OR", controller.uiState.myExchange)
    }

    @Test
    fun `the CQ is CQ FD`() {
        // `ArrlFd.pas:425`: `msgCQ: SendText(AStn, 'CQ FD <my>')`.
        val controller = session()
        controller.tick(0)
        controller.send(QsoMessage.CQ)
        assertEquals("CQ FD DL1AA", controller.drainCues().single().text)
    }

    @Test
    fun `the station sends its class and its section`() {
        val controller = session()
        controller.tick(0)
        val pieces = controller.uiState.stationExchange.split(' ')
        assertEquals(2, pieces.size, "Field Day sends two pieces, got '${controller.uiState.stationExchange}'")
        assertTrue(pieces[0].last() in "ACF", "a club station's class: ${pieces[0]}")
        assertEquals("OR", pieces[1])
    }

    @Test
    fun `two contacts score two points each times the one constant multiplier`() {
        val controller = session()
        repeat(2) { work(controller) }
        assertEquals(2, controller.uiState.qsos)
        assertEquals(4, controller.uiState.rawPoints, "2 + 2")
        assertEquals(1, controller.uiState.multipliers, "the constant key")
        assertEquals(4, controller.uiState.rawScore)
        assertEquals(4, controller.uiState.verifiedScore, "both copies were clean")
    }

    @Test
    fun `a resumed session continues where the log stopped`() {
        val first = session()
        work(first)
        work(first)
        val restored = QsoLog()
        first.loggedRecords().forEach { restored.record(it) }

        val resumed = session(seed = 31L, log = restored)
        assertEquals(2, resumed.uiState.qsos, "the restored log is the session's log")
        assertEquals(4, resumed.uiState.rawScore, "the score comes back with the log")
    }

    @Test
    fun `the same seed and settings replay the same session`() {
        fun run(): List<String> {
            val controller = session(seed = 31L)
            controller.tick(0)
            controller.editCall(controller.uiState.stationCall)
            controller.enter()
            drainMyTransmission(controller)
            val reply = controller.drainCues().single().text
            return listOf(controller.uiState.stationCall, controller.uiState.stationExchange, reply)
        }
        assertEquals(run(), run())
    }

    /**
     * Works the station on the air: copy its call and its two pieces, `Enter`, then let the
     * contact finish so the station leaves and the next caller is drawn.
     */
    private fun work(controller: SessionController) {
        controller.tick(0)
        val station = controller.uiState.stationCall
        val (cls, section) = controller.uiState.stationExchange.split(' ')
        controller.editCall(station)
        controller.enter()
        drainMyTransmission(controller)
        controller.drainCues()

        controller.editExchange1(cls)
        controller.editExchange2(section)
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
