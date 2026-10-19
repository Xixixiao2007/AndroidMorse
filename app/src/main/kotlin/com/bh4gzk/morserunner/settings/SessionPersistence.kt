package com.bh4gzk.morserunner.settings

import com.bh4gzk.morserunner.qso.QsoRecord
import com.bh4gzk.morserunner.session.SessionUiState

/**
 * Keeps the stored [SessionSnapshot] in step with a running session — F10's fix.
 *
 * The screen's tick loop calls [saveIfChanged] every 40 ms, so the interesting property is the
 * *negative* one: nothing is written unless the log, the boxes or the end-of-contest flag
 * changed. A 30-minute contest with an idle operator writes exactly the one snapshot it started
 * with, while a contest where the operator logs a contact and types the next call writes once
 * per change.
 *
 * A finished contest **clears** the snapshot: resuming a contest whose clock has run out would
 * put the operator back into a dead session, and the score is what they wanted to keep.
 */
class SessionPersistence(
    private val store: SessionStore,
    private val contestId: String,
    private val settings: SessionSettings,
    private val seed: Long,
    private val startMs: Long,
) {

    /** What was last written; `null` until the first write. */
    private var lastSignature: String? = null

    /** Writes the session if anything a resume would care about changed since the last call. */
    fun saveIfChanged(ui: SessionUiState, records: List<QsoRecord>) {
        if (ui.finished) {
            if (lastSignature != FINISHED) {
                store.clear()
                lastSignature = FINISHED
            }
            return
        }
        val signature = "${ui.qsos}|${ui.entry.call}|${ui.entry.exch1}|${ui.entry.exch2}"
        if (signature == lastSignature) return
        lastSignature = signature
        store.write(
            SessionSnapshot(
                contestId = contestId,
                seed = seed,
                startMs = startMs,
                // The contest's own clamp of the setting: what a resume has to compare against.
                durationMs = ui.durationMs,
                settings = settings,
                entry = ui.entry,
                records = records,
            ),
        )
    }

    private companion object {
        /** A signature no real state can produce, so "finished" is written exactly once. */
        const val FINISHED = "finished"
    }
}
