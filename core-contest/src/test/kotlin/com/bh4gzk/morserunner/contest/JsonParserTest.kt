package com.bh4gzk.morserunner.contest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The strict JSON reader the contest loader stands on (ADR-0004 §8). A contest
 * config is hand-written data, so "reject with a position" beats "guess".
 */
class JsonParserTest {

    private fun parse(text: String): JsonValue =
        when (val result = Json.parse(text)) {
            is JsonParseResult.Success -> result.value
            is JsonParseResult.Failure -> throw AssertionError("unexpected failure: ${result.error.render()}")
        }

    private fun failure(text: String): JsonSyntaxError {
        val result = Json.parse(text)
        assertIs<JsonParseResult.Failure>(result, "expected a parse failure for: $text")
        return result.error
    }

    @Test
    fun `parses the shapes a contest config is made of`() {
        val value = parse("""{"a": 1, "b": [true, false, null], "c": {"d": "e"}}""")
        val obj = assertIs<JsonValue.Obj>(value)
        assertEquals(1.0, assertIs<JsonValue.Num>(obj["a"]).value)
        assertEquals(3, assertIs<JsonValue.Arr>(obj["b"]).items.size)
        assertEquals("e", assertIs<JsonValue.Str>(assertIs<JsonValue.Obj>(obj["c"])["d"]).value)
    }

    @Test
    fun `preserves the document order of object keys because derive order is meaning`() {
        val obj = assertIs<JsonValue.Obj>(parse("""{"first": 1, "second": 2, "third": 3}"""))
        assertEquals(listOf("first", "second", "third"), obj.entries.keys.toList())
    }

    @Test
    fun `accepts empty containers`() {
        assertEquals(0, assertIs<JsonValue.Obj>(parse("{}")).entries.size)
        assertEquals(0, assertIs<JsonValue.Arr>(parse("[]")).items.size)
    }

    @Test
    fun `parses numbers with sign fraction and exponent`() {
        assertEquals(-12.0, assertIs<JsonValue.Num>(parse("-12")).value)
        assertEquals(0.5, assertIs<JsonValue.Num>(parse("0.5")).value)
        assertEquals(1500.0, assertIs<JsonValue.Num>(parse("1.5e3")).value)
        assertEquals(0.0, assertIs<JsonValue.Num>(parse("0")).value)
    }

    @Test
    fun `decodes escapes including a surrogate pair`() {
        val value = assertIs<JsonValue.Str>(parse(""""a\u0041\n\t\"\\""""))
        assertEquals("aA\n\t\"\\", value.value)
        val astral = assertIs<JsonValue.Str>(parse(""""\uD83D\uDE00""""))
        assertEquals("\uD83D\uDE00", astral.value)
    }

    @Test
    fun `rejects a duplicate key and points at the second occurrence`() {
        val error = failure("""{"scoring": 1, "scoring": 2}""")
        assertTrue(error.message.contains("duplicate key 'scoring'"), error.message)
        assertEquals(1, error.position.line)
    }

    @Test
    fun `rejects a trailing comma`() {
        assertTrue(failure("""{"a": 1,}""").message.contains("expected a string key"))
        assertTrue(failure("""[1,2,]""").message.contains("expected a value"))
    }

    @Test
    fun `rejects comments because configs are strict JSON`() {
        assertTrue(failure("""{"a": 1} // note""").message.contains("trailing content"))
        assertTrue(failure("""{/* note */ "a": 1}""").message.contains("expected a string key"))
    }

    @Test
    fun `rejects leading zeros`() {
        assertTrue(failure("012").message.contains("leading zeros"))
    }

    @Test
    fun `rejects an unescaped control character in a string`() {
        assertTrue(failure("\"a\nb\"").message.contains("control character"))
    }

    @Test
    fun `rejects a truncated or non-hex unicode escape`() {
        assertTrue(failure(""""\u12"""").message.contains("truncated"))
        assertTrue(failure(""""\uZZZZ"""").message.contains("invalid \\u escape"))
    }

    @Test
    fun `rejects unterminated strings and containers`() {
        assertTrue(failure("\"abc").message.contains("unterminated string"))
        assertTrue(failure("""{"a": 1""").message.contains("expected ',' or '}'"))
        assertTrue(failure("[1, 2").message.contains("expected ',' or ']'"))
    }

    @Test
    fun `reports a 1-based line and column on a later line`() {
        val error = failure("{\n  \"a\": 1,\n  \"b\" 2\n}")
        assertEquals(3, error.position.line)
        assertEquals(7, error.position.column)
        assertTrue(error.message.contains("expected ':'"), error.message)
        assertTrue(error.render().startsWith("3:7 \u2014 "), error.render())
    }
}
