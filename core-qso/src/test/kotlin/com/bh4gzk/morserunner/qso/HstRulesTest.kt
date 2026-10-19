package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.morse.MorseWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **T16: HST driven through the engine** — the seventh per-contest ticket, and the first whose
 * *score* is engine arithmetic rather than a multiplier list.
 *
 * HST's own rule is `Log.pas:600-620` `UpdateStatsHst`: for every contact, add
 * `CallToScore(call)` to the raw total, and to the verified total only when the contact has no
 * errors (`Err = '   '`). The document re-expresses that as `points = callWeight(theirCall)` plus
 * one constant multiplier key (ADR-0016 §4), and this test proves the engine turns that into:
 *
 *  - `rawScore` = the sum of every logged call's weight;
 *  - `verifiedScore` = the sum of the weights of the contacts that had **no** copy error.
 *
 * The exchange half (a running serial, `5NN` + three digits) is CQ WPX's, because `Main.pas:505`
 * gives HST the same `TCqWpx` class; T15 shipped it (`CqwpxRulesTest`), and the few checks here
 * only pin that HST's document carries the same values.
 */
class HstRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    /** HST scores by the call itself; no `CallFacts` lookup is involved. */
    private val noCalls: CallFacts = CallFacts.Unavailable

    private fun hst() = ContestRules(
        config("hst"),
        // Ini.pas:143 `ExchDefault: '5NN #'`; Main.pas:1523-1524 forces the serial to 1.
        MyStation("W1AW", mapOf("rst" to "5NN", "nr" to "1")),
        noCalls,
    )

    private fun entry(call: String = "K1ABC", rst: String = "5NN", nr: String = "001") =
        QsoEntry(call, rst, nr)

    private fun truth(call: String = "K1ABC", rst: String = "599", nr: String = "1") =
        QsoTruth(call, rst, nr)

    // --- my exchange: CQ WPX's running serial ---------------------------------

    @Test
    fun `my sent exchange is 5NN plus a three-digit serial`() {
        // Station.pas:384-394 (`scWpx, scHst` branch) and `:427-433` (599 → 5NN).
        assertEquals(OperatorExchange("5NN001", ""), hst().operatorExchange(1))
        assertEquals(OperatorExchange("5NN012", ""), hst().operatorExchange(12))
        assertEquals(OperatorExchange("5NN123", ""), hst().operatorExchange(123))
    }

    @Test
    fun `the serial advances once per logged contact, as upstream's Inc does`() {
        // Log.pas:764-766 `Inc(Tst.Me.NR)`; HST starts at 1 (Main.pas:1523-1524).
        val rules = hst()
        assertEquals(1, rules.startingSerial)
        assertEquals(1, rules.serialFor(0))
        assertEquals(2, rules.serialFor(1))
        assertEquals(8, rules.serialFor(7))
    }

    // --- the score: the call's Morse weight -----------------------------------

    @Test
    fun `one contact is worth the worked call's Morse weight`() {
        // Log.pas:586-597 `CallToScore(Qso.Call)`; the numbers are the hand-counted examples of
        // ADR-0016 §3, pinned in MorseWeightTest.
        fun points(call: String): Int = hst().score(entry(call), truth(call)).points

        assertEquals(63, points("K1ABC"))
        assertEquals(49, points("W1AW"))
        assertEquals(75, points("JA1ABC"))
        assertEquals(99, points("DL/AA1ZZ"))
        // Not a constant: the score really is a function of the callsign.
        assertTrue(points("K1ABC") != points("W1AW"))
    }

    @Test
    fun `the multiplier set is one constant key, so the product is a sum`() {
        // HST has no multiplier rule; UpdateStatsHst only adds (Log.pas:600-620). The constant
        // key is what makes `points × multipliers` equal the sum (ADR-0016 §4).
        val score = hst().score(entry("K1ABC"), truth("K1ABC"))
        assertEquals(setOf("HST"), score.multipliers)
        assertTrue(score.failure == null, "unexpected scoring failure: ${score.failure}")
        // Every call contributes the same key, so the key set never grows past one.
        assertEquals(setOf("HST"), hst().score(entry("DL/AA1ZZ"), truth("DL/AA1ZZ")).multipliers)
    }

    @Test
    fun `the raw and verified totals are sums of weights, not products`() {
        // The heart of the ticket: three contacts whose weights are 63, 49 and 75 (ADR-0016 §3),
        // the middle one mis-copied (its serial is 003 instead of 2). Upstream's UpdateStatsHst
        // gives
        //   raw      = 63 + 49 + 75 = 187   (every contact)
        //   verified = 63 + 75      = 138   (the errored contact is not confirmed)
        // Note the score of the mis-copied contact is still W1AW's weight: `theirCall` is the
        // truth, i.e. what the station sent (ADR-0005 §5), not what was typed.
        val rules = hst()
        val log = QsoLog()
        data class Copy(val call: String, val typedNr: String, val trueNr: String)

        listOf(
            Copy("K1ABC", "001", "1"),
            Copy("W1AW", "003", "2"),
            Copy("JA1ABC", "003", "3"),
        ).forEachIndexed { index, copy ->
            val entry = entry(copy.call, "5NN", copy.typedNr)
            val truth = truth(copy.call, "599", copy.trueNr)
            val verdict = rules.judge(entry, truth)
            log.record(
                QsoContact(
                    call = copy.call,
                    exch1 = "5NN",
                    exch2 = copy.typedNr,
                    score = rules.score(entry, truth),
                    verdict = verdict,
                    // What SessionController does: verified <=> no copy error (Log.pas:611-619).
                    verified = verdict.errors.isEmpty(),
                    elapsedMs = 1_000L * (index + 1),
                ),
            )
        }

        assertEquals(3, log.size)
        assertEquals(187, log.rawPointsTotal, "the raw total adds every contact's weight")
        assertEquals(1, log.rawMultiplierCount, "one constant key, not one per contact")
        assertEquals(187, log.rawScore, "the product of a sum and one key is the sum")
        assertEquals(138, log.verifiedPointsTotal, "only the two clean contacts count")
        assertEquals(1, log.verifiedMultiplierCount)
        assertEquals(138, log.verifiedScore)
        // And the guard the shape could get wrong: a duplicate weight is still added twice,
        // because the sum is in the points, not in the multiplier key set.
        val repeated = QsoLog()
        for (call in listOf("K1ABC", "K1ABC")) {
            repeated.record(
                QsoContact(
                    call = call,
                    exch1 = "5NN",
                    exch2 = "001",
                    score = rules.score(entry(call), truth(call)),
                    elapsedMs = 1_000L,
                ),
            )
        }
        assertEquals(1, repeated.size, "a duplicate call is rejected before it is scored (ADR-0005 §8)")
    }

    @Test
    fun `a score with no evaluator failure is not reported as one`() {
        // `callWeight` needs no data asset, so HST cannot hit CALL_DATA_MISSING the way WPX can:
        // the only way this test fails is if the built-in is missing or mistyped.
        val score = hst().score(entry("K1ABC"), truth("K1ABC"))
        assertTrue(score.points > 0, "a contact under HST must be worth its weight")
        assertNull(score.failure)
    }

    // --- the save-time verdict -------------------------------------------------

    @Test
    fun `a correct copy logs with no errors`() {
        val verdict = hst().judge(entry(), truth())
        assertTrue(verdict.errors.isEmpty(), "unexpected errors ${verdict.errors}")
        assertTrue(verdict.corrections.isEmpty())
    }

    @Test
    fun `a wrong serial is NR and a wrong report is RST`() {
        // Log.pas:942 (`etSerialNr`) and :859 (`etRST`) — the shared upstream labels.
        assertEquals(listOf(QsoError.Number), hst().judge(entry(nr = "002"), truth()).errors)
        assertEquals(listOf("1"), hst().judge(entry(nr = "002"), truth()).corrections)
        assertEquals(listOf(QsoError.Rst), hst().judge(entry(rst = "579"), truth()).errors)
        assertEquals(listOf("599"), hst().judge(entry(rst = "579"), truth()).corrections)
    }

    // --- wording ---------------------------------------------------------------

    @Test
    fun `HST overrides no wording, so the base CQ and exchange are used`() {
        // CqWpx.pas has no SendMsg override and Main.pas:505 gives HST that same class.
        val script = MessageScript(hst().operator(1), config("hst").messages)
        assertEquals("CQ W1AW TEST", script.text(QsoMessage.CQ))
        assertEquals("5NN001", script.text(QsoMessage.EXCHANGE))
        assertEquals("TU", script.text(QsoMessage.TU))
        assertEquals("NR?", script.text(QsoMessage.NR_QM))
    }

    @Test
    fun `the weight the engine scores is the module's, call for call`() {
        // One more guard against a document that re-derives the rule instead of asking for it.
        for (call in listOf("K1ABC", "W1AW", "JA1ABC", "DL/AA1ZZ", "5NN", "E", "T")) {
            assertEquals(MorseWeight.of(call), hst().score(entry(call), truth(call)).points, call)
        }
    }
}
