package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream

/**
 * Deterministic C++23 naming policy enforcing `kotlin.coroutines-cpp/AGENTS.md`:
 * - Types / classes / interfaces / enums: `CamelCase`
 * - Functions / methods / parameters / locals / namespaces: `snake_case`
 * - Private non-constant fields: `snake_case_`
 * - Constants & enum entries: `ALL_CAPS`
 */
public object CppNamingPolicy {
    public fun toTypeName(raw: String): String {
        val cleaned = sanitizeRawIdentifier(raw)
        if (cleaned.isEmpty()) return "UnnamedType"
        val words = splitIdentifierWords(cleaned)
        if (words.isEmpty()) return escapeCppKeyword(cleaned)
        val joined =
            buildString {
                for (w in words) {
                    if (w.isEmpty()) continue
                    append(w[0].uppercaseChar())
                    if (w.length > 1) {
                        val restLower = w.all { it.isUpperCase() || it.isDigit() } && words.size > 1
                        append(if (restLower) w.substring(1).lowercase() else w.substring(1))
                    }
                }
            }
        return escapeCppKeyword(joined)
    }

    public fun toSnakeCase(raw: String): String {
        val cleaned = sanitizeRawIdentifier(raw)
        if (cleaned.isEmpty()) return "unnamed_val"
        val words = splitIdentifierWords(cleaned)
        if (words.isEmpty()) return escapeCppKeyword(cleaned.lowercase())
        val joined = words.joinToString("_") { it.lowercase() }
        return escapeCppKeyword(joined)
    }

    public fun toPrivateFieldName(raw: String): String {
        val cleaned = sanitizeRawIdentifier(raw).trimStart('_').ifEmpty { "field" }
        val base = splitIdentifierWords(cleaned).joinToString("_") { it.lowercase() }.ifEmpty { "field" }
        return "${base.trimEnd('_')}_"
    }

    public fun toConstName(raw: String): String {
        val cleaned = sanitizeRawIdentifier(raw)
        if (cleaned.isEmpty()) return "UNNAMED_CONST"
        val words = splitIdentifierWords(cleaned)
        val upper =
            if (words.isEmpty()) {
                cleaned.uppercase()
            } else {
                words.joinToString("_") { it.uppercase() }
            }
        return escapeCppKeyword(upper)
    }

    public fun toNamespaceSegment(raw: String): String = toSnakeCase(raw)

    private fun sanitizeRawIdentifier(raw: String): String {
        val unquoted =
            if (raw.length >= 2 && raw.startsWith('`') && raw.endsWith('`')) {
                raw.substring(1, raw.length - 1)
            } else {
                raw
            }
        val sb = StringBuilder(unquoted.length)
        for (ch in unquoted) {
            if (ch.isLetterOrDigit() || ch == '_') {
                sb.append(ch)
            } else if (ch == '-' || ch == ' ' || ch == '.') {
                sb.append('_')
            }
        }
        val out = sb.toString()
        return if (out.isNotEmpty() && out[0].isDigit()) "n_$out" else out
    }

    private fun splitIdentifierWords(ident: String): List<String> {
        val parts = ident.split('_').filter { it.isNotEmpty() }
        val words = ArrayList<String>()
        for (part in parts) {
            if (part.all { it.isUpperCase() || it.isDigit() } || part.all { it.isLowerCase() || it.isDigit() }) {
                words.add(part)
                continue
            }
            var wordStart = 0
            for (i in 1 until part.length) {
                val prev = part[i - 1]
                val curr = part[i]
                val nextIsLower = i + 1 < part.length && part[i + 1].isLowerCase()
                val boundary =
                    (prev.isLowerCase() && curr.isUpperCase()) ||
                        (prev.isDigit() && curr.isUpperCase()) ||
                        (prev.isUpperCase() && curr.isUpperCase() && nextIsLower)
                if (boundary) {
                    words.add(part.substring(wordStart, i))
                    wordStart = i
                }
            }
            if (wordStart < part.length) {
                words.add(part.substring(wordStart))
            }
        }
        return words
    }

    private fun escapeCppKeyword(candidate: String): String =
        if (candidate in CPP23_RESERVED_KEYWORDS) "${candidate}_val" else candidate

    private val CPP23_RESERVED_KEYWORDS: Set<String> =
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
}

internal class CppIncludeCollector(
    private val selfHeaderPath: String,
) {
    private val systemHeaders = LinkedHashSet<String>()
    private val projectHeaders = LinkedHashSet<String>()

    fun addSystem(header: String) {
        systemHeaders.add(header.removeSurrounding("<", ">"))
    }

    fun addProject(header: String) {
        val normalized = header.removeSurrounding("\"")
        if (normalized.isNotEmpty() && normalized != selfHeaderPath) {
            projectHeaders.add(normalized)
        }
    }

    fun sortedSystemHeaders(): List<String> = systemHeaders.sorted()

    fun sortedProjectHeaders(): List<String> = projectHeaders.sorted()
}

internal enum class CppTypePosition {
    VALUE,
    RETURN,
    PARAMETER,
    TYPE_ARGUMENT,
}

/**
 * Deterministic Kotlin-to-C++23 type translator supporting `kotlinx.coroutines`,
 * `kotlinx.atomicfu`, `kotlinx.cinterop`, and Kotlin/Native Docking-Ring GC mode.
 */
internal class CppTypeTranslator(
    private val stream: PacketStream,
    private val config: CppTargetConfig,
    private val includes: CppIncludeCollector,
    private val polymorphicTypeNames: Set<String>,
    private val typeParamNames: Set<String>,
) {
    fun translateTypeFrame(
        typeFrame: PacketFrame?,
        position: CppTypePosition = CppTypePosition.VALUE,
        extraFlags: Long = PacketFlags.NONE,
    ): String {
        if (typeFrame == null) {
            return if (position == CppTypePosition.RETURN) "void" else "auto"
        }
        return when (typeFrame.nodeTypeId) {
            KtNodeTypes.TYPE_REFERENCE_ID -> {
                val inner = FrameAstHelpers.childFrames(stream, typeFrame).firstOrNull()
                translateTypeFrame(inner, position, extraFlags or typeFrame.flags)
            }
            KtNodeTypes.NULLABLE_TYPE_ID -> {
                val inner = FrameAstHelpers.childFrames(stream, typeFrame).firstOrNull()
                translateNullableInner(inner, position, extraFlags or typeFrame.flags)
            }
            KtNodeTypes.USER_TYPE_ID -> translateUserType(typeFrame, position, extraFlags or typeFrame.flags)
            KtNodeTypes.FUNCTION_TYPE_ID -> translateFunctionType(typeFrame, isNullable = false, extraFlags or typeFrame.flags)
            KtNodeTypes.DYNAMIC_TYPE_ID -> {
                includes.addSystem("any")
                "std::any"
            }
            KtNodeTypes.TYPE_PROJECTION_ID -> {
                val inner = FrameAstHelpers.firstChildFrame(stream, typeFrame, KtNodeTypes.TYPE_REFERENCE_ID)
                if (inner != null) {
                    translateTypeFrame(inner, CppTypePosition.TYPE_ARGUMENT, extraFlags)
                } else {
                    // Star projection `*`
                    includes.addSystem("any")
                    "std::any"
                }
            }
            else -> {
                val raw = FrameAstHelpers.frameSourceSlice(stream, typeFrame).trim()
                if (raw.isEmpty()) "void" else CppNamingPolicy.toTypeName(raw)
            }
        }
    }

    private fun translateNullableInner(
        inner: PacketFrame?,
        position: CppTypePosition,
        flags: Long,
    ): String {
        if (inner == null) {
            includes.addSystem("any")
            return "std::any"
        }
        if (inner.nodeTypeId == KtNodeTypes.FUNCTION_TYPE_ID) {
            return translateFunctionType(inner, isNullable = true, flags)
        }
        if (inner.nodeTypeId == KtNodeTypes.USER_TYPE_ID) {
            val simpleName = extractUserTypeSimpleName(inner)
            if (simpleName == "Any") {
                if (config.gcMode == CppGcMode.KONAN_GC &&
                    PacketFlags.hasFlag(flags, PacketFlags.GC_MANAGED_OBJECT_REF)
                ) {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    return "ObjHeader*"
                }
                includes.addSystem("any")
                return "std::any"
            }
            if (simpleName == "COpaquePointer" || simpleName == "NativePtr") {
                return "void*"
            }
            if (simpleName == "CPointer" || simpleName == "CValuesRef" || simpleName == "CPointerVar") {
                return translateUserType(inner, position, flags)
            }
            if (simpleName in SHARED_HIERARCHY_TYPES || PacketFlags.hasFlag(flags, PacketFlags.SHARED_OWNER)) {
                val base = translateUserType(inner, CppTypePosition.TYPE_ARGUMENT, flags and PacketFlags.SHARED_OWNER.inv())
                if (config.gcMode == CppGcMode.KONAN_GC &&
                    PacketFlags.hasFlag(flags, PacketFlags.GC_PERSISTENT_EXTERNAL_RC)
                ) {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    return "OwningExternalRCRef"
                }
                if (base.startsWith("std::shared_ptr<")) return base
                includes.addSystem("memory")
                return "std::shared_ptr<$base>"
            }
            if (simpleName in polymorphicTypeNames) {
                val base = translateUserType(inner, CppTypePosition.TYPE_ARGUMENT, flags)
                return "$base*"
            }
        }
        val nonNull = translateTypeFrame(inner, CppTypePosition.TYPE_ARGUMENT, flags)
        if (nonNull.endsWith("*") || nonNull.startsWith("std::shared_ptr<") || nonNull == "std::any") {
            return nonNull
        }
        includes.addSystem("optional")
        return "std::optional<$nonNull>"
    }

    private fun translateUserType(
        userTypeFrame: PacketFrame,
        position: CppTypePosition,
        flags: Long,
    ): String {
        val qualifiedParts = collectUserTypeSegments(userTypeFrame)
        val simpleName = qualifiedParts.lastOrNull() ?: "void"
        val typeArgsFrame = FrameAstHelpers.firstChildFrame(stream, userTypeFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
        val typeArgs =
            if (typeArgsFrame != null) {
                FrameAstHelpers
                    .childFramesOfType(stream, typeArgsFrame, KtNodeTypes.TYPE_PROJECTION_ID)
                    .map { translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT, PacketFlags.NONE) }
            } else {
                emptyList()
            }

        if (simpleName in typeParamNames && typeArgs.isEmpty()) {
            return simpleName
        }

        // 1. kotlinx.cinterop & POSIX C-type unwrapping (Architecture Piece 4)
        translateCInteropType(simpleName, typeArgs, position)?.let { return it }

        // 2. Kotlin built-in scalars, strings, containers, and standard types
        when (simpleName) {
            "Unit" -> {
                if (position == CppTypePosition.TYPE_ARGUMENT) {
                    includes.addProject("kotlinx/coroutines/Unit.hpp")
                    return "Unit"
                }
                return "void"
            }
            "Nothing" -> {
                includes.addSystem("utility")
                return if (position == CppTypePosition.RETURN) "[[noreturn]] void" else "void"
            }
            "Boolean" -> return "bool"
            "Byte" -> {
                includes.addSystem("cstdint")
                return "std::int8_t"
            }
            "Short" -> {
                includes.addSystem("cstdint")
                return "std::int16_t"
            }
            "Int" -> {
                includes.addSystem("cstdint")
                return "std::int32_t"
            }
            "Long" -> {
                includes.addSystem("cstdint")
                return "std::int64_t"
            }
            "UByte" -> {
                includes.addSystem("cstdint")
                return "std::uint8_t"
            }
            "UShort" -> {
                includes.addSystem("cstdint")
                return "std::uint16_t"
            }
            "UInt" -> {
                includes.addSystem("cstdint")
                return "std::uint32_t"
            }
            "ULong" -> {
                includes.addSystem("cstdint")
                return "std::uint64_t"
            }
            "Float" -> return "float"
            "Double" -> return "double"
            "Char" -> return "char16_t"
            "String" -> {
                includes.addSystem("string")
                return "std::string"
            }
            "CharSequence" -> {
                includes.addSystem("string_view")
                return "std::string_view"
            }
            "Any" -> {
                if (config.gcMode == CppGcMode.KONAN_GC &&
                    PacketFlags.hasFlag(flags, PacketFlags.GC_MANAGED_OBJECT_REF)
                ) {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    return "ObjHeader*"
                }
                includes.addSystem("any")
                return "std::any"
            }
            "Throwable",
            "Exception",
            "RuntimeException",
            "IllegalStateException",
            "IllegalArgumentException",
            -> {
                includes.addSystem("exception")
                return "std::exception_ptr"
            }
            "ByteArray" -> {
                includes.addSystem("cstdint")
                includes.addSystem("vector")
                return "std::vector<std::uint8_t>"
            }
            "ShortArray" -> {
                includes.addSystem("cstdint")
                includes.addSystem("vector")
                return "std::vector<std::int16_t>"
            }
            "IntArray" -> {
                includes.addSystem("cstdint")
                includes.addSystem("vector")
                return "std::vector<std::int32_t>"
            }
            "LongArray" -> {
                includes.addSystem("cstdint")
                includes.addSystem("vector")
                return "std::vector<std::int64_t>"
            }
            "FloatArray" -> {
                includes.addSystem("vector")
                return "std::vector<float>"
            }
            "DoubleArray" -> {
                includes.addSystem("vector")
                return "std::vector<double>"
            }
            "BooleanArray" -> {
                includes.addSystem("vector")
                return "std::vector<bool>"
            }
            "CharArray" -> {
                includes.addSystem("vector")
                return "std::vector<char16_t>"
            }
            "List",
            "MutableList",
            "ArrayList",
            "Collection",
            "MutableCollection",
            "Array",
            -> {
                includes.addSystem("vector")
                val elem = typeArgs.firstOrNull() ?: "std::any"
                return "std::vector<$elem>"
            }
            "Set",
            "MutableSet",
            "HashSet",
            "LinkedHashSet",
            -> {
                includes.addSystem("unordered_set")
                val elem = typeArgs.firstOrNull() ?: "std::any"
                return "std::unordered_set<$elem>"
            }
            "Map",
            "MutableMap",
            "HashMap",
            "LinkedHashMap",
            -> {
                includes.addSystem("unordered_map")
                val k = typeArgs.getOrNull(0) ?: "std::string"
                val v = typeArgs.getOrNull(1) ?: "std::any"
                return "std::unordered_map<$k, $v>"
            }
            "Pair" -> {
                includes.addSystem("utility")
                val a = typeArgs.getOrNull(0) ?: "std::any"
                val b = typeArgs.getOrNull(1) ?: "std::any"
                return "std::pair<$a, $b>"
            }
            "Triple" -> {
                includes.addSystem("tuple")
                val a = typeArgs.getOrNull(0) ?: "std::any"
                val b = typeArgs.getOrNull(1) ?: "std::any"
                val c = typeArgs.getOrNull(2) ?: "std::any"
                return "std::tuple<$a, $b, $c>"
            }
            "Result" -> {
                includes.addProject("kotlinx/coroutines/Result.hpp")
                val t = typeArgs.firstOrNull() ?: "void*"
                return "Result<$t>"
            }
            "AtomicInt" -> {
                includes.addSystem("atomic")
                includes.addSystem("cstdint")
                return "std::atomic<std::int32_t>"
            }
            "AtomicLong" -> {
                includes.addSystem("atomic")
                includes.addSystem("cstdint")
                return "std::atomic<std::int64_t>"
            }
            "AtomicBoolean" -> {
                includes.addSystem("atomic")
                return "std::atomic<bool>"
            }
            "AtomicRef" -> {
                includes.addSystem("atomic")
                val t = typeArgs.firstOrNull() ?: "void*"
                return "std::atomic<$t>"
            }
            "CommonThreadLocal",
            "ThreadLocal",
            -> {
                includes.addProject("kotlinx/coroutines/internal/ThreadLocal.hpp")
                val t = typeArgs.firstOrNull() ?: "void*"
                return "ThreadLocal<$t>"
            }
        }

        val cppName =
            if (qualifiedParts.size > 1) {
                val prefix =
                    qualifiedParts.dropLast(1).joinToString("::") { seg ->
                        if (seg.firstOrNull()?.isUpperCase() == true) {
                            CppNamingPolicy.toTypeName(seg)
                        } else {
                            CppNamingPolicy.toNamespaceSegment(seg)
                        }
                    }
                "$prefix::${CppNamingPolicy.toTypeName(simpleName)}"
            } else {
                CppNamingPolicy.toTypeName(simpleName)
            }

        val parameterized =
            if (typeArgs.isNotEmpty()) {
                "$cppName<${typeArgs.joinToString(", ")}>"
            } else {
                cppName
            }

        if ((simpleName in SHARED_HIERARCHY_TYPES || PacketFlags.hasFlag(flags, PacketFlags.SHARED_OWNER)) &&
            position != CppTypePosition.TYPE_ARGUMENT
        ) {
            includes.addSystem("memory")
            return "std::shared_ptr<$parameterized>"
        }
        if (simpleName in polymorphicTypeNames && position == CppTypePosition.PARAMETER) {
            return "$parameterized&"
        }
        return parameterized
    }

    private fun translateCInteropType(
        simpleName: String,
        typeArgs: List<String>,
        position: CppTypePosition,
    ): String? =
        when (simpleName) {
            "ByteVar" -> {
                includes.addSystem("cstdint")
                "std::int8_t"
            }
            "ShortVar" -> {
                includes.addSystem("cstdint")
                "std::int16_t"
            }
            "IntVar" -> {
                includes.addSystem("cstdint")
                "std::int32_t"
            }
            "LongVar" -> {
                includes.addSystem("cstdint")
                "std::int64_t"
            }
            "UByteVar" -> {
                includes.addSystem("cstdint")
                "std::uint8_t"
            }
            "UShortVar" -> {
                includes.addSystem("cstdint")
                "std::uint16_t"
            }
            "UIntVar" -> {
                includes.addSystem("cstdint")
                "std::uint32_t"
            }
            "ULongVar" -> {
                includes.addSystem("cstdint")
                "std::uint64_t"
            }
            "FloatVar" -> "float"
            "DoubleVar" -> "double"
            "BooleanVar" -> "bool"
            "size_t",
            "ptrdiff_t",
            -> {
                includes.addSystem("cstddef")
                simpleName
            }
            "ssize_t" -> {
                includes.addSystem("sys/types.h")
                "ssize_t"
            }
            "intptr_t",
            "uintptr_t",
            -> {
                includes.addSystem("cstdint")
                "std::$simpleName"
            }
            "COpaquePointer",
            "NativePtr",
            -> "void*"
            "CValuesRef" -> {
                val target = typeArgs.firstOrNull() ?: "void"
                if (target == "std::int8_t") "const char*" else "const $target*"
            }
            "CPointer" -> {
                val target = typeArgs.firstOrNull() ?: "void"
                if (target == "std::int8_t") {
                    if (position == CppTypePosition.PARAMETER) "char*" else "char*"
                } else {
                    "$target*"
                }
            }
            "CPointerVar" -> {
                val target = typeArgs.firstOrNull() ?: "void*"
                if (target.endsWith("*")) "$target*" else "$target**"
            }
            else -> null
        }

    private fun translateFunctionType(
        fnTypeFrame: PacketFrame,
        isNullable: Boolean,
        flags: Long,
    ): String {
        val isSuspend =
            PacketFlags.hasFlag(flags, PacketFlags.MOD_SUSPEND) ||
                PacketFlags.hasFlag(fnTypeFrame.flags, PacketFlags.MOD_SUSPEND) ||
                hasSuspendModifierInAncestorsOrSlice(fnTypeFrame)

        val receiverWrap = FrameAstHelpers.firstChildFrame(stream, fnTypeFrame, KtNodeTypes.FUNCTION_TYPE_RECEIVER_ID)
        val receiverTypeRef =
            if (receiverWrap != null) {
                FrameAstHelpers.firstChildFrame(stream, receiverWrap, KtNodeTypes.TYPE_REFERENCE_ID)
            } else {
                null
            }
        val paramList = FrameAstHelpers.firstChildFrame(stream, fnTypeFrame, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
        val paramTypes = ArrayList<String>()
        if (receiverTypeRef != null) {
            paramTypes.add(translateTypeFrame(receiverTypeRef, CppTypePosition.PARAMETER))
        }
        if (paramList != null) {
            for (vp in FrameAstHelpers.childFramesOfType(stream, paramList, KtNodeTypes.VALUE_PARAMETER_ID)) {
                val tr = FrameAstHelpers.firstChildFrame(stream, vp, KtNodeTypes.TYPE_REFERENCE_ID)
                paramTypes.add(translateTypeFrame(tr, CppTypePosition.PARAMETER))
            }
        }
        // Return type is the trailing TYPE_REFERENCE after `->`.
        val directTypeRefs = FrameAstHelpers.childFramesOfType(stream, fnTypeFrame, KtNodeTypes.TYPE_REFERENCE_ID)
        val retFrame = directTypeRefs.lastOrNull()
        val retType =
            if (isSuspend) {
                translateTypeFrame(retFrame, CppTypePosition.TYPE_ARGUMENT)
            } else {
                translateTypeFrame(retFrame, CppTypePosition.RETURN)
            }

        if (isSuspend) {
            includes.addProject("kotlinx/coroutines/SuspendFunction.hpp")
            val allArgs = listOf(retType) + paramTypes
            val base = "SuspendFunction<${allArgs.joinToString(", ")}>"
            if (isNullable) {
                includes.addSystem("optional")
                return "std::optional<$base>"
            }
            return base
        }

        includes.addSystem("functional")
        return "std::function<$retType(${paramTypes.joinToString(", ")})>"
    }

    private fun hasSuspendModifierInAncestorsOrSlice(fnTypeFrame: PacketFrame): Boolean {
        val parentIdx = fnTypeFrame.parentFrameIndex
        if (parentIdx >= 0) {
            val parent = stream.frames[parentIdx]
            if (PacketFlags.hasFlag(parent.flags, PacketFlags.MOD_SUSPEND)) {
                return true
            }
            if (FrameAstHelpers.hasModifierKeyword(stream, parent, KtTokens.SUSPEND_MODIFIER_ID)) {
                return true
            }
        }
        return false
    }

    private fun extractUserTypeSimpleName(userTypeFrame: PacketFrame): String =
        collectUserTypeSegments(userTypeFrame).lastOrNull().orEmpty()

    private fun collectUserTypeSegments(userTypeFrame: PacketFrame): List<String> {
        val segments = ArrayList<String>(4)
        val qualifier = FrameAstHelpers.firstChildFrame(stream, userTypeFrame, KtNodeTypes.USER_TYPE_ID)
        if (qualifier != null) {
            segments.addAll(collectUserTypeSegments(qualifier))
        }
        val refExpr = FrameAstHelpers.firstChildFrame(stream, userTypeFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        if (refExpr != null) {
            FrameAstHelpers.directIdentPacket(stream, refExpr)?.unescapedName?.let { segments.add(it) }
        } else {
            FrameAstHelpers.directIdentPacket(stream, userTypeFrame)?.unescapedName?.let { segments.add(it) }
        }
        return segments
    }

    companion object {
        val SHARED_HIERARCHY_TYPES: Set<String> =
            setOf(
                "Continuation",
                "CancellableContinuation",
                "Job",
                "CompletableJob",
                "Deferred",
                "CompletableDeferred",
                "Channel",
                "SendChannel",
                "ReceiveChannel",
                "CoroutineDispatcher",
                "CoroutineScope",
            )
    }
}
