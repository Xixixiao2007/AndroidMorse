package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.CallPool
import com.bh4gzk.morserunner.data.Callsign
import com.bh4gzk.morserunner.expr.CallFacts
import com.bh4gzk.morserunner.expr.DEFAULT_STEP_BUDGET
import com.bh4gzk.morserunner.expr.EvalResult
import com.bh4gzk.morserunner.expr.Value

/** Loading a contest's call history either produced a lookup or says why it refused. */
sealed interface HistoryLoad {
    data class Loaded(val history: CallHistoryFile) : HistoryLoad
    data class Rejected(val reason: String) : HistoryLoad
}

/**
 * The real [HistoryLookup]: a contest's call-history asset parsed according to its
 * own [CallHistory] spec (T6 fills the seam T5 left).
 *
 * The three shipped files have three different shapes and the **config** decides
 * which applies — no contest-specific code lives here:
 *
 *  - CQ WW (`CqWW.pas:79-98`): comma, no header (the `!!Order!!` banner is a skipped
 *    line), columns `call,cqzone,usertext`, at least three fields, a numeric zone.
 *  - ARRL SS (`ArrlSS.pas:106-126`): comma, no header, columns
 *    `call,section,?,check[,usertext]`, at least four fields, an integer check, and
 *    the call must pass the shared grammar.
 *  - IARU HF (`IaruHf.pas:114-151`): comma, `!!Order!!` line *defines* the columns,
 *    duplicates dropped (first wins), and every record refused unless the operator's
 *    call resolves in DXCC.
 *
 * A row that violates its spec is skipped (upstream `continue`); a file that yields
 * no usable record, or whose DXCC gate fails, is [HistoryLoad.Rejected] rather than
 * silently empty. Values are trimmed; CALLSIGN columns are uppercased through
 * [Callsign.normalise]. With `dedupe = false` a later row replaces an earlier one
 * (a plain map put); with `dedupe = true` the first row wins, as upstream's
 * `dupList` does.
 *
 * **An existing empty cell is a value for a non-required TEXT column** (T10): NAQP's DX
 * records carry an empty state (`NaQp.pas:125`) and the station sends its name only, so the
 * record has to keep `state = ""`. A row that does not have the cell at all still yields no
 * column, a required column still rejects an empty value, and non-TEXT columns keep skipping
 * empties — otherwise IARU HF's empty sections would start passing its required-column gate.
 *
 * **The pool itself can depend on the operator** (T17). `callHistory.filter` is a per-row
 * predicate over the row's cells and the station properties; a row it rejects never enters the
 * pool, the same way upstream's `continue` leaves it out of `ArrlDxCallList`
 * (`ArrlDx.pas:157-158`). Unlike a skipped row, a predicate that *cannot* be evaluated is
 * [HistoryLoad.Rejected] — an empty pool must say why it is empty, not look like a contest.
 */
class CallHistoryFile private constructor(
    private val records: Map<String, Map<String, String>>,
) : HistoryLookup, CallPool {

    /** The calls in file order; that order is the pool's replay contract. */
    private val callOrder: List<String> = records.keys.toList()

    /** The number of usable calls. */
    override val size: Int get() = callOrder.size

    /** The usable calls, in file order. */
    val calls: Set<String> get() = records.keys

    override fun callAt(index: Int): String = callOrder[index]

    override fun recordFor(call: String): Map<String, String>? = records[Callsign.normalise(call)]

    companion object {

        /**
         * Parses [text] against [spec].
         *
         * @param myCall the operator's callsign, needed only by a spec with
         *   `requiresMyCallInDxcc` (IARU HF); ignored otherwise.
         * @param calls the DXCC provider for that gate. Its default
         *   ([CallFacts.Unavailable]) refuses the gate, which is the fail-closed
         *   behaviour a caller that has not wired the data layer should see.
         * @param seed the session seed, read only by a spec that declares a [HistoryLoadMode]
         *   (T18): the strategy's draws are a pure function of it, so the same seed replays the
         *   same pool. A spec without a strategy draws nothing and ignores it.
         */
        fun parse(
            text: String,
            spec: CallHistory,
            myCall: String = "",
            calls: CallFacts = CallFacts.Unavailable,
            seed: Long = 0L,
        ): HistoryLoad {
            if (spec.columns.none { it.name == CALL_COLUMN }) {
                return HistoryLoad.Rejected(
                    "callHistory.columns must declare a '$CALL_COLUMN' column, found " +
                        spec.columns.map { it.name },
                )
            }
            if (spec.header == null) {
                val unindexed = spec.columns.firstOrNull { it.index == null }
                if (unindexed != null) {
                    return HistoryLoad.Rejected(
                        "callHistory column '${unindexed.name}' has no index and the config declares no header",
                    )
                }
            }
            if (spec.requiresMyCallInDxcc) {
                val resolves = myCall.isNotBlank() && calls.country(myCall) != null
                if (!resolves) {
                    return HistoryLoad.Rejected(
                        "the contest requires the operator call '$myCall' to resolve in DXCC " +
                            "before its call history can be used",
                    )
                }
            }

            val records = LinkedHashMap<String, Map<String, String>>()
            val headerIndices = HashMap<String, Int>()
            var headerSeen = spec.header == null
            val duplicates = if (spec.dedupe) HashSet<String>() else null
            // T18: a load strategy is a reduction over the whole file, so it needs the rows in
            // file order — including duplicates, which the generic map would overwrite.
            val load = spec.load
            val orderedRows = if (load == null) null else ArrayList<Map<String, String>>()
            // T17: the row predicate. The station properties are bound once — the file is read
            // once — and each row overwrites only the cell names it declares.
            val filter = spec.filter
            val filterEnvironment = filter?.let {
                LinkedHashMap<String, Value>().apply { putAll(stationValues(myCall, calls)) }
            }

            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (line.isBlank()) continue
                if (spec.skipPrefixes.any { line.startsWith(it) }) continue

                if (spec.header != null && !headerSeen) {
                    if (!line.startsWith(spec.header.marker)) continue
                    val indices = readHeader(line, spec, spec.header)
                        ?: return HistoryLoad.Rejected(
                            "the '${spec.header.marker}' line does not name the configured columns " +
                                spec.columns.map { it.name },
                        )
                    headerIndices.putAll(indices)
                    headerSeen = true
                    continue
                }

                val fields = line.split(spec.delimiter)
                if (fields.size < spec.minFields) continue
                val record = readRecord(fields, spec, if (spec.header != null) headerIndices else null)
                    ?: continue
                val call = record[CALL_COLUMN] ?: continue
                if (filter != null && filterEnvironment != null) {
                    for (column in spec.columns) {
                        // A declared column a shorter row does not carry reads as empty: the
                        // predicate is about what the cells say, not about the row's width.
                        filterEnvironment[column.name] = Value.str(record[column.name] ?: "")
                    }
                    val verdict = when (val result = filter.evaluate(filterEnvironment, DEFAULT_STEP_BUDGET, calls)) {
                        // Fail closed and loudly: a predicate that cannot be evaluated would
                        // silently empty the pool, and an empty pool is not a contest.
                        is EvalResult.Failure -> return HistoryLoad.Rejected(
                            "call history '${spec.asset}': the filter cannot be evaluated for " +
                                "'$call': ${result.error.render()}",
                        )
                        is EvalResult.Success -> result.value
                    }
                    val keep = (verdict as? Value.Bool)?.value
                        ?: return HistoryLoad.Rejected(
                            "call history '${spec.asset}': the filter produced ${verdict.typeName}, " +
                                "expected a boolean",
                        )
                    if (!keep) continue
                }
                if (duplicates != null && !duplicates.add(call)) continue
                if (orderedRows != null) orderedRows += record else records[call] = record
            }

            // The strategy runs over the rows the column rules (and the row filter, when a spec
            // has one) accepted; a plain history keeps its file order, while a strategy defines
            // its own order (Field Day sorts by call, `ArrlFd.pas:349`).
            val finalRecords: Map<String, Map<String, String>> =
                if (load != null && orderedRows != null) TwoPassClubLoad.apply(orderedRows, seed) else records

            if (finalRecords.isEmpty()) {
                return HistoryLoad.Rejected(
                    "call history '${spec.asset}' has no usable records for this contest",
                )
            }
            return HistoryLoad.Loaded(CallHistoryFile(finalRecords))
        }

        internal const val CALL_COLUMN: String = "call"

        /**
         * The spec §4 station properties, as the row filter sees them (T17).
         *
         * The loader's `STATION_IDENTIFIERS` is the allowed set; this is the same five names
         * with the operator's values. A property the DXCC table cannot answer is simply
         * **absent**, so a filter that reads it fails closed per row instead of comparing
         * against a guess (upstream's `ArrlDx.pas:204-208` best-guesses a prefix instead).
         */
        private fun stationValues(myCall: String, calls: CallFacts): Map<String, Value> = buildMap {
            if (myCall.isNotBlank()) {
                put("myCall", Value.str(myCall))
                calls.country(myCall)?.let { put("myCountry", Value.str(it)) }
                calls.continent(myCall)?.let { put("myContinent", Value.str(it)) }
                calls.zone(myCall)?.let { put("myZone", Value.num(it)) }
                calls.arrlSection(myCall)?.let { put("mySection", Value.str(it)) }
            }
        }

        /** Maps our column names to the indices the file's `!!Order!!` line declares. */
        private fun readHeader(line: String, spec: CallHistory, header: HeaderSpec): Map<String, Int>? {
            val cells = line.split(spec.delimiter).map { it.trim() }
            // The marker occupies a cell in the header row but no cell in the data
            // rows, so it is dropped before the indices are computed — exactly what
            // `IaruHf.pas:117` does ("tl.Delete(0); // shifts others down by one").
            val labels = if (cells.isNotEmpty() && cells[0].startsWith(header.marker)) cells.drop(1) else cells
            val indices = HashMap<String, Int>()
            for (column in spec.columns) {
                val label = header.names[column.name] ?: return null
                val index = labels.indexOfFirst { it.equals(label, ignoreCase = true) }
                if (index < 0) return null
                indices[column.name] = index
            }
            return indices
        }

        /** One row, or `null` when a required value is missing/invalid (upstream `continue`). */
        private fun readRecord(
            fields: List<String>,
            spec: CallHistory,
            headerIndices: Map<String, Int>?,
        ): Map<String, String>? {
            val record = LinkedHashMap<String, String>()
            for (column in spec.columns) {
                val index = headerIndices?.get(column.name) ?: column.index
                    ?: return null // header-driven, but the header did not name this column
                val raw = fields.getOrNull(index)
                val value = when (column.type) {
                    ColumnType.CALLSIGN -> Callsign.normalise(raw.orEmpty())
                    else -> raw.orEmpty().trim()
                }
                if (value.isEmpty() || !isValid(column.type, value)) {
                    if (column.required) return null
                    // The cell exists and is empty: for TEXT that is a value (NAQP's DX
                    // station sends no state, `NaQp.pas:125`). A shorter row has no cell at
                    // all, and keeps having no column.
                    if (raw != null && column.type == ColumnType.TEXT) record[column.name] = ""
                    continue
                }
                record[column.name] = value
            }
            return record
        }

        private fun isValid(type: ColumnType, value: String): Boolean = when (type) {
            // Upstream validates the uppercased/trimmed call (ArrlSS.pas:111,116).
            ColumnType.CALLSIGN -> Callsign.isValid(value)
            // Upstream `IsNum`: non-empty ASCII digits only.
            ColumnType.DIGITS -> value.all { it in '0'..'9' }
            // Upstream `TryStrToInt`.
            ColumnType.INTEGER -> value.toIntOrNull() != null
            ColumnType.TEXT -> true
        }
    }
}
