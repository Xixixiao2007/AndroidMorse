package com.bh4gzk.morserunner.contest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three pressure-test contests ship as classpath resources and load clean
 * (spec §11 T3, ADR-0004). If this fails, every downstream ticket is building on
 * a config the loader cannot even parse.
 */
class ContestLibraryTest {

    @Test
    fun `every manifest config loads with no errors`() {
        val bundled = ContestLibrary.bundled()
        assertTrue(
            bundled.errors.isEmpty(),
            "unexpected load errors:\n" + bundled.errors.joinToString("\n") { it.render() },
        )
        assertEquals(
            listOf("cqww", "arrlss", "iaruhf", "naqp", "k1usnsst", "allja", "acag", "cwops"),
            bundled.configs.map { it.id },
        )
    }

    @Test
    fun `the manifest only names bundled contest ids`() {
        val known = ContestRegistry.bundledIds()
        ContestLibrary.bundled().configs.forEach {
            assertTrue(it.id in known, "'${it.id}' is not a bundled contest id")
        }
    }

    @Test
    fun `each config carries everything the engine will ask for`() {
        ContestLibrary.bundled().configs.forEach { config ->
            assertEquals(ContestConfig.SUPPORTED_SCHEMA_VERSION, config.schemaVersion, config.id)
            assertTrue(config.name.isNotBlank(), config.id)
            assertTrue(config.exchange.myFields.isNotEmpty(), "${config.id}: no exchange fields")
            assertTrue(config.exchange.fieldKeys.isNotEmpty(), "${config.id}: no field keys")
            assertTrue(config.scoring.multipliers.isNotEmpty(), "${config.id}: no multipliers")
            assertTrue(config.callHistory.asset.isNotBlank(), "${config.id}: no call-history asset")
            assertTrue(config.station.callPool.isNotBlank(), "${config.id}: no call pool")
            assertTrue(config.runModes.isNotEmpty(), "${config.id}: no run modes")
            assertTrue(
                config.duration.minMinutes <= config.duration.defaultMinutes &&
                    config.duration.defaultMinutes <= config.duration.maxMinutes,
                "${config.id}: duration bounds inverted",
            )
        }
    }

    @Test
    fun `ARRL SS keeps its 85-section table as config data`() {
        val result = ContestLibrary.load("arrlss")
        assertTrue(result is LoadResult.Success, "arrlss did not load")
        val config = (result as LoadResult.Success).config
        val sections = config.constants["arrlSections"]
        assertTrue(sections is com.bh4gzk.morserunner.expr.Value.List, "arrlSections is not a list")
        val list = sections as com.bh4gzk.morserunner.expr.Value.List
        assertEquals(85, list.items.size)
        val names = list.items.map { (it as com.bh4gzk.morserunner.expr.Value.Str).value }
        assertEquals(85, names.toSet().size, "duplicate section in the table")
        // First, call-area boundaries and last entries are the ones the neighbour rule depends on.
        assertEquals(listOf("CO", "IA", "KS"), names.take(3))
        assertEquals("TER", names.last())
    }
}
