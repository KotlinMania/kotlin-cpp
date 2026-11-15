package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.ARITHMETIC_BINARY_OPERATOR_IDS
import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.ASSIGN_OPERATOR_IDS
import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.BITWISE_INFIX_NAMES
import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.BOOL_BINARY_OPERATOR_IDS
import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.KNOWN_TO_STRING_BASE_CLASSES
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal fun CppDeclarationRenderer.resolveCppFunctionOrOperatorName(
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

internal fun CppDeclarationRenderer.shouldOmitInstanceFieldConst(
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

internal fun CppDeclarationRenderer.ensureNoreturnUnreachable(
    body: String,
    isNoreturn: Boolean,
    indent: String,
): String {
    if (!isNoreturn || !body.endsWith("}")) return body
    val tokenStream = CppTokenStream.tokenizeCppCode(body)
    if (!tokenStream.endsWithPunct("}")) return body
    if (tokenStream.containsIdent("__builtin_unreachable") ||
        (tokenStream.containsIdent("std") && tokenStream.containsIdent("unreachable"))
    ) {
        return body
    }
    val trimmedEnd = body.dropLast(1).trimEnd()
    return when {
        trimmedEnd == "{" -> "{ __builtin_unreachable(); }"
        !body.contains('\n') -> "$trimmedEnd __builtin_unreachable(); }"
        else -> "$trimmedEnd\n$indent    __builtin_unreachable();\n$indent}"
    }
}

internal fun CppDeclarationRenderer.extractSuperTypeArgList(superTypeFrame: PacketFrame): List<String> {
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

internal fun CppDeclarationRenderer.buildSuperTypeArgMap(
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

internal fun CppDeclarationRenderer.resolveSuperMethodReturnType(
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
                    }.let { CppType.parseInternal(it).stripNoReturn().render() }
                val subst =
                    CppTokenStream
                        .tokenizeCppCode(rawRet)
                        .renameIdents(argMap, skipMemberAccessRhs = true)
                        .render()
                if (subst != "auto" && (allowVoid || subst != "void")) {
                    return subst
                }
            }
            val deeper = resolveSuperMethodReturnType(sameSuper, sym, allowVoid, visited)
            if (deeper != null) {
                return CppTokenStream
                    .tokenizeCppCode(deeper)
                    .renameIdents(argMap, skipMemberAccessRhs = true)
                    .render()
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

internal fun CppDeclarationRenderer.shouldMarkMethodConst(
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

internal fun CppDeclarationRenderer.mutatesMemberField(
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

internal fun CppDeclarationRenderer.extractAssignedMemberSimpleName(lhsFrame: PacketFrame): String? =
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

internal fun CppDeclarationRenderer.extractPropertyGetterBodyFrame(sym: KtPropertySymbol): PacketFrame? {
    val gfIdx = sym.getterFrameIndex ?: return null
    val accFrame = stream.frames[gfIdx]
    return FrameAstHelpers.firstChildFrame(stream, accFrame, KtNodeTypes.BLOCK_ID)
        ?: FrameAstHelpers.childFrames(stream, accFrame).lastOrNull { child ->
            child.nodeTypeId != KtNodeTypes.VALUE_PARAMETER_LIST_ID &&
                child.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID &&
                child.nodeTypeId != KtNodeTypes.TYPE_REFERENCE_ID
        }
}

internal fun CppDeclarationRenderer.shouldMarkPropertyGetterConst(
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

internal fun CppDeclarationRenderer.callsNonConstEnclosingMember(
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

internal fun CppDeclarationRenderer.containsWholeWord(
    text: String,
    word: String,
): Boolean = CppTokenStream.tokenizeCppCode(text).containsIdent(word)

internal fun CppDeclarationRenderer.superDeclaresMethod(
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

internal fun CppDeclarationRenderer.inferFunctionExpressionBodyReturnType(
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

internal fun CppDeclarationRenderer.inferExpressionBodyReturnType(
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

internal fun CppDeclarationRenderer.inferCallExpressionBodyReturnType(exprFrame: PacketFrame): String {
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

internal fun CppDeclarationRenderer.isUsedAsSuperTypeInSameFile(cls: KtClassSymbol): Boolean =
    symbols.classesByFrame.values.any { other ->
        other.frameIndex != cls.frameIndex &&
            !FrameAstHelpers.hasModifierKeyword(stream, stream.frames[other.frameIndex], KtTokens.EXPECT_MODIFIER_ID) &&
            other.superTypeFrameIndices.any { stIdx ->
                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[stIdx]) == cls.kotlinName
            }
    }

internal fun CppDeclarationRenderer.isReferencedByNonExpectInSameFile(cls: KtClassSymbol): Boolean {
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

internal fun CppDeclarationRenderer.isOmittedExpectClass(cls: KtClassSymbol): Boolean {
    if (cls.kotlinName == "TestResult" || cls.kotlinName == "StackTraceElement") return false
    val declFrame = stream.frames[cls.frameIndex]
    if (!FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID)) return false
    if (cls.kind == KtClassKind.ANNOTATION_CLASS) return false
    return !isUsedAsSuperTypeInSameFile(cls) && !isReferencedByNonExpectInSameFile(cls)
}

internal fun CppDeclarationRenderer.shouldEmitPrivateClassOnlyInCpp(cls: KtClassSymbol): Boolean =
    shouldEmitPrivateClassOnlyInCppRec(cls, HashSet())

internal fun CppDeclarationRenderer.shouldEmitPrivateClassOnlyInCppRec(
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

internal fun CppDeclarationRenderer.renderPrivateClassDefinitionForCpp(
    declFrame: PacketFrame,
    cls: KtClassSymbol,
): String =
    when (cls.kind) {
        KtClassKind.VALUE_CLASS -> renderValueClass(declFrame, cls, "", "")
        KtClassKind.DATA_CLASS -> renderDataClass(declFrame, cls, "", "")
        else -> renderGeneralClass(declFrame, cls, "", "")
    }
