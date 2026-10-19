package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.ArrlSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The T3 debt, decided in T5 (ADR-0004 decision 9): a shared data asset is
 * **referenced, never copied**.
 *
 * `arrlss.json` used to carry its own 85-entry section table while
 * `core-data.ArrlSections.all` shipped the same 85 entries. Two sources of truth
 * that happened to agree; nothing kept them agreeing. Now the config says
 * `{ "asset": "arrlSections" }`, the loader resolves it from `core-data`, and a
 * literal copy is a load error.
 *
 * These tests fail if the duplicate comes back or if the two tables drift.
 */
class DataAssetConstantTest {

    private fun text(id: String): String =
        javaClass.classLoader.getResourceAsStream("contests/$id.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun config(): ContestConfig {
        val result = ContestLibrary.load("arrlss")
        assertIs<LoadResult.Success>(result, "arrlss did not load")
        return result.config
    }

    @Test
    fun `the shipped table is core-data's table, injected by the loader`() {
        val constant = assertNotNull(config().constants["arrlSections"], "no arrlSections constant")
        assertIs<com.bh4gzk.morserunner.expr.Value.List>(constant)
        val strings = constant.items.map { (it as com.bh4gzk.morserunner.expr.Value.Str).value }
        // Same object identity is not required; same order and same entries are.
        assertEquals(ArrlSections.all, strings)
    }

    @Test
    fun `a literal copy of a shared asset is rejected at load time`() {
        // The exact regression this decree exists to prevent: someone pastes the
        // table back into the config "just this once".
        val literal = text("arrlss").replace(
            "\"arrlSections\": { \"asset\": \"arrlSections\" }",
            "\"arrlSections\": [\"CO\", \"IA\", \"KS\"]",
        )
        val result = ContestLoader.load("arrlss.json", literal)
        assertIs<LoadResult.Failure>(result, "a literal arrlSections copy must be rejected")
        assertTrue(
            result.errors.any { it.path == "constants.arrlSections" && it.message.contains("shared data asset") },
            result.errors.joinToString("\n") { it.render() },
        )
    }

    @Test
    fun `an unknown data asset is rejected`() {
        val literal = text("arrlss").replace("\"asset\": \"arrlSections\"", "\"asset\": \"nope\"")
        val result = ContestLoader.load("arrlss.json", literal)
        assertIs<LoadResult.Failure>(result)
        assertTrue(
            result.errors.any { it.path == "constants.arrlSections.asset" && it.message.contains("unknown data asset") },
            result.errors.joinToString("\n") { it.render() },
        )
    }

    @Test
    fun `an asset reference with extra fields is rejected`() {
        val literal = text("arrlss").replace(
            "{ \"asset\": \"arrlSections\" }",
            "{ \"asset\": \"arrlSections\", \"values\": [] }",
        )
        val result = ContestLoader.load("arrlss.json", literal)
        assertIs<LoadResult.Failure>(result)
        assertTrue(
            result.errors.any { it.path == "constants.arrlSections.values" && it.message.contains("unknown field") },
            result.errors.joinToString("\n") { it.render() },
        )
    }

    @Test
    fun `an asset may be referenced under another constant name`() {
        // Aliasing is allowed; only copying is not.
        val literal = text("arrlss").replace(
            "\"arrlSections\": { \"asset\": \"arrlSections\" }",
            "\"sections\": { \"asset\": \"arrlSections\" }, \"arrlSections\": { \"asset\": \"arrlSections\" }",
        )
        val result = ContestLoader.load("arrlss.json", literal)
        assertIs<LoadResult.Success>(result, "aliasing a shared asset is legal")
        assertNotNull(result.config.constants["sections"])
    }
}
