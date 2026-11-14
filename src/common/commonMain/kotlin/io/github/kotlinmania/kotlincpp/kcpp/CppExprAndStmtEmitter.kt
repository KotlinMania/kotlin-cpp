package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.LiteralPacketKind
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream
import io.github.kotlinmania.kotlincpp.packet.StringSegmentKind
import io.github.kotlinmania.kotlincpp.packet.SymbolRole
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal class LoweringBundle(
    val stream: PacketStream,
    val config: CppTargetConfig,
    val symbols: SameFileSymbolTable,
    val suspendPlan: SuspendLoweringPlan,
    val gcPlan: GcAndOwnershipPlan,
    val atomicPlan: AtomicAndThreadLocalPlan,
    val ffiPlan: FfiAndCTypePlan,
    val cfPlan: ControlFlowAndTypePlan,
    val includes: CppIncludeCollector,
    val typeTranslator: CppTypeTranslator,
    val knownFunctionNames: Set<String> = emptySet(),
    val isDocExample: Boolean = false,
)

/**
 * Emits C++23 statements and expressions from lowered [PacketFrame] trees while preserving
 * interior comments, left-to-right `hasSuspendCallInTail` temporaries, non-IIFE suspend
 * control-flow result slots, `atomicfu` operations, `cinterop` idioms, and `KONAN_GC` barriers.
 */
internal class InlinedCallerLambdaSite(
    val lambdaBodyFrame: PacketFrame,
    val lambdaParamNames: List<String>,
    val callerEmitter: CppExprAndStmtEmitter,
)

internal class CppExprAndStmtEmitter(
    private val bundle: LoweringBundle,
    private val activeTypeParams: Set<String> = emptySet(),
    private val enclosingClass: KtClassSymbol? = null,
    private var receiverSubstitution: String? = null,
    private var extensionReceiverClass: KtClassSymbol? = null,
    private var extensionReceiverIsPointer: Boolean = false,
    private var extensionReceiverSimpleName: String? = null,
    private val unitReturnExprOverride: String? = null,
    private val isVoidReturnContext: Boolean = false,
    private val enclosingFunctionReturnType: String? = null,
    private val inlinedLambdaSubstitutions: Map<String, InlinedCallerLambdaSite> = emptyMap(),
    private val inlineReturnTargetVar: String? = null,
    private val inlineReturnExitLabel: String? = null,
    private val localPolymorphicClasses: MutableSet<String> = LinkedHashSet(),
    private val localExtensionFunctions: MutableMap<String, String> = LinkedHashMap(),
    private val inDefaultParameterExpr: Boolean = false,
    private val activeInlineFunFrames: Set<Int> = emptySet(),
) {
    var usedInlineReturnExitLabel: Boolean = false
    private val stream: PacketStream get() = bundle.stream
    private val includes: CppIncludeCollector get() = bundle.includes
    private val localBindings = ArrayDeque<MutableMap<String, String>>()
    private val exceptionPtrScopes = ArrayDeque<MutableSet<String>>()
    private val stdAnyScopes = ArrayDeque<MutableSet<String>>()
    private val localPolyVarScopes = ArrayDeque<MutableSet<String>>()
    private val preLoweredElvisExprs = HashMap<Int, String>()
    private var synthCounter = 0

    init {
        localBindings.addLast(LinkedHashMap())
        exceptionPtrScopes.addLast(LinkedHashSet())
        stdAnyScopes.addLast(LinkedHashSet())
        localPolyVarScopes.addLast(LinkedHashSet())
    }

    fun bindLocalName(
        kotlinName: String,
        cppExpr: String,
    ) {
        localBindings.last()[kotlinName] = cppExpr
    }

    fun bindValueParam(vp: KtValueParamInfo) {
        bindLocalName(vp.name, vp.cppName)
        val tfIdx = vp.typeFrameIndex ?: return
        val tf = stream.frames[tfIdx]
        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf)
        if (simple == "Any") {
            stdAnyScopes.last().add(vp.name)
        } else if (simple in EXCEPTION_PTR_KOTLIN_TYPES) {
            exceptionPtrScopes.last().add(vp.name)
        } else {
            val cppT =
                bundle.typeTranslator
                    .translateTypeFrame(tf, CppTypePosition.PARAMETER)
                    .removePrefix("const ")
                    .removeSuffix("&")
                    .trim()
            if (cppT.endsWith("*") || cppT.startsWith("std::shared_ptr<")) {
                localPolyVarScopes.last().add(vp.name)
            }
        }
    }

    private fun markExceptionPtrVar(kotlinName: String) {
        exceptionPtrScopes.last().add(kotlinName)
    }

    private fun markStdAnyVar(kotlinName: String) {
        stdAnyScopes.last().add(kotlinName)
    }

    private fun unmarkStdAnyVar(kotlinName: String) {
        for (i in stdAnyScopes.size - 1 downTo 0) {
            stdAnyScopes[i].remove(kotlinName)
        }
    }

    private fun markLocalPolyVar(kotlinName: String) {
        localPolyVarScopes.last().add(kotlinName)
    }

    private fun isLocalPolyVar(kotlinName: String): Boolean {
        for (i in localPolyVarScopes.size - 1 downTo 0) {
            if (kotlinName in localPolyVarScopes[i]) return true
        }
        return false
    }

    private fun isExceptionPtrKotlinVar(kotlinName: String): Boolean {
        for (i in exceptionPtrScopes.size - 1 downTo 0) {
            if (kotlinName in exceptionPtrScopes[i]) return true
        }
        return false
    }

    private fun isStdAnyKotlinVar(kotlinName: String): Boolean {
        for (i in stdAnyScopes.size - 1 downTo 0) {
            if (kotlinName in stdAnyScopes[i]) return true
        }
        return false
    }

    private fun isExceptionPtrExpr(exprFrame: PacketFrame): Boolean {
        val unwrapped = unwrapTransparentFrame(exprFrame)
        if (unwrapped.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val name = FrameAstHelpers.directIdentPacket(stream, unwrapped)?.unescapedName ?: return false
            if (isExceptionPtrKotlinVar(name)) return true
            val prop = bundle.symbols.propertiesByName[name]
            val propSimple = prop?.returnTypeFrameIndex?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
            if (propSimple in EXCEPTION_PTR_KOTLIN_TYPES) return true
        }
        return false
    }

    private fun isStdAnyExpr(exprFrame: PacketFrame): Boolean {
        val unwrapped = unwrapTransparentFrame(exprFrame)
        if (unwrapped.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val name = FrameAstHelpers.directIdentPacket(stream, unwrapped)?.unescapedName ?: return false
            if (isStdAnyKotlinVar(name)) return true
            val prop = bundle.symbols.propertiesByName[name]
            val propSimple = prop?.returnTypeFrameIndex?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
            if (propSimple == "Any") return true
        }
        return false
    }

    private fun <T> withLexicalScope(block: () -> T): T {
        localBindings.addLast(LinkedHashMap())
        exceptionPtrScopes.addLast(LinkedHashSet())
        stdAnyScopes.addLast(LinkedHashSet())
        localPolyVarScopes.addLast(LinkedHashSet())
        val savedRecvSub = receiverSubstitution
        val savedExtCls = extensionReceiverClass
        val savedExtPtr = extensionReceiverIsPointer
        val savedExtSimple = extensionReceiverSimpleName
        return try {
            block()
        } finally {
            localBindings.removeLast()
            exceptionPtrScopes.removeLast()
            stdAnyScopes.removeLast()
            localPolyVarScopes.removeLast()
            receiverSubstitution = savedRecvSub
            extensionReceiverClass = savedExtCls
            extensionReceiverIsPointer = savedExtPtr
            extensionReceiverSimpleName = savedExtSimple
        }
    }

    private fun resolveBoundName(kotlinName: String): String? {
        for (i in localBindings.size - 1 downTo 0) {
            localBindings[i][kotlinName]?.let { return it }
        }
        return null
    }

    private fun freshTemp(prefix: String): String = "${prefix}_${synthCounter++}"

    fun emitBlockOrExprBody(
        bodyFrame: PacketFrame,
        isExpressionBody: Boolean,
        isVoidReturn: Boolean,
        indent: String,
        extraEntryLines: List<String> = emptyList(),
    ): String {
        if (!isExpressionBody && bodyFrame.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            return emitBlock(bodyFrame, indent, isValueBlock = false, resultTargetVar = null, extraEntryLines = extraEntryLines)
        }
        return buildString {
            append("{\n")
            val innerIndent = "$indent    "
            for (line in extraEntryLines) {
                append(innerIndent).append(line).append('\n')
            }
            emitHoistedPrefixesForStmt(this, bodyFrame.frameIndex, innerIndent)
            if (isVoidReturn) {
                append(emitStatement(bodyFrame, innerIndent, inValuePosition = false, resultTargetVar = null))
            } else {
                append(emitValueAssignmentOrReturn(bodyFrame, innerIndent, targetReturn = true, targetVar = null))
            }
            append(indent).append('}')
        }
    }

    fun emitBlock(
        blockFrame: PacketFrame,
        indent: String,
        isValueBlock: Boolean,
        resultTargetVar: String?,
        extraEntryLines: List<String> = emptyList(),
        smartCasts: List<SmartCastBinding> = emptyList(),
    ): String =
        withLexicalScope {
            buildString {
                append("{\n")
                val innerIndent = "$indent    "
                for (line in extraEntryLines) {
                    append(innerIndent).append(line).append('\n')
                }
                emitSmartCastBindings(this, smartCasts, innerIndent)

                val childStmts = ArrayList<PacketFrame>()
                for (i in 0 until blockFrame.childCount) {
                    val ref = blockFrame.childRefAt(i)
                    if (ref >= 0) {
                        childStmts.add(stream.frames[ref])
                    }
                }

                // Walk raw children of BLOCK_ID so interior comments between statements stay in exact source order!
                var stmtOrdinal = 0
                for (i in 0 until blockFrame.childCount) {
                    val ref = blockFrame.childRefAt(i)
                    if (ref < 0) {
                        val pkt = stream.packets[-(ref + 1)]
                        if (pkt is SyntaxPacket.CommentPacket && pkt.portLintDirectiveKind == null) {
                            val formatted =
                                FrameAstHelpers.formatCommentForCpp(
                                    comment = pkt,
                                    indent = innerIndent,
                                    knownFunctionNames = bundle.knownFunctionNames,
                                    allowFenceTranslation = !bundle.isDocExample,
                                )
                            if (formatted.isNotEmpty()) {
                                append(formatted).append('\n')
                            }
                        }
                    } else {
                        val stmtFrame = stream.frames[ref]
                        val isLast = stmtOrdinal == childStmts.lastIndex
                        stmtOrdinal += 1

                        emitHoistedPrefixesForStmt(this, stmtFrame.frameIndex, innerIndent)
                        if (isLast && (isValueBlock || resultTargetVar != null)) {
                            append(
                                emitValueAssignmentOrReturn(
                                    exprFrame = stmtFrame,
                                    indent = innerIndent,
                                    targetReturn = resultTargetVar == null,
                                    targetVar = resultTargetVar,
                                ),
                            )
                        } else {
                            append(
                                emitStatement(
                                    stmtFrame = stmtFrame,
                                    indent = innerIndent,
                                    inValuePosition = false,
                                    resultTargetVar = null,
                                ),
                            )
                        }
                        emitDeadSpillResetsAfterStmt(this, stmtFrame.frameIndex, innerIndent)
                    }
                }
                append(indent).append('}')
            }
        }

    private fun emitSmartCastBindings(
        out: StringBuilder,
        smartCasts: List<SmartCastBinding>,
        indent: String,
    ) {
        for (sc in smartCasts) {
            val targetCppType =
                bundle.typeTranslator
                    .translateTypeFrame(stream.frames[sc.targetTypeFrameIndex], CppTypePosition.TYPE_ARGUMENT)
                    .removeSuffix("&")
                    .removeSuffix("*")
            val srcExpr =
                if (sc.kotlinVarName == "this") {
                    receiverSubstitution ?: "(*this)"
                } else {
                    resolveBoundName(sc.kotlinVarName) ?: CppNamingPolicy.toSnakeCase(sc.kotlinVarName)
                }
            includes.addSystem("any")
            includes.addSystem("memory")
            includes.addSystem("type_traits")
            out
                .append(indent)
                .append("auto* ")
                .append(sc.narrowedCppName)
                .append(" = ([&](auto&& kxs_sc_src) -> ")
                .append(targetCppType)
                .append("* { using KxsScSrc = std::decay_t<decltype(kxs_sc_src)>; ")
                .append("if constexpr (std::is_same_v<KxsScSrc, std::any>) { ")
                .append("return const_cast<")
                .append(targetCppType)
                .append("*>(std::any_cast<const ")
                .append(targetCppType)
                .append(">(std::addressof(kxs_sc_src))); ")
                .append("} else if constexpr (std::is_pointer_v<KxsScSrc>) { ")
                .append("if constexpr (std::is_polymorphic_v<std::remove_pointer_t<KxsScSrc>>) return dynamic_cast<")
                .append(targetCppType)
                .append("*>(const_cast<std::remove_cv_t<std::remove_pointer_t<KxsScSrc>>*>(kxs_sc_src)); else return reinterpret_cast<")
                .append(targetCppType)
                .append("*>(const_cast<std::remove_cv_t<std::remove_pointer_t<KxsScSrc>>*>(kxs_sc_src)); ")
                .append("} else if constexpr (requires { kxs_sc_src.get(); }) { ")
                .append("using KxsScPt = std::remove_pointer_t<decltype(kxs_sc_src.get())>; ")
                .append("if constexpr (std::is_polymorphic_v<KxsScPt>) return dynamic_cast<")
                .append(targetCppType)
                .append("*>(const_cast<std::remove_cv_t<KxsScPt>*>(kxs_sc_src.get())); else return reinterpret_cast<")
                .append(targetCppType)
                .append("*>(const_cast<std::remove_cv_t<KxsScPt>*>(kxs_sc_src.get())); ")
                .append("} else if constexpr (std::is_polymorphic_v<KxsScSrc>) { ")
                .append("return dynamic_cast<")
                .append(targetCppType)
                .append("*>(const_cast<KxsScSrc*>(std::addressof(kxs_sc_src))); ")
                .append("} else { return nullptr; } }(")
                .append(srcExpr)
                .append("));\n")
            bindLocalName(sc.kotlinVarName, "(*${sc.narrowedCppName})")
            unmarkStdAnyVar(sc.kotlinVarName)
            if (sc.kotlinVarName == "this") {
                receiverSubstitution = "(*${sc.narrowedCppName})"
                val narrowedSimple =
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[sc.targetTypeFrameIndex])
                extensionReceiverClass = narrowedSimple?.let { lookupAccessibleClassByName(it) }
                extensionReceiverIsPointer = false
                extensionReceiverSimpleName = narrowedSimple
            }
        }
    }

    private fun emitHoistedPrefixesForStmt(
        out: StringBuilder,
        stmtFrameIndex: Int,
        indent: String,
    ) {
        val evalTemps = bundle.suspendPlan.hoistedTempsBeforeStmtFrame[stmtFrameIndex].orEmpty()
        for (ht in evalTemps) {
            val exprCode = emitExprIgnoringHoist(stream.frames[ht.exprFrameIndex])
            out
                .append(indent)
                .append("auto ")
                .append(ht.tempCppName)
                .append(" = ")
                .append(exprCode)
                .append(";\n")
        }

        val cfValues = bundle.cfPlan.hoistedSuspendControlFlowsBeforeStmt[stmtFrameIndex].orEmpty()
        for (cf in cfValues) {
            includes.addSystem("optional")
            val cfFrame = stream.frames[cf.controlFlowFrameIndex]
            val slotType = inferControlFlowResultType(cfFrame)
            out
                .append(indent)
                .append("std::optional<")
                .append(slotType)
                .append("> ")
                .append(cf.slotCppName)
                .append(";\n")
            out.append(emitStatement(cfFrame, indent, inValuePosition = true, resultTargetVar = cf.slotCppName))
        }
    }

    private fun emitDeadSpillResetsAfterStmt(
        out: StringBuilder,
        stmtFrameIndex: Int,
        indent: String,
    ) {
        val resets = bundle.gcPlan.deadResetsAfterStmt[stmtFrameIndex].orEmpty()
        for (r in resets) {
            if (r.isKonanExternalRc) {
                out.append(indent).append(r.cppVarName).append(".reset();\n")
            } else {
                out.append(indent).append(r.cppVarName).append(" = {};\n")
            }
        }
    }

    private tailrec fun unwrapTransparentFrame(frame: PacketFrame): PacketFrame =
        when (frame.nodeTypeId) {
            KtNodeTypes.ANNOTATED_EXPRESSION_ID -> {
                val inner =
                    FrameAstHelpers
                        .childFrames(stream, frame)
                        .lastOrNull {
                            it.nodeTypeId != KtNodeTypes.ANNOTATION_ENTRY_ID &&
                                it.nodeTypeId != KtNodeTypes.ANNOTATION_ID
                        }
                if (inner != null) unwrapTransparentFrame(inner) else frame
            }
            KtNodeTypes.LABELED_EXPRESSION_ID -> {
                val inner =
                    FrameAstHelpers
                        .childFrames(stream, frame)
                        .lastOrNull {
                            it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID &&
                                it.nodeTypeId != KtNodeTypes.LABEL_ID
                        }
                if (inner != null) unwrapTransparentFrame(inner) else frame
            }
            else -> frame
        }

    private fun emitValueAssignmentOrReturn(
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
                "${indent}return std::move(*${hoistedCf.slotCppName});\n"
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
                if (rhsUnwrapped.nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES) {
                    val elvisTemp = freshTemp("kxs_elvis")
                    val lhsCode = emitExpression(binChildren[0])
                    val transferStmt =
                        emitStatement(rhsUnwrapped, "$indent    ", inValuePosition = false, resultTargetVar = null)
                    val derefExpr =
                        if (bundle.isDocExample) {
                            includes.addSystem("type_traits")
                            "([&](auto&& kxs_ev) -> decltype(auto) { " +
                                "if constexpr (std::is_pointer_v<std::decay_t<decltype(kxs_ev)>>) " +
                                "return kxs_ev; else return *kxs_ev; }($elvisTemp))"
                        } else {
                            includes.needsNullUnwrapDef = true
                            includes.addSystem("type_traits")
                            includes.addSystem("utility")
                            "kxs_unwrap_opt($elvisTemp)"
                        }
                    val assignLine =
                        if (targetReturn) {
                            "${indent}return $derefExpr;\n"
                        } else {
                            "$indent$targetVar = $derefExpr;\n"
                        }
                    return buildString {
                        append(indent)
                        append("auto ")
                        append(elvisTemp)
                        append(" = ")
                        append(lhsCode)
                        append(";\n")
                        append(indent)
                        append("if (!")
                        append(elvisTemp)
                        append(") {\n")
                        append(transferStmt)
                        append(indent)
                        append("}\n")
                        append(assignLine)
                    }
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
                if (targetReturn && enclosingFunctionReturnType == "Unit") {
                    "$stmtCode${indent}return Unit{};\n"
                } else {
                    stmtCode
                }
            }

            KtNodeTypes.IF_ID ->
                if (targetReturn && canEmitTernaryIf(unwrappedExpr)) {
                    "${indent}return ${emitTernaryIfCore(unwrappedExpr)};\n"
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
                    if (enclosingFunctionReturnType?.startsWith("std::optional<") == true) {
                        includes.addSystem("optional")
                        return "${indent}return std::nullopt;\n"
                    }
                    if (enclosingFunctionReturnType == "std::any") {
                        return "${indent}return {};\n"
                    }
                }
                val rendered = emitExpression(unwrappedExpr)
                if (PacketFlags.hasFlag(unwrappedExpr.flags, PacketFlags.DIVERGING_EXPR)) {
                    includes.addSystem("utility")
                    "$indent$rendered;\n${indent}std::unreachable();\n"
                } else if (targetReturn) {
                    "${indent}return $rendered;\n"
                } else {
                    val coercedRhs =
                        if (rendered == "nullptr") "{}" else rendered
                    "$indent$targetVar = $coercedRhs;\n"
                }
            }
        }
    }

    fun emitStatement(
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

    private fun emitStatementBodyWithoutLeadingComments(
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
                            val elvisTemp = freshTemp("kxs_elvis")
                            val lhsCode = emitExpression(ch[0])
                            val rhsTransfer =
                                emitStatement(
                                    unwrapTransparentFrame(ch[2]),
                                    "$indent    ",
                                    inValuePosition = false,
                                    resultTargetVar = null,
                                )
                            append(indent)
                                .append("auto ")
                                .append(elvisTemp)
                                .append(" = ")
                                .append(lhsCode)
                                .append(";\n")
                            append(indent)
                                .append("if (!")
                                .append(elvisTemp)
                                .append(") {\n")
                            append(rhsTransfer)
                            append(indent).append("}\n")
                            preLoweredElvisExprs[elvisFrame.frameIndex] =
                                if (bundle.isDocExample) {
                                    "([&](auto&& kxs_ev) -> decltype(auto) { if constexpr (std::is_pointer_v<std::decay_t<decltype(" +
                                        "kxs_ev)>>) return kxs_ev; else return *kxs_ev; }($elvisTemp))"
                                } else {
                                    includes.needsNullUnwrapDef = true
                                    includes.addSystem("type_traits")
                                    includes.addSystem("utility")
                                    "kxs_unwrap_opt($elvisTemp)"
                                }
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
                    if (rhsUnwrapped.nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES) {
                        val lhsCode = emitExpression(binChildren[0])
                        val transferStmt =
                            emitStatement(rhsUnwrapped, "$indent    ", inValuePosition = false, resultTargetVar = null)
                        return "${indent}if (!($lhsCode)) {\n$transferStmt$indent}\n"
                    }
                } else if (opTokId == KtTokens.EQ_ID) {
                    val rhsAssign = unwrapTransparentFrame(binChildren[2])
                    if (rhsAssign.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
                        val elvisCh = FrameAstHelpers.childFrames(stream, rhsAssign)
                        if (elvisCh.size >= 3 &&
                            FrameAstHelpers.operationTokenId(stream, elvisCh[1]) == KtTokens.ELVIS_ID
                        ) {
                            val elvisRhs = unwrapTransparentFrame(elvisCh[2])
                            if (elvisRhs.nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES) {
                                val elvisTemp = freshTemp("kxs_elvis")
                                val lhsTarget = emitExpression(binChildren[0])
                                val elvisLhsCode = emitExpression(elvisCh[0])
                                val transferStmt =
                                    emitStatement(elvisRhs, "$indent    ", inValuePosition = false, resultTargetVar = null)
                                val derefElvisExpr =
                                    if (bundle.isDocExample) {
                                        includes.addSystem("type_traits")
                                        "([&](auto&& kxs_ev) -> decltype(auto) { if constexpr (std::is_pointer_v<std::decay_t<decltype(kxs_ev)>>) return kxs_ev; else return *kxs_ev; }($elvisTemp))"
                                    } else {
                                        includes.needsNullUnwrapDef = true
                                        includes.addSystem("type_traits")
                                        includes.addSystem("utility")
                                        "kxs_unwrap_opt($elvisTemp)"
                                    }
                                return buildString {
                                    append(indent)
                                        .append("auto ")
                                        .append(elvisTemp)
                                        .append(" = ")
                                        .append(elvisLhsCode)
                                        .append(";\n")
                                    append(indent).append("if (!").append(elvisTemp).append(") {\n")
                                    append(transferStmt)
                                    append(indent).append("}\n")
                                    append(indent)
                                        .append(lhsTarget)
                                        .append(" = ")
                                        .append(derefElvisExpr)
                                        .append(";\n")
                                }
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

    private fun emitLocalPropertyDecl(
        propFrame: PacketFrame,
        indent: String,
    ): String {
        val ident = FrameAstHelpers.directIdentPacket(stream, propFrame)
        val kotlinName = ident?.unescapedName ?: "local_val"
        val cppName = ident?.targetCppName ?: CppNamingPolicy.toSnakeCase(kotlinName)
        val isVar = FrameAstHelpers.hasDirectKeyword(stream, propFrame, KtTokens.VAR_KEYWORD_ID)
        val (_, typeFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, propFrame)
        val initFrame = FrameAstHelpers.extractInitializerAfterEquals(stream, propFrame)

        // 1. Check `val x = alloc<T>()` or `allocArray<T>(n)` inside `memScoped`
        if (initFrame != null) {
            val cInteropAlloc = bundle.ffiPlan.cInteropOpsByExprFrame[initFrame.frameIndex]
            if (cInteropAlloc != null && cInteropAlloc.kind == CInteropOpKind.ALLOC_SCALAR) {
                val elemType =
                    cInteropAlloc.targetTypeFrameIndex
                        ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE) }
                        ?: "std::int32_t"
                bindLocalName(kotlinName, cppName)
                return "$indent$elemType $cppName{};\n"
            }
            if (cInteropAlloc != null && cInteropAlloc.kind == CInteropOpKind.ALLOC_ARRAY) {
                includes.addSystem("vector")
                val elemType =
                    cInteropAlloc.targetTypeFrameIndex
                        ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT) }
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
                val declType =
                    when {
                        typeFrame != null ->
                            bundle.typeTranslator.translateTypeFrame(typeFrame, CppTypePosition.VALUE, propFrame.flags)
                        targetInlineFn?.returnTypeFrameIndex != null ->
                            bundle.typeTranslator.translateTypeFrame(
                                stream.frames[targetInlineFn.returnTypeFrameIndex],
                                CppTypePosition.VALUE,
                            )
                        else -> {
                            includes.addSystem("any")
                            "std::any"
                        }
                    }
                bindLocalName(kotlinName, cppName)
                return buildString {
                    append(indent)
                        .append(declType)
                        .append(' ')
                        .append(cppName)
                        .append("{};\n")
                    append(emitInlineExpansionAsStatement(inlineExp, indent, targetVar = cppName, targetReturn = false))
                }
            }
        }

        // 3. Check `atomicfu` local atomic variable
        bundle.atomicPlan.atomicFieldsByPropFrame[propFrame.frameIndex]?.let { af ->
            includes.addSystem("atomic")
            val elemType =
                af.explicitElementTypeFrameIndex
                    ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.TYPE_ARGUMENT) }
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

        val cppType =
            if (typeFrame != null) {
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, typeFrame)
                if (simple == "Any") markStdAnyVar(kotlinName)
                if (simple in EXCEPTION_PTR_KOTLIN_TYPES) markExceptionPtrVar(kotlinName)
                bundle.typeTranslator.translateTypeFrame(typeFrame, CppTypePosition.VALUE, propFrame.flags)
            } else {
                "auto"
            }
        val constQual = if (!isVar && cppType != "void") "const " else ""
        if (initFrame != null && initFrame.frameIndex !in preLoweredElvisExprs && initFrame.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val ch = FrameAstHelpers.childFrames(stream, initFrame)
            if (ch.size >= 3 &&
                FrameAstHelpers.operationTokenId(stream, ch[1]) == KtTokens.ELVIS_ID
            ) {
                val rhsUnwrapped = unwrapTransparentFrame(ch[2])
                if (rhsUnwrapped.nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES) {
                    val elvisTemp = freshTemp("kxs_elvis")
                    val lhsCode = emitExpression(ch[0])
                    val transferStmt =
                        emitStatement(rhsUnwrapped, "$indent    ", inValuePosition = false, resultTargetVar = null)
                    val derefElvisExpr =
                        if (bundle.isDocExample) {
                            includes.addSystem("type_traits")
                            "([&](auto&& kxs_ev) -> decltype(auto) { if constexpr (std::is_pointer_v<std::decay_t<decltype(kxs_ev)>>) return kxs_ev; else return *kxs_ev; }($elvisTemp))"
                        } else {
                            includes.needsNullUnwrapDef = true
                            includes.addSystem("type_traits")
                            includes.addSystem("utility")
                            "kxs_unwrap_opt($elvisTemp)"
                        }
                    bindLocalName(kotlinName, cppName)
                    return buildString {
                        append(indent)
                        append("auto ")
                        append(elvisTemp)
                        append(" = ")
                        append(lhsCode)
                        append(";\n")
                        append(indent)
                        append("if (!")
                        append(elvisTemp)
                        append(") {\n")
                        append(transferStmt)
                        append(indent)
                        append("}\n")
                        append(indent)
                        append(constQual)
                        append(cppType)
                        append(' ')
                        append(cppName)
                        append(" = ")
                        append(derefElvisExpr)
                        append(";\n")
                    }
                }
            }
        }
        val hadOuterLocal = resolveBoundName(kotlinName) != null
        val rawInitBeforeBind = initFrame?.let { emitExpression(it) }
        bindLocalName(kotlinName, cppName)
        if (cppType != "auto" && (cppType.endsWith("*") || cppType.startsWith("std::shared_ptr<"))) {
            markLocalPolyVar(kotlinName)
        }
        if (initFrame != null) {
            val unwrappedInit = unwrapTransparentFrame(initFrame)
            if (unwrappedInit.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                val initCallee = extractCalleeSimpleName(unwrappedInit)
                if (initCallee in localPolymorphicClasses) {
                    markLocalPolyVar(kotlinName)
                }
            } else if (unwrappedInit.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID &&
                enclosingClass != null &&
                isSharedThisClass(enclosingClass.kotlinName)
            ) {
                markLocalPolyVar(kotlinName)
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
            val coercedInit =
                when {
                    rawInit == "nullptr" && cppType.startsWith("std::optional<") -> {
                        includes.addSystem("optional")
                        "std::nullopt"
                    }
                    rawInit == "nullptr" && cppType == "std::any" -> "{}"
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

    private fun emitLocalFunctionDecl(
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
                isVoidReturnContext = retType == "void" || retType == "[[noreturn]] void",
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

    private fun frameContainsIdentifier(
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

    private fun emitLocalClassOrObjectDecl(
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
                                FrameAstHelpers.childFramesOfType(stream, al, KtNodeTypes.VALUE_ARGUMENT_ID).mapNotNull { va ->
                                    FrameAstHelpers
                                        .childFrames(stream, va)
                                        .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                                        ?.let { emitExpression(it) }
                                }
                            }.orEmpty()
                    superCtorInits.add("$baseType(${args.joinToString(", ")})")
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
                        val isVoidM = retT == "void" || retT == "[[noreturn]] void"
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
                        val renderedParams =
                            vps.joinToString(", ") { vp ->
                                mEmitter.bindValueParam(vp)
                                val pT =
                                    vp.typeFrameIndex
                                        ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER) }
                                        ?: "auto"
                                "$pT ${vp.cppName}"
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

    private fun emitDestructuringDecl(
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

    private fun emitReturnStmt(
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
            if (unitReturnExprOverride != null) {
                return "${indent}return $unitReturnExprOverride;\n"
            }
            if (isVoidReturnContext) {
                return "${indent}return;\n"
            }
        }
        if (unwrappedRet.nodeTypeId == KtNodeTypes.NULL_ID) {
            if (enclosingFunctionReturnType?.startsWith("std::optional<") == true) {
                includes.addSystem("optional")
                return "${indent}return std::nullopt;\n"
            }
            if (enclosingFunctionReturnType == "std::any") {
                return "${indent}return {};\n"
            }
        }
        if (unwrappedRet.nodeTypeId == KtNodeTypes.IF_ID ||
            unwrappedRet.nodeTypeId == KtNodeTypes.WHEN_ID ||
            unwrappedRet.nodeTypeId == KtNodeTypes.TRY_ID ||
            unwrappedRet.nodeTypeId == KtNodeTypes.BLOCK_ID
        ) {
            return emitValueAssignmentOrReturn(exprChild, indent, targetReturn = true, targetVar = null)
        }
        if (unwrappedRet.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val binCh = FrameAstHelpers.childFrames(stream, unwrappedRet)
            if (binCh.size >= 3 && FrameAstHelpers.operationTokenId(stream, binCh[1]) == KtTokens.ELVIS_ID) {
                val rhsUnwrapped = unwrapTransparentFrame(binCh[2])
                if (rhsUnwrapped.nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES) {
                    return emitValueAssignmentOrReturn(exprChild, indent, targetReturn = true, targetVar = null)
                }
            }
        }
        return "${indent}return ${emitExpression(exprChild)};\n"
    }

    private fun emitThrowStmt(
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

    private fun emitThrowableExpr(exprFrame: PacketFrame): String {
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

    private fun emitIfStatement(
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

    private fun computeNullCheckUnwrapBindings(
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

    private fun frameReassignsVariable(
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

    private fun emitBranchBody(
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

    private fun emitWhenStatement(
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
        val useCppSwitch = (subjectFrame != null || subjectPropDecl != null) && canEmitCppSwitch(entries)
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
                if (subjectExprCode != null && canEmitCppSwitch(entries)) {
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

                        if (idx == 0 && !isElse) {
                            val condCode = emitWhenEntryCondition(condFrames, subjectExprCode)
                            append(workIndent).append("if (").append(condCode).append(") ")
                        } else if (!isElse) {
                            val condCode = emitWhenEntryCondition(condFrames, subjectExprCode)
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

    private fun emitWhenEntryCondition(
        condFrames: List<PacketFrame>,
        subjectCode: String?,
    ): String {
        if (condFrames.isEmpty()) return "true"
        val parts = ArrayList<String>(condFrames.size)
        for (cf in condFrames) {
            when (cf.nodeTypeId) {
                KtNodeTypes.WHEN_CONDITION_EXPRESSION_ID -> {
                    val inner = FrameAstHelpers.childFrames(stream, cf).firstOrNull()
                    val expr = inner?.let { emitExpression(it) } ?: "false"
                    parts.add(if (subjectCode != null) "($subjectCode == $expr)" else expr)
                }
                KtNodeTypes.WHEN_CONDITION_IS_PATTERN_ID -> {
                    val negated = FrameAstHelpers.hasDirectKeyword(stream, cf, KtTokens.NOT_IS_ID)
                    val tr = FrameAstHelpers.firstChildFrame(stream, cf, KtNodeTypes.TYPE_REFERENCE_ID)
                    val cppType =
                        tr
                            ?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) }
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

    private fun emitRangeMembershipCheck(
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

    private fun emitTryStatement(
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

    private fun emitWhileStmt(
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

    private fun emitDoWhileStmt(
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

    private fun emitForStmt(
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
            if (rangeExpr != null && rangeExpr.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
                val ch = FrameAstHelpers.childFrames(stream, rangeExpr)
                if (ch.size >= 3) {
                    val op = FrameAstHelpers.operationSymbolName(stream, ch[1])
                    val lo = emitExpression(ch[0])
                    val hi = emitExpression(ch[2])
                    when (op) {
                        ".." ->
                            return@withLexicalScope "${indent}for (auto $loopVarCpp = $lo; $loopVarCpp <= $hi; ++$loopVarCpp) $bodyCode\n"
                        "..<", "until" ->
                            return@withLexicalScope "${indent}for (auto $loopVarCpp = $lo; $loopVarCpp < $hi; ++$loopVarCpp) $bodyCode\n"
                        "downTo" ->
                            return@withLexicalScope "${indent}for (auto $loopVarCpp = $lo; $loopVarCpp >= $hi; --$loopVarCpp) $bodyCode\n"
                    }
                }
            }
            val iterCode = rangeExpr?.let { emitExpression(it) } ?: "{}"
            "${indent}for (auto&& $loopVarCpp : $iterCode) $bodyCode\n"
        }
    }

    private fun emitInlineExpansionAsStatement(
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

        return withLexicalScope {
            buildString {
                append(indent).append("{\n")
                val inner = "$indent    "
                when (inlineExp.kind) {
                    BuiltinInlineKind.LET -> {
                        val recvCode =
                            inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) }
                                ?: (receiverSubstitution ?: "(*this)")
                        val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val pCpp = CppNamingPolicy.toSnakeCase(pName)
                        append(inner)
                            .append("auto&& ")
                            .append(pCpp)
                            .append(" = ")
                            .append(recvCode)
                            .append(";\n")
                        bindLocalName(pName, pCpp)
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar, targetReturn)
                    }
                    BuiltinInlineKind.ALSO -> {
                        val recvCode =
                            inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) }
                                ?: (receiverSubstitution ?: "(*this)")
                        val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val pCpp = CppNamingPolicy.toSnakeCase(pName)
                        append(inner)
                            .append("auto&& ")
                            .append(pCpp)
                            .append(" = ")
                            .append(recvCode)
                            .append(";\n")
                        bindLocalName(pName, pCpp)
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar = null, targetReturn = false)
                        if (targetVar != null) {
                            append(inner)
                                .append(targetVar)
                                .append(" = ")
                                .append(pCpp)
                                .append(";\n")
                        } else if (targetReturn) {
                            append(inner).append("return ").append(pCpp).append(";\n")
                        }
                    }
                    BuiltinInlineKind.RUN,
                    BuiltinInlineKind.WITH,
                    -> {
                        val recvIdx =
                            inlineExp.receiverExprFrameIndex ?: inlineExp.argExprFrameIndices.firstOrNull()
                        if (recvIdx != null) {
                            val recvTemp = freshTemp("kxs_recv")
                            append(inner)
                                .append("auto&& ")
                                .append(recvTemp)
                                .append(" = ")
                                .append(emitExpression(stream.frames[recvIdx]))
                                .append(";\n")
                            val nestedEmitter =
                                CppExprAndStmtEmitter(
                                    bundle = bundle,
                                    activeTypeParams = activeTypeParams,
                                    enclosingClass = enclosingClass,
                                    receiverSubstitution = recvTemp,
                                    enclosingFunctionReturnType = enclosingFunctionReturnType,
                                    inlinedLambdaSubstitutions = inlinedLambdaSubstitutions,
                                )
                            nestedEmitter.appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar, targetReturn)
                        } else {
                            appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar, targetReturn)
                        }
                    }
                    BuiltinInlineKind.APPLY -> {
                        val recvCode =
                            inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) }
                                ?: (receiverSubstitution ?: "(*this)")
                        val recvTemp = freshTemp("kxs_apply")
                        append(inner)
                            .append("auto&& ")
                            .append(recvTemp)
                            .append(" = ")
                            .append(recvCode)
                            .append(";\n")
                        val nestedEmitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                activeTypeParams = activeTypeParams,
                                enclosingClass = enclosingClass,
                                receiverSubstitution = recvTemp,
                                enclosingFunctionReturnType = enclosingFunctionReturnType,
                                inlinedLambdaSubstitutions = inlinedLambdaSubstitutions,
                            )
                        nestedEmitter.appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar = null, targetReturn = false)
                        if (targetVar != null) {
                            append(inner)
                                .append(targetVar)
                                .append(" = ")
                                .append(recvTemp)
                                .append(";\n")
                        } else if (targetReturn) {
                            append(inner).append("return ").append(recvTemp).append(";\n")
                        }
                    }
                    BuiltinInlineKind.USE -> {
                        val recvCode = inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "this"
                        val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val pCpp = CppNamingPolicy.toSnakeCase(pName)
                        val guardStruct = freshTemp("KxsCloseGuard")
                        val guardVar = freshTemp("kxs_close")
                        append(inner)
                            .append("auto&& ")
                            .append(pCpp)
                            .append(" = ")
                            .append(recvCode)
                            .append(";\n")
                        append(inner)
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
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar, targetReturn)
                    }
                    BuiltinInlineKind.WITH_LOCK -> {
                        includes.addSystem("mutex")
                        val recvCode = inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "mutex_"
                        append(inner).append("std::lock_guard kxs_lock(").append(recvCode).append(");\n")
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar, targetReturn)
                    }
                    BuiltinInlineKind.SYNCHRONIZED -> {
                        includes.addSystem("mutex")
                        includes.needsSynchronizedObjectDef = true
                        val lockExpr =
                            inlineExp.argExprFrameIndices.firstOrNull()?.let { emitExpression(stream.frames[it]) }
                                ?: (receiverSubstitution ?: "(*this)")
                        append(inner).append("std::lock_guard kxs_lock((").append(lockExpr).append(").kxs_sync_mutex_);\n")
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar, targetReturn)
                    }
                    BuiltinInlineKind.REPEAT -> {
                        val countIdx = inlineExp.argExprFrameIndices.firstOrNull()
                        val countCode = countIdx?.let { emitExpression(stream.frames[it]) } ?: "0"
                        val idxParam = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val idxCpp = CppNamingPolicy.toSnakeCase(idxParam)
                        val boundTemp = freshTemp("kxs_times")
                        append(inner)
                            .append("const auto ")
                            .append(boundTemp)
                            .append(" = ")
                            .append(countCode)
                            .append(";\n")
                        append(inner)
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
                        appendInlineLambdaBodyStatements(this, lambdaBody, "$inner    ", targetVar = null, targetReturn = false)
                        append(inner).append("}\n")
                    }
                    BuiltinInlineKind.FOR_EACH -> {
                        val recvCode =
                            inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) }
                                ?: (receiverSubstitution ?: "(*this)")
                        val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val pCpp = CppNamingPolicy.toSnakeCase(pName)
                        append(inner)
                            .append("for (auto&& ")
                            .append(pCpp)
                            .append(" : ")
                            .append(recvCode)
                            .append(") {\n")
                        bindLocalName(pName, pCpp)
                        appendInlineLambdaBodyStatements(this, lambdaBody, "$inner    ", targetVar = null, targetReturn = false)
                        append(inner).append("}\n")
                    }
                    BuiltinInlineKind.SAME_FILE_INLINE_FUN -> {
                        val targetFn = inlineExp.targetInlineFunFrameIndex?.let { bundle.symbols.functionsByFrame[it] }
                        val targetBody = targetFn?.bodyFrameIndex?.let { stream.frames[it] }
                        if (targetFn != null && targetBody != null && targetFn.frameIndex !in activeInlineFunFrames) {
                            emitSameFileInlineFunctionBody(
                                out = this,
                                inlineExp = inlineExp,
                                targetFn = targetFn,
                                targetBody = targetBody,
                                callerLambdaBody = lambdaBody,
                                inner = inner,
                                targetVar = targetVar,
                                targetReturn = targetReturn,
                            )
                        } else {
                            appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar, targetReturn)
                        }
                    }
                }
                append(indent).append("}\n")
            }
        }
    }

    private fun emitSameFileInlineFunctionBody(
        out: StringBuilder,
        inlineExp: InlineCallExpansion,
        targetFn: KtFunctionSymbol,
        targetBody: PacketFrame,
        callerLambdaBody: PacketFrame,
        inner: String,
        targetVar: String?,
        targetReturn: Boolean,
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
            val rc = emitExpression(rf)
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
                argExprIdx?.let { emitExpression(stream.frames[it]) }
                    ?: vp.defaultExprFrameIndex?.let { emitExpression(stream.frames[it]) }
                    ?: "{}"
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

        if (targetFn.typeParams.isNotEmpty()) {
            includes.addSystem("type_traits")
            val recvTypeSimple =
                targetFn.receiverTypeFrameIndex?.let {
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                }
            val retTypeSimple =
                targetFn.returnTypeFrameIndex?.let {
                    FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
                }
            for ((tpIdx, tp) in targetFn.typeParams.withIndex()) {
                if (tp.name in activeTypeParams) continue
                val explicitArg = explicitTypeArgs.getOrNull(tpIdx)
                val paramMatch =
                    nonLambdaParams.firstOrNull { vp ->
                        vp.typeFrameIndex != null &&
                            FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[vp.typeFrameIndex]) == tp.name
                    }
                val aliasRhs =
                    when {
                        explicitArg != null -> explicitArg
                        recvTemp != null && recvTypeSimple == tp.name ->
                            "std::remove_pointer_t<std::remove_cvref_t<decltype($recvTemp)>>"
                        paramMatch != null && argTempsByParam[paramMatch.name] != null ->
                            "std::remove_cvref_t<decltype(${argTempsByParam[paramMatch.name]})>"
                        targetVar != null && retTypeSimple == tp.name ->
                            "std::remove_pointer_t<std::remove_cvref_t<decltype($targetVar)>>"
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
            targetFn.typeParams
                .firstOrNull { it.name == recvTypeSimple }
                ?.upperBoundFrameIndex
                ?.let { FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) }
                ?: recvTypeSimple
        val extRecvCls = resolvedRecvSimple?.let { bundle.symbols.classesByName[it] }
        val fnEnclosingCls = targetFn.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] } ?: enclosingClass
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
                activeTypeParams = activeTypeParams + targetFn.typeParams.map { it.name },
                enclosingClass = fnEnclosingCls,
                receiverSubstitution = recvTemp ?: receiverSubstitution,
                extensionReceiverClass = extRecvCls,
                extensionReceiverIsPointer = recvIsPtr,
                extensionReceiverSimpleName = resolvedRecvSimple,
                enclosingFunctionReturnType = enclosingFunctionReturnType,
                inlinedLambdaSubstitutions = inlinedLambdaSubstitutions + (lambdaParamName to site),
                inlineReturnTargetVar = if (hasEarlyUnlabeledReturn) targetVar else null,
                inlineReturnExitLabel = exitLabel,
                activeInlineFunFrames = activeInlineFunFrames + targetFn.frameIndex,
            )
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
    }

    private fun hasEarlyUnlabeledReturnInInlineBody(blockBody: PacketFrame): Boolean {
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

    private fun containsUnlabeledReturn(frame: PacketFrame): Boolean {
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

    private fun expandInlinedCallerLambdaCall(
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
                    append(inner)
                        .append("auto&& ")
                        .append(pCpp)
                        .append(" = ")
                        .append(argVal)
                        .append(";\n")
                    site.callerEmitter.bindLocalName(pName, pCpp)
                }
                site.callerEmitter.appendInlineLambdaBodyStatements(this, site.lambdaBodyFrame, inner, targetVar, targetReturn)
                append(indent).append("}\n")
            }
        }
    }

    private fun appendInlineLambdaBodyStatements(
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

    private fun emitMemScopedStatement(
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

    private fun emitUsePinnedStatement(
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

    private fun emitAtomicLoopOrUpdateStatement(
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

    fun emitExpression(exprFrame: PacketFrame): String {
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

    private fun emitExprIgnoringHoist(exprFrame: PacketFrame): String {
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
                "[&]() {\n${emitWhenStatement(exprFrame, "    ", inValuePosition = true, targetReturn = true, targetVar = null)}}()"
            }
            KtNodeTypes.TRY_ID -> {
                "[&]() {\n${emitTryStatement(exprFrame, "    ", inValuePosition = true, targetReturn = true, targetVar = null)}}()"
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
                "[&]() ${emitBlock(exprFrame, "", isValueBlock = true, resultTargetVar = null)}()"
            }
            KtNodeTypes.OBJECT_LITERAL_ID -> emitObjectLiteralExpr(exprFrame)
            else ->
                throw IllegalArgumentException(
                    "${stream.sourcePath}:${exprFrame.span.startLine}:${exprFrame.span.startColumn}: " +
                        "unsupported Kotlin expression node ${KtNodeTypes.debugName(exprFrame.nodeTypeId)}",
                )
        }
    }

    private fun emitObjectLiteralExpr(objFrame: PacketFrame): String {
        val objDecl = FrameAstHelpers.firstChildFrame(stream, objFrame, KtNodeTypes.OBJECT_DECLARATION_ID) ?: objFrame
        val superList = FrameAstHelpers.firstChildFrame(stream, objDecl, KtNodeTypes.SUPER_TYPE_LIST_ID)
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
                                FrameAstHelpers.childFramesOfType(stream, al, KtNodeTypes.VALUE_ARGUMENT_ID).mapNotNull { va ->
                                    FrameAstHelpers
                                        .childFrames(stream, va)
                                        .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                                        ?.let { emitExpression(it) }
                                }
                            }.orEmpty()
                    superCtorInits.add("$baseType(${args.joinToString(", ")})")
                }
            }
        }

        val classBody = FrameAstHelpers.firstChildFrame(stream, objDecl, KtNodeTypes.CLASS_BODY_ID)
        val capturedLocals = LinkedHashMap<String, String>()
        if (classBody != null && classBody.firstPacketIndex <= classBody.lastPacketIndex) {
            for (pktIdx in classBody.firstPacketIndex..classBody.lastPacketIndex) {
                val pkt = stream.packets[pktIdx]
                if (pkt is SyntaxPacket.IdentPacket) {
                    val bound = resolveBoundName(pkt.unescapedName)
                    if (bound != null && bound.all { it.isLetterOrDigit() || it == '_' } && pkt.unescapedName !in capturedLocals) {
                        capturedLocals[pkt.unescapedName] = bound
                    }
                }
            }
        }

        val anonName = freshTemp("KxsAnonObject")
        val superClause = if (superBases.isNotEmpty()) " : ${superBases.joinToString(", ") { "public $it" }}" else ""
        includes.addSystem("memory")
        includes.addSystem("type_traits")
        includes.addSystem("utility")

        return buildString {
            append("([&]() {\n")
            append("    struct ").append(anonName).append(superClause).append(" {\n")
            for ((_, capCpp) in capturedLocals) {
                append("        std::decay_t<decltype($capCpp)> ${capCpp}_cap;\n")
            }
            if (capturedLocals.isNotEmpty() || superCtorInits.isNotEmpty()) {
                val ctorParams =
                    capturedLocals.values.map { capCpp ->
                        "std::decay_t<decltype($capCpp)> ${capCpp}_in"
                    }
                val inits = ArrayList<String>(superCtorInits.size + capturedLocals.size)
                inits.addAll(superCtorInits)
                for (capCpp in capturedLocals.values) {
                    inits.add("${capCpp}_cap(std::move(${capCpp}_in))")
                }
                val explicitKw = if (ctorParams.size == 1) "explicit " else ""
                append("        ")
                    .append(explicitKw)
                    .append(anonName)
                    .append('(')
                    .append(ctorParams.joinToString(", "))
                    .append(')')
                if (inits.isNotEmpty()) {
                    append(" : ").append(inits.joinToString(", "))
                }
                append(" {}\n")
            }
            if (classBody != null) {
                for (member in FrameAstHelpers.childFrames(stream, classBody)) {
                    if (member.nodeTypeId == KtNodeTypes.FUN_ID) {
                        val mIdent = FrameAstHelpers.directIdentPacket(stream, member)?.unescapedName ?: "member_fn"
                        val mCpp = CppNamingPolicy.toSnakeCase(mIdent)
                        val (_, retFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, member)
                        val retT =
                            retFrame?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.RETURN) } ?: "void"
                        val isVoidM = retT == "void" || retT == "[[noreturn]] void"
                        val pList = FrameAstHelpers.firstChildFrame(stream, member, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
                        val vps = FrameAstHelpers.extractValueParams(stream, pList)
                        val mEmitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                activeTypeParams = activeTypeParams,
                                isVoidReturnContext = isVoidM,
                            )
                        for ((ktCap, cppCap) in capturedLocals) {
                            mEmitter.bindLocalName(ktCap, "this->${cppCap}_cap")
                        }
                        val renderedParams =
                            vps.joinToString(", ") { vp ->
                                mEmitter.bindValueParam(vp)
                                val pT =
                                    vp.typeFrameIndex?.let {
                                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER)
                                    } ?: "auto"
                                "$pT ${vp.cppName}"
                            }
                        val isZeroArgAnyMethod = (mIdent == "toString" || mIdent == "hashCode") && vps.isEmpty()
                        val isOv =
                            PacketFlags.hasFlag(member.flags, PacketFlags.MOD_OVERRIDE) &&
                                superBases.isNotEmpty() &&
                                (
                                    !isZeroArgAnyMethod ||
                                        superBases.any { b -> KNOWN_TO_STRING_SUPER_FRAGMENTS.any { frag -> b.contains(frag) } }
                                )
                        val blockB = FrameAstHelpers.firstChildFrame(stream, member, KtNodeTypes.BLOCK_ID)
                        val exprB = if (blockB == null) FrameAstHelpers.extractInitializerAfterEquals(stream, member) else null
                        val bodyF = blockB ?: exprB
                        val bodyCode =
                            if (bodyF != null) {
                                mEmitter.emitBlockOrExprBody(bodyF, exprB != null, isVoidM, "        ")
                            } else {
                                "{}"
                            }
                        append("        ")
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
            append("    };\n")
            val makeArgs = capturedLocals.values.joinToString(", ")
            append("    return std::make_shared<$anonName>($makeArgs);\n")
            append("}())")
        }
    }

    private fun emitConstantLiteral(litFrame: PacketFrame): String {
        val pkt =
            (litFrame.firstPacketIndex..litFrame.lastPacketIndex)
                .asSequence()
                .mapNotNull { stream.packets.getOrNull(it) as? SyntaxPacket.LiteralPacket }
                .firstOrNull()
                ?: throw IllegalArgumentException(
                    "${stream.sourcePath}:${litFrame.span.startLine}:${litFrame.span.startColumn}: missing LiteralPacket",
                )
        return when (pkt.literalKind) {
            LiteralPacketKind.NULL -> "nullptr"
            LiteralPacketKind.BOOLEAN -> pkt.rawText
            LiteralPacketKind.CHAR -> "u${pkt.rawText}"
            LiteralPacketKind.FLOAT -> {
                val cleaned = pkt.rawText.replace("_", "")
                if (cleaned.endsWith('f') || cleaned.endsWith('F')) {
                    cleaned.dropLast(1) + "f"
                } else {
                    cleaned
                }
            }
            LiteralPacketKind.INT -> {
                var body = pkt.rawText.replace("_", "")
                var suffix = ""
                if (body.endsWith("uL", ignoreCase = true) || body.endsWith("ul", ignoreCase = true)) {
                    body = body.dropLast(2)
                    suffix = "ULL"
                } else if (body.endsWith('L') || body.endsWith('l')) {
                    body = body.dropLast(1)
                    suffix = "LL"
                } else if (body.endsWith('U') || body.endsWith('u')) {
                    body = body.dropLast(1)
                    suffix = "U"
                }
                body + suffix
            }
        }
    }

    private fun emitStringTemplate(strFrame: PacketFrame): String {
        includes.addSystem("string")
        val isRaw = PacketFlags.hasFlag(strFrame.flags, PacketFlags.RAW_MULTILINE_STRING)
        val isInterpolated = PacketFlags.hasFlag(strFrame.flags, PacketFlags.INTERPOLATED_STRING)
        val hasLoneSurrogate = PacketFlags.hasFlag(strFrame.flags, PacketFlags.HAS_LONE_SURROGATE)

        if (!isInterpolated && !hasLoneSurrogate) {
            val constantText = FrameAstHelpers.extractConstantStringText(stream, strFrame)
            return "std::string(${escapeCppStringLiteral(constantText)})"
        }

        // Interpolated or surrogate-containing string template: assemble segments deterministically in order!
        val parts = ArrayList<String>()
        val litBuffer = StringBuilder()

        fun flushLiteral() {
            if (litBuffer.isNotEmpty()) {
                parts.add("std::string(${escapeCppStringLiteral(litBuffer.toString())})")
                litBuffer.setLength(0)
            }
        }

        for (i in 0 until strFrame.childCount) {
            val ref = strFrame.childRefAt(i)
            if (ref < 0) {
                val pkt = stream.packets[-(ref + 1)]
                if (pkt is SyntaxPacket.StringSegmentPacket) {
                    when (pkt.segmentKind) {
                        StringSegmentKind.LITERAL_PART -> litBuffer.append(pkt.rawText)
                        StringSegmentKind.ESCAPE_SEQ -> litBuffer.append(decodeKotlinEscapeToCharOrText(pkt.rawText))
                        else -> Unit
                    }
                }
            } else {
                val entry = stream.frames[ref]
                when (entry.nodeTypeId) {
                    KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY_ID -> {
                        val rawPart = extractStringSegmentRaw(entry, StringSegmentKind.LITERAL_PART)
                        if (PacketFlags.hasFlag(entry.flags, PacketFlags.HAS_LONE_SURROGATE)) {
                            flushLiteral()
                            parts.add(encodeSurrogateSafeCppString(rawPart))
                        } else {
                            litBuffer.append(rawPart)
                        }
                    }
                    KtNodeTypes.ESCAPE_STRING_TEMPLATE_ENTRY_ID -> {
                        val rawEsc = extractStringSegmentRaw(entry, StringSegmentKind.ESCAPE_SEQ)
                        if (PacketFlags.hasFlag(entry.flags, PacketFlags.HAS_LONE_SURROGATE) &&
                            rawEsc.startsWith("\\u") && rawEsc.length == 6
                        ) {
                            flushLiteral()
                            val codeUnit = rawEsc.substring(2).toInt(16)
                            parts.add(encodeSingleUtf16UnitForCpp(codeUnit))
                        } else {
                            litBuffer.append(decodeKotlinEscapeToCharOrText(rawEsc))
                        }
                    }
                    KtNodeTypes.SHORT_STRING_TEMPLATE_ENTRY_ID,
                    KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY_ID,
                    -> {
                        flushLiteral()
                        val expr = FrameAstHelpers.childFrames(stream, entry).firstOrNull()
                        if (expr != null) {
                            parts.add(wrapForStringConcatenation(expr))
                        }
                    }
                    else -> Unit
                }
            }
        }
        flushLiteral()
        if (isRaw && parts.size == 1) {
            return parts[0]
        }
        return if (parts.isEmpty()) "std::string(\"\")" else parts.joinToString(" + ")
    }

    private fun wrapForStringConcatenation(exprFrame: PacketFrame): String {
        val code = emitExpression(exprFrame)
        if (exprFrame.nodeTypeId == KtNodeTypes.STRING_TEMPLATE_ID) return code
        if (exprFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val name = FrameAstHelpers.directIdentPacket(stream, exprFrame)?.unescapedName
            if (name != null && isKnownStringSymbol(name)) return code
        }
        // Use C++23 constexpr-dispatched formatter helper via inline lambda so both `std::string` and numeric scalars work!
        includes.addSystem("string")
        includes.addSystem("type_traits")
        return "([&](const auto& kxs_v) { " +
            "if constexpr (std::is_convertible_v<decltype(kxs_v), std::string>) return std::string(kxs_v); " +
            "else if constexpr (requires { std::to_string(kxs_v); }) return std::to_string(kxs_v); " +
            "else if constexpr (requires { kxs_v.to_string(); }) return kxs_v.to_string(); " +
            "else if constexpr (requires { kxs_v->to_string(); }) return kxs_v ? kxs_v->to_string() : std::string(\"null\"); " +
            "else return std::string(\"<object>\"); }($code))"
    }

    private fun extractStringSegmentRaw(
        entryFrame: PacketFrame,
        expectedKind: StringSegmentKind,
    ): String {
        if (entryFrame.firstPacketIndex > entryFrame.lastPacketIndex || stream.packets.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for (idx in entryFrame.firstPacketIndex..entryFrame.lastPacketIndex) {
            val pkt = stream.packets[idx]
            if (pkt is SyntaxPacket.StringSegmentPacket && pkt.segmentKind == expectedKind) {
                sb.append(pkt.rawText)
            }
        }
        return sb.toString()
    }

    private fun isKnownStringSymbol(kotlinName: String): Boolean {
        if (enclosingClass != null) {
            val pMatch = enclosingClass.primaryConstructorParams.firstOrNull { it.name == kotlinName }
            if (pMatch?.typeFrameIndex != null) {
                val tf = stream.frames[pMatch.typeFrameIndex]
                if (FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf) == "String" &&
                    !FrameAstHelpers.isNullableTypeFrame(stream, tf)
                ) {
                    return true
                }
            }
        }
        val prop = bundle.symbols.propertiesByName[kotlinName]
        if (prop?.returnTypeFrameIndex != null) {
            val tf = stream.frames[prop.returnTypeFrameIndex]
            if (FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf) == "String" &&
                !FrameAstHelpers.isNullableTypeFrame(stream, tf)
            ) {
                return true
            }
        }
        return false
    }

    private fun encodeSurrogateSafeCppString(raw: String): String {
        // Construct via explicit hex code units without embedding unpaired UTF-16 surrogate literals in Kotlin source!
        val units = raw.map { "0x${it.code.toString(16).uppercase()}" }
        return "std::string{${units.joinToString(", ") { "static_cast<char>($it & 0xFF)" }}}"
    }

    private fun encodeSingleUtf16UnitForCpp(codeUnit: Int): String =
        "std::string{static_cast<char>(0x${(codeUnit shr 8).toString(16).uppercase()}), " +
            "static_cast<char>(0x${(codeUnit and 0xFF).toString(16).uppercase()})}"

    private fun decodeKotlinEscapeToCharOrText(rawEsc: String): String =
        when (rawEsc) {
            "\\n" -> "\n"
            "\\r" -> "\r"
            "\\t" -> "\t"
            "\\b" -> "\b"
            "\\\\" -> "\\"
            "\\\"" -> "\""
            "\\'" -> "'"
            "\\$" -> "$"
            else -> {
                if (rawEsc.startsWith("\\u") && rawEsc.length == 6) {
                    val code = rawEsc.substring(2).toIntOrNull(16)
                    if (code != null && code !in 0xD800..0xDFFF) {
                        code.toChar().toString()
                    } else {
                        rawEsc
                    }
                } else {
                    rawEsc
                }
            }
        }

    private fun emitThisExpr(thisFrame: PacketFrame): String {
        val labelFrame = FrameAstHelpers.firstChildFrame(stream, thisFrame, KtNodeTypes.LABEL_QUALIFIER_ID)
        if (labelFrame != null) {
            val labelName = FrameAstHelpers.directIdentPacket(stream, labelFrame)?.unescapedName
            if (labelName != null && enclosingClass != null) {
                if (enclosingClass.kotlinName == labelName) {
                    return "(*this)"
                }
                var cur: KtClassSymbol? = enclosingClass
                var hops = 0
                while (cur != null && cur.isInner) {
                    val parent = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] } ?: break
                    hops += 1
                    if (parent.kotlinName == labelName) {
                        val ptrChain = if (hops == 1) "outer_" else "${"outer_->".repeat(hops - 1)}outer_"
                        return "(*$ptrChain)"
                    }
                    cur = parent
                }
            }
            return "(*this)"
        }
        receiverSubstitution?.let { return it }
        if (!bundle.isDocExample &&
            enclosingClass != null &&
            isSharedThisClass(enclosingClass.kotlinName)
        ) {
            includes.needsSharedFromThisDef = true
            includes.addSystem("memory")
            includes.addSystem("type_traits")
            return "kxs_shared_from_this(this)"
        }
        return "(*this)"
    }

    private fun resolveOuterChainPrefix(targetOwnerClassFrameIndex: Int?): String? {
        if (targetOwnerClassFrameIndex == null || enclosingClass == null) return null
        if (targetOwnerClassFrameIndex == enclosingClass.frameIndex) return ""
        var cur: KtClassSymbol? = enclosingClass
        var hops = 0
        while (cur != null && cur.isInner) {
            val parentIdx = cur.enclosingClassFrameIndex ?: break
            hops += 1
            if (parentIdx == targetOwnerClassFrameIndex) {
                return "outer_->".repeat(hops)
            }
            cur = bundle.symbols.classesByFrame[parentIdx]
        }
        return null
    }

    private fun resolveOuterPointerExpr(targetOwnerClassFrameIndex: Int?): String? {
        if (targetOwnerClassFrameIndex == null || enclosingClass == null) return null
        if (targetOwnerClassFrameIndex == enclosingClass.frameIndex) return "this"
        var cur: KtClassSymbol? = enclosingClass
        var hops = 0
        while (cur != null && cur.isInner) {
            val parentIdx = cur.enclosingClassFrameIndex ?: break
            hops += 1
            if (parentIdx == targetOwnerClassFrameIndex) {
                return if (hops == 1) "outer_" else "${"outer_->".repeat(hops - 1)}outer_"
            }
            cur = bundle.symbols.classesByFrame[parentIdx]
        }
        return null
    }

    private fun lookupAccessibleClassByName(kotlinName: String): KtClassSymbol? {
        var cur: KtClassSymbol? = enclosingClass
        while (cur != null) {
            for (nestedIdx in cur.memberClassFrameIndices) {
                val sub = bundle.symbols.classesByFrame[nestedIdx]
                if (sub != null && sub.kotlinName == kotlinName) {
                    return sub
                }
            }
            cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        return bundle.symbols.classesByName[kotlinName]
    }

    private fun emitReferenceExpr(refFrame: PacketFrame): String {
        val pkt =
            FrameAstHelpers.directIdentPacket(stream, refFrame)
                ?: throw IllegalArgumentException(
                    "${stream.sourcePath}:${refFrame.span.startLine}:${refFrame.span.startColumn}: missing IdentPacket in REFERENCE_EXPRESSION",
                )
        val ktName = pkt.unescapedName

        resolveBoundName(ktName)?.let { return it }

        // Check if `ktName` is an enum entry, private field, or property on `enclosingClass` or an `inner` outer class
        if (enclosingClass != null) {
            val enumEntry = enclosingClass.enumEntries.firstOrNull { it.kotlinName == ktName }
            if (enumEntry != null) {
                return "${enclosingClass.cppName}::${enumEntry.cppName}"
            }
            var cur: KtClassSymbol? = enclosingClass
            var prefix = ""
            while (cur != null) {
                val ctorProp = cur.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == ktName }
                if (ctorProp != null) {
                    val vpFrame = stream.frames[ctorProp.frameIndex]
                    val isPriv = PacketFlags.hasFlag(vpFrame.flags, PacketFlags.MOD_PRIVATE)
                    val isBackedOverride = isBackedOverrideCtorParamExpr(cur, ctorProp)
                    val fieldName =
                        if (isPriv || (prefix.isEmpty() && isBackedOverride)) {
                            CppNamingPolicy.toPrivateFieldName(ktName)
                        } else if (isBackedOverride) {
                            "${resolveClassPropGetterCppName(cur, ctorProp.name, ctorProp.cppName)}()"
                        } else {
                            ctorProp.cppName
                        }
                    return "$prefix$fieldName"
                }
                for (mPropIdx in cur.memberPropertyFrameIndices) {
                    val mProp = bundle.symbols.propertiesByFrame[mPropIdx] ?: continue
                    if (mProp.kotlinName == ktName) {
                        val suffix = if (isGetterStyleClassProperty(cur, mProp)) "()" else ""
                        val getterName =
                            if (suffix.isNotEmpty()) {
                                resolveClassPropGetterCppName(cur, mProp.kotlinName, mProp.cppName)
                            } else {
                                mProp.cppName
                            }
                        return "$prefix$getterName$suffix"
                    }
                }
                if (!cur.isInner) break
                cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
                prefix += "outer_->"
            }
        }

        val recvSub = receiverSubstitution
        val extRecvCls = extensionReceiverClass
        if (recvSub != null && extRecvCls != null) {
            for (mPropIdx in extRecvCls.memberPropertyFrameIndices) {
                val mProp = bundle.symbols.propertiesByFrame[mPropIdx] ?: continue
                if (mProp.kotlinName == ktName) {
                    val op = if (extensionReceiverIsPointer) "->" else "."
                    val suffix = if (isGetterStyleClassProperty(extRecvCls, mProp)) "()" else ""
                    val getterName =
                        if (suffix.isNotEmpty()) {
                            resolveClassPropGetterCppName(extRecvCls, mProp.kotlinName, mProp.cppName)
                        } else {
                            mProp.cppName
                        }
                    return "$recvSub$op$getterName$suffix"
                }
            }
        }

        val extSimple = extensionReceiverSimpleName
        if (recvSub != null && extRecvCls == null && extSimple != null) {
            if (ktName in KNOWN_EXTERNAL_RECEIVER_PROPERTIES[extSimple].orEmpty()) {
                val op = if (extensionReceiverIsPointer) "->" else "."
                when {
                    extSimple == "CoroutineScope" && ktName == "coroutineContext" ->
                        return "$recvSub${op}coroutine_context()"
                    (extSimple == "Continuation" || extSimple == "CancellableContinuation") && ktName == "context" ->
                        return "$recvSub${op}get_context()"
                    extSimple == "Duration" && ktName == "inWholeMilliseconds" ->
                        return "$recvSub${op}in_whole_milliseconds()"
                    extSimple == "Duration" && ktName in DURATION_WHOLE_UNITS -> {
                        includes.addProject("kotlin/time/Duration.hpp")
                        val unitEnum = ktName.removePrefix("inWhole").uppercase()
                        return "$recvSub${op}to_long(kotlin::time::DurationUnit::$unitEnum)"
                    }
                    else -> return "$recvSub$op${CppNamingPolicy.toSnakeCase(ktName)}()"
                }
            }
        }

        if (!bundle.isDocExample && ktName == "Unit") {
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            return "kotlinx::coroutines::Unit{}"
        }
        if (ktName == "COROUTINE_SUSPENDED") {
            includes.addProject("kotlinx/coroutines/intrinsics/Intrinsics.hpp")
            return "intrinsics::COROUTINE_SUSPENDED"
        }
        if (ktName == "EmptyCoroutineContext") {
            includes.addProject("kotlinx/coroutines/CoroutineContext.hpp")
            return "EmptyCoroutineContext::instance()"
        }
        if (ktName == "coroutineContext" && !bundle.isDocExample) {
            includes.addProject("kotlinx/coroutines/Continuation.hpp")
            return "kotlin::coroutines::get_coroutine_context()"
        }
        if (ktName == "ContinuationInterceptor" && !bundle.isDocExample) {
            includes.addProject("kotlinx/coroutines/ContinuationInterceptor.hpp")
            return "&ContinuationInterceptor::key_instance"
        }
        if (ktName == "Key" && !bundle.isDocExample && enclosingClass != null) {
            val compSym = enclosingClass.companionFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            if (compSym != null && compSym.kotlinName == "Key" && compSym.superTypeFrameIndices.isNotEmpty()) {
                return "${enclosingClass.cppName}::Key::instance()"
            }
        }

        val effectiveKtName = bundle.symbols.importAliases[ktName] ?: ktName
        val sameCls = lookupAccessibleClassByName(ktName) ?: lookupAccessibleClassByName(effectiveKtName)
        if (sameCls != null) {
            if (sameCls.kind == KtClassKind.OBJECT_SINGLETON) {
                return "${sameCls.cppName}::instance()"
            }
            val compSym = sameCls.companionFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            if (compSym != null && compSym.superTypeFrameIndices.isNotEmpty()) {
                return "${sameCls.cppName}::${compSym.cppName}::instance()"
            }
        }
        if (!bundle.isDocExample && sameCls == null && effectiveKtName in KNOWN_CONTEXT_KEY_CARRIERS) {
            val cppTypeBase = CppNamingPolicy.toTypeName(effectiveKtName)
            val qual =
                CppTypeTranslator.includeAndQualifyKnownType(
                    simpleName = effectiveKtName,
                    cppBaseName = cppTypeBase,
                    currentNs = bundle.symbols.namespaceSegments,
                    includes = includes,
                ) ?: run {
                    if (effectiveKtName == "CoroutineId") {
                        includes.addProject("kotlinx/coroutines/CoroutineContext.common.hpp")
                        if (bundle.symbols.namespaceSegments.take(2) == listOf("kotlinx", "coroutines") &&
                            bundle.symbols.namespaceSegments.size == 2
                        ) {
                            cppTypeBase
                        } else {
                            "kotlinx::coroutines::$cppTypeBase"
                        }
                    } else {
                        cppTypeBase
                    }
                }
            return "$qual::Key::instance()"
        }

        val rawCppName =
            pkt.targetCppName
                ?: if (isVerbatimCIdentifier(effectiveKtName)) {
                    effectiveKtName
                } else {
                    CppNamingPolicy.toSnakeCase(effectiveKtName)
                }
        if (!bundle.isDocExample) {
            if (effectiveKtName in KNOWN_OBJECT_SINGLETONS) {
                val qualObj =
                    CppTypeTranslator.includeAndQualifyKnownType(
                        simpleName = effectiveKtName,
                        cppBaseName = CppNamingPolicy.toTypeName(effectiveKtName),
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    )
                if (qualObj != null) return "$qualObj::instance()"
            }
            val qualifiedVal =
                CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                    kotlinName = effectiveKtName,
                    cppBaseName = rawCppName,
                    currentNs = bundle.symbols.namespaceSegments,
                    includes = includes,
                )
            if (qualifiedVal != null) return qualifiedVal
        }

        return rawCppName
    }

    private fun emitDotQualifiedExpr(dotFrame: PacketFrame): String {
        val parts = FrameAstHelpers.childFrames(stream, dotFrame)
        require(parts.size == 2) {
            "${stream.sourcePath}:${dotFrame.span.startLine}:${dotFrame.span.startColumn}: malformed DOT_QUALIFIED_EXPRESSION"
        }
        val recvFrame = parts[0]
        val selFrame = parts[1]
        val recvSimpleIdent =
            if (recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                FrameAstHelpers.directIdentPacket(stream, recvFrame)?.unescapedName
            } else {
                null
            }
        val effectiveRecvKt = recvSimpleIdent?.let { bundle.symbols.importAliases[it] ?: it }

        // 1. Check `Result.success(v)` / `Result.failure(e)`
        if (effectiveRecvKt == "Result" && selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val fnName = extractCalleeSimpleName(selFrame)
            val args = extractCallArgExpressions(selFrame)
            includes.addProject("kotlinx/coroutines/Result.hpp")
            if (bundle.isDocExample) {
                if (fnName == "success") return "Result::success(${args.joinToString(", ")})"
                if (fnName == "failure") return "Result::failure(${args.joinToString(", ")})"
            } else {
                if (fnName == "success") {
                    val vArg = args.firstOrNull()
                    if (vArg == null || vArg == "Unit" || vArg == "kotlinx::coroutines::Unit{}") {
                        includes.addProject("kotlinx/coroutines/Unit.hpp")
                        return "kotlinx::coroutines::Result<kotlinx::coroutines::Unit>::success(kotlinx::coroutines::Unit{})"
                    }
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    return "([&](auto&& kxs_rv) { return kotlinx::coroutines::Result<std::decay_t<decltype(kxs_rv)>>::success(std::forward<decltype(kxs_rv)>(kxs_rv)); }($vArg))"
                }
                if (fnName == "failure") {
                    includes.addSystem("exception")
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    includes.needsResultHelpersDef = true
                    val eArg = args.firstOrNull() ?: "std::current_exception()"
                    return "KxsResultFailure{kxs_to_exception_ptr($eArg)}"
                }
            }
        }

        // 2. Check ThreadLocal `.get()` / `.set(x)`
        if (recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val recvKt = FrameAstHelpers.directIdentPacket(stream, recvFrame)?.unescapedName
            val tlSlot = recvKt?.let { bundle.atomicPlan.threadLocalsByKotlinName[it] }
            if (tlSlot != null && selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                val fnName = extractCalleeSimpleName(selFrame)
                val args = extractCallArgExpressions(selFrame)
                if (fnName == "get") return "${tlSlot.cppName}.get()"
                if (fnName == "set") return "${tlSlot.cppName}.set(${args.joinToString(", ")})"
            }
        }

        // 3. Check `TypeName.MEMBER` -> `TypeName::MEMBER` (enum entry, companion constant/factory)
        val sameRecvCls =
            recvSimpleIdent?.let { lookupAccessibleClassByName(it) }
                ?: effectiveRecvKt?.let { lookupAccessibleClassByName(it) }
        val sameRecvSingleton =
            sameRecvCls != null &&
                sameRecvCls.kind == KtClassKind.OBJECT_SINGLETON &&
                sameRecvCls.superTypeFrameIndices.isNotEmpty()
        val knownRecvSingleton =
            !bundle.isDocExample &&
                sameRecvCls == null &&
                effectiveRecvKt in KNOWN_OBJECT_SINGLETONS
        val isObjectInstanceCall =
            if ((sameRecvSingleton || knownRecvSingleton) && selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                val fnKt = extractCalleeSimpleName(selFrame)
                val memberFns = sameRecvCls?.memberFunctionFrameIndices.orEmpty()
                val directStaticFn =
                    memberFns
                        .mapNotNull { bundle.symbols.functionsByFrame[it] }
                        .firstOrNull { it.kotlinName == fnKt }
                directStaticFn == null || PacketFlags.hasFlag(directStaticFn.flags, PacketFlags.MOD_OVERRIDE)
            } else {
                false
            }
        val isStaticTypeReceiver =
            !isObjectInstanceCall &&
                recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                effectiveRecvKt !in KNOWN_OBJECT_SINGLETONS &&
                FrameAstHelpers.directIdentPacket(stream, recvFrame)?.let { pkt ->
                    pkt.role == SymbolRole.TYPE ||
                        (pkt.unescapedName.firstOrNull()?.isUpperCase() == true && resolveBoundName(pkt.unescapedName) == null)
                } == true

        val isSuperReceiver = recvFrame.nodeTypeId == KtNodeTypes.SUPER_EXPRESSION_ID
        val isUnlabeledThisRecv =
            recvFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID &&
                FrameAstHelpers.firstChildFrame(stream, recvFrame, KtNodeTypes.LABEL_QUALIFIER_ID) == null &&
                receiverSubstitution == null
        val superSimpleAndCpp = if (isSuperReceiver) resolvePrimarySuperSimpleAndCpp(recvFrame) else null
        val rawRecvCode =
            if (superSimpleAndCpp != null) {
                superSimpleAndCpp.second
            } else if (isStaticTypeReceiver && effectiveRecvKt != null) {
                if (sameRecvCls != null) {
                    sameRecvCls.cppName
                } else {
                    val genericAlias =
                        if (!bundle.isDocExample) {
                            bundle.symbols.typeAliasesByFrame.values.firstOrNull {
                                it.kotlinName == effectiveRecvKt && it.typeParams.isNotEmpty()
                            }
                        } else {
                            null
                        }
                    if (genericAlias != null) {
                        val tArgs =
                            genericAlias.typeParams.joinToString(", ") { tp ->
                                if (tp.name in activeTypeParams) {
                                    tp.name
                                } else {
                                    includes.addSystem("any")
                                    "std::any"
                                }
                            }
                        "${genericAlias.cppName}<$tArgs>"
                    } else {
                        val baseTypeCpp = CppNamingPolicy.toTypeName(effectiveRecvKt)
                        if (!bundle.isDocExample) {
                            CppTypeTranslator.includeAndQualifyKnownType(
                                simpleName = effectiveRecvKt,
                                cppBaseName = baseTypeCpp,
                                currentNs = bundle.symbols.namespaceSegments,
                                includes = includes,
                            ) ?: baseTypeCpp
                        } else {
                            baseTypeCpp
                        }
                    }
                }
            } else if (isUnlabeledThisRecv) {
                "this"
            } else {
                emitExpression(recvFrame)
            }
        val unwrappedOuterPtr =
            if (rawRecvCode.startsWith("(*") && rawRecvCode.endsWith(")") && rawRecvCode.contains("outer_")) {
                rawRecvCode.substring(2, rawRecvCode.length - 1)
            } else {
                null
            }
        val recvCode = unwrappedOuterPtr ?: rawRecvCode
        val isPtrReceiver = unwrappedOuterPtr != null || isPointerOrSharedPtrReceiver(recvFrame, recvCode)

        if (selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            if (isStaticTypeReceiver || isSuperReceiver) {
                val fnName = extractCalleeSimpleName(selFrame)
                val args = extractCallArgExpressions(selFrame)
                return "$recvCode::${CppNamingPolicy.toSnakeCase(fnName)}(${args.joinToString(", ")})"
            }
            return emitCallExpr(receiverCode = recvCode, receiverIsPointer = isPtrReceiver, callFrame = selFrame)
        }

        val memberKt =
            FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName
                ?: FrameAstHelpers.operationSymbolName(stream, selFrame)
        if (isStaticTypeReceiver || isSuperReceiver) {
            if (isSuperReceiver) {
                val superCls = superSimpleAndCpp?.first?.let { lookupAccessibleClassByName(it) }
                val superProp =
                    superCls
                        ?.memberPropertyFrameIndices
                        ?.mapNotNull { bundle.symbols.propertiesByFrame[it] }
                        ?.firstOrNull { it.kotlinName == memberKt }
                if (superCls != null && superProp != null && isGetterStyleClassProperty(superCls, superProp)) {
                    val getterName = resolveClassPropGetterCppName(superCls, superProp.kotlinName, superProp.cppName)
                    return "$recvCode::$getterName()"
                }
            }
            if (!bundle.isDocExample && !isSuperReceiver && sameRecvCls == null) {
                when ("$effectiveRecvKt.$memberKt") {
                    "Int.MAX_VALUE" -> {
                        includes.addSystem("cstdint")
                        return "INT32_MAX"
                    }
                    "Int.MIN_VALUE" -> {
                        includes.addSystem("cstdint")
                        return "INT32_MIN"
                    }
                    "Long.MAX_VALUE" -> {
                        includes.addSystem("cstdint")
                        return "INT64_MAX"
                    }
                    "Long.MIN_VALUE" -> {
                        includes.addSystem("cstdint")
                        return "INT64_MIN"
                    }
                    "Double.MAX_VALUE" -> {
                        includes.addSystem("limits")
                        return "std::numeric_limits<double>::max()"
                    }
                    "Double.MIN_VALUE" -> {
                        includes.addSystem("limits")
                        return "std::numeric_limits<double>::denorm_min()"
                    }
                    "Double.POSITIVE_INFINITY" -> {
                        includes.addSystem("limits")
                        return "std::numeric_limits<double>::infinity()"
                    }
                    "Double.NEGATIVE_INFINITY" -> {
                        includes.addSystem("limits")
                        return "(-std::numeric_limits<double>::infinity())"
                    }
                    "Double.NaN" -> {
                        includes.addSystem("limits")
                        return "std::numeric_limits<double>::quiet_NaN()"
                    }
                }
            }
            val sameCls = if (isSuperReceiver) superSimpleAndCpp?.first?.let { lookupAccessibleClassByName(it) } else sameRecvCls
            val isEnumOrConst =
                sameCls?.enumEntries?.any { it.kotlinName == memberKt } == true ||
                    (memberKt.length > 1 && memberKt.all { it.isUpperCase() || it.isDigit() || it == '_' })
            val memberCpp = if (isEnumOrConst) CppNamingPolicy.toConstName(memberKt) else CppNamingPolicy.toSnakeCase(memberKt)
            return "$recvCode::$memberCpp"
        }

        val hasOwnField =
            enclosingClass?.primaryConstructorParams?.any { it.name == memberKt } == true ||
                enclosingClass?.memberPropertyFrameIndices?.any {
                    bundle.symbols.propertiesByFrame[it]?.kotlinName == memberKt
                } == true

        // Standard Kotlin stdlib property mappings (`.length` / `.size` -> `.size()`, `.value`, `.context`, Duration properties)
        when (memberKt) {
            "size", "length" -> {
                if (!hasOwnField) {
                    val op = if (isPtrReceiver) "->" else "."
                    return "$recvCode${op}size()"
                }
            }
            "value" -> {
                if (!bundle.isDocExample && !(isUnlabeledThisRecv && hasOwnField)) {
                    return emitFlexibleMemberPropertyAccess(recvCode, "get_value", "value", "value")
                }
            }
            "context" -> {
                if (!bundle.isDocExample && !(isUnlabeledThisRecv && hasOwnField && !classInheritsContinuation(enclosingClass))) {
                    return emitFlexibleMemberPropertyAccess(recvCode, "get_context", "context", "context")
                }
            }
            "nanoseconds", "milliseconds", "seconds" -> {
                if (!bundle.isDocExample) {
                    includes.addProject("kotlin/time/Duration.hpp")
                    return "kotlin::time::$memberKt(static_cast<long long>($recvCode))"
                }
            }
            "microseconds", "minutes", "hours", "days" -> {
                if (!bundle.isDocExample) {
                    includes.addProject("kotlin/time/Duration.hpp")
                    val unitEnum = memberKt.uppercase()
                    return "kotlin::time::to_duration(static_cast<long long>($recvCode), kotlin::time::DurationUnit::$unitEnum)"
                }
            }
            "inWholeMilliseconds" -> {
                if (!bundle.isDocExample) {
                    val op = if (isPtrReceiver) "->" else "."
                    return "$recvCode${op}in_whole_milliseconds()"
                }
            }
            "inWholeNanoseconds", "inWholeMicroseconds", "inWholeSeconds" -> {
                if (!bundle.isDocExample) {
                    includes.addProject("kotlin/time/Duration.hpp")
                    val op = if (isPtrReceiver) "->" else "."
                    val unitEnum = memberKt.removePrefix("inWhole").uppercase()
                    return "$recvCode${op}to_long(kotlin::time::DurationUnit::$unitEnum)"
                }
            }
        }

        // Check if `memberKt` is an extension property or rich-enum property declared in the same file!
        val sameFileProp = bundle.symbols.propertiesByName[memberKt]
        if (sameFileProp != null && sameFileProp.receiverTypeFrameIndex != null) {
            return "${sameFileProp.cppName}($recvCode)"
        }

        val op = if (isPtrReceiver) "->" else "."
        if (isUnlabeledThisRecv) {
            return "this->${resolveMemberFieldOrPropName(memberKt)}"
        }
        return "$recvCode$op${resolveMemberFieldOrPropName(memberKt)}"
    }

    private fun isSharedThisClass(kotlinName: String): Boolean =
        CppTypeTranslator.isSharedHierarchyType(kotlinName) ||
            kotlinName == "ConcurrentLinkedListNode" ||
            kotlinName == "Segment" ||
            kotlinName == "LockFreeLinkedListNode"

    private fun emitFlexibleMemberPropertyAccess(
        recvCode: String,
        primaryGetter: String,
        altGetter: String,
        fieldName: String,
    ): String {
        includes.addSystem("type_traits")
        return "([&](auto&& kxs_p_r) -> decltype(auto) { " +
            "using KxsPR = std::remove_cvref_t<decltype(kxs_p_r)>; " +
            "if constexpr (std::is_pointer_v<KxsPR> || requires { kxs_p_r.operator->(); }) { " +
            "if constexpr (requires { kxs_p_r->$primaryGetter(); }) return kxs_p_r->$primaryGetter(); " +
            "else if constexpr (requires { kxs_p_r->$altGetter(); }) return kxs_p_r->$altGetter(); " +
            "else return kxs_p_r->$fieldName; " +
            "} else { " +
            "if constexpr (requires { kxs_p_r.$primaryGetter(); }) return kxs_p_r.$primaryGetter(); " +
            "else if constexpr (requires { kxs_p_r.$altGetter(); }) return kxs_p_r.$altGetter(); " +
            "else return kxs_p_r.$fieldName; " +
            "} }($recvCode))"
    }

    private fun isBackedOverrideCtorParamExpr(
        cls: KtClassSymbol,
        cp: KtValueParamInfo,
    ): Boolean {
        if (cls.kind == KtClassKind.INTERFACE) return false
        if (!cp.isVal && !cp.isVar) return false
        val cpFlags = stream.frames[cp.frameIndex].flags
        return PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_OVERRIDE) &&
            !PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_ABSTRACT)
    }

    private fun isBackedOverrideMemberPropExpr(
        cls: KtClassSymbol,
        prop: KtPropertySymbol,
    ): Boolean {
        if (cls.kind == KtClassKind.INTERFACE) return false
        if (prop.isConst || prop.getterFrameIndex != null) return false
        return PacketFlags.hasFlag(prop.flags, PacketFlags.MOD_OVERRIDE) &&
            !PacketFlags.hasFlag(prop.flags, PacketFlags.MOD_ABSTRACT)
    }

    private fun classInheritsContinuation(
        cls: KtClassSymbol?,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (cls == null || !visited.add(cls.frameIndex)) return false
        if (cls.kotlinName in CONTINUATION_HIERARCHY_BASES) return true
        val allClasses = bundle.symbols.classesByFrame.values
        for (trIdx in cls.superTypeFrameIndices) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: continue
            if (simple in CONTINUATION_HIERARCHY_BASES) return true
            val sameFileSuper = allClasses.firstOrNull { it.kotlinName == simple }
            if (sameFileSuper != null && classInheritsContinuation(sameFileSuper, visited)) return true
        }
        return false
    }

    private fun resolveClassPropGetterCppName(
        ownerCls: KtClassSymbol?,
        propKtName: String,
        defaultCppName: String,
    ): String =
        if (propKtName == "context" && classInheritsContinuation(ownerCls)) {
            "get_context"
        } else {
            defaultCppName
        }

    private fun isGetterStyleClassProperty(
        ownerCls: KtClassSymbol,
        prop: KtPropertySymbol,
    ): Boolean =
        prop.getterFrameIndex != null ||
            ownerCls.kind == KtClassKind.INTERFACE ||
            (PacketFlags.hasFlag(prop.flags, PacketFlags.MOD_ABSTRACT) && prop.initializerFrameIndex == null) ||
            isBackedOverrideMemberPropExpr(ownerCls, prop)

    private fun classDeclaresMethod(
        cls: KtClassSymbol,
        methodKtName: String,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (!visited.add(cls.frameIndex)) return false
        if (cls.memberFunctionFrameIndices.any { bundle.symbols.functionsByFrame[it]?.kotlinName == methodKtName }) {
            return true
        }
        for (superTypeIdx in cls.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superTypeIdx]) ?: continue
            val superCls = bundle.symbols.classesByName[superSimple] ?: continue
            if (classDeclaresMethod(superCls, methodKtName, visited)) return true
        }
        return false
    }

    private fun resolveMemberFieldOrPropName(memberKt: String): String {
        var cur: KtClassSymbol? = enclosingClass
        while (cur != null) {
            val ctorProp = cur.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == memberKt }
            if (ctorProp != null) {
                val vpFrame = stream.frames[ctorProp.frameIndex]
                val isPriv = PacketFlags.hasFlag(vpFrame.flags, PacketFlags.MOD_PRIVATE)
                val isBackedOverride = isBackedOverrideCtorParamExpr(cur, ctorProp)
                return if (isPriv || (cur == enclosingClass && isBackedOverride)) {
                    CppNamingPolicy.toPrivateFieldName(memberKt)
                } else if (isBackedOverride) {
                    "${resolveClassPropGetterCppName(cur, ctorProp.name, ctorProp.cppName)}()"
                } else {
                    ctorProp.cppName
                }
            }
            for (pIdx in cur.memberPropertyFrameIndices) {
                val p = bundle.symbols.propertiesByFrame[pIdx] ?: continue
                if (p.kotlinName == memberKt) {
                    val suffix = if (isGetterStyleClassProperty(cur, p)) "()" else ""
                    val getterName =
                        if (suffix.isNotEmpty()) {
                            resolveClassPropGetterCppName(cur, p.kotlinName, p.cppName)
                        } else {
                            p.cppName
                        }
                    return "$getterName$suffix"
                }
            }
            cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        return CppNamingPolicy.toSnakeCase(memberKt)
    }

    private fun isPointerOrSharedPtrReceiver(
        recvFrame: PacketFrame,
        renderedRecv: String,
    ): Boolean {
        if (renderedRecv == "this") return true
        if (recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val name = FrameAstHelpers.directIdentPacket(stream, recvFrame)?.unescapedName ?: return false
            if (isLocalPolyVar(name)) return true
            var cur: KtClassSymbol? = enclosingClass
            while (cur != null) {
                val cp = cur.primaryConstructorParams.firstOrNull { it.name == name }
                if (cp?.typeFrameIndex != null) {
                    val cppT = bundle.typeTranslator.translateTypeFrame(stream.frames[cp.typeFrameIndex], CppTypePosition.VALUE)
                    if (cppT.endsWith("*") || cppT.startsWith("std::shared_ptr<")) return true
                }
                for (pIdx in cur.memberPropertyFrameIndices) {
                    val mp = bundle.symbols.propertiesByFrame[pIdx] ?: continue
                    if (mp.kotlinName == name && mp.returnTypeFrameIndex != null) {
                        val cppT = bundle.typeTranslator.translateTypeFrame(stream.frames[mp.returnTypeFrameIndex], CppTypePosition.VALUE)
                        if (cppT.endsWith("*") || cppT.startsWith("std::shared_ptr<")) return true
                    }
                }
                if (!cur.isInner) break
                cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            }
            val topProp = bundle.symbols.propertiesByName[name]
            if (topProp?.receiverTypeFrameIndex == null && topProp?.returnTypeFrameIndex != null) {
                val cppT = bundle.typeTranslator.translateTypeFrame(stream.frames[topProp.returnTypeFrameIndex], CppTypePosition.VALUE)
                if (cppT.endsWith("*") || cppT.startsWith("std::shared_ptr<")) return true
            }
        }
        return false
    }

    private fun emitSafeAccessExpr(safeFrame: PacketFrame): String {
        val parts = FrameAstHelpers.childFrames(stream, safeFrame)
        require(parts.size == 2) {
            "${stream.sourcePath}:${safeFrame.span.startLine}:${safeFrame.span.startColumn}: malformed SAFE_ACCESS_EXPRESSION"
        }
        val lhsCode = emitExpression(parts[0])
        val selFrame = parts[1]
        if (bundle.isDocExample) {
            val memberAccess =
                if (selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                    val fn = CppNamingPolicy.toSnakeCase(extractCalleeSimpleName(selFrame))
                    val args = extractCallArgExpressions(selFrame)
                    "$fn(${args.joinToString(", ")})"
                } else {
                    val prop = FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName ?: "value"
                    CppNamingPolicy.toSnakeCase(prop)
                }
            return "($lhsCode ? ($lhsCode->$memberAccess) : nullptr)"
        }

        val temp = bundle.cfPlan.singleEvalTempNamesByFrame[safeFrame.frameIndex] ?: freshTemp("kxs_safe")
        includes.addSystem("optional")
        includes.addSystem("type_traits")

        includes.needsNullUnwrapDef = true
        includes.addSystem("any")
        includes.addSystem("type_traits")
        includes.addSystem("utility")

        val safeInvokeExpr: String =
            if (selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                val calleeKt = extractCalleeSimpleName(selFrame)
                val args = extractCallArgExpressions(selFrame)
                val joinedArgs = args.joinToString(", ")
                val sameFileExt =
                    bundle.symbols.functionsByName[calleeKt]
                        ?.firstOrNull { it.receiverTypeFrameIndex != null }
                when {
                    sameFileExt != null -> {
                        val allArgs = listOf("kxs_sa_r") + args
                        "${sameFileExt.cppName}(${allArgs.joinToString(", ")})"
                    }
                    calleeKt == "invoke" ->
                        "([&](auto&& kxs_sa_ir) -> decltype(auto) { " +
                            "if constexpr (requires { kxs_sa_ir->invoke($joinedArgs); }) return kxs_sa_ir->invoke($joinedArgs); " +
                            "else if constexpr (requires { kxs_sa_ir.invoke($joinedArgs); }) return kxs_sa_ir.invoke($joinedArgs); " +
                            "else if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_ir)>> || requires { kxs_sa_ir.operator->(); }) return (*kxs_sa_ir)($joinedArgs); " +
                            "else return kxs_sa_ir($joinedArgs); }(kxs_sa_r))"
                    calleeKt == "get" && args.size == 1 ->
                        "([&](auto&& kxs_sa_gr) -> decltype(auto) { " +
                            "if constexpr (requires { kxs_sa_gr->get(${args[0]}); }) return kxs_sa_gr->get(${args[0]}); " +
                            "else if constexpr (requires { kxs_sa_gr.get(${args[0]}); }) return kxs_sa_gr.get(${args[0]}); " +
                            "else if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_gr)>> || requires { kxs_sa_gr.operator->(); }) return (*kxs_sa_gr)[${args[0]}]; " +
                            "else return kxs_sa_gr[${args[0]}]; }(kxs_sa_r))"
                    calleeKt == "isEmpty" && args.isEmpty() ->
                        "(std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_r)>> || requires { kxs_sa_r.operator->(); } ? (*kxs_sa_r).empty() : kxs_sa_r.empty())"
                    calleeKt == "isNotEmpty" && args.isEmpty() ->
                        "(!(std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_r)>> || requires { kxs_sa_r.operator->(); } ? (*kxs_sa_r).empty() : kxs_sa_r.empty()))"
                    calleeKt == "add" ->
                        "(requires { kxs_sa_r->push_back($joinedArgs); } ? kxs_sa_r->push_back($joinedArgs) : kxs_sa_r.push_back($joinedArgs))"
                    calleeKt == "toString" && args.isEmpty() ->
                        "([&](auto&& kxs_sa_sr) { if constexpr (requires { kxs_sa_sr->to_string(); }) return kxs_sa_sr->to_string(); else return kxs_sa_sr.to_string(); }(kxs_sa_r))"
                    else -> {
                        val sn = CppNamingPolicy.toSnakeCase(calleeKt)
                        "([&](auto&& kxs_sa_cr) -> decltype(auto) { " +
                            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_cr)>> || requires { kxs_sa_cr.operator->(); }) return kxs_sa_cr->$sn($joinedArgs); " +
                            "else return kxs_sa_cr.$sn($joinedArgs); }(kxs_sa_r))"
                    }
                }
            } else {
                val propKt = FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName ?: "value"
                val sameFileExtProp = bundle.symbols.propertiesByName[propKt]
                when {
                    sameFileExtProp != null && sameFileExtProp.receiverTypeFrameIndex != null ->
                        "${sameFileExtProp.cppName}(kxs_sa_r)"
                    propKt == "size" || propKt == "length" ->
                        "([&](auto&& kxs_sa_pr) { if constexpr (requires { kxs_sa_pr->size(); }) return kxs_sa_pr->size(); else return kxs_sa_pr.size(); }(kxs_sa_r))"
                    else -> {
                        val field = resolveMemberFieldOrPropName(propKt)
                        "([&](auto&& kxs_sa_pr) -> decltype(auto) { " +
                            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_pr)>> || requires { kxs_sa_pr.operator->(); }) return kxs_sa_pr->$field; " +
                            "else return kxs_sa_pr.$field; }(kxs_sa_r))"
                    }
                }
            }

        return "([&](auto&& $temp) { " +
            "using KxsSaRawT = std::remove_cvref_t<decltype($temp)>; " +
            "const bool kxs_has = [&]() -> bool { " +
            "if constexpr (std::is_same_v<KxsSaRawT, std::any>) return $temp.has_value() && $temp.type() != typeid(std::nullptr_t); " +
            "else return static_cast<bool>($temp); }(); " +
            "auto kxs_sa_fn = [&](auto&& kxs_sa_r) -> decltype(auto) { return $safeInvokeExpr; }; " +
            "using KxsSaRes = decltype(kxs_sa_fn(kxs_unwrap_opt($temp))); " +
            "if constexpr (std::is_void_v<KxsSaRes>) { if (kxs_has) { kxs_sa_fn(kxs_unwrap_opt($temp)); } } " +
            "else if constexpr (std::is_pointer_v<std::decay_t<KxsSaRes>> || requires { typename std::decay_t<KxsSaRes>::element_type; } || requires { typename std::decay_t<KxsSaRes>::value_type; std::declval<std::decay_t<KxsSaRes>&>().has_value(); }) { " +
            "return kxs_has ? kxs_sa_fn(kxs_unwrap_opt($temp)) : std::decay_t<KxsSaRes>{}; " +
            "} else { " +
            "return kxs_has ? std::optional<std::decay_t<KxsSaRes>>(kxs_sa_fn(kxs_unwrap_opt($temp))) : std::nullopt; " +
            "} }($lhsCode))"
    }

    private fun emitCallExpr(
        receiverCode: String?,
        receiverIsPointer: Boolean,
        callFrame: PacketFrame,
    ): String {
        val calleeKt = extractCalleeSimpleName(callFrame)
        // Kotlin `contract { callsInPlace(...) }` is a compile-time-only Kotlin intrinsic; elide cleanly in C++23.
        if (calleeKt == "contract" &&
            receiverCode == null &&
            resolveBoundName("contract") == null &&
            "contract" !in bundle.symbols.functionsByName &&
            FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID) != null
        ) {
            return "((void)0)"
        }
        val typeArgsFrame = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
        val typeArgs =
            typeArgsFrame
                ?.let { ta ->
                    FrameAstHelpers
                        .childFramesOfType(stream, ta, KtNodeTypes.TYPE_PROJECTION_ID)
                        .map { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) }
                }.orEmpty()
        val args = extractCallArgExpressions(callFrame)

        // Builtin Kotlin standard functions / pre-conditions / collections
        if (receiverCode == null) {
            if (calleeKt in localPolymorphicClasses) {
                includes.addSystem("memory")
                val structName = CppNamingPolicy.toTypeName(calleeKt)
                return "std::make_shared<$structName>(${args.joinToString(", ")})"
            }
            val recvSub = receiverSubstitution
            val localExtCpp = localExtensionFunctions[calleeKt]
            if (localExtCpp != null && recvSub != null) {
                val allArgs = ArrayList<String>(args.size + 1)
                allArgs.add(recvSub)
                allArgs.addAll(args)
                return "$localExtCpp(${allArgs.joinToString(", ")})"
            }
            if (recvSub != null) {
                when (calleeKt) {
                    "isEmpty" -> return "$recvSub.empty()"
                    "isNotEmpty" -> return "(!$recvSub.empty())"
                    "toTypedArray" -> return recvSub
                }
                val extRecvCls = extensionReceiverClass
                if (extRecvCls != null && classDeclaresMethod(extRecvCls, calleeKt)) {
                    val op = if (extensionReceiverIsPointer) "->" else "."
                    val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                    return "$recvSub$op${CppNamingPolicy.toSnakeCase(calleeKt)}$tSuffix(${args.joinToString(", ")})"
                }
                val extSimple = extensionReceiverSimpleName
                if (extRecvCls == null &&
                    extSimple != null &&
                    resolveBoundName(calleeKt) == null &&
                    calleeKt !in bundle.symbols.functionsByName
                ) {
                    val op = if (extensionReceiverIsPointer) "->" else "."
                    val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                    if (extSimple == "CoroutineContext" && calleeKt == "get" && !bundle.isDocExample && args.size == 1) {
                        includes.needsContextOpsDef = true
                        return "kotlin::coroutines::kxs_ctx_get($recvSub, ${args[0]})"
                    }
                    if (extSimple == "Duration" &&
                        !bundle.isDocExample &&
                        args.size == 1 &&
                        calleeKt in setOf("plus", "minus", "times", "div")
                    ) {
                        val binOp =
                            when (calleeKt) {
                                "plus" -> "+"
                                "minus" -> "-"
                                "times" -> "*"
                                else -> "/"
                            }
                        return "($recvSub $binOp ${args[0]})"
                    }
                    if (calleeKt in KNOWN_EXTERNAL_RECEIVER_METHODS[extSimple].orEmpty()) {
                        return "$recvSub$op${CppNamingPolicy.toSnakeCase(calleeKt)}$tSuffix(${args.joinToString(", ")})"
                    }
                }
            }
            when (calleeKt) {
                "Array" -> {
                    if (args.size == 2) {
                        includes.addSystem("cstdint")
                        includes.addSystem("cstddef")
                        includes.addSystem("vector")
                        val countVar = freshTemp("kxs_n")
                        val initVar = freshTemp("kxs_init")
                        val arrVar = freshTemp("kxs_arr")
                        val idxVar = freshTemp("kxs_i")
                        val elemType = typeArgs.firstOrNull() ?: "decltype($initVar(0))"
                        return "([&]() { const auto $countVar = ${args[0]}; auto $initVar = ${args[1]}; " +
                            "std::vector<$elemType> $arrVar; $arrVar.reserve(static_cast<std::size_t>($countVar)); " +
                            "for (std::int32_t $idxVar = 0; $idxVar < $countVar; ++$idxVar) { $arrVar.push_back($initVar($idxVar)); } " +
                            "return $arrVar; }())"
                    }
                }
                "check", "require" -> {
                    includes.addSystem("stdexcept")
                    val cond = args.getOrNull(0) ?: "true"
                    val msg = args.getOrNull(1) ?: "\"Failed $calleeKt\""
                    val excType = if (calleeKt == "require") "std::invalid_argument" else "std::logic_error"
                    return "(($cond) ? void(0) : throw $excType($msg))"
                }
                "checkNotNull", "requireNotNull" -> {
                    includes.addSystem("stdexcept")
                    val v = args.getOrNull(0) ?: "nullptr"
                    val msg = args.getOrNull(1) ?: "\"Required value was null\""
                    return "([&]() { auto&& kxs_nn = $v; if (!kxs_nn) throw std::invalid_argument($msg); return *kxs_nn; }())"
                }
                "error" -> {
                    includes.addSystem("stdexcept")
                    val msg = args.firstOrNull() ?: "\"Illegal state\""
                    return "throw std::logic_error($msg)"
                }
                "println" -> {
                    includes.addSystem("iostream")
                    val v = args.firstOrNull()
                    return if (v != null) "(std::cout << $v << '\\n')" else "(std::cout << '\\n')"
                }
                "print" -> {
                    includes.addSystem("iostream")
                    val v = args.firstOrNull() ?: "\"\""
                    return "(std::cout << $v)"
                }
                "listOf", "mutableListOf", "arrayListOf" -> {
                    includes.addSystem("vector")
                    val elemT = typeArgs.firstOrNull()
                    return if (elemT != null) {
                        "std::vector<$elemT>{${args.joinToString(", ")}}"
                    } else {
                        "std::vector{${args.joinToString(", ")}}"
                    }
                }
                "emptyList" -> {
                    includes.addSystem("vector")
                    val elemT = typeArgs.firstOrNull() ?: "std::any"
                    return "std::vector<$elemT>{}"
                }
                "setOf", "mutableSetOf", "hashSetOf" -> {
                    includes.addSystem("unordered_set")
                    val elemT = typeArgs.firstOrNull()
                    return if (elemT != null) {
                        "std::unordered_set<$elemT>{${args.joinToString(", ")}}"
                    } else {
                        "std::unordered_set{${args.joinToString(", ")}}"
                    }
                }
                "emptySet" -> {
                    includes.addSystem("unordered_set")
                    val elemT = typeArgs.firstOrNull() ?: "std::any"
                    return "std::unordered_set<$elemT>{}"
                }
                "emptyMap" -> {
                    includes.addSystem("unordered_map")
                    val kT = typeArgs.getOrNull(0) ?: "std::string"
                    val vT = typeArgs.getOrNull(1) ?: "std::any"
                    return "std::unordered_map<$kT, $vT>{}"
                }
                "mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf" -> {
                    includes.addSystem("unordered_map")
                    val kT = typeArgs.getOrNull(0)
                    val vT = typeArgs.getOrNull(1)
                    return if (kT != null && vT != null) {
                        "std::unordered_map<$kT, $vT>{${args.joinToString(", ")}}"
                    } else {
                        "std::unordered_map{${args.joinToString(", ")}}"
                    }
                }
                "arrayOfNulls" -> {
                    includes.addSystem("cstddef")
                    includes.addSystem("vector")
                    val elemT = typeArgs.firstOrNull() ?: "void*"
                    val n = args.firstOrNull() ?: "0"
                    return "std::vector<$elemT>(static_cast<std::size_t>($n))"
                }
                "arrayOf", "intArrayOf", "longArrayOf", "byteArrayOf" -> {
                    includes.addSystem("cstdint")
                    includes.addSystem("vector")
                    val elemT =
                        when (calleeKt) {
                            "intArrayOf" -> "std::int32_t"
                            "longArrayOf" -> "std::int64_t"
                            "byteArrayOf" -> "std::int8_t"
                            else -> typeArgs.firstOrNull()
                        }
                    return if (elemT != null) {
                        "std::vector<$elemT>{${args.joinToString(", ")}}"
                    } else {
                        "std::vector{${args.joinToString(", ")}}"
                    }
                }
                "atomic" -> {
                    if (!bundle.isDocExample) {
                        val elemT = typeArgs.firstOrNull() ?: "void*"
                        val initV = args.firstOrNull() ?: "{}"
                        if (elemT.startsWith("std::shared_ptr<")) {
                            includes.needsAtomicReferenceDef = true
                            includes.addSystem("mutex")
                            return "KxsAtomicReference<$elemT>{$initV}"
                        }
                        includes.addSystem("atomic")
                        return "std::atomic<$elemT>{$initV}"
                    }
                }
                "autoreleasepool" -> {
                    if (!bundle.isDocExample) {
                        val blk = args.firstOrNull() ?: "[](){}"
                        return "($blk)()"
                    }
                }
                "dispatch_async" -> {
                    if (!bundle.isDocExample) {
                        includes.needsDarwinDispatchHelpers = true
                        includes.addSystem("type_traits")
                        includes.addSystem("utility")
                        return "kxs_dispatch_async(${args.joinToString(", ")})"
                    }
                }
                "CFRunLoopTimerCreateWithHandler" -> {
                    if (!bundle.isDocExample) {
                        includes.needsDarwinDispatchHelpers = true
                        includes.addSystem("type_traits")
                        includes.addSystem("utility")
                        return "kxs_cf_timer_create_with_handler(${args.joinToString(", ")})"
                    }
                }
                "createCleaner" -> {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    return "create_cleaner(${args.joinToString(", ")})"
                }
            }
        } else {
            val localExtCpp = localExtensionFunctions[calleeKt]
            if (localExtCpp != null) {
                val allArgs = ArrayList<String>(args.size + 1)
                allArgs.add(receiverCode)
                allArgs.addAll(args)
                return "$localExtCpp(${allArgs.joinToString(", ")})"
            }
            // Check if calling an extension function or rich-enum helper declared in the same file (`fun Recv.ext(...)`)!
            val sameFileFns = bundle.symbols.functionsByName[calleeKt].orEmpty()
            val extFn =
                sameFileFns.firstOrNull { fn ->
                    fn.receiverTypeFrameIndex != null ||
                        fn.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it]?.kind == KtClassKind.RICH_ENUM } == true
                }
            if (extFn != null) {
                val allArgs = ArrayList<String>(args.size + 1)
                allArgs.add(receiverCode)
                allArgs.addAll(args)
                val callName = if (extFn.kotlinName == "invoke") "invoke" else extFn.cppName
                val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                return "$callName$tSuffix(${allArgs.joinToString(", ")})"
            }

            // Standard member method mappings
            val op = if (receiverIsPointer) "->" else "."
            when (calleeKt) {
                "invoke" ->
                    return if (receiverIsPointer) {
                        "(*$receiverCode)(${args.joinToString(", ")})"
                    } else {
                        "$receiverCode(${args.joinToString(", ")})"
                    }
                "get" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("type_traits")
                        return "([&](auto&& kxs_gr) -> decltype(auto) { " +
                            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_gr)>> || requires { kxs_gr.operator->(); }) { " +
                            "if constexpr (requires { kxs_gr->get_value(); }) return kxs_gr->get_value(); " +
                            "else return kxs_gr->get(); " +
                            "} else { " +
                            "if constexpr (requires { kxs_gr.get_value(); }) return kxs_gr.get_value(); " +
                            "else return kxs_gr.get(); } }($receiverCode))"
                    }
                "set" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.addSystem("type_traits")
                        includes.addSystem("utility")
                        return "([&](auto&& kxs_sr, auto&& kxs_sv) { " +
                            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sr)>> || requires { kxs_sr.operator->(); }) { " +
                            "if constexpr (requires { kxs_sr->set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); }) kxs_sr->set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); " +
                            "else if constexpr (std::is_null_pointer_v<std::remove_cvref_t<decltype(kxs_sv)>> && requires { kxs_sr->set_value({}); }) kxs_sr->set_value({}); " +
                            "else kxs_sr->set(std::forward<decltype(kxs_sv)>(kxs_sv)); " +
                            "} else { " +
                            "if constexpr (requires { kxs_sr.set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); }) kxs_sr.set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); " +
                            "else if constexpr (std::is_null_pointer_v<std::remove_cvref_t<decltype(kxs_sv)>> && requires { kxs_sr.set_value({}); }) kxs_sr.set_value({}); " +
                            "else kxs_sr.set(std::forward<decltype(kxs_sv)>(kxs_sv)); } }($receiverCode, ${args[0]}))"
                    }
                "getAndSet" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        return "$receiverCode${op}get_and_set(${args[0]})"
                    }
                "compareAndSet" ->
                    if (!bundle.isDocExample && args.size == 2) {
                        return "$receiverCode${op}compare_and_set(${args[0]}, ${args[1]})"
                    }
                "isEmpty" -> return "$receiverCode${op}empty()"
                "isNotEmpty" -> return "(!$receiverCode${op}empty())"
                "toTypedArray" -> return if (receiverIsPointer) "(*$receiverCode)" else receiverCode
                "add" -> return "$receiverCode${op}push_back(${args.joinToString(", ")})"
                "toString" -> return "$receiverCode${op}to_string()"
                "getOrThrow" -> return "$receiverCode${op}get_or_throw()"
                "exceptionOrNull" -> return "$receiverCode${op}exception_or_null()"
                "addSuppressed" -> if (!bundle.isDocExample) return "((void)0)"
                "printStackTrace" -> if (!bundle.isDocExample && args.isEmpty()) return "((void)0)"
                "toBoolean" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("cctype")
                        includes.addSystem("string")
                        return "([&](const std::string& kxs_s) -> bool { " +
                            "if (kxs_s.size() != 4) return false; " +
                            "return std::tolower(static_cast<unsigned char>(kxs_s[0])) == 't' && " +
                            "std::tolower(static_cast<unsigned char>(kxs_s[1])) == 'r' && " +
                            "std::tolower(static_cast<unsigned char>(kxs_s[2])) == 'u' && " +
                            "std::tolower(static_cast<unsigned char>(kxs_s[3])) == 'e'; }($receiverCode))"
                    }
                "toIntOrNull" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("charconv")
                        includes.addSystem("cstdint")
                        includes.addSystem("optional")
                        includes.addSystem("string")
                        return "([&](const std::string& kxs_s) -> std::optional<std::int32_t> { " +
                            "std::int32_t kxs_v = 0; auto [kxs_p, kxs_ec] = std::from_chars(kxs_s.data(), kxs_s.data() + kxs_s.size(), kxs_v); " +
                            "if (kxs_ec == std::errc{} && kxs_p == kxs_s.data() + kxs_s.size()) return kxs_v; return std::nullopt; }($receiverCode))"
                    }
                "toLongOrNull" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("charconv")
                        includes.addSystem("cstdint")
                        includes.addSystem("optional")
                        includes.addSystem("string")
                        return "([&](const std::string& kxs_s) -> std::optional<std::int64_t> { " +
                            "std::int64_t kxs_v = 0; auto [kxs_p, kxs_ec] = std::from_chars(kxs_s.data(), kxs_s.data() + kxs_s.size(), kxs_v); " +
                            "if (kxs_ec == std::errc{} && kxs_p == kxs_s.data() + kxs_s.size()) return kxs_v; return std::nullopt; }($receiverCode))"
                    }
                "toLong" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("cstdint")
                        return "static_cast<std::int64_t>($receiverCode)"
                    }
                "toInt" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("cstdint")
                        return "static_cast<std::int32_t>($receiverCode)"
                    }
                "toDouble" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        return "static_cast<double>($receiverCode)"
                    }
                "toFloat" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        return "static_cast<float>($receiverCode)"
                    }
                "toShort" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("cstdint")
                        return "static_cast<std::int16_t>($receiverCode)"
                    }
                "toByte" ->
                    if (!bundle.isDocExample && args.isEmpty()) {
                        includes.addSystem("cstdint")
                        return "static_cast<std::int8_t>($receiverCode)"
                    }
                "coerceAtLeast" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.addSystem("algorithm")
                        return "std::max($receiverCode, ${args[0]})"
                    }
                "coerceAtMost" ->
                    if (!bundle.isDocExample && args.size == 1) {
                        includes.addSystem("algorithm")
                        return "std::min($receiverCode, ${args[0]})"
                    }
                "coerceIn" ->
                    if (!bundle.isDocExample && args.size == 2) {
                        includes.addSystem("algorithm")
                        return "std::clamp($receiverCode, ${args[0]}, ${args[1]})"
                    }
            }
        }

        // Constructor invocation vs function/method invocation
        val effectiveCalleeKt = bundle.symbols.importAliases[calleeKt] ?: calleeKt
        val sameFileClass = lookupAccessibleClassByName(calleeKt) ?: lookupAccessibleClassByName(effectiveCalleeKt)
        val sameFileFnExists =
            calleeKt in bundle.symbols.functionsByName || effectiveCalleeKt in bundle.symbols.functionsByName
        val isSameFileConcreteCtor =
            sameFileClass != null &&
                sameFileClass.kind != KtClassKind.INTERFACE &&
                (!PacketFlags.hasFlag(stream.frames[sameFileClass.frameIndex].flags, PacketFlags.MOD_ABSTRACT) || !sameFileFnExists)
        val isExternalTypeCtor =
            sameFileClass == null &&
                effectiveCalleeKt.firstOrNull()?.isUpperCase() == true &&
                !sameFileFnExists &&
                (bundle.isDocExample || effectiveCalleeKt !in KNOWN_PASCAL_FACTORY_FUNCTIONS)
        val isCtorCall = receiverCode == null && (isSameFileConcreteCtor || isExternalTypeCtor)
        val effectiveArgs =
            if (isCtorCall && sameFileClass != null && sameFileClass.isInner) {
                val outerPtr = resolveOuterPointerExpr(sameFileClass.enclosingClassFrameIndex)
                if (outerPtr != null) listOf(outerPtr) + args else args
            } else {
                args
            }

        if (receiverCode == null && !isCtorCall && enclosingClass != null && enclosingClass.isInner) {
            val hasOwnMethod =
                enclosingClass.memberFunctionFrameIndices.any {
                    bundle.symbols.functionsByFrame[it]?.kotlinName == calleeKt
                }
            if (!hasOwnMethod) {
                var cur = enclosingClass.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
                while (cur != null) {
                    val outerFn =
                        cur.memberFunctionFrameIndices
                            .mapNotNull { bundle.symbols.functionsByFrame[it] }
                            .firstOrNull { it.kotlinName == calleeKt }
                    if (outerFn != null) {
                        val chain = resolveOuterChainPrefix(cur.frameIndex).orEmpty()
                        val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                        return "$chain${outerFn.cppName}$tSuffix(${effectiveArgs.joinToString(", ")})"
                    }
                    if (!cur.isInner) break
                    cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
                }
            }
        }

        val activeRecvSub = receiverSubstitution
        if (receiverCode == null && !isCtorCall && activeRecvSub != null && resolveBoundName(calleeKt) == null) {
            val implicitExtFn =
                bundle.symbols.functionsByName[calleeKt]
                    ?.firstOrNull { it.receiverTypeFrameIndex != null }
            if (implicitExtFn != null) {
                val allArgs = ArrayList<String>(effectiveArgs.size + 1)
                allArgs.add(activeRecvSub)
                allArgs.addAll(effectiveArgs)
                val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                return "${implicitExtFn.cppName}$tSuffix(${allArgs.joinToString(", ")})"
            }
        }

        val targetName =
            when {
                isCtorCall -> {
                    val baseType = sameFileClass?.cppName ?: CppNamingPolicy.toTypeName(effectiveCalleeKt)
                    if (sameFileClass == null && !bundle.isDocExample) {
                        CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = effectiveCalleeKt,
                            cppBaseName = baseType,
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: baseType
                    } else {
                        baseType
                    }
                }
                receiverCode == null -> {
                    val sameFileFnList =
                        bundle.symbols.functionsByName[calleeKt] ?: bundle.symbols.functionsByName[effectiveCalleeKt]
                    resolveBoundName(calleeKt)
                        ?: resolveEnclosingCallableProperty(calleeKt)
                        ?: sameFileFnList?.firstOrNull()?.cppName
                        ?: run {
                            val baseFn =
                                if (isVerbatimCIdentifier(effectiveCalleeKt)) {
                                    effectiveCalleeKt
                                } else {
                                    CppNamingPolicy.toSnakeCase(effectiveCalleeKt)
                                }
                            if (!bundle.isDocExample) {
                                CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                                    kotlinName = effectiveCalleeKt,
                                    cppBaseName = baseFn,
                                    currentNs = bundle.symbols.namespaceSegments,
                                    includes = includes,
                                ) ?: baseFn
                            } else {
                                baseFn
                            }
                        }
                }
                else ->
                    if (isVerbatimCIdentifier(calleeKt)) calleeKt else CppNamingPolicy.toSnakeCase(calleeKt)
            }

        val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
        if (isCtorCall &&
            !bundle.isDocExample &&
            (CppTypeTranslator.isSharedHierarchyType(effectiveCalleeKt) || effectiveCalleeKt in bundle.symbols.sharedHierarchyTypeNames) &&
            sameFileClass?.kind != KtClassKind.INTERFACE &&
            (sameFileClass == null || !PacketFlags.hasFlag(stream.frames[sameFileClass.frameIndex].flags, PacketFlags.MOD_ABSTRACT))
        ) {
            includes.addSystem("memory")
            return "std::make_shared<$targetName$tSuffix>(${effectiveArgs.joinToString(", ")})"
        }
        return if (receiverCode == null) {
            "$targetName$tSuffix(${effectiveArgs.joinToString(", ")})"
        } else {
            val op = if (receiverIsPointer) "->" else "."
            "$receiverCode$op$targetName$tSuffix(${effectiveArgs.joinToString(", ")})"
        }
    }

    private fun resolveEnclosingCallableProperty(calleeKt: String): String? {
        var cur: KtClassSymbol? = enclosingClass ?: return null
        if (classDeclaresMethod(enclosingClass, calleeKt)) return null
        var prefix = ""
        while (cur != null) {
            val ctorProp = cur.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == calleeKt }
            if (ctorProp != null) {
                val vpFrame = stream.frames[ctorProp.frameIndex]
                val fieldName =
                    if (PacketFlags.hasFlag(vpFrame.flags, PacketFlags.MOD_PRIVATE)) {
                        CppNamingPolicy.toPrivateFieldName(calleeKt)
                    } else {
                        ctorProp.cppName
                    }
                return "$prefix$fieldName"
            }
            for (mPropIdx in cur.memberPropertyFrameIndices) {
                val mProp = bundle.symbols.propertiesByFrame[mPropIdx] ?: continue
                if (mProp.kotlinName == calleeKt) {
                    val suffix = if (isGetterStyleClassProperty(cur, mProp)) "()" else ""
                    return "$prefix${mProp.cppName}$suffix"
                }
            }
            if (!cur.isInner) break
            cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            prefix += "outer_->"
        }
        return null
    }

    private fun extractCallArgExpressions(callFrame: PacketFrame): List<String> {
        val out = ArrayList<String>()
        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
        if (argList != null) {
            for (va in FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID)) {
                val expr =
                    FrameAstHelpers
                        .childFrames(stream, va)
                        .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                if (expr != null) {
                    out.add(emitExpression(expr))
                }
            }
        }
        for (tl in FrameAstHelpers.childFramesOfType(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID)) {
            val lambda = FrameAstHelpers.unwrapLambdaExpression(stream, tl)
            if (lambda != null) {
                out.add(emitExpression(lambda))
            }
        }
        return out
    }

    private fun emitArrayAccessExpr(arrAccessFrame: PacketFrame): String {
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

    private fun emitBinaryExpr(binFrame: PacketFrame): String {
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
                if (!bundle.isDocExample && !isDirectEnumSelfCompare) {
                    includes.addSystem("any")
                    includes.addSystem("cstddef")
                    includes.addSystem("memory")
                    includes.addSystem("type_traits")
                    includes.addSystem("typeinfo")
                    "([&](const auto& kxs_l, const auto& kxs_r) -> bool { " +
                        "using KxsL = std::remove_cvref_t<decltype(kxs_l)>; " +
                        "using KxsR = std::remove_cvref_t<decltype(kxs_r)>; " +
                        "if constexpr (std::is_same_v<KxsL, KxsR> && (std::is_arithmetic_v<KxsL> || std::is_enum_v<KxsL>)) { " +
                        "return kxs_l $eqOp kxs_r; " +
                        "} else { " +
                        "auto kxs_p = [](const auto& kxs_x) -> const void* { " +
                        "using KxsX = std::remove_cvref_t<decltype(kxs_x)>; " +
                        "if constexpr (std::is_null_pointer_v<KxsX>) return nullptr; " +
                        "else if constexpr (std::is_pointer_v<KxsX>) return static_cast<const void*>(kxs_x); " +
                        "else if constexpr (std::is_same_v<KxsX, std::any>) return (!kxs_x.has_value() || kxs_x.type() == typeid(std::nullptr_t)) ? nullptr : static_cast<const void*>(std::addressof(kxs_x)); " +
                        "else if constexpr (requires { kxs_x.get(); }) return static_cast<const void*>(kxs_x.get()); " +
                        "else if constexpr (requires { typename KxsX::value_type; kxs_x.has_value(); *kxs_x; }) return kxs_x.has_value() ? static_cast<const void*>(std::addressof(*kxs_x)) : nullptr; " +
                        "else return static_cast<const void*>(std::addressof(kxs_x)); }; " +
                        "return kxs_p(kxs_l) $eqOp kxs_p(kxs_r); } }($lhs, $rhs))"
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
            else -> {
                if (opText in CPP_BINARY_OPERATORS) {
                    if (opText in ASSIGN_OPS) {
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

    private fun emitBinaryWithTypeExpr(castFrame: PacketFrame): String {
        val lhs = FrameAstHelpers.childFrames(stream, castFrame).firstOrNull()
        val opRef = FrameAstHelpers.firstChildFrame(stream, castFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val tr = FrameAstHelpers.firstChildFrame(stream, castFrame, KtNodeTypes.TYPE_REFERENCE_ID)
        val isSafeCast = opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.AS_SAFE_ID
        val targetCpp = tr?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.VALUE) } ?: "void*"
        val cleanType = targetCpp.removeSuffix("&")

        if (!isSafeCast && lhs != null && unwrapTransparentFrame(lhs).nodeTypeId == KtNodeTypes.NULL_ID) {
            return "$cleanType{}"
        }

        val lhsCode = lhs?.let { emitExpression(it) } ?: "nullptr"
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
            if (bundle.isDocExample) {
                "dynamic_cast<$rawTarget*>($lhsCode)"
            } else {
                includes.needsCastHelpersDef = true
                "kxs_safe_as_cast<$cleanType>($lhsCode)"
            }
        } else if (bundle.isDocExample) {
            "static_cast<$cleanType>($lhsCode)"
        } else {
            includes.needsCastHelpersDef = true
            "kxs_as_cast<$cleanType>($lhsCode)"
        }
    }

    private fun emitIsExpr(isFrame: PacketFrame): String {
        val lhs = FrameAstHelpers.childFrames(stream, isFrame).firstOrNull()
        val opRef = FrameAstHelpers.firstChildFrame(stream, isFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val tr = FrameAstHelpers.firstChildFrame(stream, isFrame, KtNodeTypes.TYPE_REFERENCE_ID)
        val negated = opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.NOT_IS_ID
        val lhsCode = lhs?.let { emitExpression(it) } ?: "this"
        val cppType =
            tr
                ?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) }
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

    private fun emitRuntimeIsCheck(
        subjExpr: String,
        cppType: String,
    ): String {
        includes.needsCastHelpersDef = true
        return "kxs_is_instance<$cppType>($subjExpr)"
    }

    private fun emitPrefixExpr(prefixFrame: PacketFrame): String {
        val opRef = FrameAstHelpers.firstChildFrame(stream, prefixFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val operand =
            FrameAstHelpers
                .childFrames(stream, prefixFrame)
                .lastOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
        val op = opRef?.let { FrameAstHelpers.operationSymbolName(stream, it) }.orEmpty()
        val inner = operand?.let { emitExpression(it) }.orEmpty()
        return "$op$inner"
    }

    private fun emitPostfixExpr(postfixFrame: PacketFrame): String {
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

    private fun canEmitTernaryIf(ifFrame: PacketFrame): Boolean {
        if (PacketFlags.hasFlag(ifFrame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE)) return false
        val condWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.CONDITION_ID)
        val thenWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.THEN_ID)
        val elseWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.ELSE_ID)
        val cond = condWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val thenChild = thenWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val elseChild = elseWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        return cond != null &&
            thenChild != null &&
            elseChild != null &&
            thenChild.nodeTypeId != KtNodeTypes.BLOCK_ID &&
            elseChild.nodeTypeId != KtNodeTypes.BLOCK_ID &&
            thenChild.nodeTypeId !in STATEMENT_ONLY_NODE_TYPES &&
            elseChild.nodeTypeId !in STATEMENT_ONLY_NODE_TYPES &&
            (unwrapTransparentFrame(thenChild).nodeTypeId == KtNodeTypes.NULL_ID) ==
            (unwrapTransparentFrame(elseChild).nodeTypeId == KtNodeTypes.NULL_ID) &&
            !PacketFlags.hasFlag(thenChild.flags, PacketFlags.DIVERGING_EXPR) &&
            !PacketFlags.hasFlag(elseChild.flags, PacketFlags.DIVERGING_EXPR) &&
            bundle.cfPlan.smartCastsByScopeFrame[thenWrap.frameIndex].isNullOrEmpty() &&
            bundle.cfPlan.smartCastsByScopeFrame[elseWrap.frameIndex].isNullOrEmpty()
    }

    private fun emitTernaryIfCore(ifFrame: PacketFrame): String {
        val condWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.CONDITION_ID)!!
        val thenWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.THEN_ID)!!
        val elseWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.ELSE_ID)!!
        val cond = FrameAstHelpers.childFrames(stream, condWrap).first()
        val thenChild = FrameAstHelpers.childFrames(stream, thenWrap).first()
        val elseChild = FrameAstHelpers.childFrames(stream, elseWrap).first()
        return "${emitExpression(cond)} ? ${emitExpression(thenChild)} : ${emitExpression(elseChild)}"
    }

    private fun emitExpressionIf(ifFrame: PacketFrame): String {
        if (canEmitTernaryIf(ifFrame)) {
            return "(${emitTernaryIfCore(ifFrame)})"
        }
        return "[&]() {\n${emitIfStatement(ifFrame, "    ", inValuePosition = true, targetReturn = true, targetVar = null)}}()"
    }

    private fun canEmitCppSwitch(entries: List<PacketFrame>): Boolean {
        if (entries.isEmpty()) return false
        var hasCaseLabel = false
        for (entry in entries) {
            if (!bundle.cfPlan.smartCastsByScopeFrame[entry.frameIndex].isNullOrEmpty()) return false
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

    private fun isSwitchConstantExpr(expr: PacketFrame): Boolean =
        when (expr.nodeTypeId) {
            KtNodeTypes.INTEGER_CONSTANT_ID,
            KtNodeTypes.CHARACTER_CONSTANT_ID,
            KtNodeTypes.BOOLEAN_CONSTANT_ID,
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

    private fun isEnumOrAllCapsName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (enclosingClass?.enumEntries?.any { it.kotlinName == name } == true) return true
        val allClasses = bundle.symbols.classesByName.values
        if (allClasses.any { cls -> cls.enumEntries.any { it.kotlinName == name } }) return true
        return name.any { it.isLetter() } && name.all { it.isUpperCase() || it.isDigit() || it == '_' }
    }

    private fun doesBranchAlwaysTransferControl(branchFrame: PacketFrame): Boolean {
        if (PacketFlags.hasFlag(branchFrame.flags, PacketFlags.DIVERGING_EXPR)) return true
        return when (branchFrame.nodeTypeId) {
            KtNodeTypes.RETURN_ID,
            KtNodeTypes.THROW_ID,
            KtNodeTypes.BREAK_ID,
            KtNodeTypes.CONTINUE_ID,
            -> true
            KtNodeTypes.BLOCK_ID -> {
                val lastStmt = FrameAstHelpers.childFrames(stream, branchFrame).lastOrNull()
                lastStmt != null && doesBranchAlwaysTransferControl(lastStmt)
            }
            else -> false
        }
    }

    private fun isDirectIdentityOperand(expr: PacketFrame): Boolean {
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

    private fun isKnownPointerOrAnyBinding(kotlinName: String): Boolean {
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

    private fun emitLambdaClosureExpr(lambdaFrame: PacketFrame): String {
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
        val pList = fnLit?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.VALUE_PARAMETER_LIST_ID) }
        val params = FrameAstHelpers.extractValueParams(stream, pList)
        val bodyBlock = fnLit?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.BLOCK_ID) }

        return withLexicalScope {
            val paramDecls =
                if (params.isNotEmpty()) {
                    params.mapIndexed { idx, vp ->
                        val pName = if (vp.name == "_") "unused_$idx" else vp.cppName
                        if (vp.name != "_") {
                            bindLocalName(vp.name, pName)
                        }
                        val t =
                            vp.typeFrameIndex
                                ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER) }
                                ?: "auto&&"
                        "$t $pName"
                    }
                } else if (bodyBlock != null && referencesImplicitIt(bodyBlock)) {
                    bindLocalName("it", "it")
                    listOf("auto&& it")
                } else {
                    emptyList()
                }
            val bodyCode =
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
            val capture = if (inDefaultParameterExpr) "[]" else "[&]"
            "$capture(${paramDecls.joinToString(", ")}) $bodyCode"
        }
    }

    private fun canEmitLambdaTailReturn(stmtFrame: PacketFrame): Boolean {
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

    private fun containsBareVoidReturnInLambda(frame: PacketFrame): Boolean {
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

    private fun canEmitSingleLambdaExprReturn(stmtFrame: PacketFrame): Boolean {
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
                if (opText == "?:" && unwrapTransparentFrame(ch[2]).nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES) {
                    return false
                }
                true
            }
            else -> true
        }
    }

    private fun collectInlineElvisControlTransfers(
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
                unwrapTransparentFrame(ch[2]).nodeTypeId in ELVIS_CONTROL_TRANSFER_NODE_TYPES
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

    private fun emitCallableRefExpr(refExpr: PacketFrame): String {
        val ch = FrameAstHelpers.childFrames(stream, refExpr)
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

    private fun emitAtomicOpExpression(aop: AtomicOperationSite): String {
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
            AtomicOpKind.INCREMENT_AND_GET -> "($target.fetch_add(1, std::memory_order_seq_cst) + 1)"
            AtomicOpKind.GET_AND_INCREMENT -> "$target.fetch_add(1, std::memory_order_seq_cst)"
            AtomicOpKind.DECREMENT_AND_GET -> "($target.fetch_sub(1, std::memory_order_seq_cst) - 1)"
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
                        if (body != null && body.nodeTypeId == KtNodeTypes.BLOCK_ID) {
                            "[&]() ${emitBlock(body, "", isValueBlock = true, resultTargetVar = null)}()"
                        } else if (body != null) {
                            emitExpression(body)
                        } else {
                            curCpp
                        }
                    "([&]() { while (true) { auto $curCpp = $target.load(std::memory_order_seq_cst); " +
                        "auto kxs_next = $computeNext; " +
                        "if ($target.compare_exchange_strong($curCpp, kxs_next, std::memory_order_seq_cst, std::memory_order_relaxed)) " +
                        "return $retExpr; } }())"
                }
            }
            AtomicOpKind.LOOP,
            AtomicOpKind.UPDATE,
            -> "([&]() {\n${emitAtomicLoopOrUpdateStatement(aop, "    ")}}())"
        }
    }

    private fun renderAtomicTargetLvalue(aop: AtomicOperationSite): String {
        val baseField =
            if (aop.receiverPrefixExprFrameIndex != null) {
                val prefFrame = stream.frames[aop.receiverPrefixExprFrameIndex]
                val prefCode = emitExpression(prefFrame)
                val op = if (isPointerOrSharedPtrReceiver(prefFrame, prefCode)) "->" else "."
                "$prefCode$op${aop.targetFieldInfo.cppName}"
            } else {
                val bound = resolveBoundName(aop.targetFieldInfo.kotlinName)
                if (bound != null) {
                    bound
                } else {
                    val ownerClsIdx = bundle.symbols.propertiesByFrame[aop.targetFieldInfo.propFrameIndex]?.enclosingClassFrameIndex
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

    private fun emitCInteropOpExpression(cop: CInteropExprSite): String {
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
                val ptrType = if (targetT.endsWith("*")) targetT else "$targetT*"
                "reinterpret_cast<$ptrType>($recv)"
            }
            CInteropOpKind.TO_CPOINTER -> {
                includes.addSystem("cstdint")
                val ptrType = if (targetT.endsWith("*")) targetT else "$targetT*"
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
        }
    }

    private fun inferControlFlowResultType(cfFrame: PacketFrame): String {
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

    private fun collectBranchValueLeaves(
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

    private fun referencesImplicitIt(root: PacketFrame): Boolean {
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

    private fun extractCalleeSimpleName(callFrame: PacketFrame): String {
        val ref = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
        return if (ref != null) {
            FrameAstHelpers.directIdentPacket(stream, ref)?.unescapedName.orEmpty()
        } else {
            FrameAstHelpers.directIdentPacket(stream, callFrame)?.unescapedName.orEmpty()
        }
    }

    private fun formatIndentedRawCpp(
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

    private fun isEnclosingClassProperty(kotlinName: String): Boolean {
        var cur: KtClassSymbol? = enclosingClass
        while (cur != null) {
            if (cur.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == kotlinName }) return true
            if (cur.memberPropertyFrameIndices.any { bundle.symbols.propertiesByFrame[it]?.kotlinName == kotlinName }) return true
            if (!cur.isInner) break
            cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        }
        return false
    }

    private fun resolvePrimarySuperCppType(superFrame: PacketFrame? = null): String =
        resolvePrimarySuperSimpleAndCpp(superFrame).second

    private fun resolvePrimarySuperSimpleAndCpp(superFrame: PacketFrame? = null): Pair<String?, String> {
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

    private fun isIdentChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun containsUnquotedStandaloneIdent(
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

    private fun replaceUnquotedStandaloneIdent(
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

    private fun isVerbatimCIdentifier(name: String): Boolean =
        name.startsWith("CF") ||
            name.startsWith("kCF") ||
            name.startsWith("dispatch_") ||
            name.startsWith("pthread_") ||
            name.startsWith("mach_") ||
            name.startsWith("objc_")

    companion object {
        fun escapeCppStringLiteral(text: String): String =
            buildString(text.length + 2) {
                append('"')
                for (ch in text) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        '\b' -> append("\\b")
                        else -> {
                            if (ch.code < 0x20) {
                                append("\\x").append(ch.code.toString(16).padStart(2, '0'))
                            } else {
                                append(ch)
                            }
                        }
                    }
                }
                append('"')
            }

        private val CPP_BINARY_OPERATORS: Set<String> =
            setOf("+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">=", "&&", "||", "=", "+=", "-=", "*=", "/=", "%=")

        private val ASSIGN_OPS: Set<String> =
            setOf("=", "+=", "-=", "*=", "/=", "%=")

        private val ELVIS_CONTROL_TRANSFER_NODE_TYPES: Set<Int> =
            setOf(
                KtNodeTypes.RETURN_ID,
                KtNodeTypes.BREAK_ID,
                KtNodeTypes.CONTINUE_ID,
                KtNodeTypes.THROW_ID,
            )

        private val EXCEPTION_PTR_KOTLIN_TYPES: Set<String> =
            setOf(
                "Throwable",
                "Exception",
                "RuntimeException",
                "IllegalStateException",
                "IllegalArgumentException",
            )

        private val COMPOUND_OR_DECLARATION_STMT_NODE_TYPES: Set<Int> =
            setOf(
                KtNodeTypes.BLOCK_ID,
                KtNodeTypes.IF_ID,
                KtNodeTypes.WHEN_ID,
                KtNodeTypes.TRY_ID,
                KtNodeTypes.WHILE_ID,
                KtNodeTypes.DO_WHILE_ID,
                KtNodeTypes.FOR_ID,
                KtNodeTypes.FUN_ID,
                KtNodeTypes.CLASS_ID,
                KtNodeTypes.OBJECT_DECLARATION_ID,
            )

        private val STATEMENT_ONLY_NODE_TYPES: Set<Int> =
            setOf(
                KtNodeTypes.PROPERTY_ID,
                KtNodeTypes.FUN_ID,
                KtNodeTypes.CLASS_ID,
                KtNodeTypes.OBJECT_DECLARATION_ID,
                KtNodeTypes.RETURN_ID,
                KtNodeTypes.THROW_ID,
                KtNodeTypes.BREAK_ID,
                KtNodeTypes.CONTINUE_ID,
                KtNodeTypes.FOR_ID,
                KtNodeTypes.WHILE_ID,
                KtNodeTypes.DO_WHILE_ID,
            )

        private val CONTINUATION_HIERARCHY_BASES: Set<String> =
            setOf(
                "Continuation",
                "CancellableContinuation",
                "CancellableContinuationImpl",
                "AbstractCoroutine",
                "ScopeCoroutine",
                "DispatchedContinuation",
                "UndispatchedCoroutine",
                "CoroutineImpl",
                "BaseContinuationImpl",
                "ContinuationImpl",
                "RestrictedContinuationImpl",
            )

        private val KNOWN_OBJECT_SINGLETONS: Set<String> =
            setOf(
                "GlobalScope",
                "NonCancellable",
                "DefaultExecutor",
                "DefaultDelay",
                "DefaultScheduler",
                "DefaultIoScheduler",
                "UnlimitedIoScheduler",
            )

        private val KNOWN_PASCAL_FACTORY_FUNCTIONS: Set<String> =
            setOf(
                "Job",
                "SupervisorJob",
                "CoroutineScope",
                "MainScope",
                "CompletableDeferred",
                "Channel",
                "BroadcastChannel",
                "MutableSharedFlow",
                "MutableStateFlow",
                "Mutex",
                "Semaphore",
            )

        private val KNOWN_CONTEXT_KEY_CARRIERS: Set<String> =
            setOf(
                "Job",
                "CoroutineName",
                "CoroutineId",
                "CoroutineExceptionHandler",
                "YieldContext",
                "TestCoroutineScheduler",
            )

        private val KNOWN_TO_STRING_SUPER_FRAGMENTS: Set<String> =
            setOf(
                "LockFreeLinkedListNode",
                "Segment",
                "CoroutineDispatcher",
                "MainCoroutineDispatcher",
                "ExecutorCoroutineDispatcher",
                "CoroutineContext",
                "AbstractCoroutineContextElement",
                "CancellationException",
                "DispatchedTask",
                "JobNode",
                "JobSupport",
                "AbstractCoroutine",
                "ScopeCoroutine",
                "BaseContinuationImpl",
                "ContinuationImpl",
                "RestrictedContinuationImpl",
                "SuspendLambda",
                "RestrictedSuspendLambda",
                "CancellableContinuationImpl",
                "DispatchedContinuation",
                "SynchronizedObject",
                "Task",
                "DelayedTask",
            )

        private val DURATION_WHOLE_UNITS: Set<String> =
            setOf(
                "inWholeNanoseconds",
                "inWholeMicroseconds",
                "inWholeSeconds",
                "inWholeMinutes",
                "inWholeHours",
                "inWholeDays",
            )

        private val KNOWN_EXTERNAL_RECEIVER_PROPERTIES: Map<String, Set<String>> =
            mapOf(
                "CoroutineScope" to setOf("coroutineContext"),
                "Continuation" to setOf("context"),
                "CancellableContinuation" to setOf("context", "isActive", "isCompleted", "isCancelled"),
                "Duration" to
                    setOf(
                        "inWholeMilliseconds",
                        "inWholeNanoseconds",
                        "inWholeMicroseconds",
                        "inWholeSeconds",
                        "inWholeMinutes",
                        "inWholeHours",
                        "inWholeDays",
                        "absoluteValue",
                    ),
                "Job" to setOf("isActive", "isCompleted", "isCancelled", "parent", "children"),
                "CompletableJob" to setOf("isActive", "isCompleted", "isCancelled", "parent", "children"),
                "Deferred" to setOf("isActive", "isCompleted", "isCancelled", "onAwait"),
                "CompletableDeferred" to setOf("isActive", "isCompleted", "isCancelled", "onAwait"),
                "SendChannel" to setOf("isClosedForSend", "onSend"),
                "ReceiveChannel" to setOf("isClosedForReceive", "isEmpty", "onReceive", "onReceiveCatching"),
                "Channel" to setOf("isClosedForSend", "isClosedForReceive", "isEmpty", "onSend", "onReceive", "onReceiveCatching"),
            )

        private val KNOWN_EXTERNAL_RECEIVER_METHODS: Map<String, Set<String>> =
            mapOf(
                "Duration" to
                    setOf(
                        "isPositive",
                        "isNegative",
                        "isInfinite",
                        "isFinite",
                        "plus",
                        "minus",
                        "times",
                        "div",
                        "toComponents",
                        "toLong",
                        "compareTo",
                        "absoluteValue",
                    ),
                "CoroutineContext" to setOf("get", "fold", "minusKey"),
                "Job" to setOf("start", "cancel", "join", "attachChild", "invokeOnCompletion", "getCancellationException"),
                "CompletableJob" to
                    setOf(
                        "start",
                        "cancel",
                        "join",
                        "attachChild",
                        "invokeOnCompletion",
                        "getCancellationException",
                        "complete",
                        "completeExceptionally",
                    ),
                "Deferred" to
                    setOf(
                        "start",
                        "cancel",
                        "join",
                        "attachChild",
                        "invokeOnCompletion",
                        "getCancellationException",
                        "await",
                        "getCompleted",
                        "getCompletionExceptionOrNull",
                    ),
                "CompletableDeferred" to
                    setOf(
                        "start",
                        "cancel",
                        "join",
                        "attachChild",
                        "invokeOnCompletion",
                        "getCancellationException",
                        "await",
                        "getCompleted",
                        "getCompletionExceptionOrNull",
                        "complete",
                        "completeExceptionally",
                    ),
                "Continuation" to setOf("resume", "resumeWith"),
                "CancellableContinuation" to
                    setOf(
                        "resume",
                        "resumeWith",
                        "resumeUndispatched",
                        "resumeUndispatchedWithException",
                        "tryResume",
                        "tryResumeWithException",
                        "completeResume",
                        "initCancellability",
                        "cancel",
                        "invokeOnCancellation",
                    ),
                "SendChannel" to setOf("send", "trySend", "close", "invokeOnClose"),
                "ReceiveChannel" to setOf("receive", "receiveCatching", "tryReceive", "cancel", "iterator"),
                "Channel" to setOf("send", "trySend", "close", "invokeOnClose", "receive", "receiveCatching", "tryReceive", "cancel", "iterator"),
                "Flow" to setOf("collect"),
                "FlowCollector" to setOf("emit"),
                "StringBuilder" to setOf("append", "appendLine", "clear", "toString"),
            )
    }
}
