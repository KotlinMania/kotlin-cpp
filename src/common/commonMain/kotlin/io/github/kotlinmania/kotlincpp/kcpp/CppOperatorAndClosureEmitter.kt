package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.ASSIGN_OPS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.COMPOUND_OR_DECLARATION_STMT_NODE_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.CPP_BINARY_OPERATORS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.ELVIS_CONTROL_TRANSFER_NODE_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.STATEMENT_ONLY_NODE_TYPES
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal fun CppExprAndStmtEmitter.emitArrayAccessExpr(arrAccessFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.emitBinaryExpr(binFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.isLogicalAndBinaryFrame(frame: PacketFrame): Boolean {
    val unwrapped = unwrapTransparentFrame(frame)
    if (unwrapped.nodeTypeId != KtNodeTypes.BINARY_EXPRESSION_ID) return false
    val ch = FrameAstHelpers.childFrames(stream, unwrapped)
    return ch.size >= 3 && FrameAstHelpers.operationSymbolName(stream, ch[1]) == "&&"
}

internal fun CppExprAndStmtEmitter.isLogicalOrBinaryFrame(frame: PacketFrame): Boolean {
    val unwrapped = unwrapTransparentFrame(frame)
    if (unwrapped.nodeTypeId != KtNodeTypes.BINARY_EXPRESSION_ID) return false
    val ch = FrameAstHelpers.childFrames(stream, unwrapped)
    return ch.size >= 3 && FrameAstHelpers.operationSymbolName(stream, ch[1]) == "||"
}

internal fun CppExprAndStmtEmitter.emitBinaryWithTypeExpr(castFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.emitIsExpr(isFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.emitRuntimeIsCheck(
    subjExpr: String,
    cppType: String,
): String {
    includes.needsCastHelpersDef = true
    return "kxs_is_instance<$cppType>($subjExpr)"
}

internal fun CppExprAndStmtEmitter.emitPrefixExpr(prefixFrame: PacketFrame): String {
    val opRef = FrameAstHelpers.firstChildFrame(stream, prefixFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
    val operand =
        FrameAstHelpers
            .childFrames(stream, prefixFrame)
            .lastOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
    val op = opRef?.let { FrameAstHelpers.operationSymbolName(stream, it) }.orEmpty()
    val inner = operand?.let { emitExpression(it) }.orEmpty()
    return "$op$inner"
}

internal fun CppExprAndStmtEmitter.emitPostfixExpr(postfixFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.containsReferenceNamed(
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

internal fun CppExprAndStmtEmitter.canEmitTernaryIf(ifFrame: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.emitTernaryIfCore(ifFrame: PacketFrame): String {
    val condWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.CONDITION_ID)!!
    val thenWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.THEN_ID)!!
    val elseWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.ELSE_ID)!!
    val cond = FrameAstHelpers.childFrames(stream, condWrap).first()
    val thenChild = FrameAstHelpers.childFrames(stream, thenWrap).first()
    val elseChild = FrameAstHelpers.childFrames(stream, elseWrap).first()
    return "${emitExpression(cond)} ? ${emitExpression(thenChild)} : ${emitExpression(elseChild)}"
}

internal fun CppExprAndStmtEmitter.emitExpressionIf(ifFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.containsUnnestedUnlabeledBreak(frame: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.canEmitCppSwitch(entries: List<PacketFrame>): Boolean {
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

internal fun CppExprAndStmtEmitter.isSwitchConstantExpr(expr: PacketFrame): Boolean =
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

internal fun CppExprAndStmtEmitter.isEnumOrAllCapsName(name: String): Boolean {
    if (name.isEmpty()) return false
    if (enclosingClass?.enumEntries?.any { it.kotlinName == name } == true) return true
    val allClasses = bundle.symbols.classesByName.values
    if (allClasses.any { cls -> cls.enumEntries.any { it.kotlinName == name } }) return true
    return name.any { it.isLetter() } && name.all { it.isUpperCase() || it.isDigit() || it == '_' }
}

internal fun CppExprAndStmtEmitter.isElvisControlTransferTarget(frame: PacketFrame): Boolean {
    val unwrapped = unwrapTransparentFrame(frame)
    return unwrapped.nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES ||
        doesBranchAlwaysTransferControl(unwrapped)
}

internal fun CppExprAndStmtEmitter.doesBranchAlwaysTransferControl(branchFrame: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.isDirectIdentityOperand(expr: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.isKnownPointerOrAnyBinding(kotlinName: String): Boolean {
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

internal fun CppExprAndStmtEmitter.emitLambdaClosureExpr(
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

internal fun CppExprAndStmtEmitter.canEmitLambdaTailReturn(stmtFrame: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.containsBareVoidReturnInLambda(frame: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.canEmitSingleLambdaExprReturn(stmtFrame: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.collectInlineElvisControlTransfers(
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

internal fun CppExprAndStmtEmitter.resolveCallableRefMemberAccess(
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

internal fun CppExprAndStmtEmitter.emitCallableRefExpr(refExpr: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.emitAtomicOpExpression(aop: AtomicOperationSite): String {
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

internal fun CppExprAndStmtEmitter.renderAtomicTargetLvalue(aop: AtomicOperationSite): String {
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

internal fun CppExprAndStmtEmitter.emitCInteropOpExpression(cop: CInteropExprSite): String {
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

internal fun CppExprAndStmtEmitter.isRawPointerCppType(cppType: String): Boolean =
    cppType.endsWith("*") ||
        (cppType.startsWith("CF") && cppType.endsWith("Ref")) ||
        cppType in setOf("dispatch_queue_t", "dispatch_block_t", "dispatch_object_t", "dispatch_source_t")

internal fun CppExprAndStmtEmitter.inferControlFlowResultType(cfFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.collectBranchValueLeaves(
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

internal fun CppExprAndStmtEmitter.referencesImplicitIt(root: PacketFrame): Boolean {
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

internal fun CppExprAndStmtEmitter.extractCalleeSimpleName(callFrame: PacketFrame): String {
    val ref = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
    return if (ref != null) {
        FrameAstHelpers.directIdentPacket(stream, ref)?.unescapedName.orEmpty()
    } else {
        FrameAstHelpers.directIdentPacket(stream, callFrame)?.unescapedName.orEmpty()
    }
}

internal fun CppExprAndStmtEmitter.formatIndentedRawCpp(
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

internal fun CppExprAndStmtEmitter.isEnclosingClassProperty(kotlinName: String): Boolean {
    var cur: KtClassSymbol? = enclosingClass
    while (cur != null) {
        if (cur.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == kotlinName }) return true
        if (cur.memberPropertyFrameIndices.any { bundle.symbols.propertiesByFrame[it]?.kotlinName == kotlinName }) return true
        if (!cur.isInner) break
        cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
    }
    return false
}

internal fun CppExprAndStmtEmitter.resolvePrimarySuperCppType(superFrame: PacketFrame? = null): String =
    resolvePrimarySuperSimpleAndCpp(superFrame).second

internal fun CppExprAndStmtEmitter.resolvePrimarySuperSimpleAndCpp(superFrame: PacketFrame? = null): Pair<String?, String> {
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

internal fun CppExprAndStmtEmitter.isIdentChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

internal fun CppExprAndStmtEmitter.containsUnquotedStandaloneIdent(
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

internal fun CppExprAndStmtEmitter.replaceUnquotedStandaloneIdent(
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

internal fun CppExprAndStmtEmitter.isVerbatimCIdentifier(name: String): Boolean =
    name.startsWith("CF") ||
        name.startsWith("kCF") ||
        name.startsWith("CFAbsolute") ||
        name.startsWith("dispatch_") ||
        name.startsWith("DISPATCH_") ||
        name.startsWith("pthread_") ||
        name.startsWith("PTHREAD_") ||
        name.startsWith("mach_") ||
        name.startsWith("objc_")

internal fun CppExprAndStmtEmitter.recordExternalCHeaderForIdentifier(name: String) {
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

internal fun CppExprAndStmtEmitter.renderClassLikeMethodParams(
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
