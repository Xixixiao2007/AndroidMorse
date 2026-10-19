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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **T15's behaviour checks for CQ WPX** — the sixth per-contest ticket, and the first from
 * `T10–T20` that is not a document alone (spec §11).
 *
 * Every claim below is transcribed from `upstream/MorseRunnerCE/CqWpx.pas` (MRCE 1.85.4),
 * `Ini.pas`, `ExchFields.pas`, `Station.pas` and `Log.pas`, with `file:line`. The config is
 * loaded through the real loader, generated through the real roster and scored through the
 * config's own expressions.
 *
 * Two firsts in the series:
 *
 *  - **no call history at all.** `TCqWpx.LoadCallHistory` (`CqWpx.pas:87-97`) loads
 *    `MASTER.DTA` through `TCallList.LoadCallList` (`CallLst.pas:60-61`) as the *pool* only;
 *    there is no contest history to package, so `schema` v1's optional `callHistory` (T15)
 *    and the app's `MASTER.DTA` pool are the faithful shapes. Inventing a table would be
 *    inventing data.
 *  - **the first contest that sends a running serial**, which is why F8 exists: the document
 *    declares the serial field, and the engine (`ContestRules.operatorExchange(serial)`)
 *    supplies the number. `CqwpxRulesTest` proves the arithmetic; this test proves the
 *    document.
 */
class CqwpxTest {

    /** WPX's only lookup is `callPrefix(theirCall)`; the values are upstream's golden cases. */
    private val fakeCalls: CallFacts = object : CallFacts {
        private val prefixes = mapOf(
            "DL2XYZ" to "DL2",
            "W7SST/6" to "W6",
            "DL/AA1ZZ" to "DL0",
            "F4ABC" to "F4",
        )

        override fun prefix(call: String): String? = prefixes[call]
        override fun zone(call: String): Double? = null
        override fun country(call: String): String? = null
        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }

    private fun config(): ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("cqwpx"), "cqwpx did not load").config

    private val cqwpx: ContestConfig by lazy { config() }

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
    fun `CQ WPX ships as a schema-v1 document and the manifest names it`() {
        assertTrue("cqwpx" in ContestLibrary.manifestIds(), "the manifest does not list cqwpx")
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        assertEquals(
            listOf("cqww", "arrlss", "iaruhf", "naqp", "k1usnsst", "allja", "acag", "cwops", "cqwpx", "hst", "arrldx"),
            bundled.configs.map { it.id },
        )
        assertEquals(ContestConfig.SUPPORTED_SCHEMA_VERSION, cqwpx.schemaVersion)
        assertEquals("cqwpx", cqwpx.id)
        // Ini.pas:96, the contest's own name — "CQ WPX".
        assertEquals("CQ WPX", cqwpx.name)
        assertEquals(listOf("single-call", "pile-up"), cqwpx.runModes)
    }

    @Test
    fun `CQ WPX is the first contest in the series with no call history`() {
        // CqWpx.pas:87-97 `LoadCallHistory` → `CallLst.LoadCallList`, which reads MASTER.DTA
        // (`CallLst.pas:60-61`) as the station pool. There is no contest file to parse, so the
        // document declares none and the pool stays MASTER.DTA (`station.callPool`).
        assertNull(cqwpx.callHistory, "CQ WPX has no call-history file (T15 made the key optional)")
        assertEquals("MASTER.DTA", cqwpx.station.callPool)
    }

    @Test
    fun `CQ WPX declares no Farnsworth and no DXCC gate, as the Pascal does`() {
        // TCqWpx.Create (`CqWpx.pas:100-107`) builds `CallLst` and `SerialNRGen` and sets
        // neither `BFarnsworthEnabled` nor a DXCC requirement; `LoadCallHistory` ignores the
        // operator call.
        assertTrue(!cqwpx.capabilities.farnsworth, "CqWpx.pas does not enable Farnsworth")
        assertTrue(!cqwpx.capabilities.requiresMyDxcc)
    }

    @Test
    fun `the two exchange fields are the report and a serial`() {
        // Ini.pas:96-103: ExchType1 etRST, ExchType2 etSerialNr, ExchDefault '5NN #',
        // Msg '''RST <serial>'' (e.g. 5NN #|123)'; captions come from
        // ExchFields.pas:44 ('RST') and :53 ('Nr.').
        assertEquals(listOf("rst", "nr"), cqwpx.exchange.myFields.map { it.key })
        assertEquals(FieldType.RST, cqwpx.exchange.myFields[0].type)
        assertEquals(FieldType.SERIAL, cqwpx.exchange.myFields[1].type)
        assertEquals("RST", cqwpx.exchange.myFields[0].label)
        assertEquals("Nr.", cqwpx.exchange.myFields[1].label)
        // Ini.pas:232-238: `SerialNrStartContest` is `MinVal:1; MaxVal:176` with 1..3 digits.
        // The range is the DX station's draw (the operator's own serial only starts at 1).
        assertEquals(1..176, cqwpx.exchange.myFields[1].range)
        assertTrue(cqwpx.exchange.entryParse == null, "two boxes, one field each")
        assertEquals("5NN", cqwpx.station.exchangeDefaults["rst"], "Ini.pas:101 ExchDefault '5NN #'")
        assertEquals("1", cqwpx.station.exchangeDefaults["nr"], "Main.pas:1517-1531 starts NR at 1")
    }

    @Test
    fun `the sent exchange is one piece, the report and a three-digit serial`() {
        // Station.pas:391-394 `Format('%d%.3d', [RST, NR])` — concatenated, NR padded to three
        // digits — then `:427-433` replaces 599 with 5NN. `CqWpx.pas` has no `SendMsg`
        // override, so the base `<#>` wording carries the single piece.
        val sent = assertNotNull(cqwpx.exchange.sent, "CQ WPX needs the concatenation")
        fun render(rst: String, nr: Int): String =
            sent.exch1.text(cqwpx.environment("rst" to Value.str(rst), "nr" to Value.num(nr.toDouble())))

        assertEquals("5NN001", render("5NN", 1))
        assertEquals("5NN012", render("5NN", 12))
        assertEquals("5NN123", render("5NN", 123))
        // `%.3d` is a minimum width: 1234 is sent whole, not truncated.
        assertEquals("5NN1234", render("5NN", 1234))
        assertEquals("", sent.exch2.text(cqwpx.environment("rst" to Value.str("5NN"), "nr" to Value.num(1.0))))
    }

    @Test
    fun `the station sends the literal 599 and its drawn serial`() {
        // CqWpx.pas:299-304 `GetExchange`: `Exch1 := getExch1(...)` = '599' (`:323-326`);
        // `NR := GetNR(station)`; `Exch2 := IntToStr(NR)`.
        val their = cqwpx.exchange.their
        assertEquals("599", their.exch1.text(cqwpx.environment("hisNr" to Value.num(123.0))))
        assertEquals("123", their.exch2.text(cqwpx.environment("hisNr" to Value.num(123.0))))
        assertEquals(listOf("hisNr"), their.bindings.keys.toList())
        assertIs<TheirBinding.Serial>(their.bindings["hisNr"])
        assertTrue(their.parse == null, "CQ WPX parses nothing out of its exchange")
        assertTrue(their.dice.isEmpty(), "no deliberate corruption: the exchange is drawn, not diced")
    }

    @Test
    fun `the duration is the majority clamp, because no artifact states WPX's window`() {
        // `CqWpx.pas` does not override `Ini.Duration`; `Ini.pas:263` is the global default 30
        // and `:267` is the competition timer. ADR-0015 §6 declares the gap instead of
        // writing a 48-hour number from memory.
        assertEquals(Duration(defaultMinutes = 30, minMinutes = 1, maxMinutes = 60), cqwpx.duration)
    }

    @Test
    fun `validation follows the upstream field definitions`() {
        // ExchFields.pas:44 `etRST` R: '[1-5E][1-9N][1-9N]' L: 3;
        // ExchFields.pas:53 `etSerialNr` R: '([0-9OTN]+)|(#)' L: 4. Upstream wraps a field
        // definition as '^(' + R + ')$' (`Contest.pas:310`).
        val rst = cqwpx.validation.first { it.field == "rst" }
        val nr = cqwpx.validation.first { it.field == "nr" }

        fun rstRejected(text: String): Boolean =
            rst.whenExpression.flag(cqwpx.environment("rst" to Value.str(text)))

        fun nrRejected(number: Int): Boolean =
            nr.whenExpression.flag(cqwpx.environment("nr" to Value.num(number.toDouble())))

        assertTrue(!rstRejected("5NN"), "5NN is the default report")
        assertTrue(!rstRejected("599"), "599 is legal too")
        assertTrue(!rstRejected("5nn"), "case is normalised before the test")
        assertTrue(rstRejected("5999"), "an RST is three characters")
        assertTrue(rstRejected("ABC"), "the pattern is [1-5E][1-9N][1-9N]")
        assertTrue(!nrRejected(1), "a serial starts at 1")
        assertTrue(!nrRejected(9999), "four digits fit the box")
        assertTrue(nrRejected(0), "zero is not a serial")
        assertTrue(nrRejected(10_000), "the box is four characters")
    }

    // --- their exchange through the real roster -------------------------------

    private fun roster(seed: Long) = StationRoster(
        config = cqwpx,
        pool = masterDtaOf("DL2XYZ", "F4ABC", "K1ABC", "W1AW", "JA3QRS"),
        history = null,
        settings = RosterSettings(speedWpm = 25),
        seed = seed,
        calls = fakeCalls,
    )

    @Test
    fun `the roster draws a serial in upstream's range, and one seed replays it`() {
        val serials = mutableListOf<Int>()
        for (seed in 1L..20L) {
            val roster = roster(seed)
            // Three of the pool's five calls: enough samples, and far from the ten duplicate
            // attempts `add()` allows before it gives up.
            repeat(3) { roster.add() }
            assertEquals(3, roster.size, "the pool holds five calls")
            for (station in roster.stations) {
                val pieces = listOf(station.exchange.exch1, station.exchange.exch2)
                assertEquals("599", pieces[0], "${station.call} must send the literal 599")
                val serial = pieces[1].toInt()
                assertTrue(serial in 1..176, "${station.call}: serial $serial outside Ini's range")
                assertTrue(station.exchange.parsed.isEmpty(), "no parse groups for WPX")
                serials += serial
            }
        }
        assertTrue(serials.toSet().size > 5, "the serial draw does not vary: ${serials.toSet()}")

        // ADR-0007 §6: the same seed regenerates the same stations, serial included.
        val first = roster(7L).add()
        val again = roster(7L).add()
        assertEquals(assertNotNull(first).exchange, assertNotNull(again).exchange)
    }

    // --- scoring --------------------------------------------------------------

    @Test
    fun `scoring is one point and the worked call's WPX prefix`() {
        // CqWpx.pas:285-291 `Qso.Points := 1; Result := Qso.Pfx`; `Log.pas:715`
        // `Qso.Pfx := ExtractPrefix(Qso.Call)` with the WPX mode of `CallsignUtils.pas:58-133`.
        assertEquals(Value.num(1.0), cqwpx.scoring.points.value(cqwpx.environment()))
        val multiplier = cqwpx.scoring.multipliers.single().key
        fun prefix(call: String): String = multiplier.text(cqwpx.environment("theirCall" to Value.str(call)))

        assertEquals("DL2", prefix("DL2XYZ"))
        assertEquals("F4", prefix("F4ABC"))
        // Upstream's own golden cases: a district override and a portable prefix.
        assertEquals("W6", prefix("W7SST/6"))
        assertEquals("DL0", prefix("DL/AA1ZZ"))
    }

    @Test
    fun `CQ WPX overrides no wording`() {
        // `CqWpx.pas` has no `SendMsg` override, so `messages` is empty and every message
        // falls through to `Contest.pas:412-450`'s base wording.
        assertTrue(cqwpx.messages.isEmpty(), "CQ WPX declares no wording overrides: ${cqwpx.messages}")
    }
}
