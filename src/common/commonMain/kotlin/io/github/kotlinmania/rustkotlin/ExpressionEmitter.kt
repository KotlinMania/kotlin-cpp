package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FieldValue
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.RangeLimits
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.gen.Visit

internal fun KotlinJ2KEmitter.renderExpr(expr: Expr, indent: String, selfTypeName: String?): String {
    return when (expr) {
        is Expr.Lit -> renderLit(expr.lit)
        is Expr.Path -> renderExprPath(expr, selfTypeName)
        is Expr.Binary -> {
            val isBitwiseOp =
                when (expr.op) {
                    is BinOp.BitAnd, is BinOp.BitOr, is BinOp.BitXor,
                    is BinOp.BitAndAssign, is BinOp.BitOrAssign, is BinOp.BitXorAssign,
                    -> true
                    else -> false
                }
            val isCmpOp =
                when (expr.op) {
                    is BinOp.Eq, is BinOp.Ne, is BinOp.Lt, is BinOp.Le, is BinOp.Gt, is BinOp.Ge -> true
                    else -> false
                }
            val isBoolOrCmpOp =
                when (expr.op) {
                    is BinOp.Eq, is BinOp.Ne, is BinOp.Lt, is BinOp.Le, is BinOp.Gt, is BinOp.Ge,
                    is BinOp.And, is BinOp.Or,
                    -> true
                    else -> false
                }
            val isShiftOp =
                when (expr.op) {
                    is BinOp.Shl, is BinOp.Shr, is BinOp.ShlAssign, is BinOp.ShrAssign -> true
                    else -> false
                }
            val rawLeftOperand = expr.left
            val leftOperand =
                if (isCompoundAssignOp(expr.op) && rawLeftOperand is Expr.Unary && rawLeftOperand.op is UnOp.Deref) {
                    rawLeftOperand.expr
                } else {
                    rawLeftOperand
                }
            val rightOperand = expr.right
            val savedKind = currentExpectedNumericKind
            val leftInferredKind = inferExprNumericKind(leftOperand, selfTypeName)
            val rightInferredKind = inferExprNumericKind(rightOperand, selfTypeName)
            val binarySharedKind = leftInferredKind ?: rightInferredKind ?: savedKind
            val rawLeft =
                try {
                    currentExpectedNumericKind =
                        when {
                            isCmpOp -> rightInferredKind
                            isBoolOrCmpOp -> null
                            isShiftOp -> leftInferredKind ?: savedKind
                            else -> binarySharedKind
                        }
                    if (isBitwiseOp && leftOperand is Expr.Unary && leftOperand.op is UnOp.NotOp) {
                        renderBitwiseComplementOperand(leftOperand.expr, indent, selfTypeName)
                    } else {
                        renderExpr(leftOperand, indent, selfTypeName)
                    }
                } finally {
                    currentExpectedNumericKind = savedKind
                }
            val rawRight =
                try {
                    currentExpectedNumericKind =
                        when {
                            isCmpOp -> leftInferredKind
                            isShiftOp -> NumericKind.I32
                            isBoolOrCmpOp -> null
                            else -> binarySharedKind
                        }
                    if (isBitwiseOp && rightOperand is Expr.Unary && rightOperand.op is UnOp.NotOp) {
                        renderBitwiseComplementOperand(rightOperand.expr, indent, selfTypeName)
                    } else {
                        renderExpr(rightOperand, indent, selfTypeName)
                    }
                } finally {
                    currentExpectedNumericKind = savedKind
                }
            val left =
                if (isCmpOp && rightInferredKind != null && isNarrowComparisonLiteralOperand(leftOperand, rightInferredKind)) {
                    coerceRenderedNarrowLiteral(leftOperand, rawLeft, rightInferredKind)
                } else {
                    rawLeft
                }
            val right =
                if (isShiftOp && rightInferredKind != null && rightInferredKind != NumericKind.I32) {
                    val recv = if (isAtomicPostfixReceiver(rightOperand, rawRight)) rawRight else "($rawRight)"
                    "$recv.toInt()"
                } else if (isCmpOp && leftInferredKind != null && isNarrowComparisonLiteralOperand(rightOperand, leftInferredKind)) {
                    coerceRenderedNarrowLiteral(rightOperand, rawRight, leftInferredKind)
                } else {
                    rawRight
                }
            val compoundBitOp =
                when (expr.op) {
                    is BinOp.BitXorAssign -> "xor"
                    is BinOp.BitAndAssign -> "and"
                    is BinOp.BitOrAssign -> "or"
                    is BinOp.ShlAssign -> "shl"
                    is BinOp.ShrAssign -> "shr"
                    else -> null
                }
            if (compoundBitOp != null) {
                val safeRight = if (expr.right is Expr.Binary || expr.right is Expr.Cast) "($right)" else right
                "$left = $left $compoundBitOp $safeRight"
            } else {
                val op = renderBinOp(expr.op)
                "$left $op $right"
            }
        }
        is Expr.Unary -> {
            when (expr.op) {
                is UnOp.NotOp -> {
                    if (isKnownIntegerBitmaskExpr(expr.expr)) {
                        renderBitwiseComplementOperand(expr.expr, indent, selfTypeName)
                    } else {
                        val savedKind = currentExpectedNumericKind
                        currentExpectedNumericKind = null
                        try {
                            "!${renderExpr(expr.expr, indent, selfTypeName)}"
                        } finally {
                            currentExpectedNumericKind = savedKind
                        }
                    }
                }
                is UnOp.Neg -> {
                    val savedKind = currentExpectedNumericKind
                    if (savedKind == NumericKind.U8 ||
                        savedKind == NumericKind.U16 ||
                        savedKind == NumericKind.U32 ||
                        savedKind == NumericKind.U64 ||
                        savedKind == NumericKind.U128
                    ) {
                        currentExpectedNumericKind = null
                    }
                    try {
                        "-${renderExpr(expr.expr, indent, selfTypeName)}"
                    } finally {
                        currentExpectedNumericKind = savedKind
                    }
                }
                is UnOp.Deref -> {
                    val inner = expr.expr
                    if (currentEnclosingFunctionName == "clone" &&
                        inner is Expr.Path &&
                        inner.qself == null &&
                        inner.path.isIdent("self") &&
                        selfTypeName != null
                    ) {
                        val baseSelf = selfTypeName.substringBefore('<').trim()
                        val fields = inspector.structFieldNamesByType[baseSelf]
                        if (fields != null) {
                            return "$baseSelf(${fields.joinToString(", ") { "this.$it" }})"
                        }
                    }
                    val renderedInner = renderExpr(inner, indent, selfTypeName)
                    if (renderedInner in currentNullableLocals) "$renderedInner!!" else renderedInner
                }
            }
        }
        is Expr.Reference -> renderExpr(expr.expr, indent, selfTypeName)
        is Expr.RawAddr -> renderExpr(expr.expr, indent, selfTypeName)
        is Expr.Paren -> {
            val innerExpr = expr.expr
            val rendered = renderExpr(innerExpr, indent, selfTypeName)
            if (innerExpr is Expr.Unary && innerExpr.op is UnOp.Deref) {
                rendered
            } else {
                "($rendered)"
            }
        }
        is Expr.Group -> renderExpr(expr.expr, indent, selfTypeName)
        is Expr.Try -> {
            val inner = renderExpr(expr.expr, indent, selfTypeName)
            val retKw = currentClosureLabel?.let { "return@$it" } ?: "return"
            val enclosingRet = currentEnclosingReturnRenderedType
            if (enclosingRet != null && enclosingRet.endsWith("?") && !enclosingRet.startsWith("Result<")) {
                "($inner ?: $retKw null)"
            } else {
                "$inner.getOrElse { $retKw Result.failure(it) }"
            }
        }
        is Expr.Await -> renderExpr(expr.base, indent, selfTypeName)
        is Expr.Cast -> {
            val savedKind = currentExpectedNumericKind
            currentExpectedNumericKind = null
            val inner =
                try {
                    renderExpr(expr.expr, indent, selfTypeName)
                } finally {
                    currentExpectedNumericKind = savedKind
                }
            val target =
                if (expr.ty is SynType.Infer && savedKind != null) {
                    when (savedKind) {
                        NumericKind.I8 -> "Byte"
                        NumericKind.U8 -> "UByte"
                        NumericKind.I16 -> "Short"
                        NumericKind.U16 -> "UShort"
                        NumericKind.I32 -> "Int"
                        NumericKind.U32 -> "UInt"
                        NumericKind.I64 -> "Long"
                        NumericKind.U64 -> "ULong"
                        NumericKind.I128 -> {
                            usesI128 = true
                            "I128"
                        }
                        NumericKind.U128 -> {
                            usesU128 = true
                            "U128"
                        }
                        NumericKind.F32 -> "Float"
                        NumericKind.F64 -> "Double"
                    }
                } else {
                    renderType(expr.ty, selfTypeName)
                }
            renderCastExpression(inner, target, expr.expr)
        }
        is Expr.Call -> {
            val rawArgs = expr.args.toList()
            val nonUnitArgs = rawArgs.filterNot { it is Expr.Tuple && it.elems.isEmpty() }
            if (expr.func is Expr.Path && (expr.func as Expr.Path).qself == null && nonUnitArgs.size == 1) {
                val lastFuncSeg = (expr.func as Expr.Path).path.segments.last()
                if (lastFuncSeg?.ident?.toString() == "transmute") {
                    val tfTypes =
                        (lastFuncSeg.arguments as? PathArguments.AngleBracketed)
                            ?.args
                            ?.toList()
                            ?.mapNotNull { (it as? GenericArgument.TypeArg)?.type }
                            .orEmpty()
                    if (tfTypes.size == 2 && renderType(tfTypes[0], selfTypeName) == renderType(tfTypes[1], selfTypeName)) {
                        return renderExpr(nonUnitArgs[0], indent, selfTypeName)
                    }
                }
            }
            val callee = renderCalleeExpr(expr.func, indent, selfTypeName)
            when {
                nonUnitArgs.isEmpty() &&
                    (
                        callee == "null" ||
                            callee == "nullMut" ||
                            callee == "null_mut" ||
                            callee == "ptr.null" ||
                            callee == "ptr.nullMut" ||
                            callee == "ptr.null_mut" ||
                            callee.endsWith(".ptr.null") ||
                            callee.endsWith(".ptr.nullMut") ||
                            callee.endsWith(".ptr.null_mut")
                    ) ->
                    if (headerContext.rawAbiMode) "0L" else "null"
                nonUnitArgs.isEmpty() &&
                    (
                        callee == "MaybeUninit.zeroed" ||
                            callee == "MaybeUninit.uninit" ||
                            callee.endsWith(".MaybeUninit.zeroed") ||
                            callee.endsWith(".MaybeUninit.uninit") ||
                            callee == "zeroed" ||
                            callee == "mem.zeroed" ||
                            callee.endsWith(".mem.zeroed")
                    ) ->
                    if (headerContext.rawAbiMode) {
                        val retT = currentEnclosingReturnRenderedType
                        if (retT != null) renderFallbackDefaultForRenderedType(retT) else "0L"
                    } else {
                        "null"
                    }
                (callee == "Padding.uninit" || callee.endsWith(".Padding.uninit")) && nonUnitArgs.isEmpty() ->
                    renderIntLiteral("0", "")
                (callee == "Padding.new" || callee.endsWith(".Padding.new")) && nonUnitArgs.size == 1 ->
                    renderTypedNumericInitializer(nonUnitArgs[0], currentExpectedNumericKind, indent, selfTypeName)
                (callee == "typeName" || callee == "type_name" || callee.endsWith(".any.typeName") || callee.endsWith(".any.type_name")) &&
                    nonUnitArgs.isEmpty() ->
                    renderKotlinDoubleQuotedString(selfTypeName?.substringBefore('<')?.trim() ?: "Any")
                nonUnitArgs.isEmpty() &&
                    (
                        callee == "sizeOf" ||
                            callee == "size_of" ||
                            callee == "mem.sizeOf" ||
                            callee == "mem.size_of" ||
                            callee.endsWith(".mem.sizeOf") ||
                            callee.endsWith(".mem.size_of") ||
                            callee == "alignOf" ||
                            callee == "align_of" ||
                            callee == "mem.alignOf" ||
                            callee == "mem.align_of" ||
                            callee.endsWith(".mem.alignOf") ||
                            callee.endsWith(".mem.align_of")
                    ) ->
                    if (headerContext.rawAbiMode && currentExpectedNumericKind == null) "8uL" else renderIntLiteral("8", "")
                nonUnitArgs.size == 1 &&
                    (
                        callee == "sizeOfVal" ||
                            callee == "size_of_val" ||
                            callee == "mem.sizeOfVal" ||
                            callee == "mem.size_of_val" ||
                            callee.endsWith(".mem.sizeOfVal") ||
                            callee.endsWith(".mem.size_of_val")
                    ) ->
                    if (headerContext.rawAbiMode && currentExpectedNumericKind == null) "8uL" else renderIntLiteral("8", "")
                callee == "Some" && "Some" !in inspector.declaredTypeNames && nonUnitArgs.size == 1 -> {
                    val enclosingOpt = currentEnclosingReturnRenderedType?.takeIf { it.endsWith("?") }?.removeSuffix("?")
                    val optKind = enclosingOpt?.let(this::resolveNumericKind) ?: currentExpectedNumericKind
                    val savedParseTarget = currentExpectedParseTargetType
                    currentExpectedParseTargetType = savedParseTarget?.removeSuffix("?")
                    try {
                        renderTypedNumericInitializer(nonUnitArgs[0], optKind, indent, selfTypeName)
                    } finally {
                        currentExpectedParseTargetType = savedParseTarget
                    }
                }
                callee == "Some" && "Some" !in inspector.declaredTypeNames && rawArgs.size == 1 && nonUnitArgs.isEmpty() ->
                    "Unit"
                (callee == "Ok" || callee == "Result.Ok") && "Ok" !in inspector.declaredTypeNames && rawArgs.size == 1 && nonUnitArgs.isEmpty() ->
                    "Result.success(Unit)"
                (callee == "Ok" || callee == "Result.Ok") && "Ok" !in inspector.declaredTypeNames && nonUnitArgs.size == 1 -> {
                    val enclosingRes =
                        currentEnclosingReturnRenderedType
                            ?.takeIf { it.startsWith("Result<") && it.endsWith(">") }
                            ?.removePrefix("Result<")
                            ?.removeSuffix(">")
                            ?.trim()
                    val okKind = enclosingRes?.let(this::resolveNumericKind) ?: currentExpectedNumericKind
                    "Result.success(${renderTypedNumericInitializer(nonUnitArgs[0], okKind, indent, selfTypeName)})"
                }
                (callee == "Err" || callee == "Result.Err") && "Err" !in inspector.declaredTypeNames && nonUnitArgs.size == 1 ->
                    "Result.failure(${renderExpr(nonUnitArgs[0], indent, selfTypeName)})"
                (callee == "Ok" || callee.endsWith(".Ok")) && rawArgs.size == 1 && nonUnitArgs.isEmpty() ->
                    "$callee(Unit)"
                nonUnitArgs.size == 1 &&
                    (
                        (callee == "Box.new" && "Box" !in inspector.declaredTypeNames) ||
                            (callee == "Rc.new" && "Rc" !in inspector.declaredTypeNames) ||
                            (callee == "Arc.new" && "Arc" !in inspector.declaredTypeNames) ||
                            (callee == "NoDrop.new" && "NoDrop" !in inspector.declaredTypeNames) ||
                            (callee == "ManuallyDrop.new" && "ManuallyDrop" !in inspector.declaredTypeNames) ||
                            (callee == "ptr.read" && "ptr" !in inspector.declaredTypeNames)
                    ) ->
                    renderExpr(nonUnitArgs[0], indent, selfTypeName)
                callee == "Vec.new" && nonUnitArgs.isEmpty() && "Vec" !in inspector.declaredTypeNames ->
                    "mutableListOf()"
                callee == "Vec.withCapacity" && nonUnitArgs.size == 1 && "Vec" !in inspector.declaredTypeNames ->
                    "ArrayList(${renderExpr(nonUnitArgs[0], indent, selfTypeName)})"
                callee == "Vec.from" && nonUnitArgs.size == 1 && "Vec" !in inspector.declaredTypeNames ->
                    "${renderExpr(nonUnitArgs[0], indent, selfTypeName)}.toMutableList()"
                callee == "VecDeque.from" && nonUnitArgs.size == 1 && "VecDeque" !in inspector.declaredTypeNames ->
                    "ArrayDeque(${renderExpr(nonUnitArgs[0], indent, selfTypeName)})"
                (callee == "empty" || callee == "iter.empty") && nonUnitArgs.isEmpty() && "empty" !in topLevelSignatureCounts ->
                    "ArrayDeque()"
                (callee == "Debug.fmt" || callee == "Display.fmt") && nonUnitArgs.size == 2 ->
                    "${renderExpr(nonUnitArgs[1], indent, selfTypeName)}.append((${renderExpr(nonUnitArgs[0], indent, selfTypeName)}).toString())"
                else -> {
                    val calleeLookupKeys =
                        buildList {
                            add(callee)
                            val nsPrefix = currentNamespacePrefix()
                            if (nsPrefix != null && callee.startsWith("$nsPrefix.")) {
                                add(callee.removePrefix("$nsPrefix."))
                            }
                            val parts = callee.split('.')
                            if (parts.size >= 2) {
                                add("${parts[parts.size - 2]}.${parts.last()}")
                            }
                            if (parts.size >= 1) {
                                val curSub = currentSubpackageString()
                                if (curSub.isNotEmpty()) {
                                    add("$curSub.$callee")
                                }
                            }
                        }.distinct()
                    val calleeParamNumericKinds =
                        calleeLookupKeys
                            .firstNotNullOfOrNull { k ->
                                currentFunctionParamNumericKinds[k]?.takeIf { it.size == nonUnitArgs.size }
                            }
                    val savedKind = currentExpectedNumericKind
                    val savedParseTarget = currentExpectedParseTargetType
                    currentExpectedNumericKind = null
                    currentExpectedParseTargetType = null
                    val renderedArgList =
                        try {
                            nonUnitArgs.mapIndexed { argIdx, argExpr ->
                                val paramKind = calleeParamNumericKinds?.get(argIdx)
                                if (paramKind != null) {
                                    renderTypedNumericInitializer(argExpr, paramKind, indent, selfTypeName)
                                } else {
                                    currentExpectedNumericKind = null
                                    renderExpr(argExpr, indent, selfTypeName)
                                }
                            }
                        } finally {
                            currentExpectedNumericKind = savedKind
                            currentExpectedParseTargetType = savedParseTarget
                        }
                    val args = renderedArgList.joinToString(", ")
                    if (renderedArgList.size == 1 && renderedArgList[0] == "this" && '.' in callee) {
                        val ownerPart = callee.substringBeforeLast('.')
                        val methodPart = callee.substringAfterLast('.')
                        if (ownerPart in inspector.attachableTypeNames &&
                            methodPart in inspector.instanceMethodsByType[ownerPart].orEmpty()
                        ) {
                            return "this.$methodPart()"
                        }
                    }
                    val forwardedWitnesses =
                        when {
                            callee.endsWith(".parseTerminatedWith") && renderedArgList.size == 2 -> {
                                currentTypeParamWitnessCalls.entries
                                    .filter { it.key.second == "parse" && it.value !in renderedArgList }
                                    .map { it.value }
                                    .distinct()
                            }
                            callee.endsWith(".parseSeparatedNonemptyWith") && renderedArgList.size == 2 -> {
                                val peeks =
                                    currentTypeParamWitnessCalls.entries
                                        .filter { it.key.second == "peek" && it.value !in renderedArgList }
                                        .map { it.value }
                                        .distinct()
                                val parses =
                                    currentTypeParamWitnessCalls.entries
                                        .filter { it.key.second == "parse" && it.value !in renderedArgList }
                                        .map { it.value }
                                        .distinct()
                                peeks + parses
                            }
                            callee.endsWith(".parseTerminated") && renderedArgList.size == 1 -> {
                                currentTypeParamWitnessCalls.entries
                                    .filter { it.key.second == "parse" && it.value !in renderedArgList }
                                    .map { it.value }
                                    .distinct()
                            }
                            callee.endsWith(".parseSeparatedNonempty") && renderedArgList.size == 1 -> {
                                currentTypeParamWitnessCalls.values
                                    .filter { it !in renderedArgList }
                                    .distinct()
                            }
                            else -> emptyList()
                        }
                    val fullArgs =
                        if (forwardedWitnesses.isEmpty()) {
                            args
                        } else if (args.isEmpty()) {
                            forwardedWitnesses.joinToString(", ")
                        } else {
                            "$args, ${forwardedWitnesses.joinToString(", ")}"
                        }
                    val rewrittenTraitElemCallee = resolveContainerElementTraitCallee(callee)
                    val callExpr =
                        if (rewrittenTraitElemCallee != null) {
                            "$rewrittenTraitElemCallee($fullArgs)"
                        } else if (callee.endsWith(".default()") && nonUnitArgs.isNotEmpty()) {
                            "${callee.removeSuffix(".default()")}.from($fullArgs)"
                        } else {
                            "$callee($fullArgs)"
                        }
                    if ((callee == "strlen" || callee.endsWith(".strlen")) &&
                        (currentExpectedNumericKind == NumericKind.I32 || currentEnclosingReturnRenderedType == "Int")
                    ) {
                        "$callExpr.toInt()"
                    } else {
                        callExpr
                    }
                }
            }
        }
        is Expr.MethodCall -> {
            val methodEntryParseTarget = currentExpectedParseTargetType
            val prevRecvParseTarget = currentExpectedParseTargetType
            currentExpectedParseTargetType = null
            val receiver =
                try {
                    renderExpr(expr.receiver, indent, selfTypeName)
                } finally {
                    currentExpectedParseTargetType = prevRecvParseTarget
                }
            val rawMethod = expr.method.toString()
            val nonUnitArgs = expr.args.toList().filterNot { it is Expr.Tuple && it.elems.isEmpty() }
            if (rawMethod == "is_null" && nonUnitArgs.isEmpty()) {
                return if (headerContext.rawAbiMode) "($receiver == 0L)" else "($receiver == null)"
            }
            if ((rawMethod == "as_ptr" || rawMethod == "as_mut_ptr") && nonUnitArgs.isEmpty()) {
                if (headerContext.rawAbiMode) {
                    return "0L"
                }
                val arrElemKind = inferArrayElementNumericKind(expr.receiver, selfTypeName)
                if (arrElemKind == NumericKind.I8) {
                    return "$receiver.decodeToString().substringBefore('\\u0000')"
                }
                if (arrElemKind != null) {
                    usesOpaquePointer = true
                    return "COpaquePointer($receiver.hashCode().toLong())"
                }
                return receiver
            }
            if (rawMethod == "cast" && nonUnitArgs.isEmpty() && expr.turbofish != null) {
                if (headerContext.rawAbiMode) {
                    return receiver
                }
                val castTarget =
                    expr.turbofish!!
                        .args
                        .toList()
                        .firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type?.let { ty -> renderType(ty, selfTypeName) } }
                if (castTarget != null) {
                    val cleanCastTarget = castTarget.removeSuffix("?")
                    return when {
                        cleanCastTarget == "COpaquePointer" -> {
                            usesOpaquePointer = true
                            if (currentTypedLocals[receiver] == "COpaquePointer?") {
                                receiver
                            } else {
                                "($receiver)?.let { COpaquePointer(it.hashCode().toLong()) }"
                            }
                        }
                        cleanCastTarget == "String" -> "($receiver)?.toString()"
                        resolveNumericKind(cleanCastTarget) != null -> "null"
                        else -> "(if ($receiver == null) null else $cleanCastTarget())"
                    }
                }
            }
            if (rawMethod == "len" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val localTy = currentTypedLocals[receiver]
                if (localTy != null && (localTy.startsWith("List<") || localTy in PRIMITIVE_ARRAY_TYPES)) {
                    return "$receiver.size"
                }
                if (inferArrayElementNumericKind(expr.receiver, selfTypeName) != null) {
                    return "$receiver.size"
                }
            }
            if (rawMethod == "is_none" && nonUnitArgs.isEmpty() && !isKnownNonNullableNonOptionReceiver(expr.receiver, receiver, selfTypeName)) {
                return "$receiver == null"
            }
            if (rawMethod == "is_some" && nonUnitArgs.isEmpty() && !isKnownNonNullableNonOptionReceiver(expr.receiver, receiver, selfTypeName)) {
                return "$receiver != null"
            }
            if (rawMethod == "as_bytes" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvAtom = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                return if (headerContext.rawAbiMode || headerContext.finalClasses) {
                    "$recvAtom.encodeToByteArray().asUByteArray()"
                } else {
                    "$recvAtom.encodeToByteArray().map { it.toUByte() }"
                }
            }
            if ((rawMethod == "to_be_bytes" || rawMethod == "to_le_bytes" || rawMethod == "to_ne_bytes") &&
                nonUnitArgs.isEmpty() &&
                expr.turbofish == null
            ) {
                val recvKind = inferExprNumericKind(expr.receiver, selfTypeName)
                if (recvKind != null) {
                    return renderIntToBytesMethodCall(receiver, expr.receiver, recvKind, rawMethod)
                }
            }
            if (rawMethod == "id" &&
                nonUnitArgs.isEmpty() &&
                (receiver == "thread.current()" || receiver.endsWith(".thread.current()"))
            ) {
                usesCurrentThreadToken = true
                return "currentThreadBoundToken()"
            }
            if (rawMethod == "debug_list" && nonUnitArgs.isEmpty()) {
                return "object { val entries = mutableListOf<Any?>(); fun entry(v: Any?) = apply { entries.add(v) }; fun entries(iter: Iterable<*>) = apply { for (e in iter) entries.add(e) }; fun finish() { $receiver.append(entries.joinToString(\", \", \"[\", \"]\")) } }"
            }
            if (rawMethod == "debug_set" && nonUnitArgs.isEmpty()) {
                return "object { val entries = mutableListOf<Any?>(); fun entry(v: Any?) = apply { entries.add(v) }; fun entries(iter: Iterable<*>) = apply { for (e in iter) entries.add(e) }; fun finish() { $receiver.append(entries.joinToString(\", \", \"{\", \"}\")) } }"
            }
            if (rawMethod == "debug_map" && nonUnitArgs.isEmpty()) {
                return "object { val entries = mutableListOf<String>(); fun entry(k: Any?, v: Any?) = apply { entries.add(\"\$k: \$v\") }; fun finish() { $receiver.append(entries.joinToString(\", \", \"{\", \"}\")) } }"
            }
            if (rawMethod == "pop_front" && nonUnitArgs.isEmpty()) {
                return "$receiver.removeFirstOrNull()"
            }
            if ((rawMethod == "as_deref" || rawMethod == "as_deref_mut" || rawMethod == "as_ref" || rawMethod == "as_mut") &&
                nonUnitArgs.isEmpty() &&
                expr.turbofish == null
            ) {
                return receiver
            }
            val isDequeIterReceiver = isDequeIterFieldExpr(expr.receiver, selfTypeName) || receiver in currentDequeLocals
            if (isDequeIterReceiver && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                when (rawMethod) {
                    "next" -> return "$receiver.removeFirstOrNull()"
                    "next_back" -> return "$receiver.removeLastOrNull()"
                    "len" -> return "$receiver.size"
                    "clone" -> return "ArrayDeque($receiver)"
                }
            }
            val isVecReceiver = isMutableVecExpr(expr.receiver, receiver, selfTypeName)
            if (isVecReceiver && expr.turbofish == null) {
                when {
                    rawMethod == "pop" && nonUnitArgs.isEmpty() -> return "$receiver.removeLastOrNull()"
                    rawMethod == "len" && nonUnitArgs.isEmpty() -> return "$receiver.size"
                    (rawMethod == "into_iter" || rawMethod == "iter" || rawMethod == "iter_mut") && nonUnitArgs.isEmpty() ->
                        return "ArrayDeque($receiver)"
                    rawMethod == "clone" && nonUnitArgs.isEmpty() -> return "$receiver.toMutableList()"
                    rawMethod == "last_mut" && nonUnitArgs.isEmpty() -> return "$receiver.lastOrNull()"
                    rawMethod == "first_mut" && nonUnitArgs.isEmpty() -> return "$receiver.firstOrNull()"
                }
            }
            if (rawMethod == "into_iter" && nonUnitArgs.isEmpty() && expr.turbofish == null && receiver in currentIterableLocals) {
                return "$receiver.iterator()"
            }
            if (rawMethod == "clone" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                val recvTy = currentTypedLocals[receiver]
                if (recvTy != null) {
                    currentTypeParamWitnessCalls[recvTy to "clone"]?.let { cloneFn ->
                        return "$cloneFn($receiver)"
                    }
                }
            }
            val turbofish = expr.turbofish
            if (rawMethod == "parse" && nonUnitArgs.isEmpty() && turbofish != null && turbofish.args.size == 1) {
                val tfGa = turbofish.args.first() as? GenericArgument.TypeArg
                if (tfGa != null) {
                    val renderedTfTy = renderType(tfGa.type, selfTypeName).trim()
                    if (renderedTfTy.endsWith("?")) {
                        val optInner = renderedTfTy.removeSuffix("?").trim()
                        val peekTok = resolveOptionalSynParsePeekToken(optInner)
                        if (peekTok != null &&
                            optInner !in activeTypeParamNames &&
                            optInner !in KOTLIN_STDLIB_SHADOWED_IMPORTS
                        ) {
                            return renderOptionalSynParseExpr(receiver, peekTok, optInner)
                        }
                    } else {
                        val recvTyBase =
                            (currentTypedLocals[receiver] ?: inferExprRenderedType(expr.receiver, selfTypeName))
                                ?.removeSuffix("?")
                                ?.substringAfterLast('.')
                                ?.trim()
                        if ((recvTyBase == "ParseStream" || recvTyBase == "ParseBuffer") &&
                            renderedTfTy.isNotEmpty() &&
                            renderedTfTy != "TokenStream" &&
                            renderedTfTy !in activeTypeParamNames &&
                            renderedTfTy !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
                            renderedTfTy.all { it.isLetterOrDigit() || it == '_' || it == '.' }
                        ) {
                            return "$receiver.parse($renderedTfTy::parse)"
                        }
                    }
                }
            }
            if (rawMethod == "parse" && nonUnitArgs.isEmpty() && expr.turbofish == null) {
                if (methodEntryParseTarget?.endsWith("?") == true) {
                    val optInner = methodEntryParseTarget.removeSuffix("?").trim()
                    val peekTok = resolveOptionalSynParsePeekToken(optInner)
                    if (peekTok != null &&
                        optInner !in activeTypeParamNames &&
                        optInner !in KOTLIN_STDLIB_SHADOWED_IMPORTS
                    ) {
                        return renderOptionalSynParseExpr(receiver, peekTok, optInner)
                    }
                }
                val nonNullParseTarget = methodEntryParseTarget?.takeIf { !it.endsWith("?") }
                val matchedParseWitness =
                    (nonNullParseTarget?.let { currentTypeParamWitnessCalls[it to "parse"] })
                        ?: currentTypeParamWitnessCalls.entries
                            .filter { it.key.second == "parse" }
                            .map { it.value }
                            .distinct()
                            .singleOrNull()
                if (matchedParseWitness != null) {
                    return "$receiver.parse($matchedParseWitness)"
                }
                val concreteTarget =
                    nonNullParseTarget?.takeIf { t ->
                        t.isNotEmpty() &&
                            t != "TokenStream" &&
                            t !in activeTypeParamNames &&
                            t !in KOTLIN_STDLIB_SHADOWED_IMPORTS &&
                            t.all { it.isLetterOrDigit() || it == '_' || it == '.' }
                    }
                if (concreteTarget != null) {
                    return "$receiver.parse($concreteTarget::parse)"
                }
            }
            val isEscapedDoubleUnderscoreSelfCall =
                rawMethod.startsWith("__") && rustMethodIdentToKotlin(rawMethod) == currentEnclosingFunctionName
            if (isEscapedDoubleUnderscoreSelfCall && rawMethod == "__span") {
                usesQuoteUnderlyingSpan = true
            }
            val method =
                if (isEscapedDoubleUnderscoreSelfCall) {
                    "__${rustMethodIdentToKotlin(rawMethod.removePrefix("__"))}"
                } else {
                    rustMethodIdentToKotlin(rawMethod)
                }
            val macroTypeArgs = mutableListOf<String>()
            val typeArgs =
                expr.turbofish
                    ?.let { tf ->
                        val renderedArgs = mutableListOf<String>()
                        for (ga in tf.args.toList()) {
                            val unwrappedMacro = (ga as? GenericArgument.TypeArg)?.type?.let { unwrapSynType(it) } as? SynType.Macro
                            if (unwrappedMacro != null) {
                                val renderedMac = renderMacroInvocationAsStmtOrExpr(unwrappedMacro.mac, indent, selfTypeName)
                                if (rawMethod == "parse" && nonUnitArgs.isEmpty() && renderedMac.endsWith(".default()")) {
                                    macroTypeArgs += "${renderedMac.removeSuffix(".default()")}::parse"
                                } else {
                                    macroTypeArgs += renderedMac
                                }
                            } else {
                                renderGenericArgument(ga, selfTypeName)?.let { renderedArgs += it }
                            }
                        }
                        if (renderedArgs.isNotEmpty()) "<${renderedArgs.joinToString(", ")}>" else ""
                    }.orEmpty()
            val savedKind = currentExpectedNumericKind
            currentExpectedNumericKind = null
            currentExpectedParseTargetType = null
            val renderedValueArgs =
                try {
                    nonUnitArgs.map { renderExpr(it, indent, selfTypeName) }
                } finally {
                    currentExpectedNumericKind = savedKind
                    currentExpectedParseTargetType = methodEntryParseTarget
                }
            val hasherState = currentHasherStateParam
            if (hasherState != null &&
                rawMethod == "hash" &&
                renderedValueArgs.size == 1 &&
                renderedValueArgs[0] == hasherState &&
                typeArgs.isEmpty()
            ) {
                return "$hasherState = 31 * $hasherState + ($receiver).hashCode()"
            }
            if ((rawMethod == "checked_add" || rawMethod == "checked_sub" || rawMethod == "checked_mul") &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                val opSym =
                    when (rawMethod) {
                        "checked_add" -> "+"
                        "checked_sub" -> "-"
                        else -> "*"
                    }
                return "(($receiver).toLong() $opSym (${renderedValueArgs[0]}).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()"
            }
            if (rawMethod == "append_all" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                return "for (item in ${renderedValueArgs[0]}) item.toTokens($receiver)"
            }
            if ((rawMethod == "write_str" || rawMethod == "write_char" || rawMethod == "pad") &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                return "$receiver.append(${renderedValueArgs[0]})"
            }
            if (rawMethod == "find" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                return "$receiver.indexOf(${renderedValueArgs[0]}).takeIf { it >= 0 }"
            }
            if ((rawMethod == "offset" || rawMethod == "add" || rawMethod == "sub" || rawMethod == "wrapping_offset" || rawMethod == "wrapping_add" || rawMethod == "wrapping_sub") &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                val deltaSign = if (rawMethod == "sub" || rawMethod == "wrapping_sub") "-" else "+"
                if (headerContext.rawAbiMode) {
                    return "($receiver $deltaSign (${renderedValueArgs[0]}).toLong())"
                }
                val recvLocalTy = currentTypedLocals[receiver]
                val isPtrLike =
                    recvLocalTy == "COpaquePointer?" ||
                        receiver in currentNullableLocals ||
                        (expr.receiver is Expr.Paren && (expr.receiver as Expr.Paren).expr is Expr.Cast) ||
                        expr.receiver is Expr.Cast
                if (isPtrLike) {
                    usesOpaquePointer = true
                    return if (recvLocalTy == "COpaquePointer?" || receiver.contains("COpaquePointer(")) {
                        "($receiver)?.let { COpaquePointer(it.rawValue $deltaSign (${renderedValueArgs[0]}).toLong()) }"
                    } else {
                        "($receiver)?.let { COpaquePointer(it.hashCode().toLong() $deltaSign (${renderedValueArgs[0]}).toLong()) }"
                    }
                }
            }
            if (rawMethod == "debug_struct" && renderedValueArgs.size == 1 && typeArgs.isEmpty() && "DebugStruct" !in inspector.declaredTypeNames) {
                usesDebugStruct = true
                return "DebugStruct($receiver, ${renderedValueArgs[0]})"
            }
            if (rawMethod == "debug_tuple" && renderedValueArgs.size == 1 && typeArgs.isEmpty() && "DebugTuple" !in inspector.declaredTypeNames) {
                usesDebugTuple = true
                return "DebugTuple($receiver, ${renderedValueArgs[0]})"
            }
            if (rawMethod == "push_back" && renderedValueArgs.size == 1 && typeArgs.isEmpty()) {
                return "$receiver.addLast(${renderedValueArgs[0]})"
            }
            if (isVecReceiver && typeArgs.isEmpty()) {
                when {
                    rawMethod == "push" && renderedValueArgs.size == 1 ->
                        return "$receiver.add(${renderedValueArgs[0]})"
                    rawMethod == "insert" && renderedValueArgs.size == 2 ->
                        return "$receiver.add(${renderedValueArgs[0]}, ${renderedValueArgs[1]})"
                    rawMethod == "clone_from" && renderedValueArgs.size == 1 ->
                        return "run { $receiver.clear(); $receiver.addAll(${renderedValueArgs[0]}) }"
                    (rawMethod == "get" || rawMethod == "get_mut") && renderedValueArgs.size == 1 ->
                        return "$receiver.getOrNull(${renderedValueArgs[0]})"
                }
            }
            if (rawMethod == "clone_from" &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty() &&
                (expr.receiver is Expr.Field || receiver in currentNullableLocals)
            ) {
                return "$receiver = ${renderedValueArgs[0]}"
            }
            if ((rawMethod == "push" || rawMethod == "extend") &&
                !isVecReceiver &&
                renderedValueArgs.size == 1 &&
                typeArgs.isEmpty()
            ) {
                val defaultWitness =
                    currentTypeParamWitnessCalls.entries
                        .filter { it.key.second == "default" && it.value !in renderedValueArgs }
                        .map { it.value }
                        .distinct()
                        .singleOrNull()
                if (defaultWitness != null) {
                    return "$receiver.$method(${renderedValueArgs[0]}, $defaultWitness)"
                }
            }
            if (rawMethod == "map" && nonUnitArgs.size == 1 && renderedValueArgs.size == 1) {
                if (isTransparentWrapperOrIdentityMapArg(nonUnitArgs[0])) {
                    return receiver
                }
            }
            if (rawMethod == "take" &&
                nonUnitArgs.isEmpty() &&
                typeArgs.isEmpty() &&
                receiver.all { it.isLetterOrDigit() || it == '_' || it == '.' } &&
                (expr.receiver is Expr.Field || (expr.receiver is Expr.Path && receiver != "cell"))
            ) {
                return "$receiver.also { $receiver = null }"
            }
            if (isKnownNullableOptionExpr(expr.receiver, receiver, selfTypeName) && typeArgs.isEmpty()) {
                val atomicRecv = if (isAtomicPostfixReceiver(expr.receiver, receiver)) receiver else "($receiver)"
                when {
                    rawMethod == "unwrap" && renderedValueArgs.isEmpty() -> return "$atomicRecv!!"
                    rawMethod == "clone" && renderedValueArgs.isEmpty() -> return receiver
                    rawMethod == "into_iter" && renderedValueArgs.isEmpty() -> return "ArrayDeque(listOfNotNull($receiver))"
                    (rawMethod == "map" || rawMethod == "and_then") && renderedValueArgs.size == 1 ->
                        return "$atomicRecv?.let(${renderedValueArgs[0]})"
                    (rawMethod == "or_else" || rawMethod == "unwrap_or_else") && renderedValueArgs.size == 1 ->
                        return "($receiver ?: ${unwrapZeroArgClosureText(renderedValueArgs[0])})"
                    (rawMethod == "or" || rawMethod == "unwrap_or") && renderedValueArgs.size == 1 ->
                        return "($receiver ?: ${renderedValueArgs[0]})"
                }
            }
            val adjustedRenderedValueArgs =
                if ((rawMethod == "peek" || rawMethod == "peek2" || rawMethod == "peek3") &&
                    nonUnitArgs.size == 1 &&
                    nonUnitArgs[0] is Expr.Macro &&
                    renderedValueArgs.size == 1
                ) {
                    listOf(renderedValueArgs[0].removeSuffix(".default()"))
                } else {
                    renderedValueArgs
                }
            val valueArgs = macroTypeArgs + adjustedRenderedValueArgs
            val args = valueArgs.joinToString(", ")
            "$receiver.$method$typeArgs($args)"
        }
        is Expr.Field -> {
            val savedKind = currentExpectedNumericKind
            currentExpectedNumericKind = null
            val base =
                try {
                    renderExpr(expr.base, indent, selfTypeName)
                } finally {
                    currentExpectedNumericKind = savedKind
                }
            when (val m = expr.member) {
                is Member.Named -> "$base.${rustFieldIdentToKotlin(m.ident.toString())}"
                is Member.Unnamed -> "$base.${tupleAccessMemberName(expr.base, m.index.index.toInt(), selfTypeName)}"
            }
        }
        is Expr.Index -> {
            val savedKind = currentExpectedNumericKind
            currentExpectedNumericKind = null
            try {
                val base = renderExpr(expr.expr, indent, selfTypeName)
                val indexExpr = expr.index
                if (indexExpr is Expr.Range) {
                    val start = indexExpr.start?.let { renderExpr(it, indent, selfTypeName) }
                    val end = indexExpr.end?.let { renderExpr(it, indent, selfTypeName) }
                    val op =
                        when (indexExpr.limits) {
                            is RangeLimits.HalfOpen -> " until "
                            is RangeLimits.Closed -> ".."
                        }
                    when {
                        start == null && end == null -> base
                        start != null && end != null -> "$base[$start$op$end]"
                        start != null -> "$base[$start until $base.size]"
                        else -> "$base[0$op$end]"
                    }
                } else {
                    val idx = renderExpr(indexExpr, indent, selfTypeName)
                    "$base[$idx]"
                }
            } finally {
                currentExpectedNumericKind = savedKind
            }
        }
        is Expr.Tuple -> {
            val elems = expr.elems.toList()
            val pairCtor = if ("Pair" in inspector.declaredTypeNames) "kotlin.Pair" else "Pair"
            val tripleCtor = if ("Triple" in inspector.declaredTypeNames) "kotlin.Triple" else "Triple"
            when (elems.size) {
                0 -> "Unit"
                1 -> renderExpr(elems[0], indent, selfTypeName)
                2 -> "$pairCtor(${renderExpr(elems[0], indent, selfTypeName)}, ${renderExpr(elems[1], indent, selfTypeName)})"
                3 -> "$tripleCtor(${renderExpr(elems[0], indent, selfTypeName)}, ${renderExpr(elems[1], indent, selfTypeName)}, ${renderExpr(elems[2], indent, selfTypeName)})"
                else -> "listOf(${elems.joinToString(", ") { renderExpr(it, indent, selfTypeName) }})"
            }
        }
        is Expr.Array -> {
            val elems = expr.elems.toList().joinToString(", ") { renderExpr(it, indent, selfTypeName) }
            "listOf($elems)"
        }
        is Expr.Repeat -> {
            val elem = renderExpr(expr.expr, indent, selfTypeName)
            val len = renderExpr(expr.len, indent, selfTypeName)
            "List($len) { $elem }"
        }
        is Expr.Struct -> renderExprStruct(expr, indent, selfTypeName)
        is Expr.Assign -> {
            val leftExpr = expr.left
            if (leftExpr is Expr.Infer) {
                return if (isSideEffectFreeExpr(expr.right)) "" else renderExpr(expr.right, indent, selfTypeName)
            }
            val left =
                if (leftExpr is Expr.Unary && leftExpr.op is UnOp.Deref) {
                    renderExpr(leftExpr.expr, indent, selfTypeName)
                } else {
                    renderExpr(leftExpr, indent, selfTypeName)
                }
            val prevParseTarget = currentExpectedParseTargetType
            currentExpectedParseTargetType = currentTypedLocals[left]
            val right =
                try {
                    renderExpr(expr.right, indent, selfTypeName)
                } finally {
                    currentExpectedParseTargetType = prevParseTarget
                }
            "$left = $right"
        }
        is Expr.Range -> {
            val start = expr.start?.let { renderExpr(it, indent, selfTypeName) }
            val end = expr.end?.let { renderExpr(it, indent, selfTypeName) }
            val op =
                when (expr.limits) {
                    is RangeLimits.HalfOpen -> " until "
                    is RangeLimits.Closed -> ".."
                }
            when {
                start != null && end != null -> "$start$op$end"
                start != null -> "$start${op}Int.MAX_VALUE"
                end != null -> "0$op$end"
                else -> "0 until 0"
            }
        }
        is Expr.Closure -> renderExprClosure(expr, indent, selfTypeName)
        is Expr.If -> renderExprIf(expr, indent, selfTypeName)
        is Expr.While -> renderExprWhile(expr, indent, selfTypeName)
        is Expr.ForLoop -> renderExprForLoop(expr, indent, selfTypeName)
        is Expr.Loop -> {
            val body = renderBlock(expr.body, indent, selfTypeName)
            "while (true) $body"
        }
        is Expr.Match -> renderExprMatch(expr, indent, selfTypeName)
        is Expr.BlockExpr -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Unsafe -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Async -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Const -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Return -> {
            val retNumKind = currentEnclosingReturnRenderedType?.let(this::resolveNumericKind)
            val valExpr = expr.expr?.let { renderTypedNumericInitializer(it, retNumKind, indent, selfTypeName) }
            val retKw = currentClosureLabel?.let { "return@$it" } ?: "return"
            if (valExpr != null) "$retKw $valExpr" else retKw
        }
        is Expr.Break -> {
            val valExpr = expr.expr?.let { renderExpr(it, indent, selfTypeName) }
            if (valExpr != null) "break" else "break"
        }
        is Expr.Continue -> "continue"
        is Expr.Let -> {
            val patCond = renderIfLetCondition(expr.pat, expr.expr, indent, selfTypeName)
            patCond
        }
        is Expr.Macro -> {
            renderMacroInvocationAsStmtOrExpr(expr.mac, indent, selfTypeName)
        }
        is Expr.TryBlock -> {
            renderBlockAsExpression(expr.block, indent, selfTypeName)
        }
        is Expr.Yield -> {
            val valExpr = expr.expr?.let { renderExpr(it, indent, selfTypeName) }
            if (valExpr != null) "yield($valExpr)" else "yield()"
        }
        is Expr.Infer -> "_"
        is Expr.Verbatim -> ""
    }
}

internal fun KotlinJ2KEmitter.renderBlockAsExpression(block: Block, indent: String, selfTypeName: String?): String {
    val stmts = block.stmts
    if (stmts.isEmpty()) return "Unit"
    if (stmts.size == 1) {
        val only = stmts[0]
        if (only is Stmt.ExprStmt && only.semiToken == null) {
            return renderExpr(only.expr, indent, selfTypeName)
        }
        if (only is Stmt.MacroStmt && only.semiToken == null) {
            return renderMacroInvocationAsStmtOrExpr(only.mac, indent, selfTypeName)
        }
    }
    val simplifiedOverwrite = tryExtractPureOverwrittenTailExpr(stmts)
    if (simplifiedOverwrite != null) {
        return renderExpr(simplifiedOverwrite, indent, selfTypeName)
    }
    return "run ${renderBlock(block, indent, selfTypeName)}"
}

internal fun KotlinJ2KEmitter.tryExtractPureOverwrittenTailExpr(stmts: List<Stmt>): Expr? {
    if (stmts.size < 2) return null
    val tailStmt = stmts.last() as? Stmt.ExprStmt ?: return null
    if (tailStmt.semiToken != null) return null
    val tailPath = tailStmt.expr as? Expr.Path ?: return null
    if (tailPath.qself != null || tailPath.path.segments.len() != 1) return null
    val tailIdent =
        tailPath.path.segments
            .first()
            ?.ident
            ?.toString()
            ?.removePrefix("r#") ?: return null

    var lastInitExpr: Expr? = null
    for (i in 0 until stmts.size - 1) {
        val local = stmts[i] as? Stmt.Local ?: return null
        val patIdent = local.pat as? Pat.Ident ?: return null
        if (patIdent.subpat != null) return null
        if (patIdent.ident.toString().removePrefix("r#") != tailIdent) return null
        val init = local.init ?: return null
        if (init.diverge != null) return null
        if (i < stmts.size - 2 && !isPureArithmeticOrSideEffectFreeExpr(init.expr)) {
            return null
        }
        lastInitExpr = init.expr
    }
    val finalExpr = lastInitExpr ?: return null
    if (exprReferencesIdent(finalExpr, tailIdent)) return null
    return finalExpr
}

internal fun KotlinJ2KEmitter.isPureArithmeticOrSideEffectFreeExpr(expr: Expr): Boolean =
    when (expr) {
        is Expr.Lit, is Expr.Path -> true
        is Expr.Paren -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Group -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Reference -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Cast -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Unary -> isPureArithmeticOrSideEffectFreeExpr(expr.expr)
        is Expr.Binary ->
            !isCompoundAssignOp(expr.op) &&
                isPureArithmeticOrSideEffectFreeExpr(expr.left) &&
                isPureArithmeticOrSideEffectFreeExpr(expr.right)
        else -> false
    }

internal fun KotlinJ2KEmitter.exprReferencesIdent(expr: Expr, identName: String): Boolean {
    var found = false
    val visitor =
        object : Visit() {
            override fun visitExprPath(e: Expr.Path) {
                if (e.qself == null &&
                    e.path.segments.len() == 1 &&
                    e.path.segments
                        .first()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#") == identName
                ) {
                    found = true
                    return
                }
                super.visitExprPath(e)
            }
        }
    visitor.visitExpr(expr)
    return found
}

internal fun KotlinJ2KEmitter.tupleAccessMemberName(baseExpr: Expr, index: Int, selfTypeName: String?): String {
    if (baseExpr is Expr.Path && baseExpr.path.isIdent("self")) {
        return if (index == 0) "value" else "field$index"
    }
    val ownerBase = selfTypeName?.substringBefore('<')?.trim()
    if (ownerBase != null &&
        inspector.tupleStructArityByType[ownerBase] == 1 &&
        baseExpr is Expr.Path &&
        baseExpr.qself == null &&
        baseExpr.path.segments.len() == 1
    ) {
        return if (index == 0) "value" else "field$index"
    }
    return when (index) {
        0 -> "first"
        1 -> "second"
        2 -> "third"
        else -> "field$index"
    }
}

internal fun KotlinJ2KEmitter.resolveContainerElementTraitCallee(callee: String): String? {
    val standaloneTraitPath = currentStandaloneImplTraitPath ?: return null
    val standaloneSelfSynType = currentStandaloneImplSelfSynType ?: return null
    val lastTraitSeg = standaloneTraitPath.segments.last() ?: return null
    val traitName = rustTypeIdentToKotlin(lastTraitSeg.ident.toString())
    if (!callee.startsWith("$traitName.")) return null
    val methodPart = callee.removePrefix("$traitName.")
    if (methodPart.isEmpty() || '.' in methodPart) return null
    val selfPath = (unwrapSynType(standaloneSelfSynType) as? SynType.Path)?.takeIf { it.qself == null } ?: return null
    val lastSelfSeg = selfPath.path.segments.last() ?: return null
    if (lastSelfSeg.ident.toString() != "Vec") return null
    val elemSynType =
        (lastSelfSeg.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
            ?: return null
    return disambiguateReceiverlessTraitImplFnName(standaloneTraitPath, methodPart, elemSynType)
}

internal fun KotlinJ2KEmitter.extractOwnerAndFieldName(expr: Expr, selfTypeName: String?): Pair<String?, String>? {
    val core = unwrapTransparentCastExpr(expr)
    if (core !is Expr.Field) return null
    val member = core.member as? Member.Named ?: return null
    val fieldName = rustFieldIdentToKotlin(member.ident.toString())
    val baseCore = unwrapTransparentCastExpr(core.base)
    val ownerType =
        if (baseCore is Expr.Path && baseCore.qself == null && baseCore.path.segments.len() == 1) {
            val firstSeg = baseCore.path.segments.first()
            val firstIdent = firstSeg?.ident?.toString()
            val baseRaw = firstIdent?.removePrefix("r#")
            if (baseRaw == "self") {
                selfTypeName?.substringBefore('<')?.trim()
            } else {
                baseRaw?.let(::rustTypeIdentToKotlin)?.takeIf { it in inspector.declaredTypeNames }
            }
        } else {
            null
        }
    return ownerType to fieldName
}

internal fun KotlinJ2KEmitter.isDequeIterFieldExpr(expr: Expr, selfTypeName: String?): Boolean {
    val (ownerType, fieldName) = extractOwnerAndFieldName(expr, selfTypeName) ?: return false
    return ownerType != null && fieldName in inspector.dequeIterFieldsByType[ownerType].orEmpty()
}

internal fun KotlinJ2KEmitter.isMutableVecExpr(expr: Expr, renderedReceiver: String, selfTypeName: String?): Boolean {
    if (renderedReceiver in currentMutableListLocals) return true
    val (ownerType, fieldName) = extractOwnerAndFieldName(expr, selfTypeName) ?: return false
    if (ownerType != null && fieldName in inspector.vecFieldsByType[ownerType].orEmpty()) return true
    if (ownerType == null && inspector.vecFieldsByType.values.any { fieldName in it } && fieldName == "inner") {
        val baseCore = unwrapTransparentCastExpr((unwrapTransparentCastExpr(expr) as Expr.Field).base)
        if (baseCore is Expr.Path && baseCore.qself == null && baseCore.path.segments.len() == 1) {
            val firstSeg = baseCore.path.segments.first()
            val firstIdent = firstSeg?.ident?.toString()
            val rawVar = firstIdent?.removePrefix("r#").orEmpty()
            if (rustTypeIdentToKotlin(rawVar) in inspector.vecFieldsByType) return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.isKnownNullableOptionExpr(expr: Expr, renderedReceiver: String, selfTypeName: String?): Boolean {
    if (renderedReceiver in currentNullableLocals || renderedReceiver.contains("?.let(")) return true
    val (ownerType, fieldName) = extractOwnerAndFieldName(expr, selfTypeName) ?: (null to "")
    if (ownerType != null && fieldName in inspector.nullableFieldsByType[ownerType].orEmpty()) return true
    val core = unwrapTransparentCastExpr(expr)
    if (core is Expr.MethodCall) {
        val m = core.method.toString()
        if (m in KNOWN_OPTION_RETURNING_METHODS) return true
        if ((m == "map" || m == "and_then" || m == "or" || m == "or_else" || m == "as_ref" || m == "as_mut") &&
            isKnownNullableOptionExpr(core.receiver, "", selfTypeName)
        ) {
            return true
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.unwrapZeroArgClosureText(renderedClosure: String): String {
    val trimmed = renderedClosure.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}") && !trimmed.contains("->")) {
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if ('\n' !in inner && ';' !in inner) {
            return inner
        }
        return "run $trimmed"
    }
    return "$trimmed()"
}

internal fun KotlinJ2KEmitter.renderExprStruct(expr: Expr.Struct, indent: String, selfTypeName: String?): String {
    val pathSegs = expr.path.segments
    val allSegStrings = pathSegs.toList().map { it.ident.toString() }
    val resolvedCrossTarget = resolveCrossModulePathTarget(allSegStrings)
    val ctorPath =
        if (resolvedCrossTarget != null) {
            val (resolvedCrossPkg, trailing) = resolvedCrossTarget
            val boundHead = bindOrQualifyResolvedCrossSymbol(resolvedCrossPkg)
            if (trailing.isEmpty()) {
                boundHead
            } else {
                (listOf(boundHead) + trailing.map(::rustTypeIdentToKotlin)).joinToString(".")
            }
        } else {
            val rawSegments =
                expr.path.segments
                    .toList()
                    .filter { it.ident.toString() !in IGNORED_PATH_PREFIXES }
            val typeSegments =
                rawSegments.dropWhile {
                    val s = it.ident.toString()
                    s.isNotEmpty() && s.first().isLowerCase() && s != "self" && !scopeBuilder.isImportedModuleInScope(s)
                }
            if (typeSegments.isEmpty()) {
                selfTypeName?.substringBefore('<')?.trim() ?: "Any"
            } else {
                typeSegments.joinToString(".") { seg ->
                    val raw = seg.ident.toString()
                    when {
                        raw == "Self" -> selfTypeName?.substringBefore('<')?.trim() ?: "Self"
                        raw.first().isUpperCase() -> rustTypeIdentToKotlin(raw)
                        scopeBuilder.isImportedModuleInScope(raw) -> scopeBuilder.resolveModuleBinding(raw)?.kotlinName ?: rustModuleIdentToKotlin(raw)
                        else -> rustValueIdentToKotlin(raw)
                    }
                }
            }
        }
    val ctorBase = ctorPath.substringAfterLast('.').substringBefore('<').trim()
    if (!ctorPath.contains('.') &&
        ctorBase !in inspector.declaredTypeNames &&
        headerContext.crateSymbolSubpackages.isNotEmpty()
    ) {
        val bestSubpkg = resolveBestSubpackageForSymbol(ctorBase)
        val nsPrefix = currentNamespacePrefix()
        if (bestSubpkg != null && nsPrefix != null) {
            val targetPkg = if (bestSubpkg.isEmpty()) nsPrefix else "$nsPrefix.$bestSubpkg"
            if (targetPkg.isNotEmpty() && targetPkg != headerContext.packageName) {
                val existingPkg = importedSimpleNameToPkg[ctorBase]
                if (existingPkg == null || existingPkg == targetPkg) {
                    importedSimpleNameToPkg[ctorBase] = targetPkg
                    extraTypePathImports += "import $targetPkg.$ctorBase"
                }
            }
        }
    }
    val seenFieldNames = hashSetOf<String>()
    val fieldArgs =
        expr.fields.toList().mapNotNull { fv: FieldValue ->
            val fieldName =
                when (val m = fv.member) {
                    is Member.Named -> rustFieldIdentToKotlin(m.ident.toString())
                    is Member.Unnamed -> tupleFieldPropertyName(m.index.index.toInt(), expr.fields.len())
                }
            if (!seenFieldNames.add(fieldName)) return@mapNotNull null
            val fieldSynTy = inspector.structFieldSynTypesByType[ctorBase]?.get(fieldName)
            val renderedFieldTy =
                fieldSynTy?.let { renderType(it, selfTypeName) }
                    ?: headerContext.crateStructFieldTypes["$ctorBase.$fieldName"]
            val prevFieldParseTarget = currentExpectedParseTargetType
            currentExpectedParseTargetType = renderedFieldTy
            var valText =
                try {
                    if (renderedFieldTy != null && renderedFieldTy in PRIMITIVE_ARRAY_TYPES) {
                        renderStructPrimitiveArrayFieldInit(fv.expr, renderedFieldTy, indent, selfTypeName)
                    } else if (renderedFieldTy != null) {
                        renderTypedNumericInitializer(fv.expr, resolveNumericKind(renderedFieldTy), indent, selfTypeName)
                    } else {
                        renderExpr(fv.expr, indent, selfTypeName)
                    }
                } finally {
                    currentExpectedParseTargetType = prevFieldParseTarget
                }
            if (valText == "ArrayDeque()" && fieldSynTy != null && renderedFieldTy != null) {
                val traitBase = renderedFieldTy.substringBefore('<').trim()
                val traitDecl = inspector.declaredTraitsByName[traitBase]
                val iterSpec = traitDecl?.let { extractIteratorSupertraitSpec(it.supertraits.toList(), null) }
                if (traitDecl != null && iterSpec != null) {
                    val elemTy =
                        if ('<' in renderedFieldTy && renderedFieldTy.endsWith(">")) {
                            renderedFieldTy.substringAfter('<').removeSuffix(">").trim()
                        } else {
                            iterSpec.itemType
                        }
                    val methodStubs = mutableListOf<String>()
                    methodStubs += "override fun next(): $elemTy? = null"
                    if (iterSpec.hasDoubleEnded) {
                        methodStubs += "override fun nextBack(): $elemTy? = null"
                    }
                    if (iterSpec.hasExactSize) {
                        methodStubs += "override fun len(): Int = 0"
                    }
                    for (tItem in traitDecl.items) {
                        if (traitItemHasDocOnlyCfg(tItem)) continue
                        if (tItem is TraitItem.Fn && tItem.default == null) {
                            val mName = rustFunctionIdentToKotlin(tItem.sig.ident.toString())
                            if (mName == "cloneBox") {
                                methodStubs += "override fun cloneBox(): $renderedFieldTy = this"
                            }
                        }
                    }
                    valText = "object : $renderedFieldTy { ${methodStubs.joinToString("; ")} }"
                }
            }
            "$fieldName = $valText"
        }
    val restExpr = expr.rest
    return if (restExpr != null) {
        val baseText = renderExpr(restExpr, indent, selfTypeName)
        "$baseText.copy(${fieldArgs.joinToString(", ")})"
    } else {
        "$ctorPath(${fieldArgs.joinToString(", ")})"
    }
}

internal fun KotlinJ2KEmitter.renderStructPrimitiveArrayFieldInit(
    initExpr: Expr,
    arrayType: String,
    indent: String,
    selfTypeName: String?,
): String {
    val elemKind = primitiveArrayElementNumericKind(arrayType)
    return when (initExpr) {
        is Expr.Array -> {
            val fnName =
                when (arrayType) {
                    "ByteArray" -> "byteArrayOf"
                    "UByteArray" -> "ubyteArrayOf"
                    "ShortArray" -> "shortArrayOf"
                    "UShortArray" -> "ushortArrayOf"
                    "IntArray" -> "intArrayOf"
                    "UIntArray" -> "uintArrayOf"
                    "LongArray" -> "longArrayOf"
                    "ULongArray" -> "ulongArrayOf"
                    "FloatArray" -> "floatArrayOf"
                    "DoubleArray" -> "doubleArrayOf"
                    "BooleanArray" -> "booleanArrayOf"
                    else -> "listOf"
                }
            val elems =
                initExpr.elems.toList().joinToString(", ") { elem ->
                    renderTypedNumericInitializer(elem, elemKind, indent, selfTypeName)
                }
            "$fnName($elems)"
        }
        is Expr.Repeat -> {
            val lenText = normalizeArrayLengthToIntExpr(renderExpr(initExpr.len, indent, selfTypeName))
            val elemText = renderTypedNumericInitializer(initExpr.expr, elemKind, indent, selfTypeName)
            "$arrayType($lenText) { $elemText }"
        }
        else -> renderExpr(initExpr, indent, selfTypeName)
    }
}

internal fun KotlinJ2KEmitter.primitiveArrayElementNumericKind(arrayType: String): NumericKind? =
    when (arrayType) {
        "ByteArray", "List<Byte>", "MutableList<Byte>" -> NumericKind.I8
        "UByteArray", "List<UByte>", "MutableList<UByte>" -> NumericKind.U8
        "ShortArray", "List<Short>", "MutableList<Short>" -> NumericKind.I16
        "UShortArray", "List<UShort>", "MutableList<UShort>" -> NumericKind.U16
        "IntArray", "List<Int>", "MutableList<Int>" -> NumericKind.I32
        "UIntArray", "List<UInt>", "MutableList<UInt>" -> NumericKind.U32
        "LongArray", "List<Long>", "MutableList<Long>" -> NumericKind.I64
        "ULongArray", "List<ULong>", "MutableList<ULong>" -> NumericKind.U64
        "FloatArray", "List<Float>", "MutableList<Float>" -> NumericKind.F32
        "DoubleArray", "List<Double>", "MutableList<Double>" -> NumericKind.F64
        else -> null
    }

internal fun KotlinJ2KEmitter.inferArrayElementNumericKind(expr: Expr, selfTypeName: String?): NumericKind? {
    var containerTy = inferExprRenderedType(expr, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
    primitiveArrayElementNumericKind(containerTy)?.let { return it }
    var depth = 0
    while (depth < 6) {
        val aliasSyn = inspector.typeAliasTargets[containerTy] ?: break
        val rendered = renderType(aliasSyn, selfTypeName = null).removeSuffix("?").trim()
        if (rendered == containerTy) break
        primitiveArrayElementNumericKind(rendered)?.let { return it }
        containerTy = rendered
        depth++
    }
    return null
}

internal fun KotlinJ2KEmitter.extractIndexedElementRenderedType(containerTy: String, selfTypeName: String?): String? {
    var cur = containerTy.removeSuffix("?").trim()
    var depth = 0
    while (depth <= 6) {
        when (cur) {
            "ByteArray" -> return "Byte"
            "UByteArray" -> return "UByte"
            "ShortArray" -> return "Short"
            "UShortArray" -> return "UShort"
            "IntArray" -> return "Int"
            "UIntArray" -> return "UInt"
            "LongArray" -> return "Long"
            "ULongArray" -> return "ULong"
            "FloatArray" -> return "Float"
            "DoubleArray" -> return "Double"
            "BooleanArray" -> return "Boolean"
        }
        when {
            cur.startsWith("List<") && cur.endsWith(">") -> return cur.removePrefix("List<").removeSuffix(">").trim()
            cur.startsWith("MutableList<") && cur.endsWith(">") -> return cur.removePrefix("MutableList<").removeSuffix(">").trim()
            cur.startsWith("Array<") && cur.endsWith(">") -> return cur.removePrefix("Array<").removeSuffix(">").trim()
        }
        val aliasSyn = inspector.typeAliasTargets[cur] ?: break
        val next = renderType(aliasSyn, selfTypeName).removeSuffix("?").trim()
        if (next == cur) break
        cur = next
        depth++
    }
    return null
}

internal fun KotlinJ2KEmitter.renderOptionalSynParseExpr(receiver: String, peekTok: String, innerTarget: String): String {
    val isSimpleReceiver = receiver.all { it.isLetterOrDigit() || it == '_' }
    return if (isSimpleReceiver) {
        "(if ($receiver.peek($peekTok)) $receiver.parse($innerTarget::parse) else Result.success(null))"
    } else {
        "$receiver.let { s -> if (s.peek($peekTok)) s.parse($innerTarget::parse) else Result.success(null) }"
    }
}

internal fun KotlinJ2KEmitter.resolveOptionalSynParsePeekToken(innerTarget: String): String? {
    if (innerTarget.isEmpty() || !innerTarget.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
        return null
    }
    return when (innerTarget) {
        "Abi" -> "Extern"
        "BoundLifetimes" -> "For"
        "WhereClause" -> "Where"
        "Label" -> "Lifetime"
        "Ident",
        "Lifetime",
        "Lit",
        "LitStr",
        "LitByteStr",
        "LitCStr",
        "LitByte",
        "LitChar",
        "LitInt",
        "LitFloat",
        "LitBool",
        "SelfType",
        "SelfValue",
        "SynTypeToken",
        "Underscore",
        -> innerTarget
        in SYN_PUNCT_TOKEN_CLASS_NAMES -> innerTarget
        in SYN_KEYWORD_TOKEN_CLASS_NAMES -> innerTarget
        else ->
            if (innerTarget.startsWith("token.") || innerTarget.startsWith("kw.")) {
                innerTarget
            } else {
                null
            }
    }
}

internal fun KotlinJ2KEmitter.isKnownNonNullableNonOptionReceiver(
    recvExpr: Expr,
    renderedReceiver: String,
    selfTypeName: String?,
): Boolean {
    if (renderedReceiver in currentNullableLocals) return false
    val rawTy =
        (currentTypedLocals[renderedReceiver] ?: inferExprRenderedType(recvExpr, selfTypeName))
            ?.trim()
            ?: return false
    if (rawTy.isEmpty() ||
        rawTy.endsWith("?") ||
        rawTy == "Any" ||
        rawTy == "Option" ||
        rawTy.startsWith("Option<")
    ) {
        return false
    }
    var cur = rawTy
    var depth = 0
    while (depth < 8) {
        val base = cur.substringBefore('<').substringAfterLast('.').trim()
        if (base.isEmpty() || base in activeTypeParamNames) return false
        val aliasSyn = inspector.typeAliasTargets[base]
        if (aliasSyn != null) {
            val next = renderType(aliasSyn, selfTypeName).trim()
            if (next.endsWith("?") || next == "Any" || next == "Option" || next.startsWith("Option<")) {
                return false
            }
            if (next == cur) break
            cur = next
            depth++
            continue
        }
        val crateAlias = headerContext.crateTypeAliasDefaults[base]?.trim()
        if (crateAlias != null) {
            if (crateAlias.endsWith("?") || crateAlias == "Any" || crateAlias == "Option" || crateAlias.startsWith("Option<")) {
                return false
            }
            if (crateAlias == cur) break
            cur = crateAlias
            depth++
            continue
        }
        break
    }
    return true
}

internal fun KotlinJ2KEmitter.inferExprRenderedType(expr: Expr, selfTypeName: String?): String? =
    when (expr) {
        is Expr.Paren -> inferExprRenderedType(expr.expr, selfTypeName)
        is Expr.Group -> inferExprRenderedType(expr.expr, selfTypeName)
        is Expr.Reference -> inferExprRenderedType(expr.expr, selfTypeName)
        is Expr.RawAddr -> if (headerContext.rawAbiMode) "Long" else inferExprRenderedType(expr.expr, selfTypeName)
        is Expr.Await -> inferExprRenderedType(expr.base, selfTypeName)
        is Expr.Try -> {
            val inner = unwrapParenOrGroupExpr(expr.expr)
            val innerTurbofish = (inner as? Expr.MethodCall)?.turbofish
            if (inner is Expr.MethodCall &&
                inner.method.toString().removePrefix("r#") == "parse" &&
                inner.args.isEmpty() &&
                innerTurbofish != null &&
                innerTurbofish.args.size == 1
            ) {
                val tfGa = innerTurbofish.args.first() as? GenericArgument.TypeArg
                if (tfGa != null) {
                    renderType(tfGa.type, selfTypeName)
                } else {
                    null
                }
            } else {
                val innerTy = inferExprRenderedType(expr.expr, selfTypeName)?.trim()
                when {
                    innerTy == null -> null
                    innerTy.startsWith("Result<") && innerTy.endsWith(">") ->
                        innerTy.removePrefix("Result<").removeSuffix(">").trim()
                    innerTy.endsWith("?") -> innerTy.removeSuffix("?").trim()
                    else -> null
                }
            }
        }
        is Expr.Cast -> renderType(expr.ty, selfTypeName)
        is Expr.Struct -> {
            val lastSeg =
                expr.path.segments
                    .toList()
                    .lastOrNull()
                    ?.ident
                    ?.toString()
            when (lastSeg) {
                null -> null
                "Self" -> selfTypeName?.substringBefore('<')?.trim()
                else -> rustTypeIdentToKotlin(lastSeg)
            }
        }
        is Expr.Path -> {
            if (expr.qself == null && expr.path.segments.size == 1) {
                val raw =
                    expr.path.segments
                        .first()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                when {
                    raw == null -> null
                    raw == "self" -> selfTypeName
                    else -> {
                        val ktConst = inspector.declaredConstNames[raw]
                        val constTy = ktConst?.let { inspector.declaredConstTypes[it] }
                        if (constTy != null) {
                            renderType(constTy, selfTypeName)
                        } else {
                            val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                            currentTypedLocals[ktLocal]
                        }
                    }
                }
            } else {
                null
            }
        }
        is Expr.Unary ->
            when (expr.op) {
                is UnOp.Deref -> inferExprRenderedType(expr.expr, selfTypeName)?.removeSuffix("?")
                is UnOp.Neg -> inferExprRenderedType(expr.expr, selfTypeName)
                is UnOp.NotOp -> if (isKnownIntegerBitmaskExpr(expr.expr)) inferExprRenderedType(expr.expr, selfTypeName) else "Boolean"
            }
        is Expr.Field -> {
            val baseTy = inferExprRenderedType(expr.base, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
            val ownerKt = baseTy.substringAfterLast('.').substringBefore('<').trim()
            if (ownerKt.isEmpty()) return null
            when (val member = expr.member) {
                is Member.Named -> {
                    val fieldKt = rustFieldIdentToKotlin(member.ident.toString())
                    inspector.structFieldSynTypesByType[ownerKt]?.get(fieldKt)?.let { return renderType(it, ownerKt) }
                    for (key in scopedAbiKeysForRenderedType(baseTy)) {
                        headerContext.crateStructFieldTypes["$key.$fieldKt"]?.let { return it }
                    }
                    headerContext.crateStructFieldTypes["$ownerKt.$fieldKt"]
                }
                is Member.Unnamed -> {
                    val idx = member.index.index.toInt()
                    val arity = inspector.tupleStructArityByType[ownerKt]
                    val primaryField =
                        if (arity != null) {
                            tupleFieldPropertyName(idx, arity)
                        } else {
                            tupleAccessMemberName(expr.base, idx, selfTypeName)
                        }
                    inspector.structFieldSynTypesByType[ownerKt]?.get(primaryField)?.let { return renderType(it, ownerKt) }
                    val candidateFields =
                        if (idx == 0 && primaryField != "value") {
                            listOf(primaryField, "value")
                        } else {
                            listOf(primaryField)
                        }
                    candidateFields.firstNotNullOfOrNull { fName ->
                        scopedAbiKeysForRenderedType(baseTy).firstNotNullOfOrNull { key ->
                            headerContext.crateStructFieldTypes["$key.$fName"]
                        } ?: headerContext.crateStructFieldTypes["$ownerKt.$fName"]
                    }
                }
            }
        }
        is Expr.Index -> {
            if (expr.index is Expr.Range) {
                inferExprRenderedType(expr.expr, selfTypeName)
            } else {
                val baseTy = inferExprRenderedType(expr.expr, selfTypeName) ?: return null
                extractIndexedElementRenderedType(baseTy, selfTypeName)
            }
        }
        is Expr.Call -> {
            val fnPath = (expr.func as? Expr.Path)?.takeIf { it.qself == null } ?: return null
            val segs =
                fnPath.path.segments
                    .toList()
                    .filter { it.ident.toString() !in IGNORED_PATH_PREFIXES }
            when {
                segs.size == 1 -> {
                    val raw = segs[0].ident.toString().removePrefix("r#")
                    if (raw == "Self") {
                        selfTypeName?.substringBefore('<')?.trim()
                    } else if (raw.isNotEmpty() && raw.first().isUpperCase()) {
                        val ktType = rustTypeIdentToKotlin(raw)
                        if (ktType in inspector.declaredTypeNames ||
                            scopedAbiKeysForRenderedType(ktType).any { it in headerContext.crateSymbolSubpackages || "$it.value" in headerContext.crateStructFieldTypes }
                        ) {
                            ktType
                        } else {
                            val ktFn = rustFunctionIdentToKotlin(raw)
                            inspector.topLevelFnReturnSynTypes[ktFn]?.let { renderType(it, selfTypeName) }
                                ?: scopedAbiKeysForRenderedType(ktFn).firstNotNullOfOrNull { headerContext.crateFnReturnTypes[it] }
                                ?: headerContext.crateFnReturnTypes[ktFn]
                        }
                    } else {
                        val ktFn = rustFunctionIdentToKotlin(raw)
                        inspector.topLevelFnReturnSynTypes[ktFn]?.let { renderType(it, selfTypeName) }
                            ?: scopedAbiKeysForRenderedType(ktFn).firstNotNullOfOrNull { headerContext.crateFnReturnTypes[it] }
                            ?: headerContext.crateFnReturnTypes[ktFn]
                    }
                }
                segs.size >= 2 -> {
                    val penultSeg = segs[segs.size - 2]
                    val lastSeg = segs[segs.size - 1]
                    val penultRaw = penultSeg.ident.toString().removePrefix("r#")
                    val lastRaw = lastSeg.ident.toString().removePrefix("r#")
                    val methodKt = rustFunctionIdentToKotlin(lastRaw)
                    val ownerKt =
                        when {
                            penultRaw == "Self" -> selfTypeName?.substringBefore('<')?.trim()
                            penultRaw.isNotEmpty() && penultRaw.first().isUpperCase() -> rustTypeIdentToKotlin(penultRaw)
                            else -> null
                        }
                    if (ownerKt != null) {
                        inspector.implMethodReturnSynTypes[ownerKt to methodKt]?.let { return renderType(it, ownerKt) }
                        val modPrefix =
                            segs
                                .dropLast(2)
                                .joinToString(".") { rustModuleIdentToKotlin(it.ident.toString().replace('-', '_')) }
                        if (modPrefix.isNotEmpty()) {
                            headerContext.crateFnReturnTypes["$modPrefix.$ownerKt.$methodKt"]?.let { return it }
                        }
                        for (ownerKey in scopedAbiKeysForRenderedType(ownerKt)) {
                            headerContext.crateFnReturnTypes["$ownerKey.$methodKt"]?.let { return it }
                        }
                        headerContext.crateFnReturnTypes["$ownerKt.$methodKt"]
                    } else {
                        val modSubpkg =
                            segs
                                .dropLast(1)
                                .joinToString(".") { rustModuleIdentToKotlin(it.ident.toString().replace('-', '_')) }
                        headerContext.crateFnReturnTypes["$modSubpkg.$methodKt"]
                            ?: headerContext.crateFnReturnTypes[methodKt]
                    }
                }
                else -> null
            }
        }
        is Expr.MethodCall -> {
            val rawMethod = expr.method.toString().removePrefix("r#")
            when {
                (rawMethod == "to_be_bytes" || rawMethod == "to_le_bytes" || rawMethod == "to_ne_bytes") && expr.args.isEmpty() ->
                    if (headerContext.rawAbiMode || headerContext.finalClasses) "UByteArray" else "List<UByte>"
                rawMethod == "as_bytes" && expr.args.isEmpty() ->
                    if (headerContext.rawAbiMode || headerContext.finalClasses) "UByteArray" else "List<UByte>"
                rawMethod == "len" && expr.args.isEmpty() -> "Int"
                rawMethod == "clone" && expr.args.isEmpty() -> inferExprRenderedType(expr.receiver, selfTypeName)
                else -> {
                    val recvTy = inferExprRenderedType(expr.receiver, selfTypeName)?.removeSuffix("?")?.trim() ?: return null
                    val ownerKt = recvTy.substringAfterLast('.').substringBefore('<').trim()
                    if (ownerKt.isEmpty()) return null
                    val methodKt = rustFunctionIdentToKotlin(rawMethod)
                    inspector.implMethodReturnSynTypes[ownerKt to methodKt]?.let { return renderType(it, ownerKt) }
                    for (ownerKey in scopedAbiKeysForRenderedType(recvTy)) {
                        headerContext.crateFnReturnTypes["$ownerKey.$methodKt"]?.let { return it }
                    }
                    headerContext.crateFnReturnTypes["$ownerKt.$methodKt"]
                }
            }
        }
        is Expr.BlockExpr -> {
            val tail = expr.block.stmts.lastOrNull() as? Stmt.ExprStmt
            if (tail != null && tail.semiToken == null) inferExprRenderedType(tail.expr, selfTypeName) else null
        }
        is Expr.If -> {
            val tail = expr.thenBranch.stmts.lastOrNull() as? Stmt.ExprStmt
            if (tail != null && tail.semiToken == null) inferExprRenderedType(tail.expr, selfTypeName) else null
        }
        else -> null
    }

internal fun KotlinJ2KEmitter.unwrapParenOrGroupExpr(expr: Expr): Expr =
    when (expr) {
        is Expr.Paren -> unwrapParenOrGroupExpr(expr.expr)
        is Expr.Group -> unwrapParenOrGroupExpr(expr.expr)
        else -> expr
    }

internal fun KotlinJ2KEmitter.isNarrowComparisonLiteralOperand(operand: Expr, targetKind: NumericKind): Boolean {
    val isNarrowUnsigned = targetKind == NumericKind.U8 || targetKind == NumericKind.U16
    val isNarrowSigned = targetKind == NumericKind.I8 || targetKind == NumericKind.I16
    if (!isNarrowUnsigned && !isNarrowSigned) return false
    val core = unwrapParenOrGroupExpr(operand)
    if (core is Expr.Lit) {
        val lit = core.lit
        if (lit is Lit.Int) {
            val suffix = lit.value.suffix()
            return if (isNarrowUnsigned) {
                suffix.isEmpty() || suffix == "u8" || suffix == "u16"
            } else {
                suffix.isEmpty() || suffix == "i8" || suffix == "i16"
            }
        }
        return false
    }
    if (isNarrowSigned && core is Expr.Unary && core.op is UnOp.Neg) {
        val inner = unwrapParenOrGroupExpr(core.expr)
        val innerLit = (inner as? Expr.Lit)?.lit as? Lit.Int ?: return false
        val suffix = innerLit.value.suffix()
        return suffix.isEmpty() || suffix == "i8" || suffix == "i16"
    }
    return false
}

internal fun KotlinJ2KEmitter.coerceRenderedNarrowLiteral(sourceExpr: Expr, rendered: String, kind: NumericKind): String {
    val recv = if (isAtomicPostfixReceiver(sourceExpr, rendered)) rendered else "($rendered)"
    return when (kind) {
        NumericKind.U8 -> if (rendered.endsWith(".toUByte()")) rendered else "$recv.toUByte()"
        NumericKind.U16 -> if (rendered.endsWith(".toUShort()")) rendered else "$recv.toUShort()"
        NumericKind.I8 -> if (rendered.endsWith(".toByte()")) rendered else "$recv.toByte()"
        NumericKind.I16 -> if (rendered.endsWith(".toShort()")) rendered else "$recv.toShort()"
        else -> rendered
    }
}

internal fun KotlinJ2KEmitter.inferExprNumericKind(expr: Expr, selfTypeName: String?): NumericKind? =
    when (expr) {
        is Expr.Paren -> inferExprNumericKind(expr.expr, selfTypeName)
        is Expr.Group -> inferExprNumericKind(expr.expr, selfTypeName)
        is Expr.Cast -> resolveNumericKind(renderType(expr.ty, selfTypeName))
        is Expr.Index -> inferArrayElementNumericKind(expr.expr, selfTypeName)
        is Expr.Field -> resolveFieldRenderedType(expr, selfTypeName)?.let(this::resolveNumericKind)
        is Expr.Call, is Expr.MethodCall, is Expr.BlockExpr, is Expr.If ->
            inferExprRenderedType(expr, selfTypeName)?.let(this::resolveNumericKind)
        is Expr.Lit -> {
            val lit = expr.lit
            if (lit is Lit.Int) {
                when (lit.value.suffix()) {
                    "i8" -> NumericKind.I8
                    "u8" -> NumericKind.U8
                    "i16" -> NumericKind.I16
                    "u16" -> NumericKind.U16
                    "i32" -> NumericKind.I32
                    "u32" -> NumericKind.U32
                    "i64" -> NumericKind.I64
                    "u64" -> NumericKind.U64
                    "i128" -> NumericKind.I128
                    "u128" -> NumericKind.U128
                    "isize" -> if (headerContext.rawAbiMode && activeCfgProfile.pointerWidth == 64) NumericKind.I64 else NumericKind.I32
                    "usize" ->
                        if (headerContext.rawAbiMode) {
                            if (activeCfgProfile.pointerWidth == 64) NumericKind.U64 else NumericKind.U32
                        } else {
                            NumericKind.I32
                        }
                    "" -> {
                        val parsed = parseUnsigned128LiteralDigits(lit.value.token().toString())
                        if (parsed?.exceeds64BitUnsigned == true) NumericKind.U128 else null
                    }
                    else -> null
                }
            } else {
                null
            }
        }
        is Expr.Path -> {
            if (expr.qself == null && expr.path.segments.size == 1) {
                val firstSeg = expr.path.segments.first()
                val raw = firstSeg?.ident?.toString()?.removePrefix("r#")
                if (raw != null) {
                    val ktConst = inspector.declaredConstNames[raw]
                    val constTy = ktConst?.let { inspector.declaredConstTypes[it] }
                    if (constTy != null) {
                        resolveNumericKind(renderType(constTy, selfTypeName))
                    } else {
                        val ktLocal = scopeBuilder.resolveLocalKotlinName(raw) ?: rustValueIdentToKotlin(raw)
                        currentTypedLocals[ktLocal]?.let(this::resolveNumericKind)
                    }
                } else {
                    null
                }
            } else {
                null
            }
        }
        is Expr.Unary ->
            when (expr.op) {
                is UnOp.NotOp -> if (isKnownIntegerBitmaskExpr(expr.expr)) inferExprNumericKind(expr.expr, selfTypeName) else null
                is UnOp.Neg -> inferExprNumericKind(expr.expr, selfTypeName)
                else -> null
            }
        is Expr.Binary -> {
            when (expr.op) {
                is BinOp.Eq, is BinOp.Ne, is BinOp.Lt, is BinOp.Le, is BinOp.Gt, is BinOp.Ge, is BinOp.And, is BinOp.Or -> null
                is BinOp.Shl, is BinOp.Shr -> inferExprNumericKind(expr.left, selfTypeName)
                else -> inferExprNumericKind(expr.left, selfTypeName) ?: inferExprNumericKind(expr.right, selfTypeName)
            }
        }
        else -> null
    }

internal fun KotlinJ2KEmitter.numericKindToPureTypeName(kind: NumericKind): String =
    when (kind) {
        NumericKind.I8 -> "Byte"
        NumericKind.U8 -> "UByte"
        NumericKind.I16 -> "Short"
        NumericKind.U16 -> "UShort"
        NumericKind.I32 -> "Int"
        NumericKind.U32 -> "UInt"
        NumericKind.I64 -> "Long"
        NumericKind.U64 -> "ULong"
        NumericKind.I128 -> "I128"
        NumericKind.U128 -> "U128"
        NumericKind.F32 -> "Float"
        NumericKind.F64 -> "Double"
    }

internal fun KotlinJ2KEmitter.numericKindFromScannerCode(code: String?): NumericKind? =
    when (code) {
        "I8" -> NumericKind.I8
        "U8" -> NumericKind.U8
        "I16" -> NumericKind.I16
        "U16" -> NumericKind.U16
        "I32" -> NumericKind.I32
        "U32" -> NumericKind.U32
        "I64" -> NumericKind.I64
        "U64" -> NumericKind.U64
        "I128" -> NumericKind.I128
        "U128" -> NumericKind.U128
        "F32" -> NumericKind.F32
        "F64" -> NumericKind.F64
        else -> null
    }

internal fun KotlinJ2KEmitter.renderIntToBytesMethodCall(
    receiver: String,
    sourceReceiver: Expr,
    recvKind: NumericKind,
    rawMethod: String,
): String {
    val useUByteArray = headerContext.rawAbiMode || headerContext.finalClasses
    val isBigEndian =
        rawMethod == "to_be_bytes" ||
            (rawMethod == "to_ne_bytes" && activeCfgProfile.targetEndian == "big")
    if (recvKind == NumericKind.U128 || recvKind == NumericKind.I128) {
        if (recvKind == NumericKind.U128) usesU128 = true else usesI128 = true
        val recvAtom = if (isAtomicPostfixReceiver(sourceReceiver, receiver)) receiver else "($receiver)"
        val fn = if (isBigEndian) "toBeBytes" else "toLeBytes"
        return if (useUByteArray) "$recvAtom.$fn()" else "$recvAtom.$fn().toList()"
    }
    val ctor = if (useUByteArray) "ubyteArrayOf" else "listOf<UByte>"
    return when (recvKind) {
        NumericKind.U8, NumericKind.I8 -> {
            val recvAtom = if (isAtomicPostfixReceiver(sourceReceiver, receiver)) receiver else "($receiver)"
            "$ctor($recvAtom.toUByte())"
        }
        NumericKind.U16, NumericKind.I16 -> {
            val shifts = if (isBigEndian) listOf(8, 0) else listOf(0, 8)
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "_b.toUByte()" else "(_b shr $s).toUByte()" }
            "run { val _b = ($receiver).toUInt(); $ctor($elems) }"
        }
        NumericKind.U32, NumericKind.I32 -> {
            val shifts = if (isBigEndian) listOf(24, 16, 8, 0) else listOf(0, 8, 16, 24)
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "_b.toUByte()" else "(_b shr $s).toUByte()" }
            "run { val _b = ($receiver).toUInt(); $ctor($elems) }"
        }
        NumericKind.U64, NumericKind.I64 -> {
            val shifts =
                if (isBigEndian) {
                    listOf(56, 48, 40, 32, 24, 16, 8, 0)
                } else {
                    listOf(0, 8, 16, 24, 32, 40, 48, 56)
                }
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "_b.toUByte()" else "(_b shr $s).toUByte()" }
            "run { val _b = ($receiver).toULong(); $ctor($elems) }"
        }
        NumericKind.F32 -> {
            val shifts = if (isBigEndian) listOf(24, 16, 8, 0) else listOf(0, 8, 16, 24)
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "_b.toUByte()" else "(_b shr $s).toUByte()" }
            "run { val _b = ($receiver).toRawBits().toUInt(); $ctor($elems) }"
        }
        NumericKind.F64 -> {
            val shifts =
                if (isBigEndian) {
                    listOf(56, 48, 40, 32, 24, 16, 8, 0)
                } else {
                    listOf(0, 8, 16, 24, 32, 40, 48, 56)
                }
            val elems = shifts.joinToString(", ") { s -> if (s == 0) "_b.toUByte()" else "(_b shr $s).toUByte()" }
            "run { val _b = ($receiver).toRawBits().toULong(); $ctor($elems) }"
        }
        NumericKind.U128, NumericKind.I128 -> error("handled above")
    }
}

internal fun KotlinJ2KEmitter.renderExprClosure(expr: Expr.Closure, indent: String, selfTypeName: String?): String {
    val hasDirectReturn = exprContainsDirectClosureReturn(expr.body)
    val prevLabel = currentClosureLabel
    val prevEnclosingRet = currentEnclosingReturnRenderedType
    val prevExpectedKind = currentExpectedNumericKind
    val assignedLabel =
        if (hasDirectReturn) {
            val idx = closureLabelCounter++
            if (idx == 0) "closure" else "closure$idx"
        } else {
            null
        }
    currentClosureLabel = assignedLabel
    val explicitClosureRet = extractReturnSynType(expr.output)?.let { renderType(it, selfTypeName) }
    val closureRetKind = explicitClosureRet?.let(this::resolveNumericKind)
    currentEnclosingReturnRenderedType = explicitClosureRet
    currentExpectedNumericKind = null
    scopeBuilder.enterDef()
    return try {
        val params =
            expr.inputs.toList().mapIndexedNotNull { idx, pat ->
                renderClosureParam(pat, idx, selfTypeName).takeIf { it.isNotEmpty() && it != "_" }
            }
        scopeBuilder.scopeData.mutScope(scopeBuilder.topScopeId()).setParamCount(params.size)
        val body = expr.body
        val bodyText =
            if (body is Expr.BlockExpr) {
                val stmts = body.block.stmts
                if (stmts.isEmpty()) {
                    ""
                } else {
                    val innerIndent = "$indent    "
                    val renderedStmts =
                        stmts.mapIndexed { stmtIdx, stmt ->
                            val isLastValueExpr =
                                stmtIdx == stmts.lastIndex &&
                                    stmt is Stmt.ExprStmt &&
                                    stmt.semiToken == null &&
                                    !isExplicitControlFlowJump(stmt.expr)
                            currentExpectedNumericKind = if (isLastValueExpr) closureRetKind else null
                            try {
                                renderStmt(stmt, innerIndent, selfTypeName, isTailReturn = false)
                            } finally {
                                currentExpectedNumericKind = null
                            }
                        }
                    "\n" + renderedStmts.joinToString("\n") + "\n$indent"
                }
            } else {
                val renderedBody =
                    if (closureRetKind != null) {
                        renderTypedNumericInitializer(body, closureRetKind, indent, selfTypeName)
                    } else {
                        renderExpr(body, indent, selfTypeName)
                    }
                " $renderedBody "
            }
        val labelPrefix = assignedLabel?.let { "$it@" }.orEmpty()
        if (params.isEmpty()) {
            "$labelPrefix{$bodyText}"
        } else {
            "$labelPrefix{ ${params.joinToString(", ")} ->$bodyText}"
        }
    } finally {
        scopeBuilder.exitDef()
        currentClosureLabel = prevLabel
        currentEnclosingReturnRenderedType = prevEnclosingRet
        currentExpectedNumericKind = prevExpectedKind
    }
}

internal fun KotlinJ2KEmitter.exprContainsDirectClosureReturn(body: Expr): Boolean {
    var found = false
    val visitor =
        object : Visit() {
            override fun visitExprClosure(e: Expr.Closure) {
                // Nested closures have their own return target.
            }

            override fun visitItemFn(i: Item.Fn) {
                // Nested functions have their own return target.
            }

            override fun visitExprReturn(e: Expr.Return) {
                found = true
            }

            override fun visitExprTry(e: Expr.Try) {
                found = true
            }
        }
    visitor.visitExpr(body)
    return found
}

internal fun KotlinJ2KEmitter.renderClosureParam(pat: Pat, idx: Int, selfTypeName: String?): String =
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            if (raw == "_") {
                ""
            } else {
                val assignCount = if (pat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), pat.ident.span(), assignCount = assignCount).kotlinName
            }
        }
        is Pat.TypeAscription -> {
            val name = renderClosureParam(pat.pat, idx, selfTypeName)
            val ty = renderType(pat.ty, selfTypeName)
            if (name.isEmpty()) "" else "$name: $ty"
        }
        is Pat.Wild -> ""
        is Pat.Tuple -> {
            val rawElems = pat.elems.toList().mapIndexed { i, p -> renderClosureParam(p, i, selfTypeName) }
            if (rawElems.size >= 2 && rawElems.any { it.isNotEmpty() && it != "_" }) {
                "(${rawElems.joinToString(", ") { if (it.isEmpty()) "_" else it }})"
            } else {
                val inner = rawElems.filter { it.isNotEmpty() && it != "_" }
                when (inner.size) {
                    0 -> ""
                    1 -> inner[0]
                    else -> "(${inner.joinToString(", ")})"
                }
            }
        }
        is Pat.Reference -> renderClosureParam(pat.pat, idx, selfTypeName)
        else -> "param$idx"
    }

internal fun KotlinJ2KEmitter.collectUsedRawIdentsInExpr(expr: Expr): Set<String> {
    val used = linkedSetOf<String>()
    val visitor =
        object : Visit() {
            override fun visitExprPath(e: Expr.Path) {
                if (e.qself == null && e.path.segments.len() == 1) {
                    val firstSeg = e.path.segments.first()
                    val raw = firstSeg?.ident?.toString()?.removePrefix("r#")
                    if (raw != null) used += raw
                }
                super.visitExprPath(e)
            }
        }
    visitor.visitExpr(expr)
    return used
}

internal fun KotlinJ2KEmitter.collectUsedRawIdentsInBlock(block: Block): Set<String> {
    val used = linkedSetOf<String>()
    val visitor =
        object : Visit() {
            override fun visitExprPath(e: Expr.Path) {
                if (e.qself == null && e.path.segments.len() == 1) {
                    val firstSeg = e.path.segments.first()
                    val raw = firstSeg?.ident?.toString()?.removePrefix("r#")
                    if (raw != null) used += raw
                }
                super.visitExprPath(e)
            }
        }
    visitor.visitBlock(block)
    return used
}
