package com.bh4gzk.morserunner.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One test group per v1 built-in from spec §5, including the callsign data port. */
class BuiltinsTest {

    private fun eval(
        source: String,
        env: Map<String, Value> = emptyMap(),
        calls: CallFacts = CallFacts.Unavailable,
    ): Value = (evaluateExpression(source, env, calls = calls) as EvalResult.Success).value

    private fun error(
        source: String,
        env: Map<String, Value> = emptyMap(),
        calls: CallFacts = CallFacts.Unavailable,
    ): ExprError = (evaluateExpression(source, env, calls = calls) as EvalResult.Failure).error

    /** Stand-in for the DXCC/section data that a later ticket will load (spec §9). */
    private object FakeFacts : CallFacts {
        override fun prefix(call: String): String? = when (call) {
            "W1AW" -> "W1"
            "DL1ABC" -> "DL1"
            else -> null
        }

        override fun zone(call: String): Double? = when (call) {
            "W1AW" -> 5.0
            "DL1ABC" -> 14.0
            else -> null
        }

        override fun country(call: String): String? = when (call) {
            "W1AW" -> "United States"
            "DL1ABC" -> "Germany"
            else -> null
        }

        override fun arrlSection(call: String): String? = when (call) {
            "W1AW" -> "CT"
            else -> null
        }
    }

    @Test
    fun `upper and lower`() {
        assertEquals(Value.str("ABC"), eval("upper('abc')"))
        assertEquals(Value.str("abc"), eval("lower('ABC')"))
        assertEquals(Value.str("SS"), eval("upper('ß')")) // full Unicode case mapping
        assertEquals(ErrorKind.TYPE, error("upper(1)").kind)
        assertEquals(ErrorKind.TYPE, error("lower(true)").kind)
    }

    @Test
    fun `len counts code points for strings and elements for lists`() {
        assertEquals(Value.num(3), eval("len('abc')"))
        assertEquals(Value.num(2), eval("len('👍汉')")) // 2 code points, 3 UTF-16 units
        assertEquals(Value.num(3), eval("len([1, 2, 3])"))
        assertEquals("len expects a string or list, got number", error("len(1)").message)
    }

    @Test
    fun `substr is 1-based and counts code points`() {
        assertEquals(Value.str("bc"), eval("substr('abcd', 2, 2)"))
        assertEquals(Value.str("bcd"), eval("substr('abcd', 2)"))
        assertEquals(Value.str("cd"), eval("substr('abcd', 3, 99)")) // length clamps
        assertEquals(Value.str(""), eval("substr('abcd', 9)")) // start past the end
        assertEquals(Value.str(""), eval("substr('abcd', 1, 0)"))
        assertEquals(Value.str("汉"), eval("substr('a汉b', 2, 1)"))

        assertEquals(ErrorKind.BAD_ARGUMENT, error("substr('abcd', 0)").kind)
        assertEquals(ErrorKind.BAD_ARGUMENT, error("substr('abcd', 1, -1)").kind)
        assertEquals(ErrorKind.BAD_ARGUMENT, error("substr('abcd', 1.5)").kind)
        assertEquals(ErrorKind.TYPE, error("substr(1, 1)").kind)
    }

    @Test
    fun `matches searches unless anchored`() {
        assertEquals(Value.bool(true), eval("matches('W1AW', 'W1')"))
        assertEquals(Value.bool(true), eval("matches('W1AW', '^W1AW$')"))
        assertEquals(Value.bool(false), eval("matches('W1AW', '^W2')"))
        assertEquals(Value.bool(true), eval("matches('abc', '[a-c]+')"))
    }

    @Test
    fun `dynamic invalid regex fails closed at evaluation`() {
        val env = mapOf("pattern" to Value.str("["))
        val error = error("matches('abc', pattern)", env)
        assertEquals(ErrorKind.BAD_REGEX, error.kind)
        assertTrue(error.message.startsWith("invalid regular expression"))
        assertEquals(16, error.position.column) // the pattern argument
    }

    @Test
    fun `num parses numbers`() {
        assertEquals(Value.num(42), eval("num('42')"))
        assertEquals(Value.num(3.5), eval("num(' 3.5 ')"))
        assertEquals(Value.num(-1000), eval("num('-1e3')"))

        val error = error("num('abc')")
        assertEquals(ErrorKind.BAD_ARGUMENT, error.kind)
        assertEquals("num: 'abc' is not a number", error.message)
        assertEquals(ErrorKind.TYPE, error("num(1)").kind)
    }

    @Test
    fun `callsign builtins delegate to the supplied facts`() {
        assertEquals(Value.str("W1"), eval("callPrefix('W1AW')", calls = FakeFacts))
        assertEquals(Value.num(14), eval("callZone('DL1ABC')", calls = FakeFacts))
        assertEquals(Value.str("Germany"), eval("callCountry('DL1ABC')", calls = FakeFacts))
        assertEquals(Value.str("CT"), eval("arrlSectionOf('W1AW')", calls = FakeFacts))
    }

    @Test
    fun `callsign builtins fail closed when the fact is unknown`() {
        val missing = error("callZone('W1AW')")
        assertEquals(ErrorKind.CALL_DATA_MISSING, missing.kind)
        assertTrue(missing.message.contains("no DXCC data"))
        assertEquals(10, missing.position.column) // the call argument, not the function name

        assertEquals(ErrorKind.CALL_DATA_MISSING, error("callPrefix('W1AW')").kind)
        assertEquals(ErrorKind.CALL_DATA_MISSING, error("callCountry('W1AW')").kind)
        assertEquals(ErrorKind.CALL_DATA_MISSING, error("arrlSectionOf('W1AW')").kind)
        // A provider is present but does not know this call: still no guessing.
        assertEquals(ErrorKind.CALL_DATA_MISSING, error("callZone('ZZ9ZZZ')", calls = FakeFacts).kind)
    }

    @Test
    fun `unique preserves first-seen order`() {
        assertEquals(
            Value.list(Value.num(1), Value.num(2), Value.num(3)),
            eval("unique([1, 2, 1, 3, 2])"),
        )
        assertEquals(Value.list(Value.str("b"), Value.str("a")), eval("unique(['b', 'a', 'b'])"))
        assertEquals(Value.list(), eval("unique([])"))
        assertEquals(ErrorKind.TYPE, error("unique('abc')").kind)
    }

    @Test
    fun `count counts list elements`() {
        assertEquals(Value.num(3), eval("count([1, 2, 3])"))
        assertEquals(Value.num(0), eval("count([])"))
        assertEquals("expected a list, got string", error("count('abc')").message)
    }

    @Test
    fun `sum adds numbers`() {
        assertEquals(Value.num(6), eval("sum([1, 2, 3])"))
        assertEquals(Value.num(0), eval("sum([])"))
        assertEquals(Value.num(3.5), eval("sum([1, 2.5])"))
        assertEquals("expected number, got string", error("sum([1, '2'])").message)
        assertEquals(ErrorKind.TYPE, error("sum(1)").kind)
    }

    @Test
    fun `ifElse is lazy`() {
        assertEquals(Value.num(1), eval("ifElse(true, 1, 1 / 0)"))
        assertEquals(Value.num(2), eval("ifElse(false, 1 / 0, 2)"))
        assertEquals(Value.str("yes"), eval("ifElse(1 < 2, 'yes', 'no')"))
        assertEquals("expected boolean, got number", error("ifElse(1, 'a', 'b')").message)
    }
}
