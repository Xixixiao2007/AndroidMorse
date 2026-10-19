package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.expr.CallFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contest-authored rules used by the engine: entry validation (schema `validation`), the
 * save-time verdict (upstream `Log.pas` `TQso.CheckExch1/2`) and scoring
 * (upstream `Contest.ExtractMultiplier`, now expressions).
 *
 * The three contests are loaded through the real loader, so this also proves the T3
 * documents drive T6 without a second rule engine (ADR-0005 §2).
 */
class ContestRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    /** A tiny DXCC stand-in: the real one is an asset (T3), and the seam is `CallFacts`. */
    private val dxcc: CallFacts = object : CallFacts {
        override fun prefix(call: String): String? = null
        override fun zone(call: String): Double? = null

        override fun country(call: String): String? = when {
            call.startsWith("KL7") -> "Alaska"
            call.startsWith("KH6") -> "Hawaii"
            call.startsWith("DL") -> "Germany"
            call.startsWith("K") || call.startsWith("W") || call.startsWith("N") -> "United States of America"
            else -> null
        }

        override fun continent(call: String): String? = when (country(call)) {
            "Germany" -> "EU"
            "United States of America", "Alaska", "Hawaii" -> "NA"
            else -> null
        }

        override fun arrlSection(call: String): String? = if (call.startsWith("W")) "OR" else null
    }

    private fun cqww(me: MyStation = MyStation("W1AW", mapOf("rst" to "5NN", "zone" to "3"))) =
        ContestRules(config("cqww"), me, dxcc)

    private fun ss() = ContestRules(
        config("arrlss"),
        MyStation("W1AW", mapOf("nr" to "5", "prec" to "A", "check" to "72", "section" to "OR")),
        dxcc,
    )

    private fun iaru() = ContestRules(
        config("iaruhf"),
        MyStation("W1AW", mapOf("rst" to "599", "itu" to "8")),
        dxcc,
    )

    private fun entry(call: String = "DL1ABC", exch1: String = "599", exch2: String = "14") =
        QsoEntry(call, exch1, exch2)

    // --- the sent exchange -----------------------------------------------------

    @Test
    fun `the two sent pieces are the fields in schema order`() {
        assertEquals(OperatorExchange("5NN", "3"), cqww().operatorExchange)
    }

    @Test
    fun `a contest with sent expressions combines the fields`() {
        // arrlss.json: exch1 = "str(nr) + ' ' + prec", exch2 = "... ' ' + section".
        assertEquals(OperatorExchange("5 A", "72 OR"), ss().operatorExchange)
    }

    // --- entry validation ------------------------------------------------------

    @Test
    fun `the typed boxes map to the schema fields`() {
        assertEquals(mapOf("rst" to "599", "zone" to "14"), cqww().typedFields(entry()))
    }

    @Test
    fun `a single-box contest parses the typed text into fields`() {
        // arrlss.json entryParse: "^ *(?<nr>...)...(?<section>...) *$".
        assertEquals(
            mapOf("nr" to "12", "prec" to "B", "check" to "71", "section" to "OR"),
            ss().typedFields(entry(call = "K1ABC", exch1 = "12", exch2 = "B 71 OR")),
        )
    }

    @Test
    fun `a zone outside the range is rejected by the contest`() {
        assertEquals(listOf("Invalid CQ zone"), cqww().validate(entry(exch2 = "99")))
    }

    @Test
    fun `a malformed RST is rejected by the contest`() {
        assertTrue(cqww().validate(entry(exch1 = "5X9")).any { it.contains("RST") })
    }

    @Test
    fun `a valid entry has no errors`() {
        assertTrue(cqww().validate(entry()).isEmpty())
    }

    @Test
    fun `an unknown ARRL section is rejected`() {
        assertTrue(ss().validate(entry(exch1 = "12", exch2 = "B 71 XX")).any { it.contains("section") })
    }

    // --- save-time verdicts ----------------------------------------------------

    @Test
    fun `a correct contact has no verdict`() {
        val verdict = cqww().judge(entry(), QsoTruth("DL1ABC", "599", "14"))

        assertTrue(verdict.errors.isEmpty())
        assertTrue(verdict.corrections.isEmpty())
    }

    @Test
    fun `a wrong call is CALL with the true call as correction`() {
        val verdict = cqww().judge(
            entry(call = "DL1ABC"),
            QsoTruth("DL1ABD", "599", "14", mapOf("rst" to "599", "zone" to "14")),
        )

        assertEquals(listOf(QsoError.Call), verdict.errors)
        assertEquals(listOf("DL1ABD"), verdict.corrections)
    }

    @Test
    fun `a wrong RST is RST with the true report`() {
        val verdict = cqww().judge(
            entry(exch1 = "559"),
            QsoTruth("DL1ABC", "599", "14", mapOf("rst" to "599", "zone" to "14")),
        )

        assertEquals(listOf(QsoError.Rst), verdict.errors)
        assertEquals(listOf("599"), verdict.corrections)
    }

    @Test
    fun `the RST letter forms read the same as digits`() {
        // Upstream maps A/E/N to 1/5/9 on input (Main.pas Edit2KeyPress).
        val verdict = cqww().judge(
            entry(exch1 = "5NN"),
            QsoTruth("DL1ABC", "599", "14", mapOf("rst" to "599", "zone" to "14")),
        )

        assertTrue(verdict.errors.isEmpty(), "5NN and 599 are the same report")
    }

    @Test
    fun `a wrong zone is ZN, compared as a number`() {
        val truth = QsoTruth("DL1ABC", "599", "4", mapOf("rst" to "599", "zone" to "4"))
        assertTrue(cqww().judge(entry(exch2 = "04"), truth).errors.isEmpty(), "leading zeros are no difference")

        val wrong = cqww().judge(
            entry(exch2 = "15"),
            QsoTruth("DL1ABC", "599", "14", mapOf("rst" to "599", "zone" to "14")),
        )
        assertEquals(listOf(QsoError.Zone), wrong.errors)
        assertEquals(listOf("14"), wrong.corrections)
    }

    @Test
    fun `a wrong SS section is SEC, and the check is not blamed`() {
        val verdict = ss().judge(
            entry(call = "K1ABC", exch1 = "12", exch2 = "B 71 XX"),
            QsoTruth(
                "K1ABC",
                "12 B",
                "71 OR",
                mapOf("nr" to "12", "prec" to "B", "check" to "71", "section" to "OR"),
            ),
        )

        assertEquals(listOf(QsoError.Section), verdict.errors)
        assertEquals(listOf("OR"), verdict.corrections)
    }

    @Test
    fun `a wrong SS precedence is PREC`() {
        val verdict = ss().judge(
            entry(call = "K1ABC", exch1 = "12", exch2 = "U 71 OR"),
            QsoTruth(
                "K1ABC",
                "12 B",
                "71 OR",
                mapOf("nr" to "12", "prec" to "B", "check" to "71", "section" to "OR"),
            ),
        )

        assertEquals(listOf(QsoError.Precedence), verdict.errors)
        assertEquals(listOf("B"), verdict.corrections)
    }

    @Test
    fun `without typed truth the verdict names the piece`() {
        // The two-piece station model can only blame the piece (ADR-0005 §6).
        val verdict = cqww().judge(entry(exch2 = "15"), QsoTruth("DL1ABC", "599", "14"))

        assertEquals(listOf(QsoError.Zone), verdict.errors)
        assertEquals(listOf("14"), verdict.corrections)
    }

    // --- scoring: CQ WW --------------------------------------------------------

    @Test
    fun `CQ WW scores three points for another continent and two multipliers`() {
        val score = cqww().score(entry(), QsoTruth("DL1ABC", "599", "14"))

        assertNull(score.failure)
        assertEquals(3, score.points)
        assertEquals(setOf("ZN-14", "Germany"), score.multipliers)
    }

    @Test
    fun `CQ WW scores nothing for my own country`() {
        val score = cqww().score(entry(call = "K1ABC", exch2 = "5"), QsoTruth("K1ABC", "599", "5"))

        assertEquals(0, score.points)
        assertEquals(setOf("ZN-5", "United States of America"), score.multipliers)
    }

    @Test
    fun `CQ WW scores two for another North American country`() {
        val score = cqww().score(entry(call = "KL7ABC", exch2 = "1"), QsoTruth("KL7ABC", "599", "1"))

        assertEquals(2, score.points)
        // Alaska is a multiplier as the United States of America (CqWW.pas:238-243).
        assertEquals(setOf("ZN-1", "United States of America"), score.multipliers)
    }

    @Test
    fun `CQ WW scores nothing and one multiplier for a maritime mobile`() {
        val score = cqww().score(
            entry(call = "DL1ABC/MM", exch2 = "14"),
            QsoTruth("DL1ABC/MM", "599", "14"),
        )

        assertEquals(0, score.points)
        assertEquals(setOf("ZN-14"), score.multipliers, "/MM is a zone multiplier only")
    }

    @Test
    fun `a scoring expression without call data fails closed`() {
        val blind = ContestRules(config("cqww"), MyStation("W1AW", mapOf("rst" to "5NN", "zone" to "3")))

        val score = blind.score(entry(), QsoTruth("DL1ABC", "599", "14"))

        assertEquals(0, score.points)
        assertTrue(score.multipliers.isEmpty())
        assertTrue(score.failure!!.contains("callCountry"), score.failure!!)
    }

    // --- scoring: ARRL SS and IARU --------------------------------------------

    @Test
    fun `ARRL SS scores two points and the section`() {
        val score = ss().score(
            entry(call = "K1ABC", exch1 = "12", exch2 = "B 71 OR"),
            QsoTruth("K1ABC", "12 B", "71 OR"),
        )

        assertNull(score.failure)
        assertEquals(2, score.points)
        assertEquals(setOf("OR"), score.multipliers)
    }

    @Test
    fun `ARRL SS with an unparseable exchange fails closed`() {
        val score = ss().score(
            entry(call = "K1ABC", exch1 = "12", exch2 = "B 71 OR"),
            QsoTruth("K1ABC", "12 B", "B 71 OR"),
        )

        assertEquals(0, score.points)
        assertTrue(score.multipliers.isEmpty())
        assertTrue(score.failure != null)
    }

    @Test
    fun `IARU scores one point when his exchange matches mine`() {
        val score = iaru().score(entry(exch2 = "8"), QsoTruth("DL1ABC", "599", "8"))

        assertEquals(1, score.points)
        assertEquals(setOf("8"), score.multipliers)
    }

    @Test
    fun `IARU scores one point for a society name`() {
        val score = iaru().score(entry(exch2 = "DARC"), QsoTruth("DL1ABC", "599", "DARC"))

        assertEquals(1, score.points)
        assertEquals(setOf("DARC"), score.multipliers)
    }

    @Test
    fun `IARU scores five points for another continent`() {
        val score = iaru().score(entry(exch2 = "14"), QsoTruth("DL1ABC", "599", "14"))

        assertEquals(5, score.points)
    }

    @Test
    fun `IARU scores three points for the same continent`() {
        val score = iaru().score(entry(call = "K1ABC", exch2 = "6"), QsoTruth("K1ABC", "599", "6"))

        assertEquals(3, score.points)
    }

    @Test
    fun `the operator's own exchange comes from the configured fields`() {
        // myCountry/myContinent come from the DXCC lookup of my call (spec §4).
        assertFalse(cqww().score(entry(exch2 = "14"), QsoTruth("DL1ABC", "599", "14")).multipliers.isEmpty())
    }
}
