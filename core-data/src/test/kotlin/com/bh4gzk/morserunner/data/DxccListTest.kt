package com.bh4gzk.morserunner.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DxccListTest {

    private val sample = """
        Used with permission: DXCC Country List, copyright ARRL.

        1A;1A;Sov. Mil. Order of Malta;EU;28;15;246
        3A;3A;Monaco;EU;27;14;260
        3B6,7;3B[67];Agalega & St. Brandon Is.;AF;53;39;004
        K;K;United States of America;NA;8;5;291
        KH6;KH6;Hawaii;OC;61;31;110
        # a comment line;with;seven;fields;to;be;ignored
        ZL;ZL;New Zealand;OC;60;32;170
        XX;!ignore;Ignored Entity;EU;1;2;999
    """.trimIndent()

    @Test
    fun `parses records and skips the unparseable header`() {
        val list = DxccList.parse(sample)

        // Six records: the two heading lines and the comment are not 7-field rows.
        assertEquals(6, list.size)
        assertEquals(
            DxccRecord("K", "United States of America", "NA", "8", "5", "291"),
            list.records()[3],
        )
    }

    @Test
    fun `field order is prefix, regex, entity, continent, ITU, CQ, dxcc`() {
        // Malta: CQ 15, ITU 28 — the file stores ITU first (DXCC.pas:67-68).
        val malta = DxccList.parse(sample).lookup("1A")

        assertEquals("Sov. Mil. Order of Malta", malta?.entity)
        assertEquals("EU", malta?.continent)
        assertEquals("28", malta?.ituZone)
        assertEquals("15", malta?.cqZone)
        assertEquals("246", malta?.dxccNumber)
    }

    @Test
    fun `an ignore row never wins`() {
        val list = DxccList.parse(sample)

        assertNull(list.lookup("XX1ABC"))
        assertEquals(6, list.size)   // and it is not even stored
    }

    @Test
    fun `patterns are anchored and may be character classes`() {
        val list = DxccList.parse(sample)

        assertEquals("Agalega & St. Brandon Is.", list.lookup("3B6")?.entity)
        assertEquals("Agalega & St. Brandon Is.", list.lookup("3B7")?.entity)
        // '3B' alone must not match '3B[67]'.
        assertNull(list.lookup("3B"))
    }

    @Test
    fun `later rows win because the lookup scans in reverse`() {
        val text = """
            KH;KH;United States of America;NA;8;5;291
            KH6;KH6;Hawaii;OC;61;31;110
        """.trimIndent()
        val list = DxccList.parse(text)

        // KH6 is stored after KH, so it overrides it — this is how the real file
        // gives Hawaii its own entity, zone and continent.
        assertEquals("Hawaii", list.lookup("KH6")?.entity)
        assertEquals("United States of America", list.lookup("KH7")?.entity)
    }

    @Test
    fun `an unknown prefix has no record`() {
        val list = DxccList.parse(sample)

        assertNull(list.lookup("QQ9"))
        assertNull(list.lookup(""))
    }

    @Test
    fun `a broken pattern fails the load naming the line`() {
        val broken = """
            1A;1A;Malta;EU;28;15;246
            2A;[unclosed;Broken;EU;1;2;3
        """.trimIndent()

        val error = assertFailsWith<IllegalArgumentException> { DxccList.parse(broken) }
        assertTrue(error.message!!.contains("DXCC.LIST:2"), error.message)
        assertTrue(error.message!!.contains("[unclosed"), error.message)
    }

    @Test
    fun `alternation is anchored as a whole, not only its first branch`() {
        // Real rows look like `5B|C4|H2|P3` (91 of the 335 rows contain groups or
        // alternation). Without the wrapping group, `^5B|C4` would match "C4"
        // anywhere in the string, so a call prefix could map to the wrong entity.
        val list = DxccList.parse("5B;5B|C4|H2|P3;Cyprus;AS;39;20;215")

        assertEquals("Cyprus", list.lookup("C4")?.entity)
        assertEquals("Cyprus", list.lookup("5B")?.entity)
        assertNull(list.lookup("ZZC4"))
    }


    // --- find(): the two hard-coded rewrites in DXCC.pas:136-170 ---------------

    private val rewriteTable = """
        F;F;France;EU;28;14;227
        K;K|W|N;United States of America;NA;8;3,4,5;291
        KG4;KG4;Guantanamo Bay;NA;8;08;105
        CE9;CE9;South Shetland Is.;SA;73;13;241
        CE9KC4;CE9KC4|FT8Y|RI1AN;Antarctica;AN;67;12,13,29,30;013
    """.trimIndent()

    @Test
    fun `KG4 2x2 calls are Guantanamo but 2x1 and 2x3 calls are US`() {
        // DXCC.pas:149-158 -- without the rewrite KG4ABC would resolve to
        // Guantanamo Bay instead of the United States.
        val list = DxccList.parse(rewriteTable)

        assertEquals("Guantanamo Bay", list.find("KG4AA")?.entity)          // 2x2
        assertEquals("United States of America", list.find("KG4ABC")?.entity) // 2x3
        assertEquals("United States of America", list.find("KG4A")?.entity)   // 2x1
    }

    @Test
    fun `CE9 and KC4 aliases resolve to Antarctica`() {
        // DXCC.pas:160-165 -- the table alone maps CE9 to South Shetland Is. and
        // KC4 to the United States, which is wrong for Antarctic operations.
        val list = DxccList.parse(rewriteTable)

        assertEquals("Antarctica", list.find("CE9/W7SST")?.entity)
        assertEquals("Antarctica", list.find("KC4/W7SST")?.entity)
        assertEquals("Antarctica", list.find("KC4AAA")?.entity)
        assertEquals("South Shetland Is.", list.lookup("CE9")?.entity)
    }

    @Test
    fun `find uses the DXCC prefix mode and rejects nonsense`() {
        val list = DxccList.parse(rewriteTable)

        assertEquals("United States of America", list.find("W1AW")?.entity)
        // F6/W7SST is a US operator transmitting from France: the station's
        // location wins, which is exactly why FindRec uses the full call
        // (DXCC.pas:143-147).
        assertEquals("France", list.find("F6/W7SST")?.entity)
        assertNull(list.find(""))
    }

    @Test
    fun `an empty file parses to an empty table`() {
        val list = DxccList.parse("")

        assertTrue(list.isEmpty)
        assertEquals(0, list.size)
        assertNull(list.lookup("K"))
    }
}
