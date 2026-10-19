package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.AssetCallFacts
import com.bh4gzk.morserunner.contest.CallHistoryFile
import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLoad
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.contest.RosterSettings
import com.bh4gzk.morserunner.contest.RosterStation
import com.bh4gzk.morserunner.contest.SeededRandom
import com.bh4gzk.morserunner.contest.StationRoster
import com.bh4gzk.morserunner.data.DxccList
import com.bh4gzk.morserunner.data.MasterDta
import com.bh4gzk.morserunner.expr.CallFacts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The T6 acceptance test: **config → call history → roster → station operator → my machine →
 * log → score**, with the real shipped documents.
 *
 * Every layer is a product of the ticket: the contest document and its call-history spec (T3),
 * the seeded roster (T5), the call-history reader (this session), the station operator
 * (`DxOperator`), the operator's own state machine ([QsoMachine], T6) and the log's verdict +
 * scoring ([ContestRules], T6). A QSO is worked exactly the way the UI will drive it:
 * `Enter` with an empty call sends CQ; `Enter` with the call sends `<his> <#>`;
 * `Enter` with the copied exchange sends `TU` and logs.
 */
class T6ContractTest {

    // --- fixtures ------------------------------------------------------------------

    /** A two-row DXCC fixture, labelled as such: not a DXCC.LIST conversion. */
    private val dxcc = DxccList.parse(
        """
        K;K|W|N|A[A-GI-K];United States of America;NA;8;3,4,5;291
        DL;(D[A-R])|(Y[2-9]);Fed. Rep. of Germany;EU;28;14;230
        """.trimIndent(),
    )

    private val facts: CallFacts = AssetCallFacts(dxcc)

    private fun config(id: String): ContestConfig {
        val result = ContestLibrary.load(id)
        assertIs<LoadResult.Success>(result, "$id did not load: $result")
        return result.config
    }

    private fun history(
        config: ContestConfig,
        text: String,
        myCall: String = "",
        calls: CallFacts = CallFacts.Unavailable,
    ): CallHistoryFile {
        val result = CallHistoryFile.parse(text, config.callHistory!!, myCall, calls)
        return assertIs<HistoryLoad.Loaded>(result, "history was rejected: $result").history
    }

    /** A real-layout MASTER.DTA holding exactly [calls] (`core-contest`'s test helper, copied). */
    private fun poolOf(vararg calls: String): MasterDta {
        val body = calls.joinToString(separator = "") { "$it\u0000" }.toByteArray(Charsets.US_ASCII)
        val size = MasterDta.INDEX_BYTES + body.size
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val index = IntArray(MasterDta.INDEX_ENTRIES)
        index[0] = MasterDta.INDEX_BYTES
        index[MasterDta.INDEX_ENTRIES - 1] = size
        index.forEach { buffer.putInt(it) }
        body.copyInto(bytes, MasterDta.INDEX_BYTES)
        return MasterDta.parse(bytes)
    }

    private fun match(station: RosterStation) = MatchContext(
        hisCall = station.call,
        bestMatchConfidence = 100,
        bestMatchCallsign = station.call,
    )

    private fun drain(machine: QsoMachine) {
        while (machine.pending.isNotEmpty()) machine.onMyMessageSent()
    }

    /** Everything one worked contact left behind, comparable so two seeded runs can be equal. */
    private data class Session(
        val stationCall: String,
        val stationExch1: String,
        val stationExch2: String,
        val operatorStates: List<OperatorState>,
        val outcome: LogOutcome,
        val errors: List<QsoError>,
        val points: Int,
        val multipliers: Set<String>,
        val rawPoints: Int,
        val rawMultipliers: Int,
        val rawScore: Int,
        val verifiedPoints: Int,
        val verifiedMultipliers: Int,
        val verifiedScore: Int,
        val histogram: List<Int>,
    )

    // --- the flows ------------------------------------------------------------------

    /**
     * CQ WW: one station from the roster, heard answering my CQ, worked to `TU`.
     *
     * @param typedExch1 overrides what the operator copies as exchange piece 1 (the RST), so a
     *   mis-copied contact can be exercised through the same driver.
     */
    private fun workCqww(seed: Long, typedExch1: ((RosterStation) -> String)? = null): Session {
        val cqww = config("cqww")
        val callHistory = history(cqww, "DL1ABC,14,\n")
        val roster = StationRoster(cqww, poolOf("DL1ABC"), callHistory, RosterSettings(speedWpm = 30), seed, facts)
        val station = assertNotNull(roster.add(), "the roster produced no station")

        val rules = ContestRules(cqww, MyStation("W1AW", mapOf("rst" to "599", "zone" to "5")), facts)
        val machine = QsoMachine(MessageScript(rules.operator(), cqww.messages), rules)
        val operator = DxOperator(station.call, random = SeededRandom(seed + 1), runMode = RunMode.PILE_UP)
        val states = mutableListOf<OperatorState>()

        // 1. Enter with an empty call: CQ. The station hears it and wants to be called.
        machine.updateEntry(QsoEntry())
        assertEquals(listOf(QsoMessage.CQ), machine.enter().messages)
        drain(machine)
        operator.msgReceived(setOf(StationMessage.CQ), MatchContext())
        states += operator.state
        assertEquals(StationMessage.MY_CALL, operator.getReply())
        assertTrue(operator.getSendDelay(AudioBlocks()) in 1..20)

        // 2. Type the call, Enter: `<his> <#>`.
        machine.updateEntry(QsoEntry(call = station.call))
        val answer = machine.enter()
        assertEquals(listOf(QsoMessage.HIS_CALL, QsoMessage.EXCHANGE), answer.messages)
        assertEquals(station.call, answer.sentText[0])
        assertEquals("599 5", answer.sentText[1])
        drain(machine)
        operator.msgReceived(setOf(StationMessage.HIS_CALL, StationMessage.EXCHANGE), match(station))
        states += operator.state

        // 3. Copy the exchange, Enter: TU and the contact is logged.
        val typed = QsoEntry(
            call = station.call,
            exch1 = typedExch1?.invoke(station) ?: station.exchange.exch1,
            exch2 = station.exchange.exch2,
        )
        machine.updateEntry(typed)
        val save = machine.enter()
        assertEquals(listOf(QsoMessage.TU), save.messages)
        assertTrue(save.logged, "a complete exchange must log the contact")
        drain(machine)
        operator.msgReceived(setOf(StationMessage.TU), match(station))
        states += operator.state

        return record(station, typed, rules, states)
    }

    /** ARRL SS: the entry is one four-token line (`<nr> <prec> <check> <section>`), parsed by the config. */
    private fun workArrlSs(seed: Long): Session {
        val ss = config("arrlss")
        val callHistory = history(ss, "4U1WB,MDC,DC,89,\n")
        val roster = StationRoster(
            ss,
            poolOf("4U1WB"),
            callHistory,
            RosterSettings(speedWpm = 25, serialRange = 1..999),
            seed,
        )
        val station = assertNotNull(roster.add(), "the roster produced no station")

        val rules = ContestRules(
            ss,
            MyStation(
                "W1AW",
                mapOf("nr" to "12", "prec" to "B", "check" to "71", "section" to "OR"),
            ),
        )
        val machine = QsoMachine(MessageScript(rules.operator(), ss.messages), rules)
        val operator = DxOperator(
            station.call,
            random = SeededRandom(seed + 1),
            runMode = RunMode.PILE_UP,
            flavour = OperatorFlavour.ARRL_SS,
        )
        val states = mutableListOf<OperatorState>()

        machine.updateEntry(QsoEntry())
        assertEquals(listOf(QsoMessage.CQ), machine.enter().messages)
        drain(machine)
        operator.msgReceived(setOf(StationMessage.CQ), MatchContext())
        states += operator.state

        machine.updateEntry(QsoEntry(call = station.call))
        assertEquals(listOf(QsoMessage.HIS_CALL, QsoMessage.EXCHANGE), machine.enter().messages)
        drain(machine)
        operator.msgReceived(setOf(StationMessage.HIS_CALL, StationMessage.EXCHANGE), match(station))
        states += operator.state

        val typed = QsoEntry(call = station.call, exch1 = station.exchange.exch1, exch2 = station.exchange.exch2)
        machine.updateEntry(typed)
        val save = machine.enter()
        assertEquals(listOf(QsoMessage.TU), save.messages)
        assertTrue(save.logged, "a complete exchange must log the contact")
        drain(machine)
        operator.msgReceived(setOf(StationMessage.TU), match(station))
        states += operator.state

        return record(station, typed, rules, states)
    }

    private fun record(
        station: RosterStation,
        typed: QsoEntry,
        rules: ContestRules,
        states: List<OperatorState>,
    ): Session {
        val truth = station.asTruth()
        val verdict = rules.judge(typed, truth)
        val score = rules.score(typed, truth)
        val log = QsoLog()
        val outcome = log.record(
            QsoContact(
                call = typed.call,
                exch1 = typed.exch1,
                exch2 = typed.exch2,
                score = score,
                verdict = verdict,
                worked = true,
                elapsedMs = 0,
                verified = verdict.errors.isEmpty(),
            ),
        )
        return Session(
            stationCall = station.call,
            stationExch1 = station.exchange.exch1,
            stationExch2 = station.exchange.exch2,
            operatorStates = states,
            outcome = outcome,
            errors = verdict.errors,
            points = score.points,
            multipliers = score.multipliers,
            rawPoints = log.rawPointsTotal,
            rawMultipliers = log.rawMultiplierCount,
            rawScore = log.rawScore,
            verifiedPoints = log.verifiedPointsTotal,
            verifiedMultipliers = log.verifiedMultiplierCount,
            verifiedScore = log.verifiedScore,
            histogram = log.rateHistogram(),
        )
    }

    // --- assertions -----------------------------------------------------------------

    @Test
    fun `CQ WW - a roster station is called, worked, logged and scored`() {
        val session = workCqww(seed = 2026)

        assertEquals("DL1ABC", session.stationCall)
        assertEquals("599", session.stationExch1)
        // The zone comes from the call history; the 5 % leading-zero rule can prepend one.
        assertTrue(session.stationExch2.endsWith("14"), "zone piece '${session.stationExch2}'")

        // The station reached DONE (it heard the TU).
        assertEquals(OperatorState.DONE, session.operatorStates.last())

        val accepted = assertIs<LogOutcome.Accepted>(session.outcome)
        assertEquals("DL1ABC", accepted.record.call)
        assertEquals(emptyList(), session.errors, "a correctly copied exchange has no verdict")

        // CqWW.pas:258-265: another continent, not /MM, not my own country → three points.
        assertEquals(3, session.points)
        // CqWW.pas:233-255: the zone multiplier and the entity multiplier.
        assertEquals(setOf("ZN-14", "Fed. Rep. of Germany"), session.multipliers)
        assertEquals(3, session.rawPoints)
        assertEquals(2, session.rawMultipliers)
        assertEquals(6, session.rawScore)
        assertEquals(3, session.verifiedPoints)
        assertEquals(6, session.verifiedScore)
        // Spec §6: one contact in the first five-minute window.
        assertEquals(listOf(1), session.histogram)
    }

    @Test
    fun `ARRL SS - the typed four-token exchange is parsed, judged and scores its section`() {
        val session = workArrlSs(seed = 7)

        assertEquals("4U1WB", session.stationCall)
        // The station's own exchange is `<serial> <prec>` and `<check> <section>`
        // (ArrlSS.pas:437-440); the config's entryParse reads them back as one line.
        assertTrue(session.stationExch1.matches(Regex("[0-9]+ [QABUMS]")), "'${session.stationExch1}'")
        assertTrue(session.stationExch2.matches(Regex("[0-9]{2} [A-Z]{2,3}")), "'${session.stationExch2}'")

        assertEquals(OperatorState.DONE, session.operatorStates.last())
        assertIs<LogOutcome.Accepted>(session.outcome)
        assertEquals(emptyList(), session.errors)

        // ArrlSS.pas:469-470: two points, and the section is the multiplier.
        assertEquals(2, session.points)
        assertEquals(1, session.multipliers.size)
        assertEquals(session.stationExch2.substringAfter(' '), session.multipliers.single())
        assertEquals(2, session.rawPoints)
        assertEquals(2, session.rawScore)
        assertEquals(2, session.verifiedScore)
    }

    @Test
    fun `a mis-copied RST is logged with its verdict but only counts raw`() {
        // Log.pas:852-868: the RST comparison, and UpdateStats only verifies clean contacts.
        val session = workCqww(seed = 2026) { "579" }

        assertIs<LogOutcome.Accepted>(session.outcome)
        assertEquals(listOf(QsoError.Rst), session.errors)
        // The raw score counts what was typed; the verified score does not count it.
        assertEquals(3, session.rawPoints)
        assertEquals(6, session.rawScore)
        assertEquals(0, session.verifiedPoints)
        assertEquals(0, session.verifiedScore)
    }

    @Test
    fun `a call that does not match the station is a CALL verdict`() {
        // Log.pas:1015-1021: `TrueCall <> Call` is leCALL with the true call as a correction.
        val cqww = config("cqww")
        val rules = ContestRules(cqww, MyStation("W1AW", mapOf("rst" to "599", "zone" to "5")), facts)
        val entry = QsoEntry(call = "DL1ABX", exch1 = "599", exch2 = "14")
        val verdict = rules.judge(entry, QsoTruth("DL1ABC", "599", "14"))

        assertEquals(listOf(QsoError.Call), verdict.errors)
        assertEquals(listOf("DL1ABC"), verdict.corrections)
    }

    @Test
    fun `a contact that was never worked is NIL, and a repeated call is DUP`() {
        // Log.pas:1012-1025: `TrueCall = ''` is leNIL; an existing clean call is leDUP.
        val log = QsoLog()
        val score = ScoreResult(0, emptySet())

        assertEquals(
            LogOutcome.Nil,
            log.record(QsoContact("", "", "", score, worked = false, elapsedMs = 0)),
        )
        assertEquals(0, log.size)

        val first = QsoContact("W1AW", "599", "5", ScoreResult(3, setOf("ZN-5")), elapsedMs = 0)
        assertIs<LogOutcome.Accepted>(log.record(first))
        assertEquals(LogOutcome.Duplicate, log.record(first.copy(call = "  w1aw ")))
        assertEquals(1, log.size)
    }

    @Test
    fun `the same seeds replay the same session`() {
        // Determinism is the replay contract: the roster draws and the operator draws are both
        // seeded, so a disputed contact can be re-run and inspected.
        assertEquals(workCqww(seed = 4242), workCqww(seed = 4242))
        assertEquals(workArrlSs(seed = 4242), workArrlSs(seed = 4242))
    }

    @Test
    fun `the operator's audio clock is the one the app's audio engine will use`() {
        // The block count is only meaningful with its clock (DxOper.pas:177-186);
        // upstream's 11025/512 is the default, and a 48 kHz engine can pass its own.
        val upstream = AudioBlocks()
        val android = AudioBlocks(sampleRate = 48_000, samplesPerBlock = 512)
        assertTrue(android.blocksPerSecond > upstream.blocksPerSecond)
        assertEquals(1.0, android.blocksToSeconds(android.secondsToBlocks(1.0)), 0.01)
    }
}
