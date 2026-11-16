package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream

internal data class HoistedControlFlowValue(
    val controlFlowFrameIndex: Int,
    val enclosingStmtFrameIndex: Int,
    val slotCppName: String,
)

internal data class SmartCastBinding(
    val scopeFrameIndex: Int,
    val kotlinVarName: String,
    val narrowedCppName: String,
    val targetTypeFrameIndex: Int,
    val isNegated: Boolean,
)

internal class ControlFlowAndTypePlan(
    val hoistedSuspendControlFlowsByExpr: Map<Int, HoistedControlFlowValue>,
    val hoistedSuspendControlFlowsBeforeStmt: Map<Int, List<HoistedControlFlowValue>>,
    val singleEvalTempNamesByFrame: Map<Int, String>,
    val smartCastsByScopeFrame: Map<Int, List<SmartCastBinding>>,
)

/**
 * Pass 5: Expression-valued `if`/`when`/`try` classification (including non-IIFE lexical hoisting
 * whenever `HAS_SUSPEND_IN_SUBTREE` is active), single-evaluation subject temporaries,
 * `is SubType` / `!= null` smart-casts, `Result<T>` value-channel tagging, and C++23
 * `std::unreachable()` diverging control-flow detection.
 */
internal object ControlFlowAndTypePass {
    fun run(
        stream: PacketStream,
        symbols: SameFileSymbolTable,
    ): ControlFlowAndTypePlan {
        val hoistedByExpr = LinkedHashMap<Int, HoistedControlFlowValue>()
        val hoistedBeforeStmt = LinkedHashMap<Int, MutableList<HoistedControlFlowValue>>()
        val singleEvalTemps = LinkedHashMap<Int, String>()
        val smartCasts = LinkedHashMap<Int, MutableList<SmartCastBinding>>()
        var cfCounter = 0
        var tempCounter = 0

        val nothingFunNames = LinkedHashSet<String>(BUILTIN_DIVERGING_CALLS)
        for (fn in symbols.functionsByFrame.values) {
            val retIdx = fn.returnTypeFrameIndex ?: continue
            val retFrame = stream.frames[retIdx]
            if (FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retFrame) == "Nothing" &&
                !FrameAstHelpers.isNullableTypeFrame(stream, retFrame)
            ) {
                nothingFunNames.add(fn.kotlinName)
            }
        }

        for (fn in symbols.functionsByFrame.values) {
            val bodyIdx = fn.bodyFrameIndex ?: continue
            val bodyFrame = stream.frames[bodyIdx]
            val fnNothingNames = LinkedHashSet<String>(nothingFunNames)
            for (vp in fn.valueParams) {
                val tfIdx = vp.typeFrameIndex ?: continue
                val tf = stream.frames[tfIdx]
                val fnTypeFrame =
                    if (tf.nodeTypeId == KtNodeTypes.FUNCTION_TYPE_ID) {
                        tf
                    } else {
                        FrameAstHelpers.firstChildFrame(stream, tf, KtNodeTypes.FUNCTION_TYPE_ID)
                    }
                val retTr =
                    fnTypeFrame?.let {
                        FrameAstHelpers.childFramesOfType(stream, it, KtNodeTypes.TYPE_REFERENCE_ID).lastOrNull()
                    }
                if (retTr != null &&
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retTr) == "Nothing" &&
                    !FrameAstHelpers.isNullableTypeFrame(stream, retTr)
                ) {
                    fnNothingNames.add(vp.name)
                }
            }
            val ctx =
                CfWalkContext(
                    stream = stream,
                    nothingFunNames = fnNothingNames,
                    hoistedByExpr = hoistedByExpr,
                    hoistedBeforeStmt = hoistedBeforeStmt,
                    singleEvalTemps = singleEvalTemps,
                    smartCasts = smartCasts,
                    nextCfIndex = { cfCounter++ },
                    nextTempIndex = { tempCounter++ },
                )
            walkFrame(
                frame = bodyFrame,
                inValuePosition = fn.isExpressionBody,
                enclosingStmtFrameIndex = bodyFrame.frameIndex,
                ctx = ctx,
            )
        }

        for (prop in symbols.propertiesByFrame.values) {
            val entries =
                listOfNotNull(
                    prop.initializerFrameIndex?.let { it to true },
                    prop.getterFrameIndex?.let { it to true },
                    prop.setterFrameIndex?.let { it to false },
                )
            for ((idx, isValuePos) in entries) {
                val ctx =
                    CfWalkContext(
                        stream = stream,
                        nothingFunNames = nothingFunNames,
                        hoistedByExpr = hoistedByExpr,
                        hoistedBeforeStmt = hoistedBeforeStmt,
                        singleEvalTemps = singleEvalTemps,
                        smartCasts = smartCasts,
                        nextCfIndex = { cfCounter++ },
                        nextTempIndex = { tempCounter++ },
                    )
                walkFrame(stream.frames[idx], inValuePosition = isValuePos, enclosingStmtFrameIndex = idx, ctx = ctx)
            }
        }

        return ControlFlowAndTypePlan(
            hoistedSuspendControlFlowsByExpr = hoistedByExpr,
            hoistedSuspendControlFlowsBeforeStmt = hoistedBeforeStmt,
            singleEvalTempNamesByFrame = singleEvalTemps,
            smartCastsByScopeFrame = smartCasts,
        )
    }

    private class CfWalkContext(
        val stream: PacketStream,
        val nothingFunNames: Set<String>,
        val hoistedByExpr: MutableMap<Int, HoistedControlFlowValue>,
        val hoistedBeforeStmt: MutableMap<Int, MutableList<HoistedControlFlowValue>>,
        val singleEvalTemps: MutableMap<Int, String>,
        val smartCasts: MutableMap<Int, MutableList<SmartCastBinding>>,
        val nextCfIndex: () -> Int,
        val nextTempIndex: () -> Int,
    )

    private fun walkFrame(
        frame: PacketFrame,
        inValuePosition: Boolean,
        enclosingStmtFrameIndex: Int,
        ctx: CfWalkContext,
    ) {
        val stream = ctx.stream
        when (frame.nodeTypeId) {
            KtNodeTypes.THROW_ID,
            KtNodeTypes.RETURN_ID,
            KtNodeTypes.BREAK_ID,
            KtNodeTypes.CONTINUE_ID,
            -> {
                frame.flags = frame.flags or PacketFlags.DIVERGING_EXPR
                for (child in FrameAstHelpers.childFrames(stream, frame)) {
                    walkFrame(child, inValuePosition = true, enclosingStmtFrameIndex = enclosingStmtFrameIndex, ctx = ctx)
                }
                return
            }

            KtNodeTypes.BLOCK_ID -> {
                val children = FrameAstHelpers.childFrames(stream, frame)
                for ((idx, stmt) in children.withIndex()) {
                    val isLastValue = inValuePosition && idx == children.lastIndex
                    walkFrame(stmt, inValuePosition = isLastValue, enclosingStmtFrameIndex = stmt.frameIndex, ctx = ctx)
                }
                return
            }

            KtNodeTypes.PROPERTY_ID -> {
                val init = FrameAstHelpers.extractInitializerAfterEquals(stream, frame)
                if (init != null) {
                    walkFrame(init, inValuePosition = true, enclosingStmtFrameIndex = frame.frameIndex, ctx = ctx)
                }
                return
            }

            KtNodeTypes.IF_ID -> {
                if (inValuePosition) {
                    frame.flags = frame.flags or PacketFlags.EXPRESSION_VALUE_POSITION
                    val needsHoist =
                        PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE) ||
                            FrameAstHelpers.subtreeHasUnhandledControlJump(stream, frame)
                    if (needsHoist && enclosingStmtFrameIndex != frame.frameIndex) {
                        registerHoistedControlFlow(frame, enclosingStmtFrameIndex, ctx)
                    }
                }
                val condWrap = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.CONDITION_ID)
                val thenWrap = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.THEN_ID)
                val elseWrap = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.ELSE_ID)
                if (condWrap != null) {
                    detectIfConditionSmartCasts(stream, condWrap, thenWrap, elseWrap, ctx)
                    walkFrame(condWrap, inValuePosition = true, enclosingStmtFrameIndex = enclosingStmtFrameIndex, ctx = ctx)
                }
                if (thenWrap != null) {
                    for (c in FrameAstHelpers.childFrames(stream, thenWrap)) {
                        walkFrame(c, inValuePosition = inValuePosition, enclosingStmtFrameIndex = enclosingStmtFrameIndex, ctx = ctx)
                    }
                }
                if (elseWrap != null) {
                    for (c in FrameAstHelpers.childFrames(stream, elseWrap)) {
                        walkFrame(c, inValuePosition = inValuePosition, enclosingStmtFrameIndex = enclosingStmtFrameIndex, ctx = ctx)
                    }
                }
                return
            }

            KtNodeTypes.WHEN_ID -> {
                if (inValuePosition) {
                    frame.flags = frame.flags or PacketFlags.EXPRESSION_VALUE_POSITION
                    val needsHoist =
                        PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE) ||
                            FrameAstHelpers.subtreeHasUnhandledControlJump(stream, frame)
                    if (needsHoist && enclosingStmtFrameIndex != frame.frameIndex) {
                        registerHoistedControlFlow(frame, enclosingStmtFrameIndex, ctx)
                    }
                }
                // Detect subject expression (any child before WHEN_ENTRY_ID that is not PROPERTY_ID or MODIFIER_LIST)
                var subjectVarName: String? = null
                for (child in FrameAstHelpers.childFrames(stream, frame)) {
                    if (child.nodeTypeId == KtNodeTypes.WHEN_ENTRY_ID) break
                    if (child.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                        subjectVarName = FrameAstHelpers.directIdentPacket(stream, child)?.unescapedName
                        walkFrame(child, inValuePosition = true, enclosingStmtFrameIndex = enclosingStmtFrameIndex, ctx = ctx)
                    } else if (child.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        subjectVarName = FrameAstHelpers.directIdentPacket(stream, child)?.unescapedName
                    } else if (child.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID) {
                        subjectVarName = "this"
                    } else if (child.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID) {
                        frame.flags = frame.flags or PacketFlags.SINGLE_EVAL_SUBJECT_REQUIRED
                        val subjTemp = "kxs_subj${ctx.nextTempIndex()}"
                        ctx.singleEvalTemps[frame.frameIndex] = subjTemp
                        subjectVarName = subjTemp
                        walkFrame(child, inValuePosition = true, enclosingStmtFrameIndex = enclosingStmtFrameIndex, ctx = ctx)
                    }
                }

                for (entry in FrameAstHelpers.childFramesOfType(stream, frame, KtNodeTypes.WHEN_ENTRY_ID)) {
                    val entryCondCount =
                        FrameAstHelpers.childFrames(stream, entry).count { child ->
                            child.nodeTypeId == KtNodeTypes.WHEN_CONDITION_IS_PATTERN_ID ||
                                child.nodeTypeId == KtNodeTypes.WHEN_CONDITION_IN_RANGE_ID ||
                                child.nodeTypeId == KtNodeTypes.WHEN_CONDITION_EXPRESSION_ID
                        }
                    if (entryCondCount != 1) continue
                    for (isPat in FrameAstHelpers.childFramesOfType(stream, entry, KtNodeTypes.WHEN_CONDITION_IS_PATTERN_ID)) {
                        val tr = FrameAstHelpers.firstChildFrame(stream, isPat, KtNodeTypes.TYPE_REFERENCE_ID)
                        val negated = FrameAstHelpers.hasDirectKeyword(stream, isPat, KtTokens.NOT_IS_ID)
                        if (tr != null && subjectVarName != null && !negated) {
                            isPat.flags = isPat.flags or PacketFlags.SMART_CAST_SUBTYPE
                            ctx.smartCasts
                                .getOrPut(entry.frameIndex) { ArrayList() }
                                .add(
                                    SmartCastBinding(
                                        scopeFrameIndex = entry.frameIndex,
                                        kotlinVarName = subjectVarName,
                                        narrowedCppName = "${CppNamingPolicy.toSnakeCase(subjectVarName)}_narrowed",
                                        targetTypeFrameIndex = tr.frameIndex,
                                        isNegated = false,
                                    ),
                                )
                        }
                    }
                    if (subjectVarName == null) {
                        val condWrap =
                            FrameAstHelpers.firstChildFrame(stream, entry, KtNodeTypes.WHEN_CONDITION_EXPRESSION_ID)
                        val innerCond = condWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
                        if (innerCond != null) {
                            for (sc in extractConditionSmartCasts(stream, innerCond, whenTrue = true)) {
                                if (!sc.isNegated) {
                                    ctx.smartCasts
                                        .getOrPut(entry.frameIndex) { ArrayList() }
                                        .add(sc.copy(scopeFrameIndex = entry.frameIndex))
                                }
                            }
                        }
                    }
                    val children = FrameAstHelpers.childFrames(stream, entry)
                    val rhs = children.lastOrNull()
                    for (c in children) {
                        val isArmBody = c === rhs
                        walkFrame(
                            frame = c,
                            inValuePosition = isArmBody && inValuePosition,
                            enclosingStmtFrameIndex = enclosingStmtFrameIndex,
                            ctx = ctx,
                        )
                    }
                }
                return
            }

            KtNodeTypes.TRY_ID -> {
                if (inValuePosition) {
                    frame.flags = frame.flags or PacketFlags.EXPRESSION_VALUE_POSITION
                    val needsHoist =
                        PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE) ||
                            FrameAstHelpers.subtreeHasUnhandledControlJump(stream, frame)
                    if (needsHoist && enclosingStmtFrameIndex != frame.frameIndex) {
                        registerHoistedControlFlow(frame, enclosingStmtFrameIndex, ctx)
                    }
                }
                for (child in FrameAstHelpers.childFrames(stream, frame)) {
                    when (child.nodeTypeId) {
                        KtNodeTypes.BLOCK_ID ->
                            walkFrame(
                                frame = child,
                                inValuePosition = inValuePosition,
                                enclosingStmtFrameIndex = enclosingStmtFrameIndex,
                                ctx = ctx,
                            )
                        KtNodeTypes.CATCH_ID -> {
                            val catchBlock = FrameAstHelpers.firstChildFrame(stream, child, KtNodeTypes.BLOCK_ID)
                            if (catchBlock != null) {
                                walkFrame(
                                    frame = catchBlock,
                                    inValuePosition = inValuePosition,
                                    enclosingStmtFrameIndex = enclosingStmtFrameIndex,
                                    ctx = ctx,
                                )
                            }
                        }
                        KtNodeTypes.FINALLY_ID -> {
                            val finBlock = FrameAstHelpers.firstChildFrame(stream, child, KtNodeTypes.BLOCK_ID)
                            if (finBlock != null) {
                                walkFrame(finBlock, inValuePosition = false, enclosingStmtFrameIndex = enclosingStmtFrameIndex, ctx = ctx)
                            }
                        }
                        else -> Unit
                    }
                }
                return
            }

            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID -> {
                val parts = FrameAstHelpers.childFrames(stream, frame)
                if (parts.isNotEmpty() && parts[0].nodeTypeId != KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                    frame.flags = frame.flags or PacketFlags.SINGLE_EVAL_SUBJECT_REQUIRED
                    ctx.singleEvalTemps[frame.frameIndex] = "kxs_safe${ctx.nextTempIndex()}"
                }
            }

            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val children = FrameAstHelpers.childFrames(stream, frame)
                if (children.size >= 3) {
                    val opId = FrameAstHelpers.operationTokenId(stream, children[1])
                    if (opId == KtTokens.ELVIS_ID && children[0].nodeTypeId != KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        frame.flags = frame.flags or PacketFlags.SINGLE_EVAL_SUBJECT_REQUIRED
                        ctx.singleEvalTemps[frame.frameIndex] = "kxs_elvis${ctx.nextTempIndex()}"
                    }
                    val isAssign = opId in ASSIGN_OPERATOR_IDS
                    val rhsValPos = inValuePosition || isAssign || opId == KtTokens.ELVIS_ID
                    walkFrame(
                        frame = children[0],
                        inValuePosition = !isAssign,
                        enclosingStmtFrameIndex = enclosingStmtFrameIndex,
                        ctx = ctx,
                    )
                    walkFrame(children[2], inValuePosition = rhsValPos, enclosingStmtFrameIndex = enclosingStmtFrameIndex, ctx = ctx)
                    return
                }
            }

            KtNodeTypes.CALL_EXPRESSION_ID -> {
                val calleeRef = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                val callee = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()
                if (callee in ctx.nothingFunNames) {
                    frame.flags = frame.flags or PacketFlags.DIVERGING_EXPR
                }
                if (callee in RESULT_API_METHODS) {
                    frame.flags = frame.flags or PacketFlags.KOTLIN_RESULT_CHANNEL
                }
            }

            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                val parts = FrameAstHelpers.childFrames(stream, frame)
                if (parts.size == 2) {
                    val recvName =
                        if (parts[0].nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                            FrameAstHelpers.directIdentPacket(stream, parts[0])?.unescapedName
                        } else {
                            null
                        }
                    val selCall =
                        if (parts[1].nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                            FrameAstHelpers
                                .firstChildFrame(stream, parts[1], KtNodeTypes.REFERENCE_EXPRESSION_ID)
                                ?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
                        } else {
                            FrameAstHelpers.directIdentPacket(stream, parts[1])?.unescapedName
                        }
                    if (recvName == "Result" || selCall in RESULT_API_METHODS) {
                        frame.flags = frame.flags or PacketFlags.KOTLIN_RESULT_CHANNEL
                    }
                }
            }

            KtNodeTypes.PROPERTY_ACCESSOR_ID -> {
                val body =
                    FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.BLOCK_ID)
                        ?: FrameAstHelpers.childFrames(stream, frame).lastOrNull {
                            it.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID &&
                                it.nodeTypeId != KtNodeTypes.VALUE_PARAMETER_LIST_ID &&
                                it.nodeTypeId != KtNodeTypes.TYPE_REFERENCE_ID
                        }
                if (body != null) {
                    walkFrame(
                        frame = body,
                        inValuePosition = inValuePosition,
                        enclosingStmtFrameIndex = body.frameIndex,
                        ctx = ctx,
                    )
                }
                return
            }

            else -> Unit
        }

        val childValuePos =
            frame.nodeTypeId == KtNodeTypes.PARENTHESIZED_ID ||
                frame.nodeTypeId == KtNodeTypes.VALUE_ARGUMENT_ID ||
                frame.nodeTypeId == KtNodeTypes.PREFIX_EXPRESSION_ID ||
                frame.nodeTypeId == KtNodeTypes.POSTFIX_EXPRESSION_ID
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            walkFrame(
                frame = child,
                inValuePosition = if (childValuePos) true else inValuePosition && frame.childCount == 1,
                enclosingStmtFrameIndex = enclosingStmtFrameIndex,
                ctx = ctx,
            )
        }
    }

    private fun registerHoistedControlFlow(
        cfFrame: PacketFrame,
        enclosingStmtFrameIndex: Int,
        ctx: CfWalkContext,
    ) {
        if (cfFrame.frameIndex in ctx.hoistedByExpr) return
        val slotName = "kxs_cf${ctx.nextCfIndex()}"
        val entry =
            HoistedControlFlowValue(
                controlFlowFrameIndex = cfFrame.frameIndex,
                enclosingStmtFrameIndex = enclosingStmtFrameIndex,
                slotCppName = slotName,
            )
        ctx.hoistedByExpr[cfFrame.frameIndex] = entry
        ctx.hoistedBeforeStmt.getOrPut(enclosingStmtFrameIndex) { ArrayList() }.add(entry)
    }

    internal fun extractConditionSmartCasts(
        stream: PacketStream,
        expr: PacketFrame,
        whenTrue: Boolean,
    ): List<SmartCastBinding> {
        val out = ArrayList<SmartCastBinding>()
        collectConditionSmartCasts(stream, expr, whenTrue, out)
        return out
    }

    private fun unwrapConditionExpr(
        stream: PacketStream,
        expr: PacketFrame,
    ): PacketFrame {
        var cur = expr
        while (cur.nodeTypeId == KtNodeTypes.PARENTHESIZED_ID) {
            cur = FrameAstHelpers.childFrames(stream, cur).firstOrNull() ?: break
        }
        return cur
    }

    private fun collectConditionSmartCasts(
        stream: PacketStream,
        expr: PacketFrame,
        whenTrue: Boolean,
        out: MutableList<SmartCastBinding>,
    ) {
        val u = unwrapConditionExpr(stream, expr)
        when (u.nodeTypeId) {
            KtNodeTypes.IS_EXPRESSION_ID -> {
                val children = FrameAstHelpers.childFrames(stream, u)
                val lhs = children.firstOrNull()?.let { unwrapConditionExpr(stream, it) } ?: return
                val tr = FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.TYPE_REFERENCE_ID) ?: return
                val opRef = FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.OPERATION_REFERENCE_ID)
                val isNeg = opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.NOT_IS_ID
                if (isNeg == whenTrue) return
                val varName =
                    when (lhs.nodeTypeId) {
                        KtNodeTypes.REFERENCE_EXPRESSION_ID -> FrameAstHelpers.directIdentPacket(stream, lhs)?.unescapedName
                        KtNodeTypes.THIS_EXPRESSION_ID -> "this"
                        else -> null
                    } ?: return
                val narrowedCpp =
                    if (varName == "this") {
                        "this_val_narrowed"
                    } else {
                        "${CppNamingPolicy.toSnakeCase(varName)}_narrowed"
                    }
                if (out.none { it.kotlinVarName == varName && it.targetTypeFrameIndex == tr.frameIndex }) {
                    out.add(
                        SmartCastBinding(
                            scopeFrameIndex = -1,
                            kotlinVarName = varName,
                            narrowedCppName = narrowedCpp,
                            targetTypeFrameIndex = tr.frameIndex,
                            isNegated = false,
                        ),
                    )
                }
            }
            KtNodeTypes.PREFIX_EXPRESSION_ID -> {
                val operand = findNegatedOperand(stream, u) ?: return
                collectConditionSmartCasts(stream, operand, !whenTrue, out)
            }
            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val children = FrameAstHelpers.childFrames(stream, u)
                if (children.size < 3) return
                val opId = FrameAstHelpers.operationTokenId(stream, children[1])
                val shouldRecurseBoth =
                    (whenTrue && opId == KtTokens.ANDAND_ID) ||
                        (!whenTrue && opId == KtTokens.OROR_ID)
                if (shouldRecurseBoth) {
                    collectConditionSmartCasts(stream, children[0], whenTrue, out)
                    collectConditionSmartCasts(stream, children[2], whenTrue, out)
                }
            }
        }
    }

    private fun findNegatedOperand(
        stream: PacketStream,
        prefixFrame: PacketFrame,
    ): PacketFrame? {
        val opRef = FrameAstHelpers.firstChildFrame(stream, prefixFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        return if (opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.EXCL_ID) {
            FrameAstHelpers
                .childFrames(stream, prefixFrame)
                .firstOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
        } else {
            null
        }
    }

    private fun markConditionFlags(
        stream: PacketStream,
        expr: PacketFrame,
        whenTrue: Boolean,
        targetBranch: PacketFrame?,
    ) {
        val u = unwrapConditionExpr(stream, expr)
        when (u.nodeTypeId) {
            KtNodeTypes.IS_EXPRESSION_ID -> {
                val children = FrameAstHelpers.childFrames(stream, u)
                val lhs = children.firstOrNull()?.let { unwrapConditionExpr(stream, it) }
                val tr = FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.TYPE_REFERENCE_ID)
                if (lhs != null &&
                    tr != null &&
                    (
                        lhs.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID ||
                            lhs.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID
                    )
                ) {
                    u.flags = u.flags or PacketFlags.SMART_CAST_SUBTYPE
                }
            }
            KtNodeTypes.PREFIX_EXPRESSION_ID -> {
                val operand = findNegatedOperand(stream, u) ?: return
                markConditionFlags(stream, operand, !whenTrue, targetBranch)
            }
            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val children = FrameAstHelpers.childFrames(stream, u)
                if (children.size < 3) return
                val opId = FrameAstHelpers.operationTokenId(stream, children[1])
                if ((whenTrue && opId == KtTokens.ANDAND_ID) || (!whenTrue && opId == KtTokens.OROR_ID)) {
                    markConditionFlags(stream, children[0], whenTrue, targetBranch)
                    markConditionFlags(stream, children[2], whenTrue, targetBranch)
                    return
                }
                val varSide =
                    when {
                        children[2].nodeTypeId == KtNodeTypes.NULL_ID -> children[0]
                        children[0].nodeTypeId == KtNodeTypes.NULL_ID -> children[2]
                        else -> null
                    }?.let { unwrapConditionExpr(stream, it) }
                if (varSide != null &&
                    varSide.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                    (opId == KtTokens.EXCLEQ_ID || opId == KtTokens.EQEQ_ID)
                ) {
                    u.flags = u.flags or PacketFlags.SMART_CAST_NON_NULL
                    val isNonNullWhenTrue = opId == KtTokens.EXCLEQ_ID
                    if (isNonNullWhenTrue == whenTrue && targetBranch != null) {
                        targetBranch.flags = targetBranch.flags or PacketFlags.SMART_CAST_NON_NULL
                    }
                }
            }
        }
    }

    private fun detectIfConditionSmartCasts(
        stream: PacketStream,
        condWrap: PacketFrame,
        thenWrap: PacketFrame?,
        elseWrap: PacketFrame?,
        ctx: CfWalkContext,
    ) {
        val expr = FrameAstHelpers.childFrames(stream, condWrap).firstOrNull() ?: return
        markConditionFlags(stream, expr, whenTrue = true, targetBranch = thenWrap)
        markConditionFlags(stream, expr, whenTrue = false, targetBranch = elseWrap)
        thenWrap?.let { wrap ->
            val casts = extractConditionSmartCasts(stream, expr, whenTrue = true)
            if (casts.isNotEmpty()) {
                ctx.smartCasts
                    .getOrPut(wrap.frameIndex) { ArrayList() }
                    .addAll(casts.map { it.copy(scopeFrameIndex = wrap.frameIndex) })
            }
        }
        elseWrap?.let { wrap ->
            val casts = extractConditionSmartCasts(stream, expr, whenTrue = false)
            if (casts.isNotEmpty()) {
                ctx.smartCasts
                    .getOrPut(wrap.frameIndex) { ArrayList() }
                    .addAll(casts.map { it.copy(scopeFrameIndex = wrap.frameIndex) })
            }
        }
    }

    private val ASSIGN_OPERATOR_IDS: Set<Int> =
        setOf(
            KtTokens.EQ_ID,
            KtTokens.PLUSEQ_ID,
            KtTokens.MINUSEQ_ID,
            KtTokens.MULTEQ_ID,
            KtTokens.DIVEQ_ID,
            KtTokens.PERCEQ_ID,
        )

    private val BUILTIN_DIVERGING_CALLS: Set<String> =
        setOf("error", "TODO", "noImpl")

    private val RESULT_API_METHODS: Set<String> =
        setOf("runCatching", "getOrThrow", "getOrNull", "getOrDefault", "getOrElse", "exceptionOrNull", "fold")
}
