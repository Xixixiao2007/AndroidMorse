package com.bh4gzk.morserunner.qso

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The message vocabulary and its text (spec §2 "Message"; upstream `TStationMessage`,
 * `Contest.pas:412-450`, `Station.pas:242-300`).
 */
class MessagesTest {

    private val me = Operator(
        call = "W1AW",
        exchange = OperatorExchange(exch1 = "5NN", exch2 = "3"),
    )

    private fun script(wordings: Map<String, String> = emptyMap()) = MessageScript(me, wordings)

    @Test
    fun `the default wordings are upstream's`() {
        val script = script()

        assertEquals("CQ W1AW TEST", script.text(QsoMessage.CQ))
        assertEquals("CQ CQ TEST W1AW W1AW TEST", script.text(QsoMessage.LONG_CQ))
        assertEquals("5NN 3", script.text(QsoMessage.EXCHANGE))
        assertEquals("TU", script.text(QsoMessage.TU))
        assertEquals("W1AW", script.text(QsoMessage.MY_CALL))
        assertEquals("QSO B4", script.text(QsoMessage.B4))
        assertEquals("?", script.text(QsoMessage.QM))
        assertEquals("NIL", script.text(QsoMessage.NIL))
        assertEquals("NR?", script.text(QsoMessage.NR_QM))
        assertEquals("AGN", script.text(QsoMessage.AGN))
    }

    @Test
    fun `his call is the called station, not a placeholder`() {
        assertEquals("DL1ABC", script().text(QsoMessage.HIS_CALL, hisCall = "DL1ABC"))
        assertEquals("", script().text(QsoMessage.HIS_CALL, hisCall = ""))
    }

    @Test
    fun `a contest may override the CQ wording`() {
        // arrlss.json: "CQ SS <my>" / "CQ CQ SS <my> <my> SS".
        val ss = script(
            mapOf(
                "CQ" to "CQ SS <my>",
                "longCQ" to "CQ CQ SS <my> <my> SS",
            ),
        )

        assertEquals("CQ SS W1AW", ss.text(QsoMessage.CQ))
        assertEquals("CQ CQ SS W1AW W1AW SS", ss.text(QsoMessage.LONG_CQ))
    }

    @Test
    fun `an override still expands the tokens`() {
        val custom = script(mapOf("CQ" to "TEST <my>"))

        assertEquals("TEST W1AW", custom.text(QsoMessage.CQ))
    }

    @Test
    fun `an override for one message leaves the others at their defaults`() {
        val custom = script(mapOf("TU" to "TU <my>"))

        assertEquals("TU W1AW", custom.text(QsoMessage.TU))
        assertEquals("CQ W1AW TEST", custom.text(QsoMessage.CQ))
    }

    @Test
    fun `an unknown wording key is ignored rather than fatal`() {
        // The loader accepts any string key; a typo must not kill the contest (ADR-0005 §7).
        val custom = script(mapOf("cq" to "nope"))

        assertEquals("CQ W1AW TEST", custom.text(QsoMessage.CQ))
    }

    @Test
    fun `the exchange is one piece when the second is empty`() {
        val single = MessageScript(
            Operator("W1AW", OperatorExchange(exch1 = "5NN", exch2 = "")),
        )

        assertEquals("5NN", single.text(QsoMessage.EXCHANGE))
    }

    @Test
    fun `every message has a config key, and it is unique`() {
        val keys = QsoMessage.entries.map { it.configKey }

        assertEquals(keys.size, keys.toSet().size, "duplicate config key")
        assertTrue(keys.all { it.isNotBlank() })
    }
}
