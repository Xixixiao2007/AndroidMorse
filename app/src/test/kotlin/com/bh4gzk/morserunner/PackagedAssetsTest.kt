package com.bh4gzk.morserunner

import com.bh4gzk.morserunner.audio.CwMixer
import com.bh4gzk.morserunner.audio.StationAudio
import com.bh4gzk.morserunner.contest.CallHistoryFile
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLoad
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.contest.RosterSettings
import com.bh4gzk.morserunner.contest.StationRoster
import com.bh4gzk.morserunner.data.StationAssets
import com.bh4gzk.morserunner.data.StationData
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the *packaged* data assets are the real ones and that they drive the whole
 * chain the device uses: assets → roster (core-contest) → voices (core-audio) → a mix.
 *
 * These are the bytes that go into the APK, read from the source tree rather than from an
 * emulator — an assumption-style skip would prove nothing, so a missing asset fails here.
 */
class PackagedAssetsTest {

    /**
     * Gradle runs tests with the *module* directory as the working directory, so the
     * assets are found by walking up to the project root (see `.scratch/progress.md` §18).
     */
    private val assetsDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/assets") }
        .firstOrNull { it.isDirectory }
        ?: error("cannot locate app/src/main/assets above ${File("").absolutePath}")

    private val data: StationData by lazy {
        StationAssets.parse(
            masterBytes = File(assetsDir, StationAssets.MASTER).readBytes(),
            dxccText = File(assetsDir, StationAssets.DXCC).readText(),
        )
    }

    private val rate = 48_000

    @Test
    fun `the packaged MASTER DTA is the real callsign pool`() {
        assertEquals(
            1_239_476,
            File(assetsDir, StationAssets.MASTER).length().toInt(),
            "the packaged pool is not the shipped 1.2 MB MASTER.DTA",
        )
        assertTrue(data.pool.size in 40_000..60_000, "unexpected pool size ${data.pool.size}")
        assertTrue(data.pool.contains(data.pool.callAt(0)))
    }

    @Test
    fun `the packaged NAQP history drives a real roster`() {
        // T10: the first per-contest ticket. The file is upstream's `NAQPCW.txt`, copied
        // unmodified (MPL-2.0, spec §9), and this is the whole chain the device uses:
        // packaged bytes → the config's own callHistory spec → the roster's exchange.
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        val config = bundled.configs.firstOrNull { it.id == "naqp" }
        assertNotNull(config, "no bundled naqp config")

        val text = File(assetsDir, config.callHistory!!.asset).readText()
        val history = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(text, config.callHistory!!, "W1AW", data.callFacts),
        ).history
        assertTrue(history.size in 8_000..9_272, "unexpected NAQP history size ${history.size}")
        assertEquals("ERIC", history.recordFor("KP4NZ")?.get("name"))
        // A DX row keeps its empty state; the station sends its name only.
        assertEquals("", history.recordFor("6Y5PW")?.get("state"))

        // The NAQP multiplier expression asks `callContinent`/`callCountry` about the worked
        // call, and the engine fails closed when the DXCC table cannot answer. Every call the
        // shipped file can give us must resolve, or a contact would score 0 instead of 1.
        val unknown = history.calls.filter { data.callFacts.continent(it) == null }
        assertTrue(unknown.isEmpty(), "calls with no DXCC record: ${unknown.take(10)} (${unknown.size})")

        val roster = StationRoster(
            config = config,
            pool = history,
            history = history,
            settings = RosterSettings(speedWpm = 25),
            seed = 20_261_020L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { roster.add() }
        assertEquals(StationRoster.MAX_STATIONS, roster.size)
        roster.stations.forEach { station ->
            val record = assertNotNull(history.recordFor(station.call), station.call)
            assertEquals(record["name"].orEmpty().uppercase(), station.exchange.exch1, station.call)
            assertEquals(record["state"].orEmpty().uppercase(), station.exchange.exch2, station.call)
        }
    }

    @Test
    fun `the packaged K1USN SST history drives a real roster`() {
        // T11: the second per-contest ticket. The file is upstream's `K1USNSST.txt`, copied
        // unmodified (MPL-2.0, spec §9), and this is the whole chain the device uses:
        // packaged bytes → the config's own callHistory spec → the roster's exchange.
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        val config = bundled.configs.firstOrNull { it.id == "k1usnsst" }
        assertNotNull(config, "no bundled k1usnsst config")

        val file = File(assetsDir, config.callHistory!!.asset)
        assertEquals(251_728, file.length().toInt(), "the packaged history is not upstream's K1USNSST.txt")
        val history = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(file.readText(), config.callHistory!!, "W1AW", data.callFacts),
        ).history
        // 15 045 lines; the file's own rule (a name and a QTH are required) leaves 10 707; the
        // shared call grammar drops 2 (`F/OZ1CGQ`, `VYIJA`). ADR-0011 §8.
        assertEquals(10_705, history.size, "unexpected K1USN SST history size ${history.size}")
        assertEquals("MARK", history.recordFor("N5OT")?.get("name"))
        assertEquals("OK", history.recordFor("N5OT")?.get("qth"))
        assertEquals("DX", history.recordFor("KP4NZ")?.get("qth"))
        // The file writes some names in lower case; upstream upper-cases at load (`CWSST.pas:88`).
        assertEquals("Josip", history.recordFor("9A5AX")?.get("name"))

        // The DXCC table answers the multiplier's `callCountry(theirCall)` for every station that
        // sends the literal 'DX' — except the one measured below. The engine fails closed, so an
        // unresolved call scores 0 instead of upstream's 1 (ADR-0011 §3).
        val dxCalls = history.calls.filter { history.recordFor(it)?.get("qth") == "DX" }
        assertTrue(dxCalls.isNotEmpty(), "the shipped file has no DX record")
        val unresolved = dxCalls.filter { data.callFacts.country(it) == null }.toSet()
        assertEquals(setOf("LU1AW/D"), unresolved, "unexpected DX calls with no DXCC record")

        val roster = StationRoster(
            config = config,
            pool = history,
            history = history,
            settings = RosterSettings(speedWpm = 15),
            seed = 20_261_020L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { roster.add() }
        assertEquals(StationRoster.MAX_STATIONS, roster.size)
        roster.stations.forEach { station ->
            val record = assertNotNull(history.recordFor(station.call), station.call)
            assertEquals(record["name"].orEmpty().uppercase(), station.exchange.exch1, station.call)
            assertEquals(record["qth"].orEmpty().uppercase(), station.exchange.exch2, station.call)
        }
    }

    @Test
    fun `the packaged JARL ALL JA history drives a real roster`() {
        // T12: the third per-contest ticket. The file is upstream's `JARL_ALLJA.TXT`, copied
        // unmodified (MPL-2.0, spec §9), and this is the whole chain the device uses:
        // packaged bytes → the config's own callHistory spec → the roster's exchange.
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        val config = bundled.configs.firstOrNull { it.id == "allja" }
        assertNotNull(config, "no bundled allja config")

        val file = File(assetsDir, config.callHistory!!.asset)
        assertEquals(31_462, file.length().toInt(), "the packaged history is not upstream's JARL_ALLJA.TXT")
        val history = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(file.readText(), config.callHistory!!, "W1AW", data.callFacts),
        ).history
        // 2 542 lines: a '#' banner and 2 538 two-cell data rows. The file's rule (two non-empty
        // cells, no call grammar) and the port's agree exactly — the grammar drops nothing here,
        // unlike SST's two rows (ADR-0012 §8).
        assertEquals(2_538, history.size, "unexpected JARL ALL JA history size ${history.size}")
        assertEquals("14H", history.recordFor("7K1CPT")?.get("number"))
        assertEquals("110M", history.recordFor("JA8BMD")?.get("number"))

        // Every cell the shipped file carries is a legal entry for the operator's own second box
        // (ExchFields.pas:62 `([0-9AOTN]*)([LMHP])`, ≤4), so the copy an operator can type always
        // matches what the station sent. Measured, not assumed.
        val numbers = history.calls.map { history.recordFor(it)?.get("number").orEmpty() }
        assertEquals(4, numbers.maxOf { it.length }, "a number is longer than the box allows")
        assertTrue(
            numbers.all { it.last() in "LMHP" },
            "a number without a trailing power letter: ${numbers.filter { it.last() !in "LMHP" }.take(5)}",
        )

        val roster = StationRoster(
            config = config,
            pool = history,
            history = history,
            settings = RosterSettings(speedWpm = 15),
            seed = 20_261_020L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { roster.add() }
        assertEquals(StationRoster.MAX_STATIONS, roster.size)
        roster.stations.forEach { station ->
            val record = assertNotNull(history.recordFor(station.call), station.call)
            assertEquals("599", station.exchange.exch1, "AllJa.pas:222 the RST is always 599")
            assertEquals(record["number"].orEmpty().uppercase(), station.exchange.exch2, station.call)
        }
    }

    @Test
    fun `the packaged JARL ACAG history drives a real roster`() {
        // T13: the fourth per-contest ticket. The file is upstream's `JARL_ACAG.TXT`, copied
        // unmodified (MPL-2.0, spec §9), and this is the whole chain the device uses:
        // packaged bytes → the config's own callHistory spec → the roster's exchange.
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        val config = bundled.configs.firstOrNull { it.id == "acag" }
        assertNotNull(config, "no bundled acag config")

        val file = File(assetsDir, config.callHistory!!.asset)
        assertEquals(38_526, file.length().toInt(), "the packaged history is not upstream's JARL_ACAG.TXT")
        val history = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(file.readText(), config.callHistory!!, "W1AW", data.callFacts),
        ).history
        // 2 551 lines: a '#' banner and 2 547 two-cell data rows. The file's rule (two non-empty
        // cells, no call grammar) and the port's agree exactly (ADR-0013 §2).
        assertEquals(2_547, history.size, "unexpected JARL ACAG history size ${history.size}")
        assertEquals("1430H", history.recordFor("7K1CPT")?.get("number"))
        assertEquals("01039M", history.recordFor("7K1AEU/8")?.get("number"))
        assertEquals("100115M", history.recordFor("7K1BIB")?.get("number"))

        // Every cell the shipped file carries is a legal entry for the operator's own second box
        // (ExchFields.pas:63 `([0-9AOTN]*)([LMHP])`, ≤7), so the copy an operator can type always
        // matches what the station sent. Measured, not assumed.
        val numbers = history.calls.map { history.recordFor(it)?.get("number").orEmpty() }
        assertEquals(7, numbers.maxOf { it.length }, "a code is longer than the box allows")
        assertTrue(
            numbers.all { it.last() in "LMHP" },
            "a code without a trailing power letter: ${numbers.filter { it.last() !in "LMHP" }.take(5)}",
        )

        val roster = StationRoster(
            config = config,
            pool = history,
            history = history,
            settings = RosterSettings(speedWpm = 15),
            seed = 20_261_020L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { roster.add() }
        assertEquals(StationRoster.MAX_STATIONS, roster.size)
        roster.stations.forEach { station ->
            val record = assertNotNull(history.recordFor(station.call), station.call)
            assertEquals("599", station.exchange.exch1, "Acag.pas:222 the RST is always 599")
            assertEquals(record["number"].orEmpty().uppercase(), station.exchange.exch2, station.call)
        }
    }

    @Test
    fun `the packaged CWops CWT history drives a real roster`() {
        // T14: the fifth per-contest ticket, and the series' second header-driven history. The
        // file is upstream's `CWOPS.LIST`, copied unmodified (MPL-2.0, spec §9), keeping its
        // name and case because Android assets are case-sensitive.
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        val config = bundled.configs.firstOrNull { it.id == "cwops" }
        assertNotNull(config, "no bundled cwops config")

        val file = File(assetsDir, config.callHistory!!.asset)
        assertEquals(190_371, file.length().toInt(), "the packaged history is not upstream's CWOPS.LIST")
        val history = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(file.readText(), config.callHistory!!, "W1AW", data.callFacts),
        ).history
        // 6 344 lines → 6 290 records: upstream's rule table keeps 6 293 over 6 292 distinct
        // calls (`W1AL` is duplicated), and the shared call grammar drops `F/G7BED`/`I/W9CF`.
        assertEquals(6_290, history.size, "unexpected CWops CWT history size ${history.size}")
        assertEquals("Robert", history.recordFor("3B9FR")?.get("name"))
        assertEquals("1343", history.recordFor("3B9FR")?.get("exch"))
        assertEquals("Scott", history.recordFor("W1AL")?.get("name"))
        assertEquals("4O", history.recordFor("4O3A")?.get("exch"))

        // Every exchange is a legal entry for the operator's own second box
        // (ExchFields.pas:54 `[0-9A-Z]*`, ≤12): measured, the longest is 4. Four rows spell it
        // in lower case (`AC8SI,mi`), which upstream upper-cases at load (`CwOps.pas:88`) and
        // the document's `upper(hisExch)` does for the sent exchange.
        val exchanges = history.calls.map { history.recordFor(it)?.get("exch").orEmpty().uppercase() }
        assertEquals(4, exchanges.maxOf { it.length }, "an exchange is longer than the box allows")
        assertTrue(exchanges.all { it.all { c -> c in '0'..'9' || c in 'A'..'Z' } })
        assertEquals("mi", history.recordFor("AC8SI")?.get("exch"), "the file's own case is kept")
        assertEquals(
            3_055,
            exchanges.count { it.all { c -> c in '0'..'9' } },
            "the numeric (CWT member) exchanges",
        )

        val roster = StationRoster(
            config = config,
            pool = history,
            history = history,
            settings = RosterSettings(speedWpm = 15),
            seed = 20_261_020L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { roster.add() }
        assertEquals(StationRoster.MAX_STATIONS, roster.size)
        roster.stations.forEach { station ->
            val record = assertNotNull(history.recordFor(station.call), station.call)
            assertEquals(record["name"].orEmpty().uppercase(), station.exchange.exch1, station.call)
            assertEquals(record["exch"].orEmpty().uppercase(), station.exchange.exch2, station.call)
        }
    }

    @Test
    fun `the packaged DXCC list resolves the entities the contests score on`() {
        assertEquals(335, data.dxcc.size, "the DXCC table is not the shipped one")
        assertEquals("Fed. Rep. of Germany", data.callFacts.country("DL2XYZ"))
        assertEquals(14.0, data.callFacts.zone("DL2XYZ"))
        assertEquals("Japan", data.callFacts.country("JA1ABC"))
        assertEquals("Hawaii", data.callFacts.country("KH6XX"))
        // Multi-zone entities stay unresolved from the prefix alone: the zone comes from
        // call history (the stand-in supplies the gap until T6 parses the real file).
        assertEquals(null, data.callFacts.zone("K1ABC"))
    }

    @Test
    fun `the real pool fills a pile-up through the committed roster`() {
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        val config = bundled.configs.firstOrNull { it.id == "cqww" }
        assertNotNull(config, "no bundled cqww config")

        val roster = StationRoster(
            config = config,
            pool = data.pool,
            history = data.standInHistory(),
            settings = RosterSettings(speedWpm = 25),
            seed = 20_261_019L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { roster.add() }
        val stations = roster.stations

        assertEquals(StationRoster.MAX_STATIONS, stations.size, "the roster did not fill to eight")
        assertEquals(stations.size, stations.map { it.call }.toSet().size, "the pile-up repeats a callsign")
        assertTrue(
            stations.all { data.pool.contains(it.call) },
            "a station invented a callsign outside MASTER.DTA",
        )
        assertTrue(stations.all { it.level in 0.2..1.0 }, "a station has an impossible level")
        assertTrue(stations.all { it.pitchHz > 0 }, "a station has no tone frequency")
        assertTrue(stations.all { it.fade.bandwidthHz > 0.0 }, "a station has no fade profile")
        assertTrue(
            stations.map { it.pitchHz }.toSet().size >= 4,
            "the stations are stacked on one frequency",
        )
        assertTrue(
            stations.map { it.speedWpm }.toSet().size >= 3,
            "the stations all send at the same speed",
        )
    }

    @Test
    fun `assets to audio renders a real tone at the station's own pitch`() {
        val bundled = ContestLibrary.bundled()
        val config = bundled.configs.first { it.id == "cqww" }
        val roster = StationRoster(
            config = config,
            pool = data.pool,
            history = data.standInHistory(),
            settings = RosterSettings(speedWpm = 25),
            seed = 7L,
            calls = data.callFacts,
        )
        repeat(3) { roster.add() }
        val station = assertNotNull(roster.stations.firstOrNull())

        val audio = StationAudio(sampleRate = rate)
        val voice = audio.voice(station, id = 0, text = "TEST", rootSeed = 7L)
        val frames = minOf(voice.envelope.size, rate / 2)
        assertTrue(frames > 0, "the station's message encoded to nothing")

        val mixer = CwMixer(sampleRate = rate, masterGain = 1.0)
        mixer.start(voice)
        val out = FloatArray(frames)
        mixer.render(out, frames)

        assertTrue(out.any { it != 0f }, "the mix is silent")
        val atPitch = toneAt(out, frames, station.pitchHz.toDouble())
        assertTrue(atPitch > 0.02, "no energy at the station's ${station.pitchHz} Hz: $atPitch")
    }

    @Test
    fun `the packaged MASTER DTA drives a real CQ WPX roster, and its serials stay in range`() {
        // T15: CQ WPX is the first contest with no call history, so `MASTER.DTA` is the pool
        // (`CqWpx.pas:87-97`) and the exchange is generated entirely by the document: the
        // literal `599` plus a serial drawn from `Ini.pas:232-238`'s start-of-contest range.
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        val config = bundled.configs.firstOrNull { it.id == "cqwpx" }
        assertNotNull(config, "no bundled cqwpx config")
        assertNull(config.callHistory, "CQ WPX must not pretend to have a call history")

        val roster = StationRoster(
            config = config,
            pool = data.pool,
            history = null,
            settings = RosterSettings(speedWpm = 25),
            seed = 20_261_020L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { roster.add() }
        val stations = roster.stations
        assertEquals(StationRoster.MAX_STATIONS, stations.size, "the roster did not fill to eight")
        assertTrue(
            stations.all { data.pool.contains(it.call) },
            "a station invented a callsign outside MASTER.DTA",
        )
        for (station in stations) {
            assertEquals("599", station.exchange.exch1, "${station.call} must send the literal 599")
            val serial = station.exchange.exch2.toIntOrNull()
            assertNotNull(serial, "${station.call}: '${station.exchange.exch2}' is not a serial")
            assertTrue(serial in 1..176, "${station.call}: serial $serial outside Ini's range")
        }

        // The multiplier is `callPrefix(theirCall)`; a pool call with no WPX prefix would make
        // the engine fail closed and score the contact zero (ADR-0015 §7). Measure the pool
        // rather than assume: this is the number the ticket records.
        var unresolved = 0
        for (index in 0 until data.pool.size) {
            if (data.callFacts.prefix(data.pool.callAt(index)) == null) unresolved++
        }
        // Measured on the shipped file: **0 of 46 039** calls fail the WPX prefix rule, so the
        // fail-closed path can never cost a real station its point (ADR-0015 §7).
        assertEquals(0, unresolved, "MASTER.DTA calls with no WPX prefix")
    }

    @Test
    fun `the packaged ARRL DX history splits into the two contest sides`() {
        // T17: ARRL DX is the first contest whose *station pool* depends on which side of the
        // contest the operator is on (`ArrlDx.pas:157-158`). The file is upstream's
        // `ARRLDXCW_USDX.txt`, copied unmodified (MPL-2.0, spec §9): one call history carrying a
        // `State` **and** a `Power` column, where every data row fills exactly one.
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        val config = bundled.configs.firstOrNull { it.id == "arrldx" }
        assertNotNull(config, "no bundled arrldx config")

        val file = File(assetsDir, config.callHistory!!.asset)
        assertEquals(97_310, file.length().toInt(), "the packaged history is not upstream's ARRLDXCW_USDX.txt")

        val text = file.readText()
        val local = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(text, config.callHistory!!, "W1AW", data.callFacts),
        ).history
        val dx = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(text, config.callHistory!!, "DL1AA", data.callFacts),
        ).history

        // Measured on the shipped file: 8 357 data rows — 3 781 carry a power (the DX stations a
        // W/VE operator works) and 4 576 carry a state (the W/VE stations a DX operator works).
        // No row carries both, so the two pools are disjoint and add up to the whole file.
        assertEquals(3_781, local.size, "unexpected W/VE-side pool size")
        assertEquals(4_576, dx.size, "unexpected DX-side pool size")
        assertTrue(local.calls.intersect(dx.calls).isEmpty(), "the two sides share a station")
        assertEquals(8_357, local.size + dx.size, "a data row went missing")

        // One column per side and the other one empty: that is what makes `getExch2` =
        // `State + Power` (`ArrlDx.pas:320-323`) equal to the one piece the station sends.
        assertTrue(local.calls.all { local.recordFor(it)?.get("power").orEmpty().isNotEmpty() })
        assertTrue(local.calls.all { local.recordFor(it)?.get("state").orEmpty().isEmpty() })
        assertTrue(dx.calls.all { dx.recordFor(it)?.get("state").orEmpty().isNotEmpty() })
        assertTrue(dx.calls.all { dx.recordFor(it)?.get("power").orEmpty().isEmpty() })
        assertEquals("100", local.recordFor("3V8SS")?.get("power"))
        assertEquals("500", local.recordFor("3W9A")?.get("power"))
        assertEquals("MN", dx.recordFor("AA0AC")?.get("state"))
        // The file's second `!!Order!!` line announces the US section; it is a header, not a call.
        assertNull(local.recordFor("!!ORDER!!"), "the section header was parsed as a station")
        assertNull(dx.recordFor("!!ORDER!!"), "the section header was parsed as a station")

        // The W/VE side's multiplier is `callCountry(theirCall)`, and the engine fails closed: an
        // unresolved call scores 0 instead of upstream's 3 points and no multiplier
        // (`ArrlDx.pas:353-355`). Measured: three calls in 3 781 — this is the number the ticket
        // records (ADR-0017 §7).
        val unresolved = local.calls.filter { data.callFacts.country(it) == null }.toSet()
        assertEquals(setOf("D1A", "D1DX", "LU1AW/D"), unresolved)

        // The whole chain the device uses: packaged bytes → the config's own filter spec →
        // the roster's exchange, on both sides of the contest.
        val localRoster = StationRoster(
            config = config,
            pool = local,
            history = local,
            settings = RosterSettings(speedWpm = 25),
            seed = 20_261_020L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { localRoster.add() }
        assertEquals(StationRoster.MAX_STATIONS, localRoster.size)
        localRoster.stations.forEach { station ->
            val record = assertNotNull(local.recordFor(station.call), station.call)
            assertEquals("599", station.exchange.exch1, "ArrlDx.pas:315 the station's RST is 599")
            assertEquals(record["power"], station.exchange.exch2, station.call)
        }

        val dxRoster = StationRoster(
            config = config,
            pool = dx,
            history = dx,
            settings = RosterSettings(speedWpm = 25),
            seed = 20_261_020L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { dxRoster.add() }
        assertEquals(StationRoster.MAX_STATIONS, dxRoster.size)
        dxRoster.stations.forEach { station ->
            val record = assertNotNull(dx.recordFor(station.call), station.call)
            assertEquals("599", station.exchange.exch1)
            assertEquals(record["state"], station.exchange.exch2, station.call)
        }
        // Different sides, different stations: the two rosters cannot draw the same calls.
        assertTrue(
            localRoster.stations.map { it.call }.toSet()
                .intersect(dxRoster.stations.map { it.call }.toSet()).isEmpty(),
            "the two sides drew the same station",
        )
    }

    @Test
    fun `the packaged ARRL Field Day history runs the two-pass club load into a roster`() {
        // T18: the last per-contest ticket, and the only one whose history load is an algorithm
        // (`ArrlFd.pas:192-383`). The file is upstream's `FDGOTA.txt`, copied unmodified
        // (MPL-2.0, spec §9), keeping its name and case because Android assets are
        // case-sensitive (the Pascal asks for `FDGOTA.TXT`).
        val bundled = ContestLibrary.bundled()
        assertTrue(bundled.errors.isEmpty(), "bundled configs failed: ${bundled.errors}")
        val config = bundled.configs.firstOrNull { it.id == "arrlfd" }
        assertNotNull(config, "no bundled arrlfd config")
        assertNotNull(config.callHistory?.load, "Field Day must declare its load strategy")

        val file = File(assetsDir, config.callHistory!!.asset)
        assertEquals(459_501, file.length().toInt(), "the packaged history is not upstream's FDGOTA.txt")
        val history = assertIs<HistoryLoad.Loaded>(
            CallHistoryFile.parse(file.readText(), config.callHistory!!, "DL1AA", data.callFacts, 20_261_020L),
        ).history
        // Measured on the shipped file after the two passes (ADR-0018 §4): 17 322 data rows
        // reduce to 4 742 stations at this seed — the two-pass rule, not a row count.
        assertEquals(4_742, history.size, "unexpected Field Day history size ${history.size}")
        val sample = assertNotNull(history.recordFor(history.callAt(0)), history.callAt(0))
        assertTrue(sample["class"].orEmpty().isNotEmpty())
        assertTrue(sample["section"].orEmpty().isNotEmpty())

        // The whole chain the device uses: packaged bytes → the config's own load strategy →
        // the roster's exchange. Every station must send its class and its section.
        val roster = StationRoster(
            config = config,
            pool = history,
            history = history,
            settings = RosterSettings(speedWpm = 25),
            seed = 20_261_020L,
            calls = data.callFacts,
        )
        repeat(StationRoster.MAX_STATIONS) { roster.add() }
        assertEquals(StationRoster.MAX_STATIONS, roster.size)
        roster.stations.forEach { station ->
            val record = assertNotNull(history.recordFor(station.call), station.call)
            assertEquals(record["class"].orEmpty(), station.exchange.exch1, station.call)
            assertEquals(record["section"].orEmpty(), station.exchange.exch2, station.call)
        }
    }

    /** Magnitude of [hz] in [samples] (Goertzel); enough to prove a tone is present. */
    private fun toneAt(samples: FloatArray, frames: Int, hz: Double): Double {        val w = 2.0 * PI * hz / rate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until frames) {
            val s0 = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt(abs(s1 * s1 + s2 * s2 - coeff * s1 * s2)) / frames
    }
}
