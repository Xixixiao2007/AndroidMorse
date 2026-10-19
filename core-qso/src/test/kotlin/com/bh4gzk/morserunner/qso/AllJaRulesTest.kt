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
 * **T12: JARL ALL JA driven through the engine** — the third per-contest ticket's rules check
 * (spec §11).
 *
 * The rules are the Pascal's, not this test's: the operator's exchange default
 * (`Ini.pas:178-185`), the two typed fields and their verdicts (`Log.pas:859` `leRST`,
 * `Log.pas:942` `etJaPref` → `leNR`), one point per contact and the multiplier
 * `<pref><power>` minus a trailing `L`/`M`/`H`/`P` (`AllJa.pas:190-207`).
 *
 * The call facts are unavailable on purpose: ALL JA's document asks DXCC nothing, so a fake
 * table would hide a regression that started asking.
 */
class AllJaRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    private fun allja() = ContestRules(
        config("allja"),
        // Ini.pas:183 `ExchDefault: '5NN 10H'` — what upstream sends whatever the call is.
        MyStation("W1AW", mapOf("rst" to "5NN", "pref" to "10H")),
        CallFacts.Unavailable,
    )

    private fun entry(call: String = "7K1CPT", exch1: String = "599", exch2: String = "14H") =
        QsoEntry(call, exch1, exch2)

    private fun truth(call: String = "7K1CPT", exch1: String = "599", exch2: String = "14H") =
        QsoTruth(call, exch1, exch2)

    // --- my exchange -----------------------------------------------------------

    @Test
    fun `my sent exchange is upstream's default 5NN and 10H`() {
        // Ini.pas:183 `ExchDefault: '5NN 10H'`; `AllJa.pas` overrides no message.
        assertEquals(OperatorExchange("5NN", "10H"), allja().operatorExchange())
        assertEquals(
            mapOf("rst" to "599", "pref" to "14H"),
            allja().typedFields(entry()),
            "box 1 is the RST, box 2 is the prefecture+power number",
        )
    }

    @Test
    fun `the contest overrides no message, so upstream's base wording is used`() {
        // AllJa.pas has no `SendMsg` override at all (there is no `SendMsg` in the file), so
        // `messages: {}` is the faithful document and the base templates apply
        // (`Messages.kt`, `Contest.pas:412-450`).
        assertTrue(config("allja").messages.isEmpty(), "ALL JA overrides no wording")
        val script = MessageScript(allja().operator(), config("allja").messages)
        assertEquals("CQ W1AW TEST", script.text(QsoMessage.CQ))
        assertEquals("TU", script.text(QsoMessage.TU), "upstream's base TU is the bare word")
        assertEquals("CQ CQ TEST W1AW W1AW TEST", script.text(QsoMessage.LONG_CQ))
        assertEquals("NR?", script.text(QsoMessage.NR_QM))
        assertEquals("5NN 10H", script.text(QsoMessage.EXCHANGE))
    }

    // --- entry validation ------------------------------------------------------

    @Test
    fun `the typed entry is validated against the contest document`() {
        val rules = allja()
        assertTrue(rules.validate(entry()).isEmpty(), "a full exchange is valid")
        assertTrue(rules.validate(entry(exch1 = "5NN")).isEmpty(), "CW letters are legal in an RST")
        assertTrue(rules.validate(entry(exch1 = "579", exch2 = "112M")).isEmpty(), "a 3-digit pref fits")
        assertTrue(rules.validate(entry(exch2 = "14h")).isEmpty(), "case is normalised")
        assertTrue(rules.validate(entry(exch1 = "")).isNotEmpty(), "a report is required")
        assertTrue(rules.validate(entry(exch2 = "")).isNotEmpty(), "a number is required")
        assertTrue(rules.validate(entry(exch2 = "14")).isNotEmpty(), "the power letter is required")
        assertTrue(rules.validate(entry(exch2 = "1123M")).isNotEmpty(), "a number is up to four characters")
        assertTrue(rules.validate(entry(exch2 = "14X")).isNotEmpty(), "'X' is not a power letter")
    }

    // --- the save-time verdict -------------------------------------------------

    @Test
    fun `a correct copy logs with no errors`() {
        val verdict = allja().judge(entry(), truth())
        assertTrue(verdict.errors.isEmpty(), "unexpected errors ${verdict.errors}")
        assertTrue(verdict.corrections.isEmpty())
    }

    @Test
    fun `a wrong number is NR and a wrong report is RST, as upstream labels them`() {
        // Log.pas:942 `etJaPref: if TrueExch2 <> Exch2 then Exch2Error := leNR` — not ST/QTH.
        // Log.pas:859 `etRST: if TrueRst <> Rst then Exch1Error := leRST`.
        val wrongNumber = allja().judge(entry(exch2 = "15H"), truth())
        assertEquals(listOf(QsoError.Number), wrongNumber.errors, "ALL JA labels its second field NR")
        assertEquals(listOf("14H"), wrongNumber.corrections)

        val wrongRst = allja().judge(entry(exch1 = "579"), truth())
        assertEquals(listOf(QsoError.Rst), wrongRst.errors)
        assertEquals(listOf("599"), wrongRst.corrections)
    }

    @Test
    fun `a copy that differs only in case is accepted`() {
        // `ContestRules.sameValue` compares TEXT after upper-casing, as upstream's log does.
        val verdict = allja().judge(entry(exch2 = "14h"), truth())
        assertTrue(verdict.errors.isEmpty(), "case must not be an error: ${verdict.errors}")
    }

    // --- scoring --------------------------------------------------------------

    @Test
    fun `one point per contact and the prefecture is the multiplier`() {
        // AllJa.pas:206 `Qso^.Points := 1;` and `:190-207` the LMHP strip.
        assertEquals(ScoreResult(1, setOf("14")), allja().score(entry(), truth()))
        assertEquals(
            ScoreResult(1, setOf("110")),
            allja().score(entry("JA8BMD", "599", "110M"), truth("JA8BMD", "599", "110M")),
        )
        // The last character is not a power letter: the whole string is the multiplier.
        assertEquals(
            ScoreResult(1, setOf("2708")),
            allja().score(entry("K1ABC", "599", "2708"), truth("K1ABC", "599", "2708")),
        )
    }

    @Test
    fun `a hand-computed contest scores four points times three multipliers`() {
        // 7K1CPT/14H, JQ1ABC/14H (duplicate multiplier), JA8BMD/110M, JA4GBD/8/112M:
        // 1+1+1+1 points; {14, 110, 112} = 3 multipliers → 12.
        val rules = allja()
        val log = QsoLog()
        val contacts = listOf(
            Triple("7K1CPT", "599", "14H"),
            Triple("JQ1ABC", "599", "14H"),
            Triple("JA8BMD", "599", "110M"),
            Triple("JA4GBD/8", "599", "112M"),
        )
        contacts.forEachIndexed { index, (call, rst, number) ->
            val score = rules.score(entry(call, rst, number), truth(call, rst, number))
            assertIs<LogOutcome.Accepted>(
                log.record(
                    QsoContact(
                        call = call,
                        exch1 = rst,
                        exch2 = number,
                        score = score,
                        elapsedMs = 1_000L * (index + 1),
                    ),
                ),
            )
        }
        assertEquals(4, log.size)
        assertEquals(4, log.rawPointsTotal)
        assertEquals(3, log.rawMultiplierCount, "14H is counted once; 110M once; 112M once")
        assertEquals(12, log.rawScore)
    }
}
