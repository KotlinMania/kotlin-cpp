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
private data class LoweredFileBundles(
    val stream: PacketStream,
    val symbols: SameFileSymbolTable,
    val suspendPlan: SuspendLoweringPlan,
    val ffiPlan: FfiAndCTypePlan,
    val headerIncludes: CppIncludeCollector,
    val sourceIncludes: CppIncludeCollector,
    val headerBundle: LoweringBundle,
    val sourceBundle: LoweringBundle,
)

private fun buildLoweredFileBundles(
    stream: PacketStream,
    config: CppTargetConfig,
    isDocExample: Boolean,
): LoweredFileBundles {
    val symbols = SameFileSymbolPass.run(stream = stream, config = config)
    val suspendPlan = SuspendStateMachinePass.run(stream = stream, symbols = symbols)
    val gcPlan = GcAndOwnershipPass.run(stream = stream, config = config, symbols = symbols, suspendPlan = suspendPlan)
    val atomicPlan = AtomicAndThreadLocalPass.run(stream = stream, config = config, symbols = symbols)
    val ffiPlan = FfiAndCTypePass.run(stream = stream, symbols = symbols, suspendPlan = suspendPlan)
    val cfPlan = ControlFlowAndTypePass.run(stream = stream, symbols = symbols)

    val headerIncludes = CppIncludeCollector(selfHeaderPath = symbols.headerRelativePath)
    val sourceIncludes = CppIncludeCollector(selfHeaderPath = "")
    if (!isDocExample) {
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
    }

    val globalTypeParams = LinkedHashSet<String>()
    val sharedTypeParams = LinkedHashSet<String>()
    fun recordTypeParams(tps: List<KtTypeParamInfo>) {
        for (tp in tps) {
            globalTypeParams.add(tp.name)
            if (!isDocExample) {
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
    }
    for (cls in symbols.classesByFrame.values) recordTypeParams(cls.typeParams)
    for (fn in symbols.functionsByFrame.values) recordTypeParams(fn.typeParams)
    for (prop in symbols.propertiesByFrame.values) recordTypeParams(prop.typeParams)
    for (ta in symbols.typeAliasesByFrame.values) recordTypeParams(ta.typeParams)

    val localClassNames = LinkedHashSet<String>()
    val localClassCppNames = LinkedHashMap<String, String>()
    val localClassTypeParams = LinkedHashMap<String, List<String>>()
    for (cls in symbols.classesByFrame.values) {
        val isExpect =
            FrameAstHelpers.hasModifierKeyword(stream, stream.frames[cls.frameIndex], KtTokens.EXPECT_MODIFIER_ID)
        if (!isExpect) {
            if (!isDocExample) {
                localClassNames.add(cls.kotlinName)
            }
            if (cls.enclosingClassFrameIndex == null) {
                localClassCppNames[cls.kotlinName] = cls.cppName
            }
        }
        if (!isDocExample && cls.typeParams.isNotEmpty()) {
            localClassTypeParams[cls.kotlinName] = cls.typeParams.map { it.name }
        }
    }

    val namespaceForTranslator = if (isDocExample) emptyList() else symbols.namespaceSegments
    fun createTranslator(includes: CppIncludeCollector): CppTypeTranslator =
        CppTypeTranslator(
            stream = stream,
            config = config,
            includes = includes,
            polymorphicTypeNames = symbols.polymorphicTypeNames,
            typeParamNames = globalTypeParams,
            currentNamespaceSegments = namespaceForTranslator,
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

    fun createBundle(
        includes: CppIncludeCollector,
        translator: CppTypeTranslator,
    ): LoweringBundle =
        LoweringBundle(
            stream = stream,
            config = config,
            symbols = symbols,
            suspendPlan = suspendPlan,
            gcPlan = gcPlan,
            atomicPlan = atomicPlan,
            ffiPlan = ffiPlan,
            cfPlan = cfPlan,
            includes = includes,
            typeTranslator = translator,
            knownFunctionNames = knownFnNames,
            isDocExample = isDocExample,
        )

    val headerTranslator = createTranslator(headerIncludes)
    val sourceTranslator = createTranslator(sourceIncludes)
    return LoweredFileBundles(
        stream = stream,
        symbols = symbols,
        suspendPlan = suspendPlan,
        ffiPlan = ffiPlan,
        headerIncludes = headerIncludes,
        sourceIncludes = sourceIncludes,
        headerBundle = createBundle(headerIncludes, headerTranslator),
        sourceBundle = createBundle(sourceIncludes, sourceTranslator),
    )
}

public fun translateKotlinToCpp(
    source: String,
    sourcePath: String = "<memory>",
    config: CppTargetConfig = CppTargetConfig(),
): CppTranslationResult {
    val stream = buildKotlinPacketStream(source = source, sourcePath = sourcePath)
    val lowered = buildLoweredFileBundles(stream = stream, config = config, isDocExample = false)
    val symbols = lowered.symbols
    val suspendPlan = lowered.suspendPlan
    val ffiPlan = lowered.ffiPlan

    fun hasActualModifier(frameIndex: Int): Boolean =
        FrameAstHelpers.hasModifierKeyword(stream, stream.frames[frameIndex], KtTokens.ACTUAL_MODIFIER_ID)

    val hasActualDecls =
        symbols.classesByFrame.keys.any(::hasActualModifier) ||
            symbols.functionsByFrame.keys.any(::hasActualModifier) ||
            symbols.propertiesByFrame.keys.any(::hasActualModifier) ||
            symbols.typeAliasesByFrame.keys.any(::hasActualModifier)

    val headerRenderer = CppDeclarationRenderer(lowered.headerBundle, isHeaderMode = true)
    val headerBodyText = headerRenderer.renderBodySection()
    val sourceBodyText = CppDeclarationRenderer(lowered.sourceBundle, isHeaderMode = false).renderBodySection()

    val fullHeaderContent =
        assembleHeaderFile(
            symbols = symbols,
            ffiPlan = ffiPlan,
            includes = lowered.headerIncludes,
            bodyText = headerBodyText,
            postIncludeBodies = headerRenderer.postIncludeHeaderBodies,
            hasActualDecls = hasActualDecls,
        )
    val headerArtifact = CppFileArtifact(relativePath = symbols.headerRelativePath, content = fullHeaderContent)

    val sourceArtifact =
        if (sourceBodyText.isNotBlank()) {
            val fullSourceContent = assembleSourceFile(symbols, lowered.sourceIncludes, sourceBodyText)
            CppFileArtifact(relativePath = symbols.sourceRelativePath, content = fullSourceContent)
        } else {
            null
        }

    val companions =
        if (config.emitCompanionCAbiFiles && ffiPlan.cAbiExportFunctionFrames.isNotEmpty()) {
            emitCompanionCAbiArtifacts(lowered.headerBundle)
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

private fun CppTokenEmitter.emitIfInclude(
    condition: String,
    header: String,
) {
    emitPreprocessor("if", condition)
    emitIncludeProject(header)
    emitPreprocessor("endif")
}

private fun CppTokenEmitter.emitCommonIncludesAndPreamble(
    includes: CppIncludeCollector,
    selfRelPath: String,
    emitTrailingSelfInclude: Boolean,
    ffiPlan: FfiAndCTypePlan? = null,
) {
    ensureHelperPreambleHeaders(includes)
    for (sysHdr in includes.sortedSystemHeaders()) {
        emitIncludeSystem(sysHdr)
    }
    for (projHdr in includes.sortedProjectHeaders()) {
        if (projHdr == selfRelPath) continue
        emitGuardedHasInclude(projHdr)
    }
    if (emitTrailingSelfInclude) {
        emitIncludeProject(selfRelPath)
    }
    if (ffiPlan != null) {
        for (preamble in ffiPlan.preambleInlineCppBlocks) {
            newline()
            emitRawCode(preamble.trimIndent())
            ensureTrailingNewline()
        }
    }
    emitSharedHelperPreamble(includes, this)
}

private fun CppTokenEmitter.emitCommonNamespaceAliasesAndForwards(
    nsJoined: String,
    includes: CppIncludeCollector,
) {
    if (nsJoined.isNotEmpty() && includes.needsNullOperatorsDef) {
        emitRawCode("using ::operator==;")
        newline(2)
    }
    if (includes.needsCollectionAlias) {
        emitTemplateUsingAlias("typename E", "Collection", "std::vector<E>")
        newline()
    }
    if (includes.needsSequenceAlias) {
        emitTemplateUsingAlias("typename E", "Sequence", "KxsSequence<E>")
        newline()
    }
    if (includes.needsThreadLocalDef) {
        emitTemplateUsingAlias("typename T", "ThreadLocal", "KxsThreadLocal<T>")
        newline()
    }
    if (includes.extraForwardDeclarations.isNotEmpty()) {
        for (fwd in includes.extraForwardDeclarations) {
            emitRawCode(fwd)
            ensureTrailingNewline()
        }
        newline()
    }
}

private fun CppTokenEmitter.emitPostIncludeBodies(
    nsJoined: String,
    postIncludeBodies: List<String>,
    leadingNewline: Boolean = true,
) {
    if (postIncludeBodies.isEmpty()) return
    if (leadingNewline) {
        newline()
    }
    emitNamespaceBlock(nsJoined, blankLineInside = true) {
        for ((idx, b) in postIncludeBodies.withIndex()) {
            if (idx > 0) newline()
            emitRawCode(b.trimEnd())
            newline()
        }
    }
}

private fun appendFlowCycleFooter(
    out: CppTokenEmitter,
    relPath: String,
    guardMacro: String,
    nsJoined: String,
    includes: CppIncludeCollector,
    postIncludeBodies: List<String>,
) {
    val nonCycleBottom =
        includes.sortedDeferredBottomProjectHeaders().filter { it !in FLOW_CYCLE_HEADERS }
    if (nonCycleBottom.isNotEmpty()) {
        out.newline()
        for (btmHdr in nonCycleBottom) {
            out.emitGuardedHasInclude(btmHdr)
        }
    }
    val selfIdx = FLOW_CYCLE_SPECS.indexOfFirst { it.path == relPath }
    val selfSpec = FLOW_CYCLE_SPECS[selfIdx]
    out.emitPreprocessor("undef", selfSpec.inMacro)
    out.emitPreprocessor("define", selfSpec.completeMacro)
    out.emitPreprocessor("endif", "// $guardMacro")

    for (i in FLOW_CYCLE_SPECS.indices) {
        if (i == selfIdx) continue
        val spec = FLOW_CYCLE_SPECS[i]
        val prereqs =
            (0 until i)
                .filter { it != selfIdx }
                .joinToString("") { k ->
                    val prereq = FLOW_CYCLE_SPECS[k]
                    "(defined(${prereq.completeMacro}) || !__has_include(\"${prereq.path}\")) && "
                }
        val cond =
            "$prereqs!defined(${spec.inMacro}) && !defined(${spec.completeMacro}) && " +
                "__has_include(\"${spec.path}\")"
        out.newline()
        out.emitIfInclude(cond, spec.path)
    }

    val allCompleteCond =
        FLOW_CYCLE_SPECS.joinToString(" && ") { spec ->
            "(defined(${spec.completeMacro}) || !__has_include(\"${spec.path}\"))"
        }
    val absSpec = FLOW_CYCLE_SPECS[0]
    if (selfIdx != 0) {
        val cond = "$allCompleteCond && !defined(${absSpec.postMacro}) && __has_include(\"${absSpec.path}\")"
        out.newline()
        out.emitIfInclude(cond, absSpec.path)
    }

    val postPrereq =
        if (selfIdx == 0) {
            allCompleteCond
        } else {
            "$allCompleteCond && (defined(${absSpec.postMacro}) || !__has_include(\"${absSpec.path}\"))"
        }
    out.newline()
    out.emitPreprocessor("if", "$postPrereq && !defined(${selfSpec.postMacro})")
    out.emitPreprocessor("define", selfSpec.postMacro)
    out.emitPostIncludeBodies(nsJoined, postIncludeBodies, leadingNewline = false)
    for (k in 1 until FLOW_CYCLE_SPECS.size) {
        if (k == selfIdx) continue
        val peer = FLOW_CYCLE_SPECS[k]
        out.emitIfInclude("!defined(${peer.postMacro}) && __has_include(\"${peer.path}\")", peer.path)
    }
    out.emitPreprocessor("endif", "// ${selfSpec.postMacro}")
}

private fun assembleHeaderFile(
    symbols: SameFileSymbolTable,
    ffiPlan: FfiAndCTypePlan,
    includes: CppIncludeCollector,
    bodyText: String,
    postIncludeBodies: List<String> = emptyList(),
    hasActualDecls: Boolean = false,
): String =
    buildCppTokens {
        val relPath = symbols.headerRelativePath
        val isFlowCycleHeader = relPath in FLOW_CYCLE_HEADERS
        val isCycleHeader = !isFlowCycleHeader && CppTypeTranslator.isCycleParticipantHeader(relPath)
        val isCommonHeader = relPath.endsWith(".common.hpp")
        val isActualHeader = hasActualDecls && !isCommonHeader && !isFlowCycleHeader && relPath.endsWith(".hpp")
        val guardMacro = headerGuardMacro(relPath)

        if (!isFlowCycleHeader && !isCycleHeader && !isCommonHeader && !isActualHeader) {
            emitPragmaOnce()
        }
        val lintKind = symbols.portLintDirectiveKind
        val lintPath = symbols.portLintSourcePath
        if (lintKind != null && lintPath != null) {
            emitPortLintHeader(lintKind, lintPath)
        }
        if (isCycleHeader) {
            emitPreprocessor("ifndef", guardMacro)
            emitPreprocessor("ifndef", "${guardMacro}IN_P1")
            emitPreprocessor("define", "${guardMacro}IN_P1")
            if (isActualHeader) {
                emitGuardedHasInclude(pairedCommonHeaderForActual(relPath))
            }
        } else if (isFlowCycleHeader || isCommonHeader || isActualHeader) {
            emitPreprocessor("ifndef", guardMacro)
            emitPreprocessor("define", guardMacro)
            if (isActualHeader) {
                emitGuardedHasInclude(pairedCommonHeaderForActual(relPath))
            }
            FLOW_CYCLE_SPECS.find { it.path == relPath }?.let { spec ->
                emitPreprocessor("define", spec.inMacro)
            }
        }

        emitCommonIncludesAndPreamble(includes, relPath, emitTrailingSelfInclude = false, ffiPlan = ffiPlan)

        if (relPath == "kotlinx/coroutines/internal/ConcurrentLinkedList.hpp") {
            emitIncludeGuard("KXS_NOT_COMPLETED_DEFINED") {
                emitNamespaceBlock("kotlinx::coroutines", blankLineInside = false) {
                    emitRawCode(
                        """
                        class NotCompleted {
                        public:
                            virtual ~NotCompleted() = default;
                        };
                        """.trimIndent(),
                    )
                    newline()
                }
            }
        }

        val nsJoined = symbols.namespaceSegments.joinToString("::")
        newline()
        emitNamespaceBlock(nsJoined, blankLineInside = true) {
            if (nsJoined.startsWith("kotlinx::coroutines::")) {
                emitUsingNamespace("kotlinx::coroutines")
                newline()
            }
            if (symbols.imports.any { it.isWildcard && it.fqName == "kotlin.test" } && nsJoined != "kotlin::test") {
                emitUsingNamespace("kotlin::test")
                newline()
            }
            emitCommonNamespaceAliasesAndForwards(nsJoined, includes)

            if (relPath == "kotlinx/coroutines/flow/internal/AbstractSharedFlow.hpp" &&
                !bodyText.contains("class SubscriptionCountStateFlow;")
            ) {
                emitRawCode("class SubscriptionCountStateFlow;")
                newline(2)
            }
            if (relPath == "kotlinx/coroutines/CancellableContinuationImpl.hpp" &&
                !bodyText.contains("class ChildContinuation;")
            ) {
                emitRawCode("class ChildContinuation;")
                newline(2)
            }

            emitRawCode(bodyText.trimEnd())
            newline()
        }

        if (relPath == "kotlinx/coroutines/internal/Symbol.hpp") {
            newline()
            emitRawCode(
                """
                template <>
                struct std::hash<kotlinx::coroutines::internal::Symbol> {
                    std::size_t operator()(const kotlinx::coroutines::internal::Symbol& s) const noexcept {
                        return std::hash<std::string>{}(s.symbol);
                    }
                };
                """.trimIndent(),
            )
            newline()
        }

        if (isFlowCycleHeader) {
            appendFlowCycleFooter(this, relPath, guardMacro, nsJoined, includes, postIncludeBodies)
            return@buildCppTokens
        }

        val deferredBottom = includes.sortedDeferredBottomProjectHeaders()
        fun emitExpectActualIncludes() {
            if (!includes.hasExpectDeclarations) return
            if (relPath.endsWith(".common.hpp")) {
                newline()
                when (relPath) {
                    "kotlinx/coroutines/MultithreadedDispatchers.common.hpp" ->
                        emitGuardedHasInclude("kotlinx/coroutines/MultithreadedDispatchers.hpp")
                    "kotlinx/coroutines/CoroutineContext.common.hpp" ->
                        emitGuardedHasInclude("kotlinx/coroutines/native/CoroutineContext.hpp")
                    else ->
                        emitGuardedHasInclude(relPath.removeSuffix(".common.hpp") + ".hpp")
                }
            } else if (
                relPath == "kotlinx/coroutines/Dispatchers.hpp" ||
                relPath == "kotlinx/coroutines/native/CoroutineContext.hpp"
            ) {
                newline()
                emitPreprocessor(
                    "if",
                    "defined(__APPLE__) && __has_include(\"kotlinx/coroutines/nativeDarwin/Dispatchers.hpp\")",
                )
                emitIncludeProject("kotlinx/coroutines/nativeDarwin/Dispatchers.hpp")
                emitPreprocessor("elif", "__has_include(\"kotlinx/coroutines/nativeOther/Dispatchers.hpp\")")
                emitIncludeProject("kotlinx/coroutines/nativeOther/Dispatchers.hpp")
                emitPreprocessor("endif")
            }
        }

        if (isCycleHeader) {
            emitPreprocessor("undef", "${guardMacro}IN_P1")
            emitPreprocessor("define", guardMacro)
            emitPreprocessor("endif", "// ${guardMacro}IN_P1")
            emitPreprocessor("endif", "// $guardMacro")

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
            val p2Cond =
                buildString {
                    append("defined(").append(guardMacro).append(") && !defined(").append(guardMacro)
                    append("P2) && !defined(").append(guardMacro).append("IN_P2)")
                    for (bg in btmCycleGuards) {
                        append(" && !defined(").append(bg).append("IN_P1)")
                    }
                }
            newline()
            emitPreprocessor("if", p2Cond)
            emitPreprocessor("define", "${guardMacro}IN_P2")
            if (deferredBottom.isNotEmpty()) {
                newline()
                for (btmHdr in deferredBottom) {
                    val isBtmCycle = btmHdr !in FLOW_CYCLE_HEADERS && CppTypeTranslator.isCycleParticipantHeader(btmHdr)
                    if (isBtmCycle) {
                        val bg = headerGuardMacro(btmHdr)
                        emitIfInclude("!defined(${bg}P2) && !defined(${bg}IN_P2) && __has_include(\"$btmHdr\")", btmHdr)
                    } else {
                        emitGuardedHasInclude(btmHdr)
                    }
                }
            }
            emitExpectActualIncludes()
            emitPostIncludeBodies(nsJoined, postIncludeBodies, leadingNewline = true)
            emitPreprocessor("undef", "${guardMacro}IN_P2")
            emitPreprocessor("define", "${guardMacro}P2")
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
                val peerCond =
                    "defined($pg) && !defined(${pg}P2) && !defined(${pg}IN_P2) && " +
                        "!defined(${pg}IN_P1) && __has_include(\"$peerHdr\")"
                emitIfInclude(peerCond, peerHdr)
            }
            emitPreprocessor("endif", "// ${guardMacro}P2")
            return@buildCppTokens
        }

        if (deferredBottom.isNotEmpty()) {
            newline()
            for (btmHdr in deferredBottom) {
                emitGuardedHasInclude(btmHdr)
            }
        }
        emitExpectActualIncludes()
        emitPostIncludeBodies(nsJoined, postIncludeBodies, leadingNewline = true)
        if (isCommonHeader || isActualHeader) {
            emitPreprocessor("endif", "// $guardMacro")
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
    buildCppTokens {
        val lintKind = symbols.portLintDirectiveKind
        val lintPath = symbols.portLintSourcePath
        if (lintKind != null && lintPath != null) {
            emitPortLintHeader(lintKind, lintPath)
        }
        emitIncludeProject(symbols.headerRelativePath)
        emitCommonIncludesAndPreamble(includes, symbols.headerRelativePath, emitTrailingSelfInclude = true)

        val nsJoined = symbols.namespaceSegments.joinToString("::")
        newline()
        emitNamespaceBlock(nsJoined, blankLineInside = true) {
            val wildcardUsingNamespaces =
                symbols.imports
                    .asSequence()
                    .filter {
                        it.isWildcard &&
                            it.fqName in CPP_SOURCE_WILDCARD_NAMESPACES &&
                            it.fqName != symbols.packageName
                    }.map { imp ->
                        imp.fqName
                            .split('.')
                            .joinToString("::") { CppNamingPolicy.toNamespaceSegment(it) }
                    }.distinct()
                    .toList()
            if (wildcardUsingNamespaces.isNotEmpty()) {
                for (uNs in wildcardUsingNamespaces) {
                    emitUsingNamespace(uNs)
                }
                newline()
            }
            emitCommonNamespaceAliasesAndForwards(nsJoined, includes)
            emitRawCode(bodyText.trimEnd())
            newline()
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

private val PLAIN_C_STDINT_SCALARS: Set<String> =
    setOf(
        "std::int8_t",
        "std::int16_t",
        "std::int32_t",
        "std::int64_t",
        "std::uint8_t",
        "std::uint16_t",
        "std::uint32_t",
        "std::uint64_t",
        "std::size_t",
        "std::intptr_t",
        "std::uintptr_t",
    )

private fun emitCompanionCAbiArtifacts(bundle: LoweringBundle): List<CppFileArtifact> {
    val symbols = bundle.symbols
    val headerFileName = "${symbols.fileStem}_c_api.h"
    val defFileName = "${symbols.fileStem}.def"

    val cHeaderText =
        buildCppTokens {
            emitPragmaOnce()
            emitIncludeSystem("stdint.h")
            emitIncludeSystem("stdbool.h")
            emitIncludeSystem("stddef.h")
            newline()
            emitPreprocessor("ifdef", "__cplusplus")
            emitRawCode("extern \"C\" {")
            newline()
            emitPreprocessor("endif")
            newline()
            for (fnIdx in bundle.ffiPlan.cAbiExportFunctionFrames) {
                val fn = symbols.functionsByFrame[fnIdx] ?: continue
                val cName = fn.cAbiSymbolName ?: continue
                val retType =
                    fn.returnTypeFrameIndex?.let {
                        val translated =
                            bundle.typeTranslator.translateTypeFrame(
                                bundle.stream.frames[it],
                                CppTypePosition.RETURN,
                            )
                        mapCTypeForPlainC(translated)
                    } ?: "void"
                val cParams =
                    if (fn.valueParams.isEmpty()) {
                        "void"
                    } else {
                        fn.valueParams.joinToString(", ") { vp ->
                            val rawT =
                                vp.typeFrameIndex?.let {
                                    bundle.typeTranslator.translateTypeFrame(
                                        bundle.stream.frames[it],
                                        CppTypePosition.PARAMETER,
                                    )
                                } ?: "void*"
                            "${mapCTypeForPlainC(rawT)} ${vp.cppName}"
                        }
                    }
                emitRawCode(retType)
                space()
                ident(cName)
                punct("(")
                emitRawCode(cParams)
                punct(");", jointToNext = false)
                newline()
            }
            newline()
            emitPreprocessor("ifdef", "__cplusplus")
            emitRawCode("}")
            newline()
            emitPreprocessor("endif")
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

private fun stripStdIntNamespaceForPlainC(type: CppType): CppType =
    when (type) {
        is CppType.Scalar ->
            if (type.cppName in PLAIN_C_STDINT_SCALARS) {
                CppType.Scalar(type.cppName.removePrefix("std::"), type.isUnsigned)
            } else {
                type
            }
        is CppType.RawPtr -> CppType.RawPtr(stripStdIntNamespaceForPlainC(type.pointee), type.isConstPointee)
        is CppType.Ref -> CppType.Ref(stripStdIntNamespaceForPlainC(type.referent), type.isConst, type.isRvalue)
        is CppType.NoReturn -> CppType.NoReturn(stripStdIntNamespaceForPlainC(type.inner))
        is CppType.SharedPtr -> CppType.SharedPtr(stripStdIntNamespaceForPlainC(type.element))
        is CppType.UniquePtr -> CppType.UniquePtr(stripStdIntNamespaceForPlainC(type.element))
        is CppType.Optional -> CppType.Optional(stripStdIntNamespaceForPlainC(type.element))
        is CppType.Vector -> CppType.Vector(stripStdIntNamespaceForPlainC(type.element))
        is CppType.Named ->
            CppType.Named(type.qualifiers, type.simpleName, type.typeArgs.map(::stripStdIntNamespaceForPlainC))
        else -> type
    }

private fun mapCTypeForPlainC(cppType: String): String =
    stripStdIntNamespaceForPlainC(CppType.parseInternal(cppType)).render()

internal fun renderDocExampleFromPacketStream(stream: PacketStream): Pair<String, String> {
    val lowered = buildLoweredFileBundles(stream = stream, config = CppTargetConfig(), isDocExample = true)
    val headerBody = CppDeclarationRenderer(lowered.headerBundle, isHeaderMode = true).renderBodySection()
    val sourceBody = CppDeclarationRenderer(lowered.sourceBundle, isHeaderMode = false).renderBodySection()
    return headerBody to sourceBody
}
