package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Value
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The realism weights are **config data**, so they are proven by sampling the
 * shipped configs, not by re-reading the Pascal constants:
 *
 *  - ARRL SS precedence 43 % A / 19 % B / 28 % U / 10 % Q+M+S (`ArrlSS.pas:422-430`);
 *  - the check is corrupted ±1 (mod 100) 10 % of the time (`:489-495`);
 *  - the section is replaced by a table neighbour 10 % of the time (`:507-517`).
 *
 * 10 000 seeded draws each — deterministic, so the tolerances are a safety margin,
 * not rerun noise. T3 pinned the *boundaries*; this pins the *distribution*.
 */
class RosterDistributionTest {

    private val ss: ContestConfig by lazy {
        val result = ContestLibrary.load("arrlss")
        check(result is LoadResult.Success) { "arrlss did not load: $result" }
        result.config
    }

    private fun environment(dice: Map<String, Double>, check: String, section: String): Map<String, Value> {
        val environment = LinkedHashMap<String, Value>()
        environment.putAll(ss.environment())
        environment["hisCheck"] = Value.str(check)
        environment["hisSection"] = Value.str(section)
        dice.forEach { (name, draw) -> environment[name] = Value.num(draw) }
        return environment
    }

    private fun generate(dice: Map<String, Double>, check: String = "72", section: String = "OR"): GeneratedExchange =
        when (val result = TheirExchangeGenerator(ss).generate(dice, mapOf("check" to check, "section" to section), serial = 1)) {
            is GenerationResult.Success -> result.exchange
            is GenerationResult.Failure -> error(result.message)
        }

    @Test
    fun `ARRL SS precedence matches the reported 43 19 28 10 weights`() {
        val random = SeededRandom(2026)
        val counts = HashMap<String, Int>()
        val prec = ss.exchange.their.derive.getValue("prec")
        repeat(10_000) {
            val dice = Dice.draw(ss.exchange.their.dice, random)
            val result = prec.evaluate(environment(dice.values, "72", "OR"))
            check(result is EvalResult.Success) { "prec failed: $result" }
            val text = result.value as Value.Str
            counts.merge(text.value, 1, Int::plus)
        }
        fun share(value: String): Double = (counts[value] ?: 0) / 10_000.0

        assertEquals(0.43, share("A"), absoluteTolerance = 0.02, message = "A: $counts")
        assertEquals(0.19, share("B"), absoluteTolerance = 0.02, message = "B: $counts")
        assertEquals(0.28, share("U"), absoluteTolerance = 0.02, message = "U: $counts")
        assertEquals(
            0.10,
            share("Q") + share("M") + share("S"),
            absoluteTolerance = 0.02,
            message = "Q/M/S: $counts",
        )
    }

    @Test
    fun `ARRL SS corrupts the check about one time in ten`() {
        val random = SeededRandom(7)
        var corrupted = 0
        repeat(10_000) {
            val dice = Dice.draw(ss.exchange.their.dice, random)
            val exchange = generate(dice.values)
            val check = (exchange.parsed.getValue("theirChk") as Value.Str).value
            if (check != "72") corrupted++
        }
        assertEquals(0.10, corrupted / 10_000.0, absoluteTolerance = 0.01)
    }

    @Test
    fun `ARRL SS replaces the section with a neighbour about one time in ten`() {
        val random = SeededRandom(11)
        var corrupted = 0
        repeat(10_000) {
            val dice = Dice.draw(ss.exchange.their.dice, random)
            val exchange = generate(dice.values)
            val section = (exchange.parsed.getValue("theirSect") as Value.Str).value
            if (section != "OR") corrupted++
        }
        assertEquals(0.10, corrupted / 10_000.0, absoluteTolerance = 0.01)
    }

    @Test
    fun `the check corruption is the modulo-100 plus or minus one`() {
        // Generate until both directions appear: with 10 000 draws both do.
        val random = SeededRandom(5)
        val seen = HashSet<String>()
        repeat(10_000) {
            val dice = Dice.draw(ss.exchange.their.dice, random)
            seen += (generate(dice.values).parsed.getValue("theirChk") as Value.Str).value
        }
        assertEquals(setOf("71", "72", "73"), seen)
    }

    @Test
    fun `the exchange is a pure function of the dice`() {
        // Same draws → same exchange; the derivation is not order-dependent on the
        // environment map, because the config's derive order is fixed.
        val first = Dice.draw(ss.exchange.their.dice, SeededRandom(99))
        val second = Dice.draw(ss.exchange.their.dice, SeededRandom(99))
        assertEquals(generate(first.values), generate(second.values))
    }
}
