package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketStream
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
                if (ubSimple != null &&
                    (ubSimple in symbols.sharedHierarchyTypeNames || CppTypeTranslator.isSharedHierarchyType(ubSimple))
                ) {
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
    val localClassCppNames = LinkedHashMap<String, String>()
    val localClassTypeParams = LinkedHashMap<String, List<String>>()
    for (cls in symbols.classesByFrame.values) {
        if (!FrameAstHelpers.hasModifierKeyword(stream, stream.frames[cls.frameIndex], KtTokens.EXPECT_MODIFIER_ID)) {
            localClassNames.add(cls.kotlinName)
            if (cls.enclosingClassFrameIndex == null) {
                localClassCppNames[cls.kotlinName] = cls.cppName
            }
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
            localClassCppNames = localClassCppNames,
            localClassTypeParams = localClassTypeParams,
            sharedTypeParamNames = sharedTypeParams,
            sharedHierarchyTypeNames = symbols.sharedHierarchyTypeNames,
            sharedPtrTypeAliases = symbols.sharedPtrTypeAliases,
            nonInnerNestedRewrites = symbols.nonInnerNestedRewrites,
            uniqueNonInnerNestedCppNames = symbols.uniqueNonInnerNestedCppNames,
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
            localClassCppNames = localClassCppNames,
            localClassTypeParams = localClassTypeParams,
            sharedTypeParamNames = sharedTypeParams,
            sharedHierarchyTypeNames = symbols.sharedHierarchyTypeNames,
            sharedPtrTypeAliases = symbols.sharedPtrTypeAliases,
            nonInnerNestedRewrites = symbols.nonInnerNestedRewrites,
            uniqueNonInnerNestedCppNames = symbols.uniqueNonInnerNestedCppNames,
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

    val hasActualDecls =
        symbols.classesByFrame.keys.any { FrameAstHelpers.hasModifierKeyword(stream, stream.frames[it], KtTokens.ACTUAL_MODIFIER_ID) } ||
            symbols.functionsByFrame.keys.any { FrameAstHelpers.hasModifierKeyword(stream, stream.frames[it], KtTokens.ACTUAL_MODIFIER_ID) } ||
            symbols.propertiesByFrame.keys.any { FrameAstHelpers.hasModifierKeyword(stream, stream.frames[it], KtTokens.ACTUAL_MODIFIER_ID) } ||
            symbols.typeAliasesByFrame.keys.any { FrameAstHelpers.hasModifierKeyword(stream, stream.frames[it], KtTokens.ACTUAL_MODIFIER_ID) }

    val headerRenderer = CppDeclarationRenderer(headerBundle, isHeaderMode = true)
    val headerBodyText = headerRenderer.renderBodySection()
    val sourceBodyText = CppDeclarationRenderer(sourceBundle, isHeaderMode = false).renderBodySection()

    val fullHeaderContent =
        assembleHeaderFile(
            symbols = symbols,
            ffiPlan = ffiPlan,
            includes = headerIncludes,
            bodyText = headerBodyText,
            postIncludeBodies = headerRenderer.postIncludeHeaderBodies,
            hasActualDecls = hasActualDecls,
        )
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

private fun headerGuardMacro(relPath: String): String =
    "KXS_HDR_" + relPath.uppercase().replace(Regex("[^A-Z0-9]+"), "_") + "_"

private fun pairedCommonHeaderForActual(relPath: String): String =
    when (relPath) {
        "kotlinx/coroutines/native/CoroutineContext.hpp" -> "kotlinx/coroutines/CoroutineContext.common.hpp"
        "kotlinx/coroutines/nativeDarwin/Dispatchers.hpp",
        "kotlinx/coroutines/nativeOther/Dispatchers.hpp",
        -> "kotlinx/coroutines/Dispatchers.common.hpp"
        else -> relPath.removeSuffix(".hpp") + ".common.hpp"
    }

private data class FlowCycleSpec(
    val path: String,
    val inMacro: String,
    val completeMacro: String,
    val postMacro: String,
)

private val FLOW_CYCLE_SPECS: List<FlowCycleSpec> =
    listOf(
        FlowCycleSpec(
            path = "kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp",
            inMacro = "KXS_IN_ABSTRACT_SHARED_FLOW_HPP_",
            completeMacro = "KXS_COMPLETE_ABSTRACT_SHARED_FLOW_HPP_",
            postMacro = "KXS_POST_ABSTRACT_SHARED_FLOW_HPP_",
        ),
        FlowCycleSpec(
            path = "kotlinx/coroutines/flow/SharedFlow.hpp",
            inMacro = "KXS_IN_SHARED_FLOW_HPP_",
            completeMacro = "KXS_COMPLETE_SHARED_FLOW_HPP_",
            postMacro = "KXS_POST_SHARED_FLOW_HPP_",
        ),
        FlowCycleSpec(
            path = "kotlinx/coroutines/flow/StateFlow.hpp",
            inMacro = "KXS_IN_STATE_FLOW_HPP_",
            completeMacro = "KXS_COMPLETE_STATE_FLOW_HPP_",
            postMacro = "KXS_POST_STATE_FLOW_HPP_",
        ),
        FlowCycleSpec(
            path = "kotlinx/coroutines/flow/Share.hpp",
            inMacro = "KXS_IN_SHARE_HPP_",
            completeMacro = "KXS_COMPLETE_SHARE_HPP_",
            postMacro = "KXS_POST_SHARE_HPP_",
        ),
    )

private val FLOW_CYCLE_HEADERS: Set<String> = FLOW_CYCLE_SPECS.map { it.path }.toSet()

private fun appendFlowCycleFooter(
    out: StringBuilder,
    relPath: String,
    guardMacro: String,
    nsJoined: String,
    includes: CppIncludeCollector,
    postIncludeBodies: List<String>,
) {
    val nonCycleBottom =
        includes.sortedDeferredBottomProjectHeaders().filter { it !in FLOW_CYCLE_HEADERS }
    if (nonCycleBottom.isNotEmpty()) {
        out.append('\n')
        for (btmHdr in nonCycleBottom) {
            out.append("#if __has_include(\"").append(btmHdr).append("\")\n")
            out.append("#include \"").append(btmHdr).append("\"\n")
            out.append("#endif\n")
        }
    }
    val selfIdx = FLOW_CYCLE_SPECS.indexOfFirst { it.path == relPath }
    val selfSpec = FLOW_CYCLE_SPECS[selfIdx]
    out.append("#undef ").append(selfSpec.inMacro).append('\n')
    out.append("#define ").append(selfSpec.completeMacro).append('\n')
    out.append("#endif // ").append(guardMacro).append('\n')

    for (i in FLOW_CYCLE_SPECS.indices) {
        if (i == selfIdx) continue
        val spec = FLOW_CYCLE_SPECS[i]
        out.append("\n#if ")
        for (k in 0 until i) {
            if (k == selfIdx) continue
            val prereq = FLOW_CYCLE_SPECS[k]
            out
                .append("(defined(")
                .append(prereq.completeMacro)
                .append(") || !__has_include(\"")
                .append(prereq.path)
                .append("\")) && ")
        }
        out
            .append("!defined(")
            .append(spec.inMacro)
            .append(") && !defined(")
            .append(spec.completeMacro)
            .append(") && __has_include(\"")
            .append(spec.path)
            .append("\")\n")
        out.append("#include \"").append(spec.path).append("\"\n")
        out.append("#endif\n")
    }

    val allCompleteCond =
        FLOW_CYCLE_SPECS.joinToString(" && ") { spec ->
            "(defined(${spec.completeMacro}) || !__has_include(\"${spec.path}\"))"
        }
    val absSpec = FLOW_CYCLE_SPECS[0]
    if (selfIdx != 0) {
        out
            .append("\n#if ")
            .append(allCompleteCond)
            .append(" && !defined(")
            .append(absSpec.postMacro)
            .append(") && __has_include(\"")
            .append(absSpec.path)
            .append("\")\n")
        out.append("#include \"").append(absSpec.path).append("\"\n")
        out.append("#endif\n")
    }

    val postPrereq =
        if (selfIdx == 0) {
            allCompleteCond
        } else {
            "$allCompleteCond && (defined(${absSpec.postMacro}) || !__has_include(\"${absSpec.path}\"))"
        }
    out
        .append("\n#if ")
        .append(postPrereq)
        .append(" && !defined(")
        .append(selfSpec.postMacro)
        .append(")\n")
    out.append("#define ").append(selfSpec.postMacro).append('\n')
    if (postIncludeBodies.isNotEmpty()) {
        if (nsJoined.isNotEmpty()) {
            out.append("namespace ").append(nsJoined).append(" {\n\n")
        }
        for ((idx, b) in postIncludeBodies.withIndex()) {
            if (idx > 0) out.append('\n')
            out.append(b.trimEnd()).append('\n')
        }
        if (nsJoined.isNotEmpty()) {
            out.append("\n} // namespace ").append(nsJoined).append('\n')
        }
    }
    for (k in 1 until FLOW_CYCLE_SPECS.size) {
        if (k == selfIdx) continue
        val peer = FLOW_CYCLE_SPECS[k]
        out
            .append("#if !defined(")
            .append(peer.postMacro)
            .append(") && __has_include(\"")
            .append(peer.path)
            .append("\")\n")
        out.append("#include \"").append(peer.path).append("\"\n")
        out.append("#endif\n")
    }
    out.append("#endif // ").append(selfSpec.postMacro).append('\n')
}

private fun assembleHeaderFile(
    symbols: SameFileSymbolTable,
    ffiPlan: FfiAndCTypePlan,
    includes: CppIncludeCollector,
    bodyText: String,
    postIncludeBodies: List<String> = emptyList(),
    hasActualDecls: Boolean = false,
): String =
    buildString {
        val relPath = symbols.headerRelativePath
        val isFlowCycleHeader = relPath in FLOW_CYCLE_HEADERS
        val isCycleHeader = !isFlowCycleHeader && CppTypeTranslator.isCycleParticipantHeader(relPath)
        val isCommonHeader = relPath.endsWith(".common.hpp")
        val isActualHeader = hasActualDecls && !isCommonHeader && !isFlowCycleHeader && relPath.endsWith(".hpp")
        val guardMacro = headerGuardMacro(relPath)

        if (!isFlowCycleHeader && !isCycleHeader && !isCommonHeader && !isActualHeader) {
            append("#pragma once\n")
        }
        if (symbols.portLintDirectiveKind != null && symbols.portLintSourcePath != null) {
            append("// port-lint: ")
                .append(symbols.portLintDirectiveKind)
                .append(' ')
                .append(symbols.portLintSourcePath)
                .append('\n')
        }
        if (isCycleHeader) {
            append("#ifndef ").append(guardMacro).append('\n')
            append("#ifndef ").append(guardMacro).append("IN_P1\n")
            append("#define ").append(guardMacro).append("IN_P1\n")
            if (isActualHeader) {
                val commonHdr = pairedCommonHeaderForActual(relPath)
                append("#if __has_include(\"").append(commonHdr).append("\")\n")
                append("#include \"").append(commonHdr).append("\"\n")
                append("#endif\n")
            }
        } else if (isFlowCycleHeader || isCommonHeader || isActualHeader) {
            append("#ifndef ").append(guardMacro).append('\n')
            append("#define ").append(guardMacro).append('\n')
            if (isActualHeader) {
                val commonHdr = pairedCommonHeaderForActual(relPath)
                append("#if __has_include(\"").append(commonHdr).append("\")\n")
                append("#include \"").append(commonHdr).append("\"\n")
                append("#endif\n")
            }
            when (relPath) {
                "kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp" -> append("#define KXS_IN_ABSTRACT_SHARED_FLOW_HPP_\n")
                "kotlinx/coroutines/flow/SharedFlow.hpp" -> append("#define KXS_IN_SHARED_FLOW_HPP_\n")
                "kotlinx/coroutines/flow/StateFlow.hpp" -> append("#define KXS_IN_STATE_FLOW_HPP_\n")
                "kotlinx/coroutines/flow/Share.hpp" -> append("#define KXS_IN_SHARE_HPP_\n")
            }
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
            if (projHdr == relPath) continue
            append("#if __has_include(\"").append(projHdr).append("\")\n")
            append("#include \"").append(projHdr).append("\"\n")
            append("#endif\n")
        }

        for (preamble in ffiPlan.preambleInlineCppBlocks) {
            append('\n').append(preamble.trimIndent()).append('\n')
        }
        emitSharedHelperPreamble(includes, this)

        if (relPath == "kotlinx/coroutines/internal/ConcurrentLinkedList.hpp") {
            append(
                """
                #ifndef KXS_NOT_COMPLETED_DEFINED
                #define KXS_NOT_COMPLETED_DEFINED
                namespace kotlinx::coroutines {
                class NotCompleted {
                public:
                    virtual ~NotCompleted() = default;
                };
                } // namespace kotlinx::coroutines
                #endif

                """.trimIndent(),
            )
        }

        val nsJoined = symbols.namespaceSegments.joinToString("::")
        if (nsJoined.isNotEmpty()) {
            append("\nnamespace ").append(nsJoined).append(" {\n\n")
        } else {
            append('\n')
        }

        if (nsJoined.startsWith("kotlinx::coroutines::")) {
            append("using namespace kotlinx::coroutines;\n\n")
        }

        if (symbols.imports.any { it.isWildcard && it.fqName == "kotlin.test" } && nsJoined != "kotlin::test") {
            append("using namespace kotlin::test;\n\n")
        }

        if (nsJoined.isNotEmpty() && includes.needsNullOperatorsDef) {
            append("using ::operator==;\n\n")
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
        if (relPath == "kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp" &&
            !bodyText.contains("class SubscriptionCountStateFlow;")
        ) {
            append("class SubscriptionCountStateFlow;\n\n")
        }
        if (relPath == "kotlinx/coroutines/CancellableContinuationImpl.hpp" &&
            !bodyText.contains("class ChildContinuation;")
        ) {
            append("class ChildContinuation;\n\n")
        }

        append(bodyText.trimEnd()).append('\n')

        if (nsJoined.isNotEmpty()) {
            append("\n} // namespace ").append(nsJoined).append('\n')
        }

        if (relPath == "kotlinx/coroutines/internal/Symbol.hpp") {
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

        if (isFlowCycleHeader) {
            appendFlowCycleFooter(this, relPath, guardMacro, nsJoined, includes, postIncludeBodies)
            return@buildString
        }

        val deferredBottom = includes.sortedDeferredBottomProjectHeaders()
        fun emitExpectActualIncludes() {
            if (!includes.hasExpectDeclarations) return
            if (relPath.endsWith(".common.hpp")) {
                append('\n')
                when (relPath) {
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
                        val actualHdr = relPath.removeSuffix(".common.hpp") + ".hpp"
                        append("#if __has_include(\"").append(actualHdr).append("\")\n")
                        append("#include \"").append(actualHdr).append("\"\n")
                        append("#endif\n")
                    }
                }
            } else if (
                relPath == "kotlinx/coroutines/Dispatchers.hpp" ||
                relPath == "kotlinx/coroutines/native/CoroutineContext.hpp"
            ) {
                append('\n')
                append("#if defined(__APPLE__) && __has_include(\"kotlinx/coroutines/nativeDarwin/Dispatchers.hpp\")\n")
                append("#include \"kotlinx/coroutines/nativeDarwin/Dispatchers.hpp\"\n")
                append("#elif __has_include(\"kotlinx/coroutines/nativeOther/Dispatchers.hpp\")\n")
                append("#include \"kotlinx/coroutines/nativeOther/Dispatchers.hpp\"\n")
                append("#endif\n")
            }
        }

        fun emitPostIncludeBodiesBlock() {
            if (postIncludeBodies.isEmpty()) return
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

        if (isCycleHeader) {
            append("#undef ").append(guardMacro).append("IN_P1\n")
            append("#define ").append(guardMacro).append('\n')
            append("#endif // ").append(guardMacro).append("IN_P1\n")
            append("#endif // ").append(guardMacro).append('\n')

            val btmCycleGuards =
                buildSet {
                    for (h in deferredBottom) {
                        if (h !in FLOW_CYCLE_HEADERS && CppTypeTranslator.isCycleParticipantHeader(h)) {
                            add(headerGuardMacro(h))
                        }
                    }
                    if (relPath == "kotlinx/coroutines/CoroutineScope.hpp") {
                        add(headerGuardMacro("kotlinx/coroutines/JobSupport.hpp"))
                        add(headerGuardMacro("kotlinx/coroutines/AbstractCoroutine.hpp"))
                        add(headerGuardMacro("kotlinx/coroutines/internal/DispatchedContinuation.hpp"))
                    }
                }
            append("\n#if defined($guardMacro) && !defined(${guardMacro}P2) && !defined(${guardMacro}IN_P2)")
            for (bg in btmCycleGuards) {
                append(" && !defined(${bg}IN_P1)")
            }
            append('\n')
            append("#define ${guardMacro}IN_P2\n")
            if (deferredBottom.isNotEmpty()) {
                append('\n')
                for (btmHdr in deferredBottom) {
                    val isBtmCycle = btmHdr !in FLOW_CYCLE_HEADERS && CppTypeTranslator.isCycleParticipantHeader(btmHdr)
                    if (isBtmCycle) {
                        val bg = headerGuardMacro(btmHdr)
                        append("#if !defined(${bg}P2) && !defined(${bg}IN_P2) && __has_include(\"$btmHdr\")\n")
                    } else {
                        append("#if __has_include(\"$btmHdr\")\n")
                    }
                    append("#include \"$btmHdr\"\n")
                    append("#endif\n")
                }
            }
            emitExpectActualIncludes()
            emitPostIncludeBodiesBlock()
            append("#undef ${guardMacro}IN_P2\n")
            append("#define ${guardMacro}P2\n")
            val scopePeerHeaders =
                setOf(
                    "kotlinx/coroutines/JobSupport.hpp",
                    "kotlinx/coroutines/AbstractCoroutine.hpp",
                    "kotlinx/coroutines/internal/DispatchedContinuation.hpp",
                )
            val extraScopePeers =
                if (relPath in scopePeerHeaders) {
                    listOf("kotlinx/coroutines/CoroutineScope.hpp")
                } else {
                    emptyList()
                }
            for (peerHdr in (CppTypeTranslator.cyclePeersForHeader(relPath) + extraScopePeers).distinct()) {
                if (peerHdr in FLOW_CYCLE_HEADERS || peerHdr == relPath) continue
                val pg = headerGuardMacro(peerHdr)
                append(
                    "#if defined($pg) && !defined(${pg}P2) && !defined(${pg}IN_P2) && " +
                        "!defined(${pg}IN_P1) && __has_include(\"$peerHdr\")\n",
                )
                append("#include \"$peerHdr\"\n")
                append("#endif\n")
            }
            append("#endif // ").append(guardMacro).append("P2\n")
            return@buildString
        }

        if (deferredBottom.isNotEmpty()) {
            append('\n')
            for (btmHdr in deferredBottom) {
                append("#if __has_include(\"").append(btmHdr).append("\")\n")
                append("#include \"").append(btmHdr).append("\"\n")
                append("#endif\n")
            }
        }
        emitExpectActualIncludes()
        emitPostIncludeBodiesBlock()
        if (isCommonHeader || isActualHeader) {
            append("#endif // ").append(guardMacro).append('\n')
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
                append("#if __has_include(\"").append(projHdr).append("\")\n")
                append("#include \"").append(projHdr).append("\"\n")
                append("#endif\n")
            }
        }
        append("#include \"").append(symbols.headerRelativePath).append("\"\n")
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

        if (nsJoined.isNotEmpty() && includes.needsNullOperatorsDef) {
            append("using ::operator==;\n\n")
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
    if (includes.needsAnyEqDef) {
        includes.needsCastHelpersDef = true
        includes.addSystem("string")
    }
    if (includes.needsCastHelpersDef) {
        includes.addSystem("any")
        includes.addSystem("cstddef")
        includes.addSystem("exception")
        includes.addSystem("memory")
        includes.addSystem("optional")
        includes.addSystem("stdexcept")
        includes.addSystem("type_traits")
        includes.addSystem("typeinfo")
        includes.addSystem("utility")
    }
    if (includes.needsResultHelpersDef) {
        includes.addSystem("any")
        includes.addSystem("exception")
        includes.addSystem("optional")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
    }
    if (includes.needsIterableHelpersDef) {
        includes.addSystem("iterator")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
        includes.addSystem("vector")
    }
    if (includes.needsEmptyContainersDef) {
        includes.addSystem("cstddef")
        includes.addSystem("unordered_map")
        includes.addSystem("unordered_set")
        includes.addSystem("utility")
        includes.addSystem("vector")
    }
    if (includes.needsTimeMarkOpsDef) {
        includes.addSystem("compare")
        includes.addSystem("cstdint")
    }
    if (includes.needsStringBuilderDef) {
        includes.addSystem("cstddef")
        includes.addSystem("cstdint")
        includes.addSystem("string")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
    }
    if (includes.needsNullOperatorsDef) {
        includes.addSystem("any")
        includes.addSystem("concepts")
        includes.addSystem("cstddef")
        includes.addSystem("optional")
    }
    if (includes.needsNullUnwrapDef) {
        includes.addSystem("any")
        includes.addSystem("exception")
        includes.addSystem("type_traits")
        includes.addSystem("typeinfo")
        includes.addSystem("utility")
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
    if (includes.needsDarwinDispatchHelpers) {
        includes.needsDarwinHeaders = true
        includes.addSystem("type_traits")
        includes.addSystem("utility")
    }
    if (includes.needsDarwinHeaders) {
        includes.addSystem("CoreFoundation/CoreFoundation.h")
        includes.addSystem("dispatch/dispatch.h")
    }
    if (includes.needsPthreadInclude) {
        includes.addSystem("pthread.h")
    }
    if (includes.needsCoroutineIntrinsicsBridgeDef) {
        includes.needsResultHelpersDef = true
        includes.needsSuspendBoxHelpersDef = true
        includes.addSystem("functional")
        includes.addProject("kotlinx/coroutines/intrinsics/IntrinsicsNative.hpp")
    }
    if (includes.needsAtomicReferenceDef) {
        includes.addSystem("any")
        includes.addSystem("atomic")
        includes.addSystem("concepts")
        includes.addSystem("cstddef")
        includes.addSystem("memory")
        includes.addSystem("mutex")
        includes.addSystem("string")
        includes.addSystem("type_traits")
        includes.addSystem("typeinfo")
        includes.addSystem("utility")
    }
    if (includes.needsCopyableAtomicDef) {
        includes.addSystem("atomic")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
    }
    if (includes.needsVarargDef) {
        includes.addSystem("concepts")
        includes.addSystem("initializer_list")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
        includes.addSystem("vector")
    }
    if (includes.needsSuspendBoxHelpersDef) {
        includes.addSystem("any")
        includes.addSystem("cstddef")
        includes.addSystem("memory")
        includes.addSystem("optional")
        includes.addSystem("stdexcept")
        includes.addSystem("type_traits")
        includes.addSystem("typeinfo")
        includes.addSystem("utility")
        includes.addProject("kotlinx/coroutines/Continuation.hpp")
        includes.addProject("kotlinx/coroutines/ContinuationBindings.hpp")
        includes.addProject("kotlinx/coroutines/Result.hpp")
        includes.addProject("kotlinx/coroutines/Unit.hpp")
        includes.addProject("kotlinx/coroutines/dsl/Suspend.hpp")
        includes.addProject("kotlinx/coroutines/intrinsics/Intrinsics.hpp")
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

    val docLocalClassCppNames =
        symbols.classesByFrame.values
            .filter {
                it.enclosingClassFrameIndex == null &&
                    !FrameAstHelpers.hasModifierKeyword(stream, stream.frames[it.frameIndex], KtTokens.EXPECT_MODIFIER_ID)
            }.associate { it.kotlinName to it.cppName }

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
                    localClassCppNames = docLocalClassCppNames,
                    sharedHierarchyTypeNames = symbols.sharedHierarchyTypeNames,
                    sharedPtrTypeAliases = symbols.sharedPtrTypeAliases,
                    nonInnerNestedRewrites = symbols.nonInnerNestedRewrites,
                    uniqueNonInnerNestedCppNames = symbols.uniqueNonInnerNestedCppNames,
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
                    localClassCppNames = docLocalClassCppNames,
                    sharedHierarchyTypeNames = symbols.sharedHierarchyTypeNames,
                    sharedPtrTypeAliases = symbols.sharedPtrTypeAliases,
                    nonInnerNestedRewrites = symbols.nonInnerNestedRewrites,
                    uniqueNonInnerNestedCppNames = symbols.uniqueNonInnerNestedCppNames,
                ),
            knownFunctionNames = knownFnNames,
            isDocExample = true,
        )
    val headerBody = CppDeclarationRenderer(headerBundle, isHeaderMode = true).renderBodySection()
    val sourceBody = CppDeclarationRenderer(sourceBundle, isHeaderMode = false).renderBodySection()
    return headerBody to sourceBody
}
