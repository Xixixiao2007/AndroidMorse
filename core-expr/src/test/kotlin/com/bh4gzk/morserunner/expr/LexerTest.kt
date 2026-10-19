package com.bh4gzk.morserunner.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The scanner is tested through its token stream, not through evaluation, so a
 * tokenising bug cannot hide behind correct semantics.
 */
class LexerTest {

    private fun types(source: String): List<TokenType> = Lexer(source).tokenize().map { it.type }

    private fun lexError(source: String): ExprError =
        assertFailsWith<ExprFailure> { Lexer(source).tokenize() }.error

    @Test
    fun `every operator and separator tokenises`() {
        assertEquals(
            listOf(
                TokenType.LEFT_PAREN, TokenType.RIGHT_PAREN,
                TokenType.LEFT_BRACKET, TokenType.RIGHT_BRACKET,
                TokenType.COMMA, TokenType.QUESTION, TokenType.COLON,
                TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT,
                TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL,
                TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL,
                TokenType.AND, TokenType.OR, TokenType.NOT, TokenType.IN,
                TokenType.END_OF_INPUT,
            ),
            types("( ) [ ] , ? : + - * / % == != < <= > >= and or not in"),
        )
    }

    @Test
    fun `keyword matching does not eat identifiers`() {
        // `android` starts with `and` but is one identifier.
        assertEquals(listOf(TokenType.IDENTIFIER, TokenType.END_OF_INPUT), types("android"))
        // Keywords are case-sensitive; `AND` is an identifier.
        assertEquals(listOf(TokenType.IDENTIFIER, TokenType.END_OF_INPUT), types("AND"))
        assertEquals(listOf(TokenType.TRUE, TokenType.END_OF_INPUT), types("true"))
        assertEquals(listOf(TokenType.FALSE, TokenType.END_OF_INPUT), types("false"))
    }

    @Test
    fun `number literal forms`() {
        assertEquals(listOf(TokenType.NUMBER, TokenType.END_OF_INPUT), types("42"))
        assertEquals(listOf(TokenType.NUMBER, TokenType.END_OF_INPUT), types("2.5"))
        assertEquals(listOf(TokenType.NUMBER, TokenType.END_OF_INPUT), types("1e3"))
        assertEquals(listOf(TokenType.NUMBER, TokenType.END_OF_INPUT), types("1E-2"))
        val values = Lexer("1 2.5 0.125 1e3 1E-2 6.02e23").tokenize()
            .filter { it.type == TokenType.NUMBER }
            .map { it.literal }
        assertEquals(listOf(1.0, 2.5, 0.125, 1000.0, 0.01, 6.02e23), values)
    }

    @Test
    fun `strings support both quote styles and escapes`() {
        val tokens = Lexer("""'it\'s' "say \"hi\"" 'a\nb\t\\' '\u0041'""").tokenize()
            .filter { it.type == TokenType.STRING }
            .map { it.literal }
        assertEquals(listOf("it's", "say \"hi\"", "a\nb\t\\", "A"), tokens)
    }

    @Test
    fun `string literals may span lines and keep positions correct`() {
        val tokens = Lexer("'a\nb' + x").tokenize()
        assertEquals("a\nb", tokens[0].literal)
        assertEquals(SourcePosition(offset = 6, line = 2, column = 4), tokens[1].position) // '+'
    }

    @Test
    fun `positions are 1-based lines and columns counted in code points`() {
        val tokens = Lexer("alpha\n  beta").tokenize()
        assertEquals(SourcePosition(offset = 0, line = 1, column = 1), tokens[0].position)
        assertEquals(SourcePosition(offset = 8, line = 2, column = 3), tokens[1].position)

        // '👍' is one code point but two UTF-16 units: the column must not skip.
        val emoji = Lexer("'👍' + x").tokenize()
        assertEquals(SourcePosition(offset = 5, line = 1, column = 5), emoji[1].position) // '+'
        assertEquals(SourcePosition(offset = 7, line = 1, column = 7), emoji[2].position) // 'x'
    }

    @Test
    fun `unterminated string points at its opening quote`() {
        val error = lexError("1 + 'abc")
        assertEquals(ErrorKind.LEXICAL, error.kind)
        assertEquals("unterminated string literal", error.message)
        assertEquals(1, error.position.line)
        assertEquals(5, error.position.column) // the quote, not end of input
    }

    @Test
    fun `bad escapes are rejected at the backslash`() {
        val error = lexError("'a\\q'")
        assertEquals("unknown escape '\\q'", error.message)
        assertEquals(3, error.position.column)
        assertTrue(lexError("'\\u00zz'").message.startsWith("invalid unicode escape"))
        assertTrue(lexError("'\\uD83D'").message.contains("lone surrogate"))
    }

    @Test
    fun `single equals and bare bang explain the operator they probably meant`() {
        assertEquals("unexpected '='; did you mean '=='?", lexError("a = b").message)
        assertEquals("unexpected '!'; use 'not'", lexError("a ! b").message)
    }

    @Test
    fun `word operators instead of symbols are pointed out`() {
        assertEquals("unexpected '&'; use 'and'", lexError("a && b").message)
        assertEquals("unexpected '|'; use 'or'", lexError("a || b").message)
    }

    @Test
    fun `unexpected character reports the character and its code-point column`() {
        val error = lexError("1 + 👍 2")
        assertEquals(ErrorKind.LEXICAL, error.kind)
        assertEquals("unexpected character '👍'", error.message)
        assertEquals(SourcePosition(offset = 4, line = 1, column = 5), error.position)
    }
}
