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
 * **T14: CWops CWT driven through the engine** — the fifth per-contest ticket's rules check
 * (spec §11).
 *
 * The rules are the Pascal's, not this test's: the operator's exchange default
 * (`Ini.pas:106-114`), the two typed fields and their verdicts (`Log.pas:860` `leNAME`; the
 * value-dependent `leNR`/`leQTH` of `Log.pas:911-918`), one point per contact and the
 * multiplier being **the worked call** (`CwOps.pas:245-249`).
 *
 * Two things this test exists for:
 *
 *  - the **label is value-dependent**: CWT's second piece is `etGenericField`, and upstream
 *    prints `NR` for a numeric true value (a CWT member number) and `QTH` otherwise (a state,
 *    province or country prefix). `ContestRules.labelFor` now receives the true value; every
 *    other contest's label is unchanged;
 *  - the multiplier is `theirCall`, so the log's own set union does the "unique calls" count —
 *    two stations in the same state are two multipliers, and a duplicate call is still one.
 *
 * The call facts are unavailable on purpose: CWT's document asks DXCC nothing, so a fake table
 * would hide a regression that started asking.
 */
class CwopsRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    private fun cwt() = ContestRules(
        config("cwops"),
        // Ini.pas:112 `ExchDefault: 'DAVID 123'` — what upstream sends whatever the call is.
        MyStation("W1AW", mapOf("name" to "DAVID", "exch" to "123")),
        CallFacts.Unavailable,
    )

    private fun entry(call: String = "3B9FR", name: String = "ROBERT", exch: String = "1343") =
        QsoEntry(call, name, exch)

    private fun truth(call: String = "3B9FR", name: String = "ROBERT", exch: String = "1343") =
        QsoTruth(call, name, exch)

    // --- my exchange -----------------------------------------------------------

    @Test
    fun `my sent exchange is upstream's default DAVID 123`() {
        // Ini.pas:112 `ExchDefault: 'DAVID 123'`, two boxes labelled Name / Exch (`:110`).
        assertEquals(OperatorExchange("DAVID", "123"), cwt().operatorExchange())
        assertEquals(
            mapOf("name" to "ROBERT", "exch" to "1343"),
            cwt().typedFields(entry()),
            "box 1 is the operator name, box 2 is the generic exchange",
        )
    }

    @Test
    fun `CWT overrides the CQ and long-CQ wordings and keeps the rest`() {
        // CwOps.pas:184 `msgCQ: 'CQ CWT <my>'`; `:193` `msgLongCQ: 'CQ CQ CWT <my> <my>'`.
        // Every other message falls through to `inherited SendMsg` (`:195`).
        val messages = config("cwops").messages
        assertEquals("CQ CWT <my>", messages["CQ"])
        assertEquals("CQ CQ CWT <my> <my>", messages["longCQ"])
        val script = MessageScript(cwt().operator(), messages)
        assertEquals("CQ CWT W1AW", script.text(QsoMessage.CQ))
        assertEquals("CQ CQ CWT W1AW W1AW", script.text(QsoMessage.LONG_CQ))
        assertEquals("TU", script.text(QsoMessage.TU), "upstream's base TU is the bare word")
        assertEquals("NR?", script.text(QsoMessage.NR_QM))
        assertEquals("DAVID 123", script.text(QsoMessage.EXCHANGE))
    }

    // --- entry validation ------------------------------------------------------

    @Test
    fun `the typed entry is validated against the contest document`() {
        val rules = cwt()
        assertTrue(rules.validate(entry()).isEmpty(), "a full exchange is valid")
        assertTrue(rules.validate(entry(name = "DAVID", exch = "FL")).isEmpty(), "a state fits")
        assertTrue(rules.validate(entry(name = "david", exch = "fl")).isEmpty(), "case is normalised")
        assertTrue(rules.validate(entry(name = "CHRISTIAN", exch = "4O")).isEmpty(), "nine letters fit")
        assertTrue(rules.validate(entry(name = "CHRISTOPHER")).isNotEmpty(), "a name is up to ten")
        assertTrue(rules.validate(entry(name = "DAVID2")).isNotEmpty(), "a name is letters only")
        assertTrue(rules.validate(entry(name = "")).isNotEmpty(), "a name is required")
        assertTrue(rules.validate(entry(exch = "12-3")).isNotEmpty(), "a hyphen is not allowed")
        assertTrue(rules.validate(entry(exch = "ABCDEFGHIJKLM")).isNotEmpty(), "the box is twelve")
        // `[0-9A-Z]*` matches the empty string: upstream's own validator accepts a blank second
        // box (`Contest.pas:310`), so the document does not invent a stricter rule.
        assertTrue(rules.validate(entry(exch = "")).isEmpty(), "the empty string matches [0-9A-Z]*")
    }

    // --- the save-time verdict -------------------------------------------------

    @Test
    fun `a correct copy logs with no errors`() {
        val verdict = cwt().judge(entry(), truth())
        assertTrue(verdict.errors.isEmpty(), "unexpected errors ${verdict.errors}")
        assertTrue(verdict.corrections.isEmpty())
    }

    @Test
    fun `a wrong name is NAME, as upstream labels exchange 1`() {
        // Log.pas:860 `etOpName: if TrueExch1 <> Exch1 then Exch1Error := leNAME`.
        val verdict = cwt().judge(entry(name = "ROB"), truth())
        assertEquals(listOf(QsoError.Name), verdict.errors)
        assertEquals(listOf("ROBERT"), verdict.corrections)
    }

    @Test
    fun `the second field's label follows the true value, NR for a member number and QTH otherwise`() {
        // Log.pas:911-918: `etGenericField` under `scCwt` labels a mismatch `leNR` when
        // `IsNum(TrueExch2)` and `leQTH` otherwise. This is the ticket's one engine touch.
        val numericTruth = cwt().judge(entry(exch = "1344"), truth(exch = "1343"))
        assertEquals(
            listOf(QsoError.Number),
            numericTruth.errors,
            "3B9FR sent a member number, so a mismatch is NR",
        )
        assertEquals(listOf("1343"), numericTruth.corrections)

        val textTruth = cwt().judge(entry(exch = "4P"), truth(exch = "4O"))
        assertEquals(
            listOf(QsoError.Qth),
            textTruth.errors,
            "4O3A sent a country prefix, so a mismatch is QTH",
        )
        assertEquals(listOf("4O"), textTruth.corrections)

        val stateTruth = cwt().judge(
            entry(call = "N5OT", name = "MARK", exch = "NE"),
            truth("N5OT", "MARK", "ND"),
        )
        assertEquals(listOf(QsoError.Qth), stateTruth.errors, "a state is not a number")
    }

    @Test
    fun `a copy that differs only in case is accepted`() {
        // `ContestRules.sameValue` compares TEXT after upper-casing, as upstream's log does.
        val verdict = cwt().judge(entry(name = "robert", exch = "1343"), truth())
        assertTrue(verdict.errors.isEmpty(), "case must not be an error: ${verdict.errors}")
    }

    // --- scoring --------------------------------------------------------------

    @Test
    fun `one point per contact and the worked call is the multiplier`() {
        // CwOps.pas:245-249 `Qso^.Points := 1; Result:= Qso^.Call;`.
        assertEquals(ScoreResult(1, setOf("3B9FR")), cwt().score(entry(), truth()))
        assertEquals(
            ScoreResult(1, setOf("4O3A")),
            cwt().score(entry("4O3A", "RANKO", "4O"), truth("4O3A", "RANKO", "4O")),
        )
        // The multiplier is the call, not the exchange: two stations in the same state are two
        // different multipliers.
        assertEquals(
            ScoreResult(1, setOf("N5OT")),
            cwt().score(entry("N5OT", "MARK", "ND"), truth("N5OT", "MARK", "ND")),
        )
        assertEquals(
            ScoreResult(1, setOf("K1ABC")),
            cwt().score(entry("K1ABC", "DAVID", "ND"), truth("K1ABC", "DAVID", "ND")),
        )
    }

    @Test
    fun `a hand-computed contest scores five points times five callsigns`() {
        // 3B9FR/1343, 4O3A/4O, N5OT/ND, K1ABC/ND, W1AL/FL: five contacts, one point each; the
        // multiplier set is the five calls (the two `ND` stations do not collapse) → 25.
        val rules = cwt()
        val log = QsoLog()
        val contacts = listOf(
            Triple("3B9FR", "ROBERT", "1343"),
            Triple("4O3A", "RANKO", "4O"),
            Triple("N5OT", "MARK", "ND"),
            Triple("K1ABC", "DAVID", "ND"),
            Triple("W1AL", "SCOTT", "FL"),
        )
        contacts.forEachIndexed { index, (call, name, exch) ->
            val score = rules.score(entry(call, name, exch), truth(call, name, exch))
            val outcome = log.record(
                QsoContact(
                    call = call,
                    exch1 = name,
                    exch2 = exch,
                    score = score,
                    elapsedMs = 1_000L * (index + 1),
                ),
            )
            if (index < contacts.size - 1) assertIs<LogOutcome.Accepted>(outcome)
        }
        assertEquals(5, log.size)
        assertEquals(5, log.rawPointsTotal)
        assertEquals(5, log.rawMultiplierCount, "each worked call is one multiplier")
        assertEquals(25, log.rawScore)

        // A duplicate call contributes nothing: the log refuses it, and the multiplier set
        // already holds the call (`CwOps.pas:247-248` is `Qso^.Call`, the log's own union).
        val duplicate = log.record(
            QsoContact("3B9FR", "ROBERT", "1343", rules.score(entry(), truth()), elapsedMs = 9_000L),
        )
        assertEquals(LogOutcome.Duplicate, duplicate)
        assertEquals(5, log.rawMultiplierCount)
    }
}
