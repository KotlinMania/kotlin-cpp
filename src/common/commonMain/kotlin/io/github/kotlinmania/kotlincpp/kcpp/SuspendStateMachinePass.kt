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
    SYNCHRONIZED,
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
                .groupBy { it.kotlinName }

        // 1. Inspect every function & property body to validate suspend legality, detect leaf intrinsics,
        //    propagate HAS_SUSPEND_IN_SUBTREE, plan inline expansions, and hoist evaluation-order args.
        for (fnSym in symbols.functionsByFrame.values) {
            val fnFrame = stream.frames[fnSym.frameIndex]
            val isSuspendFn = PacketFlags.hasFlag(fnSym.flags, PacketFlags.MOD_SUSPEND)
            val restrictedReceiver = resolveRestrictedReceiverName(stream, fnSym, symbols)
            if (restrictedReceiver != null) {
                fnFrame.flags = fnFrame.flags or PacketFlags.RESTRICTS_SUSPENSION_SCOPE
            }

            val callableBindings = collectCallableParamBindings(stream, fnSym, symbols)
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

            val retTypeFrame = fnSym.returnTypeFrameIndex?.let { stream.frames[it] }
            val returnsSuspendLambda = retTypeFrame != null && isSuspendFunctionTypeFrame(stream, retTypeFrame)
            val retRestrictedType =
                retTypeFrame
                    ?.let { extractFunctionReceiverTypeName(stream, it) }
                    ?.takeIf { it in symbols.restrictsSuspensionTypeNames }

            val ctx =
                SuspendAnalysisContext(
                    stream = stream,
                    symbols = symbols,
                    sameFileSuspendFuns = sameFileSuspendFuns,
                    sameFileInlineFunsByName = sameFileInlineFunsByName,
                    suspendCallableVars = callableBindings.suspendVars,
                    callableVarsTakingSuspendLambda = callableBindings.takingSuspendLambdaVars,
                    nonSuspendCallableVars = callableBindings.nonSuspendCallableVars,
                    enclosingFunctionIsSuspend = isSuspendFn,
                    lambdaSuspendAllowed = isSuspendFn,
                    inCrossinlineLambda = false,
                    inNonInlineOrdinaryLambda = false,
                    returnTypeIsSuspendLambda = returnsSuspendLambda,
                    returnRestrictedReceiverType = retRestrictedType,
                    restrictedReceiverType = restrictedReceiver,
                    restrictedAllowedCalls = restrictedMemberNames,
                    enclosingFnName = fnSym.kotlinName,
                    enclosingFnFrameIndex = fnSym.frameIndex,
                    suspendCallFrames = suspendCallFrames,
                    hoistedByExpr = hoistedByExpr,
                    hoistedBeforeStmt = hoistedBeforeStmt,
                    inlineExpansions = inlineExpansions,
                    nextHoistIndex = { hoistCounter++ },
                )
            val exprBodyLambda =
                if (returnsSuspendLambda) {
                    FrameAstHelpers.unwrapLambdaExpression(stream, bodyFrame)
                } else {
                    null
                }
            val bodyHasSuspend =
                if (exprBodyLambda != null) {
                    analyzeSuspendTypedLambdaValue(exprBodyLambda, retRestrictedType, ctx)
                    false
                } else {
                    analyzeSubtree(bodyFrame, enclosingStmtFrameIndex = bodyFrame.frameIndex, ctx = ctx)
                }
            if (bodyHasSuspend) {
                fnFrame.flags = fnFrame.flags or PacketFlags.HAS_SUSPEND_IN_SUBTREE
            }
        }

        // Also check top-level/class property initializers & accessors so illegal suspend calls in initializers fail loud.
        for (propSym in symbols.propertiesByFrame.values) {
            val propTypeFrame = propSym.returnTypeFrameIndex?.let { stream.frames[it] }
            val propIsSuspendLambda = propTypeFrame != null && isSuspendFunctionTypeFrame(stream, propTypeFrame)
            val propRestricted =
                propTypeFrame
                    ?.let { extractFunctionReceiverTypeName(stream, it) }
                    ?.takeIf { it in symbols.restrictsSuspensionTypeNames }
            val framesToCheck = listOfNotNull(propSym.initializerFrameIndex, propSym.getterFrameIndex, propSym.setterFrameIndex)
            for (fIdx in framesToCheck) {
                val isSetter = fIdx == propSym.setterFrameIndex
                val ctx =
                    SuspendAnalysisContext(
                        stream = stream,
                        symbols = symbols,
                        sameFileSuspendFuns = sameFileSuspendFuns,
                        sameFileInlineFunsByName = sameFileInlineFunsByName,
                        suspendCallableVars = emptySet(),
                        callableVarsTakingSuspendLambda = emptySet(),
                        nonSuspendCallableVars = emptySet(),
                        enclosingFunctionIsSuspend = false,
                        lambdaSuspendAllowed = false,
                        inCrossinlineLambda = false,
                        inNonInlineOrdinaryLambda = false,
                        returnTypeIsSuspendLambda = propIsSuspendLambda && !isSetter,
                        returnRestrictedReceiverType = if (!isSetter) propRestricted else null,
                        restrictedReceiverType = null,
                        restrictedAllowedCalls = emptySet(),
                        enclosingFnName = propSym.kotlinName,
                        suspendCallFrames = suspendCallFrames,
                        hoistedByExpr = hoistedByExpr,
                        hoistedBeforeStmt = hoistedBeforeStmt,
                        inlineExpansions = inlineExpansions,
                        nextHoistIndex = { hoistCounter++ },
                    )
                val checkFrame = stream.frames[fIdx]
                val directSuspLam =
                    if (propIsSuspendLambda && !isSetter) {
                        if (checkFrame.nodeTypeId == KtNodeTypes.PROPERTY_ACCESSOR_ID) {
                            FrameAstHelpers
                                .extractInitializerAfterEquals(stream, checkFrame)
                                ?.let { FrameAstHelpers.unwrapLambdaExpression(stream, it) }
                        } else {
                            FrameAstHelpers.unwrapLambdaExpression(stream, checkFrame)
                        }
                    } else {
                        null
                    }
                if (directSuspLam != null) {
                    analyzeSuspendTypedLambdaValue(directSuspLam, propRestricted, ctx)
                } else {
                    analyzeSubtree(checkFrame, enclosingStmtFrameIndex = fIdx, ctx = ctx)
                }
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
        val sameFileInlineFunsByName: Map<String, List<KtFunctionSymbol>>,
        val suspendCallableVars: Set<String>,
        val callableVarsTakingSuspendLambda: Set<String>,
        val nonSuspendCallableVars: Set<String>,
        val enclosingFunctionIsSuspend: Boolean,
        val lambdaSuspendAllowed: Boolean,
        val inCrossinlineLambda: Boolean,
        val inNonInlineOrdinaryLambda: Boolean,
        val inSelectBuilderScope: Boolean = false,
        val returnTypeIsSuspendLambda: Boolean = false,
        val returnRestrictedReceiverType: String? = null,
        val restrictedReceiverType: String?,
        val restrictedAllowedCalls: Set<String>,
        val enclosingFnName: String,
        val enclosingFnFrameIndex: Int? = null,
        val suspendCallFrames: MutableSet<Int>,
        val hoistedByExpr: MutableMap<Int, HoistedEvalTemp>,
        val hoistedBeforeStmt: MutableMap<Int, MutableList<HoistedEvalTemp>>,
        val inlineExpansions: MutableMap<Int, InlineCallExpansion>,
        val nextHoistIndex: () -> Int,
    ) {
        fun withExtraSuspendVar(varName: String): SuspendAnalysisContext {
            if (varName in suspendCallableVars) return this
            val out = LinkedHashSet<String>(suspendCallableVars.size + 1)
            out.addAll(suspendCallableVars)
            out.add(varName)
            return SuspendAnalysisContext(
                stream = stream,
                symbols = symbols,
                sameFileSuspendFuns = sameFileSuspendFuns,
                sameFileInlineFunsByName = sameFileInlineFunsByName,
                suspendCallableVars = out,
                callableVarsTakingSuspendLambda = callableVarsTakingSuspendLambda,
                nonSuspendCallableVars = nonSuspendCallableVars,
                enclosingFunctionIsSuspend = enclosingFunctionIsSuspend,
                lambdaSuspendAllowed = lambdaSuspendAllowed,
                inCrossinlineLambda = inCrossinlineLambda,
                inNonInlineOrdinaryLambda = inNonInlineOrdinaryLambda,
                inSelectBuilderScope = inSelectBuilderScope,
                returnTypeIsSuspendLambda = returnTypeIsSuspendLambda,
                returnRestrictedReceiverType = returnRestrictedReceiverType,
                restrictedReceiverType = restrictedReceiverType,
                restrictedAllowedCalls = restrictedAllowedCalls,
                enclosingFnName = enclosingFnName,
                enclosingFnFrameIndex = enclosingFnFrameIndex,
                suspendCallFrames = suspendCallFrames,
                hoistedByExpr = hoistedByExpr,
                hoistedBeforeStmt = hoistedBeforeStmt,
                inlineExpansions = inlineExpansions,
                nextHoistIndex = nextHoistIndex,
            )
        }

        fun copyForLambda(
            allowSuspend: Boolean,
            crossinline: Boolean,
            nonInlineOrdinary: Boolean,
            restrictedType: String?,
            restrictedMembers: Set<String>,
            extraSuspendVars: Set<String> = emptySet(),
            selectBuilderScope: Boolean = false,
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
                callableVarsTakingSuspendLambda = callableVarsTakingSuspendLambda,
                nonSuspendCallableVars = nonSuspendCallableVars,
                enclosingFunctionIsSuspend = enclosingFunctionIsSuspend || allowSuspend,
                lambdaSuspendAllowed = allowSuspend,
                inCrossinlineLambda = crossinline,
                inNonInlineOrdinaryLambda = nonInlineOrdinary,
                inSelectBuilderScope = selectBuilderScope,
                returnTypeIsSuspendLambda = false,
                returnRestrictedReceiverType = null,
                restrictedReceiverType = restrictedType ?: restrictedReceiverType,
                restrictedAllowedCalls =
                    if (restrictedType != null) restrictedMembers else restrictedAllowedCalls,
                enclosingFnName = enclosingFnName,
                enclosingFnFrameIndex = enclosingFnFrameIndex,
                suspendCallFrames = suspendCallFrames,
                hoistedByExpr = hoistedByExpr,
                hoistedBeforeStmt = hoistedBeforeStmt,
                inlineExpansions = inlineExpansions,
                nextHoistIndex = nextHoistIndex,
            )
        }
    }

    private fun analyzeSuspendTypedLambdaValue(
        lambdaFrame: PacketFrame,
        restrictedType: String?,
        ctx: SuspendAnalysisContext,
    ) {
        val restrictedMembers =
            if (restrictedType != null) {
                collectRestrictedMemberNames(ctx.symbols, restrictedType)
            } else {
                emptySet()
            }
        val suspLamCtx =
            ctx.copyForLambda(
                allowSuspend = true,
                crossinline = false,
                nonInlineOrdinary = false,
                restrictedType = restrictedType,
                restrictedMembers = restrictedMembers,
            )
        for (child in FrameAstHelpers.childFrames(ctx.stream, lambdaFrame)) {
            analyzeSubtree(child, enclosingStmtFrameIndex = child.frameIndex, ctx = suspLamCtx)
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
                var blockCtx = ctx
                for (child in FrameAstHelpers.childFrames(stream, frame)) {
                    if (child.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                        val (_, propTypeFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, child)
                        val initExpr = FrameAstHelpers.extractInitializerAfterEquals(stream, child)
                        val isSuspendBuilderInit =
                            initExpr?.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID &&
                                extractCalleeSimpleName(stream, initExpr) == "suspend" &&
                                FrameAstHelpers.firstChildFrame(stream, initExpr, KtNodeTypes.LAMBDA_ARGUMENT_ID) != null
                        val isSuspProp =
                            (propTypeFrame != null && isSuspendFunctionTypeFrame(stream, propTypeFrame)) || isSuspendBuilderInit
                        if (isSuspProp) {
                            val unwrappedLam = initExpr?.let { FrameAstHelpers.unwrapLambdaExpression(stream, it) }
                            if (unwrappedLam != null) {
                                val suspLamCtx =
                                    blockCtx.copyForLambda(
                                        allowSuspend = true,
                                        crossinline = false,
                                        nonInlineOrdinary = false,
                                        restrictedType =
                                            propTypeFrame
                                                ?.let { extractFunctionReceiverTypeName(stream, it) }
                                                ?.takeIf { it in blockCtx.symbols.restrictsSuspensionTypeNames },
                                        restrictedMembers = emptySet(),
                                    )
                                for (lc in FrameAstHelpers.childFrames(stream, unwrappedLam)) {
                                    analyzeSubtree(lc, enclosingStmtFrameIndex = lc.frameIndex, ctx = suspLamCtx)
                                }
                            } else if (initExpr != null) {
                                if (analyzeSubtree(initExpr, enclosingStmtFrameIndex = child.frameIndex, ctx = blockCtx)) {
                                    anySuspend = true
                                }
                            }
                            FrameAstHelpers.directIdentPacket(stream, child)?.unescapedName?.let { propName ->
                                blockCtx = blockCtx.withExtraSuspendVar(propName)
                            }
                            continue
                        }
                    } else if (child.nodeTypeId == KtNodeTypes.BINARY_WITH_TYPE_ID) {
                        val castLhs = FrameAstHelpers.childFrames(stream, child).firstOrNull()
                        val castTr = FrameAstHelpers.firstChildFrame(stream, child, KtNodeTypes.TYPE_REFERENCE_ID)
                        if (castLhs?.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                            castTr != null &&
                            isSuspendFunctionTypeFrame(stream, castTr)
                        ) {
                            FrameAstHelpers.directIdentPacket(stream, castLhs)?.unescapedName?.let { castVar ->
                                blockCtx = blockCtx.withExtraSuspendVar(castVar)
                            }
                        }
                    }
                    val childHas = analyzeSubtree(child, enclosingStmtFrameIndex = child.frameIndex, ctx = blockCtx)
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
                    val opId = FrameAstHelpers.operationTokenId(stream, opRef)
                    val opSymbol = FrameAstHelpers.operationSymbolName(stream, opRef)
                    val isInfixSuspend = opId == KtTokens.IDENTIFIER_ID && opSymbol in ctx.sameFileSuspendFuns
                    val lhsHas = analyzeSubtree(lhs, enclosingStmtFrameIndex, ctx)
                    val rhsHas = analyzeSubtree(rhs, enclosingStmtFrameIndex, ctx)

                    val isShortCircuitOrAssign = opId in SHORT_CIRCUIT_AND_ASSIGN_OP_IDS
                    if (!isShortCircuitOrAssign && rhsHas && isHoistableExpression(stream, lhs)) {
                        hoistExpression(lhs, frame.frameIndex, enclosingStmtFrameIndex, ctx)
                    }
                    if (isInfixSuspend) {
                        validateSuspendCallLegality(frame, opSymbol, hasExplicitAllowedReceiver = false, ctx = ctx)
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

            KtNodeTypes.RETURN_ID -> {
                val hasLabel = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.LABEL_QUALIFIER_ID) != null
                if (!hasLabel && ctx.returnTypeIsSuspendLambda) {
                    val retExpr =
                        FrameAstHelpers
                            .childFrames(stream, frame)
                            .firstOrNull { it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID }
                    val retLambda = retExpr?.let { FrameAstHelpers.unwrapLambdaExpression(stream, it) }
                    if (retLambda != null) {
                        analyzeSuspendTypedLambdaValue(retLambda, ctx.returnRestrictedReceiverType, ctx)
                        return false
                    }
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
        val rawCalleeName = extractCalleeSimpleName(stream, callFrame)
        val calleeName =
            if (rawCalleeName in ctx.suspendCallableVars) {
                rawCalleeName
            } else {
                ctx.symbols.importAliases[rawCalleeName] ?: rawCalleeName
            }

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
        val totalCallArgs = valueArgFrames.size + trailingLambdaArgs.size

        val sameFileInlineFn =
            selectBestOverload(
                stream = stream,
                candidates =
                    ctx.sameFileInlineFunsByName[calleeName]
                        .orEmpty()
                        .filter { fn ->
                            fn.frameIndex != ctx.enclosingFnFrameIndex &&
                                fn.valueParams.lastOrNull()?.typeFrameIndex?.let { tIdx ->
                                    FrameAstHelpers.findDescendantFrame(stream, stream.frames[tIdx], KtNodeTypes.FUNCTION_TYPE_ID) != null
                                } == true
                        },
                totalCallArgs = totalCallArgs,
                hasExplicitReceiver = receiverFrame != null,
                hasLambdaArg = trailingLambdaArgs.isNotEmpty(),
            )
        val resolvedCallee = ctx.symbols.importAliases[calleeName] ?: calleeName
        val builtinInlineKind = BUILTIN_INLINE_HOFS[resolvedCallee]
        val sameFileTargetFn =
            selectBestOverload(
                stream = stream,
                candidates = ctx.symbols.functionsByName[calleeName].orEmpty(),
                totalCallArgs = totalCallArgs,
                hasExplicitReceiver = receiverFrame != null,
                hasLambdaArg = trailingLambdaArgs.isNotEmpty(),
            )

        val argExprsForInline = ArrayList<Int>()
        var capturedInlineLambdaFrame: PacketFrame? = null
        var inlineLambdaHasSuspendOrNonLocalReturn = false

        for ((argIdx, vaFrame) in valueArgFrames.withIndex()) {
            val innerExpr = extractValueArgumentExpr(stream, vaFrame) ?: continue
            argExprsForInline.add(innerExpr.frameIndex)
            val unwrappedLambda = FrameAstHelpers.unwrapLambdaExpression(stream, innerExpr)
            if (unwrappedLambda != null) {
                val lambdaResult =
                    analyzeCallLambdaArgument(
                        calleeName = calleeName,
                        paramIndex = argIdx,
                        lambdaFrame = unwrappedLambda,
                        sameFileInlineFn = sameFileInlineFn,
                        sameFileTargetFn = sameFileTargetFn,
                        builtinInlineKind = builtinInlineKind,
                        ctx = ctx,
                    )
                if (lambdaResult.inlinedIntoCaller) {
                    capturedInlineLambdaFrame = unwrappedLambda
                    if (lambdaResult.hasSuspend || lambdaResult.hasNonLocalReturn) {
                        inlineLambdaHasSuspendOrNonLocalReturn = true
                    }
                    evalExprFrames.add(unwrappedLambda)
                    evalHasSuspend.add(lambdaResult.hasSuspend)
                } else {
                    evalExprFrames.add(unwrappedLambda)
                    evalHasSuspend.add(false)
                }
            } else {
                val aHas = analyzeSubtree(innerExpr, enclosingStmtFrameIndex, ctx)
                evalExprFrames.add(innerExpr)
                evalHasSuspend.add(aHas)
            }
        }

        for ((tIdx, tlArg) in trailingLambdaArgs.withIndex()) {
            val lambdaFrame = FrameAstHelpers.unwrapLambdaExpression(stream, tlArg) ?: continue
            val targetParamCount = (sameFileInlineFn ?: sameFileTargetFn)?.valueParams?.size
            val paramIdx =
                if (trailingLambdaArgs.size == 1 && targetParamCount != null && targetParamCount > 0) {
                    targetParamCount - 1
                } else {
                    valueArgFrames.size + tIdx
                }
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

        val allSameFileOverloads = ctx.symbols.functionsByName[calleeName].orEmpty()
        val arityMatchedOverloads = allSameFileOverloads.filter { isArityCompatible(it, totalCallArgs) }
        val isSameFileSuspendCall =
            arityMatchedOverloads.isNotEmpty() &&
                arityMatchedOverloads.any { PacketFlags.hasFlag(it.flags, PacketFlags.MOD_SUSPEND) } &&
                (ctx.lambdaSuspendAllowed || arityMatchedOverloads.all { PacketFlags.hasFlag(it.flags, PacketFlags.MOD_SUSPEND) })

        val invokesSuspendCallableVar =
            calleeName in ctx.suspendCallableVars &&
                !(
                    trailingLambdaArgs.isNotEmpty() &&
                        calleeName !in ctx.callableVarsTakingSuspendLambda &&
                        (calleeName in KNOWN_SUSPEND_LAMBDA_BUILDERS || sameFileInlineFn != null || sameFileTargetFn != null)
                )

        val isDirectSuspendCall =
            isSameFileSuspendCall ||
                invokesSuspendCallableVar ||
                (calleeName in KNOWN_SUSPEND_PRIMITIVES && calleeName !in ctx.nonSuspendCallableVars) ||
                (calleeName in AMBIGUOUS_COLLECTION_OR_FLOW_OPS && ctx.lambdaSuspendAllowed) ||
                (
                    calleeName == "invoke" && receiverFrame != null &&
                        (
                            extractSimpleRefName(stream, receiverFrame) in ctx.suspendCallableVars ||
                                (receiverFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID && "this" in ctx.suspendCallableVars)
                        )
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

        // Record inline call expansion when a builtin or same-file `inline fun` has a lambda with suspend or non-local return,
        // or when `synchronized(lock) { ... }` is called directly with an inline lambda.
        if (capturedInlineLambdaFrame != null &&
            (inlineLambdaHasSuspendOrNonLocalReturn || builtinInlineKind == BuiltinInlineKind.SYNCHRONIZED)
        ) {
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
                calleeName in ctx.callableVarsTakingSuspendLambda ||
                (ctx.inSelectBuilderScope && paramIndex >= (sameFileTargetFn?.valueParams?.size ?: 0)) ||
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
                isSuspendLambdaParam -> true
                isCrossinline -> false
                isInlinedPlace -> ctx.lambdaSuspendAllowed
                else -> false
            }

        val childCtx =
            ctx.copyForLambda(
                allowSuspend = allowSuspendInsideLambda,
                crossinline = isCrossinline && !isSuspendLambdaParam,
                nonInlineOrdinary = !isInlinedPlace && !isSuspendLambdaParam,
                restrictedType = restrictedLambdaReceiver,
                restrictedMembers = restrictedMembers,
                selectBuilderScope = calleeName == "select" || calleeName == "whileSelect",
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
                ?.let { FrameAstHelpers.unwrapLambdaExpression(stream, it) }
                ?: FrameAstHelpers
                    .firstChildFrame(stream, targetCall, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
                    ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.VALUE_ARGUMENT_ID) }
                    ?.let { FrameAstHelpers.unwrapLambdaExpression(stream, it) }
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
        val isUnitReturn =
            fnSym.returnTypeFrameIndex == null ||
                stream.frames[fnSym.returnTypeFrameIndex].let { rf ->
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, rf) == "Unit" &&
                        !FrameAstHelpers.isNullableTypeFrame(stream, rf)
                }
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

    private fun isArityCompatible(
        fnSym: KtFunctionSymbol,
        totalCallArgs: Int,
    ): Boolean {
        val minArgs = fnSym.valueParams.count { it.defaultExprFrameIndex == null && !it.isVararg }
        val maxArgs = if (fnSym.valueParams.any { it.isVararg }) Int.MAX_VALUE else fnSym.valueParams.size
        return totalCallArgs in minArgs..maxArgs
    }

    private fun selectBestOverload(
        stream: PacketStream,
        candidates: List<KtFunctionSymbol>,
        totalCallArgs: Int,
        hasExplicitReceiver: Boolean,
        hasLambdaArg: Boolean,
    ): KtFunctionSymbol? {
        if (candidates.isEmpty()) return null
        val receiverFiltered =
            if (hasExplicitReceiver) {
                candidates.filter { it.receiverTypeFrameIndex != null || it.enclosingClassFrameIndex != null }
            } else {
                candidates
            }
        if (receiverFiltered.isEmpty()) return null
        val arityMatches = receiverFiltered.filter { isArityCompatible(it, totalCallArgs) }
        if (arityMatches.isEmpty()) return null
        if (arityMatches.size == 1) return arityMatches[0]
        return arityMatches.maxByOrNull { fn ->
            var score = 0
            if (hasExplicitReceiver && fn.receiverTypeFrameIndex != null) score += 4
            if (!hasExplicitReceiver && fn.receiverTypeFrameIndex == null) score += 2
            if (PacketFlags.hasFlag(fn.flags, PacketFlags.MOD_INLINE)) score += 2
            val lastParamIsSuspend =
                fn.valueParams
                    .lastOrNull()
                    ?.typeFrameIndex
                    ?.let { isSuspendFunctionTypeFrame(stream, stream.frames[it]) } == true
            if (hasLambdaArg && lastParamIsSuspend) {
                score += 3
            }
            score
        }
    }

    private class CallableVarBindings(
        val suspendVars: Set<String>,
        val takingSuspendLambdaVars: Set<String>,
        val nonSuspendCallableVars: Set<String>,
    )

    private fun collectCallableParamBindings(
        stream: PacketStream,
        fnSym: KtFunctionSymbol,
        symbols: SameFileSymbolTable,
    ): CallableVarBindings {
        val suspendOut = LinkedHashSet<String>()
        val takingSuspendLambdaOut = LinkedHashSet<String>()
        val nonSuspendCallableOut = LinkedHashSet<String>()

        fun classifyVar(
            name: String,
            typeFrame: PacketFrame,
        ) {
            if (isSuspendFunctionTypeFrame(stream, typeFrame)) {
                suspendOut.add(name)
            } else if (FrameAstHelpers.findDescendantFrame(stream, typeFrame, KtNodeTypes.FUNCTION_TYPE_ID) != null) {
                nonSuspendCallableOut.add(name)
            }
            if (functionTypeAcceptsSuspendLambdaArg(stream, typeFrame)) {
                takingSuspendLambdaOut.add(name)
            }
        }

        fnSym.receiverTypeFrameIndex?.let { rIdx ->
            classifyVar("this", stream.frames[rIdx])
        }
        for (vp in fnSym.valueParams) {
            val tIdx = vp.typeFrameIndex ?: continue
            classifyVar(vp.name, stream.frames[tIdx])
        }
        val encCls = fnSym.enclosingClassFrameIndex?.let { symbols.classesByFrame[it] }
        if (encCls != null) {
            for (cp in encCls.primaryConstructorParams) {
                if ((cp.isVal || cp.isVar) && cp.typeFrameIndex != null) {
                    classifyVar(cp.name, stream.frames[cp.typeFrameIndex])
                }
            }
            for (mpIdx in encCls.memberPropertyFrameIndices) {
                val mp = symbols.propertiesByFrame[mpIdx] ?: continue
                if (mp.returnTypeFrameIndex != null) {
                    classifyVar(mp.kotlinName, stream.frames[mp.returnTypeFrameIndex])
                }
            }
        }
        return CallableVarBindings(
            suspendVars = suspendOut,
            takingSuspendLambdaVars = takingSuspendLambdaOut,
            nonSuspendCallableVars = nonSuspendCallableOut,
        )
    }

    private fun functionTypeAcceptsSuspendLambdaArg(
        stream: PacketStream,
        typeFrame: PacketFrame,
    ): Boolean {
        val fnType = FrameAstHelpers.findDescendantFrame(stream, typeFrame, KtNodeTypes.FUNCTION_TYPE_ID) ?: return false
        val pList = FrameAstHelpers.firstChildFrame(stream, fnType, KtNodeTypes.VALUE_PARAMETER_LIST_ID) ?: return false
        for (param in FrameAstHelpers.childFrames(stream, pList)) {
            val paramTr =
                if (param.nodeTypeId == KtNodeTypes.TYPE_REFERENCE_ID) {
                    param
                } else {
                    FrameAstHelpers.firstChildFrame(stream, param, KtNodeTypes.TYPE_REFERENCE_ID)
                }
            if (paramTr != null && isSuspendFunctionTypeFrame(stream, paramTr)) {
                return true
            }
        }
        return false
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
    ): Boolean = exprFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID

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
            val labelText = FrameAstHelpers.directIdentPacket(stream, labelQual)?.unescapedName.orEmpty()
            return labelText != inlineCalleeName
        }
        for (child in FrameAstHelpers.childFrames(stream, root)) {
            if (containsUnlabeledOrOuterReturn(stream, child, inlineCalleeName)) {
                return true
            }
        }
        return false
    }

    private val SHORT_CIRCUIT_AND_ASSIGN_OP_IDS: Set<Int> =
        setOf(
            KtTokens.ANDAND_ID,
            KtTokens.OROR_ID,
            KtTokens.ELVIS_ID,
            KtTokens.EQ_ID,
            KtTokens.PLUSEQ_ID,
            KtTokens.MINUSEQ_ID,
            KtTokens.MULTEQ_ID,
            KtTokens.DIVEQ_ID,
            KtTokens.PERCEQ_ID,
        )

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
            "synchronized" to BuiltinInlineKind.SYNCHRONIZED,
            "forEach" to BuiltinInlineKind.FOR_EACH,
        )

    private val KNOWN_INLINE_COROUTINE_BUILDERS: Set<String> =
        setOf(
            "suspendCancellableCoroutine",
            "suspendCancellableCoroutineReusable",
            "suspendCoroutineUninterceptedOrReturn",
            "select",
            "selectUnbiased",
            "whileSelect",
            "onEach",
            "map",
            "mapNotNull",
            "mapIndexed",
            "mapIndexedNotNull",
            "filter",
            "filterNot",
            "filterIsInstance",
            "flatMap",
            "partition",
            "transform",
            "runCatching",
            "mapCatching",
            "recoverCatching",
            "onSuccess",
            "onFailure",
            "onClosed",
            "fold",
            "reduce",
            "getOrElse",
            "getOrDefault",
            "getOrPut",
            "takeIf",
            "takeUnless",
            "synchronized",
            "List",
            "MutableList",
            "Array",
            "buildList",
            "buildSet",
            "buildMap",
            "buildString",
            "forEachIndexed",
            "any",
            "all",
            "none",
            "count",
            "first",
            "firstOrNull",
            "last",
            "lastOrNull",
            "single",
            "singleOrNull",
            "find",
            "findLast",
            "loop",
            "update",
            "getAndUpdate",
            "updateAndGet",
            "consume",
            "assert",
            "assertFails",
            "assertFailsWith",
            "autoreleasepool",
            "memScoped",
            "usePinned",
            "measureTime",
            "measureTimedValue",
            "testSubscriptionByFirstSuspensionInCollect",
        )

    private val KNOWN_SUSPEND_LAMBDA_BUILDERS: Set<String> =
        setOf(
            "suspend",
            "start",
            "launch",
            "launchFlow",
            "async",
            "runBlocking",
            "runBlockingTest",
            "runBlockingTestOnTestScope",
            "createTestResult",
            "runTestCoroutine",
            "runTestCoroutineLegacy",
            "runTestOrSkip",
            "withMainScope",
            "finally",
            "produce",
            "actor",
            "broadcast",
            "flow",
            "unsafeFlow",
            "safeFlow",
            "scopedFlow",
            "flowScope",
            "FlowCollector",
            "channelFlow",
            "callbackFlow",
            "collect",
            "collectLatest",
            "collectIndexed",
            "collectWhile",
            "transform",
            "unsafeTransform",
            "transformLatest",
            "transformWhile",
            "onStart",
            "onCompletion",
            "onEmpty",
            "onEach",
            "onSubscription",
            "catch",
            "retry",
            "retryWhen",
            "dropWhile",
            "takeWhile",
            "flatMapConcat",
            "flatMapMerge",
            "flatMapLatest",
            "mapLatest",
            "combine",
            "combineTransform",
            "combineTransformUnsafe",
            "combineInternal",
            "combineLatest",
            "zip",
            "zipImpl",
            "scan",
            "scanFold",
            "scanReduce",
            "runningFold",
            "runningReduce",
            "switchMap",
            "subscribe",
            "onErrorReturn",
            "debounce",
            "sample",
            "timeout",
            "consumeEach",
            "withContext",
            "withContextUndispatched",
            "withTimeout",
            "withTimeoutOrNull",
            "coroutineScope",
            "supervisorScope",
            "sequence",
            "iterator",
            "onSend",
            "onReceive",
            "onReceiveCatching",
            "onAwait",
            "onJoin",
            "onTimeout",
            "default",
            "startCoroutine",
            "startCoroutineCancellable",
            "startCoroutineUndispatched",
            "createCoroutineUnintercepted",
            "undispatchedResult",
            "undispatchedResultWithTimeout",
            "runTest",
            "withVirtualTime",
            "withEmptyContext",
            "checkBuffer",
            "track",
            "runAllKindsTest",
            "parametrized",
            "testScope",
        )

    private val AMBIGUOUS_COLLECTION_OR_FLOW_OPS: Set<String> =
        setOf(
            "first",
            "firstOrNull",
            "single",
            "singleOrNull",
            "last",
            "lastOrNull",
            "toList",
            "toSet",
            "count",
            "fold",
            "reduce",
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
