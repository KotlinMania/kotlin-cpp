package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.FrameSemanticInfo
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream

internal data class HoistedEvalTemp(
    val tempCppName: String,
    val exprFrameIndex: Int,
    val callOrBinaryFrameIndex: Int,
)

internal enum class BuiltinInlineKind {
    LET,
    ALSO,
    RUN,
    APPLY,
    WITH,
    USE,
    REPEAT,
    WITH_LOCK,
    FOR_EACH,
    SAME_FILE_INLINE_FUN,
}

internal data class InlineCallExpansion(
    val callFrameIndex: Int,
    val kind: BuiltinInlineKind,
    val receiverExprFrameIndex: Int?,
    val argExprFrameIndices: List<Int>,
    val lambdaFrameIndex: Int,
    val lambdaParamNames: List<String>,
    val targetInlineFunFrameIndex: Int?,
)

internal data class LeafContinuationIntrinsic(
    val funFrameIndex: Int,
    val uContParamKotlinName: String,
    val lambdaBodyFrameIndex: Int,
    val isBoxedResult: Boolean,
)

internal class SuspendLoweringPlan(
    val suspendCallFrameIndices: Set<Int>,
    val hoistedTempByExprFrame: Map<Int, HoistedEvalTemp>,
    val hoistedTempsBeforeStmtFrame: Map<Int, List<HoistedEvalTemp>>,
    val inlineExpansionsByCallFrame: Map<Int, InlineCallExpansion>,
    val leafIntrinsicsByFunFrame: Map<Int, LeafContinuationIntrinsic>,
    val totalHoistedSuspendArgs: Int,
)

/**
 * Pass 2: Coroutine `suspend` context legality (`FirSuspendCallChecker` rules),
 * leaf `suspendCoroutineUninterceptedOrReturn` dual-ABI intrinsic detection,
 * inline suspending/non-local-return lambda expansion planning, and
 * `hasSuspendCallInTail` left-to-right evaluation-order hoisting (`kxs_argN`).
 */
internal object SuspendStateMachinePass {
    fun run(
        stream: PacketStream,
        symbols: SameFileSymbolTable,
    ): SuspendLoweringPlan {
        val suspendCallFrames = LinkedHashSet<Int>()
        val hoistedByExpr = LinkedHashMap<Int, HoistedEvalTemp>()
        val hoistedBeforeStmt = LinkedHashMap<Int, MutableList<HoistedEvalTemp>>()
        val inlineExpansions = LinkedHashMap<Int, InlineCallExpansion>()
        val leafIntrinsics = LinkedHashMap<Int, LeafContinuationIntrinsic>()
        var hoistCounter = 0

        val sameFileSuspendFuns =
            symbols.functionsByFrame.values
                .filter { PacketFlags.hasFlag(it.flags, PacketFlags.MOD_SUSPEND) }
                .map { it.kotlinName }
                .toSet()

        val sameFileInlineFunsByName =
            symbols.functionsByFrame.values
                .filter { PacketFlags.hasFlag(it.flags, PacketFlags.MOD_INLINE) }
                .associateBy { it.kotlinName }

        // 1. Inspect every function & property body to validate suspend legality, detect leaf intrinsics,
        //    propagate HAS_SUSPEND_IN_SUBTREE, plan inline expansions, and hoist evaluation-order args.
        for (fnSym in symbols.functionsByFrame.values) {
            val fnFrame = stream.frames[fnSym.frameIndex]
            val isSuspendFn = PacketFlags.hasFlag(fnSym.flags, PacketFlags.MOD_SUSPEND)
            val restrictedReceiver = resolveRestrictedReceiverName(stream, fnSym, symbols)
            if (restrictedReceiver != null) {
                fnFrame.flags = fnFrame.flags or PacketFlags.RESTRICTS_SUSPENSION_SCOPE
            }

            val suspendCallableParams = collectSuspendCallableParamNames(stream, fnSym)
            val restrictedMemberNames =
                if (restrictedReceiver != null) {
                    collectRestrictedMemberNames(symbols, restrictedReceiver)
                } else {
                    emptySet()
                }

            val bodyIdx = fnSym.bodyFrameIndex ?: continue
            val bodyFrame = stream.frames[bodyIdx]

            // Detect leaf `suspendCoroutineUninterceptedOrReturn { uCont -> ... }` pattern (e.g. `Yield.kt`).
            if (isSuspendFn) {
                detectLeafContinuationIntrinsic(stream, fnSym, bodyFrame)?.let { leaf ->
                    var updatedFlags = fnFrame.flags or PacketFlags.IMPLICIT_CONTINUATION_INTRINSIC
                    if (leaf.isBoxedResult) {
                        updatedFlags = updatedFlags or PacketFlags.BOXED_RESULT_INTRINSIC
                    }
                    fnFrame.flags = updatedFlags
                    leafIntrinsics[fnSym.frameIndex] = leaf
                }
            }

            val ctx =
                SuspendAnalysisContext(
                    stream = stream,
                    symbols = symbols,
                    sameFileSuspendFuns = sameFileSuspendFuns,
                    sameFileInlineFunsByName = sameFileInlineFunsByName,
                    suspendCallableVars = suspendCallableParams,
                    enclosingFunctionIsSuspend = isSuspendFn,
                    lambdaSuspendAllowed = isSuspendFn,
                    inCrossinlineLambda = false,
                    inNonInlineOrdinaryLambda = false,
                    restrictedReceiverType = restrictedReceiver,
                    restrictedAllowedCalls = restrictedMemberNames,
                    enclosingFnName = fnSym.kotlinName,
                    suspendCallFrames = suspendCallFrames,
                    hoistedByExpr = hoistedByExpr,
                    hoistedBeforeStmt = hoistedBeforeStmt,
                    inlineExpansions = inlineExpansions,
                    nextHoistIndex = { hoistCounter++ },
                )
            val bodyHasSuspend = analyzeSubtree(bodyFrame, enclosingStmtFrameIndex = bodyFrame.frameIndex, ctx = ctx)
            if (bodyHasSuspend) {
                fnFrame.flags = fnFrame.flags or PacketFlags.HAS_SUSPEND_IN_SUBTREE
            }
        }

        // Also check top-level/class property initializers & accessors so illegal suspend calls in initializers fail loud.
        for (propSym in symbols.propertiesByFrame.values) {
            val framesToCheck = listOfNotNull(propSym.initializerFrameIndex, propSym.getterFrameIndex, propSym.setterFrameIndex)
            for (fIdx in framesToCheck) {
                val ctx =
                    SuspendAnalysisContext(
                        stream = stream,
                        symbols = symbols,
                        sameFileSuspendFuns = sameFileSuspendFuns,
                        sameFileInlineFunsByName = sameFileInlineFunsByName,
                        suspendCallableVars = emptySet(),
                        enclosingFunctionIsSuspend = false,
                        lambdaSuspendAllowed = false,
                        inCrossinlineLambda = false,
                        inNonInlineOrdinaryLambda = false,
                        restrictedReceiverType = null,
                        restrictedAllowedCalls = emptySet(),
                        enclosingFnName = propSym.kotlinName,
                        suspendCallFrames = suspendCallFrames,
                        hoistedByExpr = hoistedByExpr,
                        hoistedBeforeStmt = hoistedBeforeStmt,
                        inlineExpansions = inlineExpansions,
                        nextHoistIndex = { hoistCounter++ },
                    )
                analyzeSubtree(stream.frames[fIdx], enclosingStmtFrameIndex = fIdx, ctx = ctx)
            }
        }

        return SuspendLoweringPlan(
            suspendCallFrameIndices = suspendCallFrames,
            hoistedTempByExprFrame = hoistedByExpr,
            hoistedTempsBeforeStmtFrame = hoistedBeforeStmt,
            inlineExpansionsByCallFrame = inlineExpansions,
            leafIntrinsicsByFunFrame = leafIntrinsics,
            totalHoistedSuspendArgs = hoistedByExpr.size,
        )
    }

    private class SuspendAnalysisContext(
        val stream: PacketStream,
        val symbols: SameFileSymbolTable,
        val sameFileSuspendFuns: Set<String>,
        val sameFileInlineFunsByName: Map<String, KtFunctionSymbol>,
        val suspendCallableVars: Set<String>,
        val enclosingFunctionIsSuspend: Boolean,
        val lambdaSuspendAllowed: Boolean,
        val inCrossinlineLambda: Boolean,
        val inNonInlineOrdinaryLambda: Boolean,
        val restrictedReceiverType: String?,
        val restrictedAllowedCalls: Set<String>,
        val enclosingFnName: String,
        val suspendCallFrames: MutableSet<Int>,
        val hoistedByExpr: MutableMap<Int, HoistedEvalTemp>,
        val hoistedBeforeStmt: MutableMap<Int, MutableList<HoistedEvalTemp>>,
        val inlineExpansions: MutableMap<Int, InlineCallExpansion>,
        val nextHoistIndex: () -> Int,
    ) {
        fun copyForLambda(
            allowSuspend: Boolean,
            crossinline: Boolean,
            nonInlineOrdinary: Boolean,
            restrictedType: String?,
            restrictedMembers: Set<String>,
            extraSuspendVars: Set<String> = emptySet(),
        ): SuspendAnalysisContext {
            val mergedVars =
                if (extraSuspendVars.isEmpty()) {
                    suspendCallableVars
                } else {
                    val out = LinkedHashSet<String>(suspendCallableVars.size + extraSuspendVars.size)
                    out.addAll(suspendCallableVars)
                    out.addAll(extraSuspendVars)
                    out
                }
            return SuspendAnalysisContext(
                stream = stream,
                symbols = symbols,
                sameFileSuspendFuns = sameFileSuspendFuns,
                sameFileInlineFunsByName = sameFileInlineFunsByName,
                suspendCallableVars = mergedVars,
                enclosingFunctionIsSuspend = enclosingFunctionIsSuspend || allowSuspend,
                lambdaSuspendAllowed = allowSuspend,
                inCrossinlineLambda = crossinline,
                inNonInlineOrdinaryLambda = nonInlineOrdinary,
                restrictedReceiverType = restrictedType ?: restrictedReceiverType,
                restrictedAllowedCalls =
                    if (restrictedType != null) restrictedMembers else restrictedAllowedCalls,
                enclosingFnName = enclosingFnName,
                suspendCallFrames = suspendCallFrames,
                hoistedByExpr = hoistedByExpr,
                hoistedBeforeStmt = hoistedBeforeStmt,
                inlineExpansions = inlineExpansions,
                nextHoistIndex = nextHoistIndex,
            )
        }
    }

    private fun analyzeSubtree(
        frame: PacketFrame,
        enclosingStmtFrameIndex: Int,
        ctx: SuspendAnalysisContext,
    ): Boolean {
        val stream = ctx.stream
        when (frame.nodeTypeId) {
            KtNodeTypes.FUN_ID,
            KtNodeTypes.CLASS_ID,
            KtNodeTypes.OBJECT_DECLARATION_ID,
            -> {
                // Nested local declarations have their own independent suspension context; do not leak into outer frame.
                return false
            }

            KtNodeTypes.BLOCK_ID -> {
                var anySuspend = false
                for (child in FrameAstHelpers.childFrames(stream, frame)) {
                    val childHas = analyzeSubtree(child, enclosingStmtFrameIndex = child.frameIndex, ctx = ctx)
                    if (childHas) anySuspend = true
                }
                if (anySuspend) {
                    frame.flags = frame.flags or PacketFlags.HAS_SUSPEND_IN_SUBTREE
                }
                return anySuspend
            }

            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID,
            KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID,
            -> {
                val children = FrameAstHelpers.childFrames(stream, frame)
                if (children.size == 2 && children[1].nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                    return analyzeCallSite(
                        outerFrame = frame,
                        receiverFrame = children[0],
                        callFrame = children[1],
                        enclosingStmtFrameIndex = enclosingStmtFrameIndex,
                        ctx = ctx,
                    )
                }
            }

            KtNodeTypes.CALL_EXPRESSION_ID -> {
                return analyzeCallSite(
                    outerFrame = frame,
                    receiverFrame = null,
                    callFrame = frame,
                    enclosingStmtFrameIndex = enclosingStmtFrameIndex,
                    ctx = ctx,
                )
            }

            KtNodeTypes.BINARY_EXPRESSION_ID -> {
                val children = FrameAstHelpers.childFrames(stream, frame)
                // Expected children: [lhs, OPERATION_REFERENCE, rhs]
                if (children.size >= 3) {
                    val lhs = children[0]
                    val opRef = children[1]
                    val rhs = children[2]
                    val opTokenText = FrameAstHelpers.frameSourceSlice(stream, opRef).trim()
                    val isInfixSuspend = opTokenText in ctx.sameFileSuspendFuns
                    val lhsHas = analyzeSubtree(lhs, enclosingStmtFrameIndex, ctx)
                    val rhsHas = analyzeSubtree(rhs, enclosingStmtFrameIndex, ctx)

                    val isShortCircuitOrAssign =
                        opTokenText in SHORT_CIRCUIT_AND_ASSIGN_OPS
                    if (!isShortCircuitOrAssign && rhsHas && isHoistableExpression(stream, lhs)) {
                        hoistExpression(lhs, frame.frameIndex, enclosingStmtFrameIndex, ctx)
                    }
                    if (isInfixSuspend) {
                        validateSuspendCallLegality(frame, opTokenText, hasExplicitAllowedReceiver = false, ctx = ctx)
                        frame.flags = frame.flags or PacketFlags.SUSPEND_CALL_SITE or PacketFlags.HAS_SUSPEND_IN_SUBTREE
                        ctx.suspendCallFrames.add(frame.frameIndex)
                        return true
                    }
                    val any = lhsHas || rhsHas
                    if (any) {
                        frame.flags = frame.flags or PacketFlags.HAS_SUSPEND_IN_SUBTREE
                    }
                    return any
                }
            }

            KtNodeTypes.LAMBDA_EXPRESSION_ID -> {
                // Standalone lambda expression not already classified by a call argument (e.g. `val fn = { ... }`):
                // an ordinary Kotlin lambda value is NOT a suspend context unless typed as `suspend (...) -> R`.
                val ordinaryCtx =
                    ctx.copyForLambda(
                        allowSuspend = false,
                        crossinline = false,
                        nonInlineOrdinary = true,
                        restrictedType = null,
                        restrictedMembers = emptySet(),
                    )
                for (child in FrameAstHelpers.childFrames(stream, frame)) {
                    analyzeSubtree(child, enclosingStmtFrameIndex = child.frameIndex, ctx = ordinaryCtx)
                }
                return false
            }

            else -> Unit
        }

        var subtreeHasSuspend = false
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            if (analyzeSubtree(child, enclosingStmtFrameIndex, ctx)) {
                subtreeHasSuspend = true
            }
        }
        if (subtreeHasSuspend) {
            frame.flags = frame.flags or PacketFlags.HAS_SUSPEND_IN_SUBTREE
        }
        return subtreeHasSuspend
    }

    private fun analyzeCallSite(
        outerFrame: PacketFrame,
        receiverFrame: PacketFrame?,
        callFrame: PacketFrame,
        enclosingStmtFrameIndex: Int,
        ctx: SuspendAnalysisContext,
    ): Boolean {
        val stream = ctx.stream
        val calleeName = extractCalleeSimpleName(stream, callFrame)
        val sameFileInlineFn = ctx.sameFileInlineFunsByName[calleeName]
        val builtinInlineKind = BUILTIN_INLINE_HOFS[calleeName]
        val sameFileTargetFn = ctx.symbols.functionsByName[calleeName]?.firstOrNull()

        // Ordered evaluation list: [receiver?, valueArg_0, valueArg_1, ..., trailingLambda?]
        val evalExprFrames = ArrayList<PacketFrame>()
        val evalHasSuspend = ArrayList<Boolean>()

        if (receiverFrame != null) {
            val rHas = analyzeSubtree(receiverFrame, enclosingStmtFrameIndex, ctx)
            evalExprFrames.add(receiverFrame)
            evalHasSuspend.add(rHas)
        }

        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
        val valueArgFrames =
            if (argList != null) {
                FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID)
            } else {
                emptyList()
            }
        val trailingLambdaArgs = FrameAstHelpers.childFramesOfType(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID)

        val argExprsForInline = ArrayList<Int>()
        var capturedInlineLambdaFrame: PacketFrame? = null
        var inlineLambdaHasSuspendOrNonLocalReturn = false

        for ((argIdx, vaFrame) in valueArgFrames.withIndex()) {
            val innerExpr = extractValueArgumentExpr(stream, vaFrame) ?: continue
            argExprsForInline.add(innerExpr.frameIndex)
            if (innerExpr.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID) {
                val lambdaResult =
                    analyzeCallLambdaArgument(
                        calleeName = calleeName,
                        paramIndex = argIdx,
                        lambdaFrame = innerExpr,
                        sameFileInlineFn = sameFileInlineFn,
                        sameFileTargetFn = sameFileTargetFn,
                        builtinInlineKind = builtinInlineKind,
                        ctx = ctx,
                    )
                if (lambdaResult.inlinedIntoCaller) {
                    capturedInlineLambdaFrame = innerExpr
                    if (lambdaResult.hasSuspend || lambdaResult.hasNonLocalReturn) {
                        inlineLambdaHasSuspendOrNonLocalReturn = true
                    }
                    evalExprFrames.add(innerExpr)
                    evalHasSuspend.add(lambdaResult.hasSuspend)
                } else {
                    evalExprFrames.add(innerExpr)
                    evalHasSuspend.add(false)
                }
            } else {
                val aHas = analyzeSubtree(innerExpr, enclosingStmtFrameIndex, ctx)
                evalExprFrames.add(innerExpr)
                evalHasSuspend.add(aHas)
            }
        }

        for ((tIdx, tlArg) in trailingLambdaArgs.withIndex()) {
            val lambdaFrame = FrameAstHelpers.firstChildFrame(stream, tlArg, KtNodeTypes.LAMBDA_EXPRESSION_ID) ?: continue
            val paramIdx = valueArgFrames.size + tIdx
            val lambdaResult =
                analyzeCallLambdaArgument(
                    calleeName = calleeName,
                    paramIndex = paramIdx,
                    lambdaFrame = lambdaFrame,
                    sameFileInlineFn = sameFileInlineFn,
                    sameFileTargetFn = sameFileTargetFn,
                    builtinInlineKind = builtinInlineKind,
                    ctx = ctx,
                )
            if (lambdaResult.inlinedIntoCaller) {
                capturedInlineLambdaFrame = lambdaFrame
                if (lambdaResult.hasSuspend || lambdaResult.hasNonLocalReturn) {
                    inlineLambdaHasSuspendOrNonLocalReturn = true
                }
                evalExprFrames.add(lambdaFrame)
                evalHasSuspend.add(lambdaResult.hasSuspend)
            } else {
                evalExprFrames.add(lambdaFrame)
                evalHasSuspend.add(false)
            }
        }

        // Apply `hasSuspendCallInTail`: if any non-lambda argument j > i has suspend in subtree,
        // hoist non-trivial e_i (inline lambda bodies execute inside the call after arguments are sequenced).
        for (i in evalExprFrames.indices) {
            val exprI = evalExprFrames[i]
            if (exprI.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID) continue
            val hasSuspendInTail =
                (i + 1 until evalExprFrames.size).any { j ->
                    evalExprFrames[j].nodeTypeId != KtNodeTypes.LAMBDA_EXPRESSION_ID && evalHasSuspend[j]
                }
            if (hasSuspendInTail && isHoistableExpression(stream, exprI)) {
                hoistExpression(exprI, outerFrame.frameIndex, enclosingStmtFrameIndex, ctx)
            }
        }

        val isDirectSuspendCall =
            calleeName in ctx.sameFileSuspendFuns ||
                calleeName in ctx.suspendCallableVars ||
                calleeName in KNOWN_SUSPEND_PRIMITIVES ||
                (
                    calleeName == "invoke" && receiverFrame != null &&
                        extractSimpleRefName(stream, receiverFrame) in ctx.suspendCallableVars
                )

        if (isDirectSuspendCall) {
            val explicitAllowedOnRestricted =
                ctx.restrictedReceiverType != null &&
                    calleeName in ctx.restrictedAllowedCalls &&
                    (receiverFrame == null || isThisReceiver(stream, receiverFrame))
            validateSuspendCallLegality(
                callSiteFrame = outerFrame,
                calleeName = calleeName,
                hasExplicitAllowedReceiver = explicitAllowedOnRestricted,
                ctx = ctx,
            )
            callFrame.flags = callFrame.flags or PacketFlags.SUSPEND_CALL_SITE or PacketFlags.HAS_SUSPEND_IN_SUBTREE
            outerFrame.flags = outerFrame.flags or PacketFlags.SUSPEND_CALL_SITE or PacketFlags.HAS_SUSPEND_IN_SUBTREE
            ctx.suspendCallFrames.add(callFrame.frameIndex)
            ctx.suspendCallFrames.add(outerFrame.frameIndex)
        }

        // Record inline call expansion when a builtin or same-file `inline fun` has a lambda with suspend or non-local return.
        if (capturedInlineLambdaFrame != null && inlineLambdaHasSuspendOrNonLocalReturn) {
            val kind = builtinInlineKind ?: if (sameFileInlineFn != null) BuiltinInlineKind.SAME_FILE_INLINE_FUN else null
            if (kind != null) {
                val lambdaParams = extractLambdaParamNames(stream, capturedInlineLambdaFrame)
                ctx.inlineExpansions[outerFrame.frameIndex] =
                    InlineCallExpansion(
                        callFrameIndex = outerFrame.frameIndex,
                        kind = kind,
                        receiverExprFrameIndex = receiverFrame?.frameIndex,
                        argExprFrameIndices = argExprsForInline,
                        lambdaFrameIndex = capturedInlineLambdaFrame.frameIndex,
                        lambdaParamNames = lambdaParams,
                        targetInlineFunFrameIndex = sameFileInlineFn?.frameIndex,
                    )
            }
        }

        val anySuspend = isDirectSuspendCall || evalHasSuspend.any { it }
        if (anySuspend) {
            callFrame.flags = callFrame.flags or PacketFlags.HAS_SUSPEND_IN_SUBTREE
            outerFrame.flags = outerFrame.flags or PacketFlags.HAS_SUSPEND_IN_SUBTREE
        }
        return anySuspend
    }

    private data class LambdaCallCheckResult(
        val inlinedIntoCaller: Boolean,
        val hasSuspend: Boolean,
        val hasNonLocalReturn: Boolean,
    )

    private fun analyzeCallLambdaArgument(
        calleeName: String,
        paramIndex: Int,
        lambdaFrame: PacketFrame,
        sameFileInlineFn: KtFunctionSymbol?,
        sameFileTargetFn: KtFunctionSymbol?,
        builtinInlineKind: BuiltinInlineKind?,
        ctx: SuspendAnalysisContext,
    ): LambdaCallCheckResult {
        val stream = ctx.stream
        val paramInfo =
            sameFileInlineFn?.valueParams?.getOrNull(paramIndex)
                ?: sameFileTargetFn?.valueParams?.getOrNull(paramIndex)

        val isCrossinline = paramInfo?.isCrossinline == true
        val isNoinline = paramInfo?.isNoinline == true
        val isInlinedPlace =
            !isNoinline &&
                (builtinInlineKind != null || sameFileInlineFn != null || calleeName in KNOWN_INLINE_COROUTINE_BUILDERS)

        val isSuspendLambdaParam =
            calleeName in KNOWN_SUSPEND_LAMBDA_BUILDERS ||
                (paramInfo?.typeFrameIndex != null && isSuspendFunctionTypeFrame(stream, stream.frames[paramInfo.typeFrameIndex]))

        val restrictedLambdaReceiver =
            when {
                calleeName == "sequence" || calleeName == "iterator" -> "SequenceScope"
                paramInfo?.typeFrameIndex != null ->
                    extractFunctionReceiverTypeName(stream, stream.frames[paramInfo.typeFrameIndex])
                        ?.takeIf { it in ctx.symbols.restrictsSuspensionTypeNames }
                else -> null
            }
        val restrictedMembers =
            if (restrictedLambdaReceiver != null) {
                collectRestrictedMemberNames(ctx.symbols, restrictedLambdaReceiver)
            } else {
                emptySet()
            }

        val allowSuspendInsideLambda =
            when {
                isCrossinline -> false
                isSuspendLambdaParam -> true
                isInlinedPlace -> ctx.lambdaSuspendAllowed
                else -> false
            }

        val childCtx =
            ctx.copyForLambda(
                allowSuspend = allowSuspendInsideLambda,
                crossinline = isCrossinline,
                nonInlineOrdinary = !isInlinedPlace && !isSuspendLambdaParam,
                restrictedType = restrictedLambdaReceiver,
                restrictedMembers = restrictedMembers,
            )

        val fnLiteral = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
        val bodyBlock =
            if (fnLiteral != null) {
                FrameAstHelpers.firstChildFrame(stream, fnLiteral, KtNodeTypes.BLOCK_ID) ?: fnLiteral
            } else {
                lambdaFrame
            }

        val hasSuspend = analyzeSubtree(bodyBlock, enclosingStmtFrameIndex = bodyBlock.frameIndex, ctx = childCtx)
        val hasNonLocalReturn = isInlinedPlace && containsUnlabeledOrOuterReturn(stream, bodyBlock, calleeName)
        if (hasSuspend && isInlinedPlace) {
            lambdaFrame.flags = lambdaFrame.flags or PacketFlags.HAS_SUSPEND_IN_SUBTREE
        }
        return LambdaCallCheckResult(
            inlinedIntoCaller = isInlinedPlace,
            hasSuspend = hasSuspend,
            hasNonLocalReturn = hasNonLocalReturn,
        )
    }

    private fun validateSuspendCallLegality(
        callSiteFrame: PacketFrame,
        calleeName: String,
        hasExplicitAllowedReceiver: Boolean,
        ctx: SuspendAnalysisContext,
    ) {
        val loc = "${ctx.stream.sourcePath}:${callSiteFrame.span.startLine}:${callSiteFrame.span.startColumn}"
        if (ctx.inCrossinlineLambda) {
            throw IllegalArgumentException(
                "$loc: suspend call '$calleeName' is forbidden inside a crossinline lambda in '${ctx.enclosingFnName}'",
            )
        }
        if (ctx.inNonInlineOrdinaryLambda) {
            throw IllegalArgumentException(
                "$loc: suspend call '$calleeName' is illegal inside a non-suspending non-inline lambda in '${ctx.enclosingFnName}'",
            )
        }
        if (!ctx.lambdaSuspendAllowed) {
            throw IllegalArgumentException(
                "$loc: suspend call '$calleeName' must be called only from a coroutine " +
                    "or another suspend function (in '${ctx.enclosingFnName}')",
            )
        }
        val restricted = ctx.restrictedReceiverType
        if (restricted != null && !hasExplicitAllowedReceiver) {
            throw IllegalArgumentException(
                "$loc: @RestrictsSuspension forbids calling external suspend function '$calleeName' inside restricted scope '$restricted'",
            )
        }
    }

    private fun hoistExpression(
        exprFrame: PacketFrame,
        ownerCallOrBinaryFrameIndex: Int,
        enclosingStmtFrameIndex: Int,
        ctx: SuspendAnalysisContext,
    ) {
        if (exprFrame.frameIndex in ctx.hoistedByExpr) return
        val tempName = "kxs_arg${ctx.nextHoistIndex()}"
        exprFrame.flags = exprFrame.flags or PacketFlags.EVAL_ORDER_HOIST_REQUIRED
        val temp =
            HoistedEvalTemp(
                tempCppName = tempName,
                exprFrameIndex = exprFrame.frameIndex,
                callOrBinaryFrameIndex = ownerCallOrBinaryFrameIndex,
            )
        ctx.hoistedByExpr[exprFrame.frameIndex] = temp
        ctx.hoistedBeforeStmt.getOrPut(enclosingStmtFrameIndex) { ArrayList() }.add(temp)

        val ownerFrame = ctx.stream.frames[ownerCallOrBinaryFrameIndex]
        val existingInfo = ownerFrame.semanticInfo ?: FrameSemanticInfo()
        val updatedList = ArrayList<Int>(existingInfo.hoistedPrefixFrameIndices.size + 1)
        updatedList.addAll(existingInfo.hoistedPrefixFrameIndices)
        updatedList.add(exprFrame.frameIndex)
        ownerFrame.semanticInfo = existingInfo.copy(hoistedPrefixFrameIndices = updatedList)
    }

    private fun isHoistableExpression(
        stream: PacketStream,
        exprFrame: PacketFrame,
    ): Boolean =
        when (exprFrame.nodeTypeId) {
            KtNodeTypes.INTEGER_CONSTANT_ID,
            KtNodeTypes.FLOAT_CONSTANT_ID,
            KtNodeTypes.BOOLEAN_CONSTANT_ID,
            KtNodeTypes.CHARACTER_CONSTANT_ID,
            KtNodeTypes.NULL_ID,
            KtNodeTypes.THIS_EXPRESSION_ID,
            -> false
            KtNodeTypes.STRING_TEMPLATE_ID ->
                PacketFlags.hasFlag(exprFrame.flags, PacketFlags.INTERPOLATED_STRING)
            else -> true
        }

    private fun detectLeafContinuationIntrinsic(
        stream: PacketStream,
        fnSym: KtFunctionSymbol,
        bodyFrame: PacketFrame,
    ): LeafContinuationIntrinsic? {
        val targetCall =
            when (bodyFrame.nodeTypeId) {
                KtNodeTypes.CALL_EXPRESSION_ID -> bodyFrame
                KtNodeTypes.BLOCK_ID -> {
                    val children = FrameAstHelpers.childFrames(stream, bodyFrame)
                    if (children.size == 1) {
                        val sole = children[0]
                        when (sole.nodeTypeId) {
                            KtNodeTypes.CALL_EXPRESSION_ID -> sole
                            KtNodeTypes.RETURN_ID ->
                                FrameAstHelpers.firstChildFrame(stream, sole, KtNodeTypes.CALL_EXPRESSION_ID)
                            else -> null
                        }
                    } else {
                        null
                    }
                }
                else -> null
            } ?: return null

        val callee = extractCalleeSimpleName(stream, targetCall)
        if (callee != "suspendCoroutineUninterceptedOrReturn") return null

        val trailingLambda =
            FrameAstHelpers
                .firstChildFrame(stream, targetCall, KtNodeTypes.LAMBDA_ARGUMENT_ID)
                ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.LAMBDA_EXPRESSION_ID) }
                ?: FrameAstHelpers
                    .firstChildFrame(stream, targetCall, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
                    ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.VALUE_ARGUMENT_ID) }
                    ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.LAMBDA_EXPRESSION_ID) }
                ?: return null

        val paramNames = extractLambdaParamNames(stream, trailingLambda)
        val uContKotlinName = paramNames.firstOrNull() ?: "it"
        val fnLit = FrameAstHelpers.firstChildFrame(stream, trailingLambda, KtNodeTypes.FUNCTION_LITERAL_ID)
        val lambdaBody =
            if (fnLit != null) {
                FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
            } else {
                trailingLambda
            }
        val retTypeSlice =
            fnSym.returnTypeFrameIndex?.let { FrameAstHelpers.frameSourceSlice(stream, stream.frames[it]).trim() }
        val isUnitReturn = retTypeSlice == null || retTypeSlice == "Unit"
        return LeafContinuationIntrinsic(
            funFrameIndex = fnSym.frameIndex,
            uContParamKotlinName = uContKotlinName,
            lambdaBodyFrameIndex = lambdaBody.frameIndex,
            isBoxedResult = !isUnitReturn,
        )
    }

    private fun resolveRestrictedReceiverName(
        stream: PacketStream,
        fnSym: KtFunctionSymbol,
        symbols: SameFileSymbolTable,
    ): String? {
        val recvIdx = fnSym.receiverTypeFrameIndex
        if (recvIdx != null) {
            val recvName = extractTopUserTypeSimpleName(stream, stream.frames[recvIdx])
            if (recvName != null && recvName in symbols.restrictsSuspensionTypeNames) {
                return recvName
            }
        }
        val encIdx = fnSym.enclosingClassFrameIndex
        if (encIdx != null) {
            val cls = symbols.classesByFrame[encIdx]
            if (cls != null && cls.kotlinName in symbols.restrictsSuspensionTypeNames) {
                return cls.kotlinName
            }
        }
        return null
    }

    private fun collectRestrictedMemberNames(
        symbols: SameFileSymbolTable,
        restrictedTypeName: String,
    ): Set<String> {
        val allowed = LinkedHashSet<String>()
        if (restrictedTypeName == "SequenceScope") {
            allowed.add("yield")
            allowed.add("yieldAll")
        }
        if (restrictedTypeName == "DeepRecursiveScope") {
            allowed.add("callRecursive")
        }
        val cls = symbols.classesByName[restrictedTypeName]
        if (cls != null) {
            for (mFnIdx in cls.memberFunctionFrameIndices) {
                val mFn = symbols.functionsByFrame[mFnIdx] ?: continue
                if (PacketFlags.hasFlag(mFn.flags, PacketFlags.MOD_SUSPEND)) {
                    allowed.add(mFn.kotlinName)
                }
            }
        }
        for (fn in symbols.functionsByFrame.values) {
            if (!PacketFlags.hasFlag(fn.flags, PacketFlags.MOD_SUSPEND)) continue
            val rIdx = fn.receiverTypeFrameIndex ?: continue
            // Match extension suspend functions declared on the restricted receiver in the same file.
            if (fn.kotlinName.isNotEmpty() && rIdx >= 0) {
                allowed.add(fn.kotlinName)
            }
        }
        return allowed
    }

    private fun collectSuspendCallableParamNames(
        stream: PacketStream,
        fnSym: KtFunctionSymbol,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        for (vp in fnSym.valueParams) {
            val tIdx = vp.typeFrameIndex ?: continue
            if (isSuspendFunctionTypeFrame(stream, stream.frames[tIdx])) {
                out.add(vp.name)
            }
        }
        return out
    }

    private fun isSuspendFunctionTypeFrame(
        stream: PacketStream,
        typeFrame: PacketFrame,
    ): Boolean {
        if (PacketFlags.hasFlag(typeFrame.flags, PacketFlags.MOD_SUSPEND) ||
            FrameAstHelpers.hasModifierKeyword(stream, typeFrame, KtTokens.SUSPEND_MODIFIER_ID)
        ) {
            return true
        }
        val fnType = FrameAstHelpers.findDescendantFrame(stream, typeFrame, KtNodeTypes.FUNCTION_TYPE_ID)
        return fnType != null && PacketFlags.hasFlag(fnType.flags, PacketFlags.MOD_SUSPEND)
    }

    private fun extractFunctionReceiverTypeName(
        stream: PacketStream,
        typeFrame: PacketFrame,
    ): String? {
        val fnType = FrameAstHelpers.findDescendantFrame(stream, typeFrame, KtNodeTypes.FUNCTION_TYPE_ID) ?: return null
        val recvWrap = FrameAstHelpers.firstChildFrame(stream, fnType, KtNodeTypes.FUNCTION_TYPE_RECEIVER_ID) ?: return null
        return extractTopUserTypeSimpleName(stream, recvWrap)
    }

    private fun extractTopUserTypeSimpleName(
        stream: PacketStream,
        root: PacketFrame,
    ): String? {
        val ut = FrameAstHelpers.findDescendantFrame(stream, root, KtNodeTypes.USER_TYPE_ID) ?: return null
        val ref = FrameAstHelpers.firstChildFrame(stream, ut, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        return if (ref != null) {
            FrameAstHelpers.directIdentPacket(stream, ref)?.unescapedName
        } else {
            FrameAstHelpers.directIdentPacket(stream, ut)?.unescapedName
        }
    }

    private fun extractCalleeSimpleName(
        stream: PacketStream,
        callFrame: PacketFrame,
    ): String {
        val ref = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        if (ref != null) {
            return FrameAstHelpers.directIdentPacket(stream, ref)?.unescapedName.orEmpty()
        }
        return FrameAstHelpers.directIdentPacket(stream, callFrame)?.unescapedName.orEmpty()
    }

    private fun extractSimpleRefName(
        stream: PacketStream,
        exprFrame: PacketFrame,
    ): String =
        if (exprFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            FrameAstHelpers.directIdentPacket(stream, exprFrame)?.unescapedName.orEmpty()
        } else {
            ""
        }

    private fun isThisReceiver(
        stream: PacketStream,
        exprFrame: PacketFrame,
    ): Boolean =
        exprFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID ||
            FrameAstHelpers.frameSourceSlice(stream, exprFrame).trim() == "this"

    private fun extractValueArgumentExpr(
        stream: PacketStream,
        vaFrame: PacketFrame,
    ): PacketFrame? {
        for (child in FrameAstHelpers.childFrames(stream, vaFrame)) {
            if (child.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID) {
                return child
            }
        }
        return null
    }

    private fun extractLambdaParamNames(
        stream: PacketStream,
        lambdaFrame: PacketFrame,
    ): List<String> {
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID) ?: return emptyList()
        val pList = FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.VALUE_PARAMETER_LIST_ID) ?: return emptyList()
        val out = ArrayList<String>()
        for (vp in FrameAstHelpers.childFramesOfType(stream, pList, KtNodeTypes.VALUE_PARAMETER_ID)) {
            FrameAstHelpers.directIdentPacket(stream, vp)?.unescapedName?.let { out.add(it) }
        }
        return out
    }

    private fun containsUnlabeledOrOuterReturn(
        stream: PacketStream,
        root: PacketFrame,
        inlineCalleeName: String,
    ): Boolean {
        if (root.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID || root.nodeTypeId == KtNodeTypes.FUN_ID) {
            return false
        }
        if (root.nodeTypeId == KtNodeTypes.RETURN_ID) {
            val labelQual = FrameAstHelpers.firstChildFrame(stream, root, KtNodeTypes.LABEL_QUALIFIER_ID)
            if (labelQual == null) return true
            val labelText = FrameAstHelpers.frameSourceSlice(stream, labelQual).removePrefix("@").trim()
            return labelText != inlineCalleeName
        }
        for (child in FrameAstHelpers.childFrames(stream, root)) {
            if (containsUnlabeledOrOuterReturn(stream, child, inlineCalleeName)) {
                return true
            }
        }
        return false
    }

    private val SHORT_CIRCUIT_AND_ASSIGN_OPS: Set<String> =
        setOf("&&", "||", "?:", "=", "+=", "-=", "*=", "/=", "%=")

    private val BUILTIN_INLINE_HOFS: Map<String, BuiltinInlineKind> =
        mapOf(
            "let" to BuiltinInlineKind.LET,
            "also" to BuiltinInlineKind.ALSO,
            "run" to BuiltinInlineKind.RUN,
            "apply" to BuiltinInlineKind.APPLY,
            "with" to BuiltinInlineKind.WITH,
            "use" to BuiltinInlineKind.USE,
            "repeat" to BuiltinInlineKind.REPEAT,
            "withLock" to BuiltinInlineKind.WITH_LOCK,
            "forEach" to BuiltinInlineKind.FOR_EACH,
        )

    private val KNOWN_INLINE_COROUTINE_BUILDERS: Set<String> =
        setOf(
            "suspendCancellableCoroutine",
            "suspendCancellableCoroutineReusable",
            "suspendCoroutineUninterceptedOrReturn",
            "select",
            "onEach",
            "map",
            "filter",
            "transform",
        )

    private val KNOWN_SUSPEND_LAMBDA_BUILDERS: Set<String> =
        setOf(
            "launch",
            "async",
            "runBlocking",
            "produce",
            "actor",
            "flow",
            "channelFlow",
            "callbackFlow",
            "withContext",
            "withTimeout",
            "withTimeoutOrNull",
            "coroutineScope",
            "supervisorScope",
            "sequence",
            "iterator",
        )

    private val KNOWN_SUSPEND_PRIMITIVES: Set<String> =
        setOf(
            "yield",
            "delay",
            "await",
            "awaitAll",
            "join",
            "joinAll",
            "cancelAndJoin",
            "receive",
            "receiveCatching",
            "send",
            "emit",
            "emitAll",
            "collect",
            "collectLatest",
            "first",
            "firstOrNull",
            "single",
            "singleOrNull",
            "toList",
            "toSet",
            "withContext",
            "withTimeout",
            "withTimeoutOrNull",
            "coroutineScope",
            "supervisorScope",
            "suspendCoroutine",
            "suspendCancellableCoroutine",
            "suspendCancellableCoroutineReusable",
            "suspendCoroutineUninterceptedOrReturn",
            "doSelect",
            "yieldAll",
            "callRecursive",
        )
}
