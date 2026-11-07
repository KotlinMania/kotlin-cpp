package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.StaticMutability
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UseTree
import io.github.kotlinmania.syn.Visibility
import io.github.kotlinmania.syn.span

internal data class UseLeaf(
    val segments: List<String>,
    val alias: String?,
    val isSelfImport: Boolean,
)

internal fun KotlinJ2KEmitter.isImportedTypeLeaf(lastSeg: String, boundRawAlias: String?, isSelfImport: Boolean): Boolean {
    if (isSelfImport || lastSeg == "*") return false
    val effectiveBound = (boundRawAlias ?: lastSeg).removePrefix("r#").trim()
    if (effectiveBound in inspector.referencedUnqualifiedTypeIdents) return true
    if (boundRawAlias != null && boundRawAlias.first().isUpperCase() && boundRawAlias.any(Char::isLowerCase)) {
        return true
    }
    return false
}

internal fun KotlinJ2KEmitter.isImportedScreamingConstLeaf(lastSeg: String, boundRawAlias: String?, isSelfImport: Boolean): Boolean {
    if (isSelfImport || lastSeg == "*") return false
    val cleaned = lastSeg.removePrefix("r#").trim()
    if (cleaned in setOf("FILE", "DIR")) return false
    val effectiveBound = (boundRawAlias ?: lastSeg).removePrefix("r#").trim()
    if (effectiveBound in inspector.referencedUnqualifiedTypeIdents ||
        effectiveBound in inspector.referencedPathPrefixTypeIdents
    ) {
        return false
    }
    val trimmedUnderscores = cleaned.trim('_')
    return trimmedUnderscores.length >= 2 && KotlinNamingPolicy.Default.isScreamingSnakeCase(trimmedUnderscores)
}

internal fun KotlinJ2KEmitter.registerUseItemInScope(item: Item.Use) {
    val leaves = mutableListOf<UseLeaf>()
    collectUseLeaves(item.tree, emptyList(), leaves)
    for (leaf in leaves) {
        if (leaf.segments.isEmpty()) continue
        val lastSeg = leaf.segments.last()
        if (lastSeg == "*") continue
        val boundRawName = leaf.alias ?: lastSeg
        val importedAsType = isImportedTypeLeaf(lastSeg, leaf.alias, leaf.isSelfImport)
        val importedAsConst = !importedAsType && isImportedScreamingConstLeaf(lastSeg, leaf.alias, leaf.isSelfImport)
        val importedPath = resolveKotlinImportPath(leaf.segments, leaf.alias, leaf.isSelfImport) ?: leaf.segments.joinToString(".")
        val isKnownSubpkgModule = isResolvedCrateSubpackageModule(importedPath, lastSeg)
        val isModuleImport =
            leaf.isSelfImport ||
                isKnownSubpkgModule ||
                (!importedAsType && !importedAsConst && boundRawName.first().isLowerCase() && boundRawName != "_")
        val kotlinName =
            when {
                isKnownSubpkgModule -> rustModuleIdentToKotlin(boundRawName)
                importedAsType -> rustTypeIdentToKotlin(boundRawName)
                importedAsConst -> rustConstIdentToKotlin(boundRawName)
                isModuleImport -> rustModuleIdentToKotlin(boundRawName)
                boundRawName.first().isUpperCase() -> rustTypeIdentToKotlin(boundRawName)
                else -> rustValueIdentToKotlin(boundRawName)
            }
        val normalizedImportedPath =
            if (isKnownSubpkgModule) {
                val pkgPrefix = importedPath.substringBeforeLast('.', missingDelimiterValue = "")
                val modLeaf = rustModuleIdentToKotlin(lastSeg)
                if (pkgPrefix.isEmpty()) modLeaf else "$pkgPrefix.$modLeaf"
            } else {
                importedPath
            }
        scopeBuilder.bindModuleImport(
            rawName = boundRawName,
            kotlinName = kotlinName,
            importedPath = normalizedImportedPath,
            isModuleImport = isModuleImport,
            vis = item.vis,
        )
    }
}

internal fun KotlinJ2KEmitter.currentNamespacePrefix(): String? =
    headerContext.projectNamespace
        ?.trim()
        ?.removeSuffix(".")
        ?.takeIf { it.isNotEmpty() }
        ?: headerContext.packageName
            ?.trim()
            ?.removeSuffix(".")
            ?.takeIf { it.isNotEmpty() && headerContext.portLintSourcePath?.contains('/') != true }
        ?: headerContext.projectGroup
            ?.trim()
            ?.removeSuffix(".")
            ?.takeIf { it.isNotEmpty() }

internal fun KotlinJ2KEmitter.currentSubpackageString(): String {
    val pkg = headerContext.packageName?.trim()?.removeSuffix(".") ?: return ""
    val ns = currentNamespacePrefix() ?: return ""
    return when {
        pkg == ns -> ""
        pkg.startsWith("$ns.") -> pkg.removePrefix("$ns.")
        else -> ""
    }
}

internal fun KotlinJ2KEmitter.resolveBestSubpackageForSymbol(ktSymbol: String): String? {
    val candidates = headerContext.crateSymbolSubpackages[ktSymbol] ?: return null
    if (candidates.isEmpty()) return null
    val cur = currentSubpackageString()
    if (cur in candidates) return cur
    if (candidates.size == 1) return candidates[0]

    val curSegs = if (cur.isEmpty()) emptyList() else cur.split('.')

    fun archAndOsBias(candSegs: List<String>): Int {
        var bias = 0
        for (s in candSegs) {
            when (s) {
                "b64", "aarch64", "x8664", "notx32" -> bias += 150
                "apple", "bsd", "linuxlike", "linux", "gnu", "unix" -> bias += 60
                "b32", "x32", "x86", "arm", "mips", "mips32", "powerpc", "sparc", "riscv32", "i386", "uclibc" -> bias -= 300
            }
        }
        return bias
    }

    fun score(cand: String): Int {
        val candSegs = if (cand.isEmpty()) emptyList() else cand.split('.')
        val isAncestor = cand.isEmpty() || cur.startsWith("$cand.")
        if (isAncestor) {
            return 20_000 + candSegs.size * 200
        }
        val isDescendant = cur.isEmpty() || cand.startsWith("$cur.")
        if (isDescendant) {
            val depthDelta = candSegs.size - curSegs.size
            return 10_000 - depthDelta * 40 + archAndOsBias(candSegs)
        }
        var common = 0
        while (common < curSegs.size && common < candSegs.size && curSegs[common] == candSegs[common]) {
            common++
        }
        return common * 300 - candSegs.size * 15 + archAndOsBias(candSegs)
    }

    return candidates.maxByOrNull(::score)
}

internal fun KotlinJ2KEmitter.isResolvedCrateSubpackageModule(resolvedImportPath: String, rawLeafIdent: String): Boolean {
    if (headerContext.crateSubpackageRawLeafNames.isEmpty()) return false
    val nsPrefix = currentNamespacePrefix() ?: return false
    if (!resolvedImportPath.startsWith("$nsPrefix.")) return false
    val afterNs = resolvedImportPath.removePrefix("$nsPrefix.")
    val parentSubpkg = afterNs.substringBeforeLast('.', missingDelimiterValue = "")
    val leafMod = rustModuleIdentToKotlin(rawLeafIdent.replace('-', '_'))
    val candidateSubpkg = if (parentSubpkg.isEmpty()) leafMod else "$parentSubpkg.$leafMod"
    return headerContext.crateSubpackageRawLeafNames[candidateSubpkg] == rawLeafIdent.removePrefix("r#")
}

internal fun KotlinJ2KEmitter.scopedAbiKeysForRenderedType(renderedType: String): List<String> {
    val clean = renderedType.trim().removeSuffix("?")
    if (clean.isEmpty()) return emptyList()
    val keys = mutableListOf<String>()
    val nsPrefix = currentNamespacePrefix()
    if ('.' in clean) {
        if (nsPrefix != null && clean.startsWith("$nsPrefix.")) {
            keys += clean.removePrefix("$nsPrefix.")
        }
        keys += clean
        keys += clean.substringAfterLast('.')
        return keys.distinct()
    }
    val importedPkg = importedSimpleNameToPkg[clean]
    if (importedPkg != null && nsPrefix != null) {
        if (importedPkg == nsPrefix) {
            keys += clean
        } else if (importedPkg.startsWith("$nsPrefix.")) {
            val sub = importedPkg.removePrefix("$nsPrefix.")
            keys += "$sub.$clean"
        }
    } else {
        val curSub = currentSubpackageString()
        if (curSub.isNotEmpty()) {
            keys += "$curSub.$clean"
        }
    }
    keys += clean
    return keys.distinct()
}

internal fun KotlinJ2KEmitter.bindOrQualifyResolvedCrossSymbol(resolvedFullImport: String): String {
    val resolvedPkg = resolvedFullImport.substringBeforeLast('.', missingDelimiterValue = "")
    val simpleName = resolvedFullImport.substringAfterLast('.')
    val collidedPkg = importedSimpleNameToPkg[simpleName]
    return when {
        simpleName in inspector.attachableTypeNames || simpleName in inspector.declaredTypeNames ->
            if (resolvedPkg.isNotEmpty() && resolvedPkg != headerContext.packageName) resolvedFullImport else simpleName
        collidedPkg != null && collidedPkg != resolvedPkg -> resolvedFullImport
        resolvedPkg.isNotEmpty() && resolvedPkg != headerContext.packageName -> {
            importedSimpleNameToPkg[simpleName] = resolvedPkg
            extraTypePathImports += "import $resolvedFullImport"
            simpleName
        }
        else -> simpleName
    }
}

internal fun KotlinJ2KEmitter.resolveCrossModulePathTarget(
    rawSegStrings: List<String>,
    inTypePosition: Boolean = false,
): Pair<String, List<String>>? {
    if (rawSegStrings.size < 2) return null
    val firstRaw = rawSegStrings.first().removePrefix("r#")
    if (firstRaw in inspector.localInlineModuleNames) return null
    if (firstRaw == "std" || firstRaw == "alloc" || firstRaw == "Self" || firstRaw in PRIMITIVE_TYPE_NAMES) return null
    if (rawSegStrings.dropLast(1).any { it.removePrefix("r#") in STDLIB_PRELUDE_REEXPORT_MODULES }) {
        return null
    }
    if (firstRaw == "core") {
        val isCrateCoreModule =
            headerContext.crateSubpackageRawLeafNames["core"] == "core" ||
                "core" in inspector.localExternalModuleNames
        val secondRaw = rawSegStrings.getOrNull(1)?.removePrefix("r#")
        val stdlibCoreSubmodules =
            setOf(
                "ffi",
                "mem",
                "ptr",
                "option",
                "result",
                "fmt",
                "marker",
                "cmp",
                "ops",
                "slice",
                "str",
                "iter",
                "convert",
                "default",
                "hash",
                "clone",
                "any",
                "arch",
                "cell",
                "hint",
                "num",
                "panic",
                "pin",
                "sync",
                "task",
                "time",
            )
        if (!isCrateCoreModule || secondRaw in STDLIB_MACRO_OR_PRIMITIVE_MODULES || secondRaw in stdlibCoreSubmodules) {
            return null
        }
    }

    val nsPrefix = currentNamespacePrefix()
    if (scopeBuilder.isImportedModuleInScope(firstRaw)) {
        val modBinding = scopeBuilder.resolveModuleBinding(firstRaw) ?: return null
        val importedPath = modBinding.importedPath ?: return null
        if (nsPrefix == null || !importedPath.startsWith("$nsPrefix.")) return null
        val boundSubpkg = importedPath.removePrefix("$nsPrefix.")
        if (boundSubpkg !in headerContext.crateSubpackageRawLeafNames) return null
        val rest = rawSegStrings.drop(1)
        var modSubpkg = boundSubpkg
        var idx = 0
        while (idx < rest.lastIndex) {
            val segClean = rest[idx].removePrefix("r#")
            val nextSub = "$modSubpkg.${rustModuleIdentToKotlin(segClean.replace('-', '_'))}"
            if (headerContext.crateSubpackageRawLeafNames[nextSub] == segClean) {
                modSubpkg = nextSub
                idx++
            } else {
                break
            }
        }
        val symRaw = rest[idx]
        val trailing = rest.drop(idx + 1)
        val symKt =
            when {
                inTypePosition || trailing.isNotEmpty() -> rustTypeIdentToKotlin(symRaw)
                isImportedScreamingConstLeaf(symRaw, null, false) -> rustConstIdentToKotlin(symRaw)
                symRaw.first().isUpperCase() -> rustTypeIdentToKotlin(symRaw)
                else -> rustValueIdentToKotlin(symRaw)
            }
        return "$nsPrefix.$modSubpkg.$symKt" to trailing
    }

    val isSelfOrRelNav =
        firstRaw == "crate" ||
            firstRaw == "super" ||
            firstRaw == "self" ||
            firstRaw in headerContext.selfCrateAliases ||
            firstRaw in inspector.localExternalModuleNames
    if (isSelfOrRelNav) {
        val navCount =
            rawSegStrings
                .takeWhile {
                    val c = it.removePrefix("r#")
                    c == "crate" || c == "super" || c == "self" || c in headerContext.selfCrateAliases
                }.size
                .coerceAtLeast(1)
        val afterNav = rawSegStrings.drop(navCount)
        if (afterNav.isEmpty()) return null
        if (afterNav.size == 1) {
            val direct = resolveKotlinImportPath(rawSegStrings, forceTypeLeaf = inTypePosition) ?: return null
            return direct to emptyList()
        }
        val hasCrateInventory =
            headerContext.crateSubpackageRawLeafNames.isNotEmpty() ||
                headerContext.crateSymbolSubpackages.isNotEmpty()
        // Try longest module prefix first, then peel 1 trailing member (e.g. `core::GUID::from_u128`).
        for (trailingCount in 0..1) {
            if (afterNav.size <= trailingCount) continue
            val candidateSegs = rawSegStrings.dropLast(trailingCount)
            val midSegs = afterNav.dropLast(1 + trailingCount)
            val resolved =
                resolveKotlinImportPath(
                    candidateSegs,
                    forceTypeLeaf = inTypePosition || trailingCount > 0,
                ) ?: continue
            if (midSegs.isEmpty()) {
                return resolved to rawSegStrings.takeLast(trailingCount)
            }
            if (nsPrefix != null && resolved.startsWith("$nsPrefix.") && hasCrateInventory) {
                val afterNs = resolved.removePrefix("$nsPrefix.")
                val candSubpkg = afterNs.substringBeforeLast('.', missingDelimiterValue = "")
                val symSimple = afterNs.substringAfterLast('.')
                val expectedRawLeaf = midSegs.last().removePrefix("r#")
                if (candSubpkg.isEmpty() ||
                    headerContext.crateSubpackageRawLeafNames[candSubpkg] == expectedRawLeaf ||
                    headerContext.crateSymbolSubpackages[symSimple]?.contains(candSubpkg) == true
                ) {
                    return resolved to rawSegStrings.takeLast(trailingCount)
                }
                if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
                    val bestSub = resolveBestSubpackageForSymbol(symSimple)
                    if (bestSub != null) {
                        val relocated = if (bestSub.isEmpty()) "$nsPrefix.$symSimple" else "$nsPrefix.$bestSub.$symSimple"
                        return relocated to rawSegStrings.takeLast(trailingCount)
                    }
                }
            } else if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
                return resolved to rawSegStrings.takeLast(trailingCount)
            }
        }
        return null
    }

    if (firstRaw.firstOrNull()?.isLowerCase() == true) {
        val firstAsType = rustTypeIdentToKotlin(firstRaw)
        if (firstAsType in inspector.declaredTypeNames ||
            firstRaw in inspector.declaredTypeNames ||
            firstAsType in activeTypeParamNames ||
            firstAsType in headerContext.crateSymbolSubpackages ||
            firstRaw in headerContext.crateSymbolSubpackages
        ) {
            return null
        }
        if (!inTypePosition &&
            rawSegStrings.size == 2 &&
            rustModuleIdentToKotlin(firstRaw) !in headerContext.crateNamespaces
        ) {
            return null
        }
        val midSegs = rawSegStrings.drop(1).dropLast(1)
        if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
            val resolved = resolveKotlinImportPath(rawSegStrings, forceTypeLeaf = inTypePosition) ?: return null
            return resolved to emptyList()
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.resolveKotlinImportPath(
    segs: List<String>,
    boundRawAlias: String? = null,
    isSelfImport: Boolean = false,
    forceTypeLeaf: Boolean = false,
): String? {
    if (segs.isEmpty()) return null
    val root = segs.first()
    if (root == "self") {
        val afterSelf = segs.drop(1)
        if (afterSelf.isEmpty()) return null
        val rawCurPkg = headerContext.packageName?.trim()?.removeSuffix(".")
        val curPkg = rawCurPkg?.takeIf { it.isNotEmpty() } ?: return null
        if (afterSelf.last() == "*" && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            return null
        }
        val mappedTail =
            afterSelf.mapIndexed { idx, s ->
                if (idx == afterSelf.lastIndex) {
                    when {
                        s == "*" -> "*"
                        forceTypeLeaf || isImportedTypeLeaf(s, boundRawAlias, isSelfImport) -> rustTypeIdentToKotlin(s)
                        isImportedScreamingConstLeaf(s, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(s)
                        s.first().isUpperCase() -> rustTypeIdentToKotlin(s)
                        else -> rustValueIdentToKotlin(s)
                    }
                } else {
                    rustModuleIdentToKotlin(s)
                }
            }
        return "$curPkg.${mappedTail.joinToString(".")}"
    }
    if (root == "super") {
        var superCount = 0
        while (superCount < segs.size && segs[superCount] == "super") {
            superCount++
        }
        val rem = segs.drop(superCount)
        if (rem.isEmpty()) return null
        if (rem.last() == "*" && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            return null
        }
        val nsPrefix = currentNamespacePrefix() ?: return null
        val curSubpkg = currentSubpackageString()
        val curSubSegs = if (curSubpkg.isEmpty()) emptyList() else curSubpkg.split('.')
        var baseSegs =
            if (superCount >= curSubSegs.size) {
                emptyList()
            } else {
                curSubSegs.dropLast(superCount)
            }
        if (baseSegs.size == 1 && headerContext.includedSubdirModuleMap.isNotEmpty()) {
            val includedSubdirs = headerContext.includedSubdirModuleMap.values
            val includedRoots = includedSubdirs.map(::rustModuleIdentToKotlin).toSet()
            if (baseSegs[0] in includedRoots) {
                val expectedRoot = headerContext.includedSubdirModuleMap[rem.first()]?.let(::rustModuleIdentToKotlin)
                if (expectedRoot != baseSegs[0]) {
                    baseSegs = emptyList()
                }
            }
        }
        if (baseSegs.isEmpty() && headerContext.includedSubdirModuleMap.isNotEmpty()) {
            headerContext.includedSubdirModuleMap[rem.first()]?.let { subRoot ->
                baseSegs = listOf(rustModuleIdentToKotlin(subRoot))
            }
        }
        val mappedRem =
            rem.mapIndexed { idx, s ->
                if (idx == rem.lastIndex) {
                    when {
                        s == "*" -> "*"
                        forceTypeLeaf || isImportedTypeLeaf(s, boundRawAlias, isSelfImport) -> rustTypeIdentToKotlin(s)
                        isImportedScreamingConstLeaf(s, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(s)
                        s.first().isUpperCase() -> rustTypeIdentToKotlin(s)
                        else -> rustValueIdentToKotlin(s)
                    }
                } else {
                    rustModuleIdentToKotlin(s)
                }
            }
        if (rem.size == 1 && mappedRem[0] != "*" && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val targetPrefix = baseSegs.joinToString(".")
            val candidates = headerContext.crateSymbolSubpackages[mappedRem[0]]
            if (candidates != null && targetPrefix !in candidates) {
                val best = resolveBestSubpackageForSymbol(mappedRem[0])
                if (best != null) {
                    return if (best.isEmpty()) "$nsPrefix.${mappedRem[0]}" else "$nsPrefix.$best.${mappedRem[0]}"
                }
            }
        }
        val combined = baseSegs + mappedRem
        return "$nsPrefix.${combined.joinToString(".")}"
    }
    if (root in inspector.localExternalModuleNames) {
        return resolveKotlinImportPath(listOf("self") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
    }
    if (root in IGNORED_USE_ROOTS) return null
    if (root in STDLIB_CRATE_ROOTS && segs.getOrNull(1) in STDLIB_MACRO_OR_PRIMITIVE_MODULES) {
        return null
    }
    val lastRaw = segs.last()
    if (!isSelfImport && lastRaw.removePrefix("r#") == "c_void") {
        return null
    }
    val mapped =
        segs.mapIndexed { idx, s ->
            if (idx == segs.lastIndex) {
                when {
                    s == "*" -> "*"
                    forceTypeLeaf || isImportedTypeLeaf(s, boundRawAlias, isSelfImport) -> rustTypeIdentToKotlin(s)
                    isImportedScreamingConstLeaf(s, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(s)
                    s.first().isUpperCase() -> rustTypeIdentToKotlin(s)
                    else -> rustValueIdentToKotlin(s)
                }
            } else {
                rustModuleIdentToKotlin(s)
            }
        }
    if (mapped.size < 2) return null
    return if (root == "crate" || root in headerContext.selfCrateAliases) {
        val nsPrefix = currentNamespacePrefix()
        val afterRoot = segs.drop(1)
        if (afterRoot.last() == "*" && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val isRootOrPreludeGlob =
                afterRoot.size == 1 ||
                    (afterRoot.size == 2 && afterRoot[0] == "prelude")
            return if (isRootOrPreludeGlob && nsPrefix != null && headerContext.packageName != nsPrefix) {
                "$nsPrefix.*"
            } else {
                null
            }
        }
        if (afterRoot.size == 1 && mapped.last() != "*" && nsPrefix != null && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val bestSub = resolveBestSubpackageForSymbol(mapped.last())
            if (bestSub != null) {
                return if (bestSub.isEmpty()) "$nsPrefix.${mapped.last()}" else "$nsPrefix.$bestSub.${mapped.last()}"
            }
        }
        val includedPrefix =
            headerContext.includedSubdirModuleMap[afterRoot.first()]
                ?.let(::rustModuleIdentToKotlin)
                ?.takeIf { it.isNotEmpty() }
        val tail =
            when {
                segs.size >= 3 && segs[1] in headerContext.crateFlatFileModules -> mapped.last()
                includedPrefix != null -> "$includedPrefix.${mapped.drop(1).joinToString(".")}"
                else -> mapped.drop(1).joinToString(".")
            }
        if (nsPrefix != null) "$nsPrefix.$tail" else tail
    } else {
        val rawCrateKey = mapped.first()
        val isSelfProcMacro2 =
            headerContext.projectNamespace == "io.github.kotlinmania.procmacro2" ||
                headerContext.packageName == "io.github.kotlinmania.procmacro2" ||
                headerContext.packageName?.startsWith("io.github.kotlinmania.procmacro2.") == true
        val crateKey =
            if (root == "proc_macro" &&
                !isSelfProcMacro2 &&
                headerContext.crateNamespaces["procmacro"] == null &&
                headerContext.projectGroup == "io.github.kotlinmania"
            ) {
                "procmacro2"
            } else {
                rawCrateKey
            }
        val customCrateNs =
            headerContext.crateNamespaces[crateKey]
                ?.trim()
                ?.removeSuffix(".")
                ?.takeIf { it.isNotEmpty() }
        val flattenSubmodules =
            headerContext.projectGroup == "io.github.kotlinmania" &&
                crateKey in FLAT_KOTLINMANIA_CRATES &&
                mapped.size > 2
        val effectiveTail = if (flattenSubmodules) mapped.last() else mapped.drop(1).joinToString(".")
        if (customCrateNs != null) {
            "$customCrateNs.$effectiveTail"
        } else {
            val joined = "$crateKey.$effectiveTail"
            val groupPrefix =
                headerContext.projectGroup
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() }
            if (groupPrefix != null && root in STDLIB_CRATE_ROOTS) {
                null
            } else if (groupPrefix != null && !joined.startsWith("$groupPrefix.")) {
                "$groupPrefix.$joined"
            } else {
                joined
            }
        }
    }
}

internal fun KotlinJ2KEmitter.renderItemUse(
    item: Item.Use,
    indent: String,
    bodyTextForPubUseFilter: String? = null,
): String {
    val leaves = mutableListOf<UseLeaf>()
    collectUseLeaves(item.tree, emptyList(), leaves)
    val lines = mutableListOf<String>()
    for (leaf in leaves) {
        val root = leaf.segments.firstOrNull()
        if (leaf.isSelfImport && root in STDLIB_CRATE_ROOTS) {
            continue
        }
        if (root in STDLIB_CRATE_ROOTS && leaf.segments.size == 2 && leaf.segments[1].firstOrNull()?.isLowerCase() == true) {
            continue
        }
        if (root == "crate" && leaf.segments.getOrNull(1) == "drops" && leaf.segments.lastOrNull() in setOf("NoDrop", "TrivialDrop")) {
            continue
        }
        val fullImport = resolveKotlinImportPath(leaf.segments, leaf.alias, leaf.isSelfImport) ?: continue
        if (isResolvedCrateSubpackageModule(fullImport, leaf.segments.last())) {
            continue
        }
        val finalSimpleName = fullImport.substringAfterLast('.')
        if (leaf.alias == null &&
            root in STDLIB_CRATE_ROOTS &&
            (finalSimpleName in KOTLIN_STDLIB_SHADOWED_IMPORTS || finalSimpleName in STDLIB_ERASED_OR_BUILTIN_IMPORTS)
        ) {
            continue
        }
        val importPkg = fullImport.substringBeforeLast('.', missingDelimiterValue = "")
        if (leaf.alias == null && headerContext.packageName != null && importPkg == headerContext.packageName) {
            continue
        }
        val lastSeg = leaf.segments.last()
        val aliasIdent =
            leaf.alias?.let { rawAlias ->
                when {
                    isImportedTypeLeaf(lastSeg, rawAlias, leaf.isSelfImport) -> rustTypeIdentToKotlin(rawAlias)
                    isImportedScreamingConstLeaf(lastSeg, rawAlias, leaf.isSelfImport) -> rustConstIdentToKotlin(rawAlias)
                    rawAlias.first().isUpperCase() -> rustTypeIdentToKotlin(rawAlias)
                    leaf.isSelfImport || lastSeg.first().isLowerCase() -> rustModuleIdentToKotlin(rawAlias)
                    else -> rustValueIdentToKotlin(rawAlias)
                }
            }
        val localBoundIdent = aliasIdent ?: finalSimpleName
        if (localBoundIdent in STDLIB_ERASED_OR_BUILTIN_IMPORTS && root in STDLIB_CRATE_ROOTS) {
            continue
        }
        if (item.vis is Visibility.Public && localBoundIdent == "*") {
            continue
        }
        val shouldFilterUnused =
            item.vis is Visibility.Public ||
                TranslationSession.sharedModuleFile != null ||
                headerContext.isModuleRootSource ||
                localBoundIdent in witnessedSuppressedTraitNames
        if (shouldFilterUnused &&
            bodyTextForPubUseFilter != null &&
            localBoundIdent != "*" &&
            !containsWholeIdentifier(bodyTextForPubUseFilter, localBoundIdent)
        ) {
            continue
        }
        if (localBoundIdent != "*" && importPkg.isNotEmpty() && localBoundIdent !in importedSimpleNameToPkg) {
            importedSimpleNameToPkg[localBoundIdent] = importPkg
        }
        val aliasSuffix = aliasIdent?.takeIf { it != finalSimpleName }?.let { " as $it" }.orEmpty()
        lines += "${indent}import $fullImport$aliasSuffix"
    }
    return lines.distinct().joinToString("\n")
}

internal fun KotlinJ2KEmitter.collectUseLeaves(
    tree: UseTree,
    prefix: List<String>,
    out: MutableList<UseLeaf>,
) {
    when (tree) {
        is UseTree.Path -> {
            val seg = tree.ident.toString()
            val child = tree.tree
            if (child != null) {
                collectUseLeaves(child, prefix + seg, out)
            }
        }
        is UseTree.Name -> {
            val seg = tree.ident.toString()
            val alias =
                tree.rename
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
                    ?.takeIf { it != "_" }
            if (seg != "self") {
                out.add(UseLeaf(segments = prefix + seg, alias = alias, isSelfImport = false))
            } else if (prefix.isNotEmpty()) {
                out.add(UseLeaf(segments = prefix, alias = alias, isSelfImport = true))
            }
        }
        is UseTree.Glob -> {
            out.add(UseLeaf(segments = prefix + "*", alias = null, isSelfImport = false))
        }
        is UseTree.Group -> {
            for (sub in tree.items.toList()) {
                collectUseLeaves(sub, prefix, out)
            }
        }
    }
}

internal fun KotlinJ2KEmitter.collectInlineModUses(modItem: Item.Mod, out: MutableList<Item.Use>) {
    val content = modItem.content as? ModContent.Inline ?: return
    for (sub in content.items) {
        if (itemHasDocOnlyCfg(sub)) continue
        when (sub) {
            is Item.Use -> out += sub
            is Item.Mod -> collectInlineModUses(sub, out)
            else -> {}
        }
    }
}

internal fun KotlinJ2KEmitter.renderItemMod(
    item: Item.Mod,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
): String {
    val content = item.content as? ModContent.Inline ?: return ""
    scopeBuilder.enterModuleSubscope()
    return try {
        val modAttachableNames =
            content.items
                .mapNotNull { sub ->
                    if (itemHasDocOnlyCfg(sub)) return@mapNotNull null
                    when (sub) {
                        is Item.Struct -> rustTypeIdentToKotlin(sub.ident.toString())
                        is Item.Enum -> rustTypeIdentToKotlin(sub.ident.toString())
                        is Item.Union -> rustTypeIdentToKotlin(sub.ident.toString())
                        else -> null
                    }
                }.toSet()
        val modLocalTypeImpls = linkedMapOf<String, MutableList<Item.Impl>>()
        val standaloneModItems = mutableListOf<Item>()
        for (sub in content.items) {
            if (itemHasDocOnlyCfg(sub)) continue
            if (sub is Item.Use) {
                registerUseItemInScope(sub)
                continue
            }
            if (sub is Item.Impl) {
                val ownerType = extractDirectOwnerTypeName(sub.selfType, sub.generics)
                if (ownerType != null && ownerType in modAttachableNames) {
                    modLocalTypeImpls.getOrPut(ownerType) { mutableListOf() }.add(sub)
                    continue
                }
            }
            standaloneModItems += sub
        }
        val subItems =
            standaloneModItems.mapNotNull { sub ->
                val r = renderItem(sub, indent, modLocalTypeImpls, scopeSignatures = scopeSignatures)
                r.takeIf { it.isNotBlank() }
            }
        subItems.joinToString("\n\n")
    } finally {
        scopeBuilder.exitModuleSubscope()
    }
}

internal fun KotlinJ2KEmitter.renderItemMacro(
    item: Item.Macro,
    indent: String,
    isLocalItem: Boolean = false,
): String {
    if (item.mac.path.isIdent("macro_rules")) {
        return ""
    }
    val lastMacroIdent =
        item.mac.path.segments
            .last()
            ?.ident
            ?.toString()
    if (lastMacroIdent == "include") {
        return ""
    }
    if (isWindowsLinkMacroPath(item.mac.path)) {
        return renderWindowsLinkMacro(item, indent)
    }
    if (!isLocalItem) {
        ffiCollector.recordTopLevelInlineMacro(item.mac)
        return ""
    }
    val rendered = renderMacroInvocationAsStmtOrExpr(item.mac, indent, selfTypeName = null)
    return if (rendered.isBlank()) "" else "$indent$rendered"
}

internal fun KotlinJ2KEmitter.isWindowsLinkMacroPath(path: Path): Boolean {
    val segs = path.segments.toList().map { it.ident.toString() }
    if (segs.size == 2 && (segs[0] == "windows_link" || segs[0] == "windows_targets") && segs[1] == "link") {
        return true
    }
    return headerContext.win32DynamicFfi && segs.lastOrNull() == "link"
}

internal fun KotlinJ2KEmitter.renderWindowsLinkMacro(item: Item.Macro, indent: String): String {
    val macTokens = item.mac.tokens
    val trees = macTokens.clone().toList()
    val fnIdx =
        trees.indexOfFirst { tt ->
            tt is TokenTree.Ident && tt.value.toString() == "fn"
        }
    if (fnIdx < 0) return ""
    val leadingStrings = mutableListOf<String>()
    for (i in 0 until fnIdx) {
        val tt = trees[i]
        if (tt is TokenTree.Literal) {
            val lit = Lit.new(tt.value)
            if (lit is Lit.Str) {
                leadingStrings += lit.value.value()
            }
        }
    }
    val dllName = leadingStrings.getOrNull(0).orEmpty()
    val customSymbol = leadingStrings.getOrNull(2)
    val fnTokens = trees.subList(fnIdx, trees.size)
    val pubTokens = TokenStream.fromString("pub").getOrThrow().toList()
    val hasTrailingSemi = (fnTokens.lastOrNull() as? TokenTree.Punct)?.value?.asChar() == ';'
    val semiTokens = if (hasTrailingSemi) emptyList() else TokenStream.fromString(";").getOrThrow().toList()
    val braceBody = TokenTree.Group(Group(Delimiter.Brace, TokenStream.fromTokenTrees(pubTokens + fnTokens + semiTokens)))
    val externPrefix = TokenStream.fromString("extern \"C\"").getOrThrow().toList()
    val wrappedStream = TokenStream.fromTokenTrees(externPrefix + braceBody)
    val parsedFile =
        io.github.kotlinmania.syn
            .parse2(io.github.kotlinmania.syn.FileParse::parse, wrappedStream)
            .getOrNull() ?: return ""
    val foreignMod = parsedFile.items.firstOrNull() as? Item.ForeignMod ?: return ""
    val foreignFn = foreignMod.items.firstOrNull() as? ForeignItem.Fn ?: return ""

    val foreignIdent = foreignFn.sig.ident.toString()
    val rawFnName = foreignIdent.removePrefix("r#")
    val fnName = rustFunctionIdentToKotlin(rawFnName)
    val exportedSymbol = customSymbol ?: rawFnName
    val trivia = renderLeadingTrivia(item, indent)
    val doc = renderKDocFromAttributes(item.attrs + foreignFn.attrs, indent)

    if (!headerContext.win32DynamicFfi) {
        val interopParams = mutableListOf<InteropParamSpec>()
        scopeBuilder.enterDef()
        val (sig, retRendered) =
            try {
                renderForeignFunctionExpectSignature(
                    sig = foreignFn.sig,
                    visPrefix = "public ",
                    fnName = fnName,
                    scopeSignatures = topLevelSignatureCounts,
                    interopParamsOut = interopParams,
                )
            } finally {
                scopeBuilder.exitDef()
            } ?: return ""
        val retSynType = extractReturnSynType(foreignFn.sig.output)
        ffiCollector.recordForeignFunction(
            visibilityPrefix = "public ",
            kotlinName = fnName,
            rawRustName = exportedSymbol,
            attrs = item.attrs + foreignFn.attrs,
            params = interopParams,
            kotlinReturnType = retRendered,
            returnSynType = retSynType,
            isVariadic = foreignFn.sig.variadic != null,
        )
        return "$trivia$doc$indent$sig"
    }

    val prevExternAbi = inExternAbiSignature
    inExternAbiSignature = true
    scopeBuilder.enterDef()
    return try {
        val paramDecls = mutableListOf<String>()
        val paramTypeKeys = mutableListOf<String>()
        val paramNameAndTypes = mutableListOf<Pair<String, String>>()
        var paramIdx = 0
        for (arg in foreignFn.sig.inputs.toList()) {
            if (arg is FnArg.Typed) {
                val rawParam = extractRawParamIdent(arg.patType.pat)
                val pName =
                    if (rawParam != null && rawParam != "_") {
                        scopeBuilder
                            .bindLocal(
                                rawName = rawParam,
                                baseKotlinName = rustValueIdentToKotlin(rawParam),
                                span = arg.patType.pat.span(),
                                assignCount = AssignCount.AtMostOnce,
                            ).kotlinName
                    } else {
                        extractParamName(arg.patType.pat, paramIdx)
                    }
                val prevHint = currentSamNameHint
                currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}${pName.replaceFirstChar { it.uppercase() }}Callback"
                val pType =
                    try {
                        renderType(arg.patType.ty, null)
                    } finally {
                        currentSamNameHint = prevHint
                    }
                paramDecls += "$pName: $pType"
                paramTypeKeys += eraseTypeForSignatureKey(pType)
                paramNameAndTypes += (pName to pType)
                paramIdx++
            }
        }
        val isVariadic = foreignFn.sig.variadic != null
        if (isVariadic) {
            paramDecls += "vararg variadicArgs: Long"
            paramTypeKeys += "Long..."
        }
        val sigKey = "$fnName(${paramTypeKeys.joinToString(",")})"
        val seen = topLevelSignatureCounts[sigKey] ?: 0
        topLevelSignatureCounts[sigKey] = seen + 1
        if (seen > 0) return ""

        val retSynType = extractReturnSynType(foreignFn.sig.output)
        val prevHint = currentSamNameHint
        currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}ResultCallback"
        val renderedReturn =
            try {
                retSynType?.let { renderType(it, null) } ?: "Unit"
            } finally {
                currentSamNameHint = prevHint
            }
        val retSuffix = if (renderedReturn == "Unit") "" else ": $renderedReturn"

        var fpMask = 0
        val slotExprs =
            paramNameAndTypes.mapIndexed { idx, (pName, pType) ->
                val pKind = resolveNumericKind(pType)
                val packTemplate =
                    if (pKind == null) {
                        scopedAbiKeysForRenderedType(pType)
                            .firstNotNullOfOrNull { headerContext.crateValueParamPackTemplates[it] }
                    } else {
                        null
                    }
                when {
                    pType == "Boolean" -> "if ($pName) 1L else 0L"
                    pKind == NumericKind.I64 -> pName
                    pKind == NumericKind.F32 -> {
                        if (idx < 8) fpMask = fpMask or (1 shl (idx * 2))
                        "($pName.toRawBits().toLong() and 0xFFFF_FFFFL)"
                    }
                    pKind == NumericKind.F64 -> {
                        if (idx < 8) fpMask = fpMask or (2 shl (idx * 2))
                        "$pName.toRawBits()"
                    }
                    pKind == NumericKind.I128 || pKind == NumericKind.U128 ->
                        "run { val buf = $pName.toLeBytes().asByteArray(); val ptr = Win32DynamicFfi.allocZeroed(16); Win32DynamicFfi.writeBytes(ptr, buf); ptr }"
                    pKind != null -> "$pName.toLong()"
                    packTemplate != null ->
                        packTemplate
                            .replace("__SELF__", pName)
                            .replace("__FFI__", "Win32DynamicFfi")
                    else -> "Win32DynamicFfi.objectSlot($pName)"
                }
            }
        val argsArrayExpr =
            if (isVariadic) {
                if (slotExprs.isEmpty()) "variadicArgs" else "longArrayOf(${slotExprs.joinToString(", ")}) + variadicArgs"
            } else {
                "longArrayOf(${slotExprs.joinToString(", ")})"
            }

        usesWin32DynamicFfi = true
        val dllLit = renderKotlinDoubleQuotedString(dllName)
        val symLit = renderKotlinDoubleQuotedString(exportedSymbol)
        val intCall =
            if (fpMask != 0) {
                "Win32DynamicFfi.invokeFp($dllLit, $symLit, $fpMask, false, $argsArrayExpr).toLong()"
            } else {
                "Win32DynamicFfi.invoke($dllLit, $symLit, $argsArrayExpr)"
            }
        val retKind = resolveNumericKind(renderedReturn)
        val retScopedKeys = scopedAbiKeysForRenderedType(renderedReturn)
        val unpackTemplate =
            if (retKind == null) {
                retScopedKeys.firstNotNullOfOrNull { headerContext.crateValueReturnUnpackTemplates[it] }
            } else {
                null
            }
        val bodyIndent = "$indent    "
        val bodyLines =
            when {
                renderedReturn == "Unit" -> {
                    val unitCall =
                        if (fpMask != 0) {
                            "Win32DynamicFfi.invokeFp($dllLit, $symLit, $fpMask, false, $argsArrayExpr)"
                        } else {
                            "Win32DynamicFfi.invoke($dllLit, $symLit, $argsArrayExpr)"
                        }
                    listOf("$bodyIndent$unitCall")
                }
                renderedReturn == "Nothing" || renderedReturn == "kotlin.Nothing" ->
                    listOf(
                        "${bodyIndent}Win32DynamicFfi.invoke($dllLit, $symLit, $argsArrayExpr)",
                        "${bodyIndent}error(\"unreachable\")",
                    )
                renderedReturn == "Boolean" ->
                    listOf(
                        "${bodyIndent}val rawRet = $intCall",
                        "${bodyIndent}return (rawRet and 0xFFL) != 0L",
                    )
                retKind == NumericKind.F32 ->
                    listOf("${bodyIndent}return Win32DynamicFfi.invokeFp($dllLit, $symLit, $fpMask, true, $argsArrayExpr).toFloat()")
                retKind == NumericKind.F64 ->
                    listOf("${bodyIndent}return Win32DynamicFfi.invokeFp($dllLit, $symLit, $fpMask, false, $argsArrayExpr)")
                retKind == NumericKind.I8 -> listOf("${bodyIndent}return $intCall.toByte()")
                retKind == NumericKind.U8 -> listOf("${bodyIndent}return $intCall.toUByte()")
                retKind == NumericKind.I16 -> listOf("${bodyIndent}return $intCall.toShort()")
                retKind == NumericKind.U16 -> listOf("${bodyIndent}return $intCall.toUShort()")
                retKind == NumericKind.I32 -> listOf("${bodyIndent}return $intCall.toInt()")
                retKind == NumericKind.U32 -> listOf("${bodyIndent}return $intCall.toUInt()")
                retKind == NumericKind.I64 -> listOf("${bodyIndent}return $intCall")
                retKind == NumericKind.U64 -> listOf("${bodyIndent}return $intCall.toULong()")
                retKind == NumericKind.I128 -> {
                    usesI128 = true
                    listOf("${bodyIndent}return I128.fromLong($intCall)")
                }
                retKind == NumericKind.U128 -> {
                    usesU128 = true
                    listOf("${bodyIndent}return U128.fromULong($intCall.toULong())")
                }
                retScopedKeys.any { it in headerContext.crateOptionFnTypes } || renderedReturn.endsWith("?") -> {
                    val baseAlias = renderedReturn.removeSuffix("?").substringAfterLast('.')
                    val samFnName = "${baseAlias}Fn"
                    var resolvedSamRef = samFnName
                    if (samFnName !in inspector.declaredTypeNames) {
                        val subpkg = resolveBestSubpackageForSymbol(samFnName)
                        val nsPrefix = currentNamespacePrefix()
                        if (subpkg != null && nsPrefix != null) {
                            val targetPkg =
                                if (subpkg.isEmpty()) nsPrefix else "$nsPrefix.$subpkg"
                            if (targetPkg.isNotEmpty() && targetPkg != headerContext.packageName) {
                                val collided = importedSimpleNameToPkg[samFnName]
                                if (collided != null && collided != targetPkg) {
                                    resolvedSamRef = "$targetPkg.$samFnName"
                                } else {
                                    importedSimpleNameToPkg[samFnName] = targetPkg
                                    extraTypePathImports += "import $targetPkg.$samFnName"
                                }
                            }
                        }
                    }
                    val samLookupKeys = (retScopedKeys.map { "${it.removeSuffix("?")}Fn" } + samFnName).distinct()
                    val invokeTemplate = samLookupKeys.firstNotNullOfOrNull { headerContext.crateFnPtrInvokeTemplates[it] }
                    val defaultCtor = samLookupKeys.firstNotNullOfOrNull { headerContext.crateTypeAliasDefaults[it] }
                    val nonNullCtor =
                        when {
                            invokeTemplate != null ->
                                invokeTemplate
                                    .replace("__SAM__", resolvedSamRef)
                                    .replace("__FFI__", "Win32DynamicFfi")
                            defaultCtor != null ->
                                if (resolvedSamRef != samFnName && defaultCtor.startsWith("$samFnName ")) {
                                    resolvedSamRef + defaultCtor.removePrefix(samFnName)
                                } else {
                                    defaultCtor
                                }
                            else -> "null"
                        }
                    if (nonNullCtor == "null") {
                        listOf(
                            "$bodyIndent$intCall",
                            "${bodyIndent}return null",
                        )
                    } else {
                        listOf(
                            "${bodyIndent}val rawRet = $intCall",
                            "${bodyIndent}return if (rawRet == 0L) null else $nonNullCtor",
                        )
                    }
                }
                unpackTemplate != null -> {
                    val unpackedExpr = unpackTemplate.replace("__TYPE__", renderedReturn)
                    listOf(
                        "${bodyIndent}val rawRet = $intCall",
                        "${bodyIndent}return $unpackedExpr",
                    )
                }
                else ->
                    listOf(
                        "$bodyIndent$intCall",
                        "${bodyIndent}return $renderedReturn()",
                    )
            }
        buildString {
            append(trivia)
            append(doc)
            append(indent)
            append("public fun ")
            append(fnName)
            append('(')
            append(paramDecls.joinToString(", "))
            append(')')
            append(retSuffix)
            append(" {\n")
            append(bodyLines.joinToString("\n"))
            append('\n')
            append(indent)
            append('}')
        }
    } finally {
        scopeBuilder.exitDef()
        inExternAbiSignature = prevExternAbi
    }
}

internal fun KotlinJ2KEmitter.renderImplOrTraitMacroMembers(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    scopeSignatures: MutableMap<String, Int>,
    asTopLevelExtension: Boolean,
    isInterface: Boolean,
    isLocalItem: Boolean = false,
): List<String> {
    val macroName =
        mac.path.segments
            .last()
            ?.ident
            ?.toString()
    if (macroName == "forward_to_deserialize_any") {
        return renderForwardToDeserializeAnyMembers(
            mac = mac,
            indent = indent,
            selfTypeName = selfTypeName,
            ownerGenerics = ownerGenerics,
            scopeSignatures = scopeSignatures,
            asTopLevelExtension = asTopLevelExtension,
            isInterface = isInterface,
            isLocalItem = isLocalItem,
        )
    }
    if (isInterface || asTopLevelExtension) {
        return emptyList()
    }
    val expanded = renderMacroInvocationAsStmtOrExpr(mac, "$indent    ", selfTypeName)
    return if (expanded.isBlank()) {
        emptyList()
    } else {
        listOf("${indent}init {\n$indent    $expanded\n$indent}")
    }
}

internal fun KotlinJ2KEmitter.renderForwardToDeserializeAnyMembers(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    scopeSignatures: MutableMap<String, Int>,
    asTopLevelExtension: Boolean,
    isInterface: Boolean,
    isLocalItem: Boolean,
): List<String> {
    var angleDepth = 0
    val keys = mutableListOf<String>()
    for (tt in mac.tokens.clone().toList()) {
        when (tt) {
            is TokenTree.Punct -> {
                when (tt.value.asChar()) {
                    '<' -> angleDepth++
                    '>' -> if (angleDepth > 0) angleDepth--
                }
            }
            is TokenTree.Ident -> {
                if (angleDepth == 0) {
                    val raw = tt.value.toString().removePrefix("r#")
                    if (raw in FORWARD_DESERIALIZE_KEYS) {
                        keys += raw
                    }
                }
            }
            else -> {}
        }
    }
    if (keys.isEmpty()) return emptyList()

    val ownerParams = ownerGenerics?.params?.toList().orEmpty()
    val ownerTypeParamNames =
        ownerParams
            .mapNotNull { param ->
                (param as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin)
            }.toSet()
    val visitorParam = if ("V" in ownerTypeParamNames) "W" else "V"
    val ownerTypeParamsRendered =
        if (asTopLevelExtension) {
            renderTypeParametersFromParams(ownerParams, selfTypeName)
                .removePrefix("<")
                .removeSuffix(">")
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
    val typeParamsStr =
        if (ownerTypeParamsRendered != null) {
            "<$ownerTypeParamsRendered, $visitorParam : Visitor>"
        } else {
            "<$visitorParam : Visitor>"
        }
    val whereClause =
        if (asTopLevelExtension) {
            renderCombinedWhereClause(
                ownerWhere = ownerGenerics?.whereClause,
                fnWhere = null,
                params = ownerParams,
                selfTypeName = selfTypeName,
            )
        } else {
            ""
        }

    val vis = if (isLocalItem) "" else "public "
    val openMod = if (!isInterface && !asTopLevelExtension && !isLocalItem && indent.isNotEmpty()) "open " else ""
    val receiverPrefix = if (asTopLevelExtension && !selfTypeName.isNullOrBlank()) "$selfTypeName." else ""
    val receiverKey = computeReceiverSignatureKey(receiverPrefix)
    val errorType = currentImplAssocTypes["Error"]?.let { renderType(it, selfTypeName) } ?: "Error"

    val out = mutableListOf<String>()
    for (key in keys) {
        val fnName = rustFunctionIdentToKotlin("deserialize_$key")
        val (extraParams, extraErased) = forwardDeserializeExtraParams(key)
        val allParams = extraParams + "visitor: $visitorParam"
        val allErased = extraErased + visitorParam
        val sigKey = "$receiverKey$fnName(${allErased.joinToString(",")})"
        val seenCount = scopeSignatures[sigKey] ?: 0
        scopeSignatures[sigKey] = seenCount + 1
        if (seenCount > 0) continue

        val sigHeader =
            "${vis}${openMod}fun $typeParamsStr ${receiverPrefix}$fnName(${allParams.joinToString(", ")}): Result<Any?, $errorType>$whereClause"
        out += "$indent$sigHeader {\n$indent    return this.deserializeAny(visitor)\n$indent}"
    }
    return out
}

internal fun KotlinJ2KEmitter.forwardDeserializeExtraParams(key: String): Pair<List<String>, List<String>> =
    when (key) {
        "unit_struct", "newtype_struct" -> listOf("name: String") to listOf("String")
        "tuple" -> listOf("len: Int") to listOf("Int")
        "tuple_struct" -> listOf("name: String", "len: Int") to listOf("String", "Int")
        "struct" -> listOf("name: String", "fields: List<String>") to listOf("String", "List")
        "enum" -> listOf("name: String", "variants: List<String>") to listOf("String", "List")
        else -> emptyList<String>() to emptyList()
    }

internal fun KotlinJ2KEmitter.renderItemForeignMod(item: Item.ForeignMod, indent: String): String {
    if (!headerContext.cDynamicFfi) {
        ffiCollector.recordForeignModAttributes(item.attrs)
    }
    val modComments =
        (renderLeadingTrivia(item, indent) + renderKDocFromAttributes(item.attrs, indent))
            .trimEnd()
    val out = mutableListOf<String>()
    for (foreign in item.items) {
        if (foreignItemHasDocOnlyCfg(foreign)) continue
        when (foreign) {
            is ForeignItem.Fn -> {
                val vis = renderVisibility(foreign.vis)
                val rawFnName = foreign.sig.ident.toString()
                val fnName = rustFunctionIdentToKotlin(rawFnName)
                if (headerContext.cDynamicFfi) {
                    val renderedFn = renderCDynamicForeignFunction(foreign, vis, fnName, rawFnName, indent)
                    if (renderedFn.isNotBlank()) {
                        out += renderedFn
                    }
                    continue
                }
                val interopParams = mutableListOf<InteropParamSpec>()
                scopeBuilder.enterDef()
                val (sig, retRendered) =
                    try {
                        renderForeignFunctionExpectSignature(
                            sig = foreign.sig,
                            visPrefix = vis,
                            fnName = fnName,
                            scopeSignatures = topLevelSignatureCounts,
                            interopParamsOut = interopParams,
                        )
                    } finally {
                        scopeBuilder.exitDef()
                    } ?: continue
                val fnTrivia = renderLeadingTrivia(foreign, indent)
                val doc = renderKDocFromAttributes(foreign.attrs, indent)
                val retType = extractReturnSynType(foreign.sig.output)
                ffiCollector.recordForeignFunction(
                    visibilityPrefix = vis,
                    kotlinName = fnName,
                    rawRustName = rawFnName,
                    attrs = foreign.attrs,
                    params = interopParams,
                    kotlinReturnType = retRendered,
                    returnSynType = retType,
                    isVariadic = foreign.sig.variadic != null,
                )
                out += "$fnTrivia$doc$indent$sig"
            }
            is ForeignItem.Static -> {
                val stTrivia = renderLeadingTrivia(foreign, indent)
                val doc = renderKDocFromAttributes(foreign.attrs, indent)
                val vis = renderVisibility(foreign.vis)
                val rawIdent = foreign.ident.toString().removePrefix("r#")
                val ktConst = inspector.declaredConstNames[rawIdent] ?: rustConstIdentToKotlin(rawIdent)
                if (rawIdent !in inspector.declaredConstNames) {
                    inspector.declaredConstNames[rawIdent] = ktConst
                }
                val name = disambiguateName(ktConst, topLevelSignatureCounts)
                val prevExternAbi = inExternAbiSignature
                inExternAbiSignature = true
                val ty =
                    try {
                        renderType(foreign.ty, null)
                    } finally {
                        inExternAbiSignature = prevExternAbi
                    }
                val kw = if (foreign.mutability is StaticMutability.Mut) "var" else "val"
                if (headerContext.cDynamicFfi) {
                    out += renderCDynamicForeignStatic(foreign, stTrivia, doc, indent, vis, kw, name, rawIdent, ty)
                } else {
                    out += "$stTrivia$doc$indent${vis}expect $kw $name: $ty"
                }
            }
            else -> {}
        }
    }
    if (modComments.isNotBlank() && out.isNotEmpty()) {
        out[0] = "$modComments\n${out[0]}"
    }
    return out.joinToString("\n\n")
}

internal fun KotlinJ2KEmitter.renderCDynamicForeignStatic(
    foreign: ForeignItem.Static,
    stTrivia: String,
    doc: String,
    indent: String,
    vis: String,
    kw: String,
    name: String,
    rawIdent: String,
    ty: String,
): String {
    val exportedSym = FfiInteropCollector.extractLinkNameAttribute(foreign.attrs) ?: rawIdent
    val symLit = renderKotlinDoubleQuotedString(exportedSym)
    val numKind = if (!ty.endsWith("?")) resolveNumericKind(ty) else null
    val isMut = kw == "var"
    val bodyIndent = "$indent    "
    val innerIndent = "$bodyIndent    "
    if (numKind != null) {
        usesLibcDynamicFfi = true
        if (numKind == NumericKind.I128 || numKind == NumericKind.U128) {
            if (numKind == NumericKind.U128) usesU128 = true else usesI128 = true
            val bigEndian = TranslationSession.cfgTargetProfile.targetEndian == "big"
            val hiOff = if (bigEndian) "addr" else "addr + 8L"
            val loOff = if (bigEndian) "addr + 8L" else "addr"
            val readConv128 =
                if (numKind == NumericKind.U128) {
                    "U128(LibcDynamicFfi.readScalarSlot($hiOff, 8).toULong(), LibcDynamicFfi.readScalarSlot($loOff, 8).toULong())"
                } else {
                    "I128(LibcDynamicFfi.readScalarSlot($hiOff, 8), LibcDynamicFfi.readScalarSlot($loOff, 8).toULong())"
                }
            val defaultVal128 = defaultInitializerForType(ty)
            if (!isMut) {
                return buildString {
                    append(stTrivia)
                    append(doc)
                    append("${indent}${vis}val $name: $ty\n")
                    append("${bodyIndent}get() {\n")
                    append("${innerIndent}val addr = LibcDynamicFfi.resolveSymbolAddress($symLit)\n")
                    append("${innerIndent}return if (addr != 0L) $readConv128 else $defaultVal128\n")
                    append(bodyIndent).append('}')
                }
            }
            val backingName128 = "__backing_$name"
            val writeHi128 = if (numKind == NumericKind.U128) "value.high.toLong()" else "value.high"
            return buildString {
                append("${indent}private var $backingName128: $ty = $defaultVal128\n\n")
                append(stTrivia)
                append(doc)
                append("${indent}${vis}var $name: $ty\n")
                append("${bodyIndent}get() {\n")
                append("${innerIndent}val addr = LibcDynamicFfi.resolveSymbolAddress($symLit)\n")
                append("${innerIndent}return if (addr != 0L) $readConv128 else $backingName128\n")
                append("$bodyIndent}\n")
                append("${bodyIndent}set(value) {\n")
                append("$innerIndent$backingName128 = value\n")
                append("${innerIndent}val addr = LibcDynamicFfi.resolveSymbolAddress($symLit)\n")
                append("${innerIndent}if (addr != 0L) {\n")
                append("$innerIndent    LibcDynamicFfi.writeScalarSlot($hiOff, 8, $writeHi128)\n")
                append("$innerIndent    LibcDynamicFfi.writeScalarSlot($loOff, 8, value.low.toLong())\n")
                append("$innerIndent}\n")
                append(bodyIndent).append('}')
            }
        }
        val width =
            when (numKind) {
                NumericKind.I8, NumericKind.U8 -> 1
                NumericKind.I16, NumericKind.U16 -> 2
                NumericKind.I32, NumericKind.U32, NumericKind.F32 -> 4
                NumericKind.I64, NumericKind.U64, NumericKind.F64 -> 8
                NumericKind.I128, NumericKind.U128 -> 16
            }
        val readConv =
            when (numKind) {
                NumericKind.I8 -> "LibcDynamicFfi.readScalarSlot(addr, $width).toByte()"
                NumericKind.U8 -> "LibcDynamicFfi.readScalarSlot(addr, $width).toUByte()"
                NumericKind.I16 -> "LibcDynamicFfi.readScalarSlot(addr, $width).toShort()"
                NumericKind.U16 -> "LibcDynamicFfi.readScalarSlot(addr, $width).toUShort()"
                NumericKind.I32 -> "LibcDynamicFfi.readScalarSlot(addr, $width).toInt()"
                NumericKind.U32 -> "LibcDynamicFfi.readScalarSlot(addr, $width).toUInt()"
                NumericKind.I64 -> "LibcDynamicFfi.readScalarSlot(addr, $width)"
                NumericKind.U64 -> "LibcDynamicFfi.readScalarSlot(addr, $width).toULong()"
                NumericKind.I128 -> "I128()"
                NumericKind.U128 -> "U128()"
                NumericKind.F32 -> "Float.fromBits(LibcDynamicFfi.readScalarSlot(addr, $width).toInt())"
                NumericKind.F64 -> "Double.fromBits(LibcDynamicFfi.readScalarSlot(addr, $width))"
            }
        val defaultVal = defaultInitializerForType(ty)
        if (!isMut) {
            return buildString {
                append(stTrivia)
                append(doc)
                append("${indent}${vis}val $name: $ty\n")
                append("${bodyIndent}get() {\n")
                append("${innerIndent}val addr = LibcDynamicFfi.resolveSymbolAddress($symLit)\n")
                append("${innerIndent}return if (addr != 0L) $readConv else $defaultVal\n")
                append(bodyIndent).append('}')
            }
        }
        val backingName = "__backing_$name"
        val writeBits =
            when (numKind) {
                NumericKind.I64 -> "value"
                NumericKind.F32 -> "(value.toRawBits().toLong() and 0xFFFF_FFFFL)"
                NumericKind.F64 -> "value.toRawBits()"
                else -> "value.toLong()"
            }
        return buildString {
            append("${indent}private var $backingName: $ty = $defaultVal\n\n")
            append(stTrivia)
            append(doc)
            append("${indent}${vis}var $name: $ty\n")
            append("${bodyIndent}get() {\n")
            append("${innerIndent}val addr = LibcDynamicFfi.resolveSymbolAddress($symLit)\n")
            append("${innerIndent}return if (addr != 0L) $readConv else $backingName\n")
            append("$bodyIndent}\n")
            append("${bodyIndent}set(value) {\n")
            append("$innerIndent$backingName = value\n")
            append("${innerIndent}val addr = LibcDynamicFfi.resolveSymbolAddress($symLit)\n")
            append("${innerIndent}if (addr != 0L) LibcDynamicFfi.writeScalarSlot(addr, $width, $writeBits)\n")
            append(bodyIndent).append('}')
        }
    }
    if (ty == "String?") {
        usesLibcDynamicFfi = true
        val backingName = "__backing_$name"
        if (!isMut) {
            return buildString {
                append(stTrivia)
                append(doc)
                append("${indent}${vis}val $name: String?\n")
                append("${bodyIndent}get() {\n")
                append("${innerIndent}val addr = LibcDynamicFfi.resolveSymbolAddress($symLit)\n")
                append("${innerIndent}if (addr == 0L) return null\n")
                append("${innerIndent}val ptr = LibcDynamicFfi.readScalarSlot(addr, 8)\n")
                append("${innerIndent}return if (ptr == 0L) null else LibcDynamicFfi.readUtf8CString(ptr)\n")
                append(bodyIndent).append('}')
            }
        }
        return buildString {
            append("${indent}private var $backingName: String? = null\n\n")
            append(stTrivia)
            append(doc)
            append("${indent}${vis}var $name: String?\n")
            append("${bodyIndent}get() {\n")
            append("${innerIndent}val addr = LibcDynamicFfi.resolveSymbolAddress($symLit)\n")
            append("${innerIndent}if (addr == 0L) return $backingName\n")
            append("${innerIndent}val ptr = LibcDynamicFfi.readScalarSlot(addr, 8)\n")
            append("${innerIndent}return if (ptr == 0L) null else LibcDynamicFfi.readUtf8CString(ptr)\n")
            append("$bodyIndent}\n")
            append("${bodyIndent}set(value) {\n")
            append("$innerIndent$backingName = value\n")
            append(bodyIndent).append('}')
        }
    }
    if (ty == "COpaquePointer?") {
        usesLibcDynamicFfi = true
        val backingName = "__backing_$name"
        if (!isMut) {
            return buildString {
                append(stTrivia)
                append(doc)
                append("${indent}${vis}val $name: COpaquePointer?\n")
                append("${bodyIndent}get() {\n")
                append("${innerIndent}val addr = LibcDynamicFfi.resolveSymbolAddress($symLit)\n")
                append("${innerIndent}if (addr == 0L) return null\n")
                append("${innerIndent}val ptr = LibcDynamicFfi.readScalarSlot(addr, 8)\n")
                append("${innerIndent}return if (ptr == 0L) null else COpaquePointer(ptr)\n")
                append(bodyIndent).append('}')
            }
        }
        return buildString {
            append("${indent}private var $backingName: COpaquePointer? = null\n\n")
            append(stTrivia)
            append(doc)
            append("${indent}${vis}var $name: COpaquePointer?\n")
            append(bodyIndent).append("get() {\n")
            append(innerIndent).append("val addr = LibcDynamicFfi.resolveSymbolAddress(").append(symLit).append(")\n")
            append(innerIndent).append("if (addr == 0L) return ").append(backingName).append('\n')
            append(innerIndent).append("val ptr = LibcDynamicFfi.readScalarSlot(addr, 8)\n")
            append(innerIndent).append("return if (ptr == 0L) null else COpaquePointer(ptr)\n")
            append(bodyIndent).append("}\n")
            append(bodyIndent).append("set(value) {\n")
            append(innerIndent).append(backingName).append(" = value\n")
            append(innerIndent).append("val addr = LibcDynamicFfi.resolveSymbolAddress(").append(symLit).append(")\n")
            append(innerIndent).append("if (addr != 0L) LibcDynamicFfi.writeScalarSlot(addr, 8, value?.value ?: 0L)\n")
            append(bodyIndent).append('}')
        }
    }
    return "$stTrivia$doc$indent$vis$kw $name: $ty = ${defaultInitializerForType(ty)}"
}

internal fun KotlinJ2KEmitter.renderCDynamicForeignFunction(
    foreign: ForeignItem.Fn,
    visPrefix: String,
    fnName: String,
    rawFnName: String,
    indent: String,
): String {
    val prevExternAbi = inExternAbiSignature
    inExternAbiSignature = true
    scopeBuilder.enterDef()
    return try {
        val sig = foreign.sig
        val combinedParams = sig.generics.params.toList()
        val typeParamsStr = renderTypeParametersFromParams(combinedParams, null)
        val paramDecls = mutableListOf<String>()
        val paramTypeKeys = mutableListOf<String>()
        val paramNameTypeAndSyn = mutableListOf<Triple<String, String, SynType>>()
        var paramIdx = 0
        for (arg in sig.inputs.toList()) {
            if (arg is FnArg.Typed) {
                val rawParam = extractRawParamIdent(arg.patType.pat)
                val pName =
                    if (rawParam != null && rawParam != "_") {
                        scopeBuilder
                            .bindLocal(
                                rawName = rawParam,
                                baseKotlinName = rustValueIdentToKotlin(rawParam),
                                span = arg.patType.pat.span(),
                                assignCount = AssignCount.AtMostOnce,
                            ).kotlinName
                    } else {
                        extractParamName(arg.patType.pat, paramIdx)
                    }
                val prevHint = currentSamNameHint
                val prevParam = inExternParamType
                currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}${pName.replaceFirstChar { it.uppercase() }}Callback"
                inExternParamType = true
                val pType =
                    try {
                        renderType(arg.patType.ty, null)
                    } finally {
                        inExternParamType = prevParam
                        currentSamNameHint = prevHint
                    }
                paramDecls += "$pName: $pType"
                paramTypeKeys += eraseTypeForSignatureKey(pType)
                paramNameTypeAndSyn += Triple(pName, pType, arg.patType.ty)
                paramIdx++
            }
        }
        val isVariadic = sig.variadic != null
        if (isVariadic) {
            paramDecls += "vararg variadicArgs: Long"
            paramTypeKeys += "Long..."
        }
        val sigKey = "$fnName(${paramTypeKeys.joinToString(",")})"
        val seenCount = topLevelSignatureCounts[sigKey] ?: 0
        topLevelSignatureCounts[sigKey] = seenCount + 1
        if (seenCount > 0) return ""

        val retSynType = extractReturnSynType(sig.output)
        val prevHint = currentSamNameHint
        currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}ResultCallback"
        val renderedReturn =
            try {
                retSynType?.let { renderType(it, null) } ?: "Unit"
            } finally {
                currentSamNameHint = prevHint
            }
        val retSuffix = if (renderedReturn == "Unit") "" else ": $renderedReturn"
        val whereClause = renderWhereClause(sig.generics.whereClause, null)
        val typeParamsPart = if (typeParamsStr.isNotEmpty()) "$typeParamsStr " else ""

        val fnTrivia = renderLeadingTrivia(foreign, indent)
        val doc = renderKDocFromAttributes(foreign.attrs, indent)
        val exportedSymbol = FfiInteropCollector.extractLinkNameAttribute(foreign.attrs) ?: rawFnName
        val symLit = renderKotlinDoubleQuotedString(exportedSymbol)
        val fixedArgc = if (isVariadic) paramNameTypeAndSyn.size else -1

        val allocLines = mutableListOf<String>()
        val cleanupLines = mutableListOf<String>()
        var fpMask = 0
        val slotExprs =
            paramNameTypeAndSyn.mapIndexed { idx, (pName, pType, pSynType) ->
                val nonNullKind = if (!pType.endsWith("?")) resolveNumericKind(pType) else null
                val nullableScalarKind = if (pType.endsWith("?")) resolveNumericKind(pType.removeSuffix("?")) else null
                val paramBridge = ffiCollector.classifyParamAbiBridge(pSynType, pType)
                when {
                    pType == "String?" -> {
                        val tmp = "__cstr_$idx"
                        allocLines += "val $tmp = if ($pName == null) 0L else LibcDynamicFfi.allocUtf8CString($pName)"
                        cleanupLines += "if ($tmp != 0L) LibcDynamicFfi.freeMem($tmp)"
                        tmp
                    }
                    pType == "String" -> {
                        val tmp = "__cstr_$idx"
                        allocLines += "val $tmp = LibcDynamicFfi.allocUtf8CString($pName)"
                        cleanupLines += "if ($tmp != 0L) LibcDynamicFfi.freeMem($tmp)"
                        tmp
                    }
                    pType == "COpaquePointer?" -> "$pName?.value ?: 0L"
                    pType == "COpaquePointer" -> "$pName.value"
                    pType == "FILE?" -> "$pName?.handle ?: 0L"
                    pType == "FILE" -> "$pName.handle"
                    pType == "DIR?" -> "$pName?.handle ?: 0L"
                    pType == "DIR" -> "$pName.handle"
                    !headerContext.rawAbiMode && paramBridge == CAbiBridgeKind.FILE_PTR -> "$pName?.handle ?: 0L"
                    !headerContext.rawAbiMode && paramBridge == CAbiBridgeKind.DIR_PTR -> "$pName?.handle ?: 0L"
                    !headerContext.rawAbiMode &&
                        (
                            paramBridge == CAbiBridgeKind.CONST_CSTR ||
                                paramBridge == CAbiBridgeKind.MUT_CSTR ||
                                paramBridge == CAbiBridgeKind.OPAQUE_PTR
                        ) &&
                        nonNullKind == null &&
                        nullableScalarKind == null -> "$pName?.value ?: 0L"
                    pType == "Boolean" -> "if ($pName) 1L else 0L"
                    pType == "Boolean?" -> {
                        val tmp = "__slot_$idx"
                        allocLines += "val $tmp = if ($pName == null) 0L else LibcDynamicFfi.allocScalarSlot(if ($pName) 1L else 0L)"
                        cleanupLines += "if ($tmp != 0L) LibcDynamicFfi.freeMem($tmp)"
                        tmp
                    }
                    nonNullKind == NumericKind.I64 -> pName
                    nonNullKind == NumericKind.F32 -> {
                        if (idx < 8) fpMask = fpMask or (1 shl (idx * 2))
                        "($pName.toRawBits().toLong() and 0xFFFF_FFFFL)"
                    }
                    nonNullKind == NumericKind.F64 -> {
                        if (idx < 8) fpMask = fpMask or (2 shl (idx * 2))
                        "$pName.toRawBits()"
                    }
                    nonNullKind != null -> "$pName.toLong()"
                    nullableScalarKind != null -> {
                        val bitsExpr =
                            when (nullableScalarKind) {
                                NumericKind.I64 -> pName
                                NumericKind.F32 -> "($pName.toRawBits().toLong() and 0xFFFF_FFFFL)"
                                NumericKind.F64 -> "$pName.toRawBits()"
                                else -> "$pName.toLong()"
                            }
                        val tmp = "__slot_$idx"
                        allocLines += "val $tmp = if ($pName == null) 0L else LibcDynamicFfi.allocScalarSlot($bitsExpr)"
                        cleanupLines += "if ($tmp != 0L) LibcDynamicFfi.freeMem($tmp)"
                        tmp
                    }
                    pType.endsWith("?") -> {
                        val tmp = "__scratch_$idx"
                        allocLines += "val $tmp = if ($pName == null) 0L else LibcDynamicFfi.allocZeroed(4096)"
                        cleanupLines += "if ($tmp != 0L) LibcDynamicFfi.freeMem($tmp)"
                        tmp
                    }
                    else -> {
                        val tmp = "__scratch_$idx"
                        allocLines += "val $tmp = LibcDynamicFfi.allocZeroed(4096)"
                        cleanupLines += "if ($tmp != 0L) LibcDynamicFfi.freeMem($tmp)"
                        tmp
                    }
                }
            }

        val argsArrayExpr =
            if (isVariadic) {
                if (slotExprs.isEmpty()) "variadicArgs" else "longArrayOf(${slotExprs.joinToString(", ")}) + variadicArgs"
            } else {
                "longArrayOf(${slotExprs.joinToString(", ")})"
            }

        usesLibcDynamicFfi = true
        val intCall =
            if (fpMask != 0 && !isVariadic) {
                "LibcDynamicFfi.invokeFp($symLit, $fpMask, false, $argsArrayExpr).toLong()"
            } else {
                "LibcDynamicFfi.invoke($symLit, $fixedArgc, $argsArrayExpr)"
            }

        val retKind = if (!renderedReturn.endsWith("?")) resolveNumericKind(renderedReturn) else null
        val retNullableScalarKind = if (renderedReturn.endsWith("?")) resolveNumericKind(renderedReturn.removeSuffix("?")) else null
        val retBridge = ffiCollector.classifyReturnAbiBridge(retSynType, renderedReturn)
        val bodyIndent = "$indent    "
        val callIndent = if (cleanupLines.isEmpty()) bodyIndent else "$bodyIndent    "
        val coreBodyLines =
            when {
                renderedReturn == "Unit" -> {
                    val unitCall =
                        if (fpMask != 0 && !isVariadic) {
                            "LibcDynamicFfi.invokeFp($symLit, $fpMask, false, $argsArrayExpr)"
                        } else {
                            "LibcDynamicFfi.invoke($symLit, $fixedArgc, $argsArrayExpr)"
                        }
                    listOf("$callIndent$unitCall")
                }
                renderedReturn == "Nothing" || renderedReturn == "kotlin.Nothing" ->
                    listOf(
                        "${callIndent}LibcDynamicFfi.invoke($symLit, $fixedArgc, $argsArrayExpr)",
                        "${callIndent}error(\"unreachable\")",
                    )
                renderedReturn == "Boolean" ->
                    listOf(
                        "${callIndent}val rawRet = $intCall",
                        "${callIndent}return (rawRet and 0xFFL) != 0L",
                    )
                renderedReturn == "Boolean?" ->
                    listOf(
                        "${callIndent}val rawRet = $intCall",
                        "${callIndent}return if (rawRet == 0L) null else (LibcDynamicFfi.readScalarSlot(rawRet, 1) and 0xFFL) != 0L",
                    )
                renderedReturn == "String?" ->
                    listOf(
                        "${callIndent}val rawRet = $intCall",
                        "${callIndent}return if (rawRet == 0L) null else LibcDynamicFfi.readUtf8CString(rawRet)",
                    )
                renderedReturn == "String" ->
                    listOf(
                        "${callIndent}val rawRet = $intCall",
                        "${callIndent}return if (rawRet == 0L) \"\" else LibcDynamicFfi.readUtf8CString(rawRet)",
                    )
                renderedReturn == "COpaquePointer?" ||
                    (
                        !headerContext.rawAbiMode &&
                            (
                                retBridge == CAbiBridgeKind.CONST_CSTR ||
                                    retBridge == CAbiBridgeKind.MUT_CSTR ||
                                    retBridge == CAbiBridgeKind.OPAQUE_PTR
                            ) &&
                            retKind == null &&
                            retNullableScalarKind == null
                    ) -> {
                    usesOpaquePointer = true
                    listOf(
                        "${callIndent}val rawRet = $intCall",
                        "${callIndent}return if (rawRet == 0L) null else COpaquePointer(rawRet)",
                    )
                }
                renderedReturn == "COpaquePointer" ->
                    listOf("${callIndent}return COpaquePointer($intCall)")
                renderedReturn == "FILE?" || (!headerContext.rawAbiMode && retBridge == CAbiBridgeKind.FILE_PTR) ->
                    listOf(
                        "${callIndent}val rawRet = $intCall",
                        "${callIndent}return if (rawRet == 0L) null else FILE(rawRet)",
                    )
                renderedReturn == "FILE" ->
                    listOf("${callIndent}return FILE($intCall)")
                renderedReturn == "DIR?" || (!headerContext.rawAbiMode && retBridge == CAbiBridgeKind.DIR_PTR) ->
                    listOf(
                        "${callIndent}val rawRet = $intCall",
                        "${callIndent}return if (rawRet == 0L) null else DIR(rawRet)",
                    )
                renderedReturn == "DIR" ->
                    listOf("${callIndent}return DIR($intCall)")
                retKind == NumericKind.F32 ->
                    listOf("${callIndent}return LibcDynamicFfi.invokeFp($symLit, $fpMask, true, $argsArrayExpr).toFloat()")
                retKind == NumericKind.F64 ->
                    listOf("${callIndent}return LibcDynamicFfi.invokeFp($symLit, $fpMask, false, $argsArrayExpr)")
                retKind == NumericKind.I8 -> listOf("${callIndent}return $intCall.toByte()")
                retKind == NumericKind.U8 -> listOf("${callIndent}return $intCall.toUByte()")
                retKind == NumericKind.I16 -> listOf("${callIndent}return $intCall.toShort()")
                retKind == NumericKind.U16 -> listOf("${callIndent}return $intCall.toUShort()")
                retKind == NumericKind.I32 -> listOf("${callIndent}return $intCall.toInt()")
                retKind == NumericKind.U32 -> listOf("${callIndent}return $intCall.toUInt()")
                retKind == NumericKind.I64 -> listOf("${callIndent}return $intCall")
                retKind == NumericKind.U64 -> listOf("${callIndent}return $intCall.toULong()")
                retNullableScalarKind != null -> {
                    val readExpr =
                        when (retNullableScalarKind) {
                            NumericKind.I8 -> "LibcDynamicFfi.readScalarSlot(rawRet, 1).toByte()"
                            NumericKind.U8 -> "LibcDynamicFfi.readScalarSlot(rawRet, 1).toUByte()"
                            NumericKind.I16 -> "LibcDynamicFfi.readScalarSlot(rawRet, 2).toShort()"
                            NumericKind.U16 -> "LibcDynamicFfi.readScalarSlot(rawRet, 2).toUShort()"
                            NumericKind.I32 -> "LibcDynamicFfi.readScalarSlot(rawRet, 4).toInt()"
                            NumericKind.U32 -> "LibcDynamicFfi.readScalarSlot(rawRet, 4).toUInt()"
                            NumericKind.I64 -> "LibcDynamicFfi.readScalarSlot(rawRet, 8)"
                            NumericKind.U64 -> "LibcDynamicFfi.readScalarSlot(rawRet, 8).toULong()"
                            NumericKind.I128 -> {
                                usesI128 = true
                                "I128.fromLeBytes(LibcDynamicFfi.readBytes(rawRet, 16).asUByteArray())"
                            }
                            NumericKind.U128 -> {
                                usesU128 = true
                                "U128.fromLeBytes(LibcDynamicFfi.readBytes(rawRet, 16).asUByteArray())"
                            }
                            NumericKind.F32 -> "Float.fromBits(LibcDynamicFfi.readScalarSlot(rawRet, 4).toInt())"
                            NumericKind.F64 -> "Double.fromBits(LibcDynamicFfi.readScalarSlot(rawRet, 8))"
                        }
                    listOf(
                        "${callIndent}val rawRet = $intCall",
                        "${callIndent}return if (rawRet == 0L) null else $readExpr",
                    )
                }
                renderedReturn in headerContext.crateOptionFnTypes || renderedReturn.endsWith("?") -> {
                    val baseType = renderedReturn.removeSuffix("?")
                    val nonNullDefault = defaultInitializerForType(baseType)
                    if (nonNullDefault == "null" || nonNullDefault.startsWith("null as ")) {
                        listOf(
                            "$callIndent$intCall",
                            "${callIndent}return null",
                        )
                    } else {
                        listOf(
                            "${callIndent}val rawRet = $intCall",
                            "${callIndent}return if (rawRet == 0L) null else $nonNullDefault",
                        )
                    }
                }
                else ->
                    listOf(
                        "$callIndent$intCall",
                        "${callIndent}return ${defaultInitializerForType(renderedReturn)}",
                    )
            }

        val fullBodyLines =
            if (cleanupLines.isEmpty()) {
                coreBodyLines
            } else {
                buildList {
                    for (al in allocLines) add("$bodyIndent$al")
                    add("${bodyIndent}try {")
                    addAll(coreBodyLines)
                    add("$bodyIndent} finally {")
                    for (cl in cleanupLines) add("$callIndent$cl")
                    add("$bodyIndent}")
                }
            }

        buildString {
            append(fnTrivia)
            append(doc)
            append(indent)
            append(visPrefix)
            append("fun ")
            append(typeParamsPart)
            append(fnName)
            append('(')
            append(paramDecls.joinToString(", "))
            append(')')
            append(retSuffix)
            append(whereClause)
            append(" {\n")
            append(fullBodyLines.joinToString("\n"))
            append('\n')
            append(indent)
            append('}')
        }
    } finally {
        scopeBuilder.exitDef()
        inExternAbiSignature = prevExternAbi
    }
}

internal fun KotlinJ2KEmitter.renderForeignFunctionExpectSignature(
    sig: Signature,
    visPrefix: String,
    fnName: String,
    scopeSignatures: MutableMap<String, Int>,
    interopParamsOut: MutableList<InteropParamSpec>,
): Pair<String, String>? {
    val prevExternAbi = inExternAbiSignature
    inExternAbiSignature = true
    return try {
        val combinedParams = sig.generics.params.toList()
        val typeParamsStr = renderTypeParametersFromParams(combinedParams, null)
        val params = mutableListOf<String>()
        val paramTypeSignatureKeys = mutableListOf<String>()
        var paramIdx = 0
        for (arg in sig.inputs.toList()) {
            if (arg is FnArg.Typed) {
                val rawParam = extractRawParamIdent(arg.patType.pat)
                val pName =
                    if (rawParam != null && rawParam != "_") {
                        scopeBuilder
                            .bindLocal(
                                rawName = rawParam,
                                baseKotlinName = rustValueIdentToKotlin(rawParam),
                                span = arg.patType.pat.span(),
                                assignCount = AssignCount.AtMostOnce,
                            ).kotlinName
                    } else {
                        extractParamName(arg.patType.pat, paramIdx)
                    }
                val prevHint = currentSamNameHint
                val prevParam = inExternParamType
                currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}${pName.replaceFirstChar { it.uppercase() }}Callback"
                inExternParamType = true
                val pType =
                    try {
                        renderType(arg.patType.ty, null)
                    } finally {
                        inExternParamType = prevParam
                        currentSamNameHint = prevHint
                    }
                val bridge = ffiCollector.classifyParamAbiBridge(arg.patType.ty, pType)
                val cParamName = rawParam?.takeIf { it != "_" } ?: "arg$paramIdx"
                interopParamsOut +=
                    InteropParamSpec(
                        kotlinName = pName,
                        cName = cParamName,
                        kotlinType = pType,
                        cType = pType,
                        bridgeKind = bridge,
                    )
                params += "$pName: $pType"
                paramTypeSignatureKeys += eraseTypeForSignatureKey(pType)
                paramIdx++
            }
        }
        if (sig.variadic != null) {
            params += "vararg variadicArgs: Long"
            paramTypeSignatureKeys += "Long..."
        }
        val sigKey = "$fnName(${paramTypeSignatureKeys.joinToString(",")})"
        val seenCount = scopeSignatures[sigKey] ?: 0
        scopeSignatures[sigKey] = seenCount + 1
        if (seenCount > 0) {
            return null
        }
        val retType = extractReturnSynType(sig.output)
        val prevHint = currentSamNameHint
        currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}ResultCallback"
        val renderedReturn =
            try {
                retType?.let { renderType(it, null) } ?: "Unit"
            } finally {
                currentSamNameHint = prevHint
            }
        val retTypeStr = if (renderedReturn == "Unit") "" else ": $renderedReturn"
        val whereClause = renderWhereClause(sig.generics.whereClause, null)
        val typeParamsPart = if (typeParamsStr.isNotEmpty()) "$typeParamsStr " else ""
        val expectSig = "${visPrefix}expect fun ${typeParamsPart}$fnName(${params.joinToString(", ")})${retTypeStr}$whereClause"
        expectSig to renderedReturn
    } finally {
        inExternAbiSignature = prevExternAbi
    }
}
