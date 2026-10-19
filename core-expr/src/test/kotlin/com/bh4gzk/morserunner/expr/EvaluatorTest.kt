package com.bh4gzk.morserunner.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Operators, precedence-sensitive combinations, typing rules and identifier lookup. */
class EvaluatorTest {

    private fun eval(source: String, env: Map<String, Value> = emptyMap()): Value =
        (evaluateExpression(source, env) as EvalResult.Success).value

    private fun error(source: String, env: Map<String, Value> = emptyMap()): ExprError =
        (evaluateExpression(source, env) as EvalResult.Failure).error

    @Test
    fun `arithmetic operators`() {
        assertEquals(Value.num(3), eval("1 + 2"))
        assertEquals(Value.num(-1), eval("1 - 2"))
        assertEquals(Value.num(6), eval("2 * 3"))
        assertEquals(Value.num(0.5), eval("1 / 2"))
        assertEquals(Value.num(1), eval("7 % 3"))
        assertEquals(Value.num(2.5), eval("0.5 + 2"))
    }

    @Test
    fun `division and remainder by zero fail closed at the operator`() {
        val division = error("1 / 0")
        assertEquals(ErrorKind.DIVISION_BY_ZERO, division.kind)
        assertEquals("division by zero", division.message)
        assertEquals(3, division.position.column)

        val remainder = error("1 % 0")
        assertEquals(ErrorKind.DIVISION_BY_ZERO, remainder.kind)
        assertEquals("remainder by zero", remainder.message)
    }

    @Test
    fun `numeric overflow is a typed error, not infinity`() {
        val error = error("1e308 * 10")
        assertEquals(ErrorKind.NUMERIC_OVERFLOW, error.kind)
        assertEquals("numeric overflow", error.message)
    }

    @Test
    fun `boolean operators short-circuit`() {
        // The right-hand side is never evaluated in both cases, so 1/0 must not run.
        assertEquals(Value.bool(false), eval("false and (1 / 0 == 1)"))
        assertEquals(Value.bool(true), eval("true or (1 / 0 == 1)"))
        assertEquals(Value.bool(true), eval("true and not false"))
        assertEquals(Value.bool(false), eval("not true"))
        assertEquals(Value.bool(true), eval("not (1 == 2)"))
    }

    @Test
    fun `comparison operators`() {
        assertEquals(Value.bool(true), eval("1 == 1"))
        assertEquals(Value.bool(true), eval("1 != 2"))
        assertEquals(Value.bool(true), eval("1 < 2"))
        assertEquals(Value.bool(true), eval("2 <= 2"))
        assertEquals(Value.bool(true), eval("3 > 2"))
        assertEquals(Value.bool(true), eval("3 >= 3"))
        assertEquals(Value.bool(false), eval("3 < 2"))
        assertEquals(Value.bool(true), eval("1.0 == 1"))
    }

    @Test
    fun `equality is structural and type-aware`() {
        assertEquals(Value.bool(true), eval("'abc' == 'abc'"))
        assertEquals(Value.bool(false), eval("'1' == 1"))
        assertEquals(Value.bool(true), eval("'1' != 1"))
        assertEquals(Value.bool(true), eval("true == true"))
        assertEquals(Value.bool(true), eval("[1, 2] == [1, 2]"))
        assertEquals(Value.bool(false), eval("[1, [2]] == [1, [3]]"))
        assertEquals(Value.bool(true), eval("[] == []"))
    }

    @Test
    fun `ordering comparisons are numbers only`() {
        val error = error("'a' < 'b'")
        assertEquals(ErrorKind.TYPE, error.kind)
        assertEquals("expected number, got string", error.message)
        assertEquals(1, error.position.column)
    }

    @Test
    fun `in checks list membership and string substring`() {
        assertEquals(Value.bool(true), eval("2 in [1, 2, 3]"))
        assertEquals(Value.bool(false), eval("4 in [1, 2, 3]"))
        assertEquals(Value.bool(true), eval("'NL' in 'W1NL'"))
        assertEquals(Value.bool(false), eval("'DL' in 'W1NL'"))

        val error = error("1 in 2")
        assertEquals(ErrorKind.TYPE, error.kind)
        assertTrue(error.message.contains("list"))
    }

    @Test
    fun `plus concatenates two strings and rejects mixed types`() {
        assertEquals(Value.str("AB"), eval("'A' + 'B'"))

        val mixed = error("1 + '2'")
        assertEquals(ErrorKind.TYPE, mixed.kind)
        assertEquals("cannot add number and string", mixed.message)
        assertEquals(5, mixed.position.column) // the string operand

        assertEquals("cannot add boolean and boolean", error("true + true").message)
    }

    @Test
    fun `type errors point at the offending operand`() {
        val minus = error("1 - true")
        assertEquals(ErrorKind.TYPE, minus.kind)
        assertEquals("expected number, got boolean", minus.message)
        assertEquals(5, minus.position.column)

        assertEquals("expected boolean, got number", error("true and 1").message)
        assertEquals("expected boolean, got number", error("not 1").message)
        assertEquals("expected boolean, got number", error("1 ? 2 : 3").message)
    }

    @Test
    fun `identifiers resolve against the environment`() {
        val env = mapOf(
            "zone" to Value.num(5),
            "prec" to Value.str("M"),
            "check" to Value.num(70),
        )
        assertEquals(Value.bool(true), eval("zone >= 1 and zone <= 40", env))
        assertEquals(Value.bool(true), eval("prec == 'M' and check > 65", env))
        assertEquals(Value.num(5), eval("zone", env))
    }

    @Test
    fun `unknown identifiers fail with their position`() {
        val error = error("1 + missing")
        assertEquals(ErrorKind.UNKNOWN_IDENTIFIER, error.kind)
        assertEquals("unknown identifier 'missing'", error.message)
        assertEquals(5, error.position.column)
    }

    @Test
    fun `list literals nest and may be empty`() {
        assertEquals(Value.list(Value.num(1), Value.num(2)), eval("[1, 2]"))
        assertEquals(Value.list(Value.list(), Value.list(Value.num(1))), eval("[[], [1]]"))
        assertEquals(Value.num(0), eval("len([])"))
        assertEquals(Value.num(1), eval("len([[]])"))
    }

    @Test
    fun `strings are unicode`() {
        assertEquals(Value.num(2), eval("len('👍汉')"))
        assertEquals(Value.str("汉A"), eval("upper('汉a')"))
    }
}
