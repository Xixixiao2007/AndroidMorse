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
 * **T13's behaviour checks for JARL ACAG** — the fourth per-contest ticket (spec §11).
 *
 * Every claim below is transcribed from `upstream/MorseRunnerCE/Acag.pas` (MRCE 1.85.4),
 * `ExchFields.pas`, `Ini.pas` and `Log.pas`, with `file:line`. The config is loaded through
 * the real loader, generated through the real roster and scored through the config's own
 * expressions, so this is a behaviour check of the document, not of a fixture.
 *
 * ACAG is ALL JA's twin with three differences that are the point of this ticket:
 *
 *  - the operator's second field is `etJaCity` (`Ini.pas:190`), the same *shape*
 *    `([0-9AOTN]*)([LMHP])` as ALL JA's `etJaPref` but with `L: 7` instead of `L: 4`
 *    (`ExchFields.pas:63`), because the number can be a city, a gun or a ku code;
 *  - the shipped file's multipliers include **leading zeros** (`7K1AEU/8,01039M`), so the
 *    multiplier must stay a string — `01039` must not become `1039`;
 *  - the load rules and the scoring strip are literally ALL JA's (`Acag.pas:72-100`,
 *    `:191-207`), so the two documents must agree rather than be re-derived.
 *
 * The DXCC provider is **fake test data**: ACAG's rules never ask for a DXCC fact (no `call*`
 * built-in in the document), which is itself worth pinning.
 */
class AcagTest {

    // --- fake callsign data (test fixture, not a DXCC.LIST conversion) ---------

    private val fakeCalls: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null
        override fun country(call: String): String? = null
        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }

    private fun config(): ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("acag"), "acag did not load").config

    private val acag: ContestConfig by lazy { config() }

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
    fun `ACAG ships as a schema-v1 document and the manifest names it`() {
        assertTrue("acag" in ContestLibrary.manifestIds(), "the manifest does not list acag")
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        assertEquals(
            listOf("cqww", "arrlss", "iaruhf", "naqp", "k1usnsst", "allja", "acag", "cwops", "cqwpx", "hst", "arrldx"),
            bundled.configs.map { it.id },
        )
        assertEquals(ContestConfig.SUPPORTED_SCHEMA_VERSION, acag.schemaVersion)
        assertEquals("acag", acag.id)
        // Ini.pas:187, the contest's own name.
        assertEquals("JARL ACAG", acag.name)
        assertEquals(listOf("single-call", "pile-up"), acag.runModes)
    }

    @Test
    fun `ACAG declares no Farnsworth and no DXCC gate, as the Pascal does`() {
        // TACAG.Create (`Acag.pas:106-111`) never sets `BFarnsworthEnabled`, and LoadCallHistory
        // ignores the operator call (`:50-100`).
        assertTrue(!acag.capabilities.farnsworth, "Acag.pas does not enable Farnsworth")
        assertTrue(!acag.capabilities.requiresMyDxcc)
    }

    @Test
    fun `the two exchange fields are the RST and the JARL city code, with upstream's default`() {
        // Ini.pas:187-194: ExchType1 etRST, ExchType2 etJaCity, ExchDefault '5NN 1002H',
        // Msg '''RST <City|Gun|Ku><Power>'' (e.g. 5NN 1002H)'. Captions come from
        // ExchFields.pas:43,63 ('RST' and 'Number').
        assertEquals(listOf("rst", "city"), acag.exchange.myFields.map { it.key })
        assertEquals(FieldType.RST, acag.exchange.myFields[0].type)
        assertEquals(FieldType.TEXT, acag.exchange.myFields[1].type)
        assertEquals("RST", acag.exchange.myFields[0].label)
        assertEquals("Number", acag.exchange.myFields[1].label)
        assertTrue(acag.exchange.entryParse == null, "two boxes, one field each")
        assertTrue(acag.exchange.sent == null, "the pieces are the fields themselves")
        assertEquals("5NN", acag.station.exchangeDefaults["rst"], "Ini.pas:192 ExchDefault '5NN 1002H'")
        assertEquals("1002H", acag.station.exchangeDefaults["city"])
    }

    @Test
    fun `the duration is the majority clamp, because no artifact states ACAG's window`() {
        // `Acag.pas` does not override `Ini.Duration`; `Ini.pas:263` is the global default 30 and
        // `:267` the competition timer. ADR-0013 §5 declares the gap instead of guessing.
        assertEquals(Duration(defaultMinutes = 30, minMinutes = 1, maxMinutes = 60), acag.duration)
    }

    @Test
    fun `validation follows the upstream field definitions, including the L7 number`() {
        // ExchFields.pas:44 `etRST` R: '[1-5E][1-9N][1-9N]' L: 3;
        // ExchFields.pas:63 `etJaCity` R: '([0-9AOTN]*)([LMHP])' L: 7.
        // Upstream wraps a field definition as '^(' + R + ')$' (`Contest.pas:310`).
        val rst = acag.validation.first { it.field == "rst" }
        val city = acag.validation.first { it.field == "city" }

        fun rejected(rule: ValidationRule, field: String, text: String): Boolean =
            rule.whenExpression.flag(acag.environment(field to Value.str(text)))

        assertTrue(!rejected(rst, "rst", "599"), "a plain report is accepted")
        assertTrue(!rejected(rst, "rst", "5NN"), "the letters E/N are allowed upstream")
        assertTrue(rejected(rst, "rst", ""), "an empty report is rejected")
        assertTrue(rejected(rst, "rst", "500"), "the second digit is 1-9/N, not 0")
        assertTrue(rejected(rst, "rst", "5999"), "a report is three characters")

        // The city box is ALL JA's shape at seven characters, not four: 5-digit gun codes and
        // 6-digit ku codes exist (`Acag.pas:185-188`).
        assertTrue(!rejected(city, "city", "1002H"), "a 4-digit city code + power, the default")
        assertTrue(!rejected(city, "city", "2708M"), "a 4-digit city code + power")
        assertTrue(!rejected(city, "city", "01039M"), "a 5-digit gun code + power")
        assertTrue(!rejected(city, "city", "100115M"), "a 6-digit ku code + power")
        assertTrue(!rejected(city, "city", "1002h"), "case is normalised before the test")
        assertTrue(rejected(city, "city", ""), "an empty number is rejected")
        assertTrue(rejected(city, "city", "1002"), "a trailing L/M/H/P is required")
        assertTrue(rejected(city, "city", "1002X"), "'X' is not one of the power letters")
        assertTrue(rejected(city, "city", "1001156M"), "longer than seven is rejected")
    }

    // --- the call history -----------------------------------------------------

    @Test
    fun `the call history is the upstream file shape`() {
        // Acag.pas:50-100 (the same code as AllJa.pas): comma + StrictDelimiter, skips
        // '!!Order!!' and '#', keeps a row with `tl.Count > 1`, columns
        // call,<city|gun|ku><power>[,usertext]; both required cells are only tested for
        // emptiness (no call grammar).
        val history = acag.callHistory!!
        assertEquals("JARL_ACAG.TXT", history.asset, "Acag.pas:72 loads its own file, not MASTER.DTA")
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
        // Acag.pas:74-96 is the whole rule table: `tl.Count > 1`, then `continue` on an empty
        // Call or an empty Number. A third cell is optional user text.
        val text = """
            # Call, Exch1, UserText,
            # JARL contest support
            7J3AOZ,2708M
            7K1AEU/8,01039M
            JQ1ABC,
            ,27M
            JA8BMD,100115M,Some user text
            JA4GBD/8,112M
        """.trimIndent()
        val loaded = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(text, acag.callHistory!!),
            "the ACAG history was rejected",
        ).history

        // The row with no number cell and the row with no call are both skipped upstream.
        assertEquals(listOf("7J3AOZ", "7K1AEU/8", "JA8BMD", "JA4GBD/8"), loaded.calls.toList())
        val row = assertNotNull(loaded.recordFor("7K1AEU/8"))
        assertEquals("7K1AEU/8", row["call"])
        assertEquals("01039M", row["number"], "a leading zero is part of the code, not a formatting detail")
        // A two-cell row has no user-text column at all (upstream only stores it for `Count >= 3`);
        // a three-cell row keeps it, trimmed.
        assertTrue("userText" !in row)
        assertEquals("Some user text", loaded.recordFor("JA8BMD")?.get("userText"))
        assertTrue(loaded.calls.none { it.startsWith("#") || it.startsWith("!!") })
    }

    // --- their exchange -------------------------------------------------------

    private fun generate(call: String, record: Map<String, String>): GeneratedExchange =
        when (val result = TheirExchangeGenerator(acag, fakeCalls).generate(emptyMap(), record, null)) {
            is GenerationResult.Success -> result.exchange
            is GenerationResult.Failure -> error("'$call': ${result.message}")
        }

    @Test
    fun `a station always sends 599 and its file number, upper-cased, with no parse step`() {
        // Acag.pas:214-231 GetExchange: Exch1 := getExch1 (`:220-223` always `'599'`),
        // Exch2 := the file's Number, which `:85-86` upper-cased at load.
        val station = generate("7K1CPT", mapOf("number" to "1430h"))
        assertEquals("599", station.exch1, "Acag.pas:222 `result := '599'`")
        assertEquals("1430H", station.exch2)
        assertTrue(station.parsed.isEmpty(), "ACAG needs no their.parse (ExtractMultiplier reads Exch2)")

        val leadingZero = generate("7K1AEU/8", mapOf("number" to "01039M"))
        assertEquals("599", leadingZero.exch1)
        assertEquals("01039M", leadingZero.exch2)
    }

    // --- scoring (transcribed from Acag.pas) ----------------------------------

    private fun multipliers(call: String, exch2: String): List<String> {
        val environment = acag.environment(
            "theirCall" to Value.str(call),
            "theirExch1" to Value.str("599"),
            "theirExch2" to Value.str(exch2),
        )
        return acag.scoring.multipliers.mapNotNull { multiplier ->
            if (multiplier.whenExpression != null && !multiplier.whenExpression.flag(environment)) {
                null
            } else {
                multiplier.key.text(environment)
            }
        }
    }

    @Test
    fun `one point per contact, and the multiplier strips only a trailing power letter`() {
        // Acag.pas:207 `Qso^.Points := 1;` — regardless of the code or the power.
        assertEquals(1.0, (acag.scoring.points.value(acag.environment()) as Value.Num).value)

        // Acag.pas:197-204: `S[Length(S)]` in ['L','M','H','P'] → `Copy(S,1,Length(S)-1)`,
        // else the whole string. The expression is ALL JA's, character for character
        // (ADR-0012 §3) — the two documents must agree, and these cases pin both readings.
        assertEquals(listOf("2708"), multipliers("7J3AOZ", "2708M"))
        assertEquals(listOf("100115"), multipliers("7K1BIB", "100115M"))
        assertEquals(listOf("1102"), multipliers("7K1BJR", "1102H"))
        assertEquals(listOf("1217"), multipliers("7K1BPK", "1217L"))
        // The leading zero survives: the multiplier is a string, never a number.
        assertEquals(listOf("01039"), multipliers("7K1AEU/8", "01039M"))
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
        // record in 10 705 cannot cost ACAG anything: every row scores its code.
        val environment = acag.environment(
            "theirCall" to Value.str("7K1BIB"),
            "theirExch1" to Value.str("599"),
            "theirExch2" to Value.str("100115M"),
        )
        val result = acag.scoring.multipliers.single().key.evaluate(environment, DEFAULT_STEP_BUDGET, fakeCalls)
        assertIs<EvalResult.Success>(result, "the multiplier must not need DXCC data")
        assertEquals(listOf("100115"), multipliers("7K1BIB", "100115M"))
    }

    // --- the roster -----------------------------------------------------------

    private fun loadedHistory(): CallHistoryFile {
        // Sixteen calls for at most six stations: the duplicate retry budget (ten attempts) then
        // has room, so a seed cannot exhaust it the way a pool exactly as large as the roster does
        // (the T10/T11 fixture lesson, `.scratch/progress.md` §31/§32).
        val text = listOf(
            "# Call, Exch1, UserText,",
            "7J3AOZ,2708M",
            "7K1AEU/8,01039M",
            "7K1BIB,100115M",
            "7K1BJR,1102H",
            "7K1BPK,1217L",
            "7K1CPT,1430H",
            "7K1CRO,1306H",
            "7K1DPE,1338M",
            "JA1ABC,1001L",
            "JA2BCD,13002M",
            "JA4GBD/8,112M",
            "JA5DEF,4303H",
            "JA8BMD,10011M",
            "JH1JKL,1201M",
            "JL1MNO,1402H",
            "JQ1ABC,1005L",
        ).joinToString("\n")
        return assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, acag.callHistory!!)).history
    }

    private fun roster(seed: Long): StationRoster = StationRoster(
        config = acag,
        pool = loadedHistory(),
        history = loadedHistory(),
        settings = RosterSettings(speedWpm = 15, maxStations = 6),
        seed = seed,
        calls = fakeCalls,
    )

    @Test
    fun `the roster generates 599 and the file's number for every station, over many seeds`() {
        val history = loadedHistory()
        var longCodes = 0
        var shortCodes = 0
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
                if (station.exchange.exch2.length > 5) longCodes++ else shortCodes++
            }
        }
        assertTrue(longCodes > 0, "no gun/ku-coded station was generated")
        assertTrue(shortCodes > 0, "no city-coded station was generated")
    }

    @Test
    fun `the same seed and settings replay the same roster`() {
        fun rosterText(seed: Long): List<String> = roster(seed).let { r ->
            repeat(6) { r.add() }
            r.stations.map { "${it.call}|${it.speedWpm}|${it.pitchHz}|${it.exchange.exch1}|${it.exchange.exch2}" }
        }
        assertEquals(rosterText(7L), rosterText(7L), "the ACAG roster is not deterministic")
        assertTrue(rosterText(7L).isNotEmpty())
    }

    @Test
    fun `a station whose exchange the file cannot describe is skipped, not invented`() {
        val history = loadedHistory()
        val roster = StationRoster(
            config = acag,
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
