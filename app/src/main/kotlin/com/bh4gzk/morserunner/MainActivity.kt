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
import com.bh4gzk.morserunner.audio.CwAudioEngine
import com.bh4gzk.morserunner.audio.StationAudio
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.contest.RosterSettings
import com.bh4gzk.morserunner.contest.StationRoster
import com.bh4gzk.morserunner.data.StationAssets
import com.bh4gzk.morserunner.data.StationData
import java.util.Locale

/**
 * Temporary harness: proves the module graph wires together, and lets the T5 pile-up be
 * heard on a device by hand. The real screens arrive with T7.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = StationAssets.load(assets)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PileUpHarness(data)
                }
            }
        }
    }
}

/** Fixed so that a demonstration run is reproducible (ADR-0004's determinism rule). */
private const val ROSTER_SEED = 20_261_019L

/** CQ WW: its only call-history binding is the CQ zone (see `StationData.standInHistory`). */
private const val DEMO_CONTEST = "cqww"

@Composable
private fun PileUpHarness(data: StationData) {
    val engine = remember { CwAudioEngine() }
    val audio = remember { StationAudio() }
    DisposableEffect(Unit) {
        engine.start()
        onDispose { engine.close() }
    }

    var status by remember { mutableStateOf("尚未开始") }
    var stations by remember { mutableStateOf(emptyList<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Morse Runner", style = MaterialTheme.typography.headlineMedium)
        Text("T5 电台名册（core-contest）+ 无锁块混音器（core-audio）")
        Text("呼号池 ${data.pool.size} · DXCC ${data.dxcc.size} · api ${Build.VERSION.SDK_INT}")
        Text("pid ${android.os.Process.myPid()}")

        Button(onClick = {
            engine.stopAll()
            val result = runCatching { buildRoster(data) }
            val roster = result.getOrNull()
            if (roster == null) {
                status = "名册失败：${result.exceptionOrNull()?.message}"
                return@Button
            }
            val specs = roster.stations
            // A real pile-up is everybody calling at once, so each station sends its own
            // CQ — long enough to hear eight carriers together, and the same text the
            // message grid will send in T7.
            specs.forEachIndexed { index, station ->
                val cq = "CQ CQ DE ${station.call} ${station.call} ${station.call} K"
                engine.play(audio.voice(station, index, cq, ROSTER_SEED))
            }
            stations = specs.map { station ->
                String.format(
                    Locale.US,
                    "%s  %2d wpm  %4d Hz  %.2f  %s",
                    station.call,
                    station.speedWpm,
                    station.pitchHz,
                    station.level,
                    station.behaviour.name,
                )
            }
            status = "喊叫堆：${specs.size} 台进入混音，活跃 ${engine.activeVoices}"
        }) { Text("喊叫堆：抽 8 台同时发声") }

        Button(onClick = {
            engine.stopAll()
            status = "已停止"
        }) { Text("停止") }

        Text(status, style = MaterialTheme.typography.bodySmall)
        for (line in stations) {
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Builds the 0–8 station roster from the packaged assets (spec §6). */
private fun buildRoster(data: StationData): StationRoster {
    val bundled = ContestLibrary.bundled()
    check(bundled.errors.isEmpty()) { "bundled configs failed to load: ${bundled.errors}" }
    val config = bundled.configs.firstOrNull { it.id == DEMO_CONTEST }
        ?: error("no bundled '$DEMO_CONTEST' config")
    val roster = StationRoster(
        config = config,
        pool = data.pool,
        history = data.standInHistory(),
        settings = RosterSettings(speedWpm = 25),
        seed = ROSTER_SEED,
        calls = data.callFacts,
    )
    repeat(StationRoster.MAX_STATIONS) { roster.add() }
    return roster
}
