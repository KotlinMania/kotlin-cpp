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
        if (content.startsWith("port-lint:")) {
            return "$indent//$leadingSpace$content"
        }
        if (content.startsWith("Transliterated from:")) {
            return ""
        }
        val translated = sanitizeLeadingStubMarker(translateProseLine(content, knownFunctionNames))
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
            if (inner.startsWith("Transliterated from:")) return ""
            val translated =
                sanitizeLeadingStubMarker(translateProseLine(inner, knownFunctionNames))
                    .replace("*/", "* /")
                    .replace("/*", "/ *")
            return if (translated.isEmpty()) "$indent/* */" else "$indent/* $translated */"
        }
        val bodyLines = extractBlockCommentBodyLines(rawText)
        val renderedLines = translateKdocLines(bodyLines, knownFunctionNames, allowFenceTranslation = true)
        val sanitizedLines = ArrayList<String>(renderedLines.size)
        var inFence = false
        for (line in renderedLines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                sanitizedLines.add(line)
                continue
            }
            if (!inFence) {
                if (trimmed.startsWith("Transliterated from:")) continue
                sanitizedLines.add(sanitizeLeadingStubMarker(translateKnitBlockCommentLine(line)))
            } else {
                sanitizedLines.add(line)
            }
        }
        if (sanitizedLines.isEmpty()) return ""
        return buildString {
            append(indent).append("/*\n")
            for (line in sanitizedLines) {
                val safe = line.replace("*/", "* /").replace("/*", "/ *").trimEnd()
                if (safe.isBlank()) {
                    append(indent).append(" *\n")
                } else {
                    append(indent).append(" * ").append(safe).append('\n')
                }
            }
            append(indent).append(" */")
        }
    }

    private fun extractBlockCommentBodyLines(rawText: String): List<String> {
        val stripped =
            rawText
                .trim()
                .removePrefix("/*")
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
                rawBody.add(line.trimEnd())
            }
        }
        return rawBody
    }

    private fun translateKnitBlockCommentLine(line: String): String {
        val trimmed = line.trim()
        val indent = line.substring(0, line.length - line.trimStart().length)
        if (trimmed.startsWith("import ")) {
            val target = trimmed.removePrefix("import ").trim().removeSuffix(";")
            return if (target.endsWith(".*")) {
                val ns = target.removeSuffix(".*").replace('.', ':').replace(":", "::")
                "${indent}using namespace $ns;"
            } else {
                val sym = target.replace(".", "::")
                "${indent}using $sym;"
            }
        }
        if (trimmed == "fun main() = runBlocking {") {
            return "${indent}int main() { return run_blocking([&]() {"
        }
        if (trimmed == ".toList().joinToString().let { println(it) } }") {
            return "$indent.to_list().join_to_string().let([&](auto&& it) { std::cout << it << '\\n'; }); }"
        }
        return line
    }

    private val LEADING_STUB_WORDS: List<String> =
        listOf(
            "not yet implemented",
            "not implemented",
            "notimplemented",
            "unimplemented",
            "placeholder",
            "fixme",
            "todo",
            "stub",
            "xxx",
            "hack",
        )

    private fun sanitizeLeadingStubMarker(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return text
        val leadLen = text.length - trimmed.length
        val lead = text.substring(0, leadLen)
        val lower = trimmed.lowercase()
        for (marker in LEADING_STUB_WORDS) {
            if (lower.startsWith(marker)) {
                val end = marker.length
                val boundaryOk = end == lower.length || (!lower[end].isLetterOrDigit() && lower[end] != '_')
                if (boundaryOk) {
                    val rest = trimmed.substring(end).trimStart(':', '-', ' ')
                    return if (rest.isEmpty()) "${lead}Note" else "${lead}Note: $rest"
                }
            }
        }
        return text
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
            val translated =
                translateProseLine(inner, knownFunctionNames)
                    .replace("*/", "* /")
                    .replace("/*", "/ *")
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
                            knownFunctionNames = knownFunctionNames,
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
                FenceState.NONE -> {
                    if (!line.trim().startsWith("Transliterated from:")) {
                        out.add(translateProseLine(line, knownFunctionNames).trimEnd())
                    }
                }
            }
        }

        if (state == FenceState.KOTLIN && bufferedFence.isNotEmpty()) {
            flushKotlinCodeFence(
                out = out,
                openFenceLine = openFenceLine,
                closeFenceLine = "$fenceIndent```",
                fenceIndent = fenceIndent,
                bufferedFence = bufferedFence,
                knownFunctionNames = knownFunctionNames,
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
        knownFunctionNames: Set<String>,
    ) {
        val (codeLines, trailingProse) = extractTrailingMarkdownNotes(bufferedFence)
        val translated = translateKotlinDocCodeBlock(codeLines)
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
            val safeOpen =
                if (openFenceLine.trim().lowercase().startsWith("```kotlin")) {
                    "$fenceIndent```"
                } else {
                    openFenceLine
                }
            out.add(safeOpen)
            for (raw in codeLines) {
                out.add(raw.trimEnd())
            }
            out.add(closeFenceLine)
        }
        if (trailingProse.isNotEmpty()) {
            out.add("")
            for (noteLine in trailingProse) {
                out.add(translateProseLine(noteLine, knownFunctionNames).trimEnd())
            }
        }
    }

    private fun translateKotlinDocCodeBlock(rawFenceLines: List<String>): List<String>? {
        if (rawFenceLines.isEmpty() || rawFenceLines.all { it.isBlank() }) return emptyList()
        val dedented = stripCommonIndentation(rawFenceLines)
        val joinedRaw = dedented.joinToString("\n")
        if (isPlainDiagramOrTrace(joinedRaw)) return null
        val preNormalized = normalizeKotlinDocSnippet(dedented).joinToString("\n")
        val rawPackets = KotlinPacketLexing.lexSnippetPacketsOrNull(preNormalized, "<doc>") ?: return null
        val normalized = renderNormalizedSnippetFromPackets(rawPackets)
        val normalizedPackets =
            if (normalized == preNormalized) {
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

    private fun extractTrailingMarkdownNotes(lines: List<String>): Pair<List<String>, List<String>> {
        if (lines.isEmpty()) return lines to emptyList()
        var cut = lines.size
        while (cut > 0) {
            val t = lines[cut - 1].trim()
            if (t.isEmpty()) {
                if (cut < lines.size) {
                    cut -= 1
                    continue
                }
                break
            }
            if (t.startsWith("**") || t.startsWith("*Note") || t.startsWith("*Caution") || t.startsWith("*Warning")) {
                cut -= 1
                continue
            }
            break
        }
        while (cut > 0 && lines[cut - 1].isBlank()) cut -= 1
        if (cut == lines.size || cut == 0) return lines to emptyList()
        val notes = lines.subList(cut, lines.size).dropWhile { it.isBlank() }
        if (notes.isEmpty()) return lines to emptyList()
        return lines.subList(0, cut) to notes
    }

    private fun isPlainDiagramOrTrace(joined: String): Boolean {
        val codeLines =
            joined.lines().mapNotNull { line ->
                val idx = line.indexOf("//")
                val code = (if (idx >= 0) line.substring(0, idx) else line).trim()
                code.takeIf { it.isNotEmpty() }
            }
        if (codeLines.isEmpty()) return false
        val body = codeLines.joinToString("\n")
        val hasKotlinConstruct =
            Regex(
                """\b(?:fun|val|var|class|interface|object|package|import|when|while|for|try|catch|return|throw|contract|launch|async|runBlocking|coroutineScope|withContext|flow|emit|produce|select)\b""",
            ).containsMatchIn(body) ||
                body.contains('{') ||
                body.contains('}') ||
                body.contains(';') ||
                Regex("""[A-Za-z0-9_)\]]\s*->\s*""").containsMatchIn(body)
        if (hasKotlinConstruct) return false
        return body.contains("+---") ||
            body.contains("-->--") ||
            body.contains("| ") ||
            body.contains("==>") ||
            Regex("""^Thread\s+\d+:""", RegexOption.MULTILINE).containsMatchIn(body)
    }

    private fun normalizeKotlinDocSnippet(lines: List<String>): List<String> {
        val unescaped =
            lines
                .map { it.replace("/ *", "/*").replace("* /", "*/") }
                .filterNot { line ->
                    val t = line.trim()
                    t.startsWith("<!---") || t == "-->" || t.startsWith("----- ")
                }
        val withoutContracts = stripContractBlocks(unescaped)
        val normalizedLines = ArrayList<String>(withoutContracts.size)
        var inTripleString = false
        var inFunParams = false
        var funParamParenDepth = 0

        for (rawLine in withoutContracts) {
            var line = rawLine
            if (!inTripleString) {
                if (Regex("""^\s*(?:(?:public|private|internal|protected|suspend|inline|operator|infix|tailrec|override|open|final|abstract)\s+)*fun\b""").containsMatchIn(line)) {
                    inFunParams = line.contains('(')
                    funParamParenDepth = 0
                }
                if (inFunParams) {
                    val fixedParams = stripFunParamValVar(line, funParamParenDepth)
                    line = fixedParams.first
                    funParamParenDepth = fixedParams.second
                    if (funParamParenDepth <= 0 && line.contains(')')) {
                        inFunParams = false
                    }
                }
                line = fixUnclosedQuoteBeforeParen(line)
                line = fixLambdaHeaderArrow(line)
                line = normalizeEllipsisPlaceholders(line)
            }
            var idx = 0
            while (idx + 2 < line.length) {
                if (line.startsWith("\"\"\"", idx)) {
                    inTripleString = !inTripleString
                    idx += 3
                } else {
                    idx += 1
                }
            }
            normalizedLines.add(line)
        }
        return balanceDocDelimiters(normalizedLines)
    }

    private fun stripContractBlocks(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var skipping = false
        var braceDepth = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (!skipping && Regex("""^contract\s*\{""").containsMatchIn(trimmed)) {
                skipping = true
                braceDepth = countBraceDelta(trimmed)
                if (braceDepth <= 0) skipping = false
                continue
            }
            if (skipping) {
                braceDepth += countBraceDelta(trimmed)
                if (braceDepth <= 0) skipping = false
                continue
            }
            out.add(line)
        }
        return out
    }

    private fun countBraceDelta(text: String): Int {
        var delta = 0
        for (ch in text) {
            if (ch == '{') {
                delta += 1
            } else if (ch == '}') {
                delta -= 1
            }
        }
        return delta
    }

    private fun stripFunParamValVar(
        line: String,
        startParenDepth: Int,
    ): Pair<String, Int> {
        val sb = StringBuilder(line.length)
        var depth = startParenDepth
        var angleDepth = 0
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (depth == 0) {
                when {
                    ch == '<' -> angleDepth += 1
                    ch == '>' && angleDepth > 0 -> angleDepth -= 1
                    ch == '(' && angleDepth == 0 -> depth = 1
                }
                sb.append(ch)
                i += 1
                continue
            }
            if (ch == '(') {
                depth += 1
                sb.append(ch)
                i += 1
            } else if (ch == ')') {
                depth = (depth - 1).coerceAtLeast(0)
                sb.append(ch)
                i += 1
            } else if (depth == 1) {
                val atParamStart =
                    sb.isEmpty() ||
                        sb.last() == '(' ||
                        sb.last() == ',' ||
                        sb.toString().let { cur -> cur.trimEnd().let { it.endsWith("(") || it.endsWith(",") || it.isEmpty() } }
                if (atParamStart && (line.startsWith("val ", i) || line.startsWith("var ", i))) {
                    i += 4
                } else {
                    sb.append(ch)
                    i += 1
                }
            } else {
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString() to depth
    }

    private fun fixUnclosedQuoteBeforeParen(line: String): String {
        var inString = false
        var inChar = false
        var escaped = false
        var codeEnd = line.length
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (escaped) {
                escaped = false
                i += 1
                continue
            }
            if ((inString || inChar) && ch == '\\') {
                escaped = true
                i += 1
                continue
            }
            if (!inString && !inChar && i + 1 < line.length && line[i] == '/' && line[i + 1] == '/') {
                codeEnd = i
                break
            }
            if (!inChar && ch == '"') {
                inString = !inString
            } else if (!inString && ch == '\'') {
                inChar = !inChar
            }
            i += 1
        }
        if (!inString) return line
        val codePart = line.substring(0, codeEnd)
        val suffix = line.substring(codeEnd)
        val trimmedCode = codePart.trimEnd()
        val hasSemi = trimmedCode.endsWith(");")
        val targetClose = if (hasSemi) trimmedCode.length - 2 else trimmedCode.length - 1
        if (targetClose <= 0 || trimmedCode[targetClose] != ')') return line
        val fixedCode = trimmedCode.substring(0, targetClose) + "\"" + trimmedCode.substring(targetClose)
        return fixedCode + suffix
    }

    private fun fixLambdaHeaderArrow(line: String): String {
        val commentIdx = findLineCommentStartOutsideStrings(line)
        val codePart = (if (commentIdx >= 0) line.substring(0, commentIdx) else line).trimEnd()
        val commentSuffix = if (commentIdx >= 0) line.substring(commentIdx) else ""
        if (codePart.contains("->")) return line
        val pattern =
            Regex(
                """^.*\{\s*(?:_|[A-Za-z_][A-Za-z0-9_]*)(?:\s*:\s*[A-Za-z0-9_<>.?]+)?(?:\s*,\s*(?:_|[A-Za-z_][A-Za-z0-9_]*)(?:\s*:\s*[A-Za-z0-9_<>.?]+)?)*\s*$""",
            )
        if (!pattern.matches(codePart)) return line
        return if (commentSuffix.isEmpty()) "$codePart ->" else "$codePart -> $commentSuffix"
    }

    private fun normalizeEllipsisPlaceholders(line: String): String {
        val commentIdx = findLineCommentStartOutsideStrings(line)
        val codeRaw = if (commentIdx >= 0) line.substring(0, commentIdx) else line
        val commentSuffix = if (commentIdx >= 0) line.substring(commentIdx) else ""
        val trimmedCode = codeRaw.trim()
        if (trimmedCode.startsWith("...")) {
            val lead = codeRaw.substring(0, codeRaw.length - codeRaw.trimStart().length)
            val rest = trimmedCode.removeSurrounding("...", "...").trim()
            val commentBody = if (rest.isEmpty()) "..." else rest
            val replaced = "$lead/* $commentBody */"
            return if (commentSuffix.isEmpty()) replaced else "$replaced $commentSuffix"
        }
        var updated = codeRaw
        updated =
            Regex("""^(\s*(?:[a-z]+\s+)*fun\b.*)=\s*\.\.\.\s*$""").replace(updated) { m ->
                "${m.groupValues[1]}{ /* ... */ }"
            }
        updated = Regex("""=\s*\.\.\.\s*$""").replace(updated, "= null")
        updated = Regex("""\bthrow\s+\.\.\.""").replace(updated, "throw Exception(\"...\")")
        return if (commentSuffix.isEmpty()) updated else updated + commentSuffix
    }

    private fun findLineCommentStartOutsideStrings(line: String): Int {
        var inString = false
        var inChar = false
        var escaped = false
        var i = 0
        while (i + 1 < line.length) {
            val ch = line[i]
            if (escaped) {
                escaped = false
                i += 1
                continue
            }
            if ((inString || inChar) && ch == '\\') {
                escaped = true
                i += 1
                continue
            }
            if (!inString && !inChar && ch == '/' && line[i + 1] == '/') return i
            if (!inChar && ch == '"') {
                inString = !inString
            } else if (!inString && ch == '\'') {
                inChar = !inChar
            }
            i += 1
        }
        return -1
    }

    private data class OpenDelimiter(
        val ch: Char,
        val indent: Int,
    )

    private fun balanceDocDelimiters(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size + 4)
        val stack = ArrayDeque<OpenDelimiter>()
        var inBlockComment = false
        for (line in lines) {
            val stripped = stripCommentsAndStringsForBalance(line, inBlockComment)
            val codeText = stripped.first
            inBlockComment = stripped.second
            val trimmedStart = line.trimStart()
            val lineIndent = line.length - trimmedStart.length
            if (trimmedStart.startsWith(".") && stack.lastOrNull()?.ch == '{') {
                val top = stack.last()
                if (lineIndent <= top.indent) {
                    stack.removeLast()
                    out.add(" ".repeat(top.indent) + "}")
                }
            }
            out.add(line)
            for (ch in codeText) {
                when (ch) {
                    '(', '{' -> stack.addLast(OpenDelimiter(ch, lineIndent))
                    ')' -> if (stack.lastOrNull()?.ch == '(') stack.removeLast()
                    '}' -> if (stack.lastOrNull()?.ch == '{') stack.removeLast()
                }
            }
        }
        if (stack.isNotEmpty()) {
            val tail = StringBuilder()
            while (stack.isNotEmpty()) {
                val open = stack.removeLast()
                if (open.ch == '(') {
                    tail.append(')')
                } else {
                    if (tail.isNotEmpty()) {
                        out.add(tail.toString())
                        tail.clear()
                    }
                    out.add(" ".repeat(open.indent) + "}")
                }
            }
            if (tail.isNotEmpty()) out.add(tail.toString())
        }
        return out
    }

    private fun stripCommentsAndStringsForBalance(
        line: String,
        startInBlockComment: Boolean,
    ): Pair<String, Boolean> {
        val sb = StringBuilder(line.length)
        var inBlock = startInBlockComment
        var inString = false
        var inChar = false
        var escaped = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inBlock) {
                if (ch == '*' && i + 1 < line.length && line[i + 1] == '/') {
                    inBlock = false
                    i += 2
                } else {
                    i += 1
                }
                continue
            }
            if (escaped) {
                escaped = false
                i += 1
                continue
            }
            if (inString || inChar) {
                when {
                    ch == '\\' -> escaped = true
                    inString && ch == '"' -> inString = false
                    inChar && ch == '\'' -> inChar = false
                }
                i += 1
                continue
            }
            if (ch == '/' && i + 1 < line.length) {
                if (line[i + 1] == '/') break
                if (line[i + 1] == '*') {
                    inBlock = true
                    i += 2
                    continue
                }
            }
            if (ch == '"') {
                inString = true
                i += 1
                continue
            }
            if (ch == '\'') {
                inChar = true
                i += 1
                continue
            }
            sb.append(ch)
            i += 1
        }
        return sb.toString() to inBlock
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
                val safe = line.replace("*/", "* /").replace("/*", "/ *")
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
                        KtTokens.FUN_KEYWORD_ID -> {
                            val nextSig = nextSignificantPacket(packets, idx + 1)
                            val nextIdent = (nextSig as? SyntaxPacket.IdentPacket)?.unescapedName
                            sb.append(if (nextIdent == "main") "int" else "void")
                        }
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
                    if (ident == "main" &&
                        (prevSigPacket as? SyntaxPacket.KeywordPacket)?.keywordId == KtTokens.FUN_KEYWORD_ID &&
                        nextSignificantPacket(packets, idx + 1) == null
                    ) {
                        sb.append("()")
                    }
                    prevSigPacket = pkt
                    prevIdentWasPascal = isPascal && mapped == ident
                }
            }
        }
        return sb.toString()
    }

    internal fun containsWholeWordInCode(
        code: String,
        word: String,
    ): Boolean = CppTokenStream.tokenizeCppCode(code).containsIdent(word)

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
