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
    fun run(
        stream: PacketStream,
        config: CppTargetConfig,
        symbols: SameFileSymbolTable,
    ): AtomicAndThreadLocalPlan {
        val atomicByProp = LinkedHashMap<Int, AtomicFieldInfo>()
        val atomicByName = LinkedHashMap<String, AtomicFieldInfo>()
        val threadLocalByProp = LinkedHashMap<Int, ThreadLocalSlotInfo>()
        val threadLocalByName = LinkedHashMap<String, ThreadLocalSlotInfo>()
        val atomicOps = LinkedHashMap<Int, AtomicOperationSite>()

        for (propSym in symbols.propertiesByFrame.values) {
            val propFrame = stream.frames[propSym.frameIndex]
            val initIdx = propSym.initializerFrameIndex
            if (initIdx != null) {
                val initFrame = stream.frames[initIdx]
                parseAtomicInitializer(stream, propSym, propFrame, initFrame)?.let { info ->
                    atomicByProp[propSym.frameIndex] = info
                    atomicByName[propSym.kotlinName] = info
                }
                parseThreadLocalInitializer(stream, propSym, propFrame, initFrame)?.let { tl ->
                    threadLocalByProp[propSym.frameIndex] = tl
                    threadLocalByName[propSym.kotlinName] = tl
                }
            } else if (propSym.returnTypeFrameIndex != null) {
                val retFrame = stream.frames[propSym.returnTypeFrameIndex]
                val retBase = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retFrame)
                if (retBase == "CommonThreadLocal" || retBase == "ThreadLocal") {
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
                val cppName = ident.targetCppName ?: CppNamingPolicy.toSnakeCase(ident.unescapedName)
                val dummySym =
                    KtPropertySymbol(
                        kotlinName = ident.unescapedName,
                        cppName = cppName,
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
                parseAtomicInitializer(stream, dummySym, frame, initFrame)?.let { info ->
                    atomicByProp[frame.frameIndex] = info
                    if (info.kotlinName !in atomicByName) {
                        atomicByName[info.kotlinName] = info
                    }
                }
            }
        }

        if (atomicByName.isNotEmpty()) {
            classifyAtomicOperationsRecursive(
                stream = stream,
                frame = stream.rootFrame,
                config = config,
                atomicByName = atomicByName,
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

    private fun parseAtomicInitializer(
        stream: PacketStream,
        propSym: KtPropertySymbol,
        propFrame: PacketFrame,
        initFrame: PacketFrame,
    ): AtomicFieldInfo? {
        if (initFrame.nodeTypeId != KtNodeTypes.CALL_EXPRESSION_ID) return null
        val calleeRef = FrameAstHelpers.firstChildFrame(stream, initFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        val callee = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()
        if (callee != "atomic" && callee != "atomicArrayOfNulls") return null

        val explicitElemType = extractFirstTypeArgFrame(stream, initFrame)
        val argList = FrameAstHelpers.firstChildFrame(stream, initFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
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
            )
        }

        propFrame.flags = propFrame.flags or PacketFlags.ATOMICFU_FIELD
        val (inferredType, isRef) = inferAtomicElementType(stream, explicitElemType, firstArgExpr)
        return AtomicFieldInfo(
            propFrameIndex = propSym.frameIndex,
            kotlinName = propSym.kotlinName,
            cppName = propSym.cppName,
            explicitElementTypeFrameIndex = explicitElemType?.frameIndex,
            inferredElementCppType = inferredType,
            initExprFrameIndex = firstArgExpr?.frameIndex,
            isArray = false,
            arraySizeExprFrameIndex = null,
            isReferenceElement = isRef,
        )
    }

    private fun inferAtomicElementType(
        stream: PacketStream,
        explicitTypeFrame: PacketFrame?,
        initExprFrame: PacketFrame?,
    ): Pair<String?, Boolean> {
        if (explicitTypeFrame != null) {
            val base = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, explicitTypeFrame)
            val isRef = base == null || base !in setOf("Int", "Long", "Boolean", "UInt", "ULong", "Byte", "Short")
            return null to isRef
        }
        if (initExprFrame == null) return "void*" to true
        val firstPkt = stream.packets.getOrNull(initExprFrame.firstPacketIndex)
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
                LiteralPacketKind.NULL -> "void*" to true
                else -> "void*" to true
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
                ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.LAMBDA_EXPRESSION_ID) }
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
        atomicByName: Map<String, AtomicFieldInfo>,
        out: MutableMap<Int, AtomicOperationSite>,
    ) {
        // 1. Check `atomicField.value = rhs` assignment BEFORE visiting child `DOT_QUALIFIED_EXPRESSION`
        if (frame.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val children = FrameAstHelpers.childFrames(stream, frame)
            if (children.size >= 3) {
                val lhs = children[0]
                val opId = FrameAstHelpers.operationTokenId(stream, children[1])
                val rhs = children[2]
                if (opId == KtTokens.EQ_ID && lhs.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
                    val dotChildren = FrameAstHelpers.childFrames(stream, lhs)
                    if (dotChildren.size == 2 && dotChildren[1].nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        val propName = FrameAstHelpers.directIdentPacket(stream, dotChildren[1])?.unescapedName
                        val resolvedTarget = resolveAtomicTarget(stream, dotChildren[0], atomicByName)
                        if (propName == "value" && resolvedTarget != null) {
                            out[frame.frameIndex] =
                                AtomicOperationSite(
                                    exprFrameIndex = frame.frameIndex,
                                    targetFieldInfo = resolvedTarget.fieldInfo,
                                    receiverPrefixExprFrameIndex = resolvedTarget.prefixExprFrameIndex,
                                    arrayIndexExprFrameIndex = resolvedTarget.arrayIndexExprFrameIndex,
                                    kind = AtomicOpKind.STORE_SEQ_CST,
                                    argExprFrameIndices = listOf(rhs.frameIndex),
                                    loopLambdaFrameIndex = null,
                                    loopCurParamName = null,
                                    useKonanCasBarrier = false,
                                )
                            // Classify inside rhs only, skipping lhs `.value` so it isn't double-marked as a load!
                            classifyAtomicOperationsRecursive(stream, rhs, config, atomicByName, out)
                            return
                        }
                    }
                }
            }
        }

        // 2. Check `atomicField.op(...)` or `atomicField.value`
        if (frame.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
            val children = FrameAstHelpers.childFrames(stream, frame)
            if (children.size == 2) {
                val recv = children[0]
                val sel = children[1]
                val resolvedTarget = resolveAtomicTarget(stream, recv, atomicByName)
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
                        val calleeRef = FrameAstHelpers.firstChildFrame(stream, sel, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                        val method = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()
                        val opKind = ATOMIC_METHOD_MAP[method]
                        if (opKind != null) {
                            val argList = FrameAstHelpers.firstChildFrame(stream, sel, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
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
                                    .firstChildFrame(stream, sel, KtNodeTypes.LAMBDA_ARGUMENT_ID)
                                    ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.LAMBDA_EXPRESSION_ID) }
                            val curParam =
                                trailingLambda?.let { extractSingleLambdaParam(stream, it) } ?: "it"
                            val useKonanCas =
                                opKind == AtomicOpKind.COMPARE_AND_SET &&
                                    config.gcMode == CppGcMode.KONAN_GC &&
                                    resolvedTarget.fieldInfo.isReferenceElement

                            if (opKind in LOOP_OP_KINDS) {
                                frame.flags = frame.flags or PacketFlags.ATOMICFU_LOOP
                            }
                            if (useKonanCas) {
                                frame.flags = frame.flags or PacketFlags.GC_VOLATILE_CAS_BARRIER
                            }

                            out[frame.frameIndex] =
                                AtomicOperationSite(
                                    exprFrameIndex = frame.frameIndex,
                                    targetFieldInfo = resolvedTarget.fieldInfo,
                                    receiverPrefixExprFrameIndex = resolvedTarget.prefixExprFrameIndex,
                                    arrayIndexExprFrameIndex = resolvedTarget.arrayIndexExprFrameIndex,
                                    kind = opKind,
                                    argExprFrameIndices = args,
                                    loopLambdaFrameIndex = trailingLambda?.frameIndex,
                                    loopCurParamName = curParam,
                                    useKonanCasBarrier = useKonanCas,
                                )
                            // Recurse into arguments / lambda body so nested atomic ops are also classified!
                            for (aIdx in args) {
                                classifyAtomicOperationsRecursive(stream, stream.frames[aIdx], config, atomicByName, out)
                            }
                            if (trailingLambda != null) {
                                classifyAtomicOperationsRecursive(stream, trailingLambda, config, atomicByName, out)
                            }
                            return
                        }
                    }
                }
            }
        }

        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            classifyAtomicOperationsRecursive(stream, child, config, atomicByName, out)
        }
    }

    private data class ResolvedAtomicReceiver(
        val fieldInfo: AtomicFieldInfo,
        val prefixExprFrameIndex: Int?,
        val arrayIndexExprFrameIndex: Int?,
    )

    private fun resolveAtomicTarget(
        stream: PacketStream,
        recvFrame: PacketFrame,
        atomicByName: Map<String, AtomicFieldInfo>,
    ): ResolvedAtomicReceiver? {
        if (recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val name = FrameAstHelpers.directIdentPacket(stream, recvFrame)?.unescapedName ?: return null
            val info = atomicByName[name] ?: return null
            return ResolvedAtomicReceiver(info, prefixExprFrameIndex = null, arrayIndexExprFrameIndex = null)
        }
        if (recvFrame.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
            val parts = FrameAstHelpers.childFrames(stream, recvFrame)
            if (parts.size == 2 && parts[1].nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                val name = FrameAstHelpers.directIdentPacket(stream, parts[1])?.unescapedName ?: return null
                val info = atomicByName[name] ?: return null
                val prefixIdx =
                    if (parts[0].nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID) null else parts[0].frameIndex
                return ResolvedAtomicReceiver(info, prefixExprFrameIndex = prefixIdx, arrayIndexExprFrameIndex = null)
            }
        }
        if (recvFrame.nodeTypeId == KtNodeTypes.ARRAY_ACCESS_EXPRESSION_ID) {
            val arrExpr = FrameAstHelpers.childFrames(stream, recvFrame).firstOrNull() ?: return null
            val baseTarget = resolveAtomicTarget(stream, arrExpr, atomicByName) ?: return null
            val indicesFrame = FrameAstHelpers.firstChildFrame(stream, recvFrame, KtNodeTypes.INDICES_ID)
            val idxExpr = indicesFrame?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
            return baseTarget.copy(arrayIndexExprFrameIndex = idxExpr?.frameIndex)
        }
        return null
    }

    private fun extractFirstTypeArgFrame(
        stream: PacketStream,
        ownerFrame: PacketFrame,
    ): PacketFrame? {
        val taList = FrameAstHelpers.findDescendantFrame(stream, ownerFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID) ?: return null
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

    private fun extractSingleLambdaParam(
        stream: PacketStream,
        lambdaFrame: PacketFrame,
    ): String? {
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID) ?: return null
        val pList = FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.VALUE_PARAMETER_LIST_ID) ?: return null
        val vp = FrameAstHelpers.firstChildFrame(stream, pList, KtNodeTypes.VALUE_PARAMETER_ID) ?: return null
        return FrameAstHelpers.directIdentPacket(stream, vp)?.unescapedName
    }

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
