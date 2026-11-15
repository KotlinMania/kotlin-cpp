package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.ASSIGN_OPS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.COMPOUND_OR_DECLARATION_STMT_NODE_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.DIRECT_EQ_SCALAR_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.DIRECT_ITERABLE_VALUE_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.DISPATCHER_OR_EVENT_LOOP_FACTORY_NAMES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.ELVIS_CONTROL_TRANSFER_NODE_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.EXCEPTION_PTR_KOTLIN_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.KNOWN_TO_STRING_SUPER_FRAGMENTS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.NON_SWITCH_SUBJECT_TYPES
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal fun CppExprAndStmtEmitter.emitValueAssignmentOrReturn(
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
                val lowered = lowerElvisControlTransfer(binChildren[0], rhsUnwrapped, indent)
                val assignLine =
                    if (targetReturn) {
                        "${indent}return ${wrapSuspendReturnValue(lowered.derefExpr)};\n"
                    } else {
                        "$indent$targetVar = ${lowered.derefExpr};\n"
                    }
                return "${lowered.preludeCode}$assignLine"
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
                renderNullReturnForType(effectiveRetTypeForNull, indent)?.let { return it }
            }
            val rendered = emitExpression(unwrappedExpr)
            if (PacketFlags.hasFlag(unwrappedExpr.flags, PacketFlags.DIVERGING_EXPR)) {
                if (isOuterSuspendReturnContext) {
                    if (isThrowCppStatement(rendered)) {
                        "$indent$rendered;\n"
                    } else {
                        "$indent$rendered;\n${indent}return ${defaultSuspendFallthroughReturnExpr()};\n"
                    }
                } else {
                    includes.addSystem("utility")
                    "$indent$rendered;\n${indent}std::unreachable();\n"
                }
            } else if (isThrowCppStatement(rendered)) {
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

internal fun CppExprAndStmtEmitter.emitStatement(
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

internal fun CppExprAndStmtEmitter.emitStatementBodyWithoutLeadingComments(
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
                        val lowered = lowerElvisControlTransfer(ch[0], unwrapTransparentFrame(ch[2]), indent)
                        append(lowered.preludeCode)
                        preLoweredElvisExprs[elvisFrame.frameIndex] = lowered.derefExpr
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
                            val lhsTarget = emitExpression(binChildren[0])
                            val lowered = lowerElvisControlTransfer(elvisCh[0], elvisRhs, indent)
                            return "${lowered.preludeCode}$indent$lhsTarget = ${lowered.derefExpr};\n"
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

internal fun CppExprAndStmtEmitter.emitLocalPropertyDecl(
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

            val parsedDeclType = CppType.parseInternal(effectiveDeclType)
            val tempType =
                if (parsedDeclType.stripNoReturn().isVoid) {
                    includes.addSystem("any")
                    "std::any"
                } else {
                    if (parsedDeclType.isStdAny) includes.addSystem("any")
                    effectiveDeclType
                }
            val parsedTemp = CppType.parseInternal(tempType)
            val useOptWrapper =
                !parsedTemp.isStdAny &&
                    !parsedTemp.isOptional &&
                    !isCppPtrOrSharedType(tempType) &&
                    !parsedTemp.isExceptionPtr
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
                val lowered = lowerElvisControlTransfer(ch[0], rhsUnwrapped, indent)
                val lhsIsPolyPtr = isPointerOrSharedPtrReceiver(ch[0], lowered.lhsCode)
                val lhsSimpleType = declaredSimpleType ?: inferExpressionSimpleTypeName(ch[0])
                bindLocalName(kotlinName, cppName)
                if (lhsSimpleType != null && declaredSimpleType == null) markLocalType(kotlinName, lhsSimpleType)
                if (lhsIsPolyPtr || (cppType != "auto" && isCppPtrOrSharedType(cppType))) {
                    markLocalPolyVar(kotlinName)
                }
                return "${lowered.preludeCode}$indent$constQual$cppType $cppName = ${lowered.derefExpr};\n"
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
        val parsedCppType = CppType.parseInternal(cppType)
        val coercedInit =
            when {
                rawInit.startsWith("std::vector<void*>(") &&
                    parsedCppType.isVector &&
                    cppType != "std::vector<void*>" ->
                    cppType + rawInit.removePrefix("std::vector<void*>")
                rawInit == "nullptr" && (parsedCppType.isOptional || parsedCppType.isStdAny) ->
                    parsedCppType.coerceNullDefaultExpr(rawInit, includes, coerceStdAnyToBraces = true)
                parsedCppType.isExceptionPtr -> wrapWithExceptionPtrConversion(rawInit, initFrame)
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

internal fun CppExprAndStmtEmitter.emitLocalFunctionDecl(
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
            isVoidReturnContext = CppType.parseInternal(retType).stripNoReturn().isVoid,
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

internal fun CppExprAndStmtEmitter.frameContainsIdentifier(
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

internal fun CppExprAndStmtEmitter.emitLocalClassOrObjectDecl(
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
                    val isVoidM = CppType.parseInternal(retT).stripNoReturn().isVoid
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

internal fun CppExprAndStmtEmitter.emitDestructuringDecl(
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

internal fun CppExprAndStmtEmitter.emitReturnStmt(
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
        renderNullReturnForType(effectiveRetTypeForNull, indent)?.let { return it }
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
    if (isThrowCppStatement(renderedRet)) {
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

internal fun CppExprAndStmtEmitter.emitThrowStmt(
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

internal fun CppExprAndStmtEmitter.emitThrowableExpr(exprFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.emitIfStatement(
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

internal fun CppExprAndStmtEmitter.computeNullCheckUnwrapBindings(
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

internal fun CppExprAndStmtEmitter.frameReassignsVariable(
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

internal fun CppExprAndStmtEmitter.emitBranchBody(
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

internal fun CppExprAndStmtEmitter.emitWhenStatement(
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

internal fun CppExprAndStmtEmitter.emitWhenEntryCondition(
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

internal fun CppExprAndStmtEmitter.emitRangeMembershipCheck(
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

internal fun CppExprAndStmtEmitter.emitTryStatement(
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

internal fun CppExprAndStmtEmitter.emitWhileStmt(
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

internal fun CppExprAndStmtEmitter.emitDoWhileStmt(
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

internal fun CppExprAndStmtEmitter.emitForStmt(
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

private data class ElvisTransferLowering(
    val lhsCode: String,
    val preludeCode: String,
    val derefExpr: String,
)

private fun CppExprAndStmtEmitter.formatElvisDerefExpr(elvisTemp: String): String =
    if (bundle.isDocExample) {
        includes.addSystem("type_traits")
        buildCppTokens {
            emitIife(
                capture = "&",
                paramsDecl = "auto&& kxs_ev",
                explicitReturnType = "decltype(auto)",
                invokeArgs = elvisTemp,
                bodyCode =
                    "if constexpr (std::is_pointer_v<std::decay_t<decltype(kxs_ev)>>) " +
                        "return kxs_ev; else return *kxs_ev;",
            )
        }
    } else {
        includes.needsNullUnwrapDef = true
        includes.addSystem("type_traits")
        includes.addSystem("utility")
        "kxs_unwrap_opt($elvisTemp)"
    }

private fun CppExprAndStmtEmitter.lowerElvisControlTransfer(
    lhsFrame: PacketFrame,
    rhsUnwrapped: PacketFrame,
    indent: String,
): ElvisTransferLowering {
    val elvisTemp = freshTemp("kxs_elvis")
    val lhsCode = emitExpression(lhsFrame)
    val transferStmt =
        emitStatement(rhsUnwrapped, "$indent    ", inValuePosition = false, resultTargetVar = null)
    val nullCheck =
        if (bundle.isDocExample) {
            "!$elvisTemp"
        } else {
            includes.needsNullUnwrapDef = true
            "kxs_is_null($elvisTemp)"
        }
    val header =
        buildCppTokens(baseIndent = indent) {
            keyword("auto")
            space()
            ident(elvisTemp)
            space()
            punct("=", jointToNext = false)
            space()
            emitRawCode(lhsCode)
            punct(";", jointToNext = false)
            newline()
            keyword("if")
            space()
            parens { emitRawCode(nullCheck) }
            space()
            punct("{", jointToNext = false)
            newline()
        }
    return ElvisTransferLowering(
        lhsCode = lhsCode,
        preludeCode = "$header$transferStmt$indent}\n",
        derefExpr = formatElvisDerefExpr(elvisTemp),
    )
}

private fun CppExprAndStmtEmitter.renderNullReturnForType(
    retType: String?,
    indent: String,
): String? {
    val parsed = retType?.let { CppType.parseInternal(it) } ?: return null
    if (parsed.isOptional) {
        includes.addSystem("optional")
        return "${indent}return std::nullopt;\n"
    }
    if (parsed.isStdAny) {
        return "${indent}return {};\n"
    }
    return null
}

private fun isThrowCppStatement(code: String): Boolean =
    CppTokenStream.tokenizeCppCode(code).startsWithKeyword("throw")
