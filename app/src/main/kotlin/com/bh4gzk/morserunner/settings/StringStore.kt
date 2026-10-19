package com.bh4gzk.morserunner.settings

/**
 * The one thing persistence needs from the platform: a small string key/value store.
 *
 * Production is `SharedPreferences` ([PrefsStringStore]); tests are a `HashMap`
 * (`MapStringStore`). Everything above this interface — the codecs, the settings, the session
 * snapshot — is plain Kotlin, which is what makes all of it testable without a device.
 */
interface StringStore {

    /** The stored string, or `null` when nothing was ever written under [key]. */
    fun read(key: String): String?

    /** Writes [value]; `null` removes the key. */
    fun write(key: String, value: String?)
}
