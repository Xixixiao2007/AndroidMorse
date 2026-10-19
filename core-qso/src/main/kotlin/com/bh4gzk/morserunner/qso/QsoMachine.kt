package com.bh4gzk.morserunner.qso

/**
 * The contest's judgement of what the operator typed, as the engine needs it.
 *
 * A port rather than a concrete class so the state machine can be exercised without a contest
 * document; [ContestRules] is the real implementation.
 */
fun interface ExchangeRules {

    /** Contest validation messages for [entry]; empty means "complete and valid". */
    fun validate(entry: QsoEntry): List<String>

    companion object {
        /** Accepts anything; useful for tests and for a session with no contest loaded. */
        val AcceptAll: ExchangeRules = ExchangeRules { emptyList() }
    }
}

/**
 * The operator's side of a contact: the state, the message queue and the `Enter` decision table.
 *
 * Transcribed from `Main.pas:952-1093 ProcessEnter` (the decision table), `Station.pas:222-235`
 * (append to the send queue) and `MyStn.pas:108-115 AbortSend` (Esc). Upstream keeps `CallSent`
 * and `NrSent` as globals in `Log.pas:118-119`; they live here because they are per-contact
 * state, not per-log state.
 *
 * The three events that drive it are the three upstream ones: the operator acts
 * ([send]/[enter]/[abort]/[wipe]), my station finishes a message ([onMyMessageSent]) and the
 * other station finishes theirs ([onTheirMessageComplete]).
 *
 * Not thread-safe by design: the audio thread posts events, a single owner applies them.
 */
class QsoMachine(
    private val script: MessageScript,
    private val rules: ExchangeRules = ExchangeRules.AcceptAll,
) {

    /** Where the contact stands (spec §2 "QSO state" — not a station's audio state). */
    var state: QsoState = QsoState.Idle
        private set

    /** What is typed in the entry boxes. */
    var entry: QsoEntry = QsoEntry()
        private set

    /** True once the call in [entry] has been sent to this station. */
    var callSent: Boolean = false
        private set

    /** True once my exchange has been sent to this station. */
    var nrSent: Boolean = false
        private set

    /** The call last sent (`TStation.HisCall`), which the `<his>` token renders. */
    var hisCall: String = ""
        private set

    private val queue = ArrayDeque<QsoMessage>()

    /** Messages waiting to be transmitted, head first. Includes the one being sent. */
    val pending: List<QsoMessage> get() = queue.toList()

    /** The message the keyer is (or is about to be) sending, `null` when the queue is empty. */
    val currentMessage: QsoMessage? get() = queue.firstOrNull()

    /** The CW text of [pending], in order — what the keyer will transmit. */
    fun pendingText(): List<String> = queue.map { script.text(it, hisCall) }

    /** Apply what the operator typed. */
    fun updateEntry(entry: QsoEntry) {
        this.entry = entry
        // Main.pas:1897-1901 Edit1Change: an empty call forgets the exchange, and any edit that
        // no longer matches what was sent invalidates the sent call.
        if (entry.call.isBlank()) nrSent = false
        callSent = entry.call.isNotBlank() && entry.call.trim() == hisCall
    }

    /**
     * Queue one message — a touch button, a function key, or the engine's own `msgHisCall`.
     *
     * Messages append rather than replace (`Station.pas:227 Include(Msg, AMsg)`), so
     * `<his> <#>` is two entries in order.
     */
    fun send(message: QsoMessage) {
        enqueue(message)
    }

    /**
     * `Enter`: send whatever this state needs, and log the contact when it is complete.
     *
     * The decision table is upstream's, statement for statement. The one substitution is that
     * "the exchange is valid" asks the contest ([ExchangeRules]) instead of a per-field length
     * check, because the rules are data now (ADR-0002).
     *
     * The completeness test assumes the schema's two-piece exchange (`exch1`, `exch2`): every
     * contest in the schema has two pieces, and a single-piece contest would never count as
     * complete. A document that drops the second piece is a schema change, not a machine change.
     */
    fun enter(): EnterOutcome {
        // Main.pas:1017: the call counts as sent only while the box still matches it.
        callSent = entry.call.isNotBlank() && entry.call.trim() == hisCall

        if (entry.call.isBlank()) {
            // No QSO in progress: call CQ.
            return enqueueAll(listOf(QsoMessage.CQ))
        }

        val validation = rules.validate(entry)
        val validCall = '?' !in entry.call && entry.call.trim().length >= 3
        val haveFirst = entry.exch1.isNotBlank()
        val exchangeReady = validation.isEmpty() && entry.exch2.isNotBlank()

        // Captured before anything is sent, exactly like upstream's C / N locals.
        val callWasSent = callSent
        val exchangeWasSent = nrSent

        val queued = mutableListOf<QsoMessage>()
        if (!callWasSent || (!exchangeWasSent && !exchangeReady)) queued += QsoMessage.HIS_CALL
        if (!exchangeWasSent && validCall) queued += QsoMessage.EXCHANGE
        if (exchangeWasSent && (!exchangeReady || !haveFirst)) queued += QsoMessage.QM

        val complete = exchangeReady && haveFirst && (callWasSent || exchangeWasSent)
        if (complete) queued += QsoMessage.TU

        val outcome = enqueueAll(queued)
        if (!complete) {
            return outcome.copy(logged = false, errors = validation, advance = true)
        }

        // SaveQso: the contact is logged, then the boxes are wiped (Log.pas:682-775).
        state = QsoState.Logged
        entry = QsoEntry()
        callSent = false
        nrSent = false
        hisCall = ""
        return outcome.copy(logged = true, errors = emptyList(), advance = false)
    }

    /**
     * `Esc`: stop sending.
     *
     * `TMyStation.AbortSend` drops the queued pieces **and reports the message as sent**
     * (`Msg := [msgGarbage]` then `evMsgSent`), so the engine moves on instead of waiting for an
     * audio event that will never arrive (ADR-0005 §4).
     */
    fun abort() {
        val dropped = queue.toList()
        queue.clear()
        if (QsoMessage.HIS_CALL in dropped) callSent = false
        if (QsoMessage.EXCHANGE in dropped) nrSent = false
        onMyMessageSent()
    }

    /** `F11`/`Ctrl-W`: clear the boxes, the flags and the queue (`Main.pas:1822-1838`). */
    fun wipe() {
        entry = QsoEntry()
        callSent = false
        nrSent = false
        hisCall = ""
        queue.clear()
        state = QsoState.Idle
    }

    /**
     * My station finished transmitting.
     *
     * When the queue still holds messages the next one simply becomes current; when it is empty
     * the state advances, which is the "engine advances on my send completion" half of spec §6.
     */
    fun onMyMessageSent() {
        if (queue.isNotEmpty()) queue.removeFirst()
        if (queue.isNotEmpty()) return
        when (state) {
            QsoState.CallingCq -> state = QsoState.WaitingForCall
            QsoState.SendingExchange -> state = QsoState.WaitingForExchange
            QsoState.Logged -> state = QsoState.Idle
            QsoState.Idle, QsoState.WaitingForCall, QsoState.WaitingForExchange -> Unit
        }
    }

    /**
     * The other station finished transmitting.
     *
     * A caller has finished his call, so it is my turn to answer: `WaitingForCall` becomes
     * `SendingExchange`. Everything else waits for the operator (spec §6: the engine advances on
     * received audio completion).
     */
    fun onTheirMessageComplete() {
        if (state == QsoState.WaitingForCall) state = QsoState.SendingExchange
    }

    private fun enqueueAll(messages: List<QsoMessage>): EnterOutcome {
        messages.forEach { enqueue(it) }
        return EnterOutcome(
            messages = messages,
            sentText = messages.map { script.text(it, hisCall) },
        )
    }

    private fun enqueue(message: QsoMessage) {
        queue.addLast(message)
        when {
            // Calling CQ is a state of its own, from wherever the operator reaches for it.
            message == QsoMessage.CQ || message == QsoMessage.LONG_CQ -> state = QsoState.CallingCq
            // Anything else means I am working a station.
            state == QsoState.Idle || state == QsoState.Logged -> state = QsoState.SendingExchange
            else -> Unit
        }
        when (message) {
            QsoMessage.HIS_CALL -> {
                hisCall = entry.call.trim()
                callSent = entry.call.isNotBlank()
            }
            QsoMessage.EXCHANGE -> nrSent = true
            else -> Unit
        }
    }
}
