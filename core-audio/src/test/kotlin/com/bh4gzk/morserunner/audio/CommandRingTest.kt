package com.bh4gzk.morserunner.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lock-free hand-off between the control thread (which decides who is
 * transmitting) and the audio thread (which renders).
 *
 * Single-producer / single-consumer (SPSC): one thread publishes, one drains. No
 * locks, and — the point of the exercise — no allocation per operation either, because
 * the slots are preallocated and reused (spec §7, "no allocation on the audio path").
 */
class CommandRingTest {

    @Test
    fun `commands come out in publish order`() {
        val ring = CommandRing(capacity = 8)
        repeat(5) { index ->
            assertTrue(ring.tryPublish { command -> publishStart(command, index) })
        }
        val seen = ArrayList<Int>()
        val drained = ring.drain { command -> seen += command.id }
        assertEquals(5, drained)
        assertEquals(listOf(0, 1, 2, 3, 4), seen)
    }

    @Test
    fun `an empty ring drains nothing`() {
        val ring = CommandRing(capacity = 4)
        assertEquals(0, ring.drain { error("drained from an empty ring") })
    }

    @Test
    fun `a full ring rejects further publishes instead of blocking`() {
        // One slot is deliberately kept free so that "full" and "empty" are
        // distinguishable from the two indices alone — the classic SPSC trade-off.
        val ring = CommandRing(capacity = 4)
        repeat(3) { assertTrue(ring.tryPublish { command -> publishStart(command, it) }) }
        assertFalse(ring.tryPublish { command -> publishStart(command, 99) }, "ring accepted a 4th command")

        assertEquals(3, ring.drain { })
        assertTrue(ring.tryPublish { command -> publishStart(command, 7) }, "ring did not free a slot")
    }

    @Test
    fun `the ring wraps around without losing order`() {
        val ring = CommandRing(capacity = 4)
        repeat(3) { round ->
            repeat(3) { offset -> ring.tryPublish { command -> publishStart(command, round * 10 + offset) } }
            val seen = ArrayList<Int>()
            ring.drain { command -> seen += command.id }
            assertEquals(listOf(round * 10, round * 10 + 1, round * 10 + 2), seen)
        }
    }

    @Test
    fun `every command kind survives the round trip`() {
        val ring = CommandRing(capacity = 8)
        val voice = CwVoice(id = 3, pitchHz = 700.0, envelope = floatArrayOf(1f), level = 0.5)
        ring.tryPublish { command ->
            command.kind = MixerCommand.Kind.START
            command.id = 3
            command.voice = voice
        }
        ring.tryPublish { command ->
            command.kind = MixerCommand.Kind.STOP
            command.id = 4
            command.voice = null
        }
        ring.tryPublish { command ->
            command.kind = MixerCommand.Kind.STOP_ALL
            command.id = -1
            command.voice = null
        }

        val kinds = ArrayList<MixerCommand.Kind>()
        var carriedVoice: CwVoice? = null
        ring.drain { command ->
            kinds += command.kind
            if (command.kind == MixerCommand.Kind.START) carriedVoice = command.voice
        }
        assertEquals(MixerCommand.Kind.values().toList(), kinds)
        assertEquals(voice, carriedVoice, "the voice handed to the ring was not the one that came out")
    }

    @Test
    fun `one producer and one consumer lose and duplicate nothing`() {
        val ring = CommandRing(capacity = 64)
        val total = 200_000
        val received = ArrayList<Int>(total)
        val producerDone = java.util.concurrent.atomic.AtomicBoolean(false)

        val producer = Thread {
            var next = 0
            while (next < total) {
                if (ring.tryPublish { command -> publishStart(command, next) }) next++
            }
            producerDone.set(true)
        }
        val consumer = Thread {
            while (true) {
                if (ring.drain { command -> received += command.id } > 0) continue
                // Only this thread touches `received`, so no lock is needed; the second
                // drain closes the window between "ring looked empty" and "producer is done".
                if (producerDone.get() && ring.drain { command -> received += command.id } == 0) break
                Thread.onSpinWait()
            }
        }

        producer.start()
        consumer.start()
        producer.join(30_000)
        consumer.join(30_000)

        assertEquals(total, received.size, "lost or duplicated commands")
        assertTrue(received.indices.all { received[it] == it }, "commands arrived out of order")
    }

    private fun publishStart(command: MixerCommand, id: Int) {
        command.kind = MixerCommand.Kind.START
        command.id = id
        command.voice = null
    }
}
