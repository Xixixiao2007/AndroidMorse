package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.contest.CallHistoryFile
import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLoad
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.qso.QsoState
import com.bh4gzk.morserunner.settings.SessionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T10's end-to-end check in the app's own engine: a **NAQP** session, driven exactly as the
 * screen drives it (entry, `Enter`, the station's reply, the log).
 *
 * `NaqpTest`/`NaqpRulesTest` prove the document and the rules; this proves the app can actually
 * run them — the operator's default exchange, the two typed boxes, a worked contact's points and
 * multiplier, and the determinism ADR-0007 §6 asks for, on the new contest.
 */
class NaqpSessionTest {

    private val naqp: ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("naqp"), "naqp did not load").config

    /** A tiny DXCC stand-in; the shipped table is `DXCC.LIST` (spec §9). */
    private val facts: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null

        override fun country(call: String): String? = when {
            call.startsWith("KH6") -> "Hawaii"
            call.startsWith("K") || call.startsWith("W") -> "United States of America"
            call.startsWith("DL") -> "Fed. Rep. of Germany"
            else -> null
        }

        override fun continent(call: String): String? = when (country(call)) {
            "Hawaii" -> "OC"
            "United States of America" -> "NA"
            "Fed. Rep. of Germany" -> "EU"
            else -> null
        }

        override fun arrlSection(call: String): String? = null
    }

    private fun history(text: String): CallHistoryFile =
        assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, naqp.callHistory!!)).history

    private fun session(
        history: CallHistoryFile,
        seed: Long = 7L,
    ): SessionController = SessionController.start(
        config = naqp,
        pool = history,
        history = history,
        autocompleteCalls = history.calls,
        calls = facts,
        myCall = "W1AW",
        // What `SessionBootstrap` derives from `station.exchangeDefaults` (Ini.pas:133).
        myExchange = mapOf("name" to "ALEX", "state" to "ON"),
        seed = seed,
        startMs = 0L,
        durationMinutes = SessionSettings.DEFAULT_DURATION_MINUTES,
    )

    /** One station on the air; the fixture decides which call it is. */
    private val localOnly = history("K1ABC,MIKE,OR,\n")
    private val dxOnly = history("DL1ABC,ZVONKO,\n")

    @Test
    fun `the session is NAQP and my exchange is upstream's default`() {
        val ui = session(localOnly).uiState
        assertEquals("NCJ NAQP", ui.contestName)
        assertEquals(listOf("Name", "State/Prov"), ui.exchangeLabels)
        assertEquals("ALEX ON", ui.myExchange)
        assertEquals("K1ABC", ui.stationCall)
        assertEquals("MIKE OR", ui.stationExchange)
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
        assertEquals("TU", tu.text)
        assertEquals(QsoState.Logged, controller.uiState.qsoState)
    }

    @Test
    fun `a local station is one point and its state is the multiplier`() {
        val controller = session(localOnly)
        work(controller, exch1 = "MIKE", exch2 = "OR")

        val ui = controller.uiState
        assertEquals(1, ui.qsos)
        assertEquals(1, ui.rawPoints, "NaQp.pas:397 Points := 1")
        assertEquals(1, ui.multipliers)
        assertTrue(ui.lastStatus.contains("K1ABC"), ui.lastStatus)
        assertTrue(controller.loggedRecords().single().multipliers == setOf("OR"))
    }

    @Test
    fun `a DX station is one point and no multiplier, with an empty second box`() {
        val controller = session(dxOnly)
        assertEquals("ZVONKO", controller.uiState.stationExchange, "a DX station sends name only")

        // Upstream's convention: a non-NA station sends no location, and the operator logs
        // `DX` (Log.pas:945-949). The two-box entry requires *something* in the second box.
        work(controller, exch1 = "ZVONKO", exch2 = "DX")
        val ui = controller.uiState
        assertEquals(1, ui.qsos)
        assertEquals(1, ui.rawPoints)
        assertEquals(0, ui.multipliers, "a DX station is not a multiplier (NaQp.pas:395-404)")
        assertTrue(controller.loggedRecords().single().multipliers.isEmpty())
    }

    @Test
    fun `the same seed and settings replay the same NAQP session`() {
        fun run(): List<String> {
            val controller = session(history("K1ABC,MIKE,OR,\nW2XYZ,DAVE,NY,\nKH6XX,LEE,HI,\n"), seed = 21L)
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
        assertEquals(run(), run(), "the NAQP session is not deterministic")
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
