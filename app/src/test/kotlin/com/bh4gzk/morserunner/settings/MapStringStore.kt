package com.bh4gzk.morserunner.settings

/**
 * A [StringStore] in a map — the whole reason settings and the session snapshot are tested
 * without Android: the production store is `SharedPreferences`, this is the same three
 * operations.
 *
 * [writes] counts every `write` call so a test can prove the session is not rewritten when
 * nothing changed.
 */
class MapStringStore(initial: Map<String, String> = emptyMap()) : StringStore {

    private val values = LinkedHashMap(initial)

    var writes: Int = 0
        private set

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String?) {
        writes++
        if (value == null) values.remove(key) else values[key] = value
    }

    /** A copy of the backing map, for assertions. */
    fun snapshot(): Map<String, String> = LinkedHashMap(values)
}
