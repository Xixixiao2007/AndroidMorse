package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.audio.FadeSchedule
import com.bh4gzk.morserunner.audio.MorseEncoder
import com.bh4gzk.morserunner.audio.MorseTiming
import com.bh4gzk.morserunner.audio.Receiver
import com.bh4gzk.morserunner.contest.BehaviourProfile
import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.RosterSettings
import com.bh4gzk.morserunner.contest.RosterStation
import com.bh4gzk.morserunner.contest.HistoryLookup
import com.bh4gzk.morserunner.contest.SeededRandom
import com.bh4gzk.morserunner.contest.StationRoster
import com.bh4gzk.morserunner.data.CallPool
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.qso.AudioBlocks
import com.bh4gzk.morserunner.qso.CallCheck
import com.bh4gzk.morserunner.qso.ContestRules
import com.bh4gzk.morserunner.qso.DxOperator
import com.bh4gzk.morserunner.qso.LogOutcome
import com.bh4gzk.morserunner.qso.MatchContext
import com.bh4gzk.morserunner.qso.MessageScript
import com.bh4gzk.morserunner.qso.MyStation
import com.bh4gzk.morserunner.qso.Operator
import com.bh4gzk.morserunner.qso.OperatorExchange
import com.bh4gzk.morserunner.qso.OperatorFlavour
import com.bh4gzk.morserunner.qso.OperatorState
import com.bh4gzk.morserunner.qso.QsoContact
import com.bh4gzk.morserunner.qso.QsoEntry
import com.bh4gzk.morserunner.qso.QsoLog
import com.bh4gzk.morserunner.qso.QsoMachine
import com.bh4gzk.morserunner.qso.QsoMessage
import com.bh4gzk.morserunner.qso.QsoRecord
import com.bh4gzk.morserunner.qso.QrmStation
import com.bh4gzk.morserunner.qso.QsoState
import com.bh4gzk.morserunner.qso.RunMode
import com.bh4gzk.morserunner.qso.StationMessage
import com.bh4gzk.morserunner.qso.StationScript
import com.bh4gzk.morserunner.qso.asTruth
import kotlin.math.roundToLong

/**
 * One transmission the audio layer must play, in the order it is drained.
 *
 * [station] also carries what the player needs to sound right — speed, pitch, level, fade
 * (spec §6) — so the audio layer never has to look a station up in the roster.
 */
data class AudioCue(
    /** The CW text to key. */
    val text: String,
    /** The station sending it, or `null` when it is my own sending (the self-monitor). */
    val station: RosterStation?,
    /** How long it keys for, from the same timing the synthesizer uses. */
    val durationMs: Long,
)

/**
 * One transmission of the interfering station (spec §7's QRM, `QrmStn.pas`).
 *
 * A separate type from [AudioCue] on purpose: this is **not** a station in the QSO. It
 * never enters [QsoMachine], it is never the "last heard" of the station being worked, and
 * the screen plays it as its own signal. It is a steady carrier at its own pitch —
 * upstream's `TQrmStation` has no `TQsb` — so there is no fade to carry.
 */
data class QrmCue(
    val text: String,
    /** Absolute pitch in Hz: the operator's pitch plus the station's own offset. */
    val pitchHz: Int,
    val speedWpm: Int,
    /** Linear level in `[0.111, 0.667]`, as `QrmStn.pas` draws it. */
    val level: Double,
    val durationMs: Long,
)

/**
 * The RIT offset a cue is played with.
 *
 * Upstream subtracts the RIT term inside the station loop (`Contest.pas:721-731`) and mixes the
 * operator's own sidetone in afterwards, so **every received station moves and my own sending
 * does not**. That distinction is the whole of RIT in a port with no baseband: the audio layer
 * asks this function instead of deciding it, and the self-monitor is "the cue with no station".
 */
fun AudioCue.receiveOffsetHz(ritHz: Int): Int = if (station == null) 0 else ritHz

/**
 * Everything the screen draws, recomputed from the engine on demand.
 *
 * A value type, so Compose can compare two snapshots and skip a recomposition; nothing in it
 * is derived twice, and nothing here decides a rule — [ContestRules] and [QsoLog] do.
 */
data class SessionUiState(
    val contestName: String,
    val runModeLabel: String,
    val elapsedMs: Long,
    val remainingMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val qsoState: QsoState,
    val entry: QsoEntry,
    val errors: List<String>,
    /** The two entry boxes' labels, from the contest's own fields. */
    val exchangeLabels: List<String>,
    /** What the keyer still has to send, in order. */
    val pendingText: List<String>,
    val stationCall: String,
    val stationExchange: String,
    val stationSpeedWpm: Int,
    val stationPitchHz: Int,
    val stationCount: Int,
    /** The last thing the station transmitted, as text — the decoded exchange of spec §8. */
    val lastHeard: String,
    /** The outcome line for the operator (logged / dup / nil / stopped). */
    val lastStatus: String,
    val qsos: Int,
    val rawScore: Int,
    val verifiedScore: Int,
    val rawPoints: Int,
    val multipliers: Int,
    val rateLastFiveMinutes: Int,
    val ratePerHour: Double,
    val suggestions: List<String>,
    val myCall: String,
    val myExchange: String,
)

/**
 * One contest session, with no Android in it.
 *
 * This is the deep module the Compose screen talks to: it owns the roster, the simulated
 * station's operator, my state machine, the log and the clock, and it answers in three ways —
 * an [AudioCue] stream (the audio layer plays and forgets), a [SessionUiState] snapshot (the
 * screen draws), and the actions ([enter], [send], [perform]).
 *
 * **The clock is passed in.** [tick] is the only source of time, so the whole session — the
 * station's reply delay, my keying length, the contest countdown — is a pure function of the
 * ticks, which is what makes a contact replayable in a JVM test. The UI runs it from a
 * `LaunchedEffect` every ~40 ms.
 *
 * **Determinism.** Two [SeededRandom] streams are created from one seed — the roster's and the
 * operators' — and nothing else may draw random numbers (spec §6, ADR-0004 decision 3). The
 * same seed and the same ticks therefore replay the same session.
 *
 * **Run mode.** T7 plays "single call" (spec §2): one station on the air at a time, reset when a
 * contact is logged. A pile-up needs a way to choose the station to work, which is T8's
 * problem, not this ticket's.
 */
class SessionController private constructor(
    private val config: ContestConfig,
    private val historyLookup: HistoryLookup?,
    private val autocompleteCalls: Collection<String>,
    private val roster: StationRoster,
    /** The contest's call pool; the interfering station is drawn from it (`PickCallOnly`). */
    private val pool: CallPool,
    private val rules: ContestRules,
    private val script: MessageScript,
    private val myCall: String,
    private val speedWpm: Int,
    /** The operator's receive pitch: the roster's centre and the QRM's reference (`RosterSettings`). */
    private val pitchHz: Int,
    /** The contest length the operator asked for; the contest's own range clamps it. */
    private val durationMinutes: Int,
    /** Whether interfering stations are scheduled at all (upstream `Ini.Qrm`). */
    private val qrmEnabled: Boolean,
    private val startMs: Long,
    private val seed: Long,
    private val log: QsoLog,
    private val stationsOnAir: Int,
) {

    companion object {
        /** Default keying speed (upstream `Ini.Wpm`). */
        const val DEFAULT_SPEED_WPM: Int = 25

        /** Upstream `Ini.Duration` (`Ini.pas:263`); the settings screen owns it from T9. */
        const val DEFAULT_DURATION_MINUTES: Int = 30

        /** How long a freshly added station waits before calling CQ. */
        const val CQ_LEAD_MS: Long = 2_000L

        /** Silence between two idle CQs. */
        const val CQ_GAP_MS: Long = 2_000L

        /** Upper bound on how many sends one tick may complete (a zero-length piece cannot loop). */
        private const val MAX_STEPS_PER_TICK: Int = 16

        /** The operators' stream is offset from the roster's so the two never align. */
        private const val OPERATOR_SEED_OFFSET: Long = 1L

        /** The contest whose operator answers with the ARRL SS wording (`OperatorFlavour`). */
        private const val ARRL_SS: String = "arrlss"

        /** The operator's pitch before T9's settings speak — `RosterSettings.pitchHz`'s default. */
        const val DEFAULT_PITCH_HZ: Int = Receiver.DEFAULT_PITCH_HZ

        /**
         * The first interfering station waits this long (`Contest.pas:695` skips the opening
         * blocks), so the opening CQ stays clean.
         */
        const val QRM_FIRST_MS: Long = 15_000L

        /**
         * The quiet stretch between two QRM episodes.
         *
         * Upstream's draw is `Random < 0.0002` per 46 ms block (`Contest.pas:716`), i.e. one
         * every ~4 minutes. T8 needs the effect audible inside a two-minute device check, so
         * the gap is 40 s ± 20 s; T9 exposes the rate as a setting and this becomes its default.
         */
        const val QRM_GAP_MS: Long = 40_000L

        /** How much the quiet stretch may vary on top of [QRM_GAP_MS]. */
        private const val QRM_GAP_JITTER_MS: Long = 20_000L

        /** The tag of the derived stream that drives the interference (see `qrmRandom`). */
        private const val QRM_STREAM: String = "qrm"

        /**
         * Builds a session.
         *
         * @param pool where station callsigns come from — the contest's call history when it
         *   has one (`CqWW.pas:141,272`), otherwise `MASTER.DTA`.
         * @param history the record lookup for the station's exchange bindings — the same
         *   call-history file, or the T5 stand-in when the contest has none.
         * @param autocompleteCalls the calls the entry box may complete from; empty when no
         *   call history is available.
         * @param durationMinutes the contest length in minutes — the operator's setting, clamped
         *   to the contest's own `duration` range. Defaults to the contest's declared default,
         *   which is the fallback for a caller with no preference.
         * @param qrm whether interfering stations are scheduled (upstream `Ini.Qrm`); when off,
         *   the QRM clock is parked at `Long.MAX_VALUE` instead of drawing an episode.
         */
        fun start(
            config: ContestConfig,
            pool: CallPool,
            history: HistoryLookup?,
            autocompleteCalls: Collection<String> = emptyList(),
            calls: CallFacts,
            myCall: String,
            myExchange: Map<String, String>,
            seed: Long,
            startMs: Long,
            speedWpm: Int = DEFAULT_SPEED_WPM,
            stationsOnAir: Int = 1,
            pitchHz: Int = DEFAULT_PITCH_HZ,
            durationMinutes: Int = config.duration.defaultMinutes,
            lids: Boolean = true,
            flutter: Boolean = true,
            qrm: Boolean = true,
            log: QsoLog = QsoLog(),
        ): SessionController {
            val rules = ContestRules(config, MyStation(myCall, myExchange), calls)
            val roster = StationRoster(
                config = config,
                pool = pool,
                history = history,
                settings = RosterSettings(
                    speedWpm = speedWpm,
                    pitchHz = pitchHz,
                    maxStations = stationsOnAir.coerceAtLeast(1),
                    lids = lids,
                    // T8: the session runs with flutter on (30 % of stations, `DxStn.pas:77-79`);
                    // T9's settings screen will expose the switch. Off is upstream's default,
                    // and a dead effect would prove nothing.
                    flutter = flutter,
                ),
                seed = seed,
                calls = calls,
            )
            val controller = SessionController(
                config = config,
                historyLookup = history,
                autocompleteCalls = autocompleteCalls,
                roster = roster,
                pool = pool,
                rules = rules,
                script = MessageScript(rules.operator, config.messages),
                myCall = myCall,
                speedWpm = speedWpm,
                pitchHz = pitchHz,
                durationMinutes = durationMinutes,
                qrmEnabled = qrm,
                startMs = startMs,
                seed = seed,
                log = log,
                stationsOnAir = stationsOnAir.coerceAtLeast(1),
            )
            controller.fillStations()
            return controller
        }

    }

    // --- engine ----------------------------------------------------------------------

    private val machine = QsoMachine(script, rules)

    private val operatorRandom = SeededRandom(seed + OPERATOR_SEED_OFFSET)

    private val audioBlocks = AudioBlocks()

    /** The clock value the last [tick] was given; actions schedule relative to it. */
    var lastNowMs: Long = startMs
        private set

    // --- the station on the air ------------------------------------------------------

    private var station: RosterStation? = null
    private var operator: DxOperator? = null
    private var stationScript: StationScript? = null

    /** The call the current transmission is addressed to (`Tst.Me.HisCall`). */
    private var addressedCall: String = ""

    /** When the idle station may call CQ again. */
    private var nextCqAt: Long = Long.MAX_VALUE

    // --- what is in flight -----------------------------------------------------------

    private var mySendEndsAt: Long? = null
    private var theirReplyAt: Long? = null
    private var theirReplyText: String = ""
    private var theirSendEndsAt: Long? = null
    private val batch = LinkedHashSet<StationMessage>()
    private val cues = ArrayDeque<AudioCue>()

    /** The station that heard my `TU` and must be replaced once the send is over. */
    private var retireStation: RosterStation? = null

    // --- the interfering station (spec §7's QRM) --------------------------------------

    /** The QRM station mid-episode, or `null` between episodes. */
    private var qrm: QrmStation? = null

    /** How many of [QrmStation.texts] it has transmitted. */
    private var qrmIndex: Int = 0

    /** When the current transmission ends; `null` between transmissions. */
    private var qrmSendEndsAt: Long? = null

    /** When the next transmission (or the next episode) is due. */
    private var qrmNextAt: Long =
        if (qrmEnabled) startMs + QRM_FIRST_MS else Long.MAX_VALUE

    private val qrmCues = ArrayDeque<QrmCue>()

    /**
     * The interference's random stream: **derived** from the session seed, never shared with
     * the roster's or the operators' streams (ADR-0007 §6: those two are the only sources of
     * truth for the session, and a third *interactive* stream would change every contact).
     * Hashing the seed means the same seed still replays the same interference.
     */
    private val qrmRandom = SeededRandom(FadeSchedule.seedFor(seed, QRM_STREAM))

    // --- operator-visible state ------------------------------------------------------

    private var entry: QsoEntry = QsoEntry()
    private var errors: List<String> = emptyList()
    private var lastHeard: String = ""
    private var lastStatus: String = ""
    private var finished: Boolean = false
    private var suggestionQuery: String? = null
    private var suggestionCache: List<String> = emptyList()

    // --- the actions -----------------------------------------------------------------

    /**
     * Advances the session clock and lets everything scheduled fire.
     *
     * The only way time passes: my keying, the station's reply delay, the station's idle CQ
     * and the contest countdown are all read from here (and never from a wall clock).
     */
    fun tick(nowMs: Long) {
        if (nowMs > lastNowMs) lastNowMs = nowMs
        if (finished) return
        if (elapsedMs() >= durationMs()) {
            finished = true
            return
        }

        var steps = 0
        while (mySendEndsAt != null && lastNowMs >= mySendEndsAt!! && steps++ < MAX_STEPS_PER_TICK) {
            mySendFinished()
        }
        if (theirReplyAt != null && lastNowMs >= theirReplyAt!!) startTheirSend()
        if (theirSendEndsAt != null && lastNowMs >= theirSendEndsAt!!) theirSendFinished()
        if (shouldCallCq()) startStationCq()
        tickQrm()
    }

    /** Plays and forgets: every cue emitted since the last drain, in transmission order. */
    fun drainCues(): List<AudioCue> {
        if (cues.isEmpty()) return emptyList()
        val drained = cues.toList()
        cues.clear()
        return drained
    }

    /**
     * Plays and forgets the interference, in transmission order.
     *
     * Deliberately a **second** stream: [drainCues] keeps exactly the contract T7 gave the
     * screen (my sending and the station being worked, in order), and nothing the QRM does
     * can reorder or delay it.
     */
    fun drainQrm(): List<QrmCue> {
        if (qrmCues.isEmpty()) return emptyList()
        val drained = qrmCues.toList()
        qrmCues.clear()
        return drained
    }

    fun editCall(text: String) = setEntry(entry.copy(call = text.uppercase()))

    fun editExchange1(text: String) = setEntry(entry.copy(exch1 = text))

    fun editExchange2(text: String) = setEntry(entry.copy(exch2 = text))

    /**
     * `Enter` — upstream's `ProcessEnter` (`Main.pas:952-1093`), which the machine transcribes.
     *
     * The controller's share is the two side effects: the queued pieces go to the keyer, and a
     * logged contact is judged, scored and handed to the log with what the station actually
     * sent (`RosterStation.asTruth`).
     */
    fun enter() {
        if (finished) return
        val typed = entry
        val outcome = machine.enter()
        entry = machine.entry
        errors = outcome.errors
        if (mySendEndsAt == null && machine.currentMessage != null) cueCurrent()
        if (outcome.logged) logContact(typed)
    }

    /** A grid button or a function key: queue one message. */
    fun send(message: QsoMessage) {
        if (finished) return
        machine.send(message)
        if (mySendEndsAt == null && machine.currentMessage != null) cueCurrent()
    }

    fun wipe() {
        machine.wipe()
        entry = machine.entry
        errors = emptyList()
        batch.clear()
        mySendEndsAt = null
        addressedCall = ""
        lastStatus = ""
    }

    /**
     * `Esc`: stop sending.
     *
     * `QsoMachine.abort` drops the queue and reports the send as finished (ADR-0005 §4); the
     * station hears that as a garbled transmission, which is upstream's `evMsgSent` →
     * `MsgReceived([msgGarbage])` (`DxOper.pas:656-669`).
     */
    fun abort() {
        if (finished) return
        val hadBatch = batch.isNotEmpty()
        machine.abort()
        mySendEndsAt = null
        batch.clear()
        if (hadBatch) {
            operator?.msgReceived(setOf(StationMessage.GARBAGE), matchContext())
            scheduleStationReply()
        }
        lastStatus = "已停止发送"
    }

    /** Dispatches a [KeyAction] from a key or a grid button. */
    fun perform(action: KeyAction) {
        when (action) {
            is KeyAction.Send -> send(action.message)
            KeyAction.Enter -> enter()
            KeyAction.Wipe -> wipe()
            KeyAction.Abort -> abort()
            KeyAction.AdvanceField -> Unit // the next box is the UI's business
        }
    }

    // --- the snapshot ----------------------------------------------------------------

    val uiState: SessionUiState
        get() {
            val onAir = station
            val window = (elapsedMs() / QsoLog.WINDOW_MS).toInt()
            return SessionUiState(
                contestName = config.name,
                runModeLabel = if (stationsOnAir > 1) "pile-up" else "single-call",
                elapsedMs = elapsedMs(),
                remainingMs = remainingMs(),
                durationMs = durationMs(),
                finished = finished,
                qsoState = machine.state,
                entry = entry,
                errors = errors,
                exchangeLabels = exchangeLabels(),
                pendingText = machine.pendingText(),
                stationCall = onAir?.call.orEmpty(),
                stationExchange = onAir?.let { "${it.exchange.exch1} ${it.exchange.exch2}".trim() }.orEmpty(),
                stationSpeedWpm = onAir?.speedWpm ?: 0,
                stationPitchHz = onAir?.pitchHz ?: 0,
                stationCount = roster.size,
                lastHeard = lastHeard,
                lastStatus = lastStatus,
                qsos = log.size,
                rawScore = log.rawScore,
                verifiedScore = log.verifiedScore,
                rawPoints = log.rawPointsTotal,
                multipliers = log.rawMultiplierCount,
                rateLastFiveMinutes = log.rateHistogram().getOrNull(window) ?: 0,
                ratePerHour = log.ratePerHour(),
                suggestions = suggestions(),
                myCall = myCall,
                myExchange = rules.operatorExchange.piece,
            )
        }

    /**
     * Completions for the call box, from the contest's call history.
     *
     * Memoised on the typed call because the screen reads the snapshot every frame (25 Hz):
     * rescanning a 9 000-call file per frame would allocate for no reason.
     */
    fun suggestions(limit: Int = CallAutocomplete.DEFAULT_LIMIT): List<String> {
        if (limit != CallAutocomplete.DEFAULT_LIMIT) {
            return CallAutocomplete.suggest(autocompleteCalls, entry.call, limit)
        }
        val query = entry.call
        if (query != suggestionQuery) {
            suggestionQuery = query
            suggestionCache = CallAutocomplete.suggest(autocompleteCalls, query)
        }
        return suggestionCache
    }

    // --- my sending ------------------------------------------------------------------

    private fun cueCurrent() {
        val message = machine.currentMessage ?: return
        if (message == QsoMessage.HIS_CALL) addressedCall = machine.hisCall
        val text = script.text(message, machine.hisCall)
        val duration = messageDuration(text, speedWpm)
        batch += message.toStationMessage()
        cues.addLast(AudioCue(text = text, station = null, durationMs = duration))
        mySendEndsAt = lastNowMs + duration
    }

    private fun mySendFinished() {
        mySendEndsAt = null
        machine.onMyMessageSent()
        if (machine.currentMessage != null) {
            cueCurrent()
            return
        }
        notifyStationOfMyBatch()
        maybeRetireStation()
    }

    /**
     * The whole transmission is out: the station hears it as one event, which is why the batch
     * is a *set* (upstream `Msg`, `Station.pas:227`) and why it is announced once.
     */
    private fun notifyStationOfMyBatch() {
        val op = operator ?: return
        if (batch.isEmpty()) return
        val heard = batch.toSet()
        batch.clear()
        op.msgReceived(heard, matchContext())
        scheduleStationReply()
    }

    private fun matchContext(): MatchContext {
        val op = operator
        val call = addressedCall.ifBlank { entry.call }
        val check = if (call.isBlank() || op == null) {
            CallCheck.NO
        } else {
            op.callConfidenceCheck(call, randomResult = false, bestMatchConfidence = 1)
        }
        return MatchContext(
            hisCall = call,
            bestMatchConfidence = if (check == CallCheck.YES) 100 else 1,
            bestMatchCallsign = if (check != CallCheck.NO) op?.call else null,
        )
    }

    // --- the station's sending -------------------------------------------------------

    private fun scheduleStationReply() {
        val op = operator
        if (op == null) {
            theirReplyAt = null
            return
        }
        val reply = op.getReply()
        if (reply == StationMessage.NONE) {
            theirReplyAt = null
            return
        }
        val delayBlocks = op.getSendDelay(audioBlocks)
        if (delayBlocks == DxOperator.NEVER) {
            theirReplyAt = null
            return
        }
        val text = stationScript?.text(reply, hisCall = myCall).orEmpty()
        if (text.isBlank()) {
            theirReplyAt = null
            return
        }
        theirReplyText = text
        theirReplyAt = lastNowMs + blocksToMs(delayBlocks)
    }

    private fun startTheirSend() {
        theirReplyAt = null
        val onAir = station ?: return
        if (theirReplyText.isBlank()) return
        lastHeard = theirReplyText
        val duration = messageDuration(theirReplyText, onAir.speedWpm)
        cues.addLast(AudioCue(theirReplyText, onAir, duration))
        theirSendEndsAt = lastNowMs + duration
    }

    private fun theirSendFinished() {
        theirSendEndsAt = null
        machine.onTheirMessageComplete()
    }

    private fun shouldCallCq(): Boolean =
        station != null &&
            operator?.state == OperatorState.NEED_PREV_END &&
            mySendEndsAt == null &&
            theirSendEndsAt == null &&
            theirReplyAt == null &&
            lastNowMs >= nextCqAt

    private fun startStationCq() {
        val onAir = station ?: return
        val text = stationScript?.text(StationMessage.CQ, hisCall = myCall).orEmpty()
        if (text.isBlank()) return
        lastHeard = text
        val duration = messageDuration(text, onAir.speedWpm)
        cues.addLast(AudioCue(text, onAir, duration))
        theirSendEndsAt = lastNowMs + duration
        nextCqAt = lastNowMs + duration + CQ_GAP_MS
    }

    // --- the interfering station -----------------------------------------------------

    /**
     * Advances the QRM clock: one transmission at a time, then the gap, then the next
     * transmission of the same episode, or the next episode.
     *
     * Everything is read from [lastNowMs] like the rest of the session, so a JVM test can
     * step the interference through a whole configuration in milliseconds.
     */
    private fun tickQrm() {
        val endsAt = qrmSendEndsAt
        if (endsAt != null) {
            if (lastNowMs >= endsAt) qrmSendFinished()
            return
        }
        if (lastNowMs < qrmNextAt) return
        if (qrm == null) startQrmEpisode() else qrmSendNext()
    }

    private fun startQrmEpisode() {
        // `StationRoster` already guarantees a non-empty pool, so this draw cannot fail; if it
        // ever did, the session would stop making stations long before the QRM noticed.
        qrm = QrmStation.create(
            random = qrmRandom,
            basePitchHz = pitchHz,
            hisCall = myCall,
            pool = pool,
        )
        qrmIndex = 0
        qrmSendNext()
    }

    private fun qrmSendNext() {
        val station = qrm ?: return
        val text = station.texts.getOrNull(qrmIndex) ?: return
        val duration = messageDuration(text, station.speedWpm)
        qrmCues.addLast(
            QrmCue(
                text = text,
                pitchHz = station.pitchHz,
                speedWpm = station.speedWpm,
                level = station.level,
                durationMs = duration,
            ),
        )
        qrmSendEndsAt = lastNowMs + duration
        qrmIndex++
    }

    private fun qrmSendFinished() {
        qrmSendEndsAt = null
        val station = qrm ?: return
        if (qrmIndex >= station.texts.size) {
            qrm = null
            qrmIndex = 0
            qrmNextAt = lastNowMs + QRM_GAP_MS + (qrmRandom.nextDouble() * QRM_GAP_JITTER_MS).toLong()
        } else {
            // `Timeout := Round(RndGaussLim(SecondsToBlocks(4), SecondsToBlocks(2)))`.
            qrmNextAt = lastNowMs + QrmStation.gapMs(qrmRandom)
        }
    }

    // --- logging and the roster ------------------------------------------------------

    private fun logContact(typed: QsoEntry) {
        val onAir = station ?: return
        val truth = onAir.asTruth()
        val verdict = rules.judge(typed, truth)
        val score = rules.score(typed, truth)
        val outcome = log.record(
            QsoContact(
                call = typed.call,
                exch1 = typed.exch1,
                exch2 = typed.exch2,
                score = score,
                verdict = verdict,
                worked = true,
                elapsedMs = elapsedMs(),
                verified = verdict.errors.isEmpty(),
            ),
        )
        lastStatus = when (outcome) {
            is LogOutcome.Accepted -> describe(outcome.record)
            LogOutcome.Duplicate -> "重复 ${typed.call.trim()} — 未记入"
            LogOutcome.Nil -> "NIL ${typed.call.trim()} — 未记入"
        }
        addressedCall = ""
        retireStation = onAir
    }

    private fun describe(record: QsoRecord): String {
        val errors = record.errors.joinToString(" ") { it.label }
        val base = "已记入 ${record.call} · ${record.points} 分 × ${record.multipliers.size} 系数"
        return if (errors.isEmpty()) base else "$base · 错误 $errors"
    }

    /** A worked station leaves the frequency; a fresh caller takes its place. */
    private fun maybeRetireStation() {
        val retired = retireStation ?: return
        if (machine.state != QsoState.Idle) return
        retireStation = null
        roster.remove(retired.call)
        station = null
        operator = null
        stationScript = null
        lastHeard = ""
        fillStations()
    }

    /** The log's accepted contacts, in order — what a resume has to replay (T9, F10). */
    fun loggedRecords(): List<QsoRecord> = log.qsos

    private fun fillStations() {
        while (roster.size < stationsOnAir) {
            val added = runCatching { roster.add() }.getOrNull() ?: break
            station = added
            operator = DxOperator(
                call = added.call,
                random = operatorRandom,
                // T8: the roster's behaviour profile decides whether this operator LIDs
                // (`DxStn.pas:73`, `DxOper.pas:507-527`). One operator, one stream.
                lids = added.behaviour == BehaviourProfile.LID,
                runMode = if (stationsOnAir > 1) RunMode.PILE_UP else RunMode.SINGLE_CALL,
                flavour = if (config.id == ARRL_SS) OperatorFlavour.ARRL_SS else OperatorFlavour.GENERIC,
            )
            stationScript = StationScript(
                Operator(
                    call = added.call,
                    exchange = OperatorExchange(added.exchange.exch1, added.exchange.exch2),
                ),
                config.messages,
            )
            nextCqAt = lastNowMs + CQ_LEAD_MS
        }
    }

    // --- test seams ------------------------------------------------------------------
    // The engine's own state. The screen reads [uiState]; these exist so a JVM test can prove
    // the wiring the effects depend on (a LID's operator, a fluttering station).

    internal val onAirStation: RosterStation? get() = station
    internal val onAirOperator: DxOperator? get() = operator

    // --- small conversions -----------------------------------------------------------

    private fun setEntry(value: QsoEntry) {
        entry = value
        errors = emptyList()
        machine.updateEntry(value)
    }

    private fun elapsedMs(): Long = (lastNowMs - startMs).coerceAtLeast(0)

    /**
     * The contest length in minutes after the contest's own range has clamped the setting.
     *
     * Schema v1 gives each contest a `duration` block (spec §4); the operator's setting is what
     * it is applied to, so a 240-minute preference in a 60-minute contest is a 60-minute contest
     * rather than a session that never ends.
     */
    private fun effectiveDurationMinutes(): Int =
        durationMinutes.coerceIn(config.duration.minMinutes, config.duration.maxMinutes)

    private fun durationMs(): Long = effectiveDurationMinutes() * 60_000L

    private fun remainingMs(): Long = (durationMs() - elapsedMs()).coerceAtLeast(0)

    /**
     * How long [text] keys for at [wpm] — the same timing table the synthesizer uses, so the
     * controller's idea of "my transmission is over" matches the audio the user heard.
     */
    private fun messageDuration(text: String, wpm: Int): Long {
        if (text.isBlank()) return 0L
        val timing = MorseTiming(effectiveWpm = wpm)
        return MorseEncoder.encode(text, timing).sumOf { it.ms }.roundToLong()
    }

    /** Audio blocks to milliseconds, through the operator's own clock. */
    private fun blocksToMs(blocks: Int): Long =
        (blocks / audioBlocks.blocksPerSecond * 1000.0).roundToLong()

    private fun exchangeLabels(): List<String> {
        val fields = config.exchange.myFields
        if (config.exchange.sent == null) {
            return listOf(fields.getOrNull(0), fields.getOrNull(1))
                .map { it?.label ?: it?.key ?: "" }
        }
        // With `exchange.sent`, one box carries one *piece* (several fields): ARRL SS types
        // `123 A 72 OR` as two boxes and the config's entryParse rejoins them.
        val half = (fields.size + 1) / 2
        return listOf(fields.take(half), fields.drop(half)).map { piece ->
            piece.joinToString(" / ") { it.label ?: it.key }
        }
    }

    private fun QsoMessage.toStationMessage(): StationMessage = when (this) {
        QsoMessage.CQ, QsoMessage.LONG_CQ -> StationMessage.CQ
        QsoMessage.EXCHANGE -> StationMessage.EXCHANGE
        QsoMessage.TU -> StationMessage.TU
        QsoMessage.MY_CALL -> StationMessage.MY_CALL
        QsoMessage.HIS_CALL -> StationMessage.HIS_CALL
        QsoMessage.B4 -> StationMessage.B4
        QsoMessage.QM -> StationMessage.QM
        QsoMessage.NIL -> StationMessage.NIL
        QsoMessage.NR_QM -> StationMessage.NR_QM
        QsoMessage.AGN -> StationMessage.AGN
    }
}
