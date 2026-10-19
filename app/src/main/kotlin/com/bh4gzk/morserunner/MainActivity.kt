package com.bh4gzk.morserunner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bh4gzk.morserunner.contest.ContestRegistry

/**
 * T1 placeholder: proves the module graph wires together and Compose renders.
 * Real screens arrive with T7.
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Morse Runner", style = MaterialTheme.typography.headlineMedium)
        Text("模块骨架就位 · T1")
        Text("bundled contests: ${contests.size}")
        Text("api ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.MODEL}")
    }
}
