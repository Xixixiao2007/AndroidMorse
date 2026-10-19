package com.bh4gzk.morserunner.expr

/** Evaluation budget used when the caller does not pass one (spec §5: default 10 000). */
const val DEFAULT_STEP_BUDGET: Int = 10_000

/** Outcome of [compileExpression]: either a reusable program or a typed error. */
sealed interface CompileResult {
    data class Success(val expression: Expression) : CompileResult
    data class Failure(val error: ExprError) : CompileResult
}

/** Outcome of [Expression.evaluate] / [evaluateExpression]: a value or a typed error. */
sealed interface EvalResult {
    data class Success(val value: Value) : EvalResult
    data class Failure(val error: ExprError) : EvalResult
}

/**
 * A compiled expression. Compiling is separate from evaluating so a contest
 * loader (T3) can validate every rule in a config **once at load time** and
 * then evaluate cheaply per QSO.
 *
 * The program is immutable and evaluating it is pure: each [evaluate] call gets
 * a fresh evaluator and a fresh budget, so one instance can be shared by the
 * QSO engine without locking.
 */
class Expression internal constructor(internal val root: Node) {

    /**
     * Evaluates the program against [environment].
     *
     * @param environment identifier bindings (exchange fields, station
     *   properties, constants). A name that is absent fails closed with
     *   [ErrorKind.UNKNOWN_IDENTIFIER] — the language never invents a value.
     * @param budget maximum number of evaluation steps (default
     *   [DEFAULT_STEP_BUDGET]); every AST node costs one step and list built-ins
     *   charge per element. Exhausting it fails with [ErrorKind.STEP_BUDGET].
     * @param calls data provider for the four callsign built-ins; the default
     *   knows nothing and those built-ins fail closed with
     *   [ErrorKind.CALL_DATA_MISSING].
     */
    fun evaluate(
        environment: Map<String, Value> = emptyMap(),
        budget: Int = DEFAULT_STEP_BUDGET,
        calls: CallFacts = CallFacts.Unavailable,
    ): EvalResult {
        if (budget <= 0) {
            return EvalResult.Failure(
                ExprError(
                    ErrorKind.BAD_ARGUMENT,
                    SourcePosition(offset = 0, line = 1, column = 1),
                    "step budget must be positive, got $budget",
                ),
            )
        }
        return try {
            EvalResult.Success(Evaluator(environment, calls, budget).evaluate(root))
        } catch (failure: ExprFailure) {
            EvalResult.Failure(failure.error)
        }
    }
}

/**
 * Compiles [source] into an [Expression].
 *
 * Never throws for bad input: a syntax, lexical, unknown-function, arity or
 * literal-regex problem is returned as [CompileResult.Failure] with an exact
 * `line:col` (spec §5).
 */
fun compileExpression(source: String): CompileResult = try {
    CompileResult.Success(Expression(Parser(Lexer(source).tokenize()).parse()))
} catch (failure: ExprFailure) {
    CompileResult.Failure(failure.error)
}

/**
 * One-line entry point: compile and evaluate in a single call.
 *
 * This is the seam most callers need (validation rules, scoring multipliers);
 * use [compileExpression] instead when the same source is evaluated repeatedly.
 */
fun evaluateExpression(
    source: String,
    environment: Map<String, Value> = emptyMap(),
    budget: Int = DEFAULT_STEP_BUDGET,
    calls: CallFacts = CallFacts.Unavailable,
): EvalResult = when (val compiled = compileExpression(source)) {
    is CompileResult.Success -> compiled.expression.evaluate(environment, budget, calls)
    is CompileResult.Failure -> EvalResult.Failure(compiled.error)
}
