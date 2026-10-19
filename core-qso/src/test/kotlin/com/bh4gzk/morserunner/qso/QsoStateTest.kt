package com.bh4gzk.morserunner.qso

import kotlin.test.Test
import kotlin.test.assertEquals

class QsoStateTest {

    @Test
    fun `vocabulary matches the spec`() {
        assertEquals(
            listOf("Idle", "CallingCq", "WaitingForCall", "SendingExchange", "WaitingForExchange", "Logged"),
            QsoState.entries.map { it.name }
        )
    }
}
