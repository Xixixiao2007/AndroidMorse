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
 */
data class QsoRecord(
    val call: String,
    val exch1: String,
    val exch2: String,
    val points: Int,
    val multipliers: Set<String>,
    val elapsedMs: Long,
    val verified: Boolean = true,
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
 * Not included yet, and deliberately so: the field-level validations that need
 * the contest configuration (wrong RST, wrong exchange) — those arrive with T6
 * once T3's loader exists.
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
        val call = record.call.trim().uppercase()
        if (call.isEmpty()) return LogOutcome.Nil
        if (!calls.add(call)) return LogOutcome.Duplicate

        // One normalised record is stored AND returned: handing back the caller's
        // original object would leak the untrimmed callsign back through the
        // outcome, so a caller echoing it would show " w1aw " instead of "W1AW".
        val accepted = record.copy(call = call)
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

    companion object {
        /** Spec §6: a five-minute rate histogram. */
        const val WINDOW_MS = 5 * 60 * 1000L
    }
}
