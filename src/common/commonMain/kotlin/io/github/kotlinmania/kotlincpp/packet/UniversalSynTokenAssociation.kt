package io.github.kotlinmania.kotlincpp.packet

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Ident
import io.github.kotlinmania.procmacro2.Literal
import io.github.kotlinmania.procmacro2.Punct
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.token.Abstract
import io.github.kotlinmania.syn.token.And
import io.github.kotlinmania.syn.token.AndAnd
import io.github.kotlinmania.syn.token.As
import io.github.kotlinmania.syn.token.Async
import io.github.kotlinmania.syn.token.At
import io.github.kotlinmania.syn.token.Brace
import io.github.kotlinmania.syn.token.Bracket
import io.github.kotlinmania.syn.token.Break
import io.github.kotlinmania.syn.token.Colon
import io.github.kotlinmania.syn.token.Comma
import io.github.kotlinmania.syn.token.Const
import io.github.kotlinmania.syn.token.Continue
import io.github.kotlinmania.syn.token.Do
import io.github.kotlinmania.syn.token.Dollar
import io.github.kotlinmania.syn.token.Dot
import io.github.kotlinmania.syn.token.DotDot
import io.github.kotlinmania.syn.token.DotDotDot
import io.github.kotlinmania.syn.token.Dyn
import io.github.kotlinmania.syn.token.Else
import io.github.kotlinmania.syn.token.Enum
import io.github.kotlinmania.syn.token.Eq
import io.github.kotlinmania.syn.token.EqEq
import io.github.kotlinmania.syn.token.Extern
import io.github.kotlinmania.syn.token.FatArrow
import io.github.kotlinmania.syn.token.Final
import io.github.kotlinmania.syn.token.Fn
import io.github.kotlinmania.syn.token.For
import io.github.kotlinmania.syn.token.Ge
import io.github.kotlinmania.syn.token.Gt
import io.github.kotlinmania.syn.token.If
import io.github.kotlinmania.syn.token.Impl
import io.github.kotlinmania.syn.token.In
import io.github.kotlinmania.syn.token.Le
import io.github.kotlinmania.syn.token.Let
import io.github.kotlinmania.syn.token.Lt
import io.github.kotlinmania.syn.token.Match
import io.github.kotlinmania.syn.token.Minus
import io.github.kotlinmania.syn.token.MinusEq
import io.github.kotlinmania.syn.token.Mod
import io.github.kotlinmania.syn.token.Mut
import io.github.kotlinmania.syn.token.Ne
import io.github.kotlinmania.syn.token.Not
import io.github.kotlinmania.syn.token.OrOr
import io.github.kotlinmania.syn.token.Override
import io.github.kotlinmania.syn.token.Paren
import io.github.kotlinmania.syn.token.PathSep
import io.github.kotlinmania.syn.token.Percent
import io.github.kotlinmania.syn.token.PercentEq
import io.github.kotlinmania.syn.token.Plus
import io.github.kotlinmania.syn.token.PlusEq
import io.github.kotlinmania.syn.token.Pound
import io.github.kotlinmania.syn.token.Priv
import io.github.kotlinmania.syn.token.Pub
import io.github.kotlinmania.syn.token.Question
import io.github.kotlinmania.syn.token.RArrow
import io.github.kotlinmania.syn.token.Return
import io.github.kotlinmania.syn.token.SelfValue
import io.github.kotlinmania.syn.token.Semi
import io.github.kotlinmania.syn.token.Slash
import io.github.kotlinmania.syn.token.SlashEq
import io.github.kotlinmania.syn.token.Star
import io.github.kotlinmania.syn.token.StarEq
import io.github.kotlinmania.syn.token.Static
import io.github.kotlinmania.syn.token.Struct
import io.github.kotlinmania.syn.token.Super
import io.github.kotlinmania.syn.token.SynTypeToken
import io.github.kotlinmania.syn.token.Token
import io.github.kotlinmania.syn.token.Trait
import io.github.kotlinmania.syn.token.Try
import io.github.kotlinmania.syn.token.Typeof
import io.github.kotlinmania.syn.token.Underscore
import io.github.kotlinmania.syn.token.Use
import io.github.kotlinmania.syn.token.Virtual
import io.github.kotlinmania.syn.token.Where
import io.github.kotlinmania.syn.token.While
import io.github.kotlinmania.procmacro2.Group as ProcMacroGroup

/**
 * Associates every significant [SyntaxPacket] produced by the ANTLR4 Kotlin specification
 * grammar with its universal like-for-like `syn-kotlin` [Token] and `proc-macro2-kotlin`
 * [TokenTree] representation without rewriting the Kotlin source spelling.
 */
internal object UniversalSynTokenAssociation {
    fun attachAll(packets: List<SyntaxPacket>) {
        val span = Span.callSite()
        for (pkt in packets) {
            when (pkt) {
                is SyntaxPacket.WhitespacePacket,
                is SyntaxPacket.CommentPacket,
                -> Unit

                is SyntaxPacket.KeywordPacket -> {
                    val synTok = associateKeyword(pkt.keywordId, span)
                    pkt.synToken = synTok
                    val pmTree =
                        if (isValidProcMacroIdent(pkt.rawText)) {
                            TokenTree.Ident(Ident.new(pkt.rawText, span))
                        } else {
                            null
                        }
                    pkt.procMacroToken = pmTree
                    pkt.universalSynToken = synTok?.toString() ?: "Keyword(${pkt.rawText})"
                }

                is SyntaxPacket.IdentPacket -> {
                    if (pkt.unescapedName == "_") {
                        val uTok = Underscore(span)
                        pkt.synToken = uTok
                        pkt.procMacroToken = TokenTree.Ident(Ident.new("_", span))
                        pkt.universalSynToken = uTok.toString()
                    } else if (isValidProcMacroIdent(pkt.unescapedName)) {
                        val ident = Ident.new(pkt.unescapedName, span)
                        pkt.procMacroToken = TokenTree.Ident(ident)
                        pkt.universalSynToken = "Ident(${pkt.unescapedName})"
                    } else {
                        val lit = Literal.string(pkt.unescapedName)
                        pkt.procMacroToken = TokenTree.Literal(lit)
                        pkt.universalSynToken = "Ident(${pkt.unescapedName})"
                    }
                }

                is SyntaxPacket.LiteralPacket -> {
                    val pmTree: TokenTree =
                        when (pkt.literalKind) {
                            LiteralPacketKind.BOOLEAN,
                            LiteralPacketKind.NULL,
                            -> TokenTree.Ident(Ident.new(pkt.rawText, span))
                            LiteralPacketKind.INT,
                            LiteralPacketKind.FLOAT,
                            LiteralPacketKind.CHAR,
                            -> TokenTree.Literal(Literal.fromStrUnchecked(pkt.rawText))
                        }
                    pkt.procMacroToken = pmTree
                    pkt.universalSynToken = "Literal(${pkt.rawText})"
                }

                is SyntaxPacket.StringSegmentPacket -> {
                    when (pkt.segmentKind) {
                        StringSegmentKind.SHORT_ENTRY_START -> {
                            val dTok = Dollar(span)
                            pkt.synToken = dTok
                            pkt.procMacroToken = TokenTree.Punct(Punct('$', Spacing.Alone, span))
                            pkt.universalSynToken = dTok.toString()
                        }
                        StringSegmentKind.LONG_ENTRY_START -> {
                            val dTok = Dollar(span)
                            pkt.synToken = dTok
                            pkt.universalSynToken = "StringInterpStart(\${)"
                        }
                        StringSegmentKind.LONG_ENTRY_END -> {
                            val bTok = Brace(span)
                            pkt.synToken = bTok
                            pkt.universalSynToken = "StringInterpEnd(})"
                        }
                        StringSegmentKind.OPEN_QUOTE,
                        StringSegmentKind.CLOSE_QUOTE,
                        -> {
                            pkt.universalSynToken = "StringQuote(${pkt.rawText})"
                        }
                        StringSegmentKind.INTERP_PREFIX,
                        StringSegmentKind.LITERAL_PART,
                        StringSegmentKind.ESCAPE_SEQ,
                        -> {
                            pkt.universalSynToken = "StringPart(${pkt.rawText})"
                        }
                    }
                }

                is SyntaxPacket.OperatorPacket -> {
                    val synTok = associateOperator(pkt.operatorId, span)
                    pkt.synToken = synTok
                    if (pkt.rawText.isNotEmpty() && pkt.rawText[0].isLetter()) {
                        pkt.procMacroToken = TokenTree.Ident(Ident.new(pkt.rawText, span))
                    } else if (pkt.rawText.length == 1) {
                        pkt.procMacroToken = TokenTree.Punct(Punct(pkt.rawText[0], Spacing.Alone, span))
                    }
                    pkt.universalSynToken = synTok?.toString() ?: "Punct(${pkt.rawText})"
                }

                is SyntaxPacket.DelimiterPacket -> {
                    val synTok = associateDelimiter(pkt.delimiterId, span)
                    pkt.synToken = synTok
                    if (pkt.delimiterId !in GROUP_DELIMITER_IDS && pkt.rawText.length == 1) {
                        pkt.procMacroToken = TokenTree.Punct(Punct(pkt.rawText[0], Spacing.Alone, span))
                    }
                    pkt.universalSynToken = synTok?.toString() ?: "Delim(${pkt.rawText})"
                }
            }
        }
    }

    fun buildTokenStream(stream: PacketStream): TokenStream {
        val span = Span.callSite()
        val packets = stream.packets

        fun collectRange(startIdx: Int, endExclusive: Int): TokenStream {
            val trees = ArrayList<TokenTree>()
            var idx = startIdx
            while (idx < endExclusive) {
                when (val pkt = packets[idx]) {
                    is SyntaxPacket.WhitespacePacket,
                    is SyntaxPacket.CommentPacket,
                    -> idx += 1

                    is SyntaxPacket.DelimiterPacket -> {
                        val groupDelim =
                            when (pkt.delimiterId) {
                                KtTokens.LPAR_ID -> Delimiter.Parenthesis
                                KtTokens.LBRACE_ID -> Delimiter.Brace
                                KtTokens.LBRACKET_ID -> Delimiter.Bracket
                                else -> null
                            }
                        if (groupDelim != null && pkt.matchingPacketIndex > idx && pkt.matchingPacketIndex < endExclusive) {
                            val inner = collectRange(idx + 1, pkt.matchingPacketIndex)
                            val grp = ProcMacroGroup(groupDelim, inner)
                            grp.setSpan(span)
                            trees.add(TokenTree.Group(grp))
                            idx = pkt.matchingPacketIndex + 1
                        } else {
                            appendPunctChars(pkt.rawText, span, trees)
                            idx += 1
                        }
                    }

                    is SyntaxPacket.StringSegmentPacket -> {
                        if (pkt.segmentKind == StringSegmentKind.OPEN_QUOTE) {
                            // Collapse an entire Kotlin string literal / template into a single Literal token tree
                            // for group/leaf structural parity with TokenStream consumers.
                            val strEnd = findClosingStringQuote(packets, idx, endExclusive)
                            val rawLiteral =
                                buildString {
                                    for (sIdx in idx..strEnd) {
                                        append(packets[sIdx].rawText)
                                    }
                                }
                            trees.add(TokenTree.Literal(Literal.fromStrUnchecked(rawLiteral)))
                            idx = strEnd + 1
                        } else {
                            idx += 1
                        }
                    }

                    is SyntaxPacket.OperatorPacket -> {
                        if (pkt.rawText.isNotEmpty() && pkt.rawText.all { it.isLetter() }) {
                            trees.add(TokenTree.Ident(Ident.new(pkt.rawText, span)))
                        } else if (pkt.rawText == "!in" || pkt.rawText == "!is") {
                            trees.add(TokenTree.Punct(Punct('!', Spacing.Alone, span)))
                            trees.add(TokenTree.Ident(Ident.new(pkt.rawText.substring(1), span)))
                        } else if (pkt.rawText == "as?") {
                            trees.add(TokenTree.Ident(Ident.new("as", span)))
                            trees.add(TokenTree.Punct(Punct('?', Spacing.Alone, span)))
                        } else {
                            appendPunctChars(pkt.rawText, span, trees)
                        }
                        idx += 1
                    }

                    else -> {
                        pkt.procMacroToken?.let { trees.add(it) }
                        idx += 1
                    }
                }
            }
            return TokenStream.fromTokenTrees(trees)
        }

        return collectRange(0, packets.size)
    }

    private fun findClosingStringQuote(
        packets: List<SyntaxPacket>,
        openIdx: Int,
        endExclusive: Int,
    ): Int {
        var depth = 0
        for (i in openIdx until endExclusive) {
            val p = packets[i]
            if (p is SyntaxPacket.StringSegmentPacket) {
                if (p.segmentKind == StringSegmentKind.OPEN_QUOTE) {
                    depth += 1
                } else if (p.segmentKind == StringSegmentKind.CLOSE_QUOTE) {
                    depth -= 1
                    if (depth == 0) return i
                }
            }
        }
        return endExclusive - 1
    }

    private fun appendPunctChars(
        text: String,
        span: Span,
        out: MutableList<TokenTree>,
    ) {
        if (text.isEmpty()) return
        for (i in 0 until text.length - 1) {
            out.add(TokenTree.Punct(Punct(text[i], Spacing.Joint, span)))
        }
        out.add(TokenTree.Punct(Punct(text[text.length - 1], Spacing.Alone, span)))
    }

    private fun associateKeyword(
        keywordId: Int,
        span: Span,
    ): Token? =
        when (keywordId) {
            KtTokens.FUN_KEYWORD_ID -> Fn(span)
            KtTokens.SUSPEND_MODIFIER_ID -> Async(span)
            KtTokens.VAL_KEYWORD_ID -> Let(span)
            KtTokens.VAR_KEYWORD_ID -> Mut(span)
            KtTokens.CONST_MODIFIER_ID -> Const(span)
            KtTokens.PUBLIC_MODIFIER_ID -> Pub(span)
            KtTokens.PRIVATE_MODIFIER_ID -> Priv(span)
            KtTokens.CLASS_KEYWORD_ID,
            KtTokens.DATA_MODIFIER_ID,
            -> Struct(span)
            KtTokens.ENUM_MODIFIER_ID -> Enum(span)
            KtTokens.INTERFACE_KEYWORD_ID -> Trait(span)
            KtTokens.OBJECT_KEYWORD_ID -> Impl(span)
            KtTokens.COMPANION_MODIFIER_ID -> Static(span)
            KtTokens.PACKAGE_KEYWORD_ID -> Mod(span)
            KtTokens.IMPORT_KEYWORD_ID -> Use(span)
            KtTokens.TYPEALIAS_KEYWORD_ID -> SynTypeToken(span)
            KtTokens.TYPEOF_KEYWORD_ID -> Typeof(span)
            KtTokens.WHERE_KEYWORD_ID -> Where(span)
            KtTokens.IF_KEYWORD_ID -> If(span)
            KtTokens.ELSE_KEYWORD_ID -> Else(span)
            KtTokens.WHEN_KEYWORD_ID -> Match(span)
            KtTokens.FOR_KEYWORD_ID -> For(span)
            KtTokens.WHILE_KEYWORD_ID -> While(span)
            KtTokens.DO_KEYWORD_ID -> Do(span)
            KtTokens.TRY_KEYWORD_ID -> Try(span)
            KtTokens.RETURN_KEYWORD_ID -> Return(span)
            KtTokens.BREAK_KEYWORD_ID -> Break(span)
            KtTokens.CONTINUE_KEYWORD_ID -> Continue(span)
            KtTokens.THIS_KEYWORD_ID -> SelfValue(span)
            KtTokens.SUPER_KEYWORD_ID -> Super(span)
            KtTokens.IN_KEYWORD_ID -> In(span)
            KtTokens.AS_KEYWORD_ID -> As(span)
            KtTokens.ABSTRACT_MODIFIER_ID -> Abstract(span)
            KtTokens.FINAL_MODIFIER_ID -> Final(span)
            KtTokens.OPEN_MODIFIER_ID -> Virtual(span)
            KtTokens.OVERRIDE_MODIFIER_ID -> Override(span)
            KtTokens.EXTERNAL_MODIFIER_ID -> Extern(span)
            KtTokens.DYNAMIC_KEYWORD_ID -> Dyn(span)
            else -> null
        }

    private fun associateOperator(
        operatorId: Int,
        span: Span,
    ): Token? =
        when (operatorId) {
            KtTokens.PLUS_ID -> Plus(span)
            KtTokens.PLUSEQ_ID -> PlusEq(span)
            KtTokens.MINUS_ID -> Minus(span)
            KtTokens.MINUSEQ_ID -> MinusEq(span)
            KtTokens.MUL_ID -> Star(span)
            KtTokens.MULTEQ_ID -> StarEq(span)
            KtTokens.DIV_ID -> Slash(span)
            KtTokens.DIVEQ_ID -> SlashEq(span)
            KtTokens.PERC_ID -> Percent(span)
            KtTokens.PERCEQ_ID -> PercentEq(span)
            KtTokens.AND_ID -> And(span)
            KtTokens.ANDAND_ID -> AndAnd(span)
            KtTokens.OROR_ID -> OrOr(span)
            KtTokens.EXCL_ID -> Not(span)
            KtTokens.EQ_ID -> Eq(span)
            KtTokens.EQEQ_ID,
            KtTokens.EQEQEQ_ID,
            -> EqEq(span)
            KtTokens.EXCLEQ_ID,
            KtTokens.EXCLEQEQEQ_ID,
            -> Ne(span)
            KtTokens.LT_ID -> Lt(span)
            KtTokens.LTEQ_ID -> Le(span)
            KtTokens.GT_ID -> Gt(span)
            KtTokens.GTEQ_ID -> Ge(span)
            KtTokens.ARROW_ID -> RArrow(span)
            KtTokens.DOUBLE_ARROW_ID -> FatArrow(span)
            KtTokens.RANGE_ID,
            KtTokens.RANGE_UNTIL_ID,
            -> DotDot(span)
            KtTokens.COLONCOLON_ID -> PathSep(span)
            KtTokens.QUEST_ID,
            KtTokens.SAFE_ACCESS_ID,
            KtTokens.ELVIS_ID,
            -> Question(span)
            KtTokens.AS_SAFE_ID -> As(span)
            KtTokens.NOT_IN_ID,
            KtTokens.NOT_IS_ID,
            -> Not(span)
            else -> null
        }

    private fun associateDelimiter(
        delimiterId: Int,
        span: Span,
    ): Token? =
        when (delimiterId) {
            KtTokens.LPAR_ID,
            KtTokens.RPAR_ID,
            -> Paren(span)
            KtTokens.LBRACE_ID,
            KtTokens.RBRACE_ID,
            -> Brace(span)
            KtTokens.LBRACKET_ID,
            KtTokens.RBRACKET_ID,
            -> Bracket(span)
            KtTokens.LT_ID -> Lt(span)
            KtTokens.GT_ID -> Gt(span)
            KtTokens.COMMA_ID -> Comma(span)
            KtTokens.DOT_ID -> Dot(span)
            KtTokens.COLON_ID -> Colon(span)
            KtTokens.SEMICOLON_ID,
            KtTokens.DOUBLE_SEMICOLON_ID,
            -> Semi(span)
            KtTokens.AT_ID -> At(span)
            KtTokens.HASH_ID -> Pound(span)
            KtTokens.RESERVED_DOTS_ID -> DotDotDot(span)
            else -> null
        }

    private fun isValidProcMacroIdent(text: String): Boolean {
        if (text.isEmpty()) return false
        val first = text[0]
        if (!first.isLetter() && first != '_') return false
        for (i in 1 until text.length) {
            val ch = text[i]
            if (!ch.isLetterOrDigit() && ch != '_') return false
        }
        return true
    }

    private val GROUP_DELIMITER_IDS: Set<Int> =
        setOf(
            KtTokens.LPAR_ID,
            KtTokens.RPAR_ID,
            KtTokens.LBRACE_ID,
            KtTokens.RBRACE_ID,
            KtTokens.LBRACKET_ID,
            KtTokens.RBRACKET_ID,
        )
}
