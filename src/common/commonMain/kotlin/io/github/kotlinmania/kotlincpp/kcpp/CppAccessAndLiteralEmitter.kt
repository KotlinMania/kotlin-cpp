package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.CANCELLATION_EXCEPTION_CLASS_NAMES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.CHANNEL_STATIC_CONSTANTS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.CONTINUATION_HIERARCHY_BASES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.DIRECT_ITERABLE_VALUE_TYPES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.DISPATCHER_OR_EVENT_LOOP_FACTORY_NAMES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.DURATION_WHOLE_UNITS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.KNOWN_CONTEXT_KEY_CARRIERS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.KNOWN_EXTERNAL_RECEIVER_PROPERTIES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.KNOWN_OBJECT_SINGLETONS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.KNOWN_TO_STRING_SUPER_FRAGMENTS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.escapeCppStringLiteral
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.LiteralPacketKind
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.StringSegmentKind
import io.github.kotlinmania.kotlincpp.packet.SymbolRole
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal fun CppExprAndStmtEmitter.frameContainsLabeledThis(root: PacketFrame): Boolean {
    if (root.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID &&
        FrameAstHelpers.firstChildFrame(stream, root, KtNodeTypes.LABEL_QUALIFIER_ID) != null
    ) {
        return true
    }
    return FrameAstHelpers.childFrames(stream, root).any { frameContainsLabeledThis(it) }
}

internal fun CppExprAndStmtEmitter.frameContainsNodeType(
    root: PacketFrame,
    nodeTypeId: Int,
): Boolean {
    if (root.nodeTypeId == nodeTypeId) return true
    return FrameAstHelpers.childFrames(stream, root).any { frameContainsNodeType(it, nodeTypeId) }
}

internal fun CppExprAndStmtEmitter.needsOuterThisCapture(
    outerCls: KtClassSymbol,
    superList: PacketFrame?,
    classBody: PacketFrame?,
    capturedLocalKeys: Set<String>,
    ownMethodNames: Set<String>,
    ownPropNames: Set<String>,
): Boolean {
    if (superList != null && frameContainsNodeType(superList, KtNodeTypes.THIS_EXPRESSION_ID)) {
        return true
    }
    if (classBody != null && frameContainsLabeledThis(classBody)) {
        return true
    }
    val scanRoots = listOfNotNull(superList, classBody)
    for (root in scanRoots) {
        if (root.firstPacketIndex > root.lastPacketIndex) continue
        for (pktIdx in root.firstPacketIndex..root.lastPacketIndex) {
            val pkt = stream.packets[pktIdx] as? SyntaxPacket.IdentPacket ?: continue
            val name = pkt.unescapedName
            if (name in capturedLocalKeys || name in ownMethodNames || name in ownPropNames) continue
            if (findClassHierarchyProperty(outerCls, name) != null || classDeclaresMethod(outerCls, name)) {
                return true
            }
        }
    }
    return false
}

internal fun CppExprAndStmtEmitter.emitObjectLiteralExpr(objFrame: PacketFrame): String {
    val objDecl = FrameAstHelpers.firstChildFrame(stream, objFrame, KtNodeTypes.OBJECT_DECLARATION_ID) ?: objFrame
    val superList = FrameAstHelpers.firstChildFrame(stream, objDecl, KtNodeTypes.SUPER_TYPE_LIST_ID)
    val classBody = FrameAstHelpers.firstChildFrame(stream, objDecl, KtNodeTypes.CLASS_BODY_ID)

    val ownMethodNames = LinkedHashSet<String>()
    val ownPropNames = LinkedHashSet<String>()
    if (classBody != null) {
        for (member in FrameAstHelpers.childFrames(stream, classBody)) {
            val name = FrameAstHelpers.directIdentPacket(stream, member)?.unescapedName ?: continue
            if (member.nodeTypeId == KtNodeTypes.FUN_ID) {
                ownMethodNames.add(name)
            } else if (member.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                ownPropNames.add(name)
            }
        }
    }

    val capturedLocals = LinkedHashMap<String, String>()
    for (scanRoot in listOfNotNull(superList, classBody)) {
        if (scanRoot.firstPacketIndex <= scanRoot.lastPacketIndex) {
            for (pktIdx in scanRoot.firstPacketIndex..scanRoot.lastPacketIndex) {
                val pkt = stream.packets[pktIdx] as? SyntaxPacket.IdentPacket ?: continue
                val ktCap = pkt.unescapedName
                if (ktCap == "this" || ktCap == "this_val" || ktCap in capturedLocals) continue
                val bound = resolveBoundName(ktCap)
                if (bound != null && bound.all { it.isLetterOrDigit() || it == '_' }) {
                    capturedLocals[ktCap] = bound
                }
            }
        }
    }

    val outerCls =
        enclosingClass
            ?: if (receiverSubstitution == "this->kxs_outer_" || receiverSubstitution == "kxs_outer_in") {
                extensionReceiverClass
            } else {
                null
            }
    val canCaptureOuterThis =
        outerCls != null &&
            outerCls.kind != KtClassKind.COMPANION_OBJECT &&
            enclosingFunction?.annotations?.any { it.simpleName == "JvmStatic" } != true
    val captureOuterThis =
        canCaptureOuterThis &&
            needsOuterThisCapture(
                outerCls = outerCls,
                superList = superList,
                classBody = classBody,
                capturedLocalKeys = capturedLocals.keys,
                ownMethodNames = ownMethodNames,
                ownPropNames = ownPropNames,
            )
    val outerPassExpr =
        when {
            enclosingClass != null -> "this"
            receiverSubstitution == "this->kxs_outer_" -> "this->kxs_outer_"
            else -> "kxs_outer_in"
        }

    val superCtorEmitter =
        CppExprAndStmtEmitter(
            bundle = bundle,
            activeTypeParams = activeTypeParams,
            receiverSubstitution = if (captureOuterThis) "kxs_outer_in" else null,
            extensionReceiverClass = if (captureOuterThis) outerCls else null,
            extensionReceiverIsPointer = captureOuterThis,
            extensionReceiverSimpleName = if (captureOuterThis) outerCls.kotlinName else null,
            localPolymorphicClasses = localPolymorphicClasses,
            localExtensionFunctions = localExtensionFunctions,
        )
    for ((ktCap, cppCap) in capturedLocals) {
        superCtorEmitter.bindLocalName(ktCap, "${cppCap}_in")
        copyLocalMetadataTo(superCtorEmitter, ktCap)
    }

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
                                        ?.let { superCtorEmitter.emitExpression(it) }
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

    val anonName = freshTemp("KxsAnonObject")
    val superClause = if (superBases.isNotEmpty()) " : ${superBases.joinToString(", ") { "public $it" }}" else ""
    includes.addSystem("memory")
    includes.addSystem("type_traits")
    includes.addSystem("utility")

    return buildString {
        append("([&]() {\n")
        if (captureOuterThis) {
            append("    using KxsOuterPtr = decltype(").append(outerPassExpr).append(");\n")
        }
        append("    struct ").append(anonName).append(superClause).append(" {\n")
        if (captureOuterThis) {
            append("        KxsOuterPtr kxs_outer_;\n")
        }
        for ((_, capCpp) in capturedLocals) {
            append("        std::decay_t<decltype($capCpp)> ${capCpp}_cap;\n")
        }
        if (captureOuterThis || capturedLocals.isNotEmpty() || superCtorInits.isNotEmpty()) {
            val ctorParams =
                buildList {
                    if (captureOuterThis) add("KxsOuterPtr kxs_outer_in")
                    for (capCpp in capturedLocals.values) {
                        add("std::decay_t<decltype($capCpp)> ${capCpp}_in")
                    }
                }
            val inits = ArrayList<String>(superCtorInits.size + capturedLocals.size + 1)
            inits.addAll(superCtorInits)
            if (captureOuterThis) {
                inits.add("kxs_outer_(kxs_outer_in)")
            }
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
            val ownPropBindings = LinkedHashMap<String, String>()
            val deferredFunMembers = ArrayList<PacketFrame>()
            for (member in FrameAstHelpers.childFrames(stream, classBody)) {
                if (member.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
                    val pIdent = FrameAstHelpers.directIdentPacket(stream, member)?.unescapedName ?: "prop"
                    val pCpp = CppNamingPolicy.toSnakeCase(pIdent)
                    val (_, pTypeFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, member)
                    val getterAcc =
                        FrameAstHelpers
                            .childFramesOfType(stream, member, KtNodeTypes.PROPERTY_ACCESSOR_ID)
                            .firstOrNull { FrameAstHelpers.hasDirectKeyword(stream, it, KtTokens.GET_KEYWORD_ID) }
                    val pInit = FrameAstHelpers.extractInitializerAfterEquals(stream, member)
                    val isOvProp =
                        PacketFlags.hasFlag(member.flags, PacketFlags.MOD_OVERRIDE) && superBases.isNotEmpty()
                    val getterBlock = getterAcc?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.BLOCK_ID) }
                    val getterExpr =
                        if (getterAcc != null && getterBlock == null) {
                            FrameAstHelpers.extractInitializerAfterEquals(stream, getterAcc)
                        } else {
                            null
                        }
                    if (getterAcc != null || isOvProp) {
                        val isContContext =
                            pIdent == "context" && superBases.any { it.contains("Continuation") }
                        val getterMethodName = if (isContContext) "get_context" else pCpp
                        val retT =
                            pTypeFrame?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.RETURN) }
                                ?: inferExprTypeInfo(getterExpr ?: pInit, emptyMap())?.cppType
                                ?: if (isOvProp) {
                                    includes.addSystem("any")
                                    "std::any"
                                } else {
                                    "auto"
                                }
                        val pEmitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                activeTypeParams = activeTypeParams,
                                enclosingFunctionReturnType = retT.takeIf { it != "auto" },
                                receiverSubstitution = if (captureOuterThis) "this->kxs_outer_" else null,
                                extensionReceiverClass = if (captureOuterThis) outerCls else null,
                                extensionReceiverIsPointer = captureOuterThis,
                                extensionReceiverSimpleName = if (captureOuterThis) outerCls.kotlinName else null,
                                localPolymorphicClasses = localPolymorphicClasses,
                                localExtensionFunctions = localExtensionFunctions,
                                anonObjectOwnMethods = ownMethodNames,
                                anonObjectOwnProps = ownPropNames,
                            )
                        for ((ktCap, cppCap) in capturedLocals) {
                            pEmitter.bindLocalName(ktCap, "this->${cppCap}_cap")
                            copyLocalMetadataTo(pEmitter, ktCap)
                        }
                        for ((k, v) in ownPropBindings) pEmitter.bindLocalName(k, v)
                        val bodyF = getterBlock ?: getterExpr ?: pInit
                        val bodyCode =
                            if (bodyF != null) {
                                pEmitter.emitBlockOrExprBody(bodyF, getterBlock == null, isVoidReturn = false, indent = "        ")
                            } else {
                                "{}"
                            }
                        val ovSuffix = if (isOvProp) " override" else ""
                        append("        ")
                            .append(retT)
                            .append(' ')
                            .append(getterMethodName)
                            .append("()")
                            .append(ovSuffix)
                            .append(' ')
                            .append(bodyCode)
                            .append('\n')
                        ownPropBindings[pIdent] = "this->$getterMethodName()"
                    } else {
                        val pType =
                            pTypeFrame?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.VALUE) }
                                ?: inferExprTypeInfo(pInit, emptyMap())?.cppType
                                ?: run {
                                    includes.addSystem("any")
                                    "std::any"
                                }
                        val pEmitter =
                            CppExprAndStmtEmitter(
                                bundle = bundle,
                                activeTypeParams = activeTypeParams,
                                receiverSubstitution = if (captureOuterThis) "this->kxs_outer_" else null,
                                extensionReceiverClass = if (captureOuterThis) outerCls else null,
                                extensionReceiverIsPointer = captureOuterThis,
                                extensionReceiverSimpleName = if (captureOuterThis) outerCls.kotlinName else null,
                                localPolymorphicClasses = localPolymorphicClasses,
                                localExtensionFunctions = localExtensionFunctions,
                                anonObjectOwnMethods = ownMethodNames,
                                anonObjectOwnProps = ownPropNames,
                            )
                        for ((ktCap, cppCap) in capturedLocals) {
                            pEmitter.bindLocalName(ktCap, "this->${cppCap}_cap")
                            copyLocalMetadataTo(pEmitter, ktCap)
                        }
                        for ((k, v) in ownPropBindings) pEmitter.bindLocalName(k, v)
                        val initExpr = pInit?.let { pEmitter.emitExpression(it) }
                        if (initExpr != null) {
                            append("        ")
                                .append(pType)
                                .append(' ')
                                .append(pCpp)
                                .append(" = ")
                                .append(initExpr)
                                .append(";\n")
                        } else {
                            append("        ")
                                .append(pType)
                                .append(' ')
                                .append(pCpp)
                                .append("{};\n")
                        }
                        ownPropBindings[pIdent] = "this->$pCpp"
                    }
                } else if (member.nodeTypeId == KtNodeTypes.FUN_ID) {
                    deferredFunMembers.add(member)
                }
            }
            for (member in deferredFunMembers) {
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
                        enclosingFunctionReturnType = retT.takeIf { it != "auto" && !isVoidM },
                        receiverSubstitution = if (captureOuterThis) "this->kxs_outer_" else null,
                        extensionReceiverClass = if (captureOuterThis) outerCls else null,
                        extensionReceiverIsPointer = captureOuterThis,
                        extensionReceiverSimpleName = if (captureOuterThis) outerCls.kotlinName else null,
                        localPolymorphicClasses = localPolymorphicClasses,
                        localExtensionFunctions = localExtensionFunctions,
                        anonObjectOwnMethods = ownMethodNames,
                        anonObjectOwnProps = ownPropNames,
                    )
                for ((ktCap, cppCap) in capturedLocals) {
                    mEmitter.bindLocalName(ktCap, "this->${cppCap}_cap")
                    copyLocalMetadataTo(mEmitter, ktCap)
                }
                for ((k, v) in ownPropBindings) {
                    mEmitter.bindLocalName(k, v)
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
                val blockB = FrameAstHelpers.firstChildFrame(stream, member, KtNodeTypes.BLOCK_ID)
                val exprB = if (blockB == null) FrameAstHelpers.extractInitializerAfterEquals(stream, member) else null
                val bodyF = blockB ?: exprB
                val bodyCode =
                    if (bodyF != null) {
                        mEmitter.emitBlockOrExprBody(bodyF, exprB != null, isVoidM, "        ")
                    } else {
                        "{}"
                    }
                val renderedParams = renderClassLikeMethodParams(vps, bodyCode)
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
        append("    };\n")
        val makeArgs =
            buildList {
                if (captureOuterThis) add(outerPassExpr)
                addAll(capturedLocals.values)
            }.joinToString(", ")
        append("    return std::make_shared<$anonName>($makeArgs);\n")
        append("}())")
    }
}

internal fun CppExprAndStmtEmitter.emitConstantLiteral(litFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.emitStringTemplate(strFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.wrapForStringConcatenation(exprFrame: PacketFrame): String {
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

internal fun CppExprAndStmtEmitter.extractStringSegmentRaw(
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

internal fun CppExprAndStmtEmitter.isKnownStringSymbol(kotlinName: String): Boolean {
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

internal fun CppExprAndStmtEmitter.encodeSurrogateSafeCppString(raw: String): String {
    // Construct via explicit hex code units without embedding unpaired UTF-16 surrogate literals in Kotlin source!
    val units = raw.map { "0x${it.code.toString(16).uppercase()}" }
    return "std::string{${units.joinToString(", ") { "static_cast<char>($it & 0xFF)" }}}"
}

internal fun CppExprAndStmtEmitter.encodeSingleUtf16UnitForCpp(codeUnit: Int): String =
    "std::string{static_cast<char>(0x${(codeUnit shr 8).toString(16).uppercase()}), " +
        "static_cast<char>(0x${(codeUnit and 0xFF).toString(16).uppercase()})}"

internal fun CppExprAndStmtEmitter.decodeKotlinEscapeToCharOrText(rawEsc: String): String =
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

internal fun CppExprAndStmtEmitter.emitThisExpr(thisFrame: PacketFrame): String {
    val labelFrame = FrameAstHelpers.firstChildFrame(stream, thisFrame, KtNodeTypes.LABEL_QUALIFIER_ID)
    if (labelFrame != null) {
        val labelName = FrameAstHelpers.directIdentPacket(stream, labelFrame)?.unescapedName
        val targetOuterCls =
            enclosingClass
                ?: if (receiverSubstitution == "this->kxs_outer_" || receiverSubstitution == "kxs_outer_in") {
                    extensionReceiverClass
                } else {
                    null
                }
        val basePtr = if (enclosingClass != null) "this" else receiverSubstitution
        if (labelName != null && targetOuterCls != null && basePtr != null) {
            if (targetOuterCls.kotlinName == labelName) {
                if (!bundle.isDocExample && isSharedThisClass(targetOuterCls.kotlinName)) {
                    includes.needsSharedFromThisDef = true
                    includes.addSystem("memory")
                    includes.addSystem("type_traits")
                    return "kxs_shared_from_this($basePtr)"
                }
                return "(*$basePtr)"
            }
            var cur: KtClassSymbol? = targetOuterCls
            var hops = 0
            while (cur != null && cur.isInner) {
                val parent = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] } ?: break
                hops += 1
                if (parent.kotlinName == labelName) {
                    val ptrChain =
                        if (basePtr == "this") {
                            if (hops == 1) "outer_" else "${"outer_->".repeat(hops - 1)}outer_"
                        } else {
                            "$basePtr->${"outer_->".repeat(hops - 1)}outer_"
                        }
                    if (!bundle.isDocExample && isSharedThisClass(parent.kotlinName)) {
                        includes.needsSharedFromThisDef = true
                        includes.addSystem("memory")
                        includes.addSystem("type_traits")
                        return "kxs_shared_from_this($ptrChain)"
                    }
                    return "(*$ptrChain)"
                }
                cur = parent
            }
        }
        return "(*this)"
    }
    if (receiverSubstitution == "this->kxs_outer_") {
        return "(*this)"
    }
    if (receiverSubstitution == "kxs_outer_in") {
        val outerExtCls = extensionReceiverClass
        if (outerExtCls != null && !bundle.isDocExample && isSharedThisClass(outerExtCls.kotlinName)) {
            includes.needsSharedFromThisDef = true
            includes.addSystem("memory")
            includes.addSystem("type_traits")
            return "kxs_shared_from_this(kxs_outer_in)"
        }
        return "(*kxs_outer_in)"
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

internal fun CppExprAndStmtEmitter.resolveOuterChainPrefix(targetOwnerClassFrameIndex: Int?): String? {
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

internal fun CppExprAndStmtEmitter.resolveOuterPointerExpr(targetOwnerClassFrameIndex: Int?): String? {
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

internal fun CppExprAndStmtEmitter.lookupAccessibleClassByName(kotlinName: String): KtClassSymbol? {
    var cur: KtClassSymbol? = enclosingClass ?: extensionReceiverClass
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

internal fun CppExprAndStmtEmitter.emitReferenceExpr(refFrame: PacketFrame): String {
    val pkt =
        FrameAstHelpers.directIdentPacket(stream, refFrame)
            ?: throw IllegalArgumentException(
                "${stream.sourcePath}:${refFrame.span.startLine}:${refFrame.span.startColumn}: missing IdentPacket in REFERENCE_EXPRESSION",
            )
    val ktName = pkt.unescapedName

    resolveBoundName(ktName)?.let { return it }
    if (ktName == "this" || ktName == "this_val") {
        if (receiverSubstitution == "this->kxs_outer_") return "(*this)"
        receiverSubstitution?.let { return it }
        if (enclosingClass != null) {
            return "(*this)"
        }
    }

    // Check if `ktName` is an enum entry, private field, or property on `enclosingClass` or an `inner` outer class
    resolveUnqualifiedPropertyAccess(ktName)?.let { return it }

    if (!bundle.isDocExample &&
        enclosingClass != null &&
        (ktName == "classSimpleName" || ktName == "hexAddress") &&
        ktName !in bundle.symbols.propertiesByName &&
        ktName !in bundle.symbols.functionsByName
    ) {
        includes.addProject("kotlinx/coroutines/Debug.hpp")
        val nsPrefix =
            if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                ""
            } else {
                "kotlinx::coroutines::"
            }
        return "$nsPrefix${CppNamingPolicy.toSnakeCase(ktName)}(this)"
    }

    if (!bundle.isDocExample && ktName == "Unit") {
        includes.addProject("kotlinx/coroutines/Unit.hpp")
        return "kotlinx::coroutines::Unit{}"
    }
    if (ktName == "COROUTINE_SUSPENDED") {
        includes.addProject("kotlinx/coroutines/intrinsics/Intrinsics.hpp")
        return "COROUTINE_SUSPENDED"
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
        if (effectiveKtName == "Unconfined") {
            val qualUnconfined =
                lookupAccessibleClassByName("Unconfined")?.cppName
                    ?: CppTypeTranslator.includeAndQualifyKnownType(
                        simpleName = "Unconfined",
                        cppBaseName = "Unconfined",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    )
            if (qualUnconfined != null) return "$qualUnconfined::shared_instance()"
        }
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
        val importedOwner =
            bundle.symbols.importedCompanionMembers[pkt.unescapedName]
                ?: bundle.symbols.importedCompanionMembers[effectiveKtName]
        if (importedOwner != null) {
            val ownerSameCls = lookupAccessibleClassByName(importedOwner)
            val qualOwner =
                ownerSameCls?.cppName
                    ?: CppTypeTranslator.includeAndQualifyKnownType(
                        simpleName = importedOwner,
                        cppBaseName = CppNamingPolicy.toTypeName(importedOwner),
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    )
            if (qualOwner != null) {
                val ownerPrefix =
                    when {
                        ownerSameCls != null && enclosingClass?.frameIndex == ownerSameCls.frameIndex -> null
                        ownerSameCls != null && ownerSameCls.typeParams.isNotEmpty() -> {
                            val voidArgs = ownerSameCls.typeParams.joinToString(", ") { "void*" }
                            "$qualOwner<$voidArgs>"
                        }
                        importedOwner in
                            setOf(
                                "Channel",
                                "ChannelResult",
                                "SendChannel",
                                "ReceiveChannel",
                                "BufferedChannel",
                                "BroadcastChannel",
                            ) -> "$qualOwner<void*>"
                        else -> qualOwner
                    }
                val isAllCaps =
                    effectiveKtName.any { it.isLetter() } &&
                        effectiveKtName.all { it.isUpperCase() || it.isDigit() || it == '_' }
                val cppMember =
                    if (isAllCaps) {
                        CppNamingPolicy.toConstName(effectiveKtName)
                    } else {
                        CppNamingPolicy.toSnakeCase(effectiveKtName)
                    }
                return if (ownerPrefix != null) "$ownerPrefix::$cppMember" else cppMember
            }
        }
    }

    if (!bundle.isDocExample &&
        !inDefaultParameterExpr &&
        enclosingClass != null &&
        enclosingClass.typeParams.isNotEmpty() &&
        enclosingClass.superTypeFrameIndices.isNotEmpty()
    ) {
        val companionSym = enclosingClass.companionFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        val isCompanionMember =
            companionSym != null &&
                (
                    companionSym.memberPropertyFrameIndices.any { bundle.symbols.propertiesByFrame[it]?.kotlinName == ktName } ||
                        companionSym.memberFunctionFrameIndices.any { bundle.symbols.functionsByFrame[it]?.kotlinName == ktName }
                )
        val isNestedClass =
            enclosingClass.memberClassFrameIndices.any { bundle.symbols.classesByFrame[it]?.kotlinName == ktName }
        val isTypeParam =
            ktName in activeTypeParams ||
                effectiveKtName in activeTypeParams ||
                enclosingClass.typeParams.any { it.name == ktName || it.name == effectiveKtName }
        val isSameFileTopLevel =
            ktName in bundle.symbols.propertiesByName ||
                effectiveKtName in bundle.symbols.propertiesByName ||
                bundle.symbols.functionsByName[ktName]?.any { it.enclosingClassFrameIndex == null } == true ||
                bundle.symbols.functionsByName[effectiveKtName]?.any { it.enclosingClassFrameIndex == null } == true ||
                ktName in bundle.symbols.classesByName ||
                effectiveKtName in bundle.symbols.classesByName ||
                bundle.symbols.typeAliasesByFrame.values
                    .any { it.kotlinName == ktName || it.kotlinName == effectiveKtName }
        if (!isTypeParam &&
            !isSameFileTopLevel &&
            !isCompanionMember &&
            !isNestedClass &&
            pkt.role != SymbolRole.TYPE &&
            !isVerbatimCIdentifier(effectiveKtName) &&
            effectiveKtName.firstOrNull()?.isUpperCase() != true
        ) {
            return "this->$rawCppName"
        }
    }

    return rawCppName
}

internal fun CppExprAndStmtEmitter.emitDotQualifiedExpr(dotFrame: PacketFrame): String {
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
    if (!bundle.isDocExample) {
        val dottedRecv = FrameAstHelpers.dottedNameText(stream, recvFrame)
        val dottedParts = dottedRecv.split('.').filter { it.isNotEmpty() }
        val isPkgPrefix =
            dottedParts.isNotEmpty() &&
                (dottedParts.first() == "kotlin" || dottedParts.first() == "kotlinx") &&
                dottedParts.all { it.first().isLowerCase() }
        if (isPkgPrefix && selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            return emitCallExpr(receiverCode = null, receiverIsPointer = false, callFrame = selFrame)
        }
        if (isPkgPrefix && selFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            return emitReferenceExpr(selFrame)
        }
        if (selFrame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val selKt = FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName
            val isDispatchersRecv =
                (
                    dottedParts.size == 1 &&
                        (bundle.symbols.importAliases[dottedParts[0]] ?: dottedParts[0]) == "Dispatchers" &&
                        resolveBoundName(dottedParts[0]) == null
                ) ||
                    (
                        dottedParts.size >= 2 &&
                            dottedParts.last() == "Dispatchers" &&
                            (dottedParts.first() == "kotlin" || dottedParts.first() == "kotlinx") &&
                            dottedParts.dropLast(1).all { it.first().isLowerCase() }
                    )
            if (isDispatchersRecv && selKt in setOf("Unconfined", "Main", "Default", "IO")) {
                val qualDisp =
                    lookupAccessibleClassByName("Dispatchers")?.cppName
                        ?: CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = "Dispatchers",
                            cppBaseName = "Dispatchers",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "Dispatchers"
                return when (selKt) {
                    "Unconfined" -> "$qualDisp::instance().unconfined()"
                    "Main" -> "$qualDisp::instance().main()"
                    "Default" -> "$qualDisp::default_val"
                    else -> "$qualDisp::IO"
                }
            }
        }
    }

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
                if (sameRecvCls.typeParams.isNotEmpty() &&
                    enclosingClass?.frameIndex != sameRecvCls.frameIndex &&
                    selFrame.nodeTypeId != KtNodeTypes.CONSTRUCTOR_CALLEE_ID
                ) {
                    val voidArgs = sameRecvCls.typeParams.joinToString(", ") { "void*" }
                    "${sameRecvCls.cppName}<$voidArgs>"
                } else {
                    sameRecvCls.cppName
                }
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
            withExpectedTargetType(null) { emitExpression(recvFrame) }
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
            val selTypeArgs = extractCallTypeArgs(selFrame)
            val selTSuffix = if (selTypeArgs.isNotEmpty()) "<${selTypeArgs.joinToString(", ")}>" else ""
            val args = extractCallArgExpressions(selFrame)
            return "$recvCode::${CppNamingPolicy.toSnakeCase(fnName)}$selTSuffix(${args.joinToString(", ")})"
        }
        return emitCallExpr(
            receiverCode = recvCode,
            receiverIsPointer = isPtrReceiver,
            callFrame = selFrame,
            receiverFrame = recvFrame,
        )
    }

    val memberKt =
        FrameAstHelpers.directIdentPacket(stream, selFrame)?.unescapedName
            ?: FrameAstHelpers.operationSymbolName(stream, selFrame)
    if (!bundle.isDocExample &&
        (
            rawRecvCode == "Channel::Factory" ||
                rawRecvCode == "Channel::factory" ||
                rawRecvCode.endsWith("::Channel::Factory") ||
                rawRecvCode.endsWith("::Channel::factory")
        ) &&
        memberKt in CHANNEL_STATIC_CONSTANTS
    ) {
        val channelType = rawRecvCode.removeSuffix("::Factory").removeSuffix("::factory")
        return "$channelType<void*>::$memberKt"
    }
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
            if (superCls == null && !bundle.isDocExample) {
                when (memberKt) {
                    "message" -> return "$recvCode::get_message()"
                    "cause" -> return "$recvCode::get_cause()"
                    "context" -> return "$recvCode::get_context()"
                    "coroutineContext" -> return "$recvCode::coroutine_context()"
                    "current" ->
                        if (effectiveRecvKt == "Worker") {
                            return "$recvCode::current"
                        } else if (isExternalCoroutineGetterProperty(
                                memberKt,
                                isUnlabeledThisRecv = false,
                                hasOwnField = false,
                            )
                        ) {
                            return "$recvCode::${CppNamingPolicy.toSnakeCase(memberKt)}()"
                        }
                    else ->
                        if (isExternalCoroutineGetterProperty(
                                memberKt,
                                isUnlabeledThisRecv = false,
                                hasOwnField = false,
                            )
                        ) {
                            return "$recvCode::${CppNamingPolicy.toSnakeCase(memberKt)}()"
                        }
                }
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
        if (!bundle.isDocExample && !isSuperReceiver && effectiveRecvKt == "Channel" && memberKt in CHANNEL_STATIC_CONSTANTS) {
            return "$recvCode<void*>::$memberKt"
        }
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
                if (bundle.isDocExample) {
                    return "$recvCode${op}size()"
                }
                includes.addSystem("cstdint")
                return "static_cast<std::int32_t>($recvCode${op}size())"
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
        "message" -> {
            if (!bundle.isDocExample && !(isUnlabeledThisRecv && hasOwnField)) {
                return emitFlexibleMemberPropertyAccess(recvCode, "get_message", "what", "message")
            }
        }
        "cause" -> {
            if (!bundle.isDocExample && !(isUnlabeledThisRecv && hasOwnField)) {
                return emitFlexibleMemberPropertyAccess(recvCode, "get_cause", "cause", "cause")
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
        "onSend",
        "onReceive",
        "onReceiveCatching",
        "onReceiveOrNull",
        "onAwait",
        "isClosedForSend",
        "isClosedForReceive",
        "coroutineContext",
        "subscriptionCount",
        "isCompleted",
        "isCancelled",
        "handled",
        "eventLoop",
        -> {
            if (isExternalCoroutineGetterProperty(memberKt, isUnlabeledThisRecv, hasOwnField)) {
                val op = if (isPtrReceiver) "->" else "."
                return "$recvCode$op${CppNamingPolicy.toSnakeCase(memberKt)}()"
            }
        }
        "classSimpleName", "hexAddress" -> {
            if (!bundle.isDocExample &&
                !hasOwnField &&
                bundle.symbols.propertiesByName[memberKt]?.receiverTypeFrameIndex == null
            ) {
                includes.addProject("kotlinx/coroutines/Debug.hpp")
                val nsPrefix =
                    if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                        ""
                    } else {
                        "kotlinx::coroutines::"
                    }
                return "$nsPrefix${CppNamingPolicy.toSnakeCase(memberKt)}($recvCode)"
            }
        }
        "coroutineName" -> {
            if (!bundle.isDocExample &&
                !hasOwnField &&
                bundle.symbols.propertiesByName[memberKt]?.receiverTypeFrameIndex == null
            ) {
                includes.addProject("kotlinx/coroutines/CoroutineContext.common.hpp")
                val nsPrefix =
                    if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                        ""
                    } else {
                        "kotlinx::coroutines::"
                    }
                return "${nsPrefix}coroutine_name($recvCode)"
            }
        }
        "job" -> {
            val recvSimple = inferExpressionSimpleTypeName(recvFrame)
            val effRecvSimple = recvSimple?.let { bundle.symbols.importAliases[it] ?: it }
            val recvHasOwnJob =
                sameRecvCls != null &&
                    (
                        findClassHierarchyProperty(sameRecvCls, "job") != null ||
                            findClassHierarchyFunction(sameRecvCls, "job", 0) != null
                    )
            val looksLikeCoroutineContext =
                effRecvSimple == "CoroutineContext" ||
                    effRecvSimple == "CombinedContext" ||
                    effRecvSimple == "EmptyCoroutineContext" ||
                    effRecvSimple == "CoroutineDispatcher" ||
                    effRecvSimple == "Element" ||
                    recvCode.endsWith("coroutine_context") ||
                    recvCode.endsWith("coroutine_context()") ||
                    recvCode.endsWith("get_coroutine_context()") ||
                    (
                        sameRecvCls == null &&
                            (
                                recvCode == "context" ||
                                    recvCode.endsWith("->context") ||
                                    recvCode.endsWith(".context") ||
                                    recvCode.endsWith("_context") ||
                                    recvCode.endsWith("_context()")
                            )
                    )
            if (!bundle.isDocExample &&
                !hasOwnField &&
                !recvHasOwnJob &&
                looksLikeCoroutineContext &&
                bundle.symbols.propertiesByName[memberKt]?.receiverTypeFrameIndex == null
            ) {
                includes.addProject("kotlinx/coroutines/Job.hpp")
                val shadowedInEnclosing =
                    enclosingClass?.let {
                        findClassHierarchyProperty(it, "job") != null ||
                            findClassHierarchyFunction(it, "job", 1) != null
                    } == true
                val nsPrefix =
                    if (!shadowedInEnclosing &&
                        bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")
                    ) {
                        ""
                    } else {
                        "kotlinx::coroutines::"
                    }
                return "${nsPrefix}job($recvCode)"
            }
        }
        "isActive" -> {
            if (isExternalCoroutineGetterProperty(memberKt, isUnlabeledThisRecv, hasOwnField)) {
                includes.addSystem("type_traits")
                return "([&](auto&& kxs_ar) -> bool { " +
                    "if constexpr (requires { kxs_ar->is_active(); }) return kxs_ar->is_active(); " +
                    "else if constexpr (requires { kxs_ar.is_active(); }) return kxs_ar.is_active(); " +
                    "else return is_active(kxs_ar); }($recvCode))"
            }
        }
    }

    // Check if `memberKt` is an extension property or rich-enum property declared in the same file!
    val sameFileProp = bundle.symbols.propertiesByName[memberKt]
    if (sameFileProp != null && sameFileProp.receiverTypeFrameIndex != null) {
        return "${sameFileProp.cppName}($recvCode)"
    }
    if (!bundle.isDocExample && memberKt == "isLazy" && !isUnlabeledThisRecv && !hasOwnField) {
        val qualIsLazy =
            if (lookupAccessibleClassByName("CoroutineStart") != null) {
                "is_lazy"
            } else {
                CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                    kotlinName = "isLazy",
                    cppBaseName = "is_lazy",
                    currentNs = bundle.symbols.namespaceSegments,
                    includes = includes,
                ) ?: "kotlinx::coroutines::is_lazy"
            }
        return "$qualIsLazy($recvCode)"
    }

    if (!bundle.isDocExample && memberKt == "result") {
        val uRecv = unwrapTransparentFrame(recvFrame)
        val recvCall =
            when (uRecv.nodeTypeId) {
                KtNodeTypes.CALL_EXPRESSION_ID -> uRecv
                KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID ->
                    FrameAstHelpers
                        .childFrames(stream, uRecv)
                        .getOrNull(1)
                        ?.let { unwrapTransparentFrame(it) }
                        ?.takeIf { it.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID }
                else -> null
            }
        val recvCallee = recvCall?.let { extractCalleeSimpleName(it) }
        if (recvCallee == "requestTermination" || recvCallee == "execute") {
            return "$recvCode.consume_result()"
        }
    }

    val op = if (isPtrReceiver) "->" else "."
    if (isUnlabeledThisRecv) {
        return "this->${resolveMemberFieldOrPropName(memberKt, recvFrame)}"
    }
    return "$recvCode$op${resolveMemberFieldOrPropName(memberKt, recvFrame)}"
}

internal fun CppExprAndStmtEmitter.isExternalCoroutineGetterProperty(
    memberKt: String,
    isUnlabeledThisRecv: Boolean,
    hasOwnField: Boolean,
): Boolean {
    if (bundle.isDocExample) return false
    if (isUnlabeledThisRecv && hasOwnField) return false
    if (bundle.symbols.propertiesByName[memberKt]?.receiverTypeFrameIndex != null) return false
    val sameFileHasBackedField =
        bundle.symbols.classesByFrame.values.any { c ->
            c.primaryConstructorParams.any {
                (it.isVal || it.isVar) && it.name == memberKt && !isBackedOverrideCtorParamExpr(c, it)
            } ||
                c.memberPropertyFrameIndices.any { pIdx ->
                    val p = bundle.symbols.propertiesByFrame[pIdx]
                    p != null && p.kotlinName == memberKt && !isGetterStyleClassProperty(c, p)
                }
        }
    return !sameFileHasBackedField
}

internal fun CppExprAndStmtEmitter.isSharedThisClass(kotlinName: String): Boolean =
    CppTypeTranslator.isSharedHierarchyType(kotlinName) ||
        kotlinName in bundle.symbols.sharedHierarchyTypeNames ||
        kotlinName == "ConcurrentLinkedListNode" ||
        kotlinName == "Segment" ||
        kotlinName == "LockFreeLinkedListNode"

internal fun CppExprAndStmtEmitter.emitFlexibleMemberPropertyAccess(
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

internal fun CppExprAndStmtEmitter.isBackedOverrideCtorParamExpr(
    cls: KtClassSymbol,
    cp: KtValueParamInfo,
): Boolean {
    if (cls.kind == KtClassKind.INTERFACE) return false
    if (!cp.isVal && !cp.isVar) return false
    val cpFlags = stream.frames[cp.frameIndex].flags
    return PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_OVERRIDE) &&
        !PacketFlags.hasFlag(cpFlags, PacketFlags.MOD_ABSTRACT)
}

internal fun CppExprAndStmtEmitter.hasCustomGetterBodyExpr(prop: KtPropertySymbol): Boolean {
    val getterIdx = prop.getterFrameIndex ?: return false
    val getterFrame = stream.frames[getterIdx]
    return FrameAstHelpers.firstChildFrame(stream, getterFrame, KtNodeTypes.BLOCK_ID) != null ||
        FrameAstHelpers.extractInitializerAfterEquals(stream, getterFrame) != null
}

internal fun CppExprAndStmtEmitter.isBackedOverrideMemberPropExpr(
    cls: KtClassSymbol,
    prop: KtPropertySymbol,
): Boolean {
    if (cls.kind == KtClassKind.INTERFACE) return false
    if (prop.isConst || hasCustomGetterBodyExpr(prop)) return false
    return PacketFlags.hasFlag(prop.flags, PacketFlags.MOD_OVERRIDE) &&
        !PacketFlags.hasFlag(prop.flags, PacketFlags.MOD_ABSTRACT)
}

internal fun CppExprAndStmtEmitter.isCancellationExceptionLikeClass(
    cls: KtClassSymbol?,
    visited: MutableSet<Int> = HashSet(),
): Boolean {
    if (cls == null || !visited.add(cls.frameIndex)) return false
    if (cls.kotlinName in CANCELLATION_EXCEPTION_CLASS_NAMES) return true
    val allClasses = bundle.symbols.classesByFrame.values
    for (trIdx in cls.superTypeFrameIndices) {
        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx]) ?: continue
        if (simple in CANCELLATION_EXCEPTION_CLASS_NAMES) return true
        val sameFileSuper = allClasses.firstOrNull { it.kotlinName == simple }
        if (sameFileSuper != null && isCancellationExceptionLikeClass(sameFileSuper, visited)) return true
    }
    return false
}

internal fun CppExprAndStmtEmitter.classInheritsContinuation(
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

internal fun CppExprAndStmtEmitter.resolveClassPropGetterCppName(
    ownerCls: KtClassSymbol?,
    propKtName: String,
    defaultCppName: String,
): String =
    if (propKtName == "context" && classInheritsContinuation(ownerCls)) {
        "get_context"
    } else {
        defaultCppName
    }

internal fun CppExprAndStmtEmitter.isGetterStyleClassProperty(
    ownerCls: KtClassSymbol,
    prop: KtPropertySymbol,
): Boolean =
    hasCustomGetterBodyExpr(prop) ||
        ownerCls.kind == KtClassKind.INTERFACE ||
        (PacketFlags.hasFlag(prop.flags, PacketFlags.MOD_ABSTRACT) && prop.initializerFrameIndex == null) ||
        isBackedOverrideMemberPropExpr(ownerCls, prop)

internal fun CppExprAndStmtEmitter.classDeclaresMethod(
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

internal fun CppExprAndStmtEmitter.resolveClassOwnPropertyAccess(
    cls: KtClassSymbol,
    ktName: String,
    isUnqualifiedCurrentThis: Boolean,
): String? {
    val ctorProp = cls.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == ktName }
    if (ctorProp != null) {
        val vpFrame = stream.frames[ctorProp.frameIndex]
        val isPriv = PacketFlags.hasFlag(vpFrame.flags, PacketFlags.MOD_PRIVATE)
        val isBackedOverride = isBackedOverrideCtorParamExpr(cls, ctorProp)
        return when {
            cls.kind == KtClassKind.VALUE_CLASS && isUnqualifiedCurrentThis ->
                CppNamingPolicy.toPrivateFieldName(ctorProp.cppName)
            cls.kind == KtClassKind.VALUE_CLASS ->
                "${resolveClassPropGetterCppName(cls, ctorProp.name, ctorProp.cppName)}()"
            isPriv || (isUnqualifiedCurrentThis && isBackedOverride) ->
                CppNamingPolicy.toPrivateFieldName(ktName)
            isBackedOverride ->
                "${resolveClassPropGetterCppName(cls, ctorProp.name, ctorProp.cppName)}()"
            else -> ctorProp.cppName
        }
    }
    for (mPropIdx in cls.memberPropertyFrameIndices) {
        val mProp = bundle.symbols.propertiesByFrame[mPropIdx] ?: continue
        if (mProp.kotlinName == ktName) {
            val suffix = if (isGetterStyleClassProperty(cls, mProp)) "()" else ""
            val getterName =
                if (suffix.isNotEmpty()) {
                    resolveClassPropGetterCppName(cls, mProp.kotlinName, mProp.cppName)
                } else {
                    mProp.cppName
                }
            return "$getterName$suffix"
        }
    }
    return null
}

internal fun CppExprAndStmtEmitter.resolveSuperclassesPropertyAccess(
    cls: KtClassSymbol,
    ktName: String,
    visited: MutableSet<Int> = hashSetOf(cls.frameIndex),
): String? {
    for (superIdx in cls.superTypeFrameIndices) {
        val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
        val superCls = lookupAccessibleClassByName(superSimple) ?: continue
        resolveClassHierarchyPropertyAccess(superCls, ktName, isUnqualifiedSelfStart = false, visited)
            ?.let { return it }
    }
    return null
}

internal fun CppExprAndStmtEmitter.resolveClassHierarchyPropertyAccess(
    startCls: KtClassSymbol,
    ktName: String,
    isUnqualifiedSelfStart: Boolean,
    visited: MutableSet<Int> = HashSet(),
): String? {
    if (!visited.add(startCls.frameIndex)) return null
    resolveClassOwnPropertyAccess(startCls, ktName, isUnqualifiedSelfStart)?.let { return it }
    return resolveSuperclassesPropertyAccess(startCls, ktName, visited)
}

internal fun CppExprAndStmtEmitter.resolveUnqualifiedPropertyAccess(ktName: String): String? {
    val recvSub = receiverSubstitution
    val extRecvCls = extensionReceiverClass
    if (recvSub != null && extRecvCls != null && ktName !in anonObjectOwnProps) {
        resolveClassHierarchyPropertyAccess(extRecvCls, ktName, isUnqualifiedSelfStart = false)
            ?.let { memberAccess ->
                val op = if (extensionReceiverIsPointer) "->" else "."
                return "$recvSub$op$memberAccess"
            }
    }

    val extSimple = extensionReceiverSimpleName
    if (recvSub != null && extRecvCls == null && extSimple != null) {
        if (!bundle.isDocExample &&
            ktName == "size" &&
            (extSimple == "Array" || extSimple in DIRECT_ITERABLE_VALUE_TYPES)
        ) {
            includes.addSystem("cstdint")
            val op = if (extensionReceiverIsPointer) "->" else "."
            return "static_cast<std::int32_t>($recvSub${op}size())"
        }
        if (ktName in KNOWN_EXTERNAL_RECEIVER_PROPERTIES[extSimple].orEmpty()) {
            val op = if (extensionReceiverIsPointer) "->" else "."
            return when {
                extSimple == "ScopeCoroutine" && ktName == "uCont" -> "$recvSub${op}u_cont"
                extSimple == "ScopeCoroutine" && ktName == "context" -> "$recvSub${op}get_context()"
                extSimple == "CoroutineScope" && ktName == "coroutineContext" ->
                    "$recvSub${op}coroutine_context()"
                (extSimple == "Continuation" || extSimple == "CancellableContinuation") && ktName == "context" ->
                    "$recvSub${op}get_context()"
                extSimple == "Duration" && ktName == "inWholeMilliseconds" ->
                    "$recvSub${op}in_whole_milliseconds()"
                extSimple == "Duration" && ktName in DURATION_WHOLE_UNITS -> {
                    includes.addProject("kotlin/time/Duration.hpp")
                    val unitEnum = ktName.removePrefix("inWhole").uppercase()
                    "$recvSub${op}to_long(kotlin::time::DurationUnit::$unitEnum)"
                }
                else -> "$recvSub$op${CppNamingPolicy.toSnakeCase(ktName)}()"
            }
        }
    }

    if (enclosingClass != null) {
        val enumEntry = enclosingClass.enumEntries.firstOrNull { it.kotlinName == ktName }
        if (enumEntry != null) {
            return "${enclosingClass.cppName}::${enumEntry.cppName}"
        }
        var cur: KtClassSymbol? = enclosingClass
        var prefix = ""
        while (cur != null) {
            resolveClassOwnPropertyAccess(cur, ktName, isUnqualifiedCurrentThis = prefix.isEmpty())
                ?.let { access ->
                    return "$prefix$access"
                }
            resolveSuperclassesPropertyAccess(cur, ktName)?.let { access ->
                val effectivePrefix =
                    if (prefix.isEmpty() &&
                        !bundle.isDocExample &&
                        cur.typeParams.isNotEmpty() &&
                        cur.superTypeFrameIndices.isNotEmpty()
                    ) {
                        "this->"
                    } else {
                        prefix
                    }
                return "$effectivePrefix$access"
            }
            if (!cur.isInner) break
            cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            prefix += "outer_->"
        }
        if (!bundle.isDocExample &&
            (ktName == "message" || ktName == "cause") &&
            isCancellationExceptionLikeClass(enclosingClass)
        ) {
            return if (ktName == "message") "get_message()" else "get_cause()"
        }
        if (!bundle.isDocExample &&
            ktName == "context" &&
            classInheritsContinuation(enclosingClass)
        ) {
            return "this->get_context()"
        }
    }
    return null
}

internal fun CppExprAndStmtEmitter.resolveMemberFieldOrPropName(
    memberKt: String,
    recvFrame: PacketFrame? = null,
): String {
    val isUnqualifiedRecvThis =
        recvFrame == null || unwrapTransparentFrame(recvFrame).nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID
    val recvCls = recvFrame?.let { inferExpressionSimpleTypeName(it) }?.let { resolveClassOrTypeParamBound(it) }
    if (recvCls != null) {
        resolveClassHierarchyPropertyAccess(
            recvCls,
            memberKt,
            isUnqualifiedSelfStart = recvCls == enclosingClass && isUnqualifiedRecvThis,
        )?.let { return it }
    }
    var cur: KtClassSymbol? = enclosingClass
    while (cur != null) {
        resolveClassHierarchyPropertyAccess(
            cur,
            memberKt,
            isUnqualifiedSelfStart = cur == enclosingClass && isUnqualifiedRecvThis,
        )?.let { return it }
        cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
    }
    var anyHasRawField = false
    var firstGetterMatch: Pair<KtClassSymbol, KtPropertySymbol>? = null
    for (cls in bundle.symbols.classesByFrame.values) {
        if (cls.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == memberKt }) {
            anyHasRawField = true
            break
        }
        for (pIdx in cls.memberPropertyFrameIndices) {
            val prop = bundle.symbols.propertiesByFrame[pIdx] ?: continue
            if (prop.kotlinName != memberKt) continue
            if (!isGetterStyleClassProperty(cls, prop)) {
                anyHasRawField = true
                break
            } else if (firstGetterMatch == null) {
                firstGetterMatch = cls to prop
            }
        }
        if (anyHasRawField) break
    }
    if (!anyHasRawField && firstGetterMatch != null) {
        val (owner, prop) = firstGetterMatch
        return "${resolveClassPropGetterCppName(owner, prop.kotlinName, prop.cppName)}()"
    }
    return CppNamingPolicy.toSnakeCase(memberKt)
}

internal fun CppExprAndStmtEmitter.stripBalancedOuterParens(s: String): String {
    var cur = s.trim()
    while (cur.startsWith('(') && cur.endsWith(')')) {
        var depth = 0
        var closesAtEnd = true
        for (i in 0 until cur.length) {
            when (cur[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && i < cur.length - 1) {
                        closesAtEnd = false
                        break
                    }
                }
            }
        }
        if (!closesAtEnd || depth != 0) break
        cur = cur.substring(1, cur.length - 1).trim()
    }
    return cur
}

internal fun CppExprAndStmtEmitter.isCppPtrOrSharedType(cppType: String): Boolean =
    cppType.endsWith("*") || cppType.startsWith("std::shared_ptr<")

internal fun CppExprAndStmtEmitter.isTypeFramePointerOrSharedPtr(typeFrameIdx: Int?): Boolean {
    if (typeFrameIdx == null) return false
    val tf = stream.frames[typeFrameIdx]
    val rawSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tf)
    val eff = rawSimple?.let { bundle.symbols.importAliases[it] ?: it }
    if (eff != null && eff in bundle.symbols.sharedPtrTypeAliases) return true
    return isCppPtrOrSharedType(translateType(tf, CppTypePosition.VALUE))
}

internal fun CppExprAndStmtEmitter.isInitSharedHierarchyCall(initIdx: Int): Boolean {
    val initF = unwrapTransparentFrame(stream.frames[initIdx])
    if (initF.nodeTypeId != KtNodeTypes.CALL_EXPRESSION_ID) return false
    val c = extractCalleeSimpleName(initF)
    val eff = bundle.symbols.importAliases[c] ?: c
    return eff in bundle.symbols.sharedHierarchyTypeNames || CppTypeTranslator.isSharedHierarchyType(eff)
}

internal fun CppExprAndStmtEmitter.isClassHierarchyPropPointerOrSharedPtr(
    cls: KtClassSymbol,
    propName: String,
    allowAllCtorParams: Boolean = false,
    visited: MutableSet<Int> = HashSet(),
): Boolean {
    if (!visited.add(cls.frameIndex)) return false
    val cp =
        cls.primaryConstructorParams.firstOrNull {
            (allowAllCtorParams || it.isVal || it.isVar) && it.name == propName
        }
    if (cp?.typeFrameIndex != null) {
        return isTypeFramePointerOrSharedPtr(cp.typeFrameIndex)
    }
    for (pIdx in cls.memberPropertyFrameIndices) {
        val mp = bundle.symbols.propertiesByFrame[pIdx] ?: continue
        if (mp.kotlinName == propName) {
            if (mp.returnTypeFrameIndex != null) {
                return isTypeFramePointerOrSharedPtr(mp.returnTypeFrameIndex)
            }
            if (mp.initializerFrameIndex != null) {
                return isInitSharedHierarchyCall(mp.initializerFrameIndex)
            }
            return false
        }
    }
    for (superIdx in cls.superTypeFrameIndices) {
        val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
        val superCls = lookupAccessibleClassByName(superSimple) ?: continue
        if (isClassHierarchyPropPointerOrSharedPtr(superCls, propName, allowAllCtorParams = false, visited)) {
            return true
        }
    }
    return false
}

internal fun CppExprAndStmtEmitter.isCallReturnPointerOrSharedPtr(
    callFrame: PacketFrame,
    recvFrame: PacketFrame?,
): Boolean {
    val calleeKt = extractCalleeSimpleName(callFrame)
    val eff = bundle.symbols.importAliases[calleeKt] ?: calleeKt
    if (!bundle.isDocExample && eff in DISPATCHER_OR_EVENT_LOOP_FACTORY_NAMES) {
        return true
    }
    val sameCls = lookupAccessibleClassByName(calleeKt) ?: lookupAccessibleClassByName(eff)
    if (recvFrame == null &&
        sameCls != null &&
        sameCls.kind != KtClassKind.INTERFACE &&
        !PacketFlags.hasFlag(stream.frames[sameCls.frameIndex].flags, PacketFlags.MOD_ABSTRACT) &&
        (eff in bundle.symbols.sharedHierarchyTypeNames || CppTypeTranslator.isSharedHierarchyType(eff))
    ) {
        return true
    }
    val argCount = extractCallArgFrames(callFrame).size
    val recvCls = recvFrame?.let { inferExpressionSimpleTypeName(it) }?.let { resolveClassOrTypeParamBound(it) }
    val targetFn =
        recvCls?.let { findClassHierarchyFunction(it, calleeKt, argCount) }
            ?: enclosingClass?.let { findClassHierarchyFunction(it, calleeKt, argCount) }
            ?: bundle.symbols.functionsByName[calleeKt]
                ?.let { fns -> fns.firstOrNull { it.valueParams.size == argCount } ?: fns.firstOrNull() }
            ?: bundle.symbols.functionsByName[eff]
                ?.let { fns -> fns.firstOrNull { it.valueParams.size == argCount } ?: fns.firstOrNull() }
    val retIdx = targetFn?.returnTypeFrameIndex ?: return false
    if (isTypeFramePointerOrSharedPtr(retIdx)) return true
    val retSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[retIdx]) ?: return false
    val tpUb =
        targetFn.typeParams.firstOrNull { it.name == retSimple }?.upperBoundFrameIndex
            ?: recvCls?.typeParams?.firstOrNull { it.name == retSimple }?.upperBoundFrameIndex
    return tpUb != null && isTypeFramePointerOrSharedPtr(tpUb)
}

internal fun CppExprAndStmtEmitter.isPointerOrSharedPtrReceiver(
    recvFrame: PacketFrame,
    renderedRecv: String,
): Boolean {
    if (renderedRecv == "this") return true
    val stripped = stripBalancedOuterParens(renderedRecv)
    if (stripped.startsWith("kxs_as_cast<std::shared_ptr<") ||
        stripped.startsWith("kxs_safe_as_cast<std::shared_ptr<") ||
        stripped.startsWith("std::make_shared<") ||
        stripped.startsWith("std::shared_ptr(") ||
        stripped.startsWith("std::shared_ptr<") ||
        stripped.startsWith("kxs_shared_from_this(") ||
        stripped.startsWith("kxs_intercepted(") ||
        stripped.startsWith("kxs_create_coroutine_unintercepted(") ||
        stripped.startsWith("intercepted(") ||
        stripped.startsWith("kotlin::coroutines::intrinsics::intercepted(") ||
        stripped.startsWith("intrinsics::intercepted(")
    ) {
        return true
    }
    val u = unwrapTransparentFrame(recvFrame)
    when (u.nodeTypeId) {
        KtNodeTypes.POSTFIX_EXPRESSION_ID -> {
            val opRef = FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.OPERATION_REFERENCE_ID)
            if (opRef != null && FrameAstHelpers.operationTokenId(stream, opRef) == KtTokens.EXCLEXCL_ID) {
                val operand =
                    FrameAstHelpers
                        .childFrames(stream, u)
                        .firstOrNull { it.nodeTypeId != KtNodeTypes.OPERATION_REFERENCE_ID }
                if (operand != null && isPointerOrSharedPtrReceiver(operand, renderedRecv)) return true
            }
        }
        KtNodeTypes.BINARY_EXPRESSION_ID -> {
            val ch = FrameAstHelpers.childFrames(stream, u)
            if (ch.size >= 3 && FrameAstHelpers.operationTokenId(stream, ch[1]) == KtTokens.ELVIS_ID) {
                if (isPointerOrSharedPtrReceiver(ch[0], renderedRecv) ||
                    isPointerOrSharedPtrReceiver(ch[2], renderedRecv)
                ) {
                    return true
                }
            }
        }
        KtNodeTypes.CALL_EXPRESSION_ID -> {
            if (isCallReturnPointerOrSharedPtr(u, recvFrame = null)) return true
        }
        KtNodeTypes.IF_ID -> {
            val thenExpr = extractBranchTailExpr(FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.THEN_ID))
            val elseExpr = extractBranchTailExpr(FrameAstHelpers.firstChildFrame(stream, u, KtNodeTypes.ELSE_ID))
            return (thenExpr != null && isPointerOrSharedPtrReceiver(thenExpr, "")) ||
                (elseExpr != null && isPointerOrSharedPtrReceiver(elseExpr, ""))
        }
        KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID -> {
            val ch = FrameAstHelpers.childFrames(stream, u)
            if (ch.size >= 2) {
                val lhs = ch[0]
                val sel = unwrapTransparentFrame(ch[1])
                if (sel.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                    if (isCallReturnPointerOrSharedPtr(sel, lhs)) return true
                } else if (sel.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                    val memberKt = FrameAstHelpers.directIdentPacket(stream, sel)?.unescapedName
                    if (memberKt == "value") {
                        resolveAtomicFieldForExpr(lhs)?.let { af ->
                            if (af.explicitElementTypeFrameIndex != null &&
                                isTypeFramePointerOrSharedPtr(af.explicitElementTypeFrameIndex)
                            ) {
                                return true
                            }
                            if (af.inferredElementCppType != null && isCppPtrOrSharedType(af.inferredElementCppType)) {
                                return true
                            }
                            if (af.initExprFrameIndex != null &&
                                isPointerOrSharedPtrReceiver(stream.frames[af.initExprFrameIndex], "")
                            ) {
                                return true
                            }
                        }
                    }
                    if (memberKt != null) {
                        if (!bundle.isDocExample && memberKt == "eventLoop") {
                            return true
                        }
                        val lhsCls =
                            inferExpressionSimpleTypeName(lhs)?.let { resolveClassOrTypeParamBound(it) }
                                ?: if (unwrapTransparentFrame(lhs).nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID) {
                                    enclosingClass
                                } else {
                                    null
                                }
                        if (lhsCls != null && isClassHierarchyPropPointerOrSharedPtr(lhsCls, memberKt)) {
                            return true
                        }
                        val extProp = bundle.symbols.propertiesByName[memberKt]
                        if (extProp != null &&
                            extProp.receiverTypeFrameIndex != null &&
                            isTypeFramePointerOrSharedPtr(extProp.returnTypeFrameIndex)
                        ) {
                            return true
                        }
                    }
                }
            }
        }
        KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
            val name = FrameAstHelpers.directIdentPacket(stream, u)?.unescapedName ?: return false
            if (isLocalPolyVar(name)) return true
            var cur: KtClassSymbol? = enclosingClass
            while (cur != null) {
                if (isClassHierarchyPropPointerOrSharedPtr(cur, name, allowAllCtorParams = true)) return true
                if (!cur.isInner) break
                cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            }
            val topProp = bundle.symbols.propertiesByName[name]
            if (topProp?.receiverTypeFrameIndex == null) {
                if (topProp?.returnTypeFrameIndex != null) {
                    if (isTypeFramePointerOrSharedPtr(topProp.returnTypeFrameIndex)) return true
                } else if (topProp?.initializerFrameIndex != null &&
                    isInitSharedHierarchyCall(topProp.initializerFrameIndex)
                ) {
                    return true
                }
            }
        }
    }
    return false
}

internal fun CppExprAndStmtEmitter.emitStringToBooleanExpr(recvExpr: String): String {
    includes.addSystem("cctype")
    includes.addSystem("string")
    return "([&](const std::string& kxs_s) -> bool { " +
        "if (kxs_s.size() != 4) return false; " +
        "return std::tolower(static_cast<unsigned char>(kxs_s[0])) == 't' && " +
        "std::tolower(static_cast<unsigned char>(kxs_s[1])) == 'r' && " +
        "std::tolower(static_cast<unsigned char>(kxs_s[2])) == 'u' && " +
        "std::tolower(static_cast<unsigned char>(kxs_s[3])) == 'e'; }($recvExpr))"
}

internal fun CppExprAndStmtEmitter.emitForEachExpr(
    recvExpr: String,
    lambdaExpr: String,
): String {
    includes.addSystem("type_traits")
    val feRecv = freshTemp("kxs_fe_r")
    return "([&]() { auto&& $feRecv = $recvExpr; " +
        "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype($feRecv)>> || " +
        "requires { $feRecv.operator->(); }) { " +
        "for (auto&& kxs_fe : (*$feRecv)) { ($lambdaExpr)(kxs_fe); } " +
        "} else { " +
        "for (auto&& kxs_fe : $feRecv) { ($lambdaExpr)(kxs_fe); } " +
        "} }())"
}

internal fun CppExprAndStmtEmitter.emitScopeApplyOrRunCall(
    calleeKt: String,
    receiverFrame: PacketFrame?,
    receiverCode: String,
    argFrames: List<PacketFrame>,
    args: Lazy<List<String>>,
): String? {
    if ((calleeKt != "apply" && calleeKt != "run") || argFrames.size != 1) return null
    val rawArg0 = unwrapTransparentFrame(argFrames[0])
    if (rawArg0.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID) {
        val fnLit = FrameAstHelpers.firstChildFrame(stream, rawArg0, KtNodeTypes.FUNCTION_LITERAL_ID)
        val lambdaBody =
            if (fnLit != null) {
                FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
            } else {
                rawArg0
            }
        val iifeRetSuffix = if (calleeKt == "run") iifeAnyRetSuffix() else ""
        return withinExpressionIife {
            val recvTemp = freshTemp(if (calleeKt == "apply") "kxs_apply" else "kxs_run")
            val nestedEmitter = buildInlineScopeEmitter(receiverFrame, receiverCode, recvTemp)
            val bodyOut =
                StringBuilder().apply {
                    append("    auto&& ")
                        .append(recvTemp)
                        .append(" = ")
                        .append(receiverCode)
                        .append(";\n")
                    nestedEmitter.appendInlineLambdaBodyStatements(
                        this,
                        lambdaBody,
                        "    ",
                        targetVar = null,
                        targetReturn = calleeKt == "run",
                    )
                    if (calleeKt == "apply") {
                        append("    return ").append(recvTemp).append(";\n")
                    }
                }
            synthCounter = nestedEmitter.synthCounter
            "([&]()$iifeRetSuffix {\n$bodyOut}())"
        }
    }
    val renderedArgs = args.value
    if (renderedArgs.size != 1) return null
    return if (calleeKt == "apply") {
        val recvTemp = freshTemp("kxs_apply")
        "([&]() { auto&& $recvTemp = $receiverCode; (${renderedArgs[0]})($recvTemp); return $recvTemp; }())"
    } else {
        "(${renderedArgs[0]})($receiverCode)"
    }
}

internal fun CppExprAndStmtEmitter.emitSafeAccessExpr(safeFrame: PacketFrame): String {
    val parts = FrameAstHelpers.childFrames(stream, safeFrame)
    require(parts.size == 2) {
        "${stream.sourcePath}:${safeFrame.span.startLine}:${safeFrame.span.startColumn}: malformed SAFE_ACCESS_EXPRESSION"
    }
    val lhsCode = withExpectedTargetType(null) { emitExpression(parts[0]) }
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
    includes.addSystem("exception")
    includes.addSystem("optional")
    includes.addSystem("type_traits")

    includes.needsNullUnwrapDef = true
    includes.addSystem("any")
    includes.addSystem("utility")

    val safeInvokeExpr: String =
        withinExpressionIife {
            if (selFrame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
                val calleeKt = extractCalleeSimpleName(selFrame)
                val argFrames = extractCallArgFrames(selFrame)
                val args = lazy(LazyThreadSafetyMode.NONE) { extractCallArgExpressions(selFrame) }
                val joinedArgs by lazy(LazyThreadSafetyMode.NONE) { args.value.joinToString(", ") }
                val sameFileExt =
                    bundle.symbols.functionsByName[calleeKt]
                        ?.firstOrNull { it.receiverTypeFrameIndex != null && isAccessibleExtensionFn(it) }
                when {
                    calleeKt == "invoke" ->
                        "([&](auto&& kxs_sa_ir) -> decltype(auto) { " +
                            "if constexpr (requires { kxs_sa_ir->invoke($joinedArgs); }) return kxs_sa_ir->invoke($joinedArgs); " +
                            "else if constexpr (requires { kxs_sa_ir.invoke($joinedArgs); }) return kxs_sa_ir.invoke($joinedArgs); " +
                            "else if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_ir)>> || requires { kxs_sa_ir.operator->(); }) return (*kxs_sa_ir)($joinedArgs); " +
                            "else return kxs_sa_ir($joinedArgs); }(kxs_sa_r))"
                    calleeKt == "let" && argFrames.size == 1 ->
                        "(${args.value[0]})(kxs_sa_r)"
                    calleeKt == "also" && argFrames.size == 1 ->
                        "([&]() { (${args.value[0]})(kxs_sa_r); return kxs_sa_r; }())"
                    (calleeKt == "apply" || calleeKt == "run") && argFrames.size == 1 ->
                        emitScopeApplyOrRunCall(calleeKt, parts[0], "kxs_sa_r", argFrames, args)
                            ?: "(${args.value[0]})(kxs_sa_r)"
                    calleeKt == "forEach" && argFrames.size == 1 ->
                        emitForEachExpr("kxs_sa_r", args.value[0])
                    calleeKt == "toBoolean" && argFrames.isEmpty() ->
                        emitStringToBooleanExpr("kxs_sa_r")
                    calleeKt == "isEmpty" && argFrames.isEmpty() ->
                        "([&](auto&& kxs_sa_er) { " +
                            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_er)>> || " +
                            "requires { kxs_sa_er.operator->(); }) return kxs_sa_er->empty(); " +
                            "else return kxs_sa_er.empty(); }(kxs_sa_r))"
                    calleeKt == "isNotEmpty" && argFrames.isEmpty() ->
                        "(![&](auto&& kxs_sa_er) { " +
                            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_er)>> || " +
                            "requires { kxs_sa_er.operator->(); }) return kxs_sa_er->empty(); " +
                            "else return kxs_sa_er.empty(); }(kxs_sa_r))"
                    calleeKt == "add" ->
                        "([&](auto&& kxs_sa_ar) -> decltype(auto) { " +
                            "if constexpr (requires { kxs_sa_ar->push_back($joinedArgs); }) " +
                            "return kxs_sa_ar->push_back($joinedArgs); " +
                            "else if constexpr (requires { kxs_sa_ar.push_back($joinedArgs); }) " +
                            "return kxs_sa_ar.push_back($joinedArgs); " +
                            "else if constexpr (requires { kxs_sa_ar->add($joinedArgs); }) " +
                            "return kxs_sa_ar->add($joinedArgs); " +
                            "else return kxs_sa_ar.add($joinedArgs); }(kxs_sa_r))"
                    calleeKt == "toString" && argFrames.isEmpty() ->
                        "([&](auto&& kxs_sa_sr) { if constexpr (requires { kxs_sa_sr->to_string(); }) " +
                            "return kxs_sa_sr->to_string(); else return kxs_sa_sr.to_string(); }(kxs_sa_r))"
                    calleeKt == "hashCode" && argFrames.isEmpty() ->
                        emitHashCodeExpr("kxs_sa_r")
                    calleeKt == "get" && argFrames.size == 1 -> {
                        val arg0 = args.value[0]
                        "([&](auto&& kxs_sa_gr) -> decltype(auto) { " +
                            "if constexpr (requires { kxs_sa_gr->get($arg0); }) return kxs_sa_gr->get($arg0); " +
                            "else if constexpr (requires { kxs_sa_gr.get($arg0); }) return kxs_sa_gr.get($arg0); " +
                            "else if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_gr)>> || " +
                            "requires { kxs_sa_gr.operator->(); }) return (*kxs_sa_gr)[$arg0]; " +
                            "else return kxs_sa_gr[$arg0]; }(kxs_sa_r))"
                    }
                    sameFileExt != null -> {
                        val allArgs = listOf("kxs_sa_r") + args.value
                        "${formatExtensionFnCallTarget(sameFileExt)}(${allArgs.joinToString(", ")})"
                    }
                    else ->
                        tryEmitCoroutineIntrinsicExtCall(calleeKt, "kxs_sa_r") { args.value } ?: run {
                            val sn = CppNamingPolicy.toSnakeCase(calleeKt)
                            "([&](auto&& kxs_sa_cr) -> decltype(auto) { " +
                                "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_cr)>> || " +
                                "requires { kxs_sa_cr.operator->(); }) return kxs_sa_cr->$sn($joinedArgs); " +
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
                        "([&](auto&& kxs_sa_pr) { if constexpr (requires { kxs_sa_pr->size(); }) " +
                            "return kxs_sa_pr->size(); else return kxs_sa_pr.size(); }(kxs_sa_r))"
                    else -> {
                        val field = resolveMemberFieldOrPropName(propKt, parts[0])
                        "([&](auto&& kxs_sa_pr) -> decltype(auto) { " +
                            "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sa_pr)>> || " +
                            "requires { kxs_sa_pr.operator->(); }) return kxs_sa_pr->$field; " +
                            "else return kxs_sa_pr.$field; }(kxs_sa_r))"
                    }
                }
            }
        }

    return "([&](auto&& $temp) { " +
        "using KxsSaRawT = std::remove_cvref_t<decltype($temp)>; " +
        "const bool kxs_has = [&]() -> bool { " +
        "if constexpr (std::is_same_v<KxsSaRawT, std::any>) " +
        "return $temp.has_value() && $temp.type() != typeid(std::nullptr_t); " +
        "else return static_cast<bool>($temp); }(); " +
        "auto kxs_sa_u = [](auto&& kxs_v) -> decltype(auto) { " +
        "if constexpr (std::is_same_v<std::remove_cvref_t<decltype(kxs_v)>, std::exception_ptr>) " +
        "return kxs_v; else return kxs_unwrap_opt(kxs_v); }; " +
        "auto kxs_sa_fn = [&](auto&& kxs_sa_r) -> decltype(auto) { return $safeInvokeExpr; }; " +
        "using KxsSaRes = decltype(kxs_sa_fn(kxs_sa_u($temp))); " +
        "if constexpr (std::is_void_v<KxsSaRes>) { if (kxs_has) { kxs_sa_fn(kxs_sa_u($temp)); } } " +
        "else if constexpr (std::is_pointer_v<std::decay_t<KxsSaRes>> || " +
        "std::is_same_v<std::decay_t<KxsSaRes>, std::exception_ptr> || " +
        "std::is_same_v<std::decay_t<KxsSaRes>, std::any> || " +
        "requires { typename std::decay_t<KxsSaRes>::element_type; } || " +
        "requires { typename std::decay_t<KxsSaRes>::value_type; " +
        "std::declval<std::decay_t<KxsSaRes>&>().has_value(); }) { " +
        "return kxs_has ? kxs_sa_fn(kxs_sa_u($temp)) : std::decay_t<KxsSaRes>{}; " +
        "} else { " +
        "return kxs_has ? std::optional<std::decay_t<KxsSaRes>>(kxs_sa_fn(kxs_sa_u($temp))) " +
        ": std::nullopt; " +
        "} }($lhsCode))"
}
