package com.bh4gzk.morserunner.settings

/**
 * The settings' stored form: one `key=value` blob ([EncodedText]'s format).
 *
 * Decoding is deliberately **lenient per field**: a missing key, a number that does not parse
 * or a foreign key falls back to that field's default (or is ignored) and leaves the rest of the
 * settings intact — a partially corrupt blob must not cost the operator every preference they
 * set. Everything is then passed through [SessionSettings.normalized], so an out-of-range value
 * written by an older build cannot start an impossible session.
 *
 * [encode] normalises first, so illegal values never reach the store.
 */
object SettingsCodec {

    /** The key of each field in the blob. Short, stable, and independent of the Kotlin name. */
    internal const val KEY_DURATION = "duration"
    internal const val KEY_SPEED = "speed"
    internal const val KEY_PITCH = "pitch"
    internal const val KEY_RIT = "rit"
    internal const val KEY_BANDWIDTH = "bandwidth"
    internal const val KEY_QRN = "qrn"
    internal const val KEY_QRM = "qrm"
    internal const val KEY_FLUTTER = "flutter"
    internal const val KEY_LIDS = "lids"
    internal const val KEY_CALL = "call"

    /** The settings as the stored blob. */
    fun encode(settings: SessionSettings): String {
        val legal = settings.normalized()
        return buildList {
            add(EncodedText.line(KEY_DURATION, legal.durationMinutes.toString()))
            add(EncodedText.line(KEY_SPEED, legal.speedWpm.toString()))
            add(EncodedText.line(KEY_PITCH, legal.pitchHz.toString()))
            add(EncodedText.line(KEY_RIT, legal.ritHz.toString()))
            add(EncodedText.line(KEY_BANDWIDTH, legal.bandwidthHz.toString()))
            add(EncodedText.line(KEY_QRN, legal.qrn.toString()))
            add(EncodedText.line(KEY_QRM, legal.qrm.toString()))
            add(EncodedText.line(KEY_FLUTTER, legal.flutter.toString()))
            add(EncodedText.line(KEY_LIDS, legal.lids.toString()))
            add(EncodedText.line(KEY_CALL, legal.myCall))
        }.joinToString("\n")
    }

    /** The settings in [blob], with defaults for whatever is absent or unreadable. */
    fun decode(blob: String?): SessionSettings {
        if (blob.isNullOrBlank()) return SessionSettings.DEFAULT
        // The settings' values are plain text, so unwrap the escaping here rather than in
        // every field; the session snapshot cannot do that (its values have their own
        // delimiters) and passes `unescapeValues = false`.
        val values = EncodedText.parseLines(blob, unescapeValues = true)
        val defaults = SessionSettings.DEFAULT
        return SessionSettings(
            durationMinutes = int(values, KEY_DURATION, defaults.durationMinutes),
            speedWpm = int(values, KEY_SPEED, defaults.speedWpm),
            pitchHz = int(values, KEY_PITCH, defaults.pitchHz),
            ritHz = int(values, KEY_RIT, defaults.ritHz),
            bandwidthHz = int(values, KEY_BANDWIDTH, defaults.bandwidthHz),
            qrn = bool(values, KEY_QRN, defaults.qrn),
            qrm = bool(values, KEY_QRM, defaults.qrm),
            flutter = bool(values, KEY_FLUTTER, defaults.flutter),
            lids = bool(values, KEY_LIDS, defaults.lids),
            myCall = values[KEY_CALL] ?: defaults.myCall,
        ).normalized()
    }

    private fun int(values: Map<String, String>, key: String, fallback: Int): Int =
        values[key]?.trim()?.toIntOrNull() ?: fallback

    private fun bool(values: Map<String, String>, key: String, fallback: Boolean): Boolean =
        when (values[key]?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> fallback
        }
}

/**
 * The settings in their [StringStore], under one key.
 *
 * One key rather than one per field: the store is a single blob, so a half-written settings
 * change cannot exist — either the old blob or the new one is read.
 */
class SettingsStore(private val store: StringStore) {

    /** The stored settings, or the defaults when nothing (or nothing readable) is stored. */
    fun load(): SessionSettings = SettingsCodec.decode(store.read(KEY))

    /** Replaces the stored settings. */
    fun save(settings: SessionSettings) = store.write(KEY, SettingsCodec.encode(settings))

    companion object {
        /** The one key this concern owns. */
        const val KEY = "settings"
    }
}
