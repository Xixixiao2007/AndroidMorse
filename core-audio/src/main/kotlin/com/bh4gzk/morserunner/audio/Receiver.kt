package com.bh4gzk.morserunner.audio

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The receiver's two knobs (spec §6: speed, pitch; spec §11 T9: RIT and bandwidth).
 *
 * Upstream does both inside `TContest.GetAudioBlock`:
 *
 * **RIT** subtracts a ramping reference phase from every station's BFO
 * (`Contest.pas:721-731`):
 *
 * ```
 * Bfo := Stations[Stn].Bfo - RitPhase - i * TWO_PI * Ini.Rit / DEFAULTRATE;
 * ...
 * RitPhase := RitPhase + Ini.BufSize * TWO_PI * Ini.Rit / DEFAULTRATE;
 * ```
 *
 * Demodulating with a reference that ramps by `-Rit` moves every received tone **up** by
 * `Rit` Hz (`f - (Bfo - Rit·n) = Rit·n` for a station at `Bfo`), while the operator's own
 * sidetone is mixed in later in the same procedure and never sees a RIT term. The range and
 * step are upstream's: ±500 Hz in steps of `Ini.RitStepIncr = 50` (`Ini.pas:242-243`).
 * [receivedPitchHz] is that shift, and it is the whole of the mapping this port needs: it
 * has no baseband, so RIT is a pitch offset on the received stations.
 *
 * **Bandwidth** sets a two-stage, three-pass moving average (`Contest.pas:125-144`):
 * `Points = Round(0.7 · Rate / Bandwidth)`, `GainDb = 10·log10(500 / Bandwidth)`. This port
 * has no receiver filter at all — ADR-0008 §1 calibrated the QRN bed for a filterless
 * generator — so the bandwidth cannot be applied that way. It is applied to the *noise
 * floor* instead, which is the audible half of the same physics: white noise through a
 * filter of bandwidth `B` keeps a fraction `B / B₀` of its power, so its amplitude scales
 * as `sqrt(B / B₀)` ([bandPeakFor]). Station levels are unchanged, matching upstream's
 * gain compensation, which restores the signal but not the noise.
 *
 * **What this is not.** Without a filter, a narrow bandwidth does not reject an
 * off-frequency interferer, does not round the CW envelope, and does not change the
 * station's level. ADR-0009 declares the approximation; the numbers are the primary
 * sources above, and the setting's job is to be audible and honest, not to be a DSP
 * emulation.
 */
object Receiver {

    /** Upstream `Ini.Pitch` (`Ini.pas:240`). */
    const val DEFAULT_PITCH_HZ: Int = 600

    /** The ends of upstream's pitch list (`ComboBox1`: 300…900 Hz in 50 Hz steps). */
    const val MIN_PITCH_HZ: Int = 300
    const val MAX_PITCH_HZ: Int = 900

    /** `ComboBox1` steps by 50 Hz. */
    const val PITCH_STEP_HZ: Int = 50

    /** Upstream `Ini.BandWidth` (`Ini.pas:239`); also the filter's gain reference. */
    const val DEFAULT_BANDWIDTH_HZ: Int = 500

    /** The lowest entry of upstream's bandwidth list (`Main.pas:1693-1697`). */
    const val MIN_BANDWIDTH_HZ: Int = 100

    /** The highest entry of upstream's bandwidth list — 11 entries from 100 to 600 Hz. */
    const val MAX_BANDWIDTH_HZ: Int = 600

    /** Upstream's list steps by 50 Hz (`100 + BwNo * 50`). */
    const val BANDWIDTH_STEP_HZ: Int = 50

    /** Upstream `Ini.Rit` is clamped to ±500 (`Main.pas:2319-2326`). */
    const val MAX_RIT_HZ: Int = 500

    /** Upstream `Ini.RitStepIncr` (`Ini.pas:243`): 50 Hz per click. */
    const val RIT_STEP_HZ: Int = 50

    /**
     * The pitch a received station is heard at when the operator's RIT is [ritHz].
     *
     * Upstream's own sidetone is not passed through here (it is mixed in after the station
     * loop), so the caller must use `ritHz = 0` for self-monitor voices.
     */
    fun receivedPitchHz(pitchHz: Int, ritHz: Int): Double = (pitchHz + ritHz).toDouble()

    /**
     * The noise bed's peak at [bandwidthHz], relative to [NoiseBed.DEFAULT_BAND_PEAK] at
     * [DEFAULT_BANDWIDTH_HZ].
     *
     * Noise amplitude is proportional to the square root of the receiver's bandwidth; see
     * the class doc for why that is the whole of this port's bandwidth effect.
     *
     * @param referencePeak the bed's peak at [DEFAULT_BANDWIDTH_HZ]; T8's calibrated value
     *   by default, so an unchanged setting keeps the sound T8 shipped.
     */
    fun bandPeakFor(bandwidthHz: Int, referencePeak: Double = NoiseBed.DEFAULT_BAND_PEAK): Double {
        val ratio = bandwidth(bandwidthHz).toDouble() / DEFAULT_BANDWIDTH_HZ
        return referencePeak * sqrt(ratio)
    }

    /** [bandwidthHz] clamped to upstream's list and snapped to its 50 Hz grid. */
    fun bandwidth(bandwidthHz: Int): Int = snap(
        value = bandwidthHz,
        step = BANDWIDTH_STEP_HZ,
        min = MIN_BANDWIDTH_HZ,
        max = MAX_BANDWIDTH_HZ,
    )

    /** [pitchHz] clamped to upstream's list and snapped to its 50 Hz grid. */
    fun pitch(pitchHz: Int): Int = snap(
        value = pitchHz,
        step = PITCH_STEP_HZ,
        min = MIN_PITCH_HZ,
        max = MAX_PITCH_HZ,
    )

    /** [ritHz] clamped to ±500 and snapped to upstream's 50 Hz step. */
    fun rit(ritHz: Int): Int = snap(value = ritHz, step = RIT_STEP_HZ, min = -MAX_RIT_HZ, max = MAX_RIT_HZ)

    /** Rounds to the nearest multiple of [step], then clamps into `min..max`. */
    private fun snap(value: Int, step: Int, min: Int, max: Int): Int =
        ((value.toDouble() / step).roundToInt() * step).coerceIn(min, max)
}
