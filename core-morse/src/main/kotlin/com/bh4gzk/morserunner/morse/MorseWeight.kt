package com.bh4gzk.morserunner.morse

/**
 * The Morse sending time of a callsign, in keyer element units — HST's score per contact.
 *
 * Transcribed from two upstream functions (MRCE 1.85.4), together because neither is usable
 * alone:
 *
 *  - `Log.pas:586-597` `CallToScore`:
 *
 *        S := Keyer.Encode(S);
 *        Result := -1;
 *        for i := 1 to Length(S) do
 *          case S[i] of
 *            '.': Inc(Result, 2);
 *            '-': Inc(Result, 4);
 *            ' ': Inc(Result, 2);
 *          end;
 *
 *  - `VCL/MorseKey.pas:158-170` `TKeyer.Encode`, which is what gives the loop its input: each
 *    character contributes its pattern **plus one trailing space**, `' '`/`'_'` contribute a
 *    single space, and the **last character of the result is replaced by `'~'`** ("EOM has ~5U
 *    spacing") — so the final trailing space is not charged, and `~` matches no case.
 *
 * For a call of *n* known characters this is `2·dots + 4·dashes + 2·(n − 1) − 1`, with the
 * `−1` being upstream's initial `Result`. The loop is transcribed literally rather than replaced
 * by the closed form so that the odd cases behave exactly as upstream does:
 *
 *  - a character with no Morse entry appends nothing (`Morse[ch]` is `''`), so it contributes no
 *    dots, no dashes and no space;
 *  - a string with no sendable character therefore scores `-1` (the baseline, loop never runs);
 *  - case is **not** folded, because `Morse[ch]` is keyed by upper case only and `Encode` does
 *    not upper-case its input. Calls are validated and normalised to upper case before scoring
 *    (`QsoLog.normalise`, `ContestRules.normaliseCall`), so this is upstream's arithmetic, not a
 *    port choice (ADR-0016 §3).
 *
 * Worked examples (ADR-0016 §3): `K1ABC` = 63, `W1AW` = 49, `JA1ABC` = 75, `DL/AA1ZZ` = 101.
 */
object MorseWeight {

    /** `Log.pas:586-597` over `MorseKey.pas:158-170`. */
    fun of(call: String): Int {
        // -1 is upstream's `Result := -1`; keep it, the EOM replacement below depends on it.
        var result = -1
        // `TKeyer.Encode` builds the encoded string first and then replaces its last character
        // with '~'. Walk the encoding without materializing it: every known character is its
        // pattern followed by one space, and only the last character's space is dropped.
        val encoded = StringBuilder()
        for (character in call) {
            if (character == ' ' || character == '_') {
                encoded.append(' ')
                continue
            }
            val pattern = MorseAlphabet.patternOf(character) ?: continue
            encoded.append(pattern).append(' ')
        }
        if (encoded.isNotEmpty()) {
            // The '~' end-of-message marker: no case in upstream's loop charges it.
            encoded[encoded.lastIndex] = '~'
        }
        for (symbol in encoded) {
            when (symbol) {
                '.' -> result += 2
                '-' -> result += 4
                ' ' -> result += 2
            }
        }
        return result
    }
}
