package com.bh4gzk.morserunner.morse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **T16: the Morse weight of a callsign** — HST's score unit.
 *
 * The rule is `Log.pas:586-597` `CallToScore` over `VCL/MorseKey.pas:158-170`
 * `TKeyer.Encode` (ADR-0016 §3): each character contributes its pattern plus one trailing
 * space, the last character is replaced by `~` (which charges nothing), and the running total
 * starts at `-1`. So for a call of *n* known characters:
 *
 *     weight = 2·dots + 4·dashes + 2·(n − 1) − 1
 *
 * Every dot and dash below was counted by hand from `VCL/MorseTbl.pas`; the closed form is
 * only what the tests check the transcription against, not where the rule lives.
 */
class MorseWeightTest {

    @Test
    fun `the worked examples of ADR-0016 §3`() {
        // K1ABC: K=-.- (1 dot, 2 dashes), 1=.---- (1,4), A=.- (1,1), B=-... (3,1), C=-.-. (2,2)
        //        → dots 8, dashes 10, n 5 → 16 + 40 + 8 − 1
        assertEquals(63, MorseWeight.of("K1ABC"))
        // W1AW: W (.-- 1,2), 1 (1,4), A (1,1), W (1,2) → dots 4, dashes 9, n 4
        assertEquals(49, MorseWeight.of("W1AW"))
        // JA1ABC: J (.--- 1,3) A (1,1) 1 (1,4) A (1,1) B (3,1) C (2,2) → dots 9, dashes 12, n 6
        assertEquals(75, MorseWeight.of("JA1ABC"))
        // DL/AA1ZZ: '/' = -..-. (3 dots, 2 dashes) → dots 15, dashes 14, n 8
        assertEquals(99, MorseWeight.of("DL/AA1ZZ"))
    }

    @Test
    fun `one character is one pattern and one trailing gap`() {
        // E = '.'  → 2 + 0 + 2·0 − 1
        assertEquals(1, MorseWeight.of("E"))
        // T = '-'  → 0 + 4 + 0 − 1
        assertEquals(3, MorseWeight.of("T"))
        // 5 = '.....' → 10 + 0 + 0 − 1
        assertEquals(9, MorseWeight.of("5"))
        // 0 = '-----' → 0 + 20 + 0 − 1
        assertEquals(19, MorseWeight.of("0"))
    }

    @Test
    fun `the closed form agrees with the transcribed element loop`() {
        // Every single character, then a few multi-character calls: a misplaced space or a
        // dropped −1 would show up in every row at once.
        for ((character, pattern) in MorseAlphabet.patterns) {
            val dots = pattern.count { it == '.' }
            val dashes = pattern.count { it == '-' }
            assertEquals(
                2 * dots + 4 * dashes - 1,
                MorseWeight.of(character.toString()),
                "one-character weight for '$character'",
            )
        }
        val calls = listOf("K1ABC", "W1AW", "JA1ABC", "DL/AA1ZZ", "5NN", "AA1AA", "ZZ9ZZZ", "K")
        for (call in calls) {
            val dots = call.sumOf { character -> MorseAlphabet.patternOf(character)!!.count { it == '.' } }
            val dashes = call.sumOf { character -> MorseAlphabet.patternOf(character)!!.count { it == '-' } }
            assertEquals(
                2 * dots + 4 * dashes + 2 * (call.length - 1) - 1,
                MorseWeight.of(call),
                "closed form for '$call'",
            )
        }
    }

    @Test
    fun `the dash is worth twice the dot, and the gap a dot`() {
        // Log.pas:586-597: '.' → 2, '-' → 4, ' ' → 2. N (one dot, one dash) is 2 + 4 − 1 = 5,
        // which is T plus two (the dot) and E plus four (the dash).
        assertEquals(5, MorseWeight.of("N"))
        assertEquals(MorseWeight.of("T") + 2, MorseWeight.of("N"))
        // KA = -.- .- → dots 2, dashes 3, n 2 → 4 + 12 + 2 − 1
        assertEquals(17, MorseWeight.of("KA"))
        // Three more than the sum of the two single-character weights: each single one carries
        // upstream's −1 baseline (−2 together), and the joined string charges one gap (+2)
        // against a single baseline (−1).
        assertEquals(MorseWeight.of("K") + MorseWeight.of("A") + 3, MorseWeight.of("KA"))
    }

    @Test
    fun `an empty string keeps upstream's minus-one baseline`() {
        // CallToScore('') = -1: Result := -1, then a zero-length loop. Not a port choice.
        assertEquals(-1, MorseWeight.of(""))
    }

    @Test
    fun `a call with no sendable character is also minus one`() {
        // TKeyer.Encode appends '' for a character with no Morse entry, so nothing is added.
        assertEquals(-1, MorseWeight.of("!!"), "no entry for '!' in the keyer's table")
        assertEquals(-1, MorseWeight.of("-"), "a dash is not a call character")
        // Case is not folded: the upstream table is keyed by upper case only
        // (`MorseKey.pas:100-113`), and `TKeyer.Encode` does not upper-case its input. A
        // lower-case *letter* therefore contributes nothing, while a digit still does.
        assertEquals(-1, MorseWeight.of("abc"))
        assertEquals(17, MorseWeight.of("k1abc"), "only the sendable '1' survives case folding")
        assertEquals(MorseWeight.of("1"), MorseWeight.of("k1abc"))
    }

    @Test
    fun `the alphabet is the keyer's, all of it and only it`() {
        // 26 letters + 10 digits + / . , ? = \ — the single-character rows of VCL/MorseTbl.pas;
        // the prosign rows are skipped upstream too (`MorseKey.pas:100-113`).
        assertEquals(42, MorseAlphabet.patterns.size)
        for (letter in 'A'..'Z') assertTrue(MorseAlphabet.isSendable(letter), "missing $letter")
        for (digit in '0'..'9') assertTrue(MorseAlphabet.isSendable(digit), "missing $digit")
        for (character in listOf('/', '.', ',', '?', '=', '\\')) {
            assertTrue(MorseAlphabet.isSendable(character), "missing '$character'")
        }
        assertTrue(!MorseAlphabet.isSendable('-'), "a dash is not a call character")
        // Only keying symbols: a stray space or bracket in the table would silently score.
        for ((character, pattern) in MorseAlphabet.patterns) {
            assertTrue(pattern.isNotEmpty(), "'$character' has an empty pattern")
            assertTrue(pattern.all { it == '.' || it == '-' }, "'$character' has a non-keying symbol")
        }
    }
}
