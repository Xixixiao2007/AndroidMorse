package com.bh4gzk.morserunner.settings

import android.content.SharedPreferences

/**
 * [StringStore] over `SharedPreferences` — the app's one piece of Android persistence
 * (spec §3 assigns persistence to `app`; ADR-0009 §"why not DataStore" argues the choice).
 *
 * `apply()` rather than `commit()`: the settings screen and the session snapshot are written
 * from the UI thread, and a lost write at process death is exactly what the snapshot's next
 * tick repairs. Nothing here is read before `MainActivity.onCreate` has finished, so the
 * in-memory map is already warm.
 */
class PrefsStringStore(private val prefs: SharedPreferences) : StringStore {

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
