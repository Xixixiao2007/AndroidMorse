package com.bh4gzk.morserunner.session

import android.content.res.AssetManager
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
 * The operator's identity is a placeholder, not personal data: `W1AW` is upstream's default
 * operator (`Ini.pas`) and its CQ zone comes from the exchange defaults or the DXCC table. T9
 * owns the settings screen that replaces it.
 *
 * **The session runs noisy** (T8): QSB/flutter, LID operators, the QRN noise bed and the
 * occasional QRM station are on by default — at `SessionController.start`'s defaults, since a
 * realism effect that is compiled but never heard proves nothing. The band, the flutter share
 * and the QRM rate are T9's settings; the levels live in `NoiseBed`/`FadeSchedule`.
 */
object SessionBootstrap {

    /** Milestone A's contest (spec §11): the one T7 has to make playable. */
    const val DEFAULT_CONTEST: String = "cqww"

    /** Upstream's default operator call; T9 turns it into a setting. */
    const val DEFAULT_MY_CALL: String = "W1AW"

    /** CQ zone used when the operator's call resolves to a multi-zone entity (W1AW → CT, zone 5). */
    private const val DEFAULT_CQ_ZONE: String = "5"

    /** What the screen starts with, plus anything the operator should be told about the data. */
    data class Result(val controller: SessionController, val note: String)

    fun create(
        assets: AssetManager,
        data: StationData,
        seed: Long,
        startMs: Long,
        contestId: String = DEFAULT_CONTEST,
        myCall: String = DEFAULT_MY_CALL,
        speedWpm: Int = SessionController.DEFAULT_SPEED_WPM,
    ): Result {
        val config = when (val loaded = ContestLibrary.load(contestId)) {
            is LoadResult.Success -> loaded.config
            is LoadResult.Failure -> error(
                "contest '$contestId' does not load: " +
                    loaded.errors.joinToString { it.render() },
            )
        }
        val facts: CallFacts = data.callFacts
        val parsed = history(assets, config, myCall, facts)

        val pool: CallPool
        val historyLookup: HistoryLookup
        val autocomplete: Collection<String>
        val note: String
        when (parsed) {
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
            myCall = myCall,
            myExchange = myExchange(config, facts, myCall),
            seed = seed,
            startMs = startMs,
            speedWpm = speedWpm,
        )
        return Result(controller, note)
    }

    /** The contest's call history, parsed against its own spec (`CallHistoryFile`). */
    private fun history(
        assets: AssetManager,
        config: ContestConfig,
        myCall: String,
        calls: CallFacts,
    ): HistoryLoad {
        val text = StationAssets.readText(assets, config.callHistory.asset)
            ?: return HistoryLoad.Rejected("this build has no asset '${config.callHistory.asset}'")
        return CallHistoryFile.parse(text, config.callHistory, myCall, calls)
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
