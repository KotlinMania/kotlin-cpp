package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.CommentPacketKind
import io.github.kotlinmania.kotlincpp.packet.KotlinPacketLexing
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.LiteralPacketKind
import io.github.kotlinmania.kotlincpp.packet.StringSegmentKind
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket
import io.github.kotlinmania.kotlincpp.packet.buildKotlinPacketStreamOrNull

/**
 * Deterministic packet-driven Kotlin-to-C++23 comment and KDoc translator.
 *
 * Mirrors `rust-kotlin`'s `KotlinComments.kt` invariants while operating strictly on
 * typed [SyntaxPacket] streams produced by [KotlinPacketLexing]:
 * 1. Never emits synthetic file-level `/** @file ... */` or `/** Transliterated from: ... */`
 *    doc blocks when the upstream Kotlin file has none.
 * 2. Translates fenced Kotlin code examples (` ``` ` / ` ```kotlin `) into C++23 (` ```cpp `)
 *    via the single `kcpp` packet/frame lowering pipeline (`renderDocExampleFromPacketStream`).
 * 3. Rewrites KDoc `[Target]` / `[label][Target]` symbol references, `@param` / `@see` tags,
 *    and inline backtick Kotlin code spans into idiomatic C++23 / Doxygen notation using
 *    [KotlinPacketLexing.lexSnippetPacketsOrNull].
 */
internal object CppComments {
    private const val WRAPPER_KT_FN_NAME = "kdocSnippetWrapper"
    private const val WRAPPER_CPP_FN_NAME = "kdoc_snippet_wrapper"

    private val VERBATIM_FENCE_LANGS: Set<String> =
        setOf(
            "text",
            "plain",
            "plaintext",
            "console",
            "sh",
            "bash",
            "zsh",
            "toml",
            "json",
            "yaml",
            "c",
            "cpp",
            "c++",
            "cxx",
            "diff",
            "mermaid",
        )

    private val TOP_LEVEL_DECL_STARTER_KEYWORD_IDS: Set<Int> =
        setOf(
            KtTokens.PACKAGE_KEYWORD_ID,
            KtTokens.IMPORT_KEYWORD_ID,
            KtTokens.CLASS_KEYWORD_ID,
            KtTokens.INTERFACE_KEYWORD_ID,
            KtTokens.OBJECT_KEYWORD_ID,
            KtTokens.FUN_KEYWORD_ID,
            KtTokens.TYPEALIAS_KEYWORD_ID,
            KtTokens.PUBLIC_MODIFIER_ID,
            KtTokens.PRIVATE_MODIFIER_ID,
            KtTokens.PROTECTED_MODIFIER_ID,
            KtTokens.INTERNAL_MODIFIER_ID,
            KtTokens.ENUM_MODIFIER_ID,
            KtTokens.SEALED_MODIFIER_ID,
            KtTokens.ANNOTATION_MODIFIER_ID,
            KtTokens.DATA_MODIFIER_ID,
            KtTokens.INNER_MODIFIER_ID,
            KtTokens.VALUE_MODIFIER_ID,
            KtTokens.TAILREC_MODIFIER_ID,
            KtTokens.OPERATOR_MODIFIER_ID,
            KtTokens.INLINE_MODIFIER_ID,
            KtTokens.INFIX_MODIFIER_ID,
            KtTokens.EXTERNAL_MODIFIER_ID,
            KtTokens.SUSPEND_MODIFIER_ID,
            KtTokens.OVERRIDE_MODIFIER_ID,
            KtTokens.ABSTRACT_MODIFIER_ID,
            KtTokens.FINAL_MODIFIER_ID,
            KtTokens.OPEN_MODIFIER_ID,
            KtTokens.CONST_MODIFIER_ID,
            KtTokens.EXPECT_MODIFIER_ID,
            KtTokens.ACTUAL_MODIFIER_ID,
        )

    private val DECL_DEFINING_KEYWORD_IDS: Set<Int> =
        setOf(
            KtTokens.CLASS_KEYWORD_ID,
            KtTokens.INTERFACE_KEYWORD_ID,
            KtTokens.OBJECT_KEYWORD_ID,
            KtTokens.FUN_KEYWORD_ID,
            KtTokens.TYPEALIAS_KEYWORD_ID,
        )

    private val CPP_PRIMITIVE_DOC_TYPES: Map<String, String> =
        mapOf(
            "Int" to "int",
            "UInt" to "unsigned int",
            "Long" to "long long",
            "ULong" to "unsigned long long",
            "Short" to "short",
            "Byte" to "signed char",
            "Boolean" to "bool",
            "Double" to "double",
            "Float" to "float",
            "Unit" to "void",
            "Char" to "char16_t",
            "String" to "std::string",
        )

    private val FILE_EXTENSIONS: Set<String> =
        setOf(
            "kt",
            "kts",
            "java",
            "rs",
            "c",
            "cc",
            "cpp",
            "cxx",
            "h",
            "hpp",
            "hxx",
            "md",
            "txt",
            "json",
            "toml",
            "yaml",
            "yml",
            "gradle",
        )

    fun formatCommentForCpp(
        comment: SyntaxPacket.CommentPacket,
        indent: String,
        knownFunctionNames: Set<String> = emptySet(),
        allowFenceTranslation: Boolean = true,
    ): String =
        when (comment.kind) {
            CommentPacketKind.SHEBANG -> ""
            CommentPacketKind.LINE -> formatLineComment(comment.rawText, indent, knownFunctionNames)
            CommentPacketKind.BLOCK -> formatBlockComment(comment.rawText, indent, knownFunctionNames)
            CommentPacketKind.KDOC -> formatKdocComment(comment.rawText, indent, knownFunctionNames, allowFenceTranslation)
        }

    private fun formatLineComment(
        rawText: String,
        indent: String,
        knownFunctionNames: Set<String>,
    ): String {
        val trimmed = rawText.trimEnd()
        if (!trimmed.startsWith("//")) return indent + trimmed
        val body = trimmed.removePrefix("//")
        if (body.isEmpty()) return "$indent//"
        val leadingSpace = if (body.startsWith(" ")) " " else ""
        val content = if (leadingSpace.isNotEmpty()) body.substring(1) else body
        if (content.startsWith("port-lint:") || content.startsWith("Transliterated from:")) {
            return "$indent//$leadingSpace$content"
        }
        val translated = translateProseLine(content, knownFunctionNames)
        return "$indent//$leadingSpace$translated".trimEnd()
    }

    private fun formatBlockComment(
        rawText: String,
        indent: String,
        knownFunctionNames: Set<String>,
    ): String {
        val lines = rawText.lines()
        if (lines.size <= 1) {
            val inner =
                rawText
                    .trim()
                    .removePrefix("/*")
                    .removeSuffix("*/")
                    .trim()
            val translated = translateProseLine(inner, knownFunctionNames)
            return if (translated.isEmpty()) "$indent/* */" else "$indent/* $translated */"
        }
        return buildString {
            append(indent).append("/*")
            val firstRest = lines[0].trim().removePrefix("/*").trim()
            if (firstRest.isNotEmpty()) {
                append(' ').append(translateProseLine(firstRest, knownFunctionNames))
            }
            for (idx in 1 until lines.lastIndex) {
                append('\n').append(indent)
                val lineTrimmed = lines[idx].trimStart()
                if (lineTrimmed.startsWith("*")) {
                    val afterStar = lineTrimmed.removePrefix("*")
                    if (afterStar.isBlank()) {
                        append(" *")
                    } else {
                        val content = if (afterStar.startsWith(" ")) afterStar.substring(1) else afterStar
                        append(" * ").append(translateProseLine(content, knownFunctionNames).trimEnd())
                    }
                } else {
                    append(translateProseLine(lineTrimmed, knownFunctionNames).trimEnd())
                }
            }
            val lastTrimmed = lines.last().trim()
            if (lastTrimmed == "*/") {
                append('\n').append(indent).append(" */")
            } else {
                val beforeClose = lastTrimmed.removeSuffix("*/").removePrefix("*").trim()
                append('\n').append(indent)
                if (beforeClose.isNotEmpty()) {
                    append(" * ").append(translateProseLine(beforeClose, knownFunctionNames)).append(" */")
                } else {
                    append(" */")
                }
            }
        }
    }

    private fun formatKdocComment(
        rawText: String,
        indent: String,
        knownFunctionNames: Set<String>,
        allowFenceTranslation: Boolean,
    ): String {
        val rawLines = rawText.lines()
        if (rawLines.size <= 1 && !rawText.contains("```")) {
            val inner =
                rawText
                    .trim()
                    .removePrefix("/**")
                    .removeSuffix("*/")
                    .trim()
            val translated = translateProseLine(inner, knownFunctionNames)
            return "$indent/** $translated */"
        }

        val bodyLines = extractKdocBodyLines(rawText)
        val renderedLines = translateKdocLines(bodyLines, knownFunctionNames, allowFenceTranslation)
        return renderKdocLines(renderedLines, indent)
    }

    private fun extractKdocBodyLines(rawText: String): List<String> {
        val stripped =
            rawText
                .trim()
                .removePrefix("/**")
                .removeSuffix("*/")
        val split = stripped.split('\n').map { it.trimEnd('\r') }
        val rawBody = ArrayList<String>(split.size)
        for ((idx, line) in split.withIndex()) {
            val trimmedStart = line.trimStart()
            if (idx == 0 && trimmedStart.isEmpty()) continue
            if (idx == split.lastIndex && trimmedStart.isEmpty()) continue
            if (trimmedStart.startsWith("*")) {
                val afterStar = trimmedStart.substring(1)
                rawBody.add(if (afterStar.startsWith(" ")) afterStar.substring(1) else afterStar)
            } else {
                rawBody.add(trimmedStart)
            }
        }
        return rawBody
    }

    private enum class FenceState {
        NONE,
        VERBATIM,
        KOTLIN,
    }

    private fun translateKdocLines(
        rawLines: List<String>,
        knownFunctionNames: Set<String>,
        allowFenceTranslation: Boolean,
    ): List<String> {
        val out = ArrayList<String>(rawLines.size + 4)
        var state = FenceState.NONE
        var openFenceLine = ""
        var fenceIndent = ""
        val bufferedFence = ArrayList<String>()

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (state == FenceState.NONE) {
                    val fencePos = line.indexOf("```")
                    fenceIndent = if (fencePos > 0) line.substring(0, fencePos) else ""
                    val lang =
                        trimmed
                            .removePrefix("```")
                            .trim()
                            .substringBefore(',')
                            .substringBefore(' ')
                            .lowercase()
                    if (!allowFenceTranslation || lang in VERBATIM_FENCE_LANGS) {
                        state = FenceState.VERBATIM
                        out.add(line.trimEnd())
                    } else {
                        state = FenceState.KOTLIN
                        openFenceLine = line.trimEnd()
                        bufferedFence.clear()
                    }
                } else {
                    if (state == FenceState.KOTLIN) {
                        flushKotlinCodeFence(
                            out = out,
                            openFenceLine = openFenceLine,
                            closeFenceLine = line.trimEnd(),
                            fenceIndent = fenceIndent,
                            bufferedFence = bufferedFence,
                        )
                        bufferedFence.clear()
                    } else {
                        out.add(line.trimEnd())
                    }
                    state = FenceState.NONE
                }
                continue
            }

            when (state) {
                FenceState.VERBATIM -> out.add(line.trimEnd())
                FenceState.KOTLIN -> bufferedFence.add(line)
                FenceState.NONE -> out.add(translateProseLine(line, knownFunctionNames).trimEnd())
            }
        }

        if (state == FenceState.KOTLIN && bufferedFence.isNotEmpty()) {
            flushKotlinCodeFence(
                out = out,
                openFenceLine = openFenceLine,
                closeFenceLine = "$fenceIndent```",
                fenceIndent = fenceIndent,
                bufferedFence = bufferedFence,
            )
        }
        return out
    }

    private fun flushKotlinCodeFence(
        out: MutableList<String>,
        openFenceLine: String,
        closeFenceLine: String,
        fenceIndent: String,
        bufferedFence: List<String>,
    ) {
        val translated = translateKotlinDocCodeBlock(bufferedFence)
        if (translated != null) {
            out.add("$fenceIndent```cpp")
            for (codeLine in translated) {
                if (codeLine.isBlank()) {
                    out.add("")
                } else {
                    out.add("$fenceIndent$codeLine".trimEnd())
                }
            }
            out.add("$fenceIndent```")
        } else {
            out.add(openFenceLine)
            for (raw in bufferedFence) {
                out.add(raw.trimEnd())
            }
            out.add(closeFenceLine)
        }
    }

    private fun translateKotlinDocCodeBlock(rawFenceLines: List<String>): List<String>? {
        if (rawFenceLines.isEmpty() || rawFenceLines.all { it.isBlank() }) return emptyList()
        val dedented = stripCommonIndentation(rawFenceLines)
        val joined = dedented.joinToString("\n")
        val rawPackets = KotlinPacketLexing.lexSnippetPacketsOrNull(joined, "<doc>") ?: return null
        val normalized = renderNormalizedSnippetFromPackets(rawPackets)
        val normalizedPackets =
            if (normalized == joined) {
                rawPackets
            } else {
                KotlinPacketLexing.lexSnippetPacketsOrNull(normalized, "<doc>") ?: return null
            }
        val topLevelDeclsOnly = isTopLevelDeclarationsOnly(normalizedPackets)

        return if (topLevelDeclsOnly) {
            val stream = buildKotlinPacketStreamOrNull(normalized, "<doc>") ?: return null
            val (headerBody, sourceBody) = renderDocExampleFromPacketStream(stream)
            val hasClassDecl =
                stream.frames.any {
                    it.nodeTypeId == KtNodeTypes.CLASS_ID || it.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID
                }
            val combined =
                when {
                    hasClassDecl && sourceBody.isNotBlank() ->
                        headerBody.trimEnd() + "\n\n" + sourceBody.trim()
                    hasClassDecl -> headerBody.trim()
                    sourceBody.isNotBlank() -> sourceBody.trim()
                    else -> headerBody.trim()
                }
            if (combined.isBlank()) emptyList() else combined.lines()
        } else {
            val wrapped = "public suspend fun $WRAPPER_KT_FN_NAME() {\n$normalized\n}\n"
            val stream = buildKotlinPacketStreamOrNull(wrapped, "<doc>") ?: return null
            val (headerBody, sourceBody) = renderDocExampleFromPacketStream(stream)
            val bodySource = sourceBody.ifBlank { headerBody }
            unwrapSyntheticSnippetFunction(bodySource)
        }
    }

    private fun unwrapSyntheticSnippetFunction(rendered: String): List<String>? {
        val lines = rendered.trim().lines()
        val headerIdx = lines.indexOfFirst { it.contains("$WRAPPER_CPP_FN_NAME(") && it.trimEnd().endsWith("{") }
        if (headerIdx < 0) return null
        var closeIdx = lines.lastIndex
        while (closeIdx > headerIdx && lines[closeIdx].trim() != "}") {
            closeIdx -= 1
        }
        if (closeIdx <= headerIdx) return null
        val prefixLines =
            lines
                .subList(0, headerIdx)
                .filterNot { it.trim().startsWith("[[clang::annotate(") }
                .dropLastWhile { it.isBlank() }
        val innerLines =
            lines.subList(headerIdx + 1, closeIdx).map { line ->
                if (line.startsWith("    ")) line.substring(4) else line
            }
        val result = ArrayList<String>(prefixLines.size + innerLines.size)
        if (prefixLines.isNotEmpty()) {
            result.addAll(prefixLines)
        }
        result.addAll(innerLines)
        while (result.firstOrNull()?.isBlank() == true) result.removeAt(0)
        while (result.lastOrNull()?.isBlank() == true) result.removeAt(result.lastIndex)
        return result
    }

    private fun renderNormalizedSnippetFromPackets(packets: List<SyntaxPacket>): String {
        if (packets.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < packets.size) {
            val pkt = packets[i]
            if (pkt is SyntaxPacket.DelimiterPacket && pkt.delimiterId == KtTokens.LBRACE_ID) {
                var j = i + 1
                while (j < packets.size && packets[j] is SyntaxPacket.WhitespacePacket) j += 1
                val dots = packets.getOrNull(j)
                if (dots is SyntaxPacket.DelimiterPacket && dots.delimiterId == KtTokens.RESERVED_DOTS_ID) {
                    var k = j + 1
                    while (k < packets.size && packets[k] is SyntaxPacket.WhitespacePacket) k += 1
                    val close = packets.getOrNull(k)
                    if (close is SyntaxPacket.DelimiterPacket && close.delimiterId == KtTokens.RBRACE_ID) {
                        sb.append("{}")
                        i = k + 1
                        continue
                    }
                }
            }
            sb.append(pkt.rawText)
            i += 1
        }
        return sb.toString()
    }

    private fun isTopLevelDeclarationsOnly(packets: List<SyntaxPacket>): Boolean {
        val sig = packets.filter { it.isSignificant }
        if (sig.isEmpty()) return false

        var braceDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var atStmtStart = true
        var sawDeclaration = false

        var idx = 0
        while (idx < sig.size) {
            val pkt = sig[idx]
            if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0 && atStmtStart) {
                if (pkt is SyntaxPacket.DelimiterPacket && pkt.delimiterId == KtTokens.SEMICOLON_ID) {
                    idx += 1
                    continue
                }
                if (pkt is SyntaxPacket.DelimiterPacket && pkt.delimiterId == KtTokens.AT_ID) {
                    idx += 1
                    while (idx < sig.size &&
                        (
                            sig[idx] is SyntaxPacket.IdentPacket ||
                                (sig[idx] as? SyntaxPacket.DelimiterPacket)?.delimiterId == KtTokens.DOT_ID
                        )
                    ) {
                        idx += 1
                    }
                    val maybeOpen = sig.getOrNull(idx)
                    if (maybeOpen is SyntaxPacket.DelimiterPacket && maybeOpen.delimiterId == KtTokens.LPAR_ID) {
                        var p = 1
                        idx += 1
                        while (idx < sig.size && p > 0) {
                            val d = sig[idx] as? SyntaxPacket.DelimiterPacket
                            if (d?.delimiterId == KtTokens.LPAR_ID) {
                                p += 1
                            } else if (d?.delimiterId == KtTokens.RPAR_ID) {
                                p -= 1
                            }
                            idx += 1
                        }
                    }
                    continue
                }
                val kwId = (pkt as? SyntaxPacket.KeywordPacket)?.keywordId ?: return false
                if (kwId !in TOP_LEVEL_DECL_STARTER_KEYWORD_IDS) {
                    return false
                }
                if (kwId in DECL_DEFINING_KEYWORD_IDS) {
                    sawDeclaration = true
                }
                atStmtStart = false
            } else if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                val kwId = (pkt as? SyntaxPacket.KeywordPacket)?.keywordId
                if (kwId != null && kwId in DECL_DEFINING_KEYWORD_IDS) {
                    sawDeclaration = true
                }
            }

            if (pkt is SyntaxPacket.DelimiterPacket) {
                when (pkt.delimiterId) {
                    KtTokens.LBRACE_ID -> braceDepth += 1
                    KtTokens.RBRACE_ID -> {
                        braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                            atStmtStart = true
                        }
                    }
                    KtTokens.LPAR_ID -> parenDepth += 1
                    KtTokens.RPAR_ID -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    KtTokens.LBRACKET_ID -> bracketDepth += 1
                    KtTokens.RBRACKET_ID -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    KtTokens.SEMICOLON_ID -> {
                        if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                            atStmtStart = true
                        }
                    }
                }
            }

            if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0 && idx + 1 < sig.size) {
                val nextLine = sig[idx + 1].span.startLine
                if (nextLine > pkt.span.endLine && isStatementTerminatingPacket(pkt)) {
                    atStmtStart = true
                }
            }
            idx += 1
        }
        return sawDeclaration
    }

    private fun isStatementTerminatingPacket(pkt: SyntaxPacket): Boolean =
        when (pkt) {
            is SyntaxPacket.IdentPacket,
            is SyntaxPacket.LiteralPacket,
            -> true
            is SyntaxPacket.DelimiterPacket ->
                pkt.delimiterId == KtTokens.RPAR_ID ||
                    pkt.delimiterId == KtTokens.RBRACKET_ID ||
                    pkt.delimiterId == KtTokens.RBRACE_ID ||
                    pkt.delimiterId == KtTokens.GT_ID
            is SyntaxPacket.StringSegmentPacket ->
                pkt.segmentKind == StringSegmentKind.CLOSE_QUOTE
            else -> false
        }

    private fun stripCommonIndentation(lines: List<String>): List<String> {
        var minIndent = Int.MAX_VALUE
        for (line in lines) {
            if (line.isBlank()) continue
            var spaces = 0
            while (spaces < line.length && line[spaces] == ' ') spaces += 1
            if (spaces < minIndent) minIndent = spaces
        }
        if (minIndent == 0 || minIndent == Int.MAX_VALUE) return lines
        return lines.map { if (it.length >= minIndent) it.substring(minIndent) else "" }
    }

    private fun renderKdocLines(
        lines: List<String>,
        indent: String,
    ): String {
        if (lines.isEmpty()) return "$indent/** */"
        return buildString {
            append(indent).append("/**\n")
            for (line in lines) {
                val safe = line.replace("*/", "* /")
                if (safe.isBlank()) {
                    append(indent).append(" *\n")
                } else {
                    append(indent).append(" * ").append(safe).append('\n')
                }
            }
            append(indent).append(" */")
        }
    }

    private fun translateProseLine(
        line: String,
        knownFunctionNames: Set<String>,
    ): String {
        if (line.isEmpty()) return ""
        val sb = StringBuilder(line.length + 16)
        var i = 0
        while (i < line.length) {
            if (line[i] == '`') {
                val close = line.indexOf('`', i + 1)
                if (close > i) {
                    val inner = line.substring(i + 1, close)
                    sb.append('`').append(translateInlineCodeSpan(inner)).append('`')
                    i = close + 1
                    continue
                }
            }

            val isParam = line.startsWith("@param", i)
            val isSee = line.startsWith("@see", i)
            if (isParam || isSee) {
                val tagLen = if (isParam) 6 else 4
                var afterTag = i + tagLen
                if (afterTag < line.length && line[afterTag].isWhitespace()) {
                    sb.append(line, i, afterTag)
                    while (afterTag < line.length && line[afterTag].isWhitespace()) {
                        sb.append(line[afterTag])
                        afterTag += 1
                    }
                    var nameEnd = afterTag
                    while (nameEnd < line.length && (line[nameEnd].isLetterOrDigit() || line[nameEnd] == '_' || line[nameEnd] == '.')) {
                        nameEnd += 1
                    }
                    val rawName = line.substring(afterTag, nameEnd)
                    if (isDottedIdentifier(rawName)) {
                        if (isParam) {
                            sb.append(CppNamingPolicy.toSnakeCase(rawName))
                        } else {
                            val prim = lookupPrimitiveDocType(rawName)
                            if (prim != null) {
                                sb.append(if (' ' !in prim) "\\c $prim" else "`$prim`")
                            } else {
                                sb.append(documentationName(rawName, knownFunctionNames))
                            }
                        }
                    } else {
                        sb.append(rawName)
                    }
                    i = nameEnd
                    continue
                }
            }

            if (line[i] == '[') {
                val firstClose = line.indexOf(']', i + 1)
                if (firstClose > i && (firstClose + 1 >= line.length || line[firstClose + 1] != '(')) {
                    val firstContent = line.substring(i + 1, firstClose)
                    var finish = firstClose + 1
                    var target = firstContent
                    var label: String? = null
                    if (finish < line.length && line[finish] == '[') {
                        val secondClose = line.indexOf(']', finish + 1)
                        if (secondClose > finish) {
                            target = line.substring(finish + 1, secondClose)
                            label = firstContent
                            finish = secondClose + 1
                        }
                    }
                    if (isDottedIdentifier(target)) {
                        val translatedLabel = label?.let { translateLabelInlineSpans(it) }
                        val prim = lookupPrimitiveDocType(target)
                        if (prim != null) {
                            sb.append(if (' ' !in prim) "\\c $prim" else "`$prim`")
                            if (!translatedLabel.isNullOrEmpty()) {
                                sb.append(" (").append(translatedLabel).append(')')
                            }
                        } else {
                            sb.append("\\ref ").append(documentationName(target, knownFunctionNames))
                            if (!translatedLabel.isNullOrEmpty()) {
                                sb.append(" \"").append(translatedLabel.replace("\"", "\\\"")).append('"')
                            }
                        }
                        i = finish
                        continue
                    }
                }
            }

            sb.append(line[i])
            i += 1
        }
        return sb.toString()
    }

    private fun translateLabelInlineSpans(label: String): String {
        if ('`' !in label) return label
        val sb = StringBuilder(label.length)
        var i = 0
        while (i < label.length) {
            if (label[i] == '`') {
                val end = label.indexOf('`', i + 1)
                if (end > i) {
                    sb.append('`').append(translateInlineCodeSpan(label.substring(i + 1, end))).append('`')
                    i = end + 1
                    continue
                }
            }
            sb.append(label[i])
            i += 1
        }
        return sb.toString()
    }

    private fun translateInlineCodeSpan(code: String): String {
        if (code.isEmpty()) return code
        val packets = KotlinPacketLexing.lexSnippetPacketsOrNull(code, "<inline-doc>") ?: return code
        if (packets.isEmpty()) return code

        val sb = StringBuilder(code.length + 8)
        var prevSigPacket: SyntaxPacket? = null
        var prevIdentWasPascal = false

        for (idx in packets.indices) {
            val pkt = packets[idx]
            when (pkt) {
                is SyntaxPacket.WhitespacePacket,
                is SyntaxPacket.CommentPacket,
                -> sb.append(pkt.rawText)

                is SyntaxPacket.StringSegmentPacket -> {
                    sb.append(pkt.rawText)
                    prevSigPacket = pkt
                    prevIdentWasPascal = false
                }

                is SyntaxPacket.LiteralPacket -> {
                    when (pkt.literalKind) {
                        LiteralPacketKind.NULL -> sb.append("nullptr")
                        else -> sb.append(pkt.rawText)
                    }
                    prevSigPacket = pkt
                    prevIdentWasPascal = false
                }

                is SyntaxPacket.KeywordPacket -> {
                    when (pkt.keywordId) {
                        KtTokens.VAL_KEYWORD_ID,
                        KtTokens.VAR_KEYWORD_ID,
                        -> sb.append("auto")
                        KtTokens.NULL_KEYWORD_ID -> sb.append("nullptr")
                        else -> sb.append(pkt.rawText)
                    }
                    prevSigPacket = pkt
                    prevIdentWasPascal = false
                }

                is SyntaxPacket.DelimiterPacket -> {
                    if (pkt.delimiterId == KtTokens.DOT_ID && prevIdentWasPascal) {
                        val nextSig = nextSignificantPacket(packets, idx + 1)
                        val nextIdent = (nextSig as? SyntaxPacket.IdentPacket)?.unescapedName
                        if (nextIdent != null && nextIdent !in FILE_EXTENSIONS) {
                            sb.append("::")
                            prevSigPacket = pkt
                            continue
                        }
                    }
                    sb.append(pkt.rawText)
                    prevSigPacket = pkt
                    if (pkt.delimiterId != KtTokens.COLON_ID) {
                        prevIdentWasPascal = false
                    }
                }

                is SyntaxPacket.OperatorPacket -> {
                    when (pkt.operatorId) {
                        KtTokens.EQEQEQ_ID -> sb.append("==")
                        KtTokens.EXCLEQEQEQ_ID -> sb.append("!=")
                        else -> sb.append(pkt.rawText)
                    }
                    prevSigPacket = pkt
                    if (pkt.operatorId != KtTokens.COLONCOLON_ID) {
                        prevIdentWasPascal = false
                    }
                }

                is SyntaxPacket.IdentPacket -> {
                    val ident = pkt.unescapedName
                    val prevWasTypeIndicator =
                        (prevSigPacket as? SyntaxPacket.DelimiterPacket)?.delimiterId == KtTokens.COLON_ID ||
                            (prevSigPacket as? SyntaxPacket.OperatorPacket)?.operatorId == KtTokens.ARROW_ID
                    val isPascal = ident.isNotEmpty() && ident[0].isUpperCase() && ident.any { it.isLowerCase() }
                    val isAllCaps = ident.isNotEmpty() && ident.none { it.isLowerCase() }
                    val mapped =
                        when {
                            ident == "null" -> "nullptr"
                            ident == "val" || ident == "var" -> "auto"
                            ident == "Unit" && prevWasTypeIndicator -> "void"
                            ident in CPP_PRIMITIVE_DOC_TYPES && prevWasTypeIndicator -> CPP_PRIMITIVE_DOC_TYPES.getValue(ident)
                            isAllCaps || isPascal -> ident
                            ident.any { it.isUpperCase() } -> CppNamingPolicy.toSnakeCase(ident)
                            else -> ident
                        }
                    sb.append(mapped)
                    prevSigPacket = pkt
                    prevIdentWasPascal = isPascal && mapped == ident
                }
            }
        }
        return sb.toString()
    }

    private fun nextSignificantPacket(
        packets: List<SyntaxPacket>,
        startIndex: Int,
    ): SyntaxPacket? {
        for (i in startIndex until packets.size) {
            if (packets[i].isSignificant) return packets[i]
        }
        return null
    }

    private fun isDottedIdentifier(text: String): Boolean {
        if (text.isEmpty()) return false
        val first = text[0]
        if (!first.isLetter() && first != '_') return false
        return text.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }

    private fun lookupPrimitiveDocType(name: String): String? {
        val stripped = if (name.startsWith("kotlin.")) name.removePrefix("kotlin.") else name
        return CPP_PRIMITIVE_DOC_TYPES[stripped]
    }

    private fun documentationName(
        reference: String,
        knownFunctionNames: Set<String>,
    ): String {
        val parts = reference.split('.')
        val sb = StringBuilder(reference.length + 4)
        for (part in parts) {
            if (part.isEmpty()) continue
            if (part == "Companion" && sb.isNotEmpty()) continue
            if (sb.isNotEmpty()) sb.append("::")
            val allCaps = part.none { it.isLowerCase() }
            val lowered =
                if (!allCaps && (part[0].isLowerCase() || part in knownFunctionNames)) {
                    CppNamingPolicy.toSnakeCase(part)
                } else {
                    part
                }
            sb.append(lowered)
        }
        return sb.toString()
    }
}
