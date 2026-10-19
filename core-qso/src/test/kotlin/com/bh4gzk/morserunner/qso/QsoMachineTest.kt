package com.bh4gzk.morserunner.qso

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Enter` and the message queue, transcribed from `Main.pas:952-1093 ProcessEnter` and
 * `MyStn.pas:108-115 AbortSend` (ADR-0005 §1/§4).
 */
class QsoMachineTest {

    private val script = MessageScript(
        Operator("W1AW", OperatorExchange("5NN", "3")),
    )

    private fun machine(rules: ExchangeRules = ExchangeRules.AcceptAll) = QsoMachine(script, rules)

    /** The zone rule of cqww.json, expressed as a stand-in for [ContestRules]. */
    private val zoneOnly: ExchangeRules = ExchangeRules { entry ->
        if (entry.exch2 == "99") listOf("Invalid CQ zone") else emptyList()
    }

    private fun completeEntry(call: String = "DL1ABC", exch1: String = "599", exch2: String = "14") =
        QsoEntry(call = call, exch1 = exch1, exch2 = exch2)

    @Test
    fun `enter with an empty call sends CQ`() {
        val machine = machine()

        val outcome = machine.enter()

        assertEquals(listOf(QsoMessage.CQ), outcome.messages)
        assertFalse(outcome.logged)
        assertEquals(QsoState.CallingCq, machine.state)
        assertEquals(listOf(QsoMessage.CQ), machine.pending)
        assertEquals(listOf("CQ W1AW TEST"), outcome.sentText)
    }

    @Test
    fun `a finished CQ puts me on the receiving end`() {
        val machine = machine()
        machine.enter()

        machine.onMyMessageSent()

        assertEquals(QsoState.WaitingForCall, machine.state)
        assertNull(machine.currentMessage)
    }

    @Test
    fun `a caller finishing their audio makes it my turn`() {
        val machine = machine()
        machine.enter()
        machine.onMyMessageSent()

        machine.onTheirMessageComplete()

        assertEquals(QsoState.SendingExchange, machine.state)
    }

    @Test
    fun `enter with a call sends his call and then my exchange`() {
        val machine = machine()
        machine.updateEntry(completeEntry())

        val outcome = machine.enter()

        assertEquals(listOf(QsoMessage.HIS_CALL, QsoMessage.EXCHANGE), outcome.messages)
        assertEquals(QsoState.SendingExchange, machine.state)
        assertTrue(machine.callSent)
        assertTrue(machine.nrSent)
        assertEquals(listOf("DL1ABC", "5NN 3"), outcome.sentText)
    }

    @Test
    fun `sending both messages means waiting for his exchange`() {
        val machine = machine()
        machine.updateEntry(completeEntry())
        machine.enter()

        machine.onMyMessageSent()
        machine.onMyMessageSent()

        assertEquals(QsoState.WaitingForExchange, machine.state)
        assertNull(machine.currentMessage)
    }

    @Test
    fun `a complete exchange logs the contact and sends TU`() {
        val machine = machine()
        machine.updateEntry(completeEntry())
        machine.enter()
        machine.onMyMessageSent()
        machine.onMyMessageSent()

        val outcome = machine.enter()

        assertEquals(listOf(QsoMessage.TU), outcome.messages)
        assertTrue(outcome.logged)
        assertEquals(QsoState.Logged, machine.state)
        // SaveQso wipes the boxes (Log.pas:764).
        assertEquals(QsoEntry(), machine.entry)
        assertFalse(machine.callSent)
        assertFalse(machine.nrSent)
    }

    @Test
    fun `a short call gets his call but not my exchange`() {
        val machine = machine()
        machine.updateEntry(completeEntry(call = "W1"))

        val outcome = machine.enter()

        // ProcessEnter: ValidCall = no '?' and at least three characters.
        assertEquals(listOf(QsoMessage.HIS_CALL), outcome.messages)
        assertFalse(machine.nrSent)
    }

    @Test
    fun `a call with a question mark is not a call`() {
        val machine = machine()
        machine.updateEntry(completeEntry(call = "W1AW?"))

        val outcome = machine.enter()

        assertEquals(listOf(QsoMessage.HIS_CALL), outcome.messages)
        assertFalse(machine.nrSent)
    }

    @Test
    fun `editing the call resends it, then the contact still completes`() {
        val machine = machine()
        machine.updateEntry(completeEntry())
        machine.enter()
        machine.onMyMessageSent()
        machine.onMyMessageSent()

        machine.updateEntry(completeEntry(call = "DL1ABCX"))
        val outcome = machine.enter()

        // CallSent is false once the box no longer matches what went out, so his call is sent
        // again; the exchange and the logging still happen because N is set (ProcessEnter's
        // "if R and Q and (C or N)").
        assertEquals(listOf(QsoMessage.HIS_CALL, QsoMessage.TU), outcome.messages)
        assertTrue(outcome.logged)
    }

    @Test
    fun `pressing enter again without his exchange asks '?'`() {
        val machine = machine()
        machine.updateEntry(completeEntry(exch2 = ""))
        machine.enter()                                  // his call + my exchange
        machine.onMyMessageSent()
        machine.onMyMessageSent()

        val outcome = machine.enter()

        // N is set, R is not: ask him to repeat.
        assertEquals(listOf(QsoMessage.QM), outcome.messages)
        assertFalse(outcome.logged)
    }

    @Test
    fun `an invalid exchange is not logged, and the retry asks '?'`() {
        val machine = machine(zoneOnly)
        machine.updateEntry(completeEntry(exch2 = "99"))

        val first = machine.enter()
        assertEquals(listOf(QsoMessage.HIS_CALL, QsoMessage.EXCHANGE), first.messages)
        assertFalse(first.logged)

        machine.onMyMessageSent()
        machine.onMyMessageSent()
        val second = machine.enter()

        assertEquals(listOf(QsoMessage.QM), second.messages)
        assertFalse(second.logged)
        assertEquals(listOf("Invalid CQ zone"), second.errors)
    }

    @Test
    fun `abort clears the queue and reports the send as finished`() {
        val machine = machine()
        machine.updateEntry(completeEntry())
        machine.send(QsoMessage.EXCHANGE)
        assertTrue(machine.nrSent)

        machine.abort()

        // TMyStation.AbortSend: drop the pieces, then fire evMsgSent.
        assertTrue(machine.pending.isEmpty())
        assertEquals(QsoState.WaitingForExchange, machine.state)
        assertFalse(machine.nrSent, "an unsent exchange is no longer sent")
    }

    @Test
    fun `aborting a CQ leaves me listening`() {
        val machine = machine()
        machine.send(QsoMessage.CQ)

        machine.abort()

        assertEquals(QsoState.WaitingForCall, machine.state)
    }

    @Test
    fun `wipe clears the boxes, the flags and the queue`() {
        val machine = machine()
        machine.updateEntry(completeEntry())
        machine.enter()

        machine.wipe()

        assertEquals(QsoEntry(), machine.entry)
        assertFalse(machine.callSent)
        assertFalse(machine.nrSent)
        assertEquals(QsoState.Idle, machine.state)
        assertTrue(machine.pending.isEmpty())
    }

    @Test
    fun `the queue keeps the order the operator pressed`() {
        val machine = machine()

        machine.send(QsoMessage.HIS_CALL)
        machine.send(QsoMessage.EXCHANGE)

        assertEquals(QsoMessage.HIS_CALL, machine.currentMessage)
        machine.onMyMessageSent()
        assertEquals(QsoMessage.EXCHANGE, machine.currentMessage)
        machine.onMyMessageSent()
        assertNull(machine.currentMessage)
    }

    @Test
    fun `a manual his-call send marks the call as sent`() {
        val machine = machine()
        machine.updateEntry(completeEntry())

        machine.send(QsoMessage.HIS_CALL)

        assertTrue(machine.callSent)
        assertEquals(QsoState.SendingExchange, machine.state)
    }

    @Test
    fun `after TU the next CQ starts a new cycle`() {
        val machine = machine()
        machine.updateEntry(completeEntry())
        machine.enter()
        machine.onMyMessageSent()
        machine.onMyMessageSent()
        machine.enter()                                  // logged, TU queued
        machine.onMyMessageSent()                        // TU done

        assertEquals(QsoState.Idle, machine.state)
        machine.enter()                                  // entry was wiped
        assertEquals(QsoState.CallingCq, machine.state)
    }

    @Test
    fun `entering an empty call mid-QSO sends CQ`() {
        val machine = machine()
        machine.updateEntry(completeEntry())
        machine.enter()
        machine.onMyMessageSent()
        machine.onMyMessageSent()
        machine.updateEntry(QsoEntry())                  // operator gives up on this one

        val outcome = machine.enter()

        assertEquals(listOf(QsoMessage.CQ), outcome.messages)
        assertEquals(QsoState.CallingCq, machine.state)
    }

    @Test
    fun `his call is remembered for the renderer`() {
        val machine = machine()
        machine.updateEntry(completeEntry(call = "DL1ABC"))

        machine.enter()

        assertEquals("DL1ABC", machine.hisCall)
        assertEquals(listOf("DL1ABC", "5NN 3"), machine.pendingText())
    }
}
