package com.bh4gzk.morserunner.contest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The determinism contract (ADR-0004 decision 3): the same seed produces the same
 * stream, and `their.dice` are drawn in declaration order.
 *
 * The SplitMix64 reference outputs below pin the algorithm itself. If a refactor
 * changes them, every recorded session becomes unreplayable — that is a breaking
 * change, not a tuning tweak.
 */
class DiceTest {

    @Test
    fun `SplitMix64 matches its reference outputs`() {
        val random = SeededRandom(0)
        // 0xE220A8397B1DCDAF, 0x6E789E6AA1B965F4, 0x06C45D188009454F as signed longs:
        // Kotlin has no unsigned hex literal above Long.MAX_VALUE.
        assertEquals(-2152535657050944081L, random.nextLong())
        assertEquals(7960286522194355700L, random.nextLong())
        assertEquals(487617019471545679L, random.nextLong())
    }

    @Test
    fun `nextDouble is the committed 53-bit fraction`() {
        val random = SeededRandom(0)
        assertEquals(0.8833108082136426, random.nextDouble())
        assertEquals(0.43152799704850997, random.nextDouble())
        assertEquals(0.026433771592597743, random.nextDouble())
    }

    @Test
    fun `the same seed replays the same stream`() {
        val first = SeededRandom(42)
        val second = SeededRandom(42)
        repeat(100) { assertEquals(first.nextLong(), second.nextLong()) }
    }

    @Test
    fun `a different seed produces a different stream`() {
        assertNotEquals(SeededRandom(42).nextLong(), SeededRandom(43).nextLong())
    }

    @Test
    fun `draws stay inside the half-open unit interval`() {
        val random = SeededRandom(7)
        repeat(10_000) {
            val draw = random.nextDouble()
            assertTrue(draw >= 0.0 && draw < 1.0, "draw outside [0,1): $draw")
        }
    }

    @Test
    fun `nextInt covers the range and rejects an empty one`() {
        val random = SeededRandom(0)
        val seen = HashSet<Int>()
        repeat(1_000) { seen += random.nextInt(0, 10) }
        assertEquals((0..9).toSet(), seen)
        assertFailsWith<IllegalArgumentException> { SeededRandom(0).nextInt(3, 3) }
    }

    @Test
    fun `dice are drawn in declaration order and logged`() {
        val names = listOf("precRoll", "checkRoll", "sectRoll")
        val dice = Dice.draw(names, SeededRandom(0))

        // Exactly the first three nextDouble draws of a fresh stream, in order.
        val reference = SeededRandom(0)
        val expected = names.map { reference.nextDouble() }

        assertEquals(names, dice.values.keys.toList(), "declaration order is the replay contract")
        assertEquals(expected, dice.sequence)
        assertEquals(expected[0], dice.value("precRoll"))
    }

    @Test
    fun `drawing the same dice twice from the same seed is identical`() {
        val names = listOf("a", "b", "c", "d")
        val first = Dice.draw(names, SeededRandom(99))
        val second = Dice.draw(names, SeededRandom(99))
        assertEquals(first.values, second.values)
        assertEquals(first.sequence, second.sequence)
    }

    @Test
    fun `a station's dice draw one value per declared name`() {
        val random = SeededRandom(1)
        val dice = Dice.draw(listOf("only"), random)
        assertEquals(1, dice.values.size)
        assertEquals(1, dice.sequence.size)
        // The value consumed by Dice is exactly the stream's first draw — the next
        // draw on the same stream is the second, which the dice did not take.
        val reference = SeededRandom(1)
        reference.nextDouble()
        assertEquals(reference.nextDouble(), random.nextDouble())
    }
}
