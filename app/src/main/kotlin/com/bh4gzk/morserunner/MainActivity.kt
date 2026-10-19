package com.bh4gzk.morserunner

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bh4gzk.morserunner.audio.CwAudioTrack
import com.bh4gzk.morserunner.audio.CwSynthesizer
import com.bh4gzk.morserunner.audio.MorseEncoder
import com.bh4gzk.morserunner.audio.MorseTiming
import com.bh4gzk.morserunner.audio.toPcm16
import com.bh4gzk.morserunner.contest.ContestRegistry
import java.util.Locale

/**
 * Temporary harness: proves the module graph wires together, and lets T4's audio
 * path be exercised on a device by hand. The real screens arrive with T7.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SkeletonScreen()
                }
            }
        }
    }
}

@Composable
private fun SkeletonScreen() {
    val contests = ContestRegistry.bundledIds()
    val timing = remember { MorseTiming(20) }
    val sink = remember { CwAudioTrack() }
    DisposableEffect(Unit) {
        onDispose { sink.close() }
    }
    var status by remember { mutableStateOf("尚未播放") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Morse Runner", style = MaterialTheme.typography.headlineMedium)
        Text("模块骨架就位 · T4 音频自测")
        Text("bundled contests: ${contests.size}")
        Text("api ${Build.VERSION.SDK_INT} / ${Build.MODEL}")
        Text("20 wpm → dit ${fmt(timing.ditMs)} ms, 字符间隔 ${fmt(timing.characterGapMs)} ms," +
            " 词间隔 ${fmt(timing.wordGapMs)} ms")

        Button(onClick = {
            val synth = CwSynthesizer(rate = sink.sampleRate)
            val elements = MorseEncoder.encode("CQ TEST CQ TEST", timing)
            val audio = synth.render(elements, pitchHz = 700.0, amplitude = 0.35)
            sink.play(audio.toPcm16())
            status = "播放中：${elements.size} 个单元 / ${audio.size} 个样本"
        }) {
            Text("播放 CQ TEST（20 wpm, 700 Hz）")
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)
