package com.bh4gzk.morserunner.contest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Load-time validation (spec §4): a bad contest document is rejected **before**
 * a QSO, naming the file, the field, and — for an expression — the `line:col`.
 *
 * Each test takes a real shipped config and injects one realistic authoring
 * mistake. That keeps the tests honest about the documents that actually ship.
 */
class ContestLoaderTest {

    private fun text(id: String): String =
        javaClass.classLoader.getResourceAsStream("contests/$id.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun errors(id: String, mutate: (String) -> String): List<ConfigError> {
        val result = ContestLoader.load("$id.json", mutate(text(id)))
        assertIs<LoadResult.Failure>(result, "expected $id.json to be rejected")
        return result.errors
    }

    private fun assertHas(errors: List<ConfigError>, path: String, vararg fragments: String) {
        val match = errors.firstOrNull { it.path == path && fragments.all { f -> it.message.contains(f) } }
        assertNotNull(
            match,
            "no error at '$path' containing ${fragments.toList()}\ngot:\n" +
                errors.joinToString("\n") { it.render() },
        )
    }

    @Test
    fun `rejects invalid JSON with a line and column`() {
        val result = ContestLoader.load("broken.json", text("cqww").dropLast(40))
        assertIs<LoadResult.Failure>(result)
        val error = result.errors.single()
        assertEquals("", error.path, "a JSON syntax error is about the file, not a field")
        assertTrue(
            Regex("^\\d+:\\d+ \u2014 .+").matches(error.message),
            "expected 'line:col — message', got '${error.message}'",
        )
    }

    @Test
    fun `rejects a duplicate JSON key`() {
        val errors = errors("arrlss") { it.replace("\"points\": \"2\",", "\"points\": \"2\", \"points\": \"3\",") }
        assertHas(errors, "", "duplicate key 'points'")
    }

    @Test
    fun `rejects an unknown top-level field`() {
        val errors = errors("cqww") { it.replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1,\n  \"extra\": 1,") }
        assertHas(errors, "extra", "unknown field")
    }

    @Test
    fun `rejects a missing required field`() {
        val errors = errors("cqww") { it.replace("    \"asset\": \"CQWWCW.txt\",\n", "") }
        assertHas(errors, "callHistory.asset", "missing required string")
    }

    @Test
    fun `a document without a call history loads, and one that binds a column still fails`() {
        // T15: `callHistory` is optional (CQ WPX has none, `CqWpx.pas:87-97` reads MASTER.DTA
        // as the pool only). "Optional" must not hide a typo: a `history` binding without a
        // history is still rejected, because there is no column list to validate it against.
        val loaded = ContestLoader.load("cqwpx.json", text("cqwpx"))
        val config = assertIs<LoadResult.Success>(loaded, "cqwpx was rejected").config
        assertNull(config.callHistory, "cqwpx declares no call history")
        assertTrue(config.station.callPool.isNotBlank(), "the pool is still declared")

        val bound = errors("cqwpx") {
            it.replace(
                "\"hisNr\": { \"source\": \"serial\" }",
                "\"hisNr\": { \"source\": \"history\", \"column\": \"zone\" }",
            )
        }
        assertHas(bound, "exchange.their.bindings.hisNr.column", "is not a call-history column")
    }

    @Test
    fun `rejects an unsupported schema version`() {
        val errors = errors("cqww") { it.replace("\"schemaVersion\": 1,", "\"schemaVersion\": 2,") }
        assertHas(errors, "schemaVersion", "unsupported schema version 2")
    }

    @Test
    fun `rejects an id that is not a bundled contest`() {
        val errors = errors("cqww") { it.replace("\"id\": \"cqww\",", "\"id\": \"mars\",") }
        assertHas(errors, "id", "not one of the bundled contest ids")
    }

    @Test
    fun `rejects an unknown run mode`() {
        val errors = errors("cqww") { it.replace("\"single-call\"", "\"telepathy\"") }
        assertHas(errors, "runModes[0]", "unknown run mode 'telepathy'")
    }

    @Test
    fun `rejects a duplicate exchange field key`() {
        val errors = errors("arrlss") { it.replace("\"key\": \"section\"", "\"key\": \"check\"") }
        assertHas(errors, "exchange.myFields", "duplicate field key 'check'")
    }

    @Test
    fun `rejects an enum field without values`() {
        val errors = errors("arrlss") { it.replace("\"valuesRef\": \"arrlSections\"", "\"values\": null") }
        assertTrue(
            errors.any { it.message.contains("an enum field needs 'values' or 'valuesRef'") },
            errors.joinToString("\n") { it.render() },
        )
    }

    @Test
    fun `rejects a parse group that is not an exchange field`() {
        val errors = errors("arrlss") { it.replace("(?<section>", "(?<sector>") }
        assertHas(errors, "exchange.entryParse.pattern", "group 'sector' has no matching exchange field")
    }

    @Test
    fun `rejects an invalid parse regex`() {
        val errors = errors("arrlss") { it.replace("^ *(?<nr>", "^ *(?<nr>[") }
        assertHas(errors, "exchange.entryParse.pattern", "invalid regular expression")
    }

    @Test
    fun `rejects a binding to a call-history column that does not exist`() {
        val errors = errors("cqww") { it.replace("\"column\": \"zone\"", "\"column\": \"cqz\"") }
        assertHas(errors, "exchange.their.bindings.hisZone.column", "'cqz' is not a call-history column")
    }

    @Test
    fun `rejects an unknown expression function with its line and column`() {
        val errors = errors("arrlss") { it.replace("\"points\": \"2\"", "\"points\": \"nope(1)\"") }
        assertHas(errors, "scoring.points", "1:1", "unknown function 'nope'")
    }

    @Test
    fun `rejects a wrong argument count with its line and column`() {
        val errors = errors("arrlss") { it.replace("\"points\": \"2\"", "\"points\": \"str(1, 2)\"") }
        assertHas(errors, "scoring.points", "expects 1 argument, got 2")
    }

    @Test
    fun `rejects an invalid literal regex at load time`() {
        val errors = errors("cqww") { it.replace("'^[1-5][0-9]{2}$'", "'['") }
        assertHas(errors, "validation[1].when", "invalid regular expression")
    }

    @Test
    fun `rejects an identifier outside the field's environment`() {
        val errors = errors("arrlss") { it.replace("\"points\": \"2\"", "\"points\": \"countrey\"") }
        assertHas(errors, "scoring.points", "unknown identifier 'countrey'")
    }

    @Test
    fun `rejects a scoring field that is not bound in the scoring environment`() {
        // `speed` belongs to station.speedRange, not to scoring.
        val errors = errors("arrlss") { it.replace("\"points\": \"2\"", "\"points\": \"speed\"") }
        assertHas(errors, "scoring.points", "unknown identifier 'speed'")
    }

    @Test
    fun `rejects a header-driven column that also declares an index`() {
        val errors = errors("iaruhf") {
            it.replace(
                "{ \"name\": \"call\", \"type\": \"callsign\", \"required\": true }",
                "{ \"name\": \"call\", \"index\": 0, \"type\": \"callsign\", \"required\": true }",
            )
        }
        assertHas(errors, "callHistory.columns[0]", "must not declare an 'index'")
    }

    @Test
    fun `rejects a positional column without an index`() {
        val errors = errors("cqww") { it.replace("\"index\": 0, \"type\": \"callsign\"", "\"type\": \"callsign\"") }
        assertHas(errors, "callHistory.columns[0]", "needs an 'index'")
    }

    @Test
    fun `reports every problem in one pass instead of stopping at the first`() {
        val errors = errors("arrlss") {
            it.replace("\"points\": \"2\"", "\"points\": \"nope(1)\"")
                .replace("\"key\": \"section\"", "\"key\": \"check\"")
        }
        assertTrue(errors.size >= 2, "expected both errors, got:\n" + errors.joinToString("\n") { it.render() })
    }

    @Test
    fun `a good document loads into an immutable config with compiled expressions`() {
        val result = ContestLoader.load("cqww.json", text("cqww"))
        assertIs<LoadResult.Success>(result)
        val config = result.config
        assertEquals("cqww", config.id)
        assertEquals("CQ World Wide DX CW", config.name)
        assertEquals(setOf("rst", "zone"), config.exchange.fieldKeys)
        assertEquals(listOf("leadingZero"), config.exchange.their.dice)
        // Expressions are already compiled programs, not source strings.
        assertTrue(
            config.scoring.points.identifiers()
                .containsAll(listOf("theirCall", "myCountry", "myContinent")),
            "scoring.points should read the DXCC identifiers, saw ${config.scoring.points.identifiers()}",
        )
    }

    // --- T17: the call-history row filter ---------------------------------------

    @Test
    fun `a call history filter compiles against the station properties and its own columns`() {
        // ARRL DX's station pool depends on which side of the contest the operator is on
        // (`ArrlDx.pas:157-158`), so a history may declare a per-row predicate. It sees the
        // row's own columns plus the station properties — `myCountry` is what decides the side.
        val result = ContestLoader.load(
            "filtered.json",
            text("cqww").replace(
                "\"requiresMyCallInDxcc\": false",
                "\"requiresMyCallInDxcc\": false, " +
                    "\"filter\": \"ifElse(myCountry == 'United States of America', zone != '', true)\"",
            ),
        )
        assertIs<LoadResult.Success>(result)
        val filter = assertNotNull(result.config.callHistory!!.filter, "the filter was dropped")
        assertEquals(
            setOf("myCountry", "zone"),
            filter.identifiers(),
            "the filter should read the row's column and the operator's country",
        )
    }

    @Test
    fun `a call history filter cannot read the QSO identifiers`() {
        // A history is loaded before any QSO exists: `theirCall` has no value to bind.
        val errors = errors("cqww") {
            it.replace(
                "\"requiresMyCallInDxcc\": false",
                "\"requiresMyCallInDxcc\": false, \"filter\": \"theirCall == 'DL1ABC'\"",
            )
        }
        assertHas(errors, "callHistory.filter", "theirCall")
    }
}
