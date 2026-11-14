package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.packet.FrameSemanticInfo
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame
import io.github.kotlinmania.kotlincpp.packet.PacketStream
import io.github.kotlinmania.kotlincpp.packet.SymbolRole
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket

internal enum class KtClassKind {
    INTERFACE,
    SIMPLE_ENUM,
    RICH_ENUM,
    SEALED_CLASS,
    DATA_CLASS,
    VALUE_CLASS,
    ANNOTATION_CLASS,
    REGULAR_CLASS,
    OBJECT_SINGLETON,
    COMPANION_OBJECT,
}

internal data class KtImportInfo(
    val fqName: String,
    val alias: String?,
    val isWildcard: Boolean,
    val mappedProjectHeader: String?,
    val mappedSystemHeader: String?,
)

internal data class KtEnumEntrySymbol(
    val kotlinName: String,
    val cppName: String,
    val frameIndex: Int,
    val constructorArgsFrameIndex: Int?,
    val bodyFrameIndex: Int?,
)

internal data class KtClassSymbol(
    val kotlinName: String,
    val cppName: String,
    val kind: KtClassKind,
    val frameIndex: Int,
    val enclosingClassFrameIndex: Int?,
    val isInner: Boolean = false,
    val typeParams: List<KtTypeParamInfo>,
    val primaryConstructorParams: List<KtValueParamInfo>,
    val superTypeFrameIndices: List<Int>,
    val delegatedSuperTypeFrameIndices: Set<Int> = emptySet(),
    val superCallArgsBySuperIndex: Map<Int, Int>,
    val enumEntries: List<KtEnumEntrySymbol>,
    val memberClassFrameIndices: List<Int>,
    val memberFunctionFrameIndices: List<Int>,
    val memberPropertyFrameIndices: List<Int>,
    val initBlockFrameIndices: List<Int> = emptyList(),
    val companionFrameIndex: Int?,
    val annotations: List<KtAnnotationInfo>,
    val flags: Long,
)

internal data class KtFunctionSymbol(
    val kotlinName: String,
    val cppName: String,
    val cAbiSymbolName: String?,
    val frameIndex: Int,
    val enclosingClassFrameIndex: Int?,
    val typeParams: List<KtTypeParamInfo>,
    val receiverTypeFrameIndex: Int?,
    val valueParams: List<KtValueParamInfo>,
    val returnTypeFrameIndex: Int?,
    val bodyFrameIndex: Int?,
    val isExpressionBody: Boolean,
    val isOperator: Boolean,
    val isInfix: Boolean,
    val isExternal: Boolean,
    val annotations: List<KtAnnotationInfo>,
    val flags: Long,
)

internal data class KtPropertySymbol(
    val kotlinName: String,
    val cppName: String,
    val frameIndex: Int,
    val enclosingClassFrameIndex: Int?,
    val typeParams: List<KtTypeParamInfo> = emptyList(),
    val isVar: Boolean,
    val isConst: Boolean,
    val receiverTypeFrameIndex: Int?,
    val returnTypeFrameIndex: Int?,
    val initializerFrameIndex: Int?,
    val getterFrameIndex: Int?,
    val setterFrameIndex: Int?,
    val annotations: List<KtAnnotationInfo>,
    val flags: Long,
)

internal data class KtTypeAliasSymbol(
    val kotlinName: String,
    val cppName: String,
    val frameIndex: Int,
    val typeParams: List<KtTypeParamInfo>,
    val underlyingTypeFrameIndex: Int?,
)

internal class SameFileSymbolTable(
    val packageName: String,
    val namespaceSegments: List<String>,
    val fileStem: String,
    val headerRelativePath: String,
    val sourceRelativePath: String,
    val portLintDirectiveKind: String?,
    val portLintSourcePath: String?,
    val imports: List<KtImportInfo>,
    val importAliases: Map<String, String>,
    val topLevelDeclarationFrames: List<PacketFrame>,
    val classesByFrame: Map<Int, KtClassSymbol>,
    val classesByName: Map<String, KtClassSymbol>,
    val functionsByFrame: Map<Int, KtFunctionSymbol>,
    val functionsByName: Map<String, List<KtFunctionSymbol>>,
    val propertiesByFrame: Map<Int, KtPropertySymbol>,
    val propertiesByName: Map<String, KtPropertySymbol>,
    val typeAliasesByFrame: Map<Int, KtTypeAliasSymbol>,
    val polymorphicTypeNames: Set<String>,
    val sharedHierarchyTypeNames: Set<String>,
    val restrictsSuspensionTypeNames: Set<String>,
    val forwardDeclaredClassFrames: List<Int>,
) {
    val hasCppSourceImplementations: Boolean
        get() =
            functionsByFrame.values.any { fn ->
                PacketFlags.hasFlag(fn.flags, PacketFlags.EMIT_IN_CPP_BODY)
            } ||
                propertiesByFrame.values.any { prop ->
                    PacketFlags.hasFlag(prop.flags, PacketFlags.EMIT_IN_CPP_BODY)
                } ||
                classesByFrame.values.any { cls ->
                    PacketFlags.hasFlag(cls.flags, PacketFlags.EMIT_IN_CPP_BODY)
                }
}

/**
 * Pass 1: Strictly single-file Kotlin symbol, receiver, naming, forward-declaration,
 * `#include`, and `.hpp`/`.cpp` co-location resolution over [PacketStream].
 */
internal object SameFileSymbolPass {
    fun run(
        stream: PacketStream,
        config: CppTargetConfig,
    ): SameFileSymbolTable {
        val root = stream.rootFrame
        val packageFrame = FrameAstHelpers.firstChildFrame(stream, root, KtNodeTypes.PACKAGE_DIRECTIVE_ID)
        val packageName =
            if (packageFrame != null) {
                extractPackageName(stream, packageFrame)
            } else {
                ""
            }

        val effectiveNamespace =
            config.rootNamespaceOverride?.takeIf { it.isNotEmpty() } ?: packageName
        val namespaceSegments =
            effectiveNamespace
                .split('.', ':')
                .filter { it.isNotEmpty() }
                .map { CppNamingPolicy.toNamespaceSegment(it) }

        val fileStem = computeFileStem(stream.sourcePath, config.fileStemSuffix)
        val cleanSubdir =
            config.sourceSetSubdir
                ?.trim()
                ?.trim('/')
                ?.takeIf { it.isNotEmpty() }
        val outputDirSegments =
            if (cleanSubdir == null) {
                namespaceSegments
            } else if (namespaceSegments.size >= 2 && namespaceSegments[0] == "kotlinx" && namespaceSegments[1] == "coroutines") {
                buildList(namespaceSegments.size + 1) {
                    add("kotlinx")
                    add("coroutines")
                    add(cleanSubdir)
                    addAll(namespaceSegments.subList(2, namespaceSegments.size))
                }
            } else {
                listOf(cleanSubdir) + namespaceSegments
            }
        val nsDir = outputDirSegments.joinToString("/")
        val headerRelativePath = if (nsDir.isEmpty()) "$fileStem.hpp" else "$nsDir/$fileStem.hpp"
        val sourceRelativePath = if (nsDir.isEmpty()) "$fileStem.cpp" else "$nsDir/$fileStem.cpp"

        val firstPortLintComment =
            stream.packets.firstOrNull { pkt ->
                pkt is SyntaxPacket.CommentPacket && pkt.portLintDirectiveKind != null
            } as? SyntaxPacket.CommentPacket

        val normalizedSourcePath = normalizeSourceRelativePath(stream.sourcePath, config.sourceRootPrefix)
        val hasSynthesizedPortLint =
            config.portLintPrefix != null ||
                (!config.sourceRootPrefix.isNullOrEmpty() && normalizedSourcePath.isNotEmpty())
        val portLintKind =
            firstPortLintComment?.portLintDirectiveKind
                ?: if (hasSynthesizedPortLint) "source" else null
        val portLintTarget =
            firstPortLintComment?.portLintTarget
                ?: config.portLintPrefix?.let { prefix ->
                    val trimmed = prefix.trimEnd('/')
                    if (trimmed.isEmpty()) normalizedSourcePath else "$trimmed/$normalizedSourcePath"
                }
                ?: if (!config.sourceRootPrefix.isNullOrEmpty() && normalizedSourcePath.isNotEmpty()) {
                    normalizedSourcePath
                } else {
                    null
                }

        val imports = ArrayList<KtImportInfo>()
        val importAliases = LinkedHashMap<String, String>()
        val importListFrame = FrameAstHelpers.firstChildFrame(stream, root, KtNodeTypes.IMPORT_LIST_ID)
        if (importListFrame != null) {
            for (imp in FrameAstHelpers.childFramesOfType(stream, importListFrame, KtNodeTypes.IMPORT_DIRECTIVE_ID)) {
                val parsedImport = parseImportDirective(stream, imp)
                if (parsedImport != null) {
                    imports.add(parsedImport)
                    if (parsedImport.alias != null) {
                        importAliases[parsedImport.alias] = parsedImport.fqName.substringAfterLast('.')
                    }
                }
            }
        }

        val classesByFrame = LinkedHashMap<Int, KtClassSymbol>()
        val classesByName = LinkedHashMap<String, KtClassSymbol>()
        val functionsByFrame = LinkedHashMap<Int, KtFunctionSymbol>()
        val functionsByName = LinkedHashMap<String, MutableList<KtFunctionSymbol>>()
        val propertiesByFrame = LinkedHashMap<Int, KtPropertySymbol>()
        val propertiesByName = LinkedHashMap<String, KtPropertySymbol>()
        val typeAliasesByFrame = LinkedHashMap<Int, KtTypeAliasSymbol>()
        val topLevelDeclFrames = ArrayList<PacketFrame>()

        for (child in FrameAstHelpers.childFrames(stream, root)) {
            when (child.nodeTypeId) {
                KtNodeTypes.CLASS_ID,
                KtNodeTypes.OBJECT_DECLARATION_ID,
                -> {
                    topLevelDeclFrames.add(child)
                    collectClassOrObjectRecursive(
                        stream = stream,
                        declFrame = child,
                        enclosingClassFrameIndex = null,
                        enclosingHasTypeParams = false,
                        classesByFrame = classesByFrame,
                        classesByName = classesByName,
                        functionsByFrame = functionsByFrame,
                        functionsByName = functionsByName,
                        propertiesByFrame = propertiesByFrame,
                        propertiesByName = propertiesByName,
                    )
                }
                KtNodeTypes.FUN_ID -> {
                    topLevelDeclFrames.add(child)
                    val sym =
                        buildFunctionSymbol(
                            stream = stream,
                            fnFrame = child,
                            enclosingClass = null,
                            enclosingHasTypeParams = false,
                        )
                    functionsByFrame[child.frameIndex] = sym
                    functionsByName.getOrPut(sym.kotlinName) { ArrayList() }.add(sym)
                }
                KtNodeTypes.PROPERTY_ID -> {
                    topLevelDeclFrames.add(child)
                    val sym =
                        buildPropertySymbol(
                            stream = stream,
                            propFrame = child,
                            enclosingClass = null,
                            enclosingHasTypeParams = false,
                        )
                    propertiesByFrame[child.frameIndex] = sym
                    propertiesByName[sym.kotlinName] = sym
                }
                KtNodeTypes.TYPEALIAS_ID -> {
                    topLevelDeclFrames.add(child)
                    val sym = buildTypeAliasSymbol(stream, child)
                    typeAliasesByFrame[child.frameIndex] = sym
                }
                else -> Unit
            }
        }

        val polymorphicTypes = LinkedHashSet<String>(KNOWN_POLYMORPHIC_TYPES)
        val sharedHierarchyTypes = LinkedHashSet<String>(CppTypeTranslator.SHARED_HIERARCHY_TYPES)
        val restrictsSuspensionTypes = LinkedHashSet<String>(KNOWN_RESTRICTS_SUSPENSION_TYPES)
        for (cls in classesByFrame.values) {
            if (cls.kind == KtClassKind.INTERFACE ||
                cls.kind == KtClassKind.SEALED_CLASS ||
                PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_OPEN) ||
                PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_ABSTRACT)
            ) {
                polymorphicTypes.add(cls.kotlinName)
                polymorphicTypes.add(cls.cppName)
            }
            if (cls.annotations.any { it.simpleName == "RestrictsSuspension" }) {
                restrictsSuspensionTypes.add(cls.kotlinName)
                restrictsSuspensionTypes.add(cls.cppName)
                cls.frameIndex.let { idx ->
                    stream.frames[idx].flags = stream.frames[idx].flags or PacketFlags.RESTRICTS_SUSPENSION_SCOPE
                }
            }
        }
        var sharedChanged = true
        while (sharedChanged) {
            sharedChanged = false
            for (cls in classesByFrame.values) {
                val isExcludedKind =
                    cls.kind == KtClassKind.SIMPLE_ENUM ||
                        cls.kind == KtClassKind.RICH_ENUM ||
                        cls.kind == KtClassKind.VALUE_CLASS ||
                        cls.kind == KtClassKind.ANNOTATION_CLASS ||
                        cls.kind == KtClassKind.COMPANION_OBJECT
                if (isExcludedKind || cls.kotlinName == "Key") continue
                if (cls.kotlinName in CppTypeTranslator.KOTLIN_STDLIB_EXCEPTION_CLASSES ||
                    cls.kotlinName.endsWith("Exception") ||
                    cls.kotlinName.endsWith("Error")
                ) {
                    continue
                }
                val isInterfaceOrAbstract =
                    cls.kind == KtClassKind.INTERFACE ||
                        PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_ABSTRACT)
                val inheritsShared =
                    cls.superTypeFrameIndices.any { stIdx ->
                        val baseSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[stIdx])
                        baseSimple != null && baseSimple in sharedHierarchyTypes
                    }
                if (isInterfaceOrAbstract || inheritsShared) {
                    if (sharedHierarchyTypes.add(cls.kotlinName)) sharedChanged = true
                    if (sharedHierarchyTypes.add(cls.cppName)) sharedChanged = true
                }
            }
        }

        val forwardDecls =
            detectForwardDeclarationsAndStampReferences(
                stream = stream,
                classesByName = classesByName,
                functionsByName = functionsByName,
                propertiesByName = propertiesByName,
                importAliases = importAliases,
            )

        return SameFileSymbolTable(
            packageName = packageName,
            namespaceSegments = namespaceSegments,
            fileStem = fileStem,
            headerRelativePath = headerRelativePath,
            sourceRelativePath = sourceRelativePath,
            portLintDirectiveKind = portLintKind,
            portLintSourcePath = portLintTarget,
            imports = imports,
            importAliases = importAliases,
            topLevelDeclarationFrames = topLevelDeclFrames,
            classesByFrame = classesByFrame,
            classesByName = classesByName,
            functionsByFrame = functionsByFrame,
            functionsByName = functionsByName,
            propertiesByFrame = propertiesByFrame,
            propertiesByName = propertiesByName,
            typeAliasesByFrame = typeAliasesByFrame,
            polymorphicTypeNames = polymorphicTypes,
            sharedHierarchyTypeNames = sharedHierarchyTypes,
            restrictsSuspensionTypeNames = restrictsSuspensionTypes,
            forwardDeclaredClassFrames = forwardDecls,
        )
    }

    private fun frameHasNonVoidPtrStarProjection(
        stream: PacketStream,
        frame: PacketFrame,
    ): Boolean {
        if (frame.nodeTypeId == KtNodeTypes.USER_TYPE_ID) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, frame)
            val argList = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
            if (argList != null) {
                for (proj in FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.TYPE_PROJECTION_ID)) {
                    val innerType = FrameAstHelpers.firstChildFrame(stream, proj, KtNodeTypes.TYPE_REFERENCE_ID)
                    if (innerType == null) {
                        if (simple !in CppTypeTranslator.VOID_PTR_STAR_PROJECTION_TYPES) {
                            return true
                        }
                    } else if (frameHasNonVoidPtrStarProjection(stream, innerType)) {
                        return true
                    }
                }
            }
            return false
        }
        for (child in FrameAstHelpers.childFrames(stream, frame)) {
            if (frameHasNonVoidPtrStarProjection(stream, child)) return true
        }
        return false
    }

    private fun extractPackageName(
        stream: PacketStream,
        packageFrame: PacketFrame,
    ): String {
        val parts = ArrayList<String>()
        for (idx in packageFrame.firstPacketIndex..packageFrame.lastPacketIndex) {
            val pkt = stream.packets[idx]
            if (pkt is SyntaxPacket.IdentPacket) {
                pkt.role = SymbolRole.PACKAGE
                pkt.targetCppName = CppNamingPolicy.toNamespaceSegment(pkt.unescapedName)
                parts.add(pkt.unescapedName)
            }
        }
        return parts.joinToString(".")
    }

    private fun computeFileStem(
        sourcePath: String,
        fileStemSuffix: String?,
    ): String {
        val normalized = sourcePath.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')
        val withoutKt =
            if (fileName.endsWith(".kt", ignoreCase = true)) {
                fileName.substring(0, fileName.length - 3)
            } else {
                fileName
            }
        for (suffix in PRESERVED_MULTIPLATFORM_SUFFIXES) {
            if (withoutKt.length > suffix.length && withoutKt.endsWith(suffix, ignoreCase = true)) {
                val rawBase = withoutKt.substring(0, withoutKt.length - suffix.length).substringBefore('.')
                if (rawBase.isNotEmpty() && rawBase != "<memory>") {
                    return "${CppNamingPolicy.toTypeName(rawBase)}${suffix.lowercase()}"
                }
            }
        }
        val base = withoutKt.substringBefore('.')
        if (base.isEmpty() || base == "<memory>") {
            return "GeneratedModule"
        }
        val stem = CppNamingPolicy.toTypeName(base)
        val cleanExtraSuffix = fileStemSuffix?.trim().orEmpty()
        if (cleanExtraSuffix.isEmpty()) {
            return stem
        }
        val dottedSuffix = if (cleanExtraSuffix.startsWith('.')) cleanExtraSuffix else ".$cleanExtraSuffix"
        return "$stem$dottedSuffix"
    }

    private fun normalizeSourceRelativePath(
        sourcePath: String,
        sourceRootPrefix: String?,
    ): String {
        if (sourcePath == "<memory>" || sourcePath.isEmpty()) return ""
        val norm = sourcePath.replace('\\', '/')
        if (sourceRootPrefix != null && sourceRootPrefix.isNotEmpty()) {
            val prefix = sourceRootPrefix.replace('\\', '/').trimEnd('/') + "/"
            if (norm.startsWith(prefix)) {
                return norm.removePrefix(prefix)
            }
        }
        return norm
    }

    private fun parseImportDirective(
        stream: PacketStream,
        impFrame: PacketFrame,
    ): KtImportInfo? {
        val aliasFrame = FrameAstHelpers.firstChildFrame(stream, impFrame, KtNodeTypes.IMPORT_ALIAS_ID)
        val aliasIdent =
            if (aliasFrame != null) {
                FrameAstHelpers.directIdentPacket(stream, aliasFrame)
            } else {
                null
            }
        if (aliasIdent != null) {
            aliasIdent.role = SymbolRole.IMPORT_ALIAS
            aliasIdent.targetCppName = CppNamingPolicy.toTypeName(aliasIdent.unescapedName)
        }
        val aliasEndStart = aliasFrame?.firstPacketIndex ?: (impFrame.lastPacketIndex + 1)
        val segments = ArrayList<String>()
        var isWildcard = false
        for (idx in impFrame.firstPacketIndex until aliasEndStart) {
            when (val pkt = stream.packets[idx]) {
                is SyntaxPacket.IdentPacket -> segments.add(pkt.unescapedName)
                is SyntaxPacket.OperatorPacket ->
                    if (pkt.operatorId == KtTokens.MUL_ID) {
                        isWildcard = true
                    }
                else -> Unit
            }
        }
        if (segments.isEmpty()) return null
        val fqName = segments.joinToString(".")
        val (projHdr, sysHdr) = mapImportToCppHeader(fqName, isWildcard)
        return KtImportInfo(
            fqName = fqName,
            alias = aliasIdent?.unescapedName,
            isWildcard = isWildcard,
            mappedProjectHeader = projHdr,
            mappedSystemHeader = sysHdr,
        )
    }

    private fun mapImportToCppHeader(
        fqName: String,
        isWildcard: Boolean,
    ): Pair<String?, String?> {
        // Compiler/JVM/JS/Native-annotation-only packages produce no C++ header dependency.
        if (IGNORED_IMPORT_PREFIXES.any { fqName.startsWith(it) } || fqName.startsWith("kotlin.contracts")) {
            return null to null
        }
        if (fqName.startsWith("kotlin.coroutines.intrinsics")) {
            return "kotlinx/coroutines/intrinsics/Intrinsics.hpp" to null
        }
        if (fqName == "kotlin.coroutines.Continuation" ||
            fqName == "kotlin.coroutines.resume" ||
            fqName == "kotlin.coroutines.resumeWithException"
        ) {
            return "kotlinx/coroutines/Continuation.hpp" to null
        }
        if (fqName == "kotlin.coroutines.ContinuationInterceptor") {
            return "kotlinx/coroutines/ContinuationInterceptor.hpp" to null
        }
        if (fqName == "kotlin.coroutines.CoroutineContext" ||
            fqName == "kotlin.coroutines.AbstractCoroutineContextElement" ||
            fqName == "kotlin.coroutines.AbstractCoroutineContextKey" ||
            fqName == "kotlin.coroutines.EmptyCoroutineContext" ||
            (isWildcard && fqName == "kotlin.coroutines")
        ) {
            return "kotlinx/coroutines/context_impl.hpp" to null
        }
        if (fqName == "kotlin.coroutines.cancellation.CancellationException" ||
            fqName == "kotlinx.coroutines.CancellationException"
        ) {
            return "kotlin/coroutines/cancellation/CancellationException.hpp" to null
        }
        if (fqName.startsWith("kotlin.native.concurrent")) {
            return "kotlin/native/concurrent/Worker.hpp" to null
        }
        if (fqName.startsWith("kotlin.time")) {
            return "kotlin/time/TimeSource.hpp" to null
        }
        if (fqName == "kotlin.test" || fqName.startsWith("kotlin.test.")) {
            return "kotlin/test/Assertions.hpp" to null
        }
        if (fqName.startsWith("platform.posix")) {
            return null to "unistd.h"
        }
        if (fqName.startsWith("platform.CoreFoundation")) {
            return null to "CoreFoundation/CoreFoundation.h"
        }
        if (fqName.startsWith("platform.darwin")) {
            return null to "dispatch/dispatch.h"
        }
        if (fqName.startsWith("kotlin.")) {
            return null to null
        }
        if (isWildcard) {
            return when (fqName) {
                "kotlinx.coroutines.selects" -> "kotlinx/coroutines/selects/Select.hpp" to null
                "kotlinx.coroutines.channels" -> "kotlinx/coroutines/channels/Channel.hpp" to null
                "kotlinx.coroutines.flow" -> "kotlinx/coroutines/flow/Flow.hpp" to null
                "kotlinx.coroutines.sync" -> "kotlinx/coroutines/sync/Mutex.hpp" to null
                "kotlinx.coroutines.intrinsics" -> "kotlinx/coroutines/intrinsics/Intrinsics.hpp" to null
                "kotlinx.coroutines.test" -> "kotlinx/coroutines/test/TestBuilders.common.hpp" to null
                else -> null to null
            }
        }
        when (fqName) {
            "kotlinx.coroutines.flow.internal.NULL",
            "kotlinx.coroutines.flow.internal.UNINITIALIZED",
            "kotlinx.coroutines.flow.internal.DONE",
            -> return "kotlinx/coroutines/flow/internal/NullSurrogate.hpp" to null
            "kotlinx.coroutines.flow.internal.NopCollector" -> return "kotlinx/coroutines/flow/internal/NopCollector.hpp" to null
            "kotlinx.coroutines.flow.internal.SendingCollector" -> return "kotlinx/coroutines/flow/internal/SendingCollector.hpp" to null
            "kotlinx.coroutines.flow.internal.FlowCoroutine",
            "kotlinx.coroutines.flow.internal.flowScope",
            -> return "kotlinx/coroutines/flow/internal/FlowCoroutine.hpp" to null
            "kotlinx.coroutines.flow.internal.SafeCollector",
            "kotlinx.coroutines.flow.internal.checkContext",
            -> return "kotlinx/coroutines/flow/internal/SafeCollector.common.hpp" to null
            "kotlinx.coroutines.flow.internal.AbortFlowException",
            "kotlinx.coroutines.flow.internal.checkOwnership",
            -> return "kotlinx/coroutines/flow/internal/FlowExceptions.common.hpp" to null
            "kotlinx.coroutines.internal.CLOSED" -> return "kotlinx/coroutines/internal/ConcurrentLinkedList.hpp" to null
            "kotlinx.coroutines.internal.NO_DECISION",
            "kotlinx.coroutines.internal.RETRY_ATOMIC",
            "kotlinx.coroutines.internal.updateThreadContext",
            "kotlinx.coroutines.internal.restoreThreadContext",
            "kotlinx.coroutines.internal.NO_THREAD_ELEMENTS",
            "kotlinx.coroutines.ThreadContextElement",
            "kotlinx.coroutines.channels.ActorScope",
            -> return null to null
            "kotlinx.coroutines.internal.resumeCancellableWith" -> return "kotlinx/coroutines/internal/DispatchedContinuation.hpp" to null
            "kotlinx.coroutines.internal.withCoroutineContext",
            "kotlinx.coroutines.internal.withContinuationContext",
            -> return "kotlinx/coroutines/CoroutineContext.common.hpp" to null
            "kotlinx.coroutines.internal.threadContextElements" -> return "kotlinx/coroutines/internal/ThreadContext.common.hpp" to null
            "kotlinx.coroutines.internal.systemProp" -> return "kotlinx/coroutines/internal/SystemProps.common.hpp" to null
            "kotlinx.coroutines.internal.synchronized" -> return "kotlinx/coroutines/internal/Synchronized.common.hpp" to null
            "kotlinx.coroutines.internal.commonThreadLocal",
            "kotlinx.coroutines.internal.CommonThreadLocal",
            -> return "kotlinx/coroutines/internal/ThreadLocal.common.hpp" to null
            "kotlinx.coroutines.internal.recoverStackTrace",
            "kotlinx.coroutines.internal.recoverAndThrow",
            "kotlinx.coroutines.internal.unwrap",
            "kotlinx.coroutines.internal.CoroutineStackFrame",
            "kotlinx.coroutines.internal.StackTraceElement",
            -> return "kotlinx/coroutines/internal/StackTraceRecovery.common.hpp" to null
            "kotlinx.coroutines.RESUME_TOKEN" -> return "kotlinx/coroutines/CancellableContinuationImpl.hpp" to null
            "kotlinx.coroutines.delay",
            "kotlinx.coroutines.awaitCancellation",
            -> return "kotlinx/coroutines/Delay.hpp" to null
            "kotlinx.coroutines.yield" -> return "kotlinx/coroutines/Yield.hpp" to null
            "kotlinx.coroutines.launch",
            "kotlinx.coroutines.async",
            "kotlinx.coroutines.withContext",
            -> return "kotlinx/coroutines/Builders.common.hpp" to null
            "kotlinx.coroutines.coroutineScope",
            "kotlinx.coroutines.MainScope",
            "kotlinx.coroutines.currentCoroutineContext",
            -> return "kotlinx/coroutines/CoroutineScope.hpp" to null
            "kotlinx.coroutines.supervisorScope",
            "kotlinx.coroutines.SupervisorJob",
            -> return "kotlinx/coroutines/Supervisor.hpp" to null
            "kotlinx.coroutines.withTimeout",
            "kotlinx.coroutines.withTimeoutOrNull",
            -> return "kotlinx/coroutines/Timeout.hpp" to null
            "kotlinx.coroutines.suspendCancellableCoroutine",
            "kotlinx.coroutines.suspendCancellableCoroutineReusable",
            "kotlinx.coroutines.disposeOnCancellation",
            -> return "kotlinx/coroutines/CancellableContinuation.hpp" to null
            "kotlinx.coroutines.handleCoroutineException" -> return "kotlinx/coroutines/CoroutineExceptionHandler.hpp" to null
            "kotlinx.coroutines.awaitAll",
            "kotlinx.coroutines.joinAll",
            -> return "kotlinx/coroutines/Await.hpp" to null
            "kotlinx.coroutines.ensureActive",
            "kotlinx.coroutines.cancel",
            "kotlinx.coroutines.cancelAndJoin",
            "kotlinx.coroutines.cancelChildren",
            "kotlinx.coroutines.isActive",
            "kotlinx.coroutines.job",
            -> return "kotlinx/coroutines/Job.hpp" to null
            "kotlinx.coroutines.MODE_CANCELLABLE",
            "kotlinx.coroutines.MODE_ATOMIC",
            "kotlinx.coroutines.MODE_CANCELLABLE_REUSABLE",
            "kotlinx.coroutines.MODE_UNDISPATCHED",
            "kotlinx.coroutines.MODE_UNINITIALIZED",
            "kotlinx.coroutines.isCancellableMode",
            "kotlinx.coroutines.isReusableMode",
            "kotlinx.coroutines.resume",
            "kotlinx.coroutines.dispatch",
            -> return "kotlinx/coroutines/DispatchedTask.hpp" to null
            "kotlinx.coroutines.recoverResult",
            "kotlinx.coroutines.toState",
            -> return "kotlinx/coroutines/CompletionState.hpp" to null
            "kotlinx.coroutines.DEBUG",
            "kotlinx.coroutines.ASSERTIONS_ENABLED",
            "kotlinx.coroutines.RECOVER_STACK_TRACES",
            "kotlinx.coroutines.assert",
            "kotlinx.coroutines.hexAddress",
            "kotlinx.coroutines.classSimpleName",
            -> return "kotlinx/coroutines/Debug.common.hpp" to null
            "kotlinx.coroutines.selects.whileSelect" -> return "kotlinx/coroutines/selects/WhileSelect.hpp" to null
            "kotlinx.coroutines.selects.onTimeout" -> return "kotlinx/coroutines/selects/OnTimeout.hpp" to null
            "kotlinx.coroutines.intrinsics.startCoroutineCancellable" -> return "kotlinx/coroutines/intrinsics/Cancellable.hpp" to null
            "kotlinx.coroutines.intrinsics.startCoroutineUndispatched",
            "kotlinx.coroutines.intrinsics.startDirect",
            "kotlinx.coroutines.intrinsics.startUndispatchedOrReturn",
            "kotlinx.coroutines.intrinsics.startUndispatchedOrReturnIgnoreTimeout",
            -> return "kotlinx/coroutines/intrinsics/Undispatched.hpp" to null
        }
        val parts = fqName.split('.')
        if (parts.size < 2) return null to null
        val firstUpperIdx = parts.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
        if (firstUpperIdx < 1) {
            return null to null
        }
        val upperSeg = parts[firstUpperIdx]
        // ALL_CAPS segments (e.g. `NULL`, `CLOSED`, `NON_CANCELLABLE`) are package constants, not `<Const>.hpp` class files.
        if (upperSeg.length > 1 && upperSeg.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
            return null to null
        }
        if (parts.first() == "kotlinx") {
            CppTypeTranslator.lookupKnownHeaderForType(upperSeg)?.let { knownHdr ->
                return knownHdr to null
            }
        }
        // Stop at the top-level enclosing type (`pkg.Channel.Factory.BUFFERED` -> `pkg/Channel.hpp`).
        val headerParts = parts.subList(0, firstUpperIdx + 1)
        val dirSegments = headerParts.dropLast(1).map { CppNamingPolicy.toNamespaceSegment(it) }
        val stem = CppNamingPolicy.toTypeName(headerParts.last())
        val dir = dirSegments.joinToString("/")
        val projPath = if (dir.isEmpty()) "$stem.hpp" else "$dir/$stem.hpp"
        return projPath to null
    }

    private fun collectClassOrObjectRecursive(
        stream: PacketStream,
        declFrame: PacketFrame,
        enclosingClassFrameIndex: Int?,
        enclosingHasTypeParams: Boolean,
        classesByFrame: MutableMap<Int, KtClassSymbol>,
        classesByName: MutableMap<String, KtClassSymbol>,
        functionsByFrame: MutableMap<Int, KtFunctionSymbol>,
        functionsByName: MutableMap<String, MutableList<KtFunctionSymbol>>,
        propertiesByFrame: MutableMap<Int, KtPropertySymbol>,
        propertiesByName: MutableMap<String, KtPropertySymbol>,
    ) {
        val annotations = FrameAstHelpers.extractAnnotations(stream, declFrame)
        val identPkt = FrameAstHelpers.directIdentPacket(stream, declFrame)
        val isCompanion =
            declFrame.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID &&
                PacketFlags.hasFlag(declFrame.flags, PacketFlags.MOD_COMPANION)
        val kotlinName =
            identPkt?.unescapedName
                ?: if (isCompanion) "Companion" else "AnonymousObject"
        val cppName = CppNamingPolicy.toTypeName(kotlinName)
        if (identPkt != null) {
            identPkt.role = SymbolRole.TYPE
            identPkt.targetCppName = cppName
        }

        val typeParams = FrameAstHelpers.extractTypeParams(stream, declFrame)
        for (tp in typeParams) {
            FrameAstHelpers.directIdentPacket(stream, stream.frames[tp.frameIndex])?.let { pkt ->
                pkt.role = SymbolRole.TYPE_PARAM
                pkt.targetCppName = tp.name
            }
        }
        val hasTemplates = enclosingHasTypeParams || typeParams.isNotEmpty()

        val primaryCtor = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.PRIMARY_CONSTRUCTOR_ID)
        val ctorParamList =
            if (primaryCtor != null) {
                FrameAstHelpers.firstChildFrame(stream, primaryCtor, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
            } else {
                FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
            }
        val ctorParams = FrameAstHelpers.extractValueParams(stream, ctorParamList)
        for (cp in ctorParams) {
            FrameAstHelpers.directIdentPacket(stream, stream.frames[cp.frameIndex])?.let { pkt ->
                val isPrivateProp =
                    (cp.isVal || cp.isVar) &&
                        PacketFlags.hasFlag(stream.frames[cp.frameIndex].flags, PacketFlags.MOD_PRIVATE)
                pkt.role = if (isPrivateProp) SymbolRole.PRIVATE_FIELD else SymbolRole.PARAM
                pkt.targetCppName =
                    if (isPrivateProp) CppNamingPolicy.toPrivateFieldName(cp.name) else cp.cppName
            }
        }

        val superList = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.SUPER_TYPE_LIST_ID)
        val superTypes = ArrayList<Int>()
        val delegatedSuperTypes = LinkedHashSet<Int>()
        val superCallArgs = LinkedHashMap<Int, Int>()
        if (superList != null) {
            for (child in FrameAstHelpers.childFrames(stream, superList)) {
                when (child.nodeTypeId) {
                    KtNodeTypes.SUPER_TYPE_ENTRY_ID -> {
                        FrameAstHelpers.firstChildFrame(stream, child, KtNodeTypes.TYPE_REFERENCE_ID)?.let {
                            superTypes.add(it.frameIndex)
                        }
                    }
                    KtNodeTypes.SUPER_TYPE_CALL_ENTRY_ID -> {
                        val callee = FrameAstHelpers.firstChildFrame(stream, child, KtNodeTypes.CONSTRUCTOR_CALLEE_ID)
                        val tr =
                            if (callee != null) {
                                FrameAstHelpers.firstChildFrame(stream, callee, KtNodeTypes.TYPE_REFERENCE_ID)
                            } else {
                                FrameAstHelpers.firstChildFrame(stream, child, KtNodeTypes.TYPE_REFERENCE_ID)
                            }
                        if (tr != null) {
                            superTypes.add(tr.frameIndex)
                            FrameAstHelpers.firstChildFrame(stream, child, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)?.let {
                                superCallArgs[tr.frameIndex] = it.frameIndex
                            }
                        }
                    }
                    KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY_ID -> {
                        FrameAstHelpers.firstChildFrame(stream, child, KtNodeTypes.TYPE_REFERENCE_ID)?.let {
                            superTypes.add(it.frameIndex)
                            delegatedSuperTypes.add(it.frameIndex)
                        }
                    }
                    else -> Unit
                }
            }
        }

        val bodyFrame = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.CLASS_BODY_ID)
        val enumEntries = ArrayList<KtEnumEntrySymbol>()
        val memberClassIndices = ArrayList<Int>()
        val memberFnIndices = ArrayList<Int>()
        val memberPropIndices = ArrayList<Int>()
        val initBlockIndices = ArrayList<Int>()
        var companionIdx: Int? = null

        val rawClassKind = classifyRawClassKind(stream, declFrame, isCompanion)

        if (bodyFrame != null) {
            for (member in FrameAstHelpers.childFrames(stream, bodyFrame)) {
                when (member.nodeTypeId) {
                    KtNodeTypes.ENUM_ENTRY_ID -> {
                        val entryIdent = FrameAstHelpers.directIdentPacket(stream, member) ?: continue
                        val entryCpp = CppNamingPolicy.toConstName(entryIdent.unescapedName)
                        entryIdent.role = SymbolRole.ENUM_ENTRY
                        entryIdent.targetCppName = entryCpp
                        val initList = FrameAstHelpers.firstChildFrame(stream, member, KtNodeTypes.INITIALIZER_LIST_ID)
                        val superCall =
                            if (initList != null) {
                                FrameAstHelpers.firstChildFrame(stream, initList, KtNodeTypes.SUPER_TYPE_CALL_ENTRY_ID)
                            } else {
                                null
                            }
                        val entryArgs =
                            if (superCall != null) {
                                FrameAstHelpers.firstChildFrame(stream, superCall, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
                            } else {
                                null
                            }
                        val entryBody = FrameAstHelpers.firstChildFrame(stream, member, KtNodeTypes.CLASS_BODY_ID)
                        enumEntries.add(
                            KtEnumEntrySymbol(
                                kotlinName = entryIdent.unescapedName,
                                cppName = entryCpp,
                                frameIndex = member.frameIndex,
                                constructorArgsFrameIndex = entryArgs?.frameIndex,
                                bodyFrameIndex = entryBody?.frameIndex,
                            ),
                        )
                    }
                    KtNodeTypes.CLASS_ID,
                    KtNodeTypes.OBJECT_DECLARATION_ID,
                    -> {
                        val nestedIsCompanion =
                            member.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID &&
                                PacketFlags.hasFlag(member.flags, PacketFlags.MOD_COMPANION)
                        if (nestedIsCompanion) {
                            companionIdx = member.frameIndex
                        } else {
                            memberClassIndices.add(member.frameIndex)
                        }
                    }
                    KtNodeTypes.FUN_ID -> memberFnIndices.add(member.frameIndex)
                    KtNodeTypes.PROPERTY_ID -> memberPropIndices.add(member.frameIndex)
                    KtNodeTypes.CLASS_INITIALIZER_ID -> initBlockIndices.add(member.frameIndex)
                    else -> Unit
                }
            }
        }

        val finalKind =
            if (rawClassKind == KtClassKind.SIMPLE_ENUM &&
                (
                    ctorParams.isNotEmpty() || memberFnIndices.isNotEmpty() || memberPropIndices.isNotEmpty() ||
                        enumEntries.any { it.constructorArgsFrameIndex != null || it.bodyFrameIndex != null }
                )
            ) {
                KtClassKind.RICH_ENUM
            } else {
                rawClassKind
            }

        // Determine class header vs cpp constructor placement:
        // Non-template regular/sealed classes with explicit primary constructor params emit out-of-line ctor in .cpp
        // when the class is a top-level concrete class (matching `Symbol.hpp` + `Symbol.cpp`).
        var classFlags = declFrame.flags or PacketFlags.EMIT_IN_HPP_DECL
        val hasNonEmptySuperArgs =
            superCallArgs.values.any { argListIdx ->
                FrameAstHelpers.childFramesOfType(stream, stream.frames[argListIdx], KtNodeTypes.VALUE_ARGUMENT_ID).isNotEmpty()
            }
        val emitOutOfLineCtor =
            !hasTemplates &&
                enclosingClassFrameIndex == null &&
                (finalKind == KtClassKind.REGULAR_CLASS || finalKind == KtClassKind.SEALED_CLASS) &&
                (ctorParams.isNotEmpty() || initBlockIndices.isNotEmpty() || hasNonEmptySuperArgs)
        classFlags =
            if (emitOutOfLineCtor) {
                classFlags or PacketFlags.EMIT_IN_CPP_BODY
            } else {
                classFlags or PacketFlags.EMIT_IN_HPP_INLINE_BODY
            }
        declFrame.flags = classFlags
        declFrame.semanticInfo =
            (declFrame.semanticInfo ?: FrameSemanticInfo()).copy(symbolName = cppName)

        val isInner =
            enclosingClassFrameIndex != null &&
                FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.INNER_MODIFIER_ID)

        val preliminarySymbol =
            KtClassSymbol(
                kotlinName = kotlinName,
                cppName = cppName,
                kind = finalKind,
                frameIndex = declFrame.frameIndex,
                enclosingClassFrameIndex = enclosingClassFrameIndex,
                isInner = isInner,
                typeParams = typeParams,
                primaryConstructorParams = ctorParams,
                superTypeFrameIndices = superTypes,
                delegatedSuperTypeFrameIndices = delegatedSuperTypes,
                superCallArgsBySuperIndex = superCallArgs,
                enumEntries = enumEntries,
                memberClassFrameIndices = memberClassIndices,
                memberFunctionFrameIndices = memberFnIndices,
                memberPropertyFrameIndices = memberPropIndices,
                initBlockFrameIndices = initBlockIndices,
                companionFrameIndex = companionIdx,
                annotations = annotations,
                flags = classFlags,
            )
        classesByFrame[declFrame.frameIndex] = preliminarySymbol
        if (enclosingClassFrameIndex == null) {
            classesByName[kotlinName] = preliminarySymbol
        } else if (kotlinName !in classesByName) {
            classesByName[kotlinName] = preliminarySymbol
        }

        if (bodyFrame != null) {
            for (member in FrameAstHelpers.childFrames(stream, bodyFrame)) {
                when (member.nodeTypeId) {
                    KtNodeTypes.CLASS_ID,
                    KtNodeTypes.OBJECT_DECLARATION_ID,
                    -> {
                        collectClassOrObjectRecursive(
                            stream = stream,
                            declFrame = member,
                            enclosingClassFrameIndex = declFrame.frameIndex,
                            enclosingHasTypeParams = hasTemplates,
                            classesByFrame = classesByFrame,
                            classesByName = classesByName,
                            functionsByFrame = functionsByFrame,
                            functionsByName = functionsByName,
                            propertiesByFrame = propertiesByFrame,
                            propertiesByName = propertiesByName,
                        )
                    }
                    KtNodeTypes.FUN_ID -> {
                        val fnSym =
                            buildFunctionSymbol(
                                stream = stream,
                                fnFrame = member,
                                enclosingClass = preliminarySymbol,
                                enclosingHasTypeParams = hasTemplates,
                            )
                        functionsByFrame[member.frameIndex] = fnSym
                        functionsByName.getOrPut(fnSym.kotlinName) { ArrayList() }.add(fnSym)
                    }
                    KtNodeTypes.PROPERTY_ID -> {
                        val propSym =
                            buildPropertySymbol(
                                stream = stream,
                                propFrame = member,
                                enclosingClass = preliminarySymbol,
                                enclosingHasTypeParams = hasTemplates,
                            )
                        propertiesByFrame[member.frameIndex] = propSym
                        if (enclosingClassFrameIndex == null &&
                            preliminarySymbol.kind == KtClassKind.COMPANION_OBJECT &&
                            propSym.kotlinName !in propertiesByName
                        ) {
                            propertiesByName[propSym.kotlinName] = propSym
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun classifyRawClassKind(
        stream: PacketStream,
        declFrame: PacketFrame,
        isCompanion: Boolean,
    ): KtClassKind {
        if (isCompanion) return KtClassKind.COMPANION_OBJECT
        if (declFrame.nodeTypeId == KtNodeTypes.OBJECT_DECLARATION_ID) return KtClassKind.OBJECT_SINGLETON
        if (FrameAstHelpers.hasDirectKeyword(stream, declFrame, KtTokens.INTERFACE_KEYWORD_ID)) {
            return KtClassKind.INTERFACE
        }
        if (FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.ANNOTATION_MODIFIER_ID)) {
            return KtClassKind.ANNOTATION_CLASS
        }
        if (FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.ENUM_MODIFIER_ID)) {
            return KtClassKind.SIMPLE_ENUM
        }
        if (PacketFlags.hasFlag(declFrame.flags, PacketFlags.MOD_VALUE) ||
            FrameAstHelpers.extractAnnotations(stream, declFrame).any { it.simpleName == "JvmInline" }
        ) {
            return KtClassKind.VALUE_CLASS
        }
        if (PacketFlags.hasFlag(declFrame.flags, PacketFlags.MOD_DATA)) {
            return KtClassKind.DATA_CLASS
        }
        if (PacketFlags.hasFlag(declFrame.flags, PacketFlags.MOD_SEALED)) {
            return KtClassKind.SEALED_CLASS
        }
        return KtClassKind.REGULAR_CLASS
    }

    private fun buildFunctionSymbol(
        stream: PacketStream,
        fnFrame: PacketFrame,
        enclosingClass: KtClassSymbol?,
        enclosingHasTypeParams: Boolean,
    ): KtFunctionSymbol {
        val annotations = FrameAstHelpers.extractAnnotations(stream, fnFrame)
        val identPkt = FrameAstHelpers.directIdentPacket(stream, fnFrame)
        val kotlinName = identPkt?.unescapedName ?: "anonymous"
        val cNameAnn = annotations.firstOrNull { it.simpleName == "CName" }
        val cAbiSymbol = cNameAnn?.stringArguments?.firstOrNull()?.takeIf { it.isNotEmpty() }
        val cppName = cAbiSymbol ?: CppNamingPolicy.toSnakeCase(kotlinName)

        if (identPkt != null) {
            identPkt.role =
                when {
                    cAbiSymbol != null -> SymbolRole.C_FFI_SYMBOL
                    enclosingClass != null -> SymbolRole.METHOD
                    else -> SymbolRole.FUNCTION
                }
            identPkt.targetCppName = cppName
        }

        val typeParams = FrameAstHelpers.extractTypeParams(stream, fnFrame)
        for (tp in typeParams) {
            FrameAstHelpers.directIdentPacket(stream, stream.frames[tp.frameIndex])?.let { pkt ->
                pkt.role = SymbolRole.TYPE_PARAM
                pkt.targetCppName = tp.name
            }
        }
        val (receiverFrame, returnFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, fnFrame)
        val paramList = FrameAstHelpers.firstChildFrame(stream, fnFrame, KtNodeTypes.VALUE_PARAMETER_LIST_ID)
        val valueParams = FrameAstHelpers.extractValueParams(stream, paramList)
        for (vp in valueParams) {
            FrameAstHelpers.directIdentPacket(stream, stream.frames[vp.frameIndex])?.let { pkt ->
                pkt.role = SymbolRole.PARAM
                pkt.targetCppName = vp.cppName
            }
        }

        val blockBody = FrameAstHelpers.firstChildFrame(stream, fnFrame, KtNodeTypes.BLOCK_ID)
        val exprBody =
            if (blockBody == null) {
                FrameAstHelpers.extractInitializerAfterEquals(stream, fnFrame)
            } else {
                null
            }
        val bodyFrame = blockBody ?: exprBody

        val isOperator = FrameAstHelpers.hasModifierKeyword(stream, fnFrame, KtTokens.OPERATOR_MODIFIER_ID)
        val isInfix = FrameAstHelpers.hasModifierKeyword(stream, fnFrame, KtTokens.INFIX_MODIFIER_ID)
        val isExternal = FrameAstHelpers.hasModifierKeyword(stream, fnFrame, KtTokens.EXTERNAL_MODIFIER_ID)

        var flags = fnFrame.flags or PacketFlags.EMIT_IN_HPP_DECL
        if (cAbiSymbol != null) {
            flags = flags or PacketFlags.FFI_C_ABI_SYMBOL
        }
        if (receiverFrame != null || isExternal) {
            flags = flags or PacketFlags.MOD_EXTERNAL_OR_EXTENSION
        }

        val isVirtualLike =
            enclosingClass?.kind == KtClassKind.INTERFACE ||
                PacketFlags.hasFlag(flags, PacketFlags.MOD_OVERRIDE) ||
                PacketFlags.hasFlag(flags, PacketFlags.MOD_OPEN) ||
                PacketFlags.hasFlag(flags, PacketFlags.MOD_ABSTRACT)
        val hasStarProjectionTemplate =
            !isVirtualLike &&
                cAbiSymbol == null &&
                (
                    (receiverFrame != null && frameHasNonVoidPtrStarProjection(stream, receiverFrame)) ||
                        valueParams.any { vp ->
                            vp.typeFrameIndex != null &&
                                frameHasNonVoidPtrStarProjection(stream, stream.frames[vp.typeFrameIndex])
                        }
                )
        val isTemplate = enclosingHasTypeParams || typeParams.isNotEmpty() || hasStarProjectionTemplate
        val isInline = PacketFlags.hasFlag(flags, PacketFlags.MOD_INLINE)
        val isPureDeclaration = bodyFrame == null
        val isNestedMemberClass = enclosingClass?.enclosingClassFrameIndex != null
        val isRichEnumHelper = enclosingClass?.kind == KtClassKind.RICH_ENUM
        val isValueOrDataClass =
            enclosingClass?.kind == KtClassKind.VALUE_CLASS || enclosingClass?.kind == KtClassKind.DATA_CLASS

        flags =
            when {
                isPureDeclaration -> flags
                isTemplate || isInline || isNestedMemberClass || isRichEnumHelper || isValueOrDataClass ->
                    flags or PacketFlags.EMIT_IN_HPP_INLINE_BODY
                else -> flags or PacketFlags.EMIT_IN_CPP_BODY
            }

        fnFrame.flags = flags
        fnFrame.semanticInfo =
            (fnFrame.semanticInfo ?: FrameSemanticInfo()).copy(symbolName = cppName)

        return KtFunctionSymbol(
            kotlinName = kotlinName,
            cppName = cppName,
            cAbiSymbolName = cAbiSymbol,
            frameIndex = fnFrame.frameIndex,
            enclosingClassFrameIndex = enclosingClass?.frameIndex,
            typeParams = typeParams,
            receiverTypeFrameIndex = receiverFrame?.frameIndex,
            valueParams = valueParams,
            returnTypeFrameIndex = returnFrame?.frameIndex,
            bodyFrameIndex = bodyFrame?.frameIndex,
            isExpressionBody = exprBody != null,
            isOperator = isOperator,
            isInfix = isInfix,
            isExternal = isExternal,
            annotations = annotations,
            flags = flags,
        )
    }

    private fun buildPropertySymbol(
        stream: PacketStream,
        propFrame: PacketFrame,
        enclosingClass: KtClassSymbol?,
        enclosingHasTypeParams: Boolean,
    ): KtPropertySymbol {
        val annotations = FrameAstHelpers.extractAnnotations(stream, propFrame)
        val identPkt = FrameAstHelpers.directIdentPacket(stream, propFrame)
        val kotlinName = identPkt?.unescapedName ?: "unnamed_prop"
        val isVar = FrameAstHelpers.hasDirectKeyword(stream, propFrame, KtTokens.VAR_KEYWORD_ID)
        val isConst = PacketFlags.hasFlag(propFrame.flags, PacketFlags.MOD_CONST)
        val isPrivate = PacketFlags.hasFlag(propFrame.flags, PacketFlags.MOD_PRIVATE)
        val isScreamingConst = !isVar && kotlinName.length > 1 && kotlinName.all { it.isUpperCase() || it.isDigit() || it == '_' }

        val (role, cppName) =
            when {
                isConst || isScreamingConst -> SymbolRole.CONSTANT to CppNamingPolicy.toConstName(kotlinName)
                enclosingClass != null && isPrivate &&
                    enclosingClass.kind != KtClassKind.OBJECT_SINGLETON &&
                    enclosingClass.kind != KtClassKind.COMPANION_OBJECT ->
                    SymbolRole.PRIVATE_FIELD to CppNamingPolicy.toPrivateFieldName(kotlinName)
                else -> SymbolRole.PROPERTY to CppNamingPolicy.toSnakeCase(kotlinName)
            }
        if (identPkt != null) {
            identPkt.role = role
            identPkt.targetCppName = cppName
        }

        val typeParams = FrameAstHelpers.extractTypeParams(stream, propFrame)
        for (tp in typeParams) {
            FrameAstHelpers.directIdentPacket(stream, stream.frames[tp.frameIndex])?.let { pkt ->
                pkt.role = SymbolRole.TYPE_PARAM
                pkt.targetCppName = tp.name
            }
        }
        val (receiverFrame, returnFrame) = FrameAstHelpers.extractReceiverAndReturnTypeFrames(stream, propFrame)
        val initFrame = FrameAstHelpers.extractInitializerAfterEquals(stream, propFrame)
        var getterIdx: Int? = null
        var setterIdx: Int? = null
        for (acc in FrameAstHelpers.childFramesOfType(stream, propFrame, KtNodeTypes.PROPERTY_ACCESSOR_ID)) {
            if (FrameAstHelpers.hasDirectKeyword(stream, acc, KtTokens.GET_KEYWORD_ID)) {
                getterIdx = acc.frameIndex
            } else if (FrameAstHelpers.hasDirectKeyword(stream, acc, KtTokens.SET_KEYWORD_ID)) {
                setterIdx = acc.frameIndex
            }
        }

        var flags = propFrame.flags or PacketFlags.EMIT_IN_HPP_DECL
        if (receiverFrame != null) {
            flags = flags or PacketFlags.MOD_EXTERNAL_OR_EXTENSION
        }
        flags =
            if (enclosingHasTypeParams || typeParams.isNotEmpty() || isConst || enclosingClass != null || (getterIdx == null && setterIdx == null)) {
                flags or PacketFlags.EMIT_IN_HPP_INLINE_BODY
            } else {
                flags or PacketFlags.EMIT_IN_CPP_BODY
            }
        propFrame.flags = flags
        propFrame.semanticInfo =
            (propFrame.semanticInfo ?: FrameSemanticInfo()).copy(symbolName = cppName)

        return KtPropertySymbol(
            kotlinName = kotlinName,
            cppName = cppName,
            frameIndex = propFrame.frameIndex,
            enclosingClassFrameIndex = enclosingClass?.frameIndex,
            typeParams = typeParams,
            isVar = isVar,
            isConst = isConst,
            receiverTypeFrameIndex = receiverFrame?.frameIndex,
            returnTypeFrameIndex = returnFrame?.frameIndex,
            initializerFrameIndex = initFrame?.frameIndex,
            getterFrameIndex = getterIdx,
            setterFrameIndex = setterIdx,
            annotations = annotations,
            flags = flags,
        )
    }

    private fun buildTypeAliasSymbol(
        stream: PacketStream,
        aliasFrame: PacketFrame,
    ): KtTypeAliasSymbol {
        val identPkt = FrameAstHelpers.directIdentPacket(stream, aliasFrame)
        val kotlinName = identPkt?.unescapedName ?: "UnnamedAlias"
        val cppName = CppNamingPolicy.toTypeName(kotlinName)
        if (identPkt != null) {
            identPkt.role = SymbolRole.TYPE
            identPkt.targetCppName = cppName
        }
        val typeParams = FrameAstHelpers.extractTypeParams(stream, aliasFrame)
        val targetType = FrameAstHelpers.firstChildFrame(stream, aliasFrame, KtNodeTypes.TYPE_REFERENCE_ID)
        aliasFrame.flags =
            aliasFrame.flags or PacketFlags.EMIT_IN_HPP_DECL or
            PacketFlags.EMIT_IN_HPP_INLINE_BODY
        aliasFrame.semanticInfo =
            (aliasFrame.semanticInfo ?: FrameSemanticInfo()).copy(symbolName = cppName)
        return KtTypeAliasSymbol(
            kotlinName = kotlinName,
            cppName = cppName,
            frameIndex = aliasFrame.frameIndex,
            typeParams = typeParams,
            underlyingTypeFrameIndex = targetType?.frameIndex,
        )
    }

    private fun detectForwardDeclarationsAndStampReferences(
        stream: PacketStream,
        classesByName: Map<String, KtClassSymbol>,
        functionsByName: Map<String, List<KtFunctionSymbol>>,
        propertiesByName: Map<String, KtPropertySymbol>,
        importAliases: Map<String, String>,
    ): List<Int> {
        val forwardNeeded = LinkedHashSet<Int>()

        for (frame in stream.frames) {
            if (frame.nodeTypeId == KtNodeTypes.USER_TYPE_ID) {
                val refExpr = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                val pkt =
                    if (refExpr != null) {
                        FrameAstHelpers.directIdentPacket(stream, refExpr)
                    } else {
                        FrameAstHelpers.directIdentPacket(stream, frame)
                    }
                if (pkt != null) {
                    val resolvedName = importAliases[pkt.unescapedName] ?: pkt.unescapedName
                    val sameFileClass = classesByName[resolvedName]
                    if (sameFileClass != null) {
                        pkt.role = SymbolRole.TYPE
                        pkt.targetCppName = sameFileClass.cppName
                        val declFrame = stream.frames[sameFileClass.frameIndex]
                        if (pkt.packetIndex < declFrame.firstPacketIndex &&
                            sameFileClass.enclosingClassFrameIndex == null &&
                            sameFileClass.kind != KtClassKind.ANNOTATION_CLASS
                        ) {
                            declFrame.flags = declFrame.flags or PacketFlags.NEEDS_FORWARD_DECL
                            forwardNeeded.add(sameFileClass.frameIndex)
                        }
                    } else if (pkt.role == SymbolRole.UNCLASSIFIED) {
                        pkt.role = SymbolRole.TYPE
                        pkt.targetCppName = CppNamingPolicy.toTypeName(resolvedName)
                    }
                }
            } else if (frame.nodeTypeId == KtNodeTypes.REFERENCE_EXPRESSION_ID) {
                val pkt = FrameAstHelpers.directIdentPacket(stream, frame) ?: continue
                if (pkt.targetCppName != null) continue
                val name = pkt.unescapedName
                val fnMatch = functionsByName[name]?.firstOrNull()
                val propMatch = propertiesByName[name]
                val clsMatch = classesByName[name]
                when {
                    fnMatch != null -> {
                        pkt.role = SymbolRole.FUNCTION
                        pkt.targetCppName = fnMatch.cppName
                    }
                    propMatch != null -> {
                        pkt.role = if (propMatch.isConst) SymbolRole.CONSTANT else SymbolRole.PROPERTY
                        pkt.targetCppName = propMatch.cppName
                    }
                    clsMatch != null -> {
                        pkt.role = SymbolRole.TYPE
                        pkt.targetCppName = clsMatch.cppName
                    }
                    else -> {
                        pkt.targetCppName =
                            if (name.firstOrNull()?.isUpperCase() == true) {
                                if (name.length > 1 && name.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
                                    name
                                } else {
                                    CppNamingPolicy.toTypeName(name)
                                }
                            } else {
                                CppNamingPolicy.toSnakeCase(name)
                            }
                    }
                }
            }
        }
        return forwardNeeded.toList()
    }

    private val PRESERVED_MULTIPLATFORM_SUFFIXES: List<String> =
        listOf(
            ".common_native",
            ".concurrent",
            ".common",
            ".native",
            ".darwin",
            ".apple",
            ".other",
        )

    private val IGNORED_IMPORT_PREFIXES: List<String> =
        listOf(
            "kotlin.jvm.",
            "kotlin.js.",
            "kotlin.contracts.",
            "kotlin.experimental.",
            "kotlinx.cinterop.",
            "kotlinx.atomicfu.",
        )

    private val KNOWN_POLYMORPHIC_TYPES: Set<String> =
        setOf(
            "Flow",
            "FlowCollector",
            "SharedFlow",
            "MutableSharedFlow",
            "StateFlow",
            "MutableStateFlow",
            "CoroutineContext",
            "Element",
            "Key",
            "DisposableHandle",
            "SelectClause0",
            "SelectClause1",
            "SelectClause2",
            "SelectInstance",
        )

    private val KNOWN_RESTRICTS_SUSPENSION_TYPES: Set<String> =
        setOf(
            "SequenceScope",
            "DeepRecursiveScope",
        )
}
