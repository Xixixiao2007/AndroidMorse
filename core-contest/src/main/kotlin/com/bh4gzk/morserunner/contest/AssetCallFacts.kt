package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.CallPrefix
import com.bh4gzk.morserunner.data.DxccList
import com.bh4gzk.morserunner.data.DxccRecord
import com.bh4gzk.morserunner.expr.CallFacts

/**
 * The real [CallFacts]: `DXCC.LIST` for entity / continent / zone, `CallPrefix`
 * for the prefix rules (spec §9). T2 left the port empty so the four `call*`
 * built-ins failed closed; this is where the data layer arrives.
 *
 * Every answer is **data, never a guess** — an unknown call, a multi-zone entity,
 * or an unanswerable question returns `null`, and the built-in then fails closed
 * with `CALL_DATA_MISSING`.
 *
 *  - [prefix] is the **WPX** prefix (`deleteTrailingLetters = true`:
 *    `DL2XYZ → DL2`, `W7SST/6 → W6`), which is what CQ WPX scores on. The
 *    DXCC-mode prefix (`DL/AA1ZZ → DL`, `RC2FX → RC2F`) is an internal detail of
 *    [country] / [continent] / [zone]; keeping the two modes apart is the point
 *    of `core-data.CallPrefix`.
 *  - [zone] is the CQ zone and only when the row carries a **single** value.
 *    Several entities list a set (`K` is `3,4,5`) and upstream takes the zone from
 *    the call history for those (`CqWW.pas:280`), so returning one number here
 *    would invent a fact. Multi-zone rows therefore fail closed.
 *  - [arrlSection] is always `null`: no data source maps a callsign to an
 *    ARRL/RAC section (a call area contains several). Sections come from the call
 *    history (`theirSect`) or the operator's settings (`mySection`). The section
 *    *table* still ships — as `constants.arrlSections`, resolved from `core-data`
 *    (ADR-0004 decision 9).
 *
 * The record comes from [DxccList.find], which is upstream's `TDXCC.FindRec`: it
 * extracts the DXCC-mode prefix and applies the two hard-coded rewrites
 * (KG4 2x1/2x3 calls are US while 2x2 stays Guantanamo; CE9/KC4 become the
 * CE9KC4 Antarctica row, `DXCC.pas:136-170`). Without those rewrites the table
 * answers wrongly for real KG4 and Antarctic calls.
 */
class AssetCallFacts(private val dxcc: DxccList) : CallFacts {

    override fun prefix(call: String): String? =
        CallPrefix.extractPrefix(call, deleteTrailingLetters = true).ifEmpty { null }

    override fun zone(call: String): Double? =
        record(call)?.cqZone?.trim()?.toIntOrNull()?.toDouble()

    override fun country(call: String): String? = record(call)?.entity

    override fun continent(call: String): String? = record(call)?.continent

    /** No call → section mapping exists; see the class KDoc. */
    override fun arrlSection(call: String): String? = null

    private fun record(call: String): DxccRecord? = dxcc.find(call)
}
