package com.bh4gzk.morserunner.qso

/**
 * Renders a **station's** transmission to the CW text the keyer plays.
 *
 * The counterpart of [MessageScript] for the other end of the contact. `DxOperator` speaks
 * [StationMessage] (`DxOper.pas` decides *what* to send); this decides *how it reads*. The
 * wordings are upstream's (`Contest.pas:412-450` `SendMsg`) and the tokens are the shared
 * [renderTokens] expansion, with the sender's roles swapped: `<my>` is the station's callsign
 * and `<#>` its own exchange, because a station renders what it transmits
 * (`Station.pas:242-300`).
 *
 * The contest's `messages` map may override a wording by [QsoMessage.configKey] where the
 * station and the operator share a message (CQ, TU, exchange, …); station-only replies
 * (`R <#>`, `DE <my> <#>`, …) have no override upstream and always use the transcribed
 * default.
 *
 * Stateless apart from its two constructor arguments, so one instance serves a session.
 */
class StationScript(
    private val station: Operator,
    private val wordings: Map<String, String> = emptyMap(),
) {

    /** The text for [message]; [hisCall] is the operator being answered, if the message names it. */
    fun text(message: StationMessage, hisCall: String = ""): String {
        val key = configKey(message)
        val template = (if (key != null) wordings[key] else null) ?: default(message)
        return renderTokens(template, station.call, station.exchange.piece, hisCall)
    }

    /**
     * The contest-wording key for a message this direction shares with the operator;
     * `null` for the station-only replies.
     */
    private fun configKey(message: StationMessage): String? = when (message) {
        StationMessage.CQ -> QsoMessage.CQ.configKey
        StationMessage.EXCHANGE -> QsoMessage.EXCHANGE.configKey
        StationMessage.TU -> QsoMessage.TU.configKey
        StationMessage.MY_CALL -> QsoMessage.MY_CALL.configKey
        StationMessage.HIS_CALL -> QsoMessage.HIS_CALL.configKey
        StationMessage.B4 -> QsoMessage.B4.configKey
        StationMessage.QM -> QsoMessage.QM.configKey
        StationMessage.NIL -> QsoMessage.NIL.configKey
        StationMessage.NR_QM -> QsoMessage.NR_QM.configKey
        StationMessage.AGN -> QsoMessage.AGN.configKey
        StationMessage.NONE,
        StationMessage.GARBAGE,
        StationMessage.MY_CALL2,
        StationMessage.R_EXCHANGE,
        StationMessage.R_EXCHANGE2,
        StationMessage.DE_MY_CALL,
        StationMessage.DE_MY_CALL2,
        StationMessage.DE_MY_CALL_EXCH,
        StationMessage.DE_MY_CALL2_EXCH,
        StationMessage.MY_CALL_EXCH,
        StationMessage.MY_CALL2_EXCH,
        -> null
    }

    /** Upstream's default wording per message (`Contest.pas:412-450`). */
    private fun default(message: StationMessage): String = when (message) {
        StationMessage.NONE, StationMessage.GARBAGE -> ""
        StationMessage.CQ -> "CQ <my> TEST"
        StationMessage.EXCHANGE -> "<#>"
        StationMessage.TU -> "TU"
        StationMessage.MY_CALL -> "<my>"
        StationMessage.HIS_CALL -> "<his>"
        StationMessage.B4 -> "QSO B4"
        StationMessage.QM -> "?"
        StationMessage.NIL -> "NIL"
        StationMessage.MY_CALL2 -> "<my> <my>"
        StationMessage.R_EXCHANGE -> "R <#>"
        StationMessage.R_EXCHANGE2 -> "R <#> <#>"
        StationMessage.DE_MY_CALL -> "DE <my>"
        StationMessage.DE_MY_CALL2 -> "DE <my> <my>"
        StationMessage.DE_MY_CALL_EXCH -> "DE <my> <#>"
        StationMessage.DE_MY_CALL2_EXCH -> "DE <my> <my> <#>"
        StationMessage.MY_CALL_EXCH -> "<my> <#>"
        StationMessage.MY_CALL2_EXCH -> "<my> <my> <#>"
        StationMessage.NR_QM -> "NR?"
        StationMessage.AGN -> "AGN"
    }
}
