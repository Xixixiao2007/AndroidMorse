package com.bh4gzk.morserunner.qso

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rendering a **station's** reply to CW text — the T6 finding F5.
 *
 * `DxOperator` returns `StationMessage` values; the audio layer needs text. The wording table
 * is upstream's (`Contest.pas:412-450` `SendMsg`), and the tokens are the ones
 * `MessageScript` already expands, with the sender's roles swapped: `<my>` is the **station's**
 * call and `<#>` is the **station's** exchange, because a station renders what it transmits.
 * Upstream's `TStation.SendText` (`Station.pas:242-300`) is the same substitution.
 */
class StationScriptTest {

    /** DL1ABC, sending `599 14` — a CQ WW station. */
    private val station = Operator("DL1ABC", OperatorExchange("599", "14"))

    private val script = StationScript(station)

    @Test
    fun `a station sends its call and its own exchange`() {
        assertEquals("DL1ABC", script.text(StationMessage.MY_CALL))
        assertEquals("DL1ABC DL1ABC", script.text(StationMessage.MY_CALL2))
        // Need_Nr: `R <exchange>` (Contest.pas:441-442).
        assertEquals("R 599 14", script.text(StationMessage.R_EXCHANGE))
        assertEquals("R 599 14 599 14", script.text(StationMessage.R_EXCHANGE2))
        assertEquals("599 14", script.text(StationMessage.EXCHANGE))
    }

    @Test
    fun `the correction replies carry the station call and exchange`() {
        // Need_Call / Need_Call_Nr (Contest.pas:443-450).
        assertEquals("DE DL1ABC", script.text(StationMessage.DE_MY_CALL))
        assertEquals("DE DL1ABC DL1ABC", script.text(StationMessage.DE_MY_CALL2))
        assertEquals("DE DL1ABC 599 14", script.text(StationMessage.DE_MY_CALL_EXCH))
        assertEquals("DE DL1ABC DL1ABC 599 14", script.text(StationMessage.DE_MY_CALL2_EXCH))
        assertEquals("DL1ABC 599 14", script.text(StationMessage.MY_CALL_EXCH))
        assertEquals("DL1ABC DL1ABC 599 14", script.text(StationMessage.MY_CALL2_EXCH))
    }

    @Test
    fun `the short replies are upstream's words`() {
        assertEquals("NR?", script.text(StationMessage.NR_QM))
        assertEquals("AGN", script.text(StationMessage.AGN))
        assertEquals("?", script.text(StationMessage.QM))
        assertEquals("NIL", script.text(StationMessage.NIL))
        assertEquals("QSO B4", script.text(StationMessage.B4))
    }

    @Test
    fun `silence renders as nothing, not as a stray word`() {
        assertEquals("", script.text(StationMessage.NONE))
        assertEquals("", script.text(StationMessage.GARBAGE))
    }

    @Test
    fun `a CQ from a station uses the contest wording with the station as my call`() {
        // ARRL SS overrides `CQ` (spec §4 `messages`); the token is the *sender*, so the
        // same wording renders the station's call when a station calls CQ.
        val ss = StationScript(station, mapOf("CQ" to "CQ SS <my>"))
        assertEquals("CQ SS DL1ABC", ss.text(StationMessage.CQ))
        // Without an override the upstream default applies (Contest.pas:416).
        assertEquals("CQ DL1ABC TEST", script.text(StationMessage.CQ))
    }

    @Test
    fun `a station echoes the operator call through his`() {
        assertEquals("W1AW", script.text(StationMessage.HIS_CALL, hisCall = "W1AW"))
        // A blank operator call renders as nothing rather than the literal token.
        assertEquals("", script.text(StationMessage.HIS_CALL))
    }
}
