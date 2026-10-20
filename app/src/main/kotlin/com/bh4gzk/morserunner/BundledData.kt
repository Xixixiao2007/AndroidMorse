package com.bh4gzk.morserunner

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The Pascal core reads `DXCC.LIST` and the contest call histories by name from one directory
 * ([MorseCore.setDataDir]). The APK ships them under `assets/data`; copy each into `filesDir`
 * (idempotent: only when missing or a different size, so a newer asset replaces a stale copy) and
 * point the core at that directory.
 *
 * Both entry points need this — the form and the core probe — because a run without the call
 * history has no callsigns to put on the air.
 */
internal object BundledData {
    private const val TAG = "BundledData"
    private const val ASSET_DIR = "data"

    fun install(context: Context) {
        val dir = context.filesDir
        runCatching {
            context.assets.list(ASSET_DIR)?.forEach { name ->
                val target = File(dir, name)
                val sourceBytes = context.assets.open("$ASSET_DIR/$name").use { it.available().toLong() }
                if (!target.exists() || target.length() != sourceBytes) {
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "copying $ASSET_DIR from assets failed", it) }
        runCatching { MorseCore.setDataDir(dir.absolutePath + "/") }
            .onFailure { Log.w(TAG, "MorseCore.setDataDir failed", it) }
    }
}
