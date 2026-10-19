package com.bh4gzk.morserunner.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The lock-free block mixer (spec §7): one output stream, up to eight stations summed.
 *
 * Model, transcribed from upstream `Contest.pas:721-731` and `DxStn.pas:291-295`:
 * every transmitting station contributes `envelope × amplitude × fade` at its own pitch
 * offset, and the contributions are **added**, sample by sample. Upstream does that in
 * complex baseband and modulates once at the end; because this app has no bandpass
 * filter to feed, adding the carriers directly is the same arithmetic with one fewer
 * stage, and it is what makes the mixer testable without a demodulator.
 *
 * Threading contract:
 *  - **control thread(s)**: [start], [stop], [stopAll] publish into a [CommandRing] and
 *    return immediately. They never touch renderer state.
 *  - **one audio thread**: [render] drains the ring, sums the voices and writes PCM.
 *
 * Allocation contract: [render] allocates nothing. The scratch state lives in the
 * [VoiceRenderer]s and the ring slots, both created with the mixer; every fade schedule
 * and envelope is prepared by the caller. `CwMixerTest` measures this rather than
 * trusting it.
 *
 * @param masterGain headroom applied to the sum before clamping. The default `1/8` is
 *   not a taste choice: the roster's contract is at most eight stations at full level,
 *   so `Σ level ≤ 8` and a master gain of `1/8` makes digital clipping unreachable. The
 *   user-facing volume question belongs to T9's settings, upstream answers it with an
 *   AGC (`Contest.pas:139-144`).
 */
class CwMixer(
    val sampleRate: Int = 48_000,
    val maxVoices: Int = 8,
    val masterGain: Double = 1.0 / 8.0,
) {

    init {
        require(sampleRate >= 8_000) { "sample rate too low: $sampleRate" }
        require(maxVoices in 1..64) { "maxVoices out of range: $maxVoices" }
        require(masterGain > 0.0) { "masterGain must be positive: $masterGain" }
    }

    private val voices = Array(maxVoices) { VoiceRenderer() }

    /**
     * Big enough that a control thread cannot saturate it by scheduling faster than the
     * audio thread drains (~10 ms per block); if it ever does, [start] says so instead
     * of blocking the UI.
     */
    private val commands = CommandRing(capacity = 4 * maxVoices + 8)

    /** Held in a field so draining does not allocate a capturing lambda per block. */
    private val commandConsumer: (MixerCommand) -> Unit = { command -> handle(command) }

    /** Voices that had to be dropped because every slot was taken. */
    var droppedVoices: Int = 0
        private set

    /** Voices currently rendering; for the UI and for tests. */
    val activeVoices: Int
        get() {
            var count = 0
            for (voice in voices) if (voice.active) count++
            return count
        }

    /**
     * Queues a station to start.
     *
     * @return `false` only when the command ring itself is full — never because the
     *   mixer is out of slots. A station with no free slot is dropped on the audio
     *   thread and counted in [droppedVoices]; the roster (not the mixer) is responsible
     *   for keeping the count within [maxVoices].
     */
    fun start(voice: CwVoice): Boolean = commands.tryPublish { command ->
        command.kind = MixerCommand.Kind.START
        command.id = voice.id
        command.voice = voice
    }

    /** Queues a stop for the station with [id]; `false` when the ring is full. */
    fun stop(id: Int): Boolean = commands.tryPublish { command ->
        command.kind = MixerCommand.Kind.STOP
        command.id = id
        command.voice = null
    }

    /** Queues a stop for every station. */
    fun stopAll(): Boolean = commands.tryPublish { command ->
        command.kind = MixerCommand.Kind.STOP_ALL
        command.id = -1
        command.voice = null
    }

    /**
     * Renders the next [frames] samples of the mix into `out[0 until frames]`.
     *
     * Mono, `-1..1`, `masterGain` applied and clamped to full scale. Safe to call with a
     * different block size every time: the output depends only on the total number of
     * samples rendered, never on how they were cut into blocks.
     */
    fun render(out: FloatArray, frames: Int) {
        require(frames in 0..out.size) { "frames out of range: $frames / ${out.size}" }
        commands.drain(commandConsumer)
        java.util.Arrays.fill(out, 0, frames, 0f)
        for (voice in voices) {
            if (voice.active) renderVoice(voice, out, frames)
        }

        val gain = masterGain.toFloat()
        for (i in 0 until frames) {
            val value = out[i] * gain
            out[i] = if (value > 1f) 1f else if (value < -1f) -1f else value
        }

        // Release after the block, so a voice that ended mid-block still fills the rest
        // of its envelope before its slot is handed to the next station.
        for (voice in voices) {
            if (voice.active && voice.pos >= voice.envelope.size) voice.release()
        }
    }

    /** Renders [frames] samples and converts them to little-endian PCM16 without allocating. */
    fun renderPcm16(out: ShortArray, frames: Int, scratch: FloatArray) {
        require(frames <= scratch.size) { "scratch too small: ${scratch.size} < $frames" }
        require(frames <= out.size) { "output too small: ${out.size} < $frames" }
        render(scratch, frames)
        Pcm16.convert(scratch, frames, out)
    }

    private fun handle(command: MixerCommand) {
        when (command.kind) {
            MixerCommand.Kind.START -> {
                val voice = command.voice ?: return
                val slot = freeSlot()
                if (slot == null) {
                    droppedVoices++
                    return
                }
                slot.begin(voice, sampleRate)
            }

            MixerCommand.Kind.STOP -> {
                for (slot in voices) {
                    if (slot.active && slot.id == command.id) slot.release()
                }
            }

            MixerCommand.Kind.STOP_ALL -> for (slot in voices) if (slot.active) slot.release()
        }
    }

    private fun freeSlot(): VoiceRenderer? {
        for (slot in voices) if (!slot.active) return slot
        return null
    }

    /**
     * Adds one station to the mix.
     *
     * The oscillator phase is never wrapped: wrapping would make the output depend on the
     * block boundaries (a wrap can only happen at the end of a block), and a message is
     * short enough that double precision is unaffected — 60 s of 900 Hz is ~3.4e5 radians,
     * where a double still resolves 1e-11 radians.
     */
    private fun renderVoice(voice: VoiceRenderer, out: FloatArray, frames: Int) {
        val envelope = voice.envelope
        val available = envelope.size - voice.pos
        if (available <= 0) return
        val count = min(frames, available)

        val level = voice.level
        val phaseStep = voice.phaseStep
        val targets = voice.gainTargets
        val gainStep = voice.gainStepSamples
        val lastTarget = if (targets == null) 0 else targets.size - 1
        var phase = voice.phase
        val start = voice.pos

        for (i in 0 until count) {
            val sample = start + i
            var gain = 1.0
            if (targets != null) {
                val step = min(sample / gainStep, lastTarget)
                val next = min(step + 1, lastTarget)
                val fraction = (sample % gainStep).toDouble() / gainStep
                val from = targets[step].toDouble()
                gain = from + (targets[next] - from) * fraction
            }
            out[i] += (envelope[sample] * level * gain * sin(phase)).toFloat()
            phase += phaseStep
        }

        voice.phase = phase
        voice.pos = start + count
    }

    /**
     * Mutable per-slot render state. Exists so that rendering only reads: no allocation,
     * no autoboxing, no virtual dispatch beyond these final fields.
     */
    private class VoiceRenderer {

        var active: Boolean = false
        var id: Int = -1
        var envelope: FloatArray = EMPTY
        var gainTargets: FloatArray? = null
        var gainStepSamples: Int = 1
        var level: Double = 1.0
        var phaseStep: Double = 0.0
        var phase: Double = 0.0
        var pos: Int = 0

        fun begin(voice: CwVoice, sampleRate: Int) {
            id = voice.id
            envelope = voice.envelope
            gainTargets = voice.gainTargets
            gainStepSamples = if (voice.gainStepSamples > 0) voice.gainStepSamples else 1
            level = voice.level
            phaseStep = 2.0 * PI * voice.pitchHz / sampleRate
            phase = 0.0
            pos = 0
            active = envelope.isNotEmpty()
        }

        fun release() {
            active = false
            id = -1
            envelope = EMPTY
            gainTargets = null
            pos = 0
        }

        companion object {
            /** Never written, so sharing one instance is safe. */
            val EMPTY = FloatArray(0)
        }
    }
}

/** Mono float to little-endian 16-bit PCM, in place over a caller-owned buffer. */
object Pcm16 {

    /**
     * Converts `from[0 until frames]` into `into[0 until frames]`.
     *
     * Clamping happens here as well as in the mixer: the converter is the last thing
     * between the mix and the device, and a value outside `-1..1` must never wrap around
     * into a full-scale click.
     */
    fun convert(from: FloatArray, frames: Int, into: ShortArray) {
        require(frames <= from.size) { "input too small: ${from.size} < $frames" }
        require(frames <= into.size) { "output too small: ${into.size} < $frames" }
        for (i in 0 until frames) {
            val clamped = when {
                from[i] > 1f -> 1f
                from[i] < -1f -> -1f
                else -> from[i]
            }
            into[i] = (clamped * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    /** Absolute peak after conversion, for level telemetry; allocates nothing. */
    fun peakAbs(samples: ShortArray, frames: Int): Int {
        var peak = 0
        for (i in 0 until frames) {
            val value = abs(samples[i].toInt())
            if (value > peak) peak = value
        }
        return peak
    }
}
