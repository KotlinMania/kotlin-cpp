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
            val retSlice = FrameAstHelpers.frameSourceSlice(stream, stream.frames[retIdx]).trim()
            if (retSlice == "Nothing") {
                nothingFunNames.add(fn.kotlinName)
            }
        }

        for (fn in symbols.functionsByFrame.values) {
            val bodyIdx = fn.bodyFrameIndex ?: continue
            val bodyFrame = stream.frames[bodyIdx]
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
            walkFrame(
                frame = bodyFrame,
                inValuePosition = fn.isExpressionBody,
                enclosingStmtFrameIndex = bodyFrame.frameIndex,
                ctx = ctx,
            )
        }

        for (prop in symbols.propertiesByFrame.values) {
            prop.initializerFrameIndex?.let { idx ->
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
                walkFrame(stream.frames[idx], inValuePosition = true, enclosingStmtFrameIndex = idx, ctx = ctx)
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
                    if (PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE) &&
                        enclosingStmtFrameIndex != frame.frameIndex
                    ) {
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
                    if (PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE) &&
                        enclosingStmtFrameIndex != frame.frameIndex
                    ) {
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
                    } else if (child.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID) {
                        frame.flags = frame.flags or PacketFlags.SINGLE_EVAL_SUBJECT_REQUIRED
                        val subjTemp = "kxs_subj${ctx.nextTempIndex()}"
                        ctx.singleEvalTemps[frame.frameIndex] = subjTemp
                        subjectVarName = subjTemp
                        walkFrame(child, inValuePosition = true, enclosingStmtFrameIndex = enclosingStmtFrameIndex, ctx = ctx)
                    }
                }

                for (entry in FrameAstHelpers.childFramesOfType(stream, frame, KtNodeTypes.WHEN_ENTRY_ID)) {
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
                    if (PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE) &&
                        enclosingStmtFrameIndex != frame.frameIndex
                    ) {
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
                    val op = FrameAstHelpers.frameSourceSlice(stream, children[1]).trim()
                    if (op == "?:" && children[0].nodeTypeId != KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        frame.flags = frame.flags or PacketFlags.SINGLE_EVAL_SUBJECT_REQUIRED
                        ctx.singleEvalTemps[frame.frameIndex] = "kxs_elvis${ctx.nextTempIndex()}"
                    }
                    val rhsValPos = inValuePosition || op in ASSIGN_OPERATORS || op == "?:"
                    walkFrame(
                        frame = children[0],
                        inValuePosition = op !in ASSIGN_OPERATORS,
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
                    val recvText = FrameAstHelpers.frameSourceSlice(stream, parts[0]).trim()
                    val selCall =
                        if (parts[1].nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                            FrameAstHelpers.firstChildFrame(stream, parts[1], KtNodeTypes.REFERENCE_EXPRESSION_ID)
                                ?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }
                        } else {
                            FrameAstHelpers.directIdentPacket(stream, parts[1])?.unescapedName
                        }
                    if (recvText == "Result" || selCall in RESULT_API_METHODS) {
                        frame.flags = frame.flags or PacketFlags.KOTLIN_RESULT_CHANNEL
                    }
                }
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

    private fun detectIfConditionSmartCasts(
        stream: PacketStream,
        condWrap: PacketFrame,
        thenWrap: PacketFrame?,
        elseWrap: PacketFrame?,
        ctx: CfWalkContext,
    ) {
        val expr = FrameAstHelpers.childFrames(stream, condWrap).firstOrNull() ?: return
        if (expr.nodeTypeId == KtNodeTypes.IS_EXPRESSION_ID) {
            val children = FrameAstHelpers.childFrames(stream, expr)
            val lhs = children.firstOrNull()
            val tr = FrameAstHelpers.firstChildFrame(stream, expr, KtNodeTypes.TYPE_REFERENCE_ID)
            val opRef = FrameAstHelpers.firstChildFrame(stream, expr, KtNodeTypes.OPERATION_REFERENCE_ID)
            val opSlice = opRef?.let { FrameAstHelpers.frameSourceSlice(stream, it).trim() }.orEmpty()
            val isNeg = opSlice == "!is"
            if (lhs != null && lhs.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID && tr != null) {
                val varName = FrameAstHelpers.directIdentPacket(stream, lhs)?.unescapedName ?: return
                expr.flags = expr.flags or PacketFlags.SMART_CAST_SUBTYPE
                val targetBranch = if (!isNeg) thenWrap else elseWrap
                if (targetBranch != null) {
                    ctx.smartCasts
                        .getOrPut(targetBranch.frameIndex) { ArrayList() }
                        .add(
                            SmartCastBinding(
                                scopeFrameIndex = targetBranch.frameIndex,
                                kotlinVarName = varName,
                                narrowedCppName = "${CppNamingPolicy.toSnakeCase(varName)}_narrowed",
                                targetTypeFrameIndex = tr.frameIndex,
                                isNegated = false,
                            ),
                        )
                }
            }
        } else if (expr.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val children = FrameAstHelpers.childFrames(stream, expr)
            if (children.size >= 3) {
                val op = FrameAstHelpers.frameSourceSlice(stream, children[1]).trim()
                val lhsIsNull = children[0].nodeTypeId == KtNodeTypes.NULL_ID
                val rhsIsNull = children[2].nodeTypeId == KtNodeTypes.NULL_ID
                val varSide = if (rhsIsNull) children[0] else if (lhsIsNull) children[2] else null
                if (varSide != null && varSide.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID && (op == "!=" || op == "==")) {
                    expr.flags = expr.flags or PacketFlags.SMART_CAST_NON_NULL
                    val targetBranch = if (op == "!=") thenWrap else elseWrap
                    if (targetBranch != null) {
                        targetBranch.flags = targetBranch.flags or PacketFlags.SMART_CAST_NON_NULL
                    }
                }
            }
        }
    }

    private val ASSIGN_OPERATORS: Set<String> =
        setOf("=", "+=", "-=", "*=", "/=", "%=")

    private val BUILTIN_DIVERGING_CALLS: Set<String> =
        setOf("error", "TODO")

    private val RESULT_API_METHODS: Set<String> =
        setOf("runCatching", "getOrThrow", "getOrNull", "getOrDefault", "getOrElse", "exceptionOrNull", "fold")
}
