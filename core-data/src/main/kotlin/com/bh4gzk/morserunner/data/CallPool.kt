package com.bh4gzk.morserunner.data

/**
 * An ordered source of callsigns a simulated station may be drawn from.
 *
 * Upstream has exactly two of these and treats them the same way: the contest's **own
 * call-history file** when it has one (`TContest.PickStation` → `CqWwCallList`,
 * `CqWW.pas:141,272`, `TCqWw.GetStationCall` → `CqWwCallList.Items[id].Call`) and
 * `MASTER.DTA` otherwise (`CallLst.pas`). Both are ordered lists whose order is part of the
 * replay contract, so the roster asks for this interface instead of one concrete asset.
 *
 * [MasterDta] is the `MASTER.DTA` implementation. `core-contest`'s `CallHistoryFile` is the
 * other one — it lives there because the history format is the schema's business, and it can
 * implement this interface because `core-contest` already depends on `core-data`.
 */
interface CallPool {

    /** How many calls are available. */
    val size: Int

    /**
     * The call at [index], `0 <= index < size`.
     *
     * The caller supplies the index from its seeded random source, which is why this is an
     * index accessor and not a shuffle: the roster's draw order stays in the roster.
     */
    fun callAt(index: Int): String
}
