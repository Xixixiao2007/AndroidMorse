package com.bh4gzk.morserunner.contest

import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Their-exchange generation, exercised through the **shipped** configs.
 *
 * This is the roster's second half: given the station's history values, its serial
 * and its declared dice, evaluate `their.bindings` → `their.derive` (top to
 * bottom) → `their.exch1/exch2`, then parse the sent piece into the
 * `their<Group>` identifiers scoring reads (`PressureTestTest.theirPieces` is the
 * same contract).
 */
class TheirExchangeGeneratorTest {

    private fun config(id: String): ContestConfig {
        val result = ContestLibrary.load(id)
        assertIs<LoadResult.Success>(result, "$id did not load")
        return result.config
    }

    private val cqww: ContestConfig by lazy { config("cqww") }
    private val ss: ContestConfig by lazy { config("arrlss") }
    private val iaru: ContestConfig by lazy { config("iaruhf") }

    private fun success(result: GenerationResult): GeneratedExchange {
        assertIs<GenerationResult.Success>(result, (result as? GenerationResult.Failure)?.message ?: "")
        return result.exchange
    }

    private fun failure(result: GenerationResult): String {
        assertIs<GenerationResult.Failure>(result, "expected a failure, got $result")
        return result.message
    }

    private fun expression(text: String): com.bh4gzk.morserunner.expr.Expression =
        when (val compiled = com.bh4gzk.morserunner.expr.compileExpression(text)) {
            is com.bh4gzk.morserunner.expr.CompileResult.Success -> compiled.expression
            is com.bh4gzk.morserunner.expr.CompileResult.Failure ->
                error("test expression '$text' does not compile: ${compiled.error.render()}")
        }

    // --- CQ WW ---------------------------------------------------------------

    @Test
    fun `CQ WW sends the history zone, with the documented leading zero`() {
        val generator = TheirExchangeGenerator(cqww)
        val plain = success(generator.generate(mapOf("leadingZero" to 0.5), mapOf("zone" to "14"), null))
        assertEquals("599", plain.exch1)
        assertEquals("14", plain.exch2)

        val corrupted = success(generator.generate(mapOf("leadingZero" to 0.0), mapOf("zone" to "3"), null))
        assertEquals("03", corrupted.exch2, "zones below 9 take a leading zero 5 % of the time")
        // 5 % is the window: at exactly 0.05 the zone is unchanged.
        val boundary = success(generator.generate(mapOf("leadingZero" to 0.05), mapOf("zone" to "3"), null))
        assertEquals("3", boundary.exch2)
    }

    @Test
    fun `CQ WW declares no parse and no serial binding`() {
        val exchange = success(
            TheirExchangeGenerator(cqww).generate(mapOf("leadingZero" to 0.5), mapOf("zone" to "5"), serial = null),
        )
        assertTrue(exchange.parsed.isEmpty())
    }

    // --- ARRL Sweepstakes -----------------------------------------------------

    private val ssDice = mapOf(
        "precRoll" to 0.0,
        "restRoll" to 0.0,
        "checkRoll" to 0.5,
        "deltaRoll" to 0.0,
        "sectRoll" to 0.5,
        "sideRoll" to 0.5,
    )

    @Test
    fun `ARRL SS builds both pieces and parses check and section`() {
        val exchange = success(
            TheirExchangeGenerator(ss).generate(
                ssDice,
                history = mapOf("check" to "72", "section" to "OR"),
                serial = 123,
            ),
        )
        assertEquals("123 A", exchange.exch1)
        assertEquals("72 OR", exchange.exch2)
        assertEquals(
            mapOf(
                TheirExchange.parseIdentifier("chk") to com.bh4gzk.morserunner.expr.Value.str("72"),
                TheirExchange.parseIdentifier("sect") to com.bh4gzk.morserunner.expr.Value.str("OR"),
            ),
            exchange.parsed,
        )
    }

    @Test
    fun `ARRL SS zero-pads the check and applies the section neighbour`() {
        val dice = ssDice + mapOf("checkRoll" to 0.0, "deltaRoll" to 0.9, "sectRoll" to 0.05, "sideRoll" to 0.1)
        val exchange = success(
            TheirExchangeGenerator(ss).generate(dice, mapOf("check" to "7", "section" to "OR"), serial = 5),
        )
        // 7 - 1 = 6 → "06"; OR steps back to NV.
        assertEquals("06 NV", exchange.exch2)
        assertEquals("NV", (exchange.parsed.getValue("theirSect") as com.bh4gzk.morserunner.expr.Value.Str).value)
    }

    @Test
    fun `a missing history column fails closed`() {
        val message = failure(
            TheirExchangeGenerator(ss).generate(ssDice, history = mapOf("section" to "OR"), serial = 1),
        )
        assertTrue(message.contains("check"), message)
    }

    @Test
    fun `a required serial that was not supplied fails closed`() {
        val message = failure(
            TheirExchangeGenerator(ss).generate(ssDice, history = mapOf("check" to "72", "section" to "OR"), serial = null),
        )
        assertTrue(message.contains("serial"), message)
    }

    @Test
    fun `a missing declared draw fails closed`() {
        val message = failure(
            TheirExchangeGenerator(ss).generate(
                ssDice - "sectRoll",
                history = mapOf("check" to "72", "section" to "OR"),
                serial = 1,
            ),
        )
        assertTrue(message.contains("sectRoll"), message)
    }

    // --- IARU HF ---------------------------------------------------------------

    @Test
    fun `IARU HF sends the history section verbatim and makes no random choices`() {
        val exchange = success(
            TheirExchangeGenerator(iaru).generate(emptyMap(), history = mapOf("section" to "ARRL"), serial = null),
        )
        assertEquals("599", exchange.exch1)
        assertEquals("ARRL", exchange.exch2)
        assertTrue(exchange.parsed.isEmpty())
    }

    // --- generation rules ------------------------------------------------------

    @Test
    fun `derive runs top to bottom, so a later name may read an earlier one`() {
        // No shipped config nests derives; the loader allows it, so the generator
        // must preserve document order (the reason Json.Obj keeps a LinkedHashMap).
        val mutated = ss.copy(
            exchange = ss.exchange.copy(
                their = ss.exchange.their.copy(
                    dice = emptyList(),
                    bindings = emptyMap(),
                    derive = linkedMapOf(
                        "first" to expression("'x'"),
                        "second" to expression("first + 'y'"),
                    ),
                    exch1 = expression("second"),
                    exch2 = expression("first"),
                    parse = null,
                ),
            ),
        )
        val exchange = success(TheirExchangeGenerator(mutated).generate(emptyMap(), serial = 1))
        assertEquals("xy", exchange.exch1)
        assertEquals("x", exchange.exch2)
    }

    @Test
    fun `a non-string exchange piece fails closed`() {
        val mutated = ss.copy(
            exchange = ss.exchange.copy(
                their = ss.exchange.their.copy(
                    exch1 = expression("1 + 1"),
                    parse = null,
                ),
            ),
        )
        val message = failure(
            TheirExchangeGenerator(mutated).generate(ssDice, history = mapOf("check" to "72", "section" to "OR"), serial = 1),
        )
        assertTrue(message.contains("string"), message)
    }

    @Test
    fun `a generated exchange that does not match its own parse fails closed`() {
        val mutated = ss.copy(
            exchange = ss.exchange.copy(
                their = ss.exchange.their.copy(
                    parse = ParseSpec("^NEVER$", emptyList(), Pattern.compile("^NEVER$")),
                ),
            ),
        )
        val message = failure(
            TheirExchangeGenerator(mutated).generate(ssDice, mapOf("check" to "72", "section" to "OR"), serial = 1),
        )
        assertTrue(message.contains("does not match"), message)
    }

    @Test
    fun `the parse binds the scoring identifier naming rule`() {
        // `sect` → `theirSect` (TheirExchange.parseIdentifier), which is what
        // `scoring.multipliers` reads.
        assertEquals("theirSect", TheirExchange.parseIdentifier("sect"))
        assertEquals("theirChk", TheirExchange.parseIdentifier("chk"))
    }
}
