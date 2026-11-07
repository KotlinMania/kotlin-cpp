package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.quote.ToTokens
import io.github.kotlinmania.rustkotlin.reused.starlark.ModuleScopeBuilder
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.File
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.WherePredicate
import io.github.kotlinmania.syn.span

internal fun translateRustSourceToKotlin(
    recognized: RecognizedRust,
    headerContext: FileHeaderContext = FileHeaderContext(),
): String = translateRustSourceToKotlinArtifacts(recognized, headerContext).first

internal fun translateRustSourceToKotlinArtifacts(
    recognized: RecognizedRust,
    headerContext: FileHeaderContext = FileHeaderContext(),
): Pair<String, List<CompanionInteropFile>> {
    val prevRawAbi = TranslationSession.rawAbiMode
    val prevFinalStructs = TranslationSession.finalStructs
    val prevCDynFfi = TranslationSession.cDynamicFfi
    val prevRelDir = TranslationSession.currentRelativeSourceDir
    val prevCfgProfile = TranslationSession.cfgTargetProfile
    val prevAliasDefaults = TranslationSession.crateTypeAliasDefaults
    val prevOptionFnTypes = TranslationSession.crateOptionFnTypes
    val prevStructFields = TranslationSession.crateStructFieldTypes
    val prevFnReturns = TranslationSession.crateFnReturnTypes
    TranslationSession.rawAbiMode = headerContext.rawAbiMode
    TranslationSession.finalStructs = headerContext.finalClasses
    TranslationSession.cDynamicFfi = headerContext.cDynamicFfi
    TranslationSession.currentRelativeSourceDir = headerContext.currentRelativeSourceDir
    if (headerContext.cfgTargetProfile != null) {
        TranslationSession.cfgTargetProfile = headerContext.cfgTargetProfile
    }
    TranslationSession.crateTypeAliasDefaults = headerContext.crateTypeAliasDefaults
    TranslationSession.crateOptionFnTypes = headerContext.crateOptionFnTypes
    TranslationSession.crateStructFieldTypes = headerContext.crateStructFieldTypes
    TranslationSession.crateFnReturnTypes = headerContext.crateFnReturnTypes
    try {
        validateSupportedRustSemantics(recognized.file, allowManuallyDrop = headerContext.rawAbiMode)
        val trivia = SourceTrivia.collect(recognized.source, recognized.tokens)
        val macroExpansion = expandFileMacroRules(recognized.file, headerContext.crateMacroSources)
        val emitter =
            KotlinJ2KEmitter(
                source = recognized.source,
                originalFile = recognized.file,
                file = macroExpansion.file,
                trivia = trivia,
                macroDefs = macroExpansion.definitions,
                macroWrapperSpansByTargetOffset = macroExpansion.macroWrapperSpansByTargetOffset,
                headerContext = headerContext,
            )
        return emitter.emitFile() to emitter.emitCompanionFiles()
    } finally {
        TranslationSession.rawAbiMode = prevRawAbi
        TranslationSession.finalStructs = prevFinalStructs
        TranslationSession.cDynamicFfi = prevCDynFfi
        TranslationSession.currentRelativeSourceDir = prevRelDir
        TranslationSession.cfgTargetProfile = prevCfgProfile
        TranslationSession.crateTypeAliasDefaults = prevAliasDefaults
        TranslationSession.crateOptionFnTypes = prevOptionFnTypes
        TranslationSession.crateStructFieldTypes = prevStructFields
        TranslationSession.crateFnReturnTypes = prevFnReturns
    }
}

internal data class ModuleRootChunkTranslation(
    val fileName: String,
    val text: String,
    val itemCount: Int,
    val companionFiles: List<CompanionInteropFile> = emptyList(),
)

internal fun hasEmittedKotlinDeclarations(kotlinText: String): Boolean {
    var inBlockComment = false
    for (rawLine in kotlinText.lineSequence()) {
        var line = rawLine.trim()
        if (inBlockComment) {
            val endIdx = line.indexOf("*/")
            if (endIdx < 0) continue
            inBlockComment = false
            line = line.substring(endIdx + 2).trim()
        }
        while (line.startsWith("/*")) {
            val endIdx = line.indexOf("*/", startIndex = 2)
            if (endIdx < 0) {
                inBlockComment = true
                line = ""
                break
            }
            line = line.substring(endIdx + 2).trim()
        }
        if (line.isEmpty() ||
            line.startsWith("//") ||
            line.startsWith("@file:") ||
            line.startsWith("package ") ||
            line.startsWith("import ")
        ) {
            continue
        }
        return true
    }
    return false
}

private fun isNonEmittingModuleRootMacro(item: Item.Macro): Boolean {
    if (item.mac.path.isIdent("macro_rules")) {
        return item.attrs.none { it.path().isIdent("macro_export") }
    }
    val segs = item.mac.path.segments
    val pathText = segs.toList().joinToString("::") { it.ident.toString() }
    return pathText == "include"
}

private fun extractChunkOwnerTypeName(selfTy: SynType, generics: Generics): String? {
    val typeParams =
        generics.params
            .toList()
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .toSet()
    return when (val u = unwrapSynType(selfTy)) {
        is SynType.Reference -> extractChunkOwnerTypeName(u.elem, generics)
        is SynType.Path -> {
            if (u.qself != null) return null
            val segs = u.path.segments.toList()
            if (segs.size != 1) return null
            val name = rustTypeIdentToKotlin(segs[0].ident.toString())
            if (name in typeParams) null else name
        }
        else -> null
    }
}

internal fun translateModuleRootIntoFocusedChunks(
    source: String,
    modulePascalName: String,
    headerContext: FileHeaderContext,
): List<ModuleRootChunkTranslation> {
    val prevRawAbi = TranslationSession.rawAbiMode
    val prevFinalStructs = TranslationSession.finalStructs
    val prevCDynFfi = TranslationSession.cDynamicFfi
    val prevRelDir = TranslationSession.currentRelativeSourceDir
    val prevCfgProfile = TranslationSession.cfgTargetProfile
    val prevAliasDefaults = TranslationSession.crateTypeAliasDefaults
    val prevOptionFnTypes = TranslationSession.crateOptionFnTypes
    val prevStructFields = TranslationSession.crateStructFieldTypes
    val prevFnReturns = TranslationSession.crateFnReturnTypes
    val prevSharedMod = TranslationSession.sharedModuleFile
    TranslationSession.rawAbiMode = headerContext.rawAbiMode
    TranslationSession.finalStructs = headerContext.finalClasses
    TranslationSession.cDynamicFfi = headerContext.cDynamicFfi
    TranslationSession.currentRelativeSourceDir = headerContext.currentRelativeSourceDir
    if (headerContext.cfgTargetProfile != null) {
        TranslationSession.cfgTargetProfile = headerContext.cfgTargetProfile
    }
    TranslationSession.crateTypeAliasDefaults = headerContext.crateTypeAliasDefaults
    TranslationSession.crateOptionFnTypes = headerContext.crateOptionFnTypes
    TranslationSession.crateStructFieldTypes = headerContext.crateStructFieldTypes
    TranslationSession.crateFnReturnTypes = headerContext.crateFnReturnTypes
    try {
        val recognized = recognizeRust(source)
        validateSupportedRustSemantics(recognized.file, allowManuallyDrop = headerContext.rawAbiMode)
        val trivia = SourceTrivia.collect(recognized.source, recognized.tokens)
        val macroExpansion = expandFileMacroRules(recognized.file, headerContext.crateMacroSources)
        val expandedFile = macroExpansion.file

        val substantiveItems =
            expandedFile.items.filter { item ->
                if (itemHasDocOnlyCfg(item)) return@filter false
                when (item) {
                    is Item.ExternCrate, is Item.Use -> false
                    is Item.Mod -> item.content is ModContent.Inline
                    is Item.Macro -> !isNonEmittingModuleRootMacro(item)
                    else -> true
                }
            }
        if (substantiveItems.isEmpty()) {
            return emptyList()
        }

        val fullInspector = CrateSymbolInspector(headerContext.selfCrateAliases).also { it.visitFile(expandedFile) }
        val implsByOwner = linkedMapOf<String, MutableList<Item.Impl>>()
        val standaloneImpls = mutableListOf<Item.Impl>()
        for (item in substantiveItems) {
            if (item is Item.Impl) {
                val owner = extractChunkOwnerTypeName(item.selfType, item.generics)
                if (owner != null && owner in fullInspector.attachableTypeNames) {
                    implsByOwner.getOrPut(owner) { mutableListOf() }.add(item)
                } else {
                    standaloneImpls += item
                }
            }
        }

        val typeBucket = mutableListOf<Item>()
        val structOwnerGroups = mutableListOf<List<Item>>()
        val constBucket = mutableListOf<Item>()
        val fnBucket = mutableListOf<Item>()
        val consumedImplOwners = hashSetOf<String>()

        for (item in substantiveItems) {
            when (item) {
                is Item.Impl -> {}
                is Item.ItemType, is Item.Trait, is Item.TraitAlias, is Item.Mod -> {
                    typeBucket += item
                }
                is Item.Enum -> {
                    val ktName = rustTypeIdentToKotlin(item.ident.toString())
                    typeBucket += item
                    if (consumedImplOwners.add(ktName)) {
                        implsByOwner[ktName]?.let { typeBucket.addAll(it) }
                    }
                }
                is Item.Struct -> {
                    val ktName = rustTypeIdentToKotlin(item.ident.toString())
                    val group = mutableListOf<Item>(item)
                    if (consumedImplOwners.add(ktName)) {
                        implsByOwner[ktName]?.let { group.addAll(it) }
                    }
                    structOwnerGroups += group
                }
                is Item.Union -> {
                    val ktName = rustTypeIdentToKotlin(item.ident.toString())
                    val group = mutableListOf<Item>(item)
                    if (consumedImplOwners.add(ktName)) {
                        implsByOwner[ktName]?.let { group.addAll(it) }
                    }
                    structOwnerGroups += group
                }
                is Item.Const, is Item.Static -> {
                    constBucket += item
                }
                else -> {
                    fnBucket += item
                }
            }
        }
        fnBucket.addAll(standaloneImpls)

        val plannedChunks = mutableListOf<Pair<String, List<Item>>>()
        if (typeBucket.isNotEmpty()) {
            plannedChunks += "${modulePascalName}Types.kt" to typeBucket
        }
        if (structOwnerGroups.isNotEmpty()) {
            val structChunks = structOwnerGroups.chunked(250)
            structChunks.forEachIndexed { idx, groups ->
                val suffix = if (idx == 0) "Structs.kt" else "Structs${idx + 1}.kt"
                plannedChunks += "$modulePascalName$suffix" to groups.flatten()
            }
        }
        if (constBucket.isNotEmpty()) {
            val constChunks = constBucket.chunked(1500)
            constChunks.forEachIndexed { idx, items ->
                val suffix = if (idx == 0) "Constants.kt" else "Constants${idx + 1}.kt"
                plannedChunks += "$modulePascalName$suffix" to items
            }
        }
        if (fnBucket.isNotEmpty()) {
            val fnChunks = fnBucket.chunked(500)
            fnChunks.forEachIndexed { idx, items ->
                val suffix = if (idx == 0) "Functions.kt" else "Functions${idx + 1}.kt"
                plannedChunks += "$modulePascalName$suffix" to items
            }
        }

        TranslationSession.sharedModuleFile = expandedFile
        val chunkHeader =
            headerContext.copy(
                portLintSourcePath = null,
                portLintDirective = "none",
                isModuleRootSource = true,
                moduleDeclaredTypeNames = fullInspector.declaredTypeNames.toSet(),
            )
        val emittedChunks = mutableListOf<ModuleRootChunkTranslation>()
        for ((chunkFileName, chunkItems) in plannedChunks) {
            val chunkAst = File(shebang = null, attrs = emptyList(), items = chunkItems)
            val emitter =
                KotlinJ2KEmitter(
                    source = recognized.source,
                    originalFile = chunkAst,
                    file = chunkAst,
                    trivia = trivia,
                    macroDefs = macroExpansion.definitions,
                    macroWrapperSpansByTargetOffset = macroExpansion.macroWrapperSpansByTargetOffset,
                    headerContext = chunkHeader,
                )
            val text = emitter.emitFile()
            val companions = emitter.emitCompanionFiles()
            if (hasEmittedKotlinDeclarations(text) || companions.isNotEmpty()) {
                emittedChunks +=
                    ModuleRootChunkTranslation(
                        fileName = chunkFileName,
                        text = text,
                        itemCount = chunkItems.size,
                        companionFiles = companions,
                    )
            }
        }
        return emittedChunks
    } finally {
        TranslationSession.sharedModuleFile = prevSharedMod
        TranslationSession.rawAbiMode = prevRawAbi
        TranslationSession.finalStructs = prevFinalStructs
        TranslationSession.cDynamicFfi = prevCDynFfi
        TranslationSession.currentRelativeSourceDir = prevRelDir
        TranslationSession.cfgTargetProfile = prevCfgProfile
        TranslationSession.crateTypeAliasDefaults = prevAliasDefaults
        TranslationSession.crateOptionFnTypes = prevOptionFnTypes
        TranslationSession.crateStructFieldTypes = prevStructFields
        TranslationSession.crateFnReturnTypes = prevFnReturns
    }
}

private val UNSIGNED_ARRAY_IDENTS =
    listOf(
        "UByteArray",
        "UShortArray",
        "UIntArray",
        "ULongArray",
        "ubyteArrayOf",
        "ushortArrayOf",
        "uintArrayOf",
        "ulongArrayOf",
    )

internal class KotlinJ2KEmitter(
    internal val source: String,
    internal val originalFile: File,
    internal val file: File,
    internal val trivia: SourceTrivia,
    internal val macroDefs: Map<String, MacroRulesDef>,
    internal val macroWrapperSpansByTargetOffset: Map<Int, List<Span>>,
    internal val headerContext: FileHeaderContext,
) {
    internal val inspector =
        CrateSymbolInspector(headerContext.selfCrateAliases).also { sym ->
            sym.declaredTypeNames.addAll(headerContext.moduleDeclaredTypeNames)
            val sharedModFile = TranslationSession.sharedModuleFile
            if (sharedModFile != null) {
                val sharedInsp = CrateSymbolInspector(headerContext.selfCrateAliases).also { it.visitFile(sharedModFile) }
                sym.declaredTypeNames.addAll(sharedInsp.declaredTypeNames)
                sym.unitStructNames.addAll(sharedInsp.unitStructNames)
                sym.typeAliasTargets.putAll(sharedInsp.typeAliasTargets)
                sym.declaredConstNames.putAll(sharedInsp.declaredConstNames)
                sym.declaredConstTypes.putAll(sharedInsp.declaredConstTypes)
                sym.structFieldNamesByType.putAll(sharedInsp.structFieldNamesByType)
                sym.structFieldSynTypesByType.putAll(sharedInsp.structFieldSynTypesByType)
                sym.declaredTopLevelFnRawNames.addAll(sharedInsp.declaredTopLevelFnRawNames)
                sym.topLevelFnReturnSynTypes.putAll(sharedInsp.topLevelFnReturnSynTypes)
                sym.implMethodReturnSynTypes.putAll(sharedInsp.implMethodReturnSynTypes)
                sym.localInlineModuleNames.addAll(sharedInsp.localInlineModuleNames)
                sym.localExternalModuleNames.addAll(sharedInsp.localExternalModuleNames)
                sym.allUseItems.addAll(sharedInsp.allUseItems)
                sym.enumVariantSimpleNames.addAll(sharedInsp.enumVariantSimpleNames)
                sym.importedEnumVariantSimpleNames.addAll(sharedInsp.importedEnumVariantSimpleNames)
                sym.ffiReferencedTypeNames.addAll(sharedInsp.ffiReferencedTypeNames)
                if (sharedInsp.hasImportedEnumGlob) sym.hasImportedEnumGlob = true
            }
            sym.visitFile(file)
            sym.closeFfiReferencedTypeAliases()
            if (sharedModFile != null && sym.allUseItems.size > 1) {
                val deduped = sym.allUseItems.distinctBy { it.span().startOffset() }
                sym.allUseItems.clear()
                sym.allUseItems.addAll(deduped)
            }
            for ((rawConst, ktConst) in sym.declaredConstNames.entries.toList()) {
                if (ktConst in sym.declaredTypeNames) {
                    val disamb = "${ktConst}_CONST"
                    sym.declaredConstNames[rawConst] = disamb
                    sym.declaredConstTypes[ktConst]?.let { cTy -> sym.declaredConstTypes[disamb] = cTy }
                }
            }
        }
    internal val ffiCollector =
        FfiInteropCollector(
            source = source,
            headerContext = headerContext,
            resolveNumericKind = { ktType -> resolveNumericKind(ktType) },
            resolveTypeAliasTarget = { ktType ->
                val simple = ktType.substringAfterLast('.').removeSuffix("?")
                inspector.typeAliasTargets[ktType]
                    ?: inspector.typeAliasTargets[simple]
                    ?: inspector.typeAliasTargets[rustTypeIdentToKotlin(simple)]
            },
        )
    internal val scopeBuilder = ModuleScopeBuilder()
    internal val topLevelSignatureCounts = linkedMapOf<String, Int>()
    internal val emittedTriviaStartBytes = hashSetOf<Int>()
    internal val synthesizedSamDeclarations = linkedMapOf<String, String>()
    internal val synthesizedSamNamesByShape = linkedMapOf<String, String>()
    internal val synthesizedSamDefaultsByName = linkedMapOf<String, String>()
    internal val importedSimpleNameToPkg = linkedMapOf<String, String>()
    internal val extraTypePathImports = linkedSetOf<String>()
    internal val standaloneTraitImplKeys = linkedSetOf<Pair<String, String>>()
    internal val witnessedSuppressedTraitNames = linkedSetOf<String>()
    internal var currentImplAssocTypes: Map<String, SynType> = emptyMap()
    internal var currentTraitAssocParamNames: Set<String> = emptySet()
    internal var currentTypeParamWitnessCalls: Map<Pair<String, String>, String> = emptyMap()
    internal var currentAttachedImplTraitPath: Path? = null
    internal var currentAttachedImplIsRefSelf: Boolean = false
    internal var currentStandaloneImplTraitPath: Path? = null
    internal var currentStandaloneImplSelfSynType: SynType? = null
    internal var currentBoundedTypeName: String? = null
    internal var substitutedTypeParamTypes: Map<String, String> = emptyMap()
    internal var activeGuardBindingSubstitutions: Map<String, String> = emptyMap()
    internal var activeTypeParamNames: Set<String> = emptySet()
    internal var currentSamNameHint: String? = null
    internal var currentEnclosingFunctionName: String? = null
    internal var currentEnclosingReturnRenderedType: String? = null
    internal var currentExpectedParseTargetType: String? = null
    internal var currentMutableListLocals: Set<String> = emptySet()
    internal var currentDequeLocals: Set<String> = emptySet()
    internal var currentIterableLocals: Set<String> = emptySet()
    internal var currentTypedLocals: Map<String, String> = emptyMap()
    internal var currentWitnessedTraitBounds: Map<String, Set<String>> = emptyMap()
    internal var currentStructOverrideMethodNames: Set<String> = emptySet()
    internal var currentHasherStateParam: String? = null
    internal var currentNullableLocals: Set<String> = emptySet()
    internal var currentExpectedNumericKind: NumericKind? = null
    internal var currentFunctionParamNumericKinds: Map<String, List<NumericKind?>> = emptyMap()
    internal var currentClosureLabel: String? = null
    internal var closureLabelCounter: Int = 0
    internal var inExternAbiSignature: Boolean = false
    internal var inExternParamType: Boolean = false
    internal var usesOpaquePointer: Boolean = false
    internal var usesFileHandle: Boolean = false
    internal var usesDirHandle: Boolean = false
    internal var usesCurrentThreadToken: Boolean = false
    internal var usesQuoteUnderlyingSpan: Boolean = false
    internal var usesDebugStruct: Boolean = false
    internal var usesDebugTuple: Boolean = false
    internal var usesWin32DynamicFfi: Boolean = false
    internal var usesLibcDynamicFfi: Boolean = false
    internal var usesU128: Boolean = false
    internal var usesI128: Boolean = false
    internal val activeCfgProfile: CfgTargetProfile
        get() = headerContext.cfgTargetProfile ?: TranslationSession.cfgTargetProfile

    internal fun emitCompanionFiles(): List<CompanionInteropFile> = ffiCollector.emitCompanionFiles()

    internal fun renderKDocFromAttributes(attrs: List<Attribute>, indent: String): String =
        io.github.kotlinmania.rustkotlin.renderKDocFromAttributes(
            attrs = attrs,
            indent = indent,
            includeDocExampleAnnotations = headerContext.isDocExample,
        )

    internal fun emitFile(): String {
        val out = StringBuilder()

        // Partition impl blocks into those belonging to a local type declared in this file
        // vs standalone/external impl blocks, and hoist all `use` imports to the top.
        val localTypeImpls = linkedMapOf<String, MutableList<Item.Impl>>()
        val standaloneItems = mutableListOf<Item>()

        for (item in file.items) {
            if (itemHasDocOnlyCfg(item)) continue
            if (item is Item.Use) {
                registerUseItemInScope(item)
                continue
            }
            if (item is Item.Impl) {
                val ownerType = extractDirectOwnerTypeName(item.selfType, item.generics)
                if (ownerType != null && ownerType in inspector.attachableTypeNames) {
                    localTypeImpls.getOrPut(ownerType) { mutableListOf() }.add(item)
                    continue
                }
            }
            standaloneItems += item
        }

        val attachedBlanketImpls = mutableListOf<Item.Impl>()
        for (item in standaloneItems) {
            if (item is Item.Impl && item.traitPath != null) {
                val matchedOwners = matchBlanketLocalIteratorImplOwners(item, localTypeImpls)
                if (matchedOwners.isNotEmpty()) {
                    for (owner in matchedOwners) {
                        localTypeImpls.getOrPut(owner) { mutableListOf() }.add(item)
                    }
                    attachedBlanketImpls += item
                }
            }
        }
        if (attachedBlanketImpls.isNotEmpty()) {
            standaloneItems.removeAll(attachedBlanketImpls.toSet())
        }
        for (item in standaloneItems) {
            if (item is Item.Impl) {
                val tPath = item.traitPath?.path ?: continue
                val traitKt =
                    tPath.segments
                        .last()
                        ?.ident
                        ?.toString()
                        ?.let(::rustTypeIdentToKotlin) ?: continue
                val selfDesc = describeSynTypeForDisambiguation(item.selfType)
                if (traitKt.isNotEmpty() && selfDesc.isNotEmpty()) {
                    standaloneTraitImplKeys += (traitKt to selfDesc)
                }
            }
        }
        val topLevelFnParamKinds = linkedMapOf<String, List<NumericKind?>?>()
        for (item in standaloneItems) {
            if (item is Item.Fn) {
                val extracted = extractFnParamNumericKinds(item, selfTypeName = null)
                if (extracted != null) {
                    val (fnName, kinds) = extracted
                    topLevelFnParamKinds[fnName] = if (fnName in topLevelFnParamKinds) null else kinds
                }
            }
        }
        val implScanItems = TranslationSession.sharedModuleFile?.items ?: file.items
        for (item in implScanItems) {
            if (item is Item.Impl && item.traitPath == null && item.generics.params.isEmpty() && !itemHasDocOnlyCfg(item)) {
                val ownerType = extractDirectOwnerTypeName(item.selfType, item.generics) ?: continue
                for (implItem in item.items) {
                    if (implItem is ImplItem.Fn && !implItemHasDocOnlyCfg(implItem)) {
                        val extracted = extractSigParamNumericKinds(implItem.sig, ownerType)
                        if (extracted != null) {
                            val (fnName, kinds) = extracted
                            val qualKey = "$ownerType.$fnName"
                            topLevelFnParamKinds[qualKey] = if (qualKey in topLevelFnParamKinds) null else kinds
                        }
                    }
                }
            }
        }
        for ((crateFnKey, kindCodes) in headerContext.crateFnParamNumericKinds) {
            if (crateFnKey !in topLevelFnParamKinds) {
                topLevelFnParamKinds[crateFnKey] = kindCodes.map { numericKindFromScannerCode(it.takeIf(String::isNotEmpty)) }
            }
        }
        currentFunctionParamNumericKinds =
            topLevelFnParamKinds.entries
                .mapNotNull { (k, v) -> v?.let { k to it } }
                .toMap()

        val renderedItems = mutableListOf<String>()

        // Emit associated type aliases required for TypeParity if not already declared as types
        for ((assocName, assocDecl) in inspector.associatedTypeDecls) {
            if (assocName !in inspector.declaredTypeNames && assocName !in KOTLIN_STDLIB_SHADOWED_IMPORTS) {
                val targetType = renderType(assocDecl.ty, selfTypeName = null)
                if (targetType.isEmpty() || targetType == assocName) continue
                val candidateParams =
                    assocDecl.generics.params
                        .toList()
                        .mapNotNull { param ->
                            (param as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin)
                        }.distinct()
                val strippedTarget = targetType.trim().removeSuffix("?")
                if (strippedTarget in candidateParams || strippedTarget in activeTypeParamNames) continue
                inspector.declaredTypeNames += assocName
                val usedParams = candidateParams.filter { containsWholeIdentifier(targetType, it) }
                val typeParamSuffix = if (usedParams.isEmpty()) "" else "<${usedParams.joinToString(", ")}>"
                renderedItems += "public typealias $assocName$typeParamSuffix = $targetType"
            }
        }

        for (item in standaloneItems) {
            val rendered = renderItem(item, indent = "", localTypeImpls = localTypeImpls)
            if (rendered.isNotBlank()) {
                renderedItems += rendered.trimEnd()
            }
        }

        if (usesCurrentThreadToken && topLevelSignatureCounts["currentThreadBoundToken()"] == null) {
            renderedItems.add(0, "internal expect fun currentThreadBoundToken(): Any")
        }
        if (usesDebugTuple && "DebugTuple" !in inspector.declaredTypeNames) {
            renderedItems.add(0, SYNTHESIZED_DEBUG_TUPLE_DECLARATION)
        }
        if (usesDebugStruct && "DebugStruct" !in inspector.declaredTypeNames) {
            renderedItems.add(0, SYNTHESIZED_DEBUG_STRUCT_DECLARATION)
        }
        val needsSynthesizedInt128 =
            (usesU128 || usesI128) &&
                "U128" !in inspector.declaredTypeNames &&
                "I128" !in inspector.declaredTypeNames &&
                "U128" !in headerContext.crateSymbolSubpackages &&
                "I128" !in headerContext.crateSymbolSubpackages
        if (needsSynthesizedInt128) {
            TranslationSession.emittedInt128Support = true
            if (!headerContext.isBatchCrateOutput) {
                renderedItems.add(0, SYNTHESIZED_I128_DECLARATION)
                renderedItems.add(0, SYNTHESIZED_U128_DECLARATION)
            }
        }

        val inlineExpects = ffiCollector.renderInlineExpectDeclarations()
        renderedItems.addAll(inlineExpects)

        val preMacroBodyEmpty =
            synthesizedSamDeclarations.isEmpty() &&
                renderedItems.isEmpty() &&
                ffiCollector.emitCompanionFiles().isEmpty()
        val activeDynFfiObj =
            when {
                headerContext.win32DynamicFfi -> "Win32DynamicFfi"
                headerContext.cDynamicFfi -> "LibcDynamicFfi"
                else -> null
            }
        val standaloneMacros =
            if (headerContext.isDocExample) {
                StandaloneMacroEmission(emptyList(), emptySet())
            } else {
                val existingNames =
                    buildSet {
                        addAll(inspector.declaredTypeNames)
                        addAll(inspector.declaredConstNames.values)
                        addAll(topLevelFnParamKinds.keys)
                    }
                emitStandaloneMacroApproximations(
                    originalFile = originalFile,
                    existingBodyItemsEmpty = preMacroBodyEmpty && !headerContext.isModuleRootSource,
                    includeDocExampleAnnotations = headerContext.isDocExample,
                    declaredNames = existingNames,
                    rawAbiMode = headerContext.rawAbiMode,
                    dynamicFfiObject = activeDynFfiObj,
                )
            }
        if (standaloneMacros.usesDynamicFfiObject) {
            if (headerContext.win32DynamicFfi) usesWin32DynamicFfi = true
            if (headerContext.cDynamicFfi) usesLibcDynamicFfi = true
        }
        renderedItems.addAll(standaloneMacros.declarations)

        val allBodyItems =
            buildList {
                addAll(synthesizedSamDeclarations.values)
                addAll(renderedItems)
            }
        if (allBodyItems.isEmpty() && ffiCollector.emitCompanionFiles().isEmpty() && !headerContext.isDocExample && !headerContext.isModuleRootSource) {
            val unemittedItems = describeUnemittedSubstantiveItems(originalFile)
            if (unemittedItems.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Unsupported macro-only Rust source: macro invocations (${unemittedItems.joinToString(", ")}) " +
                        "have no standalone Kotlin item translation",
                )
            }
        }

        val bodyTextForImports = allBodyItems.joinToString("\n\n")
        val needsUnsignedArrayOptIn =
            UNSIGNED_ARRAY_IDENTS.any { containsWholeIdentifier(bodyTextForImports, it) }

        if (headerContext.portLintDirective != "none") {
            headerContext.portLintSourcePath?.let { relPath ->
                out
                    .append("// port-lint: ")
                    .append(headerContext.portLintDirective)
                    .append(' ')
                    .append(relPath)
                    .append('\n')
            }
        }
        if (needsUnsignedArrayOptIn) {
            out.append("@file:OptIn(ExperimentalUnsignedTypes::class)\n")
        }
        headerContext.packageName?.let { pkg ->
            out.append("package ").append(pkg).append("\n\n")
        }

        val extraImports = mutableListOf<String>()
        extraImports.addAll(
            extraTypePathImports.filter { imp ->
                val simple = imp.substringAfterLast('.')
                simple !in inspector.declaredTypeNames && containsWholeIdentifier(bodyTextForImports, simple)
            },
        )
        val rawProjNs = headerContext.projectNamespace?.trim()?.removeSuffix(".")
        val projNs = rawProjNs?.takeIf { it.isNotEmpty() }
        if (usesWin32DynamicFfi && projNs != null && headerContext.packageName != projNs) {
            extraImports += "import $projNs.Win32DynamicFfi"
        }
        if (usesLibcDynamicFfi && projNs != null && headerContext.packageName != projNs) {
            extraImports += "import $projNs.LibcDynamicFfi"
        }
        if (needsSynthesizedInt128 && headerContext.isBatchCrateOutput && projNs != null && headerContext.packageName != projNs) {
            if (containsWholeIdentifier(bodyTextForImports, "U128")) {
                extraImports += "import $projNs.U128"
            }
            if (containsWholeIdentifier(bodyTextForImports, "I128")) {
                extraImports += "import $projNs.I128"
            }
        }
        val libcNs = FfiInteropCollector.resolveLibcNamespace(headerContext)
        if (libcNs != null && headerContext.packageName != libcNs) {
            if (usesOpaquePointer && "COpaquePointer" !in inspector.declaredTypeNames) {
                extraImports += "import $libcNs.COpaquePointer"
            }
            if (usesFileHandle && "FILE" !in inspector.declaredTypeNames) {
                extraImports += "import $libcNs.FILE"
            }
            if (usesDirHandle && "DIR" !in inspector.declaredTypeNames) {
                extraImports += "import $libcNs.DIR"
            }
        }
        if (usesQuoteUnderlyingSpan) {
            val quoteNs =
                headerContext.crateNamespaces["quote"]
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() }
                    ?: headerContext.projectGroup
                        ?.trim()
                        ?.removeSuffix(".")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { "$it.quote" }
            if (quoteNs != null && headerContext.packageName != quoteNs) {
                extraImports += "import $quoteNs.__span"
            }
        }

        val importLines =
            (inspector.allUseItems.flatMap { renderItemUse(it, indent = "", bodyTextForPubUseFilter = bodyTextForImports).lines() } + extraImports)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        if (importLines.isNotEmpty()) {
            out.append(importLines.joinToString("\n")).append("\n\n")
        }

        val fileDocLines = mutableListOf<String>()
        fileDocLines.addAll(extractDocLinesFromAttributes(file.attrs))
        val rawInnerDocLines = mutableListOf<String>()
        for (innerDoc in trivia.fileInnerDocs) {
            val raw =
                when (innerDoc.style) {
                    TriviaStyle.InnerDocLine -> innerDoc.text.removePrefix("//!")
                    TriviaStyle.InnerDocBlock -> innerDoc.text.removePrefix("/*!").removeSuffix("*/")
                    else -> innerDoc.text
                }
            for (line in raw.split('\n')) {
                rawInnerDocLines +=
                    line.trimEnd('\r').let {
                        if (innerDoc.style == TriviaStyle.InnerDocBlock) it.trim().removePrefix("*") else it
                    }
            }
        }
        for (sanitized in sanitizeDocLines(rawInnerDocLines)) {
            if (sanitized.isNotEmpty()) {
                fileDocLines += sanitized
            }
        }
        if (allBodyItems.isNotEmpty()) {
            for (origItem in originalFile.items) {
                if (origItem is Item.Macro && origItem.mac.path.isIdent("macro_rules") && !itemHasDocOnlyCfg(origItem)) {
                    val origMacroName = origItem.ident?.toString()?.removePrefix("r#")
                    if (origMacroName != null && origMacroName in standaloneMacros.emittedMacroNames) {
                        continue
                    }
                    val macroDocs = extractDocLinesFromAttributes(origItem.attrs)
                    if (macroDocs.isNotEmpty()) {
                        if (fileDocLines.isNotEmpty()) fileDocLines += ""
                        fileDocLines.addAll(macroDocs)
                    }
                }
            }
        }
        if (fileDocLines.isNotEmpty()) {
            for (docLine in fileDocLines) {
                if (docLine.isEmpty()) {
                    out.append("//\n")
                } else {
                    out.append("// ").append(docLine).append('\n')
                }
            }
            out.append('\n')
        }

        out.append(allBodyItems.joinToString("\n\n"))
        if (out.isNotEmpty() && !out.endsWith('\n')) {
            out.append('\n')
        }
        return out.toString()
    }

    internal fun extractDirectOwnerTypeName(selfTy: SynType, generics: Generics): String? {
        val typeParamNames =
            generics.params
                .toList()
                .mapNotNull { param ->
                    (param as? GenericParam.TypeParam)?.ident?.toString()?.let { rustTypeIdentToKotlin(it) }
                }.toSet()
        return when (selfTy) {
            is SynType.Path -> {
                if (selfTy.qself != null) return null
                val segments = selfTy.path.segments.toList()
                if (segments.size != 1) return null
                val rawSeg = segments[0].ident.toString()
                if (rawSeg == "Box" && "Box" !in inspector.declaredTypeNames) {
                    val args = (segments[0].arguments as? PathArguments.AngleBracketed)?.args?.toList().orEmpty()
                    val firstTypeArg = args.filterIsInstance<GenericArgument.TypeArg>().firstOrNull()
                    return if (firstTypeArg != null) extractDirectOwnerTypeName(firstTypeArg.type, generics) else null
                }
                if (rawSeg in PRIMITIVE_TYPE_NAMES) return null
                val name = rustTypeIdentToKotlin(rawSeg)
                if (name in typeParamNames) null else name
            }
            is SynType.Reference -> extractDirectOwnerTypeName(selfTy.elem, generics)
            is SynType.Group -> extractDirectOwnerTypeName(selfTy.elem, generics)
            is SynType.Paren -> extractDirectOwnerTypeName(selfTy.elem, generics)
            else -> null
        }
    }

    internal fun classifySynRefKind(ty: SynType): String =
        when (val u = unwrapSynType(ty)) {
            is SynType.Reference -> if (u.mutability != null) "mut_ref" else "shared_ref"
            else -> "owned"
        }

    internal fun matchBlanketLocalIteratorImplOwners(
        item: Item.Impl,
        localTypeImpls: Map<String, List<Item.Impl>>,
    ): List<String> {
        val traitPath = item.traitPath?.path ?: return emptyList()
        val blanketTraitKt =
            traitPath.segments
                .last()
                ?.ident
                ?.toString()
                ?.let(::rustTypeIdentToKotlin) ?: return emptyList()
        if (blanketTraitKt !in inspector.declaredTraitsByName) return emptyList()
        val selfPath =
            (unwrapSynType(item.selfType) as? SynType.Path)
                ?.takeIf { it.qself == null && it.path.segments.len() == 1 }
                ?: return emptyList()
        val firstSelfSeg = selfPath.path.segments.first() ?: return emptyList()
        val blanketParam = rustTypeIdentToKotlin(firstSelfSeg.ident.toString())
        val implTypeParams = collectGenericTypeParamNames(item.generics)
        if (blanketParam !in implTypeParams) return emptyList()

        val requiredStdTraits = linkedSetOf<String>()
        var expectedItemSpec: Pair<String, String>? = null
        fun inspectBoundList(bounds: List<TypeParamBound>): Boolean {
            for (b in bounds) {
                if (b !is TypeParamBound.Trait || b.maybe != null) continue
                val lastSeg = b.path.segments.last() ?: continue
                val rawName = lastSeg.ident.toString()
                if (rawName in IGNORED_TRAIT_BOUNDS) continue
                val ktName = rustTypeIdentToKotlin(rawName)
                if (ktName !in RUST_STD_IMPLICIT_SUPERTRAITS) return false
                requiredStdTraits += ktName
                val angle = lastSeg.arguments as? PathArguments.AngleBracketed
                if (angle != null) {
                    for (ga in angle.args.toList()) {
                        if (ga is GenericArgument.AssocTypeArg && ga.assoc.ident.toString() == "Item") {
                            expectedItemSpec = renderType(ga.assoc.ty, null) to classifySynRefKind(ga.assoc.ty)
                        }
                    }
                }
            }
            return true
        }
        for (gp in item.generics.params.toList()) {
            if (gp is GenericParam.TypeParam && rustTypeIdentToKotlin(gp.ident.toString()) == blanketParam) {
                if (!inspectBoundList(gp.bounds.toList())) return emptyList()
            }
        }
        val implWherePredicates =
            item.generics.whereClause
                ?.predicates
                ?.toList()
                .orEmpty()
        for (pred in implWherePredicates) {
            if (pred is WherePredicate.TypePredicate && renderType(pred.boundedTy, null) == blanketParam) {
                if (!inspectBoundList(pred.bounds.toList())) return emptyList()
            }
        }
        val targetSpec = expectedItemSpec ?: return emptyList()
        if (requiredStdTraits.isEmpty()) return emptyList()

        val matched = mutableListOf<String>()
        for (structName in inspector.attachableTypeNames) {
            val wrapsTrait =
                inspector.structFieldSynTypesByType[structName]
                    ?.values
                    ?.any { renderType(it, null).substringBefore('<').trim() == blanketTraitKt } == true
            if (wrapsTrait) continue
            val candidateImpls = localTypeImpls[structName].orEmpty()
            val implementedTraits =
                candidateImpls
                    .mapNotNull { c ->
                        if (isDocOnlyCfgAttribute(c.attrs)) return@mapNotNull null
                        c.traitPath
                            ?.path
                            ?.segments
                            ?.last()
                            ?.ident
                            ?.toString()
                            ?.let(::rustTypeIdentToKotlin)
                    }.toSet()
            if (!implementedTraits.containsAll(requiredStdTraits)) continue
            var candidateIterSpec: Pair<String, String>? = null
            for (c in candidateImpls) {
                if (isDocOnlyCfgAttribute(c.attrs)) continue
                val rawT =
                    c.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString()
                if (rawT == "Iterator") {
                    for (sub in c.items) {
                        if (sub is ImplItem.AssocType && sub.ident.toString() == "Item") {
                            candidateIterSpec = renderType(sub.ty, null) to classifySynRefKind(sub.ty)
                        }
                    }
                }
            }
            if (candidateIterSpec == targetSpec) {
                matched += structName
            }
        }
        return matched
    }

    internal fun renderLeadingTriviaForSpan(span: Span, indent: String): String {
        val startByte = span.startOffset()
        if (!emittedTriviaStartBytes.add(startByte)) return ""
        val comments = trivia.leadingBefore(span)
        return renderInlineComments(comments, indent)
    }

    internal fun renderLeadingTrivia(node: ToTokens, indent: String): String {
        val headerSpans = collectHeaderSourceSpans(node)
        if (headerSpans.isEmpty()) return ""
        val sb = StringBuilder()
        val firstOffset = headerSpans.first().startOffset()
        val wrapperSpans = macroWrapperSpansByTargetOffset[firstOffset].orEmpty()
        for (sp in wrapperSpans) {
            sb.append(renderLeadingTriviaForSpan(sp, indent))
        }
        for (sp in headerSpans) {
            sb.append(renderLeadingTriviaForSpan(sp, indent))
        }
        return sb.toString()
    }

    internal fun containsWholeIdentifier(text: String, ident: String): Boolean {
        if (ident.isEmpty()) return false
        var start = 0
        while (true) {
            val idx = text.indexOf(ident, start)
            if (idx < 0) return false
            val beforeOk = idx == 0 || (!text[idx - 1].isLetterOrDigit() && text[idx - 1] != '_')
            val afterIdx = idx + ident.length
            val afterOk = afterIdx >= text.length || (!text[afterIdx].isLetterOrDigit() && text[afterIdx] != '_')
            if (beforeOk && afterOk) return true
            start = idx + 1
        }
    }

    internal fun renderItem(
        item: Item,
        indent: String,
        localTypeImpls: Map<String, List<Item.Impl>>,
        isLocalItem: Boolean = false,
        scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    ): String {
        if (itemHasDocOnlyCfg(item)) return ""
        return when (item) {
            is Item.Use -> ""
            is Item.Const -> renderItemConst(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Static -> renderItemStatic(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.ItemType -> renderItemType(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Fn -> renderItemFn(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Struct -> {
                val typeName = rustTypeIdentToKotlin(item.ident.toString())
                val attachedImpls = localTypeImpls[typeName].orEmpty()
                renderItemStruct(item, attachedImpls, indent, scopeSignatures, isLocalItem = isLocalItem)
            }
            is Item.Enum -> {
                val typeName = rustTypeIdentToKotlin(item.ident.toString())
                val attachedImpls = localTypeImpls[typeName].orEmpty()
                renderItemEnum(item, attachedImpls, indent, scopeSignatures, isLocalItem = isLocalItem)
            }
            is Item.Union -> {
                val typeName = rustTypeIdentToKotlin(item.ident.toString())
                val attachedImpls = localTypeImpls[typeName].orEmpty()
                renderItemUnion(item, attachedImpls, indent, scopeSignatures, isLocalItem = isLocalItem)
            }
            is Item.Trait -> renderItemTrait(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.TraitAlias -> renderItemTraitAlias(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Impl -> renderStandaloneItemImpl(item, indent, scopeSignatures, isLocalItem = isLocalItem)
            is Item.Mod -> renderItemMod(item, indent, scopeSignatures)
            is Item.Macro -> renderItemMacro(item, indent, isLocalItem = isLocalItem)
            is Item.ForeignMod -> renderItemForeignMod(item, indent)
            is Item.ExternCrate -> ""
            is Item.Verbatim -> ""
        }
    }
}

internal val PRIMITIVE_ARRAY_TYPES =
    setOf(
        "ByteArray",
        "UByteArray",
        "ShortArray",
        "UShortArray",
        "IntArray",
        "UIntArray",
        "LongArray",
        "ULongArray",
        "FloatArray",
        "DoubleArray",
        "BooleanArray",
    )
internal val IGNORED_USE_ROOTS = setOf("super", "self")
internal val STDLIB_CRATE_ROOTS = setOf("std", "core", "alloc")
internal val STDLIB_MACRO_OR_PRIMITIVE_MODULES = setOf("primitive", "concat", "stringify")
internal val FLAT_KOTLINMANIA_CRATES = setOf("procmacro2", "quote")
internal val STDLIB_TYPE_PATH_PREFIXES =
    setOf("std", "core", "alloc", "vec", "boxed", "collections", "rc", "sync", "slice", "option", "marker", "mem")
internal val IGNORED_PATH_PREFIXES = setOf("crate", "super", "self", "std", "core", "alloc")
internal val STDLIB_PRELUDE_REEXPORT_MODULES =
    setOf("__private", "export", "prelude", "v1", "rust_2015", "rust_2018", "rust_2021", "rust_2024")
internal val IGNORED_TRAIT_BOUNDS =
    setOf("Sized", "Send", "Sync", "Unpin", "UnwindSafe", "RefUnwindSafe", "TrivialDrop", "Hasher")
internal val RUST_STD_IMPLICIT_SUPERTRAITS =
    setOf(
        "Clone",
        "Copy",
        "Debug",
        "Default",
        "PartialEq",
        "Eq",
        "PartialOrd",
        "Ord",
        "Hash",
        "Drop",
        "Send",
        "Sync",
        "Unpin",
        "UnwindSafe",
        "RefUnwindSafe",
        "Iterator",
        "ExactSizeIterator",
        "DoubleEndedIterator",
        "FusedIterator",
        "FromIterator",
        "IntoIterator",
        "Extend",
        "Index",
        "IndexMut",
        "Deref",
        "DerefMut",
        "Display",
    )
internal val SYNTHESIZED_DEBUG_STRUCT_DECLARATION =
    """
    public class DebugStruct(
        private val out: StringBuilder,
        private val name: String,
    ) {
        private val fields: MutableList<String> = mutableListOf()

        public fun field(name: String, value: Any?): DebugStruct = apply {
            fields.add("${'$'}name: ${'$'}value")
        }

        public fun finish() {
            if (fields.isEmpty()) {
                out.append(name)
            } else {
                out.append(name).append(" { ").append(fields.joinToString(", ")).append(" }")
            }
        }

        public fun finishNonExhaustive() {
            fields.add("..")
            finish()
        }
    }
    """.trimIndent()

internal val SYNTHESIZED_DEBUG_TUPLE_DECLARATION =
    """
    public class DebugTuple(
        private val out: StringBuilder,
        private val name: String,
    ) {
        private val fields: MutableList<String> = mutableListOf()

        public fun field(value: Any?): DebugTuple = apply {
            fields.add(value.toString())
        }

        public fun finish() {
            if (fields.isEmpty()) {
                out.append(name)
            } else if (name.isEmpty()) {
                out.append('(').append(fields.joinToString(", ")).append(')')
            } else {
                out.append(name).append('(').append(fields.joinToString(", ")).append(')')
            }
        }
    }
    """.trimIndent()

internal val STDLIB_ERASED_OR_BUILTIN_IMPORTS =
    RUST_STD_IMPLICIT_SUPERTRAITS +
        setOf(
            "Hasher",
            "Formatter",
            "DebugStruct",
            "DebugTuple",
            "DebugList",
            "DebugSet",
            "DebugMap",
            "ManuallyDrop",
            "PhantomData",
            "ThreadId",
            "Rc",
            "Arc",
            "VecDeque",
            "Cell",
            "RefCell",
            "None",
            "Some",
            "Ok",
            "Err",
        )
internal val KNOWN_SELF_PARAMETERIZED_TRAITS = setOf("Parse", "ParseQuote")
internal val KOTLIN_COLLECTION_INTERFACES = setOf("List", "MutableList", "Map", "MutableMap", "Set", "MutableSet", "ArrayDeque")
internal val MUTATING_VEC_METHODS =
    setOf("push", "pop", "clear", "insert", "remove", "truncate", "retain", "extend", "append", "drain", "swap_remove")
internal val KNOWN_OPTION_RETURNING_METHODS =
    setOf(
        "take",
        "pop",
        "pop_front",
        "removeFirstOrNull",
        "removeLastOrNull",
        "next",
        "next_back",
        "first",
        "first_mut",
        "last",
        "last_mut",
        "get",
        "get_mut",
        "getOrNull",
        "punct",
        "punct_mut",
        "find",
        "strip_prefix",
        "strip_suffix",
        "checked_add",
        "checked_sub",
        "checked_mul",
    )
internal val KOTLIN_JVM_VALUE_TYPES =
    setOf(
        "Boolean",
        "Byte",
        "Short",
        "Int",
        "Long",
        "Float",
        "Double",
        "Char",
        "UByte",
        "UShort",
        "UInt",
        "ULong",
    )
internal val NUMERIC_CAST_TARGETS = KOTLIN_JVM_VALUE_TYPES - "Boolean"
internal val KOTLIN_STDLIB_SHADOWED_IMPORTS =
    setOf(
        "String",
        "Boolean",
        "Char",
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
        "Unit",
        "Nothing",
        "Any",
        "Result",
        "Iterator",
        "Iterable",
        "Comparable",
        "List",
        "MutableList",
        "Map",
        "MutableMap",
        "Set",
        "MutableSet",
        "Pair",
        "Triple",
        "Vec",
        "Box",
        "HashMap",
        "BTreeMap",
        "HashSet",
        "BTreeSet",
    )
internal val FORWARD_DESERIALIZE_KEYS =
    setOf(
        "bool",
        "i8",
        "i16",
        "i32",
        "i64",
        "i128",
        "u8",
        "u16",
        "u32",
        "u64",
        "u128",
        "f32",
        "f64",
        "char",
        "str",
        "string",
        "bytes",
        "byte_buf",
        "option",
        "unit",
        "unit_struct",
        "newtype_struct",
        "seq",
        "tuple",
        "tuple_struct",
        "map",
        "struct",
        "enum",
        "identifier",
        "ignored_any",
    )
internal val PRIMITIVE_TYPE_NAMES =
    setOf(
        "u8",
        "u16",
        "u32",
        "u64",
        "u128",
        "usize",
        "i8",
        "i16",
        "i32",
        "i64",
        "i128",
        "isize",
        "f32",
        "f64",
        "bool",
        "str",
        "char",
    )
internal val IGNORED_QUOTE_KEYWORDS =
    setOf(
        "use",
        "as",
        "crate",
        "const",
        "let",
        "mut",
        "match",
        "fn",
        "impl",
        "for",
        "in",
        "where",
        "pub",
        "return",
        "if",
        "else",
        "struct",
        "enum",
        "trait",
        "type",
        "static",
        "unsafe",
        "ref",
        "dyn",
        "loop",
        "while",
        "break",
        "continue",
        "move",
        "async",
        "await",
        "super",
        "default",
        "union",
        "try",
        "yield",
        "mod",
        "u8",
        "u16",
        "u32",
        "u64",
        "u128",
        "usize",
        "i8",
        "i16",
        "i32",
        "i64",
        "i128",
        "isize",
        "f32",
        "f64",
        "bool",
        "str",
        "char",
    )

internal val SYN_KEYWORD_TOKENS =
    setOf(
        "abstract",
        "as",
        "async",
        "auto",
        "await",
        "become",
        "box",
        "break",
        "const",
        "continue",
        "crate",
        "default",
        "do",
        "dyn",
        "else",
        "enum",
        "extern",
        "final",
        "fn",
        "for",
        "if",
        "impl",
        "in",
        "let",
        "loop",
        "macro",
        "match",
        "mod",
        "move",
        "mut",
        "override",
        "priv",
        "pub",
        "raw",
        "ref",
        "return",
        "safe",
        "static",
        "struct",
        "super",
        "trait",
        "try",
        "typeof",
        "union",
        "unsafe",
        "unsized",
        "use",
        "virtual",
        "where",
        "while",
        "yield",
    )

internal val SYN_PUNCT_TOKEN_NAMES =
    mapOf(
        "&" to "And",
        "&&" to "AndAnd",
        "&=" to "AndEq",
        "@" to "At",
        "^" to "Caret",
        "^=" to "CaretEq",
        ":" to "Colon",
        "," to "Comma",
        "$" to "Dollar",
        "." to "Dot",
        ".." to "DotDot",
        "..." to "DotDotDot",
        "..=" to "DotDotEq",
        "=" to "Eq",
        "==" to "EqEq",
        "=>" to "FatArrow",
        ">=" to "Ge",
        ">" to "Gt",
        "<-" to "LArrow",
        "<=" to "Le",
        "<" to "Lt",
        "-" to "Minus",
        "-=" to "MinusEq",
        "!=" to "Ne",
        "!" to "Not",
        "|" to "Or",
        "|=" to "OrEq",
        "||" to "OrOr",
        "::" to "PathSep",
        "%" to "Percent",
        "%=" to "PercentEq",
        "+" to "Plus",
        "+=" to "PlusEq",
        "#" to "Pound",
        "?" to "Question",
        "->" to "RArrow",
        ";" to "Semi",
        "<<" to "Shl",
        "<<=" to "ShlEq",
        ">>" to "Shr",
        ">>=" to "ShrEq",
        "/" to "Slash",
        "/=" to "SlashEq",
        "*" to "Star",
        "*=" to "StarEq",
        "~" to "Tilde",
    )

internal val SYN_KEYWORD_TOKEN_CLASS_NAMES =
    SYN_KEYWORD_TOKENS.mapTo(linkedSetOf()) { it[0].uppercaseChar() + it.substring(1) }

internal val SYN_PUNCT_TOKEN_CLASS_NAMES =
    SYN_PUNCT_TOKEN_NAMES.values.toSet()
