package com.bh4gzk.morserunner.data

/**
 * Callsign and prefix extraction, transcribed from upstream
 * `Util/CallsignUtils.pas` — behaviour pinned against
 * `Test/CallsignUtilsTest.pas`.
 *
 * Two different questions are answered here, and they are easy to confuse:
 *
 *  - [extractCall] answers “which callsign is this, ignoring the portable
 *    qualifiers?” — `F6/W7SST/P` is `W7SST`.
 *  - [extractPrefix] answers “which prefix identifies the entity?” — and it has
 *    **two modes**:
 *    * WPX contest (`deleteTrailingLetters = true`): the country prefix plus the
 *      district digit, trailing letters removed — `DL2XYZ` is `DL2`, `A9AB` is `A9`,
 *      `DL/AA1ZZ` is `DL0`.
 *    * DXCC lookup (`deleteTrailingLetters = false`): the longest leading prefix,
 *      letters kept — `K1ABC` is `K1ABC`, `DL/AA1ZZ` is `DL`, `F/DJ4MZ` is `F`.
 *
 * The DXCC mode exists because dropping trailing letters loses real entities:
 * upstream's own note gives `RC2FX`, whose prefix `RC2F` is Kaliningrad, while
 * `RC2` would resolve to European Russia.
 */
object CallPrefix {

    private val DIGITS = '0'..'9'

    /** `CallsignUtils.pas:138`. */
    private val CALLSIGN = Regex("(([0-9][A-Z])|([A-Z]{1,2}))[0-9][A-Z0-9]*[A-Z]")

    /**
     * The callsign hidden inside a portable construction, or `""` when there is
     * none (`CallsignUtils.pas:23-37`).
     *
     * A match only counts at the start of the string or right after a `/`, so
     * `@#CALL` yields nothing rather than `CALL`.
     */
    fun extractCall(call: String): String {
        val match = CALLSIGN.find(call) ?: return ""
        val start = match.range.first
        if (start > 0 && call[start - 1] != '/') return ""
        return match.value
    }

    /**
     * The entity-identifying prefix (`CallsignUtils.pas:58-133`).
     *
     * @param deleteTrailingLetters `true` for the WPX rules (prefix + district
     *   digit), `false` for DXCC lookup (longest prefix).
     */
    fun extractPrefix(call: String, deleteTrailingLetters: Boolean = true): String {
        // Kill portable modifiers. Appending '|' first is upstream's trick for
        // stripping only a *trailing* modifier in one pass.
        var work = (call + "|")
            .replace("/QRP|", "")
            .replace("/MM|", "")
            .replace("/M|", "")
            .replace("/P|", "")
            .replace("|", "")
            .replace("//", "/")
        if (work.length < 2) return ""

        var digit = ""

        // Keep the shorter piece, except when one piece is a bare district digit.
        val slash = work.indexOf('/')
        var result: String
        if (slash < 0) {
            result = work
        } else if (slash == 0) {
            result = work.substring(1)
        } else if (slash == work.length - 1) {
            result = work.substring(0, slash)
        } else {
            val before = work.substring(0, slash)
            val after = work.substring(slash + 1)
            result = when {
                before.length == 1 && before[0] in DIGITS -> {
                    digit = before
                    after
                }
                after.length == 1 && after[0] in DIGITS -> {
                    digit = after
                    before
                }
                before.length <= after.length -> before
                else -> after
            }
        }
        if (result.contains('/')) return ""   // more than one qualifier left: give up

        // DXCC mode stops here: the longest prefix is the answer.
        if (!deleteTrailingLetters) return result

        // WPX mode: drop trailing letters, but keep at least two characters.
        for (position in result.length downTo 3) {
            if (result[position - 1] in DIGITS) break
            result = result.substring(0, position - 1) + result.substring(position)
        }

        // A prefix always ends in a digit.
        if (result.isEmpty()) return ""
        if (result[result.length - 1] !in DIGITS) result += "0"

        // A bare district digit in the call replaces the prefix's own digit.
        if (digit.isNotEmpty()) {
            result = result.substring(0, result.length - 1) + digit[0]
        }
        return result.take(5)
    }
}
