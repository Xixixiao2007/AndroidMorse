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
 * **T12's behaviour checks for JARL ALL JA** — the third per-contest ticket (spec §11).
 *
 * Every claim below is transcribed from `upstream/MorseRunnerCE/AllJa.pas` (MRCE 1.85.4),
 * `ExchFields.pas`, `Ini.pas` and `Log.pas`, with `file:line`. The config is loaded through
 * the real loader, generated through the real roster and scored through the config's own
 * expressions, so this is a behaviour check of the document, not of a fixture.
 *
 * Two things make this contest different from NAQP/SST and are the point of this ticket:
 *
 *  - the station's second piece is the **file's own** `<prefecture><power>` cell, and the
 *    multiplier strips a trailing `L`/`M`/`H`/`P` (`AllJa.pas:190-207`) — the first config to
 *    need `substr` and a *anchored* `matches` in a multiplier;
 *  - the history file's rules are thinner than every earlier one (`tl.Count > 1`, two non-empty
 *    cells, no call grammar at all, `AllJa.pas:72-100`), so the measured row count is a fact
 *    this ticket states rather than assumes.
 *
 * The DXCC provider is **fake test data**: ALL JA's rules never ask for a DXCC fact (no
 * `call*` built-in in the document), which is itself worth pinning.
 */
class AllJaTest {

    // --- fake callsign data (test fixture, not a DXCC.LIST conversion) ---------

    private val fakeCalls: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null
        override fun country(call: String): String? = null
        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }

    private fun config(): ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("allja"), "allja did not load").config

    private val allja: ContestConfig by lazy { config() }

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
    fun `ALL JA ships as a schema-v1 document and the manifest names it`() {
        assertTrue("allja" in ContestLibrary.manifestIds(), "the manifest does not list allja")
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        assertEquals(
            listOf("cqww", "arrlss", "iaruhf", "naqp", "k1usnsst", "allja", "acag"),
            bundled.configs.map { it.id },
        )
        assertEquals(ContestConfig.SUPPORTED_SCHEMA_VERSION, allja.schemaVersion)
        assertEquals("allja", allja.id)
        // Ini.pas:178-185, the contest's own name.
        assertEquals("JARL ALL JA", allja.name)
        assertEquals(listOf("single-call", "pile-up"), allja.runModes)
    }

    @Test
    fun `ALL JA declares no Farnsworth and no DXCC gate, as the Pascal does`() {
        // TALLJA.Create (`AllJa.pas:106-111`) never sets `BFarnsworthEnabled`, and the contest
        // declares no DXCC requirement (LoadCallHistory ignores the operator call, `:50-100`).
        assertTrue(!allja.capabilities.farnsworth, "AllJa.pas does not enable Farnsworth")
        assertTrue(!allja.capabilities.requiresMyDxcc)
    }

    @Test
    fun `the two exchange fields are the RST and the JARL number, with upstream's default`() {
        // Ini.pas:178-185: ExchType1 etRST, ExchType2 etJaPref, ExchDefault '5NN 10H',
        // Msg '''RST <Pref><Power>'' (e.g. 5NN 10H)'. Captions come from ExchFields.pas:43,62
        // ('RST' and 'Number').
        assertEquals(listOf("rst", "pref"), allja.exchange.myFields.map { it.key })
        assertEquals(FieldType.RST, allja.exchange.myFields[0].type)
        assertEquals(FieldType.TEXT, allja.exchange.myFields[1].type)
        assertEquals("RST", allja.exchange.myFields[0].label)
        assertEquals("Number", allja.exchange.myFields[1].label)
        assertTrue(allja.exchange.entryParse == null, "two boxes, one field each")
        assertTrue(allja.exchange.sent == null, "the pieces are the fields themselves")
        assertEquals("5NN", allja.station.exchangeDefaults["rst"], "Ini.pas:183 ExchDefault '5NN 10H'")
        assertEquals("10H", allja.station.exchangeDefaults["pref"])
    }

    @Test
    fun `validation follows the upstream field definitions`() {
        // ExchFields.pas:44 `etRST` R: '[1-5E][1-9N][1-9N]' L: 3;
        // ExchFields.pas:62 `etJaPref` R: '([0-9AOTN]*)([LMHP])' L: 4.
        // Upstream wraps a field definition as '^(' + R + ')$' (`Contest.pas:310`).
        val rst = allja.validation.first { it.field == "rst" }
        val pref = allja.validation.first { it.field == "pref" }

        fun rejected(rule: ValidationRule, field: String, text: String): Boolean =
            rule.whenExpression.flag(allja.environment(field to Value.str(text)))

        assertTrue(!rejected(rst, "rst", "599"), "a plain report is accepted")
        assertTrue(!rejected(rst, "rst", "5NN"), "the letters E/N are allowed upstream")
        assertTrue(!rejected(rst, "rst", "579"), "a real report is accepted")
        assertTrue(!rejected(rst, "rst", "5nn"), "the operator's box is upper-cased before the test")
        assertTrue(rejected(rst, "rst", ""), "an empty report is rejected")
        assertTrue(rejected(rst, "rst", "500"), "the second digit is 1-9/N, not 0")
        assertTrue(rejected(rst, "rst", "5"), "a report is three characters")
        assertTrue(rejected(rst, "rst", "5999"), "a report is three characters")

        assertTrue(!rejected(pref, "pref", "10H"), "a prefecture number is accepted")
        assertTrue(!rejected(pref, "pref", "27M"), "a power letter is required")
        assertTrue(!rejected(pref, "pref", "112M"), "3-digit promotion-bureau codes fit in 4")
        assertTrue(!rejected(pref, "pref", "10h"), "case is normalised before the test")
        assertTrue(rejected(pref, "pref", ""), "an empty number is rejected")
        assertTrue(rejected(pref, "pref", "10"), "a trailing L/M/H/P is required")
        assertTrue(rejected(pref, "pref", "1123M"), "longer than 4 is rejected")
        assertTrue(rejected(pref, "pref", "10X"), "'X' is not one of the power letters")
    }

    // --- the call history -----------------------------------------------------

    @Test
    fun `the call history is the upstream file shape`() {
        // AllJa.pas:50-100: comma + StrictDelimiter, skips '!!Order!!' and '#', keeps a row
        // with `tl.Count > 1`, columns call,<pref><power>[,usertext]; both required cells are
        // only tested for emptiness (no call grammar).
        val history = allja.callHistory
        assertEquals("JARL_ALLJA.TXT", history.asset, "AllJa.pas:72 loads its own file, not MASTER.DTA")
        assertEquals(',', history.delimiter)
        assertEquals(listOf("!!Order!!", "#"), history.skipPrefixes)
        assertEquals(2, history.minFields, "`if (tl.Count > 1)` is the whole row rule")
        assertEquals(listOf("call", "number", "userText"), history.columns.map { it.name })
        assertEquals(listOf(true, true, false), history.columns.map { it.required })
        assertTrue(!history.dedupe, "upstream keeps every row")
        assertTrue(!history.requiresMyCallInDxcc, "LoadCallHistory ignores the operator call")
        assertTrue(history.header == null, "the '#' banner is a skip prefix, not a header")
    }

    @Test
    fun `the call-history parser keeps a two-cell row and drops an empty cell`() {
        // AllJa.pas:74-96 is the whole rule table: `tl.Count > 1`, then `continue` on an empty
        // Call or an empty Exchange 1. A third cell is optional user text.
        val text = """
            # Call, Exch1, UserText,
            # JARL contest support
            7K1CPT,14H
            7K1III,10H
            JQ1ABC,
            ,27M
            JA8BMD,110M,Some user text
            JA4GBD/8,112M
        """.trimIndent()
        val loaded = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(text, allja.callHistory),
            "the ALL JA history was rejected",
        ).history

        // The row with no exchange cell and the row with no call are both skipped upstream.
        assertEquals(listOf("7K1CPT", "7K1III", "JA8BMD", "JA4GBD/8"), loaded.calls.toList())
        val row = assertNotNull(loaded.recordFor("7K1CPT"))
        assertEquals("7K1CPT", row["call"])
        assertEquals("14H", row["number"])
        // A two-cell row has no user-text column at all (upstream only stores it for `Count >= 3`);
        // a three-cell row keeps it, trimmed.
        assertTrue("userText" !in row)
        assertEquals("Some user text", loaded.recordFor("JA8BMD")?.get("userText"))
        assertTrue(loaded.calls.none { it.startsWith("#") || it.startsWith("!!") })
    }

    // --- their exchange -------------------------------------------------------

    private fun generate(call: String, record: Map<String, String>): GeneratedExchange =
        when (val result = TheirExchangeGenerator(allja, fakeCalls).generate(emptyMap(), record, null)) {
            is GenerationResult.Success -> result.exchange
            is GenerationResult.Failure -> error("'$call': ${result.message}")
        }

    @Test
    fun `a station always sends 599 and its file number, upper-cased, with no parse step`() {
        // AllJa.pas:214-227 GetExchange: Exch1 := getExch1 (`:220-223` always `'599'`),
        // Exch2 := the file's Number, which `:85-86` upper-cased at load.
        val station = generate("7K1CPT", mapOf("number" to "14h"))
        assertEquals("599", station.exch1, "AllJa.pas:220 `result := '599'`")
        assertEquals("14H", station.exch2)
        assertTrue(station.parsed.isEmpty(), "ALL JA needs no their.parse (ExtractMultiplier reads Exch2)")

        val threeDigit = generate("JA8BMD", mapOf("number" to "110M"))
        assertEquals("599", threeDigit.exch1)
        assertEquals("110M", threeDigit.exch2)
    }

    // --- scoring (transcribed from AllJa.pas) ---------------------------------

    private fun multipliers(call: String, exch2: String): List<String> {
        val environment = allja.environment(
            "theirCall" to Value.str(call),
            "theirExch1" to Value.str("599"),
            "theirExch2" to Value.str(exch2),
        )
        return allja.scoring.multipliers.mapNotNull { multiplier ->
            if (multiplier.whenExpression != null && !multiplier.whenExpression.flag(environment)) {
                null
            } else {
                multiplier.key.text(environment)
            }
        }
    }

    @Test
    fun `one point per contact, and the multiplier strips only a trailing power letter`() {
        // AllJa.pas:206 `Qso^.Points := 1;` — regardless of prefecture or power.
        assertEquals(1.0, (allja.scoring.points.value(allja.environment()) as Value.Num).value)

        // AllJa.pas:190-207: `S[Length(S)]` in ['L','M','H','P'] → `Copy(S,1,Length(S)-1)`,
        // else the whole string. The document spells "ends with one of LMHP" as
        // `matches(x, '[LMHP]$')`, and Evaluator.matches is `containsMatchIn` — the `$` is what
        // makes it a *suffix* test, not a whole-string test. These cases pin exactly that.
        assertEquals(listOf("14"), multipliers("7K1CPT", "14H"))
        assertEquals(listOf("27"), multipliers("7J3AOZ", "27M"))
        assertEquals(listOf("112"), multipliers("JA4GBD/8", "112M"))
        assertEquals(listOf("08"), multipliers("JQ1ABC", "08L"))
        // The last letter is not L/M/H/P: upstream keeps the whole string.
        assertEquals(listOf("2708"), multipliers("K1ABC", "2708"))
        assertEquals(listOf("M27"), multipliers("K1ABC", "M27"), "'M' is not a *trailing* power letter")
        // A one-character number is `Copy(S,1,0)` upstream — the empty string, which the engine
        // skips (an empty key is not a multiplier, `ContestRules.score`).
        assertEquals(listOf(""), multipliers("K1ABC", "M"))
    }

    @Test
    fun `the document never asks the DXCC table for a fact`() {
        // No `call*` built-in appears in scoring, so the fail-closed rule that costs SST one
        // record in 10 705 cannot cost ALL JA anything: every row scores its prefecture.
        val environment = allja.environment(
            "theirCall" to Value.str("JA8BMD"),
            "theirExch1" to Value.str("599"),
            "theirExch2" to Value.str("110M"),
        )
        val result = allja.scoring.multipliers.single().key.evaluate(environment, DEFAULT_STEP_BUDGET, fakeCalls)
        assertIs<EvalResult.Success>(result, "the multiplier must not need DXCC data")
        assertEquals(listOf("110"), multipliers("JA8BMD", "110M"))
    }

    // --- the roster -----------------------------------------------------------

    private fun loadedHistory(): CallHistoryFile {
        // Sixteen calls for at most six stations: the duplicate retry budget (ten attempts) then
        // has room, so a seed cannot exhaust it the way a pool exactly as large as the roster does
        // (the T10/T11 fixture lesson, `.scratch/progress.md` §31/§32).
        val text = listOf(
            "# Call, Exch1, UserText,",
            "7J3AOZ,27M",
            "7K1BIB,10M",
            "7K1CPT,14H",
            "7K1DPE,13M",
            "7K1III,10H",
            "7K1MAG/2,21M",
            "JA1ABC,11H",
            "JA2BCD,08L",
            "JA3CDE,22M",
            "JA4GBD/8,112M",
            "JA5DEF,31H",
            "JA6EFG,43M",
            "JA7FGH,02L",
            "JA8BMD,110M",
            "JA9GHI,47H",
            "JH1JKL,09M",
        ).joinToString("\n")
        return assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, allja.callHistory)).history
    }

    private fun roster(seed: Long): StationRoster = StationRoster(
        config = allja,
        pool = loadedHistory(),
        history = loadedHistory(),
        settings = RosterSettings(speedWpm = 15, maxStations = 6),
        seed = seed,
        calls = fakeCalls,
    )

    @Test
    fun `the roster generates 599 and the file's number for every station, over many seeds`() {
        val history = loadedHistory()
        var highPower = 0
        var lowPower = 0
        for (seed in 1L..25L) {
            val roster = roster(seed)
            repeat(6) { roster.add() }
            roster.stations.forEach { station ->
                val record = assertNotNull(history.recordFor(station.call), "invented '${station.call}'")
                assertEquals("599", station.exchange.exch1, "seed $seed: RST of ${station.call}")
                assertEquals(
                    record["number"]?.uppercase(),
                    station.exchange.exch2,
                    "seed $seed: number of ${station.call}",
                )
                if (station.exchange.exch2.endsWith("H")) highPower++ else lowPower++
            }
        }
        assertTrue(highPower > 0, "no high-power station was generated")
        assertTrue(lowPower > 0, "no mid/low-power station was generated")
    }

    @Test
    fun `the same seed and settings replay the same roster`() {
        fun rosterText(seed: Long): List<String> = roster(seed).let { r ->
            repeat(6) { r.add() }
            r.stations.map { "${it.call}|${it.speedWpm}|${it.pitchHz}|${it.exchange.exch1}|${it.exchange.exch2}" }
        }
        assertEquals(rosterText(7L), rosterText(7L), "the ALL JA roster is not deterministic")
        assertTrue(rosterText(7L).isNotEmpty())
    }

    @Test
    fun `a station whose exchange the file cannot describe is skipped, not invented`() {
        val history = loadedHistory()
        val roster = StationRoster(
            config = allja,
            pool = history,
            history = history,
            settings = RosterSettings(speedWpm = 15, maxStations = 2),
            seed = 3L,
            calls = fakeCalls,
        )
        roster.add()
        roster.add()
        assertEquals(2, roster.size)
        roster.stations.forEach { assertTrue(history.recordFor(it.call) != null, it.call) }
    }
}
