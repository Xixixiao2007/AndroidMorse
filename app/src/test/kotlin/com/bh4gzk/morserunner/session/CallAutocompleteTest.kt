package com.bh4gzk.morserunner.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Callsign completion for the entry box (spec §8: "call field, soft keyboard, autocomplete from
 * call history").
 *
 * It is a hint, never a decoder: it only ever returns calls the **contest's call-history file**
 * already lists, it is silent until the operator types something, and `?` — which the operator
 * uses as a wildcard for a half-copied call (`DxOper.pas:428-431`) — is left alone.
 */
class CallAutocompleteTest {

    private val calls = listOf(
        "DL1ABC", "DL2XYZ", "DL7ABC", "K1ABC", "JA1ABC", "W1AW", "DL1AB", "DL1AB",
    )

    @Test
    fun `completes on a prefix, case-insensitively, sorted, without duplicates`() {
        assertEquals(
            listOf("DL1AB", "DL1ABC"),
            CallAutocomplete.suggest(calls, "dl1"),
        )
        assertEquals(
            listOf("DL1AB", "DL1ABC", "DL2XYZ", "DL7ABC"),
            CallAutocomplete.suggest(calls, "DL"),
        )
    }

    @Test
    fun `nothing is suggested before the operator types`() {
        assertEquals(emptyList(), CallAutocomplete.suggest(calls, ""))
        assertEquals(emptyList(), CallAutocomplete.suggest(calls, "   "))
    }

    @Test
    fun `a question mark is a wildcard, not a completion trigger`() {
        // `DL?` is how an operator enters a half-copied call; completing it would defeat that.
        assertEquals(emptyList(), CallAutocomplete.suggest(calls, "DL1?"))
        assertEquals(emptyList(), CallAutocomplete.suggest(calls, "?"))
    }

    @Test
    fun `an exactly typed call is not offered back`() {
        assertEquals(emptyList(), CallAutocomplete.suggest(calls, "W1AW"))
    }

    @Test
    fun `the list is capped`() {
        val many = (1..50).map { "K$it\$/XYZ".replace("\$", "") }
        val suggestions = CallAutocomplete.suggest(many, "K", limit = 8)
        assertEquals(8, suggestions.size)
        assertEquals(suggestions.sorted(), suggestions, "suggestions are ordered deterministically")
    }

    @Test
    fun `an unknown prefix yields nothing`() {
        assertEquals(emptyList(), CallAutocomplete.suggest(calls, "ZZ"))
    }
}
