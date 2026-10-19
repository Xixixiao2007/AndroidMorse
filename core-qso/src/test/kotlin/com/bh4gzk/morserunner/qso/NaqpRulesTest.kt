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
 * **T10: NCJ NAQP driven through the engine** — the third behaviour check of the first
 * per-contest ticket (spec §11).
 *
 * The rules are the Pascal's, not this test's: the operator's exchange default
 * (`Ini.pas:128-136`), the two typed fields and their verdicts (`Log.pas:860,944`), one point
 * per contact and the state as the multiplier (`NaQp.pas:395-404`), and the local-call test
 * (`NaQp.pas:410-421`). The config is loaded through the real loader, so a document change
 * that breaks one of these fails here.
 */
class NaqpRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    /** A tiny DXCC stand-in; the shipped table is `DXCC.LIST` (spec §9). */
    private val dxcc: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null

        override fun country(call: String): String? = when {
            call.startsWith("KH6") -> "Hawaii"
            call.startsWith("KL7") -> "Alaska"
            call.startsWith("VE") -> "Canada"
            call.startsWith("K") || call.startsWith("W") || call.startsWith("N") -> "United States of America"
            call.startsWith("DL") -> "Fed. Rep. of Germany"
            else -> null
        }

        override fun continent(call: String): String? = when (country(call)) {
            "Hawaii" -> "OC"
            "Alaska", "Canada", "United States of America" -> "NA"
            "Fed. Rep. of Germany" -> "EU"
            else -> null
        }

        override fun arrlSection(call: String): String? = null
    }

    private fun naqp() = ContestRules(
        config("naqp"),
        MyStation("W1AW", mapOf("name" to "ALEX", "state" to "ON")),
        dxcc,
    )

    private fun entry(call: String = "K1ABC", exch1: String = "MIKE", exch2: String = "OR") =
        QsoEntry(call, exch1, exch2)

    private fun truth(call: String = "K1ABC", exch1: String = "MIKE", exch2: String = "OR") =
        QsoTruth(call, exch1, exch2)

    // --- my exchange -----------------------------------------------------------

    @Test
    fun `my sent exchange is upstream's default name and state`() {
        // Ini.pas:133 `ExchDefault: 'ALEX ON'` for NCJ NAQP; two boxes, one field each.
        assertEquals(OperatorExchange("ALEX", "ON"), naqp().operatorExchange)
        assertEquals(
            mapOf("name" to "MIKE", "state" to "OR"),
            naqp().typedFields(entry()),
            "box 1 is the name, box 2 is the state",
        )
    }

    // --- entry validation ------------------------------------------------------

    @Test
    fun `the typed entry is validated against the contest document`() {
        val rules = naqp()
        assertTrue(rules.validate(entry(exch1 = "MIKE", exch2 = "OR")).isEmpty(), "a full exchange is valid")
        assertTrue(rules.validate(entry(exch1 = "mike", exch2 = "or")).isEmpty(), "case is normalised")
        // A DX station sends no state, and an empty box is legal (`ExchFields.pas:66`).
        assertTrue(rules.validate(entry(exch1 = "ZVONKO", exch2 = "")).isEmpty())
        assertTrue(rules.validate(entry(exch1 = "", exch2 = "OR")).isNotEmpty(), "a name is required")
        assertTrue(rules.validate(entry(exch1 = "MIKE JR", exch2 = "OR")).isNotEmpty(), "a name has no space")
        assertTrue(rules.validate(entry(exch1 = "MIKE", exch2 = "ONTARIO")).isNotEmpty(), "state <= 6")
    }

    // --- the save-time verdict -------------------------------------------------

    @Test
    fun `a correct copy logs with no errors`() {
        val verdict = naqp().judge(entry(), truth())
        assertTrue(verdict.errors.isEmpty(), "unexpected errors ${verdict.errors}")
        assertTrue(verdict.corrections.isEmpty())
    }

    @Test
    fun `a wrong name is NAME and a wrong state is ST, as upstream labels them`() {
        // Log.pas:860 `etOpName` -> leNAME; Log.pas:944 `etNaQpExch2` -> leST.
        val wrongName = naqp().judge(entry(exch1 = "DAVE"), truth())
        assertEquals(listOf(QsoError.Name), wrongName.errors)
        assertEquals(listOf("MIKE"), wrongName.corrections)

        val wrongState = naqp().judge(entry(exch2 = "NY"), truth())
        assertEquals(listOf(QsoError.State), wrongState.errors)
        assertEquals(listOf("OR"), wrongState.corrections)
    }

    @Test
    fun `a DX station is logged as DX, upstream's convention for a non-NA station`() {
        // Log.pas:945-949 `etNaQpNonNaExch2`: TrueExch2 is empty, and `DX` in the box is
        // accepted. The two-box entry needs something in box 2, so this is how a DX station
        // is worked at all.
        val dx = naqp().judge(
            entry(call = "DL1ABC", exch1 = "ZVONKO", exch2 = "DX"),
            truth(call = "DL1ABC", exch1 = "ZVONKO", exch2 = ""),
        )
        assertTrue(dx.errors.isEmpty(), "DX stands for the empty second piece: ${dx.errors}")
        // The excuse only exists for an empty truth: a localized station's wrong state is
        // still an error.
        val wrong = naqp().judge(entry(exch2 = "DX"), truth(exch2 = "OR"))
        assertEquals(listOf(QsoError.State), wrong.errors)
    }

    @Test
    fun `a DX station's empty second piece is matched without an error`() {
        val verdict = naqp().judge(
            entry(call = "DL1ABC", exch1 = "ZVONKO", exch2 = ""),
            truth(call = "DL1ABC", exch1 = "ZVONKO", exch2 = ""),
        )
        assertTrue(verdict.errors.isEmpty(), "the DX station sent no state; an empty box is correct")
    }

    // --- scoring --------------------------------------------------------------

    @Test
    fun `one point per contact, state as the multiplier only for a local call`() {
        // NaQp.pas:395-404,410-421.
        assertEquals(ScoreResult(1, setOf("OR")), naqp().score(entry(), truth()))
        assertEquals(ScoreResult(1, setOf("ON")), naqp().score(entry("VE3ABC", "BRUCE", "ON"), truth("VE3ABC", "BRUCE", "ON")))
        assertEquals(ScoreResult(1, setOf("HI")), naqp().score(entry("KH6XX", "LEE", "HI"), truth("KH6XX", "LEE", "HI")))
        // Hawaii is OC in the DXCC table, so the entity exception is what keeps it local.
        assertTrue(naqp().score(entry("DL1ABC", "ZVONKO", "DL"), truth("DL1ABC", "ZVONKO", "DL")).multipliers.isEmpty())
    }

    @Test
    fun `a hand-computed contest scores three points times two multipliers`() {
        // K1ABC/OR, DL1ABC (no mult), KH6XX/HI: 1+1+1 points, {OR, HI} multipliers.
        val log = QsoLog()
        val one = ScoreResult(1, setOf("OR"))
        val two = ScoreResult(1, emptySet())
        val three = ScoreResult(1, setOf("HI"))
        listOf(
            QsoContact("K1ABC", "MIKE", "OR", one, elapsedMs = 1_000),
            QsoContact("DL1ABC", "ZVONKO", "", two, elapsedMs = 2_000),
            QsoContact("KH6XX", "LEE", "HI", three, elapsedMs = 3_000),
        ).forEach { assertIs<LogOutcome.Accepted>(log.record(it)) }

        assertEquals(3, log.size)
        assertEquals(3, log.rawPointsTotal)
        assertEquals(2, log.rawMultiplierCount)
        assertEquals(6, log.rawScore)
    }

    @Test
    fun `the state is the multiplier verbatim, but a copy that differs only in case is accepted`() {
        val scored = naqp().score(entry(exch2 = "or"), truth(exch2 = "OR"))
        assertEquals(setOf("OR"), scored.multipliers, "the truth's spelling is the multiplier key")
        assertTrue(
            naqp().judge(entry(exch2 = "or"), truth(exch2 = "OR")).errors.isEmpty(),
            "upstream compares exchange text case-insensitively (Log.pas:1015-1021)",
        )
    }
}
