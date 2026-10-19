package com.bh4gzk.morserunner.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.io.Closeable
import kotlin.concurrent.thread

/**
 * Streaming mono PCM16 output for CW playback.
 *
 * One [AudioTrack] at 48 kHz, written from a single daemon thread with blocking
 * writes, so the caller never blocks the UI thread. Low-latency performance mode
 * is requested where the platform supports it (API 26+); `minSdk` is 24, so the
 * call is guarded rather than branched away entirely.
 *
 * T5 replaces this with the lock-free block mixer once several stations have to
 * sound at the same time; for a single signal this is all the audio path needs.
 */
class CwAudioTrack(val sampleRate: Int = 48_000) : Closeable {

    private val minBufferBytes: Int = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).let { if (it > 0) it else sampleRate / 2 }   // half a second as a fallback

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

    private var writer: Thread? = null

    /** True while samples are still being handed to the device. */
    val isPlaying: Boolean get() = writer?.isAlive == true

    /**
     * Plays [pcm] to completion on a background thread. Any previous playback is
     * stopped first, so a second press does not overlap the first.
     */
    fun play(pcm: ShortArray) {
        stop()
        if (pcm.isEmpty()) return
        track.play()
        writer = thread(name = "cw-audio-writer", isDaemon = true) {
            var offset = 0
            try {
                while (offset < pcm.size && !Thread.currentThread().isInterrupted) {
                    val count = minOf(BLOCK_SAMPLES, pcm.size - offset)
                    val written = track.write(pcm, offset, count)
                    if (written <= 0) break           // device gone or released
                    offset += written
                }
            } finally {
                runCatching { track.stop() }
            }
        }
    }

    /** Stops playback immediately and discards buffered samples. */
    fun stop() {
        writer?.interrupt()
        writer = null
        runCatching {
            track.pause()
            track.flush()
        }
    }

    override fun close() {
        stop()
        runCatching { track.release() }
    }

    private companion object {
        /** Write granularity; small enough to stay responsive, large enough to batch. */
        const val BLOCK_SAMPLES = 1024
    }
}
