package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket
import io.github.kotlinmania.kotlincpp.packet.buildKotlinPacketStream

/**
 * Deterministic single-file Kotlin-to-C++23 transliterator (`Pass 1` through `Pass 6`).
 *
 * Parses [source] into a lossless [PacketStream], runs all 6 semantic/lowering passes
 * (`SameFileSymbolPass`, `SuspendStateMachinePass`, `GcAndOwnershipPass`,
 * `AtomicAndThreadLocalPass`, `FfiAndCTypePass`, `ControlFlowAndTypePass`), and emits
 * co-located `.hpp` + optional `.cpp` (+ optional `.h`/`.def` C-ABI companion files).
 */
public fun translateKotlinToCpp(
    source: String,
    sourcePath: String = "<memory>",
    config: CppTargetConfig = CppTargetConfig(),
): CppTranslationResult {
    val stream = buildKotlinPacketStream(source = source, sourcePath = sourcePath)
    val symbols = SameFileSymbolPass.run(stream = stream, config = config)
    val suspendPlan = SuspendStateMachinePass.run(stream = stream, symbols = symbols)
    val gcPlan = GcAndOwnershipPass.run(stream = stream, config = config, symbols = symbols, suspendPlan = suspendPlan)
    val atomicPlan = AtomicAndThreadLocalPass.run(stream = stream, config = config, symbols = symbols)
    val ffiPlan = FfiAndCTypePass.run(stream = stream, symbols = symbols, suspendPlan = suspendPlan)
    val cfPlan = ControlFlowAndTypePass.run(stream = stream, symbols = symbols)

    val headerIncludes = CppIncludeCollector(selfHeaderPath = symbols.headerRelativePath)
    val sourceIncludes = CppIncludeCollector(selfHeaderPath = "")
    for (imp in symbols.imports) {
        imp.mappedSystemHeader?.let { headerIncludes.addSystem(it) }
        imp.mappedProjectHeader?.let { hdr ->
            if (CppTypeTranslator.shouldDeferHeaderInclude(hdr, headerIncludes.selfHeaderPath)) {
                headerIncludes.addDeferredBottomProject(hdr)
            } else {
                headerIncludes.addProject(hdr)
            }
        }
    }

    val globalTypeParams = LinkedHashSet<String>()
    val sharedTypeParams = LinkedHashSet<String>()
    fun recordTypeParams(tps: List<KtTypeParamInfo>) {
        for (tp in tps) {
            globalTypeParams.add(tp.name)
            val ubIdx = tp.upperBoundFrameIndex
            if (ubIdx != null) {
                val ubSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[ubIdx])
                if (ubSimple != null && CppTypeTranslator.isSharedHierarchyType(ubSimple)) {
                    sharedTypeParams.add(tp.name)
                }
            }
        }
    }
    for (cls in symbols.classesByFrame.values) {
        recordTypeParams(cls.typeParams)
    }
    for (fn in symbols.functionsByFrame.values) {
        recordTypeParams(fn.typeParams)
    }
    for (prop in symbols.propertiesByFrame.values) {
        recordTypeParams(prop.typeParams)
    }
    for (ta in symbols.typeAliasesByFrame.values) {
        recordTypeParams(ta.typeParams)
    }

    val localClassNames = LinkedHashSet<String>()
    val localClassTypeParams = LinkedHashMap<String, List<String>>()
    for (cls in symbols.classesByFrame.values) {
        if (!FrameAstHelpers.hasModifierKeyword(stream, stream.frames[cls.frameIndex], KtTokens.EXPECT_MODIFIER_ID)) {
            localClassNames.add(cls.kotlinName)
        }
        if (cls.typeParams.isNotEmpty()) {
            localClassTypeParams[cls.kotlinName] = cls.typeParams.map { it.name }
        }
    }

    val headerTypeTranslator =
        CppTypeTranslator(
            stream = stream,
            config = config,
            includes = headerIncludes,
            polymorphicTypeNames = symbols.polymorphicTypeNames,
            typeParamNames = globalTypeParams,
            currentNamespaceSegments = symbols.namespaceSegments,
            localClassNames = localClassNames,
            localClassTypeParams = localClassTypeParams,
            sharedTypeParamNames = sharedTypeParams,
            sharedHierarchyTypeNames = symbols.sharedHierarchyTypeNames,
        )
    val sourceTypeTranslator =
        CppTypeTranslator(
            stream = stream,
            config = config,
            includes = sourceIncludes,
            polymorphicTypeNames = symbols.polymorphicTypeNames,
            typeParamNames = globalTypeParams,
            currentNamespaceSegments = symbols.namespaceSegments,
            localClassNames = localClassNames,
            localClassTypeParams = localClassTypeParams,
            sharedTypeParamNames = sharedTypeParams,
            sharedHierarchyTypeNames = symbols.sharedHierarchyTypeNames,
        )

    val knownFnNames = LinkedHashSet<String>()
    for (fn in symbols.functionsByFrame.values) {
        knownFnNames.add(fn.kotlinName)
    }

    val headerBundle =
        LoweringBundle(
            stream = stream,
            config = config,
            symbols = symbols,
            suspendPlan = suspendPlan,
            gcPlan = gcPlan,
            atomicPlan = atomicPlan,
            ffiPlan = ffiPlan,
            cfPlan = cfPlan,
            includes = headerIncludes,
            typeTranslator = headerTypeTranslator,
            knownFunctionNames = knownFnNames,
        )
    val sourceBundle =
        LoweringBundle(
            stream = stream,
            config = config,
            symbols = symbols,
            suspendPlan = suspendPlan,
            gcPlan = gcPlan,
            atomicPlan = atomicPlan,
            ffiPlan = ffiPlan,
            cfPlan = cfPlan,
            includes = sourceIncludes,
            typeTranslator = sourceTypeTranslator,
            knownFunctionNames = knownFnNames,
        )

    val headerRenderer = CppDeclarationRenderer(headerBundle, isHeaderMode = true)
    val headerBodyText = headerRenderer.renderBodySection()
    val sourceBodyText = CppDeclarationRenderer(sourceBundle, isHeaderMode = false).renderBodySection()

    val fullHeaderContent = assembleHeaderFile(symbols, ffiPlan, headerIncludes, headerBodyText, headerRenderer.postIncludeHeaderBodies)
    val headerArtifact = CppFileArtifact(relativePath = symbols.headerRelativePath, content = fullHeaderContent)

    val sourceArtifact =
        if (sourceBodyText.isNotBlank()) {
            val fullSourceContent = assembleSourceFile(symbols, sourceIncludes, sourceBodyText)
            CppFileArtifact(relativePath = symbols.sourceRelativePath, content = fullSourceContent)
        } else {
            null
        }

    val companions =
        if (config.emitCompanionCAbiFiles && ffiPlan.cAbiExportFunctionFrames.isNotEmpty()) {
            emitCompanionCAbiArtifacts(headerBundle)
        } else {
            emptyList()
        }

    val declCount = symbols.topLevelDeclarationFrames.size
    val summary =
        buildString {
            append("translated ")
            append(declCount)
            append(" declaration(s) -> ")
            append(symbols.headerRelativePath)
            if (sourceArtifact != null) {
                append(" + ")
                append(symbols.sourceRelativePath)
            }
        }

    return CppTranslationResult(
        headerArtifact = headerArtifact,
        sourceArtifact = sourceArtifact,
        companionArtifacts = companions,
        translatedDeclarationCount = declCount,
        hoistedSuspendArgCount = suspendPlan.totalHoistedSuspendArgs,
        inlineExpandedCallCount = suspendPlan.inlineExpansionsByCallFrame.size,
        summary = summary,
    )
}

private fun assembleHeaderFile(
    symbols: SameFileSymbolTable,
    ffiPlan: FfiAndCTypePlan,
    includes: CppIncludeCollector,
    bodyText: String,
    postIncludeBodies: List<String> = emptyList(),
): String =
    buildString {
        append("#pragma once\n")
        if (symbols.portLintDirectiveKind != null && symbols.portLintSourcePath != null) {
            append("// port-lint: ")
                .append(symbols.portLintDirectiveKind)
                .append(' ')
                .append(symbols.portLintSourcePath)
                .append('\n')
        }

        ensureHelperPreambleHeaders(includes)
        for (sysHdr in includes.sortedSystemHeaders()) {
            if (sysHdr == "CoreFoundation/CoreFoundation.h" || sysHdr == "dispatch/dispatch.h") {
                append("#if defined(__APPLE__)\n#include <").append(sysHdr).append(">\n#endif\n")
            } else {
                append("#include <").append(sysHdr).append(">\n")
            }
        }
        for (projHdr in includes.sortedProjectHeaders()) {
            append("#include \"").append(projHdr).append("\"\n")
        }

        for (preamble in ffiPlan.preambleInlineCppBlocks) {
            append('\n').append(preamble.trimIndent()).append('\n')
        }
        emitSharedHelperPreamble(includes, this)

        val nsJoined = symbols.namespaceSegments.joinToString("::")
        if (nsJoined.isNotEmpty()) {
            append("\nnamespace ").append(nsJoined).append(" {\n\n")
        } else {
            append('\n')
        }

        if (symbols.imports.any { it.isWildcard && it.fqName == "kotlin.test" } && nsJoined != "kotlin::test") {
            append("using namespace kotlin::test;\n\n")
        }

        if (includes.needsCollectionAlias) {
            append("template <typename E>\nusing Collection = std::vector<E>;\n\n")
        }
        if (includes.needsSequenceAlias) {
            append("template <typename E>\nusing Sequence = KxsSequence<E>;\n\n")
        }
        if (includes.needsThreadLocalDef) {
            append("template <typename T>\nusing ThreadLocal = KxsThreadLocal<T>;\n\n")
        }
        if (includes.extraForwardDeclarations.isNotEmpty()) {
            for (fwd in includes.extraForwardDeclarations) {
                append(fwd).append('\n')
            }
            append('\n')
        }

        append(bodyText.trimEnd()).append('\n')

        if (nsJoined.isNotEmpty()) {
            append("\n} // namespace ").append(nsJoined).append('\n')
        }

        if (symbols.headerRelativePath == "kotlinx/coroutines/internal/Symbol.hpp") {
            append(
                """

                template <>
                struct std::hash<kotlinx::coroutines::internal::Symbol> {
                    std::size_t operator()(const kotlinx::coroutines::internal::Symbol& s) const noexcept {
                        return std::hash<std::string>{}(s.symbol);
                    }
                };
                """.trimIndent(),
            ).append('\n')
        }

        val deferredBottom = includes.sortedDeferredBottomProjectHeaders()
        if (deferredBottom.isNotEmpty()) {
            append('\n')
            for (btmHdr in deferredBottom) {
                append("#if __has_include(\"").append(btmHdr).append("\")\n")
                append("#include \"").append(btmHdr).append("\"\n")
                append("#endif\n")
            }
        }
        if (includes.hasExpectDeclarations) {
            if (symbols.headerRelativePath.endsWith(".common.hpp")) {
                append('\n')
                when (symbols.headerRelativePath) {
                    "kotlinx/coroutines/MultithreadedDispatchers.common.hpp" -> {
                        append("#if __has_include(\"kotlinx/coroutines/MultithreadedDispatchers.hpp\")\n")
                        append("#include \"kotlinx/coroutines/MultithreadedDispatchers.hpp\"\n")
                        append("#endif\n")
                    }
                    "kotlinx/coroutines/CoroutineContext.common.hpp" -> {
                        append("#if __has_include(\"kotlinx/coroutines/native/CoroutineContext.hpp\")\n")
                        append("#include \"kotlinx/coroutines/native/CoroutineContext.hpp\"\n")
                        append("#endif\n")
                    }
                    else -> {
                        val actualHdr = symbols.headerRelativePath.removeSuffix(".common.hpp") + ".hpp"
                        append("#if __has_include(\"").append(actualHdr).append("\")\n")
                        append("#include \"").append(actualHdr).append("\"\n")
                        append("#endif\n")
                    }
                }
            } else if (
                symbols.headerRelativePath == "kotlinx/coroutines/Dispatchers.hpp" ||
                symbols.headerRelativePath == "kotlinx/coroutines/native/CoroutineContext.hpp"
            ) {
                append('\n')
                append("#if defined(__APPLE__) && __has_include(\"kotlinx/coroutines/nativeDarwin/Dispatchers.hpp\")\n")
                append("#include \"kotlinx/coroutines/nativeDarwin/Dispatchers.hpp\"\n")
                append("#elif __has_include(\"kotlinx/coroutines/nativeOther/Dispatchers.hpp\")\n")
                append("#include \"kotlinx/coroutines/nativeOther/Dispatchers.hpp\"\n")
                append("#endif\n")
            }
        }
        if (postIncludeBodies.isNotEmpty()) {
            append('\n')
            if (nsJoined.isNotEmpty()) {
                append("namespace ").append(nsJoined).append(" {\n\n")
            }
            for ((idx, b) in postIncludeBodies.withIndex()) {
                if (idx > 0) append('\n')
                append(b.trimEnd()).append('\n')
            }
            if (nsJoined.isNotEmpty()) {
                append("\n} // namespace ").append(nsJoined).append('\n')
            }
        }
    }

private val CPP_SOURCE_WILDCARD_NAMESPACES: Set<String> =
    setOf(
        "kotlin.test",
        "kotlinx.coroutines",
        "kotlinx.coroutines.internal",
        "kotlinx.coroutines.intrinsics",
        "kotlinx.coroutines.selects",
        "kotlinx.coroutines.channels",
        "kotlinx.coroutines.flow",
        "kotlinx.coroutines.flow.internal",
        "kotlinx.coroutines.sync",
        "kotlinx.coroutines.testing",
        "kotlinx.coroutines.test",
        "kotlinx.coroutines.test.internal",
    )

private fun assembleSourceFile(
    symbols: SameFileSymbolTable,
    includes: CppIncludeCollector,
    bodyText: String,
): String =
    buildString {
        if (symbols.portLintDirectiveKind != null && symbols.portLintSourcePath != null) {
            append("// port-lint: ")
                .append(symbols.portLintDirectiveKind)
                .append(' ')
                .append(symbols.portLintSourcePath)
                .append('\n')
        }
        append("#include \"").append(symbols.headerRelativePath).append("\"\n")
        ensureHelperPreambleHeaders(includes)
        for (sysHdr in includes.sortedSystemHeaders()) {
            if (sysHdr == "CoreFoundation/CoreFoundation.h" || sysHdr == "dispatch/dispatch.h") {
                append("#if defined(__APPLE__)\n#include <").append(sysHdr).append(">\n#endif\n")
            } else {
                append("#include <").append(sysHdr).append(">\n")
            }
        }
        for (projHdr in includes.sortedProjectHeaders()) {
            if (projHdr != symbols.headerRelativePath) {
                append("#include \"").append(projHdr).append("\"\n")
            }
        }
        emitSharedHelperPreamble(includes, this)

        val nsJoined = symbols.namespaceSegments.joinToString("::")
        if (nsJoined.isNotEmpty()) {
            append("\nnamespace ").append(nsJoined).append(" {\n\n")
        } else {
            append('\n')
        }

        val wildcardUsingNamespaces =
            symbols.imports
                .asSequence()
                .filter { it.isWildcard && it.fqName in CPP_SOURCE_WILDCARD_NAMESPACES && it.fqName != symbols.packageName }
                .map { imp ->
                    imp.fqName
                        .split('.')
                        .joinToString("::") { CppNamingPolicy.toNamespaceSegment(it) }
                }.distinct()
                .toList()
        if (wildcardUsingNamespaces.isNotEmpty()) {
            for (uNs in wildcardUsingNamespaces) {
                append("using namespace ").append(uNs).append(";\n")
            }
            append('\n')
        }

        if (includes.needsCollectionAlias) {
            append("template <typename E>\nusing Collection = std::vector<E>;\n\n")
        }
        if (includes.needsSequenceAlias) {
            append("template <typename E>\nusing Sequence = KxsSequence<E>;\n\n")
        }
        if (includes.needsThreadLocalDef) {
            append("template <typename T>\nusing ThreadLocal = KxsThreadLocal<T>;\n\n")
        }
        if (includes.extraForwardDeclarations.isNotEmpty()) {
            for (fwd in includes.extraForwardDeclarations) {
                append(fwd).append('\n')
            }
            append('\n')
        }

        append(bodyText.trimEnd()).append('\n')

        if (nsJoined.isNotEmpty()) {
            append("\n} // namespace ").append(nsJoined).append('\n')
        }
    }

private fun ensureHelperPreambleHeaders(includes: CppIncludeCollector) {
    if (includes.needsSequenceAlias) {
        includes.addSystem("any")
        includes.addSystem("vector")
    }
    if (includes.needsIntRangeDef) {
        includes.addSystem("cstdint")
    }
    if (includes.needsTrimIndentDef) {
        includes.addSystem("algorithm")
        includes.addSystem("sstream")
        includes.addSystem("string")
        includes.addSystem("vector")
    }
    if (includes.needsContextOpsDef) {
        includes.addSystem("concepts")
        includes.addSystem("memory")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
        includes.addProject("kotlinx/coroutines/CoroutineContext.hpp")
    }
    if (includes.needsCastHelpersDef) {
        includes.addSystem("any")
        includes.addSystem("cstddef")
        includes.addSystem("memory")
        includes.addSystem("optional")
        includes.addSystem("stdexcept")
        includes.addSystem("type_traits")
        includes.addSystem("typeinfo")
        includes.addSystem("utility")
    }
    if (includes.needsTimeMarkOpsDef) {
        includes.addSystem("compare")
    }
    if (includes.needsNullOperatorsDef) {
        includes.addSystem("any")
        includes.addSystem("concepts")
        includes.addSystem("cstddef")
        includes.addSystem("optional")
    }
    if (includes.needsSynchronizedObjectDef) {
        includes.addSystem("mutex")
        includes.addSystem("utility")
    }
    if (includes.needsArrayDequeDef) {
        includes.addSystem("deque")
        includes.addSystem("optional")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
    }
}

private fun emitSharedHelperPreamble(
    includes: CppIncludeCollector,
    out: StringBuilder,
) {
    if (includes.rootForwardDeclarations.isNotEmpty()) {
        out.append('\n')
        for (fwd in includes.rootForwardDeclarations) {
            out.append(fwd).append('\n')
        }
    }
    if (includes.needsSequenceAlias) {
        out.append(
            """

            #ifndef KXS_SEQUENCE_DEFINED
            #define KXS_SEQUENCE_DEFINED
            template <typename E>
            struct KxsSequence : public std::vector<E> {
                using std::vector<E>::vector;
            };
            template <typename E = std::any>
            inline KxsSequence<E> empty_sequence() { return {}; }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsIntRangeDef) {
        out.append(
            """

            #ifndef KXS_INT_RANGE_DEFINED
            #define KXS_INT_RANGE_DEFINED
            template <typename T>
            struct KxsIntRange {
                T start_{};
                T end_inclusive_{};
                struct iterator {
                    T cur_;
                    constexpr T operator*() const noexcept { return cur_; }
                    constexpr iterator& operator++() noexcept { ++cur_; return *this; }
                    constexpr bool operator!=(const iterator& other) const noexcept { return cur_ != other.cur_; }
                };
                constexpr iterator begin() const noexcept { return iterator{start_}; }
                constexpr iterator end() const noexcept {
                    return start_ <= end_inclusive_ ? iterator{static_cast<T>(end_inclusive_ + 1)} : iterator{start_};
                }
                constexpr bool contains(T value) const noexcept { return start_ <= value && value <= end_inclusive_; }
                constexpr bool is_empty() const noexcept { return start_ > end_inclusive_; }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsTrimIndentDef) {
        out.append(
            """

            #ifndef KXS_TRIM_INDENT_DEFINED
            #define KXS_TRIM_INDENT_DEFINED
            inline std::string kxs_trim_indent(const std::string& s) {
                std::vector<std::string> lines;
                std::istringstream iss(s);
                std::string line;
                while (std::getline(iss, line)) {
                    lines.push_back(line);
                }
                while (!lines.empty() && lines.front().find_first_not_of(" \t\r") == std::string::npos) {
                    lines.erase(lines.begin());
                }
                while (!lines.empty() && lines.back().find_first_not_of(" \t\r") == std::string::npos) {
                    lines.pop_back();
                }
                std::size_t min_indent = std::string::npos;
                for (const auto& l : lines) {
                    const auto first_non_ws = l.find_first_not_of(" \t");
                    if (first_non_ws != std::string::npos) {
                        min_indent = std::min(min_indent, first_non_ws);
                    }
                }
                if (min_indent == std::string::npos) min_indent = 0;
                std::string result;
                for (std::size_t i = 0; i < lines.size(); ++i) {
                    if (i > 0) result += '\n';
                    if (lines[i].find_first_not_of(" \t\r") != std::string::npos && lines[i].size() > min_indent) {
                        result += lines[i].substr(min_indent);
                    } else if (lines[i].find_first_not_of(" \t\r") != std::string::npos) {
                        result += lines[i];
                    }
                }
                return result;
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsContextOpsDef && includes.selfHeaderPath != "kotlinx/coroutines/CoroutineContext.hpp") {
        out.append(
            """

            #ifndef KXS_CONTEXT_OPS_DEFINED
            #define KXS_CONTEXT_OPS_DEFINED
            namespace kotlin::coroutines {
            template <typename KxsL, typename KxsR>
                requires (std::derived_from<std::remove_cv_t<KxsL>, CoroutineContext> &&
                          std::derived_from<std::remove_cv_t<KxsR>, CoroutineContext>)
            inline std::shared_ptr<CoroutineContext> operator+(const std::shared_ptr<KxsL>& lhs, const std::shared_ptr<KxsR>& rhs) {
                if (!lhs) return rhs;
                if (!rhs) return lhs;
                return lhs->operator+(std::static_pointer_cast<CoroutineContext>(rhs));
            }
            template <typename KxsElem>
            inline std::shared_ptr<KxsElem> kxs_cast_ctx_elem(
                const CoroutineContext::KeyTyped<KxsElem>*,
                std::shared_ptr<CoroutineContext::Element> elem
            ) {
                return std::dynamic_pointer_cast<KxsElem>(std::move(elem));
            }
            inline std::shared_ptr<CoroutineContext::Element> kxs_cast_ctx_elem(
                const void*,
                std::shared_ptr<CoroutineContext::Element> elem
            ) {
                return elem;
            }
            template <typename KxsCtx, typename KxsKey>
            inline auto kxs_ctx_get(KxsCtx&& ctx, KxsKey&& key) {
                using CtxD = std::remove_cvref_t<KxsCtx>;
                using KeyD = std::remove_cvref_t<KxsKey>;
                const auto* key_raw_ptr = [&]() {
                    if constexpr (std::is_pointer_v<KeyD>) return key;
                    else return std::addressof(key);
                }();
                auto* base_key = const_cast<CoroutineContext::Key*>(static_cast<const CoroutineContext::Key*>(key_raw_ptr));
                std::shared_ptr<CoroutineContext::Element> elem;
                if constexpr (requires { ctx.get(); typename CtxD::element_type; }) {
                    if (ctx) elem = ctx->get(base_key);
                } else if constexpr (std::is_pointer_v<CtxD>) {
                    if (ctx) elem = ctx->get(base_key);
                } else {
                    elem = ctx.get(base_key);
                }
                return kxs_cast_ctx_elem(key_raw_ptr, std::move(elem));
            }
            } // namespace kotlin::coroutines
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsSynchronizedObjectDef) {
        out.append(
            """

            #ifndef KXS_SYNCHRONIZED_OBJECT_DEFINED
            #define KXS_SYNCHRONIZED_OBJECT_DEFINED
            struct KxsSynchronizedObject {
                mutable std::recursive_mutex kxs_sync_mutex_{};
                KxsSynchronizedObject() = default;
                KxsSynchronizedObject(const KxsSynchronizedObject&) noexcept {}
                KxsSynchronizedObject& operator=(const KxsSynchronizedObject&) noexcept { return *this; }
                virtual ~KxsSynchronizedObject() = default;
                void lock() const { kxs_sync_mutex_.lock(); }
                void unlock() const { kxs_sync_mutex_.unlock(); }
                bool try_lock() const { return kxs_sync_mutex_.try_lock(); }
                template <typename KxsFn>
                decltype(auto) with_lock(KxsFn&& fn) const {
                    std::lock_guard<std::recursive_mutex> lk(kxs_sync_mutex_);
                    return std::forward<KxsFn>(fn)();
                }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsAutoCloseableDef) {
        out.append(
            """

            #ifndef KXS_AUTOCLOSEABLE_DEFINED
            #define KXS_AUTOCLOSEABLE_DEFINED
            class AutoCloseable {
            public:
                virtual ~AutoCloseable() = default;
                virtual void close() = 0;
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsArrayDequeDef) {
        out.append(
            """

            #ifndef KXS_ARRAY_DEQUE_DEFINED
            #define KXS_ARRAY_DEQUE_DEFINED
            template <typename T>
            class KxsArrayDeque {
            private:
                mutable std::deque<T> dq_{};
            public:
                KxsArrayDeque() = default;
                explicit KxsArrayDeque(std::int32_t) {}
                void add_last(T element) const { dq_.push_back(std::move(element)); }
                void add_first(T element) const { dq_.push_front(std::move(element)); }
                bool add(T element) const { dq_.push_back(std::move(element)); return true; }
                auto remove_first_or_null() const {
                    if constexpr (std::is_pointer_v<T> || requires(T v) { v == nullptr; }) {
                        if (dq_.empty()) return T{};
                        T v = std::move(dq_.front());
                        dq_.pop_front();
                        return v;
                    } else {
                        if (dq_.empty()) return std::optional<T>{};
                        T v = std::move(dq_.front());
                        dq_.pop_front();
                        return std::optional<T>{std::move(v)};
                    }
                }
                auto remove_last_or_null() const {
                    if constexpr (std::is_pointer_v<T> || requires(T v) { v == nullptr; }) {
                        if (dq_.empty()) return T{};
                        T v = std::move(dq_.back());
                        dq_.pop_back();
                        return v;
                    } else {
                        if (dq_.empty()) return std::optional<T>{};
                        T v = std::move(dq_.back());
                        dq_.pop_back();
                        return std::optional<T>{std::move(v)};
                    }
                }
                T remove_first() const {
                    T v = std::move(dq_.front());
                    dq_.pop_front();
                    return v;
                }
                bool is_empty() const noexcept { return dq_.empty(); }
                bool empty() const noexcept { return dq_.empty(); }
                std::int32_t size() const noexcept { return static_cast<std::int32_t>(dq_.size()); }
                void clear() const noexcept { dq_.clear(); }
                auto begin() noexcept { return dq_.begin(); }
                auto end() noexcept { return dq_.end(); }
                auto begin() const noexcept { return dq_.begin(); }
                auto end() const noexcept { return dq_.end(); }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsThreadLocalDef) {
        out.append(
            """

            #ifndef KXS_THREAD_LOCAL_DEFINED
            #define KXS_THREAD_LOCAL_DEFINED
            template <typename T>
            class KxsThreadLocal {
            private:
                static std::unordered_map<const KxsThreadLocal<T>*, T>& map() {
                    thread_local std::unordered_map<const KxsThreadLocal<T>*, T> tls{};
                    return tls;
                }
            public:
                KxsThreadLocal() = default;
                T get() const {
                    auto& m = map();
                    auto it = m.find(this);
                    return it == m.end() ? T{} : it->second;
                }
                void set(T value) const {
                    map()[this] = std::move(value);
                }
                void remove() const {
                    map().erase(this);
                }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsAtomicReferenceDef) {
        out.append(
            """

            #ifndef KXS_ATOMIC_REFERENCE_DEFINED
            #define KXS_ATOMIC_REFERENCE_DEFINED
            template <typename V>
            class KxsAtomicReference {
            private:
                mutable std::mutex mtx_{};
                V val_{};
                static bool kxs_eq(const V& a, const V& b) {
                    if constexpr (requires { a == b; }) {
                        return a == b;
                    } else {
                        return !a.has_value() && !b.has_value();
                    }
                }
            public:
                KxsAtomicReference() = default;
                explicit KxsAtomicReference(V v) : val_(std::move(v)) {}
                KxsAtomicReference(const KxsAtomicReference& o) : val_(o.get_value()) {}
                KxsAtomicReference& operator=(const KxsAtomicReference& o) {
                    if (this != &o) set_value(o.get_value());
                    return *this;
                }
                V get_value() const {
                    std::lock_guard<std::mutex> lk(mtx_);
                    return val_;
                }
                void set_value(V v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    val_ = std::move(v);
                }
                V get_and_set(V v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    V old = std::move(val_);
                    val_ = std::move(v);
                    return old;
                }
                bool compare_and_set(const V& expected, V desired) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_eq(val_, expected)) {
                        val_ = std::move(desired);
                        return true;
                    }
                    return false;
                }
                V load(std::memory_order = std::memory_order_seq_cst) const { return get_value(); }
                void store(V v, std::memory_order = std::memory_order_seq_cst) { set_value(std::move(v)); }
                V exchange(V v, std::memory_order = std::memory_order_seq_cst) { return get_and_set(std::move(v)); }
                bool compare_exchange_strong(
                    V& expected,
                    V desired,
                    std::memory_order = std::memory_order_seq_cst,
                    std::memory_order = std::memory_order_seq_cst
                ) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_eq(val_, expected)) {
                        val_ = std::move(desired);
                        return true;
                    }
                    expected = val_;
                    return false;
                }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsSharedFromThisDef) {
        out.append(
            """

            #ifndef KXS_SHARED_FROM_THIS_DEFINED
            #define KXS_SHARED_FROM_THIS_DEFINED
            template <typename KxsSelf>
            inline std::shared_ptr<std::remove_cv_t<KxsSelf>> kxs_shared_from_this(KxsSelf* self) {
                using KxsClean = std::remove_cv_t<KxsSelf>;
                auto* mut_self = const_cast<KxsClean*>(self);
                if constexpr (requires { mut_self->CoroutineContext::weak_from_this(); }) {
                    if (auto sp = mut_self->CoroutineContext::weak_from_this().lock()) {
                        return std::dynamic_pointer_cast<KxsClean>(sp);
                    }
                } else if constexpr (requires { mut_self->weak_from_this(); }) {
                    if (auto sp = mut_self->weak_from_this().lock()) {
                        return std::dynamic_pointer_cast<KxsClean>(sp);
                    }
                }
                return std::shared_ptr<KxsClean>(mut_self, [](KxsClean*) noexcept {});
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsTimeMarkOpsDef) {
        out.append(
            """

            #ifndef KXS_TIME_MARK_OPS_DEFINED
            #define KXS_TIME_MARK_OPS_DEFINED
            namespace kotlin::time {
            constexpr auto operator<=>(Duration a, Duration b) noexcept {
                return a.compare_to(b) <=> 0;
            }
            constexpr bool operator==(Duration a, Duration b) noexcept {
                return a.compare_to(b) == 0;
            }
            #ifdef KOTLIN_TIME_TIME_SOURCE_HPP_
            inline Duration operator-(const ValueTimeMark& a, const ValueTimeMark& b) {
                return a.minus(b);
            }
            inline ValueTimeMark operator+(const ValueTimeMark& m, Duration d) {
                auto* p = const_cast<ValueTimeMark&>(m).plus(d);
                auto v = *p;
                delete p;
                return v;
            }
            inline ValueTimeMark operator-(const ValueTimeMark& m, Duration d) {
                auto* p = const_cast<ValueTimeMark&>(m).minus(d);
                auto v = *p;
                delete p;
                return v;
            }
            #endif
            } // namespace kotlin::time
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsDarwinDispatchHelpers) {
        out.append(
            """

            #if defined(__APPLE__) && !defined(KXS_DARWIN_DISPATCH_HELPERS_DEFINED)
            #define KXS_DARWIN_DISPATCH_HELPERS_DEFINED
            template <typename KxsFn>
            inline void kxs_dispatch_async(dispatch_queue_t queue, KxsFn&& fn) {
                using FnDecayed = std::decay_t<KxsFn>;
                auto* heap_fn = new FnDecayed(std::forward<KxsFn>(fn));
                dispatch_async_f(queue, heap_fn, [](void* ctx) {
                    auto* f = static_cast<FnDecayed*>(ctx);
                    (*f)();
                    delete f;
                });
            }
            template <typename KxsFn>
            inline CFRunLoopTimerRef kxs_cf_timer_create_with_handler(
                CFAllocatorRef allocator,
                CFAbsoluteTime fire_date,
                CFTimeInterval interval,
                CFOptionFlags flags,
                CFIndex order,
                KxsFn&& fn
            ) {
                using FnDecayed = std::decay_t<KxsFn>;
                auto* heap_fn = new FnDecayed(std::forward<KxsFn>(fn));
                CFRunLoopTimerContext ctx{};
                ctx.version = 0;
                ctx.info = heap_fn;
                ctx.release = [](const void* info) {
                    delete static_cast<const FnDecayed*>(info);
                };
                return CFRunLoopTimerCreate(
                    allocator,
                    fire_date,
                    interval,
                    flags,
                    order,
                    [](CFRunLoopTimerRef timer, void* info) {
                        (*static_cast<FnDecayed*>(info))(timer);
                    },
                    &ctx
                );
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsKotlinIteratorDefs) {
        out.append(
            """

            #ifndef KXS_KOTLIN_ITERATOR_DEFS_DEFINED
            #define KXS_KOTLIN_ITERATOR_DEFS_DEFINED
            template <typename T>
            class Iterator {
            public:
                virtual ~Iterator() = default;
                virtual bool has_next() const = 0;
                virtual T next() = 0;
            };
            template <typename T>
            class MutableIterator : public virtual Iterator<T> {
            public:
                virtual void remove() = 0;
            };
            template <typename T>
            class ListIterator : public virtual Iterator<T> {
            public:
                virtual bool has_previous() const = 0;
                virtual T previous() = 0;
                virtual std::int32_t next_index() const = 0;
                virtual std::int32_t previous_index() const = 0;
            };
            template <typename T>
            class MutableListIterator : public virtual ListIterator<T>, public virtual MutableIterator<T> {
            public:
                virtual void set(T element) = 0;
                virtual void add(T element) = 0;
            };
            template <typename E>
            class AbstractList {
            public:
                virtual ~AbstractList() = default;
                virtual std::int32_t size() const = 0;
                virtual E get(std::int32_t index) const = 0;
                bool is_empty() const { return size() == 0; }
            };
            template <typename E>
            class AbstractMutableList : public AbstractList<E> {
            public:
                virtual bool add(E element) = 0;
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsNullOperatorsDef) {
        out.append(
            """

            #ifndef KXS_NULL_OPERATORS_DEFINED
            #define KXS_NULL_OPERATORS_DEFINED
            template <typename KxsAny>
                requires std::same_as<KxsAny, std::any>
            inline bool operator==(const KxsAny& a, std::nullptr_t) noexcept {
                return !a.has_value() || a.type() == typeid(std::nullptr_t);
            }
            template <typename KxsOptT>
            constexpr bool operator==(const std::optional<KxsOptT>& o, std::nullptr_t) noexcept {
                return !o.has_value();
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsCastHelpersDef) {
        out.append(
            """

            #ifndef KXS_CAST_HELPERS_DEFINED
            #define KXS_CAST_HELPERS_DEFINED
            template <typename T> struct kxs_is_shared_ptr : std::false_type {};
            template <typename T> struct kxs_is_shared_ptr<std::shared_ptr<T>> : std::true_type {};
            template <typename T> inline constexpr bool kxs_is_shared_ptr_v = kxs_is_shared_ptr<std::remove_cvref_t<T>>::value;
            template <typename T> struct kxs_is_optional : std::false_type {};
            template <typename T> struct kxs_is_optional<std::optional<T>> : std::true_type {};
            template <typename T> inline constexpr bool kxs_is_optional_v = kxs_is_optional<std::remove_cvref_t<T>>::value;
            template <typename T> struct kxs_pointee { using type = T; };
            template <typename T> struct kxs_pointee<std::shared_ptr<T>> { using type = T; };
            template <typename T> struct kxs_pointee<T*> { using type = T; };
            template <typename T> using kxs_pointee_t = std::remove_cv_t<typename kxs_pointee<std::remove_cvref_t<T>>::type>;

            template <typename KxsTarget, typename KxsSrc>
            inline bool kxs_is_instance(const KxsSrc& v) {
                using TargetD = std::remove_cvref_t<KxsTarget>;
                using Pointee = kxs_pointee_t<TargetD>;
                using SrcD = std::remove_cvref_t<KxsSrc>;
                if constexpr (std::is_same_v<SrcD, std::nullptr_t>) {
                    return false;
                } else if constexpr (std::is_same_v<SrcD, std::any>) {
                    if constexpr (!std::is_abstract_v<TargetD>) {
                        if (std::any_cast<const TargetD>(std::addressof(v)) != nullptr) return true;
                    }
                    if constexpr (!std::is_same_v<Pointee, TargetD> && !std::is_abstract_v<Pointee>) {
                        if (std::any_cast<const Pointee>(std::addressof(v)) != nullptr) return true;
                    }
                    return false;
                } else if constexpr (kxs_is_optional_v<SrcD>) {
                    return v.has_value() && kxs_is_instance<KxsTarget>(*v);
                } else if constexpr (kxs_is_shared_ptr_v<SrcD>) {
                    if (!v) return false;
                    using SrcEl = std::remove_cv_t<typename SrcD::element_type>;
                    if constexpr (std::is_same_v<Pointee, void>) return true;
                    else if constexpr (std::is_base_of_v<Pointee, SrcEl> || std::is_same_v<Pointee, SrcEl>) return true;
                    else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<Pointee>) return dynamic_cast<const Pointee*>(v.get()) != nullptr;
                    else return false;
                } else if constexpr (std::is_pointer_v<SrcD>) {
                    if (!v) return false;
                    using SrcEl = std::remove_cv_t<std::remove_pointer_t<SrcD>>;
                    if constexpr (std::is_same_v<Pointee, void>) return true;
                    else if constexpr (std::is_base_of_v<Pointee, SrcEl> || std::is_same_v<Pointee, SrcEl>) return true;
                    else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<Pointee>) return dynamic_cast<const Pointee*>(v) != nullptr;
                    else return false;
                } else {
                    if constexpr (std::is_same_v<Pointee, void>) return true;
                    else if constexpr (std::is_base_of_v<Pointee, SrcD> || std::is_same_v<Pointee, SrcD>) return true;
                    else if constexpr (std::is_polymorphic_v<SrcD> && std::is_polymorphic_v<Pointee>) return dynamic_cast<const Pointee*>(std::addressof(v)) != nullptr;
                    else return false;
                }
            }

            template <typename KxsTarget, typename KxsSrc>
            inline KxsTarget kxs_safe_as_cast(KxsSrc&& v) {
                using TargetD = std::remove_cvref_t<KxsTarget>;
                using SrcD = std::remove_cvref_t<KxsSrc>;
                if constexpr (std::is_abstract_v<TargetD>) {
                    static_assert(!std::is_abstract_v<TargetD>, "abstract target must use shared_ptr or pointer");
                } else if constexpr (std::is_same_v<SrcD, std::nullptr_t>) {
                    return TargetD{};
                } else if constexpr (std::is_same_v<SrcD, std::any>) {
                    if (auto* p = std::any_cast<const TargetD>(std::addressof(v))) return *p;
                    return TargetD{};
                } else if constexpr (kxs_is_optional_v<SrcD>) {
                    if (!v.has_value()) return TargetD{};
                    return kxs_safe_as_cast<KxsTarget>(*v);
                } else if constexpr (kxs_is_shared_ptr_v<SrcD>) {
                    using SrcEl = typename SrcD::element_type;
                    if constexpr (kxs_is_shared_ptr_v<TargetD>) {
                        using DstEl = typename TargetD::element_type;
                        if constexpr (std::is_convertible_v<SrcEl*, DstEl*>) {
                            return std::static_pointer_cast<DstEl>(std::forward<KxsSrc>(v));
                        } else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) {
                            return std::dynamic_pointer_cast<DstEl>(std::forward<KxsSrc>(v));
                        } else {
                            return TargetD{};
                        }
                    } else if constexpr (std::is_pointer_v<TargetD>) {
                        using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                        if constexpr (std::is_same_v<DstEl, void>) return static_cast<TargetD>(const_cast<std::remove_cv_t<SrcEl>*>(v.get()));
                        else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) return dynamic_cast<TargetD>(const_cast<std::remove_cv_t<SrcEl>*>(v.get()));
                        else return TargetD{};
                    } else {
                        return TargetD{};
                    }
                } else if constexpr (std::is_pointer_v<SrcD>) {
                    using SrcEl = std::remove_cv_t<std::remove_pointer_t<SrcD>>;
                    if (!v) return TargetD{};
                    if constexpr (std::is_pointer_v<TargetD>) {
                        using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                        if constexpr (std::is_same_v<DstEl, void>) return static_cast<TargetD>(const_cast<SrcEl*>(v));
                        else if constexpr (std::is_same_v<SrcEl, void>) return static_cast<TargetD>(const_cast<void*>(v));
                        else if constexpr (std::is_convertible_v<SrcEl*, DstEl*>) return static_cast<TargetD>(const_cast<SrcEl*>(v));
                        else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) return dynamic_cast<TargetD>(const_cast<SrcEl*>(v));
                        else return TargetD{};
                    } else if constexpr (kxs_is_shared_ptr_v<TargetD>) {
                        using DstEl = typename TargetD::element_type;
                        if constexpr (std::is_convertible_v<SrcEl*, DstEl*>) {
                            return std::shared_ptr<DstEl>(std::shared_ptr<void>{}, const_cast<DstEl*>(static_cast<const DstEl*>(v)));
                        } else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) {
                            if (auto* d = dynamic_cast<DstEl*>(const_cast<SrcEl*>(v))) {
                                return std::shared_ptr<DstEl>(std::shared_ptr<void>{}, d);
                            }
                            return TargetD{};
                        } else {
                            return TargetD{};
                        }
                    } else if constexpr (std::is_same_v<SrcEl, void>) {
                        if constexpr (kxs_is_optional_v<TargetD>) {
                            return TargetD(*static_cast<typename TargetD::value_type*>(const_cast<void*>(v)));
                        } else {
                            return *static_cast<TargetD*>(const_cast<void*>(v));
                        }
                    } else {
                        return TargetD{};
                    }
                } else if constexpr (std::is_same_v<SrcD, TargetD> || std::is_convertible_v<SrcD, TargetD>) {
                    return static_cast<TargetD>(std::forward<KxsSrc>(v));
                } else if constexpr (std::is_pointer_v<TargetD> && std::is_polymorphic_v<SrcD>) {
                    using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                    if constexpr (std::is_polymorphic_v<DstEl>) return dynamic_cast<TargetD>(const_cast<SrcD*>(std::addressof(v)));
                    else return TargetD{};
                } else {
                    return TargetD{};
                }
            }

            template <typename KxsTarget, typename KxsSrc>
            inline KxsTarget kxs_as_cast(KxsSrc&& v) {
                using TargetD = std::remove_cvref_t<KxsTarget>;
                using SrcD = std::remove_cvref_t<KxsSrc>;
                if constexpr (std::is_abstract_v<TargetD>) {
                    static_assert(!std::is_abstract_v<TargetD>, "abstract target must use shared_ptr or pointer");
                } else if constexpr (std::is_same_v<SrcD, TargetD>) {
                    return std::forward<KxsSrc>(v);
                } else if constexpr (std::is_same_v<SrcD, std::any>) {
                    if (!v.has_value() || v.type() == typeid(std::nullptr_t)) {
                        return TargetD{};
                    }
                    if (auto* p = std::any_cast<const TargetD>(std::addressof(v))) return *p;
                    throw std::runtime_error("ClassCastException");
                } else if constexpr (std::is_convertible_v<SrcD, TargetD>) {
                    return static_cast<TargetD>(std::forward<KxsSrc>(v));
                } else if constexpr (kxs_is_shared_ptr_v<SrcD> && kxs_is_shared_ptr_v<TargetD>) {
                    if (!v) return TargetD{};
                    using SrcEl = typename SrcD::element_type;
                    using DstEl = typename TargetD::element_type;
                    if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) {
                        auto res = std::dynamic_pointer_cast<DstEl>(std::forward<KxsSrc>(v));
                        if (!res) throw std::runtime_error("ClassCastException");
                        return res;
                    } else {
                        throw std::runtime_error("ClassCastException");
                    }
                } else if constexpr (std::is_pointer_v<SrcD> && std::is_same_v<std::remove_cv_t<std::remove_pointer_t<SrcD>>, void>) {
                    if constexpr (kxs_is_optional_v<TargetD>) {
                        using Inner = typename TargetD::value_type;
                        return v ? TargetD(*static_cast<Inner*>(const_cast<void*>(v))) : TargetD{std::nullopt};
                    } else if constexpr (std::is_pointer_v<TargetD>) {
                        return static_cast<TargetD>(const_cast<void*>(v));
                    } else {
                        if (!v) throw std::runtime_error("NullPointerException");
                        return *static_cast<TargetD*>(const_cast<void*>(v));
                    }
                } else if constexpr (std::is_pointer_v<SrcD>) {
                    if (!v) return TargetD{};
                    return kxs_safe_as_cast<KxsTarget>(std::forward<KxsSrc>(v));
                } else {
                    return kxs_safe_as_cast<KxsTarget>(std::forward<KxsSrc>(v));
                }
            }

            template <typename L, typename RFn>
            inline decltype(auto) kxs_elvis(L&& lhs, RFn&& rhs_fn) {
                using LDecay = std::remove_cvref_t<L>;
                using RRaw = std::remove_cvref_t<decltype(rhs_fn())>;
                if constexpr (std::is_void_v<decltype(rhs_fn())>) {
                    if constexpr (std::is_same_v<LDecay, std::any>) {
                        if (!lhs.has_value() || lhs.type() == typeid(std::nullptr_t)) {
                            std::forward<RFn>(rhs_fn)();
                        }
                    } else if constexpr (kxs_is_optional_v<LDecay>) {
                        if (!lhs.has_value()) {
                            std::forward<RFn>(rhs_fn)();
                        }
                    } else {
                        if (!lhs) {
                            std::forward<RFn>(rhs_fn)();
                        }
                    }
                    return;
                } else if constexpr (std::is_same_v<LDecay, std::any>) {
                    return (lhs.has_value() && lhs.type() != typeid(std::nullptr_t))
                        ? LDecay(std::forward<L>(lhs))
                        : std::any(std::forward<RFn>(rhs_fn)());
                } else if constexpr (kxs_is_optional_v<LDecay>) {
                    if constexpr (kxs_is_optional_v<RRaw> || std::is_null_pointer_v<RRaw>) {
                        return lhs.has_value() ? LDecay(std::forward<L>(lhs)) : LDecay(std::forward<RFn>(rhs_fn)());
                    } else if constexpr (std::is_convertible_v<RRaw, typename LDecay::value_type>) {
                        return lhs.has_value() ? typename LDecay::value_type(*std::forward<L>(lhs)) : typename LDecay::value_type(std::forward<RFn>(rhs_fn)());
                    } else {
                        return lhs.has_value() ? *std::forward<L>(lhs) : std::forward<RFn>(rhs_fn)();
                    }
                } else {
                    if constexpr (std::is_convertible_v<LDecay, RRaw>) {
                        return lhs ? RRaw(std::forward<L>(lhs)) : std::forward<RFn>(rhs_fn)();
                    } else if constexpr (std::is_convertible_v<RRaw, LDecay>) {
                        return lhs ? LDecay(std::forward<L>(lhs)) : LDecay(std::forward<RFn>(rhs_fn)());
                    } else {
                        return lhs ? std::forward<L>(lhs) : std::forward<RFn>(rhs_fn)();
                    }
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsNullUnwrapDef) {
        out.append(
            """

            #ifndef KXS_NULL_UNWRAP_DEFINED
            #define KXS_NULL_UNWRAP_DEFINED
            template <typename KxsOpt>
            constexpr decltype(auto) kxs_unwrap_opt(KxsOpt&& v) {
                using KxsDecayed = std::remove_cvref_t<KxsOpt>;
                if constexpr (requires { v.has_value(); *v; } && !std::is_same_v<KxsDecayed, std::any>) {
                    return *std::forward<KxsOpt>(v);
                } else {
                    return std::forward<KxsOpt>(v);
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsResultHelpersDef) {
        out.append(
            """

            #ifndef KXS_RESULT_HELPERS_DEFINED
            #define KXS_RESULT_HELPERS_DEFINED
            template <typename KxsExc>
            inline std::exception_ptr kxs_to_exception_ptr(KxsExc&& e) {
                if constexpr (std::is_same_v<std::decay_t<KxsExc>, std::exception_ptr>) {
                    return std::forward<KxsExc>(e);
                } else {
                    return std::make_exception_ptr(std::forward<KxsExc>(e));
                }
            }
            struct KxsResultFailure {
                std::exception_ptr e;
                constexpr bool is_success() const noexcept { return false; }
                constexpr bool is_failure() const noexcept { return true; }
                std::exception_ptr exception_or_null() const noexcept { return e; }
                [[noreturn]] void get_or_throw() const { std::rethrow_exception(e); }
                template <typename KxsResT>
                operator kotlinx::coroutines::Result<KxsResT>() const {
                    return kotlinx::coroutines::Result<KxsResT>::failure(e);
                }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
}

private class CppDeclarationRenderer(
    private val bundle: LoweringBundle,
    private val isHeaderMode: Boolean,
) {
    private val stream: PacketStream get() = bundle.stream
    private val symbols: SameFileSymbolTable get() = bundle.symbols
    private val includes: CppIncludeCollector get() = bundle.includes
    private val deferredHeaderBodies: MutableList<String> = ArrayList()
    val postIncludeHeaderBodies: MutableList<String> = ArrayList()

    private fun isHoistedTopLevelConstOrSymbolProp(declFrame: PacketFrame): Boolean {
        if (declFrame.nodeTypeId != KtNodeTypes.PROPERTY_ID) return false
        val sym = symbols.propertiesByFrame[declFrame.frameIndex] ?: return false
        if (sym.getterFrameIndex != null || sym.setterFrameIndex != null) return false
        if (FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID)) return false
        if (sym.isConst) return true
        val initIdx = sym.initializerFrameIndex ?: return false
        val initFrame = stream.frames[initIdx]
        if (initFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val callee = FrameAstHelpers.childFrames(stream, initFrame).firstOrNull()
            val ident = callee?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
            if (ident == "Symbol") return true
        }
        return false
    }

    private fun isHoistedForwardReferencedClass(declFrame: PacketFrame): Boolean {
        if (declFrame.nodeTypeId != KtNodeTypes.CLASS_ID && declFrame.nodeTypeId != KtNodeTypes.OBJECT_DECLARATION_ID) {
            return false
        }
        val cls = symbols.classesByFrame[declFrame.frameIndex] ?: return false
        if (FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID)) return false
        val isForwardRef =
            declFrame.frameIndex in symbols.forwardDeclaredClassFrames ||
                symbols.topLevelDeclarationFrames.any { earlier ->
                    earlier.frameIndex < declFrame.frameIndex && frameMentionsIdentifier(earlier, cls.kotlinName)
                }
        if (!isForwardRef) return false
        val dependsOnOtherLocalClass =
            symbols.classesByFrame.values.any { other ->
                other.frameIndex != cls.frameIndex &&
                    other.enclosingClassFrameIndex == null &&
                    frameMentionsIdentifier(declFrame, other.kotlinName)
            }
        return !dependsOnOtherLocalClass
    }

    private fun collectTopLevelClassDependencies(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        topLevelClassByName: Map<String, Int>,
    ): List<Int> {
        val deps = LinkedHashSet<Int>()
        for (stIdx in cls.superTypeFrameIndices) {
            val superName = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[stIdx]) ?: continue
            val targetIdx = topLevelClassByName[superName]
            if (targetIdx != null && targetIdx != declFrame.frameIndex) {
                deps.add(targetIdx)
            }
        }
        for (pIdx in cls.memberPropertyFrameIndices) {
            val pSym = symbols.propertiesByFrame[pIdx] ?: continue
            val retIdx = pSym.returnTypeFrameIndex ?: continue
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[retIdx]) ?: continue
            val targetIdx = topLevelClassByName[simple]
            if (targetIdx != null && targetIdx != declFrame.frameIndex && simple !in symbols.sharedHierarchyTypeNames) {
                deps.add(targetIdx)
            }
        }
        return deps.toList()
    }

    fun renderBodySection(): String =
        buildString {
            val hoistedFrames = HashSet<Int>()
            val topLevelFrameMap = LinkedHashMap<Int, PacketFrame>()
            val topLevelClassByName = LinkedHashMap<String, Int>()
            for (declFrame in symbols.topLevelDeclarationFrames) {
                topLevelFrameMap[declFrame.frameIndex] = declFrame
                if (declFrame.nodeTypeId == KtNodeTypes.CLASS_ID || declFrame.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID) {
                    val cls = symbols.classesByFrame[declFrame.frameIndex]
                    if (cls != null && !(isHeaderMode && FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID) && !isUsedAsSuperTypeInSameFile(cls))) {
                        topLevelClassByName[cls.kotlinName] = declFrame.frameIndex
                    }
                }
            }
            if (isHeaderMode) {
                emitStandaloneRootComments(this)
                emitForwardDeclarations(this)
                for (declFrame in symbols.topLevelDeclarationFrames) {
                    if (declFrame.nodeTypeId == KtNodeTypes.TYPEALIAS_ID) {
                        hoistedFrames.add(declFrame.frameIndex)
                        val taRendered = renderTypeAlias(declFrame, indent = "")
                        if (taRendered.isNotBlank()) {
                            if (isNotEmpty() && !endsWith("\n\n")) append('\n')
                            append(taRendered.trimEnd()).append('\n')
                        }
                    }
                }
                for (declFrame in symbols.topLevelDeclarationFrames) {
                    if (isHoistedTopLevelConstOrSymbolProp(declFrame)) {
                        hoistedFrames.add(declFrame.frameIndex)
                        val propRendered = renderProperty(declFrame, enclosingClass = null, indent = "")
                        if (propRendered.isNotBlank()) {
                            if (isNotEmpty() && !endsWith("\n\n")) append('\n')
                            append(propRendered.trimEnd()).append('\n')
                        }
                    }
                }
                for (declFrame in symbols.topLevelDeclarationFrames) {
                    if (isHoistedForwardReferencedClass(declFrame)) {
                        val cls = symbols.classesByFrame[declFrame.frameIndex]
                        val deps = if (cls != null) collectTopLevelClassDependencies(declFrame, cls, topLevelClassByName) else emptyList()
                        if (deps.all { it in hoistedFrames }) {
                            hoistedFrames.add(declFrame.frameIndex)
                            val clsRendered = renderClassOrObject(declFrame, qualifierPrefix = "", indent = "")
                            if (clsRendered.isNotBlank()) {
                                if (isNotEmpty() && !endsWith("\n\n")) append('\n')
                                append(clsRendered.trimEnd()).append('\n')
                            }
                        }
                    }
                }
            } else {
                // Emit file-private top-level class definitions first in `.cpp` so earlier `.cpp` functions can instantiate them.
                val headerStyleForCpp = CppDeclarationRenderer(bundle, isHeaderMode = true)
                for (declFrame in symbols.topLevelDeclarationFrames) {
                    if (declFrame.nodeTypeId == KtNodeTypes.CLASS_ID || declFrame.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID) {
                        val cls = symbols.classesByFrame[declFrame.frameIndex]
                        if (cls != null && shouldEmitPrivateClassOnlyInCpp(cls)) {
                            val clsDeclText = headerStyleForCpp.renderPrivateClassDefinitionForCpp(declFrame, cls)
                            if (clsDeclText.isNotBlank()) {
                                if (isNotEmpty() && !endsWith("\n\n")) append('\n')
                                append(clsDeclText.trimEnd()).append('\n')
                            }
                        }
                    }
                }
            }

            val emittedFrames = HashSet<Int>(hoistedFrames)
            val visitingClasses = HashSet<Int>()
            fun emitTopLevelFrame(declFrame: PacketFrame) {
                if (declFrame.frameIndex in emittedFrames) return
                if (isHeaderMode && (declFrame.nodeTypeId == KtNodeTypes.CLASS_ID || declFrame.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID)) {
                    val cls = symbols.classesByFrame[declFrame.frameIndex]
                    if (cls != null && visitingClasses.add(declFrame.frameIndex)) {
                        for (depIdx in collectTopLevelClassDependencies(declFrame, cls, topLevelClassByName)) {
                            val depFrame = topLevelFrameMap[depIdx]
                            if (depFrame != null && depIdx !in emittedFrames) {
                                emitTopLevelFrame(depFrame)
                            }
                        }
                        visitingClasses.remove(declFrame.frameIndex)
                    }
                }
                if (!emittedFrames.add(declFrame.frameIndex)) return
                val rendered =
                    when (declFrame.nodeTypeId) {
                        KtNodeTypes.TYPEALIAS_ID -> ""
                        KtNodeTypes.PROPERTY_ID ->
                            renderProperty(declFrame, enclosingClass = null, indent = "")
                        KtNodeTypes.FUN_ID ->
                            renderFunction(declFrame, enclosingClass = null, qualifierPrefix = "", indent = "")
                        KtNodeTypes.CLASS_ID,
                        KtNodeTypes.OBJECT_DECLARATION_ID,
                        -> renderClassOrObject(declFrame, qualifierPrefix = "", indent = "")
                        else -> ""
                    }
                if (rendered.isNotBlank()) {
                    if (isNotEmpty() && !endsWith("\n\n")) {
                        append('\n')
                    }
                    append(rendered.trimEnd()).append('\n')
                }
            }

            for (declFrame in symbols.topLevelDeclarationFrames) {
                emitTopLevelFrame(declFrame)
            }

            if (isHeaderMode) {
                for (deferredBody in deferredHeaderBodies) {
                    if (deferredBody.isNotBlank()) {
                        if (isNotEmpty() && !endsWith("\n\n")) {
                            append('\n')
                        }
                        append(deferredBody.trimEnd()).append('\n')
                    }
                }
            }
        }

    private fun emitStandaloneRootComments(out: StringBuilder) {
        val root = stream.rootFrame
        val importList = FrameAstHelpers.firstChildFrame(stream, root, KtNodeTypes.IMPORT_LIST_ID)
        val pkgDir = FrameAstHelpers.firstChildFrame(stream, root, KtNodeTypes.PACKAGE_DIRECTIVE_ID)
        val afterPreamblePacket =
            maxOf(importList?.lastPacketIndex ?: -1, pkgDir?.lastPacketIndex ?: -1)
        val firstDeclPacket =
            symbols.topLevelDeclarationFrames.firstOrNull()?.firstPacketIndex ?: Int.MAX_VALUE

        for (i in 0 until root.childCount) {
            val ref = root.childRefAt(i)
            if (ref < 0) {
                val pktIdx = -(ref + 1)
                if (pktIdx > afterPreamblePacket && pktIdx < firstDeclPacket) {
                    val pkt = stream.packets[pktIdx]
                    if (pkt is SyntaxPacket.CommentPacket && pkt.portLintDirectiveKind == null) {
                        val text =
                            FrameAstHelpers.formatCommentForCpp(
                                comment = pkt,
                                indent = "",
                                knownFunctionNames = bundle.knownFunctionNames,
                                allowFenceTranslation = !bundle.isDocExample,
                            )
                        if (text.isNotEmpty()) {
                            out.append(text).append("\n\n")
                        }
                    }
                }
            }
        }
    }

    private fun emitForwardDeclarations(out: StringBuilder) {
        if (symbols.forwardDeclaredClassFrames.isEmpty()) return
        var emittedAny = false
        for (clsFrameIdx in symbols.forwardDeclaredClassFrames) {
            val cls = symbols.classesByFrame[clsFrameIdx] ?: continue
            if (shouldEmitPrivateClassOnlyInCpp(cls)) continue
            if (cls.typeParams.isNotEmpty()) {
                out.append(renderTemplatePrefix(cls.typeParams, ""))
            }
            val kw = if (cls.kind == KtClassKind.SIMPLE_ENUM || cls.kind == KtClassKind.RICH_ENUM) "enum class" else "class"
            out
                .append(kw)
                .append(' ')
                .append(cls.cppName)
                .append(";\n")
            emittedAny = true
        }
        if (emittedAny) {
            out.append('\n')
        }
    }

    private fun renderProvenanceAndLeadingComments(
        declFrame: PacketFrame,
        indent: String,
        includeLeadingKdoc: Boolean,
    ): String =
        buildString {
            if (includeLeadingKdoc) {
                for (c in FrameAstHelpers.extractLeadingDeclarationComments(stream, declFrame)) {
                    val formatted =
                        FrameAstHelpers.formatCommentForCpp(
                            comment = c,
                            indent = indent,
                            knownFunctionNames = bundle.knownFunctionNames,
                            allowFenceTranslation = !bundle.isDocExample,
                        )
                    if (formatted.isNotEmpty()) {
                        append(formatted).append('\n')
                    }
                }
            }
        }

    private fun renderTemplatePrefix(
        typeParams: List<KtTypeParamInfo>,
        indent: String,
    ): String {
        if (typeParams.isEmpty()) return ""
        return "$indent" + "template <${typeParams.joinToString(", ") { "typename ${it.name}" }}>\n"
    }

    private fun renderTypeAlias(
        aliasFrame: PacketFrame,
        indent: String,
    ): String {
        val sym = symbols.typeAliasesByFrame[aliasFrame.frameIndex] ?: return ""
        val targetType =
            sym.underlyingTypeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
            } ?: "void"
        return buildString {
            append(renderProvenanceAndLeadingComments(aliasFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(sym.typeParams, indent))
            append(indent)
                .append("using ")
                .append(sym.cppName)
                .append(" = ")
                .append(targetType)
                .append(";\n")
        }
    }

    private fun renderProperty(
        propFrame: PacketFrame,
        enclosingClass: KtClassSymbol?,
        indent: String,
    ): String {
        val sym = symbols.propertiesByFrame[propFrame.frameIndex] ?: return ""
        if (FrameAstHelpers.hasModifierKeyword(stream, propFrame, KtTokens.EXPECT_MODIFIER_ID)) {
            includes.hasExpectDeclarations = true
            if (!isHeaderMode) return ""
            val prefix = renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true)
            return "$prefix$indent// expect val ${sym.cppName}\n"
        }
        val emitInCpp = PacketFlags.hasFlag(sym.flags, PacketFlags.EMIT_IN_CPP_BODY)

        // Computed property with custom `get()` and/or `set()` (e.g. `var <T> WorkaroundAtomicReference<T>.value: T`)
        if (sym.getterFrameIndex != null || sym.setterFrameIndex != null) {
            val activeTps = LinkedHashSet<String>()
            enclosingClass?.typeParams?.forEach { activeTps.add(it.name) }
            sym.typeParams.forEach { activeTps.add(it.name) }

            val getterBody =
                sym.getterFrameIndex?.let { gIdx ->
                    val getterFrame = stream.frames[gIdx]
                    FrameAstHelpers.firstChildFrame(stream, getterFrame, KtNodeTypes.BLOCK_ID)
                        ?: FrameAstHelpers.extractInitializerAfterEquals(stream, getterFrame)
                }
            val setterFrame = sym.setterFrameIndex?.let { stream.frames[it] }
            val setterBody =
                setterFrame?.let { sf ->
                    FrameAstHelpers.firstChildFrame(stream, sf, KtNodeTypes.BLOCK_ID)
                        ?: FrameAstHelpers.extractInitializerAfterEquals(stream, sf)
                }
            val rawRetType =
                sym.returnTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                } ?: getterBody?.let { inferExpressionBodyReturnType(it) } ?: "auto"
            val isNoreturn = rawRetType.startsWith("[[noreturn]] ")
            val retType = rawRetType.removePrefix("[[noreturn]] ")
            val setValType =
                sym.returnTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER)
                } ?: retType
            val recvTypeRaw =
                sym.receiverTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.EXTENSION_RECEIVER)
                }
            val recvParam =
                recvTypeRaw?.let { rType ->
                    val passType =
                        if (rType in VALUE_PASS_CPP_TYPES || rType.endsWith("&") || rType.endsWith("*")) {
                            rType
                        } else if (isMutableExtensionReceiverCppType(rType)) {
                            "$rType&"
                        } else {
                            "const $rType&"
                        }
                    "$passType self"
                }
            val mutRecvParam =
                recvTypeRaw?.let { rType ->
                    val passType =
                        if (rType.endsWith("&") || rType.endsWith("*")) {
                            rType
                        } else {
                            "$rType&"
                        }
                    "$passType self"
                }
            val setterParam =
                setterFrame?.let { sf ->
                    val pList = FrameAstHelpers.firstChildFrame(stream, sf, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
                    FrameAstHelpers.extractValueParams(stream, pList).firstOrNull()
                }
            val setterParamKt = setterParam?.name ?: "value"
            val setterParamCpp = setterParam?.cppName ?: "value"
            val (propExtRecvCls, propExtRecvPtr, propExtRecvSimple) =
                resolveExtensionReceiverFrameContext(sym.receiverTypeFrameIndex)
            val propGetterName =
                if (enclosingClass != null &&
                    recvParam == null &&
                    PacketFlags.hasFlag(propFrame.flags, PacketFlags.MOD_OVERRIDE)
                ) {
                    resolveOverridePropertyGetterName(enclosingClass, sym.kotlinName, sym.cppName)
                } else {
                    sym.cppName
                }
            val setterName =
                if (sym.cppName.endsWith("_")) {
                    "set_${sym.cppName.removeSuffix("_")}"
                } else {
                    "set_${sym.cppName}"
                }

            if (isHeaderMode) {
                val isPropOverride =
                    retType != "auto" &&
                        PacketFlags.hasFlag(propFrame.flags, PacketFlags.MOD_OVERRIDE) &&
                        enclosingClass != null &&
                        enclosingClass.superTypeFrameIndices.isNotEmpty() &&
                        sym.kotlinName != "message" &&
                        sym.kotlinName != "cause"
                val isVirtualProp =
                    if (retType != "auto" && enclosingClass != null && recvParam == null) {
                        enclosingClass.kind == KtClassKind.INTERFACE ||
                            PacketFlags.hasFlag(propFrame.flags, PacketFlags.MOD_OPEN) ||
                            PacketFlags.hasFlag(propFrame.flags, PacketFlags.MOD_ABSTRACT)
                    } else {
                        false
                    }
                val virtPrefix = if (isVirtualProp && !isPropOverride) "virtual " else ""
                val overrideSuffix = if (isPropOverride && recvParam == null) " override" else ""
                val deferPropToPostInclude =
                    !emitInCpp &&
                        (enclosingClass == null || enclosingClass.enclosingClassFrameIndex == null) &&
                        bodyReferencesExpectOrDeferredBottomHeader(propFrame)
                val outOfLinePropClsPrefix =
                    if (enclosingClass != null) {
                        val clsHead =
                            if (enclosingClass.typeParams.isEmpty()) {
                                enclosingClass.cppName
                            } else {
                                "${enclosingClass.cppName}<${enclosingClass.typeParams.joinToString(", ") { it.name }}>"
                            }
                        "$clsHead::"
                    } else {
                        ""
                    }
                return buildString {
                    append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
                    val inlineKw = if (!emitInCpp && enclosingClass == null && sym.typeParams.isEmpty()) "inline " else virtPrefix
                    val constQual = if (enclosingClass != null && recvParam == null) " const" else ""
                    val noreturnKw = if (isNoreturn) "[[noreturn]] " else ""
                    if (sym.getterFrameIndex != null) {
                        val paramSig = recvParam.orEmpty()
                        append(renderTemplatePrefix(sym.typeParams, indent))
                        if (!emitInCpp && getterBody != null && !deferPropToPostInclude) {
                            val emitter =
                                CppExprAndStmtEmitter(
                                    bundle = bundle,
                                    activeTypeParams = activeTps,
                                    enclosingClass = enclosingClass,
                                    receiverSubstitution = if (recvParam != null) "self" else null,
                                    extensionReceiverClass = propExtRecvCls,
                                    extensionReceiverIsPointer = propExtRecvPtr,
                                    extensionReceiverSimpleName = propExtRecvSimple,
                                    enclosingFunctionReturnType = retType,
                                )
                            val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                            val bodyStr = emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = indent)
                            append(indent)
                                .append(noreturnKw)
                                .append(inlineKw)
                                .append(retType)
                                .append(' ')
                                .append(propGetterName)
                                .append('(')
                                .append(paramSig)
                                .append(')')
                                .append(constQual)
                                .append(overrideSuffix)
                                .append(' ')
                                .append(bodyStr)
                                .append('\n')
                        } else {
                            append(indent)
                                .append(noreturnKw)
                                .append(if (enclosingClass == null && deferPropToPostInclude) inlineKw else virtPrefix)
                                .append(retType)
                                .append(' ')
                                .append(propGetterName)
                                .append('(')
                                .append(paramSig)
                                .append(')')
                                .append(constQual)
                                .append(overrideSuffix)
                                .append(";\n")
                            if (deferPropToPostInclude && getterBody != null) {
                                val emitter =
                                    CppExprAndStmtEmitter(
                                        bundle = bundle,
                                        activeTypeParams = activeTps,
                                        enclosingClass = enclosingClass,
                                        receiverSubstitution = if (recvParam != null) "self" else null,
                                        extensionReceiverClass = propExtRecvCls,
                                        extensionReceiverIsPointer = propExtRecvPtr,
                                        extensionReceiverSimpleName = propExtRecvSimple,
                                        enclosingFunctionReturnType = retType,
                                    )
                                val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                                val bodyStr = emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = "")
                                val postDef =
                                    buildString {
                                        if (enclosingClass != null && enclosingClass.typeParams.isNotEmpty()) {
                                            append(renderTemplatePrefix(enclosingClass.typeParams, ""))
                                        }
                                        append(renderTemplatePrefix(sym.typeParams, ""))
                                        append(noreturnKw)
                                        if ((enclosingClass == null || enclosingClass.typeParams.isEmpty()) && sym.typeParams.isEmpty()) {
                                            append("inline ")
                                        }
                                        append(retType)
                                            .append(' ')
                                            .append(outOfLinePropClsPrefix)
                                            .append(propGetterName)
                                            .append('(')
                                            .append(paramSig)
                                            .append(')')
                                            .append(constQual)
                                            .append(' ')
                                            .append(bodyStr)
                                            .append('\n')
                                    }
                                postIncludeHeaderBodies.add(postDef)
                            }
                        }
                    }
                    if (setterFrame != null) {
                        val isInlineSetterBody = !emitInCpp && setterBody != null
                        val unusedSetterAttr =
                            if (isInlineSetterBody && !frameMentionsIdentifier(setterBody, setterParamKt)) {
                                "[[maybe_unused]] "
                            } else {
                                ""
                            }
                        val setterSig =
                            if (mutRecvParam != null) "$mutRecvParam, $unusedSetterAttr$setValType $setterParamCpp" else "$unusedSetterAttr$setValType $setterParamCpp"
                        append(renderTemplatePrefix(sym.typeParams, indent))
                        if (!emitInCpp && setterBody != null && !deferPropToPostInclude) {
                            val emitter =
                                CppExprAndStmtEmitter(
                                    bundle = bundle,
                                    activeTypeParams = activeTps,
                                    enclosingClass = enclosingClass,
                                    receiverSubstitution = if (mutRecvParam != null) "self" else null,
                                    extensionReceiverClass = propExtRecvCls,
                                    extensionReceiverIsPointer = propExtRecvPtr,
                                    extensionReceiverSimpleName = propExtRecvSimple,
                                )
                            emitter.bindLocalName(setterParamKt, setterParamCpp)
                            val isExpr = setterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                            val bodyStr = emitter.emitBlockOrExprBody(setterBody, isExpr, isVoidReturn = true, indent = indent)
                            append(indent)
                                .append(inlineKw)
                                .append("void ")
                                .append(setterName)
                                .append('(')
                                .append(setterSig)
                                .append(')')
                                .append(overrideSuffix)
                                .append(' ')
                                .append(bodyStr)
                                .append('\n')
                        } else {
                            append(indent)
                                .append(if (enclosingClass == null && deferPropToPostInclude) inlineKw else virtPrefix)
                                .append("void ")
                                .append(setterName)
                                .append('(')
                                .append(setterSig)
                                .append(')')
                                .append(overrideSuffix)
                                .append(";\n")
                            if (deferPropToPostInclude && setterBody != null) {
                                val emitter =
                                    CppExprAndStmtEmitter(
                                        bundle = bundle,
                                        activeTypeParams = activeTps,
                                        enclosingClass = enclosingClass,
                                        receiverSubstitution = if (mutRecvParam != null) "self" else null,
                                        extensionReceiverClass = propExtRecvCls,
                                        extensionReceiverIsPointer = propExtRecvPtr,
                                        extensionReceiverSimpleName = propExtRecvSimple,
                                    )
                                emitter.bindLocalName(setterParamKt, setterParamCpp)
                                val isExpr = setterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                                val bodyStr = emitter.emitBlockOrExprBody(setterBody, isExpr, isVoidReturn = true, indent = "")
                                val postDef =
                                    buildString {
                                        if (enclosingClass != null && enclosingClass.typeParams.isNotEmpty()) {
                                            append(renderTemplatePrefix(enclosingClass.typeParams, ""))
                                        }
                                        append(renderTemplatePrefix(sym.typeParams, ""))
                                        if ((enclosingClass == null || enclosingClass.typeParams.isEmpty()) && sym.typeParams.isEmpty()) {
                                            append("inline ")
                                        }
                                        append("void ")
                                            .append(outOfLinePropClsPrefix)
                                            .append(setterName)
                                            .append('(')
                                            .append(setterSig)
                                            .append(") ")
                                            .append(bodyStr)
                                            .append('\n')
                                    }
                                postIncludeHeaderBodies.add(postDef)
                            }
                        }
                    }
                }
            } else if (emitInCpp) {
                val qual = if (enclosingClass != null) "${enclosingClass.cppName}::" else ""
                val constQual = if (enclosingClass != null && recvParam == null) " const" else ""
                val noreturnKw = if (isNoreturn) "[[noreturn]] " else ""
                return buildString {
                    if (getterBody != null) {
                        val emitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                activeTypeParams = activeTps,
                                enclosingClass = enclosingClass,
                                receiverSubstitution = if (recvParam != null) "self" else null,
                                extensionReceiverClass = propExtRecvCls,
                                extensionReceiverIsPointer = propExtRecvPtr,
                                extensionReceiverSimpleName = propExtRecvSimple,
                                enclosingFunctionReturnType = retType,
                            )
                        val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                        val bodyStr = emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = indent)
                        append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = false))
                        append(renderTemplatePrefix(sym.typeParams, indent))
                        append(indent)
                            .append(noreturnKw)
                            .append(retType)
                            .append(' ')
                            .append(qual)
                            .append(propGetterName)
                            .append('(')
                            .append(recvParam.orEmpty())
                            .append(')')
                            .append(constQual)
                            .append(' ')
                            .append(bodyStr)
                            .append('\n')
                    }
                    if (setterBody != null) {
                        val setterSig =
                            if (mutRecvParam != null) "$mutRecvParam, $setValType $setterParamCpp" else "$setValType $setterParamCpp"
                        val emitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                activeTypeParams = activeTps,
                                enclosingClass = enclosingClass,
                                receiverSubstitution = if (mutRecvParam != null) "self" else null,
                                extensionReceiverClass = propExtRecvCls,
                                extensionReceiverIsPointer = propExtRecvPtr,
                                extensionReceiverSimpleName = propExtRecvSimple,
                            )
                        emitter.bindLocalName(setterParamKt, setterParamCpp)
                        val isExpr = setterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                        val bodyStr = emitter.emitBlockOrExprBody(setterBody, isExpr, isVoidReturn = true, indent = indent)
                        append(renderTemplatePrefix(sym.typeParams, indent))
                        append(indent)
                            .append("void ")
                            .append(qual)
                            .append(setterName)
                            .append('(')
                            .append(setterSig)
                            .append(") ")
                            .append(bodyStr)
                            .append('\n')
                    }
                }
            }
            return ""
        }

        if (!isHeaderMode) return ""

        // `atomicfu` field or array
        bundle.atomicPlan.atomicFieldsByPropFrame[propFrame.frameIndex]?.let { af ->
            includes.addSystem("atomic")
            val sharedPtrElemType = resolveSharedPtrAtomicElemType(af, enclosingClass)
            val simpleEnumCppNames =
                symbols.classesByFrame.values
                    .filter { it.kind == KtClassKind.SIMPLE_ENUM }
                    .map { it.cppName }
                    .toSet()
            val rawElemType =
                sharedPtrElemType
                    ?: af.explicitElementTypeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT)
                    } ?: af.inferredElementCppType ?: "void*"
            val useAtomicRefHolder = sharedPtrElemType != null
            if (useAtomicRefHolder) {
                includes.addSystem("mutex")
                includes.needsAtomicReferenceDef = true
            }
            val elemType =
                if (useAtomicRefHolder) {
                    sharedPtrElemType
                } else {
                    CppTypeTranslator.coerceToTrivialAtomicElementType(rawElemType, simpleEnumCppNames)
                }
            val atomicTemplateName = if (useAtomicRefHolder) "KxsAtomicReference" else "std::atomic"
            val fieldCppName =
                if (enclosingClass != null && isBackedOverrideMemberProp(enclosingClass, propFrame, sym)) {
                    CppNamingPolicy.toPrivateFieldName(sym.kotlinName)
                } else {
                    sym.cppName
                }
            val isNonLocalInit =
                enclosingClass == null ||
                    enclosingClass.kind == KtClassKind.OBJECT_SINGLETON ||
                    enclosingClass.kind == KtClassKind.COMPANION_OBJECT
            val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = enclosingClass, inDefaultParameterExpr = isNonLocalInit)
            return buildString {
                append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
                if (af.isArray) {
                    includes.addSystem("vector")
                    val defersToCtor =
                        enclosingClass != null &&
                            af.arraySizeExprFrameIndex != null &&
                            referencesNonFieldCtorParam(enclosingClass, stream.frames[af.arraySizeExprFrameIndex])
                    val sz =
                        af.arraySizeExprFrameIndex
                            ?.takeUnless { defersToCtor }
                            ?.let { emitter.emitExpression(stream.frames[it]) } ?: "0"
                    if (enclosingClass == null) {
                        append(indent)
                            .append("inline std::vector<")
                            .append(atomicTemplateName)
                            .append('<')
                            .append(elemType)
                            .append(">> ")
                            .append(fieldCppName)
                            .append("(static_cast<std::size_t>(")
                            .append(sz)
                            .append("));\n")
                    } else if (defersToCtor) {
                        append(indent)
                            .append("std::vector<")
                            .append(atomicTemplateName)
                            .append('<')
                            .append(elemType)
                            .append(">> ")
                            .append(fieldCppName)
                            .append("{};\n")
                    } else {
                        append(indent)
                            .append("std::vector<")
                            .append(atomicTemplateName)
                            .append('<')
                            .append(elemType)
                            .append(">> ")
                            .append(fieldCppName)
                            .append(" = std::vector<")
                            .append(atomicTemplateName)
                            .append('<')
                            .append(elemType)
                            .append(">>(static_cast<std::size_t>(")
                            .append(sz)
                            .append("));\n")
                    }
                } else {
                    val initFrame = af.initExprFrameIndex?.let { stream.frames[it] }
                    val initCode =
                        if (initFrame == null || referencesNonFieldCtorParam(enclosingClass, initFrame)) {
                            "{}"
                        } else if (elemType == "void*") {
                            renderVoidPtrAtomicInit(initFrame, emitter)
                        } else {
                            emitter.emitExpression(initFrame)
                        }
                    val prefix = if (enclosingClass == null) "inline " else "mutable "
                    if (initCode == "{}") {
                        append(indent)
                            .append(prefix)
                            .append(atomicTemplateName)
                            .append('<')
                            .append(elemType)
                            .append("> ")
                            .append(fieldCppName)
                            .append("{};\n")
                    } else {
                        append(indent)
                            .append(prefix)
                            .append(atomicTemplateName)
                            .append('<')
                            .append(elemType)
                            .append("> ")
                            .append(fieldCppName)
                            .append('{')
                            .append(initCode)
                            .append("};\n")
                    }
                }
            }
        }

        // `ThreadLocal` / `CommonThreadLocal` slot
        bundle.atomicPlan.threadLocalsByPropFrame[propFrame.frameIndex]?.let { tl ->
            includes.addSystem("unordered_map")
            includes.needsThreadLocalDef = true
            val elemType =
                tl.explicitElementTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT)
                } ?: "void*"
            val prefix = if (enclosingClass == null) "inline " else "static inline "
            return buildString {
                append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
                append(indent)
                    .append(prefix)
                    .append("ThreadLocal<")
                    .append(elemType)
                    .append("> ")
                    .append(sym.cppName)
                    .append("{};\n")
            }
        }

        val isNonLocalInit =
            enclosingClass == null ||
                enclosingClass.kind == KtClassKind.OBJECT_SINGLETON ||
                enclosingClass.kind == KtClassKind.COMPANION_OBJECT
        val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = enclosingClass, inDefaultParameterExpr = isNonLocalInit)
        val initCode = sym.initializerFrameIndex?.let { emitter.emitExpression(stream.frames[it]) }
        val explicitType =
            sym.returnTypeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE, propFrame.flags)
            }

        return buildString {
            append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
            if (sym.isConst) {
                val cppT = explicitType ?: "auto"
                val staticOrInline = if (enclosingClass != null) "static constexpr " else "inline constexpr "
                val valT =
                    if (cppT == "std::string") {
                        includes.addSystem("string_view")
                        "std::string_view"
                    } else {
                        cppT
                    }
                val renderedInit =
                    if (valT == "std::string_view" && sym.initializerFrameIndex != null) {
                        val strFrame = stream.frames[sym.initializerFrameIndex]
                        if (strFrame.nodeTypeId == KtNodeTypes.STRING_TEMPLATE_ID) {
                            CppExprAndStmtEmitter.escapeCppStringLiteral(
                                FrameAstHelpers.extractConstantStringText(stream, strFrame),
                            )
                        } else {
                            initCode ?: "\"\""
                        }
                    } else {
                        initCode ?: "{}"
                    }
                append(indent)
                    .append(staticOrInline)
                    .append(valT)
                    .append(' ')
                    .append(sym.cppName)
                    .append(" = ")
                    .append(renderedInit)
                    .append(";\n")
            } else if (
                enclosingClass == null ||
                enclosingClass.kind == KtClassKind.OBJECT_SINGLETON ||
                enclosingClass.kind == KtClassKind.COMPANION_OBJECT
            ) {
                val storageKw = if (enclosingClass != null) "static inline " else "inline "
                val cppT = explicitType ?: "auto"
                val isInherentlyMutableVal =
                    cppT == "KxsSynchronizedObject" ||
                        cppT.startsWith("std::unordered_") ||
                        cppT.startsWith("std::vector<") ||
                        cppT.startsWith("KxsArrayDeque<") ||
                        cppT.startsWith("KxsAtomicReference<") ||
                        cppT.startsWith("WorkaroundAtomicReference<") ||
                        initCode?.let { ic ->
                            ic.startsWith("KxsSynchronizedObject") ||
                                ic.startsWith("SynchronizedObject") ||
                                ic.startsWith("ReentrantLock") ||
                                ic.startsWith("std::unordered_") ||
                                ic.startsWith("std::vector<") ||
                                ic.startsWith("KxsArrayDeque<")
                        } == true
                val constKw = if (!sym.isVar && !isInherentlyMutableVal) "const " else ""
                if (initCode != null) {
                    val coercedInit =
                        when {
                            initCode == "nullptr" && cppT.startsWith("std::optional<") -> "std::nullopt"
                            initCode == "nullptr" && cppT == "std::any" -> "{}"
                            else -> initCode
                        }
                    append(indent)
                        .append(storageKw)
                        .append(constKw)
                        .append(cppT)
                        .append(' ')
                        .append(sym.cppName)
                        .append(" = ")
                        .append(coercedInit)
                        .append(";\n")
                } else {
                    append(indent)
                        .append(storageKw)
                        .append(cppT)
                        .append(' ')
                        .append(sym.cppName)
                        .append("{};\n")
                }
            } else {
                val inferredMemberType =
                    sym.initializerFrameIndex?.let { idx ->
                        inferExpressionBodyReturnType(stream.frames[idx]).takeIf { it != "auto" }
                    }
                val cppT =
                    explicitType ?: inferredMemberType ?: run {
                        includes.addSystem("any")
                        "std::any"
                    }
                val isAbstractOrInterfaceProp =
                    sym.initializerFrameIndex == null &&
                        (
                            enclosingClass.kind == KtClassKind.INTERFACE ||
                                PacketFlags.hasFlag(propFrame.flags, PacketFlags.MOD_ABSTRACT)
                        )
                if (isAbstractOrInterfaceProp) {
                    append(renderTemplatePrefix(sym.typeParams, indent))
                    append(indent)
                        .append("virtual ")
                        .append(cppT)
                        .append(' ')
                        .append(sym.cppName)
                        .append("() const = 0;\n")
                    if (sym.isVar) {
                        val setterName =
                            if (sym.cppName.endsWith("_")) {
                                "set_${sym.cppName.removeSuffix("_")}"
                            } else {
                                "set_${sym.cppName}"
                            }
                        append(renderTemplatePrefix(sym.typeParams, indent))
                        append(indent)
                            .append("virtual void ")
                            .append(setterName)
                            .append('(')
                            .append(cppT)
                            .append(" value) = 0;\n")
                    }
                } else {
                    val memberFieldName =
                        if (isBackedOverrideMemberProp(enclosingClass, propFrame, sym)) {
                            CppNamingPolicy.toPrivateFieldName(sym.kotlinName)
                        } else {
                            sym.cppName
                        }
                    val qualKw =
                        if (cppT.startsWith("std::atomic<") || cppT.startsWith("KxsAtomicReference<") || cppT == "KxsSynchronizedObject") {
                            "mutable "
                        } else if (!sym.isVar && !cppT.startsWith("std::unordered_") && !cppT.startsWith("std::vector<") && !cppT.startsWith("KxsArrayDeque<")) {
                            "const "
                        } else {
                            ""
                        }
                    val initMentionsCtorParam =
                        sym.initializerFrameIndex != null &&
                            referencesNonFieldCtorParam(enclosingClass, stream.frames[sym.initializerFrameIndex])
                    if (initCode != null && !initMentionsCtorParam) {
                        val coercedInit =
                            when {
                                initCode == "nullptr" && cppT.startsWith("std::optional<") -> "std::nullopt"
                                initCode == "nullptr" && cppT == "std::any" -> "{}"
                                else -> initCode
                            }
                        append(indent)
                            .append(qualKw)
                            .append(cppT)
                            .append(' ')
                            .append(memberFieldName)
                            .append(" = ")
                            .append(coercedInit)
                            .append(";\n")
                    } else {
                        append(indent)
                            .append(qualKw)
                            .append(cppT)
                            .append(' ')
                            .append(memberFieldName)
                            .append("{};\n")
                    }
                }
            }
        }
    }

    private fun renderFunction(
        fnFrame: PacketFrame,
        enclosingClass: KtClassSymbol?,
        qualifierPrefix: String,
        indent: String,
    ): String {
        val sym = symbols.functionsByFrame[fnFrame.frameIndex] ?: return ""
        val liveFlags = fnFrame.flags

        // Special dual-ABI leaf `suspendCoroutineUninterceptedOrReturn` intrinsic (`Yield.hpp` + `Yield.cpp`)
        bundle.suspendPlan.leafIntrinsicsByFunFrame[fnFrame.frameIndex]?.let { leaf ->
            return renderLeafContinuationIntrinsicFunction(fnFrame, sym, leaf, qualifierPrefix, indent)
        }

        val emitInCpp = PacketFlags.hasFlag(liveFlags, PacketFlags.EMIT_IN_CPP_BODY)
        val emitInlineInHpp = PacketFlags.hasFlag(liveFlags, PacketFlags.EMIT_IN_HPP_INLINE_BODY)
        val isPureDecl = sym.bodyFrameIndex == null && fnFrame.frameIndex !in bundle.ffiPlan.inlineCppByFrame
        val isExpectFun = FrameAstHelpers.hasModifierKeyword(stream, fnFrame, KtTokens.EXPECT_MODIFIER_ID)

        if (!isHeaderMode && !emitInCpp) return ""
        if (isExpectFun && isPureDecl) {
            if (enclosingClass == null) {
                includes.hasExpectDeclarations = true
                val prefix = renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true)
                return "$prefix$indent// expect fun ${sym.cppName}\n"
            }
            if (sym.receiverTypeFrameIndex != null) {
                val recvSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[sym.receiverTypeFrameIndex])
                val recvCls = recvSimple?.let { symbols.classesByName[it] }
                if (recvCls != null && isOmittedExpectClass(recvCls)) {
                    includes.hasExpectDeclarations = true
                    val prefix = renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true)
                    return "$prefix$indent// expect fun ${sym.cppName}\n"
                }
            }
        }

        val collapsedVarianceTp = resolveCollapsedVarianceEscapeTypeParam(sym, enclosingClass)
        val baseTypeParams = if (collapsedVarianceTp != null) emptyList() else sym.typeParams

        val rawRetType =
            if (sym.returnTypeFrameIndex != null) {
                bundle.typeTranslator.translateTypeFrame(stream.frames[sym.returnTypeFrameIndex], CppTypePosition.RETURN)
            } else if (sym.isExpressionBody && sym.bodyFrameIndex != null) {
                inferFunctionExpressionBodyReturnType(sym, enclosingClass, stream.frames[sym.bodyFrameIndex])
            } else {
                "void"
            }
        val isNoreturn = rawRetType.startsWith("[[noreturn]] ")
        val retType = rawRetType.removePrefix("[[noreturn]] ")
        val isVoidReturn = retType == "void"

        val isDepHidden = isDeprecatedHiddenFun(sym)
        val baseMethodName = resolveCppFunctionOrOperatorName(sym, enclosingClass)
        val methodName = if (isDepHidden) "${baseMethodName}_deprecated_hidden_${sym.valueParams.size}" else baseMethodName
        val isInterfaceMethod = enclosingClass?.kind == KtClassKind.INTERFACE
        val isCAbi = sym.cAbiSymbolName != null
        val isHashCodeOverrideOnCancellationExc =
            sym.kotlinName == "hashCode" &&
                sym.valueParams.isEmpty() &&
                enclosingClass != null &&
                isCancellationExceptionSubclass(enclosingClass)
        val superDeclaresToString =
            sym.kotlinName == "toString" &&
                sym.valueParams.isEmpty() &&
                enclosingClass != null &&
                superDeclaresMethod(enclosingClass, "toString")
        val isKotlinAnyRootMethod =
            (sym.kotlinName == "toString" || sym.kotlinName == "hashCode") &&
                sym.valueParams.isEmpty() &&
                !isHashCodeOverrideOnCancellationExc &&
                !superDeclaresToString
        val hasBaseVirtualTrait =
            isInterfaceMethod ||
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) ||
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE)

        val starPlan =
            if (!bundle.isDocExample && !isCAbi && emitInlineInHpp && baseTypeParams.isEmpty() && !hasBaseVirtualTrait) {
                buildStarProjectionPlan(sym)
            } else {
                StarProjectionPlan(emptyList(), null, emptyMap())
            }
        val effectiveTypeParams = baseTypeParams + starPlan.synthesizedTypeParams

        val activeTps = LinkedHashSet<String>()
        enclosingClass?.typeParams?.forEach { activeTps.add(it.name) }
        effectiveTypeParams.forEach { activeTps.add(it.name) }

        val canBeVirtual = effectiveTypeParams.isEmpty() && retType != "auto" && !isDepHidden
        val isVirtualMethod =
            canBeVirtual &&
                (
                    isInterfaceMethod ||
                        PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
                        PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) ||
                        (isKotlinAnyRootMethod && PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE))
                )
        val isOverride =
            canBeVirtual &&
                (!isKotlinAnyRootMethod || isHashCodeOverrideOnCancellationExc || superDeclaresToString) &&
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE) &&
                enclosingClass != null &&
                enclosingClass.superTypeFrameIndices.isNotEmpty()
        val isStaticOnObject =
            (enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT) &&
                !isOverride &&
                !(isKotlinAnyRootMethod && PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE))
        val isConstMethod = shouldMarkMethodConst(sym, enclosingClass)

        val isSuspend = PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_SUSPEND)
        val isFreeNonTemplateSuspend = isSuspend && enclosingClass == null && effectiveTypeParams.isEmpty() && !emitInlineInHpp

        if (isHeaderMode) {
            val deferToPostInclude =
                emitInlineInHpp &&
                    !isPureDecl &&
                    !isCAbi &&
                    sym.bodyFrameIndex != null &&
                    enclosingClass?.enclosingClassFrameIndex == null &&
                    bodyReferencesExpectOrDeferredBottomHeader(fnFrame)
            val deferHeaderBody =
                !deferToPostInclude &&
                    emitInlineInHpp &&
                    !isPureDecl &&
                    !isCAbi &&
                    sym.bodyFrameIndex != null &&
                    enclosingClass?.enclosingClassFrameIndex == null &&
                    bodyReferencesLaterTopLevelDecl(
                        bodyFrame = stream.frames[sym.bodyFrameIndex],
                        anchorDeclFrameIndex = enclosingClass?.frameIndex ?: fnFrame.frameIndex,
                        sym = sym,
                        enclosingClass = enclosingClass,
                    )
            val hasInlineHeaderBody =
                (!isPureDecl && emitInlineInHpp) || (isPureDecl && isDepHidden && enclosingClass != null)
            val preRenderedHeaderBody =
                if (!isPureDecl && emitInlineInHpp) {
                    renderFunctionBody(
                        sym = sym,
                        enclosingClass = enclosingClass,
                        activeTypeParams = activeTps,
                        isVoidReturn = isVoidReturn,
                        indent = if (deferToPostInclude || deferHeaderBody) "" else indent,
                        collapsedVarianceTp = collapsedVarianceTp,
                    )
                } else {
                    null
                }
            val unusedHeaderParamNames =
                if (!hasInlineHeaderBody) {
                    emptySet()
                } else if (isPureDecl && isDepHidden && enclosingClass != null) {
                    val unused = sym.valueParams.mapTo(HashSet()) { it.cppName }
                    if (sym.receiverTypeFrameIndex != null) unused.add("self")
                    unused
                } else if (preRenderedHeaderBody != null) {
                    val unused =
                        sym.valueParams
                            .filter { vp -> !containsWholeWord(preRenderedHeaderBody, vp.cppName) }
                            .mapTo(HashSet()) { it.cppName }
                    if (sym.receiverTypeFrameIndex != null && !containsWholeWord(preRenderedHeaderBody, "self")) {
                        unused.add("self")
                    }
                    unused
                } else {
                    emptySet()
                }
            val rawParamsRendered =
                buildFunctionParameterList(
                    sym = sym,
                    includeDefaults = isHeaderMode && !isDepHidden,
                    starPlan = starPlan,
                    unusedParamCppNames = if (deferToPostInclude || deferHeaderBody) emptySet() else unusedHeaderParamNames,
                )
            val paramsRendered =
                if (collapsedVarianceTp != null) {
                    replaceWholeIdentifier(rawParamsRendered, collapsedVarianceTp.first, collapsedVarianceTp.second)
                } else {
                    rawParamsRendered
                }
            return buildString {
                append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                append(renderTemplatePrefix(effectiveTypeParams, indent))
                if (isFreeNonTemplateSuspend) {
                    includes.addSystem("memory")
                    includes.addProject("kotlinx/coroutines/Continuation.hpp")
                    append(indent).append("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_authored_suspend\")]]\n")
                }
                append(indent)
                if (isNoreturn) append("[[noreturn]] ")
                if (isCAbi) {
                    append("extern \"C\" ")
                } else if (isStaticOnObject) {
                    append("static ")
                } else if (isVirtualMethod && !isOverride) {
                    append("virtual ")
                } else if (emitInlineInHpp && enclosingClass == null && effectiveTypeParams.isEmpty()) {
                    append("inline ")
                }
                append(retType)
                    .append(' ')
                    .append(methodName)
                    .append('(')
                    .append(paramsRendered)
                    .append(')')
                if (isConstMethod) append(" const")
                if (isCAbi) append(" noexcept")
                if (isOverride && !isStaticOnObject) append(" override")

                if (isPureDecl) {
                    if (isDepHidden && enclosingClass != null) {
                        if (isVoidReturn) append(" {}\n") else append(" { return {}; }\n")
                    } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) && canBeVirtual) {
                        append(" = 0;\n")
                    } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) && effectiveTypeParams.isNotEmpty()) {
                        includes.addSystem("stdexcept")
                        append(" { throw std::logic_error(\"unimplemented template interface method\"); }\n")
                    } else {
                        append(";\n")
                    }
                } else if (deferToPostInclude || deferHeaderBody) {
                    append(";\n")
                    val defNoDefaultsParams =
                        buildFunctionParameterList(
                            sym = sym,
                            includeDefaults = false,
                            starPlan = starPlan,
                            unusedParamCppNames = unusedHeaderParamNames,
                        ).let { p ->
                            if (collapsedVarianceTp != null) replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second) else p
                        }
                    val defText =
                        buildString {
                            if (enclosingClass != null && enclosingClass.typeParams.isNotEmpty()) {
                                append(renderTemplatePrefix(enclosingClass.typeParams, ""))
                            }
                            append(renderTemplatePrefix(effectiveTypeParams, ""))
                            if (isNoreturn) append("[[noreturn]] ")
                            if ((enclosingClass == null || enclosingClass.typeParams.isEmpty()) && effectiveTypeParams.isEmpty()) {
                                append("inline ")
                            }
                            val qualMethod =
                                if (enclosingClass == null) {
                                    methodName
                                } else {
                                    val clsHead =
                                        if (enclosingClass.typeParams.isEmpty()) {
                                            enclosingClass.cppName
                                        } else {
                                            "${enclosingClass.cppName}<${enclosingClass.typeParams.joinToString(", ") { it.name }}>"
                                        }
                                    "$clsHead::$methodName"
                                }
                            append(retType)
                                .append(' ')
                                .append(qualMethod)
                                .append('(')
                                .append(defNoDefaultsParams)
                                .append(')')
                            if (isConstMethod) append(" const")
                            append(' ').append(preRenderedHeaderBody).append('\n')
                        }
                    if (deferToPostInclude) {
                        postIncludeHeaderBodies.add(defText)
                    } else {
                        deferredHeaderBodies.add(defText)
                    }
                } else if (emitInlineInHpp) {
                    append(' ').append(preRenderedHeaderBody).append('\n')
                } else {
                    append(";\n")
                }
                if (isFreeNonTemplateSuspend && !isPureDecl) {
                    val loweredNoDefaultParams =
                        buildFunctionParameterList(
                            sym = sym,
                            includeDefaults = false,
                            starPlan = starPlan,
                        ).let { p ->
                            if (collapsedVarianceTp != null) replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second) else p
                        }
                    val loweredWithCont =
                        if (loweredNoDefaultParams.isEmpty()) {
                            "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
                        } else {
                            "$loweredNoDefaultParams, std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
                        }
                    append('\n')
                    append(renderTemplatePrefix(effectiveTypeParams, indent))
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                    append(indent)
                        .append("void* ")
                        .append(methodName)
                        .append('(')
                        .append(loweredWithCont)
                        .append(");\n")
                }
                if (!isPureDecl && !isDepHidden && !isInterfaceMethod && !PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) && !isCAbi && !sym.isOperator) {
                    append(
                        renderPrefixDefaultOverloads(
                            sym = sym,
                            enclosingClass = enclosingClass,
                            retType = rawRetType,
                            isVoidReturn = isVoidReturn,
                            methodName = methodName,
                            isConstMethod = isConstMethod,
                            indent = indent,
                            effectiveTypeParams = effectiveTypeParams,
                            starPlan = starPlan,
                        ),
                    )
                }
                if (!isPureDecl &&
                    enclosingClass != null &&
                    sym.kotlinName == "interceptContinuation" &&
                    sym.typeParams.size == 1 &&
                    sym.valueParams.size == 1
                ) {
                    includes.addSystem("memory")
                    includes.addSystem("utility")
                    includes.addProject("kotlinx/coroutines/Continuation.hpp")
                    append(indent)
                        .append("std::shared_ptr<Continuation<void*>> intercept_continuation(std::shared_ptr<Continuation<void*>> continuation) override { return this->intercept_continuation<void*>(std::move(continuation)); }\n")
                }
            }
        }

        val rawCppBody = renderFunctionBody(sym, enclosingClass, activeTps, isVoidReturn, indent, collapsedVarianceTp)
        val cppBody =
            if (isNoreturn &&
                rawCppBody.endsWith("}") &&
                !rawCppBody.contains("throw ") &&
                !rawCppBody.contains("std::rethrow_exception") &&
                !rawCppBody.contains("std::unreachable()")
            ) {
                includes.addSystem("utility")
                val trimmedEnd = rawCppBody.dropLast(1).trimEnd()
                if (trimmedEnd == "{") {
                    "{\n$indent    std::unreachable();\n$indent}"
                } else {
                    "$trimmedEnd\n$indent    std::unreachable();\n$indent}"
                }
            } else {
                rawCppBody
            }
        val unusedCppParamNames =
            sym.valueParams
                .filter { vp -> !containsWholeWord(cppBody, vp.cppName) }
                .mapTo(HashSet()) { it.cppName }
                .apply {
                    if (sym.receiverTypeFrameIndex != null && !containsWholeWord(cppBody, "self")) {
                        add("self")
                    }
                }
        val rawParamsRendered =
            buildFunctionParameterList(
                sym = sym,
                includeDefaults = false,
                starPlan = starPlan,
                unusedParamCppNames = unusedCppParamNames,
            )
        val paramsRendered =
            if (collapsedVarianceTp != null) {
                replaceWholeIdentifier(rawParamsRendered, collapsedVarianceTp.first, collapsedVarianceTp.second)
            } else {
                rawParamsRendered
            }
        // `.cpp` out-of-line implementation
        val qualifiedName = if (qualifierPrefix.isEmpty()) methodName else "$qualifierPrefix::$methodName"
        return buildString {
            append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = false))
            append(renderTemplatePrefix(effectiveTypeParams, indent))
            if (isFreeNonTemplateSuspend) {
                includes.addSystem("memory")
                includes.addProject("kotlinx/coroutines/Continuation.hpp")
                append(indent).append("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_authored_suspend\")]]\n")
            }
            append(indent)
            if (isNoreturn) append("[[noreturn]] ")
            if (isCAbi) append("extern \"C\" ")
            append(retType)
                .append(' ')
                .append(qualifiedName)
                .append('(')
                .append(paramsRendered)
                .append(')')
            if (isConstMethod) append(" const")
            if (isCAbi) append(" noexcept")
            append(' ').append(cppBody).append('\n')
        }
    }

    private fun renderLeafContinuationIntrinsicFunction(
        fnFrame: PacketFrame,
        sym: KtFunctionSymbol,
        leaf: LeafContinuationIntrinsic,
        qualifierPrefix: String,
        indent: String,
    ): String {
        includes.addProject("kotlinx/coroutines/Continuation.hpp")
        includes.addSystem("memory")
        val contElemType =
            if (sym.returnTypeFrameIndex != null && leaf.isBoxedResult) {
                bundle.typeTranslator.translateTypeFrame(stream.frames[sym.returnTypeFrameIndex], CppTypePosition.TYPE_ARGUMENT)
            } else {
                "void*"
            }
        val authoringRetType =
            if (sym.returnTypeFrameIndex != null) {
                bundle.typeTranslator.translateTypeFrame(stream.frames[sym.returnTypeFrameIndex], CppTypePosition.RETURN)
            } else {
                "void"
            }

        val isTemplate = sym.typeParams.isNotEmpty()
        val isCancellableIntrinsic =
            isTemplate &&
                (sym.kotlinName == "suspendCancellableCoroutine" || sym.kotlinName == "suspendCancellableCoroutineReusable")

        if (isCancellableIntrinsic) {
            if (!isHeaderMode) return ""
            includes.addProject("kotlinx/coroutines/ContinuationBindings.hpp")
            includes.addProject("kotlinx/coroutines/Result.hpp")
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            includes.addProject("kotlinx/coroutines/intrinsics/Intrinsics.hpp")
            includes.addSystem("any")
            includes.addSystem("type_traits")
            includes.addSystem("utility")

            val tp0 = sym.typeParams[0].name
            val declTps =
                if (sym.typeParams.size == 1) {
                    "template <typename $tp0 = kotlinx::coroutines::Unit, typename KxsBlock>\n"
                } else {
                    "template <${sym.typeParams.joinToString(", ") { "typename ${it.name}" }}, typename KxsBlock>\n"
                }
            val defTps = "template <${sym.typeParams.joinToString(", ") { "typename ${it.name}" }}, typename KxsBlock>\n"
            val blockParamName = sym.valueParams.lastOrNull()?.cppName ?: "block"
            val isReusable = sym.kotlinName == "suspendCancellableCoroutineReusable"

            val declHeader =
                buildString {
                    append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                    append(indent).append(declTps)
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                    append(indent)
                        .append("void* ")
                        .append(sym.cppName)
                        .append("(KxsBlock&& ")
                        .append(blockParamName)
                        .append(", std::shared_ptr<kotlin::coroutines::Continuation<void*>> completion);\n\n")
                    append(indent).append(declTps)
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                    append(indent)
                        .append("void* ")
                        .append(sym.cppName)
                        .append("(KxsBlock&& ")
                        .append(blockParamName)
                        .append(", kotlin::coroutines::Continuation<void*>* completion);\n\n")
                    append(indent).append(declTps)
                    append(indent).append("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]\n")
                    append(indent)
                        .append("__attribute__((error(\"")
                        .append(sym.cppName)
                        .append("() requires suspend lowering; use ")
                        .append(sym.cppName)
                        .append("(completion) at an explicit ABI boundary\")))\n")
                    append(indent)
                        .append(authoringRetType)
                        .append(' ')
                        .append(sym.cppName)
                        .append("(KxsBlock&& ")
                        .append(blockParamName)
                        .append(");\n")
                }

            val setupBlock =
                if (isReusable) {
                    """
                    |    const auto cancellable = get_or_create_cancellable_continuation<$tp0>(kxs_typed_cont);
                    |    try {
                    |        if constexpr (std::is_invocable_v<KxsBlock&, decltype(cancellable)>) {
                    |            (void)$blockParamName(cancellable);
                    |        } else {
                    |            (void)$blockParamName();
                    |        }
                    |    } catch (...) {
                    |        cancellable->release_claimed_reusable_continuation();
                    |        throw;
                    |    }
                    """.trimMargin()
                } else {
                    """
                    |    const auto cancellable = std::make_shared<CancellableContinuationImpl<$tp0>>(kxs_typed_cont, MODE_CANCELLABLE);
                    |    cancellable->init_cancellability();
                    |    if constexpr (std::is_invocable_v<KxsBlock&, decltype(cancellable)>) {
                    |        (void)$blockParamName(cancellable);
                    |    } else {
                    |        (void)$blockParamName();
                    |    }
                    """.trimMargin()
                }

            val postDefShared =
                buildString {
                    append(defTps)
                    append("inline void* ")
                        .append(sym.cppName)
                        .append("(KxsBlock&& ")
                        .append(blockParamName)
                        .append(", std::shared_ptr<kotlin::coroutines::Continuation<void*>> completion) {\n")
                    append("    auto kxs_intercepted = kotlin::coroutines::intrinsics::intercepted(std::move(completion));\n")
                    append("    std::shared_ptr<kotlin::coroutines::Continuation<").append(tp0).append(">> kxs_typed_cont;\n")
                    append("    if constexpr (std::is_same_v<").append(tp0).append(", void*>) {\n")
                    append("        kxs_typed_cont = kxs_intercepted;\n")
                    append("    } else {\n")
                    append("        kxs_typed_cont = std::make_shared<kotlin::coroutines::FunctionalContinuation<").append(tp0).append(">>(\n")
                    append("            kxs_intercepted ? kxs_intercepted->get_context() : nullptr,\n")
                    append("            [kxs_intercepted](kotlin::coroutines::Result<").append(tp0).append("> r) {\n")
                    append("                if (!kxs_intercepted) return;\n")
                    append("                if (r.is_failure()) {\n")
                    append("                    kxs_intercepted->resume_with(kotlin::coroutines::Result<void*>::failure(r.exception_or_null()));\n")
                    append(
                        "                } else if constexpr (std::is_same_v<$tp0, " +
                            "kotlinx::coroutines::Unit> || std::is_void_v<$tp0>) {\n",
                    )
                    append("                    kxs_intercepted->resume_with(kotlin::coroutines::Result<void*>::success(nullptr));\n")
                    append("                } else {\n")
                    append("                    kxs_intercepted->resume_with(kotlin::coroutines::Result<void*>::success(new ").append(tp0).append("(r.get_or_throw())));\n")
                    append("                }\n")
                    append("            });\n")
                    append("    }\n")
                    append(setupBlock).append('\n')
                    append("    auto kxs_res = cancellable->get_result();\n")
                    append("    if constexpr (std::is_same_v<decltype(kxs_res), void*>) {\n")
                    append("        return kxs_res;\n")
                    append("    } else if constexpr (std::is_same_v<decltype(kxs_res), std::any>) {\n")
                    append("        if (const auto* kxs_ptr = std::any_cast<void*>(&kxs_res)) {\n")
                    append("            return *kxs_ptr;\n")
                    append("        }\n")
                    append("        if (!kxs_res.has_value()) return nullptr;\n")
                    append(
                        "        if constexpr (std::is_same_v<$tp0, " +
                            "kotlinx::coroutines::Unit> || std::is_void_v<$tp0>) {\n",
                    )
                    append("            return nullptr;\n")
                    append("        } else {\n")
                    append("            if (const auto* kxs_val = std::any_cast<").append(tp0).append(">(&kxs_res)) {\n")
                    append("                return new ").append(tp0).append("(*kxs_val);\n")
                    append("            }\n")
                    append("            return nullptr;\n")
                    append("        }\n")
                    append("    } else {\n")
                    append("        return nullptr;\n")
                    append("    }\n")
                    append("}\n")
                }

            val postDefRaw =
                buildString {
                    append(defTps)
                    append("inline void* ")
                        .append(sym.cppName)
                        .append("(KxsBlock&& ")
                        .append(blockParamName)
                        .append(", kotlin::coroutines::Continuation<void*>* completion) {\n")
                    append("    return ")
                        .append(sym.cppName)
                        .append('<')
                        .append(tp0)
                        .append(">(std::forward<KxsBlock>(")
                        .append(blockParamName)
                        .append("), std::shared_ptr<kotlin::coroutines::Continuation<void*>>(completion, [](kotlin::coroutines::Continuation<void*>*) {}));\n")
                    append("}\n")
                }

            postIncludeHeaderBodies.add(postDefShared)
            postIncludeHeaderBodies.add(postDefRaw)
            return declHeader
        }

        val baseParams = buildFunctionParameterList(sym, includeDefaults = isHeaderMode)
        val explicitParams =
            if (baseParams.isEmpty()) {
                "std::shared_ptr<Continuation<$contElemType>> completion"
            } else {
                "$baseParams, std::shared_ptr<Continuation<$contElemType>> completion"
            }

        if (isHeaderMode) {
            return buildString {
                append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                append(renderTemplatePrefix(sym.typeParams, indent))
                append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                append(indent)
                    .append("void* ")
                    .append(sym.cppName)
                    .append('(')
                    .append(explicitParams)
                    .append(')')
                if (isTemplate) {
                    val emitter = CppExprAndStmtEmitter(bundle, unitReturnExprOverride = "nullptr")
                    emitter.bindLocalName(leaf.uContParamKotlinName, "completion")
                    for (vp in sym.valueParams) emitter.bindValueParam(vp)
                    val lambdaBody = stream.frames[leaf.lambdaBodyFrameIndex]
                    val renderedBody =
                        if (lambdaBody.nodeTypeId == KtNodeTypes.BLOCK_ID) {
                            emitter.emitBlock(lambdaBody, indent = indent, isValueBlock = true, resultTargetVar = null)
                        } else {
                            emitter.emitBlockOrExprBody(lambdaBody, isExpressionBody = true, isVoidReturn = false, indent = indent)
                        }
                    append(' ').append(renderedBody).append('\n')
                } else {
                    append(";\n")
                }
                append('\n')
                append(renderTemplatePrefix(sym.typeParams, indent))
                append(indent).append("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]\n")
                append(indent)
                    .append("__attribute__((error(\"")
                    .append(sym.cppName)
                    .append("() requires suspend lowering; use ")
                    .append(sym.cppName)
                    .append("(completion) at an explicit ABI boundary\")))\n")
                append(indent)
                    .append(authoringRetType)
                    .append(' ')
                    .append(sym.cppName)
                    .append('(')
                    .append(baseParams)
                    .append(");\n")
            }
        }

        if (isTemplate) return ""
        val emitter = CppExprAndStmtEmitter(bundle, unitReturnExprOverride = "nullptr")
        emitter.bindLocalName(leaf.uContParamKotlinName, "completion")
        for (vp in sym.valueParams) emitter.bindValueParam(vp)
        val lambdaBody = stream.frames[leaf.lambdaBodyFrameIndex]
        val renderedBody =
            if (lambdaBody.nodeTypeId == KtNodeTypes.BLOCK_ID) {
                emitter.emitBlock(lambdaBody, indent = indent, isValueBlock = true, resultTargetVar = null)
            } else {
                emitter.emitBlockOrExprBody(lambdaBody, isExpressionBody = true, isVoidReturn = false, indent = indent)
            }
        val qualName = if (qualifierPrefix.isEmpty()) sym.cppName else "$qualifierPrefix::${sym.cppName}"
        return buildString {
            append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = false))
            append(indent)
                .append("void* ")
                .append(qualName)
                .append('(')
                .append(
                    buildFunctionParameterList(sym, includeDefaults = false).let {
                        if (it.isEmpty()) {
                            "std::shared_ptr<Continuation<$contElemType>> completion"
                        } else {
                            "$it, std::shared_ptr<Continuation<$contElemType>> completion"
                        }
                    },
                ).append(") ")
                .append(renderedBody)
                .append('\n')
        }
    }

    private fun renderFunctionBody(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
        activeTypeParams: Set<String>,
        isVoidReturn: Boolean,
        indent: String,
        collapsedVarianceTp: Pair<String, String>? = null,
    ): String {
        val liveFlags = stream.frames[sym.frameIndex].flags
        val rawInlineCpp = bundle.ffiPlan.inlineCppByFrame[sym.frameIndex]
        if (rawInlineCpp != null && sym.bodyFrameIndex == null) {
            return buildString {
                append("{\n")
                if (PacketFlags.hasFlag(liveFlags, PacketFlags.GC_FOREIGN_THREAD_ENTRY)) {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    append(indent).append("    Kotlin_initRuntimeIfNeeded();\n")
                    append(indent).append("    CalledFromNativeGuard kxs_native_guard{};\n")
                }
                for (line in rawInlineCpp.trimIndent().lines()) {
                    if (line.isBlank()) append('\n') else append(indent).append("    ").append(line).append('\n')
                }
                append(indent).append('}')
            }
        }

        val bodyIdx = sym.bodyFrameIndex ?: return "{}"
        val bodyFrame = stream.frames[bodyIdx]
        val hasRecv = sym.receiverTypeFrameIndex != null
        val (extRecvCls, extRecvPtr, extRecvSimple) = resolveExtensionReceiverContext(sym)
        val fnRetType =
            sym.returnTypeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN).removePrefix("[[noreturn]] ")
            }
        val emitter =
            CppExprAndStmtEmitter(
                bundle = bundle,
                activeTypeParams = activeTypeParams,
                enclosingClass = enclosingClass,
                receiverSubstitution = if (hasRecv) "self" else null,
                extensionReceiverClass = extRecvCls,
                extensionReceiverIsPointer = extRecvPtr,
                extensionReceiverSimpleName = extRecvSimple,
                isVoidReturnContext = isVoidReturn,
                enclosingFunctionReturnType = fnRetType,
            )
        for (vp in sym.valueParams) {
            emitter.bindValueParam(vp)
        }

        val entryGuards = ArrayList<String>()
        if (collapsedVarianceTp != null) {
            entryGuards.add("using ${collapsedVarianceTp.first} = ${collapsedVarianceTp.second};")
        }
        if (PacketFlags.hasFlag(liveFlags, PacketFlags.GC_FOREIGN_THREAD_ENTRY)) {
            includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
            entryGuards.add("Kotlin_initRuntimeIfNeeded();")
            entryGuards.add("CalledFromNativeGuard kxs_native_guard{};")
        }

        val coreBody =
            emitter.emitBlockOrExprBody(
                bodyFrame = bodyFrame,
                isExpressionBody = sym.isExpressionBody,
                isVoidReturn = isVoidReturn,
                indent = if (sym.cAbiSymbolName != null) "$indent    " else indent,
                extraEntryLines = entryGuards,
            )

        if (sym.cAbiSymbolName != null) {
            includes.addSystem("exception")
            return buildString {
                append("{\n")
                append(indent)
                    .append("    try ")
                    .append(coreBody)
                    .append(" catch (...) {\n")
                append(indent).append("        std::terminate();\n")
                append(indent).append("    }\n")
                append(indent).append('}')
            }
        }
        return coreBody
    }

    private fun computeFirstTrailingDefaultIndex(sym: KtFunctionSymbol): Int {
        var idx = sym.valueParams.size
        while (idx > 0 && sym.valueParams[idx - 1].defaultExprFrameIndex != null) {
            idx -= 1
        }
        return idx
    }

    private data class StarProjectionPlan(
        val synthesizedTypeParams: List<KtTypeParamInfo>,
        val receiverStarNames: List<String>?,
        val paramStarNamesByIdx: Map<Int, List<String>>,
    )

    private fun buildStarProjectionPlan(sym: KtFunctionSymbol): StarProjectionPlan {
        val synth = ArrayList<KtTypeParamInfo>()
        var nextIdx = 0
        fun allocNames(count: Int): List<String>? {
            if (count <= 0) return null
            val list = ArrayList<String>(count)
            repeat(count) {
                val name = "KxsStar${nextIdx++}"
                list.add(name)
                synth.add(KtTypeParamInfo(name = name, isReified = false, upperBoundFrameIndex = null, frameIndex = sym.frameIndex))
            }
            return list
        }
        val recvStars =
            sym.receiverTypeFrameIndex?.let { allocNames(countNonVoidPtrStarProjections(stream.frames[it])) }
        val paramStars = LinkedHashMap<Int, List<String>>()
        for ((pIdx, vp) in sym.valueParams.withIndex()) {
            val tfIdx = vp.typeFrameIndex ?: continue
            allocNames(countNonVoidPtrStarProjections(stream.frames[tfIdx]))?.let { paramStars[pIdx] = it }
        }
        return StarProjectionPlan(synth, recvStars, paramStars)
    }

    private fun applyStarNamesToRenderedType(
        renderedType: String,
        starNames: List<String>?,
    ): String {
        if (starNames.isNullOrEmpty()) return renderedType
        var result = renderedType
        for (starName in starNames) {
            val match = STAR_STD_ANY_IN_ANGLE_REGEX.find(result) ?: break
            val anyEnd = match.range.last + 1
            val anyStart = anyEnd - match.groups[1]!!.value.length
            result = result.replaceRange(anyStart, anyEnd, starName)
        }
        return result
    }

    private fun countNonVoidPtrStarProjections(frame: PacketFrame): Int {
        if (frame.nodeTypeId == KtNodeTypes.USER_TYPE_ID) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, frame)
            val argList = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
            var count = 0
            if (argList != null) {
                for (proj in FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.TYPE_PROJECTION_ID)) {
                    val innerType = FrameAstHelpers.firstChildFrame(stream, proj, KtNodeTypes.TYPE_REFERENCE_ID)
                    count +=
                        if (innerType == null) {
                            if (simple !in CppTypeTranslator.VOID_PTR_STAR_PROJECTION_TYPES) 1 else 0
                        } else {
                            countNonVoidPtrStarProjections(innerType)
                        }
                }
            }
            return count
        }
        var sum = 0
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            sum += countNonVoidPtrStarProjections(child)
        }
        return sum
    }

    private fun buildFunctionParameterList(
        sym: KtFunctionSymbol,
        includeDefaults: Boolean,
        starPlan: StarProjectionPlan? = null,
        unusedParamCppNames: Set<String> = emptySet(),
    ): String {
        val parts = ArrayList<String>(sym.valueParams.size + 1)
        if (sym.receiverTypeFrameIndex != null) {
            val rawRType = bundle.typeTranslator.translateTypeFrame(stream.frames[sym.receiverTypeFrameIndex], CppTypePosition.EXTENSION_RECEIVER)
            val rType = applyStarNamesToRenderedType(rawRType, starPlan?.receiverStarNames)
            val passType =
                if (rType in VALUE_PASS_CPP_TYPES || rType.endsWith("&") || rType.endsWith("*")) {
                    rType
                } else if (isMutableExtensionReceiverCppType(rType)) {
                    "$rType&"
                } else {
                    "const $rType&"
                }
            val unusedSelfAttr = if ("self" in unusedParamCppNames) "[[maybe_unused]] " else ""
            parts.add("$unusedSelfAttr$passType self")
        }
        val emitter = CppExprAndStmtEmitter(bundle, inDefaultParameterExpr = true)
        val firstTrailingDefaultIdx = computeFirstTrailingDefaultIndex(sym)
        for ((pIdx, vp) in sym.valueParams.withIndex()) {
            val unstarredRawT =
                vp.typeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[it],
                        CppTypePosition.PARAMETER,
                        stream.frames[vp.frameIndex].flags,
                    )
                } ?: "auto"
            val rawT = applyStarNamesToRenderedType(unstarredRawT, starPlan?.paramStarNamesByIdx?.get(pIdx))
            val paramT =
                if (vp.isVararg) {
                    includes.addSystem("vector")
                    "const std::vector<$rawT>&"
                } else {
                    rawT
                }
            val defaultSuffix =
                if (includeDefaults && pIdx >= firstTrailingDefaultIdx && vp.defaultExprFrameIndex != null) {
                    val rawDef = emitter.emitExpression(stream.frames[vp.defaultExprFrameIndex])
                    val coercedDef =
                        if (rawDef == "nullptr" && paramT.startsWith("std::optional<")) {
                            includes.addSystem("optional")
                            "std::nullopt"
                        } else {
                            rawDef
                        }
                    " = $coercedDef"
                } else {
                    ""
                }
            val unusedAttr = if (vp.cppName in unusedParamCppNames) "[[maybe_unused]] " else ""
            parts.add("$unusedAttr$paramT ${vp.cppName}$defaultSuffix")
            emitter.bindValueParam(vp)
        }
        return parts.joinToString(", ")
    }

    private fun renderPrefixDefaultOverloads(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
        retType: String,
        isVoidReturn: Boolean,
        methodName: String,
        isConstMethod: Boolean,
        indent: String,
        effectiveTypeParams: List<KtTypeParamInfo> = sym.typeParams,
        starPlan: StarProjectionPlan? = null,
    ): String {
        val firstTrailingDefaultIdx = computeFirstTrailingDefaultIndex(sym)
        var endDefaultExclusive = firstTrailingDefaultIdx
        while (endDefaultExclusive > 0 && sym.valueParams[endDefaultExclusive - 1].defaultExprFrameIndex == null) {
            endDefaultExclusive -= 1
        }
        if (endDefaultExclusive <= 0) return ""
        var startDefaultInclusive = endDefaultExclusive
        while (startDefaultInclusive > 0 && sym.valueParams[startDefaultInclusive - 1].defaultExprFrameIndex != null) {
            startDefaultInclusive -= 1
        }
        if (startDefaultInclusive >= endDefaultExclusive) return ""

        val isStaticOnObject =
            enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT

        return buildString {
            for (keepPrefixCount in startDefaultInclusive until endDefaultExclusive) {
                val sigParts = ArrayList<String>()
                val callArgs = ArrayList<String>()
                if (sym.receiverTypeFrameIndex != null) {
                    val rawRType = bundle.typeTranslator.translateTypeFrame(stream.frames[sym.receiverTypeFrameIndex], CppTypePosition.EXTENSION_RECEIVER)
                    val rType = applyStarNamesToRenderedType(rawRType, starPlan?.receiverStarNames)
                    val passType =
                        if (rType in VALUE_PASS_CPP_TYPES || rType.endsWith("&") || rType.endsWith("*")) {
                            rType
                        } else if (isMutableExtensionReceiverCppType(rType)) {
                            "$rType&"
                        } else {
                            "const $rType&"
                        }
                    sigParts.add("$passType self")
                    callArgs.add("self")
                }
                val (extRecvCls, extRecvPtr, extRecvSimple) = resolveExtensionReceiverContext(sym)
                val emitter =
                    CppExprAndStmtEmitter(
                        bundle = bundle,
                        enclosingClass = enclosingClass,
                        receiverSubstitution = if (sym.receiverTypeFrameIndex != null) "self" else null,
                        extensionReceiverClass = extRecvCls,
                        extensionReceiverIsPointer = extRecvPtr,
                        extensionReceiverSimpleName = extRecvSimple,
                        inDefaultParameterExpr = true,
                    )
                for ((pIdx, vp) in sym.valueParams.withIndex()) {
                    val unstarredRawT =
                        vp.typeFrameIndex?.let {
                            bundle.typeTranslator.translateTypeFrame(
                                stream.frames[it],
                                CppTypePosition.PARAMETER,
                                stream.frames[vp.frameIndex].flags,
                            )
                        } ?: "auto"
                    val rawT = applyStarNamesToRenderedType(unstarredRawT, starPlan?.paramStarNamesByIdx?.get(pIdx))
                    if (pIdx in keepPrefixCount until endDefaultExclusive) {
                        val defFrame = stream.frames[vp.defaultExprFrameIndex!!]
                        val rawDef = emitter.emitExpression(defFrame)
                        callArgs.add(if (rawDef == "nullptr" && rawT.startsWith("std::optional<")) "std::nullopt" else rawDef)
                    } else {
                        val paramT = if (vp.isVararg) "const std::vector<$rawT>&" else rawT
                        val defSuffix =
                            if (pIdx >= firstTrailingDefaultIdx && vp.defaultExprFrameIndex != null) {
                                val rawDef = emitter.emitExpression(stream.frames[vp.defaultExprFrameIndex])
                                val coercedDef =
                                    if (rawDef == "nullptr" && paramT.startsWith("std::optional<")) {
                                        includes.addSystem("optional")
                                        "std::nullopt"
                                    } else {
                                        rawDef
                                    }
                                " = $coercedDef"
                            } else {
                                ""
                            }
                        sigParts.add("$paramT ${vp.cppName}$defSuffix")
                        includes.addSystem("utility")
                        val fwdArg = if (paramT.endsWith("&") || paramT in VALUE_PASS_CPP_TYPES) vp.cppName else "std::move(${vp.cppName})"
                        callArgs.add(fwdArg)
                        emitter.bindValueParam(vp)
                    }
                }
                val isNoreturn = retType.startsWith("[[noreturn]] ")
                val cleanRetType = retType.removePrefix("[[noreturn]] ")
                append('\n')
                append(renderTemplatePrefix(effectiveTypeParams, indent))
                append(indent)
                if (isNoreturn) append("[[noreturn]] ")
                if (isStaticOnObject) {
                    append("static ")
                } else if (enclosingClass == null && effectiveTypeParams.isEmpty()) {
                    append("inline ")
                }
                val tArgs = if (effectiveTypeParams.isNotEmpty()) "<${effectiveTypeParams.joinToString(", ") { it.name }}>" else ""
                val retPrefix = if (isVoidReturn) "" else "return "
                append(cleanRetType)
                    .append(' ')
                    .append(methodName)
                    .append('(')
                    .append(sigParts.joinToString(", "))
                    .append(')')
                if (isConstMethod) append(" const")
                append(" { ")
                    .append(retPrefix)
                    .append(methodName)
                    .append(tArgs)
                    .append('(')
                    .append(callArgs.joinToString(", "))
                    .append("); }\n")
            }
        }
    }

    private fun resolveCppFunctionOrOperatorName(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
    ): String {
        if (!sym.isOperator) return sym.cppName
        val isStaticOnObj =
            enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT
        val liveFlags = stream.frames[sym.frameIndex].flags
        val allowSubscriptOrCallOp =
            sym.receiverTypeFrameIndex == null &&
                enclosingClass != null &&
                enclosingClass.kind != KtClassKind.INTERFACE &&
                !isStaticOnObj &&
                !PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) &&
                !PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) &&
                !PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE)
        return when (sym.kotlinName) {
            "get" -> if (allowSubscriptOrCallOp) "operator[]" else sym.cppName
            "invoke" -> if (allowSubscriptOrCallOp) "operator()" else sym.cppName
            "plus" -> if (isStaticOnObj) sym.cppName else "operator+"
            "minus" -> if (isStaticOnObj) sym.cppName else "operator-"
            "times" -> if (isStaticOnObj) sym.cppName else "operator*"
            "div" -> if (isStaticOnObj) sym.cppName else "operator/"
            "rem" -> if (isStaticOnObj) sym.cppName else "operator%"
            "unaryMinus" -> if (isStaticOnObj) sym.cppName else "operator-"
            "unaryPlus" -> if (isStaticOnObj) sym.cppName else "operator+"
            "not" -> if (isStaticOnObj) sym.cppName else "operator!"
            else -> sym.cppName
        }
    }

    private fun shouldMarkMethodConst(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
    ): Boolean {
        if (enclosingClass == null || enclosingClass.isInner) return false
        if ((sym.kotlinName == "toString" || sym.kotlinName == "hashCode") && sym.valueParams.isEmpty()) {
            return enclosingClass.kind != KtClassKind.INTERFACE &&
                enclosingClass.kind != KtClassKind.COMPANION_OBJECT &&
                (enclosingClass.kind != KtClassKind.OBJECT_SINGLETON || PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OVERRIDE))
        }
        val fnFrame = stream.frames[sym.frameIndex]
        if (FrameAstHelpers.hasModifierKeyword(stream, fnFrame, KtTokens.ACTUAL_MODIFIER_ID) ||
            FrameAstHelpers.hasModifierKeyword(stream, fnFrame, KtTokens.EXPECT_MODIFIER_ID)
        ) {
            return false
        }
        if (enclosingClass.kind == KtClassKind.INTERFACE ||
            enclosingClass.kind == KtClassKind.SEALED_CLASS ||
            enclosingClass.kind == KtClassKind.OBJECT_SINGLETON ||
            enclosingClass.kind == KtClassKind.COMPANION_OBJECT ||
            PacketFlags.hasFlag(enclosingClass.flags, PacketFlags.MOD_OPEN) ||
            PacketFlags.hasFlag(enclosingClass.flags, PacketFlags.MOD_ABSTRACT)
        ) {
            return false
        }
        if (sym.receiverTypeFrameIndex != null ||
            PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_SUSPEND) ||
            PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OVERRIDE) ||
            PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OPEN) ||
            PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_ABSTRACT)
        ) {
            return false
        }
        val bodyIdx = sym.bodyFrameIndex ?: return false
        // If any field is mutated with `=`, `+=`, etc. inside body, do not mark `const`.
        return !mutatesMemberField(stream.frames[bodyIdx], enclosingClass)
    }

    private fun mutatesMemberField(
        frame: PacketFrame,
        cls: KtClassSymbol,
    ): Boolean {
        if (frame.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val ch = FrameAstHelpers.childFrames(stream, frame)
            if (ch.size >= 3) {
                val opId = FrameAstHelpers.operationTokenId(stream, ch[1])
                if (opId in ASSIGN_OPERATOR_IDS) {
                    val lhsMember = extractAssignedMemberSimpleName(ch[0])
                    if (lhsMember != null) {
                        val isMember =
                            cls.primaryConstructorParams.any { it.name == lhsMember } ||
                                cls.memberPropertyFrameIndices.any { symbols.propertiesByFrame[it]?.kotlinName == lhsMember }
                        if (isMember) return true
                    }
                }
            }
        }
        for (c in FrameAstHelpers.childFrames(stream, frame)) {
            if (mutatesMemberField(c, cls)) return true
        }
        return false
    }

    private fun extractAssignedMemberSimpleName(lhsFrame: PacketFrame): String? =
        when (lhsFrame.nodeTypeId) {
            KtNodeTypes.REFERENCE_EXPRESSION_ID ->
                FrameAstHelpers.directIdentPacket(stream, lhsFrame)?.unescapedName
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                val parts = FrameAstHelpers.childFrames(stream, lhsFrame)
                if (parts.size == 2 && parts[0].nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID) {
                    FrameAstHelpers.directIdentPacket(stream, parts[1])?.unescapedName
                } else {
                    null
                }
            }
            else -> null
        }

    private fun containsWholeWord(
        text: String,
        word: String,
    ): Boolean {
        if (word.isEmpty() || text.isEmpty()) return false
        return Regex("""(?<![A-Za-z0-9_])${Regex.escape(word)}(?![A-Za-z0-9_])""").containsMatchIn(text)
    }

    private fun superDeclaresMethod(
        cls: KtClassSymbol,
        kotlinMethodName: String,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (!visited.add(cls.frameIndex)) return false
        for (stIdx in cls.superTypeFrameIndices) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[stIdx]) ?: continue
            val sameSuper = symbols.classesByName[simple]
            if (sameSuper != null) {
                val declaredOnSuper =
                    sameSuper.memberFunctionFrameIndices.any { mFnIdx ->
                        symbols.functionsByFrame[mFnIdx]?.kotlinName == kotlinMethodName
                    }
                if (declaredOnSuper || superDeclaresMethod(sameSuper, kotlinMethodName, visited)) {
                    return true
                }
            }
            if ((kotlinMethodName == "toString" || kotlinMethodName == "hashCode") && simple in KNOWN_TO_STRING_BASE_CLASSES) {
                return true
            }
        }
        return false
    }

    private fun inferFunctionExpressionBodyReturnType(
        sym: KtFunctionSymbol,
        @Suppress("UNUSED_PARAMETER") enclosingClass: KtClassSymbol?,
        exprFrame: PacketFrame,
    ): String {
        val inferred = inferExpressionBodyReturnType(exprFrame)
        if (inferred != "auto") return inferred
        if (sym.kotlinName == "toString" && sym.valueParams.isEmpty()) {
            includes.addSystem("string")
            return "std::string"
        }
        return "auto"
    }

    private fun inferExpressionBodyReturnType(exprFrame: PacketFrame): String =
        when (exprFrame.nodeTypeId) {
            KtNodeTypes.STRING_TEMPLATE_ID -> {
                includes.addSystem("string")
                "std::string"
            }
            KtNodeTypes.BOOLEAN_CONSTANT_ID,
            KtNodeTypes.IS_EXPRESSION_ID,
            -> "bool"
            KtNodeTypes.INTEGER_CONSTANT_ID -> {
                includes.addSystem("cstdint")
                val pkt = stream.packets.getOrNull(exprFrame.firstPacketIndex) as? SyntaxPacket.LiteralPacket
                if (pkt?.numericWidthBits == 64) "std::int64_t" else "std::int32_t"
            }
            KtNodeTypes.FLOAT_CONSTANT_ID -> "double"
            KtNodeTypes.CHARACTER_CONSTANT_ID -> "char16_t"
            KtNodeTypes.REFERENCE_EXPRESSION_ID ->
                if (FrameAstHelpers.directIdentPacket(stream, exprFrame)?.unescapedName == "Unit") "void" else "auto"
            KtNodeTypes.PARENTHESIZED_ID -> {
                val inner = FrameAstHelpers.childFrames(stream, exprFrame).firstOrNull()
                if (inner != null) inferExpressionBodyReturnType(inner) else "auto"
            }
            KtNodeTypes.IF_ID -> {
                val thenExpr = FrameAstHelpers.firstChildFrame(stream, exprFrame, KtNodeTypes.THEN_ID)?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val elseExpr = FrameAstHelpers.firstChildFrame(stream, exprFrame, KtNodeTypes.ELSE_ID)?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val t1 = thenExpr?.let { inferExpressionBodyReturnType(it) } ?: "auto"
                val t2 = elseExpr?.let { inferExpressionBodyReturnType(it) } ?: "auto"
                if (t1 != "auto" && t1 == t2) t1 else "auto"
            }
            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val ch = FrameAstHelpers.childFrames(stream, exprFrame)
                val opId = if (ch.size >= 3) FrameAstHelpers.operationTokenId(stream, ch[1]) else null
                if (opId in BOOL_BINARY_OPERATOR_IDS) {
                    "bool"
                } else if (opId == KtTokens.PLUS_ID &&
                    ch.size >= 3 &&
                    (inferExpressionBodyReturnType(ch[0]) == "std::string" || inferExpressionBodyReturnType(ch[2]) == "std::string")
                ) {
                    includes.addSystem("string")
                    "std::string"
                } else {
                    "auto"
                }
            }
            else -> "auto"
        }

    private fun isUsedAsSuperTypeInSameFile(cls: KtClassSymbol): Boolean =
        symbols.classesByFrame.values.any { other ->
            other.frameIndex != cls.frameIndex &&
                !FrameAstHelpers.hasModifierKeyword(stream, stream.frames[other.frameIndex], KtTokens.EXPECT_MODIFIER_ID) &&
                other.superTypeFrameIndices.any { stIdx ->
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[stIdx]) == cls.kotlinName
                }
        }

    private fun isReferencedByNonExpectInSameFile(cls: KtClassSymbol): Boolean {
        if (cls.kotlinName == "CancellationException" ||
            cls.kotlinName == "SynchronizedObject" ||
            cls.kotlinName == "ReentrantLock"
        ) {
            return false
        }
        return symbols.topLevelDeclarationFrames.any { top ->
            top.frameIndex != cls.frameIndex &&
                !FrameAstHelpers.hasModifierKeyword(stream, top, KtTokens.EXPECT_MODIFIER_ID) &&
                frameMentionsIdentifier(top, cls.kotlinName)
        }
    }

    private fun isOmittedExpectClass(cls: KtClassSymbol): Boolean {
        val declFrame = stream.frames[cls.frameIndex]
        if (!FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID)) return false
        if (cls.kind == KtClassKind.ANNOTATION_CLASS) return false
        return !isUsedAsSuperTypeInSameFile(cls) && !isReferencedByNonExpectInSameFile(cls)
    }

    private fun shouldEmitPrivateClassOnlyInCpp(cls: KtClassSymbol): Boolean {
        if (bundle.isDocExample) return false
        val hasCppBodies =
            symbols.functionsByFrame.values.any { PacketFlags.hasFlag(stream.frames[it.frameIndex].flags, PacketFlags.EMIT_IN_CPP_BODY) } ||
                symbols.classesByFrame.values.any { PacketFlags.hasFlag(it.flags, PacketFlags.EMIT_IN_CPP_BODY) } ||
                symbols.propertiesByFrame.values.any { PacketFlags.hasFlag(stream.frames[it.frameIndex].flags, PacketFlags.EMIT_IN_CPP_BODY) }
        if (!hasCppBodies) return false
        if (cls.enclosingClassFrameIndex != null) return false
        if (!PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_PRIVATE)) return false
        if (cls.typeParams.isNotEmpty()) return false
        if (cls.kind != KtClassKind.REGULAR_CLASS && cls.kind != KtClassKind.DATA_CLASS) return false
        for (top in symbols.topLevelDeclarationFrames) {
            if (top.frameIndex == cls.frameIndex) continue
            val emittedInHeader =
                when (top.nodeTypeId) {
                    KtNodeTypes.CLASS_ID,
                    KtNodeTypes.OBJECT_DECLARATION_ID,
                    -> {
                        val otherCls = symbols.classesByFrame[top.frameIndex]
                        otherCls != null && !PacketFlags.hasFlag(otherCls.flags, PacketFlags.MOD_PRIVATE)
                    }
                    KtNodeTypes.FUN_ID -> {
                        val fnSym = symbols.functionsByFrame[top.frameIndex]
                        fnSym != null &&
                            (
                                PacketFlags.hasFlag(fnSym.flags, PacketFlags.EMIT_IN_HPP_INLINE_BODY) ||
                                    fnSym.returnTypeFrameIndex?.let { frameMentionsIdentifier(stream.frames[it], cls.kotlinName) } == true ||
                                    fnSym.receiverTypeFrameIndex?.let { frameMentionsIdentifier(stream.frames[it], cls.kotlinName) } == true ||
                                    fnSym.valueParams.any { vp ->
                                        vp.typeFrameIndex?.let { frameMentionsIdentifier(stream.frames[it], cls.kotlinName) } == true
                                    }
                            )
                    }
                    KtNodeTypes.PROPERTY_ID -> {
                        val propSym = symbols.propertiesByFrame[top.frameIndex]
                        propSym != null && PacketFlags.hasFlag(propSym.flags, PacketFlags.EMIT_IN_HPP_INLINE_BODY)
                    }
                    KtNodeTypes.TYPEALIAS_ID -> true
                    else -> false
                }
            if (emittedInHeader && frameMentionsIdentifier(top, cls.kotlinName)) {
                return false
            }
        }
        return true
    }

    internal fun renderPrivateClassDefinitionForCpp(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
    ): String =
        when (cls.kind) {
            KtClassKind.DATA_CLASS -> renderDataClass(declFrame, cls, "", "")
            else -> renderGeneralClass(declFrame, cls, "", "")
        }

    private fun renderClassOrObject(
        declFrame: PacketFrame,
        qualifierPrefix: String,
        indent: String,
        outOfLineOwnerQual: String? = null,
    ): String {
        val cls = symbols.classesByFrame[declFrame.frameIndex] ?: return ""
        if (isHeaderMode && shouldEmitPrivateClassOnlyInCpp(cls)) {
            return ""
        }
        val isExpectCls = FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID)
        if (isExpectCls && cls.kind != KtClassKind.ANNOTATION_CLASS && !isUsedAsSuperTypeInSameFile(cls)) {
            includes.hasExpectDeclarations = true
            if (!isHeaderMode) return ""
            val prefix = renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true)
            return if (isReferencedByNonExpectInSameFile(cls)) {
                buildString {
                    append(prefix)
                    append(renderTemplatePrefix(cls.typeParams, indent))
                    append(indent).append("class ").append(cls.cppName).append(";\n")
                }
            } else {
                "$prefix$indent// expect class ${cls.cppName}\n"
            }
        }
        return when (cls.kind) {
            KtClassKind.ANNOTATION_CLASS ->
                if (isHeaderMode) {
                    val prefix = renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true)
                    "$prefix$indent// Annotation marker ${cls.cppName}\n"
                } else {
                    ""
                }
            KtClassKind.SIMPLE_ENUM ->
                if (isHeaderMode) renderSimpleEnum(declFrame, cls, indent) else ""
            KtClassKind.RICH_ENUM ->
                renderRichEnum(declFrame, cls, indent)
            KtClassKind.VALUE_CLASS ->
                if (isHeaderMode) renderValueClass(declFrame, cls, indent, outOfLineOwnerQual) else ""
            KtClassKind.DATA_CLASS ->
                if (isHeaderMode) renderDataClass(declFrame, cls, qualifierPrefix, indent, outOfLineOwnerQual) else ""
            KtClassKind.INTERFACE,
            KtClassKind.SEALED_CLASS,
            KtClassKind.REGULAR_CLASS,
            KtClassKind.OBJECT_SINGLETON,
            KtClassKind.COMPANION_OBJECT,
            -> renderGeneralClass(declFrame, cls, qualifierPrefix, indent, outOfLineOwnerQual)
        }
    }

    private fun renderSimpleEnum(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        indent: String,
    ): String =
        buildString {
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(indent).append("enum class ").append(cls.cppName).append(" {\n")
            val inner = "$indent    "
            for ((idx, entry) in cls.enumEntries.withIndex()) {
                val entryFrame = stream.frames[entry.frameIndex]
                for (c in FrameAstHelpers.extractLeadingDeclarationComments(stream, entryFrame)) {
                    val formatted =
                        FrameAstHelpers.formatCommentForCpp(
                            comment = c,
                            indent = inner,
                            knownFunctionNames = bundle.knownFunctionNames,
                            allowFenceTranslation = !bundle.isDocExample,
                        )
                    if (formatted.isNotEmpty()) {
                        if (idx > 0) append('\n')
                        append(formatted).append('\n')
                    }
                }
                append(inner).append(entry.cppName)
                if (idx < cls.enumEntries.lastIndex) append(',')
                append('\n')
            }
            append(indent).append("};\n")
        }

    private fun renderRichEnum(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        indent: String,
    ): String {
        if (!isHeaderMode) return ""
        val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls, receiverSubstitution = "self")
        return buildString {
            append(renderSimpleEnum(declFrame, cls, indent))

            // Emit `constexpr` accessors for any primary constructor properties on the rich enum
            for ((pIdx, cp) in cls.primaryConstructorParams.withIndex()) {
                if (!cp.isVal && !cp.isVar) continue
                val retT =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                    } ?: "auto"
                append('\n')
                append(indent)
                    .append("inline constexpr ")
                    .append(retT)
                    .append(' ')
                    .append(cp.cppName)
                    .append('(')
                    .append(cls.cppName)
                    .append(" self) {\n")
                append(indent).append("    switch (self) {\n")
                for (entry in cls.enumEntries) {
                    val argVal =
                        entry.constructorArgsFrameIndex?.let { argListIdx ->
                            val vas = FrameAstHelpers.childFramesOfType(stream, stream.frames[argListIdx], KtNodeTypes.VALUE_ARGUMENT_ID)
                            val va = vas.getOrNull(pIdx)
                            val expr = va?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                            expr?.let { emitter.emitExpression(it) }
                        } ?: "{}"
                    append(indent)
                        .append("        case ")
                        .append(cls.cppName)
                        .append("::")
                        .append(entry.cppName)
                        .append(": return ")
                        .append(argVal)
                        .append(";\n")
                }
                append(indent).append("    }\n")
                includes.addSystem("utility")
                append(indent).append("    std::unreachable();\n")
                append(indent).append("}\n")
            }

            // Emit free helper functions for rich enum properties and methods (`CoroutineStart.hpp` pattern)
            for (mPropIdx in cls.memberPropertyFrameIndices) {
                val propSym = symbols.propertiesByFrame[mPropIdx] ?: continue
                val propFrame = stream.frames[mPropIdx]
                val retT =
                    propSym.returnTypeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                    } ?: "bool"
                val getterBody =
                    propSym.getterFrameIndex?.let { gIdx ->
                        val gFrame = stream.frames[gIdx]
                        FrameAstHelpers.firstChildFrame(stream, gFrame, KtNodeTypes.BLOCK_ID)
                            ?: FrameAstHelpers.extractInitializerAfterEquals(stream, gFrame)
                    } ?: propSym.initializerFrameIndex?.let { stream.frames[it] }
                if (getterBody != null) {
                    append('\n')
                    append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
                    val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                    val bodyStr = emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = indent)
                    append(indent)
                        .append("inline ")
                        .append(retT)
                        .append(' ')
                        .append(propSym.cppName)
                        .append('(')
                        .append(cls.cppName)
                        .append(" self) ")
                        .append(bodyStr)
                        .append('\n')
                }
            }

            for (mFnIdx in cls.memberFunctionFrameIndices) {
                val fnSym = symbols.functionsByFrame[mFnIdx] ?: continue
                val fnFrame = stream.frames[mFnIdx]
                val bodyIdx = fnSym.bodyFrameIndex ?: continue
                val retT =
                    fnSym.returnTypeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                    } ?: "void"
                val extraParams = buildFunctionParameterList(fnSym, includeDefaults = true)
                val fullParams =
                    if (extraParams.isEmpty()) "${cls.cppName} self" else "${cls.cppName} self, $extraParams"
                val fnEmitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls, receiverSubstitution = "self")
                for (vp in fnSym.valueParams) fnEmitter.bindLocalName(vp.name, vp.cppName)
                append('\n')
                append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                append(renderTemplatePrefix(fnSym.typeParams, indent))
                val fnCallName = if (fnSym.kotlinName == "invoke") "invoke" else fnSym.cppName
                append(indent)
                    .append("inline ")
                    .append(retT)
                    .append(' ')
                    .append(fnCallName)
                    .append('(')
                    .append(fullParams)
                    .append(") ")
                    .append(fnEmitter.emitBlockOrExprBody(stream.frames[bodyIdx], fnSym.isExpressionBody, retT == "void", indent))
                    .append('\n')
            }
        }
    }

    private fun renderValueClass(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        indent: String,
        outOfLineOwnerQual: String? = null,
    ): String {
        val underlyingParam = cls.primaryConstructorParams.firstOrNull()
        val valType =
            underlyingParam?.typeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
            } ?: "std::int64_t"
        val propName = underlyingParam?.cppName ?: "value"
        val fieldName = CppNamingPolicy.toPrivateFieldName(propName)
        val headName = if (outOfLineOwnerQual != null) "$outOfLineOwnerQual::${cls.cppName}" else cls.cppName
        val constexprKw = if (valType in VALUE_PASS_CPP_TYPES) "constexpr " else ""
        val defaultSuffix =
            if (underlyingParam?.defaultExprFrameIndex != null) {
                val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls, inDefaultParameterExpr = true)
                val rawDef = emitter.emitExpression(stream.frames[underlyingParam.defaultExprFrameIndex])
                val coerced =
                    if (rawDef == "nullptr" && valType.startsWith("std::optional<")) {
                        includes.addSystem("optional")
                        "std::nullopt"
                    } else if (rawDef == "nullptr" && valType == "std::any") {
                        "{}"
                    } else {
                        rawDef
                    }
                " = $coerced"
            } else {
                ""
            }
        includes.addSystem("utility")

        return buildString {
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(cls.typeParams, indent))
            append(indent).append("class ").append(headName).append(" {\n")
            append(indent).append("private:\n")
            append(indent)
                .append("    ")
                .append(valType)
                .append(' ')
                .append(fieldName)
                .append(";\n")
            append(indent).append("public:\n")
            append(indent)
                .append("    ")
                .append(constexprKw)
                .append("explicit ")
                .append(cls.cppName)
                .append('(')
                .append(valType)
                .append(' ')
                .append(propName)
                .append(defaultSuffix)
                .append(") : ")
                .append(fieldName)
                .append("(std::move(")
                .append(propName)
                .append(")) {}\n")
            append(indent)
                .append("    ")
                .append(constexprKw)
                .append("const ")
                .append(valType)
                .append("& ")
                .append(propName)
                .append("() const noexcept { return ")
                .append(fieldName)
                .append("; }\n")
            if (isDefaultComparableCppType(valType)) {
                append(indent)
                    .append("    ")
                    .append(constexprKw)
                    .append("bool operator==(const ")
                    .append(cls.cppName)
                    .append("&) const = default;\n")
            } else {
                includes.addSystem("memory")
                append(indent)
                    .append("    bool operator==(const ")
                    .append(cls.cppName)
                    .append("& other) const noexcept { return std::addressof(")
                    .append(fieldName)
                    .append(") == std::addressof(other.")
                    .append(fieldName)
                    .append("); }\n")
            }
            for (mFnIdx in cls.memberFunctionFrameIndices) {
                val rendered = renderFunction(stream.frames[mFnIdx], cls, cls.cppName, "$indent    ")
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            append(indent).append("};\n")
        }
    }

    private fun renderDataClass(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        qualifierPrefix: String,
        indent: String,
        outOfLineOwnerQual: String? = null,
    ): String {
        includes.addSystem("string")
        includes.addSystem("utility")
        val fullQual = if (qualifierPrefix.isEmpty()) cls.cppName else "$qualifierPrefix::${cls.cppName}"
        val headName = if (outOfLineOwnerQual != null) "$outOfLineOwnerQual::${cls.cppName}" else cls.cppName
        val inheritance = renderInheritanceClause(cls)
        val inner = "$indent    "

        return buildString {
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(cls.typeParams, indent))
            append(indent)
                .append("class ")
                .append(headName)
                .append(inheritance)
                .append(" {\n")
            append(indent).append("public:\n")

            // Companion object with supertypes (e.g. `Key : CoroutineContext.KeyTyped<E>`) must precede constructor initializers.
            cls.companionFrameIndex?.let { compIdx ->
                val compSym = symbols.classesByFrame[compIdx]
                if (compSym != null && compSym.superTypeFrameIndices.isNotEmpty()) {
                    val renderedComp = renderClassOrObject(stream.frames[compIdx], fullQual, inner)
                    if (renderedComp.isNotBlank()) append('\n').append(renderedComp)
                }
            }

            val paramTypes = ArrayList<String>(cls.primaryConstructorParams.size)
            for (cp in cls.primaryConstructorParams) {
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                    } ?: "std::any"
                paramTypes.add(t)
                if (cp.isVal || cp.isVar) {
                    val mutPrefix = if (cp.isVar) "" else ""
                    append(inner)
                        .append(mutPrefix)
                        .append(t)
                        .append(' ')
                        .append(cp.cppName)
                        .append(";\n")
                }
            }

            if (cls.primaryConstructorParams.isNotEmpty()) {
                val explicitKw = if (cls.primaryConstructorParams.size == 1) "explicit " else ""
                val emitter = CppExprAndStmtEmitter(bundle)
                val ctorParams =
                    cls.primaryConstructorParams.mapIndexed { i, cp ->
                        val defIdx = cp.defaultExprFrameIndex
                        val def =
                            if (defIdx != null) {
                                val rawDef = emitter.emitExpression(stream.frames[defIdx])
                                val coercedDef =
                                    if (rawDef == "nullptr" && paramTypes[i].startsWith("std::optional<")) {
                                        includes.addSystem("optional")
                                        "std::nullopt"
                                    } else {
                                        rawDef
                                    }
                                " = $coercedDef"
                            } else {
                                ""
                            }
                        "${paramTypes[i]} ${cp.cppName}_in$def"
                    }
                val inits = buildConstructorInitializerList(cls, suffix = "_in")
                append('\n')
                    .append(inner)
                    .append(explicitKw)
                    .append(cls.cppName)
                    .append('(')
                    .append(ctorParams.joinToString(", "))
                    .append(") : ")
                    .append(inits.joinToString(", "))
                    .append(" {}\n")
            }

            val propParams = cls.primaryConstructorParams.filter { it.isVal || it.isVar }
            val canDefaultEq =
                cls.superTypeFrameIndices.all { trIdx ->
                    val baseSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx])
                    val baseCls = baseSimple?.let { lookupClassInScope(cls, it) }
                    baseCls != null && baseCls.kind == KtClassKind.SEALED_CLASS && baseCls.superTypeFrameIndices.isEmpty()
                } &&
                    cls.primaryConstructorParams.withIndex().all { (idx, cp) ->
                        !(cp.isVal || cp.isVar) || isDefaultComparableCppType(paramTypes[idx])
                    }
            if (canDefaultEq) {
                append(inner).append("bool operator==(const ").append(cls.cppName).append("&) const = default;\n")
            } else {
                val fieldChecks =
                    cls.primaryConstructorParams.mapIndexedNotNull { idx, cp ->
                        if (!cp.isVal && !cp.isVar) return@mapIndexedNotNull null
                        val t = paramTypes[idx]
                        if (isDefaultComparableCppType(t)) {
                            "this->${cp.cppName} == other.${cp.cppName}"
                        } else {
                            includes.addSystem("memory")
                            "std::addressof(this->${cp.cppName}) == std::addressof(other.${cp.cppName})"
                        }
                    }
                val eqExpr = if (fieldChecks.isEmpty()) "true" else fieldChecks.joinToString(" && ")
                append(inner)
                    .append("bool operator==(const ")
                    .append(cls.cppName)
                    .append("& other) const { return ")
                    .append(eqExpr)
                    .append("; }\n")
            }

            // `component1()`..`componentN()` and `copy(...)`
            for ((idx, cp) in propParams.withIndex()) {
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                    } ?: "auto"
                append(inner)
                    .append("const ")
                    .append(t)
                    .append("& component")
                    .append(idx + 1)
                    .append("() const noexcept { return ")
                    .append(cp.cppName)
                    .append("; }\n")
            }
            if (propParams.isNotEmpty()) {
                val copyParams =
                    propParams.map { cp ->
                        val t =
                            cp.typeFrameIndex?.let {
                                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                            } ?: "auto"
                        "std::optional<$t> ${cp.cppName}_opt = std::nullopt"
                    }
                includes.addSystem("optional")
                val copyArgs =
                    propParams.joinToString(", ") { cp ->
                        "${cp.cppName}_opt.has_value() ? std::move(*${cp.cppName}_opt) : this->${cp.cppName}"
                    }
                append(inner)
                    .append(cls.cppName)
                    .append(" copy(")
                    .append(copyParams.joinToString(", "))
                    .append(") const { return ")
                    .append(cls.cppName)
                    .append('(')
                    .append(copyArgs)
                    .append("); }\n")
            }

            for (nestedIdx in cls.memberClassFrameIndices) {
                val rendered = renderClassOrObject(stream.frames[nestedIdx], fullQual, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            cls.companionFrameIndex?.let { compIdx ->
                val compSym = symbols.classesByFrame[compIdx]
                if (compSym != null && compSym.superTypeFrameIndices.isEmpty()) {
                    for (cpIdx in compSym.memberPropertyFrameIndices) {
                        val rendered = renderProperty(stream.frames[cpIdx], compSym, inner)
                        if (rendered.isNotBlank()) append('\n').append(rendered)
                    }
                    for (cfIdx in compSym.memberFunctionFrameIndices) {
                        val rendered = renderFunction(stream.frames[cfIdx], compSym, fullQual, inner)
                        if (rendered.isNotBlank()) append('\n').append(rendered)
                    }
                }
            }
            for (mPropIdx in cls.memberPropertyFrameIndices) {
                val rendered = renderProperty(stream.frames[mPropIdx], cls, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            for (mFnIdx in cls.memberFunctionFrameIndices) {
                val rendered = renderFunction(stream.frames[mFnIdx], cls, fullQual, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            append(indent).append("};\n")
        }
    }

    private data class DelegatedInterfaceInfo(
        val fieldCppName: String,
        val propCppType: String,
        val delegateExprFrameIndex: Int,
        val superSimpleName: String,
        val superCppType: String,
        val isPointerField: Boolean,
    )

    private fun extractDelegatedInterfaces(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
    ): List<DelegatedInterfaceInfo> {
        val superList = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.SUPER_TYPE_LIST_ID) ?: return emptyList()
        val out = ArrayList<DelegatedInterfaceInfo>()
        var idx = 0
        for (entry in FrameAstHelpers.childFramesOfType(stream, superList, KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY_ID)) {
            val tr = FrameAstHelpers.firstChildFrame(stream, entry, KtNodeTypes.TYPE_REFERENCE_ID) ?: continue
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tr).orEmpty()
            if (simple == "MutableMap" || simple == "Map") continue
            val expr = FrameAstHelpers.childFrames(stream, entry).firstOrNull { it.nodeTypeId != KtNodeTypes.TYPE_REFERENCE_ID } ?: continue
            val propCppType = bundle.typeTranslator.translateTypeFrame(tr, CppTypePosition.VALUE)
            val superCppType = bundle.typeTranslator.translateTypeFrame(tr, CppTypePosition.TYPE_ARGUMENT)
            val isPtr = propCppType.endsWith("*") || propCppType.startsWith("std::shared_ptr<")
            out.add(
                DelegatedInterfaceInfo(
                    fieldCppName = "kxs_delegate_${idx++}_",
                    propCppType = propCppType,
                    delegateExprFrameIndex = expr.frameIndex,
                    superSimpleName = simple,
                    superCppType = superCppType,
                    isPointerField = isPtr,
                ),
            )
        }
        return out
    }

    private fun isPrivateOrBackedOverrideCtorParam(
        cls: KtClassSymbol,
        cp: KtValueParamInfo,
    ): Boolean =
        (cp.isVal || cp.isVar) &&
            (PacketFlags.hasFlag(stream.frames[cp.frameIndex].flags, PacketFlags.MOD_PRIVATE) || isBackedOverrideCtorParam(cls, cp))

    private fun isPrivateOrBackedOverrideMemberProp(
        cls: KtClassSymbol,
        pIdx: Int,
    ): Boolean {
        val p = symbols.propertiesByFrame[pIdx] ?: return false
        if (p.isConst || p.getterFrameIndex != null) return false
        return PacketFlags.hasFlag(p.flags, PacketFlags.MOD_PRIVATE) || isBackedOverrideMemberProp(cls, stream.frames[pIdx], p)
    }

    private fun renderGeneralClass(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        qualifierPrefix: String,
        indent: String,
        outOfLineOwnerQual: String? = null,
    ): String {
        val fullQual = if (qualifierPrefix.isEmpty()) cls.cppName else "$qualifierPrefix::${cls.cppName}"
        val headName = if (outOfLineOwnerQual != null) "$outOfLineOwnerQual::${cls.cppName}" else cls.cppName
        val currentScopeQualForNested = headName
        val emitCtorInCpp = PacketFlags.hasFlag(cls.flags, PacketFlags.EMIT_IN_CPP_BODY)
        val outerCls =
            if (cls.isInner && cls.enclosingClassFrameIndex != null) {
                symbols.classesByFrame[cls.enclosingClassFrameIndex]
            } else {
                null
            }
        val totalCtorParams = (if (outerCls != null) 1 else 0) + cls.primaryConstructorParams.size
        val hasNonEmptySuperArgs =
            cls.superCallArgsBySuperIndex.values.any { argListIdx ->
                FrameAstHelpers.childFramesOfType(stream, stream.frames[argListIdx], KtNodeTypes.VALUE_ARGUMENT_ID).isNotEmpty()
            }
        val delegatedInterfaces = extractDelegatedInterfaces(declFrame, cls)
        val hasPrimaryCtor =
            totalCtorParams > 0 ||
                hasNonEmptySuperArgs ||
                cls.initBlockFrameIndices.isNotEmpty() ||
                (delegatedInterfaces.isNotEmpty() && emitCtorInCpp)

        if (!isHeaderMode) {
            return buildString {
                if (emitCtorInCpp && hasPrimaryCtor) {
                    includes.addSystem("utility")
                    val initList = buildConstructorInitializerList(cls, suffix = "_value", delegatedInterfaces = delegatedInterfaces)
                    val ctorBody = renderPrimaryConstructorBody(cls, suffix = "_value", indent = indent)
                    val initAndBodyText = "${initList.joinToString(", ")}\n$ctorBody"
                    val sigParts = ArrayList<String>(totalCtorParams)
                    if (outerCls != null) {
                        val outerUnused = if (!containsWholeWord(initAndBodyText, "outer_value")) "[[maybe_unused]] " else ""
                        sigParts.add("$outerUnused${outerCls.cppName}* outer_value")
                    }
                    for (cp in cls.primaryConstructorParams) {
                        val t =
                            cp.typeFrameIndex?.let {
                                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                            } ?: "std::any"
                        val paramName = "${cp.cppName}_value"
                        val unusedAttr = if (!cp.isVal && !cp.isVar && !containsWholeWord(initAndBodyText, paramName)) "[[maybe_unused]] " else ""
                        sigParts.add("$unusedAttr$t $paramName")
                    }
                    val ctorSigParams = sigParts.joinToString(", ")
                    append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = false))
                    append(indent)
                        .append(fullQual)
                        .append("::")
                        .append(cls.cppName)
                        .append('(')
                        .append(ctorSigParams)
                        .append(')')
                    if (initList.isNotEmpty()) {
                        append(" : ").append(initList.joinToString(", "))
                    }
                    append(' ').append(ctorBody).append('\n')
                }

                for (nestedIdx in cls.memberClassFrameIndices) {
                    val sub = renderClassOrObject(stream.frames[nestedIdx], fullQual, indent)
                    if (sub.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(sub)
                    }
                }
                cls.companionFrameIndex?.let { compIdx ->
                    val compSym = symbols.classesByFrame[compIdx]
                    if (compSym != null) {
                        if (compSym.superTypeFrameIndices.isNotEmpty()) {
                            val sub = renderClassOrObject(stream.frames[compIdx], fullQual, indent)
                            if (sub.isNotBlank()) {
                                if (isNotEmpty()) append('\n')
                                append(sub)
                            }
                        } else {
                            for (cpIdx in compSym.memberPropertyFrameIndices) {
                                val sub = renderProperty(stream.frames[cpIdx], compSym, indent)
                                if (sub.isNotBlank()) {
                                    if (isNotEmpty()) append('\n')
                                    append(sub)
                                }
                            }
                            for (cfIdx in compSym.memberFunctionFrameIndices) {
                                val sub = renderFunction(stream.frames[cfIdx], compSym, fullQual, indent)
                                if (sub.isNotBlank()) {
                                    if (isNotEmpty()) append('\n')
                                    append(sub)
                                }
                            }
                        }
                    }
                }
                for (mPropIdx in cls.memberPropertyFrameIndices) {
                    val sub = renderProperty(stream.frames[mPropIdx], cls, indent)
                    if (sub.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(sub)
                    }
                }
                for (mFnIdx in cls.memberFunctionFrameIndices) {
                    val sub = renderFunction(stream.frames[mFnIdx], cls, fullQual, indent)
                    if (sub.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(sub)
                    }
                }
            }
        }

        val inheritance = renderInheritanceClause(cls)
        val delegatedMap = extractDelegatedMapTypes(cls)
        val inner = "$indent    "
        val isPolymorphicBase =
            cls.kind == KtClassKind.INTERFACE ||
                cls.kind == KtClassKind.SEALED_CLASS ||
                PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_OPEN) ||
                PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_ABSTRACT)

        val isCancellableFlowMarker =
            cls.enclosingClassFrameIndex == null &&
                cls.kotlinName == "CancellableFlow" &&
                symbols.namespaceSegments == listOf("kotlinx", "coroutines", "flow")
        val isEventLoopImplPlatformGuard =
            !bundle.isDocExample &&
                cls.enclosingClassFrameIndex == null &&
                cls.kotlinName == "EventLoopImplPlatform"
        val needsPredeclaredCancellableFlow =
            cls.enclosingClassFrameIndex == null &&
                cls.kotlinName == "AbstractFlow" &&
                symbols.namespaceSegments == listOf("kotlinx", "coroutines", "flow")
        val isSegmentClassDecl =
            !bundle.isDocExample &&
                cls.enclosingClassFrameIndex == null &&
                cls.kotlinName == "Segment" &&
                symbols.namespaceSegments == listOf("kotlinx", "coroutines", "internal")

        return buildString {
            if (isSegmentClassDecl) {
                includes.addSystem("cstdint")
                includes.addSystem("exception")
                includes.addSystem("memory")
                includes.addProject("kotlinx/coroutines/CoroutineContext.hpp")
                includes.addProject("kotlinx/coroutines/CancellableContinuationImpl.hpp")
                append(indent).append("template <>\n")
                append(indent).append("class Segment<void*> : public virtual kotlinx::coroutines::NotCompleted {\n")
                append(indent).append("public:\n")
                append(inner).append("virtual ~Segment() = default;\n")
                append(inner).append("virtual std::int32_t number_of_slots() const = 0;\n")
                append(inner)
                    .append("virtual void on_cancellation(std::int32_t index, std::exception_ptr cause, ")
                    .append("std::shared_ptr<kotlin::coroutines::CoroutineContext> context) = 0;\n")
                append(indent).append("};\n\n")
            }
            if (needsPredeclaredCancellableFlow) {
                append("#ifndef KXS_CANCELLABLE_FLOW_DEFINED\n")
                append("#define KXS_CANCELLABLE_FLOW_DEFINED\n")
                append("template <typename T>\nclass CancellableFlow : public virtual Flow<T> {\npublic:\n    virtual ~CancellableFlow() = default;\n};\n")
                append("#endif\n\n")
            }
            if (isCancellableFlowMarker) {
                append("#ifndef KXS_CANCELLABLE_FLOW_DEFINED\n")
                append("#define KXS_CANCELLABLE_FLOW_DEFINED\n")
            }
            if (isEventLoopImplPlatformGuard) {
                append("#ifndef KXS_EXPECT_SUPER_CLASS_EventLoopImplPlatform\n")
                append("#define KXS_EXPECT_SUPER_CLASS_EventLoopImplPlatform\n")
            }
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(cls.typeParams, indent))
            append(indent)
                .append("class ")
                .append(headName)
                .append(inheritance)
                .append(" {\n")

            // Private fields first (`snake_case_`)
            val privateCtorProps =
                cls.primaryConstructorParams.filter { isPrivateOrBackedOverrideCtorParam(cls, it) }
            val privateMemberProps =
                cls.memberPropertyFrameIndices.filter { isPrivateOrBackedOverrideMemberProp(cls, it) }
            if (outerCls != null ||
                delegatedInterfaces.isNotEmpty() ||
                privateCtorProps.isNotEmpty() ||
                privateMemberProps.isNotEmpty() ||
                delegatedMap != null
            ) {
                append(indent).append("private:\n")
                if (outerCls != null) {
                    append(inner)
                        .append(outerCls.cppName)
                        .append("* outer_;\n")
                }
                for (di in delegatedInterfaces) {
                    if (hasPrimaryCtor) {
                        append(inner)
                            .append(di.propCppType)
                            .append(' ')
                            .append(di.fieldCppName)
                            .append(";\n")
                    } else {
                        val delEmitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls)
                        val exprCode = delEmitter.emitExpression(stream.frames[di.delegateExprFrameIndex])
                        append(inner)
                            .append(di.propCppType)
                            .append(' ')
                            .append(di.fieldCppName)
                            .append(" = ")
                            .append(exprCode)
                            .append(";\n")
                    }
                }
                if (delegatedMap != null) {
                    includes.addSystem("memory")
                    includes.addSystem("utility")
                    includes.addSystem("vector")
                    append(inner)
                        .append("std::vector<std::pair<")
                        .append(delegatedMap.first)
                        .append(", ")
                        .append(delegatedMap.second)
                        .append(">> delegated_entries_{};\n")
                }
                for (cp in privateCtorProps) {
                    val t =
                        cp.typeFrameIndex?.let {
                            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                        } ?: "std::any"
                    val constQual = if (!cp.isVar) "const " else ""
                    append(inner)
                        .append(constQual)
                        .append(t)
                        .append(' ')
                        .append(CppNamingPolicy.toPrivateFieldName(cp.name))
                        .append(";\n")
                }
                for (pIdx in privateMemberProps) {
                    append(renderProperty(stream.frames[pIdx], cls, inner))
                }
            }

            append(indent).append("public:\n")
            if (isPolymorphicBase) {
                append(inner).append("virtual ~").append(cls.cppName).append("() = default;\n")
            }

            val isSingletonWithObj =
                cls.kind == KtClassKind.OBJECT_SINGLETON ||
                    (cls.kind == KtClassKind.COMPANION_OBJECT && cls.superTypeFrameIndices.isNotEmpty())
            if (isSingletonWithObj && !PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_ABSTRACT)) {
                val storageKw = if (cls.annotations.any { it.simpleName == "ThreadLocal" }) "thread_local" else "static"
                append(inner).append("static ").append(cls.cppName).append("& instance() {\n")
                append("$inner    $storageKw ${cls.cppName} inst{};\n")
                append(inner).append("    return inst;\n")
                append(inner).append("}\n")
                if (!bundle.isDocExample) {
                    includes.addSystem("memory")
                    includes.addSystem("type_traits")
                    append(inner)
                        .append("static std::shared_ptr<")
                        .append(cls.cppName)
                        .append("> shared_instance() { return std::shared_ptr<")
                        .append(cls.cppName)
                        .append(">(std::shared_ptr<void>{}, std::addressof(instance())); }\n")
                    append(inner)
                        .append("template <typename KxsBase, typename = std::enable_if_t<std::is_base_of_v<KxsBase, ")
                        .append(cls.cppName)
                        .append(">>> operator std::shared_ptr<KxsBase>() const { return std::shared_ptr<KxsBase>(std::shared_ptr<void>{}, const_cast<")
                        .append(cls.cppName)
                        .append("*>(this)); }\n")
                }
            }

            if (delegatedMap != null) {
                append(inner)
                    .append(delegatedMap.second)
                    .append("& operator[](const ")
                    .append(delegatedMap.first)
                    .append("& key) {\n")
                append(inner).append("    for (auto& kv : delegated_entries_) {\n")
                append(inner).append("        if constexpr (requires { kv.first == key; }) {\n")
                append(inner).append("            if (kv.first == key) return kv.second;\n")
                append(inner).append("        } else if constexpr (requires { kv.first.symbol == key.symbol; }) {\n")
                append(inner).append("            if (kv.first.symbol == key.symbol) return kv.second;\n")
                append(inner).append("        } else {\n")
                append(inner).append("            if (std::addressof(kv.first) == std::addressof(key)) return kv.second;\n")
                append(inner).append("        }\n")
                append(inner).append("    }\n")
                append(inner).append("    delegated_entries_.emplace_back(key, ").append(delegatedMap.second).append("{});\n")
                append(inner).append("    return delegated_entries_.back().second;\n")
                append(inner).append("}\n")
            }

            // Public constructor properties (`const std::string symbol;` on `Symbol`)
            val publicCtorProps =
                cls.primaryConstructorParams.filter {
                    (it.isVal || it.isVar) && !isPrivateOrBackedOverrideCtorParam(cls, it)
                }
            for (cp in publicCtorProps) {
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                    } ?: "std::any"
                val constQual = if (!cp.isVar) "const " else ""
                append(inner)
                    .append(constQual)
                    .append(t)
                    .append(' ')
                    .append(cp.cppName)
                    .append(";\n")
            }

            // Backed `override` virtual getters/setters for primary constructor params and member properties
            for (cp in cls.primaryConstructorParams) {
                if (!isBackedOverrideCtorParam(cls, cp)) continue
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                    } ?: "std::any"
                val fieldName = CppNamingPolicy.toPrivateFieldName(cp.name)
                val getterName = resolveOverridePropertyGetterName(cls, cp.name, cp.cppName)
                append(inner)
                    .append(t)
                    .append(' ')
                    .append(getterName)
                    .append("() const override { return ")
                    .append(fieldName)
                    .append("; }\n")
                if (cp.isVar) {
                    val paramT =
                        cp.typeFrameIndex?.let {
                            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER)
                        } ?: t
                    val clean = cp.cppName.removeSuffix("_")
                    append(inner)
                        .append("void set_")
                        .append(clean)
                        .append('(')
                        .append(paramT)
                        .append(" value) override { ")
                        .append(fieldName)
                        .append(" = std::move(value); }\n")
                }
            }
            for (pIdx in cls.memberPropertyFrameIndices) {
                val prop = symbols.propertiesByFrame[pIdx] ?: continue
                val propFrame = stream.frames[pIdx]
                if (!isBackedOverrideMemberProp(cls, propFrame, prop)) continue
                val fieldName = CppNamingPolicy.toPrivateFieldName(prop.kotlinName)
                val af = bundle.atomicPlan.atomicFieldsByPropFrame[pIdx]
                val t =
                    if (af != null) {
                        resolveSharedPtrAtomicElemType(af, cls)
                            ?: af.explicitElementTypeFrameIndex?.let {
                                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                            } ?: af.inferredElementCppType ?: "void*"
                    } else {
                        prop.returnTypeFrameIndex?.let {
                            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                        } ?: prop.initializerFrameIndex?.let {
                            inferExpressionBodyReturnType(stream.frames[it]).takeIf { r -> r != "auto" }
                        } ?: "std::any"
                    }
                val getterName = resolveOverridePropertyGetterName(cls, prop.kotlinName, prop.cppName)
                val readExpr = if (af != null) "$fieldName.load(std::memory_order_seq_cst)" else fieldName
                append(inner)
                    .append(t)
                    .append(' ')
                    .append(getterName)
                    .append("() const override { return ")
                    .append(readExpr)
                    .append("; }\n")
                if (prop.isVar) {
                    val clean = prop.cppName.removeSuffix("_")
                    val writeStmt =
                        if (af != null) {
                            "$fieldName.store(std::move(value), std::memory_order_seq_cst);"
                        } else {
                            "$fieldName = std::move(value);"
                        }
                    append(inner)
                        .append("void set_")
                        .append(clean)
                        .append('(')
                        .append(t)
                        .append(" value) override { ")
                        .append(writeStmt)
                        .append(" }\n")
                }
            }

            // Companion object with supertypes (e.g. `Key : CoroutineContext.KeyTyped<E>`) must precede constructor initializers.
            cls.companionFrameIndex?.let { compIdx ->
                val compSym = symbols.classesByFrame[compIdx]
                if (compSym != null && compSym.superTypeFrameIndices.isNotEmpty()) {
                    val renderedComp = renderClassOrObject(stream.frames[compIdx], fullQual, inner)
                    if (renderedComp.isNotBlank()) append('\n').append(renderedComp)
                }
            }

            // Primary constructor declaration / inline body
            if (hasPrimaryCtor) {
                val ctorVis = if (cls.kind == KtClassKind.SEALED_CLASS) "${indent}protected:\n$inner" else "\n$inner"
                val explicitKw = if (totalCtorParams == 1) "explicit " else ""
                val inlineInitList =
                    if (!emitCtorInCpp) {
                        includes.addSystem("utility")
                        buildConstructorInitializerList(cls, suffix = "_value", delegatedInterfaces = delegatedInterfaces)
                    } else {
                        null
                    }
                val inlineCtorBody =
                    if (!emitCtorInCpp) {
                        renderPrimaryConstructorBody(cls, suffix = "_value", indent = inner)
                    } else {
                        null
                    }
                val inlineInitAndBodyText =
                    if (inlineInitList != null && inlineCtorBody != null) {
                        "${inlineInitList.joinToString(", ")}\n$inlineCtorBody"
                    } else {
                        null
                    }
                val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls, inDefaultParameterExpr = true)
                val sigParts = ArrayList<String>(totalCtorParams)
                if (outerCls != null) {
                    val outerUnused =
                        if (inlineInitAndBodyText != null && !containsWholeWord(inlineInitAndBodyText, "outer_value")) {
                            "[[maybe_unused]] "
                        } else {
                            ""
                        }
                    sigParts.add("$outerUnused${outerCls.cppName}* outer_value")
                }
                for (cp in cls.primaryConstructorParams) {
                    val t =
                        cp.typeFrameIndex?.let {
                            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                        } ?: "std::any"
                    val defIdx = cp.defaultExprFrameIndex
                    val def =
                        if (defIdx != null) {
                            val rawDef = emitter.emitExpression(stream.frames[defIdx])
                            val coercedDef =
                                if (rawDef == "nullptr" && t.startsWith("std::optional<")) {
                                    includes.addSystem("optional")
                                    "std::nullopt"
                                } else {
                                    rawDef
                                }
                            " = $coercedDef"
                        } else {
                            ""
                        }
                    val paramName = "${cp.cppName}_value"
                    val unusedAttr =
                        if (inlineInitAndBodyText != null && !cp.isVal && !cp.isVar && !containsWholeWord(inlineInitAndBodyText, paramName)) {
                            "[[maybe_unused]] "
                        } else {
                            ""
                        }
                    sigParts.add("$unusedAttr$t $paramName$def")
                }
                val ctorSigParams = sigParts.joinToString(", ")
                append(ctorVis)
                    .append(explicitKw)
                    .append(cls.cppName)
                    .append('(')
                    .append(ctorSigParams)
                    .append(')')
                if (emitCtorInCpp) {
                    append(";\n")
                } else {
                    if (inlineInitList != null && inlineInitList.isNotEmpty()) {
                        append(" : ").append(inlineInitList.joinToString(", "))
                    }
                    append(' ').append(inlineCtorBody ?: "{}").append('\n')
                }
                if (cls.kind == KtClassKind.SEALED_CLASS) {
                    append(inner)
                        .append(cls.cppName)
                        .append("(const ")
                        .append(cls.cppName)
                        .append("&) = default;\n")
                    append(inner)
                        .append(cls.cppName)
                        .append('(')
                        .append(cls.cppName)
                        .append("&&) = default;\n")
                    append(inner)
                        .append(cls.cppName)
                        .append("& operator=(const ")
                        .append(cls.cppName)
                        .append("&) = default;\n")
                    append(inner)
                        .append(cls.cppName)
                        .append("& operator=(")
                        .append(cls.cppName)
                        .append("&&) = default;\n")
                    append(indent).append("public:\n")
                    if (cls.superTypeFrameIndices.isEmpty() && cls.primaryConstructorParams.isEmpty() && cls.memberPropertyFrameIndices.isEmpty()) {
                        append(inner).append("bool operator==(const ").append(cls.cppName).append("&) const = default;\n")
                    }
                }
            } else if (cls.kind == KtClassKind.SEALED_CLASS) {
                append(indent).append("protected:\n")
                append(inner).append(cls.cppName).append("() = default;\n")
                append(inner)
                    .append(cls.cppName)
                    .append("(const ")
                    .append(cls.cppName)
                    .append("&) = default;\n")
                append(inner)
                    .append(cls.cppName)
                    .append('(')
                    .append(cls.cppName)
                    .append("&&) = default;\n")
                append(inner)
                    .append(cls.cppName)
                    .append("& operator=(const ")
                    .append(cls.cppName)
                    .append("&) = default;\n")
                append(inner)
                    .append(cls.cppName)
                    .append("& operator=(")
                    .append(cls.cppName)
                    .append("&&) = default;\n")
                append(indent).append("public:\n")
                if (cls.superTypeFrameIndices.isEmpty() && cls.memberPropertyFrameIndices.isEmpty()) {
                    append(inner).append("bool operator==(const ").append(cls.cppName).append("&) const = default;\n")
                }
            } else if (!bundle.isDocExample && hasStdRuntimeErrorBase(cls)) {
                append('\n').append(inner).append(cls.cppName).append("() : std::runtime_error(\"\") {}\n")
            }

            // Secondary constructors
            val renderedSecondaryCtors = renderSecondaryConstructors(declFrame, cls, inner)
            if (renderedSecondaryCtors.isNotBlank()) {
                append(renderedSecondaryCtors)
            }

            // Runtime-error exception helper getters
            if (!bundle.isDocExample && hasStdRuntimeErrorBase(cls)) {
                val hasOwnMessage =
                    cls.primaryConstructorParams.any { it.name == "message" } ||
                        cls.memberPropertyFrameIndices.any { symbols.propertiesByFrame[it]?.kotlinName == "message" }
                if (!hasOwnMessage) {
                    includes.addSystem("string")
                    append(inner).append("std::string message() const { return this->what(); }\n")
                }
            }

            // Delegated non-Map interface forwarding methods
            if (delegatedInterfaces.isNotEmpty()) {
                append(renderDelegatedInterfaceForwardingMethods(cls, delegatedInterfaces, inner))
            }
            if (!bundle.isDocExample && cls.kind != KtClassKind.INTERFACE) {
                append(renderSiblingBaseInterfaceForwarding(cls, delegatedInterfaces, inner))
            }
            if (!bundle.isDocExample && cls.superTypeFrameIndices.isNotEmpty()) {
                append(renderOverloadedVirtualUsings(cls, delegatedInterfaces, inner))
            }

            // Nested classes (forward-declare sibling nested classes referenced earlier & defer subclasses of `cls`)
            if (cls.memberClassFrameIndices.size > 1) {
                for ((pos, nestedIdx) in cls.memberClassFrameIndices.withIndex()) {
                    val siblingCls = symbols.classesByFrame[nestedIdx] ?: continue
                    val referencedBefore =
                        cls.memberClassFrameIndices.subList(0, pos).any { earlierIdx ->
                            frameMentionsIdentifier(stream.frames[earlierIdx], siblingCls.kotlinName)
                        }
                    if (referencedBefore && !inheritsFromEnclosing(siblingCls, cls)) {
                        append('\n')
                        append(renderTemplatePrefix(siblingCls.typeParams, inner))
                        val kw =
                            if (siblingCls.kind == KtClassKind.SIMPLE_ENUM || siblingCls.kind == KtClassKind.RICH_ENUM) {
                                "enum class"
                            } else {
                                "class"
                            }
                        append(inner)
                            .append(kw)
                            .append(' ')
                            .append(siblingCls.cppName)
                            .append(";\n")
                    }
                }
            }
            val deferredOutOfLineNested = ArrayList<Int>()
            for (nestedIdx in cls.memberClassFrameIndices) {
                val nestedCls = symbols.classesByFrame[nestedIdx]
                if (nestedCls != null && inheritsFromEnclosing(nestedCls, cls)) {
                    append('\n')
                    append(renderTemplatePrefix(nestedCls.typeParams, inner))
                    val kw =
                        if (nestedCls.kind == KtClassKind.SIMPLE_ENUM || nestedCls.kind == KtClassKind.RICH_ENUM) {
                            "enum class"
                        } else {
                            "class"
                        }
                    append(inner)
                        .append(kw)
                        .append(' ')
                        .append(nestedCls.cppName)
                        .append(";\n")
                    deferredOutOfLineNested.add(nestedIdx)
                } else {
                    val rendered = renderClassOrObject(stream.frames[nestedIdx], fullQual, inner)
                    if (rendered.isNotBlank()) append('\n').append(rendered)
                }
            }

            // Companion object static constants and factories lifted onto the enclosing class
            cls.companionFrameIndex?.let { compIdx ->
                val compSym = symbols.classesByFrame[compIdx]
                if (compSym != null && compSym.superTypeFrameIndices.isEmpty()) {
                    for (cpIdx in compSym.memberPropertyFrameIndices) {
                        val rendered = renderProperty(stream.frames[cpIdx], compSym, inner)
                        if (rendered.isNotBlank()) append('\n').append(rendered)
                    }
                    for (cfIdx in compSym.memberFunctionFrameIndices) {
                        val rendered = renderFunction(stream.frames[cfIdx], compSym, fullQual, inner)
                        if (rendered.isNotBlank()) append('\n').append(rendered)
                    }
                }
            }

            // Public/non-private member properties
            for (pIdx in cls.memberPropertyFrameIndices) {
                if (pIdx in privateMemberProps) continue
                val rendered = renderProperty(stream.frames[pIdx], cls, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }

            // Member functions
            for (mFnIdx in cls.memberFunctionFrameIndices) {
                val rendered = renderFunction(stream.frames[mFnIdx], cls, fullQual, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }

            // Disambiguate diamond virtual `key()` when inheriting both `AbstractCoroutineContextElement` and `ContinuationInterceptor`
            val superNames =
                cls.superTypeFrameIndices.mapNotNull {
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                }
            if ("AbstractCoroutineContextElement" in superNames && "ContinuationInterceptor" in superNames) {
                append('\n')
                    .append(inner)
                    .append("CoroutineContext::Key* key() const override { return AbstractCoroutineContextElement::key(); }\n")
            }
            if (cls.kotlinName == "Symbol" && symbols.namespaceSegments == listOf("kotlinx", "coroutines", "internal")) {
                append('\n')
                    .append(inner)
                    .append("bool operator==(const Symbol& other) const noexcept { return symbol == other.symbol; }\n")
            }
            if (cls.kotlinName == "JobImpl" && cls.enclosingClassFrameIndex == null) {
                includes.addSystem("memory")
                append('\n')
                    .append(inner)
                    .append("static std::shared_ptr<JobImpl> create(std::shared_ptr<Job> parent = nullptr) { return std::make_shared<JobImpl>(std::move(parent)); }\n")
            }
            if (!bundle.isDocExample &&
                isHeaderMode &&
                cls.enclosingClassFrameIndex == null &&
                cls.kind == KtClassKind.INTERFACE
            ) {
                when (cls.kotlinName) {
                    "Job" -> {
                        includes.addSystem("memory")
                        includes.addProject("kotlinx/coroutines/Continuation.hpp")
                        append('\n')
                            .append(inner)
                            .append("virtual void* join([[maybe_unused]] kotlin::coroutines::Continuation<void*>* continuation) { join(); return nullptr; }\n")
                        append(inner)
                            .append("virtual void* join(std::shared_ptr<kotlin::coroutines::Continuation<void*>> continuation) { return join(continuation.get()); }\n")
                    }
                    "Semaphore" -> {
                        includes.addSystem("memory")
                        includes.addProject("kotlinx/coroutines/Continuation.hpp")
                        append('\n')
                            .append(inner)
                            .append("virtual void* acquire([[maybe_unused]] kotlin::coroutines::Continuation<void*>* continuation) { acquire(); return nullptr; }\n")
                        append(inner)
                            .append("virtual void* acquire(std::shared_ptr<kotlin::coroutines::Continuation<void*>> continuation) { return acquire(continuation.get()); }\n")
                    }
                    "Deferred" -> {
                        if (cls.typeParams.size == 1) {
                            val tp = cls.typeParams[0].name
                            includes.addSystem("memory")
                            includes.addSystem("type_traits")
                            includes.addProject("kotlinx/coroutines/Continuation.hpp")
                            append('\n')
                                .append(inner)
                                .append("virtual void* await([[maybe_unused]] kotlin::coroutines::Continuation<void*>* continuation) { ")
                                .append("if constexpr (std::is_void_v<$tp>) { await(); } else { (void)await(); } return nullptr; }\n")
                            append(inner)
                                .append("virtual void* await(std::shared_ptr<kotlin::coroutines::Continuation<void*>> continuation) { return await(continuation.get()); }\n")
                        }
                    }
                }
            }

            append(indent).append("};\n")
            if (isCancellableFlowMarker || isEventLoopImplPlatformGuard) {
                append("#endif\n")
            }

            for (deferredIdx in deferredOutOfLineNested) {
                val rendered =
                    renderClassOrObject(
                        declFrame = stream.frames[deferredIdx],
                        qualifierPrefix = fullQual,
                        indent = indent,
                        outOfLineOwnerQual = currentScopeQualForNested,
                    )
                if (rendered.isNotBlank()) {
                    append('\n').append(rendered)
                }
            }
        }
    }

    private fun inheritsFromEnclosing(
        nestedCls: KtClassSymbol,
        enclosingCls: KtClassSymbol,
    ): Boolean =
        nestedCls.superTypeFrameIndices.any { trIdx ->
            val rawBase = bundle.typeTranslator.translateTypeFrame(stream.frames[trIdx], CppTypePosition.TYPE_ARGUMENT)
            val simpleBase = rawBase.substringBefore('<').substringAfterLast("::").trim()
            simpleBase == enclosingCls.cppName || simpleBase == enclosingCls.kotlinName
        }

    private fun extractDelegatedMapTypes(cls: KtClassSymbol): Pair<String, String>? {
        for (trIdx in cls.delegatedSuperTypeFrameIndices) {
            val trFrame = stream.frames[trIdx]
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, trFrame)
            if (simple == "MutableMap" || simple == "Map") {
                val userType = FrameAstHelpers.firstChildFrame(stream, trFrame, KtNodeTypes.USER_TYPE_ID) ?: continue
                val argList = FrameAstHelpers.firstChildFrame(stream, userType, KtNodeTypes.TYPE_ARGUMENT_LIST_ID) ?: continue
                val projs = FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.TYPE_PROJECTION_ID)
                if (projs.size >= 2) {
                    val kRef = FrameAstHelpers.firstChildFrame(stream, projs[0], KtNodeTypes.TYPE_REFERENCE_ID)
                    val vRef = FrameAstHelpers.firstChildFrame(stream, projs[1], KtNodeTypes.TYPE_REFERENCE_ID)
                    val kCpp = kRef?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) } ?: "std::any"
                    val vCpp = vRef?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) } ?: "std::any"
                    return kCpp to vCpp
                }
            }
        }
        return null
    }

    private fun renderInheritanceClause(cls: KtClassSymbol): String {
        val nonDelegatedSupers =
            cls.superTypeFrameIndices.filterNot { trIdx ->
                if (trIdx !in cls.delegatedSuperTypeFrameIndices) {
                    false
                } else {
                    val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx])
                    simple == "MutableMap" || simple == "Map"
                }
            }
        val bases =
            nonDelegatedSupers
                .map { trIdx ->
                    val (translatedBase, isStdRuntimeError) = bundle.typeTranslator.translateSuperBaseType(stream.frames[trIdx])
                    val rawBase =
                        if (!bundle.isDocExample &&
                            cls.kotlinName == "Segment" &&
                            cls.enclosingClassFrameIndex == null &&
                            symbols.namespaceSegments == listOf("kotlinx", "coroutines", "internal") &&
                            translatedBase == "NotCompleted"
                        ) {
                            "Segment<void*>"
                        } else {
                            translatedBase
                        }
                    val isVirtualInterfaceBase =
                        !isStdRuntimeError &&
                            (cls.kind == KtClassKind.INTERFACE || trIdx !in cls.superCallArgsBySuperIndex) &&
                            !rawBase.startsWith("CoroutineContext::Key")
                    if (isVirtualInterfaceBase) "public virtual $rawBase" else "public $rawBase"
                }.toMutableList()
        val needsSharedThis =
            !bundle.isDocExample &&
                cls.enclosingClassFrameIndex == null &&
                (
                    cls.kotlinName == "CancellableContinuationImpl" ||
                        cls.kotlinName == "ConcurrentLinkedListNode" ||
                        cls.kotlinName == "LockFreeLinkedListNode"
                )
        if (needsSharedThis) {
            includes.addSystem("memory")
            val selfParam =
                if (cls.typeParams.isEmpty()) {
                    cls.cppName
                } else {
                    "${cls.cppName}<${cls.typeParams.joinToString(", ") { it.name }}>"
                }
            bases.add("public std::enable_shared_from_this<$selfParam>")
        }
        if (bases.isEmpty()) return ""
        return " : ${bases.joinToString(", ")}"
    }

    private fun renderPrimaryConstructorBody(
        cls: KtClassSymbol,
        suffix: String,
        indent: String,
    ): String {
        if (cls.initBlockFrameIndices.isEmpty()) return "{}"
        val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls)
        for (cp in cls.primaryConstructorParams) {
            emitter.bindLocalName(cp.name, "${cp.cppName}$suffix")
        }
        val stmts = ArrayList<String>()
        for (initIdx in cls.initBlockFrameIndices) {
            val initFrame = stream.frames[initIdx]
            val blockFrame = FrameAstHelpers.firstChildFrame(stream, initFrame, KtNodeTypes.BLOCK_ID) ?: continue
            val blockText = emitter.emitBlock(blockFrame, indent = indent, isValueBlock = false, resultTargetVar = null)
            val innerBody = blockText.removePrefix("{").removeSuffix("}").trim('\n')
            if (innerBody.isNotBlank()) {
                stmts.add(innerBody)
            }
        }
        if (stmts.isEmpty()) return "{}"
        return "{\n${stmts.joinToString("\n")}\n$indent}"
    }

    private fun bodyReferencesLaterTopLevelDecl(
        bodyFrame: PacketFrame,
        anchorDeclFrameIndex: Int,
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
    ): Boolean {
        if (bodyFrame.firstPacketIndex > bodyFrame.lastPacketIndex) return false
        val shadowedNames = HashSet<String>()
        sym.valueParams.forEach { shadowedNames.add(it.name) }
        sym.typeParams.forEach { shadowedNames.add(it.name) }
        enclosingClass?.typeParams?.forEach { shadowedNames.add(it.name) }
        enclosingClass?.primaryConstructorParams?.forEach {
            if (it.isVal || it.isVar) shadowedNames.add(it.name)
        }
        enclosingClass?.memberPropertyFrameIndices?.forEach { pIdx ->
            symbols.propertiesByFrame[pIdx]?.let { shadowedNames.add(it.kotlinName) }
        }
        for (idx in bodyFrame.firstPacketIndex..bodyFrame.lastPacketIndex) {
            val pkt = stream.packets[idx]
            if (pkt is SyntaxPacket.IdentPacket) {
                val name = pkt.unescapedName
                if (name in shadowedNames) continue
                val cls = symbols.classesByName[name]
                if (cls != null && cls.enclosingClassFrameIndex == null && cls.frameIndex > anchorDeclFrameIndex) {
                    return true
                }
                val prop = symbols.propertiesByName[name]
                if (prop != null && prop.enclosingClassFrameIndex == null && prop.frameIndex > anchorDeclFrameIndex) {
                    return true
                }
                val fns = symbols.functionsByName[name]
                if (fns != null && fns.any { it.enclosingClassFrameIndex == null && it.frameIndex > anchorDeclFrameIndex }) {
                    return true
                }
                if (symbols.typeAliasesByFrame.values.any { it.kotlinName == name && it.frameIndex > anchorDeclFrameIndex }) {
                    return true
                }
            }
        }
        return false
    }

    private fun frameMentionsIdentifier(
        frame: PacketFrame,
        identName: String,
    ): Boolean {
        if (frame.firstPacketIndex > frame.lastPacketIndex) return false
        for (idx in frame.firstPacketIndex..frame.lastPacketIndex) {
            val pkt = stream.packets[idx]
            if (pkt is SyntaxPacket.IdentPacket && pkt.unescapedName == identName) {
                return true
            }
        }
        return false
    }

    private fun resolveExtensionReceiverContext(sym: KtFunctionSymbol): Triple<KtClassSymbol?, Boolean, String?> =
        resolveExtensionReceiverFrameContext(sym.receiverTypeFrameIndex)

    private fun resolveExtensionReceiverFrameContext(rIdx: Int?): Triple<KtClassSymbol?, Boolean, String?> {
        if (rIdx == null) return Triple(null, false, null)
        val rFrame = stream.frames[rIdx]
        val rCppType = bundle.typeTranslator.translateTypeFrame(rFrame, CppTypePosition.EXTENSION_RECEIVER)
        val rawSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, rFrame)
        val resolvedSimple = rawSimple?.let { bundle.symbols.importAliases[it] ?: it }
        val rCls = rawSimple?.let { symbols.classesByName[it] } ?: resolvedSimple?.let { symbols.classesByName[it] }
        val strippedR = rCppType.removePrefix("const ").removeSuffix("&").trim()
        val rIsPtr =
            strippedR.endsWith("*") ||
                strippedR.startsWith("std::shared_ptr<") ||
                strippedR.startsWith("std::unique_ptr<")
        return Triple(rCls, rIsPtr, resolvedSimple ?: rawSimple)
    }

    private fun lookupClassInScope(
        anchorCls: KtClassSymbol?,
        kotlinName: String,
    ): KtClassSymbol? {
        var cur = anchorCls
        while (cur != null) {
            if (cur.kotlinName == kotlinName) return cur
            for (nIdx in cur.memberClassFrameIndices) {
                val sub = symbols.classesByFrame[nIdx]
                if (sub != null && sub.kotlinName == kotlinName) return sub
            }
            cur = cur.enclosingClassFrameIndex?.let { symbols.classesByFrame[it] }
        }
        return symbols.classesByName[kotlinName]
    }

    private fun isDefaultComparableCppType(cppType: String): Boolean {
        val t = cppType.trim()
        return !t.contains("std::any") &&
            !t.contains("std::function<") &&
            !t.contains("SuspendFunction<") &&
            !t.contains("std::exception_ptr")
    }

    private fun referencesNonFieldCtorParam(
        enclosingClass: KtClassSymbol?,
        exprFrame: PacketFrame,
    ): Boolean {
        if (enclosingClass == null) return false
        return enclosingClass.primaryConstructorParams.any { cp ->
            !cp.isVal && !cp.isVar && frameMentionsIdentifier(exprFrame, cp.name)
        }
    }

    private fun wrapVoidPtrExpr(code: String): String {
        val trimmed = code.trim()
        if (trimmed == "nullptr" || trimmed == "{}") return "nullptr"
        includes.addSystem("memory")
        includes.addSystem("type_traits")
        return "([&](auto&& kxs_p) -> void* { " +
            "if constexpr (std::is_convertible_v<decltype(kxs_p), const void*>) { " +
            "return const_cast<void*>(static_cast<const void*>(kxs_p)); " +
            "} else if constexpr (std::is_lvalue_reference_v<decltype(kxs_p)>) { " +
            "return const_cast<void*>(static_cast<const void*>(std::addressof(kxs_p))); " +
            "} else { return nullptr; } }($trimmed))"
    }

    private fun renderVoidPtrAtomicInit(
        initFrame: PacketFrame,
        emitter: CppExprAndStmtEmitter,
    ): String {
        val unwrapped =
            if (initFrame.nodeTypeId == KtNodeTypes.PARENTHESIZED_ID) {
                FrameAstHelpers.childFrames(stream, initFrame).firstOrNull() ?: initFrame
            } else {
                initFrame
            }
        if (unwrapped.nodeTypeId == KtNodeTypes.NULL_ID) return "nullptr"
        if (unwrapped.nodeTypeId == KtNodeTypes.IF_ID) {
            val condHolder = FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.CONDITION_ID)
            val condExpr = condHolder?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
            val thenHolder = FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.THEN_ID)
            val thenExpr = thenHolder?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
            val elseHolder = FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.ELSE_ID)
            val elseExpr = elseHolder?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
            if (condExpr != null && thenExpr != null && elseExpr != null) {
                val c = emitter.emitExpression(condExpr)
                val t = renderVoidPtrAtomicInit(thenExpr, emitter)
                val e = renderVoidPtrAtomicInit(elseExpr, emitter)
                return "($c ? $t : $e)"
            }
        }
        return wrapVoidPtrExpr(emitter.emitExpression(unwrapped))
    }

    private fun renderDeferredMemberPropCtorInit(
        cls: KtClassSymbol,
        propIdx: Int,
        emitter: CppExprAndStmtEmitter,
    ): String? {
        val prop = symbols.propertiesByFrame[propIdx] ?: return null
        if (prop.isConst || prop.getterFrameIndex != null) return null
        val fieldCppName =
            if (isBackedOverrideMemberProp(cls, stream.frames[propIdx], prop)) {
                CppNamingPolicy.toPrivateFieldName(prop.kotlinName)
            } else {
                prop.cppName
            }
        val af = bundle.atomicPlan.atomicFieldsByPropFrame[propIdx]
        if (af != null) {
            if (af.isArray) {
                val szIdx = af.arraySizeExprFrameIndex ?: return null
                val szFrame = stream.frames[szIdx]
                if (!referencesNonFieldCtorParam(cls, szFrame)) return null
                includes.addSystem("vector")
                return "$fieldCppName(static_cast<std::size_t>(${emitter.emitExpression(szFrame)}))"
            }
            val initIdx = af.initExprFrameIndex ?: return null
            val initFrame = stream.frames[initIdx]
            if (!referencesNonFieldCtorParam(cls, initFrame)) return null
            val sharedPtrElemType = resolveSharedPtrAtomicElemType(af, cls)
            if (sharedPtrElemType != null) {
                includes.needsAtomicReferenceDef = true
                includes.addSystem("mutex")
                return "$fieldCppName(${emitter.emitExpression(initFrame)})"
            }
            val simpleEnumCppNames =
                symbols.classesByFrame.values
                    .filter { it.kind == KtClassKind.SIMPLE_ENUM }
                    .map { it.cppName }
                    .toSet()
            val rawElemType =
                af.explicitElementTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT)
                } ?: af.inferredElementCppType ?: "void*"
            val elemType = CppTypeTranslator.coerceToTrivialAtomicElementType(rawElemType, simpleEnumCppNames)
            val initCode =
                if (elemType == "void*") {
                    renderVoidPtrAtomicInit(initFrame, emitter)
                } else {
                    emitter.emitExpression(initFrame)
                }
            return "$fieldCppName($initCode)"
        }
        val initIdx = prop.initializerFrameIndex ?: return null
        val initFrame = stream.frames[initIdx]
        if (!referencesNonFieldCtorParam(cls, initFrame)) return null
        return "$fieldCppName(${emitter.emitExpression(initFrame)})"
    }

    private fun buildConstructorInitializerList(
        cls: KtClassSymbol,
        suffix: String,
        delegatedInterfaces: List<DelegatedInterfaceInfo> = emptyList(),
    ): List<String> {
        val inits = ArrayList<String>()
        val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls)
        for (cp in cls.primaryConstructorParams) {
            emitter.bindLocalName(cp.name, "${cp.cppName}$suffix")
        }
        for (superTypeIdx in cls.superTypeFrameIndices) {
            if (superTypeIdx in cls.delegatedSuperTypeFrameIndices) continue
            val (baseName, isStdRuntimeError) = bundle.typeTranslator.translateSuperBaseType(stream.frames[superTypeIdx])
            val argListIdx = cls.superCallArgsBySuperIndex[superTypeIdx]
            if (argListIdx != null) {
                val args =
                    FrameAstHelpers
                        .childFramesOfType(stream, stream.frames[argListIdx], KtNodeTypes.VALUE_ARGUMENT_ID)
                        .mapNotNull { va ->
                            FrameAstHelpers
                                .childFrames(stream, va)
                                .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                                ?.let { emitter.emitExpression(it) }
                        }
                if (isStdRuntimeError) {
                    val msgExpr =
                        if (args.isEmpty()) {
                            "\"\""
                        } else {
                            includes.addSystem("string")
                            includes.addSystem("type_traits")
                            "([&](const auto& kxs_m) -> std::string { " +
                                "if constexpr (std::is_convertible_v<decltype(kxs_m), std::string>) return std::string(kxs_m); " +
                                "else if constexpr (requires { kxs_m.value_or(\"\"); }) return kxs_m.value_or(\"\"); " +
                                "else return \"\"; }(${args.first()}))"
                        }
                    inits.add("$baseName($msgExpr)")
                } else {
                    inits.add("$baseName(${args.joinToString(", ")})")
                }
            } else if (isStdRuntimeError) {
                inits.add("$baseName(\"\")")
            }
        }
        if (cls.isInner && cls.enclosingClassFrameIndex != null) {
            inits.add("outer_(outer_value)")
        }
        for (di in delegatedInterfaces) {
            inits.add("${di.fieldCppName}(${emitter.emitExpression(stream.frames[di.delegateExprFrameIndex])})")
        }
        for (cp in cls.primaryConstructorParams) {
            if (isPrivateOrBackedOverrideCtorParam(cls, cp)) {
                inits.add("${CppNamingPolicy.toPrivateFieldName(cp.name)}(std::move(${cp.cppName}$suffix))")
            }
        }
        for (pIdx in cls.memberPropertyFrameIndices) {
            if (isPrivateOrBackedOverrideMemberProp(cls, pIdx)) {
                renderDeferredMemberPropCtorInit(cls, pIdx, emitter)?.let { inits.add(it) }
            }
        }
        for (cp in cls.primaryConstructorParams) {
            if ((cp.isVal || cp.isVar) && !isPrivateOrBackedOverrideCtorParam(cls, cp)) {
                inits.add("${cp.cppName}(std::move(${cp.cppName}$suffix))")
            }
        }
        for (pIdx in cls.memberPropertyFrameIndices) {
            if (!isPrivateOrBackedOverrideMemberProp(cls, pIdx)) {
                renderDeferredMemberPropCtorInit(cls, pIdx, emitter)?.let { inits.add(it) }
            }
        }
        return inits
    }

    private fun renderSecondaryConstructors(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        indent: String,
    ): String {
        val classBody = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.CLASS_BODY_ID) ?: return ""
        val secCtors = FrameAstHelpers.childFramesOfType(stream, classBody, KtNodeTypes.SECONDARY_CONSTRUCTOR_ID)
        if (secCtors.isEmpty()) return ""
        val outerCls =
            if (cls.isInner && cls.enclosingClassFrameIndex != null) {
                symbols.classesByFrame[cls.enclosingClassFrameIndex]
            } else {
                null
            }
        val sb = StringBuilder()
        for (sec in secCtors) {
            val paramList = FrameAstHelpers.firstChildFrame(stream, sec, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
            val params = FrameAstHelpers.extractValueParams(stream, paramList)
            val sigEmitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls, inDefaultParameterExpr = true)
            val bodyEmitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls)
            val sigParts = ArrayList<String>(params.size + (if (outerCls != null) 1 else 0))
            if (outerCls != null) {
                sigParts.add("${outerCls.cppName}* outer_value")
            }
            for (vp in params) {
                val t =
                    vp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                    } ?: "std::any"
                val paramCppName = "${vp.cppName}_value"
                bodyEmitter.bindLocalName(vp.name, paramCppName)
                val defIdx = vp.defaultExprFrameIndex
                val def =
                    if (defIdx != null) {
                        val rawDef = sigEmitter.emitExpression(stream.frames[defIdx])
                        val coercedDef =
                            if (rawDef == "nullptr" && t.startsWith("std::optional<")) {
                                includes.addSystem("optional")
                                "std::nullopt"
                            } else {
                                rawDef
                            }
                        " = $coercedDef"
                    } else {
                        ""
                    }
                sigParts.add("$t $paramCppName$def")
            }
            val explicitKw = if (sigParts.size == 1) "explicit " else ""
            val delCall = FrameAstHelpers.firstChildFrame(stream, sec, KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL_ID)
            val initParts = ArrayList<String>()
            if (delCall != null) {
                val delRef = FrameAstHelpers.firstChildFrame(stream, delCall, KtNodeTypes.CONSTRUCTOR_DELEGATION_REFERENCE_ID)
                val isThis = delRef?.let { FrameAstHelpers.hasDirectKeyword(stream, it, KtTokens.THIS_KEYWORD_ID) } ?: true
                val argList = FrameAstHelpers.firstChildFrame(stream, delCall, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
                val args =
                    if (argList != null) {
                        FrameAstHelpers
                            .childFramesOfType(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID)
                            .mapNotNull { va ->
                                FrameAstHelpers
                                    .childFrames(stream, va)
                                    .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                                    ?.let { bodyEmitter.emitExpression(it) }
                            }
                    } else {
                        emptyList()
                    }
                if (isThis) {
                    val fullArgs = if (outerCls != null) listOf("outer_value") + args else args
                    initParts.add("${cls.cppName}(${fullArgs.joinToString(", ")})")
                } else {
                    val primarySuperIdx =
                        cls.superTypeFrameIndices.firstOrNull { trIdx ->
                            trIdx !in cls.delegatedSuperTypeFrameIndices &&
                                trIdx !in cls.superCallArgsBySuperIndex &&
                                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx])?.let {
                                    it != "Any" && !it.endsWith("Callback")
                                } == true
                        } ?: cls.superTypeFrameIndices.firstOrNull { it !in cls.delegatedSuperTypeFrameIndices }
                    if (primarySuperIdx != null) {
                        val (baseName, isStdRuntimeError) =
                            bundle.typeTranslator.translateSuperBaseType(stream.frames[primarySuperIdx])
                        if (isStdRuntimeError) {
                            val msgExpr =
                                if (args.isEmpty()) {
                                    "\"\""
                                } else {
                                    includes.addSystem("string")
                                    includes.addSystem("type_traits")
                                    "([&](const auto& kxs_m) -> std::string { " +
                                        "if constexpr (std::is_convertible_v<decltype(kxs_m), std::string>) return std::string(kxs_m); " +
                                        "else if constexpr (requires { kxs_m.value_or(\"\"); }) return kxs_m.value_or(\"\"); " +
                                        "else return \"\"; }(${args.first()}))"
                                }
                            initParts.add("$baseName($msgExpr)")
                        } else {
                            initParts.add("$baseName(${args.joinToString(", ")})")
                        }
                    }
                    if (outerCls != null) {
                        initParts.add("outer_(outer_value)")
                    }
                }
            }
            val blockFrame = FrameAstHelpers.firstChildFrame(stream, sec, KtNodeTypes.BLOCK_ID)
            sb.append("\n$indent$explicitKw${cls.cppName}(${sigParts.joinToString(", ")})")
            if (initParts.isNotEmpty()) {
                sb.append(" : ").append(initParts.joinToString(", "))
            }
            if (blockFrame != null) {
                val renderedBody = bodyEmitter.emitBlock(blockFrame, indent, isValueBlock = false, resultTargetVar = null)
                sb.append(' ').append(renderedBody).append('\n')
            } else {
                sb.append(" {}\n")
            }
        }
        return sb.toString()
    }

    private fun extractTemplateArgFromType(cppType: String): String {
        val lt = cppType.indexOf('<')
        val gt = cppType.lastIndexOf('>')
        return if (lt >= 0 && gt > lt) cppType.substring(lt + 1, gt).trim() else "std::any"
    }

    private fun collectReachableSameFileSuperNames(
        cls: KtClassSymbol,
        visited: MutableSet<String> = HashSet(),
    ): Set<String> {
        for (trIdx in cls.superTypeFrameIndices) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: continue
            if (!visited.add(simple)) continue
            symbols.classesByName[simple]?.let { superCls ->
                collectReachableSameFileSuperNames(superCls, visited)
            }
        }
        return visited
    }

    private fun renderSiblingBaseInterfaceForwarding(
        cls: KtClassSymbol,
        delegatedInterfaces: List<DelegatedInterfaceInfo>,
        indent: String,
    ): String {
        val nonDelegated = cls.superTypeFrameIndices.filter { it !in cls.delegatedSuperTypeFrameIndices }
        if (nonDelegated.size < 2) return ""
        val sameFileConcreteSupers =
            nonDelegated.mapNotNull { trIdx ->
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: return@mapNotNull null
                val superCls = symbols.classesByName[simple] ?: return@mapNotNull null
                if (superCls.kind != KtClassKind.INTERFACE) superCls else null
            }
        val sameFileIfaceSupers =
            nonDelegated.mapNotNull { trIdx ->
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: return@mapNotNull null
                val superCls = symbols.classesByName[simple] ?: return@mapNotNull null
                if (superCls.kind == KtClassKind.INTERFACE) superCls else null
            }
        if (sameFileConcreteSupers.isEmpty() || sameFileIfaceSupers.isEmpty()) return ""

        val concreteReachableNames = HashSet<String>()
        for (cSuper in sameFileConcreteSupers) {
            collectReachableSameFileSuperNames(cSuper, concreteReachableNames)
        }

        val declaredProps = HashSet<String>()
        cls.primaryConstructorParams.forEach { if (it.isVal || it.isVar) declaredProps.add(it.name) }
        cls.memberPropertyFrameIndices.forEach { pIdx ->
            symbols.propertiesByFrame[pIdx]?.kotlinName?.let { declaredProps.add(it) }
        }
        val declaredFnKeys = HashSet<Pair<String, Int>>()
        cls.memberFunctionFrameIndices.forEach { fIdx ->
            symbols.functionsByFrame[fIdx]?.let { fn ->
                if (!isDeprecatedHiddenFun(fn)) declaredFnKeys.add(fn.kotlinName to fn.valueParams.size)
            }
        }
        delegatedInterfaces.forEach { di -> declaredProps.add(di.superSimpleName) }

        val sb = StringBuilder()
        for (ifaceCls in sameFileIfaceSupers) {
            if (ifaceCls.kotlinName in concreteReachableNames) continue
            for (iPropIdx in ifaceCls.memberPropertyFrameIndices) {
                val iProp = symbols.propertiesByFrame[iPropIdx] ?: continue
                if (iProp.kotlinName in declaredProps) continue
                val matchingBase =
                    sameFileConcreteSupers.firstOrNull { baseCls ->
                        baseCls.memberPropertyFrameIndices.any { symbols.propertiesByFrame[it]?.kotlinName == iProp.kotlinName }
                    } ?: continue
                val baseProp =
                    matchingBase.memberPropertyFrameIndices
                        .mapNotNull { symbols.propertiesByFrame[it] }
                        .firstOrNull { it.kotlinName == iProp.kotlinName }
                val baseIsGetter =
                    baseProp == null ||
                        baseProp.getterFrameIndex != null ||
                        isBackedOverrideMemberProp(matchingBase, stream.frames[baseProp.frameIndex], baseProp)
                val getterName = resolveOverridePropertyGetterName(cls, iProp.kotlinName, iProp.cppName)
                val baseAccess =
                    if (baseIsGetter) {
                        val baseGetterName = resolveOverridePropertyGetterName(matchingBase, iProp.kotlinName, iProp.cppName)
                        "${matchingBase.cppName}::$baseGetterName()"
                    } else {
                        "${matchingBase.cppName}::${baseProp.cppName}"
                    }
                val propType =
                    iProp.returnTypeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                    } ?: run {
                        includes.addSystem("any")
                        "std::any"
                    }
                declaredProps.add(iProp.kotlinName)
                sb.append("\n$indent$propType $getterName() const override { return $baseAccess; }\n")
            }
            for (iFnIdx in ifaceCls.memberFunctionFrameIndices) {
                val iFn = symbols.functionsByFrame[iFnIdx] ?: continue
                if (iFn.typeParams.isNotEmpty() || isDeprecatedHiddenFun(iFn)) continue
                val fnKey = iFn.kotlinName to iFn.valueParams.size
                if (fnKey in declaredFnKeys) continue
                val matchingBase =
                    sameFileConcreteSupers.firstOrNull { baseCls ->
                        baseCls.memberFunctionFrameIndices.any { bFnIdx ->
                            val bFn = symbols.functionsByFrame[bFnIdx] ?: return@any false
                            !isDeprecatedHiddenFun(bFn) &&
                                bFn.typeParams.isEmpty() &&
                                bFn.kotlinName == iFn.kotlinName &&
                                bFn.valueParams.size == iFn.valueParams.size
                        }
                    } ?: continue
                val retType =
                    iFn.returnTypeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                    } ?: "void"
                val cleanRet = retType.removePrefix("[[noreturn]] ")
                val methodName = resolveCppFunctionOrOperatorName(iFn, cls)
                val paramsNoDefaults = buildFunctionParameterList(iFn, includeDefaults = false)
                val argForward =
                    iFn.valueParams.joinToString(", ") { vp ->
                        includes.addSystem("utility")
                        "std::move(${vp.cppName})"
                    }
                val isConst = shouldMarkMethodConst(iFn, ifaceCls)
                val constSuffix = if (isConst) " const" else ""
                val callStmt = "${matchingBase.cppName}::$methodName($argForward)"
                val body = if (cleanRet == "void") "{ $callStmt; }" else "{ return $callStmt; }"
                declaredFnKeys.add(fnKey)
                sb.append("\n$indent$cleanRet $methodName($paramsNoDefaults)$constSuffix override $body\n")
            }
        }
        return sb.toString()
    }

    private fun renderOverloadedVirtualUsings(
        cls: KtClassSymbol,
        delegatedInterfaces: List<DelegatedInterfaceInfo>,
        indent: String,
    ): String {
        val declaredMethodNames = HashSet<String>()
        cls.memberFunctionFrameIndices.forEach { fIdx ->
            symbols.functionsByFrame[fIdx]?.let { fn ->
                if (!isDeprecatedHiddenFun(fn)) declaredMethodNames.add(resolveCppFunctionOrOperatorName(fn, cls))
            }
        }
        val nonDelegated = cls.superTypeFrameIndices.filter { it !in cls.delegatedSuperTypeFrameIndices }
        val superEntries =
            nonDelegated.mapNotNull { trIdx ->
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: return@mapNotNull null
                val (baseCpp, isStdErr) = bundle.typeTranslator.translateSuperBaseType(stream.frames[trIdx])
                if (isStdErr) null else simple to baseCpp
            }
        val delegatedSimpleSet = delegatedInterfaces.mapTo(HashSet()) { it.superSimpleName }

        val lines = LinkedHashSet<String>()
        if ("join" in declaredMethodNames && cls.kotlinName != "Job") {
            val jobBases = setOf("Job", "CompletableJob", "Deferred", "CompletableDeferred", "JobSupport", "AbstractCoroutine", "ScopeCoroutine")
            if (superEntries.any { it.first in jobBases }) {
                if ("kotlinx/coroutines/Job.hpp" != includes.selfHeaderPath) {
                    includes.addProject("kotlinx/coroutines/Job.hpp")
                }
                lines.add("using kotlinx::coroutines::Job::join;")
            }
        }
        if ("await" in declaredMethodNames && cls.kotlinName != "Deferred") {
            val defBase = superEntries.firstOrNull { it.first == "Deferred" }
            if (defBase != null) {
                lines.add("using ${defBase.second}::await;")
            } else {
                val cDefBase = superEntries.firstOrNull { it.first == "CompletableDeferred" }
                if (cDefBase != null) {
                    val elemT = extractTemplateArgFromType(cDefBase.second)
                    includes.addProject("kotlinx/coroutines/Deferred.hpp")
                    lines.add("using kotlinx::coroutines::Deferred<$elemT>::await;")
                }
            }
        }
        if ("acquire" in declaredMethodNames ||
            (superEntries.any { it.first == "SemaphoreAndMutexImpl" } && superEntries.any { it.first == "Semaphore" })
        ) {
            for ((simple, baseCpp) in superEntries) {
                if (simple == "Semaphore" || simple == "SemaphoreAndMutexImpl") {
                    lines.add("using $baseCpp::acquire;")
                }
            }
        }
        val hasWaiterDelegation =
            "Waiter" in delegatedSimpleSet || "SelectInstanceInternal" in delegatedSimpleSet
        if ("invoke_on_cancellation" in declaredMethodNames && !hasWaiterDelegation) {
            val fnCount =
                cls.memberFunctionFrameIndices.count { fIdx ->
                    symbols.functionsByFrame[fIdx]?.kotlinName == "invokeOnCancellation"
                }
            if (fnCount == 1) {
                val ccBase = superEntries.firstOrNull { it.first == "CancellableContinuation" }
                val waiterBase = superEntries.firstOrNull { it.first == "Waiter" }
                if (ccBase != null) lines.add("using ${ccBase.second}::invoke_on_cancellation;")
                if (waiterBase != null) lines.add("using ${waiterBase.second}::invoke_on_cancellation;")
            }
        }
        if (lines.isEmpty()) return ""
        return buildString {
            for (line in lines) {
                append(indent).append(line).append('\n')
            }
        }
    }

    private fun renderDelegatedInterfaceForwardingMethods(
        cls: KtClassSymbol,
        delegatedInterfaces: List<DelegatedInterfaceInfo>,
        indent: String,
    ): String {
        if (delegatedInterfaces.isEmpty()) return ""
        val declaredNames = LinkedHashSet<String>()
        val declaredFnArities = HashSet<Pair<String, Int>>()
        val declaredFnFullSigs = HashSet<Triple<String, Int, Boolean>>()
        cls.primaryConstructorParams.forEach { if (it.isVal || it.isVar) declaredNames.add(it.name) }
        cls.memberPropertyFrameIndices.forEach { pIdx ->
            symbols.propertiesByFrame[pIdx]?.kotlinName?.let { declaredNames.add(it) }
        }
        cls.memberFunctionFrameIndices.forEach { fIdx ->
            symbols.functionsByFrame[fIdx]?.let { fn ->
                if (!isDeprecatedHiddenFun(fn)) {
                    declaredNames.add(fn.kotlinName)
                    declaredFnArities.add(fn.kotlinName to fn.valueParams.size)
                    declaredFnFullSigs.add(Triple(fn.kotlinName, fn.valueParams.size, fn.typeParams.isNotEmpty()))
                }
            }
        }
        val sb = StringBuilder()
        for (di in delegatedInterfaces) {
            val arrow = if (di.isPointerField) "->" else "."
            val fieldRef = di.fieldCppName
            val baseSimple = di.superSimpleName
            when (baseSimple) {
                "CoroutineScope" -> {
                    if ("coroutineContext" !in declaredNames) {
                        declaredNames.add("coroutineContext")
                        includes.addProject("kotlinx/coroutines/Continuation.hpp")
                        includes.addSystem("memory")
                        sb.append(
                            "\n${indent}std::shared_ptr<kotlin::coroutines::CoroutineContext> coroutine_context() const override " +
                                "{ return ${fieldRef}${arrow}coroutine_context(); }\n",
                        )
                    }
                }
                "Delay" -> {
                    if ("scheduleResumeAfterDelay" !in declaredNames) {
                        declaredNames.add("scheduleResumeAfterDelay")
                        includes.addProject("kotlinx/coroutines/CancellableContinuation.hpp")
                        includes.addProject("kotlinx/coroutines/Unit.hpp")
                        includes.addSystem("cstdint")
                        includes.addSystem("memory")
                        includes.addSystem("utility")
                        sb.append(
                            "\n${indent}void schedule_resume_after_delay(std::int64_t time_millis, " +
                                "std::shared_ptr<kotlinx::coroutines::CancellableContinuation<kotlin::Unit>> continuation) override " +
                                "{ ${fieldRef}${arrow}schedule_resume_after_delay(time_millis, std::move(continuation)); }\n",
                        )
                    }
                }
                "Waiter" -> {
                    if (("invokeOnCancellation" to 2) !in declaredFnArities) {
                        declaredFnArities.add("invokeOnCancellation" to 2)
                        includes.addProject("kotlinx/coroutines/internal/ConcurrentLinkedList.hpp")
                        includes.addSystem("cstdint")
                        includes.addSystem("memory")
                        includes.addSystem("utility")
                        sb.append(
                            "\n${indent}void invoke_on_cancellation(std::shared_ptr<kotlinx::coroutines::internal::Segment<void*>> segment, " +
                                "std::int32_t index) override { ${fieldRef}${arrow}invoke_on_cancellation(std::move(segment), index); }\n",
                        )
                    }
                }
                "FlowCollector" -> {
                    if ("emit" !in declaredNames) {
                        declaredNames.add("emit")
                        val elemType = extractTemplateArgFromType(di.superCppType)
                        includes.addSystem("utility")
                        sb.append(
                            "\n${indent}void emit($elemType value) override " +
                                "{ ${fieldRef}${arrow}emit(std::move(value)); }\n",
                        )
                    }
                }
                "Flow",
                "SharedFlow",
                "StateFlow",
                -> {
                    val elemType = extractTemplateArgFromType(di.superCppType)
                    if (baseSimple == "StateFlow" && "value" !in declaredNames) {
                        declaredNames.add("value")
                        sb.append("\n$indent$elemType value() const override { return ${fieldRef}${arrow}value(); }\n")
                    }
                    if ((baseSimple == "SharedFlow" || baseSimple == "StateFlow") && "replayCache" !in declaredNames) {
                        declaredNames.add("replayCache")
                        includes.addSystem("vector")
                        sb.append(
                            "\n${indent}std::vector<$elemType> replay_cache() const override " +
                                "{ return ${fieldRef}${arrow}replay_cache(); }\n",
                        )
                    }
                    if ("collect" !in declaredNames) {
                        declaredNames.add("collect")
                        includes.addProject("kotlinx/coroutines/flow/FlowCollector.hpp")
                        includes.addSystem("memory")
                        includes.addSystem("utility")
                        sb.append(
                            "\n${indent}void collect(std::shared_ptr<kotlinx::coroutines::flow::FlowCollector<$elemType>> collector) override " +
                                "{ ${fieldRef}${arrow}collect(std::move(collector)); }\n",
                        )
                    }
                }
                "Runnable" -> {
                    if ("run" !in declaredNames) {
                        declaredNames.add("run")
                        sb.append("\n${indent}void run() override { ${fieldRef}${arrow}run(); }\n")
                    }
                }
                "Continuation" -> {
                    val elemType = extractTemplateArgFromType(di.superCppType)
                    includes.addProject("kotlinx/coroutines/Continuation.hpp")
                    includes.addProject("kotlinx/coroutines/Result.hpp")
                    includes.addSystem("memory")
                    includes.addSystem("utility")
                    if ("context" !in declaredNames) {
                        declaredNames.add("context")
                        sb.append(
                            "\n${indent}std::shared_ptr<kotlin::coroutines::CoroutineContext> get_context() const override " +
                                "{ return ${fieldRef}${arrow}get_context(); }\n",
                        )
                    }
                    if (("resumeWith" to 1) !in declaredFnArities) {
                        declaredFnArities.add("resumeWith" to 1)
                        sb.append(
                            "\n${indent}void resume_with(kotlinx::coroutines::Result<$elemType> result) override " +
                                "{ ${fieldRef}${arrow}resume_with(std::move(result)); }\n",
                        )
                    }
                }
                "CancellableContinuation" -> {
                    val elemType = extractTemplateArgFromType(di.superCppType)
                    includes.addProject("kotlinx/coroutines/CancellableContinuation.hpp")
                    includes.addProject("kotlinx/coroutines/CompletionHandler.common.hpp")
                    includes.addProject("kotlinx/coroutines/Continuation.hpp")
                    includes.addProject("kotlinx/coroutines/CoroutineDispatcher.hpp")
                    includes.addProject("kotlinx/coroutines/Result.hpp")
                    includes.addSystem("any")
                    includes.addSystem("exception")
                    includes.addSystem("functional")
                    includes.addSystem("memory")
                    includes.addSystem("utility")
                    if ("context" !in declaredNames) {
                        declaredNames.add("context")
                        sb.append(
                            "\n${indent}std::shared_ptr<kotlin::coroutines::CoroutineContext> get_context() const override " +
                                "{ return ${fieldRef}${arrow}get_context(); }\n",
                        )
                    }
                    if (("resumeWith" to 1) !in declaredFnArities) {
                        declaredFnArities.add("resumeWith" to 1)
                        sb.append(
                            "\n${indent}void resume_with(kotlinx::coroutines::Result<$elemType> result) override " +
                                "{ ${fieldRef}${arrow}resume_with(std::move(result)); }\n",
                        )
                    }
                    if ("isActive" !in declaredNames) {
                        declaredNames.add("isActive")
                        sb.append("\n${indent}bool is_active() const override { return ${fieldRef}${arrow}is_active(); }\n")
                    }
                    if ("isCompleted" !in declaredNames) {
                        declaredNames.add("isCompleted")
                        sb.append("\n${indent}bool is_completed() const override { return ${fieldRef}${arrow}is_completed(); }\n")
                    }
                    if ("isCancelled" !in declaredNames) {
                        declaredNames.add("isCancelled")
                        sb.append("\n${indent}bool is_cancelled() const override { return ${fieldRef}${arrow}is_cancelled(); }\n")
                    }
                    if (("tryResume" to 2) !in declaredFnArities) {
                        declaredFnArities.add("tryResume" to 2)
                        sb.append(
                            "\n${indent}std::any try_resume($elemType value, std::any idempotent = nullptr) override " +
                                "{ return ${fieldRef}${arrow}try_resume(std::move(value), std::move(idempotent)); }\n",
                        )
                    }
                    if (("tryResume" to 3) !in declaredFnArities) {
                        declaredFnArities.add("tryResume" to 3)
                        sb.append(
                            "\n${indent}std::any try_resume($elemType value, std::any idempotent, " +
                                "std::function<void(std::exception_ptr, $elemType, std::shared_ptr<kotlin::coroutines::CoroutineContext>)> on_cancellation) override " +
                                "{ return ${fieldRef}${arrow}try_resume(std::move(value), std::move(idempotent), std::move(on_cancellation)); }\n",
                        )
                    }
                    if (("tryResumeWithException" to 1) !in declaredFnArities) {
                        declaredFnArities.add("tryResumeWithException" to 1)
                        sb.append(
                            "\n${indent}std::any try_resume_with_exception(std::exception_ptr exception) override " +
                                "{ return ${fieldRef}${arrow}try_resume_with_exception(std::move(exception)); }\n",
                        )
                    }
                    if (("completeResume" to 1) !in declaredFnArities) {
                        declaredFnArities.add("completeResume" to 1)
                        sb.append(
                            "\n${indent}void complete_resume(std::any token) override " +
                                "{ ${fieldRef}${arrow}complete_resume(std::move(token)); }\n",
                        )
                    }
                    if (("initCancellability" to 0) !in declaredFnArities) {
                        declaredFnArities.add("initCancellability" to 0)
                        sb.append("\n${indent}void init_cancellability() override { ${fieldRef}${arrow}init_cancellability(); }\n")
                    }
                    if (("cancel" to 1) !in declaredFnArities) {
                        declaredFnArities.add("cancel" to 1)
                        sb.append(
                            "\n${indent}bool cancel(std::exception_ptr cause = nullptr) override " +
                                "{ return ${fieldRef}${arrow}cancel(std::move(cause)); }\n",
                        )
                    }
                    if (("invokeOnCancellation" to 1) !in declaredFnArities) {
                        declaredFnArities.add("invokeOnCancellation" to 1)
                        sb.append(
                            "\n${indent}void invoke_on_cancellation(kotlinx::coroutines::CompletionHandler handler) override " +
                                "{ ${fieldRef}${arrow}invoke_on_cancellation(std::move(handler)); }\n",
                        )
                    }
                    if (("resumeUndispatched" to 2) !in declaredFnArities) {
                        declaredFnArities.add("resumeUndispatched" to 2)
                        sb.append(
                            "\n${indent}void resume_undispatched(const std::shared_ptr<kotlinx::coroutines::CoroutineDispatcher>& self, $elemType value) override " +
                                "{ ${fieldRef}${arrow}resume_undispatched(self, std::move(value)); }\n",
                        )
                    }
                    if (("resumeUndispatchedWithException" to 2) !in declaredFnArities) {
                        declaredFnArities.add("resumeUndispatchedWithException" to 2)
                        sb.append(
                            "\n${indent}void resume_undispatched_with_exception(const std::shared_ptr<kotlinx::coroutines::CoroutineDispatcher>& self, std::exception_ptr exception) override " +
                                "{ ${fieldRef}${arrow}resume_undispatched_with_exception(self, std::move(exception)); }\n",
                        )
                    }
                    if (Triple("resume", 2, false) !in declaredFnFullSigs) {
                        declaredFnFullSigs.add(Triple("resume", 2, false))
                        sb.append(
                            "\n${indent}void resume($elemType value, std::function<void(std::exception_ptr)> on_cancellation) override " +
                                "{ ${fieldRef}${arrow}resume(std::move(value), std::move(on_cancellation)); }\n",
                        )
                    }
                    if (Triple("resume", 2, true) !in declaredFnFullSigs) {
                        declaredFnFullSigs.add(Triple("resume", 2, true))
                        sb.append(
                            "\n${indent}void resume($elemType value, " +
                                "std::function<void(std::exception_ptr, $elemType, std::shared_ptr<kotlin::coroutines::CoroutineContext>)> on_cancellation) override " +
                                "{ ${fieldRef}${arrow}resume(std::move(value), std::move(on_cancellation)); }\n",
                        )
                    }
                }
                "SelectInstance",
                "SelectInstanceInternal",
                -> {
                    includes.addProject("kotlinx/coroutines/Continuation.hpp")
                    includes.addProject("kotlinx/coroutines/DisposableHandle.hpp")
                    includes.addSystem("any")
                    includes.addSystem("memory")
                    includes.addSystem("utility")
                    if ("context" !in declaredNames) {
                        declaredNames.add("context")
                        sb.append(
                            "\n${indent}std::shared_ptr<kotlin::coroutines::CoroutineContext> context() const override " +
                                "{ return ${fieldRef}${arrow}context(); }\n",
                        )
                    }
                    if (("trySelect" to 2) !in declaredFnArities) {
                        declaredFnArities.add("trySelect" to 2)
                        sb.append(
                            "\n${indent}bool try_select(std::any clause_object, std::any result) override " +
                                "{ return ${fieldRef}${arrow}try_select(std::move(clause_object), std::move(result)); }\n",
                        )
                    }
                    if (("disposeOnCompletion" to 1) !in declaredFnArities) {
                        declaredFnArities.add("disposeOnCompletion" to 1)
                        sb.append(
                            "\n${indent}void dispose_on_completion(std::shared_ptr<kotlinx::coroutines::DisposableHandle> disposable_handle) override " +
                                "{ ${fieldRef}${arrow}dispose_on_completion(std::move(disposable_handle)); }\n",
                        )
                    }
                    if (("selectInRegistrationPhase" to 1) !in declaredFnArities) {
                        declaredFnArities.add("selectInRegistrationPhase" to 1)
                        sb.append(
                            "\n${indent}void select_in_registration_phase(std::any internal_result) override " +
                                "{ ${fieldRef}${arrow}select_in_registration_phase(std::move(internal_result)); }\n",
                        )
                    }
                    if (baseSimple == "SelectInstanceInternal" && ("invokeOnCancellation" to 2) !in declaredFnArities) {
                        declaredFnArities.add("invokeOnCancellation" to 2)
                        includes.addProject("kotlinx/coroutines/internal/ConcurrentLinkedList.hpp")
                        includes.addSystem("cstdint")
                        sb.append(
                            "\n${indent}void invoke_on_cancellation(std::shared_ptr<kotlinx::coroutines::internal::Segment<void*>> segment, " +
                                "std::int32_t index) override { ${fieldRef}${arrow}invoke_on_cancellation(std::move(segment), index); }\n",
                        )
                    }
                }
                "SendChannel",
                "ReceiveChannel",
                "Channel",
                "BroadcastChannel",
                -> {
                    val elemType = extractTemplateArgFromType(di.superCppType)
                    includes.addProject("kotlinx/coroutines/Unit.hpp")
                    includes.addProject("kotlinx/coroutines/selects/Select.hpp")
                    includes.addSystem("exception")
                    includes.addSystem("functional")
                    includes.addSystem("memory")
                    includes.addSystem("optional")
                    includes.addSystem("utility")
                    if (baseSimple in setOf("SendChannel", "Channel", "BroadcastChannel")) {
                        if ("isClosedForSend" !in declaredNames) {
                            declaredNames.add("isClosedForSend")
                            sb.append("\n${indent}bool is_closed_for_send() const override { return ${fieldRef}${arrow}is_closed_for_send(); }\n")
                        }
                        if ("onSend" !in declaredNames) {
                            declaredNames.add("onSend")
                            sb.append(
                                "\n${indent}std::shared_ptr<kotlinx::coroutines::selects::SelectClause2<$elemType, kotlinx::coroutines::channels::SendChannel<$elemType>>> on_send() const override " +
                                    "{ return ${fieldRef}${arrow}on_send(); }\n",
                            )
                        }
                        if (("send" to 1) !in declaredFnArities) {
                            declaredFnArities.add("send" to 1)
                            sb.append("\n${indent}void send($elemType element) override { ${fieldRef}${arrow}send(std::move(element)); }\n")
                        }
                        if (("trySend" to 1) !in declaredFnArities) {
                            declaredFnArities.add("trySend" to 1)
                            sb.append(
                                "\n${indent}ChannelResult<kotlin::Unit> try_send($elemType element) override " +
                                    "{ return ${fieldRef}${arrow}try_send(std::move(element)); }\n",
                            )
                        }
                        if (("close" to 1) !in declaredFnArities) {
                            declaredFnArities.add("close" to 1)
                            sb.append(
                                "\n${indent}bool close(std::exception_ptr cause = nullptr) override " +
                                    "{ return ${fieldRef}${arrow}close(std::move(cause)); }\n",
                            )
                        }
                        if (("invokeOnClose" to 1) !in declaredFnArities) {
                            declaredFnArities.add("invokeOnClose" to 1)
                            sb.append(
                                "\n${indent}void invoke_on_close(std::function<void(std::exception_ptr)> handler) override " +
                                    "{ ${fieldRef}${arrow}invoke_on_close(std::move(handler)); }\n",
                            )
                        }
                    }
                    if (baseSimple in setOf("ReceiveChannel", "Channel")) {
                        includes.addProject("kotlin/coroutines/cancellation/CancellationException.hpp")
                        if ("isClosedForReceive" !in declaredNames) {
                            declaredNames.add("isClosedForReceive")
                            sb.append("\n${indent}bool is_closed_for_receive() const override { return ${fieldRef}${arrow}is_closed_for_receive(); }\n")
                        }
                        if ("isEmpty" !in declaredNames) {
                            declaredNames.add("isEmpty")
                            sb.append("\n${indent}bool is_empty() const override { return ${fieldRef}${arrow}is_empty(); }\n")
                        }
                        if ("onReceive" !in declaredNames) {
                            declaredNames.add("onReceive")
                            sb.append(
                                "\n${indent}std::shared_ptr<kotlinx::coroutines::selects::SelectClause1<$elemType>> on_receive() const override " +
                                    "{ return ${fieldRef}${arrow}on_receive(); }\n",
                            )
                        }
                        if ("onReceiveCatching" !in declaredNames) {
                            declaredNames.add("onReceiveCatching")
                            sb.append(
                                "\n${indent}std::shared_ptr<kotlinx::coroutines::selects::SelectClause1<ChannelResult<$elemType>>> on_receive_catching() const override " +
                                    "{ return ${fieldRef}${arrow}on_receive_catching(); }\n",
                            )
                        }
                        if (("receive" to 0) !in declaredFnArities) {
                            declaredFnArities.add("receive" to 0)
                            sb.append("\n$indent$elemType receive() override { return ${fieldRef}${arrow}receive(); }\n")
                        }
                        if (("receiveCatching" to 0) !in declaredFnArities) {
                            declaredFnArities.add("receiveCatching" to 0)
                            sb.append("\n${indent}ChannelResult<$elemType> receive_catching() override { return ${fieldRef}${arrow}receive_catching(); }\n")
                        }
                        if (("tryReceive" to 0) !in declaredFnArities) {
                            declaredFnArities.add("tryReceive" to 0)
                            sb.append("\n${indent}ChannelResult<$elemType> try_receive() override { return ${fieldRef}${arrow}try_receive(); }\n")
                        }
                        if (("iterator" to 0) !in declaredFnArities) {
                            declaredFnArities.add("iterator" to 0)
                            sb.append(
                                "\n${indent}std::shared_ptr<ChannelIterator<$elemType>> iterator() override " +
                                    "{ return ${fieldRef}${arrow}iterator(); }\n",
                            )
                        }
                        if (("cancel" to 1) !in declaredFnArities) {
                            declaredFnArities.add("cancel" to 1)
                            sb.append(
                                "\n${indent}void cancel(std::optional<kotlin::coroutines::cancellation::CancellationException> cause = std::nullopt) override " +
                                    "{ ${fieldRef}${arrow}cancel(std::move(cause)); }\n",
                            )
                        }
                    }
                    if (baseSimple == "BroadcastChannel") {
                        includes.addProject("kotlin/coroutines/cancellation/CancellationException.hpp")
                        if (("openSubscription" to 0) !in declaredFnArities) {
                            declaredFnArities.add("openSubscription" to 0)
                            sb.append(
                                "\n${indent}std::shared_ptr<ReceiveChannel<$elemType>> open_subscription() override " +
                                    "{ return ${fieldRef}${arrow}open_subscription(); }\n",
                            )
                        }
                        if (("cancel" to 1) !in declaredFnArities) {
                            declaredFnArities.add("cancel" to 1)
                            sb.append(
                                "\n${indent}void cancel(std::optional<kotlin::coroutines::cancellation::CancellationException> cause = std::nullopt) override " +
                                    "{ ${fieldRef}${arrow}cancel(std::move(cause)); }\n",
                            )
                        }
                    }
                }
                "ErrorCatching" -> {
                    includes.addSystem("exception")
                    includes.addSystem("utility")
                    if (("hasError" to 0) !in declaredFnArities) {
                        declaredFnArities.add("hasError" to 0)
                        sb.append("\n${indent}bool has_error() override { return ${fieldRef}${arrow}has_error(); }\n")
                    }
                    if (("reportError" to 1) !in declaredFnArities) {
                        declaredFnArities.add("reportError" to 1)
                        sb.append(
                            "\n${indent}void report_error(std::exception_ptr error) override " +
                                "{ ${fieldRef}${arrow}report_error(std::move(error)); }\n",
                        )
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun resolveSharedPtrAtomicElemType(
        af: AtomicFieldInfo,
        @Suppress("UNUSED_PARAMETER") enclosingClass: KtClassSymbol?,
    ): String? {
        if (bundle.isDocExample || af.isArray) return null
        val raw =
            af.explicitElementTypeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT)
            } ?: af.inferredElementCppType ?: return null
        val unwrapped =
            if (raw.startsWith("std::optional<std::shared_ptr<") && raw.endsWith(">")) {
                raw.removePrefix("std::optional<").removeSuffix(">")
            } else {
                raw
            }
        return if (unwrapped.startsWith("std::shared_ptr<")) unwrapped else null
    }

    private fun isBackedOverrideCtorParam(
        cls: KtClassSymbol,
        cp: KtValueParamInfo,
    ): Boolean {
        if (cls.kind == KtClassKind.INTERFACE) return false
        if (!cp.isVal && !cp.isVar) return false
        val cpFlags = stream.frames[cp.frameIndex].flags
        return PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_OVERRIDE) &&
            !PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_ABSTRACT)
    }

    private fun isBackedOverrideMemberProp(
        enclosingClass: KtClassSymbol,
        @Suppress("UNUSED_PARAMETER") propFrame: PacketFrame,
        sym: KtPropertySymbol,
    ): Boolean {
        if (enclosingClass.kind == KtClassKind.INTERFACE) return false
        if (sym.isConst || sym.getterFrameIndex != null) return false
        return PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OVERRIDE) &&
            !PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_ABSTRACT)
    }

    private fun classInheritsContinuation(
        cls: KtClassSymbol?,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (cls == null || !visited.add(cls.frameIndex)) return false
        if (cls.kotlinName in CONTINUATION_HIERARCHY_BASES) return true
        for (trIdx in cls.superTypeFrameIndices) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: continue
            if (simple in CONTINUATION_HIERARCHY_BASES) return true
            val sameFileSuper = symbols.classesByFrame.values.firstOrNull { it.kotlinName == simple }
            if (sameFileSuper != null && classInheritsContinuation(sameFileSuper, visited)) return true
        }
        return false
    }

    private fun resolveOverridePropertyGetterName(
        enclosingClass: KtClassSymbol?,
        kotlinName: String,
        defaultCppName: String,
    ): String =
        if (kotlinName == "context" && classInheritsContinuation(enclosingClass)) {
            "get_context"
        } else {
            defaultCppName
        }

    private fun isMutableExtensionReceiverCppType(cppType: String): Boolean {
        val trimmed = cppType.trim()
        return trimmed.startsWith("std::atomic<") ||
            trimmed.startsWith("KxsAtomicReference<") ||
            trimmed.startsWith("std::deque<") ||
            trimmed.startsWith("std::unordered_map<") ||
            trimmed.startsWith("std::unordered_set<")
    }

    private fun resolveCollapsedVarianceEscapeTypeParam(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
    ): Pair<String, String>? {
        if (enclosingClass == null || sym.typeParams.size != 1) return null
        val tp = sym.typeParams[0]
        val ubIdx = tp.upperBoundFrameIndex ?: return null
        val isVirtualLike =
            enclosingClass.kind == KtClassKind.INTERFACE ||
                PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OVERRIDE) ||
                PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OPEN) ||
                PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_ABSTRACT)
        if (!isVirtualLike) return null
        val ubFrame = stream.frames[ubIdx]
        val ubSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, ubFrame)
        val matchesUpperEscape =
            ubSimple == "Unit" || enclosingClass.typeParams.any { it.name == ubSimple }
        if (!matchesUpperEscape) return null
        val ubCpp = bundle.typeTranslator.translateTypeFrame(ubFrame, CppTypePosition.TYPE_ARGUMENT)
        return tp.name to ubCpp
    }

    private fun replaceWholeIdentifier(
        text: String,
        targetIdent: String,
        replacement: String,
    ): String {
        if (targetIdent.isEmpty() || text.isEmpty()) return text
        return Regex("""(?<![A-Za-z0-9_:])${Regex.escape(targetIdent)}(?![A-Za-z0-9_])""")
            .replace(text, Regex.escapeReplacement(replacement))
    }

    private fun isDeprecatedHiddenFun(sym: KtFunctionSymbol): Boolean =
        sym.annotations.any { ann ->
            ann.simpleName == "Deprecated" &&
                ann.rawArgumentTexts.any {
                    it.contains("DeprecationLevel.HIDDEN") || it.replace(" ", "").contains("level=HIDDEN")
                }
        }

    private fun hasStdRuntimeErrorBase(cls: KtClassSymbol): Boolean =
        cls.superTypeFrameIndices.any { trIdx ->
            bundle.typeTranslator.translateSuperBaseType(stream.frames[trIdx]).second
        }

    private fun isCancellationExceptionSubclass(cls: KtClassSymbol): Boolean =
        cls.kotlinName.endsWith("CancellationException") ||
            cls.superTypeFrameIndices.any { trIdx ->
                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx])?.endsWith("CancellationException") == true
            }

    private fun bodyReferencesExpectOrDeferredBottomHeader(frame: PacketFrame): Boolean {
        if (includes.selfHeaderPath.isEmpty()) return false
        val expectNames =
            symbols.functionsByFrame.values
                .asSequence()
                .filter { FrameAstHelpers.hasModifierKeyword(stream, stream.frames[it.frameIndex], KtTokens.EXPECT_MODIFIER_ID) }
                .map { it.kotlinName }
                .toHashSet()
        symbols.classesByFrame.values
            .asSequence()
            .filter { FrameAstHelpers.hasModifierKeyword(stream, stream.frames[it.frameIndex], KtTokens.EXPECT_MODIFIER_ID) }
            .forEach { expectNames.add(it.kotlinName) }
        for (pi in frame.firstPacketIndex..frame.lastPacketIndex) {
            val pkt = stream.packets[pi]
            if (pkt !is SyntaxPacket.IdentPacket) continue
            val raw = pkt.unescapedName
            val resolved = symbols.importAliases[raw] ?: raw
            if (resolved in expectNames) return true
            if (CppTypeTranslator.isDeferredCircularSymbol(resolved, includes.selfHeaderPath)) return true
            val hdr = CppTypeTranslator.lookupKnownHeaderForSymbol(resolved)
            if (hdr != null && includes.hasDeferredBottomProject(hdr)) return true
        }
        return false
    }

    companion object {
        private val STAR_STD_ANY_IN_ANGLE_REGEX: Regex =
            Regex("""(?:<|,\s*)(std::any)(?=\s*[,>])""")

        private val BOOL_BINARY_OPERATOR_IDS: Set<Int> =
            setOf(
                KtTokens.EQEQ_ID,
                KtTokens.EXCLEQ_ID,
                KtTokens.EQEQEQ_ID,
                KtTokens.EXCLEQEQEQ_ID,
                KtTokens.LT_ID,
                KtTokens.GT_ID,
                KtTokens.LTEQ_ID,
                KtTokens.GTEQ_ID,
                KtTokens.ANDAND_ID,
                KtTokens.OROR_ID,
                KtTokens.IN_KEYWORD_ID,
                KtTokens.NOT_IN_ID,
                KtTokens.IS_KEYWORD_ID,
                KtTokens.NOT_IS_ID,
            )

        private val KNOWN_TO_STRING_BASE_CLASSES: Set<String> =
            setOf(
                "CoroutineContext",
                "Element",
                "AbstractCoroutineContextElement",
                "ContinuationInterceptor",
                "CoroutineDispatcher",
                "MainCoroutineDispatcher",
                "ExecutorCoroutineDispatcher",
                "EventLoop",
                "EventLoopImplBase",
                "EventLoopImplPlatform",
                "Job",
                "CompletableJob",
                "JobSupport",
                "AbstractCoroutine",
                "ScopeCoroutine",
                "LockFreeLinkedListNode",
                "LockFreeLinkedListHead",
                "Segment",
                "ConcurrentLinkedListNode",
                "ChannelFlow",
                "ChannelFlowOperator",
                "SynchronizedObject",
                "BaseContinuationImpl",
                "ContinuationImpl",
                "DispatchedTask",
                "DispatchedContinuation",
                "CancellableContinuationImpl",
                "DelayedTask",
                "Task",
            )

        private val CONTINUATION_HIERARCHY_BASES: Set<String> =
            setOf(
                "Continuation",
                "CancellableContinuation",
                "CancellableContinuationImpl",
                "AbstractCoroutine",
                "ScopeCoroutine",
                "DispatchedContinuation",
                "UndispatchedCoroutine",
                "CoroutineImpl",
                "BaseContinuationImpl",
                "ContinuationImpl",
                "RestrictedContinuationImpl",
            )

        private val ASSIGN_OPERATOR_IDS: Set<Int> =
            setOf(
                KtTokens.EQ_ID,
                KtTokens.PLUSEQ_ID,
                KtTokens.MINUSEQ_ID,
                KtTokens.MULTEQ_ID,
                KtTokens.DIVEQ_ID,
                KtTokens.PERCEQ_ID,
            )

        private val VALUE_PASS_CPP_TYPES: Set<String> =
            setOf(
                "bool",
                "std::int8_t",
                "std::int16_t",
                "std::int32_t",
                "std::int64_t",
                "std::uint8_t",
                "std::uint16_t",
                "std::uint32_t",
                "std::uint64_t",
                "float",
                "double",
                "char16_t",
                "std::size_t",
                "size_t",
                "ssize_t",
                "ptrdiff_t",
                "std::intptr_t",
                "std::uintptr_t",
                "void*",
                "const char*",
                "char*",
                "std::string_view",
            )
    }
}

private fun emitCompanionCAbiArtifacts(bundle: LoweringBundle): List<CppFileArtifact> {
    val symbols = bundle.symbols
    val headerFileName = "${symbols.fileStem}_c_api.h"
    val defFileName = "${symbols.fileStem}.def"

    val cHeaderText =
        buildString {
            append("#pragma once\n")
            append("#include <stdint.h>\n")
            append("#include <stdbool.h>\n")
            append("#include <stddef.h>\n\n")
            append("#ifdef __cplusplus\nextern \"C\" {\n#endif\n\n")
            for (fnIdx in bundle.ffiPlan.cAbiExportFunctionFrames) {
                val fn = symbols.functionsByFrame[fnIdx] ?: continue
                val cName = fn.cAbiSymbolName ?: continue
                val retType =
                    fn.returnTypeFrameIndex?.let {
                        mapCTypeForPlainC(bundle.typeTranslator.translateTypeFrame(bundle.stream.frames[it], CppTypePosition.RETURN))
                    } ?: "void"
                val cParams =
                    if (fn.valueParams.isEmpty()) {
                        "void"
                    } else {
                        fn.valueParams.joinToString(", ") { vp ->
                            val rawT =
                                vp.typeFrameIndex?.let {
                                    bundle.typeTranslator.translateTypeFrame(bundle.stream.frames[it], CppTypePosition.PARAMETER)
                                } ?: "void*"
                            "${mapCTypeForPlainC(rawT)} ${vp.cppName}"
                        }
                    }
                append(retType)
                    .append(' ')
                    .append(cName)
                    .append('(')
                    .append(cParams)
                    .append(");\n")
            }
            append("\n#ifdef __cplusplus\n}\n#endif\n")
        }

    val defText =
        buildString {
            append("headers = ").append(headerFileName).append('\n')
            append("headerFilter = ").append(headerFileName).append('\n')
        }

    return listOf(
        CppFileArtifact(relativePath = headerFileName, content = cHeaderText),
        CppFileArtifact(relativePath = defFileName, content = defText),
    )
}

private fun mapCTypeForPlainC(cppType: String): String =
    cppType
        .replace("std::int8_t", "int8_t")
        .replace("std::int16_t", "int16_t")
        .replace("std::int32_t", "int32_t")
        .replace("std::int64_t", "int64_t")
        .replace("std::uint8_t", "uint8_t")
        .replace("std::uint16_t", "uint16_t")
        .replace("std::uint32_t", "uint32_t")
        .replace("std::uint64_t", "uint64_t")
        .replace("std::size_t", "size_t")
        .replace("std::intptr_t", "intptr_t")
        .replace("std::uintptr_t", "uintptr_t")

internal fun renderDocExampleFromPacketStream(stream: PacketStream): Pair<String, String> {
    val config = CppTargetConfig()
    val symbols = SameFileSymbolPass.run(stream = stream, config = config)
    val suspendPlan = SuspendStateMachinePass.run(stream = stream, symbols = symbols)
    val gcPlan = GcAndOwnershipPass.run(stream = stream, config = config, symbols = symbols, suspendPlan = suspendPlan)
    val atomicPlan = AtomicAndThreadLocalPass.run(stream = stream, config = config, symbols = symbols)
    val ffiPlan = FfiAndCTypePass.run(stream = stream, symbols = symbols, suspendPlan = suspendPlan)
    val cfPlan = ControlFlowAndTypePass.run(stream = stream, symbols = symbols)

    val headerIncludes = CppIncludeCollector(selfHeaderPath = symbols.headerRelativePath)
    val sourceIncludes = CppIncludeCollector(selfHeaderPath = "")
    val globalTypeParams = LinkedHashSet<String>()
    for (cls in symbols.classesByFrame.values) {
        for (tp in cls.typeParams) globalTypeParams.add(tp.name)
    }
    for (fn in symbols.functionsByFrame.values) {
        for (tp in fn.typeParams) globalTypeParams.add(tp.name)
    }
    for (prop in symbols.propertiesByFrame.values) {
        for (tp in prop.typeParams) globalTypeParams.add(tp.name)
    }
    for (ta in symbols.typeAliasesByFrame.values) {
        for (tp in ta.typeParams) globalTypeParams.add(tp.name)
    }
    val knownFnNames = LinkedHashSet<String>()
    for (fn in symbols.functionsByFrame.values) {
        knownFnNames.add(fn.kotlinName)
    }

    val headerBundle =
        LoweringBundle(
            stream = stream,
            config = config,
            symbols = symbols,
            suspendPlan = suspendPlan,
            gcPlan = gcPlan,
            atomicPlan = atomicPlan,
            ffiPlan = ffiPlan,
            cfPlan = cfPlan,
            includes = headerIncludes,
            typeTranslator =
                CppTypeTranslator(
                    stream = stream,
                    config = config,
                    includes = headerIncludes,
                    polymorphicTypeNames = symbols.polymorphicTypeNames,
                    typeParamNames = globalTypeParams,
                    sharedHierarchyTypeNames = symbols.sharedHierarchyTypeNames,
                ),
            knownFunctionNames = knownFnNames,
            isDocExample = true,
        )
    val sourceBundle =
        LoweringBundle(
            stream = stream,
            config = config,
            symbols = symbols,
            suspendPlan = suspendPlan,
            gcPlan = gcPlan,
            atomicPlan = atomicPlan,
            ffiPlan = ffiPlan,
            cfPlan = cfPlan,
            includes = sourceIncludes,
            typeTranslator =
                CppTypeTranslator(
                    stream = stream,
                    config = config,
                    includes = sourceIncludes,
                    polymorphicTypeNames = symbols.polymorphicTypeNames,
                    typeParamNames = globalTypeParams,
                    sharedHierarchyTypeNames = symbols.sharedHierarchyTypeNames,
                ),
            knownFunctionNames = knownFnNames,
            isDocExample = true,
        )
    val headerBody = CppDeclarationRenderer(headerBundle, isHeaderMode = true).renderBodySection()
    val sourceBody = CppDeclarationRenderer(sourceBundle, isHeaderMode = false).renderBodySection()
    return headerBody to sourceBody
}
