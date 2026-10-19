package com.bh4gzk.morserunner.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four built-ins the T3 pressure test added (ADR-0004) plus the static
 * identifier analysis the contest loader uses to reject a misspelled field at
 * load time.
 *
 * Why each one exists:
 *  - `str(x)` — CQ WW's `ZN-<zone>` multiplier (`CqWW.pas:233`) and ARRL SS's
 *    `%.02d` zero padding both need number → text.
 *  - `indexOf`/`listAt` — `GetAlternateSection` (`ArrlSS.pas:507-517`) needs the
 *    neighbour of a fixed table, which v1 could not address at all.
 *  - `callContinent(call)` — CQ WW (`CqWW.pas:258-265`) and IARU HF
 *    (`IaruHf.pas:337-348`) score on continent equality.
 *  - `identifiers()` — the loader checks every name against the spec §4
 *    environment table instead of discovering a typo mid-QSO.
 */
class ExtensionBuiltinsTest {

    private val fakeCalls: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = if (call.startsWith("W")) "W" else null
        override fun zone(call: String): Double? = if (call == "W1AW") 5.0 else null
        override fun country(call: String): String? =
            if (call == "W1AW") "United States of America" else null

        override fun continent(call: String): String? = when (call) {
            "W1AW", "VE3ABC" -> "NA"
            "DL1ABC" -> "EU"
            else -> null
        }

        override fun arrlSection(call: String): String? = if (call == "W1AW") "CT" else null
    }

    private fun eval(
        source: String,
        environment: Map<String, Value> = emptyMap(),
        calls: CallFacts = CallFacts.Unavailable,
        budget: Int = DEFAULT_STEP_BUDGET,
    ): Value = when (val result = evaluateExpression(source, environment, budget, calls)) {
        is EvalResult.Success -> result.value
        is EvalResult.Failure -> throw AssertionError("unexpected failure: ${result.error.render()}")
    }

    private fun failure(
        source: String,
        environment: Map<String, Value> = emptyMap(),
        calls: CallFacts = CallFacts.Unavailable,
        budget: Int = DEFAULT_STEP_BUDGET,
    ): ExprError {
        val result = evaluateExpression(source, environment, budget, calls)
        assertTrue(result is EvalResult.Failure, "expected a failure for: $source")
        return result.error
    }

    private fun compileFailure(source: String): ExprError {
        val result = compileExpression(source)
        assertTrue(result is CompileResult.Failure, "expected a compile failure for: $source")
        return result.error
    }

    private fun identifiers(source: String): Set<String> = when (val compiled = compileExpression(source)) {
        is CompileResult.Success -> compiled.expression.identifiers()
        is CompileResult.Failure -> throw AssertionError("unexpected failure: ${compiled.error.render()}")
    }

    // --- str ------------------------------------------------------------------

    @Test
    fun `str prints an integral number without a fractional part`() {
        assertEquals(Value.Str("3"), eval("str(3)"))
        assertEquals(Value.Str("0"), eval("str(0)"))
        assertEquals(Value.Str("-2"), eval("str(-2)"))
        assertEquals(Value.Str("100"), eval("str(100)"))
    }

    @Test
    fun `str keeps a fractional part`() {
        assertEquals(Value.Str("3.5"), eval("str(3.5)"))
        assertEquals(Value.Str("-0.25"), eval("str(-0.25)"))
    }

    @Test
    fun `str of a computed integer does not sprout a decimal point`() {
        // The CQ WW multiplier path: a zone arrives as a number and must print as "3".
        assertEquals(Value.Str("ZN-3"), eval("'ZN-' + str(zone)", mapOf("zone" to Value.num(3))))
        // (7 + 2) is a double at runtime; the text must still be "9".
        assertEquals(Value.Str("9"), eval("str(7 + 2)"))
    }

    @Test
    fun `str rejects a non-number`() {
        val error = failure("str('abc')")
        assertEquals(ErrorKind.TYPE, error.kind)
    }

    @Test
    fun `str arity is checked at compile time`() {
        assertEquals(ErrorKind.WRONG_ARITY, compileFailure("str()").kind)
        assertEquals(ErrorKind.WRONG_ARITY, compileFailure("str(1, 2)").kind)
    }

    // --- indexOf --------------------------------------------------------------

    @Test
    fun `indexOf finds the first, a middle and the last position`() {
        val sections = Value.list(Value.str("CO"), Value.str("IA"), Value.str("KS"))
        val environment = mapOf("sections" to sections)
        assertEquals(Value.num(0), eval("indexOf(sections, 'CO')", environment))
        assertEquals(Value.num(1), eval("indexOf(sections, 'IA')", environment))
        assertEquals(Value.num(2), eval("indexOf(sections, 'KS')", environment))
    }

    @Test
    fun `indexOf returns -1 for an absent value and for an empty list`() {
        val environment = mapOf("sections" to Value.list(Value.str("CO")))
        assertEquals(Value.num(-1), eval("indexOf(sections, 'ZZ')", environment))
        assertEquals(Value.num(-1), eval("indexOf([], 'ZZ')"))
    }

    @Test
    fun `indexOf compares structurally, not by reference`() {
        val environment = mapOf("lists" to Value.list(Value.list(Value.num(1)), Value.list(Value.num(2))))
        assertEquals(Value.num(1), eval("indexOf(lists, [2])", environment))
    }

    @Test
    fun `indexOf charges one step per element so a huge list cannot evade the budget`() {
        val big = Value.list((1..10).map { Value.num(it) })
        val error = failure("indexOf(xs, 99)", mapOf("xs" to big), budget = 5)
        assertEquals(ErrorKind.STEP_BUDGET, error.kind)
    }

    @Test
    fun `indexOf requires a list`() {
        val error = failure("indexOf('abc', 'a')")
        assertEquals(ErrorKind.TYPE, error.kind)
    }

    // --- listAt ---------------------------------------------------------------

    @Test
    fun `listAt is zero based and reads both ends`() {
        val sections = Value.list(Value.str("CO"), Value.str("IA"), Value.str("KS"))
        val environment = mapOf("sections" to sections)
        assertEquals(Value.Str("CO"), eval("listAt(sections, 0)", environment))
        assertEquals(Value.Str("KS"), eval("listAt(sections, 2)", environment))
    }

    @Test
    fun `listAt fails closed outside the list`() {
        val environment = mapOf("sections" to Value.list(Value.str("CO")))
        assertEquals(ErrorKind.BAD_ARGUMENT, failure("listAt(sections, 1)", environment).kind)
        assertEquals(ErrorKind.BAD_ARGUMENT, failure("listAt(sections, -1)", environment).kind)
        assertEquals(ErrorKind.BAD_ARGUMENT, failure("listAt(sections, 1.5)", environment).kind)
    }

    @Test
    fun `listAt requires a list`() {
        assertEquals(ErrorKind.TYPE, failure("listAt('abc', 0)").kind)
    }

    @Test
    fun `listAt and indexOf express a table neighbour`() {
        // The shape ArrlSS.GetAlternateSection needs: index ±1, clamped at both ends.
        val table = Value.list(Value.str("CO"), Value.str("IA"), Value.str("KS"))
        val environment = mapOf("t" to table)
        val neighbour = "ifElse(indexOf(t, s) == count(t) - 1, " +
            "listAt(t, indexOf(t, s) - 1), listAt(t, indexOf(t, s) + 1))"
        assertEquals(
            Value.Str("IA"),
            eval(neighbour, environment + ("s" to Value.str("CO"))),
        )
        assertEquals(
            Value.Str("KS"),
            eval(neighbour, environment + ("s" to Value.str("IA"))),
        )
        // The last entry must step back, never past the end.
        assertEquals(
            Value.Str("IA"),
            eval(neighbour, environment + ("s" to Value.str("KS"))),
        )
    }

    // --- callContinent --------------------------------------------------------

    @Test
    fun `callContinent answers from the provided provider`() {
        assertEquals(Value.Str("NA"), eval("callContinent('W1AW')", calls = fakeCalls))
        assertEquals(Value.Str("EU"), eval("callContinent('DL1ABC')", calls = fakeCalls))
    }

    @Test
    fun `callContinent fails closed without data`() {
        val error = failure("callContinent('W1AW')")
        assertEquals(ErrorKind.CALL_DATA_MISSING, error.kind)
        val unknown = failure("callContinent('KH6ZZ')", calls = fakeCalls)
        assertEquals(ErrorKind.CALL_DATA_MISSING, unknown.kind)
    }

    @Test
    fun `callContinent requires a string`() {
        assertEquals(ErrorKind.TYPE, failure("callContinent(1)", calls = fakeCalls).kind)
    }

    @Test
    fun `the new built-ins are known at compile time`() {
        listOf("str(1)", "indexOf([], 1)", "listAt([], 0)", "callContinent('W1AW')").forEach {
            assertTrue(
                compileExpression(it) is CompileResult.Success,
                "expected $it to compile",
            )
        }
    }

    // --- identifiers() --------------------------------------------------------

    @Test
    fun `identifiers reports free names, deduplicated and sorted`() {
        assertEquals(setOf("zone"), identifiers("zone >= 1 and zone <= 40"))
        assertEquals(setOf("a", "b", "c"), identifiers("a + b * c + a"))
        assertEquals(emptySet(), identifiers("'ZN-' + str(3)"))
    }

    @Test
    fun `identifiers looks inside calls, branches and lists`() {
        assertEquals(
            setOf("theirExch2"),
            identifiers("ifElse(matches(theirExch2, '^[0-9]+$'), theirExch2, '')"),
        )
        assertEquals(
            setOf("theirCall", "theirExch2"),
            identifiers("ifElse(matches(theirExch2, '^[0-9]+$'), theirExch2, theirCall)"),
        )
        assertEquals(
            setOf("x"),
            identifiers("ifElse(x > 1, [x, 2], [3])"),
        )
    }
}
