package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Block
import io.github.kotlinmania.syn.Expr
import io.github.kotlinmania.syn.Fields
import io.github.kotlinmania.syn.File
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.ImplItem
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.PathArguments
import io.github.kotlinmania.syn.ReturnType
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.TraitItem
import io.github.kotlinmania.syn.UseTree
import io.github.kotlinmania.syn.Variant
import io.github.kotlinmania.syn.gen.Visit

internal data class AssocTypeDecl(
    val ty: SynType,
    val generics: Generics,
)

internal fun itemHasDocOnlyCfg(item: Item): Boolean =
    when (item) {
        is Item.Const -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Enum -> isDocOnlyCfgAttribute(item.attrs)
        is Item.ExternCrate -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Fn -> isDocOnlyCfgAttribute(item.attrs)
        is Item.ForeignMod -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Impl -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Macro -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Mod -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Static -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Struct -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Trait -> isDocOnlyCfgAttribute(item.attrs)
        is Item.TraitAlias -> isDocOnlyCfgAttribute(item.attrs)
        is Item.ItemType -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Union -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Use -> isDocOnlyCfgAttribute(item.attrs)
        is Item.Verbatim -> false
    }

internal fun implItemHasDocOnlyCfg(item: ImplItem): Boolean =
    when (item) {
        is ImplItem.Const -> isDocOnlyCfgAttribute(item.attrs)
        is ImplItem.Fn -> isDocOnlyCfgAttribute(item.attrs)
        is ImplItem.AssocType -> isDocOnlyCfgAttribute(item.attrs)
        is ImplItem.Macro -> isDocOnlyCfgAttribute(item.attrs)
        is ImplItem.Verbatim -> false
    }

internal fun traitItemHasDocOnlyCfg(item: TraitItem): Boolean =
    when (item) {
        is TraitItem.Const -> isDocOnlyCfgAttribute(item.attrs)
        is TraitItem.Fn -> isDocOnlyCfgAttribute(item.attrs)
        is TraitItem.AssocType -> isDocOnlyCfgAttribute(item.attrs)
        is TraitItem.Macro -> isDocOnlyCfgAttribute(item.attrs)
        is TraitItem.Verbatim -> false
    }

internal fun foreignItemHasDocOnlyCfg(item: ForeignItem): Boolean =
    when (item) {
        is ForeignItem.Fn -> isDocOnlyCfgAttribute(item.attrs)
        is ForeignItem.Static -> isDocOnlyCfgAttribute(item.attrs)
        is ForeignItem.ItemType -> isDocOnlyCfgAttribute(item.attrs)
        is ForeignItem.Macro -> isDocOnlyCfgAttribute(item.attrs)
        is ForeignItem.Verbatim -> false
    }

internal enum class NumericKind {
    I8,
    I16,
    I32,
    I64,
    I128,
    U8,
    U16,
    U32,
    U64,
    U128,
    F32,
    F64,
}

internal fun unwrapSynType(ty: SynType): SynType =
    when (ty) {
        is SynType.Group -> unwrapSynType(ty.elem)
        is SynType.Paren -> unwrapSynType(ty.elem)
        else -> ty
    }

internal fun isExternalIdentityTypeReexportAlias(
    i: Item.ItemType,
    selfCrateAliases: Set<String>,
): Boolean {
    val genericParams = i.generics.params.toList()
    if (genericParams.isNotEmpty()) return false
    val rhsPath = unwrapSynType(i.ty) as? SynType.Path ?: return false
    if (rhsPath.qself != null) return false
    val segs = rhsPath.path.segments.toList()
    if (segs.size < 2) return false
    val lastSeg = segs.last()
    if (lastSeg.arguments !is PathArguments.None) return false
    val firstIdent = segs.first().ident.toString()
    val firstRaw = firstIdent.removePrefix("r#")
    if (firstRaw == "crate" || firstRaw == "super" || firstRaw == "self" || firstRaw in selfCrateAliases) {
        return false
    }
    val lhsKt = rustTypeIdentToKotlin(i.ident.toString())
    val rhsKt = rustTypeIdentToKotlin(lastSeg.ident.toString())
    return lhsKt == rhsKt
}

internal class CrateSymbolInspector(
    private val selfCrateAliases: Set<String> = emptySet(),
) : Visit() {
    val declaredTypeNames: MutableSet<String> = linkedSetOf()
    val attachableTypeNames: MutableSet<String> = linkedSetOf()
    val unitStructNames: MutableSet<String> = linkedSetOf()
    val associatedTypeDecls: MutableMap<String, AssocTypeDecl> = linkedMapOf()
    val typeAliasTargets: MutableMap<String, SynType> = linkedMapOf()
    val declaredConstNames: MutableMap<String, String> = linkedMapOf()
    val declaredConstTypes: MutableMap<String, SynType> = linkedMapOf()
    val tupleStructArityByType: MutableMap<String, Int> = linkedMapOf()
    val tupleEnumVariantArity: MutableMap<Pair<String, String>, Int> = linkedMapOf()
    val enumTypeParamsByVariant: MutableMap<String, List<String>> = linkedMapOf()
    val enumVariantFieldTypes: MutableMap<String, List<SynType>> = linkedMapOf()
    val structFieldNamesByType: MutableMap<String, List<String>> = linkedMapOf()
    val structFieldSynTypesByType: MutableMap<String, Map<String, SynType>> = linkedMapOf()
    val declaredTraitsByName: MutableMap<String, Item.Trait> = linkedMapOf()
    val instanceMethodsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val companionMethodsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val nullableFieldsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val dequeIterFieldsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val vecFieldsByType: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val selfParameterizedTraits: MutableSet<String> = linkedSetOf()
    val traitDefaultlessAssocTypes: MutableMap<String, List<String>> = linkedMapOf()
    val localInlineModuleNames: MutableSet<String> = linkedSetOf()
    val localExternalModuleNames: MutableSet<String> = linkedSetOf()
    val declaredTopLevelFnRawNames: MutableSet<String> = linkedSetOf()
    val topLevelFnReturnSynTypes: MutableMap<String, SynType> = linkedMapOf()
    val implMethodReturnSynTypes: MutableMap<Pair<String, String>, SynType> = linkedMapOf()
    val referencedUnqualifiedTypeIdents: MutableSet<String> = linkedSetOf()
    val referencedPathPrefixTypeIdents: MutableSet<String> = linkedSetOf()
    val ffiReferencedTypeNames: MutableSet<String> = linkedSetOf()
    val allUseItems: MutableList<Item.Use> = mutableListOf()
    val enumVariantSimpleNames: MutableSet<String> = linkedSetOf()
    val importedEnumVariantSimpleNames: MutableSet<String> = linkedSetOf()
    var hasImportedEnumGlob: Boolean = false
    var hasLocalNoneVariant: Boolean = false
    private var blockDepth: Int = 0

    private fun recordFfiReferencedType(ty: SynType) {
        val collector =
            object : Visit() {
                override fun visitPath(p: Path) {
                    val segs = p.segments.toList()
                    if (segs.isNotEmpty()) {
                        val raw =
                            segs
                                .last()
                                .ident
                                .toString()
                                .removePrefix("r#")
                        ffiReferencedTypeNames += raw
                        ffiReferencedTypeNames += rustTypeIdentToKotlin(raw)
                    }
                    super.visitPath(p)
                }
            }
        collector.visitType(ty)
    }

    fun closeFfiReferencedTypeAliases() {
        var changed = true
        var rounds = 0
        while (changed && rounds < 12) {
            changed = false
            rounds++
            for ((aliasName, targetTy) in typeAliasTargets) {
                if (aliasName in ffiReferencedTypeNames) {
                    val before = ffiReferencedTypeNames.size
                    recordFfiReferencedType(targetTy)
                    if (ffiReferencedTypeNames.size != before) {
                        changed = true
                    }
                }
            }
        }
    }

    override fun visitType(t: SynType) {
        if (t is SynType.FnPtr && t.abi != null) {
            for (arg in t.inputs.toList()) {
                recordFfiReferencedType(arg.ty)
            }
            val out = t.output
            if (out is ReturnType.TypeReturn) {
                recordFfiReferencedType(out.ty)
            }
        }
        if (t is SynType.Path && t.qself == null) {
            val segs = t.path.segments.toList()
            if (segs.size == 1) {
                referencedUnqualifiedTypeIdents += segs[0].ident.toString().removePrefix("r#")
            } else if (segs.size >= 2) {
                referencedPathPrefixTypeIdents += segs[0].ident.toString().removePrefix("r#")
            }
        }
        super.visitType(t)
    }

    override fun visitExprPath(e: Expr.Path) {
        if (e.qself == null) {
            val segs = e.path.segments.toList()
            if (segs.size >= 2) {
                referencedPathPrefixTypeIdents += segs[0].ident.toString().removePrefix("r#")
            }
        }
        super.visitExprPath(e)
    }

    override fun visitItem(i: Item) {
        if (itemHasDocOnlyCfg(i)) return
        if (i is Item.ForeignMod) {
            for (foreign in i.items) {
                if (foreignItemHasDocOnlyCfg(foreign)) continue
                when (foreign) {
                    is ForeignItem.Fn -> {
                        for (arg in foreign.sig.inputs.toList()) {
                            if (arg is FnArg.Typed) {
                                recordFfiReferencedType(arg.patType.ty)
                            }
                        }
                        val out = foreign.sig.output
                        if (out is ReturnType.TypeReturn) {
                            recordFfiReferencedType(out.ty)
                        }
                    }
                    is ForeignItem.Static -> recordFfiReferencedType(foreign.ty)
                    else -> {}
                }
            }
        }
        super.visitItem(i)
    }

    override fun visitItemFn(i: Item.Fn) {
        if (blockDepth == 0) {
            val rawIdent = i.sig.ident.toString()
            declaredTopLevelFnRawNames += rawIdent.removePrefix("r#")
            val genParams = i.sig.generics.params
            if (genParams.isEmpty()) {
                val out = i.sig.output
                if (out is ReturnType.TypeReturn) {
                    val ktFn = rustFunctionIdentToKotlin(rawIdent)
                    if (ktFn !in topLevelFnReturnSynTypes) {
                        topLevelFnReturnSynTypes[ktFn] = out.ty
                    }
                }
            }
        }
        super.visitItemFn(i)
    }

    override fun visitBlock(b: Block) {
        blockDepth++
        super.visitBlock(b)
        blockDepth--
    }

    override fun visitItemUse(i: Item.Use) {
        allUseItems += i
        if (useTreeImportsNonOptionNone(i.tree, emptyList())) {
            hasLocalNoneVariant = true
        }
        collectImportedEnumVariants(i.tree, emptyList())
        super.visitItemUse(i)
    }

    private fun collectImportedEnumVariants(tree: UseTree, prefix: List<String>) {
        when (tree) {
            is UseTree.Path -> {
                val child = tree.tree
                if (child != null) {
                    collectImportedEnumVariants(child, prefix + tree.ident.toString().removePrefix("r#"))
                }
            }
            is UseTree.Name -> {
                val parent = prefix.lastOrNull()
                if (parent != null && parent.firstOrNull()?.isUpperCase() == true) {
                    val seg = tree.ident.toString().removePrefix("r#")
                    val rename = tree.rename
                    val bound = if (rename != null) rename.ident.toString().removePrefix("r#") else seg
                    if (bound != "self" && bound.firstOrNull()?.isUpperCase() == true) {
                        importedEnumVariantSimpleNames += rustTypeIdentToKotlin(bound)
                    }
                }
            }
            is UseTree.Glob -> {
                val parent = prefix.lastOrNull()
                if (parent != null && parent.firstOrNull()?.isUpperCase() == true) {
                    hasImportedEnumGlob = true
                }
            }
            is UseTree.Group -> tree.items.toList().forEach { collectImportedEnumVariants(it, prefix) }
        }
    }

    private fun useTreeImportsNonOptionNone(tree: UseTree, prefix: List<String>): Boolean =
        when (tree) {
            is UseTree.Path -> {
                val child = tree.tree
                child != null && useTreeImportsNonOptionNone(child, prefix + tree.ident.toString())
            }
            is UseTree.Name -> {
                val seg = tree.ident.toString()
                val renameIdent = tree.rename?.ident?.toString()
                val bound = renameIdent?.removePrefix("r#") ?: seg
                bound == "None" && prefix.lastOrNull() != "Option"
            }
            is UseTree.Glob -> false
            is UseTree.Group -> tree.items.toList().any { useTreeImportsNonOptionNone(it, prefix) }
        }

    override fun visitItemConst(i: Item.Const) {
        val raw = i.ident.toString().removePrefix("r#")
        if (raw != "_") {
            val ktName = rustConstIdentToKotlin(raw)
            if (blockDepth == 0) {
                declaredConstNames[raw] = ktName
                declaredConstTypes[ktName] = i.ty
            }
        }
        super.visitItemConst(i)
    }

    override fun visitItemStatic(i: Item.Static) {
        val raw = i.ident.toString().removePrefix("r#")
        if (raw != "_") {
            val ktName = rustConstIdentToKotlin(raw)
            if (blockDepth == 0) {
                declaredConstNames[raw] = ktName
                declaredConstTypes[ktName] = i.ty
            }
        }
        super.visitItemStatic(i)
    }

    override fun visitForeignItemStatic(i: ForeignItem.Static) {
        if (foreignItemHasDocOnlyCfg(i)) return
        val raw = i.ident.toString().removePrefix("r#")
        if (raw != "_") {
            val ktName = rustConstIdentToKotlin(raw)
            if (blockDepth == 0) {
                declaredConstNames[raw] = ktName
                declaredConstTypes[ktName] = i.ty
            }
        }
        super.visitForeignItemStatic(i)
    }

    override fun visitItemStruct(i: Item.Struct) {
        val name = rustTypeIdentToKotlin(i.ident.toString())
        if (blockDepth == 0) {
            declaredTypeNames += name
            attachableTypeNames += name
        }
        when (val fields = i.fields) {
            is Fields.Unnamed -> {
                val list = fields.fields.unnamed.toList()
                tupleStructArityByType[name] = list.size
                val fieldNames = list.indices.map { idx -> tupleFieldPropertyName(idx, list.size) }
                structFieldNamesByType[name] = fieldNames
                val fieldTyMap = linkedMapOf<String, SynType>()
                for ((idx, f) in list.withIndex()) {
                    fieldTyMap[fieldNames[idx]] = f.ty
                }
                structFieldSynTypesByType[name] = fieldTyMap
            }
            is Fields.Named -> {
                val names = mutableListOf<String>()
                val fieldTyMap = linkedMapOf<String, SynType>()
                val seen = hashSetOf<String>()
                val namedFields = fields.fields.named.toList()
                for ((idx, f) in namedFields.withIndex()) {
                    if (isDocOnlyCfgAttribute(f.attrs)) continue
                    val fName = rustFieldIdentToKotlin(f.ident?.toString() ?: "field$idx")
                    if (seen.add(fName)) {
                        names += fName
                        fieldTyMap[fName] = f.ty
                        classifyStructFieldType(name, fName, f.ty)
                    }
                }
                structFieldNamesByType[name] = names
                structFieldSynTypesByType[name] = fieldTyMap
            }
            is Fields.Unit -> {
                unitStructNames += name
                structFieldNamesByType[name] = emptyList()
            }
        }
        super.visitItemStruct(i)
    }

    private fun classifyStructFieldType(ownerType: String, ktFieldName: String, ty: SynType) {
        val u = unwrapSynType(ty)
        val path = (u as? SynType.Path)?.takeIf { it.qself == null }?.path ?: return
        val segs = path.segments.toList()
        if (segs.isEmpty()) return
        val lastIdent = segs.last().ident.toString()
        val hasSlicePrefix = segs.any { it.ident.toString() == "slice" }
        val hasVecOrOptionPrefix = segs.any { it.ident.toString() == "vec" || it.ident.toString() == "option" }
        when {
            lastIdent == "Option" -> nullableFieldsByType.getOrPut(ownerType) { linkedSetOf() }.add(ktFieldName)
            lastIdent == "Vec" -> vecFieldsByType.getOrPut(ownerType) { linkedSetOf() }.add(ktFieldName)
            (lastIdent == "Iter" || lastIdent == "IterMut") && hasSlicePrefix ->
                dequeIterFieldsByType.getOrPut(ownerType) { linkedSetOf() }.add(ktFieldName)
            lastIdent == "IntoIter" && hasVecOrOptionPrefix ->
                dequeIterFieldsByType.getOrPut(ownerType) { linkedSetOf() }.add(ktFieldName)
        }
    }

    override fun visitItemEnum(i: Item.Enum) {
        val name = rustTypeIdentToKotlin(i.ident.toString())
        if (blockDepth == 0) {
            declaredTypeNames += name
            attachableTypeNames += name
        }
        val enumTypeParams =
            i.generics.params
                .toList()
                .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
        for (v in i.variants.toList()) {
            val vName = rustTypeIdentToKotlin(v.ident.toString())
            enumVariantSimpleNames += vName
            if (enumTypeParams.isNotEmpty()) {
                enumTypeParamsByVariant["$name.$vName"] = enumTypeParams
                enumTypeParamsByVariant[vName] = enumTypeParams
            }
            val vFields = v.fields
            if (vFields is Fields.Unnamed) {
                val fieldTys =
                    vFields.fields.unnamed
                        .toList()
                        .map { it.ty }
                tupleEnumVariantArity[name to vName] = fieldTys.size
                enumVariantFieldTypes["$name.$vName"] = fieldTys
                enumVariantFieldTypes[vName] = fieldTys
            }
        }
        super.visitItemEnum(i)
    }

    override fun visitItemTrait(i: Item.Trait) {
        val ktTraitName = rustTypeIdentToKotlin(i.ident.toString())
        if (blockDepth == 0) {
            declaredTypeNames += ktTraitName
            declaredTraitsByName[ktTraitName] = i
        }
        val defaultlessAssocs = mutableListOf<String>()
        for (item in i.items) {
            if (item is TraitItem.AssocType) {
                val assocName = rustTypeIdentToKotlin(item.ident.toString())
                val defaultTy = item.default?.type
                if (defaultTy != null) {
                    if (assocName !in associatedTypeDecls) {
                        associatedTypeDecls[assocName] = AssocTypeDecl(defaultTy, i.generics)
                    }
                } else {
                    defaultlessAssocs += assocName
                }
            }
        }
        if (defaultlessAssocs.isNotEmpty() && blockDepth == 0) {
            traitDefaultlessAssocTypes[ktTraitName] = defaultlessAssocs.distinct()
        }
        if (blockDepth == 0 && traitReferencesSelfOutsideReceiver(i)) {
            selfParameterizedTraits += ktTraitName
        }
        super.visitItemTrait(i)
    }

    override fun visitItemUnion(i: Item.Union) {
        val name = rustTypeIdentToKotlin(i.ident.toString())
        if (blockDepth == 0) {
            declaredTypeNames += name
            attachableTypeNames += name
        }
        super.visitItemUnion(i)
    }

    override fun visitItemType(i: Item.ItemType) {
        val name = rustTypeIdentToKotlin(i.ident.toString())
        if (blockDepth > 0) {
            if (name !in associatedTypeDecls) {
                associatedTypeDecls[name] = AssocTypeDecl(i.ty, i.generics)
            }
        } else if (!isExternalIdentityTypeReexportAlias(i, selfCrateAliases)) {
            declaredTypeNames += name
            if (name !in typeAliasTargets) {
                typeAliasTargets[name] = i.ty
            }
        }
        super.visitItemType(i)
    }

    override fun visitItemMod(i: Item.Mod) {
        if (blockDepth == 0) {
            if (i.content is ModContent.Inline) {
                localInlineModuleNames += i.ident.toString().removePrefix("r#")
            } else {
                localExternalModuleNames += i.ident.toString().removePrefix("r#")
            }
        }
        super.visitItemMod(i)
    }

    override fun visitItemImpl(i: Item.Impl) {
        val ownerName = extractInspectorOwnerName(i.selfType, i.generics)
        val implHasTypeParams =
            i.generics.params
                .toList()
                .any { it is GenericParam.TypeParam }
        for (implItem in i.items) {
            if (implItem is ImplItem.AssocType) {
                val assocName = rustTypeIdentToKotlin(implItem.ident.toString())
                if (assocName !in associatedTypeDecls) {
                    associatedTypeDecls[assocName] = AssocTypeDecl(implItem.ty, i.generics)
                }
            } else if (implItem is ImplItem.Fn && ownerName != null) {
                if (isDocOnlyCfgAttribute(implItem.attrs)) continue
                val fnKt = rustFunctionIdentToKotlin(implItem.sig.ident.toString())
                if (implItem.sig.inputs.firstOrNull() is FnArg.Receiver) {
                    instanceMethodsByType.getOrPut(ownerName) { linkedSetOf() }.add(fnKt)
                } else {
                    companionMethodsByType.getOrPut(ownerName) { linkedSetOf() }.add(fnKt)
                }
                val fnHasTypeParams =
                    implItem.sig.generics.params
                        .toList()
                        .any { it is GenericParam.TypeParam }
                if (!implHasTypeParams && !fnHasTypeParams) {
                    val out = implItem.sig.output
                    if (out is ReturnType.TypeReturn) {
                        val key = ownerName to fnKt
                        if (key !in implMethodReturnSynTypes) {
                            implMethodReturnSynTypes[key] = out.ty
                        }
                    }
                }
            }
        }
        super.visitItemImpl(i)
    }

    private fun extractInspectorOwnerName(selfTy: SynType, generics: Generics): String? {
        val typeParams =
            generics.params
                .toList()
                .mapNotNull { (it as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin) }
                .toSet()
        return when (val u = unwrapSynType(selfTy)) {
            is SynType.Reference -> extractInspectorOwnerName(u.elem, generics)
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
}

internal fun synTypeReferencesDirectSelf(ty: SynType): Boolean {
    var found = false
    val visitor =
        object : Visit() {
            override fun visitPath(p: Path) {
                val segs = p.segments.toList()
                if (segs.size == 1 && segs[0].ident.toString() == "Self") {
                    found = true
                    return
                }
                super.visitPath(p)
            }
        }
    visitor.visitType(ty)
    return found
}

internal fun traitReferencesSelfOutsideReceiver(traitItem: Item.Trait): Boolean {
    for (member in traitItem.items) {
        if (traitItemHasDocOnlyCfg(member)) continue
        when (member) {
            is TraitItem.Const -> {
                if (synTypeReferencesDirectSelf(member.ty)) return true
            }
            is TraitItem.Fn -> {
                for (arg in member.sig.inputs.toList()) {
                    if (arg is FnArg.Typed && synTypeReferencesDirectSelf(arg.patType.ty)) {
                        return true
                    }
                }
                val retTy =
                    when (val out = member.sig.output) {
                        is ReturnType.Default -> null
                        is ReturnType.TypeReturn -> out.ty
                    }
                if (retTy != null && synTypeReferencesDirectSelf(retTy)) return true
            }
            else -> {}
        }
    }
    return false
}

internal fun synTypeReferencesIdent(ty: SynType, target: String): Boolean {
    var found = false
    val visitor =
        object : Visit() {
            override fun visitPath(p: Path) {
                for (seg in p.segments.toList()) {
                    if (seg.ident.toString().removePrefix("r#") == target) {
                        found = true
                        return
                    }
                }
                super.visitPath(p)
            }
        }
    visitor.visitType(ty)
    return found
}

internal fun fieldsReferenceIdent(fields: Fields, target: String): Boolean =
    when (fields) {
        is Fields.Named -> {
            val named = fields.fields.named.toList()
            named.any { synTypeReferencesIdent(it.ty, target) }
        }
        is Fields.Unnamed -> {
            val unnamed = fields.fields.unnamed.toList()
            unnamed.any { synTypeReferencesIdent(it.ty, target) }
        }
        is Fields.Unit -> false
    }

internal fun validateSupportedRustSemantics(
    file: File,
    allowManuallyDrop: Boolean = false,
) {
    val unsupportedDestructorSymbols = linkedSetOf<String>()
    val visitor =
        object : Visit() {
            override fun visitItemStruct(i: Item.Struct) {
                if (!allowManuallyDrop && fieldsReferenceIdent(i.fields, "ManuallyDrop")) {
                    unsupportedDestructorSymbols += "ManuallyDrop"
                }
                super.visitItemStruct(i)
            }

            override fun visitVariant(v: Variant) {
                if (!allowManuallyDrop && fieldsReferenceIdent(v.fields, "ManuallyDrop")) {
                    unsupportedDestructorSymbols += "ManuallyDrop"
                }
                super.visitVariant(v)
            }

            override fun visitItemUnion(i: Item.Union) {
                val unionFields = i.fields.named.toList()
                if (!allowManuallyDrop && unionFields.any { synTypeReferencesIdent(it.ty, "ManuallyDrop") }) {
                    unsupportedDestructorSymbols += "ManuallyDrop"
                }
                super.visitItemUnion(i)
            }

            override fun visitUseTree(u: UseTree) {
                if (u is UseTree.Name && u.ident.toString().removePrefix("r#") == "needs_drop") {
                    unsupportedDestructorSymbols += "needs_drop"
                }
                super.visitUseTree(u)
            }

            override fun visitPath(p: Path) {
                for (seg in p.segments.toList()) {
                    if (seg.ident.toString().removePrefix("r#") == "needs_drop") {
                        unsupportedDestructorSymbols += "needs_drop"
                    }
                }
                super.visitPath(p)
            }
        }
    visitor.visitFile(file)
    if (unsupportedDestructorSymbols.isNotEmpty()) {
        throw IllegalArgumentException(
            "Unsupported Rust destructor-suppression semantics (${unsupportedDestructorSymbols.joinToString(", ")}): " +
                "Kotlin has no deterministic Drop or ManuallyDrop/needs_drop runtime equivalent",
        )
    }
}

internal fun describeUnemittedSubstantiveItems(file: File): List<String> {
    val labels = mutableListOf<String>()
    for (item in file.items) {
        if (itemHasDocOnlyCfg(item)) continue
        when (item) {
            is Item.Use, is Item.ExternCrate -> {}
            is Item.Macro -> {
                if (item.mac.path.isIdent("macro_rules")) {
                    val macroName = item.ident?.toString()?.removePrefix("r#") ?: "macro_rules"
                    labels += "$macroName!"
                } else {
                    val macPath = item.mac.path
                    val pathSegs = macPath.segments.toList()
                    val pathText = pathSegs.joinToString("::") { it.ident.toString() }
                    if (pathText != "windows_link::link" && pathText != "include") {
                        labels += "$pathText!"
                    }
                }
            }
            is Item.Mod -> {
                val content = item.content
                if (content is ModContent.Inline) {
                    val inner = describeUnemittedSubstantiveItems(File(shebang = null, attrs = emptyList(), items = content.items))
                    labels.addAll(inner)
                }
            }
            else -> {}
        }
    }
    return labels.distinct()
}
