package com.bh4gzk.morserunner.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Element timing is asserted in units of the 20-wpm keyer, i.e. 1U = 62.5 ms
 * (upstream `60/48/WPM`, see [MorseTiming]).
 *
 * The expected gap sizes come from `VCL/MorseKey.pas:243-245`:
 *  - 1U trailing gap after every element,
 *  - 3U total between characters,
 *  - 5U total between words,
 *  - 4U total at the end of a message.
 */
class MorseEncoderTest {

    private val timing = MorseTiming(20)   // 1U = 62.5 ms

    @Test
    fun `single dit is one tone followed by a four unit message gap`() {
        val e = MorseEncoder.encode("E", timing)

        assertEquals(2, e.size)
        assertEquals(MorseElement.Tone(62.5), e[0])
        assertEquals(MorseElement.Gap(250.0), e[1])   // 4U
    }

    @Test
    fun `two characters are separated by a three unit gap`() {
        val e = MorseEncoder.encode("EE", timing)

        assertEquals(
            listOf(
                MorseElement.Tone(62.5), MorseElement.Gap(187.5),   // 3U between
                MorseElement.Tone(62.5), MorseElement.Gap(250.0),   // 4U at end
            ),
            e,
        )
    }

    @Test
    fun `words are separated by a five unit gap, not three plus five`() {
        val e = MorseEncoder.encode("E E", timing)

        assertEquals(
            listOf(
                MorseElement.Tone(62.5), MorseElement.Gap(312.5),   // 5U
                MorseElement.Tone(62.5), MorseElement.Gap(250.0),
            ),
            e,
        )
    }

    @Test
    fun `sos has the documented element and gap structure`() {
        val e = MorseEncoder.encode("SOS", timing)

        // S = ... , O = ---
        val expected = listOf(
            MorseElement.Tone(62.5), MorseElement.Gap(62.5),
            MorseElement.Tone(62.5), MorseElement.Gap(62.5),
            MorseElement.Tone(62.5), MorseElement.Gap(187.5),      // 3U
            MorseElement.Tone(187.5), MorseElement.Gap(62.5),
            MorseElement.Tone(187.5), MorseElement.Gap(62.5),
            MorseElement.Tone(187.5), MorseElement.Gap(187.5),     // 3U
            MorseElement.Tone(62.5), MorseElement.Gap(62.5),
            MorseElement.Tone(62.5), MorseElement.Gap(62.5),
            MorseElement.Tone(62.5), MorseElement.Gap(250.0),      // 4U
        )
        assertEquals(expected, e)
        // 31U at 62.5 ms
        assertEquals(1937.5, e.sumOf { it.ms }, 1e-9)
    }

    @Test
    fun `farnsworth stretches gaps but not tones`() {
        val farnsworth = MorseTiming(effectiveWpm = 10, characterWpm = 30)
        val e = MorseEncoder.encode("EE", farnsworth)

        assertEquals(4, e.size)
        // Elements stay at the 30 wpm character speed.
        assertEquals(41.666666666666664, (e[0] as MorseElement.Tone).ms, 1e-9)
        // Gaps use the stretched unit: (60/10 - 31*60/30/48)/17 s = 276.9607... ms.
        assertEquals(830.8823529411764, (e[1] as MorseElement.Gap).ms, 1e-6)   // 3U
        assertEquals(41.666666666666664, (e[2] as MorseElement.Tone).ms, 1e-9)
        assertEquals(1107.8431372549019, (e[3] as MorseElement.Gap).ms, 1e-6)  // 4U
    }

    @Test
    fun `unknown characters are skipped and do not emit elements`() {
        // '@' has no entry in the upstream table; '?' does, so it cannot be used here.
        val e = MorseEncoder.encode("E@E", timing)

        assertEquals(4, e.size)
        assertEquals(MorseElement.Tone(62.5), e[0])
        assertEquals(MorseElement.Gap(187.5), e[1])
    }

    @Test
    fun `lowercase and repeated spaces are normalised`() {
        assertEquals(
            MorseEncoder.encode("E E", timing),
            MorseEncoder.encode("  e   e  ", timing),
        )
    }

    @Test
    fun `empty text encodes to nothing`() {
        assertTrue(MorseEncoder.encode("", timing).isEmpty())
        assertTrue(MorseEncoder.encode("   ", timing).isEmpty())
    }
}
