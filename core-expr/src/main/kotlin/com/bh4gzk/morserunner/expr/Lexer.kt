package com.bh4gzk.morserunner.expr

/** Token shapes of the language. Keywords are their own types, not identifiers. */
internal enum class TokenType {
    NUMBER,
    STRING,
    IDENTIFIER,
    TRUE,
    FALSE,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    PERCENT,
    EQUAL_EQUAL,
    BANG_EQUAL,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    AND,
    OR,
    NOT,
    IN,
    QUESTION,
    COLON,
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    COMMA,
    END_OF_INPUT,
}

/**
 * A lexeme plus its start position.
 *
 * [literal] carries the decoded payload for [TokenType.NUMBER] (a [Double]),
 * [TokenType.STRING] (the unescaped [String]) and [TokenType.TRUE]/[TokenType.FALSE]
 * (a [Boolean]); it is null for punctuation.
 */
internal data class Token(
    val type: TokenType,
    val lexeme: String,
    val position: SourcePosition,
    val literal: Any? = null,
)

/**
 * Hand-written scanner for the expression language (spec §5).
 *
 * Deliberate choices, all recorded here so later tickets do not re-litigate them:
 *  - Identifiers are ASCII (`[A-Za-z_][A-Za-z0-9_]*`). Contest config keys and
 *    exchange fields are ASCII; a non-ASCII character is a lexical error with a
 *    precise position rather than a silently accepted name.
 *  - Numbers are `digits [ "." digits ] [ "e" sign? digits ]`. A leading-dot form
 *    (`.5`) and a trailing-dot form (`5.`) are rejected; both are ambiguous next
 *    to the `.` that a future schema might use.
 *  - Strings accept single or double quotes and the escapes `\\ \' \" \n \r \t \uXXXX`;
 *    any other escape is an error, not a literal backslash, so config authors
 *    find out immediately instead of shipping `\d+` as a regex by accident.
 *  - Columns count code points, so an error after an emoji still points at the
 *    right character (see [SourcePosition]).
 *
 * The scanner reports its first error by throwing [ExprFailure]; the public
 * API converts that into a [CompileResult.Failure]. It never throws anything else.
 */
internal class Lexer(private val source: String) {

    private val tokens = mutableListOf<Token>()
    private var offset = 0
    private var line = 1
    private var column = 1

    fun tokenize(): List<Token> {
        while (!isAtEnd()) {
            val c = source[offset]
            when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' -> advance()
                c == '(' -> single(TokenType.LEFT_PAREN)
                c == ')' -> single(TokenType.RIGHT_PAREN)
                c == '[' -> single(TokenType.LEFT_BRACKET)
                c == ']' -> single(TokenType.RIGHT_BRACKET)
                c == ',' -> single(TokenType.COMMA)
                c == '?' -> single(TokenType.QUESTION)
                c == ':' -> single(TokenType.COLON)
                c == '+' -> single(TokenType.PLUS)
                c == '-' -> single(TokenType.MINUS)
                c == '*' -> single(TokenType.STAR)
                c == '/' -> single(TokenType.SLASH)
                c == '%' -> single(TokenType.PERCENT)
                c == '=' -> equalityOperator()
                c == '!' -> bangOperator()
                c == '<' -> comparisonOperator(TokenType.LESS, TokenType.LESS_EQUAL)
                c == '>' -> comparisonOperator(TokenType.GREATER, TokenType.GREATER_EQUAL)
                c == '&' -> fail(position(), ErrorKind.LEXICAL, "unexpected '&'; use 'and'")
                c == '|' -> fail(position(), ErrorKind.LEXICAL, "unexpected '|'; use 'or'")
                c == '\'' || c == '"' -> string(c)
                c in '0'..'9' -> number()
                isIdentifierStart(c) -> identifier()
                else -> fail(
                    position(),
                    ErrorKind.LEXICAL,
                    "unexpected character '${codePointAt(offset)}'",
                )
            }
        }
        tokens += Token(TokenType.END_OF_INPUT, "", position())
        return tokens
    }

    // --- scanning primitives -------------------------------------------------

    private fun isAtEnd(): Boolean = offset >= source.length

    private fun peek(): Char? = if (isAtEnd()) null else source[offset]

    private fun advance(): Char {
        val start = offset
        val codePoint = source.codePointAt(start)
        offset += Character.charCount(codePoint)
        if (codePoint == '\n'.code) {
            line += 1
            column = 1
        } else {
            column += 1
        }
        return source[start]
    }

    /** Consume [expected] if it is the very next character. */
    private fun matchNext(expected: Char): Boolean {
        if (!isAtEnd() && source[offset] == expected) {
            advance()
            return true
        }
        return false
    }

    private fun position(): SourcePosition = SourcePosition(offset, line, column)

    private fun single(type: TokenType) {
        val start = position()
        val lexeme = source[offset].toString()
        advance()
        tokens += Token(type, lexeme, start)
    }

    private fun equalityOperator() {
        val start = position()
        advance()
        if (matchNext('=')) {
            tokens += Token(TokenType.EQUAL_EQUAL, "==", start)
        } else {
            fail(start, ErrorKind.LEXICAL, "unexpected '='; did you mean '=='?")
        }
    }

    private fun bangOperator() {
        val start = position()
        advance()
        if (matchNext('=')) {
            tokens += Token(TokenType.BANG_EQUAL, "!=", start)
        } else {
            fail(start, ErrorKind.LEXICAL, "unexpected '!'; use 'not'")
        }
    }

    private fun comparisonOperator(single: TokenType, followedByEquals: TokenType) {
        val start = position()
        val first = source[offset]
        advance()
        if (matchNext('=')) {
            tokens += Token(followedByEquals, "$first=", start)
        } else {
            tokens += Token(single, first.toString(), start)
        }
    }

    private fun number() {
        val start = position()
        val startOffset = offset
        while (peek()?.let { it in '0'..'9' } == true) advance()
        if (peek() == '.' && source.getOrNull(offset + 1)?.let { it in '0'..'9' } == true) {
            advance() // '.'
            while (peek()?.let { it in '0'..'9' } == true) advance()
        }
        if (peek() == 'e' || peek() == 'E') {
            var probe = offset + 1
            if (source.getOrNull(probe) == '+' || source.getOrNull(probe) == '-') probe += 1
            if (source.getOrNull(probe)?.let { it in '0'..'9' } == true) {
                advance() // 'e' / 'E'
                if (peek() == '+' || peek() == '-') advance()
                while (peek()?.let { it in '0'..'9' } == true) advance()
            }
        }
        val lexeme = source.substring(startOffset, offset)
        val value = lexeme.toDoubleOrNull()
        if (value == null || !value.isFinite()) {
            fail(start, ErrorKind.NUMERIC_OVERFLOW, "number literal '$lexeme' is not finite")
        }
        tokens += Token(TokenType.NUMBER, lexeme, start, value)
    }

    private fun string(quote: Char) {
        val start = position()
        advance() // opening quote
        val decoded = StringBuilder()
        while (!isAtEnd()) {
            val c = source[offset]
            when {
                c == quote -> {
                    advance()
                    tokens += Token(TokenType.STRING, source.substring(start.offset, offset), start, decoded.toString())
                    return
                }
                c == '\\' -> {
                    val escapeStart = position()
                    advance()
                    if (isAtEnd()) fail(start, ErrorKind.LEXICAL, "unterminated string literal")
                    val escaped = source[offset]
                    advance()
                    when (escaped) {
                        'n' -> decoded.append('\n')
                        'r' -> decoded.append('\r')
                        't' -> decoded.append('\t')
                        '\\' -> decoded.append('\\')
                        '\'' -> decoded.append('\'')
                        '"' -> decoded.append('"')
                        'u' -> decoded.append(unicodeEscape(escapeStart))
                        else -> fail(escapeStart, ErrorKind.LEXICAL, "unknown escape '\\$escaped'")
                    }
                }
                else -> {
                    decoded.appendCodePoint(source.codePointAt(offset))
                    advance()
                }
            }
        }
        fail(start, ErrorKind.LEXICAL, "unterminated string literal")
    }

    private fun unicodeEscape(escapeStart: SourcePosition): Char {
        val digits = StringBuilder(4)
        repeat(4) {
            if (isAtEnd() || !source[offset].isHexDigit()) {
                fail(escapeStart, ErrorKind.LEXICAL, "invalid unicode escape (expected 4 hexadecimal digits)")
            }
            digits.append(advance())
        }
        val codeUnit = digits.toString().toInt(radix = 16).toChar()
        if (Character.isSurrogate(codeUnit)) {
            // A lone UTF-16 surrogate is not a character; write the real character
            // in the config instead of a half pair.
            fail(escapeStart, ErrorKind.LEXICAL, "invalid unicode escape (lone surrogate)")
        }
        return codeUnit
    }

    private fun identifier() {
        val start = position()
        val startOffset = offset
        while (peek()?.let(::isIdentifierPart) == true) advance()
        val lexeme = source.substring(startOffset, offset)
        val type = when (lexeme) {
            "true" -> TokenType.TRUE
            "false" -> TokenType.FALSE
            "and" -> TokenType.AND
            "or" -> TokenType.OR
            "not" -> TokenType.NOT
            "in" -> TokenType.IN
            else -> TokenType.IDENTIFIER
        }
        val literal = when (type) {
            TokenType.TRUE -> true
            TokenType.FALSE -> false
            else -> null
        }
        tokens += Token(type, lexeme, start, literal)
    }

    private fun codePointAt(index: Int): String {
        val codePoint = source.codePointAt(index)
        return String(Character.toChars(codePoint))
    }

    private companion object {
        fun isIdentifierStart(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z'
        fun isIdentifierPart(c: Char): Boolean = isIdentifierStart(c) || c in '0'..'9'
        fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
