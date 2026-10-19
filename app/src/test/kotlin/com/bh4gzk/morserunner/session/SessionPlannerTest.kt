package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.qso.QsoEntry
import com.bh4gzk.morserunner.qso.QsoError
import com.bh4gzk.morserunner.qso.QsoRecord
import com.bh4gzk.morserunner.settings.MapStringStore
import com.bh4gzk.morserunner.settings.SessionSettings
import com.bh4gzk.morserunner.settings.SessionSnapshot
import com.bh4gzk.morserunner.settings.SessionStore
import com.bh4gzk.morserunner.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Which session does the app put on screen?" — the decision T9 held privately in `MainActivity`
 * and T10 extracted so the contest choice is provable on the JVM.
 *
 * The rule is unchanged from T9/ADR-0009: a resumable snapshot wins (with the settings it was
 * started with), a finished or unreadable one is dropped, and otherwise a fresh contest starts
 * from the saved settings. T10's addition is the first branch's input: the **fresh** contest is
 * `SessionSettings.contestId`, normalized to a contest this build can load.
 */
class SessionPlannerTest {

    private val backing = MapStringStore()
    private val settings = SettingsStore(backing)
    private val sessions = SessionStore(backing)

    private fun snapshot(
        contestId: String = "cqww",
        seed: Long = 7L,
        startMs: Long = 1_000L,
        durationMs: Long = 30 * 60_000L,
        records: List<QsoRecord> = emptyList(),
        entry: QsoEntry = QsoEntry(),
    ) = SessionSnapshot(
        contestId = contestId,
        seed = seed,
        startMs = startMs,
        durationMs = durationMs,
        settings = SessionSettings(contestId = contestId, speedWpm = 18),
        entry = entry,
        records = records,
    )

    @Test
    fun `a fresh session is the contest the settings chose`() {
        settings.save(SessionSettings(contestId = "naqp", speedWpm = 40))
        val plan = SessionPlanner.plan(settings, sessions, now = 10_000L)

        assertEquals("naqp", plan.contestId, "重开一局 must start the chosen contest")
        assertEquals("naqp", plan.settings.contestId)
        assertEquals(40, plan.settings.speedWpm)
        assertEquals(10_000L, plan.seed)
        assertEquals(10_000L, plan.startMs)
        assertTrue(plan.log.qsos.isEmpty())
        assertNull(plan.entry)
    }

    @Test
    fun `a first run starts CQ WW`() {
        assertEquals("cqww", SessionPlanner.plan(settings, sessions, now = 1L).contestId)
    }

    @Test
    fun `a stored contest this build does not ship cannot be started`() {
        // A settings blob from a build that shipped another contest, or a hand-edited value.
        backing.write(SettingsStore.KEY, "contest=arrlfd\nspeed=30")
        val plan = SessionPlanner.plan(settings, sessions, now = 5L)
        assertEquals("cqww", plan.contestId)
        assertEquals(30, plan.settings.speedWpm, "the rest of the settings are still read")
    }

    @Test
    fun `a resumable snapshot keeps its own contest and settings`() {
        settings.save(SessionSettings(contestId = "naqp", speedWpm = 40))
        sessions.write(snapshot(contestId = "cqww", seed = 7L, startMs = 1_000L, entry = QsoEntry("DL1A", "599", "14")))

        val plan = SessionPlanner.plan(settings, sessions, now = 2_000L)
        assertEquals("cqww", plan.contestId, "the running contest is not rewritten by the picker")
        assertEquals(18, plan.settings.speedWpm, "a resume keeps the settings the session started with")
        assertEquals(7L, plan.seed)
        assertEquals(1_000L, plan.startMs)
        assertEquals("DL1A", plan.entry?.call, "the boxes are part of what a resume restores")
    }

    @Test
    fun `the log is replayed into the fresh controller`() {
        sessions.write(
            snapshot(
                records = listOf(
                    QsoRecord(
                        call = "DL1ABC",
                        exch1 = "599",
                        exch2 = "14",
                        points = 3,
                        multipliers = setOf("ZN-14"),
                        elapsedMs = 12_000L,
                        errors = listOf(QsoError.Rst),
                    ),
                ),
            ),
        )
        val plan = SessionPlanner.plan(settings, sessions, now = 2_000L)
        assertEquals(1, plan.log.size)
        assertEquals(3, plan.log.rawPointsTotal)
        assertEquals(1, plan.log.rawMultiplierCount)
    }

    @Test
    fun `a finished or broken snapshot is dropped and a fresh contest starts`() {
        // The clock ran out: a finished contest must not be resumed tomorrow as a fresh one.
        sessions.write(snapshot(contestId = "naqp", startMs = 0L, durationMs = 60_000L))
        settings.save(SessionSettings(contestId = "naqp"))

        val plan = SessionPlanner.plan(settings, sessions, now = 10_000_000L)
        assertEquals("naqp", plan.contestId, "a fresh contest starts from the saved settings")
        assertNull(sessions.read(), "the finished snapshot must be cleared")
        assertTrue(plan.log.qsos.isEmpty())
    }
}
