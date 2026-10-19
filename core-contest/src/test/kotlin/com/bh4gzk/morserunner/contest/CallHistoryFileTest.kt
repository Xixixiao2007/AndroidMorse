package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.CallFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `HistoryLookup` implementation T5 left as a seam: the three shipped call-history
 * shapes, driven entirely by the config's `callHistory` spec.
 *
 * The expectations are upstream's loader rules with `file:line`:
 *  - CQ WW (`CqWW.pas:79-98`): skip `!!Order!!`/`#`, `tl.Count > 2`, zone must be numeric;
 *  - ARRL SS (`ArrlSS.pas:106-126`): skip `!!Order!!`, `tl.Count > 3`, check must parse as
 *    an integer, the call must pass `IsValidCall`;
 *  - IARU HF (`IaruHf.pas:114-151`): `#` lines skipped, a `!!Order!!` line defines the
 *    columns, duplicates are dropped (first wins), and every record is refused unless the
 *    operator's call resolves in DXCC.
 */
class CallHistoryFileTest {

    private fun spec(id: String): CallHistory {
        val result = ContestLibrary.load(id)
        assertIs<LoadResult.Success>(result, "$id did not load")
        return result.config.callHistory
    }

    private fun load(text: String, id: String, myCall: String = "", calls: CallFacts = CallFacts.Unavailable) =
        CallHistoryFile.parse(text, spec(id), myCall, calls)

    private fun loaded(text: String, id: String, myCall: String = "", calls: CallFacts = CallFacts.Unavailable) =
        assertIs<HistoryLoad.Loaded>(load(text, id, myCall, calls)).history

    // --- CQ WW: headerless positional, comma, zone must be numeric -----------------

    @Test
    fun `CQ WW reads positional call, zone and optional user text`() {
        val history = loaded(
            """
            !!Order!!,Call,CQZone,Usertext,
            #
            2E0AQQ,14,
            DL1ABC,14,DDL1ABC
            9A1A,15,
            """.trimIndent(),
            "cqww",
        )

        assertEquals(setOf("2E0AQQ", "DL1ABC", "9A1A"), history.calls)
        assertEquals("14", history.recordFor("2E0AQQ")?.get("zone"))
        assertEquals("DDL1ABC", history.recordFor("DL1ABC")?.get("userText"))
        assertEquals("15", history.recordFor("9A1A")?.get("zone"))
    }

    @Test
    fun `CQ WW skips short, empty and non-numeric rows the way the Pascal does`() {
        val history = loaded(
            """
            !!Order!!,Call,CQZone,Usertext,
            2E0AQQ,14,
            only-one-field
            K1ABC,not-a-zone,
            ,14,
            ZL1ABC,,
            """.trimIndent(),
            "cqww",
        )

        assertEquals(setOf("2E0AQQ"), history.calls)
        assertNull(history.recordFor("K1ABC"))
        assertNull(history.recordFor("ZL1ABC"))
    }

    // --- ARRL SS: positional, four required fields, callsign grammar ---------------

    @Test
    fun `ARRL SS reads section and integer check from their positions`() {
        val history = loaded(
            """
            !!Order!!,Call,Sect,State,CK,UserText,
            #
            4U1WB,MDC,DC,89,
            AA0A,MO,MO,60,AA0A/MO
            """.trimIndent(),
            "arrlss",
        )

        assertEquals("MDC", history.recordFor("4U1WB")?.get("section")); assertEquals("DC", history.recordFor("4U1WB")?.get("ignored")); assertEquals("89", history.recordFor("4U1WB")?.get("check"))
        assertEquals("MO", history.recordFor("AA0A")?.get("section"))
        assertEquals("60", history.recordFor("AA0A")?.get("check"))
        assertEquals("AA0A/MO", history.recordFor("AA0A")?.get("userText"))
    }

    @Test
    fun `ARRL SS rejects a row whose check is not an integer or whose call is malformed`() {
        val history = loaded(
            """
            4U1WB,MDC,DC,89,
            AA0A,MO,MO,not-a-number,
            NOTACALL,MO,MO,60,
            AA0B,MO,,
            """.trimIndent(),
            "arrlss",
        )

        assertEquals(setOf("4U1WB"), history.calls)
    }

    // --- IARU HF: header-driven columns, dedupe, DXCC gate -------------------------

    @Test
    fun `IARU HF takes its columns from the header line, not from their order`() {
        val header = """
            # a comment line
            !!Order!!,Call,Sect,UserText
            2E0CVN,27,
            3D2AG,56,
        """.trimIndent()
        val history = loaded(header, "iaruhf", myCall = "W1AW", calls = knowsW1Aw)

        assertEquals("27", history.recordFor("2E0CVN")?.get("section"))
        assertEquals("56", history.recordFor("3D2AG")?.get("section"))

        // Same data, header columns swapped: the parser follows the header.
        val swapped = loaded(
            """
            !!Order!!,Sect,Call,UserText
            27,2E0CVN,
            """.trimIndent(),
            "iaruhf",
            myCall = "W1AW",
            calls = knowsW1Aw,
        )
        assertEquals("27", swapped.recordFor("2E0CVN")?.get("section"))
    }

    @Test
    fun `IARU HF keeps the first record for a duplicated call`() {
        // IaruHf.pas:143-144: `if dupList.Find(rec.Call, Index) then continue`.
        val history = loaded(
            """
            !!Order!!,Call,Sect,UserText
            2E0CVN,27,
            2E0CVN,99,
            """.trimIndent(),
            "iaruhf",
            myCall = "W1AW",
            calls = knowsW1Aw,
        )

        assertEquals("27", history.recordFor("2E0CVN")?.get("section"))
    }

    @Test
    fun `IARU HF refuses the whole file when my call does not resolve in DXCC`() {
        // IaruHf.pas:147-151 gates every record on `gDxCCList.FindRec(dxcc, AUserCallsign)`.
        val rejected = load(
            """
            !!Order!!,Call,Sect,UserText
            2E0CVN,27,
            """.trimIndent(),
            "iaruhf",
            myCall = "W1AW",
            calls = CallFacts.Unavailable,
        )

        assertIs<HistoryLoad.Rejected>(rejected)
        assertTrue(rejected.reason.contains("W1AW"))
    }

    @Test
    fun `a history file with no usable records is rejected rather than loaded empty`() {
        assertIs<HistoryLoad.Rejected>(load("# nothing here\n", "arrlss"))
    }

    @Test
    fun `record lookup is case and whitespace insensitive on the call`() {
        val history = loaded(
            """
            2E0AQQ,14,
            """.trimIndent(),
            "cqww",
        )

        // The stored record is normalised, so a lookup with a lower-case call finds it.
        assertEquals("14", history.recordFor("2e0aqq")?.get("zone"))
        assertNull(history.recordFor("3E0AQQ"))
    }

    /** A DXCC provider that resolves the operator's call and nothing else. */
    private val knowsW1Aw = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null
        override fun country(call: String): String? =
            if (call.equals("W1AW", ignoreCase = true)) "United States of America" else null

        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }
}
