package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.BinOp
import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.Pat
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.ReturnType
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.UnOp
import io.github.kotlinmania.syn.WhereClause
import io.github.kotlinmania.syn.WherePredicate
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.span

internal data class TypeParamWitnessSpec(
    val keyTp: String,
    val actualTp: String,
    val rawMethodName: String,
    val paramName: String,
    val kotlinFnType: String,
    val defaultExpr: String? = null,
)

internal fun KotlinJ2KEmitter.liftDefaultlessAssocTypesFromBounds(
    combinedParams: List<GenericParam>,
    ownerWhere: WhereClause?,
    fnWhere: WhereClause?,
): List<String> {
    val existingParams =
        combinedParams
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .toSet()
    val lifted = mutableListOf<String>()
    fun inspectBounds(bounds: List<TypeParamBound>) {
        for (b in bounds) {
            if (b is TypeParamBound.Trait) {
                val lastSeg = b.path.segments.last() ?: continue
                if (lastSeg.arguments is PathArguments.None) {
                    val ktTrait = rustTypeIdentToKotlin(lastSeg.ident.toString())
                    for (assoc in inspector.traitDefaultlessAssocTypes[ktTrait].orEmpty()) {
                        if (assoc !in existingParams && assoc !in lifted) {
                            lifted += assoc
                        }
                    }
                }
            }
        }
    }
    for (gp in combinedParams) {
        if (gp is GenericParam.TypeParam) {
            inspectBounds(gp.bounds.toList())
        }
    }
    for (pred in ownerWhere?.predicates?.toList().orEmpty() + fnWhere?.predicates?.toList().orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            inspectBounds(pred.bounds.toList())
        }
    }
    return lifted
}

internal fun KotlinJ2KEmitter.collectTypeParamWitnessSpecs(
    sig: Signature,
    combinedParams: List<GenericParam>,
    ownerWhere: WhereClause?,
    implWhere: WhereClause?,
    fnWhere: WhereClause?,
    block: Block?,
    selfTypeName: String?,
    suppressedBoundsOut: MutableMap<String, MutableSet<String>>,
): List<TypeParamWitnessSpec> {
    if (block == null) return emptyList()
    val orderedTypeParams =
        combinedParams
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .filter { it !in substitutedTypeParamTypes }
            .distinct()
    val fnTypeParams = orderedTypeParams.toSet()
    if (fnTypeParams.isEmpty()) return emptyList()

    val boundsByTp = linkedMapOf<String, MutableSet<String>>()
    fun recordBounds(tpName: String, bounds: List<TypeParamBound>) {
        if (tpName !in fnTypeParams) return
        val set = boundsByTp.getOrPut(tpName) { linkedSetOf() }
        for (b in bounds) {
            if (b is TypeParamBound.Trait && b.maybe == null) {
                val lastSeg = b.path.segments.last() ?: continue
                val last = lastSeg.ident.toString()
                set += rustTypeIdentToKotlin(last)
            }
        }
    }
    for (gp in combinedParams) {
        if (gp is GenericParam.TypeParam) {
            recordBounds(rustTypeIdentToKotlin(gp.ident.toString()), gp.bounds.toList())
        }
    }
    val allWherePreds =
        ownerWhere?.predicates?.toList().orEmpty() +
            implWhere?.predicates?.toList().orEmpty() +
            fnWhere?.predicates?.toList().orEmpty()
    for (pred in allWherePreds) {
        if (pred is WherePredicate.TypePredicate) {
            recordBounds(renderType(pred.boundedTy, selfTypeName), pred.bounds.toList())
        }
    }
    val defaultBoundedParams = orderedTypeParams.filter { "Default" in boundsByTp[it].orEmpty() }

    val localRawTypes = linkedMapOf<String, String>()
    for (arg in sig.inputs.toList()) {
        if (arg is FnArg.Typed) {
            val rawIdent = extractRawParamIdent(arg.patType.pat)
            val renderedTy = renderType(arg.patType.ty, selfTypeName)
            if (rawIdent != null && renderedTy in fnTypeParams) {
                localRawTypes[rawIdent] = renderedTy
            }
        }
    }
    val patScanner =
        object : Visit() {
            override fun visitItemFn(i: Item.Fn) {}

            override fun visitPatTupleStruct(p: Pat.TupleStruct) {
                val variantKey =
                    p.path.segments
                        .toList()
                        .filter { it.ident.toString() !in IGNORED_PATH_PREFIXES }
                        .joinToString(".") { rustTypeIdentToKotlin(it.ident.toString()) }
                val fieldTys =
                    inspector.enumVariantFieldTypes[variantKey]
                        ?: inspector.enumVariantFieldTypes[variantKey.substringAfterLast('.')]
                if (fieldTys != null) {
                    for ((idx, sub) in p.elems.toList().withIndex()) {
                        val rawSub = extractRawParamIdent(sub)
                        val fTy = fieldTys.getOrNull(idx)?.let { renderType(it, selfTypeName) }
                        if (rawSub != null && fTy != null && fTy in fnTypeParams) {
                            localRawTypes[rawSub] = fTy
                        }
                    }
                }
                super.visitPatTupleStruct(p)
            }
        }
    patScanner.visitBlock(block)

    val discovered = linkedMapOf<Pair<String, String>, String>()
    var usesDefaultForwarder = false
    var usesZeroArgParse = false
    var usesParseForwarder = false
    var usesTokenForwarder = false
    val clonedTypeParams = linkedSetOf<String>()

    val visitor =
        object : Visit() {
            override fun visitItemFn(i: Item.Fn) {}

            override fun visitExprPath(e: Expr.Path) {
                val qself = e.qself
                if (qself != null) {
                    val baseTy = renderType(qself.ty, selfTypeName)
                    val lastSeg = e.path.segments.last()
                    val lastIdent = lastSeg?.ident?.toString()
                    val lastRaw = lastIdent?.removePrefix("r#")
                    if (baseTy in fnTypeParams && lastRaw != null && lastRaw.firstOrNull()?.isLowerCase() == true) {
                        discovered.getOrPut(baseTy to lastRaw) { baseTy }
                    }
                } else {
                    val segs = e.path.segments.toList()
                    if (segs.size == 2) {
                        val rawFirst = segs[0].ident.toString()
                        val firstKt = rustTypeIdentToKotlin(rawFirst)
                        val secondRaw = segs[1].ident.toString().removePrefix("r#")
                        if (rawFirst.firstOrNull()?.isUpperCase() == true &&
                            firstKt in fnTypeParams &&
                            secondRaw.firstOrNull()?.isLowerCase() == true
                        ) {
                            discovered.getOrPut(firstKt to secondRaw) { firstKt }
                        } else if (rawFirst == "Default" && secondRaw == "default" && defaultBoundedParams.size == 1) {
                            discovered.getOrPut("*" to "default") { defaultBoundedParams[0] }
                        }
                    }
                }
                super.visitExprPath(e)
            }

            override fun visitExprCall(e: Expr.Call) {
                val fnPath = (e.func as? Expr.Path)?.takeIf { it.qself == null }?.path
                val lastCallSeg = fnPath?.segments?.last()
                val lastCallIdent = lastCallSeg?.ident?.toString()
                when (lastCallIdent) {
                    "parse_terminated", "parse_terminated_with" -> usesParseForwarder = true
                    "parse_separated_nonempty", "parse_separated_nonempty_with" -> {
                        usesParseForwarder = true
                        usesTokenForwarder = true
                    }
                }
                super.visitExprCall(e)
            }

            override fun visitExprMethodCall(e: Expr.MethodCall) {
                val m = e.method.toString()
                val argCount = e.args.toList().count { !(it is Expr.Tuple && it.elems.isEmpty()) }
                if (m == "parse" && argCount == 0 && e.turbofish == null) {
                    usesZeroArgParse = true
                } else if ((m == "push" || m == "insert" || m == "extend") && argCount >= 1) {
                    usesDefaultForwarder = true
                } else if (m == "clone" && argCount == 0 && e.turbofish == null) {
                    val recvCore = unwrapTransparentCastExpr(e.receiver)
                    val recvRaw =
                        if (recvCore is Expr.Path && recvCore.qself == null && recvCore.path.segments.len() == 1) {
                            val firstRecvSeg = recvCore.path.segments.first()
                            firstRecvSeg?.ident?.toString()?.removePrefix("r#")
                        } else {
                            null
                        }
                    val tp = recvRaw?.let { localRawTypes[it] }
                    if (tp != null && "Clone" in boundsByTp[tp].orEmpty()) {
                        clonedTypeParams += tp
                    }
                }
                super.visitExprMethodCall(e)
            }
        }
    visitor.visitBlock(block)

    if (usesDefaultForwarder) {
        for (tp in defaultBoundedParams) {
            if (discovered.none { it.value == tp && it.key.second == "default" }) {
                discovered[tp to "default"] = tp
            }
        }
    }
    if (usesZeroArgParse || usesParseForwarder) {
        for (tp in orderedTypeParams) {
            val tpBounds = boundsByTp[tp].orEmpty()
            if (("Parse" in tpBounds || "ParseQuote" in tpBounds) &&
                discovered.none { it.value == tp && it.key.second == "parse" }
            ) {
                discovered[tp to "parse"] = tp
            }
        }
    }
    if (usesTokenForwarder) {
        for (tp in orderedTypeParams) {
            if ("Token" in boundsByTp[tp].orEmpty() &&
                discovered.none { it.value == tp && it.key.second == "peek" }
            ) {
                discovered[tp to "peek"] = tp
            }
        }
    }
    for (tp in orderedTypeParams) {
        if (tp in clonedTypeParams && discovered.none { it.value == tp && it.key.second == "clone" }) {
            discovered[tp to "clone"] = tp
        }
    }

    val methodOrder = listOf("peek", "parse", "default", "clone", "from")
    val sortedEntries =
        discovered.entries.sortedWith(
            compareBy<Map.Entry<Pair<String, String>, String>> { orderedTypeParams.indexOf(it.value).let { i -> if (i < 0) 999 else i } }
                .thenBy { methodOrder.indexOf(it.key.second).let { i -> if (i < 0) 999 else i } },
        )

    for ((keyPair, actualTp) in sortedEntries) {
        val rawMethod = keyPair.second
        val tpBounds = boundsByTp[actualTp].orEmpty()
        when (rawMethod) {
            "parse" -> {
                for (tr in listOf("Parse", "ParseQuote")) {
                    if (tr in tpBounds) {
                        suppressedBoundsOut.getOrPut(actualTp) { linkedSetOf() }.add(tr)
                        witnessedSuppressedTraitNames += tr
                    }
                }
            }
            "peek" -> {
                if ("Token" in tpBounds) {
                    suppressedBoundsOut.getOrPut(actualTp) { linkedSetOf() }.add("Token")
                    witnessedSuppressedTraitNames += "Token"
                }
            }
        }
    }

    return sortedEntries.map { (keyPair, actualTp) ->
        val rawMethod = keyPair.second
        val ktMethod = rustValueIdentToKotlin(rawMethod)
        val paramName = "${ktMethod}${actualTp}Fn"
        val fnType =
            when (rawMethod) {
                "parse" -> "(ParseStream) -> Result<$actualTp>"
                "default" -> "() -> $actualTp"
                "peek" -> "(Cursor) -> Boolean"
                "clone" -> "($actualTp) -> $actualTp"
                "from" -> "(Any?) -> $actualTp"
                else -> "(Any?) -> Any?"
            }
        val defaultVal = if (rawMethod == "clone") "{ it }" else null
        TypeParamWitnessSpec(
            keyTp = keyPair.first,
            actualTp = actualTp,
            rawMethodName = rawMethod,
            paramName = paramName,
            kotlinFnType = fnType,
            defaultExpr = defaultVal,
        )
    }
}

internal fun KotlinJ2KEmitter.describeSynTypeForDisambiguation(ty: SynType?): String {
    if (ty == null) return ""
    return when (val u = unwrapSynType(ty)) {
        is SynType.Reference -> describeSynTypeForDisambiguation(u.elem)
        is SynType.Array -> {
            val elemDesc = describeSynTypeForDisambiguation(u.elem)
            val lenDesc = renderExpr(u.len, "", null).filter { it.isLetterOrDigit() }
            "${elemDesc}Array$lenDesc"
        }
        is SynType.Slice -> "${describeSynTypeForDisambiguation(u.elem)}Slice"
        is SynType.Tuple -> u.elems.toList().joinToString("") { describeSynTypeForDisambiguation(it) }
        is SynType.Path -> {
            val lastSeg = u.path.segments.last() ?: return ""
            val base = rustTypeIdentToKotlin(lastSeg.ident.toString())
            val argsDesc =
                (lastSeg.arguments as? PathArguments.AngleBracketed)
                    ?.args
                    ?.toList()
                    ?.mapNotNull { (it as? GenericArgument.TypeArg)?.type?.let(this::describeSynTypeForDisambiguation) }
                    ?.joinToString("")
                    .orEmpty()
            "$base$argsDesc"
        }
        else -> renderType(u, null).filter { it.isLetterOrDigit() }
    }
}

internal fun KotlinJ2KEmitter.disambiguateTraitArgCollisionFnName(fnName: String, traitPath: Path): String {
    val lastSeg = traitPath.segments.last() ?: return fnName
    val typeArgs =
        (lastSeg.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            ?.mapNotNull { (it as? GenericArgument.TypeArg)?.type }
            .orEmpty()
    if (typeArgs.isNotEmpty()) {
        val suffix = typeArgs.joinToString("") { describeSynTypeForDisambiguation(it) }
        if (suffix.isNotEmpty()) return "$fnName$suffix"
    }
    val traitKt = rustTypeIdentToKotlin(lastSeg.ident.toString())
    return if (traitKt.isNotEmpty() && !traitKt.equals(fnName, ignoreCase = true)) {
        "$fnName$traitKt"
    } else {
        fnName
    }
}

internal fun KotlinJ2KEmitter.disambiguateReceiverlessTraitImplFnName(
    traitPath: Path,
    fnName: String,
    selfSynType: SynType?,
): String {
    val lastSeg = traitPath.segments.last()
    val rawTrait = lastSeg?.ident?.toString()
    val traitName = rawTrait?.let(::rustTypeIdentToKotlin).orEmpty()
    val selfSuffix = describeSynTypeForDisambiguation(selfSynType)
    val prefix =
        if (traitName.isNotEmpty() && !traitName.equals(fnName, ignoreCase = true)) {
            traitName.replaceFirstChar { it.lowercase() }
        } else {
            fnName
        }
    return if (selfSuffix.isEmpty()) prefix else "$prefix$selfSuffix"
}

internal fun KotlinJ2KEmitter.disambiguateReceiverTraitImplFnName(
    traitPath: Path,
    fnName: String,
    selfSynType: SynType?,
): String {
    val lastSeg = traitPath.segments.last()
    val traitArgSuffix =
        (lastSeg?.arguments as? PathArguments.AngleBracketed)
            ?.args
            ?.toList()
            ?.mapNotNull { (it as? GenericArgument.TypeArg)?.type?.let(this::describeSynTypeForDisambiguation) }
            ?.joinToString("")
            .orEmpty()
    val unwrappedSelf = selfSynType?.let(::unwrapSynType) as? SynType.Array
    val selfArrSuffix =
        if (unwrappedSelf != null) {
            "From${describeSynTypeForDisambiguation(unwrappedSelf)}"
        } else {
            ""
        }
    return "$fnName$traitArgSuffix$selfArrSuffix"
}

internal fun KotlinJ2KEmitter.renderFunctionSignature(
    sig: Signature,
    visPrefix: String,
    openPrefix: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    implGenerics: Generics? = null,
    scopeSignatures: MutableMap<String, Int>,
    isInterfaceMethod: Boolean,
    asTopLevelExtension: Boolean = false,
    isCompanionMember: Boolean = false,
    fnBlock: Block? = null,
): String? {
    val rawFnName = sig.ident.toString()
    val fnName = rustFunctionIdentToKotlin(rawFnName)

    val ownerTypeParams =
        ownerGenerics
            ?.params
            ?.toList()
            ?.mapNotNull { p ->
                (p as? GenericParam.TypeParam)?.ident?.toString()?.let { rustTypeIdentToKotlin(it) }
            }?.toSet()
            .orEmpty()

    val includeOwner = asTopLevelExtension || isCompanionMember || (openPrefix.isEmpty() && !isInterfaceMethod)
    val combinedParams =
        buildList {
            addAll(mergeGenericParams(ownerGenerics, sig.generics, includeOwnerParams = includeOwner))
            if (includeOwner && implGenerics != null && implGenerics !== ownerGenerics) {
                addAll(implGenerics.params.toList())
            }
        }
    val allParamsForWitness =
        buildList {
            addAll(mergeGenericParams(ownerGenerics, sig.generics, includeOwnerParams = true))
            if (implGenerics != null && implGenerics !== ownerGenerics) {
                addAll(implGenerics.params.toList())
            }
        }
    val suppressedWitnessBounds = linkedMapOf<String, MutableSet<String>>()
    val witnessSpecs =
        collectTypeParamWitnessSpecs(
            sig = sig,
            combinedParams = allParamsForWitness,
            ownerWhere = ownerGenerics?.whereClause,
            implWhere = if (implGenerics !== ownerGenerics) implGenerics?.whereClause else null,
            fnWhere = sig.generics.whereClause,
            block = fnBlock,
            selfTypeName = selfTypeName,
            suppressedBoundsOut = suppressedWitnessBounds,
        )
    if (suppressedWitnessBounds.isNotEmpty()) {
        currentWitnessedTraitBounds = suppressedWitnessBounds
    }
    if (witnessSpecs.isNotEmpty()) {
        currentTypeParamWitnessCalls =
            buildMap {
                for (ws in witnessSpecs) {
                    put(ws.keyTp to ws.rawMethodName, ws.paramName)
                    put(ws.actualTp to ws.rawMethodName, ws.paramName)
                }
            }
    }

    val excludedParams =
        (if (!includeOwner) ownerTypeParams else emptySet()) + substitutedTypeParamTypes.keys
    val liftedAssocs =
        liftDefaultlessAssocTypesFromBounds(
            combinedParams = combinedParams,
            ownerWhere = if (includeOwner) ownerGenerics?.whereClause else null,
            fnWhere = sig.generics.whereClause,
        )
    if (liftedAssocs.isNotEmpty()) {
        activeTypeParamNames = activeTypeParamNames + liftedAssocs
    }
    val baseTypeParamsRendered =
        renderTypeParametersFromParams(
            combinedParams,
            selfTypeName,
            excludeNames = excludedParams,
        ).removePrefix("<").removeSuffix(">")
    val fullTypeParamParts =
        buildList {
            if (liftedAssocs.isNotEmpty()) addAll(liftedAssocs)
            if (baseTypeParamsRendered.isNotEmpty()) add(baseTypeParamsRendered)
        }
    val typeParamsStr =
        if (fullTypeParamParts.isEmpty()) {
            ""
        } else {
            "<${fullTypeParamParts.joinToString(", ")}>"
        }

    val intoIterBoundedTypeParams = linkedSetOf<String>()
    fun hasIntoIterTraitBound(bounds: List<TypeParamBound>): Boolean =
        bounds.any { b ->
            val lastSeg = (b as? TypeParamBound.Trait)?.path?.segments?.last()
            lastSeg?.ident?.toString() == "IntoIterator"
        }
    for (gp in combinedParams) {
        if (gp is GenericParam.TypeParam) {
            val tpKt = rustTypeIdentToKotlin(gp.ident.toString())
            if (hasIntoIterTraitBound(gp.bounds.toList())) {
                intoIterBoundedTypeParams += tpKt
            }
        }
    }
    val combinedWherePreds =
        listOfNotNull(ownerGenerics?.whereClause, implGenerics?.whereClause, sig.generics.whereClause)
            .flatMap { it.predicates.toList() }
    for (pred in combinedWherePreds) {
        if (pred is WherePredicate.TypePredicate) {
            val tpKt = renderType(pred.boundedTy, selfTypeName)
            if (hasIntoIterTraitBound(pred.bounds.toList())) {
                intoIterBoundedTypeParams += tpKt
            }
        }
    }

    val params = mutableListOf<String>()
    val paramTypeSignatureKeys = mutableListOf<String>()
    val inputs = sig.inputs.toList()
    var hasReceiver = false
    var paramIdx = 0
    for (arg in inputs) {
        when (arg) {
            is FnArg.Receiver -> {
                hasReceiver = true
            }
            is FnArg.Typed -> {
                val rawParam = extractRawParamIdent(arg.patType.pat)
                val pName =
                    if (rawParam != null && rawParam != "_") {
                        val assignCount = if (isMutablePattern(arg.patType.pat)) AssignCount.Any else AssignCount.AtMostOnce
                        scopeBuilder
                            .bindLocal(
                                rawName = rawParam,
                                baseKotlinName = rustValueIdentToKotlin(rawParam),
                                span = arg.patType.pat.span(),
                                assignCount = assignCount,
                            ).kotlinName
                    } else {
                        extractParamName(arg.patType.pat, paramIdx)
                    }
                val prevHint = currentSamNameHint
                currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}${pName.replaceFirstChar { it.uppercase() }}Callback"
                val pType =
                    try {
                        renderType(arg.patType.ty, selfTypeName)
                    } finally {
                        currentSamNameHint = prevHint
                    }
                params += "$pName: $pType"
                currentTypedLocals = currentTypedLocals + (pName to pType)
                if (pType in intoIterBoundedTypeParams || pType.startsWith("Iterable<")) {
                    currentIterableLocals = currentIterableLocals + pName
                }
                if (pType.endsWith("?")) {
                    currentNullableLocals = currentNullableLocals + pName
                }
                if (pType.startsWith("MutableList<")) {
                    currentMutableListLocals = currentMutableListLocals + pName
                }
                paramTypeSignatureKeys += eraseTypeForSignatureKey(pType)
                paramIdx++
            }
        }
    }

    for (gp in combinedParams) {
        if (gp is GenericParam.ConstParam) {
            val rawConst = gp.ident.toString().removePrefix("r#")
            val cName = rustValueIdentToKotlin(rawConst)
            val cType = renderType(gp.ty, selfTypeName)
            if (params.none { it.startsWith("$cName:") }) {
                scopeBuilder.bindLocal(rawConst, cName, gp.ident.span())
                params += "$cName: $cType = ${defaultInitializerForType(cType)}"
                paramTypeSignatureKeys += eraseTypeForSignatureKey(cType)
            }
        }
    }
    val baseParamTypeSignatureKeys = paramTypeSignatureKeys.toList()
    for (ws in witnessSpecs) {
        if (params.none { it.startsWith("${ws.paramName}:") }) {
            val defaultSuffix = ws.defaultExpr?.let { " = $it" }.orEmpty()
            params += "${ws.paramName}: ${ws.kotlinFnType}$defaultSuffix"
            paramTypeSignatureKeys += eraseTypeForSignatureKey(ws.kotlinFnType)
        }
    }
    scopeBuilder.scopeData.mutScope(scopeBuilder.topScopeId()).setParamCount(params.size)

    var effectiveFnName = fnName
    val standaloneTraitPath = currentStandaloneImplTraitPath
    val standaloneSelfSynType = currentStandaloneImplSelfSynType
    val attachedTraitPath = currentAttachedImplTraitPath
    val isArraySelf = standaloneSelfSynType?.let(::unwrapSynType) is SynType.Array
    val receiverPrefix =
        if (asTopLevelExtension && !selfTypeName.isNullOrBlank()) {
            if (hasReceiver) {
                if (standaloneTraitPath != null && isArraySelf) {
                    effectiveFnName =
                        disambiguateReceiverTraitImplFnName(
                            standaloneTraitPath,
                            fnName,
                            standaloneSelfSynType,
                        )
                }
                "$selfTypeName."
            } else if (standaloneTraitPath != null) {
                effectiveFnName =
                    disambiguateReceiverlessTraitImplFnName(
                        standaloneTraitPath,
                        fnName,
                        standaloneSelfSynType,
                    )
                ""
            } else {
                val baseType = selfTypeName.substringBefore('<').trim()
                if (baseType.isNotEmpty() &&
                    baseType !in activeTypeParamNames &&
                    baseType !in KOTLIN_COLLECTION_INTERFACES &&
                    baseType.all { it.isLetterOrDigit() || it == '_' || it == '.' }
                ) {
                    "$baseType.Companion."
                } else {
                    ""
                }
            }
        } else {
            ""
        }
    val receiverKey = computeReceiverSignatureKey(receiverPrefix)
    val baseSigKey = "$receiverKey$effectiveFnName(${baseParamTypeSignatureKeys.joinToString(",")})"
    val baseSeenCount = scopeSignatures[baseSigKey] ?: 0
    if (!asTopLevelExtension && attachedTraitPath != null && !currentAttachedImplIsRefSelf) {
        scopeSignatures[baseSigKey] = baseSeenCount + 1
        if (baseSeenCount > 0) {
            val disambiguated = disambiguateTraitArgCollisionFnName(effectiveFnName, attachedTraitPath)
            if (disambiguated != effectiveFnName) {
                effectiveFnName = disambiguated
            }
        }
    }
    var sigKey = "$receiverKey$effectiveFnName(${paramTypeSignatureKeys.joinToString(",")})"
    var seenCount = if (sigKey == baseSigKey) baseSeenCount else (scopeSignatures[sigKey] ?: 0)
    if (seenCount > 0 && asTopLevelExtension && hasReceiver && standaloneTraitPath != null) {
        val disambiguated =
            disambiguateReceiverTraitImplFnName(
                standaloneTraitPath,
                effectiveFnName,
                standaloneSelfSynType,
            )
        if (disambiguated != effectiveFnName) {
            effectiveFnName = disambiguated
            sigKey = "$receiverKey$effectiveFnName(${paramTypeSignatureKeys.joinToString(",")})"
            seenCount = scopeSignatures[sigKey] ?: 0
        }
    } else if (seenCount > 0 && !asTopLevelExtension && attachedTraitPath != null && !currentAttachedImplIsRefSelf) {
        val disambiguated = disambiguateTraitArgCollisionFnName(effectiveFnName, attachedTraitPath)
        if (disambiguated != effectiveFnName) {
            effectiveFnName = disambiguated
            sigKey = "$receiverKey$effectiveFnName(${paramTypeSignatureKeys.joinToString(",")})"
            seenCount = scopeSignatures[sigKey] ?: 0
        }
    }
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return null
    }

    val retType = extractReturnSynType(sig.output)
    val renderedReturnType = retType?.let { renderType(it, selfTypeName) } ?: "Unit"
    currentEnclosingReturnRenderedType = renderedReturnType
    val retTypeStr = if (renderedReturnType == "Unit") "" else ": $renderedReturnType"

    val effectiveOwnerWhere =
        if (includeOwner) {
            val ownerWhereClause = ownerGenerics?.whereClause
            val implWhereClause = if (implGenerics !== ownerGenerics) implGenerics?.whereClause else null
            val ownerPreds = ownerWhereClause?.predicates?.toList().orEmpty()
            val implPreds = implWhereClause?.predicates?.toList().orEmpty()
            val combinedPreds = ownerPreds + implPreds
            if (combinedPreds.isEmpty()) {
                null
            } else {
                ownerWhereClause?.takeIf { implPreds.isEmpty() } ?: implWhereClause
            }
        } else {
            null
        }
    val whereClause =
        renderCombinedWhereClause(
            ownerWhere = effectiveOwnerWhere,
            fnWhere = sig.generics.whereClause,
            params = combinedParams,
            selfTypeName = selfTypeName,
            excludeNames = excludedParams,
        )
    val isKotlinAnyOverride =
        !asTopLevelExtension &&
            !isCompanionMember &&
            !isInterfaceMethod &&
            !selfTypeName.isNullOrBlank() &&
            hasReceiver &&
            (
                effectiveFnName in currentStructOverrideMethodNames ||
                    (effectiveFnName == "toString" && params.isEmpty()) ||
                    (effectiveFnName == "hashCode" && params.isEmpty()) ||
                    (effectiveFnName == "equals" && params.size == 1 && params[0].endsWith(": Any?"))
            )
    val effectiveOpenPrefix = if (isKotlinAnyOverride) "override " else openPrefix
    val typeParamsPart = if (typeParamsStr.isNotEmpty()) "$typeParamsStr " else ""
    return "${visPrefix}${effectiveOpenPrefix}fun ${typeParamsPart}${receiverPrefix}$effectiveFnName(${params.joinToString(", ")})${retTypeStr}$whereClause"
}

internal fun KotlinJ2KEmitter.computeReassignedParamShadows(
    sig: Signature,
    block: Block,
    innerIndent: String,
): List<String> {
    val shadows = mutableListOf<String>()
    for (arg in sig.inputs) {
        if (arg is FnArg.Typed) {
            val rawParam = extractRawParamIdent(arg.patType.pat)
            if (rawParam != null && rawParam != "_" && blockReassignsIdent(block, rawParam)) {
                val ktName = scopeBuilder.resolveLocalKotlinName(rawParam) ?: rustValueIdentToKotlin(rawParam)
                shadows += "${innerIndent}var $ktName = $ktName"
            }
        }
    }
    return shadows
}

internal fun KotlinJ2KEmitter.blockReassignsIdent(block: Block, targetRawIdent: String): Boolean {
    var reassigned = false
    val visitor =
        object : Visit() {
            override fun visitItemFn(i: Item.Fn) {
                // Local `fn` items cannot capture or reassign outer parameters in Rust.
            }

            override fun visitExprAssign(e: Expr.Assign) {
                if (extractAssignedRootIdent(e.left) == targetRawIdent) {
                    reassigned = true
                    return
                }
                super.visitExprAssign(e)
            }

            override fun visitExprBinary(e: Expr.Binary) {
                if (isCompoundAssignOp(e.op) && extractAssignedRootIdent(e.left) == targetRawIdent) {
                    reassigned = true
                    return
                }
                super.visitExprBinary(e)
            }
        }
    visitor.visitBlock(block)
    return reassigned
}

internal fun KotlinJ2KEmitter.extractAssignedRootIdent(lhs: Expr): String? =
    when (lhs) {
        is Expr.Path -> {
            if (lhs.qself == null && lhs.path.segments.len() == 1) {
                lhs.path.segments
                    .first()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            } else {
                null
            }
        }
        is Expr.Paren -> extractAssignedRootIdent(lhs.expr)
        is Expr.Group -> extractAssignedRootIdent(lhs.expr)
        is Expr.Unary -> if (lhs.op is UnOp.Deref) extractAssignedRootIdent(lhs.expr) else null
        else -> null
    }

internal fun KotlinJ2KEmitter.isCompoundAssignOp(op: BinOp): Boolean =
    when (op) {
        is BinOp.AddAssign,
        is BinOp.SubAssign,
        is BinOp.MulAssign,
        is BinOp.DivAssign,
        is BinOp.RemAssign,
        is BinOp.BitXorAssign,
        is BinOp.BitAndAssign,
        is BinOp.BitOrAssign,
        is BinOp.ShlAssign,
        is BinOp.ShrAssign,
        -> true
        else -> false
    }

internal fun KotlinJ2KEmitter.computeReceiverSignatureKey(receiverPrefix: String): String =
    when {
        receiverPrefix.isEmpty() -> ""
        receiverPrefix.endsWith(".Companion.") ->
            receiverPrefix.removeSuffix(".Companion.").substringBefore('<').trim() + ".Companion."
        else ->
            receiverPrefix.removeSuffix(".").substringBefore('<').trim() + "."
    }

internal fun KotlinJ2KEmitter.eraseTypeForSignatureKey(renderedType: String): String {
    val nonNull = renderedType.removeSuffix("?")
    val base = nonNull.substringBefore('<').trim()
    val keepNullability = base in KOTLIN_JVM_VALUE_TYPES
    return if (keepNullability && renderedType.endsWith("?")) "$base?" else base
}

internal fun KotlinJ2KEmitter.extractRawParamIdent(pat: Pat): String? =
    when (pat) {
        is Pat.Ident -> pat.ident.toString().removePrefix("r#")
        is Pat.Reference -> extractRawParamIdent(pat.pat)
        is Pat.TypeAscription -> extractRawParamIdent(pat.pat)
        is Pat.PatParen -> extractRawParamIdent(pat.pat)
        else -> null
    }

internal fun KotlinJ2KEmitter.mergeGenericParams(
    owner: Generics?,
    fnGenerics: Generics,
    includeOwnerParams: Boolean,
): List<GenericParam> {
    val fnList = fnGenerics.params.toList()
    if (!includeOwnerParams || owner == null) return fnList
    val ownerList = owner.params.toList()
    if (ownerList.isEmpty()) return fnList
    return ownerList + fnList
}

internal fun KotlinJ2KEmitter.disambiguateName(baseName: String, scopeSignatures: MutableMap<String, Int>): String {
    val count = scopeSignatures[baseName] ?: 0
    scopeSignatures[baseName] = count + 1
    return if (count == 0) baseName else "${baseName}$count"
}

internal fun KotlinJ2KEmitter.extractReturnSynType(output: ReturnType): SynType? =
    when (output) {
        is ReturnType.Default -> null
        is ReturnType.TypeReturn -> output.ty
    }

internal fun KotlinJ2KEmitter.extractParamName(pat: Pat, index: Int): String =
    when (pat) {
        is Pat.Ident -> rustValueIdentToKotlin(pat.ident.toString())
        is Pat.Reference -> extractParamName(pat.pat, index)
        is Pat.TypeAscription -> extractParamName(pat.pat, index)
        is Pat.Wild -> "arg$index"
        is Pat.Tuple -> "tupleArg$index"
        is Pat.TupleStruct -> "destructuredArg$index"
        is Pat.Struct -> "structArg$index"
        else -> "arg$index"
    }

internal fun KotlinJ2KEmitter.deduplicateComplementaryCfgStmts(rawStmts: List<Stmt>): List<Stmt> {
    if (rawStmts.size < 2) return rawStmts
    var lastRawExecIdx = -1
    for (i in rawStmts.indices.reversed()) {
        if (rawStmts[i] !is Stmt.ItemStmt) {
            lastRawExecIdx = i
            break
        }
    }
    val filtered = ArrayList<Stmt>(rawStmts.size)
    var i = 0
    while (i < rawStmts.size) {
        val cur = rawStmts[i]
        filtered += cur
        if (i + 1 < rawStmts.size && cur !is Stmt.ItemStmt && rawStmts[i + 1] !is Stmt.ItemStmt) {
            val next = rawStmts[i + 1]
            val sig1 = extractLeadingCfgSignature(cur)
            val sig2 = if (sig1 != null) extractLeadingCfgSignature(next) else null
            if (sig1 != null && sig2 != null && sig1.second == sig2.second && sig1.first != sig2.first) {
                val sameLocalBinding =
                    cur is Stmt.Local &&
                        next is Stmt.Local &&
                        (cur.pat as? Pat.Ident)?.ident?.toString() != null &&
                        (cur.pat as? Pat.Ident)?.ident?.toString() == (next.pat as? Pat.Ident)?.ident?.toString()
                val complementaryTail = (i + 1 == lastRawExecIdx) && cur !is Stmt.Local && next !is Stmt.Local
                if (sameLocalBinding || complementaryTail) {
                    i += 2
                    continue
                }
            }
        }
        i++
    }
    return filtered
}

internal fun KotlinJ2KEmitter.extractLeadingCfgSignature(stmt: Stmt): Pair<Boolean, String>? {
    val trees = stmt.toTokenStream().toList()
    var found: Pair<Boolean, String>? = null
    var idx = 0
    while (idx + 1 < trees.size) {
        val pound = (trees[idx] as? TokenTree.Punct)?.value?.asChar()
        val bracket = (trees[idx + 1] as? TokenTree.Group)?.value
        if (pound != '#' || bracket == null || bracket.delimiter() != Delimiter.Bracket) break
        val attrTrees = bracket.stream().toList()
        if (attrTrees.size == 2 &&
            (attrTrees[0] as? TokenTree.Ident)?.value?.toString() == "cfg" &&
            (attrTrees[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
        ) {
            if (found != null) return null
            val predTrees = (attrTrees[1] as TokenTree.Group).value.stream().toList()
            found =
                if (predTrees.size == 2 &&
                    (predTrees[0] as? TokenTree.Ident)?.value?.toString() == "not" &&
                    (predTrees[1] as? TokenTree.Group)?.value?.delimiter() == Delimiter.Parenthesis
                ) {
                    val innerPred = (predTrees[1] as TokenTree.Group).value.stream().toList()
                    false to canonicalTokenSequenceKey(innerPred)
                } else {
                    true to canonicalTokenSequenceKey(predTrees)
                }
        }
        idx += 2
    }
    return found
}

internal fun KotlinJ2KEmitter.canonicalTokenSequenceKey(trees: List<TokenTree>): String =
    trees.joinToString(" ") { tt ->
        when (tt) {
            is TokenTree.Ident -> "I:${tt.value}"
            is TokenTree.Punct -> "P:${tt.value.asChar()}:${tt.value.spacing()}"
            is TokenTree.Literal -> "L:${tt.value}"
            is TokenTree.Group ->
                "G:${tt.value.delimiter()}(${canonicalTokenSequenceKey(tt.value.stream().toList())})"
        }
    }

internal fun KotlinJ2KEmitter.renderBlock(
    block: Block,
    indent: String,
    selfTypeName: String?,
    returnType: SynType? = null,
    leadingInjectedLines: List<String> = emptyList(),
    trailingInjectedLines: List<String> = emptyList(),
): String {
    val stmts = deduplicateComplementaryCfgStmts(block.stmts)
    val innerIndent = "$indent    "
    val closeBraceTrivia = renderLeadingTriviaForSpan(block.braceToken.span.close(), innerIndent)
    if (stmts.isEmpty() && leadingInjectedLines.isEmpty() && trailingInjectedLines.isEmpty() && closeBraceTrivia.isBlank()) {
        return "{}"
    }
    val lines = mutableListOf<String>()
    lines.addAll(leadingInjectedLines)

    val returnsValue = returnType != null && renderType(returnType, selfTypeName) != "Unit"

    var lastExecutableIdx = -1
    for (i in stmts.indices.reversed()) {
        if (stmts[i] !is Stmt.ItemStmt) {
            lastExecutableIdx = i
            break
        }
    }

    val localBlockImpls = linkedMapOf<String, MutableList<Item.Impl>>()
    val localAttachableNames =
        stmts
            .mapNotNull { stmt ->
                when (val it = (stmt as? Stmt.ItemStmt)?.item) {
                    is Item.Struct -> rustTypeIdentToKotlin(it.ident.toString())
                    is Item.Enum -> rustTypeIdentToKotlin(it.ident.toString())
                    is Item.Union -> rustTypeIdentToKotlin(it.ident.toString())
                    else -> null
                }
            }.toSet()
    for (stmt in stmts) {
        val implItem = (stmt as? Stmt.ItemStmt)?.item as? Item.Impl ?: continue
        val ownerType = extractDirectOwnerTypeName(implItem.selfType, implItem.generics)
        if (ownerType != null && ownerType in localAttachableNames) {
            localBlockImpls.getOrPut(ownerType) { mutableListOf() }.add(implItem)
        }
    }

    val localBlockSignatures = linkedMapOf<String, Int>()
    val prevFnParamNumericKinds = currentFunctionParamNumericKinds
    val blockEntryExpectedKind = currentExpectedNumericKind
    currentExpectedNumericKind = null
    scopeBuilder.enterCompr()
    try {
        val blockFnParamKinds = linkedMapOf<String, List<NumericKind?>?>()
        for (stmt in stmts) {
            val fnItem = (stmt as? Stmt.ItemStmt)?.item as? Item.Fn ?: continue
            if (itemHasDocOnlyCfg(fnItem)) continue
            val extracted = extractFnParamNumericKinds(fnItem, selfTypeName)
            if (extracted != null) {
                val (fnName, kinds) = extracted
                blockFnParamKinds[fnName] = if (fnName in blockFnParamKinds) null else kinds
            }
        }
        if (blockFnParamKinds.isNotEmpty()) {
            val updated = currentFunctionParamNumericKinds.toMutableMap()
            for ((fnName, kinds) in blockFnParamKinds) {
                if (kinds == null) {
                    updated.remove(fnName)
                } else {
                    updated[fnName] = kinds
                }
            }
            currentFunctionParamNumericKinds = updated
        }
        // Hoist local item declarations (`fn`, `struct`, `enum`, `type`, `const`, `use`)
        // before executable statements so forward calls/type references resolve in Kotlin.
        for (stmt in stmts) {
            val itemInStmt = (stmt as? Stmt.ItemStmt)?.item ?: continue
            if (itemHasDocOnlyCfg(itemInStmt)) continue
            if (itemInStmt is Item.Use) {
                registerUseItemInScope(itemInStmt)
                continue
            }
            if (itemInStmt is Item.Impl) {
                val ownerType = extractDirectOwnerTypeName(itemInStmt.selfType, itemInStmt.generics)
                if (ownerType != null && ownerType in localAttachableNames) {
                    continue
                }
            }
            val renderedItem =
                renderStmt(
                    stmt = stmt,
                    indent = innerIndent,
                    selfTypeName = selfTypeName,
                    isTailReturn = false,
                    localTypeImpls = localBlockImpls,
                    localSignatures = localBlockSignatures,
                )
            if (renderedItem.isNotBlank()) {
                lines += renderedItem
            }
        }
        var idx = 0
        while (idx < stmts.size) {
            val stmt = stmts[idx]
            if (stmt is Stmt.ItemStmt) {
                idx++
                continue
            }
            if (stmt is Stmt.Local &&
                stmt.init == null &&
                stmt.pat is Pat.Ident &&
                (stmt.pat as Pat.Ident).subpat == null &&
                idx + 1 < stmts.size
            ) {
                val identPat = stmt.pat as Pat.Ident
                val rawVar = identPat.ident.toString().removePrefix("r#")
                val nextStmt = stmts[idx + 1]
                if (nextStmt is Stmt.MacroStmt) {
                    val delim = extractDelimitedMacroParts(nextStmt.mac, innerIndent, selfTypeName)
                    if (delim != null && delim.second == rawVar) {
                        val trivia1 = renderLeadingTriviaForSpan(stmt.span(), innerIndent)
                        val trivia2 = renderLeadingTriviaForSpan(nextStmt.span(), innerIndent)
                        val assignCount = if (identPat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                        val ktVar =
                            scopeBuilder
                                .bindLocal(rawVar, rustValueIdentToKotlin(rawVar), identPat.ident.span(), assignCount = assignCount)
                                .kotlinName
                        lines += "${trivia1}${trivia2}${innerIndent}val $ktVar = ${delim.first}(${delim.third}).getOrElse { return Result.failure(it) }.content"
                        idx += 2
                        continue
                    }
                } else if (nextStmt is Stmt.Local && nextStmt.init?.diverge == null && nextStmt.init?.expr is Expr.Macro) {
                    val delim = extractDelimitedMacroParts((nextStmt.init!!.expr as Expr.Macro).mac, innerIndent, selfTypeName)
                    if (delim != null && delim.second == rawVar) {
                        val trivia1 = renderLeadingTriviaForSpan(stmt.span(), innerIndent)
                        val trivia2 = renderLeadingTriviaForSpan(nextStmt.span(), innerIndent)
                        val assignCount = if (identPat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                        val ktVar =
                            scopeBuilder
                                .bindLocal(rawVar, rustValueIdentToKotlin(rawVar), identPat.ident.span(), assignCount = assignCount)
                                .kotlinName
                        val (tokPat, tokTy) = renderLocalPattern(nextStmt.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
                        val tokKeyword = if (isMutablePattern(nextStmt.pat)) "var" else "val"
                        val tokSuffix = if (tokTy != null) ": $tokTy" else ""
                        lines += "${trivia1}${trivia2}${innerIndent}val ${ktVar}Group = ${delim.first}(${delim.third}).getOrElse { return Result.failure(it) }"
                        lines += "${innerIndent}val $ktVar = ${ktVar}Group.content"
                        lines += "${innerIndent}$tokKeyword $tokPat$tokSuffix = ${ktVar}Group.token"
                        idx += 2
                        continue
                    }
                } else if (nextStmt is Stmt.ExprStmt &&
                    nextStmt.expr is Expr.Match &&
                    !(idx + 1 == lastExecutableIdx && returnsValue)
                ) {
                    val foldedLines =
                        tryRenderDeferredLocalResultMatchInit(
                            identPat = identPat,
                            rawVar = rawVar,
                            localStmt = stmt,
                            matchStmt = nextStmt,
                            matchExpr = nextStmt.expr as Expr.Match,
                            targetLocalStmt = null,
                            innerIndent = innerIndent,
                            selfTypeName = selfTypeName,
                        )
                    if (foldedLines != null) {
                        lines.addAll(foldedLines)
                        idx += 2
                        continue
                    }
                } else if (nextStmt is Stmt.Local &&
                    nextStmt.init?.diverge == null &&
                    nextStmt.init?.expr is Expr.Match
                ) {
                    val foldedLines =
                        tryRenderDeferredLocalResultMatchInit(
                            identPat = identPat,
                            rawVar = rawVar,
                            localStmt = stmt,
                            matchStmt = nextStmt,
                            matchExpr = nextStmt.init!!.expr as Expr.Match,
                            targetLocalStmt = nextStmt,
                            innerIndent = innerIndent,
                            selfTypeName = selfTypeName,
                        )
                    if (foldedLines != null) {
                        lines.addAll(foldedLines)
                        idx += 2
                        continue
                    }
                }
            }
            val isLastExec = (idx == lastExecutableIdx)
            val isTail = isLastExec && returnsValue
            val isValueTailExpr = isLastExec && !returnsValue && stmt is Stmt.ExprStmt && stmt.semiToken == null
            val rendered =
                try {
                    if (isValueTailExpr) {
                        currentExpectedNumericKind = blockEntryExpectedKind
                    } else {
                        currentExpectedNumericKind = null
                    }
                    renderStmt(
                        stmt = stmt,
                        indent = innerIndent,
                        selfTypeName = selfTypeName,
                        isTailReturn = isTail,
                        isLastExecutableStmt = isLastExec,
                        localTypeImpls = localBlockImpls,
                        localSignatures = localBlockSignatures,
                    )
                } finally {
                    currentExpectedNumericKind = null
                }
            if (rendered.isNotBlank()) {
                lines += rendered
            }
            idx++
        }
        if (closeBraceTrivia.isNotBlank()) {
            lines += closeBraceTrivia.trimEnd('\n')
        }
        lines.addAll(trailingInjectedLines)
    } finally {
        currentExpectedNumericKind = blockEntryExpectedKind
        currentFunctionParamNumericKinds = prevFnParamNumericKinds
        scopeBuilder.exitCompr()
    }

    if (lines.isEmpty()) return "{}"
    val sb = StringBuilder()
    sb.append("{\n")
    for (line in lines) {
        sb.append(line)
        if (!line.endsWith('\n')) sb.append('\n')
    }
    sb.append(indent).append("}")
    return sb.toString()
}

internal fun KotlinJ2KEmitter.extractDelimitedMacroParts(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
): Triple<String, String, String>? {
    val lastSeg = mac.path.segments.last() ?: return null
    val macroName = lastSeg.ident.toString()
    if (macroName != "parenthesized" && macroName != "braced" && macroName != "bracketed") return null
    val list = mac.tokens.clone().toList()
    if (list.size < 3) return null
    val targetIdent = (list[0] as? TokenTree.Ident)?.value?.toString()?.removePrefix("r#") ?: return null
    val inKeyword = (list[1] as? TokenTree.Ident)?.value?.toString() ?: return null
    if (inKeyword != "in") return null
    val streamTokens = TokenStream.fromTokenTrees(list.subList(2, list.size))
    val renderedStream = parseAndRenderTokenStreamAsExpr(streamTokens, indent, selfTypeName)
    return Triple(macroName, targetIdent, renderedStream)
}

internal fun KotlinJ2KEmitter.tryRenderDeferredLocalResultMatchInit(
    identPat: Pat.Ident,
    rawVar: String,
    localStmt: Stmt.Local,
    matchStmt: Stmt,
    matchExpr: Expr.Match,
    targetLocalStmt: Stmt.Local?,
    innerIndent: String,
    selfTypeName: String?,
): List<String>? {
    if ("Ok" in inspector.declaredTypeNames || "Err" in inspector.declaredTypeNames) return null
    val arms = matchExpr.arms
    if (arms.size != 2 || arms.any { armGuardExpr(it) != null }) return null
    val firstPat = unwrapTransparentPat(armPattern(arms[0]))
    val secondPat = unwrapTransparentPat(armPattern(arms[1]))
    val (okArm, errArm) =
        when {
            isStdlibResultArmPat(firstPat, "Ok") && isStdlibResultArmPat(secondPat, "Err") -> arms[0] to arms[1]
            isStdlibResultArmPat(secondPat, "Ok") && isStdlibResultArmPat(firstPat, "Err") -> arms[1] to arms[0]
            else -> return null
        }
    val okBlock = (okArm.body as? Expr.BlockExpr)?.block ?: return null
    val errBodyUnwrapped =
        when (val eb = errArm.body) {
            is Expr.BlockExpr -> {
                val onlyStmt = eb.block.stmts.singleOrNull()
                (onlyStmt as? Stmt.ExprStmt)?.expr
            }
            else -> eb
        } ?: return null
    if (errBodyUnwrapped !is Expr.Return) return null
    val assignsTarget =
        okBlock.stmts.any { s ->
            s is Stmt.ExprStmt &&
                s.expr is Expr.Assign &&
                extractAssignedRootIdent((s.expr as Expr.Assign).left) == rawVar
        }
    if (!assignsTarget) return null

    val trivia1 = renderLeadingTriviaForSpan(localStmt.span(), innerIndent)
    val trivia2 = renderLeadingTriviaForSpan(matchStmt.span(), innerIndent)
    val subjectRendered = renderExpr(matchExpr.expr, innerIndent, selfTypeName)
    val okSubPat = (unwrapTransparentPat(armPattern(okArm)) as Pat.TupleStruct).elems.toList().firstOrNull()
    val errSubPat = (unwrapTransparentPat(armPattern(errArm)) as? Pat.TupleStruct)?.elems?.toList()?.firstOrNull()

    val out = mutableListOf<String>()
    val okVar = scopeBuilder.bindLocal("_ok", "matchVal", matchExpr.expr.span()).kotlinName
    val errRender =
        run {
            scopeBuilder.enterCompr()
            try {
                val errParam =
                    if (errSubPat is Pat.Ident && errSubPat.subpat == null && errSubPat.ident.toString() != "_") {
                        val rawErr = errSubPat.ident.toString().removePrefix("r#")
                        scopeBuilder.bindLocal(rawErr, rustValueIdentToKotlin(rawErr), errSubPat.ident.span()).kotlinName
                    } else {
                        "err"
                    }
                val renderedReturn = renderExpr(errBodyUnwrapped, "$innerIndent    ", selfTypeName)
                "{ $errParam -> $renderedReturn }"
            } finally {
                scopeBuilder.exitCompr()
            }
        }
    out += "${trivia1}${trivia2}${innerIndent}val $okVar = $subjectRendered.getOrElse $errRender"
    if (okSubPat != null) {
        out += extractMatchArmBindings(okSubPat, okVar, innerIndent, selfTypeName, bindToScope = true)
    }
    val kw = if (identPat.mutability != null) "var" else "val"
    val assignCount = if (identPat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
    var boundVar = false
    for ((sIdx, s) in okBlock.stmts.withIndex()) {
        if (s is Stmt.ExprStmt && s.expr is Expr.Assign) {
            val assignExpr = s.expr as Expr.Assign
            if (!boundVar && extractAssignedRootIdent(assignExpr.left) == rawVar) {
                val ktVar = scopeBuilder.bindLocal(rawVar, rustValueIdentToKotlin(rawVar), identPat.ident.span(), assignCount = assignCount).kotlinName
                val rhsText = renderExpr(assignExpr.right, innerIndent, selfTypeName)
                out += "$innerIndent$kw $ktVar = $rhsText"
                boundVar = true
                continue
            }
        }
        if (sIdx == okBlock.stmts.lastIndex && s is Stmt.ExprStmt && s.semiToken == null) {
            if (targetLocalStmt != null) {
                val (tokPat, tokTy) = renderLocalPattern(targetLocalStmt.pat, selfTypeName, includeCtorInTupleStruct = false, bindToScope = true)
                val tokKeyword = if (isMutablePattern(targetLocalStmt.pat)) "var" else "val"
                val tokSuffix = if (tokTy != null) ": $tokTy" else ""
                val tailVal = renderExpr(s.expr, innerIndent, selfTypeName)
                if (tokPat != "_" && tokPat.isNotEmpty()) {
                    out += "$innerIndent$tokKeyword $tokPat$tokSuffix = $tailVal"
                }
                continue
            }
            if (isSideEffectFreeExpr(s.expr)) {
                continue
            }
        }
        val renderedS = renderStmt(s, innerIndent, selfTypeName, isTailReturn = false)
        if (renderedS.isNotBlank()) {
            out += renderedS
        }
    }
    return if (boundVar) out else null
}

internal fun KotlinJ2KEmitter.isStdlibResultArmPat(pat: Pat, variant: String): Boolean {
    if (pat !is Pat.TupleStruct) return false
    val segs = pat.path.segments.toList()
    val matchesPath =
        (segs.size == 1 && segs[0].ident.toString() == variant) ||
            (
                segs.size >= 2 &&
                    segs.last().ident.toString() == variant &&
                    segs.dropLast(1).all {
                        val prefix = it.ident.toString()
                        prefix in IGNORED_PATH_PREFIXES ||
                            prefix in STDLIB_PRELUDE_REEXPORT_MODULES ||
                            prefix == "Result" ||
                            prefix == "result"
                    }
            )
    if (!matchesPath) return false
    val elems = pat.elems.toList()
    return elems.size == 1 && !isDiscriminatingSubpattern(elems[0])
}

internal fun KotlinJ2KEmitter.renderStmt(
    stmt: Stmt,
    indent: String,
    selfTypeName: String?,
    isTailReturn: Boolean,
    isLastExecutableStmt: Boolean = false,
    localTypeImpls: Map<String, List<Item.Impl>> = emptyMap(),
    localSignatures: MutableMap<String, Int> = linkedMapOf(),
): String {
    val triviaPrefix = renderLeadingTriviaForSpan(stmt.span(), indent)
    val body =
        when (stmt) {
            is Stmt.Local -> renderStmtLocal(stmt, indent, selfTypeName)
            is Stmt.ItemStmt ->
                renderItem(
                    item = stmt.item,
                    indent = indent,
                    localTypeImpls = localTypeImpls,
                    isLocalItem = true,
                    scopeSignatures = localSignatures,
                )
            is Stmt.ExprStmt -> {
                val expr = stmt.expr
                val hasSemi = stmt.semiToken != null
                if (isTailReturn && !hasSemi && !isExplicitControlFlowJump(expr)) {
                    renderTailReturnExpr(expr, indent, selfTypeName)
                } else {
                    val rendered = renderExpr(expr, indent, selfTypeName)
                    if (rendered.isBlank()) {
                        ""
                    } else if (isLastExecutableStmt && !hasSemi && rendered.startsWith("error(")) {
                        "${indent}return $rendered"
                    } else {
                        "$indent$rendered"
                    }
                }
            }
            is Stmt.MacroStmt -> {
                val rendered = renderMacroInvocationAsStmtOrExpr(stmt.mac, indent, selfTypeName)
                if (rendered.isBlank()) {
                    ""
                } else if ((isTailReturn || (isLastExecutableStmt && rendered.startsWith("error("))) && stmt.semiToken == null) {
                    "${indent}return $rendered"
                } else {
                    "$indent$rendered"
                }
            }
        }
    return if (triviaPrefix.isEmpty()) body else "$triviaPrefix$body"
}

internal fun KotlinJ2KEmitter.isExplicitControlFlowJump(expr: Expr): Boolean =
    when (expr) {
        is Expr.Return, is Expr.Break, is Expr.Continue,
        is Expr.Loop, is Expr.While, is Expr.ForLoop, is Expr.Assign,
        -> true
        else -> false
    }

internal fun KotlinJ2KEmitter.renderTailReturnExpr(expr: Expr, indent: String, selfTypeName: String?): String {
    val retNumKind = currentEnclosingReturnRenderedType?.let(this::resolveNumericKind)
    val rendered = renderTypedNumericInitializer(expr, retNumKind, indent, selfTypeName)
    return if (rendered.isBlank()) "" else "${indent}return $rendered"
}

internal fun KotlinJ2KEmitter.recordLocalInferredType(
    patText: String,
    typeAnnotation: String?,
    initExpr: Expr,
    rhs: String,
    selfTypeName: String?,
) {
    if (patText.startsWith("(")) return
    if (typeAnnotation != null) {
        currentTypedLocals = currentTypedLocals + (patText to typeAnnotation)
        return
    }
    val inferredRenderedTy = inferExprRenderedType(initExpr, selfTypeName)
    if (inferredRenderedTy != null) {
        currentTypedLocals = currentTypedLocals + (patText to inferredRenderedTy)
        return
    }
    val inferredKind = inferExprNumericKind(initExpr, selfTypeName)
    if (inferredKind != null) {
        currentTypedLocals = currentTypedLocals + (patText to numericKindToPureTypeName(inferredKind))
    } else if (rhs.endsWith(".encodeToByteArray()")) {
        currentTypedLocals = currentTypedLocals + (patText to "ByteArray")
    }
}

internal fun KotlinJ2KEmitter.renderStmtLocal(local: Stmt.Local, indent: String, selfTypeName: String?): String {
    val pat = local.pat
    val keyword = if (isMutablePattern(pat)) "var" else "val"
    val init = local.init
    if (init == null) {
        val (patText, typeAnnotation) =
            renderLocalPattern(
                pat = pat,
                selfTypeName = selfTypeName,
                includeCtorInTupleStruct = false,
                bindToScope = true,
            )
        if (typeAnnotation != null && !patText.startsWith("(")) {
            currentTypedLocals = currentTypedLocals + (patText to typeAnnotation)
            if (typeAnnotation.endsWith("?")) {
                currentNullableLocals = currentNullableLocals + patText
            }
        }
        val typeSuffix = if (typeAnnotation != null) ": $typeAnnotation" else ": Any?"
        return "$indent$keyword $patText$typeSuffix"
    }
    val diverge = init.diverge
    val initExpr = init.expr
    val explicitLocalAnnotation = extractLocalTypeAnnotation(pat, selfTypeName)
    val localNumKind = explicitLocalAnnotation?.removeSuffix("?")?.let(this::resolveNumericKind)
    val prevParseTarget = currentExpectedParseTargetType
    currentExpectedParseTargetType = explicitLocalAnnotation
    if (diverge == null && initExpr is Expr.BlockExpr) {
        val blockStmts = initExpr.block.stmts
        if (blockStmts.size == 1 && blockStmts[0] is Stmt.ExprStmt) {
            val singleRhs =
                try {
                    renderTypedNumericInitializer(
                        (blockStmts[0] as Stmt.ExprStmt).expr,
                        localNumKind,
                        indent,
                        selfTypeName,
                    )
                } finally {
                    currentExpectedParseTargetType = prevParseTarget
                }
            val (patText, typeAnnotation) =
                renderLocalPattern(
                    pat = pat,
                    selfTypeName = selfTypeName,
                    includeCtorInTupleStruct = false,
                    bindToScope = true,
                )
            recordLocalInferredType(patText, typeAnnotation, (blockStmts[0] as Stmt.ExprStmt).expr, singleRhs, selfTypeName)
            val typeSuffix = if (typeAnnotation != null) ": $typeAnnotation" else ""
            return "$indent$keyword $patText$typeSuffix = $singleRhs"
        }
        val blockBody =
            try {
                renderBlock(initExpr.block, indent, selfTypeName)
            } finally {
                currentExpectedParseTargetType = prevParseTarget
            }
        val (patText, typeAnnotation) =
            renderLocalPattern(
                pat = pat,
                selfTypeName = selfTypeName,
                includeCtorInTupleStruct = false,
                bindToScope = true,
            )
        recordLocalInferredType(patText, typeAnnotation, initExpr, blockBody, selfTypeName)
        val typeSuffix = if (typeAnnotation != null) ": $typeAnnotation" else ""
        return "$indent$keyword $patText$typeSuffix = run $blockBody"
    }
    var rhs =
        try {
            renderTypedNumericInitializer(initExpr, localNumKind, indent, selfTypeName)
        } finally {
            currentExpectedParseTargetType = prevParseTarget
        }
    if (pat is Pat.Wild) {
        if (isSideEffectFreeExpr(initExpr)) {
            return ""
        }
        return "$indent$rhs"
    }
    val unwrappedPat = unwrapTransparentPat(pat)
    if (unwrappedPat is Pat.Struct && diverge == null) {
        return renderStructDestructuringLocal(unwrappedPat, rhs, local.span(), indent, selfTypeName)
    }
    val divergeBody =
        if (diverge != null) {
            val divergeExpr = diverge.expr
            if (divergeExpr is Expr.BlockExpr) {
                val stmts = divergeExpr.block.stmts
                if (stmts.size == 1) {
                    renderStmt(stmts[0], "", selfTypeName, isTailReturn = false).trim()
                } else {
                    "run " + renderBlock(divergeExpr.block, indent, selfTypeName)
                }
            } else {
                renderExpr(divergeExpr, indent, selfTypeName)
            }
        } else {
            null
        }
    val (patText, rawTypeAnnotation) =
        renderLocalPattern(
            pat = pat,
            selfTypeName = selfTypeName,
            includeCtorInTupleStruct = false,
            bindToScope = true,
        )
    var typeAnnotation = rawTypeAnnotation
    if (rhs == "mutableListOf()" && typeAnnotation == null) {
        val inferredElem = inferEmptyListElementTypeFromReturn(currentEnclosingReturnRenderedType)
        if (inferredElem != null) {
            rhs = "mutableListOf<$inferredElem>()"
        }
    } else if (rhs.startsWith("ArrayList(") && typeAnnotation == null) {
        val inferredElem = inferEmptyListElementTypeFromReturn(currentEnclosingReturnRenderedType)
        if (inferredElem != null) {
            rhs = "ArrayList<$inferredElem>(${rhs.removePrefix("ArrayList(")}"
        }
    } else if (typeAnnotation == null && !patText.startsWith("(")) {
        val unwrappedRet =
            currentEnclosingReturnRenderedType?.let { ret ->
                if (ret.startsWith("Result<") && ret.endsWith(">")) {
                    ret.removePrefix("Result<").removeSuffix(">").trim()
                } else {
                    ret
                }
            }
        if (unwrappedRet != null && '<' in unwrappedRet) {
            val baseRet = unwrappedRet.substringBefore('<').trim()
            if (rhs == "$baseRet.new()" || rhs == "$baseRet.default()") {
                typeAnnotation = unwrappedRet
            }
        }
    }
    if (!patText.startsWith("(")) {
        recordLocalInferredType(patText, typeAnnotation, initExpr, rhs, selfTypeName)
        if (rhs.startsWith("ArrayDeque(")) {
            currentDequeLocals = currentDequeLocals + patText
            currentMutableListLocals = currentMutableListLocals - patText
        } else if (rhs.startsWith("mutableListOf") ||
            rhs.startsWith("ArrayList(") ||
            rhs.startsWith("ArrayList<") ||
            rhs.endsWith(".toMutableList()") ||
            typeAnnotation?.startsWith("MutableList<") == true
        ) {
            currentMutableListLocals = currentMutableListLocals + patText
            currentDequeLocals = currentDequeLocals - patText
        }
        if (typeAnnotation?.endsWith("?") == true || rhs == "null") {
            currentNullableLocals = currentNullableLocals + patText
        }
    }
    val typeSuffix = if (typeAnnotation != null) ": $typeAnnotation" else ""
    return if (divergeBody != null) {
        "$indent$keyword $patText$typeSuffix = $rhs ?: $divergeBody"
    } else {
        "$indent$keyword $patText$typeSuffix = $rhs"
    }
}

internal fun KotlinJ2KEmitter.renderStructDestructuringLocal(
    structPat: Pat.Struct,
    rhs: String,
    span: Span,
    indent: String,
    selfTypeName: String?,
): String {
    val structTypeName =
        structPat.path.segments
            .last()
            ?.ident
            ?.toString()
            ?.let { if (it == "Self") selfTypeName?.substringBefore('<')?.trim() else rustTypeIdentToKotlin(it) }
    val isSimpleRhs =
        rhs.all { it.isLetterOrDigit() || it == '_' } ||
            (rhs.startsWith("this.") && rhs.removePrefix("this.").all { it.isLetterOrDigit() || it == '_' })
    val lines = mutableListOf<String>()
    val receiverVar =
        if (isSimpleRhs) {
            rhs
        } else {
            val tmp = scopeBuilder.bindLocal("_struct", "structVal", span).kotlinName
            lines += "${indent}val $tmp = $rhs"
            tmp
        }
    for (fp in structPat.fields.toList()) {
        val fieldProp = renderMemberName(fp.member)
        val subPat = unwrapTransparentPat(fp.pat)
        if (subPat is Pat.Ident && subPat.subpat == null) {
            val raw = subPat.ident.toString().removePrefix("r#")
            if (raw != "_") {
                val kw = if (subPat.mutability != null) "var" else "val"
                val assignCount = if (subPat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                val ktVar = scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), subPat.ident.span(), assignCount = assignCount).kotlinName
                if (structTypeName != null) {
                    val fieldTy =
                        inspector.structFieldSynTypesByType[structTypeName]?.get(fieldProp)?.let { renderType(it, selfTypeName) }
                            ?: headerContext.crateStructFieldTypes["$structTypeName.$fieldProp"]
                    if (fieldTy != null) {
                        currentTypedLocals = currentTypedLocals + (ktVar to fieldTy)
                        if (fieldTy.endsWith("?")) {
                            currentNullableLocals = currentNullableLocals + ktVar
                        }
                    }
                    if (fieldProp in inspector.vecFieldsByType[structTypeName].orEmpty()) {
                        currentMutableListLocals = currentMutableListLocals + ktVar
                    }
                    if (fieldProp in inspector.nullableFieldsByType[structTypeName].orEmpty()) {
                        currentNullableLocals = currentNullableLocals + ktVar
                    }
                }
                lines += "$indent$kw $ktVar = $receiverVar.$fieldProp"
            }
        } else {
            lines += extractMatchArmBindings(fp.pat, "$receiverVar.$fieldProp", indent, selfTypeName, bindToScope = true)
        }
    }
    return lines.joinToString("\n")
}

internal fun KotlinJ2KEmitter.inferEmptyListElementTypeFromReturn(enclosingRet: String?): String? {
    if (enclosingRet == null) return null
    val unwrappedResult =
        if (enclosingRet.startsWith("Result<") && enclosingRet.endsWith(">")) {
            enclosingRet.removePrefix("Result<").removeSuffix(">").trim()
        } else {
            enclosingRet
        }
    return when {
        unwrappedResult.startsWith("List<") && unwrappedResult.endsWith(">") ->
            unwrappedResult.removePrefix("List<").removeSuffix(">").trim()
        unwrappedResult.startsWith("MutableList<") && unwrappedResult.endsWith(">") ->
            unwrappedResult.removePrefix("MutableList<").removeSuffix(">").trim()
        unwrappedResult.startsWith("IntoIter<") && unwrappedResult.endsWith(">") ->
            unwrappedResult.removePrefix("IntoIter<").removeSuffix(">").trim()
        else -> null
    }
}

internal fun KotlinJ2KEmitter.isSideEffectFreeExpr(expr: Expr): Boolean =
    when (expr) {
        is Expr.Path, is Expr.Lit -> true
        is Expr.Reference -> isSideEffectFreeExpr(expr.expr)
        is Expr.Paren -> isSideEffectFreeExpr(expr.expr)
        is Expr.Group -> isSideEffectFreeExpr(expr.expr)
        is Expr.Field -> isSideEffectFreeExpr(expr.base)
        is Expr.Tuple -> expr.elems.toList().all { isSideEffectFreeExpr(it) }
        else -> false
    }

internal fun KotlinJ2KEmitter.isMutablePattern(pat: Pat): Boolean =
    when (pat) {
        is Pat.Ident -> pat.mutability != null
        is Pat.TypeAscription -> isMutablePattern(pat.pat)
        is Pat.Reference -> pat.mutability != null || isMutablePattern(pat.pat)
        is Pat.Guard -> isMutablePattern(pat.pat)
        is Pat.TupleStruct -> pat.elems.toList().any { isMutablePattern(it) }
        is Pat.Tuple -> pat.elems.toList().any { isMutablePattern(it) }
        else -> false
    }

internal fun KotlinJ2KEmitter.renderLocalPattern(
    pat: Pat,
    selfTypeName: String?,
    includeCtorInTupleStruct: Boolean = false,
    bindToScope: Boolean = false,
): Pair<String, String?> =
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            val baseName = rustValueIdentToKotlin(raw)
            val emittedName =
                if (bindToScope && raw != "_" && !raw.first().isUpperCase()) {
                    val assignCount = if (pat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                    scopeBuilder.bindLocal(raw, baseName, pat.ident.span(), assignCount = assignCount).kotlinName
                } else {
                    baseName
                }
            emittedName to null
        }
        is Pat.TypeAscription -> {
            val (innerPat, _) = renderLocalPattern(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
            val ty = if (pat.ty is SynType.Infer) null else renderType(pat.ty, selfTypeName)
            innerPat to ty
        }
        is Pat.Wild -> "unused" to null
        is Pat.Tuple, is Pat.TupleStruct, is Pat.Struct, is Pat.Slice -> {
            val names = extractPatternBindingNames(pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
            when (names.size) {
                0 -> "unused" to null
                1 -> names[0] to null
                else -> "(${names.joinToString(", ")})" to null
            }
        }
        is Pat.Reference -> renderLocalPattern(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
        is Pat.PatParen -> renderLocalPattern(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
        is Pat.Guard -> renderLocalPattern(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope)
        else -> "localVal" to null
    }

internal fun KotlinJ2KEmitter.extractPatternBindingNames(
    pat: Pat,
    selfTypeName: String?,
    includeCtorInTupleStruct: Boolean = false,
    bindToScope: Boolean = false,
): List<String> {
    val out = mutableListOf<String>()
    collectPatternBindingNames(pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
    return out
}

internal fun KotlinJ2KEmitter.collectPatternBindingNames(
    pat: Pat,
    selfTypeName: String?,
    includeCtorInTupleStruct: Boolean,
    bindToScope: Boolean,
    out: MutableList<String>,
) {
    when (pat) {
        is Pat.Ident -> {
            val raw = pat.ident.toString().removePrefix("r#")
            if (raw != "_") {
                if (raw.first().isUpperCase()) {
                    out += rustTypeIdentToKotlin(raw)
                } else if (bindToScope) {
                    val assignCount = if (pat.mutability != null) AssignCount.Any else AssignCount.AtMostOnce
                    out += scopeBuilder.bindLocal(raw, rustValueIdentToKotlin(raw), pat.ident.span(), assignCount = assignCount).kotlinName
                } else {
                    out += rustValueIdentToKotlin(raw)
                }
            }
            pat.subpat?.let { collectPatternBindingNames(it, selfTypeName, includeCtorInTupleStruct, bindToScope, out) }
        }
        is Pat.Wild, is Pat.Rest -> {}
        is Pat.Reference -> collectPatternBindingNames(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
        is Pat.PatParen -> collectPatternBindingNames(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
        is Pat.TypeAscription -> collectPatternBindingNames(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
        is Pat.Guard -> collectPatternBindingNames(pat.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
        is Pat.Tuple -> {
            val elems = pat.elems.toList()
            if (elems.size >= 2) {
                val perElem =
                    elems.map { sub ->
                        val subOut = mutableListOf<String>()
                        collectPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct, bindToScope, subOut)
                        subOut
                    }
                if (perElem.any { it.isNotEmpty() }) {
                    for (subOut in perElem) {
                        if (subOut.isEmpty()) {
                            out += "_"
                        } else {
                            out.addAll(subOut)
                        }
                    }
                }
            } else {
                for (sub in elems) {
                    collectPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
                }
            }
        }
        is Pat.TupleStruct -> {
            if (includeCtorInTupleStruct) {
                for (seg in pat.path.segments.toList()) {
                    val id = seg.ident.toString()
                    if (id !in IGNORED_PATH_PREFIXES) {
                        out += if (id.first().isUpperCase()) rustTypeIdentToKotlin(id) else rustValueIdentToKotlin(id)
                    }
                }
            }
            for (sub in pat.elems.toList()) {
                collectPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
            }
        }
        is Pat.Struct -> {
            if (includeCtorInTupleStruct) {
                for (seg in pat.path.segments.toList()) {
                    val id = seg.ident.toString()
                    if (id !in IGNORED_PATH_PREFIXES) {
                        out += if (id.first().isUpperCase()) rustTypeIdentToKotlin(id) else rustValueIdentToKotlin(id)
                    }
                }
            }
            for (fp in pat.fields.toList()) {
                collectPatternBindingNames(fp.pat, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
            }
        }
        is Pat.Slice -> {
            for (sub in pat.elems.toList()) {
                collectPatternBindingNames(sub, selfTypeName, includeCtorInTupleStruct, bindToScope, out)
            }
        }
        is Pat.Path -> {
            if (includeCtorInTupleStruct) {
                for (seg in pat.path.segments.toList()) {
                    val id = seg.ident.toString()
                    if (id !in IGNORED_PATH_PREFIXES) {
                        out += if (id.first().isUpperCase()) rustTypeIdentToKotlin(id) else rustValueIdentToKotlin(id)
                    }
                }
            }
        }
        else -> {}
    }
}
