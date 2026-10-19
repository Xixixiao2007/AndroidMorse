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
 * **T11's behaviour checks for the K1USN Slow Speed Test** — the second per-contest ticket
 * (spec §11).
 *
 * Every claim below is transcribed from `upstream/MorseRunnerCE/CWSST.pas` (MRCE 1.85.4),
 * `ExchFields.pas` and `Ini.pas`, with `file:line`. The config is loaded through the real
 * loader, generated through the real roster and scored through the config's own expressions,
 * so this is a behaviour check of the document, not of a fixture.
 *
 * The DXCC provider is **fake test data**; the real asset is `DXCC.LIST` (spec §9, T11's
 * `PackagedAssetsTest` runs the shipped file through the real table).
 */
class SstTest {

    // --- fake callsign data (test fixture, not a DXCC.LIST conversion) ---------

    private val fakeCalls: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null

        override fun country(call: String): String? = when {
            call.startsWith("DL") -> "Fed. Rep. of Germany"
            call.startsWith("JA") -> "Japan"
            call.startsWith("G") -> "England"
            call.startsWith("VE") -> "Canada"
            call.startsWith("K") || call.startsWith("W") || call.startsWith("N") -> "United States of America"
            else -> null
        }

        override fun continent(call: String): String? = when (country(call)) {
            "Japan" -> "AS"
            "Fed. Rep. of Germany", "England" -> "EU"
            "Canada", "United States of America" -> "NA"
            else -> null
        }

        override fun arrlSection(call: String): String? = null
    }

    private fun config(): ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("k1usnsst"), "k1usnsst did not load").config

    private val sst: ContestConfig by lazy { config() }

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
    fun `SST ships as a schema-v1 document and the manifest names it`() {
        assertTrue("k1usnsst" in ContestLibrary.manifestIds(), "the manifest does not list k1usnsst")
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        assertEquals(
            listOf("cqww", "arrlss", "iaruhf", "naqp", "k1usnsst", "allja", "acag", "cwops", "cqwpx", "hst", "arrldx"),
            bundled.configs.map { it.id },
        )
        assertEquals(ContestConfig.SUPPORTED_SCHEMA_VERSION, sst.schemaVersion)
        assertEquals("k1usnsst", sst.id)
        // Ini.pas:166-176, the contest's own name.
        assertEquals("K1USN Slow Speed Test", sst.name)
        assertEquals(listOf("single-call", "pile-up"), sst.runModes)
    }

    @Test
    fun `SST declares Farnsworth and no DXCC gate, as the Pascal does`() {
        // CWSST.pas:121 `BFarnsworthEnabled := true`; CWSST.pas declares no DXCC requirement
        // (its LoadCallHistory takes the operator call and ignores it, `:52-113`).
        assertTrue(sst.capabilities.farnsworth, "CWSST.pas:121 enables Farnsworth for SST")
        assertTrue(!sst.capabilities.requiresMyDxcc)
    }

    @Test
    fun `the two exchange fields are the operator name and the QTH, with upstream's defaults`() {
        // Ini.pas:166-176: ExchType1 etOpName, ExchType2 etGenericField,
        // ExchCaptions ('Name', 'State/Prov/DX'), ExchDefault 'BRUCE MA'.
        assertEquals(listOf("name", "qth"), sst.exchange.myFields.map { it.key })
        assertEquals(FieldType.TEXT, sst.exchange.myFields[0].type)
        assertEquals(FieldType.TEXT, sst.exchange.myFields[1].type)
        assertEquals("Name", sst.exchange.myFields[0].label)
        assertEquals("State/Prov/DX", sst.exchange.myFields[1].label)
        assertTrue(sst.exchange.entryParse == null, "two boxes, one field each")
        assertTrue(sst.exchange.sent == null, "the pieces are the fields themselves")
        assertEquals("BRUCE", sst.station.exchangeDefaults["name"], "Ini.pas:172 ExchDefault 'BRUCE MA'")
        assertEquals("MA", sst.station.exchangeDefaults["qth"])
    }

    @Test
    fun `validation follows the upstream field definitions`() {
        // ExchFields.pas:45 `etOpName` R: '[A-Z][A-Z]*' L: 10;
        // ExchFields.pas:54 `etGenericField` R: '[0-9A-Z]*' L: 12.
        val name = sst.validation.first { it.field == "name" }
        val qth = sst.validation.first { it.field == "qth" }

        fun rejected(rule: ValidationRule, field: String, text: String): Boolean =
            rule.whenExpression.flag(sst.environment(field to Value.str(text)))

        assertTrue(!rejected(name, "name", "BRUCE"), "a name is accepted")
        assertTrue(!rejected(name, "name", "bruce"), "lower case is upper-cased before the test")
        assertTrue(rejected(name, "name", ""), "an empty name is rejected")
        assertTrue(rejected(name, "name", "BRUCE LEE"), "a space is not part of a name")
        assertTrue(rejected(name, "name", "B".repeat(11)), "longer than 10 is rejected")
        assertTrue(rejected(name, "name", "1BRUCE"), "a name starts with a letter")

        assertTrue(!rejected(qth, "qth", "MA"), "a state is accepted")
        assertTrue(!rejected(qth, "qth", "ON"), "a province is accepted")
        assertTrue(!rejected(qth, "qth", "DX"), "a DX station sends the literal DX")
        assertTrue(!rejected(qth, "qth", "DL"), "a country prefix is accepted")
        assertTrue(rejected(qth, "qth", "MASSACHUSETTS"), "longer than 12 is rejected")
        assertTrue(rejected(qth, "qth", "MA!"), "'!' is not part of the generic field")
    }

    // --- the call history -----------------------------------------------------

    @Test
    fun `the call history is the upstream file shape`() {
        // CWSST.pas:52-110: comma, skips '!!Order!!' and '#', needs tl.Count >= 3,
        // columns call,name,exch1[,userText] = call,name,QTH; call/name/QTH all required.
        val history = sst.callHistory!!
        assertEquals("K1USNSST.txt", history.asset)
        assertEquals(',', history.delimiter)
        assertEquals(listOf("!!Order!!", "#"), history.skipPrefixes)
        assertEquals(3, history.minFields)
        assertEquals(listOf("call", "name", "qth", "userText"), history.columns.map { it.name })
        assertEquals(listOf(true, true, true, false), history.columns.map { it.required })
        assertTrue(!history.dedupe, "upstream keeps every row")
        assertTrue(!history.requiresMyCallInDxcc, "LoadCallHistory ignores the operator call")
        assertTrue(history.header == null, "the !!Order!! line is a skip prefix, not a header")
    }

    @Test
    fun `the call-history parser keeps a DX row and drops a row without a name`() {
        // CWSST.pas:84-97 is the whole rule table: `tl.Count >= 3`, then continue on an
        // empty Call / Exch1 (the name) / Exch2 (the QTH).
        val text = """
            !!Order!!,Call,Name,Exch1,UserText
            #
            AA0EN,,MO,
            K1ABC,MIKE,MA,
            W2XYZ,DAVE,NJ,
            VE3ABC,BRUCE,ON,
            DL1ABC,ZVONKO,DX,
            2E0GKC,PAUL,DX,Osfordshie England
            K2DEF,JOSE
        """.trimIndent()
        val loaded = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(text, sst.callHistory!!),
            "the SST history was rejected",
        ).history

        // `AA0EN` has no operator name and `K2DEF` has no QTH column at all; both are skipped.
        assertEquals(listOf("K1ABC", "W2XYZ", "VE3ABC", "DL1ABC", "2E0GKC"), loaded.calls.toList())
        val k1 = assertNotNull(loaded.recordFor("K1ABC"))
        assertEquals("K1ABC", k1["call"])
        assertEquals("MIKE", k1["name"])
        assertEquals("MA", k1["qth"])
        // A DX station's QTH is the literal 'DX' in the file (`CWSST.pas:90`).
        assertEquals("DX", loaded.recordFor("DL1ABC")?.get("qth"))
        // The 4-field row keeps its user text (upstream stores it, `:91-92`); we do not use it.
        assertEquals("Osfordshie England", loaded.recordFor("2E0GKC")?.get("userText"))
        assertTrue(loaded.calls.none { it.startsWith("#") || it.startsWith("!!") })
    }

    // --- their exchange -------------------------------------------------------

    private fun generate(call: String, record: Map<String, String>): GeneratedExchange =
        when (val result = TheirExchangeGenerator(sst, fakeCalls).generate(emptyMap(), record, null)) {
            is GenerationResult.Success -> result.exchange
            is GenerationResult.Failure -> error("'$call': ${result.message}")
        }

    @Test
    fun `a station sends its name and its QTH, upper-cased, with no parse step`() {
        // CWSST.pas:172-177 GetExchange: Exch1 = the file's Name, Exch2 = the file's Exch1
        // (the QTH); both are upper-cased at load (`:88-90`).
        val local = generate("K1ABC", mapOf("name" to "Mike", "qth" to "ma"))
        assertEquals("MIKE", local.exch1)
        assertEquals("MA", local.exch2)
        assertTrue(local.parsed.isEmpty(), "SST needs no their.parse (ExtractMultiplier reads Exch2)")

        val dx = generate("DL1ABC", mapOf("name" to "ZVONKO", "qth" to "DX"))
        assertEquals("ZVONKO", dx.exch1)
        assertEquals("DX", dx.exch2, "a DX station sends the literal 'DX', not an entity")
    }

    // --- scoring (transcribed from CWSST.pas) ---------------------------------

    private fun multipliers(call: String, exch2: String): List<String> {
        val environment = sst.environment(
            "theirCall" to Value.str(call),
            "theirExch1" to Value.str("BRUCE"),
            "theirExch2" to Value.str(exch2),
        )
        return sst.scoring.multipliers.mapNotNull { multiplier ->
            if (multiplier.whenExpression != null && !multiplier.whenExpression.flag(environment)) {
                null
            } else {
                multiplier.key.text(environment)
            }
        }
    }

    @Test
    fun `one point per contact, and the multiplier is the state or province`() {
        // CWSST.pas:322 `Qso^.Points := 1;` — regardless of QTH.
        assertEquals(1.0, (sst.scoring.points.value(sst.environment()) as Value.Num).value)
        assertEquals(listOf("MA"), multipliers("K1ABC", "MA"))
        assertEquals(listOf("ON"), multipliers("VE3ABC", "ON"), "a Canadian province is a multiplier")
    }

    @Test
    fun `a DX station's multiplier is the DXCC entity, not the literal DX`() {
        // CWSST.pas:326-330: `Result := Qso^.Exch2; if Qso^.TrueExch2.Equals('DX') and
        // gDXCCList.FindRec(dxrec, Qso^.Call) then Result := dxrec.Entity;`
        assertEquals(listOf("Fed. Rep. of Germany"), multipliers("DL1ABC", "DX"))
        assertEquals(listOf("Japan"), multipliers("JA1XYZ", "DX"))
    }

    @Test
    fun `an unknown DX call fails closed instead of keeping the literal DX`() {
        // The port's rule (ADR-0004/ADR-0005): a built-in that has no data fails closed, so
        // the expression cannot silently score the literal 'DX' as a multiplier. Upstream
        // would keep 'DX' (`CWSST.pas:328-330`). `PackagedAssetsTest` measures the price: one
        // of the shipped file's 702 DX records has a call the DXCC table cannot resolve
        // (`LU1AW/D`), so one station in 10 707 scores zero.
        val environment = sst.environment(
            "theirCall" to Value.str("LU1AW/D"),
            "theirExch1" to Value.str("JUAN"),
            "theirExch2" to Value.str("DX"),
        )
        val result = sst.scoring.multipliers.single().key.evaluate(environment, DEFAULT_STEP_BUDGET, fakeCalls)
        assertIs<EvalResult.Failure>(result, "callCountry must fail closed for an unknown call")
    }

    // --- the roster -----------------------------------------------------------

    private fun loadedHistory(): CallHistoryFile {
        // Sixteen calls for at most six stations: the duplicate retry budget (ten attempts) then
        // has room, so a seed cannot exhaust it the way a pool exactly as large as the roster does
        // (the T10 fixture lesson, `.scratch/progress.md` §31).
        val text = listOf(
            "!!Order!!,Call,Name,Exch1,UserText",
            "#",
            "K1ABC,MIKE,MA,",
            "W2XYZ,DAVE,NJ,",
            "VE3ABC,BRUCE,ON,",
            "DL1ABC,ZVONKO,DX,",
            "JA1XYZ,HIRO,DX,",
            "G4ABC,PETER,DX,",
            "K3DEF,ANN,PA,",
            "K4GHI,BOB,OH,",
            "K5JKL,CARL,TX,",
            "K6MNO,DIANE,CA,",
            "K7PQR,ED,WA,",
            "K8STU,FRED,FL,",
            "K9VWX,GINA,NY,",
            "W0YZA,HANK,AZ,",
            "VE1BCD,IVAN,NS,",
            "G0EFG,JACK,DX,",
        ).joinToString("\n")
        return assertIs<HistoryLoad.Loaded>(CallHistoryFile.parse(text, sst.callHistory!!)).history
    }

    private fun roster(seed: Long): StationRoster = StationRoster(
        config = sst,
        pool = loadedHistory(),
        history = loadedHistory(),
        settings = RosterSettings(speedWpm = 15, maxStations = 6),
        seed = seed,
        calls = fakeCalls,
    )

    @Test
    fun `the roster generates the file's name and QTH for every station, over many seeds`() {
        val history = loadedHistory()
        var dx = 0
        var local = 0
        for (seed in 1L..25L) {
            val roster = roster(seed)
            repeat(6) { roster.add() }
            roster.stations.forEach { station ->
                val record = assertNotNull(history.recordFor(station.call), "invented '${station.call}'")
                assertEquals(record["name"]?.uppercase(), station.exchange.exch1, "seed $seed: name of ${station.call}")
                assertEquals(record["qth"]?.uppercase(), station.exchange.exch2, "seed $seed: QTH of ${station.call}")
                if (station.exchange.exch2 == "DX") dx++ else local++
            }
        }
        assertTrue(local > 0, "no station with a state/province was generated")
        assertTrue(dx > 0, "no DX station was generated")
    }

    @Test
    fun `the same seed and settings replay the same roster`() {
        fun rosterText(seed: Long): List<String> = roster(seed).let { r ->
            repeat(6) { r.add() }
            r.stations.map { "${it.call}|${it.speedWpm}|${it.pitchHz}|${it.exchange.exch1}|${it.exchange.exch2}" }
        }
        assertEquals(rosterText(7L), rosterText(7L), "the SST roster is not deterministic")
        assertTrue(rosterText(7L).isNotEmpty())
    }

    @Test
    fun `a station whose exchange the file cannot describe is skipped, not invented`() {
        // The pool is the history itself, so the fail-closed path is the only one available:
        // every generated call must come from the file.
        val history = loadedHistory()
        val roster = StationRoster(
            config = sst,
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
