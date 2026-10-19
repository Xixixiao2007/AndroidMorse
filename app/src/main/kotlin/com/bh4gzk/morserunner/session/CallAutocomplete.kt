package com.bh4gzk.morserunner.session

/**
 * Prefix completion for the callsign box (spec §8: "call field …, autocomplete from call
 * history").
 *
 * A hint, never a decoder: it can only offer calls the contest's call-history file already
 * lists, it stays silent until the operator has typed something, and it refuses to complete a
 * `?` — that character is the operator's wildcard for a half-copied call
 * (`DxOper.pas:428-431`), so treating it as a prefix would hide the calls they might mean.
 *
 * Pure and cheap enough to run on every keystroke over a ~9 000-call file (one pass, then a
 * sort of the matches only).
 */
object CallAutocomplete {

    /** How many completions the entry box may show at once. */
    const val DEFAULT_LIMIT: Int = 8

    /**
     * Calls from [pool] that start with [typed], uppercased, sorted and deduplicated.
     *
     * An exactly typed call is not offered back: the operator has finished that word.
     *
     * @param typed what is in the entry box; a blank string or one containing `?` yields nothing.
     * @param limit the maximum number of suggestions.
     */
    fun suggest(pool: Collection<String>, typed: String, limit: Int = DEFAULT_LIMIT): List<String> {
        val prefix = typed.trim().uppercase()
        if (prefix.isEmpty() || '?' in prefix) return emptyList()

        val matches = LinkedHashSet<String>()
        for (raw in pool) {
            val call = raw.trim().uppercase()
            if (call.length > prefix.length && call.startsWith(prefix)) matches += call
        }
        return matches.sorted().take(limit)
    }
}
