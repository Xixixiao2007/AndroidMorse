package com.bh4gzk.morserunner.expr

/**
 * Immutable AST of the expression language. Internal on purpose: the module's
 * public surface is [Value] + `compile`/`evaluate`, so the tree can change
 * without breaking callers (deep module, small interface).
 *
 * Each node carries the [position] an error about that node should point at:
 * literals and identifiers point at themselves, operators at the operator token,
 * calls at the function name.
 */
internal sealed interface Node {

    val position: SourcePosition

    data class NumberLiteral(val value: Double, override val position: SourcePosition) : Node

    data class StringLiteral(val value: String, override val position: SourcePosition) : Node

    data class BooleanLiteral(val value: Boolean, override val position: SourcePosition) : Node

    data class ListLiteral(val items: List<Node>, override val position: SourcePosition) : Node

    data class Identifier(val name: String, override val position: SourcePosition) : Node

    data class Unary(val operator: TokenType, val operand: Node, override val position: SourcePosition) : Node

    data class Binary(
        val operator: TokenType,
        val left: Node,
        val right: Node,
        override val position: SourcePosition,
    ) : Node

    data class Conditional(
        val condition: Node,
        val whenTrue: Node,
        val whenFalse: Node,
        override val position: SourcePosition,
    ) : Node

    data class Call(
        val name: String,
        val arguments: List<Node>,
        override val position: SourcePosition,
    ) : Node
}
