package io.github.kotlinmania.kotlincpp.kcpp

/**
 * Structured C++23 token and packet emitter backed by [CppToken] nodes.
 *
 * Manages indentation levels, preprocessor directives, include guards, namespaces,
 * template headers, `[[clang::annotate(...)]]` dual-ABI `suspend` attributes,
 * forwarding overrides, sealed-class special members, and IIFE expressions in one
 * deterministic token stream without manual indentation string threading.
 */
public class CppTokenEmitter(
    public val baseIndent: String = "",
) {
    private val tokens: ArrayList<CppToken> = ArrayList(64)

    public fun emit(token: CppToken): CppTokenEmitter {
        tokens.add(token)
        return this
    }

    public fun emitStream(stream: CppTokenStream): CppTokenEmitter {
        tokens.addAll(stream.tokens)
        return this
    }

    /** Tokenizes [cppCode] into genuine [CppToken] nodes and appends them to this stream. */
    public fun emitRawCode(cppCode: String): CppTokenEmitter {
        if (cppCode.isNotEmpty()) {
            tokens.addAll(CppTokenStream.tokenizeCppCode(cppCode).tokens)
        }
        return this
    }

    public fun ident(name: String): CppTokenEmitter {
        if (name.isNotEmpty()) {
            tokens.add(CppToken.Ident(name))
        }
        return this
    }

    public fun keyword(kw: String): CppTokenEmitter {
        if (kw.isNotEmpty()) {
            tokens.add(CppToken.Keyword(kw))
        }
        return this
    }

    public fun punct(
        symbol: String,
        jointToNext: Boolean = true,
    ): CppTokenEmitter {
        if (symbol.isNotEmpty()) {
            tokens.add(CppToken.Punct(symbol, jointToNext))
        }
        return this
    }

    /** Emits a binary operator surrounded by single spaces (e.g. ` = `, ` == `). */
    public fun op(symbol: String): CppTokenEmitter {
        space()
        punct(symbol, jointToNext = false)
        space()
        return this
    }

    public fun literal(rawText: String): CppTokenEmitter {
        tokens.add(CppToken.Literal(rawText))
        return this
    }

    /** Emits a C++ string literal with full C++23 escape handling. */
    public fun stringLiteral(unescapedText: String): CppTokenEmitter {
        tokens.add(CppToken.Literal(escapeCppStringLiteral(unescapedText)))
        return this
    }

    /** Emits a `::`-qualified name such as `kotlinx::coroutines::Job`. */
    public fun qualifiedName(segments: List<String>): CppTokenEmitter {
        var emitted = false
        for (seg in segments) {
            if (seg.isEmpty()) continue
            if (emitted) punct("::", jointToNext = true)
            ident(seg)
            emitted = true
        }
        return this
    }

    public fun comment(
        kind: CppCommentKind,
        formattedText: String,
    ): CppTokenEmitter {
        if (formattedText.isNotEmpty()) {
            tokens.add(CppToken.Comment(kind, formattedText))
        }
        return this
    }

    public fun space(count: Int = 1): CppTokenEmitter {
        if (count > 0) {
            tokens.add(CppToken.Layout(CppLayoutKind.SPACE, count))
        }
        return this
    }

    public fun newline(count: Int = 1): CppTokenEmitter {
        if (count > 0) {
            tokens.add(CppToken.Layout(CppLayoutKind.NEWLINE, count))
        }
        return this
    }

    /** Ensures the output ends with at least one blank line without emitting triple newlines. */
    public fun blankLine(): CppTokenEmitter {
        val trailingNewlines = countTrailingNewlines()
        if (trailingNewlines < 2) {
            newline(2 - trailingNewlines)
        }
        return this
    }

    public fun ensureTrailingNewline(): CppTokenEmitter {
        if (tokens.isNotEmpty() && countTrailingNewlines() == 0) {
            newline()
        }
        return this
    }

    public fun pushIndent(levels: Int = 1): CppTokenEmitter {
        if (levels > 0) {
            tokens.add(CppToken.Layout(CppLayoutKind.INDENT_PUSH, levels))
        }
        return this
    }

    public fun popIndent(levels: Int = 1): CppTokenEmitter {
        if (levels > 0) {
            tokens.add(CppToken.Layout(CppLayoutKind.INDENT_POP, levels))
        }
        return this
    }

    public fun emitPreprocessor(
        directive: String,
        argument: String = "",
    ): CppTokenEmitter {
        tokens.add(CppToken.Preprocessor(directive, argument))
        newline()
        return this
    }

    public fun emitPragmaOnce(): CppTokenEmitter = emitPreprocessor("pragma", "once")

    public fun emitPortLintHeader(
        directiveKind: String,
        sourcePath: String,
    ): CppTokenEmitter {
        comment(CppCommentKind.LINE, "// port-lint: $directiveKind $sourcePath")
        newline()
        return this
    }

    /** Emits a system `<header>` include, guarding Apple-only Darwin headers cleanly. */
    public fun emitIncludeSystem(header: String): CppTokenEmitter {
        if (header == "CoreFoundation/CoreFoundation.h" || header == "dispatch/dispatch.h") {
            emitPreprocessor("if", "defined(__APPLE__)")
            emitPreprocessor("include", "<$header>")
            emitPreprocessor("endif")
        } else {
            emitPreprocessor("include", "<$header>")
        }
        return this
    }

    /** Emits an unconditional `#include "header"` directive. */
    public fun emitIncludeProject(header: String): CppTokenEmitter = emitPreprocessor("include", "\"$header\"")

    /** Emits `#if [extraConditionPrefix && ]__has_include("header") ... #endif`. */
    public fun emitGuardedHasInclude(
        header: String,
        extraConditionPrefix: String = "",
    ): CppTokenEmitter {
        val cond =
            if (extraConditionPrefix.isEmpty()) {
                "__has_include(\"$header\")"
            } else {
                "$extraConditionPrefix && __has_include(\"$header\")"
            }
        emitPreprocessor("if", cond)
        emitIncludeProject(header)
        emitPreprocessor("endif")
        return this
    }

    /** Emits `[[clang::annotate("..."), ...]]`. */
    public fun emitClangAnnotations(annotations: List<String>): CppTokenEmitter {
        if (annotations.isEmpty()) return this
        val inner = ArrayList<CppToken>(annotations.size * 6)
        for ((idx, ann) in annotations.withIndex()) {
            if (idx > 0) {
                inner.add(CppToken.Punct(",", jointToNext = false))
                inner.add(CppToken.Layout(CppLayoutKind.SPACE, 1))
            }
            inner.add(CppToken.Ident("clang"))
            inner.add(CppToken.Punct("::", jointToNext = true))
            inner.add(CppToken.Ident("annotate"))
            inner.add(
                CppToken.Group(
                    CppDelimiter.PAREN,
                    listOf(CppToken.Literal(escapeCppStringLiteral(ann))),
                ),
            )
        }
        tokens.add(CppToken.Attribute(inner))
        return this
    }

    /** Emits the canonical `[[clang::annotate("suspend"), ...]]` attribute tuple. */
    public fun emitSuspendAnnotations(
        implicitContinuation: Boolean,
        boxedResult: Boolean,
    ): CppTokenEmitter {
        val anns = ArrayList<String>(3)
        anns.add("suspend")
        if (implicitContinuation) {
            anns.add("kxs_implicit_continuation")
        }
        if (boxedResult) {
            anns.add("kxs_boxed_result")
        }
        return emitClangAnnotations(anns)
    }

    /** Emits a standard C++ attribute such as `[[noreturn]]` or `[[maybe_unused]]`. */
    public fun emitStandardAttribute(attrName: String): CppTokenEmitter {
        tokens.add(CppToken.Attribute(listOf(CppToken.Ident(attrName))))
        return this
    }

    /** Emits `template <typename T, ...>` (with optional trailing `requires (...)`). */
    public fun emitTemplateDeclaration(
        typeParamClauses: List<String>,
        requiresClause: String = "",
    ): CppTokenEmitter {
        if (typeParamClauses.isEmpty()) return this
        keyword("template")
        space()
        punct("<")
        for ((idx, clause) in typeParamClauses.withIndex()) {
            if (idx > 0) {
                punct(",", jointToNext = false)
                space()
            }
            emitRawCode(clause)
        }
        punct(">", jointToNext = false)
        newline()
        if (requiresClause.isNotEmpty()) {
            indented {
                keyword("requires")
                space()
                emitRawCode(requiresClause)
                newline()
            }
        }
        return this
    }

    /** Emits `using namespace a::b::c;` on its own line. */
    public fun emitUsingNamespace(qualifiedNamespace: String): CppTokenEmitter {
        if (qualifiedNamespace.isEmpty()) return this
        keyword("using")
        space()
        keyword("namespace")
        space()
        emitRawCode(qualifiedNamespace)
        punct(";", jointToNext = false)
        newline()
        return this
    }

    /** Emits `template <typename E>\nusing Alias = Target;\n`. */
    public fun emitTemplateUsingAlias(
        typeParamDecl: String,
        aliasName: String,
        targetType: String,
    ): CppTokenEmitter {
        emitTemplateDeclaration(listOf(typeParamDecl))
        keyword("using")
        space()
        ident(aliasName)
        op("=")
        emitRawCode(targetType)
        punct(";", jointToNext = false)
        newline()
        return this
    }

    /**
     * Emits protected defaulted copy/move special members (and optional default constructor
     * and C++23 defaulted `operator==`) for a translated Kotlin `sealed class`.
     */
    public fun emitSealedSpecialMembers(
        cppClassName: String,
        includeDefaultCtor: Boolean,
        includeDefaultEq: Boolean,
    ): CppTokenEmitter {
        if (includeDefaultCtor) {
            keyword("protected")
            punct(":", jointToNext = false)
            newline()
            indented {
                emitDefaultedLine("$cppClassName()")
            }
        }
        indented {
            emitDefaultedLine("$cppClassName(const $cppClassName&)")
            emitDefaultedLine("$cppClassName($cppClassName&&)")
            emitDefaultedLine("$cppClassName& operator=(const $cppClassName&)")
            emitDefaultedLine("$cppClassName& operator=($cppClassName&&)")
        }
        keyword("public")
        punct(":", jointToNext = false)
        newline()
        if (includeDefaultEq) {
            indented {
                emitDefaultedLine("bool operator==(const $cppClassName&) const")
            }
        }
        return this
    }

    /**
     * Emits a single-line overriding forwarding method:
     * `<retType> <methodName>(<paramsDecl>)[ const] override { [return ]<receiver><op><methodName>(<args>); }`
     */
    public fun emitForwardingOverride(
        returnType: String,
        methodName: String,
        paramsDecl: String,
        isConst: Boolean,
        isVoidReturn: Boolean,
        receiverExpr: String,
        memberAccessOp: String,
        callArgsExpr: String,
    ): CppTokenEmitter {
        emitRawCode(returnType)
        space()
        ident(methodName)
        punct("(")
        if (paramsDecl.isNotEmpty()) emitRawCode(paramsDecl)
        punct(")", jointToNext = false)
        if (isConst) {
            space()
            keyword("const")
        }
        space()
        ident("override")
        space()
        punct("{", jointToNext = false)
        space()
        if (!isVoidReturn) {
            keyword("return")
            space()
        }
        emitRawCode(receiverExpr)
        punct(memberAccessOp, jointToNext = true)
        ident(methodName)
        punct("(")
        if (callArgsExpr.isNotEmpty()) emitRawCode(callArgsExpr)
        punct("); }", jointToNext = false)
        newline()
        return this
    }

    /**
     * Emits the canonical dual-ABI `suspend` override pair for delegated interface forwarding:
     * 1. Authoring overload declaration with `kxs_implicit_continuation` (and optional `kxs_boxed_result`).
     * 2. Lowered `[[clang::annotate("suspend")]]` `void*` `override` forwarding with `std::move(_kxs_completion)`.
     */
    public fun emitDualAbiSuspendForwardingOverride(
        authoringReturnType: String,
        boxedResult: Boolean,
        methodName: String,
        authoringParamsDecl: String,
        loweredLeadingParamsDecl: String,
        receiverExpr: String,
        memberAccessOp: String,
        loweredLeadingArgs: List<String>,
    ): CppTokenEmitter {
        emitSuspendAnnotations(implicitContinuation = true, boxedResult = boxedResult)
        newline()
        emitRawCode(authoringReturnType)
        space()
        ident(methodName)
        punct("(")
        if (authoringParamsDecl.isNotEmpty()) emitRawCode(authoringParamsDecl)
        punct(");", jointToNext = false)
        newline()
        newline()
        val completionParam = "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
        val fullLoweredParams =
            if (loweredLeadingParamsDecl.isEmpty()) {
                completionParam
            } else {
                "$loweredLeadingParamsDecl, $completionParam"
            }
        val fullCallArgs = (loweredLeadingArgs + "std::move(_kxs_completion)").joinToString(", ")
        emitSuspendAnnotations(implicitContinuation = false, boxedResult = false)
        newline()
        return emitForwardingOverride(
            returnType = "void*",
            methodName = methodName,
            paramsDecl = fullLoweredParams,
            isConst = false,
            isVoidReturn = false,
            receiverExpr = receiverExpr,
            memberAccessOp = memberAccessOp,
            callArgsExpr = fullCallArgs,
        )
    }

    /** Emits an inline C++23 lambda IIFE `[<capture>](<paramsDecl>)[ -> <explicitReturnType>] { <bodyCode> }(<invokeArgs>)`. */
    public fun emitIife(
        capture: String = "&",
        paramsDecl: String = "",
        explicitReturnType: String? = null,
        invokeArgs: String = "",
        bodyCode: String,
    ): CppTokenEmitter {
        punct("([")
        if (capture.isNotEmpty()) emitRawCode(capture)
        punct("](")
        if (paramsDecl.isNotEmpty()) emitRawCode(paramsDecl)
        punct(")", jointToNext = false)
        if (!explicitReturnType.isNullOrEmpty()) {
            space()
            punct("->", jointToNext = false)
            space()
            emitRawCode(explicitReturnType)
        }
        space()
        punct("{", jointToNext = false)
        space()
        emitRawCode(bodyCode.trim())
        space()
        punct("}(", jointToNext = true)
        if (invokeArgs.isNotEmpty()) emitRawCode(invokeArgs)
        punct("))", jointToNext = false)
        return this
    }

    public fun toTokenStream(): CppTokenStream = CppTokenStream(tokens.toList())

    public fun render(): String = toTokenStream().render(baseIndent)

    private fun emitDefaultedLine(signature: String): CppTokenEmitter {
        emitRawCode(signature)
        space()
        punct("=", jointToNext = false)
        space()
        keyword("default")
        punct(";", jointToNext = false)
        newline()
        return this
    }

    private fun countTrailingNewlines(): Int {
        var count = 0
        for (i in tokens.indices.reversed()) {
            val t = tokens[i]
            if (t is CppToken.Layout && t.kind == CppLayoutKind.NEWLINE) {
                count += t.count
                if (count >= 2) return count
            } else if (t is CppToken.Layout && (t.kind == CppLayoutKind.INDENT_PUSH || t.kind == CppLayoutKind.INDENT_POP)) {
                continue
            } else {
                break
            }
        }
        return count
    }

    private fun escapeCppStringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 4)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    internal fun captureTokens(block: CppTokenEmitter.() -> Unit): List<CppToken> {
        val start = tokens.size
        block()
        val captured = tokens.subList(start, tokens.size).toList()
        for (i in tokens.size - 1 downTo start) {
            tokens.removeAt(i)
        }
        return captured
    }

    internal fun emitDelimitedGroup(
        delimiter: CppDelimiter,
        block: CppTokenEmitter.() -> Unit,
    ): CppTokenEmitter {
        val inner = captureTokens(block)
        tokens.add(CppToken.Group(delimiter, inner))
        return this
    }
}

internal inline fun buildCppTokens(
    baseIndent: String = "",
    block: CppTokenEmitter.() -> Unit,
): String {
    val emitter = CppTokenEmitter(baseIndent = baseIndent)
    emitter.block()
    return emitter.render()
}

internal inline fun CppTokenEmitter.indented(block: CppTokenEmitter.() -> Unit): CppTokenEmitter {
    pushIndent()
    block()
    popIndent()
    return this
}

internal inline fun CppTokenEmitter.parens(crossinline block: CppTokenEmitter.() -> Unit): CppTokenEmitter =
    emitDelimitedGroup(CppDelimiter.PAREN) { block() }

internal inline fun CppTokenEmitter.angles(crossinline block: CppTokenEmitter.() -> Unit): CppTokenEmitter =
    emitDelimitedGroup(CppDelimiter.ANGLE) { block() }

internal inline fun CppTokenEmitter.brackets(crossinline block: CppTokenEmitter.() -> Unit): CppTokenEmitter =
    emitDelimitedGroup(CppDelimiter.BRACKET) { block() }

internal inline fun CppTokenEmitter.braces(crossinline block: CppTokenEmitter.() -> Unit): CppTokenEmitter =
    emitDelimitedGroup(CppDelimiter.BRACE) { block() }

internal inline fun CppTokenEmitter.emitIncludeGuard(
    macroName: String,
    trailingComment: Boolean = false,
    block: CppTokenEmitter.() -> Unit,
): CppTokenEmitter {
    emitPreprocessor("ifndef", macroName)
    emitPreprocessor("define", macroName)
    block()
    ensureTrailingNewline()
    emitPreprocessor("endif", if (trailingComment) "// $macroName" else "")
    return this
}

internal inline fun CppTokenEmitter.emitNamespaceBlock(
    namespaceJoined: String,
    blankLineInside: Boolean = true,
    block: CppTokenEmitter.() -> Unit,
): CppTokenEmitter {
    if (namespaceJoined.isNotEmpty()) {
        keyword("namespace")
        space()
        emitRawCode(namespaceJoined)
        space()
        punct("{", jointToNext = false)
        newline(if (blankLineInside) 2 else 1)
    }
    block()
    if (namespaceJoined.isNotEmpty()) {
        ensureTrailingNewline()
        if (blankLineInside) blankLine()
        punct("}", jointToNext = false)
        space()
        comment(CppCommentKind.LINE, "// namespace $namespaceJoined")
        newline()
    }
    return this
}
