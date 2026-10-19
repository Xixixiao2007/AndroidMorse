package com.bh4gzk.morserunner.settings

import com.bh4gzk.morserunner.qso.QsoEntry
import com.bh4gzk.morserunner.qso.QsoError
import com.bh4gzk.morserunner.qso.QsoRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-progress session, encoded so a process death does not lose the contest.
 *
 * F10 (T7) recorded the defect: "no persistence, so a process death loses the session". This
 * ticket closes it for the *minimal recoverable set* — the identity and clock that make the
 * session replayable, the settings it was started with, the boxes the operator was typing into,
 * and the log with its score. What cannot be recovered (the station on the air, the operator's
 * patience, a half-transmitted message) is regenerated from the seed, and ADR-0009 says so.
 */
class SessionSnapshotTest {

    private val settings = SessionSettings(speedWpm = 32, myCall = "BH4GZK", qrm = false)

    private fun record(
        call: String = "DL1ABC",
        exch1: String = "599",
        exch2: String = "14",
        points: Int = 3,
        multipliers: Set<String> = setOf("ZN-14"),
        elapsedMs: Long = 12_000L,
        verified: Boolean = true,
        errors: List<QsoError> = emptyList(),
        corrections: List<String> = emptyList(),
        scoreFailure: String? = null,
    ) = QsoRecord(
        call = call,
        exch1 = exch1,
        exch2 = exch2,
        points = points,
        multipliers = multipliers,
        elapsedMs = elapsedMs,
        verified = verified,
        errors = errors,
        corrections = corrections,
        scoreFailure = scoreFailure,
    )

    private fun snapshot(records: List<QsoRecord> = listOf(record())) = SessionSnapshot(
        contestId = "cqww",
        seed = 20_261_019L,
        startMs = 1_700_000_000_000L,
        durationMs = 30 * 60_000L,
        settings = settings,
        entry = QsoEntry(call = "DL1A", exch1 = "59"),
        records = records,
    )

    @Test
    fun `a snapshot survives an encode and decode`() {
        val original = snapshot()
        val decoded = SessionSnapshotCodec.decode(SessionSnapshotCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `awkward text in a contact survives too`() {
        // Exchange fields are free text from a contest config, and a multiplier key is a
        // composite string that upstream itself joins with `;`. None of that may break the
        // encoding or leak from one field into the next.
        val awkward = record(
            call = "DL1ABC",
            exch1 = "599\tA\nB",
            exch2 = "O'Hara, zone 14",
            multipliers = setOf("ZN-14", "ZN-14;Germany", "SEC:OR"),
            errors = listOf(QsoError.Zone, QsoError.Err),
            corrections = listOf("599 14", "text with ; and , and \\ inside"),
            scoreFailure = "evaluator: dividing by\tzero",
        )
        val original = snapshot(records = listOf(record(), awkward, record(call = "K1ABC", verified = false)))
        val decoded = SessionSnapshotCodec.decode(SessionSnapshotCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `an empty log and empty boxes round-trip`() {
        val original = snapshot(records = emptyList()).copy(entry = QsoEntry())
        val decoded = SessionSnapshotCodec.decode(SessionSnapshotCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `a snapshot that did not finish the contest can be resumed`() {
        val snap = snapshot()
        assertTrue(snap.isResumable(nowMs = snap.startMs + 60_000L), "a minute in is resumable")
        assertFalse(snap.isResumable(nowMs = snap.startMs + snap.durationMs), "the clock ran out")
        assertFalse(
            snap.isResumable(nowMs = snap.startMs + snap.durationMs + 1),
            "a contest older than its own duration is over",
        )
        assertFalse(snap.isResumable(nowMs = snap.startMs - 1), "a clock that went backwards is broken")
        assertFalse(snap.isResumable(nowMs = 0L), "an unsaved start is not a session")
    }

    @Test
    fun `a corrupt or foreign snapshot decodes to nothing instead of a broken session`() {
        assertNull(SessionSnapshotCodec.decode(null), "no store means no session")
        assertNull(SessionSnapshotCodec.decode(""), "an empty blob is not a session")
        assertNull(SessionSnapshotCodec.decode("not a snapshot"), "no version line")
        assertNull(
            SessionSnapshotCodec.decode("version=99\ncontest=cqww"),
            "a format this build does not know must not be guessed at",
        )
        assertNull(
            SessionSnapshotCodec.decode("version=1\ncontest=cqww\nseed=not a number"),
            "a session whose clock cannot be read is not resumable",
        )
    }

    @Test
    fun `the store writes and clears the session blob`() {
        val backing = MapStringStore()
        val store = SessionStore(backing)
        assertNull(store.read(), "a first run has no session")

        store.write(snapshot())
        assertEquals(snapshot(), SessionStore(backing).read(), "another process sees the session")

        store.clear()
        assertNull(store.read(), "a cleared session must not come back")
    }
}
