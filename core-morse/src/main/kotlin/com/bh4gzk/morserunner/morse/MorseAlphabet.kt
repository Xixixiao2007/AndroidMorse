package com.bh4gzk.morserunner.morse

/**
 * The CW character set, transcribed from `VCL/MorseTbl.pas` (MRCE 1.85.4).
 *
 * Only the single-character entries are included. The multi-character prosign entries in the
 * upstream table (`sk`, `ar`, `kn`, `cq`, `bk`, `dx`) are skipped there too:
 * `TKeyer.LoadMorseTable` ignores any row whose second character is not `[`
 * (`VCL/MorseKey.pas:100-113`), so they never reach the keyer. Prosign handling belongs to the
 * message layer, not to the alphabet.
 *
 * This module exists as a leaf because the alphabet has **two independent consumers**
 * (ADR-0016 §2): `:core-audio` keys it (`MorseEncoder`) and `:core-expr` scores with it
 * (`callWeight`, HST's points rule). Before T16 the table lived privately in `:core-audio`;
 * copying it into `:core-expr` would have created a second alphabet that can drift, and either
 * module depending on the other points the dependency graph the wrong way.
 */
object MorseAlphabet {

    /**
     * Character → dots and dashes, exactly as upstream stores it (without the trailing space
     * `LoadMorseTable` appends; the spaces are the keyer's business, not the alphabet's).
     */
    val patterns: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
        'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
        'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '/' to "-..-.", '.' to ".-.-.-", ',' to "--..--", '?' to "..--..",
        '=' to "-...-", '\\' to "...-.",
    )

    /** Dot/dash pattern for [ch], or null when the character has no Morse code. */
    fun patternOf(ch: Char): String? = patterns[ch]

    /** Characters that can be sent; useful for input validation. */
    fun isSendable(ch: Char): Boolean = patterns.containsKey(ch)
}
