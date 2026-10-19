package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.ArrlSections
import com.bh4gzk.morserunner.data.DxccList
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Expression
import com.bh4gzk.morserunner.expr.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cross-module contract T6 depends on: **contest config → station generation →
 * scoring**, with the real shipped documents and the real expression engine.
 *
 * If these pass, the three modules are wired correctly: `core-contest` compiles and
 * validates a contest, the roster generates a station's exchange from it, the parse
 * exposes the `their<Group>` identifiers, and `scoring` evaluates those identifiers
 * with the `CallFacts` bridge from `core-data`.
 */
class T5ContractTest {

    private fun config(id: String): ContestConfig {
        val result = ContestLibrary.load(id)
        assertIs<LoadResult.Success>(result, "$id did not load")
        return result.config
    }

    private fun value(
        expression: Expression,
        environment: Map<String, Value>,
        calls: CallFacts = CallFacts.Unavailable,
    ): Value = when (val result = expression.evaluate(environment, calls = calls)) {
        is EvalResult.Success -> result.value
        is EvalResult.Failure -> error("expected success, got ${result.error.render()}")
    }

    private fun text(
        expression: Expression,
        environment: Map<String, Value>,
        calls: CallFacts = CallFacts.Unavailable,
    ): String = (value(expression, environment, calls) as Value.Str).value

    private fun number(
        expression: Expression,
        environment: Map<String, Value>,
        calls: CallFacts = CallFacts.Unavailable,
    ): Double = (value(expression, environment, calls) as Value.Num).value

    @Test
    fun `an ARRL SS station generated from the config scores through its parsed section`() {
        val ss = config("arrlss")
        val roster = StationRoster(
            config = ss,
            pool = masterDtaOf("W1AW", "K5ZD", "VE3ABC"),
            history = HistoryLookup { mapOf("check" to "72", "section" to "OR") },
            settings = RosterSettings(speedWpm = 30),
            seed = 2026,
        )
        val station = assertNotNull(roster.add())

        // Generation produced the pieces; the parse exposed theirSect/theirChk.
        assertEquals("72 OR", station.exchange.exch2)
        assertTrue(station.exchange.exch1.endsWith(" A"))
        assertEquals("72", (station.exchange.parsed.getValue("theirChk") as Value.Str).value)
        assertEquals("OR", (station.exchange.parsed.getValue("theirSect") as Value.Str).value)

        val environment = ss.environment(
            *station.exchange.parsed.toList().toTypedArray(),
            "theirCall" to Value.str(station.call),
            "theirExch1" to Value.str(station.exchange.exch1),
            "theirExch2" to Value.str(station.exchange.exch2),
        )
        assertEquals(2.0, number(ss.scoring.points, environment))
        val multiplier = text(ss.scoring.multipliers.single().key, environment)
        assertEquals("OR", multiplier)
        assertTrue(multiplier in ArrlSections.all)
    }

    @Test
    fun `a CQ WW station generated from the config scores with the DXCC facts`() {
        val cqww = config("cqww")
        // A clearly-labelled two-row fixture, not a DXCC.LIST conversion.
        val dxcc = DxccList.parse(
            """
            K;K|W|N|A[A-GI-K];United States of America;NA;8;3,4,5;291
            DL;(D[A-R])|(Y[2-9]);Fed. Rep. of Germany;EU;28;14;230
            """.trimIndent(),
        )
        val facts = AssetCallFacts(dxcc)
        val roster = StationRoster(
            config = cqww,
            pool = masterDtaOf("DL1ABC"),
            history = HistoryLookup { mapOf("zone" to "14") },
            settings = RosterSettings(speedWpm = 30),
            seed = 7,
            calls = facts,
        )
        val station = assertNotNull(roster.add())
        assertEquals("DL1ABC", station.call)
        assertEquals("599", station.exchange.exch1)
        assertEquals("14", station.exchange.exch2)

        val environment = cqww.environment(
            "rst" to Value.str("599"),
            "zone" to Value.num(5),
            "theirCall" to Value.str(station.call),
            "theirExch1" to Value.str(station.exchange.exch1),
            "theirExch2" to Value.str(station.exchange.exch2),
            "myCountry" to Value.str("United States of America"),
            "myContinent" to Value.str("NA"),
        )
        // CqWW.pas:258-265: a different continent is three points.
        assertEquals(3.0, number(cqww.scoring.points, environment, facts))
        val multipliers = cqww.scoring.multipliers.mapNotNull { multiplier ->
            if (multiplier.whenExpression != null && (value(multiplier.whenExpression, environment, facts) as Value.Bool).value) {
                text(multiplier.key, environment, facts)
            } else if (multiplier.whenExpression == null) {
                text(multiplier.key, environment, facts)
            } else {
                null
            }
        }
        assertEquals(listOf("ZN-14", "Fed. Rep. of Germany"), multipliers)
    }

    @Test
    fun `an IARU HF station generated from the config scores through the continent facts`() {
        val iaru = config("iaruhf")
        val dxcc = DxccList.parse(
            """
            K;K|W|N|A[A-GI-K];United States of America;NA;8;3,4,5;291
            DL;(D[A-R])|(Y[2-9]);Fed. Rep. of Germany;EU;28;14;230
            """.trimIndent(),
        )
        val facts = AssetCallFacts(dxcc)
        val roster = StationRoster(
            config = iaru,
            pool = masterDtaOf("DL1ABC"),
            history = HistoryLookup { mapOf("section" to "14") },
            settings = RosterSettings(speedWpm = 30),
            seed = 9,
            calls = facts,
        )
        val station = assertNotNull(roster.add())
        assertEquals("599", station.exchange.exch1)
        assertEquals("14", station.exchange.exch2)

        val environment = iaru.environment(
            "rst" to Value.str("599"),
            "itu" to Value.str("8"),
            "theirCall" to Value.str(station.call),
            "theirExch1" to Value.str(station.exchange.exch1),
            "theirExch2" to Value.str(station.exchange.exch2),
            "myContinent" to Value.str("NA"),
        )
        // IaruHf.pas:337-348: a numeric exchange in a different zone on another
        // continent is five points; the same exchange would score three inside one.
        assertEquals(5.0, number(iaru.scoring.points, environment, facts))
        assertEquals("14", text(iaru.scoring.multipliers.single().key, environment, facts))
    }
}
