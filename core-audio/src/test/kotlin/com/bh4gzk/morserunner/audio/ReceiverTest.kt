package com.bh4gzk.morserunner.audio

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The receiver's two knobs (T9): **RIT** and **bandwidth**.
 *
 * Upstream does both inside `TContest.GetAudioBlock`:
 *
 *  - RIT subtracts a ramping reference phase from every station's BFO
 *    (`Contest.pas:721-731`), so every received station's tone moves by the RIT value while
 *    the operator's own sidetone is mixed in afterwards, unaffected;
 *  - bandwidth sets a two-stage 3-pass moving average (`Contest.pas:125-144`,
 *    `Filt.Points := Round(0.7 * Rate / Bandwidth)`) whose gain is `10*log10(500/Bandwidth)`.
 *
 * This port has no receiver filter (ADR-0008 §1 already calibrated the noise bed for that),
 * so bandwidth is applied to the **noise floor** instead: white noise through a filter of
 * bandwidth `B` has RMS proportional to `sqrt(B)` (see `Receiver.bandPeakFor`'s KDoc).
 * "Sounds narrower" is the human's ear; this file asserts the arithmetic exists.
 */
class ReceiverTest {

    // --- RIT ---------------------------------------------------------------------------

    @Test
    fun `RIT moves a received station's tone by its own value`() {
        assertEquals(600.0, Receiver.receivedPitchHz(pitchHz = 600, ritHz = 0))
        assertEquals(650.0, Receiver.receivedPitchHz(pitchHz = 600, ritHz = 50))
        assertEquals(550.0, Receiver.receivedPitchHz(pitchHz = 600, ritHz = -50))
        // A station that sits off the operator's pitch moves with its own offset intact.
        assertEquals(1_190.0, Receiver.receivedPitchHz(pitchHz = 690, ritHz = 500))
    }

    @Test
    fun `RIT is clamped to upstream's range and step`() {
        assertEquals(500, Receiver.rit(900))
        assertEquals(-500, Receiver.rit(-900))
        assertEquals(0, Receiver.rit(0))
        // `Ini.RitStepIncr = 50`: the on-screen control moves in these steps.
        assertEquals(50, Receiver.rit(37))
        assertEquals(-50, Receiver.rit(-37))
        assertEquals(100, Receiver.rit(100))
    }

    // --- bandwidth ---------------------------------------------------------------------

    @Test
    fun `pitch is clamped to upstream's list and its 50 Hz grid`() {
        assertEquals(Receiver.MIN_PITCH_HZ, Receiver.pitch(1))
        assertEquals(Receiver.MAX_PITCH_HZ, Receiver.pitch(5_000))
        assertEquals(600, Receiver.pitch(600))
        assertEquals(550, Receiver.pitch(537))
    }

    @Test
    fun `bandwidth is clamped to upstream's list and its 50 Hz grid`() {
        assertEquals(Receiver.MIN_BANDWIDTH_HZ, Receiver.bandwidth(10))
        assertEquals(Receiver.MAX_BANDWIDTH_HZ, Receiver.bandwidth(5_000))
        assertEquals(500, Receiver.bandwidth(500))
        assertEquals(550, Receiver.bandwidth(530))
        assertEquals(100, Receiver.bandwidth(120))
    }

    @Test
    fun `a narrower receiver hears a quieter noise floor, by the square root of the bandwidth`() {
        val reference = Receiver.bandPeakFor(Receiver.DEFAULT_BANDWIDTH_HZ)
        assertEquals(
            NoiseBed.DEFAULT_BAND_PEAK,
            reference,
            1e-12,
            "the default setting must keep T8's calibrated bed level",
        )

        // 100 Hz against 500 Hz: a quarter of the power, so half the amplitude.
        val narrow = Receiver.bandPeakFor(100)
        assertEquals(sqrt(0.2), narrow / reference, 1e-9)

        val wide = Receiver.bandPeakFor(600)
        assertTrue(wide > reference, "a wider receiver must hear more noise: $wide")
        assertEquals(sqrt(1.2), wide / reference, 1e-9)
    }

    @Test
    fun `the noise bed really is quieter at a narrow bandwidth`() {
        // The setting has to reach the audio, not just a number in an object: render the same
        // seed through two beds and measure what the ear would get.
        fun rms(bandwidthHz: Int): Double {
            val bed = NoiseBed(
                sampleRate = 48_000,
                bandPeak = Receiver.bandPeakFor(bandwidthHz),
                seed = 12_345L,
            )
            val block = FloatArray(48_000)
            bed.render(block, block.size)
            return sqrt(block.sumOf { it.toDouble() * it } / block.size)
        }

        val narrow = rms(100)
        val reference = rms(500)
        val wide = rms(600)
        assertTrue(narrow < reference, "100 Hz bed RMS $narrow is not below 500 Hz's $reference")
        assertTrue(wide > reference, "600 Hz bed RMS $wide is not above 500 Hz's $reference")
        assertEquals(sqrt(0.2), narrow / reference, 0.03, "narrow bed RMS ratio $narrow / $reference")
        assertEquals(sqrt(1.2), wide / reference, 0.03, "wide bed RMS ratio $wide / $reference")
    }
}
