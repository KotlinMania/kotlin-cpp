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
        val preserveLeadingUnderscore =
            cleaned.length > 1 && cleaned.startsWith('_') && cleaned[1].isLowerCase()
        val preserveTrailingUnderscore =
            cleaned.length > 1 && cleaned.endsWith('_') && !cleaned.startsWith('_') && cleaned.any { it != '_' }
        val words = splitIdentifierWords(cleaned)
        if (words.isEmpty()) return escapeCppKeyword(cleaned.lowercase())
        val joined = words.joinToString("_") { it.lowercase() }
        val prefixed = if (preserveLeadingUnderscore) "_$joined" else joined
        val candidate = if (preserveTrailingUnderscore) "${prefixed}_" else prefixed
        return escapeCppKeyword(candidate)
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

    private fun escapeCppKeyword(candidate: String): String {
        if (candidate !in CPP23_RESERVED_KEYWORDS) return candidate
        return if (candidate.none { it in 'a'..'z' }) "${candidate}_VAL" else "${candidate}_val"
    }

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
            "assert",
            "NULL",
            "EOF",
            "TRUE",
            "FALSE",
        )
}

internal class CppIncludeCollector(
    val selfHeaderPath: String,
) {
    private val systemHeaders = LinkedHashSet<String>()
    private val projectHeaders = LinkedHashSet<String>()
    private val deferredBottomProjectHeaders = LinkedHashSet<String>()
    val rootForwardDeclarations = LinkedHashSet<String>()
    val extraForwardDeclarations = LinkedHashSet<String>()
    var needsCollectionAlias: Boolean = false
    var needsSequenceAlias: Boolean = false
    var needsArrayDequeDef: Boolean = false
    var needsThreadLocalDef: Boolean = false
    var needsSynchronizedObjectDef: Boolean = false
    var needsKotlinIteratorDefs: Boolean = false
    var needsNullOperatorsDef: Boolean = false
    var needsNullUnwrapDef: Boolean = false
    var needsResultHelpersDef: Boolean = false
    var needsAutoCloseableDef: Boolean = false
    var needsStringBuilderDef: Boolean = false
    var needsAtomicReferenceDef: Boolean = false
    var needsCopyableAtomicDef: Boolean = false
    var needsSharedFromThisDef: Boolean = false
    var needsTimeMarkOpsDef: Boolean = false
    var needsIntRangeDef: Boolean = false
    var needsCastHelpersDef: Boolean = false
    var needsAnyEqDef: Boolean = false
    var needsContextOpsDef: Boolean = false
    var needsTrimIndentDef: Boolean = false
    var needsDarwinDispatchHelpers: Boolean = false
    var needsDarwinHeaders: Boolean = false
    var needsPthreadInclude: Boolean = false
    var needsSuspendBoxHelpersDef: Boolean = false
    var needsCoroutineIntrinsicsBridgeDef: Boolean = false
    var needsVarargDef: Boolean = false
    var needsArrayListInitDef: Boolean = false
    var needsIterableHelpersDef: Boolean = false
    var needsEmptyContainersDef: Boolean = false
    var hasExpectDeclarations: Boolean = false

    fun addSystem(header: String) {
        systemHeaders.add(header.removeSurrounding("<", ">"))
    }

    fun addProject(header: String) {
        val normalized = header.removeSurrounding("\"")
        if (normalized.isNotEmpty() && normalized != selfHeaderPath) {
            if (CppTypeTranslator.shouldDeferHeaderInclude(normalized, selfHeaderPath)) {
                deferredBottomProjectHeaders.add(normalized)
            } else {
                projectHeaders.add(normalized)
            }
        }
    }

    fun addDeferredBottomProject(header: String) {
        val normalized = header.removeSurrounding("\"")
        if (normalized.isNotEmpty() && normalized != selfHeaderPath) {
            deferredBottomProjectHeaders.add(normalized)
        }
    }

    fun hasDeferredBottomProject(header: String): Boolean =
        header.removeSurrounding("\"") in deferredBottomProjectHeaders &&
            header.removeSurrounding("\"") !in projectHeaders

    fun sortedSystemHeaders(): List<String> = systemHeaders.sorted()

    fun sortedProjectHeaders(): List<String> = projectHeaders.sorted()

    fun sortedDeferredBottomProjectHeaders(): List<String> =
        deferredBottomProjectHeaders.filter { it !in projectHeaders }.sorted()
}

internal enum class CppTypePosition {
    VALUE,
    RETURN,
    PARAMETER,
    EXTENSION_RECEIVER,
    TYPE_ARGUMENT,
    SUPERTYPE,
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
    private val currentNamespaceSegments: List<String> = emptyList(),
    private val localClassNames: Set<String> = emptySet(),
    private val localClassTypeParams: Map<String, List<String>> = emptyMap(),
    private val sharedTypeParamNames: Set<String> = emptySet(),
    private val sharedHierarchyTypeNames: Set<String> = SHARED_HIERARCHY_TYPES,
    private val sharedPtrTypeAliases: Set<String> = emptySet(),
    private val nonInnerNestedRewrites: Map<Pair<String, String>, String> = emptyMap(),
    private val uniqueNonInnerNestedCppNames: Map<String, String> = emptyMap(),
    private val localClassCppNames: Map<String, String> = emptyMap(),
) {
    fun translateCppType(
        typeFrame: PacketFrame?,
        position: CppTypePosition = CppTypePosition.VALUE,
        extraFlags: Long = PacketFlags.NONE,
        enclosingClassName: String? = null,
    ): CppType =
        CppType.parseInternal(
            rendered = translateTypeFrame(typeFrame, position, extraFlags, enclosingClassName),
            typeParamNames = typeParamNames,
        )

    fun translateTypeFrame(
        typeFrame: PacketFrame?,
        position: CppTypePosition = CppTypePosition.VALUE,
        extraFlags: Long = PacketFlags.NONE,
        enclosingClassName: String? = null,
    ): String {
        if (typeFrame == null) {
            return if (position == CppTypePosition.RETURN) "void" else "auto"
        }
        return when (typeFrame.nodeTypeId) {
            KtNodeTypes.TYPE_REFERENCE_ID -> {
                val inner = FrameAstHelpers.childFrames(stream, typeFrame).firstOrNull { it.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID }
                translateTypeFrame(inner, position, extraFlags or typeFrame.flags, enclosingClassName)
            }
            KtNodeTypes.NULLABLE_TYPE_ID -> {
                val inner = FrameAstHelpers.childFrames(stream, typeFrame).firstOrNull { it.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID }
                translateNullableInner(inner, position, extraFlags or typeFrame.flags, enclosingClassName)
            }
            KtNodeTypes.USER_TYPE_ID ->
                translateUserType(typeFrame, position, extraFlags or typeFrame.flags, enclosingClassName)
            KtNodeTypes.FUNCTION_TYPE_ID -> translateFunctionType(typeFrame, isNullable = false, extraFlags or typeFrame.flags)
            KtNodeTypes.DYNAMIC_TYPE_ID -> {
                includes.addSystem("any")
                "std::any"
            }
            KtNodeTypes.TYPE_PROJECTION_ID -> {
                val inner = FrameAstHelpers.firstChildFrame(stream, typeFrame, KtNodeTypes.TYPE_REFERENCE_ID)
                if (inner != null) {
                    translateTypeFrame(inner, position, extraFlags)
                } else {
                    // Star projection `*`
                    includes.addSystem("any")
                    "std::any"
                }
            }
            KtNodeTypes.INTERSECTION_TYPE_ID -> {
                val first = FrameAstHelpers.childFrames(stream, typeFrame).firstOrNull { it.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID }
                if (first != null) translateTypeFrame(first, position, extraFlags) else "void"
            }
            else -> {
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, typeFrame)
                if (simple.isNullOrEmpty()) "void" else CppNamingPolicy.toTypeName(simple)
            }
        }
    }

    private fun translateNullableInner(
        inner: PacketFrame?,
        position: CppTypePosition,
        flags: Long,
        enclosingClassName: String? = null,
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
            if (simpleName == "CPointer" ||
                simpleName == "CValuesRef" ||
                simpleName == "CPointerVar" ||
                simpleName == "dispatch_queue_t" ||
                simpleName == "dispatch_block_t" ||
                simpleName == "dispatch_object_t" ||
                simpleName == "dispatch_source_t" ||
                (simpleName.startsWith("CF") && simpleName.endsWith("Ref"))
            ) {
                return translateUserType(inner, position, flags, enclosingClassName)
            }
            if (simpleName in sharedHierarchyTypeNames ||
                simpleName in sharedTypeParamNames ||
                PacketFlags.hasFlag(flags, PacketFlags.SHARED_OWNER)
            ) {
                val base =
                    translateUserType(
                        inner,
                        CppTypePosition.TYPE_ARGUMENT,
                        flags and PacketFlags.SHARED_OWNER.inv(),
                        enclosingClassName,
                    )
                if (config.gcMode == CppGcMode.KONAN_GC &&
                    PacketFlags.hasFlag(flags, PacketFlags.GC_PERSISTENT_EXTERNAL_RC)
                ) {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    return "OwningExternalRCRef"
                }
                if (CppType.parseInternal(base, typeParamNames).isSharedPtr) return base
                includes.addSystem("memory")
                return "std::shared_ptr<$base>"
            }
            if (simpleName in polymorphicTypeNames) {
                val base = translateUserType(inner, CppTypePosition.TYPE_ARGUMENT, flags)
                return "$base*"
            }
        }
        val nonNull = translateTypeFrame(inner, CppTypePosition.TYPE_ARGUMENT, flags)
        if (inner.nodeTypeId == KtNodeTypes.USER_TYPE_ID) {
            val simpleName = extractUserTypeSimpleName(inner)
            if (simpleName in sharedPtrTypeAliases) {
                return nonNull
            }
        }
        val parsedNonNull = CppType.parseInternal(nonNull, typeParamNames)
        if (parsedNonNull.isRawPtr ||
            parsedNonNull.isSharedPtr ||
            parsedNonNull.isStdFunction ||
            parsedNonNull.isStdAny ||
            parsedNonNull.isExceptionPtr
        ) {
            return nonNull
        }
        includes.addSystem("optional")
        return "std::optional<$nonNull>"
    }

    fun translateSuperBaseType(typeFrame: PacketFrame): Pair<String, Boolean> {
        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, typeFrame)
        if (simple != null && simple in KOTLIN_STDLIB_EXCEPTION_CLASSES) {
            includes.addSystem("stdexcept")
            return "std::runtime_error" to true
        }
        return translateTypeFrame(typeFrame, CppTypePosition.SUPERTYPE) to false
    }

    private fun translateUserType(
        userTypeFrame: PacketFrame,
        position: CppTypePosition,
        flags: Long,
        enclosingClassName: String? = null,
    ): String {
        val qualifiedParts = collectUserTypeSegments(userTypeFrame)
        val simpleName = qualifiedParts.lastOrNull() ?: "void"
        val typeArgsFrame = FrameAstHelpers.firstChildFrame(stream, userTypeFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
        val elemPosition = if (simpleName in VALUE_CONTAINER_TYPES) CppTypePosition.VALUE else CppTypePosition.TYPE_ARGUMENT
        val typeArgs =
            if (typeArgsFrame != null) {
                FrameAstHelpers
                    .childFramesOfType(stream, typeArgsFrame, KtNodeTypes.TYPE_PROJECTION_ID)
                    .map { proj ->
                        if (simpleName in VOID_PTR_STAR_PROJECTION_TYPES &&
                            FrameAstHelpers.firstChildFrame(stream, proj, KtNodeTypes.TYPE_REFERENCE_ID) == null
                        ) {
                            "void*"
                        } else {
                            translateTypeFrame(proj, elemPosition, PacketFlags.NONE)
                        }
                    }
            } else {
                emptyList()
            }

        if (simpleName in typeParamNames && typeArgs.isEmpty()) {
            if (simpleName in sharedTypeParamNames &&
                position != CppTypePosition.TYPE_ARGUMENT &&
                position != CppTypePosition.SUPERTYPE
            ) {
                includes.addSystem("memory")
                return "std::shared_ptr<$simpleName>"
            }
            return simpleName
        }

        if (qualifiedParts.size >= 2 && qualifiedParts.first() == "kotlinx" && simpleName == "SynchronizedObject" &&
            "atomicfu" in qualifiedParts
        ) {
            includes.addSystem("mutex")
            includes.needsSynchronizedObjectDef = true
            return "KxsSynchronizedObject"
        }
        if (qualifiedParts == listOf("kotlin", "concurrent", "Volatile")) {
            return "void*"
        }

        // 1. kotlinx.cinterop & POSIX C-type unwrapping (Architecture Piece 4)
        translateCInteropType(simpleName, typeArgs, position)?.let { return it }

        // 2. Kotlin built-in scalars, strings, containers, and standard types
        when (simpleName) {
            "Unit" -> {
                if (position != CppTypePosition.RETURN) {
                    includes.addProject("kotlinx/coroutines/Unit.hpp")
                    return if (currentNamespaceSegments.isNotEmpty() &&
                        !isEnclosingOrSameNamespace(NS_CORO, currentNamespaceSegments)
                    ) {
                        "kotlinx::coroutines::Unit"
                    } else {
                        "Unit"
                    }
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
            "Any", "StackTraceElement" -> {
                if (simpleName == "Any" &&
                    config.gcMode == CppGcMode.KONAN_GC &&
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
            "Error",
            "IllegalStateException",
            "IllegalArgumentException",
            "UnsupportedOperationException",
            "IndexOutOfBoundsException",
            "NoSuchElementException",
            "ArithmeticException",
            "AssertionError",
            "ConcurrentModificationException",
            -> {
                includes.addSystem("exception")
                return "std::exception_ptr"
            }
            "AbstractList",
            "AbstractMutableList",
            "Iterator",
            "MutableIterator",
            "ListIterator",
            "MutableListIterator",
            -> {
                includes.addSystem("cstdint")
                includes.needsKotlinIteratorDefs = true
                val elem = typeArgs.firstOrNull() ?: "std::any"
                return "$simpleName<$elem>"
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
            "Collection" -> {
                includes.addSystem("vector")
                val elem = typeArgs.firstOrNull() ?: "std::any"
                if (position == CppTypePosition.EXTENSION_RECEIVER) {
                    includes.needsCollectionAlias = true
                    return "Collection<$elem>"
                }
                return "std::vector<$elem>"
            }
            "List",
            "MutableList",
            "ArrayList",
            "MutableCollection",
            "Iterable",
            "Array",
            -> {
                includes.addSystem("vector")
                val elem = typeArgs.firstOrNull() ?: "std::any"
                return "std::vector<$elem>"
            }
            "ArrayDeque" -> {
                includes.addSystem("deque")
                includes.addSystem("optional")
                includes.needsArrayDequeDef = true
                val elem = typeArgs.firstOrNull() ?: "std::any"
                return "KxsArrayDeque<$elem>"
            }
            "StringBuilder" -> {
                includes.addSystem("string")
                includes.needsStringBuilderDef = true
                return "KxsStringBuilder"
            }
            "Sequence" -> {
                includes.addSystem("vector")
                includes.needsSequenceAlias = true
                val elem = typeArgs.firstOrNull() ?: "std::any"
                return "KxsSequence<$elem>"
            }
            "IntRange" -> {
                includes.addSystem("cstdint")
                includes.needsIntRangeDef = true
                return "KxsIntRange<std::int32_t>"
            }
            "LongRange" -> {
                includes.addSystem("cstdint")
                includes.needsIntRangeDef = true
                return "KxsIntRange<std::int64_t>"
            }
            "Duration" -> {
                includes.addProject("kotlin/time/Duration.hpp")
                includes.needsTimeMarkOpsDef = true
                return "kotlin::time::Duration"
            }
            "DurationUnit" -> {
                includes.addProject("kotlin/time/Duration.hpp")
                return "kotlin::time::DurationUnit"
            }
            "TimeSource" -> {
                includes.addProject("kotlin/time/TimeSource.hpp")
                includes.addSystem("cstdint")
                includes.needsTimeMarkOpsDef = true
                if (position != CppTypePosition.TYPE_ARGUMENT && position != CppTypePosition.SUPERTYPE) {
                    includes.addSystem("memory")
                    return "std::shared_ptr<kotlin::time::TimeSource>"
                }
                return "kotlin::time::TimeSource"
            }
            "WithComparableMarks" -> {
                includes.addProject("kotlin/time/TimeSource.hpp")
                includes.addSystem("cstdint")
                includes.needsTimeMarkOpsDef = true
                if (position != CppTypePosition.TYPE_ARGUMENT && position != CppTypePosition.SUPERTYPE) {
                    includes.addSystem("memory")
                    return "std::shared_ptr<kotlin::time::TimeSource::WithComparableMarks>"
                }
                return "kotlin::time::TimeSource::WithComparableMarks"
            }
            "AbstractLongTimeSource" -> {
                includes.addProject("kotlin/time/TimeSource.hpp")
                includes.addSystem("cstdint")
                includes.needsTimeMarkOpsDef = true
                if (position != CppTypePosition.TYPE_ARGUMENT && position != CppTypePosition.SUPERTYPE) {
                    includes.addSystem("memory")
                    return "std::shared_ptr<kotlin::time::AbstractLongTimeSource>"
                }
                return "kotlin::time::AbstractLongTimeSource"
            }
            "TimeMark" -> {
                includes.addProject("kotlin/time/TimeSource.hpp")
                includes.needsTimeMarkOpsDef = true
                return "kotlin::time::ValueTimeMark"
            }
            "ComparableTimeMark",
            "ValueTimeMark",
            -> {
                includes.addProject("kotlin/time/TimeSource.hpp")
                includes.needsTimeMarkOpsDef = true
                return "kotlin::time::ValueTimeMark"
            }
            "Worker" -> {
                if ("Worker" !in localClassNames) {
                    includes.addProject("kotlin/native/concurrent/Worker.hpp")
                    return "kotlin::native::concurrent::Worker"
                }
            }
            "AutoCloseable" -> {
                includes.needsAutoCloseableDef = true
                if (position != CppTypePosition.TYPE_ARGUMENT && position != CppTypePosition.SUPERTYPE) {
                    includes.addSystem("memory")
                    return "std::shared_ptr<AutoCloseable>"
                }
                return "AutoCloseable"
            }
            "SynchronizedObject",
            "ReentrantLock",
            -> {
                includes.addSystem("mutex")
                includes.needsSynchronizedObjectDef = true
                return "KxsSynchronizedObject"
            }
            "AtomicNativePtr" -> {
                includes.addSystem("atomic")
                return "std::atomic<void*>"
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
                val rawT = typeArgs.firstOrNull() ?: "std::any"
                if (rawT !in TRIVIAL_ATOMIC_SCALAR_TYPES && !rawT.endsWith("*")) {
                    includes.addSystem("mutex")
                    includes.needsAtomicReferenceDef = true
                    return "KxsAtomicReference<$rawT>"
                }
                includes.addSystem("atomic")
                val t = coerceToTrivialAtomicElementType(rawT)
                return "std::atomic<$t>"
            }
            "AtomicReference" -> {
                includes.addSystem("mutex")
                includes.needsAtomicReferenceDef = true
                val rawT = typeArgs.firstOrNull() ?: "std::any"
                return "KxsAtomicReference<$rawT>"
            }
            "CommonThreadLocal" -> {
                if (simpleName !in localClassNames) {
                    includes.addProject("kotlinx/coroutines/internal/ThreadLocal.common.hpp")
                }
                val t = typeArgs.firstOrNull() ?: "void*"
                val prefix =
                    if (currentNamespaceSegments.isNotEmpty() &&
                        !isEnclosingOrSameNamespace(NS_INTERNAL, currentNamespaceSegments)
                    ) {
                        "kotlinx::coroutines::internal::"
                    } else {
                        ""
                    }
                return "${prefix}CommonThreadLocal<$t>"
            }
            "ThreadLocal" -> {
                includes.addSystem("unordered_map")
                includes.needsThreadLocalDef = true
                val t = typeArgs.firstOrNull() ?: "void*"
                return "ThreadLocal<$t>"
            }
            "SuspendFunction0",
            "SuspendFunction1",
            "SuspendFunction2",
            "SuspendFunction3",
            -> {
                includes.addProject("kotlinx/coroutines/SuspendFunction.hpp")
                val arity = simpleName.removePrefix("SuspendFunction").toInt()
                val retT = typeArgs.getOrNull(arity) ?: "void"
                val paramTs = (0 until arity).map { i -> typeArgs.getOrNull(i) ?: "std::any" }
                val allArgs = listOf(retT) + paramTs
                val prefix =
                    if (isEnclosingOrSameNamespace(NS_CORO, currentNamespaceSegments)) {
                        ""
                    } else {
                        "kotlinx::coroutines::"
                    }
                return "${prefix}SuspendFunction<${allArgs.joinToString(", ")}>"
            }
            "KFunction0",
            "KFunction1",
            "KFunction2",
            "KFunction3",
            -> {
                includes.addSystem("functional")
                val arity = simpleName.removePrefix("KFunction").toInt()
                val retT = typeArgs.getOrNull(arity) ?: "void"
                val paramTs = (0 until arity).map { i -> typeArgs.getOrNull(i) ?: "std::any" }
                return "std::function<$retT(${paramTs.joinToString(", ")})>"
            }
            "Comparable" -> {
                includes.addProject("kotlin/Comparable.hpp")
                val elem = typeArgs.firstOrNull() ?: "std::any"
                return "kotlin::Comparable<$elem>"
            }
        }

        if (simpleName == "CancellationException") {
            includes.addProject("kotlin/coroutines/cancellation/CancellationException.hpp")
            return "kotlin::coroutines::cancellation::CancellationException"
        } else if (simpleName == "SuspendFunction") {
            includes.addProject("kotlinx/coroutines/SuspendFunction.hpp")
        } else if (simpleName == "Continuation") {
            includes.addProject("kotlinx/coroutines/Continuation.hpp")
        } else if (simpleName == "ContinuationInterceptor") {
            includes.addProject("kotlinx/coroutines/ContinuationInterceptor.hpp")
        } else if (
            simpleName == "AbstractCoroutineContextElement" ||
            simpleName == "AbstractCoroutineContextKey" ||
            simpleName == "EmptyCoroutineContext"
        ) {
            includes.addProject("kotlinx/coroutines/CoroutineContext.hpp")
            includes.addProject("kotlinx/coroutines/context_impl.hpp")
        } else if (qualifiedParts.firstOrNull() == "CoroutineContext") {
            includes.addProject("kotlinx/coroutines/CoroutineContext.hpp")
        }

        if (qualifiedParts.size == 2 &&
            qualifiedParts[0] == "CoroutineContext" &&
            qualifiedParts[1] == "Key" &&
            typeArgs.isNotEmpty()
        ) {
            val hasStarArg =
                typeArgsFrame != null &&
                    FrameAstHelpers
                        .childFramesOfType(stream, typeArgsFrame, KtNodeTypes.TYPE_PROJECTION_ID)
                        .any { proj ->
                            FrameAstHelpers.firstChildFrame(stream, proj, KtNodeTypes.TYPE_REFERENCE_ID) == null
                        }
            return if (hasStarArg && position != CppTypePosition.SUPERTYPE) {
                "kotlin::coroutines::CoroutineContext::Key*"
            } else {
                "CoroutineContext::KeyTyped<${typeArgs.joinToString(", ")}>"
            }
        }

        val knownCoroType =
            if (qualifiedParts.size == 1 && simpleName !in localClassNames) {
                KNOWN_COROUTINE_TYPES[simpleName]
            } else {
                null
            }
        if (knownCoroType != null) {
            if (shouldDeferHeaderInclude(knownCoroType.headerPath, includes.selfHeaderPath)) {
                if (knownCoroType.namespaceSegments == currentNamespaceSegments && knownCoroType.forwardDecl != null) {
                    includes.extraForwardDeclarations.add(knownCoroType.forwardDecl)
                } else if (knownCoroType.rootForwardDecl != null) {
                    includes.rootForwardDeclarations.add(knownCoroType.rootForwardDecl)
                } else if (knownCoroType.forwardDecl != null) {
                    val ns = knownCoroType.namespaceSegments.joinToString("::")
                    val oneLine = knownCoroType.forwardDecl.replace('\n', ' ')
                    includes.rootForwardDeclarations.add("namespace $ns { $oneLine }")
                }
                includes.addDeferredBottomProject(knownCoroType.headerPath)
            } else {
                includes.addProject(knownCoroType.headerPath)
            }
        }

        val nonInnerRewrite =
            if (qualifiedParts.size >= 2) {
                nonInnerNestedRewrites[qualifiedParts[qualifiedParts.size - 2] to simpleName]
            } else {
                null
            }
        val qualifier =
            if (qualifiedParts.size > 1 && nonInnerRewrite == null) {
                FrameAstHelpers.firstChildFrame(stream, userTypeFrame, KtNodeTypes.USER_TYPE_ID)
            } else {
                null
            }
        val qualTypeArgsFrame =
            qualifier?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_ARGUMENT_LIST_ID) }
        val qualSimple = qualifier?.let { collectUserTypeSegments(it).lastOrNull() }
        val qualIsStarProjectedOnEnclosing =
            enclosingClassName != null &&
                qualSimple == enclosingClassName &&
                qualTypeArgsFrame != null &&
                FrameAstHelpers
                    .childFramesOfType(stream, qualTypeArgsFrame, KtNodeTypes.TYPE_PROJECTION_ID)
                    .all { proj ->
                        FrameAstHelpers.firstChildFrame(stream, proj, KtNodeTypes.TYPE_REFERENCE_ID) == null
                    }
        val preserveGenericQualifier =
            qualifier != null &&
                simpleName != "Companion" &&
                !qualIsStarProjectedOnEnclosing &&
                (qualTypeArgsFrame != null || (qualSimple != null && qualSimple in localClassTypeParams))

        val cppName =
            when {
                nonInnerRewrite != null -> {
                    val nsPrefix =
                        qualifiedParts.dropLast(2).joinToString("::") { seg ->
                            CppNamingPolicy.toNamespaceSegment(seg)
                        }
                    if (nsPrefix.isEmpty()) nonInnerRewrite else "$nsPrefix::$nonInnerRewrite"
                }
                preserveGenericQualifier -> {
                    val qualCpp =
                        translateUserType(qualifier, CppTypePosition.TYPE_ARGUMENT, PacketFlags.NONE, enclosingClassName)
                    val nestedCpp = CppNamingPolicy.toTypeName(simpleName)
                    val isDependent =
                        typeParamNames.any { tp ->
                            Regex("""(?<![A-Za-z0-9_])${Regex.escape(tp)}(?![A-Za-z0-9_])""").containsMatchIn(qualCpp)
                        }
                    if (isDependent && position != CppTypePosition.SUPERTYPE) {
                        "typename $qualCpp::$nestedCpp"
                    } else {
                        "$qualCpp::$nestedCpp"
                    }
                }
                qualifiedParts.size > 1 -> {
                    val prefix =
                        qualifiedParts.dropLast(1).joinToString("::") { seg ->
                            if (seg.firstOrNull()?.isUpperCase() == true) {
                                CppNamingPolicy.toTypeName(seg)
                            } else {
                                CppNamingPolicy.toNamespaceSegment(seg)
                            }
                        }
                    "$prefix::${CppNamingPolicy.toTypeName(simpleName)}"
                }
                knownCoroType != null &&
                    currentNamespaceSegments.isNotEmpty() &&
                    !isEnclosingOrSameNamespace(knownCoroType.namespaceSegments, currentNamespaceSegments)
                -> {
                    "${knownCoroType.namespaceSegments.joinToString("::")}::${CppNamingPolicy.toTypeName(simpleName)}"
                }
                else -> {
                    localClassCppNames[simpleName]
                        ?: uniqueNonInnerNestedCppNames[simpleName]
                        ?: CppNamingPolicy.toTypeName(simpleName)
                }
            }

        val parameterized =
            if (typeArgs.isNotEmpty()) {
                "$cppName<${typeArgs.joinToString(", ")}>"
            } else {
                val localTp = localClassTypeParams[simpleName]
                val expectedArity = localTp?.size ?: knownCoroType?.templateArity ?: if (simpleName == "Continuation") 1 else 0
                if (expectedArity > 0) {
                    val inferredArgs =
                        (0 until expectedArity).map { idx ->
                            val preferred = localTp?.getOrNull(idx) ?: if (idx == 0) "T" else "T$idx"
                            when {
                                preferred in typeParamNames -> preferred
                                idx == 0 && "T" in typeParamNames -> "T"
                                simpleName in VOID_PTR_STAR_PROJECTION_TYPES -> "void*"
                                else -> {
                                    includes.addSystem("any")
                                    "std::any"
                                }
                            }
                        }
                    "$cppName<${inferredArgs.joinToString(", ")}>"
                } else {
                    cppName
                }
            }

        if ((simpleName in sharedHierarchyTypeNames || PacketFlags.hasFlag(flags, PacketFlags.SHARED_OWNER)) &&
            position != CppTypePosition.TYPE_ARGUMENT &&
            position != CppTypePosition.SUPERTYPE
        ) {
            includes.addSystem("memory")
            return "std::shared_ptr<$parameterized>"
        }
        if (simpleName in polymorphicTypeNames &&
            (position == CppTypePosition.PARAMETER || position == CppTypePosition.EXTENSION_RECEIVER)
        ) {
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
            "dispatch_queue_t",
            "dispatch_time_t",
            "dispatch_block_t",
            "dispatch_object_t",
            "dispatch_source_t",
            -> {
                includes.needsDarwinHeaders = true
                simpleName
            }
            "CFRunLoopRef",
            "CFRunLoopTimerRef",
            "CFAllocatorRef",
            "CFStringRef",
            "CFRunLoopMode",
            "CFRunLoopObserverRef",
            "CFTimeInterval",
            "CFAbsoluteTime",
            "CFOptionFlags",
            "CFIndex",
            -> {
                includes.needsDarwinHeaders = true
                simpleName
            }
            "pthread_t",
            "pthread_mutex_t",
            "pthread_cond_t",
            "pthread_attr_t",
            "pthread_key_t",
            -> {
                includes.needsPthreadInclude = true
                includes.addSystem("pthread.h")
                simpleName
            }
            else ->
                when {
                    simpleName.startsWith("CF") && simpleName.endsWith("Ref") -> {
                        includes.needsDarwinHeaders = true
                        simpleName
                    }
                    else -> null
                }
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
                translateCppType(retFrame, CppTypePosition.TYPE_ARGUMENT).stripNoReturn().render()
            } else {
                translateCppType(retFrame, CppTypePosition.RETURN).stripNoReturn().render()
            }

        if (isSuspend) {
            includes.addProject("kotlinx/coroutines/SuspendFunction.hpp")
            val allArgs = listOf(retType) + paramTypes
            val prefix =
                if (isEnclosingOrSameNamespace(NS_CORO, currentNamespaceSegments)) {
                    ""
                } else {
                    "kotlinx::coroutines::"
                }
            val base = "${prefix}SuspendFunction<${allArgs.joinToString(", ")}>"
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
        val TRIVIAL_ATOMIC_SCALAR_TYPES: Set<String> =
            setOf(
                "bool",
                "char",
                "char16_t",
                "char32_t",
                "float",
                "double",
                "int",
                "long",
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

        private fun isNonAtomicStdOrCoroType(type: CppType): Boolean =
            when (type) {
                is CppType.Builtin ->
                    type.kind == CppBuiltinType.STD_STRING ||
                        type.kind == CppBuiltinType.STD_STRING_VIEW ||
                        type.kind == CppBuiltinType.STD_ANY ||
                        type.kind == CppBuiltinType.EXCEPTION_PTR ||
                        type.kind == CppBuiltinType.NULLPTR_T
                is CppType.SharedPtr,
                is CppType.UniquePtr,
                is CppType.Optional,
                is CppType.Vector,
                is CppType.Function,
                is CppType.SuspendFunction,
                -> true
                is CppType.Named ->
                    type.qualifiers.firstOrNull() == "std" ||
                        type.simpleName == "Result" ||
                        type.simpleName == "SuspendFunction"
                else -> false
            }

        fun coerceToTrivialAtomicElementType(
            rawType: String,
            simpleEnumNames: Set<String> = emptySet(),
        ): String {
            val trimmed = rawType.trim()
            if (trimmed.isEmpty()) return "void*"
            val parsed = CppType.parseInternal(trimmed)
            if (parsed.isRawPtr) return trimmed
            if (trimmed in TRIVIAL_ATOMIC_SCALAR_TYPES || trimmed in simpleEnumNames) return trimmed
            if (parsed is CppType.Optional) {
                val inner = parsed.element
                val innerStr = inner.render()
                if (innerStr in TRIVIAL_ATOMIC_SCALAR_TYPES || innerStr in simpleEnumNames) {
                    return trimmed
                }
                if (inner.isRawPtr) return innerStr
                return if (isNonAtomicStdOrCoroType(inner)) "void*" else "$innerStr*"
            }
            return if (isNonAtomicStdOrCoroType(parsed)) "void*" else "$trimmed*"
        }

        val VOID_PTR_STAR_PROJECTION_TYPES: Set<String> =
            setOf(
                "Continuation",
                "DispatchedContinuation",
                "CancellableContinuation",
                "CancellableContinuationImpl",
                "DispatchedTask",
                "Segment",
                "SelectImplementation",
            )

        val VALUE_CONTAINER_TYPES: Set<String> =
            setOf(
                "Collection",
                "MutableCollection",
                "Iterable",
                "Sequence",
                "List",
                "MutableList",
                "ArrayList",
                "ArrayDeque",
                "Array",
                "Set",
                "MutableSet",
                "HashSet",
                "LinkedHashSet",
                "Map",
                "MutableMap",
                "HashMap",
                "LinkedHashMap",
                "Pair",
                "Triple",
                "CommonThreadLocal",
                "ThreadLocal",
                "AtomicRef",
                "AtomicReference",
                "Result",
                "Continuation",
                "CancellableContinuation",
                "CancellableContinuationImpl",
                "DispatchedContinuation",
                "DispatchedTask",
                "Channel",
                "SendChannel",
                "ReceiveChannel",
                "ChannelResult",
                "ChannelIterator",
                "BufferedChannel",
                "ConflatedBufferedChannel",
                "BroadcastChannel",
                "ProducerScope",
                "Flow",
                "FlowCollector",
                "SharedFlow",
                "MutableSharedFlow",
                "SharedFlowImpl",
                "StateFlow",
                "MutableStateFlow",
                "StateFlowImpl",
                "Deferred",
                "CompletableDeferred",
                "AbstractCoroutine",
                "ScopeCoroutine",
                "UndispatchedCoroutine",
                "BlockingCoroutine",
                "SelectClause1",
                "SelectClause2",
                "SelectInstance",
                "SelectBuilder",
                "SelectImplementation",
                "SelectInstanceInternal",
                "UnbiasedSelectImplementation",
                "LockFreeTaskQueue",
                "LockFreeTaskQueueCore",
                "AbstractSharedFlowSlot",
                "WorkaroundAtomicReference",
                "OnUndeliveredElement",
                "ChannelFlow",
                "ChannelFlowOperator",
                "ChannelFlowOperatorImpl",
                "FusibleFlow",
                "SafeCollector",
                "SendingCollector",
                "SubscribedFlowCollector",
            )

        val KOTLIN_STDLIB_EXCEPTION_CLASSES: Set<String> =
            setOf(
                "Throwable",
                "Exception",
                "RuntimeException",
                "Error",
                "IllegalStateException",
                "IllegalArgumentException",
                "UnsupportedOperationException",
                "IndexOutOfBoundsException",
                "NoSuchElementException",
                "ArithmeticException",
                "AssertionError",
                "ConcurrentModificationException",
            )

        val SHARED_HIERARCHY_TYPES: Set<String> =
            setOf(
                "AutoCloseable",
                "BroadcastCoroutine",
                "LazyBroadcastCoroutine",
                "DispatchedCoroutine",
                "Continuation",
                "CancellableContinuation",
                "CancellableContinuationImpl",
                "CancelHandler",
                "NotCompleted",
                "Waiter",
                "CoroutineContext",
                "CoroutineExceptionHandler",
                "CoroutineStackFrame",
                "DisposableHandle",
                "Delay",
                "Runnable",
                "Job",
                "JobSupport",
                "JobImpl",
                "JobNode",
                "ChildJob",
                "ParentJob",
                "ChildHandle",
                "AbstractCoroutine",
                "ScopeCoroutine",
                "UndispatchedCoroutine",
                "BlockingCoroutine",
                "DispatchedTask",
                "DispatchedContinuation",
                "CompletableJob",
                "Deferred",
                "CompletableDeferred",
                "SelectClause0",
                "SelectClause1",
                "SelectClause2",
                "SelectInstance",
                "SelectBuilder",
                "Channel",
                "SendChannel",
                "ReceiveChannel",
                "ChannelIterator",
                "BroadcastChannel",
                "ProducerScope",
                "Flow",
                "SharedFlow",
                "StateFlow",
                "MutableSharedFlow",
                "MutableStateFlow",
                "FlowCollector",
                "SharingStarted",
                "CoroutineDispatcher",
                "CloseableCoroutineDispatcher",
                "MainCoroutineDispatcher",
                "NamedDispatcher",
                "WorkerDispatcher",
                "EventLoop",
                "EventLoopImplBase",
                "EventLoopImplPlatform",
                "CoroutineScope",
                "Mutex",
                "Semaphore",
                "Segment",
                "ConcurrentLinkedListNode",
                "SemaphoreSegment",
                "ChannelSegment",
                "LockFreeLinkedListNode",
                "LockFreeLinkedListHead",
                "LockFreeTaskQueue",
                "LockFreeTaskQueueCore",
                "ThreadSafeHeap",
                "ThreadSafeHeapNode",
                "SelectInstanceInternal",
                "SelectImplementation",
                "LimitedDispatcher",
                "BufferedChannel",
                "ConflatedBufferedChannel",
                "ChannelCoroutine",
                "BroadcastChannelImpl",
                "ConflatedBroadcastChannel",
                "SemaphoreAndMutexImpl",
                "MutexImpl",
                "SemaphoreImpl",
                "AbstractSharedFlow",
                "AbstractSharedFlowSlot",
                "SubscriptionCountStateFlow",
                "ChannelFlow",
                "ChannelFlowOperator",
                "ChannelFlowOperatorImpl",
                "FusibleFlow",
                "SafeCollector",
                "TestScope",
                "TestCoroutineScheduler",
                "TestDispatcher",
                "Incomplete",
                "DelayWithTimeoutDiagnostics",
                "DelayedTask",
                "DelayedTaskQueue",
                "SharedFlowImpl",
                "SharedFlowSlot",
                "StateFlowImpl",
                "StateFlowSlot",
                "CompletedWithCancellation",
                "SelectClause0Impl",
                "SelectClause1Impl",
                "SelectClause2Impl",
                "UnbiasedSelectImplementation",
                "AbstractFlow",
                "CancellableFlow",
                "DefaultExecutor",
                "GlobalScope",
                "NonCancellable",
                "Unconfined",
                "ContextScope",
                "ExecutorCoroutineDispatcher",
                "ExecutorCoroutineDispatcherImpl",
                "Empty",
                "NodeList",
                "InactiveNodeList",
                "CopyableThrowable",
                "CoroutineName",
                "CoroutineId",
                "InvokeOnCompletion",
                "ResumeOnCompletion",
                "ResumeAwaitOnCompletion",
                "InvokeOnCancelling",
                "ChildHandleNode",
                "ChildContinuation",
                "DisposeOnCancel",
                "DisposeOnCompletion",
                "CancellableContinuationWithOwner",
                "SelectInstanceWithOwner",
                "ErrorCatching",
                "NonDisposableHandle",
                "SendingCollector",
                "SubscribedFlowCollector",
            )

        private data class KnownCoroutineType(
            val headerPath: String,
            val namespaceSegments: List<String>,
            val templateArity: Int = 0,
            val forwardDecl: String? = null,
            val rootForwardDecl: String? = null,
            val skipIncludeFromHeaders: Set<String> = emptySet(),
        )

        private data class KnownCoroutineValueSymbol(
            val headerPath: String,
            val namespaceSegments: List<String>,
            val skipIncludeFromHeaders: Set<String> = emptySet(),
        )

        private val NS_CORO = listOf("kotlinx", "coroutines")
        private val NS_INTRINSICS = listOf("kotlinx", "coroutines", "intrinsics")
        private val NS_INTERNAL = listOf("kotlinx", "coroutines", "internal")
        private val NS_SELECTS = listOf("kotlinx", "coroutines", "selects")
        private val NS_CHANNELS = listOf("kotlinx", "coroutines", "channels")
        private val NS_FLOW = listOf("kotlinx", "coroutines", "flow")
        private val NS_FLOW_INTERNAL = listOf("kotlinx", "coroutines", "flow", "internal")
        private val NS_SYNC = listOf("kotlinx", "coroutines", "sync")
        private val NS_TESTING = listOf("kotlinx", "coroutines", "testing")
        private val NS_TEST = listOf("kotlinx", "coroutines", "test")

        fun isSharedHierarchyType(simpleName: String): Boolean = simpleName in SHARED_HIERARCHY_TYPES

        fun isKnownTemplateClass(simpleName: String): Boolean =
            (KNOWN_COROUTINE_TYPES[simpleName]?.templateArity ?: 0) > 0 || simpleName == "Continuation"

        fun knownTemplateArity(simpleName: String): Int = KNOWN_COROUTINE_TYPES[simpleName]?.templateArity ?: 0

        fun lookupKnownHeaderForType(simpleName: String): String? = KNOWN_COROUTINE_TYPES[simpleName]?.headerPath

        fun lookupKnownHeaderForSymbol(simpleName: String): String? =
            KNOWN_COROUTINE_TYPES[simpleName]?.headerPath ?: KNOWN_COROUTINE_VALUE_SYMBOLS[simpleName]?.headerPath

        fun isDeferredCircularSymbol(
            kotlinName: String,
            selfHeaderPath: String,
        ): Boolean {
            if (selfHeaderPath.isEmpty()) return false
            val kt = KNOWN_COROUTINE_TYPES[kotlinName]
            if (kt != null && shouldDeferHeaderInclude(kt.headerPath, selfHeaderPath)) return true
            val kv = KNOWN_COROUTINE_VALUE_SYMBOLS[kotlinName]
            if (kv != null && shouldDeferHeaderInclude(kv.headerPath, selfHeaderPath)) return true
            return false
        }

        fun shouldDeferHeaderInclude(
            targetHeader: String,
            selfHeaderPath: String,
        ): Boolean {
            if (selfHeaderPath.isEmpty()) return false
            if (HEADER_CYCLE_DEFERRALS[selfHeaderPath]?.contains(targetHeader) == true) return true
            return KNOWN_COROUTINE_TYPES.values.any {
                it.headerPath == targetHeader && selfHeaderPath in it.skipIncludeFromHeaders
            } ||
                KNOWN_COROUTINE_VALUE_SYMBOLS.values.any {
                    it.headerPath == targetHeader && selfHeaderPath in it.skipIncludeFromHeaders
                }
        }

        fun isCycleParticipantHeader(relPath: String): Boolean {
            if (relPath.isEmpty()) return false
            return relPath in HEADER_CYCLE_DEFERRALS.keys ||
                HEADER_CYCLE_DEFERRALS.values.any { relPath in it } ||
                KNOWN_COROUTINE_TYPES.values.any {
                    (it.headerPath == relPath && it.skipIncludeFromHeaders.isNotEmpty()) ||
                        relPath in it.skipIncludeFromHeaders
                } ||
                KNOWN_COROUTINE_VALUE_SYMBOLS.values.any {
                    (it.headerPath == relPath && it.skipIncludeFromHeaders.isNotEmpty()) ||
                        relPath in it.skipIncludeFromHeaders
                }
        }

        fun cyclePeersForHeader(relPath: String): List<String> {
            if (relPath.isEmpty()) return emptyList()
            val candidates = LinkedHashSet<String>()
            candidates.addAll(HEADER_CYCLE_DEFERRALS.keys)
            HEADER_CYCLE_DEFERRALS.values.forEach { candidates.addAll(it) }
            KNOWN_COROUTINE_TYPES.values.forEach {
                if (it.skipIncludeFromHeaders.isNotEmpty()) {
                    candidates.add(it.headerPath)
                    candidates.addAll(it.skipIncludeFromHeaders)
                }
            }
            KNOWN_COROUTINE_VALUE_SYMBOLS.values.forEach {
                if (it.skipIncludeFromHeaders.isNotEmpty()) {
                    candidates.add(it.headerPath)
                    candidates.addAll(it.skipIncludeFromHeaders)
                }
            }
            return candidates
                .filter { h ->
                    h != relPath && (shouldDeferHeaderInclude(h, relPath) || shouldDeferHeaderInclude(relPath, h))
                }.sorted()
        }

        fun includeAndQualifyKnownType(
            simpleName: String,
            cppBaseName: String,
            currentNs: List<String>,
            includes: CppIncludeCollector,
        ): String? {
            val known = KNOWN_COROUTINE_TYPES[simpleName] ?: return null
            if (shouldDeferHeaderInclude(known.headerPath, includes.selfHeaderPath)) {
                if (known.namespaceSegments == currentNs && known.forwardDecl != null) {
                    includes.extraForwardDeclarations.add(known.forwardDecl)
                } else if (known.rootForwardDecl != null) {
                    includes.rootForwardDeclarations.add(known.rootForwardDecl)
                } else if (known.forwardDecl != null) {
                    val ns = known.namespaceSegments.joinToString("::")
                    val oneLine = known.forwardDecl.replace('\n', ' ')
                    includes.rootForwardDeclarations.add("namespace $ns { $oneLine }")
                }
                includes.addDeferredBottomProject(known.headerPath)
            } else {
                includes.addProject(known.headerPath)
            }
            return if (currentNs.isNotEmpty() && !isEnclosingOrSameNamespace(known.namespaceSegments, currentNs)) {
                "${known.namespaceSegments.joinToString("::")}::$cppBaseName"
            } else {
                cppBaseName
            }
        }

        fun includeAndQualifyKnownValueSymbol(
            kotlinName: String,
            cppBaseName: String,
            currentNs: List<String>,
            includes: CppIncludeCollector,
        ): String? {
            val knownVal = KNOWN_COROUTINE_VALUE_SYMBOLS[kotlinName]
            if (knownVal != null) {
                if (shouldDeferHeaderInclude(knownVal.headerPath, includes.selfHeaderPath)) {
                    includes.addDeferredBottomProject(knownVal.headerPath)
                } else {
                    includes.addProject(knownVal.headerPath)
                }
                return if (currentNs.isNotEmpty() && !isEnclosingOrSameNamespace(knownVal.namespaceSegments, currentNs)) {
                    "${knownVal.namespaceSegments.joinToString("::")}::$cppBaseName"
                } else {
                    cppBaseName
                }
            }
            return includeAndQualifyKnownType(kotlinName, cppBaseName, currentNs, includes)
        }

        private val SELECT_REVERSE_CYCLE_HEADERS: Set<String> =
            setOf(
                "kotlinx/coroutines/Job.hpp",
                "kotlinx/coroutines/JobSupport.hpp",
                "kotlinx/coroutines/Deferred.hpp",
                "kotlinx/coroutines/Waiter.hpp",
                "kotlinx/coroutines/Delay.hpp",
                "kotlinx/coroutines/channels/Channel.hpp",
                "kotlinx/coroutines/sync/Mutex.hpp",
            )

        private val KNOWN_COROUTINE_VALUE_SYMBOLS: Map<String, KnownCoroutineValueSymbol> =
            mapOf(
                "delay" to KnownCoroutineValueSymbol("kotlinx/coroutines/Delay.hpp", NS_CORO),
                "toDelayMillis" to KnownCoroutineValueSymbol("kotlinx/coroutines/Delay.hpp", NS_CORO),
                "awaitCancellation" to KnownCoroutineValueSymbol("kotlinx/coroutines/Delay.hpp", NS_CORO),
                "yield" to KnownCoroutineValueSymbol("kotlinx/coroutines/Yield.hpp", NS_CORO),
                "suspendCancellableCoroutine" to KnownCoroutineValueSymbol("kotlinx/coroutines/CancellableContinuation.hpp", NS_CORO),
                "suspendCancellableCoroutineReusable" to KnownCoroutineValueSymbol("kotlinx/coroutines/CancellableContinuation.hpp", NS_CORO),
                "disposeOnCancellation" to KnownCoroutineValueSymbol("kotlinx/coroutines/CancellableContinuation.hpp", NS_CORO),
                "RESUME_TOKEN" to KnownCoroutineValueSymbol("kotlinx/coroutines/CancellableContinuationImpl.hpp", NS_CORO),
                "currentCoroutineContext" to KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineScope.hpp", NS_CORO),
                "coroutineScope" to KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineScope.hpp", NS_CORO),
                "MainScope" to KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineScope.hpp", NS_CORO),
                "supervisorScope" to KnownCoroutineValueSymbol("kotlinx/coroutines/Supervisor.hpp", NS_CORO),
                "SupervisorJob" to KnownCoroutineValueSymbol("kotlinx/coroutines/Supervisor.hpp", NS_CORO),
                "withTimeout" to KnownCoroutineValueSymbol("kotlinx/coroutines/Timeout.hpp", NS_CORO),
                "withTimeoutOrNull" to KnownCoroutineValueSymbol("kotlinx/coroutines/Timeout.hpp", NS_CORO),
                "launch" to KnownCoroutineValueSymbol("kotlinx/coroutines/Builders.common.hpp", NS_CORO),
                "async" to KnownCoroutineValueSymbol("kotlinx/coroutines/Builders.common.hpp", NS_CORO),
                "withContext" to KnownCoroutineValueSymbol("kotlinx/coroutines/Builders.common.hpp", NS_CORO),
                "runBlocking" to KnownCoroutineValueSymbol("kotlinx/coroutines/Builders.hpp", NS_CORO),
                "newSingleThreadContext" to KnownCoroutineValueSymbol("kotlinx/coroutines/MultithreadedDispatchers.common.hpp", NS_CORO),
                "newFixedThreadPoolContext" to KnownCoroutineValueSymbol("kotlinx/coroutines/MultithreadedDispatchers.common.hpp", NS_CORO),
                "newCoroutineContext" to KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineContext.common.hpp", NS_CORO),
                "withCoroutineContext" to KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineContext.common.hpp", NS_CORO),
                "withContinuationContext" to KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineContext.common.hpp", NS_CORO),
                "DefaultDelay" to KnownCoroutineValueSymbol("kotlinx/coroutines/native/CoroutineContext.hpp", NS_CORO),
                "toDebugString" to KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineContext.common.hpp", NS_CORO),
                "ensureActive" to KnownCoroutineValueSymbol("kotlinx/coroutines/Job.hpp", NS_CORO),
                "cancel" to KnownCoroutineValueSymbol("kotlinx/coroutines/Job.hpp", NS_CORO),
                "cancelAndJoin" to KnownCoroutineValueSymbol("kotlinx/coroutines/Job.hpp", NS_CORO),
                "cancelChildren" to KnownCoroutineValueSymbol("kotlinx/coroutines/Job.hpp", NS_CORO),
                "isActive" to KnownCoroutineValueSymbol("kotlinx/coroutines/Job.hpp", NS_CORO),
                "job" to KnownCoroutineValueSymbol("kotlinx/coroutines/Job.hpp", NS_CORO),
                "NonDisposableHandle" to KnownCoroutineValueSymbol("kotlinx/coroutines/Job.hpp", NS_CORO),
                "awaitAll" to KnownCoroutineValueSymbol("kotlinx/coroutines/Await.hpp", NS_CORO),
                "joinAll" to KnownCoroutineValueSymbol("kotlinx/coroutines/Await.hpp", NS_CORO),
                "trySelectDetailedResult" to
                    KnownCoroutineValueSymbol(
                        "kotlinx/coroutines/selects/Select.hpp",
                        NS_SELECTS,
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "produce" to KnownCoroutineValueSymbol("kotlinx/coroutines/channels/Produce.hpp", NS_CHANNELS),
                "broadcast" to KnownCoroutineValueSymbol("kotlinx/coroutines/channels/Broadcast.hpp", NS_CHANNELS),
                "DEFAULT_CLOSE_MESSAGE" to
                    KnownCoroutineValueSymbol(
                        headerPath = "kotlinx/coroutines/channels/Channels.common.hpp",
                        namespaceSegments = NS_CHANNELS,
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/channels/Channel.hpp"),
                    ),
                "cancelConsumed" to
                    KnownCoroutineValueSymbol(
                        headerPath = "kotlinx/coroutines/channels/Channels.common.hpp",
                        namespaceSegments = NS_CHANNELS,
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/channels/Channel.hpp"),
                    ),
                "CoroutineExceptionHandler" to KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineExceptionHandler.hpp", NS_CORO),
                "handleCoroutineException" to KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineExceptionHandler.hpp", NS_CORO),
                "recoverResult" to KnownCoroutineValueSymbol("kotlinx/coroutines/CompletionState.hpp", NS_CORO),
                "toState" to KnownCoroutineValueSymbol("kotlinx/coroutines/CompletionState.hpp", NS_CORO),
                "MODE_CANCELLABLE" to KnownCoroutineValueSymbol("kotlinx/coroutines/DispatchedTask.hpp", NS_CORO),
                "MODE_ATOMIC" to KnownCoroutineValueSymbol("kotlinx/coroutines/DispatchedTask.hpp", NS_CORO),
                "MODE_CANCELLABLE_REUSABLE" to KnownCoroutineValueSymbol("kotlinx/coroutines/DispatchedTask.hpp", NS_CORO),
                "MODE_UNDISPATCHED" to KnownCoroutineValueSymbol("kotlinx/coroutines/DispatchedTask.hpp", NS_CORO),
                "MODE_UNINITIALIZED" to KnownCoroutineValueSymbol("kotlinx/coroutines/DispatchedTask.hpp", NS_CORO),
                "isCancellableMode" to KnownCoroutineValueSymbol("kotlinx/coroutines/DispatchedTask.hpp", NS_CORO),
                "isReusableMode" to KnownCoroutineValueSymbol("kotlinx/coroutines/DispatchedTask.hpp", NS_CORO),
                "runUnconfinedEventLoop" to
                    KnownCoroutineValueSymbol(
                        "kotlinx/coroutines/DispatchedTask.hpp",
                        NS_CORO,
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/EventLoop.common.hpp"),
                    ),
                "DEBUG" to KnownCoroutineValueSymbol("kotlinx/coroutines/Debug.common.hpp", NS_CORO),
                "assert" to KnownCoroutineValueSymbol("kotlinx/coroutines/Debug.common.hpp", NS_CORO),
                "ASSERTIONS_ENABLED" to KnownCoroutineValueSymbol("kotlinx/coroutines/Debug.common.hpp", NS_CORO),
                "RECOVER_STACK_TRACES" to KnownCoroutineValueSymbol("kotlinx/coroutines/Exceptions.hpp", NS_CORO),
                "hexAddress" to KnownCoroutineValueSymbol("kotlinx/coroutines/Debug.common.hpp", NS_CORO),
                "classSimpleName" to KnownCoroutineValueSymbol("kotlinx/coroutines/Debug.common.hpp", NS_CORO),
                "ThreadLocalEventLoop" to KnownCoroutineValueSymbol("kotlinx/coroutines/EventLoop.common.hpp", NS_CORO),
                "createEventLoop" to KnownCoroutineValueSymbol("kotlinx/coroutines/EventLoop.common.hpp", NS_CORO),
                "checkParallelism" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/LimitedDispatcher.hpp", NS_INTERNAL),
                "namedOrThis" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/LimitedDispatcher.hpp", NS_INTERNAL),
                "recoverStackTrace" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/StackTraceRecovery.common.hpp", NS_INTERNAL),
                "recoverAndThrow" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/StackTraceRecovery.common.hpp", NS_INTERNAL),
                "unwrap" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/StackTraceRecovery.common.hpp", NS_INTERNAL),
                "resumeCancellableWith" to
                    KnownCoroutineValueSymbol(
                        "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                        NS_INTERNAL,
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/CoroutineDispatcher.hpp",
                                "kotlinx/coroutines/DispatchedTask.hpp",
                                "kotlinx/coroutines/EventLoop.common.hpp",
                            ),
                    ),
                "safeDispatch" to
                    KnownCoroutineValueSymbol(
                        "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                        NS_INTERNAL,
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/CoroutineDispatcher.hpp",
                                "kotlinx/coroutines/DispatchedTask.hpp",
                                "kotlinx/coroutines/EventLoop.common.hpp",
                            ),
                    ),
                "safeIsDispatchNeeded" to
                    KnownCoroutineValueSymbol(
                        "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                        NS_INTERNAL,
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/CoroutineDispatcher.hpp",
                                "kotlinx/coroutines/DispatchedTask.hpp",
                                "kotlinx/coroutines/EventLoop.common.hpp",
                            ),
                    ),
                "threadContextElements" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/ThreadContext.common.hpp", NS_INTERNAL),
                "commonThreadLocal" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/ThreadLocal.common.hpp", NS_INTERNAL),
                "synchronized" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/Synchronized.common.hpp", NS_INTERNAL),
                "identitySet" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/Concurrent.common.hpp", NS_INTERNAL),
                "CLOSED" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/ConcurrentLinkedList.hpp", NS_INTERNAL),
                "systemProp" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/SystemProps.common.hpp", NS_INTERNAL),
                "handleUncaughtCoroutineException" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/CoroutineExceptionHandlerImpl.common.hpp", NS_INTERNAL),
                "ensurePlatformExceptionHandlerLoaded" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/CoroutineExceptionHandlerImpl.common.hpp", NS_INTERNAL),
                "propagateExceptionFinalResort" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/CoroutineExceptionHandlerImpl.common.hpp", NS_INTERNAL),
                "callUndeliveredElement" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/OnUndeliveredElement.hpp", NS_INTERNAL),
                "callUndeliveredElementCatchingException" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/OnUndeliveredElement.hpp", NS_INTERNAL),
                "bindCancellationFun" to KnownCoroutineValueSymbol("kotlinx/coroutines/internal/OnUndeliveredElement.hpp", NS_INTERNAL),
                "intercepted" to KnownCoroutineValueSymbol("kotlinx/coroutines/ContinuationBindings.hpp", listOf("kotlin", "coroutines", "intrinsics")),
                "createCoroutineUnintercepted" to KnownCoroutineValueSymbol("kotlinx/coroutines/intrinsics/IntrinsicsNative.hpp", listOf("kotlin", "coroutines", "intrinsics")),
                "startCoroutineUninterceptedOrReturn" to KnownCoroutineValueSymbol("kotlinx/coroutines/intrinsics/IntrinsicsNative.hpp", listOf("kotlin", "coroutines", "intrinsics")),
                "startCoroutineCancellable" to KnownCoroutineValueSymbol("kotlinx/coroutines/intrinsics/Cancellable.hpp", NS_INTRINSICS),
                "startCoroutineUndispatched" to KnownCoroutineValueSymbol("kotlinx/coroutines/intrinsics/Undispatched.hpp", NS_INTRINSICS),
                "startDirect" to KnownCoroutineValueSymbol("kotlinx/coroutines/intrinsics/Undispatched.hpp", NS_INTRINSICS),
                "startUndispatchedOrReturn" to KnownCoroutineValueSymbol("kotlinx/coroutines/intrinsics/Undispatched.hpp", NS_INTRINSICS),
                "startUndispatchedOrReturnIgnoreTimeout" to KnownCoroutineValueSymbol("kotlinx/coroutines/intrinsics/Undispatched.hpp", NS_INTRINSICS),
                "checkContext" to KnownCoroutineValueSymbol("kotlinx/coroutines/flow/internal/SafeCollector.common.hpp", NS_FLOW_INTERNAL),
                "BroadcastChannel" to
                    KnownCoroutineValueSymbol("kotlinx/coroutines/channels/BroadcastChannel.hpp", NS_CHANNELS),
                "isLazy" to
                    KnownCoroutineValueSymbol("kotlinx/coroutines/CoroutineStart.hpp", NS_CORO),
                "probeCoroutineCreated" to
                    KnownCoroutineValueSymbol(
                        "kotlinx/coroutines/internal/ProbesSupport.common.hpp",
                        NS_INTERNAL,
                    ),
                "probeCoroutineResumed" to
                    KnownCoroutineValueSymbol(
                        "kotlinx/coroutines/internal/ProbesSupport.common.hpp",
                        NS_INTERNAL,
                    ),
                "probeCoroutineSuspended" to
                    KnownCoroutineValueSymbol(
                        "kotlinx/coroutines/internal/ProbesSupport.common.hpp",
                        NS_INTERNAL,
                    ),
                "unboxState" to KnownCoroutineValueSymbol("kotlinx/coroutines/JobSupport.hpp", NS_CORO),
                "NULL" to KnownCoroutineValueSymbol("kotlinx/coroutines/flow/internal/NullSurrogate.hpp", NS_FLOW_INTERNAL),
                "UNINITIALIZED" to KnownCoroutineValueSymbol("kotlinx/coroutines/flow/internal/NullSurrogate.hpp", NS_FLOW_INTERNAL),
                "DONE" to KnownCoroutineValueSymbol("kotlinx/coroutines/flow/internal/NullSurrogate.hpp", NS_FLOW_INTERNAL),
                "NO_VALUE" to
                    KnownCoroutineValueSymbol(
                        headerPath = "kotlinx/coroutines/flow/SharedFlow.hpp",
                        namespaceSegments = NS_FLOW,
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/flow/StateFlow.hpp",
                                "kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp",
                            ),
                    ),
                "EMPTY_RESUMES" to
                    KnownCoroutineValueSymbol(
                        "kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp",
                        NS_FLOW_INTERNAL,
                    ),
                "noImpl" to KnownCoroutineValueSymbol("kotlinx/coroutines/flow/Migration.hpp", NS_FLOW),
                "createMainDispatcher" to KnownCoroutineValueSymbol("kotlinx/coroutines/Dispatchers.hpp", NS_CORO),
                "Worker" to KnownCoroutineValueSymbol("kotlin/native/concurrent/Worker.hpp", listOf("kotlin", "native", "concurrent")),
                "TransferMode" to
                    KnownCoroutineValueSymbol("kotlin/native/concurrent/Worker.hpp", listOf("kotlin", "native", "concurrent")),
            )

        private fun isEnclosingOrSameNamespace(
            candidatePrefix: List<String>,
            currentNs: List<String>,
        ): Boolean {
            if (candidatePrefix.size > currentNs.size) return false
            for (i in candidatePrefix.indices) {
                if (candidatePrefix[i] != currentNs[i]) return false
            }
            return true
        }

        private val HEADER_CYCLE_DEFERRALS: Map<String, Set<String>> =
            mapOf(
                "kotlinx/coroutines/Job.hpp" to
                    setOf(
                        "kotlinx/coroutines/selects/Select.hpp",
                    ),
                "kotlinx/coroutines/Waiter.hpp" to
                    setOf(
                        "kotlinx/coroutines/internal/ConcurrentLinkedList.hpp",
                        "kotlinx/coroutines/selects/Select.hpp",
                    ),
                "kotlinx/coroutines/Delay.hpp" to
                    setOf(
                        "kotlinx/coroutines/selects/Select.hpp",
                        "kotlinx/coroutines/CancellableContinuation.hpp",
                        "kotlinx/coroutines/Job.hpp",
                    ),
                "kotlinx/coroutines/CancellableContinuation.hpp" to
                    setOf(
                        "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                        "kotlinx/coroutines/CancellableContinuationImpl.hpp",
                        "kotlinx/coroutines/CoroutineDispatcher.hpp",
                        "kotlinx/coroutines/Delay.hpp",
                        "kotlinx/coroutines/DispatchedTask.hpp",
                        "kotlinx/coroutines/EventLoop.common.hpp",
                        "kotlinx/coroutines/Job.hpp",
                        "kotlinx/coroutines/selects/Select.hpp",
                    ),
                "kotlinx/coroutines/CancellableContinuationImpl.hpp" to
                    setOf(
                        "kotlinx/coroutines/JobSupport.hpp",
                        "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                        "kotlinx/coroutines/internal/ConcurrentLinkedList.hpp",
                        "kotlinx/coroutines/selects/Select.hpp",
                    ),
                "kotlinx/coroutines/internal/DispatchedContinuation.hpp" to
                    setOf(
                        "kotlinx/coroutines/CancellableContinuation.hpp",
                        "kotlinx/coroutines/CancellableContinuationImpl.hpp",
                        "kotlinx/coroutines/CoroutineDispatcher.hpp",
                        "kotlinx/coroutines/EventLoop.common.hpp",
                    ),
                "kotlinx/coroutines/DispatchedTask.hpp" to
                    setOf(
                        "kotlinx/coroutines/CompletionState.hpp",
                        "kotlinx/coroutines/CoroutineContext.common.hpp",
                        "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                        "kotlinx/coroutines/CoroutineDispatcher.hpp",
                        "kotlinx/coroutines/EventLoop.common.hpp",
                        "kotlinx/coroutines/JobSupport.hpp",
                        "kotlinx/coroutines/intrinsics/Undispatched.hpp",
                    ),
                "kotlinx/coroutines/EventLoop.common.hpp" to
                    setOf(
                        "kotlinx/coroutines/CancellableContinuation.hpp",
                        "kotlinx/coroutines/DispatchedTask.hpp",
                        "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                        "kotlinx/coroutines/EventLoop.hpp",
                    ),
                "kotlinx/coroutines/selects/Select.hpp" to
                    setOf(
                        "kotlinx/coroutines/channels/Channel.hpp",
                        "kotlinx/coroutines/internal/ConcurrentLinkedList.hpp",
                        "kotlinx/coroutines/JobSupport.hpp",
                    ),
                "kotlinx/coroutines/channels/Channel.hpp" to
                    setOf(
                        "kotlinx/coroutines/channels/BufferedChannel.hpp",
                        "kotlinx/coroutines/channels/ConflatedBufferedChannel.hpp",
                        "kotlinx/coroutines/channels/Channels.common.hpp",
                    ),
                "kotlinx/coroutines/CoroutineScope.hpp" to
                    setOf(
                        "kotlinx/coroutines/internal/Scopes.hpp",
                        "kotlinx/coroutines/AbstractCoroutine.hpp",
                        "kotlinx/coroutines/intrinsics/Undispatched.hpp",
                    ),
                "kotlinx/coroutines/AbstractCoroutine.hpp" to
                    setOf(
                        "kotlinx/coroutines/internal/Scopes.hpp",
                        "kotlinx/coroutines/intrinsics/Undispatched.hpp",
                        "kotlinx/coroutines/intrinsics/Cancellable.hpp",
                    ),
                "kotlinx/coroutines/Dispatchers.hpp" to
                    setOf(
                        "kotlinx/coroutines/MainCoroutineDispatcher.hpp",
                    ),
                "kotlinx/coroutines/MainCoroutineDispatcher.hpp" to
                    setOf(
                        "kotlinx/coroutines/Dispatchers.hpp",
                    ),
                "kotlinx/coroutines/flow/SharingStarted.hpp" to
                    setOf(
                        "kotlinx/coroutines/flow/StateFlow.hpp",
                        "kotlinx/coroutines/flow/SharedFlow.hpp",
                        "kotlinx/coroutines/flow/Share.hpp",
                    ),
                "kotlinx/coroutines/flow/Flow.hpp" to
                    setOf(
                        "kotlinx/coroutines/flow/internal/SafeCollector.common.hpp",
                        "kotlinx/coroutines/flow/internal/SafeCollector.hpp",
                    ),
                "kotlinx/coroutines/test/TestCoroutineScheduler.hpp" to
                    setOf(
                        "kotlinx/coroutines/test/TestDispatcher.hpp",
                    ),
                "kotlinx/coroutines/flow/SharedFlow.hpp" to
                    setOf(
                        "kotlinx/coroutines/flow/StateFlow.hpp",
                        "kotlinx/coroutines/flow/Share.hpp",
                    ),
                "kotlinx/coroutines/flow/StateFlow.hpp" to
                    setOf(
                        "kotlinx/coroutines/flow/Share.hpp",
                    ),
                "kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp" to
                    setOf(
                        "kotlinx/coroutines/flow/StateFlow.hpp",
                        "kotlinx/coroutines/flow/SharedFlow.hpp",
                        "kotlinx/coroutines/flow/Share.hpp",
                    ),
                "kotlinx/coroutines/CoroutineStart.hpp" to
                    setOf(
                        "kotlinx/coroutines/intrinsics/Cancellable.hpp",
                        "kotlinx/coroutines/intrinsics/Undispatched.hpp",
                        "kotlinx/coroutines/CoroutineContext.common.hpp",
                    ),
                "kotlinx/coroutines/intrinsics/Cancellable.hpp" to
                    setOf(
                        "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                        "kotlinx/coroutines/DispatchedTask.hpp",
                    ),
                "kotlinx/coroutines/intrinsics/Undispatched.hpp" to
                    setOf(
                        "kotlinx/coroutines/internal/Scopes.hpp",
                        "kotlinx/coroutines/JobSupport.hpp",
                        "kotlinx/coroutines/CoroutineContext.common.hpp",
                        "kotlinx/coroutines/CompletionState.hpp",
                        "kotlinx/coroutines/DispatchedTask.hpp",
                        "kotlinx/coroutines/Timeout.hpp",
                    ),
                "kotlinx/coroutines/Timeout.hpp" to
                    setOf(
                        "kotlinx/coroutines/intrinsics/Undispatched.hpp",
                    ),
                "kotlinx/coroutines/CoroutineContext.common.hpp" to
                    setOf(
                        "kotlinx/coroutines/CoroutineDispatcher.hpp",
                        "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                    ),
            )

        private val KNOWN_COROUTINE_TYPES: Map<String, KnownCoroutineType> =
            mapOf(
                "Job" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/Job.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class Job;",
                    ),
                "DisposableHandle" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/Job.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class DisposableHandle;",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/Delay.hpp",
                                "kotlinx/coroutines/CancellableContinuation.hpp",
                            ),
                    ),
                "ChildJob" to KnownCoroutineType("kotlinx/coroutines/Job.hpp", NS_CORO),
                "ChildHandle" to KnownCoroutineType("kotlinx/coroutines/Job.hpp", NS_CORO),
                "ParentJob" to KnownCoroutineType("kotlinx/coroutines/Job.hpp", NS_CORO),
                "CompletableJob" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/CompletableJob.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class CompletableJob;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/Job.hpp"),
                    ),
                "JobSupport" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/JobSupport.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class JobSupport;",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/Job.hpp",
                                "kotlinx/coroutines/CompletableJob.hpp",
                                "kotlinx/coroutines/Deferred.hpp",
                            ),
                    ),
                "JobNode" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/JobSupport.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class JobNode;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/Job.hpp"),
                    ),
                "JobImpl" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/JobSupport.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class JobImpl;",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/Job.hpp",
                                "kotlinx/coroutines/CompletableJob.hpp",
                                "kotlinx/coroutines/Deferred.hpp",
                            ),
                    ),
                "AbstractCoroutine" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/AbstractCoroutine.hpp",
                        namespaceSegments = NS_CORO,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass AbstractCoroutine;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/Job.hpp", "kotlinx/coroutines/JobSupport.hpp"),
                    ),
                "UndispatchedCoroutine" to KnownCoroutineType("kotlinx/coroutines/native/CoroutineContext.hpp", NS_CORO, templateArity = 1),
                "BlockingCoroutine" to KnownCoroutineType("kotlinx/coroutines/Builders.hpp", NS_CORO, templateArity = 1),
                "WorkerDispatcher" to KnownCoroutineType("kotlinx/coroutines/MultithreadedDispatchers.hpp", NS_CORO),
                "CompletedExceptionally" to KnownCoroutineType("kotlinx/coroutines/CompletionState.hpp", NS_CORO),
                "CompletedWithCancellation" to KnownCoroutineType("kotlinx/coroutines/CompletionState.hpp", NS_CORO),
                "Dispatchers" to KnownCoroutineType("kotlinx/coroutines/Dispatchers.common.hpp", NS_CORO),
                "GlobalScope" to KnownCoroutineType("kotlinx/coroutines/CoroutineScope.hpp", NS_CORO),
                "Unconfined" to KnownCoroutineType("kotlinx/coroutines/Unconfined.hpp", NS_CORO),
                "YieldContext" to KnownCoroutineType("kotlinx/coroutines/Yield.hpp", NS_CORO),
                "DefaultExecutor" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/native/CoroutineContext.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class DefaultExecutor;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/EventLoop.common.hpp"),
                    ),
                "NonCancellable" to KnownCoroutineType("kotlinx/coroutines/NonCancellable.hpp", NS_CORO),
                "NonDisposableHandle" to KnownCoroutineType("kotlinx/coroutines/Job.hpp", NS_CORO),
                "TimeoutCancellationException" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/Timeout.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class TimeoutCancellationException;",
                    ),
                "JobCancellationException" to KnownCoroutineType("kotlinx/coroutines/Exceptions.hpp", NS_CORO),
                "CoroutinesInternalError" to KnownCoroutineType("kotlinx/coroutines/Exceptions.common.hpp", NS_CORO),
                "CompletionHandlerException" to KnownCoroutineType("kotlinx/coroutines/Exceptions.common.hpp", NS_CORO),
                "CopyableThrowable" to KnownCoroutineType("kotlinx/coroutines/Debug.common.hpp", NS_CORO, templateArity = 1),
                "EventLoopImplBase" to KnownCoroutineType("kotlinx/coroutines/EventLoop.common.hpp", NS_CORO),
                "EventLoopImplPlatform" to KnownCoroutineType("kotlinx/coroutines/EventLoop.common.hpp", NS_CORO),
                "Deferred" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/Deferred.hpp",
                        namespaceSegments = NS_CORO,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass Deferred;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/Job.hpp"),
                    ),
                "CompletableDeferred" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/CompletableDeferred.hpp",
                        namespaceSegments = NS_CORO,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass CompletableDeferred;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/Job.hpp", "kotlinx/coroutines/Deferred.hpp"),
                    ),
                "CancellableContinuation" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/CancellableContinuation.hpp",
                        namespaceSegments = NS_CORO,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass CancellableContinuation;",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/CompletionState.hpp",
                                "kotlinx/coroutines/CoroutineDispatcher.hpp",
                                "kotlinx/coroutines/Delay.hpp",
                                "kotlinx/coroutines/EventLoop.common.hpp",
                            ),
                    ),
                "CancellableContinuationImpl" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/CancellableContinuationImpl.hpp",
                        namespaceSegments = NS_CORO,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass CancellableContinuationImpl;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/CancellableContinuation.hpp"),
                    ),
                "NotCompleted" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/CancellableContinuationImpl.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class NotCompleted;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/Job.hpp"),
                    ),
                "CancelHandler" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/CancellableContinuationImpl.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class CancelHandler;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/CancellableContinuation.hpp", "kotlinx/coroutines/Job.hpp"),
                    ),
                "CompletionHandler" to KnownCoroutineType("kotlinx/coroutines/CompletionHandler.common.hpp", NS_CORO),
                "Runnable" to KnownCoroutineType("kotlinx/coroutines/Runnable.hpp", NS_CORO),
                "Waiter" to KnownCoroutineType("kotlinx/coroutines/Waiter.hpp", NS_CORO),
                "CoroutineDispatcher" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/CoroutineDispatcher.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class CoroutineDispatcher;",
                        rootForwardDecl = "namespace kotlinx::coroutines { class CoroutineDispatcher; }",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/CancellableContinuation.hpp",
                                "kotlinx/coroutines/DispatchedTask.hpp",
                                "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                            ),
                    ),
                "CloseableCoroutineDispatcher" to KnownCoroutineType("kotlinx/coroutines/CloseableCoroutineDispatcher.common.hpp", NS_CORO),
                "MainCoroutineDispatcher" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/MainCoroutineDispatcher.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class MainCoroutineDispatcher;",
                    ),
                "EventLoop" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/EventLoop.common.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class EventLoop;",
                        rootForwardDecl = "namespace kotlinx::coroutines { class EventLoop; }",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/CoroutineDispatcher.hpp",
                                "kotlinx/coroutines/DispatchedTask.hpp",
                                "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                            ),
                    ),
                "ThreadLocalEventLoop" to KnownCoroutineType("kotlinx/coroutines/EventLoop.common.hpp", NS_CORO),
                "DispatchedTask" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/DispatchedTask.hpp",
                        namespaceSegments = NS_CORO,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass DispatchedTask;",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/CoroutineDispatcher.hpp",
                                "kotlinx/coroutines/EventLoop.common.hpp",
                            ),
                    ),
                "SchedulerTask" to KnownCoroutineType("kotlinx/coroutines/SchedulerTask.common.hpp", NS_CORO),
                "SchedulerTaskContext" to KnownCoroutineType("kotlinx/coroutines/SchedulerTask.common.hpp", NS_CORO),
                "DispatchException" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/DispatchedTask.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class DispatchException;",
                    ),
                "CoroutineScope" to
                    KnownCoroutineType(
                        "kotlinx/coroutines/CoroutineScope.hpp",
                        NS_CORO,
                        forwardDecl = "class CoroutineScope;",
                    ),
                "CoroutineName" to KnownCoroutineType("kotlinx/coroutines/CoroutineName.hpp", NS_CORO),
                "CoroutineExceptionHandler" to KnownCoroutineType("kotlinx/coroutines/CoroutineExceptionHandler.hpp", NS_CORO),
                "CoroutineStart" to KnownCoroutineType("kotlinx/coroutines/CoroutineStart.hpp", NS_CORO),
                "Delay" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/Delay.hpp",
                        namespaceSegments = NS_CORO,
                        forwardDecl = "class Delay;",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/Job.hpp",
                                "kotlinx/coroutines/CancellableContinuation.hpp",
                            ),
                    ),
                "Symbol" to KnownCoroutineType("kotlinx/coroutines/internal/Symbol.hpp", NS_INTERNAL),
                "SynchronizedObject" to KnownCoroutineType("kotlinx/coroutines/internal/Synchronized.common.hpp", NS_INTERNAL),
                "ReentrantLock" to KnownCoroutineType("kotlinx/coroutines/internal/Concurrent.common.hpp", NS_INTERNAL),
                "WorkaroundAtomicReference" to KnownCoroutineType("kotlinx/coroutines/internal/Concurrent.common.hpp", NS_INTERNAL, templateArity = 1),
                "LocalAtomicInt" to KnownCoroutineType("kotlinx/coroutines/internal/LocalAtomics.common.hpp", NS_INTERNAL),
                "LockFreeLinkedListNode" to KnownCoroutineType("kotlinx/coroutines/internal/LockFreeLinkedList.hpp", NS_INTERNAL),
                "LockFreeLinkedListHead" to KnownCoroutineType("kotlinx/coroutines/internal/LockFreeLinkedList.hpp", NS_INTERNAL),
                "LockFreeTaskQueue" to KnownCoroutineType("kotlinx/coroutines/internal/LockFreeTaskQueue.hpp", NS_INTERNAL, templateArity = 1),
                "LockFreeTaskQueueCore" to KnownCoroutineType("kotlinx/coroutines/internal/LockFreeTaskQueue.hpp", NS_INTERNAL, templateArity = 1),
                "Segment" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/internal/ConcurrentLinkedList.hpp",
                        namespaceSegments = NS_INTERNAL,
                        templateArity = 1,
                        forwardDecl = "template <typename S>\nclass Segment;",
                        rootForwardDecl = "namespace kotlinx::coroutines::internal { template <typename S> class Segment; }",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/CancellableContinuationImpl.hpp"),
                    ),
                "SegmentOrClosed" to KnownCoroutineType("kotlinx/coroutines/internal/ConcurrentLinkedList.hpp", NS_INTERNAL, templateArity = 1),
                "ConcurrentLinkedListNode" to KnownCoroutineType("kotlinx/coroutines/internal/ConcurrentLinkedList.hpp", NS_INTERNAL, templateArity = 1),
                "UndeliveredElementException" to KnownCoroutineType("kotlinx/coroutines/internal/OnUndeliveredElement.hpp", NS_INTERNAL),
                "OnUndeliveredElement" to KnownCoroutineType("kotlinx/coroutines/internal/OnUndeliveredElement.hpp", NS_INTERNAL, templateArity = 1),
                "ThreadSafeHeap" to KnownCoroutineType("kotlinx/coroutines/internal/ThreadSafeHeap.hpp", NS_INTERNAL, templateArity = 1),
                "ThreadSafeHeapNode" to KnownCoroutineType("kotlinx/coroutines/internal/ThreadSafeHeap.hpp", NS_INTERNAL),
                "InlineList" to KnownCoroutineType("kotlinx/coroutines/internal/InlineList.hpp", NS_INTERNAL, templateArity = 1),
                "CoroutineStackFrame" to KnownCoroutineType("kotlinx/coroutines/internal/StackTraceRecovery.common.hpp", NS_INTERNAL),
                "ScopeCoroutine" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/internal/Scopes.hpp",
                        namespaceSegments = NS_INTERNAL,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass ScopeCoroutine;",
                    ),
                "DispatchedContinuation" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                        namespaceSegments = NS_INTERNAL,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass DispatchedContinuation;",
                        rootForwardDecl = "namespace kotlinx::coroutines::internal { template <typename T> class DispatchedContinuation; }",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/CoroutineDispatcher.hpp",
                                "kotlinx/coroutines/DispatchedTask.hpp",
                                "kotlinx/coroutines/EventLoop.common.hpp",
                            ),
                    ),
                "LimitedDispatcher" to KnownCoroutineType("kotlinx/coroutines/internal/LimitedDispatcher.hpp", NS_INTERNAL),
                "NamedDispatcher" to KnownCoroutineType("kotlinx/coroutines/internal/NamedDispatcher.hpp", NS_INTERNAL),
                "OnDemandAllocatingPool" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/internal/OnDemandAllocatingPool.hpp",
                        namespaceSegments = NS_INTERNAL,
                        templateArity = 1,
                    ),
                "DiagnosticCoroutineContextException" to KnownCoroutineType("kotlinx/coroutines/internal/CoroutineExceptionHandlerImpl.common.hpp", NS_INTERNAL),
                "ExceptionSuccessfullyProcessed" to KnownCoroutineType("kotlinx/coroutines/internal/CoroutineExceptionHandlerImpl.common.hpp", NS_INTERNAL),
                "SelectImplementation" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        templateArity = 1,
                        forwardDecl = "template <typename R>\nclass SelectImplementation;",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "TrySelectDetailedResult" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        forwardDecl = "enum class TrySelectDetailedResult;",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "SelectClause0" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        forwardDecl = "class SelectClause0;",
                        rootForwardDecl = "namespace kotlinx::coroutines::selects { class SelectClause0; }",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "SelectClause1" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        templateArity = 1,
                        forwardDecl = "template <typename Q>\nclass SelectClause1;",
                        rootForwardDecl = "namespace kotlinx::coroutines::selects { template <typename Q> class SelectClause1; }",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "SelectClause2" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        templateArity = 2,
                        forwardDecl = "template <typename P, typename Q>\nclass SelectClause2;",
                        rootForwardDecl = "namespace kotlinx::coroutines::selects { template <typename P, typename Q> class SelectClause2; }",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "SelectInstance" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        templateArity = 1,
                        forwardDecl = "template <typename R>\nclass SelectInstance;",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "SelectBuilder" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        templateArity = 1,
                        forwardDecl = "template <typename R>\nclass SelectBuilder;",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "SelectClause0Impl" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        forwardDecl = "class SelectClause0Impl;",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "SelectClause1Impl" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        templateArity = 1,
                        forwardDecl = "template <typename Q>\nclass SelectClause1Impl;",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "SelectClause2Impl" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        templateArity = 2,
                        forwardDecl = "template <typename P, typename Q>\nclass SelectClause2Impl;",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "SelectInstanceInternal" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        templateArity = 1,
                        forwardDecl = "template <typename R>\nclass SelectInstanceInternal;",
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "OnCancellationConstructor" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "RegistrationFunction" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "ProcessResultFunction" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/selects/Select.hpp",
                        namespaceSegments = NS_SELECTS,
                        skipIncludeFromHeaders = SELECT_REVERSE_CYCLE_HEADERS,
                    ),
                "Channel" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/channels/Channel.hpp",
                        namespaceSegments = NS_CHANNELS,
                        templateArity = 1,
                        forwardDecl = "template <typename E>\nclass Channel;",
                    ),
                "SendChannel" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/channels/Channel.hpp",
                        namespaceSegments = NS_CHANNELS,
                        templateArity = 1,
                        forwardDecl = "template <typename E>\nclass SendChannel;",
                    ),
                "ReceiveChannel" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/channels/Channel.hpp",
                        namespaceSegments = NS_CHANNELS,
                        templateArity = 1,
                        forwardDecl = "template <typename E>\nclass ReceiveChannel;",
                    ),
                "ChannelResult" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/channels/Channel.hpp",
                        namespaceSegments = NS_CHANNELS,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass ChannelResult;",
                    ),
                "ChannelIterator" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/channels/Channel.hpp",
                        namespaceSegments = NS_CHANNELS,
                        templateArity = 1,
                        forwardDecl = "template <typename E>\nclass ChannelIterator;",
                    ),
                "BroadcastChannel" to KnownCoroutineType("kotlinx/coroutines/channels/BroadcastChannel.hpp", NS_CHANNELS, templateArity = 1),
                "BroadcastCoroutine" to KnownCoroutineType("kotlinx/coroutines/channels/Broadcast.hpp", NS_CHANNELS, templateArity = 1),
                "LazyBroadcastCoroutine" to KnownCoroutineType("kotlinx/coroutines/channels/Broadcast.hpp", NS_CHANNELS, templateArity = 1),
                "BufferOverflow" to KnownCoroutineType("kotlinx/coroutines/channels/BufferOverflow.hpp", NS_CHANNELS),
                "ProducerScope" to KnownCoroutineType("kotlinx/coroutines/channels/Produce.hpp", NS_CHANNELS, templateArity = 1),
                "FlowCollector" to KnownCoroutineType("kotlinx/coroutines/flow/FlowCollector.hpp", NS_FLOW, templateArity = 1),
                "Flow" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/Flow.hpp",
                        namespaceSegments = NS_FLOW,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass Flow;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/flow/FlowCollector.hpp"),
                    ),
                "SharedFlow" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/SharedFlow.hpp",
                        namespaceSegments = NS_FLOW,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass SharedFlow;",
                    ),
                "MutableSharedFlow" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/SharedFlow.hpp",
                        namespaceSegments = NS_FLOW,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass MutableSharedFlow;",
                    ),
                "SharedFlowImpl" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/SharedFlow.hpp",
                        namespaceSegments = NS_FLOW,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass SharedFlowImpl;",
                    ),
                "StateFlow" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/StateFlow.hpp",
                        namespaceSegments = NS_FLOW,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass StateFlow;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/flow/SharedFlow.hpp"),
                    ),
                "MutableStateFlow" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/StateFlow.hpp",
                        namespaceSegments = NS_FLOW,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass MutableStateFlow;",
                        skipIncludeFromHeaders = setOf("kotlinx/coroutines/flow/SharedFlow.hpp"),
                    ),
                "Semaphore" to KnownCoroutineType("kotlinx/coroutines/sync/Semaphore.hpp", NS_SYNC),
                "SemaphoreAndMutexImpl" to KnownCoroutineType("kotlinx/coroutines/sync/Semaphore.hpp", NS_SYNC),
                "SemaphoreSegment" to KnownCoroutineType("kotlinx/coroutines/sync/Semaphore.hpp", NS_SYNC),
                "Mutex" to KnownCoroutineType("kotlinx/coroutines/sync/Mutex.hpp", NS_SYNC),
                "BufferedChannel" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/channels/BufferedChannel.hpp",
                        namespaceSegments = NS_CHANNELS,
                        templateArity = 1,
                        forwardDecl = "template <typename E>\nclass BufferedChannel;",
                    ),
                "ChannelSegment" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/channels/BufferedChannel.hpp",
                        namespaceSegments = NS_CHANNELS,
                        templateArity = 1,
                        forwardDecl = "template <typename E>\nclass ChannelSegment;",
                    ),
                "ConflatedBufferedChannel" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/channels/ConflatedBufferedChannel.hpp",
                        namespaceSegments = NS_CHANNELS,
                        templateArity = 1,
                        forwardDecl = "template <typename E>\nclass ConflatedBufferedChannel;",
                    ),
                "ClosedSendChannelException" to KnownCoroutineType("kotlinx/coroutines/channels/Channel.hpp", NS_CHANNELS),
                "ClosedReceiveChannelException" to KnownCoroutineType("kotlinx/coroutines/channels/Channel.hpp", NS_CHANNELS),
                "ChannelCoroutine" to KnownCoroutineType("kotlinx/coroutines/channels/ChannelCoroutine.hpp", NS_CHANNELS, templateArity = 1),
                "SharingStarted" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/SharingStarted.hpp",
                        namespaceSegments = NS_FLOW,
                        forwardDecl = "class SharingStarted;",
                    ),
                "SharingCommand" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/SharingStarted.hpp",
                        namespaceSegments = NS_FLOW,
                        forwardDecl = "enum class SharingCommand;",
                    ),
                "Worker" to
                    KnownCoroutineType(
                        headerPath = "kotlin/native/concurrent/Worker.hpp",
                        namespaceSegments = listOf("kotlin", "native", "concurrent"),
                    ),
                "TransferMode" to
                    KnownCoroutineType(
                        headerPath = "kotlin/native/concurrent/Worker.hpp",
                        namespaceSegments = listOf("kotlin", "native", "concurrent"),
                    ),
                "SubscribedFlowCollector" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/Share.hpp",
                        namespaceSegments = NS_FLOW,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass SubscribedFlowCollector;",
                        skipIncludeFromHeaders =
                            setOf(
                                "kotlinx/coroutines/flow/SharedFlow.hpp",
                                "kotlinx/coroutines/flow/StateFlow.hpp",
                            ),
                    ),
                "SendingCollector" to KnownCoroutineType("kotlinx/coroutines/flow/internal/SendingCollector.hpp", NS_FLOW_INTERNAL, templateArity = 1),
                "NopCollector" to KnownCoroutineType("kotlinx/coroutines/flow/internal/NopCollector.hpp", NS_FLOW_INTERNAL),
                "Duration" to KnownCoroutineType("kotlin/time/Duration.hpp", listOf("kotlin", "time")),
                "DurationUnit" to KnownCoroutineType("kotlin/time/Duration.hpp", listOf("kotlin", "time")),
                "TimeSource" to KnownCoroutineType("kotlin/time/TimeSource.hpp", listOf("kotlin", "time")),
                "AbstractLongTimeSource" to KnownCoroutineType("kotlin/time/TimeSource.hpp", listOf("kotlin", "time")),
                "AbstractSharedFlow" to KnownCoroutineType("kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp", NS_FLOW_INTERNAL, templateArity = 1),
                "AbstractSharedFlowSlot" to KnownCoroutineType("kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp", NS_FLOW_INTERNAL, templateArity = 1),
                "SubscriptionCountStateFlow" to KnownCoroutineType("kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp", NS_FLOW_INTERNAL),
                "ChannelFlow" to KnownCoroutineType("kotlinx/coroutines/flow/internal/ChannelFlow.hpp", NS_FLOW_INTERNAL, templateArity = 1),
                "ChannelFlowOperator" to KnownCoroutineType("kotlinx/coroutines/flow/internal/ChannelFlow.hpp", NS_FLOW_INTERNAL, templateArity = 2),
                "ChannelFlowOperatorImpl" to
                    KnownCoroutineType(
                        "kotlinx/coroutines/flow/internal/ChannelFlow.hpp",
                        NS_FLOW_INTERNAL,
                        templateArity = 1,
                    ),
                "FusibleFlow" to KnownCoroutineType("kotlinx/coroutines/flow/internal/ChannelFlow.hpp", NS_FLOW_INTERNAL, templateArity = 1),
                "SafeCollector" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/flow/internal/SafeCollector.common.hpp",
                        namespaceSegments = NS_FLOW_INTERNAL,
                        templateArity = 1,
                        forwardDecl = "template <typename T>\nclass SafeCollector;",
                    ),
                "AbortFlowException" to KnownCoroutineType("kotlinx/coroutines/flow/internal/FlowExceptions.common.hpp", NS_FLOW_INTERNAL),
                "ChildCancelledException" to KnownCoroutineType("kotlinx/coroutines/flow/internal/FlowExceptions.common.hpp", NS_FLOW_INTERNAL),
                "TestBase" to KnownCoroutineType("kotlinx/coroutines/testing/TestBase.hpp", NS_TESTING),
                "TestScope" to KnownCoroutineType("kotlinx/coroutines/test/TestScope.hpp", NS_TEST),
                "TestCoroutineScheduler" to KnownCoroutineType("kotlinx/coroutines/test/TestCoroutineScheduler.hpp", NS_TEST),
                "TestDispatcher" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/test/TestDispatcher.hpp",
                        namespaceSegments = NS_TEST,
                        forwardDecl = "class TestDispatcher;",
                    ),
                "TestResult" to KnownCoroutineType("kotlinx/coroutines/test/TestBuilders.common.hpp", NS_TEST),
                "ReportingSupervisorJob" to
                    KnownCoroutineType(
                        headerPath = "kotlinx/coroutines/test/internal/ReportingSupervisorJob.hpp",
                        namespaceSegments = listOf("kotlinx", "coroutines", "test", "internal"),
                    ),
            )
    }
}
