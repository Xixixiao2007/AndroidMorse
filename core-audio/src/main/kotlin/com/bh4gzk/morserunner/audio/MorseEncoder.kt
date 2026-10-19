package com.bh4gzk.morserunner.audio

import com.bh4gzk.morserunner.morse.MorseAlphabet

/** One piece of a keyed message: a tone, or silence. Durations are milliseconds. */
sealed interface MorseElement {
    val ms: Double

    data class Tone(override val ms: Double) : MorseElement

    data class Gap(override val ms: Double) : MorseElement
}

/**
 * Turns text into the tone/gap stream a keyer can render.
 *
 * Gap totals follow `VCL/MorseKey.pas:243-245` rather than the textbook figures:
 * 1U between elements, 3U between characters, 5U between words, 4U at the end of
 * a message. A word gap *replaces* the character gap — upstream reaches 5U by
 * adding 2U to the 3U that was already there, not by adding a second gap.
 *
 * Characters without Morse code are skipped silently; they do not create a gap.
 * Text is upper-cased and whitespace runs collapse, so entry from a phone
 * keyboard behaves the same as from a physical one.
 */
object MorseEncoder {

    fun encode(text: String, timing: MorseTiming): List<MorseElement> {
        val words = text.uppercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        // First collect what will actually be sent, with the word it belongs to,
        // so the gap after each character can be chosen by looking ahead.
        val sent = ArrayList<SentChar>(text.length)
        for ((wordIndex, word) in words.withIndex()) {
            for (ch in word) {
                val pattern = MorseAlphabet.patternOf(ch) ?: continue
                sent += SentChar(pattern, wordIndex)
            }
        }
        if (sent.isEmpty()) return emptyList()

        val out = ArrayList<MorseElement>(sent.size * 4)
        for ((index, item) in sent.withIndex()) {
            for ((i, symbol) in item.pattern.withIndex()) {
                out += MorseElement.Tone(
                    if (symbol == '.') timing.ditMs else timing.dahMs,
                )
                // The trailing 1U is folded into the character/word gap below.
                if (i < item.pattern.lastIndex) out += MorseElement.Gap(timing.intraCharacterMs)
            }
            out += MorseElement.Gap(
                when {
                    index == sent.lastIndex -> timing.interMessageGapMs
                    sent[index + 1].word != item.word -> timing.wordGapMs
                    else -> timing.characterGapMs
                },
            )
        }
        return out
    }

    private data class SentChar(val pattern: String, val word: Int)

    private val WHITESPACE = Regex("\\s+")
}
