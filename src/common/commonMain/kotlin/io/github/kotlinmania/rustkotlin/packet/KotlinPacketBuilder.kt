package io.github.kotlinmania.rustkotlin.packet

import io.github.kotlinmania.procmacro.vendored.intellij.platform.syntax.SyntaxElementType
import io.github.kotlinmania.procmacro.vendored.intellij.platform.syntax.element.SyntaxTokenTypes
import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.lexer.KDocTokens
import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.lexer.KtTokens
import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.parser.KtNodeTypes
import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.tree.LightNode
import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.tree.LightSyntaxTree
import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.tree.parseKDocLightSyntaxTree
import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.tree.parseKotlinLightSyntaxTree

/**
 * Parses [source] with `proc-macro-kotlin`'s pure-KMP `KotlinLexer` + `KotlinParser` +
 * `LightSyntaxTree` + `KDocParser` into a 100% lossless [PacketStream].
 *
 * Fails immediately with [IllegalArgumentException] if lexical `BAD_CHARACTER` tokens or
 * parser `ERROR_ELEMENT` nodes are encountered (single code path, zero fallbacks).
 */
public fun buildKotlinPacketStream(
    source: String,
    sourcePath: String = "<memory>",
): PacketStream {
    val tree = parseKotlinLightSyntaxTree(source, isScript = false, isLazy = false)
    val offsetTable = SourceOffsetTable(source)
    return KotlinPacketStreamBuilder(source, sourcePath, tree, offsetTable).build()
}

private class KotlinPacketStreamBuilder(
    private val source: String,
    private val sourcePath: String,
    private val tree: LightSyntaxTree,
    private val offsetTable: SourceOffsetTable,
) {
    private val rawPackets = ArrayList<SyntaxPacket>(tree.tokens.tokenCount)
    private val tokenToPacketIndex = IntArray(tree.tokens.tokenCount) { -1 }

    fun build(): PacketStream {
        materializeLeafPackets()
        pairBalancedDelimiters()
        validateNoSyntaxErrors(tree.getRoot())

        val framesOut = ArrayList<PacketFrame>()
        val rootFrameIdx = buildFrameRecursive(tree.getRoot(), parentSlotProvider = -1, framesOut = framesOut)

        // Stamp contextual identifier roles, modifier flags, and string/nullable flags on frames.
        enrichFramesAndPackets(framesOut)

        val stream =
            PacketStream(
                sourcePath = sourcePath,
                sourceText = source,
                packets = rawPackets,
                frames = framesOut,
                rootFrameIndex = rootFrameIdx,
            )

        // Hard structural invariants:
        // 1. Concatenating leaf packets reproduces sourceText byte-for-byte.
        // 2. Every leaf packet 0..N-1 is owned by exactly one PacketFrame in monotonic order.
        check(stream.renderRawLossless() == source) {
            "$sourcePath: lossless packet tape invariant violated"
        }
        verifyExactPacketPartition(stream)
        return stream
    }

    private fun materializeLeafPackets() {
        val tokens = tree.tokens
        var nextPacketIdx = 0
        for (tokenIdx in 0 until tokens.tokenCount) {
            val type = tokens.getTokenType(tokenIdx) ?: continue
            val start = tokens.getTokenStart(tokenIdx)
            val end = tokens.getTokenEnd(tokenIdx)
            if (start == end) continue

            val span = offsetTable.spanOf(start, end)
            if (type === SyntaxTokenTypes.BAD_CHARACTER) {
                val badText = source.substring(start, end)
                throw IllegalArgumentException(
                    "$sourcePath:${span.startLine}:${span.startColumn}: illegal Kotlin character '$badText'",
                )
            }

            val text = source.substring(start, end)
            val parentNode = tree.getParent(LightNode(-(tokenIdx + 1)))
            val parentTypeId =
                if (parentNode != null && tree.isComposite(parentNode)) {
                    KtNodeTypes.getElementTypeId(tree.getType(parentNode))
                } else {
                    0
                }

            val packet = classifyTokenPacket(nextPacketIdx, type, parentTypeId, span, text)
            rawPackets.add(packet)
            tokenToPacketIndex[tokenIdx] = nextPacketIdx
            nextPacketIdx += 1
        }
    }

    private fun classifyTokenPacket(
        packetIndex: Int,
        type: SyntaxElementType,
        parentNodeTypeId: Int,
        span: PacketSpan,
        rawText: String,
    ): SyntaxPacket {
        if (type === SyntaxTokenTypes.WHITE_SPACE || type === KtTokens.WHITE_SPACE) {
            var newlines = 0
            var trailingCols = 0
            for (ch in rawText) {
                if (ch == '\n') {
                    newlines += 1
                    trailingCols = 0
                } else if (ch != '\r') {
                    trailingCols += 1
                }
            }
            return SyntaxPacket.WhitespacePacket(
                packetIndex = packetIndex,
                span = span,
                rawText = rawText,
                newlineCount = newlines,
                trailingIndentColumns = trailingCols,
            )
        }

        val tokenId = KtTokens.getElementTypeId(type)
        return when (tokenId) {
            KtTokens.EOL_COMMENT_ID -> buildLineCommentPacket(packetIndex, span, rawText)
            KtTokens.BLOCK_COMMENT_ID ->
                SyntaxPacket.CommentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    kind = CommentPacketKind.BLOCK,
                )
            KtTokens.DOC_COMMENT_ID -> buildKDocCommentPacket(packetIndex, span, rawText)
            KtTokens.SHEBANG_COMMENT_ID ->
                SyntaxPacket.CommentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    kind = CommentPacketKind.SHEBANG,
                )

            KtTokens.INTEGER_LITERAL_ID -> buildIntegerLiteralPacket(packetIndex, span, rawText)
            KtTokens.FLOAT_LITERAL_ID -> buildFloatLiteralPacket(packetIndex, span, rawText)
            KtTokens.CHARACTER_LITERAL_ID -> buildCharLiteralPacket(packetIndex, span, rawText)
            KtTokens.TRUE_KEYWORD_ID,
            KtTokens.FALSE_KEYWORD_ID,
            ->
                SyntaxPacket.LiteralPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    literalKind = LiteralPacketKind.BOOLEAN,
                    numericWidthBits = 1,
                )
            KtTokens.NULL_KEYWORD_ID ->
                SyntaxPacket.LiteralPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    literalKind = LiteralPacketKind.NULL,
                    numericWidthBits = 0,
                )

            KtTokens.INTERPOLATION_PREFIX_ID ->
                SyntaxPacket.StringSegmentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    segmentKind = StringSegmentKind.INTERP_PREFIX,
                )
            KtTokens.OPEN_QUOTE_ID -> {
                var flags = PacketFlags.IS_SIGNIFICANT
                if (rawText == "\"\"\"") {
                    flags = flags or PacketFlags.RAW_MULTILINE_STRING
                }
                SyntaxPacket.StringSegmentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    segmentKind = StringSegmentKind.OPEN_QUOTE,
                    flags = flags,
                )
            }
            KtTokens.CLOSING_QUOTE_ID -> {
                var flags = PacketFlags.IS_SIGNIFICANT
                if (rawText == "\"\"\"") {
                    flags = flags or PacketFlags.RAW_MULTILINE_STRING
                }
                SyntaxPacket.StringSegmentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    segmentKind = StringSegmentKind.CLOSE_QUOTE,
                    flags = flags,
                )
            }
            KtTokens.REGULAR_STRING_PART_ID,
            KtTokens.DANGLING_NEWLINE_ID,
            -> {
                var flags = PacketFlags.IS_SIGNIFICANT
                if (containsUnpairedSurrogate(rawText)) {
                    flags = flags or PacketFlags.HAS_LONE_SURROGATE
                }
                SyntaxPacket.StringSegmentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    segmentKind = StringSegmentKind.LITERAL_PART,
                    flags = flags,
                )
            }
            KtTokens.ESCAPE_SEQUENCE_ID -> {
                var flags = PacketFlags.IS_SIGNIFICANT or PacketFlags.HAS_ESCAPES
                if (isLoneSurrogateUnicodeEscape(rawText)) {
                    flags = flags or PacketFlags.HAS_LONE_SURROGATE
                }
                SyntaxPacket.StringSegmentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    segmentKind = StringSegmentKind.ESCAPE_SEQ,
                    flags = flags,
                )
            }
            KtTokens.SHORT_TEMPLATE_ENTRY_START_ID ->
                SyntaxPacket.StringSegmentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    segmentKind = StringSegmentKind.SHORT_ENTRY_START,
                    flags = PacketFlags.IS_SIGNIFICANT or PacketFlags.INTERPOLATED_STRING,
                )
            KtTokens.LONG_TEMPLATE_ENTRY_START_ID ->
                SyntaxPacket.StringSegmentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    segmentKind = StringSegmentKind.LONG_ENTRY_START,
                    flags = PacketFlags.IS_SIGNIFICANT or PacketFlags.INTERPOLATED_STRING,
                )
            KtTokens.LONG_TEMPLATE_ENTRY_END_ID ->
                SyntaxPacket.StringSegmentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    segmentKind = StringSegmentKind.LONG_ENTRY_END,
                    flags = PacketFlags.IS_SIGNIFICANT or PacketFlags.INTERPOLATED_STRING,
                )

            KtTokens.IDENTIFIER_ID,
            KtTokens.FIELD_IDENTIFIER_ID,
            -> {
                val escaped = rawText.length >= 2 && rawText.startsWith('`') && rawText.endsWith('`')
                val unescaped = if (escaped) rawText.substring(1, rawText.length - 1) else rawText
                var flags = PacketFlags.IS_SIGNIFICANT
                if (escaped) {
                    flags = flags or PacketFlags.BACKTICK_ESCAPED
                }
                SyntaxPacket.IdentPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    unescapedName = unescaped,
                    flags = flags,
                )
            }

            KtTokens.LPAR_ID,
            KtTokens.RPAR_ID,
            KtTokens.LBRACE_ID,
            KtTokens.RBRACE_ID,
            KtTokens.LBRACKET_ID,
            KtTokens.RBRACKET_ID,
            KtTokens.COMMA_ID,
            KtTokens.DOT_ID,
            KtTokens.COLON_ID,
            KtTokens.SEMICOLON_ID,
            KtTokens.DOUBLE_SEMICOLON_ID,
            KtTokens.AT_ID,
            KtTokens.HASH_ID,
            ->
                SyntaxPacket.DelimiterPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    delimiterId = tokenId,
                )

            KtTokens.LT_ID,
            KtTokens.GT_ID,
            -> {
                val isTypeAngleBracket =
                    parentNodeTypeId == KtNodeTypes.TYPE_PARAMETER_LIST_ID ||
                        parentNodeTypeId == KtNodeTypes.TYPE_ARGUMENT_LIST_ID
                if (isTypeAngleBracket) {
                    SyntaxPacket.DelimiterPacket(
                        packetIndex = packetIndex,
                        span = span,
                        rawText = rawText,
                        delimiterId = tokenId,
                    )
                } else {
                    SyntaxPacket.OperatorPacket(
                        packetIndex = packetIndex,
                        span = span,
                        rawText = rawText,
                        operatorId = tokenId,
                    )
                }
            }

            in OPERATOR_TOKEN_IDS ->
                SyntaxPacket.OperatorPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    operatorId = tokenId,
                )

            else -> {
                val modFlag = modifierFlagForKeywordId(tokenId)
                SyntaxPacket.KeywordPacket(
                    packetIndex = packetIndex,
                    span = span,
                    rawText = rawText,
                    keywordId = tokenId,
                    flags = PacketFlags.IS_SIGNIFICANT or modFlag,
                )
            }
        }
    }

    private fun buildLineCommentPacket(
        packetIndex: Int,
        span: PacketSpan,
        rawText: String,
    ): SyntaxPacket.CommentPacket {
        val body = rawText.removePrefix("//").trim()
        var directiveKind: String? = null
        var directiveTarget: String? = null
        var flags = PacketFlags.NONE
        if (body.startsWith("port-lint:")) {
            val rest = body.removePrefix("port-lint:").trim()
            val kind = rest.substringBefore(' ').trim()
            val target = rest.substringAfter(' ', missingDelimiterValue = "").trim()
            require(kind != "ignore") {
                "$sourcePath:${span.startLine}:${span.startColumn}: 'port-lint: ignore' is forbidden; only 'source' and 'tests' are valid"
            }
            if ((kind == "source" || kind == "tests") && target.isNotEmpty()) {
                directiveKind = kind
                directiveTarget = target
                flags = flags or PacketFlags.PORT_LINT_DIRECTIVE
            }
        }
        return SyntaxPacket.CommentPacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            kind = CommentPacketKind.LINE,
            portLintDirectiveKind = directiveKind,
            portLintTarget = directiveTarget,
            flags = flags,
        )
    }

    private fun buildKDocCommentPacket(
        packetIndex: Int,
        span: PacketSpan,
        rawText: String,
    ): SyntaxPacket.CommentPacket {
        val kdocTree = parseKDocLightSyntaxTree(rawText)
        val kdocTokens = kdocTree.tokens
        val links = mutableListOf<KDocLinkSpan>()
        for (i in 0 until kdocTokens.tokenCount) {
            val kt = kdocTokens.getTokenType(i) ?: continue
            if (KDocTokens.getElementTypeId(kt) == KDocTokens.MARKDOWN_LINK_ID) {
                val relStart = kdocTokens.getTokenStart(i)
                val relEnd = kdocTokens.getTokenEnd(i)
                val linkSlice = rawText.substring(relStart, relEnd)
                val target = linkSlice.removePrefix("[").removeSuffix("]")
                links.add(
                    KDocLinkSpan(
                        relativeStart = relStart,
                        relativeEnd = relEnd,
                        targetPath = target,
                    ),
                )
            }
        }
        val flags = if (links.isNotEmpty()) PacketFlags.DOC_COMMENT_HAS_LINKS else PacketFlags.NONE
        return SyntaxPacket.CommentPacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            kind = CommentPacketKind.KDOC,
            kdocLinks = links,
            flags = flags,
        )
    }

    private fun buildIntegerLiteralPacket(
        packetIndex: Int,
        span: PacketSpan,
        rawText: String,
    ): SyntaxPacket.LiteralPacket {
        val lower = rawText.lowercase()
        val radix =
            when {
                lower.startsWith("0x") -> 16
                lower.startsWith("0b") -> 2
                else -> 10
            }
        val isLong = rawText.endsWith('L')
        val withoutLong = if (isLong) rawText.dropLast(1) else rawText
        val isUnsigned = withoutLong.endsWith('u') || withoutLong.endsWith('U')
        return SyntaxPacket.LiteralPacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            literalKind = LiteralPacketKind.INT,
            radix = radix,
            numericWidthBits = if (isLong) 64 else 32,
            isUnsigned = isUnsigned,
        )
    }

    private fun buildFloatLiteralPacket(
        packetIndex: Int,
        span: PacketSpan,
        rawText: String,
    ): SyntaxPacket.LiteralPacket {
        val isFloat32 = rawText.endsWith('f') || rawText.endsWith('F')
        return SyntaxPacket.LiteralPacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            literalKind = LiteralPacketKind.FLOAT,
            radix = 10,
            numericWidthBits = if (isFloat32) 32 else 64,
        )
    }

    private fun buildCharLiteralPacket(
        packetIndex: Int,
        span: PacketSpan,
        rawText: String,
    ): SyntaxPacket.LiteralPacket {
        val inner = rawText.removeSurrounding("'")
        var flags = PacketFlags.IS_SIGNIFICANT
        if (inner.startsWith('\\')) {
            flags = flags or PacketFlags.HAS_ESCAPES
        }
        if (containsUnpairedSurrogate(rawText) || isLoneSurrogateUnicodeEscape(inner)) {
            flags = flags or PacketFlags.HAS_LONE_SURROGATE
        }
        return SyntaxPacket.LiteralPacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            literalKind = LiteralPacketKind.CHAR,
            numericWidthBits = 16,
            flags = flags,
        )
    }

    private fun pairBalancedDelimiters() {
        val openStack = ArrayDeque<Int>()
        for (idx in rawPackets.indices) {
            when (val pkt = rawPackets[idx]) {
                is SyntaxPacket.DelimiterPacket -> {
                    when (pkt.delimiterId) {
                        KtTokens.LPAR_ID,
                        KtTokens.LBRACE_ID,
                        KtTokens.LBRACKET_ID,
                        KtTokens.LT_ID,
                        -> openStack.addLast(idx)

                        KtTokens.RPAR_ID -> tryPairDelimiter(openStack, idx, KtTokens.LPAR_ID)
                        KtTokens.RBRACE_ID -> tryPairDelimiter(openStack, idx, KtTokens.LBRACE_ID)
                        KtTokens.RBRACKET_ID -> tryPairDelimiter(openStack, idx, KtTokens.LBRACKET_ID)
                        KtTokens.GT_ID -> tryPairDelimiter(openStack, idx, KtTokens.LT_ID)
                    }
                }
                is SyntaxPacket.StringSegmentPacket -> {
                    when (pkt.segmentKind) {
                        StringSegmentKind.LONG_ENTRY_START -> openStack.addLast(idx)
                        StringSegmentKind.LONG_ENTRY_END -> {
                            val top = openStack.lastOrNull()
                            if (top != null &&
                                (rawPackets[top] as? SyntaxPacket.StringSegmentPacket)?.segmentKind ==
                                StringSegmentKind.LONG_ENTRY_START
                            ) {
                                openStack.removeLast()
                            }
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    private fun tryPairDelimiter(
        openStack: ArrayDeque<Int>,
        closeIdx: Int,
        expectedOpenId: Int,
    ) {
        val openIdx = openStack.lastOrNull() ?: return
        val openPkt = rawPackets[openIdx] as? SyntaxPacket.DelimiterPacket ?: return
        if (openPkt.delimiterId == expectedOpenId) {
            openStack.removeLast()
            val closePkt = rawPackets[closeIdx] as SyntaxPacket.DelimiterPacket
            rawPackets[openIdx] = openPkt.copy(matchingPacketIndex = closeIdx)
            rawPackets[closeIdx] = closePkt.copy(matchingPacketIndex = openIdx)
        }
    }

    private fun validateNoSyntaxErrors(node: LightNode) {
        val type = tree.getType(node)
        if (type === SyntaxTokenTypes.ERROR_ELEMENT) {
            val span = offsetTable.spanOf(tree.getStartOffset(node), tree.getEndOffset(node))
            throw IllegalArgumentException(
                "$sourcePath:${span.startLine}:${span.startColumn}: Kotlin syntax error near '${tree.getTextString(node)}'",
            )
        }
        if (tree.isComposite(node)) {
            for (child in tree.getChildren(node)) {
                validateNoSyntaxErrors(child)
            }
        }
    }

    private fun buildFrameRecursive(
        node: LightNode,
        parentSlotProvider: Int,
        framesOut: ArrayList<PacketFrame>,
    ): Int {
        val myFrameIdx = framesOut.size
        val startOffset = tree.getStartOffset(node)
        val endOffset = tree.getEndOffset(node)
        val span = offsetTable.spanOf(startOffset, endOffset)
        val type = tree.getType(node)
        val nodeTypeId =
            if (node.index == tree.rootIndex) {
                KtNodeTypes.KT_FILE_ID
            } else {
                KtNodeTypes.getElementTypeId(type)
            }
        val (firstPacket, lastPacket) =
            if (node.index == tree.rootIndex) {
                0 to (rawPackets.size - 1)
            } else {
                packetRangeForOffsets(startOffset, endOffset)
            }

        // Reserve slot so parentFrameIndex of descendants can point directly to myFrameIdx.
        framesOut.add(
            PacketFrame(
                frameIndex = myFrameIdx,
                nodeTypeId = nodeTypeId,
                debugName = type.toString(),
                firstPacketIndex = firstPacket,
                lastPacketIndex = lastPacket,
                span = span,
                parentFrameIndex = parentSlotProvider,
                rawChildNodeRefs = IntArray(0),
            ),
        )

        val childRefs = ArrayList<Int>(8)
        var cursor = firstPacket
        val lightChildren = tree.getChildren(node)
        for (child in lightChildren) {
            if (!tree.isComposite(child)) continue
            val childStart = tree.getStartOffset(child)
            val childEnd = tree.getEndOffset(child)
            val (cFirst, cLast) = packetRangeForOffsets(childStart, childEnd)
            if (cFirst <= cLast) {
                while (cursor < cFirst && cursor <= lastPacket) {
                    childRefs.add(-(cursor + 1))
                    cursor += 1
                }
                val childFrameIdx = buildFrameRecursive(child, myFrameIdx, framesOut)
                childRefs.add(childFrameIdx)
                cursor = cLast + 1
            } else {
                while (cursor <= lastPacket && rawPackets[cursor].span.endOffset <= childStart) {
                    childRefs.add(-(cursor + 1))
                    cursor += 1
                }
                val childFrameIdx = buildFrameRecursive(child, myFrameIdx, framesOut)
                childRefs.add(childFrameIdx)
            }
        }
        while (cursor <= lastPacket) {
            childRefs.add(-(cursor + 1))
            cursor += 1
        }

        framesOut[myFrameIdx] =
            PacketFrame(
                frameIndex = myFrameIdx,
                nodeTypeId = nodeTypeId,
                debugName = type.toString(),
                firstPacketIndex = firstPacket,
                lastPacketIndex = lastPacket,
                span = span,
                parentFrameIndex = parentSlotProvider,
                rawChildNodeRefs = childRefs.toIntArray(),
            )
        return myFrameIdx
    }

    private fun packetRangeForOffsets(
        startOffset: Int,
        endOffset: Int,
    ): Pair<Int, Int> {
        if (rawPackets.isEmpty() || startOffset >= endOffset) {
            val ins = findFirstPacketStartingAtOrAfter(startOffset)
            return ins to (ins - 1)
        }
        val first = findFirstPacketStartingAtOrAfter(startOffset)
        val last = findLastPacketEndingAtOrBefore(endOffset)
        return first to last
    }

    private fun findFirstPacketStartingAtOrAfter(offset: Int): Int {
        var lo = 0
        var hi = rawPackets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rawPackets[mid].span.startOffset < offset) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo
    }

    private fun findLastPacketEndingAtOrBefore(offset: Int): Int {
        var lo = 0
        var hi = rawPackets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rawPackets[mid].span.endOffset <= offset) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo - 1
    }

    private fun enrichFramesAndPackets(frames: MutableList<PacketFrame>) {
        for (frame in frames) {
            when (frame.nodeTypeId) {
                KtNodeTypes.MODIFIER_LIST_ID -> {
                    var modMask = PacketFlags.NONE
                    for (i in 0 until frame.childCount) {
                        val ref = frame.childRefAt(i)
                        if (ref < 0) {
                            val pkt = rawPackets[-(ref + 1)]
                            modMask = modMask or (pkt.flags and ALL_MODIFIER_FLAGS_MASK)
                        }
                    }
                    frame.flags = frame.flags or modMask
                    if (frame.parentFrameIndex >= 0) {
                        frames[frame.parentFrameIndex].flags = frames[frame.parentFrameIndex].flags or modMask
                    }
                }

                KtNodeTypes.NULLABLE_TYPE_ID -> {
                    frame.flags = frame.flags or PacketFlags.NULLABLE_TYPE
                }

                KtNodeTypes.STRING_TEMPLATE_ID -> {
                    for (idx in frame.firstPacketIndex..frame.lastPacketIndex) {
                        val pkt = rawPackets[idx]
                        if (pkt is SyntaxPacket.StringSegmentPacket) {
                            if (pkt.segmentKind == StringSegmentKind.SHORT_ENTRY_START ||
                                pkt.segmentKind == StringSegmentKind.LONG_ENTRY_START
                            ) {
                                frame.flags = frame.flags or PacketFlags.INTERPOLATED_STRING
                            }
                            if (PacketFlags.hasFlag(pkt.flags, PacketFlags.RAW_MULTILINE_STRING)) {
                                frame.flags = frame.flags or PacketFlags.RAW_MULTILINE_STRING
                            }
                        }
                    }
                }

                KtNodeTypes.PACKAGE_DIRECTIVE_ID -> stampDirectIdentRole(frame, SymbolRole.PACKAGE)
                KtNodeTypes.IMPORT_ALIAS_ID -> stampDirectIdentRole(frame, SymbolRole.IMPORT_ALIAS)
                KtNodeTypes.CLASS_ID,
                KtNodeTypes.OBJECT_DECLARATION_ID,
                KtNodeTypes.TYPEALIAS_ID,
                -> recordDeclarationSymbol(frame, SymbolRole.TYPE)
                KtNodeTypes.ENUM_ENTRY_ID -> recordDeclarationSymbol(frame, SymbolRole.ENUM_ENTRY)
                KtNodeTypes.TYPE_PARAMETER_ID -> recordDeclarationSymbol(frame, SymbolRole.TYPE_PARAM)
                KtNodeTypes.FUN_ID -> {
                    val parentId = if (frame.parentFrameIndex >= 0) frames[frame.parentFrameIndex].nodeTypeId else 0
                    val role = if (parentId == KtNodeTypes.CLASS_BODY_ID) SymbolRole.METHOD else SymbolRole.FUNCTION
                    recordDeclarationSymbol(frame, role)
                }
                KtNodeTypes.PROPERTY_ID -> {
                    val isConst = PacketFlags.hasFlag(frame.flags, PacketFlags.MOD_CONST)
                    val isPrivate = PacketFlags.hasFlag(frame.flags, PacketFlags.MOD_PRIVATE)
                    val parentId = if (frame.parentFrameIndex >= 0) frames[frame.parentFrameIndex].nodeTypeId else 0
                    val role =
                        when {
                            isConst -> SymbolRole.CONSTANT
                            parentId == KtNodeTypes.BLOCK_ID -> SymbolRole.LOCAL
                            isPrivate && parentId == KtNodeTypes.CLASS_BODY_ID -> SymbolRole.PRIVATE_FIELD
                            else -> SymbolRole.PROPERTY
                        }
                    recordDeclarationSymbol(frame, role)
                }
                KtNodeTypes.VALUE_PARAMETER_ID -> recordDeclarationSymbol(frame, SymbolRole.PARAM)
                KtNodeTypes.LABEL_ID -> stampDirectIdentRole(frame, SymbolRole.LABEL)
            }
        }
    }

    private fun stampDirectIdentRole(
        frame: PacketFrame,
        role: SymbolRole,
    ) {
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (ref < 0) {
                val pkt = rawPackets[-(ref + 1)]
                if (pkt is SyntaxPacket.IdentPacket) {
                    pkt.role = role
                }
            }
        }
    }

    private fun recordDeclarationSymbol(
        frame: PacketFrame,
        role: SymbolRole,
    ) {
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (ref < 0) {
                val pkt = rawPackets[-(ref + 1)]
                if (pkt is SyntaxPacket.IdentPacket) {
                    pkt.role = role
                    frame.semanticInfo = FrameSemanticInfo(symbolName = pkt.unescapedName)
                    return
                }
            }
        }
    }

    private fun verifyExactPacketPartition(stream: PacketStream) {
        var expectedNextPacket = 0
        fun walk(frame: PacketFrame) {
            for (i in 0 until frame.childCount) {
                val ref = frame.childRefAt(i)
                if (ref >= 0) {
                    walk(stream.frames[ref])
                } else {
                    val pktIdx = -(ref + 1)
                    check(pktIdx == expectedNextPacket) {
                        "$sourcePath: frame ${frame.debugName} visited packet $pktIdx out of sequence (expected $expectedNextPacket)"
                    }
                    expectedNextPacket += 1
                }
            }
        }
        walk(stream.rootFrame)
        check(expectedNextPacket == stream.packets.size) {
            "$sourcePath: frame partition visited $expectedNextPacket packets out of ${stream.packets.size}"
        }
    }

    companion object {
        private val OPERATOR_TOKEN_IDS: Set<Int> =
            setOf(
                KtTokens.PLUSPLUS_ID,
                KtTokens.MINUSMINUS_ID,
                KtTokens.MUL_ID,
                KtTokens.PLUS_ID,
                KtTokens.MINUS_ID,
                KtTokens.EXCL_ID,
                KtTokens.DIV_ID,
                KtTokens.PERC_ID,
                KtTokens.LTEQ_ID,
                KtTokens.GTEQ_ID,
                KtTokens.EQEQEQ_ID,
                KtTokens.ARROW_ID,
                KtTokens.DOUBLE_ARROW_ID,
                KtTokens.EXCLEQEQEQ_ID,
                KtTokens.EQEQ_ID,
                KtTokens.EXCLEQ_ID,
                KtTokens.EXCLEXCL_ID,
                KtTokens.ANDAND_ID,
                KtTokens.AND_ID,
                KtTokens.OROR_ID,
                KtTokens.SAFE_ACCESS_ID,
                KtTokens.ELVIS_ID,
                KtTokens.QUEST_ID,
                KtTokens.COLONCOLON_ID,
                KtTokens.RANGE_ID,
                KtTokens.RANGE_UNTIL_ID,
                KtTokens.EQ_ID,
                KtTokens.MULTEQ_ID,
                KtTokens.DIVEQ_ID,
                KtTokens.PERCEQ_ID,
                KtTokens.PLUSEQ_ID,
                KtTokens.MINUSEQ_ID,
                KtTokens.NOT_IN_ID,
                KtTokens.NOT_IS_ID,
                KtTokens.AS_SAFE_ID,
            )

        private const val ALL_MODIFIER_FLAGS_MASK: Long =
            PacketFlags.MOD_PUBLIC or
                PacketFlags.MOD_INTERNAL or
                PacketFlags.MOD_PROTECTED or
                PacketFlags.MOD_PRIVATE or
                PacketFlags.MOD_SUSPEND or
                PacketFlags.MOD_INLINE or
                PacketFlags.MOD_NOINLINE or
                PacketFlags.MOD_CROSSINLINE or
                PacketFlags.MOD_REIFIED or
                PacketFlags.MOD_OPEN or
                PacketFlags.MOD_ABSTRACT or
                PacketFlags.MOD_OVERRIDE or
                PacketFlags.MOD_SEALED or
                PacketFlags.MOD_DATA or
                PacketFlags.MOD_VALUE or
                PacketFlags.MOD_CONST or
                PacketFlags.MOD_COMPANION or
                PacketFlags.MOD_EXTERNAL_OR_EXTENSION

        private fun modifierFlagForKeywordId(tokenId: Int): Long =
            when (tokenId) {
                KtTokens.PUBLIC_MODIFIER_ID -> PacketFlags.MOD_PUBLIC
                KtTokens.INTERNAL_MODIFIER_ID -> PacketFlags.MOD_INTERNAL
                KtTokens.PROTECTED_MODIFIER_ID -> PacketFlags.MOD_PROTECTED
                KtTokens.PRIVATE_MODIFIER_ID -> PacketFlags.MOD_PRIVATE
                KtTokens.SUSPEND_MODIFIER_ID -> PacketFlags.MOD_SUSPEND
                KtTokens.INLINE_MODIFIER_ID -> PacketFlags.MOD_INLINE
                KtTokens.NOINLINE_MODIFIER_ID -> PacketFlags.MOD_NOINLINE
                KtTokens.CROSSINLINE_MODIFIER_ID -> PacketFlags.MOD_CROSSINLINE
                KtTokens.REIFIED_MODIFIER_ID -> PacketFlags.MOD_REIFIED
                KtTokens.OPEN_MODIFIER_ID -> PacketFlags.MOD_OPEN
                KtTokens.ABSTRACT_MODIFIER_ID -> PacketFlags.MOD_ABSTRACT
                KtTokens.OVERRIDE_MODIFIER_ID -> PacketFlags.MOD_OVERRIDE
                KtTokens.SEALED_MODIFIER_ID -> PacketFlags.MOD_SEALED
                KtTokens.DATA_MODIFIER_ID -> PacketFlags.MOD_DATA
                KtTokens.VALUE_MODIFIER_ID -> PacketFlags.MOD_VALUE
                KtTokens.CONST_MODIFIER_ID -> PacketFlags.MOD_CONST
                KtTokens.COMPANION_MODIFIER_ID -> PacketFlags.MOD_COMPANION
                KtTokens.EXTERNAL_MODIFIER_ID -> PacketFlags.MOD_EXTERNAL_OR_EXTENSION
                else -> PacketFlags.NONE
            }

        private fun containsUnpairedSurrogate(value: String): Boolean {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c.isHighSurrogate()) {
                    if (i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                        i += 2
                        continue
                    }
                    return true
                }
                if (c.isLowSurrogate()) {
                    return true
                }
                i += 1
            }
            return false
        }

        private fun isLoneSurrogateUnicodeEscape(escapeText: String): Boolean {
            if (!escapeText.startsWith("\\u") || escapeText.length != 6) return false
            val code = escapeText.substring(2).toIntOrNull(16) ?: return false
            return code in 0xD800..0xDFFF
        }
    }
}
