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
 * **T11: the K1USN Slow Speed Test driven through the engine** — the second per-contest
 * ticket's rules check (spec §11).
 *
 * The rules are the Pascal's, not this test's: the operator's exchange default
 * (`Ini.pas:166-176`), the two typed fields and their verdicts (`Log.pas:860` `leNAME`,
 * `Log.pas:917-920` `leQTH`), one point per contact (`CWSST.pas:322`) and the multiplier —
 * the QTH, or the DXCC entity when the station sent the literal `DX`
 * (`CWSST.pas:326-330`).
 */
class SstRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    /** A tiny DXCC stand-in; the shipped table is `DXCC.LIST` (spec §9). */
    private val dxcc: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null

        override fun country(call: String): String? = when {
            call.startsWith("DL") -> "Fed. Rep. of Germany"
            call.startsWith("JA") -> "Japan"
            call.startsWith("VE") -> "Canada"
            call.startsWith("K") || call.startsWith("W") || call.startsWith("N") -> "United States of America"
            else -> null
        }

        override fun continent(call: String): String? = when (country(call)) {
            "Japan" -> "AS"
            "Fed. Rep. of Germany" -> "EU"
            "Canada", "United States of America" -> "NA"
            else -> null
        }

        override fun arrlSection(call: String): String? = null
    }

    private fun sst() = ContestRules(
        config("k1usnsst"),
        MyStation("W1AW", mapOf("name" to "BRUCE", "qth" to "MA")),
        dxcc,
    )

    private fun entry(call: String = "K1ABC", exch1: String = "MIKE", exch2: String = "MA") =
        QsoEntry(call, exch1, exch2)

    private fun truth(call: String = "K1ABC", exch1: String = "MIKE", exch2: String = "MA") =
        QsoTruth(call, exch1, exch2)

    // --- my exchange -----------------------------------------------------------

    @Test
    fun `my sent exchange is upstream's default name and QTH`() {
        // Ini.pas:172 `ExchDefault: 'BRUCE MA'` for the K1USN Slow Speed Test.
        assertEquals(OperatorExchange("BRUCE", "MA"), sst().operatorExchange)
        assertEquals(
            mapOf("name" to "MIKE", "qth" to "MA"),
            sst().typedFields(entry()),
            "box 1 is the name, box 2 is the QTH",
        )
    }

    @Test
    fun `the contest's message overrides are the Pascal's`() {
        // CWSST.pas:190,200,223: msgCQ 'CQ SST <my>', msgTU 'TU <my>',
        // msgLongCQ 'CQ CQ SST <my> <my>'.
        val script = MessageScript(sst().operator, config("k1usnsst").messages)
        assertEquals("CQ SST W1AW", script.text(QsoMessage.CQ))
        assertEquals("TU W1AW", script.text(QsoMessage.TU))
        assertEquals("CQ CQ SST W1AW W1AW", script.text(QsoMessage.LONG_CQ))
        // A message the contest does not override keeps upstream's base wording.
        assertEquals("?", script.text(QsoMessage.QM))
    }

    // --- entry validation ------------------------------------------------------

    @Test
    fun `the typed entry is validated against the contest document`() {
        val rules = sst()
        assertTrue(rules.validate(entry(exch1 = "MIKE", exch2 = "MA")).isEmpty(), "a full exchange is valid")
        assertTrue(rules.validate(entry(exch1 = "mike", exch2 = "ma")).isEmpty(), "case is normalised")
        assertTrue(rules.validate(entry(exch1 = "ZVONKO", exch2 = "DX")).isEmpty(), "DX is a legal QTH")
        assertTrue(rules.validate(entry(exch1 = "", exch2 = "MA")).isNotEmpty(), "a name is required")
        assertTrue(rules.validate(entry(exch1 = "MIKE JR", exch2 = "MA")).isNotEmpty(), "a name has no space")
        assertTrue(rules.validate(entry(exch1 = "MIKE", exch2 = "MASSACHUSETTS")).isNotEmpty(), "QTH <= 12")
    }

    // --- the save-time verdict -------------------------------------------------

    @Test
    fun `a correct copy logs with no errors`() {
        val verdict = sst().judge(entry(), truth())
        assertTrue(verdict.errors.isEmpty(), "unexpected errors ${verdict.errors}")
        assertTrue(verdict.corrections.isEmpty())
    }

    @Test
    fun `a wrong name is NAME and a wrong QTH is QTH, as upstream labels them`() {
        // Log.pas:860 `etOpName` -> leNAME; Log.pas:917-920 `scSst` on `etGenericField` -> leQTH.
        val wrongName = sst().judge(entry(exch1 = "DAVE"), truth())
        assertEquals(listOf(QsoError.Name), wrongName.errors)
        assertEquals(listOf("MIKE"), wrongName.corrections)

        val wrongQth = sst().judge(entry(exch2 = "NY"), truth())
        assertEquals(listOf(QsoError.Qth), wrongQth.errors, "SST labels its second field QTH, not ST")
        assertEquals(listOf("MA"), wrongQth.corrections)
    }

    @Test
    fun `a DX station is logged as DX and scores its entity`() {
        // The file's own QTH for a DX station is the literal 'DX' (`CWSST.pas:90`), so there is
        // no empty-box convention here: the operator copies 'DX' and the entity is the multiplier.
        val rules = sst()
        val atDx = rules.judge(entry("DL1ABC", "ZVONKO", "DX"), truth("DL1ABC", "ZVONKO", "DX"))
        assertTrue(atDx.errors.isEmpty(), "DX is what the station sends: ${atDx.errors}")
        assertEquals(
            ScoreResult(1, setOf("Fed. Rep. of Germany")),
            rules.score(entry("DL1ABC", "ZVONKO", "DX"), truth("DL1ABC", "ZVONKO", "DX")),
        )
    }

    // --- scoring --------------------------------------------------------------

    @Test
    fun `one point per contact whatever the QTH`() {
        // CWSST.pas:322 `Qso^.Points := 1;` — no bonus for DX, no penalty for local.
        assertEquals(ScoreResult(1, setOf("MA")), sst().score(entry(), truth()))
        assertEquals(
            ScoreResult(1, setOf("ON")),
            sst().score(entry("VE3ABC", "BRUCE", "ON"), truth("VE3ABC", "BRUCE", "ON")),
        )
        assertEquals(
            ScoreResult(1, setOf("Japan")),
            sst().score(entry("JA1XYZ", "HIRO", "DX"), truth("JA1XYZ", "HIRO", "DX")),
        )
    }

    @Test
    fun `a hand-computed contest scores four points times three multipliers`() {
        // K1ABC/MA, W2XYZ/MA (duplicate multiplier), DL1ABC/DX (Germany), VE3ABC/ON:
        // 1+1+1+1 points; {MA, Fed. Rep. of Germany, ON} = 3 multipliers → 12.
        val rules = sst()
        val log = QsoLog()
        listOf(
            rules.score(entry("K1ABC", "MIKE", "MA"), truth("K1ABC", "MIKE", "MA")),
            rules.score(entry("W2XYZ", "DAVE", "MA"), truth("W2XYZ", "DAVE", "MA")),
            rules.score(entry("DL1ABC", "ZVONKO", "DX"), truth("DL1ABC", "ZVONKO", "DX")),
            rules.score(entry("VE3ABC", "BRUCE", "ON"), truth("VE3ABC", "BRUCE", "ON")),
        ).forEachIndexed { index, score ->
            val call = listOf("K1ABC", "W2XYZ", "DL1ABC", "VE3ABC")[index]
            val exchange = listOf("MIKE MA", "DAVE MA", "ZVONKO DX", "BRUCE ON")[index].split(" ")
            assertIs<LogOutcome.Accepted>(
                log.record(
                    QsoContact(
                        call = call,
                        exch1 = exchange[0],
                        exch2 = exchange[1],
                        score = score,
                        elapsedMs = 1_000L * (index + 1),
                    ),
                ),
            )
        }
        assertEquals(4, log.size)
        assertEquals(4, log.rawPointsTotal)
        assertEquals(3, log.rawMultiplierCount, "MA is counted once; the DX entity once; ON once")
        assertEquals(12, log.rawScore)
    }

    @Test
    fun `the QTH is the multiplier verbatim, but a copy that differs only in case is accepted`() {
        val scored = sst().score(entry(exch2 = "ma"), truth(exch2 = "MA"))
        assertEquals(setOf("MA"), scored.multipliers, "the truth's spelling is the multiplier key")
        assertTrue(
            sst().judge(entry(exch2 = "ma"), truth(exch2 = "MA")).errors.isEmpty(),
            "the port compares exchange text after uppercasing",
        )
    }
}
