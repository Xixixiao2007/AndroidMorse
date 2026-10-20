package com.bh4gzk.morserunner.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.abs
import com.bh4gzk.morserunner.MorseCore
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The contest core's audio output: one streaming [AudioTrack] fed by one dedicated thread.
 *
 * `MorseCore.block` is not a "give me some samples" helper — it is the whole game loop
 * (`docs/abi.md`): every call filters, modulates, runs the AGC, ticks the operator and every
 * station, and appends finished QSOs to the log. The core's own contract is therefore **one
 * thread only**, and that thread is the one [start] creates. Nothing else in the app calls it;
 * the run clock and the log are read back from the main thread instead.
 *
 * The device paces the loop: `AudioTrack.write` in blocking mode returns only when the buffer
 * has room, so there is no timer and no sleep on the normal path. The core hands back the
 * AGC/mixer output, i.e. numbers in about-16-bit units (peak ~3150 measured on the device), so
 * every sample is divided by [PCM16_SCALE] and clamped to [-1, 1] before the `PCM_FLOAT` device
 * sees it.
 *
 * If the device cannot be opened (the constructor fails), the simulation still runs — blocks are
 * pulled and the clock advances — only the sound is missing. A native simulation that died
 * silently because an audio device was busy would be much harder to debug than a silent one.
 */
class ContestAudio {

    /**
     * Native rate and block size, asked once. Lazy so constructing this object never loads the
     * library: the activity creates the controller before it has called `setDataDir`.
     */
    val sampleRate: Int by lazy { runCatching { MorseCore.rate() }.getOrDefault(DEFAULT_RATE) }
    val blockFrames: Int by lazy { runCatching { MorseCore.blockSize() }.getOrDefault(DEFAULT_BLOCK) }

    /** Samples pulled out of the core since the clock was last reset; the run clock is this / rate. */
    private val samples = AtomicLong(0L)

    /**
     * Incremented by every [start].  A worker compares its own value against this on each
     * iteration, so a worker that could not be joined (see [stop]) can never pull a block again
     * once a new run has started.
     */
    private val generation = AtomicLong(0L)

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var track: AudioTrack? = null

    /** Samples produced so far, across pauses, so the clock can be resumed where it stopped. */
    val samplesConsumed: Long get() = samples.get()

    val isRunning: Boolean get() = running

    /**
     * Starts pulling blocks from [handle] and playing them.
     *
     * [resetClock] is true for a fresh run (`Tst.BlockNumber := 0` in the original, Main.pas:2097)
     * and false when the activity comes back from the background and the same run continues.
     * Idempotent while running; a zero handle is the core's "no run" and is ignored.
     */
    fun start(handle: Long, resetClock: Boolean = false) {
        if (running || handle == 0L) return
        if (resetClock) samples.set(0L)
        running = true
        val mine = generation.incrementAndGet()
        val device = openTrack()
        track = device
        device?.let { runCatching { it.play() } }
        worker = thread(name = "contest-audio", isDaemon = true) {
            val raw = FloatArray(blockFrames)
            val out = FloatArray(blockFrames)
            // One debug line per second of audio: the run's PEAK level, so logcat can show that a
            // message actually went out (peak 0 while the operator sends = silent transmit path)
            // instead of leaving "is there any audio" to the ear of whoever is debugging.
            var peak = 0f
            var sinceLog = 0
            var starved = 0   // blocks the core had nothing for; a stream of them = choppy audio
            while (running && generation.get() == mine && !Thread.currentThread().isInterrupted) {
                // The only call site of MorseCore.block in the app — see the class comment.
                val count = runCatching { MorseCore.block(handle, raw) }.getOrDefault(0)
                if (count <= 0) {
                    // Nothing was ready (or the handle is gone). Never leave the device without
                    // data: write one block of silence — writeAll blocks while the device buffer
                    // is full, which paces this loop — and count the event.
                    starved++
                    java.util.Arrays.fill(out, 0f)
                    if (device != null) {
                        writeAll(device, out, blockFrames, mine)
                    } else {
                        pause(blockFrames * 1_000L / sampleRate)
                    }
                    continue
                }
                for (i in 0 until count) {
                    val v = raw[i]
                    if (abs(v) > peak) peak = abs(v)
                    out[i] = (v / PCM16_SCALE).coerceIn(-1f, 1f)
                }
                sinceLog += count
                if (sinceLog >= sampleRate) {
                    Log.d(TAG, "peak=${peak.toInt()} block=${count} samples=${samples.get()} starved=$starved")
                    peak = 0f
                    sinceLog = 0
                    starved = 0
                }
                if (device != null) {
                    writeAll(device, out, count, mine)
                } else {
                    // No device: pace the loop by wall clock so the simulation still advances
                    // at roughly the rate it would have played.
                    pause(count * 1_000L / sampleRate)
                }
                samples.addAndGet(count.toLong())
            }
        }
    }

    /**
     * Stops the loop and releases the device.
     *
     * Returns true only when the audio thread has actually exited. The caller must **not** free
     * the contest handle otherwise: freeing it while the thread could still be inside `mr_block`
     * is a use-after-free in native code, which Kotlin cannot catch.
     */
    fun stop(): Boolean {
        if (!running && worker == null) return true
        running = false
        val workerThread = worker
        workerThread?.interrupt()
        workerThread?.join(STOP_TIMEOUT_MS)
        val stopped = workerThread?.isAlive != true
        track?.let { device ->
            runCatching {
                device.pause()
                device.flush()
                device.release()
            }
        }
        track = null
        worker = null
        return stopped
    }

    private fun writeAll(device: AudioTrack, block: FloatArray, count: Int, mine: Long) {
        var offset = 0
        while (running && generation.get() == mine && offset < count) {
            val written = runCatching {
                device.write(block, offset, count - offset, AudioTrack.WRITE_BLOCKING)
            }.getOrDefault(-1)
            if (written <= 0) return
            offset += written
        }
    }

    /** ~4 blocks of headroom, and never less than the device's own minimum. */
    private fun openTrack(): AudioTrack? = runCatching {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val blockBytes = blockFrames * BLOCKS_BUFFERED * BYTES_PER_FLOAT
        val bufferBytes = if (minBytes > 0) maxOf(minBytes, blockBytes) else blockBytes
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.getOrNull()

    private fun pause(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val TAG = "ContestAudio"

        /** The float stream is in 16-bit units: peak ~3150 on the device, so /32768 is full scale. */
        const val PCM16_SCALE = 32_768f
        const val BLOCKS_BUFFERED = 4
        const val BYTES_PER_FLOAT = 4
        const val STOP_TIMEOUT_MS = 2_000L
        const val DEFAULT_RATE = 11_025
        const val DEFAULT_BLOCK = 512
    }
}
