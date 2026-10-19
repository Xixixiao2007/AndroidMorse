package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.DEFAULT_STEP_BUDGET
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Expression
import com.bh4gzk.morserunner.expr.Value

/** What one simulated station sends, generated from the contest's `their` rules. */
data class GeneratedExchange(
    /** The first sent piece, e.g. CQ WW's `599` or SS's `123 A`. */
    val exch1: String,
    /** The second sent piece, e.g. CQ WW's `14` or SS's `72 OR`. */
    val exch2: String,
    /**
     * The pieces `their.parse` extracted, keyed exactly as scoring reads them
     * (`theirSect`, `theirChk`, … — [TheirExchange.parseIdentifier]). Empty when
     * the contest declares no parse.
     */
    val parsed: Map<String, Value>,
)

/** Generation either produced a station's exchange or says why it could not. */
sealed interface GenerationResult {
    data class Success(val exchange: GeneratedExchange) : GenerationResult
    data class Failure(val message: String) : GenerationResult
}

/**
 * Evaluates a contest's `their` generation rules for one station (spec §4/§6):
 *
 *  1. bind `exchange.their.bindings` — a call-history column or the serial;
 *  2. bind `their.dice` — the draws the caller took from [SeededRandom];
 *  3. evaluate `their.derive` **top to bottom** (a later derive may read an earlier one);
 *  4. evaluate `their.exch1` / `their.exch2`;
 *  5. parse `exch2` with `their.parse`, producing `their<Group>` identifiers.
 *
 * Every evaluation runs against [ContestConfig.environment], which merges the
 * contest's `constants` — a bare map would hide `arrlSections` and fail closed.
 *
 * `their.parse` applies to **`exch2`**: that is the piece the shipped configs
 * describe (SS sends `<nr> <prec>` then `<check> <section>`; the chk/sect pattern
 * matches the second), and `PressureTestTest.theirPieces` is the same contract. A
 * contest that needed to parse `exch1` would need a schema field to say so.
 *
 * Nothing is guessed: a missing binding, a missing draw, a non-string piece or a
 * parse mismatch is a [GenerationResult.Failure], never a fabricated exchange.
 */
class TheirExchangeGenerator(
    private val config: ContestConfig,
    private val calls: CallFacts = CallFacts.Unavailable,
) {

    private val their: TheirExchange = config.exchange.their

    fun generate(
        dice: Map<String, Double> = emptyMap(),
        history: Map<String, String> = emptyMap(),
        serial: Int? = null,
    ): GenerationResult {
        val environment = LinkedHashMap<String, Value>()
        environment.putAll(config.environment())

        for ((name, binding) in their.bindings) {
            when (binding) {
                is TheirBinding.HistoryColumn -> {
                    val value = history[binding.column]
                        ?: return GenerationResult.Failure(
                            "no call-history value for column '${binding.column}'",
                        )
                    environment[name] = Value.str(value)
                }
                TheirBinding.Serial -> {
                    val value = serial
                        ?: return GenerationResult.Failure(
                            "the config binds a serial number but none was supplied",
                        )
                    environment[name] = Value.num(value)
                }
            }
        }

        // Declaration order is the replay contract (ADR-0004 decision 3).
        for (name in their.dice) {
            val draw = dice[name]
                ?: return GenerationResult.Failure("missing declared draw '$name'")
            environment[name] = Value.num(draw)
        }

        for ((name, expression) in their.derive) {
            when (val result = expression.evaluate(environment, DEFAULT_STEP_BUDGET, calls)) {
                is EvalResult.Failure ->
                    return GenerationResult.Failure("their.derive.$name: ${result.error.render()}")
                is EvalResult.Success -> environment[name] = result.value
            }
        }

        val exch1 = when (val result = text(their.exch1, environment)) {
            is TextResult.Failure -> return GenerationResult.Failure("their.exch1: ${result.message}")
            is TextResult.Success -> result.text
        }
        val exch2 = when (val result = text(their.exch2, environment)) {
            is TextResult.Failure -> return GenerationResult.Failure("their.exch2: ${result.message}")
            is TextResult.Success -> result.text
        }

        val parsed = their.parse?.let { spec ->
            val matcher = spec.regex.matcher(exch2)
            if (!matcher.matches()) {
                return GenerationResult.Failure(
                    "their.exch2 '$exch2' does not match their.parse '${spec.pattern}'",
                )
            }
            spec.groupNames.associate { group ->
                TheirExchange.parseIdentifier(group) to Value.str(matcher.group(group))
            }
        } ?: emptyMap()

        return GenerationResult.Success(GeneratedExchange(exch1, exch2, parsed))
    }

    private fun text(
        expression: Expression,
        environment: Map<String, Value>,
    ): TextResult = when (val result = expression.evaluate(environment, DEFAULT_STEP_BUDGET, calls)) {
        is EvalResult.Failure -> TextResult.Failure(result.error.render())
        is EvalResult.Success -> when (val value = result.value) {
            is Value.Str -> TextResult.Success(value.value)
            else -> TextResult.Failure("produced ${value.typeName}, expected a string")
        }
    }

    private sealed interface TextResult {
        data class Success(val text: String) : TextResult
        data class Failure(val message: String) : TextResult
    }
}
