package com.bh4gzk.morserunner.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrlSectionsTest {

    @Test
    fun `the table has the upstream size and ends`() {
        assertEquals(85, ArrlSections.all.size)
        assertEquals(85, ArrlSections.all.toSet().size, "sections must be unique")
        assertEquals("CO", ArrlSections.all.first())
        assertEquals("TER", ArrlSections.all.last())
    }

    @Test
    fun `sections from every call area are present`() {
        for (section in listOf("CO", "WMA", "NNY", "WPA", "PR", "VI", "NTX", "SCV", "PAC", "AK", "WWA", "MI", "IL", "ONN", "QC", "TER")) {
            assertTrue(ArrlSections.isKnown(section), "expected $section to be known")
        }
        assertTrue(!ArrlSections.isKnown("ZZ"))
        assertTrue(!ArrlSections.isKnown(""))
        assertEquals(-1, ArrlSections.indexOf("ZZ"))
    }

    @Test
    fun `a roll below one half steps back, otherwise forward`() {
        val ia = ArrlSections.indexOf("IA")
        assertEquals("CO", ArrlSections.neighbourOf("IA", 0.0))
        assertEquals("CO", ArrlSections.neighbourOf("IA", 0.499))
        assertEquals("KS", ArrlSections.neighbourOf("IA", 0.5))
        assertEquals("KS", ArrlSections.neighbourOf("IA", 0.999))
        assertEquals(ia, ArrlSections.indexOf("IA"))
    }

    @Test
    fun `the ends of the table never run off it`() {
        // The first section cannot step back even when the dice ask for it.
        assertEquals("IA", ArrlSections.neighbourOf("CO", 0.0))
        // The last section must step back whatever the dice say (upstream's
        // `or (index = High(SectionsTbl))` clause).
        assertEquals("SK", ArrlSections.neighbourOf("TER", 0.0))
        assertEquals("SK", ArrlSections.neighbourOf("TER", 0.999))
    }

    @Test
    fun `an unknown section has no neighbour`() {
        assertNull(ArrlSections.neighbourOf("ZZ", 0.1))
        assertNull(ArrlSections.neighbourOf("", 0.1))
    }

    @Test
    fun `every known section has a valid neighbour for both rolls`() {
        for (section in ArrlSections.all) {
            for (roll in listOf(0.0, 0.5)) {
                val neighbour = ArrlSections.neighbourOf(section, roll)
                assertTrue(neighbour != null && ArrlSections.isKnown(neighbour), "$section @ $roll -> $neighbour")
            }
        }
    }
}
