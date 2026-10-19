package com.bh4gzk.morserunner.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The callsign grammar shared by the call-history loaders (`Lexer.IsValidCall`,
 * used by `ArrlSS.pas:116`).
 *
 * The positive cases are transcribed from the upstream lexer's own golden table
 * (`Test/SSLexerTest.pas`, category `SSRules`: `Callsign.1`..`Callsign.10`); the
 * negative cases are the forms that table implies (a call sign needs a digit and
 * a trailing letter, and at most a prefix and two suffixes).
 */
class CallsignTest {

    @Test
    fun `the upstream golden callsigns are valid`() {
        // Test/SSLexerTest.pas Callsign.1..Callsign.10.
        listOf(
            "W7SST",
            "W7SST/5",
            "W7SST/QRP",
            "KP4/W7SST",
            "KP4/W7SST/QRP",
            "W7S",
            "W7SS",
            "WN7SST",
            "WA7S",
            "WA7SS",
        ).forEach { assertTrue(Callsign.isValid(it), "'$it' should be a valid callsign") }
    }

    @Test
    fun `shapes without a digit or a trailing letter are rejected`() {
        listOf(
            "",
            "ABC",          // no digit
            "123",          // no letter at all
            "W7SST/",       // empty suffix
            "/W7SST",       // empty prefix
            "W7SST/5/QRP/X", // three suffixes
            "W7SST 5",      // a space is not part of a call
            "W7?ST",        // '?' is a search wildcard, not a call
        ).forEach { assertFalse(Callsign.isValid(it), "'$it' should not be a valid callsign") }
    }

    @Test
    fun `the real contest call-history spellings parse`() {
        // Straight from the shipped history files' own first data rows.
        listOf("2E0AQQ", "4U1WB", "AA0A", "3D2AG", "2E0CVN", "9A1A", "JY4CI", "S50A", "VP2E", "E51ABC")
            .forEach { assertTrue(Callsign.isValid(it), "'$it' should be a valid callsign") }
    }

    @Test
    fun `validation normalises case and surrounding whitespace`() {
        assertTrue(Callsign.isValid("  w7sst "))
        assertEquals("W7SST", Callsign.normalise("  w7sst "))
    }
}
