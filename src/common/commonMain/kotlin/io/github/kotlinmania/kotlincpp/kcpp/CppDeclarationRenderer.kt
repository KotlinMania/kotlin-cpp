package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal class CppDeclarationRenderer(
    internal val bundle: LoweringBundle,
    internal val isHeaderMode: Boolean,
) {
    internal val stream: PacketStream get() = bundle.stream
    internal val symbols: SameFileSymbolTable get() = bundle.symbols
    internal val includes: CppIncludeCollector get() = bundle.includes
    internal val deferredHeaderBodies: MutableList<String> = ArrayList()
    val postIncludeHeaderBodies: MutableList<String> = ArrayList()

    internal fun hasCustomGetterBody(sym: KtPropertySymbol): Boolean {
        val gIdx = sym.getterFrameIndex ?: return false
        val gf = stream.frames[gIdx]
        return FrameAstHelpers.firstChildFrame(stream, gf, KtNodeTypes.BLOCK_ID) != null ||
            FrameAstHelpers.extractInitializerAfterEquals(stream, gf) != null
    }

    internal fun hasCustomSetterBody(sym: KtPropertySymbol): Boolean {
        val sIdx = sym.setterFrameIndex ?: return false
        val sf = stream.frames[sIdx]
        return FrameAstHelpers.firstChildFrame(stream, sf, KtNodeTypes.BLOCK_ID) != null ||
            FrameAstHelpers.extractInitializerAfterEquals(stream, sf) != null
    }

    internal fun hasCustomAccessorBody(sym: KtPropertySymbol): Boolean =
        hasCustomGetterBody(sym) || hasCustomSetterBody(sym)

    internal fun containsLambdaDescendant(frame: PacketFrame): Boolean {
        if (frame.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID) return true
        return FrameAstHelpers.childFrames(stream, frame).any { containsLambdaDescendant(it) }
    }

    internal fun isHoistedTopLevelConstOrSymbolProp(declFrame: PacketFrame): Boolean {
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

    internal fun isHoistedForwardReferencedClass(declFrame: PacketFrame): Boolean {
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

    internal fun collectTopLevelClassDependencies(
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

    internal fun emitStandaloneRootComments(out: StringBuilder) {
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

    internal fun frameMentionsUndeclaredLocalClass(frameIdx: Int?): Boolean {
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

    internal fun emitForwardDeclarations(out: StringBuilder) {
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
            val parsedRet = CppType.parseInternal(rawRetType)
            val isNoreturn = parsedRet.isNoReturn
            val retType = parsedRet.stripNoReturn().render()
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

    internal fun renderProvenanceAndLeadingComments(
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

    internal fun renderTemplatePrefix(
        typeParams: List<KtTypeParamInfo>,
        indent: String,
        defaultedVoidPtrNames: Set<String> = emptySet(),
    ): String {
        if (typeParams.isEmpty()) return ""
        val clauses =
            typeParams.map { tp ->
                if (tp.name in defaultedVoidPtrNames) {
                    "typename ${tp.name} = void*"
                } else {
                    "typename ${tp.name}"
                }
            }
        return buildCppTokens(baseIndent = indent) {
            emitTemplateDeclaration(clauses)
        }
    }

    internal fun renderTypeAlias(
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

    internal fun renderProperty(
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
            val parsedRet = CppType.parseInternal(rawRetType)
            val isNoreturn = parsedRet.isNoReturn
            val retType = parsedRet.stripNoReturn().render()
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
                            if (rawInit == null) {
                                "{}"
                            } else {
                                CppType.parseInternal(backingCppType).coerceNullDefaultExpr(
                                    rawInit,
                                    includes,
                                    coerceStdAnyToBraces = true,
                                )
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
                        CppType.parseInternal(cppT).coerceNullDefaultExpr(
                            initCode,
                            includes,
                            coerceStdAnyToBraces = true,
                        )
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
                            CppType.parseInternal(cppT).coerceNullDefaultExpr(
                                initCode,
                                includes,
                                coerceStdAnyToBraces = true,
                            )
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

    companion object {
        internal val BOOL_BINARY_OPERATOR_IDS: Set<Int> =
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

        internal val KNOWN_TO_STRING_BASE_CLASSES: Set<String> =
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

        internal val CONTINUATION_HIERARCHY_BASES: Set<String> =
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

        internal val ASSIGN_OPERATOR_IDS: Set<Int> =
            setOf(
                KtTokens.EQ_ID,
                KtTokens.PLUSEQ_ID,
                KtTokens.MINUSEQ_ID,
                KtTokens.MULTEQ_ID,
                KtTokens.DIVEQ_ID,
                KtTokens.PERCEQ_ID,
            )

        internal val VECTOR_MUTATING_METHOD_NAMES: Set<String> =
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

        internal val ARITHMETIC_BINARY_OPERATOR_IDS: Set<Int> =
            setOf(
                KtTokens.PLUS_ID,
                KtTokens.MINUS_ID,
                KtTokens.MUL_ID,
                KtTokens.DIV_ID,
                KtTokens.PERC_ID,
            )

        internal val BITWISE_INFIX_NAMES: Set<String> =
            setOf("shl", "shr", "ushr", "and", "or", "xor")

        internal val VALUE_PASS_CPP_TYPES: Set<String> =
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
