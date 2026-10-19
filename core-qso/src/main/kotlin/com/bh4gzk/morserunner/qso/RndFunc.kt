package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.SeededRandom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * `RndGaussLim(mean, limit)` from `RndFunc.pas:46-60`: a Gaussian with `sigma = limit/2`,
 * re-drawn until it lands inside `±limit`.
 *
 * The rejection loop is why the number of stream draws varies from call to call — still
 * deterministic, still replayable from the seed. Shared by [DxOperator] (reply timeouts)
 * and [QrmStation] (its off-frequency offset and its inter-transmission gap); the loop
 * lives in one place so that "a limited Gaussian" cannot mean two different distributions.
 */
internal fun gaussLimited(random: SeededRandom, mean: Double, limit: Double): Double {
    if (limit <= 0.0) return mean
    while (true) {
        var first = random.nextDouble()
        // `ln(0)` is -inf and would make every candidate the same infinite value.
        while (first == 0.0) first = random.nextDouble()
        val normal = sqrt(-2.0 * ln(first)) * cos(2.0 * kotlin.math.PI * random.nextDouble())
        val value = normal * 0.5 * limit
        if (abs(value) <= limit) return mean + value
    }
}
