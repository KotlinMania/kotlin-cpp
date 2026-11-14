package io.github.kotlinmania.kotlincpp.packet

import io.github.kotlinmania.antlr4.ParserRuleContext
import io.github.kotlinmania.antlr4.tree.ParseTree
import io.github.kotlinmania.antlr4.tree.TerminalNode
import io.github.kotlinmania.kotlincpp.grammar.KotlinLexer
import io.github.kotlinmania.kotlincpp.grammar.KotlinParser

/**
 * Tokenizes and parses Kotlin source via the pure-KMP ANTLR4 Kotlin specification grammar
 * ([KotlinLexer] + [KotlinParser]), associates every significant packet with its universal
 * `syn-kotlin` / `proc-macro2-kotlin` token representation, and builds a lossless hierarchical
 * [PacketStream] whose leaf [SyntaxPacket] tape partitions [source] byte-for-byte.
 */
public fun buildKotlinPacketStream(
    source: String,
    sourcePath: String = "<memory>",
): PacketStream {
    val parsed = KotlinPacketLexing.lexAndParse(source, sourcePath)
    val builder = KotlinFrameTreeBuilder(parsed, sourcePath)
    val stream = builder.buildStream()
    check(stream.renderRawLossless() == source) {
        "$sourcePath: lossless packet render mismatch"
    }
    verifyExactPacketPartition(stream)
    return stream
}

internal fun buildKotlinPacketStreamOrNull(
    source: String,
    sourcePath: String = "<doc>",
): PacketStream? {
    if (source.contains("// port-lint: ignore")) return null
    val parsed = KotlinPacketLexing.lexAndParseOrNull(source, sourcePath) ?: return null
    val builder = KotlinFrameTreeBuilder(parsed, sourcePath)
    val stream = builder.buildStream()
    if (stream.renderRawLossless() != source) return null
    return stream
}

private class FrameDraft(
    val nodeTypeId: Int,
    val firstPacketIndex: Int,
    val lastPacketIndex: Int,
    val children: List<FrameDraft> = emptyList(),
    val flags: Long = PacketFlags.NONE,
    val semanticInfo: FrameSemanticInfo? = null,
)

private class KotlinFrameTreeBuilder(
    private val unit: ParsedKotlinUnit,
    private val sourcePath: String,
) {
    private val packets: List<SyntaxPacket> = unit.packets
    private val frames = ArrayList<PacketFrame>(64)

    fun buildStream(): PacketStream {
        val rootDraft = buildFileDraft(unit.fileCtx)
        val rootIdx = materialize(rootDraft, parentFrameIndex = -1)
        return PacketStream(
            sourcePath = sourcePath,
            packets = packets,
            frames = frames,
            rootFrameIndex = rootIdx,
        )
    }

    private fun materialize(draft: FrameDraft, parentFrameIndex: Int): Int {
        val thisIndex = frames.size
        val placeholderSpan =
            if (draft.firstPacketIndex <= draft.lastPacketIndex && packets.isNotEmpty()) {
                unit.offsetTable.spanOf(
                    packets[draft.firstPacketIndex].span.startOffset,
                    packets[draft.lastPacketIndex].span.endOffset,
                )
            } else {
                unit.offsetTable.spanOf(0, 0)
            }

        // Reserve slot before materializing children so parent indices are topologically ordered.
        frames.add(
            PacketFrame(
                frameIndex = thisIndex,
                nodeTypeId = draft.nodeTypeId,
                debugName = KtNodeTypes.debugName(draft.nodeTypeId),
                firstPacketIndex = draft.firstPacketIndex,
                lastPacketIndex = draft.lastPacketIndex,
                span = placeholderSpan,
                parentFrameIndex = parentFrameIndex,
                rawChildNodeRefs = IntArray(0),
                flags = draft.flags,
                semanticInfo = draft.semanticInfo,
            ),
        )

        if (draft.firstPacketIndex > draft.lastPacketIndex || packets.isEmpty()) {
            return thisIndex
        }

        val validChildren =
            draft.children
                .filter { it.firstPacketIndex <= it.lastPacketIndex }
                .sortedWith(compareBy<FrameDraft> { it.firstPacketIndex }.thenByDescending { it.lastPacketIndex })

        val nonOverlapping = ArrayList<FrameDraft>(validChildren.size)
        var lastCovered = draft.firstPacketIndex - 1
        for (child in validChildren) {
            if (child.firstPacketIndex < draft.firstPacketIndex || child.lastPacketIndex > draft.lastPacketIndex) {
                continue
            }
            if (child.firstPacketIndex <= lastCovered) {
                continue
            }
            nonOverlapping.add(child)
            lastCovered = child.lastPacketIndex
        }

        val refs = ArrayList<Int>((draft.lastPacketIndex - draft.firstPacketIndex + 1).coerceAtLeast(1))
        var cursor = draft.firstPacketIndex
        for (child in nonOverlapping) {
            while (cursor < child.firstPacketIndex) {
                refs.add(-(cursor + 1))
                cursor += 1
            }
            val childIdx = materialize(child, parentFrameIndex = thisIndex)
            refs.add(childIdx)
            cursor = child.lastPacketIndex + 1
        }
        while (cursor <= draft.lastPacketIndex) {
            refs.add(-(cursor + 1))
            cursor += 1
        }

        frames[thisIndex] =
            PacketFrame(
                frameIndex = thisIndex,
                nodeTypeId = draft.nodeTypeId,
                debugName = KtNodeTypes.debugName(draft.nodeTypeId),
                firstPacketIndex = draft.firstPacketIndex,
                lastPacketIndex = draft.lastPacketIndex,
                span = placeholderSpan,
                parentFrameIndex = parentFrameIndex,
                rawChildNodeRefs = refs.toIntArray(),
                flags = draft.flags,
                semanticInfo = draft.semanticInfo,
            )
        return thisIndex
    }

    private fun ctxBounds(ctx: ParserRuleContext?): Pair<Int, Int>? {
        if (ctx == null) return null
        val startTok = ctx.start?.tokenIndex ?: return null
        val stopTok = ctx.stop?.tokenIndex ?: return null
        if (startTok < 0 || stopTok < startTok || startTok >= unit.tokCoreFirst.size) {
            return null
        }
        val clampedStop = stopTok.coerceAtMost(unit.tokCoreLast.lastIndex)
        var firstPkt = -1
        for (t in startTok..clampedStop) {
            val p = unit.tokCoreFirst[t]
            if (p >= 0) {
                firstPkt = p
                break
            }
        }
        var lastPkt = -1
        for (t in clampedStop downTo startTok) {
            val p = unit.tokCoreLast[t]
            if (p >= 0) {
                lastPkt = p
                break
            }
        }
        if (firstPkt < 0 || lastPkt < firstPkt) return null
        return firstPkt to lastPkt
    }

    private fun termBounds(node: TerminalNode?): Pair<Int, Int>? {
        val tokIdx = node?.symbol?.tokenIndex ?: return null
        if (tokIdx !in unit.tokCoreFirst.indices) return null
        val firstPkt = unit.tokCoreFirst[tokIdx]
        val lastPkt = unit.tokCoreLast[tokIdx]
        if (firstPkt < 0 || lastPkt < firstPkt) return null
        return firstPkt to lastPkt
    }

    private fun collectModifierFlagsInRange(firstPkt: Int, lastPkt: Int): Long {
        var flags = PacketFlags.NONE
        if (firstPkt > lastPkt || packets.isEmpty()) return flags
        for (idx in firstPkt..lastPkt) {
            val pkt = packets[idx]
            if (pkt is SyntaxPacket.KeywordPacket) {
                flags = flags or KotlinPacketLexing.keywordPacketFlags(pkt.keywordId)
            }
        }
        return flags
    }

    private fun extractSimpleIdentName(ctx: KotlinParser.SimpleIdentifierContext?): String? {
        val bounds = ctxBounds(ctx) ?: return null
        for (idx in bounds.first..bounds.second) {
            val pkt = packets[idx]
            if (pkt is SyntaxPacket.IdentPacket) {
                return pkt.unescapedName
            }
        }
        return null
    }

    private fun markIdentRole(ctx: KotlinParser.SimpleIdentifierContext?, role: SymbolRole) {
        val bounds = ctxBounds(ctx) ?: return
        for (idx in bounds.first..bounds.second) {
            val pkt = packets[idx]
            if (pkt is SyntaxPacket.IdentPacket && pkt.role == SymbolRole.UNCLASSIFIED) {
                pkt.role = role
            }
        }
    }

    private fun buildFileDraft(ctx: KotlinParser.KotlinFileContext): FrameDraft {
        if (packets.isEmpty()) {
            return FrameDraft(KtNodeTypes.KT_FILE_ID, 0, -1)
        }
        val children = ArrayList<FrameDraft>()

        val fileAnnots = ctx.fileAnnotation()
        if (fileAnnots.isNotEmpty()) {
            val annotEntries = ArrayList<FrameDraft>()
            var firstAnnotPkt = Int.MAX_VALUE
            var lastAnnotPkt = -1
            for (fa in fileAnnots) {
                val b = ctxBounds(fa) ?: continue
                if (b.first < firstAnnotPkt) firstAnnotPkt = b.first
                if (b.second > lastAnnotPkt) lastAnnotPkt = b.second
                for (unesc in fa.getRuleContextsOfType<KotlinParser.UnescapedAnnotationContext>()) {
                    buildUnescapedAnnotationEntry(unesc, useSiteStartPkt = null)?.let { annotEntries.add(it) }
                }
            }
            if (firstAnnotPkt <= lastAnnotPkt) {
                children.add(
                    FrameDraft(
                        nodeTypeId = KtNodeTypes.FILE_ANNOTATION_LIST_ID,
                        firstPacketIndex = firstAnnotPkt,
                        lastPacketIndex = lastAnnotPkt,
                        children = annotEntries,
                    ),
                )
            }
        }

        ctx.packageHeader()?.let { pkgCtx ->
            val b = ctxBounds(pkgCtx)
            if (b != null && pkgCtx.PACKAGE() != null) {
                val pkgIdentBounds = ctxBounds(pkgCtx.identifier())
                if (pkgIdentBounds != null) {
                    for (idx in pkgIdentBounds.first..pkgIdentBounds.second) {
                        val pkt = packets[idx]
                        if (pkt is SyntaxPacket.IdentPacket) {
                            pkt.role = SymbolRole.PACKAGE
                        }
                    }
                }
                children.add(FrameDraft(KtNodeTypes.PACKAGE_DIRECTIVE_ID, b.first, b.second))
            }
        }

        ctx.importList()?.let { impListCtx ->
            val imports = impListCtx.importHeader()
            if (imports.isNotEmpty()) {
                val impDrafts = ArrayList<FrameDraft>(imports.size)
                for (imp in imports) {
                    val ib = ctxBounds(imp) ?: continue
                    val impChildren = ArrayList<FrameDraft>(1)
                    imp.importAlias()?.let { aliasCtx ->
                        val ab = ctxBounds(aliasCtx)
                        if (ab != null) {
                            markIdentRole(aliasCtx.simpleIdentifier(), SymbolRole.IMPORT_ALIAS)
                            impChildren.add(FrameDraft(KtNodeTypes.IMPORT_ALIAS_ID, ab.first, ab.second))
                        }
                    }
                    impDrafts.add(FrameDraft(KtNodeTypes.IMPORT_DIRECTIVE_ID, ib.first, ib.second, impChildren))
                }
                if (impDrafts.isNotEmpty()) {
                    children.add(
                        FrameDraft(
                            nodeTypeId = KtNodeTypes.IMPORT_LIST_ID,
                            firstPacketIndex = impDrafts.first().firstPacketIndex,
                            lastPacketIndex = impDrafts.last().lastPacketIndex,
                            children = impDrafts,
                        ),
                    )
                }
            }
        }

        val minTopDeclPkt = (children.lastOrNull()?.lastPacketIndex ?: -1) + 1
        val topDecls = ArrayList<FrameDraft>()
        for (topObj in ctx.topLevelObject()) {
            topObj.declaration()?.let { decl ->
                buildDeclaration(decl)?.let { topDecls.add(it) }
            }
        }
        attachLeadingDeclarationComments(topDecls, minTopDeclPkt)
        children.addAll(topDecls)

        return FrameDraft(
            nodeTypeId = KtNodeTypes.KT_FILE_ID,
            firstPacketIndex = 0,
            lastPacketIndex = packets.lastIndex,
            children = children,
        )
    }

    private fun buildDeclaration(ctx: KotlinParser.DeclarationContext): FrameDraft? {
        ctx.classDeclaration()?.let { return buildClassDeclaration(it) }
        ctx.objectDeclaration()?.let { return buildObjectDeclaration(it) }
        ctx.functionDeclaration()?.let { return buildFunctionDeclaration(it) }
        ctx.propertyDeclaration()?.let { return buildPropertyDeclaration(it) }
        ctx.typeAlias()?.let { return buildTypeAlias(it) }
        return null
    }

    private fun buildModifiersDraft(
        modifiersCtx: KotlinParser.ModifiersContext?,
        extraLeadingAnnotations: List<KotlinParser.AnnotationContext> = emptyList(),
        extraKeywordNode: TerminalNode? = null,
    ): FrameDraft? {
        val entries = ArrayList<FrameDraft>()
        var firstPkt = Int.MAX_VALUE
        var lastPkt = -1

        fun includeBounds(b: Pair<Int, Int>?) {
            if (b != null) {
                if (b.first < firstPkt) firstPkt = b.first
                if (b.second > lastPkt) lastPkt = b.second
            }
        }

        for (ann in extraLeadingAnnotations) {
            includeBounds(ctxBounds(ann))
            buildAnnotationDraft(ann)?.let { entries.add(it) }
        }
        if (modifiersCtx != null) {
            includeBounds(ctxBounds(modifiersCtx))
            for (ann in modifiersCtx.annotation()) {
                buildAnnotationDraft(ann)?.let { entries.add(it) }
            }
        }
        includeBounds(termBounds(extraKeywordNode))

        if (firstPkt > lastPkt) return null
        val flags = collectModifierFlagsInRange(firstPkt, lastPkt)
        return FrameDraft(
            nodeTypeId = KtNodeTypes.MODIFIER_LIST_ID,
            firstPacketIndex = firstPkt,
            lastPacketIndex = lastPkt,
            children = entries,
            flags = flags,
        )
    }

    private fun buildAnnotationDraft(ctx: KotlinParser.AnnotationContext): FrameDraft? {
        ctx.singleAnnotation()?.let { single ->
            val sb = ctxBounds(single) ?: return null
            val unesc = single.unescapedAnnotation() ?: return null
            return buildUnescapedAnnotationEntry(unesc, useSiteStartPkt = sb.first)
        }
        ctx.multiAnnotation()?.let { multi ->
            val mb = ctxBounds(multi) ?: return null
            val entries = ArrayList<FrameDraft>()
            for (unesc in multi.getRuleContextsOfType<KotlinParser.UnescapedAnnotationContext>()) {
                buildUnescapedAnnotationEntry(unesc, useSiteStartPkt = null)?.let { entries.add(it) }
            }
            return FrameDraft(KtNodeTypes.ANNOTATION_ID, mb.first, mb.second, entries)
        }
        return null
    }

    private fun buildUnescapedAnnotationEntry(
        unesc: KotlinParser.UnescapedAnnotationContext,
        useSiteStartPkt: Int?,
    ): FrameDraft? {
        val ub = ctxBounds(unesc) ?: return null
        val startPkt = if (useSiteStartPkt != null && useSiteStartPkt < ub.first) useSiteStartPkt else ub.first
        val children = ArrayList<FrameDraft>(2)
        val ctorInv = unesc.constructorInvocation()
        if (ctorInv != null) {
            ctorInv.userType()?.let { ut ->
                val utDraft = buildUserType(ut)
                if (utDraft != null) {
                    val trDraft = FrameDraft(KtNodeTypes.TYPE_REFERENCE_ID, utDraft.firstPacketIndex, utDraft.lastPacketIndex, listOf(utDraft))
                    children.add(FrameDraft(KtNodeTypes.CONSTRUCTOR_CALLEE_ID, trDraft.firstPacketIndex, trDraft.lastPacketIndex, listOf(trDraft)))
                }
            }
            ctorInv.valueArguments()?.let { va ->
                buildValueArgumentList(va)?.let { children.add(it) }
            }
        } else {
            unesc.userType()?.let { ut ->
                val utDraft = buildUserType(ut)
                if (utDraft != null) {
                    val trDraft = FrameDraft(KtNodeTypes.TYPE_REFERENCE_ID, utDraft.firstPacketIndex, utDraft.lastPacketIndex, listOf(utDraft))
                    children.add(FrameDraft(KtNodeTypes.CONSTRUCTOR_CALLEE_ID, trDraft.firstPacketIndex, trDraft.lastPacketIndex, listOf(trDraft)))
                }
            }
        }
        return FrameDraft(KtNodeTypes.ANNOTATION_ENTRY_ID, startPkt, ub.second, children)
    }

    private fun buildClassDeclaration(ctx: KotlinParser.ClassDeclarationContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        val funModNode = if (ctx.FUN() != null && ctx.INTERFACE() != null) ctx.FUN() else null
        val modDraft = buildModifiersDraft(ctx.modifiers(), extraKeywordNode = funModNode)
        if (modDraft != null) children.add(modDraft)

        markIdentRole(ctx.simpleIdentifier(), SymbolRole.TYPE)
        val name = extractSimpleIdentName(ctx.simpleIdentifier())

        ctx.typeParameters()?.let { buildTypeParameterList(it)?.let(children::add) }
        ctx.primaryConstructor()?.let { buildPrimaryConstructor(it)?.let(children::add) }
        ctx.delegationSpecifiers()?.let { buildSuperTypeList(it)?.let(children::add) }
        ctx.typeConstraints()?.let { buildTypeConstraintList(it)?.let(children::add) }
        ctx.classBody()?.let { buildClassBody(it)?.let(children::add) }
        ctx.enumClassBody()?.let { buildEnumClassBody(it)?.let(children::add) }

        val flags = modDraft?.flags ?: PacketFlags.NONE
        return FrameDraft(
            nodeTypeId = KtNodeTypes.CLASS_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = flags,
            semanticInfo = name?.let { FrameSemanticInfo(symbolName = it) },
        )
    }

    private fun buildObjectDeclaration(ctx: KotlinParser.ObjectDeclarationContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        val modDraft = buildModifiersDraft(ctx.modifiers())
        if (modDraft != null) children.add(modDraft)

        markIdentRole(ctx.simpleIdentifier(), SymbolRole.TYPE)
        val name = extractSimpleIdentName(ctx.simpleIdentifier())

        ctx.delegationSpecifiers()?.let { buildSuperTypeList(it)?.let(children::add) }
        ctx.classBody()?.let { buildClassBody(it)?.let(children::add) }

        return FrameDraft(
            nodeTypeId = KtNodeTypes.OBJECT_DECLARATION_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = modDraft?.flags ?: PacketFlags.NONE,
            semanticInfo = name?.let { FrameSemanticInfo(symbolName = it) },
        )
    }

    private fun buildCompanionObject(ctx: KotlinParser.CompanionObjectContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        val modDraft = buildModifiersDraft(ctx.modifiers(), extraKeywordNode = ctx.COMPANION())
        if (modDraft != null) children.add(modDraft)

        val identCtx = ctx.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0)
        markIdentRole(identCtx, SymbolRole.TYPE)
        val name = extractSimpleIdentName(identCtx) ?: "Companion"

        ctx.getRuleContextOfType<KotlinParser.DelegationSpecifiersContext>(0)?.let {
            buildSuperTypeList(it)?.let(children::add)
        }
        ctx.getRuleContextOfType<KotlinParser.ClassBodyContext>(0)?.let {
            buildClassBody(it)?.let(children::add)
        }

        return FrameDraft(
            nodeTypeId = KtNodeTypes.OBJECT_DECLARATION_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = modDraft?.flags ?: PacketFlags.NONE,
            semanticInfo = FrameSemanticInfo(symbolName = name),
        )
    }

    private fun buildPrimaryConstructor(ctx: KotlinParser.PrimaryConstructorContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(2)
        val modDraft = buildModifiersDraft(ctx.modifiers())
        if (modDraft != null) children.add(modDraft)
        ctx.classParameters()?.let { cpCtx ->
            val cpBounds = ctxBounds(cpCtx)
            if (cpBounds != null) {
                val paramDrafts = ArrayList<FrameDraft>()
                for (p in cpCtx.getRuleContextsOfType<KotlinParser.ClassParameterContext>()) {
                    buildClassParameter(p)?.let { paramDrafts.add(it) }
                }
                children.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_LIST_ID, cpBounds.first, cpBounds.second, paramDrafts))
            }
        }
        return FrameDraft(
            nodeTypeId = KtNodeTypes.PRIMARY_CONSTRUCTOR_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = modDraft?.flags ?: PacketFlags.NONE,
        )
    }

    private fun buildClassParameter(ctx: KotlinParser.ClassParameterContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(3)
        val modDraft = buildModifiersDraft(ctx.modifiers())
        if (modDraft != null) children.add(modDraft)
        val isProperty = ctx.getToken(KotlinLexer.Tokens.VAL, 0) != null || ctx.getToken(KotlinLexer.Tokens.VAR, 0) != null
        markIdentRole(ctx.simpleIdentifier(), if (isProperty) SymbolRole.PROPERTY else SymbolRole.PARAM)
        ctx.type()?.let { buildTypeReference(it)?.let(children::add) }
        ctx.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let {
            buildExpression(it)?.let(children::add)
        }
        return FrameDraft(
            nodeTypeId = KtNodeTypes.VALUE_PARAMETER_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = modDraft?.flags ?: PacketFlags.NONE,
        )
    }

    private fun buildSuperTypeList(ctx: KotlinParser.DelegationSpecifiersContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val entries = ArrayList<FrameDraft>()
        for (annotSpec in ctx.getRuleContextsOfType<KotlinParser.AnnotatedDelegationSpecifierContext>()) {
            val spec = annotSpec.getRuleContextOfType<KotlinParser.DelegationSpecifierContext>(0) ?: continue
            val sb = ctxBounds(annotSpec) ?: ctxBounds(spec) ?: continue
            val expDel = spec.explicitDelegation()
            val ctorInv = spec.constructorInvocation()
            if (expDel != null) {
                val sub = ArrayList<FrameDraft>(2)
                expDel.getRuleContextOfType<KotlinParser.UserTypeContext>(0)?.let { ut ->
                    buildUserType(ut)?.let { utDraft ->
                        sub.add(FrameDraft(KtNodeTypes.TYPE_REFERENCE_ID, utDraft.firstPacketIndex, utDraft.lastPacketIndex, listOf(utDraft)))
                    }
                }
                expDel.getRuleContextOfType<KotlinParser.FunctionTypeContext>(0)?.let { ft ->
                    buildFunctionType(ft)?.let { ftDraft ->
                        sub.add(FrameDraft(KtNodeTypes.TYPE_REFERENCE_ID, ftDraft.firstPacketIndex, ftDraft.lastPacketIndex, listOf(ftDraft)))
                    }
                }
                expDel.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let { expr ->
                    buildExpression(expr)?.let(sub::add)
                }
                entries.add(FrameDraft(KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY_ID, sb.first, sb.second, sub))
            } else if (ctorInv != null) {
                val sub = ArrayList<FrameDraft>(2)
                ctorInv.userType()?.let { ut ->
                    buildUserType(ut)?.let { utDraft ->
                        val tr = FrameDraft(KtNodeTypes.TYPE_REFERENCE_ID, utDraft.firstPacketIndex, utDraft.lastPacketIndex, listOf(utDraft))
                        sub.add(FrameDraft(KtNodeTypes.CONSTRUCTOR_CALLEE_ID, tr.firstPacketIndex, tr.lastPacketIndex, listOf(tr)))
                    }
                }
                ctorInv.valueArguments()?.let { va -> buildValueArgumentList(va)?.let(sub::add) }
                entries.add(FrameDraft(KtNodeTypes.SUPER_TYPE_CALL_ENTRY_ID, sb.first, sb.second, sub))
            } else {
                val sub = ArrayList<FrameDraft>(1)
                spec.userType()?.let { ut ->
                    buildUserType(ut)?.let { utDraft ->
                        sub.add(FrameDraft(KtNodeTypes.TYPE_REFERENCE_ID, utDraft.firstPacketIndex, utDraft.lastPacketIndex, listOf(utDraft)))
                    }
                }
                spec.functionType()?.let { ft ->
                    buildFunctionType(ft)?.let { ftDraft ->
                        val modFlag = if (spec.SUSPEND() != null) PacketFlags.MOD_SUSPEND else PacketFlags.NONE
                        val trStart = termBounds(spec.SUSPEND())?.first ?: ftDraft.firstPacketIndex
                        val trChildren = ArrayList<FrameDraft>(2)
                        termBounds(spec.SUSPEND())?.let { sbSuspend ->
                            trChildren.add(FrameDraft(KtNodeTypes.MODIFIER_LIST_ID, sbSuspend.first, sbSuspend.second, flags = PacketFlags.MOD_SUSPEND))
                        }
                        trChildren.add(ftDraft)
                        sub.add(FrameDraft(KtNodeTypes.TYPE_REFERENCE_ID, trStart, ftDraft.lastPacketIndex, trChildren, flags = modFlag))
                    }
                }
                entries.add(FrameDraft(KtNodeTypes.SUPER_TYPE_ENTRY_ID, sb.first, sb.second, sub))
            }
        }
        return FrameDraft(KtNodeTypes.SUPER_TYPE_LIST_ID, b.first, b.second, entries)
    }

    private fun buildClassBody(ctx: KotlinParser.ClassBodyContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val members = ArrayList<FrameDraft>()
        ctx.getRuleContextOfType<KotlinParser.ClassMemberDeclarationsContext>(0)?.let { decls ->
            appendClassMemberDeclarations(decls, members)
        }
        attachLeadingDeclarationComments(members, b.first + 1)
        return FrameDraft(KtNodeTypes.CLASS_BODY_ID, b.first, b.second, members)
    }

    private fun buildEnumClassBody(ctx: KotlinParser.EnumClassBodyContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val members = ArrayList<FrameDraft>()
        ctx.getRuleContextOfType<KotlinParser.EnumEntriesContext>(0)?.let { entriesCtx ->
            for (entry in entriesCtx.getRuleContextsOfType<KotlinParser.EnumEntryContext>()) {
                val eb = ctxBounds(entry) ?: continue
                val entryChildren = ArrayList<FrameDraft>()
                val modDraft = buildModifiersDraft(entry.modifiers())
                if (modDraft != null) entryChildren.add(modDraft)
                markIdentRole(entry.simpleIdentifier(), SymbolRole.ENUM_ENTRY)
                entry.valueArguments()?.let { va ->
                    buildValueArgumentList(va)?.let { vaDraft ->
                        val superCall =
                            FrameDraft(
                                nodeTypeId = KtNodeTypes.SUPER_TYPE_CALL_ENTRY_ID,
                                firstPacketIndex = vaDraft.firstPacketIndex,
                                lastPacketIndex = vaDraft.lastPacketIndex,
                                children = listOf(vaDraft),
                            )
                        entryChildren.add(
                            FrameDraft(
                                nodeTypeId = KtNodeTypes.INITIALIZER_LIST_ID,
                                firstPacketIndex = vaDraft.firstPacketIndex,
                                lastPacketIndex = vaDraft.lastPacketIndex,
                                children = listOf(superCall),
                            ),
                        )
                    }
                }
                entry.classBody()?.let { cb -> buildClassBody(cb)?.let(entryChildren::add) }
                members.add(
                    FrameDraft(
                        nodeTypeId = KtNodeTypes.ENUM_ENTRY_ID,
                        firstPacketIndex = eb.first,
                        lastPacketIndex = eb.second,
                        children = entryChildren,
                        flags = modDraft?.flags ?: PacketFlags.NONE,
                    ),
                )
            }
        }
        ctx.getRuleContextOfType<KotlinParser.ClassMemberDeclarationsContext>(0)?.let { decls ->
            appendClassMemberDeclarations(decls, members)
        }
        attachLeadingDeclarationComments(members, b.first + 1)
        return FrameDraft(KtNodeTypes.CLASS_BODY_ID, b.first, b.second, members)
    }

    private fun attachLeadingDeclarationComments(
        drafts: MutableList<FrameDraft>,
        minPacketIndex: Int,
    ) {
        for (i in drafts.indices) {
            val draft = drafts[i]
            val rawFloor = if (i == 0) minPacketIndex else drafts[i - 1].lastPacketIndex + 1
            var attachedStart = draft.firstPacketIndex
            var cursor = draft.firstPacketIndex - 1
            while (cursor >= rawFloor) {
                when (val pkt = packets[cursor]) {
                    is SyntaxPacket.WhitespacePacket -> {
                        if (pkt.rawText.count { it == '\n' } >= 2) {
                            break
                        }
                        cursor -= 1
                    }
                    is SyntaxPacket.CommentPacket -> {
                        if (pkt.kind == CommentPacketKind.SHEBANG || pkt.portLintDirectiveKind != null) {
                            break
                        }
                        if (pkt.kind == CommentPacketKind.KDOC || isLeadingOnItsLine(cursor)) {
                            attachedStart = cursor
                            cursor -= 1
                        } else {
                            break
                        }
                    }
                    else -> break
                }
            }
            if (attachedStart < draft.firstPacketIndex) {
                drafts[i] =
                    FrameDraft(
                        nodeTypeId = draft.nodeTypeId,
                        firstPacketIndex = attachedStart,
                        lastPacketIndex = draft.lastPacketIndex,
                        children = draft.children,
                        flags = draft.flags,
                        semanticInfo = draft.semanticInfo,
                    )
            }
        }
    }

    private fun isLeadingOnItsLine(packetIndex: Int): Boolean {
        val targetLine = packets[packetIndex].span.startLine
        var p = packetIndex - 1
        while (p >= 0 && packets[p].span.endLine == targetLine) {
            val earlier = packets[p]
            if (earlier !is SyntaxPacket.WhitespacePacket && earlier !is SyntaxPacket.CommentPacket) {
                return false
            }
            p -= 1
        }
        return true
    }

    private fun appendClassMemberDeclarations(
        ctx: KotlinParser.ClassMemberDeclarationsContext,
        out: MutableList<FrameDraft>,
    ) {
        for (member in ctx.getRuleContextsOfType<KotlinParser.ClassMemberDeclarationContext>()) {
            member.getRuleContextOfType<KotlinParser.DeclarationContext>(0)?.let {
                buildDeclaration(it)?.let(out::add)
            }
            member.getRuleContextOfType<KotlinParser.CompanionObjectContext>(0)?.let {
                buildCompanionObject(it)?.let(out::add)
            }
            member.getRuleContextOfType<KotlinParser.AnonymousInitializerContext>(0)?.let { initCtx ->
                val ib = ctxBounds(initCtx)
                if (ib != null) {
                    val initChildren = ArrayList<FrameDraft>(1)
                    initCtx.getRuleContextOfType<KotlinParser.BlockContext>(0)?.let { blk ->
                        buildBlock(blk)?.let(initChildren::add)
                    }
                    out.add(FrameDraft(KtNodeTypes.CLASS_INITIALIZER_ID, ib.first, ib.second, initChildren))
                }
            }
            member.getRuleContextOfType<KotlinParser.SecondaryConstructorContext>(0)?.let { secCtx ->
                buildSecondaryConstructor(secCtx)?.let(out::add)
            }
        }
    }

    private fun buildSecondaryConstructor(ctx: KotlinParser.SecondaryConstructorContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        val modDraft = buildModifiersDraft(ctx.modifiers())
        if (modDraft != null) children.add(modDraft)
        ctx.functionValueParameters()?.let { buildFunctionValueParameterList(it)?.let(children::add) }
        ctx.getRuleContextOfType<KotlinParser.ConstructorDelegationCallContext>(0)?.let { delCtx ->
            val db = ctxBounds(delCtx)
            if (db != null) {
                val delChildren = ArrayList<FrameDraft>(1)
                delCtx.getRuleContextOfType<KotlinParser.ValueArgumentsContext>(0)?.let { va ->
                    buildValueArgumentList(va)?.let(delChildren::add)
                }
                children.add(FrameDraft(KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL_ID, db.first, db.second, delChildren))
            }
        }
        ctx.getRuleContextOfType<KotlinParser.BlockContext>(0)?.let { buildBlock(it)?.let(children::add) }
        return FrameDraft(
            nodeTypeId = KtNodeTypes.SECONDARY_CONSTRUCTOR_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = modDraft?.flags ?: PacketFlags.NONE,
        )
    }

    private fun buildFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        val modDraft = buildModifiersDraft(ctx.modifiers())
        if (modDraft != null) children.add(modDraft)

        ctx.typeParameters()?.let { buildTypeParameterList(it)?.let(children::add) }
        ctx.getRuleContextOfType<KotlinParser.ReceiverTypeContext>(0)?.let { recvCtx ->
            buildReceiverTypeReference(recvCtx)?.let(children::add)
        }

        markIdentRole(ctx.simpleIdentifier(), SymbolRole.FUNCTION)
        val fnName = extractSimpleIdentName(ctx.simpleIdentifier())

        ctx.functionValueParameters()?.let { buildFunctionValueParameterList(it)?.let(children::add) }
        ctx.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { retType ->
            buildTypeReference(retType)?.let(children::add)
        }
        ctx.getRuleContextOfType<KotlinParser.TypeConstraintsContext>(0)?.let {
            buildTypeConstraintList(it)?.let(children::add)
        }
        ctx.getRuleContextOfType<KotlinParser.FunctionBodyContext>(0)?.let { bodyCtx ->
            appendFunctionBodyChildren(bodyCtx, children)
        }

        val flags = modDraft?.flags ?: PacketFlags.NONE
        return FrameDraft(
            nodeTypeId = KtNodeTypes.FUN_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = flags,
            semanticInfo = fnName?.let { FrameSemanticInfo(symbolName = it) },
        )
    }

    private fun appendFunctionBodyChildren(
        bodyCtx: KotlinParser.FunctionBodyContext,
        out: MutableList<FrameDraft>,
    ) {
        bodyCtx.getRuleContextOfType<KotlinParser.BlockContext>(0)?.let { blk ->
            buildBlock(blk)?.let(out::add)
            return
        }
        bodyCtx.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let { expr ->
            buildExpression(expr)?.let(out::add)
        }
    }

    private fun buildFunctionValueParameterList(ctx: KotlinParser.FunctionValueParametersContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val params = ArrayList<FrameDraft>()
        for (fvp in ctx.getRuleContextsOfType<KotlinParser.FunctionValueParameterContext>()) {
            val pb = ctxBounds(fvp) ?: continue
            val pChildren = ArrayList<FrameDraft>(3)
            var pFlags = PacketFlags.NONE
            fvp.parameterModifiers()?.let { pmCtx ->
                val pmb = ctxBounds(pmCtx)
                if (pmb != null) {
                    val annDrafts = pmCtx.getRuleContextsOfType<KotlinParser.AnnotationContext>().mapNotNull { buildAnnotationDraft(it) }
                    pFlags = collectModifierFlagsInRange(pmb.first, pmb.second)
                    pChildren.add(FrameDraft(KtNodeTypes.MODIFIER_LIST_ID, pmb.first, pmb.second, annDrafts, pFlags))
                }
            }
            fvp.parameter()?.let { param ->
                markIdentRole(param.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.PARAM)
                param.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(pChildren::add) }
            }
            fvp.expression()?.let { buildExpression(it)?.let(pChildren::add) }
            params.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_ID, pb.first, pb.second, pChildren, pFlags))
        }
        return FrameDraft(KtNodeTypes.VALUE_PARAMETER_LIST_ID, b.first, b.second, params)
    }

    private fun buildPropertyDeclaration(ctx: KotlinParser.PropertyDeclarationContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val multiVar = ctx.multiVariableDeclaration()
        if (multiVar != null) {
            val children = ArrayList<FrameDraft>()
            val modDraft = buildModifiersDraft(ctx.modifiers())
            if (modDraft != null) children.add(modDraft)
            for (vd in multiVar.getRuleContextsOfType<KotlinParser.VariableDeclarationContext>()) {
                val vdb = ctxBounds(vd) ?: continue
                val vdChildren = ArrayList<FrameDraft>(2)
                val annots = vd.getRuleContextsOfType<KotlinParser.AnnotationContext>()
                if (annots.isNotEmpty()) {
                    buildModifiersDraft(null, extraLeadingAnnotations = annots)?.let(vdChildren::add)
                }
                markIdentRole(vd.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.LOCAL)
                vd.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(vdChildren::add) }
                children.add(FrameDraft(KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY_ID, vdb.first, vdb.second, vdChildren))
            }
            ctx.expression()?.let { buildExpression(it)?.let(children::add) }
            return FrameDraft(
                nodeTypeId = KtNodeTypes.DESTRUCTURING_DECLARATION_ID,
                firstPacketIndex = b.first,
                lastPacketIndex = b.second,
                children = children,
                flags = modDraft?.flags ?: PacketFlags.NONE,
            )
        }

        val children = ArrayList<FrameDraft>()
        val modDraft = buildModifiersDraft(ctx.modifiers())
        if (modDraft != null) children.add(modDraft)

        ctx.typeParameters()?.let { buildTypeParameterList(it)?.let(children::add) }
        ctx.receiverType()?.let { buildReceiverTypeReference(it)?.let(children::add) }

        var propName: String? = null
        ctx.variableDeclaration()?.let { vd ->
            val identCtx = vd.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0)
            markIdentRole(identCtx, SymbolRole.PROPERTY)
            propName = extractSimpleIdentName(identCtx)
            vd.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(children::add) }
        }

        ctx.typeConstraints()?.let { buildTypeConstraintList(it)?.let(children::add) }
        ctx.expression()?.let { buildExpression(it)?.let(children::add) }
        ctx.propertyDelegate()?.let { delCtx ->
            val db = ctxBounds(delCtx)
            if (db != null) {
                val delChildren = ArrayList<FrameDraft>(1)
                delCtx.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let { buildExpression(it)?.let(delChildren::add) }
                children.add(FrameDraft(KtNodeTypes.PROPERTY_DELEGATE_ID, db.first, db.second, delChildren))
            }
        }
        ctx.getter()?.let { buildGetter(it)?.let(children::add) }
        ctx.setter()?.let { buildSetter(it)?.let(children::add) }

        return FrameDraft(
            nodeTypeId = KtNodeTypes.PROPERTY_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = modDraft?.flags ?: PacketFlags.NONE,
            semanticInfo = propName?.let { FrameSemanticInfo(symbolName = it) },
        )
    }

    private fun buildGetter(ctx: KotlinParser.GetterContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        val modDraft = buildModifiersDraft(ctx.modifiers())
        if (modDraft != null) children.add(modDraft)
        if (ctx.LPAREN() != null && ctx.RPAREN() != null) {
            val lp = termBounds(ctx.LPAREN())
            val rp = termBounds(ctx.RPAREN())
            if (lp != null && rp != null) {
                children.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_LIST_ID, lp.first, rp.second))
            }
        }
        ctx.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(children::add) }
        ctx.functionBody()?.let { appendFunctionBodyChildren(it, children) }
        return FrameDraft(
            nodeTypeId = KtNodeTypes.PROPERTY_ACCESSOR_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = modDraft?.flags ?: PacketFlags.NONE,
        )
    }

    private fun buildSetter(ctx: KotlinParser.SetterContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        val modDraft = buildModifiersDraft(ctx.modifiers())
        if (modDraft != null) children.add(modDraft)
        val lp = termBounds(ctx.LPAREN())
        val rp = termBounds(ctx.RPAREN())
        if (lp != null && rp != null) {
            val pListChildren = ArrayList<FrameDraft>(1)
            ctx.functionValueParameterWithOptionalType()?.let { pCtx ->
                val pb = ctxBounds(pCtx)
                if (pb != null) {
                    val vpChildren = ArrayList<FrameDraft>(2)
                    pCtx.getRuleContextOfType<KotlinParser.ParameterModifiersContext>(0)?.let { pm ->
                        ctxBounds(pm)?.let { pmb ->
                            val annDrafts = pm.getRuleContextsOfType<KotlinParser.AnnotationContext>().mapNotNull { buildAnnotationDraft(it) }
                            vpChildren.add(FrameDraft(KtNodeTypes.MODIFIER_LIST_ID, pmb.first, pmb.second, annDrafts))
                        }
                    }
                    pCtx.getRuleContextOfType<KotlinParser.ParameterWithOptionalTypeContext>(0)?.let { pot ->
                        markIdentRole(pot.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.PARAM)
                        pot.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(vpChildren::add) }
                    }
                    pListChildren.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_ID, pb.first, pb.second, vpChildren))
                }
            }
            children.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_LIST_ID, lp.first, rp.second, pListChildren))
        }
        ctx.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(children::add) }
        ctx.getRuleContextOfType<KotlinParser.FunctionBodyContext>(0)?.let { appendFunctionBodyChildren(it, children) }
        return FrameDraft(
            nodeTypeId = KtNodeTypes.PROPERTY_ACCESSOR_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = modDraft?.flags ?: PacketFlags.NONE,
        )
    }

    private fun buildTypeAlias(ctx: KotlinParser.TypeAliasContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        val modDraft = buildModifiersDraft(ctx.modifiers())
        if (modDraft != null) children.add(modDraft)
        markIdentRole(ctx.simpleIdentifier(), SymbolRole.TYPE)
        val name = extractSimpleIdentName(ctx.simpleIdentifier())
        ctx.getRuleContextOfType<KotlinParser.TypeParametersContext>(0)?.let {
            buildTypeParameterList(it)?.let(children::add)
        }
        ctx.type()?.let { buildTypeReference(it)?.let(children::add) }
        return FrameDraft(
            nodeTypeId = KtNodeTypes.TYPEALIAS_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = modDraft?.flags ?: PacketFlags.NONE,
            semanticInfo = name?.let { FrameSemanticInfo(symbolName = it) },
        )
    }

    private fun buildTypeParameterList(ctx: KotlinParser.TypeParametersContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val params = ArrayList<FrameDraft>()
        for (tp in ctx.getRuleContextsOfType<KotlinParser.TypeParameterContext>()) {
            val tpb = ctxBounds(tp) ?: continue
            val tpChildren = ArrayList<FrameDraft>(2)
            var tpFlags = PacketFlags.NONE
            tp.getRuleContextOfType<KotlinParser.TypeParameterModifiersContext>(0)?.let { tpm ->
                val tpmb = ctxBounds(tpm)
                if (tpmb != null) {
                    val annDrafts = tpm.getRuleContextsOfType<KotlinParser.AnnotationContext>().mapNotNull { buildAnnotationDraft(it) }
                    tpFlags = collectModifierFlagsInRange(tpmb.first, tpmb.second)
                    tpChildren.add(FrameDraft(KtNodeTypes.MODIFIER_LIST_ID, tpmb.first, tpmb.second, annDrafts, tpFlags))
                }
            }
            markIdentRole(tp.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.TYPE_PARAM)
            tp.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(tpChildren::add) }
            params.add(FrameDraft(KtNodeTypes.TYPE_PARAMETER_ID, tpb.first, tpb.second, tpChildren, tpFlags))
        }
        return FrameDraft(KtNodeTypes.TYPE_PARAMETER_LIST_ID, b.first, b.second, params)
    }

    private fun buildTypeConstraintList(ctx: KotlinParser.TypeConstraintsContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val constraints = ArrayList<FrameDraft>()
        for (tc in ctx.getRuleContextsOfType<KotlinParser.TypeConstraintContext>()) {
            val tcb = ctxBounds(tc) ?: continue
            val tcChildren = ArrayList<FrameDraft>(3)
            val annots = tc.getRuleContextsOfType<KotlinParser.AnnotationContext>()
            if (annots.isNotEmpty()) {
                buildModifiersDraft(null, extraLeadingAnnotations = annots)?.let(tcChildren::add)
            }
            tc.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0)?.let { idCtx ->
                markIdentRole(idCtx, SymbolRole.TYPE_PARAM)
                ctxBounds(idCtx)?.let { ib ->
                    tcChildren.add(FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, ib.first, ib.second))
                }
            }
            tc.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(tcChildren::add) }
            constraints.add(FrameDraft(KtNodeTypes.TYPE_CONSTRAINT_ID, tcb.first, tcb.second, tcChildren))
        }
        return FrameDraft(KtNodeTypes.TYPE_CONSTRAINT_LIST_ID, b.first, b.second, constraints)
    }

    private fun buildReceiverTypeReference(ctx: KotlinParser.ReceiverTypeContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(2)
        var flags = PacketFlags.NONE
        ctx.getRuleContextOfType<KotlinParser.TypeModifiersContext>(0)?.let { tm ->
            val tmb = ctxBounds(tm)
            if (tmb != null) {
                val annDrafts = tm.getRuleContextsOfType<KotlinParser.AnnotationContext>().mapNotNull { buildAnnotationDraft(it) }
                flags = collectModifierFlagsInRange(tmb.first, tmb.second)
                children.add(FrameDraft(KtNodeTypes.MODIFIER_LIST_ID, tmb.first, tmb.second, annDrafts, flags))
            }
        }
        ctx.getRuleContextOfType<KotlinParser.NullableTypeContext>(0)?.let { nt ->
            buildNullableType(nt)?.let {
                flags = flags or PacketFlags.NULLABLE_TYPE
                children.add(it)
            }
        }
        ctx.getRuleContextOfType<KotlinParser.TypeReferenceContext>(0)?.let { tr ->
            buildInnerTypeReference(tr)?.let(children::add)
        }
        ctx.getRuleContextOfType<KotlinParser.ParenthesizedTypeContext>(0)?.let { pt ->
            unwrapParenthesizedTypeInner(pt)?.let { inner ->
                flags = flags or (inner.flags and (PacketFlags.NULLABLE_TYPE or PacketFlags.MOD_SUSPEND))
                children.add(inner)
            }
        }
        return FrameDraft(KtNodeTypes.TYPE_REFERENCE_ID, b.first, b.second, children, flags)
    }

    private fun buildTypeReference(ctx: KotlinParser.TypeContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(2)
        var flags = PacketFlags.NONE
        ctx.getRuleContextOfType<KotlinParser.TypeModifiersContext>(0)?.let { tm ->
            val tmb = ctxBounds(tm)
            if (tmb != null) {
                val annDrafts = tm.getRuleContextsOfType<KotlinParser.AnnotationContext>().mapNotNull { buildAnnotationDraft(it) }
                flags = collectModifierFlagsInRange(tmb.first, tmb.second)
                children.add(FrameDraft(KtNodeTypes.MODIFIER_LIST_ID, tmb.first, tmb.second, annDrafts, flags))
            }
        }
        ctx.getRuleContextOfType<KotlinParser.FunctionTypeContext>(0)?.let { ft ->
            buildFunctionType(ft)?.let(children::add)
        }
        ctx.getRuleContextOfType<KotlinParser.NullableTypeContext>(0)?.let { nt ->
            buildNullableType(nt)?.let {
                flags = flags or PacketFlags.NULLABLE_TYPE
                children.add(it)
            }
        }
        ctx.getRuleContextOfType<KotlinParser.TypeReferenceContext>(0)?.let { tr ->
            buildInnerTypeReference(tr)?.let(children::add)
        }
        ctx.getRuleContextOfType<KotlinParser.ParenthesizedTypeContext>(0)?.let { pt ->
            unwrapParenthesizedTypeInner(pt)?.let { inner ->
                flags = flags or (inner.flags and (PacketFlags.NULLABLE_TYPE or PacketFlags.MOD_SUSPEND))
                children.add(inner)
            }
        }
        ctx.getRuleContextOfType<KotlinParser.DefinitelyNonNullableTypeContext>(0)?.let { dnn ->
            val firstUt = dnn.userType(0)
            if (firstUt != null) {
                buildUserType(firstUt)?.let(children::add)
            }
        }
        return FrameDraft(KtNodeTypes.TYPE_REFERENCE_ID, b.first, b.second, children, flags)
    }

    private fun unwrapParenthesizedTypeInner(ctx: KotlinParser.ParenthesizedTypeContext): FrameDraft? {
        val innerType = ctx.getRuleContextOfType<KotlinParser.TypeContext>(0) ?: return null
        val innerRef = buildTypeReference(innerType) ?: return null
        val unwrapped = innerRef.children.lastOrNull() ?: return innerRef
        return FrameDraft(
            nodeTypeId = unwrapped.nodeTypeId,
            firstPacketIndex = unwrapped.firstPacketIndex,
            lastPacketIndex = unwrapped.lastPacketIndex,
            children = unwrapped.children,
            flags = unwrapped.flags or innerRef.flags,
            semanticInfo = unwrapped.semanticInfo,
        )
    }

    private fun buildNullableType(ctx: KotlinParser.NullableTypeContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(1)
        var extraFlags = PacketFlags.NONE
        ctx.typeReference()?.let { buildInnerTypeReference(it)?.let(children::add) }
        ctx.parenthesizedType()?.let { pt ->
            unwrapParenthesizedTypeInner(pt)?.let { inner ->
                extraFlags = extraFlags or (inner.flags and PacketFlags.MOD_SUSPEND)
                children.add(inner)
            }
        }
        return FrameDraft(
            nodeTypeId = KtNodeTypes.NULLABLE_TYPE_ID,
            firstPacketIndex = b.first,
            lastPacketIndex = b.second,
            children = children,
            flags = PacketFlags.NULLABLE_TYPE or extraFlags,
        )
    }

    private fun buildInnerTypeReference(ctx: KotlinParser.TypeReferenceContext): FrameDraft? {
        ctx.userType()?.let { return buildUserType(it) }
        if (ctx.DYNAMIC() != null) {
            val b = ctxBounds(ctx) ?: return null
            return FrameDraft(KtNodeTypes.DYNAMIC_TYPE_ID, b.first, b.second)
        }
        return null
    }

    private fun buildUserType(ctx: KotlinParser.UserTypeContext): FrameDraft? {
        val segments = ctx.getRuleContextsOfType<KotlinParser.SimpleUserTypeContext>()
        if (segments.isEmpty()) return null
        var acc: FrameDraft? = null
        for (seg in segments) {
            val sb = ctxBounds(seg) ?: continue
            val segChildren = ArrayList<FrameDraft>(3)
            if (acc != null) {
                segChildren.add(acc)
            }
            seg.simpleIdentifier()?.let { idCtx ->
                markIdentRole(idCtx, SymbolRole.TYPE)
                ctxBounds(idCtx)?.let { ib ->
                    segChildren.add(FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, ib.first, ib.second))
                }
            }
            seg.typeArguments()?.let { ta ->
                buildTypeArgumentList(ta)?.let(segChildren::add)
            }
            val startPkt = acc?.firstPacketIndex ?: sb.first
            acc = FrameDraft(KtNodeTypes.USER_TYPE_ID, startPkt, sb.second, segChildren)
        }
        return acc
    }

    private fun buildTypeArgumentList(ctx: KotlinParser.TypeArgumentsContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val projs = ArrayList<FrameDraft>()
        for (tp in ctx.getRuleContextsOfType<KotlinParser.TypeProjectionContext>()) {
            val tpb = ctxBounds(tp) ?: continue
            val tpChildren = ArrayList<FrameDraft>(2)
            tp.typeProjectionModifiers()?.let { tpm ->
                ctxBounds(tpm)?.let { tpmb ->
                    val annDrafts = tpm.getRuleContextsOfType<KotlinParser.AnnotationContext>().mapNotNull { buildAnnotationDraft(it) }
                    tpChildren.add(FrameDraft(KtNodeTypes.MODIFIER_LIST_ID, tpmb.first, tpmb.second, annDrafts))
                }
            }
            tp.type()?.let { buildTypeReference(it)?.let(tpChildren::add) }
            projs.add(FrameDraft(KtNodeTypes.TYPE_PROJECTION_ID, tpb.first, tpb.second, tpChildren))
        }
        return FrameDraft(KtNodeTypes.TYPE_ARGUMENT_LIST_ID, b.first, b.second, projs)
    }

    private fun buildFunctionType(ctx: KotlinParser.FunctionTypeContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(3)
        ctx.receiverType()?.let { recv ->
            buildReceiverTypeReference(recv)?.let { tr ->
                children.add(FrameDraft(KtNodeTypes.FUNCTION_TYPE_RECEIVER_ID, tr.firstPacketIndex, tr.lastPacketIndex, listOf(tr)))
            }
        }
        ctx.functionTypeParameters()?.let { ftp ->
            val ftpb = ctxBounds(ftp)
            if (ftpb != null) {
                val vpDrafts = ArrayList<FrameDraft>()
                val ftpChildren = ftp.children
                if (ftpChildren != null) {
                    for (ch in ftpChildren) {
                        when (ch) {
                            is KotlinParser.ParameterContext -> {
                                val pb = ctxBounds(ch) ?: continue
                                val sub = ArrayList<FrameDraft>(1)
                                markIdentRole(ch.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.PARAM)
                                ch.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(sub::add) }
                                vpDrafts.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_ID, pb.first, pb.second, sub))
                            }
                            is KotlinParser.TypeContext -> {
                                val tr = buildTypeReference(ch) ?: continue
                                vpDrafts.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_ID, tr.firstPacketIndex, tr.lastPacketIndex, listOf(tr)))
                            }
                        }
                    }
                }
                children.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_LIST_ID, ftpb.first, ftpb.second, vpDrafts))
            }
        }
        ctx.type()?.let { buildTypeReference(it)?.let(children::add) }
        return FrameDraft(KtNodeTypes.FUNCTION_TYPE_ID, b.first, b.second, children)
    }

    private fun buildBlock(ctx: KotlinParser.BlockContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val stmts = ArrayList<FrameDraft>()
        ctx.statements()?.let { appendStatements(it, stmts) }
        return FrameDraft(KtNodeTypes.BLOCK_ID, b.first, b.second, stmts)
    }

    private fun appendStatements(ctx: KotlinParser.StatementsContext, out: MutableList<FrameDraft>) {
        for (stmt in ctx.statement()) {
            buildStatement(stmt)?.let(out::add)
        }
    }

    private fun buildStatement(ctx: KotlinParser.StatementContext): FrameDraft? {
        var core: FrameDraft? = null
        ctx.declaration()?.let { core = buildDeclaration(it) }
        if (core == null) ctx.assignment()?.let { core = buildAssignment(it) }
        if (core == null) ctx.loopStatement()?.let { core = buildLoopStatement(it) }
        if (core == null) ctx.expression()?.let { core = buildExpression(it) }
        val base = core ?: return null

        var wrapped = base
        for (label in ctx.label().asReversed()) {
            val lb = ctxBounds(label) ?: continue
            markIdentRole(label.simpleIdentifier(), SymbolRole.LABEL)
            val lq = FrameDraft(KtNodeTypes.LABEL_QUALIFIER_ID, lb.first, lb.second)
            wrapped = FrameDraft(KtNodeTypes.LABELED_EXPRESSION_ID, lb.first, wrapped.lastPacketIndex, listOf(lq, wrapped))
        }
        val annots = ctx.annotation()
        if (annots.isNotEmpty() && ctx.declaration() == null) {
            val entries = annots.mapNotNull { buildAnnotationDraft(it) }
            val firstAnnPkt = entries.firstOrNull()?.firstPacketIndex ?: wrapped.firstPacketIndex
            wrapped = FrameDraft(KtNodeTypes.ANNOTATED_EXPRESSION_ID, firstAnnPkt, wrapped.lastPacketIndex, entries + wrapped)
        }
        return wrapped
    }

    private fun buildControlStructureBodyInner(ctx: KotlinParser.ControlStructureBodyContext): FrameDraft? {
        ctx.block()?.let { return buildBlock(it) }
        ctx.statement()?.let { return buildStatement(it) }
        return null
    }

    private fun buildLoopStatement(ctx: KotlinParser.LoopStatementContext): FrameDraft? {
        ctx.forStatement()?.let { return buildForStatement(it) }
        ctx.whileStatement()?.let { return buildWhileStatement(it) }
        ctx.doWhileStatement()?.let { return buildDoWhileStatement(it) }
        return null
    }

    private fun buildForStatement(ctx: KotlinParser.ForStatementContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(3)

        val annots = ctx.annotation()
        val vd = ctx.variableDeclaration()
        val mvd = ctx.multiVariableDeclaration()
        if (vd != null) {
            val vdb = ctxBounds(vd)
            if (vdb != null) {
                val vpChildren = ArrayList<FrameDraft>(2)
                var vpStart = vdb.first
                if (annots.isNotEmpty()) {
                    buildModifiersDraft(null, extraLeadingAnnotations = annots)?.let {
                        vpStart = minOf(vpStart, it.firstPacketIndex)
                        vpChildren.add(it)
                    }
                }
                markIdentRole(vd.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.LOCAL)
                vd.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(vpChildren::add) }
                children.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_ID, vpStart, vdb.second, vpChildren))
            }
        } else if (mvd != null) {
            val mvdb = ctxBounds(mvd)
            if (mvdb != null) {
                val entries = ArrayList<FrameDraft>()
                for (entryVd in mvd.getRuleContextsOfType<KotlinParser.VariableDeclarationContext>()) {
                    val eb = ctxBounds(entryVd) ?: continue
                    val eChildren = ArrayList<FrameDraft>(1)
                    markIdentRole(entryVd.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.LOCAL)
                    entryVd.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(eChildren::add) }
                    entries.add(FrameDraft(KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY_ID, eb.first, eb.second, eChildren))
                }
                val destr = FrameDraft(KtNodeTypes.DESTRUCTURING_DECLARATION_ID, mvdb.first, mvdb.second, entries)
                var vpStart = mvdb.first
                val vpChildren = ArrayList<FrameDraft>(2)
                if (annots.isNotEmpty()) {
                    buildModifiersDraft(null, extraLeadingAnnotations = annots)?.let {
                        vpStart = minOf(vpStart, it.firstPacketIndex)
                        vpChildren.add(it)
                    }
                }
                vpChildren.add(destr)
                children.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_ID, vpStart, mvdb.second, vpChildren))
            }
        }

        ctx.expression()?.let { expr ->
            buildExpression(expr)?.let { exprDraft ->
                children.add(FrameDraft(KtNodeTypes.LOOP_RANGE_ID, exprDraft.firstPacketIndex, exprDraft.lastPacketIndex, listOf(exprDraft)))
            }
        }
        ctx.controlStructureBody()?.let { csb ->
            buildControlStructureBodyInner(csb)?.let { bodyDraft ->
                children.add(FrameDraft(KtNodeTypes.BODY_ID, bodyDraft.firstPacketIndex, bodyDraft.lastPacketIndex, listOf(bodyDraft)))
            }
        }
        return FrameDraft(KtNodeTypes.FOR_ID, b.first, b.second, children)
    }

    private fun buildWhileStatement(ctx: KotlinParser.WhileStatementContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(2)
        ctx.expression()?.let { expr ->
            buildExpression(expr)?.let { exprDraft ->
                children.add(FrameDraft(KtNodeTypes.CONDITION_ID, exprDraft.firstPacketIndex, exprDraft.lastPacketIndex, listOf(exprDraft)))
            }
        }
        ctx.controlStructureBody()?.let { csb ->
            buildControlStructureBodyInner(csb)?.let { bodyDraft ->
                children.add(FrameDraft(KtNodeTypes.BODY_ID, bodyDraft.firstPacketIndex, bodyDraft.lastPacketIndex, listOf(bodyDraft)))
            }
        }
        return FrameDraft(KtNodeTypes.WHILE_ID, b.first, b.second, children)
    }

    private fun buildDoWhileStatement(ctx: KotlinParser.DoWhileStatementContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(2)
        ctx.controlStructureBody()?.let { csb ->
            buildControlStructureBodyInner(csb)?.let { bodyDraft ->
                children.add(FrameDraft(KtNodeTypes.BODY_ID, bodyDraft.firstPacketIndex, bodyDraft.lastPacketIndex, listOf(bodyDraft)))
            }
        }
        ctx.expression()?.let { expr ->
            buildExpression(expr)?.let { exprDraft ->
                children.add(FrameDraft(KtNodeTypes.CONDITION_ID, exprDraft.firstPacketIndex, exprDraft.lastPacketIndex, listOf(exprDraft)))
            }
        }
        return FrameDraft(KtNodeTypes.DO_WHILE_ID, b.first, b.second, children)
    }

    private fun buildAssignment(ctx: KotlinParser.AssignmentContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val lhsDraft =
            ctx.directlyAssignableExpression()?.let { buildDirectlyAssignableExpression(it) }
                ?: ctx.assignableExpression()?.let { buildAssignableExpression(it) }
                ?: return null
        val opBounds =
            termBounds(ctx.ASSIGNMENT())
                ?: ctxBounds(ctx.assignmentAndOperator())
                ?: return null
        val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opBounds.first, opBounds.second)
        val rhsDraft = buildExpression(ctx.expression()) ?: return null
        return FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, b.first, b.second, listOf(lhsDraft, opRef, rhsDraft))
    }

    private fun buildDirectlyAssignableExpression(ctx: KotlinParser.DirectlyAssignableExpressionContext): FrameDraft? {
        ctx.simpleIdentifier()?.let { idCtx ->
            val ib = ctxBounds(idCtx) ?: return null
            return FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, ib.first, ib.second)
        }
        ctx.parenthesizedDirectlyAssignableExpression()?.let { pCtx ->
            val pb = ctxBounds(pCtx) ?: return null
            val inner = pCtx.getRuleContextOfType<KotlinParser.DirectlyAssignableExpressionContext>(0)?.let { buildDirectlyAssignableExpression(it) }
            return FrameDraft(KtNodeTypes.PARENTHESIZED_ID, pb.first, pb.second, listOfNotNull(inner))
        }
        val base = ctx.postfixUnaryExpression()?.let { buildPostfixUnaryExpression(it) } ?: return null
        val suffix = ctx.assignableSuffix() ?: return base
        suffix.indexingSuffix()?.let { idxCtx ->
            val ib = ctxBounds(idxCtx) ?: return base
            val exprs = idxCtx.expression().mapNotNull { buildExpression(it) }
            val indicesDraft = FrameDraft(KtNodeTypes.INDICES_ID, ib.first, ib.second, exprs)
            return FrameDraft(KtNodeTypes.ARRAY_ACCESS_EXPRESSION_ID, base.firstPacketIndex, ib.second, listOf(base, indicesDraft))
        }
        suffix.navigationSuffix()?.let { navCtx ->
            return applyNavigationSuffix(base, navCtx, callTypeArgs = null, callSuffix = null)
        }
        return base
    }

    private fun buildAssignableExpression(ctx: KotlinParser.AssignableExpressionContext): FrameDraft? {
        ctx.prefixUnaryExpression()?.let { return buildPrefixUnaryExpression(it) }
        ctx.parenthesizedAssignableExpression()?.let { pCtx ->
            val pb = ctxBounds(pCtx) ?: return null
            val inner = pCtx.getRuleContextOfType<KotlinParser.AssignableExpressionContext>(0)?.let { buildAssignableExpression(it) }
            return FrameDraft(KtNodeTypes.PARENTHESIZED_ID, pb.first, pb.second, listOfNotNull(inner))
        }
        return null
    }

    private fun buildExpression(ctx: KotlinParser.ExpressionContext?): FrameDraft? {
        val disj = ctx?.getRuleContextOfType<KotlinParser.DisjunctionContext>(0) ?: return null
        return buildDisjunction(disj)
    }

    private fun buildDisjunction(ctx: KotlinParser.DisjunctionContext): FrameDraft? {
        val operands = ctx.conjunction()
        if (operands.isEmpty()) return null
        var acc = buildConjunction(operands[0]) ?: return null
        val ops = ctx.DISJ()
        for (i in 1 until operands.size) {
            val rhs = buildConjunction(operands[i]) ?: continue
            val opB = termBounds(ops.getOrNull(i - 1)) ?: continue
            val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
            acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, acc.firstPacketIndex, rhs.lastPacketIndex, listOf(acc, opRef, rhs))
        }
        return acc
    }

    private fun buildConjunction(ctx: KotlinParser.ConjunctionContext): FrameDraft? {
        val operands = ctx.equality()
        if (operands.isEmpty()) return null
        var acc = buildEquality(operands[0]) ?: return null
        val ops = ctx.CONJ()
        for (i in 1 until operands.size) {
            val rhs = buildEquality(operands[i]) ?: continue
            val opB = termBounds(ops.getOrNull(i - 1)) ?: continue
            val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
            acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, acc.firstPacketIndex, rhs.lastPacketIndex, listOf(acc, opRef, rhs))
        }
        return acc
    }

    private fun buildEquality(ctx: KotlinParser.EqualityContext): FrameDraft? {
        val operands = ctx.comparison()
        if (operands.isEmpty()) return null
        var acc = buildComparison(operands[0]) ?: return null
        val ops = ctx.equalityOperator()
        for (i in 1 until operands.size) {
            val rhs = buildComparison(operands[i]) ?: continue
            val opB = ctxBounds(ops.getOrNull(i - 1)) ?: continue
            val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
            acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, acc.firstPacketIndex, rhs.lastPacketIndex, listOf(acc, opRef, rhs))
        }
        return acc
    }

    private fun buildComparison(ctx: KotlinParser.ComparisonContext): FrameDraft? {
        val operands = ctx.genericCallLikeComparison()
        if (operands.isEmpty()) return null
        var acc = buildGenericCallLikeComparison(operands[0]) ?: return null
        val ops = ctx.comparisonOperator()
        for (i in 1 until operands.size) {
            val rhs = buildGenericCallLikeComparison(operands[i]) ?: continue
            val opB = ctxBounds(ops.getOrNull(i - 1)) ?: continue
            val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
            acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, acc.firstPacketIndex, rhs.lastPacketIndex, listOf(acc, opRef, rhs))
        }
        return acc
    }

    private fun buildGenericCallLikeComparison(ctx: KotlinParser.GenericCallLikeComparisonContext): FrameDraft? {
        var acc = ctx.infixOperation()?.let { buildInfixOperation(it) } ?: return null
        for (cs in ctx.callSuffix()) {
            acc = applyCallSuffix(acc, explicitTypeArgs = null, callSuffix = cs)
        }
        return acc
    }

    private fun buildInfixOperation(ctx: KotlinParser.InfixOperationContext): FrameDraft? {
        val ch = ctx.children ?: return null
        var acc: FrameDraft? = null
        var i = 0
        while (i < ch.size) {
            when (val node = ch[i]) {
                is KotlinParser.ElvisExpressionContext -> {
                    if (acc == null) {
                        acc = buildElvisExpression(node)
                    }
                    i += 1
                }
                is KotlinParser.InOperatorContext -> {
                    val cur = acc ?: return null
                    val opB = ctxBounds(node)
                    var nextIdx = i + 1
                    while (nextIdx < ch.size && ch[nextIdx] !is KotlinParser.ElvisExpressionContext) {
                        nextIdx += 1
                    }
                    val rhsCtx = ch.getOrNull(nextIdx) as? KotlinParser.ElvisExpressionContext
                    val rhs = rhsCtx?.let { buildElvisExpression(it) }
                    if (opB != null && rhs != null) {
                        val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
                        acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, cur.firstPacketIndex, rhs.lastPacketIndex, listOf(cur, opRef, rhs))
                    }
                    i = nextIdx + 1
                }
                is KotlinParser.IsOperatorContext -> {
                    val cur = acc ?: return null
                    val opB = ctxBounds(node)
                    var nextIdx = i + 1
                    while (nextIdx < ch.size && ch[nextIdx] !is KotlinParser.TypeContext) {
                        nextIdx += 1
                    }
                    val typeCtx = ch.getOrNull(nextIdx) as? KotlinParser.TypeContext
                    val tr = typeCtx?.let { buildTypeReference(it) }
                    if (opB != null && tr != null) {
                        val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
                        acc = FrameDraft(KtNodeTypes.IS_EXPRESSION_ID, cur.firstPacketIndex, tr.lastPacketIndex, listOf(cur, opRef, tr))
                    }
                    i = nextIdx + 1
                }
                else -> i += 1
            }
        }
        return acc
    }

    private fun buildElvisExpression(ctx: KotlinParser.ElvisExpressionContext): FrameDraft? {
        val operands = ctx.infixFunctionCall()
        if (operands.isEmpty()) return null
        var acc = buildInfixFunctionCall(operands[0]) ?: return null
        val ops = ctx.elvis()
        for (i in 1 until operands.size) {
            val rhs = buildInfixFunctionCall(operands[i]) ?: continue
            val opB = ctxBounds(ops.getOrNull(i - 1)) ?: continue
            val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
            acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, acc.firstPacketIndex, rhs.lastPacketIndex, listOf(acc, opRef, rhs))
        }
        return acc
    }

    private fun buildInfixFunctionCall(ctx: KotlinParser.InfixFunctionCallContext): FrameDraft? {
        val operands = ctx.rangeExpression()
        if (operands.isEmpty()) return null
        var acc = buildRangeExpression(operands[0]) ?: return null
        val ops = ctx.simpleIdentifier()
        for (i in 1 until operands.size) {
            val rhs = buildRangeExpression(operands[i]) ?: continue
            val opIdCtx = ops.getOrNull(i - 1) ?: continue
            markIdentRole(opIdCtx, SymbolRole.FUNCTION)
            val opB = ctxBounds(opIdCtx) ?: continue
            val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
            acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, acc.firstPacketIndex, rhs.lastPacketIndex, listOf(acc, opRef, rhs))
        }
        return acc
    }

    private fun buildRangeExpression(ctx: KotlinParser.RangeExpressionContext): FrameDraft? {
        val ch = ctx.children ?: return null
        var acc: FrameDraft? = null
        var pendingOpBounds: Pair<Int, Int>? = null
        for (node in ch) {
            when (node) {
                is KotlinParser.AdditiveExpressionContext -> {
                    val expr = buildAdditiveExpression(node) ?: continue
                    val prev = acc
                    val opB = pendingOpBounds
                    if (prev != null && opB != null) {
                        val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
                        acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, prev.firstPacketIndex, expr.lastPacketIndex, listOf(prev, opRef, expr))
                        pendingOpBounds = null
                    } else {
                        acc = expr
                    }
                }
                is TerminalNode -> {
                    val t = node.symbol?.type
                    if (t == KotlinLexer.Tokens.RANGE || t == KotlinLexer.Tokens.RANGE_UNTIL) {
                        pendingOpBounds = termBounds(node)
                    }
                }
            }
        }
        return acc
    }

    private fun buildAdditiveExpression(ctx: KotlinParser.AdditiveExpressionContext): FrameDraft? {
        val operands = ctx.multiplicativeExpression()
        if (operands.isEmpty()) return null
        var acc = buildMultiplicativeExpression(operands[0]) ?: return null
        val ops = ctx.additiveOperator()
        for (i in 1 until operands.size) {
            val rhs = buildMultiplicativeExpression(operands[i]) ?: continue
            val opB = ctxBounds(ops.getOrNull(i - 1)) ?: continue
            val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
            acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, acc.firstPacketIndex, rhs.lastPacketIndex, listOf(acc, opRef, rhs))
        }
        return acc
    }

    private fun buildMultiplicativeExpression(ctx: KotlinParser.MultiplicativeExpressionContext): FrameDraft? {
        val operands = ctx.asExpression()
        if (operands.isEmpty()) return null
        var acc = buildAsExpression(operands[0]) ?: return null
        val ops = ctx.multiplicativeOperator()
        for (i in 1 until operands.size) {
            val rhs = buildAsExpression(operands[i]) ?: continue
            val opB = ctxBounds(ops.getOrNull(i - 1)) ?: continue
            val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
            acc = FrameDraft(KtNodeTypes.BINARY_EXPRESSION_ID, acc.firstPacketIndex, rhs.lastPacketIndex, listOf(acc, opRef, rhs))
        }
        return acc
    }

    private fun buildAsExpression(ctx: KotlinParser.AsExpressionContext): FrameDraft? {
        var acc = ctx.prefixUnaryExpression()?.let { buildPrefixUnaryExpression(it) } ?: return null
        val ops = ctx.asOperator()
        val types = ctx.type()
        val count = minOf(ops.size, types.size)
        for (i in 0 until count) {
            val opB = ctxBounds(ops[i]) ?: continue
            val tr = buildTypeReference(types[i]) ?: continue
            val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
            acc = FrameDraft(KtNodeTypes.BINARY_WITH_TYPE_ID, acc.firstPacketIndex, tr.lastPacketIndex, listOf(acc, opRef, tr))
        }
        return acc
    }

    private fun buildPrefixUnaryExpression(ctx: KotlinParser.PrefixUnaryExpressionContext): FrameDraft? {
        var acc = ctx.postfixUnaryExpression()?.let { buildPostfixUnaryExpression(it) } ?: return null
        for (prefix in ctx.unaryPrefix().asReversed()) {
            prefix.prefixUnaryOperator()?.let { opCtx ->
                val opB = ctxBounds(opCtx)
                if (opB != null) {
                    val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
                    acc = FrameDraft(KtNodeTypes.PREFIX_EXPRESSION_ID, opB.first, acc.lastPacketIndex, listOf(opRef, acc))
                }
            }
            prefix.label()?.let { lblCtx ->
                val lb = ctxBounds(lblCtx)
                if (lb != null) {
                    markIdentRole(lblCtx.simpleIdentifier(), SymbolRole.LABEL)
                    val lq = FrameDraft(KtNodeTypes.LABEL_QUALIFIER_ID, lb.first, lb.second)
                    acc = FrameDraft(KtNodeTypes.LABELED_EXPRESSION_ID, lb.first, acc.lastPacketIndex, listOf(lq, acc))
                }
            }
            prefix.annotation()?.let { annCtx ->
                val annDraft = buildAnnotationDraft(annCtx)
                if (annDraft != null) {
                    acc = FrameDraft(KtNodeTypes.ANNOTATED_EXPRESSION_ID, annDraft.firstPacketIndex, acc.lastPacketIndex, listOf(annDraft, acc))
                }
            }
        }
        return acc
    }

    private fun buildPostfixUnaryExpression(ctx: KotlinParser.PostfixUnaryExpressionContext): FrameDraft? {
        var acc = ctx.primaryExpression()?.let { buildPrimaryExpression(it) } ?: return null
        val suffixes = ctx.postfixUnarySuffix()
        var i = 0
        while (i < suffixes.size) {
            val suf = suffixes[i]
            val explicitTa = suf.typeArguments()
            if (explicitTa != null && i + 1 < suffixes.size && suffixes[i + 1].callSuffix() != null) {
                acc = applyCallSuffix(acc, explicitTypeArgs = explicitTa, callSuffix = suffixes[i + 1].callSuffix()!!)
                i += 2
                continue
            }
            val cs = suf.callSuffix()
            if (cs != null) {
                acc = applyCallSuffix(acc, explicitTypeArgs = null, callSuffix = cs)
                i += 1
                continue
            }
            val idxSuf = suf.indexingSuffix()
            if (idxSuf != null) {
                val ib = ctxBounds(idxSuf)
                if (ib != null) {
                    val exprs = idxSuf.expression().mapNotNull { buildExpression(it) }
                    val indices = FrameDraft(KtNodeTypes.INDICES_ID, ib.first, ib.second, exprs)
                    acc = FrameDraft(KtNodeTypes.ARRAY_ACCESS_EXPRESSION_ID, acc.firstPacketIndex, ib.second, listOf(acc, indices))
                }
                i += 1
                continue
            }
            val navSuf = suf.navigationSuffix()
            if (navSuf != null) {
                val next1 = suffixes.getOrNull(i + 1)
                val next2 = suffixes.getOrNull(i + 2)
                if (next1?.typeArguments() != null && next2?.callSuffix() != null && navSuf.CLASS() == null && navSuf.memberAccessOperator()?.COLONCOLON() == null) {
                    acc = applyNavigationSuffix(acc, navSuf, callTypeArgs = next1.typeArguments(), callSuffix = next2.callSuffix())
                    i += 3
                } else if (next1?.callSuffix() != null && navSuf.CLASS() == null && navSuf.memberAccessOperator()?.COLONCOLON() == null) {
                    acc = applyNavigationSuffix(acc, navSuf, callTypeArgs = null, callSuffix = next1.callSuffix())
                    i += 2
                } else {
                    acc = applyNavigationSuffix(acc, navSuf, callTypeArgs = null, callSuffix = null)
                    i += 1
                }
                continue
            }
            val postOp = suf.postfixUnaryOperator()
            if (postOp != null) {
                val opB = ctxBounds(postOp)
                if (opB != null) {
                    val opRef = FrameDraft(KtNodeTypes.OPERATION_REFERENCE_ID, opB.first, opB.second)
                    acc = FrameDraft(KtNodeTypes.POSTFIX_EXPRESSION_ID, acc.firstPacketIndex, opB.second, listOf(acc, opRef))
                }
                i += 1
                continue
            }
            i += 1
        }
        return acc
    }

    private fun applyCallSuffix(
        callee: FrameDraft,
        explicitTypeArgs: KotlinParser.TypeArgumentsContext?,
        callSuffix: KotlinParser.CallSuffixContext,
    ): FrameDraft {
        val csBounds = ctxBounds(callSuffix) ?: return callee
        val callChildren = ArrayList<FrameDraft>(4)
        callChildren.add(callee)
        val taCtx = explicitTypeArgs ?: callSuffix.typeArguments()
        if (taCtx != null) {
            buildTypeArgumentList(taCtx)?.let(callChildren::add)
        }
        callSuffix.valueArguments()?.let { va ->
            buildValueArgumentList(va)?.let(callChildren::add)
        }
        callSuffix.annotatedLambda()?.let { al ->
            val alb = ctxBounds(al)
            val lamDraft = buildAnnotatedLambda(al)
            if (alb != null && lamDraft != null) {
                callChildren.add(FrameDraft(KtNodeTypes.LAMBDA_ARGUMENT_ID, alb.first, alb.second, listOf(lamDraft)))
            }
        }
        return FrameDraft(KtNodeTypes.CALL_EXPRESSION_ID, callee.firstPacketIndex, csBounds.second, callChildren)
    }

    private fun applyNavigationSuffix(
        receiver: FrameDraft,
        navSuffix: KotlinParser.NavigationSuffixContext,
        callTypeArgs: KotlinParser.TypeArgumentsContext?,
        callSuffix: KotlinParser.CallSuffixContext?,
    ): FrameDraft {
        val navBounds = ctxBounds(navSuffix) ?: return receiver
        val opCtx = navSuffix.memberAccessOperator()
        val isColonColon = opCtx?.COLONCOLON() != null
        val isSafeNav = opCtx?.getRuleContextOfType<KotlinParser.SafeNavContext>(0) != null

        if (isColonColon) {
            if (navSuffix.CLASS() != null) {
                return FrameDraft(KtNodeTypes.CLASS_LITERAL_EXPRESSION_ID, receiver.firstPacketIndex, navBounds.second, listOf(receiver))
            }
            val selDraft =
                navSuffix.simpleIdentifier()?.let { idCtx ->
                    ctxBounds(idCtx)?.let { ib -> FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, ib.first, ib.second) }
                }
            return FrameDraft(
                nodeTypeId = KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION_ID,
                firstPacketIndex = receiver.firstPacketIndex,
                lastPacketIndex = navBounds.second,
                children = listOfNotNull(receiver, selDraft),
            )
        }

        val baseSelector: FrameDraft =
            navSuffix.simpleIdentifier()?.let { idCtx ->
                ctxBounds(idCtx)?.let { ib -> FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, ib.first, ib.second) }
            }
                ?: navSuffix.parenthesizedExpression()?.let { buildParenthesizedExpression(it) }
                ?: return receiver

        val finalSelector =
            if (callSuffix != null) {
                applyCallSuffix(baseSelector, explicitTypeArgs = callTypeArgs, callSuffix = callSuffix)
            } else {
                baseSelector
            }

        val nodeId = if (isSafeNav) KtNodeTypes.SAFE_ACCESS_EXPRESSION_ID else KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID
        return FrameDraft(nodeId, receiver.firstPacketIndex, finalSelector.lastPacketIndex, listOf(receiver, finalSelector))
    }

    private fun buildValueArgumentList(ctx: KotlinParser.ValueArgumentsContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val args = ArrayList<FrameDraft>()
        for (va in ctx.valueArgument()) {
            val vab = ctxBounds(va) ?: continue
            val vaChildren = ArrayList<FrameDraft>(3)
            va.annotation()?.let { buildAnnotationDraft(it)?.let(vaChildren::add) }
            if (va.simpleIdentifier() != null && va.ASSIGNMENT() != null) {
                val idCtx = va.simpleIdentifier()!!
                markIdentRole(idCtx, SymbolRole.PARAM)
                val idB = ctxBounds(idCtx)
                val eqB = termBounds(va.ASSIGNMENT())
                if (idB != null && eqB != null) {
                    val refExpr = FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, idB.first, idB.second)
                    vaChildren.add(FrameDraft(KtNodeTypes.VALUE_ARGUMENT_NAME_ID, idB.first, eqB.second, listOf(refExpr)))
                }
            }
            va.expression()?.let { buildExpression(it)?.let(vaChildren::add) }
            args.add(FrameDraft(KtNodeTypes.VALUE_ARGUMENT_ID, vab.first, vab.second, vaChildren))
        }
        return FrameDraft(KtNodeTypes.VALUE_ARGUMENT_LIST_ID, b.first, b.second, args)
    }

    private fun buildPrimaryExpression(ctx: KotlinParser.PrimaryExpressionContext): FrameDraft? {
        ctx.parenthesizedExpression()?.let { return buildParenthesizedExpression(it) }
        ctx.simpleIdentifier()?.let { idCtx ->
            val ib = ctxBounds(idCtx) ?: return null
            return FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, ib.first, ib.second)
        }
        ctx.literalConstant()?.let { return buildLiteralConstant(it) }
        ctx.stringLiteral()?.let { return buildStringLiteral(it) }
        ctx.callableReference()?.let { return buildCallableReference(it) }
        ctx.functionLiteral()?.let { fnLit ->
            fnLit.getRuleContextOfType<KotlinParser.LambdaLiteralContext>(0)?.let { return buildLambdaLiteral(it) }
            fnLit.getRuleContextOfType<KotlinParser.AnonymousFunctionContext>(0)?.let { return buildAnonymousFunction(it) }
        }
        ctx.objectLiteral()?.let { return buildObjectLiteral(it) }
        ctx.collectionLiteral()?.let { col ->
            val cb = ctxBounds(col) ?: return null
            val exprs = col.expression().mapNotNull { buildExpression(it) }
            return FrameDraft(KtNodeTypes.COLLECTION_LITERAL_EXPRESSION_ID, cb.first, cb.second, exprs)
        }
        ctx.thisExpression()?.let { return buildThisExpression(it) }
        ctx.superExpression()?.let { return buildSuperExpression(it) }
        ctx.ifExpression()?.let { return buildIfExpression(it) }
        ctx.whenExpression()?.let { return buildWhenExpression(it) }
        ctx.tryExpression()?.let { return buildTryExpression(it) }
        ctx.jumpExpression()?.let { return buildJumpExpression(it) }
        return null
    }

    private fun buildParenthesizedExpression(ctx: KotlinParser.ParenthesizedExpressionContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val inner = ctx.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let { buildExpression(it) }
        return FrameDraft(KtNodeTypes.PARENTHESIZED_ID, b.first, b.second, listOfNotNull(inner))
    }

    private fun buildLiteralConstant(ctx: KotlinParser.LiteralConstantContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val pkt = packets[b.first] as? SyntaxPacket.LiteralPacket
        val nodeType =
            when (pkt?.literalKind) {
                LiteralPacketKind.BOOLEAN -> KtNodeTypes.BOOLEAN_CONSTANT_ID
                LiteralPacketKind.INT -> KtNodeTypes.INTEGER_CONSTANT_ID
                LiteralPacketKind.FLOAT -> KtNodeTypes.FLOAT_CONSTANT_ID
                LiteralPacketKind.CHAR -> KtNodeTypes.CHARACTER_CONSTANT_ID
                LiteralPacketKind.NULL -> KtNodeTypes.NULL_ID
                null -> KtNodeTypes.INTEGER_CONSTANT_ID
            }
        val flags = (pkt?.flags ?: PacketFlags.NONE) and PacketFlags.HAS_LONE_SURROGATE
        return FrameDraft(nodeType, b.first, b.second, flags = flags)
    }

    private fun buildStringLiteral(ctx: KotlinParser.StringLiteralContext): FrameDraft? {
        ctx.getRuleContextOfType<KotlinParser.LineStringLiteralContext>(0)?.let { return buildLineStringTemplate(it) }
        ctx.getRuleContextOfType<KotlinParser.MultiLineStringLiteralContext>(0)?.let { return buildMultiLineStringTemplate(it) }
        return null
    }

    private fun buildLineStringTemplate(ctx: KotlinParser.LineStringLiteralContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val entries = ArrayList<FrameDraft>()
        var templateFlags = PacketFlags.NONE
        val ch = ctx.children
        if (ch != null) {
            for (node in ch) {
                appendStringChildEntry(node, entries)?.let { entryFlags ->
                    templateFlags = templateFlags or entryFlags
                }
            }
        }
        return FrameDraft(KtNodeTypes.STRING_TEMPLATE_ID, b.first, b.second, entries, flags = templateFlags)
    }

    private fun buildMultiLineStringTemplate(ctx: KotlinParser.MultiLineStringLiteralContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val entries = ArrayList<FrameDraft>()
        var templateFlags = PacketFlags.RAW_MULTILINE_STRING
        val ch = ctx.children
        if (ch != null) {
            for (node in ch) {
                appendStringChildEntry(node, entries)?.let { entryFlags ->
                    templateFlags = templateFlags or entryFlags
                }
            }
        }
        return FrameDraft(KtNodeTypes.STRING_TEMPLATE_ID, b.first, b.second, entries, flags = templateFlags)
    }

    private fun appendStringChildEntry(node: ParseTree, out: MutableList<FrameDraft>): Long? {
        when (node) {
            is KotlinParser.LineStringContentContext -> {
                val cb = ctxBounds(node) ?: return null
                val pkt = packets[cb.first]
                val flags = pkt.flags and PacketFlags.HAS_LONE_SURROGATE
                val refTerm = node.getToken(KotlinLexer.Tokens.LineStrRef, 0)
                if (refTerm != null) {
                    val refExpr = FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, cb.second, cb.second)
                    out.add(FrameDraft(KtNodeTypes.SHORT_STRING_TEMPLATE_ENTRY_ID, cb.first, cb.second, listOf(refExpr), PacketFlags.INTERPOLATED_STRING))
                    return PacketFlags.INTERPOLATED_STRING
                }
                val isEsc = node.getToken(KotlinLexer.Tokens.LineStrEscapedChar, 0) != null
                val entryType = if (isEsc) KtNodeTypes.ESCAPE_STRING_TEMPLATE_ENTRY_ID else KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY_ID
                out.add(FrameDraft(entryType, cb.first, cb.second, flags = flags))
                return flags
            }

            is KotlinParser.MultiLineStringContentContext -> {
                val cb = ctxBounds(node) ?: return null
                val pkt = packets[cb.first]
                val flags = pkt.flags and PacketFlags.HAS_LONE_SURROGATE
                val refTerm = node.getToken(KotlinLexer.Tokens.MultiLineStrRef, 0)
                if (refTerm != null) {
                    val refExpr = FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, cb.second, cb.second)
                    out.add(FrameDraft(KtNodeTypes.SHORT_STRING_TEMPLATE_ENTRY_ID, cb.first, cb.second, listOf(refExpr), PacketFlags.INTERPOLATED_STRING))
                    return PacketFlags.INTERPOLATED_STRING
                }
                out.add(FrameDraft(KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY_ID, cb.first, cb.second, flags = flags))
                return flags
            }

            is KotlinParser.LineStringExpressionContext -> {
                val eb = ctxBounds(node) ?: return null
                val expr = node.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let { buildExpression(it) }
                out.add(FrameDraft(KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY_ID, eb.first, eb.second, listOfNotNull(expr), PacketFlags.INTERPOLATED_STRING))
                return PacketFlags.INTERPOLATED_STRING
            }

            is KotlinParser.MultiLineStringExpressionContext -> {
                val eb = ctxBounds(node) ?: return null
                val expr = node.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let { buildExpression(it) }
                out.add(FrameDraft(KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY_ID, eb.first, eb.second, listOfNotNull(expr), PacketFlags.INTERPOLATED_STRING))
                return PacketFlags.INTERPOLATED_STRING
            }

            is TerminalNode -> {
                if (node.symbol?.type == KotlinLexer.Tokens.MultiLineStringQuote) {
                    val qb = termBounds(node) ?: return null
                    out.add(FrameDraft(KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY_ID, qb.first, qb.second))
                    return PacketFlags.NONE
                }
            }
        }
        return null
    }

    private fun buildCallableReference(ctx: KotlinParser.CallableReferenceContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(2)
        ctx.receiverType()?.let { buildReceiverTypeReference(it)?.let(children::add) }
        if (ctx.CLASS() != null) {
            return FrameDraft(KtNodeTypes.CLASS_LITERAL_EXPRESSION_ID, b.first, b.second, children)
        }
        ctx.simpleIdentifier()?.let { idCtx ->
            ctxBounds(idCtx)?.let { ib ->
                children.add(FrameDraft(KtNodeTypes.REFERENCE_EXPRESSION_ID, ib.first, ib.second))
            }
        }
        return FrameDraft(KtNodeTypes.CALLABLE_REFERENCE_EXPRESSION_ID, b.first, b.second, children)
    }

    private fun buildAnnotatedLambda(ctx: KotlinParser.AnnotatedLambdaContext): FrameDraft? {
        val lamCtx = ctx.lambdaLiteral() ?: return null
        var draft = buildLambdaLiteral(lamCtx) ?: return null
        ctx.label()?.let { lbl ->
            val lb = ctxBounds(lbl)
            if (lb != null) {
                markIdentRole(lbl.simpleIdentifier(), SymbolRole.LABEL)
                val lq = FrameDraft(KtNodeTypes.LABEL_QUALIFIER_ID, lb.first, lb.second)
                draft = FrameDraft(KtNodeTypes.LABELED_EXPRESSION_ID, lb.first, draft.lastPacketIndex, listOf(lq, draft))
            }
        }
        val annots = ctx.annotation()
        if (annots.isNotEmpty()) {
            val entries = annots.mapNotNull { buildAnnotationDraft(it) }
            if (entries.isNotEmpty()) {
                draft = FrameDraft(KtNodeTypes.ANNOTATED_EXPRESSION_ID, entries.first().firstPacketIndex, draft.lastPacketIndex, entries + draft)
            }
        }
        return draft
    }

    private fun buildLambdaLiteral(ctx: KotlinParser.LambdaLiteralContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val fnChildren = ArrayList<FrameDraft>(2)
        ctx.getRuleContextOfType<KotlinParser.LambdaParametersContext>(0)?.let { lpCtx ->
            val lpb = ctxBounds(lpCtx)
            if (lpb != null) {
                val params = ArrayList<FrameDraft>()
                for (lp in lpCtx.getRuleContextsOfType<KotlinParser.LambdaParameterContext>()) {
                    val pb = ctxBounds(lp) ?: continue
                    val vd = lp.getRuleContextOfType<KotlinParser.VariableDeclarationContext>(0)
                    val mvd = lp.getRuleContextOfType<KotlinParser.MultiVariableDeclarationContext>(0)
                    val pChildren = ArrayList<FrameDraft>(2)
                    if (vd != null) {
                        markIdentRole(vd.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.PARAM)
                        vd.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(pChildren::add) }
                    } else if (mvd != null) {
                        val entries = ArrayList<FrameDraft>()
                        for (evd in mvd.getRuleContextsOfType<KotlinParser.VariableDeclarationContext>()) {
                            val eb = ctxBounds(evd) ?: continue
                            val eChildren = ArrayList<FrameDraft>(1)
                            markIdentRole(evd.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.PARAM)
                            evd.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(eChildren::add) }
                            entries.add(FrameDraft(KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY_ID, eb.first, eb.second, eChildren))
                        }
                        pChildren.add(FrameDraft(KtNodeTypes.DESTRUCTURING_DECLARATION_ID, pb.first, pb.second, entries))
                        lp.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(pChildren::add) }
                    }
                    params.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_ID, pb.first, pb.second, pChildren))
                }
                fnChildren.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_LIST_ID, lpb.first, lpb.second, params))
            }
        }
        ctx.statements()?.let { stmtsCtx ->
            val stmtDrafts = ArrayList<FrameDraft>()
            appendStatements(stmtsCtx, stmtDrafts)
            val sb = ctxBounds(stmtsCtx)
            if (sb != null && stmtDrafts.isNotEmpty()) {
                fnChildren.add(FrameDraft(KtNodeTypes.BLOCK_ID, sb.first, sb.second, stmtDrafts))
            }
        }
        val fnLit = FrameDraft(KtNodeTypes.FUNCTION_LITERAL_ID, b.first, b.second, fnChildren)
        return FrameDraft(KtNodeTypes.LAMBDA_EXPRESSION_ID, b.first, b.second, listOf(fnLit))
    }

    private fun buildAnonymousFunction(ctx: KotlinParser.AnonymousFunctionContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        var flags = PacketFlags.NONE
        termBounds(ctx.SUSPEND())?.let { sb ->
            flags = PacketFlags.MOD_SUSPEND
            children.add(FrameDraft(KtNodeTypes.MODIFIER_LIST_ID, sb.first, sb.second, flags = PacketFlags.MOD_SUSPEND))
        }
        val types = ctx.getRuleContextsOfType<KotlinParser.TypeContext>()
        val hasReceiverDot = ctx.getToken(KotlinLexer.Tokens.DOT, 0) != null
        if (hasReceiverDot && types.isNotEmpty()) {
            buildTypeReference(types[0])?.let(children::add)
        }
        ctx.parametersWithOptionalType()?.let { pCtx ->
            val pb = ctxBounds(pCtx)
            if (pb != null) {
                val pDrafts = ArrayList<FrameDraft>()
                for (fvp in pCtx.getRuleContextsOfType<KotlinParser.FunctionValueParameterWithOptionalTypeContext>()) {
                    val fvpb = ctxBounds(fvp) ?: continue
                    val sub = ArrayList<FrameDraft>(2)
                    fvp.getRuleContextOfType<KotlinParser.ParameterWithOptionalTypeContext>(0)?.let { pot ->
                        markIdentRole(pot.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.PARAM)
                        pot.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(sub::add) }
                    }
                    pDrafts.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_ID, fvpb.first, fvpb.second, sub))
                }
                children.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_LIST_ID, pb.first, pb.second, pDrafts))
            }
        }
        val retTypeIdx = if (hasReceiverDot) 1 else 0
        types.getOrNull(retTypeIdx)?.let { buildTypeReference(it)?.let(children::add) }
        ctx.getRuleContextOfType<KotlinParser.TypeConstraintsContext>(0)?.let { buildTypeConstraintList(it)?.let(children::add) }
        ctx.getRuleContextOfType<KotlinParser.FunctionBodyContext>(0)?.let { appendFunctionBodyChildren(it, children) }
        return FrameDraft(KtNodeTypes.FUN_ID, b.first, b.second, children, flags = flags)
    }

    private fun buildObjectLiteral(ctx: KotlinParser.ObjectLiteralContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val objChildren = ArrayList<FrameDraft>(2)
        ctx.getRuleContextOfType<KotlinParser.DelegationSpecifiersContext>(0)?.let {
            buildSuperTypeList(it)?.let(objChildren::add)
        }
        ctx.getRuleContextOfType<KotlinParser.ClassBodyContext>(0)?.let {
            buildClassBody(it)?.let(objChildren::add)
        }
        val objDecl = FrameDraft(KtNodeTypes.OBJECT_DECLARATION_ID, b.first, b.second, objChildren)
        return FrameDraft(KtNodeTypes.OBJECT_LITERAL_ID, b.first, b.second, listOf(objDecl))
    }

    private fun buildThisExpression(ctx: KotlinParser.ThisExpressionContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(1)
        if (ctx.THIS_AT() != null && b.second > b.first) {
            children.add(FrameDraft(KtNodeTypes.LABEL_QUALIFIER_ID, b.first + 1, b.second))
        }
        return FrameDraft(KtNodeTypes.THIS_EXPRESSION_ID, b.first, b.second, children)
    }

    private fun buildSuperExpression(ctx: KotlinParser.SuperExpressionContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(2)
        ctx.type()?.let { buildTypeReference(it)?.let(children::add) }
        val superAt = ctx.getToken(KotlinLexer.Tokens.SUPER_AT, 0)
        if (superAt != null) {
            val sb = termBounds(superAt)
            if (sb != null && sb.second > sb.first) {
                children.add(FrameDraft(KtNodeTypes.LABEL_QUALIFIER_ID, sb.first + 1, sb.second))
            }
        } else if (ctx.AT_NO_WS() != null) {
            val atB = termBounds(ctx.AT_NO_WS())
            val idCtx = ctx.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0)
            markIdentRole(idCtx, SymbolRole.LABEL)
            val idB = ctxBounds(idCtx)
            if (atB != null && idB != null) {
                children.add(FrameDraft(KtNodeTypes.LABEL_QUALIFIER_ID, atB.first, idB.second))
            }
        }
        return FrameDraft(KtNodeTypes.SUPER_EXPRESSION_ID, b.first, b.second, children)
    }

    private fun buildIfExpression(ctx: KotlinParser.IfExpressionContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>(3)
        ctx.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let { cond ->
            buildExpression(cond)?.let { condDraft ->
                children.add(FrameDraft(KtNodeTypes.CONDITION_ID, condDraft.firstPacketIndex, condDraft.lastPacketIndex, listOf(condDraft)))
            }
        }
        val bodies = ctx.getRuleContextsOfType<KotlinParser.ControlStructureBodyContext>()
        val elseNode = ctx.getToken(KotlinLexer.Tokens.ELSE, 0)
        val elsePkt = termBounds(elseNode)?.first ?: Int.MAX_VALUE
        for (csb in bodies) {
            val bodyDraft = buildControlStructureBodyInner(csb) ?: continue
            val wrapperId = if (bodyDraft.firstPacketIndex < elsePkt) KtNodeTypes.THEN_ID else KtNodeTypes.ELSE_ID
            children.add(FrameDraft(wrapperId, bodyDraft.firstPacketIndex, bodyDraft.lastPacketIndex, listOf(bodyDraft)))
        }
        return FrameDraft(KtNodeTypes.IF_ID, b.first, b.second, children)
    }

    private fun buildWhenExpression(ctx: KotlinParser.WhenExpressionContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        ctx.getRuleContextOfType<KotlinParser.WhenSubjectContext>(0)?.let { subjCtx ->
            val vd = subjCtx.getRuleContextOfType<KotlinParser.VariableDeclarationContext>(0)
            val expr = subjCtx.getRuleContextOfType<KotlinParser.ExpressionContext>(0)
            if (vd != null && expr != null) {
                val valTerm = subjCtx.getToken(KotlinLexer.Tokens.VAL, 0)
                val annots = subjCtx.getRuleContextsOfType<KotlinParser.AnnotationContext>()
                val exprDraft = buildExpression(expr)
                val valB = termBounds(valTerm)
                if (valB != null && exprDraft != null) {
                    val propChildren = ArrayList<FrameDraft>(3)
                    var propStart = valB.first
                    if (annots.isNotEmpty()) {
                        buildModifiersDraft(null, extraLeadingAnnotations = annots)?.let {
                            propStart = minOf(propStart, it.firstPacketIndex)
                            propChildren.add(it)
                        }
                    }
                    markIdentRole(vd.getRuleContextOfType<KotlinParser.SimpleIdentifierContext>(0), SymbolRole.LOCAL)
                    vd.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(propChildren::add) }
                    propChildren.add(exprDraft)
                    children.add(FrameDraft(KtNodeTypes.PROPERTY_ID, propStart, exprDraft.lastPacketIndex, propChildren))
                }
            } else if (expr != null) {
                buildExpression(expr)?.let(children::add)
            }
        }

        for (entry in ctx.getRuleContextsOfType<KotlinParser.WhenEntryContext>()) {
            val eb = ctxBounds(entry) ?: continue
            val entryChildren = ArrayList<FrameDraft>()
            for (wc in entry.whenCondition()) {
                val wcb = ctxBounds(wc) ?: continue
                wc.typeTest()?.let { tt ->
                    val ttChildren = ArrayList<FrameDraft>(1)
                    tt.getRuleContextOfType<KotlinParser.TypeContext>(0)?.let { buildTypeReference(it)?.let(ttChildren::add) }
                    entryChildren.add(FrameDraft(KtNodeTypes.WHEN_CONDITION_IS_PATTERN_ID, wcb.first, wcb.second, ttChildren))
                }
                wc.rangeTest()?.let { rt ->
                    val rtChildren = ArrayList<FrameDraft>(1)
                    rt.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let { buildExpression(it)?.let(rtChildren::add) }
                    entryChildren.add(FrameDraft(KtNodeTypes.WHEN_CONDITION_IN_RANGE_ID, wcb.first, wcb.second, rtChildren))
                }
                wc.expression()?.let { expr ->
                    buildExpression(expr)?.let { exprDraft ->
                        entryChildren.add(FrameDraft(KtNodeTypes.WHEN_CONDITION_EXPRESSION_ID, wcb.first, wcb.second, listOf(exprDraft)))
                    }
                }
            }
            entry.whenEntryGuard()?.let { guardCtx ->
                val gb = ctxBounds(guardCtx)
                val gExpr = guardCtx.getRuleContextOfType<KotlinParser.ExpressionContext>(0)?.let { buildExpression(it) }
                if (gb != null && gExpr != null) {
                    entryChildren.add(FrameDraft(KtNodeTypes.WHEN_ENTRY_GUARD_ID, gb.first, gb.second, listOf(gExpr)))
                }
            }
            entry.controlStructureBody()?.let { csb ->
                buildControlStructureBodyInner(csb)?.let(entryChildren::add)
            }
            children.add(FrameDraft(KtNodeTypes.WHEN_ENTRY_ID, eb.first, eb.second, entryChildren))
        }
        return FrameDraft(KtNodeTypes.WHEN_ID, b.first, b.second, children)
    }

    private fun buildTryExpression(ctx: KotlinParser.TryExpressionContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        val children = ArrayList<FrameDraft>()
        ctx.getRuleContextOfType<KotlinParser.BlockContext>(0)?.let { buildBlock(it)?.let(children::add) }
        for (catchCtx in ctx.getRuleContextsOfType<KotlinParser.CatchBlockContext>()) {
            val cb = ctxBounds(catchCtx) ?: continue
            val cChildren = ArrayList<FrameDraft>(2)
            val lp = termBounds(catchCtx.LPAREN())
            val rp = termBounds(catchCtx.RPAREN())
            if (lp != null && rp != null) {
                val vpChildren = ArrayList<FrameDraft>(2)
                val annots = catchCtx.getRuleContextsOfType<KotlinParser.AnnotationContext>()
                if (annots.isNotEmpty()) {
                    buildModifiersDraft(null, extraLeadingAnnotations = annots)?.let(vpChildren::add)
                }
                markIdentRole(catchCtx.simpleIdentifier(), SymbolRole.PARAM)
                catchCtx.type()?.let { buildTypeReference(it)?.let(vpChildren::add) }
                val identBounds = ctxBounds(catchCtx.simpleIdentifier())
                val firstChildIdx = vpChildren.firstOrNull()?.firstPacketIndex
                val lastChildIdx = vpChildren.lastOrNull()?.lastPacketIndex
                val vpStart =
                    when {
                        firstChildIdx != null && identBounds != null -> minOf(firstChildIdx, identBounds.first)
                        firstChildIdx != null -> firstChildIdx
                        identBounds != null -> identBounds.first
                        else -> lp.first
                    }
                val vpEnd =
                    when {
                        lastChildIdx != null && identBounds != null -> maxOf(lastChildIdx, identBounds.second)
                        lastChildIdx != null -> lastChildIdx
                        identBounds != null -> identBounds.second
                        else -> rp.second
                    }
                val vpDraft = FrameDraft(KtNodeTypes.VALUE_PARAMETER_ID, vpStart, vpEnd, vpChildren)
                cChildren.add(FrameDraft(KtNodeTypes.VALUE_PARAMETER_LIST_ID, lp.first, rp.second, listOf(vpDraft)))
            }
            catchCtx.getRuleContextOfType<KotlinParser.BlockContext>(0)?.let { buildBlock(it)?.let(cChildren::add) }
            children.add(FrameDraft(KtNodeTypes.CATCH_ID, cb.first, cb.second, cChildren))
        }
        ctx.getRuleContextOfType<KotlinParser.FinallyBlockContext>(0)?.let { finCtx ->
            val fb = ctxBounds(finCtx)
            if (fb != null) {
                val finChildren = ArrayList<FrameDraft>(1)
                finCtx.getRuleContextOfType<KotlinParser.BlockContext>(0)?.let { buildBlock(it)?.let(finChildren::add) }
                children.add(FrameDraft(KtNodeTypes.FINALLY_ID, fb.first, fb.second, finChildren))
            }
        }
        return FrameDraft(KtNodeTypes.TRY_ID, b.first, b.second, children)
    }

    private fun buildJumpExpression(ctx: KotlinParser.JumpExpressionContext): FrameDraft? {
        val b = ctxBounds(ctx) ?: return null
        if (ctx.THROW() != null) {
            val expr = ctx.expression()?.let { buildExpression(it) }
            return FrameDraft(KtNodeTypes.THROW_ID, b.first, b.second, listOfNotNull(expr))
        }
        if (ctx.RETURN() != null || ctx.RETURN_AT() != null) {
            val children = ArrayList<FrameDraft>(2)
            val retAt = ctx.RETURN_AT()
            if (retAt != null) {
                val rb = termBounds(retAt)
                if (rb != null && rb.second > rb.first) {
                    children.add(FrameDraft(KtNodeTypes.LABEL_QUALIFIER_ID, rb.first + 1, rb.second))
                }
            }
            ctx.expression()?.let { buildExpression(it)?.let(children::add) }
            return FrameDraft(KtNodeTypes.RETURN_ID, b.first, b.second, children)
        }
        val contAt = ctx.getToken(KotlinLexer.Tokens.CONTINUE_AT, 0)
        if (ctx.getToken(KotlinLexer.Tokens.CONTINUE, 0) != null || contAt != null) {
            val children = ArrayList<FrameDraft>(1)
            if (contAt != null) {
                val cb = termBounds(contAt)
                if (cb != null && cb.second > cb.first) {
                    children.add(FrameDraft(KtNodeTypes.LABEL_QUALIFIER_ID, cb.first + 1, cb.second))
                }
            }
            return FrameDraft(KtNodeTypes.CONTINUE_ID, b.first, b.second, children)
        }
        val brkAt = ctx.getToken(KotlinLexer.Tokens.BREAK_AT, 0)
        val children = ArrayList<FrameDraft>(1)
        if (brkAt != null) {
            val bb = termBounds(brkAt)
            if (bb != null && bb.second > bb.first) {
                children.add(FrameDraft(KtNodeTypes.LABEL_QUALIFIER_ID, bb.first + 1, bb.second))
            }
        }
        return FrameDraft(KtNodeTypes.BREAK_ID, b.first, b.second, children)
    }
}

internal fun verifyExactPacketPartition(stream: PacketStream) {
    val packets = stream.packets
    for (frame in stream.frames) {
        if (frame.firstPacketIndex > frame.lastPacketIndex) {
            check(frame.childCount == 0) {
                "${stream.sourcePath}: empty frame ${frame.debugName} must have 0 children"
            }
            continue
        }
        check(frame.firstPacketIndex in packets.indices && frame.lastPacketIndex in packets.indices) {
            "${stream.sourcePath}: frame ${frame.debugName} bounds ${frame.firstPacketIndex}..${frame.lastPacketIndex} out of range 0..${packets.lastIndex}"
        }
        var expectedNext = frame.firstPacketIndex
        for (i in 0 until frame.childCount) {
            val ref = frame.childRefAt(i)
            if (frame.isChildFrame(ref)) {
                val child = stream.frames[frame.childFrameIndex(ref)]
                check(child.parentFrameIndex == frame.frameIndex) {
                    "${stream.sourcePath}: parent mismatch on ${child.debugName}"
                }
                check(child.firstPacketIndex == expectedNext) {
                    "${stream.sourcePath}: gap/overlap in ${frame.debugName}: expected $expectedNext, got ${child.firstPacketIndex}"
                }
                expectedNext = child.lastPacketIndex + 1
            } else {
                val pktIdx = frame.childPacketIndex(ref)
                check(pktIdx == expectedNext) {
                    "${stream.sourcePath}: leaf gap/overlap in ${frame.debugName}: expected $expectedNext, got $pktIdx"
                }
                expectedNext = pktIdx + 1
            }
        }
        check(expectedNext == frame.lastPacketIndex + 1) {
            "${stream.sourcePath}: incomplete partition in ${frame.debugName}: ended at ${expectedNext - 1}, expected ${frame.lastPacketIndex}"
        }
    }
}
