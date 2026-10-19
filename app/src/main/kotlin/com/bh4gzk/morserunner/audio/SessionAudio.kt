package com.bh4gzk.morserunner.audio

import com.bh4gzk.morserunner.contest.BehaviourProfile
import com.bh4gzk.morserunner.contest.FadeProfile
import com.bh4gzk.morserunner.contest.GeneratedExchange
import com.bh4gzk.morserunner.contest.RosterStation
import com.bh4gzk.morserunner.session.AudioCue
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays the [AudioCue]s a [com.bh4gzk.morserunner.session.SessionController] emits.
 *
 * The controller decides *what* is transmitted and *when it is due*; this turns a cue into a
 * [CwVoice] and hands it to the one [CwAudioEngine]. Building an envelope allocates (a 6 s
 * message is a few hundred thousand floats), so the work runs on one dedicated thread: the
 * screen keeps its frames and the cues keep their order.
 *
 * My own sending is monitored through the same synthesizer (spec §7) at a fixed pitch; a
 * station is played at its own speed, pitch, level and fade.
 */
class SessionAudio(
    private val engine: CwAudioEngine,
    private val stationAudio: StationAudio,
    private val myCall: String,
    private val mySpeedWpm: Int,
    private val myPitchHz: Int = 600,
) : Closeable {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "session-audio").apply { isDaemon = true }
    }

    private val voiceIds = AtomicInteger(0)

    @Volatile
    private var closed = false

    /** My station as the audio layer sees it: no fade, full level, my own speed and pitch. */
    private val myStation = RosterStation(
        call = myCall,
        speedWpm = mySpeedWpm,
        pitchHz = myPitchHz,
        level = 1.0,
        fade = FadeProfile(bandwidthHz = 0.1, flutter = false),
        behaviour = BehaviourProfile.NORMAL,
        dice = emptyMap(),
        exchange = GeneratedExchange("", "", emptyMap()),
    )

    fun start() = engine.start()

    /** Queues [cue] for playback off the UI thread, preserving the order cues were drained in. */
    fun play(cue: AudioCue) {
        if (closed || cue.text.isBlank()) return
        worker.execute {
            val station = cue.station ?: myStation
            val id = voiceIds.getAndIncrement()
            engine.play(stationAudio.voice(station, id, cue.text, ROOT_SEED))
        }
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        engine.close()
    }

    private companion object {
        /** The fade stream's root; per-station variation comes from the callsign. */
        const val ROOT_SEED: Long = 20_261_019L
    }
}
