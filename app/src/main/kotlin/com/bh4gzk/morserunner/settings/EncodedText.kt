package com.bh4gzk.morserunner.settings

/**
 * The stored blob's text format, shared by the settings and the session snapshot.
 *
 * A blob is a sequence of `key=value` lines. Keys are plain ASCII identifiers; values are
 * escaped so that a line can never be split by its own content:
 *
 * | character | written as |
 * |---|---|
 * | `\` | `\\` |
 * | newline | `\n` |
 * | carriage return | `\r` |
 * | tab | `\t` |
 * | `,` | `\,` |
 *
 * The escaping exists because a contact's exchange is free text (`QsoRecord.exch1`) and a
 * multiplier key is a composite string (`ZN-14;Germany`); ADR-0009 records the decision to
 * store one readable blob per concern instead of a binary or JSON payload, so a support question
 * ("what does the app think my settings are?") is answerable with `adb shell run-as cat`.
 */
internal object EncodedText {

    /** The escaped text of [value]; the inverse of [unescape]. */
    fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                ',' -> out.append("\\,")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** The text [value] was before [escape]; unknown escapes keep their character. */
    fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                else -> out.append(next)
            }
            i += 2
        }
        return out.toString()
    }

    /** Splits an escaped [text] on the unescaped [delimiter], keeping escape pairs intact. */
    fun splitEscaped(text: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                current.append(c).append(text[i + 1])
                i += 2
                continue
            }
            if (c == delimiter) {
                out += current.toString()
                current.setLength(0)
                i++
                continue
            }
            current.append(c)
            i++
        }
        out += current.toString()
        return out
    }

    /**
     * A blob's `key=value` lines as a map; a line without `=` is ignored.
     *
     * Values are returned exactly as they appear in the blob. `unescapeValues = true` is for a
     * blob whose values are plain escaped text (the settings); a blob whose values are
     * *themselves* delimited lists or field tuples (the session snapshot) must keep the escapes
     * intact until it has split them, so it passes `false` and unescapes each field itself.
     */
    fun parseLines(blob: String, unescapeValues: Boolean = false): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (line in blob.split('\n')) {
            if (line.isEmpty()) continue
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            val raw = line.substring(separator + 1)
            values[line.substring(0, separator)] = if (unescapeValues) unescape(raw) else raw
        }
        return values
    }

    /** Renders one `key=value` line, escaping the value. */
    fun line(key: String, value: String): String = "$key=${escape(value)}"

    /** A list as one escaped field: each item escaped, then joined by `,`. */
    fun joinList(items: Collection<String>): String = items.joinToString(",") { escape(it) }

    /** The inverse of [joinList]; a blank field is the empty list. */
    fun splitList(field: String): List<String> =
        if (field.isEmpty()) emptyList() else splitEscaped(field, ',').map { unescape(it) }
}
