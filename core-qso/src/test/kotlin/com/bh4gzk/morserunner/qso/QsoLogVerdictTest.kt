package com.bh4gzk.morserunner.qso

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The log's save-time contract: a contact that was worked is logged even when the operator
 * mis-copied it, and the verdict travels with the record (`Log.pas:998-1090 CheckErr`).
 */
class QsoLogVerdictTest {

    private fun contact(
        call: String = "DL1ABC",
        exch1: String = "599",
        exch2: String = "14",
        points: Int = 3,
        multipliers: Set<String> = setOf("ZN-14", "Germany"),
        verdict: QsoVerdict = QsoVerdict.None,
        worked: Boolean = true,
        elapsedMs: Long = 0,
        verified: Boolean = true,
        failure: String? = null,
    ) = QsoContact(
        call = call,
        exch1 = exch1,
        exch2 = exch2,
        score = ScoreResult(points, multipliers, failure),
        verdict = verdict,
        worked = worked,
        elapsedMs = elapsedMs,
        verified = verified,
    )

    @Test
    fun `a clean contact is accepted with its score`() {
        val log = QsoLog()

        val outcome = log.record(contact())

        assertTrue(outcome is LogOutcome.Accepted)
        val accepted = outcome as LogOutcome.Accepted
        assertEquals(3, accepted.record.points)
        assertEquals(setOf("ZN-14", "Germany"), accepted.record.multipliers)
        assertEquals(3, accepted.rawPoints)
        assertEquals(2, accepted.rawMultipliers)
        assertEquals(6, accepted.rawScore)
        assertTrue(accepted.record.errors.isEmpty())
    }

    @Test
    fun `a mis-copied contact is still logged, with its verdict`() {
        // Upstream keeps the contact and explains it in the Err column; it is not silently
        // dropped (ADR-0005 §8).
        val log = QsoLog()

        val outcome = log.record(contact(verdict = QsoVerdict(listOf(QsoError.Rst), listOf("579"))))

        assertTrue(outcome is LogOutcome.Accepted)
        val record = (outcome as LogOutcome.Accepted).record
        assertEquals(listOf(QsoError.Rst), record.errors)
        assertEquals(listOf("579"), record.corrections)
        assertEquals(1, log.size)
        assertEquals(3, log.rawPointsTotal)
    }

    @Test
    fun `a station that was never worked is NIL, not a contact`() {
        val log = QsoLog()

        assertEquals(LogOutcome.Nil, log.record(contact(worked = false)))
        assertEquals(0, log.size)
        assertEquals(0, log.rawPointsTotal)
    }

    @Test
    fun `a repeated call is a duplicate and changes nothing`() {
        val log = QsoLog()
        log.record(contact())

        assertEquals(LogOutcome.Duplicate, log.record(contact(call = " dl1abc ")))
        assertEquals(1, log.size)
        assertEquals(3, log.rawPointsTotal)
    }

    @Test
    fun `a scoring failure is recorded with the contact`() {
        val log = QsoLog()

        val outcome = log.record(contact(points = 0, multipliers = emptySet(), failure = "callCountry: no data"))

        assertTrue(outcome is LogOutcome.Accepted)
        val record = (outcome as LogOutcome.Accepted).record
        assertEquals("callCountry: no data", record.scoreFailure)
        assertEquals(0, log.rawScore)
    }

    @Test
    fun `an unverified contact counts raw but not verified`() {
        val log = QsoLog()

        log.record(contact(verified = false))

        assertEquals(1, log.size)
        assertEquals(3, log.rawPointsTotal)
        assertEquals(0, log.verifiedPointsTotal)
        assertEquals(0, log.verifiedScore)
    }

    @Test
    fun `the contact's elapsed time feeds the rate histogram`() {
        val log = QsoLog()

        log.record(contact(call = "A1", elapsedMs = 0))
        log.record(contact(call = "A2", elapsedMs = 6 * 60_000L))

        assertEquals(listOf(1, 1), log.rateHistogram())
    }

    @Test
    fun `the logged call is normalised once`() {
        val log = QsoLog()

        val outcome = log.record(contact(call = "  dl1abc "))

        assertTrue(outcome is LogOutcome.Accepted)
        assertEquals("DL1ABC", (outcome as LogOutcome.Accepted).record.call)
        assertEquals("DL1ABC", log.qsos.single().call)
    }

    @Test
    fun `the score is points times distinct multipliers over accepted contacts`() {
        val log = QsoLog()

        log.record(contact(call = "DL1ABC", multipliers = setOf("ZN-14", "Germany")))
        log.record(contact(call = "K1ABC", multipliers = setOf("ZN-5", "United States of America")))

        assertEquals(6, log.rawPointsTotal)
        assertEquals(4, log.rawMultiplierCount)
        assertEquals(24, log.rawScore)
    }
}
