package com.bh4gzk.morserunner.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden cases transcribed from upstream `Test/CallsignUtilsTest.pas`.
 *
 * Keeping the same inputs as the Pascal suite is the point: when the port and
 * upstream disagree, the test names which call disagrees.
 */
class CallPrefixTest {

    /** call, WPX prefix (trailing letters removed), DXCC prefix (longest match). */
    private val prefixes = listOf(
        Triple("K1ABC", "K1", "K1ABC"),
        Triple("DL2XYZ", "DL2", "DL2XYZ"),
        Triple("F4ABC", "F4", "F4ABC"),
        Triple("sp4xyz", "sp4", "sp4xyz"),
        Triple("", "", ""),
        Triple("W7SST", "W7", "W7SST"),
        Triple("W7SST/7", "W7", "W7SST"),
        Triple("W7SST/6", "W6", "W7SST"),
        Triple("N7SST/6", "N6", "N7SST"),
        Triple("F/W7SST", "F0", "F"),
        Triple("F6/W7SST", "F6", "F6"),
        Triple("W7SST/W", "W0", "W"),
        Triple("F6FVY/W7", "W7", "W7"),
        Triple("F6/AB7Q", "F6", "F6"),
        Triple("F6/W7SST/P", "F6", "F6"),
        Triple("W7SST/W/QRP", "W0", "W"),
        Triple("F6FVY/W7/MM", "W7", "W7"),
        Triple("DL/AA1ZZ", "DL0", "DL"),
        Triple("OE/F1ABC", "OE0", "OE"),
        Triple("PA0/AA1ZZ", "PA0", "PA0"),
        Triple("DL/HA5VJ", "DL0", "DL"),
        Triple("F/DJ4MZ", "F0", "F"),
        Triple("TK/IK1BPL", "TK0", "TK"),
        Triple("3A/F1ABC", "3A0", "3A"),
        Triple("I2/AA1ZZ/MM", "I2", "I2"),
        Triple("C3/F1ABC", "C3", "C3"),
        Triple("CT3ABC", "CT3", "CT3ABC"),
        Triple("SV5ABC", "SV5", "SV5ABC"),
        Triple("J49N", "J49", "J49N"),
        Triple("OH0A", "OH0", "OH0A"),
        Triple("JW7ABC", "JW7", "JW7ABC"),
        Triple("VU2ABC", "VU2", "VU2ABC"),
        Triple("BY1ABC", "BY1", "BY1ABC"),
        Triple("A9AB", "A9", "A9AB"),
        Triple("BS7H", "BS7", "BS7H"),
        Triple("JY3CW", "JY3", "JY3CW"),
        Triple("CN8CN", "CN8", "CN8CN"),
        Triple("ZS6ZS", "ZS6", "ZS6ZS"),
        // Incomplete and awkward constructions from the upstream edge cases.
        Triple("F6/", "F6", "F6"),
        Triple("F6/W7SST/MM", "F6", "F6"),
        Triple("F6/W7SST/W", "F6", "F6"),
    )

    @Test
    fun `wpx prefixes match the upstream golden data`() {
        for ((call, wpx, _) in prefixes) {
            assertEquals(wpx, CallPrefix.extractPrefix(call, deleteTrailingLetters = true), "WPX $call")
        }
    }

    @Test
    fun `dxcc prefixes match the upstream golden data`() {
        for ((call, _, dxcc) in prefixes) {
            assertEquals(dxcc, CallPrefix.extractPrefix(call, deleteTrailingLetters = false), "DXCC $call")
        }
    }

    @Test
    fun `the default mode is the WPX rules`() {
        assertEquals("DL2", CallPrefix.extractPrefix("DL2XYZ"))
        assertEquals("DL2XYZ", CallPrefix.extractPrefix("DL2XYZ", deleteTrailingLetters = false))
    }

    /** Valid and invalid callsigns, from the ExtractCallsign part of the Pascal suite. */
    private val calls = listOf(
        "F6/W7SST" to "W7SST",
        "DL/AA1ZZ" to "AA1ZZ",
        "OE/F1ABC" to "F1ABC",
        "F6/W7SST/P" to "W7SST",
        "PA0/AA1ZZ" to "AA1ZZ",
        "" to "",
        "123ABC" to "",
        "@#CALL" to "",
        "F6/" to "",
        "K1ABC" to "K1ABC",
    )

    @Test
    fun `extractCall finds the callsign and rejects lookalikes`() {
        for ((input, expected) in calls) {
            assertEquals(expected, CallPrefix.extractCall(input), "extractCall($input)")
        }
    }

    @Test
    fun `a bare district digit replaces the prefix digit`() {
        // W7SST/6 is a W7 call operating in district 6: WPX wants W6, DXCC wants W7SST.
        assertEquals("W6", CallPrefix.extractPrefix("W7SST/6", deleteTrailingLetters = true))
        assertEquals("W7SST", CallPrefix.extractPrefix("W7SST/6", deleteTrailingLetters = false))
    }
}
