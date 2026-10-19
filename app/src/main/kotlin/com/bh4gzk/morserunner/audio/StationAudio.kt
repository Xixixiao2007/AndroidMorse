package com.bh4gzk.morserunner.audio

import com.bh4gzk.morserunner.contest.RosterStation
import com.bh4gzk.morserunner.contest.SeededRandom

/**
 * Turns a signal — a roster station, or the interfering station of spec §7's QRM — into
 * something the mixer can play: the keyed envelope at its own speed and pitch, with the
 * fade schedule (if any) precomputed.
 *
 * The station model lives in `core-contest` (`RosterStation`: call, speed, pitch, level,
 * fade parameters, behaviour); this is the audio half. Everything here runs on the control
 * thread — the encoder allocates element lists, the synthesizer allocates an envelope, the
 * fade generator allocates a schedule — and the audio thread only ever reads the result.
 */
class StationAudio(val sampleRate: Int = 48_000) {

    private val synthesizer = CwSynthesizer(sampleRate)

    /**
     * A roster station's transmission, with its QSB/flutter schedule.
     *
     * @param id the mixer slot identifier, used to stop this station later.
     * @param rootSeed the session's seed; the per-station fade stream is derived from it
     *   and the callsign, so two stations never fade together.
     */
    fun voice(station: RosterStation, id: Int, text: String, rootSeed: Long): CwVoice {
        val envelope = envelope(text, station.speedWpm)
        val random = SeededRandom(FadeSchedule.seedFor(rootSeed, station.call))
        val targets = FadeSchedule.generate(
            bandwidthHz = station.fade.bandwidthHz,
            sampleRate = sampleRate,
            samples = envelope.size,
        ) { random.nextDouble() }

        return CwVoice(
            id = id,
            pitchHz = station.pitchHz.toDouble(),
            envelope = envelope,
            level = station.level,
            gainTargets = targets,
            gainStepSamples = if (targets == null) 0 else FadeSchedule.stepSamples(sampleRate),
        )
    }

    /**
     * A CW signal that is not a roster station — the off-frequency QRM operator.
     *
     * A steady carrier by construction: upstream's `TQrmStation` has no `TQsb`, so another
     * operator's signal does not fade like the station being worked.
     */
    fun signal(id: Int, text: String, pitchHz: Int, speedWpm: Int, level: Double): CwVoice =
        CwVoice(
            id = id,
            pitchHz = pitchHz.toDouble(),
            envelope = envelope(text, speedWpm),
            level = level,
        )

    /** The keyed envelope of [text] at [wpm] — the one place the encoder is called. */
    private fun envelope(text: String, wpm: Int): FloatArray =
        synthesizer.envelope(MorseEncoder.encode(text, MorseTiming(effectiveWpm = wpm)))
}
