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
 * T11's end-to-end check in the app's own engine: a **K1USN SST** session, driven exactly as
 * the screen drives it (entry, `Enter`, the station's reply, the log).
 *
 * `SstTest`/`SstRulesTest` prove the document and the rules; this proves the app can actually
 * run them — upstream's `BRUCE MA` default, the two typed boxes, a contact's point and
 * multiplier (a state, and the DXCC entity for a station that sent `DX`), the contest's own CQ
 * wording, and the determinism ADR-0007 §6 asks for, on the new contest.
 */
class SstSessionTest {

    private val sst: ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("k1usnsst"), "k1usnsst did not load").config

    /** A tiny DXCC stand-in; the shipped table is `DXCC.LIST` (spec §9). */
    private val facts: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null

        override fun country(call: String): String? = when {
            call.startsWith("DL") -> "Fed. Rep. of Germany"
            call.startsWith("K") || call.startsWith("W") -> "United States of America"
            else -> null
        }

        override fun continent(call: String): String? = when (country(call)) {
            "Fed. Rep. of Germany" -> "EU"
            "United States of America" -> "NA"
            else -> null
        }

        override fun arrlSection(call: String): String? = null
    }

    private fun history(text: String): CallHistoryFile =
        assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, sst.callHistory!!)).history

    private fun session(
        history: CallHistoryFile,
        seed: Long = 7L,
    ): SessionController = SessionController.start(
        config = sst,
        pool = history,
        history = history,
        autocompleteCalls = history.calls,
        calls = facts,
        myCall = "W1AW",
        // What `SessionBootstrap` derives from `station.exchangeDefaults` (Ini.pas:172).
        myExchange = mapOf("name" to "BRUCE", "qth" to "MA"),
        seed = seed,
        startMs = 0L,
        durationMinutes = SessionSettings.DEFAULT_DURATION_MINUTES,
    )

    /** One station on the air; the fixture decides which call it is. */
    private val localOnly = history("K1ABC,MIKE,MA,\n")
    private val dxOnly = history("DL1ABC,ZVONKO,DX,\n")

    @Test
    fun `the session is K1USN SST and my exchange is upstream's default`() {
        val ui = session(localOnly).uiState
        assertEquals("K1USN Slow Speed Test", ui.contestName)
        assertEquals(listOf("Name", "State/Prov/DX"), ui.exchangeLabels)
        assertEquals("BRUCE MA", ui.myExchange)
        assertEquals("K1ABC", ui.stationCall)
        assertEquals("MIKE MA", ui.stationExchange)
    }

    @Test
    fun `CQ is the contest's own wording`() {
        // CWSST.pas:190 `msgCQ: SendText(AStn, 'CQ SST <my>')`.
        val controller = session(localOnly)
        controller.tick(0)
        controller.send(QsoMessage.CQ)
        assertEquals("CQ SST W1AW", controller.drainCues().single().text)
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
        assertEquals("TU W1AW", tu.text, "CWSST.pas:200 msgTU 'TU <my>'")
        assertEquals(QsoState.Logged, controller.uiState.qsoState)
    }

    @Test
    fun `a local station is one point and its state is the multiplier`() {
        val controller = session(localOnly)
        work(controller, exch1 = "MIKE", exch2 = "MA")

        val ui = controller.uiState
        assertEquals(1, ui.qsos)
        assertEquals(1, ui.rawPoints, "CWSST.pas:322 Qso^.Points := 1")
        assertEquals(1, ui.multipliers)
        assertTrue(ui.lastStatus.contains("K1ABC"), ui.lastStatus)
        assertEquals(setOf("MA"), controller.loggedRecords().single().multipliers)
    }

    @Test
    fun `a DX station scores its DXCC entity as the multiplier`() {
        // The file's QTH for a DX station is the literal 'DX' (CWSST.pas:90) and the entity is
        // the multiplier (CWSST.pas:326-330), so the second box is copied, not left blank.
        val controller = session(dxOnly)
        assertEquals("ZVONKO DX", controller.uiState.stationExchange)

        work(controller, exch1 = "ZVONKO", exch2 = "DX")
        val ui = controller.uiState
        assertEquals(1, ui.qsos)
        assertEquals(1, ui.rawPoints)
        assertEquals(1, ui.multipliers)
        assertEquals(setOf("Fed. Rep. of Germany"), controller.loggedRecords().single().multipliers)
    }

    @Test
    fun `the same seed and settings replay the same SST session`() {
        fun run(): List<String> {
            val controller = session(history("K1ABC,MIKE,MA,\nW2XYZ,DAVE,MA,\nDL1ABC,ZVONKO,DX,\n"), seed = 21L)
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
        assertEquals(run(), run(), "the SST session is not deterministic")
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
