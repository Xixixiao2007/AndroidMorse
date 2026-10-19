package com.bh4gzk.morserunner.contest

/**
 * Position inside a JSON document. [line] and [column] are 1-based; columns
 * count UTF-16 code units (contest configs are ASCII in practice — the
 * expression module reports its own positions in code points).
 */
data class JsonPosition(val line: Int, val column: Int)

/**
 * A parsed JSON value.
 *
 * Objects keep their **document order** ([Obj.entries] is a LinkedHashMap): the
 * `their.derive` block of a contest config is evaluated top to bottom and a
 * later entry may read an earlier one, so order is part of the meaning.
 *
 * There is no "any" type: the loader walks this tree and reports the exact path
 * of anything it did not expect.
 */
sealed interface JsonValue {

    val position: JsonPosition

    data class Obj(
        val entries: Map<String, JsonValue>,
        override val position: JsonPosition,
    ) : JsonValue {
        operator fun get(key: String): JsonValue? = entries[key]
    }

    data class Arr(val items: List<JsonValue>, override val position: JsonPosition) : JsonValue

    data class Str(val value: String, override val position: JsonPosition) : JsonValue

    data class Num(val value: Double, override val position: JsonPosition) : JsonValue

    data class Bool(val value: Boolean, override val position: JsonPosition) : JsonValue

    data class Null(override val position: JsonPosition) : JsonValue
}

/** Why a document is not JSON. [render] is the `line:col — message` the spec requires. */
data class JsonSyntaxError(val position: JsonPosition, val message: String) {
    fun render(): String = "${position.line}:${position.column} \u2014 $message"
    override fun toString(): String = render()
}

sealed interface JsonParseResult {
    data class Success(val value: JsonValue) : JsonParseResult
    data class Failure(val error: JsonSyntaxError) : JsonParseResult
}

/**
 * A strict RFC 8259 parser — hand written so this module stays dependency-free.
 *
 * Strictness is deliberate: no comments, no trailing commas, **no duplicate
 * object keys** (a duplicated `scoring` or `dice` entry would silently change
 * meaning), exactly one top-level value. Contest configs are authored by hand,
 * so an unhelpful parse is worse than a rejected one.
 */
object Json {

    fun parse(text: String): JsonParseResult = try {
        JsonParseResult.Success(Parser(text).parseDocument())
    } catch (failure: JsonFailure) {
        JsonParseResult.Failure(failure.error)
    }

    private class JsonFailure(val error: JsonSyntaxError) : RuntimeException(error.render())

    private class Parser(private val text: String) {

        private var index = 0
        private var line = 1
        private var column = 1

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (!atEnd()) fail("unexpected trailing content after the top-level value")
            return value
        }

        // --- structure ----------------------------------------------------

        private fun parseValue(): JsonValue {
            if (atEnd()) fail("unexpected end of document, expected a value")
            return when (val char = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", JsonValue.Bool(true, position()))
                'f' -> literal("false", JsonValue.Bool(false, position()))
                'n' -> literal("null", JsonValue.Null(position()))
                else -> if (char == '-' || char.isDigit()) parseNumber() else {
                    fail("unexpected character '$char', expected a value")
                }
            }
        }

        private fun parseObject(): JsonValue {
            val start = position()
            expect('{')
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                advance()
                return JsonValue.Obj(entries, start)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a string key, found '${describe(peek())}'")
                val keyPosition = position()
                val key = (parseString() as JsonValue.Str).value
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = parseValue()
                if (entries.put(key, value) != null) {
                    throw JsonFailure(
                        JsonSyntaxError(keyPosition, "duplicate key '$key'"),
                    )
                }
                skipWhitespace()
                when (peek()) {
                    ',' -> advance()
                    '}' -> {
                        advance()
                        return JsonValue.Obj(entries, start)
                    }
                    else -> fail("expected ',' or '}' in the object, found '${describe(peek())}'")
                }
            }
        }

        private fun parseArray(): JsonValue {
            val start = position()
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                advance()
                return JsonValue.Arr(items, start)
            }
            while (true) {
                skipWhitespace()
                items += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> advance()
                    ']' -> {
                        advance()
                        return JsonValue.Arr(items, start)
                    }
                    else -> fail("expected ',' or ']' in the array, found '${describe(peek())}'")
                }
            }
        }

        private fun parseString(): JsonValue {
            val start = position()
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string", start)
                val char = text[index]
                when {
                    char == '"' -> {
                        advance()
                        return JsonValue.Str(out.toString(), start)
                    }
                    char == '\\' -> out.append(parseEscape(start))
                    char < ' ' -> fail("unescaped control character U+${char.code.toString(16)} in a string")
                    else -> {
                        out.append(char)
                        advance()
                    }
                }
            }
        }

        private fun parseEscape(start: JsonPosition): Char {
            advance() // the backslash
            if (atEnd()) fail("unterminated escape sequence", start)
            val escape = text[index]
            advance()
            return when (escape) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape()
                else -> fail("invalid escape '\\$escape'", start)
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > text.length) fail("truncated \\u escape")
            val digits = text.substring(index, index + 4)
            if (digits.length != 4 || digits.any { it.digitToIntOrNull(16) == null }) {
                fail("invalid \\u escape '$digits'")
            }
            repeat(4) { advance() }
            return digits.toInt(16).toChar()
        }

        private fun parseNumber(): JsonValue {
            val start = position()
            val begin = index
            if (peek() == '-') advance()
            if (atEnd() || !text[index].isDigit()) fail("expected a digit in the number")
            if (text[index] == '0') {
                advance()
                if (!atEnd() && text[index].isDigit()) fail("numbers must not have leading zeros", start)
            } else {
                while (!atEnd() && text[index].isDigit()) advance()
            }
            if (peek() == '.') {
                advance()
                if (atEnd() || !text[index].isDigit()) fail("expected a digit after the decimal point")
                while (!atEnd() && text[index].isDigit()) advance()
            }
            if (peek() == 'e' || peek() == 'E') {
                advance()
                if (peek() == '+' || peek() == '-') advance()
                if (atEnd() || !text[index].isDigit()) fail("expected a digit in the exponent")
                while (!atEnd() && text[index].isDigit()) advance()
            }
            val literal = text.substring(begin, index)
            val value = literal.toDoubleOrNull()
                ?: fail("'$literal' is not a valid number", start)
            if (!value.isFinite()) fail("'$literal' is out of range for a double", start)
            return JsonValue.Num(value, start)
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) fail("expected '$word'")
            repeat(word.length) { advance() }
            return value
        }

        // --- scanning -----------------------------------------------------

        private fun atEnd(): Boolean = index >= text.length

        private fun peek(): Char? = if (atEnd()) null else text[index]

        private fun describe(char: Char?): String = char?.toString() ?: "end of document"

        private fun position(): JsonPosition = JsonPosition(line, column)

        private fun expect(expected: Char) {
            if (peek() != expected) fail("expected '$expected', found '${describe(peek())}'")
            advance()
        }

        private fun skipWhitespace() {
            while (!atEnd() && (text[index] == ' ' || text[index] == '\t' ||
                    text[index] == '\n' || text[index] == '\r')) {
                advance()
            }
        }

        private fun advance() {
            if (atEnd()) return
            if (text[index] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
            index++
        }

        private fun fail(message: String, at: JsonPosition = position()): Nothing =
            throw JsonFailure(JsonSyntaxError(at, message))
    }
}
