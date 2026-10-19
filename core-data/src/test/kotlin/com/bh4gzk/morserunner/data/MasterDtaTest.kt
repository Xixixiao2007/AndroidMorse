package com.bh4gzk.morserunner.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A synthetic fixture is used rather than the 1.2 MB upstream file, so the test
 * documents the layout instead of depending on a git-ignored asset.
 */
class MasterDtaTest {

    /** Builds a file with the upstream layout: index header, then NUL-terminated records. */
    private fun buildFile(
        vararg records: String,
        indexFirst: Int? = null,
        indexLast: Int? = null,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        for (record in records) {
            body.write(record.toByteArray(Charsets.US_ASCII))
            body.write(0)
        }
        val bodyBytes = body.toByteArray()
        val total = MasterDta.INDEX_BYTES + bodyBytes.size

        val index = ByteBuffer.allocate(MasterDta.INDEX_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        index.putInt(0, indexFirst ?: MasterDta.INDEX_BYTES)
        index.putInt((MasterDta.INDEX_ENTRIES - 1) * 4, indexLast ?: total)
        return index.array() + bodyBytes
    }

    @Test
    fun `parses the pool and exposes contains`() {
        val dta = MasterDta.parse(buildFile("DL1ABC", "W1AW", "K7S"))

        assertEquals(3, dta.size)
        assertTrue(dta.contains("W1AW"))
        assertTrue(dta.contains("K7S"))
        assertFalse(dta.contains("N0CALL"))
    }

    @Test
    fun `the pool is sorted ordinally and deduplicated`() {
        val dta = MasterDta.parse(buildFile("K7S", "W1AW", "K7S", "DL1ABC", "W1AW"))

        assertEquals(listOf("DL1ABC", "K7S", "W1AW"), dta.calls)
    }

    @Test
    fun `the version banner is not a callsign`() {
        val dta = MasterDta.parse(buildFile("VER2025", "W1AW"))

        assertEquals(listOf("W1AW"), dta.calls)
    }

    @Test
    fun `an index that does not point at itself is rejected`() {
        val bytes = buildFile("W1AW", indexFirst = 1234)

        val error = assertFailsWith<IllegalArgumentException> { MasterDta.parse(bytes) }
        assertTrue(error.message!!.contains("index[0]"), error.message)
        // Upstream gives up silently here; parseOrNull keeps that behaviour.
        assertNull(MasterDta.parseOrNull(bytes))
    }

    @Test
    fun `an index that disagrees with the file size is rejected`() {
        val bytes = buildFile("W1AW", indexLast = 999_999)

        val error = assertFailsWith<IllegalArgumentException> { MasterDta.parse(bytes) }
        assertTrue(error.message!!.contains("file size"), error.message)
        assertNull(MasterDta.parseOrNull(bytes))
    }

    @Test
    fun `a file shorter than the index is rejected`() {
        val tooShort = ByteArray(MasterDta.INDEX_BYTES - 1)

        assertFailsWith<IllegalArgumentException> { MasterDta.parse(tooShort) }
        assertNull(MasterDta.parseOrNull(tooShort))
    }

    @Test
    fun `an empty pool is parsed without error`() {
        val dta = MasterDta.parse(buildFile())

        assertTrue(dta.isEmpty)
        assertEquals(0, dta.size)
        assertFalse(dta.contains("W1AW"))
    }

    @Test
    fun `callAt lets the caller index the pool`() {
        val dta = MasterDta.parse(buildFile("DL1ABC", "W1AW"))

        assertEquals("DL1ABC", dta.callAt(0))
        assertEquals("W1AW", dta.callAt(1))
    }

    @Test
    fun `the index size matches the upstream constants`() {
        // Sqr(37) + 1 entries of 4 bytes = 5480, which is what MASTER.DTA stores.
        assertEquals(37, MasterDta.ALPHABET.length)
        assertEquals(1370, MasterDta.INDEX_ENTRIES)
        assertEquals(5480, MasterDta.INDEX_BYTES)
    }
}
