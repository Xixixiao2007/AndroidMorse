package com.bh4gzk.morserunner.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-cutting contract: the step budget, exact `line:col` rendering, and the
 * "fail closed, never throw" promise of the public API.
 */
class ErrorsAndBudgetTest {

    private fun failure(
        source: String,
        budget: Int = DEFAULT_STEP_BUDGET,
        calls: CallFacts = CallFacts.Unavailable,
    ): ExprError = (evaluateExpression(source, emptyMap(), budget, calls) as EvalResult.Failure).error

    @Test
    fun `budget bounds evaluation steps`() {
        val expression = compileExpression("1 + 1") as CompileResult.Success
        // Each AST node costs one step: binary + left literal + right literal = 3.
        assertTrue(expression.expression.evaluate(budget = 3) is EvalResult.Success)

        val error = (expression.expression.evaluate(budget = 2) as EvalResult.Failure).error
        assertEquals(ErrorKind.STEP_BUDGET, error.kind)
        assertEquals("step budget of 2 exhausted", error.message)
    }

    @Test
    fun `exhausted budget still yields a precise position`() {
        val error = failure("1 + 2 + 3 + 4", budget = 4)
        assertEquals(ErrorKind.STEP_BUDGET, error.kind)
        assertEquals(1, error.position.line)
        assertTrue(error.position.column > 1)
    }

    @Test
    fun `non-positive budget is rejected, not silently ignored`() {
        assertEquals(ErrorKind.BAD_ARGUMENT, failure("1", budget = 0).kind)
        assertEquals(ErrorKind.BAD_ARGUMENT, failure("1", budget = -5).kind)
    }

    @Test
    fun `budget is spent on the chosen ternary branch only`() {
        val expression = compileExpression("true ? 1 : 99 * 99") as CompileResult.Success
        // conditional + condition + the chosen literal = 3 steps; the dead branch is free.
        assertTrue(expression.expression.evaluate(budget = 3) is EvalResult.Success)
    }

    @Test
    fun `multi-line sources report their real line and column`() {
        val error = failure("1 +\n  )")
        assertEquals("2:3 — expected an expression, found ')'", error.render())
    }

    @Test
    fun `columns count unicode code points, not UTF-16 units`() {
        val error = failure("'👍' + 🚫")
        assertEquals("unexpected character '🚫'", error.message)
        assertEquals("1:7 — unexpected character '🚫'", error.render())
    }

    @Test
    fun `render is the editor contract line colon col em dash message`() {
        val error = failure("zone >")
        assertEquals("1:7 — expected an expression, found end of expression", error.render())
    }

    @Test
    fun `compile errors surface through the one-line entry point`() {
        val error = (evaluateExpression("1 +") as EvalResult.Failure).error
        assertEquals(ErrorKind.SYNTAX, error.kind)
    }

    @Test
    fun `every bad input is returned, never thrown`() {
        val hostile = listOf(
            "",
            "1 +",
            "((",
            "1 / 0",
            "1 % 0",
            "unknown_field",
            "foo(1)",
            "matches('a', '[')",
            "1 + true",
            "(".repeat(1000) + "1" + ")".repeat(1000),
            "'",
            "1 in 2",
            "sum([1, 'x'])",
            "substr('a', 0)",
            "1e308 * 10",
        )
        for (source in hostile) {
            assertTrue(evaluateExpression(source) is EvalResult.Failure, "expected failure for: $source")
        }
    }

    @Test
    fun `compiled expression is reusable and pure`() {
        val compiled = compileExpression("zone + 1") as CompileResult.Success
        val first = compiled.expression.evaluate(mapOf("zone" to Value.num(1))) as EvalResult.Success
        val second = compiled.expression.evaluate(mapOf("zone" to Value.num(9))) as EvalResult.Success
        assertEquals(Value.num(2), first.value)
        assertEquals(Value.num(10), second.value)
        // Evaluating does not consume a shared budget: the next call starts fresh.
        assertTrue(compiled.expression.evaluate(emptyMap()) is EvalResult.Failure)
    }
}
