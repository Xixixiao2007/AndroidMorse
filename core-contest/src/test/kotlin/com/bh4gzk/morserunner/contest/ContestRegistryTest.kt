package com.bh4gzk.morserunner.contest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContestRegistryTest {

    @Test
    fun `all twelve upstream contests are registered`() {
        assertEquals(12, ContestRegistry.bundledIds().size)
    }

    @Test
    fun `ids are unique and lower case`() {
        val ids = ContestRegistry.bundledIds()
        assertEquals(ids.size, ids.toSet().size, "duplicate contest id")
        assertTrue(ids.all { it == it.lowercase() })
    }

    @Test
    fun `the three schema pressure-test contests are present`() {
        val ids = ContestRegistry.bundledIds()
        listOf("cqww", "arrlss", "iaruhf").forEach {
            assertTrue(it in ids, "missing $it")
        }
    }
}
