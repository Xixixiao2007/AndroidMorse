package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLookup
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.data.CallPool
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
 * T17's end-to-end check in the app's own engine: an **ARRL DX CW** session driven exactly as
 * the screen drives it (entry, `Enter`, the station's reply, the log), on **both sides** of the
 * contest.
 *
 * The pool here is what `SessionBootstrap` hands the session: the contest's call history,
 * already filtered by the operator's side (ADR-0017 §4). So a W/VE operator's roster only ever
 * contains DX stations and a DX operator's only W/VE stations — the two runs below use the two
 * pools the real file produces.
 */
class ArrlDxSessionTest {

    private val arrldx: ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("arrldx"), "arrldx did not load").config

    /** The DXCC table, reduced to the calls these runs can draw. */
    private val facts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null
        override fun country(call: String): String? = when (call.uppercase()) {
            "W1AW", "K1ABC", "W1MA" -> "United States of America"
            "DL1AA" -> "Fed. Rep. of Germany"
            "DL1ABC" -> "Fed. Rep. of Germany"
            "G4UEL" -> "England"
            "JA1ABC" -> "Japan"
            else -> null
        }

        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }

    /** The two DX stations a W/VE operator works: a power each, different entities. */
    private val dxPool = object : CallPool {
        override val size: Int = 3
        override fun callAt(index: Int): String = listOf("DL1ABC", "JA1ABC", "G4UEL")[index]
    }

    /** The two W/VE stations a DX operator works: a state each, the **same** state. */
    private val wvePool = object : CallPool {
        override val size: Int = 3
        override fun callAt(index: Int): String = listOf("K1ABC", "W1MA", "N1MA")[index]
    }

    /** The file's columns, as the filtered history would give them. */
    private val history = HistoryLookup { call ->
        when (call) {
            "DL1ABC" -> mapOf("state" to "", "power" to "100")
            "JA1ABC" -> mapOf("state" to "", "power" to "KW")
            "G4UEL" -> mapOf("state" to "", "power" to "400")
            "K1ABC" -> mapOf("state" to "MA", "power" to "")
            "W1MA" -> mapOf("state" to "MA", "power" to "")
            "N1MA" -> mapOf("state" to "MA", "power" to "")
            else -> null
        }
    }

    /** A W/VE operator: my call resolves to the USA, so I send a state. */
    private fun localSession(seed: Long = 7L, log: QsoLog = QsoLog()): SessionController =
        SessionController.start(
            config = arrldx,
            pool = dxPool,
            history = history,
            autocompleteCalls = emptyList(),
            calls = facts,
            myCall = "W1AW",
            myExchange = mapOf("rst" to "5NN", "exch2" to "CT"),
            seed = seed,
            startMs = 0L,
            durationMinutes = SessionSettings.DEFAULT_DURATION_MINUTES,
            log = log,
        )

    /** A DX operator: my call resolves to Germany, so I send a power. */
    private fun dxSession(seed: Long = 7L, log: QsoLog = QsoLog()): SessionController =
        SessionController.start(
            config = arrldx,
            pool = wvePool,
            history = history,
            autocompleteCalls = emptyList(),
            calls = facts,
            myCall = "DL1AA",
            myExchange = mapOf("rst" to "5NN", "exch2" to "100"),
            seed = seed,
            startMs = 0L,
            durationMinutes = SessionSettings.DEFAULT_DURATION_MINUTES,
            log = log,
        )

    @Test
    fun `the session is ARRL DX, and my own box is what I typed`() {
        // One box, two meanings (`Main.pas:1548`): the label is the contest's, the value is mine.
        assertEquals("ARRL DX CW", localSession().uiState.contestName)
        assertEquals(listOf("RST", "State/Prov or Power"), localSession().uiState.exchangeLabels)
        assertEquals("5NN CT", localSession().uiState.myExchange)
        assertEquals("5NN 100", dxSession().uiState.myExchange)
    }

    @Test
    fun `a W or VE operator hears a power, and a DX operator hears a state`() {
        // `getExch2` is State + Power (`ArrlDx.pas:320-323`); the filter has already dropped the
        // other side's rows, so the station sends exactly the one piece it has.
        val local = localSession()
        local.tick(0)
        val localPieces = local.uiState.stationExchange.split(' ')
        assertEquals("599", localPieces[0], "ArrlDx.pas:315 the station's RST is always 599")
        assertTrue(
            localPieces[1].all { it.isDigit() || it in "KWT" },
            "a DX station sends a power, got '${localPieces[1]}'",
        )

        val dx = dxSession()
        dx.tick(0)
        val dxPieces = dx.uiState.stationExchange.split(' ')
        assertEquals("599", dxPieces[0])
        assertEquals(2, dxPieces[1].length, "a W/VE station sends a state/province, got '${dxPieces[1]}'")
        assertTrue(dxPieces[1].all { it in 'A'..'Z' })
    }

    @Test
    fun `the base CQ wording is used, because ARRL DX overrides no message`() {
        val controller = localSession()
        controller.tick(0)
        controller.send(QsoMessage.CQ)
        assertEquals("CQ W1AW TEST", controller.drainCues().single().text)
    }

    @Test
    fun `two DX contacts score three points each times two entities`() {
        // ArrlDx.pas:351 `Qso^.Points := 3`; the multiplier is each worked station's entity, so
        // two different DXCC entities make 6 points × 2 multipliers.
        val controller = localSession()
        repeat(2) { work(controller) }
        assertEquals(2, controller.uiState.qsos)
        assertEquals(6, controller.uiState.rawPoints, "3 + 3")
        assertEquals(2, controller.uiState.multipliers, "two different DXCC entities")
        assertEquals(12, controller.uiState.rawScore)
        assertEquals(12, controller.uiState.verifiedScore, "both copies were clean")
    }

    @Test
    fun `two W or VE contacts in the same state score three points each times one multiplier`() {
        // The other side of ArrlDx.pas:359: the multiplier is the station's own state, so two
        // Massachusetts stations are one multiplier — the opposite shape from the run above.
        val controller = dxSession()
        repeat(2) { work(controller) }
        assertEquals(2, controller.uiState.qsos)
        assertEquals(6, controller.uiState.rawPoints, "3 + 3")
        assertEquals(1, controller.uiState.multipliers, "two stations, one state")
        assertEquals(6, controller.uiState.rawScore)
        assertEquals(6, controller.uiState.verifiedScore)
    }

    @Test
    fun `a resumed session continues where the log stopped`() {
        val first = localSession()
        work(first)
        work(first)
        val restored = QsoLog()
        first.loggedRecords().forEach { restored.record(it) }

        val resumed = localSession(seed = 31L, log = restored)
        assertEquals(2, resumed.uiState.qsos, "the restored log is the session's log")
        assertEquals(12, resumed.uiState.rawScore, "the score comes back with the log")
    }

    @Test
    fun `the same seed and settings replay the same session on both sides`() {
        fun run(session: () -> SessionController): List<String> {
            val controller = session()
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
        assertEquals(run { localSession(seed = 31L) }, run { localSession(seed = 31L) })
        assertEquals(run { dxSession(seed = 31L) }, run { dxSession(seed = 31L) })
    }

    /**
     * Works the station on the air: copy its call and its two pieces, `Enter`, then let the
     * contact finish so the station leaves and the next caller is drawn.
     */
    private fun work(controller: SessionController) {
        controller.tick(0)
        val station = controller.uiState.stationCall
        val (rst, exch2) = controller.uiState.stationExchange.split(' ')
        controller.editCall(station)
        controller.enter()
        drainMyTransmission(controller)
        controller.drainCues()

        controller.editExchange1(rst)
        controller.editExchange2(exch2)
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
