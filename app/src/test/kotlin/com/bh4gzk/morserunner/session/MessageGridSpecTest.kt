package com.bh4gzk.morserunner.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §8's message grid, pinned as data.
 *
 * The layout is human-mandated (two rows of four) and the long-press pairs are easy to get
 * backwards, so the table is a value here instead of being spread over the composable.
 */
class MessageGridSpecTest {

    @Test
    fun `the grid is two rows of four in the mandated order`() {
        val rows = MessageGridSpec.rows
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.size == 4 })

        assertEquals(
            listOf("CQ", "EXCH", "TU", "NR?"),
            rows[0].map { it.label },
            "the first row is CQ / EXCH / TU / NR? (spec §8)",
        )
        assertEquals(
            listOf("?", "B4", "NIL", "WIPE"),
            rows[1].map { it.label },
            "the second row is ? / B4 / NIL / WIPE (spec §8)",
        )
    }

    @Test
    fun `each cell shows its function key`() {
        assertEquals(
            listOf("F1", "F2", "F3", "F12"),
            MessageGridSpec.rows[0].map { it.keyLabel },
        )
        assertEquals(
            listOf("F7", "F6", "F8", "F11"),
            MessageGridSpec.rows[1].map { it.keyLabel },
        )
    }

    @Test
    fun `CQ and TU carry the two long-press messages`() {
        val cells = MessageGridSpec.rows.flatten()
        assertEquals(
            KeyAction.Send(com.bh4gzk.morserunner.qso.QsoMessage.MY_CALL),
            cells.single { it.label == "CQ" }.longPress,
            "long-press CQ sends MYCALL (F4)",
        )
        assertEquals(
            KeyAction.Send(com.bh4gzk.morserunner.qso.QsoMessage.HIS_CALL),
            cells.single { it.label == "TU" }.longPress,
            "long-press TU sends HISCALL (F5)",
        )
        assertEquals(
            listOf("CQ", "TU"),
            cells.filter { it.longPress != null }.map { it.label },
            "no other cell has a long-press action",
        )
    }

    @Test
    fun `every cell's press is the action its key label names`() {
        val byKey = MessageGridSpec.rows.flatten().associateBy { it.keyLabel }
        for ((label, key) in MessageGridSpec.rows.flatten().map { it.label to it.keyLabel }) {
            val fromKeyboard = SessionKeys.action(PhysicalKey.valueOf(key))
            assertEquals(
                byKey.getValue(key).press,
                fromKeyboard,
                "the $label button and the $key key must do the same thing",
            )
        }
    }

    @Test
    fun `WIPE clears rather than sends`() {
        val wipe = MessageGridSpec.rows.flatten().single { it.label == "WIPE" }
        assertEquals(KeyAction.Wipe, wipe.press)
        assertEquals(null, wipe.longPress)
    }
}
