package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.EqPeek
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.ExprParse
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Meta
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.PointerMutability
import io.github.kotlinmania.syn.SynResult
import io.github.kotlinmania.syn.SynType

public sealed class CompanionInteropKind {
    public data object CInteropDef : CompanionInteropKind()

    public data object CHeader : CompanionInteropKind()

    public data object CSource : CompanionInteropKind()

    public data object CppSource : CompanionInteropKind()

    public data object NativeKotlinActuals : CompanionInteropKind()
}

public data class CompanionInteropFile(
    public val relativePath: String,
    public val kind: CompanionInteropKind,
    public val content: String,
)

internal enum class CAbiBridgeKind {
    VOID,
    NEVER,
    BOOL,
    I8,
    U8,
    I16,
    U16,
    I32,
    U32,
    I64,
    U64,
    F32,
    F64,
    CONST_CSTR,
    MUT_CSTR,
    OPAQUE_PTR,
    FILE_PTR,
    DIR_PTR,
}

internal data class InteropParamSpec(
    val kotlinName: String,
    val cName: String,
    val kotlinType: String,
    val cType: String,
    val bridgeKind: CAbiBridgeKind,
)

internal data class ForeignFnInteropSpec(
    val visibilityPrefix: String,
    val kotlinName: String,
    val cSymbolName: String,
    val wrapperSymbolName: String,
    val params: List<InteropParamSpec>,
    val kotlinReturnType: String,
    val cReturnType: String,
    val returnBridgeKind: CAbiBridgeKind,
    val isVariadic: Boolean,
)

internal data class InlineCFunctionSpec(
    val visibilityPrefix: String,
    val kotlinName: String,
    val wrapperSymbolName: String,
    val params: List<InteropParamSpec>,
    val kotlinReturnType: String,
    val cReturnType: String,
    val returnBridgeKind: CAbiBridgeKind,
    val rawBody: String,
    val isCpp: Boolean,
)

internal data class ParsedInlineCMacro(
    val captureArgs: List<InlineCaptureArg>,
    val returnRustType: SynType?,
    val explicitCReturnType: String?,
    val rawBody: String,
    val isCpp: Boolean,
)

internal data class InlineCaptureArg(
    val rawRustIdent: String,
    val isMut: Boolean,
    val rustType: SynType?,
    val explicitCType: String?,
)

private data class PlatformHeaderSpec(
    val header: String,
    val guardCondition: String? = null,
    val windowsFallbackHeaders: List<String> = emptyList(),
)

internal class FfiInteropCollector(
    private val source: String,
    private val headerContext: FileHeaderContext,
    private val resolveNumericKind: (String) -> NumericKind? = { null },
    private val resolveTypeAliasTarget: (String) -> SynType? = { null },
) {
    private val preambles = mutableListOf<String>()
    private val foreignFunctions = mutableListOf<ForeignFnInteropSpec>()
    private val inlineFunctions = mutableListOf<InlineCFunctionSpec>()
    private val linkLibraries = linkedSetOf<String>()
    private val explicitHeaders = linkedSetOf<String>()
    private var usesCppLanguage = false
    private var inlineCounter = 0

    init {
        extractHeaderCommentsFromSource(source, explicitHeaders)
    }

    val cSymbolPrefix: String = computeCSymbolPrefix(headerContext)

    private val activeCfgProfile: CfgTargetProfile
        get() = headerContext.cfgTargetProfile ?: TranslationSession.cfgTargetProfile

    private fun decodePointerAliasSpec(encoded: String?): Pair<Boolean, String>? {
        if (encoded == null) return null
        val sep = encoded.indexOf(':')
        if (sep <= 0) return null
        val isMut = encoded.substring(0, sep) == "mut"
        val pointee = encoded.substring(sep + 1)
        return isMut to pointee
    }

    private fun lookupCrossFilePointerAlias(ident: String): Pair<Boolean, String>? {
        val simple = ident.substringAfterLast('.').removeSuffix("?").removePrefix("r#")
        decodePointerAliasSpec(headerContext.cratePointerTypeAliases[ident])?.let { return it }
        decodePointerAliasSpec(headerContext.cratePointerTypeAliases[simple])?.let { return it }
        val mappedKt = KotlinNamingPolicy.Default.toTypeIdentifier(simple, allowRawAbi = headerContext.rawAbiMode)
        decodePointerAliasSpec(headerContext.cratePointerTypeAliases[mappedKt])?.let { return it }
        val curRel = headerContext.currentRelativeSourceDir?.replace('/', '.')?.takeIf { it.isNotEmpty() }
        if (curRel != null) {
            decodePointerAliasSpec(headerContext.cratePointerTypeAliases["$curRel.$simple"])?.let { return it }
            decodePointerAliasSpec(headerContext.cratePointerTypeAliases["$curRel.$mappedKt"])?.let { return it }
        }
        return null
    }

    fun classifyParamAbiBridge(synType: SynType?, renderedKotlinType: String): CAbiBridgeKind =
        classifyAbiBridge(
            synType = synType,
            renderedKotlinType = renderedKotlinType,
            cfgProfile = activeCfgProfile,
            resolveNumericKind = resolveNumericKind,
            resolveTypeAliasTarget = resolveTypeAliasTarget,
            resolvePointerAlias = ::lookupCrossFilePointerAlias,
        )

    fun classifyReturnAbiBridge(synType: SynType?, renderedKotlinType: String): CAbiBridgeKind =
        classifyAbiBridge(
            synType = synType,
            renderedKotlinType = renderedKotlinType,
            cfgProfile = activeCfgProfile,
            resolveNumericKind = resolveNumericKind,
            resolveTypeAliasTarget = resolveTypeAliasTarget,
            resolvePointerAlias = ::lookupCrossFilePointerAlias,
        )

    fun hasInteropContent(): Boolean =
        preambles.isNotEmpty() || foreignFunctions.isNotEmpty() || inlineFunctions.isNotEmpty()

    fun recordForeignModAttributes(attrs: List<Attribute>) {
        for (attr in attrs) {
            if (attr.meta is Meta.List && attr.path().isIdent("link")) {
                attr.parseNestedMeta { nested ->
                    if (nested.path.isIdent("name")) {
                        val valueStream = nested.value().getOrElse { return@parseNestedMeta SynResult.failure(it) }
                        val expr = ExprParse.parse(valueStream).getOrElse { return@parseNestedMeta SynResult.failure(it) }
                        val exprLit = (expr as? Expr.Lit)?.lit
                        if (exprLit is Lit.Str) {
                            val libName = exprLit.value.value().trim()
                            if (libName.isNotEmpty() && libName != "c") {
                                linkLibraries += libName
                            }
                        }
                    } else if (nested.input.peek(EqPeek)) {
                        val valueStream = nested.value().getOrElse { return@parseNestedMeta SynResult.failure(it) }
                        ExprParse.parse(valueStream).getOrElse { return@parseNestedMeta SynResult.failure(it) }
                    }
                    SynResult.success(Unit)
                }
            }
        }
    }

    fun recordTopLevelInlineMacro(mac: Macro): Boolean {
        val lastSeg = mac.path.segments.last() ?: return false
        val macroName = lastSeg.ident.toString()
        if (macroName != "cpp" && macroName != "c" && macroName != "inline_c") return false
        if (macroName == "cpp") {
            usesCppLanguage = true
        }
        val trees = mac.tokens.clone().toList()
        val rawChunk =
            if (trees.size == 1 && trees[0] is TokenTree.Group && (trees[0] as TokenTree.Group).value.delimiter() == Delimiter.Brace) {
                extractGroupInnerSource(source, trees[0] as TokenTree.Group)
            } else {
                extractStreamSource(source, trees)
            }.trim()
        if (rawChunk.isNotEmpty()) {
            preambles += rawChunk
            if (looksLikeCppCode(rawChunk)) {
                usesCppLanguage = true
            }
        }
        return true
    }

    fun recordForeignFunction(
        visibilityPrefix: String,
        kotlinName: String,
        rawRustName: String,
        attrs: List<Attribute>,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnSynType: SynType?,
        isVariadic: Boolean,
    ) {
        val linkName = extractLinkNameAttribute(attrs) ?: rawRustName.removePrefix("r#")
        val retBridge = classifyReturnAbiBridge(returnSynType, kotlinReturnType)
        val cRet = cTypeForBridge(retBridge)
        val wrapperName = "${cSymbolPrefix}_${sanitizeCSymbolPart(kotlinName)}"
        foreignFunctions +=
            ForeignFnInteropSpec(
                visibilityPrefix = visibilityPrefix,
                kotlinName = kotlinName,
                cSymbolName = linkName,
                wrapperSymbolName = wrapperName,
                params = params,
                kotlinReturnType = kotlinReturnType,
                cReturnType = cRet,
                returnBridgeKind = retBridge,
                isVariadic = isVariadic,
            )
    }

    fun recordInlineExpressionMacro(
        mac: Macro,
        enclosingFnKotlinName: String?,
        renderRustType: (SynType, Boolean) -> String,
        resolveLocalExprName: (String) -> String,
    ): String? {
        val lastSeg = mac.path.segments.last() ?: return null
        val macroName = lastSeg.ident.toString()
        if (macroName != "cpp" && macroName != "c") return null
        val parsed = parseInlineCMacro(source, mac.tokens.clone(), isCpp = macroName == "cpp") ?: return null
        if (parsed.isCpp || looksLikeCppCode(parsed.rawBody)) {
            usesCppLanguage = true
        }
        val idx = inlineCounter++
        val basePrefix = enclosingFnKotlinName?.takeIf { it.isNotEmpty() } ?: "inlineBlock"
        val suffixTag = if (parsed.isCpp) "Cpp" else "C"
        val helperKtName = if (idx == 0) "${basePrefix}$suffixTag" else "${basePrefix}$suffixTag$idx"
        val wrapperSymbol = "${cSymbolPrefix}_inline_${idx}_${sanitizeCSymbolPart(basePrefix)}"

        val paramSpecs =
            parsed.captureArgs.mapIndexed { pIdx, cap ->
                val ktParamName = rustValueIdentToKotlin(cap.rawRustIdent).ifEmpty { "arg$pIdx" }
                val cParamName = cap.rawRustIdent.removePrefix("r#").ifEmpty { "arg$pIdx" }
                val explicitC = cap.explicitCType?.trim()
                val ktType =
                    when {
                        cap.rustType != null -> renderRustType(cap.rustType, true)
                        explicitC != null ->
                            inferKotlinTypeFromCType(
                                cType = explicitC,
                                isReturn = false,
                                rawAbiMode = headerContext.rawAbiMode,
                                cfgProfile = activeCfgProfile,
                            )
                        else -> "Long"
                    }
                val bridge =
                    when {
                        cap.rustType != null -> classifyParamAbiBridge(cap.rustType, ktType)
                        explicitC != null -> classifyExplicitCTypeBridge(explicitC, activeCfgProfile)
                        else -> CAbiBridgeKind.I64
                    }
                val finalCType = explicitC?.takeIf { it.isNotEmpty() } ?: cTypeForBridge(bridge)
                InteropParamSpec(
                    kotlinName = ktParamName,
                    cName = cParamName,
                    kotlinType = ktType,
                    cType = finalCType,
                    bridgeKind = bridge,
                )
            }

        val ktReturnType =
            when {
                parsed.returnRustType != null -> renderRustType(parsed.returnRustType, false)
                parsed.explicitCReturnType != null ->
                    inferKotlinTypeFromCType(
                        cType = parsed.explicitCReturnType,
                        isReturn = true,
                        rawAbiMode = headerContext.rawAbiMode,
                        cfgProfile = activeCfgProfile,
                    )
                else -> "Unit"
            }
        val retBridge =
            when {
                parsed.returnRustType != null -> classifyReturnAbiBridge(parsed.returnRustType, ktReturnType)
                parsed.explicitCReturnType != null -> classifyExplicitCTypeBridge(parsed.explicitCReturnType, activeCfgProfile)
                else -> CAbiBridgeKind.VOID
            }
        val finalCRetType = parsed.explicitCReturnType?.takeIf { it.isNotEmpty() } ?: cTypeForBridge(retBridge)

        inlineFunctions +=
            InlineCFunctionSpec(
                visibilityPrefix = "internal ",
                kotlinName = helperKtName,
                wrapperSymbolName = wrapperSymbol,
                params = paramSpecs,
                kotlinReturnType = ktReturnType,
                cReturnType = finalCRetType,
                returnBridgeKind = retBridge,
                rawBody = parsed.rawBody,
                isCpp = parsed.isCpp,
            )

        val callArgs = parsed.captureArgs.map { cap -> resolveLocalExprName(cap.rawRustIdent.removePrefix("r#")) }
        return "$helperKtName(${callArgs.joinToString(", ")})"
    }

    fun renderInlineExpectDeclarations(): List<String> =
        inlineFunctions.map { fn ->
            val paramsStr = fn.params.joinToString(", ") { "${it.kotlinName}: ${it.kotlinType}" }
            val retSuffix = if (fn.kotlinReturnType == "Unit") "" else ": ${fn.kotlinReturnType}"
            "${fn.visibilityPrefix}expect fun ${fn.kotlinName}($paramsStr)$retSuffix"
        }

    fun emitCompanionFiles(): List<CompanionInteropFile> {
        if (!hasInteropContent()) return emptyList()

        val stemSnake = computeStemSnake(headerContext)
        val stemPascal = KotlinNamingPolicy.Default.toTypeIdentifier(stemSnake)
        val headerFileName = "${stemSnake}_wrapper.h"
        val sourceExt = if (usesCppLanguage) "cpp" else "c"
        val sourceFileName = "${stemSnake}_wrapper.$sourceExt"
        val defFileName = "$stemPascal.def"
        val nativeActualsFileName = "${stemPascal}NativeActuals.kt"
        val pkgName = headerContext.packageName
        val cinteropPkg = if (!pkgName.isNullOrBlank()) "$pkgName.cinterop" else "ffi.cinterop"

        val defContent = buildDefFileContent(cinteropPkg, headerFileName, stemSnake)
        val headerContent = buildCHeaderContent(stemSnake)
        val cSourceContent = buildCOrCppSourceContent(headerFileName)
        val nativeActualsContent = buildNativeActualsKotlinContent(pkgName, cinteropPkg)

        return listOf(
            CompanionInteropFile(
                relativePath = defFileName,
                kind = CompanionInteropKind.CInteropDef,
                content = defContent,
            ),
            CompanionInteropFile(
                relativePath = headerFileName,
                kind = CompanionInteropKind.CHeader,
                content = headerContent,
            ),
            CompanionInteropFile(
                relativePath = sourceFileName,
                kind = if (usesCppLanguage) CompanionInteropKind.CppSource else CompanionInteropKind.CSource,
                content = cSourceContent,
            ),
            CompanionInteropFile(
                relativePath = nativeActualsFileName,
                kind = CompanionInteropKind.NativeKotlinActuals,
                content = nativeActualsContent,
            ),
        )
    }

    private fun buildDefFileContent(
        cinteropPkg: String,
        headerFileName: String,
        stemSnake: String,
    ): String {
        val sb = StringBuilder()
        sb.append("package = ").append(cinteropPkg).append('\n')
        sb.append("headers = ").append(headerFileName).append('\n')
        sb.append("headerFilter = ").append(headerFileName).append('\n')
        sb.append("compilerOpts = -Isrc/native/interop/cinterop\n")
        sb.append("staticLibraries = ").append(stemSnake).append("_wrapper.a\n")
        if (linkLibraries.isNotEmpty()) {
            val flags = linkLibraries.joinToString(" ") { "-l$it" }
            sb.append("linkerOpts = ").append(flags).append('\n')
        }
        val foreignPtrFns =
            foreignFunctions
                .filter { !it.isVariadic && requiresNoStringConversion(it.params) }
                .map { it.wrapperSymbolName }
        val inlinePtrFns =
            inlineFunctions
                .filter { fn ->
                    requiresNoStringConversion(fn.params) ||
                        fn.params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR }
                }.map { it.wrapperSymbolName }
        val pointerStringFns = (foreignPtrFns + inlinePtrFns).distinct()
        if (pointerStringFns.isNotEmpty()) {
            sb.append("noStringConversion = ").append(pointerStringFns.joinToString(" ")).append('\n')
        }
        return sb.toString()
    }

    private fun buildCHeaderContent(stemSnake: String): String {
        val guard = "${cSymbolPrefix.uppercase()}_${stemSnake.uppercase()}_WRAPPER_H"
        val sb = StringBuilder()
        sb.append("#ifndef ").append(guard).append('\n')
        sb.append("#define ").append(guard).append("\n\n")
        sb.append("#include <stdbool.h>\n")
        sb.append("#include <stddef.h>\n")
        sb.append("#include <stdint.h>\n\n")
        sb.append("#ifdef __cplusplus\n")
        sb.append("extern \"C\" {\n")
        sb.append("#endif\n\n")

        val emittedPrototypes = linkedSetOf<String>()
        for (fn in foreignFunctions) {
            if (fn.isVariadic) continue
            if (!emittedPrototypes.add(fn.wrapperSymbolName)) continue
            val paramsText =
                if (fn.params.isEmpty()) {
                    "void"
                } else {
                    fn.params.joinToString(", ") { "${cHeaderTypeForParam(it)} ${it.cName}" }
                }
            sb.append("${cHeaderTypeForReturn(fn)} ${fn.wrapperSymbolName}($paramsText);\n")
        }
        for (fn in inlineFunctions) {
            if (!emittedPrototypes.add(fn.wrapperSymbolName)) continue
            val paramsText =
                if (fn.params.isEmpty()) {
                    "void"
                } else {
                    fn.params.joinToString(", ") { "${cHeaderTypeForParam(it)} ${it.cName}" }
                }
            sb.append("${cHeaderTypeForInlineReturn(fn)} ${fn.wrapperSymbolName}($paramsText);\n")
        }

        sb.append("\n#ifdef __cplusplus\n")
        sb.append("}\n")
        sb.append("#endif\n\n")
        sb.append("#endif /* ").append(guard).append(" */\n")
        return sb.toString()
    }

    private fun buildCOrCppSourceContent(headerFileName: String): String {
        val sb = StringBuilder()
        sb.append("#include \"").append(headerFileName).append("\"\n")
        sb.append("#include <errno.h>\n")

        val preambleIncludes = extractIncludedHeadersFromPreambles(preambles)
        val inferredSpecs = inferRequiredHeaders(foreignFunctions, inlineFunctions, explicitHeaders, preambleIncludes)

        val unguardedHeaders = linkedSetOf<String>()
        val guardedGroups = linkedMapOf<String, MutableSet<String>>()
        val winFallbackGroups = linkedSetOf<String>()

        for (spec in inferredSpecs) {
            if (spec.guardCondition == null) {
                unguardedHeaders += spec.header
            } else {
                guardedGroups.getOrPut(spec.guardCondition) { linkedSetOf() } += spec.header
                winFallbackGroups.addAll(spec.windowsFallbackHeaders)
            }
        }

        for (hdr in unguardedHeaders) {
            if (hdr !in preambleIncludes) {
                sb.append("#include ").append(formatIncludeTarget(hdr)).append('\n')
            }
        }
        if (winFallbackGroups.isNotEmpty()) {
            sb.append("#ifdef _WIN32\n")
            for (hdr in winFallbackGroups) {
                if (hdr !in preambleIncludes) {
                    sb.append("#include ").append(formatIncludeTarget(hdr)).append('\n')
                }
            }
            sb.append("#endif\n")
        }
        for ((cond, hdrs) in guardedGroups) {
            val filtered = hdrs.filter { it !in preambleIncludes }
            if (filtered.isEmpty()) continue
            sb.append("#if ").append(cond).append('\n')
            for (hdr in filtered) {
                sb.append("#include ").append(formatIncludeTarget(hdr)).append('\n')
            }
            sb.append("#endif\n")
        }
        sb.append('\n')

        if (preambles.isNotEmpty()) {
            for (chunk in preambles) {
                sb.append(chunk).append("\n\n")
            }
        }

        if (usesCppLanguage) {
            sb.append("extern \"C\" {\n\n")
        }

        val emittedImpls = linkedSetOf<String>()
        for (fn in foreignFunctions) {
            if (fn.isVariadic) continue
            if (!emittedImpls.add(fn.wrapperSymbolName)) continue
            sb.append(renderForeignWrapperCFunction(fn)).append('\n')
        }
        for (fn in inlineFunctions) {
            if (!emittedImpls.add(fn.wrapperSymbolName)) continue
            sb.append(renderInlineWrapperCFunction(fn)).append('\n')
        }

        if (usesCppLanguage) {
            sb.append("} /* extern \"C\" */\n")
        }
        return sb.toString()
    }

    private fun renderForeignWrapperCFunction(fn: ForeignFnInteropSpec): String {
        val sb = StringBuilder()
        val retType = cHeaderTypeForReturn(fn)
        val paramsText =
            if (fn.params.isEmpty()) {
                "void"
            } else {
                fn.params.joinToString(", ") { "${cHeaderTypeForParam(it)} ${it.cName}" }
            }
        sb.append("$retType ${fn.wrapperSymbolName}($paramsText) {\n")

        val symGuard = symbolPlatformGuard(fn.cSymbolName)
        val callArgs =
            fn.params.joinToString(", ") { param ->
                when (param.bridgeKind) {
                    CAbiBridgeKind.FILE_PTR -> "(FILE*)${param.cName}"
                    CAbiBridgeKind.DIR_PTR -> "(DIR*)${param.cName}"
                    else -> param.cName
                }
            }
        val callExpr = "${fn.cSymbolName}($callArgs)"

        if (symGuard != null) {
            sb.append("#if ").append(symGuard).append('\n')
        }
        when (fn.returnBridgeKind) {
            CAbiBridgeKind.VOID -> sb.append("    ").append(callExpr).append(";\n")
            CAbiBridgeKind.NEVER -> {
                sb.append("    ").append(callExpr).append(";\n")
            }
            else -> sb.append("    return ($retType)$callExpr;\n")
        }
        if (symGuard != null) {
            sb.append("#else\n")
            for (param in fn.params) {
                sb.append("    (void)").append(param.cName).append(";\n")
            }
            sb.append("    errno = ENOSYS;\n")
            if (fn.returnBridgeKind != CAbiBridgeKind.VOID && fn.returnBridgeKind != CAbiBridgeKind.NEVER) {
                sb.append("    return ").append(cDefaultFailureLiteral(fn.returnBridgeKind)).append(";\n")
            }
            sb.append("#endif\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderInlineWrapperCFunction(fn: InlineCFunctionSpec): String {
        val sb = StringBuilder()
        val retType = cHeaderTypeForInlineReturn(fn)
        val paramsText =
            if (fn.params.isEmpty()) {
                "void"
            } else {
                fn.params.joinToString(", ") { "${cHeaderTypeForParam(it)} ${it.cName}" }
            }
        sb.append("$retType ${fn.wrapperSymbolName}($paramsText) {\n")
        for (param in fn.params) {
            if (!containsCIdentifier(fn.rawBody, param.cName)) {
                sb.append("    (void)").append(param.cName).append(";\n")
            }
        }
        val bodyTrimmed = fn.rawBody.trim()
        if (bodyTrimmed.isNotEmpty()) {
            for (line in bodyTrimmed.lines()) {
                if (line.isBlank()) {
                    sb.append('\n')
                } else {
                    sb.append("    ").append(line.trimEnd()).append('\n')
                }
            }
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun buildNativeActualsKotlinContent(
        pkgName: String?,
        cinteropPkg: String,
    ): String {
        val sb = StringBuilder()
        sb.append("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)\n\n")
        if (!pkgName.isNullOrBlank()) {
            sb.append("package ").append(pkgName).append("\n\n")
        }
        val imports = linkedSetOf<String>()
        val libcNs = resolveLibcNamespace(headerContext)

        val actualBlocks = mutableListOf<String>()
        val emittedActuals = linkedSetOf<String>()

        for (fn in foreignFunctions) {
            if (fn.isVariadic) continue
            if (!emittedActuals.add(fn.kotlinName)) continue
            actualBlocks +=
                renderNativeActualFunction(
                    visibilityPrefix = fn.visibilityPrefix,
                    kotlinName = fn.kotlinName,
                    cinteropCallee = "$cinteropPkg.${fn.wrapperSymbolName}",
                    params = fn.params,
                    kotlinReturnType = fn.kotlinReturnType,
                    returnBridgeKind = fn.returnBridgeKind,
                    noStringConvEnabled = requiresNoStringConversion(fn.params),
                    libcNs = libcNs,
                    pkgName = pkgName,
                    importsOut = imports,
                )
        }
        for (fn in inlineFunctions) {
            if (!emittedActuals.add(fn.kotlinName)) continue
            val inlineNoStringConv =
                requiresNoStringConversion(fn.params) ||
                    fn.params.any { it.bridgeKind == CAbiBridgeKind.MUT_CSTR }
            actualBlocks +=
                renderNativeActualFunction(
                    visibilityPrefix = fn.visibilityPrefix,
                    kotlinName = fn.kotlinName,
                    cinteropCallee = "$cinteropPkg.${fn.wrapperSymbolName}",
                    params = fn.params,
                    kotlinReturnType = fn.kotlinReturnType,
                    returnBridgeKind = fn.returnBridgeKind,
                    noStringConvEnabled = inlineNoStringConv,
                    libcNs = libcNs,
                    pkgName = pkgName,
                    importsOut = imports,
                )
        }

        if (imports.isNotEmpty()) {
            for (imp in imports.sorted()) {
                sb.append("import ").append(imp).append('\n')
            }
            sb.append('\n')
        }

        sb.append(actualBlocks.joinToString("\n\n"))
        if (actualBlocks.isNotEmpty()) {
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun renderPointerParamToCPointer(
        param: InteropParamSpec,
        libcNs: String?,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val ktType = param.kotlinType
        val nonNullNumeric = if (!ktType.endsWith("?")) resolveNumericKind(ktType) else null
        if (headerContext.rawAbiMode || ktType == "Long" || nonNullNumeric != null) {
            importsOut += "kotlinx.cinterop.ByteVar"
            importsOut += "kotlinx.cinterop.toCPointer"
            return if (ktType == "Long" || nonNullNumeric == NumericKind.I64) {
                "${param.kotlinName}.toCPointer<ByteVar>()"
            } else {
                "${param.kotlinName}.toLong().toCPointer<ByteVar>()"
            }
        }
        if (ktType == "FILE?" || param.bridgeKind == CAbiBridgeKind.FILE_PTR) {
            importsOut += "kotlinx.cinterop.ByteVar"
            importsOut += "kotlinx.cinterop.toCPointer"
            if (libcNs != null && pkgName != libcNs && ktType.startsWith("FILE")) {
                importsOut += "$libcNs.FILE"
            }
            return if (ktType == "FILE") {
                "${param.kotlinName}.handle.toCPointer<ByteVar>()"
            } else {
                "${param.kotlinName}?.handle?.toCPointer<ByteVar>()"
            }
        }
        if (ktType == "DIR?" || param.bridgeKind == CAbiBridgeKind.DIR_PTR) {
            importsOut += "kotlinx.cinterop.ByteVar"
            importsOut += "kotlinx.cinterop.toCPointer"
            if (libcNs != null && pkgName != libcNs && ktType.startsWith("DIR")) {
                importsOut += "$libcNs.DIR"
            }
            return if (ktType == "DIR") {
                "${param.kotlinName}.handle.toCPointer<ByteVar>()"
            } else {
                "${param.kotlinName}?.handle?.toCPointer<ByteVar>()"
            }
        }
        importsOut += "kotlinx.cinterop.ByteVar"
        importsOut += "kotlinx.cinterop.toCPointer"
        if (libcNs != null && pkgName != libcNs && ktType.startsWith("COpaquePointer")) {
            importsOut += "$libcNs.COpaquePointer"
        }
        return if (ktType == "COpaquePointer") {
            "${param.kotlinName}.value.toCPointer<ByteVar>()"
        } else {
            "${param.kotlinName}?.value?.toCPointer<ByteVar>()"
        }
    }

    private fun renderScalarParamArg(
        param: InteropParamSpec,
        targetKind: NumericKind,
        convMethod: String,
    ): String {
        val paramKind = if (!param.kotlinType.endsWith("?")) resolveNumericKind(param.kotlinType) else null
        return if (paramKind == targetKind) {
            param.kotlinName
        } else {
            "${param.kotlinName}.$convMethod()"
        }
    }

    private fun renderNativeActualFunction(
        visibilityPrefix: String,
        kotlinName: String,
        cinteropCallee: String,
        params: List<InteropParamSpec>,
        kotlinReturnType: String,
        returnBridgeKind: CAbiBridgeKind,
        noStringConvEnabled: Boolean,
        libcNs: String?,
        pkgName: String?,
        importsOut: MutableSet<String>,
    ): String {
        val paramsSig = params.joinToString(", ") { "${it.kotlinName}: ${it.kotlinType}" }
        val retSuffix = if (kotlinReturnType == "Unit") "" else ": $kotlinReturnType"
        val convertedArgs =
            params.joinToString(", ") { param ->
                when (param.bridgeKind) {
                    CAbiBridgeKind.CONST_CSTR -> {
                        when {
                            !noStringConvEnabled -> param.kotlinName
                            param.kotlinType == "String?" -> {
                                importsOut += "kotlinx.cinterop.cstr"
                                "${param.kotlinName}?.cstr"
                            }
                            param.kotlinType == "String" -> {
                                importsOut += "kotlinx.cinterop.cstr"
                                "${param.kotlinName}.cstr"
                            }
                            else -> renderPointerParamToCPointer(param, libcNs, pkgName, importsOut)
                        }
                    }
                    CAbiBridgeKind.MUT_CSTR,
                    CAbiBridgeKind.OPAQUE_PTR,
                    CAbiBridgeKind.FILE_PTR,
                    CAbiBridgeKind.DIR_PTR,
                    -> renderPointerParamToCPointer(param, libcNs, pkgName, importsOut)
                    CAbiBridgeKind.I8 -> renderScalarParamArg(param, NumericKind.I8, "toByte")
                    CAbiBridgeKind.U8 -> renderScalarParamArg(param, NumericKind.U8, "toUByte")
                    CAbiBridgeKind.I16 -> renderScalarParamArg(param, NumericKind.I16, "toShort")
                    CAbiBridgeKind.U16 -> renderScalarParamArg(param, NumericKind.U16, "toUShort")
                    CAbiBridgeKind.I32 -> renderScalarParamArg(param, NumericKind.I32, "toInt")
                    CAbiBridgeKind.U32 -> renderScalarParamArg(param, NumericKind.U32, "toUInt")
                    CAbiBridgeKind.I64 -> renderScalarParamArg(param, NumericKind.I64, "toLong")
                    CAbiBridgeKind.U64 -> renderScalarParamArg(param, NumericKind.U64, "toULong")
                    else -> param.kotlinName
                }
            }
        val rawCall = "$cinteropCallee($convertedArgs)"
        val retNonNullNumeric = if (!kotlinReturnType.endsWith("?")) resolveNumericKind(kotlinReturnType) else null
        val isRawPointerReturn = headerContext.rawAbiMode || kotlinReturnType == "Long" || retNonNullNumeric != null
        val bodyExpr =
            when (returnBridgeKind) {
                CAbiBridgeKind.VOID -> rawCall
                CAbiBridgeKind.CONST_CSTR,
                CAbiBridgeKind.MUT_CSTR,
                CAbiBridgeKind.OPAQUE_PTR,
                -> {
                    importsOut += "kotlinx.cinterop.toLong"
                    if (isRawPointerReturn) {
                        "$rawCall?.toLong() ?: 0L"
                    } else {
                        if (libcNs != null && pkgName != libcNs) {
                            importsOut += "$libcNs.COpaquePointer"
                        }
                        "$rawCall?.let { COpaquePointer(it.toLong()) }"
                    }
                }
                CAbiBridgeKind.FILE_PTR -> {
                    importsOut += "kotlinx.cinterop.toLong"
                    if (isRawPointerReturn) {
                        "$rawCall?.toLong() ?: 0L"
                    } else {
                        if (libcNs != null && pkgName != libcNs) {
                            importsOut += "$libcNs.FILE"
                        }
                        "$rawCall?.let { FILE(it.toLong()) }"
                    }
                }
                CAbiBridgeKind.DIR_PTR -> {
                    importsOut += "kotlinx.cinterop.toLong"
                    if (isRawPointerReturn) {
                        "$rawCall?.toLong() ?: 0L"
                    } else {
                        if (libcNs != null && pkgName != libcNs) {
                            importsOut += "$libcNs.DIR"
                        }
                        "$rawCall?.let { DIR(it.toLong()) }"
                    }
                }
                else -> rawCall
            }
        return when {
            kotlinReturnType == "Unit" ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig) {\n    $bodyExpr\n}"
            returnBridgeKind == CAbiBridgeKind.NEVER ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig): Nothing {\n    $rawCall\n    error(\"Unreachable noreturn C function $kotlinName\")\n}"
            else ->
                "${visibilityPrefix}actual fun $kotlinName($paramsSig)$retSuffix =\n    $bodyExpr"
        }
    }

    private fun cHeaderTypeForParam(param: InteropParamSpec): String =
        when (param.bridgeKind) {
            CAbiBridgeKind.FILE_PTR, CAbiBridgeKind.DIR_PTR, CAbiBridgeKind.OPAQUE_PTR -> "void*"
            else -> cTypeForBridge(param.bridgeKind)
        }

    private fun cHeaderTypeForReturn(fn: ForeignFnInteropSpec): String =
        when (fn.returnBridgeKind) {
            CAbiBridgeKind.FILE_PTR, CAbiBridgeKind.DIR_PTR, CAbiBridgeKind.OPAQUE_PTR -> "void*"
            else -> cTypeForBridge(fn.returnBridgeKind)
        }

    private fun cHeaderTypeForInlineReturn(fn: InlineCFunctionSpec): String =
        when (fn.returnBridgeKind) {
            CAbiBridgeKind.FILE_PTR, CAbiBridgeKind.DIR_PTR, CAbiBridgeKind.OPAQUE_PTR -> "void*"
            else -> cTypeForBridge(fn.returnBridgeKind)
        }

    companion object {
        private val POINTEE_STOP_IDENTS = setOf("c_char", "FILE", "DIR", "c_void")

        private fun requiresNoStringConversion(params: List<InteropParamSpec>): Boolean =
            params.any { param ->
                param.bridgeKind == CAbiBridgeKind.CONST_CSTR &&
                    param.kotlinType != "String?" &&
                    param.kotlinType != "String"
            }

        private fun unwrapOuterAliases(
            ty: SynType,
            resolveTypeAliasTarget: (String) -> SynType?,
        ): SynType {
            var cur = unwrapType(ty)
            var depth = 0
            while (depth < 8 && cur is SynType.Path && cur.qself == null) {
                val lastSeg = cur.path.segments.last() ?: break
                if (lastSeg.arguments !is PathArguments.None) break
                val rawIdent = lastSeg.ident.toString().removePrefix("r#")
                val target = resolveTypeAliasTarget(rawIdent) ?: break
                cur = unwrapType(target)
                depth++
            }
            return cur
        }

        private fun unwrapPointeeAliases(
            ty: SynType,
            resolveTypeAliasTarget: (String) -> SynType?,
        ): SynType {
            var cur = unwrapType(ty)
            var depth = 0
            while (depth < 8 && cur is SynType.Path && cur.qself == null) {
                val lastSeg = cur.path.segments.last() ?: break
                if (lastSeg.arguments !is PathArguments.None) break
                val rawIdent = lastSeg.ident.toString().removePrefix("r#")
                if (rawIdent in POINTEE_STOP_IDENTS) break
                val target = resolveTypeAliasTarget(rawIdent) ?: break
                cur = unwrapType(target)
                depth++
            }
            return cur
        }

        internal fun classifyAbiBridge(
            synType: SynType?,
            renderedKotlinType: String,
            cfgProfile: CfgTargetProfile = TranslationSession.cfgTargetProfile,
            resolveNumericKind: (String) -> NumericKind? = { null },
            resolveTypeAliasTarget: (String) -> SynType? = { null },
            resolvePointerAlias: (String) -> Pair<Boolean, String>? = { null },
        ): CAbiBridgeKind {
            if (synType == null || renderedKotlinType == "Unit") return CAbiBridgeKind.VOID
            val unwrapped = unwrapOuterAliases(synType, resolveTypeAliasTarget)
            if (unwrapped is SynType.Never || renderedKotlinType == "Nothing") return CAbiBridgeKind.NEVER
            if (unwrapped is SynType.Ptr) {
                val elem = unwrapPointeeAliases(unwrapped.elem, resolveTypeAliasTarget)
                val elemPath = (elem as? SynType.Path)?.takeIf { it.qself == null }?.path
                val elemSeg = elemPath?.segments?.last()
                val elemIdent = elemSeg?.ident?.toString()?.removePrefix("r#")
                return when (elemIdent) {
                    "c_char" -> {
                        if (unwrapped.mutability is PointerMutability.Mut) {
                            CAbiBridgeKind.MUT_CSTR
                        } else {
                            CAbiBridgeKind.CONST_CSTR
                        }
                    }
                    "FILE" -> CAbiBridgeKind.FILE_PTR
                    "DIR" -> CAbiBridgeKind.DIR_PTR
                    else -> CAbiBridgeKind.OPAQUE_PTR
                }
            }
            if (unwrapped is SynType.Path && unwrapped.qself == null) {
                val pathIdent =
                    unwrapped.path.segments
                        .last()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                val ptrAlias =
                    (pathIdent?.let(resolvePointerAlias))
                        ?: resolvePointerAlias(renderedKotlinType)
                if (ptrAlias != null) {
                    val (isMut, pointeeIdent) = ptrAlias
                    return when (pointeeIdent) {
                        "c_char" -> if (isMut) CAbiBridgeKind.MUT_CSTR else CAbiBridgeKind.CONST_CSTR
                        "FILE" -> CAbiBridgeKind.FILE_PTR
                        "DIR" -> CAbiBridgeKind.DIR_PTR
                        else -> CAbiBridgeKind.OPAQUE_PTR
                    }
                }
            }
            if (!renderedKotlinType.endsWith("?")) {
                when (resolveNumericKind(renderedKotlinType)) {
                    NumericKind.I8 -> return CAbiBridgeKind.I8
                    NumericKind.U8 -> return CAbiBridgeKind.U8
                    NumericKind.I16 -> return CAbiBridgeKind.I16
                    NumericKind.U16 -> return CAbiBridgeKind.U16
                    NumericKind.I32 -> return CAbiBridgeKind.I32
                    NumericKind.U32 -> return CAbiBridgeKind.U32
                    NumericKind.I64 -> return CAbiBridgeKind.I64
                    NumericKind.U64 -> return CAbiBridgeKind.U64
                    NumericKind.F32 -> return CAbiBridgeKind.F32
                    NumericKind.F64 -> return CAbiBridgeKind.F64
                    NumericKind.I128, NumericKind.U128, null -> {}
                }
            }
            val ptrWidth = cfgProfile.pointerWidth
            val isWindows =
                cfgProfile.targetOs == "windows" ||
                    cfgProfile.targetFamilies?.contains("windows") == true
            val ptrSigned =
                when (ptrWidth) {
                    64 -> CAbiBridgeKind.I64
                    16 -> CAbiBridgeKind.I16
                    else -> CAbiBridgeKind.I32
                }
            val ptrUnsigned =
                when (ptrWidth) {
                    64 -> CAbiBridgeKind.U64
                    16 -> CAbiBridgeKind.U16
                    else -> CAbiBridgeKind.U32
                }
            val cLongSigned = if (ptrWidth == 64 && !isWindows) CAbiBridgeKind.I64 else CAbiBridgeKind.I32
            val cLongUnsigned = if (ptrWidth == 64 && !isWindows) CAbiBridgeKind.U64 else CAbiBridgeKind.U32
            val simpleType = renderedKotlinType.substringAfterLast('.')
            if (isCPointerWidthSignedTypeName(simpleType)) return ptrSigned
            if (isCPointerWidthUnsignedTypeName(simpleType)) return ptrUnsigned
            return when (simpleType) {
                "Boolean" -> CAbiBridgeKind.BOOL
                "Byte", "CChar", "c_char", "CSchar", "c_schar", "Int8T", "int8_t" -> CAbiBridgeKind.I8
                "UByte", "CUchar", "c_uchar", "Uint8T", "uint8_t" -> CAbiBridgeKind.U8
                "Short", "CShort", "c_short", "Int16T", "int16_t" -> CAbiBridgeKind.I16
                "UShort", "CUshort", "c_ushort", "Uint16T", "uint16_t", "SaFamilyT", "sa_family_t", "InPortT", "in_port_t" ->
                    CAbiBridgeKind.U16
                "Int", "CInt", "c_int", "Int32T", "int32_t", "PidT", "pid_t", "ClockidT", "clockid_t", "WcharT", "wchar_t" ->
                    CAbiBridgeKind.I32
                "UInt", "CUint", "c_uint", "Uint32T", "uint32_t", "UidT", "uid_t", "GidT", "gid_t",
                "ModeT", "mode_t", "SocklenT", "socklen_t", "InAddrT", "in_addr_t",
                -> CAbiBridgeKind.U32
                "CLong", "c_long" -> cLongSigned
                "CUlong", "c_ulong" -> cLongUnsigned
                "Long", "CLonglong", "c_longlong", "OffT", "off_t", "TimeT", "time_t", "Int64T", "int64_t" -> CAbiBridgeKind.I64
                "ULong", "CUlonglong", "c_ulonglong", "Uint64T", "uint64_t" -> CAbiBridgeKind.U64
                "Float", "CFloat", "c_float" -> CAbiBridgeKind.F32
                "Double", "CDouble", "c_double" -> CAbiBridgeKind.F64
                "String", "String?" -> CAbiBridgeKind.CONST_CSTR
                "FILE", "FILE?" -> CAbiBridgeKind.FILE_PTR
                "DIR", "DIR?" -> CAbiBridgeKind.DIR_PTR
                "COpaquePointer", "COpaquePointer?" -> CAbiBridgeKind.OPAQUE_PTR
                else -> if (renderedKotlinType.endsWith("?")) CAbiBridgeKind.OPAQUE_PTR else CAbiBridgeKind.I32
            }
        }

        private fun classifyExplicitCTypeBridge(
            cType: String,
            cfgProfile: CfgTargetProfile = TranslationSession.cfgTargetProfile,
        ): CAbiBridgeKind {
            val norm = cType.replace(" ", "")
            val ptrWidth = cfgProfile.pointerWidth
            val isWindows =
                cfgProfile.targetOs == "windows" ||
                    cfgProfile.targetFamilies?.contains("windows") == true
            val ptrSigned = if (ptrWidth == 64) CAbiBridgeKind.I64 else CAbiBridgeKind.I32
            val ptrUnsigned = if (ptrWidth == 64) CAbiBridgeKind.U64 else CAbiBridgeKind.U32
            val cLongSigned = if (ptrWidth == 64 && !isWindows) CAbiBridgeKind.I64 else CAbiBridgeKind.I32
            val cLongUnsigned = if (ptrWidth == 64 && !isWindows) CAbiBridgeKind.U64 else CAbiBridgeKind.U32
            return when {
                norm == "void" -> CAbiBridgeKind.VOID
                norm == "bool" || norm == "_Bool" -> CAbiBridgeKind.BOOL
                norm == "constchar*" -> CAbiBridgeKind.CONST_CSTR
                norm == "char*" -> CAbiBridgeKind.MUT_CSTR
                norm == "FILE*" || norm == "constFILE*" -> CAbiBridgeKind.FILE_PTR
                norm == "DIR*" || norm == "constDIR*" -> CAbiBridgeKind.DIR_PTR
                norm.endsWith("*") -> CAbiBridgeKind.OPAQUE_PTR
                norm == "int8_t" || norm == "signedchar" || norm == "char" -> CAbiBridgeKind.I8
                norm == "uint8_t" || norm == "unsignedchar" -> CAbiBridgeKind.U8
                norm == "int16_t" || norm == "short" -> CAbiBridgeKind.I16
                norm == "uint16_t" || norm == "unsignedshort" -> CAbiBridgeKind.U16
                norm == "uint32_t" || norm == "unsignedint" || norm == "unsigned" -> CAbiBridgeKind.U32
                norm == "long" -> cLongSigned
                norm == "unsignedlong" -> cLongUnsigned
                norm == "ssize_t" || norm == "intptr_t" || norm == "ptrdiff_t" -> ptrSigned
                norm == "size_t" || norm == "uintptr_t" -> ptrUnsigned
                norm == "int64_t" || norm == "longlong" -> CAbiBridgeKind.I64
                norm == "uint64_t" || norm == "unsignedlonglong" -> CAbiBridgeKind.U64
                norm == "float" -> CAbiBridgeKind.F32
                norm == "double" -> CAbiBridgeKind.F64
                else -> CAbiBridgeKind.I32
            }
        }

        private fun inferKotlinTypeFromCType(
            cType: String,
            isReturn: Boolean = false,
            rawAbiMode: Boolean = false,
            cfgProfile: CfgTargetProfile = TranslationSession.cfgTargetProfile,
        ): String =
            when (classifyExplicitCTypeBridge(cType, cfgProfile)) {
                CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "Unit"
                CAbiBridgeKind.BOOL -> "Boolean"
                CAbiBridgeKind.I8 -> "Byte"
                CAbiBridgeKind.U8 -> "UByte"
                CAbiBridgeKind.I16 -> "Short"
                CAbiBridgeKind.U16 -> "UShort"
                CAbiBridgeKind.I32 -> "Int"
                CAbiBridgeKind.U32 -> "UInt"
                CAbiBridgeKind.I64 -> "Long"
                CAbiBridgeKind.U64 -> "ULong"
                CAbiBridgeKind.F32 -> "Float"
                CAbiBridgeKind.F64 -> "Double"
                CAbiBridgeKind.CONST_CSTR ->
                    when {
                        rawAbiMode -> "Long"
                        isReturn -> "COpaquePointer?"
                        else -> "String?"
                    }
                CAbiBridgeKind.MUT_CSTR -> if (rawAbiMode) "Long" else "COpaquePointer?"
                CAbiBridgeKind.FILE_PTR -> if (rawAbiMode) "Long" else "FILE?"
                CAbiBridgeKind.DIR_PTR -> if (rawAbiMode) "Long" else "DIR?"
                CAbiBridgeKind.OPAQUE_PTR -> if (rawAbiMode) "Long" else "COpaquePointer?"
            }

        private fun cTypeForBridge(kind: CAbiBridgeKind): String =
            when (kind) {
                CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> "void"
                CAbiBridgeKind.BOOL -> "bool"
                CAbiBridgeKind.I8 -> "int8_t"
                CAbiBridgeKind.U8 -> "uint8_t"
                CAbiBridgeKind.I16 -> "int16_t"
                CAbiBridgeKind.U16 -> "uint16_t"
                CAbiBridgeKind.I32 -> "int32_t"
                CAbiBridgeKind.U32 -> "uint32_t"
                CAbiBridgeKind.I64 -> "int64_t"
                CAbiBridgeKind.U64 -> "uint64_t"
                CAbiBridgeKind.F32 -> "float"
                CAbiBridgeKind.F64 -> "double"
                CAbiBridgeKind.CONST_CSTR -> "const char*"
                CAbiBridgeKind.MUT_CSTR -> "char*"
                CAbiBridgeKind.OPAQUE_PTR, CAbiBridgeKind.FILE_PTR, CAbiBridgeKind.DIR_PTR -> "void*"
            }

        private fun cDefaultFailureLiteral(kind: CAbiBridgeKind): String =
            when (kind) {
                CAbiBridgeKind.VOID, CAbiBridgeKind.NEVER -> ""
                CAbiBridgeKind.BOOL -> "false"
                CAbiBridgeKind.U8, CAbiBridgeKind.U16, CAbiBridgeKind.U32, CAbiBridgeKind.U64 -> "0"
                CAbiBridgeKind.F32 -> "0.0f"
                CAbiBridgeKind.F64 -> "0.0"
                CAbiBridgeKind.CONST_CSTR,
                CAbiBridgeKind.MUT_CSTR,
                CAbiBridgeKind.OPAQUE_PTR,
                CAbiBridgeKind.FILE_PTR,
                CAbiBridgeKind.DIR_PTR,
                -> "NULL"
                CAbiBridgeKind.I8, CAbiBridgeKind.I16, CAbiBridgeKind.I32, CAbiBridgeKind.I64 -> "-1"
            }

        private fun unwrapType(ty: SynType): SynType =
            when (ty) {
                is SynType.Group -> unwrapType(ty.elem)
                is SynType.Paren -> unwrapType(ty.elem)
                else -> ty
            }

        internal fun resolveLibcNamespace(headerContext: FileHeaderContext): String? {
            if (headerContext.projectNamespace == "io.github.kotlinmania.libc") {
                return "io.github.kotlinmania.libc"
            }
            return headerContext.crateNamespaces["libc"]
        }

        internal fun extractLinkNameAttribute(attrs: List<Attribute>): String? {
            for (attr in attrs) {
                val meta = attr.meta
                if (meta is Meta.NameValue && meta.path.isIdent("link_name")) {
                    val expr = meta.value
                    if (expr is Expr.Lit && expr.lit is Lit.Str) {
                        return (expr.lit as Lit.Str).value.value()
                    }
                }
            }
            return null
        }

        private fun extractHeaderCommentsFromSource(source: String, out: MutableSet<String>) {
            for (line in source.lineSequence()) {
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("//")) continue
                var idx = 2
                while (idx < trimmed.length && (trimmed[idx] == '!' || trimmed[idx] == '/' || trimmed[idx] == '*' || trimmed[idx].isWhitespace())) {
                    idx++
                }
                if (!trimmed.regionMatches(idx, "Header:", 0, 7)) continue
                idx += 7
                while (idx < trimmed.length && (trimmed[idx].isWhitespace() || trimmed[idx] == '`')) {
                    idx++
                }
                val start = idx
                while (idx < trimmed.length && !trimmed[idx].isWhitespace() && trimmed[idx] != '`' && trimmed[idx] != '*') {
                    idx++
                }
                val hdr = trimmed.substring(start, idx).trim()
                if (hdr.endsWith(".h") || hdr.endsWith(".hpp")) {
                    out += hdr
                }
            }
        }

        private fun extractIncludedHeadersFromPreambles(preambles: List<String>): Set<String> {
            val found = linkedSetOf<String>()
            for (chunk in preambles) {
                for (line in chunk.lineSequence()) {
                    val trimmed = line.trimStart()
                    if (!trimmed.startsWith('#')) continue
                    val afterHash = trimmed.substring(1).trimStart()
                    if (!afterHash.startsWith("include")) continue
                    val afterInclude = afterHash.substring(7).trimStart()
                    if (afterInclude.isEmpty()) continue
                    val open = afterInclude[0]
                    val close =
                        when (open) {
                            '<' -> '>'
                            '"' -> '"'
                            else -> continue
                        }
                    val closeIdx = afterInclude.indexOf(close, startIndex = 1)
                    if (closeIdx > 1) {
                        found += afterInclude.substring(1, closeIdx)
                    }
                }
            }
            return found
        }

        private fun formatIncludeTarget(hdr: String): String =
            if (hdr.startsWith('<') || hdr.startsWith('"')) hdr else "<$hdr>"

        private fun looksLikeCppCode(code: String): Boolean =
            code.contains("std::") ||
                code.contains("#include <iostream>") ||
                code.contains("#include <vector>") ||
                code.contains("#include <string>") ||
                code.contains("namespace ") ||
                code.contains("reinterpret_cast<") ||
                code.contains("static_cast<")

        private fun computeStemSnake(headerContext: FileHeaderContext): String {
            val rawRel = headerContext.portLintSourcePath
            if (!rawRel.isNullOrBlank()) {
                val fileName = rawRel.replace('\\', '/').substringAfterLast('/')
                val stem = fileName.removeSuffix(".rs").removeSuffix(".RS")
                val clean = sanitizeCSymbolPart(stem)
                if (clean.isNotEmpty()) return clean
            }
            val pkgTail = headerContext.packageName?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
            return sanitizeCSymbolPart(pkgTail ?: "ffi")
        }

        private fun computeCSymbolPrefix(headerContext: FileHeaderContext): String {
            val nsSegment =
                headerContext.projectNamespace
                    ?.substringAfterLast('.')
                    ?.takeIf { it.isNotEmpty() }
                    ?: headerContext.packageName
                        ?.substringAfterLast('.')
                        ?.takeIf { it.isNotEmpty() }
                    ?: "ffi"
            val stem = computeStemSnake(headerContext)
            val cleanNs = sanitizeCSymbolPart(nsSegment)
            return if (cleanNs == stem) cleanNs else "${cleanNs}_$stem"
        }

        private fun sanitizeCSymbolPart(raw: String): String {
            val sb = StringBuilder(raw.length + 4)
            for ((idx, ch) in raw.withIndex()) {
                when {
                    ch.isUpperCase() -> {
                        if (idx > 0 && raw[idx - 1].isLowerCase()) sb.append('_')
                        sb.append(ch.lowercaseChar())
                    }
                    ch.isLowerCase() || ch.isDigit() -> sb.append(ch)
                    ch == '_' || ch == '-' -> if (sb.isNotEmpty() && sb.last() != '_') sb.append('_')
                }
            }
            val trimmed = sb.toString().trim('_')
            if (trimmed.isEmpty()) return "fn"
            return if (trimmed.first().isDigit()) "n_$trimmed" else trimmed
        }

        private fun containsCIdentifier(code: String, ident: String): Boolean {
            if (ident.isEmpty()) return false
            var idx = 0
            while (true) {
                val pos = code.indexOf(ident, idx)
                if (pos < 0) return false
                val beforeOk = pos == 0 || (!code[pos - 1].isLetterOrDigit() && code[pos - 1] != '_')
                val afterPos = pos + ident.length
                val afterOk = afterPos >= code.length || (!code[afterPos].isLetterOrDigit() && code[afterPos] != '_')
                if (beforeOk && afterOk) return true
                idx = pos + 1
            }
        }

        private fun extractGroupInnerSource(source: String, group: TokenTree.Group): String {
            val openEnd = group.value.spanOpen().endExclusiveOffset()
            val closeStart = group.value.spanClose().startOffset()
            if (openEnd >= 0 && closeStart >= openEnd && closeStart <= source.length) {
                return source.substring(openEnd, closeStart)
            }
            return group.value.stream().toString()
        }

        private fun extractStreamSource(source: String, trees: List<TokenTree>): String {
            if (trees.isEmpty()) return ""
            val start = tokenStartOffset(trees.first())
            val end = tokenEndOffset(trees.last())
            if (start >= 0 && end >= start && end <= source.length) {
                return source.substring(start, end)
            }
            return TokenStream.fromTokenTrees(trees).toString()
        }

        private fun tokenStartOffset(tt: TokenTree): Int =
            when (tt) {
                is TokenTree.Group -> tt.value.spanOpen().startOffset()
                is TokenTree.Ident -> tt.value.span().startOffset()
                is TokenTree.Punct -> tt.value.span().startOffset()
                is TokenTree.Literal -> tt.value.span().startOffset()
            }

        private fun tokenEndOffset(tt: TokenTree): Int =
            when (tt) {
                is TokenTree.Group -> tt.value.spanClose().endExclusiveOffset()
                is TokenTree.Ident -> tt.value.span().endExclusiveOffset()
                is TokenTree.Punct -> tt.value.span().endExclusiveOffset()
                is TokenTree.Literal -> tt.value.span().endExclusiveOffset()
            }

        private fun parseInlineCMacro(
            source: String,
            tokens: TokenStream,
            isCpp: Boolean,
        ): ParsedInlineCMacro? {
            val list = tokens.clone().toList()
            if (list.isEmpty()) return null
            var cursor = 0
            if (list[cursor] is TokenTree.Ident && (list[cursor] as TokenTree.Ident).value.toString() == "unsafe") {
                cursor++
            }
            val captureArgs = mutableListOf<InlineCaptureArg>()
            if (cursor < list.size &&
                list[cursor] is TokenTree.Group &&
                (list[cursor] as TokenTree.Group).value.delimiter() == Delimiter.Bracket
            ) {
                val bracketGroup = list[cursor] as TokenTree.Group
                val parts = MacroRulesFileExpander.splitMacroArguments(bracketGroup.value.stream())
                for (part in parts) {
                    parseSingleCaptureArg(part)?.let { captureArgs += it }
                }
                cursor++
            }

            var returnRustType: SynType? = null
            var explicitCReturnType: String? = null
            if (cursor + 1 < list.size &&
                (list[cursor] as? TokenTree.Punct)?.value?.asChar() == '-' &&
                (list[cursor + 1] as? TokenTree.Punct)?.value?.asChar() == '>'
            ) {
                cursor += 2
                val bodyGroupIdx =
                    (cursor until list.size).lastOrNull { idx ->
                        list[idx] is TokenTree.Group && (list[idx] as TokenTree.Group).value.delimiter() == Delimiter.Brace
                    } ?: return null
                val headerSlice = list.subList(cursor, bodyGroupIdx)
                val asIdx =
                    headerSlice.indexOfFirst { tt ->
                        tt is TokenTree.Ident && tt.value.toString() == "as"
                    }
                val rustTyTokens = if (asIdx >= 0) headerSlice.subList(0, asIdx) else headerSlice
                if (rustTyTokens.isNotEmpty()) {
                    returnRustType =
                        io.github.kotlinmania.syn
                            .parse2(SynType.Companion::parse, TokenStream.fromTokenTrees(rustTyTokens))
                            .getOrNull()
                }
                if (asIdx >= 0 && asIdx + 1 < headerSlice.size) {
                    val cLit = headerSlice[asIdx + 1] as? TokenTree.Literal
                    if (cLit != null) {
                        val parsedLit = Lit.new(cLit.value)
                        if (parsedLit is Lit.Str) {
                            explicitCReturnType = parsedLit.value.value()
                        }
                    }
                }
                cursor = bodyGroupIdx
            }

            if (cursor >= list.size) return null
            val lastTree = list[cursor]
            val rawBody =
                if (lastTree is TokenTree.Group && lastTree.value.delimiter() == Delimiter.Brace) {
                    extractGroupInnerSource(source, lastTree)
                } else {
                    extractStreamSource(source, list.subList(cursor, list.size))
                }
            return ParsedInlineCMacro(
                captureArgs = captureArgs,
                returnRustType = returnRustType,
                explicitCReturnType = explicitCReturnType,
                rawBody = rawBody,
                isCpp = isCpp,
            )
        }

        private fun parseSingleCaptureArg(tokens: List<TokenTree>): InlineCaptureArg? {
            if (tokens.isEmpty()) return null
            var idx = 0
            var isMut = false
            if (tokens[idx] is TokenTree.Ident && (tokens[idx] as TokenTree.Ident).value.toString() == "mut") {
                isMut = true
                idx++
            }
            if (idx >= tokens.size) return null
            val identTree = tokens[idx] as? TokenTree.Ident ?: return null
            val rawIdent = identTree.value.toString()
            idx++

            var rustType: SynType? = null
            var explicitCType: String? = null
            val asIdx =
                (idx until tokens.size).firstOrNull { i ->
                    tokens[i] is TokenTree.Ident && (tokens[i] as TokenTree.Ident).value.toString() == "as"
                }
            val typeEnd = asIdx ?: tokens.size
            if (idx < typeEnd && (tokens[idx] as? TokenTree.Punct)?.value?.asChar() == ':') {
                val tySlice = tokens.subList(idx + 1, typeEnd)
                if (tySlice.isNotEmpty()) {
                    rustType =
                        io.github.kotlinmania.syn
                            .parse2(SynType.Companion::parse, TokenStream.fromTokenTrees(tySlice))
                            .getOrNull()
                }
            }
            if (asIdx != null && asIdx + 1 < tokens.size) {
                val litTree = tokens[asIdx + 1] as? TokenTree.Literal
                if (litTree != null) {
                    val lit = Lit.new(litTree.value)
                    if (lit is Lit.Str) {
                        explicitCType = lit.value.value()
                    }
                }
            }
            return InlineCaptureArg(
                rawRustIdent = rawIdent,
                isMut = isMut,
                rustType = rustType,
                explicitCType = explicitCType,
            )
        }

        private fun inferRequiredHeaders(
            foreignFunctions: List<ForeignFnInteropSpec>,
            inlineFunctions: List<InlineCFunctionSpec>,
            explicitHeaders: Set<String>,
            preambleIncludes: Set<String>,
        ): List<PlatformHeaderSpec> {
            val specs = mutableListOf<PlatformHeaderSpec>()
            for (hdr in explicitHeaders) {
                specs += classifyHeaderGuard(hdr)
            }
            for (fn in foreignFunctions) {
                specs.addAll(headersForCSymbol(fn.cSymbolName))
                if (fn.returnBridgeKind == CAbiBridgeKind.FILE_PTR || fn.params.any { it.bridgeKind == CAbiBridgeKind.FILE_PTR }) {
                    specs += PlatformHeaderSpec("stdio.h")
                }
                if (fn.returnBridgeKind == CAbiBridgeKind.DIR_PTR || fn.params.any { it.bridgeKind == CAbiBridgeKind.DIR_PTR }) {
                    specs += PlatformHeaderSpec("dirent.h", guardCondition = "!defined(_WIN32)")
                }
            }
            for (fn in inlineFunctions) {
                for ((sym, hdrList) in C_SYMBOL_HEADERS) {
                    if (containsCIdentifier(fn.rawBody, sym)) {
                        specs.addAll(hdrList)
                    }
                }
            }
            return specs.filter { it.header !in preambleIncludes }.distinct()
        }

        private fun classifyHeaderGuard(header: String): PlatformHeaderSpec =
            when {
                header in PORTABLE_STD_HEADERS -> PlatformHeaderSpec(header)
                header.startsWith("linux/") || header == "sys/epoll.h" || header == "sys/eventfd.h" || header == "sys/signalfd.h" ->
                    PlatformHeaderSpec(header, guardCondition = "defined(__linux__)")
                header == "sys/event.h" || header.startsWith("mach/") ->
                    PlatformHeaderSpec(
                        header,
                        guardCondition = "defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__)",
                    )
                header == "windows.h" || header == "winsock2.h" || header == "ws2tcpip.h" || header == "io.h" || header == "direct.h" ->
                    PlatformHeaderSpec(header, guardCondition = "defined(_WIN32)")
                else -> PlatformHeaderSpec(header, guardCondition = "!defined(_WIN32)")
            }

        private fun symbolPlatformGuard(cSymbol: String): String? =
            when {
                cSymbol in LINUX_ONLY_SYMBOLS -> "defined(__linux__)"
                cSymbol in BSD_APPLE_ONLY_SYMBOLS ->
                    "defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__)"
                cSymbol in POSIX_NON_WIN32_SYMBOLS -> "!defined(_WIN32)"
                else -> null
            }

        private fun headersForCSymbol(symbol: String): List<PlatformHeaderSpec> =
            C_SYMBOL_HEADERS[symbol].orEmpty()

        private val PORTABLE_STD_HEADERS =
            setOf(
                "assert.h",
                "ctype.h",
                "errno.h",
                "float.h",
                "inttypes.h",
                "limits.h",
                "locale.h",
                "math.h",
                "setjmp.h",
                "signal.h",
                "stdarg.h",
                "stdbool.h",
                "stddef.h",
                "stdint.h",
                "stdio.h",
                "stdlib.h",
                "string.h",
                "time.h",
                "wchar.h",
                "wctype.h",
                "fcntl.h",
                "sys/types.h",
                "sys/stat.h",
            )

        private val LINUX_ONLY_SYMBOLS =
            setOf(
                "epoll_create",
                "epoll_create1",
                "epoll_ctl",
                "epoll_wait",
                "eventfd",
                "signalfd",
                "timerfd_create",
                "timerfd_settime",
                "timerfd_gettime",
                "memfd_create",
                "prctl",
            )

        private val BSD_APPLE_ONLY_SYMBOLS =
            setOf(
                "kqueue",
                "kevent",
            )

        private val POSIX_NON_WIN32_SYMBOLS =
            setOf(
                "fork",
                "pipe",
                "link",
                "symlink",
                "readlink",
                "chown",
                "fchown",
                "getuid",
                "geteuid",
                "getgid",
                "getegid",
                "setenv",
                "unsetenv",
                "mmap",
                "munmap",
                "mprotect",
                "msync",
                "mlock",
                "munlock",
                "dlopen",
                "dlsym",
                "dlclose",
                "dlerror",
                "pthread_self",
                "pthread_equal",
                "pthread_detach",
                "pthread_join",
                "pthread_kill",
                "sem_init",
                "sem_destroy",
                "sem_wait",
                "sem_trywait",
                "sem_post",
                "poll",
                "ioctl",
                "wait",
                "waitpid",
                "opendir",
                "readdir",
                "closedir",
                "rewinddir",
                "chmod",
                "fchmod",
                "lstat",
                "mkfifo",
                "mknod",
                "sysconf",
                "sleep",
                "usleep",
            )

        private val C_SYMBOL_HEADERS: Map<String, List<PlatformHeaderSpec>> =
            buildMap {
                fun register(headers: List<PlatformHeaderSpec>, vararg symbols: String) {
                    for (sym in symbols) {
                        put(sym, headers)
                    }
                }
                register(
                    listOf(PlatformHeaderSpec("stdlib.h")),
                    "malloc",
                    "calloc",
                    "realloc",
                    "free",
                    "aligned_alloc",
                    "abort",
                    "exit",
                    "atexit",
                    "getenv",
                    "system",
                    "bsearch",
                    "qsort",
                    "abs",
                    "labs",
                    "llabs",
                    "div",
                    "ldiv",
                    "lldiv",
                    "atoi",
                    "atol",
                    "atoll",
                    "atof",
                    "strtod",
                    "strtof",
                    "strtol",
                    "strtoll",
                    "strtoul",
                    "strtoull",
                    "rand",
                    "srand",
                )
                register(
                    listOf(PlatformHeaderSpec("stdio.h")),
                    "fopen",
                    "freopen",
                    "fclose",
                    "fflush",
                    "setbuf",
                    "setvbuf",
                    "fread",
                    "fwrite",
                    "fgetc",
                    "fgets",
                    "fputc",
                    "fputs",
                    "getc",
                    "getchar",
                    "putchar",
                    "puts",
                    "ungetc",
                    "fseek",
                    "ftell",
                    "rewind",
                    "clearerr",
                    "feof",
                    "ferror",
                    "perror",
                    "remove",
                    "rename",
                    "tmpfile",
                    "tmpnam",
                    "snprintf",
                )
                register(
                    listOf(PlatformHeaderSpec("string.h")),
                    "memcpy",
                    "memmove",
                    "memset",
                    "memcmp",
                    "memchr",
                    "strcpy",
                    "strncpy",
                    "strcat",
                    "strncat",
                    "strcmp",
                    "strncmp",
                    "strcoll",
                    "strxfrm",
                    "strchr",
                    "strrchr",
                    "strcspn",
                    "strspn",
                    "strpbrk",
                    "strstr",
                    "strtok",
                    "strlen",
                    "strnlen",
                    "strerror",
                    "strdup",
                    "strndup",
                )
                register(
                    listOf(PlatformHeaderSpec("ctype.h")),
                    "isalnum",
                    "isalpha",
                    "isblank",
                    "iscntrl",
                    "isdigit",
                    "isgraph",
                    "islower",
                    "isprint",
                    "ispunct",
                    "isspace",
                    "isupper",
                    "isxdigit",
                    "tolower",
                    "toupper",
                )
                register(
                    listOf(PlatformHeaderSpec("math.h")),
                    "sin",
                    "cos",
                    "tan",
                    "asin",
                    "acos",
                    "atan",
                    "atan2",
                    "exp",
                    "log",
                    "log10",
                    "pow",
                    "sqrt",
                    "ceil",
                    "floor",
                    "fabs",
                    "fmod",
                    "round",
                    "trunc",
                )
                register(
                    listOf(PlatformHeaderSpec("time.h")),
                    "clock",
                    "time",
                    "difftime",
                    "mktime",
                    "strftime",
                    "gmtime",
                    "localtime",
                    "asctime",
                    "ctime",
                )
                register(listOf(PlatformHeaderSpec("signal.h")), "signal", "raise", "kill")
                register(listOf(PlatformHeaderSpec("locale.h")), "setlocale", "localeconv")
                register(listOf(PlatformHeaderSpec("fcntl.h")), "open", "creat", "fcntl", "openat")
                register(
                    listOf(PlatformHeaderSpec("sys/types.h"), PlatformHeaderSpec("sys/stat.h")),
                    "stat",
                    "fstat",
                    "lstat",
                    "mkdir",
                    "chmod",
                    "fchmod",
                    "umask",
                    "mkfifo",
                    "mknod",
                )
                register(
                    listOf(
                        PlatformHeaderSpec(
                            header = "unistd.h",
                            guardCondition = "!defined(_WIN32)",
                            windowsFallbackHeaders = listOf("io.h", "process.h", "direct.h"),
                        ),
                    ),
                    "read",
                    "write",
                    "close",
                    "lseek",
                    "dup",
                    "dup2",
                    "pipe",
                    "chdir",
                    "getcwd",
                    "unlink",
                    "rmdir",
                    "access",
                    "isatty",
                    "getpid",
                    "sleep",
                    "usleep",
                    "fsync",
                    "ftruncate",
                    "truncate",
                    "link",
                    "symlink",
                    "readlink",
                    "chown",
                    "fchown",
                    "geteuid",
                    "getuid",
                    "getegid",
                    "getgid",
                    "fork",
                    "sysconf",
                )
                register(
                    listOf(PlatformHeaderSpec("stdlib.h"), PlatformHeaderSpec("unistd.h", guardCondition = "!defined(_WIN32)")),
                    "setenv",
                    "unsetenv",
                )
                register(
                    listOf(PlatformHeaderSpec("sys/mman.h", guardCondition = "!defined(_WIN32)")),
                    "mmap",
                    "munmap",
                    "mprotect",
                    "msync",
                    "mlock",
                    "munlock",
                )
                register(
                    listOf(
                        PlatformHeaderSpec(
                            header = "sys/socket.h",
                            guardCondition = "!defined(_WIN32)",
                            windowsFallbackHeaders = listOf("winsock2.h", "ws2tcpip.h"),
                        ),
                    ),
                    "socket",
                    "bind",
                    "listen",
                    "accept",
                    "connect",
                    "send",
                    "recv",
                    "sendto",
                    "recvfrom",
                    "setsockopt",
                    "getsockopt",
                    "shutdown",
                    "getsockname",
                    "getpeername",
                    "socketpair",
                )
                register(
                    listOf(PlatformHeaderSpec("dlfcn.h", guardCondition = "!defined(_WIN32)")),
                    "dlopen",
                    "dlsym",
                    "dlclose",
                    "dlerror",
                )
                register(
                    listOf(PlatformHeaderSpec("pthread.h", guardCondition = "!defined(_WIN32)")),
                    "pthread_create",
                    "pthread_join",
                    "pthread_detach",
                    "pthread_self",
                    "pthread_equal",
                    "pthread_kill",
                )
                register(
                    listOf(PlatformHeaderSpec("semaphore.h", guardCondition = "!defined(_WIN32)")),
                    "sem_init",
                    "sem_destroy",
                    "sem_wait",
                    "sem_trywait",
                    "sem_post",
                )
                register(listOf(PlatformHeaderSpec("poll.h", guardCondition = "!defined(_WIN32)")), "poll")
                register(listOf(PlatformHeaderSpec("sys/ioctl.h", guardCondition = "!defined(_WIN32)")), "ioctl")
                register(listOf(PlatformHeaderSpec("sys/wait.h", guardCondition = "!defined(_WIN32)")), "wait", "waitpid")
                register(
                    listOf(PlatformHeaderSpec("dirent.h", guardCondition = "!defined(_WIN32)")),
                    "opendir",
                    "readdir",
                    "closedir",
                    "rewinddir",
                )
                register(
                    listOf(PlatformHeaderSpec("sys/epoll.h", guardCondition = "defined(__linux__)")),
                    "epoll_create",
                    "epoll_create1",
                    "epoll_ctl",
                    "epoll_wait",
                )
                register(
                    listOf(
                        PlatformHeaderSpec(
                            "sys/event.h",
                            guardCondition = "defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__)",
                        ),
                    ),
                    "kqueue",
                    "kevent",
                )
            }
    }
}

internal data class ProjectFfiScaffoldFile(
    val projectRelativePath: String,
    val content: String,
)

internal fun emitWin32DynamicFfiScaffold(
    projectNamespace: String,
    ffiName: String = "Windowssys",
): List<ProjectFfiScaffoldFile> {
    val ns = projectNamespace.trim().removeSuffix(".")
    val pkgPath = ns.replace('.', '/')
    val ffiPascal = ffiName.trim().replaceFirstChar { it.uppercase() }
    val ffiLower = ffiName.trim().lowercase()
    val cGuard = "${ffiLower.uppercase()}_WRAPPER_H"
    val jniPrefix = "Java_" + ns.replace("_", "_1").replace('.', '_') + "_internal_${ffiPascal}Jni_"
    val npmPkg = "@kotlinmania/$ffiLower-native-bindings"

    val commonKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class)

        package $ns

        internal expect object Win32DynamicFfi {
            fun isWindowsHost(): Boolean

            fun invoke(
                dll: String,
                symbol: String,
                args: LongArray,
            ): Long

            fun invokeFp(
                dll: String,
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double

            fun invokePtr(
                fnPtr: Long,
                args: LongArray,
            ): Long

            fun guidSlot(
                data1: UInt,
                data2: UShort,
                data3: UShort,
                data4: UByteArray,
            ): Long

            fun objectSlot(value: Any?): Long

            fun allocZeroed(size: Int): Long

            fun freeMem(address: Long)

            fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray

            fun writeBytes(
                address: Long,
                source: ByteArray,
            )
        }

        internal object Win32LiteralPool {
            private val utf8Interned = HashMap<String, Long>()
            private val utf16Interned = HashMap<String, Long>()

            fun utf8Pointer(literal: String): Long {
                utf8Interned[literal]?.let { return it }
                val encoded = literal.encodeToByteArray()
                val nullTerminated = ByteArray(encoded.size + 1)
                encoded.copyInto(nullTerminated, destinationOffset = 0)
                val ptr = Win32DynamicFfi.allocZeroed(nullTerminated.size)
                Win32DynamicFfi.writeBytes(ptr, nullTerminated)
                utf8Interned[literal] = ptr
                return ptr
            }

            fun utf16Pointer(literal: String): Long {
                utf16Interned[literal]?.let { return it }
                val units = literal.length
                val nullTerminated = ByteArray((units + 1) * 2)
                for (i in 0 until units) {
                    val code = literal[i].code
                    nullTerminated[i * 2] = (code and 0xFF).toByte()
                    nullTerminated[i * 2 + 1] = ((code ushr 8) and 0xFF).toByte()
                }
                val ptr = Win32DynamicFfi.allocZeroed(nullTerminated.size)
                Win32DynamicFfi.writeBytes(ptr, nullTerminated)
                utf16Interned[literal] = ptr
                return ptr
            }
        }
        """.trimIndent() + "\n"

    val defContent =
        """
        package = $ns.cinterop
        headers = ${ffiLower}_wrapper.h
        headerFilter = ${ffiLower}_wrapper.h
        compilerOpts = -Isrc/native/interop/cinterop
        staticLibraries = ${ffiLower}_wrapper.a
        """.trimIndent() + "\n"

    val wrapperH =
        """
        #ifndef $cGuard
        #define $cGuard

        #include <stdint.h>

        #ifdef __cplusplus
        extern "C" {
        #endif

        int32_t ${ffiLower}_is_windows_host(void);
        int64_t ${ffiLower}_invoke(const char* dll, const char* symbol, int32_t argc, const int64_t* argv, int32_t* status_out);
        double ${ffiLower}_invoke_fp(const char* dll, const char* symbol, int32_t fp_mask, int32_t ret_f32, int32_t argc, const int64_t* argv, int32_t* status_out);
        int64_t ${ffiLower}_invoke_ptr(int64_t fn_ptr, int32_t argc, const int64_t* argv, int32_t* status_out);
        int64_t ${ffiLower}_guid_slot(uint32_t d1, uint16_t d2, uint16_t d3, const uint8_t* d4);
        int64_t ${ffiLower}_alloc_zeroed(int32_t size);
        void ${ffiLower}_free_mem(int64_t addr);
        void ${ffiLower}_read_bytes(int64_t addr, int32_t len, uint8_t* dst);
        void ${ffiLower}_write_bytes(int64_t addr, int32_t len, const uint8_t* src);

        #ifdef __cplusplus
        }
        #endif

        #endif
        """.trimIndent() + "\n"

    val wrapperC =
        buildWin32DispatchCSource(
            includeHeader = "${ffiLower}_wrapper.h",
            prefix = ffiLower,
            includeJni = false,
            jniPrefix = "",
        )

    val jniC =
        buildWin32DispatchCSource(
            includeHeader = null,
            prefix = "${ffiLower}_jni_impl",
            includeJni = true,
            jniPrefix = jniPrefix,
        )

    val nativeKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class, kotlinx.cinterop.ExperimentalForeignApi::class)

        package $ns

        import $ns.cinterop.${ffiLower}_alloc_zeroed
        import $ns.cinterop.${ffiLower}_free_mem
        import $ns.cinterop.${ffiLower}_guid_slot
        import $ns.cinterop.${ffiLower}_invoke
        import $ns.cinterop.${ffiLower}_invoke_fp
        import $ns.cinterop.${ffiLower}_invoke_ptr
        import $ns.cinterop.${ffiLower}_is_windows_host
        import $ns.cinterop.${ffiLower}_read_bytes
        import $ns.cinterop.${ffiLower}_write_bytes
        import kotlinx.cinterop.IntVar
        import kotlinx.cinterop.addressOf
        import kotlinx.cinterop.alloc
        import kotlinx.cinterop.memScoped
        import kotlinx.cinterop.ptr
        import kotlinx.cinterop.reinterpret
        import kotlinx.cinterop.usePinned
        import kotlinx.cinterop.value

        internal actual object Win32DynamicFfi {
            private val objectHandles = HashMap<Any, Long>()
            private var nextHandleId = 0x1000_0000L

            actual fun isWindowsHost(): Boolean = ${ffiLower}_is_windows_host() != 0

            actual fun invoke(
                dll: String,
                symbol: String,
                args: LongArray,
            ): Long =
                memScoped {
                    val status = alloc<IntVar>()
                    val raw =
                        if (args.isEmpty()) {
                            ${ffiLower}_invoke(dll, symbol, 0, null, status.ptr)
                        } else {
                            args.usePinned { pinned ->
                                ${ffiLower}_invoke(dll, symbol, args.size, pinned.addressOf(0), status.ptr)
                            }
                        }
                    checkInvokeStatus(status.value, dll, symbol)
                    raw
                }

            actual fun invokeFp(
                dll: String,
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double =
                memScoped {
                    val status = alloc<IntVar>()
                    val retF32 = if (returnIsFloat32) 1 else 0
                    val raw =
                        if (args.isEmpty()) {
                            ${ffiLower}_invoke_fp(dll, symbol, fpMask, retF32, 0, null, status.ptr)
                        } else {
                            args.usePinned { pinned ->
                                ${ffiLower}_invoke_fp(dll, symbol, fpMask, retF32, args.size, pinned.addressOf(0), status.ptr)
                            }
                        }
                    checkInvokeStatus(status.value, dll, symbol)
                    raw
                }

            actual fun invokePtr(
                fnPtr: Long,
                args: LongArray,
            ): Long =
                memScoped {
                    val status = alloc<IntVar>()
                    val raw =
                        if (args.isEmpty()) {
                            ${ffiLower}_invoke_ptr(fnPtr, 0, null, status.ptr)
                        } else {
                            args.usePinned { pinned ->
                                ${ffiLower}_invoke_ptr(fnPtr, args.size, pinned.addressOf(0), status.ptr)
                            }
                        }
                    checkInvokeStatus(status.value, "<fnptr>", fnPtr.toString(16))
                    raw
                }

            actual fun guidSlot(
                data1: UInt,
                data2: UShort,
                data3: UShort,
                data4: UByteArray,
            ): Long {
                val padded = ByteArray(8)
                val limit = minOf(8, data4.size)
                for (i in 0 until limit) {
                    padded[i] = data4[i].toByte()
                }
                return padded.usePinned { pinned ->
                    ${ffiLower}_guid_slot(data1, data2, data3, pinned.addressOf(0).reinterpret())
                }
            }

            actual fun objectSlot(value: Any?): Long {
                if (value == null) return 0L
                return objectHandles.getOrPut(value) { nextHandleId++ }
            }

            actual fun allocZeroed(size: Int): Long = ${ffiLower}_alloc_zeroed(size)

            actual fun freeMem(address: Long) {
                ${ffiLower}_free_mem(address)
            }

            actual fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray {
                if (length <= 0 || address == 0L) return ByteArray(0)
                val out = ByteArray(length)
                out.usePinned { pinned ->
                    ${ffiLower}_read_bytes(address, length, pinned.addressOf(0).reinterpret())
                }
                return out
            }

            actual fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                if (source.isEmpty() || address == 0L) return
                source.usePinned { pinned ->
                    ${ffiLower}_write_bytes(address, source.size, pinned.addressOf(0).reinterpret())
                }
            }

            private fun checkInvokeStatus(
                code: Int,
                dll: String,
                symbol: String,
            ) {
                if (code == -2) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) requires a Windows host")
                }
                if (code != 0) {
                    throw IllegalStateException("Win32 symbol resolution failed (${'$'}code): ${'$'}dll!${'$'}symbol")
                }
            }
        }
        """.trimIndent() + "\n"

    val jvmJniLoaderKt =
        """
        package $ns.internal

        import java.io.File

        internal object ${ffiPascal}Jni {
            init {
                try {
                    System.loadLibrary("${ffiLower}_jni")
                } catch (error: UnsatisfiedLinkError) {
                    val os = System.getProperty("os.name", "").lowercase()
                    val arch =
                        when (System.getProperty("os.arch", "").lowercase()) {
                            "aarch64", "arm64" -> "aarch64"
                            "amd64", "x86_64" -> "x86_64"
                            else -> throw UnsatisfiedLinkError("Unsupported JNI architecture: ${'$'}{System.getProperty("os.arch")}")
                        }
                    val platform =
                        when {
                            os.contains("mac") || os.contains("darwin") -> "macos"
                            os.contains("windows") -> "windows"
                            os.contains("linux") -> "linux"
                            else -> throw UnsatisfiedLinkError("Unsupported JNI host: ${'$'}os")
                        }
                    val mapped = System.mapLibraryName("${ffiLower}_jni")
                    val resource = "/$pkgPath/native/${'$'}platform-${'$'}arch/${'$'}mapped"
                    val stream =
                        ${ffiPascal}Jni::class.java.getResourceAsStream(resource)
                            ?: throw UnsatisfiedLinkError("Missing packaged JNI library ${'$'}resource").apply { initCause(error) }
                    val extracted = File.createTempFile("kotlinmania-$ffiLower-", "." + mapped.substringAfterLast('.'))
                    extracted.deleteOnExit()
                    stream.use { input -> extracted.outputStream().use { input.copyTo(it) } }
                    System.load(extracted.absolutePath)
                }
            }

            @JvmStatic
            external fun isWindowsHost(): Boolean

            @JvmStatic
            external fun invoke(
                dll: String,
                symbol: String,
                args: LongArray,
            ): Long

            @JvmStatic
            external fun invokeFp(
                dll: String,
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double

            @JvmStatic
            external fun invokePtr(
                fnPtr: Long,
                args: LongArray,
            ): Long

            @JvmStatic
            external fun guidSlot(
                d1: Int,
                d2: Short,
                d3: Short,
                d4: ByteArray,
            ): Long

            @JvmStatic
            external fun allocZeroed(size: Int): Long

            @JvmStatic
            external fun freeMem(address: Long)

            @JvmStatic
            external fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray

            @JvmStatic
            external fun writeBytes(
                address: Long,
                source: ByteArray,
            )
        }
        """.trimIndent() + "\n"

    val androidJniLoaderKt =
        """
        package $ns.internal

        internal object ${ffiPascal}Jni {
            init {
                System.loadLibrary("${ffiLower}_jni")
            }

            @JvmStatic
            external fun isWindowsHost(): Boolean

            @JvmStatic
            external fun invoke(
                dll: String,
                symbol: String,
                args: LongArray,
            ): Long

            @JvmStatic
            external fun invokeFp(
                dll: String,
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double

            @JvmStatic
            external fun invokePtr(
                fnPtr: Long,
                args: LongArray,
            ): Long

            @JvmStatic
            external fun guidSlot(
                d1: Int,
                d2: Short,
                d3: Short,
                d4: ByteArray,
            ): Long

            @JvmStatic
            external fun allocZeroed(size: Int): Long

            @JvmStatic
            external fun freeMem(address: Long)

            @JvmStatic
            external fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray

            @JvmStatic
            external fun writeBytes(
                address: Long,
                source: ByteArray,
            )
        }
        """.trimIndent() + "\n"

    val jvmAndAndroidActualKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class)

        package $ns

        import $ns.internal.${ffiPascal}Jni
        import java.util.concurrent.ConcurrentHashMap
        import java.util.concurrent.atomic.AtomicLong

        internal actual object Win32DynamicFfi {
            private val objectHandles = ConcurrentHashMap<Any, Long>()
            private val nextHandleId = AtomicLong(0x1000_0000L)

            actual fun isWindowsHost(): Boolean = ${ffiPascal}Jni.isWindowsHost()

            actual fun invoke(
                dll: String,
                symbol: String,
                args: LongArray,
            ): Long = ${ffiPascal}Jni.invoke(dll, symbol, args)

            actual fun invokeFp(
                dll: String,
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double = ${ffiPascal}Jni.invokeFp(dll, symbol, fpMask, returnIsFloat32, args)

            actual fun invokePtr(
                fnPtr: Long,
                args: LongArray,
            ): Long = ${ffiPascal}Jni.invokePtr(fnPtr, args)

            actual fun guidSlot(
                data1: UInt,
                data2: UShort,
                data3: UShort,
                data4: UByteArray,
            ): Long {
                val padded = ByteArray(8)
                val limit = minOf(8, data4.size)
                for (i in 0 until limit) {
                    padded[i] = data4[i].toByte()
                }
                return ${ffiPascal}Jni.guidSlot(data1.toInt(), data2.toShort(), data3.toShort(), padded)
            }

            actual fun objectSlot(value: Any?): Long {
                if (value == null) return 0L
                return objectHandles.computeIfAbsent(value) { nextHandleId.getAndIncrement() }
            }

            actual fun allocZeroed(size: Int): Long = ${ffiPascal}Jni.allocZeroed(size)

            actual fun freeMem(address: Long) {
                ${ffiPascal}Jni.freeMem(address)
            }

            actual fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray = ${ffiPascal}Jni.readBytes(address, length)

            actual fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                ${ffiPascal}Jni.writeBytes(address, source)
            }
        }
        """.trimIndent() + "\n"

    val nodePackageJson =
        """
        {
          "name": "$npmPkg",
          "version": "0.1.0",
          "description": "N-API C++ addon providing dynamic Win32 DLL bindings for Kotlin/JS and Kotlin/WASM targets",
          "main": "index.js",
          "dependencies": {
            "node-addon-api": "^8.0.0"
          },
          "gypfile": false
        }
        """.trimIndent() + "\n"

    val nodeBindingGyp =
        """
        {
          "targets": [
            {
              "target_name": "${ffiLower}_native",
              "sources": [
                "../src/${ffiLower}_bindings.cpp"
              ],
              "include_dirs": [
                "<!@(node -p \"require('node-addon-api').include\")"
              ],
              "dependencies": [
                "<!(node -p \"require('node-addon-api').gyp\")"
              ],
              "cflags!": [ "-fno-exceptions" ],
              "cflags_cc!": [ "-fno-exceptions" ],
              "defines": [ "NAPI_DISABLE_CPP_EXCEPTIONS" ],
              "xcode_settings": {
                "GCC_ENABLE_CPP_EXCEPTIONS": "YES",
                "CLANG_CXX_LIBRARY": "libc++",
                "MACOSX_DEPLOYMENT_TARGET": "10.15"
              }
            }
          ]
        }
        """.trimIndent() + "\n"

    val nodeIndexJs =
        """
        try {
            module.exports = require('./build/Release/${ffiLower}_native.node');
        } catch (err) {
            try {
                module.exports = require('./build/Debug/${ffiLower}_native.node');
            } catch (err2) {
                throw new Error(
                    'Could not load ${ffiLower}_native addon. ' +
                    'Original error: ' + err.message + ' | Debug error: ' + err2.message
                );
            }
        }
        """.trimIndent() + "\n"

    val nodeBindingsCpp = buildWin32NapiBindingsCpp()

    val jsActualKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class)

        package $ns

        // Hide require('$npmPkg') behind new Function so webpack browser bundles never scan it statically.
        private fun loadNodeWin32Addon(): dynamic =
            js(
                "(function(){ try { var rq = (new Function('return typeof require === \"function\" ? require : null'))(); if (!rq) return null; return rq('$npmPkg'); } catch (e) { return null; } })()",
            )

        internal actual object Win32DynamicFfi {
            private val addon: dynamic = loadNodeWin32Addon()
            private var arena = ByteArray(65536)
            private var arenaOffset = 64
            private val objectHandles = HashMap<Any, Long>()
            private var nextHandleId = 0x1000_0000L

            private fun packLoHi(
                lo: Int,
                hi: Int,
            ): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFF_FFFFL)

            actual fun isWindowsHost(): Boolean = addon != null && (addon.isWindowsHost() as Boolean)

            actual fun invoke(
                dll: String,
                symbol: String,
                args: LongArray,
            ): Long {
                val mod = addon ?: throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) requires a Windows Node.js host")
                val flat = IntArray(args.size * 2)
                for (i in args.indices) {
                    flat[i * 2] = args[i].toInt()
                    flat[i * 2 + 1] = (args[i] ushr 32).toInt()
                }
                val res = mod.invokeLoHi(dll, symbol, flat)
                val status = res[2] as Int
                if (status == -2) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) requires a Windows host")
                }
                if (status != 0) {
                    throw IllegalStateException("Win32 symbol resolution failed (${'$'}status): ${'$'}dll!${'$'}symbol")
                }
                return packLoHi(res[0] as Int, res[1] as Int)
            }

            actual fun invokeFp(
                dll: String,
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double {
                val mod = addon ?: throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) requires a Windows Node.js host")
                val flat = IntArray(args.size * 2)
                for (i in args.indices) {
                    flat[i * 2] = args[i].toInt()
                    flat[i * 2 + 1] = (args[i] ushr 32).toInt()
                }
                val res = mod.invokeFpPair(dll, symbol, fpMask, if (returnIsFloat32) 1 else 0, flat)
                val status = res[1] as Int
                if (status == -2) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) requires a Windows host")
                }
                if (status != 0) {
                    throw IllegalStateException("Win32 symbol resolution failed (${'$'}status): ${'$'}dll!${'$'}symbol")
                }
                return res[0] as Double
            }

            actual fun invokePtr(
                fnPtr: Long,
                args: LongArray,
            ): Long {
                val mod = addon ?: throw UnsupportedOperationException("Win32 dynamic FFI (<fnptr>) requires a Windows Node.js host")
                val flat = IntArray(args.size * 2)
                for (i in args.indices) {
                    flat[i * 2] = args[i].toInt()
                    flat[i * 2 + 1] = (args[i] ushr 32).toInt()
                }
                val res = mod.invokePtrLoHi(fnPtr.toInt(), (fnPtr ushr 32).toInt(), flat)
                val status = res[2] as Int
                if (status == -2) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (<fnptr>) requires a Windows host")
                }
                if (status != 0) {
                    throw IllegalStateException("Win32 function pointer call failed (${'$'}status)")
                }
                return packLoHi(res[0] as Int, res[1] as Int)
            }

            actual fun guidSlot(
                data1: UInt,
                data2: UShort,
                data3: UShort,
                data4: UByteArray,
            ): Long {
                val bytes = ByteArray(16)
                val d1 = data1.toInt()
                val d2 = data2.toInt()
                val d3 = data3.toInt()
                bytes[0] = (d1 and 0xFF).toByte()
                bytes[1] = ((d1 ushr 8) and 0xFF).toByte()
                bytes[2] = ((d1 ushr 16) and 0xFF).toByte()
                bytes[3] = ((d1 ushr 24) and 0xFF).toByte()
                bytes[4] = (d2 and 0xFF).toByte()
                bytes[5] = ((d2 ushr 8) and 0xFF).toByte()
                bytes[6] = (d3 and 0xFF).toByte()
                bytes[7] = ((d3 ushr 8) and 0xFF).toByte()
                val limit = minOf(8, data4.size)
                for (i in 0 until limit) {
                    bytes[8 + i] = data4[i].toByte()
                }
                val ptr = allocZeroed(16)
                writeBytes(ptr, bytes)
                return ptr
            }

            actual fun objectSlot(value: Any?): Long {
                if (value == null) return 0L
                return objectHandles.getOrPut(value) { nextHandleId++ }
            }

            actual fun allocZeroed(size: Int): Long {
                val n = maxOf(size, 1)
                val mod = addon
                if (mod != null) {
                    val pair = mod.allocZeroedLoHi(n)
                    return packLoHi(pair[0] as Int, pair[1] as Int)
                }
                val aligned = (arenaOffset + 7) and -8
                if (aligned + n > arena.size) {
                    arena = arena.copyOf(maxOf(arena.size * 2, aligned + n + 4096))
                }
                arenaOffset = aligned + n
                return aligned.toLong()
            }

            actual fun freeMem(address: Long) {
                val mod = addon
                if (mod != null && address != 0L) {
                    mod.freeMemLoHi(address.toInt(), (address ushr 32).toInt())
                }
            }

            actual fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray {
                if (length <= 0 || address == 0L) return ByteArray(0)
                val mod = addon
                if (mod != null) {
                    val raw = mod.readBytesLoHi(address.toInt(), (address ushr 32).toInt(), length)
                    val out = ByteArray(length)
                    for (i in 0 until length) {
                        out[i] = (raw[i] as Int).toByte()
                    }
                    return out
                }
                val start = address.toInt()
                return arena.copyOfRange(start, start + length)
            }

            actual fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                if (source.isEmpty() || address == 0L) return
                val mod = addon
                if (mod != null) {
                    val ints = IntArray(source.size) { source[it].toInt() and 0xFF }
                    mod.writeBytesLoHi(address.toInt(), (address ushr 32).toInt(), ints)
                    return
                }
                source.copyInto(arena, destinationOffset = address.toInt())
            }
        }
        """.trimIndent() + "\n"

    val wasmJsActualKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class, kotlin.js.ExperimentalWasmJsInterop::class)

        package $ns

        // Hide require('$npmPkg') behind new Function so webpack browser bundles never scan it statically.
        private fun wasmHasNodeWin32Addon(): Boolean =
            js(
                "{ try { var rq = (new Function('return typeof require === \"function\" ? require : null'))(); if (!rq) return false; var m = rq('$npmPkg'); globalThis.__kmWin32Addon = m; return !!m; } catch (e) { return false; } }",
            )

        private fun wasmAddonIsWindowsHost(): Boolean =
            js("{ return !!(globalThis.__kmWin32Addon && globalThis.__kmWin32Addon.isWindowsHost()); }")

        private fun wasmAddonInvokeCsv(
            dll: String,
            symbol: String,
            flatCsv: String,
        ): String =
            js(
                "{ var parts = flatCsv.length === 0 ? [] : flatCsv.split(',').map(Number); var r = globalThis.__kmWin32Addon.invokeLoHi(dll, symbol, parts); return r[0] + ',' + r[1] + ',' + r[2]; }",
            )

        private fun wasmAddonInvokeFpCsv(
            dll: String,
            symbol: String,
            fpMask: Int,
            retF32: Int,
            flatCsv: String,
        ): String =
            js(
                "{ var parts = flatCsv.length === 0 ? [] : flatCsv.split(',').map(Number); var r = globalThis.__kmWin32Addon.invokeFpPair(dll, symbol, fpMask, retF32, parts); return r[0] + ',' + r[1]; }",
            )

        private fun wasmAddonInvokePtrCsv(
            lo: Int,
            hi: Int,
            flatCsv: String,
        ): String =
            js(
                "{ var parts = flatCsv.length === 0 ? [] : flatCsv.split(',').map(Number); var r = globalThis.__kmWin32Addon.invokePtrLoHi(lo, hi, parts); return r[0] + ',' + r[1] + ',' + r[2]; }",
            )

        private fun wasmAddonAllocCsv(size: Int): String =
            js("{ var r = globalThis.__kmWin32Addon.allocZeroedLoHi(size); return r[0] + ',' + r[1]; }")

        private fun wasmAddonFree(
            lo: Int,
            hi: Int,
        ): Unit =
            js("{ globalThis.__kmWin32Addon.freeMemLoHi(lo, hi); }")

        private fun wasmAddonReadCsv(
            lo: Int,
            hi: Int,
            length: Int,
        ): String =
            js("{ var r = globalThis.__kmWin32Addon.readBytesLoHi(lo, hi, length); return Array.from(r).join(','); }")

        private fun wasmAddonWriteCsv(
            lo: Int,
            hi: Int,
            bytesCsv: String,
        ): Unit =
            js(
                "{ var arr = bytesCsv.length === 0 ? [] : bytesCsv.split(',').map(Number); globalThis.__kmWin32Addon.writeBytesLoHi(lo, hi, arr); }",
            )

        internal actual object Win32DynamicFfi {
            private val hasAddon: Boolean = wasmHasNodeWin32Addon()
            private var arena = ByteArray(65536)
            private var arenaOffset = 64
            private val objectHandles = HashMap<Any, Long>()
            private var nextHandleId = 0x1000_0000L

            private fun packLoHi(
                lo: Int,
                hi: Int,
            ): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFF_FFFFL)

            private fun encodeArgsCsv(args: LongArray): String {
                if (args.isEmpty()) return ""
                val sb = StringBuilder(args.size * 12)
                for (i in args.indices) {
                    if (i > 0) sb.append(',')
                    sb.append(args[i].toInt())
                    sb.append(',')
                    sb.append((args[i] ushr 32).toInt())
                }
                return sb.toString()
            }

            actual fun isWindowsHost(): Boolean = hasAddon && wasmAddonIsWindowsHost()

            actual fun invoke(
                dll: String,
                symbol: String,
                args: LongArray,
            ): Long {
                if (!hasAddon) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) requires a Windows Node.js host")
                }
                val parts = wasmAddonInvokeCsv(dll, symbol, encodeArgsCsv(args)).split(',')
                val status = parts[2].toInt()
                if (status == -2) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) requires a Windows host")
                }
                if (status != 0) {
                    throw IllegalStateException("Win32 symbol resolution failed (${'$'}status): ${'$'}dll!${'$'}symbol")
                }
                return packLoHi(parts[0].toInt(), parts[1].toInt())
            }

            actual fun invokeFp(
                dll: String,
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double {
                if (!hasAddon) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) requires a Windows Node.js host")
                }
                val parts = wasmAddonInvokeFpCsv(dll, symbol, fpMask, if (returnIsFloat32) 1 else 0, encodeArgsCsv(args)).split(',')
                val status = parts[1].toInt()
                if (status == -2) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) requires a Windows host")
                }
                if (status != 0) {
                    throw IllegalStateException("Win32 symbol resolution failed (${'$'}status): ${'$'}dll!${'$'}symbol")
                }
                return parts[0].toDouble()
            }

            actual fun invokePtr(
                fnPtr: Long,
                args: LongArray,
            ): Long {
                if (!hasAddon) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (<fnptr>) requires a Windows Node.js host")
                }
                val parts = wasmAddonInvokePtrCsv(fnPtr.toInt(), (fnPtr ushr 32).toInt(), encodeArgsCsv(args)).split(',')
                val status = parts[2].toInt()
                if (status == -2) {
                    throw UnsupportedOperationException("Win32 dynamic FFI (<fnptr>) requires a Windows host")
                }
                if (status != 0) {
                    throw IllegalStateException("Win32 function pointer call failed (${'$'}status)")
                }
                return packLoHi(parts[0].toInt(), parts[1].toInt())
            }

            actual fun guidSlot(
                data1: UInt,
                data2: UShort,
                data3: UShort,
                data4: UByteArray,
            ): Long {
                val bytes = ByteArray(16)
                val d1 = data1.toInt()
                val d2 = data2.toInt()
                val d3 = data3.toInt()
                bytes[0] = (d1 and 0xFF).toByte()
                bytes[1] = ((d1 ushr 8) and 0xFF).toByte()
                bytes[2] = ((d1 ushr 16) and 0xFF).toByte()
                bytes[3] = ((d1 ushr 24) and 0xFF).toByte()
                bytes[4] = (d2 and 0xFF).toByte()
                bytes[5] = ((d2 ushr 8) and 0xFF).toByte()
                bytes[6] = (d3 and 0xFF).toByte()
                bytes[7] = ((d3 ushr 8) and 0xFF).toByte()
                val limit = minOf(8, data4.size)
                for (i in 0 until limit) {
                    bytes[8 + i] = data4[i].toByte()
                }
                val ptr = allocZeroed(16)
                writeBytes(ptr, bytes)
                return ptr
            }

            actual fun objectSlot(value: Any?): Long {
                if (value == null) return 0L
                return objectHandles.getOrPut(value) { nextHandleId++ }
            }

            actual fun allocZeroed(size: Int): Long {
                val n = maxOf(size, 1)
                if (hasAddon) {
                    val parts = wasmAddonAllocCsv(n).split(',')
                    return packLoHi(parts[0].toInt(), parts[1].toInt())
                }
                val aligned = (arenaOffset + 7) and -8
                if (aligned + n > arena.size) {
                    arena = arena.copyOf(maxOf(arena.size * 2, aligned + n + 4096))
                }
                arenaOffset = aligned + n
                return aligned.toLong()
            }

            actual fun freeMem(address: Long) {
                if (hasAddon && address != 0L) {
                    wasmAddonFree(address.toInt(), (address ushr 32).toInt())
                }
            }

            actual fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray {
                if (length <= 0 || address == 0L) return ByteArray(0)
                if (hasAddon) {
                    val csv = wasmAddonReadCsv(address.toInt(), (address ushr 32).toInt(), length)
                    if (csv.isEmpty()) return ByteArray(0)
                    val parts = csv.split(',')
                    return ByteArray(parts.size) { parts[it].toInt().toByte() }
                }
                val start = address.toInt()
                return arena.copyOfRange(start, start + length)
            }

            actual fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                if (source.isEmpty() || address == 0L) return
                if (hasAddon) {
                    val csv = source.joinToString(",") { (it.toInt() and 0xFF).toString() }
                    wasmAddonWriteCsv(address.toInt(), (address ushr 32).toInt(), csv)
                    return
                }
                source.copyInto(arena, destinationOffset = address.toInt())
            }
        }
        """.trimIndent() + "\n"

    val wasmWasiActualKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class)

        package $ns

        internal actual object Win32DynamicFfi {
            private var arena = ByteArray(65536)
            private var arenaOffset = 64
            private val objectHandles = HashMap<Any, Long>()
            private var nextHandleId = 0x1000_0000L

            actual fun isWindowsHost(): Boolean = false

            actual fun invoke(
                dll: String,
                symbol: String,
                args: LongArray,
            ): Long = throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) is unavailable on WASI")

            actual fun invokeFp(
                dll: String,
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double = throw UnsupportedOperationException("Win32 dynamic FFI (${'$'}dll!${'$'}symbol) is unavailable on WASI")

            actual fun invokePtr(
                fnPtr: Long,
                args: LongArray,
            ): Long = throw UnsupportedOperationException("Win32 dynamic FFI (<fnptr>) is unavailable on WASI")

            actual fun guidSlot(
                data1: UInt,
                data2: UShort,
                data3: UShort,
                data4: UByteArray,
            ): Long {
                val bytes = ByteArray(16)
                val d1 = data1.toInt()
                val d2 = data2.toInt()
                val d3 = data3.toInt()
                bytes[0] = (d1 and 0xFF).toByte()
                bytes[1] = ((d1 ushr 8) and 0xFF).toByte()
                bytes[2] = ((d1 ushr 16) and 0xFF).toByte()
                bytes[3] = ((d1 ushr 24) and 0xFF).toByte()
                bytes[4] = (d2 and 0xFF).toByte()
                bytes[5] = ((d2 ushr 8) and 0xFF).toByte()
                bytes[6] = (d3 and 0xFF).toByte()
                bytes[7] = ((d3 ushr 8) and 0xFF).toByte()
                val limit = minOf(8, data4.size)
                for (i in 0 until limit) {
                    bytes[8 + i] = data4[i].toByte()
                }
                val ptr = allocZeroed(16)
                writeBytes(ptr, bytes)
                return ptr
            }

            actual fun objectSlot(value: Any?): Long {
                if (value == null) return 0L
                return objectHandles.getOrPut(value) { nextHandleId++ }
            }

            actual fun allocZeroed(size: Int): Long {
                val n = maxOf(size, 1)
                val aligned = (arenaOffset + 7) and -8
                if (aligned + n > arena.size) {
                    arena = arena.copyOf(maxOf(arena.size * 2, aligned + n + 4096))
                }
                arenaOffset = aligned + n
                return aligned.toLong()
            }

            actual fun freeMem(address: Long) {
                if (address == 0L) return
            }

            actual fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray {
                if (length <= 0 || address == 0L) return ByteArray(0)
                val start = address.toInt()
                return arena.copyOfRange(start, start + length)
            }

            actual fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                if (source.isEmpty() || address == 0L) return
                source.copyInto(arena, destinationOffset = address.toInt())
            }
        }
        """.trimIndent() + "\n"

    return listOf(
        ProjectFfiScaffoldFile("src/common/commonMain/kotlin/$pkgPath/Win32DynamicFfi.kt", commonKt),
        ProjectFfiScaffoldFile("src/native/interop/cinterop/$ffiPascal.def", defContent),
        ProjectFfiScaffoldFile("src/native/interop/cinterop/${ffiLower}_wrapper.h", wrapperH),
        ProjectFfiScaffoldFile("src/native/interop/cinterop/${ffiLower}_wrapper.c", wrapperC),
        ProjectFfiScaffoldFile("src/native/nativeMain/kotlin/$pkgPath/Win32DynamicFfi.native.kt", nativeKt),
        ProjectFfiScaffoldFile("src/jvm/jvmMain/kotlin/$pkgPath/internal/${ffiPascal}Jni.kt", jvmJniLoaderKt),
        ProjectFfiScaffoldFile("src/jvm/jvmMain/kotlin/$pkgPath/Win32DynamicFfi.jvm.kt", jvmAndAndroidActualKt),
        ProjectFfiScaffoldFile("src/jvm/jvmMain/c/${ffiLower}_jni.c", jniC),
        ProjectFfiScaffoldFile("src/android/jvm/androidMain/kotlin/$pkgPath/internal/${ffiPascal}Jni.kt", androidJniLoaderKt),
        ProjectFfiScaffoldFile("src/android/jvm/androidMain/kotlin/$pkgPath/Win32DynamicFfi.android.kt", jvmAndAndroidActualKt),
        ProjectFfiScaffoldFile("src/js/node-$ffiLower/dist/package.json", nodePackageJson),
        ProjectFfiScaffoldFile("src/js/node-$ffiLower/dist/binding.gyp", nodeBindingGyp),
        ProjectFfiScaffoldFile("src/js/node-$ffiLower/dist/index.js", nodeIndexJs),
        ProjectFfiScaffoldFile("src/js/node-$ffiLower/src/${ffiLower}_bindings.cpp", nodeBindingsCpp),
        ProjectFfiScaffoldFile("src/js/jsMain/kotlin/$pkgPath/Win32DynamicFfi.js.kt", jsActualKt),
        ProjectFfiScaffoldFile("src/js/wasm/wasmJsMain/kotlin/$pkgPath/Win32DynamicFfi.wasmJs.kt", wasmJsActualKt),
        ProjectFfiScaffoldFile("src/js/wasm/wasmWasiMain/kotlin/$pkgPath/Win32DynamicFfi.wasmWasi.kt", wasmWasiActualKt),
    )
}

private fun buildWin32DispatchCSource(
    includeHeader: String?,
    prefix: String,
    includeJni: Boolean,
    jniPrefix: String,
): String {
    val sb = StringBuilder()
    if (includeHeader != null) {
        sb.append("#include \"").append(includeHeader).append("\"\n")
    }
    if (includeJni) {
        sb.append("#include <jni.h>\n")
        sb.append("#include <stdint.h>\n")
        sb.append("#include <stdio.h>\n")
    }
    sb.append("#include <stdlib.h>\n")
    sb.append("#include <string.h>\n")
    sb.append("#ifdef _WIN32\n")
    sb.append("#ifndef WIN32_LEAN_AND_MEAN\n#define WIN32_LEAN_AND_MEAN\n#endif\n")
    sb.append("#include <windows.h>\n")
    sb.append("#endif\n\n")

    val storage = if (includeJni) "static " else ""

    sb.append("typedef struct { uint32_t d1; uint16_t d2; uint16_t d3; uint8_t d4[8]; } km_win32_guid_t;\n")
    sb.append("static km_win32_guid_t km_guid_ring[64];\n")
    sb.append("static uint32_t km_guid_ring_idx = 0;\n\n")

    sb.append("#ifdef _WIN32\n")
    sb.append("static FARPROC km_resolve_win32_proc(const char* dll, const char* symbol) {\n")
    sb.append("    if (!dll || !symbol) return NULL;\n")
    sb.append("    HMODULE mod = GetModuleHandleA(dll);\n")
    sb.append("    if (!mod) mod = LoadLibraryA(dll);\n")
    sb.append("    if (!mod) return NULL;\n")
    sb.append("    return GetProcAddress(mod, symbol);\n")
    sb.append("}\n\n")
    sb.append("static uintptr_t km_call_raw_proc(FARPROC proc, int32_t argc, const int64_t* a) {\n")
    sb.append("    uintptr_t u[16];\n")
    sb.append("    int32_t i;\n")
    sb.append("    for (i = 0; i < 16; ++i) u[i] = (a && i < argc) ? (uintptr_t)a[i] : 0;\n")
    sb.append("    switch (argc <= 0 ? 0 : (argc > 16 ? 16 : argc)) {\n")
    for (n in 0..16) {
        val paramTypes = if (n == 0) "void" else (0 until n).joinToString(", ") { "uintptr_t" }
        val argsCall = (0 until n).joinToString(", ") { "u[$it]" }
        sb.append("        case $n: { typedef uintptr_t (__stdcall *fn_t)($paramTypes); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn($argsCall); }\n")
    }
    sb.append("        default: return 0;\n")
    sb.append("    }\n")
    sb.append("}\n\n")
    sb.append("static double km_bits_to_f64(int64_t bits) { double d = 0.0; memcpy(&d, &bits, sizeof(d)); return d; }\n")
    sb.append("static float km_bits_to_f32(int64_t bits) { uint32_t b = (uint32_t)bits; float f = 0.0f; memcpy(&f, &b, sizeof(f)); return f; }\n\n")
    sb.append("static double km_call_fp_proc(FARPROC proc, int32_t fp_mask, int32_t ret_f32, int32_t argc, const int64_t* a) {\n")
    sb.append("    uintptr_t u[16];\n")
    sb.append("    int32_t i;\n")
    sb.append("    for (i = 0; i < 16; ++i) u[i] = (a && i < argc) ? (uintptr_t)a[i] : 0;\n")
    sb.append("    int32_t m0 = fp_mask & 3;\n")
    sb.append("    int32_t m1 = (fp_mask >> 2) & 3;\n")
    sb.append("    if (ret_f32) {\n")
    sb.append("        if (m0 == 1 && m1 == 1) { typedef float (__stdcall *fn_t)(float, float, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return (double)fn(km_bits_to_f32(a ? a[0] : 0), km_bits_to_f32(a && argc > 1 ? a[1] : 0), u[2], u[3], u[4], u[5], u[6], u[7]); }\n")
    sb.append("        if (m0 == 1) { typedef float (__stdcall *fn_t)(float, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return (double)fn(km_bits_to_f32(a ? a[0] : 0), u[1], u[2], u[3], u[4], u[5], u[6], u[7]); }\n")
    sb.append("        if (m0 == 2) { typedef float (__stdcall *fn_t)(double, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return (double)fn(km_bits_to_f64(a ? a[0] : 0), u[1], u[2], u[3], u[4], u[5], u[6], u[7]); }\n")
    sb.append("        { typedef float (__stdcall *fn_t)(uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return (double)fn(u[0], u[1], u[2], u[3], u[4], u[5], u[6], u[7], u[8], u[9], u[10], u[11], u[12], u[13], u[14], u[15]); }\n")
    sb.append("    } else {\n")
    sb.append("        if (m0 == 2 && m1 == 2) { typedef double (__stdcall *fn_t)(double, double, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn(km_bits_to_f64(a ? a[0] : 0), km_bits_to_f64(a && argc > 1 ? a[1] : 0), u[2], u[3], u[4], u[5], u[6], u[7]); }\n")
    sb.append("        if (m0 == 2) { typedef double (__stdcall *fn_t)(double, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn(km_bits_to_f64(a ? a[0] : 0), u[1], u[2], u[3], u[4], u[5], u[6], u[7]); }\n")
    sb.append("        if (m0 == 1) { typedef double (__stdcall *fn_t)(float, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn(km_bits_to_f32(a ? a[0] : 0), u[1], u[2], u[3], u[4], u[5], u[6], u[7]); }\n")
    sb.append("        { typedef double (__stdcall *fn_t)(uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn(u[0], u[1], u[2], u[3], u[4], u[5], u[6], u[7], u[8], u[9], u[10], u[11], u[12], u[13], u[14], u[15]); }\n")
    sb.append("    }\n")
    sb.append("}\n")
    sb.append("#endif\n\n")

    sb.append("${storage}int32_t ${prefix}_is_windows_host(void) {\n")
    sb.append("#ifdef _WIN32\n    return 1;\n#else\n    return 0;\n#endif\n}\n\n")

    sb.append("${storage}int64_t ${prefix}_invoke(const char* dll, const char* symbol, int32_t argc, const int64_t* argv, int32_t* status_out) {\n")
    sb.append("#ifdef _WIN32\n")
    sb.append("    FARPROC proc = km_resolve_win32_proc(dll, symbol);\n")
    sb.append("    if (!proc) { if (status_out) *status_out = -1; return 0; }\n")
    sb.append("    if (status_out) *status_out = 0;\n")
    sb.append("    return (int64_t)km_call_raw_proc(proc, argc, argv);\n")
    sb.append("#else\n")
    sb.append("    (void)dll; (void)symbol; (void)argc; (void)argv;\n")
    sb.append("    if (status_out) *status_out = -2;\n")
    sb.append("    return 0;\n")
    sb.append("#endif\n}\n\n")

    sb.append("${storage}double ${prefix}_invoke_fp(const char* dll, const char* symbol, int32_t fp_mask, int32_t ret_f32, int32_t argc, const int64_t* argv, int32_t* status_out) {\n")
    sb.append("#ifdef _WIN32\n")
    sb.append("    FARPROC proc = km_resolve_win32_proc(dll, symbol);\n")
    sb.append("    if (!proc) { if (status_out) *status_out = -1; return 0.0; }\n")
    sb.append("    if (status_out) *status_out = 0;\n")
    sb.append("    return km_call_fp_proc(proc, fp_mask, ret_f32, argc, argv);\n")
    sb.append("#else\n")
    sb.append("    (void)dll; (void)symbol; (void)fp_mask; (void)ret_f32; (void)argc; (void)argv;\n")
    sb.append("    if (status_out) *status_out = -2;\n")
    sb.append("    return 0.0;\n")
    sb.append("#endif\n}\n\n")

    sb.append("${storage}int64_t ${prefix}_invoke_ptr(int64_t fn_ptr, int32_t argc, const int64_t* argv, int32_t* status_out) {\n")
    sb.append("#ifdef _WIN32\n")
    sb.append("    FARPROC proc = NULL;\n")
    sb.append("    uintptr_t raw = (uintptr_t)fn_ptr;\n")
    sb.append("    if (raw == 0) { if (status_out) *status_out = -1; return 0; }\n")
    sb.append("    memcpy(&proc, &raw, sizeof(proc));\n")
    sb.append("    if (status_out) *status_out = 0;\n")
    sb.append("    return (int64_t)km_call_raw_proc(proc, argc, argv);\n")
    sb.append("#else\n")
    sb.append("    (void)fn_ptr; (void)argc; (void)argv;\n")
    sb.append("    if (status_out) *status_out = -2;\n")
    sb.append("    return 0;\n")
    sb.append("#endif\n}\n\n")

    sb.append("${storage}int64_t ${prefix}_guid_slot(uint32_t d1, uint16_t d2, uint16_t d3, const uint8_t* d4) {\n")
    sb.append("    uint32_t slot = (km_guid_ring_idx++) & 63u;\n")
    sb.append("    km_guid_ring[slot].d1 = d1;\n")
    sb.append("    km_guid_ring[slot].d2 = d2;\n")
    sb.append("    km_guid_ring[slot].d3 = d3;\n")
    sb.append("    if (d4) memcpy(km_guid_ring[slot].d4, d4, 8);\n")
    sb.append("    else memset(km_guid_ring[slot].d4, 0, 8);\n")
    sb.append("    return (int64_t)(uintptr_t)&km_guid_ring[slot];\n")
    sb.append("}\n\n")

    sb.append("${storage}int64_t ${prefix}_alloc_zeroed(int32_t size) {\n")
    sb.append("    size_t n = size > 0 ? (size_t)size : 1u;\n")
    sb.append("    void* p = calloc(1u, n);\n")
    sb.append("    return (int64_t)(uintptr_t)p;\n")
    sb.append("}\n\n")

    sb.append("${storage}void ${prefix}_free_mem(int64_t addr) {\n")
    sb.append("    if (addr != 0) free((void*)(uintptr_t)addr);\n")
    sb.append("}\n\n")

    sb.append("${storage}void ${prefix}_read_bytes(int64_t addr, int32_t len, uint8_t* dst) {\n")
    sb.append("    if (addr != 0 && len > 0 && dst) memcpy(dst, (const void*)(uintptr_t)addr, (size_t)len);\n")
    sb.append("}\n\n")

    sb.append("${storage}void ${prefix}_write_bytes(int64_t addr, int32_t len, const uint8_t* src) {\n")
    sb.append("    if (addr != 0 && len > 0 && src) memcpy((void*)(uintptr_t)addr, src, (size_t)len);\n")
    sb.append("}\n")

    if (includeJni) {
        sb.append("\nstatic void km_throw_jni_status(JNIEnv* env, int32_t status, const char* dll, const char* sym) {\n")
        sb.append("    char msg[256];\n")
        sb.append("    const char* cls = (status == -2) ? \"java/lang/UnsupportedOperationException\" : \"java/lang/IllegalStateException\";\n")
        sb.append("    jclass ex = (*env)->FindClass(env, cls);\n")
        sb.append("    if (!ex) return;\n")
        sb.append("    if (status == -2) {\n")
        sb.append("        snprintf(msg, sizeof(msg), \"Win32 dynamic FFI (%s!%s) requires a Windows host\", dll ? dll : \"\", sym ? sym : \"\");\n")
        sb.append("    } else {\n")
        sb.append("        snprintf(msg, sizeof(msg), \"Win32 symbol resolution failed (%d): %s!%s\", (int)status, dll ? dll : \"\", sym ? sym : \"\");\n")
        sb.append("    }\n")
        sb.append("    (*env)->ThrowNew(env, ex, msg);\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jboolean JNICALL ${jniPrefix}isWindowsHost(JNIEnv* env, jclass clazz) {\n")
        sb.append("    (void)env; (void)clazz;\n")
        sb.append("    return ${prefix}_is_windows_host() ? JNI_TRUE : JNI_FALSE;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jlong JNICALL ${jniPrefix}invoke(JNIEnv* env, jclass clazz, jstring dll, jstring symbol, jlongArray args) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    const char* c_dll = dll ? (*env)->GetStringUTFChars(env, dll, NULL) : NULL;\n")
        sb.append("    const char* c_sym = symbol ? (*env)->GetStringUTFChars(env, symbol, NULL) : NULL;\n")
        sb.append("    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;\n")
        sb.append("    jlong* elems = (args && argc > 0) ? (*env)->GetLongArrayElements(env, args, NULL) : NULL;\n")
        sb.append("    int32_t status = 0;\n")
        sb.append("    int64_t ret = ${prefix}_invoke(c_dll, c_sym, (int32_t)argc, (const int64_t*)elems, &status);\n")
        sb.append("    if (elems) (*env)->ReleaseLongArrayElements(env, args, elems, JNI_ABORT);\n")
        sb.append("    if (status != 0) km_throw_jni_status(env, status, c_dll, c_sym);\n")
        sb.append("    if (c_sym) (*env)->ReleaseStringUTFChars(env, symbol, c_sym);\n")
        sb.append("    if (c_dll) (*env)->ReleaseStringUTFChars(env, dll, c_dll);\n")
        sb.append("    return (jlong)ret;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jdouble JNICALL ${jniPrefix}invokeFp(JNIEnv* env, jclass clazz, jstring dll, jstring symbol, jint fpMask, jboolean retF32, jlongArray args) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    const char* c_dll = dll ? (*env)->GetStringUTFChars(env, dll, NULL) : NULL;\n")
        sb.append("    const char* c_sym = symbol ? (*env)->GetStringUTFChars(env, symbol, NULL) : NULL;\n")
        sb.append("    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;\n")
        sb.append("    jlong* elems = (args && argc > 0) ? (*env)->GetLongArrayElements(env, args, NULL) : NULL;\n")
        sb.append("    int32_t status = 0;\n")
        sb.append("    double ret = ${prefix}_invoke_fp(c_dll, c_sym, (int32_t)fpMask, retF32 ? 1 : 0, (int32_t)argc, (const int64_t*)elems, &status);\n")
        sb.append("    if (elems) (*env)->ReleaseLongArrayElements(env, args, elems, JNI_ABORT);\n")
        sb.append("    if (status != 0) km_throw_jni_status(env, status, c_dll, c_sym);\n")
        sb.append("    if (c_sym) (*env)->ReleaseStringUTFChars(env, symbol, c_sym);\n")
        sb.append("    if (c_dll) (*env)->ReleaseStringUTFChars(env, dll, c_dll);\n")
        sb.append("    return (jdouble)ret;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jlong JNICALL ${jniPrefix}invokePtr(JNIEnv* env, jclass clazz, jlong fnPtr, jlongArray args) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;\n")
        sb.append("    jlong* elems = (args && argc > 0) ? (*env)->GetLongArrayElements(env, args, NULL) : NULL;\n")
        sb.append("    int32_t status = 0;\n")
        sb.append("    int64_t ret = ${prefix}_invoke_ptr((int64_t)fnPtr, (int32_t)argc, (const int64_t*)elems, &status);\n")
        sb.append("    if (elems) (*env)->ReleaseLongArrayElements(env, args, elems, JNI_ABORT);\n")
        sb.append("    if (status != 0) km_throw_jni_status(env, status, \"<fnptr>\", \"\");\n")
        sb.append("    return (jlong)ret;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jlong JNICALL ${jniPrefix}guidSlot(JNIEnv* env, jclass clazz, jint d1, jshort d2, jshort d3, jbyteArray d4) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    uint8_t buf[8] = {0};\n")
        sb.append("    if (d4) {\n")
        sb.append("        jsize n = (*env)->GetArrayLength(env, d4);\n")
        sb.append("        if (n > 8) n = 8;\n")
        sb.append("        if (n > 0) (*env)->GetByteArrayRegion(env, d4, 0, n, (jbyte*)buf);\n")
        sb.append("    }\n")
        sb.append("    return (jlong)${prefix}_guid_slot((uint32_t)d1, (uint16_t)d2, (uint16_t)d3, buf);\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jlong JNICALL ${jniPrefix}allocZeroed(JNIEnv* env, jclass clazz, jint size) {\n")
        sb.append("    (void)env; (void)clazz;\n")
        sb.append("    return (jlong)${prefix}_alloc_zeroed((int32_t)size);\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT void JNICALL ${jniPrefix}freeMem(JNIEnv* env, jclass clazz, jlong address) {\n")
        sb.append("    (void)env; (void)clazz;\n")
        sb.append("    ${prefix}_free_mem((int64_t)address);\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jbyteArray JNICALL ${jniPrefix}readBytes(JNIEnv* env, jclass clazz, jlong address, jint length) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    jsize n = (length > 0 && address != 0) ? (jsize)length : 0;\n")
        sb.append("    jbyteArray out = (*env)->NewByteArray(env, n);\n")
        sb.append("    if (out && n > 0) {\n")
        sb.append("        (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte*)(uintptr_t)address);\n")
        sb.append("    }\n")
        sb.append("    return out;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT void JNICALL ${jniPrefix}writeBytes(JNIEnv* env, jclass clazz, jlong address, jbyteArray source) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    if (!source || address == 0) return;\n")
        sb.append("    jsize n = (*env)->GetArrayLength(env, source);\n")
        sb.append("    if (n > 0) {\n")
        sb.append("        (*env)->GetByteArrayRegion(env, source, 0, n, (jbyte*)(uintptr_t)address);\n")
        sb.append("    }\n")
        sb.append("}\n")
    }
    return sb.toString()
}

private fun buildWin32NapiBindingsCpp(): String {
    val body =
        buildWin32DispatchCSource(
            includeHeader = null,
            prefix = "napi_win32",
            includeJni = false,
            jniPrefix = "",
        )
    return buildString {
        append("#include <napi.h>\n")
        append("#include <cstdint>\n")
        append("#include <cstdlib>\n")
        append("#include <cstring>\n")
        append("#include <string>\n")
        append("#include <vector>\n\n")
        append(body)
        append(
            """

            namespace {

            int64_t unpackLoHi(int32_t lo, int32_t hi) {
                return (static_cast<int64_t>(hi) << 32) | (static_cast<int64_t>(static_cast<uint32_t>(lo)));
            }

            std::vector<int64_t> extractArgsFromFlatArray(const Napi::Array& flat) {
                uint32_t len = flat.Length();
                uint32_t count = len / 2;
                std::vector<int64_t> out(count);
                for (uint32_t i = 0; i < count; ++i) {
                    int32_t lo = flat.Get(i * 2).As<Napi::Number>().Int32Value();
                    int32_t hi = flat.Get(i * 2 + 1).As<Napi::Number>().Int32Value();
                    out[i] = unpackLoHi(lo, hi);
                }
                return out;
            }

            Napi::Value IsWindowsHostWrapped(const Napi::CallbackInfo& info) {
                return Napi::Boolean::New(info.Env(), napi_win32_is_windows_host() != 0);
            }

            Napi::Value InvokeLoHiWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                std::string dll = info[0].As<Napi::String>().Utf8Value();
                std::string sym = info[1].As<Napi::String>().Utf8Value();
                std::vector<int64_t> argv = extractArgsFromFlatArray(info[2].As<Napi::Array>());
                int32_t status = 0;
                int64_t ret = napi_win32_invoke(dll.c_str(), sym.c_str(), static_cast<int32_t>(argv.size()), argv.empty() ? nullptr : argv.data(), &status);
                Napi::Array out = Napi::Array::New(env, 3);
                out.Set(uint32_t{0}, Napi::Number::New(env, static_cast<int32_t>(ret & 0xFFFFFFFFLL)));
                out.Set(uint32_t{1}, Napi::Number::New(env, static_cast<int32_t>(static_cast<uint64_t>(ret) >> 32)));
                out.Set(uint32_t{2}, Napi::Number::New(env, status));
                return out;
            }

            Napi::Value InvokeFpPairWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                std::string dll = info[0].As<Napi::String>().Utf8Value();
                std::string sym = info[1].As<Napi::String>().Utf8Value();
                int32_t fpMask = info[2].As<Napi::Number>().Int32Value();
                int32_t retF32 = info[3].As<Napi::Number>().Int32Value();
                std::vector<int64_t> argv = extractArgsFromFlatArray(info[4].As<Napi::Array>());
                int32_t status = 0;
                double ret = napi_win32_invoke_fp(dll.c_str(), sym.c_str(), fpMask, retF32, static_cast<int32_t>(argv.size()), argv.empty() ? nullptr : argv.data(), &status);
                Napi::Array out = Napi::Array::New(env, 2);
                out.Set(uint32_t{0}, Napi::Number::New(env, ret));
                out.Set(uint32_t{1}, Napi::Number::New(env, status));
                return out;
            }

            Napi::Value InvokePtrLoHiWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                int32_t lo = info[0].As<Napi::Number>().Int32Value();
                int32_t hi = info[1].As<Napi::Number>().Int32Value();
                std::vector<int64_t> argv = extractArgsFromFlatArray(info[2].As<Napi::Array>());
                int32_t status = 0;
                int64_t ret = napi_win32_invoke_ptr(unpackLoHi(lo, hi), static_cast<int32_t>(argv.size()), argv.empty() ? nullptr : argv.data(), &status);
                Napi::Array out = Napi::Array::New(env, 3);
                out.Set(uint32_t{0}, Napi::Number::New(env, static_cast<int32_t>(ret & 0xFFFFFFFFLL)));
                out.Set(uint32_t{1}, Napi::Number::New(env, static_cast<int32_t>(static_cast<uint64_t>(ret) >> 32)));
                out.Set(uint32_t{2}, Napi::Number::New(env, status));
                return out;
            }

            Napi::Value AllocZeroedLoHiWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                int32_t size = info[0].As<Napi::Number>().Int32Value();
                int64_t ptr = napi_win32_alloc_zeroed(size);
                Napi::Array out = Napi::Array::New(env, 2);
                out.Set(uint32_t{0}, Napi::Number::New(env, static_cast<int32_t>(ptr & 0xFFFFFFFFLL)));
                out.Set(uint32_t{1}, Napi::Number::New(env, static_cast<int32_t>(static_cast<uint64_t>(ptr) >> 32)));
                return out;
            }

            Napi::Value FreeMemLoHiWrapped(const Napi::CallbackInfo& info) {
                int32_t lo = info[0].As<Napi::Number>().Int32Value();
                int32_t hi = info[1].As<Napi::Number>().Int32Value();
                napi_win32_free_mem(unpackLoHi(lo, hi));
                return info.Env().Undefined();
            }

            Napi::Value ReadBytesLoHiWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                int32_t lo = info[0].As<Napi::Number>().Int32Value();
                int32_t hi = info[1].As<Napi::Number>().Int32Value();
                int32_t len = info[2].As<Napi::Number>().Int32Value();
                int64_t addr = unpackLoHi(lo, hi);
                uint32_t count = (len > 0 && addr != 0) ? static_cast<uint32_t>(len) : 0;
                Napi::Array out = Napi::Array::New(env, count);
                if (count > 0) {
                    std::vector<uint8_t> buf(count);
                    napi_win32_read_bytes(addr, len, buf.data());
                    for (uint32_t i = 0; i < count; ++i) {
                        out.Set(i, Napi::Number::New(env, static_cast<int32_t>(buf[i])));
                    }
                }
                return out;
            }

            Napi::Value WriteBytesLoHiWrapped(const Napi::CallbackInfo& info) {
                int32_t lo = info[0].As<Napi::Number>().Int32Value();
                int32_t hi = info[1].As<Napi::Number>().Int32Value();
                Napi::Array src = info[2].As<Napi::Array>();
                int64_t addr = unpackLoHi(lo, hi);
                uint32_t count = src.Length();
                if (count > 0 && addr != 0) {
                    std::vector<uint8_t> buf(count);
                    for (uint32_t i = 0; i < count; ++i) {
                        buf[i] = static_cast<uint8_t>(src.Get(i).As<Napi::Number>().Int32Value() & 0xFF);
                    }
                    napi_win32_write_bytes(addr, static_cast<int32_t>(count), buf.data());
                }
                return info.Env().Undefined();
            }

            Napi::Object Init(Napi::Env env, Napi::Object exports) {
                exports.Set("isWindowsHost", Napi::Function::New(env, IsWindowsHostWrapped));
                exports.Set("invokeLoHi", Napi::Function::New(env, InvokeLoHiWrapped));
                exports.Set("invokeFpPair", Napi::Function::New(env, InvokeFpPairWrapped));
                exports.Set("invokePtrLoHi", Napi::Function::New(env, InvokePtrLoHiWrapped));
                exports.Set("allocZeroedLoHi", Napi::Function::New(env, AllocZeroedLoHiWrapped));
                exports.Set("freeMemLoHi", Napi::Function::New(env, FreeMemLoHiWrapped));
                exports.Set("readBytesLoHi", Napi::Function::New(env, ReadBytesLoHiWrapped));
                exports.Set("writeBytesLoHi", Napi::Function::New(env, WriteBytesLoHiWrapped));
                return exports;
            }

            }  // namespace

            NODE_API_MODULE(addon, Init)
            """.trimIndent(),
        )
        append('\n')
    }
}

internal fun emitCDynamicFfiScaffold(
    projectNamespace: String,
    ffiName: String = "Libc",
): List<ProjectFfiScaffoldFile> {
    val ns = projectNamespace.trim().removeSuffix(".")
    val pkgPath = ns.replace('.', '/')
    val ffiPascal = ffiName.trim().replaceFirstChar { it.uppercase() }
    val ffiLower = ffiName.trim().lowercase()
    val cGuard = "${ffiLower.uppercase()}_WRAPPER_H"
    val jniPrefix = "Java_" + ns.replace("_", "_1").replace('.', '_') + "_internal_${ffiPascal}Jni_"
    val npmPkg = "@kotlinmania/$ffiLower-native-bindings"

    val commonKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class)

        package $ns

        internal expect object LibcDynamicFfiPlatform {
            fun isHostAvailable(): Boolean

            fun invoke(
                symbol: String,
                fixedArgc: Int,
                args: LongArray,
            ): Long

            fun invokeFp(
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double

            fun resolveSymbolAddress(symbol: String): Long

            fun lastErrorNumber(): Int

            fun strlenAt(address: Long): Int

            fun allocZeroed(size: Int): Long

            fun freeMem(address: Long)

            fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray

            fun writeBytes(
                address: Long,
                source: ByteArray,
            )
        }

        internal object LibcDynamicFfi {
            fun isHostAvailable(): Boolean = LibcDynamicFfiPlatform.isHostAvailable()

            fun invoke(
                symbol: String,
                fixedArgc: Int,
                args: LongArray,
            ): Long = LibcDynamicFfiPlatform.invoke(symbol, fixedArgc, args)

            fun invokeFp(
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double = LibcDynamicFfiPlatform.invokeFp(symbol, fpMask, returnIsFloat32, args)

            fun resolveSymbolAddress(symbol: String): Long = LibcDynamicFfiPlatform.resolveSymbolAddress(symbol)

            fun lastErrorNumber(): Int = LibcDynamicFfiPlatform.lastErrorNumber()

            fun allocZeroed(size: Int): Long = LibcDynamicFfiPlatform.allocZeroed(size)

            fun freeMem(address: Long) {
                LibcDynamicFfiPlatform.freeMem(address)
            }

            fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray = LibcDynamicFfiPlatform.readBytes(address, length)

            fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                LibcDynamicFfiPlatform.writeBytes(address, source)
            }

            fun allocUtf8CString(value: String): Long {
                val utf8 = value.encodeToByteArray()
                val ptr = allocZeroed(utf8.size + 1)
                if (utf8.isNotEmpty()) {
                    writeBytes(ptr, utf8)
                }
                return ptr
            }

            fun allocUtf8CBuffer(
                initial: String?,
                minCapacity: Int,
            ): Long {
                val utf8 = initial?.encodeToByteArray()
                val size = maxOf(minCapacity, (utf8?.size ?: 0) + 1, 1)
                val ptr = allocZeroed(size)
                if (utf8 != null && utf8.isNotEmpty()) {
                    writeBytes(ptr, utf8)
                }
                return ptr
            }

            fun readUtf8CString(address: Long): String {
                if (address == 0L) return ""
                val len = LibcDynamicFfiPlatform.strlenAt(address)
                if (len <= 0) return ""
                return readBytes(address, len).decodeToString()
            }

            fun allocScalarSlot(bits: Long): Long {
                val ptr = allocZeroed(8)
                writeScalarSlot(ptr, 8, bits)
                return ptr
            }

            fun readScalarSlot(
                address: Long,
                byteWidth: Int,
            ): Long {
                if (address == 0L || byteWidth <= 0) return 0L
                val w = byteWidth.coerceIn(1, 8)
                val bytes = readBytes(address, w)
                var out = 0L
                for (i in 0 until bytes.size) {
                    out = out or ((bytes[i].toLong() and 0xFFL) shl (i * 8))
                }
                return out
            }

            fun writeScalarSlot(
                address: Long,
                byteWidth: Int,
                bits: Long,
            ) {
                if (address == 0L || byteWidth <= 0) return
                val w = byteWidth.coerceIn(1, 8)
                val bytes = ByteArray(w)
                for (i in 0 until w) {
                    bytes[i] = ((bits ushr (i * 8)) and 0xFFL).toByte()
                }
                writeBytes(address, bytes)
            }
        }
        """.trimIndent() + "\n"

    val defContent =
        """
        package = $ns.cinterop
        headers = ${ffiLower}_wrapper.h
        headerFilter = ${ffiLower}_wrapper.h
        compilerOpts = -Isrc/native/interop/cinterop
        staticLibraries = ${ffiLower}_wrapper.a
        """.trimIndent() + "\n"

    val wrapperH =
        """
        #ifndef $cGuard
        #define $cGuard

        #include <stdint.h>

        #ifdef __cplusplus
        extern "C" {
        #endif

        int32_t ${ffiLower}_is_host_available(void);
        int64_t ${ffiLower}_invoke(const char* symbol, int32_t fixed_argc, int32_t argc, const int64_t* argv, int32_t* status_out);
        double ${ffiLower}_invoke_fp(const char* symbol, int32_t fp_mask, int32_t ret_f32, int32_t argc, const int64_t* argv, int32_t* status_out);
        int64_t ${ffiLower}_resolve_symbol_address(const char* symbol);
        int32_t ${ffiLower}_last_error_number(void);
        int32_t ${ffiLower}_strlen_at(int64_t addr);
        int64_t ${ffiLower}_alloc_zeroed(int32_t size);
        void ${ffiLower}_free_mem(int64_t addr);
        void ${ffiLower}_read_bytes(int64_t addr, int32_t len, uint8_t* dst);
        void ${ffiLower}_write_bytes(int64_t addr, int32_t len, const uint8_t* src);

        #ifdef __cplusplus
        }
        #endif

        #endif
        """.trimIndent() + "\n"

    val wrapperC =
        buildCDynamicDispatchCSource(
            includeHeader = "${ffiLower}_wrapper.h",
            prefix = ffiLower,
            includeJni = false,
            jniPrefix = "",
        )

    val jniC =
        buildCDynamicDispatchCSource(
            includeHeader = null,
            prefix = "${ffiLower}_jni_impl",
            includeJni = true,
            jniPrefix = jniPrefix,
        )

    val nativeKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class, kotlinx.cinterop.ExperimentalForeignApi::class)

        package $ns

        import $ns.cinterop.${ffiLower}_alloc_zeroed
        import $ns.cinterop.${ffiLower}_free_mem
        import $ns.cinterop.${ffiLower}_invoke
        import $ns.cinterop.${ffiLower}_invoke_fp
        import $ns.cinterop.${ffiLower}_is_host_available
        import $ns.cinterop.${ffiLower}_last_error_number
        import $ns.cinterop.${ffiLower}_read_bytes
        import $ns.cinterop.${ffiLower}_resolve_symbol_address
        import $ns.cinterop.${ffiLower}_strlen_at
        import $ns.cinterop.${ffiLower}_write_bytes
        import kotlinx.cinterop.IntVar
        import kotlinx.cinterop.addressOf
        import kotlinx.cinterop.alloc
        import kotlinx.cinterop.memScoped
        import kotlinx.cinterop.ptr
        import kotlinx.cinterop.reinterpret
        import kotlinx.cinterop.usePinned
        import kotlinx.cinterop.value

        internal actual object LibcDynamicFfiPlatform {
            actual fun isHostAvailable(): Boolean = ${ffiLower}_is_host_available() != 0

            actual fun invoke(
                symbol: String,
                fixedArgc: Int,
                args: LongArray,
            ): Long =
                memScoped {
                    val status = alloc<IntVar>()
                    val raw =
                        if (args.isEmpty()) {
                            ${ffiLower}_invoke(symbol, fixedArgc, 0, null, status.ptr)
                        } else {
                            args.usePinned { pinned ->
                                ${ffiLower}_invoke(symbol, fixedArgc, args.size, pinned.addressOf(0), status.ptr)
                            }
                        }
                    checkInvokeStatus(status.value, symbol)
                    raw
                }

            actual fun invokeFp(
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double =
                memScoped {
                    val status = alloc<IntVar>()
                    val retF32 = if (returnIsFloat32) 1 else 0
                    val raw =
                        if (args.isEmpty()) {
                            ${ffiLower}_invoke_fp(symbol, fpMask, retF32, 0, null, status.ptr)
                        } else {
                            args.usePinned { pinned ->
                                ${ffiLower}_invoke_fp(symbol, fpMask, retF32, args.size, pinned.addressOf(0), status.ptr)
                            }
                        }
                    checkInvokeStatus(status.value, symbol)
                    raw
                }

            actual fun resolveSymbolAddress(symbol: String): Long = ${ffiLower}_resolve_symbol_address(symbol)

            actual fun lastErrorNumber(): Int = ${ffiLower}_last_error_number()

            actual fun strlenAt(address: Long): Int = ${ffiLower}_strlen_at(address)

            actual fun allocZeroed(size: Int): Long = ${ffiLower}_alloc_zeroed(size)

            actual fun freeMem(address: Long) {
                ${ffiLower}_free_mem(address)
            }

            actual fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray {
                if (length <= 0 || address == 0L) return ByteArray(0)
                val out = ByteArray(length)
                out.usePinned { pinned ->
                    ${ffiLower}_read_bytes(address, length, pinned.addressOf(0).reinterpret())
                }
                return out
            }

            actual fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                if (source.isEmpty() || address == 0L) return
                source.usePinned { pinned ->
                    ${ffiLower}_write_bytes(address, source.size, pinned.addressOf(0).reinterpret())
                }
            }

            private fun checkInvokeStatus(
                code: Int,
                symbol: String,
            ) {
                if (code != 0) {
                    throw UnsupportedOperationException("C symbol '${'$'}symbol' is unavailable on this host (${'$'}code)")
                }
            }
        }
        """.trimIndent() + "\n"

    val jvmJniLoaderKt =
        """
        package $ns.internal

        import java.io.File

        internal object ${ffiPascal}Jni {
            init {
                try {
                    System.loadLibrary("${ffiLower}_jni")
                } catch (error: UnsatisfiedLinkError) {
                    val os = System.getProperty("os.name", "").lowercase()
                    val arch =
                        when (System.getProperty("os.arch", "").lowercase()) {
                            "aarch64", "arm64" -> "aarch64"
                            "amd64", "x86_64" -> "x86_64"
                            else -> throw UnsatisfiedLinkError("Unsupported JNI architecture: ${'$'}{System.getProperty("os.arch")}")
                        }
                    val platform =
                        when {
                            os.contains("mac") || os.contains("darwin") -> "macos"
                            os.contains("windows") -> "windows"
                            os.contains("linux") -> "linux"
                            else -> throw UnsatisfiedLinkError("Unsupported JNI host: ${'$'}os")
                        }
                    val mapped = System.mapLibraryName("${ffiLower}_jni")
                    val resource = "/$pkgPath/native/${'$'}platform-${'$'}arch/${'$'}mapped"
                    val stream =
                        ${ffiPascal}Jni::class.java.getResourceAsStream(resource)
                            ?: throw UnsatisfiedLinkError("Missing packaged JNI library ${'$'}resource").apply { initCause(error) }
                    val extracted = File.createTempFile("kotlinmania-$ffiLower-", "." + mapped.substringAfterLast('.'))
                    extracted.deleteOnExit()
                    stream.use { input -> extracted.outputStream().use { input.copyTo(it) } }
                    System.load(extracted.absolutePath)
                }
            }

            @JvmStatic
            external fun isHostAvailable(): Boolean

            @JvmStatic
            external fun invoke(
                symbol: String,
                fixedArgc: Int,
                args: LongArray,
            ): Long

            @JvmStatic
            external fun invokeFp(
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double

            @JvmStatic
            external fun resolveSymbolAddress(symbol: String): Long

            @JvmStatic
            external fun lastErrorNumber(): Int

            @JvmStatic
            external fun strlenAt(address: Long): Int

            @JvmStatic
            external fun allocZeroed(size: Int): Long

            @JvmStatic
            external fun freeMem(address: Long)

            @JvmStatic
            external fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray

            @JvmStatic
            external fun writeBytes(
                address: Long,
                source: ByteArray,
            )
        }
        """.trimIndent() + "\n"

    val androidJniLoaderKt =
        """
        package $ns.internal

        internal object ${ffiPascal}Jni {
            init {
                System.loadLibrary("${ffiLower}_jni")
            }

            @JvmStatic
            external fun isHostAvailable(): Boolean

            @JvmStatic
            external fun invoke(
                symbol: String,
                fixedArgc: Int,
                args: LongArray,
            ): Long

            @JvmStatic
            external fun invokeFp(
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double

            @JvmStatic
            external fun resolveSymbolAddress(symbol: String): Long

            @JvmStatic
            external fun lastErrorNumber(): Int

            @JvmStatic
            external fun strlenAt(address: Long): Int

            @JvmStatic
            external fun allocZeroed(size: Int): Long

            @JvmStatic
            external fun freeMem(address: Long)

            @JvmStatic
            external fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray

            @JvmStatic
            external fun writeBytes(
                address: Long,
                source: ByteArray,
            )
        }
        """.trimIndent() + "\n"

    val jvmAndAndroidActualKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class)

        package $ns

        import $ns.internal.${ffiPascal}Jni

        internal actual object LibcDynamicFfiPlatform {
            actual fun isHostAvailable(): Boolean = ${ffiPascal}Jni.isHostAvailable()

            actual fun invoke(
                symbol: String,
                fixedArgc: Int,
                args: LongArray,
            ): Long = ${ffiPascal}Jni.invoke(symbol, fixedArgc, args)

            actual fun invokeFp(
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double = ${ffiPascal}Jni.invokeFp(symbol, fpMask, returnIsFloat32, args)

            actual fun resolveSymbolAddress(symbol: String): Long = ${ffiPascal}Jni.resolveSymbolAddress(symbol)

            actual fun lastErrorNumber(): Int = ${ffiPascal}Jni.lastErrorNumber()

            actual fun strlenAt(address: Long): Int = ${ffiPascal}Jni.strlenAt(address)

            actual fun allocZeroed(size: Int): Long = ${ffiPascal}Jni.allocZeroed(size)

            actual fun freeMem(address: Long) {
                ${ffiPascal}Jni.freeMem(address)
            }

            actual fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray = ${ffiPascal}Jni.readBytes(address, length)

            actual fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                ${ffiPascal}Jni.writeBytes(address, source)
            }
        }
        """.trimIndent() + "\n"

    val nodePackageJson =
        """
        {
          "name": "$npmPkg",
          "version": "0.1.0",
          "description": "N-API C++ addon providing dynamic host C bindings for Kotlin/JS and Kotlin/WASM targets",
          "main": "index.js",
          "dependencies": {
            "node-addon-api": "^8.0.0"
          },
          "gypfile": false
        }
        """.trimIndent() + "\n"

    val nodeBindingGyp =
        """
        {
          "targets": [
            {
              "target_name": "${ffiLower}_native",
              "sources": [
                "../src/${ffiLower}_bindings.cpp"
              ],
              "include_dirs": [
                "<!@(node -p \"require('node-addon-api').include\")"
              ],
              "dependencies": [
                "<!(node -p \"require('node-addon-api').gyp\")"
              ],
              "cflags!": [ "-fno-exceptions" ],
              "cflags_cc!": [ "-fno-exceptions" ],
              "defines": [ "NAPI_DISABLE_CPP_EXCEPTIONS" ],
              "xcode_settings": {
                "GCC_ENABLE_CPP_EXCEPTIONS": "YES",
                "CLANG_CXX_LIBRARY": "libc++",
                "MACOSX_DEPLOYMENT_TARGET": "10.15"
              }
            }
          ]
        }
        """.trimIndent() + "\n"

    val nodeIndexJs =
        """
        try {
            module.exports = require('./build/Release/${ffiLower}_native.node');
        } catch (err) {
            try {
                module.exports = require('./build/Debug/${ffiLower}_native.node');
            } catch (err2) {
                throw new Error(
                    'Could not load ${ffiLower}_native addon. ' +
                    'Original error: ' + err.message + ' | Debug error: ' + err2.message
                );
            }
        }
        """.trimIndent() + "\n"

    val nodeBindingsCpp = buildCDynamicNapiBindingsCpp()

    val jsActualKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class)

        package $ns

        // Hide require('$npmPkg') behind new Function so webpack browser bundles never scan it statically.
        private fun loadNodeLibcAddon(): dynamic =
            js(
                "(function(){ try { var rq = (new Function('return typeof require === \"function\" ? require : null'))(); if (!rq) return null; return rq('$npmPkg'); } catch (e) { return null; } })()",
            )

        internal actual object LibcDynamicFfiPlatform {
            private val addon: dynamic = loadNodeLibcAddon()
            private var arena = ByteArray(65536)
            private var arenaOffset = 64

            private fun packLoHi(
                lo: Int,
                hi: Int,
            ): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFF_FFFFL)

            actual fun isHostAvailable(): Boolean = addon != null

            actual fun invoke(
                symbol: String,
                fixedArgc: Int,
                args: LongArray,
            ): Long {
                val mod = addon ?: throw UnsupportedOperationException("C dynamic FFI (${'$'}symbol) requires a Node.js host")
                val flat = IntArray(args.size * 2)
                for (i in args.indices) {
                    flat[i * 2] = args[i].toInt()
                    flat[i * 2 + 1] = (args[i] ushr 32).toInt()
                }
                val res = mod.invokeLoHi(symbol, fixedArgc, flat)
                val status = res[2] as Int
                if (status != 0) {
                    throw UnsupportedOperationException("C symbol '${'$'}symbol' is unavailable on this host (${'$'}status)")
                }
                return packLoHi(res[0] as Int, res[1] as Int)
            }

            actual fun invokeFp(
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double {
                val mod = addon ?: throw UnsupportedOperationException("C dynamic FFI (${'$'}symbol) requires a Node.js host")
                val flat = IntArray(args.size * 2)
                for (i in args.indices) {
                    flat[i * 2] = args[i].toInt()
                    flat[i * 2 + 1] = (args[i] ushr 32).toInt()
                }
                val res = mod.invokeFpPair(symbol, fpMask, if (returnIsFloat32) 1 else 0, flat)
                val status = res[1] as Int
                if (status != 0) {
                    throw UnsupportedOperationException("C symbol '${'$'}symbol' is unavailable on this host (${'$'}status)")
                }
                return res[0] as Double
            }

            actual fun resolveSymbolAddress(symbol: String): Long {
                val mod = addon ?: return 0L
                val pair = mod.resolveSymbolLoHi(symbol)
                return packLoHi(pair[0] as Int, pair[1] as Int)
            }

            actual fun lastErrorNumber(): Int {
                val mod = addon ?: throw UnsupportedOperationException("errno requires a Node.js host")
                return mod.lastErrorNumber() as Int
            }

            actual fun strlenAt(address: Long): Int {
                if (address == 0L) return 0
                val mod = addon
                if (mod != null) {
                    return mod.strlenAtLoHi(address.toInt(), (address ushr 32).toInt()) as Int
                }
                var i = address.toInt()
                while (i < arena.size && arena[i] != 0.toByte()) i++
                return i - address.toInt()
            }

            actual fun allocZeroed(size: Int): Long {
                val n = maxOf(size, 1)
                val mod = addon
                if (mod != null) {
                    val pair = mod.allocZeroedLoHi(n)
                    return packLoHi(pair[0] as Int, pair[1] as Int)
                }
                val aligned = (arenaOffset + 7) and -8
                if (aligned + n > arena.size) {
                    arena = arena.copyOf(maxOf(arena.size * 2, aligned + n + 4096))
                }
                arena.fill(0, aligned, aligned + n)
                arenaOffset = aligned + n
                return aligned.toLong()
            }

            actual fun freeMem(address: Long) {
                val mod = addon
                if (mod != null && address != 0L) {
                    mod.freeMemLoHi(address.toInt(), (address ushr 32).toInt())
                }
            }

            actual fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray {
                if (length <= 0 || address == 0L) return ByteArray(0)
                val mod = addon
                if (mod != null) {
                    val raw = mod.readBytesLoHi(address.toInt(), (address ushr 32).toInt(), length)
                    val out = ByteArray(length)
                    for (i in 0 until length) {
                        out[i] = (raw[i] as Int).toByte()
                    }
                    return out
                }
                val start = address.toInt()
                return arena.copyOfRange(start, start + length)
            }

            actual fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                if (source.isEmpty() || address == 0L) return
                val mod = addon
                if (mod != null) {
                    val ints = IntArray(source.size) { source[it].toInt() and 0xFF }
                    mod.writeBytesLoHi(address.toInt(), (address ushr 32).toInt(), ints)
                    return
                }
                source.copyInto(arena, destinationOffset = address.toInt())
            }
        }
        """.trimIndent() + "\n"

    val wasmJsActualKt =
        """
        @file:OptIn(ExperimentalUnsignedTypes::class, kotlin.js.ExperimentalWasmJsInterop::class)

        package $ns

        // Hide require('$npmPkg') behind new Function so webpack browser bundles never scan it statically.
        private fun wasmHasNodeLibcAddon(): Boolean =
            js(
                "{ try { var rq = (new Function('return typeof require === \"function\" ? require : null'))(); if (!rq) return false; var m = rq('$npmPkg'); globalThis.__kmLibcAddon = m; return !!m; } catch (e) { return false; } }",
            )

        private fun wasmLibcInvokeCsv(
            symbol: String,
            fixedArgc: Int,
            flatCsv: String,
        ): String =
            js(
                "{ var parts = flatCsv.length === 0 ? [] : flatCsv.split(',').map(Number); var r = globalThis.__kmLibcAddon.invokeLoHi(symbol, fixedArgc, parts); return r[0] + ',' + r[1] + ',' + r[2]; }",
            )

        private fun wasmLibcInvokeFpCsv(
            symbol: String,
            fpMask: Int,
            retF32: Int,
            flatCsv: String,
        ): String =
            js(
                "{ var parts = flatCsv.length === 0 ? [] : flatCsv.split(',').map(Number); var r = globalThis.__kmLibcAddon.invokeFpPair(symbol, fpMask, retF32, parts); return r[0] + ',' + r[1]; }",
            )

        private fun wasmLibcResolveCsv(symbol: String): String =
            js("{ var r = globalThis.__kmLibcAddon.resolveSymbolLoHi(symbol); return r[0] + ',' + r[1]; }")

        private fun wasmLibcLastErrno(): Int =
            js("{ return globalThis.__kmLibcAddon.lastErrorNumber() | 0; }")

        private fun wasmLibcStrlenAt(
            lo: Int,
            hi: Int,
        ): Int =
            js("{ return globalThis.__kmLibcAddon.strlenAtLoHi(lo, hi) | 0; }")

        private fun wasmLibcAllocCsv(size: Int): String =
            js("{ var r = globalThis.__kmLibcAddon.allocZeroedLoHi(size); return r[0] + ',' + r[1]; }")

        private fun wasmLibcFree(
            lo: Int,
            hi: Int,
        ): Unit =
            js("{ globalThis.__kmLibcAddon.freeMemLoHi(lo, hi); }")

        private fun wasmLibcReadCsv(
            lo: Int,
            hi: Int,
            length: Int,
        ): String =
            js("{ var r = globalThis.__kmLibcAddon.readBytesLoHi(lo, hi, length); return Array.from(r).join(','); }")

        private fun wasmLibcWriteCsv(
            lo: Int,
            hi: Int,
            bytesCsv: String,
        ): Unit =
            js(
                "{ var arr = bytesCsv.length === 0 ? [] : bytesCsv.split(',').map(Number); globalThis.__kmLibcAddon.writeBytesLoHi(lo, hi, arr); }",
            )

        internal actual object LibcDynamicFfiPlatform {
            private val hasAddon: Boolean = wasmHasNodeLibcAddon()
            private var arena = ByteArray(65536)
            private var arenaOffset = 64

            private fun packLoHi(
                lo: Int,
                hi: Int,
            ): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFF_FFFFL)

            private fun encodeArgsCsv(args: LongArray): String {
                if (args.isEmpty()) return ""
                val sb = StringBuilder(args.size * 12)
                for (i in args.indices) {
                    if (i > 0) sb.append(',')
                    sb.append(args[i].toInt())
                    sb.append(',')
                    sb.append((args[i] ushr 32).toInt())
                }
                return sb.toString()
            }

            actual fun isHostAvailable(): Boolean = hasAddon

            actual fun invoke(
                symbol: String,
                fixedArgc: Int,
                args: LongArray,
            ): Long {
                if (!hasAddon) {
                    throw UnsupportedOperationException("C dynamic FFI (${'$'}symbol) requires a Node.js host")
                }
                val parts = wasmLibcInvokeCsv(symbol, fixedArgc, encodeArgsCsv(args)).split(',')
                val status = parts[2].toInt()
                if (status != 0) {
                    throw UnsupportedOperationException("C symbol '${'$'}symbol' is unavailable on this host (${'$'}status)")
                }
                return packLoHi(parts[0].toInt(), parts[1].toInt())
            }

            actual fun invokeFp(
                symbol: String,
                fpMask: Int,
                returnIsFloat32: Boolean,
                args: LongArray,
            ): Double {
                if (!hasAddon) {
                    throw UnsupportedOperationException("C dynamic FFI (${'$'}symbol) requires a Node.js host")
                }
                val parts = wasmLibcInvokeFpCsv(symbol, fpMask, if (returnIsFloat32) 1 else 0, encodeArgsCsv(args)).split(',')
                val status = parts[1].toInt()
                if (status != 0) {
                    throw UnsupportedOperationException("C symbol '${'$'}symbol' is unavailable on this host (${'$'}status)")
                }
                return parts[0].toDouble()
            }

            actual fun resolveSymbolAddress(symbol: String): Long {
                if (!hasAddon) return 0L
                val parts = wasmLibcResolveCsv(symbol).split(',')
                return packLoHi(parts[0].toInt(), parts[1].toInt())
            }

            actual fun lastErrorNumber(): Int {
                if (!hasAddon) throw UnsupportedOperationException("errno requires a Node.js host")
                return wasmLibcLastErrno()
            }

            actual fun strlenAt(address: Long): Int {
                if (address == 0L) return 0
                if (hasAddon) {
                    return wasmLibcStrlenAt(address.toInt(), (address ushr 32).toInt())
                }
                var i = address.toInt()
                while (i < arena.size && arena[i] != 0.toByte()) i++
                return i - address.toInt()
            }

            actual fun allocZeroed(size: Int): Long {
                val n = maxOf(size, 1)
                if (hasAddon) {
                    val parts = wasmLibcAllocCsv(n).split(',')
                    return packLoHi(parts[0].toInt(), parts[1].toInt())
                }
                val aligned = (arenaOffset + 7) and -8
                if (aligned + n > arena.size) {
                    arena = arena.copyOf(maxOf(arena.size * 2, aligned + n + 4096))
                }
                arena.fill(0, aligned, aligned + n)
                arenaOffset = aligned + n
                return aligned.toLong()
            }

            actual fun freeMem(address: Long) {
                if (hasAddon && address != 0L) {
                    wasmLibcFree(address.toInt(), (address ushr 32).toInt())
                }
            }

            actual fun readBytes(
                address: Long,
                length: Int,
            ): ByteArray {
                if (length <= 0 || address == 0L) return ByteArray(0)
                if (hasAddon) {
                    val csv = wasmLibcReadCsv(address.toInt(), (address ushr 32).toInt(), length)
                    if (csv.isEmpty()) return ByteArray(0)
                    val parts = csv.split(',')
                    return ByteArray(parts.size) { parts[it].toInt().toByte() }
                }
                val start = address.toInt()
                return arena.copyOfRange(start, start + length)
            }

            actual fun writeBytes(
                address: Long,
                source: ByteArray,
            ) {
                if (source.isEmpty() || address == 0L) return
                if (hasAddon) {
                    val csv = source.joinToString(",") { (it.toInt() and 0xFF).toString() }
                    wasmLibcWriteCsv(address.toInt(), (address ushr 32).toInt(), csv)
                    return
                }
                source.copyInto(arena, destinationOffset = address.toInt())
            }
        }
        """.trimIndent() + "\n"

    val wasmWasiActualKt = buildCDynamicWasmWasiActualKt(ns)

    return listOf(
        ProjectFfiScaffoldFile("src/common/commonMain/kotlin/$pkgPath/LibcDynamicFfi.kt", commonKt),
        ProjectFfiScaffoldFile("src/native/interop/cinterop/$ffiPascal.def", defContent),
        ProjectFfiScaffoldFile("src/native/interop/cinterop/${ffiLower}_wrapper.h", wrapperH),
        ProjectFfiScaffoldFile("src/native/interop/cinterop/${ffiLower}_wrapper.c", wrapperC),
        ProjectFfiScaffoldFile("src/native/nativeMain/kotlin/$pkgPath/LibcDynamicFfi.native.kt", nativeKt),
        ProjectFfiScaffoldFile("src/jvm/jvmMain/kotlin/$pkgPath/internal/${ffiPascal}Jni.kt", jvmJniLoaderKt),
        ProjectFfiScaffoldFile("src/jvm/jvmMain/kotlin/$pkgPath/LibcDynamicFfi.jvm.kt", jvmAndAndroidActualKt),
        ProjectFfiScaffoldFile("src/jvm/jvmMain/c/${ffiLower}_jni.c", jniC),
        ProjectFfiScaffoldFile("src/android/jvm/androidMain/kotlin/$pkgPath/internal/${ffiPascal}Jni.kt", androidJniLoaderKt),
        ProjectFfiScaffoldFile("src/android/jvm/androidMain/kotlin/$pkgPath/LibcDynamicFfi.android.kt", jvmAndAndroidActualKt),
        ProjectFfiScaffoldFile("src/js/node-$ffiLower/dist/package.json", nodePackageJson),
        ProjectFfiScaffoldFile("src/js/node-$ffiLower/dist/binding.gyp", nodeBindingGyp),
        ProjectFfiScaffoldFile("src/js/node-$ffiLower/dist/index.js", nodeIndexJs),
        ProjectFfiScaffoldFile("src/js/node-$ffiLower/src/${ffiLower}_bindings.cpp", nodeBindingsCpp),
        ProjectFfiScaffoldFile("src/js/jsMain/kotlin/$pkgPath/LibcDynamicFfi.js.kt", jsActualKt),
        ProjectFfiScaffoldFile("src/js/wasm/wasmJsMain/kotlin/$pkgPath/LibcDynamicFfi.wasmJs.kt", wasmJsActualKt),
        ProjectFfiScaffoldFile("src/js/wasm/wasmWasiMain/kotlin/$pkgPath/LibcDynamicFfi.wasmWasi.kt", wasmWasiActualKt),
    )
}

private fun buildCDynamicWasmWasiActualKt(ns: String): String =
    """
    @file:OptIn(
        ExperimentalUnsignedTypes::class,
        kotlin.wasm.ExperimentalWasmInterop::class,
        kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
    )

    package $ns

    import kotlin.wasm.WasmImport
    import kotlin.wasm.unsafe.withScopedMemoryAllocator

    @WasmImport("wasi_snapshot_preview1", "environ_sizes_get")
    private external fun wasiEnvironSizesGet(
        environCountPtr: Int,
        environBufSizePtr: Int,
    ): Int

    @WasmImport("wasi_snapshot_preview1", "environ_get")
    private external fun wasiEnvironGet(
        environPtr: Int,
        environBufPtr: Int,
    ): Int

    @WasmImport("wasi_snapshot_preview1", "fd_fdstat_get")
    private external fun wasiFdFdstatGet(
        fd: Int,
        statPtr: Int,
    ): Int

    @WasmImport("wasi_snapshot_preview1", "fd_close")
    private external fun wasiFdClose(fd: Int): Int

    @WasmImport("wasi_snapshot_preview1", "fd_prestat_get")
    private external fun wasiPrestatGet(
        fd: Int,
        resultPtr: Int,
    ): Int

    @WasmImport("wasi_snapshot_preview1", "fd_prestat_dir_name")
    private external fun wasiPrestatDirName(
        fd: Int,
        pathPtr: Int,
        pathLen: Int,
    ): Int

    @WasmImport("wasi_snapshot_preview1", "path_open")
    private external fun wasiPathOpen(
        dirFd: Int,
        dirflags: Int,
        pathPtr: Int,
        pathLen: Int,
        oflags: Int,
        fsRightsBase: Long,
        fsRightsInheriting: Long,
        fdflags: Int,
        openedFdPtr: Int,
    ): Int

    @WasmImport("wasi_snapshot_preview1", "path_unlink_file")
    private external fun wasiPathUnlinkFile(
        dirFd: Int,
        pathPtr: Int,
        pathLen: Int,
    ): Int

    @WasmImport("wasi_snapshot_preview1", "fd_read")
    private external fun wasiFdRead(
        fd: Int,
        iovsPtr: Int,
        iovsLen: Int,
        nreadPtr: Int,
    ): Int

    @WasmImport("wasi_snapshot_preview1", "fd_write")
    private external fun wasiFdWrite(
        fd: Int,
        iovsPtr: Int,
        iovsLen: Int,
        nwrittenPtr: Int,
    ): Int

    @WasmImport("wasi_snapshot_preview1", "fd_seek")
    private external fun wasiFdSeek(
        fd: Int,
        offset: Long,
        whence: Int,
        newoffsetPtr: Int,
    ): Int

    internal actual object LibcDynamicFfiPlatform {
        private var arena = ByteArray(131072)
        private var arenaOffset = 64
        private val allocSizes = HashMap<Long, Int>()
        private var envInitialized = false
        private val envMap = LinkedHashMap<String, String>()
        private val stringPool = HashMap<String, Long>()
        private var lastErrno = 0

        private data class PreopenCapability(
            val dirFd: Int,
            val relativePath: String,
        )

        private fun ensureEnv() {
            if (envInitialized) return
            envInitialized = true
            withScopedMemoryAllocator { allocator ->
                val countPtr = allocator.allocate(4)
                val bufSizePtr = allocator.allocate(4)
                if (wasiEnvironSizesGet(countPtr.address.toInt(), bufSizePtr.address.toInt()) != 0) return
                val count = countPtr.loadInt()
                val bufSize = bufSizePtr.loadInt()
                if (count <= 0 || bufSize <= 0) return
                val ptrs = allocator.allocate(count * 4)
                val buf = allocator.allocate(bufSize)
                if (wasiEnvironGet(ptrs.address.toInt(), buf.address.toInt()) != 0) return
                val baseAddr = buf.address.toInt()
                for (i in 0 until count) {
                    val entryAddr = (ptrs + i * 4).loadInt()
                    val offset = entryAddr - baseAddr
                    if (offset in 0 until bufSize) {
                        var len = 0
                        val tmp = ByteArray(bufSize - offset)
                        while (offset + len < bufSize) {
                            val b = (buf + (offset + len)).loadByte()
                            if (b == 0.toByte()) break
                            tmp[len] = b
                            len++
                        }
                        val entry = tmp.decodeToString(0, len)
                        val eq = entry.indexOf('=')
                        if (eq > 0) {
                            envMap[entry.substring(0, eq)] = entry.substring(eq + 1)
                        }
                    }
                }
            }
        }

        private fun internCString(s: String): Long {
            stringPool[s]?.let { return it }
            val utf8 = s.encodeToByteArray()
            val ptr = allocZeroed(utf8.size + 1)
            if (utf8.isNotEmpty()) writeBytes(ptr, utf8)
            stringPool[s] = ptr
            return ptr
        }

        private fun decodeCStringAt(addr: Long): String? {
            if (addr == 0L) return null
            val len = strlenAt(addr)
            return if (len <= 0) "" else readBytes(addr, len).decodeToString()
        }

        private fun resolveCapability(path: String): PreopenCapability? =
            withScopedMemoryAllocator { allocator ->
                var best: PreopenCapability? = null
                var bestPrefixLen = -1
                val prestat = allocator.allocate(8)
                for (fd in 3 until 64) {
                    if (wasiPrestatGet(fd, prestat.address.toInt()) != 0 || prestat.loadByte() != 0.toByte()) continue
                    val nameLen = (prestat + 4).loadInt()
                    if (nameLen !in 1..1_048_576) continue
                    val dirBuf = allocator.allocate(nameLen)
                    if (wasiPrestatDirName(fd, dirBuf.address.toInt(), nameLen) != 0) continue
                    val prefix = ByteArray(nameLen) { (dirBuf + it).loadByte() }.decodeToString().trimEnd('/').ifEmpty { "/" }
                    val rel =
                        when {
                            prefix == "." && !path.startsWith('/') -> path.removePrefix("./")
                            prefix == "/" && path.startsWith('/') -> path.removePrefix("/")
                            path.startsWith("${'$'}prefix/") -> path.substring(prefix.length + 1)
                            path == prefix -> "."
                            else -> continue
                        }
                    if (rel.split('/').any { it == ".." }) continue
                    if (prefix.length > bestPrefixLen) {
                        best = PreopenCapability(fd, rel)
                        bestPrefixLen = prefix.length
                    }
                }
                best
            }

        actual fun isHostAvailable(): Boolean = true

        actual fun invoke(
            symbol: String,
            fixedArgc: Int,
            args: LongArray,
        ): Long {
            fun a(i: Int): Long = if (i < args.size) args[i] else 0L
            return when (symbol) {
                "malloc" -> {
                    val sz = a(0).toInt().coerceAtLeast(1)
                    allocZeroed(sz)
                }
                "calloc" -> {
                    val total = (a(0) * a(1)).toInt().coerceAtLeast(1)
                    allocZeroed(total)
                }
                "realloc" -> {
                    val oldPtr = a(0)
                    val newSz = a(1).toInt().coerceAtLeast(1)
                    val newPtr = allocZeroed(newSz)
                    if (oldPtr != 0L) {
                        val oldSz = allocSizes[oldPtr] ?: 0
                        val copyLen = minOf(oldSz, newSz)
                        if (copyLen > 0) {
                            arena.copyInto(arena, destinationOffset = newPtr.toInt(), startIndex = oldPtr.toInt(), endIndex = oldPtr.toInt() + copyLen)
                        }
                    }
                    newPtr
                }
                "free" -> 0L
                "memset" -> {
                    val dst = a(0)
                    val byteVal = (a(1).toInt() and 0xFF).toByte()
                    val count = a(2).toInt()
                    if (dst != 0L && count > 0) {
                        arena.fill(byteVal, dst.toInt(), dst.toInt() + count)
                    }
                    dst
                }
                "memcpy", "memmove" -> {
                    val dst = a(0)
                    val src = a(1)
                    val count = a(2).toInt()
                    if (dst != 0L && src != 0L && count > 0) {
                        arena.copyInto(arena, destinationOffset = dst.toInt(), startIndex = src.toInt(), endIndex = src.toInt() + count)
                    }
                    dst
                }
                "memcmp" -> {
                    val cx = a(0).toInt()
                    val ct = a(1).toInt()
                    val n = a(2).toInt()
                    var diff = 0L
                    for (i in 0 until n) {
                        val b1 = arena[cx + i].toInt() and 0xFF
                        val b2 = arena[ct + i].toInt() and 0xFF
                        if (b1 != b2) {
                            diff = (b1 - b2).toLong()
                            break
                        }
                    }
                    diff
                }
                "getenv" -> {
                    ensureEnv()
                    val k = decodeCStringAt(a(0)) ?: return 0L
                    val v = envMap[k] ?: return 0L
                    internCString(v)
                }
                "setenv" -> {
                    val k = decodeCStringAt(a(0))
                    val v = decodeCStringAt(a(1))
                    val overwrite = a(2).toInt()
                    if (k.isNullOrEmpty() || v == null || '=' in k) {
                        lastErrno = 28
                        -1L
                    } else {
                        ensureEnv()
                        if (overwrite != 0 || !envMap.containsKey(k)) {
                            envMap[k] = v
                        }
                        0L
                    }
                }
                "unsetenv" -> {
                    val k = decodeCStringAt(a(0))
                    if (k.isNullOrEmpty() || '=' in k) {
                        lastErrno = 28
                        -1L
                    } else {
                        ensureEnv()
                        envMap.remove(k)
                        0L
                    }
                }
                "putenv", "_putenv" -> {
                    val kv = decodeCStringAt(a(0))
                    if (kv == null) {
                        lastErrno = 28
                        -1L
                    } else {
                        ensureEnv()
                        val eq = kv.indexOf('=')
                        if (eq < 0) {
                            envMap.remove(kv)
                            0L
                        } else if (eq == 0) {
                            lastErrno = 28
                            -1L
                        } else {
                            envMap[kv.substring(0, eq)] = kv.substring(eq + 1)
                            0L
                        }
                    }
                }
                "_putenv_s", "putenv_s" -> {
                    val k = decodeCStringAt(a(0))
                    val v = decodeCStringAt(a(1))
                    if (k.isNullOrEmpty() || v == null || '=' in k) {
                        lastErrno = 28
                        28L
                    } else {
                        ensureEnv()
                        if (v.isEmpty()) envMap.remove(k) else envMap[k] = v
                        0L
                    }
                }
                "clearenv" -> {
                    ensureEnv()
                    envMap.clear()
                    0L
                }
                "isatty", "_isatty" -> {
                    val fd = a(0).toInt()
                    withScopedMemoryAllocator { allocator ->
                        val statPtr = allocator.allocate(24)
                        val rc = wasiFdFdstatGet(fd, statPtr.address.toInt())
                        if (rc != 0) {
                            lastErrno = rc
                            0L
                        } else {
                            val filetype = statPtr.loadByte().toInt() and 0xFF
                            val rightsBase = (statPtr + 8).loadLong().toULong()
                            val seekOrTell = (1uL shl 2) or (1uL shl 5)
                            if (filetype == 2 && (rightsBase and seekOrTell) == 0uL) 1L else 0L
                        }
                    }
                }
                "close", "_close", "fclose" -> {
                    val fd = a(0).toInt()
                    val rc = wasiFdClose(fd)
                    if (rc != 0) {
                        lastErrno = rc
                        -1L
                    } else {
                        0L
                    }
                }
                "fopen" -> {
                    val path = decodeCStringAt(a(0))
                    val mode = decodeCStringAt(a(1))
                    if (path == null || mode.isNullOrEmpty() || mode[0] !in "rwa") {
                        lastErrno = 28
                        return 0L
                    }
                    val cap = resolveCapability(path)
                    if (cap == null) {
                        lastErrno = 76
                        return 0L
                    }
                    val bytes = cap.relativePath.encodeToByteArray()
                    val opened =
                        withScopedMemoryAllocator { allocator ->
                            val pBuf = allocator.allocate(bytes.size.coerceAtLeast(1))
                            bytes.forEachIndexed { idx, b -> (pBuf + idx).storeByte(b) }
                            val outFd = allocator.allocate(4)
                            val readable = mode[0] == 'r' || '+' in mode
                            val writable = mode[0] != 'r' || '+' in mode
                            val rights = (if (readable) 2L else 0L) or (if (writable) 64L else 0L) or 4L or 32L or (1L shl 21)
                            val oflags =
                                when (mode[0]) {
                                    'w' -> 1 or 8
                                    'a' -> 1
                                    else -> 0
                                }
                            val rc = wasiPathOpen(cap.dirFd, 1, pBuf.address.toInt(), bytes.size, oflags, rights, 0L, if (mode[0] == 'a') 1 else 0, outFd.address.toInt())
                            if (rc != 0) {
                                lastErrno = rc
                                -1
                            } else {
                                outFd.loadInt()
                            }
                        }
                    if (opened < 0) 0L else opened.toLong()
                }
                "open", "_open" -> {
                    val path = decodeCStringAt(a(0))
                    val oflag = a(1).toInt()
                    if (path == null) {
                        lastErrno = 21
                        return -1L
                    }
                    val cap = resolveCapability(path)
                    if (cap == null) {
                        lastErrno = 76
                        return -1L
                    }
                    val bytes = cap.relativePath.encodeToByteArray()
                    withScopedMemoryAllocator { allocator ->
                        val pBuf = allocator.allocate(bytes.size.coerceAtLeast(1))
                        bytes.forEachIndexed { idx, b -> (pBuf + idx).storeByte(b) }
                        val outFd = allocator.allocate(4)
                        val acc = oflag and 3
                        val readable = acc == 0 || acc == 2
                        val writable = acc == 1 || acc == 2
                        val rights = (if (readable) 2L else 0L) or (if (writable) 64L else 0L) or 4L or 32L or (1L shl 21)
                        val wasiOflags =
                            (if ((oflag and 0x0200) != 0 || (oflag and 0x0040) != 0 || (oflag and 0x0100) != 0) 1 else 0) or
                                (if ((oflag and 0x0400) != 0 || (oflag and 0x0200) != 0) 8 else 0)
                        val rc = wasiPathOpen(cap.dirFd, 1, pBuf.address.toInt(), bytes.size, wasiOflags, rights, 0L, 0, outFd.address.toInt())
                        if (rc != 0) {
                            lastErrno = rc
                            -1L
                        } else {
                            outFd.loadInt().toLong()
                        }
                    }
                }
                "read", "_read" -> {
                    val fd = a(0).toInt()
                    val dst = a(1).toInt()
                    val count = a(2).toInt()
                    if (count <= 0) return 0L
                    withScopedMemoryAllocator { allocator ->
                        val buf = allocator.allocate(count)
                        val iov = allocator.allocate(8)
                        iov.storeInt(buf.address.toInt())
                        (iov + 4).storeInt(count)
                        val nread = allocator.allocate(4)
                        val rc = wasiFdRead(fd, iov.address.toInt(), 1, nread.address.toInt())
                        if (rc != 0) {
                            lastErrno = rc
                            -1L
                        } else {
                            val n = nread.loadInt()
                            for (i in 0 until n) arena[dst + i] = (buf + i).loadByte()
                            n.toLong()
                        }
                    }
                }
                "write", "_write" -> {
                    val fd = a(0).toInt()
                    val src = a(1).toInt()
                    val count = a(2).toInt()
                    if (count <= 0) return 0L
                    withScopedMemoryAllocator { allocator ->
                        val buf = allocator.allocate(count)
                        for (i in 0 until count) (buf + i).storeByte(arena[src + i])
                        val iov = allocator.allocate(8)
                        iov.storeInt(buf.address.toInt())
                        (iov + 4).storeInt(count)
                        val nwritten = allocator.allocate(4)
                        val rc = wasiFdWrite(fd, iov.address.toInt(), 1, nwritten.address.toInt())
                        if (rc != 0) {
                            lastErrno = rc
                            -1L
                        } else {
                            nwritten.loadInt().toLong()
                        }
                    }
                }
                "lseek", "_lseek", "lseek64" -> {
                    val fd = a(0).toInt()
                    val offset = a(1)
                    val whence = a(2).toInt()
                    withScopedMemoryAllocator { allocator ->
                        val newOff = allocator.allocate(8)
                        val rc = wasiFdSeek(fd, offset, whence, newOff.address.toInt())
                        if (rc != 0) {
                            lastErrno = rc
                            -1L
                        } else {
                            newOff.loadLong()
                        }
                    }
                }
                "unlink", "_unlink", "remove" -> {
                    val path = decodeCStringAt(a(0))
                    if (path == null) {
                        lastErrno = 21
                        return -1L
                    }
                    val cap = resolveCapability(path)
                    if (cap == null) {
                        lastErrno = 76
                        return -1L
                    }
                    val bytes = cap.relativePath.encodeToByteArray()
                    withScopedMemoryAllocator { allocator ->
                        val pBuf = allocator.allocate(bytes.size.coerceAtLeast(1))
                        bytes.forEachIndexed { idx, b -> (pBuf + idx).storeByte(b) }
                        val rc = wasiPathUnlinkFile(cap.dirFd, pBuf.address.toInt(), bytes.size)
                        if (rc != 0) {
                            lastErrno = rc
                            -1L
                        } else {
                            0L
                        }
                    }
                }
                "strerror" -> internCString("WASI error ${'$'}{a(0).toInt()}")
                else -> throw UnsupportedOperationException("C symbol '${'$'}symbol' is unavailable on WASI preview1")
            }
        }

        actual fun invokeFp(
            symbol: String,
            fpMask: Int,
            returnIsFloat32: Boolean,
            args: LongArray,
        ): Double = throw UnsupportedOperationException("C symbol '${'$'}symbol' is unavailable on WASI preview1")

        actual fun resolveSymbolAddress(symbol: String): Long = 0L

        actual fun lastErrorNumber(): Int = lastErrno

        actual fun strlenAt(address: Long): Int {
            if (address == 0L) return 0
            var i = address.toInt()
            while (i < arena.size && arena[i] != 0.toByte()) i++
            return i - address.toInt()
        }

        actual fun allocZeroed(size: Int): Long {
            val n = maxOf(size, 1)
            val aligned = (arenaOffset + 7) and -8
            if (aligned + n > arena.size) {
                arena = arena.copyOf(maxOf(arena.size * 2, aligned + n + 4096))
            }
            arena.fill(0, aligned, aligned + n)
            arenaOffset = aligned + n
            val addr = aligned.toLong()
            allocSizes[addr] = n
            return addr
        }

        actual fun freeMem(address: Long) {
            if (address == 0L) return
        }

        actual fun readBytes(
            address: Long,
            length: Int,
        ): ByteArray {
            if (length <= 0 || address == 0L) return ByteArray(0)
            val start = address.toInt()
            return arena.copyOfRange(start, start + length)
        }

        actual fun writeBytes(
            address: Long,
            source: ByteArray,
        ) {
            if (source.isEmpty() || address == 0L) return
            source.copyInto(arena, destinationOffset = address.toInt())
        }
    }
    """.trimIndent() + "\n"

private fun buildCDynamicDispatchCSource(
    includeHeader: String?,
    prefix: String,
    includeJni: Boolean,
    jniPrefix: String,
): String {
    val sb = StringBuilder()
    sb.append("#ifndef _GNU_SOURCE\n#define _GNU_SOURCE\n#endif\n")
    if (includeHeader != null) {
        sb.append("#include \"").append(includeHeader).append("\"\n")
    }
    if (includeJni) {
        sb.append("#include <jni.h>\n")
    }
    sb.append("#include <stdint.h>\n")
    sb.append("#include <stdlib.h>\n")
    sb.append("#include <string.h>\n")
    sb.append("#include <stdio.h>\n")
    sb.append("#include <errno.h>\n")
    sb.append("#ifdef _WIN32\n")
    sb.append("#ifndef WIN32_LEAN_AND_MEAN\n#define WIN32_LEAN_AND_MEAN\n#endif\n")
    sb.append("#include <windows.h>\n")
    sb.append("#include <io.h>\n")
    sb.append("#include <direct.h>\n")
    sb.append("#include <process.h>\n")
    sb.append("#include <fcntl.h>\n")
    sb.append("#include <sys/stat.h>\n")
    sb.append("#else\n")
    sb.append("#include <dlfcn.h>\n")
    sb.append("#include <unistd.h>\n")
    sb.append("#include <fcntl.h>\n")
    sb.append("#ifdef __APPLE__\n")
    sb.append("#include <mach/mach_init.h>\n")
    sb.append("#endif\n")
    sb.append("#endif\n\n")

    sb.append("#if defined(_MSC_VER)\n")
    sb.append("#define KM_TLS __declspec(thread)\n")
    sb.append("#elif defined(__cplusplus)\n")
    sb.append("#define KM_TLS thread_local\n")
    sb.append("#else\n")
    sb.append("#define KM_TLS _Thread_local\n")
    sb.append("#endif\n")
    sb.append("static KM_TLS int32_t km_saved_errno = 0;\n\n")

    val storage = if (includeJni) "static " else ""

    sb.append("static uintptr_t km_c_errno_ptr(void) { return (uintptr_t)&errno; }\n\n")

    sb.append("#ifdef _WIN32\n")
    sb.append("static int km_c_setenv_win(const char* k, const char* v, int overwrite) {\n")
    sb.append("    if (!k || !v || k[0] == '\\0' || strchr(k, '=') != NULL) { errno = EINVAL; return -1; }\n")
    sb.append("    if (!overwrite && getenv(k) != NULL) return 0;\n")
    sb.append("    int err = _putenv_s(k, v);\n")
    sb.append("    if (err != 0) { errno = err; return -1; }\n")
    sb.append("    return 0;\n")
    sb.append("}\n")
    sb.append("static int km_c_unsetenv_win(const char* k) {\n")
    sb.append("    if (!k || k[0] == '\\0' || strchr(k, '=') != NULL) { errno = EINVAL; return -1; }\n")
    sb.append("    int err = _putenv_s(k, \"\");\n")
    sb.append("    if (err != 0) { errno = err; return -1; }\n")
    sb.append("    return 0;\n")
    sb.append("}\n")
    sb.append("static int64_t km_c_lseek64_win(int fd, int64_t offset, int whence) {\n")
    sb.append("    return (int64_t)_lseeki64(fd, (__int64)offset, whence);\n")
    sb.append("}\n")
    sb.append("static int km_c_open_win(const char* path, int oflag, int mode) {\n")
    sb.append("    return _open(path, oflag | _O_BINARY, mode & (_S_IREAD | _S_IWRITE));\n")
    sb.append("}\n")
    sb.append("static void* km_resolve_c_proc(const char* symbol) {\n")
    sb.append("    if (!symbol || symbol[0] == '\\0') return NULL;\n")
    sb.append("    if (strcmp(symbol, \"setenv\") == 0) { void* p = NULL; int (*f)(const char*, const char*, int) = km_c_setenv_win; memcpy(&p, &f, sizeof(p)); return p; }\n")
    sb.append("    if (strcmp(symbol, \"unsetenv\") == 0) { void* p = NULL; int (*f)(const char*) = km_c_unsetenv_win; memcpy(&p, &f, sizeof(p)); return p; }\n")
    sb.append("    if (strcmp(symbol, \"lseek\") == 0 || strcmp(symbol, \"lseek64\") == 0 || strcmp(symbol, \"_lseeki64\") == 0) { void* p = NULL; int64_t (*f)(int, int64_t, int) = km_c_lseek64_win; memcpy(&p, &f, sizeof(p)); return p; }\n")
    sb.append("    if (strcmp(symbol, \"open\") == 0) { void* p = NULL; int (*f)(const char*, int, int) = km_c_open_win; memcpy(&p, &f, sizeof(p)); return p; }\n")
    sb.append("    if (strcmp(symbol, \"__error\") == 0 || strcmp(symbol, \"__errno_location\") == 0 || strcmp(symbol, \"_errno\") == 0) { void* p = NULL; uintptr_t (*f)(void) = km_c_errno_ptr; memcpy(&p, &f, sizeof(p)); return p; }\n")
    sb.append("    static const char* dlls[] = { \"ucrtbase.dll\", \"msvcrt.dll\", \"kernel32.dll\", \"ws2_32.dll\", \"advapi32.dll\", \"ntdll.dll\", \"bcrypt.dll\", \"userenv.dll\", \"shell32.dll\", \"ole32.dll\" };\n")
    sb.append("    size_t i;\n")
    sb.append("    for (i = 0; i < sizeof(dlls) / sizeof(dlls[0]); ++i) {\n")
    sb.append("        HMODULE mod = GetModuleHandleA(dlls[i]);\n")
    sb.append("        if (!mod) mod = LoadLibraryA(dlls[i]);\n")
    sb.append("        if (mod) {\n")
    sb.append("            FARPROC fp = GetProcAddress(mod, symbol);\n")
    sb.append("            if (fp) { void* out = NULL; memcpy(&out, &fp, sizeof(out)); return out; }\n")
    sb.append("        }\n")
    sb.append("    }\n")
    sb.append("    if (symbol[0] != '_' && strlen(symbol) < 240) {\n")
    sb.append("        char underscored[256];\n")
    sb.append("        underscored[0] = '_';\n")
    sb.append("        strcpy(underscored + 1, symbol);\n")
    sb.append("        for (i = 0; i < 2; ++i) {\n")
    sb.append("            HMODULE mod = GetModuleHandleA(dlls[i]);\n")
    sb.append("            if (!mod) mod = LoadLibraryA(dlls[i]);\n")
    sb.append("            if (mod) {\n")
    sb.append("                FARPROC fp = GetProcAddress(mod, underscored);\n")
    sb.append("                if (fp) { void* out = NULL; memcpy(&out, &fp, sizeof(out)); return out; }\n")
    sb.append("            }\n")
    sb.append("        }\n")
    sb.append("    }\n")
    sb.append("    return NULL;\n")
    sb.append("}\n")
    sb.append("#else\n")
    sb.append("static int km_c_putenv_posix(const char* kv) {\n")
    sb.append("    if (!kv) { errno = EINVAL; return -1; }\n")
    sb.append("    const char* eq = strchr(kv, '=');\n")
    sb.append("    if (!eq) return unsetenv(kv);\n")
    sb.append("    if (eq == kv) { errno = EINVAL; return -1; }\n")
    sb.append("    size_t klen = (size_t)(eq - kv);\n")
    sb.append("    char* key = (char*)malloc(klen + 1u);\n")
    sb.append("    if (!key) { errno = ENOMEM; return -1; }\n")
    sb.append("    memcpy(key, kv, klen);\n")
    sb.append("    key[klen] = '\\0';\n")
    sb.append("    int rc = setenv(key, eq + 1, 1);\n")
    sb.append("    free(key);\n")
    sb.append("    return rc;\n")
    sb.append("}\n")
    sb.append("static int km_c_putenv_s_posix(const char* k, const char* v) {\n")
    sb.append("    if (!k || !v || k[0] == '\\0' || strchr(k, '=') != NULL) { errno = EINVAL; return EINVAL; }\n")
    sb.append("    if (v[0] == '\\0') return unsetenv(k) == 0 ? 0 : errno;\n")
    sb.append("    return setenv(k, v, 1) == 0 ? 0 : errno;\n")
    sb.append("}\n")
    sb.append("#ifdef __APPLE__\n")
    sb.append("static uintptr_t km_c_mach_task_self(void) { return (uintptr_t)mach_task_self(); }\n")
    sb.append("#endif\n")
    sb.append("static void* km_resolve_c_proc(const char* symbol) {\n")
    sb.append("    if (!symbol || symbol[0] == '\\0') return NULL;\n")
    sb.append("    if (strcmp(symbol, \"putenv\") == 0 || strcmp(symbol, \"_putenv\") == 0) { void* p = NULL; int (*f)(const char*) = km_c_putenv_posix; memcpy(&p, &f, sizeof(p)); return p; }\n")
    sb.append("    if (strcmp(symbol, \"_putenv_s\") == 0 || strcmp(symbol, \"putenv_s\") == 0) { void* p = NULL; int (*f)(const char*, const char*) = km_c_putenv_s_posix; memcpy(&p, &f, sizeof(p)); return p; }\n")
    sb.append("    if (strcmp(symbol, \"__error\") == 0 || strcmp(symbol, \"__errno_location\") == 0 || strcmp(symbol, \"_errno\") == 0) { void* p = NULL; uintptr_t (*f)(void) = km_c_errno_ptr; memcpy(&p, &f, sizeof(p)); return p; }\n")
    sb.append("#ifdef __APPLE__\n")
    sb.append("    if (strcmp(symbol, \"mach_task_self\") == 0) { void* p = NULL; uintptr_t (*f)(void) = km_c_mach_task_self; memcpy(&p, &f, sizeof(p)); return p; }\n")
    sb.append("#endif\n")
    sb.append("    void* p = dlsym(RTLD_DEFAULT, symbol);\n")
    sb.append("    if (!p && symbol[0] == '_' && symbol[1] != '\\0') {\n")
    sb.append("        p = dlsym(RTLD_DEFAULT, symbol + 1);\n")
    sb.append("    }\n")
    sb.append("    return p;\n")
    sb.append("}\n")
    sb.append("#endif\n\n")

    sb.append("static uintptr_t km_call_c_proc(void* proc, int32_t fixed_argc, int32_t argc, const int64_t* a) {\n")
    sb.append("    uintptr_t u[16];\n")
    sb.append("    int32_t i;\n")
    sb.append("    for (i = 0; i < 16; ++i) u[i] = (a && i < argc) ? (uintptr_t)a[i] : 0;\n")
    sb.append("    if (fixed_argc >= 1 && fixed_argc <= 6) {\n")
    sb.append("        int32_t extra = argc - fixed_argc;\n")
    sb.append("        if (extra < 0) extra = 0;\n")
    sb.append("        if (extra > 4) extra = 4;\n")
    sb.append("        switch (fixed_argc) {\n")
    for (fixed in 1..6) {
        val fixedParams = (0 until fixed).joinToString(", ") { "uintptr_t" }
        sb.append("            case $fixed: {\n")
        sb.append("                typedef uintptr_t (*vfn_t)($fixedParams, ...);\n")
        sb.append("                vfn_t fn = NULL;\n")
        sb.append("                memcpy(&fn, &proc, sizeof(fn));\n")
        sb.append("                switch (extra) {\n")
        for (ex in 0..4) {
            val totalArgs = (0 until (fixed + ex)).joinToString(", ") { "u[$it]" }
            sb.append("                    case $ex: return fn($totalArgs);\n")
        }
        sb.append("                    default: return 0;\n")
        sb.append("                }\n")
        sb.append("            }\n")
    }
    sb.append("            default: break;\n")
    sb.append("        }\n")
    sb.append("    }\n")
    sb.append("    switch (argc <= 0 ? 0 : (argc > 16 ? 16 : argc)) {\n")
    for (n in 0..16) {
        val paramTypes = if (n == 0) "void" else (0 until n).joinToString(", ") { "uintptr_t" }
        val argsCall = (0 until n).joinToString(", ") { "u[$it]" }
        sb.append("        case $n: { typedef uintptr_t (*fn_t)($paramTypes); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn($argsCall); }\n")
    }
    sb.append("        default: return 0;\n")
    sb.append("    }\n")
    sb.append("}\n\n")

    sb.append("static double km_c_bits_to_f64(int64_t bits) { double d = 0.0; memcpy(&d, &bits, sizeof(d)); return d; }\n")
    sb.append("static float km_c_bits_to_f32(int64_t bits) { uint32_t b = (uint32_t)bits; float f = 0.0f; memcpy(&f, &b, sizeof(f)); return f; }\n\n")

    sb.append("static double km_call_c_fp_proc(void* proc, int32_t fp_mask, int32_t ret_f32, int32_t argc, const int64_t* a) {\n")
    sb.append("    uintptr_t u[8];\n")
    sb.append("    int32_t i;\n")
    sb.append("    for (i = 0; i < 8; ++i) u[i] = (a && i < argc) ? (uintptr_t)a[i] : 0;\n")
    sb.append("    int32_t m0 = fp_mask & 3;\n")
    sb.append("    int32_t m1 = (fp_mask >> 2) & 3;\n")
    sb.append("    if (ret_f32) {\n")
    sb.append("        if (m0 == 1 && m1 == 1) { typedef float (*fn_t)(float, float, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return (double)fn(km_c_bits_to_f32(a ? a[0] : 0), km_c_bits_to_f32(a && argc > 1 ? a[1] : 0), u[2], u[3]); }\n")
    sb.append("        if (m0 == 1) { typedef float (*fn_t)(float, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return (double)fn(km_c_bits_to_f32(a ? a[0] : 0), u[1], u[2], u[3]); }\n")
    sb.append("        if (m0 == 2) { typedef float (*fn_t)(double, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return (double)fn(km_c_bits_to_f64(a ? a[0] : 0), u[1], u[2], u[3]); }\n")
    sb.append("        { typedef float (*fn_t)(uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return (double)fn(u[0], u[1], u[2], u[3], u[4], u[5], u[6], u[7]); }\n")
    sb.append("    } else {\n")
    sb.append("        if (m0 == 2 && m1 == 2) { typedef double (*fn_t)(double, double, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn(km_c_bits_to_f64(a ? a[0] : 0), km_c_bits_to_f64(a && argc > 1 ? a[1] : 0), u[2], u[3]); }\n")
    sb.append("        if (m0 == 2) { typedef double (*fn_t)(double, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn(km_c_bits_to_f64(a ? a[0] : 0), u[1], u[2], u[3]); }\n")
    sb.append("        if (m0 == 1) { typedef double (*fn_t)(float, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn(km_c_bits_to_f32(a ? a[0] : 0), u[1], u[2], u[3]); }\n")
    sb.append("        { typedef double (*fn_t)(uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t); fn_t fn = NULL; memcpy(&fn, &proc, sizeof(fn)); return fn(u[0], u[1], u[2], u[3], u[4], u[5], u[6], u[7]); }\n")
    sb.append("    }\n")
    sb.append("}\n\n")

    sb.append("${storage}int32_t ${prefix}_is_host_available(void) { return 1; }\n\n")

    sb.append("${storage}int64_t ${prefix}_invoke(const char* symbol, int32_t fixed_argc, int32_t argc, const int64_t* argv, int32_t* status_out) {\n")
    sb.append("    void* proc = km_resolve_c_proc(symbol);\n")
    sb.append("    if (!proc) { if (status_out) *status_out = -2; return 0; }\n")
    sb.append("    if (status_out) *status_out = 0;\n")
    sb.append("    errno = km_saved_errno;\n")
    sb.append("    uintptr_t res = km_call_c_proc(proc, fixed_argc, argc, argv);\n")
    sb.append("    km_saved_errno = (int32_t)errno;\n")
    sb.append("    return (int64_t)res;\n")
    sb.append("}\n\n")

    sb.append("${storage}double ${prefix}_invoke_fp(const char* symbol, int32_t fp_mask, int32_t ret_f32, int32_t argc, const int64_t* argv, int32_t* status_out) {\n")
    sb.append("    void* proc = km_resolve_c_proc(symbol);\n")
    sb.append("    if (!proc) { if (status_out) *status_out = -2; return 0.0; }\n")
    sb.append("    if (status_out) *status_out = 0;\n")
    sb.append("    errno = km_saved_errno;\n")
    sb.append("    double res = km_call_c_fp_proc(proc, fp_mask, ret_f32, argc, argv);\n")
    sb.append("    km_saved_errno = (int32_t)errno;\n")
    sb.append("    return res;\n")
    sb.append("}\n\n")

    sb.append("${storage}int64_t ${prefix}_resolve_symbol_address(const char* symbol) {\n")
    sb.append("    if (!symbol || symbol[0] == '\\0') return 0;\n")
    sb.append("#ifdef _WIN32\n")
    sb.append("    static const char* dlls[] = { \"ucrtbase.dll\", \"msvcrt.dll\", \"kernel32.dll\", \"ws2_32.dll\" };\n")
    sb.append("    size_t i;\n")
    sb.append("    for (i = 0; i < sizeof(dlls) / sizeof(dlls[0]); ++i) {\n")
    sb.append("        HMODULE mod = GetModuleHandleA(dlls[i]);\n")
    sb.append("        if (!mod) mod = LoadLibraryA(dlls[i]);\n")
    sb.append("        if (mod) {\n")
    sb.append("            FARPROC fp = GetProcAddress(mod, symbol);\n")
    sb.append("            if (fp) return (int64_t)(uintptr_t)fp;\n")
    sb.append("        }\n")
    sb.append("    }\n")
    sb.append("    return 0;\n")
    sb.append("#else\n")
    sb.append("#ifdef __APPLE__\n")
    sb.append("    if (strcmp(symbol, \"mach_task_self_\") == 0) {\n")
    sb.append("        void* p = dlsym(RTLD_DEFAULT, \"mach_task_self_\");\n")
    sb.append("        if (p) return (int64_t)(uintptr_t)p;\n")
    sb.append("        return (int64_t)(uintptr_t)&mach_task_self_;\n")
    sb.append("    }\n")
    sb.append("#endif\n")
    sb.append("    return (int64_t)(uintptr_t)dlsym(RTLD_DEFAULT, symbol);\n")
    sb.append("#endif\n")
    sb.append("}\n\n")

    sb.append("${storage}int32_t ${prefix}_last_error_number(void) {\n")
    sb.append("    return km_saved_errno;\n")
    sb.append("}\n\n")

    sb.append("${storage}int32_t ${prefix}_strlen_at(int64_t addr) {\n")
    sb.append("    if (addr == 0) return 0;\n")
    sb.append("    return (int32_t)strlen((const char*)(uintptr_t)addr);\n")
    sb.append("}\n\n")

    sb.append("${storage}int64_t ${prefix}_alloc_zeroed(int32_t size) {\n")
    sb.append("    size_t n = size > 0 ? (size_t)size : 1u;\n")
    sb.append("    void* p = calloc(1u, n);\n")
    sb.append("    return (int64_t)(uintptr_t)p;\n")
    sb.append("}\n\n")

    sb.append("${storage}void ${prefix}_free_mem(int64_t addr) {\n")
    sb.append("    if (addr != 0) free((void*)(uintptr_t)addr);\n")
    sb.append("}\n\n")

    sb.append("${storage}void ${prefix}_read_bytes(int64_t addr, int32_t len, uint8_t* dst) {\n")
    sb.append("    if (addr != 0 && len > 0 && dst) memcpy(dst, (const void*)(uintptr_t)addr, (size_t)len);\n")
    sb.append("}\n\n")

    sb.append("${storage}void ${prefix}_write_bytes(int64_t addr, int32_t len, const uint8_t* src) {\n")
    sb.append("    if (addr != 0 && len > 0 && src) {\n")
    sb.append("        memcpy((void*)(uintptr_t)addr, src, (size_t)len);\n")
    sb.append("        if ((uintptr_t)addr == (uintptr_t)&errno && len == (int32_t)sizeof(int)) {\n")
    sb.append("            km_saved_errno = (int32_t)errno;\n")
    sb.append("        }\n")
    sb.append("    }\n")
    sb.append("}\n")

    if (includeJni) {
        sb.append("\nstatic void km_throw_c_jni_status(JNIEnv* env, int32_t status, const char* sym) {\n")
        sb.append("    char msg[256];\n")
        sb.append("    jclass ex = (*env)->FindClass(env, \"java/lang/UnsupportedOperationException\");\n")
        sb.append("    if (!ex) return;\n")
        sb.append("    snprintf(msg, sizeof(msg), \"C symbol '%s' is unavailable on this host (%d)\", sym ? sym : \"\", (int)status);\n")
        sb.append("    (*env)->ThrowNew(env, ex, msg);\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jboolean JNICALL ${jniPrefix}isHostAvailable(JNIEnv* env, jclass clazz) {\n")
        sb.append("    (void)env; (void)clazz;\n")
        sb.append("    return ${prefix}_is_host_available() ? JNI_TRUE : JNI_FALSE;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jlong JNICALL ${jniPrefix}invoke(JNIEnv* env, jclass clazz, jstring symbol, jint fixedArgc, jlongArray args) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    const char* c_sym = symbol ? (*env)->GetStringUTFChars(env, symbol, NULL) : NULL;\n")
        sb.append("    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;\n")
        sb.append("    jlong* elems = (args && argc > 0) ? (*env)->GetLongArrayElements(env, args, NULL) : NULL;\n")
        sb.append("    int32_t status = 0;\n")
        sb.append("    int64_t ret = ${prefix}_invoke(c_sym, (int32_t)fixedArgc, (int32_t)argc, (const int64_t*)elems, &status);\n")
        sb.append("    if (elems) (*env)->ReleaseLongArrayElements(env, args, elems, JNI_ABORT);\n")
        sb.append("    if (status != 0) km_throw_c_jni_status(env, status, c_sym);\n")
        sb.append("    if (c_sym) (*env)->ReleaseStringUTFChars(env, symbol, c_sym);\n")
        sb.append("    return (jlong)ret;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jdouble JNICALL ${jniPrefix}invokeFp(JNIEnv* env, jclass clazz, jstring symbol, jint fpMask, jboolean retF32, jlongArray args) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    const char* c_sym = symbol ? (*env)->GetStringUTFChars(env, symbol, NULL) : NULL;\n")
        sb.append("    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;\n")
        sb.append("    jlong* elems = (args && argc > 0) ? (*env)->GetLongArrayElements(env, args, NULL) : NULL;\n")
        sb.append("    int32_t status = 0;\n")
        sb.append("    double ret = ${prefix}_invoke_fp(c_sym, (int32_t)fpMask, retF32 ? 1 : 0, (int32_t)argc, (const int64_t*)elems, &status);\n")
        sb.append("    if (elems) (*env)->ReleaseLongArrayElements(env, args, elems, JNI_ABORT);\n")
        sb.append("    if (status != 0) km_throw_c_jni_status(env, status, c_sym);\n")
        sb.append("    if (c_sym) (*env)->ReleaseStringUTFChars(env, symbol, c_sym);\n")
        sb.append("    return (jdouble)ret;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jlong JNICALL ${jniPrefix}resolveSymbolAddress(JNIEnv* env, jclass clazz, jstring symbol) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    const char* c_sym = symbol ? (*env)->GetStringUTFChars(env, symbol, NULL) : NULL;\n")
        sb.append("    int64_t addr = ${prefix}_resolve_symbol_address(c_sym);\n")
        sb.append("    if (c_sym) (*env)->ReleaseStringUTFChars(env, symbol, c_sym);\n")
        sb.append("    return (jlong)addr;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jint JNICALL ${jniPrefix}lastErrorNumber(JNIEnv* env, jclass clazz) {\n")
        sb.append("    (void)env; (void)clazz;\n")
        sb.append("    return (jint)${prefix}_last_error_number();\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jint JNICALL ${jniPrefix}strlenAt(JNIEnv* env, jclass clazz, jlong address) {\n")
        sb.append("    (void)env; (void)clazz;\n")
        sb.append("    return (jint)${prefix}_strlen_at((int64_t)address);\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jlong JNICALL ${jniPrefix}allocZeroed(JNIEnv* env, jclass clazz, jint size) {\n")
        sb.append("    (void)env; (void)clazz;\n")
        sb.append("    return (jlong)${prefix}_alloc_zeroed((int32_t)size);\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT void JNICALL ${jniPrefix}freeMem(JNIEnv* env, jclass clazz, jlong address) {\n")
        sb.append("    (void)env; (void)clazz;\n")
        sb.append("    ${prefix}_free_mem((int64_t)address);\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT jbyteArray JNICALL ${jniPrefix}readBytes(JNIEnv* env, jclass clazz, jlong address, jint length) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    jsize n = (length > 0 && address != 0) ? (jsize)length : 0;\n")
        sb.append("    jbyteArray out = (*env)->NewByteArray(env, n);\n")
        sb.append("    if (out && n > 0) {\n")
        sb.append("        (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte*)(uintptr_t)address);\n")
        sb.append("    }\n")
        sb.append("    return out;\n")
        sb.append("}\n\n")
        sb.append("JNIEXPORT void JNICALL ${jniPrefix}writeBytes(JNIEnv* env, jclass clazz, jlong address, jbyteArray source) {\n")
        sb.append("    (void)clazz;\n")
        sb.append("    if (!source || address == 0) return;\n")
        sb.append("    jsize n = (*env)->GetArrayLength(env, source);\n")
        sb.append("    if (n > 0) {\n")
        sb.append("        jbyte* elems = (*env)->GetByteArrayElements(env, source, NULL);\n")
        sb.append("        if (elems) {\n")
        sb.append("            ${prefix}_write_bytes((int64_t)address, (int32_t)n, (const uint8_t*)elems);\n")
        sb.append("            (*env)->ReleaseByteArrayElements(env, source, elems, JNI_ABORT);\n")
        sb.append("        }\n")
        sb.append("    }\n")
        sb.append("}\n")
    }
    return sb.toString()
}

private fun buildCDynamicNapiBindingsCpp(): String {
    val body =
        buildCDynamicDispatchCSource(
            includeHeader = null,
            prefix = "napi_c",
            includeJni = false,
            jniPrefix = "",
        )
    return buildString {
        append("#include <napi.h>\n")
        append("#include <cstdint>\n")
        append("#include <cstdlib>\n")
        append("#include <cstring>\n")
        append("#include <string>\n")
        append("#include <vector>\n\n")
        append(body)
        append(
            """

            namespace {

            int64_t unpackLoHi(int32_t lo, int32_t hi) {
                return (static_cast<int64_t>(hi) << 32) | (static_cast<int64_t>(static_cast<uint32_t>(lo)));
            }

            std::vector<int64_t> extractArgsFromFlatArray(const Napi::Array& flat) {
                uint32_t len = flat.Length();
                uint32_t count = len / 2;
                std::vector<int64_t> out(count);
                for (uint32_t i = 0; i < count; ++i) {
                    int32_t lo = flat.Get(i * 2).As<Napi::Number>().Int32Value();
                    int32_t hi = flat.Get(i * 2 + 1).As<Napi::Number>().Int32Value();
                    out[i] = unpackLoHi(lo, hi);
                }
                return out;
            }

            Napi::Value InvokeLoHiWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                std::string sym = info[0].As<Napi::String>().Utf8Value();
                int32_t fixedArgc = info[1].As<Napi::Number>().Int32Value();
                std::vector<int64_t> argv = extractArgsFromFlatArray(info[2].As<Napi::Array>());
                int32_t status = 0;
                int64_t ret = napi_c_invoke(sym.c_str(), fixedArgc, static_cast<int32_t>(argv.size()), argv.empty() ? nullptr : argv.data(), &status);
                Napi::Array out = Napi::Array::New(env, 3);
                out.Set(uint32_t{0}, Napi::Number::New(env, static_cast<int32_t>(ret & 0xFFFFFFFFLL)));
                out.Set(uint32_t{1}, Napi::Number::New(env, static_cast<int32_t>(static_cast<uint64_t>(ret) >> 32)));
                out.Set(uint32_t{2}, Napi::Number::New(env, status));
                return out;
            }

            Napi::Value InvokeFpPairWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                std::string sym = info[0].As<Napi::String>().Utf8Value();
                int32_t fpMask = info[1].As<Napi::Number>().Int32Value();
                int32_t retF32 = info[2].As<Napi::Number>().Int32Value();
                std::vector<int64_t> argv = extractArgsFromFlatArray(info[3].As<Napi::Array>());
                int32_t status = 0;
                double ret = napi_c_invoke_fp(sym.c_str(), fpMask, retF32, static_cast<int32_t>(argv.size()), argv.empty() ? nullptr : argv.data(), &status);
                Napi::Array out = Napi::Array::New(env, 2);
                out.Set(uint32_t{0}, Napi::Number::New(env, ret));
                out.Set(uint32_t{1}, Napi::Number::New(env, status));
                return out;
            }

            Napi::Value ResolveSymbolLoHiWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                std::string sym = info[0].As<Napi::String>().Utf8Value();
                int64_t addr = napi_c_resolve_symbol_address(sym.c_str());
                Napi::Array out = Napi::Array::New(env, 2);
                out.Set(uint32_t{0}, Napi::Number::New(env, static_cast<int32_t>(addr & 0xFFFFFFFFLL)));
                out.Set(uint32_t{1}, Napi::Number::New(env, static_cast<int32_t>(static_cast<uint64_t>(addr) >> 32)));
                return out;
            }

            Napi::Value LastErrorNumberWrapped(const Napi::CallbackInfo& info) {
                return Napi::Number::New(info.Env(), napi_c_last_error_number());
            }

            Napi::Value StrlenAtLoHiWrapped(const Napi::CallbackInfo& info) {
                int32_t lo = info[0].As<Napi::Number>().Int32Value();
                int32_t hi = info[1].As<Napi::Number>().Int32Value();
                return Napi::Number::New(info.Env(), napi_c_strlen_at(unpackLoHi(lo, hi)));
            }

            Napi::Value AllocZeroedLoHiWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                int32_t size = info[0].As<Napi::Number>().Int32Value();
                int64_t ptr = napi_c_alloc_zeroed(size);
                Napi::Array out = Napi::Array::New(env, 2);
                out.Set(uint32_t{0}, Napi::Number::New(env, static_cast<int32_t>(ptr & 0xFFFFFFFFLL)));
                out.Set(uint32_t{1}, Napi::Number::New(env, static_cast<int32_t>(static_cast<uint64_t>(ptr) >> 32)));
                return out;
            }

            Napi::Value FreeMemLoHiWrapped(const Napi::CallbackInfo& info) {
                int32_t lo = info[0].As<Napi::Number>().Int32Value();
                int32_t hi = info[1].As<Napi::Number>().Int32Value();
                napi_c_free_mem(unpackLoHi(lo, hi));
                return info.Env().Undefined();
            }

            Napi::Value ReadBytesLoHiWrapped(const Napi::CallbackInfo& info) {
                Napi::Env env = info.Env();
                int32_t lo = info[0].As<Napi::Number>().Int32Value();
                int32_t hi = info[1].As<Napi::Number>().Int32Value();
                int32_t len = info[2].As<Napi::Number>().Int32Value();
                int64_t addr = unpackLoHi(lo, hi);
                uint32_t count = (len > 0 && addr != 0) ? static_cast<uint32_t>(len) : 0;
                Napi::Array out = Napi::Array::New(env, count);
                if (count > 0) {
                    std::vector<uint8_t> buf(count);
                    napi_c_read_bytes(addr, len, buf.data());
                    for (uint32_t i = 0; i < count; ++i) {
                        out.Set(i, Napi::Number::New(env, static_cast<int32_t>(buf[i])));
                    }
                }
                return out;
            }

            Napi::Value WriteBytesLoHiWrapped(const Napi::CallbackInfo& info) {
                int32_t lo = info[0].As<Napi::Number>().Int32Value();
                int32_t hi = info[1].As<Napi::Number>().Int32Value();
                Napi::Array src = info[2].As<Napi::Array>();
                int64_t addr = unpackLoHi(lo, hi);
                uint32_t count = src.Length();
                if (count > 0 && addr != 0) {
                    std::vector<uint8_t> buf(count);
                    for (uint32_t i = 0; i < count; ++i) {
                        buf[i] = static_cast<uint8_t>(src.Get(i).As<Napi::Number>().Int32Value() & 0xFF);
                    }
                    napi_c_write_bytes(addr, static_cast<int32_t>(count), buf.data());
                }
                return info.Env().Undefined();
            }

            Napi::Object Init(Napi::Env env, Napi::Object exports) {
                exports.Set("invokeLoHi", Napi::Function::New(env, InvokeLoHiWrapped));
                exports.Set("invokeFpPair", Napi::Function::New(env, InvokeFpPairWrapped));
                exports.Set("resolveSymbolLoHi", Napi::Function::New(env, ResolveSymbolLoHiWrapped));
                exports.Set("lastErrorNumber", Napi::Function::New(env, LastErrorNumberWrapped));
                exports.Set("strlenAtLoHi", Napi::Function::New(env, StrlenAtLoHiWrapped));
                exports.Set("allocZeroedLoHi", Napi::Function::New(env, AllocZeroedLoHiWrapped));
                exports.Set("freeMemLoHi", Napi::Function::New(env, FreeMemLoHiWrapped));
                exports.Set("readBytesLoHi", Napi::Function::New(env, ReadBytesLoHiWrapped));
                exports.Set("writeBytesLoHi", Napi::Function::New(env, WriteBytesLoHiWrapped));
                return exports;
            }

            }  // namespace

            NODE_API_MODULE(addon, Init)
            """.trimIndent(),
        )
        append('\n')
    }
}
