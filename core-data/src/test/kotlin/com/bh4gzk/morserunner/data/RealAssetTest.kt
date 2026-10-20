package com.bh4gzk.morserunner.data

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the parsers against the real assets in `upstream/MorseRunnerCE/`.
 *
 * `upstream/` is git-ignored, so these tests skip on a machine without the
 * reference clone (JUnit's `assumeTrue` marks them skipped rather than passing).
 * Where the data *is* present they are the only proof that the parsers survive
 * 1.2 MB of real callsigns and the real 335-row DXCC table, not just fixtures.
 */
class RealAssetTest {

    /**
     * Gradle runs tests with the *module* directory as the working directory, so a
     * plain relative path finds nothing and every test silently skips — which is
     * not evidence. Walk up to the project root that holds the reference clone.
     */
    private val upstream: File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { root ->
            sequenceOf(File(root, "upstream/MorseRunnerCE"), File(root, "app/src/main/assets/data"))
        }
        .firstOrNull { it.isDirectory && it.listFiles().orEmpty().isNotEmpty() }

    @Test
    fun `the real MASTER DTA parses with its integrity header`() {
        val root = upstream
        assumeTrue("no reference clone above ${File("").absolutePath}", root != null)
        val file = File(root!!, "MASTER.DTA")
        assumeTrue("MASTER.DTA is missing", file.isFile)

        val dta = MasterDta.parse(file.readBytes())

        // The shipped file holds 193 452 non-banner records but only 46 039
        // distinct callsigns: it is 76 % redundant, which is why upstream sorts
        // and deduplicates instead of using the records as they come.
        assertTrue(dta.size in 40_000..60_000, "unexpected pool size ${dta.size}")
        assertTrue(dta.calls.none { it.isEmpty() }, "empty call slipped into the pool")
        assertTrue(dta.calls.none { it.startsWith("VER2") }, "version banner treated as a call")
        assertTrue(dta.calls.all { call -> call.all { it.isUpperCase() || it.isDigit() || it == '/' } })

        // Ordinal-sorted with no duplicates: check the whole list, it is cheap enough.
        for (i in 1 until dta.size) {
            assertTrue(dta.calls[i - 1] < dta.calls[i], "not sorted or duplicated at $i")
        }
        assertTrue(dta.contains(dta.calls.first()))
    }

    @Test
    fun `the real DXCC LIST resolves the entities the contests score on`() {
        val root = upstream
        assumeTrue("no reference clone above ${File("").absolutePath}", root != null)
        val file = File(root!!, "DXCC.LIST")
        assumeTrue("DXCC.LIST is missing", file.isFile)

        val dxcc = DxccList.parse(file.readText())

        assertEquals(335, dxcc.size)

        // CQ WW's own assertions in the Pascal rely on these two facts.
        assertEquals("K|W|N|A[A-GI-K]", dxcc.lookup("W1")?.prefixPattern)
        assertEquals("United States of America", dxcc.lookup("W1")?.entity)
        assertEquals("NA", dxcc.lookup("W1")?.continent)
        // A DXCC row may list several zones: the US row carries "3,4,5", so the
        // zone used for scoring has to come from the call history, as upstream
        // does (CqWW.pas:280 takes the zone from the history file, not from here).
        assertEquals("3,4,5", dxcc.lookup("W1")?.cqZone)
        assertEquals("6,7,8", dxcc.lookup("W1")?.ituZone)

        assertEquals("Hawaii", dxcc.lookup("KH6")?.entity)
        assertEquals("OC", dxcc.lookup("KH6")?.continent)
        assertEquals("31", dxcc.lookup("KH6")?.cqZone)

        assertEquals("Fed. Rep. of Germany", dxcc.lookup("DL")?.entity)
        assertEquals("EU", dxcc.lookup("DL")?.continent)
        assertEquals("(D[A-R])|(Y[2-9])", dxcc.lookup("DL")?.prefixPattern)
        assertEquals("14", dxcc.lookup("DL")?.cqZone)

        // An empty answers nothing rather than guessing.
        assertEquals(null, dxcc.lookup(""))
    }

    @Test
    fun `extracting a prefix from real pool calls always yields something usable`() {
        val root = upstream
        assumeTrue("no reference clone above ${File("").absolutePath}", root != null)
        val file = File(root!!, "MASTER.DTA")
        assumeTrue("MASTER.DTA is missing", file.isFile)

        val dta = MasterDta.parse(file.readBytes())
        // Sample every 1000th call: enough to catch systematic breakage, fast.
        val sample = dta.calls.filterIndexed { index, _ -> index % 1000 == 0 }

        var resolved = 0
        for (call in sample) {
            val wpx = CallPrefix.extractPrefix(call, deleteTrailingLetters = true)
            val dxcc = CallPrefix.extractPrefix(call, deleteTrailingLetters = false)
            // The DXCC prefix must be a prefix of the call, and the WPX prefix
            // must be one too; both staying prefixes is what makes lookup sound.
            assertTrue(call.startsWith(dxcc), "DXCC prefix '$dxcc' is not a prefix of '$call'")
            assertTrue(call.startsWith(wpx), "WPX prefix '$wpx' is not a prefix of '$call'")
            if (dxcc.isNotEmpty()) resolved++
        }
        // Almost every pool call should resolve; a handful of odd formats may not.
        assertTrue(resolved > sample.size * 0.95, "only $resolved of ${sample.size} resolved")
    }
}
