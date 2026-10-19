package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.CallPrefix
import com.bh4gzk.morserunner.data.DxccList
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.ErrorKind
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Value
import com.bh4gzk.morserunner.expr.evaluateExpression
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `CallFacts` port wired to `core-data` (spec §9): `DXCC.LIST` for the
 * entity/continent/zone and `CallPrefix` for the prefix rules. T2 left the port
 * deliberately empty so the four `call*` built-ins failed closed; T5 is where
 * real data arrives.
 *
 * The fake table below is **test data**, not a DXCC.LIST conversion: it exercises
 * the rules (single zone, multi-zone, aliasing entity, portable suffix) without
 * pretending to be the 335-row asset. [RealAssetCallFactsTest] runs the real one.
 */
class AssetCallFactsTest {

    private val table = """
        Used with permission: DXCC Country List, copyright ARRL.

        1A;1A;Sov. Mil. Order of Malta;EU;28;15;246
        3B6,7;3B[67];Agalega & St. Brandon Is.;AF;53;39;004
        K;K|W|N|A[A-GI-K];United States of America;NA;8;3,4,5;291
        KH6;KH6;Hawaii;OC;61;31;110
        DL;(D[A-R])|(Y[2-9]);Fed. Rep. of Germany;EU;28;14;230
        VE;VE;Canada;NA;4;3,4,5;001
        ZL;ZL;New Zealand;OC;60;32;170
    """.trimIndent()

    private val dxcc = DxccList.parse(table)
    private val facts: CallFacts = AssetCallFacts(dxcc)

    // --- prefixes ------------------------------------------------------------

    @Test
    fun `prefix is the WPX prefix the contest scores on`() {
        // WPX mode (deleteTrailingLetters): prefix + district digit, trailing letters dropped.
        assertEquals("DL2", facts.prefix("DL2XYZ"))
        assertEquals("A9", facts.prefix("A9AB"))
        assertEquals("W1", facts.prefix("W1AW"))
        // A bare district digit replaces the prefix's own digit.
        assertEquals("W6", facts.prefix("W7SST/6"))
        assertNull(facts.prefix(""))
    }

    @Test
    fun `country uses the DXCC-mode prefix, so portable calls stay in their entity`() {
        // The DXCC prefix keeps trailing letters (RC2F is Kaliningrad, RC2 is Russia),
        // which is why it is a different question from the WPX prefix.
        assertEquals("Fed. Rep. of Germany", facts.country("DL1ABC"))
        assertEquals("Fed. Rep. of Germany", facts.country("DL1ABC/MM"))
        assertEquals("Fed. Rep. of Germany", facts.country("DL/AA1ZZ"))
        assertEquals("Hawaii", facts.country("KH6XX"))
        assertEquals("United States of America", facts.country("W1AW"))
        assertEquals("Agalega & St. Brandon Is.", facts.country("3B6AB"))
        assertNull(facts.country("ZZ9ZZZ"), "an unknown call must fail closed, never guess")
        assertNull(facts.country(""))
    }

    @Test
    fun `continent comes from the same DXCC row`() {
        assertEquals("EU", facts.continent("DL1ABC"))
        assertEquals("OC", facts.continent("KH6XX"))
        assertEquals("NA", facts.continent("W1AW"))
        assertNull(facts.continent("ZZ9ZZZ"))
    }

    @Test
    fun `zone is the CQ zone only when the entity has exactly one`() {
        assertEquals(14.0, facts.zone("DL1ABC"))
        assertEquals(31.0, facts.zone("KH6XX"))
        assertEquals(15.0, facts.zone("1A0AA"))
        // The US and Canada rows list several zones ("3,4,5"). Upstream takes the
        // zone from the call history (CqWW.pas:280), so guessing one here would be
        // an invented fact — the answer is "unknown".
        assertNull(facts.zone("W1AW"))
        assertNull(facts.zone("VE3ABC"))
        assertNull(facts.zone("ZZ9ZZZ"))
    }

    @Test
    fun `no callsign maps to an ARRL section, so the section answer is unknown`() {
        // The 85-section table is ordered by call area, but several sections share
        // an area; the real source is the call history (`theirSect`).
        assertNull(facts.arrlSection("W1AW"))
        assertNull(facts.arrlSection("VE3ABC"))
    }

    // --- the port inside expressions -------------------------------------------

    @Test
    fun `the four built-ins now answer through the port`() {
        val result = evaluateExpression(
            "callCountry(c) == 'Hawaii' and callContinent(c) == 'OC' and callZone(c) == 31",
            mapOf("c" to Value.str("KH6XX")),
            calls = facts,
        )
        assertIs<EvalResult.Success>(result, (result as? EvalResult.Failure)?.error?.render() ?: "failed")
        val answer = assertIs<Value.Bool>(result.value)
        assertTrue(answer.value)
    }

    @Test
    fun `an unanswerable call still fails closed with CALL_DATA_MISSING`() {
        val result = evaluateExpression("callZone(c)", mapOf("c" to Value.str("W1AW")), calls = facts)
        assertIs<EvalResult.Failure>(result)
        assertEquals(ErrorKind.CALL_DATA_MISSING, result.error.kind)
    }
}

/**
 * The same bridge against the real `DXCC.LIST` (and a sample of `MASTER.DTA`).
 *
 * `upstream/` is git-ignored, so this skips on a machine without the reference
 * clone; where it is present it is the only proof that the wiring survives the
 * real 335-row table, not just the fixture above.
 */
class RealAssetCallFactsTest {

    private val upstream: File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "upstream/MorseRunnerCE") }
        .firstOrNull { it.isDirectory }

    private fun facts(): CallFacts {
        val root = upstream
        assumeTrue("no reference clone above ${File("").absolutePath}", root != null)
        val dxccFile = File(root!!, "DXCC.LIST")
        assumeTrue("DXCC.LIST is missing", dxccFile.isFile)
        return AssetCallFacts(DxccList.parse(dxccFile.readText()))
    }

    @Test
    fun `the real table answers the entities the three contests score on`() {
        val facts = facts()
        assertEquals("Fed. Rep. of Germany", facts.country("DL1ABC"))
        assertEquals("Fed. Rep. of Germany", facts.country("DL1ABC/MM"))
        assertEquals("United States of America", facts.country("W1AW"))
        assertEquals("Hawaii", facts.country("KH6XX"))
        assertEquals("Canada", facts.country("VE3ABC"))
        assertEquals("EU", facts.continent("DL1ABC"))
        assertEquals("OC", facts.continent("KH6XX"))
        assertEquals("14.0", facts.zone("DL1ABC").toString())
        assertEquals("31.0", facts.zone("KH6XX").toString())
        // The real US row carries "3,4,5".
        assertNull(facts.zone("W1AW"))
        // WPX prefix rules from the real call extractor.
        assertEquals("DL2", facts.prefix("DL2XYZ"))
        assertEquals(CallPrefix.extractPrefix("DL2XYZ"), facts.prefix("DL2XYZ"))
    }

    @Test
    fun `most real pool calls resolve to an entity`() {
        val facts = facts()
        val root = upstream!!
        val poolFile = File(root, "MASTER.DTA")
        assumeTrue("MASTER.DTA is missing", poolFile.isFile)
        val pool = com.bh4gzk.morserunner.data.MasterDta.parse(poolFile.readBytes())
        val sample = pool.calls.filterIndexed { index, _ -> index % 500 == 0 }

        val resolved = sample.count { facts.country(it) != null }
        assertTrue(
            resolved > sample.size * 0.9,
            "only $resolved of ${sample.size} real pool calls resolved to an entity",
        )
    }
}
