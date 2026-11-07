package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.GenericArgument
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Member
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.Stmt
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.TypeParamBound
import io.github.kotlinmania.syn.Variant
import io.github.kotlinmania.syn.Visibility
import io.github.kotlinmania.syn.WhereClause
import io.github.kotlinmania.syn.WherePredicate
import io.github.kotlinmania.syn.gen.Visit
import io.github.kotlinmania.syn.span

internal fun KotlinJ2KEmitter.renderItemConst(
    item: Item.Const,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val rawIdent = item.ident.toString()
    if (rawIdent == "_") {
        val expr = item.expr
        if (expr is Expr.BlockExpr) {
            val stmts = expr.block.stmts
            val rendered =
                stmts.mapNotNull { stmt ->
                    if (stmt is Stmt.ItemStmt) {
                        renderItem(stmt.item, indent, emptyMap(), isLocalItem = isLocalItem, scopeSignatures = scopeSignatures)
                            .takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
            return rendered.joinToString("\n\n")
        }
        return ""
    }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val cleanRaw = rawIdent.removePrefix("r#")
    val baseName =
        if (!isLocalItem) {
            inspector.declaredConstNames[cleanRaw] ?: rustConstIdentToKotlin(rawIdent)
        } else {
            rustConstIdentToKotlin(rawIdent)
        }
    val name = disambiguateName(baseName, scopeSignatures)
    val ty = renderType(item.ty, selfTypeName = null)
    val numKind = resolveNumericKind(ty)
    val initExpr = renderTypedNumericInitializer(item.expr, numKind, indent, selfTypeName = null)
    sb
        .append(indent)
        .append(vis)
        .append("val ")
        .append(name)
        .append(": ")
        .append(ty)
        .append(" = ")
        .append(initExpr)
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderItemStatic(
    item: Item.Static,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val rawIdent = item.ident.toString()
    val cleanRaw = rawIdent.removePrefix("r#")
    val baseName =
        if (!isLocalItem) {
            inspector.declaredConstNames[cleanRaw] ?: rustConstIdentToKotlin(rawIdent)
        } else {
            rustConstIdentToKotlin(rawIdent)
        }
    val name = disambiguateName(baseName, scopeSignatures)
    val ty = renderType(item.ty, selfTypeName = null)
    val numKind = resolveNumericKind(ty)
    val expr = renderTypedNumericInitializer(item.expr, numKind, indent, selfTypeName = null)
    sb
        .append(indent)
        .append(vis)
        .append("var ")
        .append(name)
        .append(": ")
        .append(ty)
        .append(" = ")
        .append(expr)
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderItemType(
    item: Item.ItemType,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    if (isLocalItem || isExternalIdentityTypeReexportAlias(item, headerContext.selfCrateAliases)) {
        return ""
    }
    val name = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "typealias:$name"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (headerContext.rawAbiMode || headerContext.finalClasses) "public " else renderVisibility(item.vis)
    val typeParams = renderTypeParameters(item.generics, selfTypeName = name)
    if (typeParams.isEmpty() && indent.isEmpty()) {
        val unwrappedTy = unwrapSynType(item.ty)
        if (unwrappedTy is SynType.FnPtr) {
            sb.append(renderBareFnSamInterface(vis, name, unwrappedTy, indent))
            return sb.toString()
        }
        val optionBareFn = extractOptionBareFn(unwrappedTy)
        if (optionBareFn != null) {
            val samName = "${name}Fn"
            if (samName !in inspector.declaredTypeNames && samName !in synthesizedSamDeclarations) {
                inspector.declaredTypeNames += samName
                sb.append(renderBareFnSamInterface(vis, samName, optionBareFn, indent)).append("\n\n")
            }
            sb
                .append(indent)
                .append(vis)
                .append("typealias ")
                .append(name)
                .append(" = ")
                .append(samName)
                .append('?')
            return sb.toString()
        }
    }
    val rawIdent = item.ident.toString().removePrefix("r#")
    val cPointerWidthRhs =
        if (typeParams.isEmpty()) {
            val singleSeg = extractSinglePrimitiveTargetIdent(item.ty)
            val isFfiAlias =
                headerContext.rawAbiMode ||
                    name in inspector.ffiReferencedTypeNames ||
                    rawIdent in inspector.ffiReferencedTypeNames
            when {
                singleSeg == "usize" &&
                    (
                        isFfiAlias ||
                            isCPointerWidthUnsignedTypeName(rawIdent) ||
                            scopedAbiKeysForRenderedType(name).any {
                                headerContext.crateNumericTypeKinds[it] in setOf("U64", "U32", "U16")
                            }
                    ) ->
                    when (activeCfgProfile.pointerWidth) {
                        64 -> "ULong"
                        16 -> "UShort"
                        else -> "UInt"
                    }
                singleSeg == "isize" &&
                    (
                        isFfiAlias ||
                            isCPointerWidthSignedTypeName(rawIdent) ||
                            scopedAbiKeysForRenderedType(name).any {
                                headerContext.crateNumericTypeKinds[it] in setOf("I64", "I32", "I16")
                            }
                    ) ->
                    when (activeCfgProfile.pointerWidth) {
                        64 -> "Long"
                        16 -> "Short"
                        else -> "Int"
                    }
                else -> null
            }
        } else {
            null
        }
    val rhs =
        if (cPointerWidthRhs != null) {
            cPointerWidthRhs
        } else {
            val prevHint = currentSamNameHint
            currentSamNameHint = "${name}Callback"
            try {
                renderType(item.ty, selfTypeName = name)
            } finally {
                currentSamNameHint = prevHint
            }
        }
    if (rhs.isEmpty() || rhs == name || rhs == "$name$typeParams") {
        inspector.declaredTypeNames.remove(name)
        return ""
    }
    sb
        .append(indent)
        .append(vis)
        .append("typealias ")
        .append(name)
        .append(typeParams)
        .append(" = ")
        .append(rhs)
    return sb.toString()
}

internal fun KotlinJ2KEmitter.extractFnTraitSubstitutions(
    params: List<GenericParam>,
    whereClause: WhereClause?,
    selfTypeName: String?,
): Map<String, String> {
    val fnTypeParams =
        params
            .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
            .toSet()
    if (fnTypeParams.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    fun inspectBounds(tpName: String, bounds: List<TypeParamBound>) {
        for (b in bounds) {
            if (b is TypeParamBound.Trait && b.maybe == null) {
                val lastSeg = b.path.segments.last() ?: continue
                val rawName = lastSeg.ident.toString()
                if ((rawName == "Fn" || rawName == "FnMut" || rawName == "FnOnce") &&
                    lastSeg.arguments is PathArguments.Parenthesized
                ) {
                    result[tpName] = renderPathAsType(b.path, selfTypeName)
                }
            }
        }
    }
    for (gp in params) {
        if (gp is GenericParam.TypeParam) {
            val tpName = rustTypeIdentToKotlin(gp.ident.toString())
            inspectBounds(tpName, gp.bounds.toList())
        }
    }
    for (pred in whereClause?.predicates?.toList().orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            val tpName = renderType(pred.boundedTy, selfTypeName)
            if (tpName in fnTypeParams) {
                inspectBounds(tpName, pred.bounds.toList())
            }
        }
    }
    return result
}

internal fun KotlinJ2KEmitter.renderItemFn(
    item: Item.Fn,
    indent: String,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val prevClosureLabel = currentClosureLabel
    val prevEnclosingFn = currentEnclosingFunctionName
    val prevEnclosingRet = currentEnclosingReturnRenderedType
    val prevMutableListLocals = currentMutableListLocals
    val prevDequeLocals = currentDequeLocals
    val prevIterableLocals = currentIterableLocals
    val prevTypedLocals = currentTypedLocals
    val prevWitnessedBounds = currentWitnessedTraitBounds
    val prevNullableLocals = currentNullableLocals
    val prevTypeParams = activeTypeParamNames
    val prevSubst = substitutedTypeParamTypes
    val prevWitnesses = currentTypeParamWitnessCalls
    currentClosureLabel = null
    currentEnclosingFunctionName = rustFunctionIdentToKotlin(item.sig.ident.toString())
    currentEnclosingReturnRenderedType = null
    currentMutableListLocals = emptySet()
    currentDequeLocals = emptySet()
    currentIterableLocals = emptySet()
    currentTypedLocals = emptyMap()
    currentWitnessedTraitBounds = emptyMap()
    currentNullableLocals = emptySet()
    val fnGenerics = item.sig.generics
    val fnParamList = fnGenerics.params.toList()
    val fnSubsts = extractFnTraitSubstitutions(fnParamList, fnGenerics.whereClause, null)
    if (fnSubsts.isNotEmpty()) {
        substitutedTypeParamTypes = prevSubst + fnSubsts
    }
    activeTypeParamNames = (activeTypeParamNames + collectGenericTypeParamNames(item.sig.generics)) - substitutedTypeParamTypes.keys
    scopeBuilder.enterDef()
    try {
        val sigHeader =
            renderFunctionSignature(
                sig = item.sig,
                visPrefix = vis,
                openPrefix = "",
                selfTypeName = null,
                ownerGenerics = null,
                scopeSignatures = scopeSignatures,
                isInterfaceMethod = false,
                fnBlock = item.block,
            ) ?: return ""
        val sb = StringBuilder()
        sb.append(renderLeadingTrivia(item, indent))
        sb.append(renderKDocFromAttributes(item.attrs, indent))
        if (!isLocalItem && hasTestAttribute(item.attrs)) {
            sb.append(indent).append("@Test\n")
        }
        sb.append(indent).append(sigHeader)
        val fnBlock = item.block
        val retType = extractReturnSynType(item.sig.output)
        val shadows = computeReassignedParamShadows(item.sig, fnBlock, "$indent    ")
        sb.append(" ")
        sb.append(renderBlock(fnBlock, indent, selfTypeName = null, returnType = retType, leadingInjectedLines = shadows))
        return sb.toString()
    } finally {
        scopeBuilder.exitDef()
        currentClosureLabel = prevClosureLabel
        currentEnclosingFunctionName = prevEnclosingFn
        currentEnclosingReturnRenderedType = prevEnclosingRet
        currentMutableListLocals = prevMutableListLocals
        currentDequeLocals = prevDequeLocals
        currentIterableLocals = prevIterableLocals
        currentTypedLocals = prevTypedLocals
        currentWitnessedTraitBounds = prevWitnessedBounds
        currentNullableLocals = prevNullableLocals
        activeTypeParamNames = prevTypeParams
        substitutedTypeParamTypes = prevSubst
        currentTypeParamWitnessCalls = prevWitnesses
    }
}

internal fun KotlinJ2KEmitter.unwrapPaddingSynType(ty: SynType): SynType {
    val u = unwrapSynType(ty)
    if (u is SynType.Path && u.qself == null && "Padding" !in inspector.declaredTypeNames) {
        val segs = u.path.segments.toList()
        val last = segs.lastOrNull()
        if (last?.ident?.toString() == "Padding") {
            val angle = last.arguments as? PathArguments.AngleBracketed
            val inner =
                angle
                    ?.args
                    ?.toList()
                    ?.firstNotNullOfOrNull { (it as? GenericArgument.TypeArg)?.type }
            if (inner != null) return unwrapPaddingSynType(inner)
        }
    }
    return u
}

internal fun KotlinJ2KEmitter.renderFallbackDefaultForRenderedType(renderedType: String): String {
    val clean = renderedType.trim()
    val scopedKeys = scopedAbiKeysForRenderedType(clean)
    if (clean.endsWith("?") || scopedKeys.any { it in headerContext.crateOptionFnTypes }) return "null"
    synthesizedSamDefaultsByName[clean]?.let { return it }
    for (key in scopedKeys) {
        headerContext.crateTypeAliasDefaults[key]?.let { return it }
    }
    when (resolveNumericKind(clean)) {
        NumericKind.I8, NumericKind.I16, NumericKind.I32 -> return "0"
        NumericKind.I64 -> return "0L"
        NumericKind.I128 -> {
            usesI128 = true
            return "I128()"
        }
        NumericKind.U8, NumericKind.U16, NumericKind.U32 -> return "0u"
        NumericKind.U64 -> return "0uL"
        NumericKind.U128 -> {
            usesU128 = true
            return "U128()"
        }
        NumericKind.F32 -> return "0.0f"
        NumericKind.F64 -> return "0.0"
        null -> {}
    }
    return defaultInitializerForType(clean)
}

internal fun KotlinJ2KEmitter.normalizeArrayLengthToIntExpr(rawLen: String): String {
    val trimmed = rawLen.trim()
    val unsignedStripped =
        when {
            trimmed.endsWith("uL") || trimmed.endsWith("UL") -> trimmed.dropLast(2)
            trimmed.endsWith("u") || trimmed.endsWith("U") || trimmed.endsWith("L") -> trimmed.dropLast(1)
            else -> null
        }
    if (unsignedStripped != null &&
        unsignedStripped.isNotEmpty() &&
        (
            unsignedStripped.all { it.isDigit() || it == '_' } ||
                (
                    (unsignedStripped.startsWith("0x") || unsignedStripped.startsWith("0X")) &&
                        unsignedStripped.drop(2).isNotEmpty() &&
                        unsignedStripped.drop(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }
                )
        )
    ) {
        return unsignedStripped
    }
    if (trimmed.isNotEmpty() &&
        (
            trimmed.all { it.isDigit() || it == '_' } ||
                (
                    (trimmed.startsWith("0x") || trimmed.startsWith("0X")) &&
                        trimmed.drop(2).isNotEmpty() &&
                        trimmed.drop(2).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }
                )
        )
    ) {
        return trimmed
    }
    if (trimmed.endsWith(".toInt()")) return trimmed
    return "($trimmed).toInt()"
}

internal fun KotlinJ2KEmitter.renderStructFieldDefault(
    fieldSynTy: SynType,
    renderedType: String,
    selfTypeName: String?,
): String {
    val cleanType = renderedType.trim()
    val unwrapped = unwrapPaddingSynType(fieldSynTy)
    if (unwrapped is SynType.Array) {
        val rawLen = renderTypedNumericInitializer(unwrapped.len, NumericKind.I32, "", selfTypeName)
        val lenInt = normalizeArrayLengthToIntExpr(rawLen)
        if (cleanType in PRIMITIVE_ARRAY_TYPES) {
            return "$cleanType($lenInt)"
        }
        if (cleanType.startsWith("List<") && cleanType.endsWith(">")) {
            val elemTy = cleanType.removePrefix("List<").removeSuffix(">").trim()
            return "List($lenInt) { ${renderFallbackDefaultForRenderedType(elemTy)} }"
        }
    }
    return renderFallbackDefaultForRenderedType(cleanType)
}

internal fun KotlinJ2KEmitter.buildArrayAwareDataClassMethods(
    typeName: String,
    fields: List<Pair<String, String>>,
    memberIndent: String,
): List<String> {
    val eqLines = mutableListOf<String>()
    eqLines += "${memberIndent}override fun equals(other: Any?): Boolean {"
    eqLines += "$memberIndent    if (this === other) return true"
    eqLines += "$memberIndent    if (other !is $typeName) return false"
    for ((fName, fType) in fields) {
        if (fType in PRIMITIVE_ARRAY_TYPES) {
            eqLines += "$memberIndent    if (!this.$fName.contentEquals(other.$fName)) return false"
        } else {
            eqLines += "$memberIndent    if (this.$fName != other.$fName) return false"
        }
    }
    eqLines += "$memberIndent    return true"
    eqLines += "$memberIndent}"

    val hashLines = mutableListOf<String>()
    hashLines += "${memberIndent}override fun hashCode(): Int {"
    if (fields.isEmpty()) {
        hashLines += "$memberIndent    return 0"
    } else {
        val (firstName, firstType) = fields[0]
        val firstHash = if (firstType in PRIMITIVE_ARRAY_TYPES) "this.$firstName.contentHashCode()" else "this.$firstName.hashCode()"
        if (fields.size == 1) {
            hashLines += "$memberIndent    return $firstHash"
        } else {
            hashLines += "$memberIndent    var result = $firstHash"
            for (i in 1 until fields.size) {
                val (fName, fType) = fields[i]
                val fHash = if (fType in PRIMITIVE_ARRAY_TYPES) "this.$fName.contentHashCode()" else "this.$fName.hashCode()"
                hashLines += "$memberIndent    result = 31 * result + $fHash"
            }
            hashLines += "$memberIndent    return result"
        }
    }
    hashLines += "$memberIndent}"
    return listOf(eqLines.joinToString("\n"), hashLines.joinToString("\n"))
}

internal fun KotlinJ2KEmitter.renderItemStruct(
    item: Item.Struct,
    attachedImpls: List<Item.Impl>,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val typeName = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$typeName"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val effectiveAttachedImpls =
        if (useFinalAbiLayout) {
            attachedImpls.filterNot { impl ->
                impl.traitPath
                    ?.path
                    ?.segments
                    ?.last()
                    ?.ident
                    ?.toString() == "Default"
            }
        } else {
            attachedImpls
        }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val selfTypeWithGenerics = typeName + renderTypeArgumentsFromGenerics(item.generics)
    val prevStructTypeParams = activeTypeParamNames
    activeTypeParamNames = activeTypeParamNames + collectGenericTypeParamNames(item.generics)
    val typeParams: String
    val whereClause: String
    val superTraits = mutableListOf<String>()
    val extraIterableMembers = mutableListOf<String>()
    val ctorParams = mutableListOf<String>()
    val bodyFieldProps = mutableListOf<String>()
    val fieldNameAndTypes = mutableListOf<Pair<String, String>>()
    try {
        typeParams = renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics)
        whereClause =
            renderWhereClause(
                item.generics.whereClause,
                selfTypeName = selfTypeWithGenerics,
                ownerGenerics = item.generics,
            )
        superTraits.addAll(renderDistinctSuperTraits(effectiveAttachedImpls, selfTypeWithGenerics))
        val iterElemTy = extractDirectIteratorItemRenderedType(effectiveAttachedImpls, "Iterator", selfTypeWithGenerics, requireOwnedSelf = false)
        val intoIterElemTy =
            if (iterElemTy == null) {
                extractDirectIteratorItemRenderedType(effectiveAttachedImpls, "IntoIterator", selfTypeWithGenerics, requireOwnedSelf = true)
            } else {
                null
            }
        val iterableElem = iterElemTy ?: intoIterElemTy
        if (iterableElem != null && superTraits.none { it.substringBefore('<').trim() == "Iterable" }) {
            superTraits += "Iterable<$iterableElem>"
            val mIndent = "$indent    "
            if (iterElemTy != null) {
                val iterBody =
                    buildString {
                        append(mIndent)
                        append("override fun iterator(): kotlin.collections.Iterator<")
                        append(iterElemTy)
                        append("> = object : AbstractIterator<")
                        append(iterElemTy)
                        append(">() {\n")
                        append(mIndent).append("    override fun computeNext() {\n")
                        append(mIndent).append("        when (val v = this@").append(typeName).append(".next()) {\n")
                        append(mIndent).append("            null -> done()\n")
                        append(mIndent).append("            else -> setNext(v)\n")
                        append(mIndent).append("        }\n")
                        append(mIndent).append("    }\n")
                        append(mIndent).append("}")
                    }
                extraIterableMembers += iterBody
            } else if (intoIterElemTy != null) {
                extraIterableMembers +=
                    "${mIndent}override fun iterator(): kotlin.collections.Iterator<$intoIterElemTy> = this.intoIter().iterator()"
            }
        }
        when (val fields = item.fields) {
            is Fields.Named -> {
                val fieldList = fields.fields.named.toList()
                val seenFieldNames = hashSetOf<String>()
                for ((idx, field) in fieldList.withIndex()) {
                    if (isDocOnlyCfgAttribute(field.attrs)) continue
                    val rawFieldIdent = field.ident?.toString()?.removePrefix("r#") ?: "field$idx"
                    val fName = rustFieldIdentToKotlin(field.ident?.toString() ?: "field$idx")
                    if (!seenFieldNames.add(fName)) continue
                    val fieldTrivia = renderLeadingTrivia(field, "$indent    ")
                    val fieldDoc = renderKDocFromAttributes(field.attrs, "$indent    ")
                    val fVis = renderVisibility(field.vis)
                    val prevHint = currentSamNameHint
                    currentSamNameHint = "${typeName}${fName.replaceFirstChar { it.uppercase() }}Callback"
                    var fType =
                        try {
                            renderType(field.ty, selfTypeName = selfTypeWithGenerics)
                        } finally {
                            currentSamNameHint = prevHint
                        }
                    if (field.vis !is Visibility.Public &&
                        fType.startsWith("List<") &&
                        structImplsMutateField(effectiveAttachedImpls, rawFieldIdent)
                    ) {
                        fType = "Mutable$fType"
                    }
                    val combinedFieldHeader = fieldTrivia + fieldDoc
                    val docPrefix = if (combinedFieldHeader.isNotEmpty()) "\n$combinedFieldHeader$indent    " else ""
                    val defaultSuffix =
                        if (useFinalAbiLayout) {
                            " = ${renderStructFieldDefault(field.ty, fType, selfTypeWithGenerics)}"
                        } else {
                            ""
                        }
                    ctorParams += "${docPrefix}${fVis}var $fName: $fType$defaultSuffix"
                    bodyFieldProps += "${combinedFieldHeader}$indent    ${fVis}var $fName: $fType$defaultSuffix"
                    fieldNameAndTypes += fName to fType
                }
            }
            is Fields.Unnamed -> {
                val fieldList = fields.fields.unnamed.toList()
                for ((idx, field) in fieldList.withIndex()) {
                    val fVis = renderVisibility(field.vis)
                    val propName = tupleFieldPropertyName(idx, fieldList.size)
                    val prevHint = currentSamNameHint
                    currentSamNameHint = "${typeName}Field${idx}Callback"
                    val fType =
                        try {
                            renderType(field.ty, selfTypeName = selfTypeWithGenerics)
                        } finally {
                            currentSamNameHint = prevHint
                        }
                    val defaultSuffix =
                        if (useFinalAbiLayout) {
                            " = ${renderStructFieldDefault(field.ty, fType, selfTypeWithGenerics)}"
                        } else {
                            ""
                        }
                    ctorParams += "${fVis}var $propName: $fType$defaultSuffix"
                    bodyFieldProps += "$indent    ${fVis}var $propName: $fType$defaultSuffix"
                    fieldNameAndTypes += propName to fType
                }
            }
            is Fields.Unit -> {}
        }
    } finally {
        activeTypeParamNames = prevStructTypeParams
    }

    val useBodyPropertiesForLargeStruct = useFinalAbiLayout && ctorParams.size > 64
    val isDataClass =
        useFinalAbiLayout &&
            ctorParams.size in 1..16 &&
            superTraits.isEmpty() &&
            extraIterableMembers.isEmpty() &&
            typeParams.isEmpty()
    if (isDataClass && fieldNameAndTypes.any { it.second in PRIMITIVE_ARRAY_TYPES }) {
        extraIterableMembers.addAll(buildArrayAwareDataClassMethods(typeName, fieldNameAndTypes, "$indent    "))
    }
    val classKeyword =
        when {
            isDataClass -> "data class "
            useFinalAbiLayout -> "class "
            else -> "open class "
        }

    sb
        .append(indent)
        .append(vis)
        .append(classKeyword)
        .append(typeName)
        .append(typeParams)
    if (ctorParams.isNotEmpty() && !useBodyPropertiesForLargeStruct) {
        if (ctorParams.any { it.contains('\n') } || ctorParams.size > 2) {
            sb.append("(\n")
            for (p in ctorParams) {
                if (p.startsWith('\n')) {
                    sb.append(p.removePrefix("\n")).append(",\n")
                } else {
                    sb
                        .append(indent)
                        .append("    ")
                        .append(p)
                        .append(",\n")
                }
            }
            sb.append(indent).append(")")
        } else {
            sb.append("(").append(ctorParams.joinToString(", ")).append(")")
        }
    }
    if (superTraits.isNotEmpty()) {
        sb.append(" : ").append(superTraits.joinToString(", "))
    }
    sb.append(whereClause)

    val extraProps =
        if (useBodyPropertiesForLargeStruct) {
            bodyFieldProps + buildTupleStructAliasProperties(item.fields, selfTypeWithGenerics, "$indent    ")
        } else {
            buildTupleStructAliasProperties(item.fields, selfTypeWithGenerics, "$indent    ")
        }
    val prevOverrideNames = currentStructOverrideMethodNames
    currentStructOverrideMethodNames = collectLocalSupertraitMethodNames(superTraits)
    val memberLines =
        try {
            renderAttachedImplMembers(
                typeName = selfTypeWithGenerics,
                ownerGenerics = item.generics,
                attachedImpls = effectiveAttachedImpls,
                indent = "$indent    ",
                extraProperties = extraProps,
                extraMembers = extraIterableMembers,
                isLocalClass = isLocalItem,
            )
        } finally {
            currentStructOverrideMethodNames = prevOverrideNames
        }
    if (memberLines.isNotEmpty()) {
        sb.append(" {\n")
        sb.append(memberLines)
        if (!memberLines.endsWith('\n')) sb.append('\n')
        sb.append(indent).append("}")
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.extractDirectIteratorItemRenderedType(
    attachedImpls: List<Item.Impl>,
    traitRawName: String,
    selfTypeWithGenerics: String,
    requireOwnedSelf: Boolean,
): String? {
    for (impl in attachedImpls) {
        if (isDocOnlyCfgAttribute(impl.attrs)) continue
        if (requireOwnedSelf && unwrapSynType(impl.selfType) is SynType.Reference) continue
        val rawTrait =
            impl.traitPath
                ?.path
                ?.segments
                ?.last()
                ?.ident
                ?.toString() ?: continue
        if (rawTrait != traitRawName) continue
        for (sub in impl.items) {
            if (sub is ImplItem.AssocType && sub.ident.toString() == "Item") {
                return renderType(sub.ty, selfTypeWithGenerics)
            }
        }
    }
    return null
}

internal data class IteratorSupertraitSpec(
    val itemType: String,
    val hasDoubleEnded: Boolean,
    val hasExactSize: Boolean,
)

internal fun KotlinJ2KEmitter.extractIteratorSupertraitSpec(
    bounds: List<TypeParamBound>,
    selfTypeName: String?,
): IteratorSupertraitSpec? {
    var sawIteratorFamily = false
    var hasDoubleEnded = false
    var hasExactSize = false
    var itemRendered: String? = null
    for (b in bounds) {
        if (b !is TypeParamBound.Trait || b.maybe != null) continue
        val lastSeg = b.path.segments.last() ?: continue
        val rawName = lastSeg.ident.toString()
        if (rawName == "Iterator" || rawName == "DoubleEndedIterator" || rawName == "ExactSizeIterator") {
            sawIteratorFamily = true
            if (rawName == "DoubleEndedIterator") hasDoubleEnded = true
            if (rawName == "ExactSizeIterator") hasExactSize = true
            val angle = lastSeg.arguments as? PathArguments.AngleBracketed
            if (angle != null && itemRendered == null) {
                for (ga in angle.args.toList()) {
                    if (ga is GenericArgument.AssocTypeArg && ga.assoc.ident.toString() == "Item") {
                        itemRendered = renderType(ga.assoc.ty, selfTypeName)
                    }
                }
            }
        }
    }
    if (!sawIteratorFamily || itemRendered == null) return null
    return IteratorSupertraitSpec(
        itemType = itemRendered,
        hasDoubleEnded = hasDoubleEnded,
        hasExactSize = hasExactSize,
    )
}

internal fun KotlinJ2KEmitter.collectLocalSupertraitMethodNames(superTraits: List<String>): Set<String> {
    val names = linkedSetOf<String>()
    for (st in superTraits) {
        val baseName = st.substringBefore('<').trim()
        val traitDecl = inspector.declaredTraitsByName[baseName] ?: continue
        for (tItem in traitDecl.items) {
            if (traitItemHasDocOnlyCfg(tItem)) continue
            if (tItem is TraitItem.Fn && tItem.sig.inputs.firstOrNull() is FnArg.Receiver) {
                names += rustFunctionIdentToKotlin(tItem.sig.ident.toString())
            }
        }
        val iterSpec = extractIteratorSupertraitSpec(traitDecl.supertraits.toList(), null)
        if (iterSpec != null) {
            names += "next"
            if (iterSpec.hasDoubleEnded) names += "nextBack"
            if (iterSpec.hasExactSize) names += "len"
        }
    }
    return names
}

internal fun KotlinJ2KEmitter.renderDistinctSuperTraits(
    attachedImpls: List<Item.Impl>,
    selfTypeWithGenerics: String,
): List<String> {
    val seenInterfaces = hashSetOf<String>()
    val result = mutableListOf<String>()
    for (impl in attachedImpls) {
        if (isDocOnlyCfgAttribute(impl.attrs)) continue
        val path = impl.traitPath?.path ?: continue
        val rawTrait =
            path.segments
                .last()
                ?.ident
                ?.toString() ?: continue
        if (rawTrait in IGNORED_TRAIT_BOUNDS) continue
        val ktTrait = rustTypeIdentToKotlin(rawTrait)
        if (ktTrait in RUST_STD_IMPLICIT_SUPERTRAITS && ktTrait !in inspector.declaredTypeNames) {
            continue
        }
        val prevBounded = currentBoundedTypeName
        currentBoundedTypeName = selfTypeWithGenerics
        val rendered =
            try {
                renderPathAsType(path, selfTypeName = selfTypeWithGenerics)
            } finally {
                currentBoundedTypeName = prevBounded
            }
        val baseIface = rendered.substringBefore('<').trim()
        if (baseIface.isNotEmpty() && !rendered.contains("->") && seenInterfaces.add(baseIface)) {
            result += rendered
        }
    }
    return result
}

internal fun KotlinJ2KEmitter.structImplsMutateField(attachedImpls: List<Item.Impl>, rawFieldIdent: String): Boolean {
    var mutated = false
    val visitor =
        object : Visit() {
            override fun visitExprMethodCall(e: Expr.MethodCall) {
                val m = e.method.toString()
                if (m in MUTATING_VEC_METHODS) {
                    val recv = e.receiver
                    if (recv is Expr.Field &&
                        recv.base is Expr.Path &&
                        (recv.base as Expr.Path).path.isIdent("self") &&
                        recv.member is Member.Named &&
                        (recv.member as Member.Named).ident.toString().removePrefix("r#") == rawFieldIdent
                    ) {
                        mutated = true
                        return
                    }
                }
                super.visitExprMethodCall(e)
            }
        }
    for (impl in attachedImpls) {
        visitor.visitItemImpl(impl)
        if (mutated) return true
    }
    return false
}

internal fun KotlinJ2KEmitter.buildTupleStructAliasProperties(
    fields: Fields,
    selfTypeName: String,
    memberIndent: String,
): List<String> {
    if (fields !is Fields.Unnamed) return emptyList()
    val list = fields.fields.unnamed.toList()
    if (list.size != 1) return emptyList()
    val fType = renderType(list[0].ty, selfTypeName)
    return listOf("${memberIndent}public var field0: $fType\n$memberIndent    get() = value\n$memberIndent    set(v) { value = v }")
}

internal fun KotlinJ2KEmitter.renderAttachedImplMembers(
    typeName: String,
    ownerGenerics: Generics,
    attachedImpls: List<Item.Impl>,
    indent: String,
    extraProperties: List<String> = emptyList(),
    extraMembers: List<String> = emptyList(),
    isLocalClass: Boolean = false,
): String {
    val instanceMembers = mutableListOf<String>()
    val companionMembers = mutableListOf<String>()
    val instanceSignatures = linkedMapOf<String, Int>()
    val companionSignatures = linkedMapOf<String, Int>()

    instanceMembers.addAll(extraProperties)
    instanceMembers.addAll(extraMembers)

    val ownerFallbackAssocTypes =
        buildMap<String, SynType> {
            for (impl in attachedImpls) {
                if (isDocOnlyCfgAttribute(impl.attrs)) continue
                for (implItem in impl.items) {
                    if (implItem is ImplItem.AssocType) {
                        val key = implItem.ident.toString()
                        if (key !in this) {
                            put(key, implItem.ty)
                        }
                    }
                }
            }
        }

    for (impl in attachedImpls) {
        if (isDocOnlyCfgAttribute(impl.attrs)) continue
        val implComments =
            (renderLeadingTrivia(impl, indent) + renderKDocFromAttributes(impl.attrs, indent))
                .trimEnd()
        val instStart = instanceMembers.size
        val compStart = companionMembers.size
        val prevAssocTypes = currentImplAssocTypes
        val prevAttachedTraitPath = currentAttachedImplTraitPath
        val prevAttachedIsRefSelf = currentAttachedImplIsRefSelf
        val prevSubst = substitutedTypeParamTypes
        currentAttachedImplTraitPath = impl.traitPath?.path
        currentAttachedImplIsRefSelf = unwrapSynType(impl.selfType) is SynType.Reference
        val selfPathParam =
            (unwrapSynType(impl.selfType) as? SynType.Path)
                ?.takeIf { it.qself == null && it.path.segments.len() == 1 }
                ?.path
                ?.segments
                ?.first()
                ?.ident
                ?.toString()
                ?.let(::rustTypeIdentToKotlin)
        val ownerBaseName = typeName.substringBefore('<').trim()
        if (selfPathParam != null &&
            selfPathParam != ownerBaseName &&
            selfPathParam in collectGenericTypeParamNames(impl.generics)
        ) {
            substitutedTypeParamTypes = prevSubst + (selfPathParam to typeName)
        }
        currentImplAssocTypes =
            buildMap {
                putAll(prevAssocTypes)
                putAll(ownerFallbackAssocTypes)
                for (implItem in impl.items) {
                    if (implItem is ImplItem.AssocType) {
                        put(implItem.ident.toString(), implItem.ty)
                    }
                }
            }
        try {
            for (implItem in impl.items) {
                if (implItemHasDocOnlyCfg(implItem)) continue
                when (implItem) {
                    is ImplItem.Const -> {
                        val constIndent = if (isLocalClass) indent else "$indent    "
                        val targetList = if (isLocalClass) instanceMembers else companionMembers
                        targetList += renderImplItemConst(implItem, constIndent, typeName, companionSignatures)
                    }
                    is ImplItem.Fn -> {
                        val hasReceiver = implItem.sig.inputs.firstOrNull() is FnArg.Receiver
                        if (!hasReceiver && !isLocalClass) {
                            val rendered =
                                renderImplItemFn(
                                    item = implItem,
                                    indent = "$indent    ",
                                    selfTypeName = typeName,
                                    ownerGenerics = ownerGenerics,
                                    implGenerics = impl.generics,
                                    scopeSignatures = companionSignatures,
                                    isCompanionMember = true,
                                )
                            if (rendered.isNotBlank()) companionMembers += rendered
                        } else {
                            val rendered =
                                renderImplItemFn(
                                    item = implItem,
                                    indent = indent,
                                    selfTypeName = typeName,
                                    ownerGenerics = ownerGenerics,
                                    implGenerics = impl.generics,
                                    scopeSignatures = instanceSignatures,
                                )
                            if (rendered.isNotBlank()) instanceMembers += rendered
                        }
                    }
                    is ImplItem.AssocType -> {}
                    is ImplItem.Macro -> {
                        instanceMembers +=
                            renderImplOrTraitMacroMembers(
                                mac = implItem.mac,
                                indent = indent,
                                selfTypeName = typeName,
                                ownerGenerics = ownerGenerics,
                                scopeSignatures = instanceSignatures,
                                asTopLevelExtension = false,
                                isInterface = false,
                            )
                    }
                    is ImplItem.Verbatim -> {}
                }
            }
        } finally {
            currentImplAssocTypes = prevAssocTypes
            currentAttachedImplTraitPath = prevAttachedTraitPath
            currentAttachedImplIsRefSelf = prevAttachedIsRefSelf
            substitutedTypeParamTypes = prevSubst
        }
        if (implComments.isNotBlank()) {
            when {
                instanceMembers.size > instStart -> {
                    instanceMembers[instStart] = "$implComments\n${instanceMembers[instStart]}"
                }
                companionMembers.size > compStart -> {
                    val reindented =
                        implComments.lines().joinToString("\n") { line ->
                            if (line.isBlank()) line else "    $line"
                        }
                    companionMembers[compStart] = "$reindented\n${companionMembers[compStart]}"
                }
                else -> {
                    instanceMembers += implComments
                }
            }
        }
    }

    if (companionMembers.isNotEmpty()) {
        val compSb = StringBuilder()
        compSb.append(indent).append("public companion object {\n")
        compSb.append(companionMembers.joinToString("\n\n"))
        compSb.append('\n').append(indent).append("}")
        instanceMembers += compSb.toString()
    }

    return instanceMembers.filter { it.isNotBlank() }.joinToString("\n\n")
}

internal fun KotlinJ2KEmitter.renderItemUnion(
    item: Item.Union,
    attachedImpls: List<Item.Impl>,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val typeName = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$typeName"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val selfTypeWithGenerics = typeName + renderTypeArgumentsFromGenerics(item.generics)
    val typeParams = renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics)
    val seenFieldNames = hashSetOf<String>()
    val fieldNameAndTypes = mutableListOf<Pair<String, String>>()
    val bodyFieldProps = mutableListOf<String>()
    val ctorParams =
        item.fields.named.toList().mapIndexedNotNull { idx, field ->
            if (isDocOnlyCfgAttribute(field.attrs)) return@mapIndexedNotNull null
            val fName = rustFieldIdentToKotlin(field.ident?.toString() ?: "field$idx")
            if (!seenFieldNames.add(fName)) return@mapIndexedNotNull null
            val fieldTrivia = renderLeadingTrivia(field, "$indent    ")
            val fieldDoc = renderKDocFromAttributes(field.attrs, "$indent    ")
            val fVis = renderVisibility(field.vis)
            val prevHint = currentSamNameHint
            currentSamNameHint = "${typeName}${fName.replaceFirstChar { it.uppercase() }}Callback"
            val fType =
                try {
                    renderType(field.ty, selfTypeName = selfTypeWithGenerics)
                } finally {
                    currentSamNameHint = prevHint
                }
            val combinedFieldHeader = fieldTrivia + fieldDoc
            val docPrefix = if (combinedFieldHeader.isNotEmpty()) "\n$combinedFieldHeader$indent    " else ""
            if (useFinalAbiLayout) {
                val defVal = renderStructFieldDefault(field.ty, fType, selfTypeWithGenerics)
                fieldNameAndTypes += fName to fType
                bodyFieldProps += "${combinedFieldHeader}$indent    ${fVis}var $fName: $fType = $defVal"
                "${docPrefix}${fVis}var $fName: $fType = $defVal"
            } else {
                "${docPrefix}${fVis}var $fName: $fType? = null"
            }
        }
    val useBodyPropertiesForLargeUnion = useFinalAbiLayout && ctorParams.size > 64
    val isDataClass = useFinalAbiLayout && ctorParams.size in 1..16 && typeParams.isEmpty()
    val extraUnionMembers =
        if (isDataClass && fieldNameAndTypes.any { it.second in PRIMITIVE_ARRAY_TYPES }) {
            buildArrayAwareDataClassMethods(typeName, fieldNameAndTypes, "$indent    ")
        } else {
            emptyList()
        }
    val classKeyword =
        when {
            isDataClass -> "data class "
            useFinalAbiLayout -> "class "
            else -> "open class "
        }
    sb
        .append(indent)
        .append(vis)
        .append(classKeyword)
        .append(typeName)
        .append(typeParams)
    if (ctorParams.isNotEmpty() && !useBodyPropertiesForLargeUnion) {
        if (ctorParams.any { it.contains('\n') } || ctorParams.size > 2) {
            sb.append("(\n")
            for (p in ctorParams) {
                if (p.startsWith('\n')) {
                    sb.append(p.removePrefix("\n")).append(",\n")
                } else {
                    sb
                        .append(indent)
                        .append("    ")
                        .append(p)
                        .append(",\n")
                }
            }
            sb.append(indent).append(")")
        } else {
            sb.append("(").append(ctorParams.joinToString(", ")).append(")")
        }
    }
    val hasExplicitDebug = hasExplicitOriginalDebugImpl(originalFile.items, typeName)
    val filteredImpls =
        attachedImpls.filterNot { impl ->
            val traitIdent =
                impl.traitPath
                    ?.path
                    ?.segments
                    ?.last()
                    ?.ident
                    ?.toString()
            (!hasExplicitDebug && traitIdent == "Debug") || (useFinalAbiLayout && traitIdent == "Default")
        }
    val memberLines =
        renderAttachedImplMembers(
            selfTypeWithGenerics,
            item.generics,
            filteredImpls,
            "$indent    ",
            extraProperties = if (useBodyPropertiesForLargeUnion) bodyFieldProps else emptyList(),
            extraMembers = extraUnionMembers,
            isLocalClass = isLocalItem,
        )
    if (memberLines.isNotEmpty()) {
        sb
            .append(" {\n")
            .append(memberLines)
            .append('\n')
            .append(indent)
            .append("}")
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.hasExplicitOriginalDebugImpl(items: List<Item>, ownerTypeName: String): Boolean {
    for (sub in items) {
        when (sub) {
            is Item.Impl -> {
                val lastTraitSeg =
                    sub.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                if (lastTraitSeg?.ident?.toString() == "Debug" &&
                    extractDirectOwnerTypeName(sub.selfType, sub.generics) == ownerTypeName
                ) {
                    return true
                }
            }
            is Item.Mod -> {
                val content = sub.content
                if (content is ModContent.Inline && hasExplicitOriginalDebugImpl(content.items, ownerTypeName)) {
                    return true
                }
            }
            else -> {}
        }
    }
    return false
}

internal fun KotlinJ2KEmitter.formatEnumOffsetLiteral(offset: Int, reprNumKind: NumericKind?): String =
    when (reprNumKind) {
        NumericKind.U8 -> "${offset}u.toUByte()"
        NumericKind.U16 -> "${offset}u.toUShort()"
        NumericKind.U32 -> "${offset}u"
        NumericKind.U64 -> "${offset}uL"
        NumericKind.I8 -> "$offset.toByte()"
        NumericKind.I16 -> "$offset.toShort()"
        NumericKind.I64 -> "${offset}L"
        else -> "$offset"
    }

internal fun KotlinJ2KEmitter.formatEnumSteppedDiscriminant(
    baseExpr: String?,
    offset: Int,
    reprNumKind: NumericKind?,
): String {
    if (baseExpr == null) {
        return formatEnumOffsetLiteral(offset, reprNumKind)
    }
    if (offset == 0) return baseExpr
    val stepLit =
        when (reprNumKind) {
            NumericKind.U8, NumericKind.U16, NumericKind.U32 -> "${offset}u"
            NumericKind.U64 -> "${offset}uL"
            NumericKind.I64 -> "${offset}L"
            else -> "$offset"
        }
    val sumExpr =
        when (reprNumKind) {
            NumericKind.U8, NumericKind.U16 -> "(($baseExpr).toUInt() + $stepLit)"
            NumericKind.I8, NumericKind.I16 -> "(($baseExpr).toInt() + $stepLit)"
            else -> "(($baseExpr) + $stepLit)"
        }
    return when (reprNumKind) {
        NumericKind.U8 -> "$sumExpr.toUByte()"
        NumericKind.U16 -> "$sumExpr.toUShort()"
        NumericKind.I8 -> "$sumExpr.toByte()"
        NumericKind.I16 -> "$sumExpr.toShort()"
        else -> sumExpr
    }
}

internal fun KotlinJ2KEmitter.renderItemEnum(
    item: Item.Enum,
    attachedImpls: List<Item.Impl>,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val typeName = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$typeName"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val typeArgsUse = renderTypeArgumentsFromGenerics(item.generics)
    val selfTypeWithGenerics = typeName + typeArgsUse
    val prevEnumTypeParams = activeTypeParamNames
    activeTypeParamNames = activeTypeParamNames + collectGenericTypeParamNames(item.generics)
    val typeParams: String
    val superTraits: List<String>
    try {
        typeParams = renderTypeParameters(item.generics, selfTypeName = selfTypeWithGenerics)
        superTraits = renderDistinctSuperTraits(attachedImpls, selfTypeWithGenerics)
    } finally {
        activeTypeParamNames = prevEnumTypeParams
    }
    val variants = item.variants.toList().filterNot { isDocOnlyCfgAttribute(it.attrs) }
    if (useFinalAbiLayout && variants.isEmpty() && typeParams.isEmpty() && superTraits.isEmpty()) {
        sb.append("${indent}${vis}class $typeName")
        return sb.toString()
    }
    val isSimpleEnum = variants.isNotEmpty() && variants.all { it.fields is Fields.Unit }
    if (useFinalAbiLayout && isSimpleEnum && typeParams.isEmpty() && superTraits.isEmpty()) {
        val reprKotlinType = extractEnumReprKotlinType(item.attrs) ?: "UInt"
        val reprNumKind = resolveNumericKind(reprKotlinType) ?: NumericKind.U32
        val enumFilteredImpls =
            attachedImpls.filterNot { impl ->
                val traitIdent =
                    impl.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString()
                traitIdent == "Clone" || traitIdent == "Default"
            }
        val prevOverrideNames = currentStructOverrideMethodNames
        currentStructOverrideMethodNames = emptySet()
        val attachedMembers =
            try {
                renderAttachedImplMembers(selfTypeWithGenerics, item.generics, enumFilteredImpls, "$indent    ", isLocalClass = isLocalItem)
            } finally {
                currentStructOverrideMethodNames = prevOverrideNames
            }
        sb
            .append(indent)
            .append(vis)
            .append("enum class ")
            .append(typeName)
            .append("(public val value: ")
            .append(reprKotlinType)
            .append(") {\n")
        var baseDiscExpr: String? = null
        var stepOffset = 0
        val seenEntryNames = hashSetOf<String>()
        val entryLines = mutableListOf<String>()
        for (v in variants) {
            val rawVar = v.ident.toString().removePrefix("r#")
            val entryName =
                if (isScreamingSnakeIdent(rawVar)) {
                    rustConstIdentToKotlin(v.ident.toString())
                } else {
                    rustTypeIdentToKotlin(v.ident.toString())
                }
            if (!seenEntryNames.add(entryName)) continue
            val vTrivia = renderLeadingTrivia(v, "$indent    ")
            val vDoc = renderKDocFromAttributes(v.attrs, "$indent    ")
            val explicitExpr = v.discriminant?.expr
            val discArg =
                if (explicitExpr != null) {
                    val rendered = renderTypedNumericInitializer(explicitExpr, reprNumKind, "$indent    ", selfTypeName = selfTypeWithGenerics)
                    baseDiscExpr = rendered
                    stepOffset = 1
                    rendered
                } else {
                    val rendered = formatEnumSteppedDiscriminant(baseDiscExpr, stepOffset, reprNumKind)
                    stepOffset += 1
                    rendered
                }
            entryLines += "${vTrivia}${vDoc}$indent    $entryName($discArg)"
        }
        if (attachedMembers.isNotEmpty()) {
            sb.append(entryLines.joinToString(",\n")).append(";\n\n")
            sb.append(attachedMembers).append('\n')
        } else {
            sb.append(entryLines.joinToString(",\n")).append(",\n")
        }
        sb.append(indent).append("}")
        return sb.toString()
    }

    val prevOverrideNames = currentStructOverrideMethodNames
    currentStructOverrideMethodNames = collectLocalSupertraitMethodNames(superTraits)
    val attachedMembers =
        try {
            renderAttachedImplMembers(selfTypeWithGenerics, item.generics, attachedImpls, "$indent    ", isLocalClass = isLocalItem)
        } finally {
            currentStructOverrideMethodNames = prevOverrideNames
        }
    val hasDiscriminants = isSimpleEnum && typeParams.isEmpty() && variants.any { it.discriminant != null }
    val reprKotlinType = if (hasDiscriminants) (extractEnumReprKotlinType(item.attrs) ?: "Int") else null
    val reprNumKind = reprKotlinType?.let(this::resolveNumericKind)

    sb
        .append(indent)
        .append(vis)
        .append("sealed class ")
        .append(typeName)
        .append(typeParams)
    if (reprKotlinType != null) {
        sb.append("(public val value: ").append(reprKotlinType).append(')')
    }
    if (superTraits.isNotEmpty()) {
        sb.append(" : ").append(superTraits.joinToString(", "))
    }
    sb.append(" {\n")
    var prevVariantName: String? = null
    activeTypeParamNames = prevEnumTypeParams + collectGenericTypeParamNames(item.generics)
    try {
        for (v in variants) {
            val vName = rustTypeIdentToKotlin(v.ident.toString())
            val discArg =
                if (reprKotlinType != null) {
                    val explicitExpr = v.discriminant?.expr
                    if (explicitExpr != null) {
                        renderTypedNumericInitializer(explicitExpr, reprNumKind, "$indent    ", selfTypeName = selfTypeWithGenerics)
                    } else if (prevVariantName != null) {
                        val stepOne =
                            when (reprNumKind) {
                                NumericKind.U8, NumericKind.U16, NumericKind.U32 -> "1u"
                                NumericKind.U64 -> "1uL"
                                NumericKind.I64 -> "1L"
                                else -> "1"
                            }
                        val rawStep = "$prevVariantName.value + $stepOne"
                        when (reprNumKind) {
                            NumericKind.U8 -> "($rawStep).toUByte()"
                            NumericKind.U16 -> "($rawStep).toUShort()"
                            NumericKind.I8 -> "($rawStep).toByte()"
                            NumericKind.I16 -> "($rawStep).toShort()"
                            else -> rawStep
                        }
                    } else {
                        defaultInitializerForType(reprKotlinType)
                    }
                } else {
                    null
                }
            sb.append(renderEnumVariantClass(v, typeName, typeParams, typeArgsUse, "$indent    ", discArg))
            sb.append('\n')
            prevVariantName = vName
        }
    } finally {
        activeTypeParamNames = prevEnumTypeParams
    }
    if (attachedMembers.isNotEmpty()) {
        sb.append('\n').append(attachedMembers).append('\n')
    }
    sb.append(indent).append("}")
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderEnumVariantClass(
    variant: Variant,
    enumName: String,
    enumTypeParams: String,
    enumTypeArgsUse: String,
    indent: String,
    discriminantArg: String? = null,
): String {
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(variant, indent))
    sb.append(renderKDocFromAttributes(variant.attrs, indent))
    val vName = rustTypeIdentToKotlin(variant.ident.toString())
    val enumSelfType = enumName + enumTypeArgsUse
    val superCallArgs = discriminantArg.orEmpty()
    when (val fields = variant.fields) {
        is Fields.Unit -> {
            if (enumTypeParams.isEmpty()) {
                sb
                    .append(indent)
                    .append("public data object ")
                    .append(vName)
                    .append(" : ")
                    .append(enumName)
                    .append('(')
                    .append(superCallArgs)
                    .append(')')
            } else {
                sb
                    .append(indent)
                    .append("public class ")
                    .append(vName)
                    .append(enumTypeParams)
                    .append("()")
                    .append(" : ")
                    .append(enumName)
                    .append(enumTypeArgsUse)
                    .append('(')
                    .append(superCallArgs)
                    .append(')')
            }
        }
        is Fields.Unnamed -> {
            val list = fields.fields.unnamed.toList()
            val params =
                list.mapIndexed { idx, f ->
                    val pName = tupleFieldPropertyName(idx, list.size)
                    val pType = renderType(f.ty, selfTypeName = enumSelfType)
                    "public var $pName: $pType"
                }
            sb
                .append(indent)
                .append("public data class ")
                .append(vName)
                .append(enumTypeParams)
                .append("(")
                .append(params.joinToString(", "))
                .append(")")
                .append(" : ")
                .append(enumName)
                .append(enumTypeArgsUse)
                .append("()")
        }
        is Fields.Named -> {
            val list = fields.fields.named.toList()
            val seenFieldNames = hashSetOf<String>()
            val params =
                list.mapIndexedNotNull { idx, f ->
                    if (isDocOnlyCfgAttribute(f.attrs)) return@mapIndexedNotNull null
                    val pName = rustFieldIdentToKotlin(f.ident?.toString() ?: "field$idx")
                    if (!seenFieldNames.add(pName)) return@mapIndexedNotNull null
                    val pType = renderType(f.ty, selfTypeName = enumSelfType)
                    "public var $pName: $pType"
                }
            sb
                .append(indent)
                .append("public data class ")
                .append(vName)
                .append(enumTypeParams)
                .append("(")
                .append(params.joinToString(", "))
                .append(")")
                .append(" : ")
                .append(enumName)
                .append(enumTypeArgsUse)
                .append("()")
        }
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.pickUnusedTraitSelfParamName(generics: Generics, assocTypeNames: List<String>): String {
    val existing = collectGenericTypeParamNames(generics) + assocTypeNames
    for (candidate in listOf("T", "SelfType", "S", "Target")) {
        if (candidate !in existing) return candidate
    }
    return "SelfType0"
}

internal fun KotlinJ2KEmitter.renderItemTrait(
    item: Item.Trait,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val traitName = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$traitName"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val assocTypeNames = inspector.traitDefaultlessAssocTypes[traitName].orEmpty()
    val selfParamName =
        if (traitName in inspector.selfParameterizedTraits) {
            pickUnusedTraitSelfParamName(item.generics, assocTypeNames)
        } else {
            null
        }
    val memberSelfType = selfParamName ?: (traitName + renderTypeArgumentsFromGenerics(item.generics))
    val prevTraitAssoc = currentTraitAssocParamNames
    val prevTraitTypeParams = activeTypeParamNames
    currentTraitAssocParamNames = assocTypeNames.toSet()
    activeTypeParamNames =
        prevTraitTypeParams +
        collectGenericTypeParamNames(item.generics) +
        setOfNotNull(selfParamName) +
        assocTypeNames
    return try {
        val baseTypeParams =
            renderTypeParameters(item.generics, selfTypeName = memberSelfType)
                .removePrefix("<")
                .removeSuffix(">")
        val combinedTraitParams =
            buildList {
                if (baseTypeParams.isNotEmpty()) add(baseTypeParams)
                if (selfParamName != null) add(selfParamName)
                addAll(assocTypeNames)
            }
        val typeParams =
            if (combinedTraitParams.isEmpty()) {
                ""
            } else {
                "<${combinedTraitParams.joinToString(", ")}>"
            }
        val superBounds = item.supertraits.toList().mapNotNull { renderTypeParamBound(it, selfTypeName = memberSelfType) }
        val whereClause = renderWhereClause(item.generics.whereClause, selfTypeName = memberSelfType, ownerGenerics = item.generics)

        sb
            .append(indent)
            .append(vis)
            .append("interface ")
            .append(traitName)
            .append(typeParams)
        if (superBounds.isNotEmpty()) {
            sb.append(" : ").append(superBounds.joinToString(", "))
        }
        sb.append(whereClause)

        val traitSignatures = linkedMapOf<String, Int>()
        val members = mutableListOf<String>()
        for (tItem in item.items) {
            if (traitItemHasDocOnlyCfg(tItem)) continue
            when (tItem) {
                is TraitItem.Const -> {
                    val cDoc = renderKDocFromAttributes(tItem.attrs, "$indent    ")
                    val cName = disambiguateName(rustConstIdentToKotlin(tItem.ident.toString()), traitSignatures)
                    val cType = renderType(tItem.ty, selfTypeName = memberSelfType)
                    val defaultExpr = tItem.default?.expr
                    if (defaultExpr != null) {
                        val renderedExpr =
                            renderTypedNumericInitializer(
                                defaultExpr,
                                resolveNumericKind(cType),
                                "$indent    ",
                                selfTypeName = memberSelfType,
                            )
                        members += "${cDoc}$indent    public val $cName: $cType\n$indent        get() = $renderedExpr"
                    } else {
                        members += "${cDoc}$indent    public val $cName: $cType"
                    }
                }
                is TraitItem.Fn -> {
                    val prevClosureLabel = currentClosureLabel
                    val prevEnclosingRet = currentEnclosingReturnRenderedType
                    val prevMutableListLocals = currentMutableListLocals
                    val prevDequeLocals = currentDequeLocals
                    val prevIterableLocals = currentIterableLocals
                    val prevTypedLocals = currentTypedLocals
                    val prevWitnessedBounds = currentWitnessedTraitBounds
                    val prevNullableLocals = currentNullableLocals
                    val prevSubst = substitutedTypeParamTypes
                    val prevTraitFnTypeParams = activeTypeParamNames
                    val prevWitnesses = currentTypeParamWitnessCalls
                    currentClosureLabel = null
                    currentEnclosingReturnRenderedType = null
                    currentMutableListLocals = emptySet()
                    currentDequeLocals = emptySet()
                    currentIterableLocals = emptySet()
                    currentTypedLocals = emptyMap()
                    currentWitnessedTraitBounds = emptyMap()
                    currentNullableLocals = emptySet()
                    val traitFnGenerics = tItem.sig.generics
                    val traitFnParams = traitFnGenerics.params.toList()
                    val fnSubsts = extractFnTraitSubstitutions(traitFnParams, traitFnGenerics.whereClause, memberSelfType)
                    if (fnSubsts.isNotEmpty()) {
                        substitutedTypeParamTypes = prevSubst + fnSubsts
                    }
                    activeTypeParamNames = (prevTraitFnTypeParams + collectGenericTypeParamNames(tItem.sig.generics)) - substitutedTypeParamTypes.keys
                    scopeBuilder.enterDef()
                    try {
                        val sigHeader =
                            renderFunctionSignature(
                                sig = tItem.sig,
                                visPrefix = "public ",
                                openPrefix = "",
                                selfTypeName = memberSelfType,
                                ownerGenerics = item.generics,
                                scopeSignatures = traitSignatures,
                                isInterfaceMethod = true,
                                fnBlock = tItem.default,
                            )
                        if (sigHeader != null) {
                            val fnDoc = renderKDocFromAttributes(tItem.attrs, "$indent    ")
                            val defaultBlock = tItem.default
                            if (defaultBlock != null) {
                                val retType = extractReturnSynType(tItem.sig.output)
                                val shadows = computeReassignedParamShadows(tItem.sig, defaultBlock, "$indent        ")
                                val body =
                                    renderBlock(
                                        defaultBlock,
                                        "$indent    ",
                                        selfTypeName = memberSelfType,
                                        returnType = retType,
                                        leadingInjectedLines = shadows,
                                    )
                                members += "${fnDoc}$indent    $sigHeader $body"
                            } else {
                                members += "${fnDoc}$indent    $sigHeader"
                            }
                        }
                    } finally {
                        scopeBuilder.exitDef()
                        currentClosureLabel = prevClosureLabel
                        currentEnclosingReturnRenderedType = prevEnclosingRet
                        currentMutableListLocals = prevMutableListLocals
                        currentDequeLocals = prevDequeLocals
                        currentIterableLocals = prevIterableLocals
                        currentTypedLocals = prevTypedLocals
                        currentWitnessedTraitBounds = prevWitnessedBounds
                        currentNullableLocals = prevNullableLocals
                        substitutedTypeParamTypes = prevSubst
                        activeTypeParamNames = prevTraitFnTypeParams
                        currentTypeParamWitnessCalls = prevWitnesses
                    }
                }
                is TraitItem.AssocType -> {}
                is TraitItem.Macro -> {
                    members +=
                        renderImplOrTraitMacroMembers(
                            mac = tItem.mac,
                            indent = "$indent    ",
                            selfTypeName = memberSelfType,
                            ownerGenerics = item.generics,
                            scopeSignatures = traitSignatures,
                            asTopLevelExtension = false,
                            isInterface = true,
                        )
                }
                is TraitItem.Verbatim -> {}
            }
        }
        val iterSuperSpec = extractIteratorSupertraitSpec(item.supertraits.toList(), memberSelfType)
        if (iterSuperSpec != null) {
            if ("next()" !in traitSignatures) {
                traitSignatures["next()"] = 1
                members += "$indent    public fun next(): ${iterSuperSpec.itemType}?"
            }
            if (iterSuperSpec.hasDoubleEnded && "nextBack()" !in traitSignatures) {
                traitSignatures["nextBack()"] = 1
                members += "$indent    public fun nextBack(): ${iterSuperSpec.itemType}?"
            }
            if (iterSuperSpec.hasExactSize && "len()" !in traitSignatures) {
                traitSignatures["len()"] = 1
                members += "$indent    public fun len(): Int"
            }
        }

        if (members.isNotEmpty()) {
            sb.append(" {\n")
            sb.append(members.joinToString("\n\n"))
            sb.append('\n').append(indent).append("}")
        }
        sb.toString()
    } finally {
        currentTraitAssocParamNames = prevTraitAssoc
        activeTypeParamNames = prevTraitTypeParams
    }
}

internal fun KotlinJ2KEmitter.renderItemTraitAlias(
    item: Item.TraitAlias,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val name = rustTypeIdentToKotlin(item.ident.toString())
    val sigKey = "type:$name"
    val seenCount = scopeSignatures[sigKey] ?: 0
    scopeSignatures[sigKey] = seenCount + 1
    if (seenCount > 0) {
        return ""
    }
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val typeParams = renderTypeParameters(item.generics, selfTypeName = name)
    val bounds = item.bounds.toList().mapNotNull { renderTypeParamBound(it, selfTypeName = name) }
    sb
        .append(indent)
        .append(vis)
        .append("interface ")
        .append(name)
        .append(typeParams)
    if (bounds.isNotEmpty()) {
        sb.append(" : ").append(bounds.joinToString(", "))
    }
    return sb.toString()
}

internal fun KotlinJ2KEmitter.extractFnTraitClosureReceiver(item: Item.Impl): Pair<String, String>? {
    val selfPath = unwrapSynType(item.selfType) as? SynType.Path ?: return null
    if (selfPath.qself != null) return null
    val selfSegs = selfPath.path.segments.toList()
    if (selfSegs.size != 1 || selfSegs[0].arguments !is PathArguments.None) return null
    val selfParamName = rustTypeIdentToKotlin(selfSegs[0].ident.toString())
    val ownerTypeParams = collectGenericTypeParamNames(item.generics)
    if (selfParamName !in ownerTypeParams) return null

    val candidateBounds = mutableListOf<TypeParamBound>()
    for (gp in item.generics.params.toList()) {
        if (gp is GenericParam.TypeParam && rustTypeIdentToKotlin(gp.ident.toString()) == selfParamName) {
            candidateBounds.addAll(gp.bounds.toList())
        }
    }
    val wherePreds = item.generics.whereClause?.predicates
    for (pred in wherePreds?.toList().orEmpty()) {
        if (pred is WherePredicate.TypePredicate) {
            val predTy = unwrapSynType(pred.boundedTy) as? SynType.Path
            if (predTy != null && predTy.qself == null && predTy.path.segments.len() == 1) {
                val firstSeg = predTy.path.segments.first()
                val rawPred = firstSeg?.ident?.toString()
                val predIdent = rawPred?.let(::rustTypeIdentToKotlin)
                if (predIdent == selfParamName) {
                    candidateBounds.addAll(pred.bounds.toList())
                }
            }
        }
    }
    for (bound in candidateBounds) {
        if (bound is TypeParamBound.Trait) {
            val lastSeg = bound.path.segments.last() ?: continue
            val boundIdent = lastSeg.ident.toString()
            if ((boundIdent == "Fn" || boundIdent == "FnMut" || boundIdent == "FnOnce") &&
                lastSeg.arguments is PathArguments.Parenthesized
            ) {
                val fnType = renderPathAsType(bound.path, selfTypeName = null)
                return selfParamName to fnType
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.renderStandaloneItemImpl(
    item: Item.Impl,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
    isLocalItem: Boolean = false,
): String {
    val implComments =
        (renderLeadingTrivia(item, indent) + renderKDocFromAttributes(item.attrs, indent))
            .trimEnd()
    val closureRecv = extractFnTraitClosureReceiver(item)
    val prevSubst = substitutedTypeParamTypes
    val prevStandaloneTraitPath = currentStandaloneImplTraitPath
    val prevStandaloneSelfTy = currentStandaloneImplSelfSynType
    val prevAssocTypes = currentImplAssocTypes
    if (closureRecv != null) {
        substitutedTypeParamTypes = prevSubst + (closureRecv.first to closureRecv.second)
    }
    currentStandaloneImplTraitPath = item.traitPath?.path
    currentStandaloneImplSelfSynType = item.selfType
    currentImplAssocTypes =
        buildMap {
            putAll(prevAssocTypes)
            for (implItem in item.items) {
                if (implItem is ImplItem.AssocType) {
                    put(implItem.ident.toString(), implItem.ty)
                }
            }
        }
    return try {
        val selfTypeRendered =
            if (closureRecv != null) {
                "(${closureRecv.second})"
            } else {
                renderType(item.selfType, selfTypeName = null)
            }
        val scopeSigs = scopeSignatures
        val renderedMembers = mutableListOf<String>()
        for (implItem in item.items) {
            if (implItemHasDocOnlyCfg(implItem)) continue
            when (implItem) {
                is ImplItem.Const -> {
                    renderedMembers +=
                        renderImplItemConst(
                            item = implItem,
                            indent = indent,
                            selfTypeName = selfTypeRendered,
                            scopeSignatures = scopeSigs,
                            isLocalItem = isLocalItem,
                        )
                }
                is ImplItem.Fn -> {
                    val rendered =
                        renderImplItemFn(
                            item = implItem,
                            indent = indent,
                            selfTypeName = selfTypeRendered,
                            ownerGenerics = item.generics,
                            implGenerics = item.generics,
                            scopeSignatures = scopeSigs,
                            asTopLevelExtension = true,
                            isLocalItem = isLocalItem,
                        )
                    if (rendered.isNotBlank()) renderedMembers += rendered
                }
                is ImplItem.AssocType -> {}
                is ImplItem.Macro -> {
                    renderedMembers +=
                        renderImplOrTraitMacroMembers(
                            mac = implItem.mac,
                            indent = indent,
                            selfTypeName = selfTypeRendered,
                            ownerGenerics = item.generics,
                            scopeSignatures = scopeSigs,
                            asTopLevelExtension = true,
                            isInterface = false,
                            isLocalItem = isLocalItem,
                        )
                }
                is ImplItem.Verbatim -> {}
            }
        }
        if (implComments.isNotBlank()) {
            if (renderedMembers.isNotEmpty()) {
                renderedMembers[0] = "$implComments\n${renderedMembers[0]}"
            } else {
                renderedMembers += implComments
            }
        }
        renderedMembers.joinToString("\n\n")
    } finally {
        currentImplAssocTypes = prevAssocTypes
        currentStandaloneImplTraitPath = prevStandaloneTraitPath
        currentStandaloneImplSelfSynType = prevStandaloneSelfTy
        substitutedTypeParamTypes = prevSubst
    }
}

internal fun KotlinJ2KEmitter.renderImplItemConst(
    item: ImplItem.Const,
    indent: String,
    selfTypeName: String?,
    scopeSignatures: MutableMap<String, Int>,
    isLocalItem: Boolean = false,
): String {
    val sb = StringBuilder()
    sb.append(renderLeadingTrivia(item, indent))
    sb.append(renderKDocFromAttributes(item.attrs, indent))
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val name = disambiguateName(rustConstIdentToKotlin(item.ident.toString()), scopeSignatures)
    val ty = renderType(item.ty, selfTypeName)
    val numKind = resolveNumericKind(ty)
    val expr = renderTypedNumericInitializer(item.expr, numKind, indent, selfTypeName)
    sb
        .append(indent)
        .append(vis)
        .append("val ")
        .append(name)
        .append(": ")
        .append(ty)
        .append(" = ")
        .append(expr)
    return sb.toString()
}

internal fun KotlinJ2KEmitter.renderImplItemFn(
    item: ImplItem.Fn,
    indent: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    implGenerics: Generics? = null,
    scopeSignatures: MutableMap<String, Int>,
    asTopLevelExtension: Boolean = false,
    isCompanionMember: Boolean = false,
    isLocalItem: Boolean = false,
): String {
    val vis = if (isLocalItem) "" else renderVisibility(item.vis)
    val useFinalAbiLayout = headerContext.rawAbiMode || headerContext.finalClasses
    val openMod =
        if (!useFinalAbiLayout && !asTopLevelExtension && !isCompanionMember && !isLocalItem && indent.isNotEmpty()) {
            "open "
        } else {
            ""
        }
    val prevClosureLabel = currentClosureLabel
    val prevEnclosingFn = currentEnclosingFunctionName
    val prevEnclosingRet = currentEnclosingReturnRenderedType
    val prevMutableListLocals = currentMutableListLocals
    val prevDequeLocals = currentDequeLocals
    val prevIterableLocals = currentIterableLocals
    val prevTypedLocals = currentTypedLocals
    val prevWitnessedBounds = currentWitnessedTraitBounds
    val prevHasherState = currentHasherStateParam
    val prevNullableLocals = currentNullableLocals
    val prevTypeParams = activeTypeParamNames
    val prevSubst = substitutedTypeParamTypes
    val prevWitnesses = currentTypeParamWitnessCalls
    currentClosureLabel = null
    currentEnclosingFunctionName = rustFunctionIdentToKotlin(item.sig.ident.toString())
    currentEnclosingReturnRenderedType = null
    currentMutableListLocals = emptySet()
    currentDequeLocals = emptySet()
    currentIterableLocals = emptySet()
    currentTypedLocals = emptyMap()
    currentWitnessedTraitBounds = emptyMap()
    currentHasherStateParam = null
    currentNullableLocals = emptySet()
    val implFnSubsts =
        buildMap {
            if (implGenerics != null) {
                val implGenParams = implGenerics.params.toList()
                putAll(extractFnTraitSubstitutions(implGenParams, implGenerics.whereClause, selfTypeName))
            }
            val itemGenerics = item.sig.generics
            val itemGenParams = itemGenerics.params.toList()
            putAll(extractFnTraitSubstitutions(itemGenParams, itemGenerics.whereClause, selfTypeName))
        }
    if (implFnSubsts.isNotEmpty()) {
        substitutedTypeParamTypes = prevSubst + implFnSubsts
    }
    val combinedActiveParams =
        buildSet {
            addAll(activeTypeParamNames)
            addAll(collectGenericTypeParamNames(ownerGenerics))
            addAll(collectGenericTypeParamNames(implGenerics))
            addAll(collectGenericTypeParamNames(item.sig.generics))
        }
    activeTypeParamNames = combinedActiveParams - substitutedTypeParamTypes.keys
    scopeBuilder.enterDef()
    try {
        val activeTraitLast =
            (currentAttachedImplTraitPath ?: currentStandaloneImplTraitPath)
                ?.segments
                ?.last()
                ?.ident
                ?.toString()
        val inputsList = item.sig.inputs.toList()
        if (activeTraitLast == "Hash" &&
            item.sig.ident.toString() == "hash" &&
            inputsList.size == 2 &&
            inputsList[0] is FnArg.Receiver &&
            inputsList[1] is FnArg.Typed &&
            !asTopLevelExtension &&
            !isCompanionMember
        ) {
            val hashSigKey = "hashCode()"
            val seen = scopeSignatures[hashSigKey] ?: 0
            scopeSignatures[hashSigKey] = seen + 1
            if (seen > 0) return ""
            val stateArg = inputsList[1] as FnArg.Typed
            val rawState = extractRawParamIdent(stateArg.patType.pat) ?: "state"
            val stateKt =
                scopeBuilder
                    .bindLocal(
                        rawName = rawState,
                        baseKotlinName = rustValueIdentToKotlin(rawState),
                        span = stateArg.patType.pat.span(),
                        assignCount = AssignCount.Any,
                    ).kotlinName
            currentHasherStateParam = stateKt
            currentEnclosingReturnRenderedType = "Int"
            val sb = StringBuilder()
            sb.append(renderLeadingTrivia(item, indent))
            sb.append(renderKDocFromAttributes(item.attrs, indent))
            val body =
                renderBlock(
                    item.block,
                    indent,
                    selfTypeName = selfTypeName,
                    returnType = null,
                    leadingInjectedLines = listOf("$indent    var $stateKt = 0"),
                    trailingInjectedLines = listOf("$indent    return $stateKt"),
                )
            sb.append(indent).append("${vis}override fun hashCode(): Int ").append(body)
            return sb.toString()
        }
        val sigHeader =
            renderFunctionSignature(
                sig = item.sig,
                visPrefix = vis,
                openPrefix = openMod,
                selfTypeName = selfTypeName,
                ownerGenerics = ownerGenerics,
                implGenerics = implGenerics,
                scopeSignatures = scopeSignatures,
                isInterfaceMethod = false,
                asTopLevelExtension = asTopLevelExtension,
                isCompanionMember = isCompanionMember,
                fnBlock = item.block,
            ) ?: return ""
        val sb = StringBuilder()
        sb.append(renderLeadingTrivia(item, indent))
        sb.append(renderKDocFromAttributes(item.attrs, indent))
        if (!isLocalItem && hasTestAttribute(item.attrs)) {
            sb.append(indent).append("@Test\n")
        }
        val retType = extractReturnSynType(item.sig.output)
        val shadows = computeReassignedParamShadows(item.sig, item.block, "$indent    ")
        val body =
            renderBlock(
                item.block,
                indent,
                selfTypeName = selfTypeName,
                returnType = retType,
                leadingInjectedLines = shadows,
            )
        sb
            .append(indent)
            .append(sigHeader)
            .append(" ")
            .append(body)
        return sb.toString()
    } finally {
        scopeBuilder.exitDef()
        currentClosureLabel = prevClosureLabel
        currentEnclosingFunctionName = prevEnclosingFn
        currentEnclosingReturnRenderedType = prevEnclosingRet
        currentMutableListLocals = prevMutableListLocals
        currentDequeLocals = prevDequeLocals
        currentIterableLocals = prevIterableLocals
        currentTypedLocals = prevTypedLocals
        currentWitnessedTraitBounds = prevWitnessedBounds
        currentHasherStateParam = prevHasherState
        currentNullableLocals = prevNullableLocals
        activeTypeParamNames = prevTypeParams
        substitutedTypeParamTypes = prevSubst
        currentTypeParamWitnessCalls = prevWitnesses
    }
}
