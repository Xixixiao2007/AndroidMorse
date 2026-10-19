package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.DEFAULT_STEP_BUDGET
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Expression
import com.bh4gzk.morserunner.expr.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **T10's behaviour checks for NCJ NAQP** — the first per-contest ticket (spec §11).
 *
 * Every claim below is transcribed from `upstream/MorseRunnerCE/NaQp.pas` (MRCE 1.85.4),
 * `ExchFields.pas` and `Ini.pas`, with `file:line`. The config is loaded through the real
 * loader, generated through the real roster and scored through the config's own expressions,
 * so this is a behaviour check of the document, not of a fixture.
 *
 * The DXCC provider is **fake test data**; the real asset is `DXCC.LIST` (spec §9, T10's
 * `PackagedAssetsTest` runs it through this same config).
 */
class NaqpTest {

    // --- fake callsign data (test fixture, not a DXCC.LIST conversion) ---------

    private val fakeCalls: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null

        override fun country(call: String): String? = when {
            call.startsWith("KH6") -> "Hawaii"
            call.startsWith("KL7") -> "Alaska"
            call.startsWith("VE") -> "Canada"
            call.startsWith("K") || call.startsWith("W") || call.startsWith("N") -> "United States of America"
            call.startsWith("DL") -> "Fed. Rep. of Germany"
            call.startsWith("JA") -> "Japan"
            call.startsWith("6Y") -> "Jamaica"
            else -> null
        }

        override fun continent(call: String): String? = when (country(call)) {
            // Hawaii is Oceania in the DXCC table; NAQP treats it as local (NaQp.pas:410-421).
            "Hawaii" -> "OC"
            "Japan" -> "AS"
            "Alaska", "Canada", "United States of America", "Jamaica" -> "NA"
            "Fed. Rep. of Germany" -> "EU"
            else -> null
        }

        override fun arrlSection(call: String): String? = null
    }

    private fun config(): ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("naqp"), "naqp did not load").config

    private val naqp: ContestConfig by lazy { config() }

    private fun Expression.value(environment: Map<String, Value>): Value =
        when (val result = evaluate(environment, DEFAULT_STEP_BUDGET, fakeCalls)) {
            is EvalResult.Success -> result.value
            is EvalResult.Failure -> error("unexpected failure: ${result.error.render()}")
        }

    private fun Expression.text(environment: Map<String, Value>): String =
        assertIs<Value.Str>(value(environment)).value

    private fun Expression.flag(environment: Map<String, Value>): Boolean =
        assertIs<Value.Bool>(value(environment)).value

    // --- the document ---------------------------------------------------------

    @Test
    fun `NAQP ships as a schema-v1 document and the manifest names it`() {
        assertTrue("naqp" in ContestLibrary.manifestIds(), "the manifest does not list naqp")
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        assertEquals(
            listOf("cqww", "arrlss", "iaruhf", "naqp", "k1usnsst", "allja", "acag", "cwops", "cqwpx", "hst", "arrldx", "arrlfd"),
            bundled.configs.map { it.id },
        )
        assertEquals(ContestConfig.SUPPORTED_SCHEMA_VERSION, naqp.schemaVersion)
        assertEquals("naqp", naqp.id)
        assertEquals("NCJ NAQP", naqp.name)
        assertEquals(listOf("single-call", "pile-up"), naqp.runModes)
    }

    @Test
    fun `the two exchange fields are name and state, with upstream's defaults`() {
        // Ini.pas:128-136: '<name> [<state|prov|dxcc-entity>]' (e.g. ALEX ON).
        assertEquals(listOf("name", "state"), naqp.exchange.myFields.map { it.key })
        assertEquals(FieldType.TEXT, naqp.exchange.myFields[0].type)
        assertEquals(FieldType.TEXT, naqp.exchange.myFields[1].type)
        assertEquals("Name", naqp.exchange.myFields[0].label)
        assertEquals("State/Prov", naqp.exchange.myFields[1].label)
        assertTrue(naqp.exchange.entryParse == null, "two boxes, one field each")
        assertTrue(naqp.exchange.sent == null, "the pieces are the fields themselves")
        assertEquals("ALEX", naqp.station.exchangeDefaults["name"], "Ini.pas:133 ExchDefault 'ALEX ON'")
        assertEquals("ON", naqp.station.exchangeDefaults["state"])
    }

    @Test
    fun `validation follows the upstream field definitions`() {
        // ExchFields.pas:45 `etOpName` R: '[A-Z][A-Z]*' L: 10;
        // ExchFields.pas:66 `etNaQpExch2` R: '([0-9A-Z/]*)' L: 6 (empty allowed).
        val name = naqp.validation.first { it.field == "name" }
        val state = naqp.validation.first { it.field == "state" }

        fun rejected(rule: ValidationRule, field: String, text: String): Boolean =
            rule.whenExpression.flag(naqp.environment(field to Value.str(text)))

        assertTrue(!rejected(name, "name", "MIKE"), "a name is accepted")
        assertTrue(!rejected(name, "name", "mike"), "lower case is upper-cased before the test")
        assertTrue(rejected(name, "name", ""), "an empty name is rejected")
        assertTrue(rejected(name, "name", "MIKE JR"), "a space is not part of a name")
        assertTrue(rejected(name, "name", "M".repeat(11)), "longer than 10 is rejected")
        assertTrue(rejected(name, "name", "1MIKE"), "a name starts with a letter")

        assertTrue(!rejected(state, "state", "OR"), "a state is accepted")
        assertTrue(!rejected(state, "state", ""), "a DX station sends no state (empty is legal)")
        assertTrue(!rejected(state, "state", "VE3/P"), "digits and a slash are legal")
        assertTrue(rejected(state, "state", "ONTARIO"), "longer than 6 is rejected")
        assertTrue(rejected(state, "state", "ON!"), "'!' is not part of a state")
    }

    @Test
    fun `the call history is the upstream file shape`() {
        // NaQp.pas:61-159: comma, skips '!!Order!!' and '#', needs tl.Count > 2,
        // columns call,name,state[,userText]; call and name required, name <= 12,
        // the state may be empty.
        val history = naqp.callHistory!!
        assertEquals("NAQPCW.txt", history.asset)
        assertEquals(',', history.delimiter)
        assertEquals(listOf("!!Order!!", "#"), history.skipPrefixes)
        assertEquals(3, history.minFields)
        assertEquals(listOf("call", "name", "state", "userText"), history.columns.map { it.name })
        assertEquals(listOf(true, true, false, false), history.columns.map { it.required })
        assertTrue(!history.dedupe, "upstream keeps every row")
        assertTrue(!history.requiresMyCallInDxcc, "OnSetMyCall warns; it does not refuse (NaQp.pas:178-210)")
        assertTrue(history.header == null, "the !!Order!! line is a skip prefix, not a header")
    }

    @Test
    fun `the call-history parser keeps an empty state and drops malformed rows`() {
        // Transcribed from NaQp.pas:97-159.
        val text = """
            !!Order!!,Call,Name,State,UserText,
            #
            K1ABC,MIKE,OR,
            6Y5PW,PATRICK,
            DL1ABC,,DL,
            VE3ABC,BRUCE,ON,
            KP4NZ,ERIC,KP4,
            K2DEF,JOSE,,
        """.trimIndent()
        val loaded = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(text, naqp.callHistory!!),
            "the NAQP history was rejected",
        ).history

        // Empty call/name rows are dropped; the 4-field and 3-field rows are kept.
        // (Upstream also drops a name longer than 12 characters, `NaQp.pas:124`; the schema's
        // TEXT column has no length rule — ADR-0010 §8. This file has no such row.)
        assertEquals(listOf("K1ABC", "6Y5PW", "VE3ABC", "KP4NZ", "K2DEF"), loaded.calls.toList())
        val k1 = assertNotNull(loaded.recordFor("K1ABC"))
        assertEquals("K1ABC", k1["call"])
        assertEquals("MIKE", k1["name"])
        assertEquals("OR", k1["state"])
        // A DX station has no state; the record carries the empty cell (`NaQp.pas:125`).
        assertEquals("", assertNotNull(loaded.recordFor("6Y5PW"))["state"])
        assertEquals("PATRICK", loaded.recordFor("6Y5PW")!!["name"])
        // The comment banner is not a call.
        assertTrue(loaded.calls.none { it.startsWith("#") || it.startsWith("!!") })
    }

    // --- their exchange -------------------------------------------------------

    private fun generate(call: String, record: Map<String, String>): GeneratedExchange =
        when (val result = TheirExchangeGenerator(naqp, fakeCalls).generate(emptyMap(), record, null)) {
            is GenerationResult.Success -> result.exchange
            is GenerationResult.Failure -> error("'$call': ${result.message}")
        }

    @Test
    fun `an NA station sends name and state, a DX station sends only its name`() {
        // NaQp.pas:439-448 getExch1/getExch2; :215-216 the local/DX exchange types.
        val na = generate("K1ABC", mapOf("name" to "MIKE", "state" to "OR"))
        assertEquals("MIKE", na.exch1)
        assertEquals("OR", na.exch2)
        assertTrue(na.parsed.isEmpty(), "NAQP needs no their.parse (ExtractMultiplier uses Exch2)")

        val dx = generate("DL1ABC", mapOf("name" to "ZVONKO", "state" to ""))
        assertEquals("ZVONKO", dx.exch1)
        assertEquals("", dx.exch2, "a DX station sends no second piece")
    }

    // --- scoring (transcribed from NaQp.pas) ----------------------------------

    @Test
    fun `points are one and the multiplier is the state of an NA or Hawaii station`() {
        // NaQp.pas:395-404: Points := 1; multiplier is Exch2 for a local call, '' otherwise.
        // :410-421: local = continent NA or entity Hawaii.
        fun multipliers(call: String, exch2: String): List<String> {
            val environment = naqp.environment(
                "theirCall" to Value.str(call),
                "theirExch1" to Value.str("MIKE"),
                "theirExch2" to Value.str(exch2),
            )
            return naqp.scoring.multipliers.mapNotNull { multiplier ->
                if (multiplier.whenExpression != null && !multiplier.whenExpression.flag(environment)) {
                    null
                } else {
                    multiplier.key.text(environment)
                }
            }
        }

        assertEquals(1.0, (naqp.scoring.points.value(naqp.environment()) as Value.Num).value)
        assertEquals(listOf("OR"), multipliers("K1ABC", "OR"))
        assertEquals(listOf("ON"), multipliers("VE3ABC", "ON"), "Canada is NA")
        assertEquals(listOf("AK"), multipliers("KL7AA", "AK"), "Alaska's continent is NA")
        assertEquals(listOf("HI"), multipliers("KH6XX", "HI"), "Hawaii is an entity exception")
        assertTrue(multipliers("DL1ABC", "").isEmpty(), "a DX station is not a multiplier")
        assertTrue(multipliers("DL1ABC", "DL").isEmpty(), "even if it sent something")
    }

    // --- the roster -----------------------------------------------------------

    private fun loadedHistory(): CallHistoryFile {
        val text = listOf(
            "!!Order!!,Call,Name,State,UserText,",
            "#",
            "K1ABC,MIKE,OR,",
            "W2XYZ,DAVE,NY,",
            "VE3ABC,BRUCE,ON,",
            "KH6XX,LEE,HI,",
            "DL1ABC,ZVONKO,,",
            "6Y5PW,PATRICK,",
            "JA1XYZ,HIRO,,",
        ).joinToString("\n")
        return assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, naqp.callHistory!!)).history
    }

    private fun roster(seed: Long): StationRoster = StationRoster(
        config = naqp,
        pool = loadedHistory(),
        history = loadedHistory(),
        // Five of the fixture's seven calls: enough to prove the draw without exhausting the
        // duplicate retries (a real pool has 9 000).
        settings = RosterSettings(speedWpm = 25, maxStations = 5),
        seed = seed,
        calls = fakeCalls,
    )

    @Test
    fun `the roster generates the file's exchange for every station, over many seeds`() {
        val history = loadedHistory()
        var dx = 0
        var na = 0
        for (seed in 1L..25L) {
            val roster = roster(seed)
            repeat(5) { roster.add() }
            roster.stations.forEach { station ->
                val record = assertNotNull(history.recordFor(station.call), "invented '${station.call}'")
                assertEquals(record["name"], station.exchange.exch1, "seed $seed: name of ${station.call}")
                assertEquals(record["state"].orEmpty(), station.exchange.exch2, "seed $seed: state of ${station.call}")
                if (station.exchange.exch2.isEmpty()) dx++ else na++
            }
        }
        assertTrue(na > 0, "no NA station was generated")
        assertTrue(dx > 0, "no DX station (empty state) was generated — the empty cell was dropped")
    }

    @Test
    fun `the same seed and settings replay the same roster`() {
        fun rosterText(seed: Long): List<String> = roster(seed).let { r ->
            repeat(5) { r.add() }
            r.stations.map { "${it.call}|${it.speedWpm}|${it.pitchHz}|${it.exchange.exch1}|${it.exchange.exch2}" }
        }
        assertEquals(rosterText(7L), rosterText(7L), "the NAQP roster is not deterministic")
        assertTrue(rosterText(7L).isNotEmpty())
    }

    @Test
    fun `a station with no history record is skipped, not invented`() {
        // The pool is the history itself, so this is the fail-closed path: every generated call
        // must come from the file. A short pool makes the ten-attempt budget the thing under test.
        val history = loadedHistory()
        val roster = StationRoster(
            config = naqp,
            pool = history,
            history = history,
            settings = RosterSettings(speedWpm = 25, maxStations = 2),
            seed = 3L,
            calls = fakeCalls,
        )
        roster.add()
        roster.add()
        assertEquals(2, roster.size)
        roster.stations.forEach { assertTrue(history.recordFor(it.call) != null, it.call) }
    }
}
