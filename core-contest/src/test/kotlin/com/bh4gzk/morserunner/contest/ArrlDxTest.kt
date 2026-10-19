package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ARRL DX CW (T17) — the first contest whose **station pool depends on which side of the
 * contest the operator is on**.
 *
 * Upstream holds the two exchanges in `TDualExchContest` (`DualExchContest.pas:16-108`, the
 * Karnaugh table in the comment at `:66-76`): a W/VE station sends a state/province and works
 * DX stations (which send a power), a DX station sends a power and works W/VE stations (which
 * send a state). `ArrlDx.pas` implements the two consequences this file tests:
 *
 *  - `:157-158` the call history keeps only the rows this operator can work;
 *  - `:344-362` a contact is worth **3 points** and the multiplier is the worked station's
 *    entity for W/VE, the worked station's state/province for DX.
 *
 * `myCountry` is the identifier that decides the side everywhere it matters. It is a station
 * property (spec §4), so it is bound from the DXCC lookup of the operator's own call — the
 * engine never guesses a side from a prefix (ADR-0017 §3).
 */
class ArrlDxTest {

    private val config = assertIs<LoadResult.Success>(ContestLibrary.load("arrldx"), "arrldx did not load").config

    /** Resolves the two operator calls this file needs, and nothing else. */
    private val countries = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null
        override fun country(call: String): String? = when (call.uppercase()) {
            "W1AW", "K1ABC" -> "United States of America"
            "DL1AA" -> "Fed. Rep. of Germany"
            else -> null
        }

        override fun continent(call: String): String? = null
        override fun arrlSection(call: String): String? = null
    }

    /**
     * Four rows, two per side: `Ini.pas`'s file carries a `State` **and** a `Power` column and
     * every data row fills exactly one of them (`ArrlDx.pas:120-160`).
     */
    private val rows = """
        !!Order!!,Call,Name,State,Power,UserText,
        #
        # the two sides of the file, interleaved so a filter cannot pass by accident
        DL1AA,,,100,
        W1AW,,CT,,
        K1ABC,,MA,,
        JA1ABC,,,KW,
    """.trimIndent()

    private fun history(myCall: String, calls: CallFacts = countries): HistoryLoad =
        CallHistoryFile.parse(rows, config.callHistory!!, myCall, calls)

    private fun loaded(myCall: String, calls: CallFacts = countries): CallHistoryFile =
        assertIs<HistoryLoad.Loaded>(history(myCall, calls), "$myCall was rejected").history

    // --- the document -------------------------------------------------------------

    @Test
    fun `the document is the ARRL DX CW the upstream defines`() {
        assertEquals("arrldx", config.id)
        assertEquals("ARRL DX CW", config.name)
        assertEquals(Duration(defaultMinutes = 30, minMinutes = 1, maxMinutes = 60), config.duration)
        // `Ini.pas:157-161`: ExchType1 etRST, ExchType2 etStateProv **or** etPower — the operator
        // types one box whose meaning follows the side (`Main.pas:1548` sends `Me.Exch2` either way).
        assertEquals(setOf("rst", "exch2"), config.exchange.fieldKeys)
        val sent = config.exchange.sent!!
        assertEquals(setOf("rst"), sent.exch1.identifiers())
        assertEquals(setOf("exch2"), sent.exch2.identifiers(), "my sent piece is the typed box itself")
        // Both history columns are bound: `getExch2` is `State + Power` (`ArrlDx.pas:320-323`).
        assertEquals(
            setOf("hisState", "hisPower"),
            config.exchange.their.bindings.keys,
            "both columns must be bound; each row fills exactly one",
        )
        assertEquals(setOf("hisState", "hisPower"), config.exchange.their.exch2.identifiers())
        // The pool filter is the ticket's engine prerequisite, and it reads exactly three names.
        assertEquals(
            setOf("myCountry", "state", "power"),
            config.callHistory!!.filter!!.identifiers(),
        )
        // The contest cannot decide its own side without the operator's country.
        assertTrue(config.capabilities.requiresMyDxcc, "ARRL DX needs the operator's DXCC entity")
    }

    @Test
    fun `a contact is worth three points whatever the side`() {
        // `ArrlDx.pas:351`: `Qso^.Points := 3`, unconditionally.
        val points = assertIs<EvalResult.Success>(config.scoring.points.evaluate()).value
        assertEquals(3.0, assertIs<Value.Num>(points).value)
        assertEquals(1, config.scoring.multipliers.size, "the multiplier switches sides inside one key")
    }

    // --- the pool filter ----------------------------------------------------------

    @Test
    fun `a W or VE operator works only the rows that carry a power`() {
        // `ArrlDx.pas:157-158`: `HomeCallIsLocal and not rec.Power.IsEmpty`.
        val history = loaded("W1AW")
        assertEquals(setOf("DL1AA", "JA1ABC"), history.calls)
        assertEquals("100", history.recordFor("DL1AA")?.get("power"))
        assertEquals("", history.recordFor("DL1AA")?.get("state"))
        assertNull(history.recordFor("K1ABC"), "a state-only row is not a station a W/VE operator works")
    }

    @Test
    fun `a DX operator works only the rows that carry a state`() {
        // `ArrlDx.pas:157-158`: `not HomeCallIsLocal and not rec.State.IsEmpty`.
        val history = loaded("DL1AA")
        assertEquals(setOf("W1AW", "K1ABC"), history.calls)
        assertEquals("CT", history.recordFor("W1AW")?.get("state"))
        assertEquals("", history.recordFor("W1AW")?.get("power"))
        assertNull(history.recordFor("JA1ABC"), "a power-only row is not a station a DX operator works")
    }

    @Test
    fun `the filter is refused when the operator call does not resolve`() {
        // Fail closed: without the country there is no side, and a pool that guesses is worse
        // than a pool that says why it cannot be built (upstream best-guesses the prefix,
        // `ArrlDx.pas:204-208`; the port refuses instead — ADR-0017 §3).
        val rejected = assertIs<HistoryLoad.Rejected>(history("W1AW", CallFacts.Unavailable))
        assertTrue(
            rejected.reason.contains("myCountry"),
            "the reason should name the missing property, was: ${rejected.reason}",
        )
    }

    @Test
    fun `a filter that keeps nothing is rejected rather than loaded empty`() {
        // A W/VE operator and a file with no DX row: the pool is not a pool.
        val wveOnly = """
            !!Order!!,Call,Name,State,Power,UserText,
            W1AW,,CT,,
            K1ABC,,MA,,
        """.trimIndent()
        val rejected = assertIs<HistoryLoad.Rejected>(
            CallHistoryFile.parse(wveOnly, config.callHistory!!, "W1AW", countries),
        )
        assertTrue(rejected.reason.contains("no usable records"), rejected.reason)
    }

    // --- the two exchange shapes -------------------------------------------------

    @Test
    fun `the station's exchange is its power for a W or VE operator and its state for a DX operator`() {
        // `getExch2` concatenates State + Power (`ArrlDx.pas:320-323`); the filter guarantees
        // exactly one of them is filled, so the concatenation *is* the one that was filled.
        val generator = TheirExchangeGenerator(config, countries)

        val dxRow = loaded("W1AW").recordFor("DL1AA")!!
        assertEquals(
            "100",
            assertIs<GenerationResult.Success>(generator.generate(history = dxRow)).exchange.exch2,
        )
        assertEquals("599", assertIs<GenerationResult.Success>(generator.generate(history = dxRow)).exchange.exch1)

        val wveRow = loaded("DL1AA").recordFor("W1AW")!!
        assertEquals(
            "CT",
            assertIs<GenerationResult.Success>(generator.generate(history = wveRow)).exchange.exch2,
        )
    }

    // --- determinism --------------------------------------------------------------

    @Test
    fun `the same bytes and the same operator produce the same pool in the same order`() {
        // The replay contract (ADR-0007): the pool is the file's own order, filtered.
        val first = loaded("W1AW")
        val second = loaded("W1AW")
        assertEquals(first.calls.toList(), second.calls.toList())
        assertEquals(setOf("DL1AA", "JA1ABC"), first.calls)
    }
}
