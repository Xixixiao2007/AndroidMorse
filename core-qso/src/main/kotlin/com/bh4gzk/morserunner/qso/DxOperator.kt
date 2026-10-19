package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.SeededRandom

/**
 * A station-side operator state (`DxOper.pas:81-82 TOperatorState`).
 *
 * These are the states of the *simulated DX operator*, not of the operator's own station
 * ([QsoState] is that one). The distinction matters: a station can be `NEED_END` while the
 * operator's station is `WaitingForExchange`.
 */
enum class OperatorState {
    /** Waiting for any prior QSO to finish (the operator's next CQ or `TU`). */
    NEED_PREV_END,

    /** Waiting for the operator to send this station's callsign. */
    NEED_QSO,

    /** The station has the call and waits for the operator's exchange. */
    NEED_NR,

    /** The operator's exchange was copied but the call was partial: asks for the call again. */
    NEED_CALL,

    /** Both the call and the exchange are wrong: asks for both. */
    NEED_CALL_NR,

    /** Call and exchange received: waits for `TU`. */
    NEED_END,

    /** The contact is complete. */
    DONE,

    /** The contact failed (ghosted, `NIL`, or `B4`). */
    FAILED,
}

/** The result of comparing an entered call with the operator's own (`TCallCheckResult`). */
enum class CallCheck { NO, YES, ALMOST }

/**
 * The CW vocabulary of a station (`Station.pas:19-22 TStationMessage`), trimmed to the values
 * the operator state machine consumes or produces.
 *
 * Upstream uses one enum for both directions; so does this one, and the KDoc marks which side
 * sends which. Rendering to text is a separate concern (the operator's own station renders
 * through [MessageScript]; a station's reply is rendered by whatever plays it).
 */
enum class StationMessage {
    /** Silence: no reply to send (`msgNone`). */
    NONE,

    // --- what the operator's station sends, as the station hears it ------------------
    /** `msgCQ` — a CQ call. */
    CQ,
    /** `msgNR` — the operator's exchange. */
    EXCHANGE,
    /** `msgTU` — the operator finished the contact. */
    TU,
    /** `msgHisCall` — the operator is calling this station; the payload is [MatchContext.hisCall]. */
    HIS_CALL,
    /** `msgB4` — the operator says this is a duplicate contact. */
    B4,
    /** `msgQm` — the operator sends `?`. */
    QM,
    /** `msgNil` — the operator says the contact is not in the log. */
    NIL,
    /** `msgGarbage` — the operator's transmission was missed or copied partially. */
    GARBAGE,

    // --- what a station sends back ---------------------------------------------------
    /** `msgMyCall` — the station's callsign. */
    MY_CALL,
    /** `msgMyCall2` — the callsign twice. */
    MY_CALL2,
    /** `msgR_NR` — `R <exchange>`. */
    R_EXCHANGE,
    /** `msgR_NR2` — `R <exchange> <exchange>`. */
    R_EXCHANGE2,
    /** `msgDeMyCall1` — `DE <call>`. */
    DE_MY_CALL,
    /** `msgDeMyCall2` — `DE <call> <call>`. */
    DE_MY_CALL2,
    /** `msgDeMyCallNr1` — `DE <call> <exchange>`. */
    DE_MY_CALL_EXCH,
    /** `msgDeMyCallNr2` — `DE <call> <call> <exchange>`. */
    DE_MY_CALL2_EXCH,
    /** `msgMyCallNr1` — `<call> <exchange>`. */
    MY_CALL_EXCH,
    /** `msgMyCallNr2` — `<call> <call> <exchange>`. */
    MY_CALL2_EXCH,
    /** `msgNrQm` — `NR?`. */
    NR_QM,
    /** `msgAgn` — `AGN`. */
    AGN,
}

/** How a station is run, for the few branches that differ (`rmSingle`, `rmPileup`, `rmWpx`). */
enum class RunMode {
    /** One station at a time: no repeat draws, generous patience (`rmSingle`). */
    SINGLE_CALL,

    /** A pile-up: stations may double their reply (`rmPileup`). */
    PILE_UP,

    /** A timed scoring run: behaves like a pile-up here (`rmWpx`). */
    COMPETITION,
}

/** Per-contest wording the operator's replies depend on (`SimContest in [scArrlSS]`). */
enum class OperatorFlavour { GENERIC, ARRL_SS }

/**
 * Upstream's audio block, which is the unit its timing constants are expressed in.
 *
 * `SecondsToBlocks(Sec) = Round(DEFAULTRATE / BufSize * Sec)` with `DEFAULTRATE = 11025` and
 * `BufSize = 512` by default (`Ini.pas:24,244`, `RndFunc.pas:61-69`). The Android audio engine
 * decides its own rate and buffer; passing the clock in keeps [DxOperator]'s constants honest
 * instead of hard-coding a sample rate the audio layer may not use.
 */
data class AudioBlocks(
    val sampleRate: Int = 11_025,
    val samplesPerBlock: Int = 512,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(samplesPerBlock > 0) { "samplesPerBlock must be positive: $samplesPerBlock" }
    }

    val blocksPerSecond: Double get() = sampleRate.toDouble() / samplesPerBlock

    fun secondsToBlocks(seconds: Double): Int = roundLikeDelphi(blocksPerSecond * seconds)

    fun blocksToSeconds(blocks: Int): Double = blocks / blocksPerSecond
}

/**
 * What the operator's current transmission says, as far as a station is concerned.
 *
 * @param hisCall the callsign in the operator's entry box (`Tst.Me.HisCall`).
 * @param bestMatchConfidence `TStations.BestMatchConfidence` — the highest confidence any
 *   caller reached for that entry (`StnColl.pas:114-137`). Starts at 1 so a `mcNo` caller can
 *   never look like the best match.
 * @param bestMatchCallsign the call that reached it, `null` before anyone matches.
 */
data class MatchContext(
    val hisCall: String = "",
    val bestMatchConfidence: Int = 1,
    val bestMatchCallsign: String? = null,
)

/**
 * One simulated DX operator: its patience, its state, and how it answers the operator
 * (`DxOper.pas:87-125 TDxOperator`).
 *
 * **What this port keeps.** The state machine, `Patience`/ghosting, `RepeatCnt`, the two
 * timing functions, the callsign matching (wildcard, edit distance, substring, confidence) and
 * the LID behaviour. `Test/DxOperTest.pas`'s 180-case golden table is the acceptance test for
 * the matcher.
 *
 * **What it delegates.**
 *  - speed / pitch / level / fade / serial: the roster draws them (T5, `StationRoster`), and
 *    `DxOper.GetWpm/GetNR` would be a second source of truth;
 *  - rendering a reply to CW text: [StationMessage] is the vocabulary, the player renders it;
 *  - the operator's own station: [QsoState]/[QsoMachine] hold that side.
 *
 * **Randomness** comes from the caller's [SeededRandom] in upstream's order, so a session
 * replays from its seed. Two branches of `DxOper.pas` are known to consume draws only under a
 * condition (the repeat-count draw, the `NR?` draw); both are kept draw-for-draw.
 *
 * **HST is out of scope** (spec §11: T10–T20 carry the remaining contests). The `rmHst`
 * branches of `DxOper.pas` (`GetSendDelay`, `GetReply`, `MsgReceived`'s `NR` handling) are not
 * transcribed; HST's contest document will need them, and that is a ticket of its own.
 */
class DxOperator(
    val call: String,
    initialState: OperatorState = OperatorState.NEED_PREV_END,
    private val random: SeededRandom,
    /** LIDs mis-copy calls (`DxOper.pas:507-527`); the roster decides which stations are. */
    val lids: Boolean = false,
    private val runMode: RunMode = RunMode.PILE_UP,
    private val flavour: OperatorFlavour = OperatorFlavour.GENERIC,
) {

    companion object {
        /** Patience before an unanswered station gives up (`DxOper.pas:14`). */
        const val FULL_PATIENCE: Int = 5

        /** The confidence floor the wildcard branch uses (`DxOper.pas:15`). */
        const val LOW_CONFIDENCE: Int = 2

        /** "No timeout" in block counts (`Station.pas:16 NEVER = MAXINT`). */
        const val NEVER: Int = Int.MAX_VALUE

        private const val TWO_PI: Double = 2.0 * kotlin.math.PI

        /** The two states a station may fail from; an in-progress QSO ghosts instead. */
        private val GHOSTABLE_STATES: Set<OperatorState> =
            setOf(OperatorState.NEED_PREV_END, OperatorState.NEED_QSO)
    }

    // Upstream TDxOperator.Create: one response draw, then skills (DxOper.pas:139-141).
    /** `R2`: fixed at creation so a correction uses a consistent reply (`DxOper.pas:139`). */
    private val responseDraw: Double = random.nextDouble()

    /** `Skills := 1 + Random(3)`, i.e. 1..3 (`DxOper.pas:141`). */
    val skills: Int = 1 + random.nextInt(0, 3)

    var state: OperatorState = initialState
        private set

    /** Timeouts this station will survive before it gives up or ghosts. */
    var patience: Int = 0
        private set

    /** How many times a reply is sent at once (`DxOper.pas:356-358`). */
    var repeatCount: Int = 1
        private set

    /** How closely the last entered call matched, 0–100 (`DxOper.pas:104-106`). */
    var callConfidence: Int = 0
        private set

    /** True once the station answered with its callsign *and* exchange (`DxOper.pas:108-109`). */
    var correctedCallAndExchSent: Boolean = false
        private set

    private var sendNrQmCount: Int = 0
    private var lastCheckedCall: String = ""
    private var lastCallCheck: CallCheck = CallCheck.NO

    init {
        // `Patience := 0; RepeatCnt := 1; SetState(AState)` (DxOper.pas:142-144).
        setState(initialState)
    }

    /** A station that has run out of patience stops transmitting but can still be worked. */
    val isGhosting: Boolean get() = patience == 0

    /**
     * Moves to [newState] and re-grants patience (`DxOper.pas:326-365`).
     *
     * `NEED_QSO` gets `3 + Round(RndRayleigh(3))` — the May-2024 fix for stations that
     * disappeared right after their first transmission (`DxOper.pas:330-351`) — and every other
     * state gets `FULL_PATIENCE`.
     */
    fun setState(newState: OperatorState) {
        state = newState
        patience = if (newState == OperatorState.NEED_QSO) {
            3 + roundLikeDelphi(rayleigh(3.0))
        } else {
            FULL_PATIENCE
        }
        // Short-circuit exactly like the Pascal: the draw happens only in a pile-up, in
        // NEED_QSO (DxOper.pas:356-358).
        repeatCount = if (newState == OperatorState.NEED_QSO && runMode != RunMode.SINGLE_CALL) {
            if (random.nextDouble() < 0.1) 2 else 1
        } else {
            1
        }
        if (newState == OperatorState.NEED_QSO) correctedCallAndExchSent = false
        if (newState == OperatorState.NEED_NR) sendNrQmCount = 0
    }

    /**
     * One [timeout][StationMessage.GARBAGE]-free event worth of impatience (`DxOper.pas:251-262`).
     *
     * A station that has not started a QSO is discarded; one that has keeps its state and
     * ghosts (it stops transmitting, upstream's caller-ghosting rule).
     */
    fun decPatience() {
        if (state == OperatorState.DONE) return
        if (patience > 0) patience--
        if (patience < 1 && state in GHOSTABLE_STATES) state = OperatorState.FAILED
    }

    /**
     * Grants patience after a message that did not change the state (`DxOper.pas:290-305`).
     *
     * @param value an explicit patience, capped at [FULL_PATIENCE]; `0` means "the default
     *   grant".
     */
    fun morePatience(value: Int = 0) {
        if (state == OperatorState.DONE) return
        if (value > 0) {
            patience = minOf(value, FULL_PATIENCE)
        } else if (patience < FULL_PATIENCE) {
            patience = when {
                runMode == RunMode.SINGLE_CALL -> 4
                patience == 0 -> 3
                else -> minOf(patience + 2, 4)
            }
        }
    }

    /**
     * Compares an entered call with this station's (`DxOper.pas:402-528`).
     *
     * Resolution order, exactly upstream's:
     *  1. a cached answer for the same pattern (upstream caches across calls);
     *  2. a `?` pattern is a wildcard regex, `.` per `?`, and a trailing `?` also matches the
     *     rest of the call;
     *  3. otherwise the Levenshtein distance classifies the match, with a substring fallback;
     *  4. confidence is `100 * matching characters / call length`.
     *
     * @param randomResult whether a LID may accept a wrong call or reject a correct one. The
     *   calls made while saving a QSO pass `false` (`Log.pas:732,743`), so a log check never
     *   re-rolls the LID dice.
     */
    fun isMyCall(pattern: String, randomResult: Boolean): CallCheck {
        var result: CallCheck
        if (lastCheckedCall == pattern) {
            result = lastCallCheck
        } else {
            lastCheckedCall = pattern
            result = when {
                // Upstream's own golden case `'' / '?'` expects No/0; the Pascal would divide
                // by the empty call's length in the wildcard branch, so this port fails closed.
                call.isEmpty() -> CallCheck.NO.also { callConfidence = 0 }
                '?' in pattern -> wildcardMatch(pattern)
                else -> editDistanceMatch(pattern)
            }
            lastCallCheck = result
        }

        // "accept a wrong call, or reject the correct one" (DxOper.pas:507-527).
        if (randomResult && lids && pattern.length > 3 && '?' !in pattern) {
            when (result) {
                CallCheck.YES -> if (random.nextDouble() < 0.01) {
                    result = CallCheck.ALMOST
                    callConfidence = 100 * (call.length - 1) / call.length
                }
                CallCheck.ALMOST -> if (random.nextDouble() < 0.04) {
                    result = CallCheck.YES
                    callConfidence = 100
                }
                CallCheck.NO -> Unit
            }
        }
        return result
    }

    /**
     * [isMyCall] with the "only the best partial match answers" rule (`DxOper.pas:537-545`).
     */
    fun callConfidenceCheck(pattern: String, randomResult: Boolean, bestMatchConfidence: Int): CallCheck {
        val result = isMyCall(pattern, randomResult)
        return if (result == CallCheck.ALMOST && callConfidence < bestMatchConfidence) {
            CallCheck.NO
        } else {
            result
        }
    }

    /** True when this station is the one being worked (`DxOper.pas:551-555`). */
    fun isActiveInQso(bestMatchConfidence: Int, bestMatchCallsign: String?): Boolean =
        callConfidence >= bestMatchConfidence || bestMatchCallsign == call

    /**
     * Reacts to what the operator just transmitted (`DxOper.pas:558-672`).
     *
     * @param messages the set of messages in that transmission; an empty set is a timeout
     *   (`evTimeout` passes `[msgNone]`).
     */
    fun msgReceived(messages: Set<StationMessage>, context: MatchContext) {
        // "if CQ received, we can call no matter what else was sent" (DxOper.pas:562-571).
        if (StationMessage.CQ in messages) {
            when (state) {
                OperatorState.NEED_PREV_END -> setState(OperatorState.NEED_QSO)
                OperatorState.NEED_QSO -> decPatience()
                OperatorState.NEED_NR,
                OperatorState.NEED_CALL,
                OperatorState.NEED_CALL_NR,
                -> state = OperatorState.FAILED
                OperatorState.NEED_END -> state = OperatorState.DONE
                OperatorState.DONE, OperatorState.FAILED -> Unit
            }
            return
        }

        // `NIL` forces the QSO to fail (DxOper.pas:573-581).
        if (StationMessage.NIL in messages) {
            when (state) {
                OperatorState.NEED_PREV_END -> setState(OperatorState.NEED_QSO)
                OperatorState.NEED_QSO -> decPatience()
                OperatorState.NEED_NR,
                OperatorState.NEED_CALL,
                OperatorState.NEED_CALL_NR,
                OperatorState.NEED_END,
                -> state = OperatorState.FAILED
                OperatorState.DONE, OperatorState.FAILED -> Unit
            }
            return
        }

        if (StationMessage.HIS_CALL in messages) {
            when (callConfidenceCheck(context.hisCall, randomResult = true, context.bestMatchConfidence)) {
                CallCheck.YES -> when (state) {
                    OperatorState.NEED_PREV_END, OperatorState.NEED_QSO -> setState(OperatorState.NEED_NR)
                    OperatorState.NEED_CALL_NR -> setState(OperatorState.NEED_NR)
                    OperatorState.NEED_NR, OperatorState.NEED_END -> morePatience()
                    OperatorState.NEED_CALL -> setState(OperatorState.NEED_END)
                    OperatorState.DONE, OperatorState.FAILED -> Unit
                }
                CallCheck.ALMOST -> when (state) {
                    OperatorState.NEED_PREV_END, OperatorState.NEED_QSO -> setState(OperatorState.NEED_CALL_NR)
                    OperatorState.NEED_CALL_NR, OperatorState.NEED_CALL -> morePatience()
                    OperatorState.NEED_NR -> setState(OperatorState.NEED_CALL_NR)
                    OperatorState.NEED_END -> setState(OperatorState.NEED_CALL)
                    OperatorState.DONE, OperatorState.FAILED -> Unit
                }
                CallCheck.NO -> when (state) {
                    OperatorState.NEED_QSO,
                    OperatorState.NEED_NR,
                    OperatorState.NEED_CALL,
                    OperatorState.NEED_CALL_NR,
                    -> state = OperatorState.NEED_PREV_END
                    OperatorState.NEED_END -> state = OperatorState.DONE
                    OperatorState.NEED_PREV_END, OperatorState.DONE, OperatorState.FAILED -> Unit
                }
            }
        }

        // "I had a prior QSO" (DxOper.pas:605-610).
        if (StationMessage.B4 in messages) {
            when (state) {
                OperatorState.NEED_PREV_END, OperatorState.NEED_QSO -> setState(OperatorState.NEED_QSO)
                OperatorState.NEED_NR, OperatorState.NEED_END -> state = OperatorState.FAILED
                // Same state: correct the call.
                OperatorState.NEED_CALL, OperatorState.NEED_CALL_NR -> Unit
                OperatorState.DONE, OperatorState.FAILED -> Unit
            }
        }

        if (StationMessage.EXCHANGE in messages) {
            when (state) {
                OperatorState.NEED_PREV_END -> Unit
                OperatorState.NEED_QSO -> state = OperatorState.NEED_PREV_END
                OperatorState.NEED_NR -> {
                    // The draw is consumed even in single-call mode: the Pascal evaluates
                    // `Random < 0.9` first (DxOper.pas:616).
                    val lucky = random.nextDouble() < 0.9
                    if (lucky || runMode == RunMode.SINGLE_CALL) {
                        setState(OperatorState.NEED_END)
                    } else {
                        morePatience()
                    }
                }
                OperatorState.NEED_CALL -> morePatience()
                OperatorState.NEED_CALL_NR -> {
                    val lucky = random.nextDouble() < 0.9
                    if (lucky || runMode == RunMode.SINGLE_CALL) {
                        setState(OperatorState.NEED_CALL)
                    } else {
                        morePatience()
                    }
                }
                OperatorState.NEED_END -> morePatience()
                OperatorState.DONE, OperatorState.FAILED -> Unit
            }
        }

        if (StationMessage.TU in messages) {
            when (state) {
                OperatorState.NEED_PREV_END -> setState(OperatorState.NEED_QSO)
                OperatorState.NEED_QSO -> setState(OperatorState.NEED_QSO)
                OperatorState.NEED_NR -> if (isActiveInQso(context.bestMatchConfidence, context.bestMatchCallsign)) {
                    state = OperatorState.DONE // may have an exchange error
                } else {
                    setState(OperatorState.NEED_QSO)
                }
                OperatorState.NEED_CALL -> if (isActiveInQso(context.bestMatchConfidence, context.bestMatchCallsign)) {
                    state = OperatorState.DONE // possible partial call match
                } else {
                    setState(OperatorState.NEED_QSO)
                }
                OperatorState.NEED_CALL_NR -> if (isActiveInQso(context.bestMatchConfidence, context.bestMatchCallsign) &&
                    correctedCallAndExchSent
                ) {
                    state = OperatorState.DONE
                } else {
                    setState(OperatorState.NEED_QSO)
                }
                OperatorState.NEED_END -> state = OperatorState.DONE
                OperatorState.DONE, OperatorState.FAILED -> Unit
            }
        }

        if (StationMessage.QM in messages) {
            when (state) {
                OperatorState.NEED_PREV_END -> if (context.hisCall.isBlank()) setState(OperatorState.NEED_QSO)
                OperatorState.NEED_QSO -> Unit
                OperatorState.NEED_NR,
                OperatorState.NEED_CALL,
                OperatorState.NEED_CALL_NR,
                OperatorState.NEED_END,
                -> morePatience()
                OperatorState.DONE, OperatorState.FAILED -> Unit
            }
        }

        // A transmission the station missed entirely (DxOper.pas:656-669). Only when the
        // station is not itself a LID: a LID hears the message regardless.
        if (!lids && messages == setOf(StationMessage.GARBAGE)) {
            when (state) {
                OperatorState.NEED_PREV_END, OperatorState.NEED_END,
                OperatorState.DONE, OperatorState.FAILED,
                -> Unit
                OperatorState.NEED_QSO,
                OperatorState.NEED_NR,
                OperatorState.NEED_CALL,
                OperatorState.NEED_CALL_NR,
                -> morePatience()
            }
        }

        if (state != OperatorState.NEED_PREV_END) decPatience()
    }

    /**
     * What this station transmits next (`DxOper.pas:675-746`).
     *
     * A ghosting station sends nothing; the caller must not call this while [isGhosting].
     */
    fun getReply(): StationMessage {
        if (isGhosting) return StationMessage.NONE
        return when (state) {
            OperatorState.NEED_PREV_END,
            OperatorState.DONE,
            OperatorState.FAILED,
            -> StationMessage.NONE

            OperatorState.NEED_QSO -> StationMessage.MY_CALL

            OperatorState.NEED_NR -> {
                // `(Patience = FULL-1) or ((PostInc(SendNrQmCnt) mod 3) = 0) or (Random < 0.2)`
                // with Delphi's short-circuit `or`: the counter and the draw happen only when
                // the earlier tests are false (DxOper.pas:691-695).
                val firstTime = patience == FULL_PATIENCE - 1
                val everyThird = if (!firstTime) {
                    val current = sendNrQmCount
                    sendNrQmCount++
                    current % 3 == 0
                } else {
                    false
                }
                val sometimes = if (!firstTime && !everyThird) random.nextDouble() < 0.2 else false
                if (firstTime || everyThird || sometimes) StationMessage.NR_QM else StationMessage.AGN
            }

            // I have their exchange but need the call corrected (DxOper.pas:698-713).
            OperatorState.NEED_CALL -> when {
                flavour == OperatorFlavour.ARRL_SS -> when ((responseDraw * 3).toInt()) {
                    0 -> StationMessage.DE_MY_CALL_EXCH
                    else -> StationMessage.MY_CALL_EXCH
                }
                else -> when ((responseDraw * 6).toInt()) {
                    0 -> StationMessage.DE_MY_CALL_EXCH
                    1 -> StationMessage.DE_MY_CALL2_EXCH
                    2, 3 -> StationMessage.MY_CALL_EXCH
                    4 -> StationMessage.MY_CALL2_EXCH
                    else -> StationMessage.MY_CALL
                }
            }

            // An almost-correct call: ask for it (DxOper.pas:716-738).
            OperatorState.NEED_CALL_NR -> when ((responseDraw * 6).toInt()) {
                0 -> StationMessage.DE_MY_CALL
                1 -> StationMessage.DE_MY_CALL2
                2, 3 -> StationMessage.MY_CALL
                4 -> StationMessage.MY_CALL2
                else -> if (lids && responseDraw < 0.88) {
                    correctedCallAndExchSent = true
                    StationMessage.MY_CALL_EXCH
                } else if (responseDraw < 0.95) {
                    StationMessage.MY_CALL
                } else {
                    StationMessage.MY_CALL2
                }
            }

            // Waiting for `TU` (DxOper.pas:739-745). The draw is consumed only in the generic
            // branch, because the contest tests come first.
            OperatorState.NEED_END -> when {
                patience < FULL_PATIENCE - 1 -> StationMessage.EXCHANGE
                flavour == OperatorFlavour.ARRL_SS -> StationMessage.R_EXCHANGE
                random.nextDouble() < 0.9 -> StationMessage.R_EXCHANGE
                else -> StationMessage.R_EXCHANGE2
            }
        }
    }

    /**
     * How many blocks to wait before replying (`DxOper.pas:175-186`).
     *
     * `0.1 + 0.5*Random` seconds, and [NEVER] while waiting for a prior QSO to end.
     */
    fun getSendDelay(blocks: AudioBlocks): Int {
        if (state == OperatorState.NEED_PREV_END) return NEVER
        return blocks.secondsToBlocks(0.1 + 0.5 * random.nextDouble())
    }

    /**
     * How many blocks the station waits for the operator before timing out
     * (`DxOper.pas:234-241`): `6 - Skills` seconds, jittered by a limited Gaussian of half
     * that value.
     */
    fun getReplyTimeout(blocks: AudioBlocks): Int {
        // `SecondsToBlocks(6-Skills)` rounds once, then `Round(RndGaussLim(d, d/2))` rounds
        // again — both roundings are part of the transcription.
        val base = roundLikeDelphi(blocks.blocksPerSecond * (6 - skills))
        return roundLikeDelphi(limitedGaussian(base.toDouble(), base / 2.0)).toInt()
    }

    // --- matcher -------------------------------------------------------------------

    private fun wildcardMatch(pattern: String): CallCheck {
        // `?` is one character; a trailing `?` also matches the rest of the call
        // (DxOper.pas:428-431).
        val source = if (pattern.endsWith("?")) {
            pattern.replace("?", ".") + "*"
        } else {
            pattern.replace("?", ".")
        }
        // PerlRegEx.Match is a search, not an anchor (DxOper.pas:432-433). A phone keyboard can
        // produce a character the pattern grammar does not allow, so a malformed pattern fails
        // closed instead of throwing out of the engine.
        val matched = try {
            Regex(source).containsMatchIn(call)
        } catch (invalid: java.util.regex.PatternSyntaxException) {
            false
        }
        return if (matched) {
            // The "penalty" is how many of the call's characters the pattern did not supply.
            val penalty = call.length - pattern.replace("?", "").length
            callConfidence = 100 * (call.length - penalty) / call.length
            if (callConfidence == 0) callConfidence = LOW_CONFIDENCE
            CallCheck.ALMOST
        } else {
            callConfidence = 0
            CallCheck.NO
        }
    }

    private fun editDistanceMatch(pattern: String): CallCheck {
        // The dynamic-programming table is upstream's, character for character
        // (DxOper.pas:456-470).
        val table = Array(pattern.length + 1) { IntArray(call.length + 1) }
        for (x in 0..pattern.length) table[x][0] = x
        for (y in 0..call.length) table[0][y] = y
        for (x in 1..pattern.length) {
            for (y in 1..call.length) {
                table[x][y] = if (pattern[x - 1] == call[y - 1]) {
                    table[x - 1][y - 1]
                } else {
                    1 + minOf(table[x][y - 1], table[x - 1][y], table[x - 1][y - 1])
                }
            }
        }

        var penalty = table[pattern.length][call.length]
        // `P <= (C0.Length-1)/2` is real division in the Pascal; doubling keeps it exact.
        var result = when {
            penalty == 0 -> CallCheck.YES
            penalty * 2 <= call.length - 1 -> CallCheck.ALMOST
            else -> CallCheck.NO
        }

        // Fall back to a substring match when the edit distance was too large
        // (DxOper.pas:482-487).
        if (result == CallCheck.NO && call.contains(pattern)) {
            result = CallCheck.ALMOST
            penalty = call.length - pattern.length
        }

        callConfidence = when (result) {
            CallCheck.YES -> 100
            CallCheck.ALMOST -> {
                val confidence = 100 * (call.length - penalty) / call.length
                if (confidence == 0) LOW_CONFIDENCE else confidence
            }
            CallCheck.NO -> 0
        }
        return result
    }

    // --- randomness (upstream RndFunc.pas) -------------------------------------------

    /** `RndRayleigh(mean) = mean * sqrt(-ln(r1) - ln(r2))` (`RndFunc.pas:72-75`). */
    private fun rayleigh(mean: Double): Double {
        val first = positiveDraw()
        val second = positiveDraw()
        return mean * kotlin.math.sqrt(-kotlin.math.ln(first) - kotlin.math.ln(second))
    }

    /**
     * `RndGaussLim(mean, limit)`: a Gaussian with `sigma = limit/2`, re-drawn until it lands
     * inside `±limit` (`RndFunc.pas:46-60`). The rejection loop is why the number of draws
     * varies — still deterministic, still replayable.
     *
     * The distribution itself lives in [gaussLimited] so that the operator and [QrmStation]
     * cannot drift into two different "limited Gaussians"; the draws come from this
     * operator's own stream, in the same order as before.
     */
    private fun limitedGaussian(mean: Double, limit: Double): Double =
        gaussLimited(random, mean, limit)

    /** A draw in `(0, 1)`: `ln(0)` is `-inf` and would make the distributions infinite. */
    private fun positiveDraw(): Double {
        var draw = random.nextDouble()
        while (draw == 0.0) draw = random.nextDouble()
        return draw
    }
}

/**
 * Delphi's `Round`: half-way values go to the even neighbour (`Math.rint`), unlike Kotlin's
 * `roundToInt` which rounds half up. Upstream's block counts depend on this
 * (`SecondsToBlocks`, `GetReplyTimeout`), so the transcription keeps it.
 */
internal fun roundLikeDelphi(value: Double): Int = Math.rint(value).toInt()
