package com.bh4gzk.morserunner.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grammar-level tests. Precedence and associativity are proven *behaviourally*
 * (through evaluation) rather than by inspecting the AST, so the tests survive
 * an internal tree refactor while still failing if the grammar changes meaning.
 */
class ParserTest {

    private fun failure(source: String): ExprError =
        (compileExpression(source) as CompileResult.Failure).error

    private fun value(source: String): Value =
        (evaluateExpression(source) as EvalResult.Success).value

    @Test
    fun `empty source is a syntax error at 1 col 1`() {
        val error = failure("")
        assertEquals(ErrorKind.SYNTAX, error.kind)
        assertEquals("expression is empty", error.message)
        assertEquals("1:1 — expression is empty", error.render())
        // Whitespace and newlines alone are equally empty.
        assertEquals(ErrorKind.SYNTAX, failure("   \n  ").kind)
    }

    @Test
    fun `missing closing parenthesis points where the parser stopped`() {
        val error = failure("(1 + 2")
        assertEquals(ErrorKind.SYNTAX, error.kind)
        assertEquals("expected ')'", error.message)
        assertEquals(1, error.position.line)
        assertEquals(7, error.position.column)
    }

    @Test
    fun `missing closing bracket points where the parser stopped`() {
        val error = failure("[1, 2")
        assertEquals("expected ']' to close list literal", error.message)
        assertEquals(6, error.position.column)
    }

    @Test
    fun `trailing tokens are rejected`() {
        val error = failure("1 2")
        assertEquals("unexpected number '2' after end of expression", error.message)
        assertEquals(3, error.position.column)
    }

    @Test
    fun `incomplete expressions are syntax errors`() {
        assertEquals("expected an expression, found end of expression", failure("1 +").message)
        assertEquals("expected an expression, found '?'", failure("?").message)
        assertEquals("expected an expression, found ')'", failure("()").message)
    }

    @Test
    fun `conditional requires both branches`() {
        assertEquals("expected ':' in conditional expression", failure("a ? b").message)
    }

    @Test
    fun `unknown functions and wrong arity fail at compile time`() {
        val unknown = failure("foo(1)")
        assertEquals(ErrorKind.UNKNOWN_FUNCTION, unknown.kind)
        assertEquals("unknown function 'foo'", unknown.message)
        assertEquals(1, unknown.position.column)

        val arity = failure("matches('a')")
        assertEquals(ErrorKind.WRONG_ARITY, arity.kind)
        assertEquals("'matches' expects 2 arguments, got 1", arity.message)

        assertEquals(
            "'substr' expects 2 to 3 arguments, got 4",
            failure("substr('a', 1, 2, 3)").message,
        )
        assertEquals(
            "'ifElse' expects 3 arguments, got 2",
            failure("ifElse(true, 1)").message,
        )
    }

    @Test
    fun `literal regular expressions are validated at compile time`() {
        val error = failure("matches('abc', '[')")
        assertEquals(ErrorKind.BAD_REGEX, error.kind)
        assertTrue(error.message.startsWith("invalid regular expression"))
        assertEquals(1, error.position.line)
        assertEquals(16, error.position.column) // points at the pattern literal
    }

    @Test
    fun `deep nesting fails closed instead of overflowing the stack`() {
        val source = "(".repeat(1000) + "1" + ")".repeat(1000)
        val error = failure(source)
        assertEquals(ErrorKind.SYNTAX, error.kind)
        assertTrue(error.message.startsWith("expression nests too deeply"))
    }

    @Test
    fun `precedence and associativity`() {
        // * before +
        assertEquals(Value.num(14), value("2 + 3 * 4"))
        assertEquals(Value.num(20), value("(2 + 3) * 4"))
        assertEquals(Value.num(-10), value("2 - 3 * 4"))
        // and before or
        assertEquals(Value.bool(true), value("false or true and true"))
        assertEquals(Value.bool(false), value("(false or true) and false"))
        // not binds looser than ==, tighter than and
        assertEquals(Value.bool(true), value("not 1 == 2"))
        assertEquals(Value.bool(true), value("not (1 == 1) == false"))
        // left-associative arithmetic
        assertEquals(Value.num(5), value("10 - 3 - 2"))
        assertEquals(Value.num(2), value("8 / 2 / 2"))
        assertEquals(Value.num(1), value("9 % 4 % 3"))
        // unary minus binds tighter than *
        assertEquals(Value.num(-6), value("-2 * 3"))
        assertEquals(Value.num(2), value("--2"))
    }

    @Test
    fun `ternary is right-associative and lazy`() {
        assertEquals(Value.num(1), value("true ? 1 : false ? 2 : 3"))
        assertEquals(Value.num(2), value("false ? 1 : true ? 2 : 3"))
        assertEquals(Value.num(3), value("false ? 1 : false ? 2 : 3"))
    }
}
