package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.DEFAULT_STEP_BUDGET
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Expression
import com.bh4gzk.morserunner.expr.Value
import com.bh4gzk.morserunner.morse.MorseWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **T16's behaviour checks for HST (High Speed Test)** — the seventh per-contest ticket, and the
 * first contest whose score is not `points × multipliers` but a **sum of per-contact weights**
 * (spec §11).
 *
 * Every claim is transcribed from `upstream/MorseRunnerCE` (MRCE 1.85.4): `Ini.pas:138-145` for
 * the contest, `Main.pas:505` for the shared `TCqWpx` class, `Station.pas:384-394` for the sent
 * text, `CqWpx.pas:299-326` for the station's exchange, and `Log.pas:586-620` for the score. The
 * config is loaded through the real loader, generated through the real roster, and scored through
 * the document's own expressions.
 *
 * HST's two firsts in the series:
 *
 *  - **the score is the callsign's Morse sending time.** `CallToScore` (`Log.pas:586-597`) turns
 *    a call into an integer; `UpdateStatsHst` (`:600-620`) adds one per contact. The document
 *    expresses the sum as `callWeight(theirCall)` points and one constant multiplier key
 *    (ADR-0016 §4).
 *  - **it reuses another contest's class.** `Main.pas:505` maps `scWpx, scHst` to `TCqWpx`, so
 *    the exchange, the serial and the roster path are CQ WPX's, which T15 already shipped.
 */
class HstTest {

    /** HST scores by the call itself; no `CallFacts` lookup is involved, so nothing is stubbed. */
    private val noCalls: CallFacts = CallFacts.Unavailable

    private fun config(): ContestConfig =
        assertIs<LoadResult.Success>(ContestLibrary.load("hst"), "hst did not load").config

    private val hst: ContestConfig by lazy { config() }

    private fun Expression.value(environment: Map<String, Value>): Value =
        when (val result = evaluate(environment, DEFAULT_STEP_BUDGET, noCalls)) {
            is EvalResult.Success -> result.value
            is EvalResult.Failure -> error("unexpected failure: ${result.error.render()}")
        }

    private fun Expression.text(environment: Map<String, Value>): String =
        assertIs<Value.Str>(value(environment)).value

    private fun Expression.flag(environment: Map<String, Value>): Boolean =
        assertIs<Value.Bool>(value(environment)).value

    private fun Expression.number(environment: Map<String, Value>): Int =
        assertIs<Value.Num>(value(environment)).value.toInt()

    // --- the document ---------------------------------------------------------

    @Test
    fun `HST ships as a schema-v1 document and the manifest names it`() {
        assertTrue("hst" in ContestLibrary.manifestIds(), "the manifest does not list hst")
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        assertEquals(
            listOf(
                "cqww", "arrlss", "iaruhf", "naqp", "k1usnsst", "allja", "acag", "cwops", "cqwpx", "hst", "arrldx",
                "arrlfd",
            ),
            bundled.configs.map { it.id },
        )
        assertEquals(ContestConfig.SUPPORTED_SCHEMA_VERSION, hst.schemaVersion)
        assertEquals("hst", hst.id)
        // Ini.pas:138, the contest's own name — "HST (High Speed Test)".
        assertEquals("HST (High Speed Test)", hst.name)
        assertEquals(listOf("single-call", "pile-up"), hst.runModes)
    }

    @Test
    fun `HST has no call history, because TCqWpx loads only the pool`() {
        // Main.pas:505 maps scHst to TCqWpx; TCqWpx.LoadCallHistory (CqWpx.pas:87-97) reads
        // MASTER.DTA through CallLst (CallLst.pas:60-61) as the station pool. There is no HST
        // history file to package; `HstResults.txt` is an *output* (Main.pas:2210-2230).
        assertNull(hst.callHistory, "HST has no call-history file (ADR-0016 §8.3)")
        assertEquals("MASTER.DTA", hst.station.callPool)
    }

    @Test
    fun `HST declares no Farnsworth and no DXCC gate, as TCqWpx does`() {
        // TCqWpx.Create (CqWpx.pas:100-107) builds CallLst and SerialNRGen and sets neither
        // BFarnsworthEnabled nor a DXCC requirement.
        assertTrue(!hst.capabilities.farnsworth)
        assertTrue(!hst.capabilities.requiresMyDxcc)
    }

    @Test
    fun `the two exchange fields are the report and a serial`() {
        // Ini.pas:138-145: ExchType1 etRST, ExchType2 etSerialNr, ExchFieldEditable False,
        // ExchDefault '5NN #'; captions from ExchFields.pas:44 ('RST') and :53 ('Nr.').
        assertEquals(listOf("rst", "nr"), hst.exchange.myFields.map { it.key })
        assertEquals(FieldType.RST, hst.exchange.myFields[0].type)
        assertEquals(FieldType.SERIAL, hst.exchange.myFields[1].type)
        assertEquals("RST", hst.exchange.myFields[0].label)
        assertEquals("Nr.", hst.exchange.myFields[1].label)
        // Ini.pas:234: the start-of-contest serial setting is MinVal 1, MaxVal 176.
        assertEquals(1..176, hst.exchange.myFields[1].range)
        assertTrue(hst.exchange.entryParse == null, "two boxes, one field each")
        assertEquals("5NN", hst.station.exchangeDefaults["rst"], "Ini.pas:143 ExchDefault '5NN #'")
        // Main.pas:1523-1524 forces `Tst.Me.NR := 1` for HST, whatever the exchange box holds.
        assertEquals("1", hst.station.exchangeDefaults["nr"])
    }

    @Test
    fun `the sent exchange is CQ WPX's 5NN plus a three-digit serial`() {
        // Station.pas:384-394: the `scWpx, scHst` branch is `Format('%d%.3d', [RST, NR])`; the
        // 599→5NN replacement is `:427-433`. CqWpx has no SendMsg override, so the base wording
        // carries the single piece.
        val sent = assertNotNull(hst.exchange.sent)
        fun render(rst: String, nr: Int): String =
            sent.exch1.text(hst.environment("rst" to Value.str(rst), "nr" to Value.num(nr.toDouble())))

        assertEquals("5NN001", render("5NN", 1))
        assertEquals("5NN012", render("5NN", 12))
        assertEquals("5NN123", render("5NN", 123))
        assertEquals("", sent.exch2.text(hst.environment("rst" to Value.str("5NN"), "nr" to Value.num(1.0))))
    }

    @Test
    fun `the station sends the literal 599 and a drawn serial`() {
        // CqWpx.pas:299-304 `GetExchange`; `:323-326` `getExch1` is the literal '599'.
        val their = hst.exchange.their
        assertEquals("599", their.exch1.text(hst.environment("hisNr" to Value.num(123.0))))
        assertEquals("123", their.exch2.text(hst.environment("hisNr" to Value.num(123.0))))
        assertEquals(listOf("hisNr"), their.bindings.keys.toList())
        assertIs<TheirBinding.Serial>(their.bindings["hisNr"])
        assertTrue(their.parse == null)
        assertTrue(their.dice.isEmpty())
    }

    @Test
    fun `the duration is the global clamp, because no artifact states HST's window`() {
        // CqWpx.pas overrides nothing; Ini.pas:263 is the global default 30. ADR-0016 §7 declares
        // the gap instead of writing a competition window from memory.
        assertEquals(Duration(defaultMinutes = 30, minMinutes = 1, maxMinutes = 60), hst.duration)
    }

    @Test
    fun `validation follows the shared upstream field definitions`() {
        // ExchFields.pas:44 etRST R '[1-5E][1-9N][1-9N]' L 3; :53 etSerialNr R '([0-9OTN]+)|(#)'
        // L 4, wrapped as '^(R)$' by Contest.pas:310.
        val rst = hst.validation.first { it.field == "rst" }
        val nr = hst.validation.first { it.field == "nr" }

        fun rstRejected(text: String): Boolean = rst.whenExpression.flag(hst.environment("rst" to Value.str(text)))
        fun nrRejected(number: Int): Boolean =
            nr.whenExpression.flag(hst.environment("nr" to Value.num(number.toDouble())))

        assertTrue(!rstRejected("5NN"), "5NN is the default report")
        assertTrue(!rstRejected("599"))
        assertTrue(!rstRejected("5nn"), "case is normalised before the test")
        assertTrue(rstRejected("5999"), "an RST is three characters")
        assertTrue(rstRejected("ABC"))
        assertTrue(!nrRejected(1), "HST starts at 1 (Main.pas:1523-1524)")
        assertTrue(!nrRejected(9999))
        assertTrue(nrRejected(0))
        assertTrue(nrRejected(10_000))
    }

    // --- their exchange through the real roster -------------------------------

    private fun roster(seed: Long) = StationRoster(
        config = hst,
        pool = masterDtaOf("K1ABC", "W1AW", "JA1ABC", "DL2XYZ", "F4ABC"),
        history = null,
        settings = RosterSettings(speedWpm = 25),
        seed = seed,
        calls = noCalls,
    )

    @Test
    fun `the roster draws a serial in upstream's range, and one seed replays it`() {
        val serials = mutableListOf<Int>()
        for (seed in 1L..20L) {
            val roster = roster(seed)
            // Three of the pool's five calls: enough samples, far from the ten duplicate attempts
            // `add()` allows before giving up.
            repeat(3) { roster.add() }
            assertEquals(3, roster.size)
            for (station in roster.stations) {
                assertEquals("599", station.exchange.exch1, "${station.call} sends the literal 599")
                val serial = station.exchange.exch2.toInt()
                assertTrue(serial in 1..176, "${station.call}: serial $serial outside Ini's range")
                assertTrue(station.exchange.parsed.isEmpty())
                serials += serial
            }
        }
        assertTrue(serials.toSet().size > 5, "the serial draw does not vary: ${serials.toSet()}")

        // ADR-0007 §6: the same seed regenerates the same stations, serial included.
        assertEquals(assertNotNull(roster(7L).add()).exchange, assertNotNull(roster(7L).add()).exchange)
    }

    // --- the score ------------------------------------------------------------

    @Test
    fun `the points are the worked call's Morse weight`() {
        // Log.pas:586-597 `CallToScore`; the arithmetic is pinned in MorseWeightTest. What this
        // test proves is that the *document* asks for it (`callWeight(theirCall)`).
        val points = hst.scoring.points
        fun weight(call: String): Int = points.number(hst.environment("theirCall" to Value.str(call)))

        assertEquals(63, weight("K1ABC"), "K1ABC = 2·8 + 4·10 + 2·4 − 1")
        assertEquals(49, weight("W1AW"))
        assertEquals(75, weight("JA1ABC"))
        assertEquals(99, weight("DL/AA1ZZ"))
        // The same function the module exposes: the document does not re-derive it.
        assertEquals(MorseWeight.of("K1ABC"), weight("K1ABC"))
    }

    @Test
    fun `HST has one constant multiplier, so the product is the sum`() {
        // UpdateStatsHst (Log.pas:600-620) adds one CallToScore per contact; it never multiplies
        // by a multiplier list. The port's score is `points × multipliers`, so a single constant
        // key makes that product the sum (ADR-0016 §4).
        val multiplier = hst.scoring.multipliers.single()
        assertEquals(Value.Str("HST"), multiplier.key.value(hst.environment()))
        assertNull(multiplier.whenExpression, "HST has no conditional multiplier")
        // The key does not depend on the contact: every contact contributes the same one.
        assertEquals(
            multiplier.key.value(hst.environment("theirCall" to Value.str("K1ABC"))),
            multiplier.key.value(hst.environment("theirCall" to Value.str("DL/AA1ZZ"))),
        )
    }

    @Test
    fun `HST overrides no wording`() {
        // CqWpx.pas has no SendMsg override (Main.pas:505 gives HST the same class), so every
        // message falls through to Contest.pas:412-450's base wording.
        assertTrue(hst.messages.isEmpty(), "HST declares no wording overrides: ${hst.messages}")
    }
}
