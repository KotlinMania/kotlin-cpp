package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.LiteralPacketKind
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream
import io.github.kotlinmania.kotlincpp.packet.StringSegmentKind
import io.github.kotlinmania.kotlincpp.packet.SymbolRole
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
    private val bundle: LoweringBundle,
    private val activeTypeParams: Set<String> = emptySet(),
    private val enclosingClass: KtClassSymbol? = null,
    private val enclosingFunction: KtFunctionSymbol? = null,
    private var receiverSubstitution: String? = null,
    private var extensionReceiverClass: KtClassSymbol? = null,
    private var extensionReceiverIsPointer: Boolean = false,
    private var extensionReceiverSimpleName: String? = null,
    private val unitReturnExprOverride: String? = null,
    private val isVoidReturnContext: Boolean = false,
    private val enclosingFunctionReturnType: String? = null,
    private val inlinedLambdaSubstitutions: Map<String, InlinedCallerLambdaSite> = emptyMap(),
    private val inlineReturnTargetVar: String? = null,
    private val inlineReturnExitLabel: String? = null,
    private val localPolymorphicClasses: MutableSet<String> = LinkedHashSet(),
    private val localExtensionFunctions: MutableMap<String, String> = LinkedHashMap(),
    private val inDefaultParameterExpr: Boolean = false,
    private val activeInlineFunFrames: Set<Int> = emptySet(),
    private val isSuspendBody: Boolean = false,
    private val suspendBoxType: String? = null,
    private val wrapReturnInMakeSharedOf: String? = null,
    private val expectedTargetType: String? = null,
    private val anonObjectOwnMethods: Set<String> = emptySet(),
    private val anonObjectOwnProps: Set<String> = emptySet(),
) {
    var usedInlineReturnExitLabel: Boolean = false
    private val stream: PacketStream get() = bundle.stream
    private val includes: CppIncludeCollector get() = bundle.includes
    private val localBindings = ArrayDeque<MutableMap<String, String>>()
    private val nonCallableCtorParamNames = LinkedHashSet<String>()
    private val exceptionPtrScopes = ArrayDeque<MutableSet<String>>()
    private val stdAnyScopes = ArrayDeque<MutableSet<String>>()
    private val localPolyVarScopes = ArrayDeque<MutableSet<String>>()
    private val localTypeScopes = ArrayDeque<MutableMap<String, String>>()
    private val activeNarrowedScopes = ArrayDeque<MutableSet<String>>()
    private val callableReceiverParamScopes = ArrayDeque<MutableMap<String, Int>>()
    private val preLoweredElvisExprs = HashMap<Int, String>()
    private var synthCounter = 0
    private var inExpressionIifeDepth = 0
    private var currentExpectedTargetType: String? = expectedTargetType
    private var sequenceYieldTargetVar: String? = null
    private val isOuterSuspendReturnContext: Boolean
        get() = isSuspendBody && inExpressionIifeDepth == 0

    private inline fun <T> withinExpressionIife(block: () -> T): T {
        inExpressionIifeDepth += 1
        return try {
            block()
        } finally {
            inExpressionIifeDepth -= 1
        }
    }

    private fun iifeAnyRetSuffix(): String =
        if (currentExpectedTargetType == "std::any") {
            includes.addSystem("any")
            " -> std::any"
        } else {
            ""
        }

    private inline fun <T> withExpectedTargetType(
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

    private fun translateType(
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

    private fun isFunctionOrCallableTypeFrame(typeFrameIdx: Int?): Boolean {
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

    private fun markLocalType(
        kotlinName: String,
        simpleTypeName: String,
    ) {
        localTypeScopes.last()[kotlinName] = simpleTypeName
    }

    private fun resolveLocalTypeSimpleName(kotlinName: String): String? {
        for (i in localTypeScopes.size - 1 downTo 0) {
            localTypeScopes[i][kotlinName]?.let { return it }
        }
        return null
    }

    private fun resolveClassOrTypeParamBound(
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

    private fun findClassHierarchyProperty(
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

    private fun findClassHierarchyPropertyTypeFrameIndex(
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

    private fun findClassHierarchyFunction(
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

    private fun classDerivesFrom(
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

    private fun extractBranchTailExpr(wrapper: PacketFrame?): PacketFrame? {
        val child = wrapper?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() } ?: return null
        val u = unwrapTransparentFrame(child)
        return if (u.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            FrameAstHelpers.childFrames(stream, u).lastOrNull()?.let { unwrapTransparentFrame(it) }
        } else {
            u
        }
    }

    private fun extractFunctionTypeNode(typeFrame: PacketFrame): PacketFrame? {
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

    private fun extractFunctionTypeReceiverRef(typeFrame: PacketFrame): PacketFrame? {
        val fnType = extractFunctionTypeNode(typeFrame) ?: return null
        val recvWrap = FrameAstHelpers.firstChildFrame(stream, fnType, KtNodeTypes.FUNCTION_TYPE_RECEIVER_ID)
        return recvWrap?.let {
            FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_REFERENCE_ID)
                ?: FrameAstHelpers.childFrames(stream, it).firstOrNull()
        }
    }

    private fun extractFunctionTypeValueParamCount(typeFrame: PacketFrame): Int {
        val fnType = extractFunctionTypeNode(typeFrame) ?: return 0
        val pList = FrameAstHelpers.firstChildFrame(stream, fnType, KtNodeTypes.VALUE_PARAMETER_LIST_ID) ?: return 0
        return FrameAstHelpers.childFramesOfType(stream, pList, KtNodeTypes.VALUE_PARAMETER_ID).size
    }

    private fun resolveAtomicFieldForExpr(exprFrame: PacketFrame): AtomicFieldInfo? {
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

    private fun inferExpressionSimpleTypeName(exprFrame: PacketFrame): String? {
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

    private fun markExceptionPtrVar(kotlinName: String) {
        exceptionPtrScopes.last().add(kotlinName)
    }

    private fun markStdAnyVar(kotlinName: String) {
        stdAnyScopes.last().add(kotlinName)
    }

    private fun unmarkStdAnyVar(kotlinName: String) {
        for (i in stdAnyScopes.size - 1 downTo 0) {
            stdAnyScopes[i].remove(kotlinName)
        }
    }

    private fun markLocalPolyVar(kotlinName: String) {
        localPolyVarScopes.last().add(kotlinName)
    }

    private fun isLocalPolyVar(kotlinName: String): Boolean {
        for (i in localPolyVarScopes.size - 1 downTo 0) {
            if (kotlinName in localPolyVarScopes[i]) return true
        }
        return false
    }

    private fun isExceptionPtrKotlinVar(kotlinName: String): Boolean {
        val narrowedSimple = resolveLocalTypeSimpleName(kotlinName)
        if (narrowedSimple != null && narrowedSimple !in EXCEPTION_PTR_KOTLIN_TYPES) return false
        for (i in exceptionPtrScopes.size - 1 downTo 0) {
            if (kotlinName in exceptionPtrScopes[i]) return true
        }
        return false
    }

    private fun isExceptionPtrVarByCppName(cppName: String): Boolean {
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

    private fun isAlreadyExceptionPtrExprCode(code: String): Boolean {
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

    private fun wrapWithExceptionPtrConversion(
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

    private fun isStdAnyKotlinVar(kotlinName: String): Boolean {
        for (i in stdAnyScopes.size - 1 downTo 0) {
            if (kotlinName in stdAnyScopes[i]) return true
        }
        return false
    }

    private fun isExceptionPtrExpr(exprFrame: PacketFrame): Boolean {
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

    private fun isStdAnyExpr(exprFrame: PacketFrame): Boolean {
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

    private fun <T> withLexicalScope(block: () -> T): T {
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

    private fun callableReceiverParamArity(kotlinName: String): Int? {
        for (i in callableReceiverParamScopes.indices.reversed()) {
            callableReceiverParamScopes[i][kotlinName]?.let { return it }
            if (kotlinName in localBindings[i]) return null
        }
        return null
    }

    private fun resolveBoundName(kotlinName: String): String? {
        for (i in localBindings.size - 1 downTo 0) {
            localBindings[i][kotlinName]?.let { return it }
        }
        return null
    }

    private fun resolveCallableBoundName(kotlinName: String): String? {
        if (kotlinName in nonCallableCtorParamNames) {
            val bound = resolveBoundName(kotlinName)
            if (bound != null && bound.endsWith("_value")) return null
        }
        return resolveBoundName(kotlinName)
    }

    private fun freshTemp(prefix: String): String = "${prefix}_${synthCounter++}"

    private fun wrapCopyableReturnIfNeeded(exprCode: String): String {
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

    private fun defaultSuspendFallthroughReturnExpr(): String =
        if (suspendBoxType == "kotlin::Unit") {
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            "static_cast<void*>(new kotlin::Unit{})"
        } else {
            "nullptr"
        }

    private fun wrapSuspendReturnValue(exprCode: String): String {
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
                return if (boxT.startsWith("std::optional<") ||
                    boxT == "std::any" ||
                    boxT.startsWith("std::shared_ptr<") ||
                    boxT.endsWith("*")
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

    private fun emitSmartCastBindings(
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

    private fun emitHoistedPrefixesForStmt(
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

    private fun emitDeadSpillResetsAfterStmt(
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

    private tailrec fun unwrapTransparentFrame(frame: PacketFrame): PacketFrame =
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

    private fun emitValueAssignmentOrReturn(
        exprFrame: PacketFrame,
        indent: String,
        targetReturn: Boolean,
        targetVar: String?,
    ): String {
        val unwrappedExpr = unwrapTransparentFrame(exprFrame)
        // Check if this expression is itself a hoisted suspend control-flow node
        val hoistedCf =
            bundle.cfPlan.hoistedSuspendControlFlowsByExpr[exprFrame.frameIndex]
                ?: bundle.cfPlan.hoistedSuspendControlFlowsByExpr[unwrappedExpr.frameIndex]
        if (hoistedCf != null) {
            includes.addSystem("utility")
            return if (targetReturn) {
                "${indent}return ${wrapSuspendReturnValue("std::move(*${hoistedCf.slotCppName})")};\n"
            } else {
                "$indent$targetVar = std::move(*${hoistedCf.slotCppName});\n"
            }
        }

        if (unwrappedExpr.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val binChildren = FrameAstHelpers.childFrames(stream, unwrappedExpr)
            if (binChildren.size >= 3 &&
                FrameAstHelpers.operationTokenId(stream, binChildren[1]) == KtTokens.ELVIS_ID
            ) {
                val rhsUnwrapped = unwrapTransparentFrame(binChildren[2])
                if (isElvisControlTransferTarget(rhsUnwrapped)) {
                    val elvisTemp = freshTemp("kxs_elvis")
                    val lhsCode = emitExpression(binChildren[0])
                    val transferStmt =
                        emitStatement(rhsUnwrapped, "$indent    ", inValuePosition = false, resultTargetVar = null)
                    val derefExpr =
                        if (bundle.isDocExample) {
                            includes.addSystem("type_traits")
                            "([&](auto&& kxs_ev) -> decltype(auto) { " +
                                "if constexpr (std::is_pointer_v<std::decay_t<decltype(kxs_ev)>>) " +
                                "return kxs_ev; else return *kxs_ev; }($elvisTemp))"
                        } else {
                            includes.needsNullUnwrapDef = true
                            includes.addSystem("type_traits")
                            includes.addSystem("utility")
                            "kxs_unwrap_opt($elvisTemp)"
                        }
                    val assignLine =
                        if (targetReturn) {
                            "${indent}return ${wrapSuspendReturnValue(derefExpr)};\n"
                        } else {
                            "$indent$targetVar = $derefExpr;\n"
                        }
                    return buildString {
                        append(indent)
                        append("auto ")
                        append(elvisTemp)
                        append(" = ")
                        append(lhsCode)
                        append(";\n")
                        append(indent)
                        append("if (")
                        append(if (bundle.isDocExample) "!$elvisTemp" else "kxs_is_null($elvisTemp)")
                        append(") {\n")
                        append(transferStmt)
                        append(indent)
                        append("}\n")
                        append(assignLine)
                    }
                }
            }
        }

        bundle.suspendPlan.inlineExpansionsByCallFrame[unwrappedExpr.frameIndex]?.let { inlineExp ->
            return emitInlineExpansionAsStatement(inlineExp, indent, targetVar = targetVar, targetReturn = targetReturn)
        }
        if (unwrappedExpr.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val calleeSimple = extractCalleeSimpleName(unwrappedExpr)
            inlinedLambdaSubstitutions[calleeSimple]?.let { site ->
                return expandInlinedCallerLambdaCall(unwrappedExpr, site, indent, targetVar = targetVar, targetReturn = targetReturn)
            }
        }

        return when (unwrappedExpr.nodeTypeId) {
            KtNodeTypes.RETURN_ID,
            KtNodeTypes.THROW_ID,
            KtNodeTypes.BREAK_ID,
            KtNodeTypes.CONTINUE_ID,
            -> emitStatement(unwrappedExpr, indent, inValuePosition = false, resultTargetVar = null)

            KtNodeTypes.WHILE_ID,
            KtNodeTypes.DO_WHILE_ID,
            KtNodeTypes.FOR_ID,
            KtNodeTypes.PROPERTY_ID,
            KtNodeTypes.FUN_ID,
            KtNodeTypes.CLASS_ID,
            KtNodeTypes.OBJECT_DECLARATION_ID,
            KtNodeTypes.DESTRUCTURING_DECLARATION_ID,
            -> {
                val stmtCode = emitStatement(unwrappedExpr, indent, inValuePosition = false, resultTargetVar = null)
                if (targetReturn && isOuterSuspendReturnContext) {
                    "$stmtCode${indent}return ${defaultSuspendFallthroughReturnExpr()};\n"
                } else if (targetReturn && enclosingFunctionReturnType == "Unit") {
                    "$stmtCode${indent}return Unit{};\n"
                } else {
                    stmtCode
                }
            }

            KtNodeTypes.IF_ID ->
                if (targetReturn && canEmitTernaryIf(unwrappedExpr)) {
                    "${indent}return ${wrapSuspendReturnValue(emitTernaryIfCore(unwrappedExpr))};\n"
                } else {
                    emitIfStatement(
                        unwrappedExpr,
                        indent,
                        inValuePosition = true,
                        targetReturn = targetReturn,
                        targetVar = targetVar,
                    )
                }

            KtNodeTypes.WHEN_ID ->
                emitWhenStatement(
                    unwrappedExpr,
                    indent,
                    inValuePosition = true,
                    targetReturn = targetReturn,
                    targetVar = targetVar,
                )

            KtNodeTypes.TRY_ID ->
                emitTryStatement(
                    unwrappedExpr,
                    indent,
                    inValuePosition = true,
                    targetReturn = targetReturn,
                    targetVar = targetVar,
                )

            KtNodeTypes.BLOCK_ID ->
                "${emitBlock(unwrappedExpr, indent.dropLast(4), isValueBlock = targetReturn, resultTargetVar = targetVar)}\n"

            else -> {
                if (targetReturn && unwrappedExpr.nodeTypeId == KtNodeTypes.NULL_ID) {
                    if (isOuterSuspendReturnContext) {
                        return "${indent}return ${wrapSuspendReturnValue("nullptr")};\n"
                    }
                    val effectiveRetTypeForNull =
                        if (inExpressionIifeDepth > 0) {
                            currentExpectedTargetType
                        } else {
                            enclosingFunctionReturnType
                        }
                    if (effectiveRetTypeForNull?.startsWith("std::optional<") == true) {
                        includes.addSystem("optional")
                        return "${indent}return std::nullopt;\n"
                    }
                    if (effectiveRetTypeForNull == "std::any") {
                        return "${indent}return {};\n"
                    }
                }
                val rendered = emitExpression(unwrappedExpr)
                if (PacketFlags.hasFlag(unwrappedExpr.flags, PacketFlags.DIVERGING_EXPR)) {
                    if (isOuterSuspendReturnContext) {
                        if (rendered.trimStart().startsWith("throw ")) {
                            "$indent$rendered;\n"
                        } else {
                            "$indent$rendered;\n${indent}return ${defaultSuspendFallthroughReturnExpr()};\n"
                        }
                    } else {
                        includes.addSystem("utility")
                        "$indent$rendered;\n${indent}std::unreachable();\n"
                    }
                } else if (rendered.trimStart().startsWith("throw ")) {
                    "$indent$rendered;\n"
                } else if (targetReturn) {
                    val trimmedRendered = rendered.trim()
                    if (isOuterSuspendReturnContext &&
                        isVoidReturnContext &&
                        suspendBoxType == null &&
                        unitReturnExprOverride == null &&
                        trimmedRendered != "COROUTINE_SUSPENDED" &&
                        !trimmedRendered.endsWith("::COROUTINE_SUSPENDED")
                    ) {
                        "$indent$rendered;\n${indent}return nullptr;\n"
                    } else {
                        "${indent}return ${wrapSuspendReturnValue(rendered)};\n"
                    }
                } else {
                    val coercedRhs =
                        when {
                            targetVar != null && isExceptionPtrVarByCppName(targetVar) ->
                                wrapWithExceptionPtrConversion(rendered, unwrappedExpr)
                            rendered == "nullptr" -> "{}"
                            else -> rendered
                        }
                    "$indent$targetVar = $coercedRhs;\n"
                }
            }
        }
    }

    fun emitStatement(
        stmtFrame: PacketFrame,
        indent: String,
        inValuePosition: Boolean,
        resultTargetVar: String?,
    ): String {
        for (leadComment in FrameAstHelpers.extractLeadingDeclarationComments(stream, stmtFrame)) {
            val formatted =
                FrameAstHelpers.formatCommentForCpp(
                    comment = leadComment,
                    indent = indent,
                    knownFunctionNames = bundle.knownFunctionNames,
                    allowFenceTranslation = !bundle.isDocExample,
                )
            if (formatted.isNotEmpty()) {
                return formatted + "\n" + emitStatementBodyWithoutLeadingComments(stmtFrame, indent, inValuePosition, resultTargetVar)
            }
        }
        return emitStatementBodyWithoutLeadingComments(stmtFrame, indent, inValuePosition, resultTargetVar)
    }

    private fun emitStatementBodyWithoutLeadingComments(
        stmtFrame: PacketFrame,
        indent: String,
        inValuePosition: Boolean,
        resultTargetVar: String?,
    ): String {
        val unwrappedStmt = unwrapTransparentFrame(stmtFrame)
        if (unwrappedStmt.nodeTypeId !in COMPOUND_OR_DECLARATION_STMT_NODE_TYPES &&
            !(
                unwrappedStmt.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID &&
                    FrameAstHelpers.childFrames(stream, unwrappedStmt).let { ch ->
                        ch.size >= 3 && FrameAstHelpers.operationTokenId(stream, ch[1]) == KtTokens.ELVIS_ID
                    }
            )
        ) {
            val inlineElvis = ArrayList<PacketFrame>(2)
            collectInlineElvisControlTransfers(unwrappedStmt, inlineElvis)
            if (inlineElvis.isNotEmpty()) {
                val newlyBound = ArrayList<Int>(inlineElvis.size)
                return try {
                    buildString {
                        includes.addSystem("type_traits")
                        for (elvisFrame in inlineElvis) {
                            val ch = FrameAstHelpers.childFrames(stream, elvisFrame)
                            val elvisTemp = freshTemp("kxs_elvis")
                            val lhsCode = emitExpression(ch[0])
                            val rhsTransfer =
                                emitStatement(
                                    unwrapTransparentFrame(ch[2]),
                                    "$indent    ",
                                    inValuePosition = false,
                                    resultTargetVar = null,
                                )
                            append(indent)
                                .append("auto ")
                                .append(elvisTemp)
                                .append(" = ")
                                .append(lhsCode)
                                .append(";\n")
                            val nullCheck =
                                if (bundle.isDocExample) {
                                    "!$elvisTemp"
                                } else {
                                    includes.needsNullUnwrapDef = true
                                    "kxs_is_null($elvisTemp)"
                                }
                            append(indent)
                                .append("if (")
                                .append(nullCheck)
                                .append(") {\n")
                            append(rhsTransfer)
                            append(indent).append("}\n")
                            preLoweredElvisExprs[elvisFrame.frameIndex] =
                                if (bundle.isDocExample) {
                                    "([&](auto&& kxs_ev) -> decltype(auto) { if constexpr (std::is_pointer_v<std::decay_t<decltype(" +
                                        "kxs_ev)>>) return kxs_ev; else return *kxs_ev; }($elvisTemp))"
                                } else {
                                    includes.needsNullUnwrapDef = true
                                    includes.addSystem("type_traits")
                                    includes.addSystem("utility")
                                    "kxs_unwrap_opt($elvisTemp)"
                                }
                            newlyBound.add(elvisFrame.frameIndex)
                        }
                        append(emitStatementBodyWithoutLeadingComments(stmtFrame, indent, inValuePosition, resultTargetVar))
                    }
                } finally {
                    for (idx in newlyBound) {
                        preLoweredElvisExprs.remove(idx)
                    }
                }
            }
        }
        if (inValuePosition && resultTargetVar != null) {
            when (unwrappedStmt.nodeTypeId) {
                KtNodeTypes.IF_ID ->
                    return emitIfStatement(
                        unwrappedStmt,
                        indent,
                        inValuePosition = true,
                        targetReturn = false,
                        targetVar = resultTargetVar,
                    )
                KtNodeTypes.WHEN_ID ->
                    return emitWhenStatement(
                        unwrappedStmt,
                        indent,
                        inValuePosition = true,
                        targetReturn = false,
                        targetVar = resultTargetVar,
                    )
                KtNodeTypes.TRY_ID ->
                    return emitTryStatement(
                        unwrappedStmt,
                        indent,
                        inValuePosition = true,
                        targetReturn = false,
                        targetVar = resultTargetVar,
                    )
                else ->
                    return emitValueAssignmentOrReturn(
                        unwrappedStmt,
                        indent,
                        targetReturn = false,
                        targetVar = resultTargetVar,
                    )
            }
        }

        // Check `atomicfu` loop/update statement FIRST before inline expansions so `atomicField.loop { ... }`
        // never collides with a same-file 0-receiver `inline fun loop(...)`.
        bundle.atomicPlan.atomicOpsByExprFrame[unwrappedStmt.frameIndex]?.let { aop ->
            if (aop.kind == AtomicOpKind.LOOP || aop.kind == AtomicOpKind.UPDATE) {
                return emitAtomicLoopOrUpdateStatement(aop, indent)
            }
        }

        // Check inlined caller lambda parameter call (`onClosedAction()`) inside an expanded same-file `inline fun`
        if (unwrappedStmt.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val calleeSimple = extractCalleeSimpleName(unwrappedStmt)
            inlinedLambdaSubstitutions[calleeSimple]?.let { site ->
                return expandInlinedCallerLambdaCall(unwrappedStmt, site, indent, targetVar = null, targetReturn = false)
            }
        }

        // Check inline call expansion (`let`, `also`, `run`, `apply`, `use`, `repeat`, `withLock`, `synchronized`, `forEach`, inline fun)
        bundle.suspendPlan.inlineExpansionsByCallFrame[unwrappedStmt.frameIndex]?.let { inlineExp ->
            return emitInlineExpansionAsStatement(inlineExp, indent, targetVar = null, targetReturn = false)
        }

        // Check `memScoped { ... }`
        bundle.ffiPlan.memScopedBlocksByFrame[unwrappedStmt.frameIndex]?.let { ms ->
            return emitMemScopedStatement(ms, indent)
        }

        // Check `usePinned { ... }`
        bundle.ffiPlan.usePinnedBlocksByFrame[unwrappedStmt.frameIndex]?.let { up ->
            return emitUsePinnedStatement(up, indent)
        }

        // Check verbatim inline C/C++ call statement
        bundle.ffiPlan.inlineCppByFrame[unwrappedStmt.frameIndex]?.let { rawCpp ->
            return formatIndentedRawCpp(rawCpp, indent)
        }

        if (unwrappedStmt.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val binChildren = FrameAstHelpers.childFrames(stream, unwrappedStmt)
            if (binChildren.size >= 3) {
                val opTokId = FrameAstHelpers.operationTokenId(stream, binChildren[1])
                if (opTokId == KtTokens.ELVIS_ID) {
                    val rhsUnwrapped = unwrapTransparentFrame(binChildren[2])
                    if (isElvisControlTransferTarget(rhsUnwrapped)) {
                        val lhsCode = emitExpression(binChildren[0])
                        val transferStmt =
                            emitStatement(rhsUnwrapped, "$indent    ", inValuePosition = false, resultTargetVar = null)
                        val nullCheck =
                            if (bundle.isDocExample) {
                                "!($lhsCode)"
                            } else {
                                includes.needsNullUnwrapDef = true
                                "kxs_is_null($lhsCode)"
                            }
                        return "${indent}if ($nullCheck) {\n$transferStmt$indent}\n"
                    }
                } else if (opTokId == KtTokens.EQ_ID) {
                    val rhsAssign = unwrapTransparentFrame(binChildren[2])
                    if (rhsAssign.frameIndex !in preLoweredElvisExprs &&
                        rhsAssign.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID
                    ) {
                        val elvisCh = FrameAstHelpers.childFrames(stream, rhsAssign)
                        if (elvisCh.size >= 3 &&
                            FrameAstHelpers.operationTokenId(stream, elvisCh[1]) == KtTokens.ELVIS_ID
                        ) {
                            val elvisRhs = unwrapTransparentFrame(elvisCh[2])
                            if (isElvisControlTransferTarget(elvisRhs)) {
                                val elvisTemp = freshTemp("kxs_elvis")
                                val lhsTarget = emitExpression(binChildren[0])
                                val elvisLhsCode = emitExpression(elvisCh[0])
                                val transferStmt =
                                    emitStatement(elvisRhs, "$indent    ", inValuePosition = false, resultTargetVar = null)
                                val derefElvisExpr =
                                    if (bundle.isDocExample) {
                                        includes.addSystem("type_traits")
                                        "([&](auto&& kxs_ev) -> decltype(auto) { if constexpr (std::is_pointer_v<std::decay_t<decltype(kxs_ev)>>) return kxs_ev; else return *kxs_ev; }($elvisTemp))"
                                    } else {
                                        includes.needsNullUnwrapDef = true
                                        includes.addSystem("type_traits")
                                        includes.addSystem("utility")
                                        "kxs_unwrap_opt($elvisTemp)"
                                    }
                                return buildString {
                                    append(indent)
                                        .append("auto ")
                                        .append(elvisTemp)
                                        .append(" = ")
                                        .append(elvisLhsCode)
                                        .append(";\n")
                                    append(indent)
                                        .append("if (")
                                        .append(if (bundle.isDocExample) "!$elvisTemp" else "kxs_is_null($elvisTemp)")
                                        .append(") {\n")
                                    append(transferStmt)
                                    append(indent).append("}\n")
                                    append(indent)
                                        .append(lhsTarget)
                                        .append(" = ")
                                        .append(derefElvisExpr)
                                        .append(";\n")
                                }
                            }
                        }
                    }
                }
            }
        }

        return when (unwrappedStmt.nodeTypeId) {
            KtNodeTypes.PROPERTY_ID -> emitLocalPropertyDecl(unwrappedStmt, indent)
            KtNodeTypes.FUN_ID -> emitLocalFunctionDecl(unwrappedStmt, indent)
            KtNodeTypes.CLASS_ID,
            KtNodeTypes.OBJECT_DECLARATION_ID,
            -> emitLocalClassOrObjectDecl(unwrappedStmt, indent)
            KtNodeTypes.BINARY_WITH_TYPE_ID -> {
                val castLhs = FrameAstHelpers.childFrames(stream, unwrappedStmt).firstOrNull()
                val opRef = FrameAstHelpers.firstChildFrame(stream, unwrappedStmt, KtNodeTypes.OPERATION_REFERENCE_ID)
                val isHardAs = opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.AS_KEYWORD_ID
                val varKt =
                    if (isHardAs && castLhs?.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        FrameAstHelpers.directIdentPacket(stream, castLhs)?.unescapedName
                    } else {
                        null
                    }
                if (varKt != null) {
                    val castTemp = freshTemp("kxs_cast")
                    val castExpr = emitBinaryWithTypeExpr(unwrappedStmt)
                    bindLocalName(varKt, castTemp)
                    unmarkStdAnyVar(varKt)
                    "${indent}auto $castTemp = $castExpr;\n"
                } else {
                    "$indent${emitBinaryWithTypeExpr(unwrappedStmt)};\n"
                }
            }
            KtNodeTypes.DESTRUCTURING_DECLARATION_ID -> emitDestructuringDecl(unwrappedStmt, indent)
            KtNodeTypes.RETURN_ID -> emitReturnStmt(unwrappedStmt, indent)
            KtNodeTypes.THROW_ID -> emitThrowStmt(unwrappedStmt, indent)
            KtNodeTypes.BREAK_ID -> "${indent}break;\n"
            KtNodeTypes.CONTINUE_ID -> "${indent}continue;\n"
            KtNodeTypes.IF_ID ->
                emitIfStatement(unwrappedStmt, indent, inValuePosition = false, targetReturn = false, targetVar = null)
            KtNodeTypes.WHEN_ID ->
                emitWhenStatement(unwrappedStmt, indent, inValuePosition = false, targetReturn = false, targetVar = null)
            KtNodeTypes.TRY_ID ->
                emitTryStatement(unwrappedStmt, indent, inValuePosition = false, targetReturn = false, targetVar = null)
            KtNodeTypes.WHILE_ID -> emitWhileStmt(unwrappedStmt, indent)
            KtNodeTypes.DO_WHILE_ID -> emitDoWhileStmt(unwrappedStmt, indent)
            KtNodeTypes.FOR_ID -> emitForStmt(unwrappedStmt, indent)
            KtNodeTypes.BLOCK_ID ->
                "${emitBlock(unwrappedStmt, indent, isValueBlock = false, resultTargetVar = null)}\n"
            else -> {
                if (unwrappedStmt.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                    FrameAstHelpers.directIdentPacket(stream, unwrappedStmt)?.unescapedName == "Unit"
                ) {
                    return ""
                }
                val exprText = emitExpression(unwrappedStmt)
                if (PacketFlags.hasFlag(unwrappedStmt.flags, PacketFlags.DIVERGING_EXPR)) {
                    includes.addSystem("utility")
                    "$indent$exprText;\n${indent}std::unreachable();\n"
                } else {
                    "$indent$exprText;\n"
                }
            }
        }
    }

    private fun emitLocalPropertyDecl(
        propFrame: PacketFrame,
        indent: String,
    ): String {
        val ident = FrameAstHelpers.directIdentPacket(stream, propFrame)
        val kotlinName = ident?.unescapedName ?: "local_val"
        val hadOuterLocal = resolveBoundName(kotlinName) != null
        val baseCppName = ident?.targetCppName ?: CppNamingPolicy.toSnakeCase(kotlinName)
        val cppName =
            if (hadOuterLocal && !bundle.isDocExample) {
                freshTemp("${baseCppName}_shadow")
            } else {
                baseCppName
            }
        val isVar = FrameAstHelpers.hasDirectKeyword(stream, propFrame, KtTokens.VAR_KEYWORD_ID)
        val (_, typeFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, propFrame)
        val initFrame = FrameAstHelpers.extractInitializerAfterEquals(stream, propFrame)

        // 1. Check `val x = alloc<T>()` or `allocArray<T>(n)` inside `memScoped`
        if (initFrame != null) {
            val cInteropAlloc = bundle.ffiPlan.cInteropOpsByExprFrame[initFrame.frameIndex]
            if (cInteropAlloc != null && cInteropAlloc.kind == CInteropOpKind.ALLOC_SCALAR) {
                val elemType =
                    cInteropAlloc.targetTypeFrameIndex
                        ?.let { translateType(stream.frames[it], CppTypePosition.VALUE) }
                        ?: "std::int32_t"
                bindLocalName(kotlinName, cppName)
                return "$indent$elemType $cppName{};\n"
            }
            if (cInteropAlloc != null && cInteropAlloc.kind == CInteropOpKind.ALLOC_ARRAY) {
                includes.addSystem("vector")
                val elemType =
                    cInteropAlloc.targetTypeFrameIndex
                        ?.let { translateType(stream.frames[it], CppTypePosition.TYPE_ARGUMENT) }
                        ?: "std::uint8_t"
                val countExpr =
                    cInteropAlloc.argExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "0"
                val bufName = "${cppName}_storage"
                bindLocalName(kotlinName, "$bufName.data()")
                return "${indent}std::vector<$elemType> $bufName(static_cast<std::size_t>($countExpr));\n"
            }

            // 2. Check inline call expansion assigned directly to local `val x = obj.let { ... }`
            bundle.suspendPlan.inlineExpansionsByCallFrame[initFrame.frameIndex]?.let { inlineExp ->
                val targetInlineFn =
                    inlineExp.targetInlineFunFrameIndex?.let { bundle.symbols.functionsByFrame[it] }
                val targetInlineCls =
                    targetInlineFn?.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
                val undeclaredTps =
                    if (targetInlineFn != null) {
                        (targetInlineCls?.typeParams.orEmpty() + targetInlineFn.typeParams)
                            .map { it.name }
                            .filter { it !in activeTypeParams }
                            .toSet()
                    } else {
                        emptySet()
                    }
                val baseInlineSimple =
                    typeFrame?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, it) }
                        ?: targetInlineFn
                            ?.returnTypeFrameIndex
                            ?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
                            ?.takeIf { it !in undeclaredTps }
                val baseDeclType =
                    when {
                        typeFrame != null ->
                            translateType(typeFrame, CppTypePosition.VALUE, propFrame.flags)
                        targetInlineFn?.returnTypeFrameIndex != null ->
                            resolveInlineReturnDeclType(initFrame, inlineExp, targetInlineFn, targetInlineCls, undeclaredTps)
                        else -> "std::any"
                    }
                val callExprFrame = extractInlineCallExpressionFrame(initFrame)
                val inlineCalleeName = callExprFrame?.let { extractCalleeSimpleName(it) }.orEmpty()
                val lambdaOwnLabel = callExprFrame?.let { extractInlineLambdaOwnLabel(it, inlineExp.lambdaFrameIndex) }
                val iifeCheck =
                    checkInlineIifeEligibility(
                        lambdaFrameIndex = inlineExp.lambdaFrameIndex,
                        lambdaOwnLabel = lambdaOwnLabel,
                        inlineCalleeName = inlineCalleeName,
                    )
                val tailInferred =
                    if (typeFrame == null && baseDeclType == "std::any") {
                        inferInlineLambdaTailType(inlineExp.lambdaFrameIndex)
                    } else {
                        null
                    }
                val declaredInlineSimple = baseInlineSimple ?: tailInferred?.simpleName
                val effectiveDeclType =
                    if (baseDeclType != "std::any" || tailInferred?.cppType == null) {
                        baseDeclType
                    } else {
                        tailInferred.cppType
                    }

                val deadBeforeLocalSuspend =
                    PacketFlags.hasFlag(propFrame.flags, PacketFlags.DEAD_BEFORE_NEXT_SUSPEND)
                val isMutableInlineVal =
                    isInherentlyMutableLocalVal(declaredInlineSimple, effectiveDeclType, null)
                val inlineConstQual =
                    if (!isVar && !deadBeforeLocalSuspend && !isMutableInlineVal) "const " else ""

                if (iifeCheck.canEmitIife) {
                    val iifeRetType =
                        when {
                            typeFrame != null || baseDeclType != "std::any" -> baseDeclType
                            tailInferred?.cppType != null -> tailInferred.cppType
                            iifeCheck.hasLambdaOwnReturn -> {
                                includes.addSystem("any")
                                "std::any"
                            }
                            else -> null
                        }
                    val iifeRetSuffix = if (iifeRetType != null) " -> $iifeRetType" else ""
                    val iifeBody =
                        withinExpressionIife {
                            withExpectedTargetType(iifeRetType?.takeIf { it != "std::any" }) {
                                emitInlineExpansionAsStatement(
                                    inlineExp,
                                    "$indent    ",
                                    targetVar = null,
                                    targetReturn = true,
                                )
                            }
                        }
                    applyInlineLocalVarMetadata(kotlinName, cppName, declaredInlineSimple, effectiveDeclType)
                    val outerVarType = if (typeFrame != null) effectiveDeclType else "auto"
                    return buildString {
                        append(indent)
                            .append(inlineConstQual)
                            .append(outerVarType)
                            .append(' ')
                            .append(cppName)
                            .append(" = ([&]()")
                            .append(iifeRetSuffix)
                            .append(" {\n")
                            .append(iifeBody)
                            .append(indent)
                            .append("}());\n")
                    }
                }

                val tempType =
                    if (effectiveDeclType == "void" || effectiveDeclType == "[[noreturn]] void") {
                        includes.addSystem("any")
                        "std::any"
                    } else {
                        if (effectiveDeclType == "std::any") includes.addSystem("any")
                        effectiveDeclType
                    }
                val useOptWrapper =
                    tempType != "std::any" &&
                        !tempType.startsWith("std::optional<") &&
                        !isCppPtrOrSharedType(tempType) &&
                        tempType != "std::exception_ptr"
                if (useOptWrapper) includes.addSystem("optional")
                includes.addSystem("utility")
                val resTmp = freshTemp("kxs_inln_res")
                val resTmpDeclType = if (useOptWrapper) "std::optional<$tempType>" else tempType
                val expandedBody =
                    withExpectedTargetType(tempType.takeIf { it != "std::any" }) {
                        emitInlineExpansionAsStatement(inlineExp, indent, targetVar = resTmp, targetReturn = false)
                    }
                applyInlineLocalVarMetadata(kotlinName, cppName, declaredInlineSimple, effectiveDeclType)
                val finalVarType = if (typeFrame != null) effectiveDeclType else "auto"
                val movedExpr = if (useOptWrapper) "std::move(*$resTmp)" else "std::move($resTmp)"
                return buildString {
                    append(indent)
                        .append(resTmpDeclType)
                        .append(' ')
                        .append(resTmp)
                        .append("{};\n")
                        .append(expandedBody)
                        .append(indent)
                        .append(inlineConstQual)
                        .append(finalVarType)
                        .append(' ')
                        .append(cppName)
                        .append(" = ")
                        .append(movedExpr)
                        .append(";\n")
                }
            }
        }

        // 3. Check `atomicfu` local atomic variable
        bundle.atomicPlan.atomicFieldsByPropFrame[propFrame.frameIndex]?.let { af ->
            includes.addSystem("atomic")
            val elemType =
                af.explicitElementTypeFrameIndex
                    ?.let { translateType(stream.frames[it], CppTypePosition.TYPE_ARGUMENT) }
                    ?: af.inferredElementCppType
                    ?: "void*"
            val initVal = af.initExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "{}"
            bindLocalName(kotlinName, cppName)
            return "${indent}std::atomic<$elemType> $cppName{$initVal};\n"
        }

        // 4. Kotlin/Native Docking-Ring GC mode (`KONAN_GC`) local roots vs cross-suspend handles!
        if (bundle.config.gcMode == CppGcMode.KONAN_GC) {
            if (PacketFlags.hasFlag(propFrame.flags, PacketFlags.GC_PERSISTENT_EXTERNAL_RC)) {
                includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                val initVal = initFrame?.let { emitExpression(it) } ?: "nullptr"
                bindLocalName(kotlinName, cppName)
                return "${indent}OwningExternalRCRef $cppName($initVal);\n"
            }
            if (PacketFlags.hasFlag(propFrame.flags, PacketFlags.GC_PHYSICAL_STACK_ROOT)) {
                includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                val holderName = "${cppName}_holder"
                val initVal = initFrame?.let { emitExpression(it) }
                bindLocalName(kotlinName, "$holderName.obj()")
                return if (initVal != null) {
                    "${indent}ObjHolder $holderName($initVal);\n"
                } else {
                    "${indent}ObjHolder $holderName{};\n"
                }
            }
        }

        val declaredSimpleType =
            typeFrame?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, it) }
        val cppType =
            if (typeFrame != null) {
                val simple = declaredSimpleType
                if (simple != null) markLocalType(kotlinName, simple)
                if (simple == "Any") {
                    markStdAnyVar(kotlinName)
                } else if (simple != null) {
                    unmarkStdAnyVar(kotlinName)
                }
                if (simple in EXCEPTION_PTR_KOTLIN_TYPES) markExceptionPtrVar(kotlinName)
                translateType(typeFrame, CppTypePosition.VALUE, propFrame.flags)
            } else {
                val inferredInitSimple = initFrame?.let { inferExpressionSimpleTypeName(it) }
                if (inferredInitSimple != null && inferredInitSimple != "Any") {
                    unmarkStdAnyVar(kotlinName)
                }
                "auto"
            }
        val deadBeforeSuspend = PacketFlags.hasFlag(propFrame.flags, PacketFlags.DEAD_BEFORE_NEXT_SUSPEND)
        val isMutableVal = isInherentlyMutableLocalVal(declaredSimpleType, cppType, initFrame)
        val constQual =
            if (!isVar && !deadBeforeSuspend && cppType != "void" && !isMutableVal) "const " else ""
        if (initFrame != null && initFrame.frameIndex !in preLoweredElvisExprs && initFrame.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val ch = FrameAstHelpers.childFrames(stream, initFrame)
            if (ch.size >= 3 &&
                FrameAstHelpers.operationTokenId(stream, ch[1]) == KtTokens.ELVIS_ID
            ) {
                val rhsUnwrapped = unwrapTransparentFrame(ch[2])
                if (isElvisControlTransferTarget(rhsUnwrapped)) {
                    val elvisTemp = freshTemp("kxs_elvis")
                    val lhsCode = emitExpression(ch[0])
                    val lhsIsPolyPtr = isPointerOrSharedPtrReceiver(ch[0], lhsCode)
                    val lhsSimpleType = declaredSimpleType ?: inferExpressionSimpleTypeName(ch[0])
                    val transferStmt =
                        emitStatement(rhsUnwrapped, "$indent    ", inValuePosition = false, resultTargetVar = null)
                    val derefElvisExpr =
                        if (bundle.isDocExample) {
                            includes.addSystem("type_traits")
                            "([&](auto&& kxs_ev) -> decltype(auto) { if constexpr (std::is_pointer_v<std::decay_t<decltype(kxs_ev)>>) return kxs_ev; else return *kxs_ev; }($elvisTemp))"
                        } else {
                            includes.needsNullUnwrapDef = true
                            includes.addSystem("type_traits")
                            includes.addSystem("utility")
                            "kxs_unwrap_opt($elvisTemp)"
                        }
                    bindLocalName(kotlinName, cppName)
                    if (lhsSimpleType != null && declaredSimpleType == null) markLocalType(kotlinName, lhsSimpleType)
                    if (lhsIsPolyPtr || (cppType != "auto" && isCppPtrOrSharedType(cppType))) {
                        markLocalPolyVar(kotlinName)
                    }
                    return buildString {
                        append(indent)
                        append("auto ")
                        append(elvisTemp)
                        append(" = ")
                        append(lhsCode)
                        append(";\n")
                        append(indent)
                        append("if (")
                        append(if (bundle.isDocExample) "!$elvisTemp" else "kxs_is_null($elvisTemp)")
                        append(") {\n")
                        append(transferStmt)
                        append(indent)
                        append("}\n")
                        append(indent)
                        append(constQual)
                        append(cppType)
                        append(' ')
                        append(cppName)
                        append(" = ")
                        append(derefElvisExpr)
                        append(";\n")
                    }
                }
            }
            val unwrappedTryInit = unwrapTransparentFrame(initFrame)
            val tryCatches =
                if (unwrappedTryInit.nodeTypeId == KtNodeTypes.TRY_ID) {
                    FrameAstHelpers.childFramesOfType(stream, unwrappedTryInit, KtNodeTypes.CATCH_ID)
                } else {
                    emptyList()
                }
            if (!bundle.isDocExample &&
                unwrappedTryInit.nodeTypeId == KtNodeTypes.TRY_ID &&
                (
                    containsUnlabeledReturn(unwrappedTryInit) ||
                        (
                            cppType == "auto" &&
                                enclosingFunctionReturnType == "std::any" &&
                                tryCatches.isNotEmpty()
                        )
                )
            ) {
                includes.addSystem("optional")
                includes.addSystem("utility")
                val allCatchesDiverge =
                    tryCatches.isNotEmpty() &&
                        tryCatches.all { catchFrame ->
                            val catchBody =
                                FrameAstHelpers.firstChildFrame(stream, catchFrame, KtNodeTypes.BLOCK_ID)
                                    ?: FrameAstHelpers.childFrames(stream, catchFrame).lastOrNull()
                            catchBody != null && doesBranchAlwaysTransferControl(catchBody)
                        }
                val tryBlockLastExpr =
                    if (cppType == "auto" && allCatchesDiverge) {
                        FrameAstHelpers
                            .firstChildFrame(stream, unwrappedTryInit, KtNodeTypes.BLOCK_ID)
                            ?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                    } else {
                        null
                    }
                val valType =
                    if (cppType != "auto") {
                        cppType
                    } else {
                        when (tryBlockLastExpr?.let { inferExpressionSimpleTypeName(it) }) {
                            "Int" -> {
                                includes.addSystem("cstdint")
                                "std::int32_t"
                            }
                            "Long" -> {
                                includes.addSystem("cstdint")
                                "std::int64_t"
                            }
                            "Boolean" -> "bool"
                            "Double" -> "double"
                            "Float" -> "float"
                            "String" -> {
                                includes.addSystem("string")
                                "std::string"
                            }
                            else -> "std::any"
                        }
                    }
                if (valType == "std::any") includes.addSystem("any")
                val optTemp = freshTemp("kxs_try_val")
                val tryStmtCode =
                    emitTryStatement(
                        unwrappedTryInit,
                        indent,
                        inValuePosition = true,
                        targetReturn = false,
                        targetVar = optTemp,
                    )
                bindLocalName(kotlinName, cppName)
                if (valType == "std::any") markStdAnyVar(kotlinName)
                if (valType != "std::any" && isCppPtrOrSharedType(valType)) markLocalPolyVar(kotlinName)
                return buildString {
                    append(indent)
                        .append("std::optional<")
                        .append(valType)
                        .append("> ")
                        .append(optTemp)
                        .append("{};\n")
                    append(tryStmtCode)
                    append(indent)
                        .append(constQual)
                        .append(if (cppType == "auto") "auto" else cppType)
                        .append(' ')
                        .append(cppName)
                        .append(" = std::move(*")
                        .append(optTemp)
                        .append(");\n")
                }
            }
        }
        val rawInitBeforeBind =
            initFrame?.let {
                withExpectedTargetType(if (cppType != "auto") cppType else null) {
                    emitExpression(it)
                }
            }
        val initIsPtrBeforeBind =
            initFrame != null &&
                rawInitBeforeBind != null &&
                isPointerOrSharedPtrReceiver(initFrame, rawInitBeforeBind)
        val initSimpleBeforeBind =
            if (declaredSimpleType == null && initFrame != null) {
                inferExpressionSimpleTypeName(initFrame)
            } else {
                null
            }
        bindLocalName(kotlinName, cppName)
        val effDeclaredSimple = declaredSimpleType?.let { bundle.symbols.importAliases[it] ?: it }
        if ((cppType != "auto" && isCppPtrOrSharedType(cppType)) ||
            (effDeclaredSimple != null && effDeclaredSimple in bundle.symbols.sharedPtrTypeAliases) ||
            initIsPtrBeforeBind
        ) {
            markLocalPolyVar(kotlinName)
        }
        if (initSimpleBeforeBind != null) {
            markLocalType(kotlinName, initSimpleBeforeBind)
        }
        val isSharedElvisInit =
            rawInitBeforeBind != null &&
                rawInitBeforeBind.startsWith("kxs_elvis(") &&
                rawInitBeforeBind.contains("kxs_safe_as_cast<std::shared_ptr<")
        if (isSharedElvisInit ||
            (
                rawInitBeforeBind != null &&
                    (
                        rawInitBeforeBind.startsWith("kxs_safe_as_cast<std::shared_ptr<") ||
                            rawInitBeforeBind.startsWith("kxs_as_cast<std::shared_ptr<") ||
                            rawInitBeforeBind.startsWith("std::make_shared<") ||
                            rawInitBeforeBind.startsWith("std::shared_ptr(") ||
                            rawInitBeforeBind.startsWith("std::shared_ptr<") ||
                            rawInitBeforeBind.startsWith("kxs_shared_from_this(") ||
                            rawInitBeforeBind.startsWith("kotlin::coroutines::intrinsics::intercepted(")
                    )
            )
        ) {
            markLocalPolyVar(kotlinName)
            unmarkStdAnyVar(kotlinName)
        }
        if (initFrame != null) {
            val unwrappedInit = unwrapTransparentFrame(initFrame)
            if (unwrappedInit.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                val initCallee = extractCalleeSimpleName(unwrappedInit)
                val effInitCallee = bundle.symbols.importAliases[initCallee] ?: initCallee
                if (initCallee in localPolymorphicClasses ||
                    effInitCallee in bundle.symbols.sharedHierarchyTypeNames ||
                    effInitCallee in DISPATCHER_OR_EVENT_LOOP_FACTORY_NAMES ||
                    CppTypeTranslator.isSharedHierarchyType(effInitCallee)
                ) {
                    markLocalPolyVar(kotlinName)
                    if (declaredSimpleType == null) markLocalType(kotlinName, effInitCallee)
                }
            } else if (unwrappedInit.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID) {
                val extSimple = extensionReceiverSimpleName
                if (extSimple != null) {
                    if (extensionReceiverIsPointer || isSharedThisClass(extSimple)) {
                        markLocalPolyVar(kotlinName)
                    }
                    if (declaredSimpleType == null) markLocalType(kotlinName, extSimple)
                } else if (enclosingClass != null && isSharedThisClass(enclosingClass.kotlinName)) {
                    markLocalPolyVar(kotlinName)
                    if (declaredSimpleType == null) markLocalType(kotlinName, enclosingClass.kotlinName)
                }
            } else if (unwrappedInit.nodeTypeId == KtNodeTypes.IF_ID) {
                if (isPointerOrSharedPtrReceiver(unwrappedInit, "")) {
                    markLocalPolyVar(kotlinName)
                }
                if (declaredSimpleType == null) {
                    inferExpressionSimpleTypeName(unwrappedInit)?.let { markLocalType(kotlinName, it) }
                }
            }
        }
        return if (rawInitBeforeBind != null) {
            val hasTypedDeclWithSameName =
                Regex("""(?:auto|[A-Za-z_][A-Za-z0-9_:<>]*[*&]?)\s+${Regex.escape(cppName)}\b""")
                    .containsMatchIn(rawInitBeforeBind)
            val rawInit =
                if (!hadOuterLocal &&
                    !hasTypedDeclWithSameName &&
                    enclosingClass != null &&
                    isEnclosingClassProperty(kotlinName) &&
                    containsUnquotedStandaloneIdent(rawInitBeforeBind, cppName)
                ) {
                    replaceUnquotedStandaloneIdent(rawInitBeforeBind, cppName, "this->$cppName")
                } else {
                    rawInitBeforeBind
                }
            val coercedInit =
                when {
                    rawInit.startsWith("std::vector<void*>(") &&
                        cppType.startsWith("std::vector<") &&
                        cppType != "std::vector<void*>" ->
                        cppType + rawInit.removePrefix("std::vector<void*>")
                    rawInit == "nullptr" && cppType.startsWith("std::optional<") -> {
                        includes.addSystem("optional")
                        "std::nullopt"
                    }
                    rawInit == "nullptr" && cppType == "std::any" -> "{}"
                    cppType == "std::exception_ptr" -> wrapWithExceptionPtrConversion(rawInit, initFrame)
                    else -> rawInit
                }
            if (containsUnquotedStandaloneIdent(coercedInit, cppName)) {
                includes.addSystem("utility")
                val tmp = freshTemp("kxs_init")
                val targetCppT = if (cppType == "void") "auto" else cppType
                "${indent}auto $tmp = $coercedInit;\n$indent$constQual$targetCppT $cppName = std::move($tmp);\n"
            } else {
                "$indent$constQual$cppType $cppName = $coercedInit;\n"
            }
        } else {
            "$indent$cppType $cppName{};\n"
        }
    }

    private fun emitLocalFunctionDecl(
        fnFrame: PacketFrame,
        indent: String,
    ): String {
        val ident = FrameAstHelpers.directIdentPacket(stream, fnFrame)
        val kotlinName = ident?.unescapedName ?: "local_fn"
        val cppName = ident?.targetCppName ?: CppNamingPolicy.toSnakeCase(kotlinName)
        val paramList = FrameAstHelpers.firstChildFrame(stream, fnFrame, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
        val params = FrameAstHelpers.extractValueParams(stream, paramList)
        val (recvTypeFrame, retTypeFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, fnFrame)
        val blockBody = FrameAstHelpers.firstChildFrame(stream, fnFrame, KtNodeTypes.BLOCK_ID)
        val exprBody = if (blockBody == null) FrameAstHelpers.extractInitializerAfterEquals(stream, fnFrame) else null
        val retType =
            when {
                retTypeFrame != null ->
                    bundle.typeTranslator.translateTypeFrame(retTypeFrame, CppTypePosition.RETURN, fnFrame.flags)
                blockBody != null -> "void"
                else -> "auto"
            }

        val bodyTarget = blockBody ?: exprBody
        val isRecursive = bodyTarget != null && frameContainsIdentifier(bodyTarget, kotlinName)
        if (recvTypeFrame != null) {
            localExtensionFunctions[kotlinName] = cppName
        } else {
            bindLocalName(kotlinName, cppName)
        }

        val fnEmitter =
            CppExprAndStmtEmitter(
                bundle = bundle,
                activeTypeParams = activeTypeParams,
                enclosingClass = enclosingClass,
                receiverSubstitution = if (recvTypeFrame != null) "kxs_self" else receiverSubstitution,
                isVoidReturnContext = retType == "void" || retType == "[[noreturn]] void",
                enclosingFunctionReturnType = retType.takeIf { it != "auto" },
                localPolymorphicClasses = localPolymorphicClasses,
                localExtensionFunctions = LinkedHashMap(localExtensionFunctions),
            )
        for (scopeMap in localBindings) {
            for ((k, v) in scopeMap) {
                fnEmitter.bindLocalName(k, v)
            }
        }
        if (isRecursive) {
            if (recvTypeFrame != null) {
                fnEmitter.localExtensionFunctions[kotlinName] = "kxs_fn_self"
            } else {
                fnEmitter.bindLocalName(kotlinName, "kxs_fn_self")
            }
        }

        val sigParts = ArrayList<String>(params.size + 2)
        if (isRecursive) {
            sigParts.add("this auto&& kxs_fn_self")
        }
        if (recvTypeFrame != null) {
            val rType = bundle.typeTranslator.translateTypeFrame(recvTypeFrame, CppTypePosition.EXTENSION_RECEIVER)
            val passType =
                if (rType.endsWith("&") || rType.endsWith("*")) {
                    rType
                } else {
                    "const $rType&"
                }
            sigParts.add("$passType kxs_self")
        }
        for (vp in params) {
            fnEmitter.bindValueParam(vp)
            val vpSimple = vp.typeFrameIndex?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
            val pType =
                if (vpSimple != null && vpSimple in localPolymorphicClasses) {
                    includes.addSystem("memory")
                    fnEmitter.markLocalPolyVar(vp.name)
                    "const std::shared_ptr<${CppNamingPolicy.toTypeName(vpSimple)}>&"
                } else {
                    vp.typeFrameIndex
                        ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER) }
                        ?: "auto"
                }
            sigParts.add("$pType ${vp.cppName}")
        }

        val trailingRet = if (retType != "auto") " -> $retType" else ""
        val bodyCode =
            when {
                blockBody != null ->
                    fnEmitter.emitBlock(blockBody, indent, isValueBlock = retType != "void" && retType != "auto", resultTargetVar = null)
                exprBody != null -> {
                    val innerIndent = "$indent    "
                    if (retType == "void") {
                        "{\n$innerIndent${fnEmitter.emitExpression(exprBody)};\n$indent}"
                    } else {
                        "{\n${innerIndent}return ${fnEmitter.emitExpression(exprBody)};\n$indent}"
                    }
                }
                else -> "{}"
            }
        return "${indent}auto $cppName = [&](${sigParts.joinToString(", ")})$trailingRet $bodyCode;\n"
    }

    private fun frameContainsIdentifier(
        frame: PacketFrame,
        targetIdent: String,
    ): Boolean {
        if (frame.firstPacketIndex > frame.lastPacketIndex || stream.packets.isEmpty()) return false
        for (pktIdx in frame.firstPacketIndex..frame.lastPacketIndex) {
            val pkt = stream.packets[pktIdx]
            if (pkt is SyntaxPacket.IdentPacket && pkt.unescapedName == targetIdent) {
                return true
            }
        }
        return false
    }

    private fun emitLocalClassOrObjectDecl(
        declFrame: PacketFrame,
        indent: String,
    ): String {
        val rawName = FrameAstHelpers.directIdentPacket(stream, declFrame)?.unescapedName ?: "LocalStruct"
        val structName = CppNamingPolicy.toTypeName(rawName)
        val primaryCtor = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.PRIMARY_CONSTRUCTOR_ID)
        val ctorParamList = primaryCtor?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.VALUE_PARAMETER_LIST_ID) }
        val ctorParams = FrameAstHelpers.extractValueParams(stream, ctorParamList)

        val superList = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.SUPER_TYPE_LIST_ID)
        val superBases = ArrayList<String>()
        val superCtorInits = ArrayList<String>()
        if (superList != null) {
            for (entry in FrameAstHelpers.childFrames(stream, superList)) {
                val tr = FrameAstHelpers.findDescendantFrame(stream, entry, KtNodeTypes.TYPE_REFERENCE_ID) ?: continue
                val baseType = bundle.typeTranslator.translateTypeFrame(tr, CppTypePosition.TYPE_ARGUMENT)
                superBases.add(baseType)
                if (entry.nodeTypeId == KtNodeTypes.SUPER_TYPE_CALL_ENTRY_ID) {
                    val argList = FrameAstHelpers.findDescendantFrame(stream, entry, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
                    val args =
                        argList
                            ?.let { al ->
                                FrameAstHelpers
                                    .childFramesOfType(stream, al, KtNodeTypes.VALUE_ARGUMENT_ID)
                                    .mapNotNull { va ->
                                        FrameAstHelpers
                                            .childFrames(stream, va)
                                            .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                                            ?.let { emitExpression(it) }
                                    }
                            }.orEmpty()
                    val finalArgs =
                        if (baseType.endsWith("AbstractCoroutineContextElement") ||
                            baseType.contains("AbstractCoroutineContextKey")
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
                    superCtorInits.add("$baseType(${finalArgs.joinToString(", ")})")
                }
            }
        }

        if (superBases.isNotEmpty() && declFrame.nodeTypeId == KtNodeTypes.CLASS_ID) {
            localPolymorphicClasses.add(rawName)
        }
        val superClause = if (superBases.isNotEmpty()) " : ${superBases.joinToString(", ") { "public $it" }}" else ""
        val classBody = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.CLASS_BODY_ID)
        val memberIndent = "$indent    "

        val ctorInitEmitter =
            CppExprAndStmtEmitter(
                bundle = bundle,
                activeTypeParams = activeTypeParams,
                localPolymorphicClasses = localPolymorphicClasses,
                localExtensionFunctions = localExtensionFunctions,
            )
        for (vp in ctorParams) {
            ctorInitEmitter.bindValueParam(vp)
        }
        val ctorParamNames = ctorParams.map { it.name }.toSet()

        val fieldBindings = LinkedHashMap<String, String>()
        val fieldDecls = ArrayList<String>()
        val ctorMemberInits = ArrayList<String>(superCtorInits)

        for (vp in ctorParams) {
            if (vp.isVal || vp.isVar) {
                val fType =
                    vp.typeFrameIndex
                        ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE) }
                        ?: "std::any"
                fieldDecls.add("$memberIndent$fType ${vp.cppName}{};")
                ctorMemberInits.add("${vp.cppName}(${vp.cppName})")
                fieldBindings[vp.name] = "this->${vp.cppName}"
            }
        }

        if (classBody != null) {
            for (member in FrameAstHelpers.childFrames(stream, classBody)) {
                if (member.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                    val pIdent = FrameAstHelpers.directIdentPacket(stream, member)?.unescapedName ?: "field"
                    val atomicField = bundle.atomicPlan.atomicFieldsByPropFrame[member.frameIndex]
                    if (atomicField != null) {
                        includes.addSystem("atomic")
                        val elemT =
                            atomicField.explicitElementTypeFrameIndex
                                ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT) }
                                ?: atomicField.inferredElementCppType
                                ?: "void*"
                        val initFrame = atomicField.initExprFrameIndex?.let { stream.frames[it] }
                        if (initFrame != null && ctorParamNames.any { frameContainsIdentifier(initFrame, it) }) {
                            fieldDecls.add("${memberIndent}std::atomic<$elemT> ${atomicField.cppName}{};")
                            ctorMemberInits.add("${atomicField.cppName}(${ctorInitEmitter.emitExpression(initFrame)})")
                        } else {
                            val initExpr = initFrame?.let { ctorInitEmitter.emitExpression(it) } ?: "{}"
                            fieldDecls.add("${memberIndent}std::atomic<$elemT> ${atomicField.cppName}{$initExpr};")
                        }
                        fieldBindings[pIdent] = "this->${atomicField.cppName}"
                    } else {
                        val pCpp = CppNamingPolicy.toSnakeCase(pIdent)
                        val (_, pTypeFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, member)
                        val pInit = FrameAstHelpers.extractInitializerAfterEquals(stream, member)
                        val pType =
                            pTypeFrame?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.VALUE) }
                                ?: "auto"
                        if (pInit != null && pType != "auto" && ctorParamNames.any { frameContainsIdentifier(pInit, it) }) {
                            fieldDecls.add("$memberIndent$pType $pCpp{};")
                            ctorMemberInits.add("$pCpp(${ctorInitEmitter.emitExpression(pInit)})")
                        } else if (pInit != null) {
                            fieldDecls.add("$memberIndent$pType $pCpp = ${ctorInitEmitter.emitExpression(pInit)};")
                        } else {
                            fieldDecls.add("$memberIndent$pType $pCpp{};")
                        }
                        fieldBindings[pIdent] = "this->$pCpp"
                    }
                }
            }
        }

        return buildString {
            append("${indent}struct $structName$superClause {\n")
            for (fd in fieldDecls) {
                append(fd).append('\n')
            }
            if (ctorParams.isNotEmpty() || ctorMemberInits.isNotEmpty()) {
                val renderedCtorParams =
                    ctorParams.joinToString(", ") { vp ->
                        val pT =
                            vp.typeFrameIndex
                                ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER) }
                                ?: "auto"
                        "$pT ${vp.cppName}"
                    }
                val explicitKw = if (ctorParams.size == 1) "explicit " else ""
                append(memberIndent)
                    .append(explicitKw)
                    .append(structName)
                    .append('(')
                    .append(renderedCtorParams)
                    .append(')')
                if (ctorMemberInits.isNotEmpty()) {
                    append(" : ").append(ctorMemberInits.joinToString(", "))
                }
                append(" {}\n")
            }
            if (classBody != null) {
                for (member in FrameAstHelpers.childFrames(stream, classBody)) {
                    if (member.nodeTypeId == KtNodeTypes.FUN_ID) {
                        val mIdent = FrameAstHelpers.directIdentPacket(stream, member)?.unescapedName ?: "member_fn"
                        val mCpp = CppNamingPolicy.toSnakeCase(mIdent)
                        val (_, retFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, member)
                        val blockB = FrameAstHelpers.firstChildFrame(stream, member, KtNodeTypes.BLOCK_ID)
                        val exprB = if (blockB == null) FrameAstHelpers.extractInitializerAfterEquals(stream, member) else null
                        val retT =
                            when {
                                retFrame != null -> bundle.typeTranslator.translateTypeFrame(retFrame, CppTypePosition.RETURN)
                                blockB != null -> "void"
                                else -> "auto"
                            }
                        val isVoidM = retT == "void" || retT == "[[noreturn]] void"
                        val pList = FrameAstHelpers.firstChildFrame(stream, member, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
                        val vps = FrameAstHelpers.extractValueParams(stream, pList)
                        val mEmitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                activeTypeParams = activeTypeParams,
                                isVoidReturnContext = isVoidM,
                                enclosingFunctionReturnType = retT.takeIf { it != "auto" },
                                localPolymorphicClasses = localPolymorphicClasses,
                                localExtensionFunctions = localExtensionFunctions,
                            )
                        for ((fKt, fCpp) in fieldBindings) {
                            mEmitter.bindLocalName(fKt, fCpp)
                        }
                        for (vp in vps) {
                            mEmitter.bindValueParam(vp)
                        }
                        val isZeroArgAnyMethod = (mIdent == "toString" || mIdent == "hashCode") && vps.isEmpty()
                        val isOv =
                            PacketFlags.hasFlag(member.flags, PacketFlags.MOD_OVERRIDE) &&
                                superBases.isNotEmpty() &&
                                (
                                    !isZeroArgAnyMethod ||
                                        superBases.any { b -> KNOWN_TO_STRING_SUPER_FRAGMENTS.any { frag -> b.contains(frag) } }
                                )
                        val bodyF = blockB ?: exprB
                        val bodyCode =
                            if (bodyF != null) {
                                mEmitter.emitBlockOrExprBody(bodyF, exprB != null, isVoidM, memberIndent)
                            } else {
                                "{}"
                            }
                        val renderedParams = renderClassLikeMethodParams(vps, bodyCode)
                        append(memberIndent)
                            .append(retT)
                            .append(' ')
                            .append(mCpp)
                            .append('(')
                            .append(renderedParams)
                            .append(')')
                        if (isZeroArgAnyMethod) append(" const")
                        if (isOv) append(" override")
                        append(' ').append(bodyCode).append('\n')
                    }
                }
            }
            append(indent).append("};\n")
            if (declFrame.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID) {
                val instCpp = CppNamingPolicy.toSnakeCase(rawName)
                bindLocalName(rawName, instCpp)
                if (superBases.isNotEmpty()) {
                    includes.addSystem("memory")
                    markLocalPolyVar(rawName)
                    append("${indent}auto $instCpp = std::make_shared<$structName>();\n")
                } else {
                    append("$indent$structName $instCpp{};\n")
                }
            }
        }
    }

    private fun emitDestructuringDecl(
        destrFrame: PacketFrame,
        indent: String,
    ): String {
        val entries = FrameAstHelpers.childFramesOfType(stream, destrFrame, KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY_ID)
        val initFrame = FrameAstHelpers.extractInitializerAfterEquals(stream, destrFrame)
        val names = ArrayList<String>(entries.size)
        for (e in entries) {
            val raw = FrameAstHelpers.directIdentPacket(stream, e)?.unescapedName ?: "_"
            val cpp = if (raw == "_") freshTemp("unused") else CppNamingPolicy.toSnakeCase(raw)
            if (raw != "_") bindLocalName(raw, cpp)
            names.add(cpp)
        }
        val rhs = initFrame?.let { emitExpression(it) } ?: "{}"
        return "${indent}auto [${names.joinToString(", ")}] = $rhs;\n"
    }

    private fun emitReturnStmt(
        retFrame: PacketFrame,
        indent: String,
    ): String {
        val labelQual = FrameAstHelpers.firstChildFrame(stream, retFrame, KtNodeTypes.LABEL_QUALIFIER_ID)
        val exprChild =
            FrameAstHelpers
                .childFrames(stream, retFrame)
                .firstOrNull { it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID }
        if (labelQual == null && inlineReturnExitLabel != null) {
            usedInlineReturnExitLabel = true
            if (exprChild == null) {
                return "${indent}goto $inlineReturnExitLabel;\n"
            }
            val assignPart =
                if (inlineReturnTargetVar != null) {
                    emitValueAssignmentOrReturn(exprChild, indent, targetReturn = false, targetVar = inlineReturnTargetVar)
                } else {
                    ""
                }
            return "$assignPart${indent}goto $inlineReturnExitLabel;\n"
        }
        if (exprChild == null) {
            if (isOuterSuspendReturnContext) {
                return if (unitReturnExprOverride != null) {
                    "${indent}return $unitReturnExprOverride;\n"
                } else {
                    "${indent}return ${defaultSuspendFallthroughReturnExpr()};\n"
                }
            }
            return if (unitReturnExprOverride != null) {
                "${indent}return $unitReturnExprOverride;\n"
            } else {
                "${indent}return;\n"
            }
        }
        val unwrappedRet = unwrapTransparentFrame(exprChild)
        if (unwrappedRet.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
            FrameAstHelpers.directIdentPacket(stream, unwrappedRet)?.unescapedName == "Unit"
        ) {
            if (isOuterSuspendReturnContext) {
                return if (unitReturnExprOverride != null) {
                    "${indent}return $unitReturnExprOverride;\n"
                } else {
                    "${indent}return ${defaultSuspendFallthroughReturnExpr()};\n"
                }
            }
            if (unitReturnExprOverride != null) {
                return "${indent}return $unitReturnExprOverride;\n"
            }
            if (isVoidReturnContext) {
                return "${indent}return;\n"
            }
        }
        if (unwrappedRet.nodeTypeId == KtNodeTypes.NULL_ID) {
            if (isOuterSuspendReturnContext) {
                return "${indent}return ${wrapSuspendReturnValue("nullptr")};\n"
            }
            val effectiveRetTypeForNull =
                if (inExpressionIifeDepth > 0) {
                    currentExpectedTargetType
                } else {
                    enclosingFunctionReturnType
                }
            if (effectiveRetTypeForNull?.startsWith("std::optional<") == true) {
                includes.addSystem("optional")
                return "${indent}return std::nullopt;\n"
            }
            if (effectiveRetTypeForNull == "std::any") {
                return "${indent}return {};\n"
            }
        }
        if (unwrappedRet.nodeTypeId == KtNodeTypes.IF_ID ||
            unwrappedRet.nodeTypeId == KtNodeTypes.WHEN_ID ||
            unwrappedRet.nodeTypeId == KtNodeTypes.TRY_ID ||
            unwrappedRet.nodeTypeId == KtNodeTypes.BLOCK_ID ||
            unwrappedRet.frameIndex in bundle.suspendPlan.inlineExpansionsByCallFrame
        ) {
            return emitValueAssignmentOrReturn(exprChild, indent, targetReturn = true, targetVar = null)
        }
        if (unwrappedRet.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val binCh = FrameAstHelpers.childFrames(stream, unwrappedRet)
            if (binCh.size >= 3 && FrameAstHelpers.operationTokenId(stream, binCh[1]) == KtTokens.ELVIS_ID) {
                val rhsUnwrapped = unwrapTransparentFrame(binCh[2])
                if (rhsUnwrapped.nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES || doesBranchAlwaysTransferControl(rhsUnwrapped)) {
                    return emitValueAssignmentOrReturn(exprChild, indent, targetReturn = true, targetVar = null)
                }
            }
        }
        val effectiveRetTarget =
            if (inExpressionIifeDepth > 0) currentExpectedTargetType else enclosingFunctionReturnType
        val renderedRet = withExpectedTargetType(effectiveRetTarget) { emitExpression(exprChild) }
        if (renderedRet.trimStart().startsWith("throw ")) {
            return "$indent$renderedRet;\n"
        }
        if (PacketFlags.hasFlag(unwrappedRet.flags, PacketFlags.DIVERGING_EXPR) && isOuterSuspendReturnContext) {
            return "$indent$renderedRet;\n${indent}return ${defaultSuspendFallthroughReturnExpr()};\n"
        }
        val trimmedRet = renderedRet.trim()
        if (isOuterSuspendReturnContext &&
            isVoidReturnContext &&
            suspendBoxType == null &&
            unitReturnExprOverride == null &&
            trimmedRet != "COROUTINE_SUSPENDED" &&
            !trimmedRet.endsWith("::COROUTINE_SUSPENDED")
        ) {
            return "$indent$renderedRet;\n${indent}return nullptr;\n"
        }
        return "${indent}return ${wrapSuspendReturnValue(renderedRet)};\n"
    }

    private fun emitThrowStmt(
        throwFrame: PacketFrame,
        indent: String,
    ): String {
        val exprChild = FrameAstHelpers.childFrames(stream, throwFrame).firstOrNull()
        if (exprChild != null && isExceptionPtrExpr(exprChild)) {
            includes.addSystem("exception")
            return "${indent}std::rethrow_exception(${emitExpression(exprChild)});\n"
        }
        val rendered = exprChild?.let { emitThrowableExpr(it) } ?: "std::runtime_error(\"throw\")"
        return "${indent}throw $rendered;\n"
    }

    private fun emitThrowableExpr(exprFrame: PacketFrame): String {
        if (exprFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val callee =
                FrameAstHelpers
                    .firstChildFrame(stream, exprFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                    ?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
            val args = extractCallArgExpressions(exprFrame)
            when (callee) {
                "IllegalArgumentException" -> {
                    includes.addSystem("stdexcept")
                    return "std::invalid_argument(${args.firstOrNull() ?: "\"IllegalArgumentException\""})"
                }
                "IllegalStateException",
                "RuntimeException",
                "Exception",
                "ArithmeticException",
                "UnsupportedOperationException",
                -> {
                    includes.addSystem("stdexcept")
                    return "std::runtime_error(${args.firstOrNull() ?: "\"$callee\""})"
                }
                "IndexOutOfBoundsException",
                "NoSuchElementException",
                -> {
                    includes.addSystem("stdexcept")
                    return "std::out_of_range(${args.firstOrNull() ?: "\"$callee\""})"
                }
            }
        }
        return emitExpression(exprFrame)
    }

    private fun emitIfStatement(
        ifFrame: PacketFrame,
        indent: String,
        inValuePosition: Boolean,
        targetReturn: Boolean,
        targetVar: String?,
    ): String {
        val condWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.CONDITION_ID)
        val thenWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.THEN_ID)
        val elseWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.ELSE_ID)
        val condExpr = condWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val condCode = condExpr?.let { emitExpression(it) } ?: "false"

        val thenChild = thenWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val elseChild = elseWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }

        val thenSmartCasts = thenWrap?.let { bundle.cfPlan.smartCastsByScopeFrame[it.frameIndex] }.orEmpty()
        val elseSmartCasts = elseWrap?.let { bundle.cfPlan.smartCastsByScopeFrame[it.frameIndex] }.orEmpty()
        val (thenNullUnwraps, elseNullUnwraps) = computeNullCheckUnwrapBindings(condExpr, thenChild, elseChild)

        return buildString {
            append(indent).append("if (").append(condCode).append(") ")
            append(emitBranchBody(thenChild, indent, inValuePosition, targetReturn, targetVar, thenSmartCasts, thenNullUnwraps))
            if (elseChild != null) {
                if (elseChild.nodeTypeId == KtNodeTypes.IF_ID && elseSmartCasts.isEmpty() && elseNullUnwraps.isEmpty()) {
                    val nested =
                        emitIfStatement(
                            elseChild,
                            indent,
                            inValuePosition = inValuePosition,
                            targetReturn = targetReturn,
                            targetVar = targetVar,
                        ).trimStart()
                    append(" else ").append(nested)
                } else {
                    append(" else ")
                    append(emitBranchBody(elseChild, indent, inValuePosition, targetReturn, targetVar, elseSmartCasts, elseNullUnwraps))
                    append('\n')
                }
            } else {
                append('\n')
            }
        }
    }

    private fun computeNullCheckUnwrapBindings(
        condExpr: PacketFrame?,
        thenChild: PacketFrame?,
        elseChild: PacketFrame?,
    ): Pair<Map<String, String>, Map<String, String>> {
        if (bundle.isDocExample || condExpr == null) return emptyMap<String, String>() to emptyMap()
        val unwrappedCond = unwrapTransparentFrame(condExpr)
        if (unwrappedCond.nodeTypeId != KtNodeTypes.BINARY_EXPRESSION_ID) return emptyMap<String, String>() to emptyMap()
        val binCh = FrameAstHelpers.childFrames(stream, unwrappedCond)
        if (binCh.size < 3) return emptyMap<String, String>() to emptyMap()
        val opTxt = FrameAstHelpers.operationSymbolName(stream, binCh[1])
        if (opTxt != "!=" && opTxt != "!==" && opTxt != "==" && opTxt != "===") {
            return emptyMap<String, String>() to emptyMap()
        }
        val lhsU = unwrapTransparentFrame(binCh[0])
        val rhsU = unwrapTransparentFrame(binCh[2])
        val varFrame =
            when {
                rhsU.nodeTypeId == KtNodeTypes.NULL_ID && lhsU.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID -> lhsU
                lhsU.nodeTypeId == KtNodeTypes.NULL_ID && rhsU.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID -> rhsU
                else -> null
            } ?: return emptyMap<String, String>() to emptyMap()
        val varKt = FrameAstHelpers.directIdentPacket(stream, varFrame)?.unescapedName ?: return emptyMap<String, String>() to emptyMap()
        if (isLocalPolyVar(varKt) || isStdAnyKotlinVar(varKt)) return emptyMap<String, String>() to emptyMap()
        val targetBranch = if (opTxt == "!=" || opTxt == "!==") thenChild else elseChild
        if (targetBranch == null || frameReassignsVariable(targetBranch, varKt)) {
            return emptyMap<String, String>() to emptyMap()
        }
        val existingCpp = emitReferenceExpr(varFrame)
        includes.needsNullUnwrapDef = true
        includes.addSystem("any")
        includes.addSystem("optional")
        includes.addSystem("type_traits")
        includes.addSystem("utility")
        val map = mapOf(varKt to "kxs_unwrap_opt($existingCpp)")
        return if (opTxt == "!=" || opTxt == "!==") map to emptyMap() else emptyMap<String, String>() to map
    }

    private fun frameReassignsVariable(
        root: PacketFrame,
        varKt: String,
    ): Boolean {
        if (root.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val ch = FrameAstHelpers.childFrames(stream, root)
            if (ch.size >= 3 && FrameAstHelpers.operationSymbolName(stream, ch[1]) in ASSIGN_OPS &&
                frameContainsIdentifier(ch[0], varKt)
            ) {
                return true
            }
        }
        if ((root.nodeTypeId == KtNodeTypes.PREFIX_EXPRESSION_ID || root.nodeTypeId == KtNodeTypes.POSTFIX_EXPRESSION_ID) &&
            frameContainsIdentifier(root, varKt)
        ) {
            val opRef = FrameAstHelpers.firstChildFrame(stream, root, KtNodeTypes.OPERATION_REFERENCE_ID)
            val opSym = opRef?.let { FrameAstHelpers.operationSymbolName(stream, it) }
            if (opSym == "++" || opSym == "--") return true
        }
        for (child in FrameAstHelpers.childFrames(stream, root)) {
            if (frameReassignsVariable(child, varKt)) return true
        }
        return false
    }

    private fun emitBranchBody(
        branchChild: PacketFrame?,
        indent: String,
        inValuePosition: Boolean,
        targetReturn: Boolean,
        targetVar: String?,
        smartCasts: List<SmartCastBinding>,
        nullUnwraps: Map<String, String> = emptyMap(),
    ): String {
        if (branchChild == null) {
            return "{}"
        }
        if (nullUnwraps.isNotEmpty()) {
            return withLexicalScope {
                for ((k, v) in nullUnwraps) {
                    bindLocalName(k, v)
                }
                emitBranchBody(branchChild, indent, inValuePosition, targetReturn, targetVar, smartCasts, emptyMap())
            }
        }
        if (branchChild.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            return emitBlock(
                blockFrame = branchChild,
                indent = indent,
                isValueBlock = inValuePosition && targetReturn,
                resultTargetVar = if (inValuePosition) targetVar else null,
                smartCasts = smartCasts,
            )
        }
        return withLexicalScope {
            buildString {
                append("{\n")
                val inner = "$indent    "
                emitSmartCastBindings(this, smartCasts, inner)
                emitHoistedPrefixesForStmt(this, branchChild.frameIndex, inner)
                if (inValuePosition) {
                    append(emitValueAssignmentOrReturn(branchChild, inner, targetReturn = targetReturn, targetVar = targetVar))
                } else {
                    append(emitStatement(branchChild, inner, inValuePosition = false, resultTargetVar = null))
                }
                append(indent).append('}')
            }
        }
    }

    private fun emitWhenStatement(
        whenFrame: PacketFrame,
        indent: String,
        inValuePosition: Boolean,
        targetReturn: Boolean,
        targetVar: String?,
    ): String {
        var subjectFrame: PacketFrame? = null
        var subjectPropDecl: PacketFrame? = null
        for (child in FrameAstHelpers.childFrames(stream, whenFrame)) {
            if (child.nodeTypeId == KtNodeTypes.WHEN_ENTRY_ID) break
            if (child.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                subjectPropDecl = child
            } else if (child.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID) {
                subjectFrame = child
            }
        }

        val entries = FrameAstHelpers.childFramesOfType(stream, whenFrame, KtNodeTypes.WHEN_ENTRY_ID)
        val unwrappedSubject = subjectFrame?.let { unwrapTransparentFrame(it) }
        val subjectIsBoolExpr =
            unwrappedSubject != null &&
                (
                    unwrappedSubject.nodeTypeId == KtNodeTypes.BOOLEAN_CONSTANT_ID ||
                        unwrappedSubject.nodeTypeId == KtNodeTypes.IS_EXPRESSION_ID ||
                        (
                            unwrappedSubject.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID &&
                                FrameAstHelpers.childFrames(stream, unwrappedSubject).getOrNull(1)?.let {
                                    FrameAstHelpers.operationSymbolName(stream, it)
                                } in setOf("==", "!=", "===", "!==", "<", "<=", ">", ">=", "&&", "||", "in", "!in")
                        ) ||
                        (
                            unwrappedSubject.nodeTypeId == KtNodeTypes.PREFIX_EXPRESSION_ID &&
                                FrameAstHelpers.firstChildFrame(stream, unwrappedSubject, KtNodeTypes.OPERATION_REFERENCE_ID)?.let {
                                    FrameAstHelpers.operationSymbolName(stream, it)
                                } == "!"
                        )
                )
        val subjectPropInitFrame = subjectPropDecl?.let { FrameAstHelpers.extractInitializerAfterEquals(stream, it) }
        val subjectPropTypeSimple =
            subjectPropDecl
                ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_REFERENCE_ID) }
                ?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, it) }
        val subjectCannotSwitch =
            when {
                subjectFrame != null ->
                    isStdAnyExpr(subjectFrame) || inferExpressionSimpleTypeName(subjectFrame) in NON_SWITCH_SUBJECT_TYPES
                subjectPropDecl != null ->
                    subjectPropTypeSimple in NON_SWITCH_SUBJECT_TYPES ||
                        (
                            subjectPropInitFrame != null &&
                                (
                                    isStdAnyExpr(subjectPropInitFrame) ||
                                        inferExpressionSimpleTypeName(subjectPropInitFrame) in NON_SWITCH_SUBJECT_TYPES
                                )
                        )
                else -> false
            }
        val useCppSwitch =
            !subjectIsBoolExpr &&
                !subjectCannotSwitch &&
                (subjectFrame != null || subjectPropDecl != null) &&
                canEmitCppSwitch(entries)
        return withLexicalScope {
            buildString {
                var needOuterCloseBrace = false
                var workIndent = indent
                val subjectExprCode: String? =
                    when {
                        subjectPropDecl != null -> {
                            append(indent).append("{\n")
                            needOuterCloseBrace = true
                            workIndent = "$indent    "
                            append(emitLocalPropertyDecl(subjectPropDecl, workIndent))
                            val pIdent = FrameAstHelpers.directIdentPacket(stream, subjectPropDecl)?.unescapedName
                            pIdent?.let { resolveBoundName(it) ?: CppNamingPolicy.toSnakeCase(it) }
                        }
                        subjectFrame != null -> {
                            val singleEvalTemp =
                                if (useCppSwitch) null else bundle.cfPlan.singleEvalTempNamesByFrame[whenFrame.frameIndex]
                            if (singleEvalTemp != null) {
                                append(indent).append("{\n")
                                needOuterCloseBrace = true
                                workIndent = "$indent    "
                                append(workIndent)
                                    .append("const auto ")
                                    .append(singleEvalTemp)
                                    .append(" = ")
                                    .append(emitExpression(subjectFrame))
                                    .append(";\n")
                                singleEvalTemp
                            } else {
                                emitExpression(subjectFrame)
                            }
                        }
                        else -> null
                    }

                var hasElseBranch = false
                if (subjectExprCode != null && useCppSwitch) {
                    append(workIndent).append("switch (").append(subjectExprCode).append(") {\n")
                    val caseIndent = "$workIndent    "
                    val bodyIndent = "$caseIndent    "
                    for (entry in entries) {
                        val isElse = FrameAstHelpers.hasDirectKeyword(stream, entry, KtTokens.ELSE_KEYWORD_ID)
                        if (isElse) hasElseBranch = true
                        val entryChildren = FrameAstHelpers.childFrames(stream, entry)
                        val armBody = entryChildren.lastOrNull()
                        val condFrames = entryChildren.dropLast(1)
                        if (isElse) {
                            append(caseIndent).append("default:\n")
                        } else {
                            for (cf in condFrames) {
                                val inner = FrameAstHelpers.childFrames(stream, cf).firstOrNull()
                                val caseExpr = inner?.let { emitExpression(it) } ?: "0"
                                append(caseIndent).append("case ").append(caseExpr).append(":\n")
                            }
                        }
                        if (armBody == null) {
                            append(bodyIndent).append("break;\n")
                        } else if (armBody.nodeTypeId == KtNodeTypes.BLOCK_ID) {
                            append(bodyIndent)
                                .append(
                                    emitBlock(
                                        blockFrame = armBody,
                                        indent = bodyIndent,
                                        isValueBlock = inValuePosition && targetReturn,
                                        resultTargetVar = if (inValuePosition) targetVar else null,
                                    ),
                                ).append('\n')
                            if (!targetReturn && !doesBranchAlwaysTransferControl(armBody)) {
                                append(bodyIndent).append("break;\n")
                            }
                        } else if (inValuePosition && targetReturn) {
                            emitHoistedPrefixesForStmt(this, armBody.frameIndex, bodyIndent)
                            append(emitValueAssignmentOrReturn(armBody, bodyIndent, targetReturn = true, targetVar = null))
                        } else {
                            emitHoistedPrefixesForStmt(this, armBody.frameIndex, bodyIndent)
                            if (inValuePosition) {
                                append(emitValueAssignmentOrReturn(armBody, bodyIndent, targetReturn = false, targetVar = targetVar))
                            } else {
                                append(emitStatement(armBody, bodyIndent, inValuePosition = false, resultTargetVar = null))
                            }
                            if (!doesBranchAlwaysTransferControl(armBody)) {
                                append(bodyIndent).append("break;\n")
                            }
                        }
                    }
                    append(workIndent).append("}\n")
                } else {
                    for ((idx, entry) in entries.withIndex()) {
                        val isElse = FrameAstHelpers.hasDirectKeyword(stream, entry, KtTokens.ELSE_KEYWORD_ID)
                        if (isElse) hasElseBranch = true
                        val entryChildren = FrameAstHelpers.childFrames(stream, entry)
                        val armBody = entryChildren.lastOrNull()
                        val condFrames = entryChildren.dropLast(1)
                        val entrySmartCasts = bundle.cfPlan.smartCastsByScopeFrame[entry.frameIndex].orEmpty()

                        val effectiveSubjectFrame = subjectFrame ?: subjectPropInitFrame
                        if (idx == 0 && !isElse) {
                            val condCode = emitWhenEntryCondition(condFrames, subjectExprCode, effectiveSubjectFrame)
                            append(workIndent).append("if (").append(condCode).append(") ")
                        } else if (!isElse) {
                            val condCode = emitWhenEntryCondition(condFrames, subjectExprCode, effectiveSubjectFrame)
                            append(" else if (").append(condCode).append(") ")
                        } else if (idx == 0) {
                            // Sole `else ->` entry
                            append(workIndent)
                        } else {
                            append(" else ")
                        }
                        append(emitBranchBody(armBody, workIndent, inValuePosition, targetReturn, targetVar, entrySmartCasts))
                    }
                    if (entries.isNotEmpty()) {
                        append('\n')
                    }
                }
                if (inValuePosition && targetReturn && !hasElseBranch && entries.isNotEmpty()) {
                    includes.addSystem("utility")
                    append(workIndent).append("std::unreachable();\n")
                }
                if (needOuterCloseBrace) {
                    append(indent).append("}\n")
                }
            }
        }
    }

    private fun emitWhenEntryCondition(
        condFrames: List<PacketFrame>,
        subjectCode: String?,
        subjectFrame: PacketFrame? = null,
    ): String {
        if (condFrames.isEmpty()) return "true"
        val parts = ArrayList<String>(condFrames.size)
        for (cf in condFrames) {
            when (cf.nodeTypeId) {
                KtNodeTypes.WHEN_CONDITION_EXPRESSION_ID -> {
                    val inner = FrameAstHelpers.childFrames(stream, cf).firstOrNull()
                    val expr = inner?.let { emitExpression(it) } ?: "false"
                    val subjSimple = subjectFrame?.let { inferExpressionSimpleTypeName(it) }
                    val isDirectScalarSubject =
                        subjSimple in DIRECT_EQ_SCALAR_TYPES ||
                            (
                                (expr == "true" || expr == "false") &&
                                    subjSimple != "Any" &&
                                    (subjectFrame == null || !isStdAnyExpr(subjectFrame))
                            )
                    parts.add(
                        if (subjectCode != null) {
                            if (!bundle.isDocExample && !isDirectScalarSubject) {
                                includes.needsAnyEqDef = true
                                "kxs_any_eq($subjectCode, $expr)"
                            } else {
                                "($subjectCode == $expr)"
                            }
                        } else {
                            expr
                        },
                    )
                }
                KtNodeTypes.WHEN_CONDITION_IS_PATTERN_ID -> {
                    val negated = FrameAstHelpers.hasDirectKeyword(stream, cf, KtTokens.NOT_IS_ID)
                    val tr = FrameAstHelpers.firstChildFrame(stream, cf, KtNodeTypes.TYPE_REFERENCE_ID)
                    val cppType =
                        tr
                            ?.let { translateType(it, CppTypePosition.TYPE_ARGUMENT) }
                            ?.removeSuffix("&")
                            ?.removeSuffix("*")
                            ?: "void"
                    includes.addSystem("memory")
                    val check =
                        if (bundle.isDocExample) {
                            val subj = subjectCode ?: "this"
                            "dynamic_cast<const $cppType*>(std::addressof($subj)) != nullptr"
                        } else {
                            val subj = subjectCode ?: (receiverSubstitution ?: "this")
                            emitRuntimeIsCheck(subj, cppType)
                        }
                    parts.add(if (negated) "!($check)" else "($check)")
                }
                KtNodeTypes.WHEN_CONDITION_IN_RANGE_ID -> {
                    val negated = FrameAstHelpers.hasDirectKeyword(stream, cf, KtTokens.NOT_IN_ID)
                    val rangeExpr =
                        FrameAstHelpers
                            .childFrames(stream, cf)
                            .lastOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
                    val subj = subjectCode ?: "0"
                    val check = emitRangeMembershipCheck(subj, rangeExpr)
                    parts.add(if (negated) "!($check)" else "($check)")
                }
                KtNodeTypes.WHEN_ENTRY_GUARD_ID -> {
                    val guardExpr = FrameAstHelpers.childFrames(stream, cf).firstOrNull()
                    if (guardExpr != null && parts.isNotEmpty()) {
                        val lastIdx = parts.lastIndex
                        parts[lastIdx] = "(${parts[lastIdx]} && (${emitExpression(guardExpr)}))"
                    }
                }
                else -> parts.add(emitExpression(cf))
            }
        }
        return parts.joinToString(" || ")
    }

    private fun emitRangeMembershipCheck(
        lhsCode: String,
        rangeFrame: PacketFrame?,
    ): String {
        if (rangeFrame != null && rangeFrame.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val ch = FrameAstHelpers.childFrames(stream, rangeFrame)
            if (ch.size >= 3) {
                val op = FrameAstHelpers.operationSymbolName(stream, ch[1])
                val lo = emitExpression(ch[0])
                val hi = emitExpression(ch[2])
                if (op == "..") return "$lhsCode >= $lo && $lhsCode <= $hi"
                if (op == "..<" || op == "until") return "$lhsCode >= $lo && $lhsCode < $hi"
            }
        }
        val container = rangeFrame?.let { emitExpression(it) } ?: "{}"
        includes.addSystem("algorithm")
        return "std::find($container.begin(), $container.end(), $lhsCode) != $container.end()"
    }

    private fun emitTryStatement(
        tryFrame: PacketFrame,
        indent: String,
        inValuePosition: Boolean,
        targetReturn: Boolean,
        targetVar: String?,
    ): String {
        val tryBlock = FrameAstHelpers.firstChildFrame(stream, tryFrame, KtNodeTypes.BLOCK_ID)
        val catches = FrameAstHelpers.childFramesOfType(stream, tryFrame, KtNodeTypes.CATCH_ID)
        val finallyNode = FrameAstHelpers.firstChildFrame(stream, tryFrame, KtNodeTypes.FINALLY_ID)
        val finallyBlock = finallyNode?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.BLOCK_ID) }

        return buildString {
            var workIndent = indent
            var guardClose = false
            if (finallyBlock != null) {
                append(indent).append("{\n")
                workIndent = "$indent    "
                guardClose = true
                val guardStruct = freshTemp("KxsFinallyGuard")
                val guardVar = freshTemp("kxs_finally")
                append(workIndent).append("struct ").append(guardStruct).append(" {\n")
                append(workIndent).append("    std::function<void()> action;\n")
                append(workIndent).append("    ~").append(guardStruct).append("() noexcept(false) { action(); }\n")
                append(workIndent).append("} ").append(guardVar).append("{[&]() ")
                append(emitBlock(finallyBlock, workIndent, isValueBlock = false, resultTargetVar = null))
                append("};\n")
                includes.addSystem("functional")
            }

            if (catches.isEmpty()) {
                if (tryBlock != null) {
                    append(workIndent)
                        .append(
                            emitBlock(
                                tryBlock,
                                workIndent,
                                isValueBlock = inValuePosition && targetReturn,
                                resultTargetVar = if (inValuePosition) targetVar else null,
                            ),
                        ).append('\n')
                }
            } else {
                append(workIndent).append("try ")
                if (tryBlock != null) {
                    append(
                        emitBlock(
                            tryBlock,
                            workIndent,
                            isValueBlock = inValuePosition && targetReturn,
                            resultTargetVar = if (inValuePosition) targetVar else null,
                        ),
                    )
                } else {
                    append("{}")
                }
                for (catchFrame in catches) {
                    val pList = FrameAstHelpers.firstChildFrame(stream, catchFrame, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
                    val vp = pList?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.VALUE_PARAMETER_ID) }
                    val catchVarKt = vp?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName } ?: "ex"
                    val catchVarCpp = CppNamingPolicy.toSnakeCase(catchVarKt)
                    val vpType = vp?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_REFERENCE_ID) }
                    val catchCppType =
                        vpType?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.VALUE) } ?: "std::exception_ptr"
                    val catchBlock = FrameAstHelpers.firstChildFrame(stream, catchFrame, KtNodeTypes.BLOCK_ID)
                    includes.addSystem("exception")
                    if (catchCppType == "std::exception_ptr") {
                        append(" catch (...) ")
                        withLexicalScope {
                            bindLocalName(catchVarKt, catchVarCpp)
                            markExceptionPtrVar(catchVarKt)
                            markLocalType(catchVarKt, "Throwable")
                            val bindLine = "const std::exception_ptr $catchVarCpp = std::current_exception();"
                            if (catchBlock != null) {
                                append(
                                    emitBlock(
                                        catchBlock,
                                        workIndent,
                                        isValueBlock = inValuePosition && targetReturn,
                                        resultTargetVar = if (inValuePosition) targetVar else null,
                                        extraEntryLines = listOf(bindLine),
                                    ),
                                )
                            } else {
                                append("{\n$workIndent    $bindLine\n$workIndent}")
                            }
                        }
                    } else {
                        val cleanCatchType =
                            catchCppType
                                .removePrefix("std::shared_ptr<")
                                .removeSuffix(">")
                                .removeSuffix("&")
                                .removeSuffix("*")
                        append(" catch (const $cleanCatchType& $catchVarCpp) ")
                        withLexicalScope {
                            bindLocalName(catchVarKt, catchVarCpp)
                            if (catchBlock != null) {
                                append(
                                    emitBlock(
                                        catchBlock,
                                        workIndent,
                                        isValueBlock = inValuePosition && targetReturn,
                                        resultTargetVar = if (inValuePosition) targetVar else null,
                                    ),
                                )
                            } else {
                                append("{}")
                            }
                        }
                    }
                }
                append('\n')
            }

            if (guardClose) {
                append(indent).append("}\n")
            }
        }
    }

    private fun emitWhileStmt(
        whileFrame: PacketFrame,
        indent: String,
    ): String {
        val condWrap = FrameAstHelpers.firstChildFrame(stream, whileFrame, KtNodeTypes.CONDITION_ID)
        val condExpr = condWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val bodyWrap = FrameAstHelpers.firstChildFrame(stream, whileFrame, KtNodeTypes.BODY_ID)
        val bodyChild = bodyWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val condCode = condExpr?.let { emitExpression(it) } ?: "true"
        val bodyCode =
            emitBranchBody(
                branchChild = bodyChild,
                indent = indent,
                inValuePosition = false,
                targetReturn = false,
                targetVar = null,
                smartCasts = emptyList(),
            )
        return "${indent}while ($condCode) $bodyCode\n"
    }

    private fun emitDoWhileStmt(
        doWhileFrame: PacketFrame,
        indent: String,
    ): String {
        val bodyWrap = FrameAstHelpers.firstChildFrame(stream, doWhileFrame, KtNodeTypes.BODY_ID)
        val bodyChild = bodyWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val condWrap = FrameAstHelpers.firstChildFrame(stream, doWhileFrame, KtNodeTypes.CONDITION_ID)
        val condExpr = condWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val condCode = condExpr?.let { emitExpression(it) } ?: "false"
        val bodyCode =
            emitBranchBody(
                branchChild = bodyChild,
                indent = indent,
                inValuePosition = false,
                targetReturn = false,
                targetVar = null,
                smartCasts = emptyList(),
            )
        return "${indent}do $bodyCode while ($condCode);\n"
    }

    private fun emitForStmt(
        forFrame: PacketFrame,
        indent: String,
    ): String {
        val vp = FrameAstHelpers.firstChildFrame(stream, forFrame, KtNodeTypes.VALUE_PARAMETER_ID)
        val loopVarKt = vp?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName } ?: "item"
        val loopVarCpp = CppNamingPolicy.toSnakeCase(loopVarKt)
        val rangeWrap = FrameAstHelpers.firstChildFrame(stream, forFrame, KtNodeTypes.LOOP_RANGE_ID)
        val rangeExpr = rangeWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val bodyWrap = FrameAstHelpers.firstChildFrame(stream, forFrame, KtNodeTypes.BODY_ID)
        val bodyChild = bodyWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }

        return withLexicalScope {
            if (rangeExpr != null && rangeExpr.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
                val ch = FrameAstHelpers.childFrames(stream, rangeExpr)
                if (ch.size >= 3) {
                    val op = FrameAstHelpers.operationSymbolName(stream, ch[1])
                    if (op == ".." || op == "..<" || op == "until" || op == "downTo") {
                        val lo = emitExpression(ch[0])
                        val hi = emitExpression(ch[2])
                        bindLocalName(loopVarKt, loopVarCpp)
                        val bodyCode =
                            emitBranchBody(
                                branchChild = bodyChild,
                                indent = indent,
                                inValuePosition = false,
                                targetReturn = false,
                                targetVar = null,
                                smartCasts = emptyList(),
                            )
                        return@withLexicalScope when (op) {
                            ".." -> "${indent}for (auto $loopVarCpp = $lo; $loopVarCpp <= $hi; ++$loopVarCpp) $bodyCode\n"
                            "..<", "until" -> "${indent}for (auto $loopVarCpp = $lo; $loopVarCpp < $hi; ++$loopVarCpp) $bodyCode\n"
                            else -> "${indent}for (auto $loopVarCpp = $lo; $loopVarCpp >= $hi; --$loopVarCpp) $bodyCode\n"
                        }
                    }
                }
            }
            val iterCode = rangeExpr?.let { emitExpression(it) } ?: "{}"
            bindLocalName(loopVarKt, loopVarCpp)
            val bodyCode =
                emitBranchBody(
                    branchChild = bodyChild,
                    indent = indent,
                    inValuePosition = false,
                    targetReturn = false,
                    targetVar = null,
                    smartCasts = emptyList(),
                )
            val rangeType = rangeExpr?.let { inferExpressionSimpleTypeName(it) }
            val isChannelRange =
                rangeType in
                    setOf(
                        "ReceiveChannel",
                        "Channel",
                        "BufferedChannel",
                        "ConflatedBufferedChannel",
                        "BroadcastChannel",
                    ) ||
                    (
                        rangeType != null &&
                            lookupAccessibleClassByName(rangeType)?.let {
                                classDerivesFrom(it, "ReceiveChannel")
                            } == true
                    )
            val isDirectValueContainer =
                rangeExpr != null &&
                    !isPointerOrSharedPtrReceiver(rangeExpr, iterCode) &&
                    rangeType in DIRECT_ITERABLE_VALUE_TYPES
            if (!bundle.isDocExample && isChannelRange && rangeExpr != null) {
                val op = if (isPointerOrSharedPtrReceiver(rangeExpr, "")) "->" else "."
                val itVar = freshTemp("kxs_ch_it")
                "${indent}for (auto $itVar = $iterCode${op}iterator(); $itVar->has_next(); ) {\n" +
                    "$indent    auto&& $loopVarCpp = $itVar->next();\n" +
                    "$indent    $bodyCode\n" +
                    "$indent}\n"
            } else if (!bundle.isDocExample && !isDirectValueContainer) {
                includes.needsIterableHelpersDef = true
                "${indent}for (auto&& $loopVarCpp : kxs_iterable_ref($iterCode)) $bodyCode\n"
            } else {
                "${indent}for (auto&& $loopVarCpp : $iterCode) $bodyCode\n"
            }
        }
    }

    private fun emitInlineExpansionAsStatement(
        inlineExp: InlineCallExpansion,
        indent: String,
        targetVar: String?,
        targetReturn: Boolean = false,
    ): String {
        val lambdaFrame = stream.frames[inlineExp.lambdaFrameIndex]
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
        val lambdaBody =
            if (fnLit != null) {
                FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
            } else {
                lambdaFrame
            }
        val isSafeCall =
            stream.frames[inlineExp.callFrameIndex].nodeTypeId == KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID &&
                inlineExp.receiverExprFrameIndex != null
        val rawRecvFrame = inlineExp.receiverExprFrameIndex?.let { stream.frames[it] }

        return withLexicalScope {
            buildString {
                append(indent).append("{\n")
                val inner = "$indent    "

                fun emitScopeBody(
                    bodyIndent: String,
                    effTargetVar: String?,
                    effTargetReturn: Boolean,
                    recvOverrideCode: String?,
                ) {
                    fun emitReceiverScope(
                        recvFrame: PacketFrame?,
                        recvExprOverride: String?,
                    ) {
                        if (recvFrame != null || recvExprOverride != null) {
                            val recvCode =
                                recvExprOverride ?: withExpectedTargetType(null) { emitExpression(recvFrame!!) }
                            val recvTemp = freshTemp("kxs_recv")
                            append(bodyIndent)
                                .append("auto&& ")
                                .append(recvTemp)
                                .append(" = ")
                                .append(recvCode)
                                .append(";\n")
                            val nestedEmitter = buildInlineScopeEmitter(recvFrame, recvCode, recvTemp)
                            nestedEmitter.appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                bodyIndent,
                                effTargetVar,
                                effTargetReturn,
                            )
                            synthCounter = nestedEmitter.synthCounter
                        } else {
                            appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                bodyIndent,
                                effTargetVar,
                                effTargetReturn,
                            )
                        }
                    }

                    when (inlineExp.kind) {
                        BuiltinInlineKind.LET -> {
                            val recvCode =
                                recvOverrideCode
                                    ?: rawRecvFrame?.let { emitExpression(it) }
                                    ?: (receiverSubstitution ?: "(*this)")
                            val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                            val pCpp = CppNamingPolicy.toSnakeCase(pName)
                            append(bodyIndent)
                                .append("auto&& ")
                                .append(pCpp)
                                .append(" = ")
                                .append(recvCode)
                                .append(";\n")
                            bindLocalName(pName, pCpp)
                            appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                bodyIndent,
                                effTargetVar,
                                effTargetReturn,
                            )
                        }
                        BuiltinInlineKind.ALSO -> {
                            val recvCode =
                                recvOverrideCode
                                    ?: rawRecvFrame?.let { emitExpression(it) }
                                    ?: (receiverSubstitution ?: "(*this)")
                            val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                            val pCpp = CppNamingPolicy.toSnakeCase(pName)
                            append(bodyIndent)
                                .append("auto&& ")
                                .append(pCpp)
                                .append(" = ")
                                .append(recvCode)
                                .append(";\n")
                            bindLocalName(pName, pCpp)
                            appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                bodyIndent,
                                targetVar = null,
                                targetReturn = false,
                            )
                            if (effTargetVar != null) {
                                append(bodyIndent)
                                    .append(effTargetVar)
                                    .append(" = ")
                                    .append(pCpp)
                                    .append(";\n")
                            } else if (effTargetReturn) {
                                append(bodyIndent).append("return ").append(wrapSuspendReturnValue(pCpp)).append(";\n")
                            }
                        }
                        BuiltinInlineKind.RUN -> emitReceiverScope(rawRecvFrame, recvOverrideCode)
                        BuiltinInlineKind.WITH -> {
                            val withRecv = inlineExp.argExprFrameIndices.firstOrNull()?.let { stream.frames[it] }
                            emitReceiverScope(withRecv, recvExprOverride = null)
                        }
                        BuiltinInlineKind.APPLY -> {
                            val recvCode =
                                recvOverrideCode
                                    ?: rawRecvFrame?.let { withExpectedTargetType(null) { emitExpression(it) } }
                                    ?: (receiverSubstitution ?: "(*this)")
                            val recvTemp = freshTemp("kxs_apply")
                            append(bodyIndent)
                                .append("auto&& ")
                                .append(recvTemp)
                                .append(" = ")
                                .append(recvCode)
                                .append(";\n")
                            val nestedEmitter = buildInlineScopeEmitter(rawRecvFrame, recvCode, recvTemp)
                            nestedEmitter.appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                bodyIndent,
                                targetVar = null,
                                targetReturn = false,
                            )
                            synthCounter = nestedEmitter.synthCounter
                            if (effTargetVar != null) {
                                append(bodyIndent)
                                    .append(effTargetVar)
                                    .append(" = ")
                                    .append(recvTemp)
                                    .append(";\n")
                            } else if (effTargetReturn) {
                                append(bodyIndent)
                                    .append("return ")
                                    .append(wrapSuspendReturnValue(recvTemp))
                                    .append(";\n")
                            }
                        }
                        BuiltinInlineKind.USE -> {
                            val recvCode =
                                recvOverrideCode
                                    ?: rawRecvFrame?.let { emitExpression(it) }
                                    ?: "this"
                            val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                            val pCpp = CppNamingPolicy.toSnakeCase(pName)
                            val guardStruct = freshTemp("KxsCloseGuard")
                            val guardVar = freshTemp("kxs_close")
                            append(bodyIndent)
                                .append("auto&& ")
                                .append(pCpp)
                                .append(" = ")
                                .append(recvCode)
                                .append(";\n")
                            append(bodyIndent)
                                .append("struct ")
                                .append(guardStruct)
                                .append(" { decltype((")
                                .append(pCpp)
                                .append(")) ref; ~")
                                .append(guardStruct)
                                .append("() { ref.close(); } } ")
                                .append(guardVar)
                                .append('{')
                                .append(pCpp)
                                .append("};\n")
                            bindLocalName(pName, pCpp)
                            appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                bodyIndent,
                                effTargetVar,
                                effTargetReturn,
                            )
                        }
                        BuiltinInlineKind.WITH_LOCK -> {
                            includes.addSystem("mutex")
                            val recvCode =
                                recvOverrideCode
                                    ?: rawRecvFrame?.let { emitExpression(it) }
                                    ?: "mutex_"
                            append(bodyIndent).append("std::lock_guard kxs_lock(").append(recvCode).append(");\n")
                            appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                bodyIndent,
                                effTargetVar,
                                effTargetReturn,
                            )
                        }
                        BuiltinInlineKind.SYNCHRONIZED -> {
                            includes.addSystem("mutex")
                            includes.addSystem("type_traits")
                            includes.needsSynchronizedObjectDef = true
                            val lockExpr =
                                inlineExp.argExprFrameIndices.firstOrNull()?.let { emitExpression(stream.frames[it]) }
                                    ?: (receiverSubstitution ?: "(*this)")
                            val mtxTemp = freshTemp("kxs_sync_mtx")
                            val lockTemp = freshTemp("kxs_lock")
                            val syncPtrBranch =
                                "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_so)>> || " +
                                    "requires { kxs_so.operator->(); }) "
                            append(bodyIndent)
                                .append("auto& ")
                                .append(mtxTemp)
                                .append(" = ([&](auto&& kxs_so) -> std::recursive_mutex& { ")
                                .append(syncPtrBranch)
                                .append("return kxs_so->kxs_sync_mutex_; else return kxs_so.kxs_sync_mutex_; }(")
                                .append(lockExpr)
                                .append("));\n")
                            append("${bodyIndent}std::lock_guard $lockTemp($mtxTemp);\n")
                            appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                bodyIndent,
                                effTargetVar,
                                effTargetReturn,
                            )
                        }
                        BuiltinInlineKind.REPEAT -> {
                            val countIdx = inlineExp.argExprFrameIndices.firstOrNull()
                            val countCode = countIdx?.let { emitExpression(stream.frames[it]) } ?: "0"
                            val idxParam = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                            val idxCpp = CppNamingPolicy.toSnakeCase(idxParam)
                            val boundTemp = freshTemp("kxs_times")
                            append(bodyIndent)
                                .append("const auto ")
                                .append(boundTemp)
                                .append(" = ")
                                .append(countCode)
                                .append(";\n")
                            append(bodyIndent)
                                .append("for (std::int32_t ")
                                .append(idxCpp)
                                .append(" = 0; ")
                                .append(idxCpp)
                                .append(" < ")
                                .append(boundTemp)
                                .append("; ++")
                                .append(idxCpp)
                                .append(") {\n")
                            includes.addSystem("cstdint")
                            bindLocalName(idxParam, idxCpp)
                            appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                "$bodyIndent    ",
                                targetVar = null,
                                targetReturn = false,
                            )
                            append(bodyIndent).append("}\n")
                        }
                        BuiltinInlineKind.FOR_EACH -> {
                            val recvCode =
                                recvOverrideCode
                                    ?: rawRecvFrame?.let { emitExpression(it) }
                                    ?: (receiverSubstitution ?: "(*this)")
                            val recvType =
                                if (rawRecvFrame != null) {
                                    inferExpressionSimpleTypeName(rawRecvFrame)
                                } else {
                                    extensionReceiverSimpleName ?: enclosingClass?.kotlinName
                                }
                            val recvIsPtr =
                                if (rawRecvFrame != null) {
                                    isPointerOrSharedPtrReceiver(rawRecvFrame, recvCode)
                                } else {
                                    receiverSubstitution?.let { extensionReceiverIsPointer } ?: false
                                }
                            val isDirectValueContainer = !recvIsPtr && recvType in DIRECT_ITERABLE_VALUE_TYPES
                            val iterableExpr =
                                if (!bundle.isDocExample && !isDirectValueContainer) {
                                    includes.needsIterableHelpersDef = true
                                    "kxs_iterable_ref($recvCode)"
                                } else {
                                    recvCode
                                }
                            val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                            val pCpp = CppNamingPolicy.toSnakeCase(pName)
                            append(bodyIndent)
                                .append("for (auto&& ")
                                .append(pCpp)
                                .append(" : ")
                                .append(iterableExpr)
                                .append(") {\n")
                            bindLocalName(pName, pCpp)
                            appendInlineLambdaBodyStatements(
                                this,
                                lambdaBody,
                                "$bodyIndent    ",
                                targetVar = null,
                                targetReturn = false,
                            )
                            append(bodyIndent).append("}\n")
                        }
                        BuiltinInlineKind.SAME_FILE_INLINE_FUN -> {
                            val targetFn =
                                inlineExp.targetInlineFunFrameIndex?.let { bundle.symbols.functionsByFrame[it] }
                            val targetBody = targetFn?.bodyFrameIndex?.let { stream.frames[it] }
                            if (targetFn != null &&
                                targetBody != null &&
                                targetFn.frameIndex !in activeInlineFunFrames
                            ) {
                                emitSameFileInlineFunctionBody(
                                    out = this,
                                    inlineExp = inlineExp,
                                    targetFn = targetFn,
                                    targetBody = targetBody,
                                    callerLambdaBody = lambdaBody,
                                    inner = bodyIndent,
                                    targetVar = effTargetVar,
                                    targetReturn = effTargetReturn,
                                    recvOverrideCode = recvOverrideCode,
                                )
                            } else {
                                appendInlineLambdaBodyStatements(
                                    this,
                                    lambdaBody,
                                    bodyIndent,
                                    effTargetVar,
                                    effTargetReturn,
                                )
                            }
                        }
                    }
                }

                if (!isSafeCall) {
                    emitScopeBody(inner, targetVar, targetReturn, recvOverrideCode = null)
                } else {
                    includes.needsNullUnwrapDef = true
                    val rawRecvCode = withExpectedTargetType(null) { emitExpression(rawRecvFrame!!) }
                    val rawTemp = freshTemp("kxs_sa_inln")
                    val unwrappedExpr = "kxs_unwrap_opt($rawTemp)"
                    append(inner)
                        .append("auto&& ")
                        .append(rawTemp)
                        .append(" = ")
                        .append(rawRecvCode)
                        .append(";\n")
                    if (!targetReturn || isOuterSuspendReturnContext) {
                        append(inner).append("if (!kxs_is_null(").append(rawTemp).append(")) {\n")
                        emitScopeBody("$inner    ", targetVar, targetReturn, recvOverrideCode = unwrappedExpr)
                        if (targetVar != null && !targetReturn) {
                            append(inner).append("} else {\n")
                            append(inner).append("    ").append(targetVar).append(" = {};\n")
                            append(inner).append("}\n")
                        } else if (targetReturn) {
                            val nullRet =
                                if (isVoidReturnContext && suspendBoxType == null && unitReturnExprOverride == null) {
                                    "nullptr"
                                } else {
                                    wrapSuspendReturnValue("{}")
                                }
                            append(inner).append("} else {\n")
                            append(inner).append("    return ").append(nullRet).append(";\n")
                            append(inner).append("}\n")
                        } else {
                            append(inner).append("}\n")
                        }
                    } else {
                        includes.addSystem("optional")
                        includes.addSystem("type_traits")
                        includes.addSystem("any")
                        val fnTemp = freshTemp("kxs_sa_fn")
                        append(inner).append("auto ").append(fnTemp).append(" = [&]() {\n")
                        emitScopeBody("$inner    ", effTargetVar = null, effTargetReturn = true, recvOverrideCode = unwrappedExpr)
                        append(inner).append("};\n")
                        append(inner).append("using KxsSaRes = decltype(").append(fnTemp).append("());\n")
                        val voidReturnAny =
                            if (currentExpectedTargetType == "std::any") " return std::any{};" else ""
                        append(inner)
                            .append("if constexpr (std::is_void_v<KxsSaRes>) { if (!kxs_is_null(")
                            .append(rawTemp)
                            .append(")) { ")
                            .append(fnTemp)
                            .append("(); }")
                            .append(voidReturnAny)
                            .append(" }\n")
                        append(inner)
                            .append("else if constexpr (std::is_pointer_v<std::decay_t<KxsSaRes>> || ")
                            .append("std::is_same_v<std::decay_t<KxsSaRes>, std::exception_ptr> || ")
                            .append("std::is_same_v<std::decay_t<KxsSaRes>, std::any> || ")
                            .append("requires { typename std::decay_t<KxsSaRes>::element_type; } || ")
                            .append("requires { typename std::decay_t<KxsSaRes>::value_type; ")
                            .append("std::declval<std::decay_t<KxsSaRes>&>().has_value(); }) {\n")
                        append(inner)
                            .append("    return !kxs_is_null(")
                            .append(rawTemp)
                            .append(") ? ")
                            .append(fnTemp)
                            .append("() : std::decay_t<KxsSaRes>{};\n")
                        append(inner).append("} else {\n")
                        append(inner)
                            .append("    return !kxs_is_null(")
                            .append(rawTemp)
                            .append(") ? std::optional<std::decay_t<KxsSaRes>>(")
                            .append(fnTemp)
                            .append("()) : std::nullopt;\n")
                        append(inner).append("}\n")
                    }
                }
                append(indent).append("}\n")
            }
        }
    }

    private fun emitSameFileInlineFunctionBody(
        out: StringBuilder,
        inlineExp: InlineCallExpansion,
        targetFn: KtFunctionSymbol,
        targetBody: PacketFrame,
        callerLambdaBody: PacketFrame,
        inner: String,
        targetVar: String?,
        targetReturn: Boolean,
        recvOverrideCode: String? = null,
    ) {
        val callFrame = stream.frames[inlineExp.callFrameIndex]
        val explicitTypeArgs =
            FrameAstHelpers
                .firstChildFrame(stream, callFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
                ?.let { ta ->
                    FrameAstHelpers
                        .childFramesOfType(stream, ta, KtNodeTypes.TYPE_PROJECTION_ID)
                        .map { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) }
                }.orEmpty()

        var recvTemp: String? = null
        var recvIsPtr = false
        if (inlineExp.receiverExprFrameIndex != null) {
            val rf = stream.frames[inlineExp.receiverExprFrameIndex]
            val rc = recvOverrideCode ?: emitExpression(rf)
            recvIsPtr = isPointerOrSharedPtrReceiver(rf, rc)
            val tmp = freshTemp("kxs_inln_recv")
            out
                .append(inner)
                .append("auto&& ")
                .append(tmp)
                .append(" = ")
                .append(rc)
                .append(";\n")
            recvTemp = tmp
        } else if (targetFn.receiverTypeFrameIndex != null || targetFn.enclosingClassFrameIndex != null) {
            val rc = receiverSubstitution ?: "(*this)"
            val tmp = freshTemp("kxs_inln_recv")
            out
                .append(inner)
                .append("auto&& ")
                .append(tmp)
                .append(" = ")
                .append(rc)
                .append(";\n")
            recvTemp = tmp
            recvIsPtr = extensionReceiverIsPointer
        }

        val targetCls = targetFn.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        val seenTps = HashSet<String>(activeTypeParams)
        var curEncCls: KtClassSymbol? = enclosingClass
        while (curEncCls != null) {
            curEncCls.typeParams.forEach { seenTps.add(it.name) }
            curEncCls = curEncCls.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        enclosingFunction?.typeParams?.forEach { seenTps.add(it.name) }
        var curTargetOuterCls: KtClassSymbol? =
            targetCls?.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        while (curTargetOuterCls != null) {
            curTargetOuterCls.typeParams.forEach { tp ->
                if (tp.name in activeTypeParams) seenTps.add(tp.name)
            }
            curTargetOuterCls =
                curTargetOuterCls.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        if (targetCls != null &&
            targetCls.typeParams.size == 1 &&
            targetFn.receiverTypeFrameIndex == null &&
            recvTemp != null
        ) {
            val tp = targetCls.typeParams[0]
            if (seenTps.add(tp.name)) {
                includes.addSystem("type_traits")
                val derefRecv = if (recvIsPtr) "*$recvTemp" else recvTemp
                val recvAlias = freshTemp("KxsRecvD")
                out
                    .append(inner)
                    .append("using ")
                    .append(recvAlias)
                    .append(" = std::remove_cvref_t<decltype(")
                    .append(derefRecv)
                    .append(")>;\n")
                out
                    .append(inner)
                    .append("using ")
                    .append(tp.name)
                    .append(" = typename decltype([]<typename KxsU>(std::type_identity<KxsU>) {\n")
                    .append(inner)
                    .append("    if constexpr (requires {\n")
                    .append(inner)
                    .append("        []<template <typename...> class KxsC, typename KxsT0, typename... KxsRest>")
                    .append("(KxsC<KxsT0, KxsRest...>*) {}(static_cast<KxsU*>(nullptr));\n")
                    .append(inner)
                    .append("    }) {\n")
                    .append(inner)
                    .append("        return []<template <typename...> class KxsC, typename KxsT0, ")
                    .append("typename... KxsRest>(KxsC<KxsT0, KxsRest...>*) {\n")
                    .append(inner)
                    .append("            return std::type_identity<KxsT0>{};\n")
                    .append(inner)
                    .append("        }(static_cast<KxsU*>(nullptr));\n")
                    .append(inner)
                    .append("    } else {\n")
                    .append(inner)
                    .append("        return std::type_identity<KxsU>{};\n")
                    .append(inner)
                    .append("    }\n")
                    .append(inner)
                    .append("}(std::type_identity<")
                    .append(recvAlias)
                    .append(">{}))::type;\n")
            }
        }

        val nonLambdaParams =
            if (targetFn.valueParams.isNotEmpty()) {
                targetFn.valueParams.dropLast(1)
            } else {
                emptyList()
            }
        val argTempsByParam = LinkedHashMap<String, String>()
        for ((idx, vp) in nonLambdaParams.withIndex()) {
            val argExprIdx = inlineExp.argExprFrameIndices.getOrNull(idx)
            val argCode =
                withExpectedTargetType(null) {
                    argExprIdx?.let { emitExpression(stream.frames[it]) }
                        ?: vp.defaultExprFrameIndex?.let { emitExpression(stream.frames[it]) }
                        ?: "{}"
                }
            val argTemp = freshTemp("kxs_inln_arg_${vp.cppName}")
            out
                .append(inner)
                .append("auto&& ")
                .append(argTemp)
                .append(" = ")
                .append(argCode)
                .append(";\n")
            argTempsByParam[vp.name] = argTemp
        }

        val targetAllTps = targetCls?.typeParams.orEmpty() + targetFn.typeParams
        if (targetAllTps.isNotEmpty()) {
            includes.addSystem("type_traits")
            val recvTypeSimple =
                targetFn.receiverTypeFrameIndex?.let {
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                }
            val retTypeSimple =
                targetFn.returnTypeFrameIndex?.let {
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                }
            for ((tpIdx, tp) in targetAllTps.withIndex()) {
                if (!seenTps.add(tp.name)) continue
                val explicitArgIdx = tpIdx - (targetCls?.typeParams?.size ?: 0)
                val explicitArg = if (explicitArgIdx >= 0) explicitTypeArgs.getOrNull(explicitArgIdx) else null
                val paramMatch =
                    nonLambdaParams.firstOrNull { vp ->
                        vp.typeFrameIndex != null &&
                            FrameAstHelpers.extractPrimaryTypeSimpleName(
                                stream,
                                stream.frames[vp.typeFrameIndex],
                            ) == tp.name
                    }
                val aliasRhs =
                    when {
                        explicitArg != null -> explicitArg
                        recvTemp != null && recvTypeSimple == tp.name ->
                            emitUnwrappedPointeeTypeAlias(recvTemp)
                        paramMatch != null && argTempsByParam[paramMatch.name] != null ->
                            "std::remove_cvref_t<decltype(${argTempsByParam[paramMatch.name]})>"
                        targetVar != null && retTypeSimple == tp.name ->
                            emitUnwrappedPointeeTypeAlias(targetVar)
                        else -> {
                            includes.addSystem("any")
                            "std::any"
                        }
                    }
                out
                    .append(inner)
                    .append("using ")
                    .append(tp.name)
                    .append(" = ")
                    .append(aliasRhs)
                    .append(";\n")
            }
        }

        val recvTypeSimple =
            targetFn.receiverTypeFrameIndex?.let {
                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
            }
        val resolvedRecvSimple =
            if (targetFn.receiverTypeFrameIndex != null) {
                targetFn.typeParams
                    .firstOrNull { it.name == recvTypeSimple }
                    ?.upperBoundFrameIndex
                    ?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
                    ?: targetCls
                        ?.typeParams
                        ?.firstOrNull { it.name == recvTypeSimple }
                        ?.upperBoundFrameIndex
                        ?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
                    ?: recvTypeSimple
            } else {
                targetCls?.kotlinName
            }
        val extRecvCls =
            resolvedRecvSimple?.let { lookupAccessibleClassByName(it) }
                ?: if (targetFn.receiverTypeFrameIndex == null) targetCls else null
        val fnEnclosingCls = targetCls
        val lambdaParamName = targetFn.valueParams.lastOrNull()?.name ?: "block"
        val site = InlinedCallerLambdaSite(callerLambdaBody, inlineExp.lambdaParamNames, callerEmitter = this)
        val hasEarlyUnlabeledReturn =
            !targetReturn &&
                targetBody.nodeTypeId == KtNodeTypes.BLOCK_ID &&
                hasEarlyUnlabeledReturnInInlineBody(targetBody)
        val exitLabel = if (hasEarlyUnlabeledReturn) freshTemp("kxs_inln_exit") else null

        val nestedEmitter =
            CppExprAndStmtEmitter(
                bundle = bundle,
                activeTypeParams = activeTypeParams + targetAllTps.map { it.name },
                enclosingClass = fnEnclosingCls,
                enclosingFunction = targetFn,
                receiverSubstitution = recvTemp ?: receiverSubstitution,
                extensionReceiverClass = extRecvCls,
                extensionReceiverIsPointer = recvIsPtr,
                extensionReceiverSimpleName = resolvedRecvSimple,
                unitReturnExprOverride = unitReturnExprOverride,
                isVoidReturnContext = isVoidReturnContext,
                enclosingFunctionReturnType = enclosingFunctionReturnType,
                inlinedLambdaSubstitutions = inlinedLambdaSubstitutions + (lambdaParamName to site),
                inlineReturnTargetVar = if (hasEarlyUnlabeledReturn) targetVar else null,
                inlineReturnExitLabel = exitLabel,
                activeInlineFunFrames = activeInlineFunFrames + targetFn.frameIndex,
                isSuspendBody = isSuspendBody,
                suspendBoxType = suspendBoxType,
            )
        nestedEmitter.inExpressionIifeDepth = inExpressionIifeDepth
        nestedEmitter.synthCounter = synthCounter
        for ((pKt, argTmp) in argTempsByParam) {
            nestedEmitter.bindLocalName(pKt, argTmp)
        }

        if (targetBody.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            val stmts = FrameAstHelpers.childFrames(stream, targetBody)
            for ((idx, s) in stmts.withIndex()) {
                val unwrappedS = unwrapTransparentFrame(s)
                val isLastUnlabeledReturn =
                    idx == stmts.lastIndex &&
                        !targetReturn &&
                        exitLabel == null &&
                        unwrappedS.nodeTypeId == KtNodeTypes.RETURN_ID &&
                        FrameAstHelpers.firstChildFrame(stream, unwrappedS, KtNodeTypes.LABEL_QUALIFIER_ID) == null
                nestedEmitter.emitHoistedPrefixesForStmt(out, s.frameIndex, inner)
                if (isLastUnlabeledReturn) {
                    val retExpr =
                        FrameAstHelpers
                            .childFrames(stream, unwrappedS)
                            .firstOrNull { it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID }
                    if (retExpr != null && targetVar != null) {
                        out.append(nestedEmitter.emitValueAssignmentOrReturn(retExpr, inner, targetReturn = false, targetVar = targetVar))
                    } else if (retExpr != null) {
                        out.append(nestedEmitter.emitStatement(retExpr, inner, inValuePosition = false, resultTargetVar = null))
                    }
                } else if (idx == stmts.lastIndex && (targetVar != null || targetReturn) && exitLabel == null) {
                    out.append(nestedEmitter.emitValueAssignmentOrReturn(s, inner, targetReturn = targetReturn, targetVar = targetVar))
                } else {
                    out.append(nestedEmitter.emitStatement(s, inner, inValuePosition = false, resultTargetVar = null))
                }
                nestedEmitter.emitDeadSpillResetsAfterStmt(out, s.frameIndex, inner)
            }
            if (exitLabel != null && nestedEmitter.usedInlineReturnExitLabel) {
                out.append(inner).append(exitLabel).append(":;\n")
            }
        } else {
            nestedEmitter.appendInlineLambdaBodyStatements(out, targetBody, inner, targetVar, targetReturn)
        }
        synthCounter = nestedEmitter.synthCounter
    }

    private fun extractCallTypeArgs(
        callFrame: PacketFrame,
        position: CppTypePosition = CppTypePosition.TYPE_ARGUMENT,
    ): List<String> {
        val typeArgsFrame = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
        return typeArgsFrame
            ?.let { ta ->
                FrameAstHelpers
                    .childFramesOfType(stream, ta, KtNodeTypes.TYPE_PROJECTION_ID)
                    .map { translateType(it, position) }
            }.orEmpty()
    }

    private fun extractInlineCallExpressionFrame(initFrame: PacketFrame): PacketFrame? {
        val u = unwrapTransparentFrame(initFrame)
        if (u.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) return u
        if (u.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID ||
            u.nodeTypeId == KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID
        ) {
            val sel = FrameAstHelpers.childFrames(stream, u).getOrNull(1)?.let { unwrapTransparentFrame(it) }
            if (sel?.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) return sel
        }
        return null
    }

    private fun resolveInlineReturnDeclType(
        initFrame: PacketFrame,
        inlineExp: InlineCallExpansion,
        targetInlineFn: KtFunctionSymbol,
        targetInlineCls: KtClassSymbol?,
        undeclaredTps: Set<String>,
    ): String {
        val retIdx = targetInlineFn.returnTypeFrameIndex ?: return "std::any"
        val rawRet = translateType(stream.frames[retIdx], CppTypePosition.VALUE)
        if (undeclaredTps.isEmpty()) return rawRet

        val callF = extractInlineCallExpressionFrame(initFrame)
        val explicitTypeArgs = callF?.let { extractCallTypeArgs(it) }.orEmpty()
        val substitutions = LinkedHashMap<String, String>()
        for ((idx, tp) in targetInlineFn.typeParams.withIndex()) {
            if (tp.name in undeclaredTps) {
                explicitTypeArgs.getOrNull(idx)?.let { substitutions[tp.name] = it }
            }
        }

        val recvFrame = inlineExp.receiverExprFrameIndex?.let { stream.frames[it] }
        val recvSimple =
            if (recvFrame != null) {
                inferExpressionSimpleTypeName(recvFrame)
            } else {
                extensionReceiverSimpleName ?: enclosingClass?.kotlinName
            }
        val recvParamSimple =
            targetInlineFn.receiverTypeFrameIndex?.let {
                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
            }
        if (recvParamSimple != null &&
            recvParamSimple in undeclaredTps &&
            recvParamSimple !in substitutions &&
            recvSimple != null &&
            recvSimple !in undeclaredTps
        ) {
            val recvCls = lookupAccessibleClassByName(recvSimple)
            if (recvCls != null && recvCls.typeParams.isEmpty()) {
                val isShared =
                    recvCls.kind == KtClassKind.INTERFACE ||
                        isSharedThisClass(recvCls.kotlinName) ||
                        recvCls.kotlinName in bundle.symbols.sharedHierarchyTypeNames
                if (isShared) {
                    includes.addSystem("memory")
                    substitutions[recvParamSimple] = "std::shared_ptr<${recvCls.cppName}>"
                } else {
                    substitutions[recvParamSimple] = recvCls.cppName
                }
            }
        }

        val crtpReceiverTarget =
            when {
                recvFrame == null && receiverSubstitution == null && enclosingClass != null -> "(*this)"
                recvFrame == null && receiverSubstitution != null -> receiverSubstitution
                recvFrame != null && unwrapTransparentFrame(recvFrame).nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID ->
                    "(${receiverSubstitution ?: "*this"})"
                recvFrame != null && unwrapTransparentFrame(recvFrame).nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID ->
                    emitExpression(recvFrame)
                else -> null
            }
        if (crtpReceiverTarget != null) {
            val singleClsTp = targetInlineCls?.typeParams?.singleOrNull()
            val singleClsUb = singleClsTp?.upperBoundFrameIndex
            if (singleClsTp != null &&
                singleClsUb != null &&
                singleClsTp.name in undeclaredTps &&
                singleClsTp.name !in substitutions
            ) {
                val ubSimple =
                    FrameAstHelpers.extractPrimaryTypeSimpleName(
                        stream,
                        stream.frames[singleClsUb],
                    )
                if (ubSimple == targetInlineCls.kotlinName) {
                    substitutions[singleClsTp.name] =
                        if (crtpReceiverTarget == "(*this)") {
                            includes.addSystem("type_traits")
                            "std::remove_cvref_t<decltype(*this)>"
                        } else {
                            emitUnwrappedPointeeTypeAlias(crtpReceiverTarget)
                        }
                }
            }
            if (recvParamSimple != null &&
                recvParamSimple in undeclaredTps &&
                recvParamSimple !in substitutions
            ) {
                substitutions[recvParamSimple] =
                    if (crtpReceiverTarget == "(*this)") {
                        includes.addSystem("type_traits")
                        "std::remove_cvref_t<decltype(*this)>"
                    } else {
                        emitUnwrappedPointeeTypeAlias(crtpReceiverTarget)
                    }
            }
        }

        var resolved = rawRet
        for ((tpName, repl) in substitutions) {
            resolved = Regex("""\b${Regex.escape(tpName)}\b""").replace(resolved) { repl }
        }
        val stillHasUndeclared =
            undeclaredTps.any { tpName -> Regex("""\b${Regex.escape(tpName)}\b""").containsMatchIn(resolved) }
        if (stillHasUndeclared || resolved == "void" || resolved == "decltype(auto)") {
            includes.addSystem("any")
            return "std::any"
        }
        return resolved
    }

    private fun emitUnwrappedPointeeTypeAlias(expr: String): String {
        includes.addSystem("type_traits")
        return "typename decltype([](const auto& kxs_pt) { " +
            "using KxsPt = std::remove_cvref_t<decltype(kxs_pt)>; " +
            "if constexpr (std::is_pointer_v<KxsPt>) return std::type_identity<std::remove_pointer_t<KxsPt>>{}; " +
            "else if constexpr (requires { typename KxsPt::element_type; }) return std::type_identity<typename KxsPt::element_type>{}; " +
            "else return std::type_identity<KxsPt>{}; " +
            "}($expr))::type"
    }

    private fun copyLexicalStateTo(target: CppExprAndStmtEmitter) {
        target.inExpressionIifeDepth = inExpressionIifeDepth
        target.synthCounter = synthCounter
        for (scope in localBindings) target.localBindings.last().putAll(scope)
        for (scope in exceptionPtrScopes) target.exceptionPtrScopes.last().addAll(scope)
        for (scope in stdAnyScopes) target.stdAnyScopes.last().addAll(scope)
        for (scope in localPolyVarScopes) target.localPolyVarScopes.last().addAll(scope)
        for (scope in localTypeScopes) target.localTypeScopes.last().putAll(scope)
    }

    private fun buildInlineScopeEmitter(
        recvFrame: PacketFrame?,
        recvCode: String,
        recvTemp: String,
    ): CppExprAndStmtEmitter {
        val recvSimple =
            if (recvFrame != null) {
                inferExpressionSimpleTypeName(recvFrame)
            } else {
                extensionReceiverSimpleName
            }
        val recvCls =
            if (recvFrame != null) {
                recvSimple?.let { resolveClassOrTypeParamBound(it) }
            } else {
                extensionReceiverClass
            }
        val recvIsPtr =
            if (recvFrame != null) {
                isPointerOrSharedPtrReceiver(recvFrame, recvCode)
            } else {
                extensionReceiverIsPointer
            }
        val nested =
            CppExprAndStmtEmitter(
                bundle = bundle,
                activeTypeParams = activeTypeParams,
                enclosingClass = enclosingClass,
                enclosingFunction = if (recvFrame == null) enclosingFunction else null,
                receiverSubstitution = recvTemp,
                extensionReceiverClass = recvCls,
                extensionReceiverIsPointer = recvIsPtr,
                extensionReceiverSimpleName = recvCls?.kotlinName ?: recvSimple,
                unitReturnExprOverride = unitReturnExprOverride,
                isVoidReturnContext = isVoidReturnContext,
                enclosingFunctionReturnType = enclosingFunctionReturnType,
                inlinedLambdaSubstitutions = inlinedLambdaSubstitutions,
                localPolymorphicClasses = localPolymorphicClasses,
                localExtensionFunctions = localExtensionFunctions,
                activeInlineFunFrames = activeInlineFunFrames,
                isSuspendBody = isSuspendBody,
                suspendBoxType = suspendBoxType,
                expectedTargetType = currentExpectedTargetType,
            )
        nested.inExpressionIifeDepth = inExpressionIifeDepth
        copyLexicalStateTo(nested)
        return nested
    }

    private fun hasEarlyUnlabeledReturnInInlineBody(blockBody: PacketFrame): Boolean {
        val stmts = FrameAstHelpers.childFrames(stream, blockBody)
        for ((idx, s) in stmts.withIndex()) {
            if (idx == stmts.lastIndex) {
                val u = unwrapTransparentFrame(s)
                if (u.nodeTypeId == KtNodeTypes.RETURN_ID &&
                    FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.LABEL_QUALIFIER_ID) == null
                ) {
                    val expr = FrameAstHelpers.childFrames(stream, u).firstOrNull { it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID }
                    if (expr != null && containsUnlabeledReturn(expr)) return true
                    continue
                }
            }
            if (containsUnlabeledReturn(s)) return true
        }
        return false
    }

    private fun containsUnlabeledReturn(frame: PacketFrame): Boolean {
        if (frame.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID || frame.nodeTypeId == KtNodeTypes.FUN_ID) {
            return false
        }
        if (frame.nodeTypeId == KtNodeTypes.RETURN_ID &&
            FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.LABEL_QUALIFIER_ID) == null
        ) {
            return true
        }
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            if (containsUnlabeledReturn(child)) return true
        }
        return false
    }

    private fun expandInlinedCallerLambdaCall(
        callFrame: PacketFrame,
        site: InlinedCallerLambdaSite,
        indent: String,
        targetVar: String?,
        targetReturn: Boolean,
    ): String {
        val callArgs = extractCallArgExpressions(callFrame)
        return site.callerEmitter.withLexicalScope {
            buildString {
                append(indent).append("{\n")
                val inner = "$indent    "
                val pNames =
                    if (site.lambdaParamNames.isNotEmpty()) {
                        site.lambdaParamNames
                    } else if (callArgs.size == 1 && site.callerEmitter.referencesImplicitIt(site.lambdaBodyFrame)) {
                        listOf("it")
                    } else {
                        emptyList()
                    }
                for ((idx, pName) in pNames.withIndex()) {
                    if (pName == "_") continue
                    val argVal = callArgs.getOrNull(idx) ?: "{}"
                    val pCpp = CppNamingPolicy.toSnakeCase(pName)
                    if (argVal.trim() != pCpp) {
                        append(inner)
                            .append("auto&& ")
                            .append(pCpp)
                            .append(" = ")
                            .append(argVal)
                            .append(";\n")
                    }
                    site.callerEmitter.bindLocalName(pName, pCpp)
                }
                site.callerEmitter.appendInlineLambdaBodyStatements(this, site.lambdaBodyFrame, inner, targetVar, targetReturn)
                append(indent).append("}\n")
            }
        }
    }

    private fun appendInlineLambdaBodyStatements(
        out: StringBuilder,
        lambdaBody: PacketFrame,
        indent: String,
        targetVar: String?,
        targetReturn: Boolean = false,
    ) {
        if (lambdaBody.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            val stmts = FrameAstHelpers.childFrames(stream, lambdaBody)
            for ((idx, s) in stmts.withIndex()) {
                emitHoistedPrefixesForStmt(out, s.frameIndex, indent)
                if (idx == stmts.lastIndex && (targetVar != null || targetReturn)) {
                    out.append(emitValueAssignmentOrReturn(s, indent, targetReturn = targetReturn, targetVar = targetVar))
                } else {
                    out.append(emitStatement(s, indent, inValuePosition = false, resultTargetVar = null))
                }
                emitDeadSpillResetsAfterStmt(out, s.frameIndex, indent)
            }
        } else {
            emitHoistedPrefixesForStmt(out, lambdaBody.frameIndex, indent)
            if (targetVar != null || targetReturn) {
                out.append(emitValueAssignmentOrReturn(lambdaBody, indent, targetReturn = targetReturn, targetVar = targetVar))
            } else {
                out.append(emitStatement(lambdaBody, indent, inValuePosition = false, resultTargetVar = null))
            }
        }
    }

    private fun emitMemScopedStatement(
        ms: MemScopedBlockInfo,
        indent: String,
    ): String {
        val body = stream.frames[ms.bodyFrameIndex]
        return if (body.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            "${emitBlock(body, indent, isValueBlock = false, resultTargetVar = null)}\n"
        } else {
            buildString {
                append(indent).append("{\n")
                append(emitStatement(body, "$indent    ", inValuePosition = false, resultTargetVar = null))
                append(indent).append("}\n")
            }
        }
    }

    private fun emitUsePinnedStatement(
        up: UsePinnedBlockInfo,
        indent: String,
    ): String {
        val targetExpr = emitExpression(stream.frames[up.targetExprFrameIndex])
        val pinnedVar = freshTemp("kxs_pinned")
        val body = stream.frames[up.bodyFrameIndex]
        return withLexicalScope {
            bindLocalName(up.pinnedParamName, pinnedVar)
            buildString {
                append(indent).append("{\n")
                val inner = "$indent    "
                append(inner)
                    .append("auto&& ")
                    .append(pinnedVar)
                    .append(" = ")
                    .append(targetExpr)
                    .append(";\n")
                if (bundle.config.gcMode == CppGcMode.KONAN_GC) {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    append(inner).append("PinnedArrayGuard kxs_pin_guard(").append(pinnedVar).append(".data());\n")
                }
                appendInlineLambdaBodyStatements(this, body, inner, targetVar = null)
                append(indent).append("}\n")
            }
        }
    }

    private fun emitAtomicLoopOrUpdateStatement(
        aop: AtomicOperationSite,
        indent: String,
    ): String {
        includes.addSystem("atomic")
        val atomicRef = renderAtomicTargetLvalue(aop)
        val curKt = aop.loopCurParamName ?: "it"
        val curCpp = CppNamingPolicy.toSnakeCase(curKt)
        val lambdaFrame = aop.loopLambdaFrameIndex?.let { stream.frames[it] }
        val fnLit = lambdaFrame?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.FUNCTION_LITERAL_ID) }
        val body =
            if (fnLit != null) {
                FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
            } else {
                lambdaFrame
            }

        return withLexicalScope {
            bindLocalName(curKt, curCpp)
            buildString {
                append(indent).append("while (true) {\n")
                val inner = "$indent    "
                append(inner)
                    .append("auto ")
                    .append(curCpp)
                    .append(" = ")
                    .append(atomicRef)
                    .append(".load(std::memory_order_seq_cst);\n")
                if (aop.kind == AtomicOpKind.LOOP) {
                    if (body != null) {
                        appendInlineLambdaBodyStatements(this, body, inner, targetVar = null)
                    }
                } else {
                    val nextVar = freshTemp("kxs_next")
                    if (body != null) {
                        append(inner).append("auto ").append(nextVar).append(" = [&]() ")
                        if (body.nodeTypeId == KtNodeTypes.BLOCK_ID) {
                            append(emitBlock(body, inner, isValueBlock = true, resultTargetVar = null))
                        } else {
                            append("{ return ").append(emitExpression(body)).append("; }")
                        }
                        append("();\n")
                    } else {
                        append(inner)
                            .append("auto ")
                            .append(nextVar)
                            .append(" = ")
                            .append(curCpp)
                            .append(";\n")
                    }
                    append(inner)
                        .append("if (")
                        .append(atomicRef)
                        .append(".compare_exchange_strong(")
                        .append(curCpp)
                        .append(", ")
                        .append(nextVar)
                        .append(", std::memory_order_seq_cst, std::memory_order_relaxed)) break;\n")
                }
                append(indent).append("}\n")
            }
        }
    }

    fun emitExpression(exprFrame: PacketFrame): String {
        // 1. Substitute hoisted `hasSuspendCallInTail` temporary when present!
        bundle.suspendPlan.hoistedTempByExprFrame[exprFrame.frameIndex]?.let { hoisted ->
            return hoisted.tempCppName
        }
        // 2. Substitute hoisted suspend control-flow slot (`kxs_cfN`) when present!
        bundle.cfPlan.hoistedSuspendControlFlowsByExpr[exprFrame.frameIndex]?.let { cf ->
            return "(*${cf.slotCppName})"
        }
        return emitExprIgnoringHoist(exprFrame)
    }

    private fun emitExprIgnoringHoist(exprFrame: PacketFrame): String {
        preLoweredElvisExprs[exprFrame.frameIndex]?.let { return it }

        // 1. Check `atomicfu` operation site
        bundle.atomicPlan.atomicOpsByExprFrame[exprFrame.frameIndex]?.let { return emitAtomicOpExpression(it) }

        // 2. Check `cinterop` operation site
        bundle.ffiPlan.cInteropOpsByExprFrame[exprFrame.frameIndex]?.let { return emitCInteropOpExpression(it) }

        // 3. Check `KONAN_GC` heap write barrier (`obj.field = rhs`)
        if (exprFrame.frameIndex in bundle.gcPlan.heapWriteBarrierExprFrames) {
            val ch = FrameAstHelpers.childFrames(stream, exprFrame)
            if (ch.size >= 3) {
                includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                includes.addSystem("memory")
                val lhs = emitExpression(ch[0])
                val rhs = emitExpression(ch[2])
                return "Kotlin_mm_UpdateHeapRef(std::addressof($lhs), $rhs)"
            }
        }

        // 4. Check verbatim inline C/C++ expression
        bundle.ffiPlan.inlineCppByFrame[exprFrame.frameIndex]?.let { return it.trim() }

        bundle.suspendPlan.inlineExpansionsByCallFrame[exprFrame.frameIndex]?.let { inlineExp ->
            val iifeRetSuffix = iifeAnyRetSuffix()
            return withinExpressionIife {
                val body = emitInlineExpansionAsStatement(inlineExp, "    ", targetVar = null, targetReturn = true)
                "[&]()$iifeRetSuffix {\n$body}()"
            }
        }

        return when (exprFrame.nodeTypeId) {
            KtNodeTypes.PARENTHESIZED_ID -> {
                val inner = FrameAstHelpers.childFrames(stream, exprFrame).firstOrNull()
                if (inner != null) "(${emitExpression(inner)})" else "()"
            }

            KtNodeTypes.INTEGER_CONSTANT_ID,
            KtNodeTypes.FLOAT_CONSTANT_ID,
            KtNodeTypes.BOOLEAN_CONSTANT_ID,
            KtNodeTypes.CHARACTER_CONSTANT_ID,
            KtNodeTypes.NULL_ID,
            -> emitConstantLiteral(exprFrame)

            KtNodeTypes.STRING_TEMPLATE_ID -> emitStringTemplate(exprFrame)

            KtNodeTypes.THIS_EXPRESSION_ID -> emitThisExpr(exprFrame)
            KtNodeTypes.SUPER_EXPRESSION_ID -> resolvePrimarySuperCppType(exprFrame)

            KtNodeTypes.REFERENCE_EXPRESSION_ID -> emitReferenceExpr(exprFrame)

            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> emitDotQualifiedExpr(exprFrame)
            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID -> emitSafeAccessExpr(exprFrame)

            KtNodeTypes.CALL_EXPRESSION_ID -> emitCallExpr(receiverCode = null, receiverIsPointer = false, callFrame = exprFrame)

            KtNodeTypes.ARRAY_ACCESS_EXPRESSION_ID -> emitArrayAccessExpr(exprFrame)

            KtNodeTypes.BINARY_EXPRESSION_ID -> emitBinaryExpr(exprFrame)
            KtNodeTypes.BINARY_WITH_TYPE_ID -> emitBinaryWithTypeExpr(exprFrame)
            KtNodeTypes.IS_EXPRESSION_ID -> emitIsExpr(exprFrame)
            KtNodeTypes.PREFIX_EXPRESSION_ID -> emitPrefixExpr(exprFrame)
            KtNodeTypes.POSTFIX_EXPRESSION_ID -> emitPostfixExpr(exprFrame)

            KtNodeTypes.IF_ID -> {
                // Non-suspending expression-valued `if` -> C++ ternary or IIFE
                emitExpressionIf(exprFrame)
            }
            KtNodeTypes.WHEN_ID -> {
                // Non-suspending expression-valued `when` -> C++ IIFE
                val iifeRetSuffix = iifeAnyRetSuffix()
                withinExpressionIife {
                    "[&]()$iifeRetSuffix {\n" +
                        emitWhenStatement(exprFrame, "    ", inValuePosition = true, targetReturn = true, targetVar = null) +
                        "}()"
                }
            }
            KtNodeTypes.TRY_ID -> {
                val iifeRetSuffix = iifeAnyRetSuffix()
                withinExpressionIife {
                    "[&]()$iifeRetSuffix {\n" +
                        emitTryStatement(exprFrame, "    ", inValuePosition = true, targetReturn = true, targetVar = null) +
                        "}()"
                }
            }
            KtNodeTypes.LAMBDA_EXPRESSION_ID -> emitLambdaClosureExpr(exprFrame)
            KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION_ID -> emitCallableRefExpr(exprFrame)
            KtNodeTypes.CLASS_LITERAL_EXPRESSION_ID -> {
                includes.addSystem("typeinfo")
                val lhs = FrameAstHelpers.childFrames(stream, exprFrame).firstOrNull()
                val typeCode =
                    when {
                        lhs == null -> "void"
                        lhs.nodeTypeId == KtNodeTypes.TYPE_REFERENCE_ID || lhs.nodeTypeId == KtNodeTypes.USER_TYPE_ID ->
                            bundle.typeTranslator.translateTypeFrame(lhs, CppTypePosition.TYPE_ARGUMENT)
                        lhs.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID ||
                            lhs.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                            val simple = FrameAstHelpers.dottedNameText(stream, lhs).substringAfterLast('.').ifEmpty { "void" }
                            CppNamingPolicy.toTypeName(simple)
                        }
                        else -> "decltype(${emitExpression(lhs)})"
                    }
                "typeid($typeCode)"
            }
            KtNodeTypes.THROW_ID -> {
                val exprChild = FrameAstHelpers.childFrames(stream, exprFrame).firstOrNull()
                if (exprChild != null && isExceptionPtrExpr(exprChild)) {
                    includes.addSystem("exception")
                    "([&]() -> void { std::rethrow_exception(${emitExpression(exprChild)}); }())"
                } else {
                    val rendered = exprChild?.let { emitThrowableExpr(it) } ?: "std::runtime_error(\"throw\")"
                    "throw $rendered"
                }
            }
            KtNodeTypes.LABELED_EXPRESSION_ID -> {
                val inner =
                    FrameAstHelpers
                        .childFrames(stream, exprFrame)
                        .lastOrNull { it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID && it.nodeTypeId != KtNodeTypes.LABEL_ID }
                if (inner != null) emitExpression(inner) else "{}"
            }
            KtNodeTypes.ANNOTATED_EXPRESSION_ID -> {
                val inner =
                    FrameAstHelpers
                        .childFrames(stream, exprFrame)
                        .lastOrNull { it.nodeTypeId != KtNodeTypes.ANNOTATION_ENTRY_ID && it.nodeTypeId != KtNodeTypes.ANNOTATION_ID }
                if (inner != null) emitExpression(inner) else "{}"
            }
            KtNodeTypes.COLLECTION_LITERAL_EXPRESSION_ID -> {
                val elems = FrameAstHelpers.childFrames(stream, exprFrame).joinToString(", ") { emitExpression(it) }
                "{$elems}"
            }
            KtNodeTypes.BLOCK_ID -> {
                val iifeRetSuffix = iifeAnyRetSuffix()
                withinExpressionIife {
                    "[&]()$iifeRetSuffix ${emitBlock(exprFrame, "", isValueBlock = true, resultTargetVar = null)}()"
                }
            }
            KtNodeTypes.OBJECT_LITERAL_ID -> emitObjectLiteralExpr(exprFrame)
            else ->
                throw IllegalArgumentException(
                    "${stream.sourcePath}:${exprFrame.span.startLine}:${exprFrame.span.startColumn}: " +
                        "unsupported Kotlin expression node ${KtNodeTypes.debugName(exprFrame.nodeTypeId)}",
                )
        }
    }

    private data class InlineIifeEligibility(
        val canEmitIife: Boolean,
        val hasLambdaOwnReturn: Boolean,
    )

    private data class InlineTailTypeResult(
        val simpleName: String?,
        val cppType: String?,
    )

    private data class LambdaVarTypeInfo(
        val simpleName: String?,
        val cppType: String?,
        val nonNullCppType: String?,
        val typeOrInitFrame: PacketFrame?,
    )

    private fun applyInlineLocalVarMetadata(
        kotlinName: String,
        cppName: String,
        declaredInlineSimple: String?,
        effectiveDeclType: String,
    ) {
        if (declaredInlineSimple != null) markLocalType(kotlinName, declaredInlineSimple)
        if (effectiveDeclType == "std::any" || declaredInlineSimple == "Any") {
            markStdAnyVar(kotlinName)
        } else {
            unmarkStdAnyVar(kotlinName)
            val effInlineSimple = declaredInlineSimple?.let { bundle.symbols.importAliases[it] ?: it }
            if (isCppPtrOrSharedType(effectiveDeclType) ||
                (effInlineSimple != null && effInlineSimple in bundle.symbols.sharedPtrTypeAliases)
            ) {
                markLocalPolyVar(kotlinName)
            }
        }
        bindLocalName(kotlinName, cppName)
    }

    private fun copyLocalMetadataTo(
        target: CppExprAndStmtEmitter,
        kotlinName: String,
    ) {
        resolveLocalTypeSimpleName(kotlinName)?.let { target.markLocalType(kotlinName, it) }
        if (isLocalPolyVar(kotlinName)) target.markLocalPolyVar(kotlinName)
        if (isStdAnyKotlinVar(kotlinName)) target.markStdAnyVar(kotlinName)
        if (isExceptionPtrKotlinVar(kotlinName)) target.markExceptionPtrVar(kotlinName)
    }

    private fun extractLabelQualifierName(labelQual: PacketFrame): String? =
        FrameAstHelpers.directIdentPacket(stream, labelQual)?.unescapedName
            ?: FrameAstHelpers.firstChildFrame(stream, labelQual, KtNodeTypes.LABEL_ID)?.let { labelNode ->
                FrameAstHelpers.directIdentPacket(stream, labelNode)?.unescapedName
            }

    private fun extractInlineLambdaOwnLabel(
        callExprFrame: PacketFrame,
        lambdaFrameIndex: Int,
    ): String? {
        val candidateArgs = ArrayList<PacketFrame>()
        candidateArgs.addAll(FrameAstHelpers.childFramesOfType(stream, callExprFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID))
        FrameAstHelpers.firstChildFrame(stream, callExprFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)?.let { valList ->
            candidateArgs.addAll(FrameAstHelpers.childFramesOfType(stream, valList, KtNodeTypes.VALUE_ARGUMENT_ID))
        }
        for (arg in candidateArgs) {
            val labeled = FrameAstHelpers.firstChildFrame(stream, arg, KtNodeTypes.LABELED_EXPRESSION_ID) ?: continue
            if (FrameAstHelpers.unwrapLambdaExpression(stream, labeled)?.frameIndex != lambdaFrameIndex) continue
            val labelQual = FrameAstHelpers.firstChildFrame(stream, labeled, KtNodeTypes.LABEL_QUALIFIER_ID) ?: continue
            val labelName = extractLabelQualifierName(labelQual)
            if (!labelName.isNullOrEmpty()) return labelName
        }
        return null
    }

    private fun extractLambdaBodyFrame(lambdaFrameIndex: Int): PacketFrame? {
        val lambdaFrame = stream.frames.getOrNull(lambdaFrameIndex) ?: return null
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID) ?: lambdaFrame
        return FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID)
            ?: FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.BLOCK_ID)
            ?: fnLit
    }

    private fun checkInlineIifeEligibility(
        lambdaFrameIndex: Int,
        lambdaOwnLabel: String?,
        inlineCalleeName: String,
    ): InlineIifeEligibility {
        val body =
            extractLambdaBodyFrame(lambdaFrameIndex)
                ?: return InlineIifeEligibility(canEmitIife = false, hasLambdaOwnReturn = false)
        var canEmitIife = true
        var hasLambdaOwnReturn = false

        fun walk(
            node: PacketFrame,
            loopDepth: Int,
            nestedLambdaLabel: String?,
        ) {
            if (!canEmitIife) return
            if (PacketFlags.hasFlag(node.flags, PacketFlags.SUSPEND_CALL_SITE)) {
                canEmitIife = false
                return
            }
            when (node.nodeTypeId) {
                KtNodeTypes.FUN_ID,
                KtNodeTypes.CLASS_ID,
                KtNodeTypes.OBJECT_LITERAL_ID,
                -> return
                KtNodeTypes.LAMBDA_EXPRESSION_ID -> {
                    if (nestedLambdaLabel == null) return
                }
                KtNodeTypes.BREAK_ID,
                KtNodeTypes.CONTINUE_ID,
                -> {
                    val labelQual = FrameAstHelpers.firstChildFrame(stream, node, KtNodeTypes.LABEL_QUALIFIER_ID)
                    if (loopDepth == 0 || labelQual != null) {
                        canEmitIife = false
                        return
                    }
                }
                KtNodeTypes.RETURN_ID -> {
                    val labelQual = FrameAstHelpers.firstChildFrame(stream, node, KtNodeTypes.LABEL_QUALIFIER_ID)
                    val labelName = labelQual?.let { extractLabelQualifierName(it) }
                    if (nestedLambdaLabel != null) {
                        if (labelName == null || labelName != nestedLambdaLabel) {
                            canEmitIife = false
                        }
                        return
                    }
                    val matchesLambdaReturn =
                        labelName != null &&
                            (
                                (lambdaOwnLabel != null && labelName == lambdaOwnLabel) ||
                                    (inlineCalleeName.isNotEmpty() && labelName == inlineCalleeName)
                            )
                    if (matchesLambdaReturn) {
                        hasLambdaOwnReturn = true
                    } else {
                        canEmitIife = false
                        return
                    }
                }
                KtNodeTypes.FOR_ID,
                KtNodeTypes.WHILE_ID,
                KtNodeTypes.DO_WHILE_ID,
                -> {
                    for (child in FrameAstHelpers.childFrames(stream, node)) {
                        walk(child, loopDepth + 1, nestedLambdaLabel)
                    }
                    return
                }
                KtNodeTypes.CALL_EXPRESSION_ID -> {
                    val callee = extractCalleeSimpleName(node)
                    val nestedInlineLabel =
                        if (node.frameIndex in bundle.suspendPlan.inlineExpansionsByCallFrame ||
                            callee in INLINE_SCOPE_HELPER_NAMES
                        ) {
                            callee
                        } else {
                            null
                        }
                    for (child in FrameAstHelpers.childFrames(stream, node)) {
                        val isLambdaArg =
                            child.nodeTypeId == KtNodeTypes.LAMBDA_ARGUMENT_ID ||
                                child.nodeTypeId == KtNodeTypes.VALUE_ARGUMENT_LIST_ID
                        walk(
                            child,
                            if (isLambdaArg && nestedInlineLabel != null) 0 else loopDepth,
                            if (isLambdaArg) nestedInlineLabel else nestedLambdaLabel,
                        )
                    }
                    return
                }
            }
            for (child in FrameAstHelpers.childFrames(stream, node)) {
                walk(child, loopDepth, nestedLambdaLabel)
            }
        }

        for (child in FrameAstHelpers.childFrames(stream, body)) {
            walk(child, loopDepth = 0, nestedLambdaLabel = null)
        }
        return InlineIifeEligibility(canEmitIife = canEmitIife, hasLambdaOwnReturn = hasLambdaOwnReturn)
    }

    private fun wrapNullableCppType(nonNullCpp: String): String =
        if (nonNullCpp.endsWith("*") ||
            nonNullCpp.startsWith("std::shared_ptr<") ||
            nonNullCpp.startsWith("std::optional<") ||
            nonNullCpp.startsWith("std::function<") ||
            nonNullCpp == "std::any" ||
            nonNullCpp == "std::exception_ptr"
        ) {
            nonNullCpp
        } else {
            includes.addSystem("optional")
            "std::optional<$nonNullCpp>"
        }

    private fun extractFirstContainerElementProjection(typeOrCallFrame: PacketFrame): PacketFrame? {
        val unwrapped = unwrapTransparentFrame(typeOrCallFrame)
        val typeArgList =
            when (unwrapped.nodeTypeId) {
                KtNodeTypes.CALL_EXPRESSION_ID ->
                    FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
                KtNodeTypes.TYPE_REFERENCE_ID,
                KtNodeTypes.NULLABLE_TYPE_ID,
                -> {
                    val userType = FrameAstHelpers.findDescendantFrame(stream, unwrapped, KtNodeTypes.USER_TYPE_ID)
                    userType?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_ARGUMENT_LIST_ID) }
                }
                KtNodeTypes.USER_TYPE_ID ->
                    FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
                else -> null
            } ?: return null
        return FrameAstHelpers.childFramesOfType(stream, typeArgList, KtNodeTypes.TYPE_PROJECTION_ID).firstOrNull()
    }

    private fun resolveSameFileClassCppTypeName(cls: KtClassSymbol): String {
        val parentIdx = cls.enclosingClassFrameIndex ?: return cls.cppName
        val parentCls = bundle.symbols.classesByFrame[parentIdx] ?: return cls.cppName
        val hoistedName = bundle.symbols.nonInnerNestedRewrites[parentCls.kotlinName to cls.kotlinName]
        if (hoistedName != null) return hoistedName
        if (enclosingClass?.frameIndex == parentIdx) return cls.cppName
        return "${resolveSameFileClassCppTypeName(parentCls)}::${cls.cppName}"
    }

    private fun wrapClassCppValueType(
        effKotlinName: String,
        cppBaseType: String,
    ): String =
        if (CppTypeTranslator.isSharedHierarchyType(effKotlinName) ||
            effKotlinName in bundle.symbols.sharedHierarchyTypeNames ||
            effKotlinName in bundle.symbols.sharedPtrTypeAliases
        ) {
            includes.addSystem("memory")
            "std::shared_ptr<$cppBaseType>"
        } else if (effKotlinName in bundle.symbols.polymorphicTypeNames) {
            "$cppBaseType*"
        } else {
            cppBaseType
        }

    private fun resolveSimpleNameToValueCppType(simpleName: String): String? {
        if (simpleName == "Any") {
            includes.addSystem("any")
            return "std::any"
        }
        if (simpleName == "Unit") {
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            val segs = bundle.symbols.namespaceSegments
            val inCoroNs = segs.isEmpty() || (segs.size >= 2 && segs[0] == "kotlinx" && segs[1] == "coroutines")
            return if (inCoroNs) "Unit" else "kotlinx::coroutines::Unit"
        }
        val scalarCpp =
            when (simpleName) {
                "Boolean" -> "bool"
                "Byte" -> {
                    includes.addSystem("cstdint")
                    "std::int8_t"
                }
                "Short" -> {
                    includes.addSystem("cstdint")
                    "std::int16_t"
                }
                "Int" -> {
                    includes.addSystem("cstdint")
                    "std::int32_t"
                }
                "Long" -> {
                    includes.addSystem("cstdint")
                    "std::int64_t"
                }
                "UByte" -> {
                    includes.addSystem("cstdint")
                    "std::uint8_t"
                }
                "UShort" -> {
                    includes.addSystem("cstdint")
                    "std::uint16_t"
                }
                "UInt" -> {
                    includes.addSystem("cstdint")
                    "std::uint32_t"
                }
                "ULong" -> {
                    includes.addSystem("cstdint")
                    "std::uint64_t"
                }
                "Float" -> "float"
                "Double" -> "double"
                "Char" -> "char16_t"
                "String" -> {
                    includes.addSystem("string")
                    "std::string"
                }
                else -> null
            }
        if (scalarCpp != null) return scalarCpp

        val eff = bundle.symbols.importAliases[simpleName] ?: simpleName
        if (eff in activeTypeParams) {
            val boundIdx =
                enclosingFunction?.typeParams?.firstOrNull { it.name == eff }?.upperBoundFrameIndex
                    ?: enclosingClass?.typeParams?.firstOrNull { it.name == eff }?.upperBoundFrameIndex
            val boundSimple = boundIdx?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
            val isSharedTp =
                boundSimple != null &&
                    (
                        boundSimple in bundle.symbols.sharedHierarchyTypeNames ||
                            CppTypeTranslator.isSharedHierarchyType(boundSimple)
                    )
            return if (isSharedTp) {
                includes.addSystem("memory")
                "std::shared_ptr<$eff>"
            } else {
                eff
            }
        }
        val cls = lookupAccessibleClassByName(simpleName) ?: lookupAccessibleClassByName(eff)
        if (cls != null) {
            return wrapClassCppValueType(eff, resolveSameFileClassCppTypeName(cls))
        }
        val cppBase =
            CppTypeTranslator.includeAndQualifyKnownType(
                simpleName = eff,
                cppBaseName = CppNamingPolicy.toTypeName(eff),
                currentNs = bundle.symbols.namespaceSegments,
                includes = includes,
            ) ?: return null
        return wrapClassCppValueType(eff, cppBase)
    }

    private fun extractNullCheckVarName(condFrame: PacketFrame): Pair<String, Boolean>? {
        val unwrapped = unwrapTransparentFrame(condFrame)
        if (unwrapped.nodeTypeId != KtNodeTypes.BINARY_EXPRESSION_ID) return null
        val ch = FrameAstHelpers.childFrames(stream, unwrapped)
        if (ch.size < 3) return null
        val opId = FrameAstHelpers.operationTokenId(stream, ch[1])
        if (opId != KtTokens.EQEQ_ID && opId != KtTokens.EXCLEQ_ID) return null
        val lhs = unwrapTransparentFrame(ch[0])
        val rhs = unwrapTransparentFrame(ch[2])
        val refFrame =
            when {
                lhs.nodeTypeId == KtNodeTypes.NULL_ID && rhs.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID -> rhs
                rhs.nodeTypeId == KtNodeTypes.NULL_ID && lhs.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID -> lhs
                else -> return null
            }
        val varName = FrameAstHelpers.directIdentPacket(stream, refFrame)?.unescapedName ?: return null
        return varName to (opId == KtTokens.EQEQ_ID)
    }

    private fun resolveReceiverTypeOrInitFrame(
        recvFrame: PacketFrame,
        localVars: Map<String, LambdaVarTypeInfo>,
    ): PacketFrame? {
        val unwrapped = unwrapTransparentFrame(recvFrame)
        if (unwrapped.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
            val ch = FrameAstHelpers.childFrames(stream, unwrapped)
            val tail = ch.lastOrNull()
            if (tail != null && tail.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                return resolveReceiverTypeOrInitFrame(tail, localVars)
            }
        }
        if (unwrapped.nodeTypeId != KtNodeTypes.REFERENCE_EXPRESSION_ID) return null
        val name = FrameAstHelpers.directIdentPacket(stream, unwrapped)?.unescapedName ?: return null
        localVars[name]?.typeOrInitFrame?.let { return it }
        val classProp = enclosingClass?.let { findClassHierarchyProperty(it, name)?.second }
        val topProp = classProp ?: bundle.symbols.propertiesByName[name]
        val frameIdx = topProp?.returnTypeFrameIndex ?: topProp?.initializerFrameIndex
        return frameIdx?.let { stream.frames[it] }
    }

    private fun inferExprTypeInfo(
        exprFrame: PacketFrame?,
        localVars: Map<String, LambdaVarTypeInfo>,
    ): LambdaVarTypeInfo? {
        if (exprFrame == null) return null
        val unwrapped = unwrapTransparentFrame(exprFrame)
        when (unwrapped.nodeTypeId) {
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID,
            -> {
                val ch = FrameAstHelpers.childFrames(stream, unwrapped)
                if (ch.size >= 2) {
                    val recv = ch[0]
                    val sel = ch.last()
                    if (sel.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                        val callee = extractCalleeSimpleName(sel)
                        if (callee == "also" || callee == "apply") {
                            return inferExprTypeInfo(recv, localVars)
                        }
                        if (callee in ELEMENT_RETURNING_CONTAINER_METHODS) {
                            val containerFrame = resolveReceiverTypeOrInitFrame(recv, localVars)
                            val elemProj = containerFrame?.let { extractFirstContainerElementProjection(it) }
                            if (elemProj != null) {
                                val elemSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, elemProj)
                                val elemCpp = bundle.typeTranslator.translateTypeFrame(elemProj, CppTypePosition.VALUE)
                                val nullableCpp = wrapNullableCppType(elemCpp)
                                return LambdaVarTypeInfo(
                                    simpleName = elemSimple,
                                    cppType = nullableCpp,
                                    nonNullCppType = elemCpp,
                                    typeOrInitFrame = elemProj,
                                )
                            }
                        }
                    }
                }
            }
            KtNodeTypes.CALL_EXPRESSION_ID -> {
                val callee = extractCalleeSimpleName(unwrapped)
                val eff = bundle.symbols.importAliases[callee] ?: callee
                val cls = lookupAccessibleClassByName(callee) ?: lookupAccessibleClassByName(eff)
                if (cls != null) {
                    val cppClassBase = resolveSameFileClassCppTypeName(cls)
                    val explicitTArgs =
                        FrameAstHelpers
                            .firstChildFrame(stream, unwrapped, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
                            ?.let { taList ->
                                FrameAstHelpers
                                    .childFramesOfType(stream, taList, KtNodeTypes.TYPE_PROJECTION_ID)
                                    .map { translateType(it, CppTypePosition.TYPE_ARGUMENT) }
                            }.orEmpty()
                    val fullBase =
                        if (explicitTArgs.isNotEmpty()) {
                            "$cppClassBase<${explicitTArgs.joinToString(", ")}>"
                        } else {
                            cppClassBase
                        }
                    val cppType = wrapClassCppValueType(eff, fullBase)
                    return LambdaVarTypeInfo(
                        simpleName = eff,
                        cppType = cppType,
                        nonNullCppType = cppType,
                        typeOrInitFrame = unwrapped,
                    )
                }
            }
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val name = FrameAstHelpers.directIdentPacket(stream, unwrapped)?.unescapedName
                if (name != null) {
                    localVars[name]?.let { return it }
                    val propTypeIdx = enclosingClass?.let { findClassHierarchyPropertyTypeFrameIndex(it, name) }
                    if (propTypeIdx != null) {
                        val tf = stream.frames[propTypeIdx]
                        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf)
                        val cpp = translateType(tf, CppTypePosition.VALUE)
                        return LambdaVarTypeInfo(simpleName = simple, cppType = cpp, nonNullCppType = cpp, typeOrInitFrame = tf)
                    }
                    val outerSimple = resolveLocalTypeSimpleName(name)
                    if (outerSimple != null) {
                        val cpp = resolveSimpleNameToValueCppType(outerSimple)
                        if (cpp != null) {
                            return LambdaVarTypeInfo(simpleName = outerSimple, cppType = cpp, nonNullCppType = cpp, typeOrInitFrame = null)
                        }
                    }
                }
            }
            KtNodeTypes.IF_ID -> {
                val cond =
                    FrameAstHelpers
                        .firstChildFrame(stream, unwrapped, KtNodeTypes.CONDITION_ID)
                        ?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val thenBr =
                    FrameAstHelpers
                        .firstChildFrame(stream, unwrapped, KtNodeTypes.THEN_ID)
                        ?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val elseBr =
                    FrameAstHelpers
                        .firstChildFrame(stream, unwrapped, KtNodeTypes.ELSE_ID)
                        ?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val thenDiverges = thenBr != null && doesBranchAlwaysTransferControl(thenBr)
                val elseDiverges = elseBr != null && doesBranchAlwaysTransferControl(elseBr)
                val nullCheck = cond?.let { extractNullCheckVarName(it) }
                if (thenDiverges && !elseDiverges && elseBr != null) {
                    val narrowed = localVars.toMutableMap()
                    if (nullCheck != null && nullCheck.second) {
                        narrowed[nullCheck.first]?.let { existing ->
                            if (existing.nonNullCppType != null) {
                                narrowed[nullCheck.first] = existing.copy(cppType = existing.nonNullCppType)
                            }
                        }
                    }
                    val elseTail = extractBranchTailExpr(elseBr) ?: elseBr
                    return inferExprTypeInfo(elseTail, narrowed)
                }
                if (elseDiverges && !thenDiverges && thenBr != null) {
                    val narrowed = localVars.toMutableMap()
                    if (nullCheck != null && !nullCheck.second) {
                        narrowed[nullCheck.first]?.let { existing ->
                            if (existing.nonNullCppType != null) {
                                narrowed[nullCheck.first] = existing.copy(cppType = existing.nonNullCppType)
                            }
                        }
                    }
                    val thenTail = extractBranchTailExpr(thenBr) ?: thenBr
                    return inferExprTypeInfo(thenTail, narrowed)
                }
                val thenTail = thenBr?.let { extractBranchTailExpr(it) ?: it }
                val elseTail = elseBr?.let { extractBranchTailExpr(it) ?: it }
                val thenIsNull = thenTail != null && unwrapTransparentFrame(thenTail).nodeTypeId == KtNodeTypes.NULL_ID
                val elseIsNull = elseTail != null && unwrapTransparentFrame(elseTail).nodeTypeId == KtNodeTypes.NULL_ID
                if (thenIsNull && elseTail != null) {
                    val elseInfo = inferExprTypeInfo(elseTail, localVars) ?: return null
                    val wrappedCpp = elseInfo.cppType?.let { wrapNullableCppType(it) }
                    return elseInfo.copy(cppType = wrappedCpp)
                }
                if (elseIsNull && thenTail != null) {
                    val thenInfo = inferExprTypeInfo(thenTail, localVars) ?: return null
                    val wrappedCpp = thenInfo.cppType?.let { wrapNullableCppType(it) }
                    return thenInfo.copy(cppType = wrappedCpp)
                }
                return thenTail?.let { inferExprTypeInfo(it, localVars) }
                    ?: elseTail?.let { inferExprTypeInfo(it, localVars) }
            }
        }
        val simple = inferExpressionSimpleTypeName(unwrapped) ?: return null
        val cpp = resolveSimpleNameToValueCppType(simple) ?: return null
        return LambdaVarTypeInfo(simpleName = simple, cppType = cpp, nonNullCppType = cpp, typeOrInitFrame = unwrapped)
    }

    private fun inferInlineLambdaTailType(lambdaFrameIndex: Int): InlineTailTypeResult? {
        val body = extractLambdaBodyFrame(lambdaFrameIndex) ?: return null
        val children = FrameAstHelpers.childFrames(stream, body)
        if (children.isEmpty()) return null

        val localVars = HashMap<String, LambdaVarTypeInfo>()
        for (i in 0 until children.size - 1) {
            val stmt = unwrapTransparentFrame(children[i])
            if (stmt.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                val pIdent = FrameAstHelpers.directIdentPacket(stream, stmt)?.unescapedName ?: continue
                val pTypeFrame = FrameAstHelpers.firstChildFrame(stream, stmt, KtNodeTypes.TYPE_REFERENCE_ID)
                val pInit = FrameAstHelpers.extractInitializerAfterEquals(stream, stmt)
                val info =
                    if (pTypeFrame != null) {
                        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, pTypeFrame)
                        val cpp = translateType(pTypeFrame, CppTypePosition.VALUE)
                        LambdaVarTypeInfo(simpleName = simple, cppType = cpp, nonNullCppType = cpp, typeOrInitFrame = pTypeFrame)
                    } else {
                        inferExprTypeInfo(pInit, localVars)
                    }
                if (info != null) localVars[pIdent] = info
            } else if (stmt.nodeTypeId == KtNodeTypes.IF_ID) {
                val cond =
                    FrameAstHelpers
                        .firstChildFrame(stream, stmt, KtNodeTypes.CONDITION_ID)
                        ?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val thenBr =
                    FrameAstHelpers
                        .firstChildFrame(stream, stmt, KtNodeTypes.THEN_ID)
                        ?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val elseBr = FrameAstHelpers.firstChildFrame(stream, stmt, KtNodeTypes.ELSE_ID)
                val nullCheck = cond?.let { extractNullCheckVarName(it) }
                if (nullCheck != null && nullCheck.second && elseBr == null &&
                    thenBr != null && doesBranchAlwaysTransferControl(thenBr)
                ) {
                    localVars[nullCheck.first]?.let { existing ->
                        if (existing.nonNullCppType != null) {
                            localVars[nullCheck.first] = existing.copy(cppType = existing.nonNullCppType)
                        }
                    }
                }
            }
        }

        val tailExpr = extractBranchTailExpr(body) ?: children.lastOrNull() ?: return null
        val tailInfo = inferExprTypeInfo(tailExpr, localVars) ?: return null
        if (tailInfo.cppType == null && tailInfo.simpleName == null) return null
        return InlineTailTypeResult(simpleName = tailInfo.simpleName, cppType = tailInfo.cppType)
    }

    private fun isInherentlyMutableLocalVal(
        declaredSimpleType: String?,
        cppType: String,
        initFrame: PacketFrame?,
    ): Boolean {
        val simple = declaredSimpleType ?: initFrame?.let { inferExpressionSimpleTypeName(it) }
        val effSimple = simple?.let { bundle.symbols.importAliases[it] ?: it }
        if (effSimple != null && effSimple in INHERENTLY_MUTABLE_VAL_TYPES) return true
        val trimmedCpp = cppType.trim()
        if (trimmedCpp.startsWith("std::vector<") ||
            trimmedCpp.startsWith("std::unordered_set<") ||
            trimmedCpp.startsWith("std::set<") ||
            trimmedCpp.startsWith("std::unordered_map<") ||
            trimmedCpp.startsWith("std::map<") ||
            trimmedCpp.startsWith("std::deque<") ||
            trimmedCpp.startsWith("ThreadSafeHeap<") ||
            trimmedCpp == "KxsSynchronizedObject" ||
            trimmedCpp == "std::mutex" ||
            trimmedCpp == "std::recursive_mutex"
        ) {
            return true
        }
        val unwrappedInit = initFrame?.let { unwrapTransparentFrame(it) } ?: return false
        val callExpr =
            when (unwrappedInit.nodeTypeId) {
                KtNodeTypes.CALL_EXPRESSION_ID -> unwrappedInit
                KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID ->
                    FrameAstHelpers
                        .childFrames(stream, unwrappedInit)
                        .lastOrNull()
                        ?.takeIf { it.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID }
                else -> null
            } ?: return false
        val callee = extractCalleeSimpleName(callExpr)
        return callee in INHERENTLY_MUTABLE_FACTORY_NAMES
    }

    private fun inferChannelFactoryElementTypeArg(targetCpp: String): List<String> =
        listOf("Channel", "SendChannel", "ReceiveChannel")
            .firstNotNullOfOrNull { base ->
                val rawArgs =
                    extractOuterTemplateArgsForBase(targetCpp, base)
                        ?: extractOuterTemplateArgsForBase(targetCpp, "kotlinx::coroutines::channels::$base")
                rawArgs
                    ?.firstOrNull()
                    ?.let { listOf(it) }
            }.orEmpty()

    private fun frameContainsLabeledThis(root: PacketFrame): Boolean {
        if (root.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID &&
            FrameAstHelpers.firstChildFrame(stream, root, KtNodeTypes.LABEL_QUALIFIER_ID) != null
        ) {
            return true
        }
        return FrameAstHelpers.childFrames(stream, root).any { frameContainsLabeledThis(it) }
    }

    private fun frameContainsNodeType(
        root: PacketFrame,
        nodeTypeId: Int,
    ): Boolean {
        if (root.nodeTypeId == nodeTypeId) return true
        return FrameAstHelpers.childFrames(stream, root).any { frameContainsNodeType(it, nodeTypeId) }
    }

    private fun needsOuterThisCapture(
        outerCls: KtClassSymbol,
        superList: PacketFrame?,
        classBody: PacketFrame?,
        capturedLocalKeys: Set<String>,
        ownMethodNames: Set<String>,
        ownPropNames: Set<String>,
    ): Boolean {
        if (superList != null && frameContainsNodeType(superList, KtNodeTypes.THIS_EXPRESSION_ID)) {
            return true
        }
        if (classBody != null && frameContainsLabeledThis(classBody)) {
            return true
        }
        val scanRoots = listOfNotNull(superList, classBody)
        for (root in scanRoots) {
            if (root.firstPacketIndex > root.lastPacketIndex) continue
            for (pktIdx in root.firstPacketIndex..root.lastPacketIndex) {
                val pkt = stream.packets[pktIdx] as? SyntaxPacket.IdentPacket ?: continue
                val name = pkt.unescapedName
                if (name in capturedLocalKeys || name in ownMethodNames || name in ownPropNames) continue
                if (findClassHierarchyProperty(outerCls, name) != null || classDeclaresMethod(outerCls, name)) {
                    return true
                }
            }
        }
        return false
    }

    private fun emitObjectLiteralExpr(objFrame: PacketFrame): String {
        val objDecl = FrameAstHelpers.firstChildFrame(stream, objFrame, KtNodeTypes.OBJECT_DECLARATION_ID) ?: objFrame
        val superList = FrameAstHelpers.firstChildFrame(stream, objDecl, KtNodeTypes.SUPER_TYPE_LIST_ID)
        val classBody = FrameAstHelpers.firstChildFrame(stream, objDecl, KtNodeTypes.CLASS_BODY_ID)

        val ownMethodNames = LinkedHashSet<String>()
        val ownPropNames = LinkedHashSet<String>()
        if (classBody != null) {
            for (member in FrameAstHelpers.childFrames(stream, classBody)) {
                val name = FrameAstHelpers.directIdentPacket(stream, member)?.unescapedName ?: continue
                if (member.nodeTypeId == KtNodeTypes.FUN_ID) {
                    ownMethodNames.add(name)
                } else if (member.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                    ownPropNames.add(name)
                }
            }
        }

        val capturedLocals = LinkedHashMap<String, String>()
        for (scanRoot in listOfNotNull(superList, classBody)) {
            if (scanRoot.firstPacketIndex <= scanRoot.lastPacketIndex) {
                for (pktIdx in scanRoot.firstPacketIndex..scanRoot.lastPacketIndex) {
                    val pkt = stream.packets[pktIdx] as? SyntaxPacket.IdentPacket ?: continue
                    val ktCap = pkt.unescapedName
                    if (ktCap == "this" || ktCap == "this_val" || ktCap in capturedLocals) continue
                    val bound = resolveBoundName(ktCap)
                    if (bound != null && bound.all { it.isLetterOrDigit() || it == '_' }) {
                        capturedLocals[ktCap] = bound
                    }
                }
            }
        }

        val outerCls =
            enclosingClass
                ?: if (receiverSubstitution == "this->kxs_outer_" || receiverSubstitution == "kxs_outer_in") {
                    extensionReceiverClass
                } else {
                    null
                }
        val canCaptureOuterThis =
            outerCls != null &&
                outerCls.kind != KtClassKind.COMPANION_OBJECT &&
                enclosingFunction?.annotations?.any { it.simpleName == "JvmStatic" } != true
        val captureOuterThis =
            canCaptureOuterThis &&
                needsOuterThisCapture(
                    outerCls = outerCls,
                    superList = superList,
                    classBody = classBody,
                    capturedLocalKeys = capturedLocals.keys,
                    ownMethodNames = ownMethodNames,
                    ownPropNames = ownPropNames,
                )
        val outerPassExpr =
            when {
                enclosingClass != null -> "this"
                receiverSubstitution == "this->kxs_outer_" -> "this->kxs_outer_"
                else -> "kxs_outer_in"
            }

        val superCtorEmitter =
            CppExprAndStmtEmitter(
                bundle = bundle,
                activeTypeParams = activeTypeParams,
                receiverSubstitution = if (captureOuterThis) "kxs_outer_in" else null,
                extensionReceiverClass = if (captureOuterThis) outerCls else null,
                extensionReceiverIsPointer = captureOuterThis,
                extensionReceiverSimpleName = if (captureOuterThis) outerCls.kotlinName else null,
                localPolymorphicClasses = localPolymorphicClasses,
                localExtensionFunctions = localExtensionFunctions,
            )
        for ((ktCap, cppCap) in capturedLocals) {
            superCtorEmitter.bindLocalName(ktCap, "${cppCap}_in")
            copyLocalMetadataTo(superCtorEmitter, ktCap)
        }

        val superBases = ArrayList<String>()
        val superCtorInits = ArrayList<String>()
        if (superList != null) {
            for (entry in FrameAstHelpers.childFrames(stream, superList)) {
                val tr = FrameAstHelpers.findDescendantFrame(stream, entry, KtNodeTypes.TYPE_REFERENCE_ID) ?: continue
                val baseType = bundle.typeTranslator.translateTypeFrame(tr, CppTypePosition.TYPE_ARGUMENT)
                superBases.add(baseType)
                if (entry.nodeTypeId == KtNodeTypes.SUPER_TYPE_CALL_ENTRY_ID) {
                    val argList = FrameAstHelpers.findDescendantFrame(stream, entry, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
                    val args =
                        argList
                            ?.let { al ->
                                FrameAstHelpers
                                    .childFramesOfType(stream, al, KtNodeTypes.VALUE_ARGUMENT_ID)
                                    .mapNotNull { va ->
                                        FrameAstHelpers
                                            .childFrames(stream, va)
                                            .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                                            ?.let { superCtorEmitter.emitExpression(it) }
                                    }
                            }.orEmpty()
                    val finalArgs =
                        if (baseType.endsWith("AbstractCoroutineContextElement") ||
                            baseType.contains("AbstractCoroutineContextKey")
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
                    superCtorInits.add("$baseType(${finalArgs.joinToString(", ")})")
                }
            }
        }

        val anonName = freshTemp("KxsAnonObject")
        val superClause = if (superBases.isNotEmpty()) " : ${superBases.joinToString(", ") { "public $it" }}" else ""
        includes.addSystem("memory")
        includes.addSystem("type_traits")
        includes.addSystem("utility")

        return buildString {
            append("([&]() {\n")
            if (captureOuterThis) {
                append("    using KxsOuterPtr = decltype(").append(outerPassExpr).append(");\n")
            }
            append("    struct ").append(anonName).append(superClause).append(" {\n")
            if (captureOuterThis) {
                append("        KxsOuterPtr kxs_outer_;\n")
            }
            for ((_, capCpp) in capturedLocals) {
                append("        std::decay_t<decltype($capCpp)> ${capCpp}_cap;\n")
            }
            if (captureOuterThis || capturedLocals.isNotEmpty() || superCtorInits.isNotEmpty()) {
                val ctorParams =
                    buildList {
                        if (captureOuterThis) add("KxsOuterPtr kxs_outer_in")
                        for (capCpp in capturedLocals.values) {
                            add("std::decay_t<decltype($capCpp)> ${capCpp}_in")
                        }
                    }
                val inits = ArrayList<String>(superCtorInits.size + capturedLocals.size + 1)
                inits.addAll(superCtorInits)
                if (captureOuterThis) {
                    inits.add("kxs_outer_(kxs_outer_in)")
                }
                for (capCpp in capturedLocals.values) {
                    inits.add("${capCpp}_cap(std::move(${capCpp}_in))")
                }
                val explicitKw = if (ctorParams.size == 1) "explicit " else ""
                append("        ")
                    .append(explicitKw)
                    .append(anonName)
                    .append('(')
                    .append(ctorParams.joinToString(", "))
                    .append(')')
                if (inits.isNotEmpty()) {
                    append(" : ").append(inits.joinToString(", "))
                }
                append(" {}\n")
            }
            if (classBody != null) {
                val ownPropBindings = LinkedHashMap<String, String>()
                val deferredFunMembers = ArrayList<PacketFrame>()
                for (member in FrameAstHelpers.childFrames(stream, classBody)) {
                    if (member.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                        val pIdent = FrameAstHelpers.directIdentPacket(stream, member)?.unescapedName ?: "prop"
                        val pCpp = CppNamingPolicy.toSnakeCase(pIdent)
                        val (_, pTypeFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, member)
                        val getterAcc =
                            FrameAstHelpers
                                .childFramesOfType(stream, member, KtNodeTypes.PROPERTY_ACCESSOR_ID)
                                .firstOrNull { FrameAstHelpers.hasDirectKeyword(stream, it, KtTokens.GET_KEYWORD_ID) }
                        val pInit = FrameAstHelpers.extractInitializerAfterEquals(stream, member)
                        val isOvProp =
                            PacketFlags.hasFlag(member.flags, PacketFlags.MOD_OVERRIDE) && superBases.isNotEmpty()
                        val getterBlock = getterAcc?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.BLOCK_ID) }
                        val getterExpr =
                            if (getterAcc != null && getterBlock == null) {
                                FrameAstHelpers.extractInitializerAfterEquals(stream, getterAcc)
                            } else {
                                null
                            }
                        if (getterAcc != null || isOvProp) {
                            val isContContext =
                                pIdent == "context" && superBases.any { it.contains("Continuation") }
                            val getterMethodName = if (isContContext) "get_context" else pCpp
                            val retT =
                                pTypeFrame?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.RETURN) }
                                    ?: inferExprTypeInfo(getterExpr ?: pInit, emptyMap())?.cppType
                                    ?: if (isOvProp) {
                                        includes.addSystem("any")
                                        "std::any"
                                    } else {
                                        "auto"
                                    }
                            val pEmitter =
                                CppExprAndStmtEmitter(
                                    bundle = bundle,
                                    activeTypeParams = activeTypeParams,
                                    enclosingFunctionReturnType = retT.takeIf { it != "auto" },
                                    receiverSubstitution = if (captureOuterThis) "this->kxs_outer_" else null,
                                    extensionReceiverClass = if (captureOuterThis) outerCls else null,
                                    extensionReceiverIsPointer = captureOuterThis,
                                    extensionReceiverSimpleName = if (captureOuterThis) outerCls.kotlinName else null,
                                    localPolymorphicClasses = localPolymorphicClasses,
                                    localExtensionFunctions = localExtensionFunctions,
                                    anonObjectOwnMethods = ownMethodNames,
                                    anonObjectOwnProps = ownPropNames,
                                )
                            for ((ktCap, cppCap) in capturedLocals) {
                                pEmitter.bindLocalName(ktCap, "this->${cppCap}_cap")
                                copyLocalMetadataTo(pEmitter, ktCap)
                            }
                            for ((k, v) in ownPropBindings) pEmitter.bindLocalName(k, v)
                            val bodyF = getterBlock ?: getterExpr ?: pInit
                            val bodyCode =
                                if (bodyF != null) {
                                    pEmitter.emitBlockOrExprBody(bodyF, getterBlock == null, isVoidReturn = false, indent = "        ")
                                } else {
                                    "{}"
                                }
                            val ovSuffix = if (isOvProp) " override" else ""
                            append("        ")
                                .append(retT)
                                .append(' ')
                                .append(getterMethodName)
                                .append("()")
                                .append(ovSuffix)
                                .append(' ')
                                .append(bodyCode)
                                .append('\n')
                            ownPropBindings[pIdent] = "this->$getterMethodName()"
                        } else {
                            val pType =
                                pTypeFrame?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.VALUE) }
                                    ?: inferExprTypeInfo(pInit, emptyMap())?.cppType
                                    ?: run {
                                        includes.addSystem("any")
                                        "std::any"
                                    }
                            val pEmitter =
                                CppExprAndStmtEmitter(
                                    bundle = bundle,
                                    activeTypeParams = activeTypeParams,
                                    receiverSubstitution = if (captureOuterThis) "this->kxs_outer_" else null,
                                    extensionReceiverClass = if (captureOuterThis) outerCls else null,
                                    extensionReceiverIsPointer = captureOuterThis,
                                    extensionReceiverSimpleName = if (captureOuterThis) outerCls.kotlinName else null,
                                    localPolymorphicClasses = localPolymorphicClasses,
                                    localExtensionFunctions = localExtensionFunctions,
                                    anonObjectOwnMethods = ownMethodNames,
                                    anonObjectOwnProps = ownPropNames,
                                )
                            for ((ktCap, cppCap) in capturedLocals) {
                                pEmitter.bindLocalName(ktCap, "this->${cppCap}_cap")
                                copyLocalMetadataTo(pEmitter, ktCap)
                            }
                            for ((k, v) in ownPropBindings) pEmitter.bindLocalName(k, v)
                            val initExpr = pInit?.let { pEmitter.emitExpression(it) }
                            if (initExpr != null) {
                                append("        ")
                                    .append(pType)
                                    .append(' ')
                                    .append(pCpp)
                                    .append(" = ")
                                    .append(initExpr)
                                    .append(";\n")
                            } else {
                                append("        ")
                                    .append(pType)
                                    .append(' ')
                                    .append(pCpp)
                                    .append("{};\n")
                            }
                            ownPropBindings[pIdent] = "this->$pCpp"
                        }
                    } else if (member.nodeTypeId == KtNodeTypes.FUN_ID) {
                        deferredFunMembers.add(member)
                    }
                }
                for (member in deferredFunMembers) {
                    val mIdent = FrameAstHelpers.directIdentPacket(stream, member)?.unescapedName ?: "member_fn"
                    val mCpp = CppNamingPolicy.toSnakeCase(mIdent)
                    val (_, retFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, member)
                    val retT =
                        retFrame?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.RETURN) } ?: "void"
                    val isVoidM = retT == "void" || retT == "[[noreturn]] void"
                    val pList = FrameAstHelpers.firstChildFrame(stream, member, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
                    val vps = FrameAstHelpers.extractValueParams(stream, pList)
                    val mEmitter =
                        CppExprAndStmtEmitter(
                            bundle = bundle,
                            activeTypeParams = activeTypeParams,
                            isVoidReturnContext = isVoidM,
                            enclosingFunctionReturnType = retT.takeIf { it != "auto" && !isVoidM },
                            receiverSubstitution = if (captureOuterThis) "this->kxs_outer_" else null,
                            extensionReceiverClass = if (captureOuterThis) outerCls else null,
                            extensionReceiverIsPointer = captureOuterThis,
                            extensionReceiverSimpleName = if (captureOuterThis) outerCls.kotlinName else null,
                            localPolymorphicClasses = localPolymorphicClasses,
                            localExtensionFunctions = localExtensionFunctions,
                            anonObjectOwnMethods = ownMethodNames,
                            anonObjectOwnProps = ownPropNames,
                        )
                    for ((ktCap, cppCap) in capturedLocals) {
                        mEmitter.bindLocalName(ktCap, "this->${cppCap}_cap")
                        copyLocalMetadataTo(mEmitter, ktCap)
                    }
                    for ((k, v) in ownPropBindings) {
                        mEmitter.bindLocalName(k, v)
                    }
                    for (vp in vps) {
                        mEmitter.bindValueParam(vp)
                    }
                    val isZeroArgAnyMethod = (mIdent == "toString" || mIdent == "hashCode") && vps.isEmpty()
                    val isOv =
                        PacketFlags.hasFlag(member.flags, PacketFlags.MOD_OVERRIDE) &&
                            superBases.isNotEmpty() &&
                            (
                                !isZeroArgAnyMethod ||
                                    superBases.any { b -> KNOWN_TO_STRING_SUPER_FRAGMENTS.any { frag -> b.contains(frag) } }
                            )
                    val blockB = FrameAstHelpers.firstChildFrame(stream, member, KtNodeTypes.BLOCK_ID)
                    val exprB = if (blockB == null) FrameAstHelpers.extractInitializerAfterEquals(stream, member) else null
                    val bodyF = blockB ?: exprB
                    val bodyCode =
                        if (bodyF != null) {
                            mEmitter.emitBlockOrExprBody(bodyF, exprB != null, isVoidM, "        ")
                        } else {
                            "{}"
                        }
                    val renderedParams = renderClassLikeMethodParams(vps, bodyCode)
                    append("        ")
                        .append(retT)
                        .append(' ')
                        .append(mCpp)
                        .append('(')
                        .append(renderedParams)
                        .append(')')
                    if (isZeroArgAnyMethod) append(" const")
                    if (isOv) append(" override")
                    append(' ').append(bodyCode).append('\n')
                }
            }
            append("    };\n")
            val makeArgs =
                buildList {
                    if (captureOuterThis) add(outerPassExpr)
                    addAll(capturedLocals.values)
                }.joinToString(", ")
            append("    return std::make_shared<$anonName>($makeArgs);\n")
            append("}())")
        }
    }

    private fun emitConstantLiteral(litFrame: PacketFrame): String {
        val pkt =
            (litFrame.firstPacketIndex..litFrame.lastPacketIndex)
                .asSequence()
                .mapNotNull { stream.packets.getOrNull(it) as? SyntaxPacket.LiteralPacket }
                .firstOrNull()
                ?: throw IllegalArgumentException(
                    "${stream.sourcePath}:${litFrame.span.startLine}:${litFrame.span.startColumn}: missing LiteralPacket",
                )
        return when (pkt.literalKind) {
            LiteralPacketKind.NULL -> "nullptr"
            LiteralPacketKind.BOOLEAN -> pkt.rawText
            LiteralPacketKind.CHAR -> "u${pkt.rawText}"
            LiteralPacketKind.FLOAT -> {
                val cleaned = pkt.rawText.replace("_", "")
                if (cleaned.endsWith('f') || cleaned.endsWith('F')) {
                    cleaned.dropLast(1) + "f"
                } else {
                    cleaned
                }
            }
            LiteralPacketKind.INT -> {
                var body = pkt.rawText.replace("_", "")
                var suffix = ""
                if (body.endsWith("uL", ignoreCase = true) || body.endsWith("ul", ignoreCase = true)) {
                    body = body.dropLast(2)
                    suffix = "ULL"
                } else if (body.endsWith('L') || body.endsWith('l')) {
                    body = body.dropLast(1)
                    suffix = "LL"
                } else if (body.endsWith('U') || body.endsWith('u')) {
                    body = body.dropLast(1)
                    suffix = "U"
                }
                body + suffix
            }
        }
    }

    private fun emitStringTemplate(strFrame: PacketFrame): String {
        includes.addSystem("string")
        val isRaw = PacketFlags.hasFlag(strFrame.flags, PacketFlags.RAW_MULTILINE_STRING)
        val isInterpolated = PacketFlags.hasFlag(strFrame.flags, PacketFlags.INTERPOLATED_STRING)
        val hasLoneSurrogate = PacketFlags.hasFlag(strFrame.flags, PacketFlags.HAS_LONE_SURROGATE)

        if (!isInterpolated && !hasLoneSurrogate) {
            val constantText = FrameAstHelpers.extractConstantStringText(stream, strFrame)
            return "std::string(${escapeCppStringLiteral(constantText)})"
        }

        // Interpolated or surrogate-containing string template: assemble segments deterministically in order!
        val parts = ArrayList<String>()
        val litBuffer = StringBuilder()

        fun flushLiteral() {
            if (litBuffer.isNotEmpty()) {
                parts.add("std::string(${escapeCppStringLiteral(litBuffer.toString())})")
                litBuffer.setLength(0)
            }
        }

        for (i in 0 until strFrame.childCount) {
            val ref = strFrame.childRefAt(i)
            if (ref < 0) {
                val pkt = stream.packets[-(ref + 1)]
                if (pkt is SyntaxPacket.StringSegmentPacket) {
                    when (pkt.segmentKind) {
                        StringSegmentKind.LITERAL_PART -> litBuffer.append(pkt.rawText)
                        StringSegmentKind.ESCAPE_SEQ -> litBuffer.append(decodeKotlinEscapeToCharOrText(pkt.rawText))
                        else -> Unit
                    }
                }
            } else {
                val entry = stream.frames[ref]
                when (entry.nodeTypeId) {
                    KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY_ID -> {
                        val rawPart = extractStringSegmentRaw(entry, StringSegmentKind.LITERAL_PART)
                        if (PacketFlags.hasFlag(entry.flags, PacketFlags.HAS_LONE_SURROGATE)) {
                            flushLiteral()
                            parts.add(encodeSurrogateSafeCppString(rawPart))
                        } else {
                            litBuffer.append(rawPart)
                        }
                    }
                    KtNodeTypes.ESCAPE_STRING_TEMPLATE_ENTRY_ID -> {
                        val rawEsc = extractStringSegmentRaw(entry, StringSegmentKind.ESCAPE_SEQ)
                        if (PacketFlags.hasFlag(entry.flags, PacketFlags.HAS_LONE_SURROGATE) &&
                            rawEsc.startsWith("\\u") && rawEsc.length == 6
                        ) {
                            flushLiteral()
                            val codeUnit = rawEsc.substring(2).toInt(16)
                            parts.add(encodeSingleUtf16UnitForCpp(codeUnit))
                        } else {
                            litBuffer.append(decodeKotlinEscapeToCharOrText(rawEsc))
                        }
                    }
                    KtNodeTypes.SHORT_STRING_TEMPLATE_ENTRY_ID,
                    KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY_ID,
                    -> {
                        flushLiteral()
                        val expr = FrameAstHelpers.childFrames(stream, entry).firstOrNull()
                        if (expr != null) {
                            parts.add(wrapForStringConcatenation(expr))
                        }
                    }
                    else -> Unit
                }
            }
        }
        flushLiteral()
        if (isRaw && parts.size == 1) {
            return parts[0]
        }
        return if (parts.isEmpty()) "std::string(\"\")" else parts.joinToString(" + ")
    }

    private fun wrapForStringConcatenation(exprFrame: PacketFrame): String {
        val code = emitExpression(exprFrame)
        if (exprFrame.nodeTypeId == KtNodeTypes.STRING_TEMPLATE_ID) return code
        if (exprFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val name = FrameAstHelpers.directIdentPacket(stream, exprFrame)?.unescapedName
            if (name != null && isKnownStringSymbol(name)) return code
        }
        // Use C++23 constexpr-dispatched formatter helper via inline lambda so both `std::string` and numeric scalars work!
        includes.addSystem("string")
        includes.addSystem("type_traits")
        return "([&](const auto& kxs_v) { " +
            "if constexpr (std::is_convertible_v<decltype(kxs_v), std::string>) return std::string(kxs_v); " +
            "else if constexpr (requires { std::to_string(kxs_v); }) return std::to_string(kxs_v); " +
            "else if constexpr (requires { kxs_v.to_string(); }) return kxs_v.to_string(); " +
            "else if constexpr (requires { kxs_v->to_string(); }) return kxs_v ? kxs_v->to_string() : std::string(\"null\"); " +
            "else return std::string(\"<object>\"); }($code))"
    }

    private fun extractStringSegmentRaw(
        entryFrame: PacketFrame,
        expectedKind: StringSegmentKind,
    ): String {
        if (entryFrame.firstPacketIndex > entryFrame.lastPacketIndex || stream.packets.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for (idx in entryFrame.firstPacketIndex..entryFrame.lastPacketIndex) {
            val pkt = stream.packets[idx]
            if (pkt is SyntaxPacket.StringSegmentPacket && pkt.segmentKind == expectedKind) {
                sb.append(pkt.rawText)
            }
        }
        return sb.toString()
    }

    private fun isKnownStringSymbol(kotlinName: String): Boolean {
        if (enclosingClass != null) {
            val pMatch = enclosingClass.primaryConstructorParams.firstOrNull { it.name == kotlinName }
            if (pMatch?.typeFrameIndex != null) {
                val tf = stream.frames[pMatch.typeFrameIndex]
                if (FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf) == "String" &&
                    !FrameAstHelpers.isNullableTypeFrame(stream, tf)
                ) {
                    return true
                }
            }
        }
        val prop = bundle.symbols.propertiesByName[kotlinName]
        if (prop?.returnTypeFrameIndex != null) {
            val tf = stream.frames[prop.returnTypeFrameIndex]
            if (FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf) == "String" &&
                !FrameAstHelpers.isNullableTypeFrame(stream, tf)
            ) {
                return true
            }
        }
        return false
    }

    private fun encodeSurrogateSafeCppString(raw: String): String {
        // Construct via explicit hex code units without embedding unpaired UTF-16 surrogate literals in Kotlin source!
        val units = raw.map { "0x${it.code.toString(16).uppercase()}" }
        return "std::string{${units.joinToString(", ") { "static_cast<char>($it & 0xFF)" }}}"
    }

    private fun encodeSingleUtf16UnitForCpp(codeUnit: Int): String =
        "std::string{static_cast<char>(0x${(codeUnit shr 8).toString(16).uppercase()}), " +
            "static_cast<char>(0x${(codeUnit and 0xFF).toString(16).uppercase()})}"

    private fun decodeKotlinEscapeToCharOrText(rawEsc: String): String =
        when (rawEsc) {
            "\\n" -> "\n"
            "\\r" -> "\r"
            "\\t" -> "\t"
            "\\b" -> "\b"
            "\\\\" -> "\\"
            "\\\"" -> "\""
            "\\'" -> "'"
            "\\$" -> "$"
            else -> {
                if (rawEsc.startsWith("\\u") && rawEsc.length == 6) {
                    val code = rawEsc.substring(2).toIntOrNull(16)
                    if (code != null && code !in 0xD800..0xDFFF) {
                        code.toChar().toString()
                    } else {
                        rawEsc
                    }
                } else {
                    rawEsc
                }
            }
        }

    private fun emitThisExpr(thisFrame: PacketFrame): String {
        val labelFrame = FrameAstHelpers.firstChildFrame(stream, thisFrame, KtNodeTypes.LABEL_QUALIFIER_ID)
        if (labelFrame != null) {
            val labelName = FrameAstHelpers.directIdentPacket(stream, labelFrame)?.unescapedName
            val targetOuterCls =
                enclosingClass
                    ?: if (receiverSubstitution == "this->kxs_outer_" || receiverSubstitution == "kxs_outer_in") {
                        extensionReceiverClass
                    } else {
                        null
                    }
            val basePtr = if (enclosingClass != null) "this" else receiverSubstitution
            if (labelName != null && targetOuterCls != null && basePtr != null) {
                if (targetOuterCls.kotlinName == labelName) {
                    if (!bundle.isDocExample && isSharedThisClass(targetOuterCls.kotlinName)) {
                        includes.needsSharedFromThisDef = true
                        includes.addSystem("memory")
                        includes.addSystem("type_traits")
                        return "kxs_shared_from_this($basePtr)"
                    }
                    return "(*$basePtr)"
                }
                var cur: KtClassSymbol? = targetOuterCls
                var hops = 0
                while (cur != null && cur.isInner) {
                    val parent = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] } ?: break
                    hops += 1
                    if (parent.kotlinName == labelName) {
                        val ptrChain =
                            if (basePtr == "this") {
                                if (hops == 1) "outer_" else "${"outer_->".repeat(hops - 1)}outer_"
                            } else {
                                "$basePtr->${"outer_->".repeat(hops - 1)}outer_"
                            }
                        if (!bundle.isDocExample && isSharedThisClass(parent.kotlinName)) {
                            includes.needsSharedFromThisDef = true
                            includes.addSystem("memory")
                            includes.addSystem("type_traits")
                            return "kxs_shared_from_this($ptrChain)"
                        }
                        return "(*$ptrChain)"
                    }
                    cur = parent
                }
            }
            return "(*this)"
        }
        if (receiverSubstitution == "this->kxs_outer_") {
            return "(*this)"
        }
        if (receiverSubstitution == "kxs_outer_in") {
            val outerExtCls = extensionReceiverClass
            if (outerExtCls != null && !bundle.isDocExample && isSharedThisClass(outerExtCls.kotlinName)) {
                includes.needsSharedFromThisDef = true
                includes.addSystem("memory")
                includes.addSystem("type_traits")
                return "kxs_shared_from_this(kxs_outer_in)"
            }
            return "(*kxs_outer_in)"
        }
        receiverSubstitution?.let { return it }
        if (!bundle.isDocExample &&
            enclosingClass != null &&
            isSharedThisClass(enclosingClass.kotlinName)
        ) {
            includes.needsSharedFromThisDef = true
            includes.addSystem("memory")
            includes.addSystem("type_traits")
            return "kxs_shared_from_this(this)"
        }
        return "(*this)"
    }

    private fun resolveOuterChainPrefix(targetOwnerClassFrameIndex: Int?): String? {
        if (targetOwnerClassFrameIndex == null || enclosingClass == null) return null
        if (targetOwnerClassFrameIndex == enclosingClass.frameIndex) return ""
        var cur: KtClassSymbol? = enclosingClass
        var hops = 0
        while (cur != null && cur.isInner) {
            val parentIdx = cur.enclosingClassFrameIndex ?: break
            hops += 1
            if (parentIdx == targetOwnerClassFrameIndex) {
                return "outer_->".repeat(hops)
            }
            cur = bundle.symbols.classesByFrame[parentIdx]
        }
        return null
    }

    private fun resolveOuterPointerExpr(targetOwnerClassFrameIndex: Int?): String? {
        if (targetOwnerClassFrameIndex == null || enclosingClass == null) return null
        if (targetOwnerClassFrameIndex == enclosingClass.frameIndex) return "this"
        var cur: KtClassSymbol? = enclosingClass
        var hops = 0
        while (cur != null && cur.isInner) {
            val parentIdx = cur.enclosingClassFrameIndex ?: break
            hops += 1
            if (parentIdx == targetOwnerClassFrameIndex) {
                return if (hops == 1) "outer_" else "${"outer_->".repeat(hops - 1)}outer_"
            }
            cur = bundle.symbols.classesByFrame[parentIdx]
        }
        return null
    }

    private fun lookupAccessibleClassByName(kotlinName: String): KtClassSymbol? {
        var cur: KtClassSymbol? = enclosingClass ?: extensionReceiverClass
        while (cur != null) {
            for (nestedIdx in cur.memberClassFrameIndices) {
                val sub = bundle.symbols.classesByFrame[nestedIdx]
                if (sub != null && sub.kotlinName == kotlinName) {
                    return sub
                }
            }
            cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        return bundle.symbols.classesByName[kotlinName]
    }

    private fun emitReferenceExpr(refFrame: PacketFrame): String {
        val pkt =
            FrameAstHelpers.directIdentPacket(stream, refFrame)
                ?: throw IllegalArgumentException(
                    "${stream.sourcePath}:${refFrame.span.startLine}:${refFrame.span.startColumn}: missing IdentPacket in REFERENCE_EXPRESSION",
                )
        val ktName = pkt.unescapedName

        resolveBoundName(ktName)?.let { return it }
        if (ktName == "this" || ktName == "this_val") {
            if (receiverSubstitution == "this->kxs_outer_") return "(*this)"
            receiverSubstitution?.let { return it }
            if (enclosingClass != null) {
                return "(*this)"
            }
        }

        // Check if `ktName` is an enum entry, private field, or property on `enclosingClass` or an `inner` outer class
        resolveUnqualifiedPropertyAccess(ktName)?.let { return it }

        if (!bundle.isDocExample &&
            enclosingClass != null &&
            (ktName == "classSimpleName" || ktName == "hexAddress") &&
            ktName !in bundle.symbols.propertiesByName &&
            ktName !in bundle.symbols.functionsByName
        ) {
            includes.addProject("kotlinx/coroutines/Debug.hpp")
            val nsPrefix =
                if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                    ""
                } else {
                    "kotlinx::coroutines::"
                }
            return "$nsPrefix${CppNamingPolicy.toSnakeCase(ktName)}(this)"
        }

        if (!bundle.isDocExample && ktName == "Unit") {
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            return "kotlinx::coroutines::Unit{}"
        }
        if (ktName == "COROUTINE_SUSPENDED") {
            includes.addProject("kotlinx/coroutines/intrinsics/Intrinsics.hpp")
            return "COROUTINE_SUSPENDED"
        }
        if (ktName == "EmptyCoroutineContext") {
            includes.addProject("kotlinx/coroutines/CoroutineContext.hpp")
            return "EmptyCoroutineContext::instance()"
        }
        if (ktName == "coroutineContext" && !bundle.isDocExample) {
            includes.addProject("kotlinx/coroutines/Continuation.hpp")
            return "kotlin::coroutines::get_coroutine_context()"
        }
        if (ktName == "ContinuationInterceptor" && !bundle.isDocExample) {
            includes.addProject("kotlinx/coroutines/ContinuationInterceptor.hpp")
            return "&ContinuationInterceptor::key_instance"
        }
        if (ktName == "Key" && !bundle.isDocExample && enclosingClass != null) {
            val compSym = enclosingClass.companionFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            if (compSym != null && compSym.kotlinName == "Key" && compSym.superTypeFrameIndices.isNotEmpty()) {
                return "${enclosingClass.cppName}::Key::instance()"
            }
        }

        val effectiveKtName = bundle.symbols.importAliases[ktName] ?: ktName
        val sameCls = lookupAccessibleClassByName(ktName) ?: lookupAccessibleClassByName(effectiveKtName)
        if (sameCls != null) {
            if (sameCls.kind == KtClassKind.OBJECT_SINGLETON) {
                return "${sameCls.cppName}::instance()"
            }
            val compSym = sameCls.companionFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            if (compSym != null && compSym.superTypeFrameIndices.isNotEmpty()) {
                return "${sameCls.cppName}::${compSym.cppName}::instance()"
            }
        }
        if (!bundle.isDocExample && sameCls == null && effectiveKtName in KNOWN_CONTEXT_KEY_CARRIERS) {
            val cppTypeBase = CppNamingPolicy.toTypeName(effectiveKtName)
            val qual =
                CppTypeTranslator.includeAndQualifyKnownType(
                    simpleName = effectiveKtName,
                    cppBaseName = cppTypeBase,
                    currentNs = bundle.symbols.namespaceSegments,
                    includes = includes,
                ) ?: run {
                    if (effectiveKtName == "CoroutineId") {
                        includes.addProject("kotlinx/coroutines/CoroutineContext.common.hpp")
                        if (bundle.symbols.namespaceSegments.take(2) == listOf("kotlinx", "coroutines") &&
                            bundle.symbols.namespaceSegments.size == 2
                        ) {
                            cppTypeBase
                        } else {
                            "kotlinx::coroutines::$cppTypeBase"
                        }
                    } else {
                        cppTypeBase
                    }
                }
            return "$qual::Key::instance()"
        }

        val rawCppName =
            pkt.targetCppName
                ?: if (isVerbatimCIdentifier(effectiveKtName)) {
                    effectiveKtName
                } else {
                    CppNamingPolicy.toSnakeCase(effectiveKtName)
                }
        if (!bundle.isDocExample) {
            if (effectiveKtName == "Unconfined") {
                val qualUnconfined =
                    lookupAccessibleClassByName("Unconfined")?.cppName
                        ?: CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = "Unconfined",
                            cppBaseName = "Unconfined",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        )
                if (qualUnconfined != null) return "$qualUnconfined::shared_instance()"
            }
            if (effectiveKtName in KNOWN_OBJECT_SINGLETONS) {
                val qualObj =
                    CppTypeTranslator.includeAndQualifyKnownType(
                        simpleName = effectiveKtName,
                        cppBaseName = CppNamingPolicy.toTypeName(effectiveKtName),
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    )
                if (qualObj != null) return "$qualObj::instance()"
            }
            val qualifiedVal =
                CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                    kotlinName = effectiveKtName,
                    cppBaseName = rawCppName,
                    currentNs = bundle.symbols.namespaceSegments,
                    includes = includes,
                )
            if (qualifiedVal != null) return qualifiedVal
            val importedOwner =
                bundle.symbols.importedCompanionMembers[pkt.unescapedName]
                    ?: bundle.symbols.importedCompanionMembers[effectiveKtName]
            if (importedOwner != null) {
                val ownerSameCls = lookupAccessibleClassByName(importedOwner)
                val qualOwner =
                    ownerSameCls?.cppName
                        ?: CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = importedOwner,
                            cppBaseName = CppNamingPolicy.toTypeName(importedOwner),
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        )
                if (qualOwner != null) {
                    val ownerPrefix =
                        when {
                            ownerSameCls != null && enclosingClass?.frameIndex == ownerSameCls.frameIndex -> null
                            ownerSameCls != null && ownerSameCls.typeParams.isNotEmpty() -> {
                                val voidArgs = ownerSameCls.typeParams.joinToString(", ") { "void*" }
                                "$qualOwner<$voidArgs>"
                            }
                            importedOwner in
                                setOf(
                                    "Channel",
                                    "ChannelResult",
                                    "SendChannel",
                                    "ReceiveChannel",
                                    "BufferedChannel",
                                    "BroadcastChannel",
                                ) -> "$qualOwner<void*>"
                            else -> qualOwner
                        }
                    val isAllCaps =
                        effectiveKtName.any { it.isLetter() } &&
                            effectiveKtName.all { it.isUpperCase() || it.isDigit() || it == '_' }
                    val cppMember =
                        if (isAllCaps) {
                            CppNamingPolicy.toConstName(effectiveKtName)
                        } else {
                            CppNamingPolicy.toSnakeCase(effectiveKtName)
                        }
                    return if (ownerPrefix != null) "$ownerPrefix::$cppMember" else cppMember
                }
            }
        }

        if (!bundle.isDocExample &&
            !inDefaultParameterExpr &&
            enclosingClass != null &&
            enclosingClass.typeParams.isNotEmpty() &&
            enclosingClass.superTypeFrameIndices.isNotEmpty()
        ) {
            val companionSym = enclosingClass.companionFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            val isCompanionMember =
                companionSym != null &&
                    (
                        companionSym.memberPropertyFrameIndices.any { bundle.symbols.propertiesByFrame[it]?.kotlinName == ktName } ||
                            companionSym.memberFunctionFrameIndices.any { bundle.symbols.functionsByFrame[it]?.kotlinName == ktName }
                    )
            val isNestedClass =
                enclosingClass.memberClassFrameIndices.any { bundle.symbols.classesByFrame[it]?.kotlinName == ktName }
            val isTypeParam =
                ktName in activeTypeParams ||
                    effectiveKtName in activeTypeParams ||
                    enclosingClass.typeParams.any { it.name == ktName || it.name == effectiveKtName }
            val isSameFileTopLevel =
                ktName in bundle.symbols.propertiesByName ||
                    effectiveKtName in bundle.symbols.propertiesByName ||
                    bundle.symbols.functionsByName[ktName]?.any { it.enclosingClassFrameIndex == null } == true ||
                    bundle.symbols.functionsByName[effectiveKtName]?.any { it.enclosingClassFrameIndex == null } == true ||
                    ktName in bundle.symbols.classesByName ||
                    effectiveKtName in bundle.symbols.classesByName ||
                    bundle.symbols.typeAliasesByFrame.values
                        .any { it.kotlinName == ktName || it.kotlinName == effectiveKtName }
            if (!isTypeParam &&
                !isSameFileTopLevel &&
                !isCompanionMember &&
                !isNestedClass &&
                pkt.role != SymbolRole.TYPE &&
                !isVerbatimCIdentifier(effectiveKtName) &&
                effectiveKtName.firstOrNull()?.isUpperCase() != true
            ) {
                return "this->$rawCppName"
            }
        }

        return rawCppName
    }

    private fun emitDotQualifiedExpr(dotFrame: PacketFrame): String {
        val parts = FrameAstHelpers.childFrames(stream, dotFrame)
        require(parts.size == 2) {
            "${stream.sourcePath}:${dotFrame.span.startLine}:${dotFrame.span.startColumn}: malformed DOT_QUALIFIED_EXPRESSION"
        }
        val recvFrame = parts[0]
        val selFrame = parts[1]
        val recvSimpleIdent =
            if (recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                FrameAstHelpers.directIdentPacket(stream, recvFrame)?.unescapedName
            } else {
                null
            }
        val effectiveRecvKt = recvSimpleIdent?.let { bundle.symbols.importAliases[it] ?: it }
        if (!bundle.isDocExample) {
            val dottedRecv = FrameAstHelpers.dottedNameText(stream, recvFrame)
            val dottedParts = dottedRecv.split('.').filter { it.isNotEmpty() }
            val isPkgPrefix =
                dottedParts.isNotEmpty() &&
                    (dottedParts.first() == "kotlin" || dottedParts.first() == "kotlinx") &&
                    dottedParts.all { it.first().isLowerCase() }
            if (isPkgPrefix && selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                return emitCallExpr(receiverCode = null, receiverIsPointer = false, callFrame = selFrame)
            }
            if (isPkgPrefix && selFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                return emitReferenceExpr(selFrame)
            }
            if (selFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                val selKt = FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName
                val isDispatchersRecv =
                    (
                        dottedParts.size == 1 &&
                            (bundle.symbols.importAliases[dottedParts[0]] ?: dottedParts[0]) == "Dispatchers" &&
                            resolveBoundName(dottedParts[0]) == null
                    ) ||
                        (
                            dottedParts.size >= 2 &&
                                dottedParts.last() == "Dispatchers" &&
                                (dottedParts.first() == "kotlin" || dottedParts.first() == "kotlinx") &&
                                dottedParts.dropLast(1).all { it.first().isLowerCase() }
                        )
                if (isDispatchersRecv && selKt in setOf("Unconfined", "Main", "Default", "IO")) {
                    val qualDisp =
                        lookupAccessibleClassByName("Dispatchers")?.cppName
                            ?: CppTypeTranslator.includeAndQualifyKnownType(
                                simpleName = "Dispatchers",
                                cppBaseName = "Dispatchers",
                                currentNs = bundle.symbols.namespaceSegments,
                                includes = includes,
                            ) ?: "Dispatchers"
                    return when (selKt) {
                        "Unconfined" -> "$qualDisp::instance().unconfined()"
                        "Main" -> "$qualDisp::instance().main()"
                        "Default" -> "$qualDisp::default_val"
                        else -> "$qualDisp::IO"
                    }
                }
            }
        }

        // 1. Check `Result.success(v)` / `Result.failure(e)`
        if (effectiveRecvKt == "Result" && selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val fnName = extractCalleeSimpleName(selFrame)
            val args = extractCallArgExpressions(selFrame)
            includes.addProject("kotlinx/coroutines/Result.hpp")
            if (bundle.isDocExample) {
                if (fnName == "success") return "Result::success(${args.joinToString(", ")})"
                if (fnName == "failure") return "Result::failure(${args.joinToString(", ")})"
            } else {
                if (fnName == "success") {
                    val vArg = args.firstOrNull()
                    if (vArg == null || vArg == "Unit" || vArg == "kotlinx::coroutines::Unit{}") {
                        includes.addProject("kotlinx/coroutines/Unit.hpp")
                        return "kotlinx::coroutines::Result<kotlinx::coroutines::Unit>::success(kotlinx::coroutines::Unit{})"
                    }
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    return "([&](auto&& kxs_rv) { return kotlinx::coroutines::Result<std::decay_t<decltype(kxs_rv)>>::success(std::forward<decltype(kxs_rv)>(kxs_rv)); }($vArg))"
                }
                if (fnName == "failure") {
                    includes.addSystem("exception")
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    includes.needsResultHelpersDef = true
                    val eArg = args.firstOrNull() ?: "std::current_exception()"
                    return "KxsResultFailure{kxs_to_exception_ptr($eArg)}"
                }
            }
        }

        // 2. Check ThreadLocal `.get()` / `.set(x)`
        if (recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val recvKt = FrameAstHelpers.directIdentPacket(stream, recvFrame)?.unescapedName
            val tlSlot = recvKt?.let { bundle.atomicPlan.threadLocalsByKotlinName[it] }
            if (tlSlot != null && selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                val fnName = extractCalleeSimpleName(selFrame)
                val args = extractCallArgExpressions(selFrame)
                if (fnName == "get") return "${tlSlot.cppName}.get()"
                if (fnName == "set") return "${tlSlot.cppName}.set(${args.joinToString(", ")})"
            }
        }

        // 3. Check `TypeName.MEMBER` -> `TypeName::MEMBER` (enum entry, companion constant/factory)
        val sameRecvCls =
            recvSimpleIdent?.let { lookupAccessibleClassByName(it) }
                ?: effectiveRecvKt?.let { lookupAccessibleClassByName(it) }
        val sameRecvSingleton =
            sameRecvCls != null &&
                sameRecvCls.kind == KtClassKind.OBJECT_SINGLETON &&
                sameRecvCls.superTypeFrameIndices.isNotEmpty()
        val knownRecvSingleton =
            !bundle.isDocExample &&
                sameRecvCls == null &&
                effectiveRecvKt in KNOWN_OBJECT_SINGLETONS
        val isObjectInstanceCall =
            if ((sameRecvSingleton || knownRecvSingleton) && selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                val fnKt = extractCalleeSimpleName(selFrame)
                val memberFns = sameRecvCls?.memberFunctionFrameIndices.orEmpty()
                val directStaticFn =
                    memberFns
                        .mapNotNull { bundle.symbols.functionsByFrame[it] }
                        .firstOrNull { it.kotlinName == fnKt }
                directStaticFn == null || PacketFlags.hasFlag(directStaticFn.flags, PacketFlags.MOD_OVERRIDE)
            } else {
                false
            }
        val isStaticTypeReceiver =
            !isObjectInstanceCall &&
                recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                effectiveRecvKt !in KNOWN_OBJECT_SINGLETONS &&
                FrameAstHelpers.directIdentPacket(stream, recvFrame)?.let { pkt ->
                    pkt.role == SymbolRole.TYPE ||
                        (pkt.unescapedName.firstOrNull()?.isUpperCase() == true && resolveBoundName(pkt.unescapedName) == null)
                } == true

        val isSuperReceiver = recvFrame.nodeTypeId == KtNodeTypes.SUPER_EXPRESSION_ID
        val isUnlabeledThisRecv =
            recvFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID &&
                FrameAstHelpers.firstChildFrame(stream, recvFrame, KtNodeTypes.LABEL_QUALIFIER_ID) == null &&
                receiverSubstitution == null
        val superSimpleAndCpp = if (isSuperReceiver) resolvePrimarySuperSimpleAndCpp(recvFrame) else null
        val rawRecvCode =
            if (superSimpleAndCpp != null) {
                superSimpleAndCpp.second
            } else if (isStaticTypeReceiver && effectiveRecvKt != null) {
                if (sameRecvCls != null) {
                    if (sameRecvCls.typeParams.isNotEmpty() &&
                        enclosingClass?.frameIndex != sameRecvCls.frameIndex &&
                        selFrame.nodeTypeId != KtNodeTypes.CONSTRUCTOR_CALLEE_ID
                    ) {
                        val voidArgs = sameRecvCls.typeParams.joinToString(", ") { "void*" }
                        "${sameRecvCls.cppName}<$voidArgs>"
                    } else {
                        sameRecvCls.cppName
                    }
                } else {
                    val genericAlias =
                        if (!bundle.isDocExample) {
                            bundle.symbols.typeAliasesByFrame.values.firstOrNull {
                                it.kotlinName == effectiveRecvKt && it.typeParams.isNotEmpty()
                            }
                        } else {
                            null
                        }
                    if (genericAlias != null) {
                        val tArgs =
                            genericAlias.typeParams.joinToString(", ") { tp ->
                                if (tp.name in activeTypeParams) {
                                    tp.name
                                } else {
                                    includes.addSystem("any")
                                    "std::any"
                                }
                            }
                        "${genericAlias.cppName}<$tArgs>"
                    } else {
                        val baseTypeCpp = CppNamingPolicy.toTypeName(effectiveRecvKt)
                        if (!bundle.isDocExample) {
                            CppTypeTranslator.includeAndQualifyKnownType(
                                simpleName = effectiveRecvKt,
                                cppBaseName = baseTypeCpp,
                                currentNs = bundle.symbols.namespaceSegments,
                                includes = includes,
                            ) ?: baseTypeCpp
                        } else {
                            baseTypeCpp
                        }
                    }
                }
            } else if (isUnlabeledThisRecv) {
                "this"
            } else {
                withExpectedTargetType(null) { emitExpression(recvFrame) }
            }
        val unwrappedOuterPtr =
            if (rawRecvCode.startsWith("(*") && rawRecvCode.endsWith(")") && rawRecvCode.contains("outer_")) {
                rawRecvCode.substring(2, rawRecvCode.length - 1)
            } else {
                null
            }
        val recvCode = unwrappedOuterPtr ?: rawRecvCode
        val isPtrReceiver = unwrappedOuterPtr != null || isPointerOrSharedPtrReceiver(recvFrame, recvCode)

        if (selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            if (isStaticTypeReceiver || isSuperReceiver) {
                val fnName = extractCalleeSimpleName(selFrame)
                val selTypeArgs = extractCallTypeArgs(selFrame)
                val selTSuffix = if (selTypeArgs.isNotEmpty()) "<${selTypeArgs.joinToString(", ")}>" else ""
                val args = extractCallArgExpressions(selFrame)
                return "$recvCode::${CppNamingPolicy.toSnakeCase(fnName)}$selTSuffix(${args.joinToString(", ")})"
            }
            return emitCallExpr(
                receiverCode = recvCode,
                receiverIsPointer = isPtrReceiver,
                callFrame = selFrame,
                receiverFrame = recvFrame,
            )
        }

        val memberKt =
            FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName
                ?: FrameAstHelpers.operationSymbolName(stream, selFrame)
        if (!bundle.isDocExample &&
            (
                rawRecvCode == "Channel::Factory" ||
                    rawRecvCode == "Channel::factory" ||
                    rawRecvCode.endsWith("::Channel::Factory") ||
                    rawRecvCode.endsWith("::Channel::factory")
            ) &&
            memberKt in CHANNEL_STATIC_CONSTANTS
        ) {
            val channelType = rawRecvCode.removeSuffix("::Factory").removeSuffix("::factory")
            return "$channelType<void*>::$memberKt"
        }
        if (isStaticTypeReceiver || isSuperReceiver) {
            if (isSuperReceiver) {
                val superCls = superSimpleAndCpp?.first?.let { lookupAccessibleClassByName(it) }
                val superProp =
                    superCls
                        ?.memberPropertyFrameIndices
                        ?.mapNotNull { bundle.symbols.propertiesByFrame[it] }
                        ?.firstOrNull { it.kotlinName == memberKt }
                if (superCls != null && superProp != null && isGetterStyleClassProperty(superCls, superProp)) {
                    val getterName = resolveClassPropGetterCppName(superCls, superProp.kotlinName, superProp.cppName)
                    return "$recvCode::$getterName()"
                }
                if (superCls == null && !bundle.isDocExample) {
                    when (memberKt) {
                        "message" -> return "$recvCode::get_message()"
                        "cause" -> return "$recvCode::get_cause()"
                        "context" -> return "$recvCode::get_context()"
                        "coroutineContext" -> return "$recvCode::coroutine_context()"
                        "current" ->
                            if (effectiveRecvKt == "Worker") {
                                return "$recvCode::current"
                            } else if (isExternalCoroutineGetterProperty(
                                    memberKt,
                                    isUnlabeledThisRecv = false,
                                    hasOwnField = false,
                                )
                            ) {
                                return "$recvCode::${CppNamingPolicy.toSnakeCase(memberKt)}()"
                            }
                        else ->
                            if (isExternalCoroutineGetterProperty(
                                    memberKt,
                                    isUnlabeledThisRecv = false,
                                    hasOwnField = false,
                                )
                            ) {
                                return "$recvCode::${CppNamingPolicy.toSnakeCase(memberKt)}()"
                            }
                    }
                }
            }
            if (!bundle.isDocExample && !isSuperReceiver && sameRecvCls == null) {
                when ("$effectiveRecvKt.$memberKt") {
                    "Int.MAX_VALUE" -> {
                        includes.addSystem("cstdint")
                        return "INT32_MAX"
                    }
                    "Int.MIN_VALUE" -> {
                        includes.addSystem("cstdint")
                        return "INT32_MIN"
                    }
                    "Long.MAX_VALUE" -> {
                        includes.addSystem("cstdint")
                        return "INT64_MAX"
                    }
                    "Long.MIN_VALUE" -> {
                        includes.addSystem("cstdint")
                        return "INT64_MIN"
                    }
                    "Double.MAX_VALUE" -> {
                        includes.addSystem("limits")
                        return "std::numeric_limits<double>::max()"
                    }
                    "Double.MIN_VALUE" -> {
                        includes.addSystem("limits")
                        return "std::numeric_limits<double>::denorm_min()"
                    }
                    "Double.POSITIVE_INFINITY" -> {
                        includes.addSystem("limits")
                        return "std::numeric_limits<double>::infinity()"
                    }
                    "Double.NEGATIVE_INFINITY" -> {
                        includes.addSystem("limits")
                        return "(-std::numeric_limits<double>::infinity())"
                    }
                    "Double.NaN" -> {
                        includes.addSystem("limits")
                        return "std::numeric_limits<double>::quiet_NaN()"
                    }
                }
            }
            val sameCls = if (isSuperReceiver) superSimpleAndCpp?.first?.let { lookupAccessibleClassByName(it) } else sameRecvCls
            if (!bundle.isDocExample && !isSuperReceiver && effectiveRecvKt == "Channel" && memberKt in CHANNEL_STATIC_CONSTANTS) {
                return "$recvCode<void*>::$memberKt"
            }
            val isEnumOrConst =
                sameCls?.enumEntries?.any { it.kotlinName == memberKt } == true ||
                    (memberKt.length > 1 && memberKt.all { it.isUpperCase() || it.isDigit() || it == '_' })
            val memberCpp = if (isEnumOrConst) CppNamingPolicy.toConstName(memberKt) else CppNamingPolicy.toSnakeCase(memberKt)
            return "$recvCode::$memberCpp"
        }

        val hasOwnField =
            enclosingClass?.primaryConstructorParams?.any { it.name == memberKt } == true ||
                enclosingClass?.memberPropertyFrameIndices?.any {
                    bundle.symbols.propertiesByFrame[it]?.kotlinName == memberKt
                } == true

        // Standard Kotlin stdlib property mappings (`.length` / `.size` -> `.size()`, `.value`, `.context`, Duration properties)
        when (memberKt) {
            "size", "length" -> {
                if (!hasOwnField) {
                    val op = if (isPtrReceiver) "->" else "."
                    if (bundle.isDocExample) {
                        return "$recvCode${op}size()"
                    }
                    includes.addSystem("cstdint")
                    return "static_cast<std::int32_t>($recvCode${op}size())"
                }
            }
            "value" -> {
                if (!bundle.isDocExample && !(isUnlabeledThisRecv && hasOwnField)) {
                    return emitFlexibleMemberPropertyAccess(recvCode, "get_value", "value", "value")
                }
            }
            "context" -> {
                if (!bundle.isDocExample && !(isUnlabeledThisRecv && hasOwnField && !classInheritsContinuation(enclosingClass))) {
                    return emitFlexibleMemberPropertyAccess(recvCode, "get_context", "context", "context")
                }
            }
            "message" -> {
                if (!bundle.isDocExample && !(isUnlabeledThisRecv && hasOwnField)) {
                    return emitFlexibleMemberPropertyAccess(recvCode, "get_message", "what", "message")
                }
            }
            "cause" -> {
                if (!bundle.isDocExample && !(isUnlabeledThisRecv && hasOwnField)) {
                    return emitFlexibleMemberPropertyAccess(recvCode, "get_cause", "cause", "cause")
                }
            }
            "nanoseconds", "milliseconds", "seconds" -> {
                if (!bundle.isDocExample) {
                    includes.addProject("kotlin/time/Duration.hpp")
                    return "kotlin::time::$memberKt(static_cast<long long>($recvCode))"
                }
            }
            "microseconds", "minutes", "hours", "days" -> {
                if (!bundle.isDocExample) {
                    includes.addProject("kotlin/time/Duration.hpp")
                    val unitEnum = memberKt.uppercase()
                    return "kotlin::time::to_duration(static_cast<long long>($recvCode), kotlin::time::DurationUnit::$unitEnum)"
                }
            }
            "inWholeMilliseconds" -> {
                if (!bundle.isDocExample) {
                    val op = if (isPtrReceiver) "->" else "."
                    return "$recvCode${op}in_whole_milliseconds()"
                }
            }
            "inWholeNanoseconds", "inWholeMicroseconds", "inWholeSeconds" -> {
                if (!bundle.isDocExample) {
                    includes.addProject("kotlin/time/Duration.hpp")
                    val op = if (isPtrReceiver) "->" else "."
                    val unitEnum = memberKt.removePrefix("inWhole").uppercase()
                    return "$recvCode${op}to_long(kotlin::time::DurationUnit::$unitEnum)"
                }
            }
            "onSend",
            "onReceive",
            "onReceiveCatching",
            "onReceiveOrNull",
            "onAwait",
            "isClosedForSend",
            "isClosedForReceive",
            "coroutineContext",
            "subscriptionCount",
            "isCompleted",
            "isCancelled",
            "handled",
            "eventLoop",
            -> {
                if (isExternalCoroutineGetterProperty(memberKt, isUnlabeledThisRecv, hasOwnField)) {
                    val op = if (isPtrReceiver) "->" else "."
                    return "$recvCode$op${CppNamingPolicy.toSnakeCase(memberKt)}()"
                }
            }
            "classSimpleName", "hexAddress" -> {
                if (!bundle.isDocExample &&
                    !hasOwnField &&
                    bundle.symbols.propertiesByName[memberKt]?.receiverTypeFrameIndex == null
                ) {
                    includes.addProject("kotlinx/coroutines/Debug.hpp")
                    val nsPrefix =
                        if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                            ""
                        } else {
                            "kotlinx::coroutines::"
                        }
                    return "$nsPrefix${CppNamingPolicy.toSnakeCase(memberKt)}($recvCode)"
                }
            }
            "coroutineName" -> {
                if (!bundle.isDocExample &&
                    !hasOwnField &&
                    bundle.symbols.propertiesByName[memberKt]?.receiverTypeFrameIndex == null
                ) {
                    includes.addProject("kotlinx/coroutines/CoroutineContext.common.hpp")
                    val nsPrefix =
                        if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                            ""
                        } else {
                            "kotlinx::coroutines::"
                        }
                    return "${nsPrefix}coroutine_name($recvCode)"
                }
            }
            "job" -> {
                val recvSimple = inferExpressionSimpleTypeName(recvFrame)
                val effRecvSimple = recvSimple?.let { bundle.symbols.importAliases[it] ?: it }
                val recvHasOwnJob =
                    sameRecvCls != null &&
                        (
                            findClassHierarchyProperty(sameRecvCls, "job") != null ||
                                findClassHierarchyFunction(sameRecvCls, "job", 0) != null
                        )
                val looksLikeCoroutineContext =
                    effRecvSimple == "CoroutineContext" ||
                        effRecvSimple == "CombinedContext" ||
                        effRecvSimple == "EmptyCoroutineContext" ||
                        effRecvSimple == "CoroutineDispatcher" ||
                        effRecvSimple == "Element" ||
                        recvCode.endsWith("coroutine_context") ||
                        recvCode.endsWith("coroutine_context()") ||
                        recvCode.endsWith("get_coroutine_context()") ||
                        (
                            sameRecvCls == null &&
                                (
                                    recvCode == "context" ||
                                        recvCode.endsWith("->context") ||
                                        recvCode.endsWith(".context") ||
                                        recvCode.endsWith("_context") ||
                                        recvCode.endsWith("_context()")
                                )
                        )
                if (!bundle.isDocExample &&
                    !hasOwnField &&
                    !recvHasOwnJob &&
                    looksLikeCoroutineContext &&
                    bundle.symbols.propertiesByName[memberKt]?.receiverTypeFrameIndex == null
                ) {
                    includes.addProject("kotlinx/coroutines/Job.hpp")
                    val shadowedInEnclosing =
                        enclosingClass?.let {
                            findClassHierarchyProperty(it, "job") != null ||
                                findClassHierarchyFunction(it, "job", 1) != null
                        } == true
                    val nsPrefix =
                        if (!shadowedInEnclosing &&
                            bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")
                        ) {
                            ""
                        } else {
                            "kotlinx::coroutines::"
                        }
                    return "${nsPrefix}job($recvCode)"
                }
            }
            "isActive" -> {
                if (isExternalCoroutineGetterProperty(memberKt, isUnlabeledThisRecv, hasOwnField)) {
                    includes.addSystem("type_traits")
                    return "([&](auto&& kxs_ar) -> bool { " +
                        "if constexpr (requires { kxs_ar->is_active(); }) return kxs_ar->is_active(); " +
                        "else if constexpr (requires { kxs_ar.is_active(); }) return kxs_ar.is_active(); " +
                        "else return is_active(kxs_ar); }($recvCode))"
                }
            }
        }

        // Check if `memberKt` is an extension property or rich-enum property declared in the same file!
        val sameFileProp = bundle.symbols.propertiesByName[memberKt]
        if (sameFileProp != null && sameFileProp.receiverTypeFrameIndex != null) {
            return "${sameFileProp.cppName}($recvCode)"
        }
        if (!bundle.isDocExample && memberKt == "isLazy" && !isUnlabeledThisRecv && !hasOwnField) {
            val qualIsLazy =
                if (lookupAccessibleClassByName("CoroutineStart") != null) {
                    "is_lazy"
                } else {
                    CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                        kotlinName = "isLazy",
                        cppBaseName = "is_lazy",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "kotlinx::coroutines::is_lazy"
                }
            return "$qualIsLazy($recvCode)"
        }

        if (!bundle.isDocExample && memberKt == "result") {
            val uRecv = unwrapTransparentFrame(recvFrame)
            val recvCall =
                when (uRecv.nodeTypeId) {
                    KtNodeTypes.CALL_EXPRESSION_ID -> uRecv
                    KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID ->
                        FrameAstHelpers
                            .childFrames(stream, uRecv)
                            .getOrNull(1)
                            ?.let { unwrapTransparentFrame(it) }
                            ?.takeIf { it.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID }
                    else -> null
                }
            val recvCallee = recvCall?.let { extractCalleeSimpleName(it) }
            if (recvCallee == "requestTermination" || recvCallee == "execute") {
                return "$recvCode.consume_result()"
            }
        }

        val op = if (isPtrReceiver) "->" else "."
        if (isUnlabeledThisRecv) {
            return "this->${resolveMemberFieldOrPropName(memberKt, recvFrame)}"
        }
        return "$recvCode$op${resolveMemberFieldOrPropName(memberKt, recvFrame)}"
    }

    private fun isExternalCoroutineGetterProperty(
        memberKt: String,
        isUnlabeledThisRecv: Boolean,
        hasOwnField: Boolean,
    ): Boolean {
        if (bundle.isDocExample) return false
        if (isUnlabeledThisRecv && hasOwnField) return false
        if (bundle.symbols.propertiesByName[memberKt]?.receiverTypeFrameIndex != null) return false
        val sameFileHasBackedField =
            bundle.symbols.classesByFrame.values.any { c ->
                c.primaryConstructorParams.any {
                    (it.isVal || it.isVar) && it.name == memberKt && !isBackedOverrideCtorParamExpr(c, it)
                } ||
                    c.memberPropertyFrameIndices.any { pIdx ->
                        val p = bundle.symbols.propertiesByFrame[pIdx]
                        p != null && p.kotlinName == memberKt && !isGetterStyleClassProperty(c, p)
                    }
            }
        return !sameFileHasBackedField
    }

    private fun isSharedThisClass(kotlinName: String): Boolean =
        CppTypeTranslator.isSharedHierarchyType(kotlinName) ||
            kotlinName in bundle.symbols.sharedHierarchyTypeNames ||
            kotlinName == "ConcurrentLinkedListNode" ||
            kotlinName == "Segment" ||
            kotlinName == "LockFreeLinkedListNode"

    private fun emitFlexibleMemberPropertyAccess(
        recvCode: String,
        primaryGetter: String,
        altGetter: String,
        fieldName: String,
    ): String {
        includes.addSystem("type_traits")
        return "([&](auto&& kxs_p_r) -> decltype(auto) { " +
            "using KxsPR = std::remove_cvref_t<decltype(kxs_p_r)>; " +
            "if constexpr (std::is_pointer_v<KxsPR> || requires { kxs_p_r.operator->(); }) { " +
            "if constexpr (requires { kxs_p_r->$primaryGetter(); }) return kxs_p_r->$primaryGetter(); " +
            "else if constexpr (requires { kxs_p_r->$altGetter(); }) return kxs_p_r->$altGetter(); " +
            "else return kxs_p_r->$fieldName; " +
            "} else { " +
            "if constexpr (requires { kxs_p_r.$primaryGetter(); }) return kxs_p_r.$primaryGetter(); " +
            "else if constexpr (requires { kxs_p_r.$altGetter(); }) return kxs_p_r.$altGetter(); " +
            "else return kxs_p_r.$fieldName; " +
            "} }($recvCode))"
    }

    private fun isBackedOverrideCtorParamExpr(
        cls: KtClassSymbol,
        cp: KtValueParamInfo,
    ): Boolean {
        if (cls.kind == KtClassKind.INTERFACE) return false
        if (!cp.isVal && !cp.isVar) return false
        val cpFlags = stream.frames[cp.frameIndex].flags
        return PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_OVERRIDE) &&
            !PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_ABSTRACT)
    }

    private fun hasCustomGetterBodyExpr(prop: KtPropertySymbol): Boolean {
        val getterIdx = prop.getterFrameIndex ?: return false
        val getterFrame = stream.frames[getterIdx]
        return FrameAstHelpers.firstChildFrame(stream, getterFrame, KtNodeTypes.BLOCK_ID) != null ||
            FrameAstHelpers.extractInitializerAfterEquals(stream, getterFrame) != null
    }

    private fun isBackedOverrideMemberPropExpr(
        cls: KtClassSymbol,
        prop: KtPropertySymbol,
    ): Boolean {
        if (cls.kind == KtClassKind.INTERFACE) return false
        if (prop.isConst || hasCustomGetterBodyExpr(prop)) return false
        return PacketFlags.hasFlag(prop.flags, PacketFlags.MOD_OVERRIDE) &&
            !PacketFlags.hasFlag(prop.flags, PacketFlags.MOD_ABSTRACT)
    }

    private fun isCancellationExceptionLikeClass(
        cls: KtClassSymbol?,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (cls == null || !visited.add(cls.frameIndex)) return false
        if (cls.kotlinName in CANCELLATION_EXCEPTION_CLASS_NAMES) return true
        val allClasses = bundle.symbols.classesByFrame.values
        for (trIdx in cls.superTypeFrameIndices) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: continue
            if (simple in CANCELLATION_EXCEPTION_CLASS_NAMES) return true
            val sameFileSuper = allClasses.firstOrNull { it.kotlinName == simple }
            if (sameFileSuper != null && isCancellationExceptionLikeClass(sameFileSuper, visited)) return true
        }
        return false
    }

    private fun classInheritsContinuation(
        cls: KtClassSymbol?,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (cls == null || !visited.add(cls.frameIndex)) return false
        if (cls.kotlinName in CONTINUATION_HIERARCHY_BASES) return true
        val allClasses = bundle.symbols.classesByFrame.values
        for (trIdx in cls.superTypeFrameIndices) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: continue
            if (simple in CONTINUATION_HIERARCHY_BASES) return true
            val sameFileSuper = allClasses.firstOrNull { it.kotlinName == simple }
            if (sameFileSuper != null && classInheritsContinuation(sameFileSuper, visited)) return true
        }
        return false
    }

    private fun resolveClassPropGetterCppName(
        ownerCls: KtClassSymbol?,
        propKtName: String,
        defaultCppName: String,
    ): String =
        if (propKtName == "context" && classInheritsContinuation(ownerCls)) {
            "get_context"
        } else {
            defaultCppName
        }

    private fun isGetterStyleClassProperty(
        ownerCls: KtClassSymbol,
        prop: KtPropertySymbol,
    ): Boolean =
        hasCustomGetterBodyExpr(prop) ||
            ownerCls.kind == KtClassKind.INTERFACE ||
            (PacketFlags.hasFlag(prop.flags, PacketFlags.MOD_ABSTRACT) && prop.initializerFrameIndex == null) ||
            isBackedOverrideMemberPropExpr(ownerCls, prop)

    private fun classDeclaresMethod(
        cls: KtClassSymbol,
        methodKtName: String,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (!visited.add(cls.frameIndex)) return false
        if (cls.memberFunctionFrameIndices.any { bundle.symbols.functionsByFrame[it]?.kotlinName == methodKtName }) {
            return true
        }
        for (superTypeIdx in cls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superTypeIdx]) ?: continue
            val superCls = bundle.symbols.classesByName[superSimple] ?: continue
            if (classDeclaresMethod(superCls, methodKtName, visited)) return true
        }
        return false
    }

    private fun resolveClassOwnPropertyAccess(
        cls: KtClassSymbol,
        ktName: String,
        isUnqualifiedCurrentThis: Boolean,
    ): String? {
        val ctorProp = cls.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == ktName }
        if (ctorProp != null) {
            val vpFrame = stream.frames[ctorProp.frameIndex]
            val isPriv = PacketFlags.hasFlag(vpFrame.flags, PacketFlags.MOD_PRIVATE)
            val isBackedOverride = isBackedOverrideCtorParamExpr(cls, ctorProp)
            return when {
                cls.kind == KtClassKind.VALUE_CLASS && isUnqualifiedCurrentThis ->
                    CppNamingPolicy.toPrivateFieldName(ctorProp.cppName)
                cls.kind == KtClassKind.VALUE_CLASS ->
                    "${resolveClassPropGetterCppName(cls, ctorProp.name, ctorProp.cppName)}()"
                isPriv || (isUnqualifiedCurrentThis && isBackedOverride) ->
                    CppNamingPolicy.toPrivateFieldName(ktName)
                isBackedOverride ->
                    "${resolveClassPropGetterCppName(cls, ctorProp.name, ctorProp.cppName)}()"
                else -> ctorProp.cppName
            }
        }
        for (mPropIdx in cls.memberPropertyFrameIndices) {
            val mProp = bundle.symbols.propertiesByFrame[mPropIdx] ?: continue
            if (mProp.kotlinName == ktName) {
                val suffix = if (isGetterStyleClassProperty(cls, mProp)) "()" else ""
                val getterName =
                    if (suffix.isNotEmpty()) {
                        resolveClassPropGetterCppName(cls, mProp.kotlinName, mProp.cppName)
                    } else {
                        mProp.cppName
                    }
                return "$getterName$suffix"
            }
        }
        return null
    }

    private fun resolveSuperclassesPropertyAccess(
        cls: KtClassSymbol,
        ktName: String,
        visited: MutableSet<Int> = hashSetOf(cls.frameIndex),
    ): String? {
        for (superIdx in cls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            val superCls = lookupAccessibleClassByName(superSimple) ?: continue
            resolveClassHierarchyPropertyAccess(superCls, ktName, isUnqualifiedSelfStart = false, visited)
                ?.let { return it }
        }
        return null
    }

    private fun resolveClassHierarchyPropertyAccess(
        startCls: KtClassSymbol,
        ktName: String,
        isUnqualifiedSelfStart: Boolean,
        visited: MutableSet<Int> = HashSet(),
    ): String? {
        if (!visited.add(startCls.frameIndex)) return null
        resolveClassOwnPropertyAccess(startCls, ktName, isUnqualifiedSelfStart)?.let { return it }
        return resolveSuperclassesPropertyAccess(startCls, ktName, visited)
    }

    private fun resolveUnqualifiedPropertyAccess(ktName: String): String? {
        val recvSub = receiverSubstitution
        val extRecvCls = extensionReceiverClass
        if (recvSub != null && extRecvCls != null && ktName !in anonObjectOwnProps) {
            resolveClassHierarchyPropertyAccess(extRecvCls, ktName, isUnqualifiedSelfStart = false)
                ?.let { memberAccess ->
                    val op = if (extensionReceiverIsPointer) "->" else "."
                    return "$recvSub$op$memberAccess"
                }
        }

        val extSimple = extensionReceiverSimpleName
        if (recvSub != null && extRecvCls == null && extSimple != null) {
            if (!bundle.isDocExample &&
                ktName == "size" &&
                (extSimple == "Array" || extSimple in DIRECT_ITERABLE_VALUE_TYPES)
            ) {
                includes.addSystem("cstdint")
                val op = if (extensionReceiverIsPointer) "->" else "."
                return "static_cast<std::int32_t>($recvSub${op}size())"
            }
            if (ktName in KNOWN_EXTERNAL_RECEIVER_PROPERTIES[extSimple].orEmpty()) {
                val op = if (extensionReceiverIsPointer) "->" else "."
                return when {
                    extSimple == "ScopeCoroutine" && ktName == "uCont" -> "$recvSub${op}u_cont"
                    extSimple == "ScopeCoroutine" && ktName == "context" -> "$recvSub${op}get_context()"
                    extSimple == "CoroutineScope" && ktName == "coroutineContext" ->
                        "$recvSub${op}coroutine_context()"
                    (extSimple == "Continuation" || extSimple == "CancellableContinuation") && ktName == "context" ->
                        "$recvSub${op}get_context()"
                    extSimple == "Duration" && ktName == "inWholeMilliseconds" ->
                        "$recvSub${op}in_whole_milliseconds()"
                    extSimple == "Duration" && ktName in DURATION_WHOLE_UNITS -> {
                        includes.addProject("kotlin/time/Duration.hpp")
                        val unitEnum = ktName.removePrefix("inWhole").uppercase()
                        "$recvSub${op}to_long(kotlin::time::DurationUnit::$unitEnum)"
                    }
                    else -> "$recvSub$op${CppNamingPolicy.toSnakeCase(ktName)}()"
                }
            }
        }

        if (enclosingClass != null) {
            val enumEntry = enclosingClass.enumEntries.firstOrNull { it.kotlinName == ktName }
            if (enumEntry != null) {
                return "${enclosingClass.cppName}::${enumEntry.cppName}"
            }
            var cur: KtClassSymbol? = enclosingClass
            var prefix = ""
            while (cur != null) {
                resolveClassOwnPropertyAccess(cur, ktName, isUnqualifiedCurrentThis = prefix.isEmpty())
                    ?.let { access ->
                        return "$prefix$access"
                    }
                resolveSuperclassesPropertyAccess(cur, ktName)?.let { access ->
                    val effectivePrefix =
                        if (prefix.isEmpty() &&
                            !bundle.isDocExample &&
                            cur.typeParams.isNotEmpty() &&
                            cur.superTypeFrameIndices.isNotEmpty()
                        ) {
                            "this->"
                        } else {
                            prefix
                        }
                    return "$effectivePrefix$access"
                }
                if (!cur.isInner) break
                cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
                prefix += "outer_->"
            }
            if (!bundle.isDocExample &&
                (ktName == "message" || ktName == "cause") &&
                isCancellationExceptionLikeClass(enclosingClass)
            ) {
                return if (ktName == "message") "get_message()" else "get_cause()"
            }
            if (!bundle.isDocExample &&
                ktName == "context" &&
                classInheritsContinuation(enclosingClass)
            ) {
                return "this->get_context()"
            }
        }
        return null
    }

    private fun resolveMemberFieldOrPropName(
        memberKt: String,
        recvFrame: PacketFrame? = null,
    ): String {
        val isUnqualifiedRecvThis =
            recvFrame == null || unwrapTransparentFrame(recvFrame).nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID
        val recvCls = recvFrame?.let { inferExpressionSimpleTypeName(it) }?.let { resolveClassOrTypeParamBound(it) }
        if (recvCls != null) {
            resolveClassHierarchyPropertyAccess(
                recvCls,
                memberKt,
                isUnqualifiedSelfStart = recvCls == enclosingClass && isUnqualifiedRecvThis,
            )?.let { return it }
        }
        var cur: KtClassSymbol? = enclosingClass
        while (cur != null) {
            resolveClassHierarchyPropertyAccess(
                cur,
                memberKt,
                isUnqualifiedSelfStart = cur == enclosingClass && isUnqualifiedRecvThis,
            )?.let { return it }
            cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        var anyHasRawField = false
        var firstGetterMatch: Pair<KtClassSymbol, KtPropertySymbol>? = null
        for (cls in bundle.symbols.classesByFrame.values) {
            if (cls.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == memberKt }) {
                anyHasRawField = true
                break
            }
            for (pIdx in cls.memberPropertyFrameIndices) {
                val prop = bundle.symbols.propertiesByFrame[pIdx] ?: continue
                if (prop.kotlinName != memberKt) continue
                if (!isGetterStyleClassProperty(cls, prop)) {
                    anyHasRawField = true
                    break
                } else if (firstGetterMatch == null) {
                    firstGetterMatch = cls to prop
                }
            }
            if (anyHasRawField) break
        }
        if (!anyHasRawField && firstGetterMatch != null) {
            val (owner, prop) = firstGetterMatch
            return "${resolveClassPropGetterCppName(owner, prop.kotlinName, prop.cppName)}()"
        }
        return CppNamingPolicy.toSnakeCase(memberKt)
    }

    private fun stripBalancedOuterParens(s: String): String {
        var cur = s.trim()
        while (cur.startsWith('(') && cur.endsWith(')')) {
            var depth = 0
            var closesAtEnd = true
            for (i in 0 until cur.length) {
                when (cur[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0 && i < cur.length - 1) {
                            closesAtEnd = false
                            break
                        }
                    }
                }
            }
            if (!closesAtEnd || depth != 0) break
            cur = cur.substring(1, cur.length - 1).trim()
        }
        return cur
    }

    private fun isCppPtrOrSharedType(cppType: String): Boolean =
        cppType.endsWith("*") || cppType.startsWith("std::shared_ptr<")

    private fun isTypeFramePointerOrSharedPtr(typeFrameIdx: Int?): Boolean {
        if (typeFrameIdx == null) return false
        val tf = stream.frames[typeFrameIdx]
        val rawSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf)
        val eff = rawSimple?.let { bundle.symbols.importAliases[it] ?: it }
        if (eff != null && eff in bundle.symbols.sharedPtrTypeAliases) return true
        return isCppPtrOrSharedType(translateType(tf, CppTypePosition.VALUE))
    }

    private fun isInitSharedHierarchyCall(initIdx: Int): Boolean {
        val initF = unwrapTransparentFrame(stream.frames[initIdx])
        if (initF.nodeTypeId != KtNodeTypes.CALL_EXPRESSION_ID) return false
        val c = extractCalleeSimpleName(initF)
        val eff = bundle.symbols.importAliases[c] ?: c
        return eff in bundle.symbols.sharedHierarchyTypeNames || CppTypeTranslator.isSharedHierarchyType(eff)
    }

    private fun isClassHierarchyPropPointerOrSharedPtr(
        cls: KtClassSymbol,
        propName: String,
        allowAllCtorParams: Boolean = false,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (!visited.add(cls.frameIndex)) return false
        val cp =
            cls.primaryConstructorParams.firstOrNull {
                (allowAllCtorParams || it.isVal || it.isVar) && it.name == propName
            }
        if (cp?.typeFrameIndex != null) {
            return isTypeFramePointerOrSharedPtr(cp.typeFrameIndex)
        }
        for (pIdx in cls.memberPropertyFrameIndices) {
            val mp = bundle.symbols.propertiesByFrame[pIdx] ?: continue
            if (mp.kotlinName == propName) {
                if (mp.returnTypeFrameIndex != null) {
                    return isTypeFramePointerOrSharedPtr(mp.returnTypeFrameIndex)
                }
                if (mp.initializerFrameIndex != null) {
                    return isInitSharedHierarchyCall(mp.initializerFrameIndex)
                }
                return false
            }
        }
        for (superIdx in cls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            val superCls = lookupAccessibleClassByName(superSimple) ?: continue
            if (isClassHierarchyPropPointerOrSharedPtr(superCls, propName, allowAllCtorParams = false, visited)) {
                return true
            }
        }
        return false
    }

    private fun isCallReturnPointerOrSharedPtr(
        callFrame: PacketFrame,
        recvFrame: PacketFrame?,
    ): Boolean {
        val calleeKt = extractCalleeSimpleName(callFrame)
        val eff = bundle.symbols.importAliases[calleeKt] ?: calleeKt
        if (!bundle.isDocExample && eff in DISPATCHER_OR_EVENT_LOOP_FACTORY_NAMES) {
            return true
        }
        val sameCls = lookupAccessibleClassByName(calleeKt) ?: lookupAccessibleClassByName(eff)
        if (recvFrame == null &&
            sameCls != null &&
            sameCls.kind != KtClassKind.INTERFACE &&
            !PacketFlags.hasFlag(stream.frames[sameCls.frameIndex].flags, PacketFlags.MOD_ABSTRACT) &&
            (eff in bundle.symbols.sharedHierarchyTypeNames || CppTypeTranslator.isSharedHierarchyType(eff))
        ) {
            return true
        }
        val argCount = extractCallArgFrames(callFrame).size
        val recvCls = recvFrame?.let { inferExpressionSimpleTypeName(it) }?.let { resolveClassOrTypeParamBound(it) }
        val targetFn =
            recvCls?.let { findClassHierarchyFunction(it, calleeKt, argCount) }
                ?: enclosingClass?.let { findClassHierarchyFunction(it, calleeKt, argCount) }
                ?: bundle.symbols.functionsByName[calleeKt]
                    ?.let { fns -> fns.firstOrNull { it.valueParams.size == argCount } ?: fns.firstOrNull() }
                ?: bundle.symbols.functionsByName[eff]
                    ?.let { fns -> fns.firstOrNull { it.valueParams.size == argCount } ?: fns.firstOrNull() }
        val retIdx = targetFn?.returnTypeFrameIndex ?: return false
        if (isTypeFramePointerOrSharedPtr(retIdx)) return true
        val retSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[retIdx]) ?: return false
        val tpUb =
            targetFn.typeParams.firstOrNull { it.name == retSimple }?.upperBoundFrameIndex
                ?: recvCls?.typeParams?.firstOrNull { it.name == retSimple }?.upperBoundFrameIndex
        return tpUb != null && isTypeFramePointerOrSharedPtr(tpUb)
    }

    private fun isPointerOrSharedPtrReceiver(
        recvFrame: PacketFrame,
        renderedRecv: String,
    ): Boolean {
        if (renderedRecv == "this") return true
        val stripped = stripBalancedOuterParens(renderedRecv)
        if (stripped.startsWith("kxs_as_cast<std::shared_ptr<") ||
            stripped.startsWith("kxs_safe_as_cast<std::shared_ptr<") ||
            stripped.startsWith("std::make_shared<") ||
            stripped.startsWith("std::shared_ptr(") ||
            stripped.startsWith("std::shared_ptr<") ||
            stripped.startsWith("kxs_shared_from_this(") ||
            stripped.startsWith("kxs_intercepted(") ||
            stripped.startsWith("kxs_create_coroutine_unintercepted(") ||
            stripped.startsWith("intercepted(") ||
            stripped.startsWith("kotlin::coroutines::intrinsics::intercepted(") ||
            stripped.startsWith("intrinsics::intercepted(")
        ) {
            return true
        }
        val u = unwrapTransparentFrame(recvFrame)
        when (u.nodeTypeId) {
            KtNodeTypes.POSTFIX_EXPRESSION_ID -> {
                val opRef = FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.OPERATION_REFERENCE_ID)
                if (opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.EXCLEXCL_ID) {
                    val operand =
                        FrameAstHelpers
                            .childFrames(stream, u)
                            .firstOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
                    if (operand != null && isPointerOrSharedPtrReceiver(operand, renderedRecv)) return true
                }
            }
            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val ch = FrameAstHelpers.childFrames(stream, u)
                if (ch.size >= 3 && FrameAstHelpers.operationTokenId(stream, ch[1]) == KtTokens.ELVIS_ID) {
                    if (isPointerOrSharedPtrReceiver(ch[0], renderedRecv) ||
                        isPointerOrSharedPtrReceiver(ch[2], renderedRecv)
                    ) {
                        return true
                    }
                }
            }
            KtNodeTypes.CALL_EXPRESSION_ID -> {
                if (isCallReturnPointerOrSharedPtr(u, recvFrame = null)) return true
            }
            KtNodeTypes.IF_ID -> {
                val thenExpr = extractBranchTailExpr(FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.THEN_ID))
                val elseExpr = extractBranchTailExpr(FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.ELSE_ID))
                return (thenExpr != null && isPointerOrSharedPtrReceiver(thenExpr, "")) ||
                    (elseExpr != null && isPointerOrSharedPtrReceiver(elseExpr, ""))
            }
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                val ch = FrameAstHelpers.childFrames(stream, u)
                if (ch.size >= 2) {
                    val lhs = ch[0]
                    val sel = unwrapTransparentFrame(ch[1])
                    if (sel.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                        if (isCallReturnPointerOrSharedPtr(sel, lhs)) return true
                    } else if (sel.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        val memberKt = FrameAstHelpers.directIdentPacket(stream, sel)?.unescapedName
                        if (memberKt == "value") {
                            resolveAtomicFieldForExpr(lhs)?.let { af ->
                                if (af.explicitElementTypeFrameIndex != null &&
                                    isTypeFramePointerOrSharedPtr(af.explicitElementTypeFrameIndex)
                                ) {
                                    return true
                                }
                                if (af.inferredElementCppType != null && isCppPtrOrSharedType(af.inferredElementCppType)) {
                                    return true
                                }
                                if (af.initExprFrameIndex != null &&
                                    isPointerOrSharedPtrReceiver(stream.frames[af.initExprFrameIndex], "")
                                ) {
                                    return true
                                }
                            }
                        }
                        if (memberKt != null) {
                            if (!bundle.isDocExample && memberKt == "eventLoop") {
                                return true
                            }
                            val lhsCls =
                                inferExpressionSimpleTypeName(lhs)?.let { resolveClassOrTypeParamBound(it) }
                                    ?: if (unwrapTransparentFrame(lhs).nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID) {
                                        enclosingClass
                                    } else {
                                        null
                                    }
                            if (lhsCls != null && isClassHierarchyPropPointerOrSharedPtr(lhsCls, memberKt)) {
                                return true
                            }
                            val extProp = bundle.symbols.propertiesByName[memberKt]
                            if (extProp != null &&
                                extProp.receiverTypeFrameIndex != null &&
                                isTypeFramePointerOrSharedPtr(extProp.returnTypeFrameIndex)
                            ) {
                                return true
                            }
                        }
                    }
                }
            }
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val name = FrameAstHelpers.directIdentPacket(stream, u)?.unescapedName ?: return false
                if (isLocalPolyVar(name)) return true
                var cur: KtClassSymbol? = enclosingClass
                while (cur != null) {
                    if (isClassHierarchyPropPointerOrSharedPtr(cur, name, allowAllCtorParams = true)) return true
                    if (!cur.isInner) break
                    cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
                }
                val topProp = bundle.symbols.propertiesByName[name]
                if (topProp?.receiverTypeFrameIndex == null) {
                    if (topProp?.returnTypeFrameIndex != null) {
                        if (isTypeFramePointerOrSharedPtr(topProp.returnTypeFrameIndex)) return true
                    } else if (topProp?.initializerFrameIndex != null &&
                        isInitSharedHierarchyCall(topProp.initializerFrameIndex)
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun emitStringToBooleanExpr(recvExpr: String): String {
        includes.addSystem("cctype")
        includes.addSystem("string")
        return "([&](const std::string& kxs_s) -> bool { " +
            "if (kxs_s.size() != 4) return false; " +
            "return std::tolower(static_cast<unsigned char>(kxs_s[0])) == 't' && " +
            "std::tolower(static_cast<unsigned char>(kxs_s[1])) == 'r' && " +
            "std::tolower(static_cast<unsigned char>(kxs_s[2])) == 'u' && " +
            "std::tolower(static_cast<unsigned char>(kxs_s[3])) == 'e'; }($recvExpr))"
    }

    private fun emitForEachExpr(
        recvExpr: String,
        lambdaExpr: String,
    ): String {
        includes.addSystem("type_traits")
        val feRecv = freshTemp("kxs_fe_r")
        return "([&]() { auto&& $feRecv = $recvExpr; " +
            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype($feRecv)>> || " +
            "requires { $feRecv.operator->(); }) { " +
            "for (auto&& kxs_fe : (*$feRecv)) { ($lambdaExpr)(kxs_fe); } " +
            "} else { " +
            "for (auto&& kxs_fe : $feRecv) { ($lambdaExpr)(kxs_fe); } " +
            "} }())"
    }

    private fun emitScopeApplyOrRunCall(
        calleeKt: String,
        receiverFrame: PacketFrame?,
        receiverCode: String,
        argFrames: List<PacketFrame>,
        args: Lazy<List<String>>,
    ): String? {
        if ((calleeKt != "apply" && calleeKt != "run") || argFrames.size != 1) return null
        val rawArg0 = unwrapTransparentFrame(argFrames[0])
        if (rawArg0.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID) {
            val fnLit = FrameAstHelpers.firstChildFrame(stream, rawArg0, KtNodeTypes.FUNCTION_LITERAL_ID)
            val lambdaBody =
                if (fnLit != null) {
                    FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
                } else {
                    rawArg0
                }
            val iifeRetSuffix = if (calleeKt == "run") iifeAnyRetSuffix() else ""
            return withinExpressionIife {
                val recvTemp = freshTemp(if (calleeKt == "apply") "kxs_apply" else "kxs_run")
                val nestedEmitter = buildInlineScopeEmitter(receiverFrame, receiverCode, recvTemp)
                val bodyOut =
                    StringBuilder().apply {
                        append("    auto&& ").append(recvTemp).append(" = ").append(receiverCode).append(";\n")
                        nestedEmitter.appendInlineLambdaBodyStatements(
                            this,
                            lambdaBody,
                            "    ",
                            targetVar = null,
                            targetReturn = calleeKt == "run",
                        )
                        if (calleeKt == "apply") {
                            append("    return ").append(recvTemp).append(";\n")
                        }
                    }
                synthCounter = nestedEmitter.synthCounter
                "([&]()$iifeRetSuffix {\n$bodyOut}())"
            }
        }
        val renderedArgs = args.value
        if (renderedArgs.size != 1) return null
        return if (calleeKt == "apply") {
            val recvTemp = freshTemp("kxs_apply")
            "([&]() { auto&& $recvTemp = $receiverCode; (${renderedArgs[0]})($recvTemp); return $recvTemp; }())"
        } else {
            "(${renderedArgs[0]})($receiverCode)"
        }
    }

    private fun emitSafeAccessExpr(safeFrame: PacketFrame): String {
        val parts = FrameAstHelpers.childFrames(stream, safeFrame)
        require(parts.size == 2) {
            "${stream.sourcePath}:${safeFrame.span.startLine}:${safeFrame.span.startColumn}: malformed SAFE_ACCESS_EXPRESSION"
        }
        val lhsCode = withExpectedTargetType(null) { emitExpression(parts[0]) }
        val selFrame = parts[1]
        if (bundle.isDocExample) {
            val memberAccess =
                if (selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                    val fn = CppNamingPolicy.toSnakeCase(extractCalleeSimpleName(selFrame))
                    val args = extractCallArgExpressions(selFrame)
                    "$fn(${args.joinToString(", ")})"
                } else {
                    val prop = FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName ?: "value"
                    CppNamingPolicy.toSnakeCase(prop)
                }
            return "($lhsCode ? ($lhsCode->$memberAccess) : nullptr)"
        }

        val temp = bundle.cfPlan.singleEvalTempNamesByFrame[safeFrame.frameIndex] ?: freshTemp("kxs_safe")
        includes.addSystem("exception")
        includes.addSystem("optional")
        includes.addSystem("type_traits")

        includes.needsNullUnwrapDef = true
        includes.addSystem("any")
        includes.addSystem("utility")

        val safeInvokeExpr: String =
            withinExpressionIife {
                if (selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                    val calleeKt = extractCalleeSimpleName(selFrame)
                    val argFrames = extractCallArgFrames(selFrame)
                    val args = lazy(LazyThreadSafetyMode.NONE) { extractCallArgExpressions(selFrame) }
                    val joinedArgs by lazy(LazyThreadSafetyMode.NONE) { args.value.joinToString(", ") }
                    val sameFileExt =
                        bundle.symbols.functionsByName[calleeKt]
                            ?.firstOrNull { it.receiverTypeFrameIndex != null && isAccessibleExtensionFn(it) }
                    when {
                        calleeKt == "invoke" ->
                            "([&](auto&& kxs_sa_ir) -> decltype(auto) { " +
                                "if constexpr (requires { kxs_sa_ir->invoke($joinedArgs); }) return kxs_sa_ir->invoke($joinedArgs); " +
                                "else if constexpr (requires { kxs_sa_ir.invoke($joinedArgs); }) return kxs_sa_ir.invoke($joinedArgs); " +
                                "else if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_ir)>> || requires { kxs_sa_ir.operator->(); }) return (*kxs_sa_ir)($joinedArgs); " +
                                "else return kxs_sa_ir($joinedArgs); }(kxs_sa_r))"
                        calleeKt == "let" && argFrames.size == 1 ->
                            "(${args.value[0]})(kxs_sa_r)"
                        calleeKt == "also" && argFrames.size == 1 ->
                            "([&]() { (${args.value[0]})(kxs_sa_r); return kxs_sa_r; }())"
                        (calleeKt == "apply" || calleeKt == "run") && argFrames.size == 1 ->
                            emitScopeApplyOrRunCall(calleeKt, parts[0], "kxs_sa_r", argFrames, args)
                                ?: "(${args.value[0]})(kxs_sa_r)"
                        calleeKt == "forEach" && argFrames.size == 1 ->
                            emitForEachExpr("kxs_sa_r", args.value[0])
                        calleeKt == "toBoolean" && argFrames.isEmpty() ->
                            emitStringToBooleanExpr("kxs_sa_r")
                        calleeKt == "isEmpty" && argFrames.isEmpty() ->
                            "([&](auto&& kxs_sa_er) { " +
                                "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_er)>> || " +
                                "requires { kxs_sa_er.operator->(); }) return kxs_sa_er->empty(); " +
                                "else return kxs_sa_er.empty(); }(kxs_sa_r))"
                        calleeKt == "isNotEmpty" && argFrames.isEmpty() ->
                            "(![&](auto&& kxs_sa_er) { " +
                                "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_er)>> || " +
                                "requires { kxs_sa_er.operator->(); }) return kxs_sa_er->empty(); " +
                                "else return kxs_sa_er.empty(); }(kxs_sa_r))"
                        calleeKt == "add" ->
                            "([&](auto&& kxs_sa_ar) -> decltype(auto) { " +
                                "if constexpr (requires { kxs_sa_ar->push_back($joinedArgs); }) " +
                                "return kxs_sa_ar->push_back($joinedArgs); " +
                                "else if constexpr (requires { kxs_sa_ar.push_back($joinedArgs); }) " +
                                "return kxs_sa_ar.push_back($joinedArgs); " +
                                "else if constexpr (requires { kxs_sa_ar->add($joinedArgs); }) " +
                                "return kxs_sa_ar->add($joinedArgs); " +
                                "else return kxs_sa_ar.add($joinedArgs); }(kxs_sa_r))"
                        calleeKt == "toString" && argFrames.isEmpty() ->
                            "([&](auto&& kxs_sa_sr) { if constexpr (requires { kxs_sa_sr->to_string(); }) " +
                                "return kxs_sa_sr->to_string(); else return kxs_sa_sr.to_string(); }(kxs_sa_r))"
                        calleeKt == "hashCode" && argFrames.isEmpty() ->
                            emitHashCodeExpr("kxs_sa_r")
                        calleeKt == "get" && argFrames.size == 1 -> {
                            val arg0 = args.value[0]
                            "([&](auto&& kxs_sa_gr) -> decltype(auto) { " +
                                "if constexpr (requires { kxs_sa_gr->get($arg0); }) return kxs_sa_gr->get($arg0); " +
                                "else if constexpr (requires { kxs_sa_gr.get($arg0); }) return kxs_sa_gr.get($arg0); " +
                                "else if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_gr)>> || " +
                                "requires { kxs_sa_gr.operator->(); }) return (*kxs_sa_gr)[$arg0]; " +
                                "else return kxs_sa_gr[$arg0]; }(kxs_sa_r))"
                        }
                        sameFileExt != null -> {
                            val allArgs = listOf("kxs_sa_r") + args.value
                            "${formatExtensionFnCallTarget(sameFileExt)}(${allArgs.joinToString(", ")})"
                        }
                        else ->
                            tryEmitCoroutineIntrinsicExtCall(calleeKt, "kxs_sa_r") { args.value } ?: run {
                                val sn = CppNamingPolicy.toSnakeCase(calleeKt)
                                "([&](auto&& kxs_sa_cr) -> decltype(auto) { " +
                                    "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_cr)>> || " +
                                    "requires { kxs_sa_cr.operator->(); }) return kxs_sa_cr->$sn($joinedArgs); " +
                                    "else return kxs_sa_cr.$sn($joinedArgs); }(kxs_sa_r))"
                            }
                    }
                } else {
                    val propKt = FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName ?: "value"
                    val sameFileExtProp = bundle.symbols.propertiesByName[propKt]
                    when {
                        sameFileExtProp != null && sameFileExtProp.receiverTypeFrameIndex != null ->
                            "${sameFileExtProp.cppName}(kxs_sa_r)"
                        propKt == "size" || propKt == "length" ->
                            "([&](auto&& kxs_sa_pr) { if constexpr (requires { kxs_sa_pr->size(); }) " +
                                "return kxs_sa_pr->size(); else return kxs_sa_pr.size(); }(kxs_sa_r))"
                        else -> {
                            val field = resolveMemberFieldOrPropName(propKt, parts[0])
                            "([&](auto&& kxs_sa_pr) -> decltype(auto) { " +
                                "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_pr)>> || " +
                                "requires { kxs_sa_pr.operator->(); }) return kxs_sa_pr->$field; " +
                                "else return kxs_sa_pr.$field; }(kxs_sa_r))"
                        }
                    }
                }
            }

        return "([&](auto&& $temp) { " +
            "using KxsSaRawT = std::remove_cvref_t<decltype($temp)>; " +
            "const bool kxs_has = [&]() -> bool { " +
            "if constexpr (std::is_same_v<KxsSaRawT, std::any>) " +
            "return $temp.has_value() && $temp.type() != typeid(std::nullptr_t); " +
            "else return static_cast<bool>($temp); }(); " +
            "auto kxs_sa_u = [](auto&& kxs_v) -> decltype(auto) { " +
            "if constexpr (std::is_same_v<std::remove_cvref_t<decltype(kxs_v)>, std::exception_ptr>) " +
            "return kxs_v; else return kxs_unwrap_opt(kxs_v); }; " +
            "auto kxs_sa_fn = [&](auto&& kxs_sa_r) -> decltype(auto) { return $safeInvokeExpr; }; " +
            "using KxsSaRes = decltype(kxs_sa_fn(kxs_sa_u($temp))); " +
            "if constexpr (std::is_void_v<KxsSaRes>) { if (kxs_has) { kxs_sa_fn(kxs_sa_u($temp)); } } " +
            "else if constexpr (std::is_pointer_v<std::decay_t<KxsSaRes>> || " +
            "std::is_same_v<std::decay_t<KxsSaRes>, std::exception_ptr> || " +
            "std::is_same_v<std::decay_t<KxsSaRes>, std::any> || " +
            "requires { typename std::decay_t<KxsSaRes>::element_type; } || " +
            "requires { typename std::decay_t<KxsSaRes>::value_type; " +
            "std::declval<std::decay_t<KxsSaRes>&>().has_value(); }) { " +
            "return kxs_has ? kxs_sa_fn(kxs_sa_u($temp)) : std::decay_t<KxsSaRes>{}; " +
            "} else { " +
            "return kxs_has ? std::optional<std::decay_t<KxsSaRes>>(kxs_sa_fn(kxs_sa_u($temp))) " +
            ": std::nullopt; " +
            "} }($lhsCode))"
    }

    private fun emitCallExpr(
        receiverCode: String?,
        receiverIsPointer: Boolean,
        callFrame: PacketFrame,
        receiverFrame: PacketFrame? = null,
    ): String {
        val calleeKt = extractCalleeSimpleName(callFrame)
        // Kotlin `contract { callsInPlace(...) }` is a compile-time-only Kotlin intrinsic; elide cleanly in C++23.
        if (calleeKt == "contract" &&
            receiverCode == null &&
            resolveBoundName("contract") == null &&
            "contract" !in bundle.symbols.functionsByName &&
            FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID) != null
        ) {
            return "((void)0)"
        }
        val typeArgPos =
            if (receiverCode == null && calleeKt in VALUE_CONTAINER_CALL_NAMES) {
                CppTypePosition.VALUE
            } else {
                CppTypePosition.TYPE_ARGUMENT
            }
        val typeArgs = extractCallTypeArgs(callFrame, typeArgPos)
        val rawCallArgs = extractRawCallArgs(callFrame)
        val argFrames = rawCallArgs.map { it.exprFrame }
        val args by lazy(LazyThreadSafetyMode.NONE) {
            argFrames.map { withExpectedTargetType(null) { emitExpression(it) } }
        }
        val targetCppForInference = currentExpectedTargetType ?: enclosingFunctionReturnType

        // Builtin Kotlin standard functions / pre-conditions / collections
        if (receiverCode == null) {
            if (calleeKt in localPolymorphicClasses) {
                includes.addSystem("memory")
                val structName = CppNamingPolicy.toTypeName(calleeKt)
                return "std::make_shared<$structName>(${args.joinToString(", ")})"
            }
            val recvSub = receiverSubstitution
            val localExtCpp = localExtensionFunctions[calleeKt]
            if (localExtCpp != null && recvSub != null) {
                val allArgs = ArrayList<String>(args.size + 1)
                allArgs.add(recvSub)
                allArgs.addAll(args)
                return "$localExtCpp(${allArgs.joinToString(", ")})"
            }
            if (recvSub != null) {
                if (recvSub != "this->kxs_outer_" && recvSub != "kxs_outer_in") {
                    if (!bundle.isDocExample &&
                        extensionReceiverSimpleName in setOf("Array", "List", "MutableList", "ArrayList")
                    ) {
                        when (calleeKt) {
                            "get" ->
                                if (args.size == 1) {
                                    includes.addSystem("cstddef")
                                    return "$recvSub[static_cast<std::size_t>(${args[0]})]"
                                }
                            "set" ->
                                if (args.size == 2) {
                                    includes.addSystem("cstddef")
                                    return "($recvSub[static_cast<std::size_t>(${args[0]})] = ${args[1]})"
                                }
                        }
                    }
                    when (calleeKt) {
                        "isEmpty" -> return "$recvSub.empty()"
                        "isNotEmpty" -> return "(!$recvSub.empty())"
                        "toTypedArray" -> return recvSub
                        "getOrElse" ->
                            if (!bundle.isDocExample && extensionReceiverSimpleName == "Result" && args.size == 1) {
                                includes.needsResultHelpersDef = true
                                return "kxs_result_get_or_else($recvSub, ${args[0]})"
                            }
                        "inv" ->
                            if (!bundle.isDocExample && args.isEmpty()) {
                                return "(~$recvSub)"
                            }
                    }
                }
                val extRecvCls = extensionReceiverClass
                if (extRecvCls != null &&
                    calleeKt !in anonObjectOwnMethods &&
                    classDeclaresMethod(extRecvCls, calleeKt)
                ) {
                    val op = if (extensionReceiverIsPointer) "->" else "."
                    val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                    val tmplKw =
                        if (tSuffix.isNotEmpty() && isDependentTemplateReceiver(recvSub, null)) {
                            "template "
                        } else {
                            ""
                        }
                    return "$recvSub$op$tmplKw${CppNamingPolicy.toSnakeCase(calleeKt)}$tSuffix(${args.joinToString(", ")})"
                }
                if (resolveBoundName(calleeKt) == null) {
                    tryEmitCoroutineIntrinsicExtCall(calleeKt, recvSub) { args }?.let { return it }
                }
                val extSimple = extensionReceiverSimpleName
                if (extRecvCls == null &&
                    extSimple != null &&
                    resolveBoundName(calleeKt) == null &&
                    calleeKt !in bundle.symbols.functionsByName
                ) {
                    val op = if (extensionReceiverIsPointer) "->" else "."
                    val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                    if (extSimple == "CoroutineContext" && calleeKt == "get" && !bundle.isDocExample && args.size == 1) {
                        includes.needsContextOpsDef = true
                        return "kotlin::coroutines::kxs_ctx_get($recvSub, ${args[0]})"
                    }
                    if (extSimple == "Duration" &&
                        !bundle.isDocExample &&
                        args.size == 1 &&
                        calleeKt in setOf("plus", "minus", "times", "div")
                    ) {
                        val binOp =
                            when (calleeKt) {
                                "plus" -> "+"
                                "minus" -> "-"
                                "times" -> "*"
                                else -> "/"
                            }
                        return "($recvSub $binOp ${args[0]})"
                    }
                    if (calleeKt in KNOWN_EXTERNAL_RECEIVER_METHODS[extSimple].orEmpty()) {
                        val tmplKw =
                            if (tSuffix.isNotEmpty() && isDependentTemplateReceiver(recvSub, null)) {
                                "template "
                            } else {
                                ""
                            }
                        val cppCallee = CppNamingPolicy.toSnakeCase(calleeKt)
                        return "$recvSub$op$tmplKw$cppCallee$tSuffix(${args.joinToString(", ")})"
                    }
                }
            }
            when (calleeKt) {
                "Array" -> {
                    if (args.size == 2) {
                        includes.addSystem("cstdint")
                        includes.addSystem("cstddef")
                        includes.addSystem("vector")
                        val countVar = freshTemp("kxs_n")
                        val initVar = freshTemp("kxs_init")
                        val arrVar = freshTemp("kxs_arr")
                        val idxVar = freshTemp("kxs_i")
                        val elemType = typeArgs.firstOrNull() ?: "decltype($initVar(0))"
                        return "([&]() { const auto $countVar = ${args[0]}; auto $initVar = ${args[1]}; " +
                            "std::vector<$elemType> $arrVar; $arrVar.reserve(static_cast<std::size_t>($countVar)); " +
                            "for (std::int32_t $idxVar = 0; $idxVar < $countVar; ++$idxVar) { $arrVar.push_back($initVar($idxVar)); } " +
                            "return $arrVar; }())"
                    }
                }
                "check", "require" -> {
                    includes.addSystem("stdexcept")
                    val cond = args.getOrNull(0) ?: "true"
                    val rawMsg = args.getOrNull(1)
                    val msgIsLambda =
                        argFrames.getOrNull(1)?.let { unwrapTransparentFrame(it).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID } == true
                    val msg =
                        when {
                            rawMsg == null -> "\"Failed $calleeKt\""
                            msgIsLambda -> "($rawMsg)()"
                            else -> rawMsg
                        }
                    val excType = if (calleeKt == "require") "std::invalid_argument" else "std::logic_error"
                    return "(($cond) ? void(0) : throw $excType($msg))"
                }
                "checkNotNull", "requireNotNull" -> {
                    includes.addSystem("stdexcept")
                    val v = args.getOrNull(0) ?: "nullptr"
                    val rawMsg = args.getOrNull(1)
                    val msgIsLambda =
                        argFrames.getOrNull(1)?.let { unwrapTransparentFrame(it).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID } == true
                    val msg =
                        when {
                            rawMsg == null -> "\"Required value was null\""
                            msgIsLambda -> "($rawMsg)()"
                            else -> rawMsg
                        }
                    return "([&]() { auto&& kxs_nn = $v; if (!kxs_nn) throw std::invalid_argument($msg); return *kxs_nn; }())"
                }
                "error" -> {
                    includes.addSystem("stdexcept")
                    val rawMsg = args.firstOrNull()
                    val msgIsLambda =
                        argFrames.firstOrNull()?.let { unwrapTransparentFrame(it).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID } == true
                    val msg =
                        when {
                            rawMsg == null -> "\"Illegal state\""
                            msgIsLambda -> "($rawMsg)()"
                            else -> rawMsg
                        }
                    return "throw std::logic_error($msg)"
                }
                "println" -> {
                    includes.addSystem("iostream")
                    val v = args.firstOrNull()
                    return if (v != null) "(std::cout << $v << '\\n')" else "(std::cout << '\\n')"
                }
                "print" -> {
                    includes.addSystem("iostream")
                    val v = args.firstOrNull() ?: "\"\""
                    return "(std::cout << $v)"
                }
                "listOf", "mutableListOf", "arrayListOf" -> {
                    includes.addSystem("vector")
                    val elemT = typeArgs.firstOrNull()
                    return if (elemT != null) {
                        "std::vector<$elemT>{${args.joinToString(", ")}}"
                    } else {
                        "std::vector{${args.joinToString(", ")}}"
                    }
                }
                "emptyList" -> {
                    if (typeArgs.isEmpty() && !bundle.isDocExample) {
                        includes.needsEmptyContainersDef = true
                        return "KxsEmptyList{}"
                    }
                    includes.addSystem("vector")
                    val elemT = typeArgs.firstOrNull() ?: "std::any"
                    return "std::vector<$elemT>{}"
                }
                "setOf", "mutableSetOf", "hashSetOf" -> {
                    includes.addSystem("unordered_set")
                    val elemT = typeArgs.firstOrNull()
                    return if (elemT != null) {
                        "std::unordered_set<$elemT>{${args.joinToString(", ")}}"
                    } else {
                        "std::unordered_set{${args.joinToString(", ")}}"
                    }
                }
                "emptySet" -> {
                    if (typeArgs.isEmpty() && !bundle.isDocExample) {
                        includes.needsEmptyContainersDef = true
                        return "KxsEmptySet{}"
                    }
                    includes.addSystem("unordered_set")
                    val elemT = typeArgs.firstOrNull() ?: "std::any"
                    return "std::unordered_set<$elemT>{}"
                }
                "emptyMap" -> {
                    if (typeArgs.isEmpty() && !bundle.isDocExample) {
                        includes.needsEmptyContainersDef = true
                        return "KxsEmptyMap{}"
                    }
                    includes.addSystem("unordered_map")
                    val kT = typeArgs.getOrNull(0) ?: "std::string"
                    val vT = typeArgs.getOrNull(1) ?: "std::any"
                    return "std::unordered_map<$kT, $vT>{}"
                }
                "mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf" -> {
                    includes.addSystem("unordered_map")
                    val kT = typeArgs.getOrNull(0)
                    val vT = typeArgs.getOrNull(1)
                    return if (kT != null && vT != null) {
                        "std::unordered_map<$kT, $vT>{${args.joinToString(", ")}}"
                    } else {
                        "std::unordered_map{${args.joinToString(", ")}}"
                    }
                }
                "arrayOfNulls" -> {
                    includes.addSystem("cstddef")
                    includes.addSystem("vector")
                    val inferredFromRet =
                        if (typeArgs.isEmpty() &&
                            enclosingFunctionReturnType != null &&
                            enclosingFunctionReturnType.startsWith("std::vector<") &&
                            enclosingFunctionReturnType.endsWith(">")
                        ) {
                            enclosingFunctionReturnType
                                .removePrefix("std::vector<")
                                .removeSuffix(">")
                                .trim()
                        } else {
                            null
                        }
                    val elemT = typeArgs.firstOrNull() ?: inferredFromRet ?: "void*"
                    val n = args.firstOrNull() ?: "0"
                    return "std::vector<$elemT>(static_cast<std::size_t>($n))"
                }
                "arrayOf", "intArrayOf", "longArrayOf", "byteArrayOf" -> {
                    includes.addSystem("cstdint")
                    includes.addSystem("vector")
                    val elemT =
                        when (calleeKt) {
                            "intArrayOf" -> "std::int32_t"
                            "longArrayOf" -> "std::int64_t"
                            "byteArrayOf" -> "std::int8_t"
                            else -> typeArgs.firstOrNull()
                        }
                    return if (elemT != null) {
                        "std::vector<$elemT>{${args.joinToString(", ")}}"
                    } else {
                        "std::vector{${args.joinToString(", ")}}"
                    }
                }
                "atomic" -> {
                    if (!bundle.isDocExample) {
                        val elemT = typeArgs.firstOrNull() ?: "void*"
                        val initV = args.firstOrNull() ?: "{}"
                        if (elemT.startsWith("std::shared_ptr<")) {
                            includes.needsAtomicReferenceDef = true
                            includes.addSystem("mutex")
                            return "KxsAtomicReference<$elemT>{$initV}"
                        }
                        includes.addSystem("atomic")
                        return "std::atomic<$elemT>{$initV}"
                    }
                }
                "autoreleasepool" -> {
                    if (!bundle.isDocExample) {
                        val blk = args.firstOrNull() ?: "[](){}"
                        return "($blk)()"
                    }
                }
                "dispatch_async" -> {
                    if (!bundle.isDocExample) {
                        includes.needsDarwinDispatchHelpers = true
                        includes.addSystem("type_traits")
                        includes.addSystem("utility")
                        return "kxs_dispatch_async(${args.joinToString(", ")})"
                    }
                }
                "CFRunLoopTimerCreateWithHandler" -> {
                    if (!bundle.isDocExample) {
                        includes.needsDarwinDispatchHelpers = true
                        includes.addSystem("type_traits")
                        includes.addSystem("utility")
                        return "kxs_cf_timer_create_with_handler(${args.joinToString(", ")})"
                    }
                }
                "createCleaner" -> {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    return "create_cleaner(${args.joinToString(", ")})"
                }
                "suspendCoroutineUninterceptedOrReturn" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.needsSuspendBoxHelpersDef = true
                        val tArg = typeArgs.firstOrNull()
                        return if (tArg != null) {
                            "kxs_suspend_coroutine_unintercepted_or_return<$tArg>(${args[0]})"
                        } else {
                            "kxs_suspend_coroutine_unintercepted_or_return(${args[0]})"
                        }
                    }
                "sequence" ->
                    if (!bundle.isDocExample &&
                        argFrames.size == 1 &&
                        unwrapTransparentFrame(argFrames[0]).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
                    ) {
                        val lambdaFrame = unwrapTransparentFrame(argFrames[0])
                        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
                        val lambdaBody =
                            if (fnLit != null) {
                                FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
                            } else {
                                lambdaFrame
                            }
                        includes.addSystem("vector")
                        includes.needsSequenceAlias = true
                        val elemT =
                            typeArgs.firstOrNull()
                                ?: extractOuterTemplateArgsForBase(targetCppForInference, "KxsSequence")?.firstOrNull()
                                ?: extractOuterTemplateArgsForBase(targetCppForInference, "Sequence")?.firstOrNull()
                                ?: "std::any"
                        if (elemT == "std::any") includes.addSystem("any")
                        val seqVar = freshTemp("kxs_seq")
                        val prevSeqVar = sequenceYieldTargetVar
                        sequenceYieldTargetVar = seqVar
                        return try {
                            withinExpressionIife {
                                buildString {
                                    append("([&]() {\n")
                                    append("    KxsSequence<$elemT> $seqVar{};\n")
                                    appendInlineLambdaBodyStatements(
                                        this,
                                        lambdaBody,
                                        "    ",
                                        targetVar = null,
                                        targetReturn = false,
                                    )
                                    append("    return ").append(seqVar).append(";\n")
                                    append("}())")
                                }
                            }
                        } finally {
                            sequenceYieldTargetVar = prevSeqVar
                        }
                    }
                "yield" -> {
                    val seqTarget = sequenceYieldTargetVar
                    if (!bundle.isDocExample && args.size == 1 && seqTarget != null) {
                        return "$seqTarget.push_back(${args[0]})"
                    }
                }
                "run" ->
                    if (argFrames.size == 1 &&
                        unwrapTransparentFrame(argFrames[0]).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
                    ) {
                        return "(${args[0]})()"
                    }
                "with" ->
                    if (argFrames.size == 2 &&
                        unwrapTransparentFrame(argFrames[1]).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
                    ) {
                        val lambdaFrame = unwrapTransparentFrame(argFrames[1])
                        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
                        val lambdaBody =
                            if (fnLit != null) {
                                FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
                            } else {
                                lambdaFrame
                            }
                        val recvTemp = freshTemp("kxs_recv")
                        return withinExpressionIife {
                            val nestedEmitter = buildInlineScopeEmitter(argFrames[0], args[0], recvTemp)
                            val code =
                                buildString {
                                    append("([&]() {\n")
                                    append("    auto&& $recvTemp = ${args[0]};\n")
                                    nestedEmitter.appendInlineLambdaBodyStatements(
                                        this,
                                        lambdaBody,
                                        "    ",
                                        targetVar = null,
                                        targetReturn = true,
                                    )
                                    append("}())")
                                }
                            synthCounter = nestedEmitter.synthCounter
                            code
                        }
                    }
                "StringBuilder" ->
                    if (!bundle.isDocExample && args.size <= 1) {
                        includes.needsStringBuilderDef = true
                        return "KxsStringBuilder(${args.joinToString(", ")})"
                    }
                "buildString" ->
                    if (!bundle.isDocExample &&
                        argFrames.isNotEmpty() &&
                        argFrames.size <= 2 &&
                        unwrapTransparentFrame(argFrames.last()).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
                    ) {
                        includes.addSystem("cstdint")
                        includes.addSystem("string")
                        includes.addSystem("type_traits")
                        val lambdaFrame = unwrapTransparentFrame(argFrames.last())
                        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
                        val lambdaBody =
                            if (fnLit != null) {
                                FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
                            } else {
                                lambdaFrame
                            }
                        val sbVar = freshTemp("kxs_sb")
                        val sbStruct = freshTemp("KxsSb")
                        val capReserve =
                            if (argFrames.size == 2) {
                                includes.addSystem("cstddef")
                                " $sbVar.s.reserve(static_cast<std::size_t>(${args[0]}));"
                            } else {
                                ""
                            }
                        return withinExpressionIife {
                            withLexicalScope {
                                receiverSubstitution = sbVar
                                extensionReceiverClass = null
                                extensionReceiverIsPointer = false
                                extensionReceiverSimpleName = "StringBuilder"
                                buildString {
                                    append("([&]() {\n")
                                    append("    struct ").append(sbStruct).append(" {\n")
                                    append("        std::string s;\n")
                                    append("        template <typename KxsV>\n")
                                    append("        ").append(sbStruct).append("& append(const KxsV& v) {\n")
                                    append("            using D = std::remove_cvref_t<KxsV>;\n")
                                    append("            if constexpr (std::is_same_v<D, char> || std::is_same_v<D, char16_t>) s.push_back(static_cast<char>(v));\n")
                                    append("            else if constexpr (std::is_same_v<D, bool>) s += (v ? \"true\" : \"false\");\n")
                                    append("            else if constexpr (std::is_convertible_v<D, std::string>) s += v;\n")
                                    append("            else if constexpr (std::is_arithmetic_v<D>) s += std::to_string(v);\n")
                                    append("            else if constexpr (requires { v.to_string(); }) s += v.to_string();\n")
                                    append("            else if constexpr (requires { v->to_string(); }) s += (v ? v->to_string() : std::string(\"null\"));\n")
                                    append("            else if constexpr (requires { v.has_value(); *v; }) { if (v.has_value()) append(*v); else s += \"null\"; }\n")
                                    append("            return *this;\n")
                                    append("        }\n")
                                    append("        ").append(sbStruct).append("& append_line() { s.push_back('\\n'); return *this; }\n")
                                    append("        template <typename KxsV>\n")
                                    append("        ").append(sbStruct).append("& append_line(const KxsV& v) { append(v); s.push_back('\\n'); return *this; }\n")
                                    append("        void clear() { s.clear(); }\n")
                                    append("        std::int32_t length() const noexcept { return static_cast<std::int32_t>(s.size()); }\n")
                                    append("        std::string to_string() const { return s; }\n")
                                    append("    } $sbVar;$capReserve\n")
                                    appendInlineLambdaBodyStatements(this, lambdaBody, "    ", targetVar = null, targetReturn = false)
                                    append("    return ").append(sbVar).append(".s;\n")
                                    append("}())")
                                }
                            }
                        }
                    }
                "min", "minOf" ->
                    if (!bundle.isDocExample &&
                        args.size == 2 &&
                        (calleeKt == "minOf" || calleeKt !in bundle.symbols.functionsByName)
                    ) {
                        includes.addSystem("algorithm")
                        return "std::min(${args[0]}, ${args[1]})"
                    }
                "max", "maxOf" ->
                    if (!bundle.isDocExample &&
                        args.size == 2 &&
                        (calleeKt == "maxOf" || calleeKt !in bundle.symbols.functionsByName)
                    ) {
                        includes.addSystem("algorithm")
                        return "std::max(${args[0]}, ${args[1]})"
                    }
                "compareValuesBy" ->
                    if (!bundle.isDocExample && args.size >= 3) {
                        includes.addSystem("functional")
                        includes.addSystem("type_traits")
                        includes.addSystem("utility")
                        val steps =
                            args.drop(2).joinToString(" ") { sel ->
                                "{ auto&& kxs_sel = $sel; " +
                                    "decltype(auto) kxs_va = std::invoke(kxs_sel, kxs_a); " +
                                    "decltype(auto) kxs_vb = std::invoke(kxs_sel, kxs_b); " +
                                    "if constexpr (requires { kxs_va == nullptr; kxs_va->compare_to(*kxs_vb); }) { " +
                                    "if (kxs_va != kxs_vb) { if (kxs_va == nullptr) return -1; if (kxs_vb == nullptr) return 1; " +
                                    "if (int kxs_c = kxs_va->compare_to(*kxs_vb); kxs_c != 0) return kxs_c; } " +
                                    "} else if constexpr (requires { kxs_va.compare_to(kxs_vb); }) { " +
                                    "if (int kxs_c = kxs_va.compare_to(kxs_vb); kxs_c != 0) return kxs_c; " +
                                    "} else { " +
                                    "if (kxs_va < kxs_vb) return -1; if (kxs_vb < kxs_va) return 1; " +
                                    "} }"
                            }
                        return "([&](const auto& kxs_a_raw, const auto& kxs_b_raw) -> int { " +
                            "auto kxs_unwrap = [](const auto& kxs_x) -> decltype(auto) { " +
                            "using KxsX = std::remove_cvref_t<decltype(kxs_x)>; " +
                            "if constexpr (std::is_pointer_v<KxsX> || requires { kxs_x.get(); *kxs_x; }) return *kxs_x; " +
                            "else return kxs_x; }; " +
                            "const auto& kxs_a = kxs_unwrap(kxs_a_raw); " +
                            "const auto& kxs_b = kxs_unwrap(kxs_b_raw); " +
                            "$steps return 0; }(${args[0]}, ${args[1]}))"
                    }
                "ArrayList" ->
                    if (!bundle.isDocExample && args.size <= 1) {
                        includes.addSystem("vector")
                        val elemT =
                            typeArgs.firstOrNull()
                                ?: extractOuterTemplateArgsForBase(targetCppForInference, "std::vector")?.firstOrNull()
                                ?: "void*"
                        if (args.isEmpty()) {
                            return "std::vector<$elemT>{}"
                        }
                        includes.addSystem("cstddef")
                        includes.addSystem("type_traits")
                        return "([&](auto&& kxs_al_arg) { std::vector<$elemT> kxs_v; " +
                            "if constexpr (std::is_integral_v<std::decay_t<decltype(kxs_al_arg)>>) { " +
                            "kxs_v.reserve(static_cast<std::size_t>(kxs_al_arg)); " +
                            "} else { " +
                            "for (auto&& kxs_it : kxs_al_arg) kxs_v.push_back(kxs_it); " +
                            "} return kxs_v; }(${args[0]}))"
                    }
                "HashSet", "LinkedHashSet" ->
                    if (!bundle.isDocExample && args.size <= 1) {
                        includes.addSystem("unordered_set")
                        val elemT =
                            typeArgs.firstOrNull()
                                ?: extractOuterTemplateArgsForBase(targetCppForInference, "std::unordered_set")
                                    ?.firstOrNull()
                                ?: "void*"
                        if (args.isEmpty()) {
                            return "std::unordered_set<$elemT>{}"
                        }
                        includes.addSystem("cstddef")
                        includes.addSystem("type_traits")
                        return "([&](auto&& kxs_hs_arg) { std::unordered_set<$elemT> kxs_s; " +
                            "if constexpr (std::is_integral_v<std::decay_t<decltype(kxs_hs_arg)>>) { " +
                            "kxs_s.reserve(static_cast<std::size_t>(kxs_hs_arg)); " +
                            "} else { " +
                            "for (auto&& kxs_it : kxs_hs_arg) kxs_s.insert(kxs_it); " +
                            "} return kxs_s; }(${args[0]}))"
                    }
                "HashMap", "LinkedHashMap" ->
                    if (!bundle.isDocExample && args.size <= 1) {
                        includes.addSystem("unordered_map")
                        val inferredMapArgs =
                            if (typeArgs.size < 2) {
                                extractOuterTemplateArgsForBase(targetCppForInference, "std::unordered_map")
                            } else {
                                null
                            }
                        val kT = typeArgs.getOrNull(0) ?: inferredMapArgs?.getOrNull(0) ?: "std::string"
                        val vT = typeArgs.getOrNull(1) ?: inferredMapArgs?.getOrNull(1) ?: "void*"
                        if (args.isEmpty()) {
                            return "std::unordered_map<$kT, $vT>{}"
                        }
                        includes.addSystem("cstddef")
                        includes.addSystem("type_traits")
                        return "([&](auto&& kxs_hm_arg) { std::unordered_map<$kT, $vT> kxs_m; " +
                            "if constexpr (std::is_integral_v<std::decay_t<decltype(kxs_hm_arg)>>) { " +
                            "kxs_m.reserve(static_cast<std::size_t>(kxs_hm_arg)); " +
                            "} else { " +
                            "kxs_m.insert(kxs_hm_arg.begin(), kxs_hm_arg.end()); " +
                            "} return kxs_m; }(${args[0]}))"
                    }
            }
        } else {
            val localExtCpp = localExtensionFunctions[calleeKt]
            if (localExtCpp != null) {
                val allArgs = ArrayList<String>(args.size + 1)
                allArgs.add(receiverCode)
                allArgs.addAll(args)
                return "$localExtCpp(${allArgs.joinToString(", ")})"
            }
            // Check if calling an extension function or rich-enum helper declared in the same file (`fun Recv.ext(...)`)!
            val sameFileFns = bundle.symbols.functionsByName[calleeKt].orEmpty()
            val extCandidates =
                sameFileFns.filter { fn ->
                    isAccessibleExtensionFn(fn) &&
                        (
                            fn.receiverTypeFrameIndex != null ||
                                fn.enclosingClassFrameIndex?.let {
                                    bundle.symbols.classesByFrame[it]?.kind == KtClassKind.RICH_ENUM
                                } == true
                        )
                }
            if (extCandidates.isNotEmpty()) {
                val recvSimple = receiverFrame?.let { inferExpressionSimpleTypeName(it) }
                val matchingExt = extCandidates.filter { doesFnMatchRawArgs(it.valueParams, rawCallArgs) }
                val matchedExtFn =
                    matchingExt.firstOrNull { fn ->
                        recvSimple != null &&
                            fn.receiverTypeFrameIndex?.let {
                                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) == recvSimple
                            } == true
                    } ?: matchingExt.firstOrNull() ?: extCandidates.first()
                val boundMap = mapCallArgsToParamIndices(matchedExtFn.valueParams, rawCallArgs)
                val renderedArgs =
                    if (boundMap != null) {
                        emitAlignedArgsForParams(matchedExtFn.valueParams, boundMap)
                    } else {
                        args
                    }
                val effTypeArgs =
                    inferMissingFunctionTypeArgs(
                        fn = matchedExtFn,
                        explicitTypeArgs = typeArgs,
                        targetCppForInference = targetCppForInference,
                        receiverFrame = receiverFrame,
                    )
                val allArgs = ArrayList<String>(renderedArgs.size + 1)
                allArgs.add(receiverCode)
                allArgs.addAll(renderedArgs)
                val callName =
                    if (matchedExtFn.kotlinName == "invoke") "invoke" else formatExtensionFnCallTarget(matchedExtFn)
                val tSuffix = if (effTypeArgs.isNotEmpty()) "<${effTypeArgs.joinToString(", ")}>" else ""
                return "$callName$tSuffix(${allArgs.joinToString(", ")})"
            }
            if (!bundle.isDocExample && calleeKt == "toCancellationException" && enclosingClass != null) {
                val prefix = if (enclosingClass.typeParams.isNotEmpty()) "this->" else ""
                val extraArgs =
                    if (rawCallArgs.size == 1 &&
                        unwrapTransparentFrame(rawCallArgs[0].exprFrame).nodeTypeId == KtNodeTypes.NULL_ID
                    ) {
                        includes.addSystem("optional")
                        listOf("std::nullopt")
                    } else {
                        args
                    }
                val allArgs = listOf(receiverCode) + extraArgs
                return "${prefix}to_cancellation_exception(${allArgs.joinToString(", ")})"
            }
            if (receiverCode != "this") {
                tryEmitCoroutineIntrinsicExtCall(calleeKt, receiverCode) { args }?.let { return it }
            }

            // Standard member method mappings
            val op = if (receiverIsPointer) "->" else "."
            when (calleeKt) {
                "invoke" ->
                    if (bundle.isDocExample) {
                        return if (receiverIsPointer) {
                            "(*$receiverCode)(${args.joinToString(", ")})"
                        } else {
                            "$receiverCode(${args.joinToString(", ")})"
                        }
                    } else {
                        includes.addSystem("type_traits")
                        includes.addSystem("utility")
                        val callArgs = args.joinToString(", ")
                        return "([&](auto&& kxs_inv) -> decltype(auto) { " +
                            "if constexpr (requires { (*kxs_inv)($callArgs); }) return (*kxs_inv)($callArgs); " +
                            "else if constexpr (requires { kxs_inv->invoke($callArgs); }) " +
                            "return kxs_inv->invoke($callArgs); " +
                            "else if constexpr (requires { kxs_inv($callArgs); }) return kxs_inv($callArgs); " +
                            "else return kxs_inv.invoke($callArgs); }($receiverCode))"
                    }
                "get" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("type_traits")
                        return "([&](auto&& kxs_gr) -> decltype(auto) { " +
                            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_gr)>> || requires { kxs_gr.operator->(); }) { " +
                            "if constexpr (requires { kxs_gr->get_value(); }) return kxs_gr->get_value(); " +
                            "else return kxs_gr->get(); " +
                            "} else { " +
                            "if constexpr (requires { kxs_gr.get_value(); }) return kxs_gr.get_value(); " +
                            "else return kxs_gr.get(); } }($receiverCode))"
                    }
                "set" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.addSystem("type_traits")
                        includes.addSystem("utility")
                        return "([&](auto&& kxs_sr, auto&& kxs_sv) { " +
                            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sr)>> || requires { kxs_sr.operator->(); }) { " +
                            "if constexpr (requires { kxs_sr->set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); }) kxs_sr->set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); " +
                            "else if constexpr (std::is_null_pointer_v<std::remove_cvref_t<decltype(kxs_sv)>> && requires { kxs_sr->set_value({}); }) kxs_sr->set_value({}); " +
                            "else kxs_sr->set(std::forward<decltype(kxs_sv)>(kxs_sv)); " +
                            "} else { " +
                            "if constexpr (requires { kxs_sr.set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); }) kxs_sr.set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); " +
                            "else if constexpr (std::is_null_pointer_v<std::remove_cvref_t<decltype(kxs_sv)>> && requires { kxs_sr.set_value({}); }) kxs_sr.set_value({}); " +
                            "else kxs_sr.set(std::forward<decltype(kxs_sv)>(kxs_sv)); } }($receiverCode, ${args[0]}))"
                    }
                "getAndSet" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        return "$receiverCode${op}get_and_set(${args[0]})"
                    }
                "compareAndSet" ->
                    if (!bundle.isDocExample && args.size == 2) {
                        return "$receiverCode${op}compare_and_set(${args[0]}, ${args[1]})"
                    }
                "isEmpty" -> return "$receiverCode${op}empty()"
                "isNotEmpty" -> return "(!$receiverCode${op}empty())"
                "toTypedArray" -> return if (receiverIsPointer) "(*$receiverCode)" else receiverCode
                "add" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.addSystem("type_traits")
                        return "([&](auto&& kxs_ac, auto&& kxs_av) -> bool { " +
                            "auto kxs_add_one = [&](auto& kxs_c) -> bool { " +
                            "if constexpr (requires { kxs_c.push_back(kxs_av); }) { " +
                            "kxs_c.push_back(kxs_av); return true; " +
                            "} else if constexpr (requires { kxs_c.insert(kxs_av); }) { " +
                            "auto kxs_ir = kxs_c.insert(kxs_av); " +
                            "if constexpr (requires { kxs_ir.second; }) return kxs_ir.second; " +
                            "else return true; " +
                            "} else { return kxs_c.add(kxs_av); } }; " +
                            "using KxsAC = std::remove_cvref_t<decltype(kxs_ac)>; " +
                            "if constexpr (std::is_pointer_v<KxsAC> || requires { kxs_ac.operator->(); }) { " +
                            "return kxs_add_one(*kxs_ac); " +
                            "} else { " +
                            "return kxs_add_one(kxs_ac); } }($receiverCode, ${args[0]}))"
                    } else {
                        return "$receiverCode${op}push_back(${args.joinToString(", ")})"
                    }
                "toString" -> return "$receiverCode${op}to_string()"
                "hashCode" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        return emitHashCodeExpr(receiverCode)
                    }
                "getOrThrow" -> return "$receiverCode${op}get_or_throw()"
                "getOrElse" ->
                    if (!bundle.isDocExample && args.size == 1 && calleeKt !in bundle.symbols.functionsByName) {
                        includes.needsResultHelpersDef = true
                        return "kxs_result_get_or_else($receiverCode, ${args[0]})"
                    }
                "toState" ->
                    if (!bundle.isDocExample && calleeKt !in bundle.symbols.functionsByName) {
                        includes.addProject("kotlinx/coroutines/CompletionState.hpp")
                        val fnName =
                            if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                                "to_state"
                            } else {
                                "kotlinx::coroutines::to_state"
                            }
                        return "$fnName(${(listOf(receiverCode) + args).joinToString(", ")})"
                    }
                "unboxState" ->
                    if (!bundle.isDocExample &&
                        args.isEmpty() &&
                        enclosingClass?.kotlinName != "JobSupport" &&
                        calleeKt !in bundle.symbols.functionsByName
                    ) {
                        val qual =
                            CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                                kotlinName = "unboxState",
                                cppBaseName = "unbox_state",
                                currentNs = bundle.symbols.namespaceSegments,
                                includes = includes,
                            ) ?: "kotlinx::coroutines::unbox_state"
                        return "$qual($receiverCode)"
                    }
                "resumeWithException" ->
                    if (!bundle.isDocExample &&
                        args.size == 1 &&
                        calleeKt !in bundle.symbols.functionsByName
                    ) {
                        includes.needsCoroutineIntrinsicsBridgeDef = true
                        includes.needsResultHelpersDef = true
                        includes.addProject("kotlinx/coroutines/Continuation.hpp")
                        return "kxs_resume_with_exception($receiverCode, ${args[0]})"
                    }
                "resume" ->
                    if (!bundle.isDocExample &&
                        args.size == 1 &&
                        unwrapTransparentFrame(argFrames[0]).nodeTypeId != KtNodeTypes.LAMBDA_EXPRESSION_ID &&
                        calleeKt !in bundle.symbols.functionsByName
                    ) {
                        includes.needsCoroutineIntrinsicsBridgeDef = true
                        includes.addProject("kotlinx/coroutines/Continuation.hpp")
                        return "kxs_resume_cont($receiverCode, ${args[0]})"
                    }
                "inv" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        return "(~$receiverCode)"
                    }
                "exceptionOrNull" -> return "$receiverCode${op}exception_or_null()"
                "addSuppressed" -> if (!bundle.isDocExample) return "((void)0)"
                "printStackTrace" -> if (!bundle.isDocExample && args.isEmpty()) return "((void)0)"
                "toBoolean" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        return emitStringToBooleanExpr(receiverCode)
                    }
                "toIntOrNull" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("charconv")
                        includes.addSystem("cstdint")
                        includes.addSystem("optional")
                        includes.addSystem("string")
                        return "([&](const std::string& kxs_s) -> std::optional<std::int32_t> { " +
                            "std::int32_t kxs_v = 0; auto [kxs_p, kxs_ec] = std::from_chars(kxs_s.data(), kxs_s.data() + kxs_s.size(), kxs_v); " +
                            "if (kxs_ec == std::errc{} && kxs_p == kxs_s.data() + kxs_s.size()) return kxs_v; return std::nullopt; }($receiverCode))"
                    }
                "toLongOrNull" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("charconv")
                        includes.addSystem("cstdint")
                        includes.addSystem("optional")
                        includes.addSystem("string")
                        return "([&](const std::string& kxs_s) -> std::optional<std::int64_t> { " +
                            "std::int64_t kxs_v = 0; auto [kxs_p, kxs_ec] = std::from_chars(kxs_s.data(), kxs_s.data() + kxs_s.size(), kxs_v); " +
                            "if (kxs_ec == std::errc{} && kxs_p == kxs_s.data() + kxs_s.size()) return kxs_v; return std::nullopt; }($receiverCode))"
                    }
                "toLong" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("cstdint")
                        return "static_cast<std::int64_t>($receiverCode)"
                    }
                "toInt" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("cstdint")
                        return "static_cast<std::int32_t>($receiverCode)"
                    }
                "toDouble" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        return "static_cast<double>($receiverCode)"
                    }
                "toFloat" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        return "static_cast<float>($receiverCode)"
                    }
                "toShort" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("cstdint")
                        return "static_cast<std::int16_t>($receiverCode)"
                    }
                "toByte" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("cstdint")
                        return "static_cast<std::int8_t>($receiverCode)"
                    }
                "coerceAtLeast" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.addSystem("algorithm")
                        includes.addSystem("type_traits")
                        return "([&](auto kxs_v, auto kxs_min) { " +
                            "using KxsCT = std::common_type_t<std::decay_t<decltype(kxs_v)>, " +
                            "std::decay_t<decltype(kxs_min)>>; " +
                            "return std::max<KxsCT>(static_cast<KxsCT>(kxs_v), " +
                            "static_cast<KxsCT>(kxs_min)); }($receiverCode, ${args[0]}))"
                    }
                "coerceAtMost" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.addSystem("algorithm")
                        includes.addSystem("type_traits")
                        return "([&](auto kxs_v, auto kxs_max) { " +
                            "using KxsCT = std::common_type_t<std::decay_t<decltype(kxs_v)>, " +
                            "std::decay_t<decltype(kxs_max)>>; " +
                            "return std::min<KxsCT>(static_cast<KxsCT>(kxs_v), " +
                            "static_cast<KxsCT>(kxs_max)); }($receiverCode, ${args[0]}))"
                    }
                "coerceIn" ->
                    if (!bundle.isDocExample && args.size == 2) {
                        includes.addSystem("algorithm")
                        includes.addSystem("type_traits")
                        return "([&](auto kxs_v, auto kxs_lo, auto kxs_hi) { " +
                            "using KxsCT = std::common_type_t<std::decay_t<decltype(kxs_v)>, " +
                            "std::decay_t<decltype(kxs_lo)>, std::decay_t<decltype(kxs_hi)>>; " +
                            "return std::clamp<KxsCT>(static_cast<KxsCT>(kxs_v), " +
                            "static_cast<KxsCT>(kxs_lo), static_cast<KxsCT>(kxs_hi)); }" +
                            "($receiverCode, ${args[0]}, ${args[1]}))"
                    }
                "let" ->
                    if (args.size == 1) {
                        return "(${args[0]})($receiverCode)"
                    }
                "also" ->
                    if (args.size == 1) {
                        val alsoTemp = freshTemp("kxs_also")
                        return "([&]() { auto&& $alsoTemp = $receiverCode; (${args[0]})($alsoTemp); return $alsoTemp; }())"
                    }
                "apply", "run" ->
                    if (argFrames.size == 1) {
                        emitScopeApplyOrRunCall(
                            calleeKt,
                            receiverFrame,
                            receiverCode,
                            argFrames,
                            lazy(LazyThreadSafetyMode.NONE) { args },
                        )?.let { return it }
                    }
                "takeIf", "takeUnless" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.addSystem("optional")
                        includes.addSystem("type_traits")
                        val condPrefix = if (calleeKt == "takeUnless") "!" else ""
                        return "([&]() { auto&& kxs_ti = $receiverCode; " +
                            "using KxsTi = std::decay_t<decltype(kxs_ti)>; " +
                            "if constexpr (std::is_pointer_v<KxsTi> || requires { kxs_ti.operator->(); } || requires { kxs_ti.has_value(); }) { " +
                            "return ($condPrefix(${args[0]})(kxs_ti)) ? kxs_ti : KxsTi{}; " +
                            "} else { " +
                            "return ($condPrefix(${args[0]})(kxs_ti)) ? std::optional<KxsTi>(kxs_ti) : std::optional<KxsTi>{}; " +
                            "} }())"
                    }
                "forEach" ->
                    if (args.size == 1) {
                        return emitForEachExpr(receiverCode, args[0])
                    }
                "plus" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.addSystem("type_traits")
                        return "([&](const auto& kxs_pl, const auto& kxs_pr) { " +
                            "if constexpr (requires { kxs_pl->plus(kxs_pr); }) return kxs_pl->plus(kxs_pr); " +
                            "else if constexpr (requires { kxs_pl.plus(kxs_pr); }) return kxs_pl.plus(kxs_pr); " +
                            "else return (kxs_pl + kxs_pr); }($receiverCode, ${args[0]}))"
                    }
            }
        }

        // Constructor invocation vs function/method invocation
        val effectiveCalleeKt = bundle.symbols.importAliases[calleeKt] ?: calleeKt
        val sameFileClass = lookupAccessibleClassByName(calleeKt) ?: lookupAccessibleClassByName(effectiveCalleeKt)
        val sameFileFnExists =
            calleeKt in bundle.symbols.functionsByName || effectiveCalleeKt in bundle.symbols.functionsByName
        val isSameFileConcreteCtor =
            sameFileClass != null &&
                sameFileClass.kind != KtClassKind.INTERFACE &&
                (!PacketFlags.hasFlag(stream.frames[sameFileClass.frameIndex].flags, PacketFlags.MOD_ABSTRACT) || !sameFileFnExists)
        val isExternalTypeCtor =
            sameFileClass == null &&
                effectiveCalleeKt.firstOrNull()?.isUpperCase() == true &&
                !sameFileFnExists &&
                (bundle.isDocExample || effectiveCalleeKt !in KNOWN_PASCAL_FACTORY_FUNCTIONS)
        val isCtorCall = receiverCode == null && (isSameFileConcreteCtor || isExternalTypeCtor)
        val effectiveArgs by lazy(LazyThreadSafetyMode.NONE) {
            if (isCtorCall && sameFileClass != null && sameFileClass.isInner) {
                val outerPtr = resolveOuterPointerExpr(sameFileClass.enclosingClassFrameIndex)
                if (outerPtr != null) listOf(outerPtr) + args else args
            } else {
                args
            }
        }

        if (receiverCode == null && !isCtorCall && enclosingClass != null && enclosingClass.isInner) {
            val hasOwnMethod =
                enclosingClass.memberFunctionFrameIndices.any {
                    bundle.symbols.functionsByFrame[it]?.kotlinName == calleeKt
                }
            if (!hasOwnMethod) {
                var cur = enclosingClass.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
                while (cur != null) {
                    val outerFn =
                        cur.memberFunctionFrameIndices
                            .mapNotNull { bundle.symbols.functionsByFrame[it] }
                            .firstOrNull { it.kotlinName == calleeKt }
                    if (outerFn != null) {
                        val chain = resolveOuterChainPrefix(cur.frameIndex).orEmpty()
                        val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                        return "$chain${outerFn.cppName}$tSuffix(${effectiveArgs.joinToString(", ")})"
                    }
                    if (!cur.isInner) break
                    cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
                }
            }
        }

        val activeRecvSub = receiverSubstitution
        if (receiverCode == null && !isCtorCall && activeRecvSub != null && resolveCallableBoundName(calleeKt) == null) {
            tryEmitCoroutineIntrinsicExtCall(calleeKt, activeRecvSub) { effectiveArgs }?.let { return it }
            val implicitCandidates =
                bundle.symbols.functionsByName[calleeKt]
                    ?.filter { it.receiverTypeFrameIndex != null && isAccessibleExtensionFn(it) }
                    .orEmpty()
            val implicitExtFn =
                implicitCandidates.firstOrNull { doesFnMatchRawArgs(it.valueParams, rawCallArgs) }
                    ?: implicitCandidates.firstOrNull()
            if (implicitExtFn != null) {
                val boundMap = mapCallArgsToParamIndices(implicitExtFn.valueParams, rawCallArgs)
                val renderedArgs =
                    if (boundMap != null) {
                        emitAlignedArgsForParams(implicitExtFn.valueParams, boundMap)
                    } else {
                        effectiveArgs
                    }
                val effTypeArgs =
                    inferMissingFunctionTypeArgs(
                        fn = implicitExtFn,
                        explicitTypeArgs = typeArgs,
                        targetCppForInference = targetCppForInference,
                        receiverFrame = null,
                    )
                val allArgs = ArrayList<String>(renderedArgs.size + 1)
                allArgs.add(activeRecvSub)
                allArgs.addAll(renderedArgs)
                val tSuffix = if (effTypeArgs.isNotEmpty()) "<${effTypeArgs.joinToString(", ")}>" else ""
                return "${formatExtensionFnCallTarget(implicitExtFn)}$tSuffix(${allArgs.joinToString(", ")})"
            }
        }
        if (receiverCode == null &&
            !isCtorCall &&
            activeRecvSub == null &&
            enclosingClass != null &&
            resolveCallableBoundName(calleeKt) == null
        ) {
            val hasOwnOrOuterOrCompanionMemberFn =
                generateSequence(enclosingClass) { cur ->
                    if (cur.isInner) cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] } else null
                }.any { cur ->
                    cur.memberFunctionFrameIndices.any { bundle.symbols.functionsByFrame[it]?.kotlinName == calleeKt }
                } ||
                    enclosingClass.companionFrameIndex
                        ?.let { bundle.symbols.classesByFrame[it] }
                        ?.memberFunctionFrameIndices
                        ?.any { bundle.symbols.functionsByFrame[it]?.kotlinName == calleeKt } == true
            if (!hasOwnOrOuterOrCompanionMemberFn) {
                val intrinsicThis =
                    if (!bundle.isDocExample && isSharedThisClass(enclosingClass.kotlinName)) {
                        includes.needsSharedFromThisDef = true
                        includes.addSystem("memory")
                        includes.addSystem("type_traits")
                        "kxs_shared_from_this(this)"
                    } else {
                        "this"
                    }
                tryEmitCoroutineIntrinsicExtCall(calleeKt, intrinsicThis) { effectiveArgs }?.let { return it }
                val sameFileThisExtCandidates =
                    bundle.symbols.functionsByName[calleeKt]
                        ?.filter { fn ->
                            fn.receiverTypeFrameIndex != null &&
                                isAccessibleExtensionFn(fn) &&
                                doesClassMatchExtensionReceiver(enclosingClass, fn.receiverTypeFrameIndex)
                        }.orEmpty()
                val sameFileThisExtFn =
                    sameFileThisExtCandidates.firstOrNull { doesFnMatchRawArgs(it.valueParams, rawCallArgs) }
                        ?: sameFileThisExtCandidates.firstOrNull()
                if (sameFileThisExtFn != null) {
                    val recvCppT =
                        translateType(
                            stream.frames[sameFileThisExtFn.receiverTypeFrameIndex!!],
                            CppTypePosition.EXTENSION_RECEIVER,
                        )
                    val selfArg =
                        when {
                            recvCppT.startsWith("std::shared_ptr<") -> {
                                includes.needsSharedFromThisDef = true
                                includes.addSystem("memory")
                                includes.addSystem("type_traits")
                                "kxs_shared_from_this(this)"
                            }
                            recvCppT.endsWith("*") -> "this"
                            else -> "(*this)"
                        }
                    val boundMap = mapCallArgsToParamIndices(sameFileThisExtFn.valueParams, rawCallArgs)
                    val renderedArgs =
                        if (boundMap != null) {
                            emitAlignedArgsForParams(sameFileThisExtFn.valueParams, boundMap)
                        } else {
                            effectiveArgs
                        }
                    val effTypeArgs =
                        inferMissingFunctionTypeArgs(
                            fn = sameFileThisExtFn,
                            explicitTypeArgs = typeArgs,
                            targetCppForInference = targetCppForInference,
                            receiverFrame = null,
                        )
                    val allArgs = ArrayList<String>(renderedArgs.size + 1)
                    allArgs.add(selfArg)
                    allArgs.addAll(renderedArgs)
                    val tSuffix = if (effTypeArgs.isNotEmpty()) "<${effTypeArgs.joinToString(", ")}>" else ""
                    return "${formatExtensionFnCallTarget(sameFileThisExtFn)}$tSuffix(${allArgs.joinToString(", ")})"
                }
            }
        }

        val callArgs by lazy(LazyThreadSafetyMode.NONE) {
            if (receiverCode != null &&
                (calleeKt == "minusKey" || calleeKt == "get") &&
                rawCallArgs.size == 1 &&
                effectiveArgs.size == 1 &&
                effectiveArgs[0].endsWith("::Key::instance()")
            ) {
                includes.addSystem("memory")
                listOf("std::addressof(${effectiveArgs[0]})")
            } else {
                effectiveArgs
            }
        }

        val privateZeroArgCollidingFnName: String? =
            if ((receiverCode == null || receiverCode == "this") &&
                !isCtorCall &&
                enclosingClass != null &&
                rawCallArgs.isEmpty()
            ) {
                val mFn =
                    enclosingClass.memberFunctionFrameIndices
                        .asSequence()
                        .mapNotNull { bundle.symbols.functionsByFrame[it] }
                        .firstOrNull {
                            it.kotlinName == calleeKt &&
                                it.valueParams.isEmpty() &&
                                PacketFlags.hasFlag(stream.frames[it.frameIndex].flags, PacketFlags.MOD_PRIVATE)
                        }
                val hasSameNameProp =
                    enclosingClass.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == calleeKt } ||
                        enclosingClass.memberPropertyFrameIndices.any {
                            bundle.symbols.propertiesByFrame[it]?.kotlinName == calleeKt
                        }
                val collidesWithProp = mFn != null && hasSameNameProp
                if (collidesWithProp) "${mFn.cppName}_fn" else null
            } else {
                null
            }

        var matchedCallFn: KtFunctionSymbol? = null
        val targetName =
            when {
                isCtorCall -> {
                    val baseType = sameFileClass?.cppName ?: CppNamingPolicy.toTypeName(effectiveCalleeKt)
                    if (sameFileClass == null && !bundle.isDocExample) {
                        CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = effectiveCalleeKt,
                            cppBaseName = baseType,
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: baseType
                    } else {
                        baseType
                    }
                }
                receiverCode == null -> {
                    if (!bundle.isDocExample && resolveLocalTypeSimpleName(calleeKt) == "CoroutineStart") {
                        includes.addProject("kotlinx/coroutines/CoroutineStart.hpp")
                        val fnTarget =
                            if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                                "invoke"
                            } else {
                                "kotlinx::coroutines::invoke"
                            }
                        val boundTarget =
                            resolveBoundName(calleeKt)
                                ?: resolveUnqualifiedPropertyAccess(calleeKt)
                                ?: CppNamingPolicy.toSnakeCase(calleeKt)
                        val allInvokeArgs = listOf(boundTarget) + callArgs
                        return "$fnTarget(${allInvokeArgs.joinToString(", ")})"
                    }
                    val sameFileFnList =
                        bundle.symbols.functionsByName[calleeKt] ?: bundle.symbols.functionsByName[effectiveCalleeKt]
                    val callableBound = resolveCallableBoundName(calleeKt)
                    if (callableBound != null) {
                        val recvParamArity = callableReceiverParamArity(calleeKt)
                        if (recvParamArity != null && rawCallArgs.size == recvParamArity) {
                            val implicitRecv =
                                receiverSubstitution
                                    ?: if (enclosingClass != null) {
                                        if (!bundle.isDocExample && isSharedThisClass(enclosingClass.kotlinName)) {
                                            includes.needsSharedFromThisDef = true
                                            includes.addSystem("memory")
                                            includes.addSystem("type_traits")
                                            "kxs_shared_from_this(this)"
                                        } else {
                                            "(*this)"
                                        }
                                    } else {
                                        null
                                    }
                            if (implicitRecv != null) {
                                val allArgs = listOf(implicitRecv) + callArgs
                                return "$callableBound(${allArgs.joinToString(", ")})"
                            }
                        }
                    }
                    val callableProp = if (callableBound == null) resolveEnclosingCallableProperty(calleeKt) else null
                    val enclosingMemberFns =
                        enclosingClass
                            ?.memberFunctionFrameIndices
                            ?.asSequence()
                            ?.mapNotNull { bundle.symbols.functionsByFrame[it] }
                            ?.toList()
                    val matchedEnclosingMemberFn =
                        enclosingMemberFns?.firstOrNull {
                            it.kotlinName == calleeKt && doesFnMatchRawArgs(it.valueParams, rawCallArgs)
                        }
                    val enclosingMemberFn =
                        matchedEnclosingMemberFn ?: enclosingMemberFns?.firstOrNull { it.kotlinName == calleeKt }
                    val companionMemberFnIndices =
                        enclosingClass
                            ?.companionFrameIndex
                            ?.let { bundle.symbols.classesByFrame[it] }
                            ?.memberFunctionFrameIndices
                    val isCompanionFn =
                        companionMemberFnIndices?.any {
                            bundle.symbols.functionsByFrame[it]?.kotlinName == calleeKt
                        } == true
                    val importedCompanionOwner =
                        if (callableBound == null &&
                            callableProp == null &&
                            privateZeroArgCollidingFnName == null &&
                            enclosingMemberFn == null &&
                            !isCompanionFn
                        ) {
                            bundle.symbols.importedCompanionMembers[calleeKt]
                        } else {
                            null
                        }
                    val importedCompanionQualified =
                        if (importedCompanionOwner != null) {
                            val ownerSameCls = lookupAccessibleClassByName(importedCompanionOwner)
                            val ownerBase =
                                ownerSameCls?.cppName
                                    ?: if (!bundle.isDocExample) {
                                        CppTypeTranslator.includeAndQualifyKnownType(
                                            simpleName = importedCompanionOwner,
                                            cppBaseName = CppNamingPolicy.toTypeName(importedCompanionOwner),
                                            currentNs = bundle.symbols.namespaceSegments,
                                            includes = includes,
                                        ) ?: CppNamingPolicy.toTypeName(importedCompanionOwner)
                                    } else {
                                        CppNamingPolicy.toTypeName(importedCompanionOwner)
                                    }
                            val inferredOwnerArgs =
                                extractOuterTemplateArgsForBase(targetCppForInference, ownerBase)
                            val ownerQual =
                                when {
                                    ownerSameCls != null && enclosingClass?.frameIndex == ownerSameCls.frameIndex ->
                                        null
                                    inferredOwnerArgs != null ->
                                        "$ownerBase<${inferredOwnerArgs.joinToString(", ")}>"
                                    ownerSameCls != null && ownerSameCls.typeParams.isNotEmpty() ->
                                        "$ownerBase<${ownerSameCls.typeParams.joinToString(", ") { "void*" }}>"
                                    ownerSameCls == null &&
                                        importedCompanionOwner in
                                        setOf(
                                            "Channel",
                                            "ChannelResult",
                                            "SendChannel",
                                            "ReceiveChannel",
                                            "BufferedChannel",
                                            "BroadcastChannel",
                                        ) -> "$ownerBase<void*>"
                                    else -> ownerBase
                                }
                            val compMemberCpp =
                                ownerSameCls
                                    ?.companionFrameIndex
                                    ?.let { bundle.symbols.classesByFrame[it] }
                                    ?.memberFunctionFrameIndices
                                    ?.asSequence()
                                    ?.mapNotNull { bundle.symbols.functionsByFrame[it] }
                                    ?.firstOrNull { it.kotlinName == calleeKt }
                                    ?.cppName
                                    ?: CppNamingPolicy.toSnakeCase(calleeKt)
                            if (ownerQual != null) "$ownerQual::$compMemberCpp" else compMemberCpp
                        } else {
                            null
                        }
                    val sameFileTopCandidates =
                        sameFileFnList
                            ?.filter { it.enclosingClassFrameIndex == null && it.receiverTypeFrameIndex == null }
                            .orEmpty()
                    val matchedSameFileTopFn =
                        sameFileTopCandidates.firstOrNull { doesFnMatchRawArgs(it.valueParams, rawCallArgs) }
                    if (callableBound == null &&
                        callableProp == null &&
                        privateZeroArgCollidingFnName == null
                    ) {
                        matchedCallFn =
                            when {
                                matchedEnclosingMemberFn != null -> matchedEnclosingMemberFn
                                enclosingMemberFn == null &&
                                    importedCompanionQualified == null &&
                                    !isCompanionFn &&
                                    matchedSameFileTopFn != null -> matchedSameFileTopFn
                                else -> null
                            }
                    }
                    val knownQualifiedFn =
                        if (callableBound == null &&
                            callableProp == null &&
                            privateZeroArgCollidingFnName == null &&
                            enclosingMemberFn == null &&
                            importedCompanionQualified == null &&
                            sameFileFnList == null &&
                            !bundle.isDocExample &&
                            !isVerbatimCIdentifier(effectiveCalleeKt)
                        ) {
                            CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                                kotlinName = effectiveCalleeKt,
                                cppBaseName = CppNamingPolicy.toSnakeCase(effectiveCalleeKt),
                                currentNs = bundle.symbols.namespaceSegments,
                                includes = includes,
                            )
                        } else {
                            null
                        }
                    val rawTarget =
                        callableBound
                            ?: callableProp
                            ?: privateZeroArgCollidingFnName
                            ?: enclosingMemberFn?.cppName
                            ?: importedCompanionQualified
                            ?: matchedSameFileTopFn?.cppName
                            ?: sameFileFnList?.firstOrNull()?.cppName
                            ?: knownQualifiedFn
                            ?: if (isVerbatimCIdentifier(effectiveCalleeKt)) {
                                recordExternalCHeaderForIdentifier(effectiveCalleeKt)
                                effectiveCalleeKt
                            } else {
                                CppNamingPolicy.toSnakeCase(effectiveCalleeKt)
                            }
                    val isSameFileTopLevelFn = sameFileFnList?.any { it.enclosingClassFrameIndex == null } == true
                    val needsDependentThis =
                        !bundle.isDocExample &&
                            enclosingClass != null &&
                            enclosingClass.typeParams.isNotEmpty() &&
                            enclosingClass.superTypeFrameIndices.isNotEmpty() &&
                            callableBound == null &&
                            callableProp == null &&
                            importedCompanionQualified == null &&
                            !isSameFileTopLevelFn &&
                            !isCompanionFn &&
                            knownQualifiedFn == null &&
                            !isVerbatimCIdentifier(effectiveCalleeKt) &&
                            "::" !in rawTarget
                    if (needsDependentThis) "this->$rawTarget" else rawTarget
                }
                else -> {
                    privateZeroArgCollidingFnName ?: run {
                        val aliasedCallee = bundle.symbols.importAliases[calleeKt] ?: calleeKt
                        if (isVerbatimCIdentifier(aliasedCallee)) {
                            recordExternalCHeaderForIdentifier(aliasedCallee)
                            aliasedCallee
                        } else {
                            CppNamingPolicy.toSnakeCase(aliasedCallee)
                        }
                    }
                }
            }

        val matchedCtorParams =
            if (isCtorCall &&
                sameFileClass != null &&
                doesFnMatchRawArgs(sameFileClass.primaryConstructorParams, rawCallArgs)
            ) {
                sameFileClass.primaryConstructorParams
            } else {
                null
            }
        val rawAlignedArgs =
            when {
                matchedCtorParams != null -> {
                    val aligned =
                        emitAlignedArgsForParams(
                            matchedCtorParams,
                            mapCallArgsToParamIndices(matchedCtorParams, rawCallArgs)!!,
                        )
                    if (sameFileClass?.isInner == true) {
                        val outerPtr = resolveOuterPointerExpr(sameFileClass.enclosingClassFrameIndex)
                        if (outerPtr != null) listOf(outerPtr) + aligned else aligned
                    } else {
                        aligned
                    }
                }
                matchedCallFn != null ->
                    emitAlignedArgsForParams(
                        matchedCallFn.valueParams,
                        mapCallArgsToParamIndices(matchedCallFn.valueParams, rawCallArgs)!!,
                    )
                else -> callArgs
            }
        val finalCallArgs =
            if (!bundle.isDocExample &&
                !isCtorCall &&
                calleeKt in setOf("cancelInternal", "cancelCoroutine") &&
                (receiverCode == null || receiverCode == "this") &&
                rawAlignedArgs.size == 1 &&
                !rawAlignedArgs[0].trimStart().startsWith("kxs_to_exception_ptr(")
            ) {
                includes.addSystem("exception")
                includes.addSystem("optional")
                includes.addSystem("type_traits")
                includes.addSystem("utility")
                includes.needsResultHelpersDef = true
                listOf("kxs_to_exception_ptr(${rawAlignedArgs[0]})")
            } else {
                rawAlignedArgs
            }
        val inferredCtorTypeArgs =
            if (isCtorCall && typeArgs.isEmpty()) {
                val directArgs = extractOuterTemplateArgsForBase(targetCppForInference, targetName)
                if (!directArgs.isNullOrEmpty()) {
                    directArgs
                } else {
                    val expectedArity =
                        sameFileClass?.typeParams?.size ?: CppTypeTranslator.knownTemplateArity(effectiveCalleeKt)
                    val isSharedTarget =
                        effectiveCalleeKt in KNOWN_GENERIC_SHARED_CLASSES ||
                            sameFileClass?.let { isSharedThisClass(it.kotlinName) } == true
                    if (expectedArity == 1) {
                        val unwrappedSharedArg =
                            targetCppForInference
                                ?.trim()
                                ?.removePrefix("const ")
                                ?.removeSuffix("&")
                                ?.trim()
                                ?.takeIf { isSharedTarget && it.startsWith("std::shared_ptr<") }
                                ?.let { extractAnyOuterTemplateArgs(it) }
                                ?.takeIf { it.size == 1 }
                        when {
                            unwrappedSharedArg != null -> unwrappedSharedArg
                            isSharedTarget && enclosingFunction?.typeParams?.size == 1 ->
                                listOf(CppNamingPolicy.toTypeName(enclosingFunction.typeParams[0].name))
                            isSharedTarget && enclosingClass?.typeParams?.size == 1 ->
                                listOf(CppNamingPolicy.toTypeName(enclosingClass.typeParams[0].name))
                            else -> emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }
            } else {
                emptyList()
            }
        val inferredFnTypeArgs =
            when {
                !isCtorCall && matchedCallFn != null ->
                    inferMissingFunctionTypeArgs(
                        fn = matchedCallFn,
                        explicitTypeArgs = typeArgs,
                        targetCppForInference = targetCppForInference,
                        receiverFrame = null,
                    )
                !isCtorCall &&
                    calleeKt == "Channel" &&
                    typeArgs.isEmpty() &&
                    targetCppForInference != null ->
                    inferChannelFactoryElementTypeArg(targetCppForInference)
                else -> emptyList()
            }
        val effectiveTypeArgs =
            when {
                typeArgs.isNotEmpty() -> typeArgs
                isCtorCall -> inferredCtorTypeArgs
                else -> inferredFnTypeArgs
            }
        val tSuffix = if (effectiveTypeArgs.isNotEmpty()) "<${effectiveTypeArgs.joinToString(", ")}>" else ""
        val isSharedCtorTarget =
            CppTypeTranslator.isSharedHierarchyType(effectiveCalleeKt) ||
                effectiveCalleeKt in bundle.symbols.sharedHierarchyTypeNames
        val isNonAbstractSameFileClass =
            sameFileClass == null ||
                !PacketFlags.hasFlag(stream.frames[sameFileClass.frameIndex].flags, PacketFlags.MOD_ABSTRACT)
        if (isCtorCall &&
            !bundle.isDocExample &&
            isSharedCtorTarget &&
            sameFileClass?.kind != KtClassKind.INTERFACE &&
            isNonAbstractSameFileClass
        ) {
            includes.addSystem("memory")
            val hasClassTypeParams =
                (sameFileClass != null && sameFileClass.typeParams.isNotEmpty()) ||
                    (sameFileClass == null && effectiveCalleeKt in KNOWN_GENERIC_SHARED_CLASSES)
            val isGenericSharedWithoutArgs = effectiveTypeArgs.isEmpty() && hasClassTypeParams
            return if (isGenericSharedWithoutArgs) {
                "std::shared_ptr(new $targetName(${finalCallArgs.joinToString(", ")}))"
            } else {
                "std::make_shared<$targetName$tSuffix>(${finalCallArgs.joinToString(", ")})"
            }
        }
        return if (receiverCode == null) {
            "$targetName$tSuffix(${finalCallArgs.joinToString(", ")})"
        } else {
            val op = if (receiverIsPointer) "->" else "."
            val tmplKw =
                if (tSuffix.isNotEmpty() && isDependentTemplateReceiver(receiverCode, receiverFrame)) {
                    "template "
                } else {
                    ""
                }
            "$receiverCode$op$tmplKw$targetName$tSuffix(${finalCallArgs.joinToString(", ")})"
        }
    }

    private fun isDependentTemplateReceiver(
        recvExpr: String,
        recvFrame: PacketFrame?,
    ): Boolean {
        if (bundle.isDocExample || activeTypeParams.isEmpty()) return false
        if (recvFrame != null && inferExpressionSimpleTypeName(recvFrame) in activeTypeParams) {
            return true
        }
        return activeTypeParams.any { tp ->
            Regex("""\b${Regex.escape(tp)}\b""").containsMatchIn(recvExpr)
        }
    }

    private fun isEnclosingOrSuperClass(
        cls: KtClassSymbol?,
        targetClsFrameIdx: Int,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (cls == null || !visited.add(cls.frameIndex)) return false
        if (cls.frameIndex == targetClsFrameIdx) return true
        for (superIdx in cls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            val superCls = lookupAccessibleClassByName(superSimple) ?: continue
            if (isEnclosingOrSuperClass(superCls, targetClsFrameIdx, visited)) return true
        }
        return false
    }

    private fun isAccessibleExtensionFn(fn: KtFunctionSymbol): Boolean =
        fn.enclosingClassFrameIndex == null ||
            (enclosingClass != null && isEnclosingOrSuperClass(enclosingClass, fn.enclosingClassFrameIndex))

    private fun formatExtensionFnCallTarget(fn: KtFunctionSymbol): String {
        val isDependentMemberExt =
            !bundle.isDocExample &&
                enclosingClass != null &&
                fn.enclosingClassFrameIndex != null &&
                isEnclosingOrSuperClass(enclosingClass, fn.enclosingClassFrameIndex) &&
                enclosingClass.typeParams.isNotEmpty() &&
                enclosingClass.superTypeFrameIndices.isNotEmpty() &&
                !inDefaultParameterExpr
        return if (isDependentMemberExt) "this->${fn.cppName}" else fn.cppName
    }

    private fun emitHashCodeExpr(recvExpr: String): String {
        includes.addSystem("any")
        includes.addSystem("cstdint")
        includes.addSystem("exception")
        includes.addSystem("functional")
        includes.addSystem("memory")
        includes.addSystem("type_traits")
        includes.addSystem("typeinfo")
        return "([&](const auto& kxs_hr) -> std::int32_t { " +
            "using KxsH = std::remove_cvref_t<decltype(kxs_hr)>; " +
            "if constexpr (std::is_same_v<KxsH, std::exception_ptr>) { " +
            "return kxs_hr ? static_cast<std::int32_t>(1) : static_cast<std::int32_t>(0); " +
            "} else if constexpr (std::is_same_v<KxsH, std::any>) { " +
            "return (kxs_hr.has_value() && kxs_hr.type() != typeid(std::nullptr_t)) ? " +
            "static_cast<std::int32_t>(kxs_hr.type().hash_code()) : static_cast<std::int32_t>(0); " +
            "} else if constexpr (requires { kxs_hr->hash_code(); }) { " +
            "return kxs_hr ? static_cast<std::int32_t>(kxs_hr->hash_code()) : static_cast<std::int32_t>(0); " +
            "} else if constexpr (requires { kxs_hr.hash_code(); }) { " +
            "return static_cast<std::int32_t>(kxs_hr.hash_code()); " +
            "} else if constexpr (std::is_pointer_v<KxsH>) { " +
            "return static_cast<std::int32_t>(std::hash<const void*>{}(kxs_hr)); " +
            "} else if constexpr (requires { kxs_hr.get(); }) { " +
            "return static_cast<std::int32_t>(std::hash<const void*>{}(kxs_hr.get())); " +
            "} else if constexpr (requires { std::hash<KxsH>{}(kxs_hr); }) { " +
            "return static_cast<std::int32_t>(std::hash<KxsH>{}(kxs_hr)); " +
            "} else { return static_cast<std::int32_t>(0); } }($recvExpr))"
    }

    private fun splitTopLevelCommaList(inner: String): List<String> {
        val parts = ArrayList<String>()
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var start = 0
        for (i in 0 until inner.length) {
            when (inner[i]) {
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                ',' ->
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                        parts.add(inner.substring(start, i).trim())
                        start = i + 1
                    }
            }
        }
        val last = inner.substring(start).trim()
        if (last.isNotEmpty()) parts.add(last)
        return parts
    }

    private fun extractOuterTemplateArgsForBase(
        rawCppType: String?,
        expectedBase: String,
    ): List<String>? {
        var cur =
            (rawCppType ?: return null)
                .trim()
                .removePrefix("const ")
                .removeSuffix("&")
                .trim()
        while (true) {
            val unwrapped =
                when {
                    cur.startsWith("std::optional<") && cur.endsWith(">") ->
                        cur.removePrefix("std::optional<").dropLast(1).trim()
                    cur.startsWith("std::shared_ptr<") && cur.endsWith(">") ->
                        cur.removePrefix("std::shared_ptr<").dropLast(1).trim()
                    else -> break
                }
            cur = unwrapped
        }
        val ltIdx = cur.indexOf('<')
        if (ltIdx < 0 || !cur.endsWith(">")) return null
        val head = cur.substring(0, ltIdx).trim()
        val expectedSimple = expectedBase.substringAfterLast("::")
        if (head != expectedBase && head.substringAfterLast("::") != expectedSimple) return null
        val inner = cur.substring(ltIdx + 1, cur.length - 1).trim()
        if (inner.isEmpty()) return null
        return splitTopLevelCommaList(inner)
    }

    private fun tryEmitCoroutineIntrinsicExtCall(
        calleeKt: String,
        recvExpr: String,
        argProvider: () -> List<String>,
    ): String? {
        if (bundle.isDocExample || calleeKt in bundle.symbols.functionsByName) return null
        val args by lazy(LazyThreadSafetyMode.NONE) { argProvider() }
        return when (calleeKt) {
            "intercepted" ->
                if (args.isEmpty()) {
                    includes.needsCoroutineIntrinsicsBridgeDef = true
                    "kxs_intercepted($recvExpr)"
                } else {
                    null
                }
            "createCoroutineUnintercepted" ->
                if (args.size == 1 || args.size == 2) {
                    includes.needsCoroutineIntrinsicsBridgeDef = true
                    "kxs_create_coroutine_unintercepted($recvExpr, ${args.joinToString(", ")})"
                } else {
                    null
                }
            "startCoroutineUninterceptedOrReturn" ->
                if (args.size == 1 || args.size == 2) {
                    includes.needsCoroutineIntrinsicsBridgeDef = true
                    "kxs_start_coroutine_unintercepted_or_return($recvExpr, ${args.joinToString(", ")})"
                } else {
                    null
                }
            "resumeCancellableWith" ->
                if (args.size == 1) {
                    includes.addProject("kotlinx/coroutines/internal/DispatchedContinuation.hpp")
                    includes.addSystem("type_traits")
                    "([&](auto&& kxs_rc, auto&& kxs_rv) { " +
                        "if constexpr (requires { kxs_rc->resume_cancellable_with(kxs_rv); }) kxs_rc->resume_cancellable_with(kxs_rv); " +
                        "else if constexpr (requires { kxs_rc.resume_cancellable_with(kxs_rv); }) kxs_rc.resume_cancellable_with(kxs_rv); " +
                        "else kotlinx::coroutines::internal::resume_cancellable_with(kxs_rc, kxs_rv); " +
                        "}($recvExpr, ${args[0]}))"
                } else {
                    null
                }
            "startCoroutine" ->
                if (args.size == 1 || args.size == 2) {
                    includes.needsCoroutineIntrinsicsBridgeDef = true
                    "kxs_start_coroutine($recvExpr, ${args.joinToString(", ")})"
                } else {
                    null
                }
            "startCoroutineCancellable" ->
                if (args.size == 1 || args.size == 2) {
                    includes.addProject("kotlinx/coroutines/intrinsics/Cancellable.hpp")
                    "kotlinx::coroutines::intrinsics::start_coroutine_cancellable($recvExpr, ${args.joinToString(", ")})"
                } else {
                    null
                }
            "startCoroutineUndispatched" ->
                if (args.size == 2) {
                    includes.addProject("kotlinx/coroutines/intrinsics/Undispatched.hpp")
                    "kotlinx::coroutines::intrinsics::start_coroutine_undispatched($recvExpr, ${args.joinToString(", ")})"
                } else {
                    null
                }
            "startUndispatchedOrReturn" ->
                if (args.size == 2) {
                    includes.addProject("kotlinx/coroutines/intrinsics/Undispatched.hpp")
                    "kotlinx::coroutines::intrinsics::start_undispatched_or_return($recvExpr, ${args.joinToString(", ")})"
                } else {
                    null
                }
            "startUndispatchedOrReturnIgnoreTimeout" ->
                if (args.size == 2) {
                    includes.addProject("kotlinx/coroutines/intrinsics/Undispatched.hpp")
                    "kotlinx::coroutines::intrinsics::start_undispatched_or_return_ignore_timeout($recvExpr, ${args.joinToString(", ")})"
                } else {
                    null
                }
            "newCoroutineContext" ->
                if (args.size == 1) {
                    val qual =
                        CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                            kotlinName = "newCoroutineContext",
                            cppBaseName = "new_coroutine_context",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "kotlinx::coroutines::new_coroutine_context"
                    "$qual($recvExpr, ${args[0]})"
                } else {
                    null
                }
            "cancelConsumed" ->
                if (args.size == 1) {
                    val qual =
                        CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                            kotlinName = "cancelConsumed",
                            cppBaseName = "cancel_consumed",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "kotlinx::coroutines::channels::cancel_consumed"
                    includes.addSystem("exception")
                    includes.addSystem("optional")
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    includes.needsResultHelpersDef = true
                    "$qual($recvExpr, kxs_to_exception_ptr(${args[0]}))"
                } else {
                    null
                }
            "ensureActive" ->
                if (args.isEmpty()) {
                    val qual =
                        CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                            kotlinName = "ensureActive",
                            cppBaseName = "ensure_active",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "kotlinx::coroutines::ensure_active"
                    includes.addProject("kotlinx/coroutines/CoroutineScope.hpp")
                    "$qual($recvExpr)"
                } else {
                    null
                }
            "safeDispatch" ->
                if (args.size == 2) {
                    val qual =
                        CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                            kotlinName = "safeDispatch",
                            cppBaseName = "safe_dispatch",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "kotlinx::coroutines::internal::safe_dispatch"
                    "$qual($recvExpr, ${args[0]}, ${args[1]})"
                } else {
                    null
                }
            "safeIsDispatchNeeded" ->
                if (args.size == 1) {
                    val qual =
                        CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                            kotlinName = "safeIsDispatchNeeded",
                            cppBaseName = "safe_is_dispatch_needed",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "kotlinx::coroutines::internal::safe_is_dispatch_needed"
                    "$qual($recvExpr, ${args[0]})"
                } else {
                    null
                }
            "checkParallelism" ->
                if (args.isEmpty()) {
                    val qual =
                        CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                            kotlinName = "checkParallelism",
                            cppBaseName = "check_parallelism",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "kotlinx::coroutines::internal::check_parallelism"
                    "$qual($recvExpr)"
                } else {
                    null
                }
            "namedOrThis" ->
                if (args.size == 1) {
                    val qual =
                        CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                            kotlinName = "namedOrThis",
                            cppBaseName = "named_or_this",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "kotlinx::coroutines::internal::named_or_this"
                    "$qual($recvExpr, ${args[0]})"
                } else {
                    null
                }
            "executeAfter" ->
                when (args.size) {
                    1 -> "$recvExpr.execute_after(0LL, ${args[0]})"
                    2 -> "$recvExpr.execute_after(${args[0]}, ${args[1]})"
                    else -> null
                }
            else -> null
        }
    }

    private fun doesClassMatchExtensionReceiver(
        cls: KtClassSymbol,
        recvTypeFrameIdx: Int,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        val recvSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[recvTypeFrameIdx]) ?: return false
        if (recvSimple == "Any") return true
        fun matchesHierarchy(cur: KtClassSymbol): Boolean {
            if (!visited.add(cur.frameIndex)) return false
            if (cur.kotlinName == recvSimple) return true
            for (superIdx in cur.superTypeFrameIndices) {
                val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
                if (superSimple == recvSimple) return true
                val sameFileSuper = bundle.symbols.classesByName[superSimple]
                if (sameFileSuper != null && matchesHierarchy(sameFileSuper)) return true
            }
            return false
        }
        return matchesHierarchy(cls)
    }

    private fun resolveEnclosingCallableProperty(calleeKt: String): String? {
        var cur: KtClassSymbol? = enclosingClass ?: return null
        if (classDeclaresMethod(enclosingClass, calleeKt)) return null
        var prefix = ""
        while (cur != null) {
            val ctorProp = cur.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == calleeKt }
            if (ctorProp != null) {
                val vpFrame = stream.frames[ctorProp.frameIndex]
                val fieldName =
                    if (PacketFlags.hasFlag(vpFrame.flags, PacketFlags.MOD_PRIVATE)) {
                        CppNamingPolicy.toPrivateFieldName(calleeKt)
                    } else {
                        ctorProp.cppName
                    }
                return "$prefix$fieldName"
            }
            for (mPropIdx in cur.memberPropertyFrameIndices) {
                val mProp = bundle.symbols.propertiesByFrame[mPropIdx] ?: continue
                if (mProp.kotlinName == calleeKt) {
                    val suffix = if (isGetterStyleClassProperty(cur, mProp)) "()" else ""
                    return "$prefix${mProp.cppName}$suffix"
                }
            }
            if (!cur.isInner) break
            cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            prefix += "outer_->"
        }
        return null
    }

    private fun extractAnyOuterTemplateArgs(rawCppType: String?): List<String>? {
        var cur =
            (rawCppType ?: return null)
                .trim()
                .removePrefix("const ")
                .removeSuffix("&")
                .trim()
        while (true) {
            val unwrapped =
                when {
                    cur.startsWith("std::optional<") && cur.endsWith(">") ->
                        cur.removePrefix("std::optional<").dropLast(1).trim()
                    cur.startsWith("std::shared_ptr<") && cur.endsWith(">") ->
                        cur.removePrefix("std::shared_ptr<").dropLast(1).trim()
                    else -> break
                }
            cur = unwrapped
        }
        val ltIdx = cur.indexOf('<')
        if (ltIdx < 0 || !cur.endsWith(">")) return null
        val inner = cur.substring(ltIdx + 1, cur.length - 1).trim()
        if (inner.isEmpty()) return null
        return splitTopLevelCommaList(inner)
    }

    private fun extractUserTypeArgTypeFrames(typeFrame: PacketFrame): List<PacketFrame> {
        var cur: PacketFrame? = typeFrame
        while (cur != null && cur.nodeTypeId != KtNodeTypes.USER_TYPE_ID) {
            cur =
                FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.USER_TYPE_ID)
                    ?: FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.NULLABLE_TYPE_ID)
                    ?: FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.TYPE_REFERENCE_ID)
        }
        val argList =
            cur?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_ARGUMENT_LIST_ID) }
                ?: return emptyList()
        return FrameAstHelpers
            .childFramesOfType(stream, argList, KtNodeTypes.TYPE_PROJECTION_ID)
            .mapNotNull { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_REFERENCE_ID) }
    }

    private fun resolveCallSiteReceiverTypeFrame(receiverFrame: PacketFrame?): PacketFrame? {
        if (receiverFrame == null) {
            return if (receiverSubstitution != null) {
                enclosingFunction?.receiverTypeFrameIndex?.let { stream.frames[it] }
            } else {
                null
            }
        }
        val u = unwrapTransparentFrame(receiverFrame)
        return when (u.nodeTypeId) {
            KtNodeTypes.THIS_EXPRESSION_ID ->
                enclosingFunction?.receiverTypeFrameIndex?.let { stream.frames[it] }
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val refKt = FrameAstHelpers.directIdentPacket(stream, u)?.unescapedName ?: return null
                val fnParamTf = enclosingFunction?.valueParams?.firstOrNull { it.name == refKt }?.typeFrameIndex
                val clsParamTf = enclosingClass?.primaryConstructorParams?.firstOrNull { it.name == refKt }?.typeFrameIndex
                (fnParamTf ?: clsParamTf)?.let { stream.frames[it] }
            }
            else -> null
        }
    }

    private fun fnUsesTypeParamInSuspendOrTemplate(
        fn: KtFunctionSymbol,
        tpName: String,
    ): Boolean {
        for (vp in fn.valueParams) {
            val tfIdx = vp.typeFrameIndex ?: continue
            val tf = stream.frames[tfIdx]
            if (PacketFlags.hasFlag(stream.frames[vp.frameIndex].flags, PacketFlags.MOD_SUSPEND) ||
                PacketFlags.hasFlag(tf.flags, PacketFlags.MOD_SUSPEND)
            ) {
                return true
            }
            val fnNode = extractFunctionTypeNode(tf)
            if (fnNode != null && PacketFlags.hasFlag(fnNode.flags, PacketFlags.MOD_SUSPEND)) {
                return true
            }
            if (extractUserTypeArgTypeFrames(tf).any {
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, it) == tpName
                }
            ) {
                return true
            }
        }
        return false
    }

    private fun inferMissingFunctionTypeArgs(
        fn: KtFunctionSymbol,
        explicitTypeArgs: List<String>,
        targetCppForInference: String?,
        receiverFrame: PacketFrame?,
    ): List<String> {
        if (fn.typeParams.isEmpty() || explicitTypeArgs.size >= fn.typeParams.size) return explicitTypeArgs
        val inferred = arrayOfNulls<String>(fn.typeParams.size)
        for (i in explicitTypeArgs.indices) {
            if (i < inferred.size) inferred[i] = explicitTypeArgs[i]
        }
        val fnRecvIdx = fn.receiverTypeFrameIndex
        if (fnRecvIdx != null) {
            val fnRecvFrame = stream.frames[fnRecvIdx]
            val callSiteRecvFrame = resolveCallSiteReceiverTypeFrame(receiverFrame)
            if (callSiteRecvFrame != null) {
                val fnRecvArgs = extractUserTypeArgTypeFrames(fnRecvFrame)
                val siteRecvArgs = extractUserTypeArgTypeFrames(callSiteRecvFrame)
                if (fnRecvArgs.size == siteRecvArgs.size) {
                    for (i in fnRecvArgs.indices) {
                        val fnArgSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, fnRecvArgs[i])
                        val tpIdx = fn.typeParams.indexOfFirst { it.name == fnArgSimple }
                        if (tpIdx >= 0 && inferred[tpIdx] == null) {
                            inferred[tpIdx] = translateType(siteRecvArgs[i], CppTypePosition.TYPE_ARGUMENT)
                        }
                    }
                }
            }
        }
        val normTarget =
            targetCppForInference
                ?.trim()
                ?.removePrefix("[[noreturn]] ")
                ?.takeIf { it.isNotEmpty() && it != "auto" && it != "std::any" && it != "void*" }
        val retIdx = fn.returnTypeFrameIndex
        if (normTarget != null && retIdx != null) {
            val retFrame = stream.frames[retIdx]
            val retSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retFrame)
            if (retSimple != null) {
                val directIdx = fn.typeParams.indexOfFirst { it.name == retSimple }
                if (directIdx >= 0 && inferred[directIdx] == null) {
                    val unwrapped = normTarget.removePrefix("const ").removeSuffix("&").trim()
                    inferred[directIdx] =
                        if (unwrapped == "void" && fnUsesTypeParamInSuspendOrTemplate(fn, retSimple)) {
                            includes.addProject("kotlinx/coroutines/Unit.hpp")
                            "kotlin::Unit"
                        } else {
                            unwrapped
                        }
                } else {
                    val cppBase =
                        lookupAccessibleClassByName(retSimple)?.cppName
                            ?: CppTypeTranslator.includeAndQualifyKnownType(
                                simpleName = retSimple,
                                cppBaseName = CppNamingPolicy.toTypeName(retSimple),
                                currentNs = bundle.symbols.namespaceSegments,
                                includes = includes,
                            )
                            ?: CppNamingPolicy.toTypeName(retSimple)
                    val outerArgs = extractOuterTemplateArgsForBase(normTarget, cppBase)
                    val retTypeArgFrames = extractUserTypeArgTypeFrames(retFrame)
                    if (outerArgs != null && outerArgs.size == retTypeArgFrames.size) {
                        for (i in retTypeArgFrames.indices) {
                            val argSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retTypeArgFrames[i])
                            val tpIdx = fn.typeParams.indexOfFirst { it.name == argSimple }
                            if (tpIdx >= 0 && inferred[tpIdx] == null) {
                                inferred[tpIdx] = outerArgs[i]
                            }
                        }
                    }
                }
            }
        }
        return if (inferred.all { it != null }) inferred.filterNotNull() else explicitTypeArgs
    }

    private data class RawCallArg(
        val name: String?,
        val exprFrame: PacketFrame,
        val isTrailingLambda: Boolean = false,
    )

    private fun extractRawCallArgs(callFrame: PacketFrame): List<RawCallArg> {
        val out = ArrayList<RawCallArg>()
        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
        if (argList != null) {
            for (va in FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID)) {
                val argNameFrame = FrameAstHelpers.firstChildFrame(stream, va, KtNodeTypes.VALUE_ARGUMENT_NAME_ID)
                val argName =
                    argNameFrame?.let { nf ->
                        FrameAstHelpers
                            .firstChildFrame(stream, nf, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                            ?.let { ref -> FrameAstHelpers.directIdentPacket(stream, ref)?.unescapedName }
                            ?: FrameAstHelpers.directIdentPacket(stream, nf)?.unescapedName
                    }
                val expr =
                    FrameAstHelpers
                        .childFrames(stream, va)
                        .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                if (expr != null) {
                    out.add(RawCallArg(name = argName, exprFrame = expr))
                }
            }
        }
        for (tl in FrameAstHelpers.childFramesOfType(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID)) {
            val lambda = FrameAstHelpers.unwrapLambdaExpression(stream, tl)
            if (lambda != null) {
                out.add(RawCallArg(name = null, exprFrame = lambda, isTrailingLambda = true))
            }
        }
        return out
    }

    private fun mapCallArgsToParamIndices(
        params: List<KtValueParamInfo>,
        rawArgs: List<RawCallArg>,
    ): Map<Int, List<RawCallArg>>? {
        if (rawArgs.isEmpty()) {
            val allOptional = params.all { it.defaultExprFrameIndex != null || it.isVararg }
            return if (allOptional) emptyMap() else null
        }
        val bound = LinkedHashMap<Int, MutableList<RawCallArg>>()
        var nextPositional = 0
        var seenNamed = false
        for (arg in rawArgs) {
            if (arg.isTrailingLambda) {
                if (params.isEmpty()) return null
                val targetIdx = params.lastIndex
                if (targetIdx in bound) return null
                bound.getOrPut(targetIdx) { ArrayList() }.add(arg)
            } else if (arg.name != null) {
                seenNamed = true
                val idx = params.indexOfFirst { it.name == arg.name }
                if (idx < 0) return null
                if (idx in bound && !params[idx].isVararg) return null
                bound.getOrPut(idx) { ArrayList() }.add(arg)
            } else {
                if (seenNamed || nextPositional >= params.size) return null
                bound.getOrPut(nextPositional) { ArrayList() }.add(arg)
                if (!params[nextPositional].isVararg) {
                    nextPositional++
                }
            }
        }
        for (i in params.indices) {
            if (i !in bound && params[i].defaultExprFrameIndex == null && !params[i].isVararg) {
                return null
            }
        }
        return bound
    }

    private fun doesFnMatchRawArgs(
        params: List<KtValueParamInfo>,
        rawArgs: List<RawCallArg>,
    ): Boolean = mapCallArgsToParamIndices(params, rawArgs) != null

    private fun isOptionalOrAnyParamType(paramTypeFrameIdx: Int?): String? {
        val tfIdx = paramTypeFrameIdx ?: return null
        val paramCppT = translateType(stream.frames[tfIdx], CppTypePosition.PARAMETER)
        return when {
            paramCppT.startsWith("std::optional<") || paramCppT.startsWith("const std::optional<") -> "optional"
            paramCppT == "std::any" || paramCppT == "const std::any&" -> "any"
            else -> null
        }
    }

    private fun emitCallArgForParam(
        vp: KtValueParamInfo,
        rawArg: RawCallArg,
    ): String {
        val u = unwrapTransparentFrame(rawArg.exprFrame)
        if (u.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID) {
            val tf = vp.typeFrameIndex?.let { stream.frames[it] }
            val recvRef = tf?.let { extractFunctionTypeReceiverRef(it) }
            val expectedCount = tf?.let { extractFunctionTypeValueParamCount(it) }
            return emitLambdaClosureExpr(u, receiverTypeRef = recvRef, expectedValueParamCount = expectedCount)
        }
        if (u.nodeTypeId == KtNodeTypes.NULL_ID) {
            when (isOptionalOrAnyParamType(vp.typeFrameIndex)) {
                "optional" -> {
                    includes.addSystem("optional")
                    return "std::nullopt"
                }
                "any" -> {
                    includes.addSystem("any")
                    return "{}"
                }
            }
        }
        return withExpectedTargetType(null) { emitExpression(rawArg.exprFrame) }
    }

    private fun emitDefaultValueForParam(vp: KtValueParamInfo): String {
        val defIdx = vp.defaultExprFrameIndex ?: return "{}"
        val defFrame = unwrapTransparentFrame(stream.frames[defIdx])
        if (defFrame.nodeTypeId == KtNodeTypes.NULL_ID) {
            when (isOptionalOrAnyParamType(vp.typeFrameIndex)) {
                "optional" -> {
                    includes.addSystem("optional")
                    return "std::nullopt"
                }
                "any" -> {
                    includes.addSystem("any")
                    return "{}"
                }
            }
        }
        return withExpectedTargetType(null) { emitExpression(defFrame) }
    }

    private fun emitAlignedArgsForParams(
        params: List<KtValueParamInfo>,
        bound: Map<Int, List<RawCallArg>>,
    ): List<String> {
        if (bound.isEmpty()) return emptyList()
        val lastProvidedIdx = bound.keys.maxOrNull() ?: return emptyList()
        val out = ArrayList<String>(lastProvidedIdx + 1)
        for (i in 0..lastProvidedIdx) {
            val vp = params[i]
            val supplied = bound[i]
            if (supplied == null) {
                out.add(emitDefaultValueForParam(vp))
            } else if (vp.isVararg) {
                val rendered = supplied.map { emitCallArgForParam(vp, it) }
                if (i == lastProvidedIdx) {
                    out.addAll(rendered)
                } else {
                    out.add("{${rendered.joinToString(", ")}}")
                }
            } else {
                out.add(emitCallArgForParam(vp, supplied[0]))
            }
        }
        return out
    }

    private fun extractCallArgFrames(callFrame: PacketFrame): List<PacketFrame> =
        extractRawCallArgs(callFrame).map { it.exprFrame }

    private fun extractCallArgExpressions(callFrame: PacketFrame): List<String> =
        extractCallArgFrames(callFrame).map { withExpectedTargetType(null) { emitExpression(it) } }

    private fun emitArrayAccessExpr(arrAccessFrame: PacketFrame): String {
        val arrExpr = FrameAstHelpers.childFrames(stream, arrAccessFrame).firstOrNull()
        val indicesFrame = FrameAstHelpers.firstChildFrame(stream, arrAccessFrame, KtNodeTypes.INDICES_ID)
        val idxExprs =
            indicesFrame?.let { FrameAstHelpers.childFrames(stream, it).map { idx -> emitExpression(idx) } }.orEmpty()
        val arrCode = arrExpr?.let { emitExpression(it) } ?: "arr"
        if (!bundle.isDocExample && idxExprs.size == 1) {
            val idxCode = idxExprs[0]
            val isKeySubscript =
                idxCode.endsWith("::Key::instance()") ||
                    idxCode.endsWith("key_instance") ||
                    idxCode.endsWith("type_key") ||
                    (
                        (idxCode == "key" || idxCode == "key_") &&
                            (
                                arrCode.contains("context") ||
                                    arrCode == "self" ||
                                    arrCode == "this" ||
                                    arrCode == "(*this)"
                            )
                    )
            if (isKeySubscript) {
                includes.needsContextOpsDef = true
                return "kotlin::coroutines::kxs_ctx_get($arrCode, $idxCode)"
            }
        }
        // C++23 supports multidimensional subscript `arr[i, j]` directly!
        return "$arrCode[${idxExprs.joinToString(", ")}]"
    }

    private fun emitBinaryExpr(binFrame: PacketFrame): String {
        val ch = FrameAstHelpers.childFrames(stream, binFrame)
        require(ch.size >= 3) {
            "${stream.sourcePath}:${binFrame.span.startLine}:${binFrame.span.startColumn}: malformed BINARY_EXPRESSION"
        }
        val lhsFrame = ch[0]
        val opText = FrameAstHelpers.operationSymbolName(stream, ch[1])
        val rhsFrame = ch[2]

        val unwrappedLhs = unwrapTransparentFrame(lhsFrame)
        if (!bundle.isDocExample && opText == "=" && bundle.config.gcMode != CppGcMode.KONAN_GC) {
            if (unwrappedLhs.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
                val dotParts = FrameAstHelpers.childFrames(stream, unwrappedLhs)
                if (dotParts.size == 2) {
                    val dRecv = dotParts[0]
                    val dSel = dotParts[1]
                    val selKt = FrameAstHelpers.directIdentPacket(stream, dSel)?.unescapedName
                    val isUnlabeledThisDot =
                        dRecv.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID &&
                            FrameAstHelpers.firstChildFrame(stream, dRecv, KtNodeTypes.LABEL_QUALIFIER_ID) == null &&
                            receiverSubstitution == null
                    val memberProps = enclosingClass?.memberPropertyFrameIndices.orEmpty()
                    val hasOwnValueProp =
                        isUnlabeledThisDot &&
                            memberProps.any { bundle.symbols.propertiesByFrame[it]?.kotlinName == "value" }
                    if (selKt == "value" && !hasOwnValueProp) {
                        val rhsCode = emitExpression(rhsFrame)
                        val recvCode = if (isUnlabeledThisDot) "this" else emitExpression(dRecv)
                        includes.addSystem("type_traits")
                        includes.addSystem("utility")
                        return "([&](auto&& kxs_w_r, auto&& kxs_w_v) { " +
                            "using KxsWR = std::remove_cvref_t<decltype(kxs_w_r)>; " +
                            "if constexpr (std::is_pointer_v<KxsWR> || requires { kxs_w_r.operator->(); }) { " +
                            "if constexpr (requires { kxs_w_r->set_value(std::forward<decltype(kxs_w_v)>(kxs_w_v)); }) " +
                            "kxs_w_r->set_value(std::forward<decltype(kxs_w_v)>(kxs_w_v)); " +
                            "else if constexpr (std::is_null_pointer_v<std::remove_cvref_t<decltype(kxs_w_v)>> && requires { kxs_w_r->set_value({}); }) " +
                            "kxs_w_r->set_value({}); " +
                            "else kxs_w_r->value = std::forward<decltype(kxs_w_v)>(kxs_w_v); " +
                            "} else { " +
                            "if constexpr (requires { kxs_w_r.set_value(std::forward<decltype(kxs_w_v)>(kxs_w_v)); }) " +
                            "kxs_w_r.set_value(std::forward<decltype(kxs_w_v)>(kxs_w_v)); " +
                            "else if constexpr (std::is_null_pointer_v<std::remove_cvref_t<decltype(kxs_w_v)>> && requires { kxs_w_r.set_value({}); }) " +
                            "kxs_w_r.set_value({}); " +
                            "else kxs_w_r.value = std::forward<decltype(kxs_w_v)>(kxs_w_v); " +
                            "} }($recvCode, $rhsCode))"
                    }
                    if (isUnlabeledThisDot && selKt != null && enclosingClass != null) {
                        val backedVarProp =
                            enclosingClass.memberPropertyFrameIndices
                                .mapNotNull { bundle.symbols.propertiesByFrame[it] }
                                .firstOrNull { it.kotlinName == selKt && it.isVar && isBackedOverrideMemberPropExpr(enclosingClass, it) }
                        if (backedVarProp != null) {
                            val rhsCode = emitExpression(rhsFrame)
                            return "this->set_${backedVarProp.cppName.removeSuffix("_")}($rhsCode)"
                        }
                    }
                }
            } else if (unwrappedLhs.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID && enclosingClass != null) {
                val ktVar = FrameAstHelpers.directIdentPacket(stream, unwrappedLhs)?.unescapedName
                if (ktVar != null && resolveBoundName(ktVar) == null) {
                    val backedVarProp =
                        enclosingClass.memberPropertyFrameIndices
                            .mapNotNull { bundle.symbols.propertiesByFrame[it] }
                            .firstOrNull { it.kotlinName == ktVar && it.isVar && isBackedOverrideMemberPropExpr(enclosingClass, it) }
                    if (backedVarProp != null) {
                        val rhsCode = emitExpression(rhsFrame)
                        return "this->set_${backedVarProp.cppName.removeSuffix("_")}($rhsCode)"
                    }
                }
            }
        }

        if (opText == "&&" || opText == "||") {
            val lhs = withExpectedTargetType(null) { emitExpression(lhsFrame) }
            val whenTrue = opText == "&&"
            val lhsSmartCasts = ControlFlowAndTypePass.extractConditionSmartCasts(stream, lhsFrame, whenTrue = whenTrue)
            val lhsWrapped =
                if (opText == "||" && isLogicalAndBinaryFrame(lhsFrame)) {
                    "($lhs)"
                } else if (opText == "&&" && isLogicalOrBinaryFrame(lhsFrame)) {
                    "($lhs)"
                } else {
                    lhs
                }
            if (lhsSmartCasts.isNotEmpty()) {
                val bindSb = StringBuilder()
                val rhsCode =
                    withLexicalScope {
                        emitSmartCastBindings(bindSb, lhsSmartCasts, "")
                        val r = withExpectedTargetType(null) { emitExpression(rhsFrame) }
                        if (opText == "||" && isLogicalAndBinaryFrame(rhsFrame)) {
                            "($r)"
                        } else if (opText == "&&" && isLogicalOrBinaryFrame(rhsFrame)) {
                            "($r)"
                        } else {
                            r
                        }
                    }
                val bindInline = bindSb.toString().replace('\n', ' ').trim()
                val shortCircuit = if (whenTrue) "if (!($lhsWrapped)) return false;" else "if ($lhsWrapped) return true;"
                return "([&]() -> bool { $shortCircuit $bindInline return static_cast<bool>($rhsCode); }())"
            }
            val rhs = withExpectedTargetType(null) { emitExpression(rhsFrame) }
            val rhsWrapped =
                if (opText == "||" && isLogicalAndBinaryFrame(rhsFrame)) {
                    "($rhs)"
                } else if (opText == "&&" && isLogicalOrBinaryFrame(rhsFrame)) {
                    "($rhs)"
                } else {
                    rhs
                }
            return "$lhsWrapped $opText $rhsWrapped"
        }

        val lhs = emitExpression(lhsFrame)
        val rhs = emitExpression(rhsFrame)

        val isEqualityOp = opText == "==" || opText == "!=" || opText == "===" || opText == "!=="
        val hasNullOperand =
            unwrapTransparentFrame(lhsFrame).nodeTypeId == KtNodeTypes.NULL_ID ||
                unwrapTransparentFrame(rhsFrame).nodeTypeId == KtNodeTypes.NULL_ID
        if (!bundle.isDocExample && isEqualityOp && hasNullOperand) {
            includes.needsNullOperatorsDef = true
            includes.addSystem("any")
            includes.addSystem("cstddef")
            includes.addSystem("optional")
            includes.addSystem("typeinfo")
        }

        return when (opText) {
            "===",
            "!==",
            -> {
                val isEq = opText == "==="
                val eqOp = if (isEq) "==" else "!="
                if (bundle.config.gcMode != CppGcMode.KONAN_GC && isStdAnyExpr(lhsFrame)) {
                    includes.addSystem("any")
                    includes.addSystem("cstddef")
                    includes.addSystem("memory")
                    includes.addSystem("type_traits")
                    includes.addSystem("typeinfo")
                    if (unwrapTransparentFrame(rhsFrame).nodeTypeId == KtNodeTypes.NULL_ID) {
                        val isNullExpr = "(!$lhs.has_value() || $lhs.type() == typeid(std::nullptr_t))"
                        return if (isEq) isNullExpr else "(!$isNullExpr)"
                    }
                    if (rhsFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID && receiverSubstitution == null) {
                        val sameCheck =
                            "(std::any_cast<decltype(this)>(std::addressof($lhs)) != nullptr && " +
                                "*std::any_cast<decltype(this)>(std::addressof($lhs)) == this)"
                        return if (isEq) sameCheck else "(!$sameCheck)"
                    }
                    val sameCheck =
                        "(std::any_cast<const std::decay_t<decltype($rhs)>*>(std::addressof($lhs)) != nullptr && " +
                            "*std::any_cast<const std::decay_t<decltype($rhs)>*>(std::addressof($lhs)) == std::addressof($rhs))"
                    return if (isEq) sameCheck else "(!$sameCheck)"
                }
                if (bundle.config.gcMode != CppGcMode.KONAN_GC && isStdAnyExpr(rhsFrame)) {
                    includes.addSystem("any")
                    includes.addSystem("cstddef")
                    includes.addSystem("memory")
                    includes.addSystem("type_traits")
                    includes.addSystem("typeinfo")
                    if (unwrapTransparentFrame(lhsFrame).nodeTypeId == KtNodeTypes.NULL_ID) {
                        val isNullExpr = "(!$rhs.has_value() || $rhs.type() == typeid(std::nullptr_t))"
                        return if (isEq) isNullExpr else "(!$isNullExpr)"
                    }
                    if (lhsFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID && receiverSubstitution == null) {
                        val sameCheck =
                            "(std::any_cast<decltype(this)>(std::addressof($rhs)) != nullptr && " +
                                "*std::any_cast<decltype(this)>(std::addressof($rhs)) == this)"
                        return if (isEq) sameCheck else "(!$sameCheck)"
                    }
                    val sameCheck =
                        "(std::any_cast<const std::decay_t<decltype($lhs)>*>(std::addressof($rhs)) != nullptr && " +
                            "*std::any_cast<const std::decay_t<decltype($lhs)>*>(std::addressof($rhs)) == std::addressof($lhs))"
                    return if (isEq) sameCheck else "(!$sameCheck)"
                }
                val isEnumOwner =
                    enclosingClass?.kind == KtClassKind.SIMPLE_ENUM ||
                        enclosingClass?.kind == KtClassKind.RICH_ENUM
                val hasThisExpr =
                    lhsFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID ||
                        rhsFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID
                val isDirectEnumSelfCompare =
                    isEnumOwner &&
                        hasThisExpr &&
                        lhsFrame.nodeTypeId != KtNodeTypes.NULL_ID &&
                        rhsFrame.nodeTypeId != KtNodeTypes.NULL_ID
                if (!bundle.isDocExample &&
                    bundle.config.gcMode != CppGcMode.KONAN_GC &&
                    !isDirectEnumSelfCompare
                ) {
                    includes.needsAnyEqDef = true
                    includes.addSystem("any")
                    includes.addSystem("memory")
                    includes.addSystem("optional")
                    includes.addSystem("type_traits")
                    includes.addSystem("typeinfo")
                    if (isEq) "kxs_any_ref_eq($lhs, $rhs)" else "(!kxs_any_ref_eq($lhs, $rhs))"
                } else if (lhsFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID && receiverSubstitution == null && isDirectIdentityOperand(rhsFrame)) {
                    "(this $eqOp $rhs)"
                } else if (rhsFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID && receiverSubstitution == null && isDirectIdentityOperand(lhsFrame)) {
                    "($lhs $eqOp this)"
                } else if (isDirectIdentityOperand(lhsFrame) || isDirectIdentityOperand(rhsFrame)) {
                    "($lhs $eqOp $rhs)"
                } else {
                    includes.addSystem("memory")
                    "(std::addressof($lhs) $eqOp std::addressof($rhs))"
                }
            }
            "?:" -> {
                val temp = bundle.cfPlan.singleEvalTempNamesByFrame[binFrame.frameIndex]
                if (bundle.isDocExample) {
                    if (PacketFlags.hasFlag(rhsFrame.flags, PacketFlags.DIVERGING_EXPR)) {
                        if (temp != null) {
                            "([&]() { auto&& $temp = $lhs; return $temp ? *$temp : ($rhs); }())"
                        } else {
                            "($lhs ? *$lhs : ($rhs))"
                        }
                    } else if (temp != null) {
                        "([&]() { auto&& $temp = $lhs; return $temp ? *$temp : ($rhs); }())"
                    } else {
                        "($lhs ? *$lhs : ($rhs))"
                    }
                } else if (PacketFlags.hasFlag(rhsFrame.flags, PacketFlags.DIVERGING_EXPR)) {
                    val ev = temp ?: freshTemp("kxs_elvis")
                    includes.needsNullUnwrapDef = true
                    includes.addSystem("any")
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    "([&]() { auto&& $ev = $lhs; " +
                        "if constexpr (std::is_same_v<std::remove_cvref_t<decltype($ev)>, std::any>) { " +
                        "if (!$ev.has_value() || $ev.type() == typeid(std::nullptr_t)) { $rhs; } return $ev; " +
                        "} else { if (!$ev) { $rhs; } return kxs_unwrap_opt($ev); } }())"
                } else {
                    includes.needsCastHelpersDef = true
                    "kxs_elvis($lhs, [&]() -> decltype(auto) { return $rhs; })"
                }
            }
            "shl" -> "($lhs << $rhs)"
            "shr" -> "($lhs >> $rhs)"
            "ushr" -> {
                includes.addSystem("type_traits")
                "(static_cast<std::make_unsigned_t<decltype($lhs)>>($lhs) >> $rhs)"
            }
            "and" -> "($lhs & $rhs)"
            "or" -> "($lhs | $rhs)"
            "xor" -> "($lhs ^ $rhs)"
            "to" -> {
                includes.addSystem("utility")
                "std::make_pair($lhs, $rhs)"
            }
            "in" -> emitRangeMembershipCheck(lhs, rhsFrame)
            "!in" -> "!(${emitRangeMembershipCheck(lhs, rhsFrame)})"
            "<", "<=", ">", ">=" -> {
                if (!bundle.isDocExample) {
                    includes.addSystem("type_traits")
                    "([&](const auto& kxs_cl, const auto& kxs_cr) -> bool { " +
                        "if constexpr (requires { kxs_cl->compare_to(*kxs_cr); }) return kxs_cl->compare_to(*kxs_cr) $opText 0; " +
                        "else if constexpr (requires { kxs_cl->compare_to(kxs_cr); }) return kxs_cl->compare_to(kxs_cr) $opText 0; " +
                        "else if constexpr (requires { kxs_cl.compare_to(kxs_cr); }) return kxs_cl.compare_to(kxs_cr) $opText 0; " +
                        "else return kxs_cl $opText kxs_cr; }($lhs, $rhs))"
                } else {
                    "$lhs $opText $rhs"
                }
            }
            else -> {
                if (!bundle.isDocExample &&
                    bundle.config.gcMode != CppGcMode.KONAN_GC &&
                    !hasNullOperand &&
                    (opText == "==" || opText == "!=")
                ) {
                    includes.needsAnyEqDef = true
                    includes.addSystem("any")
                    includes.addSystem("memory")
                    includes.addSystem("optional")
                    includes.addSystem("type_traits")
                    includes.addSystem("typeinfo")
                    if (opText == "==") "kxs_any_eq($lhs, $rhs)" else "(!kxs_any_eq($lhs, $rhs))"
                } else if (opText in CPP_BINARY_OPERATORS) {
                    if (opText == "=" &&
                        !bundle.isDocExample &&
                        (isExceptionPtrExpr(lhsFrame) || isExceptionPtrVarByCppName(lhs))
                    ) {
                        "$lhs = ${wrapWithExceptionPtrConversion(rhs, rhsFrame)}"
                    } else if (opText in ASSIGN_OPS) {
                        "$lhs $opText $rhs"
                    } else {
                        "$lhs $opText $rhs"
                    }
                } else {
                    // Kotlin `infix fun` call
                    "${CppNamingPolicy.toSnakeCase(opText)}($lhs, $rhs)"
                }
            }
        }
    }

    private fun isLogicalAndBinaryFrame(frame: PacketFrame): Boolean {
        val unwrapped = unwrapTransparentFrame(frame)
        if (unwrapped.nodeTypeId != KtNodeTypes.BINARY_EXPRESSION_ID) return false
        val ch = FrameAstHelpers.childFrames(stream, unwrapped)
        return ch.size >= 3 && FrameAstHelpers.operationSymbolName(stream, ch[1]) == "&&"
    }

    private fun isLogicalOrBinaryFrame(frame: PacketFrame): Boolean {
        val unwrapped = unwrapTransparentFrame(frame)
        if (unwrapped.nodeTypeId != KtNodeTypes.BINARY_EXPRESSION_ID) return false
        val ch = FrameAstHelpers.childFrames(stream, unwrapped)
        return ch.size >= 3 && FrameAstHelpers.operationSymbolName(stream, ch[1]) == "||"
    }

    private fun emitBinaryWithTypeExpr(castFrame: PacketFrame): String {
        val lhs = FrameAstHelpers.childFrames(stream, castFrame).firstOrNull()
        val opRef = FrameAstHelpers.firstChildFrame(stream, castFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val tr = FrameAstHelpers.firstChildFrame(stream, castFrame, KtNodeTypes.TYPE_REFERENCE_ID)
        val isSafeCast = opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.AS_SAFE_ID
        val targetCpp = tr?.let { translateType(it, CppTypePosition.VALUE) } ?: "void*"
        val cleanType = targetCpp.removeSuffix("&")

        if (!isSafeCast && lhs != null && unwrapTransparentFrame(lhs).nodeTypeId == KtNodeTypes.NULL_ID) {
            return "$cleanType{}"
        }

        val lhsCode = lhs?.let { emitExpression(it) } ?: "nullptr"
        if (!bundle.isDocExample && isSafeCast) {
            val safeTarget =
                if (cleanType.startsWith("std::shared_ptr<") ||
                    cleanType.endsWith("*") ||
                    cleanType.startsWith("std::optional<") ||
                    cleanType == "std::any"
                ) {
                    cleanType
                } else {
                    includes.addSystem("optional")
                    "std::optional<$cleanType>"
                }
            includes.needsCastHelpersDef = true
            includes.addSystem("memory")
            return "kxs_safe_as_cast<$safeTarget>($lhsCode)"
        }
        if (bundle.config.gcMode != CppGcMode.KONAN_GC && lhs != null && isStdAnyExpr(lhs)) {
            includes.addSystem("any")
            includes.addSystem("cstddef")
            includes.addSystem("memory")
            includes.addSystem("typeinfo")
            return if (isSafeCast) {
                "std::any_cast<${cleanType.removeSuffix("*")}>(std::addressof($lhsCode))"
            } else if (unwrapTransparentFrame(lhs).nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                "((!$lhsCode.has_value() || $lhsCode.type() == typeid(std::nullptr_t)) ? $cleanType{} : std::any_cast<$cleanType>($lhsCode))"
            } else {
                "([&](const std::any& kxs_any) -> $cleanType { return (!kxs_any.has_value() || kxs_any.type() == typeid(std::nullptr_t)) ? $cleanType{} : std::any_cast<$cleanType>(kxs_any); }($lhsCode))"
            }
        }

        return if (isSafeCast) {
            val rawTarget = cleanType.removeSuffix("*")
            includes.addSystem("memory")
            "dynamic_cast<$rawTarget*>($lhsCode)"
        } else if (bundle.isDocExample) {
            "static_cast<$cleanType>($lhsCode)"
        } else {
            includes.needsCastHelpersDef = true
            "kxs_as_cast<$cleanType>($lhsCode)"
        }
    }

    private fun emitIsExpr(isFrame: PacketFrame): String {
        val lhs = FrameAstHelpers.childFrames(stream, isFrame).firstOrNull()
        val opRef = FrameAstHelpers.firstChildFrame(stream, isFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val tr = FrameAstHelpers.firstChildFrame(stream, isFrame, KtNodeTypes.TYPE_REFERENCE_ID)
        val negated = opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.NOT_IS_ID
        val lhsCode = lhs?.let { emitExpression(it) } ?: "this"
        val cppType =
            tr
                ?.let { translateType(it, CppTypePosition.TYPE_ARGUMENT) }
                ?.removeSuffix("&")
                ?.removeSuffix("*")
                ?: "void"
        includes.addSystem("memory")
        if (bundle.isDocExample) {
            val docCheck = "dynamic_cast<const $cppType*>(std::addressof($lhsCode)) != nullptr"
            return if (negated) "(!($docCheck))" else "($docCheck)"
        }
        val check = emitRuntimeIsCheck(lhsCode, cppType)
        return if (negated) "(!$check)" else check
    }

    private fun emitRuntimeIsCheck(
        subjExpr: String,
        cppType: String,
    ): String {
        includes.needsCastHelpersDef = true
        return "kxs_is_instance<$cppType>($subjExpr)"
    }

    private fun emitPrefixExpr(prefixFrame: PacketFrame): String {
        val opRef = FrameAstHelpers.firstChildFrame(stream, prefixFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val operand =
            FrameAstHelpers
                .childFrames(stream, prefixFrame)
                .lastOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
        val op = opRef?.let { FrameAstHelpers.operationSymbolName(stream, it) }.orEmpty()
        val inner = operand?.let { emitExpression(it) }.orEmpty()
        return "$op$inner"
    }

    private fun emitPostfixExpr(postfixFrame: PacketFrame): String {
        val operand =
            FrameAstHelpers
                .childFrames(stream, postfixFrame)
                .firstOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
        val opRef = FrameAstHelpers.firstChildFrame(stream, postfixFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val opId = opRef?.let { FrameAstHelpers.operationTokenId(stream, it) }
        val op = opRef?.let { FrameAstHelpers.operationSymbolName(stream, it) }.orEmpty()
        val inner = operand?.let { emitExpression(it) }.orEmpty()
        return if (opId == KtTokens.EXCLEXCL_ID) {
            includes.addSystem("stdexcept")
            includes.addSystem("type_traits")
            if (bundle.isDocExample) {
                "([&](auto&& kxs_v) -> decltype(auto) { if (!kxs_v) throw std::runtime_error(\"NullPointerException\"); " +
                    "if constexpr (std::is_pointer_v<std::decay_t<decltype(kxs_v)>>) return kxs_v; else return *kxs_v; }($inner))"
            } else {
                includes.needsNullUnwrapDef = true
                includes.addSystem("any")
                includes.addSystem("utility")
                "([&](auto&& kxs_v) -> decltype(auto) { using KxsV = std::remove_cvref_t<decltype(kxs_v)>; " +
                    "if constexpr (std::is_same_v<KxsV, std::any>) { " +
                    "if (!kxs_v.has_value() || kxs_v.type() == typeid(std::nullptr_t)) throw std::runtime_error(\"NullPointerException\"); " +
                    "return kxs_v; } else { " +
                    "if (!kxs_v) throw std::runtime_error(\"NullPointerException\"); " +
                    "return kxs_unwrap_opt(std::forward<decltype(kxs_v)>(kxs_v)); } }($inner))"
            }
        } else {
            "$inner$op"
        }
    }

    private fun containsReferenceNamed(
        frame: PacketFrame,
        targetName: String,
    ): Boolean {
        if (frame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
            FrameAstHelpers.directIdentPacket(stream, frame)?.unescapedName == targetName
        ) {
            return true
        }
        return FrameAstHelpers.childFrames(stream, frame).any { containsReferenceNamed(it, targetName) }
    }

    private fun canEmitTernaryIf(ifFrame: PacketFrame): Boolean {
        if (PacketFlags.hasFlag(ifFrame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE)) return false
        if (isOuterSuspendReturnContext && isVoidReturnContext) return false
        val condWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.CONDITION_ID)
        val thenWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.THEN_ID)
        val elseWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.ELSE_ID)
        val cond = condWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val thenChild = thenWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val elseChild = elseWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        if (thenChild != null && containsReferenceNamed(thenChild, "COROUTINE_SUSPENDED")) return false
        if (elseChild != null && containsReferenceNamed(elseChild, "COROUTINE_SUSPENDED")) return false
        return cond != null &&
            thenChild != null &&
            elseChild != null &&
            thenChild.nodeTypeId != KtNodeTypes.BLOCK_ID &&
            elseChild.nodeTypeId != KtNodeTypes.BLOCK_ID &&
            thenChild.nodeTypeId !in STATEMENT_ONLY_NODE_TYPES &&
            elseChild.nodeTypeId !in STATEMENT_ONLY_NODE_TYPES &&
            unwrapTransparentFrame(thenChild).nodeTypeId != KtNodeTypes.THROW_ID &&
            unwrapTransparentFrame(elseChild).nodeTypeId != KtNodeTypes.THROW_ID &&
            (unwrapTransparentFrame(thenChild).nodeTypeId == KtNodeTypes.NULL_ID) ==
            (unwrapTransparentFrame(elseChild).nodeTypeId == KtNodeTypes.NULL_ID) &&
            !PacketFlags.hasFlag(thenChild.flags, PacketFlags.DIVERGING_EXPR) &&
            !PacketFlags.hasFlag(elseChild.flags, PacketFlags.DIVERGING_EXPR) &&
            bundle.cfPlan.smartCastsByScopeFrame[thenWrap.frameIndex].isNullOrEmpty() &&
            bundle.cfPlan.smartCastsByScopeFrame[elseWrap.frameIndex].isNullOrEmpty()
    }

    private fun emitTernaryIfCore(ifFrame: PacketFrame): String {
        val condWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.CONDITION_ID)!!
        val thenWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.THEN_ID)!!
        val elseWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.ELSE_ID)!!
        val cond = FrameAstHelpers.childFrames(stream, condWrap).first()
        val thenChild = FrameAstHelpers.childFrames(stream, thenWrap).first()
        val elseChild = FrameAstHelpers.childFrames(stream, elseWrap).first()
        return "${emitExpression(cond)} ? ${emitExpression(thenChild)} : ${emitExpression(elseChild)}"
    }

    private fun emitExpressionIf(ifFrame: PacketFrame): String {
        if (canEmitTernaryIf(ifFrame)) {
            return "(${emitTernaryIfCore(ifFrame)})"
        }
        val iifeRetSuffix = iifeAnyRetSuffix()
        return withinExpressionIife {
            "[&]()$iifeRetSuffix {\n" +
                emitIfStatement(ifFrame, "    ", inValuePosition = true, targetReturn = true, targetVar = null) +
                "}()"
        }
    }

    private fun containsUnnestedUnlabeledBreak(frame: PacketFrame): Boolean {
        val unwrapped = unwrapTransparentFrame(frame)
        when (unwrapped.nodeTypeId) {
            KtNodeTypes.WHILE_ID,
            KtNodeTypes.DO_WHILE_ID,
            KtNodeTypes.FOR_ID,
            KtNodeTypes.LAMBDA_EXPRESSION_ID,
            KtNodeTypes.FUN_ID,
            -> return false
            KtNodeTypes.BREAK_ID ->
                return FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.LABEL_QUALIFIER_ID) == null &&
                    FrameAstHelpers.firstChildFrame(stream, unwrapped, KtNodeTypes.LABEL_ID) == null
        }
        return FrameAstHelpers.childFrames(stream, unwrapped).any { containsUnnestedUnlabeledBreak(it) }
    }

    private fun canEmitCppSwitch(entries: List<PacketFrame>): Boolean {
        if (entries.isEmpty()) return false
        var hasCaseLabel = false
        for (entry in entries) {
            if (!bundle.cfPlan.smartCastsByScopeFrame[entry.frameIndex].isNullOrEmpty()) return false
            val bodyFrame = FrameAstHelpers.childFrames(stream, entry).lastOrNull()
            if (bodyFrame != null && containsUnnestedUnlabeledBreak(bodyFrame)) return false
            val isElse = FrameAstHelpers.hasDirectKeyword(stream, entry, KtTokens.ELSE_KEYWORD_ID)
            if (isElse) continue
            val condFrames = FrameAstHelpers.childFrames(stream, entry).dropLast(1)
            if (condFrames.isEmpty()) return false
            for (cf in condFrames) {
                if (cf.nodeTypeId != KtNodeTypes.WHEN_CONDITION_EXPRESSION_ID) return false
                val inner = FrameAstHelpers.childFrames(stream, cf).firstOrNull() ?: return false
                if (!isSwitchConstantExpr(inner)) return false
                hasCaseLabel = true
            }
        }
        return hasCaseLabel
    }

    private fun isSwitchConstantExpr(expr: PacketFrame): Boolean =
        when (expr.nodeTypeId) {
            KtNodeTypes.INTEGER_CONSTANT_ID,
            KtNodeTypes.CHARACTER_CONSTANT_ID,
            -> true
            KtNodeTypes.PREFIX_EXPRESSION_ID -> {
                val operand =
                    FrameAstHelpers
                        .childFrames(stream, expr)
                        .lastOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
                operand?.nodeTypeId == KtNodeTypes.INTEGER_CONSTANT_ID
            }
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val name = FrameAstHelpers.directIdentPacket(stream, expr)?.unescapedName.orEmpty()
                isEnumOrAllCapsName(name)
            }
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                val parts = FrameAstHelpers.childFrames(stream, expr)
                parts.size == 2 &&
                    parts[1].nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                    isEnumOrAllCapsName(FrameAstHelpers.directIdentPacket(stream, parts[1])?.unescapedName.orEmpty())
            }
            else -> false
        }

    private fun isEnumOrAllCapsName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (enclosingClass?.enumEntries?.any { it.kotlinName == name } == true) return true
        val allClasses = bundle.symbols.classesByName.values
        if (allClasses.any { cls -> cls.enumEntries.any { it.kotlinName == name } }) return true
        return name.any { it.isLetter() } && name.all { it.isUpperCase() || it.isDigit() || it == '_' }
    }

    private fun isElvisControlTransferTarget(frame: PacketFrame): Boolean {
        val unwrapped = unwrapTransparentFrame(frame)
        return unwrapped.nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES ||
            doesBranchAlwaysTransferControl(unwrapped)
    }

    private fun doesBranchAlwaysTransferControl(branchFrame: PacketFrame): Boolean {
        val unwrapped = unwrapTransparentFrame(branchFrame)
        if (PacketFlags.hasFlag(unwrapped.flags, PacketFlags.DIVERGING_EXPR)) return true
        return when (unwrapped.nodeTypeId) {
            KtNodeTypes.RETURN_ID,
            KtNodeTypes.THROW_ID,
            KtNodeTypes.BREAK_ID,
            KtNodeTypes.CONTINUE_ID,
            -> true
            KtNodeTypes.CALL_EXPRESSION_ID -> {
                val calleeName = extractCalleeSimpleName(unwrapped)
                if (calleeName == "error" || calleeName == "TODO" || calleeName == "noImpl") {
                    true
                } else if (calleeName == "run" || calleeName == "let") {
                    val lambdaArg =
                        FrameAstHelpers
                            .firstChildFrame(stream, unwrapped, KtNodeTypes.LAMBDA_ARGUMENT_ID)
                            ?.let { FrameAstHelpers.unwrapLambdaExpression(stream, it) }
                    val body = lambdaArg?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.BLOCK_ID) }
                    body != null && doesBranchAlwaysTransferControl(body)
                } else {
                    false
                }
            }
            KtNodeTypes.BLOCK_ID -> {
                val lastStmt = FrameAstHelpers.childFrames(stream, unwrapped).lastOrNull()
                lastStmt != null && doesBranchAlwaysTransferControl(lastStmt)
            }
            KtNodeTypes.IF_ID -> {
                val thenBranch =
                    FrameAstHelpers
                        .firstChildFrame(stream, unwrapped, KtNodeTypes.THEN_ID)
                        ?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                val elseBranch =
                    FrameAstHelpers
                        .firstChildFrame(stream, unwrapped, KtNodeTypes.ELSE_ID)
                        ?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                thenBranch != null &&
                    elseBranch != null &&
                    doesBranchAlwaysTransferControl(thenBranch) &&
                    doesBranchAlwaysTransferControl(elseBranch)
            }
            KtNodeTypes.WHEN_ID -> {
                val entries = FrameAstHelpers.childFramesOfType(stream, unwrapped, KtNodeTypes.WHEN_ENTRY_ID)
                val hasElse = entries.any { FrameAstHelpers.hasDirectKeyword(stream, it, KtTokens.ELSE_KEYWORD_ID) }
                hasElse &&
                    entries.isNotEmpty() &&
                    entries.all { entry ->
                        val body = FrameAstHelpers.childFrames(stream, entry).lastOrNull()
                        body != null && doesBranchAlwaysTransferControl(body)
                    }
            }
            else -> false
        }
    }

    private fun isDirectIdentityOperand(expr: PacketFrame): Boolean {
        return when (expr.nodeTypeId) {
            KtNodeTypes.NULL_ID,
            KtNodeTypes.BOOLEAN_CONSTANT_ID,
            KtNodeTypes.INTEGER_CONSTANT_ID,
            KtNodeTypes.CHARACTER_CONSTANT_ID,
            -> true
            KtNodeTypes.THIS_EXPRESSION_ID ->
                enclosingClass?.kind == KtClassKind.RICH_ENUM || enclosingClass?.kind == KtClassKind.SIMPLE_ENUM
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val name = FrameAstHelpers.directIdentPacket(stream, expr)?.unescapedName.orEmpty()
                if (isEnumOrAllCapsName(name)) return true
                val rendered = emitReferenceExpr(expr)
                rendered.contains("::") || isKnownPointerOrAnyBinding(name)
            }
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                val parts = FrameAstHelpers.childFrames(stream, expr)
                parts.size == 2 &&
                    parts[1].nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                    isEnumOrAllCapsName(FrameAstHelpers.directIdentPacket(stream, parts[1])?.unescapedName.orEmpty())
            }
            else -> false
        }
    }

    private fun isKnownPointerOrAnyBinding(kotlinName: String): Boolean {
        for (fnSym in bundle.symbols.functionsByFrame.values) {
            val match = fnSym.valueParams.firstOrNull { it.name == kotlinName }
            if (match?.typeFrameIndex != null) {
                val tf = stream.frames[match.typeFrameIndex]
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf)
                if (FrameAstHelpers.isNullableTypeFrame(stream, tf) || simple == "Any") return true
                val cppT = bundle.typeTranslator.translateTypeFrame(tf, CppTypePosition.PARAMETER)
                if (cppT.endsWith("*") || cppT.startsWith("std::shared_ptr<")) return true
            }
        }
        return false
    }

    private fun emitLambdaClosureExpr(
        lambdaFrame: PacketFrame,
        receiverTypeRef: PacketFrame? = null,
        expectedValueParamCount: Int? = null,
    ): String {
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
        val pList = fnLit?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.VALUE_PARAMETER_LIST_ID) }
        val params = FrameAstHelpers.extractValueParams(stream, pList)
        val bodyBlock = fnLit?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.BLOCK_ID) }

        return withLexicalScope {
            if (receiverTypeRef != null) {
                val recvSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, receiverTypeRef)
                val recvCls = recvSimple?.let { lookupAccessibleClassByName(it) }
                val recvCppT = translateType(receiverTypeRef, CppTypePosition.EXTENSION_RECEIVER)
                val recvIsPtr =
                    recvCppT.startsWith("std::shared_ptr<") ||
                        recvCppT.startsWith("const std::shared_ptr<") ||
                        recvCppT.endsWith("*") ||
                        (recvSimple != null && isSharedThisClass(recvSimple))
                receiverSubstitution = "kxs_this"
                extensionReceiverClass = recvCls
                extensionReceiverIsPointer = recvIsPtr
                extensionReceiverSimpleName = recvCls?.kotlinName ?: recvSimple
            }
            val explicitParamDecls =
                if (params.isNotEmpty()) {
                    params.mapIndexed { idx, vp ->
                        val isUnused = vp.name == "_"
                        val pName = if (isUnused) "unused_$idx" else vp.cppName
                        if (!isUnused) {
                            bindLocalName(vp.name, pName)
                        }
                        val t =
                            vp.typeFrameIndex
                                ?.let { translateType(stream.frames[it], CppTypePosition.PARAMETER) }
                                ?: "auto&&"
                        val unusedAttr = if (isUnused) "[[maybe_unused]] " else ""
                        "$unusedAttr$t $pName"
                    }
                } else if (bodyBlock != null &&
                    referencesImplicitIt(bodyBlock) &&
                    (receiverTypeRef == null || (expectedValueParamCount ?: 0) == 1)
                ) {
                    bindLocalName("it", "it")
                    listOf("auto&& it")
                } else {
                    emptyList()
                }
            val paramDecls =
                if (receiverTypeRef != null) {
                    listOf("[[maybe_unused]] auto&& kxs_this") + explicitParamDecls
                } else {
                    explicitParamDecls
                }
            val bodyCode =
                withinExpressionIife {
                    if (bodyBlock != null) {
                        val stmts = FrameAstHelpers.childFrames(stream, bodyBlock)
                        if (stmts.size == 1 && canEmitSingleLambdaExprReturn(stmts[0])) {
                            "{ return ${emitExpression(stmts[0])}; }"
                        } else {
                            val isValBlk =
                                stmts.isNotEmpty() &&
                                    canEmitLambdaTailReturn(stmts.last()) &&
                                    !containsBareVoidReturnInLambda(bodyBlock)
                            emitBlock(bodyBlock, "", isValueBlock = isValBlk, resultTargetVar = null)
                        }
                    } else {
                        "{}"
                    }
                }
            val capture = if (inDefaultParameterExpr) "[]" else "[&]"
            "$capture(${paramDecls.joinToString(", ")}) $bodyCode"
        }
    }

    private fun canEmitLambdaTailReturn(stmtFrame: PacketFrame): Boolean {
        val s = unwrapTransparentFrame(stmtFrame)
        if (s.nodeTypeId in STATEMENT_ONLY_NODE_TYPES || s.nodeTypeId == KtNodeTypes.DESTRUCTURING_DECLARATION_ID) {
            return false
        }
        if (s.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
            FrameAstHelpers.directIdentPacket(stream, s)?.unescapedName == "Unit"
        ) {
            return false
        }
        return when (s.nodeTypeId) {
            KtNodeTypes.IF_ID -> {
                val thenCh = FrameAstHelpers.firstChildFrame(stream, s, KtNodeTypes.THEN_ID)?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                val elseCh = FrameAstHelpers.firstChildFrame(stream, s, KtNodeTypes.ELSE_ID)?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                thenCh != null && elseCh != null && canEmitLambdaTailReturn(thenCh) && canEmitLambdaTailReturn(elseCh)
            }
            KtNodeTypes.WHEN_ID -> {
                val entries = FrameAstHelpers.childFramesOfType(stream, s, KtNodeTypes.WHEN_ENTRY_ID)
                entries.isNotEmpty() &&
                    entries.any { FrameAstHelpers.hasDirectKeyword(stream, it, KtTokens.ELSE_KEYWORD_ID) } &&
                    entries.all { e -> FrameAstHelpers.childFrames(stream, e).lastOrNull()?.let { canEmitLambdaTailReturn(it) } == true }
            }
            KtNodeTypes.TRY_ID -> {
                val tryBlk = FrameAstHelpers.firstChildFrame(stream, s, KtNodeTypes.BLOCK_ID)
                val catches = FrameAstHelpers.childFramesOfType(stream, s, KtNodeTypes.CATCH_ID)
                tryBlk != null &&
                    canEmitLambdaTailReturn(tryBlk) &&
                    catches.all { c ->
                        val cBlk = FrameAstHelpers.firstChildFrame(stream, c, KtNodeTypes.BLOCK_ID)
                        cBlk != null && (doesBranchAlwaysTransferControl(cBlk) || canEmitLambdaTailReturn(cBlk))
                    }
            }
            KtNodeTypes.BLOCK_ID -> {
                val last = FrameAstHelpers.childFrames(stream, s).lastOrNull()
                last != null && canEmitLambdaTailReturn(last)
            }
            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val ch = FrameAstHelpers.childFrames(stream, s)
                if (ch.size < 3) return true
                val opText = FrameAstHelpers.operationSymbolName(stream, ch[1])
                opText !in ASSIGN_OPS
            }
            else -> true
        }
    }

    private fun containsBareVoidReturnInLambda(frame: PacketFrame): Boolean {
        if (frame.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID || frame.nodeTypeId == KtNodeTypes.FUN_ID) {
            return false
        }
        if (frame.nodeTypeId == KtNodeTypes.RETURN_ID) {
            val exprChild =
                FrameAstHelpers
                    .childFrames(stream, frame)
                    .firstOrNull { it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID }
            if (exprChild == null) return true
            val u = unwrapTransparentFrame(exprChild)
            if (u.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                FrameAstHelpers.directIdentPacket(stream, u)?.unescapedName == "Unit"
            ) {
                return true
            }
        }
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            if (containsBareVoidReturnInLambda(child)) return true
        }
        return false
    }

    private fun canEmitSingleLambdaExprReturn(stmtFrame: PacketFrame): Boolean {
        val s = unwrapTransparentFrame(stmtFrame)
        if (s.nodeTypeId in STATEMENT_ONLY_NODE_TYPES) return false
        return when (s.nodeTypeId) {
            KtNodeTypes.DESTRUCTURING_DECLARATION_ID,
            KtNodeTypes.BLOCK_ID,
            KtNodeTypes.TRY_ID,
            KtNodeTypes.WHEN_ID,
            -> false
            KtNodeTypes.IF_ID -> canEmitTernaryIf(s)
            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val ch = FrameAstHelpers.childFrames(stream, s)
                if (ch.size < 3) return true
                val opText = FrameAstHelpers.operationSymbolName(stream, ch[1])
                if (opText in ASSIGN_OPS) return false
                if (opText == "?:" && isElvisControlTransferTarget(ch[2])) {
                    return false
                }
                true
            }
            else -> true
        }
    }

    private fun collectInlineElvisControlTransfers(
        frame: PacketFrame,
        out: MutableList<PacketFrame>,
    ) {
        if (frame.frameIndex in preLoweredElvisExprs) return
        if (frame.nodeTypeId in COMPOUND_OR_DECLARATION_STMT_NODE_TYPES ||
            frame.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
        ) {
            return
        }
        if (frame.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val ch = FrameAstHelpers.childFrames(stream, frame)
            if (ch.size >= 3 &&
                FrameAstHelpers.operationTokenId(stream, ch[1]) == KtTokens.ELVIS_ID &&
                isElvisControlTransferTarget(ch[2])
            ) {
                collectInlineElvisControlTransfers(ch[0], out)
                out.add(frame)
                return
            }
        }
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            collectInlineElvisControlTransfers(child, out)
        }
    }

    private fun resolveCallableRefMemberAccess(
        startCls: KtClassSymbol,
        memberKt: String,
        visited: MutableSet<Int> = HashSet(),
    ): String? {
        if (!visited.add(startCls.frameIndex)) return null
        val ctorProp = startCls.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == memberKt }
        if (ctorProp != null) {
            val isPriv = PacketFlags.hasFlag(stream.frames[ctorProp.frameIndex].flags, PacketFlags.MOD_PRIVATE)
            return when {
                startCls.kind == KtClassKind.VALUE_CLASS -> CppNamingPolicy.toPrivateFieldName(ctorProp.cppName)
                isPriv -> CppNamingPolicy.toPrivateFieldName(memberKt)
                isBackedOverrideCtorParamExpr(startCls, ctorProp) ->
                    "${resolveClassPropGetterCppName(startCls, ctorProp.name, ctorProp.cppName)}()"
                else -> ctorProp.cppName
            }
        }
        for (mPropIdx in startCls.memberPropertyFrameIndices) {
            val mProp = bundle.symbols.propertiesByFrame[mPropIdx] ?: continue
            if (mProp.kotlinName == memberKt) {
                return if (isGetterStyleClassProperty(startCls, mProp)) {
                    "${resolveClassPropGetterCppName(startCls, mProp.kotlinName, mProp.cppName)}()"
                } else {
                    mProp.cppName
                }
            }
        }
        for (mFnIdx in startCls.memberFunctionFrameIndices) {
            val mFn = bundle.symbols.functionsByFrame[mFnIdx] ?: continue
            if (mFn.kotlinName == memberKt && mFn.valueParams.isEmpty()) {
                return "${mFn.cppName}()"
            }
        }
        for (superIdx in startCls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            val superCls = lookupAccessibleClassByName(superSimple) ?: continue
            resolveCallableRefMemberAccess(superCls, memberKt, visited)?.let { return it }
        }
        return null
    }

    private fun emitCallableRefExpr(refExpr: PacketFrame): String {
        val ch = FrameAstHelpers.childFrames(stream, refExpr)
        if (!bundle.isDocExample && ch.size >= 2) {
            val lhsFrame = ch[0]
            val rhs =
                FrameAstHelpers.directIdentPacket(stream, ch[1])?.unescapedName
                    ?: FrameAstHelpers.operationSymbolName(stream, ch[1])
            if (rhs == "classSimpleName") {
                val fnQual =
                    CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                        kotlinName = "classSimpleName",
                        cppBaseName = "class_simple_name",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "class_simple_name"
                return "$fnQual(${emitExpression(lhsFrame)})"
            }
            val lhsSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, lhsFrame)
            val lhsHasStar =
                FrameAstHelpers.findDescendantFrame(stream, lhsFrame, KtNodeTypes.TYPE_PROJECTION_ID) != null
            val cls = lhsSimple?.let { lookupAccessibleClassByName(it) }
            val snakeRhs = CppNamingPolicy.toSnakeCase(rhs)
            if (cls != null) {
                val resolvedMemberAccess = resolveCallableRefMemberAccess(cls, rhs)
                if (resolvedMemberAccess != null) {
                    includes.addSystem("type_traits")
                    return "[](const auto& kxs_cr) -> decltype(auto) { " +
                        "using KxsCR = std::remove_cvref_t<decltype(kxs_cr)>; " +
                        "if constexpr (std::is_pointer_v<KxsCR> || requires { kxs_cr.operator->(); }) return kxs_cr->$resolvedMemberAccess; " +
                        "else return kxs_cr.$resolvedMemberAccess; }"
                }
            }
            val lhsType =
                when (lhsFrame.nodeTypeId) {
                    KtNodeTypes.TYPE_REFERENCE_ID,
                    KtNodeTypes.USER_TYPE_ID,
                    -> bundle.typeTranslator.translateTypeFrame(lhsFrame, CppTypePosition.TYPE_ARGUMENT)
                    else -> {
                        val simple = FrameAstHelpers.dottedNameText(stream, lhsFrame).substringAfterLast('.').ifEmpty { "void" }
                        CppNamingPolicy.toTypeName(simple)
                    }
                }
            if (cls != null || lhsHasStar || lhsType.contains("<std::any>")) {
                includes.addSystem("type_traits")
                return "[](const auto& kxs_cr) -> decltype(auto) { " +
                    "using KxsCR = std::remove_cvref_t<decltype(kxs_cr)>; " +
                    "if constexpr (std::is_pointer_v<KxsCR> || requires { kxs_cr.operator->(); }) { " +
                    "if constexpr (requires { kxs_cr->$snakeRhs(); }) return kxs_cr->$snakeRhs(); " +
                    "else return kxs_cr->$snakeRhs; " +
                    "} else { " +
                    "if constexpr (requires { kxs_cr.$snakeRhs(); }) return kxs_cr.$snakeRhs(); " +
                    "else return kxs_cr.$snakeRhs; } }"
            }
            return "&$lhsType::$snakeRhs"
        }
        return if (ch.size >= 2) {
            val lhsFrame = ch[0]
            val lhsType =
                when (lhsFrame.nodeTypeId) {
                    KtNodeTypes.TYPE_REFERENCE_ID,
                    KtNodeTypes.USER_TYPE_ID,
                    -> bundle.typeTranslator.translateTypeFrame(lhsFrame, CppTypePosition.TYPE_ARGUMENT)
                    else -> {
                        val simple = FrameAstHelpers.dottedNameText(stream, lhsFrame).substringAfterLast('.').ifEmpty { "void" }
                        CppNamingPolicy.toTypeName(simple)
                    }
                }
            val rhs =
                FrameAstHelpers.directIdentPacket(stream, ch[1])?.unescapedName
                    ?: FrameAstHelpers.operationSymbolName(stream, ch[1])
            "&$lhsType::${CppNamingPolicy.toSnakeCase(rhs)}"
        } else {
            val sole = ch.lastOrNull()?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()
            "&${CppNamingPolicy.toSnakeCase(sole)}"
        }
    }

    private fun emitAtomicOpExpression(aop: AtomicOperationSite): String {
        includes.addSystem("atomic")
        val target = renderAtomicTargetLvalue(aop)
        val args = aop.argExprFrameIndices.map { emitExpression(stream.frames[it]) }
        return when (aop.kind) {
            AtomicOpKind.LOAD_SEQ_CST -> "$target.load(std::memory_order_seq_cst)"
            AtomicOpKind.STORE_SEQ_CST -> "$target.store(${args.firstOrNull() ?: "{}"}, std::memory_order_seq_cst)"
            AtomicOpKind.STORE_RELEASE -> "$target.store(${args.firstOrNull() ?: "{}"}, std::memory_order_release)"
            AtomicOpKind.COMPARE_AND_SET -> {
                val expCode = args.getOrNull(0) ?: "{}"
                val updCode = args.getOrNull(1) ?: "{}"
                if (aop.useKonanCasBarrier) {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    includes.addSystem("memory")
                    "Kotlin_mm_CompareAndSetVolatileHeapRef(std::addressof($target), $expCode, $updCode)"
                } else {
                    // C++ `compare_exchange_strong` mutates its first parameter by lvalue reference!
                    // Bind `expected` to a local temporary inside an inline lambda so rvalues compile cleanly
                    // and caller lvalues are never mutated!
                    val expVar = freshTemp("kxs_expected")
                    "([&]() { auto $expVar = $expCode; return $target.compare_exchange_strong(" +
                        "$expVar, $updCode, std::memory_order_seq_cst, std::memory_order_seq_cst); }())"
                }
            }
            AtomicOpKind.EXCHANGE -> "$target.exchange(${args.firstOrNull() ?: "{}"}, std::memory_order_seq_cst)"
            AtomicOpKind.INCREMENT_AND_GET ->
                "([&]() { return ($target.fetch_add(1, std::memory_order_seq_cst) + 1); }())"
            AtomicOpKind.GET_AND_INCREMENT -> "$target.fetch_add(1, std::memory_order_seq_cst)"
            AtomicOpKind.DECREMENT_AND_GET ->
                "([&]() { return ($target.fetch_sub(1, std::memory_order_seq_cst) - 1); }())"
            AtomicOpKind.GET_AND_DECREMENT -> "$target.fetch_sub(1, std::memory_order_seq_cst)"
            AtomicOpKind.ADD_AND_GET -> {
                val d = args.firstOrNull() ?: "0"
                "([&]() { const auto kxs_d = $d; return $target.fetch_add(kxs_d, std::memory_order_seq_cst) + kxs_d; }())"
            }
            AtomicOpKind.GET_AND_ADD -> "$target.fetch_add(${args.firstOrNull() ?: "0"}, std::memory_order_seq_cst)"
            AtomicOpKind.GET_AND_UPDATE,
            AtomicOpKind.UPDATE_AND_GET,
            -> {
                val curKt = aop.loopCurParamName ?: "it"
                val curCpp = CppNamingPolicy.toSnakeCase(curKt)
                val lambdaFrame = aop.loopLambdaFrameIndex?.let { stream.frames[it] }
                val fnLit = lambdaFrame?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.FUNCTION_LITERAL_ID) }
                val body = fnLit?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.BLOCK_ID) ?: it }
                val retExpr = if (aop.kind == AtomicOpKind.GET_AND_UPDATE) curCpp else "kxs_next"
                withLexicalScope {
                    bindLocalName(curKt, curCpp)
                    val computeNext =
                        withinExpressionIife {
                            if (body != null && body.nodeTypeId == KtNodeTypes.BLOCK_ID) {
                                "[&]() ${emitBlock(body, "", isValueBlock = true, resultTargetVar = null)}()"
                            } else if (body != null) {
                                emitExpression(body)
                            } else {
                                curCpp
                            }
                        }
                    "([&]() { while (true) { auto $curCpp = $target.load(std::memory_order_seq_cst); " +
                        "auto kxs_next = $computeNext; " +
                        "if ($target.compare_exchange_strong($curCpp, kxs_next, std::memory_order_seq_cst, std::memory_order_relaxed)) " +
                        "return $retExpr; } }())"
                }
            }
            AtomicOpKind.LOOP,
            AtomicOpKind.UPDATE,
            ->
                withinExpressionIife {
                    "([&]() {\n${emitAtomicLoopOrUpdateStatement(aop, "    ")}}())"
                }
        }
    }

    private fun renderAtomicTargetLvalue(aop: AtomicOperationSite): String {
        val baseField =
            if (aop.receiverPrefixExprFrameIndex != null) {
                val prefFrame = stream.frames[aop.receiverPrefixExprFrameIndex]
                val prefCode = emitExpression(prefFrame)
                val op = if (isPointerOrSharedPtrReceiver(prefFrame, prefCode)) "->" else "."
                "$prefCode$op${aop.targetFieldInfo.cppName}"
            } else {
                val ownerClsIdx =
                    bundle.symbols.propertiesByFrame[aop.targetFieldInfo.propFrameIndex]?.enclosingClassFrameIndex
                val bound = if (ownerClsIdx == null) resolveBoundName(aop.targetFieldInfo.kotlinName) else null
                if (bound != null) {
                    bound
                } else {
                    val outerPrefix = resolveOuterChainPrefix(ownerClsIdx).orEmpty()
                    val templateBasePrefix =
                        if (outerPrefix.isEmpty() &&
                            enclosingClass != null &&
                            enclosingClass.typeParams.isNotEmpty() &&
                            ownerClsIdx != null &&
                            ownerClsIdx != enclosingClass.frameIndex
                        ) {
                            "this->"
                        } else {
                            ""
                        }
                    "$outerPrefix$templateBasePrefix${aop.targetFieldInfo.cppName}"
                }
            }
        return if (aop.arrayIndexExprFrameIndex != null) {
            val idxCode = emitExpression(stream.frames[aop.arrayIndexExprFrameIndex])
            "$baseField[$idxCode]"
        } else {
            baseField
        }
    }

    private fun emitCInteropOpExpression(cop: CInteropExprSite): String {
        val recv = cop.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) }.orEmpty()
        val targetT =
            cop.targetTypeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT)
            } ?: "void"
        val arg = cop.argExprFrameIndex?.let { emitExpression(stream.frames[it]) }
        return when (cop.kind) {
            CInteropOpKind.POINTED -> "(*$recv)"
            CInteropOpKind.PTR -> {
                includes.addSystem("memory")
                "std::addressof($recv)"
            }
            CInteropOpKind.CSTR -> "$recv.c_str()"
            CInteropOpKind.REINTERPRET -> {
                val ptrType = if (isRawPointerCppType(targetT)) targetT else "$targetT*"
                "reinterpret_cast<$ptrType>($recv)"
            }
            CInteropOpKind.TO_CPOINTER -> {
                includes.addSystem("cstdint")
                val ptrType = if (isRawPointerCppType(targetT)) targetT else "$targetT*"
                "reinterpret_cast<$ptrType>(static_cast<std::uintptr_t>($recv))"
            }
            CInteropOpKind.TO_LONG_FROM_PTR -> {
                includes.addSystem("cstdint")
                "reinterpret_cast<std::intptr_t>($recv)"
            }
            CInteropOpKind.ADDRESS_OF -> {
                val idx = arg ?: "0"
                "($recv.data() + $idx)"
            }
            CInteropOpKind.ALLOC_SCALAR -> "$targetT{}"
            CInteropOpKind.ALLOC_ARRAY -> {
                includes.addSystem("vector")
                "std::vector<$targetT>(static_cast<std::size_t>(${arg ?: "0"})).data()"
            }
            CInteropOpKind.INTERPRET_CPOINTER -> {
                val ptrType = if (isRawPointerCppType(targetT)) targetT else "$targetT*"
                "reinterpret_cast<$ptrType>(${arg ?: "nullptr"})"
            }
            CInteropOpKind.RAW_VALUE -> "static_cast<void*>($recv)"
            CInteropOpKind.CONVERT ->
                if (cop.targetTypeFrameIndex != null) {
                    "static_cast<$targetT>($recv)"
                } else {
                    "([](auto kxs_cv) { struct KxsConv { decltype(kxs_cv) v; template <typename KxsDst> operator KxsDst() const noexcept { return static_cast<KxsDst>(v); } }; return KxsConv{kxs_cv}; }($recv))"
                }
            CInteropOpKind.NATIVE_PTR_NULL -> "static_cast<void*>(nullptr)"
        }
    }

    private fun isRawPointerCppType(cppType: String): Boolean =
        cppType.endsWith("*") ||
            (cppType.startsWith("CF") && cppType.endsWith("Ref")) ||
            cppType in setOf("dispatch_queue_t", "dispatch_block_t", "dispatch_object_t", "dispatch_source_t")

    private fun inferControlFlowResultType(cfFrame: PacketFrame): String {
        // Inspect all branch value leaves for literal or same-file return type hints;
        // default to `std::any` only when genuinely heterogeneous/unknown.
        val leaves = ArrayList<PacketFrame>()
        collectBranchValueLeaves(cfFrame, leaves)
        for (leaf in leaves) {
            when (leaf.nodeTypeId) {
                KtNodeTypes.STRING_TEMPLATE_ID -> {
                    includes.addSystem("string")
                    return "std::string"
                }
                KtNodeTypes.BOOLEAN_CONSTANT_ID,
                KtNodeTypes.IS_EXPRESSION_ID,
                -> return "bool"
                KtNodeTypes.INTEGER_CONSTANT_ID -> {
                    includes.addSystem("cstdint")
                    val pkt = stream.packets.getOrNull(leaf.firstPacketIndex) as? SyntaxPacket.LiteralPacket
                    return if (pkt?.numericWidthBits == 64) "std::int64_t" else "std::int32_t"
                }
                KtNodeTypes.FLOAT_CONSTANT_ID -> return "double"
                KtNodeTypes.CALL_EXPRESSION_ID -> {
                    val callee = extractCalleeSimpleName(leaf)
                    val sameFileRetIdx =
                        bundle.symbols.functionsByName[callee]
                            ?.firstOrNull()
                            ?.returnTypeFrameIndex
                    if (sameFileRetIdx != null) {
                        val resolved =
                            bundle.typeTranslator.translateTypeFrame(
                                stream.frames[sameFileRetIdx],
                                CppTypePosition.TYPE_ARGUMENT,
                            )
                        if (resolved != "void") {
                            return resolved
                        }
                    }
                }
            }
        }
        includes.addSystem("any")
        return "std::any"
    }

    private fun collectBranchValueLeaves(
        frame: PacketFrame,
        out: MutableList<PacketFrame>,
    ) {
        when (frame.nodeTypeId) {
            KtNodeTypes.IF_ID -> {
                FrameAstHelpers
                    .firstChildFrame(stream, frame, KtNodeTypes.THEN_ID)
                    ?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                    ?.let { collectBranchValueLeaves(it, out) }
                FrameAstHelpers
                    .firstChildFrame(stream, frame, KtNodeTypes.ELSE_ID)
                    ?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                    ?.let { collectBranchValueLeaves(it, out) }
            }
            KtNodeTypes.WHEN_ID -> {
                for (e in FrameAstHelpers.childFramesOfType(stream, frame, KtNodeTypes.WHEN_ENTRY_ID)) {
                    FrameAstHelpers.childFrames(stream, e).lastOrNull()?.let { collectBranchValueLeaves(it, out) }
                }
            }
            KtNodeTypes.TRY_ID -> {
                FrameAstHelpers
                    .firstChildFrame(stream, frame, KtNodeTypes.BLOCK_ID)
                    ?.let { collectBranchValueLeaves(it, out) }
                for (c in FrameAstHelpers.childFramesOfType(stream, frame, KtNodeTypes.CATCH_ID)) {
                    FrameAstHelpers
                        .firstChildFrame(stream, c, KtNodeTypes.BLOCK_ID)
                        ?.let { collectBranchValueLeaves(it, out) }
                }
            }
            KtNodeTypes.BLOCK_ID -> {
                FrameAstHelpers.childFrames(stream, frame).lastOrNull()?.let { collectBranchValueLeaves(it, out) }
            }
            else -> out.add(frame)
        }
    }

    private fun referencesImplicitIt(root: PacketFrame): Boolean {
        if (root.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID) return false
        if (root.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
            FrameAstHelpers.directIdentPacket(stream, root)?.unescapedName == "it"
        ) {
            return true
        }
        for (child in FrameAstHelpers.childFrames(stream, root)) {
            if (referencesImplicitIt(child)) return true
        }
        return false
    }

    private fun extractCalleeSimpleName(callFrame: PacketFrame): String {
        val ref = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        return if (ref != null) {
            FrameAstHelpers.directIdentPacket(stream, ref)?.unescapedName.orEmpty()
        } else {
            FrameAstHelpers.directIdentPacket(stream, callFrame)?.unescapedName.orEmpty()
        }
    }

    private fun formatIndentedRawCpp(
        rawCpp: String,
        indent: String,
    ): String =
        buildString {
            for (line in rawCpp.trimIndent().lines()) {
                if (line.isBlank()) {
                    append('\n')
                } else {
                    append(indent).append(line).append('\n')
                }
            }
        }

    private fun isEnclosingClassProperty(kotlinName: String): Boolean {
        var cur: KtClassSymbol? = enclosingClass
        while (cur != null) {
            if (cur.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == kotlinName }) return true
            if (cur.memberPropertyFrameIndices.any { bundle.symbols.propertiesByFrame[it]?.kotlinName == kotlinName }) return true
            if (!cur.isInner) break
            cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        return false
    }

    private fun resolvePrimarySuperCppType(superFrame: PacketFrame? = null): String =
        resolvePrimarySuperSimpleAndCpp(superFrame).second

    private fun resolvePrimarySuperSimpleAndCpp(superFrame: PacketFrame? = null): Pair<String?, String> {
        if (superFrame != null) {
            val explicitTr = FrameAstHelpers.firstChildFrame(stream, superFrame, KtNodeTypes.TYPE_REFERENCE_ID)
            if (explicitTr != null) {
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, explicitTr)
                val (cppBase, _) = bundle.typeTranslator.translateSuperBaseType(explicitTr)
                return simple to cppBase
            }
        }
        val cls = enclosingClass ?: return null to "Base"
        val nonDelegated = cls.superTypeFrameIndices.filter { it !in cls.delegatedSuperTypeFrameIndices }
        val chosenIdx =
            nonDelegated.firstOrNull { idx ->
                idx in cls.superCallArgsBySuperIndex &&
                    !bundle.typeTranslator.translateSuperBaseType(stream.frames[idx]).second
            }
                ?: nonDelegated.firstOrNull { idx ->
                    !bundle.typeTranslator.translateSuperBaseType(stream.frames[idx]).second
                }
                ?: nonDelegated.firstOrNull()
                ?: return null to "Base"
        val trFrame = stream.frames[chosenIdx]
        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, trFrame)
        val (cppBase, _) = bundle.typeTranslator.translateSuperBaseType(trFrame)
        return simple to cppBase
    }

    private fun isIdentChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun containsUnquotedStandaloneIdent(
        code: String,
        word: String,
    ): Boolean {
        if (word.isEmpty()) return false
        var i = 0
        var inString = false
        var inChar = false
        var escaped = false
        while (i < code.length) {
            val ch = code[i]
            if (escaped) {
                escaped = false
                i += 1
                continue
            }
            if ((inString || inChar) && ch == '\\') {
                escaped = true
                i += 1
                continue
            }
            if (inString) {
                if (ch == '"') inString = false
                i += 1
                continue
            }
            if (inChar) {
                if (ch == '\'') inChar = false
                i += 1
                continue
            }
            if (ch == '"') {
                inString = true
                i += 1
                continue
            }
            if (ch == '\'') {
                inChar = true
                i += 1
                continue
            }
            if (code.startsWith(word, i)) {
                val prev = if (i > 0) code[i - 1] else '\u0000'
                val next = if (i + word.length < code.length) code[i + word.length] else '\u0000'
                val badPrev = isIdentChar(prev) || prev == '>' || prev == '.' || prev == ':'
                val badNext = isIdentChar(next)
                if (!badPrev && !badNext) return true
            }
            i += 1
        }
        return false
    }

    private fun replaceUnquotedStandaloneIdent(
        code: String,
        word: String,
        replacement: String,
    ): String {
        if (word.isEmpty()) return code
        return buildString(code.length) {
            var i = 0
            var inString = false
            var inChar = false
            var escaped = false
            while (i < code.length) {
                val ch = code[i]
                if (escaped) {
                    escaped = false
                    append(ch)
                    i += 1
                    continue
                }
                if ((inString || inChar) && ch == '\\') {
                    escaped = true
                    append(ch)
                    i += 1
                    continue
                }
                if (inString) {
                    if (ch == '"') inString = false
                    append(ch)
                    i += 1
                    continue
                }
                if (inChar) {
                    if (ch == '\'') inChar = false
                    append(ch)
                    i += 1
                    continue
                }
                if (ch == '"') {
                    inString = true
                    append(ch)
                    i += 1
                    continue
                }
                if (ch == '\'') {
                    inChar = true
                    append(ch)
                    i += 1
                    continue
                }
                if (code.startsWith(word, i)) {
                    val prev = if (i > 0) code[i - 1] else '\u0000'
                    val next = if (i + word.length < code.length) code[i + word.length] else '\u0000'
                    val badPrev = isIdentChar(prev) || prev == '>' || prev == '.' || prev == ':'
                    val badNext = isIdentChar(next)
                    if (!badPrev && !badNext) {
                        append(replacement)
                        i += word.length
                        continue
                    }
                }
                append(ch)
                i += 1
            }
        }
    }

    private fun isVerbatimCIdentifier(name: String): Boolean =
        name.startsWith("CF") ||
            name.startsWith("kCF") ||
            name.startsWith("CFAbsolute") ||
            name.startsWith("dispatch_") ||
            name.startsWith("DISPATCH_") ||
            name.startsWith("pthread_") ||
            name.startsWith("PTHREAD_") ||
            name.startsWith("mach_") ||
            name.startsWith("objc_")

    private fun recordExternalCHeaderForIdentifier(name: String) {
        when {
            name.startsWith("CF") ||
                name.startsWith("kCF") ||
                name.startsWith("CFAbsolute") ||
                name.startsWith("dispatch_") ||
                name.startsWith("DISPATCH_")
            -> includes.needsDarwinHeaders = true
            name.startsWith("pthread_") || name.startsWith("PTHREAD_") -> {
                includes.needsPthreadInclude = true
                includes.addSystem("pthread.h")
            }
        }
    }

    private fun renderClassLikeMethodParams(
        vps: List<KtValueParamInfo>,
        bodyCode: String,
    ): String =
        vps.joinToString(", ") { vp ->
            val pT =
                vp.typeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER)
                } ?: "auto"
            val unusedPrefix =
                if (!CppComments.containsWholeWordInCode(bodyCode, vp.cppName)) {
                    "[[maybe_unused]] "
                } else {
                    ""
                }
            "$unusedPrefix$pT ${vp.cppName}"
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

        private val CPP_BINARY_OPERATORS: Set<String> =
            setOf("+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">=", "&&", "||", "=", "+=", "-=", "*=", "/=", "%=")

        private val ASSIGN_OPS: Set<String> =
            setOf("=", "+=", "-=", "*=", "/=", "%=")

        private val ELVIS_CONTROL_TRANSFER_NODE_TYPES: Set<Int> =
            setOf(
                KtNodeTypes.RETURN_ID,
                KtNodeTypes.BREAK_ID,
                KtNodeTypes.CONTINUE_ID,
                KtNodeTypes.THROW_ID,
            )

        private val EXCEPTION_PTR_KOTLIN_TYPES: Set<String> =
            setOf(
                "Throwable",
                "Exception",
                "RuntimeException",
                "IllegalStateException",
                "IllegalArgumentException",
            )

        private val COMPOUND_OR_DECLARATION_STMT_NODE_TYPES: Set<Int> =
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

        private val STATEMENT_ONLY_NODE_TYPES: Set<Int> =
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

        private val CANCELLATION_EXCEPTION_CLASS_NAMES: Set<String> =
            setOf(
                "CancellationException",
                "JobCancellationException",
                "TimeoutCancellationException",
                "AbortFlowException",
                "ChildCancelledException",
                "CopyableThrowable",
            )

        private val CHANNEL_STATIC_CONSTANTS: Set<String> =
            setOf(
                "RENDEZVOUS",
                "CONFLATED",
                "UNLIMITED",
                "BUFFERED",
                "OPTIONAL_CHANNEL",
                "CHANNEL_DEFAULT_CAPACITY",
            )

        private val KNOWN_CALLABLE_TYPEALIASES: Set<String> =
            setOf(
                "CompletionHandler",
                "CancellationHandler",
                "OnUndeliveredElement",
            )

        private val KNOWN_GENERIC_SHARED_CLASSES: Set<String> =
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

        private val KNOWN_OBJECT_SINGLETONS: Set<String> =
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

        private val KNOWN_PASCAL_FACTORY_FUNCTIONS: Set<String> =
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

        private val KNOWN_CONTEXT_KEY_CARRIERS: Set<String> =
            setOf(
                "Job",
                "CoroutineName",
                "CoroutineId",
                "CoroutineExceptionHandler",
                "YieldContext",
                "TestCoroutineScheduler",
            )

        private val KNOWN_TO_STRING_SUPER_FRAGMENTS: Set<String> =
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

        private val DURATION_WHOLE_UNITS: Set<String> =
            setOf(
                "inWholeNanoseconds",
                "inWholeMicroseconds",
                "inWholeSeconds",
                "inWholeMinutes",
                "inWholeHours",
                "inWholeDays",
            )

        private val KNOWN_EXTERNAL_RECEIVER_PROPERTIES: Map<String, Set<String>> =
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

        private val KNOWN_EXTERNAL_RECEIVER_METHODS: Map<String, Set<String>> =
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

        private val DISPATCHER_OR_EVENT_LOOP_FACTORY_NAMES: Set<String> =
            setOf(
                "newFixedThreadPoolContext",
                "newSingleThreadContext",
                "createEventLoop",
                "takeEventLoop",
                "eventLoopOrNull",
                "currentOrNull",
            )

        private val DIRECT_ITERABLE_VALUE_TYPES: Set<String> =
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

        private val NON_SWITCH_SUBJECT_TYPES: Set<String> =
            setOf(
                "Any",
                "String",
                "Symbol",
                "Throwable",
                "Exception",
            )

        private val DIRECT_EQ_SCALAR_TYPES: Set<String> =
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

        private val VALUE_CONTAINER_CALL_NAMES: Set<String> =
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

        private val INLINE_SCOPE_HELPER_NAMES: Set<String> =
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

        private val ELEMENT_RETURNING_CONTAINER_METHODS: Set<String> =
            setOf(
                "removeFirstIf",
                "removeLastOrNull",
                "removeFirstOrNull",
                "firstOrNull",
                "lastOrNull",
            )

        private val INHERENTLY_MUTABLE_VAL_TYPES: Set<String> =
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

        private val INHERENTLY_MUTABLE_FACTORY_NAMES: Set<String> =
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
