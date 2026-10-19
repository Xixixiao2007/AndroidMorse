package com.bh4gzk.morserunner

import com.bh4gzk.morserunner.audio.CwMixer
import com.bh4gzk.morserunner.audio.StationAudio
import com.bh4gzk.morserunner.contest.ContestLibrary
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
import kotlin.test.assertNotNull
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

    /** Magnitude of [hz] in [samples] (Goertzel); enough to prove a tone is present. */
    private fun toneAt(samples: FloatArray, frames: Int, hz: Double): Double {
        val w = 2.0 * PI * hz / rate
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
