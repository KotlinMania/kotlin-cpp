package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.ASSIGN_OPERATOR_IDS
import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.CONTINUATION_HIERARCHY_BASES
import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.VALUE_PASS_CPP_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.VECTOR_MUTATING_METHOD_NAMES
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal fun CppDeclarationRenderer.outermostEnclosingClass(cls: KtClassSymbol?): KtClassSymbol? {
    var cur = cls ?: return null
    while (true) {
        val parentIdx = cur.enclosingClassFrameIndex ?: return cur
        cur = symbols.classesByFrame[parentIdx] ?: return cur
    }
}

internal fun CppDeclarationRenderer.resolveOutOfLineEnclosingQualification(
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

internal fun CppDeclarationRenderer.frameReferencesLaterTopLevelDecl(
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

internal fun CppDeclarationRenderer.bodyReferencesLaterTopLevelDecl(
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

internal fun CppDeclarationRenderer.frameMentionsIdentifier(
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

internal fun CppDeclarationRenderer.resolveExtensionReceiverContext(sym: KtFunctionSymbol): Triple<KtClassSymbol?, Boolean, String?> =
    resolveExtensionReceiverFrameContext(sym.receiverTypeFrameIndex)

internal fun CppDeclarationRenderer.resolveExtensionReceiverFrameContext(rIdx: Int?): Triple<KtClassSymbol?, Boolean, String?> {
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

internal fun CppDeclarationRenderer.lookupClassInScope(
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

internal fun CppDeclarationRenderer.isDefaultComparableCppType(cppType: String): Boolean {
    val t = cppType.trim()
    return !t.contains("std::any") &&
        !t.contains("std::function<") &&
        !t.contains("SuspendFunction<") &&
        !t.contains("std::exception_ptr")
}

internal fun CppDeclarationRenderer.referencesNonFieldCtorParam(
    enclosingClass: KtClassSymbol?,
    exprFrame: PacketFrame,
): Boolean {
    if (enclosingClass == null) return false
    return enclosingClass.primaryConstructorParams.any { cp ->
        !cp.isVal && !cp.isVar && frameMentionsIdentifier(exprFrame, cp.name)
    }
}

internal fun CppDeclarationRenderer.wrapVoidPtrExpr(code: String): String {
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

internal fun CppDeclarationRenderer.renderVoidPtrAtomicInit(
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

internal fun CppDeclarationRenderer.renderDeferredMemberPropCtorInit(
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

internal fun CppDeclarationRenderer.buildConstructorInitializerList(
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

internal fun CppDeclarationRenderer.renderSecondaryConstructors(
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

internal fun CppDeclarationRenderer.extractTemplateArgFromType(cppType: String): String {
    val lt = cppType.indexOf('<')
    val gt = cppType.lastIndexOf('>')
    return if (lt >= 0 && gt > lt) cppType.substring(lt + 1, gt).trim() else "std::any"
}

internal fun CppDeclarationRenderer.collectReachableSameFileSuperNames(
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

internal fun CppDeclarationRenderer.renderSiblingBaseInterfaceForwarding(
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

internal fun CppDeclarationRenderer.renderOverloadedVirtualUsings(
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

internal fun CppDeclarationRenderer.renderDelegatedInterfaceForwardingMethods(
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

internal fun CppDeclarationRenderer.ensureCreateTestResultReturn(
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

internal data class AtomicFieldRenderSpec(
    val elemType: String,
    val atomicTemplateName: String,
    val useAtomicRefHolder: Boolean,
)

internal fun CppDeclarationRenderer.resolveNonTrivialAtomicElemType(
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

internal fun CppDeclarationRenderer.resolveAtomicFieldRenderSpec(
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

internal fun CppDeclarationRenderer.resolveComparableElemType(
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

internal fun CppDeclarationRenderer.renderComparableClassHelpers(
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

internal fun CppDeclarationRenderer.computeNormalizedOverloadKey(
    methodName: String,
    normTypes: List<String>,
    isConstMethod: Boolean,
): String = "$methodName(${normTypes.joinToString(",")})|const=$isConstMethod"

internal fun CppDeclarationRenderer.collectScopeNormalizedSigs(
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

internal fun CppDeclarationRenderer.collectClassScopeNormalizedSigs(cls: KtClassSymbol): MutableSet<String> {
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

internal fun CppDeclarationRenderer.isBackedOverrideCtorParam(
    cls: KtClassSymbol,
    cp: KtValueParamInfo,
): Boolean {
    if (cls.kind == KtClassKind.INTERFACE) return false
    if (!cp.isVal && !cp.isVar) return false
    val cpFlags = stream.frames[cp.frameIndex].flags
    return PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_OVERRIDE) &&
        !PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_ABSTRACT)
}

internal fun CppDeclarationRenderer.isBackedOverrideMemberProp(
    enclosingClass: KtClassSymbol,
    @Suppress("UNUSED_PARAMETER") propFrame: PacketFrame,
    sym: KtPropertySymbol,
): Boolean {
    if (enclosingClass.kind == KtClassKind.INTERFACE) return false
    if (sym.isConst || hasCustomGetterBody(sym)) return false
    return PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_OVERRIDE) &&
        !PacketFlags.hasFlag(sym.flags, PacketFlags.MOD_ABSTRACT)
}

internal fun CppDeclarationRenderer.classInheritsContinuation(
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

internal fun CppDeclarationRenderer.resolveOverridePropertyGetterName(
    enclosingClass: KtClassSymbol?,
    kotlinName: String,
    defaultCppName: String,
): String =
    if (kotlinName == "context" && classInheritsContinuation(enclosingClass)) {
        "get_context"
    } else {
        defaultCppName
    }

internal fun CppDeclarationRenderer.collectEnclosingClassTypeParamNames(
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

internal fun CppDeclarationRenderer.resolveReceiverTypeForReturnInference(
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

internal fun CppDeclarationRenderer.isMutableExtensionReceiverCppType(cppType: String): Boolean {
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

internal fun CppDeclarationRenderer.extensionFunctionMutatesVectorReceiver(
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

internal fun CppDeclarationRenderer.formatExtensionReceiverPassType(
    rType: String,
    mutatesVectorReceiver: Boolean = false,
): String =
    when {
        rType in VALUE_PASS_CPP_TYPES || rType.endsWith("&") || rType.endsWith("*") -> rType
        isMutableExtensionReceiverCppType(rType) -> "$rType&"
        mutatesVectorReceiver && rType.trim().startsWith("std::vector<") -> "$rType&"
        else -> "const $rType&"
    }

internal fun CppDeclarationRenderer.resolveCollapsedVarianceEscapeTypeParam(
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

internal fun CppDeclarationRenderer.replaceWholeIdentifier(
    text: String,
    targetIdent: String,
    replacement: String,
): String {
    if (targetIdent.isEmpty() || text.isEmpty()) return text
    return Regex("""(?<![A-Za-z0-9_:])${Regex.escape(targetIdent)}(?![A-Za-z0-9_])""")
        .replace(text, Regex.escapeReplacement(replacement))
}

internal fun CppDeclarationRenderer.isDeprecatedHiddenFun(sym: KtFunctionSymbol): Boolean =
    sym.annotations.any { ann ->
        ann.simpleName == "Deprecated" &&
            ann.rawArgumentTexts.any {
                it.contains("DeprecationLevel.HIDDEN") || it.replace(" ", "").contains("level=HIDDEN")
            }
    }

internal fun CppDeclarationRenderer.hasStdRuntimeErrorBase(cls: KtClassSymbol): Boolean =
    cls.superTypeFrameIndices.any { trIdx ->
        bundle.typeTranslator.translateSuperBaseType(stream.frames[trIdx]).second
    }

internal fun CppDeclarationRenderer.isCancellationExceptionSubclass(cls: KtClassSymbol): Boolean =
    cls.kotlinName.endsWith("CancellationException") ||
        cls.superTypeFrameIndices.any { trIdx ->
            FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx])?.endsWith("CancellationException") == true
        }

internal fun CppDeclarationRenderer.isPostIncludeCycleClass(cls: KtClassSymbol): Boolean =
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

internal fun CppDeclarationRenderer.bodyReferencesExpectOrDeferredBottomHeader(frame: PacketFrame): Boolean {
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
