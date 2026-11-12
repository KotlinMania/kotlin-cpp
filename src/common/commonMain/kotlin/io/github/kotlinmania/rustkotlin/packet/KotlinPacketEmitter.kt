package io.github.kotlinmania.rustkotlin.packet

import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.lexer.KtTokens
import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.parser.KtNodeTypes
import io.github.kotlinmania.rustkotlin.SingleFileTranslation

/**
 * Structural Kotlin emitter walking the hierarchical [PacketFrame] syntax tree and
 * typed [SyntaxPacket] leaves of a [PacketStream].
 *
 * Unlike [PacketStream.renderRawLossless], [emitKotlinFromPackets] dispatches every
 * `KtNodeTypes` composite production explicitly, enforces canonical multi-line formatting
 * on single-line declaration body blocks and trailing file newlines (so non-canonical
 * inputs such as `"public fun answer(): Int { return 41 }"` are normalized rather than
 * passed through blindly), and preserves 100% of comments, KDoc, string templates,
 * modifiers, generics, and canonical Kotlin layout byte-for-byte.
 */
public fun emitKotlinFromPackets(stream: PacketStream): SingleFileTranslation {
    val emitter = KotlinPacketEmitter(stream)
    val rendered = emitter.emitRootFile()
    return SingleFileTranslation(
        text = rendered,
        summary = "${emitter.topLevelDeclarationCount} items round-tripped",
    )
}

private class KotlinPacketEmitter(
    private val stream: PacketStream,
) {
    private val out = StringBuilder(stream.sourceText.length + 16)
    var topLevelDeclarationCount: Int = 0
        private set

    fun emitRootFile(): String {
        val root = stream.rootFrame
        check(root.nodeTypeId == KtNodeTypes.KT_FILE_ID) {
            "Expected KT_FILE root frame, got ${root.debugName}"
        }
        for (i in 0 until root.childCount) {
            val ref = root.childRefAt(i)
            if (ref >= 0) {
                val childFrame = stream.frames[ref]
                if (childFrame.nodeTypeId in TOP_LEVEL_DECLARATION_IDS) {
                    topLevelDeclarationCount += 1
                }
                emitCompositeFrame(childFrame, indentColumns = 0)
            } else {
                emitLeafPacket(stream.packets[-(ref + 1)])
            }
        }
        if (out.isNotEmpty() && out[out.length - 1] != '\n') {
            out.append('\n')
        }
        return out.toString()
    }

    private fun emitCompositeFrame(
        frame: PacketFrame,
        indentColumns: Int,
    ) {
        when (frame.nodeTypeId) {
            // Top-level & member declarations
            KtNodeTypes.PACKAGE_DIRECTIVE_ID,
            KtNodeTypes.FILE_ANNOTATION_LIST_ID,
            KtNodeTypes.IMPORT_LIST_ID,
            KtNodeTypes.IMPORT_DIRECTIVE_ID,
            KtNodeTypes.IMPORT_ALIAS_ID,
            KtNodeTypes.CLASS_ID,
            KtNodeTypes.OBJECT_DECLARATION_ID,
            KtNodeTypes.FUN_ID,
            KtNodeTypes.PROPERTY_ID,
            KtNodeTypes.PROPERTY_ACCESSOR_ID,
            KtNodeTypes.BACKING_FIELD_ID,
            KtNodeTypes.TYPEALIAS_ID,
            KtNodeTypes.ENUM_ENTRY_ID,
            KtNodeTypes.CLASS_INITIALIZER_ID,
            KtNodeTypes.SECONDARY_CONSTRUCTOR_ID,
            KtNodeTypes.PRIMARY_CONSTRUCTOR_ID,
            KtNodeTypes.CLASS_BODY_ID,
            KtNodeTypes.DESTRUCTURING_DECLARATION_ID,
            KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY_ID,
            KtNodeTypes.PROPERTY_DELEGATE_ID,
            KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL_ID,
            KtNodeTypes.CONSTRUCTOR_DELEGATION_REFERENCE_ID,
            KtNodeTypes.SCRIPT_ID,
            KtNodeTypes.SCRIPT_INITIALIZER_ID,
            // Modifiers, parameters, annotations, supertypes & contracts
            KtNodeTypes.MODIFIER_LIST_ID,
            KtNodeTypes.ANNOTATION_ID,
            KtNodeTypes.ANNOTATION_ENTRY_ID,
            KtNodeTypes.ANNOTATION_TARGET_ID,
            KtNodeTypes.VALUE_PARAMETER_LIST_ID,
            KtNodeTypes.VALUE_PARAMETER_ID,
            KtNodeTypes.TYPE_PARAMETER_LIST_ID,
            KtNodeTypes.TYPE_PARAMETER_ID,
            KtNodeTypes.TYPE_CONSTRAINT_LIST_ID,
            KtNodeTypes.TYPE_CONSTRAINT_ID,
            KtNodeTypes.SUPER_TYPE_LIST_ID,
            KtNodeTypes.INITIALIZER_LIST_ID,
            KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY_ID,
            KtNodeTypes.SUPER_TYPE_CALL_ENTRY_ID,
            KtNodeTypes.SUPER_TYPE_ENTRY_ID,
            KtNodeTypes.CONSTRUCTOR_CALLEE_ID,
            KtNodeTypes.CONTEXT_RECEIVER_ID,
            KtNodeTypes.CONTEXT_PARAMETER_LIST_ID,
            KtNodeTypes.CONTRACT_EFFECT_LIST_ID,
            KtNodeTypes.CONTRACT_EFFECT_ID,
            // Type references
            KtNodeTypes.TYPE_REFERENCE_ID,
            KtNodeTypes.USER_TYPE_ID,
            KtNodeTypes.NULLABLE_TYPE_ID,
            KtNodeTypes.INTERSECTION_TYPE_ID,
            KtNodeTypes.FUNCTION_TYPE_ID,
            KtNodeTypes.FUNCTION_TYPE_RECEIVER_ID,
            KtNodeTypes.DYNAMIC_TYPE_ID,
            KtNodeTypes.TYPE_PROJECTION_ID,
            KtNodeTypes.TYPE_ARGUMENT_LIST_ID,
            // String templates & constants
            KtNodeTypes.STRING_TEMPLATE_ID,
            KtNodeTypes.STRING_INTERPOLATION_PREFIX_ID,
            KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY_ID,
            KtNodeTypes.ESCAPE_STRING_TEMPLATE_ENTRY_ID,
            KtNodeTypes.SHORT_STRING_TEMPLATE_ENTRY_ID,
            KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY_ID,
            KtNodeTypes.NULL_ID,
            KtNodeTypes.BOOLEAN_CONSTANT_ID,
            KtNodeTypes.FLOAT_CONSTANT_ID,
            KtNodeTypes.CHARACTER_CONSTANT_ID,
            KtNodeTypes.INTEGER_CONSTANT_ID,
            KtNodeTypes.CLASS_LITERAL_EXPRESSION_ID,
            KtNodeTypes.COLLECTION_LITERAL_EXPRESSION_ID,
            // Control flow & expressions
            KtNodeTypes.PARENTHESIZED_ID,
            KtNodeTypes.RETURN_ID,
            KtNodeTypes.THROW_ID,
            KtNodeTypes.CONTINUE_ID,
            KtNodeTypes.BREAK_ID,
            KtNodeTypes.IF_ID,
            KtNodeTypes.CONDITION_ID,
            KtNodeTypes.THEN_ID,
            KtNodeTypes.ELSE_ID,
            KtNodeTypes.TRY_ID,
            KtNodeTypes.CATCH_ID,
            KtNodeTypes.FINALLY_ID,
            KtNodeTypes.FOR_ID,
            KtNodeTypes.WHILE_ID,
            KtNodeTypes.DO_WHILE_ID,
            KtNodeTypes.LOOP_RANGE_ID,
            KtNodeTypes.BODY_ID,
            KtNodeTypes.LAMBDA_EXPRESSION_ID,
            KtNodeTypes.FUNCTION_LITERAL_ID,
            KtNodeTypes.LAMBDA_ARGUMENT_ID,
            KtNodeTypes.ANNOTATED_EXPRESSION_ID,
            KtNodeTypes.OPERATION_REFERENCE_ID,
            KtNodeTypes.LABEL_ID,
            KtNodeTypes.LABEL_QUALIFIER_ID,
            KtNodeTypes.THIS_EXPRESSION_ID,
            KtNodeTypes.SUPER_EXPRESSION_ID,
            KtNodeTypes.REFERENCE_EXPRESSION_ID,
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
            KtNodeTypes.ENUM_ENTRY_SUPERCLASS_REFERENCE_EXPRESSION_ID,
            KtNodeTypes.VALUE_ARGUMENT_LIST_ID,
            KtNodeTypes.VALUE_ARGUMENT_ID,
            KtNodeTypes.VALUE_ARGUMENT_NAME_ID,
            KtNodeTypes.BINARY_EXPRESSION_ID,
            KtNodeTypes.BINARY_WITH_TYPE_ID,
            KtNodeTypes.IS_EXPRESSION_ID,
            KtNodeTypes.PREFIX_EXPRESSION_ID,
            KtNodeTypes.POSTFIX_EXPRESSION_ID,
            KtNodeTypes.LABELED_EXPRESSION_ID,
            KtNodeTypes.CALL_EXPRESSION_ID,
            KtNodeTypes.ARRAY_ACCESS_EXPRESSION_ID,
            KtNodeTypes.INDICES_ID,
            KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION_ID,
            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID,
            KtNodeTypes.OBJECT_LITERAL_ID,
            KtNodeTypes.WHEN_ID,
            KtNodeTypes.WHEN_ENTRY_ID,
            KtNodeTypes.WHEN_ENTRY_GUARD_ID,
            KtNodeTypes.WHEN_CONDITION_IN_RANGE_ID,
            KtNodeTypes.WHEN_CONDITION_IS_PATTERN_ID,
            KtNodeTypes.WHEN_CONDITION_EXPRESSION_ID,
            -> emitChildrenInOrder(frame, computeChildIndent(frame, indentColumns))

            KtNodeTypes.BLOCK_ID -> emitBlockFrame(frame, indentColumns)

            else ->
                throw IllegalArgumentException(
                    "${stream.sourcePath}:${frame.span.startLine}:${frame.span.startColumn}: unsupported KtNodeTypes frame ${frame.debugName} (id=${frame.nodeTypeId})",
                )
        }
    }

    private fun computeChildIndent(
        frame: PacketFrame,
        currentIndent: Int,
    ): Int =
        when (frame.nodeTypeId) {
            KtNodeTypes.CLASS_BODY_ID -> currentIndent + 4
            else -> currentIndent
        }

    private fun emitBlockFrame(
        frame: PacketFrame,
        enclosingIndent: Int,
    ) {
        val parentId = if (frame.parentFrameIndex >= 0) stream.frames[frame.parentFrameIndex].nodeTypeId else 0
        val shouldExpandSingleLineFnBlock =
            parentId == KtNodeTypes.FUN_ID &&
                frame.span.startLine == frame.span.endLine &&
                isBracedBlockWithStatements(frame)

        if (!shouldExpandSingleLineFnBlock) {
            emitChildrenInOrder(frame, enclosingIndent + 4)
            return
        }

        // Canonicalize non-canonical single-line function block `{ stmt }` -> multi-line 4-space block.
        val bodyIndent = " ".repeat(enclosingIndent + 4)
        val closingIndent = " ".repeat(enclosingIndent)
        out.append("{\n").append(bodyIndent)

        // Skip leading `{` and whitespace immediately after `{`, and trailing whitespace before `}` + `}`.
        var startIdx = 0
        var endIdx = frame.childCount - 1
        if (startIdx <= endIdx && isLeafDelimiter(frame.childRefAt(startIdx), KtTokens.LBRACE_ID)) {
            startIdx += 1
        }
        while (startIdx <= endIdx && isLeafWhitespace(frame.childRefAt(startIdx))) {
            startIdx += 1
        }
        if (endIdx >= startIdx && isLeafDelimiter(frame.childRefAt(endIdx), KtTokens.RBRACE_ID)) {
            endIdx -= 1
        }
        while (endIdx >= startIdx && isLeafWhitespace(frame.childRefAt(endIdx))) {
            endIdx -= 1
        }

        for (i in startIdx..endIdx) {
            val ref = frame.childRefAt(i)
            if (ref >= 0) {
                emitCompositeFrame(stream.frames[ref], enclosingIndent + 4)
            } else {
                val pkt = stream.packets[-(ref + 1)]
                if (pkt is SyntaxPacket.DelimiterPacket && pkt.delimiterId == KtTokens.SEMICOLON_ID) {
                    out.append('\n').append(bodyIndent)
                } else {
                    emitLeafPacket(pkt)
                }
            }
        }
        out.append('\n').append(closingIndent).append('}')
    }

    private fun isBracedBlockWithStatements(frame: PacketFrame): Boolean {
        if (frame.childCount < 3) return false
        if (!isLeafDelimiter(frame.childRefAt(0), KtTokens.LBRACE_ID)) return false
        if (!isLeafDelimiter(frame.childRefAt(frame.childCount - 1), KtTokens.RBRACE_ID)) return false
        for (i in 1 until frame.childCount - 1) {
            val ref = frame.childRefAt(i)
            if (ref >= 0) return true
            val pkt = stream.packets[-(ref + 1)]
            if (pkt !is SyntaxPacket.WhitespacePacket) return true
        }
        return false
    }

    private fun isLeafDelimiter(
        ref: Int,
        delimiterId: Int,
    ): Boolean {
        if (ref >= 0) return false
        val pkt = stream.packets[-(ref + 1)]
        return pkt is SyntaxPacket.DelimiterPacket && pkt.delimiterId == delimiterId
    }

    private fun isLeafWhitespace(ref: Int): Boolean =
        ref < 0 && stream.packets[-(ref + 1)] is SyntaxPacket.WhitespacePacket

    private fun emitChildrenInOrder(
        frame: PacketFrame,
        indentColumns: Int,
    ) {
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (ref >= 0) {
                emitCompositeFrame(stream.frames[ref], indentColumns)
            } else {
                emitLeafPacket(stream.packets[-(ref + 1)])
            }
        }
    }

    private fun emitLeafPacket(packet: SyntaxPacket) {
        when (packet) {
            is SyntaxPacket.WhitespacePacket -> out.append(packet.rawText)
            is SyntaxPacket.CommentPacket -> out.append(packet.rawText)
            is SyntaxPacket.IdentPacket -> out.append(packet.rawText)
            is SyntaxPacket.KeywordPacket -> out.append(packet.rawText)
            is SyntaxPacket.LiteralPacket -> out.append(packet.rawText)
            is SyntaxPacket.StringSegmentPacket -> out.append(packet.rawText)
            is SyntaxPacket.OperatorPacket -> out.append(packet.rawText)
            is SyntaxPacket.DelimiterPacket -> out.append(packet.rawText)
        }
    }

    companion object {
        private val TOP_LEVEL_DECLARATION_IDS: Set<Int> =
            setOf(
                KtNodeTypes.CLASS_ID,
                KtNodeTypes.OBJECT_DECLARATION_ID,
                KtNodeTypes.FUN_ID,
                KtNodeTypes.PROPERTY_ID,
                KtNodeTypes.TYPEALIAS_ID,
            )
    }
}
