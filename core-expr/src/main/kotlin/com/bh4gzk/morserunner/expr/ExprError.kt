package com.bh4gzk.morserunner.expr

/**
 * A position inside an expression document.
 *
 * [offset] is a UTF-16 index into the source string (what Kotlin string APIs use).
 * [line] and [column] are **1-based** and counted in **Unicode code points**, not
 * UTF-16 units, so the column still points at the character a person sees even
 * when an emoji or an astral-plane character appears earlier on the line.
 *
 * The spec (§5) requires errors to be user-visible in the contest editor as
 * `line:col — message`; [ExprError.render] produces exactly that.
 */
data class SourcePosition(
    val offset: Int,
    val line: Int,
    val column: Int,
)

/**
 * Why an expression failed to compile or evaluate.
 *
 * Callers are expected to branch on this type — it is part of the public
 * contract, not a debugging aid. Every failure of the language is one of these
 * kinds; nothing in this module throws for bad *input*.
 */
enum class ErrorKind {
    /** The character stream is malformed (unterminated string, stray character …). */
    LEXICAL,

    /** The token stream is not a valid expression (missing token, trailing tokens …). */
    SYNTAX,

    /** An operator or built-in received a value of the wrong type. */
    TYPE,

    /** An identifier is not present in the environment supplied by the caller. */
    UNKNOWN_IDENTIFIER,

    /** A function name is not one of the v1 built-ins (caught at compile time). */
    UNKNOWN_FUNCTION,

    /** A built-in was called with the wrong number of arguments (caught at compile time). */
    WRONG_ARITY,

    /** `/` or `%` with a zero right-hand side. */
    DIVISION_BY_ZERO,

    /** An arithmetic result is no longer a finite IEEE-754 double. */
    NUMERIC_OVERFLOW,

    /** The evaluation step budget ran out — the language is deliberately bounded. */
    STEP_BUDGET,

    /** A `matches` pattern is not a valid regular expression. */
    BAD_REGEX,

    /** A built-in argument was well-typed but out of range (e.g. `substr` start < 1). */
    BAD_ARGUMENT,

    /**
     * A callsign built-in was evaluated without a [CallFacts] provider that knows
     * the call. The language never guesses contest data.
     */
    CALL_DATA_MISSING,
}

/**
 * A typed, user-presentable expression failure.
 *
 * [render] is the exact string the contest editor shows: `line:col — message`
 * (em dash, as specified in §5).
 */
data class ExprError(
    val kind: ErrorKind,
    val position: SourcePosition,
    val message: String,
) {
    fun render(): String = "${position.line}:${position.column} \u2014 $message"

    override fun toString(): String = render()
}

/**
 * Internal control-flow carrier: the public API turns this into
 * [CompileResult.Failure] / [EvalResult.Failure] and never lets it escape.
 */
internal class ExprFailure(val error: ExprError) : RuntimeException(error.render())

/** Abort the current compile/evaluate pass with a typed [error]. */
internal fun fail(position: SourcePosition, kind: ErrorKind, message: String): Nothing =
    throw ExprFailure(ExprError(kind, position, message))
