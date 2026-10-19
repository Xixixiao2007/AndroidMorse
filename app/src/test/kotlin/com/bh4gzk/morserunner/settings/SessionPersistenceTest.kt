package com.bh4gzk.morserunner.settings

import com.bh4gzk.morserunner.contest.AssetCallFacts
import com.bh4gzk.morserunner.contest.CallHistoryFile
import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLoad
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.data.DxccList
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.session.SessionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge from a running [SessionController] to the stored snapshot, with no Android in it.
 *
 * Two properties matter and both are about *not* doing work: a session that did not change is
 * not rewritten (the tick loop runs every 40 ms), and a session that ended leaves nothing to
 * resume (a finished contest must not reappear tomorrow as a fresh one).
 */
class SessionPersistenceTest {

    private val dxcc = DxccList.parse(
        """
        K;K|W|N|A[A-GI-K];United States of America;NA;8;3,4,5;291
        DL;(D[A-R])|(Y[2-9]);Fed. Rep. of Germany;EU;28;14;230
        """.trimIndent(),
    )

    private val facts: CallFacts = AssetCallFacts(dxcc)

    private val cqww: ContestConfig = assertIs<LoadResult.Success>(ContestLibrary.load("cqww")).config

    private val calls: CallHistoryFile =
        assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse("DL1ABC,14,\nK1ABC,5,\n", cqww.callHistory)).history

    private fun session(settings: SessionSettings = SessionSettings()) = SessionController.start(
        config = cqww,
        pool = calls,
        history = calls,
        autocompleteCalls = calls.calls,
        calls = facts,
        myCall = settings.myCall,
        myExchange = mapOf("rst" to "599", "zone" to "5"),
        seed = 7L,
        startMs = 1_000L,
        speedWpm = settings.speedWpm,
        pitchHz = settings.pitchHz,
        durationMinutes = settings.durationMinutes,
        qrm = settings.qrm,
        flutter = settings.flutter,
        lids = settings.lids,
    )

    private fun persistence(store: SessionStore, settings: SessionSettings = SessionSettings()) =
        SessionPersistence(
            store = store,
            contestId = cqww.id,
            settings = settings,
            seed = 7L,
            startMs = 1_000L,
        )

    /** Works one contact to completion; the operator copies what the station sends. */
    private fun logOneContact(controller: SessionController) {
        controller.tick(controller.lastNowMs)
        controller.editCall(controller.uiState.stationCall)
        controller.enter()
        var guard = 0
        while (controller.uiState.pendingText.isNotEmpty() && guard++ < 10) {
            val cue = controller.drainCues().firstOrNull() ?: break
            controller.tick(controller.lastNowMs + cue.durationMs + 1)
        }
        controller.tick(controller.lastNowMs + 2_000)
        controller.drainCues()
        controller.editExchange1("599")
        controller.editExchange2(controller.uiState.stationExchange.substringAfter(' '))
        controller.enter()
        controller.drainCues()
    }

    @Test
    fun `a logged contact is written and survives a restart`() {
        val backing = MapStringStore()
        val store = SessionStore(backing)
        val controller = session()
        val persistence = persistence(store)
        controller.tick(controller.lastNowMs)
        val worked = controller.uiState.stationCall

        logOneContact(controller)
        persistence.saveIfChanged(controller.uiState, controller.loggedRecords())

        val resumed = assertIs<SessionSnapshot>(SessionStore(backing).read(), "nothing was stored")
        assertEquals(7L, resumed.seed)
        assertEquals(1_000L, resumed.startMs)
        assertEquals(30 * 60_000L, resumed.durationMs)
        assertEquals(1, resumed.records.size)
        assertEquals(worked, resumed.records.single().call)
        assertEquals(controller.uiState.qsos, resumed.records.size)
        val restoredScore = resumed.records.sumOf { it.points } *
            resumed.records.flatMap { it.multipliers }.toSet().size
        assertEquals(controller.uiState.rawScore, restoredScore, "the log's score is rebuilt from the records")
        assertEquals(controller.uiState.entry, resumed.entry, "the boxes are part of what was typed")
    }

    @Test
    fun `an unchanged session is not rewritten on every tick`() {
        val backing = MapStringStore()
        val store = SessionStore(backing)
        val controller = session()
        val persistence = persistence(store)
        controller.tick(controller.lastNowMs)

        persistence.saveIfChanged(controller.uiState, controller.loggedRecords())
        val afterFirst = backing.writes
        repeat(25) { persistence.saveIfChanged(controller.uiState, controller.loggedRecords()) }

        assertEquals(afterFirst, backing.writes, "the 40 ms tick loop rewrote an unchanged session")
        assertEquals(1, afterFirst)
    }

    @Test
    fun `typing in a box is persisted, so the half-copied call is not lost`() {
        val backing = MapStringStore()
        val store = SessionStore(backing)
        val controller = session()
        val persistence = persistence(store)
        controller.tick(controller.lastNowMs)

        controller.editCall("DL1")
        persistence.saveIfChanged(controller.uiState, controller.loggedRecords())
        assertEquals("DL1", assertIs<SessionSnapshot>(store.read()).entry.call)

        controller.editCall("DL1A")
        persistence.saveIfChanged(controller.uiState, controller.loggedRecords())
        assertEquals("DL1A", assertIs<SessionSnapshot>(store.read()).entry.call)
    }

    @Test
    fun `a finished session leaves nothing to resume`() {
        val backing = MapStringStore()
        val store = SessionStore(backing)
        val controller = session()
        val persistence = persistence(store)
        controller.tick(controller.lastNowMs)
        persistence.saveIfChanged(controller.uiState, controller.loggedRecords())
        assertTrue(store.read() != null, "nothing to clear in the first place")

        controller.tick(1_000L + 30 * 60_000L)
        persistence.saveIfChanged(controller.uiState, controller.loggedRecords())
        assertNull(store.read(), "a finished contest came back as a resumable session")
    }

    @Test
    fun `the stored settings are the ones the session was started with`() {
        val backing = MapStringStore()
        val store = SessionStore(backing)
        val settings = SessionSettings(durationMinutes = 10, speedWpm = 40, pitchHz = 700, qrm = false, myCall = "BH4GZK")
        val controller = session(settings)
        controller.tick(controller.lastNowMs)

        persistence(store, settings).saveIfChanged(controller.uiState, controller.loggedRecords())

        val resumed = assertIs<SessionSnapshot>(store.read())
        assertEquals(settings, resumed.settings, "a resumed session must not adopt newer settings")
        assertEquals(10 * 60_000L, resumed.durationMs, "the effective duration is the contest's clamp of the setting")
    }
}
