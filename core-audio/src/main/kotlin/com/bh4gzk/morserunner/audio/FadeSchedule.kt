package com.bh4gzk.morserunner.audio

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a station's QSB parameters into the gain schedule the mixer interpolates.
 *
 * The station's *parameters* come from the roster (`core-contest`'s `FadeProfile`:
 * upstream's `Qsb.Bandwidth`); the *audio* is this module's business. The schedule is
 * computed here, off the audio thread — [generate] allocates one `FloatArray` and the
 * mixer then only reads it, which is what keeps the render path allocation-free.
 *
 * Timing is upstream's (`Qsb.pas`):
 *  - a new gain target every `BufSize div 4` samples of the 11 025 Hz clock — **11.6 ms**;
 *  - the correlation length is `Ceil(0.37 * Rate / (128 * bandwidth))` steps, i.e. 0.6 s to
 *    3.7 s over upstream's QSB range (0.1–0.6) and 0.13–0.6 s over its flutter range
 *    (3–33) — the same formula the roster used to pick the number.
 *
 * Structure and normalisation, where this deliberately leaves upstream:
 *  - **three cascaded moving averages**, upstream's `Filt.Passes := 3`. One pass followed by
 *    a magnitude still wobbles near a null, because `|z|` is a non-linear function of an
 *    almost-zero average; the cascade is what makes a fade *slow*.
 *  - the cascade length is split across the stages, so the **total** time constant stays
 *    upstream's. Three cascaded averages of `points` each would fade three times too slowly
 *    (11 s instead of 3.7 s), and a station would barely fade inside one message.
 *  - the gain is **calibrated by measurement**, not by a closed form: the variance after a
 *    cascade depends on how each stage's input is correlated with itself, and a derived
 *    constant left every gain pinned at full scale (three tests caught it). The mean
 *    magnitude over a fixed warm-up is scaled to 1 — which doubles as the warm start that
 *    stops every message opening inside a fade.
 *  - upstream's own constant (`Points^-3` times `3 * Points`) leaves the gain at about
 *    `sqrt(2)/Points`, i.e. 0.004 at bandwidth 0.1: as written its QSB path mutes a station
 *    instead of fading it. It ships with `Qsb = false`, which is presumably why.
 *
 * T8 owns authenticity (exact Rayleigh statistics, flutter, QRN/QRM).
 */
object FadeSchedule {

    /** How far a full null takes the signal down; T8/T9 will expose it as a setting. */
    const val DEFAULT_DEPTH = 0.8

    /** `Qsb.pas:39` `Filt.Passes := 3`. */
    private const val PASSES = 3

    /** Warm-up steps per stage point, used both to fill the cascade and to calibrate it. */
    private const val WARMUP_STAGES = 8

    /** Upstream's clock and buffer quarter (`Ini.pas:24`, `Ini.BufSize div 4`). */
    const val UPSTREAM_RATE = 11_025.0
    const val UPSTREAM_STEP_SAMPLES = 128

    private const val BANDWIDTH_SCALE = 0.37

    /** Seconds between gain updates — 11.61 ms. */
    const val STEP_SECONDS: Double = UPSTREAM_STEP_SAMPLES / UPSTREAM_RATE

    /** Samples between gain targets at [sampleRate]; the mixer is told the same value. */
    fun stepSamples(sampleRate: Int): Int {
        require(sampleRate > 0) { "sample rate must be positive: $sampleRate" }
        return maxOf(1, (STEP_SECONDS * sampleRate).roundToInt())
    }

    /** Upstream `Qsb.pas:63`: `Ceil(0.37 * Rate / (128 * bandwidth))`. */
    fun correlationSteps(bandwidthHz: Double): Int {
        require(bandwidthHz > 0.0) { "bandwidth must be positive: $bandwidthHz" }
        return ceil(BANDWIDTH_SCALE * UPSTREAM_RATE / (UPSTREAM_STEP_SAMPLES * bandwidthHz))
            .toInt()
            .coerceAtLeast(1)
    }

    /**
     * One gain per fade step, in `[1 - depth, 1]`, or `null` when the message is empty.
     *
     * @param bandwidthHz upstream `Qsb.Bandwidth` (0.1–0.6 QSB, 3–33 flutter).
     * @param random a uniform source in `[0, 1)`, supplied by the caller so this module
     *   needs no random number generator of its own and the schedule stays reproducible
     *   from whatever seed the roster used.
     */
    fun generate(
        bandwidthHz: Double,
        sampleRate: Int,
        samples: Int,
        depth: Double = DEFAULT_DEPTH,
        random: () -> Double,
    ): FloatArray? {
        require(sampleRate > 0) { "sample rate must be positive: $sampleRate" }
        require(samples >= 0) { "sample count cannot be negative: $samples" }
        require(depth in 0.0..1.0) { "fade depth out of range: $depth" }
        if (depth == 0.0 || samples == 0) return null

        val stepSamples = stepSamples(sampleRate)
        val steps = (samples + stepSamples - 1) / stepSamples + 1
        val stagePoints = maxOf(1, correlationSteps(bandwidthHz) / PASSES)

        val ringRe = Array(PASSES) { DoubleArray(stagePoints) }
        val ringIm = Array(PASSES) { DoubleArray(stagePoints) }
        val sumRe = DoubleArray(PASSES)
        val sumIm = DoubleArray(PASSES)
        var cursor = 0

        fun rawMagnitude(): Double {
            var re = 2.0 * random() - 1.0
            var im = 2.0 * random() - 1.0
            for (pass in 0 until PASSES) {
                sumRe[pass] += re - ringRe[pass][cursor]
                sumIm[pass] += im - ringIm[pass][cursor]
                ringRe[pass][cursor] = re
                ringIm[pass][cursor] = im
                re = sumRe[pass] / stagePoints
                im = sumIm[pass] / stagePoints
            }
            cursor = (cursor + 1) % stagePoints
            return sqrt(re * re + im * im)
        }

        var magnitudeSum = 0.0
        repeat(WARMUP_STAGES * stagePoints) { magnitudeSum += rawMagnitude() }
        val scale = (WARMUP_STAGES * stagePoints) / magnitudeSum

        val floor = 1.0 - depth
        return FloatArray(steps) {
            val shape = min(rawMagnitude() * scale, 1.0)
            (1.0 - depth * (1.0 - shape)).coerceIn(floor, 1.0).toFloat()
        }
    }

    /** A per-station seed: two stations of one session never fade in lockstep. */
    fun seedFor(rootSeed: Long, call: String): Long {
        var z = rootSeed
        for (character in call) {
            z = (z xor character.code.toLong()) * MIX
            z = (z xor (z ushr 31)) * GOLDEN
        }
        return z xor (z ushr 29)
    }

    private val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
    private val MIX = 0xC2B2AE3D27D4EB4FuL.toLong()
}
