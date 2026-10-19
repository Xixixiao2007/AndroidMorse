package com.bh4gzk.morserunner.settings

import com.bh4gzk.morserunner.qso.QsoEntry
import com.bh4gzk.morserunner.qso.QsoError
import com.bh4gzk.morserunner.qso.QsoRecord

/**
 * The minimum it takes to put a contest back on the screen after the process died (F10).
 *
 * What is stored is the **replayable** part: which contest, the seed and the wall-clock start
 * that the whole session is a function of (ADR-0007 §1, §6), the effective duration, the
 * settings it was started with, the boxes mid-typing, and the log with its verdicts and score.
 *
 * What is not stored — and is regenerated from the seed instead — is the live engine state: the
 * station currently on the air, the operator's patience, a half-transmitted message, the queue.
 * Replaying those would need a full event journal (every tick of a 30-minute contest), and a
 * resumed session whose *score* and *clock* are intact already answers what F10 was about: a
 * process death must not throw away what the operator has earned. ADR-0009 §"what a resume
 * cannot restore" states the limitation rather than hiding it.
 *
 * @param startMs the wall-clock millisecond the contest started; the elapsed time is
 *   `now - startMs`, so a resume keeps the contest's real clock rather than restarting it.
 * @param durationMs the *effective* duration — the setting clamped by the contest's own range
 *   — so [isResumable] needs no config at all.
 */
data class SessionSnapshot(
    val contestId: String,
    val seed: Long,
    val startMs: Long,
    val durationMs: Long,
    val settings: SessionSettings,
    val entry: QsoEntry,
    val records: List<QsoRecord>,
) {

    /** Whether a contest saved with this snapshot still has time left at [nowMs]. */
    fun isResumable(nowMs: Long): Boolean {
        if (startMs <= 0L || durationMs <= 0L) return false
        val elapsed = nowMs - startMs
        return elapsed >= 0L && elapsed < durationMs
    }
}

/**
 * [SessionSnapshot] as one `key=value` blob.
 *
 * The identity fields must be readable: a snapshot whose clock or seed does not parse is not a
 * session this build can trust, so [decode] answers `null` and the app starts fresh — that is
 * safer than resuming a contest at a made-up time.
 *
 * Each contact is one `rec=` line: ten tab-separated fields, every string field escaped
 * ([EncodedText]), so an exchange containing a tab, a comma or a backslash cannot shift a field.
 */
object SessionSnapshotCodec {

    /** The version this build writes and the only one it reads. */
    internal const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_CONTEST = "contest"
    private const val KEY_SEED = "seed"
    private const val KEY_START = "start"
    private const val KEY_DURATION = "duration"
    private const val KEY_ENTRY_CALL = "entry.call"
    private const val KEY_ENTRY_EXCH1 = "entry.exch1"
    private const val KEY_ENTRY_EXCH2 = "entry.exch2"

    /** Settings are stored under their own field names, prefixed so they cannot collide. */
    private const val SETTINGS_PREFIX = "set."

    /** One contact. Fields: call, exch1, exch2, points, multipliers, elapsed, verified, errors, corrections, failure. */
    private const val KEY_RECORD = "rec"
    private const val RECORD_FIELDS = 10

    fun encode(snapshot: SessionSnapshot): String {
        val settings = snapshot.settings.normalized()
        val lines = buildList {
            add(EncodedText.line(KEY_VERSION, VERSION.toString()))
            add(EncodedText.line(KEY_CONTEST, snapshot.contestId))
            add(EncodedText.line(KEY_SEED, snapshot.seed.toString()))
            add(EncodedText.line(KEY_START, snapshot.startMs.toString()))
            add(EncodedText.line(KEY_DURATION, snapshot.durationMs.toString()))
            for (line in SettingsCodec.encode(settings).split('\n')) {
                val separator = line.indexOf('=')
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                add("$SETTINGS_PREFIX$key=$value")
            }
            add(EncodedText.line(KEY_ENTRY_CALL, snapshot.entry.call))
            add(EncodedText.line(KEY_ENTRY_EXCH1, snapshot.entry.exch1))
            add(EncodedText.line(KEY_ENTRY_EXCH2, snapshot.entry.exch2))
            snapshot.records.forEachIndexed { index, record -> add(encodeRecord(index, record)) }
        }
        return lines.joinToString("\n")
    }

    fun decode(blob: String?): SessionSnapshot? {
        if (blob.isNullOrBlank()) return null
        // Raw values: a record's value still carries the escapes of its ten fields, and the
        // identity fields are unwrapped one by one below.
        val values = EncodedText.parseLines(blob)

        if (values[KEY_VERSION]?.trim()?.toIntOrNull() != VERSION) return null

        val contestId = values[KEY_CONTEST]?.let { EncodedText.unescape(it) }?.takeIf { it.isNotBlank() }
            ?: return null
        val seed = values[KEY_SEED]?.trim()?.toLongOrNull() ?: return null
        val startMs = values[KEY_START]?.trim()?.toLongOrNull() ?: return null
        val durationMs = values[KEY_DURATION]?.trim()?.toLongOrNull() ?: return null

        val settingsBlob = values
            .filterKeys { it.startsWith(SETTINGS_PREFIX) }
            .entries
            .joinToString("\n") { (key, value) ->
                "${key.removePrefix(SETTINGS_PREFIX)}=$value"
            }
        val settings = SettingsCodec.decode(settingsBlob)

        val records = values.entries
            .filter { it.key.startsWith("$KEY_RECORD.") }
            .sortedBy { it.key.removePrefix("$KEY_RECORD.").toIntOrNull() ?: Int.MAX_VALUE }
            .map { (_, value) -> decodeRecord(value) ?: return null }

        return SessionSnapshot(
            contestId = contestId,
            seed = seed,
            startMs = startMs,
            durationMs = durationMs,
            settings = settings,
            entry = QsoEntry(
                call = values[KEY_ENTRY_CALL]?.let { EncodedText.unescape(it) }.orEmpty(),
                exch1 = values[KEY_ENTRY_EXCH1]?.let { EncodedText.unescape(it) }.orEmpty(),
                exch2 = values[KEY_ENTRY_EXCH2]?.let { EncodedText.unescape(it) }.orEmpty(),
            ),
            records = records,
        )
    }

    private fun encodeRecord(index: Int, record: QsoRecord): String {
        val fields = listOf(
            EncodedText.escape(record.call),
            EncodedText.escape(record.exch1),
            EncodedText.escape(record.exch2),
            record.points.toString(),
            EncodedText.joinList(record.multipliers),
            record.elapsedMs.toString(),
            if (record.verified) "1" else "0",
            EncodedText.joinList(record.errors.map { it.name }),
            EncodedText.joinList(record.corrections),
            record.scoreFailure?.let { EncodedText.escape(it) } ?: "",
        )
        // The index keeps the log's order explicit and makes a truncated blob detectable.
        return "$KEY_RECORD.$index=" + fields.joinToString("\t")
    }
    private fun decodeRecord(value: String): QsoRecord? {
        val fields = EncodedText.splitEscaped(value, '\t')
        if (fields.size != RECORD_FIELDS) return null
        val points = fields[3].toIntOrNull() ?: return null
        val elapsedMs = fields[5].toLongOrNull() ?: return null
        val errors = EncodedText.splitList(fields[7]).map { name ->
            QsoError.entries.firstOrNull { it.name == name } ?: QsoError.Err
        }
        return QsoRecord(
            call = EncodedText.unescape(fields[0]),
            exch1 = EncodedText.unescape(fields[1]),
            exch2 = EncodedText.unescape(fields[2]),
            points = points,
            multipliers = EncodedText.splitList(fields[4]).toSet(),
            elapsedMs = elapsedMs,
            verified = fields[6] == "1",
            errors = errors,
            corrections = EncodedText.splitList(fields[8]),
            scoreFailure = EncodedText.unescape(fields[9]).takeIf { fields[9].isNotEmpty() },
        )
    }
}

/**
 * The session snapshot's home in the [StringStore].
 *
 * The snapshot is written over and over while a contest runs, and cleared when it ends: the app
 * must never resume a contest it already finished.
 */
class SessionStore(private val store: StringStore) {

    /** The resumable session, or `null` when there is none (or it cannot be read). */
    fun read(): SessionSnapshot? = SessionSnapshotCodec.decode(store.read(KEY))

    /** Replaces the stored session. */
    fun write(snapshot: SessionSnapshot) = store.write(KEY, SessionSnapshotCodec.encode(snapshot))

    /** Removes the stored session — nothing to resume. */
    fun clear() = store.write(KEY, null)

    companion object {
        /** The one key this concern owns. */
        const val KEY = "session"
    }
}
