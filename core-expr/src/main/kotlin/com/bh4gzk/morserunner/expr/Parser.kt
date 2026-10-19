package com.bh4gzk.morserunner.expr

/**
 * Maximum nesting allowed while parsing. A config that nests deeper is rejected
 * with a typed error instead of risking a [StackOverflowError] — "fails closed"
 * includes not dying on hostile input.
 */
private const val MAX_NESTING_DEPTH = 256

/**
 * Recursive-descent parser for the grammar in spec §5.
 *
 * Precedence, from loosest to tightest (recorded once so it is reviewable):
 * ternary → `or` → `and` → `not` → comparisons (`== != < <= > >= in`) →
 * additive (`+ -`) → multiplicative (`* / %`) → unary `-` → primary.
 *
 * Consequences worth knowing:
 *  - `not a == b` parses as `not (a == b)` — the reading humans expect;
 *  - `a ? b : c ? d : e` is right-associative;
 *  - calls are validated against the [Builtins] catalogue **here**, so an
 *    unknown function name or wrong argument count is a load-time error with a
 *    position instead of a surprise mid-QSO.
 */
internal class Parser(private val tokens: List<Token>) {

    private var current = 0
    private var depth = 0

    fun parse(): Node {
        if (isAtEnd()) fail(position(), ErrorKind.SYNTAX, "expression is empty")
        val expression = expression()
        if (!isAtEnd()) {
            fail(position(), ErrorKind.SYNTAX, "unexpected ${describe(peek())} after end of expression")
        }
        return expression
    }

    // --- grammar -------------------------------------------------------------

    private fun expression(): Node = nested { ternary() }

    private fun ternary(): Node {
        val condition = or()
        if (!match(TokenType.QUESTION)) return condition
        val whenTrue = expression()
        expect(TokenType.COLON, "expected ':' in conditional expression")
        val whenFalse = ternary()
        return Node.Conditional(condition, whenTrue, whenFalse, condition.position)
    }

    private fun or(): Node {
        var left = and()
        while (match(TokenType.OR)) {
            val operator = previous()
            left = Node.Binary(operator.type, left, and(), operator.position)
        }
        return left
    }

    private fun and(): Node {
        var left = not()
        while (match(TokenType.AND)) {
            val operator = previous()
            left = Node.Binary(operator.type, left, not(), operator.position)
        }
        return left
    }

    private fun not(): Node {
        if (match(TokenType.NOT)) {
            val operator = previous()
            return Node.Unary(operator.type, nested { not() }, operator.position)
        }
        return comparison()
    }

    private fun comparison(): Node {
        var left = additive()
        while (
            match(
                TokenType.EQUAL_EQUAL,
                TokenType.BANG_EQUAL,
                TokenType.LESS,
                TokenType.LESS_EQUAL,
                TokenType.GREATER,
                TokenType.GREATER_EQUAL,
                TokenType.IN,
            )
        ) {
            val operator = previous()
            left = Node.Binary(operator.type, left, additive(), operator.position)
        }
        return left
    }

    private fun additive(): Node {
        var left = multiplicative()
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            val operator = previous()
            left = Node.Binary(operator.type, left, multiplicative(), operator.position)
        }
        return left
    }

    private fun multiplicative(): Node {
        var left = unary()
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            val operator = previous()
            left = Node.Binary(operator.type, left, unary(), operator.position)
        }
        return left
    }

    private fun unary(): Node {
        if (match(TokenType.MINUS)) {
            val operator = previous()
            return Node.Unary(operator.type, nested { unary() }, operator.position)
        }
        return primary()
    }

    private fun primary(): Node {
        if (match(TokenType.NUMBER)) {
            val token = previous()
            return Node.NumberLiteral(token.literal as Double, token.position)
        }
        if (match(TokenType.STRING)) {
            val token = previous()
            return Node.StringLiteral(token.literal as String, token.position)
        }
        if (match(TokenType.TRUE, TokenType.FALSE)) {
            val token = previous()
            return Node.BooleanLiteral(token.type == TokenType.TRUE, token.position)
        }
        if (match(TokenType.LEFT_PAREN)) {
            val grouped = nested { expression() }
            expect(TokenType.RIGHT_PAREN, "expected ')'")
            return grouped
        }
        if (match(TokenType.LEFT_BRACKET)) {
            val open = previous()
            val items = mutableListOf<Node>()
            if (!check(TokenType.RIGHT_BRACKET)) {
                do {
                    items += nested { expression() }
                } while (match(TokenType.COMMA))
            }
            expect(TokenType.RIGHT_BRACKET, "expected ']' to close list literal")
            return Node.ListLiteral(items, open.position)
        }
        if (match(TokenType.IDENTIFIER)) {
            val name = previous()
            if (!match(TokenType.LEFT_PAREN)) {
                return Node.Identifier(name.lexeme, name.position)
            }
            val arguments = mutableListOf<Node>()
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    arguments += nested { expression() }
                } while (match(TokenType.COMMA))
            }
            expect(TokenType.RIGHT_PAREN, "expected ')' to close call to '${name.lexeme}'")
            Builtins.validate(name.lexeme, arguments.size, name.position)
            validateLiteralRegex(name.lexeme, arguments)
            return Node.Call(name.lexeme, arguments, name.position)
        }
        fail(position(), ErrorKind.SYNTAX, "expected an expression, found ${describe(peek())}")
    }

    /**
     * A literal regex argument is compiled at load time, so a typo in a contest
     * config is reported with `line:col` when the config is loaded (T3) rather
     * than when a QSO happens to reach that rule.
     */
    private fun validateLiteralRegex(name: String, arguments: List<Node>) {
        if (name != "matches" || arguments.size != 2) return
        val pattern = arguments[1] as? Node.StringLiteral ?: return
        try {
            Regex(pattern.value)
        } catch (exception: java.util.regex.PatternSyntaxException) {
            fail(
                pattern.position,
                ErrorKind.BAD_REGEX,
                "invalid regular expression: ${exception.description}",
            )
        }
    }

    // --- token plumbing -------------------------------------------------------

    private inline fun <T> nested(block: () -> T): T {
        depth += 1
        if (depth > MAX_NESTING_DEPTH) {
            fail(position(), ErrorKind.SYNTAX, "expression nests too deeply (max $MAX_NESTING_DEPTH)")
        }
        try {
            return block()
        } finally {
            depth -= 1
        }
    }

    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    private fun check(type: TokenType): Boolean = !isAtEnd() && peek().type == type

    private fun advance(): Token {
        if (!isAtEnd()) current += 1
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().type == TokenType.END_OF_INPUT

    private fun peek(): Token = tokens[current]

    private fun previous(): Token = tokens[current - 1]

    private fun position(): SourcePosition = peek().position

    private fun expect(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        fail(position(), ErrorKind.SYNTAX, message)
    }

    private fun describe(token: Token): String = when (token.type) {
        TokenType.END_OF_INPUT -> "end of expression"
        TokenType.NUMBER -> "number '${token.lexeme}'"
        TokenType.STRING -> "string"
        TokenType.IDENTIFIER -> "identifier '${token.lexeme}'"
        else -> "'${token.lexeme}'"
    }
}
