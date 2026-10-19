package com.bh4gzk.morserunner.session

import android.content.res.AssetManager
import com.bh4gzk.morserunner.contest.CallHistory
import com.bh4gzk.morserunner.contest.CallHistoryFile
import com.bh4gzk.morserunner.contest.ContestConfig
import com.bh4gzk.morserunner.contest.ContestLibrary
import com.bh4gzk.morserunner.contest.HistoryLoad
import com.bh4gzk.morserunner.contest.HistoryLookup
import com.bh4gzk.morserunner.contest.LoadResult
import com.bh4gzk.morserunner.data.CallPool
import com.bh4gzk.morserunner.data.StationAssets
import com.bh4gzk.morserunner.data.StationData
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.qso.QsoLog
import com.bh4gzk.morserunner.settings.SessionSettings

/**
 * Turns the packaged data into a running session.
 *
 * Two decisions live here, and both have an upstream primary source:
 *
 * 1. **The station pool is the contest's call-history file** when it loads. Upstream draws a
 *    caller from its own contest call list (`TContest.PickStation` → `CqWwCallList`,
 *    `CqWW.pas:141,272`) and falls back to `MASTER.DTA` only for contests that have no history.
 *    That also removes a real defect: the 8 875-call `CQWWCW.txt` and the 46 039-call
 *    `MASTER.DTA` overlap in 4 584 calls, so drawing from `MASTER.DTA` and skipping calls the
 *    history cannot describe made `StationRoster.add()` exhaust its ten attempts about a third
 *    of the time.
 * 2. **When the file is rejected** (absent from this build, or the contest's DXCC gate fails)
 *    the session still starts: `MASTER.DTA` is the pool and the T5 stand-in supplies the
 *    exchange columns, with [Result.note] saying so on screen. A contest that cannot generate a
 *    station at all is not playable, and a phone that refuses to start is worse than one that
 *    explains itself.
 *
 * **T9 owns the operator and the session's shape.** The callsign, the speed, the pitch, the
 * duration and the four effect switches all come from [SessionSettings]; the operator's exchange
 * is still derived from the callsign (the contest's defaults, then the DXCC table), so changing
 * the call changes the zone the session sends. `W1AW` is T7's placeholder, not upstream's:
 * `Ini.Call`'s own default is `VE3NEA` (`Ini.pas:223`).
 */
object SessionBootstrap {

    /** CQ zone used when the operator's call resolves to a multi-zone entity (W1AW → CT, zone 5). */
    private const val DEFAULT_CQ_ZONE: String = "5"

    /** What the screen starts with, plus anything the operator should be told about the data. */
    data class Result(val controller: SessionController, val note: String)

    fun create(
        assets: AssetManager,
        data: StationData,
        settings: SessionSettings,
        seed: Long,
        startMs: Long,
        contestId: String = ContestLibrary.DEFAULT_ID,
        /** A log to resume into (T9's session snapshot); empty for a new contest. */
        log: QsoLog = QsoLog(),
    ): Result {
        val config = when (val loaded = ContestLibrary.load(contestId)) {
            is LoadResult.Success -> loaded.config
            is LoadResult.Failure -> error(
                "contest '$contestId' does not load: " +
                    loaded.errors.joinToString { it.render() },
            )
        }
        val facts: CallFacts = data.callFacts
        val spec = config.callHistory

        val pool: CallPool
        val historyLookup: HistoryLookup?
        val autocomplete: Collection<String>
        val note: String
        when (val parsed = if (spec == null) null else history(assets, spec, settings.myCall, facts)) {
            // No call-history file at all (T15's CQ WPX): upstream reads `MASTER.DTA` as the
            // station pool and takes every exchange from the contest's generation rules
            // (`CqWpx.pas:87-97,299-317`), so there is nothing to parse and no note of loss —
            // only a note of which pool is in use.
            null -> {
                pool = data.pool
                historyLookup = null
                autocomplete = emptyList()
                note = "${config.name} · MASTER.DTA"
            }
            is HistoryLoad.Loaded -> {
                pool = parsed.history
                historyLookup = parsed.history
                autocomplete = parsed.history.calls
                note = "${config.name} · ${parsed.history.size} 个呼号"
            }
            is HistoryLoad.Rejected -> {
                pool = data.pool
                historyLookup = data.standInHistory()
                autocomplete = emptyList()
                note = "呼号表不可用（${parsed.reason}），交换用占位值"
            }
        }

        val controller = SessionController.start(
            config = config,
            pool = pool,
            history = historyLookup,
            autocompleteCalls = autocomplete,
            calls = facts,
            myCall = settings.myCall,
            myExchange = myExchange(config, facts, settings.myCall),
            seed = seed,
            startMs = startMs,
            speedWpm = settings.speedWpm,
            pitchHz = settings.pitchHz,
            durationMinutes = settings.durationMinutes,
            lids = settings.lids,
            flutter = settings.flutter,
            qrm = settings.qrm,
            log = log,
        )
        return Result(controller, note)
    }

    /** The contest's call history, parsed against its own spec (`CallHistoryFile`). */
    private fun history(
        assets: AssetManager,
        spec: CallHistory,
        myCall: String,
        calls: CallFacts,
    ): HistoryLoad {
        val text = StationAssets.readText(assets, spec.asset)
            ?: return HistoryLoad.Rejected("this build has no asset '${spec.asset}'")
        return CallHistoryFile.parse(text, spec, myCall, calls)
    }

    /** My own exchange: the contest's defaults, with the DXCC-derived values as a fallback. */
    private fun myExchange(
        config: ContestConfig,
        calls: CallFacts,
        myCall: String,
    ): Map<String, String> {
        val defaults = config.station.exchangeDefaults
        return config.exchange.myFields.associate { field ->
            val value = defaults[field.key] ?: when (field.key) {
                "zone" -> calls.zone(myCall)?.toInt()?.toString() ?: DEFAULT_CQ_ZONE
                "section" -> calls.arrlSection(myCall) ?: ""
                "rst" -> "599"
                else -> ""
            }
            field.key to value
        }
    }
}
