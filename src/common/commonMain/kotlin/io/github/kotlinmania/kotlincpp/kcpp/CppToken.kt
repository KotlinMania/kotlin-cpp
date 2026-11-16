package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Ident
import io.github.kotlinmania.procmacro2.Literal
import io.github.kotlinmania.procmacro2.Punct
import io.github.kotlinmania.procmacro2.Spacing
import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.procmacro2.Group as ProcMacroGroup

public enum class CppDelimiter {
    PAREN,
    BRACE,
    BRACKET,
    ANGLE,
}

public enum class CppCommentKind {
    LINE,
    BLOCK,
    DOXYGEN,
}

public enum class CppLayoutKind {
    SPACE,
    TAB,
    CR,
    NEWLINE,
    INDENT_PUSH,
    INDENT_POP,
}

/**
 * Typed C++23 token leaf or delimited group produced by [CppTokenEmitter] or parsed by
 * [CppTokenStream.tokenizeCppCode].
 *
 * Modeled as a `sealed class` (never `sealed interface`) for clean Swift Export code generation.
 */
public sealed class CppToken {
    /** Unqualified C++ identifier (`std`, `vector`, `CoroutineContext`, `T`). */
    public data class Ident(
        public val name: String,
    ) : CppToken()

    /** C++23 reserved keyword (`const`, `void`, `return`, `template`, `typename`, ...). */
    public data class Keyword(
        public val keyword: String,
    ) : CppToken()

    /** Punctuator or operator (`::`, `->`, `<<=`, `<=>`, `*`, `&`, `,`, `;`, ...). */
    public data class Punct(
        public val symbol: String,
        public val jointToNext: Boolean = false,
    ) : CppToken()

    /** Numeric, character, string, or raw-string literal preserved verbatim. */
    public data class Literal(
        public val rawText: String,
    ) : CppToken()

    /** Delimited token sequence (`(...)`, `{...}`, `[...]`, `<...>`). */
    public data class Group(
        public val delimiter: CppDelimiter,
        public val tokens: List<CppToken>,
    ) : CppToken()

    /** C++11/23 attribute block (`[[...]]`). */
    public data class Attribute(
        public val entries: List<CppToken>,
    ) : CppToken()

    /** Preprocessor directive line (`#pragma once`, `#ifndef FOO`, `#include <any>`). */
    public data class Preprocessor(
        public val directive: String,
        public val argument: String = "",
    ) : CppToken()

    /** Line, block, or Doxygen comment (`// ...`, `/* ... */`, `/** ... */`). */
    public data class Comment(
        public val kind: CppCommentKind,
        public val formattedText: String,
    ) : CppToken()

    /** Whitespace or structural indentation control token. */
    public data class Layout(
        public val kind: CppLayoutKind,
        public val count: Int = 1,
    ) : CppToken()
}

/**
 * Immutable stream of C++23 [CppToken] nodes supporting $O(N)$ comment/string-safe
 * identifier queries, scope-aware identifier rewriting, deterministic C++23 serialization,
 * and structural projection into `proc-macro2-kotlin` [TokenStream].
 */
public class CppTokenStream(
    public val tokens: List<CppToken>,
) {
    public val size: Int
        get() = tokens.size

    public fun isEmpty(): Boolean = tokens.isEmpty()

    /** Returns true if [target] appears as a C++ identifier or keyword outside strings/comments/preprocessor. */
    public fun containsIdent(target: String): Boolean {
        if (target.isEmpty() || tokens.isEmpty()) return false
        return containsIdentInList(tokens, target)
    }

    /** Returns true if the first non-layout, non-comment token is [CppToken.Keyword] matching [keyword]. */
    public fun startsWithKeyword(keyword: String): Boolean {
        val first = firstSignificantToken(tokens) ?: return false
        return first is CppToken.Keyword && first.keyword == keyword
    }

    /** Returns true if the last non-layout, non-comment token ends with punctuation [symbol] or brace group. */
    public fun endsWithPunct(symbol: String): Boolean {
        val last = lastSignificantToken(tokens) ?: return false
        return when (last) {
            is CppToken.Punct -> last.symbol == symbol
            is CppToken.Group ->
                (symbol == "}" && last.delimiter == CppDelimiter.BRACE) ||
                    (symbol == ")" && last.delimiter == CppDelimiter.PAREN) ||
                    (symbol == "]" && last.delimiter == CppDelimiter.BRACKET) ||
                    (symbol == ">" && last.delimiter == CppDelimiter.ANGLE)
            else -> false
        }
    }

    /**
     * Rewrites whole identifiers matching keys in [renames] outside literals, comments,
     * and preprocessor directives.
     *
     * When [skipMemberAccessRhs] is true, an [CppToken.Ident] immediately preceded by `.`,
     * `->`, or `::` (with no intervening space) is left intact so member names (`ptr->T`,
     * `obj.T`, `Ns::T`) are preserved while trailing return types (`-> T`) are renamed.
     */
    public fun renameIdents(
        renames: Map<String, String>,
        skipMemberAccessRhs: Boolean = true,
    ): CppTokenStream {
        if (renames.isEmpty() || tokens.isEmpty()) return this
        val tokenizedReplacements = LinkedHashMap<String, List<CppToken>>(renames.size)
        for ((k, v) in renames) {
            tokenizedReplacements[k] = tokenizeCppCode(v).tokens
        }
        return CppTokenStream(renameInList(tokens, tokenizedReplacements, skipMemberAccessRhs))
    }

    /** Serializes this token stream to C++23 source text, starting nested lines with [baseIndent]. */
    public fun render(baseIndent: String = ""): String {
        val out = StringBuilder(tokens.size * 4 + 16)
        val state = RenderState(baseIndent = baseIndent)
        renderTokenList(tokens, out, state)
        return out.toString()
    }

    /** Projects non-layout, non-comment tokens into a balanced `proc-macro2-kotlin` [TokenStream]. */
    internal fun toProcMacroTokenStream(): TokenStream {
        val span = Span.callSite()
        return convertFlatRangeToProcMacro(tokens, 0, tokens.size, span).first
    }

    private fun convertFlatRangeToProcMacro(
        list: List<CppToken>,
        startIdx: Int,
        endExclusive: Int,
        span: Span,
    ): Pair<TokenStream, Int> {
        val trees = ArrayList<TokenTree>()
        var idx = startIdx
        while (idx < endExclusive) {
            when (val tok = list[idx]) {
                is CppToken.Layout,
                is CppToken.Comment,
                -> idx += 1

                is CppToken.Preprocessor -> {
                    trees.add(TokenTree.Punct(Punct('#', Spacing.Joint, span)))
                    if (tok.directive.isNotEmpty() && isValidProcMacroIdent(tok.directive)) {
                        trees.add(TokenTree.Ident(Ident.new(tok.directive, span)))
                    }
                    if (tok.argument.isNotEmpty()) {
                        val argTrees =
                            tokenizeCppCode(tok.argument).toProcMacroTokenStream()
                        for (t in argTrees) {
                            trees.add(t)
                        }
                    }
                    idx += 1
                }

                is CppToken.Ident -> {
                    if (isValidProcMacroIdent(tok.name)) {
                        trees.add(TokenTree.Ident(Ident.new(tok.name, span)))
                    } else {
                        trees.add(TokenTree.Literal(Literal.fromStrUnchecked(tok.name)))
                    }
                    idx += 1
                }

                is CppToken.Keyword -> {
                    if (isValidProcMacroIdent(tok.keyword)) {
                        trees.add(TokenTree.Ident(Ident.new(tok.keyword, span)))
                    } else {
                        trees.add(TokenTree.Literal(Literal.fromStrUnchecked(tok.keyword)))
                    }
                    idx += 1
                }

                is CppToken.Literal -> {
                    trees.add(TokenTree.Literal(Literal.fromStrUnchecked(tok.rawText)))
                    idx += 1
                }

                is CppToken.Group -> {
                    val inner = convertFlatRangeToProcMacro(tok.tokens, 0, tok.tokens.size, span).first
                    when (tok.delimiter) {
                        CppDelimiter.PAREN ->
                            trees.add(
                                TokenTree.Group(
                                    ProcMacroGroup(Delimiter.Parenthesis, inner).also { it.setSpan(span) },
                                ),
                            )
                        CppDelimiter.BRACE ->
                            trees.add(
                                TokenTree.Group(
                                    ProcMacroGroup(Delimiter.Brace, inner).also { it.setSpan(span) },
                                ),
                            )
                        CppDelimiter.BRACKET ->
                            trees.add(
                                TokenTree.Group(
                                    ProcMacroGroup(Delimiter.Bracket, inner).also { it.setSpan(span) },
                                ),
                            )
                        CppDelimiter.ANGLE -> {
                            trees.add(TokenTree.Punct(Punct('<', Spacing.Alone, span)))
                            for (t in inner) trees.add(t)
                            trees.add(TokenTree.Punct(Punct('>', Spacing.Alone, span)))
                        }
                    }
                    idx += 1
                }

                is CppToken.Attribute -> {
                    val innerStream = convertFlatRangeToProcMacro(tok.entries, 0, tok.entries.size, span).first
                    val innerGroup =
                        ProcMacroGroup(Delimiter.Bracket, innerStream).also { it.setSpan(span) }
                    val outerGroup =
                        ProcMacroGroup(
                            Delimiter.Bracket,
                            TokenStream.fromTokenTrees(listOf(TokenTree.Group(innerGroup))),
                        ).also { it.setSpan(span) }
                    trees.add(TokenTree.Group(outerGroup))
                    idx += 1
                }

                is CppToken.Punct -> {
                    val openDelim =
                        when (tok.symbol) {
                            "(" -> Delimiter.Parenthesis
                            "{" -> Delimiter.Brace
                            "[" -> Delimiter.Bracket
                            else -> null
                        }
                    if (openDelim != null) {
                        val matchIdx = findMatchingFlatClose(list, idx, tok.symbol, endExclusive)
                        if (matchIdx > idx) {
                            val inner = convertFlatRangeToProcMacro(list, idx + 1, matchIdx, span).first
                            val grp = ProcMacroGroup(openDelim, inner).also { it.setSpan(span) }
                            trees.add(TokenTree.Group(grp))
                            idx = matchIdx + 1
                            continue
                        }
                    }
                    appendPunctSymbol(tok.symbol, tok.jointToNext, span, trees)
                    idx += 1
                }
            }
        }
        return TokenStream.fromTokenTrees(trees) to idx
    }

    private class RenderState(
        val baseIndent: String,
    ) {
        var indentLevel: Int = 0
        var atLineStart: Boolean = true
        var lastNonLayoutWasWordLike: Boolean = false
        var justSawLayout: Boolean = true
    }

    private fun renderTokenList(
        list: List<CppToken>,
        out: StringBuilder,
        state: RenderState,
    ) {
        for (tok in list) {
            renderSingleToken(tok, out, state)
        }
    }

    private fun renderSingleToken(
        tok: CppToken,
        out: StringBuilder,
        state: RenderState,
    ) {
        when (tok) {
            is CppToken.Layout -> {
                when (tok.kind) {
                    CppLayoutKind.SPACE -> {
                        if (state.atLineStart) {
                            writeLineIndent(out, state)
                        }
                        repeat(tok.count) { out.append(' ') }
                        state.justSawLayout = true
                    }
                    CppLayoutKind.TAB -> {
                        if (state.atLineStart) {
                            writeLineIndent(out, state)
                        }
                        repeat(tok.count) { out.append('\t') }
                        state.justSawLayout = true
                    }
                    CppLayoutKind.CR -> {
                        repeat(tok.count) { out.append('\r') }
                    }
                    CppLayoutKind.NEWLINE -> {
                        repeat(tok.count) { out.append('\n') }
                        state.atLineStart = true
                        state.justSawLayout = true
                    }
                    CppLayoutKind.INDENT_PUSH -> {
                        state.indentLevel += tok.count
                    }
                    CppLayoutKind.INDENT_POP -> {
                        state.indentLevel = (state.indentLevel - tok.count).coerceAtLeast(0)
                    }
                }
            }

            is CppToken.Ident -> {
                prepareWordToken(out, state)
                out.append(tok.name)
                state.lastNonLayoutWasWordLike = true
                state.justSawLayout = false
            }

            is CppToken.Keyword -> {
                prepareWordToken(out, state)
                out.append(tok.keyword)
                state.lastNonLayoutWasWordLike = true
                state.justSawLayout = false
            }

            is CppToken.Literal -> {
                prepareWordToken(out, state)
                out.append(tok.rawText)
                state.lastNonLayoutWasWordLike = true
                state.justSawLayout = false
            }

            is CppToken.Punct -> {
                if (state.atLineStart) {
                    writeLineIndent(out, state)
                }
                out.append(tok.symbol)
                state.lastNonLayoutWasWordLike = false
                state.justSawLayout = false
            }

            is CppToken.Group -> {
                if (state.atLineStart) {
                    writeLineIndent(out, state)
                }
                val (openCh, closeCh) =
                    when (tok.delimiter) {
                        CppDelimiter.PAREN -> '(' to ')'
                        CppDelimiter.BRACE -> '{' to '}'
                        CppDelimiter.BRACKET -> '[' to ']'
                        CppDelimiter.ANGLE -> '<' to '>'
                    }
                out.append(openCh)
                state.lastNonLayoutWasWordLike = false
                state.justSawLayout = true
                renderTokenList(tok.tokens, out, state)
                if (state.atLineStart) {
                    writeLineIndent(out, state)
                }
                out.append(closeCh)
                state.lastNonLayoutWasWordLike = false
                state.justSawLayout = false
            }

            is CppToken.Attribute -> {
                prepareWordToken(out, state)
                out.append("[[")
                state.lastNonLayoutWasWordLike = false
                state.justSawLayout = true
                renderTokenList(tok.entries, out, state)
                if (state.atLineStart) {
                    writeLineIndent(out, state)
                }
                out.append("]]")
                state.lastNonLayoutWasWordLike = true
                state.justSawLayout = false
            }

            is CppToken.Preprocessor -> {
                // Explicit leading spaces are carried by Layout(SPACE); structured directives flush at column 0.
                state.atLineStart = false
                out.append('#').append(tok.directive)
                if (tok.argument.isNotEmpty()) {
                    if (!tok.argument[0].isWhitespace()) {
                        out.append(' ')
                    }
                    out.append(tok.argument)
                }
                state.lastNonLayoutWasWordLike = false
                state.justSawLayout = false
            }

            is CppToken.Comment -> {
                if (state.atLineStart) {
                    writeLineIndent(out, state)
                }
                out.append(tok.formattedText)
                state.lastNonLayoutWasWordLike = false
                state.justSawLayout = false
            }
        }
    }

    private fun prepareWordToken(
        out: StringBuilder,
        state: RenderState,
    ) {
        if (state.atLineStart) {
            writeLineIndent(out, state)
        } else if (!state.justSawLayout && state.lastNonLayoutWasWordLike) {
            out.append(' ')
        }
    }

    private fun writeLineIndent(
        out: StringBuilder,
        state: RenderState,
    ) {
        if (state.baseIndent.isNotEmpty()) {
            out.append(state.baseIndent)
        }
        repeat(state.indentLevel) {
            out.append("    ")
        }
        state.atLineStart = false
        state.justSawLayout = true
    }

    public companion object {
        private val CPP23_KEYWORDS: Set<String> =
            setOf(
                "alignas",
                "alignof",
                "and",
                "and_eq",
                "asm",
                "auto",
                "bitand",
                "bitor",
                "bool",
                "break",
                "case",
                "catch",
                "char",
                "char8_t",
                "char16_t",
                "char32_t",
                "class",
                "compl",
                "concept",
                "const",
                "consteval",
                "constexpr",
                "constinit",
                "const_cast",
                "continue",
                "co_await",
                "co_return",
                "co_yield",
                "decltype",
                "default",
                "delete",
                "do",
                "double",
                "dynamic_cast",
                "else",
                "enum",
                "explicit",
                "export",
                "extern",
                "false",
                "float",
                "for",
                "friend",
                "goto",
                "if",
                "inline",
                "int",
                "long",
                "mutable",
                "namespace",
                "new",
                "noexcept",
                "not",
                "not_eq",
                "nullptr",
                "operator",
                "or",
                "or_eq",
                "private",
                "protected",
                "public",
                "register",
                "reinterpret_cast",
                "requires",
                "return",
                "short",
                "signed",
                "sizeof",
                "static",
                "static_assert",
                "static_cast",
                "struct",
                "switch",
                "template",
                "this",
                "thread_local",
                "throw",
                "true",
                "try",
                "typedef",
                "typeid",
                "typename",
                "union",
                "unsigned",
                "using",
                "virtual",
                "void",
                "volatile",
                "wchar_t",
                "while",
                "xor",
                "xor_eq",
            )

        private val THREE_CHAR_PUNCT: Set<String> =
            setOf(
                "<=>",
                "<<=",
                ">>=",
                "->*",
                "...",
            )

        private val TWO_CHAR_PUNCT: Set<String> =
            setOf(
                "::",
                "->",
                "++",
                "--",
                "<<",
                ">>",
                "<=",
                ">=",
                "==",
                "!=",
                "&&",
                "||",
                "+=",
                "-=",
                "*=",
                "/=",
                "%=",
                "&=",
                "|=",
                "^=",
                ".*",
            )

        private fun isIdentStart(ch: Char): Boolean =
            ch == '_' || ch in 'a'..'z' || ch in 'A'..'Z'

        private fun isIdentContinue(ch: Char): Boolean =
            isIdentStart(ch) || ch in '0'..'9'

        private fun matchRawStringPrefixLen(
            code: String,
            idx: Int,
        ): Int {
            val n = code.length
            if (idx + 1 < n && code[idx] == 'R' && code[idx + 1] == '"') return 1
            if (idx + 2 < n &&
                (code[idx] == 'u' || code[idx] == 'U' || code[idx] == 'L') &&
                code[idx + 1] == 'R' &&
                code[idx + 2] == '"'
            ) {
                return 2
            }
            if (idx + 3 < n &&
                code[idx] == 'u' &&
                code[idx + 1] == '8' &&
                code[idx + 2] == 'R' &&
                code[idx + 3] == '"'
            ) {
                return 3
            }
            return -1
        }

        private fun matchStringOrCharPrefixLen(
            code: String,
            idx: Int,
        ): Int {
            val n = code.length
            val c = code[idx]
            if (c == '"' || c == '\'') return 0
            if ((c == 'u' || c == 'U' || c == 'L') && idx + 1 < n && (code[idx + 1] == '"' || code[idx + 1] == '\'')) {
                return 1
            }
            if (c == 'u' &&
                idx + 2 < n &&
                code[idx + 1] == '8' &&
                (code[idx + 2] == '"' || code[idx + 2] == '\'')
            ) {
                return 2
            }
            return -1
        }

        /** Tokenizes C++23 source text losslessly into [CppToken] nodes. */
        public fun tokenizeCppCode(code: String): CppTokenStream {
            if (code.isEmpty()) return CppTokenStream(emptyList())
            val out = ArrayList<CppToken>((code.length / 3).coerceAtLeast(8))
            val n = code.length
            var i = 0
            var atLineStart = true
            while (i < n) {
                val ch = code[i]
                when {
                    ch == ' ' -> {
                        var j = i + 1
                        while (j < n && code[j] == ' ') j += 1
                        out.add(CppToken.Layout(CppLayoutKind.SPACE, j - i))
                        i = j
                    }
                    ch == '\t' -> {
                        var j = i + 1
                        while (j < n && code[j] == '\t') j += 1
                        out.add(CppToken.Layout(CppLayoutKind.TAB, j - i))
                        i = j
                    }
                    ch == '\r' -> {
                        var j = i + 1
                        while (j < n && code[j] == '\r') j += 1
                        out.add(CppToken.Layout(CppLayoutKind.CR, j - i))
                        i = j
                    }
                    ch == '\n' -> {
                        var j = i + 1
                        while (j < n && code[j] == '\n') j += 1
                        out.add(CppToken.Layout(CppLayoutKind.NEWLINE, j - i))
                        atLineStart = true
                        i = j
                    }
                    ch == '/' && i + 1 < n && code[i + 1] == '/' -> {
                        var end = i + 2
                        while (end < n && code[end] != '\n' && code[end] != '\r') end += 1
                        out.add(CppToken.Comment(CppCommentKind.LINE, code.substring(i, end)))
                        atLineStart = false
                        i = end
                    }
                    ch == '/' && i + 1 < n && code[i + 1] == '*' -> {
                        val close = code.indexOf("*/", i + 2)
                        val end = if (close < 0) n else close + 2
                        val raw = code.substring(i, end)
                        val kind =
                            if (raw.startsWith("/**") && !raw.startsWith("/**/")) {
                                CppCommentKind.DOXYGEN
                            } else {
                                CppCommentKind.BLOCK
                            }
                        out.add(CppToken.Comment(kind, raw))
                        atLineStart = false
                        i = end
                    }
                    ch == '#' && atLineStart -> {
                        var dirStart = i + 1
                        while (dirStart < n && (code[dirStart] == ' ' || code[dirStart] == '\t')) dirStart += 1
                        var dirEnd = dirStart
                        while (dirEnd < n && isIdentContinue(code[dirEnd])) dirEnd += 1
                        val directive = code.substring(dirStart, dirEnd)
                        var lineEnd = dirEnd
                        while (lineEnd < n) {
                            val lc = code[lineEnd]
                            if (lc == '\n' || lc == '\r') {
                                val hasContinuation = lineEnd > i && code[lineEnd - 1] == '\\'
                                if (!hasContinuation) break
                                if (lc == '\r' && lineEnd + 1 < n && code[lineEnd + 1] == '\n') {
                                    lineEnd += 2
                                    continue
                                }
                            }
                            lineEnd += 1
                        }
                        val prefixSpaces = code.substring(i + 1, dirStart)
                        val rawRest = code.substring(dirEnd, lineEnd)
                        val argument =
                            if (prefixSpaces.isNotEmpty()) {
                                prefixSpaces + directive + rawRest
                            } else if (rawRest.length >= 2 && rawRest[0] == ' ' && !rawRest[1].isWhitespace()) {
                                rawRest.substring(1)
                            } else {
                                rawRest
                            }
                        val finalDirective = if (prefixSpaces.isEmpty()) directive else ""
                        out.add(CppToken.Preprocessor(finalDirective, argument))
                        atLineStart = false
                        i = lineEnd
                    }
                    else -> {
                        atLineStart = false
                        val rawPrefixLen = matchRawStringPrefixLen(code, i)
                        if (rawPrefixLen > 0) {
                            val openQuote = i + rawPrefixLen
                            val parenOpen = code.indexOf('(', openQuote + 1)
                            if (parenOpen >= 0) {
                                val delim = code.substring(openQuote + 1, parenOpen)
                                val endMarker = ")$delim\""
                                val closeIdx = code.indexOf(endMarker, parenOpen + 1)
                                val end = if (closeIdx < 0) n else closeIdx + endMarker.length
                                out.add(CppToken.Literal(code.substring(i, end)))
                                i = end
                                continue
                            }
                        }
                        val strPrefixLen = matchStringOrCharPrefixLen(code, i)
                        if (strPrefixLen >= 0) {
                            val quoteIdx = i + strPrefixLen
                            val quote = code[quoteIdx]
                            var j = quoteIdx + 1
                            while (j < n) {
                                val qc = code[j]
                                if (qc == '\\' && j + 1 < n) {
                                    j += 2
                                } else if (qc == quote) {
                                    j += 1
                                    break
                                } else {
                                    j += 1
                                }
                            }
                            out.add(CppToken.Literal(code.substring(i, j)))
                            i = j
                            continue
                        }
                        if (ch in '0'..'9' || (ch == '.' && i + 1 < n && code[i + 1] in '0'..'9')) {
                            var j = i + 1
                            while (j < n) {
                                val nc = code[j]
                                if (isIdentContinue(nc) || nc == '.' || nc == '\'') {
                                    if ((nc == 'e' || nc == 'E' || nc == 'p' || nc == 'P') &&
                                        j + 1 < n &&
                                        (code[j + 1] == '+' || code[j + 1] == '-')
                                    ) {
                                        j += 2
                                        continue
                                    }
                                    j += 1
                                } else {
                                    break
                                }
                            }
                            out.add(CppToken.Literal(code.substring(i, j)))
                            i = j
                            continue
                        }
                        if (isIdentStart(ch)) {
                            var j = i + 1
                            while (j < n && isIdentContinue(code[j])) j += 1
                            val word = code.substring(i, j)
                            if (word in CPP23_KEYWORDS) {
                                out.add(CppToken.Keyword(word))
                            } else {
                                out.add(CppToken.Ident(word))
                            }
                            i = j
                            continue
                        }
                        if (i + 3 <= n && code.substring(i, i + 3) in THREE_CHAR_PUNCT) {
                            val sym = code.substring(i, i + 3)
                            val joint = i + 3 < n && !code[i + 3].isWhitespace()
                            out.add(CppToken.Punct(sym, joint))
                            i += 3
                            continue
                        }
                        if (i + 2 <= n && code.substring(i, i + 2) in TWO_CHAR_PUNCT) {
                            val sym = code.substring(i, i + 2)
                            val joint = i + 2 < n && !code[i + 2].isWhitespace()
                            out.add(CppToken.Punct(sym, joint))
                            i += 2
                            continue
                        }
                        val sym = code.substring(i, i + 1)
                        val joint = i + 1 < n && !code[i + 1].isWhitespace()
                        out.add(CppToken.Punct(sym, joint))
                        i += 1
                    }
                }
            }
            return CppTokenStream(out)
        }

        private fun containsIdentInList(
            list: List<CppToken>,
            target: String,
        ): Boolean {
            for (tok in list) {
                when (tok) {
                    is CppToken.Ident -> if (tok.name == target) return true
                    is CppToken.Keyword -> if (tok.keyword == target) return true
                    is CppToken.Group -> if (containsIdentInList(tok.tokens, target)) return true
                    is CppToken.Attribute -> if (containsIdentInList(tok.entries, target)) return true
                    is CppToken.Punct,
                    is CppToken.Literal,
                    is CppToken.Preprocessor,
                    is CppToken.Comment,
                    is CppToken.Layout,
                    -> Unit
                }
            }
            return false
        }

        private fun firstSignificantToken(list: List<CppToken>): CppToken? {
            for (tok in list) {
                if (tok !is CppToken.Layout && tok !is CppToken.Comment) {
                    return tok
                }
            }
            return null
        }

        private fun lastSignificantToken(list: List<CppToken>): CppToken? {
            for (idx in list.indices.reversed()) {
                val tok = list[idx]
                if (tok !is CppToken.Layout && tok !is CppToken.Comment) {
                    return tok
                }
            }
            return null
        }

        private fun renameInList(
            list: List<CppToken>,
            renames: Map<String, List<CppToken>>,
            skipMemberAccessRhs: Boolean,
        ): List<CppToken> {
            val out = ArrayList<CppToken>(list.size)
            for (i in list.indices) {
                val tok = list[i]
                when (tok) {
                    is CppToken.Ident -> {
                        val prev = if (i > 0) list[i - 1] else null
                        val isScopeQualified = prev is CppToken.Punct && prev.symbol == "::"
                        val isMemberAccess =
                            skipMemberAccessRhs &&
                                prev is CppToken.Punct &&
                                (prev.symbol == "." || prev.symbol == "->" || prev.symbol == "::")
                        val repl = if (isScopeQualified || isMemberAccess) null else renames[tok.name]
                        if (repl != null) {
                            out.addAll(repl)
                        } else {
                            out.add(tok)
                        }
                    }
                    is CppToken.Group ->
                        out.add(
                            CppToken.Group(
                                tok.delimiter,
                                renameInList(tok.tokens, renames, skipMemberAccessRhs),
                            ),
                        )
                    is CppToken.Attribute ->
                        out.add(
                            CppToken.Attribute(
                                renameInList(tok.entries, renames, skipMemberAccessRhs),
                            ),
                        )
                    else -> out.add(tok)
                }
            }
            return out
        }

        private fun findMatchingFlatClose(
            list: List<CppToken>,
            openIdx: Int,
            openSym: String,
            endExclusive: Int,
        ): Int {
            val closeSym =
                when (openSym) {
                    "(" -> ")"
                    "{" -> "}"
                    "[" -> "]"
                    else -> return -1
                }
            var depth = 0
            for (i in openIdx until endExclusive) {
                val t = list[i]
                if (t is CppToken.Punct) {
                    if (t.symbol == openSym) {
                        depth += 1
                    } else if (t.symbol == closeSym) {
                        depth -= 1
                        if (depth == 0) return i
                    }
                }
            }
            return -1
        }

        private fun appendPunctSymbol(
            symbol: String,
            jointToNext: Boolean,
            span: Span,
            out: MutableList<TokenTree>,
        ) {
            if (symbol.isEmpty()) return
            for (i in 0 until symbol.length - 1) {
                out.add(TokenTree.Punct(Punct(symbol[i], Spacing.Joint, span)))
            }
            val tailSpacing = if (jointToNext) Spacing.Joint else Spacing.Alone
            out.add(TokenTree.Punct(Punct(symbol[symbol.length - 1], tailSpacing, span)))
        }

        private fun isValidProcMacroIdent(text: String): Boolean {
            if (text.isEmpty()) return false
            if (!isIdentStart(text[0])) return false
            if (text == "_") return false
            for (i in 1 until text.length) {
                if (!isIdentContinue(text[i])) return false
            }
            return true
        }
    }
}
