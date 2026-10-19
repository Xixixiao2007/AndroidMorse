package com.bh4gzk.morserunner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bh4gzk.morserunner.audio.Receiver
import com.bh4gzk.morserunner.settings.SessionSettings

/**
 * The settings screen of spec §11 T9 — the one place the session is configured.
 *
 * **Where it lives.** Spec §8 fixes the session screen's layout and the 2×4 grid's meaning, so
 * the entry point is a small button at the right end of the status bar and the settings are a
 * dialog over the session: no grid cell changes meaning, and the operator is one tap from the
 * contest. ADR-0009 records the choice and what was rejected (a nav drawer, a separate
 * activity, a long-press on the status bar).
 *
 * **When a value acts.** The dialog is not a live mixing desk:
 *  - `保存` persists and applies the receive controls (RIT) to the running contest — that is
 *    what a RIT knob does (`Main.pas`'s arrow buttons), and the next transmission is already
 *    shifted;
 *  - `重开一局` persists and starts a new contest with the new values, because duration, speed,
 *    pitch, bandwidth and the effect switches are baked into the roster, the contest clock and
 *    the audio engine when a session starts (ADR-0009 §"when a value acts").
 *
 * The footer says exactly that, so no setting can look broken while it is simply waiting for
 * the next session.
 *
 * The controls are upstream's own (`Main.pas`): two spin edits, two combo boxes, RIT arrows and
 * four checkboxes — with the same ranges (spec §11).
 */
@Composable
fun SettingsDialog(
    settings: SessionSettings,
    onSave: (SessionSettings) -> Unit,
    onRestart: (SessionSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(settings.normalized()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            // The controls scroll, the footer does not: on a short window (WSA's is 540 dp tall)
            // the buttons would otherwise be clipped, and "保存" must always be reachable.
            Column(modifier = Modifier.padding(16.dp).fillMaxHeight(0.9f)) {
                Text("设置", style = MaterialTheme.typography.titleLarge)

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Stepper(
                        label = "时长",
                        value = "${draft.durationMinutes} 分",
                        onMinus = {
                            draft = draft.copy(
                                durationMinutes = (draft.durationMinutes - 1).coerceIn(
                                    SessionSettings.MIN_DURATION_MINUTES,
                                    SessionSettings.MAX_DURATION_MINUTES,
                                ),
                            )
                        },
                        onPlus = {
                            draft = draft.copy(
                                durationMinutes = (draft.durationMinutes + 1).coerceIn(
                                    SessionSettings.MIN_DURATION_MINUTES,
                                    SessionSettings.MAX_DURATION_MINUTES,
                                ),
                            )
                        },
                    )
                    Stepper(
                        label = "速度",
                        value = "${draft.speedWpm} WPM",
                        onMinus = {
                            draft = draft.copy(
                                speedWpm = (draft.speedWpm - 1).coerceIn(
                                    SessionSettings.MIN_SPEED_WPM,
                                    SessionSettings.MAX_SPEED_WPM,
                                ),
                            )
                        },
                        onPlus = {
                            draft = draft.copy(
                                speedWpm = (draft.speedWpm + 1).coerceIn(
                                    SessionSettings.MIN_SPEED_WPM,
                                    SessionSettings.MAX_SPEED_WPM,
                                ),
                            )
                        },
                    )
                    Stepper(
                        label = "音高",
                        value = "${draft.pitchHz} Hz",
                        onMinus = {
                            draft = draft.copy(pitchHz = Receiver.pitch(draft.pitchHz - Receiver.PITCH_STEP_HZ))
                        },
                        onPlus = {
                            draft = draft.copy(pitchHz = Receiver.pitch(draft.pitchHz + Receiver.PITCH_STEP_HZ))
                        },
                    )
                    Stepper(
                        label = "RIT",
                        value = if (draft.ritHz > 0) "+${draft.ritHz} Hz" else "${draft.ritHz} Hz",
                        onMinus = {
                            draft = draft.copy(ritHz = Receiver.rit(draft.ritHz - Receiver.RIT_STEP_HZ))
                        },
                        onPlus = {
                            draft = draft.copy(ritHz = Receiver.rit(draft.ritHz + Receiver.RIT_STEP_HZ))
                        },
                    )
                    Stepper(
                        label = "带宽",
                        value = "${draft.bandwidthHz} Hz",
                        onMinus = {
                            draft = draft.copy(
                                bandwidthHz = Receiver.bandwidth(draft.bandwidthHz - Receiver.BANDWIDTH_STEP_HZ),
                            )
                        },
                        onPlus = {
                            draft = draft.copy(
                                bandwidthHz = Receiver.bandwidth(draft.bandwidthHz + Receiver.BANDWIDTH_STEP_HZ),
                            )
                        },
                    )

                    OutlinedTextField(
                        value = draft.myCall,
                        onValueChange = { draft = draft.copy(myCall = it) },
                        label = { Text("我的呼号") },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text("效果", style = MaterialTheme.typography.labelLarge)
                    Toggle("QRN 背景噪声", draft.qrn) { draft = draft.copy(qrn = it) }
                    Toggle("QRM 干扰台", draft.qrm) { draft = draft.copy(qrm = it) }
                    Toggle("Flutter 快速抖动", draft.flutter) { draft = draft.copy(flutter = it) }
                    Toggle("LIDs 新手算子", draft.lids) { draft = draft.copy(lids = it) }
                }

                Text(
                    "RIT 保存后立即生效；其余设置在下一局生效（重开一局）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onSave(draft) }) { Text("保存") }
                    TextButton(onClick = { onRestart(draft) }) { Text("重开一局") }
                }
            }
        }
    }
}

/** One numeric setting: a label, its value, and the two arrow buttons upstream uses. */
@Composable
private fun Stepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−") }
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 84.dp),
        )
        TextButton(onClick = onPlus) { Text("+") }
    }
}

/** One effect switch (upstream's `CheckBox2..6`). */
@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
