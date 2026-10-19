package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.qso.QsoEntry
import com.bh4gzk.morserunner.qso.QsoLog
import com.bh4gzk.morserunner.settings.SessionSettings
import com.bh4gzk.morserunner.settings.SessionStore
import com.bh4gzk.morserunner.settings.SettingsStore

/**
 * Which contest the screen shows, and everything a resume needs.
 *
 * @param contestId the contest document [SessionBootstrap] must load. For a resume it is the
 *   snapshot's own id — the running contest is never rewritten by the settings screen — and for
 *   a fresh contest it is [SessionSettings.contestId] (T10's picker).
 */
data class SessionPlan(
    val contestId: String,
    val settings: SessionSettings,
    val seed: Long,
    val startMs: Long,
    val log: QsoLog,
    val entry: QsoEntry?,
)

/**
 * The "resume or start fresh?" decision that T9 held privately in `MainActivity`.
 *
 * It lives here so the rule can be proven on the JVM (`SessionPlannerTest`) instead of only on a
 * device: a resumable snapshot wins with the settings the running contest was started with, a
 * finished or unreadable one is dropped, and a fresh contest starts from the saved settings —
 * including the contest the operator chose (T10, ADR-0010 §6).
 *
 * Pure: [SettingsStore] and [SessionStore] are both `StringStore`s, and `now` is the caller's
 * wall clock. No Android type is involved.
 */
object SessionPlanner {

    /** The session to put on screen at [now], clearing a snapshot that may not be resumed. */
    fun plan(settings: SettingsStore, sessions: SessionStore, now: Long): SessionPlan {
        val saved = sessions.read()
        val resume = saved?.takeIf { it.isResumable(now) }
        if (saved != null && resume == null) {
            // A finished contest must not be resumed tomorrow as a fresh one.
            sessions.clear()
        }
        if (resume == null) return fresh(settings, now)

        // A resumed contest keeps the settings it was started with, not whatever was saved
        // afterwards: changing the speed must not silently rewrite a running session.
        return SessionPlan(
            contestId = resume.contestId,
            settings = resume.settings,
            seed = resume.seed,
            startMs = resume.startMs,
            log = QsoLog().also { log -> resume.records.forEach { record -> log.record(record) } },
            entry = resume.entry,
        )
    }

    /** A new contest with the saved settings, starting now. */
    fun fresh(settings: SettingsStore, now: Long): SessionPlan {
        val stored = settings.load()
        return SessionPlan(
            contestId = stored.contestId,
            settings = stored,
            seed = now,
            startMs = now,
            log = QsoLog(),
            entry = null,
        )
    }
}
