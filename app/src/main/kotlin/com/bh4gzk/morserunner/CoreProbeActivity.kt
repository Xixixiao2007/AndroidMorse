package com.bh4gzk.morserunner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Proof-of-wiring screen: it shows the version banner and the contest list that the ORIGINAL
 * Pascal code reports. Nothing here is re-implemented in Kotlin — the numbers and names come
 * out of `libmrcore.so`, so a successful render means the native core is loaded and answering.
 *
 * This is the first slice of route C (docs/adr/0020); the full Main.dfm layout replaces it.
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

    LaunchedEffect(Unit) {
        version = runCatching { MorseCore.version() }.getOrElse { "ERROR: ${it.message}" }
        rows = runCatching {
            (0 until MorseCore.contestCount()).map { i ->
                "${i + 1}. ${MorseCore.contestName(i)}  [${MorseCore.contestKey(i)}]"
            }
        }.getOrElse { listOf("ERROR: ${it.message}") }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pascal core: $version", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Contest definitions read from the core: ${rows.size}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn { items(rows) { Text(it) } }
    }
}
