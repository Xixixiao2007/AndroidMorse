package com.bh4gzk.morserunner.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.io.Closeable
import kotlin.concurrent.thread

/**
 * The single audio output of the app (spec §7): one streaming [AudioTrack] fed by a
 * dedicated thread from the lock-free [CwMixer].
 *
 * Replaces T4's single-signal `CwAudioTrack` (removed in T5), which could only play one
 * pre-rendered buffer at a time and stopped whatever was playing first. Now every station
 * is a [CwVoice] queued into the mixer, so several transmit at once and a station can
 * start while another is still keyed.
 *
 * The device is the clock: `AudioTrack.write` in blocking mode paces the loop, so no
 * timer thread, no `sleep`, and no sample-rate drift. The mixer never blocks the caller —
 * [play] only publishes a command.
 *
 * Allocation: [start] allocates the two block buffers once; the loop that follows
 * allocates nothing (proven by `CwMixerTest`'s measurement of the render path).
 */
class CwAudioEngine(
    val sampleRate: Int = 48_000,
    val maxVoices: Int = 8,
    private val blockFrames: Int = 1_024,
    /** The band's background noise (spec §7's QRN); `null` is a clean generator. */
    bed: NoiseBed? = null,
) : Closeable {

    private val mixer = CwMixer(sampleRate = sampleRate, maxVoices = maxVoices, bed = bed)
    private val floatBlock = FloatArray(blockFrames)
    private val pcmBlock = ShortArray(blockFrames)

    private val minBufferBytes: Int = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).let { if (it > 0) it else sampleRate / 2 }

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(minBufferBytes * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
        }
        .build()

    @Volatile
    private var running = false

    @Volatile
    private var thread: Thread? = null

    /** Stations currently transmitting, for the UI. */
    val activeVoices: Int get() = mixer.activeVoices

    /** Stations that found no free slot — should stay 0 while the roster respects §6. */
    val droppedVoices: Int get() = mixer.droppedVoices

    /** Absolute peak of the block last handed to the device, for a level meter. */
    val lastBlockPeak: Int get() = Pcm16.peakAbs(pcmBlock, blockFrames)

    /** Starts the render loop. Idempotent. */
    fun start() {
        if (running) return
        running = true
        track.play()
        thread = thread(name = "cw-audio-mixer", isDaemon = true) {
            while (running && !Thread.currentThread().isInterrupted) {
                mixer.renderPcm16(pcmBlock, blockFrames, floatBlock)
                val written = track.write(pcmBlock, 0, blockFrames)
                if (written <= 0) break
            }
        }
    }

    /** Queues one station. Returns `false` only when the command ring is saturated. */
    fun play(voice: CwVoice): Boolean = mixer.start(voice)

    fun stop(id: Int): Boolean = mixer.stop(id)

    fun stopAll(): Boolean = mixer.stopAll()

    override fun close() {
        running = false
        mixer.stopAll()
        thread?.interrupt()
        thread = null
        runCatching {
            track.pause()
            track.flush()
        }
        runCatching { track.release() }
    }
}
