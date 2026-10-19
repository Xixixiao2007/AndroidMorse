package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.expr.CallFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **T15: CQ WPX driven through the engine** — the sixth per-contest ticket, and the first one
 * whose contest needs engine behaviour rather than only a document (spec §11).
 *
 * Two things this test exists for:
 *
 *  - **F8, the running serial.** Upstream's `Tst.Me.NR` starts at the exchange box's value
 *    (`Main.pas:1320-1325`, `:1517-1531`; `#`/non-numeric → 1) and is incremented after every
 *    logged contact (`Log.pas:766`). `ContestRules.operatorExchange(serial)` is the seam: the
 *    document keeps describing the field, the engine supplies the current number.
 *  - **The sent text is `5NN` + a three-digit serial with no space.** `Station.NrAsText` for
 *    `scWpx` is `Format('%d%.3d', [RST, NR])` (`Station.pas:391-394`), followed by the
 *    `599 → 5NN` replacement (`:427-433`), so my exchange is `5NN001`, not `5NN 1`. The
 *    document expresses that concatenation and the padding with `+`, `len` and `substr`.
 *
 * The multiplier is CQ WPX's `ExtractPrefix` (`CqWpx.pas:285-291`, `Log.pas:715`), which the
 * port already transcribes as `CallPrefix.extractPrefix(deleteTrailingLetters = true)`. The
 * golden values used here are upstream's own (`Test/CallsignUtilsTest.pas`), and this test's
 * fake `CallFacts` pins the *contract* the document asks for (`callPrefix(theirCall)`) rather
 * than re-testing the extractor (`CallPrefixTest` owns that table).
 */
class CqwpxRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    /** The WPX prefixes the scoring expressions see, straight from `CallsignUtilsTest.pas`. */
    private val prefix: CallFacts = object : CallFacts {
        private val prefixes = mapOf(
            "DL2XYZ" to "DL2",
            "DL2ABC" to "DL2",
            "DL3ABC" to "DL3",
            "F4ABC" to "F4",
            "K1ABC" to "K1",
            "W7SST/6" to "W6",
            "DL/AA1ZZ" to "DL0",
        )

        override fun prefix(call: String): String? = prefixes[call]
        override fun zone(call: String): Double? = null
        override fun country(call: String): String? = null
        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }

    private fun wpx() = ContestRules(
        config("cqwpx"),
        // Ini.pas:101 `ExchDefault: '5NN #'` — the `#` is the placeholder for the serial,
        // which upstream initialises to 1 for the start-of-contest mode (`Main.pas:1517-1531`).
        MyStation("W1AW", mapOf("rst" to "5NN", "nr" to "1")),
        prefix,
    )

    private fun entry(call: String = "DL2XYZ", rst: String = "5NN", nr: String = "001") =
        QsoEntry(call, rst, nr)

    private fun truth(call: String = "DL2XYZ", rst: String = "599", nr: String = "1") =
        QsoTruth(call, rst, nr)

    // --- my exchange: the running serial (F8) ----------------------------------

    @Test
    fun `my sent exchange is 5NN plus a three-digit serial and no space`() {
        // Station.pas:391-394 `Format('%d%.3d', [RST, NR])`, then `:427-433` 599 → 5NN.
        assertEquals(OperatorExchange("5NN001", ""), wpx().operatorExchange(1), "the first contact")
        assertEquals("5NN001", wpx().operatorExchange(1).piece, "one piece, no space")
        assertEquals(OperatorExchange("5NN012", ""), wpx().operatorExchange(12))
        assertEquals(OperatorExchange("5NN123", ""), wpx().operatorExchange(123), "three digits as-is")
        // `%.3d` is a minimum width: a four-digit serial is not truncated (ADR-0015 §3).
        assertEquals(OperatorExchange("5NN1234", ""), wpx().operatorExchange(1234))
    }

    @Test
    fun `without a serial the configured start is sent`() {
        // `exchangeDefaults.nr` is "1" (upstream's `Tst.Me.NR := 1`), padded by the same rule.
        assertEquals(OperatorExchange("5NN001", ""), wpx().operatorExchange())
        assertEquals(1, wpx().startingSerial)
    }

    @Test
    fun `the serial advances once per logged contact, as upstream's Inc does`() {
        // Log.pas:766 `Inc(Tst.Me.NR)` after every saved contact.
        val rules = wpx()
        assertEquals(1, rules.startingSerial)
        assertEquals(1, rules.serialFor(0), "the first contact sends the start")
        assertEquals(2, rules.serialFor(1), "after one contact the next sends 2")
        assertEquals(8, rules.serialFor(7))
        // The two are the same statement: contact number n (1-based) sends start + n - 1.
        for (logged in 0..5) {
            assertEquals(rules.serialFor(logged), rules.startingSerial + logged)
        }
    }

    @Test
    fun `a session's serial is the start plus the contacts already in the log`() {
        // What `SessionController` does: `rules.serialFor(log.size)`. A hand-driven log proves
        // that a resumed session continues where it stopped (the log is persisted, T9).
        val rules = wpx()
        val log = QsoLog()
        listOf("DL2XYZ", "F4ABC", "K1ABC").forEachIndexed { index, call ->
            log.record(
                QsoContact(
                    call = call,
                    exch1 = "5NN",
                    exch2 = "001",
                    score = rules.score(entry(call), truth(call)),
                    elapsedMs = 1_000L * (index + 1),
                ),
            )
        }
        assertEquals(3, log.size)
        assertEquals("5NN004", rules.operatorExchange(rules.serialFor(log.size)).piece)
    }

    // --- wording ---------------------------------------------------------------

    @Test
    fun `CQ WPX overrides no wording, so the base CQ and exchange are used`() {
        // CqWpx.pas has no `SendMsg` override; `messages` is empty in the document.
        assertTrue(config("cqwpx").messages.isEmpty(), "CQ WPX declares no wording overrides")
        val script = MessageScript(wpx().operator(1), config("cqwpx").messages)
        assertEquals("CQ W1AW TEST", script.text(QsoMessage.CQ))
        assertEquals("CQ CQ TEST W1AW W1AW TEST", script.text(QsoMessage.LONG_CQ))
        assertEquals("5NN001", script.text(QsoMessage.EXCHANGE))
        assertEquals("TU", script.text(QsoMessage.TU))
        assertEquals("NR?", script.text(QsoMessage.NR_QM))
    }

    // --- entry validation ------------------------------------------------------

    @Test
    fun `the typed entry is validated against the contest document`() {
        val rules = wpx()
        assertTrue(rules.validate(entry()).isEmpty(), "5NN + 001 is a valid exchange")
        assertTrue(rules.validate(entry(rst = "599", nr = "1")).isEmpty(), "599 and 1 are too")
        assertTrue(rules.validate(entry(rst = "5nn", nr = "001")).isEmpty(), "case is normalised")
        assertTrue(rules.validate(entry(rst = "5NN", nr = "9999")).isEmpty(), "four digits fit")
        assertTrue(rules.validate(entry(rst = "5999")).isNotEmpty(), "an RST is three characters")
        assertTrue(rules.validate(entry(rst = "ABC")).isNotEmpty(), "[1-5E][1-9N][1-9N] only")
        assertTrue(rules.validate(entry(nr = "0")).isNotEmpty(), "a serial starts at 1")
        assertTrue(rules.validate(entry(nr = "10000")).isNotEmpty(), "the box holds four digits")
        assertTrue(rules.validate(entry(nr = "")).isNotEmpty(), "the serial is required")
        // Upstream's box pattern is `([0-9OTN]+)|(#)` (`ExchFields.pas:53`): the O/T/N letters
        // are the digit confusions the operator may type. A numeric field binds a number when
        // it parses, so a lettered serial cannot pass `validation` here — the same shape CQ WW's
        // numeric zone already has (ADR-0015 §7). The *verdict* still folds the letters (§ below).
        assertTrue(rules.validate(entry(nr = "1O")).isNotEmpty(), "a numeric field cannot hold 'O'")
    }

    // --- the save-time verdict -------------------------------------------------

    @Test
    fun `a correct copy logs with no errors`() {
        val verdict = wpx().judge(entry(), truth())
        assertTrue(verdict.errors.isEmpty(), "unexpected errors ${verdict.errors}")
        assertTrue(verdict.corrections.isEmpty())
    }

    @Test
    fun `a wrong serial is NR and a wrong report is RST`() {
        // `Log.pas:942` (`etSerialNr`) and `:859` (`etRST`).
        assertEquals(listOf(QsoError.Number), wpx().judge(entry(nr = "002"), truth()).errors)
        assertEquals(listOf("1"), wpx().judge(entry(nr = "002"), truth()).corrections)
        assertEquals(listOf(QsoError.Rst), wpx().judge(entry(rst = "579"), truth()).errors)
        assertEquals(listOf("599"), wpx().judge(entry(rst = "579"), truth()).corrections)
    }

    @Test
    fun `the O T N digit confusions are accepted, as upstream's copy table does`() {
        // Upstream maps what the operator hears onto digits: RST `A/E/N → 1/5/9`
        // (`Main.pas:592-601`) and the serial `A/N/T → 1/9/0` (`Main.pas:663-673`). The port's
        // shared table (`ContestRules.digits`) is the union, so `O` is a zero too.
        assertTrue(wpx().judge(entry(nr = "1T"), truth(nr = "10")).errors.isEmpty(), "T is a zero")
        assertTrue(wpx().judge(entry(nr = "1N"), truth(nr = "19")).errors.isEmpty(), "N is a nine")
        assertTrue(wpx().judge(entry(nr = "1O"), truth(nr = "10")).errors.isEmpty(), "O is a zero")
        assertTrue(wpx().judge(entry(rst = "ENN"), truth()).errors.isEmpty(), "E and N are 5 and 9")
        assertTrue(
            wpx().judge(entry(nr = "001"), truth(nr = "1")).errors.isEmpty(),
            "padding is not a copy error",
        )
    }

    // --- scoring --------------------------------------------------------------

    @Test
    fun `one point per contact and the worked call's WPX prefix is the multiplier`() {
        // CqWpx.pas:285-291 `Qso.Points := 1; Result := Qso.Pfx`, `Log.pas:715`
        // `Qso.Pfx := ExtractPrefix(Qso.Call)`.
        assertEquals(ScoreResult(1, setOf("DL2")), wpx().score(entry(), truth()))
        assertEquals(
            ScoreResult(1, setOf("F4")),
            wpx().score(entry("F4ABC", "5NN", "001"), truth("F4ABC")),
        )
        // Upstream's own prefix cases: a district override and a portable prefix.
        assertEquals(
            ScoreResult(1, setOf("W6")),
            wpx().score(entry("W7SST/6", "5NN", "001"), truth("W7SST/6")),
        )
        assertEquals(
            ScoreResult(1, setOf("DL0")),
            wpx().score(entry("DL/AA1ZZ", "5NN", "001"), truth("DL/AA1ZZ")),
        )
    }

    @Test
    fun `two calls with one prefix are one multiplier, two prefixes are two`() {
        // The multiplier is the prefix, not the call: DL2XYZ and DL2ABC share `DL2`, while
        // DL3ABC is `DL3`. Hand-computed: 3 contacts × 1 point = 3 points, 2 prefixes → 6.
        val rules = wpx()
        val log = QsoLog()
        listOf("DL2XYZ", "DL2ABC", "DL3ABC").forEachIndexed { index, call ->
            log.record(
                QsoContact(
                    call = call,
                    exch1 = "5NN",
                    exch2 = "001",
                    score = rules.score(entry(call), truth(call)),
                    elapsedMs = 1_000L * (index + 1),
                ),
            )
        }
        assertEquals(3, log.rawPointsTotal)
        assertEquals(setOf("DL2", "DL3"), log.qsos.flatMap { it.multipliers }.toSet())
        assertEquals(2, log.rawMultiplierCount)
        assertEquals(6, log.rawScore)
    }

    @Test
    fun `a call with no resolvable prefix fails closed instead of inventing one`() {
        // The document asks `callPrefix(theirCall)`; unknown data is `CALL_DATA_MISSING`
        // (spec §4/§5), which the engine reports rather than scoring a guess (ADR-0015 §7).
        val score = wpx().score(entry("ZZ9ZZZ"), truth("ZZ9ZZZ"))
        assertEquals(0, score.points)
        assertTrue(score.multipliers.isEmpty())
        assertTrue(score.failure.orEmpty().contains("callPrefix"), "unexpected failure: ${score.failure}")
    }
}
