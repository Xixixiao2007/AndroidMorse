package com.bh4gzk.morserunner.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **T16: the `callWeight` built-in** — the one language extension HST needed (ADR-0016 §2/§5).
 *
 * `callWeight(call)` is `Log.pas:586-597` `CallToScore`: the sending time of the callsign in
 * keyer element units, which is exactly HST's per-contact score. The arithmetic is pinned in
 * `MorseWeightTest`; this test pins the *language contract* — the value's type, the arity rule
 * at compile time, and that the work is charged against the step budget like every other
 * string built-in.
 */
class CallWeightTest {

    private fun eval(
        source: String,
        environment: Map<String, Value> = emptyMap(),
        budget: Int = DEFAULT_STEP_BUDGET,
    ): Value = when (val result = evaluateExpression(source, environment, budget, CallFacts.Unavailable)) {
        is EvalResult.Success -> result.value
        is EvalResult.Failure -> throw AssertionError("unexpected failure: ${result.error.render()}")
    }

    private fun failure(source: String, budget: Int = DEFAULT_STEP_BUDGET): ExprError {
        val result = evaluateExpression(source, emptyMap(), budget, CallFacts.Unavailable)
        assertTrue(result is EvalResult.Failure, "expected a failure for: $source")
        return result.error
    }

    private fun compileFailure(source: String): ExprError {
        val result = compileExpression(source)
        assertTrue(result is CompileResult.Failure, "expected a compile failure for: $source")
        return result.error
    }

    @Test
    fun `a callsign's weight is the number HST scores it`() {
        // The four worked examples of ADR-0016 §3, hand-counted from VCL/MorseTbl.pas.
        assertEquals(Value.num(63.0), eval("callWeight('K1ABC')"))
        assertEquals(Value.num(49.0), eval("callWeight('W1AW')"))
        assertEquals(Value.num(75.0), eval("callWeight('JA1ABC')"))
        assertEquals(Value.num(99.0), eval("callWeight('DL/AA1ZZ')"))
    }

    @Test
    fun `the built-in reads the scoring environment's theirCall`() {
        // The whole point: HST's points expression is `callWeight(theirCall)` in the document,
        // with no extra identifier in spec §4's environment table.
        assertEquals(
            Value.num(63.0),
            eval("callWeight(theirCall)", mapOf("theirCall" to Value.str("K1ABC"))),
        )
        // It composes like any other number: a future score rule may scale it.
        assertEquals(Value.num(125.0), eval("callWeight(theirCall) * 2 - 1", mapOf("theirCall" to Value.str("K1ABC"))))
    }

    @Test
    fun `callWeight takes exactly one argument`() {
        assertEquals(ErrorKind.WRONG_ARITY, compileFailure("callWeight()").kind)
        assertEquals(ErrorKind.WRONG_ARITY, compileFailure("callWeight('K1ABC', 'W1AW')").kind)
    }

    @Test
    fun `a non-string argument is a type error, not a guess`() {
        val error = failure("callWeight(1)")
        assertEquals(ErrorKind.TYPE, error.kind)
    }

    @Test
    fun `the weight is charged per character against the step budget`() {
        // Every evaluator node costs a step; a string built-in additionally charges what it
        // inspects (ADR-0003's bound). A call is short, but the rule has to hold or a long
        // string could be a free loop.
        val shortBudget = 2 // the call node plus its literal, then the characters
        val error = failure("callWeight('K1ABCK1ABCK1ABC')", budget = shortBudget)
        assertEquals(ErrorKind.STEP_BUDGET, error.kind)
        // With room for the characters it succeeds: the charge is the length, not an unbounded loop.
        val roomy = 2 + "K1ABCK1ABCK1ABC".length
        // Not 3 × 63: the trailing gap is charged per character and only the *last* character of
        // the whole string loses its space, so concatenating three calls is not additive —
        // exactly what the transcribed loop says (ADR-0016 §3).
        assertEquals(Value.num(195.0), eval("callWeight('K1ABCK1ABCK1ABC')", budget = roomy))
    }
}
