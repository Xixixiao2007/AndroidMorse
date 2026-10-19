package com.bh4gzk.morserunner.data

import android.content.res.AssetManager
import com.bh4gzk.morserunner.contest.AssetCallFacts
import com.bh4gzk.morserunner.contest.HistoryLookup

/**
 * The two upstream data files the simulator cannot invent (spec §9), packaged as
 * Android assets.
 *
 * Both are redistributed **unmodified** from Morse Runner Community Edition 1.85.4
 * (MPL-2.0): `MASTER.DTA` is the 1 239 476-byte callsign pool and `DXCC.LIST` the
 * 335-entity prefix table (sha256 pinned by `PackagedAssetsTest`). They are committed
 * under `app/src/main/assets/` rather than read from `upstream/` because `upstream/` is a
 * git-ignored reference clone — an APK built from a clean checkout would otherwise have
 * no stations at all.
 *
 * [parse] exists so a JVM test can prove the *packaged* bytes parse, without an emulator.
 */
class StationData(
    val pool: com.bh4gzk.morserunner.data.MasterDta,
    val dxcc: com.bh4gzk.morserunner.data.DxccList,
    val callFacts: AssetCallFacts,
) {

    /**
     * A stand-in for the contest call-history asset (`CQWWCW.TXT`, `SSCW.TXT`, …).
     *
     * The roster refuses to create a station whose exchange it cannot generate
     * (`StationRoster` skips such a call), and T6 is the ticket that parses those files.
     * Until then this supplies the one column the demo contest binds (`zone`):
     *  - from the DXCC table when the entity has a single CQ zone — real data;
     *  - otherwise a **deterministic** fallback derived from the callsign, which is
     *    exactly the gap the real history file fills (upstream reads a US station's zone
     *    from it, `CqWw.pas:293`).
     *
     * The fallback is not a claim about the world; it is a placeholder whose only job is
     * to let the pile-up be heard before T6 lands. The `check`/`section` columns are
     * supplied for the other two configs and are likewise stand-ins.
     */
    fun standInHistory(): HistoryLookup = HistoryLookup { call ->
        mapOf(
            "zone" to zoneFor(call).toString(),
            "check" to "72",
            "section" to sectionFor(call),
        )
    }

    private fun zoneFor(call: String): Int {
        val known = callFacts.zone(call)
        if (known != null) return known.toInt()
        var mixed = 0L
        for (character in call) {
            mixed = (mixed xor character.code.toLong()) * MIX
            mixed = (mixed xor (mixed ushr 29)) * GOLDEN
        }
        // CQ zones are 1..40; the low bits pick one deterministically for this call.
        return ((mixed ushr 1) % 40L).toInt() + 1
    }

    private fun sectionFor(call: String): String {
        val digit = call.firstOrNull { it in '0'..'9' } ?: return "OR"
        // Areas 0-9 map onto the ARRL/RAC table's call-area groups closely enough for a
        // placeholder; the section's real source is the call-history file.
        val areas = mapOf(
            '0' to "IA", '1' to "EMA", '2' to "NNY", '3' to "EPA", '4' to "NC",
            '5' to "NTX", '6' to "SCV", '7' to "AZ", '8' to "OH", '9' to "IL",
        )
        return areas[digit] ?: "OR"
    }

    private companion object {
        val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
        val MIX = 0xC2B2AE3D27D4EB4FuL.toLong()
    }
}

object StationAssets {

    const val MASTER = "master.dta"
    const val DXCC = "dxcc.list"

    /** Parses both assets; throws when either is broken, because an asset is not user input. */
    fun parse(masterBytes: ByteArray, dxccText: String): StationData {
        val dxcc = com.bh4gzk.morserunner.data.DxccList.parse(dxccText)
        return StationData(
            pool = com.bh4gzk.morserunner.data.MasterDta.parse(masterBytes),
            dxcc = dxcc,
            callFacts = AssetCallFacts(dxcc),
        )
    }

    /** Reads and parses both packaged assets. */
    fun load(assets: AssetManager): StationData = parse(
        masterBytes = assets.open(MASTER).use { it.readBytes() },
        dxccText = assets.open(DXCC).use { it.readBytes().toString(Charsets.UTF_8) },
    )

    /**
     * The text of one packaged asset, or `null` when this build does not carry it.
     *
     * The contest's call-history file is named by its config (`callHistory.asset`, spec §9),
     * not by this object: a contest document may name a file T10's build adds.
     */
    fun readText(assets: AssetManager, name: String): String? = try {
        assets.open(name).use { it.readBytes().toString(Charsets.ISO_8859_1) }
    } catch (missing: java.io.IOException) {
        null
    }
}
