package com.bh4gzk.morserunner.expr

/**
 * The value domain of the contest expression language (spec §5).
 *
 * The language has exactly four value shapes and **no null** — the spec lists
 * numbers, strings, booleans and lists, and "fails closed" means there is no
 * poisoned value to propagate. Equality is structural (data-class equality),
 * which is what the `==`/`!=`/`in` operators use.
 *
 * Numbers are IEEE-754 doubles throughout: contest data (zones, serials,
 * points) is numeric and integer-valued, and one numeric type keeps the
 * language total. Non-finite results are rejected by the evaluator instead of
 * silently producing `Infinity` (see [ErrorKind.NUMERIC_OVERFLOW]).
 */
sealed interface Value {

    /** Type name used in user-visible error messages ("expected number, got string"). */
    val typeName: String

    data class Num(val value: Double) : Value {
        override val typeName: String get() = "number"
    }

    data class Str(val value: String) : Value {
        override val typeName: String get() = "string"
    }

    data class Bool(val value: Boolean) : Value {
        override val typeName: String get() = "boolean"
    }

    data class List(val items: kotlin.collections.List<Value>) : Value {
        override val typeName: String get() = "list"
    }

    companion object {
        val True: Bool = Bool(true)
        val False: Bool = Bool(false)

        fun num(value: Number): Num = Num(value.toDouble())
        fun str(value: String): Str = Str(value)
        fun bool(value: Boolean): Bool = Bool(value)
        fun list(vararg items: Value): List = List(items.toList())
        fun list(items: kotlin.collections.List<Value>): List = List(items)
    }
}
