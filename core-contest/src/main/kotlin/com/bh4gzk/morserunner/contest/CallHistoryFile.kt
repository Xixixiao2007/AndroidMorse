package com.bh4gzk.morserunner.contest

import com.bh4gzk.morserunner.data.CallPool
import com.bh4gzk.morserunner.data.Callsign
import com.bh4gzk.morserunner.expr.CallFacts

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
         */
        fun parse(
            text: String,
            spec: CallHistory,
            myCall: String = "",
            calls: CallFacts = CallFacts.Unavailable,
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
                if (duplicates != null && !duplicates.add(call)) continue
                records[call] = record
            }

            if (records.isEmpty()) {
                return HistoryLoad.Rejected(
                    "call history '${spec.asset}' has no usable records for this contest",
                )
            }
            return HistoryLoad.Loaded(CallHistoryFile(records))
        }

        internal const val CALL_COLUMN: String = "call"

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
