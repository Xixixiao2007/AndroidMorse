package com.bh4gzk.morserunner.qso

/**
 * One completed contact, as the log sees it.
 *
 * The contest engine decides [points] and [multipliers] (they come from the
 * contest configuration, ADR-0002/0004); the log only aggregates them. That
 * split is what lets this module stay free of contest rules.
 *
 * @param multipliers composite multiplier keys produced by the contest, e.g.
 *   `ZN-3` or `ZN-3;Germany`. Upstream keeps them as one `;`-separated string
 *   and splits on `;` when it fills the multiplier list
 *   (`Log.pas:250-253 TMultList.ApplyMults`), so a single QSO may contribute
 *   several keys at once.
 * @param verified false for a contact that was not confirmed (competition modes);
 *   upstream keeps raw and verified totals separately (`Log.pas:116-117`).
 * @param elapsedMs milliseconds since the session started, used for the
 *   5-minute rate histogram (spec §6).
 * @param errors save-time verdict: which fields look mis-copied (`Log.pas:998-1090`).
 *   An errored contact is still a logged contact, exactly as upstream's error
 *   column implies (ADR-0005 §8).
 * @param corrections what the station actually sent, for the log's correction
 *   column (`Log.pas:1030`).
 * @param scoreFailure why the contest scoring could not be evaluated, if it
 *   could not; [points] is then zero (ADR-0005 §2).
 */
data class QsoRecord(
    val call: String,
    val exch1: String,
    val exch2: String,
    val points: Int,
    val multipliers: Set<String>,
    val elapsedMs: Long,
    val verified: Boolean = true,
    val errors: List<QsoError> = emptyList(),
    val corrections: List<String> = emptyList(),
    val scoreFailure: String? = null,
)

/** What happened when a contact was handed to the log. */
sealed interface LogOutcome {

    /** The contact was accepted; the totals after it are included for convenience. */
    data class Accepted(
        val record: QsoRecord,
        val rawPoints: Int,
        val rawMultipliers: Int,
        val rawScore: Int,
    ) : LogOutcome

    /** The call is already in the log (`Log.pas:48 leDUP`). */
    data object Duplicate : LogOutcome

    /** Nothing to log: the callsign is empty (`Log.pas:48 leNIL`). */
    data object Nil : LogOutcome
}

/**
 * The contest log: accepted contacts, points, multipliers, score and the rate
 * histogram.
 *
 * Upstream keeps two parallel totals, raw and verified, because a competition
 * log may contain contacts that were never confirmed. Both are exposed here;
 * the histogram is always over accepted contacts.
 *
 * Two ways in: [record] with a [QsoRecord] (the raw form, used when the caller
 * already scored the contact) and [record] with a [QsoContact] (the validated
 * form, which carries the save-time verdict and the contest's scoring).
 */
class QsoLog {

    private val records = ArrayList<QsoRecord>(256)
    private val calls = HashSet<String>(256)
    private val rawMultiplierKeys = LinkedHashSet<String>()
    private val verifiedMultiplierKeys = LinkedHashSet<String>()

    private var rawPoints = 0
    private var verifiedPoints = 0

    val qsos: List<QsoRecord> get() = records

    val size: Int get() = records.size

    /** Sum of points over every accepted contact. */
    val rawPointsTotal: Int get() = rawPoints

    /** Sum of points over the confirmed contacts only. */
    val verifiedPointsTotal: Int get() = verifiedPoints

    val rawMultiplierCount: Int get() = rawMultiplierKeys.size

    val verifiedMultiplierCount: Int get() = verifiedMultiplierKeys.size

    /** score = points × multipliers, like upstream `RawPoints * MultList.Count`. */
    val rawScore: Int get() = rawPoints * rawMultiplierCount

    val verifiedScore: Int get() = verifiedPoints * verifiedMultiplierCount

    /**
     * Adds [record] unless it is a nil or duplicate contact.
     *
     * Duplicate detection is by callsign only, which is what upstream's `Dupe`
     * flag means; band-aware duplication is a later concern (the app has one
     * band per session).
     */
    fun record(record: QsoRecord): LogOutcome {
        val call = normalise(record.call)
        if (call.isEmpty()) return LogOutcome.Nil
        if (!calls.add(call)) return LogOutcome.Duplicate
        // One normalised record is stored AND returned: handing back the caller's
        // original object would leak the untrimmed callsign back through the
        // outcome, so a caller echoing it would show " w1aw " instead of "W1AW".
        return add(record.copy(call = call))
    }

    /**
     * Adds the contact described by [contact], computing its verdict and score
     * first.
     *
     * `NIL` and `DUP` are rejected outright (upstream flags them in the log, but a
     * contact that was not worked or is already in the log must not add points);
     * every other verdict is accepted *with* its errors, so the operator can see
     * what went wrong (ADR-0005 §8).
     */
    fun record(contact: QsoContact): LogOutcome {
        val call = normalise(contact.call)
        // TrueCall = '' means the station was never worked (Log.pas:1015).
        if (call.isEmpty() || !contact.worked) return LogOutcome.Nil
        if (!calls.add(call)) return LogOutcome.Duplicate
        return add(
            QsoRecord(
                call = call,
                exch1 = contact.exch1.trim(),
                exch2 = contact.exch2.trim(),
                points = contact.score.points,
                multipliers = contact.score.multipliers,
                elapsedMs = contact.elapsedMs,
                verified = contact.verified,
                errors = contact.verdict.errors,
                corrections = contact.verdict.corrections,
                scoreFailure = contact.score.failure,
            ),
        )
    }

    private fun add(accepted: QsoRecord): LogOutcome {
        records.add(accepted)
        rawPoints += accepted.points
        rawMultiplierKeys += accepted.multipliers
        if (accepted.verified) {
            verifiedPoints += accepted.points
            verifiedMultiplierKeys += accepted.multipliers
        }
        return LogOutcome.Accepted(accepted, rawPoints, rawMultiplierCount, rawScore)
    }

    /**
     * Contacts per five-minute window, index 0 being the first five minutes.
     * Trailing empty windows are dropped so the histogram ends at the last
     * contact.
     */
    fun rateHistogram(): List<Int> {
        if (records.isEmpty()) return emptyList()
        val lastWindow = records.maxOf { it.elapsedMs } / WINDOW_MS
        val histogram = MutableList(lastWindow.toInt() + 1) { 0 }
        for (record in records) {
            histogram[(record.elapsedMs / WINDOW_MS).toInt()]++
        }
        return histogram
    }

    /** Contacts per hour over the whole session, `0.0` when nothing was logged. */
    fun ratePerHour(): Double {
        if (records.isEmpty()) return 0.0
        val spanMs = records.maxOf { it.elapsedMs }
        if (spanMs <= 0) return 0.0
        return records.size * 3_600_000.0 / spanMs
    }

    fun clear() {
        records.clear()
        calls.clear()
        rawMultiplierKeys.clear()
        verifiedMultiplierKeys.clear()
        rawPoints = 0
        verifiedPoints = 0
    }

    /** Callsigns are compared without the `?` the operator uses as "unknown" (`Log.pas:686`). */
    private fun normalise(call: String): String = call.replace("?", "").trim().uppercase()

    companion object {
        /** Spec §6: a five-minute rate histogram. */
        const val WINDOW_MS = 5 * 60 * 1000L
    }
}
