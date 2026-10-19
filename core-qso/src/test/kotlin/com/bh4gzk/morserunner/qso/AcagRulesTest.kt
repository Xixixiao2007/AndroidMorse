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
 * **T13: JARL ACAG driven through the engine** — the fourth per-contest ticket's rules check
 * (spec §11).
 *
 * The rules are the Pascal's, not this test's: the operator's exchange default
 * (`Ini.pas:187-194`), the two typed fields and their verdicts (`Log.pas:859` `leRST`,
 * `Log.pas:943` `etJaCity` → `leNR`), one point per contact and the multiplier
 * `<city|gun|ku><power>` minus a trailing `L`/`M`/`H`/`P` (`Acag.pas:191-207`).
 *
 * ACAG is ALL JA's twin, and the two rules that differ are the ones this test exists for: the
 * second field's key is `city` (upstream's `etJaCity`, not `pref`), and a code like `01039`
 * must keep its leading zero because the multiplier is a **string**.
 *
 * The call facts are unavailable on purpose: ACAG's document asks DXCC nothing, so a fake
 * table would hide a regression that started asking.
 */
class AcagRulesTest {

    private fun config(id: String): ContestConfig = when (val loaded = ContestLibrary.load(id)) {
        is LoadResult.Success -> loaded.config
        is LoadResult.Failure -> error("$id failed to load: ${loaded.errors.joinToString()}")
    }

    private fun acag() = ContestRules(
        config("acag"),
        // Ini.pas:192 `ExchDefault: '5NN 1002H'` — what upstream sends whatever the call is.
        MyStation("W1AW", mapOf("rst" to "5NN", "city" to "1002H")),
        CallFacts.Unavailable,
    )

    private fun entry(call: String = "7K1CPT", exch1: String = "599", exch2: String = "1430H") =
        QsoEntry(call, exch1, exch2)

    private fun truth(call: String = "7K1CPT", exch1: String = "599", exch2: String = "1430H") =
        QsoTruth(call, exch1, exch2)

    // --- my exchange -----------------------------------------------------------

    @Test
    fun `my sent exchange is upstream's default 5NN and 1002H`() {
        // Ini.pas:192 `ExchDefault: '5NN 1002H'`; `Acag.pas` overrides no message.
        assertEquals(OperatorExchange("5NN", "1002H"), acag().operatorExchange())
        assertEquals(
            mapOf("rst" to "599", "city" to "1430H"),
            acag().typedFields(entry()),
            "box 1 is the RST, box 2 is the city/gun/ku code",
        )
    }

    @Test
    fun `the contest overrides no message, so upstream's base wording is used`() {
        // `Acag.pas` has no `SendMsg` override at all (there is no `SendMsg` in the file), so
        // `messages: {}` is the faithful document and the base templates apply.
        assertTrue(config("acag").messages.isEmpty(), "ACAG overrides no wording")
        val script = MessageScript(acag().operator(), config("acag").messages)
        assertEquals("CQ W1AW TEST", script.text(QsoMessage.CQ))
        assertEquals("TU", script.text(QsoMessage.TU), "upstream's base TU is the bare word")
        assertEquals("NR?", script.text(QsoMessage.NR_QM))
        assertEquals("5NN 1002H", script.text(QsoMessage.EXCHANGE))
    }

    // --- entry validation ------------------------------------------------------

    @Test
    fun `the typed entry is validated against the contest document`() {
        val rules = acag()
        assertTrue(rules.validate(entry()).isEmpty(), "a full exchange is valid")
        assertTrue(rules.validate(entry(exch1 = "5NN")).isEmpty(), "CW letters are legal in an RST")
        assertTrue(rules.validate(entry(exch2 = "01039M")).isEmpty(), "a 5-digit gun code fits in 7")
        assertTrue(rules.validate(entry(exch2 = "100115M")).isEmpty(), "a 6-digit ku code fits in 7")
        assertTrue(rules.validate(entry(exch2 = "1430h")).isEmpty(), "case is normalised")
        assertTrue(rules.validate(entry(exch1 = "")).isNotEmpty(), "a report is required")
        assertTrue(rules.validate(entry(exch2 = "")).isNotEmpty(), "a code is required")
        assertTrue(rules.validate(entry(exch2 = "1430")).isNotEmpty(), "the power letter is required")
        assertTrue(rules.validate(entry(exch2 = "1430X")).isNotEmpty(), "'X' is not a power letter")
        // ACAG's box is seven characters, not ALL JA's four: `L: 7` (`ExchFields.pas:63`).
        assertTrue(rules.validate(entry(exch2 = "1001156M")).isNotEmpty(), "a code is up to seven characters")
        assertTrue(rules.validate(entry(exch2 = "1001M")).isEmpty(), "four characters still fit")
    }

    // --- the save-time verdict -------------------------------------------------

    @Test
    fun `a correct copy logs with no errors`() {
        val verdict = acag().judge(entry(), truth())
        assertTrue(verdict.errors.isEmpty(), "unexpected errors ${verdict.errors}")
        assertTrue(verdict.corrections.isEmpty())
    }

    @Test
    fun `a wrong code is NR and a wrong report is RST, as upstream labels them`() {
        // Log.pas:943 `etJaCity: if TrueExch2 <> Exch2 then Exch2Error := leNR` — the line after
        // ALL JA's `etJaPref`, with the same label and not ST/QTH.
        // Log.pas:859 `etRST: if TrueRst <> Rst then Exch1Error := leRST`.
        val wrongCode = acag().judge(entry(exch2 = "1431H"), truth())
        assertEquals(listOf(QsoError.Number), wrongCode.errors, "ACAG labels its second field NR")
        assertEquals(listOf("1430H"), wrongCode.corrections)

        val wrongRst = acag().judge(entry(exch1 = "579"), truth())
        assertEquals(listOf(QsoError.Rst), wrongRst.errors)
        assertEquals(listOf("599"), wrongRst.corrections)
    }

    @Test
    fun `a copy that differs only in case is accepted`() {
        // `ContestRules.sameValue` compares TEXT after upper-casing, as upstream's log does.
        val verdict = acag().judge(entry(exch2 = "1430h"), truth())
        assertTrue(verdict.errors.isEmpty(), "case must not be an error: ${verdict.errors}")
    }

    // --- scoring --------------------------------------------------------------

    @Test
    fun `one point per contact and the city code is the multiplier`() {
        // Acag.pas:207 `Qso^.Points := 1;` and `:191-207` the LMHP strip.
        assertEquals(ScoreResult(1, setOf("1430")), acag().score(entry(), truth()))
        assertEquals(
            ScoreResult(1, setOf("100115")),
            acag().score(entry("7K1BIB", "599", "100115M"), truth("7K1BIB", "599", "100115M")),
        )
        // A leading zero is part of the code: `01039`, never `1039`.
        assertEquals(
            ScoreResult(1, setOf("01039")),
            acag().score(entry("7K1AEU/8", "599", "01039M"), truth("7K1AEU/8", "599", "01039M")),
        )
        // The last character is not a power letter: the whole string is the multiplier.
        assertEquals(
            ScoreResult(1, setOf("2708")),
            acag().score(entry("K1ABC", "599", "2708"), truth("K1ABC", "599", "2708")),
        )
    }

    @Test
    fun `a hand-computed contest scores four points times three multipliers`() {
        // 7K1CPT/1430H, 7K1CRO/1430H (duplicate multiplier), 7K1AEU/8/01039M, K1ABC/2708:
        // 1+1+1+1 points; {1430, 01039, 2708} = 3 multipliers → 12. The leading zero keeps
        // `01039` its own multiplier instead of colliding with a `1039` one.
        val rules = acag()
        val log = QsoLog()
        val contacts = listOf(
            Triple("7K1CPT", "599", "1430H"),
            Triple("7K1CRO", "599", "1430H"),
            Triple("7K1AEU/8", "599", "01039M"),
            Triple("K1ABC", "599", "2708"),
        )
        contacts.forEachIndexed { index, (call, rst, code) ->
            val score = rules.score(entry(call, rst, code), truth(call, rst, code))
            assertIs<LogOutcome.Accepted>(
                log.record(
                    QsoContact(
                        call = call,
                        exch1 = rst,
                        exch2 = code,
                        score = score,
                        elapsedMs = 1_000L * (index + 1),
                    ),
                ),
            )
        }
        assertEquals(4, log.size)
        assertEquals(4, log.rawPointsTotal)
        assertEquals(3, log.rawMultiplierCount, "1430H is counted once; 01039M once; 2708 once")
        assertEquals(12, log.rawScore)
    }
}
