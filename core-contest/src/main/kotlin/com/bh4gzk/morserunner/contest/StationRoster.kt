package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.MasterDta
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.DEFAULT_STEP_BUDGET
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Expression
import com.bh4gzk.morserunner.expr.Value
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How a simulated operator behaves (spec §6; the LID failures themselves are T8's effect). */
enum class BehaviourProfile { NORMAL, LID }

/**
 * The fade parameters a station will be given by the audio effects (T8).
 *
 * Transcribed from `DxStn.pas:84-88`: QSB bandwidth is `0.1 + Random/2`; when
 * flutter is enabled, 30 % of stations replace it with `3 + Random*30`. T5 only
 * generates the numbers — T8 turns them into audio.
 */
data class FadeProfile(
    /** Upstream `Qsb.Bandwidth` in the same unit (0.1..0.6 QSB, 3..33 flutter). */
    val bandwidthHz: Double,
    val flutter: Boolean,
)

/**
 * The call-history values one call needs, keyed by the config's column names
 * (`zone`, `check`, `section`).
 *
 * This is the seam T6 fills by parsing the asset named in `callHistory.asset`
 * (`CQWWCW.TXT`, `SSCW.TXT`, `IARU_HF.txt`); T5 does not parse those formats, and
 * tests supply small, clearly-labelled maps. `null` means "this call is not in the
 * history", which makes the roster skip it — the same reason upstream picks its
 * stations *from* the history file.
 */
fun interface HistoryLookup {
    fun recordFor(call: String): Map<String, String>?
}

/**
 * What the roster is allowed to make up. The operator's settings are inputs, not
 * draws (T9 owns the settings UI).
 */
data class RosterSettings(
    /** The operator's speed in WPM; `station.speedRange` expressions read it as `speed`. */
    val speedWpm: Int,
    /** The operator's audio pitch; a station's pitch is this plus a Gaussian offset. */
    val pitchHz: Int = 600,
    /**
     * How far a station may sit from the operator's pitch, in Hz. Upstream spreads
     * `RndGaussLim(0, 50)` in single-call mode and `(0, 300)` in a pile-up
     * (`DxStn.pas:84-88`); the default is the pile-up value because that is when
     * the spread is audible.
     */
    val pitchSpreadHz: Int = 300,
    /** 0–8 stations on the air at once (spec §6). */
    val maxStations: Int = StationRoster.MAX_STATIONS,
    /** Upstream `Ini.Lids`: 10 % of stations are LIDs (`DxStn.pas:73`). */
    val lids: Boolean = true,
    /** Upstream `Ini.Flutter`: 30 % of stations flutter (`DxStn.pas:86-88`). */
    val flutter: Boolean = false,
    /** Serial range for a `{ "source": "serial" }` binding; defaults to the config's serial field. */
    val serialRange: IntRange? = null,
) {
    init {
        require(speedWpm in 1..100) { "speedWpm out of range: $speedWpm" }
        require(pitchHz > 0) { "pitchHz must be positive: $pitchHz" }
        require(pitchSpreadHz >= 0) { "pitchSpreadHz must not be negative: $pitchSpreadHz" }
        require(maxStations in 0..StationRoster.MAX_STATIONS) { "maxStations out of range: $maxStations" }
        serialRange?.let { require(it.first <= it.last) { "serialRange is inverted: $it" } }
    }
}

/**
 * One simulated station, with everything spec §6 asks a roster entry to carry:
 * call, speed, pitch, level, fade profile, behaviour profile — plus the exchange
 * the QSO engine will send and score.
 */
data class RosterStation(
    val call: String,
    val speedWpm: Int,
    /** The station's tone frequency: the operator's pitch plus its own offset. */
    val pitchHz: Int,
    /** Linear level in `[0.2, 1.0]`; see [StationRoster] for the transcription. */
    val level: Double,
    val fade: FadeProfile,
    val behaviour: BehaviourProfile,
    /** The declared draws that produced [exchange], in declaration order — the replay log. */
    val dice: Map<String, Double>,
    val exchange: GeneratedExchange,
)

/**
 * The 0–8 concurrent simulated stations (spec §6, ticket T5).
 *
 * Each [add] draws a call from the `station.callPool` (`MASTER.DTA`), generates
 * that call's exchange through [TheirExchangeGenerator] using the call's
 * call-history values, its serial and its declared dice, and gives it the audible
 * properties upstream gives a DX station (`DxStn.pas:73-90`):
 *
 *  - speed: `Round(min + (max-min)*r)` where `min`/`max` come from
 *    `station.speedRange` evaluated with the operator's `speed`;
 *  - pitch: the operator's pitch plus `RndGaussLim(0, pitchSpreadHz)` — the same
 *    limited-Gaussian rejection loop as `RndFunc.pas:46-60`; kept positive;
 *  - level: upstream's `Amplitude := 9000 + 18000*(1 + RndUShaped)` with
 *    `RndUShaped = sin(pi*(r-0.5))`, normalised by its 45 000 maximum → `[0.2, 1.0]`;
 *  - fade: QSB bandwidth `0.1 + r/2`, or `3 + 30r` for the 30 % that flutter
 *    (flutter only when [RosterSettings.flutter]);
 *  - behaviour: LID with probability 0.10 when [RosterSettings.lids].
 *
 * **Replay contract.** Every random decision comes from one [SeededRandom] started
 * at [seed], in this order per station: call (per attempt, duplicates retried up to
 * ten times like `StnColl.pas:AddCaller`), speed, pitch (one or more Gaussian
 * rejection draws), level, QSB, flutter flag (only when enabled), behaviour (only
 * when enabled), serial (only when the config binds one), then the declared dice in
 * declaration order. The same seed and inputs therefore reproduce the same roster
 * byte for byte, and each station exposes the draws that made its exchange.
 *
 * A call with no call-history record is skipped and another call is drawn: a
 * station whose exchange cannot be generated is not a station. Running out of
 * attempts is a data error (`IllegalStateException`), never a fabricated exchange.
 */
class StationRoster(
    private val config: ContestConfig,
    private val pool: MasterDta,
    private val history: HistoryLookup? = null,
    private val settings: RosterSettings,
    val seed: Long,
    private val calls: CallFacts = CallFacts.Unavailable,
) {

    companion object {
        /** Spec §6: at most eight stations are on the air at once. */
        const val MAX_STATIONS: Int = 8

        /** Duplicate-call retries per addition (`StnColl.pas:AddCaller`). */
        private const val ATTEMPTS: Int = 10
    }

    private val random = SeededRandom(seed)
    private val generator = TheirExchangeGenerator(config, calls)
    private val their: TheirExchange = config.exchange.their
    private val historyColumns: List<String> = their.bindings.values
        .filterIsInstance<TheirBinding.HistoryColumn>()
        .map { it.column }
    private val stationList = mutableListOf<RosterStation>()
    private val serialRange: IntRange?
    private val speedMin: Double
    private val speedMax: Double

    init {
        require(pool.size > 0) { "the call pool is empty" }
        require(historyColumns.isEmpty() || history != null) {
            "contest '${config.id}' binds call-history columns $historyColumns; a HistoryLookup is required"
        }
        serialRange = resolveSerialRange()
        val bounds = evaluateSpeedRange()
        speedMin = bounds.first
        speedMax = bounds.second
    }

    /** The stations currently on the air, oldest first. */
    val stations: List<RosterStation> get() = stationList.toList()

    val size: Int get() = stationList.size

    val isFull: Boolean get() = stationList.size >= settings.maxStations

    /**
     * Adds one station, or returns `null` when the roster is at capacity (0–8).
     *
     * @throws IllegalStateException when no station could be generated in [ATTEMPTS]
     *   attempts (the pool does not intersect the call history, say) or when the
     *   contest's own rules cannot generate an exchange for a call it picked.
     */
    fun add(): RosterStation? {
        if (isFull) return null
        var problem = "no attempt made"
        for (attempt in 1..ATTEMPTS) {
            val call = pool.callAt(random.nextInt(0, pool.size))
            if (stationList.any { it.call == call }) {
                problem = "duplicate call '$call'"
                continue
            }
            val record = history?.recordFor(call)
            if (historyColumns.isNotEmpty()) {
                if (record == null) {
                    problem = "no call-history record for '$call'"
                    continue
                }
                val missing = historyColumns.firstOrNull { it !in record }
                if (missing != null) {
                    problem = "call-history record for '$call' has no '$missing'"
                    continue
                }
            }

            val speed = drawSpeed()
            val pitch = drawPitch()
            val level = drawLevel()
            val fade = drawFade()
            val behaviour = drawBehaviour()
            val serial = serialRange?.let { it.first + random.nextInt(0, it.last - it.first + 1) }
            val dice = Dice.draw(their.dice, random)

            val exchange = when (val generated = generator.generate(dice.values, record ?: emptyMap(), serial)) {
                is GenerationResult.Failure -> throw IllegalStateException(
                    "contest '${config.id}' could not generate an exchange for '$call': ${generated.message}",
                )
                is GenerationResult.Success -> generated.exchange
            }
            val station = RosterStation(
                call = call,
                speedWpm = speed,
                pitchHz = pitch,
                level = level,
                fade = fade,
                behaviour = behaviour,
                dice = dice.values,
                exchange = exchange,
            )
            stationList += station
            return station
        }
        throw IllegalStateException(
            "contest '${config.id}': could not create a station in $ATTEMPTS attempts ($problem)",
        )
    }

    /** Removes the station with [call]; `true` when one was on the air. */
    fun remove(call: String): Boolean = stationList.removeAll { it.call == call }

    fun clear() {
        stationList.clear()
    }

    // --- draws -----------------------------------------------------------------

    private fun resolveSerialRange(): IntRange? {
        val bindsSerial = their.bindings.values.any { it is TheirBinding.Serial }
        if (!bindsSerial) return null
        settings.serialRange?.let { return it }
        val field = config.exchange.myFields.firstOrNull { it.type == FieldType.SERIAL }
            ?: throw IllegalArgumentException(
                "contest '${config.id}' binds a serial but declares no serial exchange field to take its range from",
            )
        return field.range
            ?: throw IllegalArgumentException(
                "contest '${config.id}' serial field '${field.key}' declares no range",
            )
    }

    private fun evaluateSpeedRange(): Pair<Double, Double> {
        val environment = config.environment("speed" to Value.num(settings.speedWpm))
        val min = speedBound(config.station.speedRange.min, "min", environment)
        val max = speedBound(config.station.speedRange.max, "max", environment)
        require(min <= max) { "station.speedRange is inverted: min $min > max $max" }
        return min to max
    }

    private fun speedBound(expression: Expression, label: String, environment: Map<String, Value>): Double =
        when (val result = expression.evaluate(environment, DEFAULT_STEP_BUDGET, calls)) {
            is EvalResult.Failure ->
                throw IllegalArgumentException("station.speedRange.$label: ${result.error.render()}")
            is EvalResult.Success -> (result.value as? Value.Num)?.value
                ?: throw IllegalArgumentException(
                    "station.speedRange.$label produced ${result.value.typeName}, expected a number",
                )
        }

    /**
     * Upstream's uniform branch, `Round(Ini.Wpm - Min + (Min+Max)*Random)`
     * (`DxOper.pas:195`), clamped to the 1..100 WPM the keyer supports: a very
     * slow operator makes the lower bound non-positive (`speed - 5` at 5 WPM), and
     * a station still has to be sendable.
     */
    private fun drawSpeed(): Int =
        (speedMin + (speedMax - speedMin) * random.nextDouble()).roundToInt().coerceIn(1, 100)

    /** `RndGaussLim(0, spread)` plus the operator's pitch; positive for the tone generator. */
    private fun drawPitch(): Int {
        val offset = gaussLimited(mean = 0.0, limit = settings.pitchSpreadHz.toDouble())
        return (settings.pitchHz + offset).roundToInt().coerceAtLeast(1)
    }

    /** Upstream `Amplitude` normalised by its 45 000 maximum (`DxStn.pas:81`). */
    private fun drawLevel(): Double =
        (9000 + 18000 * (1 + sin(PI * (random.nextDouble() - 0.5)))) / 45000.0

    private fun drawFade(): FadeProfile {
        // Upstream always draws the QSB bandwidth first, then decides flutter.
        val qsb = 0.1 + random.nextDouble() / 2
        if (settings.flutter && random.nextDouble() < 0.3) {
            return FadeProfile(bandwidthHz = 3 + random.nextDouble() * 30, flutter = true)
        }
        return FadeProfile(bandwidthHz = qsb, flutter = false)
    }

    private fun drawBehaviour(): BehaviourProfile =
        if (settings.lids && random.nextDouble() < 0.10) BehaviourProfile.LID else BehaviourProfile.NORMAL

    /**
     * `RndGaussLim` from `RndFunc.pas:46-60`: a Gaussian with `sigma = limit/2`,
     * re-drawn until it lands inside `±limit`. The rejection loop is why the number
     * of stream draws per pitch varies — still deterministic, still replayable.
     */
    private fun gaussLimited(mean: Double, limit: Double): Double {
        if (limit <= 0.0) return mean
        while (true) {
            var uniform = random.nextDouble()
            while (uniform == 0.0) uniform = random.nextDouble() // ln(0) is -inf
            val normal = sqrt(-2.0 * ln(uniform)) * cos(2 * PI * random.nextDouble())
            val value = normal * 0.5 * limit
            if (abs(value) <= limit) return mean + value
        }
    }
}
