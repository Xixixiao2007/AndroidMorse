package com.bh4gzk.morserunner.data

/**
 * One DXCC entity row from `DXCC.LIST`, fields named as upstream names them
 * (`DXCC.pas:63-69`).
 *
 * The ITU and CQ zones stay [String]s, like upstream: the file contains values
 * that are not plain numbers for a few entities, and the expression layer does
 * its own `num()` conversion where it needs arithmetic.
 */
data class DxccRecord(
    /** Regular expression matched against the callsign prefix (`field 1`). */
    val prefixPattern: String,
    val entity: String,
    val continent: String,
    val ituZone: String,
    val cqZone: String,
    /** `field 6`, kept for completeness; upstream reads it but never uses it. */
    val dxccNumber: String,
)

/**
 * The ARRL DXCC prefix table, parsed from `DXCC.LIST` (spec §9).
 *
 * Parsing rules are upstream's (`DXCC.pas:44-82`):
 *  - lines starting with `#` are comments;
 *  - only rows that split into exactly **7** `;`-separated fields are records, which
 *    is what silently drops the licence header at the top of the file;
 *  - a row whose second field starts with `!ignore` is skipped on purpose, because
 *    that expression would mask another entity;
 *  - field order is `prefix;prefixRegex;entity;continent;ITU;CQ;dxccNumber` —
 *    note ITU comes *before* CQ.
 *
 * Lookup walks the table **in reverse** and returns the first match
 * (`DXCC.pas:113-131`): later rows therefore win, which is how the file expresses
 * “this more specific prefix overrides the earlier general one”.
 *
 * This class answers questions about a *prefix*. Turning a callsign into a prefix
 * is a separate concern (upstream `CallsignUtils.pas`, covered by
 * `Test/CallsignUtilsTest.pas`); keeping the two apart is what lets both be tested
 * against their own golden data.
 */
class DxccList private constructor(
    private val records: List<DxccRecord>,
    private val patterns: List<Regex>,
) {

    val size: Int get() = records.size

    val isEmpty: Boolean get() = records.isEmpty()

    /** All records in file order; later entries take precedence on lookup. */
    fun records(): List<DxccRecord> = records

    /** The winning record for [prefix], or `null` when nothing matches. */
    fun lookup(prefix: String): DxccRecord? {
        if (prefix.isEmpty()) return null
        for (index in records.indices.reversed()) {
            if (patterns[index].containsMatchIn(prefix)) return records[index]
        }
        return null
    }

    /**
     * The record for a full callsign: extract the DXCC-mode prefix, apply
     * upstream's two hard-coded rewrites, then look it up (`DXCC.pas:136-170`).
     *
     * The rewrites are not cosmetic — without them the table answers wrongly:
     *  - **KG4**: a 2×2 call is Guantanamo Bay, but 2×1 and 2×3 calls are US
     *    (`DXCC.pas:149-158` rewrites those to `K`; `KG4ABC` would otherwise be
     *    Guantanamo Bay).
     *  - **Antarctica**: `CE9` and `KC4` are rewritten to `CE9KC4`
     *    (`DXCC.pas:160-165`), because the table maps `CE9` to South Shetland Is.
     *    and `KC4` to the United States.
     */
    fun find(call: String): DxccRecord? {
        var prefix = CallPrefix.extractPrefix(call, deleteTrailingLetters = false)
        if (prefix.isEmpty()) return null

        if (prefix.startsWith("KG4") && !prefix.startsWith("KG44") &&
            (prefix.length == 6 || prefix.length == 4)
        ) {
            prefix = "K"
        }
        if ((prefix.length == 3 && (prefix == "CE9" || prefix == "KC4")) ||
            (prefix.length == 6 && (prefix.startsWith("KC4AA") || prefix.startsWith("KC4US")))
        ) {
            prefix = "CE9KC4"
        }
        return lookup(prefix)
    }

    companion object {

        private const val FIELD_COUNT = 7
        private const val IGNORE_MARKER = "!ignore"
        private const val DELIMITER = ';'

        /**
         * Parses the whole file.
         *
         * @throws IllegalArgumentException when a record's regular expression does
         *   not compile, naming the line number and the pattern — a broken table
         *   should fail the build, not silently score contests wrongly.
         */
        fun parse(text: String): DxccList {
            val records = ArrayList<DxccRecord>()
            val patterns = ArrayList<Regex>()
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

                val fields = line.split(DELIMITER)
                if (fields.size != FIELD_COUNT) return@forEachIndexed
                if (fields[1].startsWith(IGNORE_MARKER)) return@forEachIndexed

                val record = DxccRecord(
                    prefixPattern = fields[1],
                    entity = fields[2],
                    continent = fields[3],
                    ituZone = fields[4],
                    cqZone = fields[5],
                    dxccNumber = fields[6],
                )
                val pattern = try {
                    Regex("^(${record.prefixPattern})")
                } catch (invalid: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "DXCC.LIST:${index + 1}: invalid prefix pattern " +
                            "'${record.prefixPattern}': ${invalid.message}",
                        invalid,
                    )
                }
                records.add(record)
                patterns.add(pattern)
            }
            return DxccList(records, patterns)
        }
    }
}
