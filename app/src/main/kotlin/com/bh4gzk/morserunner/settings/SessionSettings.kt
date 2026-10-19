package com.bh4gzk.morserunner.settings

import com.bh4gzk.morserunner.audio.Receiver

/**
 * Everything the operator can configure (spec §11 T9), and nothing else.
 *
 * The defaults are upstream's declarations in `Ini.pas` and the ranges are the ranges of
 * upstream's own controls (`Main.pas`'s `SpinEdit1`/`SpinEdit2`, `ComboBox1`/`ComboBox2`, the
 * RIT arrow buttons). The four effect switches are the one deliberate deviation: upstream ships
 * `Qrn = false, Qrm = false, Flutter = false, Lids = false` (`Ini.pas:252-256`), and this port
 * turns them **on** because T8 shipped a session whose point is the sound (ADR-0008 §3-4). The
 * setting's job is therefore to be able to turn an effect off, and ADR-0009 records the choice.
 *
 * The type is a value: [normalized] is the only place that decides what a legal setting is, so
 * a stored blob, a slider and a hand-written test all pass through the same rules.
 *
 * @param durationMinutes contest length in minutes — upstream `Ini.Duration`; a contest clamps
 *   it to its own `duration` range, so the same preference works for a 4-hour contest later.
 * @param speedWpm the operator's keying speed — `Ini.Wpm`, `SpeedRange` expressions read it.
 * @param pitchHz the operator's audio pitch — `Ini.Pitch`; the receiver's centre frequency.
 * @param ritHz receive incremental tuning — `Ini.Rit`; shifts received stations only.
 * @param bandwidthHz the receiver's bandwidth — `Ini.BandWidth`; see [Receiver.bandPeakFor].
 * @param qrn the band's background noise (spec §7) — upstream `Ini.Qrn`.
 * @param qrm the off-frequency interfering station — upstream `Ini.Qrm`.
 * @param flutter the fast fade on 30 % of stations — upstream `Ini.Flutter`.
 * @param lids whether 10 % of stations are LIDs — upstream `Ini.Lids`.
 * @param myCall the operator's callsign — upstream `Ini.Call`; T7's placeholder was `W1AW`.
 */
data class SessionSettings(
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
    val speedWpm: Int = DEFAULT_SPEED_WPM,
    val pitchHz: Int = Receiver.DEFAULT_PITCH_HZ,
    val ritHz: Int = 0,
    val bandwidthHz: Int = Receiver.DEFAULT_BANDWIDTH_HZ,
    val qrn: Boolean = true,
    val qrm: Boolean = true,
    val flutter: Boolean = true,
    val lids: Boolean = true,
    val myCall: String = DEFAULT_MY_CALL,
) {

    /**
     * The same settings with every value inside the range its control can express.
     *
     * Clamping rather than rejecting is the same choice `Main.pas` makes (`Max(0, Min(BwNo, …))`)
     * and it means a corrupt stored blob or a slider that overshoots can never start a session
     * with an impossible speed or a negative pitch.
     */
    fun normalized(): SessionSettings = SessionSettings(
        durationMinutes = durationMinutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES),
        speedWpm = speedWpm.coerceIn(MIN_SPEED_WPM, MAX_SPEED_WPM),
        pitchHz = Receiver.pitch(pitchHz),
        ritHz = Receiver.rit(ritHz),
        bandwidthHz = Receiver.bandwidth(bandwidthHz),
        qrn = qrn,
        qrm = qrm,
        flutter = flutter,
        lids = lids,
        myCall = normalizeCall(myCall),
    )

    companion object {
        /** `Ini.Duration` (`Ini.pas:263`). */
        const val DEFAULT_DURATION_MINUTES: Int = 30

        /** `SpinEdit2.MinValue`/`MaxValue` — one minute to four hours. */
        const val MIN_DURATION_MINUTES: Int = 1
        const val MAX_DURATION_MINUTES: Int = 240

        /** `Ini.Wpm` (`Ini.pas:227`). */
        const val DEFAULT_SPEED_WPM: Int = 25

        /** `SpinEdit1.MinValue`/`MaxValue` — 10 to 120 WPM. */
        const val MIN_SPEED_WPM: Int = 10
        const val MAX_SPEED_WPM: Int = 120

        /** T7's placeholder operator; upstream's own `Ini.Call` default is `VE3NEA` (`Ini.pas:223`). */
        const val DEFAULT_MY_CALL: String = "W1AW"

        /** A callsign long enough for a portable prefix and a suffix; upstream has no limit. */
        const val MAX_CALL_LENGTH: Int = 15

        val DEFAULT = SessionSettings()

        /**
         * Upper-cases a typed call and keeps only what a call may contain.
         *
         * A blank field falls back to the default operator instead of starting a session with
         * no call at all: the contest's exchange is derived from it (`SessionBootstrap`), so an
         * empty call would make the whole contest unselectable.
         */
        fun normalizeCall(call: String): String {
            val cleaned = call.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '/' }
                .take(MAX_CALL_LENGTH)
            return cleaned.ifBlank { DEFAULT_MY_CALL }
        }
    }
}
