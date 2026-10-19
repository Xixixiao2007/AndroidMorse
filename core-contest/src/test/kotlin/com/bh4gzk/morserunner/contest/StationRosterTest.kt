package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.MasterDta
import com.bh4gzk.morserunner.expr.Value


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The roster (spec §6, T5): 0–8 stations, each with call, speed, pitch, level,
 * fade profile and behaviour profile, plus its generated exchange.
 *
 * The pool and the call-history values are **test fixtures** (a synthetic
 * `MASTER.DTA` written with the real layout, and a two-entry history map); the
 * shipped configs are real, because those are what the engine will run.
 */
class StationRosterTest {

    // --- fixtures --------------------------------------------------------------

    private fun config(id: String): ContestConfig {
        val result = ContestLibrary.load(id)
        assertIs<LoadResult.Success>(result, "$id did not load")
        return result.config
    }

    private fun poolOf(vararg calls: String): MasterDta = masterDtaOf(*calls)

    private val pool = poolOf(
        "DL1ABC", "F5ABC", "JA1XYZ", "K5ZD", "KH6XX", "KL7AA", "VE3ABC", "W1AW",
        "G4ABC", "I2XYZ", "OH2AA", "PY1ABC", "S51AA", "UA3ABC", "VK2AA", "ZS6AA",
        "9A1AA", "BY1AA", "CE3AA", "DU1AA", "EA3AA", "HL1AA", "LU1AA", "OA4AA",
    )

    private fun cqwwHistory(): HistoryLookup = HistoryLookup { call -> mapOf("zone" to "14").takeIf { call.isNotEmpty() } }

    private fun ssHistory(): HistoryLookup = HistoryLookup { call ->
        mapOf("check" to "72", "section" to "OR").takeIf { call.isNotEmpty() }
    }

    /** Every call in the history → the roster can always generate. */
    private fun historyForAll(calls: List<String>, values: Map<String, String>): HistoryLookup =
        HistoryLookup { call -> values.takeIf { call in calls } }

    // --- determinism -----------------------------------------------------------

    @Test
    fun `the same seed reproduces the roster byte for byte`() {
        val first = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30), seed = 42)
        val second = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30), seed = 42)
        repeat(8) {
            assertNotNull(first.add())
            assertNotNull(second.add())
        }
        assertEquals(first.stations, second.stations)
        assertEquals(first.stations.toString(), second.stations.toString(), "not byte-for-byte identical")
    }

    @Test
    fun `a different seed produces a different roster`() {
        val history = ssHistory()
        val first = StationRoster(config("arrlss"), pool, history, RosterSettings(speedWpm = 30), seed = 1)
        val second = StationRoster(config("arrlss"), pool, history, RosterSettings(speedWpm = 30), seed = 2)
        repeat(8) {
            assertNotNull(first.add())
            assertNotNull(second.add())
        }
        assertNotEquals(first.stations, second.stations)
    }

    // --- the 0–8 cap -------------------------------------------------------------

    @Test
    fun `a roster holds at most eight concurrent stations`() {
        val roster = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30), seed = 3)
        repeat(StationRoster.MAX_STATIONS) { assertNotNull(roster.add(), "station $it") }
        assertEquals(8, roster.size)
        assertTrue(roster.isFull)
        assertNull(roster.add(), "the ninth station must be refused")
        assertEquals(8, roster.size)
    }

    @Test
    fun `zero stations is a legal roster`() {
        val roster = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30, maxStations = 0), seed = 3)
        assertNull(roster.add())
        assertTrue(roster.stations.isEmpty())
    }

    @Test
    fun `a configured limit below eight is honoured`() {
        val roster = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30, maxStations = 3), seed = 3)
        repeat(3) { assertNotNull(roster.add()) }
        assertNull(roster.add())
    }

    @Test
    fun `remove and clear take stations off the air`() {
        val roster = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30), seed = 4)
        val first = assertNotNull(roster.add())
        assertNotNull(roster.add())
        assertTrue(roster.remove(first.call))
        assertEquals(1, roster.size)
        assertTrue(!roster.remove("NOPE"))
        roster.clear()
        assertEquals(0, roster.size)
    }

    // --- the audible properties (spec §6) ----------------------------------------

    @Test
    fun `every station carries the properties spec 6 asks for`() {
        val roster = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30), seed = 5)
        repeat(8) { assertNotNull(roster.add()) }

        val declaredDice = config("arrlss").exchange.their.dice
        for (station in roster.stations) {
            assertTrue(station.call.isNotEmpty())
            assertTrue(station.speedWpm in 25..40, "speed ${station.speedWpm} outside speedRange")
            assertTrue(station.pitchHz in 1..900, "pitch ${station.pitchHz} out of the operator's window")
            assertTrue(station.level in 0.2..1.0, "level ${station.level} outside [0.2, 1.0]")
            assertTrue(station.fade.bandwidthHz in 0.1..0.6, "QSB ${station.fade.bandwidthHz}")
            assertTrue(!station.fade.flutter)
            assertIs<BehaviourProfile>(station.behaviour)
            assertEquals(declaredDice.toSet(), station.dice.keys)
            val section = (station.exchange.parsed.getValue("theirSect") as Value.Str).value
            assertTrue(section in com.bh4gzk.morserunner.data.ArrlSections.all, "unknown section $section")
        }
    }

    @Test
    fun `station speed follows the config's speedRange evaluated with speed`() {
        val slow = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 10), seed = 6)
        repeat(8) { assertNotNull(slow.add()) }
        // speed - 5 .. speed + 10 = 5..20
        assertTrue(slow.stations.all { it.speedWpm in 5..20 }, slow.stations.map { it.speedWpm }.toString())

        // A very slow operator makes the lower bound non-positive; the keyer floor wins.
        val floor = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 5), seed = 6)
        repeat(8) { assertNotNull(floor.add()) }
        assertTrue(floor.stations.all { it.speedWpm in 1..15 }, floor.stations.map { it.speedWpm }.toString())
    }

    @Test
    fun `pitch spread follows the setting`() {
        val narrow = StationRoster(
            config("arrlss"), pool, ssHistory(),
            RosterSettings(speedWpm = 30, pitchHz = 600, pitchSpreadHz = 50), seed = 7,
        )
        repeat(8) { assertNotNull(narrow.add()) }
        assertTrue(narrow.stations.all { it.pitchHz in 550..650 })

        val wide = StationRoster(
            config("arrlss"), pool, ssHistory(),
            RosterSettings(speedWpm = 30, pitchHz = 600, pitchSpreadHz = 300), seed = 7,
        )
        repeat(8) { assertNotNull(wide.add()) }
        assertTrue(wide.stations.all { it.pitchHz in 300..900 })
        // The widest draws really do spread out; a fixed pitch would be a bug.
        assertTrue(wide.stations.map { it.pitchHz }.toSet().size > 1)
    }

    @Test
    fun `a LID is roughly one station in ten and can be switched off`() {
        var lids = 0
        var total = 0
        for (seed in 0L until 100L) {
            val roster = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30), seed = seed)
            repeat(8) { assertNotNull(roster.add()) }
            lids += roster.stations.count { it.behaviour == BehaviourProfile.LID }
            total += roster.stations.size
        }
        val rate = lids.toDouble() / total
        assertTrue(rate in 0.06..0.14, "LID rate $rate over $total stations (upstream: 10 %)")

        val clean = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30, lids = false), seed = 8)
        repeat(8) { assertNotNull(clean.add()) }
        assertTrue(clean.stations.none { it.behaviour == BehaviourProfile.LID })
    }

    @Test
    fun `flutter is a setting, and when enabled it replaces the QSB bandwidth`() {
        val still = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30, flutter = false), seed = 9)
        repeat(8) { assertNotNull(still.add()) }
        assertTrue(still.stations.none { it.fade.flutter })

        val fluttering = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30, flutter = true), seed = 9)
        repeat(8) { assertNotNull(fluttering.add()) }
        // 30 % of stations flutter with bandwidth 3..33; the rest keep QSB 0.1..0.6.
        for (station in fluttering.stations) {
            if (station.fade.flutter) {
                assertTrue(station.fade.bandwidthHz in 3.0..33.0, "flutter ${station.fade.bandwidthHz}")
            } else {
                assertTrue(station.fade.bandwidthHz in 0.1..0.6, "QSB ${station.fade.bandwidthHz}")
            }
        }
    }

    // --- calls, history, serial ---------------------------------------------------

    @Test
    fun `a roster never puts the same call on twice`() {
        val roster = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30), seed = 10)
        repeat(8) { assertNotNull(roster.add()) }
        assertEquals(8, roster.stations.map { it.call }.toSet().size)
        assertTrue(roster.stations.all { it.call in pool.calls })
    }

    @Test
    fun `a contest with history bindings requires a history lookup`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            StationRoster(config("arrlss"), pool, null, RosterSettings(speedWpm = 30), seed = 1)
        }
        assertTrue(failure.message!!.contains("HistoryLookup"), failure.message)
    }

    @Test
    fun `a call with no history record is skipped, then the roster gives up loudly`() {
        val roster = StationRoster(
            config("arrlss"),
            poolOf("W1AW"),
            HistoryLookup { null },
            RosterSettings(speedWpm = 30),
            seed = 1,
        )
        val failure = assertFailsWith<IllegalStateException> { roster.add() }
        assertTrue(failure.message!!.contains("W1AW"), failure.message)
        assertEquals(0, roster.size, "no half-made station may be left on the air")
    }

    @Test
    fun `the serial binding draws from the contest's serial field range`() {
        val roster = StationRoster(config("arrlss"), pool, ssHistory(), RosterSettings(speedWpm = 30), seed = 11)
        repeat(8) { assertNotNull(roster.add()) }
        // ARRL SS exchange 1 is "<serial> <precedence>".
        for (station in roster.stations) {
            val serial = station.exchange.exch1.substringBefore(' ').toInt()
            assertTrue(serial in 1..999, "serial $serial outside the declared 1..999")
        }
    }

    @Test
    fun `an empty call pool is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StationRoster(config("arrlss"), poolOf(), ssHistory(), RosterSettings(speedWpm = 30), seed = 1)
        }
    }

    // --- per-contest generation ----------------------------------------------------

    @Test
    fun `CQ WW stations send the history zone`() {
        val roster = StationRoster(config("cqww"), pool, cqwwHistory(), RosterSettings(speedWpm = 30), seed = 12)
        repeat(8) { assertNotNull(roster.add()) }
        assertTrue(roster.stations.all { it.exchange.exch1 == "599" })
        assertTrue(roster.stations.all { it.exchange.exch2 == "14" }, roster.stations.map { it.exchange.exch2 }.toString())
    }

    @Test
    fun `IARU HF stations send the history section and declare no dice`() {
        val history = historyForAll(listOf("W1AW"), mapOf("section" to "ARRL"))
        val roster = StationRoster(config("iaruhf"), poolOf("W1AW"), history, RosterSettings(speedWpm = 30), seed = 13)
        val station = assertNotNull(roster.add())
        assertEquals("599", station.exchange.exch1)
        assertEquals("ARRL", station.exchange.exch2)
        assertTrue(station.dice.isEmpty())
        assertTrue(station.exchange.parsed.isEmpty())
    }
}
