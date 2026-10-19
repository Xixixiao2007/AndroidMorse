package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.expr.CallFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **T17: ARRL DX CW driven through the engine** — the eighth per-contest ticket and the first
 * whose *multiplier* depends on which side of the contest the operator is on.
 *
 * `ArrlDx.pas:344-362` is the whole rule this file pins:
 *
 * ```pascal
 * Qso^.Points := 3;
 * if Self.HomeCallIsLocal then
 *   if gDXCCList.FindRec(dxrec, Qso^.Call) then Result := dxrec.Entity
 * else
 *   Result := Qso.Exch2;          // the worked W/VE station's state/province
 * ```
 *
 * `HomeCallIsLocal` is the DXCC entity of *my* call being the USA or Canada
 * (`ArrlDx.pas:196-213`) — in the port that is the `myCountry` identifier, which `scoring`
 * already sees (spec §4). The sent piece does **not** consult it: upstream types one box whose
 * value is `Me.Exch2` on both sides (`Main.pas:1548`), and the port mirrors that one-box model
 * (ADR-0017 §2).
 */
class ArrlDxRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    /** The two operator sides this contest distinguishes, plus the stations they work. */
    private val dxcc = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null
        override fun country(call: String): String? = when (call.uppercase()) {
            "W1AW", "K1ABC" -> "United States of America"
            "VE3ABC" -> "Canada"
            "DL1AA", "DL1ABC" -> "Fed. Rep. of Germany"
            "JA1ABC" -> "Japan"
            else -> null
        }

        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }

    private fun rules(myCall: String, exch2: String, calls: CallFacts = dxcc): ContestRules =
        ContestRules(config("arrldx"), MyStation(myCall, mapOf("rst" to "5NN", "exch2" to exch2)), calls)

    private fun entry(call: String, rst: String = "5NN", exch2: String = "") = QsoEntry(call, rst, exch2)

    private fun truth(call: String, rst: String = "599", exch2: String = "") = QsoTruth(call, rst, exch2)

    // --- my exchange: one box, two meanings --------------------------------------

    @Test
    fun `my sent exchange is the one box I typed, whichever side I am on`() {
        // `Main.pas:1548` handles `etStateProv` and `etPower` in the *same* branch: the value is
        // `Me.Exch2` either way, and the type decides only the caption and the length rule
        // (`Contest.pas:585`). That is why the sent piece is the field, not a conditional on
        // `myCountry` (ADR-0017 §2).
        assertEquals(OperatorExchange("5NN", "OR"), rules("W1AW", "OR").operatorExchange())
        assertEquals(OperatorExchange("5NN", "100"), rules("DL1AA", "100").operatorExchange())
        assertEquals(OperatorExchange("5NN", "KW"), rules("DL1AA", "KW").operatorExchange())
    }

    @Test
    fun `the sent exchange needs no country, so an unknown operator call still renders it`() {
        // Fail closed applies to the *side*, not to the piece: an unresolved operator call makes
        // scoring fail (there is no way to know which multiplier I earn) while the exchange I send
        // stays exactly what I typed.
        val unknownOperator = rules("W1AW", "CT", CallFacts.Unavailable)
        assertEquals(OperatorExchange("5NN", "CT"), unknownOperator.operatorExchange())
    }

    // --- scoring: three points, two multiplier rules ------------------------------

    @Test
    fun `a W or VE operator's multiplier is the worked station's DXCC entity`() {
        // `ArrlDx.pas:352-355`: `gDXCCList.FindRec(dxrec, Qso^.Call)` → `dxrec.Entity`.
        val score = rules("W1AW", "CT").score(entry("DL1ABC", "5NN", "100"), truth("DL1ABC", "599", "100"))
        assertEquals(3, score.points)
        assertEquals(setOf("Fed. Rep. of Germany"), score.multipliers)
        assertNull(score.failure)
    }

    @Test
    fun `a DX operator's multiplier is the worked station's state or province`() {
        // `ArrlDx.pas:359`: `Result := Qso.Exch2` — no lookup at all.
        val score = rules("DL1AA", "100").score(entry("K1ABC", "5NN", "CT"), truth("K1ABC", "599", "CT"))
        assertEquals(3, score.points)
        assertEquals(setOf("CT"), score.multipliers)
        assertNull(score.failure)
    }

    @Test
    fun `Canada is on the W-VE side of the contest`() {
        // `ArrlDx.pas:201-203`: the entity is `'United States of America'` **or** `'Canada'`.
        val score = rules("VE3ABC", "ON").score(entry("JA1ABC", "5NN", "100"), truth("JA1ABC", "599", "100"))
        assertEquals(3, score.points)
        assertEquals(setOf("Japan"), score.multipliers)
    }

    @Test
    fun `the operator's country decides the multiplier rule, not the worked call`() {
        // One worked station, two operators, two different multiplier sets — the side is mine.
        val worked = entry("DL1ABC", "5NN", "100")
        val facts = truth("DL1ABC", "599", "100")
        assertEquals(setOf("Fed. Rep. of Germany"), rules("W1AW", "CT").score(worked, facts).multipliers)
        // A DX operator never works DL1ABC in play (the pool filter forbids it), but the rule is
        // still the state side: `Qso.Exch2`, whatever the call resolves to.
        assertEquals(setOf("100"), rules("DL1AA", "100").score(worked, facts).multipliers)
    }

    @Test
    fun `an unresolvable worked call costs the entity side its contact and nothing on the DX side`() {
        // Fail closed: `callCountry` of an unknown call is an evaluation failure, so a W/VE
        // operator's contact scores 0 where upstream keeps 3 points and no multiplier
        // (`ArrlDx.pas:353-355`). The DX side reads the station's own exchange and cannot fail.
        // Declared as a deviation in ADR-0017 §7; measured against the shipped pool in
        // `PackagedAssetsTest`.
        val local = rules("W1AW", "CT").score(entry("ZZ9ZZZ", "5NN", "100"), truth("ZZ9ZZZ", "599", "100"))
        assertEquals(0, local.points)
        assertNotNull(local.failure)

        val dx = rules("DL1AA", "100").score(entry("ZZ9ZZZ", "5NN", "CT"), truth("ZZ9ZZZ", "599", "CT"))
        assertEquals(3, dx.points)
        assertEquals(setOf("CT"), dx.multipliers)
        assertNull(dx.failure)
    }

    // --- the save-time verdict ---------------------------------------------------

    @Test
    fun `a correct copy logs with no errors, and a wrong exchange is flagged with the true value`() {
        val rules = rules("W1AW", "CT")
        assertTrue(
            rules.judge(entry("DL1ABC", "5NN", "100"), truth("DL1ABC", "599", "100")).errors.isEmpty(),
        )
        val wrong = rules.judge(entry("DL1ABC", "5NN", "50"), truth("DL1ABC", "599", "100"))
        // The box is `exch2`, whose meaning follows the side, so the shared upstream label is the
        // generic `leERR` rather than `leST`/`lePWR` (ADR-0017 §8).
        assertEquals(listOf(QsoError.Err), wrong.errors)
        assertEquals(listOf("100"), wrong.corrections)
    }

    @Test
    fun `a wrong report is RST`() {
        val wrong = rules("W1AW", "CT").judge(entry("DL1ABC", "579", "100"), truth("DL1ABC", "599", "100"))
        assertEquals(listOf(QsoError.Rst), wrong.errors)
        assertEquals(listOf("599"), wrong.corrections)
    }

    // --- wording ------------------------------------------------------------------

    @Test
    fun `the base script sends my own box, with no contest override`() {
        // `ArrlDx.pas` overrides GetExchange/GetExch2 for the *station*, not SendMsg for me.
        val script = MessageScript(rules("W1AW", "OR").operator(), config("arrldx").messages)
        assertEquals("CQ W1AW TEST", script.text(QsoMessage.CQ))
        assertEquals("5NN OR", script.text(QsoMessage.EXCHANGE))
        assertEquals("TU", script.text(QsoMessage.TU))
    }
}
