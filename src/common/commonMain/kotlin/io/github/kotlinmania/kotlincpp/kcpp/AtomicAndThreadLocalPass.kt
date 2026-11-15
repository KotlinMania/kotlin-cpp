package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.LiteralPacketKind
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal data class AtomicFieldInfo(
    val propFrameIndex: Int,
    val kotlinName: String,
    val cppName: String,
    val explicitElementTypeFrameIndex: Int?,
    val inferredElementCppType: String?,
    val initExprFrameIndex: Int?,
    val isArray: Boolean,
    val arraySizeExprFrameIndex: Int?,
    val isReferenceElement: Boolean,
    val enclosingClassFrameIndex: Int? = null,
)

internal data class ThreadLocalSlotInfo(
    val propFrameIndex: Int,
    val kotlinName: String,
    val cppName: String,
    val explicitElementTypeFrameIndex: Int?,
    val defaultLambdaFrameIndex: Int?,
)

internal enum class AtomicOpKind {
    LOAD_SEQ_CST,
    STORE_SEQ_CST,
    STORE_RELEASE,
    COMPARE_AND_SET,
    EXCHANGE,
    INCREMENT_AND_GET,
    GET_AND_INCREMENT,
    DECREMENT_AND_GET,
    GET_AND_DECREMENT,
    ADD_AND_GET,
    GET_AND_ADD,
    LOOP,
    UPDATE,
    GET_AND_UPDATE,
    UPDATE_AND_GET,
}

internal data class AtomicOperationSite(
    val exprFrameIndex: Int,
    val targetFieldInfo: AtomicFieldInfo,
    val receiverPrefixExprFrameIndex: Int?,
    val arrayIndexExprFrameIndex: Int?,
    val kind: AtomicOpKind,
    val argExprFrameIndices: List<Int>,
    val loopLambdaFrameIndex: Int?,
    val loopCurParamName: String?,
    val useKonanCasBarrier: Boolean,
)

internal class AtomicAndThreadLocalPlan(
    val atomicFieldsByPropFrame: Map<Int, AtomicFieldInfo>,
    val atomicFieldsByKotlinName: Map<String, AtomicFieldInfo>,
    val threadLocalsByPropFrame: Map<Int, ThreadLocalSlotInfo>,
    val threadLocalsByKotlinName: Map<String, ThreadLocalSlotInfo>,
    val atomicOpsByExprFrame: Map<Int, AtomicOperationSite>,
)

/**
 * Pass 4: `kotlinx.atomicfu` (`atomic<T>`, `atomicArrayOfNulls<T>`, `.value`, `.lazySet`,
 * lvalue-safe `.compareAndSet`, `.getAndSet`, `.incrementAndGet`, `.loop` / `.update`) and
 * `CommonThreadLocal<T>` / `ThreadLocal<T>` lowering.
 */
internal object AtomicAndThreadLocalPass {
    private data class AtomicScopeIndex(
        val symbols: SameFileSymbolTable,
        val topLevelAtomics: Map<String, AtomicFieldInfo>,
        val classAtomicsByClass: Map<Int, Map<String, AtomicFieldInfo>>,
        val nonAtomicPropsByClass: Map<Int, Set<String>>,
        val localAtomicsByPropFrame: Map<Int, AtomicFieldInfo>,
        val scopedParamsByOwner: Map<Int, Map<String, AtomicFieldInfo>>,
        val allNonAtomicPropNames: Set<String>,
        val classesWithAtomicName: Map<String, List<AtomicFieldInfo>>,
    )

    private data class AtomicLexicalScope(
        val currentClassFrameIndex: Int?,
        val classStack: List<Int>,
        val localShadowedNames: Set<String>,
        val localAtomics: Map<String, AtomicFieldInfo>,
        val localTypeSimpleNames: Map<String, String>,
        val thisValueShadowed: Boolean,
    )

    fun run(
        stream: PacketStream,
        config: CppTargetConfig,
        symbols: SameFileSymbolTable,
    ): AtomicAndThreadLocalPlan {
        val atomicByProp = LinkedHashMap<Int, AtomicFieldInfo>()
        val atomicByName = LinkedHashMap<String, AtomicFieldInfo>()
        val topLevelAtomics = LinkedHashMap<String, AtomicFieldInfo>()
        val classAtomicsByClass = LinkedHashMap<Int, MutableMap<String, AtomicFieldInfo>>()
        val localAtomicsByPropFrame = LinkedHashMap<Int, AtomicFieldInfo>()
        val threadLocalByProp = LinkedHashMap<Int, ThreadLocalSlotInfo>()
        val threadLocalByName = LinkedHashMap<String, ThreadLocalSlotInfo>()
        val atomicOps = LinkedHashMap<Int, AtomicOperationSite>()

        for (propSym in symbols.propertiesByFrame.values) {
            val propFrame = stream.frames[propSym.frameIndex]
            val initIdx = propSym.initializerFrameIndex
            if (initIdx != null) {
                val initFrame = stream.frames[initIdx]
                val atomicInfo = parseAtomicInitializer(stream, symbols, propSym, propFrame, initFrame)
                if (atomicInfo != null) {
                    atomicByProp[propSym.frameIndex] = atomicInfo
                    if (propSym.kotlinName !in atomicByName) {
                        atomicByName[propSym.kotlinName] = atomicInfo
                    }
                    val encCls = propSym.enclosingClassFrameIndex
                    if (encCls != null) {
                        classAtomicsByClass.getOrPut(encCls) { LinkedHashMap() }[propSym.kotlinName] = atomicInfo
                    } else {
                        topLevelAtomics[propSym.kotlinName] = atomicInfo
                    }
                }
                parseThreadLocalInitializer(stream, propSym, propFrame, initFrame)?.let { tl ->
                    threadLocalByProp[propSym.frameIndex] = tl
                    threadLocalByName[propSym.kotlinName] = tl
                }
            } else if (propSym.returnTypeFrameIndex != null) {
                val retFrame = stream.frames[propSym.returnTypeFrameIndex]
                val retBase = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retFrame)
                if (retBase in ATOMIC_PARAM_TYPE_NAMES) {
                    val atomicInfo = parseExplicitAtomicTypeProperty(stream, propSym, propFrame, retFrame, retBase!!)
                    atomicByProp[propSym.frameIndex] = atomicInfo
                    if (propSym.kotlinName !in atomicByName) {
                        atomicByName[propSym.kotlinName] = atomicInfo
                    }
                    val encCls = propSym.enclosingClassFrameIndex
                    if (encCls != null) {
                        classAtomicsByClass.getOrPut(encCls) { LinkedHashMap() }[propSym.kotlinName] = atomicInfo
                    } else {
                        topLevelAtomics[propSym.kotlinName] = atomicInfo
                    }
                } else if (retBase == "CommonThreadLocal" || retBase == "ThreadLocal") {
                    propFrame.flags = propFrame.flags or PacketFlags.THREAD_LOCAL_SLOT
                    val typeArg = extractFirstTypeArgFrame(stream, retFrame)
                    val tl =
                        ThreadLocalSlotInfo(
                            propFrameIndex = propSym.frameIndex,
                            kotlinName = propSym.kotlinName,
                            cppName = propSym.cppName,
                            explicitElementTypeFrameIndex = typeArg?.frameIndex,
                            defaultLambdaFrameIndex = null,
                        )
                    threadLocalByProp[propSym.frameIndex] = tl
                    threadLocalByName[propSym.kotlinName] = tl
                }
            }
        }

        // Also detect local `val x = atomic(...)` declarations inside functions.
        for (frame in stream.frames) {
            if (frame.nodeTypeId == KtNodeTypes.PROPERTY_ID && frame.frameIndex !in symbols.propertiesByFrame) {
                val ident = FrameAstHelpers.directIdentPacket(stream, frame) ?: continue
                val initFrame = FrameAstHelpers.extractInitializerAfterEquals(stream, frame) ?: continue
                val kotlinName = ident.unescapedName
                val provisionalCppName = ident.targetCppName ?: CppNamingPolicy.toSnakeCase(kotlinName)
                val dummySym =
                    KtPropertySymbol(
                        kotlinName = kotlinName,
                        cppName = provisionalCppName,
                        frameIndex = frame.frameIndex,
                        enclosingClassFrameIndex = null,
                        isVar = false,
                        isConst = false,
                        receiverTypeFrameIndex = null,
                        returnTypeFrameIndex = null,
                        initializerFrameIndex = initFrame.frameIndex,
                        getterFrameIndex = null,
                        setterFrameIndex = null,
                        annotations = emptyList(),
                        flags = frame.flags,
                    )
                val parsed = parseAtomicInitializer(stream, symbols, dummySym, frame, initFrame)
                if (parsed != null) {
                    val info =
                        if (kotlinName.startsWith('_') && ident.targetCppName == null) {
                            val base = CppNamingPolicy.toPrivateFieldName(kotlinName).removeSuffix("_")
                            val atomicCppName = "kxs_atomic_${base}_"
                            ident.targetCppName = atomicCppName
                            parsed.copy(cppName = atomicCppName)
                        } else {
                            parsed
                        }
                    atomicByProp[frame.frameIndex] = info
                    localAtomicsByPropFrame[frame.frameIndex] = info
                    if (info.kotlinName !in atomicByName) {
                        atomicByName[info.kotlinName] = info
                    }
                }
            }
        }

        val scopedAtomicsByOwner = LinkedHashMap<Int, MutableMap<String, AtomicFieldInfo>>()
        collectScopedAtomicValueParams(stream, stream.rootFrame, atomicByName, classAtomicsByClass, scopedAtomicsByOwner)

        val scopeIndex = buildAtomicScopeIndex(
            symbols = symbols,
            topLevelAtomics = topLevelAtomics,
            classAtomicsByClass = classAtomicsByClass,
            localAtomicsByPropFrame = localAtomicsByPropFrame,
            scopedAtomicsByOwner = scopedAtomicsByOwner,
            atomicByProp = atomicByProp,
        )

        if (atomicByProp.isNotEmpty() || scopedAtomicsByOwner.isNotEmpty()) {
            val rootScope =
                AtomicLexicalScope(
                    currentClassFrameIndex = null,
                    classStack = emptyList(),
                    localShadowedNames = emptySet(),
                    localAtomics = emptyMap(),
                    localTypeSimpleNames = emptyMap(),
                    thisValueShadowed = false,
                )
            classifyAtomicOperationsRecursive(
                stream = stream,
                frame = stream.rootFrame,
                config = config,
                env = scopeIndex,
                scope = rootScope,
                out = atomicOps,
            )
        }

        return AtomicAndThreadLocalPlan(
            atomicFieldsByPropFrame = atomicByProp,
            atomicFieldsByKotlinName = atomicByName,
            threadLocalsByPropFrame = threadLocalByProp,
            threadLocalsByKotlinName = threadLocalByName,
            atomicOpsByExprFrame = atomicOps,
        )
    }

    private fun buildAtomicScopeIndex(
        symbols: SameFileSymbolTable,
        topLevelAtomics: Map<String, AtomicFieldInfo>,
        classAtomicsByClass: Map<Int, Map<String, AtomicFieldInfo>>,
        localAtomicsByPropFrame: Map<Int, AtomicFieldInfo>,
        scopedAtomicsByOwner: Map<Int, Map<String, AtomicFieldInfo>>,
        atomicByProp: Map<Int, AtomicFieldInfo>,
    ): AtomicScopeIndex {
        val nonAtomicPropsByClass = LinkedHashMap<Int, MutableSet<String>>()
        val allNonAtomicPropNames = LinkedHashSet<String>()
        for (propSym in symbols.propertiesByFrame.values) {
            val encCls = propSym.enclosingClassFrameIndex ?: continue
            if (propSym.frameIndex !in atomicByProp) {
                nonAtomicPropsByClass.getOrPut(encCls) { LinkedHashSet() }.add(propSym.kotlinName)
                allNonAtomicPropNames.add(propSym.kotlinName)
            }
        }
        for (cls in symbols.classesByFrame.values) {
            val clsAtomics = classAtomicsByClass[cls.frameIndex].orEmpty()
            for (vp in cls.primaryConstructorParams) {
                if ((vp.isVal || vp.isVar) && vp.name !in clsAtomics) {
                    nonAtomicPropsByClass.getOrPut(cls.frameIndex) { LinkedHashSet() }.add(vp.name)
                    allNonAtomicPropNames.add(vp.name)
                }
            }
        }
        val classesWithAtomicName = LinkedHashMap<String, MutableList<AtomicFieldInfo>>()
        for (clsMap in classAtomicsByClass.values) {
            for ((name, info) in clsMap) {
                classesWithAtomicName.getOrPut(name) { ArrayList() }.add(info)
            }
        }
        return AtomicScopeIndex(
            symbols = symbols,
            topLevelAtomics = topLevelAtomics,
            classAtomicsByClass = classAtomicsByClass,
            nonAtomicPropsByClass = nonAtomicPropsByClass,
            localAtomicsByPropFrame = localAtomicsByPropFrame,
            scopedParamsByOwner = scopedAtomicsByOwner,
            allNonAtomicPropNames = allNonAtomicPropNames,
            classesWithAtomicName = classesWithAtomicName,
        )
    }

    private fun parseExplicitAtomicTypeProperty(
        stream: PacketStream,
        propSym: KtPropertySymbol,
        propFrame: PacketFrame,
        retFrame: PacketFrame,
        retBase: String,
    ): AtomicFieldInfo {
        propFrame.flags = propFrame.flags or PacketFlags.ATOMICFU_FIELD
        val (explicitElemType, inferredType, isRefElem) = resolveAtomicParamElemType(stream, retFrame, retBase)
        return AtomicFieldInfo(
            propFrameIndex = propSym.frameIndex,
            kotlinName = propSym.kotlinName,
            cppName = propSym.cppName,
            explicitElementTypeFrameIndex = explicitElemType?.frameIndex,
            inferredElementCppType = inferredType,
            initExprFrameIndex = null,
            isArray = false,
            arraySizeExprFrameIndex = null,
            isReferenceElement = isRefElem,
            enclosingClassFrameIndex = propSym.enclosingClassFrameIndex,
        )
    }

    private fun parseAtomicInitializer(
        stream: PacketStream,
        symbols: SameFileSymbolTable,
        propSym: KtPropertySymbol,
        propFrame: PacketFrame,
        initFrame: PacketFrame,
    ): AtomicFieldInfo? {
        val callFrame = unwrapQualifiedCall(stream, initFrame) ?: return null
        val calleeRef = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        val callee = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()
        if (callee != "atomic" &&
            callee != "atomicArrayOfNulls" &&
            callee != "AtomicReference" &&
            callee != "AtomicInt" &&
            callee != "AtomicLong" &&
            callee != "AtomicBoolean" &&
            callee != "AtomicNativePtr"
        ) {
            return null
        }

        val explicitElemType = extractFirstTypeArgFrame(stream, callFrame)
        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
        val firstArgExpr =
            argList
                ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.VALUE_ARGUMENT_ID) }
                ?.let { extractArgExpr(stream, it) }

        if (callee == "atomicArrayOfNulls") {
            propFrame.flags = propFrame.flags or PacketFlags.ATOMICFU_ARRAY
            return AtomicFieldInfo(
                propFrameIndex = propSym.frameIndex,
                kotlinName = propSym.kotlinName,
                cppName = propSym.cppName,
                explicitElementTypeFrameIndex = explicitElemType?.frameIndex,
                inferredElementCppType = null,
                initExprFrameIndex = null,
                isArray = true,
                arraySizeExprFrameIndex = firstArgExpr?.frameIndex,
                isReferenceElement = true,
                enclosingClassFrameIndex = propSym.enclosingClassFrameIndex,
            )
        }

        propFrame.flags = propFrame.flags or PacketFlags.ATOMICFU_FIELD
        val typedScalar: Pair<String, Boolean>? =
            when (callee) {
                "AtomicInt" -> "std::int32_t" to false
                "AtomicLong" -> "std::int64_t" to false
                "AtomicBoolean" -> "bool" to false
                "AtomicNativePtr" -> "void*" to false
                else -> null
            }
        if (typedScalar != null) {
            return AtomicFieldInfo(
                propFrameIndex = propSym.frameIndex,
                kotlinName = propSym.kotlinName,
                cppName = propSym.cppName,
                explicitElementTypeFrameIndex = null,
                inferredElementCppType = typedScalar.first,
                initExprFrameIndex = firstArgExpr?.frameIndex,
                isArray = false,
                arraySizeExprFrameIndex = null,
                isReferenceElement = typedScalar.second,
                enclosingClassFrameIndex = propSym.enclosingClassFrameIndex,
            )
        }
        val retTypeArg =
            if (explicitElemType == null && propSym.returnTypeFrameIndex != null) {
                val retFrame = stream.frames[propSym.returnTypeFrameIndex]
                val retBase = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retFrame)
                if (retBase == "AtomicRef" || retBase == "AtomicReference") {
                    extractFirstTypeArgFrame(stream, retFrame)
                } else {
                    null
                }
            } else {
                null
            }
        val resolvedExplicitElem = explicitElemType ?: retTypeArg
        val inferredTypeFrameIdx =
            if (resolvedExplicitElem != null) {
                resolvedExplicitElem.frameIndex
            } else {
                inferAtomicElementTypeFrameIndex(stream, symbols, propSym, firstArgExpr)
            }
        val (inferredType, isRef) = inferAtomicElementType(stream, symbols, propSym, resolvedExplicitElem, firstArgExpr)
        return AtomicFieldInfo(
            propFrameIndex = propSym.frameIndex,
            kotlinName = propSym.kotlinName,
            cppName = propSym.cppName,
            explicitElementTypeFrameIndex = inferredTypeFrameIdx,
            inferredElementCppType = inferredType,
            initExprFrameIndex = firstArgExpr?.frameIndex,
            isArray = false,
            arraySizeExprFrameIndex = null,
            isReferenceElement = isRef,
            enclosingClassFrameIndex = propSym.enclosingClassFrameIndex,
        )
    }

    private fun inferAtomicElementTypeFrameIndex(
        stream: PacketStream,
        symbols: SameFileSymbolTable,
        propSym: KtPropertySymbol,
        initExprFrame: PacketFrame?,
    ): Int? {
        if (initExprFrame == null) return null
        if (initExprFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val refName = FrameAstHelpers.directIdentPacket(stream, initExprFrame)?.unescapedName ?: return null
            val encCls = propSym.enclosingClassFrameIndex?.let { symbols.classesByFrame[it] }
            val ctorParam = encCls?.primaryConstructorParams?.firstOrNull { it.name == refName }
            if (ctorParam?.typeFrameIndex != null) return ctorParam.typeFrameIndex
            val sameScopeProp =
                symbols.propertiesByFrame.values.firstOrNull { sym ->
                    val sameClassOrTop =
                        sym.enclosingClassFrameIndex == null ||
                            sym.enclosingClassFrameIndex == propSym.enclosingClassFrameIndex
                    sym.frameIndex != propSym.frameIndex &&
                        sym.kotlinName == refName &&
                        sameClassOrTop
                }
            if (sameScopeProp?.returnTypeFrameIndex != null) return sameScopeProp.returnTypeFrameIndex
            var curIdx = stream.frames[propSym.frameIndex].parentFrameIndex
            while (curIdx >= 0) {
                val cur = stream.frames[curIdx]
                if (cur.nodeTypeId == KtNodeTypes.FUN_ID ||
                    cur.nodeTypeId == KtNodeTypes.PRIMARY_CONSTRUCTOR_ID ||
                    cur.nodeTypeId == KtNodeTypes.SECONDARY_CONSTRUCTOR_ID
                ) {
                    val pList = FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
                    if (pList != null) {
                        for (vp in FrameAstHelpers.childFramesOfType(stream, pList, KtNodeTypes.VALUE_PARAMETER_ID)) {
                            if (FrameAstHelpers.directIdentPacket(stream, vp)?.unescapedName == refName) {
                                val tRef = FrameAstHelpers.firstChildFrame(stream, vp, KtNodeTypes.TYPE_REFERENCE_ID)
                                if (tRef != null) return tRef.frameIndex
                            }
                        }
                    }
                }
                if (curIdx == 0) break
                curIdx = cur.parentFrameIndex
            }
        } else if (initExprFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val calleeFrame = FrameAstHelpers.childFrames(stream, initExprFrame).firstOrNull()
            val calleeName = calleeFrame?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
            if (calleeName != null) {
                val ta =
                    symbols.typeAliasesByFrame.values.firstOrNull {
                        it.kotlinName == calleeName && it.typeParams.isEmpty()
                    }
                if (ta?.underlyingTypeFrameIndex != null) {
                    return ta.underlyingTypeFrameIndex
                }
                val fn =
                    symbols.functionsByName[calleeName]?.firstOrNull {
                        it.returnTypeFrameIndex != null && it.typeParams.isEmpty()
                    }
                if (fn?.returnTypeFrameIndex != null) {
                    return fn.returnTypeFrameIndex
                }
            }
        }
        return null
    }

    private fun simpleScalarTypeName(typeSimple: String?): Pair<String?, Boolean> =
        when (typeSimple) {
            "Boolean" -> "bool" to false
            "Byte" -> "std::int8_t" to false
            "Short" -> "std::int16_t" to false
            "Int" -> "std::int32_t" to false
            "Long" -> "std::int64_t" to false
            "UByte" -> "std::uint8_t" to false
            "UShort" -> "std::uint16_t" to false
            "UInt" -> "std::uint32_t" to false
            "ULong" -> "std::uint64_t" to false
            "Float" -> "float" to false
            "Double" -> "double" to false
            "Char" -> "char16_t" to false
            "String" -> "std::string" to true
            else -> null to true
        }

    private fun extractCallTypeArgCppNames(
        stream: PacketStream,
        symbols: SameFileSymbolTable,
        callFrame: PacketFrame,
    ): List<String> {
        val taList =
            FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
                ?: return emptyList()
        return FrameAstHelpers.childFramesOfType(stream, taList, KtNodeTypes.TYPE_PROJECTION_ID).map { proj ->
            val ref = FrameAstHelpers.firstChildFrame(stream, proj, KtNodeTypes.TYPE_REFERENCE_ID)
            if (ref == null) {
                "void*"
            } else {
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, ref)
                val (scalar, _) = simpleScalarTypeName(simple)
                when {
                    scalar != null -> scalar
                    simple == null -> "std::any"
                    simple == "Any" -> "std::any"
                    simple in symbols.classesByName -> {
                        val c = symbols.classesByName.getValue(simple)
                        if (c.kotlinName in symbols.sharedHierarchyTypeNames ||
                            CppTypeTranslator.isSharedHierarchyType(c.kotlinName)
                        ) {
                            "std::shared_ptr<${c.cppName}>"
                        } else {
                            c.cppName
                        }
                    }
                    else -> simple
                }
            }
        }
    }

    private fun inferAtomicElementType(
        stream: PacketStream,
        symbols: SameFileSymbolTable,
        propSym: KtPropertySymbol,
        explicitTypeFrame: PacketFrame?,
        initExprFrame: PacketFrame?,
    ): Pair<String?, Boolean> {
        if (explicitTypeFrame != null) {
            val base = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, explicitTypeFrame)
            val isRef = base == null || base !in setOf("Int", "Long", "Boolean", "UInt", "ULong", "Byte", "Short")
            return null to isRef
        }
        return inferExprScalarType(stream, symbols, propSym, initExprFrame)
    }

    private fun inferExprScalarType(
        stream: PacketStream,
        symbols: SameFileSymbolTable,
        propSym: KtPropertySymbol,
        exprFrame: PacketFrame?,
        visitedFnFrames: Set<Int> = emptySet(),
    ): Pair<String?, Boolean> {
        if (exprFrame == null) return "std::any" to true
        when (exprFrame.nodeTypeId) {
            KtNodeTypes.PARENTHESIZED_ID -> {
                val inner = FrameAstHelpers.childFrames(stream, exprFrame).firstOrNull()
                return inferExprScalarType(stream, symbols, propSym, inner, visitedFnFrames)
            }
            KtNodeTypes.PREFIX_EXPRESSION_ID -> {
                val inner = FrameAstHelpers.childFrames(stream, exprFrame).lastOrNull()
                val opPkt = stream.packets.getOrNull(exprFrame.firstPacketIndex)
                if (opPkt is SyntaxPacket.OperatorPacket && opPkt.operatorId == KtTokens.EXCL_ID) {
                    return "bool" to false
                }
                return inferExprScalarType(stream, symbols, propSym, inner, visitedFnFrames)
            }
            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val kids = FrameAstHelpers.childFrames(stream, exprFrame)
                if (kids.size >= 3) {
                    val opFrame = kids[1]
                    val opId = FrameAstHelpers.operationTokenId(stream, opFrame)
                    val opText = FrameAstHelpers.directIdentPacket(stream, opFrame)?.unescapedName
                    val comparisonOps =
                        setOf(
                            KtTokens.EQEQ_ID,
                            KtTokens.EXCLEQ_ID,
                            KtTokens.EQEQEQ_ID,
                            KtTokens.EXCLEQEQEQ_ID,
                            KtTokens.LT_ID,
                            KtTokens.LTEQ_ID,
                            KtTokens.GT_ID,
                            KtTokens.GTEQ_ID,
                            KtTokens.ANDAND_ID,
                            KtTokens.OROR_ID,
                        )
                    if (opId in comparisonOps) {
                        return "bool" to false
                    }
                    val arithmeticOps =
                        setOf(
                            KtTokens.PLUS_ID,
                            KtTokens.MINUS_ID,
                            KtTokens.MUL_ID,
                            KtTokens.DIV_ID,
                            KtTokens.PERC_ID,
                        )
                    if (opId in arithmeticOps || opText in setOf("shl", "shr", "ushr", "and", "or", "xor")) {
                        val (lhsT, _) = inferExprScalarType(stream, symbols, propSym, kids[0], visitedFnFrames)
                        val (rhsT, _) = inferExprScalarType(stream, symbols, propSym, kids[2], visitedFnFrames)
                        val is64 =
                            lhsT == "std::int64_t" || lhsT == "std::uint64_t" ||
                                (opText !in setOf("shl", "shr", "ushr") && (rhsT == "std::int64_t" || rhsT == "std::uint64_t"))
                        val isUnsigned = lhsT?.startsWith("std::uint") == true
                        val cpp =
                            when {
                                is64 && isUnsigned -> "std::uint64_t"
                                is64 -> "std::int64_t"
                                isUnsigned -> "std::uint32_t"
                                else -> "std::int32_t"
                            }
                        return cpp to false
                    }
                }
            }
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                val parts = FrameAstHelpers.childFrames(stream, exprFrame)
                if (parts.size == 2) {
                    val recv = parts[0]
                    val rhs = parts[1]
                    if (rhs.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                        val callRef = FrameAstHelpers.firstChildFrame(stream, rhs, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                        val callName = callRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
                        when (callName) {
                            "toBoolean" -> return "bool" to false
                            "toByte" -> return "std::int8_t" to false
                            "toShort" -> return "std::int16_t" to false
                            "toInt" -> return "std::int32_t" to false
                            "toLong" -> return "std::int64_t" to false
                            "toUByte" -> return "std::uint8_t" to false
                            "toUShort" -> return "std::uint16_t" to false
                            "toUInt" -> return "std::uint32_t" to false
                            "toULong" -> return "std::uint64_t" to false
                        }
                    } else if (rhs.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        val memberName = FrameAstHelpers.directIdentPacket(stream, rhs)?.unescapedName
                        val recvName =
                            if (recv.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                                FrameAstHelpers.directIdentPacket(stream, recv)?.unescapedName
                            } else {
                                null
                            }
                        when (memberName) {
                            "size", "length", "count" -> return "std::int32_t" to false
                            "MAX_VALUE", "MIN_VALUE", "SIZE_BYTES", "SIZE_BITS" -> {
                                val (scalar, isRef) = simpleScalarTypeName(recvName)
                                if (scalar != null && !isRef) return scalar to false
                            }
                        }
                    }
                }
            }
            KtNodeTypes.THIS_EXPRESSION_ID -> {
                val encCls = propSym.enclosingClassFrameIndex?.let { symbols.classesByFrame[it] }
                if (encCls != null &&
                    (
                        encCls.kotlinName in symbols.sharedHierarchyTypeNames ||
                            CppTypeTranslator.isSharedHierarchyType(encCls.kotlinName)
                    )
                ) {
                    val tpSuffix =
                        if (encCls.typeParams.isNotEmpty()) {
                            "<${encCls.typeParams.joinToString(", ") { it.name }}>"
                        } else {
                            ""
                        }
                    return "std::shared_ptr<${encCls.cppName}$tpSuffix>" to true
                }
            }
            KtNodeTypes.CALL_EXPRESSION_ID -> {
                val calleeFrame = FrameAstHelpers.childFrames(stream, exprFrame).firstOrNull()
                val calleeName = calleeFrame?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
                if (calleeName != null) {
                    val ta = symbols.typeAliasesByFrame.values.firstOrNull { it.kotlinName == calleeName }
                    if (ta?.underlyingTypeFrameIndex != null) {
                        val underlyingFrame = stream.frames[ta.underlyingTypeFrameIndex]
                        val underlyingSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, underlyingFrame)
                        if (underlyingSimple != null &&
                            (
                                underlyingSimple in symbols.sharedHierarchyTypeNames ||
                                    CppTypeTranslator.isSharedHierarchyType(underlyingSimple)
                            )
                        ) {
                            val underlyingCpp =
                                symbols.classesByName[underlyingSimple]?.cppName
                                    ?: CppNamingPolicy.toTypeName(underlyingSimple)
                            val callArgs = extractCallTypeArgCppNames(stream, symbols, exprFrame)
                            val tpSuffix =
                                if (callArgs.isNotEmpty()) {
                                    "<${callArgs.joinToString(", ")}>"
                                } else if (ta.typeParams.isNotEmpty()) {
                                    "<${ta.typeParams.joinToString(", ") { it.name }}>"
                                } else {
                                    ""
                                }
                            return "std::shared_ptr<$underlyingCpp$tpSuffix>" to true
                        }
                    }
                    val cls = symbols.classesByName[calleeName]
                    if (cls != null &&
                        (
                            cls.kotlinName in symbols.sharedHierarchyTypeNames ||
                                CppTypeTranslator.isSharedHierarchyType(cls.kotlinName)
                        )
                    ) {
                        val callArgs = extractCallTypeArgCppNames(stream, symbols, exprFrame)
                        val tpSuffix =
                            when {
                                cls.typeParams.isEmpty() -> ""
                                callArgs.size == cls.typeParams.size -> "<${callArgs.joinToString(", ")}>"
                                else -> "<${cls.typeParams.joinToString(", ") { it.name }}>"
                            }
                        return "std::shared_ptr<${cls.cppName}$tpSuffix>" to true
                    }
                    val fn = symbols.functionsByName[calleeName]?.firstOrNull()
                    if (fn != null) {
                        if (fn.returnTypeFrameIndex != null) {
                            val retSimple =
                                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[fn.returnTypeFrameIndex])
                            val (scalar, isRef) = simpleScalarTypeName(retSimple)
                            if (scalar != null && !isRef) return scalar to false
                        } else if (fn.isExpressionBody && fn.bodyFrameIndex != null && fn.frameIndex !in visitedFnFrames) {
                            return inferExprScalarType(
                                stream = stream,
                                symbols = symbols,
                                propSym = propSym,
                                exprFrame = stream.frames[fn.bodyFrameIndex],
                                visitedFnFrames = visitedFnFrames + fn.frameIndex,
                            )
                        }
                    }
                }
            }
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
                val refName = FrameAstHelpers.directIdentPacket(stream, exprFrame)?.unescapedName
                if (refName != null) {
                    val encCls = propSym.enclosingClassFrameIndex?.let { symbols.classesByFrame[it] }
                    val ctorParam = encCls?.primaryConstructorParams?.firstOrNull { it.name == refName }
                    val paramTypeBase =
                        ctorParam?.typeFrameIndex?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
                    val (paramScalar, paramIsRef) = simpleScalarTypeName(paramTypeBase)
                    if (paramScalar != null && !paramIsRef) return paramScalar to false
                    val sameScopeProp =
                        symbols.propertiesByName[refName]
                            ?: symbols.propertiesByFrame.values.firstOrNull { sym ->
                                sym.frameIndex != propSym.frameIndex &&
                                    sym.kotlinName == refName &&
                                    (sym.enclosingClassFrameIndex == null || sym.enclosingClassFrameIndex == propSym.enclosingClassFrameIndex)
                            }
                    if (sameScopeProp != null && sameScopeProp.frameIndex != propSym.frameIndex) {
                        val propTypeBase =
                            sameScopeProp.returnTypeFrameIndex?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
                        val (propScalar, propIsRef) = simpleScalarTypeName(propTypeBase)
                        if (propScalar != null && !propIsRef) return propScalar to false
                        if (sameScopeProp.initializerFrameIndex != null && sameScopeProp.frameIndex !in visitedFnFrames) {
                            return inferExprScalarType(
                                stream = stream,
                                symbols = symbols,
                                propSym = sameScopeProp,
                                exprFrame = stream.frames[sameScopeProp.initializerFrameIndex],
                                visitedFnFrames = visitedFnFrames + sameScopeProp.frameIndex,
                            )
                        }
                    }
                }
            }
        }
        val firstPkt = stream.packets.getOrNull(exprFrame.firstPacketIndex)
        if (firstPkt is SyntaxPacket.LiteralPacket) {
            return when (firstPkt.literalKind) {
                LiteralPacketKind.BOOLEAN -> "bool" to false
                LiteralPacketKind.INT -> {
                    val cpp =
                        if (firstPkt.numericWidthBits == 64) {
                            if (firstPkt.isUnsigned) "std::uint64_t" else "std::int64_t"
                        } else {
                            if (firstPkt.isUnsigned) "std::uint32_t" else "std::int32_t"
                        }
                    cpp to false
                }
                LiteralPacketKind.NULL -> "std::any" to true
                else -> "std::any" to true
            }
        }
        return null to true
    }

    private fun parseThreadLocalInitializer(
        stream: PacketStream,
        propSym: KtPropertySymbol,
        propFrame: PacketFrame,
        initFrame: PacketFrame,
    ): ThreadLocalSlotInfo? {
        if (initFrame.nodeTypeId != KtNodeTypes.CALL_EXPRESSION_ID) return null
        val calleeRef = FrameAstHelpers.firstChildFrame(stream, initFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        val callee = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()
        if (callee != "CommonThreadLocal" && callee != "ThreadLocal" && callee != "commonThreadLocal") return null

        propFrame.flags = propFrame.flags or PacketFlags.THREAD_LOCAL_SLOT
        val explicitElem =
            extractFirstTypeArgFrame(stream, initFrame)
                ?: propSym.returnTypeFrameIndex?.let { extractFirstTypeArgFrame(stream, stream.frames[it]) }
        val trailingLambda =
            FrameAstHelpers
                .firstChildFrame(stream, initFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID)
                ?.let { FrameAstHelpers.unwrapLambdaExpression(stream, it) }
        return ThreadLocalSlotInfo(
            propFrameIndex = propSym.frameIndex,
            kotlinName = propSym.kotlinName,
            cppName = propSym.cppName,
            explicitElementTypeFrameIndex = explicitElem?.frameIndex,
            defaultLambdaFrameIndex = trailingLambda?.frameIndex,
        )
    }

    private fun classifyAtomicOperationsRecursive(
        stream: PacketStream,
        frame: PacketFrame,
        config: CppTargetConfig,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        out: MutableMap<Int, AtomicOperationSite>,
    ) {
        if (frame.nodeTypeId == KtNodeTypes.VALUE_ARGUMENT_NAME_ID ||
            frame.nodeTypeId == KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION_ID
        ) {
            return
        }
        val activeScope = enterFrameScope(stream, frame, env, scope)

        // 1. Check `atomicField.value = rhs`, implicit `value = rhs`, or deferred `atomicField = atomic(rhs)`.
        if (frame.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val children = FrameAstHelpers.childFrames(stream, frame)
            if (children.size >= 3 && FrameAstHelpers.operationTokenId(stream, children[1]) == KtTokens.EQ_ID) {
                val lhs = children[0]
                val rhs = children[2]
                if (tryRecordAtomicStore(stream, frame, lhs, rhs, config, env, activeScope, out)) {
                    return
                }
            }
        }

        // 2. Check `atomicField.op(...)` or `atomicField.value`
        if (frame.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID ||
            frame.nodeTypeId == KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID
        ) {
            val children = FrameAstHelpers.childFrames(stream, frame)
            if (children.size == 2) {
                val recv = children[0]
                val sel = children[1]
                val resolvedTarget =
                    if (frame.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
                        resolveAtomicTarget(stream, recv, env, activeScope)
                    } else {
                        null
                    }
                if (resolvedTarget != null) {
                    if (sel.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        val member = FrameAstHelpers.directIdentPacket(stream, sel)?.unescapedName
                        if (member == "value") {
                            out[frame.frameIndex] =
                                AtomicOperationSite(
                                    exprFrameIndex = frame.frameIndex,
                                    targetFieldInfo = resolvedTarget.fieldInfo,
                                    receiverPrefixExprFrameIndex = resolvedTarget.prefixExprFrameIndex,
                                    arrayIndexExprFrameIndex = resolvedTarget.arrayIndexExprFrameIndex,
                                    kind = AtomicOpKind.LOAD_SEQ_CST,
                                    argExprFrameIndices = emptyList(),
                                    loopLambdaFrameIndex = null,
                                    loopCurParamName = null,
                                    useKonanCasBarrier = false,
                                )
                            return
                        }
                    } else if (sel.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                        if (recordAtomicCallSite(
                                stream = stream,
                                siteFrame = frame,
                                callFrame = sel,
                                resolvedTarget = resolvedTarget,
                                config = config,
                                env = env,
                                scope = activeScope,
                                out = out,
                            )
                        ) {
                            return
                        }
                    }
                }
                // Selector on a non-atomic receiver must never match implicit atomic `this` (`other.value` / `other.loop`).
                classifyAtomicOperationsRecursive(stream, recv, config, env, activeScope, out)
                if (sel.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                    for (selChild in FrameAstHelpers.childFrames(stream, sel)) {
                        if (selChild.nodeTypeId != KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                            classifyAtomicOperationsRecursive(stream, selChild, config, env, activeScope, out)
                        }
                    }
                }
                return
            }
        }

        // 3. Check implicit-`this` atomic ops (`loop { ... }`, `compareAndSet(...)`, `value`) inside atomic extensions
        val selfAtomic = activeScope.localAtomics["this"]
        if (selfAtomic != null) {
            if (!activeScope.thisValueShadowed && frame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                val ident = FrameAstHelpers.directIdentPacket(stream, frame)?.unescapedName
                if (ident == "value") {
                    out[frame.frameIndex] =
                        AtomicOperationSite(
                            exprFrameIndex = frame.frameIndex,
                            targetFieldInfo = selfAtomic,
                            receiverPrefixExprFrameIndex = null,
                            arrayIndexExprFrameIndex = null,
                            kind = AtomicOpKind.LOAD_SEQ_CST,
                            argExprFrameIndices = emptyList(),
                            loopLambdaFrameIndex = null,
                            loopCurParamName = null,
                            useKonanCasBarrier = false,
                        )
                    return
                }
            } else if (frame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                if (recordAtomicCallSite(
                        stream = stream,
                        siteFrame = frame,
                        callFrame = frame,
                        resolvedTarget = ResolvedAtomicReceiver(selfAtomic, null, null),
                        config = config,
                        env = env,
                        scope = activeScope,
                        out = out,
                    )
                ) {
                    return
                }
            }
        }

        var childScope = activeScope
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            if (child.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                val propName = FrameAstHelpers.directIdentPacket(stream, child)?.unescapedName
                if (propName == "value") {
                    FrameAstHelpers.extractInitializerAfterEquals(stream, child)?.let { initChild ->
                        classifyAtomicOperationsRecursive(stream, initChild, config, env, childScope, out)
                    }
                    childScope = childScope.copy(thisValueShadowed = true)
                    continue
                }
                classifyAtomicOperationsRecursive(stream, child, config, env, childScope, out)
                if (propName != null && child.frameIndex !in env.symbols.propertiesByFrame) {
                    val localAtomic = env.localAtomicsByPropFrame[child.frameIndex]
                    if (localAtomic != null) {
                        childScope =
                            childScope.copy(
                                localAtomics = childScope.localAtomics + (propName to localAtomic),
                                localShadowedNames = childScope.localShadowedNames - propName,
                            )
                    } else {
                        val inferredType = inferLocalPropertyTypeName(stream, env, childScope, child)
                        val updatedTypes =
                            if (inferredType != null) {
                                childScope.localTypeSimpleNames + (propName to inferredType)
                            } else {
                                childScope.localTypeSimpleNames - propName
                            }
                        childScope =
                            childScope.copy(
                                localShadowedNames = childScope.localShadowedNames + propName,
                                localAtomics = childScope.localAtomics - propName,
                                localTypeSimpleNames = updatedTypes,
                            )
                    }
                }
                continue
            }
            classifyAtomicOperationsRecursive(stream, child, config, env, childScope, out)
        }
    }

    private fun tryRecordAtomicStore(
        stream: PacketStream,
        exprFrame: PacketFrame,
        lhs: PacketFrame,
        rhs: PacketFrame,
        config: CppTargetConfig,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        out: MutableMap<Int, AtomicOperationSite>,
    ): Boolean {
        val implicitThisTarget =
            if (!scope.thisValueShadowed &&
                lhs.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                FrameAstHelpers.directIdentPacket(stream, lhs)?.unescapedName == "value"
            ) {
                scope.localAtomics["this"]?.let { ResolvedAtomicReceiver(it, null, null) }
            } else {
                null
            }
        val dotTarget =
            if (lhs.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
                val dotChildren = FrameAstHelpers.childFrames(stream, lhs)
                if (dotChildren.size == 2 &&
                    dotChildren[1].nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                    FrameAstHelpers.directIdentPacket(stream, dotChildren[1])?.unescapedName == "value"
                ) {
                    resolveAtomicTarget(stream, dotChildren[0], env, scope)
                } else {
                    null
                }
            } else {
                null
            }
        val valueTarget = dotTarget ?: implicitThisTarget
        if (valueTarget != null) {
            out[exprFrame.frameIndex] =
                AtomicOperationSite(
                    exprFrameIndex = exprFrame.frameIndex,
                    targetFieldInfo = valueTarget.fieldInfo,
                    receiverPrefixExprFrameIndex = valueTarget.prefixExprFrameIndex,
                    arrayIndexExprFrameIndex = valueTarget.arrayIndexExprFrameIndex,
                    kind = AtomicOpKind.STORE_SEQ_CST,
                    argExprFrameIndices = listOf(rhs.frameIndex),
                    loopLambdaFrameIndex = null,
                    loopCurParamName = null,
                    useKonanCasBarrier = false,
                )
            classifyAtomicOperationsRecursive(stream, rhs, config, env, scope, out)
            return true
        }
        val initArgResult = extractAtomicFactoryInit(stream, rhs) ?: return false
        val directTarget = resolveAtomicTarget(stream, lhs, env, scope) ?: return false
        val argExpr = initArgResult.argFrame
        out[exprFrame.frameIndex] =
            AtomicOperationSite(
                exprFrameIndex = exprFrame.frameIndex,
                targetFieldInfo = directTarget.fieldInfo,
                receiverPrefixExprFrameIndex = directTarget.prefixExprFrameIndex,
                arrayIndexExprFrameIndex = directTarget.arrayIndexExprFrameIndex,
                kind = AtomicOpKind.STORE_SEQ_CST,
                argExprFrameIndices = listOfNotNull(argExpr?.frameIndex),
                loopLambdaFrameIndex = null,
                loopCurParamName = null,
                useKonanCasBarrier = false,
            )
        if (argExpr != null) {
            classifyAtomicOperationsRecursive(stream, argExpr, config, env, scope, out)
        }
        return true
    }

    private data class AtomicFactoryInitMatch(
        val argFrame: PacketFrame?,
    )

    private fun extractAtomicFactoryInit(
        stream: PacketStream,
        rhs: PacketFrame,
    ): AtomicFactoryInitMatch? {
        val callFrame = unwrapQualifiedCall(stream, rhs) ?: return null
        val calleeRef = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        val callee = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()
        if (callee !in ATOMIC_FACTORY_CALLEE_NAMES) return null
        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
        val firstArg =
            argList
                ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.VALUE_ARGUMENT_ID) }
                ?.let { extractArgExpr(stream, it) }
        return AtomicFactoryInitMatch(firstArg)
    }

    private fun enterFrameScope(
        stream: PacketStream,
        frame: PacketFrame,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
    ): AtomicLexicalScope {
        if ((frame.nodeTypeId == KtNodeTypes.CLASS_ID || frame.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID) &&
            frame.frameIndex in env.symbols.classesByFrame
        ) {
            val scopedParams = env.scopedParamsByOwner[frame.frameIndex].orEmpty()
            val shadowed = LinkedHashSet<String>()
            val types = LinkedHashMap<String, String>()
            val clsSym = env.symbols.classesByFrame[frame.frameIndex]
            if (clsSym != null) {
                for (vp in clsSym.primaryConstructorParams) {
                    if (!vp.isVal && !vp.isVar && vp.name !in scopedParams) {
                        shadowed.add(vp.name)
                    }
                    vp.typeFrameIndex?.let {
                        FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])?.let { t ->
                            types[vp.name] = t
                        }
                    }
                }
            }
            return AtomicLexicalScope(
                currentClassFrameIndex = frame.frameIndex,
                classStack = scope.classStack + frame.frameIndex,
                localShadowedNames = shadowed,
                localAtomics = scopedParams,
                localTypeSimpleNames = types,
                thisValueShadowed = "value" in scopedParams,
            )
        }

        val isCallableScope =
            frame.nodeTypeId == KtNodeTypes.FUN_ID ||
                frame.nodeTypeId == KtNodeTypes.PRIMARY_CONSTRUCTOR_ID ||
                frame.nodeTypeId == KtNodeTypes.SECONDARY_CONSTRUCTOR_ID ||
                frame.nodeTypeId == KtNodeTypes.PROPERTY_ACCESSOR_ID ||
                frame.nodeTypeId == KtNodeTypes.FUNCTION_LITERAL_ID
        if (!isCallableScope) return scope

        val scopedAdditions = env.scopedParamsByOwner[frame.frameIndex].orEmpty()
        val updatedAtomics = LinkedHashMap(scope.localAtomics)
        val updatedShadowed = LinkedHashSet(scope.localShadowedNames)
        val updatedTypes = LinkedHashMap(scope.localTypeSimpleNames)
        var thisValueShadowed = scope.thisValueShadowed

        if (frame.nodeTypeId == KtNodeTypes.FUN_ID) {
            val (recvTypeFrame, _) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, frame)
            if (recvTypeFrame != null && "this" !in scopedAdditions) {
                updatedAtomics.remove("this")
                thisValueShadowed = false
            }
        }
        for ((k, v) in scopedAdditions) {
            updatedAtomics[k] = v
            updatedShadowed.remove(k)
        }
        if ("this" in scopedAdditions || scopedAdditions.isNotEmpty()) {
            thisValueShadowed = "value" in scopedAdditions
        }

        val pList = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
        if (pList != null) {
            for (vp in FrameAstHelpers.childFramesOfType(stream, pList, KtNodeTypes.VALUE_PARAMETER_ID)) {
                val pName = FrameAstHelpers.directIdentPacket(stream, vp)?.unescapedName ?: continue
                if (pName == "value") {
                    thisValueShadowed = true
                }
                if (pName !in scopedAdditions) {
                    updatedShadowed.add(pName)
                    updatedAtomics.remove(pName)
                    val tRef = FrameAstHelpers.firstChildFrame(stream, vp, KtNodeTypes.TYPE_REFERENCE_ID)
                    val tSimple = tRef?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, it) }
                    if (tSimple != null) {
                        updatedTypes[pName] = tSimple
                    } else {
                        updatedTypes.remove(pName)
                    }
                }
            }
        }
        return scope.copy(
            localShadowedNames = updatedShadowed,
            localAtomics = updatedAtomics,
            localTypeSimpleNames = updatedTypes,
            thisValueShadowed = thisValueShadowed,
        )
    }

    private fun inferLocalPropertyTypeName(
        stream: PacketStream,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        propFrame: PacketFrame,
    ): String? {
        val (_, retTypeFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, propFrame)
        if (retTypeFrame != null) {
            return FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retTypeFrame)
        }
        val initExpr = FrameAstHelpers.extractInitializerAfterEquals(stream, propFrame) ?: return null
        val clsFrame = resolveReceiverClassFrame(stream, env, scope, initExpr) ?: return null
        return env.symbols.classesByFrame[clsFrame]?.kotlinName
    }

    private fun recordAtomicCallSite(
        stream: PacketStream,
        siteFrame: PacketFrame,
        callFrame: PacketFrame,
        resolvedTarget: ResolvedAtomicReceiver,
        config: CppTargetConfig,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        out: MutableMap<Int, AtomicOperationSite>,
    ): Boolean {
        val calleeRef = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        val method = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()
        val opKind = ATOMIC_METHOD_MAP[method] ?: return false
        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
        val args =
            if (argList != null) {
                FrameAstHelpers
                    .childFramesOfType(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID)
                    .mapNotNull { extractArgExpr(stream, it)?.frameIndex }
            } else {
                emptyList()
            }
        val trailingLambda =
            FrameAstHelpers
                .firstChildFrame(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID)
                ?.let { FrameAstHelpers.unwrapLambdaExpression(stream, it) }
        val curParam = trailingLambda?.let { extractSingleLambdaParam(stream, it) } ?: "it"
        val useKonanCas =
            opKind == AtomicOpKind.COMPARE_AND_SET &&
                config.gcMode == CppGcMode.KONAN_GC &&
                resolvedTarget.fieldInfo.isReferenceElement

        if (opKind in LOOP_OP_KINDS) {
            siteFrame.flags = siteFrame.flags or PacketFlags.ATOMICFU_LOOP
        }
        if (useKonanCas) {
            siteFrame.flags = siteFrame.flags or PacketFlags.GC_VOLATILE_CAS_BARRIER
        }

        out[siteFrame.frameIndex] =
            AtomicOperationSite(
                exprFrameIndex = siteFrame.frameIndex,
                targetFieldInfo = resolvedTarget.fieldInfo,
                receiverPrefixExprFrameIndex = resolvedTarget.prefixExprFrameIndex,
                arrayIndexExprFrameIndex = resolvedTarget.arrayIndexExprFrameIndex,
                kind = opKind,
                argExprFrameIndices = args,
                loopLambdaFrameIndex = trailingLambda?.frameIndex,
                loopCurParamName = curParam,
                useKonanCasBarrier = useKonanCas,
            )
        for (aIdx in args) {
            classifyAtomicOperationsRecursive(stream, stream.frames[aIdx], config, env, scope, out)
        }
        if (trailingLambda != null) {
            classifyAtomicOperationsRecursive(stream, trailingLambda, config, env, scope, out)
        }
        return true
    }

    private data class ResolvedAtomicReceiver(
        val fieldInfo: AtomicFieldInfo,
        val prefixExprFrameIndex: Int?,
        val arrayIndexExprFrameIndex: Int?,
    )

    private fun resolveAtomicTarget(
        stream: PacketStream,
        recvFrame: PacketFrame,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
    ): ResolvedAtomicReceiver? {
        if (recvFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID) {
            val info = scope.localAtomics["this"] ?: return null
            return ResolvedAtomicReceiver(info, prefixExprFrameIndex = null, arrayIndexExprFrameIndex = null)
        }
        if (recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val name = FrameAstHelpers.directIdentPacket(stream, recvFrame)?.unescapedName ?: return null
            val info = resolveBareAtomicName(stream, env, scope, name) ?: return null
            return ResolvedAtomicReceiver(info, prefixExprFrameIndex = null, arrayIndexExprFrameIndex = null)
        }
        if (recvFrame.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
            val parts = FrameAstHelpers.childFrames(stream, recvFrame)
            if (parts.size == 2 && parts[1].nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                val fieldName = FrameAstHelpers.directIdentPacket(stream, parts[1])?.unescapedName ?: return null
                if (parts[0].nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID) {
                    val info = resolveThisDotAtomic(stream, env, scope, parts[0], fieldName) ?: return null
                    return ResolvedAtomicReceiver(info, prefixExprFrameIndex = null, arrayIndexExprFrameIndex = null)
                }
                val info = resolveQualifiedAtomic(stream, env, scope, parts[0], fieldName) ?: return null
                return ResolvedAtomicReceiver(
                    info,
                    prefixExprFrameIndex = parts[0].frameIndex,
                    arrayIndexExprFrameIndex = null,
                )
            }
        }
        if (recvFrame.nodeTypeId == KtNodeTypes.ARRAY_ACCESS_EXPRESSION_ID) {
            val arrExpr = FrameAstHelpers.childFrames(stream, recvFrame).firstOrNull() ?: return null
            val baseTarget = resolveAtomicTarget(stream, arrExpr, env, scope) ?: return null
            val indicesFrame = FrameAstHelpers.firstChildFrame(stream, recvFrame, KtNodeTypes.INDICES_ID)
            val idxExpr = indicesFrame?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
            return baseTarget.copy(arrayIndexExprFrameIndex = idxExpr?.frameIndex)
        }
        return null
    }

    private fun resolveBareAtomicName(
        stream: PacketStream,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        name: String,
    ): AtomicFieldInfo? {
        scope.localAtomics[name]?.let { return it }
        if (name in scope.localShadowedNames) return null
        val curClsIdx = scope.currentClassFrameIndex
        if (curClsIdx != null) {
            val classHit = resolveClassMemberAtomicInOuterChain(stream, env, curClsIdx, name)
            if (classHit.shadowedByNonAtomic) return null
            if (classHit.field != null) return classHit.field
        }
        return env.topLevelAtomics[name]
    }

    private fun resolveThisDotAtomic(
        stream: PacketStream,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        thisFrame: PacketFrame,
        fieldName: String,
    ): AtomicFieldInfo? {
        val labelFrame = FrameAstHelpers.firstChildFrame(stream, thisFrame, KtNodeTypes.LABEL_QUALIFIER_ID)
        if (labelFrame != null) {
            val labelName = FrameAstHelpers.directIdentPacket(stream, labelFrame)?.unescapedName ?: return null
            for (clsIdx in scope.classStack.asReversed()) {
                val clsSym = env.symbols.classesByFrame[clsIdx] ?: continue
                if (clsSym.kotlinName == labelName) {
                    val res = resolveAtomicOnSingleHierarchy(stream, env, clsIdx, fieldName)
                    return if (res.shadowedByNonAtomic) null else res.field
                }
            }
            return null
        }
        val curClsIdx = scope.currentClassFrameIndex ?: return null
        val res = resolveClassMemberAtomicInOuterChain(stream, env, curClsIdx, fieldName)
        return if (res.shadowedByNonAtomic) null else res.field
    }

    private fun resolveQualifiedAtomic(
        stream: PacketStream,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        recvExpr: PacketFrame,
        fieldName: String,
    ): AtomicFieldInfo? {
        val recvClsFrame = resolveReceiverClassFrame(stream, env, scope, recvExpr)
        if (recvClsFrame != null) {
            val res = resolveAtomicOnSingleHierarchy(stream, env, recvClsFrame, fieldName)
            return if (res.shadowedByNonAtomic) null else res.field
        }
        val candidates = env.classesWithAtomicName[fieldName].orEmpty()
        if (candidates.isEmpty()) return env.topLevelAtomics[fieldName]
        val curClsIdx = scope.currentClassFrameIndex
        if (fieldName in env.allNonAtomicPropNames) {
            if (curClsIdx != null) {
                val curRes = resolveClassMemberAtomicInOuterChain(stream, env, curClsIdx, fieldName)
                if (curRes.shadowedByNonAtomic) return null
                if (curRes.field != null && candidates.size == 1) return curRes.field
            }
            return null
        }
        if (curClsIdx != null) {
            val curRes = resolveClassMemberAtomicInOuterChain(stream, env, curClsIdx, fieldName)
            if (curRes.field != null) return curRes.field
        }
        return candidates.firstOrNull()
    }

    private fun resolveReceiverClassFrame(
        stream: PacketStream,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        recvExpr: PacketFrame,
        depth: Int = 0,
    ): Int? {
        if (depth > 8) return null
        var cur = recvExpr
        while (cur.nodeTypeId == KtNodeTypes.PARENTHESIZED_ID) {
            cur = FrameAstHelpers.childFrames(stream, cur).firstOrNull() ?: return null
        }
        return when (cur.nodeTypeId) {
            KtNodeTypes.THIS_EXPRESSION_ID -> resolveThisExprClassFrame(stream, env, scope, cur)
            KtNodeTypes.REFERENCE_EXPRESSION_ID -> resolveRefExprClassFrame(stream, env, scope, cur)
            KtNodeTypes.BINARY_WITH_TYPE_ID -> {
                val typeRef = FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.TYPE_REFERENCE_ID)
                typeRef?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, it) }
                    ?.let { resolveClassFrameByTypeName(stream, env, it) }
            }
            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID,
            -> resolveDotExprClassFrame(stream, env, scope, cur, depth)
            KtNodeTypes.CALL_EXPRESSION_ID -> resolveCallExprClassFrame(stream, env, scope, cur)
            KtNodeTypes.ARRAY_ACCESS_EXPRESSION_ID -> {
                val arrExpr = FrameAstHelpers.childFrames(stream, cur).firstOrNull() ?: return null
                resolveAtomicTarget(stream, arrExpr, env, scope)
                    ?.let { atomicElemClassFrame(stream, env, it.fieldInfo) }
            }
            else -> null
        }
    }

    private fun resolveThisExprClassFrame(
        stream: PacketStream,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        thisFrame: PacketFrame,
    ): Int? {
        val labelFrame = FrameAstHelpers.firstChildFrame(stream, thisFrame, KtNodeTypes.LABEL_QUALIFIER_ID)
        if (labelFrame != null) {
            val labelName = FrameAstHelpers.directIdentPacket(stream, labelFrame)?.unescapedName ?: return null
            return scope.classStack.asReversed().firstOrNull { clsIdx ->
                env.symbols.classesByFrame[clsIdx]?.kotlinName == labelName
            }
        }
        return scope.currentClassFrameIndex
    }

    private fun resolveRefExprClassFrame(
        stream: PacketStream,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        refFrame: PacketFrame,
    ): Int? {
        val name = FrameAstHelpers.directIdentPacket(stream, refFrame)?.unescapedName ?: return null
        scope.localTypeSimpleNames[name]?.let { return resolveClassFrameByTypeName(stream, env, it) }
        if (name in scope.localShadowedNames || name in scope.localAtomics) return null
        val curClsIdx = scope.currentClassFrameIndex
        if (curClsIdx != null) {
            val memberHit = resolveMemberTypeInOuterChain(stream, env, curClsIdx, name, isFunction = false)
            if (memberHit != null) return memberHit
        }
        for (propSym in env.symbols.propertiesByFrame.values) {
            if (propSym.enclosingClassFrameIndex == null && propSym.kotlinName == name) {
                return extractDeclReturnClassFrame(stream, env, stream.frames[propSym.frameIndex])
            }
        }
        return env.symbols.classesByName[name]?.frameIndex
    }

    private fun resolveDotExprClassFrame(
        stream: PacketStream,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        dotFrame: PacketFrame,
        depth: Int,
    ): Int? {
        val parts = FrameAstHelpers.childFrames(stream, dotFrame)
        if (parts.size != 2) return null
        if (parts[1].nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val memberName = FrameAstHelpers.directIdentPacket(stream, parts[1])?.unescapedName ?: return null
            if (memberName == "value") {
                resolveAtomicTarget(stream, parts[0], env, scope)
                    ?.let { return atomicElemClassFrame(stream, env, it.fieldInfo) }
            }
            val baseCls = resolveReceiverClassFrame(stream, env, scope, parts[0], depth + 1) ?: return null
            return resolveMemberTypeInHierarchy(stream, env, baseCls, memberName, isFunction = false)
        }
        if (parts[1].nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val calleeRef = FrameAstHelpers.firstChildFrame(stream, parts[1], KtNodeTypes.REFERENCE_EXPRESSION_ID)
            val methodName = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
                ?: return null
            val baseCls = resolveReceiverClassFrame(stream, env, scope, parts[0], depth + 1) ?: return null
            return resolveMemberTypeInHierarchy(stream, env, baseCls, methodName, isFunction = true)
        }
        return null
    }

    private fun resolveCallExprClassFrame(
        stream: PacketStream,
        env: AtomicScopeIndex,
        scope: AtomicLexicalScope,
        callFrame: PacketFrame,
    ): Int? {
        val calleeRef = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        val calleeName = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName } ?: return null
        env.symbols.classesByName[calleeName]?.let { return it.frameIndex }
        val curClsIdx = scope.currentClassFrameIndex
        if (curClsIdx != null) {
            val memberHit = resolveMemberTypeInOuterChain(stream, env, curClsIdx, calleeName, isFunction = true)
            if (memberHit != null) return memberHit
        }
        for (fnSym in env.symbols.functionsByFrame.values) {
            if (fnSym.enclosingClassFrameIndex == null && fnSym.kotlinName == calleeName) {
                return extractDeclReturnClassFrame(stream, env, stream.frames[fnSym.frameIndex])
            }
        }
        return null
    }

    private fun atomicElemClassFrame(
        stream: PacketStream,
        env: AtomicScopeIndex,
        info: AtomicFieldInfo,
    ): Int? {
        val elemTypeIdx = info.explicitElementTypeFrameIndex ?: return null
        val simpleName = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[elemTypeIdx]) ?: return null
        return resolveClassFrameByTypeName(stream, env, simpleName)
    }

    private fun extractDeclReturnClassFrame(
        stream: PacketStream,
        env: AtomicScopeIndex,
        declFrame: PacketFrame,
    ): Int? {
        val (_, retFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, declFrame)
        return retFrame
            ?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, it) }
            ?.let { resolveClassFrameByTypeName(stream, env, it) }
    }

    private fun resolveClassFrameByTypeName(
        stream: PacketStream,
        env: AtomicScopeIndex,
        typeName: String,
    ): Int? {
        env.symbols.classesByName[typeName]?.let { return it.frameIndex }
        for (alias in env.symbols.typeAliasesByFrame.values) {
            if (alias.kotlinName == typeName) {
                val underlyingIdx = alias.underlyingTypeFrameIndex ?: continue
                val underlyingSimple =
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[underlyingIdx]) ?: continue
                env.symbols.classesByName[underlyingSimple]?.let { return it.frameIndex }
            }
        }
        return null
    }

    private fun resolveMemberTypeInOuterChain(
        stream: PacketStream,
        env: AtomicScopeIndex,
        startClsIdx: Int,
        memberName: String,
        isFunction: Boolean,
    ): Int? {
        resolveMemberTypeInHierarchy(stream, env, startClsIdx, memberName, isFunction)?.let { return it }
        var curCls = env.symbols.classesByFrame[startClsIdx]
        while (curCls != null) {
            val parentIdx = curCls.enclosingClassFrameIndex ?: break
            val targetIdx =
                if (curCls.isInner) parentIdx else env.symbols.classesByFrame[parentIdx]?.companionFrameIndex
            if (targetIdx != null) {
                resolveMemberTypeInHierarchy(stream, env, targetIdx, memberName, isFunction)?.let { return it }
            }
            curCls = env.symbols.classesByFrame[parentIdx]
        }
        return null
    }

    private fun resolveMemberTypeInHierarchy(
        stream: PacketStream,
        env: AtomicScopeIndex,
        clsIdx: Int,
        memberName: String,
        isFunction: Boolean,
        visited: MutableSet<Int> = HashSet(),
    ): Int? {
        if (!visited.add(clsIdx)) return null
        val clsSym = env.symbols.classesByFrame[clsIdx] ?: return null
        if (!isFunction) {
            for (vp in clsSym.primaryConstructorParams) {
                if ((vp.isVal || vp.isVar) && vp.name == memberName) {
                    val typeName = vp.typeFrameIndex?.let {
                        FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                    }
                    return typeName?.let { resolveClassFrameByTypeName(stream, env, it) }
                }
            }
        }
        val memberIndices = if (isFunction) clsSym.memberFunctionFrameIndices else clsSym.memberPropertyFrameIndices
        for (mIdx in memberIndices) {
            val memberFrame = stream.frames[mIdx]
            if (FrameAstHelpers.directIdentPacket(stream, memberFrame)?.unescapedName == memberName) {
                return extractDeclReturnClassFrame(stream, env, memberFrame)
            }
        }
        for (superIdx in clsSym.superTypeFrameIndices) {
            val superName = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            val superCls = env.symbols.classesByName[superName] ?: continue
            resolveMemberTypeInHierarchy(stream, env, superCls.frameIndex, memberName, isFunction, visited)
                ?.let { return it }
        }
        return null
    }

    private data class HierarchyLookupResult(
        val field: AtomicFieldInfo?,
        val shadowedByNonAtomic: Boolean,
    )

    private fun resolveClassMemberAtomicInOuterChain(
        stream: PacketStream,
        env: AtomicScopeIndex,
        startClsIdx: Int,
        fieldName: String,
    ): HierarchyLookupResult {
        val direct = resolveAtomicOnSingleHierarchy(stream, env, startClsIdx, fieldName)
        if (direct.field != null || direct.shadowedByNonAtomic) return direct

        var curCls = env.symbols.classesByFrame[startClsIdx]
        while (curCls != null) {
            val parentIdx = curCls.enclosingClassFrameIndex ?: break
            val parentRes =
                if (curCls.isInner) {
                    resolveAtomicOnSingleHierarchy(stream, env, parentIdx, fieldName)
                } else {
                    val compIdx = env.symbols.classesByFrame[parentIdx]?.companionFrameIndex
                    if (compIdx != null) {
                        resolveAtomicOnSingleHierarchy(stream, env, compIdx, fieldName)
                    } else {
                        HierarchyLookupResult(null, false)
                    }
                }
            if (parentRes.field != null || parentRes.shadowedByNonAtomic) return parentRes
            curCls = env.symbols.classesByFrame[parentIdx]
        }
        return HierarchyLookupResult(null, false)
    }

    private fun resolveAtomicOnSingleHierarchy(
        stream: PacketStream,
        env: AtomicScopeIndex,
        clsIdx: Int,
        fieldName: String,
        visited: MutableSet<Int> = HashSet(),
    ): HierarchyLookupResult {
        if (!visited.add(clsIdx)) return HierarchyLookupResult(null, false)
        env.classAtomicsByClass[clsIdx]?.get(fieldName)?.let {
            return HierarchyLookupResult(it, shadowedByNonAtomic = false)
        }
        if (env.nonAtomicPropsByClass[clsIdx]?.contains(fieldName) == true) {
            return HierarchyLookupResult(null, shadowedByNonAtomic = true)
        }
        val clsSym = env.symbols.classesByFrame[clsIdx] ?: return HierarchyLookupResult(null, false)
        val compIdx = clsSym.companionFrameIndex
        if (compIdx != null && compIdx !in visited) {
            val compRes = resolveAtomicOnSingleHierarchy(stream, env, compIdx, fieldName, visited)
            if (compRes.field != null || compRes.shadowedByNonAtomic) return compRes
        }
        for (superIdx in clsSym.superTypeFrameIndices) {
            val superName = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            env.symbols.classesByName[superName]?.let { superCls ->
                val subRes = resolveAtomicOnSingleHierarchy(stream, env, superCls.frameIndex, fieldName, visited)
                if (subRes.field != null || subRes.shadowedByNonAtomic) return subRes
            }
        }
        return HierarchyLookupResult(null, false)
    }

    private fun collectScopedAtomicValueParams(
        stream: PacketStream,
        frame: PacketFrame,
        atomicByName: MutableMap<String, AtomicFieldInfo>,
        classAtomicsByClass: MutableMap<Int, MutableMap<String, AtomicFieldInfo>>,
        scopedByOwner: MutableMap<Int, MutableMap<String, AtomicFieldInfo>>,
    ) {
        val isOwnerFrame =
            frame.nodeTypeId == KtNodeTypes.CLASS_ID ||
                frame.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID ||
                frame.nodeTypeId == KtNodeTypes.FUN_ID ||
                frame.nodeTypeId == KtNodeTypes.SECONDARY_CONSTRUCTOR_ID
        if (isOwnerFrame) {
            val isClassOwner =
                frame.nodeTypeId == KtNodeTypes.CLASS_ID ||
                    frame.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID
            val paramList =
                if (isClassOwner) {
                    val primaryCtor = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.PRIMARY_CONSTRUCTOR_ID)
                    if (primaryCtor != null) {
                        FrameAstHelpers.firstChildFrame(stream, primaryCtor, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
                    } else {
                        FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
                    }
                } else {
                    FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
                }
            if (frame.nodeTypeId == KtNodeTypes.FUN_ID) {
                val (recvTypeFrame, _) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, frame)
                val recvSimple =
                    recvTypeFrame?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, it) }
                if (recvTypeFrame != null && recvSimple in ATOMIC_PARAM_TYPE_NAMES) {
                    val (explicitElemType, inferredType, isRefElem) =
                        resolveAtomicParamElemType(stream, recvTypeFrame, recvSimple!!)
                    val selfInfo =
                        AtomicFieldInfo(
                            propFrameIndex = frame.frameIndex,
                            kotlinName = "this",
                            cppName = "self",
                            explicitElementTypeFrameIndex = explicitElemType?.frameIndex,
                            inferredElementCppType = inferredType,
                            initExprFrameIndex = null,
                            isArray = false,
                            arraySizeExprFrameIndex = null,
                            isReferenceElement = isRefElem,
                        )
                    scopedByOwner.getOrPut(frame.frameIndex) { LinkedHashMap() }["this"] = selfInfo
                }
            }
            if (paramList != null) {
                for (vpFrame in FrameAstHelpers.childFramesOfType(stream, paramList, KtNodeTypes.VALUE_PARAMETER_ID)) {
                    val ident = FrameAstHelpers.directIdentPacket(stream, vpFrame) ?: continue
                    val typeRef = FrameAstHelpers.firstChildFrame(stream, vpFrame, KtNodeTypes.TYPE_REFERENCE_ID) ?: continue
                    val typeSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, typeRef) ?: continue
                    if (typeSimple !in ATOMIC_PARAM_TYPE_NAMES) continue
                    val (explicitElemType, inferredType, isRefElem) =
                        resolveAtomicParamElemType(stream, typeRef, typeSimple)
                    val kotlinName = ident.unescapedName
                    val cppName = ident.targetCppName ?: CppNamingPolicy.toSnakeCase(kotlinName)
                    val isClassProperty =
                        isClassOwner &&
                            (
                                FrameAstHelpers.hasDirectKeyword(stream, vpFrame, KtTokens.VAL_KEYWORD_ID) ||
                                    FrameAstHelpers.hasDirectKeyword(stream, vpFrame, KtTokens.VAR_KEYWORD_ID)
                            )
                    val info =
                        AtomicFieldInfo(
                            propFrameIndex = vpFrame.frameIndex,
                            kotlinName = kotlinName,
                            cppName = cppName,
                            explicitElementTypeFrameIndex = explicitElemType?.frameIndex,
                            inferredElementCppType = inferredType,
                            initExprFrameIndex = null,
                            isArray = false,
                            arraySizeExprFrameIndex = null,
                            isReferenceElement = isRefElem,
                            enclosingClassFrameIndex = if (isClassProperty) frame.frameIndex else null,
                        )
                    scopedByOwner.getOrPut(frame.frameIndex) { LinkedHashMap() }[kotlinName] = info
                    if (isClassProperty) {
                        classAtomicsByClass.getOrPut(frame.frameIndex) { LinkedHashMap() }[kotlinName] = info
                        if (kotlinName !in atomicByName) {
                            atomicByName[kotlinName] = info
                        }
                    }
                }
            }
        }
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            collectScopedAtomicValueParams(
                stream = stream,
                frame = child,
                atomicByName = atomicByName,
                classAtomicsByClass = classAtomicsByClass,
                scopedByOwner = scopedByOwner,
            )
        }
    }

    private fun resolveAtomicParamElemType(
        stream: PacketStream,
        typeRef: PacketFrame,
        typeSimple: String,
    ): Triple<PacketFrame?, String?, Boolean> {
        val isRefParam = typeSimple == "AtomicRef" || typeSimple == "AtomicReference"
        val explicitElemType = if (isRefParam) extractFirstTypeArgFrame(stream, typeRef) else null
        val (inferredType, isRefElem) =
            when (typeSimple) {
                "AtomicInt" -> "std::int32_t" to false
                "AtomicLong" -> "std::int64_t" to false
                "AtomicBoolean" -> "bool" to false
                "AtomicNativePtr" -> "void*" to false
                else -> {
                    if (explicitElemType != null) {
                        val base = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, explicitElemType)
                        val isRef = base == null || base !in PRIMITIVE_ATOMIC_TYPE_NAMES
                        null to isRef
                    } else {
                        "std::any" to true
                    }
                }
            }
        return Triple(explicitElemType, inferredType, isRefElem)
    }

    private fun extractFirstTypeArgFrame(
        stream: PacketStream,
        ownerFrame: PacketFrame,
    ): PacketFrame? {
        val targetFrame =
            when (ownerFrame.nodeTypeId) {
                KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
                KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID,
                -> FrameAstHelpers.childFrames(stream, ownerFrame).lastOrNull() ?: ownerFrame
                else -> ownerFrame
            }
        val taList =
            if (targetFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID ||
                targetFrame.nodeTypeId == KtNodeTypes.USER_TYPE_ID
            ) {
                FrameAstHelpers.firstChildFrame(stream, targetFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
            } else {
                FrameAstHelpers.findDescendantFrame(stream, targetFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
            } ?: return null
        val proj = FrameAstHelpers.firstChildFrame(stream, taList, KtNodeTypes.TYPE_PROJECTION_ID) ?: return null
        return FrameAstHelpers.firstChildFrame(stream, proj, KtNodeTypes.TYPE_REFERENCE_ID)
    }

    private fun extractArgExpr(
        stream: PacketStream,
        vaFrame: PacketFrame,
    ): PacketFrame? {
        for (child in FrameAstHelpers.childFrames(stream, vaFrame)) {
            if (child.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID) return child
        }
        return null
    }

    private fun unwrapQualifiedCall(
        stream: PacketStream,
        exprFrame: PacketFrame,
    ): PacketFrame? {
        if (exprFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) return exprFrame
        if (exprFrame.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
            val parts = FrameAstHelpers.childFrames(stream, exprFrame)
            if (parts.size == 2) return unwrapQualifiedCall(stream, parts[1])
        }
        return null
    }

    private fun extractSingleLambdaParam(
        stream: PacketStream,
        lambdaFrame: PacketFrame,
    ): String? {
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID) ?: return null
        val pList = FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.VALUE_PARAMETER_LIST_ID) ?: return null
        val vp = FrameAstHelpers.firstChildFrame(stream, pList, KtNodeTypes.VALUE_PARAMETER_ID) ?: return null
        return FrameAstHelpers.directIdentPacket(stream, vp)?.unescapedName
    }

    private val ATOMIC_PARAM_TYPE_NAMES: Set<String> =
        setOf(
            "AtomicInt",
            "AtomicLong",
            "AtomicBoolean",
            "AtomicNativePtr",
            "AtomicRef",
            "AtomicReference",
        )

    private val ATOMIC_FACTORY_CALLEE_NAMES: Set<String> =
        setOf(
            "atomic",
            "AtomicReference",
            "AtomicInt",
            "AtomicLong",
            "AtomicBoolean",
            "AtomicNativePtr",
        )

    private val PRIMITIVE_ATOMIC_TYPE_NAMES: Set<String> =
        setOf(
            "Int",
            "Long",
            "Boolean",
            "UInt",
            "ULong",
            "Byte",
            "Short",
        )

    private val LOOP_OP_KINDS: Set<AtomicOpKind> =
        setOf(
            AtomicOpKind.LOOP,
            AtomicOpKind.UPDATE,
            AtomicOpKind.GET_AND_UPDATE,
            AtomicOpKind.UPDATE_AND_GET,
        )

    private val ATOMIC_METHOD_MAP: Map<String, AtomicOpKind> =
        mapOf(
            "lazySet" to AtomicOpKind.STORE_RELEASE,
            "compareAndSet" to AtomicOpKind.COMPARE_AND_SET,
            "getAndSet" to AtomicOpKind.EXCHANGE,
            "incrementAndGet" to AtomicOpKind.INCREMENT_AND_GET,
            "getAndIncrement" to AtomicOpKind.GET_AND_INCREMENT,
            "decrementAndGet" to AtomicOpKind.DECREMENT_AND_GET,
            "getAndDecrement" to AtomicOpKind.GET_AND_DECREMENT,
            "addAndGet" to AtomicOpKind.ADD_AND_GET,
            "getAndAdd" to AtomicOpKind.GET_AND_ADD,
            "loop" to AtomicOpKind.LOOP,
            "update" to AtomicOpKind.UPDATE,
            "getAndUpdate" to AtomicOpKind.GET_AND_UPDATE,
            "updateAndGet" to AtomicOpKind.UPDATE_AND_GET,
        )
}
