package com.bh4gzk.morserunner.audio

/**
 * The band's background noise: spec §7's **QRN — impulse + band noise**.
 *
 * Upstream makes it in two layers, both inside `Contest.GetAudioBlock`:
 *
 *  - a per-sample uniform bed, `Re[i] = 3 * NOISEAMP * (Random - 0.5)` (`Contest.pas:695-703`,
 *    `NOISEAMP = 6000`), i.e. white noise over ±9 000 against a station's ±9 000…±45 000;
 *  - over that bed, **1 % of samples** are replaced by `60 * NOISEAMP * (Random - 0.5)`
 *    (`:706-711`): the crackle of a rural band, 60× the bed's amplitude.
 *
 * and, with probability 0.01 per 11 025 Hz block (`:712`), a `TQrnStation` burst: a
 * 0–1 s envelope of 1 %-dense random-sign impulses whose amplitude is
 * `1E5 * 10^(2·Random)` — a lightning crash, 0.55× to 55× the ordinary click.
 *
 * **Levels.** This port has no receiver filter between the noise and the ear. Upstream's
 * two 3-pass moving averages (`Contest.pas:125-135`, `BufSize = 512`) cut the bed's RMS by
 * about 20×, so the numbers above are corrected to what the ear actually hears: the bed
 * starts at [DEFAULT_BAND_PEAK] (≈ −41 dBFS peak, ≈ 30 dB below a station at level 0.6)
 * and an impulse is 60× that. The crash is capped at [DEFAULT_BURST_GAIN] × the click
 * instead of upstream's 55×: at our master gain the mixer can absorb it, and a wall of
 * clipped impulses stops sounding like lightning. These are the constants T9 will expose
 * on the settings screen.
 *
 * **Contract.**
 *  - [render] adds into the caller's block and allocates nothing (an xorshift state and two
 *    burst fields are the whole state; `NoiseBedTest` measures the allocation).
 *  - the sample sequence depends only on the seed and the sample index, never on how the
 *    stream is cut into blocks, so the mixer stays block-size independent.
 *  - the seed is the caller's business: the mixer takes one bed per session.
 */
class NoiseBed(
    val sampleRate: Int = 48_000,
    /** Peak of the uniform white bed, in the mixer's pre-gain units. */
    val bandPeak: Double = DEFAULT_BAND_PEAK,
    /** Average impulses per second — upstream's 1 % at 11 025 Hz. */
    val impulseRateHz: Double = DEFAULT_IMPULSE_RATE_HZ,
    /** An impulse's peak relative to [bandPeak]; upstream `60 * NOISEAMP` vs `3 * NOISEAMP`. */
    val impulseGain: Double = DEFAULT_IMPULSE_GAIN,
    /** Average bursts per second — upstream's 1 % per 46 ms block. */
    val burstRateHz: Double = DEFAULT_BURST_RATE_HZ,
    val burstMinSeconds: Double = 0.05,
    val burstMaxSeconds: Double = 1.0,
    /** Extra click amplitude during a burst: `1 + burstGain * random` (see the class doc). */
    val burstGain: Double = DEFAULT_BURST_GAIN,
    seed: Long = 1L,
) {

    init {
        require(sampleRate > 0) { "sample rate must be positive: $sampleRate" }
        require(bandPeak >= 0.0) { "band peak must not be negative: $bandPeak" }
        require(impulseRateHz >= 0.0) { "impulse rate must not be negative: $impulseRateHz" }
        require(burstRateHz >= 0.0) { "burst rate must not be negative: $burstRateHz" }
        require(burstMinSeconds in 0.0..burstMaxSeconds) {
            "burst duration range is inverted: $burstMinSeconds..$burstMaxSeconds"
        }
        require(burstGain >= 0.0) { "burst gain must not be negative: $burstGain" }
    }

    /** One draw per sample, so the impulse and burst rates are per second, not per block. */
    private val impulseProbability: Double = (impulseRateHz / sampleRate).coerceIn(0.0, 1.0)
    private val burstProbability: Double = (burstRateHz / sampleRate).coerceIn(0.0, 1.0)
    private val burstMinSamples: Int = (burstMinSeconds * sampleRate).toInt()
    private val burstMaxSamples: Int = (burstMaxSeconds * sampleRate).toInt()

    /** xorshift64\* state. Zero is the generator's fixed point, so a zero seed is remapped. */
    private var state: Long = if (seed == 0L) DEFAULT_SEED else seed

    /** Samples left in the current burst; `0` when the band is quiet. */
    private var burstRemaining: Int = 0

    /** The current burst's click multiplier, `1.0` outside a burst. */
    private var burstFactor: Double = 1.0

    /**
     * Adds [frames] samples of noise to `out[0 until frames]`.
     *
     * Addition, not replacement: a station and the band are summed, exactly as upstream sums
     * its noise into the same complex block the stations write to.
     */
    fun render(out: FloatArray, frames: Int) {
        require(frames in 0..out.size) { "frames out of range: $frames / ${out.size}" }
        val burstChance = burstProbability
        val impulseChance = impulseProbability
        val band = bandPeak
        var remaining = burstRemaining
        var factor = burstFactor

        for (i in 0 until frames) {
            // Burst bookkeeping first, so a burst's first click is as loud as its last.
            if (remaining > 0) {
                remaining--
                if (remaining == 0) factor = 1.0
            } else if (burstChance > 0.0 && nextUniform() < burstChance) {
                remaining = nextBurstSamples()
                factor = 1.0 + burstGain * nextUniform()
            }

            var sample = (nextUniform() - 0.5) * 2.0 * band
            if (impulseChance > 0.0 && nextUniform() < impulseChance) {
                sample += (nextUniform() - 0.5) * 2.0 * band * impulseGain * factor
            }
            out[i] += sample.toFloat()
        }

        burstRemaining = remaining
        burstFactor = factor
    }

    private fun nextBurstSamples(): Int {
        if (burstMaxSamples <= burstMinSamples) return burstMinSamples
        return burstMinSamples + (nextUniform() * (burstMaxSamples - burstMinSamples)).toInt()
    }

    /**
     * xorshift64\* with a 53-bit mantissa, the same shape `SeededRandom` uses.
     *
     * A field-based generator rather than `kotlin.random.Random`: the render path must not
     * call a synchronised or allocating source, and the test measures the allocation.
     */
    private fun nextUniform(): Double {
        var z = state
        z = z xor (z ushr 12)
        z = z xor (z shl 25)
        z = z xor (z ushr 27)
        state = z
        return ((z * MIX) ushr 11).toDouble() * DOUBLE_UNIT
    }

    companion object {
        /** ≈ −41 dBFS, ≈ 30 dB below a station at level 0.6 — audible hiss, not masking. */
        const val DEFAULT_BAND_PEAK: Double = 0.015

        /** `Contest.pas:710`: 1 % of upstream's 11 025 Hz samples. */
        const val DEFAULT_IMPULSE_RATE_HZ: Double = 110.0

        /** `Contest.pas:710`: `60 * NOISEAMP` against the bed's `3 * NOISEAMP`. */
        const val DEFAULT_IMPULSE_GAIN: Double = 60.0

        /** `Contest.pas:712`: 1 % per `BufSize = 512` block at 11 025 Hz. */
        const val DEFAULT_BURST_RATE_HZ: Double = 0.215

        /** Upstream allows 55×; capped so the crash stays inside the mixer's full scale. */
        const val DEFAULT_BURST_GAIN: Double = 8.0

        /** xorshift64's fixed point; any other odd-ish seed is used as given. */
        private const val DEFAULT_SEED: Long = 0x2545F4914F6CDD1DL

        private const val MIX: Long = 2685821657736338717L

        /** `2^-53`: the 53-bit mantissa of a double. */
        private const val DOUBLE_UNIT: Double = 1.0 / (1L shl 53)
    }
}
