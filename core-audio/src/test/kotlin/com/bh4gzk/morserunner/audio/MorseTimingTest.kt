package com.bh4gzk.morserunner.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Expected values are hand-computed from the upstream equations quoted in
 * [MorseTiming], not copied from its implementation, so the test can actually
 * disagree with the code.
 */
class MorseTimingTest {

    @Test
    fun `20 wpm follows upstream 48-unit word, not textbook PARIS`() {
        val t = MorseTiming(20)

        // 60/48/20 s = 62.5 ms. Textbook PARIS would be 60 ms; Morse Runner is
        // deliberately 1/24 longer because its word is 48 units (MorseKey.pas:249).
        assertEquals(62.5, t.ditMs, 1e-9)
        assertEquals(187.5, t.dahMs, 1e-9)
        assertEquals(62.5, t.intraCharacterMs, 1e-9)
        assertEquals(187.5, t.characterGapMs, 1e-9)
        // 5U inter-word, not the textbook 7U (MorseKey.pas:244).
        assertEquals(312.5, t.wordGapMs, 1e-9)
        assertEquals(250.0, t.interMessageGapMs, 1e-9)
        // The speed is still exactly 20 wpm: one word in 3 s.
        assertEquals(3000.0, t.parisWordMs, 1e-9)

        assertFalse(t.farnsworthEnabled)
    }

    @Test
    fun `farnsworth stretches gaps and keeps element speed`() {
        // effective 10 wpm, elements at 30 wpm (FarnsKeyer.pas:292-293):
        //   delayPerWord = 60/10 - 31*60/30/48 = 4.708333... s
        //   gap unit     = delayPerWord / 17 = 276.960784... ms
        val t = MorseTiming(effectiveWpm = 10, characterWpm = 30)

        assertTrue(t.farnsworthEnabled)
        assertEquals(30, t.elementsWpm)
        assertEquals(41.666666666666664, t.ditMs, 1e-6)   // elements stay at 30 wpm
        assertEquals(276.96078431372547, t.gapUnitMs, 1e-6)
        assertEquals(830.8823529411764, t.characterGapMs, 1e-6)
        assertEquals(1384.8039215686274, t.wordGapMs, 1e-6)
        assertEquals(6000.0, t.parisWordMs, 1e-6)         // 10 wpm word time
    }

    @Test
    fun `every speed pair keeps the paris word time of the effective speed`() {
        // This is the invariant the upstream delay equation is solved for:
        // 31 units at the element speed + 17 stretched units == one word at WpmS.
        for (effective in intArrayOf(5, 10, 15, 20, 25, 30, 40)) {
            for (character in intArrayOf(10, 18, 25, 30, 35)) {
                val t = MorseTiming(effective, character)
                val expected = 60_000.0 / effective
                assertEquals(expected, t.parisWordMs, 1e-9, "eff=$effective char=$character")
            }
        }
    }

    @Test
    fun `character speed below the effective speed is clamped up`() {
        // FarnsKeyer.pas:296 -- `else WpmC := WpmS`; elements are never slower.
        val clamped = MorseTiming(effectiveWpm = 25, characterWpm = 20)
        val plain = MorseTiming(25)

        assertFalse(clamped.farnsworthEnabled)
        assertEquals(25, clamped.elementsWpm)
        assertEquals(plain.ditMs, clamped.ditMs, 1e-9)
        assertEquals(plain.characterGapMs, clamped.characterGapMs, 1e-9)
    }

    @Test
    fun `out of range wpm is rejected`() {
        assertFailsWith<IllegalArgumentException> { MorseTiming(0) }
        assertFailsWith<IllegalArgumentException> { MorseTiming(101) }
        assertFailsWith<IllegalArgumentException> { MorseTiming(20, 0) }
        assertFailsWith<IllegalArgumentException> { MorseTiming(20, 101) }
    }
}
