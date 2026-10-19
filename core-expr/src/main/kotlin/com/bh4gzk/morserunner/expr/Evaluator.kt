package com.bh4gzk.morserunner.expr

import java.util.regex.PatternSyntaxException

/**
 * Tree-walking evaluator: type checks every operation, enforces the step budget
 * and turns every failure into a typed [ExprError].
 *
 * Typing rules (spec §5 is silent on operands; these are the documented,
 * total choices — each one either succeeds for all values or fails closed):
 *  - `+` adds two numbers or concatenates two strings; mixed types are a 
 *    [ErrorKind.TYPE] error. (Concatenation is here because exchange formatting
 *    needs it and inventing a `concat` built-in instead would grow the language.)
 *  - `- * / % < <= > >=` are numbers only.
 *  - `==`/`!=` compare structurally and across all four value shapes; a type
 *    mismatch is simply `false`/`true`, never an error.
 *  - `and`/`or` are booleans and short-circuit; `not` is boolean.
 *  - `?:` requires a boolean condition and evaluates only the chosen branch.
 *  - `in` tests list membership (structural equality) or a substring when both
 *    sides are strings.
 *
 * Cost: every AST node costs one step; list built-ins additionally charge one
 * step per element they inspect, so a huge list cannot sneak past the budget.
 */
internal class Evaluator(
    private val environment: Map<String, Value>,
    private val calls: CallFacts,
    private val budget: Int,
) {

    private var steps = 0

    fun evaluate(node: Node): Value {
        charge(1, node.position)
        return when (node) {
            is Node.NumberLiteral -> Value.Num(node.value)
            is Node.StringLiteral -> Value.Str(node.value)
            is Node.BooleanLiteral -> Value.Bool(node.value)
            is Node.ListLiteral -> Value.List(node.items.map { evaluate(it) })
            is Node.Identifier -> environment[node.name]
                ?: fail(node.position, ErrorKind.UNKNOWN_IDENTIFIER, "unknown identifier '${node.name}'")
            is Node.Unary -> unary(node)
            is Node.Binary -> binary(node)
            is Node.Conditional -> conditional(node)
            is Node.Call -> call(node)
        }
    }

    private fun charge(amount: Int, position: SourcePosition) {
        if (steps + amount > budget) {
            fail(position, ErrorKind.STEP_BUDGET, "step budget of $budget exhausted")
        }
        steps += amount
    }

    // --- operators ------------------------------------------------------------

    private fun unary(node: Node.Unary): Value = when (node.operator) {
        TokenType.NOT -> Value.Bool(!requireBoolean(evaluate(node.operand), node.operand.position))
        TokenType.MINUS -> Value.Num(finite(-requireNumber(evaluate(node.operand), node.operand.position), node.position))
        else -> fail(node.position, ErrorKind.SYNTAX, "unsupported unary operator '${node.operator}'")
    }

    private fun binary(node: Node.Binary): Value {
        val left = evaluate(node.left)
        return when (node.operator) {
            TokenType.AND -> {
                if (!requireBoolean(left, node.left.position)) {
                    Value.False
                } else {
                    Value.Bool(requireBoolean(evaluate(node.right), node.right.position))
                }
            }
            TokenType.OR -> {
                if (requireBoolean(left, node.left.position)) {
                    Value.True
                } else {
                    Value.Bool(requireBoolean(evaluate(node.right), node.right.position))
                }
            }
            else -> {
                val right = evaluate(node.right)
                when (node.operator) {
                    TokenType.PLUS -> add(left, right, node)
                    TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT ->
                        arithmetic(left, right, node)
                    TokenType.EQUAL_EQUAL -> Value.Bool(left == right)
                    TokenType.BANG_EQUAL -> Value.Bool(left != right)
                    TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL ->
                        comparison(left, right, node)
                    TokenType.IN -> membership(left, right, node)
                    else -> fail(node.position, ErrorKind.SYNTAX, "unsupported binary operator '${node.operator}'")
                }
            }
        }
    }

    private fun add(left: Value, right: Value, node: Node.Binary): Value = when {
        left is Value.Num && right is Value.Num -> Value.Num(finite(left.value + right.value, node.position))
        left is Value.Str && right is Value.Str -> Value.Str(left.value + right.value)
        else -> {
            // Point at the operand that is out of place, not at the operator.
            val offending = if (left !is Value.Num && left !is Value.Str) node.left else node.right
            fail(offending.position, ErrorKind.TYPE, "cannot add ${left.typeName} and ${right.typeName}")
        }
    }

    private fun arithmetic(left: Value, right: Value, node: Node.Binary): Value {
        val leftNumber = requireNumber(left, node.left.position)
        val rightNumber = requireNumber(right, node.right.position)
        val result = when (node.operator) {
            TokenType.MINUS -> leftNumber - rightNumber
            TokenType.STAR -> leftNumber * rightNumber
            TokenType.SLASH -> {
                if (rightNumber == 0.0) fail(node.position, ErrorKind.DIVISION_BY_ZERO, "division by zero")
                leftNumber / rightNumber
            }
            TokenType.PERCENT -> {
                if (rightNumber == 0.0) fail(node.position, ErrorKind.DIVISION_BY_ZERO, "remainder by zero")
                leftNumber % rightNumber
            }
            else -> fail(node.position, ErrorKind.SYNTAX, "unsupported arithmetic operator '${node.operator}'")
        }
        return Value.Num(finite(result, node.position))
    }

    private fun comparison(left: Value, right: Value, node: Node.Binary): Value {
        val leftNumber = requireNumber(left, node.left.position)
        val rightNumber = requireNumber(right, node.right.position)
        return Value.Bool(
            when (node.operator) {
                TokenType.LESS -> leftNumber < rightNumber
                TokenType.LESS_EQUAL -> leftNumber <= rightNumber
                TokenType.GREATER -> leftNumber > rightNumber
                TokenType.GREATER_EQUAL -> leftNumber >= rightNumber
                else -> false
            },
        )
    }

    private fun membership(left: Value, right: Value, node: Node.Binary): Value = when {
        right is Value.List -> {
            charge(right.items.size, node.position)
            Value.Bool(right.items.contains(left))
        }
        right is Value.Str && left is Value.Str -> Value.Bool(right.value.contains(left.value))
        else -> fail(
            node.right.position,
            ErrorKind.TYPE,
            "expected a list or string on the right of 'in', got ${right.typeName}",
        )
    }

    private fun conditional(node: Node.Conditional): Value {
        val condition = requireBoolean(evaluate(node.condition), node.condition.position)
        return evaluate(if (condition) node.whenTrue else node.whenFalse)
    }

    // --- built-ins ------------------------------------------------------------

    private fun call(node: Node.Call): Value {
        // ifElse is the one lazy built-in: only the chosen branch may run, so it
        // can guard division by zero and unknown fields.
        if (node.name == "ifElse") {
            val condition = requireBoolean(evaluate(node.arguments[0]), node.arguments[0].position)
            return evaluate(if (condition) node.arguments[1] else node.arguments[2])
        }
        val arguments = node.arguments.map { evaluate(it) }
        return builtin(node, arguments)
    }

    private fun builtin(node: Node.Call, arguments: List<Value>): Value = when (node.name) {
        "upper" -> Value.Str(requireString(arguments[0], node.arguments[0].position).uppercase())
        "lower" -> Value.Str(requireString(arguments[0], node.arguments[0].position).lowercase())
        "len" -> when (val value = arguments[0]) {
            is Value.Str -> Value.Num(value.value.codePointCount(0, value.value.length).toDouble())
            is Value.List -> Value.Num(value.items.size.toDouble())
            else -> fail(
                node.arguments[0].position,
                ErrorKind.TYPE,
                "len expects a string or list, got ${value.typeName}",
            )
        }
        "substr" -> substring(node, arguments)
        "matches" -> matches(node, arguments)
        "num" -> number(node, arguments)
        "callPrefix" -> {
            val call = requireString(arguments[0], node.arguments[0].position)
            Value.Str(
                calls.prefix(call) ?: fail(
                    node.arguments[0].position,
                    ErrorKind.CALL_DATA_MISSING,
                    "callPrefix: no callsign data is available for '$call'",
                ),
            )
        }
        "callZone" -> {
            val call = requireString(arguments[0], node.arguments[0].position)
            val zone = calls.zone(call) ?: fail(
                node.arguments[0].position,
                ErrorKind.CALL_DATA_MISSING,
                "callZone: no DXCC data is available for '$call'",
            )
            Value.Num(finite(zone, node.position))
        }
        "callCountry" -> {
            val call = requireString(arguments[0], node.arguments[0].position)
            Value.Str(
                calls.country(call) ?: fail(
                    node.arguments[0].position,
                    ErrorKind.CALL_DATA_MISSING,
                    "callCountry: no DXCC data is available for '$call'",
                ),
            )
        }
        "arrlSectionOf" -> {
            val call = requireString(arguments[0], node.arguments[0].position)
            Value.Str(
                calls.arrlSection(call) ?: fail(
                    node.arguments[0].position,
                    ErrorKind.CALL_DATA_MISSING,
                    "arrlSectionOf: no ARRL section data is available for '$call'",
                ),
            )
        }
        "unique" -> {
            val list = requireList(arguments[0], node.arguments[0].position)
            charge(list.items.size, node.position)
            val unique = mutableListOf<Value>()
            for (item in list.items) {
                if (item !in unique) unique += item
            }
            Value.List(unique.toList())
        }
        "count" -> Value.Num(requireList(arguments[0], node.arguments[0].position).items.size.toDouble())
        "sum" -> {
            val list = requireList(arguments[0], node.arguments[0].position)
            charge(list.items.size, node.position)
            var total = 0.0
            list.items.forEachIndexed { index, item ->
                total += requireNumber(item, elementPosition(node.arguments[0], index))
            }
            Value.Num(finite(total, node.position))
        }
        else -> fail(node.position, ErrorKind.UNKNOWN_FUNCTION, "unknown function '${node.name}'")
    }

    private fun substring(node: Node.Call, arguments: List<Value>): Value {
        val text = requireString(arguments[0], node.arguments[0].position)
        val start = requireInteger(arguments[1], node.arguments[1].position, "substr start")
        if (start < 1) {
            fail(node.arguments[1].position, ErrorKind.BAD_ARGUMENT, "substr start is 1-based, got $start")
        }
        val length = if (arguments.size == 3) {
            val value = requireInteger(arguments[2], node.arguments[2].position, "substr length")
            if (value < 0) {
                fail(node.arguments[2].position, ErrorKind.BAD_ARGUMENT, "substr length must not be negative, got $value")
            }
            value
        } else {
            null
        }
        val codePoints = text.codePoints().toArray()
        val from = start - 1
        if (from >= codePoints.size) return Value.Str("")
        val count = if (length == null) codePoints.size - from else minOf(length, codePoints.size - from)
        return Value.Str(String(codePoints, from, count))
    }

    private fun matches(node: Node.Call, arguments: List<Value>): Value {
        val text = requireString(arguments[0], node.arguments[0].position)
        val pattern = requireString(arguments[1], node.arguments[1].position)
        val regex = try {
            Regex(pattern)
        } catch (exception: PatternSyntaxException) {
            fail(
                node.arguments[1].position,
                ErrorKind.BAD_REGEX,
                "invalid regular expression: ${exception.description}",
            )
        }
        return Value.Bool(regex.containsMatchIn(text))
    }

    private fun number(node: Node.Call, arguments: List<Value>): Value {
        val text = requireString(arguments[0], node.arguments[0].position).trim()
        val parsed = text.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            fail(node.arguments[0].position, ErrorKind.BAD_ARGUMENT, "num: '$text' is not a number")
        }
        return Value.Num(parsed)
    }

    // --- typed accessors ------------------------------------------------------

    private fun requireNumber(value: Value, position: SourcePosition): Double =
        (value as? Value.Num)?.value
            ?: fail(position, ErrorKind.TYPE, "expected number, got ${value.typeName}")

    private fun requireBoolean(value: Value, position: SourcePosition): Boolean =
        (value as? Value.Bool)?.value
            ?: fail(position, ErrorKind.TYPE, "expected boolean, got ${value.typeName}")

    private fun requireString(value: Value, position: SourcePosition): String =
        (value as? Value.Str)?.value
            ?: fail(position, ErrorKind.TYPE, "expected a string, got ${value.typeName}")

    private fun requireList(value: Value, position: SourcePosition): Value.List =
        value as? Value.List
            ?: fail(position, ErrorKind.TYPE, "expected a list, got ${value.typeName}")

    private fun requireInteger(value: Value, position: SourcePosition, name: String): Int {
        val number = requireNumber(value, position)
        if (number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
            fail(position, ErrorKind.BAD_ARGUMENT, "$name must be an integer, got $number")
        }
        return number.toInt()
    }

    private fun finite(value: Double, position: SourcePosition): Double {
        if (!value.isFinite()) fail(position, ErrorKind.NUMERIC_OVERFLOW, "numeric overflow")
        return value
    }

    /** For list literals, point a bad element error at that element, not the bracket. */
    private fun elementPosition(listNode: Node, index: Int): SourcePosition =
        (listNode as? Node.ListLiteral)?.items?.getOrNull(index)?.position ?: listNode.position
}
