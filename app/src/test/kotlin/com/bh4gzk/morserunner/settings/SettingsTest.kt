package com.bh4gzk.morserunner.settings

import com.bh4gzk.morserunner.audio.Receiver
import com.bh4gzk.morserunner.session.SessionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The operator's settings: their defaults, their ranges, and the fact that they survive a
 * process death.
 *
 * Every default here has a primary source — `Ini.pas`'s declarations are the numbers upstream
 * ships, and `Main.pas`'s controls are the ranges its UI offers:
 *
 * | Setting | Upstream | Default | Range |
 * |---|---|---|---|
 * | duration | `Ini.Duration` (`Ini.pas:263`) | 30 min | 1–240 (`SpinEdit2`) |
 * | speed | `Ini.Wpm` (`:227`) | 25 WPM | 10–120 (`SpinEdit1`) |
 * | pitch | `Ini.Pitch` (`:240`) | 600 Hz | 300–900, step 50 (`ComboBox1`) |
 * | RIT | `Ini.Rit` (`:242`) | 0 Hz | ±500, step 50 (`Ini.RitStepIncr`) |
 * | bandwidth | `Ini.BandWidth` (`:239`) | 500 Hz | 100–600, step 50 (`ComboBox2`) |
 * | QRN/QRM/flutter/LIDs | `Ini.Qrn/Qrm/Flutter/Lids` (`:252-256`) | **on** | on/off |
 *
 * The four switches are upstream's off values **deliberately on** in this port: T8 shipped a
 * session whose sound is the ticket, and a switch that defaults to "you cannot hear the
 * feature" would need the ear check to be repeated per setting. ADR-0009 records the deviation
 * and the one-tap way back to upstream's default.
 */
class SettingsTest {

    // --- defaults ----------------------------------------------------------------------

    @Test
    fun `a fresh install starts from upstream's defaults`() {
        val settings = SessionSettings.DEFAULT
        assertEquals(30, settings.durationMinutes, "`Ini.Duration`")
        assertEquals(25, settings.speedWpm, "`Ini.Wpm`")
        assertEquals(600, settings.pitchHz, "`Ini.Pitch`")
        assertEquals(0, settings.ritHz, "`Ini.Rit`")
        assertEquals(500, settings.bandwidthHz, "`Ini.BandWidth`")
        assertEquals("W1AW", settings.myCall, "T7's placeholder operator call")
        assertEquals(SessionSettings.DEFAULT, SessionSettings().normalized(), "defaults are already legal")
    }

    @Test
    fun `the effects are on by default, and upstream's own defaults are off`() {
        // Upstream: `Ini.Qrn = false`, `Ini.Qrm = false`, `Ini.Flutter = false`, `Ini.Lids = false`.
        // T8 shipped them on (ADR-0008 §3-4) and this ticket keeps that, so the switch's job is
        // to be able to turn an effect *off*.
        assertTrue(SessionSettings.DEFAULT.qrn, "QRN off by default would hide T8's noise bed")
        assertTrue(SessionSettings.DEFAULT.qrm, "QRM off by default would hide T8's interferer")
        assertTrue(SessionSettings.DEFAULT.flutter, "flutter off by default would hide T8's fast fade")
        assertTrue(SessionSettings.DEFAULT.lids, "LIDs off by default would hide T8's LID operators")
    }

    @Test
    fun `the defaults agree with the session and receiver constants`() {
        // Two files, one truth: a drift here would mean the app starts a session with
        // different numbers than the settings screen shows.
        assertEquals(SessionController.DEFAULT_SPEED_WPM, SessionSettings.DEFAULT.speedWpm)
        assertEquals(SessionController.DEFAULT_PITCH_HZ, SessionSettings.DEFAULT.pitchHz)
        assertEquals(Receiver.DEFAULT_PITCH_HZ, SessionSettings.DEFAULT.pitchHz)
        assertEquals(Receiver.DEFAULT_BANDWIDTH_HZ, SessionSettings.DEFAULT.bandwidthHz)
        assertEquals(SessionController.DEFAULT_DURATION_MINUTES, SessionSettings.DEFAULT.durationMinutes)
    }

    // --- ranges of the on-screen controls ----------------------------------------------

    @Test
    fun `settings are clamped to what the controls can express`() {
        val wild = SessionSettings(
            durationMinutes = 9_999,
            speedWpm = 1,
            pitchHz = 5_000,
            ritHz = -9_999,
            bandwidthHz = 5_000,
            myCall = " bh4gzk ",
        ).normalized()

        assertEquals(SessionSettings.MAX_DURATION_MINUTES, wild.durationMinutes)
        assertEquals(SessionSettings.MIN_SPEED_WPM, wild.speedWpm)
        assertEquals(Receiver.MAX_PITCH_HZ, wild.pitchHz)
        assertEquals(-Receiver.MAX_RIT_HZ, wild.ritHz)
        assertEquals(Receiver.MAX_BANDWIDTH_HZ, wild.bandwidthHz)
        assertEquals("BH4GZK", wild.myCall, "the call is upper-cased and trimmed")
    }

    @Test
    fun `the pitched knobs snap to their grid instead of inventing a value`() {
        val snapped = SessionSettings(pitchHz = 537, ritHz = 37, bandwidthHz = 537).normalized()
        assertEquals(550, snapped.pitchHz, "pitch moves in 50 Hz steps")
        assertEquals(50, snapped.ritHz, "RIT moves in `Ini.RitStepIncr` steps")
        assertEquals(550, snapped.bandwidthHz, "bandwidth moves in 50 Hz steps")

        assertEquals(300, SessionSettings(pitchHz = 100).normalized().pitchHz)
        assertEquals(0, SessionSettings(ritHz = 24).normalized().ritHz)
    }

    @Test
    fun `a callsign keeps only what a call may contain`() {
        assertEquals("W1AW", SessionSettings(myCall = "   ").normalized().myCall, "blank falls back")
        assertEquals("W1AW", SessionSettings(myCall = "").normalized().myCall)
        assertEquals("BH4GZK/P", SessionSettings(myCall = "bh4gzk/p").normalized().myCall)
        assertEquals("W1AW", SessionSettings(myCall = "W1-AW!").normalized().myCall, "illegal characters are dropped")
        assertEquals(15, SessionSettings(myCall = "A".repeat(40)).normalized().myCall.length)
    }

    // --- the codec ---------------------------------------------------------------------

    @Test
    fun `settings survive an encode and decode`() {
        val custom = SessionSettings(
            durationMinutes = 45,
            speedWpm = 32,
            pitchHz = 750,
            ritHz = -100,
            bandwidthHz = 250,
            qrn = false,
            qrm = false,
            flutter = true,
            lids = false,
            myCall = "BH4GZK",
        )
        val decoded = SettingsCodec.decode(SettingsCodec.encode(custom))
        assertEquals(custom, decoded)
        assertNotEquals(SessionSettings.DEFAULT, decoded, "the round trip lost the values")
    }

    @Test
    fun `a missing or corrupt line falls back to its default instead of losing the rest`() {
        val stored = SettingsCodec.encode(SessionSettings(speedWpm = 40, myCall = "K1ABC"))
            .lines()
            .filterNot { it.startsWith("bandwidth=") }
            .joinToString("\n") +
            "\npitch=not a number\nqrm=maybe\nunknown-key=ignored"

        val decoded = SettingsCodec.decode(stored)
        assertEquals(40, decoded.speedWpm, "a good key next to a bad one must survive")
        assertEquals("K1ABC", decoded.myCall)
        assertEquals(SessionSettings.DEFAULT.bandwidthHz, decoded.bandwidthHz)
        assertEquals(SessionSettings.DEFAULT.pitchHz, decoded.pitchHz)
        assertEquals(SessionSettings.DEFAULT.qrm, decoded.qrm)
    }

    @Test
    fun `an out-of-range stored value is clamped on the way in`() {
        val decoded = SettingsCodec.decode(
            "duration=100000\nspeed=-3\nrit=-2500\npitch=1\nbandwidth=0",
        )
        assertEquals(SessionSettings.MAX_DURATION_MINUTES, decoded.durationMinutes)
        assertEquals(SessionSettings.MIN_SPEED_WPM, decoded.speedWpm)
        assertEquals(-Receiver.MAX_RIT_HZ, decoded.ritHz)
        assertEquals(Receiver.MIN_PITCH_HZ, decoded.pitchHz)
        assertEquals(Receiver.MIN_BANDWIDTH_HZ, decoded.bandwidthHz)
    }

    @Test
    fun `a value that looks like the format cannot break the format`() {
        // A callsign is the only free text here today, but the encoder is shared with the
        // session snapshot's exchange text, so the escaping has to hold.
        val hostile = SessionSettings(myCall = "W1\nAW=1")
        assertEquals("W1AW1", hostile.normalized().myCall, "the callsign filter drops the newline and `=`")
        val decoded = SettingsCodec.decode(SettingsCodec.encode(hostile))
        assertEquals("W1AW1", decoded.myCall, "the encoder normalises before it writes")
    }

    @Test
    fun `settings persist through the store and read back as defaults when absent`() {
        val store = MapStringStore()
        val settingsStore = SettingsStore(store)

        assertEquals(SessionSettings.DEFAULT, settingsStore.load(), "a first run has no stored values")

        val custom = SessionSettings(speedWpm = 35, pitchHz = 700, qrn = false)
        settingsStore.save(custom)
        assertEquals(custom, SettingsStore(store).load(), "another process reads the same values")

        // One write, not one per key: the store is a single blob so a torn write cannot leave
        // half the settings behind.
        assertEquals(1, store.writes)
    }
}
