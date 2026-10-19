package com.bh4gzk.morserunner.qso

/**
 * What the engine thinks went wrong with a logged contact.
 *
 * The labels are upstream's `TLogError` column strings (`Log.pas:29-31`) because operators read
 * that column and recognise the abbreviations. Only the values schema v1 can produce are
 * emitted; `NIL` and `DUP` are log verdicts rather than field errors, and are listed here so the
 * UI can render any verdict with one lookup (ADR-0005 §8).
 */
enum class QsoError(val label: String) {
    /** The station was never actually worked (`TrueCall = ''`, `Log.pas:1015`). */
    Nil("NIL"),

    /** The call is already in the log. */
    Dup("DUP"),

    /** The logged call is not the call the station sent. */
    Call("CALL"),

    Rst("RST"),

    /** The operator's name (NAQP, CWT, SST; `Log.pas:860` `leNAME`). */
    Name("NAME"),

    /** A serial number (or an untyped number) is wrong. */
    Number("NR"),

    Zone("ZN"),

    Section("SEC"),

    /** A state, province or DXCC prefix (NAQP; `Log.pas:944` `leST`). */
    State("ST"),

    Check("CHK"),

    /** ARRL SS precedence letter. */
    Precedence("PREC"),

    /** IARU society / operator location. */
    Qth("QTH"),

    /** A field the schema cannot classify; upstream's fallback label. */
    Err("ERR"),
}

/**
 * The save-time verdict for one contact: which fields look wrong, and what the station actually
 * sent (upstream's `Corrections` list, `Log.pas:1030`).
 */
data class QsoVerdict(
    val errors: List<QsoError> = emptyList(),
    val corrections: List<String> = emptyList(),
) {
    companion object {
        val None = QsoVerdict()
    }
}

/**
 * What the station being worked actually sent.
 *
 * This is the seam between the roster (which generates a station) and the engine (which judges
 * the contact) — upstream's `TrueCall`/`TrueRst`/`TrueExch*`, filled by
 * `TDxStation.DataToLastQso` (`DxStn.pas:246`). `core-qso` deliberately does not import the
 * roster: `app` maps whatever the roster exposes onto this (ADR-0005 §3).
 *
 * @param fields the per-field truth when the generator knows it (`prec`, `check`, `section`, …).
 *   Empty is normal for a two-piece station, and the verdict then names the piece instead of the
 *   field (ADR-0005 §6).
 */
data class QsoTruth(
    val call: String,
    val exch1: String,
    val exch2: String,
    val fields: Map<String, String> = emptyMap(),
)

/**
 * Points and multiplier keys for one contact, as the contest's `scoring` block computed them.
 *
 * @param failure non-null when an expression could not be evaluated (a missing data asset, a
 *   config that slipped a type error through). The contact is still logged — with zero points —
 *   because losing the contact would hide the problem; the failure string is what makes it
 *   visible.
 */
data class ScoreResult(
    val points: Int,
    val multipliers: Set<String>,
    val failure: String? = null,
) {
    companion object {
        val Zero = ScoreResult(0, emptySet())
    }
}

/**
 * One contact offered to the log, with everything the verdict needs.
 *
 * @param worked false when the station was never completed (upstream `TrueCall = ''`): the log
 *   answers [LogOutcome.Nil] and keeps nothing.
 */
data class QsoContact(
    val call: String,
    val exch1: String,
    val exch2: String,
    val score: ScoreResult,
    val verdict: QsoVerdict = QsoVerdict.None,
    val worked: Boolean = true,
    val elapsedMs: Long,
    val verified: Boolean = true,
)
