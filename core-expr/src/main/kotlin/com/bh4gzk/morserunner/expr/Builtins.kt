package com.bh4gzk.morserunner.expr

/**
 * Callsign facts that the language cannot derive by itself.
 *
 * Spec §5 lists `callPrefix`, `callZone`, `callCountry` and `arrlSectionOf` as v1
 * built-ins, but the data behind them — `DXCC.LIST`, the ARRL section table
 * (spec §9) — is loaded by later tickets (T3 loader, T5 roster). This port keeps
 * the expression module free of data loading: it asks for a fact and gets
 * `null` when the fact is not known.
 *
 * A `null` answer makes the built-in **fail closed** with
 * [ErrorKind.CALL_DATA_MISSING]; the language never guesses a zone or country.
 * [Unavailable] is the default until a caller supplies a real provider.
 */
interface CallFacts {

    fun prefix(call: String): String?

    /** DXCC CQ zone, integral; `null` when unknown. */
    fun zone(call: String): Double?

    fun country(call: String): String?

    fun arrlSection(call: String): String?

    companion object {
        /** Provider that knows nothing: every lookup fails closed. */
        val Unavailable: CallFacts = object : CallFacts {
            override fun prefix(call: String): String? = null
            override fun zone(call: String): Double? = null
            override fun country(call: String): String? = null
            override fun arrlSection(call: String): String? = null
        }
    }
}

/**
 * The v1 built-in catalogue (spec §5) and its arity rules.
 *
 * Keeping the table here means the *parser* can reject an unknown function or a
 * wrong argument count at compile time — a broken contest config is caught when
 * it is loaded (T3), not in the middle of a QSO.
 */
internal object Builtins {

    class Signature(val name: String, val minArguments: Int, val maxArguments: Int) {
        fun accepts(argumentCount: Int): Boolean = argumentCount in minArguments..maxArguments

        fun describeArity(): String = when {
            minArguments == maxArguments && minArguments == 1 -> "1 argument"
            minArguments == maxArguments -> "$minArguments arguments"
            else -> "$minArguments to $maxArguments arguments"
        }
    }

    private val signatures: Map<String, Signature> = listOf(
        Signature("upper", 1, 1),
        Signature("lower", 1, 1),
        Signature("len", 1, 1),
        Signature("substr", 2, 3),
        Signature("matches", 2, 2),
        Signature("num", 1, 1),
        Signature("callPrefix", 1, 1),
        Signature("callZone", 1, 1),
        Signature("callCountry", 1, 1),
        Signature("arrlSectionOf", 1, 1),
        Signature("unique", 1, 1),
        Signature("count", 1, 1),
        Signature("sum", 1, 1),
        Signature("ifElse", 3, 3),
    ).associateBy { it.name }

    fun validate(name: String, argumentCount: Int, position: SourcePosition) {
        val signature = signatures[name]
            ?: fail(position, ErrorKind.UNKNOWN_FUNCTION, "unknown function '$name'")
        if (!signature.accepts(argumentCount)) {
            fail(
                position,
                ErrorKind.WRONG_ARITY,
                "'$name' expects ${signature.describeArity()}, got $argumentCount",
            )
        }
    }
}
