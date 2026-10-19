package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.DEFAULT_STEP_BUDGET
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Expression
import com.bh4gzk.morserunner.expr.Value
import com.bh4gzk.morserunner.expr.evaluateExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The T3 pressure test** (spec §4 "Open item", spec §11).
 *
 * These are not unit tests of a parser: they run the *shipped* CQ WW, ARRL SS and
 * IARU HF configs through the expression engine and compare the answers with
 * what the Pascal does (`docs/contests/exchange-facts.md`). If a contest's
 * behaviour can be read off its JSON, the schema absorbed it.
 *
 * The three gaps the fact sheet left open are disposed of here:
 *  1. table-neighbour lookup → `indexOf` + `listAt` over `constants.arrlSections`;
 *  2. seeded RNG → the dice are inputs, so the same seed gives the same exchange;
 *  3. numeric test → `matches(s, "^[0-9]+$")`, checked against the upstream table.
 *
 * The DXCC provider below is **fake test data**, explicitly not a DXCC.LIST
 * conversion — spec §9 owns the real asset.
 */
class PressureTestTest {

    // --- fake callsign data (test fixture, not a DXCC.LIST conversion) --------

    private val fakeCalls: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = call.take(1)

        override fun zone(call: String): Double? = when (call) {
            "W1AW" -> 5.0
            "DL1ABC" -> 14.0
            else -> null
        }

        override fun country(call: String): String? = when (call) {
            "W1AW", "K5ZD", "W1AW/MM" -> "United States of America"
            "KL7AA" -> "Alaska"
            "KH6XX" -> "Hawaii"
            "VE3ABC" -> "Canada"
            "DL1ABC", "DL1ABC/MM" -> "Germany"
            "F5ABC" -> "France"
            "JA1XYZ" -> "Japan"
            else -> null
        }

        override fun continent(call: String): String? = when (call) {
            "W1AW", "K5ZD", "KL7AA", "KH6XX", "VE3ABC", "W1AW/MM" -> "NA"
            "DL1ABC", "F5ABC", "DL1ABC/MM" -> "EU"
            "JA1XYZ" -> "AS"
            else -> null
        }

        override fun arrlSection(call: String): String? = if (call == "W1AW") "CT" else null
    }

    private fun config(id: String): ContestConfig {
        val result = ContestLibrary.load(id)
        assertIs<LoadResult.Success>(result, "$id did not load")
        return result.config
    }

    private fun Expression.value(environment: Map<String, Value>): Value {
        val result = evaluate(environment, DEFAULT_STEP_BUDGET, fakeCalls)
        assertIs<EvalResult.Success>(result, "unexpected failure: ${result.errorOrNull()}")
        return result.value
    }

    private fun EvalResult.errorOrNull(): String =
        if (this is EvalResult.Failure) error.render() else "none"

    private fun Expression.number(environment: Map<String, Value>): Double =
        assertIs<Value.Num>(value(environment)).value

    private fun Expression.text(environment: Map<String, Value>): String =
        assertIs<Value.Str>(value(environment)).value

    private fun Expression.flag(environment: Map<String, Value>): Boolean =
        assertIs<Value.Bool>(value(environment)).value

    // --- CQ WW ----------------------------------------------------------------

    private fun cqwwPoints(
        theirCall: String,
        myCountry: String = "United States of America",
        myContinent: String = "NA",
        theirExch2: String = "5",
    ): Double = config("cqww").scoring.points.number(
        cqww.environment(
            "rst" to Value.str("599"),
            "zone" to Value.num(5),
            "theirCall" to Value.str(theirCall),
            "theirExch1" to Value.str("599"),
            "theirExch2" to Value.str(theirExch2),
            "myCountry" to Value.str(myCountry),
            "myContinent" to Value.str(myContinent),
        ),
    )

    private val cqww: ContestConfig by lazy { config("cqww") }

    private fun cqwwMultipliers(
        theirCall: String,
        theirExch2: String,
        myCountry: String = "United States of America",
        myContinent: String = "NA",
    ): List<String> {
        val environment = cqww.environment(
            "rst" to Value.str("599"),
            "zone" to Value.num(5),
            "theirCall" to Value.str(theirCall),
            "theirExch1" to Value.str("599"),
            "theirExch2" to Value.str(theirExch2),
            "myCountry" to Value.str(myCountry),
            "myContinent" to Value.str(myContinent),
        )
        return cqww.scoring.multipliers.mapNotNull { multiplier ->
            if (multiplier.whenExpression != null && !multiplier.whenExpression.flag(environment)) {
                null
            } else {
                multiplier.key.text(environment)
            }
        }
    }

    @Test
    fun `CQ WW points follow the DXCC decision table`() {
        // CqWW.pas:258-265 — different continent 3, same entity 0, NA 2, else 1.
        assertEquals(3.0, cqwwPoints("DL1ABC"))
        assertEquals(0.0, cqwwPoints("W1AW"))
        assertEquals(2.0, cqwwPoints("VE3ABC"))
        assertEquals(1.0, cqwwPoints("DL1ABC", myCountry = "France", myContinent = "EU"))
    }

    @Test
    fun `CQ WW gives zero points to a maritime mobile station`() {
        // CqWW.pas:236-240.
        assertEquals(0.0, cqwwPoints("DL1ABC/MM"))
    }

    @Test
    fun `CQ WW multipliers are zone plus entity, with Alaska and Hawaii folded into the USA`() {
        assertEquals(listOf("ZN-5", "United States of America"), cqwwMultipliers("W1AW", "5"))
        assertEquals(listOf("ZN-1", "United States of America"), cqwwMultipliers("KL7AA", "1"))
        assertEquals(listOf("ZN-31", "United States of America"), cqwwMultipliers("KH6XX", "31"))
        assertEquals(listOf("ZN-14", "Germany"), cqwwMultipliers("DL1ABC", "14"))
    }

    @Test
    fun `CQ WW normalises a deliberately corrupted zone before building the multiplier`() {
        // CqWW.pas:296-297 inserts a leading zero for zones below 9; the multiplier
        // still keys on the integer, so "03" and "3" must not be two multipliers.
        assertEquals(listOf("ZN-3", "Germany"), cqwwMultipliers("DL1ABC", "03"))
    }

    @Test
    fun `CQ WW maritime mobile yields a zone multiplier only`() {
        assertEquals(listOf("ZN-14"), cqwwMultipliers("DL1ABC/MM", "14"))
    }

    @Test
    fun `CQ WW generates the documented leading-zero corruption deterministically`() {
        val their = cqww.exchange.their
        fun exch2(zone: String, draw: Double): String = their.exch2.text(
            cqww.environment("hisZone" to Value.str(zone), "leadingZero" to Value.num(draw)),
        )
        assertEquals("03", exch2("3", draw = 0.0))
        assertEquals("03", exch2("3", draw = 0.0499))
        // Outside the 5 % window nothing changes.
        assertEquals("3", exch2("3", draw = 0.05))
        // Zones 9 and up never take the leading zero.
        assertEquals("14", exch2("14", draw = 0.0))
    }

    // --- ARRL Sweepstakes -----------------------------------------------------

    private val ss: ContestConfig by lazy { config("arrlss") }

    /** Evaluates the station's exchange exactly as the roster will: derive, then send. */
    private fun ssExchange(
        dice: Map<String, Value>,
        hisCheck: String = "72",
        hisSection: String = "OR",
        serial: Int = 123,
    ): Pair<String, String> {
        val environment = mutableMapOf<String, Value>(
            "hisCheck" to Value.str(hisCheck),
            "hisSection" to Value.str(hisSection),
            "hisSer" to Value.num(serial),
        )
        ss.environment().forEach { (name, value) -> environment[name] = value }
        environment.putAll(dice)
        ss.exchange.their.derive.forEach { (name, expression) ->
            environment[name] = expression.value(environment)
        }
        return ss.exchange.their.exch1.text(environment) to ss.exchange.their.exch2.text(environment)
    }

    @Test
    fun `ARRL SS precedence follows the documented 43 19 28 10 weights`() {
        // ArrlSS.pas:422-430. The last 10 % is Q/M/S chosen by a second draw.
        fun precedence(roll: Double, rest: Double): String = ss.exchange.their.derive.getValue("prec").text(
            ss.environment(
                "precRoll" to Value.num(roll),
                "restRoll" to Value.num(rest),
            ),
        )
        assertEquals("A", precedence(0.0, 0.0))
        assertEquals("A", precedence(0.4299, 0.0))
        assertEquals("B", precedence(0.43, 0.0))
        assertEquals("B", precedence(0.6199, 0.0))
        assertEquals("U", precedence(0.62, 0.0))
        assertEquals("U", precedence(0.8999, 0.0))
        assertEquals("Q", precedence(0.90, 0.10))
        assertEquals("M", precedence(0.90, 0.50))
        assertEquals("S", precedence(0.90, 0.90))
    }

    @Test
    fun `ARRL SS adopts the historically reported split, not the arithmetic one`() {
        // The config encodes the *average* of the two field reports quoted at
        // ArrlSS.pas:418-421; a uniform guess would be wrong in both directions.
        fun precedence(roll: Double, rest: Double): String = ss.exchange.their.derive.getValue("prec").text(
            ss.environment("precRoll" to Value.num(roll), "restRoll" to Value.num(rest)),
        )
        // 37 % vs 50 % → 43 %: the midpoint is inside A.
        assertEquals("A", precedence(0.42, 0.0))
        // 36 % vs 20 % → 28 %: 0.85 is inside U, but would be B for either report alone.
        assertEquals("U", precedence(0.85, 0.0))
    }

    @Test
    fun `ARRL SS corrupts the check by plus or minus one modulo 100`() {
        // ArrlSS.pas:489-495.
        fun check(roll: Double, delta: Double, hisCheck: String): Double =
            ss.exchange.their.derive.getValue("check").number(
                ss.environment(
                    "checkRoll" to Value.num(roll),
                    "deltaRoll" to Value.num(delta),
                    "hisCheck" to Value.str(hisCheck),
                ),
            )
        assertEquals(8.0, check(0.09, 0.0, "7"))
        assertEquals(6.0, check(0.09, 0.9, "7"))
        // Wraps modulo 100 in both directions.
        assertEquals(0.0, check(0.0, 0.0, "99"))
        assertEquals(99.0, check(0.0, 0.9, "00"))
        // Ten percent, not more: the boundary itself is outside the window.
        assertEquals(7.0, check(0.10, 0.0, "7"))
    }

    @Test
    fun `ARRL SS replaces the section with a table neighbour`() {
        // ArrlSS.pas:507-517. "OR" is index 61 of the 85-section table:
        // 60 = NV (down), 62 = UT (up).
        fun section(roll: Double, side: Double, hisSection: String): String =
            ss.exchange.their.derive.getValue("section").text(
                ss.environment(
                    "sectRoll" to Value.num(roll),
                    "sideRoll" to Value.num(side),
                    "hisSection" to Value.str(hisSection),
                ),
            )
        assertEquals("NV", section(0.05, 0.1, "OR"))
        assertEquals("UT", section(0.05, 0.9, "OR"))
        // Ten percent, not more.
        assertEquals("OR", section(0.10, 0.1, "OR"))
    }

    @Test
    fun `ARRL SS table neighbour respects both ends of the table`() {
        // First entry cannot step down; last entry must step down (ArrlSS.pas:512).
        fun section(roll: Double, side: Double, hisSection: String): String =
            ss.exchange.their.derive.getValue("section").text(
                ss.environment(
                    "sectRoll" to Value.num(roll),
                    "sideRoll" to Value.num(side),
                    "hisSection" to Value.str(hisSection),
                ),
            )
        assertEquals("IA", section(0.05, 0.1, "CO"))
        assertEquals("IA", section(0.05, 0.9, "CO"))
        assertEquals("SK", section(0.05, 0.9, "TER"))
        assertEquals("SK", section(0.05, 0.1, "TER"))
        // A section that is not in the table is left alone rather than guessed at.
        assertEquals("ZZ", section(0.05, 0.1, "ZZ"))
    }

    @Test
    fun `ARRL SS sends two pieces built from the entry fields`() {
        val (exch1, exch2) = ssExchange(
            dice = mapOf(
                "precRoll" to Value.num(0.0),
                "restRoll" to Value.num(0.0),
                "checkRoll" to Value.num(0.5),
                "deltaRoll" to Value.num(0.0),
                "sectRoll" to Value.num(0.5),
                "sideRoll" to Value.num(0.5),
            ),
            hisCheck = "7",
        )
        assertEquals("123 A", exch1)
        assertEquals("07 OR", exch2)
    }

    /** Parses their exchange the way the engine will, binding `theirSect` and friends. */
    private fun theirPieces(exch2: String): Map<String, Value> {
        val parse = assertNotNull(ss.exchange.their.parse, "ARRL SS declares no their.parse")
        val matcher = parse.regex.matcher(exch2)
        assertTrue(matcher.matches(), "the parse must accept the exchange '$exch2'")
        return parse.groupNames.associate { name ->
            TheirExchange.parseIdentifier(name) to Value.str(matcher.group(name))
        }
    }

    @Test
    fun `ARRL SS points are constant and the multiplier is the worked section`() {
        assertEquals(2.0, ss.scoring.points.number(ss.environment()))
        val environment = ss.environment(
            *theirPieces("72 OR").toList().toTypedArray(),
            "theirExch1" to Value.str("123 A"),
            "theirExch2" to Value.str("72 OR"),
            "theirCall" to Value.str("W1AW"),
        )
        assertEquals("theirSect", TheirExchange.parseIdentifier("sect"))
        assertEquals("OR", ss.scoring.multipliers.single().key.text(environment))
        // A 3-character RAC section survives the same path.
        val rac = ss.environment(
            *theirPieces("07 TER").toList().toTypedArray(),
            "theirExch1" to Value.str("1 B"),
            "theirExch2" to Value.str("07 TER"),
            "theirCall" to Value.str("VE3ABC"),
        )
        assertEquals("TER", ss.scoring.multipliers.single().key.text(rac))
    }

    @Test
    fun `ARRL SS validation reads the section table from config data`() {
        // The table is not a built-in: it is `constants.arrlSections`, so a config
        // typo in the table would be a load error, not a silent behaviour change.
        val rule = ss.validation.first { it.field == "section" }
        assertTrue(rule.whenExpression.flag(ss.environment("section" to Value.str("ZZ"))), "ZZ must be rejected")
        assertTrue(!rule.whenExpression.flag(ss.environment("section" to Value.str("CO"))), "CO must be accepted")
        assertTrue(!rule.whenExpression.flag(ss.environment("section" to Value.str("TER"))), "TER must be accepted")
    }

    // --- IARU HF --------------------------------------------------------------

    private val iaru: ContestConfig by lazy { config("iaruhf") }

    private fun iaruPoints(
        theirExch2: String,
        myExch2: String,
        theirCall: String,
        myContinent: String = "NA",
    ): Double = iaru.scoring.points.number(
        iaru.environment(
            "rst" to Value.str("599"),
            "itu" to Value.str(myExch2),
            "theirCall" to Value.str(theirCall),
            "theirExch1" to Value.str("599"),
            "theirExch2" to Value.str(theirExch2),
            "myContinent" to Value.str(myContinent),
        ),
    )

    @Test
    fun `IARU HF scores one point for the same zone or for an HQ society`() {
        // IaruHf.pas:337-348.
        assertEquals(1.0, iaruPoints("8", "8", "DL1ABC"))
        assertEquals(1.0, iaruPoints("ARRL", "8", "W1AW"))
        assertEquals(1.0, iaruPoints("IARU", "8", "DL1ABC"))
    }

    @Test
    fun `IARU HF scores three points for the same continent and five across continents`() {
        assertEquals(3.0, iaruPoints("5", "8", "VE3ABC"))
        assertEquals(5.0, iaruPoints("28", "8", "JA1XYZ"))
        assertEquals(5.0, iaruPoints("14", "8", "DL1ABC"))
    }

    @Test
    fun `IARU HF numeric test matches the upstream IsNum truth table`() {
        // IaruHf.pas:355-366: non-empty and every character in '0123456789'.
        // gap 3 of the fact sheet — verified to need no new built-in.
        val pattern = "^[0-9]+$"
        fun isNum(text: String): Boolean {
            val result = evaluateExpression("matches(s, '$pattern')", mapOf("s" to Value.str(text)))
            assertIs<EvalResult.Success>(result)
            return assertIs<Value.Bool>(result.value).value
        }
        assertEquals(false, isNum(""))
        assertEquals(true, isNum("0"))
        assertEquals(true, isNum("8"))
        assertEquals(true, isNum("007"))
        assertEquals(true, isNum("28"))
        assertEquals(false, isNum("8A"))
        assertEquals(false, isNum(" 8"))
        assertEquals(false, isNum("8 "))
        assertEquals(false, isNum("-8"))
        assertEquals(false, isNum("٣"))
    }

    @Test
    fun `IARU HF multiplier is the received exchange verbatim`() {
        val environment = iaru.environment(
            "theirExch2" to Value.str("ARRL"),
            "theirCall" to Value.str("W1AW"),
        )
        assertEquals("ARRL", iaru.scoring.multipliers.single().key.text(environment))
    }

    // --- determinism (ADR-0003 / gap 2) ---------------------------------------

    @Test
    fun `the same dice produce the same exchange, every time`() {
        val dice = mapOf(
            "precRoll" to Value.num(0.70),
            "restRoll" to Value.num(0.0),
            "checkRoll" to Value.num(0.05),
            "deltaRoll" to Value.num(0.9),
            "sectRoll" to Value.num(0.05),
            "sideRoll" to Value.num(0.1),
        )
        val first = ssExchange(dice)
        val second = ssExchange(dice)
        assertEquals(first, second)
        assertEquals("123 U", first.first)
        // check 72 - 1 = 71, section OR → NV
        assertEquals("71 NV", first.second)
    }

    @Test
    fun `the contest's randomness is fully declared in the config`() {
        // Every draw the generator needs is named in the document, so a session can
        // log its seed and replay it — the reason there is no `random()` built-in.
        assertEquals(
            listOf("leadingZero"),
            config("cqww").exchange.their.dice,
        )
        assertEquals(
            listOf("precRoll", "restRoll", "checkRoll", "deltaRoll", "sectRoll", "sideRoll"),
            ss.exchange.their.dice,
        )
        assertTrue(config("iaruhf").exchange.their.dice.isEmpty(), "IARU HF makes no random choices")
    }
}
