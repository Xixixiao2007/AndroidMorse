package com.bh4gzk.morserunner.data

/**
 * The callsign grammar, transcribed from the upstream SS lexer rule
 * (`Util/SSExchParser.pas:109-110`, `SSLexerRules[ttCallsign]`) and used by the
 * call-history loaders (`ArrlSS.pas:116` calls `Lexer.IsValidCall`).
 *
 * Upstream validates the *whole* token (`NextToken(call) and token = ttCallsign`,
 * `SSExchParser.pas:232-239`), so this is an anchored whole-string match rather
 * than a search. The rule itself is upstream's, minus the leading `(?<=\b)`
 * lookbehind — that assertion exists to find a callsign inside a longer exchange
 * string, and this API only ever classifies a complete token.
 *
 * A call is: an optional `PREFIX/` of two or more letters/digits, a prefix block
 * (1–2 letters, or digit+letter, or letter+digit, or digit+letter+letter), the
 * call-area digit, a suffix ending in a letter, and up to two `/SUFFIX` groups.
 * It is applied to an **uppercased, trimmed** value — both upstream loaders
 * uppercase before they validate (`CqWW.pas:87`, `ArrlSS.pas:111`).
 */
object Callsign {

    /**
     * One callsign token. `[0-9]` rather than `\d`: upstream's lexer is ASCII and
     * a Unicode digit in a callsign must not be accepted.
     */
    private val GRAMMAR = Regex(
        "^([A-Z0-9]{2,}/)?" +
            "([A-Z]{1,2}|[0-9][A-Z]|[A-Z][0-9]|[0-9][A-Z]{2})" +
            "([0-9])" +
            "([A-Z0-9]*[A-Z])" +
            "(/[A-Z0-9]+)?" +
            "(/[A-Z0-9]+)?$",
    )

    /** True when [call] (after [normalise]) is exactly one callsign. */
    fun isValid(call: String): Boolean = GRAMMAR.matches(normalise(call))

    /**
     * The stored form: trimmed and uppercased.
     *
     * The call-history files carry mixed case and stray spaces (`IaruHf.pas:135`
     * trims explicitly because "some records" have them), and every lookup key the
     * roster uses is an uppercased MASTER.DTA call.
     */
    fun normalise(call: String): String = call.trim().uppercase()
}
