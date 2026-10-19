package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **T18's behaviour checks for ARRL Field Day** — the last per-contest ticket, and the only one
 * whose *history load* is an algorithm rather than a row predicate.
 *
 * Two things are pinned here.
 *
 * 1. **The document** (`contests/arrlfd.json`): `Ini.pas:118-125`'s two-string exchange
 *    (`etFdClass` + `etArrlSection`), `ArrlFd.pas:479-486`'s **two points and no multiplier**
 *    (`Points := 2`, `Result := '1'`), and `ArrlFd.pas:418-433`'s CQ wording (`CQ FD <my>`).
 * 2. **The two-pass club load** (`ArrlFd.pas:192-383`, ADR-0018): club stations are added as they
 *    are read and the highest transmitter count wins within a club; home/portable stations with a
 *    club name wait for a second pass that drops a group whose club already owns a station and
 *    otherwise keeps one row from the group's top transmitter-count segment; home/portable
 *    stations without a club name are kept one time in four.
 *
 * The 25 % cull is upstream's unseeded `Random`; the port's substitute is a **pure function of the
 * session seed and the row's callsign** (ADR-0018 §3), so the same seed replays the same pool.
 * That is why every fixture here takes a seed and the cull is proven both ways: a kept set and a
 * rejected one exist for some seed, never a silently empty pool.
 */
class ArrlFdTest {

    private val config = assertIs<LoadResult.Success>(ContestLibrary.load("arrlfd"), "arrlfd did not load").config

    /** The upstream file's own header (`FDGOTA.txt:1`); every fixture below reuses it. */
    private val header = "!!Order!!,Call,Exch1,Sect,UserText\n"

    private fun history(rows: String, seed: Long = 0L, myCall: String = "DL1AA"): HistoryLoad =
        CallHistoryFile.parse(header + rows, config.callHistory!!, myCall, CallFacts.Unavailable, seed)

    private fun loaded(rows: String, seed: Long = 0L, myCall: String = "DL1AA"): CallHistoryFile {
        val result = history(rows, seed, myCall)
        val rejected = result as? HistoryLoad.Rejected
        return assertIs<HistoryLoad.Loaded>(result, "fixture rejected: ${rejected?.reason}").history
    }

    // --- the document -------------------------------------------------------------

    @Test
    fun `the document is the ARRL Field Day the upstream defines`() {
        assertEquals("arrlfd", config.id)
        assertEquals("ARRL Field Day", config.name)
        assertEquals(Duration(defaultMinutes = 30, minMinutes = 1, maxMinutes = 60), config.duration)
        // `Ini.pas:118-125`: `ExchType1: etFdClass`, `ExchType2: etArrlSection`, `ExchDefault: '3A OR'`.
        assertEquals(setOf("class", "section"), config.exchange.fieldKeys)
        val sent = config.exchange.sent!!
        assertEquals(setOf("class"), sent.exch1.identifiers())
        assertEquals(setOf("section"), sent.exch2.identifiers())
        // `ArrlFd.pas:495-510`: the station's exchange is its class and its section.
        assertEquals(
            setOf("hisClass", "hisSection"),
            config.exchange.their.bindings.keys,
            "the station's class and section both come from the history",
        )
        assertEquals(setOf("hisClass"), config.exchange.their.exch1.identifiers())
        assertEquals(setOf("hisSection"), config.exchange.their.exch2.identifiers())
        assertTrue(config.exchange.their.dice.isEmpty(), "Field Day's exchange draws nothing")
        // `ArrlFd.pas:418-433`: the CQ wording is the only message override.
        assertEquals("CQ FD <my>", config.messages["CQ"])
        assertEquals("CQ CQ FD <my> <my> FD", config.messages["longCQ"])
        // The contest needs no DXCC lookup of my own call (`ArrlFd.pas` never asks for one).
        assertTrue(!config.capabilities.requiresMyDxcc)
    }

    @Test
    fun `a contact is worth two points and the multiplier is the constant one`() {
        // `ArrlFd.pas:479-486`: `Qso^.Points := 2; Result := '1'` — no multiplier at all.
        val points = assertIs<EvalResult.Success>(config.scoring.points.evaluate()).value
        assertEquals(2.0, assertIs<Value.Num>(points).value)
        assertEquals(1, config.scoring.multipliers.size)
        val key = assertIs<EvalResult.Success>(config.scoring.multipliers.single().key.evaluate()).value
        assertEquals("1", assertIs<Value.Str>(key).value, "the constant key, exactly like HST's")
    }

    @Test
    fun `the load is the two-pass club strategy, and it declares the columns it reads`() {
        val callHistory = config.callHistory!!
        assertEquals(HistoryLoadMode.TWO_PASS_CLUBS, callHistory.load)
        assertEquals(listOf("class", "club"), callHistory.load!!.requiredColumns)
        // The enum (the config's contract) and the strategy (the reader) must name the same
        // columns; two copies of a name are exactly how a loader and a reader drift apart.
        assertEquals(
            listOf(TwoPassClubLoad.CLASS_COLUMN, TwoPassClubLoad.CLUB_COLUMN),
            callHistory.load!!.requiredColumns,
        )
        assertTrue(callHistory.columns.map { it.name }.containsAll(callHistory.load!!.requiredColumns))
        assertTrue(callHistory.filter == null, "the two-pass load replaces the row predicate here")
        // The upstream file is header-driven (`ArrlFd.pas:222-232` reads no header, the file has one).
        assertEquals("!!Order!!", callHistory.header?.marker)
        assertEquals(3, callHistory.minFields, "a row without a club name still carries three cells")
    }

    // --- pass 1: club stations ----------------------------------------------------

    @Test
    fun `a club station enters the pool, and a better station from the same club replaces it`() {
        // `ArrlFd.pas:283-306`: `A`/`C`/`F` stations are added as they are read, and a later row
        // from a club already seen replaces the earlier one only when its transmitter count is
        // **strictly** greater.
        val history = loaded(
            """
            AA1AA,2A,OR,ClubX
            AA2AA,3A,OR,ClubX
            AA3AA,1A,OR,ClubX
            AA4AA,1A,OR,ClubY
            """.trimIndent(),
        )
        assertEquals(setOf("AA2AA", "AA4AA"), history.calls, "ClubX keeps its 3A, ClubY its only station")
        assertEquals("3A", history.recordFor("AA2AA")?.get("class"))
        assertEquals("ClubX", history.recordFor("AA2AA")?.get("club"))
    }

    @Test
    fun `a club station without a club name still enters the pool`() {
        // `ArrlFd.pas:283-306`: the `ClubNames` dictionary only tracks rows that name a club; an
        // anonymous club station is added and forgotten.
        val history = loaded(
            """
            AA1AA,2A,OR
            AA2AA,1A,OR,ClubY
            """.trimIndent(),
        )
        assertEquals(setOf("AA1AA", "AA2AA"), history.calls)
    }

    // --- pass 2: deferred home/portable stations ---------------------------------

    @Test
    fun `a home or portable station with a club name waits, and is dropped when the club is taken`() {
        // `ArrlFd.pas:308-350`: a deferred group whose club already owns a station from pass 1 is
        // discarded whole — both the home and the portable row here.
        val history = loaded(
            """
            AA1AA,2A,OR,ClubX
            BB1BB,1D,OR,ClubX
            BB2BB,1B,OR,ClubX
            """.trimIndent(),
        )
        assertEquals(setOf("AA1AA"), history.calls, "the club already has its station")
    }

    @Test
    fun `pass two keeps one station per free club, from the top transmitter-count segment`() {
        // `ArrlFd.pas:130-160 PickPendingCallIdx`: sort by transmitter count, take the segment of
        // the highest count, and pick uniformly inside it (the drawn index is the replay contract).
        // Both top rows end in `B`, so the keep draw cannot hide which one was picked — a portable
        // station is always kept (`ArrlFd.pas:344`).
        val rows = """
            BB1BB,1D,OR,ClubY
            BB2BB,2B,OR,ClubY
            BB3BB,2B,OR,ClubY
        """.trimIndent()
        val history = loaded(rows, seed = 7L)
        assertEquals(1, history.size, "one station per free club")
        assertTrue(
            history.calls.single() in setOf("BB2BB", "BB3BB"),
            "picked ${history.calls.single()}, below the top transmitter count",
        )
        assertEquals("2B", history.recordFor(history.calls.single())?.get("class"))
    }

    @Test
    fun `a free club whose top station is a home station is kept by the one-in-four draw`() {
        // `ArrlFd.pas:344`: `rec.StnClass.EndsWith('B') or (Random < 0.25)` — a `D` row that is
        // not portable depends on the draw, so over a range of seeds both outcomes must appear.
        val rows = """
            BB1BB,2D,OR,ClubY
            BB2BB,1D,OR,ClubY
        """.trimIndent()
        val outcomes = (0L..50L).map { seed ->
            when (val result = history(rows, seed)) {
                is HistoryLoad.Loaded -> result.history.calls.toSet()
                is HistoryLoad.Rejected -> emptySet()
            }
        }
        assertTrue(outcomes.any { it == setOf("BB1BB") }, "the top station is kept for some seed")
        assertTrue(outcomes.any { it.isEmpty() }, "and dropped for another")
        assertTrue(outcomes.all { it.isEmpty() || it == setOf("BB1BB") })
        // An empty pool is a rejection with a reason, never a loaded contest with no stations.
        val emptySeed = (0L..50L).first { history(rows, it) is HistoryLoad.Rejected }
        val rejected = assertIs<HistoryLoad.Rejected>(history(rows, emptySeed))
        assertTrue(rejected.reason.contains("no usable records"), rejected.reason)
    }

    // --- pass 1: the one-in-four cull ---------------------------------------------

    @Test
    fun `a home or portable station without a club name is kept one time in four`() {
        // `ArrlFd.pas:262-276`: `if Random < 0.25 then FdCallList.Add(rec)`.
        val rows = (0 until 400).joinToString("\n") { i ->
            val first = 'A' + i / 26
            val second = 'A' + i % 26
            "K${i % 10}$first$second,1D,OR"
        }
        val history = loaded(rows, seed = 7L)
        assertEquals(400, rows.lines().size)
        assertTrue(history.size in 60..140, "expected about a quarter of 400, got ${history.size}")
        assertTrue(history.calls.all { it.startsWith("K") }, "a kept call is not a file call")
        // The same seed is the same pool, call for call (ADR-0007's replay contract).
        assertEquals(history.calls.toList(), loaded(rows, seed = 7L).calls.toList())
        // A different seed decides differently: 400 independent one-in-four draws cannot agree.
        assertTrue(
            loaded(rows, seed = 8L).calls.toSet() != history.calls.toSet(),
            "two seeds produced the same cull",
        )
    }

    // --- the pool itself -----------------------------------------------------------

    @Test
    fun `the pool is sorted by call, the way upstream sorts its list`() {
        // `ArrlFd.pas:349 FdCallList.Sort` → `CompareStr(left.Call, right.Call)` (`:625-628`).
        val history = loaded(
            """
            ZZ9ZZ,2A,OR,ClubZ
            AA1AA,2A,OR,ClubA
            MM5MM,2A,OR,ClubM
            """.trimIndent(),
        )
        assertEquals(listOf("AA1AA", "MM5MM", "ZZ9ZZ"), history.calls.toList())
    }

    @Test
    fun `the operator's call does not change the pool`() {
        // Field Day's load never reads the operator: unlike ARRL DX, no side exists to be on.
        val rows = """
            AA1AA,2A,OR,ClubX
            BB1BB,1D,OR,ClubX
            CC1CC,1D,OR
        """.trimIndent()
        assertEquals(loaded(rows, seed = 3L, myCall = "DL1AA").calls, loaded(rows, seed = 3L, myCall = "W1AW").calls)
    }

    @Test
    fun `a two-cell row is not a station`() {
        // `ArrlFd.pas:222`: `if tl.Count > 2` — a row without a section never reaches the pool.
        val history = loaded(
            """
            AA1AA,2A
            AA2AA,2A,OR,ClubX
            """.trimIndent(),
        )
        assertEquals(setOf("AA2AA"), history.calls)
        assertNotNull(history.recordFor("AA2AA"))
    }
}
