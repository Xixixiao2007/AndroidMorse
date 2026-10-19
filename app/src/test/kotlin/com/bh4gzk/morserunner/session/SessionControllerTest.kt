package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.contest.AssetCallFacts
import com.bh4gzk.morserunner.contest.CallHistoryFile
import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLoad
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.data.DxccList
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.qso.QsoMessage
import com.bh4gzk.morserunner.qso.QsoState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The session controller: what the Compose screen drives, with no Android in the way.
 *
 * The clock is passed in ([SessionController.tick]) and the audio is drained as
 * [AudioCue]s, so a whole contact — CQ, the station's answer, the copied exchange, `TU`,
 * the log — is reproducible on the JVM without a device or a sound card.
 */
class SessionControllerTest {

    // --- fixtures ------------------------------------------------------------------

    private val dxcc = DxccList.parse(
        """
        K;K|W|N|A[A-GI-K];United States of America;NA;8;3,4,5;291
        DL;(D[A-R])|(Y[2-9]);Fed. Rep. of Germany;EU;28;14;230
        """.trimIndent(),
    )

    private val facts: CallFacts = AssetCallFacts(dxcc)

    private val cqww: ContestConfig = assertIs<LoadResult.Success>(ContestLibrary.load("cqww")).config

    /** One station on the air: the contact tests need no draw. */
    private val oneCall: CallHistoryFile = load("DL1ABC,14,\n")

    /** Three real calls, for the completion test. */
    private val threeCalls: CallHistoryFile = load("DL1ABC,14,\nDL2XYZ,14,\nK1ABC,5,\n")

    private fun load(text: String): CallHistoryFile =
        assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, cqww.callHistory)).history

    private fun session(
        history: CallHistoryFile = oneCall,
        seed: Long = 7L,
    ): SessionController = SessionController.start(
        config = cqww,
        pool = history,
        history = history,
        autocompleteCalls = history.calls,
        calls = facts,
        myCall = "W1AW",
        myExchange = mapOf("rst" to "599", "zone" to "5"),
        seed = seed,
        startMs = 0L,
        speedWpm = 25,
    )

    // --- entry and Enter -------------------------------------------------------------

    @Test
    fun `nothing is transmitted before the session starts`() {
        val controller = session()
        controller.tick(0)
        assertEquals(emptyList(), controller.drainCues())
        assertEquals(QsoState.Idle, controller.uiState.qsoState)
        assertEquals("DL1ABC", controller.uiState.stationCall, "a station is on the air")
        assertEquals("599 14", controller.uiState.stationExchange)
    }

    @Test
    fun `Enter with an empty call sends CQ`() {
        val controller = session()
        controller.tick(0)
        controller.enter()

        val cue = controller.drainCues().single()
        assertEquals("CQ W1AW TEST", cue.text)
        assertEquals(null, cue.station, "my own sending has no station")
        assertTrue(cue.durationMs > 0)
        assertEquals(QsoState.CallingCq, controller.uiState.qsoState)
        assertEquals(listOf("CQ W1AW TEST"), controller.uiState.pendingText)
    }

    @Test
    fun `Enter with the call sends his call and then my exchange, in that order`() {
        val controller = session()
        controller.tick(0)
        controller.editCall("DL1ABC")
        controller.enter()

        val first = controller.drainCues().single()
        assertEquals("DL1ABC", first.text)
        controller.tick(first.durationMs + 1)

        val second = controller.drainCues().single()
        assertEquals("599 5", second.text, "`<#>` is my exchange, not the station's")
        assertTrue(second.durationMs > 0)
        // The piece is current, so it is still in the queue until its keying completes.
        assertEquals(listOf("599 5"), controller.uiState.pendingText)
    }

    // --- the station answers ---------------------------------------------------------

    @Test
    fun `the station answers after my batch, and the panel shows what it sent`() {
        val controller = session()
        controller.tick(0)
        controller.editCall("DL1ABC")
        controller.enter()
        drainMyTransmission(controller)

        val reply = controller.drainCues().single()
        assertEquals("DL1ABC", reply.station?.call)
        assertTrue(reply.text.startsWith("R "), "expected `R <exchange>`, got '${reply.text}'")
        assertTrue(reply.text.contains("599 14"), "the reply carries the station exchange: '${reply.text}'")
        assertEquals(reply.text, controller.uiState.lastHeard)
    }

    @Test
    fun `an idle station calls CQ on its own`() {
        val controller = session()
        controller.tick(0)
        controller.tick(SessionController.CQ_LEAD_MS)

        val cq = controller.drainCues().single()
        assertEquals("CQ DL1ABC TEST", cq.text)
        assertEquals("DL1ABC", cq.station?.call)
        assertEquals(cq.text, controller.uiState.lastHeard)
    }

    // --- logging ---------------------------------------------------------------------

    @Test
    fun `a complete contact is logged, scored and shown in the status bar`() {
        val controller = session()
        controller.tick(0)
        controller.editCall("DL1ABC")
        controller.enter()
        drainMyTransmission(controller)
        controller.drainCues()

        // I copied the exchange the station sent.
        controller.editExchange1("599")
        controller.editExchange2("14")
        controller.enter()

        val tu = controller.drainCues().single()
        assertEquals("TU", tu.text)
        assertEquals(QsoState.Logged, controller.uiState.qsoState)

        val ui = controller.uiState
        assertEquals(1, ui.qsos)
        assertEquals(6, ui.rawScore, "3 points × 2 multipliers")
        assertEquals(6, ui.verifiedScore)
        assertEquals(2, ui.multipliers)
        assertTrue(ui.lastStatus.contains("DL1ABC"), "the status line names the logged call: '${ui.lastStatus}'")
        assertEquals(1, ui.rateLastFiveMinutes)
        assertTrue(ui.ratePerHour > 0.0)
    }

    @Test
    fun `the worked station is replaced once the TU is sent`() {
        val controller = session(threeCalls)
        val first = controller.uiState.stationCall
        controller.tick(0)
        controller.editCall(first)
        controller.enter()
        drainMyTransmission(controller)
        controller.drainCues()
        controller.editExchange1("599")
        controller.editExchange2(controller.uiState.stationExchange.substringAfter(' '))
        controller.enter()

        val tu = controller.drainCues().single()
        assertEquals("TU", tu.text)
        controller.tick(controller.lastNowMs + tu.durationMs + 1)

        assertEquals(QsoState.Idle, controller.uiState.qsoState, "the contact is over")
        assertEquals(1, controller.uiState.qsos)
        assertNotEquals(first, controller.uiState.stationCall, "a new station takes the frequency")
        assertEquals("", controller.uiState.entry.call, "the boxes are wiped for the next contact")
    }

    // --- the other actions -----------------------------------------------------------

    @Test
    fun `autocomplete comes from the contest call history`() {
        val controller = session(threeCalls)
        controller.tick(0)
        controller.editCall("DL")
        assertEquals(listOf("DL1ABC", "DL2XYZ"), controller.uiState.suggestions)

        // `?` stays a wildcard: no completion while the call is half-copied.
        controller.editCall("DL?")
        assertEquals(emptyList(), controller.uiState.suggestions)
    }

    @Test
    fun `a message button queues what the key would`() {
        val controller = session()
        controller.tick(0)
        controller.perform(KeyAction.Send(QsoMessage.CQ))
        assertEquals("CQ W1AW TEST", controller.drainCues().single().text)

        controller.perform(KeyAction.Wipe)
        assertEquals("", controller.uiState.entry.call)
        assertEquals(emptyList(), controller.uiState.pendingText)
        assertEquals(QsoState.Idle, controller.uiState.qsoState)
    }

    @Test
    fun `Esc stops my sending`() {
        val controller = session()
        controller.tick(0)
        controller.editCall("DL1ABC")
        controller.enter()
        controller.drainCues()

        controller.perform(KeyAction.Abort)
        assertEquals(emptyList(), controller.uiState.pendingText, "the queue is dropped")
        // The keyer must not resume the cancelled pieces later either.
        controller.tick(controller.lastNowMs + 60_000)
        assertTrue(controller.drainCues().none { it.text == "599 5" })
    }

    @Test
    fun `the session ends when the contest duration elapses`() {
        val controller = session()
        controller.tick(0)
        assertEquals(30 * 60_000L, controller.uiState.durationMs)

        controller.tick(30 * 60_000L)
        assertTrue(controller.uiState.finished)
        assertEquals(0L, controller.uiState.remainingMs)

        controller.perform(KeyAction.Send(QsoMessage.CQ))
        assertEquals(emptyList(), controller.drainCues(), "a finished session transmits nothing")
    }

    @Test
    fun `the same seed replays the same session`() {
        fun run(): List<String> {
            val controller = session(threeCalls)
            controller.tick(0)
            controller.editCall(controller.uiState.stationCall)
            controller.enter()
            drainMyTransmission(controller)
            val reply = controller.drainCues().single().text
            return listOf(controller.uiState.stationCall, reply)
        }
        assertEquals(run(), run())
    }

    // --- helpers ---------------------------------------------------------------------

    /** Lets my own transmission finish, cue by cue, until the queue is empty. */
    private fun drainMyTransmission(controller: SessionController) {
        var guard = 0
        while (controller.uiState.pendingText.isNotEmpty() && guard++ < 10) {
            val cue = controller.drainCues().firstOrNull() ?: break
            controller.tick(controller.lastNowMs + cue.durationMs + 1)
        }
        // The station is told what it heard and schedules its reply on the same tick.
        controller.tick(controller.lastNowMs + 2_000)
    }
}
