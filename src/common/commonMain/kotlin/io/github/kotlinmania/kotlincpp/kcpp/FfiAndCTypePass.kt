package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.FrameSemanticInfo
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream

internal data class MemScopedBlockInfo(
    val callFrameIndex: Int,
    val arenaParamName: String?,
    val bodyFrameIndex: Int,
)

internal data class UsePinnedBlockInfo(
    val callFrameIndex: Int,
    val targetExprFrameIndex: Int,
    val pinnedParamName: String,
    val bodyFrameIndex: Int,
)

internal enum class CInteropOpKind {
    POINTED,
    PTR,
    CSTR,
    REINTERPRET,
    TO_CPOINTER,
    TO_LONG_FROM_PTR,
    ADDRESS_OF,
    ALLOC_SCALAR,
    ALLOC_ARRAY,
}

internal data class CInteropExprSite(
    val exprFrameIndex: Int,
    val kind: CInteropOpKind,
    val receiverExprFrameIndex: Int?,
    val targetTypeFrameIndex: Int?,
    val argExprFrameIndex: Int?,
)

internal class FfiAndCTypePlan(
    val preambleInlineCppBlocks: List<String>,
    val inlineCppByFrame: Map<Int, String>,
    val memScopedBlocksByFrame: Map<Int, MemScopedBlockInfo>,
    val usePinnedBlocksByFrame: Map<Int, UsePinnedBlockInfo>,
    val cInteropOpsByExprFrame: Map<Int, CInteropExprSite>,
    val cAbiExportFunctionFrames: List<Int>,
)

/**
 * Pass 3b: Verbatim Inline-C/C++ pass-through (`@InlineC`/`@InlineCpp`, `inlineC(...)`/`cpp(...)`),
 * `kotlinx.cinterop` pointer/arena/pinning lowering (`memScoped`, `alloc`, `usePinned`, `.pointed`,
 * `.ptr`, `.reinterpret<T>()`, `.addressOf(i)`, `.cstr`), `@CName` `extern "C"` C-ABI tracking,
 * and hard rejection of `usePinned` across any coroutine suspension point.
 */
internal object FfiAndCTypePass {
    fun run(
        stream: PacketStream,
        symbols: SameFileSymbolTable,
        suspendPlan: SuspendLoweringPlan,
    ): FfiAndCTypePlan {
        val preambleBlocks = ArrayList<String>()
        val inlineCppByFrame = LinkedHashMap<Int, String>()
        val memScopedBlocks = LinkedHashMap<Int, MemScopedBlockInfo>()
        val usePinnedBlocks = LinkedHashMap<Int, UsePinnedBlockInfo>()
        val cInteropOps = LinkedHashMap<Int, CInteropExprSite>()
        val cAbiFuns = ArrayList<Int>()

        // 1. Inspect `@file:InlineC(...)` / `@file:InlineCpp(...)` annotations on `KT_FILE`.
        val fileAnnotList = FrameAstHelpers.firstChildFrame(stream, stream.rootFrame, KtNodeTypes.FILE_ANNOTATION_LIST_ID)
        if (fileAnnotList != null) {
            for (entry in FrameAstHelpers.childFramesOfType(stream, fileAnnotList, KtNodeTypes.ANNOTATION_ENTRY_ID)) {
                extractAnnotationInlineCode(stream, entry)?.let { code ->
                    entry.flags = entry.flags or PacketFlags.INLINE_C_PASSTHROUGH
                    preambleBlocks.add(code)
                }
            }
        }

        // 2. Inspect declaration-level `@InlineC`/`@InlineCpp` and `@CName` on functions & classes.
        for (cls in symbols.classesByFrame.values) {
            for (ann in cls.annotations) {
                if (ann.simpleName in INLINE_CODE_ANNOTATIONS && ann.stringArguments.isNotEmpty()) {
                    val code = ann.stringArguments.joinToString("\n")
                    val clsFrame = stream.frames[cls.frameIndex]
                    clsFrame.flags = clsFrame.flags or PacketFlags.INLINE_C_PASSTHROUGH
                    preambleBlocks.add(code)
                }
            }
        }

        for (fn in symbols.functionsByFrame.values) {
            val fnFrame = stream.frames[fn.frameIndex]
            if (fn.cAbiSymbolName != null) {
                cAbiFuns.add(fn.frameIndex)
            }
            val inlineAnn = fn.annotations.firstOrNull { it.simpleName in INLINE_CODE_ANNOTATIONS }
            if (inlineAnn != null && inlineAnn.stringArguments.isNotEmpty()) {
                val code = inlineAnn.stringArguments.joinToString("\n")
                var newFlags = fnFrame.flags or PacketFlags.INLINE_C_PASSTHROUGH
                if (fn.bodyFrameIndex == null) {
                    newFlags =
                        if (PacketFlags.hasFlag(newFlags, PacketFlags.MOD_INLINE) || fn.typeParams.isNotEmpty()) {
                            newFlags or PacketFlags.EMIT_IN_HPP_INLINE_BODY
                        } else {
                            newFlags or PacketFlags.EMIT_IN_CPP_BODY
                        }
                }
                fnFrame.flags = newFlags
                fnFrame.semanticInfo = (fnFrame.semanticInfo ?: FrameSemanticInfo()).copy(inlineCppBody = code)
                inlineCppByFrame[fn.frameIndex] = code
            }
        }

        // 3. Walk expression trees for `inlineC(...)`, `memScoped`, `usePinned`, and `cinterop` ops.
        scanExpressionsRecursive(
            stream = stream,
            frame = stream.rootFrame,
            suspendCallFrames = suspendPlan.suspendCallFrameIndices,
            activePinnedParamNames = emptySet(),
            activeArenaParamNames = emptySet(),
            inlineCppByFrame = inlineCppByFrame,
            memScopedBlocks = memScopedBlocks,
            usePinnedBlocks = usePinnedBlocks,
            cInteropOps = cInteropOps,
        )

        return FfiAndCTypePlan(
            preambleInlineCppBlocks = preambleBlocks,
            inlineCppByFrame = inlineCppByFrame,
            memScopedBlocksByFrame = memScopedBlocks,
            usePinnedBlocksByFrame = usePinnedBlocks,
            cInteropOpsByExprFrame = cInteropOps,
            cAbiExportFunctionFrames = cAbiFuns,
        )
    }

    private fun scanExpressionsRecursive(
        stream: PacketStream,
        frame: PacketFrame,
        suspendCallFrames: Set<Int>,
        activePinnedParamNames: Set<String>,
        activeArenaParamNames: Set<String>,
        inlineCppByFrame: MutableMap<Int, String>,
        memScopedBlocks: MutableMap<Int, MemScopedBlockInfo>,
        usePinnedBlocks: MutableMap<Int, UsePinnedBlockInfo>,
        cInteropOps: MutableMap<Int, CInteropExprSite>,
    ) {
        when (frame.nodeTypeId) {
            KtNodeTypes.CALL_EXPRESSION_ID -> {
                val calleeRef = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                val callee = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()

                if (callee in INLINE_CODE_CALL_NAMES) {
                    extractCallStringLiteral(stream, frame)?.let { code ->
                        frame.flags = frame.flags or PacketFlags.INLINE_C_PASSTHROUGH
                        frame.semanticInfo = (frame.semanticInfo ?: FrameSemanticInfo()).copy(inlineCppBody = code)
                        inlineCppByFrame[frame.frameIndex] = code
                        return
                    }
                }

                if (callee == "memScoped") {
                    val lambda = extractCallLambda(stream, frame)
                    if (lambda != null) {
                        frame.flags = frame.flags or PacketFlags.FFI_MEM_SCOPED_ARENA
                        val paramName = extractSingleLambdaParam(stream, lambda)
                        val body = extractLambdaBody(stream, lambda)
                        memScopedBlocks[frame.frameIndex] =
                            MemScopedBlockInfo(
                                callFrameIndex = frame.frameIndex,
                                arenaParamName = paramName,
                                bodyFrameIndex = body.frameIndex,
                            )
                        val nextArenas =
                            if (paramName != null) activeArenaParamNames + paramName else activeArenaParamNames + "this"
                        scanExpressionsRecursive(
                            stream = stream,
                            frame = body,
                            suspendCallFrames = suspendCallFrames,
                            activePinnedParamNames = activePinnedParamNames,
                            activeArenaParamNames = nextArenas,
                            inlineCppByFrame = inlineCppByFrame,
                            memScopedBlocks = memScopedBlocks,
                            usePinnedBlocks = usePinnedBlocks,
                            cInteropOps = cInteropOps,
                        )
                        return
                    }
                }

                if (callee == "alloc" || callee == "allocArray") {
                    val targetType = extractFirstTypeArg(stream, frame)
                    val firstArg = extractFirstValueArgExpr(stream, frame)
                    cInteropOps[frame.frameIndex] =
                        CInteropExprSite(
                            exprFrameIndex = frame.frameIndex,
                            kind = if (callee == "alloc") CInteropOpKind.ALLOC_SCALAR else CInteropOpKind.ALLOC_ARRAY,
                            receiverExprFrameIndex = null,
                            targetTypeFrameIndex = targetType?.frameIndex,
                            argExprFrameIndex = firstArg?.frameIndex,
                        )
                }
            }

            KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
                val parts = FrameAstHelpers.childFrames(stream, frame)
                if (parts.size == 2) {
                    val recv = parts[0]
                    val sel = parts[1]

                    if (sel.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                        val calleeRef = FrameAstHelpers.firstChildFrame(stream, sel, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                        val method = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()

                        if (method == "usePinned") {
                            val lambda = extractCallLambda(stream, sel)
                            if (lambda != null) {
                                if (PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE) ||
                                    PacketFlags.hasFlag(sel.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE) ||
                                    containsSuspendCallFrame(stream, lambda, suspendCallFrames)
                                ) {
                                    val loc = "${stream.sourcePath}:${frame.span.startLine}:${frame.span.startColumn}"
                                    throw IllegalArgumentException(
                                        "$loc: usePinned cannot span a suspension point " +
                                            "(physical memory pin cannot survive coroutine suspension)",
                                    )
                                }
                                frame.flags = frame.flags or PacketFlags.FFI_PINNED_SCOPE
                                val pinnedParam = extractSingleLambdaParam(stream, lambda) ?: "it"
                                val body = extractLambdaBody(stream, lambda)
                                usePinnedBlocks[frame.frameIndex] =
                                    UsePinnedBlockInfo(
                                        callFrameIndex = frame.frameIndex,
                                        targetExprFrameIndex = recv.frameIndex,
                                        pinnedParamName = pinnedParam,
                                        bodyFrameIndex = body.frameIndex,
                                    )
                                scanExpressionsRecursive(
                                    stream = stream,
                                    frame = recv,
                                    suspendCallFrames = suspendCallFrames,
                                    activePinnedParamNames = activePinnedParamNames,
                                    activeArenaParamNames = activeArenaParamNames,
                                    inlineCppByFrame = inlineCppByFrame,
                                    memScopedBlocks = memScopedBlocks,
                                    usePinnedBlocks = usePinnedBlocks,
                                    cInteropOps = cInteropOps,
                                )
                                scanExpressionsRecursive(
                                    stream = stream,
                                    frame = body,
                                    suspendCallFrames = suspendCallFrames,
                                    activePinnedParamNames = activePinnedParamNames + pinnedParam,
                                    activeArenaParamNames = activeArenaParamNames,
                                    inlineCppByFrame = inlineCppByFrame,
                                    memScopedBlocks = memScopedBlocks,
                                    usePinnedBlocks = usePinnedBlocks,
                                    cInteropOps = cInteropOps,
                                )
                                return
                            }
                        }

                        when (method) {
                            "reinterpret" -> {
                                cInteropOps[frame.frameIndex] =
                                    CInteropExprSite(
                                        exprFrameIndex = frame.frameIndex,
                                        kind = CInteropOpKind.REINTERPRET,
                                        receiverExprFrameIndex = recv.frameIndex,
                                        targetTypeFrameIndex = extractFirstTypeArg(stream, sel)?.frameIndex,
                                        argExprFrameIndex = null,
                                    )
                            }
                            "toCPointer" -> {
                                cInteropOps[frame.frameIndex] =
                                    CInteropExprSite(
                                        exprFrameIndex = frame.frameIndex,
                                        kind = CInteropOpKind.TO_CPOINTER,
                                        receiverExprFrameIndex = recv.frameIndex,
                                        targetTypeFrameIndex = extractFirstTypeArg(stream, sel)?.frameIndex,
                                        argExprFrameIndex = null,
                                    )
                            }
                            "addressOf" -> {
                                val recvName =
                                    if (recv.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                                        FrameAstHelpers.directIdentPacket(stream, recv)?.unescapedName
                                    } else {
                                        null
                                    }
                                if (recvName == null || recvName in activePinnedParamNames || activePinnedParamNames.isNotEmpty()) {
                                    cInteropOps[frame.frameIndex] =
                                        CInteropExprSite(
                                            exprFrameIndex = frame.frameIndex,
                                            kind = CInteropOpKind.ADDRESS_OF,
                                            receiverExprFrameIndex = recv.frameIndex,
                                            targetTypeFrameIndex = null,
                                            argExprFrameIndex = extractFirstValueArgExpr(stream, sel)?.frameIndex,
                                        )
                                }
                            }
                            "alloc",
                            "allocArray",
                            -> {
                                val recvName =
                                    if (recv.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                                        FrameAstHelpers.directIdentPacket(stream, recv)?.unescapedName
                                    } else {
                                        null
                                    }
                                if (recv.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID || recvName in activeArenaParamNames) {
                                    cInteropOps[frame.frameIndex] =
                                        CInteropExprSite(
                                            exprFrameIndex = frame.frameIndex,
                                            kind = if (method == "alloc") CInteropOpKind.ALLOC_SCALAR else CInteropOpKind.ALLOC_ARRAY,
                                            receiverExprFrameIndex = recv.frameIndex,
                                            targetTypeFrameIndex = extractFirstTypeArg(stream, sel)?.frameIndex,
                                            argExprFrameIndex = extractFirstValueArgExpr(stream, sel)?.frameIndex,
                                        )
                                }
                            }
                        }
                    } else if (sel.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                        val prop = FrameAstHelpers.directIdentPacket(stream, sel)?.unescapedName
                        when (prop) {
                            "pointed" ->
                                cInteropOps[frame.frameIndex] =
                                    CInteropExprSite(frame.frameIndex, CInteropOpKind.POINTED, recv.frameIndex, null, null)
                            "ptr" ->
                                cInteropOps[frame.frameIndex] =
                                    CInteropExprSite(frame.frameIndex, CInteropOpKind.PTR, recv.frameIndex, null, null)
                            "cstr" ->
                                cInteropOps[frame.frameIndex] =
                                    CInteropExprSite(frame.frameIndex, CInteropOpKind.CSTR, recv.frameIndex, null, null)
                        }
                    }
                }
            }
        }

        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            scanExpressionsRecursive(
                stream = stream,
                frame = child,
                suspendCallFrames = suspendCallFrames,
                activePinnedParamNames = activePinnedParamNames,
                activeArenaParamNames = activeArenaParamNames,
                inlineCppByFrame = inlineCppByFrame,
                memScopedBlocks = memScopedBlocks,
                usePinnedBlocks = usePinnedBlocks,
                cInteropOps = cInteropOps,
            )
        }
    }

    private fun containsSuspendCallFrame(
        stream: PacketStream,
        root: PacketFrame,
        suspendCallFrames: Set<Int>,
    ): Boolean {
        if (root.frameIndex in suspendCallFrames ||
            PacketFlags.hasFlag(root.flags, PacketFlags.SUSPEND_CALL_SITE)
        ) {
            return true
        }
        for (child in FrameAstHelpers.childFrames(stream, root)) {
            if (containsSuspendCallFrame(stream, child, suspendCallFrames)) return true
        }
        return false
    }

    private fun extractAnnotationInlineCode(
        stream: PacketStream,
        entryFrame: PacketFrame,
    ): String? {
        val callee = FrameAstHelpers.firstChildFrame(stream, entryFrame, KtNodeTypes.CONSTRUCTOR_CALLEE_ID)
        val name =
            if (callee != null) {
                FrameAstHelpers.dottedNameText(stream, callee).substringAfterLast('.')
            } else {
                FrameAstHelpers.directIdentPacket(stream, entryFrame)?.unescapedName.orEmpty()
            }
        if (name !in INLINE_CODE_ANNOTATIONS) return null
        val strFrame = FrameAstHelpers.findDescendantFrame(stream, entryFrame, KtNodeTypes.STRING_TEMPLATE_ID) ?: return null
        return FrameAstHelpers.extractConstantStringText(stream, strFrame)
    }

    private fun extractCallStringLiteral(
        stream: PacketStream,
        callFrame: PacketFrame,
    ): String? {
        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID) ?: return null
        val firstArg = FrameAstHelpers.firstChildFrame(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID) ?: return null
        val strFrame = FrameAstHelpers.findDescendantFrame(stream, firstArg, KtNodeTypes.STRING_TEMPLATE_ID) ?: return null
        return FrameAstHelpers.extractConstantStringText(stream, strFrame)
    }

    private fun extractCallLambda(
        stream: PacketStream,
        callFrame: PacketFrame,
    ): PacketFrame? {
        val trailing =
            FrameAstHelpers
                .firstChildFrame(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID)
                ?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.LAMBDA_EXPRESSION_ID) }
        if (trailing != null) return trailing
        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID) ?: return null
        val lastVa = FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID).lastOrNull() ?: return null
        return FrameAstHelpers.firstChildFrame(stream, lastVa, KtNodeTypes.LAMBDA_EXPRESSION_ID)
    }

    private fun extractLambdaBody(
        stream: PacketStream,
        lambdaFrame: PacketFrame,
    ): PacketFrame {
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID) ?: return lambdaFrame
        return FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
    }

    private fun extractSingleLambdaParam(
        stream: PacketStream,
        lambdaFrame: PacketFrame,
    ): String? {
        val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID) ?: return null
        val pList = FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.VALUE_PARAMETER_LIST_ID) ?: return null
        val vp = FrameAstHelpers.firstChildFrame(stream, pList, KtNodeTypes.VALUE_PARAMETER_ID) ?: return null
        return FrameAstHelpers.directIdentPacket(stream, vp)?.unescapedName
    }

    private fun extractFirstTypeArg(
        stream: PacketStream,
        callFrame: PacketFrame,
    ): PacketFrame? {
        val taList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID) ?: return null
        val proj = FrameAstHelpers.firstChildFrame(stream, taList, KtNodeTypes.TYPE_PROJECTION_ID) ?: return null
        return FrameAstHelpers.firstChildFrame(stream, proj, KtNodeTypes.TYPE_REFERENCE_ID)
    }

    private fun extractFirstValueArgExpr(
        stream: PacketStream,
        callFrame: PacketFrame,
    ): PacketFrame? {
        val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID) ?: return null
        val va = FrameAstHelpers.firstChildFrame(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID) ?: return null
        for (child in FrameAstHelpers.childFrames(stream, va)) {
            if (child.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID) return child
        }
        return null
    }

    private val INLINE_CODE_ANNOTATIONS: Set<String> =
        setOf("InlineC", "InlineCpp")

    private val INLINE_CODE_CALL_NAMES: Set<String> =
        setOf("inlineC", "inlineCpp", "c", "cpp")
}
