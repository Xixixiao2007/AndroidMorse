package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.CallPool
import com.bh4gzk.morserunner.data.MasterDta
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where a simulated station's callsign comes from (T7).
 *
 * Upstream draws a caller from the contest's **own call-history list** when it has one
 * (`TContest.PickStation` → `CqWwCallList`, `CqWW.pas:141,272`) and from `MASTER.DTA`
 * otherwise. Both are ordered call lists, so the roster takes a [CallPool] rather than one
 * concrete asset.
 *
 * Why this is not cosmetic: the packaged `MASTER.DTA` (46 039 unique calls) and the packaged
 * `CQWWCW.txt` (8 875 calls) overlap in only 4 584 calls, so a roster that draws from
 * `MASTER.DTA` and skips calls the history cannot describe throws after its ten attempts about
 * a third of the time. Drawing from the history file removes both the throw and the fabricated
 * zone the T5 stand-in had to invent.
 */
class CallPoolTest {

    private fun config(id: String): ContestConfig {
        val result = ContestLibrary.load(id)
        return assertIs<LoadResult.Success>(result, "$id did not load: $result").config
    }

    /** A real-layout MASTER.DTA holding exactly [calls]. */
    private fun masterDtaOf(vararg calls: String): MasterDta {
        val body = calls.joinToString(separator = "") { "$it\u0000" }.toByteArray(Charsets.US_ASCII)
        val size = MasterDta.INDEX_BYTES + body.size
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val index = IntArray(MasterDta.INDEX_ENTRIES)
        index[0] = MasterDta.INDEX_BYTES
        index[MasterDta.INDEX_ENTRIES - 1] = size
        index.forEach { buffer.putInt(it) }
        body.copyInto(bytes, MasterDta.INDEX_BYTES)
        return MasterDta.parse(bytes)
    }

    private fun history(cqww: ContestConfig, text: String): CallHistoryFile {
        val result = CallHistoryFile.parse(text, cqww.callHistory)
        return assertIs<HistoryLoad.Loaded>(result, "history was rejected: $result").history
    }

    @Test
    fun `a call history is itself a call pool, in file order`() {
        val cqww = config("cqww")
        val file = history(cqww, "DL1ABC,14,\nK1ABC,5,\nJA1ABC,25,\n")
        val pool: CallPool = file

        assertEquals(3, pool.size)
        assertEquals(listOf("DL1ABC", "K1ABC", "JA1ABC"), (0 until pool.size).map { pool.callAt(it) })
    }

    @Test
    fun `MASTER DTA is a call pool`() {
        val pool: CallPool = masterDtaOf("DL1ABC", "K1ABC", "JA1ABC")

        assertEquals(3, pool.size)
        // MasterDta sorts the pool (CallLst.pas:93-96), so ordinal order, not file order.
        assertEquals(listOf("DL1ABC", "JA1ABC", "K1ABC"), (0 until pool.size).map { pool.callAt(it) })
    }

    @Test
    fun `a roster filled from the history pool never fabricates an exchange`() {
        // Every call in the pool has a record by construction, so add() cannot exhaust its
        // attempts and every station's zone is the file's own value.
        val cqww = config("cqww")
        val file = history(cqww, "DL1ABC,14,\nK1ABC,5,\nJA1ABC,25,\n")
        val roster = StationRoster(
            config = cqww,
            pool = file,
            history = file,
            settings = RosterSettings(speedWpm = 25, maxStations = 3),
            seed = 7L,
        )
        repeat(3) { assertNotNull(roster.add(), "the roster could not fill a station") }

        val zones = roster.stations.map { it.exchange.exch2 }.toSet()
        assertEquals(setOf("14", "5", "25"), zones, "a station's zone did not come from the file")
        assertTrue(roster.stations.all { file.recordFor(it.call) != null })
    }

    @Test
    fun `the same seed replays the same calls from either pool`() {
        val cqww = config("cqww")
        val file = history(cqww, "DL1ABC,14,\nK1ABC,5,\nJA1ABC,25,\nVK2ABC,30,\n")
        fun run(pool: CallPool) = StationRoster(
            cqww,
            pool,
            file,
            RosterSettings(speedWpm = 25, maxStations = 2),
            seed = 4242L,
        ).let { roster -> repeat(2) { roster.add() }; roster.stations.map { it.call } }

        assertEquals(run(file), run(file))
    }
}
