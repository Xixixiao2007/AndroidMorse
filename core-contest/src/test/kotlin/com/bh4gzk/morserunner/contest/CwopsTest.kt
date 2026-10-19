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
 * **T14's behaviour checks for CWops CWT** — the fifth per-contest ticket (spec §11).
 *
 * Every claim below is transcribed from `upstream/MorseRunnerCE/CwOps.pas` (MRCE 1.85.4),
 * `Ini.pas`, `ExchFields.pas` and `Log.pas`, with `file:line`. The config is loaded through
 * the real loader, generated through the real roster and scored through the config's own
 * expressions, so this is a behaviour check of the document, not of a fixture.
 *
 * CWT is the series' **second header-driven** call history (`IARU HF` was the first): the
 * `!!Order!!,Call,Name,Exch1,UserText,` line names the four columns (`CwOps.pas:52-56`), and
 * the marker line must therefore **not** be a skip prefix — it is the header itself. Two more
 * firsts: the second sent piece mixes three kinds of value (a CWT member number, a state or a
 * country prefix) and the multiplier is the **worked call**, not the exchange.
 *
 * The DXCC provider is **fake/unavailable on purpose**: CWT's rules never ask DXCC a fact
 * (`CwOps.pas:245-249`), which is itself worth pinning.
 */
class CwopsTest {

    /** CWT asks DXCC nothing; an unavailable provider is the honest stand-in. */
    private val fakeCalls: CallFacts = CallFacts.Unavailable

    private fun config(): ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("cwops"), "cwops did not load").config

    private val cwops: ContestConfig by lazy { config() }

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
    fun `CWT ships as a schema-v1 document and the manifest names it`() {
        assertTrue("cwops" in ContestLibrary.manifestIds(), "the manifest does not list cwops")
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        assertEquals(
            listOf("cqww", "arrlss", "iaruhf", "naqp", "k1usnsst", "allja", "acag", "cwops", "cqwpx"),
            bundled.configs.map { it.id },
        )
        assertEquals(ContestConfig.SUPPORTED_SCHEMA_VERSION, cwops.schemaVersion)
        assertEquals("cwops", cwops.id)
        // Ini.pas:106, the contest's own name — "CWOPS CWT", not "CWops".
        assertEquals("CWOPS CWT", cwops.name)
        assertEquals(listOf("single-call", "pile-up"), cwops.runModes)
    }

    @Test
    fun `CWT declares no Farnsworth and no DXCC gate, as the Pascal does`() {
        // TCWOPS.Create (`CwOps.pas:113-118`) never sets `BFarnsworthEnabled`, and
        // LoadCallHistory (`:50-120`) ignores the operator call entirely.
        assertTrue(!cwops.capabilities.farnsworth, "CwOps.pas does not enable Farnsworth")
        assertTrue(!cwops.capabilities.requiresMyDxcc)
    }

    @Test
    fun `the two exchange fields are the operator name and the generic exchange`() {
        // Ini.pas:106-114: ExchType1 etOpName, ExchType2 etGenericField,
        // ExchCaptions ('Name', 'Exch'), ExchDefault 'DAVID 123',
        // Msg '''<op name> <CWOPS Number|State|Country>'' (e.g. DAVID 123)'.
        assertEquals(listOf("name", "exch"), cwops.exchange.myFields.map { it.key })
        assertEquals(FieldType.TEXT, cwops.exchange.myFields[0].type)
        assertEquals(FieldType.TEXT, cwops.exchange.myFields[1].type)
        assertEquals("Name", cwops.exchange.myFields[0].label)
        assertEquals("Exch", cwops.exchange.myFields[1].label)
        assertTrue(cwops.exchange.entryParse == null, "two boxes, one field each")
        assertTrue(cwops.exchange.sent == null, "the pieces are the fields themselves")
        assertEquals("DAVID", cwops.station.exchangeDefaults["name"], "Ini.pas:112 ExchDefault 'DAVID 123'")
        assertEquals("123", cwops.station.exchangeDefaults["exch"])
    }

    @Test
    fun `the duration is the majority clamp, because no artifact states CWT's window`() {
        // `CwOps.pas` does not override `Ini.Duration`; `Ini.pas:263` is the global default 30.
        // ADR-0014 §5 declares the gap instead of guessing a CWT window.
        assertEquals(Duration(defaultMinutes = 30, minMinutes = 1, maxMinutes = 60), cwops.duration)
    }

    @Test
    fun `validation follows the upstream field definitions`() {
        // ExchFields.pas:45 `etOpName` R: '[A-Z][A-Z]*' L: 10;
        // ExchFields.pas:54 `etGenericField` R: '[0-9A-Z]*' L: 12.
        // Upstream wraps a field definition as '^(' + R + ')$' (`Contest.pas:310`).
        val name = cwops.validation.first { it.field == "name" }
        val exch = cwops.validation.first { it.field == "exch" }

        fun rejected(rule: ValidationRule, field: String, text: String): Boolean =
            rule.whenExpression.flag(cwops.environment(field to Value.str(text)))

        assertTrue(!rejected(name, "name", "DAVID"), "the default name is accepted")
        assertTrue(!rejected(name, "name", "david"), "case is normalised before the test")
        assertTrue(rejected(name, "name", ""), "a name needs at least one letter")
        assertTrue(rejected(name, "name", "DAVID2"), "a name is letters only")
        assertTrue(rejected(name, "name", "CHRISTOPHER"), "a name is up to ten characters")
        assertTrue(!rejected(name, "name", "CHRISTIAN"), "nine characters still fit")

        assertTrue(!rejected(exch, "exch", "123"), "a CWT member number is accepted")
        assertTrue(!rejected(exch, "exch", "FL"), "a state is accepted")
        assertTrue(!rejected(exch, "exch", "4O"), "a country prefix is accepted")
        // `[0-9A-Z]*` matches the empty string, so upstream's own validator accepts a blank
        // second box; the port keeps that fidelity rather than inventing a rule.
        assertTrue(!rejected(exch, "exch", ""), "the empty string matches [0-9A-Z]*")
        assertTrue(rejected(exch, "exch", "12-3"), "a hyphen is not in the character class")
        assertTrue(rejected(exch, "exch", "ABCDEFGHIJKLM"), "the box is twelve characters")
        assertTrue(!rejected(exch, "exch", "ABCDEFGHIJKL"), "twelve characters still fit")
    }

    // --- the call history -----------------------------------------------------

    @Test
    fun `the call history is header-driven, as the file's own !!Order!! line says`() {
        // CwOps.pas:52-56: the header names Call, Name, Exch1, UserText; `:82` keeps a row with
        // `tl.Count >= 3`; `:86-95` uppercase the call/name/exch, require all three, and drop a
        // name longer than 10 or an exchange longer than 5. IARU_HF.txt is the only other
        // header-driven history (ADR-0008), so the marker must **not** be a skip prefix.
        val history = cwops.callHistory!!
        assertEquals("CWOPS.LIST", history.asset, "CwOps.pas:91 loads its own file, not MASTER.DTA")
        assertEquals(',', history.delimiter)
        assertEquals(listOf("#"), history.skipPrefixes, "the !!Order!! line is the header, not a skip")
        assertEquals(3, history.minFields, "`if (tl.Count >= 3)` is the row rule")
        assertNotNull(history.header)
        assertEquals("!!Order!!", history.header!!.marker)
        assertEquals(
            mapOf("call" to "Call", "name" to "Name", "exch" to "Exch1", "userText" to "UserText"),
            history.header!!.names,
            "the file's own column labels (the header's trailing comma is harmless)",
        )
        assertEquals(listOf("call", "name", "exch", "userText"), history.columns.map { it.name })
        assertEquals(listOf(true, true, true, false), history.columns.map { it.required })
        assertTrue(history.columns.all { it.index == null }, "a header-driven column has no index")
        assertTrue(!history.dedupe, "upstream keeps every row")
        assertTrue(!history.requiresMyCallInDxcc, "LoadCallHistory ignores the operator call")
    }

    @Test
    fun `the call-history parser binds by the header's own labels`() {
        // A header whose columns are in a different order proves the binding is by name:
        // nothing in the port depends on the Call/Name/Exch1 positions.
        val text = """
            !!Order!!,Name,Call,UserText,Exch1,
            # CWOPS
            DAVID,K1ABC,Somewhere MA,123
            SCOTT,W1AL,Cape Coral FL,FL
        """.trimIndent()
        val loaded = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(text, cwops.callHistory!!),
            "the CWT history was rejected",
        ).history
        val row = assertNotNull(loaded.recordFor("K1ABC"))
        assertEquals("K1ABC", row["call"])
        assertEquals("DAVID", row["name"])
        assertEquals("123", row["exch"])
        assertEquals("Somewhere MA", row["userText"])
        assertEquals("FL", loaded.recordFor("W1AL")?.get("exch"))
    }

    @Test
    fun `the parser drops the rows upstream drops and keeps an empty user text as a value`() {
        // CwOps.pas:82-95: fewer than three cells (`W8UE/4,Ted`), an empty call, an empty name
        // or an empty exchange all `continue`. The fourth cell is optional user text.
        val text = """
            !!Order!!,Call,Name,Exch1,UserText,
            # CWOPS
            K1ABC,DAVID,123,
            4U1ITU,,4U1ITU,Geneva Switzerland
            IZ3IRJ,Al,
            W8UE/4,Ted
            K2DEF,ANN,ND,Somewhere ND
            K3GHI,BOB,1343,Somewhere
        """.trimIndent()
        val loaded = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(text, cwops.callHistory!!),
            "the CWT history was rejected",
        ).history

        assertEquals(listOf("K1ABC", "K2DEF", "K3GHI"), loaded.calls.toList())
        // A cell that exists but is empty is a value for an optional TEXT column (the same
        // NAQP rule): the row keeps `userText = ""`.
        assertEquals("", loaded.recordFor("K1ABC")?.get("userText"))
        assertEquals("Somewhere ND", loaded.recordFor("K2DEF")?.get("userText"))
        assertTrue(loaded.calls.none { it.startsWith("#") || it.startsWith("!!") })
    }

    // --- their exchange -------------------------------------------------------

    private fun generate(call: String, record: Map<String, String>): GeneratedExchange =
        when (val result = TheirExchangeGenerator(cwops, fakeCalls).generate(emptyMap(), record, null)) {
            is GenerationResult.Success -> result.exchange
            is GenerationResult.Failure -> error("'$call': ${result.message}")
        }

    @Test
    fun `a station sends its name and the file's exchange column, upper-cased, with no parse`() {
        // CwOps.pas:168-173 GetExchange: Exch1 := the record's Exch1 (the file's Name column),
        // Exch2 := the record's Exch2 (the file's Exch1 column); `:86-88` upper-cased both at load.
        val member = generate("3B9FR", mapOf("name" to "Robert", "exch" to "1343"))
        assertEquals("ROBERT", member.exch1, "the name is the first sent piece")
        assertEquals("1343", member.exch2, "a CWT member number is the second piece")
        assertTrue(member.parsed.isEmpty(), "CWT needs no their.parse (the multiplier is the call)")

        val nonMember = generate("4O3A", mapOf("name" to "Ranko", "exch" to "4O"))
        assertEquals("RANKO", nonMember.exch1)
        assertEquals("4O", nonMember.exch2, "a country prefix is the same field")
    }

    // --- scoring (transcribed from CwOps.pas) ---------------------------------

    private fun multipliers(call: String, exch2: String): List<String> {
        val environment = cwops.environment(
            "theirCall" to Value.str(call),
            "theirExch1" to Value.str("ROBERT"),
            "theirExch2" to Value.str(exch2),
        )
        return cwops.scoring.multipliers.mapNotNull { multiplier ->
            if (multiplier.whenExpression != null && !multiplier.whenExpression.flag(environment)) {
                null
            } else {
                multiplier.key.text(environment)
            }
        }
    }

    @Test
    fun `one point per contact and the multiplier is the worked call itself`() {
        // CwOps.pas:245-249: `Qso^.Points := 1; Result:= Qso^.Call;` — the "unique calls" count
        // is the log's own set union, so the document's key is `theirCall`.
        assertEquals(1.0, (cwops.scoring.points.value(cwops.environment()) as Value.Num).value)

        assertEquals(listOf("3B9FR"), multipliers("3B9FR", "1343"))
        assertEquals(listOf("4O3A"), multipliers("4O3A", "4O"))
        assertEquals(listOf("N5OT"), multipliers("N5OT", "ND"))
        // The exchange plays no part: two stations in the same state are two multipliers.
        assertEquals(listOf("K1ABC"), multipliers("K1ABC", "ND"))
        assertEquals(listOf("K2DEF"), multipliers("K2DEF", "ND"))
    }

    @Test
    fun `the document never asks the DXCC table for a fact`() {
        // No `call*` built-in appears in scoring, so no row can fail closed on a missing
        // DXCC fact the way SST's one-in-10 705 does (ADR-0011 §3).
        val environment = cwops.environment(
            "theirCall" to Value.str("3B9FR"),
            "theirExch1" to Value.str("ROBERT"),
            "theirExch2" to Value.str("1343"),
        )
        assertIs<EvalResult.Success>(
            cwops.scoring.multipliers.single().key.evaluate(environment, DEFAULT_STEP_BUDGET, fakeCalls),
            "the multiplier must not need DXCC data",
        )
    }

    // --- the roster -----------------------------------------------------------

    private fun loadedHistory(): CallHistoryFile {
        // Sixteen calls for at most six stations: the duplicate retry budget has room, so a
        // seed cannot exhaust it (the T10/T11 fixture lesson, `.scratch/progress.md` §31/§32).
        // Alternate member numbers and state/country prefixes, so both branches are generated.
        val text = """
            !!Order!!,Call,Name,Exch1,UserText,
            # CWOPS fixture
            2E0DCW,Gerald,G,London England
            3B9FR,Robert,1343,Rodriguez Island
            4O3A,Ranko,4O,Montenegro
            9A1A,Krešo,9A,Croatia
            K1ABC,David,123,Somewhere MA
            K2DEF,Ann,ND,Somewhere ND
            K3GHI,Bob,1343,Somewhere PA
            N5OT,Mark,ND,Bartlesville OK
            W1AL,Scott,FL,Cape Coral FL
            W2BCD,Marie,NY,Somewhere NY
            W3EFG,Peter,123,Somewhere MD
            JA1ABC,Taro,123,Tokyo
            DL2XYZ,Karl,DL,Berlin
            G3ABC,Nigel,G,London
            F5XYZ,Pierre,F,Paris
            VK2ABC,Rob,VK,Sydney
        """.trimIndent()
        return assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, cwops.callHistory!!)).history
    }

    private fun roster(seed: Long): StationRoster = StationRoster(
        config = cwops,
        pool = loadedHistory(),
        history = loadedHistory(),
        settings = RosterSettings(speedWpm = 15, maxStations = 6),
        seed = seed,
        calls = fakeCalls,
    )

    @Test
    fun `the roster sends each station's name and file exchange, over many seeds`() {
        val history = loadedHistory()
        var members = 0
        var nonMembers = 0
        for (seed in 1L..25L) {
            val roster = roster(seed)
            repeat(6) { roster.add() }
            roster.stations.forEach { station ->
                val record = assertNotNull(history.recordFor(station.call), "invented '${station.call}'")
                assertEquals(record["name"]?.uppercase(), station.exchange.exch1, "seed $seed: ${station.call}")
                assertEquals(record["exch"]?.uppercase(), station.exchange.exch2, "seed $seed: ${station.call}")
                if (station.exchange.exch2.all { it in '0'..'9' }) members++ else nonMembers++
            }
        }
        assertTrue(members > 0, "no CWT member number was generated")
        assertTrue(nonMembers > 0, "no state/country prefix was generated")
    }

    @Test
    fun `the same seed and settings replay the same roster`() {
        fun rosterText(seed: Long): List<String> = roster(seed).let { r ->
            repeat(6) { r.add() }
            r.stations.map { "${it.call}|${it.speedWpm}|${it.pitchHz}|${it.exchange.exch1}|${it.exchange.exch2}" }
        }
        assertEquals(rosterText(11L), rosterText(11L), "the CWT roster is not deterministic")
        assertTrue(rosterText(11L).isNotEmpty())
    }

    @Test
    fun `a station whose exchange the file cannot describe is skipped, not invented`() {
        val history = loadedHistory()
        val roster = StationRoster(
            config = cwops,
            pool = history,
            history = history,
            settings = RosterSettings(speedWpm = 15, maxStations = 2),
            seed = 4L,
            calls = fakeCalls,
        )
        roster.add()
        roster.add()
        assertEquals(2, roster.size)
        roster.stations.forEach { assertTrue(history.recordFor(it.call) != null, it.call) }
    }
}
