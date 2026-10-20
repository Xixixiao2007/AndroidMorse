package com.bh4gzk.morserunner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Proof-of-wiring screen for route C (docs/adr/0020).
 *
 * Two kinds of evidence live here:
 *  1. on load — the version banner and the full contest list come out of `libmrcore.so`, i.e. out of
 *     the ORIGINAL Pascal code; nothing is re-implemented in Kotlin;
 *  2. on TAP — the button below asks the core for one contest at a time and shows the answer, so a
 *     real user gesture (not just a screen load) is what drives the native call.
 *
 * This is the smallest thing that can honestly claim "a UI action calls the Pascal core and gets a
 * correct value back". The full Main.dfm layout replaces this screen; the binding stays.
 */
class CoreProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CoreProbeScreen() } }
    }
}

@Composable
private fun CoreProbeScreen() {
    var version by remember { mutableStateOf("(loading)") }
    var rows by remember { mutableStateOf(emptyList<String>()) }
    var tapped by remember { mutableStateOf("") }
    var tapCount by remember { mutableStateOf(0) }
    var started by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        version = runCatching { MorseCore.version() }.getOrElse { "ERROR: ${it.message}" }
        rows = runCatching {
            (0 until MorseCore.contestCount()).map { i ->
                "${i + 1}. ${MorseCore.contestName(i)}  [${MorseCore.contestKey(i)}]"
            }
        }.getOrElse { listOf("ERROR: " + it.message) }

        // Proof that a real contest object can be built inside the core: the class name that
        // comes back is the Pascal subclass (TCqWpx and friends), not a Kotlin stand-in.
        started = runCatching {
            val h = MorseCore.contestStart(0)
            if (h == 0L) {
                "contestStart(0) FAILED (core returned nil)"
            } else {
                val cls = MorseCore.contestClass(h)
                MorseCore.contestFree(h)
                "contestStart(0) -> " + cls
            }
        }.getOrElse { "ERROR: " + it.message }
        android.util.Log.i("MorseCoreProbe", "started=" + started)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pascal core: $version", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Contest definitions from the core: " + rows.size, style = MaterialTheme.typography.bodyMedium)
        if (started.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(started, style = MaterialTheme.typography.titleSmall)
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                // Each tap crosses into libmrcore.so and asks for the next contest definition.
                val n = runCatching { MorseCore.contestCount() }.getOrDefault(0)
                if (n > 0) {
                    val idx = tapCount % n
                    val name = runCatching { MorseCore.contestName(idx) }.getOrNull()
                    val key = runCatching { MorseCore.contestKey(idx) }.getOrNull()
                    tapped = "tap #${tapCount + 1} -> [$idx] $name (key=$key)"
                } else {
                    tapped = "tap #${tapCount + 1} -> core reported no contests"
                }
                tapCount += 1
            },
            modifier = Modifier.testTag("askCore"),
        ) {
            Text("Ask the core for the next contest")
        }
        if (tapped.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(tapped, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LazyColumn { items(rows) { Text(it) } }
        }
    }
}
