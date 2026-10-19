package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.qso.QsoMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The physical-keyboard mapping of spec §8 and `Readme.txt:229` ("KEY ASSIGNMENTS").
 *
 * The translation from a Compose `Key` to [PhysicalKey] lives in the composable; this table is
 * pure, so it is provable on the JVM.
 */
class SessionKeysTest {

    @Test
    fun `F1 to F8 and F11 F12 are the operator's messages`() {
        assertEquals(KeyAction.Send(QsoMessage.CQ), SessionKeys.action(PhysicalKey.F1))
        assertEquals(KeyAction.Send(QsoMessage.EXCHANGE), SessionKeys.action(PhysicalKey.F2))
        assertEquals(KeyAction.Send(QsoMessage.TU), SessionKeys.action(PhysicalKey.F3))
        assertEquals(KeyAction.Send(QsoMessage.MY_CALL), SessionKeys.action(PhysicalKey.F4))
        assertEquals(KeyAction.Send(QsoMessage.HIS_CALL), SessionKeys.action(PhysicalKey.F5))
        assertEquals(KeyAction.Send(QsoMessage.B4), SessionKeys.action(PhysicalKey.F6))
        assertEquals(KeyAction.Send(QsoMessage.QM), SessionKeys.action(PhysicalKey.F7))
        assertEquals(KeyAction.Send(QsoMessage.NIL), SessionKeys.action(PhysicalKey.F8))
        assertEquals(KeyAction.Wipe, SessionKeys.action(PhysicalKey.F11))
        assertEquals(KeyAction.Send(QsoMessage.NR_QM), SessionKeys.action(PhysicalKey.F12))
    }

    @Test
    fun `F9 and F10 are not ours`() {
        // Upstream uses them for speed with Alt/Ctrl (Main.pas:866-871); speed is T9's.
        assertNull(SessionKeys.action(PhysicalKey.F9))
        assertNull(SessionKeys.action(PhysicalKey.F10))
    }

    @Test
    fun `Enter Space and Escape keep their upstream meanings`() {
        assertEquals(KeyAction.Enter, SessionKeys.action(PhysicalKey.Enter))
        assertEquals(KeyAction.Enter, SessionKeys.action(PhysicalKey.NumPadEnter))
        // "Space Bar - Auto completes and jumps between input fields" (Readme.txt:235).
        assertEquals(KeyAction.AdvanceField, SessionKeys.action(PhysicalKey.Space))
        // "Escape key - Stops your sending" (Readme.txt:236).
        assertEquals(KeyAction.Abort, SessionKeys.action(PhysicalKey.Escape))
    }

    @Test
    fun `an unrelated key is not intercepted`() {
        assertNull(SessionKeys.action(PhysicalKey.Other))
    }
}
