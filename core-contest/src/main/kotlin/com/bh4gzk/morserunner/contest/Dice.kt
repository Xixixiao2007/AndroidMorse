package com.bh4gzk.morserunner.contest

/**
 * The session's **only** source of randomness: a deterministic SplitMix64 stream.
 *
 * The contest language deliberately has no `random()` (ADR-0004 decision 3), so
 * every random decision the engine makes — the declared dice a station draws, its
 * speed, its pitch, whether it is a LID — comes from here. The same seed therefore
 * reproduces the same session byte for byte, which is what makes the roster and
 * the audio testable and a disputed QSO replayable.
 *
 * SplitMix64 is implemented here rather than reached for from the JDK so the
 * algorithm is part of this project, not a library implementation detail. Its
 * reference outputs are pinned by [DiceTest]: seed 0 starts
 * `0xE220A8397B1DCDAF`, `0x6E789E6AA1B965F4`, …
 */
class SeededRandom(val seed: Long) {

    private var state: Long = seed

    /** One 64-bit step of the stream. */
    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_A
        z = (z xor (z ushr 27)) * MIX_B
        return z xor (z ushr 31)
    }

    /** A draw in `[0, 1)` — the form `their.dice` is injected as (spec §4). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * DOUBLE_UNIT

    /** A uniform integer in `[fromInclusive, untilExclusive)`; consumes one step. */
    fun nextInt(fromInclusive: Int, untilExclusive: Int): Int {
        require(untilExclusive > fromInclusive) {
            "empty range [$fromInclusive, $untilExclusive)"
        }
        val bound = (untilExclusive - fromInclusive).toLong()
        return fromInclusive + ((nextLong() ushr 1) % bound).toInt()
    }

    private companion object {
        /** `0x9E3779B97F4A7C15`: the golden-ratio increment. */
        const val GOLDEN_GAMMA: Long = -7046029254386353131L

        /** `0xBF58476D1CE4E5B9`. */
        const val MIX_A: Long = -4658895280553007687L

        /** `0x94D049BB133111EB`. */
        const val MIX_B: Long = -7723592293110705685L

        /** `2^-53`: the 53-bit mantissa of a double. */
        const val DOUBLE_UNIT: Double = 1.0 / (1L shl 53)
    }
}

/**
 * One station's **declared** random draws: `their.dice` names are drawn from
 * [SeededRandom] in declaration order, one `[0, 1)` each (ADR-0004 decision 3).
 *
 * Keeping the draws in a value object means the engine can log exactly what a
 * station drew (`RosterStation.dice`) and replay a session from its seed — the
 * property a global `Random` call cannot offer. Declaration order is part of the
 * replay contract, so [values] is a [LinkedHashMap].
 */
class Dice private constructor(val values: Map<String, Double>) {

    /** The draws in declaration order. */
    val sequence: List<Double> get() = values.values.toList()

    fun value(name: String): Double? = values[name]

    override fun toString(): String =
        values.entries.joinToString(prefix = "Dice(", postfix = ")") { "${it.key}=${it.value}" }

    companion object {
        /** Draws one value per name, in the order the config declares them. */
        fun draw(names: List<String>, random: SeededRandom): Dice {
            val values = LinkedHashMap<String, Double>(names.size)
            for (name in names) values[name] = random.nextDouble()
            return Dice(values)
        }
    }
}
