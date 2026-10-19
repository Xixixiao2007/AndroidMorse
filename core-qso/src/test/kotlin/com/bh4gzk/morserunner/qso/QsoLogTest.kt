package com.bh4gzk.morserunner.qso

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QsoLogTest {

    private fun qso(
        call: String,
        points: Int = 3,
        multipliers: Set<String> = setOf("ZN-3"),
        elapsedMs: Long = 0,
        verified: Boolean = true,
    ) = QsoRecord(
        call = call,
        exch1 = "599",
        exch2 = "3",
        points = points,
        multipliers = multipliers,
        elapsedMs = elapsedMs,
        verified = verified,
    )

    @Test
    fun `score is points times distinct multipliers`() {
        val log = QsoLog()
        log.record(qso("W1AW", points = 3, multipliers = setOf("ZN-5", "United States of America")))
        log.record(qso("DL1ABC", points = 3, multipliers = setOf("ZN-14", "Germany")))

        assertEquals(2, log.size)
        assertEquals(6, log.rawPointsTotal)
        assertEquals(4, log.rawMultiplierCount)      // four distinct keys
        assertEquals(24, log.rawScore)               // 6 * 4
    }

    @Test
    fun `a repeated multiplier key only counts once`() {
        val log = QsoLog()
        log.record(qso("W1AW", multipliers = setOf("ZN-5", "USA")))
        log.record(qso("K7S", multipliers = setOf("ZN-3", "USA")))

        assertEquals(3, log.rawMultiplierCount)
        assertEquals(2 * 3 * 3, log.rawScore)        // 6 points * 3 mults
    }

    @Test
    fun `a duplicate call is rejected and leaves the totals alone`() {
        val log = QsoLog()
        log.record(qso("W1AW"))

        val outcome = log.record(qso("  w1aw  ", points = 99))

        assertEquals(LogOutcome.Duplicate, outcome)
        assertEquals(1, log.size)
        assertEquals(3, log.rawPointsTotal)
    }

    @Test
    fun `an empty call is NIL, not a duplicate`() {
        val log = QsoLog()

        assertEquals(LogOutcome.Nil, log.record(qso("")))
        assertEquals(LogOutcome.Nil, log.record(qso("   ")))
        assertEquals(0, log.size)
        assertTrue(log.qsos.isEmpty())
    }

    @Test
    fun `unverified contacts count raw but not verified`() {
        val log = QsoLog()
        log.record(qso("W1AW", points = 3, multipliers = setOf("A")))
        log.record(qso("DL1ABC", points = 5, multipliers = setOf("B"), verified = false))

        assertEquals(8, log.rawPointsTotal)
        assertEquals(2, log.rawMultiplierCount)
        assertEquals(16, log.rawScore)

        assertEquals(3, log.verifiedPointsTotal)
        assertEquals(1, log.verifiedMultiplierCount)
        assertEquals(3, log.verifiedScore)
    }

    @Test
    fun `the rate histogram uses five minute windows`() {
        val log = QsoLog()
        log.record(qso("A1", elapsedMs = 0))
        log.record(qso("A2", elapsedMs = 4 * 60_000L))          // still window 0
        log.record(qso("A3", elapsedMs = 5 * 60_000L))          // window 1
        log.record(qso("A4", elapsedMs = 12 * 60_000L))         // window 2

        assertEquals(listOf(2, 1, 1), log.rateHistogram())
        assertEquals(QsoLog.WINDOW_MS, 300_000L)
    }

    @Test
    fun `an empty log has an empty histogram and no rate`() {
        val log = QsoLog()

        assertTrue(log.rateHistogram().isEmpty())
        assertEquals(0.0, log.ratePerHour(), 1e-9)
    }

    @Test
    fun `rate per hour scales with the elapsed span`() {
        val log = QsoLog()
        log.record(qso("A1", elapsedMs = 0))
        log.record(qso("A2", elapsedMs = 30 * 60_000L))
        log.record(qso("A3", elapsedMs = 60 * 60_000L))

        // 3 contacts over one hour.
        assertEquals(3.0, log.ratePerHour(), 1e-9)
    }

    @Test
    fun `the log records the normalised callsign`() {
        val log = QsoLog()
        log.record(qso("  w1aw "))

        assertEquals("W1AW", log.qsos.single().call)
    }

    @Test
    fun `clear empties everything`() {
        val log = QsoLog()
        log.record(qso("W1AW"))
        log.clear()

        assertEquals(0, log.size)
        assertEquals(0, log.rawScore)
        assertEquals(0, log.rawMultiplierCount)
        // A cleared log accepts the same call again.
        assertTrue(log.record(qso("W1AW")) is LogOutcome.Accepted)
    }
}
