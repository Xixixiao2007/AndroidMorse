package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.SeededRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The station-side operator state machine, ported from `DxOper.pas` (T6).
 *
 * The callsign-matching table is the upstream golden suite, transcribed verbatim from
 * `Test/DxOperTest.pas` (180 cases, categories `Wild`, `1x1`..`2x3/9`, `FY/...`): call,
 * entered call, expected result, and — where the Pascal states it — the expected confidence
 * percentage. It pins an edit-distance algorithm, a wildcard rule and a substring fallback
 * against the reference implementation's own expected values, which is the strongest evidence
 * available for a transcription of this size.
 *
 * The state-machine tests cite the `DxOper.pas` lines they mirror. Branches that consume a
 * random draw are exercised twice: deterministically (single-call mode, or a fixed draw) and as
 * a distribution over seeds, so a transcription that silently stopped drawing would fail.
 */
class DxOperatorTest {

    /** `call, entered call, expected` — transcribed from `Test/DxOperTest.pas`. */
    private val goldenCases: List<Triple<String, String, String>> = """
AA0AA, W?, N;0
AA0AA, W?, N;0
AA0AA, AA0?, P;60
AA0AA, AA0??, P;60
AA0AA, ??0??, P;20
AA0AA, ??0AA, P;60
AA0AA, A?0?A, P;60
AA0AA, ?A0A?, P;60
AA0AA, A?0, P;40
AA0AA, A?0?, P;40
AA0AA, AA, P;40
AA0AA, AA?, P;40
AA0AA, A, P;20
AA0AA, A?, P;20
AA0AA, ?, P;2
AA0AA, ??, P;2
AA0AA, ???, P;2
A0A, ????, P;2
AA0AA, W?, N;0
AA0AA, W7?, N
AA0AA, W7S?, N
AA0AA, W7SS?, N
AA0AA, W7SST?, N
AA0AA, W7SST?, N
4U73B, ?, P;2
, ?, N;0
W7S, W7S, Y;100
W7S, A0X, N
W7S, W7, P;66
W7S, W7X, P;66
W7S, W7XX, N;0
W7S, W7XXX, N;0
W7S, W6S, P;66
W7S, W6X, N;0
W7S, A7S, P;66
W7S, W7SSS, N;0
W7S, W7S, Y;100
W7S, W7S, Y;100
W7SS, W7SS, Y
W7SS, W7S, P;75
W7SS, W7, P;50
W7SS, W7SST, P;75
W7SS, A7SS, P;75
W7SS, A7SST, N;0
W7SS, S, P;25
W7SS, SS, P;50
W7SS, 7SS, P;75
W7SS, W7SS, Y;100
W7AU, W7AB, P;75
W7SST, W7SST, Y
W7SST, W7SSTT, P;80
W7SST, W7, P;40
W7SST, W7S, P;60
W7SST, W7SS, P;80
W7SST, W7SSS, P;80
W7SST, A7SST, P;80
W7SST, W7ABC, N
W7SST, T, P;20
W7SST, ST, P;40
W7SST, SST, P;60
W7SST, 7SS, P;60
W7SST, W7XST, P;80
W7SST, W7??T, P;60
W7SST/9, W7SST/9, Y
W7SST/9, W7SSTT/9, P;85
W7SST/9, W7SST/8, P;85
W7SST/9, W7, P;28
W7SST/9, W7S, P;42
W7SST/9, W7SS, P;57
W7SST/9, W7SSS, P;57
W7SST/9, A7SST, P;57
W7SST/9, W7ABC, N
W7SST/9, T, P;14
W7SST/9, ST, P;28
W7SST/9, SST, P;42
W7SST/9, 7SS, P;42
W7SST/9, W7XST, P;57
W7SST/9, W7??T, P;42
W7SST/9, W7??T?, P;42
W7SST/9, W7??T/?, P;57
W7SST/9, W7SST?, P;71
W7ABU/9, W7AB, P;57
AA0AA, AA0AA, Y;100
AA0AA, AA0A, P;80
AA0AA, AA0, P;60
AA0AA, AA, P;40
AA0AA, A, P;20
AA0AA, A0AA, P;80
AA0AA, 0AA, P;60
AA0AA, AA, P;40
AA0AA, A, P;20
AA0AA, A0A, P;60
AA0AA, 0A, P;40
AA0AA, A0, P;40
AA0AA, AA7AA, P;80
AA0AA, AA7BB, N
AA0AA, AA7BBB, N
AA0AA, AB7CD, N
AA0AA, AA0AA/7, P;60
AA0AA, FY/AA0AA, N
AA0AA, FY, N
FY/AA0AA, FY, P;25
FY/AA0AA, FY/, P;37
FY/AA0AA, FY/AA, P;62
WN7SST, WN7SST, Y
WN7SST, WN, P;33
WN7SST, WN7, P;50
WN7SST, WN7S, P;66
WN7SST, WN7SS, P;83
WN7SST, WN7SSS, P;83
WN7SST, AN7SST, P;83
WN7SST, WN7ABC, N;0
WN7SST, T, P;16
WN7SST, ST, P;33
WN7SST, SST, P;50
WN7SST, 7SS, P;50
WN7SST, WN7XST, P;83
WN7SST, WN7??T, P;66
WN7SST, W7SST, P;83
WN7SST, W7ST, P;66
WN7SST, WN7ST, P;83
WN7SST, W7AB, N
WN7SST, W7ABC, N
WN7SST, 7, P;16
FY/WN7SST, FY/WN7SST, Y
FY/WN7SST, FY/WN7SST, Y
FY/WN7SST, WN, P;22
FY/WN7SST, WN7, P;33
FY/WN7SST, WN7S, P;44
FY/WN7SST, WN7SS, P;55
FY/WN7SST, WN7SSS, P;55
FY/WN7SST, AN7SST, P;55
FY/WN7SST, WN7ABC, N
FY/WN7SST, T, P;11
FY/WN7SST, ST, P;22
FY/WN7SST, SST, P;33
FY/WN7SST, 7SS, P;33
FY/WN7SST, WN7XST, P;55
FY/WN7SST, WN7??T, P;44
FY/WN7SST, W7SST, P;55
FY/WN7SST, W7ST, N
FY/WN7SST, WN7ST, P;55
FY/WN7SST, W7AB, N
FY/WN7SST, W7ABC, N
FY/WN7SST, FY, P;22
FY/WN7SST, FY?, P;22
FY/WN7SST, FY/WN7, P;66
FY/WN7SST, FY/, P;33
FY/WN7SST, FY/W?7, P;55
FY/WN7SST, FX/W7SST, P;77
WN7SST/9, WN7SST/9, Y
WN7SST/9, WN, P;25
WN7SST/9, WN7, P;37
WN7SST/9, WN7S, P;50
WN7SST/9, WN7SS, P;62
WN7SST/9, WN7SSS, P;62
WN7SST/9, AN7SST, P;62
WN7SST/9, WN7ABC, N
WN7SST/9, T, P;12
WN7SST/9, ST, P;25
WN7SST/9, SST, P;37
WN7SST/9, 7SS, P;37
WN7SST/9, WN7XST, P;62
WN7SST/9, WN7??T, P;50
WN7SST/9, W7SST, P;62
WN7SST/9, W7ST, N
WN7SST/9, WN7ST, P;62
WN7SST/9, W7AB, N
WN7SST/9, W7ABC, N
WN7SST/9, FY, N
WN7SST/9, FY?, N
WN7SST/9, /9, P;25
WN7SST/9, 9, P;12
WN7SST/9, 6, N
WN7SST/9, 7, P;12
WN7SST/9, T/9, P;37
WN7SST/9, SST/9, P;62
WN7SST/9, 7SST/9, P;75
WN7SST/9, WN7?/9, N
WN7SST/9, WN7???/9, P;62
    """.trimIndent().lines().filter { it.isNotBlank() }.map { line ->
        val parts = line.split(", ", limit = 3)
        Triple(parts[0], parts[1], parts[2])
    }

    private fun operator(
        call: String,
        seed: Long = 7,
        lids: Boolean = false,
        runMode: RunMode = RunMode.PILE_UP,
        flavour: OperatorFlavour = OperatorFlavour.GENERIC,
    ) = DxOperator(call, random = SeededRandom(seed), lids = lids, runMode = runMode, flavour = flavour)

    private fun expected(value: String): CallCheck = when (value) {
        "N" -> CallCheck.NO
        "Y" -> CallCheck.YES
        "P" -> CallCheck.ALMOST
        else -> error("unknown expected result '$value'")
    }

    @Test
    fun `the upstream call-matching golden table holds, confidence included`() {
        assertEquals(180, goldenCases.size, "the transcribed table lost cases")

        for ((call, entered, expectedText) in goldenCases) {
            val parts = expectedText.split(";")
            val op = operator(call)
            val result = op.isMyCall(entered, randomResult = false)
            assertEquals(expected(parts[0]), result, "call '$call', entered '$entered'")
            if (parts.size > 1) {
                assertEquals(
                    parts[1].trim().toInt(),
                    op.callConfidence,
                    "confidence for call '$call', entered '$entered'",
                )
            }
        }
    }

    @Test
    fun `an empty operator call never matches, even a bare wildcard`() {
        // Upstream's own expectation for `'' / '?'` is `N;0` (DxOperTest Wild.18). The Pascal
        // would divide by the empty call's length in the wildcard branch; this port fails
        // closed with No/0 instead of throwing.
        val op = operator("")
        assertEquals(CallCheck.NO, op.isMyCall("?", randomResult = false))
        assertEquals(0, op.callConfidence)
    }

    @Test
    fun `a repeated lookup is answered from the cache`() {
        // DxOper.pas:416-420: `if LastCheckedCall = APattern then Result := LastCallCheck`.
        val op = operator("W7SST", seed = 99, lids = true)
        val first = op.isMyCall("W7SST", randomResult = true)
        val confidence = op.callConfidence
        repeat(5) {
            assertEquals(first, op.isMyCall("W7SST", randomResult = true))
            assertEquals(confidence, op.callConfidence)
        }
    }

    @Test
    fun `only the best partial match answers when two callers are close`() {
        // DxOper.pas:531-545 CallConfidenceCheck + StnColl.pas:114-137.
        val exact = operator("K7AA")
        val partial = operator("K7AB")
        assertEquals(CallCheck.YES, exact.isMyCall("K7AA", randomResult = false))
        assertEquals(CallCheck.ALMOST, partial.isMyCall("K7AA", randomResult = false))

        val best = exact.callConfidence
        // The exact match (100) is the bar: the 75 % partial caller is told to stand by.
        assertEquals(CallCheck.NO, partial.callConfidenceCheck("K7AA", false, best))
        // With a lower bar the partial caller is allowed to answer.
        assertEquals(CallCheck.ALMOST, partial.callConfidenceCheck("K7AA", false, partial.callConfidence))
    }

    @Test
    fun `a lid rejects the correct call about one time in a hundred`() {
        // DxOper.pas:508-527: `mcYes: if Random < 0.01 then mcAlmost`.
        var rejected = 0
        val runs = 2_000
        repeat(runs) { seed ->
            val op = operator("W7SST", seed = seed.toLong(), lids = true)
            if (op.isMyCall("W7SST", randomResult = true) == CallCheck.ALMOST) rejected++
        }
        assertTrue(rejected in 5..45, "LID rejected $rejected of $runs correct calls")
    }

    // --- state machine ------------------------------------------------------------

    @Test
    fun `entering need-qso grants the patience the ghosting fix introduced`() {
        // DxOper.pas:352-354: `Patience := 3 + Round(RndRayleigh(3))`.
        val op = operator("K5ZD")
        op.setState(OperatorState.NEED_QSO)
        assertTrue(op.patience >= 3, "patience ${op.patience}")
        // Every other state gets FULL_PATIENCE.
        op.setState(OperatorState.NEED_END)
        assertEquals(DxOperator.FULL_PATIENCE, op.patience)
        op.setState(OperatorState.DONE)
        assertEquals(DxOperator.FULL_PATIENCE, op.patience)
    }

    @Test
    fun `a caller nobody answers fails, but a started qso only ghosts`() {
        // DxOper.pas:251-262: patience below 1 sets osFailed only in the two "not started
        // yet" states; an in-progress QSO keeps its state and stops transmitting.
        val neverStarted = operator("K5ZD")
        neverStarted.setState(OperatorState.NEED_QSO)
        var guard = 0
        while (neverStarted.state != OperatorState.FAILED && guard++ < 64) neverStarted.decPatience()
        assertEquals(OperatorState.FAILED, neverStarted.state)
        assertTrue(neverStarted.isGhosting)

        val inProgress = operator("K5ZD")
        inProgress.setState(OperatorState.NEED_NR)
        repeat(10) { inProgress.msgReceived(emptySet(), MatchContext()) } // timeouts
        assertEquals(OperatorState.NEED_NR, inProgress.state)
        assertTrue(inProgress.isGhosting)
    }

    @Test
    fun `a ghosting station still reaches osDone when the operator finally sends TU`() {
        // DxOper.pas:159-164: a ghosting station "can still receive messages".
        val op = operator("K5ZD")
        op.setState(OperatorState.NEED_NR)
        repeat(10) { op.msgReceived(emptySet(), MatchContext()) }
        assertTrue(op.isGhosting)

        op.msgReceived(
            setOf(StationMessage.TU),
            MatchContext(hisCall = "K5ZD", bestMatchConfidence = 100, bestMatchCallsign = "K5ZD"),
        )
        assertEquals(OperatorState.DONE, op.state)
    }

    @Test
    fun `a correct callsign moves the operator to waiting for my exchange`() {
        // DxOper.pas:583-596: mcYes in osNeedQso -> osNeedNr.
        val op = operator("K5ZD")
        op.setState(OperatorState.NEED_QSO)
        op.msgReceived(
            setOf(StationMessage.HIS_CALL),
            MatchContext(hisCall = "K5ZD", bestMatchConfidence = 100, bestMatchCallsign = "K5ZD"),
        )
        assertEquals(OperatorState.NEED_NR, op.state)

        // A partial callsign asks for the call and exchange together.
        val partial = operator("K5ZD")
        partial.setState(OperatorState.NEED_QSO)
        partial.msgReceived(
            setOf(StationMessage.HIS_CALL),
            MatchContext(hisCall = "K5Z", bestMatchConfidence = 60, bestMatchCallsign = "K5ZD"),
        )
        assertEquals(OperatorState.NEED_CALL_NR, partial.state)
    }

    @Test
    fun `my exchange advances an operator that already has my call`() {
        // DxOper.pas:612-626: osNeedNr + msgNR -> osNeedEnd. Single-call mode makes the branch
        // deterministic; the 90 % rate itself is asserted separately.
        val op = operator("K5ZD", runMode = RunMode.SINGLE_CALL)
        op.setState(OperatorState.NEED_NR)
        op.msgReceived(setOf(StationMessage.EXCHANGE), MatchContext())
        assertEquals(OperatorState.NEED_END, op.state)
    }

    @Test
    fun `an exchange advances the qso about nine times in ten`() {
        // The draw is consumed in every mode (DxOper.pas:616), so this also proves the branch
        // still draws rather than always taking the lucky path.
        var advanced = 0
        val runs = 1_000
        repeat(runs) { seed ->
            val op = operator("K5ZD", seed = seed.toLong())
            op.setState(OperatorState.NEED_NR)
            op.msgReceived(setOf(StationMessage.EXCHANGE), MatchContext())
            if (op.state == OperatorState.NEED_END) advanced++
        }
        assertTrue(advanced in 850..950, "advanced $advanced of $runs times")
    }

    @Test
    fun `TU completes the qso from need-end but restarts it from need-qso`() {
        // DxOper.pas:628-642.
        val atEnd = operator("K5ZD")
        atEnd.setState(OperatorState.NEED_END)
        atEnd.msgReceived(setOf(StationMessage.TU), MatchContext())
        assertEquals(OperatorState.DONE, atEnd.state)

        val atStart = operator("K5ZD")
        atStart.setState(OperatorState.NEED_QSO)
        atStart.msgReceived(setOf(StationMessage.TU), MatchContext())
        assertEquals(OperatorState.NEED_QSO, atStart.state)
    }

    @Test
    fun `NIL fails a qso that has started`() {
        // DxOper.pas:573-581.
        val op = operator("K5ZD")
        op.setState(OperatorState.NEED_END)
        op.msgReceived(setOf(StationMessage.NIL), MatchContext())
        assertEquals(OperatorState.FAILED, op.state)
    }

    @Test
    fun `a message without a state change keeps the station around`() {
        // DxOper.pas:290-305 MorePatience + the trailing DecPatience (DxOper.pas:671):
        // from patience 1 the `?` raises it to 3 and the trailing decrement leaves 2.
        val op = operator("K5ZD")
        op.setState(OperatorState.NEED_NR)
        repeat(4) { op.decPatience() }
        assertEquals(1, op.patience)
        op.msgReceived(setOf(StationMessage.QM), MatchContext())
        assertEquals(OperatorState.NEED_NR, op.state)
        assertEquals(2, op.patience)
    }

    @Test
    fun `a pile-up station repeats its reply twice about one time in ten`() {
        // DxOper.pas:356-358.
        var doubled = 0
        val runs = 1_000
        repeat(runs) { seed ->
            val op = operator("K5ZD", seed = seed.toLong(), runMode = RunMode.PILE_UP)
            op.setState(OperatorState.NEED_QSO)
            if (op.repeatCount == 2) doubled++
        }
        assertTrue(doubled in 70..130, "repeat count doubled $doubled of $runs times")
    }

    @Test
    fun `single-call mode never repeats`() {
        repeat(200) { seed ->
            val op = operator("K5ZD", seed = seed.toLong(), runMode = RunMode.SINGLE_CALL)
            op.setState(OperatorState.NEED_QSO)
            assertEquals(1, op.repeatCount)
        }
    }

    // --- replies and timing --------------------------------------------------------

    @Test
    fun `the reply comes from the state and the exchange`() {
        // DxOper.pas:675-746.
        val waitingForCall = operator("K5ZD")
        waitingForCall.setState(OperatorState.NEED_QSO)
        assertEquals(StationMessage.MY_CALL, waitingForCall.getReply())

        // ARRL SS sends `R <exchange>` deterministically (the generic branch draws).
        val atEnd = operator("K5ZD", flavour = OperatorFlavour.ARRL_SS)
        atEnd.setState(OperatorState.NEED_END)
        assertEquals(StationMessage.R_EXCHANGE, atEnd.getReply())

        val impatient = operator("K5ZD")
        impatient.setState(OperatorState.NEED_END)
        impatient.decPatience()
        impatient.decPatience() // Patience below FULL_PATIENCE - 1
        assertEquals(StationMessage.EXCHANGE, impatient.getReply())

        val done = operator("K5ZD")
        done.setState(OperatorState.DONE)
        assertEquals(StationMessage.NONE, done.getReply())
    }

    @Test
    fun `a generic station sends R with its exchange nine times in ten`() {
        // DxOper.pas:739-745: the `Random < 0.9` draw happens only in the generic branch.
        var plain = 0
        val runs = 1_000
        repeat(runs) { seed ->
            val op = operator("K5ZD", seed = seed.toLong())
            op.setState(OperatorState.NEED_END)
            if (op.getReply() == StationMessage.R_EXCHANGE) plain++
        }
        assertTrue(plain in 850..950, "R <exch> sent $plain of $runs times")
    }

    @Test
    fun `a station waiting for the previous qso has no send delay`() {
        // DxOper.pas:180-181: osNeedPrevEnd -> NEVER.
        val op = operator("K5ZD")
        assertEquals(DxOperator.NEVER, op.getSendDelay(AudioBlocks()))
    }

    @Test
    fun `a send delay is 0_1 to 0_6 seconds in audio blocks`() {
        // DxOper.pas:185: `SecondsToBlocks(0.1 + 0.5*Random)`.
        val blocks = AudioBlocks()
        val op = operator("K5ZD", seed = 123)
        op.setState(OperatorState.NEED_QSO)
        repeat(200) {
            val delay = op.getSendDelay(blocks)
            val seconds = blocks.blocksToSeconds(delay)
            assertTrue(seconds in 0.05..0.65, "send delay ${seconds}s is outside 0.1..0.6s")
        }
    }

    @Test
    fun `a reply timeout is six minus skills seconds, jittered around that mean`() {
        // DxOper.pas:234-241: `SecondsToBlocks(6-Skills)` then `RndGaussLim(d, d/2)`.
        val blocks = AudioBlocks()
        repeat(50) { seed ->
            val op = operator("K5ZD", seed = seed.toLong())
            op.setState(OperatorState.NEED_QSO)
            val base = blocks.secondsToBlocks((6 - op.skills).toDouble())
            val timeout = op.getReplyTimeout(blocks)
            assertTrue(
                timeout in (base / 2)..(base * 3 / 2 + 1),
                "timeout $timeout blocks is outside ${base / 2}..${base * 3 / 2} for skills ${op.skills}",
            )
        }
    }

    @Test
    fun `the audio block clock matches upstream's 11025 Hz over 512 samples`() {
        // Ini.pas:24,244 + RndFunc.pas:61-69.
        val blocks = AudioBlocks()
        assertEquals(11_025.0 / 512.0, blocks.blocksPerSecond, 1e-9)
        assertEquals(22, blocks.secondsToBlocks(1.0))
        assertEquals(1.0, blocks.blocksToSeconds(22), 0.03)
    }

    @Test
    fun `the same seed replays the same operator`() {
        // Determinism is the replay contract: every draw comes from SeededRandom.
        fun script(): List<Int> {
            val op = DxOperator("W7SST", random = SeededRandom(2026), lids = true)
            op.setState(OperatorState.NEED_QSO)
            val blocks = AudioBlocks()
            return listOf(
                op.patience,
                op.skills,
                op.repeatCount,
                op.getSendDelay(blocks),
                op.getReplyTimeout(blocks),
                op.getSendDelay(blocks),
                op.getReplyTimeout(blocks),
            )
        }
        assertEquals(script(), script())
    }
}
