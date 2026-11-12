package io.github.kotlinmania.rustkotlin.kcpp

import io.github.kotlinmania.procmacro.vendored.kotlin.kmp.parser.KtNodeTypes
import io.github.kotlinmania.rustkotlin.packet.PacketFlags
import io.github.kotlinmania.rustkotlin.packet.PacketFrame
import io.github.kotlinmania.rustkotlin.packet.PacketStream
import io.github.kotlinmania.rustkotlin.packet.SyntaxPacket

internal data class DeadSpillReset(
    val afterStmtFrameIndex: Int,
    val cppVarName: String,
    val isKonanExternalRc: Boolean,
)

internal class GcAndOwnershipPlan(
    val spilledVarFrameIndices: Set<Int>,
    val konanExternalRcVarFrameIndices: Set<Int>,
    val konanStackRootVarFrameIndices: Set<Int>,
    val deadResetsAfterStmt: Map<Int, List<DeadSpillReset>>,
    val heapWriteBarrierExprFrames: Set<Int>,
    val foreignThreadEntryFunFrames: Set<Int>,
    val cleanerCallFrames: Set<Int>,
)

/**
 * Pass 3: Ownership (`OWNED_VALUE` / `BORROWED_REF` / `SHARED_OWNER`), cross-suspension
 * live-range spill analysis (`SPILL_ACROSS_SUSPEND` / `DEAD_BEFORE_NEXT_SUSPEND`), and
 * Kotlin/Native Docking-Ring GC bridge lowering (`ObjHolder` vs `OwningExternalRCRef`,
 * `UpdateHeapRef`, `CalledFromNativeGuard`, and capture-free `Cleaner` verification).
 */
internal object GcAndOwnershipPass {
    fun run(
        stream: PacketStream,
        config: CppTargetConfig,
        symbols: SameFileSymbolTable,
        suspendPlan: SuspendLoweringPlan,
    ): GcAndOwnershipPlan {
        val spilledVars = LinkedHashSet<Int>()
        val konanExternalRcVars = LinkedHashSet<Int>()
        val konanStackRootVars = LinkedHashSet<Int>()
        val deadResetsAfterStmt = LinkedHashMap<Int, MutableList<DeadSpillReset>>()
        val heapWriteBarriers = LinkedHashSet<Int>()
        val foreignThreadFuns = LinkedHashSet<Int>()
        val cleanerCalls = LinkedHashSet<Int>()

        for (fnSym in symbols.functionsByFrame.values) {
            val fnFrame = stream.frames[fnSym.frameIndex]
            if (config.gcMode == CppGcMode.KONAN_GC &&
                PacketFlags.hasFlag(fnFrame.flags, PacketFlags.FFI_C_ABI_SYMBOL)
            ) {
                fnFrame.flags = fnFrame.flags or PacketFlags.GC_FOREIGN_THREAD_ENTRY
                foreignThreadFuns.add(fnFrame.frameIndex)
            }

            // Stamp parameter ownership flags (`BORROWED_REF` vs `SHARED_OWNER`).
            for (vp in fnSym.valueParams) {
                val vpFrame = stream.frames[vp.frameIndex]
                val typeText = vp.typeFrameIndex?.let { FrameAstHelpers.frameSourceSlice(stream, stream.frames[it]).trim() }.orEmpty()
                val baseType = typeText.substringBefore('<').removeSuffix("?").trim()
                if (baseType in CppTypeTranslator.SHARED_HIERARCHY_TYPES) {
                    vpFrame.flags = vpFrame.flags or PacketFlags.SHARED_OWNER
                } else {
                    vpFrame.flags = vpFrame.flags or PacketFlags.BORROWED_REF
                }
            }

            val bodyIdx = fnSym.bodyFrameIndex ?: continue
            val bodyFrame = stream.frames[bodyIdx]

            // Scan Cleaner calls and heap write barriers across the function body.
            scanBodyBarriersAndCleaners(
                stream = stream,
                root = bodyFrame,
                config = config,
                symbols = symbols,
                enclosingClassFrameIndex = fnSym.enclosingClassFrameIndex,
                heapWriteBarriers = heapWriteBarriers,
                cleanerCalls = cleanerCalls,
            )

            if (!PacketFlags.hasFlag(fnFrame.flags, PacketFlags.MOD_SUSPEND)) {
                if (config.gcMode == CppGcMode.KONAN_GC) {
                    markSynchronousStackRoots(stream, bodyFrame, konanStackRootVars)
                }
                continue
            }

            // Collect packet indices of all suspend call sites reachable in this function's frame.
            val suspendPacketIndices = ArrayList<Int>()
            collectSuspendPacketIndicesInFrame(stream, bodyFrame, suspendPlan.suspendCallFrameIndices, suspendPacketIndices)
            suspendPacketIndices.sort()

            // Collect local variable declarations in the suspend body and compute def..lastUse spans.
            val localProps = ArrayList<LocalVarCandidate>()
            collectLocalPropertiesInSuspendFrame(stream, bodyFrame, topStmtFrameIndex = bodyFrame.frameIndex, out = localProps)

            val usesByName = LinkedHashMap<String, MutableList<UseSite>>()
            collectVarUsesInSuspendFrame(stream, bodyFrame, topStmtFrameIndex = bodyFrame.frameIndex, out = usesByName)

            for (cand in localProps) {
                val uses = usesByName[cand.kotlinName].orEmpty()
                val lastUsePkt = uses.maxOfOrNull { it.packetIndex } ?: cand.defPacketIndex
                val lastUseStmt = uses.maxByOrNull { it.packetIndex }?.enclosingStmtFrameIndex ?: cand.enclosingStmtFrameIndex

                val crossesSuspend =
                    suspendPacketIndices.any { sPkt ->
                        sPkt > cand.defPacketIndex && sPkt < lastUsePkt
                    }
                val propFrame = stream.frames[cand.propFrameIndex]
                val isReferenceCandidate = isManagedOrPointerCandidate(stream, propFrame)

                if (crossesSuspend) {
                    propFrame.flags = propFrame.flags or PacketFlags.SPILL_ACROSS_SUSPEND
                    spilledVars.add(cand.propFrameIndex)
                    val useKonanExternalRc = config.gcMode == CppGcMode.KONAN_GC && isReferenceCandidate
                    if (useKonanExternalRc) {
                        propFrame.flags =
                            propFrame.flags or PacketFlags.GC_MANAGED_OBJECT_REF or PacketFlags.GC_PERSISTENT_EXTERNAL_RC
                        konanExternalRcVars.add(cand.propFrameIndex)
                    }

                    // Check if another suspension point occurs AFTER lastUsePkt: clear dead spilled slot early!
                    val hasLaterSuspend = suspendPacketIndices.any { sPkt -> sPkt > lastUsePkt }
                    if (hasLaterSuspend && isReferenceCandidate && lastUseStmt != bodyFrame.frameIndex) {
                        propFrame.flags = propFrame.flags or PacketFlags.DEAD_BEFORE_NEXT_SUSPEND
                        deadResetsAfterStmt
                            .getOrPut(lastUseStmt) { ArrayList() }
                            .add(
                                DeadSpillReset(
                                    afterStmtFrameIndex = lastUseStmt,
                                    cppVarName = cand.cppName,
                                    isKonanExternalRc = useKonanExternalRc,
                                ),
                            )
                    }
                } else if (config.gcMode == CppGcMode.KONAN_GC && isReferenceCandidate) {
                    propFrame.flags =
                        propFrame.flags or PacketFlags.GC_MANAGED_OBJECT_REF or PacketFlags.GC_PHYSICAL_STACK_ROOT
                    konanStackRootVars.add(cand.propFrameIndex)
                }
            }
        }

        return GcAndOwnershipPlan(
            spilledVarFrameIndices = spilledVars,
            konanExternalRcVarFrameIndices = konanExternalRcVars,
            konanStackRootVarFrameIndices = konanStackRootVars,
            deadResetsAfterStmt = deadResetsAfterStmt,
            heapWriteBarrierExprFrames = heapWriteBarriers,
            foreignThreadEntryFunFrames = foreignThreadFuns,
            cleanerCallFrames = cleanerCalls,
        )
    }

    private data class LocalVarCandidate(
        val propFrameIndex: Int,
        val kotlinName: String,
        val cppName: String,
        val defPacketIndex: Int,
        val enclosingStmtFrameIndex: Int,
    )

    private data class UseSite(
        val packetIndex: Int,
        val enclosingStmtFrameIndex: Int,
    )

    private fun markSynchronousStackRoots(
        stream: PacketStream,
        root: PacketFrame,
        konanStackRootVars: MutableSet<Int>,
    ) {
        if (root.nodeTypeId == KtNodeTypes.FUN_ID || root.nodeTypeId == KtNodeTypes.CLASS_ID) return
        if (root.nodeTypeId == KtNodeTypes.PROPERTY_ID && isManagedOrPointerCandidate(stream, root)) {
            root.flags = root.flags or PacketFlags.GC_MANAGED_OBJECT_REF or PacketFlags.GC_PHYSICAL_STACK_ROOT
            konanStackRootVars.add(root.frameIndex)
        }
        for (child in FrameAstHelpers.childFrames(stream, root)) {
            markSynchronousStackRoots(stream, child, konanStackRootVars)
        }
    }

    private fun scanBodyBarriersAndCleaners(
        stream: PacketStream,
        root: PacketFrame,
        config: CppTargetConfig,
        symbols: SameFileSymbolTable,
        enclosingClassFrameIndex: Int?,
        heapWriteBarriers: MutableSet<Int>,
        cleanerCalls: MutableSet<Int>,
    ) {
        if (root.nodeTypeId == KtNodeTypes.FUN_ID || root.nodeTypeId == KtNodeTypes.CLASS_ID) return

        if (root.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            val calleeRef = FrameAstHelpers.firstChildFrame(stream, root, KtNodeTypes.REFERENCE_EXPRESSION_ID)
            val calleeName = calleeRef?.let { FrameAstHelpers.directIdentPacket(stream, it)?.unescapedName }.orEmpty()
            if (calleeName == "createCleaner") {
                if (subtreeContainsThis(stream, root)) {
                    val loc = "${stream.sourcePath}:${root.span.startLine}:${root.span.startColumn}"
                    throw IllegalArgumentException(
                        "$loc: createCleaner lambda must not capture 'this' (causes immortal GC reference cycle)",
                    )
                }
                root.flags = root.flags or PacketFlags.CLEANER_DETACHED_RESOURCE
                cleanerCalls.add(root.frameIndex)
            }
        }

        if (config.gcMode == CppGcMode.KONAN_GC && root.nodeTypeId == KtNodeTypes.BINARY_EXPRESSION_ID) {
            val children = FrameAstHelpers.childFrames(stream, root)
            if (children.size >= 3) {
                val lhs = children[0]
                val opText = FrameAstHelpers.frameSourceSlice(stream, children[1]).trim()
                if (opText == "=" && isHeapFieldLhs(stream, lhs, symbols, enclosingClassFrameIndex)) {
                    root.flags = root.flags or PacketFlags.GC_HEAP_WRITE_BARRIER
                    heapWriteBarriers.add(root.frameIndex)
                }
            }
        }

        for (child in FrameAstHelpers.childFrames(stream, root)) {
            scanBodyBarriersAndCleaners(
                stream = stream,
                root = child,
                config = config,
                symbols = symbols,
                enclosingClassFrameIndex = enclosingClassFrameIndex,
                heapWriteBarriers = heapWriteBarriers,
                cleanerCalls = cleanerCalls,
            )
        }
    }

    private fun isHeapFieldLhs(
        stream: PacketStream,
        lhs: PacketFrame,
        symbols: SameFileSymbolTable,
        enclosingClassFrameIndex: Int?,
    ): Boolean {
        if (lhs.nodeTypeId == KtNodeTypes.DOT_QUALIFIED_EXPRESSION_ID) {
            return true
        }
        if (lhs.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID && enclosingClassFrameIndex != null) {
            val name = FrameAstHelpers.directIdentPacket(stream, lhs)?.unescapedName ?: return false
            val cls = symbols.classesByFrame[enclosingClassFrameIndex] ?: return false
            if (cls.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == name }) return true
            return cls.memberPropertyFrameIndices.any { pIdx ->
                symbols.propertiesByFrame[pIdx]?.kotlinName == name
            }
        }
        return false
    }

    private fun subtreeContainsThis(
        stream: PacketStream,
        root: PacketFrame,
    ): Boolean {
        if (root.nodeTypeId == KtNodeTypes.THIS_EXPRESSION_ID) return true
        for (child in FrameAstHelpers.childFrames(stream, root)) {
            if (subtreeContainsThis(stream, child)) return true
        }
        return false
    }

    private fun collectSuspendPacketIndicesInFrame(
        stream: PacketStream,
        frame: PacketFrame,
        suspendCallFrameIndices: Set<Int>,
        out: MutableList<Int>,
    ) {
        if (frame.nodeTypeId == KtNodeTypes.FUN_ID || frame.nodeTypeId == KtNodeTypes.CLASS_ID) return
        if (frame.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID &&
            !PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE)
        ) {
            return
        }
        if (frame.frameIndex in suspendCallFrameIndices && frame.nodeTypeId == KtNodeTypes.CALL_EXPRESSION_ID) {
            out.add(frame.firstPacketIndex)
        }
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            collectSuspendPacketIndicesInFrame(stream, child, suspendCallFrameIndices, out)
        }
    }

    private fun collectLocalPropertiesInSuspendFrame(
        stream: PacketStream,
        frame: PacketFrame,
        topStmtFrameIndex: Int,
        out: MutableList<LocalVarCandidate>,
    ) {
        if (frame.nodeTypeId == KtNodeTypes.FUN_ID || frame.nodeTypeId == KtNodeTypes.CLASS_ID) return
        if (frame.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID &&
            !PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE)
        ) {
            return
        }
        if (frame.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            for (stmt in FrameAstHelpers.childFrames(stream, frame)) {
                collectLocalPropertiesInSuspendFrame(stream, stmt, topStmtFrameIndex = stmt.frameIndex, out = out)
            }
            return
        }
        if (frame.nodeTypeId == KtNodeTypes.PROPERTY_ID) {
            val ident = FrameAstHelpers.directIdentPacket(stream, frame)
            if (ident != null) {
                val cppName = ident.targetCppName ?: CppNamingPolicy.toSnakeCase(ident.unescapedName)
                ident.targetCppName = cppName
                out.add(
                    LocalVarCandidate(
                        propFrameIndex = frame.frameIndex,
                        kotlinName = ident.unescapedName,
                        cppName = cppName,
                        defPacketIndex = frame.lastPacketIndex,
                        enclosingStmtFrameIndex = topStmtFrameIndex,
                    ),
                )
            }
        }
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            collectLocalPropertiesInSuspendFrame(stream, child, topStmtFrameIndex, out)
        }
    }

    private fun collectVarUsesInSuspendFrame(
        stream: PacketStream,
        frame: PacketFrame,
        topStmtFrameIndex: Int,
        out: MutableMap<String, MutableList<UseSite>>,
    ) {
        if (frame.nodeTypeId == KtNodeTypes.FUN_ID || frame.nodeTypeId == KtNodeTypes.CLASS_ID) return
        if (frame.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID &&
            !PacketFlags.hasFlag(frame.flags, PacketFlags.HAS_SUSPEND_IN_SUBTREE)
        ) {
            return
        }
        if (frame.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            for (stmt in FrameAstHelpers.childFrames(stream, frame)) {
                collectVarUsesInSuspendFrame(stream, stmt, topStmtFrameIndex = stmt.frameIndex, out = out)
            }
            return
        }
        if (frame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
            val ident = FrameAstHelpers.directIdentPacket(stream, frame)
            if (ident != null) {
                out.getOrPut(ident.unescapedName) { ArrayList() }
                    .add(UseSite(packetIndex = ident.packetIndex, enclosingStmtFrameIndex = topStmtFrameIndex))
            }
        }
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            collectVarUsesInSuspendFrame(stream, child, topStmtFrameIndex, out)
        }
    }

    private fun isManagedOrPointerCandidate(
        stream: PacketStream,
        propFrame: PacketFrame,
    ): Boolean {
        val (_, typeFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, propFrame)
        if (typeFrame != null) {
            val raw = FrameAstHelpers.frameSourceSlice(stream, typeFrame).trim().removeSuffix("?")
            val base = raw.substringBefore('<').trim()
            return base !in SCALAR_VALUE_TYPES
        }
        val init = FrameAstHelpers.extractInitializerAfterEquals(stream, propFrame) ?: return false
        return when (init.nodeTypeId) {
            KtNodeTypes.INTEGER_CONSTANT_ID,
            KtNodeTypes.FLOAT_CONSTANT_ID,
            KtNodeTypes.BOOLEAN_CONSTANT_ID,
            KtNodeTypes.CHARACTER_CONSTANT_ID,
            KtNodeTypes.STRING_TEMPLATE_ID,
            -> false
            else -> {
                // Literal arithmetic on primitives is also scalar; object calls/constructors are managed candidates.
                val firstPkt = stream.packets.getOrNull(init.firstPacketIndex)
                firstPkt !is SyntaxPacket.LiteralPacket
            }
        }
    }

    private val SCALAR_VALUE_TYPES: Set<String> =
        setOf(
            "Boolean",
            "Byte",
            "Short",
            "Int",
            "Long",
            "UByte",
            "UShort",
            "UInt",
            "ULong",
            "Float",
            "Double",
            "Char",
            "String",
            "Unit",
        )
}
