package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.Expression
import com.bh4gzk.morserunner.expr.Value
import java.util.regex.Pattern

/**
 * One contest, as data (ADR-0002). Spec §4 is the contract; ADR-0004 records
 * why each field exists.
 *
 * Everything executable in here is an already-compiled [Expression]: the loader
 * compiles once and the QSO engine evaluates cheaply. Nothing in this model is
 * mutable and nothing reads the filesystem — [ContestLibrary] is the only place
 * that touches resources.
 */
data class ContestConfig(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val duration: Duration,
    val runModes: List<String>,
    /** Expression constants, injected as identifiers into every evaluation. */
    val constants: Map<String, Value>,
    val exchange: Exchange,
    val validation: List<ValidationRule>,
    val scoring: Scoring,
    val callHistory: CallHistory,
    val station: Station,
    val messages: Map<String, String>,
    val capabilities: Capabilities,
) {
    companion object {
        const val SUPPORTED_SCHEMA_VERSION: Int = 1

        /** Session length bounds, in minutes (spec §4). */
        val RUN_MODES: Set<String> = setOf("single-call", "pile-up", "competition")
    }

    /**
     * The environment every expression of this contest starts from: [constants]
     * plus whatever the caller binds for the current QSO.
     *
     * Evaluation never merges constants implicitly — a config that forgot to pass
     * them fails closed with `UNKNOWN_IDENTIFIER` instead of silently reading a
     * different contest's table.
     */
    fun environment(vararg bindings: Pair<String, Value>): Map<String, Value> =
        if (bindings.isEmpty()) constants else constants + bindings.toMap()
}

data class Duration(val defaultMinutes: Int, val minMinutes: Int, val maxMinutes: Int)

// --- exchange -----------------------------------------------------------------

/** The semantic shape of a field; decides the [Value] the expression environment binds. */
enum class FieldType(val numeric: Boolean) {
    /** Signal report, kept as text: `599`, `5NN`, `579`. */
    RST(numeric = false),
    INT(numeric = true),
    /** A running serial number. */
    SERIAL(numeric = true),
    ENUM(numeric = false),
    /** ARRL SS check: two digits, arithmetic is meaningful (`(check+1) % 100`). */
    CHECK(numeric = true),
    TEXT(numeric = false),
}

data class ExchangeField(
    val key: String,
    val type: FieldType,
    val label: String?,
    /** Sent-form override for this field (`599` is sent as `5NN`). */
    val send: String?,
    val range: IntRange?,
    /** Inline enum values. */
    val values: List<String>?,
    /** Or the name of a constant list, keeping 85 sections out of every field. */
    val valuesRef: String?,
)

/**
 * A whole-string parse with Java named groups.
 *
 * ARRL SS is the reason this exists: the operator types `123 A 72 OR` into one
 * box (`ArrlSS.pas:179-198`) and the program splits it into the two exchange
 * pieces it sends.
 */
data class ParseSpec(
    val pattern: String,
    val groupNames: List<String>,
    val regex: Pattern,
)

data class Exchange(
    val myFields: List<ExchangeField>,
    /** Optional single-box entry form parsed into the fields above. */
    val entryParse: ParseSpec?,
    /** Optional split into the two sent pieces; absent means "one field per piece". */
    val sent: SentExchange?,
    val their: TheirExchange,
) {
    /** Field keys, which are also the identifiers bound in expression environments. */
    val fieldKeys: Set<String> get() = myFields.map { it.key }.toSet()
}

data class SentExchange(val exch1: Expression, val exch2: Expression)

/**
 * How the station being worked is generated.
 *
 * The randomness is declared, not called (ADR-0004 §3): [dice] names the draws a
 * seeded generator supplies as numbers in `[0, 1)`, so a contest's behaviour is
 * reproducible and the set of random decisions is enumerable.
 */
data class TheirExchange(
    val dice: List<String>,
    val bindings: Map<String, TheirBinding>,
    /** Named intermediate expressions, evaluated top to bottom; later ones may read earlier ones. */
    val derive: Map<String, Expression>,
    /** Optional parse of the sent exchange into named pieces (`theirChk`, `theirSect`). */
    val parse: ParseSpec?,
    val exch1: Expression,
    val exch2: Expression,
) {
    /** Identifier bound for a parse group: `sect` → `theirSect`. */
    companion object {
        fun parseIdentifier(group: String): String =
            "their" + group.replaceFirstChar { it.uppercase() }
    }
}

/** Where a generation identifier comes from. */
sealed interface TheirBinding {
    /** A column of the call-history record for this station. */
    data class HistoryColumn(val column: String) : TheirBinding

    /**
     * The serial number the station sends.
     *
     * Upstream draws a fresh random serial per station from the contest's
     * configured range (`DxOper.GetNR` / `SerNRGen`), it is not a session counter;
     * T5 implements that from the config's `serial` exchange field range. The
     * engine supplies the value, so the expression stays deterministic.
     */
    data object Serial : TheirBinding
}

// --- rules --------------------------------------------------------------------

data class ValidationRule(
    /** Which entry field the message belongs to; `null` means the whole exchange. */
    val field: String?,
    val whenExpression: Expression,
    val error: String,
)

data class Scoring(
    val points: Expression,
    /**
     * Each multiplier contributes a key; `when` may switch it off entirely
     * (CQ WW `/MM` stations are a zone multiplier only, `CqWW.pas:236-240`).
     */
    val multipliers: List<Multiplier>,
)

data class Multiplier(val key: Expression, val whenExpression: Expression?)

// --- call history -------------------------------------------------------------

enum class ColumnType {
    /** Validated by the shared callsign grammar (upstream `Lexer.IsValidCall`). */
    CALLSIGN,

    /** Non-empty and all ASCII digits (upstream `IsNum`). */
    DIGITS,

    /** Parses as an integer (upstream `TryStrToInt`). */
    INTEGER,

    TEXT,
}

data class HistoryColumn(
    val name: String,
    /** Positional index; `null` when the header defines the columns. */
    val index: Int?,
    val type: ColumnType,
    val required: Boolean,
)

/** Header-driven column order (IARU HF: `!!Order!!,Call,Sect,UserText`, `IaruHf.pas:115-126`). */
data class HeaderSpec(
    /** The line that starts the header; it defines columns instead of being skipped. */
    val marker: String,
    /** Our column name → the label used in the file's header line. */
    val names: Map<String, String>,
)

data class CallHistory(
    val asset: String,
    val delimiter: Char,
    /** Lines starting with any of these are skipped (`!!Order!!`, `#`). */
    val skipPrefixes: List<String>,
    val header: HeaderSpec?,
    val minFields: Int,
    val columns: List<HistoryColumn>,
    val dedupe: Boolean,
    /** IARU HF refuses any history record unless the operator's call resolves in DXCC. */
    val requiresMyCallInDxcc: Boolean,
)

// --- station ------------------------------------------------------------------

data class Station(
    val callPool: String,
    /** Expressions over `speed` giving the per-station speed range. */
    val speedRange: SpeedRange,
    val exchangeDefaults: Map<String, String>,
)

data class SpeedRange(val min: Expression, val max: Expression)

data class Capabilities(
    /** Farnsworth timing is contest-gated (upstream `Contest.pas:200`); not these three. */
    val farnsworth: Boolean,
    /** IARU HF refuses to start unless the operator's call resolves (`IaruHf.pas:185-197`). */
    val requiresMyDxcc: Boolean,
)
