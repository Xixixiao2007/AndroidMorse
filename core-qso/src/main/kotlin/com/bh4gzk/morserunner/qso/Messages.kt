package com.bh4gzk.morserunner.qso

/**
 * One canned transmission: the vocabulary of spec §2 "Message", transcribed from upstream
 * `TStationMessage` (`Station.pas:19-22`).
 *
 * Upstream carries 24 values because its contest classes override the wording per contest and
 * because a few exist only for the QRM generators (`msqQsy`, `msgQrl`). The set here is what the
 * operator can send: the eight touch buttons and the physical keys of spec §8, plus the two
 * messages the engine itself emits while working a station.
 *
 * [configKey] is the key a contest document may use to override the wording
 * (`ContestConfig.messages`, spec §4). It is deliberately not the enum name: the document uses
 * `longCQ`, the enum uses `LONG_CQ`.
 */
enum class QsoMessage(val configKey: String) {

    /** `CQ <my> TEST` (F1). */
    CQ("CQ"),

    /** `CQ CQ TEST <my> <my> TEST`; ARRL SS overrides it (`ArrlSS.pas`). */
    LONG_CQ("longCQ"),

    /** My side of the exchange, `<#>` (F2). */
    EXCHANGE("EXCHANGE"),

    /** `TU` (F3). */
    TU("TU"),

    /** `<my>`; long-press on CQ (F4). */
    MY_CALL("MYCALL"),

    /** `<his>` (F5). */
    HIS_CALL("HISCALL"),

    /** `QSO B4` (F6). */
    B4("B4"),

    /** `?` (F7). */
    QM("?"),

    /** `NIL` (F8). */
    NIL("NIL"),

    /** `NR?` (F12). */
    NR_QM("NR?"),

    /** `AGN`; upstream `msgAgn`. */
    AGN("AGN"),
}

/**
 * The two pieces of the operator's own exchange, already rendered as they are sent
 * (`Contest.pas` `NrAsText`, `Station.pas:364`).
 */
data class OperatorExchange(val exch1: String, val exch2: String) {

    /** `<#>`: the two pieces joined; a blank second piece is simply absent. */
    val piece: String
        get() = when {
            exch2.isBlank() -> exch1
            exch1.isBlank() -> exch2
            else -> "$exch1 $exch2"
        }
}

/** The operator's identity, as the messages need it. */
data class Operator(val call: String, val exchange: OperatorExchange)

/**
 * Renders a [QsoMessage] to the CW text the keyer transmits.
 *
 * Transcribed from `Contest.pas:412-450 SendMsg` (the wordings), `Station.pas:242-300 SendText`
 * (the tokens) and `Station.pas:364-430 NrAsText` (the exchange piece). A contest document's
 * `messages` map overrides a wording by [QsoMessage.configKey]; a key the document does not
 * mention keeps the upstream default (ADR-0005 §7).
 *
 * **The operator is a provider, not a value.** My exchange can change between two contacts: a
 * contest whose sent piece reads the running serial sends `5NN001`, then `5NN002` (F8,
 * `Log.pas:766`). Rendering reads the operator at call time, so one script instance serves a
 * whole session and the queue, the keyer and the log all see the current serial. The secondary
 * constructor keeps the fixed-operator form every test and the [StationScript] counterpart use.
 */
class MessageScript(
    private val operator: () -> Operator,
    private val wordings: Map<String, String> = emptyMap(),
) {

    /** A script for one fixed operator: a unit test, or a station whose exchange never changes. */
    constructor(operator: Operator, wordings: Map<String, String> = emptyMap()) : this({ operator }, wordings)

    /**
     * The text for [message]. [hisCall] is the station being worked, which changes during a
     * contact — that is why it is an argument rather than part of [operator].
     */
    fun text(message: QsoMessage, hisCall: String = ""): String {
        val current = operator()
        return renderTokens(
            template = wordings[message.configKey] ?: default(message),
            my = current.call,
            exchange = current.exchange.piece,
            hisCall = hisCall,
        )
    }

    /** Upstream's default wording per message (`Contest.pas:412-450`). */
    private fun default(message: QsoMessage): String = when (message) {
        QsoMessage.CQ -> "CQ <my> TEST"
        QsoMessage.LONG_CQ -> "CQ CQ TEST <my> <my> TEST"
        QsoMessage.EXCHANGE -> "<#>"
        QsoMessage.TU -> "TU"
        QsoMessage.MY_CALL -> "<my>"
        QsoMessage.HIS_CALL -> "<his>"
        QsoMessage.B4 -> "QSO B4"
        QsoMessage.QM -> "?"
        QsoMessage.NIL -> "NIL"
        QsoMessage.NR_QM -> "NR?"
        QsoMessage.AGN -> "AGN"
    }
}

/**
 * Expands the three message tokens (`Station.pas:242-300` `SendText`): `<#>` the sender's
 * exchange, `<my>` the sender's call, `<his>` the station being worked.
 *
 * One rendering routine serves both directions — [MessageScript] for my station and
 * [StationScript] for a simulated one — because the tokens are relative to the *sender*, and a
 * second copy of this rule is what T6's finding F5 declined to add.
 */
internal fun renderTokens(template: String, my: String, exchange: String, hisCall: String): String =
    template
        .replace("<#>", exchange)
        .replace("<my>", my)
        .replace("<his>", hisCall)
        .trim()
