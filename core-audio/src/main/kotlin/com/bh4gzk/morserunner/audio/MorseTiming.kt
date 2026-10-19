package com.bh4gzk.morserunner.audio

/**
 * Morse element timing, transcribed from Morse Runner Community Edition 1.85.4.
 *
 * Upstream is *not* textbook PARIS, and the difference is deliberate:
 *
 *  - `VCL/MorseKey.pas:249` computes `SamplesInUnit := Round(60/48 * Rate / WpmS)`
 *    with the comment "48U = 1 word w/ 5U inter-word space". One PARIS word is
 *    48 units here, not 50, so the element unit is `60/48/WPM` s = `1250/WPM` ms
 *    (textbook would be `1200/WPM`). The word still lasts `60/WPM` s, so the
 *    *effective speed is correct*; the elements are ~4% longer and the word gap
 *    is 5U rather than 7U.
 *  - `VCL/MorseKey.pas:243-245` confirms the gaps: a character contributes a
 *    trailing 1U plus 2U more = 3U inter-character; a word adds 2U more = 5U
 *    inter-word; a message adds 3U more = 4U inter-message.
 *  - `VCL/FarnsKeyer.pas:292-293` is the Farnsworth stretch (K1USN SST and CWops
 *    only, see `Contest.pas:200` `IsFarnsworthAllowed`):
 *
 *        DelayPerWord        := 60/WpmS - 31*60/WpmC/(43+InterWordSpacing)
 *        SamplesInAdjustUnit := Round(DelayPerWord * Rate / (12+InterWordSpacing))
 *
 *    with `InterWordSpacing = 5`, i.e. the inter-character and inter-word gaps
 *    are sent at `DelayPerWord/17` per unit while elements stay at `WpmC`.
 *    The 31/17/48 split is exactly one word: `31 units @ WpmC + 17 units @ WpmS`.
 *
 * Pure arithmetic so it can be tested on the JVM without Android (spec §7).
 *
 * @param effectiveWpm   upstream `WpmS` — the speed the operator sets, and the
 *                       speed at which words are actually delivered.
 * @param characterWpm   upstream `WpmC` — the element speed. Equal to
 *                       [effectiveWpm] means no Farnsworth. Upstream clamps it
 *                       up when it would be the slower of the two
 *                       (`FarnsKeyer.pas:296` `else WpmC := WpmS`).
 */
data class MorseTiming(
    val effectiveWpm: Int,
    val characterWpm: Int = effectiveWpm,
) {

    init {
        require(effectiveWpm in 1..100) { "effectiveWpm out of range: $effectiveWpm" }
        require(characterWpm in 1..100) { "characterWpm out of range: $characterWpm" }
    }

    /** Element speed actually used: Farnsworth never makes elements slower. */
    val elementsWpm: Int = maxOf(characterWpm, effectiveWpm)

    /** True when the gaps are stretched relative to the element speed. */
    val farnsworthEnabled: Boolean = effectiveWpm < elementsWpm

    /** Duration of one dot in ms: `60/48/WPM` s, per upstream, not `1200/WPM`. */
    val ditMs: Double = 60_000.0 / 48.0 / elementsWpm

    val dahMs: Double get() = 3 * ditMs

    /** Gap between elements of one character. */
    val intraCharacterMs: Double get() = ditMs

    /**
     * Unit length for inter-character / inter-word / inter-message gaps.
     *
     * Without Farnsworth this is the plain element unit. With Farnsworth it is
     * `DelayPerWord/17` seconds, transcribed from `FarnsKeyer.pas:292-293`.
     * Both branches agree when `effectiveWpm == elementsWpm`, which is why the
     * upstream `WpmS <= WpmC` branch needs no special case.
     */
    val gapUnitMs: Double = if (!farnsworthEnabled) {
        ditMs
    } else {
        val delayPerWordMs = 60_000.0 / effectiveWpm - 31 * 60_000.0 / elementsWpm / 48
        delayPerWordMs / 17
    }

    /** 3U: gap between characters. */
    val characterGapMs: Double get() = 3 * gapUnitMs

    /** 5U: gap between words (upstream's 48-unit word, not the textbook 7U). */
    val wordGapMs: Double get() = 5 * gapUnitMs

    /** 4U: gap between independently sent messages. */
    val interMessageGapMs: Double get() = 4 * gapUnitMs

    /**
     * Time for one PARIS word. Invariant: always `60/WPM` s of the effective
     * speed, whatever the Farnsworth split — this is what the upstream delay
     * equation is solved for.
     */
    val parisWordMs: Double get() = 31 * ditMs + 17 * gapUnitMs
}
