package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.DIRECT_ITERABLE_VALUE_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.ELEMENT_RETURNING_CONTAINER_METHODS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.INHERENTLY_MUTABLE_FACTORY_NAMES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.INHERENTLY_MUTABLE_VAL_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.INLINE_SCOPE_HELPER_NAMES
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame

internal fun CppExprAndStmtEmitter.emitInlineExpansionAsStatement(
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

internal fun CppExprAndStmtEmitter.emitSameFileInlineFunctionBody(
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

internal fun CppExprAndStmtEmitter.extractCallTypeArgs(
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

internal fun CppExprAndStmtEmitter.extractInlineCallExpressionFrame(initFrame: PacketFrame): PacketFrame? {
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

internal fun CppExprAndStmtEmitter.resolveInlineReturnDeclType(
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

internal fun CppExprAndStmtEmitter.emitUnwrappedPointeeTypeAlias(expr: String): String {
    includes.addSystem("type_traits")
    return "typename decltype([](const auto& kxs_pt) { " +
        "using KxsPt = std::remove_cvref_t<decltype(kxs_pt)>; " +
        "if constexpr (std::is_pointer_v<KxsPt>) return std::type_identity<std::remove_pointer_t<KxsPt>>{}; " +
        "else if constexpr (requires { typename KxsPt::element_type; }) return std::type_identity<typename KxsPt::element_type>{}; " +
        "else return std::type_identity<KxsPt>{}; " +
        "}($expr))::type"
}

internal fun CppExprAndStmtEmitter.copyLexicalStateTo(target: CppExprAndStmtEmitter) {
    target.inExpressionIifeDepth = inExpressionIifeDepth
    target.synthCounter = synthCounter
    for (scope in localBindings) target.localBindings.last().putAll(scope)
    for (scope in exceptionPtrScopes) target.exceptionPtrScopes.last().addAll(scope)
    for (scope in stdAnyScopes) target.stdAnyScopes.last().addAll(scope)
    for (scope in localPolyVarScopes) target.localPolyVarScopes.last().addAll(scope)
    for (scope in localTypeScopes) target.localTypeScopes.last().putAll(scope)
}

internal fun CppExprAndStmtEmitter.buildInlineScopeEmitter(
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

internal fun CppExprAndStmtEmitter.hasEarlyUnlabeledReturnInInlineBody(blockBody: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.containsUnlabeledReturn(frame: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.expandInlinedCallerLambdaCall(
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

internal fun CppExprAndStmtEmitter.appendInlineLambdaBodyStatements(
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

internal fun CppExprAndStmtEmitter.emitMemScopedStatement(
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

internal fun CppExprAndStmtEmitter.emitUsePinnedStatement(
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

internal fun CppExprAndStmtEmitter.emitAtomicLoopOrUpdateStatement(
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

internal fun CppExprAndStmtEmitter.emitExpression(exprFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.emitExprIgnoringHoist(exprFrame: PacketFrame): String {
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

internal data class InlineIifeEligibility(
    val canEmitIife: Boolean,
    val hasLambdaOwnReturn: Boolean,
)

internal data class InlineTailTypeResult(
    val simpleName: String?,
    val cppType: String?,
)

internal data class LambdaVarTypeInfo(
    val simpleName: String?,
    val cppType: String?,
    val nonNullCppType: String?,
    val typeOrInitFrame: PacketFrame?,
)

internal fun CppExprAndStmtEmitter.applyInlineLocalVarMetadata(
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

internal fun CppExprAndStmtEmitter.copyLocalMetadataTo(
    target: CppExprAndStmtEmitter,
    kotlinName: String,
) {
    resolveLocalTypeSimpleName(kotlinName)?.let { target.markLocalType(kotlinName, it) }
    if (isLocalPolyVar(kotlinName)) target.markLocalPolyVar(kotlinName)
    if (isStdAnyKotlinVar(kotlinName)) target.markStdAnyVar(kotlinName)
    if (isExceptionPtrKotlinVar(kotlinName)) target.markExceptionPtrVar(kotlinName)
}

internal fun CppExprAndStmtEmitter.extractLabelQualifierName(labelQual: PacketFrame): String? =
    FrameAstHelpers.directIdentPacket(stream, labelQual)?.unescapedName
        ?: FrameAstHelpers.firstChildFrame(stream, labelQual, KtNodeTypes.LABEL_ID)?.let { labelNode ->
            FrameAstHelpers.directIdentPacket(stream, labelNode)?.unescapedName
        }

internal fun CppExprAndStmtEmitter.extractInlineLambdaOwnLabel(
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

internal fun CppExprAndStmtEmitter.extractLambdaBodyFrame(lambdaFrameIndex: Int): PacketFrame? {
    val lambdaFrame = stream.frames.getOrNull(lambdaFrameIndex) ?: return null
    val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID) ?: lambdaFrame
    return FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID)
        ?: FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.BLOCK_ID)
        ?: fnLit
}

internal fun CppExprAndStmtEmitter.checkInlineIifeEligibility(
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

internal fun CppExprAndStmtEmitter.wrapNullableCppType(nonNullCpp: String): String =
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

internal fun CppExprAndStmtEmitter.extractFirstContainerElementProjection(typeOrCallFrame: PacketFrame): PacketFrame? {
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

internal fun CppExprAndStmtEmitter.resolveSameFileClassCppTypeName(cls: KtClassSymbol): String {
    val parentIdx = cls.enclosingClassFrameIndex ?: return cls.cppName
    val parentCls = bundle.symbols.classesByFrame[parentIdx] ?: return cls.cppName
    val hoistedName = bundle.symbols.nonInnerNestedRewrites[parentCls.kotlinName to cls.kotlinName]
    if (hoistedName != null) return hoistedName
    if (enclosingClass?.frameIndex == parentIdx) return cls.cppName
    return "${resolveSameFileClassCppTypeName(parentCls)}::${cls.cppName}"
}

internal fun CppExprAndStmtEmitter.wrapClassCppValueType(
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

internal fun CppExprAndStmtEmitter.resolveSimpleNameToValueCppType(simpleName: String): String? {
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

internal fun CppExprAndStmtEmitter.extractNullCheckVarName(condFrame: PacketFrame): Pair<String, Boolean>? {
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

internal fun CppExprAndStmtEmitter.resolveReceiverTypeOrInitFrame(
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

internal fun CppExprAndStmtEmitter.inferExprTypeInfo(
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

internal fun CppExprAndStmtEmitter.inferInlineLambdaTailType(lambdaFrameIndex: Int): InlineTailTypeResult? {
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

internal fun CppExprAndStmtEmitter.isInherentlyMutableLocalVal(
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

internal fun CppExprAndStmtEmitter.inferChannelFactoryElementTypeArg(targetCpp: String): List<String> =
    listOf("Channel", "SendChannel", "ReceiveChannel")
        .firstNotNullOfOrNull { base ->
            val rawArgs =
                extractOuterTemplateArgsForBase(targetCpp, base)
                    ?: extractOuterTemplateArgsForBase(targetCpp, "kotlinx::coroutines::channels::$base")
            rawArgs
                ?.firstOrNull()
                ?.let { listOf(it) }
        }.orEmpty()
