package com.bh4gzk.morserunner.audio

import com.bh4gzk.morserunner.contest.RosterStation
import com.bh4gzk.morserunner.contest.SeededRandom

/**
 * Turns a roster entry into something the mixer can play: the keyed signal at the
 * station's own speed and pitch, with its fade schedule precomputed.
 *
 * The station model lives in `core-contest` (`RosterStation`: call, speed, pitch, level,
 * fade parameters, behaviour); this is the audio half of the same ticket. Everything here
 * runs on the control thread — the encoder allocates element lists, the synthesizer
 * allocates an envelope, the fade generator allocates a schedule — and the audio thread
 * only ever reads the result.
 */
class StationAudio(val sampleRate: Int = 48_000) {

    private val synthesizer = CwSynthesizer(sampleRate)

    /**
     * @param id the mixer slot identifier, used to stop this station later.
     * @param rootSeed the session's seed; the per-station fade stream is derived from it
     *   and the callsign, so two stations never fade together.
     */
    fun voice(station: RosterStation, id: Int, text: String, rootSeed: Long): CwVoice {
        val timing = MorseTiming(effectiveWpm = station.speedWpm)
        val envelope = synthesizer.envelope(MorseEncoder.encode(text, timing))
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
}
