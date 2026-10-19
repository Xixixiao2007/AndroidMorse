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
 * T13's end-to-end check in the app's own engine: a **JARL ACAG** session, driven exactly as
 * the screen drives it (entry, `Enter`, the station's reply, the log).
 *
 * `AcagTest`/`AcagRulesTest` prove the document and the rules; this proves the app can
 * actually run them — upstream's `5NN 1002H` default, the two typed boxes, a station that
 * sends `599 <city|gun|ku><power>`, a contact's point and multiplier (the code with the
 * trailing power letter stripped, leading zeros intact), and the determinism ADR-0007 §6 asks
 * for, on the new contest.
 *
 * ACAG overrides **no** message, so this is also the check that the base wording is used:
 * CQ is `CQ <my> TEST` and TU is the bare `TU`, unlike SST's `CQ SST` / `TU <my>`.
 */
class AcagSessionTest {

    private val acag: ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("acag"), "acag did not load").config

    /** ACAG's document asks DXCC nothing; an unavailable provider is the honest stand-in. */
    private val facts: CallFacts = CallFacts.Unavailable

    private fun history(text: String): CallHistoryFile =
        assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, acag.callHistory!!)).history

    private fun session(
        history: CallHistoryFile,
        seed: Long = 7L,
    ): SessionController = SessionController.start(
        config = acag,
        pool = history,
        history = history,
        autocompleteCalls = history.calls,
        calls = facts,
        myCall = "W1AW",
        // What `SessionBootstrap` derives from `station.exchangeDefaults` (Ini.pas:192).
        myExchange = mapOf("rst" to "5NN", "city" to "1002H"),
        seed = seed,
        startMs = 0L,
        durationMinutes = SessionSettings.DEFAULT_DURATION_MINUTES,
    )

    /** One station on the air; the fixture decides which call it is. */
    private val oneStation = history("7K1CPT,1430H\n")

    @Test
    fun `the session is JARL ACAG and my exchange is upstream's default`() {
        val ui = session(oneStation).uiState
        assertEquals("JARL ACAG", ui.contestName)
        assertEquals(listOf("RST", "Number"), ui.exchangeLabels)
        assertEquals("5NN 1002H", ui.myExchange)
        assertEquals("7K1CPT", ui.stationCall)
        assertEquals("599 1430H", ui.stationExchange)
    }

    @Test
    fun `CQ uses upstream's base wording, because ACAG overrides no message`() {
        // Acag.pas has no SendMsg override at all; the base template is `CQ <my> TEST`
        // (`Messages.kt`, `Contest.pas:412-450`).
        val controller = session(oneStation)
        controller.tick(0)
        controller.send(QsoMessage.CQ)
        assertEquals("CQ W1AW TEST", controller.drainCues().single().text)
    }

    private fun work(
        controller: SessionController,
        exch1: String,
        exch2: String,
    ) {
        controller.tick(0)
        controller.editCall(controller.uiState.stationCall)
        controller.enter()
        drainMyTransmission(controller)
        controller.drainCues()

        controller.editExchange1(exch1)
        controller.editExchange2(exch2)
        controller.enter()
        val tu = controller.drainCues().single()
        assertEquals("TU", tu.text, "no override: upstream's base TU is the bare word")
        assertEquals(QsoState.Logged, controller.uiState.qsoState)
    }

    @Test
    fun `a Japanese station is one point and its city code is the multiplier`() {
        val controller = session(oneStation)
        work(controller, exch1 = "599", exch2 = "1430H")

        val ui = controller.uiState
        assertEquals(1, ui.qsos)
        assertEquals(1, ui.rawPoints, "Acag.pas:207 Qso^.Points := 1")
        assertEquals(1, ui.multipliers)
        assertTrue(ui.lastStatus.contains("7K1CPT"), ui.lastStatus)
        assertEquals(setOf("1430"), controller.loggedRecords().single().multipliers)
    }

    @Test
    fun `a gun code keeps its leading zero in the multiplier`() {
        // `7K1AEU/8,01039M` is the first shipped row with a leading zero; the multiplier must be
        // the string `01039`, not the number `1039` (ADR-0013 §3).
        val controller = session(history("7K1AEU/8,01039M\n"))
        assertEquals("599 01039M", controller.uiState.stationExchange)
        work(controller, exch1 = "599", exch2 = "01039M")
        assertEquals(1, controller.uiState.multipliers)
        assertEquals(setOf("01039"), controller.loggedRecords().single().multipliers)
    }

    @Test
    fun `the same seed and settings replay the same ACAG session`() {
        fun run(): List<String> {
            val controller = session(history("7K1CPT,1430H\n7K1BIB,100115M\nJQ1ABC,1005L\n"), seed = 21L)
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
        assertEquals(run(), run(), "the ACAG session is not deterministic")
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
