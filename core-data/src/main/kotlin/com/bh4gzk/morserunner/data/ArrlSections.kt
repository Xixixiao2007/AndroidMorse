package com.bh4gzk.morserunner.data

/**
 * The 85 ARRL/RAC contest multipliers, in upstream table order
 * (`Util/ArrlSections.pas:17-64`).
 *
 * Order is *data*, not presentation: the ARRL Sweepstakes "corrected section"
 * behaviour picks the neighbouring entry by index (`ArrlSS.pas:507-517`), so
 * re-sorting this list would change how the contest misleads the operator.
 */
object ArrlSections {

    val all: List<String> = listOf(
        // Call Area 0
        "CO", "IA", "KS", "MN", "MO",
        "ND", "NE", "SD",
        // Call Area 1
        "CT", "EMA", "ME", "NH", "RI",
        "VT", "WMA",
        // Call Area 2
        "ENY", "NLI", "NNJ", "NNY", "SNJ",
        "WNY",
        // Call Area 3
        "DE", "EPA", "MDC", "WPA",
        // Call Area 4
        "AL", "GA", "KY", "NC", "NFL",
        "SC", "SFL", "TN", "VA", "WCF",
        "PR", "VI",
        // Call Area 5
        "AR", "LA", "MS", "NM", "NTX",
        "OK", "STX", "WTX",
        // Call Area 6
        "EB", "LAX", "ORG", "SB", "SCV",
        "SDG", "SF", "SJV",
        // Call Area 7
        "SV", "PAC", "AK", "AZ", "EWA",
        "ID", "MT", "NV",
        // Call Area 8
        "OR", "UT", "WWA", "WY", "MI",
        "OH", "WV", "IL",
        // Call Area 9
        "IN", "WI", "AB", "BC", "GH",
        "MB", "NB", "NL",
        // Canada (RAC)
        "NS", "ONE", "ONN", "ONS", "PE",
        "QC", "SK", "TER",
    )

    private val index: Map<String, Int> = all.withIndex().associate { (i, s) -> s to i }

    fun indexOf(section: String): Int = index[section] ?: -1

    fun isKnown(section: String): Boolean = index.containsKey(section)

    /**
     * The neighbouring section used by the Sweepstakes realism rule
     * (`ArrlSS.pas:507-517`): step back when the dice say so, but always step
     * back from the end of the table and always step forward from the start.
     *
     * @param roll a seeded draw in `[0, 1)`
     * @return the neighbour, or `null` when [section] is not an ARRL/RAC section
     */
    fun neighbourOf(section: String, roll: Double): String? {
        val position = indexOf(section)
        if (position < 0) return null
        val stepBack = (roll < 0.5 && position > 0) || position == all.lastIndex
        return all[if (stepBack) position - 1 else minOf(position + 1, all.lastIndex)]
    }
}
