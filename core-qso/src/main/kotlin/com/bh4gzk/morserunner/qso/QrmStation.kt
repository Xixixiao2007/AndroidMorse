package com.bh4gzk.morserunner.qso

import com.bh4gzk.morserunner.contest.SeededRandom
import com.bh4gzk.morserunner.data.CallPool
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The interruptions a QRM station can send (`QrmStn.pas:45-49`). */
enum class QrmMessage { QRL, QRL2, LONG_CQ, QSY }

/**
 * `case Random(7) of 0: QRL?; 1,2: QRL? QRL?; 3,4,5: long CQ; 6: QSY` — `QrmStn.pas:45-49`.
 *
 * `else` is the QSY branch, which is what upstream's `6` (its last case label) means.
 */
internal fun qrmMessageFor(index: Int): QrmMessage = when (index) {
    0 -> QrmMessage.QRL
    1, 2 -> QrmMessage.QRL2
    in 3..5 -> QrmMessage.LONG_CQ
    else -> QrmMessage.QSY
}

/**
 * Another operator's CW signal, off-frequency — spec §7's **QRM** (`QrmStn.pas`).
 *
 * Upstream's `TQrmStation` is not a contestant: it has no QSO state machine, no exchange
 * and no log, it just transmits at the operator's frequency neighbourhood until its
 * patience runs out. Drawn exactly as `QrmStn.CreateStation` draws it:
 *
 * | field | upstream | here |
 * |---|---|---|
 * | patience | `1 + Random(5)` | [transmissions] = 1..5 |
 * | call | `Tst.PickCallOnly` | drawn from the contest's [CallPool] |
 * | level | `5000 + 25000·Random` | same, normalised by the roster's 45 000 |
 * | pitch | `Round(RndGaussLim(0, 300))` | offset from the operator's pitch, floored at [MIN_OFFSET_HZ] |
 * | speed | `30 + Random(20)` | 30–50 WPM |
 * | first message | `Random(7)` | [QrmMessage] |
 *
 * **One deviation, and why.** Upstream's Gaussian offset can land at 0 Hz — a station
 * calling CQ *on* the operator's frequency. In a phone's mixer there is no spatial cue to
 * tell the two apart, so spec §7's "off-frequency" would be unprovable and the effect
 * would sound like a second copy of the worked station. The offset is floored at
 * [MIN_OFFSET_HZ], keeping the sign.
 *
 * **What it is not.** Not a roster station and not a [DxOperator]: nothing here touches the
 * QSO engine, the log or the operator's random streams. The session schedules
 * transmissions and plays them as audio; the operator being worked never hears them.
 *
 * @param texts the whole episode, in order: the drawn first message, then one long CQ per
 *   remaining patience point — upstream's `evTimeout → SendMsg(msgLongCQ)` (`QrmStn.pas:65`).
 */
class QrmStation private constructor(
    val call: String,
    val speedWpm: Int,
    /** Absolute pitch in Hz: the operator's pitch plus this station's offset. */
    val pitchHz: Int,
    /** Linear level in `[0.111, 0.667]`, as the roster's levels are. */
    val level: Double,
    val texts: List<String>,
) {

    /** How many transmissions it will make before it disappears (upstream `Patience`). */
    val transmissions: Int get() = texts.size

    companion object {
        /** Upstream `RndGaussLim(0, 300)`: the largest offset it allows. */
        const val MAX_OFFSET_HZ: Int = 300

        /** The floor that keeps "off-frequency" true (see the class doc). */
        const val MIN_OFFSET_HZ: Int = 100

        /** The normalising divisor of upstream's `Amplitude` (`DxStn.pas:81`). */
        private const val FULL_SCALE: Double = 45_000.0

        /** `QrmStn.pas:36-44`, in its own draw order (see the table above). */
        fun create(
            random: SeededRandom,
            basePitchHz: Int,
            hisCall: String,
            pool: CallPool,
        ): QrmStation {
            require(pool.size > 0) { "the call pool is empty" }

            // Patience := 1 + Random(5)
            val patience = 1 + random.nextInt(0, 5)
            // MyCall := Tst.PickCallOnly
            val call = pool.callAt(random.nextInt(0, pool.size))
            // Amplitude := 5000 + 25000 * Random
            val level = (5_000 + 25_000 * random.nextDouble()) / FULL_SCALE
            // Pitch := Round(RndGaussLim(0, 300)), floored away from the operator's pitch.
            val drawn = gaussLimited(random, 0.0, MAX_OFFSET_HZ.toDouble()).roundToInt()
            val offset = if (abs(drawn) < MIN_OFFSET_HZ) {
                if (drawn < 0) -MIN_OFFSET_HZ else MIN_OFFSET_HZ
            } else {
                drawn
            }
            // WpmS := 30 + Random(20)
            val speedWpm = 30 + random.nextInt(0, 20)
            // case Random(7) of ...
            val first = qrmMessageFor(random.nextInt(0, 7))

            val texts = buildList {
                add(render(first, call, hisCall))
                repeat(patience - 1) { add(render(QrmMessage.LONG_CQ, call, hisCall)) }
            }
            return QrmStation(call, speedWpm, basePitchHz + offset, level, texts)
        }

        /**
         * The pause before the next transmission: `Round(RndGaussLim(4 s, 2 s))`
         * (`QrmStn.pas:57`), i.e. 2–6 s around 4 s.
         */
        fun gapMs(random: SeededRandom): Long =
            (gaussLimited(random, 4.0, 2.0) * 1_000.0).roundToLong()

        /**
         * Upstream's wording (`Contest.pas:438-443`), rendered with the shared token
         * expander: `<my>` is the QRM station's call, `<his>` the operator's.
         *
         * A contest that overrides `msgLongCQ` (`ArrlSS.pas:257` and friends) would need
         * its own wording here; the three schema-v1 configs carry no such override, and
         * the per-contest pass (T10) can add one.
         */
        private fun render(message: QrmMessage, call: String, hisCall: String): String =
            renderTokens(template(message), my = call, exchange = "", hisCall = hisCall)

        private fun template(message: QrmMessage): String = when (message) {
            QrmMessage.QRL -> "QRL?"
            QrmMessage.QRL2 -> "QRL?   QRL?"
            QrmMessage.LONG_CQ -> "CQ CQ TEST <my> <my> TEST"
            QrmMessage.QSY -> "<his>  QSY QSY"
        }
    }
}
