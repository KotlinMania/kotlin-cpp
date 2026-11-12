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
)

/**
 * Emits C++23 statements and expressions from lowered [PacketFrame] trees while preserving
 * interior comments, left-to-right `hasSuspendCallInTail` temporaries, non-IIFE suspend
 * control-flow result slots, `atomicfu` operations, `cinterop` idioms, and `KONAN_GC` barriers.
 */
internal class CppExprAndStmtEmitter(
    private val bundle: LoweringBundle,
    private val activeTypeParams: Set<String> = emptySet(),
    private val enclosingClass: KtClassSymbol? = null,
    private val receiverSubstitution: String? = null,
) {
    private val stream: PacketStream get() = bundle.stream
    private val includes: CppIncludeCollector get() = bundle.includes
    private val localBindings = ArrayDeque<MutableMap<String, String>>()
    private var synthCounter = 0

    init {
        localBindings.addLast(LinkedHashMap())
    }

    fun bindLocalName(
        kotlinName: String,
        cppExpr: String,
    ) {
        localBindings.last()[kotlinName] = cppExpr
    }

    private fun <T> withLexicalScope(block: () -> T): T {
        localBindings.addLast(LinkedHashMap())
        return try {
            block()
        } finally {
            localBindings.removeLast()
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
                            val formatted = FrameAstHelpers.formatCommentForCpp(pkt, innerIndent)
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
            val srcExpr = resolveBoundName(sc.kotlinVarName) ?: CppNamingPolicy.toSnakeCase(sc.kotlinVarName)
            out.append(indent)
                .append("auto* ")
                .append(sc.narrowedCppName)
                .append(" = dynamic_cast<")
                .append(targetCppType)
                .append("*>(std::addressof(")
                .append(srcExpr)
                .append("));\n")
            includes.addSystem("memory")
            bindLocalName(sc.kotlinVarName, "(*${sc.narrowedCppName})")
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
            out.append(indent).append("auto ").append(ht.tempCppName).append(" = ").append(exprCode).append(";\n")
        }

        val cfValues = bundle.cfPlan.hoistedSuspendControlFlowsBeforeStmt[stmtFrameIndex].orEmpty()
        for (cf in cfValues) {
            includes.addSystem("optional")
            val cfFrame = stream.frames[cf.controlFlowFrameIndex]
            val slotType = inferControlFlowResultType(cfFrame)
            out.append(indent).append("std::optional<").append(slotType).append("> ").append(cf.slotCppName).append(";\n")
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

    private fun emitValueAssignmentOrReturn(
        exprFrame: PacketFrame,
        indent: String,
        targetReturn: Boolean,
        targetVar: String?,
    ): String {
        // Check if this expression is itself a hoisted suspend control-flow node
        val hoistedCf = bundle.cfPlan.hoistedSuspendControlFlowsByExpr[exprFrame.frameIndex]
        if (hoistedCf != null) {
            includes.addSystem("utility")
            return if (targetReturn) {
                "${indent}return std::move(*${hoistedCf.slotCppName});\n"
            } else {
                "$indent$targetVar = std::move(*${hoistedCf.slotCppName});\n"
            }
        }

        return when (exprFrame.nodeTypeId) {
            KtNodeTypes.RETURN_ID,
            KtNodeTypes.THROW_ID,
            KtNodeTypes.BREAK_ID,
            KtNodeTypes.CONTINUE_ID,
            -> emitStatement(exprFrame, indent, inValuePosition = false, resultTargetVar = null)

            KtNodeTypes.IF_ID ->
                emitIfStatement(exprFrame, indent, inValuePosition = true, targetReturn = targetReturn, targetVar = targetVar)

            KtNodeTypes.WHEN_ID ->
                emitWhenStatement(exprFrame, indent, inValuePosition = true, targetReturn = targetReturn, targetVar = targetVar)

            KtNodeTypes.TRY_ID ->
                emitTryStatement(exprFrame, indent, inValuePosition = true, targetReturn = targetReturn, targetVar = targetVar)

            KtNodeTypes.BLOCK_ID ->
                "${emitBlock(exprFrame, indent.dropLast(4), isValueBlock = targetReturn, resultTargetVar = targetVar)}\n"

            else -> {
                val rendered = emitExpression(exprFrame)
                if (PacketFlags.hasFlag(exprFrame.flags, PacketFlags.DIVERGING_EXPR)) {
                    includes.addSystem("utility")
                    "$indent$rendered;\n${indent}std::unreachable();\n"
                } else if (targetReturn) {
                    "${indent}return $rendered;\n"
                } else {
                    "$indent$targetVar = $rendered;\n"
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
            val formatted = FrameAstHelpers.formatCommentForCpp(leadComment, indent)
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
        if (inValuePosition && resultTargetVar != null) {
            when (stmtFrame.nodeTypeId) {
                KtNodeTypes.IF_ID ->
                    return emitIfStatement(stmtFrame, indent, inValuePosition = true, targetReturn = false, targetVar = resultTargetVar)
                KtNodeTypes.WHEN_ID ->
                    return emitWhenStatement(stmtFrame, indent, inValuePosition = true, targetReturn = false, targetVar = resultTargetVar)
                KtNodeTypes.TRY_ID ->
                    return emitTryStatement(stmtFrame, indent, inValuePosition = true, targetReturn = false, targetVar = resultTargetVar)
                else -> return emitValueAssignmentOrReturn(stmtFrame, indent, targetReturn = false, targetVar = resultTargetVar)
            }
        }

        // Check inline call expansion (`let`, `also`, `run`, `apply`, `use`, `repeat`, `withLock`, `forEach`, inline fun)
        bundle.suspendPlan.inlineExpansionsByCallFrame[stmtFrame.frameIndex]?.let { inlineExp ->
            return emitInlineExpansionAsStatement(inlineExp, indent, targetVar = null)
        }

        // Check `memScoped { ... }`
        bundle.ffiPlan.memScopedBlocksByFrame[stmtFrame.frameIndex]?.let { ms ->
            return emitMemScopedStatement(ms, indent)
        }

        // Check `usePinned { ... }`
        bundle.ffiPlan.usePinnedBlocksByFrame[stmtFrame.frameIndex]?.let { up ->
            return emitUsePinnedStatement(up, indent)
        }

        // Check `atomicfu` loop/update statement
        bundle.atomicPlan.atomicOpsByExprFrame[stmtFrame.frameIndex]?.let { aop ->
            if (aop.kind == AtomicOpKind.LOOP || aop.kind == AtomicOpKind.UPDATE) {
                return emitAtomicLoopOrUpdateStatement(aop, indent)
            }
        }

        // Check verbatim inline C/C++ call statement
        bundle.ffiPlan.inlineCppByFrame[stmtFrame.frameIndex]?.let { rawCpp ->
            return formatIndentedRawCpp(rawCpp, indent)
        }

        return when (stmtFrame.nodeTypeId) {
            KtNodeTypes.PROPERTY_ID -> emitLocalPropertyDecl(stmtFrame, indent)
            KtNodeTypes.DESTRUCTURING_DECLARATION_ID -> emitDestructuringDecl(stmtFrame, indent)
            KtNodeTypes.RETURN_ID -> emitReturnStmt(stmtFrame, indent)
            KtNodeTypes.THROW_ID -> emitThrowStmt(stmtFrame, indent)
            KtNodeTypes.BREAK_ID -> "${indent}break;\n"
            KtNodeTypes.CONTINUE_ID -> "${indent}continue;\n"
            KtNodeTypes.IF_ID ->
                emitIfStatement(stmtFrame, indent, inValuePosition = false, targetReturn = false, targetVar = null)
            KtNodeTypes.WHEN_ID ->
                emitWhenStatement(stmtFrame, indent, inValuePosition = false, targetReturn = false, targetVar = null)
            KtNodeTypes.TRY_ID ->
                emitTryStatement(stmtFrame, indent, inValuePosition = false, targetReturn = false, targetVar = null)
            KtNodeTypes.WHILE_ID -> emitWhileStmt(stmtFrame, indent)
            KtNodeTypes.DO_WHILE_ID -> emitDoWhileStmt(stmtFrame, indent)
            KtNodeTypes.FOR_ID -> emitForStmt(stmtFrame, indent)
            KtNodeTypes.BLOCK_ID -> "${emitBlock(stmtFrame, indent, isValueBlock = false, resultTargetVar = null)}\n"
            else -> {
                val exprText = emitExpression(stmtFrame)
                if (PacketFlags.hasFlag(stmtFrame.flags, PacketFlags.DIVERGING_EXPR)) {
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
                val declType =
                    if (typeFrame != null) {
                        bundle.typeTranslator.translateTypeFrame(typeFrame, CppTypePosition.VALUE, propFrame.flags)
                    } else {
                        "std::any"
                    }
                bindLocalName(kotlinName, cppName)
                return buildString {
                    append(indent).append(declType).append(' ').append(cppName).append("{};\n")
                    append(emitInlineExpansionAsStatement(inlineExp, indent, targetVar = cppName))
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
                bundle.typeTranslator.translateTypeFrame(typeFrame, CppTypePosition.VALUE, propFrame.flags)
            } else {
                "auto"
            }
        val constQual = if (!isVar && cppType != "void") "const " else ""
        bindLocalName(kotlinName, cppName)
        return if (initFrame != null) {
            "$indent$constQual$cppType $cppName = ${emitExpression(initFrame)};\n"
        } else {
            "$indent$cppType $cppName{};\n"
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
        val exprChild =
            FrameAstHelpers.childFrames(stream, retFrame)
                .firstOrNull { it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID }
        return if (exprChild == null) {
            "${indent}return;\n"
        } else {
            "${indent}return ${emitExpression(exprChild)};\n"
        }
    }

    private fun emitThrowStmt(
        throwFrame: PacketFrame,
        indent: String,
    ): String {
        val exprChild = FrameAstHelpers.childFrames(stream, throwFrame).firstOrNull()
        val rendered = exprChild?.let { emitThrowableExpr(it) } ?: "std::runtime_error(\"throw\")"
        return "${indent}throw $rendered;\n"
    }

    private fun emitThrowableExpr(exprFrame: PacketFrame): String {
        if (exprFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val callee =
                FrameAstHelpers.firstChildFrame(stream, exprFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
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

        return buildString {
            append(indent).append("if (").append(condCode).append(") ")
            append(emitBranchBody(thenChild, indent, inValuePosition, targetReturn, targetVar, thenSmartCasts))
            if (elseChild != null) {
                if (elseChild.nodeTypeId == KtNodeTypes.IF_ID && elseSmartCasts.isEmpty()) {
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
                    append(emitBranchBody(elseChild, indent, inValuePosition, targetReturn, targetVar, elseSmartCasts))
                    append('\n')
                }
            } else {
                append('\n')
            }
        }
    }

    private fun emitBranchBody(
        branchChild: PacketFrame?,
        indent: String,
        inValuePosition: Boolean,
        targetReturn: Boolean,
        targetVar: String?,
        smartCasts: List<SmartCastBinding>,
    ): String {
        if (branchChild == null) {
            return "{}"
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
                            val singleEvalTemp = bundle.cfPlan.singleEvalTempNamesByFrame[whenFrame.frameIndex]
                            if (singleEvalTemp != null) {
                                append(indent).append("{\n")
                                needOuterCloseBrace = true
                                workIndent = "$indent    "
                                append(workIndent).append("const auto ").append(singleEvalTemp)
                                    .append(" = ").append(emitExpression(subjectFrame)).append(";\n")
                                singleEvalTemp
                            } else {
                                emitExpression(subjectFrame)
                            }
                        }
                        else -> null
                    }

                var hasElseBranch = false
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
                        tr?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) }
                            ?.removeSuffix("&")
                            ?.removeSuffix("*")
                            ?: "void"
                    includes.addSystem("memory")
                    val subj = subjectCode ?: "this"
                    val check = "dynamic_cast<const $cppType*>(std::addressof($subj)) != nullptr"
                    parts.add(if (negated) "!($check)" else "($check)")
                }
                KtNodeTypes.WHEN_CONDITION_IN_RANGE_ID -> {
                    val negated = FrameAstHelpers.hasDirectKeyword(stream, cf, KtTokens.NOT_IN_ID)
                    val rangeExpr =
                        FrameAstHelpers.childFrames(stream, cf)
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
                val op = FrameAstHelpers.frameSourceSlice(stream, ch[1]).trim()
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
                    append(workIndent).append(
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
                    val catchBlock = FrameAstHelpers.firstChildFrame(stream, catchFrame, KtNodeTypes.BLOCK_ID)
                    includes.addSystem("exception")
                    append(" catch (const std::exception& ").append(catchVarCpp).append(") ")
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
                    val op = FrameAstHelpers.frameSourceSlice(stream, ch[1]).trim()
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
                        val recvCode = inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "this"
                        val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val pCpp = CppNamingPolicy.toSnakeCase(pName)
                        append(inner).append("auto&& ").append(pCpp).append(" = ").append(recvCode).append(";\n")
                        bindLocalName(pName, pCpp)
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar)
                    }
                    BuiltinInlineKind.ALSO -> {
                        val recvCode = inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "this"
                        val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val pCpp = CppNamingPolicy.toSnakeCase(pName)
                        append(inner).append("auto&& ").append(pCpp).append(" = ").append(recvCode).append(";\n")
                        bindLocalName(pName, pCpp)
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar = null)
                        if (targetVar != null) {
                            append(inner).append(targetVar).append(" = ").append(pCpp).append(";\n")
                        }
                    }
                    BuiltinInlineKind.RUN,
                    BuiltinInlineKind.WITH,
                    -> {
                        val recvIdx =
                            inlineExp.receiverExprFrameIndex ?: inlineExp.argExprFrameIndices.firstOrNull()
                        if (recvIdx != null) {
                            val recvTemp = freshTemp("kxs_recv")
                            append(inner).append("auto&& ").append(recvTemp).append(" = ")
                                .append(emitExpression(stream.frames[recvIdx])).append(";\n")
                            val nestedEmitter =
                                CppExprAndStmtEmitter(
                                    bundle = bundle,
                                    activeTypeParams = activeTypeParams,
                                    enclosingClass = enclosingClass,
                                    receiverSubstitution = recvTemp,
                                )
                            nestedEmitter.appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar)
                        } else {
                            appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar)
                        }
                    }
                    BuiltinInlineKind.APPLY -> {
                        val recvCode = inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "(*this)"
                        val recvTemp = freshTemp("kxs_apply")
                        append(inner).append("auto&& ").append(recvTemp).append(" = ").append(recvCode).append(";\n")
                        val nestedEmitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                activeTypeParams = activeTypeParams,
                                enclosingClass = enclosingClass,
                                receiverSubstitution = recvTemp,
                            )
                        nestedEmitter.appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar = null)
                        if (targetVar != null) {
                            append(inner).append(targetVar).append(" = ").append(recvTemp).append(";\n")
                        }
                    }
                    BuiltinInlineKind.USE -> {
                        val recvCode = inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "this"
                        val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val pCpp = CppNamingPolicy.toSnakeCase(pName)
                        val guardStruct = freshTemp("KxsCloseGuard")
                        val guardVar = freshTemp("kxs_close")
                        append(inner).append("auto&& ").append(pCpp).append(" = ").append(recvCode).append(";\n")
                        append(inner).append("struct ").append(guardStruct).append(" { decltype((").append(pCpp).append(")) ref; ~")
                            .append(guardStruct).append("() { ref.close(); } } ").append(guardVar).append('{').append(pCpp).append("};\n")
                        bindLocalName(pName, pCpp)
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar)
                    }
                    BuiltinInlineKind.WITH_LOCK -> {
                        includes.addSystem("mutex")
                        val recvCode = inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "mutex_"
                        append(inner).append("std::lock_guard kxs_lock(").append(recvCode).append(");\n")
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar)
                    }
                    BuiltinInlineKind.REPEAT -> {
                        val countIdx = inlineExp.argExprFrameIndices.firstOrNull()
                        val countCode = countIdx?.let { emitExpression(stream.frames[it]) } ?: "0"
                        val idxParam = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val idxCpp = CppNamingPolicy.toSnakeCase(idxParam)
                        val boundTemp = freshTemp("kxs_times")
                        append(inner).append("const auto ").append(boundTemp).append(" = ").append(countCode).append(";\n")
                        append(inner).append("for (std::int32_t ").append(idxCpp).append(" = 0; ").append(idxCpp)
                            .append(" < ").append(boundTemp).append("; ++").append(idxCpp).append(") {\n")
                        includes.addSystem("cstdint")
                        bindLocalName(idxParam, idxCpp)
                        appendInlineLambdaBodyStatements(this, lambdaBody, "$inner    ", targetVar = null)
                        append(inner).append("}\n")
                    }
                    BuiltinInlineKind.FOR_EACH -> {
                        val recvCode = inlineExp.receiverExprFrameIndex?.let { emitExpression(stream.frames[it]) } ?: "{}"
                        val pName = inlineExp.lambdaParamNames.firstOrNull() ?: "it"
                        val pCpp = CppNamingPolicy.toSnakeCase(pName)
                        append(inner).append("for (auto&& ").append(pCpp).append(" : ").append(recvCode).append(") {\n")
                        bindLocalName(pName, pCpp)
                        appendInlineLambdaBodyStatements(this, lambdaBody, "$inner    ", targetVar = null)
                        append(inner).append("}\n")
                    }
                    BuiltinInlineKind.SAME_FILE_INLINE_FUN -> {
                        // Expand same-file inline function body or inline lambda directly into caller frame
                        appendInlineLambdaBodyStatements(this, lambdaBody, inner, targetVar)
                    }
                }
                append(indent).append("}\n")
            }
        }
    }

    private fun appendInlineLambdaBodyStatements(
        out: StringBuilder,
        lambdaBody: PacketFrame,
        indent: String,
        targetVar: String?,
    ) {
        if (lambdaBody.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            val stmts = FrameAstHelpers.childFrames(stream, lambdaBody)
            for ((idx, s) in stmts.withIndex()) {
                emitHoistedPrefixesForStmt(out, s.frameIndex, indent)
                if (idx == stmts.lastIndex && targetVar != null) {
                    out.append(emitValueAssignmentOrReturn(s, indent, targetReturn = false, targetVar = targetVar))
                } else {
                    out.append(emitStatement(s, indent, inValuePosition = false, resultTargetVar = null))
                }
                emitDeadSpillResetsAfterStmt(out, s.frameIndex, indent)
            }
        } else {
            emitHoistedPrefixesForStmt(out, lambdaBody.frameIndex, indent)
            if (targetVar != null) {
                out.append(emitValueAssignmentOrReturn(lambdaBody, indent, targetReturn = false, targetVar = targetVar))
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
                append(inner).append("auto&& ").append(pinnedVar).append(" = ").append(targetExpr).append(";\n")
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
                append(inner).append("auto ").append(curCpp).append(" = ")
                    .append(atomicRef).append(".load(std::memory_order_seq_cst);\n")
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
                        append(inner).append("auto ").append(nextVar).append(" = ").append(curCpp).append(";\n")
                    }
                    append(inner).append("if (").append(atomicRef)
                        .append(".compare_exchange_weak(").append(curCpp).append(", ").append(nextVar)
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

            KtNodeTypes.THIS_EXPRESSION_ID -> receiverSubstitution ?: "(*this)"
            KtNodeTypes.SUPER_EXPRESSION_ID -> "Base"

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
                val typeName = lhs?.let { FrameAstHelpers.frameSourceSlice(stream, it).trim() } ?: "void"
                "typeid(${CppNamingPolicy.toTypeName(typeName)})"
            }
            else -> FrameAstHelpers.frameSourceSlice(stream, exprFrame).trim()
        }
    }

    private fun emitConstantLiteral(litFrame: PacketFrame): String {
        val pkt = stream.packets.getOrNull(litFrame.firstPacketIndex) as? SyntaxPacket.LiteralPacket
            ?: return FrameAstHelpers.frameSourceSlice(stream, litFrame).trim()
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
                        val rawPart = FrameAstHelpers.frameSourceSlice(stream, entry)
                        if (PacketFlags.hasFlag(entry.flags, PacketFlags.HAS_LONE_SURROGATE)) {
                            flushLiteral()
                            parts.add(encodeSurrogateSafeCppString(rawPart))
                        } else {
                            litBuffer.append(rawPart)
                        }
                    }
                    KtNodeTypes.ESCAPE_STRING_TEMPLATE_ENTRY_ID -> {
                        val rawEsc = FrameAstHelpers.frameSourceSlice(stream, entry)
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
        return "([&]() { const auto& kxs_v = $code; " +
            "if constexpr (std::is_convertible_v<decltype(kxs_v), std::string>) return std::string(kxs_v); " +
            "else return std::to_string(kxs_v); }())"
    }

    private fun isKnownStringSymbol(kotlinName: String): Boolean {
        if (enclosingClass != null) {
            val pMatch = enclosingClass.primaryConstructorParams.firstOrNull { it.name == kotlinName }
            if (pMatch?.typeFrameIndex != null) {
                val slice = FrameAstHelpers.frameSourceSlice(stream, stream.frames[pMatch.typeFrameIndex]).trim()
                if (slice == "String") return true
            }
        }
        val prop = bundle.symbols.propertiesByName[kotlinName]
        if (prop?.returnTypeFrameIndex != null) {
            val slice = FrameAstHelpers.frameSourceSlice(stream, stream.frames[prop.returnTypeFrameIndex]).trim()
            if (slice == "String") return true
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

    private fun emitReferenceExpr(refFrame: PacketFrame): String {
        val pkt = FrameAstHelpers.directIdentPacket(stream, refFrame)
            ?: return FrameAstHelpers.frameSourceSlice(stream, refFrame).trim()
        val ktName = pkt.unescapedName

        resolveBoundName(ktName)?.let { return it }

        // Check if `ktName` is a private field or property on `enclosingClass`
        if (enclosingClass != null) {
            val ctorProp = enclosingClass.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == ktName }
            if (ctorProp != null) {
                val vpFrame = stream.frames[ctorProp.frameIndex]
                return if (PacketFlags.hasFlag(vpFrame.flags, PacketFlags.MOD_PRIVATE)) {
                    CppNamingPolicy.toPrivateFieldName(ktName)
                } else {
                    ctorProp.cppName
                }
            }
            for (mPropIdx in enclosingClass.memberPropertyFrameIndices) {
                val mProp = bundle.symbols.propertiesByFrame[mPropIdx] ?: continue
                if (mProp.kotlinName == ktName) {
                    return mProp.cppName
                }
            }
        }

        if (ktName == "COROUTINE_SUSPENDED") {
            includes.addProject("kotlinx/coroutines/intrinsics/Intrinsics.hpp")
            return "intrinsics::COROUTINE_SUSPENDED"
        }

        return pkt.targetCppName ?: CppNamingPolicy.toSnakeCase(ktName)
    }

    private fun emitDotQualifiedExpr(dotFrame: PacketFrame): String {
        val parts = FrameAstHelpers.childFrames(stream, dotFrame)
        if (parts.size != 2) {
            return FrameAstHelpers.frameSourceSlice(stream, dotFrame).trim()
        }
        val recvFrame = parts[0]
        val selFrame = parts[1]

        // 1. Check `Result.success(v)` / `Result.failure(e)`
        val recvRaw = FrameAstHelpers.frameSourceSlice(stream, recvFrame).trim()
        if (recvRaw == "Result" && selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val fnName = extractCalleeSimpleName(selFrame)
            val args = extractCallArgExpressions(selFrame)
            includes.addProject("kotlinx/coroutines/Result.hpp")
            if (fnName == "success") return "Result::success(${args.joinToString(", ")})"
            if (fnName == "failure") return "Result::failure(${args.joinToString(", ")})"
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
        val isStaticTypeReceiver =
            recvFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID &&
                FrameAstHelpers.directIdentPacket(stream, recvFrame)?.let { pkt ->
                    pkt.role == SymbolRole.TYPE ||
                        (pkt.unescapedName.firstOrNull()?.isUpperCase() == true && resolveBoundName(pkt.unescapedName) == null)
                } == true

        val recvCode = emitExpression(recvFrame)
        val isPtrReceiver = isPointerOrSharedPtrReceiver(recvFrame, recvCode)

        if (selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            if (isStaticTypeReceiver) {
                val fnName = extractCalleeSimpleName(selFrame)
                val args = extractCallArgExpressions(selFrame)
                return "$recvCode::${CppNamingPolicy.toSnakeCase(fnName)}(${args.joinToString(", ")})"
            }
            return emitCallExpr(receiverCode = recvCode, receiverIsPointer = isPtrReceiver, callFrame = selFrame)
        }

        val memberKt =
            FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName
                ?: FrameAstHelpers.frameSourceSlice(stream, selFrame).trim()
        if (isStaticTypeReceiver) {
            val sameCls = bundle.symbols.classesByName[recvRaw]
            val isEnumOrConst =
                sameCls?.enumEntries?.any { it.kotlinName == memberKt } == true ||
                    (memberKt.length > 1 && memberKt.all { it.isUpperCase() || it.isDigit() || it == '_' })
            val memberCpp = if (isEnumOrConst) CppNamingPolicy.toConstName(memberKt) else CppNamingPolicy.toSnakeCase(memberKt)
            return "$recvCode::$memberCpp"
        }

        // Standard Kotlin stdlib property mappings (`.length` / `.size` -> `.size()`, `.isEmpty` -> `.empty()`)
        when (memberKt) {
            "size", "length" -> {
                // Only map `.length` cleanly to `.size()` unless the current class declares a field named `size`/`length`
                val hasOwnField =
                    enclosingClass?.primaryConstructorParams?.any { it.name == memberKt } == true ||
                        enclosingClass?.memberPropertyFrameIndices?.any {
                            bundle.symbols.propertiesByFrame[it]?.kotlinName == memberKt
                        } == true
                if (!hasOwnField) {
                    val op = if (isPtrReceiver) "->" else "."
                    return "$recvCode${op}size()"
                }
            }
        }

        // Check if `memberKt` is an extension property or rich-enum property declared in the same file!
        val sameFileProp = bundle.symbols.propertiesByName[memberKt]
        if (sameFileProp != null && sameFileProp.receiverTypeFrameIndex != null) {
            return "${sameFileProp.cppName}($recvCode)"
        }

        val op = if (isPtrReceiver) "->" else "."
        if (recvFrame.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID && receiverSubstitution == null) {
            return "this->${resolveMemberFieldOrPropName(memberKt)}"
        }
        return "$recvCode$op${resolveMemberFieldOrPropName(memberKt)}"
    }

    private fun resolveMemberFieldOrPropName(memberKt: String): String {
        if (enclosingClass != null) {
            val ctorProp = enclosingClass.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == memberKt }
            if (ctorProp != null) {
                val vpFrame = stream.frames[ctorProp.frameIndex]
                return if (PacketFlags.hasFlag(vpFrame.flags, PacketFlags.MOD_PRIVATE)) {
                    CppNamingPolicy.toPrivateFieldName(memberKt)
                } else {
                    ctorProp.cppName
                }
            }
            for (pIdx in enclosingClass.memberPropertyFrameIndices) {
                val p = bundle.symbols.propertiesByFrame[pIdx] ?: continue
                if (p.kotlinName == memberKt) return p.cppName
            }
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
            if (enclosingClass != null) {
                val cp = enclosingClass.primaryConstructorParams.firstOrNull { it.name == name }
                if (cp?.typeFrameIndex != null) {
                    val cppT = bundle.typeTranslator.translateTypeFrame(stream.frames[cp.typeFrameIndex], CppTypePosition.VALUE)
                    if (cppT.endsWith("*") || cppT.startsWith("std::shared_ptr<")) return true
                }
            }
        }
        return false
    }

    private fun emitSafeAccessExpr(safeFrame: PacketFrame): String {
        val parts = FrameAstHelpers.childFrames(stream, safeFrame)
        if (parts.size != 2) return FrameAstHelpers.frameSourceSlice(stream, safeFrame).trim()
        val lhsCode = emitExpression(parts[0])
        val temp = bundle.cfPlan.singleEvalTempNamesByFrame[safeFrame.frameIndex]
        val selFrame = parts[1]
        val memberAccess =
            if (selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                val fn = CppNamingPolicy.toSnakeCase(extractCalleeSimpleName(selFrame))
                val args = extractCallArgExpressions(selFrame)
                "$fn(${args.joinToString(", ")})"
            } else {
                val prop = FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName ?: "value"
                CppNamingPolicy.toSnakeCase(prop)
            }
        return if (temp != null) {
            "([&]() { auto&& $temp = $lhsCode; return $temp ? std::make_optional($temp->$memberAccess) : std::nullopt; }())"
        } else {
            "($lhsCode ? ($lhsCode->$memberAccess) : nullptr)"
        }
    }

    private fun emitCallExpr(
        receiverCode: String?,
        receiverIsPointer: Boolean,
        callFrame: PacketFrame,
    ): String {
        val calleeKt = extractCalleeSimpleName(callFrame)
        val typeArgsFrame = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
        val typeArgs =
            typeArgsFrame?.let { ta ->
                FrameAstHelpers.childFramesOfType(stream, ta, KtNodeTypes.TYPE_PROJECTION_ID)
                    .map { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) }
            }.orEmpty()
        val args = extractCallArgExpressions(callFrame)

        // Builtin Kotlin standard functions / pre-conditions / collections
        if (receiverCode == null) {
            when (calleeKt) {
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
                "createCleaner" -> {
                    includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                    return "create_cleaner(${args.joinToString(", ")})"
                }
            }
        } else {
            // Standard member method mappings
            val op = if (receiverIsPointer) "->" else "."
            when (calleeKt) {
                "isEmpty" -> return "$receiverCode${op}empty()"
                "isNotEmpty" -> return "(!$receiverCode${op}empty())"
                "add" -> return "$receiverCode${op}push_back(${args.joinToString(", ")})"
                "toString" -> return "$receiverCode${op}to_string()"
                "getOrThrow" -> return "$receiverCode${op}get_or_throw()"
                "exceptionOrNull" -> return "$receiverCode${op}exception_or_null()"
            }

            // Check if calling an extension function declared in the same file (`fun Recv.ext(...)`)!
            val sameFileFns = bundle.symbols.functionsByName[calleeKt].orEmpty()
            val extFn = sameFileFns.firstOrNull { it.receiverTypeFrameIndex != null }
            if (extFn != null) {
                val allArgs = ArrayList<String>(args.size + 1)
                allArgs.add(receiverCode)
                allArgs.addAll(args)
                val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                return "${extFn.cppName}$tSuffix(${allArgs.joinToString(", ")})"
            }
        }

        // Constructor invocation vs function/method invocation
        val sameFileClass = bundle.symbols.classesByName[calleeKt]
        val isCtorCall =
            receiverCode == null &&
                (sameFileClass != null || (calleeKt.firstOrNull()?.isUpperCase() == true && calleeKt !in bundle.symbols.functionsByName))
        val targetName =
            when {
                isCtorCall -> sameFileClass?.cppName ?: CppNamingPolicy.toTypeName(calleeKt)
                receiverCode == null -> {
                    bundle.symbols.functionsByName[calleeKt]?.firstOrNull()?.cppName
                        ?: CppNamingPolicy.toSnakeCase(calleeKt)
                }
                else -> CppNamingPolicy.toSnakeCase(calleeKt)
            }

        val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
        return if (receiverCode == null) {
            "$targetName$tSuffix(${args.joinToString(", ")})"
        } else {
            val op = if (receiverIsPointer) "->" else "."
            "$receiverCode$op$targetName$tSuffix(${args.joinToString(", ")})"
        }
    }

    private fun extractCallArgExpressions(callFrame: PacketFrame): List<String> {
        val out = ArrayList<String>()
        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
        if (argList != null) {
            for (va in FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID)) {
                val expr =
                    FrameAstHelpers.childFrames(stream, va)
                        .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
                if (expr != null) {
                    out.add(emitExpression(expr))
                }
            }
        }
        for (tl in FrameAstHelpers.childFramesOfType(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID)) {
            val lambda = FrameAstHelpers.firstChildFrame(stream, tl, KtNodeTypes.LAMBDA_EXPRESSION_ID)
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
        // C++23 supports multidimensional subscript `arr[i, j]` directly!
        return "$arrCode[${idxExprs.joinToString(", ")}]"
    }

    private fun emitBinaryExpr(binFrame: PacketFrame): String {
        val ch = FrameAstHelpers.childFrames(stream, binFrame)
        if (ch.size < 3) return FrameAstHelpers.frameSourceSlice(stream, binFrame).trim()
        val lhsFrame = ch[0]
        val opText = FrameAstHelpers.frameSourceSlice(stream, ch[1]).trim()
        val rhsFrame = ch[2]

        val lhs = emitExpression(lhsFrame)
        val rhs = emitExpression(rhsFrame)

        return when (opText) {
            "===" -> {
                includes.addSystem("memory")
                "(std::addressof($lhs) == std::addressof($rhs))"
            }
            "!==" -> {
                includes.addSystem("memory")
                "(std::addressof($lhs) != std::addressof($rhs))"
            }
            "?:" -> {
                val temp = bundle.cfPlan.singleEvalTempNamesByFrame[binFrame.frameIndex]
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
        val opText = opRef?.let { FrameAstHelpers.frameSourceSlice(stream, it).trim() } ?: "as"
        val lhsCode = lhs?.let { emitExpression(it) } ?: "nullptr"
        val targetCpp = tr?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.VALUE) } ?: "void*"
        val cleanType = targetCpp.removeSuffix("&")

        return if (opText == "as?") {
            val rawTarget = cleanType.removeSuffix("*")
            includes.addSystem("memory")
            "dynamic_cast<$rawTarget*>($lhsCode)"
        } else {
            if (cleanType.endsWith("*")) {
                "static_cast<$cleanType>($lhsCode)"
            } else {
                "static_cast<$cleanType>($lhsCode)"
            }
        }
    }

    private fun emitIsExpr(isFrame: PacketFrame): String {
        val lhs = FrameAstHelpers.childFrames(stream, isFrame).firstOrNull()
        val opRef = FrameAstHelpers.firstChildFrame(stream, isFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val tr = FrameAstHelpers.firstChildFrame(stream, isFrame, KtNodeTypes.TYPE_REFERENCE_ID)
        val negated = opRef?.let { FrameAstHelpers.frameSourceSlice(stream, it).trim() } == "!is"
        val lhsCode = lhs?.let { emitExpression(it) } ?: "this"
        val cppType =
            tr?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) }
                ?.removeSuffix("&")
                ?.removeSuffix("*")
                ?: "void"
        includes.addSystem("memory")
        val check = "dynamic_cast<const $cppType*>(std::addressof($lhsCode)) != nullptr"
        return if (negated) "(!$check)" else "($check)"
    }

    private fun emitPrefixExpr(prefixFrame: PacketFrame): String {
        val opRef = FrameAstHelpers.firstChildFrame(stream, prefixFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val operand =
            FrameAstHelpers.childFrames(stream, prefixFrame)
                .lastOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
        val op = opRef?.let { FrameAstHelpers.frameSourceSlice(stream, it).trim() }.orEmpty()
        val inner = operand?.let { emitExpression(it) }.orEmpty()
        return "$op$inner"
    }

    private fun emitPostfixExpr(postfixFrame: PacketFrame): String {
        val operand =
            FrameAstHelpers.childFrames(stream, postfixFrame)
                .firstOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
        val opRef = FrameAstHelpers.firstChildFrame(stream, postfixFrame, KtNodeTypes.OPERATION_REFERENCE_ID)
        val op = opRef?.let { FrameAstHelpers.frameSourceSlice(stream, it).trim() }.orEmpty()
        val inner = operand?.let { emitExpression(it) }.orEmpty()
        return if (op == "!!") {
            includes.addSystem("stdexcept")
            includes.addSystem("type_traits")
            "([&]() -> decltype(auto) { auto&& kxs_v = $inner; if (!kxs_v) throw std::runtime_error(\"NullPointerException\"); " +
                "if constexpr (std::is_pointer_v<std::decay_t<decltype(kxs_v)>>) return kxs_v; else return *kxs_v; }())"
        } else {
            "$inner$op"
        }
    }

    private fun emitExpressionIf(ifFrame: PacketFrame): String {
        val condWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.CONDITION_ID)
        val thenWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.THEN_ID)
        val elseWrap = FrameAstHelpers.firstChildFrame(stream, ifFrame, KtNodeTypes.ELSE_ID)
        val cond = condWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val thenChild = thenWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }
        val elseChild = elseWrap?.let { FrameAstHelpers.childFrames(stream, it).firstOrNull() }

        if (cond != null && thenChild != null && elseChild != null &&
            thenChild.nodeTypeId != KtNodeTypes.BLOCK_ID &&
            elseChild.nodeTypeId != KtNodeTypes.BLOCK_ID &&
            bundle.cfPlan.smartCastsByScopeFrame[thenWrap.frameIndex].isNullOrEmpty() &&
            bundle.cfPlan.smartCastsByScopeFrame[elseWrap.frameIndex].isNullOrEmpty()
        ) {
            return "(${emitExpression(cond)} ? ${emitExpression(thenChild)} : ${emitExpression(elseChild)})"
        }
        return "[&]() {\n${emitIfStatement(ifFrame, "    ", inValuePosition = true, targetReturn = true, targetVar = null)}}()"
    }

    private fun emitLambdaClosureExpr(lambdaFrame: PacketFrame): String {
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
        val pList = fnLit?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.VALUE_PARAMETER_LIST_ID) }
        val params = FrameAstHelpers.extractValueParams(stream, pList)
        val bodyBlock = fnLit?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.BLOCK_ID) }

        return withLexicalScope {
            val paramDecls =
                if (params.isNotEmpty()) {
                    params.map { vp ->
                        bindLocalName(vp.name, vp.cppName)
                        val t =
                            vp.typeFrameIndex
                                ?.let { bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER) }
                                ?: "auto&&"
                        "$t ${vp.cppName}"
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
                    if (stmts.size == 1 && stmts[0].nodeTypeId !in STATEMENT_ONLY_NODE_TYPES) {
                        "{ return ${emitExpression(stmts[0])}; }"
                    } else {
                        emitBlock(bodyBlock, "", isValueBlock = false, resultTargetVar = null)
                    }
                } else {
                    "{}"
                }
            "[&](${paramDecls.joinToString(", ")}) $bodyCode"
        }
    }

    private fun emitCallableRefExpr(refExpr: PacketFrame): String {
        val ch = FrameAstHelpers.childFrames(stream, refExpr)
        return if (ch.size >= 2) {
            val lhs = FrameAstHelpers.frameSourceSlice(stream, ch[0]).trim()
            val rhs =
                FrameAstHelpers.directIdentPacket(stream, ch[1])?.unescapedName
                    ?: FrameAstHelpers.frameSourceSlice(stream, ch[1]).trim()
            "&${CppNamingPolicy.toTypeName(lhs)}::${CppNamingPolicy.toSnakeCase(rhs)}"
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
                        "if ($target.compare_exchange_weak($curCpp, kxs_next, std::memory_order_seq_cst, std::memory_order_relaxed)) " +
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
                resolveBoundName(aop.targetFieldInfo.kotlinName) ?: aop.targetFieldInfo.cppName
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
                FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.THEN_ID)
                    ?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                    ?.let { collectBranchValueLeaves(it, out) }
                FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.ELSE_ID)
                    ?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                    ?.let { collectBranchValueLeaves(it, out) }
            }
            KtNodeTypes.WHEN_ID -> {
                for (e in FrameAstHelpers.childFramesOfType(stream, frame, KtNodeTypes.WHEN_ENTRY_ID)) {
                    FrameAstHelpers.childFrames(stream, e).lastOrNull()?.let { collectBranchValueLeaves(it, out) }
                }
            }
            KtNodeTypes.TRY_ID -> {
                FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.BLOCK_ID)
                    ?.let { collectBranchValueLeaves(it, out) }
                for (c in FrameAstHelpers.childFramesOfType(stream, frame, KtNodeTypes.CATCH_ID)) {
                    FrameAstHelpers.firstChildFrame(stream, c, KtNodeTypes.BLOCK_ID)
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

        private val STATEMENT_ONLY_NODE_TYPES: Set<Int> =
            setOf(
                KtNodeTypes.PROPERTY_ID,
                KtNodeTypes.RETURN_ID,
                KtNodeTypes.THROW_ID,
                KtNodeTypes.FOR_ID,
                KtNodeTypes.WHILE_ID,
                KtNodeTypes.DO_WHILE_ID,
            )
    }
}
