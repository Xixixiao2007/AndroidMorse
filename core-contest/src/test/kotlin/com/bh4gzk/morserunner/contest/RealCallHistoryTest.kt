package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.CallFacts
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the call-history parser against the **real** shipped history files in
 * `upstream/MorseRunnerCE/` (the same precedent as `core-data/RealAssetTest`).
 *
 * `upstream/` is git-ignored, so these skip on a machine without the reference
 * clone (`assumeTrue`, reported as skipped — and a skipped test is not evidence,
 * which is why the ticket's evidence line states the skip count).
 *
 * Where the data *is* present this is the only proof that 26 000 real lines are
 * handled: the files carry blank fields, duplicate calls, comment banners and
 * announced-operation sections that fixtures do not.
 */
class RealCallHistoryTest {

    /** Gradle runs tests with the module directory as cwd, so walk up to the repo root. */
    private val upstream: File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "upstream/MorseRunnerCE") }
        .firstOrNull { it.isDirectory }

    /** Android assets are case-sensitive; this machine may not be, so resolve the name ignoring case. */
    private fun asset(name: String): File? {
        val root = upstream ?: return null
        return File(root, name).takeIf { it.isFile }
            ?: root.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun spec(id: String): CallHistory =
        assertIs<LoadResult.Success>(ContestLibrary.load(id)).config.callHistory

    private fun parse(id: String, myCall: String = "", calls: CallFacts = CallFacts.Unavailable): CallHistoryFile {
        val file = asset(spec(id).asset)
        assumeTrue("no ${spec(id).asset} next to ${upstream ?: "the repo root"}", file != null)
        val result = CallHistoryFile.parse(file!!.readText(), spec(id), myCall, calls)
        return assertIs<HistoryLoad.Loaded>(result, "$id history was rejected: $result").history
    }

    @Test
    fun `the real CQ WW history parses every usable row and drops the rest`() {
        val history = parse("cqww")

        // 8 902 rows carry a numeric zone; a handful are dropped by the callsign
        // grammar, so the usable set is close to — but not above — that figure.
        assertTrue(history.size in 8_500..8_902, "unexpected CQ WW history size ${history.size}")
        // Every kept record has the columns the config binds.
        assertTrue(history.calls.all { history.recordFor(it)?.get("zone")?.all { c -> c.isDigit() } == true })
        // A known row, and the announced-operation banner lines are not calls.
        assertEquals("14", history.recordFor("2E0AQQ")?.get("zone"))
        assertTrue(history.calls.none { it.startsWith("ANNOUNCED") || it.startsWith("#") })
    }

    @Test
    fun `the real ARRL SS history keeps only rows with an integer check`() {
        val history = parse("arrlss")

        // 13 012 of 13 063 lines carry an integer check; 40 have a blank check
        // (`TryStrToInt` fails, so ArrlSS.pas:112 skips them).
        assertTrue(history.size in 12_000..13_012, "unexpected SS history size ${history.size}")
        assertTrue(history.calls.all { history.recordFor(it)?.get("check")?.toIntOrNull() != null })
        assertNull(history.recordFor("AC0W"), "a row with a blank check must be skipped")
        assertEquals("MDC", history.recordFor("4U1WB")?.get("section"))
    }

    @Test
    fun `the real IARU history is de-duplicated and header-driven`() {
        val history = parse("iaruhf", myCall = "W1AW", calls = knowsW1Aw)

        // 4 057 usable rows carry 77 duplicated calls (`IaruHf.pas:143-144` keeps the first).
        assertTrue(history.size in 3_900..4_057, "unexpected IARU history size ${history.size}")
        assertTrue(history.size < 4_057, "duplicates were not dropped")
        assertTrue(history.calls.all { history.recordFor(it)?.get("section").orEmpty().isNotEmpty() })
    }

    @Test
    fun `the real IARU history is refused when the operator call is unknown`() {
        val file = asset(spec("iaruhf").asset)
        assumeTrue("no IARU_HF.txt next to the repo root", file != null)
        val result = CallHistoryFile.parse(file!!.readText(), spec("iaruhf"), "W1AW", CallFacts.Unavailable)
        assertIs<HistoryLoad.Rejected>(result)
    }

    private val knowsW1Aw = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null
        override fun country(call: String): String? =
            if (call.equals("W1AW", ignoreCase = true)) "United States of America" else null

        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }
}
