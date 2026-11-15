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
            struct KxsEmptySequence {
                template <typename E>
                operator KxsSequence<E>() const { return {}; }
            };
            inline KxsEmptySequence empty_sequence() { return {}; }
            template <typename E>
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
                if (!lhs) return std::const_pointer_cast<CoroutineContext>(std::static_pointer_cast<const CoroutineContext>(rhs));
                if (!rhs) return std::const_pointer_cast<CoroutineContext>(std::static_pointer_cast<const CoroutineContext>(lhs));
                return lhs->operator+(std::const_pointer_cast<CoroutineContext>(std::static_pointer_cast<const CoroutineContext>(rhs)));
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
                const std::type_info* eq_type_{nullptr};
                bool (*eq_fn_)(const std::any&, const std::any&){nullptr};

                static V kxs_null_v() {
                    return V{};
                }

                template <typename U>
                static bool kxs_any_typed_eq(const std::any& a, const std::any& b) {
                    const auto* pa = std::any_cast<U>(&a);
                    const auto* pb = std::any_cast<U>(&b);
                    if (!pa || !pb) return false;
                    if constexpr (requires(const U& x, const U& y) { x == y; }) {
                        return *pa == *pb;
                    } else {
                        return pa == pb;
                    }
                }

                static bool kxs_is_any_null(const std::any& a) noexcept {
                    return !a.has_value() || a.type() == typeid(std::nullptr_t);
                }

                static bool kxs_any_pair_eq(
                    const std::any& cur,
                    const std::any& expected,
                    const std::type_info* eq_type,
                    bool (*eq_fn)(const std::any&, const std::any&)
                ) {
                    if (kxs_is_any_null(cur) && kxs_is_any_null(expected)) return true;
                    if (kxs_is_any_null(cur) || kxs_is_any_null(expected)) return false;
                    if (cur.type() != expected.type()) return false;
                    if (eq_fn != nullptr && eq_type != nullptr && *eq_type == cur.type()) {
                        return eq_fn(cur, expected);
                    }
                    if (const auto* p1 = std::any_cast<void*>(&cur)) {
                        if (const auto* p2 = std::any_cast<void*>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<const void*>(&cur)) {
                        if (const auto* p2 = std::any_cast<const void*>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<std::int32_t>(&cur)) {
                        if (const auto* p2 = std::any_cast<std::int32_t>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<std::int64_t>(&cur)) {
                        if (const auto* p2 = std::any_cast<std::int64_t>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<bool>(&cur)) {
                        if (const auto* p2 = std::any_cast<bool>(&expected)) return *p1 == *p2;
                    }
                    if (const auto* p1 = std::any_cast<std::string>(&cur)) {
                        if (const auto* p2 = std::any_cast<std::string>(&expected)) return *p1 == *p2;
                    }
                    return false;
                }

                template <typename Exp>
                bool kxs_matches_unlocked(const Exp& expected) const {
                    using ExpD = std::remove_cvref_t<Exp>;
                    if constexpr (std::is_same_v<V, std::any>) {
                        if constexpr (std::is_same_v<ExpD, std::nullptr_t>) {
                            return kxs_is_any_null(val_);
                        } else if constexpr (std::is_same_v<ExpD, std::any>) {
                            return kxs_any_pair_eq(val_, expected, eq_type_, eq_fn_);
                        } else {
                            if (kxs_is_any_null(val_)) return false;
                            if (const auto* p = std::any_cast<ExpD>(&val_)) {
                                if constexpr (requires(const ExpD& x, const ExpD& y) { x == y; }) {
                                    return *p == expected;
                                } else {
                                    return false;
                                }
                            }
                            return false;
                        }
                    } else {
                        if constexpr (std::is_same_v<ExpD, std::nullptr_t>) {
                            if constexpr (requires { val_ == nullptr; }) {
                                return val_ == nullptr;
                            } else if constexpr (requires { !val_.has_value(); }) {
                                return !val_.has_value();
                            } else {
                                return false;
                            }
                        } else if constexpr (requires { val_ == expected; }) {
                            return val_ == expected;
                        } else {
                            V exp_v(expected);
                            if constexpr (requires { val_ == exp_v; }) {
                                return val_ == exp_v;
                            } else {
                                return false;
                            }
                        }
                    }
                }

                template <typename Des>
                void kxs_store_unlocked(Des&& desired) {
                    using DesD = std::decay_t<Des>;
                    if constexpr (std::is_same_v<V, std::any>) {
                        if constexpr (std::is_same_v<DesD, std::nullptr_t>) {
                            val_.reset();
                            eq_type_ = nullptr;
                            eq_fn_ = nullptr;
                        } else if constexpr (std::is_same_v<DesD, std::any>) {
                            if (kxs_is_any_null(desired)) {
                                val_.reset();
                                eq_type_ = nullptr;
                                eq_fn_ = nullptr;
                            } else {
                                if (eq_type_ == nullptr || *eq_type_ != desired.type()) {
                                    eq_type_ = nullptr;
                                    eq_fn_ = nullptr;
                                }
                                val_ = std::forward<Des>(desired);
                            }
                        } else {
                            val_ = std::forward<Des>(desired);
                            eq_type_ = &typeid(DesD);
                            eq_fn_ = &kxs_any_typed_eq<DesD>;
                        }
                    } else {
                        if constexpr (std::is_same_v<DesD, std::nullptr_t>) {
                            if constexpr (requires { val_.reset(); }) {
                                val_.reset();
                            } else {
                                val_ = kxs_null_v();
                            }
                        } else if constexpr (std::is_assignable_v<V&, Des>) {
                            val_ = std::forward<Des>(desired);
                        } else if constexpr (
                            requires { val_.emplace(*std::forward<Des>(desired)); } &&
                            requires { desired.has_value(); *desired; }
                        ) {
                            if (desired.has_value()) {
                                val_.emplace(*std::forward<Des>(desired));
                            } else {
                                val_.reset();
                            }
                        } else if constexpr (requires { val_.emplace(std::forward<Des>(desired)); }) {
                            val_.emplace(std::forward<Des>(desired));
                        } else {
                            val_ = static_cast<V>(std::forward<Des>(desired));
                        }
                    }
                }
                template <typename KxsDst, typename KxsSrc>
                static void kxs_assign_val(KxsDst& dst, KxsSrc&& src) {
                    if constexpr (std::is_assignable_v<KxsDst&, KxsSrc>) {
                        dst = std::forward<KxsSrc>(src);
                    } else if constexpr (
                        requires { dst.reset(); dst.emplace(*std::forward<KxsSrc>(src)); src.has_value(); }
                    ) {
                        if (src.has_value()) {
                            dst.emplace(*std::forward<KxsSrc>(src));
                        } else {
                            dst.reset();
                        }
                    } else if constexpr (std::is_constructible_v<KxsDst, KxsSrc>) {
                        std::destroy_at(std::addressof(dst));
                        std::construct_at(std::addressof(dst), std::forward<KxsSrc>(src));
                    }
                }
            public:
                KxsAtomicReference() = default;
                explicit KxsAtomicReference(std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) : val_(kxs_null_v()) {}
                explicit KxsAtomicReference(V v) : val_(std::move(v)) {}
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, KxsAtomicReference> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                explicit KxsAtomicReference(T&& v) {
                    kxs_store_unlocked(std::forward<T>(v));
                }
                KxsAtomicReference(const KxsAtomicReference& o) {
                    std::lock_guard<std::mutex> lk(o.mtx_);
                    kxs_assign_val(val_, o.val_);
                    eq_type_ = o.eq_type_;
                    eq_fn_ = o.eq_fn_;
                }
                KxsAtomicReference(KxsAtomicReference&& o) noexcept {
                    std::lock_guard<std::mutex> lk(o.mtx_);
                    kxs_assign_val(val_, std::move(o.val_));
                    eq_type_ = o.eq_type_;
                    eq_fn_ = o.eq_fn_;
                }
                KxsAtomicReference& operator=(const KxsAtomicReference& o) {
                    if (this != &o) {
                        std::scoped_lock lk(mtx_, o.mtx_);
                        kxs_assign_val(val_, o.val_);
                        eq_type_ = o.eq_type_;
                        eq_fn_ = o.eq_fn_;
                    }
                    return *this;
                }
                KxsAtomicReference& operator=(KxsAtomicReference&& o) noexcept {
                    if (this != &o) {
                        std::scoped_lock lk(mtx_, o.mtx_);
                        kxs_assign_val(val_, std::move(o.val_));
                        eq_type_ = o.eq_type_;
                        eq_fn_ = o.eq_fn_;
                    }
                    return *this;
                }
                V get_value() const {
                    std::lock_guard<std::mutex> lk(mtx_);
                    return val_;
                }
                void set_value(std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    kxs_store_unlocked(nullptr);
                }
                void set_value(V v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    kxs_store_unlocked(std::move(v));
                }
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                void set_value(T&& v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    kxs_store_unlocked(std::forward<T>(v));
                }
                V get_and_set(std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    V old = std::move(val_);
                    kxs_store_unlocked(nullptr);
                    return old;
                }
                V get_and_set(V v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    V old = std::move(val_);
                    kxs_store_unlocked(std::move(v));
                    return old;
                }
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                V get_and_set(T&& v) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    V old = std::move(val_);
                    kxs_store_unlocked(std::forward<T>(v));
                    return old;
                }
                bool compare_and_set(const V& expected, V desired) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(std::move(desired));
                        return true;
                    }
                    return false;
                }
                bool compare_and_set(std::nullptr_t, V desired) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(nullptr)) {
                        kxs_store_unlocked(std::move(desired));
                        return true;
                    }
                    return false;
                }
                bool compare_and_set(const V& expected, std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(nullptr);
                        return true;
                    }
                    return false;
                }
                bool compare_and_set(std::nullptr_t, std::nullptr_t) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(nullptr)) {
                        kxs_store_unlocked(nullptr);
                        return true;
                    }
                    return false;
                }
                template <typename Exp, typename Des>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !(std::same_as<std::remove_cvref_t<Exp>, std::any> && std::same_as<std::remove_cvref_t<Des>, std::any>) &&
                        !(std::same_as<std::remove_cvref_t<Exp>, std::nullptr_t> && std::same_as<std::remove_cvref_t<Des>, std::nullptr_t>) &&
                        !(std::same_as<std::remove_cvref_t<Exp>, std::nullptr_t> && std::same_as<std::remove_cvref_t<Des>, std::any>) &&
                        !(std::same_as<std::remove_cvref_t<Exp>, std::any> && std::same_as<std::remove_cvref_t<Des>, std::nullptr_t>)
                    )
                bool compare_and_set(Exp&& expected, Des&& desired) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(std::forward<Des>(desired));
                        return true;
                    }
                    return false;
                }
                V load(std::memory_order = std::memory_order_seq_cst) const { return get_value(); }
                void store(std::nullptr_t, std::memory_order = std::memory_order_seq_cst) requires (!std::is_same_v<V, std::nullptr_t>) { set_value(nullptr); }
                void store(V v, std::memory_order = std::memory_order_seq_cst) { set_value(std::move(v)); }
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                void store(T&& v, std::memory_order = std::memory_order_seq_cst) { set_value(std::forward<T>(v)); }
                V exchange(std::nullptr_t, std::memory_order = std::memory_order_seq_cst) requires (!std::is_same_v<V, std::nullptr_t>) { return get_and_set(nullptr); }
                V exchange(V v, std::memory_order = std::memory_order_seq_cst) { return get_and_set(std::move(v)); }
                template <typename T>
                    requires (
                        std::is_same_v<V, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::any> &&
                        !std::same_as<std::remove_cvref_t<T>, std::nullptr_t>
                    )
                V exchange(T&& v, std::memory_order = std::memory_order_seq_cst) { return get_and_set(std::forward<T>(v)); }
                bool compare_exchange_strong(
                    V& expected,
                    V desired,
                    std::memory_order = std::memory_order_seq_cst,
                    std::memory_order = std::memory_order_seq_cst
                ) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(std::move(desired));
                        return true;
                    }
                    expected = val_;
                    return false;
                }
                bool compare_exchange_strong(
                    V& expected,
                    std::nullptr_t,
                    std::memory_order = std::memory_order_seq_cst,
                    std::memory_order = std::memory_order_seq_cst
                ) requires (!std::is_same_v<V, std::nullptr_t>) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(nullptr);
                        return true;
                    }
                    expected = val_;
                    return false;
                }
                template <typename Exp, typename Des>
                    requires (!std::same_as<std::remove_cvref_t<Exp>, V>)
                bool compare_exchange_strong(
                    Exp& expected,
                    Des&& desired,
                    std::memory_order = std::memory_order_seq_cst,
                    std::memory_order = std::memory_order_seq_cst
                ) {
                    std::lock_guard<std::mutex> lk(mtx_);
                    if (kxs_matches_unlocked(expected)) {
                        kxs_store_unlocked(std::forward<Des>(desired));
                        return true;
                    }
                    if constexpr (!std::is_const_v<Exp> && std::is_assignable_v<Exp&, const V&>) {
                        expected = val_;
                    }
                    return false;
                }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsCopyableAtomicDef) {
        out.append(
            """

            #ifndef KXS_COPYABLE_ATOMIC_DEFINED
            #define KXS_COPYABLE_ATOMIC_DEFINED
            template <typename T>
            class KxsCopyableAtomic {
            private:
                std::atomic<T> val_{};
            public:
                KxsCopyableAtomic() = default;
                constexpr KxsCopyableAtomic(T v) noexcept : val_(v) {}
                KxsCopyableAtomic(const KxsCopyableAtomic& o) noexcept : val_(o.val_.load(std::memory_order_seq_cst)) {}
                KxsCopyableAtomic(KxsCopyableAtomic&& o) noexcept : val_(o.val_.load(std::memory_order_seq_cst)) {}
                KxsCopyableAtomic& operator=(const KxsCopyableAtomic& o) noexcept {
                    if (this != &o) val_.store(o.val_.load(std::memory_order_seq_cst), std::memory_order_seq_cst);
                    return *this;
                }
                KxsCopyableAtomic& operator=(KxsCopyableAtomic&& o) noexcept {
                    if (this != &o) val_.store(o.val_.load(std::memory_order_seq_cst), std::memory_order_seq_cst);
                    return *this;
                }
                T operator=(T desired) noexcept {
                    val_.store(desired, std::memory_order_seq_cst);
                    return desired;
                }
                operator std::atomic<T>&() noexcept { return val_; }
                operator const std::atomic<T>&() const noexcept { return val_; }
                T load(std::memory_order order = std::memory_order_seq_cst) const noexcept { return val_.load(order); }
                void store(T desired, std::memory_order order = std::memory_order_seq_cst) noexcept { val_.store(desired, order); }
                T exchange(T desired, std::memory_order order = std::memory_order_seq_cst) noexcept { return val_.exchange(desired, order); }
                bool compare_exchange_strong(
                    T& expected,
                    T desired,
                    std::memory_order success = std::memory_order_seq_cst,
                    std::memory_order failure = std::memory_order_seq_cst
                ) noexcept {
                    return val_.compare_exchange_strong(expected, desired, success, failure);
                }
                bool compare_exchange_weak(
                    T& expected,
                    T desired,
                    std::memory_order success = std::memory_order_seq_cst,
                    std::memory_order failure = std::memory_order_seq_cst
                ) noexcept {
                    return val_.compare_exchange_weak(expected, desired, success, failure);
                }
                T get_value() const noexcept { return load(); }
                void set_value(T v) noexcept { store(v); }
                T get_and_set(T v) noexcept { return exchange(v); }
                bool compare_and_set(T expected, T desired) noexcept { return val_.compare_exchange_strong(expected, desired); }
                T fetch_add(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_add(arg, order); }
                T fetch_sub(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_sub(arg, order); }
                T fetch_and(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_and(arg, order); }
                T fetch_or(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_or(arg, order); }
                T fetch_xor(T arg, std::memory_order order = std::memory_order_seq_cst) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_.fetch_xor(arg, order); }
                T operator+=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ += arg; }
                T operator-=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ -= arg; }
                T operator&=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ &= arg; }
                T operator|=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ |= arg; }
                T operator^=(T arg) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_ ^= arg; }
                T operator++() noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return ++val_; }
                T operator++(int) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_++; }
                T operator--() noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return --val_; }
                T operator--(int) noexcept requires (std::is_integral_v<T> && !std::is_same_v<T, bool>) { return val_--; }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsVarargDef) {
        out.append(
            """

            #ifndef KXS_VARARG_DEFINED
            #define KXS_VARARG_DEFINED
            template <typename T>
            class KxsVararg : public std::vector<T> {
            public:
                KxsVararg() = default;
                KxsVararg(const KxsVararg&) = default;
                KxsVararg(KxsVararg&&) noexcept = default;
                KxsVararg& operator=(const KxsVararg&) = default;
                KxsVararg& operator=(KxsVararg&&) noexcept = default;
                KxsVararg(std::initializer_list<T> init) : std::vector<T>(init) {}
                KxsVararg(const std::vector<T>& items) : std::vector<T>(items) {}
                KxsVararg(std::vector<T>&& items) noexcept : std::vector<T>(std::move(items)) {}
                template <typename... Args>
                    requires (
                        sizeof...(Args) > 0 &&
                        !(sizeof...(Args) == 1 && ((
                            std::same_as<std::remove_cvref_t<Args>, KxsVararg<T>> ||
                            std::same_as<std::remove_cvref_t<Args>, std::vector<T>> ||
                            std::same_as<std::remove_cvref_t<Args>, std::initializer_list<T>>
                        ) || ...)) &&
                        (std::convertible_to<Args, T> && ...)
                    )
                KxsVararg(Args&&... args) {
                    this->reserve(sizeof...(Args));
                    (this->emplace_back(std::forward<Args>(args)), ...);
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
            inline auto operator<=>(Duration a, Duration b) noexcept {
                return a.compare_to(b) <=> 0;
            }
            inline bool operator==(Duration a, Duration b) noexcept {
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
            #ifndef KXS_ABSTRACT_LONG_TIME_SOURCE_DEFINED
            #define KXS_ABSTRACT_LONG_TIME_SOURCE_DEFINED
            class AbstractLongTimeSource : public TimeSource::WithComparableMarks {
            public:
                explicit AbstractLongTimeSource(DurationUnit unit) : unit_(unit) {}
                virtual ~AbstractLongTimeSource() = default;
                ComparableTimeMark* mark_now() override {
                    return new ValueTimeMark(
                        static_cast<ValueTimeMarkReading>(to_duration(read(), unit_).to_long(DurationUnit::NANOSECONDS)));
                }
            protected:
                virtual std::int64_t read() = 0;
            private:
                DurationUnit unit_;
            };
            #endif
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
                    if (!v.has_value() || v.type() == typeid(std::nullptr_t)) return false;
                    if constexpr (!std::is_void_v<TargetD> && !std::is_abstract_v<TargetD>) {
                        if (std::any_cast<const TargetD>(std::addressof(v)) != nullptr) return true;
                    }
                    if constexpr (!std::is_void_v<Pointee> && !std::is_same_v<Pointee, TargetD> && !std::is_abstract_v<Pointee>) {
                        if (std::any_cast<const Pointee>(std::addressof(v)) != nullptr) return true;
                    }
                    if constexpr (!std::is_void_v<Pointee> && !std::is_same_v<TargetD, std::shared_ptr<Pointee>>) {
                        if (const auto* sp = std::any_cast<const std::shared_ptr<Pointee>>(std::addressof(v))) {
                            if (*sp) return true;
                        }
                    }
                    if constexpr (!std::is_void_v<Pointee> && !std::is_same_v<TargetD, Pointee*>) {
                        if (const auto* ptr = std::any_cast<Pointee* const>(std::addressof(v))) {
                            if (*ptr != nullptr) return true;
                        }
                    }
                    return false;
                } else if constexpr (kxs_is_optional_v<SrcD>) {
                    return v.has_value() && kxs_is_instance<KxsTarget>(*v);
                } else if constexpr (kxs_is_shared_ptr_v<SrcD>) {
                    if (!v) return false;
                    using SrcEl = std::remove_cv_t<typename SrcD::element_type>;
                    if constexpr (std::is_same_v<Pointee, void>) return true;
                    else if constexpr (!std::is_void_v<SrcEl> && (std::is_base_of_v<Pointee, SrcEl> || std::is_same_v<Pointee, SrcEl>)) return true;
                    else if constexpr (!std::is_void_v<SrcEl> && std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<Pointee>) return dynamic_cast<const Pointee*>(v.get()) != nullptr;
                    else return false;
                } else if constexpr (std::is_pointer_v<SrcD>) {
                    if (!v) return false;
                    using SrcEl = std::remove_cv_t<std::remove_pointer_t<SrcD>>;
                    if constexpr (std::is_same_v<Pointee, void>) return true;
                    else if constexpr (!std::is_void_v<SrcEl> && (std::is_base_of_v<Pointee, SrcEl> || std::is_same_v<Pointee, SrcEl>)) return true;
                    else if constexpr (!std::is_void_v<SrcEl> && std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<Pointee>) return dynamic_cast<const Pointee*>(v) != nullptr;
                    else return false;
                } else {
                    if constexpr (std::is_same_v<Pointee, void>) return true;
                    else if constexpr (!std::is_void_v<SrcD> && (std::is_base_of_v<Pointee, SrcD> || std::is_same_v<Pointee, SrcD>)) return true;
                    else if constexpr (!std::is_void_v<SrcD> && std::is_polymorphic_v<SrcD> && std::is_polymorphic_v<Pointee>) return dynamic_cast<const Pointee*>(std::addressof(v)) != nullptr;
                    else return false;
                }
            }

            template <typename KxsTargetD>
            inline KxsTargetD kxs_cast_default_or_throw() {
                if constexpr (std::is_default_constructible_v<KxsTargetD>) {
                    return KxsTargetD{};
                } else {
                    throw std::runtime_error("ClassCastException");
                }
            }

            template <typename KxsTargetD>
            inline KxsTargetD kxs_safe_cast_null() {
                if constexpr (std::is_pointer_v<KxsTargetD> || kxs_is_shared_ptr_v<KxsTargetD> || std::is_same_v<KxsTargetD, std::nullptr_t>) {
                    return nullptr;
                } else if constexpr (kxs_is_optional_v<KxsTargetD> || std::is_same_v<KxsTargetD, std::any>) {
                    return KxsTargetD{};
                } else if constexpr (std::is_default_constructible_v<KxsTargetD>) {
                    return KxsTargetD{};
                } else {
                    throw std::runtime_error("ClassCastException");
                }
            }

            template <typename KxsTarget, typename KxsSrc>
            inline KxsTarget kxs_safe_as_cast(KxsSrc&& v) {
                using TargetD = std::remove_cvref_t<KxsTarget>;
                using SrcD = std::remove_cvref_t<KxsSrc>;
                if constexpr (std::is_abstract_v<TargetD>) {
                    static_assert(!std::is_abstract_v<TargetD>, "abstract target must use shared_ptr or pointer");
                } else if constexpr (std::is_same_v<SrcD, std::nullptr_t>) {
                    return kxs_safe_cast_null<TargetD>();
                } else if constexpr (std::is_same_v<SrcD, std::any>) {
                    if (!v.has_value() || v.type() == typeid(std::nullptr_t)) {
                        return kxs_safe_cast_null<TargetD>();
                    }
                    if constexpr (!std::is_void_v<TargetD>) {
                        if (const auto* p = std::any_cast<const TargetD>(std::addressof(v))) return *p;
                    }
                    if constexpr (kxs_is_optional_v<TargetD>) {
                        using Inner = typename TargetD::value_type;
                        if constexpr (!std::is_void_v<Inner> && !std::is_abstract_v<Inner>) {
                            if (const auto* p = std::any_cast<const Inner>(std::addressof(v))) return TargetD(*p);
                        }
                    } else if constexpr (kxs_is_shared_ptr_v<TargetD>) {
                        using DstEl = typename TargetD::element_type;
                        if (const auto* pp = std::any_cast<DstEl* const>(std::addressof(v))) {
                            return *pp ? std::shared_ptr<DstEl>(std::shared_ptr<void>{}, *pp) : TargetD{};
                        }
                    } else if constexpr (std::is_pointer_v<TargetD>) {
                        using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                        if constexpr (!std::is_void_v<DstEl>) {
                            if (const auto* sp = std::any_cast<const std::shared_ptr<DstEl>>(std::addressof(v))) {
                                return sp->get();
                            }
                        }
                    }
                    return kxs_safe_cast_null<TargetD>();
                } else if constexpr (kxs_is_optional_v<SrcD>) {
                    if (!v.has_value()) return kxs_safe_cast_null<TargetD>();
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
                            return kxs_safe_cast_null<TargetD>();
                        }
                    } else if constexpr (std::is_pointer_v<TargetD>) {
                        using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                        if constexpr (std::is_same_v<DstEl, void>) return static_cast<TargetD>(const_cast<std::remove_cv_t<SrcEl>*>(v.get()));
                        else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) return dynamic_cast<TargetD>(const_cast<std::remove_cv_t<SrcEl>*>(v.get()));
                        else return kxs_safe_cast_null<TargetD>();
                    } else {
                        return kxs_safe_cast_null<TargetD>();
                    }
                } else if constexpr (std::is_pointer_v<SrcD>) {
                    using SrcEl = std::remove_cv_t<std::remove_pointer_t<SrcD>>;
                    if (!v) return kxs_safe_cast_null<TargetD>();
                    if constexpr (std::is_pointer_v<TargetD>) {
                        using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                        if constexpr (std::is_same_v<DstEl, void>) return static_cast<TargetD>(const_cast<SrcEl*>(v));
                        else if constexpr (std::is_same_v<SrcEl, void>) return static_cast<TargetD>(const_cast<void*>(v));
                        else if constexpr (std::is_convertible_v<SrcEl*, DstEl*>) return static_cast<TargetD>(const_cast<SrcEl*>(v));
                        else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) return dynamic_cast<TargetD>(const_cast<SrcEl*>(v));
                        else return kxs_safe_cast_null<TargetD>();
                    } else if constexpr (kxs_is_shared_ptr_v<TargetD>) {
                        using DstEl = typename TargetD::element_type;
                        if constexpr (std::is_convertible_v<SrcEl*, DstEl*>) {
                            return std::shared_ptr<DstEl>(std::shared_ptr<void>{}, const_cast<DstEl*>(static_cast<const DstEl*>(v)));
                        } else if constexpr (std::is_polymorphic_v<SrcEl> && std::is_polymorphic_v<DstEl>) {
                            if (auto* d = dynamic_cast<DstEl*>(const_cast<SrcEl*>(v))) {
                                return std::shared_ptr<DstEl>(std::shared_ptr<void>{}, d);
                            }
                            return kxs_safe_cast_null<TargetD>();
                        } else {
                            return kxs_safe_cast_null<TargetD>();
                        }
                    } else if constexpr (std::is_same_v<SrcEl, void>) {
                        if constexpr (kxs_is_optional_v<TargetD>) {
                            return TargetD(*static_cast<typename TargetD::value_type*>(const_cast<void*>(v)));
                        } else {
                            return *static_cast<TargetD*>(const_cast<void*>(v));
                        }
                    } else {
                        return kxs_safe_cast_null<TargetD>();
                    }
                } else if constexpr (std::is_same_v<SrcD, TargetD> || std::is_convertible_v<SrcD, TargetD>) {
                    return static_cast<TargetD>(std::forward<KxsSrc>(v));
                } else if constexpr (std::is_pointer_v<TargetD> && std::is_polymorphic_v<SrcD>) {
                    using DstEl = std::remove_cv_t<std::remove_pointer_t<TargetD>>;
                    if constexpr (std::is_polymorphic_v<DstEl>) return dynamic_cast<TargetD>(const_cast<SrcD*>(std::addressof(v)));
                    else return kxs_safe_cast_null<TargetD>();
                } else {
                    return kxs_safe_cast_null<TargetD>();
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
                        return kxs_cast_default_or_throw<TargetD>();
                    }
                    if (auto* p = std::any_cast<const TargetD>(std::addressof(v))) return *p;
                    throw std::runtime_error("ClassCastException");
                } else if constexpr (std::is_convertible_v<SrcD, TargetD>) {
                    return static_cast<TargetD>(std::forward<KxsSrc>(v));
                } else if constexpr (kxs_is_shared_ptr_v<SrcD> && kxs_is_shared_ptr_v<TargetD>) {
                    if (!v) return kxs_cast_default_or_throw<TargetD>();
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
                    if (!v) return kxs_cast_default_or_throw<TargetD>();
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
                    if constexpr (
                        std::is_same_v<LDecay, std::exception_ptr> &&
                        !std::is_same_v<RRaw, std::exception_ptr>
                    ) {
                        return lhs ? std::forward<L>(lhs) : std::make_exception_ptr(std::forward<RFn>(rhs_fn)());
                    } else if constexpr (std::is_convertible_v<LDecay, RRaw>) {
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
    if (includes.needsAnyEqDef) {
        out.append(
            """
            #ifndef KXS_ANY_EQ_DEFINED
            #define KXS_ANY_EQ_DEFINED
            template <typename KxsT>
            inline bool kxs_is_null_any_or_ptr(const KxsT& v) noexcept {
                using D = std::remove_cvref_t<KxsT>;
                if constexpr (std::is_null_pointer_v<D>) {
                    return true;
                } else if constexpr (std::is_same_v<D, std::any>) {
                    return !v.has_value() || v.type() == typeid(std::nullptr_t);
                } else if constexpr (std::is_pointer_v<D> || kxs_is_shared_ptr_v<D>) {
                    return v == nullptr;
                } else if constexpr (kxs_is_optional_v<D>) {
                    return !v.has_value();
                } else {
                    return false;
                }
            }

            template <typename KxsL, typename KxsR>
            inline bool kxs_any_eq(const KxsL& lhs, const KxsR& rhs) {
                using L = std::remove_cvref_t<KxsL>;
                using R = std::remove_cvref_t<KxsR>;
                if constexpr (std::is_same_v<L, std::any> && std::is_same_v<R, std::any>) {
                    const bool ln = kxs_is_null_any_or_ptr(lhs);
                    const bool rn = kxs_is_null_any_or_ptr(rhs);
                    if (ln || rn) return ln && rn;
                    if (lhs.type() != rhs.type()) return false;
                    if (lhs.type() == typeid(bool)) {
                        return std::any_cast<bool>(lhs) == std::any_cast<bool>(rhs);
                    }
                    if (lhs.type() == typeid(std::int32_t)) {
                        return std::any_cast<std::int32_t>(lhs) == std::any_cast<std::int32_t>(rhs);
                    }
                    if (lhs.type() == typeid(std::int64_t)) {
                        return std::any_cast<std::int64_t>(lhs) == std::any_cast<std::int64_t>(rhs);
                    }
                    if (lhs.type() == typeid(double)) {
                        return std::any_cast<double>(lhs) == std::any_cast<double>(rhs);
                    }
                    if (lhs.type() == typeid(std::string)) {
                        const auto& ls = std::any_cast<const std::string&>(lhs);
                        const auto& rs = std::any_cast<const std::string&>(rhs);
                        return ls == rs;
                    }
                    if (lhs.type() == typeid(void*)) {
                        return std::any_cast<void*>(lhs) == std::any_cast<void*>(rhs);
                    }
                    if (lhs.type() == typeid(const void*)) {
                        return std::any_cast<const void*>(lhs) == std::any_cast<const void*>(rhs);
                    }
                    return std::addressof(lhs) == std::addressof(rhs);
                } else if constexpr (std::is_same_v<L, std::any>) {
                    if (kxs_is_null_any_or_ptr(lhs)) return kxs_is_null_any_or_ptr(rhs);
                    if (kxs_is_null_any_or_ptr(rhs)) return false;
                    if constexpr (!std::is_abstract_v<R> && !std::is_array_v<R> && !std::is_function_v<R>) {
                        if (const auto* lp = std::any_cast<const R>(std::addressof(lhs))) {
                            if constexpr (requires { *lp == rhs; }) return *lp == rhs;
                            else return false;
                        }
                    }
                    if constexpr (std::is_pointer_v<R>) {
                        using Pt = std::remove_cv_t<std::remove_pointer_t<R>>;
                        if constexpr (!std::is_void_v<Pt> && !std::is_function_v<Pt>) {
                            if (const auto* lsp = std::any_cast<const std::shared_ptr<Pt>>(std::addressof(lhs))) {
                                return lsp->get() == rhs;
                            }
                            if constexpr (std::is_polymorphic_v<Pt>) {
                                if (const auto* lvp = std::any_cast<void* const>(std::addressof(lhs))) {
                                    return *lvp == static_cast<const void*>(rhs);
                                }
                            }
                        }
                    } else if constexpr (kxs_is_shared_ptr_v<R>) {
                        using Pt = std::remove_cv_t<typename R::element_type>;
                        if constexpr (!std::is_void_v<Pt>) {
                            if (const auto* lptr = std::any_cast<Pt* const>(std::addressof(lhs))) {
                                return *lptr == rhs.get();
                            }
                        }
                    } else if constexpr (kxs_is_optional_v<R>) {
                        return kxs_any_eq(lhs, *rhs);
                    } else if constexpr (std::is_convertible_v<R, std::string>) {
                        if (const auto* ls = std::any_cast<const std::string>(std::addressof(lhs))) {
                            return *ls == std::string(rhs);
                        }
                    }
                    return false;
                } else if constexpr (std::is_same_v<R, std::any>) {
                    return kxs_any_eq(rhs, lhs);
                } else if constexpr (std::is_null_pointer_v<L> || std::is_null_pointer_v<R>) {
                    return kxs_is_null_any_or_ptr(lhs) && kxs_is_null_any_or_ptr(rhs);
                } else if constexpr (requires { lhs == rhs; }) {
                    return lhs == rhs;
                } else if constexpr (kxs_is_shared_ptr_v<L> && std::is_pointer_v<R>) {
                    return static_cast<const void*>(lhs.get()) == static_cast<const void*>(rhs);
                } else if constexpr (std::is_pointer_v<L> && kxs_is_shared_ptr_v<R>) {
                    return static_cast<const void*>(lhs) == static_cast<const void*>(rhs.get());
                } else {
                    return std::addressof(lhs) == std::addressof(rhs);
                }
            }

            template <typename KxsL, typename KxsR>
            inline bool kxs_any_ref_eq(const KxsL& lhs, const KxsR& rhs) {
                using L = std::remove_cvref_t<KxsL>;
                using R = std::remove_cvref_t<KxsR>;
                if constexpr (std::is_same_v<L, std::any> && std::is_same_v<R, std::any>) {
                    return kxs_any_eq(lhs, rhs);
                } else if constexpr (std::is_same_v<L, std::any>) {
                    if (kxs_is_null_any_or_ptr(lhs)) return kxs_is_null_any_or_ptr(rhs);
                    if (kxs_is_null_any_or_ptr(rhs)) return false;
                    if constexpr (!std::is_abstract_v<R> && !std::is_array_v<R> && !std::is_function_v<R>) {
                        if (const auto* lp = std::any_cast<const R>(std::addressof(lhs))) {
                            if constexpr (requires { *lp == rhs; }) return *lp == rhs;
                            else return lp == std::addressof(rhs);
                        }
                    }
                    if constexpr (std::is_pointer_v<R>) {
                        using Pt = std::remove_cv_t<std::remove_pointer_t<R>>;
                        if constexpr (!std::is_void_v<Pt> && !std::is_function_v<Pt>) {
                            if (const auto* lsp = std::any_cast<const std::shared_ptr<Pt>>(std::addressof(lhs))) {
                                return lsp->get() == rhs;
                            }
                        }
                    } else if constexpr (kxs_is_shared_ptr_v<R>) {
                        using Pt = std::remove_cv_t<typename R::element_type>;
                        if constexpr (!std::is_void_v<Pt>) {
                            if (const auto* lptr = std::any_cast<Pt* const>(std::addressof(lhs))) {
                                return *lptr == rhs.get();
                            }
                        }
                    }
                    return false;
                } else if constexpr (std::is_same_v<R, std::any>) {
                    return kxs_any_ref_eq(rhs, lhs);
                } else if constexpr (std::is_same_v<L, R> && (std::is_arithmetic_v<L> || std::is_enum_v<L>)) {
                    return lhs == rhs;
                } else {
                    auto kxs_ptr = [](const auto& v) -> const void* {
                        using V = std::remove_cvref_t<decltype(v)>;
                        if constexpr (std::is_null_pointer_v<V>) return nullptr;
                        else if constexpr (std::is_pointer_v<V>) return static_cast<const void*>(v);
                        else if constexpr (requires { v.get(); }) return static_cast<const void*>(v.get());
                        else if constexpr (requires { typename V::value_type; v.has_value(); *v; }) {
                            return v.has_value() ? static_cast<const void*>(std::addressof(*v)) : nullptr;
                        } else return static_cast<const void*>(std::addressof(v));
                    };
                    return kxs_ptr(lhs) == kxs_ptr(rhs);
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
            template <typename KxsVal>
            inline bool kxs_is_null(const KxsVal& v) {
                using KxsDecayed = std::remove_cvref_t<KxsVal>;
                if constexpr (std::is_same_v<KxsDecayed, std::any>) {
                    return !v.has_value() || v.type() == typeid(std::nullptr_t);
                } else if constexpr (std::is_null_pointer_v<KxsDecayed>) {
                    return true;
                } else if constexpr (requires { v.has_value(); }) {
                    return !v.has_value();
                } else if constexpr (std::is_pointer_v<KxsDecayed> || requires { v.get(); }) {
                    return !static_cast<bool>(v);
                } else if constexpr (std::is_same_v<KxsDecayed, std::exception_ptr>) {
                    return !static_cast<bool>(v);
                } else if constexpr (requires { static_cast<bool>(v); } && !std::is_arithmetic_v<KxsDecayed>) {
                    return !static_cast<bool>(v);
                } else {
                    return false;
                }
            }
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
                using KxsExcD = std::remove_cvref_t<KxsExc>;
                if constexpr (std::is_same_v<KxsExcD, std::exception_ptr>) {
                    return std::forward<KxsExc>(e);
                } else if constexpr (std::is_null_pointer_v<KxsExcD>) {
                    return std::exception_ptr{};
                } else if constexpr (std::is_same_v<KxsExcD, std::any>) {
                    if (!e.has_value() || e.type() == typeid(std::nullptr_t)) {
                        return std::exception_ptr{};
                    }
                    if (const auto* ep = std::any_cast<std::exception_ptr>(std::addressof(e))) {
                        return *ep;
                    }
                    return std::make_exception_ptr(e);
                } else if constexpr (requires { typename KxsExcD::value_type; e.has_value(); *e; }) {
                    return e.has_value() ? kxs_to_exception_ptr(*std::forward<KxsExc>(e)) : std::exception_ptr{};
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
            template <typename KxsRes, typename KxsFn>
            inline decltype(auto) kxs_result_get_or_else(KxsRes&& r, KxsFn&& on_failure) {
                if constexpr (std::is_void_v<decltype(r.get_or_throw())>) {
                    if (r.is_success()) {
                        if constexpr (std::is_void_v<decltype(on_failure(r.exception_or_null()))>) {
                            return;
                        } else {
                            return kotlinx::coroutines::Unit{};
                        }
                    }
                    return on_failure(r.exception_or_null());
                } else {
                    using ValT = std::decay_t<decltype(r.get_or_throw())>;
                    using AltT = std::decay_t<decltype(on_failure(r.exception_or_null()))>;
                    if constexpr (std::is_void_v<AltT>) {
                        if (!r.is_success()) {
                            on_failure(r.exception_or_null());
                        }
                        return r.get_or_throw();
                    } else if constexpr (std::is_same_v<ValT, AltT>) {
                        if (r.is_success()) return r.get_or_throw();
                        return on_failure(r.exception_or_null());
                    } else if constexpr (requires { typename std::common_type_t<ValT, AltT>; }) {
                        using RetT = std::common_type_t<ValT, AltT>;
                        if (r.is_success()) return static_cast<RetT>(r.get_or_throw());
                        return static_cast<RetT>(on_failure(r.exception_or_null()));
                    } else {
                        if (r.is_success()) return std::any(r.get_or_throw());
                        return std::any(on_failure(r.exception_or_null()));
                    }
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsSuspendBoxHelpersDef) {
        out.append(
            """

            #ifndef KXS_SUSPEND_BOX_HELPERS_DEFINED
            #define KXS_SUSPEND_BOX_HELPERS_DEFINED
            template <typename KxsT>
            struct kxs_box_is_optional : std::false_type {};
            template <typename KxsT>
            struct kxs_box_is_optional<std::optional<KxsT>> : std::true_type {};

            template <typename KxsBoxT, typename KxsVal>
            inline void* kxs_box_suspend_result(KxsVal&& v) {
                using BoxD = std::remove_cvref_t<KxsBoxT>;
                using ValD = std::remove_cvref_t<KxsVal>;
                if constexpr (std::is_same_v<ValD, void*>) {
                    if (kotlin::coroutines::intrinsics::is_coroutine_suspended(v)) {
                        return v;
                    }
                    if constexpr (std::is_void_v<BoxD>) {
                        return nullptr;
                    } else if constexpr (std::is_default_constructible_v<BoxD>) {
                        return v ? v : static_cast<void*>(new BoxD{});
                    } else {
                        return v;
                    }
                } else if constexpr (std::is_same_v<ValD, std::any>) {
                    if (const auto* kxs_raw_p = std::any_cast<void*>(std::addressof(v))) {
                        if (kotlin::coroutines::intrinsics::is_coroutine_suspended(*kxs_raw_p)) {
                            return *kxs_raw_p;
                        }
                    }
                    if constexpr (std::is_void_v<BoxD>) {
                        return nullptr;
                    } else if constexpr (std::is_same_v<BoxD, std::any>) {
                        return static_cast<void*>(new std::any(std::forward<KxsVal>(v)));
                    } else if constexpr (std::is_same_v<BoxD, kotlin::Unit>) {
                        return static_cast<void*>(new kotlin::Unit{});
                    } else {
                        if (const auto* kxs_val_p = std::any_cast<BoxD>(std::addressof(v))) {
                            return static_cast<void*>(new BoxD(*kxs_val_p));
                        }
                        if constexpr (kxs_box_is_optional<BoxD>::value) {
                            using InnerT = typename BoxD::value_type;
                            if (const auto* kxs_inner_p = std::any_cast<InnerT>(std::addressof(v))) {
                                return static_cast<void*>(new BoxD(*kxs_inner_p));
                            }
                            if (!v.has_value() || v.type() == typeid(std::nullptr_t)) {
                                return static_cast<void*>(new BoxD{});
                            }
                        }
                        if constexpr (std::is_default_constructible_v<BoxD>) {
                            if (!v.has_value()) {
                                return static_cast<void*>(new BoxD{});
                            }
                        }
                        throw std::runtime_error("ClassCastException");
                    }
                } else if constexpr (std::is_void_v<BoxD>) {
                    return nullptr;
                } else {
                    return static_cast<void*>(new BoxD(std::forward<KxsVal>(v)));
                }
            }

            template <typename KxsValT>
            inline void* kxs_unit_suspend_result(KxsValT&& v) {
                using ValD = std::remove_cvref_t<KxsValT>;
                if constexpr (std::is_same_v<ValD, void*>) {
                    return kotlin::coroutines::intrinsics::is_coroutine_suspended(v) ? v : nullptr;
                } else if constexpr (std::is_same_v<ValD, std::any>) {
                    if (const auto* p = std::any_cast<void*>(std::addressof(v))) {
                        if (kotlin::coroutines::intrinsics::is_coroutine_suspended(*p)) {
                            return *p;
                        }
                    }
                    return nullptr;
                } else {
                    return nullptr;
                }
            }

            template <typename KxsContElem = void*, template <typename> class KxsContTmpl>
            inline std::shared_ptr<KxsContTmpl<KxsContElem>> kxs_wrap_raw_continuation(KxsContTmpl<KxsContElem>* raw) {
                if (!raw) return nullptr;
                return std::shared_ptr<KxsContTmpl<KxsContElem>>(raw, [](KxsContTmpl<KxsContElem>*) {});
            }

            template <typename KxsT = kotlin::Unit, typename KxsBlock>
            [[clang::annotate("suspend"), clang::annotate("kxs_implicit_continuation"), clang::annotate("kxs_boxed_result")]]
            std::conditional_t<std::is_void_v<KxsT>, kotlin::Unit, KxsT>
            kxs_suspend_coroutine_unintercepted_or_return(KxsBlock&& block);

            template <typename KxsT = kotlin::Unit, typename KxsBlock>
            [[clang::annotate("suspend")]]
            inline void* kxs_suspend_coroutine_unintercepted_or_return(
                KxsBlock&& block,
                std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion
            ) {
                using SafeT = std::conditional_t<std::is_void_v<KxsT>, kotlin::Unit, KxsT>;
                std::shared_ptr<kotlin::coroutines::Continuation<KxsT>> kxs_typed_ucont;
                if constexpr (std::is_same_v<KxsT, void*>) {
                    kxs_typed_ucont = _kxs_completion;
                } else {
                    kxs_typed_ucont = std::make_shared<kotlin::coroutines::FunctionalContinuation<KxsT>>(
                        _kxs_completion ? _kxs_completion->get_context() : nullptr,
                        [_kxs_completion](kotlin::coroutines::Result<KxsT> r) {
                            if (!_kxs_completion) return;
                            if (r.is_failure()) {
                                _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::failure(r.exception_or_null()));
                            } else if constexpr (std::is_same_v<KxsT, kotlin::Unit> || std::is_void_v<KxsT>) {
                                _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::success(new kotlin::Unit{}));
                            } else {
                                _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::success(new SafeT(r.get_or_throw())));
                            }
                        });
                }
                if constexpr (std::is_void_v<decltype(block(kxs_typed_ucont))>) {
                    block(kxs_typed_ucont);
                    if constexpr (std::is_same_v<SafeT, kotlin::Unit>) {
                        return static_cast<void*>(new kotlin::Unit{});
                    } else if constexpr (std::is_default_constructible_v<SafeT>) {
                        return static_cast<void*>(new SafeT{});
                    } else {
                        return nullptr;
                    }
                } else {
                    return kxs_box_suspend_result<SafeT>(block(kxs_typed_ucont));
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsCoroutineIntrinsicsBridgeDef) {
        out.append(
            """

            #ifndef KXS_COROUTINE_INTRINSICS_BRIDGE_DEFINED
            #define KXS_COROUTINE_INTRINSICS_BRIDGE_DEFINED
            template <typename KxsContT>
            kotlin::coroutines::Continuation<KxsContT>* kxs_cont_base_probe(
                kotlin::coroutines::Continuation<KxsContT>*
            );

            template <typename KxsPtr>
            concept kxs_derived_cont_ptr =
                requires(const KxsPtr& p) { kxs_cont_base_probe(p.operator->()); };

            template <typename KxsContT>
            inline std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> kxs_intercepted(
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> cont
            ) {
                auto erased = kotlin::coroutines::intrinsics::intercepted(
                    kotlin::coroutines::to_void_continuation(std::move(cont))
                );
                return kotlin::coroutines::internal::result_box_completion<KxsContT>(std::move(erased));
            }

            template <typename KxsContT>
            inline std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> kxs_intercepted(
                kotlin::coroutines::Continuation<KxsContT>* raw
            ) {
                return kxs_intercepted(kxs_wrap_raw_continuation(raw));
            }

            template <typename KxsFn, typename KxsContT>
            inline std::shared_ptr<kotlin::coroutines::Continuation<kotlin::Unit>> kxs_create_coroutine_unintercepted(
                KxsFn&& block,
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> completion
            ) {
                using FnD = std::decay_t<KxsFn>;
                kotlin::coroutines::intrinsics::ErasedSuspendFunction erased;
                if constexpr (std::is_invocable_v<FnD&, std::shared_ptr<kotlin::coroutines::Continuation<void*>>>) {
                    erased = [b = std::forward<KxsFn>(block)](std::shared_ptr<kotlin::coroutines::Continuation<void*>> c) mutable -> void* {
                        return b(std::move(c));
                    };
                } else if constexpr (std::is_invocable_v<FnD&, kotlin::coroutines::Continuation<KxsContT>*>) {
                    erased = kotlin::coroutines::intrinsics::erase_suspend_function<KxsContT>(
                        std::function<void*(kotlin::coroutines::Continuation<KxsContT>*)>(std::forward<KxsFn>(block))
                    );
                } else {
                    erased = [b = std::forward<KxsFn>(block)](std::shared_ptr<kotlin::coroutines::Continuation<void*>>) mutable -> void* {
                        using SafeT = std::conditional_t<std::is_void_v<KxsContT>, kotlin::Unit, KxsContT>;
                        if constexpr (std::is_void_v<decltype(b())>) {
                            b();
                            return kxs_box_suspend_result<SafeT>(kotlin::Unit{});
                        } else {
                            return kxs_box_suspend_result<SafeT>(b());
                        }
                    };
                }
                auto raw_cont = kotlin::coroutines::intrinsics::create_coroutine_unintercepted(
                    std::move(erased),
                    kotlin::coroutines::to_void_continuation(std::move(completion))
                );
                return kotlin::coroutines::internal::result_box_completion<kotlin::Unit>(std::move(raw_cont));
            }

            template <typename KxsFn, typename KxsRecv, typename KxsContT>
            inline std::shared_ptr<kotlin::coroutines::Continuation<kotlin::Unit>> kxs_create_coroutine_unintercepted(
                KxsFn&& block,
                KxsRecv&& receiver,
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> completion
            ) {
                using FnD = std::decay_t<KxsFn>;
                using RecvD = std::decay_t<KxsRecv>;
                if constexpr (std::is_invocable_v<FnD&, RecvD, std::shared_ptr<kotlin::coroutines::Continuation<void*>>>) {
                    return kxs_create_coroutine_unintercepted(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)](
                            std::shared_ptr<kotlin::coroutines::Continuation<void*>> c
                        ) mutable -> void* { return b(r, std::move(c)); },
                        std::move(completion)
                    );
                } else if constexpr (std::is_invocable_v<FnD&, RecvD, kotlin::coroutines::Continuation<KxsContT>*>) {
                    return kxs_create_coroutine_unintercepted(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)](
                            kotlin::coroutines::Continuation<KxsContT>* c
                        ) mutable -> void* { return b(r, c); },
                        std::move(completion)
                    );
                } else {
                    return kxs_create_coroutine_unintercepted(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)]() mutable -> decltype(auto) {
                            return b(r);
                        },
                        std::move(completion)
                    );
                }
            }

            template <typename KxsFn, typename KxsContT>
            inline void* kxs_start_coroutine_unintercepted_or_return(
                KxsFn&& block,
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> completion
            ) {
                using FnD = std::decay_t<KxsFn>;
                kotlin::coroutines::intrinsics::ErasedSuspendFunction erased;
                if constexpr (std::is_invocable_v<FnD&, std::shared_ptr<kotlin::coroutines::Continuation<void*>>>) {
                    erased = [b = std::forward<KxsFn>(block)](std::shared_ptr<kotlin::coroutines::Continuation<void*>> c) mutable -> void* {
                        return b(std::move(c));
                    };
                } else if constexpr (std::is_invocable_v<FnD&, kotlin::coroutines::Continuation<KxsContT>*>) {
                    erased = kotlin::coroutines::intrinsics::erase_suspend_function<KxsContT>(
                        std::function<void*(kotlin::coroutines::Continuation<KxsContT>*)>(std::forward<KxsFn>(block))
                    );
                } else {
                    erased = [b = std::forward<KxsFn>(block)](std::shared_ptr<kotlin::coroutines::Continuation<void*>>) mutable -> void* {
                        using SafeT = std::conditional_t<std::is_void_v<KxsContT>, kotlin::Unit, KxsContT>;
                        if constexpr (std::is_void_v<decltype(b())>) {
                            b();
                            return kxs_box_suspend_result<SafeT>(kotlin::Unit{});
                        } else {
                            return kxs_box_suspend_result<SafeT>(b());
                        }
                    };
                }
                return kotlin::coroutines::intrinsics::start_coroutine_unintercepted_or_return(
                    std::move(erased),
                    kotlin::coroutines::to_void_continuation(std::move(completion))
                );
            }

            template <typename KxsFn, typename KxsRecv, typename KxsContT>
            inline void* kxs_start_coroutine_unintercepted_or_return(
                KxsFn&& block,
                KxsRecv&& receiver,
                std::shared_ptr<kotlin::coroutines::Continuation<KxsContT>> completion
            ) {
                using FnD = std::decay_t<KxsFn>;
                using RecvD = std::decay_t<KxsRecv>;
                if constexpr (std::is_invocable_v<FnD&, RecvD, std::shared_ptr<kotlin::coroutines::Continuation<void*>>>) {
                    return kxs_start_coroutine_unintercepted_or_return(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)](
                            std::shared_ptr<kotlin::coroutines::Continuation<void*>> c
                        ) mutable -> void* { return b(r, std::move(c)); },
                        std::move(completion)
                    );
                } else if constexpr (std::is_invocable_v<FnD&, RecvD, kotlin::coroutines::Continuation<KxsContT>*>) {
                    return kxs_start_coroutine_unintercepted_or_return(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)](
                            kotlin::coroutines::Continuation<KxsContT>* c
                        ) mutable -> void* { return b(r, c); },
                        std::move(completion)
                    );
                } else {
                    return kxs_start_coroutine_unintercepted_or_return(
                        [b = std::forward<KxsFn>(block), r = std::forward<KxsRecv>(receiver)]() mutable -> decltype(auto) {
                            return b(r);
                        },
                        std::move(completion)
                    );
                }
            }

            template <typename KxsFn, typename KxsContObj>
                requires requires(KxsContObj* p) { kxs_cont_base_probe(p); }
            inline void kxs_start_coroutine(
                KxsFn&& block,
                std::shared_ptr<KxsContObj> completion
            ) {
                using BaseCont = std::remove_pointer_t<decltype(kxs_cont_base_probe(static_cast<KxsContObj*>(nullptr)))>;
                std::shared_ptr<BaseCont> base_comp(std::move(completion));
                kxs_intercepted(
                    kxs_create_coroutine_unintercepted(std::forward<KxsFn>(block), std::move(base_comp))
                )->resume_with(kotlin::coroutines::Result<kotlin::Unit>::success(kotlin::Unit{}));
            }

            template <typename KxsFn, typename KxsRecv, typename KxsContObj>
                requires requires(KxsContObj* p) { kxs_cont_base_probe(p); }
            inline void kxs_start_coroutine(
                KxsFn&& block,
                KxsRecv&& receiver,
                std::shared_ptr<KxsContObj> completion
            ) {
                using BaseCont = std::remove_pointer_t<decltype(kxs_cont_base_probe(static_cast<KxsContObj*>(nullptr)))>;
                std::shared_ptr<BaseCont> base_comp(std::move(completion));
                kxs_intercepted(
                    kxs_create_coroutine_unintercepted(
                        std::forward<KxsFn>(block),
                        std::forward<KxsRecv>(receiver),
                        std::move(base_comp)
                    )
                )->resume_with(kotlin::coroutines::Result<kotlin::Unit>::success(kotlin::Unit{}));
            }

            template <typename KxsFn, typename KxsRecv, typename KxsContObj>
                requires (!std::is_same_v<std::remove_cv_t<KxsContObj>, kotlin::coroutines::Continuation<void*>> &&
                    requires(KxsContObj* p) { kxs_cont_base_probe(p); })
            inline std::shared_ptr<kotlin::coroutines::Continuation<kotlin::Unit>> kxs_create_coroutine_unintercepted(
                KxsFn&& block,
                KxsRecv&& receiver,
                std::shared_ptr<KxsContObj> completion
            ) {
                using BaseCont = std::remove_pointer_t<decltype(kxs_cont_base_probe(static_cast<KxsContObj*>(nullptr)))>;
                using ValT = decltype([](kotlin::coroutines::Continuation<void*>*) { return nullptr; }(static_cast<BaseCont*>(nullptr)));
                (void)sizeof(ValT);
                std::shared_ptr<BaseCont> base_comp(std::move(completion));
                return kxs_create_coroutine_unintercepted(
                    std::forward<KxsFn>(block),
                    std::forward<KxsRecv>(receiver),
                    std::move(base_comp)
                );
            }

            template <typename KxsCont, typename KxsExc>
            inline void kxs_resume_with_exception(KxsCont&& cont, KxsExc&& exc) {
                auto kxs_e = kxs_to_exception_ptr(std::forward<KxsExc>(exc));
                if constexpr (requires { cont->resume_with_exception(kxs_e); }) {
                    cont->resume_with_exception(std::move(kxs_e));
                } else if constexpr (requires { cont.resume_with_exception(kxs_e); }) {
                    cont.resume_with_exception(std::move(kxs_e));
                } else if constexpr (requires { *cont; }) {
                    kotlin::coroutines::resume_with_exception(*cont, std::move(kxs_e));
                } else {
                    kotlin::coroutines::resume_with_exception(cont, std::move(kxs_e));
                }
            }

            template <typename KxsCont, typename KxsVal>
            inline void kxs_resume_cont(KxsCont&& cont, KxsVal&& val) {
                if constexpr (requires { cont->resume(std::forward<KxsVal>(val)); }) {
                    cont->resume(std::forward<KxsVal>(val));
                } else if constexpr (requires { cont.resume(std::forward<KxsVal>(val)); }) {
                    cont.resume(std::forward<KxsVal>(val));
                } else if constexpr (requires { *cont; }) {
                    kotlin::coroutines::resume(*cont, std::forward<KxsVal>(val));
                } else {
                    kotlin::coroutines::resume(cont, std::forward<KxsVal>(val));
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsIterableHelpersDef) {
        out.append(
            """

            #ifndef KXS_ITERABLE_HELPERS_DEFINED
            #define KXS_ITERABLE_HELPERS_DEFINED
            template <typename KxsPtr>
            struct KxsPtrRange {
                KxsPtr ptr;
                auto begin() const { return (*ptr).begin(); }
                auto end() const { return (*ptr).end(); }
            };
            template <typename T>
                requires requires(T& r) { std::begin(r); std::end(r); }
            inline T& kxs_iterable_ref(T& x) noexcept {
                return x;
            }
            template <typename T>
            inline auto kxs_iterable_ref(T&& x) {
                if constexpr (requires { std::begin(x); std::end(x); }) {
                    return std::decay_t<T>(std::move(x));
                } else if constexpr (requires { (*x).begin(); (*x).end(); }) {
                    return KxsPtrRange<std::decay_t<T>>{std::forward<T>(x)};
                } else if constexpr (requires { x->next_node(); x->for_each([](decltype(x->next_node())) {}); }) {
                    using ElemT = std::decay_t<decltype(x->next_node())>;
                    std::vector<ElemT> v;
                    if (x) {
                        x->for_each([&](ElemT n) { v.push_back(std::move(n)); });
                    }
                    return v;
                } else if constexpr (requires { x.next_node(); x.for_each([](decltype(x.next_node())) {}); }) {
                    using ElemT = std::decay_t<decltype(x.next_node())>;
                    std::vector<ElemT> v;
                    x.for_each([&](ElemT n) { v.push_back(std::move(n)); });
                    return v;
                } else if constexpr (requires { x->iterator(); }) {
                    auto it = x->iterator();
                    if constexpr (requires { it->next(); }) {
                        using ElemT = std::decay_t<decltype(it->next())>;
                        std::vector<ElemT> v;
                        while (it->has_next()) v.push_back(it->next());
                        return v;
                    } else {
                        using ElemT = std::decay_t<decltype(it.next())>;
                        std::vector<ElemT> v;
                        while (it.has_next()) v.push_back(it.next());
                        return v;
                    }
                } else if constexpr (requires { x.iterator(); }) {
                    auto it = x.iterator();
                    if constexpr (requires { it->next(); }) {
                        using ElemT = std::decay_t<decltype(it->next())>;
                        std::vector<ElemT> v;
                        while (it->has_next()) v.push_back(it->next());
                        return v;
                    } else {
                        using ElemT = std::decay_t<decltype(it.next())>;
                        std::vector<ElemT> v;
                        while (it.has_next()) v.push_back(it.next());
                        return v;
                    }
                } else {
                    return std::forward<T>(x);
                }
            }
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsStringBuilderDef) {
        out.append(
            """

            #ifndef KXS_STRING_BUILDER_DEFINED
            #define KXS_STRING_BUILDER_DEFINED
            struct KxsStringBuilder {
                std::string buf_{};

                KxsStringBuilder() = default;
                explicit KxsStringBuilder(std::string s) : buf_(std::move(s)) {}
                explicit KxsStringBuilder(std::int32_t capacity) {
                    if (capacity > 0) buf_.reserve(static_cast<std::size_t>(capacity));
                }

                KxsStringBuilder& append(const std::string& s) {
                    buf_.append(s);
                    return *this;
                }
                KxsStringBuilder& append(const char* s) {
                    if (s) buf_.append(s);
                    return *this;
                }
                KxsStringBuilder& append(char c) {
                    buf_.push_back(c);
                    return *this;
                }
                KxsStringBuilder& append(char16_t c) {
                    buf_.push_back(static_cast<char>(c));
                    return *this;
                }
                KxsStringBuilder& append(bool b) {
                    buf_.append(b ? "true" : "false");
                    return *this;
                }
                template <typename T>
                    requires (std::is_arithmetic_v<std::remove_cvref_t<T>> &&
                              !std::is_same_v<std::remove_cvref_t<T>, bool> &&
                              !std::is_same_v<std::remove_cvref_t<T>, char> &&
                              !std::is_same_v<std::remove_cvref_t<T>, char16_t>)
                KxsStringBuilder& append(T v) {
                    buf_.append(std::to_string(v));
                    return *this;
                }
                template <typename T>
                    requires (!std::is_arithmetic_v<std::remove_cvref_t<T>> &&
                              !std::is_convertible_v<T, std::string> &&
                              !std::is_convertible_v<T, const char*>)
                KxsStringBuilder& append(const T& v) {
                    if constexpr (requires { v->to_string(); }) {
                        if (v) buf_.append(v->to_string());
                        else buf_.append("null");
                    } else if constexpr (requires { v.to_string(); }) {
                        buf_.append(v.to_string());
                    }
                    return *this;
                }
                KxsStringBuilder& append_line() {
                    buf_.push_back('\n');
                    return *this;
                }
                template <typename T>
                KxsStringBuilder& append_line(T&& v) {
                    append(std::forward<T>(v));
                    buf_.push_back('\n');
                    return *this;
                }
                std::int32_t length() const noexcept { return static_cast<std::int32_t>(buf_.size()); }
                std::int32_t size() const noexcept { return static_cast<std::int32_t>(buf_.size()); }
                bool empty() const noexcept { return buf_.empty(); }
                char last() const { return buf_.empty() ? '\0' : buf_.back(); }
                KxsStringBuilder& delete_at(std::int32_t index) {
                    if (index >= 0 && static_cast<std::size_t>(index) < buf_.size()) {
                        buf_.erase(static_cast<std::size_t>(index), 1);
                    }
                    return *this;
                }
                void clear() noexcept { buf_.clear(); }
                std::string to_string() const { return buf_; }
            };
            #endif
            """.trimIndent(),
        )
        out.append('\n')
    }
    if (includes.needsEmptyContainersDef) {
        out.append(
            """

            #ifndef KXS_EMPTY_CONTAINERS_DEFINED
            #define KXS_EMPTY_CONTAINERS_DEFINED
            struct KxsEmptyList {
                const int* begin() const noexcept { return nullptr; }
                const int* end() const noexcept { return nullptr; }
                bool empty() const noexcept { return true; }
                std::size_t size() const noexcept { return 0; }
                template <typename T>
                operator std::vector<T>() const { return {}; }
            };
            struct KxsEmptySet {
                const int* begin() const noexcept { return nullptr; }
                const int* end() const noexcept { return nullptr; }
                bool empty() const noexcept { return true; }
                std::size_t size() const noexcept { return 0; }
                template <typename T>
                operator std::unordered_set<T>() const { return {}; }
            };
            struct KxsEmptyMap {
                const std::pair<const int, int>* begin() const noexcept { return nullptr; }
                const std::pair<const int, int>* end() const noexcept { return nullptr; }
                bool empty() const noexcept { return true; }
                std::size_t size() const noexcept { return 0; }
                template <typename K, typename V>
                operator std::unordered_map<K, V>() const { return {}; }
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

    private fun hasCustomGetterBody(sym: KtPropertySymbol): Boolean {
        val gIdx = sym.getterFrameIndex ?: return false
        val gf = stream.frames[gIdx]
        return FrameAstHelpers.firstChildFrame(stream, gf, KtNodeTypes.BLOCK_ID) != null ||
            FrameAstHelpers.extractInitializerAfterEquals(stream, gf) != null
    }

    private fun hasCustomSetterBody(sym: KtPropertySymbol): Boolean {
        val sIdx = sym.setterFrameIndex ?: return false
        val sf = stream.frames[sIdx]
        return FrameAstHelpers.firstChildFrame(stream, sf, KtNodeTypes.BLOCK_ID) != null ||
            FrameAstHelpers.extractInitializerAfterEquals(stream, sf) != null
    }

    private fun hasCustomAccessorBody(sym: KtPropertySymbol): Boolean =
        hasCustomGetterBody(sym) || hasCustomSetterBody(sym)

    private fun containsLambdaDescendant(frame: PacketFrame): Boolean {
        if (frame.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID) return true
        return FrameAstHelpers.childFrames(stream, frame).any { containsLambdaDescendant(it) }
    }

    private fun isHoistedTopLevelConstOrSymbolProp(declFrame: PacketFrame): Boolean {
        if (declFrame.nodeTypeId != KtNodeTypes.PROPERTY_ID) return false
        val sym = symbols.propertiesByFrame[declFrame.frameIndex] ?: return false
        if (sym.receiverTypeFrameIndex != null) return false
        if (hasCustomAccessorBody(sym)) return false
        if (FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID)) return false
        if (sym.isConst) return true
        val initIdx = sym.initializerFrameIndex ?: return false
        val initFrame = stream.frames[initIdx]
        if (initFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val callee = FrameAstHelpers.childFrames(stream, initFrame).firstOrNull()
            val ident = callee?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
            if (ident == "Symbol") return true
        }
        if (sym.isVar) return false
        var unwrappedInit = initFrame
        while (unwrappedInit.nodeTypeId == KtNodeTypes.PARENTHESIZED_ID) {
            unwrappedInit = FrameAstHelpers.childFrames(stream, unwrappedInit).firstOrNull() ?: break
        }
        val isLambda = unwrappedInit.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
        val isHoistableConfigExpr =
            unwrappedInit.nodeTypeId in
                setOf(
                    KtNodeTypes.CALL_EXPRESSION_ID,
                    KtNodeTypes.BINARY_EXPRESSION_ID,
                    KtNodeTypes.PREFIX_EXPRESSION_ID,
                    KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
                    KtNodeTypes.INTEGER_CONSTANT_ID,
                    KtNodeTypes.FLOAT_CONSTANT_ID,
                    KtNodeTypes.BOOLEAN_CONSTANT_ID,
                    KtNodeTypes.CHARACTER_CONSTANT_ID,
                    KtNodeTypes.STRING_TEMPLATE_ID,
                ) &&
                !containsLambdaDescendant(unwrappedInit)
        if (!isLambda && !isHoistableConfigExpr) return false
        val mentionsDependency =
            symbols.classesByFrame.values.any { frameMentionsIdentifier(declFrame, it.kotlinName) } ||
                symbols.functionsByFrame.values.any {
                    it.enclosingClassFrameIndex == null && frameMentionsIdentifier(unwrappedInit, it.kotlinName)
                } ||
                symbols.propertiesByFrame.values.any {
                    it.enclosingClassFrameIndex == null &&
                        it.frameIndex != sym.frameIndex &&
                        frameMentionsIdentifier(unwrappedInit, it.kotlinName) &&
                        (
                            it.frameIndex >= sym.frameIndex ||
                                !isHoistedTopLevelConstOrSymbolProp(stream.frames[it.frameIndex])
                        )
                }
        return !mentionsDependency
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
        fun collectFromClass(
            curCls: KtClassSymbol,
            visited: MutableSet<Int>,
        ) {
            if (!visited.add(curCls.frameIndex)) return
            for (stIdx in curCls.superTypeFrameIndices) {
                val superName = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[stIdx]) ?: continue
                val targetIdx = topLevelClassByName[superName]
                if (targetIdx != null && targetIdx != declFrame.frameIndex) {
                    deps.add(targetIdx)
                }
            }
            for (cp in curCls.primaryConstructorParams) {
                val tfIdx = cp.typeFrameIndex ?: continue
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[tfIdx]) ?: continue
                val targetIdx = topLevelClassByName[simple]
                if (targetIdx != null && targetIdx != declFrame.frameIndex && simple !in symbols.sharedHierarchyTypeNames) {
                    deps.add(targetIdx)
                }
            }
            for (pIdx in curCls.memberPropertyFrameIndices) {
                val pSym = symbols.propertiesByFrame[pIdx] ?: continue
                val retIdx = pSym.returnTypeFrameIndex ?: continue
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[retIdx]) ?: continue
                val targetIdx = topLevelClassByName[simple]
                if (targetIdx != null && targetIdx != declFrame.frameIndex && simple !in symbols.sharedHierarchyTypeNames) {
                    deps.add(targetIdx)
                }
            }
            for (nestedIdx in curCls.memberClassFrameIndices) {
                val nestedCls = symbols.classesByFrame[nestedIdx] ?: continue
                collectFromClass(nestedCls, visited)
            }
        }
        collectFromClass(cls, HashSet())
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
                do {
                    var progressed = false
                    for (declFrame in symbols.topLevelDeclarationFrames) {
                        if (declFrame.frameIndex in hoistedFrames) continue
                        if (isHoistedForwardReferencedClass(declFrame)) {
                            val cls = symbols.classesByFrame[declFrame.frameIndex]
                            if (cls != null && isPostIncludeCycleClass(cls)) continue
                            val deps = if (cls != null) collectTopLevelClassDependencies(declFrame, cls, topLevelClassByName) else emptyList()
                            if (deps.all { it in hoistedFrames }) {
                                hoistedFrames.add(declFrame.frameIndex)
                                val clsRendered = renderClassOrObject(declFrame, qualifierPrefix = "", indent = "")
                                if (clsRendered.isNotBlank()) {
                                    if (isNotEmpty() && !endsWith("\n\n")) append('\n')
                                    append(clsRendered.trimEnd()).append('\n')
                                }
                                progressed = true
                            }
                        }
                    }
                } while (progressed)
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

            val topLevelSeenNormalizedSigs =
                collectScopeNormalizedSigs(
                    symbols.topLevelDeclarationFrames
                        .filter { it.nodeTypeId == KtNodeTypes.FUN_ID }
                        .map { it.frameIndex },
                    enclosingClass = null,
                )
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
                val topCls = symbols.classesByFrame[declFrame.frameIndex]
                if (isHeaderMode && topCls != null && isPostIncludeCycleClass(topCls)) {
                    val postClsIdx = postIncludeHeaderBodies.size
                    val defBefore = deferredHeaderBodies.size
                    val clsRendered = renderClassOrObject(declFrame, qualifierPrefix = "", indent = "")
                    if (clsRendered.isNotBlank()) {
                        postIncludeHeaderBodies.add(postClsIdx, clsRendered)
                    }
                    while (deferredHeaderBodies.size > defBefore) {
                        val moved = deferredHeaderBodies.removeAt(defBefore)
                        if (moved.isNotBlank()) {
                            postIncludeHeaderBodies.add(moved)
                        }
                    }
                    return
                }
                val rendered =
                    when (declFrame.nodeTypeId) {
                        KtNodeTypes.TYPEALIAS_ID -> ""
                        KtNodeTypes.PROPERTY_ID ->
                            renderProperty(declFrame, enclosingClass = null, indent = "")
                        KtNodeTypes.FUN_ID ->
                            renderFunction(
                                declFrame,
                                enclosingClass = null,
                                qualifierPrefix = "",
                                indent = "",
                                scopeSeenNormalizedSigs = topLevelSeenNormalizedSigs,
                            )
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

    private fun frameMentionsUndeclaredLocalClass(frameIdx: Int?): Boolean {
        if (frameIdx == null) return false
        val frame = stream.frames[frameIdx]
        if (frame.firstPacketIndex > frame.lastPacketIndex) return false
        for (idx in frame.firstPacketIndex..frame.lastPacketIndex) {
            val pkt = stream.packets[idx]
            if (pkt is SyntaxPacket.IdentPacket) {
                val cls = symbols.classesByName[pkt.unescapedName] ?: continue
                if (cls.enclosingClassFrameIndex == null &&
                    (
                        cls.frameIndex !in symbols.forwardDeclaredClassFrames ||
                            shouldEmitPrivateClassOnlyInCpp(cls) ||
                            isOmittedExpectClass(cls)
                    )
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun emitForwardDeclarations(out: StringBuilder) {
        var emittedAny = false
        val emittedClassFrames = HashSet<Int>()
        for (clsFrameIdx in symbols.forwardDeclaredClassFrames) {
            val cls = symbols.classesByFrame[clsFrameIdx] ?: continue
            if (shouldEmitPrivateClassOnlyInCpp(cls) ||
                isOmittedExpectClass(cls) ||
                cls.kind == KtClassKind.ANNOTATION_CLASS ||
                cls.kotlinName == "TestResult" ||
                cls.kotlinName == "StackTraceElement"
            ) {
                continue
            }
            if (!emittedClassFrames.add(cls.frameIndex)) continue
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
        for (declFrame in symbols.topLevelDeclarationFrames) {
            if (declFrame.nodeTypeId != KtNodeTypes.CLASS_ID &&
                declFrame.nodeTypeId != KtNodeTypes.OBJECT_DECLARATION_ID
            ) {
                continue
            }
            val cls = symbols.classesByFrame[declFrame.frameIndex] ?: continue
            if (cls.frameIndex in emittedClassFrames) continue
            if (shouldEmitPrivateClassOnlyInCpp(cls) ||
                isOmittedExpectClass(cls) ||
                cls.kind == KtClassKind.ANNOTATION_CLASS ||
                cls.kotlinName == "TestResult" ||
                cls.kotlinName == "StackTraceElement"
            ) {
                continue
            }
            emittedClassFrames.add(cls.frameIndex)
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
        for (declFrame in symbols.topLevelDeclarationFrames) {
            if (declFrame.nodeTypeId != KtNodeTypes.FUN_ID) continue
            val fn = symbols.functionsByFrame[declFrame.frameIndex] ?: continue
            if (bundle.suspendPlan.leafIntrinsicsByFunFrame.containsKey(declFrame.frameIndex)) continue
            val liveFlags = declFrame.flags
            if (PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_SUSPEND)) continue
            val isExpectFun = FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID)
            if (isExpectFun) {
                val hasSameFileActual =
                    symbols.topLevelDeclarationFrames.any { other ->
                        other.nodeTypeId == KtNodeTypes.FUN_ID &&
                            other.frameIndex != declFrame.frameIndex &&
                            FrameAstHelpers.hasModifierKeyword(stream, other, KtTokens.ACTUAL_MODIFIER_ID) &&
                            symbols.functionsByFrame[other.frameIndex]?.kotlinName == fn.kotlinName
                    }
                if (hasSameFileActual) continue
                if (fn.receiverTypeFrameIndex != null) {
                    val recvSimple =
                        FrameAstHelpers.extractPrimaryTypeSimpleName(
                            stream,
                            stream.frames[fn.receiverTypeFrameIndex],
                        )
                    val recvCls = recvSimple?.let { symbols.classesByName[it] }
                    if (recvCls != null && isOmittedExpectClass(recvCls)) continue
                }
            }
            if (isDeprecatedHiddenFun(fn) || fn.cAbiSymbolName != null) continue
            if (fn.valueParams.any { it.defaultExprFrameIndex != null }) continue
            val referencedEarlier =
                symbols.topLevelDeclarationFrames.any { earlier ->
                    earlier.frameIndex < declFrame.frameIndex && frameMentionsIdentifier(earlier, fn.kotlinName)
                }
            if (!referencedEarlier) continue
            val rawRetType =
                when {
                    fn.returnTypeFrameIndex != null ->
                        bundle.typeTranslator.translateTypeFrame(
                            stream.frames[fn.returnTypeFrameIndex],
                            CppTypePosition.RETURN,
                        )
                    fn.isExpressionBody && fn.bodyFrameIndex != null ->
                        inferFunctionExpressionBodyReturnType(fn, null, stream.frames[fn.bodyFrameIndex])
                    else -> "void"
                }
            val isNoreturn = rawRetType.startsWith("[[noreturn]] ")
            val retType = rawRetType.removePrefix("[[noreturn]] ")
            if (retType == "auto") continue
            if (frameMentionsUndeclaredLocalClass(fn.returnTypeFrameIndex) ||
                frameMentionsUndeclaredLocalClass(fn.receiverTypeFrameIndex) ||
                fn.valueParams.any { frameMentionsUndeclaredLocalClass(it.typeFrameIndex) }
            ) {
                continue
            }
            val emitInlineInHpp = PacketFlags.hasFlag(liveFlags, PacketFlags.EMIT_IN_HPP_INLINE_BODY)
            val isPureDecl = fn.bodyFrameIndex == null && declFrame.frameIndex !in bundle.ffiPlan.inlineCppByFrame
            val starPlan =
                if (!bundle.isDocExample && emitInlineInHpp && fn.typeParams.isEmpty() && !isPureDecl) {
                    buildStarProjectionPlan(fn)
                } else {
                    StarProjectionPlan(emptyList(), null, emptyMap())
                }
            val effectiveTypeParams = fn.typeParams + starPlan.synthesizedTypeParams
            val paramsStr = buildFunctionParameterList(sym = fn, includeDefaults = false, starPlan = starPlan)
            val methodName = resolveCppFunctionOrOperatorName(fn, null)
            val inlineOpt = if (!isPureDecl && emitInlineInHpp && effectiveTypeParams.isEmpty()) "inline " else ""
            val noreturnOpt = if (isNoreturn) "[[noreturn]] " else ""
            out.append(renderTemplatePrefix(effectiveTypeParams, ""))
            out
                .append(noreturnOpt)
                .append(inlineOpt)
                .append(retType)
                .append(' ')
                .append(methodName)
                .append('(')
                .append(paramsStr)
                .append(");\n")
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
        defaultedVoidPtrNames: Set<String> = emptySet(),
    ): String {
        if (typeParams.isEmpty()) return ""
        val rendered =
            typeParams.joinToString(", ") { tp ->
                if (tp.name in defaultedVoidPtrNames) {
                    "typename ${tp.name} = void*"
                } else {
                    "typename ${tp.name}"
                }
            }
        return "$indent" + "template <$rendered>\n"
    }

    private fun renderTypeAlias(
        aliasFrame: PacketFrame,
        indent: String,
    ): String {
        val sym = symbols.typeAliasesByFrame[aliasFrame.frameIndex] ?: return ""
        val targetType =
            sym.underlyingTypeFrameIndex?.let {
                val aliasPos =
                    if (sym.kotlinName in symbols.sharedHierarchyTypeNames) {
                        CppTypePosition.SUPERTYPE
                    } else {
                        CppTypePosition.VALUE
                    }
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], aliasPos)
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
            if (enclosingClass == null && sym.receiverTypeFrameIndex == null && sym.returnTypeFrameIndex != null) {
                val expType =
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[sym.returnTypeFrameIndex],
                        CppTypePosition.VALUE,
                    )
                if (expType == "bool" || expType == "std::int32_t" || sym.kotlinName == "DefaultDelay") {
                    return "$prefix${indent}extern const $expType ${sym.cppName};\n"
                }
            }
            return "$prefix$indent// expect val ${sym.cppName}\n"
        }
        val emitInCpp = PacketFlags.hasFlag(sym.flags, PacketFlags.EMIT_IN_CPP_BODY)

        // Computed property with custom `get()` and/or `set()`
        if (hasCustomAccessorBody(sym)) {
            val activeTps = LinkedHashSet<String>()
            collectEnclosingClassTypeParamNames(enclosingClass, activeTps)
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
            val needsBackingField =
                sym.initializerFrameIndex != null ||
                    (getterBody != null && frameMentionsIdentifier(getterBody, "field")) ||
                    (setterBody != null && frameMentionsIdentifier(setterBody, "field"))
            val backingFieldName = "kxs_field_${sym.cppName}"
            val propRecvForInfer = resolveReceiverTypeForReturnInference(sym.receiverTypeFrameIndex, enclosingClass)
            val rawRetType =
                sym.returnTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[it],
                        CppTypePosition.RETURN,
                    )
                } ?: getterBody?.let { inferExpressionBodyReturnType(it, enclosingClass, propRecvForInfer) }
                    ?: sym.initializerFrameIndex?.let {
                        inferExpressionBodyReturnType(stream.frames[it], enclosingClass, propRecvForInfer)
                    } ?: "auto"
            val isNoreturn = rawRetType.startsWith("[[noreturn]] ")
            val retType = rawRetType.removePrefix("[[noreturn]] ")
            val setValType =
                sym.returnTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[it],
                        CppTypePosition.PARAMETER,
                    )
                } ?: retType
            val backingCppType =
                sym.returnTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[it],
                        CppTypePosition.VALUE,
                        propFrame.flags,
                    )
                } ?: retType.takeIf { it != "auto" } ?: run {
                    includes.addSystem("any")
                    "std::any"
                }
            val recvTypeRaw =
                sym.receiverTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.EXTENSION_RECEIVER)
                }
            val recvParam =
                recvTypeRaw?.let { rType ->
                    val passType = formatExtensionReceiverPassType(rType)
                    "[[maybe_unused]] $passType self"
                }
            val mutRecvParam =
                recvTypeRaw?.let { rType ->
                    val passType =
                        if (rType.endsWith("&") || rType.endsWith("*")) {
                            rType
                        } else {
                            "$rType&"
                        }
                    "[[maybe_unused]] $passType self"
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
                val outOfLineQual = resolveOutOfLineEnclosingQualification(enclosingClass)
                val deferPropToPostInclude =
                    !emitInCpp &&
                        (enclosingClass == null || outOfLineQual != null) &&
                        bodyReferencesExpectOrDeferredBottomHeader(propFrame)
                val deferPropToBottom =
                    !deferPropToPostInclude &&
                        !emitInCpp &&
                        getterBody != null &&
                        (enclosingClass == null || outOfLineQual != null) &&
                        frameReferencesLaterTopLevelDecl(
                            bodyFrame = getterBody,
                            anchorDeclFrameIndex = outermostEnclosingClass(enclosingClass)?.frameIndex ?: propFrame.frameIndex,
                            enclosingClass = enclosingClass,
                        )
                val deferOutOfLineProp = retType != "auto" && (deferPropToPostInclude || deferPropToBottom)
                val outOfLinePropClsPrefix =
                    if (outOfLineQual != null && outOfLineQual.second.isNotEmpty()) {
                        "${outOfLineQual.second}::"
                    } else {
                        ""
                    }
                return buildString {
                    append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
                    val inlineKw =
                        if ((!emitInCpp || retType == "auto") && enclosingClass == null && sym.typeParams.isEmpty()) {
                            "inline "
                        } else {
                            virtPrefix
                        }
                    val constQual =
                        if (recvParam == null && shouldMarkPropertyGetterConst(sym, enclosingClass, getterBody)) {
                            " const"
                        } else {
                            ""
                        }
                    val noreturnKw = if (isNoreturn) "[[noreturn]] " else ""
                    if (needsBackingField) {
                        val isNonLocalBacking =
                            enclosingClass == null ||
                                enclosingClass.kind == KtClassKind.OBJECT_SINGLETON ||
                                enclosingClass.kind == KtClassKind.COMPANION_OBJECT
                        val fieldStoragePrefix =
                            if (isNonLocalBacking) {
                                if (enclosingClass != null) "static inline " else "inline "
                            } else {
                                ""
                            }
                        val initEmitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                enclosingClass = enclosingClass,
                                enclosingFunctionReturnType = backingCppType,
                                expectedTargetType = backingCppType,
                                inDefaultParameterExpr = isNonLocalBacking,
                            )
                        val rawInit =
                            sym.initializerFrameIndex?.let { initEmitter.emitExpression(stream.frames[it]) }
                        val coercedInit =
                            when {
                                rawInit == null -> "{}"
                                rawInit == "nullptr" && backingCppType.startsWith("std::optional<") -> "std::nullopt"
                                rawInit == "nullptr" && backingCppType == "std::any" -> "{}"
                                else -> rawInit
                            }
                        append(indent)
                            .append(fieldStoragePrefix)
                            .append(backingCppType)
                            .append(' ')
                            .append(backingFieldName)
                        if (coercedInit == "{}") {
                            append("{};\n")
                        } else {
                            append(" = ").append(coercedInit).append(";\n")
                        }
                    }
                    if (getterBody != null) {
                        val paramSig = recvParam.orEmpty()
                        append(renderTemplatePrefix(sym.typeParams, indent))
                        if ((!emitInCpp || retType == "auto") && !deferOutOfLineProp) {
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
                            if (needsBackingField) {
                                emitter.bindLocalName("field", backingFieldName)
                            }
                            val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                            val bodyStr =
                                emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = indent)
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
                                .append(if (enclosingClass == null && deferOutOfLineProp) inlineKw else virtPrefix)
                                .append(retType)
                                .append(' ')
                                .append(propGetterName)
                                .append('(')
                                .append(paramSig)
                                .append(')')
                                .append(constQual)
                                .append(overrideSuffix)
                                .append(";\n")
                            if (deferOutOfLineProp) {
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
                                if (needsBackingField) {
                                    emitter.bindLocalName("field", backingFieldName)
                                }
                                val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                                val bodyStr =
                                    emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = "")
                                val postDef =
                                    buildString {
                                        val outerTps = outOfLineQual?.first.orEmpty()
                                        if (outerTps.isNotEmpty()) {
                                            append(renderTemplatePrefix(outerTps, ""))
                                        }
                                        append(renderTemplatePrefix(sym.typeParams, ""))
                                        append(noreturnKw)
                                        val noTps = outerTps.isEmpty() && sym.typeParams.isEmpty()
                                        if (noTps) {
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
                                if (deferPropToPostInclude) {
                                    postIncludeHeaderBodies.add(postDef)
                                } else {
                                    deferredHeaderBodies.add(postDef)
                                }
                            }
                        }
                    } else if (needsBackingField) {
                        val defaultGetterKw =
                            if (enclosingClass == null && sym.typeParams.isEmpty()) "inline " else virtPrefix
                        append(indent)
                            .append(defaultGetterKw)
                            .append(retType)
                            .append(' ')
                            .append(propGetterName)
                            .append('(')
                            .append(recvParam.orEmpty())
                            .append(')')
                            .append(constQual)
                            .append(overrideSuffix)
                            .append(" { return ")
                            .append(backingFieldName)
                            .append("; }\n")
                    }
                    if (setterBody != null) {
                        val isInlineSetterBody = !emitInCpp
                        val unusedSetterAttr =
                            if (isInlineSetterBody && !frameMentionsIdentifier(setterBody, setterParamKt)) {
                                "[[maybe_unused]] "
                            } else {
                                ""
                            }
                        val setterSig =
                            if (mutRecvParam != null) {
                                "$mutRecvParam, $unusedSetterAttr$setValType $setterParamCpp"
                            } else {
                                "$unusedSetterAttr$setValType $setterParamCpp"
                            }
                        append(renderTemplatePrefix(sym.typeParams, indent))
                        if (!emitInCpp && !deferPropToPostInclude) {
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
                            if (needsBackingField) {
                                emitter.bindLocalName("field", backingFieldName)
                            }
                            val isExpr = setterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                            val bodyStr =
                                emitter.emitBlockOrExprBody(setterBody, isExpr, isVoidReturn = true, indent = indent)
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
                            if (deferPropToPostInclude) {
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
                                if (needsBackingField) {
                                    emitter.bindLocalName("field", backingFieldName)
                                }
                                val isExpr = setterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                                val bodyStr =
                                    emitter.emitBlockOrExprBody(setterBody, isExpr, isVoidReturn = true, indent = "")
                                val postDef =
                                    buildString {
                                        if (enclosingClass != null && enclosingClass.typeParams.isNotEmpty()) {
                                            append(renderTemplatePrefix(enclosingClass.typeParams, ""))
                                        }
                                        append(renderTemplatePrefix(sym.typeParams, ""))
                                        val noTps =
                                            (enclosingClass == null || enclosingClass.typeParams.isEmpty()) &&
                                                sym.typeParams.isEmpty()
                                        if (noTps) {
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
                    } else if (needsBackingField && sym.isVar) {
                        includes.addSystem("utility")
                        val defaultSetterKw =
                            if (enclosingClass == null && sym.typeParams.isEmpty()) "inline " else virtPrefix
                        val setterSig =
                            if (mutRecvParam != null) {
                                "$mutRecvParam, $setValType value"
                            } else {
                                "$setValType value"
                            }
                        append(indent)
                            .append(defaultSetterKw)
                            .append("void ")
                            .append(setterName)
                            .append('(')
                            .append(setterSig)
                            .append(')')
                            .append(overrideSuffix)
                            .append(" { ")
                            .append(backingFieldName)
                            .append(" = std::move(value); }\n")
                    }
                }
            } else if (emitInCpp) {
                val qual = if (enclosingClass != null) "${enclosingClass.cppName}::" else ""
                val constQual =
                    if (recvParam == null && shouldMarkPropertyGetterConst(sym, enclosingClass, getterBody)) {
                        " const"
                    } else {
                        ""
                    }
                val noreturnKw = if (isNoreturn) "[[noreturn]] " else ""
                return buildString {
                    if (getterBody != null && retType != "auto") {
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
                        if (needsBackingField) {
                            emitter.bindLocalName("field", backingFieldName)
                        }
                        val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                        val bodyStr =
                            emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = indent)
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
                            if (mutRecvParam != null) {
                                "$mutRecvParam, $setValType $setterParamCpp"
                            } else {
                                "$setValType $setterParamCpp"
                            }
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
                        if (needsBackingField) {
                            emitter.bindLocalName("field", backingFieldName)
                        }
                        val isExpr = setterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                        val bodyStr =
                            emitter.emitBlockOrExprBody(setterBody, isExpr, isVoidReturn = true, indent = indent)
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
            val atomicSpec = resolveAtomicFieldRenderSpec(af, enclosingClass)
            val elemType = atomicSpec.elemType
            val atomicTemplateName = atomicSpec.atomicTemplateName
            val useAtomicRefHolder = atomicSpec.useAtomicRefHolder
            val fieldCppName =
                if (enclosingClass != null && isBackedOverrideMemberProp(enclosingClass, propFrame, sym)) {
                    if (sym.cppName.startsWith("kxs_atomic_")) sym.cppName else CppNamingPolicy.toPrivateFieldName(sym.kotlinName)
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
                            .append("mutable std::vector<")
                            .append(atomicTemplateName)
                            .append('<')
                            .append(elemType)
                            .append(">> ")
                            .append(fieldCppName)
                            .append("{};\n")
                    } else {
                        append(indent)
                            .append("mutable std::vector<")
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
                    val rawInitCode =
                        if (initFrame == null || referencesNonFieldCtorParam(enclosingClass, initFrame)) {
                            "{}"
                        } else if (elemType == "void*") {
                            renderVoidPtrAtomicInit(initFrame, emitter)
                        } else {
                            emitter.emitExpression(initFrame)
                        }
                    val initCode = if (useAtomicRefHolder && rawInitCode == "nullptr") "{}" else rawInitCode
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

        val explicitType =
            sym.returnTypeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE, propFrame.flags)
            }
        val isNonLocalInit =
            enclosingClass == null ||
                enclosingClass.kind == KtClassKind.OBJECT_SINGLETON ||
                enclosingClass.kind == KtClassKind.COMPANION_OBJECT
        val emitter =
            CppExprAndStmtEmitter(
                bundle = bundle,
                enclosingClass = enclosingClass,
                enclosingFunctionReturnType = explicitType,
                expectedTargetType = explicitType,
                inDefaultParameterExpr = isNonLocalInit,
            )
        val initCode = sym.initializerFrameIndex?.let { emitter.emitExpression(stream.frames[it]) }

        return buildString {
            append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
            if (sym.isConst) {
                val cppT = explicitType ?: "auto"
                val isStringConstInit =
                    sym.initializerFrameIndex?.let { initIdx ->
                        stream.frames[initIdx].nodeTypeId == KtNodeTypes.STRING_TEMPLATE_ID
                    } ?: false
                if (cppT == "std::string" || (cppT == "auto" && isStringConstInit)) {
                    includes.addSystem("string")
                    val storageKw = if (enclosingClass != null) "static inline const " else "inline const "
                    append(indent)
                        .append(storageKw)
                        .append("std::string ")
                        .append(sym.cppName)
                        .append(" = ")
                        .append(initCode ?: "\"\"")
                        .append(";\n")
                } else {
                    val staticOrInline = if (enclosingClass != null) "static constexpr " else "inline constexpr "
                    val valT = cppT
                    val renderedInit =
                        if (valT == "std::string_view" && sym.initializerFrameIndex != null) {
                            val strFrame = stream.frames[sym.initializerFrameIndex]
                            CppExprAndStmtEmitter.escapeCppStringLiteral(
                                FrameAstHelpers.extractConstantStringText(stream, strFrame),
                            )
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
                }
            } else if (
                enclosingClass == null ||
                enclosingClass.kind == KtClassKind.OBJECT_SINGLETON ||
                enclosingClass.kind == KtClassKind.COMPANION_OBJECT
            ) {
                val storageKw = if (enclosingClass != null) "static inline " else "inline "
                val inferredNonLocalType =
                    sym.initializerFrameIndex?.let { idx ->
                        inferExpressionBodyReturnType(
                            stream.frames[idx],
                            enclosingClass,
                            resolveReceiverTypeForReturnInference(null, enclosingClass),
                        ).takeIf { it != "auto" }
                    }
                val cppT = explicitType ?: inferredNonLocalType ?: "auto"
                val isInherentlyMutableVal =
                    cppT == "KxsSynchronizedObject" ||
                        cppT.startsWith("std::unordered_") ||
                        cppT.startsWith("std::vector<") ||
                        cppT.startsWith("KxsArrayDeque<") ||
                        cppT.contains("ThreadSafeHeap<") ||
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
                val outOfLineStaticQual =
                    if (isHeaderMode && enclosingClass != null && cppT != "auto" && sym.initializerFrameIndex != null) {
                        resolveOutOfLineEnclosingQualification(enclosingClass)?.takeIf { it.first.isEmpty() }
                    } else {
                        null
                    }
                val deferStaticToPost =
                    outOfLineStaticQual != null && bodyReferencesExpectOrDeferredBottomHeader(propFrame)
                val deferStaticToBottom =
                    !deferStaticToPost &&
                        outOfLineStaticQual != null &&
                        sym.initializerFrameIndex != null &&
                        frameReferencesLaterTopLevelDecl(
                            bodyFrame = stream.frames[sym.initializerFrameIndex],
                            anchorDeclFrameIndex = outermostEnclosingClass(enclosingClass)?.frameIndex ?: propFrame.frameIndex,
                            enclosingClass = enclosingClass,
                        )
                if (initCode != null) {
                    val coercedInit =
                        when {
                            initCode == "nullptr" && cppT.startsWith("std::optional<") -> "std::nullopt"
                            initCode == "nullptr" && cppT == "std::any" -> "{}"
                            else -> initCode
                        }
                    if (outOfLineStaticQual != null && (deferStaticToPost || deferStaticToBottom)) {
                        append(indent)
                            .append("static ")
                            .append(constKw)
                            .append(cppT)
                            .append(' ')
                            .append(sym.cppName)
                            .append(";\n")
                        val defLine =
                            "inline $constKw$cppT ${outOfLineStaticQual.second}::${sym.cppName} = $coercedInit;\n"
                        if (deferStaticToPost) {
                            postIncludeHeaderBodies.add(defLine)
                        } else {
                            deferredHeaderBodies.add(defLine)
                        }
                    } else {
                        append(indent)
                            .append(storageKw)
                            .append(constKw)
                            .append(cppT)
                            .append(' ')
                            .append(sym.cppName)
                            .append(" = ")
                            .append(coercedInit)
                            .append(";\n")
                    }
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
                        inferExpressionBodyReturnType(
                            stream.frames[idx],
                            enclosingClass,
                            resolveReceiverTypeForReturnInference(null, enclosingClass),
                        ).takeIf { it != "auto" }
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
                            if (sym.cppName.startsWith("kxs_atomic_")) sym.cppName else CppNamingPolicy.toPrivateFieldName(sym.kotlinName)
                        } else {
                            sym.cppName
                        }
                    val omitFieldConst =
                        shouldOmitInstanceFieldConst(
                            enclosingClass,
                            sym.kotlinName,
                            hasInlineInitializer = sym.initializerFrameIndex != null,
                        )
                    val qualKw =
                        if (cppT.startsWith("std::atomic<") ||
                            cppT.startsWith("KxsAtomicReference<") ||
                            cppT.startsWith("WorkaroundAtomicReference<") ||
                            cppT.startsWith("KxsCopyableAtomic<") ||
                            cppT == "KxsSynchronizedObject"
                        ) {
                            "mutable "
                        } else if (!sym.isVar &&
                            !omitFieldConst &&
                            !cppT.startsWith("std::unordered_") &&
                            !cppT.startsWith("std::vector<") &&
                            !cppT.startsWith("KxsArrayDeque<") &&
                            !cppT.contains("ThreadSafeHeap<")
                        ) {
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
        scopeSeenNormalizedSigs: MutableSet<String> = HashSet(),
    ): String {
        val sym = symbols.functionsByFrame[fnFrame.frameIndex] ?: return ""
        val liveFlags = fnFrame.flags

        // Special dual-ABI leaf `suspendCoroutineUninterceptedOrReturn` intrinsic (`Yield.hpp` + `Yield.cpp`)
        bundle.suspendPlan.leafIntrinsicsByFunFrame[fnFrame.frameIndex]?.let { leaf ->
            return renderLeafContinuationIntrinsicFunction(fnFrame, sym, enclosingClass, leaf, qualifierPrefix, indent)
        }

        val emitInCpp = PacketFlags.hasFlag(liveFlags, PacketFlags.EMIT_IN_CPP_BODY)
        val emitInlineInHpp = PacketFlags.hasFlag(liveFlags, PacketFlags.EMIT_IN_HPP_INLINE_BODY)
        val isPureDecl = sym.bodyFrameIndex == null && fnFrame.frameIndex !in bundle.ffiPlan.inlineCppByFrame
        val isExpectFun = FrameAstHelpers.hasModifierKeyword(stream, fnFrame, KtTokens.EXPECT_MODIFIER_ID)

        if (!isHeaderMode && !emitInCpp) return ""
        if (isExpectFun && isPureDecl) {
            includes.hasExpectDeclarations = true
            if (sym.receiverTypeFrameIndex != null) {
                val recvSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[sym.receiverTypeFrameIndex])
                val recvCls = recvSimple?.let { symbols.classesByName[it] }
                if (recvCls != null && isOmittedExpectClass(recvCls)) {
                    val prefix = renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true)
                    return "$prefix$indent// expect fun ${sym.cppName}\n"
                }
            }
        }

        val collapsedVarianceTp = resolveCollapsedVarianceEscapeTypeParam(sym, enclosingClass)
        val baseTypeParams = if (collapsedVarianceTp != null) emptyList() else sym.typeParams

        val comparableElem = resolveComparableElemType(enclosingClass)
        val isComparableOverride =
            !bundle.isDocExample &&
                comparableElem != null &&
                sym.kotlinName == "compareTo" &&
                sym.valueParams.size == 1 &&
                sym.typeParams.isEmpty() &&
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE)

        val initialRawRetType =
            when {
                isComparableOverride -> "int"
                !bundle.isDocExample && sym.kotlinName == "createTestResult" -> "TestResult"
                sym.returnTypeFrameIndex != null ->
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[sym.returnTypeFrameIndex],
                        CppTypePosition.RETURN,
                    )
                sym.isExpressionBody && sym.bodyFrameIndex != null ->
                    inferFunctionExpressionBodyReturnType(sym, enclosingClass, stream.frames[sym.bodyFrameIndex])
                else -> "void"
            }
        val isNoreturn = initialRawRetType.startsWith("[[noreturn]] ")
        val baseRetType = initialRawRetType.removePrefix("[[noreturn]] ")
        val overrideNonVoidRet =
            if ((baseRetType == "auto" || (isNoreturn && baseRetType == "void")) &&
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE) &&
                enclosingClass != null
            ) {
                resolveSuperMethodReturnType(enclosingClass, sym, allowVoid = baseRetType == "auto")
            } else {
                null
            }
        val preCopyRetType = overrideNonVoidRet ?: baseRetType
        val copyableRawRetType =
            if (!bundle.isDocExample &&
                sym.kotlinName == "createCopy" &&
                sym.valueParams.isEmpty() &&
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE) &&
                !preCopyRetType.startsWith("std::shared_ptr<") &&
                preCopyRetType != "void" &&
                preCopyRetType != "auto"
            ) {
                preCopyRetType
            } else {
                null
            }
        val unwrappedRetType =
            if (copyableRawRetType != null) {
                includes.addSystem("memory")
                "std::shared_ptr<$copyableRawRetType>"
            } else {
                preCopyRetType
            }
        val rawRetType = if (isNoreturn) "[[noreturn]] $unwrappedRetType" else unwrappedRetType
        val retTypeNoShadow = unwrappedRetType
        val isVoidReturn = unwrappedRetType == "void"

        val isDepHidden = isDeprecatedHiddenFun(sym)
        val baseMethodName = resolveCppFunctionOrOperatorName(sym, enclosingClass)
        val hasClassPropCollision =
            enclosingClass != null &&
                !isDepHidden &&
                sym.valueParams.isEmpty() &&
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_PRIVATE) &&
                (
                    enclosingClass.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == sym.kotlinName } ||
                        enclosingClass.memberPropertyFrameIndices.any {
                            symbols.propertiesByFrame[it]?.kotlinName == sym.kotlinName
                        }
                )
        val methodName =
            when {
                isDepHidden -> "${baseMethodName}_deprecated_hidden_${sym.valueParams.size}"
                hasClassPropCollision -> "${baseMethodName}_fn"
                else -> baseMethodName
            }
        val isInterfaceMethod = enclosingClass?.kind == KtClassKind.INTERFACE
        val isCAbi = sym.cAbiSymbolName != null
        val isHashCodeOverrideOnCancellationExc =
            sym.kotlinName == "hashCode" &&
                sym.valueParams.isEmpty() &&
                enclosingClass != null &&
                isCancellationExceptionSubclass(enclosingClass)
        val isEqualsOverrideOnCancellationExc =
            sym.kotlinName == "equals" &&
                sym.valueParams.size == 1 &&
                enclosingClass != null &&
                isCancellationExceptionSubclass(enclosingClass)
        val superDeclaresToString =
            sym.kotlinName == "toString" &&
                sym.valueParams.isEmpty() &&
                enclosingClass != null &&
                superDeclaresMethod(enclosingClass, "toString")
        val superDeclaresHashCode =
            sym.kotlinName == "hashCode" &&
                sym.valueParams.isEmpty() &&
                enclosingClass != null &&
                superDeclaresMethod(enclosingClass, "hashCode")
        val superDeclaresEquals =
            sym.kotlinName == "equals" &&
                sym.valueParams.size == 1 &&
                enclosingClass != null &&
                superDeclaresMethod(enclosingClass, "equals")
        val isToStringRoot =
            sym.kotlinName == "toString" &&
                sym.valueParams.isEmpty() &&
                !superDeclaresToString
        val isHashCodeRoot =
            sym.kotlinName == "hashCode" &&
                sym.valueParams.isEmpty() &&
                !isHashCodeOverrideOnCancellationExc &&
                !superDeclaresHashCode
        val isEqualsRoot =
            sym.kotlinName == "equals" &&
                sym.valueParams.size == 1 &&
                !isEqualsOverrideOnCancellationExc &&
                !superDeclaresEquals
        val isKotlinAnyRootMethod = isToStringRoot || isHashCodeRoot || isEqualsRoot
        val hasBaseVirtualTrait =
            isInterfaceMethod ||
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) ||
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE)
        val isSuspend = PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_SUSPEND)

        val starPlan =
            if (!bundle.isDocExample &&
                !isCAbi &&
                (emitInlineInHpp || (isPureDecl && isExpectFun)) &&
                !isSuspend &&
                baseTypeParams.isEmpty() &&
                !hasBaseVirtualTrait
            ) {
                buildStarProjectionPlan(sym)
            } else {
                StarProjectionPlan(emptyList(), null, emptyMap())
            }
        val effectiveTypeParams = baseTypeParams + starPlan.synthesizedTypeParams
        val enclosingTpNames = enclosingClass?.typeParams?.mapTo(HashSet()) { it.name }.orEmpty()
        val shadowTpRenames = LinkedHashMap<String, String>()
        val renderedTypeParams =
            effectiveTypeParams.map { tp ->
                if (tp.name in enclosingTpNames) {
                    val renamed = "KxsFn${tp.name}"
                    shadowTpRenames[tp.name] = renamed
                    tp.copy(name = renamed)
                } else {
                    tp
                }
            }
        val retType = replaceWholeIdentifierOutsideStrings(retTypeNoShadow, shadowTpRenames)
        val rawRetTypeEffective = replaceWholeIdentifierOutsideStrings(rawRetType, shadowTpRenames)
        val isDualAbiSuspend = isSuspend && !isCAbi && !bundle.isDocExample
        val forceInlineAuto = retType == "auto" && !isPureDecl && !isDualAbiSuspend
        if (!isHeaderMode && forceInlineAuto) return ""
        val effectiveEmitInlineInHpp = emitInlineInHpp || forceInlineAuto

        val activeTps = LinkedHashSet<String>()
        collectEnclosingClassTypeParamNames(enclosingClass, activeTps)
        effectiveTypeParams.forEach { activeTps.add(it.name) }

        val canBeVirtual = renderedTypeParams.isEmpty() && retType != "auto" && !isDepHidden
        val isVirtualMethod =
            canBeVirtual &&
                (
                    isInterfaceMethod ||
                        PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
                        PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) ||
                        (isKotlinAnyRootMethod && PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE))
                )
        val isOverride =
            isComparableOverride ||
                isEqualsOverrideOnCancellationExc ||
                (
                    canBeVirtual &&
                        (
                            !isKotlinAnyRootMethod ||
                                isHashCodeOverrideOnCancellationExc ||
                                superDeclaresToString ||
                                superDeclaresHashCode ||
                                superDeclaresEquals
                        ) &&
                        PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE) &&
                        enclosingClass != null &&
                        enclosingClass.superTypeFrameIndices.isNotEmpty()
                )
        val isStaticOnObject =
            (enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT) &&
                !isOverride &&
                !(isKotlinAnyRootMethod && PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE))
        val isConstMethod =
            isComparableOverride || isEqualsOverrideOnCancellationExc || shouldMarkMethodConst(sym, enclosingClass)
        var suspendBoxType: String? = null
        var suspendAuthoringRetType = retTypeNoShadow
        var hasBoxedResult = false
        if (isDualAbiSuspend) {
            includes.needsSuspendBoxHelpersDef = true
            val isRetUnit =
                isVoidReturn ||
                    retTypeNoShadow == "Unit" ||
                    retTypeNoShadow == "kotlin::Unit" ||
                    retTypeNoShadow == "kotlinx::coroutines::Unit"
            val mentionsTp =
                effectiveTypeParams.any { containsWholeWord(retTypeNoShadow, it.name) } ||
                    enclosingClass?.typeParams?.any { containsWholeWord(retTypeNoShadow, it.name) } == true
            if (retTypeNoShadow == "void*") {
                suspendBoxType = null
                suspendAuthoringRetType = "void*"
                hasBoxedResult = false
            } else if (mentionsTp) {
                includes.addProject("kotlinx/coroutines/Unit.hpp")
                val safeBox = "std::conditional_t<std::is_void_v<$retTypeNoShadow>, kotlin::Unit, $retTypeNoShadow>"
                suspendBoxType = safeBox
                suspendAuthoringRetType = safeBox
                hasBoxedResult = true
            } else if (isRetUnit) {
                suspendBoxType = null
                suspendAuthoringRetType = "void"
                hasBoxedResult = false
            } else {
                val safeRet =
                    if (retTypeNoShadow == "auto") {
                        includes.addSystem("any")
                        "std::any"
                    } else {
                        retTypeNoShadow
                    }
                suspendBoxType = safeRet
                suspendAuthoringRetType = safeRet
                hasBoxedResult = true
            }
        }
        val renderedSuspendAuthoringRetType =
            replaceWholeIdentifierOutsideStrings(suspendAuthoringRetType, shadowTpRenames)
        val authoringAnn =
            if (hasBoxedResult) {
                "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]"
            } else {
                "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"
            }

        fun appendCompletionParam(
            baseParams: String,
            completionUnused: Boolean,
        ): String {
            val contDecl =
                if (completionUnused) {
                    "[[maybe_unused]] std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
                } else {
                    "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
                }
            return if (baseParams.isEmpty()) contDecl else "$baseParams, $contDecl"
        }

        if (isHeaderMode) {
            val outOfLineFnQual = resolveOutOfLineEnclosingQualification(enclosingClass)
            val canDeferReturnType = isDualAbiSuspend || retType != "auto"
            val deferToPostInclude =
                canDeferReturnType &&
                    effectiveEmitInlineInHpp &&
                    !isPureDecl &&
                    !isCAbi &&
                    sym.bodyFrameIndex != null &&
                    (enclosingClass == null || outOfLineFnQual != null) &&
                    bodyReferencesExpectOrDeferredBottomHeader(fnFrame)
            val deferHeaderBody =
                canDeferReturnType &&
                    !deferToPostInclude &&
                    effectiveEmitInlineInHpp &&
                    !isPureDecl &&
                    !isCAbi &&
                    sym.bodyFrameIndex != null &&
                    (enclosingClass == null || outOfLineFnQual != null) &&
                    bodyReferencesLaterTopLevelDecl(
                        bodyFrame = stream.frames[sym.bodyFrameIndex],
                        anchorDeclFrameIndex = outermostEnclosingClass(enclosingClass)?.frameIndex ?: fnFrame.frameIndex,
                        sym = sym,
                        enclosingClass = enclosingClass,
                    )
            val hasInlineHeaderBody =
                (!isPureDecl && effectiveEmitInlineInHpp) || (isPureDecl && isDepHidden && enclosingClass != null)
            val preRenderedHeaderBody =
                if (!isPureDecl && effectiveEmitInlineInHpp) {
                    val headerIndent = if (deferToPostInclude || deferHeaderBody) "" else indent
                    val rawBody =
                        renderFunctionBody(
                            sym = sym,
                            enclosingClass = enclosingClass,
                            activeTypeParams = activeTps,
                            isVoidReturn = isVoidReturn,
                            indent = headerIndent,
                            collapsedVarianceTp = collapsedVarianceTp,
                            isSuspendBody = isDualAbiSuspend,
                            suspendBoxType = suspendBoxType,
                            forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                            comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                            wrapReturnInMakeSharedOf = copyableRawRetType,
                        )
                    val ensuredBody = ensureCreateTestResultReturn(rawBody, sym, isDualAbiSuspend, headerIndent)
                    val noreturnSafeBody =
                        ensureNoreturnUnreachable(ensuredBody, isNoreturn && !isDualAbiSuspend, headerIndent)
                    replaceWholeIdentifierOutsideStrings(noreturnSafeBody, shadowTpRenames)
                } else {
                    null
                }
            val isTemplateInterfaceStub =
                isPureDecl &&
                    (isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                    !canBeVirtual &&
                    renderedTypeParams.isNotEmpty()
            val unusedHeaderParamNames =
                if (isTemplateInterfaceStub || (isPureDecl && isDepHidden && enclosingClass != null)) {
                    val unused = sym.valueParams.mapTo(HashSet()) { it.cppName }
                    if (sym.receiverTypeFrameIndex != null) unused.add("self")
                    unused
                } else if (!hasInlineHeaderBody) {
                    emptySet()
                } else if (preRenderedHeaderBody != null) {
                    val unused =
                        sym.valueParams
                            .filter { vp -> !CppComments.containsWholeWordInCode(preRenderedHeaderBody, vp.cppName) }
                            .mapTo(HashSet()) { it.cppName }
                    if (sym.receiverTypeFrameIndex != null &&
                        !CppComments.containsWholeWordInCode(preRenderedHeaderBody, "self")
                    ) {
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
                    unusedParamCppNames =
                        if (!isTemplateInterfaceStub && (isDualAbiSuspend || deferToPostInclude || deferHeaderBody)) {
                            emptySet()
                        } else {
                            unusedHeaderParamNames
                        },
                    isSuspend = isDualAbiSuspend,
                    forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                    comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                )
            val paramsRendered =
                rawParamsRendered
                    .let { p ->
                        if (collapsedVarianceTp != null) {
                            replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                        } else {
                            p
                        }
                    }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
            val declDefaultedVoidPtrTps =
                if (isHeaderMode &&
                    !isPureDecl &&
                    !isOverride &&
                    !isDualAbiSuspend &&
                    !deferToPostInclude &&
                    !deferHeaderBody &&
                    sym.receiverTypeFrameIndex == null &&
                    enclosingClass?.kind == KtClassKind.COMPANION_OBJECT &&
                    renderedTypeParams.isNotEmpty()
                ) {
                    val trailing = LinkedHashSet<String>()
                    for (tp in renderedTypeParams.asReversed()) {
                        if (containsWholeWord(paramsRendered, tp.name)) break
                        trailing.add(tp.name)
                    }
                    trailing
                } else {
                    emptySet()
                }
            return buildString {
                append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                if (isDualAbiSuspend) {
                    // 1. Authoring overload (non-virtual declaration-only `;` with `kxs_implicit_continuation`)
                    append(renderTemplatePrefix(renderedTypeParams, indent))
                    append(indent).append(authoringAnn).append('\n')
                    append(indent)
                    if (isNoreturn) append("[[noreturn]] ")
                    if (isStaticOnObject) append("static ")
                    append(renderedSuspendAuthoringRetType)
                        .append(' ')
                        .append(methodName)
                        .append('(')
                        .append(paramsRendered)
                        .append(')')
                    if (isConstMethod) append(" const")
                    append(";\n\n")

                    // 2. Lowered ABI overload (`void*` + `_kxs_completion`)
                    val rawLoweredParams =
                        buildFunctionParameterList(
                            sym = sym,
                            includeDefaults = false,
                            starPlan = starPlan,
                            unusedParamCppNames =
                                if (!isTemplateInterfaceStub && (deferToPostInclude || deferHeaderBody || !hasInlineHeaderBody)) {
                                    emptySet()
                                } else {
                                    unusedHeaderParamNames
                                },
                            isSuspend = true,
                            forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                            comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                        )
                    val loweredBaseParams =
                        rawLoweredParams
                            .let { p ->
                                if (collapsedVarianceTp != null) {
                                    replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                                } else {
                                    p
                                }
                            }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
                    val loweredDeclParams =
                        appendCompletionParam(loweredBaseParams, completionUnused = isTemplateInterfaceStub)
                    val completionUnusedInInline =
                        hasInlineHeaderBody &&
                            !deferToPostInclude &&
                            !deferHeaderBody &&
                            (
                                preRenderedHeaderBody == null ||
                                    !CppComments.containsWholeWordInCode(preRenderedHeaderBody, "_kxs_completion")
                            )
                    val loweredInlineParams =
                        appendCompletionParam(loweredBaseParams, completionUnused = completionUnusedInInline)
                    val loweredUsedParams =
                        if (!isPureDecl && effectiveEmitInlineInHpp && !deferToPostInclude && !deferHeaderBody) {
                            loweredInlineParams
                        } else {
                            loweredDeclParams
                        }

                    append(renderTemplatePrefix(renderedTypeParams, indent))
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                    append(indent)
                    if (isStaticOnObject) {
                        append("static ")
                    } else if (isVirtualMethod && !isOverride) {
                        append("virtual ")
                    } else if (effectiveEmitInlineInHpp && enclosingClass == null && renderedTypeParams.isEmpty()) {
                        append("inline ")
                    }
                    append("void* ")
                        .append(methodName)
                        .append('(')
                        .append(loweredUsedParams)
                        .append(')')
                    if (isConstMethod) append(" const")
                    if (isOverride && !isStaticOnObject) append(" override")

                    if (isPureDecl) {
                        if (isDepHidden && enclosingClass != null) {
                            append(" { return nullptr; }\n")
                        } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                            canBeVirtual
                        ) {
                            append(" = 0;\n")
                        } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                            renderedTypeParams.isNotEmpty()
                        ) {
                            includes.addSystem("stdexcept")
                            append(" { throw std::logic_error(\"unimplemented template interface method\"); }\n")
                        } else {
                            append(";\n")
                        }
                    } else if (deferToPostInclude || deferHeaderBody) {
                        append(";\n")
                        val rawDefBaseParams =
                            buildFunctionParameterList(
                                sym = sym,
                                includeDefaults = false,
                                starPlan = starPlan,
                                unusedParamCppNames = unusedHeaderParamNames,
                                isSuspend = true,
                                forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                                comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                            ).let { p ->
                                if (collapsedVarianceTp != null) {
                                    replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                                } else {
                                    p
                                }
                            }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
                        val defCompletionUnused =
                            preRenderedHeaderBody == null ||
                                !CppComments.containsWholeWordInCode(preRenderedHeaderBody, "_kxs_completion")
                        val defParams = appendCompletionParam(rawDefBaseParams, completionUnused = defCompletionUnused)
                        val defText =
                            buildString {
                                if (enclosingClass != null && enclosingClass.typeParams.isNotEmpty()) {
                                    append(renderTemplatePrefix(enclosingClass.typeParams, ""))
                                }
                                append(renderTemplatePrefix(renderedTypeParams, ""))
                                append("[[clang::annotate(\"suspend\")]]\n")
                                val noFuncOrClsTps =
                                    (enclosingClass == null || enclosingClass.typeParams.isEmpty()) &&
                                        renderedTypeParams.isEmpty()
                                if (noFuncOrClsTps) {
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
                                                val tpList = enclosingClass.typeParams.joinToString(", ") { it.name }
                                                "${enclosingClass.cppName}<$tpList>"
                                            }
                                        "$clsHead::$methodName"
                                    }
                                append("void* ")
                                    .append(qualMethod)
                                    .append('(')
                                    .append(defParams)
                                    .append(')')
                                if (isConstMethod) append(" const")
                                append(' ').append(preRenderedHeaderBody).append('\n')
                            }
                        if (deferToPostInclude) {
                            postIncludeHeaderBodies.add(defText)
                        } else {
                            deferredHeaderBodies.add(defText)
                        }
                    } else if (effectiveEmitInlineInHpp) {
                        append(' ').append(preRenderedHeaderBody).append('\n')
                    } else {
                        append(";\n")
                    }
                } else {
                    append(renderTemplatePrefix(renderedTypeParams, indent, declDefaultedVoidPtrTps))
                    append(indent)
                    if (isNoreturn) append("[[noreturn]] ")
                    if (isCAbi) {
                        append("extern \"C\" ")
                    } else if (isStaticOnObject) {
                        append("static ")
                    } else if (isVirtualMethod && !isOverride) {
                        append("virtual ")
                    } else if (
                        effectiveEmitInlineInHpp &&
                        enclosingClass == null &&
                        renderedTypeParams.isEmpty() &&
                        !(bundle.isDocExample && isSuspend)
                    ) {
                        append("inline ")
                    }
                    if (bundle.isDocExample && isSuspend) append("suspend ")
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
                        } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                            canBeVirtual
                        ) {
                            append(" = 0;\n")
                        } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                            renderedTypeParams.isNotEmpty()
                        ) {
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
                                forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                                comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                            ).let { p ->
                                if (collapsedVarianceTp != null) {
                                    replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                                } else {
                                    p
                                }
                            }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
                        val defText =
                            buildString {
                                val outerTps = outOfLineFnQual?.first.orEmpty()
                                if (outerTps.isNotEmpty()) {
                                    append(renderTemplatePrefix(outerTps, ""))
                                }
                                append(renderTemplatePrefix(renderedTypeParams, ""))
                                if (isNoreturn) append("[[noreturn]] ")
                                val noFuncOrClsTps =
                                    outerTps.isEmpty() && renderedTypeParams.isEmpty()
                                if (noFuncOrClsTps && !(bundle.isDocExample && isSuspend)) {
                                    append("inline ")
                                }
                                val qualMethod =
                                    if (outOfLineFnQual == null || outOfLineFnQual.second.isEmpty()) {
                                        methodName
                                    } else {
                                        "${outOfLineFnQual.second}::$methodName"
                                    }
                                if (bundle.isDocExample && isSuspend) append("suspend ")
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
                    } else if (effectiveEmitInlineInHpp) {
                        append(' ').append(preRenderedHeaderBody).append('\n')
                    } else {
                        append(";\n")
                    }
                    appendCallableFunctionForwarderIfEligible(
                        sym = sym,
                        enclosingClass = enclosingClass,
                        renderedTypeParams = renderedTypeParams,
                        retType = retType,
                        methodName = methodName,
                        indent = indent,
                        isPureOrInlineDecl = isPureDecl || effectiveEmitInlineInHpp,
                        starPlan = starPlan,
                        collapsedVarianceTp = collapsedVarianceTp,
                        shadowTpRenames = shadowTpRenames,
                    )
                }
                val allowDefaultOverloads =
                    !isPureDecl &&
                        !isDepHidden &&
                        !isInterfaceMethod &&
                        !PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) &&
                        !isCAbi &&
                        !sym.isOperator
                if (allowDefaultOverloads) {
                    val overloadsText =
                        renderPrefixDefaultOverloads(
                            sym = sym,
                            enclosingClass = enclosingClass,
                            retType = rawRetTypeEffective,
                            isVoidReturn = isVoidReturn,
                            methodName = methodName,
                            isConstMethod = isConstMethod,
                            indent = indent,
                            effectiveTypeParams = renderedTypeParams,
                            starPlan = starPlan,
                            isDualAbiSuspend = isDualAbiSuspend,
                            suspendAuthoringRetType = renderedSuspendAuthoringRetType,
                            hasBoxedResult = hasBoxedResult,
                            isEqualsOverrideOnCancellationExc = isEqualsOverrideOnCancellationExc,
                            comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                            scopeSeenNormalizedSigs = scopeSeenNormalizedSigs,
                        )
                    append(replaceWholeIdentifierOutsideStrings(overloadsText, shadowTpRenames))
                }
                val allowVarargListOverload =
                    bundle.isDocExample &&
                        !isPureDecl &&
                        !isDepHidden &&
                        !hasBaseVirtualTrait &&
                        !isCAbi &&
                        !sym.isOperator
                if (allowVarargListOverload) {
                    val varargText =
                        renderVarargInitializerListOverload(
                            sym = sym,
                            enclosingClass = enclosingClass,
                            retType = rawRetTypeEffective,
                            isVoidReturn = isVoidReturn,
                            methodName = methodName,
                            isConstMethod = isConstMethod,
                            isDualAbiSuspend = isDualAbiSuspend,
                            suspendAuthoringRetType = renderedSuspendAuthoringRetType,
                            hasBoxedResult = hasBoxedResult,
                            indent = indent,
                            effectiveTypeParams = renderedTypeParams,
                            starPlan = starPlan,
                        )
                    append(replaceWholeIdentifierOutsideStrings(varargText, shadowTpRenames))
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
                        .append("std::shared_ptr<Continuation<void*>> intercept_continuation(")
                        .append("std::shared_ptr<Continuation<void*>> continuation) override ")
                        .append("{ return this->intercept_continuation<void*>(std::move(continuation)); }\n")
                }
            }
        }

        val rawCppBody =
            ensureCreateTestResultReturn(
                body =
                    renderFunctionBody(
                        sym = sym,
                        enclosingClass = enclosingClass,
                        activeTypeParams = activeTps,
                        isVoidReturn = isVoidReturn,
                        indent = indent,
                        collapsedVarianceTp = collapsedVarianceTp,
                        isSuspendBody = isDualAbiSuspend,
                        suspendBoxType = suspendBoxType,
                        forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                        comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                        wrapReturnInMakeSharedOf = copyableRawRetType,
                    ),
                sym = sym,
                isDualAbiSuspend = isDualAbiSuspend,
                indent = indent,
            )
        val cppBody =
            ensureNoreturnUnreachable(rawCppBody, isNoreturn && !isDualAbiSuspend, indent)
                .let { b -> replaceWholeIdentifierOutsideStrings(b, shadowTpRenames) }
        val unusedCppParamNames =
            sym.valueParams
                .filter { vp -> !CppComments.containsWholeWordInCode(cppBody, vp.cppName) }
                .mapTo(HashSet()) { it.cppName }
                .apply {
                    if (sym.receiverTypeFrameIndex != null && !CppComments.containsWholeWordInCode(cppBody, "self")) {
                        add("self")
                    }
                }
        val rawParamsRendered =
            buildFunctionParameterList(
                sym = sym,
                includeDefaults = false,
                starPlan = starPlan,
                unusedParamCppNames = unusedCppParamNames,
                isSuspend = isDualAbiSuspend,
                forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
            )
        val paramsRendered =
            rawParamsRendered
                .let { p ->
                    if (collapsedVarianceTp != null) {
                        replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                    } else {
                        p
                    }
                }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
        // `.cpp` out-of-line implementation
        val qualifiedName = if (qualifierPrefix.isEmpty()) methodName else "$qualifierPrefix::$methodName"
        return buildString {
            append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = false))
            append(renderTemplatePrefix(renderedTypeParams, indent))
            if (isDualAbiSuspend) {
                val cppContUnused = !CppComments.containsWholeWordInCode(cppBody, "_kxs_completion")
                val cppLoweredParams = appendCompletionParam(paramsRendered, completionUnused = cppContUnused)
                append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                append(indent)
                    .append("void* ")
                    .append(qualifiedName)
                    .append('(')
                    .append(cppLoweredParams)
                    .append(')')
                if (isConstMethod) append(" const")
                append(' ').append(cppBody).append('\n')
            } else {
                append(indent)
                if (isNoreturn) append("[[noreturn]] ")
                if (isCAbi) append("extern \"C\" ")
                if (bundle.isDocExample && isSuspend) append("suspend ")
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
    }

    private fun renderLeafContinuationIntrinsicFunction(
        fnFrame: PacketFrame,
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
        leaf: LeafContinuationIntrinsic,
        qualifierPrefix: String,
        indent: String,
    ): String {
        includes.needsSuspendBoxHelpersDef = true
        includes.addProject("kotlinx/coroutines/Continuation.hpp")
        includes.addSystem("memory")
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
                    "template <typename $tp0 = kotlin::Unit, typename KxsBlock>\n"
                } else {
                    "template <${sym.typeParams.joinToString(", ") { "typename ${it.name}" }}, typename KxsBlock>\n"
                }
            val defTps = "template <${sym.typeParams.joinToString(", ") { "typename ${it.name}" }}, typename KxsBlock>\n"
            val blockParamName = sym.valueParams.lastOrNull()?.cppName ?: "block"
            val isReusable = sym.kotlinName == "suspendCancellableCoroutineReusable"
            val boxedAuthoringRet = "std::conditional_t<std::is_void_v<$tp0>, kotlin::Unit, $tp0>"

            val declHeader =
                buildString {
                    append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                    append(indent).append(declTps)
                    append(indent).append("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]\n")
                    append(indent)
                        .append(boxedAuthoringRet)
                        .append(' ')
                        .append(sym.cppName)
                        .append("(KxsBlock&& ")
                        .append(blockParamName)
                        .append(");\n\n")
                    append(indent).append(declTps)
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                    append(indent)
                        .append("void* ")
                        .append(sym.cppName)
                        .append("(KxsBlock&& ")
                        .append(blockParamName)
                        .append(", std::shared_ptr<kotlin::coroutines::Continuation<void*>> completion);\n\n")
                    append(indent).append(declTps)
                    append(indent)
                        .append("void* ")
                        .append(sym.cppName)
                        .append("(KxsBlock&& ")
                        .append(blockParamName)
                        .append(", kotlin::coroutines::Continuation<void*>* completion);\n")
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
                            "kotlin::Unit> || std::is_void_v<$tp0>) {\n",
                    )
                    append("                    kxs_intercepted->resume_with(kotlin::coroutines::Result<void*>::success(new kotlin::Unit{}));\n")
                    append("                } else {\n")
                    append("                    kxs_intercepted->resume_with(kotlin::coroutines::Result<void*>::success(new ").append(tp0).append("(r.get_or_throw())));\n")
                    append("                }\n")
                    append("            });\n")
                    append("    }\n")
                    append(setupBlock).append('\n')
                    append("    auto kxs_res = cancellable->get_result();\n")
                    append("    return kxs_box_suspend_result<").append(boxedAuthoringRet).append(">(std::move(kxs_res));\n")
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
                        .append("), kxs_wrap_raw_continuation(completion));\n")
                    append("}\n")
                }

            postIncludeHeaderBodies.add(postDefShared)
            postIncludeHeaderBodies.add(postDefRaw)
            return declHeader
        }

        val liveFlags = fnFrame.flags
        val isVoidReturn =
            sym.returnTypeFrameIndex == null ||
                authoringRetType == "void" ||
                authoringRetType == "Unit" ||
                authoringRetType == "kotlin::Unit" ||
                authoringRetType == "kotlinx::coroutines::Unit"
        val mentionsTp =
            sym.typeParams.any { containsWholeWord(authoringRetType, it.name) } ||
                enclosingClass?.typeParams?.any { containsWholeWord(authoringRetType, it.name) } == true
        val suspendBoxType: String?
        val suspendAuthoringRetType: String
        val hasBoxedResult: Boolean
        if (authoringRetType == "void*") {
            suspendBoxType = null
            suspendAuthoringRetType = "void*"
            hasBoxedResult = false
        } else if (mentionsTp) {
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            val safeBox = "std::conditional_t<std::is_void_v<$authoringRetType>, kotlin::Unit, $authoringRetType>"
            suspendBoxType = safeBox
            suspendAuthoringRetType = safeBox
            hasBoxedResult = true
        } else if (isVoidReturn) {
            suspendBoxType = null
            suspendAuthoringRetType = "void"
            hasBoxedResult = false
        } else {
            suspendBoxType = authoringRetType
            suspendAuthoringRetType = authoringRetType
            hasBoxedResult = true
        }
        val authoringAnn =
            if (hasBoxedResult) {
                "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]"
            } else {
                "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"
            }
        val contElemType =
            if (sym.returnTypeFrameIndex != null && leaf.isBoxedResult) {
                bundle.typeTranslator.translateTypeFrame(stream.frames[sym.returnTypeFrameIndex], CppTypePosition.TYPE_ARGUMENT)
            } else {
                "void*"
            }

        val isInterfaceMethod = enclosingClass?.kind == KtClassKind.INTERFACE
        val isOverride =
            sym.typeParams.isEmpty() &&
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE) &&
                enclosingClass != null &&
                enclosingClass.superTypeFrameIndices.isNotEmpty()
        val isStaticOnObject =
            (enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT) &&
                !isOverride
        val canBeVirtual = sym.typeParams.isEmpty() && authoringRetType != "auto"
        val isVirtualMethod =
            canBeVirtual &&
                (
                    isInterfaceMethod ||
                        PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
                        PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)
                )

        fun appendCompletionParam(
            base: String,
            completionUnused: Boolean,
        ): String {
            val contDecl =
                if (completionUnused) {
                    "[[maybe_unused]] std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
                } else {
                    "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
                }
            return if (base.isEmpty()) contDecl else "$base, $contDecl"
        }

        val adapterLines: List<String> =
            if (contElemType == "void*") {
                emptyList()
            } else {
                includes.addProject("kotlinx/coroutines/Continuation.hpp")
                includes.addProject("kotlinx/coroutines/Result.hpp")
                includes.addProject("kotlinx/coroutines/Unit.hpp")
                includes.addSystem("memory")
                includes.addSystem("type_traits")
                val safeBoxElem = "std::conditional_t<std::is_void_v<$contElemType>, kotlin::Unit, $contElemType>"
                listOf(
                    "auto kxs_typed_ucont = std::make_shared<kotlin::coroutines::FunctionalContinuation<$contElemType>>(",
                    "    _kxs_completion ? _kxs_completion->get_context() : nullptr,",
                    "    [_kxs_completion](kotlin::coroutines::Result<$contElemType> r) {",
                    "        if (!_kxs_completion) return;",
                    "        if (r.is_failure()) {",
                    "            _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::failure(r.exception_or_null()));",
                    "        } else if constexpr (std::is_same_v<$contElemType, kotlin::Unit> || std::is_void_v<$contElemType>) {",
                    "            _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::success(new kotlin::Unit{}));",
                    "        } else {",
                    "            _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::success(new $safeBoxElem(r.get_or_throw())));",
                    "        }",
                    "    });",
                )
            }

        fun renderLeafBody(bodyIndent: String): String {
            val hasRecv = sym.receiverTypeFrameIndex != null
            val (extRecvCls, extRecvPtr, extRecvSimple) = resolveExtensionReceiverContext(sym)
            val emitter =
                CppExprAndStmtEmitter(
                    bundle = bundle,
                    activeTypeParams =
                        buildSet {
                            enclosingClass?.typeParams?.forEach { add(it.name) }
                            sym.typeParams.forEach { add(it.name) }
                        },
                    enclosingClass = enclosingClass,
                    enclosingFunction = sym,
                    receiverSubstitution = if (hasRecv) "self" else null,
                    extensionReceiverClass = extRecvCls,
                    extensionReceiverIsPointer = extRecvPtr,
                    extensionReceiverSimpleName = extRecvSimple,
                    isVoidReturnContext = isVoidReturn,
                    enclosingFunctionReturnType = authoringRetType.removePrefix("[[noreturn]] "),
                    unitReturnExprOverride = if (suspendBoxType == null) "nullptr" else null,
                    isSuspendBody = true,
                    suspendBoxType = suspendBoxType,
                )
            emitter.bindLocalName(leaf.uContParamKotlinName, if (contElemType == "void*") "_kxs_completion" else "kxs_typed_ucont")
            for (vp in sym.valueParams) emitter.bindValueParam(vp)
            val lambdaBody = stream.frames[leaf.lambdaBodyFrameIndex]
            return if (lambdaBody.nodeTypeId == KtNodeTypes.BLOCK_ID) {
                emitter.emitBlock(lambdaBody, indent = bodyIndent, isValueBlock = true, resultTargetVar = null, extraEntryLines = adapterLines)
            } else {
                emitter.emitBlockOrExprBody(
                    lambdaBody,
                    isExpressionBody = true,
                    isVoidReturn = false,
                    indent = bodyIndent,
                    extraEntryLines = adapterLines,
                )
            }
        }

        fun collectUnusedLeafParams(body: String): HashSet<String> =
            sym.valueParams
                .filter { vp -> !CppComments.containsWholeWordInCode(body, vp.cppName) }
                .mapTo(HashSet()) { it.cppName }
                .apply {
                    if (sym.receiverTypeFrameIndex != null && !CppComments.containsWholeWordInCode(body, "self")) {
                        add("self")
                    }
                }

        if (isHeaderMode) {
            val authoringParams = buildFunctionParameterList(sym, includeDefaults = true, isSuspend = true)
            val loweredBaseParams = buildFunctionParameterList(sym, includeDefaults = false, isSuspend = true)
            val lambdaBody = stream.frames[leaf.lambdaBodyFrameIndex]
            val deferToPostInclude =
                isTemplate &&
                    enclosingClass?.enclosingClassFrameIndex == null &&
                    bodyReferencesExpectOrDeferredBottomHeader(fnFrame)
            val deferHeaderBody =
                isTemplate &&
                    !deferToPostInclude &&
                    enclosingClass?.enclosingClassFrameIndex == null &&
                    bodyReferencesLaterTopLevelDecl(
                        bodyFrame = lambdaBody,
                        anchorDeclFrameIndex = enclosingClass?.frameIndex ?: fnFrame.frameIndex,
                        sym = sym,
                        enclosingClass = enclosingClass,
                    )
            return buildString {
                append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                append(renderTemplatePrefix(sym.typeParams, indent))
                append(indent).append(authoringAnn).append('\n')
                append(indent)
                if (isStaticOnObject) append("static ")
                append(suspendAuthoringRetType)
                    .append(' ')
                    .append(sym.cppName)
                    .append('(')
                    .append(authoringParams)
                    .append(");\n\n")

                if (isTemplate) {
                    if (deferToPostInclude || deferHeaderBody) {
                        append(renderTemplatePrefix(sym.typeParams, indent))
                        append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                        append(indent)
                        if (isStaticOnObject) append("static ")
                        append("void* ")
                            .append(sym.cppName)
                            .append('(')
                            .append(appendCompletionParam(loweredBaseParams, completionUnused = false))
                            .append(");\n")

                        val renderedBody = renderLeafBody("")
                        val unusedTemplateCppParams = collectUnusedLeafParams(renderedBody)
                        val templateDefBaseParams =
                            buildFunctionParameterList(
                                sym = sym,
                                includeDefaults = false,
                                unusedParamCppNames = unusedTemplateCppParams,
                                isSuspend = true,
                            )
                        val completionUnused = !CppComments.containsWholeWordInCode(renderedBody, "_kxs_completion")
                        val defParams =
                            appendCompletionParam(templateDefBaseParams, completionUnused = completionUnused)
                        val qualMethod =
                            if (enclosingClass == null) {
                                sym.cppName
                            } else {
                                val clsHead =
                                    if (enclosingClass.typeParams.isEmpty()) {
                                        enclosingClass.cppName
                                    } else {
                                        "${enclosingClass.cppName}<${enclosingClass.typeParams.joinToString(", ") { it.name }}>"
                                    }
                                "$clsHead::${sym.cppName}"
                            }
                        val defText =
                            buildString {
                                if (enclosingClass != null && enclosingClass.typeParams.isNotEmpty()) {
                                    append(renderTemplatePrefix(enclosingClass.typeParams, ""))
                                }
                                append(renderTemplatePrefix(sym.typeParams, ""))
                                append("[[clang::annotate(\"suspend\")]]\n")
                                append("void* ")
                                    .append(qualMethod)
                                    .append('(')
                                    .append(defParams)
                                    .append(") ")
                                    .append(renderedBody)
                                    .append('\n')
                            }
                        if (deferToPostInclude) {
                            postIncludeHeaderBodies.add(defText)
                        } else {
                            deferredHeaderBodies.add(defText)
                        }
                    } else {
                        val renderedBody = renderLeafBody(indent)
                        val unusedTemplateCppParams = collectUnusedLeafParams(renderedBody)
                        val templateDefBaseParams =
                            buildFunctionParameterList(
                                sym = sym,
                                includeDefaults = false,
                                unusedParamCppNames = unusedTemplateCppParams,
                                isSuspend = true,
                            )
                        val completionUnused = !CppComments.containsWholeWordInCode(renderedBody, "_kxs_completion")
                        append(renderTemplatePrefix(sym.typeParams, indent))
                        append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                        append(indent)
                        if (isStaticOnObject) append("static ")
                        append("void* ")
                            .append(sym.cppName)
                            .append('(')
                            .append(appendCompletionParam(templateDefBaseParams, completionUnused = completionUnused))
                            .append(") ")
                            .append(renderedBody)
                            .append('\n')
                    }
                } else {
                    append(renderTemplatePrefix(sym.typeParams, indent))
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                    append(indent)
                    if (isStaticOnObject) {
                        append("static ")
                    } else if (isVirtualMethod && !isOverride) {
                        append("virtual ")
                    }
                    append("void* ")
                        .append(sym.cppName)
                        .append('(')
                        .append(appendCompletionParam(loweredBaseParams, completionUnused = false))
                        .append(')')
                    if (isOverride && !isStaticOnObject) append(" override")
                    append(";\n")
                }
            }
        }

        if (isTemplate) return ""
        val renderedBody = renderLeafBody(indent)
        val unusedCppParams = collectUnusedLeafParams(renderedBody)
        val baseCppParams =
            buildFunctionParameterList(
                sym = sym,
                includeDefaults = false,
                unusedParamCppNames = unusedCppParams,
                isSuspend = true,
            )
        val completionUnused = !CppComments.containsWholeWordInCode(renderedBody, "_kxs_completion")
        val qualName = if (qualifierPrefix.isEmpty()) sym.cppName else "$qualifierPrefix::${sym.cppName}"
        return buildString {
            append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = false))
            append(indent).append("[[clang::annotate(\"suspend\")]]\n")
            append(indent)
                .append("void* ")
                .append(qualName)
                .append('(')
                .append(appendCompletionParam(baseCppParams, completionUnused = completionUnused))
                .append(") ")
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
        isSuspendBody: Boolean = false,
        suspendBoxType: String? = null,
        forceCancellationEqualsParam: Boolean = false,
        comparableOverrideParamElem: String? = null,
        wrapReturnInMakeSharedOf: String? = null,
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

        val bodyIdx =
            sym.bodyFrameIndex
                ?: return if (isSuspendBody) {
                    if (suspendBoxType == "kotlin::Unit") "{ return new kotlin::Unit{}; }" else "{ return nullptr; }"
                } else {
                    "{}"
                }
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
                enclosingFunction = sym,
                receiverSubstitution = if (hasRecv) "self" else null,
                extensionReceiverClass = extRecvCls,
                extensionReceiverIsPointer = extRecvPtr,
                extensionReceiverSimpleName = extRecvSimple,
                isVoidReturnContext = isVoidReturn,
                enclosingFunctionReturnType = fnRetType,
                isSuspendBody = isSuspendBody,
                suspendBoxType = suspendBoxType,
                wrapReturnInMakeSharedOf = wrapReturnInMakeSharedOf,
            )
        for ((pIdx, vp) in sym.valueParams.withIndex()) {
            if (comparableOverrideParamElem != null && sym.valueParams.size == 1 && pIdx == 0) {
                emitter.bindLocalName(vp.name, vp.cppName)
            } else {
                emitter.bindValueParam(
                    vp = vp,
                    forcePolyPointer = forceCancellationEqualsParam && sym.valueParams.size == 1 && pIdx == 0,
                )
            }
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
                            if (!bundle.isDocExample || simple !in CppTypeTranslator.VOID_PTR_STAR_PROJECTION_TYPES) 1 else 0
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

    private fun resolveFunctionParamRawType(
        sym: KtFunctionSymbol,
        pIdx: Int,
        vp: KtValueParamInfo,
        starPlan: StarProjectionPlan? = null,
        forceCancellationEqualsParam: Boolean = false,
        comparableOverrideParamElem: String? = null,
    ): String =
        when {
            comparableOverrideParamElem != null && sym.valueParams.size == 1 && pIdx == 0 ->
                "const $comparableOverrideParamElem&"
            forceCancellationEqualsParam && sym.valueParams.size == 1 && pIdx == 0 -> {
                includes.addSystem("exception")
                "const std::exception*"
            }
            else -> {
                val unstarredRawT =
                    vp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(
                            stream.frames[it],
                            CppTypePosition.PARAMETER,
                            stream.frames[vp.frameIndex].flags,
                        )
                    } ?: "auto"
                applyStarNamesToRenderedType(unstarredRawT, starPlan?.paramStarNamesByIdx?.get(pIdx))
            }
        }

    private fun wrapVarargParamType(
        rawT: String,
        isVararg: Boolean,
    ): String {
        if (!isVararg) return rawT
        includes.addSystem("vector")
        return if (!bundle.isDocExample) {
            includes.needsVarargDef = true
            "const KxsVararg<$rawT>&"
        } else {
            "const std::vector<$rawT>&"
        }
    }

    private fun rewriteSuspendFunctionSecondArg(
        cppType: String,
        bareParamTpNames: Set<String>,
    ): String {
        if (bareParamTpNames.isEmpty()) return cppType
        val token = "SuspendFunction<"
        val start = cppType.indexOf(token)
        if (start < 0) return cppType
        var depth = 1
        var commaIdx = -1
        var closeIdx = -1
        var i = start + token.length
        while (i < cppType.length) {
            when (cppType[i]) {
                '<' -> depth += 1
                '>' -> {
                    depth -= 1
                    if (depth == 0) {
                        closeIdx = i
                        break
                    }
                }
                ',' -> if (depth == 1 && commaIdx < 0) commaIdx = i
            }
            i += 1
        }
        if (commaIdx < 0 || closeIdx < 0) return cppType
        val secondArg = cppType.substring(commaIdx + 1, closeIdx).trim()
        if (secondArg !in bareParamTpNames) return cppType
        includes.addSystem("type_traits")
        val prefix = cppType.substring(0, commaIdx + 1)
        val suffix = cppType.substring(closeIdx)
        return "$prefix std::type_identity_t<$secondArg>$suffix"
    }

    private fun rewriteContinuationParamType(
        cppType: String,
        callableTpNames: Set<String>,
    ): String {
        if (callableTpNames.isEmpty()) return cppType
        for (tp in callableTpNames) {
            if (cppType == "std::shared_ptr<Continuation<$tp>>" ||
                cppType == "std::shared_ptr<kotlin::coroutines::Continuation<$tp>>" ||
                cppType == "std::shared_ptr<kotlinx::coroutines::Continuation<$tp>>"
            ) {
                includes.addSystem("type_traits")
                return "std::type_identity_t<$cppType>"
            }
        }
        return cppType
    }

    private fun computeDeductionSensitiveTpSets(
        sym: KtFunctionSymbol,
        rawRecvType: String?,
        rawParamTypes: List<String>,
    ): Pair<Set<String>, Set<String>> {
        if (bundle.isDocExample || sym.typeParams.isEmpty()) {
            return emptySet<String>() to emptySet()
        }
        val fnTpNames = sym.typeParams.mapTo(HashSet()) { it.name }
        val bareParamTpNames =
            rawParamTypes.filterTo(HashSet()) { it in fnTpNames }
        val callableTpNames = HashSet<String>()
        val callableSources = ArrayList<String>(rawParamTypes.size + 1)
        if (rawRecvType != null &&
            (rawRecvType.contains("SuspendFunction<") || rawRecvType.contains("std::function<"))
        ) {
            callableSources.add(rawRecvType)
        }
        for (pT in rawParamTypes) {
            if (pT.contains("SuspendFunction<") || pT.contains("std::function<")) {
                callableSources.add(pT)
            }
        }
        for (tp in fnTpNames) {
            if (callableSources.any { containsWholeWord(it, tp) }) {
                callableTpNames.add(tp)
            }
        }
        return bareParamTpNames to callableTpNames
    }

    private fun StringBuilder.appendCallableFunctionForwarderIfEligible(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
        renderedTypeParams: List<KtTypeParamInfo>,
        retType: String,
        methodName: String,
        indent: String,
        isPureOrInlineDecl: Boolean,
        starPlan: StarProjectionPlan?,
        collapsedVarianceTp: Pair<String, String>?,
        shadowTpRenames: Map<String, String>,
    ) {
        if (bundle.isDocExample ||
            enclosingClass != null ||
            sym.isOperator ||
            !isPureOrInlineDecl ||
            renderedTypeParams.size != 1 ||
            sym.receiverTypeFrameIndex != null ||
            sym.valueParams.isEmpty()
        ) {
            return
        }
        val tpName = renderedTypeParams[0].name
        val cleanRet = retType.removePrefix("[[noreturn]] ")
        if (cleanRet != tpName) return
        val lastIdx = sym.valueParams.lastIndex
        val rawParamTypes =
            sym.valueParams.mapIndexed { pIdx, vp ->
                resolveFunctionParamRawType(
                    sym = sym,
                    pIdx = pIdx,
                    vp = vp,
                    starPlan = starPlan,
                ).let { p ->
                    if (collapsedVarianceTp != null) {
                        replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                    } else {
                        p
                    }
                }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
            }
        val expectedFnType = "std::function<$tpName()>"
        if (rawParamTypes[lastIdx] != expectedFnType) return
        if ((0 until lastIdx).any { idx -> containsWholeWord(rawParamTypes[idx], tpName) }) return
        includes.addSystem("functional")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
        val nsMacro =
            bundle.symbols.namespaceSegments
                .ifEmpty { listOf("ROOT") }
                .joinToString("_") { it.uppercase() }
        val fnMacro = methodName.uppercase()
        val guardName = "KXS_FN_CALLABLE_FWD_${nsMacro}_${fnMacro}_${sym.valueParams.size}"
        val priorParamDecls =
            (0 until lastIdx).map { idx -> "${rawParamTypes[idx]} ${sym.valueParams[idx].cppName}" }
        val lastVp = sym.valueParams[lastIdx]
        val fwdParams = (priorParamDecls + "KxsCallable&& ${lastVp.cppName}").joinToString(", ")
        val priorCallArgs =
            (0 until lastIdx).map { idx ->
                val pT = rawParamTypes[idx]
                val pName = sym.valueParams[idx].cppName
                if (pT.endsWith("&") || pT in VALUE_PASS_CPP_TYPES) pName else "std::move($pName)"
            }
        val fnArg = "std::function<KxsRet()>(std::forward<KxsCallable>(${lastVp.cppName}))"
        val allCallArgs = (priorCallArgs + fnArg).joinToString(", ")
        append('\n')
        append("#ifndef ").append(guardName).append('\n')
        append("#define ").append(guardName).append('\n')
        append(indent).append("template <typename KxsCallable>\n")
        append(indent)
            .append("    requires (std::is_invocable_v<KxsCallable&> && ")
            .append("!std::is_same_v<std::remove_cvref_t<KxsCallable>, ")
            .append("std::function<std::invoke_result_t<KxsCallable&>()>>)\n")
        append(indent)
            .append("inline std::invoke_result_t<KxsCallable&> ")
            .append(methodName)
            .append('(')
            .append(fwdParams)
            .append(") {\n")
        append(indent)
            .append("    using KxsRet = std::invoke_result_t<KxsCallable&>;\n")
        append(indent)
            .append("    return ")
            .append(methodName)
            .append("<KxsRet>(")
            .append(allCallArgs)
            .append(");\n")
        append(indent).append("}\n")
        append("#endif\n")
    }

    private fun buildFunctionParameterList(
        sym: KtFunctionSymbol,
        includeDefaults: Boolean,
        starPlan: StarProjectionPlan? = null,
        unusedParamCppNames: Set<String> = emptySet(),
        isSuspend: Boolean = false,
        forceCancellationEqualsParam: Boolean = false,
        comparableOverrideParamElem: String? = null,
    ): String {
        val parts = ArrayList<String>(sym.valueParams.size + 1)
        val rawRType =
            sym.receiverTypeFrameIndex?.let { rIdx ->
                val translated =
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[rIdx],
                        CppTypePosition.EXTENSION_RECEIVER,
                    )
                applyStarNamesToRenderedType(translated, starPlan?.receiverStarNames)
            }
        val rawParamTypes =
            sym.valueParams.mapIndexed { pIdx, vp ->
                val rawT =
                    resolveFunctionParamRawType(
                        sym = sym,
                        pIdx = pIdx,
                        vp = vp,
                        starPlan = starPlan,
                        forceCancellationEqualsParam = forceCancellationEqualsParam,
                        comparableOverrideParamElem = comparableOverrideParamElem,
                    )
                wrapVarargParamType(rawT, vp.isVararg)
            }
        val (bareParamTpNames, callableTpNames) =
            computeDeductionSensitiveTpSets(sym, rawRType, rawParamTypes)
        if (rawRType != null) {
            val rType = rewriteSuspendFunctionSecondArg(rawRType, bareParamTpNames)
            val passType =
                formatExtensionReceiverPassType(
                    rType = rType,
                    mutatesVectorReceiver = extensionFunctionMutatesVectorReceiver(sym, rType),
                )
            val extRecvAttr = if (isSuspend) "[[clang::annotate(\"kotlin.ir.ExtensionReceiver\")]] " else ""
            val unusedSelfAttr = if ("self" in unusedParamCppNames) "[[maybe_unused]] " else ""
            parts.add("$extRecvAttr$unusedSelfAttr$passType self")
        }
        val emitter = CppExprAndStmtEmitter(bundle, inDefaultParameterExpr = true)
        val firstTrailingDefaultIdx = computeFirstTrailingDefaultIndex(sym)
        for ((pIdx, vp) in sym.valueParams.withIndex()) {
            val paramT =
                rawParamTypes[pIdx]
                    .let { rewriteSuspendFunctionSecondArg(it, bareParamTpNames) }
                    .let { rewriteContinuationParamType(it, callableTpNames) }
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
        isDualAbiSuspend: Boolean = false,
        suspendAuthoringRetType: String = retType,
        hasBoxedResult: Boolean = false,
        isEqualsOverrideOnCancellationExc: Boolean = false,
        comparableOverrideParamElem: String? = null,
        scopeSeenNormalizedSigs: MutableSet<String> = HashSet(),
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
        val rawRType =
            sym.receiverTypeFrameIndex?.let { rIdx ->
                val translated =
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[rIdx],
                        CppTypePosition.EXTENSION_RECEIVER,
                    )
                applyStarNamesToRenderedType(translated, starPlan?.receiverStarNames)
            }
        fun resolveParamRawType(pIdx: Int, vp: KtValueParamInfo): String =
            resolveFunctionParamRawType(
                sym = sym,
                pIdx = pIdx,
                vp = vp,
                starPlan = starPlan,
                forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                comparableOverrideParamElem = comparableOverrideParamElem,
            )
        val rawParamTypes =
            sym.valueParams.mapIndexed { pIdx, vp ->
                wrapVarargParamType(resolveParamRawType(pIdx, vp), vp.isVararg)
            }
        val (bareParamTpNames, callableTpNames) =
            computeDeductionSensitiveTpSets(sym, rawRType, rawParamTypes)
        val receiverPassType: String? =
            rawRType?.let { baseRType ->
                val rType = rewriteSuspendFunctionSecondArg(baseRType, bareParamTpNames)
                if (rType in VALUE_PASS_CPP_TYPES || rType.endsWith("&") || rType.endsWith("*")) {
                    rType
                } else if (isMutableExtensionReceiverCppType(rType)) {
                    "$rType&"
                } else {
                    "const $rType&"
                }
            }

        val primaryNormTypes = ArrayList<String>(sym.valueParams.size + 1)
        if (receiverPassType != null) primaryNormTypes.add(receiverPassType)
        for (pRawT in rawParamTypes) {
            val rewritten =
                pRawT
                    .let { rewriteSuspendFunctionSecondArg(it, bareParamTpNames) }
                    .let { rewriteContinuationParamType(it, callableTpNames) }
            primaryNormTypes.add(rewritten)
        }
        val primaryKey = computeNormalizedOverloadKey(methodName, primaryNormTypes, isConstMethod)
        scopeSeenNormalizedSigs.add(primaryKey)
        val seenNormalizedSigs = HashSet<String>(scopeSeenNormalizedSigs)

        val authoringAnn =
            if (hasBoxedResult) {
                "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]"
            } else {
                "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"
            }

        return buildString {
            for (keepPrefixCount in startDefaultInclusive until endDefaultExclusive) {
                val sigParts = ArrayList<String>()
                val authoringSigParts = ArrayList<String>()
                val loweredSigParts = ArrayList<String>()
                val normTypes = ArrayList<String>()
                val callArgs = ArrayList<String>()
                if (receiverPassType != null) {
                    sigParts.add("$receiverPassType self")
                    authoringSigParts.add("[[clang::annotate(\"kotlin.ir.ExtensionReceiver\")]] $receiverPassType self")
                    loweredSigParts.add("[[clang::annotate(\"kotlin.ir.ExtensionReceiver\")]] $receiverPassType self")
                    normTypes.add(receiverPassType)
                    callArgs.add("self")
                }
                val (extRecvCls, extRecvPtr, extRecvSimple) = resolveExtensionReceiverContext(sym)
                val emitter =
                    CppExprAndStmtEmitter(
                        bundle = bundle,
                        enclosingClass = enclosingClass,
                        enclosingFunction = sym,
                        receiverSubstitution = if (sym.receiverTypeFrameIndex != null) "self" else null,
                        extensionReceiverClass = extRecvCls,
                        extensionReceiverIsPointer = extRecvPtr,
                        extensionReceiverSimpleName = extRecvSimple,
                        inDefaultParameterExpr = true,
                    )
                for ((pIdx, vp) in sym.valueParams.withIndex()) {
                    val rawT = resolveParamRawType(pIdx, vp)
                    if (pIdx in keepPrefixCount until endDefaultExclusive) {
                        val defFrame = stream.frames[vp.defaultExprFrameIndex!!]
                        val rawDef = emitter.emitExpression(defFrame)
                        callArgs.add(if (rawDef == "nullptr" && rawT.startsWith("std::optional<")) "std::nullopt" else rawDef)
                    } else {
                        val paramT =
                            rawParamTypes[pIdx]
                                .let { rewriteSuspendFunctionSecondArg(it, bareParamTpNames) }
                                .let { rewriteContinuationParamType(it, callableTpNames) }
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
                        authoringSigParts.add("$paramT ${vp.cppName}$defSuffix")
                        loweredSigParts.add("$paramT ${vp.cppName}")
                        normTypes.add(paramT)
                        includes.addSystem("utility")
                        val fwdArg = if (paramT.endsWith("&") || paramT in VALUE_PASS_CPP_TYPES) vp.cppName else "std::move(${vp.cppName})"
                        callArgs.add(fwdArg)
                        emitter.bindValueParam(vp)
                    }
                }
                val normSig = computeNormalizedOverloadKey(methodName, normTypes, isConstMethod)
                if (!seenNormalizedSigs.add(normSig)) continue
                scopeSeenNormalizedSigs.add(normSig)

                val isNoreturn = retType.startsWith("[[noreturn]] ")
                val cleanRetType = retType.removePrefix("[[noreturn]] ")
                val tArgs = if (effectiveTypeParams.isNotEmpty()) "<${effectiveTypeParams.joinToString(", ") { it.name }}>" else ""
                if (isDualAbiSuspend) {
                    includes.addSystem("memory")
                    includes.addSystem("utility")
                    includes.addProject("kotlinx/coroutines/Continuation.hpp")
                    val cleanAuthRet = suspendAuthoringRetType.removePrefix("[[noreturn]] ")
                    append('\n')
                    append(renderTemplatePrefix(effectiveTypeParams, indent))
                    append(indent).append(authoringAnn).append('\n')
                    append(indent)
                    if (isNoreturn) append("[[noreturn]] ")
                    if (isStaticOnObject) append("static ")
                    append(cleanAuthRet)
                        .append(' ')
                        .append(methodName)
                        .append('(')
                        .append(authoringSigParts.joinToString(", "))
                        .append(')')
                    if (isConstMethod) append(" const")
                    append(";\n")

                    val loweredParams =
                        (loweredSigParts + "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion")
                            .joinToString(", ")
                    val loweredCallArgs = (callArgs + "std::move(_kxs_completion)").joinToString(", ")
                    append('\n')
                    append(renderTemplatePrefix(effectiveTypeParams, indent))
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                    append(indent)
                    if (isStaticOnObject) {
                        append("static ")
                    } else if (enclosingClass == null && effectiveTypeParams.isEmpty()) {
                        append("inline ")
                    }
                    append("void* ")
                        .append(methodName)
                        .append('(')
                        .append(loweredParams)
                        .append(')')
                    if (isConstMethod) append(" const")
                    append(" { return ")
                        .append(methodName)
                        .append(tArgs)
                        .append('(')
                        .append(loweredCallArgs)
                        .append("); }\n")
                } else {
                    append('\n')
                    append(renderTemplatePrefix(effectiveTypeParams, indent))
                    append(indent)
                    if (isNoreturn) append("[[noreturn]] ")
                    if (isStaticOnObject) {
                        append("static ")
                    } else if (enclosingClass == null && effectiveTypeParams.isEmpty()) {
                        append("inline ")
                    }
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
    }

    private fun renderVarargInitializerListOverload(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
        retType: String,
        isVoidReturn: Boolean,
        methodName: String,
        isConstMethod: Boolean,
        isDualAbiSuspend: Boolean,
        suspendAuthoringRetType: String,
        hasBoxedResult: Boolean,
        indent: String,
        effectiveTypeParams: List<KtTypeParamInfo> = sym.typeParams,
        starPlan: StarProjectionPlan? = null,
    ): String {
        val varargIdx = sym.valueParams.indexOfFirst { it.isVararg }
        if (varargIdx < 0 || sym.valueParams.count { it.isVararg } != 1) return ""
        val firstTrailingDefaultIdx = computeFirstTrailingDefaultIndex(sym)
        if (firstTrailingDefaultIdx <= varargIdx) return ""

        includes.addSystem("initializer_list")
        includes.addSystem("utility")
        includes.addSystem("vector")

        val isStaticOnObject =
            enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT
        val sigParts = ArrayList<String>(sym.valueParams.size + 1)
        val authoringSigParts = ArrayList<String>(sym.valueParams.size + 1)
        val callArgs = ArrayList<String>(sym.valueParams.size + 1)

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
            authoringSigParts.add("[[clang::annotate(\"kotlin.ir.ExtensionReceiver\")]] $passType self")
            callArgs.add("self")
        }

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
            if (pIdx == varargIdx) {
                val declPart = "std::initializer_list<$rawT> ${vp.cppName}"
                sigParts.add(declPart)
                authoringSigParts.add(declPart)
                callArgs.add("std::vector<$rawT>(${vp.cppName})")
            } else {
                val declPart = "$rawT ${vp.cppName}"
                sigParts.add(declPart)
                authoringSigParts.add(declPart)
                val fwdArg = if (rawT.endsWith("&") || rawT in VALUE_PASS_CPP_TYPES) vp.cppName else "std::move(${vp.cppName})"
                callArgs.add(fwdArg)
            }
        }

        val isNoreturn = retType.startsWith("[[noreturn]] ")
        val cleanRetType = retType.removePrefix("[[noreturn]] ")
        val tArgs = if (effectiveTypeParams.isNotEmpty()) "<${effectiveTypeParams.joinToString(", ") { it.name }}>" else ""

        return buildString {
            if (isDualAbiSuspend) {
                includes.addSystem("memory")
                includes.addProject("kotlinx/coroutines/Continuation.hpp")
                val authoringAnn =
                    if (hasBoxedResult) {
                        "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]"
                    } else {
                        "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"
                    }
                val cleanAuthRet = suspendAuthoringRetType.removePrefix("[[noreturn]] ")
                append('\n')
                append(renderTemplatePrefix(effectiveTypeParams, indent))
                append(indent).append(authoringAnn).append('\n')
                append(indent)
                if (isNoreturn) append("[[noreturn]] ")
                if (isStaticOnObject) append("static ")
                append(cleanAuthRet)
                    .append(' ')
                    .append(methodName)
                    .append('(')
                    .append(authoringSigParts.joinToString(", "))
                    .append(')')
                if (isConstMethod) append(" const")
                append(";\n")

                val loweredParams =
                    (authoringSigParts + "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion")
                        .joinToString(", ")
                val loweredCallArgs = (callArgs + "std::move(_kxs_completion)").joinToString(", ")
                append('\n')
                append(renderTemplatePrefix(effectiveTypeParams, indent))
                append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                append(indent)
                if (isStaticOnObject) {
                    append("static ")
                } else if (enclosingClass == null && effectiveTypeParams.isEmpty()) {
                    append("inline ")
                }
                append("void* ")
                    .append(methodName)
                    .append('(')
                    .append(loweredParams)
                    .append(')')
                if (isConstMethod) append(" const")
                append(" { return ")
                    .append(methodName)
                    .append(tArgs)
                    .append('(')
                    .append(loweredCallArgs)
                    .append("); }\n")
            } else {
                append('\n')
                append(renderTemplatePrefix(effectiveTypeParams, indent))
                append(indent)
                if (isNoreturn) append("[[noreturn]] ")
                if (isStaticOnObject) {
                    append("static ")
                } else if (enclosingClass == null && effectiveTypeParams.isEmpty()) {
                    append("inline ")
                }
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

    private fun shouldOmitInstanceFieldConst(
        cls: KtClassSymbol,
        kotlinPropName: String,
        hasInlineInitializer: Boolean,
    ): Boolean {
        if (!hasInlineInitializer) return true
        if (cls.isInner) return true
        if (cls.kind == KtClassKind.DATA_CLASS || cls.kind == KtClassKind.VALUE_CLASS) return true
        val declFrame = stream.frames[cls.frameIndex]
        val classBody = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.CLASS_BODY_ID)
        if (classBody != null && FrameAstHelpers.childFramesOfType(stream, classBody, KtNodeTypes.SECONDARY_CONSTRUCTOR_ID).isNotEmpty()) {
            return true
        }
        if (isCancellationExceptionSubclass(cls)) return true
        if (cls.superTypeFrameIndices.any { stIdx ->
                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[stIdx]) == "CopyableThrowable"
            }
        ) {
            return true
        }
        return cls.initBlockFrameIndices.any { initIdx ->
            val f = stream.frames[initIdx]
            (f.firstPacketIndex..f.lastPacketIndex).any { pi ->
                val pkt = stream.packets[pi]
                pkt is SyntaxPacket.IdentPacket && pkt.unescapedName == kotlinPropName
            }
        }
    }

    private fun replaceWholeIdentifierOutsideStrings(
        text: String,
        renames: Map<String, String>,
    ): String {
        if (renames.isEmpty() || text.isEmpty()) return text
        val n = text.length
        val out = StringBuilder(n)
        var i = 0
        while (i < n) {
            val ch = text[i]
            if (ch == '/' && i + 1 < n && text[i + 1] == '/') {
                val nl = text.indexOf('\n', i + 2)
                val end = if (nl < 0) n else nl + 1
                out.append(text, i, end)
                i = end
                continue
            }
            if (ch == '/' && i + 1 < n && text[i + 1] == '*') {
                val close = text.indexOf("*/", i + 2)
                val end = if (close < 0) n else close + 2
                out.append(text, i, end)
                i = end
                continue
            }
            if (ch == 'R' &&
                i + 1 < n &&
                text[i + 1] == '"' &&
                (i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_'))
            ) {
                val parenOpen = text.indexOf('(', i + 2)
                if (parenOpen >= 0) {
                    val delim = text.substring(i + 2, parenOpen)
                    val endMarker = ")$delim\""
                    val close = text.indexOf(endMarker, parenOpen + 1)
                    val end = if (close < 0) n else close + endMarker.length
                    out.append(text, i, end)
                    i = end
                    continue
                }
            }
            if (ch == '"' || ch == '\'') {
                val quote = ch
                var j = i + 1
                while (j < n) {
                    val qc = text[j]
                    if (qc == '\\' && j + 1 < n) {
                        j += 2
                    } else if (qc == quote) {
                        j += 1
                        break
                    } else {
                        j += 1
                    }
                }
                out.append(text, i, j)
                i = j
                continue
            }
            if (ch.isLetter() || ch == '_') {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) {
                    j += 1
                }
                val ident = text.substring(i, j)
                val prevIsMemberOp =
                    (i >= 1 && text[i - 1] == '.') ||
                        (i >= 2 && text[i - 1] == '>' && text[i - 2] == '-') ||
                        (i >= 2 && text[i - 1] == ':' && text[i - 2] == ':')
                val repl = if (!prevIsMemberOp) renames[ident] else null
                out.append(repl ?: ident)
                i = j
                continue
            }
            out.append(ch)
            i += 1
        }
        return out.toString()
    }

    private fun ensureNoreturnUnreachable(
        body: String,
        isNoreturn: Boolean,
        indent: String,
    ): String {
        if (!isNoreturn || !body.endsWith("}")) return body
        val trimmedEnd = body.dropLast(1).trimEnd()
        if (trimmedEnd.endsWith("__builtin_unreachable();") ||
            trimmedEnd.endsWith("std::unreachable();")
        ) {
            return body
        }
        return when {
            trimmedEnd == "{" -> "{ __builtin_unreachable(); }"
            !body.contains('\n') -> "$trimmedEnd __builtin_unreachable(); }"
            else -> "$trimmedEnd\n$indent    __builtin_unreachable();\n$indent}"
        }
    }

    private fun extractSuperTypeArgList(superTypeFrame: PacketFrame): List<String> {
        val userType =
            if (superTypeFrame.nodeTypeId == KtNodeTypes.USER_TYPE_ID) {
                superTypeFrame
            } else {
                FrameAstHelpers.firstChildFrame(stream, superTypeFrame, KtNodeTypes.USER_TYPE_ID)
            } ?: return emptyList()
        val argList =
            FrameAstHelpers.firstChildFrame(stream, userType, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
                ?: return emptyList()
        return FrameAstHelpers
            .childFramesOfType(stream, argList, KtNodeTypes.TYPE_PROJECTION_ID)
            .map { proj ->
                bundle.typeTranslator.translateTypeFrame(proj, CppTypePosition.TYPE_ARGUMENT)
            }
    }

    private fun buildSuperTypeArgMap(
        superCls: KtClassSymbol,
        superTypeFrame: PacketFrame,
    ): Map<String, String> {
        if (superCls.typeParams.isEmpty()) return emptyMap()
        val args = extractSuperTypeArgList(superTypeFrame)
        if (args.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        val limit = minOf(superCls.typeParams.size, args.size)
        for (i in 0 until limit) {
            map[superCls.typeParams[i].name] = args[i]
        }
        return map
    }

    private fun resolveSuperMethodReturnType(
        cls: KtClassSymbol,
        sym: KtFunctionSymbol,
        allowVoid: Boolean,
        visited: MutableSet<Int> = HashSet(),
    ): String? {
        if (!visited.add(cls.frameIndex)) return null
        for (stIdx in cls.superTypeFrameIndices) {
            val superTypeFrame = stream.frames[stIdx]
            val simple =
                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, superTypeFrame) ?: continue
            val sameSuper = symbols.classesByName[simple]
            if (sameSuper != null) {
                val argMap = buildSuperTypeArgMap(sameSuper, superTypeFrame)
                val matchingFn =
                    sameSuper.memberFunctionFrameIndices
                        .asSequence()
                        .mapNotNull { symbols.functionsByFrame[it] }
                        .firstOrNull { mFn ->
                            mFn.kotlinName == sym.kotlinName &&
                                mFn.valueParams.size == sym.valueParams.size
                        }
                if (matchingFn != null) {
                    val rawRet =
                        when {
                            matchingFn.returnTypeFrameIndex != null ->
                                bundle.typeTranslator.translateTypeFrame(
                                    stream.frames[matchingFn.returnTypeFrameIndex],
                                    CppTypePosition.RETURN,
                                )
                            matchingFn.isExpressionBody && matchingFn.bodyFrameIndex != null ->
                                inferFunctionExpressionBodyReturnType(
                                    matchingFn,
                                    sameSuper,
                                    stream.frames[matchingFn.bodyFrameIndex],
                                )
                            else -> "void"
                        }.removePrefix("[[noreturn]] ")
                    val subst = replaceWholeIdentifierOutsideStrings(rawRet, argMap)
                    if (subst != "auto" && (allowVoid || subst != "void")) {
                        return subst
                    }
                }
                val deeper = resolveSuperMethodReturnType(sameSuper, sym, allowVoid, visited)
                if (deeper != null) {
                    return replaceWholeIdentifierOutsideStrings(deeper, argMap)
                }
                continue
            }
            val superArgs = extractSuperTypeArgList(superTypeFrame)
            val externalRet =
                when {
                    (simple == "LockFreeLinkedListNode" || simple == "LockFreeLinkedListHead") &&
                        sym.kotlinName == "remove" &&
                        sym.valueParams.isEmpty() -> "bool"
                    simple == "AbstractSharedFlow" &&
                        sym.kotlinName == "createSlot" &&
                        sym.valueParams.isEmpty() -> {
                        includes.addSystem("memory")
                        val slotT = superArgs.firstOrNull() ?: "void"
                        "std::shared_ptr<$slotT>"
                    }
                    simple == "AbstractSharedFlow" &&
                        sym.kotlinName == "createSlotArray" &&
                        sym.valueParams.size == 1 -> {
                        includes.addSystem("memory")
                        includes.addSystem("vector")
                        val slotT = superArgs.firstOrNull() ?: "void"
                        "std::vector<std::shared_ptr<$slotT>>"
                    }
                    simple == "FusibleFlow" &&
                        sym.kotlinName == "fuse" &&
                        sym.valueParams.size == 3 -> {
                        includes.addSystem("memory")
                        val elemT = superArgs.firstOrNull() ?: "std::any"
                        val flowCpp =
                            CppTypeTranslator.includeAndQualifyKnownType(
                                simpleName = "Flow",
                                cppBaseName = "Flow",
                                currentNs = symbols.namespaceSegments,
                                includes = includes,
                            ) ?: "Flow"
                        "std::shared_ptr<$flowCpp<$elemT>>"
                    }
                    (simple == "MutableSharedFlow" || simple == "MutableStateFlow") &&
                        sym.kotlinName == "resetReplayCache" &&
                        sym.valueParams.isEmpty() -> if (allowVoid) "void" else null
                    (simple == "MutableSharedFlow" || simple == "MutableStateFlow") &&
                        sym.kotlinName == "tryEmit" &&
                        sym.valueParams.size == 1 -> "bool"
                    simple == "DisposableHandle" &&
                        sym.kotlinName == "dispose" &&
                        sym.valueParams.isEmpty() -> if (allowVoid) "void" else null
                    (simple == "CompletableDeferred" || simple == "CompletableJob") &&
                        (
                            (sym.kotlinName == "completeExceptionally" && sym.valueParams.size == 1) ||
                                (simple == "CompletableDeferred" && sym.kotlinName == "complete" && sym.valueParams.size == 1) ||
                                (simple == "CompletableJob" && sym.kotlinName == "complete" && sym.valueParams.isEmpty())
                        ) -> "bool"
                    simple == "Continuation" &&
                        sym.kotlinName == "resumeWith" &&
                        sym.valueParams.size == 1 -> if (allowVoid) "void" else null
                    simple == "CancellableContinuation" &&
                        sym.kotlinName == "cancel" &&
                        sym.valueParams.size <= 1 -> "bool"
                    simple == "CancellableContinuation" &&
                        (
                            (sym.kotlinName == "resume" && sym.valueParams.size in 1..3) ||
                                (sym.kotlinName == "invokeOnCancellation" && sym.valueParams.size == 1) ||
                                (sym.kotlinName == "completeResume" && sym.valueParams.size == 1)
                        ) -> if (allowVoid) "void" else null
                    simple == "CoroutineDispatcher" &&
                        (sym.kotlinName == "dispatch" || sym.kotlinName == "dispatchYield") &&
                        sym.valueParams.size == 2 -> if (allowVoid) "void" else null
                    simple == "CoroutineDispatcher" &&
                        sym.kotlinName == "isDispatchNeeded" &&
                        sym.valueParams.size == 1 -> "bool"
                    simple == "Runnable" &&
                        sym.kotlinName == "run" &&
                        sym.valueParams.isEmpty() -> if (allowVoid) "void" else null
                    simple == "Segment" &&
                        sym.kotlinName == "numberOfSlots" &&
                        sym.valueParams.isEmpty() -> {
                        includes.addSystem("cstdint")
                        "std::int32_t"
                    }
                    simple == "Segment" &&
                        sym.kotlinName == "onCancellation" &&
                        sym.valueParams.size == 3 -> if (allowVoid) "void" else null
                    (simple == "CancelHandler" || simple == "CompletionHandler") &&
                        sym.kotlinName == "invoke" &&
                        sym.valueParams.size == 1 -> if (allowVoid) "void" else null
                    sym.kotlinName == "toString" &&
                        sym.valueParams.isEmpty() &&
                        simple in KNOWN_TO_STRING_BASE_CLASSES -> {
                        includes.addSystem("string")
                        "std::string"
                    }
                    else -> null
                }
            if (externalRet != null) return externalRet
        }
        return null
    }

    private fun shouldMarkMethodConst(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
    ): Boolean {
        if (enclosingClass == null) return false
        if ((sym.kotlinName == "toString" || sym.kotlinName == "hashCode") &&
            sym.valueParams.isEmpty()
        ) {
            val allowsSingletonConst =
                enclosingClass.kind != KtClassKind.OBJECT_SINGLETON ||
                    PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OVERRIDE)
            return enclosingClass.kind != KtClassKind.INTERFACE &&
                enclosingClass.kind != KtClassKind.COMPANION_OBJECT &&
                allowsSingletonConst
        }
        if (enclosingClass.isInner) return false
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

    private fun extractPropertyGetterBodyFrame(sym: KtPropertySymbol): PacketFrame? {
        val gfIdx = sym.getterFrameIndex ?: return null
        val accFrame = stream.frames[gfIdx]
        return FrameAstHelpers.firstChildFrame(stream, accFrame, KtNodeTypes.BLOCK_ID)
            ?: FrameAstHelpers.childFrames(stream, accFrame).lastOrNull { child ->
                child.nodeTypeId != KtNodeTypes.VALUE_PARAMETER_LIST_ID &&
                    child.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID &&
                    child.nodeTypeId != KtNodeTypes.TYPE_REFERENCE_ID
            }
    }

    private fun shouldMarkPropertyGetterConst(
        sym: KtPropertySymbol,
        enclosingClass: KtClassSymbol?,
        getterBody: PacketFrame?,
        visitedFrames: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (enclosingClass == null) return false
        if (PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OVERRIDE)) return true
        if (getterBody == null) return true
        if (!visitedFrames.add(sym.frameIndex)) return true
        if (mutatesMemberField(getterBody, enclosingClass)) return false
        if (callsNonConstEnclosingMember(getterBody, enclosingClass, visitedFrames)) return false
        return true
    }

    private fun callsNonConstEnclosingMember(
        frame: PacketFrame,
        cls: KtClassSymbol,
        visitedFrames: MutableSet<Int> = HashSet(),
    ): Boolean {
        fun enclosesNonConstMethod(name: String): Boolean =
            cls.memberFunctionFrameIndices.any { fIdx ->
                val fn = symbols.functionsByFrame[fIdx] ?: return@any false
                if (fn.kotlinName != name || !visitedFrames.add(fn.frameIndex)) return@any false
                val fnFlags = stream.frames[fIdx].flags
                if (PacketFlags.hasFlag(fnFlags, PacketFlags.MOD_INLINE) &&
                    !PacketFlags.hasFlag(fnFlags, PacketFlags.MOD_ABSTRACT) &&
                    fn.bodyFrameIndex != null
                ) {
                    val fnBody = stream.frames[fn.bodyFrameIndex]
                    mutatesMemberField(fnBody, cls) || callsNonConstEnclosingMember(fnBody, cls, visitedFrames)
                } else {
                    !shouldMarkMethodConst(fn, cls)
                }
            }

        fun enclosesNonConstProp(name: String): Boolean =
            cls.memberPropertyFrameIndices.any { pIdx ->
                val prop = symbols.propertiesByFrame[pIdx] ?: return@any false
                prop.kotlinName == name &&
                    !shouldMarkPropertyGetterConst(prop, cls, extractPropertyGetterBodyFrame(prop), visitedFrames)
            }

        return when (frame.nodeTypeId) {
            KtNodeTypes.CLASS_ID,
            KtNodeTypes.OBJECT_DECLARATION_ID,
            KtNodeTypes.OBJECT_LITERAL_ID,
            KtNodeTypes.VALUE_ARGUMENT_NAME_ID,
            KtNodeTypes.TYPE_REFERENCE_ID,
            KtNodeTypes.TYPE_ARGUMENT_LIST_ID,
            KtNodeTypes.USER_TYPE_ID,
            -> false
            KtNodeTypes.CALL_EXPRESSION_ID -> {
                val children = FrameAstHelpers.childFrames(stream, frame)
                val callee = children.firstOrNull()
                if (callee?.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                    val ktName = FrameAstHelpers.directIdentPacket(stream, callee)?.unescapedName
                    if (ktName != null && enclosesNonConstMethod(ktName)) return true
                }
                children.any { callsNonConstEnclosingMember(it, cls, visitedFrames) }
            }
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID,
            -> {
                val parts = FrameAstHelpers.childFrames(stream, frame)
                val recv = parts.getOrNull(0) ?: return false
                val sel = parts.getOrNull(1)
                if (recv.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID && sel != null) {
                    when (sel.nodeTypeId) {
                        KtNodeTypes.CALL_EXPRESSION_ID -> {
                            val callee = FrameAstHelpers.childFrames(stream, sel).firstOrNull()
                            val ktName =
                                if (callee?.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                                    FrameAstHelpers.directIdentPacket(stream, callee)?.unescapedName
                                } else {
                                    null
                                }
                            if (ktName != null && enclosesNonConstMethod(ktName)) return true
                            return FrameAstHelpers.childFrames(stream, sel).drop(1).any {
                                callsNonConstEnclosingMember(it, cls, visitedFrames)
                            }
                        }
                        KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                            val ktName = FrameAstHelpers.directIdentPacket(stream, sel)?.unescapedName
                            if (ktName != null && enclosesNonConstProp(ktName)) return true
                            return false
                        }
                    }
                }
                if (callsNonConstEnclosingMember(recv, cls, visitedFrames)) return true
                if (sel?.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                    FrameAstHelpers.childFrames(stream, sel).drop(1).any {
                        callsNonConstEnclosingMember(it, cls, visitedFrames)
                    }
                } else {
                    false
                }
            }
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val ktName = FrameAstHelpers.directIdentPacket(stream, frame)?.unescapedName ?: return false
                enclosesNonConstProp(ktName)
            }
            else ->
                FrameAstHelpers.childFrames(stream, frame).any {
                    callsNonConstEnclosingMember(it, cls, visitedFrames)
                }
        }
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
            val matchesKnownExtMethod =
                when (simple) {
                    "DisposableHandle" -> kotlinMethodName == "dispose"
                    "CompletableDeferred", "CompletableJob" ->
                        kotlinMethodName == "complete" || kotlinMethodName == "completeExceptionally"
                    "Continuation" -> kotlinMethodName == "resumeWith"
                    "CancellableContinuation" ->
                        kotlinMethodName in setOf("resume", "invokeOnCancellation", "completeResume", "cancel")
                    "CoroutineDispatcher" ->
                        kotlinMethodName in setOf("dispatch", "dispatchYield", "isDispatchNeeded")
                    "Runnable" -> kotlinMethodName == "run"
                    "Segment" -> kotlinMethodName == "numberOfSlots" || kotlinMethodName == "onCancellation"
                    "CancelHandler", "CompletionHandler" -> kotlinMethodName == "invoke"
                    else -> false
                }
            if (matchesKnownExtMethod) {
                return true
            }
        }
        return false
    }

    private fun inferFunctionExpressionBodyReturnType(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
        exprFrame: PacketFrame,
    ): String {
        if (sym.kotlinName == "toString" && sym.valueParams.isEmpty()) {
            includes.addSystem("string")
            return "std::string"
        }
        if (enclosingClass != null && PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OVERRIDE)) {
            resolveSuperMethodReturnType(enclosingClass, sym, allowVoid = true)?.let { return it }
        }
        val recvTypeCpp = resolveReceiverTypeForReturnInference(sym.receiverTypeFrameIndex, enclosingClass)
        val inferred = inferExpressionBodyReturnType(exprFrame, enclosingClass, recvTypeCpp)
        if (inferred != "auto") return inferred
        return "auto"
    }

    private fun inferExpressionBodyReturnType(
        exprFrame: PacketFrame,
        enclosingClass: KtClassSymbol? = null,
        receiverTypeCpp: String? = null,
        visitedPropNames: Set<String> = emptySet(),
    ): String =
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
            KtNodeTypes.THIS_EXPRESSION_ID -> receiverTypeCpp ?: "auto"
            KtNodeTypes.PREFIX_EXPRESSION_ID -> {
                val opRef = FrameAstHelpers.firstChildFrame(stream, exprFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
                val opId = opRef?.let { FrameAstHelpers.operationTokenId(stream, it) }
                val operand =
                    FrameAstHelpers
                        .childFrames(stream, exprFrame)
                        .firstOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
                when {
                    opId == KtTokens.EXCL_ID -> "bool"
                    (opId == KtTokens.MINUS_ID || opId == KtTokens.PLUS_ID) && operand != null ->
                        inferExpressionBodyReturnType(operand, enclosingClass, receiverTypeCpp, visitedPropNames)
                    else -> "auto"
                }
            }
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val refName = FrameAstHelpers.directIdentPacket(stream, exprFrame)?.unescapedName
                when {
                    refName == "Unit" -> "void"
                    refName != null && refName !in visitedPropNames -> {
                        val targetProp =
                            enclosingClass
                                ?.memberPropertyFrameIndices
                                ?.asSequence()
                                ?.mapNotNull { symbols.propertiesByFrame[it] }
                                ?.firstOrNull { it.kotlinName == refName && it.receiverTypeFrameIndex == null }
                                ?: symbols.propertiesByName[refName]?.takeIf {
                                    it.enclosingClassFrameIndex == null && it.receiverTypeFrameIndex == null
                                }
                        if (targetProp != null) {
                            if (targetProp.returnTypeFrameIndex != null) {
                                bundle.typeTranslator.translateTypeFrame(
                                    stream.frames[targetProp.returnTypeFrameIndex],
                                    CppTypePosition.RETURN,
                                )
                            } else {
                                val propExprFrame =
                                    targetProp.initializerFrameIndex?.let { stream.frames[it] }
                                        ?: targetProp.getterFrameIndex?.let { gIdx ->
                                            val gf = stream.frames[gIdx]
                                            FrameAstHelpers.firstChildFrame(stream, gf, KtNodeTypes.BLOCK_ID)
                                                ?: FrameAstHelpers.extractInitializerAfterEquals(stream, gf)
                                        }
                                if (propExprFrame != null) {
                                    val ownerCls = targetProp.enclosingClassFrameIndex?.let { symbols.classesByFrame[it] }
                                    val ownerRecvCpp = resolveReceiverTypeForReturnInference(null, ownerCls)
                                    inferExpressionBodyReturnType(
                                        propExprFrame,
                                        ownerCls,
                                        ownerRecvCpp,
                                        visitedPropNames + refName,
                                    )
                                } else {
                                    "auto"
                                }
                            }
                        } else {
                            "auto"
                        }
                    }
                    else -> "auto"
                }
            }
            KtNodeTypes.CALL_EXPRESSION_ID -> inferCallExpressionBodyReturnType(exprFrame)
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                val parts = FrameAstHelpers.childFrames(stream, exprFrame)
                val sel = parts.getOrNull(1)
                if (sel != null) {
                    val selCallName =
                        if (sel.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                            val c = FrameAstHelpers.childFrames(stream, sel).firstOrNull()
                            c?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
                        } else {
                            FrameAstHelpers.directIdentPacket(stream, sel)?.unescapedName
                        }
                    when (selCallName) {
                        "apply", "also" ->
                            inferExpressionBodyReturnType(parts[0], enclosingClass, receiverTypeCpp, visitedPropNames)
                        "current", "start" -> {
                            val recvIdent = FrameAstHelpers.directIdentPacket(stream, parts[0])?.unescapedName
                            val effRecvIdent = recvIdent?.let { symbols.importAliases[it] ?: it }
                            if (effRecvIdent == "Worker") {
                                CppTypeTranslator.includeAndQualifyKnownType(
                                    simpleName = "Worker",
                                    cppBaseName = "Worker",
                                    currentNs = symbols.namespaceSegments,
                                    includes = includes,
                                ) ?: "kotlin::native::concurrent::Worker"
                            } else {
                                "auto"
                            }
                        }
                        "createCoroutineUnintercepted" -> {
                            includes.addSystem("memory")
                            includes.addProject("kotlinx/coroutines/Continuation.hpp")
                            includes.addProject("kotlinx/coroutines/Unit.hpp")
                            "std::shared_ptr<kotlin::coroutines::Continuation<kotlin::Unit>>"
                        }
                        "toString", "joinToString" -> {
                            includes.addSystem("string")
                            "std::string"
                        }
                        "isEmpty", "isNotEmpty", "contains", "any", "all", "none", "equals" -> "bool"
                        "size", "length", "hashCode", "compareTo" -> {
                            includes.addSystem("cstdint")
                            "std::int32_t"
                        }
                        else -> "auto"
                    }
                } else {
                    "auto"
                }
            }
            KtNodeTypes.PARENTHESIZED_ID -> {
                val inner = FrameAstHelpers.childFrames(stream, exprFrame).firstOrNull()
                if (inner != null) {
                    inferExpressionBodyReturnType(inner, enclosingClass, receiverTypeCpp, visitedPropNames)
                } else {
                    "auto"
                }
            }
            KtNodeTypes.IF_ID -> {
                val thenExpr =
                    FrameAstHelpers
                        .firstChildFrame(stream, exprFrame, KtNodeTypes.THEN_ID)
                        ?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val elseExpr =
                    FrameAstHelpers
                        .firstChildFrame(stream, exprFrame, KtNodeTypes.ELSE_ID)
                        ?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val t1 =
                    thenExpr?.let {
                        inferExpressionBodyReturnType(it, enclosingClass, receiverTypeCpp, visitedPropNames)
                    } ?: "auto"
                val t2 =
                    elseExpr?.let {
                        inferExpressionBodyReturnType(it, enclosingClass, receiverTypeCpp, visitedPropNames)
                    } ?: "auto"
                if (t1 != "auto" && t1 == t2) t1 else "auto"
            }
            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val ch = FrameAstHelpers.childFrames(stream, exprFrame)
                val opId = if (ch.size >= 3) FrameAstHelpers.operationTokenId(stream, ch[1]) else null
                val opIdent =
                    if (ch.size >= 3 && opId == KtTokens.IDENTIFIER_ID) {
                        FrameAstHelpers.directIdentPacket(stream, ch[1])?.unescapedName
                    } else {
                        null
                    }
                when {
                    opId in ASSIGN_OPERATOR_IDS -> "void"
                    opId in BOOL_BINARY_OPERATOR_IDS -> "bool"
                    opId == KtTokens.PLUS_ID &&
                        ch.size >= 3 &&
                        (
                            inferExpressionBodyReturnType(ch[0], enclosingClass, receiverTypeCpp, visitedPropNames) == "std::string" ||
                                inferExpressionBodyReturnType(ch[2], enclosingClass, receiverTypeCpp, visitedPropNames) == "std::string"
                        ) -> {
                        includes.addSystem("string")
                        "std::string"
                    }
                    ch.size >= 3 &&
                        (opId in ARITHMETIC_BINARY_OPERATOR_IDS || opIdent in BITWISE_INFIX_NAMES) -> {
                        val lT = inferExpressionBodyReturnType(ch[0], enclosingClass, receiverTypeCpp, visitedPropNames)
                        val rT = inferExpressionBodyReturnType(ch[2], enclosingClass, receiverTypeCpp, visitedPropNames)
                        when {
                            opIdent in setOf("shl", "shr", "ushr") ->
                                if (lT == "std::int64_t" || lT == "std::int32_t") lT else "auto"
                            lT == "std::int64_t" || rT == "std::int64_t" -> {
                                includes.addSystem("cstdint")
                                "std::int64_t"
                            }
                            lT == "std::int32_t" && rT == "std::int32_t" -> {
                                includes.addSystem("cstdint")
                                "std::int32_t"
                            }
                            else -> "auto"
                        }
                    }
                    else -> "auto"
                }
            }
            else -> "auto"
        }

    private fun inferCallExpressionBodyReturnType(exprFrame: PacketFrame): String {
        val callee = FrameAstHelpers.childFrames(stream, exprFrame).firstOrNull()
        val ident = callee?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
        val effIdent = ident?.let { symbols.importAliases[it] ?: it }
        val ta = FrameAstHelpers.firstChildFrame(stream, exprFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
        val projs =
            ta?.let { FrameAstHelpers.childFramesOfType(stream, it, KtNodeTypes.TYPE_PROJECTION_ID) }.orEmpty()
        return when {
            ident == "synchronized" -> "auto"
            ident == "require" || ident == "check" || ident == "assert" -> "void"
            ident == "StringBuilder" -> {
                includes.needsStringBuilderDef = true
                "KxsStringBuilder"
            }
            effIdent == "newFixedThreadPoolContext" || effIdent == "newSingleThreadContext" -> {
                includes.addSystem("memory")
                val qual =
                    CppTypeTranslator.includeAndQualifyKnownType(
                        simpleName = "CloseableCoroutineDispatcher",
                        cppBaseName = "CloseableCoroutineDispatcher",
                        currentNs = symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "CloseableCoroutineDispatcher"
                "std::shared_ptr<$qual>"
            }
            effIdent == "OnDemandAllocatingPool" -> {
                val elemT =
                    if (projs.size == 1) {
                        bundle.typeTranslator.translateTypeFrame(projs[0], CppTypePosition.TYPE_ARGUMENT)
                    } else if (frameMentionsIdentifier(exprFrame, "Worker")) {
                        CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = "Worker",
                            cppBaseName = "Worker",
                            currentNs = symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "kotlin::native::concurrent::Worker"
                    } else {
                        "auto"
                    }
                if (elemT != "auto") {
                    val qual =
                        CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = "OnDemandAllocatingPool",
                            cppBaseName = "OnDemandAllocatingPool",
                            currentNs = symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "OnDemandAllocatingPool"
                    "$qual<$elemT>"
                } else {
                    "auto"
                }
            }
            ident == "SynchronizedObject" || ident == "ReentrantLock" -> {
                includes.needsSynchronizedObjectDef = true
                "KxsSynchronizedObject"
            }
            ident == "ArrayList" || ident == "mutableListOf" || ident == "listOf" ->
                if (projs.size == 1) {
                    includes.addSystem("vector")
                    val elemT = bundle.typeTranslator.translateTypeFrame(projs[0], CppTypePosition.TYPE_ARGUMENT)
                    "std::vector<$elemT>"
                } else {
                    "auto"
                }
            ident == "ArrayDeque" ->
                if (projs.size == 1) {
                    includes.needsArrayDequeDef = true
                    val elemT = bundle.typeTranslator.translateTypeFrame(projs[0], CppTypePosition.TYPE_ARGUMENT)
                    "KxsArrayDeque<$elemT>"
                } else {
                    "auto"
                }
            ident == "HashMap" || ident == "mutableMapOf" || ident == "LinkedHashMap" ->
                if (projs.size == 2) {
                    includes.addSystem("unordered_map")
                    val kT = bundle.typeTranslator.translateTypeFrame(projs[0], CppTypePosition.TYPE_ARGUMENT)
                    val vT = bundle.typeTranslator.translateTypeFrame(projs[1], CppTypePosition.TYPE_ARGUMENT)
                    "std::unordered_map<$kT, $vT>"
                } else {
                    "auto"
                }
            ident == "HashSet" || ident == "mutableSetOf" || ident == "LinkedHashSet" ->
                if (projs.size == 1) {
                    includes.addSystem("unordered_set")
                    val elemT = bundle.typeTranslator.translateTypeFrame(projs[0], CppTypePosition.TYPE_ARGUMENT)
                    "std::unordered_set<$elemT>"
                } else {
                    "auto"
                }
            effIdent == "Channel" ->
                if (projs.size == 1) {
                    includes.addSystem("memory")
                    val elemT = bundle.typeTranslator.translateTypeFrame(projs[0], CppTypePosition.TYPE_ARGUMENT)
                    val channelCpp =
                        CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = "Channel",
                            cppBaseName = "Channel",
                            currentNs = symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "Channel"
                    "std::shared_ptr<$channelCpp<$elemT>>"
                } else {
                    "auto"
                }
            ident == "arrayOfNulls" ->
                if (projs.isNotEmpty()) {
                    includes.addSystem("vector")
                    val elemT = bundle.typeTranslator.translateTypeFrame(projs[0], CppTypePosition.TYPE_ARGUMENT)
                    "std::vector<$elemT>"
                } else {
                    "auto"
                }
            else -> {
                val cls = effIdent?.let { symbols.classesByName[it] } ?: ident?.let { symbols.classesByName[it] }
                if (cls != null && cls.typeParams.size == projs.size) {
                    val tSuffix =
                        if (projs.isEmpty()) {
                            ""
                        } else {
                            val args =
                                projs.joinToString(", ") {
                                    bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT)
                                }
                            "<$args>"
                        }
                    val isShared =
                        cls.kotlinName in symbols.sharedHierarchyTypeNames ||
                            CppTypeTranslator.isSharedHierarchyType(cls.kotlinName)
                    if (isShared) {
                        includes.addSystem("memory")
                        "std::shared_ptr<${cls.cppName}$tSuffix>"
                    } else {
                        "${cls.cppName}$tSuffix"
                    }
                } else if (cls == null &&
                    effIdent != null &&
                    effIdent.firstOrNull()?.isUpperCase() == true &&
                    projs.isNotEmpty() &&
                    (
                        CppTypeTranslator.isSharedHierarchyType(effIdent) ||
                            CppTypeTranslator.lookupKnownHeaderForSymbol(effIdent) != null
                    )
                ) {
                    val qualName =
                        CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = effIdent,
                            cppBaseName = CppNamingPolicy.toTypeName(effIdent),
                            currentNs = symbols.namespaceSegments,
                            includes = includes,
                        ) ?: CppNamingPolicy.toTypeName(effIdent)
                    val tArgs =
                        projs.joinToString(", ") {
                            bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT)
                        }
                    if (CppTypeTranslator.isSharedHierarchyType(effIdent)) {
                        includes.addSystem("memory")
                        "std::shared_ptr<$qualName<$tArgs>>"
                    } else {
                        "$qualName<$tArgs>"
                    }
                } else {
                    "auto"
                }
            }
        }
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
        if (cls.kotlinName == "TestResult" || cls.kotlinName == "StackTraceElement") return false
        val declFrame = stream.frames[cls.frameIndex]
        if (!FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID)) return false
        if (cls.kind == KtClassKind.ANNOTATION_CLASS) return false
        return !isUsedAsSuperTypeInSameFile(cls) && !isReferencedByNonExpectInSameFile(cls)
    }

    private fun shouldEmitPrivateClassOnlyInCpp(cls: KtClassSymbol): Boolean =
        shouldEmitPrivateClassOnlyInCppRec(cls, HashSet())

    private fun shouldEmitPrivateClassOnlyInCppRec(
        cls: KtClassSymbol,
        inStack: MutableSet<Int>,
    ): Boolean {
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
        if (!inStack.add(cls.frameIndex)) return true
        try {
            for (top in symbols.topLevelDeclarationFrames) {
                if (top.frameIndex == cls.frameIndex) continue
                val emittedInHeader =
                    when (top.nodeTypeId) {
                        KtNodeTypes.CLASS_ID,
                        KtNodeTypes.OBJECT_DECLARATION_ID,
                        -> {
                            val otherCls = symbols.classesByFrame[top.frameIndex]
                            otherCls != null && !shouldEmitPrivateClassOnlyInCppRec(otherCls, inStack)
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
        } finally {
            inStack.remove(cls.frameIndex)
        }
    }

    internal fun renderPrivateClassDefinitionForCpp(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
    ): String =
        when (cls.kind) {
            KtClassKind.VALUE_CLASS -> renderValueClass(declFrame, cls, "", "")
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
            if (cls.kotlinName == "TestResult") {
                includes.addProject("kotlinx/coroutines/Unit.hpp")
                return "${prefix}${indent}using TestResult = kotlin::Unit;\n"
            }
            if (cls.kotlinName == "StackTraceElement") {
                includes.addSystem("any")
                return "${prefix}${indent}using StackTraceElement = std::any;\n"
            }
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
                renderValueClass(declFrame, cls, qualifierPrefix, indent, outOfLineOwnerQual)
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
                    val selfUnused =
                        if (CppComments.containsWholeWordInCode(bodyStr, "self")) "" else "[[maybe_unused]] "
                    append(indent)
                        .append("inline ")
                        .append(retT)
                        .append(' ')
                        .append(propSym.cppName)
                        .append('(')
                        .append(selfUnused)
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
                val fnEmitter =
                    CppExprAndStmtEmitter(
                        bundle = bundle,
                        enclosingClass = cls,
                        enclosingFunction = fnSym,
                        receiverSubstitution = "self",
                    )
                for (vp in fnSym.valueParams) fnEmitter.bindLocalName(vp.name, vp.cppName)
                val fnBodyStr =
                    fnEmitter.emitBlockOrExprBody(
                        stream.frames[bodyIdx],
                        fnSym.isExpressionBody,
                        retT == "void",
                        indent,
                    )
                val unusedFnParams =
                    fnSym.valueParams
                        .filter { vp -> !CppComments.containsWholeWordInCode(fnBodyStr, vp.cppName) }
                        .mapTo(HashSet()) { it.cppName }
                val selfUnused =
                    if (CppComments.containsWholeWordInCode(fnBodyStr, "self")) "" else "[[maybe_unused]] "
                val extraParams =
                    buildFunctionParameterList(
                        sym = fnSym,
                        includeDefaults = true,
                        unusedParamCppNames = unusedFnParams,
                    )
                val fullParams =
                    if (extraParams.isEmpty()) {
                        "$selfUnused${cls.cppName} self"
                    } else {
                        "$selfUnused${cls.cppName} self, $extraParams"
                    }
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
                    .append(fnBodyStr)
                    .append('\n')
            }
        }
    }

    private fun renderClassOutOfLineCompanionAndMembers(
        cls: KtClassSymbol,
        fullQual: String,
        indent: String,
        scopeSigs: MutableSet<String>,
        out: StringBuilder,
    ) {
        fun appendNonBlank(sub: String) {
            if (sub.isNotBlank()) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(sub)
            }
        }
        for (nestedIdx in cls.memberClassFrameIndices) {
            val nestedSym = symbols.classesByFrame[nestedIdx]
            val nestedQual =
                if (nestedSym != null && (cls.kotlinName to nestedSym.kotlinName) in symbols.nonInnerNestedRewrites) {
                    ""
                } else {
                    fullQual
                }
            appendNonBlank(renderClassOrObject(stream.frames[nestedIdx], nestedQual, indent))
        }
        cls.companionFrameIndex?.let { compIdx ->
            val compSym = symbols.classesByFrame[compIdx]
            if (compSym != null) {
                if (compSym.superTypeFrameIndices.isNotEmpty()) {
                    appendNonBlank(renderClassOrObject(stream.frames[compIdx], fullQual, indent))
                } else {
                    for (cpIdx in compSym.memberPropertyFrameIndices) {
                        appendNonBlank(renderProperty(stream.frames[cpIdx], compSym, indent))
                    }
                    for (cfIdx in compSym.memberFunctionFrameIndices) {
                        appendNonBlank(renderFunction(stream.frames[cfIdx], compSym, fullQual, indent, scopeSigs))
                    }
                }
            }
        }
        for (mPropIdx in cls.memberPropertyFrameIndices) {
            appendNonBlank(renderProperty(stream.frames[mPropIdx], cls, indent))
        }
        for (mFnIdx in cls.memberFunctionFrameIndices) {
            appendNonBlank(renderFunction(stream.frames[mFnIdx], cls, fullQual, indent, scopeSigs))
        }
    }

    private fun renderClassCompanionPropsAndFnsInHeader(
        cls: KtClassSymbol,
        fullQual: String,
        inner: String,
        scopeSigs: MutableSet<String>,
        out: StringBuilder,
        allowSuperCompanion: Boolean = false,
    ) {
        val compIdx = cls.companionFrameIndex ?: return
        val compSym = symbols.classesByFrame[compIdx] ?: return
        if (compSym.superTypeFrameIndices.isEmpty()) {
            val compName = compSym.cppName.ifEmpty { "Companion" }
            val dottedCompNames =
                setOf(
                    "${cls.kotlinName}.${compSym.kotlinName}",
                    "${cls.kotlinName}.Companion",
                )
            val hasCompanionExt =
                symbols.functionsByFrame.values.any { fn ->
                    fn.receiverTypeFrameIndex?.let { rIdx ->
                        FrameAstHelpers.dottedNameText(stream, stream.frames[rIdx]) in dottedCompNames
                    } == true
                } ||
                    symbols.propertiesByFrame.values.any { prop ->
                        prop.receiverTypeFrameIndex?.let { rIdx ->
                            FrameAstHelpers.dottedNameText(stream, stream.frames[rIdx]) in dottedCompNames
                        } == true
                    }
            if (hasCompanionExt) {
                out
                    .append('\n')
                    .append(inner)
                    .append("struct ")
                    .append(compName)
                    .append(" {};\n")
            }
            for (cpIdx in compSym.memberPropertyFrameIndices) {
                val rendered = renderProperty(stream.frames[cpIdx], compSym, inner)
                if (rendered.isNotBlank()) out.append('\n').append(rendered)
            }
            for (cfIdx in compSym.memberFunctionFrameIndices) {
                val rendered = renderFunction(stream.frames[cfIdx], compSym, fullQual, inner, scopeSigs)
                if (rendered.isNotBlank()) out.append('\n').append(rendered)
            }
        } else if (allowSuperCompanion) {
            val rendered = renderClassOrObject(stream.frames[compIdx], fullQual, inner)
            if (rendered.isNotBlank()) out.append('\n').append(rendered)
        }
    }

    private fun collectHoistedNonInnerNestedClasses(cls: KtClassSymbol): List<KtClassSymbol> {
        val raw =
            cls.memberClassFrameIndices
                .mapNotNull { symbols.classesByFrame[it] }
                .filter { (cls.kotlinName to it.kotlinName) in symbols.nonInnerNestedRewrites }
        if (raw.size <= 1) return raw
        val rawByName = raw.associateBy { it.kotlinName }
        val ordered = ArrayList<KtClassSymbol>(raw.size)
        val visited = HashSet<Int>()
        fun visit(c: KtClassSymbol) {
            if (!visited.add(c.frameIndex)) return
            for (stIdx in c.superTypeFrameIndices) {
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[stIdx]) ?: continue
                rawByName[simple]?.let { visit(it) }
            }
            ordered.add(c)
        }
        for (c in raw) visit(c)
        return ordered
    }

    private fun appendHoistedNestedDefinitions(
        cls: KtClassSymbol,
        indent: String,
        out: StringBuilder,
    ) {
        for (nested in collectHoistedNonInnerNestedClasses(cls)) {
            val rendered = renderClassOrObject(stream.frames[nested.frameIndex], "", indent)
            if (rendered.isNotBlank()) {
                out.append(rendered).append('\n')
            }
        }
    }

    private fun renderValueClass(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        qualifierPrefix: String,
        indent: String,
        outOfLineOwnerQual: String? = null,
    ): String {
        val fullQual = if (qualifierPrefix.isEmpty()) cls.cppName else "$qualifierPrefix::${cls.cppName}"
        val scopeSigs = collectClassScopeNormalizedSigs(cls)
        if (!isHeaderMode) {
            return buildString {
                renderClassOutOfLineCompanionAndMembers(cls, fullQual, indent, scopeSigs, this)
            }
        }
        val underlyingParam = cls.primaryConstructorParams.firstOrNull()
        val valType =
            underlyingParam?.typeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
            } ?: "std::int64_t"
        val propName = underlyingParam?.cppName ?: "value"
        val fieldName = CppNamingPolicy.toPrivateFieldName(propName)
        val headName = if (outOfLineOwnerQual != null) "$outOfLineOwnerQual::${cls.cppName}" else cls.cppName
        val constexprKw = if (valType in VALUE_PASS_CPP_TYPES) "constexpr " else ""
        val fieldIsTypeParamIndependent =
            cls.typeParams.size == 1 && cls.typeParams.none { tp -> containsWholeWord(valType, tp.name) }
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

        val inner = "$indent    "
        return buildString {
            if (outOfLineOwnerQual == null) {
                appendHoistedNestedDefinitions(cls, indent, this)
            }
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(cls.typeParams, indent))
            append(indent).append("class ").append(headName).append(" {\n")
            if (!bundle.isDocExample && cls.typeParams.isNotEmpty()) {
                append(inner)
                    .append("template <")
                    .append(cls.typeParams.joinToString(", ") { "typename" })
                    .append(">\n")
                append(inner).append("friend class ").append(cls.cppName).append(";\n")
            }
            append(indent).append("private:\n")
            append(inner)
                .append(valType)
                .append(' ')
                .append(fieldName)
                .append(";\n")
            append(indent).append("public:\n")
            append(inner)
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
            if (fieldIsTypeParamIndependent) {
                append(inner)
                    .append("template <typename KxsOtherT>\n")
                append(inner)
                    .append(constexprKw)
                    .append(cls.cppName)
                    .append("(const ")
                    .append(cls.cppName)
                    .append("<KxsOtherT>& other) : ")
                    .append(fieldName)
                    .append("(other.")
                    .append(fieldName)
                    .append(") {}\n")
            }
            append(inner)
                .append(constexprKw)
                .append("const ")
                .append(valType)
                .append("& ")
                .append(propName)
                .append("() const noexcept { return ")
                .append(fieldName)
                .append("; }\n")
            if (isDefaultComparableCppType(valType)) {
                append(inner)
                    .append(constexprKw)
                    .append("bool operator==(const ")
                    .append(cls.cppName)
                    .append("&) const = default;\n")
            } else {
                includes.addSystem("memory")
                append(inner)
                    .append("bool operator==(const ")
                    .append(cls.cppName)
                    .append("& other) const noexcept { return std::addressof(")
                    .append(fieldName)
                    .append(") == std::addressof(other.")
                    .append(fieldName)
                    .append("); }\n")
            }
            for (nestedIdx in cls.memberClassFrameIndices) {
                val nestedCls = symbols.classesByFrame[nestedIdx]
                if (nestedCls != null && (cls.kotlinName to nestedCls.kotlinName) in symbols.nonInnerNestedRewrites) {
                    append('\n')
                        .append(inner)
                        .append("using ")
                        .append(CppNamingPolicy.toTypeName(nestedCls.kotlinName))
                        .append(" = ")
                        .append(nestedCls.cppName)
                        .append(";\n")
                } else {
                    val rendered = renderClassOrObject(stream.frames[nestedIdx], fullQual, inner)
                    if (rendered.isNotBlank()) append('\n').append(rendered)
                }
            }
            renderClassCompanionPropsAndFnsInHeader(cls, fullQual, inner, scopeSigs, this, allowSuperCompanion = true)
            for (mPropIdx in cls.memberPropertyFrameIndices) {
                val rendered = renderProperty(stream.frames[mPropIdx], cls, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            for (mFnIdx in cls.memberFunctionFrameIndices) {
                val rendered = renderFunction(stream.frames[mFnIdx], cls, fullQual, inner, scopeSigs)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            val cmpHelpers = renderComparableClassHelpers(cls, inner)
            if (cmpHelpers.isNotBlank()) append('\n').append(cmpHelpers)
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
            if (outOfLineOwnerQual == null) {
                appendHoistedNestedDefinitions(cls, indent, this)
            }
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(cls.typeParams, indent))
            val restrictsAttr =
                if (PacketFlags.hasFlag(declFrame.flags, PacketFlags.RESTRICTS_SUSPENSION_SCOPE) && !bundle.isDocExample) {
                    "[[clang::annotate(\"kotlin.coroutines.RestrictsSuspension\")]] "
                } else {
                    ""
                }
            append(indent)
                .append("class ")
                .append(restrictsAttr)
                .append(headName)
                .append(inheritance)
                .append(" {\n")
            if (!bundle.isDocExample && cls.typeParams.isNotEmpty()) {
                append(inner)
                    .append("template <")
                    .append(cls.typeParams.joinToString(", ") { "typename" })
                    .append(">\n")
                append(inner).append("friend class ").append(cls.cppName).append(";\n")
            }
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
                val nestedCls = symbols.classesByFrame[nestedIdx]
                if (nestedCls != null && (cls.kotlinName to nestedCls.kotlinName) in symbols.nonInnerNestedRewrites) {
                    append('\n')
                        .append(inner)
                        .append("using ")
                        .append(CppNamingPolicy.toTypeName(nestedCls.kotlinName))
                        .append(" = ")
                        .append(nestedCls.cppName)
                        .append(";\n")
                } else {
                    val rendered = renderClassOrObject(stream.frames[nestedIdx], fullQual, inner)
                    if (rendered.isNotBlank()) append('\n').append(rendered)
                }
            }
            val scopeSigs = collectClassScopeNormalizedSigs(cls)
            renderClassCompanionPropsAndFnsInHeader(cls, fullQual, inner, scopeSigs, this)
            for (mPropIdx in cls.memberPropertyFrameIndices) {
                val rendered = renderProperty(stream.frames[mPropIdx], cls, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            for (mFnIdx in cls.memberFunctionFrameIndices) {
                val rendered = renderFunction(stream.frames[mFnIdx], cls, fullQual, inner, scopeSigs)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            val cmpHelpers = renderComparableClassHelpers(cls, inner)
            if (cmpHelpers.isNotBlank()) append('\n').append(cmpHelpers)
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
        if (p.isConst || hasCustomGetterBody(p)) return false
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
                        val outerUnused =
                            if (!CppComments.containsWholeWordInCode(initAndBodyText, "outer_value")) {
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
                        val paramName = "${cp.cppName}_value"
                        val unusedAttr =
                            if (!cp.isVal &&
                                !cp.isVar &&
                                !CppComments.containsWholeWordInCode(initAndBodyText, paramName)
                            ) {
                                "[[maybe_unused]] "
                            } else {
                                ""
                            }
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

                val scopeSigs = collectClassScopeNormalizedSigs(cls)
                renderClassOutOfLineCompanionAndMembers(cls, fullQual, indent, scopeSigs, this)
            }
        }

        val inheritance = renderInheritanceClause(cls)
        val delegatedMap = extractDelegatedMapTypes(cls)
        val inner = "$indent    "
        val isPolymorphicBase =
            cls.kind == KtClassKind.INTERFACE ||
                cls.kind == KtClassKind.SEALED_CLASS ||
                PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_OPEN) ||
                PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_ABSTRACT) ||
                isUsedAsSuperTypeInSameFile(cls) ||
                cls.kotlinName in symbols.sharedHierarchyTypeNames ||
                CppTypeTranslator.isSharedHierarchyType(cls.kotlinName) ||
                cls.memberFunctionFrameIndices.any { fnIdx ->
                    val flags = stream.frames[fnIdx].flags
                    PacketFlags.hasFlag(flags, PacketFlags.MOD_OPEN) ||
                        PacketFlags.hasFlag(flags, PacketFlags.MOD_ABSTRACT) ||
                        PacketFlags.hasFlag(flags, PacketFlags.MOD_OVERRIDE)
                } ||
                cls.memberPropertyFrameIndices.any { pIdx ->
                    val flags = stream.frames[pIdx].flags
                    PacketFlags.hasFlag(flags, PacketFlags.MOD_OPEN) ||
                        PacketFlags.hasFlag(flags, PacketFlags.MOD_ABSTRACT) ||
                        PacketFlags.hasFlag(flags, PacketFlags.MOD_OVERRIDE)
                }

        val isCancellableFlowMarker =
            cls.enclosingClassFrameIndex == null &&
                cls.kotlinName == "CancellableFlow" &&
                symbols.namespaceSegments == listOf("kotlinx", "coroutines", "flow")
        val isEventLoopImplPlatformGuard =
            !bundle.isDocExample &&
                cls.enclosingClassFrameIndex == null &&
                cls.kotlinName == "EventLoopImplPlatform"
        val isNotCompletedMarker =
            !bundle.isDocExample &&
                cls.enclosingClassFrameIndex == null &&
                cls.kotlinName == "NotCompleted" &&
                symbols.namespaceSegments == listOf("kotlinx", "coroutines")
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
            if (isNotCompletedMarker) {
                append("#ifndef KXS_NOT_COMPLETED_DEFINED\n")
                append("#define KXS_NOT_COMPLETED_DEFINED\n")
            }
            if (outOfLineOwnerQual == null) {
                appendHoistedNestedDefinitions(cls, indent, this)
            }
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(cls.typeParams, indent))
            val restrictsAttr =
                if (PacketFlags.hasFlag(declFrame.flags, PacketFlags.RESTRICTS_SUSPENSION_SCOPE) && !bundle.isDocExample) {
                    "[[clang::annotate(\"kotlin.coroutines.RestrictsSuspension\")]] "
                } else {
                    ""
                }
            append(indent)
                .append("class ")
                .append(restrictsAttr)
                .append(headName)
                .append(inheritance)
                .append(" {\n")
            if (!bundle.isDocExample && cls.typeParams.isNotEmpty()) {
                append(inner)
                    .append("template <")
                    .append(cls.typeParams.joinToString(", ") { "typename" })
                    .append(">\n")
                append(inner).append("friend class ").append(cls.cppName).append(";\n")
            }

            // Private fields first (`snake_case_`), deferring static object initializers that read public members
            val privateCtorProps =
                cls.primaryConstructorParams.filter { isPrivateOrBackedOverrideCtorParam(cls, it) }
            val privateMemberProps =
                cls.memberPropertyFrameIndices.filter { isPrivateOrBackedOverrideMemberProp(cls, it) }
            val deferredPrivateMemberProps = LinkedHashSet<Int>()
            if (cls.kind == KtClassKind.OBJECT_SINGLETON || cls.kind == KtClassKind.COMPANION_OBJECT) {
                val triggerNames =
                    cls.memberPropertyFrameIndices
                        .filter { it !in privateMemberProps }
                        .mapNotNull { symbols.propertiesByFrame[it]?.kotlinName }
                        .toMutableSet()
                var progressed = true
                while (progressed) {
                    progressed = false
                    for (pIdx in privateMemberProps) {
                        if (pIdx in deferredPrivateMemberProps) continue
                        val prop = symbols.propertiesByFrame[pIdx] ?: continue
                        val initF = prop.initializerFrameIndex?.let { stream.frames[it] } ?: continue
                        if (triggerNames.any { frameMentionsIdentifier(initF, it) }) {
                            deferredPrivateMemberProps.add(pIdx)
                            triggerNames.add(prop.kotlinName)
                            progressed = true
                        }
                    }
                }
            }
            if (cls.memberClassFrameIndices.isNotEmpty()) {
                val nestedClassKotlinNames =
                    cls.memberClassFrameIndices.mapNotNull { symbols.classesByFrame[it]?.kotlinName }
                for (pIdx in privateMemberProps) {
                    if (pIdx in deferredPrivateMemberProps) continue
                    val propFrame = stream.frames[pIdx]
                    if (nestedClassKotlinNames.any { nName -> frameMentionsIdentifier(propFrame, nName) }) {
                        deferredPrivateMemberProps.add(pIdx)
                    }
                }
            }
            val earlyPrivateMemberProps = privateMemberProps.filter { it !in deferredPrivateMemberProps }
            if (outerCls != null ||
                delegatedInterfaces.isNotEmpty() ||
                privateCtorProps.isNotEmpty() ||
                earlyPrivateMemberProps.isNotEmpty() ||
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
                    val constQual =
                        if (!cp.isVar &&
                            !shouldOmitInstanceFieldConst(cls, cp.name, hasInlineInitializer = true)
                        ) {
                            "const "
                        } else {
                            ""
                        }
                    append(inner)
                        .append(constQual)
                        .append(t)
                        .append(' ')
                        .append(CppNamingPolicy.toPrivateFieldName(cp.name))
                        .append(";\n")
                }
                for (pIdx in earlyPrivateMemberProps) {
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
                val constQual =
                    if (!cp.isVar &&
                        !shouldOmitInstanceFieldConst(cls, cp.name, hasInlineInitializer = true)
                    ) {
                        "const "
                    } else {
                        ""
                    }
                append(inner)
                    .append(constQual)
                    .append(t)
                    .append(' ')
                    .append(cp.cppName)
                    .append(";\n")
            }

            // Backed `override` virtual getters/setters for primary constructor params and member properties
            val isStdErrCls = hasStdRuntimeErrorBase(cls)
            for (cp in cls.primaryConstructorParams) {
                if (!isBackedOverrideCtorParam(cls, cp)) continue
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                    } ?: "std::any"
                val fieldName = CppNamingPolicy.toPrivateFieldName(cp.name)
                val getterName = resolveOverridePropertyGetterName(cls, cp.name, cp.cppName)
                val overrideKw =
                    if (isStdErrCls && (cp.name == "message" || cp.name == "cause")) "" else " override"
                append(inner)
                    .append(t)
                    .append(' ')
                    .append(getterName)
                    .append("() const")
                    .append(overrideKw)
                    .append(" { return ")
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
                        .append(" value)")
                        .append(overrideKw)
                        .append(" { ")
                        .append(fieldName)
                        .append(" = std::move(value); }\n")
                }
            }
            for (pIdx in cls.memberPropertyFrameIndices) {
                val prop = symbols.propertiesByFrame[pIdx] ?: continue
                val propFrame = stream.frames[pIdx]
                if (!isBackedOverrideMemberProp(cls, propFrame, prop)) continue
                val fieldName =
                    if (prop.cppName.startsWith("kxs_atomic_")) prop.cppName else CppNamingPolicy.toPrivateFieldName(prop.kotlinName)
                val af = bundle.atomicPlan.atomicFieldsByPropFrame[pIdx]
                val t =
                    if (af != null) {
                        resolveAtomicFieldRenderSpec(af, cls).elemType
                    } else {
                        prop.returnTypeFrameIndex?.let {
                            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                        } ?: prop.initializerFrameIndex?.let {
                            inferExpressionBodyReturnType(stream.frames[it]).takeIf { r -> r != "auto" }
                        } ?: "std::any"
                    }
                val getterName = resolveOverridePropertyGetterName(cls, prop.kotlinName, prop.cppName)
                val readExpr = if (af != null) "$fieldName.load(std::memory_order_seq_cst)" else fieldName
                val overrideKw =
                    if (isStdErrCls && (prop.kotlinName == "message" || prop.kotlinName == "cause")) "" else " override"
                append(inner)
                    .append(t)
                    .append(' ')
                    .append(getterName)
                    .append("() const")
                    .append(overrideKw)
                    .append(" { return ")
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
                        .append(" value)")
                        .append(overrideKw)
                        .append(" { ")
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
                val outOfLineCtorQual =
                    if (isHeaderMode && !emitCtorInCpp) resolveOutOfLineEnclosingQualification(cls) else null
                val ctorDepFrames =
                    if (outOfLineCtorQual != null) {
                        buildList {
                            cls.delegatedSuperTypeFrameIndices.forEach { add(stream.frames[it]) }
                            cls.superCallArgsBySuperIndex.values.forEach { add(stream.frames[it]) }
                            cls.initBlockFrameIndices.forEach { add(stream.frames[it]) }
                            cls.memberPropertyFrameIndices.forEach { pIdx ->
                                val pSym = symbols.propertiesByFrame[pIdx] ?: return@forEach
                                val initIdx = pSym.initializerFrameIndex ?: return@forEach
                                val initF = stream.frames[initIdx]
                                if (referencesNonFieldCtorParam(cls, initF)) add(initF)
                            }
                        }
                    } else {
                        emptyList()
                    }
                val deferCtorToPostInclude =
                    outOfLineCtorQual != null &&
                        ctorDepFrames.any { bodyReferencesExpectOrDeferredBottomHeader(it) }
                val anchorCtorIdx = outermostEnclosingClass(cls)?.frameIndex ?: cls.frameIndex
                val deferCtorToBottom =
                    !deferCtorToPostInclude &&
                        outOfLineCtorQual != null &&
                        ctorDepFrames.any { frameReferencesLaterTopLevelDecl(it, anchorCtorIdx, cls) }
                val deferOutOfLineCtor = deferCtorToPostInclude || deferCtorToBottom
                val inlineInitList =
                    if (!emitCtorInCpp) {
                        includes.addSystem("utility")
                        buildConstructorInitializerList(cls, suffix = "_value", delegatedInterfaces = delegatedInterfaces)
                    } else {
                        null
                    }
                val inlineCtorBody =
                    if (!emitCtorInCpp) {
                        val bodyIndent = if (deferOutOfLineCtor) "" else inner
                        renderPrimaryConstructorBody(cls, suffix = "_value", indent = bodyIndent)
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
                val defSigParts = ArrayList<String>(totalCtorParams)
                if (outerCls != null) {
                    val outerUnused =
                        if (inlineInitAndBodyText != null &&
                            !CppComments.containsWholeWordInCode(inlineInitAndBodyText, "outer_value")
                        ) {
                            "[[maybe_unused]] "
                        } else {
                            ""
                        }
                    sigParts.add(
                        if (deferOutOfLineCtor) {
                            "${outerCls.cppName}* outer_value"
                        } else {
                            "$outerUnused${outerCls.cppName}* outer_value"
                        },
                    )
                    defSigParts.add("$outerUnused${outerCls.cppName}* outer_value")
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
                        if (inlineInitAndBodyText != null &&
                            !cp.isVal &&
                            !cp.isVar &&
                            !CppComments.containsWholeWordInCode(inlineInitAndBodyText, paramName)
                        ) {
                            "[[maybe_unused]] "
                        } else {
                            ""
                        }
                    sigParts.add(
                        if (deferOutOfLineCtor) {
                            "$t $paramName$def"
                        } else {
                            "$unusedAttr$t $paramName$def"
                        },
                    )
                    defSigParts.add("$unusedAttr$t $paramName")
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
                } else if (deferOutOfLineCtor) {
                    append(";\n")
                    val ctorDef =
                        buildString {
                            val outerTps = outOfLineCtorQual.first
                            if (outerTps.isNotEmpty()) {
                                append(renderTemplatePrefix(outerTps, ""))
                            } else {
                                append("inline ")
                            }
                            append(outOfLineCtorQual.second)
                                .append("::")
                                .append(cls.cppName)
                                .append('(')
                                .append(defSigParts.joinToString(", "))
                                .append(')')
                            if (inlineInitList != null && inlineInitList.isNotEmpty()) {
                                append(" : ").append(inlineInitList.joinToString(", "))
                            }
                            append(' ').append(inlineCtorBody ?: "{}").append('\n')
                        }
                    if (deferCtorToPostInclude) {
                        postIncludeHeaderBodies.add(ctorDef)
                    } else {
                        deferredHeaderBodies.add(ctorDef)
                    }
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
            if (cls.memberClassFrameIndices.isNotEmpty()) {
                for ((pos, nestedIdx) in cls.memberClassFrameIndices.withIndex()) {
                    val siblingCls = symbols.classesByFrame[nestedIdx] ?: continue
                    if ((cls.kotlinName to siblingCls.kotlinName) in symbols.nonInnerNestedRewrites) continue
                    val referencedInCtor =
                        cls.primaryConstructorParams.any { cp ->
                            cp.typeFrameIndex?.let { frameMentionsIdentifier(stream.frames[it], siblingCls.kotlinName) } == true ||
                                cp.defaultExprFrameIndex?.let { frameMentionsIdentifier(stream.frames[it], siblingCls.kotlinName) } == true
                        }
                    val referencedInProps =
                        cls.memberPropertyFrameIndices.any { pIdx ->
                            frameMentionsIdentifier(stream.frames[pIdx], siblingCls.kotlinName)
                        }
                    val referencedInFns =
                        cls.memberFunctionFrameIndices.any { fIdx ->
                            frameMentionsIdentifier(stream.frames[fIdx], siblingCls.kotlinName)
                        }
                    val referencedInEarlierNested =
                        cls.memberClassFrameIndices.subList(0, pos).any { earlierIdx ->
                            frameMentionsIdentifier(stream.frames[earlierIdx], siblingCls.kotlinName)
                        }
                    val referencedBefore = referencedInCtor || referencedInProps || referencedInFns || referencedInEarlierNested
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
                if (nestedCls != null && (cls.kotlinName to nestedCls.kotlinName) in symbols.nonInnerNestedRewrites) {
                    append('\n')
                        .append(inner)
                        .append("using ")
                        .append(CppNamingPolicy.toTypeName(nestedCls.kotlinName))
                        .append(" = ")
                        .append(nestedCls.cppName)
                        .append(";\n")
                } else if (nestedCls != null && inheritsFromEnclosing(nestedCls, cls)) {
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

            val scopeSigs = collectClassScopeNormalizedSigs(cls)
            // Companion object static constants and factories lifted onto the enclosing class
            renderClassCompanionPropsAndFnsInHeader(cls, fullQual, inner, scopeSigs, this)

            // Public/non-private member properties
            for (pIdx in cls.memberPropertyFrameIndices) {
                if (pIdx in privateMemberProps) continue
                val rendered = renderProperty(stream.frames[pIdx], cls, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            if (deferredPrivateMemberProps.isNotEmpty()) {
                append('\n').append(indent).append("private:\n")
                for (pIdx in privateMemberProps) {
                    if (pIdx !in deferredPrivateMemberProps) continue
                    val rendered = renderProperty(stream.frames[pIdx], cls, inner)
                    if (rendered.isNotBlank()) append(rendered)
                }
                append('\n').append(indent).append("public:\n")
            }

            // Member functions
            for (mFnIdx in cls.memberFunctionFrameIndices) {
                val rendered = renderFunction(stream.frames[mFnIdx], cls, fullQual, inner, scopeSigs)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            val cmpHelpers = renderComparableClassHelpers(cls, inner)
            if (cmpHelpers.isNotBlank()) append('\n').append(cmpHelpers)

            // Disambiguate diamond virtual `key()` when inheriting both `AbstractCoroutineContextElement` and `ContinuationInterceptor`
            val superNames =
                cls.superTypeFrameIndices.mapNotNull {
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                }
            if ("AbstractCoroutineContextElement" in superNames && "ContinuationInterceptor" in superNames) {
                append('\n')
                    .append(inner)
                    .append("kotlin::coroutines::CoroutineContext::Key* key() const override { return AbstractCoroutineContextElement::key(); }\n")
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
                        includes.needsSuspendBoxHelpersDef = true
                        append('\n')
                            .append(inner)
                            .append("void* join(kotlin::coroutines::Continuation<void*>* completion) { ")
                            .append("return join(kxs_wrap_raw_continuation(completion)); }\n")
                    }
                    "Deferred" -> {
                        if (cls.typeParams.size == 1) {
                            includes.needsSuspendBoxHelpersDef = true
                            append('\n')
                                .append(inner)
                                .append("void* await(kotlin::coroutines::Continuation<void*>* completion) { ")
                                .append("return await(kxs_wrap_raw_continuation(completion)); }\n")
                        }
                    }
                }
            }

            append(indent).append("};\n")
            if (isCancellableFlowMarker || isEventLoopImplPlatformGuard || isNotCompletedMarker) {
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
            emitter.bindConstructorParam(cp, suffix)
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

    private fun outermostEnclosingClass(cls: KtClassSymbol?): KtClassSymbol? {
        var cur = cls ?: return null
        while (true) {
            val parentIdx = cur.enclosingClassFrameIndex ?: return cur
            cur = symbols.classesByFrame[parentIdx] ?: return cur
        }
    }

    private fun resolveOutOfLineEnclosingQualification(
        cls: KtClassSymbol?,
    ): Pair<List<KtTypeParamInfo>, String>? {
        if (cls == null) return Pair(emptyList(), "")
        val chain = ArrayList<KtClassSymbol>()
        var cur: KtClassSymbol? = cls
        while (cur != null) {
            chain.add(cur)
            cur = cur.enclosingClassFrameIndex?.let { symbols.classesByFrame[it] }
        }
        chain.reverse()
        val effective =
            chain.filter { c ->
                !(c.kind == KtClassKind.COMPANION_OBJECT && c.superTypeFrameIndices.isEmpty())
            }
        if (effective.isEmpty()) return null
        if (effective.size > 1 && effective.any { it.typeParams.isNotEmpty() }) return null
        val tps = effective.flatMap { it.typeParams }
        val qual =
            effective.joinToString("::") { c ->
                if (c.typeParams.isEmpty()) {
                    c.cppName
                } else {
                    "${c.cppName}<${c.typeParams.joinToString(", ") { it.name }}>"
                }
            }
        return Pair(tps, qual)
    }

    private fun frameReferencesLaterTopLevelDecl(
        bodyFrame: PacketFrame,
        anchorDeclFrameIndex: Int,
        enclosingClass: KtClassSymbol?,
        extraShadowed: Set<String> = emptySet(),
    ): Boolean {
        if (bodyFrame.firstPacketIndex > bodyFrame.lastPacketIndex) return false
        val shadowedNames = HashSet<String>(extraShadowed)
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

    private fun bodyReferencesLaterTopLevelDecl(
        bodyFrame: PacketFrame,
        anchorDeclFrameIndex: Int,
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
    ): Boolean {
        val extra = HashSet<String>()
        sym.valueParams.forEach { extra.add(it.name) }
        sym.typeParams.forEach { extra.add(it.name) }
        return frameReferencesLaterTopLevelDecl(bodyFrame, anchorDeclFrameIndex, enclosingClass, extra)
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
        if (prop.isConst || hasCustomGetterBody(prop)) return null
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
            val atomicSpec = resolveAtomicFieldRenderSpec(af, cls)
            val rawInitCode =
                if (atomicSpec.elemType == "void*") {
                    renderVoidPtrAtomicInit(initFrame, emitter)
                } else {
                    emitter.emitExpression(initFrame)
                }
            val initCode = if (atomicSpec.useAtomicRefHolder && rawInitCode == "nullptr") "{}" else rawInitCode
            return if (initCode == "{}") "$fieldCppName{}" else "$fieldCppName($initCode)"
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
            emitter.bindConstructorParam(cp, suffix)
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
                    val finalArgs =
                        if (baseName.endsWith("AbstractCoroutineContextElement") ||
                            baseName.contains("AbstractCoroutineContextKey")
                        ) {
                            args.map { arg ->
                                if (arg.endsWith("::Key::instance()")) {
                                    includes.addSystem("memory")
                                    "std::addressof($arg)"
                                } else {
                                    arg
                                }
                            }
                        } else {
                            args
                        }
                    inits.add("$baseName(${finalArgs.joinToString(", ")})")
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
            val outOfLineSecQual =
                if (isHeaderMode) resolveOutOfLineEnclosingQualification(cls) else null
            val anchorSecIdx = outermostEnclosingClass(cls)?.frameIndex ?: cls.frameIndex
            val sigParts = ArrayList<String>(params.size + (if (outerCls != null) 1 else 0))
            val defSigParts = ArrayList<String>(params.size + (if (outerCls != null) 1 else 0))
            if (outerCls != null) {
                sigParts.add("${outerCls.cppName}* outer_value")
                defSigParts.add("${outerCls.cppName}* outer_value")
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
                defSigParts.add("$t $paramCppName")
            }
            val explicitKw = if (sigParts.size == 1) "explicit " else ""
            val delCall = FrameAstHelpers.firstChildFrame(stream, sec, KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL_ID)
            val blockFrame = FrameAstHelpers.firstChildFrame(stream, sec, KtNodeTypes.BLOCK_ID)
            val deferSecToPostInclude =
                outOfLineSecQual != null &&
                    (
                        (delCall != null && bodyReferencesExpectOrDeferredBottomHeader(delCall)) ||
                            (blockFrame != null && bodyReferencesExpectOrDeferredBottomHeader(blockFrame))
                    )
            val deferSecToBottom =
                !deferSecToPostInclude &&
                    outOfLineSecQual != null &&
                    (
                        (delCall != null && frameReferencesLaterTopLevelDecl(delCall, anchorSecIdx, cls)) ||
                            (blockFrame != null && frameReferencesLaterTopLevelDecl(blockFrame, anchorSecIdx, cls))
                    )
            val deferOutOfLineSec = deferSecToPostInclude || deferSecToBottom
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
            sb.append("\n$indent$explicitKw${cls.cppName}(${sigParts.joinToString(", ")})")
            if (deferOutOfLineSec) {
                sb.append(";\n")
                val secBodyIndent = ""
                val secDef =
                    buildString {
                        val outerTps = outOfLineSecQual.first
                        if (outerTps.isNotEmpty()) {
                            append(renderTemplatePrefix(outerTps, ""))
                        } else {
                            append("inline ")
                        }
                        append(outOfLineSecQual.second)
                            .append("::")
                            .append(cls.cppName)
                            .append('(')
                            .append(defSigParts.joinToString(", "))
                            .append(')')
                        if (initParts.isNotEmpty()) {
                            append(" : ").append(initParts.joinToString(", "))
                        }
                        if (blockFrame != null) {
                            val renderedBody =
                                bodyEmitter.emitBlock(blockFrame, secBodyIndent, isValueBlock = false, resultTargetVar = null)
                            append(' ').append(renderedBody).append('\n')
                        } else {
                            append(" {}\n")
                        }
                    }
                if (deferSecToPostInclude) {
                    postIncludeHeaderBodies.add(secDef)
                } else {
                    deferredHeaderBodies.add(secDef)
                }
            } else {
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
                        hasCustomGetterBody(baseProp) ||
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
        if ("start" in declaredMethodNames && cls.kotlinName != "Job" && cls.kotlinName != "JobSupport") {
            val startBases = setOf("JobSupport", "AbstractCoroutine", "ScopeCoroutine")
            if (superEntries.any { it.first in startBases }) {
                if ("kotlinx/coroutines/JobSupport.hpp" != includes.selfHeaderPath) {
                    includes.addProject("kotlinx/coroutines/JobSupport.hpp")
                }
                lines.add("using kotlinx::coroutines::JobSupport::start;")
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
        val knownSuspendBaseMethods =
            mapOf(
                "Flow" to setOf("collect"),
                "SharedFlow" to setOf("collect"),
                "StateFlow" to setOf("collect"),
                "FlowCollector" to setOf("emit"),
                "SendChannel" to setOf("send"),
                "SendBufferedChannel" to setOf("send"),
                "Channel" to setOf("send", "receive", "receive_catching"),
                "ProducerScope" to setOf("send"),
                "ReceiveChannel" to setOf("receive", "receive_catching"),
                "Mutex" to setOf("lock"),
            )
        for (fIdx in cls.memberFunctionFrameIndices) {
            val fn = symbols.functionsByFrame[fIdx] ?: continue
            if (isDeprecatedHiddenFun(fn)) continue
            val fnFlags = stream.frames[fn.frameIndex].flags
            if (!PacketFlags.hasFlag(fnFlags, PacketFlags.MOD_SUSPEND) || !PacketFlags.hasFlag(fnFlags, PacketFlags.MOD_OVERRIDE)) {
                continue
            }
            val fnMethodName = resolveCppFunctionOrOperatorName(fn, cls)
            if (fnMethodName == "join" || fnMethodName == "await" || fnMethodName == "acquire") continue
            for ((simple, baseCpp) in superEntries) {
                val declaredOnKnownBase = fnMethodName in knownSuspendBaseMethods[simple].orEmpty()
                val sameFileBaseCls = symbols.classesByName[simple]
                val declaredOnSameFileBase =
                    sameFileBaseCls != null &&
                        sameFileBaseCls.memberFunctionFrameIndices.any { bfIdx ->
                            val bFn = symbols.functionsByFrame[bfIdx] ?: return@any false
                            !isDeprecatedHiddenFun(bFn) && resolveCppFunctionOrOperatorName(bFn, sameFileBaseCls) == fnMethodName
                        }
                if (declaredOnKnownBase || declaredOnSameFileBase) {
                    lines.add("using $baseCpp::$fnMethodName;")
                }
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
                        includes.addProject("kotlinx/coroutines/Continuation.hpp")
                        includes.addSystem("memory")
                        includes.addSystem("utility")
                        sb.append(
                            "\n$indent[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]\n" +
                                "${indent}void emit($elemType value);\n\n" +
                                "$indent[[clang::annotate(\"suspend\")]]\n" +
                                "${indent}void* emit($elemType value, std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion) override " +
                                "{ return ${fieldRef}${arrow}emit(std::move(value), std::move(_kxs_completion)); }\n",
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
                        includes.addProject("kotlinx/coroutines/Continuation.hpp")
                        includes.addProject("kotlinx/coroutines/flow/FlowCollector.hpp")
                        includes.addSystem("memory")
                        includes.addSystem("utility")
                        sb.append(
                            "\n$indent[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]\n" +
                                "${indent}void collect(std::shared_ptr<kotlinx::coroutines::flow::FlowCollector<$elemType>> collector);\n\n" +
                                "$indent[[clang::annotate(\"suspend\")]]\n" +
                                "${indent}void* collect(std::shared_ptr<kotlinx::coroutines::flow::FlowCollector<$elemType>> collector, " +
                                "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion) override " +
                                "{ return ${fieldRef}${arrow}collect(std::move(collector), std::move(_kxs_completion)); }\n",
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
                    includes.addProject("kotlinx/coroutines/Job.hpp")
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
                                "\n${indent}std::shared_ptr<kotlinx::coroutines::selects::SelectClause2<" +
                                    "$elemType, std::shared_ptr<kotlinx::coroutines::channels::SendChannel<$elemType>>>> " +
                                    "on_send() const override { return ${fieldRef}${arrow}on_send(); }\n",
                            )
                        }
                        if (("send" to 1) !in declaredFnArities) {
                            declaredFnArities.add("send" to 1)
                            includes.addProject("kotlinx/coroutines/Continuation.hpp")
                            sb.append(
                                "\n$indent[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]\n" +
                                    "${indent}void send($elemType element);\n\n" +
                                    "$indent[[clang::annotate(\"suspend\")]]\n" +
                                    "${indent}void* send($elemType element, std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion) override " +
                                    "{ return ${fieldRef}${arrow}send(std::move(element), std::move(_kxs_completion)); }\n",
                            )
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
                            includes.addProject("kotlinx/coroutines/Continuation.hpp")
                            if (elemType == "void*") {
                                sb.append(
                                    "\n$indent[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]\n" +
                                        "${indent}void* receive();\n\n" +
                                        "$indent[[clang::annotate(\"suspend\")]]\n" +
                                        "${indent}void* receive(std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion) override " +
                                        "{ return ${fieldRef}${arrow}receive(std::move(_kxs_completion)); }\n",
                                )
                            } else {
                                includes.addSystem("type_traits")
                                val boxElem = "std::conditional_t<std::is_void_v<$elemType>, kotlin::Unit, $elemType>"
                                sb.append(
                                    "\n$indent[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]\n" +
                                        "$indent$boxElem receive();\n\n" +
                                        "$indent[[clang::annotate(\"suspend\")]]\n" +
                                        "${indent}void* receive(std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion) override " +
                                        "{ return ${fieldRef}${arrow}receive(std::move(_kxs_completion)); }\n",
                                )
                            }
                        }
                        if (("receiveCatching" to 0) !in declaredFnArities) {
                            declaredFnArities.add("receiveCatching" to 0)
                            includes.addProject("kotlinx/coroutines/Continuation.hpp")
                            sb.append(
                                "\n$indent[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]\n" +
                                    "${indent}ChannelResult<$elemType> receive_catching();\n\n" +
                                    "$indent[[clang::annotate(\"suspend\")]]\n" +
                                    "${indent}void* receive_catching(std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion) override " +
                                    "{ return ${fieldRef}${arrow}receive_catching(std::move(_kxs_completion)); }\n",
                            )
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

    private fun ensureCreateTestResultReturn(
        body: String,
        sym: KtFunctionSymbol,
        isDualAbiSuspend: Boolean,
        indent: String,
    ): String {
        if (bundle.isDocExample ||
            isDualAbiSuspend ||
            sym.kotlinName != "createTestResult" ||
            sym.isExpressionBody ||
            !body.endsWith("}") ||
            containsWholeWord(body, "return")
        ) {
            return body
        }
        val trimmedEnd = body.dropLast(1).trimEnd()
        return when {
            trimmedEnd == "{" -> "{ return TestResult{}; }"
            !body.contains('\n') -> "$trimmedEnd return TestResult{}; }"
            else -> "$trimmedEnd\n$indent    return TestResult{};\n$indent}"
        }
    }

    private data class AtomicFieldRenderSpec(
        val elemType: String,
        val atomicTemplateName: String,
        val useAtomicRefHolder: Boolean,
    )

    private fun resolveNonTrivialAtomicElemType(
        af: AtomicFieldInfo,
        simpleEnumCppNames: Set<String>,
    ): String? {
        if (bundle.isDocExample) return null
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
        if (unwrapped.startsWith("std::shared_ptr<")) return unwrapped
        if (unwrapped == "void*") return null
        if (unwrapped !in CppTypeTranslator.TRIVIAL_ATOMIC_SCALAR_TYPES &&
            unwrapped !in simpleEnumCppNames &&
            !unwrapped.endsWith("*")
        ) {
            return unwrapped
        }
        return null
    }

    private fun resolveAtomicFieldRenderSpec(
        af: AtomicFieldInfo,
        enclosingClass: KtClassSymbol?,
    ): AtomicFieldRenderSpec {
        val simpleEnumCppNames =
            symbols.classesByFrame.values
                .filter { it.kind == KtClassKind.SIMPLE_ENUM }
                .map { it.cppName }
                .toSet()
        val nonTrivialElem = resolveNonTrivialAtomicElemType(af, simpleEnumCppNames)
        val useAtomicRefHolder = nonTrivialElem != null
        val elemType =
            if (useAtomicRefHolder) {
                if (nonTrivialElem == "std::any") {
                    includes.addSystem("any")
                }
                nonTrivialElem
            } else {
                val rawElemType =
                    af.explicitElementTypeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT)
                    } ?: af.inferredElementCppType ?: "void*"
                CppTypeTranslator.coerceToTrivialAtomicElementType(rawElemType, simpleEnumCppNames)
            }
        val useCopyableAtomic =
            !useAtomicRefHolder &&
                !bundle.isDocExample &&
                !af.isArray &&
                enclosingClass != null &&
                enclosingClass.kind != KtClassKind.OBJECT_SINGLETON &&
                enclosingClass.kind != KtClassKind.COMPANION_OBJECT
        val atomicTemplateName =
            when {
                useAtomicRefHolder -> {
                    includes.addSystem("any")
                    includes.addSystem("cstddef")
                    includes.addSystem("mutex")
                    includes.addSystem("typeinfo")
                    includes.needsAtomicReferenceDef = true
                    "KxsAtomicReference"
                }
                useCopyableAtomic -> {
                    includes.addSystem("type_traits")
                    includes.needsCopyableAtomicDef = true
                    "KxsCopyableAtomic"
                }
                else -> "std::atomic"
            }
        return AtomicFieldRenderSpec(
            elemType = elemType,
            atomicTemplateName = atomicTemplateName,
            useAtomicRefHolder = useAtomicRefHolder,
        )
    }

    private fun resolveComparableElemType(
        cls: KtClassSymbol?,
        visited: MutableSet<Int> = HashSet(),
    ): String? {
        if (cls == null || bundle.isDocExample || !visited.add(cls.frameIndex)) return null
        for (trIdx in cls.superTypeFrameIndices) {
            val trFrame = stream.frames[trIdx]
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, trFrame) ?: continue
            if (simple == "Comparable") {
                val userType = FrameAstHelpers.firstChildFrame(stream, trFrame, KtNodeTypes.USER_TYPE_ID)
                val argList = userType?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_ARGUMENT_LIST_ID) }
                val firstProj =
                    argList?.let { FrameAstHelpers.childFramesOfType(stream, it, KtNodeTypes.TYPE_PROJECTION_ID).firstOrNull() }
                val argRef = firstProj?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_REFERENCE_ID) }
                return if (argRef != null) {
                    bundle.typeTranslator.translateTypeFrame(argRef, CppTypePosition.TYPE_ARGUMENT)
                } else {
                    includes.addSystem("any")
                    "std::any"
                }
            }
        }
        for (trIdx in cls.superTypeFrameIndices) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: continue
            val sameFileSuper = symbols.classesByName[simple] ?: continue
            resolveComparableElemType(sameFileSuper, visited)?.let { return it }
        }
        return null
    }

    private fun renderComparableClassHelpers(
        cls: KtClassSymbol,
        inner: String,
    ): String {
        if (bundle.isDocExample || cls.kind == KtClassKind.INTERFACE) return ""
        val compElem = resolveComparableElemType(cls) ?: return ""
        includes.addSystem("compare")
        val selfQual =
            if (cls.typeParams.isEmpty()) {
                cls.cppName
            } else {
                "${cls.cppName}<${cls.typeParams.joinToString(", ") { it.name }}>"
            }
        val hasSyntheticEq =
            (cls.kind == KtClassKind.VALUE_CLASS || cls.kind == KtClassKind.DATA_CLASS) && compElem == selfQual
        return buildString {
            append(inner)
                .append("auto operator<=>(const ")
                .append(compElem)
                .append("& other) const { return this->compare_to(other) <=> 0; }\n")
            if (!hasSyntheticEq) {
                append(inner)
                    .append("bool operator==(const ")
                    .append(compElem)
                    .append("& other) const { return this->compare_to(other) == 0; }\n")
            }
        }
    }

    private fun computeNormalizedOverloadKey(
        methodName: String,
        normTypes: List<String>,
        isConstMethod: Boolean,
    ): String = "$methodName(${normTypes.joinToString(",")})|const=$isConstMethod"

    private fun collectScopeNormalizedSigs(
        fnFrameIndices: List<Int>,
        enclosingClass: KtClassSymbol?,
        into: MutableSet<String> = HashSet(),
    ): MutableSet<String> {
        for (fnIdx in fnFrameIndices) {
            val fnFrame = stream.frames[fnIdx]
            val sym = symbols.functionsByFrame[fnIdx] ?: continue
            if (bundle.suspendPlan.leafIntrinsicsByFunFrame.containsKey(fnIdx)) continue
            val liveFlags = fnFrame.flags
            val isDepHidden = isDeprecatedHiddenFun(sym)
            if (isDepHidden || sym.cAbiSymbolName != null) continue
            val collapsedVarianceTp = resolveCollapsedVarianceEscapeTypeParam(sym, enclosingClass)
            val baseTypeParams = if (collapsedVarianceTp != null) emptyList() else sym.typeParams
            val emitInlineInHpp = PacketFlags.hasFlag(liveFlags, PacketFlags.EMIT_IN_HPP_INLINE_BODY)
            val isPureDecl = sym.bodyFrameIndex == null && fnFrame.frameIndex !in bundle.ffiPlan.inlineCppByFrame
            val isExpectFun = FrameAstHelpers.hasModifierKeyword(stream, fnFrame, KtTokens.EXPECT_MODIFIER_ID)
            val isInterfaceMethod = enclosingClass?.kind == KtClassKind.INTERFACE
            val hasBaseVirtualTrait =
                isInterfaceMethod ||
                    PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
                    PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) ||
                    PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE)
            val isSuspend = PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_SUSPEND)
            val starPlan =
                if (!bundle.isDocExample &&
                    (emitInlineInHpp || (isPureDecl && isExpectFun)) &&
                    !isSuspend &&
                    baseTypeParams.isEmpty() &&
                    !hasBaseVirtualTrait
                ) {
                    buildStarProjectionPlan(sym)
                } else {
                    StarProjectionPlan(emptyList(), null, emptyMap())
                }
            val comparableElem = resolveComparableElemType(enclosingClass)
            val isComparableOverride =
                !bundle.isDocExample &&
                    comparableElem != null &&
                    sym.kotlinName == "compareTo" &&
                    sym.valueParams.size == 1 &&
                    sym.typeParams.isEmpty() &&
                    PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE)
            val isEqualsOverrideOnCancellationExc =
                sym.kotlinName == "equals" &&
                    sym.valueParams.size == 1 &&
                    enclosingClass != null &&
                    isCancellationExceptionSubclass(enclosingClass)
            val isConstMethod =
                isComparableOverride || isEqualsOverrideOnCancellationExc || shouldMarkMethodConst(sym, enclosingClass)
            val methodName = resolveCppFunctionOrOperatorName(sym, enclosingClass)
            val normTypes = ArrayList<String>(sym.valueParams.size + 1)
            if (sym.receiverTypeFrameIndex != null) {
                val rawRType = bundle.typeTranslator.translateTypeFrame(stream.frames[sym.receiverTypeFrameIndex], CppTypePosition.EXTENSION_RECEIVER)
                val rType = applyStarNamesToRenderedType(rawRType, starPlan.receiverStarNames)
                val passType =
                    formatExtensionReceiverPassType(
                        rType = rType,
                        mutatesVectorReceiver = extensionFunctionMutatesVectorReceiver(sym, rType),
                    )
                normTypes.add(passType)
            }
            for ((pIdx, vp) in sym.valueParams.withIndex()) {
                val rawT =
                    resolveFunctionParamRawType(
                        sym = sym,
                        pIdx = pIdx,
                        vp = vp,
                        starPlan = starPlan,
                        forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                        comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                    )
                normTypes.add(wrapVarargParamType(rawT, vp.isVararg))
            }
            into.add(computeNormalizedOverloadKey(methodName, normTypes, isConstMethod))
        }
        return into
    }

    private fun collectClassScopeNormalizedSigs(cls: KtClassSymbol): MutableSet<String> {
        val sigs = HashSet<String>()
        cls.companionFrameIndex?.let { compIdx ->
            val compSym = symbols.classesByFrame[compIdx]
            if (compSym != null && compSym.superTypeFrameIndices.isEmpty()) {
                collectScopeNormalizedSigs(compSym.memberFunctionFrameIndices, enclosingClass = compSym, into = sigs)
            }
        }
        collectScopeNormalizedSigs(cls.memberFunctionFrameIndices, enclosingClass = cls, into = sigs)
        return sigs
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
        if (sym.isConst || hasCustomGetterBody(sym)) return false
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

    private fun collectEnclosingClassTypeParamNames(
        enclosingClass: KtClassSymbol?,
        into: MutableSet<String>,
    ) {
        val chain = ArrayList<KtClassSymbol>()
        var cur = enclosingClass
        while (cur != null) {
            chain.add(cur)
            cur = cur.enclosingClassFrameIndex?.let { symbols.classesByFrame[it] }
        }
        for (i in chain.lastIndex downTo 0) {
            chain[i].typeParams.forEach { into.add(it.name) }
        }
    }

    private fun resolveReceiverTypeForReturnInference(
        recvTypeFrameIndex: Int?,
        enclosingClass: KtClassSymbol?,
    ): String? {
        if (recvTypeFrameIndex != null) {
            return bundle.typeTranslator.translateTypeFrame(
                stream.frames[recvTypeFrameIndex],
                CppTypePosition.RETURN,
            )
        }
        if (enclosingClass == null) return null
        val tArgs =
            if (enclosingClass.typeParams.isEmpty()) {
                ""
            } else {
                "<${enclosingClass.typeParams.joinToString(", ") { it.name }}>"
            }
        val clsCpp = "${enclosingClass.cppName}$tArgs"
        val isShared =
            !bundle.isDocExample &&
                (
                    CppTypeTranslator.isSharedHierarchyType(enclosingClass.kotlinName) ||
                        enclosingClass.kotlinName in symbols.sharedHierarchyTypeNames ||
                        enclosingClass.kotlinName == "ConcurrentLinkedListNode" ||
                        enclosingClass.kotlinName == "Segment" ||
                        enclosingClass.kotlinName == "LockFreeLinkedListNode"
                )
        return if (isShared) {
            includes.addSystem("memory")
            "std::shared_ptr<$clsCpp>"
        } else {
            clsCpp
        }
    }

    private fun isMutableExtensionReceiverCppType(cppType: String): Boolean {
        val trimmed = cppType.trim()
        return trimmed.startsWith("std::atomic<") ||
            trimmed.startsWith("KxsAtomicReference<") ||
            trimmed.startsWith("WorkaroundAtomicReference<") ||
            trimmed.startsWith("KxsCopyableAtomic<") ||
            trimmed.startsWith("KxsArrayDeque<") ||
            trimmed == "KxsStringBuilder" ||
            trimmed.startsWith("std::deque<") ||
            trimmed.startsWith("std::unordered_map<") ||
            trimmed.startsWith("std::unordered_set<")
    }

    private fun extensionFunctionMutatesVectorReceiver(
        sym: KtFunctionSymbol,
        cppRecvType: String,
    ): Boolean {
        val trimmed = cppRecvType.trim()
        if (!trimmed.startsWith("std::vector<") || sym.bodyFrameIndex == null) return false
        val bodyFrame = stream.frames[sym.bodyFrameIndex]
        fun checkFrame(
            frame: PacketFrame,
            parentFrame: PacketFrame?,
        ): Boolean {
            when (frame.nodeTypeId) {
                KtNodeTypes.CALL_EXPRESSION_ID -> {
                    val calleeFrame = FrameAstHelpers.childFrames(stream, frame).firstOrNull()
                    val calleeName = calleeFrame?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
                    val isImplicitOrThisCall =
                        when (parentFrame?.nodeTypeId) {
                            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
                            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID,
                            -> {
                                val parts = FrameAstHelpers.childFrames(stream, parentFrame)
                                parts.size >= 2 &&
                                    parts[1].frameIndex == frame.frameIndex &&
                                    parts[0].nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID
                            }
                            else -> true
                        }
                    if (isImplicitOrThisCall && calleeName in VECTOR_MUTATING_METHOD_NAMES) {
                        return true
                    }
                }
                KtNodeTypes.BINARY_EXPRESSION_ID -> {
                    val ch = FrameAstHelpers.childFrames(stream, frame)
                    if (ch.size >= 3 && FrameAstHelpers.operationTokenId(stream, ch[1]) in ASSIGN_OPERATOR_IDS) {
                        val lhs = FrameAstHelpers.childFrames(stream, ch[0]).firstOrNull()
                        if (ch[0].nodeTypeId == KtNodeTypes.ARRAY_ACCESS_EXPRESSION_ID &&
                            lhs?.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID
                        ) {
                            return true
                        }
                    }
                }
            }
            for (child in FrameAstHelpers.childFrames(stream, frame)) {
                if (checkFrame(child, frame)) return true
            }
            return false
        }
        return checkFrame(bodyFrame, null)
    }

    private fun formatExtensionReceiverPassType(
        rType: String,
        mutatesVectorReceiver: Boolean = false,
    ): String =
        when {
            rType in VALUE_PASS_CPP_TYPES || rType.endsWith("&") || rType.endsWith("*") -> rType
            isMutableExtensionReceiverCppType(rType) -> "$rType&"
            mutatesVectorReceiver && rType.trim().startsWith("std::vector<") -> "$rType&"
            else -> "const $rType&"
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

    private fun isPostIncludeCycleClass(cls: KtClassSymbol): Boolean =
        (
            symbols.headerRelativePath == "kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp" &&
                cls.kotlinName == "SubscriptionCountStateFlow"
        ) ||
            (
                symbols.headerRelativePath == "kotlinx/coroutines/CancellableContinuationImpl.hpp" &&
                    cls.kotlinName == "ChildContinuation"
            ) ||
            (
                symbols.headerRelativePath == "kotlinx/coroutines/CancellableContinuation.hpp" &&
                    cls.kotlinName == "DisposeOnCancel"
            ) ||
            (
                symbols.headerRelativePath == "kotlinx/coroutines/JobSupport.hpp" &&
                    cls.kotlinName == "AwaitContinuation"
            )

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
        symbols.propertiesByFrame.values
            .asSequence()
            .filter { FrameAstHelpers.hasModifierKeyword(stream, stream.frames[it.frameIndex], KtTokens.EXPECT_MODIFIER_ID) }
            .forEach { expectNames.add(it.kotlinName) }
        for (pi in frame.firstPacketIndex..frame.lastPacketIndex) {
            val pkt = stream.packets[pi]
            if (pkt !is SyntaxPacket.IdentPacket) continue
            val raw = pkt.unescapedName
            val resolved = symbols.importAliases[raw] ?: raw
            if (resolved in expectNames) return true
            if (includes.selfHeaderPath == "kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp" &&
                resolved == "SubscriptionCountStateFlow"
            ) {
                return true
            }
            if (includes.selfHeaderPath == "kotlinx/coroutines/CancellableContinuationImpl.hpp" &&
                resolved == "ChildContinuation"
            ) {
                return true
            }
            if (includes.selfHeaderPath == "kotlinx/coroutines/CancellableContinuation.hpp" &&
                resolved == "DisposeOnCancel"
            ) {
                return true
            }
            if (includes.selfHeaderPath == "kotlinx/coroutines/JobSupport.hpp" &&
                resolved == "AwaitContinuation"
            ) {
                return true
            }
            if (CppTypeTranslator.isDeferredCircularSymbol(resolved, includes.selfHeaderPath)) return true
            val hdr = CppTypeTranslator.lookupKnownHeaderForSymbol(resolved)
            if (hdr != null && includes.hasDeferredBottomProject(hdr)) return true
        }
        return false
    }

    companion object {
        private val STAR_STD_ANY_IN_ANGLE_REGEX: Regex =
            Regex("""(?:<|,\s*)(std::any|void\*)(?=\s*[,>])""")

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
                "BaseContinuationImpl",
                "ContinuationImpl",
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

        private val VECTOR_MUTATING_METHOD_NAMES: Set<String> =
            setOf(
                "set",
                "add",
                "addAll",
                "remove",
                "removeAt",
                "removeFirst",
                "removeLast",
                "clear",
                "fill",
                "sort",
                "sortWith",
                "sortBy",
                "sortByDescending",
                "shuffle",
                "reverse",
                "retainAll",
                "removeAll",
            )

        private val ARITHMETIC_BINARY_OPERATOR_IDS: Set<Int> =
            setOf(
                KtTokens.PLUS_ID,
                KtTokens.MINUS_ID,
                KtTokens.MUL_ID,
                KtTokens.DIV_ID,
                KtTokens.PERC_ID,
            )

        private val BITWISE_INFIX_NAMES: Set<String> =
            setOf("shl", "shr", "ushr", "and", "or", "xor")

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
