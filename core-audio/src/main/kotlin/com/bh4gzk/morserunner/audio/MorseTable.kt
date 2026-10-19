package com.bh4gzk.morserunner.audio

/**
 * The character set, transcribed from `VCL/MorseTbl.pas`.
 *
 * Only the single-character entries are included. The multi-character prosign
 * entries in the upstream table (`sk`, `ar`, `kn`, `cq`, `bk`, `dx`) are skipped
 * there too: `TKeyer.LoadMorseTable` ignores any row whose second character is
 * not `[` (`MorseKey.pas:100-113`), so they never reach the keyer. Prosign
 * handling belongs to the message layer, not the audio layer.
 */
object MorseTable {

    private val patterns: Map<Char, String> = mapOf(
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
