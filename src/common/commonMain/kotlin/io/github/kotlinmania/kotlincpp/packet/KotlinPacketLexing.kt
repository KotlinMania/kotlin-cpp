package io.github.kotlinmania.kotlincpp.packet

import io.github.kotlinmania.antlr4.BaseErrorListener
import io.github.kotlinmania.antlr4.CharStreams
import io.github.kotlinmania.antlr4.CommonTokenStream
import io.github.kotlinmania.antlr4.ListTokenSource
import io.github.kotlinmania.antlr4.ParserRuleContext
import io.github.kotlinmania.antlr4.RecognitionException
import io.github.kotlinmania.antlr4.Recognizer
import io.github.kotlinmania.antlr4.Token
import io.github.kotlinmania.antlr4.WritableToken
import io.github.kotlinmania.antlr4.tree.ErrorNode
import io.github.kotlinmania.antlr4.tree.ParseTree
import io.github.kotlinmania.antlr4.tree.TerminalNode
import io.github.kotlinmania.kotlincpp.grammar.KotlinLexer
import io.github.kotlinmania.kotlincpp.grammar.KotlinParser

internal class ParsedKotlinUnit(
    val fileCtx: KotlinParser.KotlinFileContext,
    val packets: List<SyntaxPacket>,
    val offsetTable: SourceOffsetTable,
    val tokCoreFirst: IntArray,
    val tokCoreLast: IntArray,
)

private class PreScanMarks(
    val identifierTokens: BooleanArray,
    val angleBracketDelims: BooleanArray,
    val funModifierTokens: BooleanArray,
    val stringExprClosingBraces: BooleanArray,
    val pairedSecondOperatorId: IntArray,
)

private sealed class RawPacketItem {
    abstract val startOffset: Int
    abstract val endOffset: Int
    abstract val rawText: String

    class Whitespace(
        override val startOffset: Int,
        override val endOffset: Int,
        override val rawText: String,
    ) : RawPacketItem()

    class Concrete(
        override val startOffset: Int,
        override val endOffset: Int,
        override val rawText: String,
        val factory: (packetIndex: Int, span: PacketSpan) -> SyntaxPacket,
    ) : RawPacketItem()
}

internal object KotlinPacketLexing {
    fun lexAndParse(source: String, sourcePath: String): ParsedKotlinUnit {
        val errorListener =
            object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?,
                ): Unit = throw IllegalArgumentException(
                    "$sourcePath:$line:${charPositionInLine + 1}: Kotlin syntax error: ${msg ?: "invalid syntax"}",
                )
            }

        val charStream = CharStreams.fromString(source, sourcePath)
        val lexer = KotlinLexer(charStream)
        lexer.removeErrorListeners()
        lexer.addErrorListener(errorListener)

        val tokenStream = CommonTokenStream(lexer)
        tokenStream.fill()

        val allTokens = tokenStream.allTokens()
        val nonEofCount =
            if (allTokens.isNotEmpty() && allTokens.last().type == Token.EOF) {
                allTokens.size - 1
            } else {
                allTokens.size
            }

        val mutatedNlIndices = temporarilyDisambiguateSameLineReturnNewlines(allTokens, nonEofCount)
        val fileCtx =
            try {
                val parser = KotlinParser(tokenStream)
                parser.removeErrorListeners()
                parser.addErrorListener(errorListener)
                parser.kotlinFile()
            } finally {
                restoreNewlineTokens(allTokens, mutatedNlIndices)
            }

        val sourceTokens = allTokens.subList(0, nonEofCount)
        repairExplicitDelegationSwallowedClassBodies(fileCtx, sourceTokens, sourcePath)

        val marks = preScanParseTree(fileCtx, nonEofCount, sourcePath, throwOnError = true)!!
        return buildParsedUnitFromTokens(source, sourcePath, fileCtx, sourceTokens, nonEofCount, marks)
    }

    fun lexAndParseOrNull(source: String, sourcePath: String): ParsedKotlinUnit? {
        var syntaxErrors = 0
        val errorListener =
            object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?,
                ) {
                    syntaxErrors += 1
                }
            }

        val charStream = CharStreams.fromString(source, sourcePath)
        val lexer = KotlinLexer(charStream)
        lexer.removeErrorListeners()
        lexer.addErrorListener(errorListener)

        val tokenStream = CommonTokenStream(lexer)
        tokenStream.fill()
        if (syntaxErrors > 0) return null

        val allTokens = tokenStream.allTokens()
        val nonEofCount =
            if (allTokens.isNotEmpty() && allTokens.last().type == Token.EOF) {
                allTokens.size - 1
            } else {
                allTokens.size
            }

        val mutatedNlIndices = temporarilyDisambiguateSameLineReturnNewlines(allTokens, nonEofCount)
        val fileCtx =
            try {
                val parser = KotlinParser(tokenStream)
                parser.removeErrorListeners()
                parser.addErrorListener(errorListener)
                parser.kotlinFile()
            } finally {
                restoreNewlineTokens(allTokens, mutatedNlIndices)
            }
        if (syntaxErrors > 0) return null

        val sourceTokens = allTokens.subList(0, nonEofCount)
        if (sourceTokens.any {
                it.type == KotlinLexer.Tokens.ErrorCharacter ||
                    it.type == KotlinLexer.Tokens.RESERVED ||
                    it.type == KotlinLexer.Tokens.SINGLE_QUOTE
            }
        ) {
            return null
        }

        repairExplicitDelegationSwallowedClassBodies(fileCtx, sourceTokens, sourcePath)
        val marks = preScanParseTree(fileCtx, nonEofCount, sourcePath, throwOnError = false) ?: return null
        return buildParsedUnitFromTokens(source, sourcePath, fileCtx, sourceTokens, nonEofCount, marks)
    }

    fun lexSnippetPacketsOrNull(source: String, sourcePath: String = "<doc>"): List<SyntaxPacket>? {
        if (source.contains("// port-lint: ignore")) return null
        var lexErrors = 0
        val errorListener =
            object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String?,
                    e: RecognitionException?,
                ) {
                    lexErrors += 1
                }
            }
        val charStream = CharStreams.fromString(source, sourcePath)
        val lexer = KotlinLexer(charStream)
        lexer.removeErrorListeners()
        lexer.addErrorListener(errorListener)

        val tokenStream = CommonTokenStream(lexer)
        tokenStream.fill()
        if (lexErrors > 0) return null

        val allTokens = tokenStream.allTokens()
        val nonEofCount =
            if (allTokens.isNotEmpty() && allTokens.last().type == Token.EOF) {
                allTokens.size - 1
            } else {
                allTokens.size
            }
        val sourceTokens = allTokens.subList(0, nonEofCount)
        if (sourceTokens.any {
                it.type == KotlinLexer.Tokens.ErrorCharacter ||
                    it.type == KotlinLexer.Tokens.SINGLE_QUOTE
            }
        ) {
            return null
        }

        val emptyMarks =
            PreScanMarks(
                identifierTokens = BooleanArray(nonEofCount),
                angleBracketDelims = BooleanArray(nonEofCount),
                funModifierTokens = BooleanArray(nonEofCount),
                stringExprClosingBraces = computeSnippetStringExprClosingBraces(sourceTokens),
                pairedSecondOperatorId = IntArray(nonEofCount),
            )
        val offsetTable = SourceOffsetTable(source)
        val rawItems = ArrayList<RawPacketItem>(nonEofCount + 8)
        val rawCoreFirst = IntArray(nonEofCount) { -1 }
        val rawCoreLast = IntArray(nonEofCount) { -1 }

        var utf16Cursor = 0
        for (tokIdx in 0 until nonEofCount) {
            val tok = sourceTokens[tokIdx]
            val text = tok.text.orEmpty()
            emitSingleAntlrToken(
                tok = tok,
                tokIdx = tokIdx,
                tokStartUtf16 = utf16Cursor,
                sourcePath = sourcePath,
                marks = emptyMarks,
                rawItems = rawItems,
                rawCoreFirst = rawCoreFirst,
                rawCoreLast = rawCoreLast,
                allowSnippetEllipsis = true,
            )
            utf16Cursor += text.length
        }
        if (utf16Cursor != source.length) return null

        val packets = ArrayList<SyntaxPacket>(rawItems.size)
        var rIdx = 0
        while (rIdx < rawItems.size) {
            when (val item = rawItems[rIdx]) {
                is RawPacketItem.Whitespace -> {
                    val wsStart = item.startOffset
                    var wsEnd = item.endOffset
                    val sb = StringBuilder(item.rawText)
                    rIdx += 1
                    while (rIdx < rawItems.size && rawItems[rIdx] is RawPacketItem.Whitespace) {
                        val nextWs = rawItems[rIdx] as RawPacketItem.Whitespace
                        sb.append(nextWs.rawText)
                        wsEnd = nextWs.endOffset
                        rIdx += 1
                    }
                    val finalIdx = packets.size
                    packets.add(buildWhitespacePacket(finalIdx, offsetTable.spanOf(wsStart, wsEnd), sb.toString()))
                }
                is RawPacketItem.Concrete -> {
                    val finalIdx = packets.size
                    packets.add(item.factory(finalIdx, offsetTable.spanOf(item.startOffset, item.endOffset)))
                    rIdx += 1
                }
            }
        }
        pairBalancedDelimiters(packets)
        UniversalSynTokenAssociation.attachAll(packets)
        return packets
    }

    private fun computeSnippetStringExprClosingBraces(sourceTokens: List<Token>): BooleanArray {
        val closing = BooleanArray(sourceTokens.size)
        val braceDepthStack = ArrayList<Int>()
        for ((idx, tok) in sourceTokens.withIndex()) {
            when (tok.type) {
                KotlinLexer.Tokens.LineStrExprStart,
                KotlinLexer.Tokens.MultiLineStrExprStart,
                -> braceDepthStack.add(0)

                KotlinLexer.Tokens.LCURL -> {
                    if (braceDepthStack.isNotEmpty()) {
                        braceDepthStack[braceDepthStack.lastIndex] = braceDepthStack.last() + 1
                    }
                }

                KotlinLexer.Tokens.RCURL -> {
                    if (braceDepthStack.isNotEmpty()) {
                        val innerDepth = braceDepthStack.last()
                        if (innerDepth == 0) {
                            braceDepthStack.removeAt(braceDepthStack.lastIndex)
                            closing[idx] = true
                        } else {
                            braceDepthStack[braceDepthStack.lastIndex] = innerDepth - 1
                        }
                    }
                }
            }
        }
        return closing
    }

    private fun buildParsedUnitFromTokens(
        source: String,
        sourcePath: String,
        fileCtx: KotlinParser.KotlinFileContext,
        sourceTokens: List<Token>,
        nonEofCount: Int,
        marks: PreScanMarks,
    ): ParsedKotlinUnit {
        val offsetTable = SourceOffsetTable(source)
        val rawItems = ArrayList<RawPacketItem>(nonEofCount + 16)
        val rawCoreFirst = IntArray(nonEofCount) { -1 }
        val rawCoreLast = IntArray(nonEofCount) { -1 }

        var utf16Cursor = 0
        var tokIdx = 0
        while (tokIdx < nonEofCount) {
            val tok = sourceTokens[tokIdx]
            val text = tok.text.orEmpty()
            val pairedOpId = marks.pairedSecondOperatorId[tokIdx]
            if (pairedOpId != 0 && tokIdx + 1 < nonEofCount) {
                val secondTok = sourceTokens[tokIdx + 1]
                val secondText = secondTok.text.orEmpty()
                if (secondTok.type == KotlinLexer.Tokens.EXCL_WS && secondText.length > 1) {
                    val opText = text + "!"
                    val opStart = utf16Cursor
                    val opEnd = opStart + opText.length
                    val rawIdx =
                        addConcrete(rawItems, opStart, opEnd, opText) { idx, span ->
                            SyntaxPacket.OperatorPacket(idx, span, opText, pairedOpId)
                        }
                    rawCoreFirst[tokIdx] = rawIdx
                    rawCoreLast[tokIdx] = rawIdx
                    rawCoreFirst[tokIdx + 1] = rawIdx
                    rawCoreLast[tokIdx + 1] = rawIdx
                    scanHiddenTrivia(secondText, 1, secondText.length, opEnd - 1, rawItems)
                    utf16Cursor += text.length + secondText.length
                } else {
                    val mergedText = text + secondText
                    val opStart = utf16Cursor
                    val opEnd = opStart + mergedText.length
                    val rawIdx =
                        addConcrete(rawItems, opStart, opEnd, mergedText) { idx, span ->
                            SyntaxPacket.OperatorPacket(idx, span, mergedText, pairedOpId)
                        }
                    rawCoreFirst[tokIdx] = rawIdx
                    rawCoreLast[tokIdx] = rawIdx
                    rawCoreFirst[tokIdx + 1] = rawIdx
                    rawCoreLast[tokIdx + 1] = rawIdx
                    utf16Cursor = opEnd
                }
                tokIdx += 2
                continue
            }

            emitSingleAntlrToken(
                tok = tok,
                tokIdx = tokIdx,
                tokStartUtf16 = utf16Cursor,
                sourcePath = sourcePath,
                marks = marks,
                rawItems = rawItems,
                rawCoreFirst = rawCoreFirst,
                rawCoreLast = rawCoreLast,
            )
            utf16Cursor += text.length
            tokIdx += 1
        }

        check(utf16Cursor == source.length) {
            "$sourcePath: UTF-16 token cursor mismatch ($utf16Cursor != ${source.length})"
        }

        val rawToFinal = IntArray(rawItems.size) { -1 }
        val packets = ArrayList<SyntaxPacket>(rawItems.size)
        var rIdx = 0
        while (rIdx < rawItems.size) {
            when (val item = rawItems[rIdx]) {
                is RawPacketItem.Whitespace -> {
                    val wsStart = item.startOffset
                    var wsEnd = item.endOffset
                    val sb = StringBuilder(item.rawText)
                    val firstRaw = rIdx
                    rIdx += 1
                    while (rIdx < rawItems.size && rawItems[rIdx] is RawPacketItem.Whitespace) {
                        val nextWs = rawItems[rIdx] as RawPacketItem.Whitespace
                        sb.append(nextWs.rawText)
                        wsEnd = nextWs.endOffset
                        rIdx += 1
                    }
                    val mergedWs = sb.toString()
                    val finalIdx = packets.size
                    for (k in firstRaw until rIdx) {
                        rawToFinal[k] = finalIdx
                    }
                    packets.add(buildWhitespacePacket(finalIdx, offsetTable.spanOf(wsStart, wsEnd), mergedWs))
                }

                is RawPacketItem.Concrete -> {
                    val finalIdx = packets.size
                    rawToFinal[rIdx] = finalIdx
                    packets.add(item.factory(finalIdx, offsetTable.spanOf(item.startOffset, item.endOffset)))
                    rIdx += 1
                }
            }
        }

        pairBalancedDelimiters(packets)
        UniversalSynTokenAssociation.attachAll(packets)

        val tokCoreFirst =
            IntArray(nonEofCount) { idx ->
                val raw = rawCoreFirst[idx]
                if (raw >= 0) rawToFinal[raw] else -1
            }
        val tokCoreLast =
            IntArray(nonEofCount) { idx ->
                val raw = rawCoreLast[idx]
                if (raw >= 0) rawToFinal[raw] else -1
            }

        return ParsedKotlinUnit(
            fileCtx = fileCtx,
            packets = packets,
            offsetTable = offsetTable,
            tokCoreFirst = tokCoreFirst,
            tokCoreLast = tokCoreLast,
        )
    }

    private fun preScanParseTree(
        root: ParserRuleContext,
        tokenCount: Int,
        sourcePath: String,
        throwOnError: Boolean,
    ): PreScanMarks? {
        val identifierTokens = BooleanArray(tokenCount)
        val angleBracketDelims = BooleanArray(tokenCount)
        val funModifierTokens = BooleanArray(tokenCount)
        val stringExprClosingBraces = BooleanArray(tokenCount)
        val pairedSecondOperatorId = IntArray(tokenCount)
        var hasParseError = false

        fun markToken(node: TerminalNode?, target: BooleanArray) {
            val idx = node?.symbol?.tokenIndex ?: return
            if (idx in 0 until tokenCount) {
                target[idx] = true
            }
        }

        fun visit(ctx: ParserRuleContext) {
            if (hasParseError) return
            if (ctx.exception != null) {
                if (throwOnError) {
                    val line = ctx.start?.line ?: 1
                    val col = (ctx.start?.charPositionInLine ?: 0) + 1
                    throw IllegalArgumentException("$sourcePath:$line:$col: Kotlin parse error in rule ${ctx.ruleIndex}")
                }
                hasParseError = true
                return
            }
            when (ctx) {
                is KotlinParser.SimpleIdentifierContext -> {
                    val children = ctx.children
                    if (children != null) {
                        for (child in children) {
                            if (child is TerminalNode) {
                                markToken(child, identifierTokens)
                            }
                        }
                    }
                }

                is KotlinParser.TypeParametersContext -> {
                    markToken(ctx.LANGLE(), angleBracketDelims)
                    markToken(ctx.RANGLE(), angleBracketDelims)
                }

                is KotlinParser.TypeArgumentsContext -> {
                    markToken(ctx.LANGLE(), angleBracketDelims)
                    markToken(ctx.RANGLE(), angleBracketDelims)
                }

                is KotlinParser.SuperExpressionContext -> {
                    markToken(ctx.LANGLE(), angleBracketDelims)
                    markToken(ctx.RANGLE(), angleBracketDelims)
                }

                is KotlinParser.ClassDeclarationContext -> {
                    if (ctx.FUN() != null && ctx.INTERFACE() != null) {
                        markToken(ctx.FUN(), funModifierTokens)
                    }
                }

                is KotlinParser.LineStringExpressionContext -> {
                    markToken(ctx.RCURL(), stringExprClosingBraces)
                }

                is KotlinParser.MultiLineStringExpressionContext -> {
                    markToken(ctx.RCURL(), stringExprClosingBraces)
                }

                is KotlinParser.ElvisContext -> {
                    val qIdx = ctx.QUEST_NO_WS()?.symbol?.tokenIndex ?: -1
                    val cIdx = ctx.COLON()?.symbol?.tokenIndex ?: -1
                    if (qIdx >= 0 && cIdx == qIdx + 1 && qIdx < tokenCount) {
                        pairedSecondOperatorId[qIdx] = KtTokens.ELVIS_ID
                    }
                }

                is KotlinParser.SafeNavContext -> {
                    val qIdx = ctx.QUEST_NO_WS()?.symbol?.tokenIndex ?: -1
                    val dIdx = ctx.DOT()?.symbol?.tokenIndex ?: -1
                    if (qIdx >= 0 && dIdx == qIdx + 1 && qIdx < tokenCount) {
                        pairedSecondOperatorId[qIdx] = KtTokens.SAFE_ACCESS_ID
                    }
                }

                is KotlinParser.PostfixUnaryOperatorContext -> {
                    val e1Idx = ctx.EXCL_NO_WS()?.symbol?.tokenIndex ?: -1
                    val exclCtx = ctx.excl()
                    val e2Idx = (exclCtx?.EXCL_NO_WS() ?: exclCtx?.EXCL_WS())?.symbol?.tokenIndex ?: -1
                    if (e1Idx >= 0 && e2Idx == e1Idx + 1 && e1Idx < tokenCount) {
                        pairedSecondOperatorId[e1Idx] = KtTokens.EXCLEXCL_ID
                    }
                }
            }

            val children = ctx.children ?: return
            for (child in children) {
                when (child) {
                    is ErrorNode -> {
                        if (throwOnError) {
                            val sym = child.symbol
                            val line = sym?.line ?: 1
                            val col = (sym?.charPositionInLine ?: 0) + 1
                            throw IllegalArgumentException(
                                "$sourcePath:$line:$col: unexpected token '${sym?.text.orEmpty()}'",
                            )
                        }
                        hasParseError = true
                        return
                    }
                    is ParserRuleContext -> visit(child)
                }
            }
        }

        visit(root)
        if (hasParseError) return null
        return PreScanMarks(
            identifierTokens = identifierTokens,
            angleBracketDelims = angleBracketDelims,
            funModifierTokens = funModifierTokens,
            stringExprClosingBraces = stringExprClosingBraces,
            pairedSecondOperatorId = pairedSecondOperatorId,
        )
    }

    private fun emitSingleAntlrToken(
        tok: Token,
        tokIdx: Int,
        tokStartUtf16: Int,
        sourcePath: String,
        marks: PreScanMarks,
        rawItems: MutableList<RawPacketItem>,
        rawCoreFirst: IntArray,
        rawCoreLast: IntArray,
        allowSnippetEllipsis: Boolean = false,
    ) {
        val text = tok.text.orEmpty()
        val tokEndUtf16 = tokStartUtf16 + text.length
        val type = tok.type

        if (allowSnippetEllipsis && type == KotlinLexer.Tokens.RESERVED) {
            val rawIdx =
                addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                    SyntaxPacket.DelimiterPacket(idx, span, text, KtTokens.RESERVED_DOTS_ID)
                }
            rawCoreFirst[tokIdx] = rawIdx
            rawCoreLast[tokIdx] = rawIdx
            return
        }

        if (type == KotlinLexer.Tokens.ErrorCharacter ||
            type == KotlinLexer.Tokens.RESERVED ||
            type == KotlinLexer.Tokens.SINGLE_QUOTE
        ) {
            throw IllegalArgumentException(
                "$sourcePath:${tok.line}:${tok.charPositionInLine + 1}: invalid Kotlin lexical token '$text'",
            )
        }

        when (type) {
            KotlinLexer.Tokens.WS,
            KotlinLexer.Tokens.NL,
            KotlinLexer.Tokens.Inside_WS,
            KotlinLexer.Tokens.Inside_NL,
            -> {
                rawItems.add(RawPacketItem.Whitespace(tokStartUtf16, tokEndUtf16, text))
                return
            }

            KotlinLexer.Tokens.ShebangLine -> {
                addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                    SyntaxPacket.CommentPacket(idx, span, text, CommentPacketKind.SHEBANG)
                }
                return
            }

            KotlinLexer.Tokens.LineComment -> {
                addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                    buildLineCommentPacket(idx, span, text)
                }
                return
            }

            KotlinLexer.Tokens.DelimitedComment -> {
                addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                    buildBlockOrKdocCommentPacket(idx, span, text)
                }
                return
            }

            KotlinLexer.Tokens.Inside_Comment -> {
                addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                    if (text.startsWith("//")) {
                        buildLineCommentPacket(idx, span, text)
                    } else {
                        buildBlockOrKdocCommentPacket(idx, span, text)
                    }
                }
                return
            }
        }

        if (marks.identifierTokens[tokIdx]) {
            val rawIdx =
                addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                    buildIdentPacket(idx, span, text)
                }
            rawCoreFirst[tokIdx] = rawIdx
            rawCoreLast[tokIdx] = rawIdx
            return
        }

        when (type) {
            KotlinLexer.Tokens.EXCL_WS -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokStartUtf16 + 1, "!") { idx, span ->
                        SyntaxPacket.OperatorPacket(idx, span, "!", KtTokens.EXCL_ID)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
                if (text.length > 1) {
                    scanHiddenTrivia(text, 1, text.length, tokStartUtf16, rawItems)
                }
            }

            KotlinLexer.Tokens.QUEST_WS -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokStartUtf16 + 1, "?") { idx, span ->
                        SyntaxPacket.OperatorPacket(idx, span, "?", KtTokens.QUEST_ID)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
                if (text.length > 1) {
                    scanHiddenTrivia(text, 1, text.length, tokStartUtf16, rawItems)
                }
            }

            KotlinLexer.Tokens.NOT_IS -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokStartUtf16 + 3, "!is") { idx, span ->
                        SyntaxPacket.OperatorPacket(idx, span, "!is", KtTokens.NOT_IS_ID)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
                if (text.length > 3) {
                    scanHiddenTrivia(text, 3, text.length, tokStartUtf16, rawItems)
                }
            }

            KotlinLexer.Tokens.NOT_IN -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokStartUtf16 + 3, "!in") { idx, span ->
                        SyntaxPacket.OperatorPacket(idx, span, "!in", KtTokens.NOT_IN_ID)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
                if (text.length > 3) {
                    scanHiddenTrivia(text, 3, text.length, tokStartUtf16, rawItems)
                }
            }

            KotlinLexer.Tokens.AT_POST_WS -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokStartUtf16 + 1, "@") { idx, span ->
                        SyntaxPacket.DelimiterPacket(idx, span, "@", KtTokens.AT_ID)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
                if (text.length > 1) {
                    scanHiddenTrivia(text, 1, text.length, tokStartUtf16, rawItems)
                }
            }

            KotlinLexer.Tokens.AT_PRE_WS -> {
                if (text.length > 1) {
                    scanHiddenTrivia(text, 0, text.length - 1, tokStartUtf16, rawItems)
                }
                val atStart = tokEndUtf16 - 1
                val rawIdx =
                    addConcrete(rawItems, atStart, tokEndUtf16, "@") { idx, span ->
                        SyntaxPacket.DelimiterPacket(idx, span, "@", KtTokens.AT_ID)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.AT_BOTH_WS -> {
                val atPos = findUncommentedAt(text, 0)
                if (atPos > 0) {
                    scanHiddenTrivia(text, 0, atPos, tokStartUtf16, rawItems)
                }
                val atAbs = tokStartUtf16 + atPos
                val rawIdx =
                    addConcrete(rawItems, atAbs, atAbs + 1, "@") { idx, span ->
                        SyntaxPacket.DelimiterPacket(idx, span, "@", KtTokens.AT_ID)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
                if (atPos + 1 < text.length) {
                    scanHiddenTrivia(text, atPos + 1, text.length, tokStartUtf16, rawItems)
                }
            }

            KotlinLexer.Tokens.RETURN_AT ->
                emitKeywordAtLabel(
                    text,
                    tokStartUtf16,
                    "return",
                    KtTokens.RETURN_KEYWORD_ID,
                    tokIdx,
                    rawItems,
                    rawCoreFirst,
                    rawCoreLast,
                )

            KotlinLexer.Tokens.CONTINUE_AT ->
                emitKeywordAtLabel(
                    text,
                    tokStartUtf16,
                    "continue",
                    KtTokens.CONTINUE_KEYWORD_ID,
                    tokIdx,
                    rawItems,
                    rawCoreFirst,
                    rawCoreLast,
                )

            KotlinLexer.Tokens.BREAK_AT ->
                emitKeywordAtLabel(
                    text,
                    tokStartUtf16,
                    "break",
                    KtTokens.BREAK_KEYWORD_ID,
                    tokIdx,
                    rawItems,
                    rawCoreFirst,
                    rawCoreLast,
                )

            KotlinLexer.Tokens.THIS_AT ->
                emitKeywordAtLabel(
                    text,
                    tokStartUtf16,
                    "this",
                    KtTokens.THIS_KEYWORD_ID,
                    tokIdx,
                    rawItems,
                    rawCoreFirst,
                    rawCoreLast,
                )

            KotlinLexer.Tokens.SUPER_AT ->
                emitKeywordAtLabel(
                    text,
                    tokStartUtf16,
                    "super",
                    KtTokens.SUPER_KEYWORD_ID,
                    tokIdx,
                    rawItems,
                    rawCoreFirst,
                    rawCoreLast,
                )

            KotlinLexer.Tokens.LineStrRef,
            KotlinLexer.Tokens.MultiLineStrRef,
            KotlinLexer.Tokens.FieldIdentifier,
            -> {
                val strFlags = PacketFlags.IS_SIGNIFICANT or PacketFlags.INTERPOLATED_STRING
                val firstRaw =
                    addConcrete(rawItems, tokStartUtf16, tokStartUtf16 + 1, "$") { idx, span ->
                        SyntaxPacket.StringSegmentPacket(idx, span, "$", StringSegmentKind.SHORT_ENTRY_START, strFlags)
                    }
                val identRawText = text.substring(1)
                val lastRaw =
                    addConcrete(rawItems, tokStartUtf16 + 1, tokEndUtf16, identRawText) { idx, span ->
                        buildIdentPacket(idx, span, identRawText, extraFlags = PacketFlags.INTERPOLATED_STRING)
                    }
                rawCoreFirst[tokIdx] = firstRaw
                rawCoreLast[tokIdx] = lastRaw
            }

            KotlinLexer.Tokens.QUOTE_OPEN -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        SyntaxPacket.StringSegmentPacket(idx, span, text, StringSegmentKind.OPEN_QUOTE)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.TRIPLE_QUOTE_OPEN -> {
                val flags = PacketFlags.IS_SIGNIFICANT or PacketFlags.RAW_MULTILINE_STRING
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        SyntaxPacket.StringSegmentPacket(idx, span, text, StringSegmentKind.OPEN_QUOTE, flags)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.QUOTE_CLOSE -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        SyntaxPacket.StringSegmentPacket(idx, span, text, StringSegmentKind.CLOSE_QUOTE)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.TRIPLE_QUOTE_CLOSE -> {
                val flags = PacketFlags.IS_SIGNIFICANT or PacketFlags.RAW_MULTILINE_STRING
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        SyntaxPacket.StringSegmentPacket(idx, span, text, StringSegmentKind.CLOSE_QUOTE, flags)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.LineStrText,
            KotlinLexer.Tokens.MultiLineStrText,
            KotlinLexer.Tokens.MultiLineStringQuote,
            -> {
                var flags = PacketFlags.IS_SIGNIFICANT
                if (type != KotlinLexer.Tokens.LineStrText) {
                    flags = flags or PacketFlags.RAW_MULTILINE_STRING
                }
                if (containsUnpairedUtf16Surrogate(text)) {
                    flags = flags or PacketFlags.HAS_LONE_SURROGATE
                }
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        SyntaxPacket.StringSegmentPacket(idx, span, text, StringSegmentKind.LITERAL_PART, flags)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.LineStrEscapedChar -> {
                var flags = PacketFlags.IS_SIGNIFICANT or PacketFlags.HAS_ESCAPES
                if (isUnicodeSurrogateEscape(text)) {
                    flags = flags or PacketFlags.HAS_LONE_SURROGATE
                }
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        SyntaxPacket.StringSegmentPacket(idx, span, text, StringSegmentKind.ESCAPE_SEQ, flags)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.LineStrExprStart,
            KotlinLexer.Tokens.MultiLineStrExprStart,
            -> {
                var flags = PacketFlags.IS_SIGNIFICANT or PacketFlags.INTERPOLATED_STRING
                if (type == KotlinLexer.Tokens.MultiLineStrExprStart) {
                    flags = flags or PacketFlags.RAW_MULTILINE_STRING
                }
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        SyntaxPacket.StringSegmentPacket(idx, span, text, StringSegmentKind.LONG_ENTRY_START, flags)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.RCURL -> {
                val rawIdx =
                    if (marks.stringExprClosingBraces[tokIdx]) {
                        val flags = PacketFlags.IS_SIGNIFICANT or PacketFlags.INTERPOLATED_STRING
                        addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                            SyntaxPacket.StringSegmentPacket(idx, span, text, StringSegmentKind.LONG_ENTRY_END, flags)
                        }
                    } else {
                        addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                            SyntaxPacket.DelimiterPacket(idx, span, text, KtTokens.RBRACE_ID)
                        }
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.LANGLE -> {
                val rawIdx =
                    if (marks.angleBracketDelims[tokIdx]) {
                        addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                            SyntaxPacket.DelimiterPacket(idx, span, text, KtTokens.LT_ID)
                        }
                    } else {
                        addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                            SyntaxPacket.OperatorPacket(idx, span, text, KtTokens.LT_ID)
                        }
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.RANGLE -> {
                val rawIdx =
                    if (marks.angleBracketDelims[tokIdx]) {
                        addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                            SyntaxPacket.DelimiterPacket(idx, span, text, KtTokens.GT_ID)
                        }
                    } else {
                        addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                            SyntaxPacket.OperatorPacket(idx, span, text, KtTokens.GT_ID)
                        }
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.Identifier,
            KotlinLexer.Tokens.IdentifierOrSoftKey,
            -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        buildIdentPacket(idx, span, text)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.IntegerLiteral,
            KotlinLexer.Tokens.HexLiteral,
            KotlinLexer.Tokens.BinLiteral,
            KotlinLexer.Tokens.UnsignedLiteral,
            KotlinLexer.Tokens.LongLiteral,
            -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        buildIntLiteralPacket(idx, span, text)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.RealLiteral,
            KotlinLexer.Tokens.FloatLiteral,
            KotlinLexer.Tokens.DoubleLiteral,
            -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        buildFloatLiteralPacket(idx, span, text)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.BooleanLiteral -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        SyntaxPacket.LiteralPacket(idx, span, text, LiteralPacketKind.BOOLEAN, radix = 10, numericWidthBits = 1)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.NullLiteral -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        SyntaxPacket.LiteralPacket(idx, span, text, LiteralPacketKind.NULL, radix = 10, numericWidthBits = 0)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            KotlinLexer.Tokens.CharacterLiteral -> {
                val rawIdx =
                    addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                        buildCharLiteralPacket(idx, span, text)
                    }
                rawCoreFirst[tokIdx] = rawIdx
                rawCoreLast[tokIdx] = rawIdx
            }

            else -> {
                val delimId = mapDelimiterId(type)
                if (delimId != 0) {
                    val rawIdx =
                        addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                            SyntaxPacket.DelimiterPacket(idx, span, text, delimId)
                        }
                    rawCoreFirst[tokIdx] = rawIdx
                    rawCoreLast[tokIdx] = rawIdx
                    return
                }
                val opId = mapOperatorId(type)
                if (opId != 0) {
                    val rawIdx =
                        addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                            SyntaxPacket.OperatorPacket(idx, span, text, opId)
                        }
                    rawCoreFirst[tokIdx] = rawIdx
                    rawCoreLast[tokIdx] = rawIdx
                    return
                }
                val kwId =
                    if (marks.funModifierTokens[tokIdx]) {
                        KtTokens.FUN_KEYWORD_ID
                    } else {
                        mapKeywordId(type)
                    }
                if (kwId != 0) {
                    val flags = PacketFlags.IS_SIGNIFICANT or keywordPacketFlags(kwId)
                    val rawIdx =
                        addConcrete(rawItems, tokStartUtf16, tokEndUtf16, text) { idx, span ->
                            SyntaxPacket.KeywordPacket(idx, span, text, kwId, flags)
                        }
                    rawCoreFirst[tokIdx] = rawIdx
                    rawCoreLast[tokIdx] = rawIdx
                    return
                }
                throw IllegalArgumentException(
                    "$sourcePath:${tok.line}:${tok.charPositionInLine + 1}: unsupported Kotlin token type $type ('$text')",
                )
            }
        }
    }

    private fun emitKeywordAtLabel(
        text: String,
        tokStartUtf16: Int,
        keywordLiteral: String,
        keywordId: Int,
        tokIdx: Int,
        rawItems: MutableList<RawPacketItem>,
        rawCoreFirst: IntArray,
        rawCoreLast: IntArray,
    ) {
        val kwLen = keywordLiteral.length
        val atPos = findUncommentedAt(text, kwLen)
        val firstRaw =
            addConcrete(rawItems, tokStartUtf16, tokStartUtf16 + kwLen, keywordLiteral) { idx, span ->
                SyntaxPacket.KeywordPacket(idx, span, keywordLiteral, keywordId)
            }
        if (atPos > kwLen) {
            scanHiddenTrivia(text, kwLen, atPos, tokStartUtf16, rawItems)
        }
        val atAbs = tokStartUtf16 + atPos
        addConcrete(rawItems, atAbs, atAbs + 1, "@") { idx, span ->
            SyntaxPacket.DelimiterPacket(idx, span, "@", KtTokens.AT_ID)
        }
        val labelRaw = text.substring(atPos + 1)
        val lastRaw =
            addConcrete(rawItems, atAbs + 1, tokStartUtf16 + text.length, labelRaw) { idx, span ->
                buildIdentPacket(idx, span, labelRaw, role = SymbolRole.LABEL)
            }
        rawCoreFirst[tokIdx] = firstRaw
        rawCoreLast[tokIdx] = lastRaw
    }

    private fun scanHiddenTrivia(
        tokenText: String,
        startInTok: Int,
        endInTok: Int,
        tokBaseUtf16: Int,
        rawItems: MutableList<RawPacketItem>,
    ) {
        var i = startInTok
        while (i < endInTok) {
            if (i + 1 < endInTok && tokenText[i] == '/' && tokenText[i + 1] == '/') {
                val cStart = i
                i += 2
                while (i < endInTok && tokenText[i] != '\n' && tokenText[i] != '\r') {
                    i += 1
                }
                val slice = tokenText.substring(cStart, i)
                addConcrete(rawItems, tokBaseUtf16 + cStart, tokBaseUtf16 + i, slice) { idx, span ->
                    buildLineCommentPacket(idx, span, slice)
                }
            } else if (i + 1 < endInTok && tokenText[i] == '/' && tokenText[i + 1] == '*') {
                val cStart = i
                i += 2
                var depth = 1
                while (i < endInTok && depth > 0) {
                    if (i + 1 < endInTok && tokenText[i] == '/' && tokenText[i + 1] == '*') {
                        depth += 1
                        i += 2
                    } else if (i + 1 < endInTok && tokenText[i] == '*' && tokenText[i + 1] == '/') {
                        depth -= 1
                        i += 2
                    } else {
                        i += 1
                    }
                }
                val slice = tokenText.substring(cStart, i)
                addConcrete(rawItems, tokBaseUtf16 + cStart, tokBaseUtf16 + i, slice) { idx, span ->
                    buildBlockOrKdocCommentPacket(idx, span, slice)
                }
            } else {
                val wStart = i
                while (i < endInTok) {
                    if (i + 1 < endInTok && tokenText[i] == '/' && (tokenText[i + 1] == '/' || tokenText[i + 1] == '*')) {
                        break
                    }
                    i += 1
                }
                val slice = tokenText.substring(wStart, i)
                rawItems.add(RawPacketItem.Whitespace(tokBaseUtf16 + wStart, tokBaseUtf16 + i, slice))
            }
        }
    }

    private fun findUncommentedAt(text: String, startFrom: Int): Int {
        var i = startFrom
        while (i < text.length) {
            if (i + 1 < text.length && text[i] == '/' && text[i + 1] == '/') {
                i += 2
                while (i < text.length && text[i] != '\n' && text[i] != '\r') {
                    i += 1
                }
            } else if (i + 1 < text.length && text[i] == '/' && text[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < text.length && depth > 0) {
                    if (i + 1 < text.length && text[i] == '/' && text[i + 1] == '*') {
                        depth += 1
                        i += 2
                    } else if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '/') {
                        depth -= 1
                        i += 2
                    } else {
                        i += 1
                    }
                }
            } else if (text[i] == '@') {
                return i
            } else {
                i += 1
            }
        }
        return text.indexOf('@', startFrom).coerceAtLeast(startFrom)
    }

    private fun addConcrete(
        rawItems: MutableList<RawPacketItem>,
        startOffset: Int,
        endOffset: Int,
        rawText: String,
        factory: (packetIndex: Int, span: PacketSpan) -> SyntaxPacket,
    ): Int {
        val idx = rawItems.size
        rawItems.add(RawPacketItem.Concrete(startOffset, endOffset, rawText, factory))
        return idx
    }

    private fun buildWhitespacePacket(
        packetIndex: Int,
        span: PacketSpan,
        rawText: String,
    ): SyntaxPacket.WhitespacePacket {
        var newlines = 0
        var indentCols = 0
        var idx = 0
        while (idx < rawText.length) {
            when (rawText[idx]) {
                '\r' -> {
                    newlines += 1
                    indentCols = 0
                    if (idx + 1 < rawText.length && rawText[idx + 1] == '\n') {
                        idx += 1
                    }
                }
                '\n' -> {
                    newlines += 1
                    indentCols = 0
                }
                ' ' -> indentCols += 1
                '\t' -> indentCols += 4
                else -> indentCols += 1
            }
            idx += 1
        }
        return SyntaxPacket.WhitespacePacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            newlineCount = newlines,
            trailingIndentColumns = indentCols,
        )
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
            val spaceIdx = rest.indexOf(' ')
            val kind = if (spaceIdx > 0) rest.substring(0, spaceIdx).trim() else rest
            if (kind == "ignore") {
                throw IllegalArgumentException("forbidden 'port-lint: ignore' directive: $rawText")
            }
            if (spaceIdx > 0) {
                val target = rest.substring(spaceIdx + 1).trim()
                if ((kind == "source" || kind == "tests") && target.isNotEmpty()) {
                    directiveKind = kind
                    directiveTarget = target
                    flags = PacketFlags.PORT_LINT_DIRECTIVE
                }
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

    private fun buildBlockOrKdocCommentPacket(
        packetIndex: Int,
        span: PacketSpan,
        rawText: String,
    ): SyntaxPacket.CommentPacket {
        val isKdoc = rawText.startsWith("/**") && rawText != "/**/"
        val links = if (isKdoc) extractKdocLinks(rawText) else emptyList()
        val flags = if (links.isNotEmpty()) PacketFlags.DOC_COMMENT_HAS_LINKS else PacketFlags.NONE
        return SyntaxPacket.CommentPacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            kind = if (isKdoc) CommentPacketKind.KDOC else CommentPacketKind.BLOCK,
            kdocLinks = links,
            flags = flags,
        )
    }

    private fun extractKdocLinks(rawText: String): List<KDocLinkSpan> {
        val links = ArrayList<KDocLinkSpan>()
        var inCodeFence = false
        var i = 3
        val limit = (rawText.length - 2).coerceAtLeast(3)
        while (i < limit) {
            if (i + 2 < limit && rawText[i] == '`' && rawText[i + 1] == '`' && rawText[i + 2] == '`') {
                inCodeFence = !inCodeFence
                i += 3
                continue
            }
            if (!inCodeFence && rawText[i] == '`') {
                i += 1
                while (i < limit && rawText[i] != '`' && rawText[i] != '\n') {
                    i += 1
                }
                if (i < limit && rawText[i] == '`') i += 1
                continue
            }
            if (!inCodeFence && rawText[i] == '[') {
                val openIdx = i
                var closeIdx = openIdx + 1
                while (closeIdx < limit && rawText[closeIdx] != ']' && rawText[closeIdx] != '\n') {
                    closeIdx += 1
                }
                if (closeIdx < limit && rawText[closeIdx] == ']') {
                    if (closeIdx + 1 < limit && rawText[closeIdx + 1] == '(') {
                        i = closeIdx + 2
                        continue
                    }
                    if (closeIdx + 1 < limit && rawText[closeIdx + 1] == '[') {
                        val refOpen = closeIdx + 1
                        var refClose = refOpen + 1
                        while (refClose < limit && rawText[refClose] != ']' && rawText[refClose] != '\n') {
                            refClose += 1
                        }
                        if (refClose < limit && rawText[refClose] == ']') {
                            val target = rawText.substring(refOpen + 1, refClose).trim()
                            if (target.isNotEmpty()) {
                                links.add(KDocLinkSpan(refOpen, refClose + 1, target))
                            }
                            i = refClose + 1
                            continue
                        }
                    }
                    val target = rawText.substring(openIdx + 1, closeIdx).trim()
                    if (target.isNotEmpty() && target.none { it.isWhitespace() }) {
                        links.add(KDocLinkSpan(openIdx, closeIdx + 1, target))
                    }
                    i = closeIdx + 1
                    continue
                }
            }
            i += 1
        }
        return links
    }

    private fun buildIdentPacket(
        packetIndex: Int,
        span: PacketSpan,
        rawText: String,
        role: SymbolRole = SymbolRole.UNCLASSIFIED,
        extraFlags: Long = PacketFlags.NONE,
    ): SyntaxPacket.IdentPacket {
        val isBacktick = rawText.length >= 2 && rawText.first() == '`' && rawText.last() == '`'
        val unescaped = if (isBacktick) rawText.substring(1, rawText.length - 1) else rawText
        var flags = PacketFlags.IS_SIGNIFICANT or extraFlags
        if (isBacktick) {
            flags = flags or PacketFlags.BACKTICK_ESCAPED
        }
        return SyntaxPacket.IdentPacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            unescapedName = unescaped,
            role = role,
            flags = flags,
        )
    }

    private fun buildIntLiteralPacket(
        packetIndex: Int,
        span: PacketSpan,
        rawText: String,
    ): SyntaxPacket.LiteralPacket {
        val radix =
            when {
                rawText.startsWith("0x") || rawText.startsWith("0X") -> 16
                rawText.startsWith("0b") || rawText.startsWith("0B") -> 2
                else -> 10
            }
        val hasLongSuffix = rawText.endsWith('L')
        val bodyBeforeLong = if (hasLongSuffix) rawText.dropLast(1) else rawText
        val isUnsigned = bodyBeforeLong.endsWith('u') || bodyBeforeLong.endsWith('U')
        val digitsOnly =
            bodyBeforeLong
                .let { if (isUnsigned) it.dropLast(1) else it }
                .let { if (radix != 10) it.drop(2) else it }
                .replace("_", "")
        val widthBits =
            if (hasLongSuffix) {
                64
            } else if (isUnsigned) {
                val uVal = digitsOnly.toULongOrNull(radix)
                if (uVal != null && uVal > UInt.MAX_VALUE.toULong()) 64 else 32
            } else {
                val sVal = digitsOnly.toLongOrNull(radix)
                if (sVal == null || sVal > Int.MAX_VALUE.toLong()) 64 else 32
            }
        return SyntaxPacket.LiteralPacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            literalKind = LiteralPacketKind.INT,
            radix = radix,
            numericWidthBits = widthBits,
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
        var flags = PacketFlags.IS_SIGNIFICANT
        val inner = if (rawText.length >= 2) rawText.substring(1, rawText.length - 1) else ""
        if (containsUnpairedUtf16Surrogate(inner) || isUnicodeSurrogateEscape(inner)) {
            flags = flags or PacketFlags.HAS_LONE_SURROGATE
        }
        return SyntaxPacket.LiteralPacket(
            packetIndex = packetIndex,
            span = span,
            rawText = rawText,
            literalKind = LiteralPacketKind.CHAR,
            radix = 10,
            numericWidthBits = 16,
            isUnsigned = true,
            flags = flags,
        )
    }

    private fun containsUnpairedUtf16Surrogate(text: String): Boolean {
        var idx = 0
        while (idx < text.length) {
            val ch = text[idx]
            if (ch.isHighSurrogate()) {
                if (idx + 1 < text.length && text[idx + 1].isLowSurrogate()) {
                    idx += 2
                    continue
                }
                return true
            }
            if (ch.isLowSurrogate()) {
                return true
            }
            idx += 1
        }
        return false
    }

    private fun isUnicodeSurrogateEscape(escapeSeq: String): Boolean {
        if (escapeSeq.length == 6 && escapeSeq.startsWith("\\u")) {
            val code = escapeSeq.substring(2).toIntOrNull(16) ?: return false
            return code in 0xD800..0xDFFF
        }
        return false
    }

    private fun pairBalancedDelimiters(packets: MutableList<SyntaxPacket>) {
        val parenStack = ArrayList<Int>()
        val bracketStack = ArrayList<Int>()
        val braceStack = ArrayList<Int>()
        val angleStack = ArrayList<Int>()

        fun linkPair(openIdx: Int, closeIdx: Int) {
            val openPkt = packets[openIdx] as SyntaxPacket.DelimiterPacket
            val closePkt = packets[closeIdx] as SyntaxPacket.DelimiterPacket
            packets[openIdx] =
                openPkt.copy(matchingPacketIndex = closeIdx).also {
                    it.universalSynToken = openPkt.universalSynToken
                    it.synToken = openPkt.synToken
                    it.procMacroToken = openPkt.procMacroToken
                }
            packets[closeIdx] =
                closePkt.copy(matchingPacketIndex = openIdx).also {
                    it.universalSynToken = closePkt.universalSynToken
                    it.synToken = closePkt.synToken
                    it.procMacroToken = closePkt.procMacroToken
                }
        }

        for (idx in packets.indices) {
            val pkt = packets[idx] as? SyntaxPacket.DelimiterPacket ?: continue
            when (pkt.delimiterId) {
                KtTokens.LPAR_ID -> parenStack.add(idx)
                KtTokens.RPAR_ID -> if (parenStack.isNotEmpty()) linkPair(parenStack.removeAt(parenStack.lastIndex), idx)
                KtTokens.LBRACKET_ID -> bracketStack.add(idx)
                KtTokens.RBRACKET_ID -> if (bracketStack.isNotEmpty()) linkPair(bracketStack.removeAt(bracketStack.lastIndex), idx)
                KtTokens.LBRACE_ID -> braceStack.add(idx)
                KtTokens.RBRACE_ID -> if (braceStack.isNotEmpty()) linkPair(braceStack.removeAt(braceStack.lastIndex), idx)
                KtTokens.LT_ID -> angleStack.add(idx)
                KtTokens.GT_ID -> if (angleStack.isNotEmpty()) linkPair(angleStack.removeAt(angleStack.lastIndex), idx)
            }
        }
    }

    private fun mapDelimiterId(tokenType: Int): Int =
        when (tokenType) {
            KotlinLexer.Tokens.DOT -> KtTokens.DOT_ID
            KotlinLexer.Tokens.COMMA -> KtTokens.COMMA_ID
            KotlinLexer.Tokens.LPAREN -> KtTokens.LPAR_ID
            KotlinLexer.Tokens.RPAREN -> KtTokens.RPAR_ID
            KotlinLexer.Tokens.LSQUARE -> KtTokens.LBRACKET_ID
            KotlinLexer.Tokens.RSQUARE -> KtTokens.RBRACKET_ID
            KotlinLexer.Tokens.LCURL -> KtTokens.LBRACE_ID
            KotlinLexer.Tokens.COLON -> KtTokens.COLON_ID
            KotlinLexer.Tokens.SEMICOLON -> KtTokens.SEMICOLON_ID
            KotlinLexer.Tokens.DOUBLE_SEMICOLON -> KtTokens.DOUBLE_SEMICOLON_ID
            KotlinLexer.Tokens.HASH -> KtTokens.HASH_ID
            KotlinLexer.Tokens.AT_NO_WS -> KtTokens.AT_ID
            else -> 0
        }

    private fun mapOperatorId(tokenType: Int): Int =
        when (tokenType) {
            KotlinLexer.Tokens.MULT -> KtTokens.MUL_ID
            KotlinLexer.Tokens.MOD -> KtTokens.PERC_ID
            KotlinLexer.Tokens.DIV -> KtTokens.DIV_ID
            KotlinLexer.Tokens.ADD -> KtTokens.PLUS_ID
            KotlinLexer.Tokens.SUB -> KtTokens.MINUS_ID
            KotlinLexer.Tokens.INCR -> KtTokens.PLUSPLUS_ID
            KotlinLexer.Tokens.DECR -> KtTokens.MINUSMINUS_ID
            KotlinLexer.Tokens.CONJ -> KtTokens.ANDAND_ID
            KotlinLexer.Tokens.DISJ -> KtTokens.OROR_ID
            KotlinLexer.Tokens.EXCL_NO_WS -> KtTokens.EXCL_ID
            KotlinLexer.Tokens.ASSIGNMENT -> KtTokens.EQ_ID
            KotlinLexer.Tokens.ADD_ASSIGNMENT -> KtTokens.PLUSEQ_ID
            KotlinLexer.Tokens.SUB_ASSIGNMENT -> KtTokens.MINUSEQ_ID
            KotlinLexer.Tokens.MULT_ASSIGNMENT -> KtTokens.MULTEQ_ID
            KotlinLexer.Tokens.DIV_ASSIGNMENT -> KtTokens.DIVEQ_ID
            KotlinLexer.Tokens.MOD_ASSIGNMENT -> KtTokens.PERCEQ_ID
            KotlinLexer.Tokens.ARROW -> KtTokens.ARROW_ID
            KotlinLexer.Tokens.DOUBLE_ARROW -> KtTokens.DOUBLE_ARROW_ID
            KotlinLexer.Tokens.RANGE -> KtTokens.RANGE_ID
            KotlinLexer.Tokens.RANGE_UNTIL -> KtTokens.RANGE_UNTIL_ID
            KotlinLexer.Tokens.COLONCOLON -> KtTokens.COLONCOLON_ID
            KotlinLexer.Tokens.QUEST_NO_WS -> KtTokens.QUEST_ID
            KotlinLexer.Tokens.LE -> KtTokens.LTEQ_ID
            KotlinLexer.Tokens.GE -> KtTokens.GTEQ_ID
            KotlinLexer.Tokens.EXCL_EQ -> KtTokens.EXCLEQ_ID
            KotlinLexer.Tokens.EXCL_EQEQ -> KtTokens.EXCLEQEQEQ_ID
            KotlinLexer.Tokens.AS_SAFE -> KtTokens.AS_SAFE_ID
            KotlinLexer.Tokens.EQEQ -> KtTokens.EQEQ_ID
            KotlinLexer.Tokens.EQEQEQ -> KtTokens.EQEQEQ_ID
            KotlinLexer.Tokens.AMP -> KtTokens.AND_ID
            else -> 0
        }

    private fun mapKeywordId(tokenType: Int): Int =
        when (tokenType) {
            KotlinLexer.Tokens.FILE -> KtTokens.FILE_KEYWORD_ID
            KotlinLexer.Tokens.FIELD -> KtTokens.FIELD_KEYWORD_ID
            KotlinLexer.Tokens.PROPERTY -> KtTokens.PROPERTY_KEYWORD_ID
            KotlinLexer.Tokens.GET -> KtTokens.GET_KEYWORD_ID
            KotlinLexer.Tokens.SET -> KtTokens.SET_KEYWORD_ID
            KotlinLexer.Tokens.RECEIVER -> KtTokens.RECEIVER_KEYWORD_ID
            KotlinLexer.Tokens.PARAM -> KtTokens.PARAM_KEYWORD_ID
            KotlinLexer.Tokens.SETPARAM -> KtTokens.SETPARAM_KEYWORD_ID
            KotlinLexer.Tokens.DELEGATE -> KtTokens.DELEGATE_KEYWORD_ID
            KotlinLexer.Tokens.PACKAGE -> KtTokens.PACKAGE_KEYWORD_ID
            KotlinLexer.Tokens.IMPORT -> KtTokens.IMPORT_KEYWORD_ID
            KotlinLexer.Tokens.CLASS -> KtTokens.CLASS_KEYWORD_ID
            KotlinLexer.Tokens.INTERFACE -> KtTokens.INTERFACE_KEYWORD_ID
            KotlinLexer.Tokens.FUN -> KtTokens.FUN_KEYWORD_ID
            KotlinLexer.Tokens.OBJECT -> KtTokens.OBJECT_KEYWORD_ID
            KotlinLexer.Tokens.VAL -> KtTokens.VAL_KEYWORD_ID
            KotlinLexer.Tokens.VAR -> KtTokens.VAR_KEYWORD_ID
            KotlinLexer.Tokens.TYPE_ALIAS -> KtTokens.TYPEALIAS_KEYWORD_ID
            KotlinLexer.Tokens.CONSTRUCTOR -> KtTokens.CONSTRUCTOR_KEYWORD_ID
            KotlinLexer.Tokens.BY -> KtTokens.BY_KEYWORD_ID
            KotlinLexer.Tokens.COMPANION -> KtTokens.COMPANION_MODIFIER_ID
            KotlinLexer.Tokens.INIT -> KtTokens.INIT_KEYWORD_ID
            KotlinLexer.Tokens.THIS -> KtTokens.THIS_KEYWORD_ID
            KotlinLexer.Tokens.SUPER -> KtTokens.SUPER_KEYWORD_ID
            KotlinLexer.Tokens.TYPEOF -> KtTokens.TYPEOF_KEYWORD_ID
            KotlinLexer.Tokens.WHERE -> KtTokens.WHERE_KEYWORD_ID
            KotlinLexer.Tokens.IF -> KtTokens.IF_KEYWORD_ID
            KotlinLexer.Tokens.ELSE -> KtTokens.ELSE_KEYWORD_ID
            KotlinLexer.Tokens.WHEN -> KtTokens.WHEN_KEYWORD_ID
            KotlinLexer.Tokens.TRY -> KtTokens.TRY_KEYWORD_ID
            KotlinLexer.Tokens.CATCH -> KtTokens.CATCH_KEYWORD_ID
            KotlinLexer.Tokens.FINALLY -> KtTokens.FINALLY_KEYWORD_ID
            KotlinLexer.Tokens.FOR -> KtTokens.FOR_KEYWORD_ID
            KotlinLexer.Tokens.DO -> KtTokens.DO_KEYWORD_ID
            KotlinLexer.Tokens.WHILE -> KtTokens.WHILE_KEYWORD_ID
            KotlinLexer.Tokens.THROW -> KtTokens.THROW_KEYWORD_ID
            KotlinLexer.Tokens.RETURN -> KtTokens.RETURN_KEYWORD_ID
            KotlinLexer.Tokens.CONTINUE -> KtTokens.CONTINUE_KEYWORD_ID
            KotlinLexer.Tokens.BREAK -> KtTokens.BREAK_KEYWORD_ID
            KotlinLexer.Tokens.AS -> KtTokens.AS_KEYWORD_ID
            KotlinLexer.Tokens.IS -> KtTokens.IS_KEYWORD_ID
            KotlinLexer.Tokens.IN -> KtTokens.IN_KEYWORD_ID
            KotlinLexer.Tokens.OUT -> KtTokens.OUT_MODIFIER_ID
            KotlinLexer.Tokens.DYNAMIC -> KtTokens.DYNAMIC_KEYWORD_ID
            KotlinLexer.Tokens.PUBLIC -> KtTokens.PUBLIC_MODIFIER_ID
            KotlinLexer.Tokens.PRIVATE -> KtTokens.PRIVATE_MODIFIER_ID
            KotlinLexer.Tokens.PROTECTED -> KtTokens.PROTECTED_MODIFIER_ID
            KotlinLexer.Tokens.INTERNAL -> KtTokens.INTERNAL_MODIFIER_ID
            KotlinLexer.Tokens.ENUM -> KtTokens.ENUM_MODIFIER_ID
            KotlinLexer.Tokens.SEALED -> KtTokens.SEALED_MODIFIER_ID
            KotlinLexer.Tokens.ANNOTATION -> KtTokens.ANNOTATION_MODIFIER_ID
            KotlinLexer.Tokens.DATA -> KtTokens.DATA_MODIFIER_ID
            KotlinLexer.Tokens.INNER -> KtTokens.INNER_MODIFIER_ID
            KotlinLexer.Tokens.VALUE -> KtTokens.VALUE_MODIFIER_ID
            KotlinLexer.Tokens.TAILREC -> KtTokens.TAILREC_MODIFIER_ID
            KotlinLexer.Tokens.OPERATOR -> KtTokens.OPERATOR_MODIFIER_ID
            KotlinLexer.Tokens.INLINE -> KtTokens.INLINE_MODIFIER_ID
            KotlinLexer.Tokens.INFIX -> KtTokens.INFIX_MODIFIER_ID
            KotlinLexer.Tokens.EXTERNAL -> KtTokens.EXTERNAL_MODIFIER_ID
            KotlinLexer.Tokens.SUSPEND -> KtTokens.SUSPEND_MODIFIER_ID
            KotlinLexer.Tokens.OVERRIDE -> KtTokens.OVERRIDE_MODIFIER_ID
            KotlinLexer.Tokens.ABSTRACT -> KtTokens.ABSTRACT_MODIFIER_ID
            KotlinLexer.Tokens.FINAL -> KtTokens.FINAL_MODIFIER_ID
            KotlinLexer.Tokens.OPEN -> KtTokens.OPEN_MODIFIER_ID
            KotlinLexer.Tokens.CONST -> KtTokens.CONST_MODIFIER_ID
            KotlinLexer.Tokens.LATEINIT -> KtTokens.LATEINIT_MODIFIER_ID
            KotlinLexer.Tokens.VARARG -> KtTokens.VARARG_MODIFIER_ID
            KotlinLexer.Tokens.NOINLINE -> KtTokens.NOINLINE_MODIFIER_ID
            KotlinLexer.Tokens.CROSSINLINE -> KtTokens.CROSSINLINE_MODIFIER_ID
            KotlinLexer.Tokens.REIFIED -> KtTokens.REIFIED_MODIFIER_ID
            KotlinLexer.Tokens.EXPECT -> KtTokens.EXPECT_MODIFIER_ID
            KotlinLexer.Tokens.ACTUAL -> KtTokens.ACTUAL_MODIFIER_ID
            else -> 0
        }

    internal fun keywordPacketFlags(keywordId: Int): Long =
        when (keywordId) {
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

    /**
     * Prevents canonical `KotlinParser.g4`'s `jumpExpression : (RETURN | RETURN_AT) expression?`
     * combined with `infixFunctionCall : rangeExpression (simpleIdentifier NL* rangeExpression)*`
     * from dropping a same-line returned identifier `X` in `if (...) return X` and stealing the
     * following line's `<receiver>.<prop> = ...` statement across `NL`.
     *
     * Restored immediately in `finally` before packet construction so every [SyntaxPacket] and
     * lossless round-trip byte remains 100% identical to the input source.
     */
    private fun temporarilyDisambiguateSameLineReturnNewlines(
        allTokens: List<Token>,
        nonEofCount: Int,
    ): IntArray {
        val defaultIndices = IntArray(nonEofCount)
        var defaultCount = 0
        for (i in 0 until nonEofCount) {
            if (allTokens[i].channel == Token.DEFAULT_CHANNEL) {
                defaultIndices[defaultCount] = i
                defaultCount += 1
            }
        }
        val mutated = ArrayList<Int>(4)
        var dIdx = 0
        while (dIdx + 2 < defaultCount) {
            val t0 = allTokens[defaultIndices[dIdx]]
            if (t0.type == KotlinLexer.Tokens.RETURN || t0.type == KotlinLexer.Tokens.RETURN_AT) {
                val t1 = allTokens[defaultIndices[dIdx + 1]]
                val t2Idx = defaultIndices[dIdx + 2]
                val t2 = allTokens[t2Idx]
                if (t1.type != KotlinLexer.Tokens.NL &&
                    t1.type != KotlinLexer.Tokens.SEMICOLON &&
                    t2.type == KotlinLexer.Tokens.NL
                ) {
                    var look = dIdx + 3
                    while (look < defaultCount && allTokens[defaultIndices[look]].type == KotlinLexer.Tokens.NL) {
                        look += 1
                    }
                    val nextType = if (look < defaultCount) allTokens[defaultIndices[look]].type else Token.EOF
                    if (nextType !in LINE_CONTINUATION_AFTER_RETURN_TOKENS) {
                        val writable = t2 as? WritableToken
                        if (writable != null) {
                            writable.type = KotlinLexer.Tokens.SEMICOLON
                            mutated.add(t2Idx)
                        }
                    }
                }
            }
            dIdx += 1
        }
        return mutated.toIntArray()
    }

    private fun restoreNewlineTokens(
        allTokens: List<Token>,
        mutatedIndices: IntArray,
    ) {
        for (idx in mutatedIndices) {
            (allTokens[idx] as? WritableToken)?.type = KotlinLexer.Tokens.NL
        }
    }

    private val LINE_CONTINUATION_AFTER_RETURN_TOKENS: Set<Int> =
        setOf(
            KotlinLexer.Tokens.DOT,
            KotlinLexer.Tokens.QUEST_NO_WS,
            KotlinLexer.Tokens.COLONCOLON,
            KotlinLexer.Tokens.DISJ,
            KotlinLexer.Tokens.CONJ,
            KotlinLexer.Tokens.AS,
            KotlinLexer.Tokens.AS_SAFE,
            KotlinLexer.Tokens.LCURL,
            KotlinLexer.Tokens.AT_NO_WS,
            KotlinLexer.Tokens.AT_POST_WS,
            KotlinLexer.Tokens.ELSE,
            KotlinLexer.Tokens.CATCH,
            KotlinLexer.Tokens.FINALLY,
            KotlinLexer.Tokens.WHILE,
            KotlinLexer.Tokens.RPAREN,
            KotlinLexer.Tokens.RCURL,
            KotlinLexer.Tokens.RSQUARE,
            KotlinLexer.Tokens.COMMA,
            KotlinLexer.Tokens.SEMICOLON,
            KotlinLexer.Tokens.COLON,
            KotlinLexer.Tokens.ARROW,
        )

    /**
     * Repairs canonical `KotlinParser.g4`'s `explicitDelegation : (userType | functionType) NL* BY NL* expression`
     * greedily consuming a class/object body `{ ... }` as a trailing `annotatedLambda` on the delegate expression
     * (e.g. `class DispatchedContinuation<T>(...) : ..., Continuation<T> by continuation { ... }`).
     */
    private fun repairExplicitDelegationSwallowedClassBodies(
        root: ParserRuleContext,
        sourceTokens: List<Token>,
        sourcePath: String,
    ) {
        fun tryRepairHeader(ctx: ParserRuleContext) {
            val isCandidate =
                ctx is KotlinParser.ClassDeclarationContext ||
                    ctx is KotlinParser.ObjectDeclarationContext ||
                    ctx is KotlinParser.CompanionObjectContext ||
                    ctx is KotlinParser.ObjectLiteralContext
            if (!isCandidate) return
            val kids = ctx.children ?: return
            if (kids.any {
                    it is KotlinParser.ClassBodyContext ||
                        it is KotlinParser.EnumClassBodyContext ||
                        it is KotlinParser.TypeConstraintsContext
                }
            ) {
                return
            }
            val delSpecs =
                kids.lastOrNull { it is KotlinParser.DelegationSpecifiersContext }
                    as? KotlinParser.DelegationSpecifiersContext ?: return
            val lastAnnotSpec =
                delSpecs.children?.lastOrNull { it is KotlinParser.AnnotatedDelegationSpecifierContext }
                    as? KotlinParser.AnnotatedDelegationSpecifierContext ?: return
            val spec =
                lastAnnotSpec.children?.lastOrNull { it is KotlinParser.DelegationSpecifierContext }
                    as? KotlinParser.DelegationSpecifierContext ?: return
            val expDel = spec.explicitDelegation() ?: return
            val exprCtx =
                expDel.children?.lastOrNull { it is KotlinParser.ExpressionContext }
                    as? KotlinParser.ExpressionContext ?: return
            val exprStartIdx = exprCtx.start?.tokenIndex ?: return
            val exprStopTok = exprCtx.stop ?: return

            var cur: ParserRuleContext = exprCtx
            var foundPostfix: KotlinParser.PostfixUnaryExpressionContext? = null
            while (true) {
                if (cur is KotlinParser.PostfixUnaryExpressionContext && cur.stop === exprStopTok) {
                    val lastChild = cur.children?.lastOrNull()
                    if (lastChild is KotlinParser.PostfixUnarySuffixContext) {
                        val cs =
                            lastChild.children?.lastOrNull { it is KotlinParser.CallSuffixContext }
                                as? KotlinParser.CallSuffixContext
                        val al =
                            cs?.children?.lastOrNull { it is KotlinParser.AnnotatedLambdaContext }
                                as? KotlinParser.AnnotatedLambdaContext
                        if (al != null && al.stop === exprStopTok) {
                            foundPostfix = cur
                        }
                    }
                }
                val nextRule =
                    cur.children?.lastOrNull { it is ParserRuleContext && it.stop === exprStopTok }
                        as? ParserRuleContext ?: break
                cur = nextRule
            }

            val postfixCtx = foundPostfix ?: return
            val suffixCtx = postfixCtx.children?.lastOrNull() as? KotlinParser.PostfixUnarySuffixContext ?: return
            val callSuffix =
                suffixCtx.children?.lastOrNull { it is KotlinParser.CallSuffixContext }
                    as? KotlinParser.CallSuffixContext ?: return
            val lambdaCtx =
                callSuffix.children?.lastOrNull { it is KotlinParser.AnnotatedLambdaContext }
                    as? KotlinParser.AnnotatedLambdaContext ?: return
            val lambdaStartIdx = lambdaCtx.start?.tokenIndex ?: return
            val lambdaStopIdx = lambdaCtx.stop?.tokenIndex ?: return
            if (lambdaStartIdx <= exprStartIdx || lambdaStopIdx >= sourceTokens.size) return
            if (sourceTokens[lambdaStartIdx].type != KotlinLexer.Tokens.LCURL ||
                sourceTokens[lambdaStopIdx].type != KotlinLexer.Tokens.RCURL
            ) {
                return
            }

            var subErrors = 0
            val subListener =
                object : BaseErrorListener() {
                    override fun syntaxError(
                        recognizer: Recognizer<*, *>?,
                        offendingSymbol: Any?,
                        line: Int,
                        charPositionInLine: Int,
                        msg: String?,
                        e: RecognitionException?,
                    ) {
                        subErrors += 1
                    }
                }

            val slice = sourceTokens.subList(lambdaStartIdx, lambdaStopIdx + 1)
            val parsedBody: KotlinParser.ClassBodyContext
            val reachedEof: Boolean
            try {
                val subSource = ListTokenSource(slice, sourcePath)
                val subStream = CommonTokenStream(subSource)
                subStream.fill()
                val subParser = KotlinParser(subStream)
                subParser.removeErrorListeners()
                subParser.addErrorListener(subListener)
                parsedBody = subParser.classBody()
                reachedEof = subStream.LA(1) == Token.EOF
            } finally {
                for (tIdx in lambdaStartIdx..lambdaStopIdx) {
                    (sourceTokens[tIdx] as? WritableToken)?.tokenIndex = tIdx
                }
            }

            if (subErrors > 0 || !reachedEof || parsedBody.exception != null) return
            fun containsErrorNode(tree: ParseTree): Boolean {
                if (tree is ErrorNode) return true
                if (tree is ParserRuleContext) {
                    val ch = tree.children ?: return false
                    for (c in ch) {
                        if (containsErrorNode(c)) return true
                    }
                }
                return false
            }
            if (containsErrorNode(parsedBody)) return

            var newStopIdx = lambdaStartIdx - 1
            while (newStopIdx >= exprStartIdx) {
                val tok = sourceTokens[newStopIdx]
                if (tok.channel == Token.DEFAULT_CHANNEL && tok.type != KotlinLexer.Tokens.NL) {
                    break
                }
                newStopIdx -= 1
            }
            if (newStopIdx < exprStartIdx) return
            val newStopTok = sourceTokens[newStopIdx]

            val hasCallArgsOrTypeArgs =
                callSuffix.children?.any {
                    it is KotlinParser.ValueArgumentsContext || it is KotlinParser.TypeArgumentsContext
                } == true
            if (hasCallArgsOrTypeArgs) {
                while (callSuffix.children?.isNotEmpty() == true) {
                    val last = callSuffix.children!!.last()
                    if (last === lambdaCtx || (last is TerminalNode && last.symbol?.type == KotlinLexer.Tokens.NL)) {
                        callSuffix.removeLastChild()
                        if (last === lambdaCtx) break
                    } else {
                        break
                    }
                }
                while (callSuffix.children?.lastOrNull().let { it is TerminalNode && it.symbol?.type == KotlinLexer.Tokens.NL }) {
                    callSuffix.removeLastChild()
                }
                callSuffix.stop = newStopTok
                suffixCtx.stop = newStopTok
            } else {
                postfixCtx.removeLastChild()
                while (postfixCtx.children?.lastOrNull().let { it is TerminalNode && it.symbol?.type == KotlinLexer.Tokens.NL }) {
                    postfixCtx.removeLastChild()
                }
            }

            var anc: ParserRuleContext? = postfixCtx
            while (anc != null && anc !== ctx) {
                anc.stop = newStopTok
                anc = anc.parent as? ParserRuleContext
            }

            parsedBody.setParent(ctx)
            ctx.addChild(parsedBody)
        }

        fun walk(node: ParserRuleContext) {
            tryRepairHeader(node)
            val kids = node.children ?: return
            for (i in kids.indices) {
                val child = kids[i]
                if (child is ParserRuleContext) {
                    walk(child)
                }
            }
        }

        walk(root)
    }
}
