package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal class LoweringBundle(
    val stream: PacketStream,
    val config: CppTargetConfig,
    val symbols: SameFileSymbolTable,
    val suspendPlan: SuspendLoweringPlan,
    val gcPlan: GcAndOwnershipPlan,
    val atomicPlan: AtomicAndThreadLocalPlan,
    val ffiPlan: FfiAndCTypePlan,
    val cfPlan: ControlFlowAndTypePlan,
    val includes: CppIncludeCollector,
    val typeTranslator: CppTypeTranslator,
    val knownFunctionNames: Set<String> = emptySet(),
    val isDocExample: Boolean = false,
)

/**
 * Emits C++23 statements and expressions from lowered [PacketFrame] trees while preserving
 * interior comments, left-to-right `hasSuspendCallInTail` temporaries, non-IIFE suspend
 * control-flow result slots, `atomicfu` operations, `cinterop` idioms, and `KONAN_GC` barriers.
 */
internal class InlinedCallerLambdaSite(
    val lambdaBodyFrame: PacketFrame,
    val lambdaParamNames: List<String>,
    val callerEmitter: CppExprAndStmtEmitter,
)

internal class CppExprAndStmtEmitter(
    internal val bundle: LoweringBundle,
    internal val activeTypeParams: Set<String> = emptySet(),
    internal val enclosingClass: KtClassSymbol? = null,
    internal val enclosingFunction: KtFunctionSymbol? = null,
    internal var receiverSubstitution: String? = null,
    internal var extensionReceiverClass: KtClassSymbol? = null,
    internal var extensionReceiverIsPointer: Boolean = false,
    internal var extensionReceiverSimpleName: String? = null,
    internal val unitReturnExprOverride: String? = null,
    internal val isVoidReturnContext: Boolean = false,
    internal val enclosingFunctionReturnType: String? = null,
    internal val inlinedLambdaSubstitutions: Map<String, InlinedCallerLambdaSite> = emptyMap(),
    internal val inlineReturnTargetVar: String? = null,
    internal val inlineReturnExitLabel: String? = null,
    internal val localPolymorphicClasses: MutableSet<String> = LinkedHashSet(),
    internal val localExtensionFunctions: MutableMap<String, String> = LinkedHashMap(),
    internal val inDefaultParameterExpr: Boolean = false,
    internal val activeInlineFunFrames: Set<Int> = emptySet(),
    internal val isSuspendBody: Boolean = false,
    internal val suspendBoxType: String? = null,
    internal val wrapReturnInMakeSharedOf: String? = null,
    internal val expectedTargetType: String? = null,
    internal val anonObjectOwnMethods: Set<String> = emptySet(),
    internal val anonObjectOwnProps: Set<String> = emptySet(),
) {
    var usedInlineReturnExitLabel: Boolean = false
    internal val stream: PacketStream get() = bundle.stream
    internal val includes: CppIncludeCollector get() = bundle.includes
    internal val localBindings = ArrayDeque<MutableMap<String, String>>()
    internal val nonCallableCtorParamNames = LinkedHashSet<String>()
    internal val exceptionPtrScopes = ArrayDeque<MutableSet<String>>()
    internal val stdAnyScopes = ArrayDeque<MutableSet<String>>()
    internal val localPolyVarScopes = ArrayDeque<MutableSet<String>>()
    internal val localTypeScopes = ArrayDeque<MutableMap<String, String>>()
    internal val activeNarrowedScopes = ArrayDeque<MutableSet<String>>()
    internal val callableReceiverParamScopes = ArrayDeque<MutableMap<String, Int>>()
    internal val preLoweredElvisExprs = HashMap<Int, String>()
    internal var synthCounter = 0
    internal var inExpressionIifeDepth = 0
    internal var currentExpectedTargetType: String? = expectedTargetType
    internal var sequenceYieldTargetVar: String? = null
    internal val isOuterSuspendReturnContext: Boolean
        get() = isSuspendBody && inExpressionIifeDepth == 0

    internal inline fun <T> withinExpressionIife(block: () -> T): T {
        inExpressionIifeDepth += 1
        return try {
            block()
        } finally {
            inExpressionIifeDepth -= 1
        }
    }

    internal fun iifeAnyRetSuffix(): String =
        if (currentExpectedTargetType == "std::any") {
            includes.addSystem("any")
            " -> std::any"
        } else {
            ""
        }

    internal inline fun <T> withExpectedTargetType(
        target: String?,
        block: () -> T,
    ): T {
        val prev = currentExpectedTargetType
        currentExpectedTargetType = target
        return try {
            block()
        } finally {
            currentExpectedTargetType = prev
        }
    }

    internal fun translateType(
        typeFrame: PacketFrame,
        position: CppTypePosition,
        extraFlags: Long = PacketFlags.NONE,
    ): String =
        bundle.typeTranslator.translateTypeFrame(
            typeFrame,
            position,
            extraFlags = extraFlags,
            enclosingClassName = enclosingClass?.kotlinName,
        )

    init {
        localBindings.addLast(LinkedHashMap())
        exceptionPtrScopes.addLast(LinkedHashSet())
        stdAnyScopes.addLast(LinkedHashSet())
        localPolyVarScopes.addLast(LinkedHashSet())
        localTypeScopes.addLast(LinkedHashMap())
        activeNarrowedScopes.addLast(LinkedHashSet())
        callableReceiverParamScopes.addLast(LinkedHashMap())
    }

    fun bindLocalName(
        kotlinName: String,
        cppExpr: String,
    ) {
        localBindings.last()[kotlinName] = cppExpr
    }

    internal fun isFunctionOrCallableTypeFrame(typeFrameIdx: Int?): Boolean {
        if (typeFrameIdx == null) return false
        var tf = stream.frames[typeFrameIdx]
        while (tf.nodeTypeId == KtNodeTypes.TYPE_REFERENCE_ID ||
            tf.nodeTypeId == KtNodeTypes.NULLABLE_TYPE_ID ||
            tf.nodeTypeId == KtNodeTypes.TYPE_PROJECTION_ID
        ) {
            val next =
                FrameAstHelpers.childFrames(stream, tf).firstOrNull {
                    it.nodeTypeId == KtNodeTypes.FUNCTION_TYPE_ID ||
                        it.nodeTypeId == KtNodeTypes.USER_TYPE_ID ||
                        it.nodeTypeId == KtNodeTypes.NULLABLE_TYPE_ID ||
                        it.nodeTypeId == KtNodeTypes.TYPE_REFERENCE_ID
                } ?: break
            tf = next
        }
        if (tf.nodeTypeId == KtNodeTypes.FUNCTION_TYPE_ID) return true
        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf) ?: return false
        val sameFileAlias =
            bundle.symbols.typeAliasesByFrame.values
                .firstOrNull { it.kotlinName == simple }
        if (sameFileAlias != null) {
            return isFunctionOrCallableTypeFrame(sameFileAlias.underlyingTypeFrameIndex)
        }
        return simple in KNOWN_CALLABLE_TYPEALIASES
    }

    fun bindConstructorParam(
        cp: KtValueParamInfo,
        suffix: String,
    ) {
        bindLocalName(cp.name, "${cp.cppName}$suffix")
        if (!isFunctionOrCallableTypeFrame(cp.typeFrameIndex)) {
            nonCallableCtorParamNames.add(cp.name)
        }
        cp.typeFrameIndex?.let { tfIdx ->
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[tfIdx])
            if (simple != null) markLocalType(cp.name, simple)
        }
    }

    fun bindValueParam(
        vp: KtValueParamInfo,
        forcePolyPointer: Boolean = false,
    ) {
        bindLocalName(vp.name, vp.cppName)
        if (forcePolyPointer) {
            localPolyVarScopes.last().add(vp.name)
            return
        }
        val tfIdx = vp.typeFrameIndex ?: return
        val tf = stream.frames[tfIdx]
        if (extractFunctionTypeReceiverRef(tf) != null) {
            callableReceiverParamScopes.last()[vp.name] = extractFunctionTypeValueParamCount(tf)
        }
        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf)
        if (simple != null) markLocalType(vp.name, simple)
        if (simple == "Any") {
            stdAnyScopes.last().add(vp.name)
        } else if (simple in EXCEPTION_PTR_KOTLIN_TYPES) {
            exceptionPtrScopes.last().add(vp.name)
        } else {
            val effSimple = simple?.let { bundle.symbols.importAliases[it] ?: it }
            val cppT =
                translateType(tf, CppTypePosition.PARAMETER)
                    .removePrefix("const ")
                    .removeSuffix("&")
                    .trim()
            if (cppT.endsWith("*") ||
                cppT.startsWith("std::shared_ptr<") ||
                (effSimple != null && effSimple in bundle.symbols.sharedPtrTypeAliases)
            ) {
                localPolyVarScopes.last().add(vp.name)
            }
        }
    }

    internal fun markLocalType(
        kotlinName: String,
        simpleTypeName: String,
    ) {
        localTypeScopes.last()[kotlinName] = simpleTypeName
    }

    internal fun resolveLocalTypeSimpleName(kotlinName: String): String? {
        for (i in localTypeScopes.size - 1 downTo 0) {
            localTypeScopes[i][kotlinName]?.let { return it }
        }
        return null
    }

    internal fun resolveClassOrTypeParamBound(
        simpleName: String,
        visited: MutableSet<String> = HashSet(),
    ): KtClassSymbol? {
        if (!visited.add(simpleName)) return null
        lookupAccessibleClassByName(simpleName)?.let { return it }
        var curCls: KtClassSymbol? = enclosingClass
        while (curCls != null) {
            val ub = curCls.typeParams.firstOrNull { it.name == simpleName }?.upperBoundFrameIndex
            if (ub != null) {
                val ubSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[ub])
                if (ubSimple != null) {
                    resolveClassOrTypeParamBound(ubSimple, visited)?.let { return it }
                }
            }
            curCls = curCls.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        for (fnIdx in activeInlineFunFrames) {
            val ub =
                bundle.symbols.functionsByFrame[fnIdx]
                    ?.typeParams
                    ?.firstOrNull { it.name == simpleName }
                    ?.upperBoundFrameIndex
            if (ub != null) {
                val ubSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[ub])
                if (ubSimple != null) {
                    resolveClassOrTypeParamBound(ubSimple, visited)?.let { return it }
                }
            }
        }
        if (simpleName in activeTypeParams) {
            val ownerCands =
                enclosingClass
                    ?.memberFunctionFrameIndices
                    .orEmpty()
                    .asSequence()
                    .mapNotNull { bundle.symbols.functionsByFrame[it] }
            for (fn in ownerCands + bundle.symbols.functionsByFrame.values) {
                val ub = fn.typeParams.firstOrNull { it.name == simpleName }?.upperBoundFrameIndex ?: continue
                val ubSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[ub]) ?: continue
                resolveClassOrTypeParamBound(ubSimple, visited)?.let { return it }
            }
        }
        return null
    }

    internal fun findClassHierarchyProperty(
        cls: KtClassSymbol,
        propName: String,
        visited: MutableSet<Int> = HashSet(),
    ): Pair<KtClassSymbol, KtPropertySymbol?>? {
        if (!visited.add(cls.frameIndex)) return null
        val ctorProp = cls.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == propName }
        if (ctorProp != null) return cls to null
        for (pIdx in cls.memberPropertyFrameIndices) {
            val mp = bundle.symbols.propertiesByFrame[pIdx] ?: continue
            if (mp.kotlinName == propName) return cls to mp
        }
        for (superIdx in cls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            val superCls = lookupAccessibleClassByName(superSimple) ?: continue
            findClassHierarchyProperty(superCls, propName, visited)?.let { return it }
        }
        return null
    }

    internal fun findClassHierarchyPropertyTypeFrameIndex(
        cls: KtClassSymbol,
        propName: String,
        visited: MutableSet<Int> = HashSet(),
    ): Int? {
        if (!visited.add(cls.frameIndex)) return null
        val ctorProp = cls.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == propName }
        if (ctorProp?.typeFrameIndex != null) return ctorProp.typeFrameIndex
        for (pIdx in cls.memberPropertyFrameIndices) {
            val mp = bundle.symbols.propertiesByFrame[pIdx] ?: continue
            if (mp.kotlinName == propName && mp.returnTypeFrameIndex != null) return mp.returnTypeFrameIndex
        }
        for (superIdx in cls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            val superCls = lookupAccessibleClassByName(superSimple) ?: continue
            findClassHierarchyPropertyTypeFrameIndex(superCls, propName, visited)?.let { return it }
        }
        return null
    }

    internal fun findClassHierarchyFunction(
        cls: KtClassSymbol,
        fnName: String,
        argCount: Int? = null,
        visited: MutableSet<Int> = HashSet(),
    ): KtFunctionSymbol? {
        if (!visited.add(cls.frameIndex)) return null
        val own =
            cls.memberFunctionFrameIndices
                .mapNotNull { bundle.symbols.functionsByFrame[it] }
                .filter { it.kotlinName == fnName }
        val ownMatch =
            if (argCount != null) {
                own.firstOrNull { it.valueParams.size == argCount } ?: own.firstOrNull()
            } else {
                own.firstOrNull()
            }
        if (ownMatch != null) return ownMatch
        for (superIdx in cls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            val superCls = lookupAccessibleClassByName(superSimple) ?: continue
            findClassHierarchyFunction(superCls, fnName, argCount, visited)?.let { return it }
        }
        return null
    }

    internal fun classDerivesFrom(
        cls: KtClassSymbol,
        targetSimpleName: String,
        visited: MutableSet<String> = LinkedHashSet(),
    ): Boolean {
        if (cls.kotlinName == targetSimpleName) return true
        if (!visited.add(cls.kotlinName)) return false
        for (superIdx in cls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            if (superSimple == targetSimpleName) return true
            val superCls = lookupAccessibleClassByName(superSimple) ?: continue
            if (classDerivesFrom(superCls, targetSimpleName, visited)) return true
        }
        return false
    }

    internal fun extractBranchTailExpr(wrapper: PacketFrame?): PacketFrame? {
        val child = wrapper?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() } ?: return null
        val u = unwrapTransparentFrame(child)
        return if (u.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            FrameAstHelpers.childFrames(stream, u).lastOrNull()?.let { unwrapTransparentFrame(it) }
        } else {
            u
        }
    }

    internal fun extractFunctionTypeNode(typeFrame: PacketFrame): PacketFrame? {
        var cur: PacketFrame? = typeFrame
        while (cur != null) {
            if (cur.nodeTypeId == KtNodeTypes.FUNCTION_TYPE_ID) return cur
            cur =
                FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.FUNCTION_TYPE_ID)
                    ?: FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.NULLABLE_TYPE_ID)
                    ?: FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.TYPE_REFERENCE_ID)
        }
        return null
    }

    internal fun extractFunctionTypeReceiverRef(typeFrame: PacketFrame): PacketFrame? {
        val fnType = extractFunctionTypeNode(typeFrame) ?: return null
        val recvWrap = FrameAstHelpers.firstChildFrame(stream, fnType, KtNodeTypes.FUNCTION_TYPE_RECEIVER_ID)
        return recvWrap?.let {
            FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_REFERENCE_ID)
                ?: FrameAstHelpers.childFrames(stream, it).firstOrNull()
        }
    }

    internal fun extractFunctionTypeValueParamCount(typeFrame: PacketFrame): Int {
        val fnType = extractFunctionTypeNode(typeFrame) ?: return 0
        val pList = FrameAstHelpers.firstChildFrame(stream, fnType, KtNodeTypes.VALUE_PARAMETER_LIST_ID) ?: return 0
        return FrameAstHelpers.childFramesOfType(stream, pList, KtNodeTypes.VALUE_PARAMETER_ID).size
    }

    internal fun resolveAtomicFieldForExpr(exprFrame: PacketFrame): AtomicFieldInfo? {
        val u = unwrapTransparentFrame(exprFrame)
        val propKt =
            when (u.nodeTypeId) {
                KtNodeTypes.REFERENCE_EXPRESSION_ID ->
                    FrameAstHelpers.directIdentPacket(stream, u)?.unescapedName
                KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                    val ch = FrameAstHelpers.childFrames(stream, u)
                    if (ch.size >= 2) {
                        val sel = unwrapTransparentFrame(ch[1])
                        if (sel.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                            FrameAstHelpers.directIdentPacket(stream, sel)?.unescapedName
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
                else -> null
            } ?: return null
        val ownerCls =
            if (u.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
                val recvF = FrameAstHelpers.childFrames(stream, u).firstOrNull()
                recvF?.let { inferExpressionSimpleTypeName(it) }?.let { resolveClassOrTypeParamBound(it) }
            } else {
                enclosingClass
            }
        var curCls = ownerCls
        while (curCls != null) {
            val hit = findClassHierarchyProperty(curCls, propKt)?.second
            if (hit != null) {
                bundle.atomicPlan.atomicFieldsByPropFrame[hit.frameIndex]?.let { return it }
            }
            if (!curCls.isInner) break
            curCls = curCls.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        bundle.symbols.propertiesByName[propKt]?.let { prop ->
            bundle.atomicPlan.atomicFieldsByPropFrame[prop.frameIndex]?.let { return it }
        }
        return bundle.atomicPlan.atomicFieldsByPropFrame.values.firstOrNull { af ->
            bundle.symbols.propertiesByFrame[af.propFrameIndex]?.kotlinName == propKt
        }
    }

    internal fun inferExpressionSimpleTypeName(exprFrame: PacketFrame): String? {
        val unwrapped = unwrapTransparentFrame(exprFrame)
        return when (unwrapped.nodeTypeId) {
            KtNodeTypes.BOOLEAN_CONSTANT_ID -> "Boolean"
            KtNodeTypes.CHARACTER_CONSTANT_ID -> "Char"
            KtNodeTypes.STRING_TEMPLATE_ID -> "String"
            KtNodeTypes.FLOAT_CONSTANT_ID -> {
                val pkt = stream.packets.getOrNull(unwrapped.firstPacketIndex) as? SyntaxPacket.LiteralPacket
                if (pkt?.numericWidthBits == 32) "Float" else "Double"
            }
            KtNodeTypes.INTEGER_CONSTANT_ID -> {
                val pkt = stream.packets.getOrNull(unwrapped.firstPacketIndex) as? SyntaxPacket.LiteralPacket
                when {
                    pkt?.isUnsigned == true && pkt.numericWidthBits == 64 -> "ULong"
                    pkt?.isUnsigned == true -> "UInt"
                    pkt?.numericWidthBits == 64 -> "Long"
                    else -> "Int"
                }
            }
            KtNodeTypes.THIS_EXPRESSION_ID -> {
                val extRecv = extensionReceiverSimpleName
                if (extRecv != null && receiverSubstitution == "self") {
                    extRecv
                } else {
                    enclosingClass?.kotlinName
                }
            }
            KtNodeTypes.SUPER_EXPRESSION_ID -> resolvePrimarySuperSimpleAndCpp(unwrapped).first
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val name = FrameAstHelpers.directIdentPacket(stream, unwrapped)?.unescapedName ?: return null
                resolveLocalTypeSimpleName(name)?.let { return it }
                var cur: KtClassSymbol? = enclosingClass
                while (cur != null) {
                    findClassHierarchyPropertyTypeFrameIndex(cur, name)?.let { tfIdx ->
                        return FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[tfIdx])
                    }
                    if (!cur.isInner) break
                    cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
                }
                bundle.symbols.propertiesByName[name]?.returnTypeFrameIndex?.let {
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                }
            }
            KtNodeTypes.POSTFIX_EXPRESSION_ID -> {
                val opRef = FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.OPERATION_REFERENCE_ID)
                if (opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.EXCLEXCL_ID) {
                    val operand =
                        FrameAstHelpers
                            .childFrames(stream, unwrapped)
                            .firstOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
                    operand?.let { inferExpressionSimpleTypeName(it) }
                } else {
                    null
                }
            }
            KtNodeTypes.CALL_EXPRESSION_ID -> {
                val callee = extractCalleeSimpleName(unwrapped)
                val eff = bundle.symbols.importAliases[callee] ?: callee
                if (callee == "StringBuilder") {
                    return "StringBuilder"
                }
                if (lookupAccessibleClassByName(callee) != null || lookupAccessibleClassByName(eff) != null) {
                    return eff
                }
                val argCount = extractCallArgFrames(unwrapped).size
                val memberFn = enclosingClass?.let { findClassHierarchyFunction(it, callee, argCount) }
                val candidateFn =
                    memberFn
                        ?: bundle.symbols.functionsByName[callee]
                            ?.let { fns -> fns.firstOrNull { it.valueParams.size == argCount } ?: fns.firstOrNull() }
                        ?: bundle.symbols.functionsByName[eff]
                            ?.let { fns -> fns.firstOrNull { it.valueParams.size == argCount } ?: fns.firstOrNull() }
                candidateFn?.returnTypeFrameIndex?.let {
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                }
            }
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID,
            -> {
                val children = FrameAstHelpers.childFrames(stream, unwrapped)
                if (children.size < 2) return null
                val recvF = unwrapTransparentFrame(children[0])
                val selF = unwrapTransparentFrame(children[1])
                val staticCls =
                    if (recvF.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        val ident = FrameAstHelpers.directIdentPacket(stream, recvF)?.unescapedName
                        ident?.let { lookupAccessibleClassByName(bundle.symbols.importAliases[it] ?: it) }
                    } else {
                        null
                    }
                val recvCls = staticCls ?: inferExpressionSimpleTypeName(recvF)?.let { resolveClassOrTypeParamBound(it) }
                when (selF.nodeTypeId) {
                    KtNodeTypes.CALL_EXPRESSION_ID -> {
                        val selCallee = extractCalleeSimpleName(selF)
                        if (selCallee == "apply" || selCallee == "also") {
                            return inferExpressionSimpleTypeName(recvF)
                        }
                        val recvIdent =
                            if (recvF.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                                FrameAstHelpers.directIdentPacket(stream, recvF)?.unescapedName
                            } else {
                                null
                            }
                        if (selCallee == "start" && (staticCls?.kotlinName == "Worker" || recvIdent == "Worker")) {
                            return "Worker"
                        }
                        if (selCallee == "StringBuilder") {
                            return "StringBuilder"
                        }
                        val effSel = bundle.symbols.importAliases[selCallee] ?: selCallee
                        val argCount = extractCallArgFrames(selF).size
                        val compCls =
                            if (staticCls != null) {
                                staticCls.companionFrameIndex?.let { bundle.symbols.classesByFrame[it] } ?: staticCls
                            } else {
                                null
                            }
                        val ownerFn =
                            compCls?.let { findClassHierarchyFunction(it, selCallee, argCount) }
                                ?: recvCls?.let { findClassHierarchyFunction(it, selCallee, argCount) }
                        if (ownerFn?.returnTypeFrameIndex != null) {
                            return FrameAstHelpers.extractPrimaryTypeSimpleName(
                                stream,
                                stream.frames[ownerFn.returnTypeFrameIndex],
                            )
                        }
                        val extFn =
                            bundle.symbols.functionsByName[selCallee]
                                ?.firstOrNull { it.receiverTypeFrameIndex != null && isAccessibleExtensionFn(it) }
                                ?: bundle.symbols.functionsByName[selCallee]
                                    ?.firstOrNull { it.returnTypeFrameIndex != null }
                        if (extFn?.returnTypeFrameIndex != null) {
                            return FrameAstHelpers.extractPrimaryTypeSimpleName(
                                stream,
                                stream.frames[extFn.returnTypeFrameIndex],
                            )
                        }
                        if (lookupAccessibleClassByName(selCallee) != null ||
                            lookupAccessibleClassByName(effSel) != null
                        ) {
                            effSel
                        } else {
                            null
                        }
                    }
                    KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                        val propKt = FrameAstHelpers.directIdentPacket(stream, selF)?.unescapedName ?: return null
                        if (propKt == "value") {
                            resolveAtomicFieldForExpr(recvF)?.let { af ->
                                if (af.explicitElementTypeFrameIndex != null) {
                                    return FrameAstHelpers.extractPrimaryTypeSimpleName(
                                        stream,
                                        stream.frames[af.explicitElementTypeFrameIndex],
                                    )
                                }
                                if (af.initExprFrameIndex != null) {
                                    return inferExpressionSimpleTypeName(stream.frames[af.initExprFrameIndex])
                                }
                            }
                        }
                        if (recvCls != null) {
                            findClassHierarchyPropertyTypeFrameIndex(recvCls, propKt)?.let { tfIdx ->
                                return FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[tfIdx])
                            }
                        }
                        val extProp = bundle.symbols.propertiesByName[propKt]
                        if (extProp != null && extProp.receiverTypeFrameIndex != null && extProp.returnTypeFrameIndex != null) {
                            return FrameAstHelpers.extractPrimaryTypeSimpleName(
                                stream,
                                stream.frames[extProp.returnTypeFrameIndex],
                            )
                        }
                        null
                    }
                    else -> null
                }
            }
            KtNodeTypes.IF_ID -> {
                val thenExpr = extractBranchTailExpr(FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.THEN_ID))
                val elseExpr = extractBranchTailExpr(FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.ELSE_ID))
                val thenType = thenExpr?.let { inferExpressionSimpleTypeName(it) }
                val elseType = elseExpr?.let { inferExpressionSimpleTypeName(it) }
                when {
                    thenType != null && elseType != null -> {
                        val thenCls = lookupAccessibleClassByName(thenType)
                        val elseCls = lookupAccessibleClassByName(elseType)
                        when {
                            thenType == elseType -> thenType
                            thenCls != null && classDerivesFrom(thenCls, elseType) -> elseType
                            elseCls != null && classDerivesFrom(elseCls, thenType) -> thenType
                            else -> thenType
                        }
                    }
                    thenType != null -> thenType
                    else -> elseType
                }
            }
            else -> null
        }
    }

    internal fun markExceptionPtrVar(kotlinName: String) {
        exceptionPtrScopes.last().add(kotlinName)
    }

    internal fun markStdAnyVar(kotlinName: String) {
        stdAnyScopes.last().add(kotlinName)
    }

    internal fun unmarkStdAnyVar(kotlinName: String) {
        for (i in stdAnyScopes.size - 1 downTo 0) {
            stdAnyScopes[i].remove(kotlinName)
        }
    }

    internal fun markLocalPolyVar(kotlinName: String) {
        localPolyVarScopes.last().add(kotlinName)
    }

    internal fun isLocalPolyVar(kotlinName: String): Boolean {
        for (i in localPolyVarScopes.size - 1 downTo 0) {
            if (kotlinName in localPolyVarScopes[i]) return true
        }
        return false
    }

    internal fun isExceptionPtrKotlinVar(kotlinName: String): Boolean {
        val narrowedSimple = resolveLocalTypeSimpleName(kotlinName)
        if (narrowedSimple != null && narrowedSimple !in EXCEPTION_PTR_KOTLIN_TYPES) return false
        for (i in exceptionPtrScopes.size - 1 downTo 0) {
            if (kotlinName in exceptionPtrScopes[i]) return true
        }
        return false
    }

    internal fun isExceptionPtrVarByCppName(cppName: String): Boolean {
        val clean = cppName.trim()
        for (i in localBindings.size - 1 downTo 0) {
            for ((kt, boundCpp) in localBindings[i]) {
                if (boundCpp == clean && resolveBoundName(kt) == clean && isExceptionPtrKotlinVar(kt)) {
                    return true
                }
            }
        }
        val memberCpp = clean.removePrefix("this->")
        val enc = enclosingClass ?: return false
        for (cp in enc.primaryConstructorParams) {
            if (cp.cppName == memberCpp || CppNamingPolicy.toPrivateFieldName(cp.cppName) == memberCpp) {
                val simple =
                    cp.typeFrameIndex?.let {
                        FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                    }
                if (simple != null && simple in EXCEPTION_PTR_KOTLIN_TYPES) return true
            }
        }
        for (pIdx in enc.memberPropertyFrameIndices) {
            val prop = bundle.symbols.propertiesByFrame[pIdx] ?: continue
            if (prop.cppName == memberCpp || CppNamingPolicy.toPrivateFieldName(prop.cppName) == memberCpp) {
                val simple =
                    prop.returnTypeFrameIndex?.let {
                        FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                    }
                if (simple != null && simple in EXCEPTION_PTR_KOTLIN_TYPES) return true
            }
        }
        return false
    }

    internal fun isAlreadyExceptionPtrExprCode(code: String): Boolean {
        val t = code.trim()
        return t == "nullptr" ||
            t == "{}" ||
            t == "std::nullopt" ||
            t == "std::exception_ptr{}" ||
            t == "std::exception_ptr()" ||
            t == "COROUTINE_SUSPENDED" ||
            t.endsWith("::COROUTINE_SUSPENDED") ||
            t.startsWith("std::current_exception(") ||
            t.startsWith("std::make_exception_ptr(") ||
            t.startsWith("kxs_to_exception_ptr(") ||
            t.startsWith("throw ")
    }

    internal fun wrapWithExceptionPtrConversion(
        exprCode: String,
        exprFrame: PacketFrame? = null,
    ): String {
        val t = exprCode.trim()
        if (t == "nullptr" || t == "{}" || t == "std::nullopt") {
            return "{}"
        }
        if ((exprFrame != null && isExceptionPtrExpr(exprFrame)) ||
            isAlreadyExceptionPtrExprCode(t) ||
            isExceptionPtrVarByCppName(t)
        ) {
            return exprCode
        }
        includes.addSystem("exception")
        includes.needsResultHelpersDef = true
        return "kxs_to_exception_ptr($t)"
    }

    internal fun isStdAnyKotlinVar(kotlinName: String): Boolean {
        for (i in stdAnyScopes.size - 1 downTo 0) {
            if (kotlinName in stdAnyScopes[i]) return true
        }
        return false
    }

    internal fun isExceptionPtrExpr(exprFrame: PacketFrame): Boolean {
        val unwrapped = unwrapTransparentFrame(exprFrame)
        when (unwrapped.nodeTypeId) {
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val name = FrameAstHelpers.directIdentPacket(stream, unwrapped)?.unescapedName ?: return false
                if (isExceptionPtrKotlinVar(name)) return true
                val prop = bundle.symbols.propertiesByName[name]
                val propSimple =
                    prop?.returnTypeFrameIndex?.let {
                        FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                    }
                return propSimple in EXCEPTION_PTR_KOTLIN_TYPES
            }
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID,
            -> {
                val children = FrameAstHelpers.childFrames(stream, unwrapped)
                val selector = children.getOrNull(1)?.let { unwrapTransparentFrame(it) } ?: return false
                return selector.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                    FrameAstHelpers.directIdentPacket(stream, selector)?.unescapedName == "cause"
            }
            KtNodeTypes.CALL_EXPRESSION_ID -> {
                val calleeRef =
                    FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                val calleeName = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
                if (calleeName != "recoverStackTrace" && calleeName != "unwrap") return false
                val arg0 = extractCallArgFrames(unwrapped).firstOrNull() ?: return false
                return isExceptionPtrExpr(arg0)
            }
            else -> return false
        }
    }

    internal fun isStdAnyExpr(exprFrame: PacketFrame): Boolean {
        val unwrapped = unwrapTransparentFrame(exprFrame)
        if (unwrapped.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val name = FrameAstHelpers.directIdentPacket(stream, unwrapped)?.unescapedName ?: return false
            if (isStdAnyKotlinVar(name)) return true
            val prop = bundle.symbols.propertiesByName[name]
            val propSimple = prop?.returnTypeFrameIndex?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
            if (propSimple == "Any") return true
        }
        return false
    }

    internal fun <T> withLexicalScope(block: () -> T): T {
        localBindings.addLast(LinkedHashMap())
        exceptionPtrScopes.addLast(LinkedHashSet())
        stdAnyScopes.addLast(LinkedHashSet())
        localPolyVarScopes.addLast(LinkedHashSet())
        localTypeScopes.addLast(LinkedHashMap())
        activeNarrowedScopes.addLast(LinkedHashSet())
        callableReceiverParamScopes.addLast(LinkedHashMap())
        val savedRecvSub = receiverSubstitution
        val savedExtCls = extensionReceiverClass
        val savedExtPtr = extensionReceiverIsPointer
        val savedExtSimple = extensionReceiverSimpleName
        return try {
            block()
        } finally {
            localBindings.removeLast()
            exceptionPtrScopes.removeLast()
            stdAnyScopes.removeLast()
            localPolyVarScopes.removeLast()
            localTypeScopes.removeLast()
            activeNarrowedScopes.removeLast()
            callableReceiverParamScopes.removeLast()
            receiverSubstitution = savedRecvSub
            extensionReceiverClass = savedExtCls
            extensionReceiverIsPointer = savedExtPtr
            extensionReceiverSimpleName = savedExtSimple
        }
    }

    internal fun callableReceiverParamArity(kotlinName: String): Int? {
        for (i in callableReceiverParamScopes.indices.reversed()) {
            callableReceiverParamScopes[i][kotlinName]?.let { return it }
            if (kotlinName in localBindings[i]) return null
        }
        return null
    }

    internal fun resolveBoundName(kotlinName: String): String? {
        for (i in localBindings.size - 1 downTo 0) {
            localBindings[i][kotlinName]?.let { return it }
        }
        return null
    }

    internal fun resolveCallableBoundName(kotlinName: String): String? {
        if (kotlinName in nonCallableCtorParamNames) {
            val bound = resolveBoundName(kotlinName)
            if (bound != null && bound.endsWith("_value")) return null
        }
        return resolveBoundName(kotlinName)
    }

    internal fun freshTemp(prefix: String): String = "${prefix}_${synthCounter++}"

    internal fun wrapCopyableReturnIfNeeded(exprCode: String): String {
        val targetT = wrapReturnInMakeSharedOf ?: return exprCode
        val trimmed = exprCode.trim()
        if (trimmed == "nullptr" || trimmed == "{}") return "nullptr"
        includes.addSystem("memory")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
        return "([&](auto&& kxs_cc) -> std::shared_ptr<$targetT> { " +
            "if constexpr (std::is_null_pointer_v<std::remove_cvref_t<decltype(kxs_cc)>> || " +
            "requires { kxs_cc.operator->(); }) return kxs_cc; " +
            "else return std::make_shared<$targetT>(std::forward<decltype(kxs_cc)>(kxs_cc)); }($exprCode))"
    }

    internal fun defaultSuspendFallthroughReturnExpr(): String =
        if (suspendBoxType == "kotlin::Unit") {
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            "static_cast<void*>(new kotlin::Unit{})"
        } else {
            "nullptr"
        }

    internal fun wrapSuspendReturnValue(exprCode: String): String {
        if (!isOuterSuspendReturnContext) {
            val copyWrapped = wrapCopyableReturnIfNeeded(exprCode)
            val trimmedNonSuspend = copyWrapped.trim()
            if (enclosingFunctionReturnType?.endsWith("::Key*") == true &&
                trimmedNonSuspend.endsWith("::Key::instance()") &&
                !trimmedNonSuspend.startsWith("&") &&
                !trimmedNonSuspend.startsWith("std::addressof(")
            ) {
                includes.addSystem("memory")
                return "std::addressof($trimmedNonSuspend)"
            }
            return copyWrapped
        }
        val trimmed = exprCode.trim()
        if (trimmed == "COROUTINE_SUSPENDED" || trimmed.endsWith("::COROUTINE_SUSPENDED")) {
            return exprCode
        }
        val boxT = suspendBoxType
        if (boxT != null) {
            includes.needsSuspendBoxHelpersDef = true
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            if (trimmed == "Unit{}" || trimmed == "kotlin::Unit{}" || trimmed == "kotlinx::coroutines::Unit{}") {
                return "kxs_box_suspend_result<$boxT>(kotlin::Unit{})"
            }
            if (trimmed == "nullptr" || trimmed == "std::nullopt" || trimmed == "{}") {
                val parsedBoxT = CppType.parseInternal(boxT)
                return if (parsedBoxT.isOptional ||
                    parsedBoxT.isStdAny ||
                    parsedBoxT.isSharedPtr ||
                    parsedBoxT.isRawPtr
                ) {
                    "kxs_box_suspend_result<$boxT>($boxT{})"
                } else {
                    "nullptr"
                }
            }
            return "kxs_box_suspend_result<$boxT>($exprCode)"
        }
        if (enclosingFunctionReturnType == "void*") {
            return if (trimmed == "Unit{}" || trimmed == "kotlin::Unit{}" || trimmed == "kotlinx::coroutines::Unit{}") {
                "nullptr"
            } else {
                exprCode
            }
        }
        if (trimmed == "nullptr" ||
            trimmed == "Unit{}" ||
            trimmed == "kotlin::Unit{}" ||
            trimmed == "kotlinx::coroutines::Unit{}"
        ) {
            return "nullptr"
        }
        includes.needsSuspendBoxHelpersDef = true
        return "kxs_unit_suspend_result($exprCode)"
    }

    fun emitBlockOrExprBody(
        bodyFrame: PacketFrame,
        isExpressionBody: Boolean,
        isVoidReturn: Boolean,
        indent: String,
        extraEntryLines: List<String> = emptyList(),
    ): String {
        if (!isExpressionBody && bodyFrame.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            return emitBlock(
                blockFrame = bodyFrame,
                indent = indent,
                isValueBlock = false,
                resultTargetVar = null,
                extraEntryLines = extraEntryLines,
                isTopLevelSuspendBlock = isOuterSuspendReturnContext,
            )
        }
        return buildString {
            append("{\n")
            val innerIndent = "$indent    "
            for (line in extraEntryLines) {
                append(innerIndent).append(line).append('\n')
            }
            emitHoistedPrefixesForStmt(this, bodyFrame.frameIndex, innerIndent)
            if (isVoidReturn) {
                append(emitStatement(bodyFrame, innerIndent, inValuePosition = false, resultTargetVar = null))
                if (isOuterSuspendReturnContext &&
                    !doesBranchAlwaysTransferControl(bodyFrame) &&
                    !PacketFlags.hasFlag(bodyFrame.flags, PacketFlags.DIVERGING_EXPR)
                ) {
                    append(innerIndent).append("return ").append(defaultSuspendFallthroughReturnExpr()).append(";\n")
                }
            } else {
                append(emitValueAssignmentOrReturn(bodyFrame, innerIndent, targetReturn = true, targetVar = null))
            }
            append(indent).append('}')
        }
    }

    fun emitBlock(
        blockFrame: PacketFrame,
        indent: String,
        isValueBlock: Boolean,
        resultTargetVar: String?,
        extraEntryLines: List<String> = emptyList(),
        smartCasts: List<SmartCastBinding> = emptyList(),
        isTopLevelSuspendBlock: Boolean = false,
    ): String =
        withLexicalScope {
            buildString {
                append("{\n")
                val innerIndent = "$indent    "
                for (line in extraEntryLines) {
                    append(innerIndent).append(line).append('\n')
                }
                emitSmartCastBindings(this, smartCasts, innerIndent)

                val childStmts = ArrayList<PacketFrame>()
                for (i in 0 until blockFrame.childCount) {
                    val ref = blockFrame.childRefAt(i)
                    if (ref >= 0) {
                        childStmts.add(stream.frames[ref])
                    }
                }

                // Walk raw children of BLOCK_ID so interior comments between statements stay in exact source order!
                var stmtOrdinal = 0
                for (i in 0 until blockFrame.childCount) {
                    val ref = blockFrame.childRefAt(i)
                    if (ref < 0) {
                        val pkt = stream.packets[-(ref + 1)]
                        if (pkt is SyntaxPacket.CommentPacket && pkt.portLintDirectiveKind == null) {
                            val formatted =
                                FrameAstHelpers.formatCommentForCpp(
                                    comment = pkt,
                                    indent = innerIndent,
                                    knownFunctionNames = bundle.knownFunctionNames,
                                    allowFenceTranslation = !bundle.isDocExample,
                                )
                            if (formatted.isNotEmpty()) {
                                append(formatted).append('\n')
                            }
                        }
                    } else {
                        val stmtFrame = stream.frames[ref]
                        val isLast = stmtOrdinal == childStmts.lastIndex
                        stmtOrdinal += 1

                        emitHoistedPrefixesForStmt(this, stmtFrame.frameIndex, innerIndent)
                        if (isLast && (isValueBlock || resultTargetVar != null)) {
                            append(
                                emitValueAssignmentOrReturn(
                                    exprFrame = stmtFrame,
                                    indent = innerIndent,
                                    targetReturn = resultTargetVar == null,
                                    targetVar = resultTargetVar,
                                ),
                            )
                        } else {
                            withExpectedTargetType(null) {
                                append(
                                    emitStatement(
                                        stmtFrame = stmtFrame,
                                        indent = innerIndent,
                                        inValuePosition = false,
                                        resultTargetVar = null,
                                    ),
                                )
                            }
                        }
                        emitDeadSpillResetsAfterStmt(this, stmtFrame.frameIndex, innerIndent)
                    }
                }
                if (isTopLevelSuspendBlock && !isValueBlock && resultTargetVar == null) {
                    val lastStmt = childStmts.lastOrNull()
                    val transfers =
                        lastStmt != null &&
                            (
                                doesBranchAlwaysTransferControl(lastStmt) ||
                                    PacketFlags.hasFlag(lastStmt.flags, PacketFlags.DIVERGING_EXPR)
                            )
                    if (!transfers) {
                        append(innerIndent).append("return ").append(defaultSuspendFallthroughReturnExpr()).append(";\n")
                    }
                }
                append(indent).append('}')
            }
        }

    internal fun emitSmartCastBindings(
        out: StringBuilder,
        smartCasts: List<SmartCastBinding>,
        indent: String,
    ) {
        for (sc in smartCasts) {
            val targetCppType =
                translateType(stream.frames[sc.targetTypeFrameIndex], CppTypePosition.TYPE_ARGUMENT)
                    .removeSuffix("&")
                    .removeSuffix("*")
            val srcExpr =
                if (sc.kotlinVarName == "this") {
                    receiverSubstitution ?: "(*this)"
                } else {
                    resolveBoundName(sc.kotlinVarName)
                        ?: resolveUnqualifiedPropertyAccess(sc.kotlinVarName)
                        ?: CppNamingPolicy.toSnakeCase(sc.kotlinVarName)
                }
            val effectiveNarrowedName =
                if (activeNarrowedScopes.any { sc.narrowedCppName in it }) {
                    freshTemp(sc.narrowedCppName)
                } else {
                    sc.narrowedCppName
                }
            activeNarrowedScopes.last().add(effectiveNarrowedName)
            val targetSimple =
                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[sc.targetTypeFrameIndex])
            val effTargetSimple = targetSimple?.let { bundle.symbols.importAliases[it] ?: it }
            val isSharedTarget =
                effTargetSimple != null &&
                    (
                        CppTypeTranslator.isSharedHierarchyType(effTargetSimple) ||
                            effTargetSimple in bundle.symbols.sharedHierarchyTypeNames ||
                            effTargetSimple in bundle.symbols.sharedPtrTypeAliases
                    )
            if (!bundle.isDocExample && sc.kotlinVarName != "this" && isSharedTarget) {
                includes.needsCastHelpersDef = true
                includes.addSystem("memory")
                out
                    .append(indent)
                    .append("[[maybe_unused]] auto ")
                    .append(effectiveNarrowedName)
                    .append(" = kxs_safe_as_cast<std::shared_ptr<")
                    .append(targetCppType)
                    .append(">>(")
                    .append(srcExpr)
                    .append(");\n")
                bindLocalName(sc.kotlinVarName, effectiveNarrowedName)
                markLocalPolyVar(sc.kotlinVarName)
                unmarkStdAnyVar(sc.kotlinVarName)
                markLocalType(sc.kotlinVarName, effTargetSimple)
                continue
            }
            if (!bundle.isDocExample && sc.kotlinVarName != "this" && isExceptionPtrKotlinVar(sc.kotlinVarName)) {
                includes.addSystem("exception")
                includes.addSystem("memory")
                out
                    .append(indent)
                    .append("[[maybe_unused]] auto ")
                    .append(effectiveNarrowedName)
                    .append(" = ([&](const std::exception_ptr& kxs_ep_src) -> std::shared_ptr<")
                    .append(targetCppType)
                    .append("> { if (!kxs_ep_src) return nullptr; try { std::rethrow_exception(kxs_ep_src); } ")
                    .append("catch (const ")
                    .append(targetCppType)
                    .append("& kxs_ex) { return std::make_shared<")
                    .append(targetCppType)
                    .append(">(kxs_ex); } catch (...) { return nullptr; } }(")
                    .append(srcExpr)
                    .append("));\n")
                bindLocalName(sc.kotlinVarName, "(*$effectiveNarrowedName)")
                unmarkStdAnyVar(sc.kotlinVarName)
                if (effTargetSimple != null) markLocalType(sc.kotlinVarName, effTargetSimple)
                continue
            }
            includes.addSystem("any")
            includes.addSystem("memory")
            includes.addSystem("type_traits")
            out
                .append(indent)
                .append("[[maybe_unused]] auto* ")
                .append(effectiveNarrowedName)
                .append(" = ([&](auto&& kxs_sc_src) -> ")
                .append(targetCppType)
                .append("* { using KxsScSrc = std::decay_t<decltype(kxs_sc_src)>; ")
                .append("if constexpr (std::is_same_v<KxsScSrc, std::any>) { ")
                .append("if constexpr (!std::is_abstract_v<")
                .append(targetCppType)
                .append(">) { if (const auto* kxs_p = std::any_cast<const ")
                .append(targetCppType)
                .append(">(std::addressof(kxs_sc_src))) return const_cast<")
                .append(targetCppType)
                .append("*>(kxs_p); } ")
                .append("if (const auto* kxs_sp = std::any_cast<const std::shared_ptr<")
                .append(targetCppType)
                .append(">>(std::addressof(kxs_sc_src))) return kxs_sp->get(); ")
                .append("if (const auto* kxs_ptr = std::any_cast<")
                .append(targetCppType)
                .append("* const>(std::addressof(kxs_sc_src))) return *kxs_ptr; ")
                .append("return nullptr; ")
                .append("} else if constexpr (std::is_pointer_v<KxsScSrc>) { ")
                .append("if constexpr (std::is_polymorphic_v<std::remove_pointer_t<KxsScSrc>>) return dynamic_cast<")
                .append(targetCppType)
                .append("*>(const_cast<std::remove_cv_t<std::remove_pointer_t<KxsScSrc>>*>(kxs_sc_src)); else return reinterpret_cast<")
                .append(targetCppType)
                .append("*>(const_cast<std::remove_cv_t<std::remove_pointer_t<KxsScSrc>>*>(kxs_sc_src)); ")
                .append("} else if constexpr (requires { kxs_sc_src.get(); }) { ")
                .append("using KxsScPt = std::remove_pointer_t<decltype(kxs_sc_src.get())>; ")
                .append("if constexpr (std::is_polymorphic_v<KxsScPt>) return dynamic_cast<")
                .append(targetCppType)
                .append("*>(const_cast<std::remove_cv_t<KxsScPt>*>(kxs_sc_src.get())); else return reinterpret_cast<")
                .append(targetCppType)
                .append("*>(const_cast<std::remove_cv_t<KxsScPt>*>(kxs_sc_src.get())); ")
                .append("} else if constexpr (std::is_polymorphic_v<KxsScSrc>) { ")
                .append("return dynamic_cast<")
                .append(targetCppType)
                .append("*>(const_cast<KxsScSrc*>(std::addressof(kxs_sc_src))); ")
                .append("} else { return nullptr; } }(")
                .append(srcExpr)
                .append("));\n")
            bindLocalName(sc.kotlinVarName, "(*$effectiveNarrowedName)")
            unmarkStdAnyVar(sc.kotlinVarName)
            if (sc.kotlinVarName == "this") {
                receiverSubstitution = "(*$effectiveNarrowedName)"
                val narrowedSimple =
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[sc.targetTypeFrameIndex])
                extensionReceiverClass = narrowedSimple?.let { lookupAccessibleClassByName(it) }
                extensionReceiverIsPointer = false
                extensionReceiverSimpleName = narrowedSimple
            }
        }
    }

    internal fun emitHoistedPrefixesForStmt(
        out: StringBuilder,
        stmtFrameIndex: Int,
        indent: String,
    ) {
        val evalTemps = bundle.suspendPlan.hoistedTempsBeforeStmtFrame[stmtFrameIndex].orEmpty()
        for (ht in evalTemps) {
            val exprCode = emitExprIgnoringHoist(stream.frames[ht.exprFrameIndex])
            out
                .append(indent)
                .append("auto ")
                .append(ht.tempCppName)
                .append(" = ")
                .append(exprCode)
                .append(";\n")
        }

        val cfValues = bundle.cfPlan.hoistedSuspendControlFlowsBeforeStmt[stmtFrameIndex].orEmpty()
        for (cf in cfValues) {
            includes.addSystem("optional")
            val cfFrame = stream.frames[cf.controlFlowFrameIndex]
            val slotType = inferControlFlowResultType(cfFrame)
            out
                .append(indent)
                .append("std::optional<")
                .append(slotType)
                .append("> ")
                .append(cf.slotCppName)
                .append(";\n")
            out.append(emitStatement(cfFrame, indent, inValuePosition = true, resultTargetVar = cf.slotCppName))
        }
    }

    internal fun emitDeadSpillResetsAfterStmt(
        out: StringBuilder,
        stmtFrameIndex: Int,
        indent: String,
    ) {
        val resets = bundle.gcPlan.deadResetsAfterStmt[stmtFrameIndex].orEmpty()
        for (r in resets) {
            if (r.isKonanExternalRc) {
                out.append(indent).append(r.cppVarName).append(".reset();\n")
            } else {
                out.append(indent).append(r.cppVarName).append(" = {};\n")
            }
        }
    }

    internal tailrec fun unwrapTransparentFrame(frame: PacketFrame): PacketFrame =
        when (frame.nodeTypeId) {
            KtNodeTypes.ANNOTATED_EXPRESSION_ID -> {
                val inner =
                    FrameAstHelpers
                        .childFrames(stream, frame)
                        .lastOrNull {
                            it.nodeTypeId != KtNodeTypes.ANNOTATION_ENTRY_ID &&
                                it.nodeTypeId != KtNodeTypes.ANNOTATION_ID
                        }
                if (inner != null) unwrapTransparentFrame(inner) else frame
            }
            KtNodeTypes.LABELED_EXPRESSION_ID -> {
                val inner =
                    FrameAstHelpers
                        .childFrames(stream, frame)
                        .lastOrNull {
                            it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID &&
                                it.nodeTypeId != KtNodeTypes.LABEL_ID
                        }
                if (inner != null) unwrapTransparentFrame(inner) else frame
            }
            else -> frame
        }

    companion object {
        fun escapeCppStringLiteral(text: String): String =
            buildString(text.length + 2) {
                append('"')
                for (ch in text) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        '\b' -> append("\\b")
                        else -> {
                            if (ch.code < 0x20) {
                                append("\\x").append(ch.code.toString(16).padStart(2, '0'))
                            } else {
                                append(ch)
                            }
                        }
                    }
                }
                append('"')
            }

        internal val CPP_BINARY_OPERATORS: Set<String> =
            setOf("+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">=", "&&", "||", "=", "+=", "-=", "*=", "/=", "%=")

        internal val ASSIGN_OPS: Set<String> =
            setOf("=", "+=", "-=", "*=", "/=", "%=")

        internal val ELVIS_CONTROL_TRANSFER_NODE_TYPES: Set<Int> =
            setOf(
                KtNodeTypes.RETURN_ID,
                KtNodeTypes.BREAK_ID,
                KtNodeTypes.CONTINUE_ID,
                KtNodeTypes.THROW_ID,
            )

        internal val EXCEPTION_PTR_KOTLIN_TYPES: Set<String> =
            setOf(
                "Throwable",
                "Exception",
                "RuntimeException",
                "IllegalStateException",
                "IllegalArgumentException",
            )

        internal val COMPOUND_OR_DECLARATION_STMT_NODE_TYPES: Set<Int> =
            setOf(
                KtNodeTypes.BLOCK_ID,
                KtNodeTypes.IF_ID,
                KtNodeTypes.WHEN_ID,
                KtNodeTypes.TRY_ID,
                KtNodeTypes.WHILE_ID,
                KtNodeTypes.DO_WHILE_ID,
                KtNodeTypes.FOR_ID,
                KtNodeTypes.FUN_ID,
                KtNodeTypes.CLASS_ID,
                KtNodeTypes.OBJECT_DECLARATION_ID,
            )

        internal val STATEMENT_ONLY_NODE_TYPES: Set<Int> =
            setOf(
                KtNodeTypes.PROPERTY_ID,
                KtNodeTypes.FUN_ID,
                KtNodeTypes.CLASS_ID,
                KtNodeTypes.OBJECT_DECLARATION_ID,
                KtNodeTypes.RETURN_ID,
                KtNodeTypes.THROW_ID,
                KtNodeTypes.BREAK_ID,
                KtNodeTypes.CONTINUE_ID,
                KtNodeTypes.FOR_ID,
                KtNodeTypes.WHILE_ID,
                KtNodeTypes.DO_WHILE_ID,
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

        internal val CANCELLATION_EXCEPTION_CLASS_NAMES: Set<String> =
            setOf(
                "CancellationException",
                "JobCancellationException",
                "TimeoutCancellationException",
                "AbortFlowException",
                "ChildCancelledException",
                "CopyableThrowable",
            )

        internal val CHANNEL_STATIC_CONSTANTS: Set<String> =
            setOf(
                "RENDEZVOUS",
                "CONFLATED",
                "UNLIMITED",
                "BUFFERED",
                "OPTIONAL_CHANNEL",
                "CHANNEL_DEFAULT_CAPACITY",
            )

        internal val KNOWN_CALLABLE_TYPEALIASES: Set<String> =
            setOf(
                "CompletionHandler",
                "CancellationHandler",
                "OnUndeliveredElement",
            )

        internal val KNOWN_GENERIC_SHARED_CLASSES: Set<String> =
            setOf(
                "ScopeCoroutine",
                "TimeoutCoroutine",
                "UndispatchedCoroutine",
                "LazyDeferredCoroutine",
                "DeferredCoroutine",
                "StandaloneCoroutine",
                "LazyStandaloneCoroutine",
                "BroadcastCoroutine",
                "LazyBroadcastCoroutine",
                "DispatchedCoroutine",
                "BufferedChannel",
                "ConflatedBufferedChannel",
                "ChannelCoroutine",
                "ProducerCoroutine",
                "ActorCoroutine",
                "CancellableContinuationImpl",
                "DispatchedContinuation",
                "SelectImplementation",
                "StateFlowImpl",
                "SharedFlowImpl",
                "SafeCollector",
                "ChannelFlowOperatorImpl",
            )

        internal val KNOWN_OBJECT_SINGLETONS: Set<String> =
            setOf(
                "GlobalScope",
                "NonCancellable",
                "NonDisposableHandle",
                "DefaultExecutor",
                "DefaultDelay",
                "DefaultScheduler",
                "DefaultIoScheduler",
                "UnlimitedIoScheduler",
                "ThreadLocalEventLoop",
            )

        internal val KNOWN_PASCAL_FACTORY_FUNCTIONS: Set<String> =
            setOf(
                "Job",
                "SupervisorJob",
                "CoroutineScope",
                "MainScope",
                "CoroutineExceptionHandler",
                "CompletableDeferred",
                "Channel",
                "BroadcastChannel",
                "MutableSharedFlow",
                "MutableStateFlow",
                "Mutex",
                "Semaphore",
            )

        internal val KNOWN_CONTEXT_KEY_CARRIERS: Set<String> =
            setOf(
                "Job",
                "CoroutineName",
                "CoroutineId",
                "CoroutineExceptionHandler",
                "YieldContext",
                "TestCoroutineScheduler",
            )

        internal val KNOWN_TO_STRING_SUPER_FRAGMENTS: Set<String> =
            setOf(
                "LockFreeLinkedListNode",
                "Segment",
                "CoroutineDispatcher",
                "MainCoroutineDispatcher",
                "ExecutorCoroutineDispatcher",
                "CoroutineContext",
                "AbstractCoroutineContextElement",
                "CancellationException",
                "JobNode",
                "JobSupport",
                "AbstractCoroutine",
                "ScopeCoroutine",
                "BaseContinuationImpl",
                "ContinuationImpl",
                "RestrictedContinuationImpl",
                "SuspendLambda",
                "RestrictedSuspendLambda",
                "CancellableContinuationImpl",
                "DispatchedContinuation",
                "Task",
                "DelayedTask",
            )

        internal val DURATION_WHOLE_UNITS: Set<String> =
            setOf(
                "inWholeNanoseconds",
                "inWholeMicroseconds",
                "inWholeSeconds",
                "inWholeMinutes",
                "inWholeHours",
                "inWholeDays",
            )

        internal val KNOWN_EXTERNAL_RECEIVER_PROPERTIES: Map<String, Set<String>> =
            mapOf(
                "CoroutineScope" to setOf("coroutineContext"),
                "Continuation" to setOf("context"),
                "CancellableContinuation" to setOf("context", "isActive", "isCompleted", "isCancelled"),
                "StringBuilder" to setOf("length"),
                "Duration" to
                    setOf(
                        "inWholeMilliseconds",
                        "inWholeNanoseconds",
                        "inWholeMicroseconds",
                        "inWholeSeconds",
                        "inWholeMinutes",
                        "inWholeHours",
                        "inWholeDays",
                        "absoluteValue",
                    ),
                "Job" to setOf("isActive", "isCompleted", "isCancelled", "parent", "children"),
                "CompletableJob" to setOf("isActive", "isCompleted", "isCancelled", "parent", "children"),
                "Deferred" to setOf("isActive", "isCompleted", "isCancelled", "onAwait"),
                "CompletableDeferred" to setOf("isActive", "isCompleted", "isCancelled", "onAwait"),
                "SendChannel" to setOf("isClosedForSend", "onSend"),
                "ReceiveChannel" to
                    setOf(
                        "isClosedForReceive",
                        "isEmpty",
                        "onReceive",
                        "onReceiveCatching",
                        "onReceiveOrNull",
                    ),
                "Channel" to
                    setOf(
                        "isClosedForSend",
                        "isClosedForReceive",
                        "isEmpty",
                        "onSend",
                        "onReceive",
                        "onReceiveCatching",
                        "onReceiveOrNull",
                    ),
                "BufferedChannel" to
                    setOf(
                        "isClosedForSend",
                        "isClosedForReceive",
                        "isEmpty",
                        "onSend",
                        "onReceive",
                        "onReceiveCatching",
                        "onReceiveOrNull",
                    ),
                "ProducerScope" to
                    setOf("coroutineContext", "channel", "isClosedForSend", "onSend"),
                "ActorScope" to
                    setOf(
                        "coroutineContext",
                        "channel",
                        "isClosedForReceive",
                        "isEmpty",
                        "onReceive",
                        "onReceiveCatching",
                        "onReceiveOrNull",
                    ),
                "BroadcastChannel" to setOf("isClosedForSend", "onSend"),
                "ScopeCoroutine" to setOf("uCont", "context"),
                "ThreadLocalEventLoop" to setOf("eventLoop"),
                "Worker" to setOf("name"),
            )

        internal val KNOWN_EXTERNAL_RECEIVER_METHODS: Map<String, Set<String>> =
            mapOf(
                "Duration" to
                    setOf(
                        "isPositive",
                        "isNegative",
                        "isInfinite",
                        "isFinite",
                        "plus",
                        "minus",
                        "times",
                        "div",
                        "toComponents",
                        "toLong",
                        "compareTo",
                        "absoluteValue",
                    ),
                "CoroutineContext" to setOf("get", "fold", "minusKey"),
                "Job" to setOf("start", "cancel", "join", "attachChild", "invokeOnCompletion", "getCancellationException"),
                "CompletableJob" to
                    setOf(
                        "start",
                        "cancel",
                        "join",
                        "attachChild",
                        "invokeOnCompletion",
                        "getCancellationException",
                        "complete",
                        "completeExceptionally",
                    ),
                "Deferred" to
                    setOf(
                        "start",
                        "cancel",
                        "join",
                        "attachChild",
                        "invokeOnCompletion",
                        "getCancellationException",
                        "await",
                        "getCompleted",
                        "getCompletionExceptionOrNull",
                    ),
                "CompletableDeferred" to
                    setOf(
                        "start",
                        "cancel",
                        "join",
                        "attachChild",
                        "invokeOnCompletion",
                        "getCancellationException",
                        "await",
                        "getCompleted",
                        "getCompletionExceptionOrNull",
                        "complete",
                        "completeExceptionally",
                    ),
                "Continuation" to setOf("resume", "resumeWith"),
                "CancellableContinuation" to
                    setOf(
                        "resume",
                        "resumeWith",
                        "resumeUndispatched",
                        "resumeUndispatchedWithException",
                        "tryResume",
                        "tryResumeWithException",
                        "completeResume",
                        "initCancellability",
                        "cancel",
                        "invokeOnCancellation",
                    ),
                "SendChannel" to setOf("send", "trySend", "close", "invokeOnClose"),
                "ReceiveChannel" to setOf("receive", "receiveCatching", "tryReceive", "cancel", "iterator"),
                "Channel" to setOf("send", "trySend", "close", "invokeOnClose", "receive", "receiveCatching", "tryReceive", "cancel", "iterator"),
                "ProducerScope" to setOf("send", "trySend", "close", "invokeOnClose"),
                "ActorScope" to setOf("receive", "receiveCatching", "tryReceive", "cancel", "iterator"),
                "BroadcastChannel" to
                    setOf(
                        "openSubscription",
                        "cancel",
                        "send",
                        "trySend",
                        "close",
                        "invokeOnClose",
                    ),
                "Flow" to setOf("collect"),
                "FlowCollector" to setOf("emit"),
                "StringBuilder" to setOf("append", "appendLine", "clear", "toString"),
                "ScopeCoroutine" to
                    setOf(
                        "makeCompleting",
                        "makeCompletingOnce",
                        "afterCompletionUndispatched",
                        "cancelCoroutine",
                        "initParentJob",
                    ),
                "ThreadLocalEventLoop" to setOf("currentOrNull", "resetEventLoop", "setEventLoop"),
                "Worker" to setOf("execute", "executeAfter", "park", "requestTermination"),
            )

        internal val DISPATCHER_OR_EVENT_LOOP_FACTORY_NAMES: Set<String> =
            setOf(
                "newFixedThreadPoolContext",
                "newSingleThreadContext",
                "createEventLoop",
                "takeEventLoop",
                "eventLoopOrNull",
                "currentOrNull",
            )

        internal val DIRECT_ITERABLE_VALUE_TYPES: Set<String> =
            setOf(
                "Collection",
                "List",
                "MutableList",
                "ArrayList",
                "Set",
                "MutableSet",
                "HashSet",
                "LinkedHashSet",
                "Map",
                "MutableMap",
                "HashMap",
                "LinkedHashMap",
                "Array",
                "ArrayDeque",
                "Iterable",
                "Sequence",
                "String",
            )

        internal val NON_SWITCH_SUBJECT_TYPES: Set<String> =
            setOf(
                "Any",
                "String",
                "Symbol",
                "Throwable",
                "Exception",
            )

        internal val DIRECT_EQ_SCALAR_TYPES: Set<String> =
            setOf(
                "Boolean",
                "Int",
                "Long",
                "Short",
                "Byte",
                "Char",
                "UInt",
                "ULong",
                "UShort",
                "UByte",
                "Float",
                "Double",
                "String",
            )

        internal val VALUE_CONTAINER_CALL_NAMES: Set<String> =
            setOf(
                "HashMap",
                "LinkedHashMap",
                "HashSet",
                "LinkedHashSet",
                "ArrayList",
                "ArrayDeque",
                "listOf",
                "mutableListOf",
                "arrayListOf",
                "setOf",
                "mutableSetOf",
                "hashSetOf",
                "mapOf",
                "mutableMapOf",
                "hashMapOf",
                "linkedMapOf",
                "emptyList",
                "emptySet",
                "emptyMap",
            )

        internal val INLINE_SCOPE_HELPER_NAMES: Set<String> =
            setOf(
                "also",
                "apply",
                "let",
                "run",
                "with",
                "repeat",
                "use",
                "forEach",
                "synchronized",
                "withLock",
            )

        internal val ELEMENT_RETURNING_CONTAINER_METHODS: Set<String> =
            setOf(
                "removeFirstIf",
                "removeLastOrNull",
                "removeFirstOrNull",
                "firstOrNull",
                "lastOrNull",
            )

        internal val INHERENTLY_MUTABLE_VAL_TYPES: Set<String> =
            setOf(
                "MutableList",
                "ArrayList",
                "MutableSet",
                "HashSet",
                "LinkedHashSet",
                "MutableMap",
                "HashMap",
                "LinkedHashMap",
                "ArrayDeque",
                "StringBuilder",
                "ThreadSafeHeap",
                "ReentrantLock",
                "SynchronizedObject",
                "KxsSynchronizedObject",
                "LockFreeTaskQueue",
                "AtomicInt",
                "AtomicLong",
                "AtomicBoolean",
                "AtomicRef",
            )

        internal val INHERENTLY_MUTABLE_FACTORY_NAMES: Set<String> =
            setOf(
                "mutableListOf",
                "arrayListOf",
                "mutableSetOf",
                "hashSetOf",
                "linkedSetOf",
                "mutableMapOf",
                "hashMapOf",
                "linkedMapOf",
                "toMutableList",
                "toMutableSet",
                "toMutableMap",
                "ArrayList",
                "HashSet",
                "LinkedHashSet",
                "HashMap",
                "LinkedHashMap",
                "ArrayDeque",
                "StringBuilder",
                "ThreadSafeHeap",
                "ReentrantLock",
                "SynchronizedObject",
            )
    }
}
