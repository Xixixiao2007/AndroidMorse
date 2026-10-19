package com.bh4gzk.morserunner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bh4gzk.morserunner.audio.SessionAudio
import com.bh4gzk.morserunner.session.GridCell
import com.bh4gzk.morserunner.session.KeyAction
import com.bh4gzk.morserunner.session.MessageGridSpec
import com.bh4gzk.morserunner.session.PhysicalKey
import com.bh4gzk.morserunner.session.SessionController
import com.bh4gzk.morserunner.session.SessionKeys
import com.bh4gzk.morserunner.session.SessionUiState
import kotlinx.coroutines.delay

/** What the screen can ask the session to do (spec §8's inputs). */
class SessionEvents(
    val onEditCall: (String) -> Unit,
    val onEditExchange1: (String) -> Unit,
    val onEditExchange2: (String) -> Unit,
    val onEnter: () -> Unit,
    val onPerform: (KeyAction) -> Unit,
)

/**
 * The session screen (spec §8), top to bottom: status bar, signal panel, entry, 2×4 grid.
 *
 * Landscape moves the grid to a right-hand column and gives the signal panel the extra height,
 * exactly as spec §8 asks. Rotation does not restart the contest: the activity declares
 * `configChanges="orientation|screenSize"`, and the controller lives outside the composition.
 *
 * The tick loop is the session's clock: every 40 ms it advances the controller, plays whatever
 * came due, and refreshes the snapshot. Nothing here reads a wall clock for game logic; the
 * controller does not even have one.
 */
@Composable
fun SessionRoot(controller: SessionController, audio: SessionAudio, note: String) {
    var ui by remember { mutableStateOf(controller.uiState) }
    val focusManager = LocalFocusManager.current
    val rootFocus = remember { FocusRequester() }

    fun refresh() {
        ui = controller.uiState
    }

    val events = remember(controller) {
        SessionEvents(
            onEditCall = { controller.editCall(it); ui = controller.uiState },
            onEditExchange1 = { controller.editExchange1(it); ui = controller.uiState },
            onEditExchange2 = { controller.editExchange2(it); ui = controller.uiState },
            onEnter = {
                focusManager.clearFocus()
                controller.enter()
                ui = controller.uiState
            },
            onPerform = {
                controller.perform(it)
                ui = controller.uiState
            },
        )
    }

    LaunchedEffect(controller, audio) {
        audio.start()
        while (true) {
            controller.tick(System.currentTimeMillis())
            for (cue in controller.drainCues()) audio.play(cue)
            // The interference is its own stream (spec §7's QRM): it can never reorder the
            // QSO's cues, and it is a steady signal, not a fading station.
            for (cue in controller.drainQrm()) audio.playQrm(cue)
            ui = controller.uiState
            delay(TICK_MS)
        }
    }
    androidx.compose.runtime.DisposableEffect(audio) {
        onDispose { audio.close() }
    }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                handleKey(event, controller, focusManager) { refresh() }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(0.64f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusBar(ui)
                        SignalPanel(ui, Modifier.weight(1f))
                        EntryRow(ui, events)
                        ErrorsAndNote(ui, note)
                    }
                    MessageGrid(ui.finished, events, Modifier.weight(0.36f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusBar(ui)
                    SignalPanel(ui, Modifier.weight(1f))
                    EntryRow(ui, events)
                    ErrorsAndNote(ui, note)
                    MessageGrid(ui.finished, events, Modifier.fillMaxWidth().weight(1.15f))
                }
            }
        }
    }
}

private const val TICK_MS: Long = 40L

/**
 * Translates a key event into the session's vocabulary.
 *
 * `Space` is bound to "next box" (`Readme.txt:235`) rather than to a character: the two entry
 * boxes are the two exchange pieces, and the contest's `entryParse` rejoins them with a space
 * (ARRL SS types `123 A` then `72 OR`), so a space inside a box is never needed.
 */
private fun handleKey(
    event: KeyEvent,
    controller: SessionController,
    focusManager: FocusManager,
    refresh: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val action = SessionKeys.action(event.key.toPhysicalKey()) ?: return false
    if (action is KeyAction.AdvanceField) {
        focusManager.moveFocus(FocusDirection.Next)
        return true
    }
    controller.perform(action)
    refresh()
    return true
}

private fun Key.toPhysicalKey(): PhysicalKey = when (this) {
    Key.F1 -> PhysicalKey.F1
    Key.F2 -> PhysicalKey.F2
    Key.F3 -> PhysicalKey.F3
    Key.F4 -> PhysicalKey.F4
    Key.F5 -> PhysicalKey.F5
    Key.F6 -> PhysicalKey.F6
    Key.F7 -> PhysicalKey.F7
    Key.F8 -> PhysicalKey.F8
    Key.F9 -> PhysicalKey.F9
    Key.F10 -> PhysicalKey.F10
    Key.F11 -> PhysicalKey.F11
    Key.F12 -> PhysicalKey.F12
    Key.Enter -> PhysicalKey.Enter
    Key.NumPadEnter -> PhysicalKey.NumPadEnter
    Key.Spacebar -> PhysicalKey.Space
    Key.Escape -> PhysicalKey.Escape
    else -> PhysicalKey.Other
}

// --- status bar ----------------------------------------------------------------------

@Composable
private fun StatusBar(ui: SessionUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ui.contestName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LabelValue("用时", "${ui.elapsedMs.asClock()} / ${ui.remainingMs.asClock()}")
            LabelValue("QSO", "${ui.qsos}")
            LabelValue("分", "${ui.rawScore}（验 ${ui.verifiedScore}）")
            LabelValue("速率", "${ui.rateLastFiveMinutes}/5m · ${ui.ratePerHour.toInt()}/h")
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// --- signal panel --------------------------------------------------------------------

@Composable
private fun SignalPanel(ui: SessionUiState, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (ui.finished) "时间到 — 本局结束" else "信号面板",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Text(
                ui.stationCall.ifBlank { "— —" },
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
            Text(
                ui.stationExchange.ifBlank { "等待一个台站" },
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (ui.stationCall.isNotBlank()) {
                Text(
                    "${ui.stationSpeedWpm} wpm · ${ui.stationPitchHz} Hz · ${ui.runModeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
            Text(
                "我：${ui.myCall} ${ui.myExchange}".trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Text(
                ui.lastHeard.ifBlank { "还没有听到任何发送" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sending = ui.pendingText.joinToString(" ")
            if (sending.isNotBlank()) {
                Text(
                    "发送中：$sending",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ui.lastStatus.isNotBlank()) {
                Text(
                    ui.lastStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --- entry ---------------------------------------------------------------------------

@Composable
private fun EntryRow(ui: SessionUiState, events: SessionEvents) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = ui.entry.call,
                onValueChange = events.onEditCall,
                label = { Text("呼号") },
                singleLine = true,
                isError = ui.errors.isNotEmpty(),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { events.onEnter() }),
                modifier = Modifier.weight(1.3f),
            )
            OutlinedTextField(
                value = ui.entry.exch1,
                onValueChange = events.onEditExchange1,
                label = { Text(ui.exchangeLabels.getOrElse(0) { "交换 1" }) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.weight(0.85f),
            )
            OutlinedTextField(
                value = ui.entry.exch2,
                onValueChange = events.onEditExchange2,
                label = { Text(ui.exchangeLabels.getOrElse(1) { "交换 2" }) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { events.onEnter() }),
                modifier = Modifier.weight(0.85f),
            )
            TextButton(
                onClick = { events.onPerform(KeyAction.Abort) },
                modifier = Modifier.width(64.dp),
            ) { Text("ESC") }
        }
        if (ui.suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ui.suggestions.forEach { call ->
                    AssistChip(onClick = { events.onEditCall(call) }, label = { Text(call) })
                }
            }
        }
    }
}

@Composable
private fun ErrorsAndNote(ui: SessionUiState, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (ui.errors.isNotEmpty()) {
            Text(
                ui.errors.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (note.isNotBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- the 2×4 grid --------------------------------------------------------------------

@Composable
private fun MessageGrid(finished: Boolean, events: SessionEvents, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MessageGridSpec.rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { cell ->
                    GridButton(cell, finished, events, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridButton(
    cell: GridCell,
    finished: Boolean,
    events: SessionEvents,
    modifier: Modifier,
) {
    val enabled = !finished
    val background = if (enabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(12.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = { events.onPerform(cell.press) },
                onLongClick = cell.longPress?.let { longPress -> { events.onPerform(longPress) } },
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                cell.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                cell.keyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.65f),
            )
        }
    }
}

// --- formatting ----------------------------------------------------------------------

/** `mm:ss`; a contest session is under an hour, so the hour part is not worth the width. */
private fun Long.asClock(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
