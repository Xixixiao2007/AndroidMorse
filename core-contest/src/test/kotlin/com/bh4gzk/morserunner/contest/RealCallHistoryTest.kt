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

    @Test
    fun `the real NAQP history keeps name and state, and an empty state stays empty`() {
        val history = parse("naqp")

        // 15 448 data rows; 6 176 have no operator name (upstream drops them,
        // `NaQp.pas:123`) and a handful fail the callsign grammar once accepted, so the
        // usable set is just under 9 272.
        assertTrue(history.size in 8_000..9_272, "unexpected NAQP history size ${history.size}")
        assertTrue(history.calls.all { history.recordFor(it)?.get("name").orEmpty().isNotEmpty() })
        // A known NA row and a known DX row: the DX record carries the empty state cell,
        // which is what makes the station send its name only (`NaQp.pas:125,439-448`).
        assertEquals("ERIC", history.recordFor("KP4NZ")?.get("name"))
        assertEquals("KP4", history.recordFor("KP4NZ")?.get("state"))
        assertEquals("PATRICK", history.recordFor("6Y5PW")?.get("name"))
        assertEquals("", history.recordFor("6Y5PW")?.get("state"))
        // A row with a blank operator name is not a usable record.
        assertNull(history.recordFor("AA0BA"), "a row with a blank name must be skipped")
        assertTrue(history.calls.none { it.startsWith("#") || it.startsWith("!!") })
    }

    @Test
    fun `the real K1USN SST history keeps the named members and drops the rest`() {
        val history = parse("k1usnsst")

        // 15 045 lines; 4 311 carry no operator name (upstream drops them, `CWSST.pas:94`)
        // and 16 have no QTH (`:95`), so the file's own rule leaves 10 707 rows. The shared
        // callsign grammar drops two more (`F/OZ1CGQ`, `VYIJA` — one-letter portable prefix
        // and a non-call), which upstream's SST loader does not check at all; ADR-0011 §8
        // records that as the declared deviation.
        assertEquals(10_705, history.size, "unexpected K1USN SST history size ${history.size}")
        assertTrue(history.calls.all { history.recordFor(it)?.get("name").orEmpty().isNotEmpty() })
        assertTrue(history.calls.all { history.recordFor(it)?.get("qth").orEmpty().isNotEmpty() })
        // A member in a state, a DX member (the QTH is the literal 'DX'), and a name the file
        // spells in lower case — upstream upper-cases it at load (`CWSST.pas:88-90`).
        assertEquals("MARK", history.recordFor("N5OT")?.get("name"))
        assertEquals("OK", history.recordFor("N5OT")?.get("qth"))
        assertEquals("DX", history.recordFor("KP4NZ")?.get("qth"))
        assertEquals("Josip", history.recordFor("9A5AX")?.get("name"))
        // A row with a blank operator name is not a usable record.
        assertNull(history.recordFor("AA0EN"), "a row with a blank name must be skipped")
        assertNull(history.recordFor("F/OZ1CGQ"), "a one-letter portable prefix fails the call grammar")
        assertTrue(history.calls.none { it.startsWith("#") || it.startsWith("!!") })
    }

    @Test
    fun `the real JARL ALL JA history keeps every row, because the file's rule is the grammar`() {
        val history = parse("allja")

        // 2 542 lines: a four-line '#' banner and 2 538 data rows, every one with exactly two
        // cells. AllJa.pas:74-96 keeps a row when `tl.Count > 1` and both cells are non-empty,
        // and does **no** call validation — but every one of the 2 538 calls also passes the
        // shared grammar, so upstream's rule and the port's agree exactly (ADR-0012 §8).
        assertEquals(2_538, history.size, "unexpected JARL ALL JA history size ${history.size}")
        assertTrue(history.calls.all { history.recordFor(it)?.get("number").orEmpty().isNotEmpty() })
        // A two-digit prefecture and a three-digit promotion-bureau code, both from the file.
        assertEquals("14H", history.recordFor("7K1CPT")?.get("number"))
        assertEquals("10H", history.recordFor("7K1III")?.get("number"))
        assertEquals("110M", history.recordFor("JA8BMD")?.get("number"))
        // A portable call with a call-area suffix stays, as upstream keeps it.
        assertEquals("112M", history.recordFor("JA4GBD/8")?.get("number"))
        assertTrue(history.calls.none { it.startsWith("#") || it.startsWith("!!") })
    }

    @Test
    fun `the real JARL ACAG history keeps every row, because the file's rule is the grammar`() {
        val history = parse("acag")

        // 2 551 lines: a four-line '#' banner and 2 547 data rows, every one with exactly two
        // cells. Acag.pas:74-96 keeps a row when `tl.Count > 1` and both cells are non-empty,
        // and does **no** call validation — but every one of the 2 547 calls also passes the
        // shared grammar, so upstream's rule and the port's agree exactly (ADR-0013 §2).
        assertEquals(2_547, history.size, "unexpected JARL ACAG history size ${history.size}")
        assertTrue(history.calls.all { history.recordFor(it)?.get("number").orEmpty().isNotEmpty() })
        // A 4-digit city code, a 5-digit gun code and a 6-digit ku code, all from the file.
        assertEquals("1430H", history.recordFor("7K1CPT")?.get("number"))
        assertEquals("01039M", history.recordFor("7K1AEU/8")?.get("number"), "a leading zero is kept")
        assertEquals("100115M", history.recordFor("7K1BIB")?.get("number"))
        // A portable call with a call-area suffix stays, as upstream keeps it.
        assertTrue(history.calls.none { it.startsWith("#") || it.startsWith("!!") })
    }

    @Test
    fun `the real CWops CWT history is header-driven and loses three rows`() {
        val history = parse("cwops")

        // 6 344 lines: the `!!Order!!,Call,Name,Exch1,UserText,` header, a nine-line '#' banner
        // and 6 334 data rows. CwOps.pas:82-95 keeps a row when `tl.Count >= 3` and call/name/exch
        // non-empty, which leaves 6 293 records over 6 292 distinct calls (`W1AL` is listed
        // twice, byte for byte). The port's map keeps one of the two, and the shared call grammar
        // drops `F/G7BED` and `I/W9CF`, so it holds 6 290 (ADR-0014 §3).
        assertEquals(6_290, history.size, "unexpected CWops CWT history size ${history.size}")
        assertTrue(history.calls.all { history.recordFor(it)?.get("name").orEmpty().isNotEmpty() })
        assertTrue(history.calls.all { history.recordFor(it)?.get("exch").orEmpty().isNotEmpty() })
        assertNull(history.recordFor("F/G7BED"), "a one-letter portable prefix fails the shared grammar")
        assertNull(history.recordFor("I/W9CF"), "a one-letter prefix fails the shared grammar")
        // A member number, a state and a country prefix, all from the file.
        assertEquals("Robert", history.recordFor("3B9FR")?.get("name"))
        assertEquals("1343", history.recordFor("3B9FR")?.get("exch"))
        assertEquals("Scott", history.recordFor("W1AL")?.get("name"))
        assertEquals("FL", history.recordFor("W1AL")?.get("exch"))
        assertEquals("4O", history.recordFor("4O3A")?.get("exch"))
        // The two loader-only caps never fire on the shipped data: the longest name is 9
        // (`length > 10`) and the longest exchange 4 (`length > 5`), so no row is lost to them.
        val names = history.calls.map { history.recordFor(it)?.get("name").orEmpty() }
        val exchanges = history.calls.map { history.recordFor(it)?.get("exch").orEmpty() }
        assertEquals(9, names.maxOf { it.length }, "a name is longer than the loader's cap")
        assertEquals(4, exchanges.maxOf { it.length }, "an exchange is longer than the loader's cap")
        assertTrue(history.calls.none { it.startsWith("#") || it.startsWith("!!") })
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
