package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.GenerationResult
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.contest.TheirExchangeGenerator
import com.bh4gzk.morserunner.expr.CallFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **T18: ARRL Field Day driven through the engine** — the ninth and last per-contest ticket.
 *
 * `ArrlFd.pas` is short where the other contests are long, and three rules are the whole file:
 *
 * ```pascal
 * Qso^.Points := 2;  Result := '1';                     // :479-486 — no multiplier at all
 * msgCQ:    SendText(AStn, 'CQ FD <my>');               // :425 — the Field Day CQ
 * msgLongCQ: SendText(AStn, 'CQ CQ FD <my> <my> FD');   // :432
 * ```
 *
 * The exchange is two strings from the history (`GetExchange`, `:495-510`): the station's class
 * (`3A`) and its ARRL section (`OR`). Unlike ARRL DX, nothing here depends on the operator — so
 * every rule is testable with [CallFacts.Unavailable].
 */
class ArrlFdRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    private fun rules(stnClass: String = "3A", section: String = "OR", myCall: String = "DL1AA"): ContestRules =
        ContestRules(
            config("arrlfd"),
            MyStation(myCall, mapOf("class" to stnClass, "section" to section)),
            CallFacts.Unavailable,
        )

    private fun entry(call: String = "K1ABC", exch1: String = "3A", exch2: String = "OR") =
        QsoEntry(call, exch1, exch2)

    private fun truth(call: String = "K1ABC", exch1: String = "3A", exch2: String = "OR") =
        QsoTruth(call, exch1, exch2)

    // --- my exchange: two strings --------------------------------------------------

    @Test
    fun `my sent exchange is my class and my section`() {
        // `Ini.pas:118-125`: `ExchType1: etFdClass`, `ExchType2: etArrlSection`, `ExchDefault: '3A OR'`.
        assertEquals(OperatorExchange("3A", "OR"), rules().operatorExchange())
        assertEquals(OperatorExchange("2B", "DX"), rules("2B", "DX").operatorExchange())
    }

    // --- scoring: two points, no multiplier ---------------------------------------

    @Test
    fun `a contact is worth two points and one constant multiplier key`() {
        // `ArrlFd.pas:479-486`: `Qso^.Points := 2; Result := '1'`.
        val score = rules().score(entry(), truth())
        assertEquals(2, score.points)
        assertEquals(setOf("1"), score.multipliers)
        assertNull(score.failure)
    }

    @Test
    fun `every contact adds the same constant key, so the multiplier count never leaves one`() {
        // The constant key is what makes a Field Day score a plain sum of 2s: `QsoLog` multiplies
        // by the distinct keys, and there is exactly one.
        val first = rules().score(entry("K1ABC"), truth("K1ABC"))
        val second = rules().score(entry("W6XYZ", "1D", "SC"), truth("W6XYZ", "1D", "SC"))
        assertEquals(4, first.points + second.points, "two points each")
        assertEquals(first.multipliers, second.multipliers, "a different class/section is no multiplier")
    }

    // --- the station's exchange ----------------------------------------------------

    @Test
    fun `the station's exchange is its class and its section`() {
        // `ArrlFd.pas:495-510 GetExchange`: `Exch1 := StnClass`, `Exch2 := Section`.
        val generator = TheirExchangeGenerator(config("arrlfd"), CallFacts.Unavailable)
        val exchange = assertIs<GenerationResult.Success>(
            generator.generate(history = mapOf("class" to "2A", "section" to "OR")),
        ).exchange
        assertEquals("2A", exchange.exch1)
        assertEquals("OR", exchange.exch2)
    }

    // --- messages: CQ FD -----------------------------------------------------------

    @Test
    fun `the CQ is CQ FD, and the long CQ says it twice`() {
        // `ArrlFd.pas:418-433`: the only two overrides; every other message keeps the base wording.
        val wordings = config("arrlfd").messages
        val script = MessageScript(rules().operator(), wordings)
        assertEquals("CQ FD DL1AA", script.text(QsoMessage.CQ))
        assertEquals("CQ CQ FD DL1AA DL1AA FD", script.text(QsoMessage.LONG_CQ))
        assertEquals("TU", script.text(QsoMessage.TU), "TU is not overridden")
        assertEquals("3A OR", script.text(QsoMessage.EXCHANGE), "<#> is my class and section")
    }

    @Test
    fun `a station's CQ uses the same Field Day wording`() {
        // The `messages` map is shared by both directions (`StationScript`), which is why the
        // station also calls `CQ FD`.
        val station = StationScript(
            Operator("K1ABC", OperatorExchange("2A", "OR")),
            config("arrlfd").messages,
        )
        assertEquals("CQ FD K1ABC", station.text(StationMessage.CQ))
    }

    // --- validation ----------------------------------------------------------------

    @Test
    fun `the class is a transmitter count and a letter, the section two or three letters`() {
        // `Contest.pas:571,584` only require a length > 1; the field definitions
        // (`ExchFields.pas:46,55`) are the shape: class `[1-9][0-9]*[A-F]` (max 3), section two or
        // three letters. The port's rule is the field definition, as ARRL DX's RST rule is.
        assertTrue(rules().validate(entry()).isEmpty(), "3A / OR is a full exchange")
        assertTrue(rules().validate(entry(exch1 = "1D", exch2 = "SC")).isEmpty())
        assertTrue(rules().validate(entry(exch1 = "10A")).isEmpty(), "a two-digit transmitter count")
        assertTrue(rules().validate(entry(exch2 = "dx")).isEmpty(), "case is normalised")
        assertTrue(rules().validate(entry(exch1 = "3G")).isNotEmpty(), "'G' is not a Field Day class")
        assertTrue(rules().validate(entry(exch1 = "3")).isNotEmpty(), "the class letter is required")
        assertTrue(rules().validate(entry(exch1 = "A")).isNotEmpty(), "a transmitter count is required")
        assertTrue(rules().validate(entry(exch1 = "100A")).isNotEmpty(), "the box holds three characters")
        assertTrue(rules().validate(entry(exch2 = "O")).isNotEmpty(), "a section is two or three letters")
        assertTrue(rules().validate(entry(exch2 = "OREGON")).isNotEmpty(), "and at most three")
    }
}
