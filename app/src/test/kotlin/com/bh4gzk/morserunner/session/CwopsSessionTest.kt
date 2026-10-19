package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.contest.CallHistoryFile
import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLoad
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.qso.QsoMessage
import com.bh4gzk.morserunner.qso.QsoState
import com.bh4gzk.morserunner.settings.SessionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T14's end-to-end check in the app's own engine: a **CWops CWT** session, driven exactly as
 * the screen drives it (entry, `Enter`, the station's reply, the log).
 *
 * `CwopsTest`/`CwopsRulesTest` prove the document and the rules; this proves the app can
 * actually run them — upstream's `DAVID 123` default, the two typed boxes, a station that
 * sends its name plus a member number / state / country prefix, the CQ wording `CQ CWT <my>`,
 * a contact's point and multiplier (**the worked call**), and the determinism ADR-0007 §6 asks
 * for, on the new contest.
 */
class CwopsSessionTest {

    private val cwops: ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("cwops"), "cwops did not load").config

    /** CWT's document asks DXCC nothing; an unavailable provider is the honest stand-in. */
    private val facts: CallFacts = CallFacts.Unavailable

    /** The file is header-driven, so every fixture starts with its `!!Order!!` line. */
    private fun history(text: String): CallHistoryFile =
        assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse("!!Order!!,Call,Name,Exch1,UserText,\n$text", cwops.callHistory!!),
        ).history

    private fun session(
        history: CallHistoryFile,
        seed: Long = 7L,
    ): SessionController = SessionController.start(
        config = cwops,
        pool = history,
        history = history,
        autocompleteCalls = history.calls,
        calls = facts,
        myCall = "W1AW",
        // What `SessionBootstrap` derives from `station.exchangeDefaults` (Ini.pas:112).
        myExchange = mapOf("name" to "DAVID", "exch" to "123"),
        seed = seed,
        startMs = 0L,
        durationMinutes = SessionSettings.DEFAULT_DURATION_MINUTES,
    )

    /** One station on the air; the fixture decides which call it is. */
    private val oneStation = history("3B9FR,Robert,1343,Rodriguez Island\n")

    @Test
    fun `the session is CWOPS CWT and my exchange is upstream's default`() {
        val ui = session(oneStation).uiState
        // Ini.pas:106 the contest's own name; `:110` the two captions; `:112` the default.
        assertEquals("CWOPS CWT", ui.contestName)
        assertEquals(listOf("Name", "Exch"), ui.exchangeLabels)
        assertEquals("DAVID 123", ui.myExchange)
        assertEquals("3B9FR", ui.stationCall)
        // The pieces are joined with one space by the port (`SessionController`), where
        // upstream's `Station.NrAsText` uses two (`Station.pas:372-373`) — ADR-0014 §8.
        assertEquals("ROBERT 1343", ui.stationExchange)
    }

    @Test
    fun `CQ is upstream's CWT wording`() {
        // CwOps.pas:184 `msgCQ: SendText(AStn, 'CQ CWT <my>')`.
        val controller = session(oneStation)
        controller.tick(0)
        controller.send(QsoMessage.CQ)
        assertEquals("CQ CWT W1AW", controller.drainCues().single().text)
    }

    private fun work(
        controller: SessionController,
        name: String,
        exch: String,
    ) {
        controller.tick(0)
        controller.editCall(controller.uiState.stationCall)
        controller.enter()
        drainMyTransmission(controller)
        controller.drainCues()

        controller.editExchange1(name)
        controller.editExchange2(exch)
        controller.enter()
        val tu = controller.drainCues().single()
        assertEquals("TU", tu.text, "no override: upstream's base TU is the bare word")
        assertEquals(QsoState.Logged, controller.uiState.qsoState)
    }

    @Test
    fun `a CWT member is one point and the worked call is the multiplier`() {
        val controller = session(oneStation)
        work(controller, name = "ROBERT", exch = "1343")

        val ui = controller.uiState
        assertEquals(1, ui.qsos)
        assertEquals(1, ui.rawPoints, "CwOps.pas:247 Qso^.Points := 1")
        assertEquals(1, ui.multipliers)
        assertTrue(ui.lastStatus.contains("3B9FR"), ui.lastStatus)
        // CwOps.pas:248 `Result:= Qso^.Call` — the call, not the member number.
        assertEquals(setOf("3B9FR"), controller.loggedRecords().single().multipliers)
    }

    @Test
    fun `a non-member's state or prefix is the same field and the call is still the multiplier`() {
        // The second piece is `etGenericField`: a state here, a country prefix next door.
        val controller = session(history("4O3A,Ranko,4O,Montenegro\nN5OT,Mark,ND,Bartlesville OK\n"))
        val ui = controller.uiState
        assertTrue(ui.stationExchange.endsWith("4O") || ui.stationExchange.endsWith("ND"))
        val station = ui.stationCall
        val exch = ui.stationExchange.substringAfter(' ')
        work(controller, name = ui.stationExchange.substringBefore(' '), exch = exch)

        assertEquals(1, controller.uiState.qsos)
        assertEquals(1, controller.uiState.multipliers)
        assertEquals(setOf(station), controller.loggedRecords().single().multipliers)
    }

    @Test
    fun `the same seed and settings replay the same CWT session`() {
        fun run(): List<String> {
            val controller = session(
                history("3B9FR,Robert,1343,Rodriguez Island\n4O3A,Ranko,4O,Montenegro\nN5OT,Mark,ND,Bartlesville OK\n"),
                seed = 31L,
            )
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
        assertEquals(run(), run(), "the CWT session is not deterministic")
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
