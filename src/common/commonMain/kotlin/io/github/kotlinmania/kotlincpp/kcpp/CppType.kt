package io.github.kotlinmania.kotlincpp.kcpp

/** Canonical built-in C++23 leaf types produced by [CppTypeTranslator]. */
public enum class CppBuiltinType {
    VOID,
    AUTO,
    BOOL,
    NULLPTR_T,
    STD_STRING,
    STD_STRING_VIEW,
    STD_ANY,
    EXCEPTION_PTR,
}

/**
 * Structured C++23 type AST replacing brittle prefix/suffix/regex string parsing
 * across [io.github.kotlinmania.kotlincpp.kcpp].
 *
 * Modeled as a `sealed class` (never `sealed interface`) for clean Swift Export code generation.
 */
public sealed class CppType {
    public data class Builtin(
        public val kind: CppBuiltinType,
    ) : CppType()

    public data class Scalar(
        public val cppName: String,
        public val isUnsigned: Boolean = false,
    ) : CppType()

    public data class TypeParam(
        public val name: String,
    ) : CppType()

    public data class Named(
        public val qualifiers: List<String>,
        public val simpleName: String,
        public val typeArgs: List<CppType> = emptyList(),
    ) : CppType()

    public data class SharedPtr(
        public val element: CppType,
    ) : CppType()

    public data class UniquePtr(
        public val element: CppType,
    ) : CppType()

    public data class Optional(
        public val element: CppType,
    ) : CppType()

    public data class Vector(
        public val element: CppType,
    ) : CppType()

    public data class RawPtr(
        public val pointee: CppType,
        public val isConstPointee: Boolean = false,
    ) : CppType()

    public data class Ref(
        public val referent: CppType,
        public val isConst: Boolean = false,
        public val isRvalue: Boolean = false,
    ) : CppType()

    public data class Function(
        public val returnType: CppType,
        public val paramTypes: List<CppType>,
    ) : CppType()

    public data class SuspendFunction(
        public val qualifiers: List<String>,
        public val returnType: CppType,
        public val paramTypes: List<CppType>,
    ) : CppType()

    public data class NoReturn(
        public val inner: CppType,
    ) : CppType()

    /** Dependent or trait expression not reducible to a simple named type (e.g. `decltype(x)`). */
    public data class Dependent(
        public val cppText: String,
    ) : CppType()

    public val isNoReturn: Boolean
        get() = this is NoReturn

    public val isOptional: Boolean
        get() = this is Optional

    public val isSharedPtr: Boolean
        get() = this is SharedPtr

    public val isUniquePtr: Boolean
        get() = this is UniquePtr

    public val isRawPtr: Boolean
        get() = this is RawPtr

    public val isPointerLike: Boolean
        get() = this is RawPtr || this is SharedPtr || this is UniquePtr

    public val isVoidPtr: Boolean
        get() = this is RawPtr && !isConstPointee && pointee == Builtin(CppBuiltinType.VOID)

    public val isVector: Boolean
        get() = this is Vector

    public val isStdFunction: Boolean
        get() = this is Function

    public val isSuspendFunction: Boolean
        get() = this is SuspendFunction

    public val isStdAny: Boolean
        get() = this is Builtin && kind == CppBuiltinType.STD_ANY

    public val isExceptionPtr: Boolean
        get() = this is Builtin && kind == CppBuiltinType.EXCEPTION_PTR

    public val isVoid: Boolean
        get() = this is Builtin && kind == CppBuiltinType.VOID

    public val isAuto: Boolean
        get() = this is Builtin && kind == CppBuiltinType.AUTO

    /** Strips a leading `[[noreturn]]` wrapper if present. */
    public fun stripNoReturn(): CppType = if (this is NoReturn) inner else this

    /** Unwraps `std::optional<T>` to `T`, or returns `this` unchanged. */
    public fun unwrapOptional(): CppType = if (this is Optional) element else this

    /** Strips top-level `const` / `&` / `&&` qualifiers (e.g. `const T&` -> `T`). */
    public fun stripCvRef(): CppType =
        when (this) {
            is Ref -> referent.stripCvRef()
            is Dependent -> {
                val stripped =
                    cppText
                        .removePrefix("const ")
                        .removeSuffix("&&")
                        .removeSuffix("&")
                        .trim()
                if (stripped != cppText && stripped.isNotEmpty()) parseInternal(stripped) else this
            }
            else -> this
        }

    /** Returns the unqualified single identifier name if this is a [TypeParam] or bare [Named]. */
    public fun asBareIdentName(): String? =
        when (this) {
            is TypeParam -> name
            is Named -> if (qualifiers.isEmpty() && typeArgs.isEmpty()) simpleName else null
            else -> null
        }

    /** Replaces template `std::any` / `void*` wildcard positions in left-to-right pre-order with [starNames]. */
    public fun substituteStarProjections(starNames: List<String>): CppType {
        if (starNames.isEmpty()) return this
        var cursor = 0
        val walker =
            object {
                fun substituteSlot(slotType: CppType): CppType {
                    if (cursor < starNames.size && (slotType.isStdAny || slotType.isVoidPtr)) {
                        val starName = starNames[cursor]
                        cursor += 1
                        return TypeParam(starName)
                    }
                    return walk(slotType)
                }

                fun walk(node: CppType): CppType {
                    if (cursor >= starNames.size) return node
                    return when (node) {
                        is NoReturn -> NoReturn(walk(node.inner))
                        is Ref -> Ref(walk(node.referent), node.isConst, node.isRvalue)
                        is RawPtr -> RawPtr(walk(node.pointee), node.isConstPointee)
                        is SharedPtr -> SharedPtr(substituteSlot(node.element))
                        is UniquePtr -> UniquePtr(substituteSlot(node.element))
                        is Optional -> Optional(substituteSlot(node.element))
                        is Vector -> Vector(substituteSlot(node.element))
                        is Named -> {
                            if (node.typeArgs.isEmpty()) {
                                node
                            } else {
                                Named(node.qualifiers, node.simpleName, node.typeArgs.map { substituteSlot(it) })
                            }
                        }
                        is SuspendFunction ->
                            SuspendFunction(
                                qualifiers = node.qualifiers,
                                returnType = substituteSlot(node.returnType),
                                paramTypes = node.paramTypes.map { substituteSlot(it) },
                            )
                        is Function -> Function(walk(node.returnType), node.paramTypes.map { walk(it) })
                        is Builtin,
                        is Scalar,
                        is TypeParam,
                        is Dependent,
                        -> node
                    }
                }
            }

        return walker.walk(this)
    }

    /**
     * Wraps a single bare template parameter inside `SuspendFunction<R, T>` in `std::type_identity_t<T>`
     * so template deduction cannot fail against heterogeneous lambda objects.
     */
    internal fun withNonDeducedSuspendArg(
        bareParamTpNames: Set<String>,
        includes: CppIncludeCollector,
    ): CppType {
        if (bareParamTpNames.isEmpty()) return this
        return when (this) {
            is Ref -> Ref(referent.withNonDeducedSuspendArg(bareParamTpNames, includes), isConst, isRvalue)
            is Optional -> Optional(element.withNonDeducedSuspendArg(bareParamTpNames, includes))
            is SuspendFunction -> {
                if (paramTypes.size == 1 && paramTypes[0].asBareIdentName() in bareParamTpNames) {
                    includes.addSystem("type_traits")
                    SuspendFunction(
                        qualifiers = qualifiers,
                        returnType = returnType,
                        paramTypes = listOf(Named(listOf("std"), "type_identity_t", listOf(paramTypes[0]))),
                    )
                } else {
                    this
                }
            }
            else -> this
        }
    }

    /**
     * Wraps `std::shared_ptr<[kotlin::coroutines::|kotlinx::coroutines::]Continuation<T>>` in
     * `std::type_identity_t<...>` when `T` is in [callableTpNames].
     */
    internal fun withNonDeducedContinuation(
        callableTpNames: Set<String>,
        includes: CppIncludeCollector,
    ): CppType {
        if (callableTpNames.isEmpty() || this !is SharedPtr) return this
        val inner = element
        val isCont =
            inner is Named &&
                inner.simpleName == "Continuation" &&
                inner.typeArgs.size == 1 &&
                (
                    inner.qualifiers.isEmpty() ||
                        inner.qualifiers == listOf("kotlin", "coroutines") ||
                        inner.qualifiers == listOf("kotlinx", "coroutines")
                )
        if (!isCont) return this
        val tpArg = inner.typeArgs[0].asBareIdentName()
        if (tpArg != null && tpArg in callableTpNames) {
            includes.addSystem("type_traits")
            return Named(listOf("std"), "type_identity_t", listOf(this))
        }
        return this
    }

    /** Coerces a Kotlin `nullptr` default/initializer expression to match this C++ type cleanly. */
    internal fun coerceNullDefaultExpr(
        rawDef: String,
        includes: CppIncludeCollector,
        coerceStdAnyToBraces: Boolean = false,
    ): String {
        if (rawDef != "nullptr") return rawDef
        if (isOptional) {
            includes.addSystem("optional")
            return "std::nullopt"
        }
        if (coerceStdAnyToBraces && isStdAny) {
            return "{}"
        }
        return rawDef
    }

    /** Emits this C++23 type into [out] as structured [CppToken] nodes. */
    public fun emitTokens(out: CppTokenEmitter) {
        when (this) {
            is NoReturn -> {
                out.emitStandardAttribute("noreturn")
                out.space()
                inner.emitTokens(out)
            }
            is Builtin -> {
                when (kind) {
                    CppBuiltinType.VOID -> out.keyword("void")
                    CppBuiltinType.AUTO -> out.keyword("auto")
                    CppBuiltinType.BOOL -> out.keyword("bool")
                    CppBuiltinType.NULLPTR_T -> out.qualifiedName(listOf("std", "nullptr_t"))
                    CppBuiltinType.STD_STRING -> out.qualifiedName(listOf("std", "string"))
                    CppBuiltinType.STD_STRING_VIEW -> out.qualifiedName(listOf("std", "string_view"))
                    CppBuiltinType.STD_ANY -> out.qualifiedName(listOf("std", "any"))
                    CppBuiltinType.EXCEPTION_PTR -> out.qualifiedName(listOf("std", "exception_ptr"))
                }
            }
            is Scalar -> out.emitRawCode(cppName)
            is TypeParam -> out.ident(name)
            is Dependent -> out.emitRawCode(cppText)
            is Named -> {
                if (qualifiers.isNotEmpty()) {
                    out.qualifiedName(qualifiers)
                    out.punct("::", jointToNext = true)
                }
                out.ident(simpleName)
                if (typeArgs.isNotEmpty()) {
                    out.angles {
                        for ((idx, arg) in typeArgs.withIndex()) {
                            if (idx > 0) {
                                punct(",", jointToNext = false)
                                space()
                            }
                            arg.emitTokens(this)
                        }
                    }
                }
            }
            is SharedPtr -> emitStdSingleArgTemplate(out, "shared_ptr", element)
            is UniquePtr -> emitStdSingleArgTemplate(out, "unique_ptr", element)
            is Optional -> emitStdSingleArgTemplate(out, "optional", element)
            is Vector -> emitStdSingleArgTemplate(out, "vector", element)
            is RawPtr -> {
                if (isConstPointee) {
                    out.keyword("const")
                    out.space()
                }
                pointee.emitTokens(out)
                out.punct("*", jointToNext = false)
            }
            is Ref -> {
                if (isConst) {
                    out.keyword("const")
                    out.space()
                }
                referent.emitTokens(out)
                out.punct(if (isRvalue) "&&" else "&", jointToNext = false)
            }
            is Function -> {
                out.qualifiedName(listOf("std", "function"))
                out.angles {
                    returnType.emitTokens(this)
                    parens {
                        for ((idx, p) in paramTypes.withIndex()) {
                            if (idx > 0) {
                                punct(",", jointToNext = false)
                                space()
                            }
                            p.emitTokens(this)
                        }
                    }
                }
            }
            is SuspendFunction -> {
                if (qualifiers.isNotEmpty()) {
                    out.qualifiedName(qualifiers)
                    out.punct("::", jointToNext = true)
                }
                out.ident("SuspendFunction")
                out.angles {
                    returnType.emitTokens(this)
                    for (p in paramTypes) {
                        punct(",", jointToNext = false)
                        space()
                        p.emitTokens(this)
                    }
                }
            }
        }
    }

    public fun toTokenStream(): CppTokenStream {
        val emitter = CppTokenEmitter()
        emitTokens(emitter)
        return emitter.toTokenStream()
    }

    public fun render(): String = toTokenStream().render()

    private fun emitStdSingleArgTemplate(
        out: CppTokenEmitter,
        templateName: String,
        element: CppType,
    ) {
        out.qualifiedName(listOf("std", templateName))
        out.angles {
            element.emitTokens(this)
        }
    }

    public companion object {
        private val SCALAR_TYPE_NAMES: Set<String> =
            setOf(
                "char",
                "char8_t",
                "char16_t",
                "char32_t",
                "wchar_t",
                "short",
                "int",
                "long",
                "long long",
                "unsigned char",
                "unsigned short",
                "unsigned int",
                "unsigned long",
                "unsigned long long",
                "float",
                "double",
                "long double",
                "std::int8_t",
                "std::int16_t",
                "std::int32_t",
                "std::int64_t",
                "std::uint8_t",
                "std::uint16_t",
                "std::uint32_t",
                "std::uint64_t",
                "std::size_t",
                "std::ptrdiff_t",
                "std::intptr_t",
                "std::uintptr_t",
            )

        private val BUILTIN_KEYWORDS_OR_QUALIFIED: Map<String, CppBuiltinType> =
            mapOf(
                "void" to CppBuiltinType.VOID,
                "auto" to CppBuiltinType.AUTO,
                "bool" to CppBuiltinType.BOOL,
                "std::nullptr_t" to CppBuiltinType.NULLPTR_T,
                "std::string" to CppBuiltinType.STD_STRING,
                "std::string_view" to CppBuiltinType.STD_STRING_VIEW,
                "std::any" to CppBuiltinType.STD_ANY,
                "std::exception_ptr" to CppBuiltinType.EXCEPTION_PTR,
            )

        /** Parses any rendered C++23 type string into a structured [CppType]. */
        public fun parse(
            rendered: String,
            typeParamNames: List<String> = emptyList(),
        ): CppType = parseInternal(rendered, if (typeParamNames.isEmpty()) emptySet() else typeParamNames.toSet())

        internal fun parseInternal(
            rendered: String,
            typeParamNames: Set<String> = emptySet(),
        ): CppType {
            val trimmed = rendered.trim()
            if (trimmed.isEmpty()) return Builtin(CppBuiltinType.VOID)
            val stream = CppTokenStream.tokenizeCppCode(trimmed)
            val expanded = expandTokensForTypeParse(stream.tokens)
            val cursor = TokenCursor(expanded)
            val parsed = parseTypeExpr(cursor, typeParamNames)
            return if (parsed != null && cursor.isAtEnd()) {
                parsed
            } else {
                Dependent(trimmed)
            }
        }

        /** Flattens layout tokens and splits `>>` into two `>` tokens for nested template arguments. */
        private fun expandTokensForTypeParse(rawTokens: List<CppToken>): List<CppToken> {
            val out = ArrayList<CppToken>(rawTokens.size)
            for (tok in rawTokens) {
                when (tok) {
                    is CppToken.Layout,
                    is CppToken.Comment,
                    -> Unit
                    is CppToken.Punct ->
                        when (tok.symbol) {
                            ">>" -> {
                                out.add(CppToken.Punct(">", jointToNext = false))
                                out.add(CppToken.Punct(">", jointToNext = false))
                            }
                            "&&" -> out.add(tok)
                            else -> out.add(tok)
                        }
                    else -> out.add(tok)
                }
            }
            return out
        }

        private class TokenCursor(
            private val tokens: List<CppToken>,
        ) {
            var pos: Int = 0

            fun isAtEnd(): Boolean = pos >= tokens.size

            fun peek(offset: Int = 0): CppToken? = tokens.getOrNull(pos + offset)

            fun peekWord(offset: Int = 0): String? =
                when (val t = peek(offset)) {
                    is CppToken.Ident -> t.name
                    is CppToken.Keyword -> t.keyword
                    else -> null
                }

            fun peekPunct(symbol: String): Boolean {
                val t = peek()
                return t is CppToken.Punct && t.symbol == symbol
            }

            fun consume(): CppToken = tokens[pos++]

            fun consumePunct(symbol: String): Boolean {
                if (peekPunct(symbol)) {
                    pos += 1
                    return true
                }
                return false
            }
        }

        private fun parseTypeExpr(
            cursor: TokenCursor,
            typeParamNames: Set<String>,
        ): CppType? {
            if (consumeNoReturnAttr(cursor)) {
                val inner = parseTypeExpr(cursor, typeParamNames) ?: return null
                return NoReturn(inner)
            }
            val hasConst =
                (cursor.peek() as? CppToken.Keyword)?.keyword == "const" &&
                    run {
                        cursor.consume()
                        true
                    }
            var base = parseBaseType(cursor, typeParamNames) ?: return null
            var remainingConst = hasConst
            while (!cursor.isAtEnd()) {
                when {
                    cursor.consumePunct("*") -> {
                        base = RawPtr(base, isConstPointee = remainingConst)
                        remainingConst = false
                    }
                    cursor.consumePunct("&") -> {
                        base = Ref(base, isConst = remainingConst, isRvalue = false)
                        remainingConst = false
                    }
                    cursor.consumePunct("&&") -> {
                        base = Ref(base, isConst = remainingConst, isRvalue = true)
                        remainingConst = false
                    }
                    else -> break
                }
            }
            if (remainingConst) {
                // Rare standalone `const T` without pointer/ref: preserve exact spelling in Dependent
                return null
            }
            return base
        }

        private fun consumeNoReturnAttr(cursor: TokenCursor): Boolean {
            if (!cursor.peekPunct("[")) return false
            val t1 = cursor.peek(1)
            val t2 = cursor.peek(2)
            val t3 = cursor.peek(3)
            val t4 = cursor.peek(4)
            if (t1 is CppToken.Punct && t1.symbol == "[" &&
                ((t2 is CppToken.Ident && t2.name == "noreturn") || (t2 is CppToken.Keyword && t2.keyword == "noreturn")) &&
                t3 is CppToken.Punct && t3.symbol == "]" &&
                t4 is CppToken.Punct && t4.symbol == "]"
            ) {
                cursor.pos += 5
                return true
            }
            return false
        }

        private fun parseBaseType(
            cursor: TokenCursor,
            typeParamNames: Set<String>,
        ): CppType? {
            val multiScalar = tryConsumeMultiWordScalar(cursor)
            if (multiScalar != null) {
                return Scalar(multiScalar, isUnsigned = multiScalar.startsWith("unsigned "))
            }
            val segments = ArrayList<String>(4)
            val firstWord = cursor.peekWord() ?: return null
            if (firstWord == "typename" || firstWord == "decltype" || firstWord == "struct" || firstWord == "class") {
                return null
            }
            cursor.consume()
            segments.add(firstWord)
            while (cursor.peekPunct("::")) {
                val nextWord = cursor.peekWord(1) ?: break
                cursor.consume()
                cursor.consume()
                segments.add(nextWord)
            }
            val joined = segments.joinToString("::")
            if (!cursor.peekPunct("<")) {
                BUILTIN_KEYWORDS_OR_QUALIFIED[joined]?.let { return Builtin(it) }
                if (joined in SCALAR_TYPE_NAMES) {
                    return Scalar(joined, isUnsigned = joined.startsWith("std::u"))
                }
                if (segments.size == 1 && segments[0] in typeParamNames) {
                    return TypeParam(segments[0])
                }
                return Named(
                    qualifiers = segments.dropLast(1),
                    simpleName = segments.last(),
                    typeArgs = emptyList(),
                )
            }

            if (joined == "std::function") {
                return parseStdFunction(cursor, typeParamNames)
            }

            cursor.consumePunct("<")
            val args = ArrayList<CppType>(2)
            if (!cursor.peekPunct(">")) {
                while (!cursor.isAtEnd()) {
                    val arg = parseTypeExpr(cursor, typeParamNames) ?: return null
                    args.add(arg)
                    if (!cursor.consumePunct(",")) break
                }
            }
            if (!cursor.consumePunct(">")) return null

            // Qualified member access after template specialization, e.g. `Outer<T>::Inner`: fail cleanly to Dependent
            if (cursor.peekPunct("::")) return null

            if (segments.last() == "SuspendFunction" &&
                (segments.size == 1 || segments.dropLast(1) == listOf("kotlinx", "coroutines")) &&
                args.isNotEmpty()
            ) {
                return SuspendFunction(
                    qualifiers = segments.dropLast(1),
                    returnType = args.first(),
                    paramTypes = args.drop(1),
                )
            }

            if (joined == "std::shared_ptr" && args.size == 1) return SharedPtr(args[0])
            if (joined == "std::unique_ptr" && args.size == 1) return UniquePtr(args[0])
            if (joined == "std::optional" && args.size == 1) return Optional(args[0])
            if (joined == "std::vector" && args.size == 1) return Vector(args[0])

            return Named(
                qualifiers = segments.dropLast(1),
                simpleName = segments.last(),
                typeArgs = args,
            )
        }

        private fun parseStdFunction(
            cursor: TokenCursor,
            typeParamNames: Set<String>,
        ): CppType? {
            if (!cursor.consumePunct("<")) return null
            val retType = parseTypeExpr(cursor, typeParamNames) ?: return null
            if (!cursor.consumePunct("(")) return null
            val params = ArrayList<CppType>()
            if (!cursor.peekPunct(")")) {
                while (!cursor.isAtEnd()) {
                    val p = parseTypeExpr(cursor, typeParamNames) ?: return null
                    params.add(p)
                    if (!cursor.consumePunct(",")) break
                }
            }
            if (!cursor.consumePunct(")")) return null
            if (!cursor.consumePunct(">")) return null
            return Function(retType, params)
        }

        private fun tryConsumeMultiWordScalar(cursor: TokenCursor): String? {
            val w0 = cursor.peekWord(0) ?: return null
            val w1 = cursor.peekWord(1) ?: return null
            val w2 = cursor.peekWord(2)
            if (w0 == "unsigned" && w1 == "long" && w2 == "long") {
                cursor.pos += 3
                return "unsigned long long"
            }
            if (w0 == "long" && (w1 == "long" || w1 == "double")) {
                cursor.pos += 2
                return "$w0 $w1"
            }
            if (w0 == "unsigned" && (w1 == "char" || w1 == "short" || w1 == "int" || w1 == "long")) {
                cursor.pos += 2
                return "$w0 $w1"
            }
            return null
        }
    }
}
