package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.data.CallPool
import com.bh4gzk.morserunner.data.CallPrefix
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
 * T15's end-to-end check in the app's own engine: a **CQ WPX** session, driven exactly as the
 * screen drives it (entry, `Enter`, the station's reply, the log) — and the first session test
 * that has to prove a number **changes between contacts**.
 *
 * `CqwpxTest`/`CqwpxRulesTest` prove the document and the rules; this proves the app can run
 * them: upstream's `5NN #` default rendered as `5NN001`, the two typed boxes, a station that
 * sends its report and its serial, the CQ wording every earlier contest but CWT/SS uses, one
 * point per contact with the **WPX prefix** as the multiplier, the serial advancing by one per
 * logged contact (**F8** — `.scratch/progress.md` §27's finding), a resume that continues the
 * serial from the log, and the determinism ADR-0007 §6 asks for.
 */
class CqwpxSessionTest {

    private val cqwpx: ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("cqwpx"), "cqwpx did not load").config

    /**
     * The real WPX prefix rules (`core-data.CallPrefix`, already pinned against upstream's
     * `Test/CallsignUtilsTest.pas` by `CallPrefixTest`), wrapped as the scoring seam. The real
     * asset path (`AssetCallFacts` over `DXCC.LIST`) is `PackagedAssetsTest`'s job; this test
     * needs the prefix rule, not the entity table.
     */
    private val facts: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? =
            CallPrefix.extractPrefix(call, deleteTrailingLetters = true).ifEmpty { null }

        override fun zone(call: String): Double? = null
        override fun country(call: String): String? = null
        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }

    /** CQ WPX has no call history: `MASTER.DTA` is the pool and nothing else (`CqWpx.pas:87-97`). */
    private val pool = object : CallPool {
        override val size: Int = 3
        override fun callAt(index: Int): String = listOf("DL2XYZ", "F4ABC", "K1ABC")[index]
    }

    private fun session(seed: Long = 7L, log: QsoLog = QsoLog()): SessionController =
        SessionController.start(
            config = cqwpx,
            pool = pool,
            history = null,
            autocompleteCalls = emptyList(),
            calls = facts,
            myCall = "W1AW",
            // What `SessionBootstrap` derives from `station.exchangeDefaults` (Ini.pas:101
            // `ExchDefault: '5NN #'`; `Main.pas:1517-1531` starts `Tst.Me.NR` at 1).
            myExchange = mapOf("rst" to "5NN", "nr" to "1"),
            seed = seed,
            startMs = 0L,
            durationMinutes = SessionSettings.DEFAULT_DURATION_MINUTES,
            log = log,
        )

    @Test
    fun `the session is CQ WPX, and my first serial is 001`() {
        val ui = session().uiState
        // Ini.pas:96 the contest's own name; `ExchFields.pas:44,53` the two captions.
        assertEquals("CQ WPX", ui.contestName)
        assertEquals(listOf("RST", "Nr."), ui.exchangeLabels)
        // Station.pas:391-394 + `:427-433`: RST and a three-digit serial, concatenated.
        assertEquals("5NN001", ui.myExchange)
        assertTrue(ui.stationCall in listOf("DL2XYZ", "F4ABC", "K1ABC"), "station ${ui.stationCall}")
        val pieces = ui.stationExchange.split(' ')
        assertEquals(2, pieces.size, "the station sends its report and its serial: ${ui.stationExchange}")
        assertEquals("599", pieces[0], "CqWpx.pas:323-326 `getExch1` is the literal '599'")
        // `Ini.pas:232-238`: the start-of-contest range is 1..176 (`MinVal:1; MaxVal:176`).
        val serial = pieces[1].toInt()
        assertTrue(serial in 1..176, "the DX serial is outside upstream's range: $serial")
    }

    @Test
    fun `CQ is upstream's base wording, because CqWpx overrides none`() {
        // CqWpx.pas has no `SendMsg` override, so `CQ <my> TEST` is what is sent.
        val controller = session()
        controller.tick(0)
        controller.send(QsoMessage.CQ)
        assertEquals("CQ W1AW TEST", controller.drainCues().single().text)
    }

    @Test
    fun `the serial advances once per logged contact and the score is the prefix`() {
        // F8 in the app's engine: upstream's `Log.pas:766` `Inc(Tst.Me.NR)`.
        val controller = session()
        work(controller)
        assertEquals(1, controller.uiState.qsos)
        assertEquals("5NN002", controller.uiState.myExchange, "the second contact sends 002")

        work(controller)
        assertEquals(2, controller.uiState.qsos)
        assertEquals("5NN003", controller.uiState.myExchange, "the third contact sends 003")

        // One point per contact (`CqWpx.pas:285-291`); the two worked calls have different WPX
        // prefixes (DL2 and one of F4/K1), so the multiplier set holds two keys → 4.
        assertEquals(2, controller.uiState.rawPoints)
        assertEquals(2, controller.uiState.multipliers)
        assertEquals(4, controller.uiState.rawScore)
        assertEquals(2, controller.loggedRecords().map { it.multipliers }.toSet().size)
    }

    @Test
    fun `a resumed session continues the serial from the log`() {
        // The log is the serial's state (`startingSerial + log.size`), so a process restart
        // that restores the contacts restores the number — T9's snapshot path.
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
    fun `the same seed and settings replay the same WPX session`() {
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
        assertEquals(run(), run(), "the WPX session is not deterministic")
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
        // Finish the TU so the worked station retires and a fresh caller takes its place.
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
