package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ExchangeField
import com.bh4gzk.morserunner.contest.FieldType
import com.bh4gzk.morserunner.contest.TheirExchange
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Expression
import com.bh4gzk.morserunner.expr.Value
import kotlin.math.roundToInt

/**
 * The operator's own station: call plus the values of its own exchange fields.
 *
 * `myCountry`/`myContinent`/`myZone`/`mySection` are not stored here — they are DXCC lookups of
 * [call] (spec §4), performed through the [CallFacts] a [ContestRules] is given.
 */
data class MyStation(
    val call: String,
    /** My exchange values by the config's field keys: `zone` → `3`, `section` → `OR`. */
    val exchange: Map<String, String> = emptyMap(),
)

/**
 * Everything a contest document says about *this* contact: how the typed boxes become fields,
 * whether the entry is valid, what was mis-copied, and what the contact is worth.
 *
 * This is the single place where `:core-qso` touches `:core-contest` (ADR-0005 §2). It owns no
 * rules of its own: the schema's compiled [com.bh4gzk.morserunner.expr.Expression]s decide both
 * validation (`validation`) and scoring (`scoring`), and the field types decide how a copy is
 * compared (upstream `TQso.CheckExch1/2`, `Log.pas:520-660`).
 */
class ContestRules(
    private val config: ContestConfig,
    private val me: MyStation,
    private val calls: CallFacts = CallFacts.Unavailable,
) : ExchangeRules {

    private val fields: List<ExchangeField> = config.exchange.myFields

    /**
     * Which fields make up which sent piece.
     *
     * With `exchange.sent` the document says the pieces are built from several fields (ARRL SS:
     * `nr prec` / `check section`); without it, one field per piece (schema §4). The split is
     * only used to label a piece-level verdict, so an even split is enough.
     */
    private val fieldPieces: List<List<ExchangeField>> =
        if (config.exchange.sent == null) {
            fields.map { listOf(it) }
        } else {
            val half = (fields.size + 1) / 2
            listOf(fields.take(half), fields.drop(half))
        }

    /** My exchange as it is transmitted, `<exch1> <exch2>`. */
    val operatorExchange: OperatorExchange = sentExchange()

    /** The operator identity the [MessageScript] needs. */
    val operator: Operator get() = Operator(me.call, operatorExchange)

    // --- entry ------------------------------------------------------------------

    /**
     * The typed boxes as the schema's fields.
     *
     * A contest with `entryParse` types the whole exchange into the boxes and the pattern splits
     * it (ARRL SS: `12 B 71 OR`); otherwise field *n* is box *n*.
     */
    fun typedFields(entry: QsoEntry): Map<String, String> {
        val parse = config.exchange.entryParse
        if (parse != null) {
            val whole = listOf(entry.exch1, entry.exch2)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val matcher = parse.regex.matcher(whole)
            if (matcher.matches()) {
                return parse.groupNames.mapNotNull { group ->
                    matcher.group(group)?.let { group to it }
                }.toMap()
            }
        }
        return mapOfNotNull(
            fields.getOrNull(0)?.key to entry.exch1.trim(),
            fields.getOrNull(1)?.key to entry.exch2.trim(),
        )
    }

    override fun validate(entry: QsoEntry): List<String> {
        val environment = config.environment() + typedValues(entry)
        return config.validation.mapNotNull { rule ->
            when (val result = rule.whenExpression.evaluate(environment, calls = calls)) {
                // Fail closed: a rule that cannot be evaluated is an error the operator must see,
                // not a silent pass. The value being unusable (a blank or non-numeric box) is the
                // most common cause and the rule's own message is the useful explanation.
                is EvalResult.Failure -> rule.error
                is EvalResult.Success -> if ((result.value as? Value.Bool)?.value == true) rule.error else null
            }
        }
    }

    // --- save-time verdict -------------------------------------------------------

    /**
     * Compares the logged contact against what the station actually sent.
     *
     * Per-field when [QsoTruth.fields] is known (upstream's granularity), piece-level otherwise
     * (ADR-0005 §6). The call comparison is upstream's `leCALL` (`Log.pas:1015-1021`).
     */
    fun judge(entry: QsoEntry, truth: QsoTruth): QsoVerdict {
        val errors = mutableListOf<QsoError>()
        val corrections = mutableListOf<String>()

        if (normaliseCall(entry.call) != normaliseCall(truth.call)) {
            errors += QsoError.Call
            corrections += truth.call
        }

        if (truth.fields.isNotEmpty()) {
            val typed = typedFields(entry)
            for ((key, trueValue) in truth.fields) {
                val field = fields.firstOrNull { it.key == key } ?: continue
                val typedValue = typed[key] ?: continue
                if (!sameValue(field, typedValue, trueValue)) {
                    errors += labelFor(field, trueValue)
                    corrections += trueValue
                }
            }
            return QsoVerdict(errors, corrections)
        }

        val typed = typedFields(entry)
        for ((index, piece) in fieldPieces.withIndex()) {
            val trueValue = if (index == 0) truth.exch1 else truth.exch2
            val typedValue = if (index == 0) entry.exch1 else entry.exch2
            val same = if (piece.size == 1) {
                sameValue(piece.single(), typedValue, trueValue)
            } else {
                canonical(typedValue) == canonical(trueValue)
            }
            if (!same) {
                errors += piece.firstOrNull()?.let { labelFor(it, trueValue) } ?: QsoError.Err
                corrections += trueValue
            }
        }
        return QsoVerdict(errors, corrections)
    }

    // --- scoring -----------------------------------------------------------------

    /**
     * Points and multiplier keys for one contact, from the contest's `scoring` block.
     *
     * The environment is exactly the one spec §4 promises for scoring: my typed fields plus
     * `theirCall`/`theirExch1`/`theirExch2`, the `their.parse` groups, the station identifiers
     * and the constants. A failed evaluation is returned as a [ScoreResult.failure] rather than
     * thrown — the contact is worth zero, and the reason is visible.
     */
    fun score(entry: QsoEntry, truth: QsoTruth): ScoreResult {
        val environment = buildMap {
            putAll(config.environment())
            putAll(typedValues(entry))
            // Spec §4 lists `myFields[].key` in the scoring environment too, and there the words
            // mean *my* exchange: IARU HF compares the station's zone/society with mine
            // (`theirExch2 == itu`). Validation reads the same keys from the entry boxes
            // instead. The two roles are different, so my own station wins when a value is
            // known, and the typed box is the fallback (ADR-0005 §10).
            me.exchange.forEach { (key, text) -> put(key, numericValue(me.exchange, key, text)) }
            put("theirCall", Value.str(truth.call))
            put("theirExch1", Value.str(truth.exch1))
            put("theirExch2", Value.str(truth.exch2))

            config.exchange.their.parse?.let { parse ->
                val matcher = parse.regex.matcher(truth.exch2)
                if (!matcher.matches()) {
                    return ScoreResult(0, emptySet(), "their.parse does not match '${truth.exch2}'")
                }
                for (group in parse.groupNames) {
                    val value = matcher.group(group) ?: continue
                    put(TheirExchange.parseIdentifier(group), Value.str(value))
                }
            }

            put("myCall", Value.str(me.call))
            calls.country(me.call)?.let { put("myCountry", Value.str(it)) }
            calls.continent(me.call)?.let { put("myContinent", Value.str(it)) }
            calls.zone(me.call)?.let { put("myZone", Value.num(it)) }
            calls.arrlSection(me.call)?.let { put("mySection", Value.str(it)) }
            me.exchange["zone"]?.toDoubleOrNull()?.let { if ("myZone" !in this) put("myZone", Value.num(it)) }
            me.exchange["section"]?.let { if ("mySection" !in this) put("mySection", Value.str(it)) }
        }

        val points = when (val result = config.scoring.points.evaluate(environment, calls = calls)) {
            is EvalResult.Failure -> return ScoreResult(0, emptySet(), result.error.render())
            is EvalResult.Success -> (result.value as? Value.Num)?.value?.roundToInt()
                ?: return ScoreResult(0, emptySet(), "scoring.points is not a number")
        }

        val multipliers = LinkedHashSet<String>()
        for (multiplier in config.scoring.multipliers) {
            val condition = multiplier.whenExpression
            if (condition != null) {
                when (val result = condition.evaluate(environment, calls = calls)) {
                    is EvalResult.Failure -> return ScoreResult(0, emptySet(), result.error.render())
                    is EvalResult.Success -> if ((result.value as? Value.Bool)?.value != true) continue
                }
            }
            when (val result = multiplier.key.evaluate(environment, calls = calls)) {
                is EvalResult.Failure -> return ScoreResult(0, emptySet(), result.error.render())
                is EvalResult.Success -> {
                    val key = (result.value as? Value.Str)?.value?.trim()
                        ?: return ScoreResult(0, emptySet(), "a multiplier key is not a string")
                    // An unknown DXCC entity contributes no multiplier (upstream only applies the
                    // country multiplier when the lookup succeeded, `CqWW.pas:246-262`).
                    if (key.isNotEmpty()) multipliers += key
                }
            }
        }
        return ScoreResult(points, multipliers)
    }

    // --- internals ---------------------------------------------------------------

    private fun sentExchange(): OperatorExchange {
        val sent = config.exchange.sent
            ?: return OperatorExchange(
                exch1 = fields.getOrNull(0)?.let { me.exchange[it.key] } ?: "",
                exch2 = fields.getOrNull(1)?.let { me.exchange[it.key] } ?: "",
            )
        val environment = config.environment() + me.exchange.mapValues { (key, value) ->
            numericValue(me.exchange, key, value)
        }
        return OperatorExchange(
            exch1 = render(sent.exch1, environment),
            exch2 = render(sent.exch2, environment),
        )
    }

    private fun render(
        expression: Expression,
        environment: Map<String, Value>,
    ): String = when (val result = expression.evaluate(environment, calls = calls)) {
        is EvalResult.Success -> valueText(result.value)
        // A sent-piece expression that cannot be evaluated is a broken document; the loader
        // validates identifiers, so this only happens with missing data. Silence beats an
        // exception in an audio callback, and an empty piece is visible on screen.
        is EvalResult.Failure -> ""
    }

    private fun valueText(value: Value): String = when (value) {
        is Value.Str -> value.value
        is Value.Num -> if (value.value == value.value.toLong().toDouble()) {
            value.value.toLong().toString()
        } else {
            value.value.toString()
        }
        is Value.Bool -> if (value.value) "true" else "false"
        is Value.List -> value.items.joinToString(" ") { valueText(it) }
    }

    /** The typed boxes as expression values, numbers for numeric field types (schema §4). */
    private fun typedValues(entry: QsoEntry): Map<String, Value> {
        val typed = typedFields(entry)
        return typed.mapValues { (key, text) -> numericValue(typed, key, text) }
    }

    private fun numericValue(fields: Map<String, String>, key: String, text: String): Value {
        val field = this.fields.firstOrNull { it.key == key }
        val number = text.trim().toDoubleOrNull()
        return if (field?.type?.numeric == true && number != null) Value.Num(number) else Value.Str(text.trim())
    }

    /**
     * Would the operator's copy mean the same as what was sent?
     *
     * Upstream compares typed values per exchange type (`Log.pas` `TQso.CheckExch1/2`): RST as a
     * number after the A/E/N → 1/5/9 input mapping, zones as numbers after T/O/A/N → 0/0/1/9,
     * everything else as text.
     */
    private fun sameValue(field: ExchangeField, typed: String, truth: String): Boolean =
        when (field.type) {
            FieldType.RST -> digits(typed) == digits(truth)
            FieldType.INT, FieldType.SERIAL, FieldType.CHECK -> {
                val typedNumber = digits(typed).toDoubleOrNull()
                val trueNumber = digits(truth).toDoubleOrNull()
                if (typedNumber != null && trueNumber != null) {
                    typedNumber == trueNumber
                } else {
                    canonical(typed) == canonical(truth)
                }
            }
            FieldType.ENUM, FieldType.TEXT -> {
                // A non-NA station sends no location, and upstream lets the operator log `DX`
                // in the second box (`Log.pas:945-949`, `etNaQpNonNaExch2`). The rule only
                // fires for an empty truth, so no other text field changes meaning.
                canonical(typed) == canonical(truth) ||
                    (truth.isBlank() && canonical(typed) == "DX")
            }
        }

    /** Upstream's digit/letter confusion table (`Main.pas:608-620`, `Log.pas:565-575`). */
    private fun digits(text: String): String = text.trim().uppercase().map { char ->
        when (char) {
            'T', 'O' -> '0'
            'A' -> '1'
            'E' -> '5'
            'N' -> '9'
            else -> char
        }
    }.joinToString("")

    private fun canonical(text: String): String = text.trim().uppercase().replace(Regex("\\s+"), " ")

    private fun normaliseCall(call: String): String = call.replace("?", "").trim().uppercase()

    /**
     * The label upstream would print in the log's error column for this field.
     *
     * [trueValue] is what the station actually sent, and only one contest reads it: CWops CWT's
     * second piece is `etGenericField`, whose mismatch upstream labels `leNR` when the true value
     * is numeric (a CWT member number) and `leQTH` otherwise (a state, province or country
     * prefix; `Log.pas:911-918`). Every other document's label depends on the field alone, so the
     * argument defaults to `null` and their mapping is unchanged (ADR-0014 §4).
     */
    private fun labelFor(field: ExchangeField, trueValue: String? = null): QsoError = when (field.type) {
        FieldType.RST -> QsoError.Rst
        FieldType.SERIAL -> QsoError.Number
        FieldType.CHECK -> QsoError.Check
        FieldType.INT -> if ("zone" in field.key || field.key == "zn") QsoError.Zone else QsoError.Number
        FieldType.ENUM -> if ("sect" in field.key) QsoError.Section else QsoError.Precedence
        FieldType.TEXT -> when {
            // NAQP labels its two text fields the way upstream does (`Log.pas:860,944`);
            // the field key is the config's own name, so this is naming, not a new rule.
            field.key == "name" -> QsoError.Name
            field.key == "state" -> QsoError.State
            // K1USN SST's second field is `etGenericField`, and upstream labels a mismatch on
            // it `leQTH` for that contest (`Log.pas:917-920`), not `leERR`.
            field.key == "qth" -> QsoError.Qth
            // JARL ALL JA's second field is `etJaPref`, which upstream labels `leNR`
            // (`Log.pas:942`); JARL ACAG's is `etJaCity`, the line after it, with the same
            // label (`Log.pas:943`). Both are naming, not a new rule.
            field.key == "pref" -> QsoError.Number
            field.key == "city" -> QsoError.Number
            // CWops CWT's second field is `etGenericField` with a *value-dependent* label
            // (`Log.pas:911-918`): a member number is `leNR`, anything else `leQTH`.
            field.key == "exch" -> if (isNumeric(trueValue)) QsoError.Number else QsoError.Qth
            "itu" in field.key || "soc" in field.key -> QsoError.Qth
            else -> QsoError.Err
        }
    }

    /** Upstream's `IsNum` (`CwOps.pas:263-274`): non-empty, ASCII digits only. */
    private fun isNumeric(text: String?): Boolean =
        !text.isNullOrEmpty() && text.all { it in '0'..'9' }

    private fun mapOfNotNull(vararg pairs: Pair<String?, String>): Map<String, String> =
        pairs.mapNotNull { (key, value) -> key?.let { it to value } }.toMap()
}
