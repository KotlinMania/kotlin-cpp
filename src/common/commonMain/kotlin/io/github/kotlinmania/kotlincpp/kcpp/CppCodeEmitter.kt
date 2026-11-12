package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
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
        imp.mappedProjectHeader?.let { headerIncludes.addProject(it) }
    }

    val globalTypeParams = LinkedHashSet<String>()
    for (cls in symbols.classesByFrame.values) {
        for (tp in cls.typeParams) globalTypeParams.add(tp.name)
    }
    for (fn in symbols.functionsByFrame.values) {
        for (tp in fn.typeParams) globalTypeParams.add(tp.name)
    }
    for (ta in symbols.typeAliasesByFrame.values) {
        for (tp in ta.typeParams) globalTypeParams.add(tp.name)
    }

    val headerTypeTranslator =
        CppTypeTranslator(
            stream = stream,
            config = config,
            includes = headerIncludes,
            polymorphicTypeNames = symbols.polymorphicTypeNames,
            typeParamNames = globalTypeParams,
        )
    val sourceTypeTranslator =
        CppTypeTranslator(
            stream = stream,
            config = config,
            includes = sourceIncludes,
            polymorphicTypeNames = symbols.polymorphicTypeNames,
            typeParamNames = globalTypeParams,
        )

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
        )

    val headerBodyText = CppDeclarationRenderer(headerBundle, isHeaderMode = true).renderBodySection()
    val sourceBodyText = CppDeclarationRenderer(sourceBundle, isHeaderMode = false).renderBodySection()

    val fullHeaderContent = assembleHeaderFile(symbols, ffiPlan, headerIncludes, headerBodyText)
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
        append("/** Transliterated from: ").append(symbols.transliteratedFromPath).append(" */\n")

        for (sysHdr in includes.sortedSystemHeaders()) {
            append("#include <").append(sysHdr).append(">\n")
        }
        for (projHdr in includes.sortedProjectHeaders()) {
            append("#include \"").append(projHdr).append("\"\n")
        }

        for (preamble in ffiPlan.preambleInlineCppBlocks) {
            append('\n').append(preamble.trimIndent()).append('\n')
        }

        val nsJoined = symbols.namespaceSegments.joinToString("::")
        if (nsJoined.isNotEmpty()) {
            append("\nnamespace ").append(nsJoined).append(" {\n\n")
        } else {
            append('\n')
        }

        append(bodyText.trimEnd()).append('\n')

        if (nsJoined.isNotEmpty()) {
            append("\n} // namespace ").append(nsJoined).append('\n')
        }
    }

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
        append("/** Transliterated from: ").append(symbols.transliteratedFromPath).append(" */\n")
        append("#include \"").append(symbols.headerRelativePath).append("\"\n")
        for (sysHdr in includes.sortedSystemHeaders()) {
            append("#include <").append(sysHdr).append(">\n")
        }
        for (projHdr in includes.sortedProjectHeaders()) {
            if (projHdr != symbols.headerRelativePath) {
                append("#include \"").append(projHdr).append("\"\n")
            }
        }

        val nsJoined = symbols.namespaceSegments.joinToString("::")
        if (nsJoined.isNotEmpty()) {
            append("\nnamespace ").append(nsJoined).append(" {\n\n")
        } else {
            append('\n')
        }

        append(bodyText.trimEnd()).append('\n')

        if (nsJoined.isNotEmpty()) {
            append("\n} // namespace ").append(nsJoined).append('\n')
        }
    }

private class CppDeclarationRenderer(
    private val bundle: LoweringBundle,
    private val isHeaderMode: Boolean,
) {
    private val stream: PacketStream get() = bundle.stream
    private val symbols: SameFileSymbolTable get() = bundle.symbols
    private val includes: CppIncludeCollector get() = bundle.includes

    fun renderBodySection(): String =
        buildString {
            if (isHeaderMode) {
                emitStandaloneRootComments(this)
                emitForwardDeclarations(this)
            }

            for (declFrame in symbols.topLevelDeclarationFrames) {
                val rendered =
                    when (declFrame.nodeTypeId) {
                        KtNodeTypes.TYPEALIAS_ID ->
                            if (isHeaderMode) renderTypeAlias(declFrame, indent = "") else ""
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
                        val text = FrameAstHelpers.formatCommentForCpp(pkt, "")
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
        for (clsFrameIdx in symbols.forwardDeclaredClassFrames) {
            val cls = symbols.classesByFrame[clsFrameIdx] ?: continue
            if (cls.typeParams.isNotEmpty()) {
                out.append(renderTemplatePrefix(cls.typeParams, ""))
            }
            val kw = if (cls.kind == KtClassKind.SIMPLE_ENUM || cls.kind == KtClassKind.RICH_ENUM) "enum class" else "class"
            out.append(kw).append(' ').append(cls.cppName).append(";\n")
        }
        out.append('\n')
    }

    private fun renderProvenanceAndLeadingComments(
        declFrame: PacketFrame,
        indent: String,
        includeLeadingKdoc: Boolean,
    ): String =
        buildString {
            if (includeLeadingKdoc) {
                for (c in FrameAstHelpers.extractLeadingDeclarationComments(stream, declFrame)) {
                    val formatted = FrameAstHelpers.formatCommentForCpp(c, indent)
                    if (formatted.isNotEmpty()) {
                        append(formatted).append('\n')
                    }
                }
            }
            if (PacketFlags.hasFlag(declFrame.flags, PacketFlags.EMIT_PROVENANCE_COMMENT)) {
                append(indent)
                    .append("// Transliterated from: ")
                    .append(symbols.transliteratedFromPath)
                    .append(':')
                    .append(declFrame.span.startLine)
                    .append('-')
                    .append(declFrame.span.endLine)
                    .append('\n')
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
            append(indent).append("using ").append(sym.cppName).append(" = ").append(targetType).append(";\n")
        }
    }

    private fun renderProperty(
        propFrame: PacketFrame,
        enclosingClass: KtClassSymbol?,
        indent: String,
    ): String {
        val sym = symbols.propertiesByFrame[propFrame.frameIndex] ?: return ""
        val emitInCpp = PacketFlags.hasFlag(sym.flags, PacketFlags.EMIT_IN_CPP_BODY)

        // Computed property with custom `get()` (e.g. `val Foo.isReady: Boolean get() = ...`)
        if (sym.getterFrameIndex != null) {
            val getterFrame = stream.frames[sym.getterFrameIndex]
            val getterBody =
                FrameAstHelpers.firstChildFrame(stream, getterFrame, KtNodeTypes.BLOCK_ID)
                    ?: FrameAstHelpers.extractInitializerAfterEquals(stream, getterFrame)
            val retType =
                sym.returnTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                } ?: "auto"
            val recvParam =
                sym.receiverTypeFrameIndex?.let {
                    val rType = bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER)
                    "$rType self"
                }

            if (isHeaderMode) {
                return buildString {
                    append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
                    val inlineKw = if (!emitInCpp && enclosingClass == null) "inline " else ""
                    val constQual = if (enclosingClass != null && recvParam == null) " const" else ""
                    val paramSig = recvParam.orEmpty()
                    if (!emitInCpp && getterBody != null) {
                        val emitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                enclosingClass = enclosingClass,
                                receiverSubstitution = if (recvParam != null) "self" else null,
                            )
                        val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                        val bodyStr = emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = indent)
                        append(indent).append(inlineKw).append(retType).append(' ').append(sym.cppName)
                            .append('(').append(paramSig).append(')').append(constQual).append(' ').append(bodyStr).append('\n')
                    } else {
                        append(indent).append(retType).append(' ').append(sym.cppName)
                            .append('(').append(paramSig).append(')').append(constQual).append(";\n")
                    }
                }
            } else if (emitInCpp && getterBody != null) {
                val emitter =
                    CppExprAndStmtEmitter(
                        bundle = bundle,
                        enclosingClass = enclosingClass,
                        receiverSubstitution = if (recvParam != null) "self" else null,
                    )
                val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                val bodyStr = emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = indent)
                val qual = if (enclosingClass != null) "${enclosingClass.cppName}::" else ""
                val constQual = if (enclosingClass != null && recvParam == null) " const" else ""
                return buildString {
                    append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = false))
                    append(indent).append(retType).append(' ').append(qual).append(sym.cppName)
                        .append('(').append(recvParam.orEmpty()).append(')').append(constQual).append(' ').append(bodyStr).append('\n')
                }
            }
            return ""
        }

        if (!isHeaderMode) return ""

        // `atomicfu` field or array
        bundle.atomicPlan.atomicFieldsByPropFrame[propFrame.frameIndex]?.let { af ->
            includes.addSystem("atomic")
            val elemType =
                af.explicitElementTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT)
                } ?: af.inferredElementCppType ?: "void*"
            val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = enclosingClass)
            return buildString {
                append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
                if (af.isArray) {
                    includes.addSystem("vector")
                    val sz = af.arraySizeExprFrameIndex?.let { emitter.emitExpression(stream.frames[it]) } ?: "0"
                    val prefix = if (enclosingClass == null) "inline " else ""
                    append(indent).append(prefix).append("std::vector<std::atomic<").append(elemType).append(">> ")
                        .append(sym.cppName).append("(static_cast<std::size_t>(").append(sz).append("));\n")
                } else {
                    val initCode = af.initExprFrameIndex?.let { emitter.emitExpression(stream.frames[it]) } ?: "{}"
                    val prefix = if (enclosingClass == null) "inline " else "mutable "
                    append(indent).append(prefix).append("std::atomic<").append(elemType).append("> ")
                        .append(sym.cppName).append('{').append(initCode).append("};\n")
                }
            }
        }

        // `ThreadLocal` / `CommonThreadLocal` slot
        bundle.atomicPlan.threadLocalsByPropFrame[propFrame.frameIndex]?.let { tl ->
            includes.addProject("kotlinx/coroutines/internal/ThreadLocal.hpp")
            val elemType =
                tl.explicitElementTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT)
                } ?: "void*"
            val prefix = if (enclosingClass == null) "inline " else "static inline "
            return buildString {
                append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
                append(indent).append(prefix).append("ThreadLocal<").append(elemType).append("> ")
                    .append(sym.cppName).append("{};\n")
            }
        }

        val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = enclosingClass)
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
                val valT = if (cppT == "std::string") {
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
                append(indent).append(staticOrInline).append(valT).append(' ').append(sym.cppName)
                    .append(" = ").append(renderedInit).append(";\n")
            } else if (
                enclosingClass == null ||
                enclosingClass.kind == KtClassKind.OBJECT_SINGLETON ||
                enclosingClass.kind == KtClassKind.COMPANION_OBJECT
            ) {
                val storageKw = if (enclosingClass != null) "static inline " else "inline "
                val constKw = if (!sym.isVar) "const " else ""
                val cppT = explicitType ?: "auto"
                if (initCode != null) {
                    append(indent).append(storageKw).append(constKw).append(cppT).append(' ').append(sym.cppName)
                        .append(" = ").append(initCode).append(";\n")
                } else {
                    append(indent).append(storageKw).append(cppT).append(' ').append(sym.cppName).append("{};\n")
                }
            } else {
                val constKw = if (!sym.isVar) "const " else ""
                val cppT = explicitType ?: "std::any"
                if (initCode != null) {
                    append(indent).append(constKw).append(cppT).append(' ').append(sym.cppName)
                        .append(" = ").append(initCode).append(";\n")
                } else {
                    append(indent).append(constKw).append(cppT).append(' ').append(sym.cppName).append("{};\n")
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

        if (!isHeaderMode && !emitInCpp) return ""

        val activeTps = LinkedHashSet<String>()
        enclosingClass?.typeParams?.forEach { activeTps.add(it.name) }
        sym.typeParams.forEach { activeTps.add(it.name) }

        val retType =
            if (sym.returnTypeFrameIndex != null) {
                bundle.typeTranslator.translateTypeFrame(stream.frames[sym.returnTypeFrameIndex], CppTypePosition.RETURN)
            } else if (sym.isExpressionBody && sym.bodyFrameIndex != null) {
                inferExpressionBodyReturnType(stream.frames[sym.bodyFrameIndex])
            } else {
                "void"
            }
        val isVoidReturn = retType == "void" || retType == "[[noreturn]] void"

        val paramsRendered = buildFunctionParameterList(sym, includeDefaults = isHeaderMode)
        val methodName = resolveCppFunctionOrOperatorName(sym)
        val isStaticOnObject =
            enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT
        val isInterfaceMethod = enclosingClass?.kind == KtClassKind.INTERFACE
        val isVirtualMethod =
            isInterfaceMethod ||
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
                PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)
        val isOverride = PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE)
        val isConstMethod = shouldMarkMethodConst(sym, enclosingClass)

        val isSuspend = PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_SUSPEND)
        val isCAbi = sym.cAbiSymbolName != null

        if (isHeaderMode) {
            return buildString {
                append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                append(renderTemplatePrefix(sym.typeParams, indent))
                if (isSuspend) {
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                }
                append(indent)
                if (isCAbi) {
                    append("extern \"C\" ")
                } else if (isStaticOnObject) {
                    append("static ")
                } else if (isVirtualMethod && !isOverride) {
                    append("virtual ")
                } else if (emitInlineInHpp && enclosingClass == null && sym.typeParams.isEmpty()) {
                    append("inline ")
                }
                append(retType).append(' ').append(methodName).append('(').append(paramsRendered).append(')')
                if (isConstMethod) append(" const")
                if (isCAbi) append(" noexcept")
                if (isOverride && enclosingClass != null && !isStaticOnObject) append(" override")

                if (isPureDecl) {
                    if (isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) {
                        append(" = 0;\n")
                    } else {
                        append(";\n")
                    }
                } else if (emitInlineInHpp) {
                    append(' ').append(renderFunctionBody(sym, enclosingClass, activeTps, isVoidReturn, indent)).append('\n')
                } else {
                    append(";\n")
                }
            }
        }

        // `.cpp` out-of-line implementation
        val qualifiedName = if (qualifierPrefix.isEmpty()) methodName else "$qualifierPrefix::$methodName"
        return buildString {
            append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = false))
            append(renderTemplatePrefix(sym.typeParams, indent))
            append(indent)
            if (isCAbi) append("extern \"C\" ")
            append(retType).append(' ').append(qualifiedName).append('(').append(paramsRendered).append(')')
            if (isConstMethod) append(" const")
            if (isCAbi) append(" noexcept")
            append(' ').append(renderFunctionBody(sym, enclosingClass, activeTps, isVoidReturn, indent)).append('\n')
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

        val baseParams = buildFunctionParameterList(sym, includeDefaults = isHeaderMode)
        val explicitParams =
            if (baseParams.isEmpty()) {
                "std::shared_ptr<Continuation<$contElemType>> completion"
            } else {
                "$baseParams, std::shared_ptr<Continuation<$contElemType>> completion"
            }
        val isTemplate = sym.typeParams.isNotEmpty()

        if (isHeaderMode) {
            return buildString {
                append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                append(renderTemplatePrefix(sym.typeParams, indent))
                append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                append(indent).append("void* ").append(sym.cppName).append('(').append(explicitParams).append(')')
                if (isTemplate) {
                    val emitter = CppExprAndStmtEmitter(bundle)
                    emitter.bindLocalName(leaf.uContParamKotlinName, "completion")
                    for (vp in sym.valueParams) emitter.bindLocalName(vp.name, vp.cppName)
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
                append(indent).append("__attribute__((error(\"").append(sym.cppName)
                    .append("() requires suspend lowering; use ").append(sym.cppName)
                    .append("(completion) at an explicit ABI boundary\")))\n")
                append(indent).append(authoringRetType).append(' ').append(sym.cppName).append('(').append(baseParams).append(");\n")
            }
        }

        if (isTemplate) return ""
        val emitter = CppExprAndStmtEmitter(bundle)
        emitter.bindLocalName(leaf.uContParamKotlinName, "completion")
        for (vp in sym.valueParams) emitter.bindLocalName(vp.name, vp.cppName)
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
            append(indent).append("void* ").append(qualName).append('(')
                .append(
                    buildFunctionParameterList(sym, includeDefaults = false).let {
                        if (it.isEmpty()) {
                            "std::shared_ptr<Continuation<$contElemType>> completion"
                        } else {
                            "$it, std::shared_ptr<Continuation<$contElemType>> completion"
                        }
                    },
                )
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
        val emitter =
            CppExprAndStmtEmitter(
                bundle = bundle,
                activeTypeParams = activeTypeParams,
                enclosingClass = enclosingClass,
                receiverSubstitution = if (hasRecv) "self" else null,
            )
        for (vp in sym.valueParams) {
            emitter.bindLocalName(vp.name, vp.cppName)
        }

        val entryGuards = ArrayList<String>()
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
                append(indent).append("    try ").append(coreBody)
                    .append(" catch (...) {\n")
                append(indent).append("        std::terminate();\n")
                append(indent).append("    }\n")
                append(indent).append('}')
            }
        }
        return coreBody
    }

    private fun buildFunctionParameterList(
        sym: KtFunctionSymbol,
        includeDefaults: Boolean,
    ): String {
        val parts = ArrayList<String>(sym.valueParams.size + 1)
        if (sym.receiverTypeFrameIndex != null) {
            val rType = bundle.typeTranslator.translateTypeFrame(stream.frames[sym.receiverTypeFrameIndex], CppTypePosition.PARAMETER)
            val passType =
                if (rType in VALUE_PASS_CPP_TYPES || rType.endsWith("&") || rType.endsWith("*")) {
                    rType
                } else {
                    "const $rType&"
                }
            parts.add("$passType self")
        }
        val emitter = CppExprAndStmtEmitter(bundle)
        for (vp in sym.valueParams) {
            val rawT =
                vp.typeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[it],
                        CppTypePosition.PARAMETER,
                        stream.frames[vp.frameIndex].flags,
                    )
                } ?: "auto"
            val paramT =
                if (vp.isVararg) {
                    includes.addSystem("vector")
                    "const std::vector<$rawT>&"
                } else {
                    rawT
                }
            val defaultSuffix =
                if (includeDefaults && vp.defaultExprFrameIndex != null) {
                    " = ${emitter.emitExpression(stream.frames[vp.defaultExprFrameIndex])}"
                } else {
                    ""
                }
            parts.add("$paramT ${vp.cppName}$defaultSuffix")
        }
        return parts.joinToString(", ")
    }

    private fun resolveCppFunctionOrOperatorName(sym: KtFunctionSymbol): String {
        if (!sym.isOperator) return sym.cppName
        return when (sym.kotlinName) {
            "get" -> "operator[]"
            "invoke" -> "operator()"
            "plus" -> "operator+"
            "minus" -> "operator-"
            "times" -> "operator*"
            "div" -> "operator/"
            "rem" -> "operator%"
            "unaryMinus" -> "operator-"
            "unaryPlus" -> "operator+"
            "not" -> "operator!"
            else -> sym.cppName
        }
    }

    private fun shouldMarkMethodConst(
        sym: KtFunctionSymbol,
        enclosingClass: KtClassSymbol?,
    ): Boolean {
        if (enclosingClass == null) return false
        if (enclosingClass.kind == KtClassKind.INTERFACE ||
            enclosingClass.kind == KtClassKind.OBJECT_SINGLETON ||
            enclosingClass.kind == KtClassKind.COMPANION_OBJECT
        ) {
            return false
        }
        if (sym.receiverTypeFrameIndex != null || PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_SUSPEND)) {
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
                val op = FrameAstHelpers.frameSourceSlice(stream, ch[1]).trim()
                if (op in setOf("=", "+=", "-=", "*=", "/=", "%=")) {
                    val lhsText = FrameAstHelpers.frameSourceSlice(stream, ch[0]).trim().removePrefix("this.")
                    val isMember =
                        cls.primaryConstructorParams.any { it.name == lhsText } ||
                            cls.memberPropertyFrameIndices.any { symbols.propertiesByFrame[it]?.kotlinName == lhsText }
                    if (isMember) return true
                }
            }
        }
        for (c in FrameAstHelpers.childFrames(stream, frame)) {
            if (mutatesMemberField(c, cls)) return true
        }
        return false
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
            else -> "auto"
        }

    private fun renderClassOrObject(
        declFrame: PacketFrame,
        qualifierPrefix: String,
        indent: String,
    ): String {
        val cls = symbols.classesByFrame[declFrame.frameIndex] ?: return ""
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
                if (isHeaderMode) renderValueClass(declFrame, cls, indent) else ""
            KtClassKind.DATA_CLASS ->
                if (isHeaderMode) renderDataClass(declFrame, cls, indent) else ""
            KtClassKind.INTERFACE,
            KtClassKind.SEALED_CLASS,
            KtClassKind.REGULAR_CLASS,
            KtClassKind.OBJECT_SINGLETON,
            KtClassKind.COMPANION_OBJECT,
            -> renderGeneralClass(declFrame, cls, qualifierPrefix, indent)
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
                    val formatted = FrameAstHelpers.formatCommentForCpp(c, inner)
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
                append(indent).append("inline constexpr ").append(retT).append(' ')
                    .append(cp.cppName).append('(').append(cls.cppName).append(" self) {\n")
                append(indent).append("    switch (self) {\n")
                for (entry in cls.enumEntries) {
                    val argVal =
                        entry.constructorArgsFrameIndex?.let { argListIdx ->
                            val vas = FrameAstHelpers.childFramesOfType(stream, stream.frames[argListIdx], KtNodeTypes.VALUE_ARGUMENT_ID)
                            val va = vas.getOrNull(pIdx)
                            val expr = va?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                            expr?.let { emitter.emitExpression(it) }
                        } ?: "{}"
                    append(indent).append("        case ").append(cls.cppName).append("::").append(entry.cppName)
                        .append(": return ").append(argVal).append(";\n")
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
                    append(indent).append("inline ").append(retT).append(' ').append(propSym.cppName)
                        .append('(').append(cls.cppName).append(" self) ").append(bodyStr).append('\n')
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
                append(indent).append("inline ").append(retT).append(' ').append(fnCallName)
                    .append('(').append(fullParams).append(") ")
                    .append(fnEmitter.emitBlockOrExprBody(stream.frames[bodyIdx], fnSym.isExpressionBody, retT == "void", indent))
                    .append('\n')
            }
        }
    }

    private fun renderValueClass(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        indent: String,
    ): String {
        val underlyingParam = cls.primaryConstructorParams.firstOrNull()
        val valType =
            underlyingParam?.typeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
            } ?: "std::int64_t"
        val propName = underlyingParam?.cppName ?: "value"
        val fieldName = CppNamingPolicy.toPrivateFieldName(propName)
        includes.addSystem("utility")

        return buildString {
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(cls.typeParams, indent))
            append(indent).append("class ").append(cls.cppName).append(" {\n")
            append(indent).append("private:\n")
            append(indent).append("    ").append(valType).append(' ').append(fieldName).append(";\n")
            append(indent).append("public:\n")
            append(indent).append("    constexpr explicit ").append(cls.cppName).append('(')
                .append(valType).append(' ').append(propName).append(") : ")
                .append(fieldName).append("(std::move(").append(propName).append(")) {}\n")
            append(indent).append("    constexpr const ").append(valType).append("& ")
                .append(propName).append("() const noexcept { return ").append(fieldName).append("; }\n")
            append(indent).append("    constexpr bool operator==(const ").append(cls.cppName).append("&) const = default;\n")
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
        indent: String,
    ): String {
        includes.addSystem("string")
        includes.addSystem("utility")
        val inheritance = renderInheritanceClause(cls)
        val inner = "$indent    "

        return buildString {
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(cls.typeParams, indent))
            append(indent).append("class ").append(cls.cppName).append(inheritance).append(" {\n")
            append(indent).append("public:\n")

            val paramTypes = ArrayList<String>(cls.primaryConstructorParams.size)
            for (cp in cls.primaryConstructorParams) {
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                    } ?: "std::any"
                paramTypes.add(t)
                if (cp.isVal || cp.isVar) {
                    val mutPrefix = if (cp.isVar) "" else ""
                    append(inner).append(mutPrefix).append(t).append(' ').append(cp.cppName).append(";\n")
                }
            }

            if (cls.primaryConstructorParams.isNotEmpty()) {
                val explicitKw = if (cls.primaryConstructorParams.size == 1) "explicit " else ""
                val emitter = CppExprAndStmtEmitter(bundle)
                val ctorParams =
                    cls.primaryConstructorParams.mapIndexed { i, cp ->
                        val def =
                            cp.defaultExprFrameIndex?.let { " = ${emitter.emitExpression(stream.frames[it])}" }.orEmpty()
                        "${paramTypes[i]} ${cp.cppName}_in$def"
                    }
                val inits = buildConstructorInitializerList(cls, suffix = "_in")
                append('\n').append(inner).append(explicitKw).append(cls.cppName)
                    .append('(').append(ctorParams.joinToString(", ")).append(") : ")
                    .append(inits.joinToString(", ")).append(" {}\n")
            }

            append(inner).append("bool operator==(const ").append(cls.cppName).append("&) const = default;\n")

            // `component1()`..`componentN()` and `copy(...)`
            val propParams = cls.primaryConstructorParams.filter { it.isVal || it.isVar }
            for ((idx, cp) in propParams.withIndex()) {
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                    } ?: "auto"
                append(inner).append("const ").append(t).append("& component").append(idx + 1)
                    .append("() const noexcept { return ").append(cp.cppName).append("; }\n")
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
                append(inner).append(cls.cppName).append(" copy(")
                    .append(copyParams.joinToString(", ")).append(") const { return ")
                    .append(cls.cppName).append('(').append(copyArgs).append("); }\n")
            }

            for (mPropIdx in cls.memberPropertyFrameIndices) {
                val rendered = renderProperty(stream.frames[mPropIdx], cls, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            for (mFnIdx in cls.memberFunctionFrameIndices) {
                val rendered = renderFunction(stream.frames[mFnIdx], cls, cls.cppName, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
            append(indent).append("};\n")
        }
    }

    private fun renderGeneralClass(
        declFrame: PacketFrame,
        cls: KtClassSymbol,
        qualifierPrefix: String,
        indent: String,
    ): String {
        val fullQual = if (qualifierPrefix.isEmpty()) cls.cppName else "$qualifierPrefix::${cls.cppName}"
        val emitCtorInCpp = PacketFlags.hasFlag(cls.flags, PacketFlags.EMIT_IN_CPP_BODY)

        if (!isHeaderMode) {
            return buildString {
                if (emitCtorInCpp && cls.primaryConstructorParams.isNotEmpty()) {
                    includes.addSystem("utility")
                    val ctorSigParams =
                        cls.primaryConstructorParams.joinToString(", ") { cp ->
                            val t =
                                cp.typeFrameIndex?.let {
                                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                                } ?: "std::any"
                            "$t ${cp.cppName}_value"
                        }
                    val initList = buildConstructorInitializerList(cls, suffix = "_value")
                    append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = false))
                    append(indent).append(fullQual).append("::").append(cls.cppName)
                        .append('(').append(ctorSigParams).append(") : ")
                        .append(initList.joinToString(", ")).append(" {}\n")
                }

                for (nestedIdx in cls.memberClassFrameIndices) {
                    val sub = renderClassOrObject(stream.frames[nestedIdx], fullQual, indent)
                    if (sub.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(sub)
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
        val inner = "$indent    "
        val isPolymorphicBase =
            cls.kind == KtClassKind.INTERFACE ||
                cls.kind == KtClassKind.SEALED_CLASS ||
                PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_OPEN) ||
                PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_ABSTRACT)

        return buildString {
            append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(cls.typeParams, indent))
            append(indent).append("class ").append(cls.cppName).append(inheritance).append(" {\n")

            // Private fields first (`snake_case_`)
            val privateCtorProps =
                cls.primaryConstructorParams.filter {
                    (it.isVal || it.isVar) && PacketFlags.hasFlag(stream.frames[it.frameIndex].flags, PacketFlags.MOD_PRIVATE)
                }
            val privateMemberProps =
                cls.memberPropertyFrameIndices.filter { pIdx ->
                    val p = symbols.propertiesByFrame[pIdx]
                    p != null && !p.isConst && PacketFlags.hasFlag(p.flags, PacketFlags.MOD_PRIVATE) && p.getterFrameIndex == null
                }
            if (privateCtorProps.isNotEmpty() || privateMemberProps.isNotEmpty()) {
                append(indent).append("private:\n")
                for (cp in privateCtorProps) {
                    val t =
                        cp.typeFrameIndex?.let {
                            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                        } ?: "std::any"
                    val constQual = if (!cp.isVar) "const " else ""
                    append(inner).append(constQual).append(t).append(' ')
                        .append(CppNamingPolicy.toPrivateFieldName(cp.name)).append(";\n")
                }
                for (pIdx in privateMemberProps) {
                    append(renderProperty(stream.frames[pIdx], cls, inner))
                }
            }

            append(indent).append("public:\n")
            if (isPolymorphicBase) {
                append(inner).append("virtual ~").append(cls.cppName).append("() = default;\n")
            }

            if (cls.kind == KtClassKind.OBJECT_SINGLETON) {
                append(inner).append("static ").append(cls.cppName).append("& instance() {\n")
                append(inner).append("    static ").append(cls.cppName).append(" inst{};\n")
                append(inner).append("    return inst;\n")
                append(inner).append("}\n")
            }

            // Public constructor properties (`const std::string symbol;` on `Symbol`)
            val publicCtorProps =
                cls.primaryConstructorParams.filter {
                    (it.isVal || it.isVar) && !PacketFlags.hasFlag(stream.frames[it.frameIndex].flags, PacketFlags.MOD_PRIVATE)
                }
            for (cp in publicCtorProps) {
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                    } ?: "std::any"
                val constQual = if (!cp.isVar) "const " else ""
                append(inner).append(constQual).append(t).append(' ').append(cp.cppName).append(";\n")
            }

            // Primary constructor declaration / inline body
            if (cls.primaryConstructorParams.isNotEmpty() || cls.superCallArgsBySuperIndex.isNotEmpty()) {
                val ctorVis = if (cls.kind == KtClassKind.SEALED_CLASS) "${indent}protected:\n$inner" else "\n$inner"
                val explicitKw = if (cls.primaryConstructorParams.size == 1) "explicit " else ""
                val emitter = CppExprAndStmtEmitter(bundle)
                val ctorSigParams =
                    cls.primaryConstructorParams.joinToString(", ") { cp ->
                        val t =
                            cp.typeFrameIndex?.let {
                                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                            } ?: "std::any"
                        val def = cp.defaultExprFrameIndex?.let { " = ${emitter.emitExpression(stream.frames[it])}" }.orEmpty()
                        "$t ${cp.cppName}_value$def"
                    }
                append(ctorVis).append(explicitKw).append(cls.cppName).append('(').append(ctorSigParams).append(')')
                if (emitCtorInCpp) {
                    append(";\n")
                } else {
                    includes.addSystem("utility")
                    val initList = buildConstructorInitializerList(cls, suffix = "_value")
                    if (initList.isNotEmpty()) {
                        append(" : ").append(initList.joinToString(", "))
                    }
                    append(" {}\n")
                }
                if (cls.kind == KtClassKind.SEALED_CLASS) {
                    append(indent).append("public:\n")
                }
            } else if (cls.kind == KtClassKind.SEALED_CLASS) {
                append(indent).append("protected:\n")
                append(inner).append(cls.cppName).append("() = default;\n")
                append(indent).append("public:\n")
            }

            // Nested classes (excluding `Companion`, whose members lift directly as `static` onto enclosing class!)
            for (nestedIdx in cls.memberClassFrameIndices) {
                val rendered = renderClassOrObject(stream.frames[nestedIdx], fullQual, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }

            // Companion object constants and static factories lifted onto the class
            cls.companionFrameIndex?.let { compIdx ->
                val compSym = symbols.classesByFrame[compIdx]
                if (compSym != null) {
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

            append(indent).append("};\n")
        }
    }

    private fun renderInheritanceClause(cls: KtClassSymbol): String {
        if (cls.superTypeFrameIndices.isEmpty()) return ""
        val bases =
            cls.superTypeFrameIndices.map { trIdx ->
                val rawBase = bundle.typeTranslator.translateTypeFrame(stream.frames[trIdx], CppTypePosition.TYPE_ARGUMENT)
                "public $rawBase"
            }
        return " : ${bases.joinToString(", ")}"
    }

    private fun buildConstructorInitializerList(
        cls: KtClassSymbol,
        suffix: String,
    ): List<String> {
        val inits = ArrayList<String>()
        val emitter = CppExprAndStmtEmitter(bundle)
        for (cp in cls.primaryConstructorParams) {
            emitter.bindLocalName(cp.name, "${cp.cppName}$suffix")
        }
        for ((superTypeIdx, argListIdx) in cls.superCallArgsBySuperIndex) {
            val baseName = bundle.typeTranslator.translateTypeFrame(stream.frames[superTypeIdx], CppTypePosition.TYPE_ARGUMENT)
            val args =
                FrameAstHelpers.childFramesOfType(stream, stream.frames[argListIdx], KtNodeTypes.VALUE_ARGUMENT_ID)
                    .mapNotNull { va ->
                        FrameAstHelpers.childFrames(stream, va)
                            .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                            ?.let { emitter.emitExpression(it) }
                    }
            inits.add("$baseName(${args.joinToString(", ")})")
        }
        for (cp in cls.primaryConstructorParams) {
            if (!cp.isVal && !cp.isVar) continue
            val isPriv = PacketFlags.hasFlag(stream.frames[cp.frameIndex].flags, PacketFlags.MOD_PRIVATE)
            val fieldName = if (isPriv) CppNamingPolicy.toPrivateFieldName(cp.name) else cp.cppName
            inits.add("$fieldName(std::move(${cp.cppName}$suffix))")
        }
        return inits
    }

    companion object {
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
                append(retType).append(' ').append(cName).append('(').append(cParams).append(");\n")
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
