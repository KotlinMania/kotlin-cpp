package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

/**
 * Target memory-management and runtime interop mode for C++23 emission.
 *
 * - [NONE]: Standalone RAII C++23 (`std::unique_ptr`, `std::shared_ptr`, `std::atomic`)
 *   with zero dependency on Kotlin/JVM/Native runtimes.
 * - [KONAN_GC]: Explicit Kotlin/Native Docking-Ring GC bridge (`ObjHolder` for synchronous
 *   stack roots, `OwningExternalRCRef` across suspension points, `UpdateHeapRef` /
 *   `CompareAndSetVolatileHeapRef` barriers, and `CalledFromNativeGuard` on foreign threads).
 */
public enum class CppGcMode {
    NONE,
    KONAN_GC,
}

/**
 * Deterministic configuration for transliterating Kotlin source into C++23 `.hpp`/`.cpp`.
 */
public data class CppTargetConfig(
    public val cppStandard: Int = 23,
    public val gcMode: CppGcMode = CppGcMode.NONE,
    public val portLintPrefix: String? = null,
    public val sourceRootPrefix: String? = null,
    public val rootNamespaceOverride: String? = null,
    public val fileStemSuffix: String? = null,
    public val sourceSetSubdir: String? = null,
    public val emitCompanionCAbiFiles: Boolean = false,
) {
    init {
        require(cppStandard >= 23) {
            "kotlin-cpp requires C++23 or newer (CMAKE_CXX_STANDARD >= 23), got C++$cppStandard"
        }
    }
}

/**
 * Co-located C++23 header, source, or C-ABI companion file artifact.
 */
public data class CppFileArtifact(
    public val relativePath: String,
    public val content: String,
)

/**
 * Complete single-file Kotlin-to-C++23 transliteration output.
 */
public data class CppTranslationResult(
    public val headerArtifact: CppFileArtifact,
    public val sourceArtifact: CppFileArtifact?,
    public val companionArtifacts: List<CppFileArtifact> = emptyList(),
    public val translatedDeclarationCount: Int,
    public val hoistedSuspendArgCount: Int,
    public val inlineExpandedCallCount: Int,
    public val summary: String,
)

internal data class KtAnnotationInfo(
    val simpleName: String,
    val qualifiedName: String,
    val stringArguments: List<String>,
    val rawArgumentTexts: List<String>,
    val frameIndex: Int,
)

internal data class KtTypeParamInfo(
    val name: String,
    val isReified: Boolean,
    val upperBoundFrameIndex: Int?,
    val frameIndex: Int,
)

internal data class KtValueParamInfo(
    val name: String,
    val cppName: String,
    val isVal: Boolean,
    val isVar: Boolean,
    val isVararg: Boolean,
    val isNoinline: Boolean,
    val isCrossinline: Boolean,
    val typeFrameIndex: Int?,
    val defaultExprFrameIndex: Int?,
    val frameIndex: Int,
)

/**
 * Structural navigation helpers over [PacketStream] and [PacketFrame] nodes.
 */
internal object FrameAstHelpers {
    fun childFrames(
        stream: PacketStream,
        frame: PacketFrame,
    ): List<PacketFrame> {
        val out = ArrayList<PacketFrame>(frame.childCount)
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (ref >= 0) {
                out.add(stream.frames[ref])
            }
        }
        return out
    }

    fun childFramesOfType(
        stream: PacketStream,
        frame: PacketFrame,
        nodeTypeId: Int,
    ): List<PacketFrame> {
        val out = ArrayList<PacketFrame>()
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (ref >= 0) {
                val child = stream.frames[ref]
                if (child.nodeTypeId == nodeTypeId) {
                    out.add(child)
                }
            }
        }
        return out
    }

    fun firstChildFrame(
        stream: PacketStream,
        frame: PacketFrame,
        nodeTypeId: Int,
    ): PacketFrame? {
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (ref >= 0) {
                val child = stream.frames[ref]
                if (child.nodeTypeId == nodeTypeId) {
                    return child
                }
            }
        }
        return null
    }

    fun directIdentPacket(
        stream: PacketStream,
        frame: PacketFrame,
    ): SyntaxPacket.IdentPacket? {
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (ref < 0) {
                val pkt = stream.packets[-(ref + 1)]
                if (pkt is SyntaxPacket.IdentPacket) {
                    return pkt
                }
            }
        }
        return null
    }

    fun hasDirectKeyword(
        stream: PacketStream,
        frame: PacketFrame,
        keywordId: Int,
    ): Boolean {
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (ref < 0) {
                val pkt = stream.packets[-(ref + 1)]
                if (pkt is SyntaxPacket.KeywordPacket && pkt.keywordId == keywordId) {
                    return true
                }
                if (pkt is SyntaxPacket.OperatorPacket && pkt.operatorId == keywordId) {
                    return true
                }
            }
        }
        return false
    }

    fun hasModifierKeyword(
        stream: PacketStream,
        declFrame: PacketFrame,
        keywordId: Int,
    ): Boolean {
        val modList = firstChildFrame(stream, declFrame, KtNodeTypes.MODIFIER_LIST_ID) ?: return false
        return hasDirectKeyword(stream, modList, keywordId)
    }

    fun renderPacketsText(
        stream: PacketStream,
        frame: PacketFrame,
    ): String {
        if (frame.firstPacketIndex > frame.lastPacketIndex || stream.packets.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for (idx in frame.firstPacketIndex..frame.lastPacketIndex) {
            sb.append(stream.packets[idx].rawText)
        }
        return sb.toString()
    }

    fun operationTokenId(
        stream: PacketStream,
        opFrame: PacketFrame,
    ): Int? {
        if (opFrame.firstPacketIndex > opFrame.lastPacketIndex || stream.packets.isEmpty()) {
            return null
        }
        for (idx in opFrame.firstPacketIndex..opFrame.lastPacketIndex) {
            when (val pkt = stream.packets[idx]) {
                is SyntaxPacket.OperatorPacket -> return pkt.operatorId
                is SyntaxPacket.KeywordPacket -> return pkt.keywordId
                is SyntaxPacket.IdentPacket -> return KtTokens.IDENTIFIER_ID
                is SyntaxPacket.DelimiterPacket -> return pkt.delimiterId
                else -> Unit
            }
        }
        return null
    }

    fun operationSymbolName(
        stream: PacketStream,
        opFrame: PacketFrame,
    ): String {
        if (opFrame.firstPacketIndex > opFrame.lastPacketIndex || stream.packets.isEmpty()) {
            return ""
        }
        for (idx in opFrame.firstPacketIndex..opFrame.lastPacketIndex) {
            when (val pkt = stream.packets[idx]) {
                is SyntaxPacket.IdentPacket -> return pkt.unescapedName
                is SyntaxPacket.OperatorPacket -> return pkt.rawText
                is SyntaxPacket.KeywordPacket -> return pkt.rawText
                is SyntaxPacket.DelimiterPacket -> return pkt.rawText
                else -> Unit
            }
        }
        return ""
    }

    fun isNullableTypeFrame(
        stream: PacketStream,
        typeFrame: PacketFrame,
    ): Boolean =
        when (typeFrame.nodeTypeId) {
            KtNodeTypes.NULLABLE_TYPE_ID -> true
            KtNodeTypes.TYPE_REFERENCE_ID ->
                childFrames(stream, typeFrame).any { it.nodeTypeId == KtNodeTypes.NULLABLE_TYPE_ID }
            else -> false
        }

    fun extractPrimaryTypeSimpleName(
        stream: PacketStream,
        typeFrame: PacketFrame,
    ): String? =
        when (typeFrame.nodeTypeId) {
            KtNodeTypes.TYPE_REFERENCE_ID,
            KtNodeTypes.NULLABLE_TYPE_ID,
            KtNodeTypes.TYPE_PROJECTION_ID,
            -> {
                val child = childFrames(stream, typeFrame).firstOrNull { it.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID }
                if (child != null) extractPrimaryTypeSimpleName(stream, child) else null
            }
            KtNodeTypes.USER_TYPE_ID -> {
                val refExpr = firstChildFrame(stream, typeFrame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                if (refExpr != null) {
                    directIdentPacket(stream, refExpr)?.unescapedName
                } else {
                    directIdentPacket(stream, typeFrame)?.unescapedName
                }
            }
            KtNodeTypes.DYNAMIC_TYPE_ID -> "dynamic"
            else -> null
        }

    fun dottedNameText(
        stream: PacketStream,
        frame: PacketFrame,
    ): String {
        val sb = StringBuilder()
        for (idx in frame.firstPacketIndex..frame.lastPacketIndex) {
            when (val pkt = stream.packets[idx]) {
                is SyntaxPacket.IdentPacket -> sb.append(pkt.unescapedName)
                is SyntaxPacket.DelimiterPacket ->
                    if (pkt.delimiterId == KtTokens.DOT_ID) {
                        sb.append('.')
                    }
                is SyntaxPacket.OperatorPacket ->
                    if (pkt.operatorId == KtTokens.MUL_ID) {
                        sb.append('*')
                    }
                else -> Unit
            }
        }
        return sb.toString()
    }

    fun extractAnnotations(
        stream: PacketStream,
        declFrame: PacketFrame,
    ): List<KtAnnotationInfo> {
        val modList = firstChildFrame(stream, declFrame, KtNodeTypes.MODIFIER_LIST_ID) ?: return emptyList()
        val entries = ArrayList<PacketFrame>()
        for (child in childFrames(stream, modList)) {
            if (child.nodeTypeId == KtNodeTypes.ANNOTATION_ENTRY_ID) {
                entries.add(child)
            } else if (child.nodeTypeId == KtNodeTypes.ANNOTATION_ID) {
                entries.addAll(childFramesOfType(stream, child, KtNodeTypes.ANNOTATION_ENTRY_ID))
            }
        }
        val out = ArrayList<KtAnnotationInfo>(entries.size)
        for (entry in entries) {
            val callee = firstChildFrame(stream, entry, KtNodeTypes.CONSTRUCTOR_CALLEE_ID)
            val qualified =
                if (callee != null) {
                    dottedNameText(stream, callee)
                } else {
                    directIdentPacket(stream, entry)?.unescapedName.orEmpty()
                }
            if (qualified.isEmpty()) continue
            val simple = qualified.substringAfterLast('.')
            val argList = firstChildFrame(stream, entry, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
            val strArgs = ArrayList<String>()
            val rawArgs = ArrayList<String>()
            if (argList != null) {
                for (arg in childFramesOfType(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID)) {
                    rawArgs.add(renderPacketsText(stream, arg).trim())
                    val strFrame = findDescendantFrame(stream, arg, KtNodeTypes.STRING_TEMPLATE_ID)
                    if (strFrame != null) {
                        strArgs.add(extractConstantStringText(stream, strFrame))
                    }
                }
            }
            out.add(
                KtAnnotationInfo(
                    simpleName = simple,
                    qualifiedName = qualified,
                    stringArguments = strArgs,
                    rawArgumentTexts = rawArgs,
                    frameIndex = entry.frameIndex,
                ),
            )
        }
        return out
    }

    fun extractConstantStringText(
        stream: PacketStream,
        stringTemplateFrame: PacketFrame,
    ): String {
        if (stringTemplateFrame.firstPacketIndex > stringTemplateFrame.lastPacketIndex || stream.packets.isEmpty()) {
            return ""
        }
        var isMultiline = PacketFlags.hasFlag(stringTemplateFrame.flags, PacketFlags.RAW_MULTILINE_STRING)
        val sb = StringBuilder()
        for (idx in stringTemplateFrame.firstPacketIndex..stringTemplateFrame.lastPacketIndex) {
            val pkt = stream.packets[idx]
            if (pkt is SyntaxPacket.StringSegmentPacket) {
                when (pkt.segmentKind) {
                    io.github.kotlinmania.kotlincpp.packet.StringSegmentKind.OPEN_QUOTE -> {
                        if (pkt.rawText == "\"\"\"" || PacketFlags.hasFlag(pkt.flags, PacketFlags.RAW_MULTILINE_STRING)) {
                            isMultiline = true
                        }
                    }
                    io.github.kotlinmania.kotlincpp.packet.StringSegmentKind.LITERAL_PART -> sb.append(pkt.rawText)
                    io.github.kotlinmania.kotlincpp.packet.StringSegmentKind.ESCAPE_SEQ ->
                        sb.append(unescapeSimpleKotlinString(pkt.rawText))
                    io.github.kotlinmania.kotlincpp.packet.StringSegmentKind.CLOSE_QUOTE -> {
                        if (isMultiline && pkt.rawText.length > 3 && pkt.rawText.endsWith("\"\"\"")) {
                            sb.append(pkt.rawText, 0, pkt.rawText.length - 3)
                        }
                    }
                    else -> Unit
                }
            }
        }
        return if (isMultiline) sb.toString().trimIndent() else sb.toString()
    }

    private fun unescapeSimpleKotlinString(body: String): String =
        buildString(body.length) {
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    when (val next = body[i + 1]) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'b' -> append('\b')
                        '\\' -> append('\\')
                        '"' -> append('"')
                        '\'' -> append('\'')
                        '$' -> append('$')
                        'u' -> {
                            if (i + 5 < body.length) {
                                val hex = body.substring(i + 2, i + 6).toIntOrNull(16)
                                if (hex != null) {
                                    append(hex.toChar())
                                    i += 6
                                    continue
                                }
                            }
                            append('\\').append(next)
                        }
                        else -> append(next)
                    }
                    i += 2
                } else {
                    append(c)
                    i += 1
                }
            }
        }

    fun findDescendantFrame(
        stream: PacketStream,
        root: PacketFrame,
        targetTypeId: Int,
    ): PacketFrame? {
        if (root.nodeTypeId == targetTypeId) return root
        for (i in 0 until root.childCount) {
            val ref = root.childRefAt(i)
            if (ref >= 0) {
                val found = findDescendantFrame(stream, stream.frames[ref], targetTypeId)
                if (found != null) return found
            }
        }
        return null
    }

    fun extractTypeParams(
        stream: PacketStream,
        declFrame: PacketFrame,
    ): List<KtTypeParamInfo> {
        val tpList = firstChildFrame(stream, declFrame, KtNodeTypes.TYPE_PARAMETER_LIST_ID) ?: return emptyList()
        val constraintMap = HashMap<String, Int>()
        val constraintList = firstChildFrame(stream, declFrame, KtNodeTypes.TYPE_CONSTRAINT_LIST_ID)
        if (constraintList != null) {
            for (tc in childFramesOfType(stream, constraintList, KtNodeTypes.TYPE_CONSTRAINT_ID)) {
                val refExpr = firstChildFrame(stream, tc, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                val tpName =
                    if (refExpr != null) {
                        directIdentPacket(stream, refExpr)?.unescapedName
                    } else {
                        directIdentPacket(stream, tc)?.unescapedName
                    }
                val bound = firstChildFrame(stream, tc, KtNodeTypes.TYPE_REFERENCE_ID)
                if (tpName != null && bound != null) {
                    constraintMap[tpName] = bound.frameIndex
                }
            }
        }

        val out = ArrayList<KtTypeParamInfo>()
        for (tp in childFramesOfType(stream, tpList, KtNodeTypes.TYPE_PARAMETER_ID)) {
            val ident = directIdentPacket(stream, tp)?.unescapedName ?: continue
            val isReified = PacketFlags.hasFlag(tp.flags, PacketFlags.MOD_REIFIED)
            val directBound = firstChildFrame(stream, tp, KtNodeTypes.TYPE_REFERENCE_ID)?.frameIndex
            out.add(
                KtTypeParamInfo(
                    name = ident,
                    isReified = isReified,
                    upperBoundFrameIndex = directBound ?: constraintMap[ident],
                    frameIndex = tp.frameIndex,
                ),
            )
        }
        return out
    }

    fun extractValueParams(
        stream: PacketStream,
        paramListFrame: PacketFrame?,
    ): List<KtValueParamInfo> {
        if (paramListFrame == null) return emptyList()
        val out = ArrayList<KtValueParamInfo>()
        for (vp in childFramesOfType(stream, paramListFrame, KtNodeTypes.VALUE_PARAMETER_ID)) {
            val ident = directIdentPacket(stream, vp)?.unescapedName ?: continue
            val isVal = hasDirectKeyword(stream, vp, KtTokens.VAL_KEYWORD_ID)
            val isVar = hasDirectKeyword(stream, vp, KtTokens.VAR_KEYWORD_ID)
            val isVararg = hasModifierKeyword(stream, vp, KtTokens.VARARG_MODIFIER_ID)
            val isNoinline = PacketFlags.hasFlag(vp.flags, PacketFlags.MOD_NOINLINE)
            val isCrossinline = PacketFlags.hasFlag(vp.flags, PacketFlags.MOD_CROSSINLINE)
            val typeFrame = firstChildFrame(stream, vp, KtNodeTypes.TYPE_REFERENCE_ID)
            val defaultExpr = extractInitializerAfterEquals(stream, vp)
            out.add(
                KtValueParamInfo(
                    name = ident,
                    cppName = CppNamingPolicy.toSnakeCase(ident),
                    isVal = isVal,
                    isVar = isVar,
                    isVararg = isVararg,
                    isNoinline = isNoinline,
                    isCrossinline = isCrossinline,
                    typeFrameIndex = typeFrame?.frameIndex,
                    defaultExprFrameIndex = defaultExpr?.frameIndex,
                    frameIndex = vp.frameIndex,
                ),
            )
        }
        return out
    }

    fun extractInitializerAfterEquals(
        stream: PacketStream,
        frame: PacketFrame,
    ): PacketFrame? {
        var seenEq = false
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (ref < 0) {
                val pkt = stream.packets[-(ref + 1)]
                if (pkt is SyntaxPacket.OperatorPacket && pkt.operatorId == KtTokens.EQ_ID) {
                    seenEq = true
                }
            } else if (seenEq) {
                val child = stream.frames[ref]
                if (child.nodeTypeId != KtNodeTypes.PROPERTY_ACCESSOR_ID) {
                    return child
                }
            }
        }
        return null
    }

    /**
     * For `FUN` or `PROPERTY`, distinguishes an extension receiver `TYPE_REFERENCE` (before
     * the declaration identifier + `.`) from the return/value `TYPE_REFERENCE` (after `:`).
     */
    fun extractReceiverAndReturnTypeFrames(
        stream: PacketStream,
        declFrame: PacketFrame,
    ): Pair<PacketFrame?, PacketFrame?> {
        var declIdentSeen = false
        var colonAfterIdentOrParamsSeen = false
        var seenParamList = false
        var receiverType: PacketFrame? = null
        var returnType: PacketFrame? = null

        for (i in 0 until declFrame.childCount) {
            val ref = declFrame.childRefAt(i)
            if (ref < 0) {
                val pkt = stream.packets[-(ref + 1)]
                if (pkt is SyntaxPacket.IdentPacket) {
                    declIdentSeen = true
                } else if (pkt is SyntaxPacket.DelimiterPacket && pkt.delimiterId == KtTokens.COLON_ID) {
                    if (declIdentSeen || seenParamList) {
                        colonAfterIdentOrParamsSeen = true
                    }
                } else if (pkt is SyntaxPacket.OperatorPacket && pkt.operatorId == KtTokens.EQ_ID) {
                    break
                }
            } else {
                val child = stream.frames[ref]
                if (child.nodeTypeId == KtNodeTypes.VALUE_PARAMETER_LIST_ID) {
                    seenParamList = true
                } else if (child.nodeTypeId == KtNodeTypes.TYPE_REFERENCE_ID) {
                    if (!declIdentSeen && !seenParamList && receiverType == null) {
                        receiverType = child
                    } else if (colonAfterIdentOrParamsSeen && returnType == null) {
                        returnType = child
                    }
                } else if (child.nodeTypeId == KtNodeTypes.BLOCK_ID) {
                    break
                }
            }
        }
        return receiverType to returnType
    }

    /**
     * Extracts leading comment packets attached directly inside [declFrame] before the
     * declaration's first keyword/identifier (e.g. KDoc `/** ... */` or `//` notes).
     */
    fun extractLeadingDeclarationComments(
        stream: PacketStream,
        declFrame: PacketFrame,
    ): List<SyntaxPacket.CommentPacket> {
        val out = ArrayList<SyntaxPacket.CommentPacket>()
        for (i in 0 until declFrame.childCount) {
            val ref = declFrame.childRefAt(i)
            if (ref < 0) {
                when (val pkt = stream.packets[-(ref + 1)]) {
                    is SyntaxPacket.CommentPacket -> {
                        if (pkt.portLintDirectiveKind == null) {
                            out.add(pkt)
                        }
                    }
                    is SyntaxPacket.WhitespacePacket -> Unit
                    else -> break
                }
            } else {
                val child = stream.frames[ref]
                if (child.nodeTypeId == KtNodeTypes.MODIFIER_LIST_ID) {
                    for (j in 0 until child.childCount) {
                        val mRef = child.childRefAt(j)
                        if (mRef < 0) {
                            val mPkt = stream.packets[-(mRef + 1)]
                            if (mPkt is SyntaxPacket.CommentPacket && mPkt.portLintDirectiveKind == null) {
                                out.add(mPkt)
                            }
                        }
                    }
                }
                break
            }
        }
        return out
    }

    fun formatCommentForCpp(
        comment: SyntaxPacket.CommentPacket,
        indent: String,
        knownFunctionNames: Set<String> = emptySet(),
        allowFenceTranslation: Boolean = true,
    ): String =
        CppComments.formatCommentForCpp(
            comment = comment,
            indent = indent,
            knownFunctionNames = knownFunctionNames,
            allowFenceTranslation = allowFenceTranslation,
        )

    fun unwrapLambdaExpression(
        stream: PacketStream,
        frame: PacketFrame,
    ): PacketFrame? =
        when (frame.nodeTypeId) {
            KtNodeTypes.LAMBDA_EXPRESSION_ID -> frame
            KtNodeTypes.LAMBDA_ARGUMENT_ID,
            KtNodeTypes.VALUE_ARGUMENT_ID,
            -> {
                val inner =
                    childFrames(stream, frame).lastOrNull {
                        it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID &&
                            it.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID
                    }
                if (inner != null) unwrapLambdaExpression(stream, inner) else null
            }
            KtNodeTypes.LABELED_EXPRESSION_ID -> {
                val inner =
                    childFrames(stream, frame).lastOrNull {
                        it.nodeTypeId != KtNodeTypes.LABEL_QUALIFIER_ID &&
                            it.nodeTypeId != KtNodeTypes.LABEL_ID
                    }
                if (inner != null) unwrapLambdaExpression(stream, inner) else null
            }
            KtNodeTypes.ANNOTATED_EXPRESSION_ID -> {
                val inner =
                    childFrames(stream, frame).lastOrNull {
                        it.nodeTypeId != KtNodeTypes.ANNOTATION_ENTRY_ID &&
                            it.nodeTypeId != KtNodeTypes.ANNOTATION_ID &&
                            it.nodeTypeId != KtNodeTypes.MODIFIER_LIST_ID
                    }
                if (inner != null) unwrapLambdaExpression(stream, inner) else null
            }
            KtNodeTypes.PARENTHESIZED_ID -> {
                val inner = childFrames(stream, frame).firstOrNull()
                if (inner != null) unwrapLambdaExpression(stream, inner) else null
            }
            else -> null
        }
}
