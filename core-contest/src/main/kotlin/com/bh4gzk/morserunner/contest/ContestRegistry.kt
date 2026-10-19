package com.bh4gzk.morserunner.contest

/**
 * Registry of contest definitions.
 *
 * T1: only knows the identity of the 12 bundled contests; loading and validating the
 * JSON documents arrives with T3 (ADR-0002, ADR-0003).
 */
object ContestRegistry {

    /** Upstream contest ids, in the order the reference program lists them. */
    val BUNDLED_IDS: List<String> = listOf(
        "arrldx",   // ARRL DX
        "arrlfd",   // ARRL Field Day
        "arrlss",   // ARRL Sweepstakes
        "cqwpx",    // CQ WPX
        "cqww",     // CQ WW
        "iaruhf",   // IARU HF
        "naqp",     // NCJ NAQP
        "allja",    // JARL ALL JA
        "acag",     // JARL ACAG
        "cwops",    // CWops CWT
        "k1usnsst", // K1USN Slow Speed Test
        "hst"       // HST
    )

    fun bundledIds(): List<String> = BUNDLED_IDS
}
