package com.bh4gzk.morserunner.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * One station's contribution to the mix — everything the renderer needs, already
 * prepared off the audio thread.
 *
 * The [envelope] is the keyed signal from [CwSynthesizer.envelope] and the
 * [gainTargets] schedule is a fade precomputed by the caller (see
 * `core-station`'s `FadeSchedule`): both are plain arrays so that rendering touches
 * nothing but reads. That split is what lets the audio path allocate nothing.
 *
 * @param pitchHz audio frequency of this station (already offset from the receiver's
 *   centre frequency).
 * @param level linear amplitude `0..1`.
 * @param gainTargets amplitude targets, one per [gainStepSamples] samples, linearly
 *   interpolated; the last entry is held. `null` means a steady carrier.
 */
class CwVoice(
    val id: Int,
    val pitchHz: Double,
    val envelope: FloatArray,
    val level: Double,
    val gainTargets: FloatArray? = null,
    val gainStepSamples: Int = 0,
) {
    init {
        require(pitchHz in 50.0..8_000.0) { "pitch out of range: $pitchHz" }
        require(level in 0.0..1.0) { "level out of range: $level" }
        require(gainTargets == null || gainTargets.isNotEmpty()) { "empty fade schedule" }
        require(gainTargets == null || gainStepSamples > 0) { "a fade schedule needs a step size" }
    }
}

/**
 * One instruction from the control thread to the audio thread.
 *
 * A single mutable object allocated once per ring slot: the producer fills it, the
 * consumer reads it, and neither creates a new instance — that is the whole reason the
 * queue can be allocation-free on the consuming side.
 */
class MixerCommand internal constructor() {

    var kind: Kind = Kind.START
    var id: Int = -1
    var voice: CwVoice? = null

    enum class Kind { START, STOP, STOP_ALL }
}

/**
 * Single-producer / single-consumer ring of [MixerCommand]s.
 *
 * Lock-free in the Lamport sense: two `AtomicInteger` cursors with plain volatile
 * semantics, which is acquire/release on every platform this app targets (API 24 has no
 * `VarHandle`). One thread publishes, exactly one drains — the two roles are never
 * swapped and never shared, which is what makes the algorithm correct without a lock.
 *
 * One slot is kept free so that "full" and "empty" remain distinguishable from the two
 * cursors alone; [capacity] is therefore the number of *pending* commands, not slots.
 */
class CommandRing(val capacity: Int = 64) {

    init {
        require(capacity >= 2) { "capacity must be at least 2: $capacity" }
    }

    private val slots: Array<MixerCommand> = Array(capacity) { MixerCommand() }
    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    /** Commands published and not yet drained. Approximate when read across threads. */
    val pending: Int get() = writeIndex.get() - readIndex.get()

    /**
     * Fills the next free slot through [configure] and publishes it.
     *
     * @return `false` when the ring is full — the caller decides what to do, the ring
     *   never blocks and never grows.
     */
    fun tryPublish(configure: (MixerCommand) -> Unit): Boolean {
        val write = writeIndex.get()
        val read = readIndex.get()
        if (write - read >= capacity - 1) return false
        configure(slots[Math.floorMod(write, capacity)])
        // The slot is fully written before the cursor moves: a consumer that sees the
        // new index must see the command behind it.
        writeIndex.set(write + 1)
        return true
    }

    /**
     * Hands every pending command to [consume] in publish order and returns how many.
     *
     * The producer may publish while this runs; those commands are picked up by the next
     * call, which keeps the drain bounded and the audio block deterministic.
     */
    fun drain(consume: (MixerCommand) -> Unit): Int {
        var read = readIndex.get()
        val write = writeIndex.get()
        var count = 0
        while (read != write) {
            consume(slots[Math.floorMod(read, capacity)])
            read++
            count++
        }
        // Only the consumer writes this cursor, and only after it is done reading.
        readIndex.set(read)
        return count
    }
}
