package com.bh4gzk.morserunner.session

import com.bh4gzk.morserunner.qso.QsoMessage

/**
 * The keys the UI cares about, named so the mapping table is testable without Compose.
 *
 * The composable translates `androidx.compose.ui.input.key.Key` to this enum; everything after
 * that is [SessionKeys] and JVM tests.
 */
enum class PhysicalKey {
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    Enter,
    NumPadEnter,
    Space,
    Escape,

    /** Any key the session does not bind; the UI lets it through to the focused field. */
    Other,
}

/** What a key or a grid button asks the session to do. */
sealed interface KeyAction {

    /** Transmit [message] (the grid's buttons and the function keys). */
    data class Send(val message: QsoMessage) : KeyAction

    /** `Enter`: the state-dependent send, or log the contact (`Main.pas:952-1093`). */
    data object Enter : KeyAction

    /** `F11`: clear the boxes, the flags and the send queue (`Main.pas:1817-1838`). */
    data object Wipe : KeyAction

    /** `Esc`: stop sending (`MyStn.pas:108-115`). */
    data object Abort : KeyAction

    /** `Space`: move to the next entry box; focus belongs to the UI, not the session. */
    data object AdvanceField : KeyAction
}

/**
 * Spec §8 / `Readme.txt:229-238` ("KEY ASSIGNMENTS"), transcribed.
 *
 * `F9`/`F10` are deliberately unbound: upstream changes speed with them and speed is T9's.
 */
object SessionKeys {

    /** The action a physical key asks for, or `null` when the key is not ours. */
    fun action(key: PhysicalKey): KeyAction? = when (key) {
        PhysicalKey.F1 -> KeyAction.Send(QsoMessage.CQ)
        PhysicalKey.F2 -> KeyAction.Send(QsoMessage.EXCHANGE)
        PhysicalKey.F3 -> KeyAction.Send(QsoMessage.TU)
        PhysicalKey.F4 -> KeyAction.Send(QsoMessage.MY_CALL)
        PhysicalKey.F5 -> KeyAction.Send(QsoMessage.HIS_CALL)
        PhysicalKey.F6 -> KeyAction.Send(QsoMessage.B4)
        PhysicalKey.F7 -> KeyAction.Send(QsoMessage.QM)
        PhysicalKey.F8 -> KeyAction.Send(QsoMessage.NIL)
        PhysicalKey.F11 -> KeyAction.Wipe
        PhysicalKey.F12 -> KeyAction.Send(QsoMessage.NR_QM)
        PhysicalKey.Enter, PhysicalKey.NumPadEnter -> KeyAction.Enter
        PhysicalKey.Space -> KeyAction.AdvanceField
        PhysicalKey.Escape -> KeyAction.Abort
        PhysicalKey.F9, PhysicalKey.F10, PhysicalKey.Other -> null
    }
}

/** One button of the 2×4 message grid (spec §8). */
data class GridCell(
    val label: String,
    /** The physical key that does the same thing, shown on the button. */
    val keyLabel: String,
    val press: KeyAction,
    val longPress: KeyAction? = null,
)

/**
 * Spec §8's grid, as data.
 *
 * Two rows of four in the mandated order; long-press CQ sends MYCALL and long-press TU sends
 * HISCALL. Keeping it a table (instead of four `if`s spread over the composable) is what lets
 * the layout and the key mapping be checked against each other.
 */
object MessageGridSpec {

    val rows: List<List<GridCell>> = listOf(
        listOf(
            GridCell("CQ", "F1", KeyAction.Send(QsoMessage.CQ), longPress = KeyAction.Send(QsoMessage.MY_CALL)),
            GridCell("EXCH", "F2", KeyAction.Send(QsoMessage.EXCHANGE)),
            GridCell("TU", "F3", KeyAction.Send(QsoMessage.TU), longPress = KeyAction.Send(QsoMessage.HIS_CALL)),
            GridCell("NR?", "F12", KeyAction.Send(QsoMessage.NR_QM)),
        ),
        listOf(
            GridCell("?", "F7", KeyAction.Send(QsoMessage.QM)),
            GridCell("B4", "F6", KeyAction.Send(QsoMessage.B4)),
            GridCell("NIL", "F8", KeyAction.Send(QsoMessage.NIL)),
            GridCell("WIPE", "F11", KeyAction.Wipe),
        ),
    )
}
