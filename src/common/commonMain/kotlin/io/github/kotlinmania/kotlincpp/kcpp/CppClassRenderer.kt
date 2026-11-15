package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.VALUE_PASS_CPP_TYPES
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame

internal fun CppDeclarationRenderer.renderClassOrObject(
    declFrame: PacketFrame,
    qualifierPrefix: String,
    indent: String,
    outOfLineOwnerQual: String? = null,
): String {
    val cls = symbols.classesByFrame[declFrame.frameIndex] ?: return ""
    if (isHeaderMode && shouldEmitPrivateClassOnlyInCpp(cls)) {
        return ""
    }
    val isExpectCls = FrameAstHelpers.hasModifierKeyword(stream, declFrame, KtTokens.EXPECT_MODIFIER_ID)
    if (isExpectCls && cls.kind != KtClassKind.ANNOTATION_CLASS && !isUsedAsSuperTypeInSameFile(cls)) {
        includes.hasExpectDeclarations = true
        if (!isHeaderMode) return ""
        val prefix = renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true)
        if (cls.kotlinName == "TestResult") {
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            return "${prefix}${indent}using TestResult = kotlin::Unit;\n"
        }
        if (cls.kotlinName == "StackTraceElement") {
            includes.addSystem("any")
            return "${prefix}${indent}using StackTraceElement = std::any;\n"
        }
        return if (isReferencedByNonExpectInSameFile(cls)) {
            buildString {
                append(prefix)
                append(renderTemplatePrefix(cls.typeParams, indent))
                append(indent).append("class ").append(cls.cppName).append(";\n")
            }
        } else {
            "$prefix$indent// expect class ${cls.cppName}\n"
        }
    }
    return when (cls.kind) {
        KtClassKind.ANNOTATION_CLASS ->
            if (isHeaderMode) {
                val prefix = renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true)
                "$prefix$indent// Annotation marker ${cls.cppName}\n"
            } else {
                ""
            }
        KtClassKind.SIMPLE_ENUM ->
            if (isHeaderMode) renderSimpleEnum(declFrame, cls, indent) else ""
        KtClassKind.RICH_ENUM ->
            renderRichEnum(declFrame, cls, indent)
        KtClassKind.VALUE_CLASS ->
            renderValueClass(declFrame, cls, qualifierPrefix, indent, outOfLineOwnerQual)
        KtClassKind.DATA_CLASS ->
            if (isHeaderMode) renderDataClass(declFrame, cls, qualifierPrefix, indent, outOfLineOwnerQual) else ""
        KtClassKind.INTERFACE,
        KtClassKind.SEALED_CLASS,
        KtClassKind.REGULAR_CLASS,
        KtClassKind.OBJECT_SINGLETON,
        KtClassKind.COMPANION_OBJECT,
        -> renderGeneralClass(declFrame, cls, qualifierPrefix, indent, outOfLineOwnerQual)
    }
}

internal fun CppDeclarationRenderer.renderSimpleEnum(
    declFrame: PacketFrame,
    cls: KtClassSymbol,
    indent: String,
): String =
    buildString {
        append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
        append(indent).append("enum class ").append(cls.cppName).append(" {\n")
        val inner = "$indent    "
        for ((idx, entry) in cls.enumEntries.withIndex()) {
            val entryFrame = stream.frames[entry.frameIndex]
            for (c in FrameAstHelpers.extractLeadingDeclarationComments(stream, entryFrame)) {
                val formatted =
                    FrameAstHelpers.formatCommentForCpp(
                        comment = c,
                        indent = inner,
                        knownFunctionNames = bundle.knownFunctionNames,
                        allowFenceTranslation = !bundle.isDocExample,
                    )
                if (formatted.isNotEmpty()) {
                    if (idx > 0) append('\n')
                    append(formatted).append('\n')
                }
            }
            append(inner).append(entry.cppName)
            if (idx < cls.enumEntries.lastIndex) append(',')
            append('\n')
        }
        append(indent).append("};\n")
    }

internal fun CppDeclarationRenderer.renderRichEnum(
    declFrame: PacketFrame,
    cls: KtClassSymbol,
    indent: String,
): String {
    if (!isHeaderMode) return ""
    val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls, receiverSubstitution = "self")
    return buildString {
        append(renderSimpleEnum(declFrame, cls, indent))

        // Emit `constexpr` accessors for any primary constructor properties on the rich enum
        for ((pIdx, cp) in cls.primaryConstructorParams.withIndex()) {
            if (!cp.isVal && !cp.isVar) continue
            val retT =
                cp.typeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                } ?: "auto"
            append('\n')
            append(indent)
                .append("inline constexpr ")
                .append(retT)
                .append(' ')
                .append(cp.cppName)
                .append('(')
                .append(cls.cppName)
                .append(" self) {\n")
            append(indent).append("    switch (self) {\n")
            for (entry in cls.enumEntries) {
                val argVal =
                    entry.constructorArgsFrameIndex?.let { argListIdx ->
                        val vas = FrameAstHelpers.childFramesOfType(stream, stream.frames[argListIdx], KtNodeTypes.VALUE_ARGUMENT_ID)
                        val va = vas.getOrNull(pIdx)
                        val expr = va?.let { FrameAstHelpers.childFrames(stream, it).lastOrNull() }
                        expr?.let { emitter.emitExpression(it) }
                    } ?: "{}"
                append(indent)
                    .append("        case ")
                    .append(cls.cppName)
                    .append("::")
                    .append(entry.cppName)
                    .append(": return ")
                    .append(argVal)
                    .append(";\n")
            }
            append(indent).append("    }\n")
            includes.addSystem("utility")
            append(indent).append("    std::unreachable();\n")
            append(indent).append("}\n")
        }

        // Emit free helper functions for rich enum properties and methods (`CoroutineStart.hpp` pattern)
        for (mPropIdx in cls.memberPropertyFrameIndices) {
            val propSym = symbols.propertiesByFrame[mPropIdx] ?: continue
            val propFrame = stream.frames[mPropIdx]
            val retT =
                propSym.returnTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                } ?: "bool"
            val getterBody =
                propSym.getterFrameIndex?.let { gIdx ->
                    val gFrame = stream.frames[gIdx]
                    FrameAstHelpers.firstChildFrame(stream, gFrame, KtNodeTypes.BLOCK_ID)
                        ?: FrameAstHelpers.extractInitializerAfterEquals(stream, gFrame)
                } ?: propSym.initializerFrameIndex?.let { stream.frames[it] }
            if (getterBody != null) {
                append('\n')
                append(renderProvenanceAndLeadingComments(propFrame, indent, includeLeadingKdoc = true))
                val isExpr = getterBody.nodeTypeId != KtNodeTypes.BLOCK_ID
                val bodyStr = emitter.emitBlockOrExprBody(getterBody, isExpr, isVoidReturn = false, indent = indent)
                val selfUnused =
                    if (CppComments.containsWholeWordInCode(bodyStr, "self")) "" else "[[maybe_unused]] "
                append(indent)
                    .append("inline ")
                    .append(retT)
                    .append(' ')
                    .append(propSym.cppName)
                    .append('(')
                    .append(selfUnused)
                    .append(cls.cppName)
                    .append(" self) ")
                    .append(bodyStr)
                    .append('\n')
            }
        }

        for (mFnIdx in cls.memberFunctionFrameIndices) {
            val fnSym = symbols.functionsByFrame[mFnIdx] ?: continue
            val fnFrame = stream.frames[mFnIdx]
            val bodyIdx = fnSym.bodyFrameIndex ?: continue
            val retT =
                fnSym.returnTypeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                } ?: "void"
            val fnEmitter =
                CppExprAndStmtEmitter(
                    bundle = bundle,
                    enclosingClass = cls,
                    enclosingFunction = fnSym,
                    receiverSubstitution = "self",
                )
            for (vp in fnSym.valueParams) fnEmitter.bindLocalName(vp.name, vp.cppName)
            val fnBodyStr =
                fnEmitter.emitBlockOrExprBody(
                    stream.frames[bodyIdx],
                    fnSym.isExpressionBody,
                    retT == "void",
                    indent,
                )
            val unusedFnParams =
                fnSym.valueParams
                    .filter { vp -> !CppComments.containsWholeWordInCode(fnBodyStr, vp.cppName) }
                    .mapTo(HashSet()) { it.cppName }
            val selfUnused =
                if (CppComments.containsWholeWordInCode(fnBodyStr, "self")) "" else "[[maybe_unused]] "
            val extraParams =
                buildFunctionParameterList(
                    sym = fnSym,
                    includeDefaults = true,
                    unusedParamCppNames = unusedFnParams,
                )
            val fullParams =
                if (extraParams.isEmpty()) {
                    "$selfUnused${cls.cppName} self"
                } else {
                    "$selfUnused${cls.cppName} self, $extraParams"
                }
            append('\n')
            append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(fnSym.typeParams, indent))
            val fnCallName = if (fnSym.kotlinName == "invoke") "invoke" else fnSym.cppName
            append(indent)
                .append("inline ")
                .append(retT)
                .append(' ')
                .append(fnCallName)
                .append('(')
                .append(fullParams)
                .append(") ")
                .append(fnBodyStr)
                .append('\n')
        }
    }
}

internal fun CppDeclarationRenderer.renderClassOutOfLineCompanionAndMembers(
    cls: KtClassSymbol,
    fullQual: String,
    indent: String,
    scopeSigs: MutableSet<String>,
    out: StringBuilder,
) {
    fun appendNonBlank(sub: String) {
        if (sub.isNotBlank()) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(sub)
        }
    }
    for (nestedIdx in cls.memberClassFrameIndices) {
        val nestedSym = symbols.classesByFrame[nestedIdx]
        val nestedQual =
            if (nestedSym != null && (cls.kotlinName to nestedSym.kotlinName) in symbols.nonInnerNestedRewrites) {
                ""
            } else {
                fullQual
            }
        appendNonBlank(renderClassOrObject(stream.frames[nestedIdx], nestedQual, indent))
    }
    cls.companionFrameIndex?.let { compIdx ->
        val compSym = symbols.classesByFrame[compIdx]
        if (compSym != null) {
            if (compSym.superTypeFrameIndices.isNotEmpty()) {
                appendNonBlank(renderClassOrObject(stream.frames[compIdx], fullQual, indent))
            } else {
                for (cpIdx in compSym.memberPropertyFrameIndices) {
                    appendNonBlank(renderProperty(stream.frames[cpIdx], compSym, indent))
                }
                for (cfIdx in compSym.memberFunctionFrameIndices) {
                    appendNonBlank(renderFunction(stream.frames[cfIdx], compSym, fullQual, indent, scopeSigs))
                }
            }
        }
    }
    for (mPropIdx in cls.memberPropertyFrameIndices) {
        appendNonBlank(renderProperty(stream.frames[mPropIdx], cls, indent))
    }
    for (mFnIdx in cls.memberFunctionFrameIndices) {
        appendNonBlank(renderFunction(stream.frames[mFnIdx], cls, fullQual, indent, scopeSigs))
    }
}

internal fun CppDeclarationRenderer.renderClassCompanionPropsAndFnsInHeader(
    cls: KtClassSymbol,
    fullQual: String,
    inner: String,
    scopeSigs: MutableSet<String>,
    out: StringBuilder,
    allowSuperCompanion: Boolean = false,
) {
    val compIdx = cls.companionFrameIndex ?: return
    val compSym = symbols.classesByFrame[compIdx] ?: return
    if (compSym.superTypeFrameIndices.isEmpty()) {
        val compName = compSym.cppName.ifEmpty { "Companion" }
        val dottedCompNames =
            setOf(
                "${cls.kotlinName}.${compSym.kotlinName}",
                "${cls.kotlinName}.Companion",
            )
        val hasCompanionExt =
            symbols.functionsByFrame.values.any { fn ->
                fn.receiverTypeFrameIndex?.let { rIdx ->
                    FrameAstHelpers.dottedNameText(stream, stream.frames[rIdx]) in dottedCompNames
                } == true
            } ||
                symbols.propertiesByFrame.values.any { prop ->
                    prop.receiverTypeFrameIndex?.let { rIdx ->
                        FrameAstHelpers.dottedNameText(stream, stream.frames[rIdx]) in dottedCompNames
                    } == true
                }
        if (hasCompanionExt) {
            out
                .append('\n')
                .append(inner)
                .append("struct ")
                .append(compName)
                .append(" {};\n")
        }
        for (cpIdx in compSym.memberPropertyFrameIndices) {
            val rendered = renderProperty(stream.frames[cpIdx], compSym, inner)
            if (rendered.isNotBlank()) out.append('\n').append(rendered)
        }
        for (cfIdx in compSym.memberFunctionFrameIndices) {
            val rendered = renderFunction(stream.frames[cfIdx], compSym, fullQual, inner, scopeSigs)
            if (rendered.isNotBlank()) out.append('\n').append(rendered)
        }
    } else if (allowSuperCompanion) {
        val rendered = renderClassOrObject(stream.frames[compIdx], fullQual, inner)
        if (rendered.isNotBlank()) out.append('\n').append(rendered)
    }
}

internal fun CppDeclarationRenderer.collectHoistedNonInnerNestedClasses(cls: KtClassSymbol): List<KtClassSymbol> {
    val raw =
        cls.memberClassFrameIndices
            .mapNotNull { symbols.classesByFrame[it] }
            .filter { (cls.kotlinName to it.kotlinName) in symbols.nonInnerNestedRewrites }
    if (raw.size <= 1) return raw
    val rawByName = raw.associateBy { it.kotlinName }
    val ordered = ArrayList<KtClassSymbol>(raw.size)
    val visited = HashSet<Int>()
    fun visit(c: KtClassSymbol) {
        if (!visited.add(c.frameIndex)) return
        for (stIdx in c.superTypeFrameIndices) {
            val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[stIdx]) ?: continue
            rawByName[simple]?.let { visit(it) }
        }
        ordered.add(c)
    }
    for (c in raw) visit(c)
    return ordered
}

internal fun CppDeclarationRenderer.appendHoistedNestedDefinitions(
    cls: KtClassSymbol,
    indent: String,
    out: StringBuilder,
) {
    for (nested in collectHoistedNonInnerNestedClasses(cls)) {
        val rendered = renderClassOrObject(stream.frames[nested.frameIndex], "", indent)
        if (rendered.isNotBlank()) {
            out.append(rendered).append('\n')
        }
    }
}

internal fun CppDeclarationRenderer.renderValueClass(
    declFrame: PacketFrame,
    cls: KtClassSymbol,
    qualifierPrefix: String,
    indent: String,
    outOfLineOwnerQual: String? = null,
): String {
    val fullQual = if (qualifierPrefix.isEmpty()) cls.cppName else "$qualifierPrefix::${cls.cppName}"
    val scopeSigs = collectClassScopeNormalizedSigs(cls)
    if (!isHeaderMode) {
        return buildString {
            renderClassOutOfLineCompanionAndMembers(cls, fullQual, indent, scopeSigs, this)
        }
    }
    val underlyingParam = cls.primaryConstructorParams.firstOrNull()
    val valType =
        underlyingParam?.typeFrameIndex?.let {
            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
        } ?: "std::int64_t"
    val propName = underlyingParam?.cppName ?: "value"
    val fieldName = CppNamingPolicy.toPrivateFieldName(propName)
    val headName = if (outOfLineOwnerQual != null) "$outOfLineOwnerQual::${cls.cppName}" else cls.cppName
    val constexprKw = if (valType in VALUE_PASS_CPP_TYPES) "constexpr " else ""
    val fieldIsTypeParamIndependent =
        cls.typeParams.size == 1 && cls.typeParams.none { tp -> containsWholeWord(valType, tp.name) }
    val defaultSuffix =
        if (underlyingParam?.defaultExprFrameIndex != null) {
            val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls, inDefaultParameterExpr = true)
            val rawDef = emitter.emitExpression(stream.frames[underlyingParam.defaultExprFrameIndex])
            val coerced =
                if (rawDef == "nullptr" && valType.startsWith("std::optional<")) {
                    includes.addSystem("optional")
                    "std::nullopt"
                } else if (rawDef == "nullptr" && valType == "std::any") {
                    "{}"
                } else {
                    rawDef
                }
            " = $coerced"
        } else {
            ""
        }
    includes.addSystem("utility")

    val inner = "$indent    "
    return buildString {
        if (outOfLineOwnerQual == null) {
            appendHoistedNestedDefinitions(cls, indent, this)
        }
        append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
        append(renderTemplatePrefix(cls.typeParams, indent))
        append(indent).append("class ").append(headName).append(" {\n")
        if (!bundle.isDocExample && cls.typeParams.isNotEmpty()) {
            append(inner)
                .append("template <")
                .append(cls.typeParams.joinToString(", ") { "typename" })
                .append(">\n")
            append(inner).append("friend class ").append(cls.cppName).append(";\n")
        }
        append(indent).append("private:\n")
        append(inner)
            .append(valType)
            .append(' ')
            .append(fieldName)
            .append(";\n")
        append(indent).append("public:\n")
        append(inner)
            .append(constexprKw)
            .append("explicit ")
            .append(cls.cppName)
            .append('(')
            .append(valType)
            .append(' ')
            .append(propName)
            .append(defaultSuffix)
            .append(") : ")
            .append(fieldName)
            .append("(std::move(")
            .append(propName)
            .append(")) {}\n")
        if (fieldIsTypeParamIndependent) {
            append(inner)
                .append("template <typename KxsOtherT>\n")
            append(inner)
                .append(constexprKw)
                .append(cls.cppName)
                .append("(const ")
                .append(cls.cppName)
                .append("<KxsOtherT>& other) : ")
                .append(fieldName)
                .append("(other.")
                .append(fieldName)
                .append(") {}\n")
        }
        append(inner)
            .append(constexprKw)
            .append("const ")
            .append(valType)
            .append("& ")
            .append(propName)
            .append("() const noexcept { return ")
            .append(fieldName)
            .append("; }\n")
        if (isDefaultComparableCppType(valType)) {
            append(inner)
                .append(constexprKw)
                .append("bool operator==(const ")
                .append(cls.cppName)
                .append("&) const = default;\n")
        } else {
            includes.addSystem("memory")
            append(inner)
                .append("bool operator==(const ")
                .append(cls.cppName)
                .append("& other) const noexcept { return std::addressof(")
                .append(fieldName)
                .append(") == std::addressof(other.")
                .append(fieldName)
                .append("); }\n")
        }
        for (nestedIdx in cls.memberClassFrameIndices) {
            val nestedCls = symbols.classesByFrame[nestedIdx]
            if (nestedCls != null && (cls.kotlinName to nestedCls.kotlinName) in symbols.nonInnerNestedRewrites) {
                append('\n')
                    .append(inner)
                    .append("using ")
                    .append(CppNamingPolicy.toTypeName(nestedCls.kotlinName))
                    .append(" = ")
                    .append(nestedCls.cppName)
                    .append(";\n")
            } else {
                val rendered = renderClassOrObject(stream.frames[nestedIdx], fullQual, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
        }
        renderClassCompanionPropsAndFnsInHeader(cls, fullQual, inner, scopeSigs, this, allowSuperCompanion = true)
        for (mPropIdx in cls.memberPropertyFrameIndices) {
            val rendered = renderProperty(stream.frames[mPropIdx], cls, inner)
            if (rendered.isNotBlank()) append('\n').append(rendered)
        }
        for (mFnIdx in cls.memberFunctionFrameIndices) {
            val rendered = renderFunction(stream.frames[mFnIdx], cls, fullQual, inner, scopeSigs)
            if (rendered.isNotBlank()) append('\n').append(rendered)
        }
        val cmpHelpers = renderComparableClassHelpers(cls, inner)
        if (cmpHelpers.isNotBlank()) append('\n').append(cmpHelpers)
        append(indent).append("};\n")
    }
}

internal fun CppDeclarationRenderer.renderDataClass(
    declFrame: PacketFrame,
    cls: KtClassSymbol,
    qualifierPrefix: String,
    indent: String,
    outOfLineOwnerQual: String? = null,
): String {
    includes.addSystem("string")
    includes.addSystem("utility")
    val fullQual = if (qualifierPrefix.isEmpty()) cls.cppName else "$qualifierPrefix::${cls.cppName}"
    val headName = if (outOfLineOwnerQual != null) "$outOfLineOwnerQual::${cls.cppName}" else cls.cppName
    val inheritance = renderInheritanceClause(cls)
    val inner = "$indent    "

    return buildString {
        if (outOfLineOwnerQual == null) {
            appendHoistedNestedDefinitions(cls, indent, this)
        }
        append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
        append(renderTemplatePrefix(cls.typeParams, indent))
        val restrictsAttr =
            if (PacketFlags.hasFlag(declFrame.flags, PacketFlags.RESTRICTS_SUSPENSION_SCOPE) && !bundle.isDocExample) {
                "[[clang::annotate(\"kotlin.coroutines.RestrictsSuspension\")]] "
            } else {
                ""
            }
        append(indent)
            .append("class ")
            .append(restrictsAttr)
            .append(headName)
            .append(inheritance)
            .append(" {\n")
        if (!bundle.isDocExample && cls.typeParams.isNotEmpty()) {
            append(inner)
                .append("template <")
                .append(cls.typeParams.joinToString(", ") { "typename" })
                .append(">\n")
            append(inner).append("friend class ").append(cls.cppName).append(";\n")
        }
        append(indent).append("public:\n")

        // Companion object with supertypes (e.g. `Key : CoroutineContext.KeyTyped<E>`) must precede constructor initializers.
        cls.companionFrameIndex?.let { compIdx ->
            val compSym = symbols.classesByFrame[compIdx]
            if (compSym != null && compSym.superTypeFrameIndices.isNotEmpty()) {
                val renderedComp = renderClassOrObject(stream.frames[compIdx], fullQual, inner)
                if (renderedComp.isNotBlank()) append('\n').append(renderedComp)
            }
        }

        val paramTypes = ArrayList<String>(cls.primaryConstructorParams.size)
        for (cp in cls.primaryConstructorParams) {
            val t =
                cp.typeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                } ?: "std::any"
            paramTypes.add(t)
            if (cp.isVal || cp.isVar) {
                val mutPrefix = if (cp.isVar) "" else ""
                append(inner)
                    .append(mutPrefix)
                    .append(t)
                    .append(' ')
                    .append(cp.cppName)
                    .append(";\n")
            }
        }

        if (cls.primaryConstructorParams.isNotEmpty()) {
            val explicitKw = if (cls.primaryConstructorParams.size == 1) "explicit " else ""
            val emitter = CppExprAndStmtEmitter(bundle)
            val ctorParams =
                cls.primaryConstructorParams.mapIndexed { i, cp ->
                    val defIdx = cp.defaultExprFrameIndex
                    val def =
                        if (defIdx != null) {
                            val rawDef = emitter.emitExpression(stream.frames[defIdx])
                            val coercedDef =
                                if (rawDef == "nullptr" && paramTypes[i].startsWith("std::optional<")) {
                                    includes.addSystem("optional")
                                    "std::nullopt"
                                } else {
                                    rawDef
                                }
                            " = $coercedDef"
                        } else {
                            ""
                        }
                    "${paramTypes[i]} ${cp.cppName}_in$def"
                }
            val inits = buildConstructorInitializerList(cls, suffix = "_in")
            append('\n')
                .append(inner)
                .append(explicitKw)
                .append(cls.cppName)
                .append('(')
                .append(ctorParams.joinToString(", "))
                .append(") : ")
                .append(inits.joinToString(", "))
                .append(" {}\n")
        }

        val propParams = cls.primaryConstructorParams.filter { it.isVal || it.isVar }
        val canDefaultEq =
            cls.superTypeFrameIndices.all { trIdx ->
                val baseSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx])
                val baseCls = baseSimple?.let { lookupClassInScope(cls, it) }
                baseCls != null && baseCls.kind == KtClassKind.SEALED_CLASS && baseCls.superTypeFrameIndices.isEmpty()
            } &&
                cls.primaryConstructorParams.withIndex().all { (idx, cp) ->
                    !(cp.isVal || cp.isVar) || isDefaultComparableCppType(paramTypes[idx])
                }
        if (canDefaultEq) {
            append(inner).append("bool operator==(const ").append(cls.cppName).append("&) const = default;\n")
        } else {
            val fieldChecks =
                cls.primaryConstructorParams.mapIndexedNotNull { idx, cp ->
                    if (!cp.isVal && !cp.isVar) return@mapIndexedNotNull null
                    val t = paramTypes[idx]
                    if (isDefaultComparableCppType(t)) {
                        "this->${cp.cppName} == other.${cp.cppName}"
                    } else {
                        includes.addSystem("memory")
                        "std::addressof(this->${cp.cppName}) == std::addressof(other.${cp.cppName})"
                    }
                }
            val eqExpr = if (fieldChecks.isEmpty()) "true" else fieldChecks.joinToString(" && ")
            append(inner)
                .append("bool operator==(const ")
                .append(cls.cppName)
                .append("& other) const { return ")
                .append(eqExpr)
                .append("; }\n")
        }

        // `component1()`..`componentN()` and `copy(...)`
        for ((idx, cp) in propParams.withIndex()) {
            val t =
                cp.typeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                } ?: "auto"
            append(inner)
                .append("const ")
                .append(t)
                .append("& component")
                .append(idx + 1)
                .append("() const noexcept { return ")
                .append(cp.cppName)
                .append("; }\n")
        }
        if (propParams.isNotEmpty()) {
            val copyParams =
                propParams.map { cp ->
                    val t =
                        cp.typeFrameIndex?.let {
                            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                        } ?: "auto"
                    "std::optional<$t> ${cp.cppName}_opt = std::nullopt"
                }
            includes.addSystem("optional")
            val copyArgs =
                propParams.joinToString(", ") { cp ->
                    "${cp.cppName}_opt.has_value() ? std::move(*${cp.cppName}_opt) : this->${cp.cppName}"
                }
            append(inner)
                .append(cls.cppName)
                .append(" copy(")
                .append(copyParams.joinToString(", "))
                .append(") const { return ")
                .append(cls.cppName)
                .append('(')
                .append(copyArgs)
                .append("); }\n")
        }

        for (nestedIdx in cls.memberClassFrameIndices) {
            val nestedCls = symbols.classesByFrame[nestedIdx]
            if (nestedCls != null && (cls.kotlinName to nestedCls.kotlinName) in symbols.nonInnerNestedRewrites) {
                append('\n')
                    .append(inner)
                    .append("using ")
                    .append(CppNamingPolicy.toTypeName(nestedCls.kotlinName))
                    .append(" = ")
                    .append(nestedCls.cppName)
                    .append(";\n")
            } else {
                val rendered = renderClassOrObject(stream.frames[nestedIdx], fullQual, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
        }
        val scopeSigs = collectClassScopeNormalizedSigs(cls)
        renderClassCompanionPropsAndFnsInHeader(cls, fullQual, inner, scopeSigs, this)
        for (mPropIdx in cls.memberPropertyFrameIndices) {
            val rendered = renderProperty(stream.frames[mPropIdx], cls, inner)
            if (rendered.isNotBlank()) append('\n').append(rendered)
        }
        for (mFnIdx in cls.memberFunctionFrameIndices) {
            val rendered = renderFunction(stream.frames[mFnIdx], cls, fullQual, inner, scopeSigs)
            if (rendered.isNotBlank()) append('\n').append(rendered)
        }
        val cmpHelpers = renderComparableClassHelpers(cls, inner)
        if (cmpHelpers.isNotBlank()) append('\n').append(cmpHelpers)
        append(indent).append("};\n")
    }
}

internal data class DelegatedInterfaceInfo(
    val fieldCppName: String,
    val propCppType: String,
    val delegateExprFrameIndex: Int,
    val superSimpleName: String,
    val superCppType: String,
    val isPointerField: Boolean,
)

internal fun CppDeclarationRenderer.extractDelegatedInterfaces(
    declFrame: PacketFrame,
    cls: KtClassSymbol,
): List<DelegatedInterfaceInfo> {
    val superList = FrameAstHelpers.firstChildFrame(stream, declFrame, KtNodeTypes.SUPER_TYPE_LIST_ID) ?: return emptyList()
    val out = ArrayList<DelegatedInterfaceInfo>()
    var idx = 0
    for (entry in FrameAstHelpers.childFramesOfType(stream, superList, KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY_ID)) {
        val tr = FrameAstHelpers.firstChildFrame(stream, entry, KtNodeTypes.TYPE_REFERENCE_ID) ?: continue
        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, tr).orEmpty()
        if (simple == "MutableMap" || simple == "Map") continue
        val expr = FrameAstHelpers.childFrames(stream, entry).firstOrNull { it.nodeTypeId != KtNodeTypes.TYPE_REFERENCE_ID } ?: continue
        val propCppType = bundle.typeTranslator.translateTypeFrame(tr, CppTypePosition.VALUE)
        val superCppType = bundle.typeTranslator.translateTypeFrame(tr, CppTypePosition.TYPE_ARGUMENT)
        val isPtr = propCppType.endsWith("*") || propCppType.startsWith("std::shared_ptr<")
        out.add(
            DelegatedInterfaceInfo(
                fieldCppName = "kxs_delegate_${idx++}_",
                propCppType = propCppType,
                delegateExprFrameIndex = expr.frameIndex,
                superSimpleName = simple,
                superCppType = superCppType,
                isPointerField = isPtr,
            ),
        )
    }
    return out
}

internal fun CppDeclarationRenderer.isPrivateOrBackedOverrideCtorParam(
    cls: KtClassSymbol,
    cp: KtValueParamInfo,
): Boolean =
    (cp.isVal || cp.isVar) &&
        (PacketFlags.hasFlag(stream.frames[cp.frameIndex].flags, PacketFlags.MOD_PRIVATE) || isBackedOverrideCtorParam(cls, cp))

internal fun CppDeclarationRenderer.isPrivateOrBackedOverrideMemberProp(
    cls: KtClassSymbol,
    pIdx: Int,
): Boolean {
    val p = symbols.propertiesByFrame[pIdx] ?: return false
    if (p.isConst || hasCustomGetterBody(p)) return false
    return PacketFlags.hasFlag(p.flags, PacketFlags.MOD_PRIVATE) || isBackedOverrideMemberProp(cls, stream.frames[pIdx], p)
}

internal fun CppDeclarationRenderer.renderGeneralClass(
    declFrame: PacketFrame,
    cls: KtClassSymbol,
    qualifierPrefix: String,
    indent: String,
    outOfLineOwnerQual: String? = null,
): String {
    val fullQual = if (qualifierPrefix.isEmpty()) cls.cppName else "$qualifierPrefix::${cls.cppName}"
    val headName = if (outOfLineOwnerQual != null) "$outOfLineOwnerQual::${cls.cppName}" else cls.cppName
    val currentScopeQualForNested = headName
    val emitCtorInCpp = PacketFlags.hasFlag(cls.flags, PacketFlags.EMIT_IN_CPP_BODY)
    val outerCls =
        if (cls.isInner && cls.enclosingClassFrameIndex != null) {
            symbols.classesByFrame[cls.enclosingClassFrameIndex]
        } else {
            null
        }
    val totalCtorParams = (if (outerCls != null) 1 else 0) + cls.primaryConstructorParams.size
    val hasNonEmptySuperArgs =
        cls.superCallArgsBySuperIndex.values.any { argListIdx ->
            FrameAstHelpers.childFramesOfType(stream, stream.frames[argListIdx], KtNodeTypes.VALUE_ARGUMENT_ID).isNotEmpty()
        }
    val delegatedInterfaces = extractDelegatedInterfaces(declFrame, cls)
    val hasPrimaryCtor =
        totalCtorParams > 0 ||
            hasNonEmptySuperArgs ||
            cls.initBlockFrameIndices.isNotEmpty() ||
            (delegatedInterfaces.isNotEmpty() && emitCtorInCpp)

    if (!isHeaderMode) {
        return buildString {
            if (emitCtorInCpp && hasPrimaryCtor) {
                includes.addSystem("utility")
                val initList = buildConstructorInitializerList(cls, suffix = "_value", delegatedInterfaces = delegatedInterfaces)
                val ctorBody = renderPrimaryConstructorBody(cls, suffix = "_value", indent = indent)
                val initAndBodyText = "${initList.joinToString(", ")}\n$ctorBody"
                val sigParts = ArrayList<String>(totalCtorParams)
                if (outerCls != null) {
                    val outerUnused =
                        if (!CppComments.containsWholeWordInCode(initAndBodyText, "outer_value")) {
                            "[[maybe_unused]] "
                        } else {
                            ""
                        }
                    sigParts.add("$outerUnused${outerCls.cppName}* outer_value")
                }
                for (cp in cls.primaryConstructorParams) {
                    val t =
                        cp.typeFrameIndex?.let {
                            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                        } ?: "std::any"
                    val paramName = "${cp.cppName}_value"
                    val unusedAttr =
                        if (!cp.isVal &&
                            !cp.isVar &&
                            !CppComments.containsWholeWordInCode(initAndBodyText, paramName)
                        ) {
                            "[[maybe_unused]] "
                        } else {
                            ""
                        }
                    sigParts.add("$unusedAttr$t $paramName")
                }
                val ctorSigParams = sigParts.joinToString(", ")
                append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = false))
                append(indent)
                    .append(fullQual)
                    .append("::")
                    .append(cls.cppName)
                    .append('(')
                    .append(ctorSigParams)
                    .append(')')
                if (initList.isNotEmpty()) {
                    append(" : ").append(initList.joinToString(", "))
                }
                append(' ').append(ctorBody).append('\n')
            }

            val scopeSigs = collectClassScopeNormalizedSigs(cls)
            renderClassOutOfLineCompanionAndMembers(cls, fullQual, indent, scopeSigs, this)
        }
    }

    val inheritance = renderInheritanceClause(cls)
    val delegatedMap = extractDelegatedMapTypes(cls)
    val inner = "$indent    "
    val isPolymorphicBase =
        cls.kind == KtClassKind.INTERFACE ||
            cls.kind == KtClassKind.SEALED_CLASS ||
            PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_OPEN) ||
            PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_ABSTRACT) ||
            isUsedAsSuperTypeInSameFile(cls) ||
            cls.kotlinName in symbols.sharedHierarchyTypeNames ||
            CppTypeTranslator.isSharedHierarchyType(cls.kotlinName) ||
            cls.memberFunctionFrameIndices.any { fnIdx ->
                val flags = stream.frames[fnIdx].flags
                PacketFlags.hasFlag(flags, PacketFlags.MOD_OPEN) ||
                    PacketFlags.hasFlag(flags, PacketFlags.MOD_ABSTRACT) ||
                    PacketFlags.hasFlag(flags, PacketFlags.MOD_OVERRIDE)
            } ||
            cls.memberPropertyFrameIndices.any { pIdx ->
                val flags = stream.frames[pIdx].flags
                PacketFlags.hasFlag(flags, PacketFlags.MOD_OPEN) ||
                    PacketFlags.hasFlag(flags, PacketFlags.MOD_ABSTRACT) ||
                    PacketFlags.hasFlag(flags, PacketFlags.MOD_OVERRIDE)
            }

    val isCancellableFlowMarker =
        cls.enclosingClassFrameIndex == null &&
            cls.kotlinName == "CancellableFlow" &&
            symbols.namespaceSegments == listOf("kotlinx", "coroutines", "flow")
    val isEventLoopImplPlatformGuard =
        !bundle.isDocExample &&
            cls.enclosingClassFrameIndex == null &&
            cls.kotlinName == "EventLoopImplPlatform"
    val isNotCompletedMarker =
        !bundle.isDocExample &&
            cls.enclosingClassFrameIndex == null &&
            cls.kotlinName == "NotCompleted" &&
            symbols.namespaceSegments == listOf("kotlinx", "coroutines")
    val needsPredeclaredCancellableFlow =
        cls.enclosingClassFrameIndex == null &&
            cls.kotlinName == "AbstractFlow" &&
            symbols.namespaceSegments == listOf("kotlinx", "coroutines", "flow")
    val isSegmentClassDecl =
        !bundle.isDocExample &&
            cls.enclosingClassFrameIndex == null &&
            cls.kotlinName == "Segment" &&
            symbols.namespaceSegments == listOf("kotlinx", "coroutines", "internal")

    return buildString {
        if (isSegmentClassDecl) {
            includes.addSystem("cstdint")
            includes.addSystem("exception")
            includes.addSystem("memory")
            includes.addProject("kotlinx/coroutines/CoroutineContext.hpp")
            includes.addProject("kotlinx/coroutines/CancellableContinuationImpl.hpp")
            append(indent).append("template <>\n")
            append(indent).append("class Segment<void*> : public virtual kotlinx::coroutines::NotCompleted {\n")
            append(indent).append("public:\n")
            append(inner).append("virtual ~Segment() = default;\n")
            append(inner).append("virtual std::int32_t number_of_slots() const = 0;\n")
            append(inner)
                .append("virtual void on_cancellation(std::int32_t index, std::exception_ptr cause, ")
                .append("std::shared_ptr<kotlin::coroutines::CoroutineContext> context) = 0;\n")
            append(indent).append("};\n\n")
        }
        if (needsPredeclaredCancellableFlow) {
            append("#ifndef KXS_CANCELLABLE_FLOW_DEFINED\n")
            append("#define KXS_CANCELLABLE_FLOW_DEFINED\n")
            append("template <typename T>\nclass CancellableFlow : public virtual Flow<T> {\npublic:\n    virtual ~CancellableFlow() = default;\n};\n")
            append("#endif\n\n")
        }
        if (isCancellableFlowMarker) {
            append("#ifndef KXS_CANCELLABLE_FLOW_DEFINED\n")
            append("#define KXS_CANCELLABLE_FLOW_DEFINED\n")
        }
        if (isEventLoopImplPlatformGuard) {
            append("#ifndef KXS_EXPECT_SUPER_CLASS_EventLoopImplPlatform\n")
            append("#define KXS_EXPECT_SUPER_CLASS_EventLoopImplPlatform\n")
        }
        if (isNotCompletedMarker) {
            append("#ifndef KXS_NOT_COMPLETED_DEFINED\n")
            append("#define KXS_NOT_COMPLETED_DEFINED\n")
        }
        if (outOfLineOwnerQual == null) {
            appendHoistedNestedDefinitions(cls, indent, this)
        }
        append(renderProvenanceAndLeadingComments(declFrame, indent, includeLeadingKdoc = true))
        append(renderTemplatePrefix(cls.typeParams, indent))
        val restrictsAttr =
            if (PacketFlags.hasFlag(declFrame.flags, PacketFlags.RESTRICTS_SUSPENSION_SCOPE) && !bundle.isDocExample) {
                "[[clang::annotate(\"kotlin.coroutines.RestrictsSuspension\")]] "
            } else {
                ""
            }
        append(indent)
            .append("class ")
            .append(restrictsAttr)
            .append(headName)
            .append(inheritance)
            .append(" {\n")
        if (!bundle.isDocExample && cls.typeParams.isNotEmpty()) {
            append(inner)
                .append("template <")
                .append(cls.typeParams.joinToString(", ") { "typename" })
                .append(">\n")
            append(inner).append("friend class ").append(cls.cppName).append(";\n")
        }

        // Private fields first (`snake_case_`), deferring static object initializers that read public members
        val privateCtorProps =
            cls.primaryConstructorParams.filter { isPrivateOrBackedOverrideCtorParam(cls, it) }
        val privateMemberProps =
            cls.memberPropertyFrameIndices.filter { isPrivateOrBackedOverrideMemberProp(cls, it) }
        val deferredPrivateMemberProps = LinkedHashSet<Int>()
        if (cls.kind == KtClassKind.OBJECT_SINGLETON || cls.kind == KtClassKind.COMPANION_OBJECT) {
            val triggerNames =
                cls.memberPropertyFrameIndices
                    .filter { it !in privateMemberProps }
                    .mapNotNull { symbols.propertiesByFrame[it]?.kotlinName }
                    .toMutableSet()
            var progressed = true
            while (progressed) {
                progressed = false
                for (pIdx in privateMemberProps) {
                    if (pIdx in deferredPrivateMemberProps) continue
                    val prop = symbols.propertiesByFrame[pIdx] ?: continue
                    val initF = prop.initializerFrameIndex?.let { stream.frames[it] } ?: continue
                    if (triggerNames.any { frameMentionsIdentifier(initF, it) }) {
                        deferredPrivateMemberProps.add(pIdx)
                        triggerNames.add(prop.kotlinName)
                        progressed = true
                    }
                }
            }
        }
        if (cls.memberClassFrameIndices.isNotEmpty()) {
            val nestedClassKotlinNames =
                cls.memberClassFrameIndices.mapNotNull { symbols.classesByFrame[it]?.kotlinName }
            for (pIdx in privateMemberProps) {
                if (pIdx in deferredPrivateMemberProps) continue
                val propFrame = stream.frames[pIdx]
                if (nestedClassKotlinNames.any { nName -> frameMentionsIdentifier(propFrame, nName) }) {
                    deferredPrivateMemberProps.add(pIdx)
                }
            }
        }
        val earlyPrivateMemberProps = privateMemberProps.filter { it !in deferredPrivateMemberProps }
        if (outerCls != null ||
            delegatedInterfaces.isNotEmpty() ||
            privateCtorProps.isNotEmpty() ||
            earlyPrivateMemberProps.isNotEmpty() ||
            delegatedMap != null
        ) {
            append(indent).append("private:\n")
            if (outerCls != null) {
                append(inner)
                    .append(outerCls.cppName)
                    .append("* outer_;\n")
            }
            for (di in delegatedInterfaces) {
                if (hasPrimaryCtor) {
                    append(inner)
                        .append(di.propCppType)
                        .append(' ')
                        .append(di.fieldCppName)
                        .append(";\n")
                } else {
                    val delEmitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls)
                    val exprCode = delEmitter.emitExpression(stream.frames[di.delegateExprFrameIndex])
                    append(inner)
                        .append(di.propCppType)
                        .append(' ')
                        .append(di.fieldCppName)
                        .append(" = ")
                        .append(exprCode)
                        .append(";\n")
                }
            }
            if (delegatedMap != null) {
                includes.addSystem("memory")
                includes.addSystem("utility")
                includes.addSystem("vector")
                append(inner)
                    .append("std::vector<std::pair<")
                    .append(delegatedMap.first)
                    .append(", ")
                    .append(delegatedMap.second)
                    .append(">> delegated_entries_{};\n")
            }
            for (cp in privateCtorProps) {
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                    } ?: "std::any"
                val constQual =
                    if (!cp.isVar &&
                        !shouldOmitInstanceFieldConst(cls, cp.name, hasInlineInitializer = true)
                    ) {
                        "const "
                    } else {
                        ""
                    }
                append(inner)
                    .append(constQual)
                    .append(t)
                    .append(' ')
                    .append(CppNamingPolicy.toPrivateFieldName(cp.name))
                    .append(";\n")
            }
            for (pIdx in earlyPrivateMemberProps) {
                append(renderProperty(stream.frames[pIdx], cls, inner))
            }
        }

        append(indent).append("public:\n")
        if (isPolymorphicBase) {
            append(inner).append("virtual ~").append(cls.cppName).append("() = default;\n")
        }

        val isSingletonWithObj =
            cls.kind == KtClassKind.OBJECT_SINGLETON ||
                (cls.kind == KtClassKind.COMPANION_OBJECT && cls.superTypeFrameIndices.isNotEmpty())
        if (isSingletonWithObj && !PacketFlags.hasFlag(cls.flags, PacketFlags.MOD_ABSTRACT)) {
            val storageKw = if (cls.annotations.any { it.simpleName == "ThreadLocal" }) "thread_local" else "static"
            append(inner).append("static ").append(cls.cppName).append("& instance() {\n")
            append("$inner    $storageKw ${cls.cppName} inst{};\n")
            append(inner).append("    return inst;\n")
            append(inner).append("}\n")
            if (!bundle.isDocExample) {
                includes.addSystem("memory")
                includes.addSystem("type_traits")
                append(inner)
                    .append("static std::shared_ptr<")
                    .append(cls.cppName)
                    .append("> shared_instance() { return std::shared_ptr<")
                    .append(cls.cppName)
                    .append(">(std::shared_ptr<void>{}, std::addressof(instance())); }\n")
                append(inner)
                    .append("template <typename KxsBase, typename = std::enable_if_t<std::is_base_of_v<KxsBase, ")
                    .append(cls.cppName)
                    .append(">>> operator std::shared_ptr<KxsBase>() const { return std::shared_ptr<KxsBase>(std::shared_ptr<void>{}, const_cast<")
                    .append(cls.cppName)
                    .append("*>(this)); }\n")
            }
        }

        if (delegatedMap != null) {
            append(inner)
                .append(delegatedMap.second)
                .append("& operator[](const ")
                .append(delegatedMap.first)
                .append("& key) {\n")
            append(inner).append("    for (auto& kv : delegated_entries_) {\n")
            append(inner).append("        if constexpr (requires { kv.first == key; }) {\n")
            append(inner).append("            if (kv.first == key) return kv.second;\n")
            append(inner).append("        } else if constexpr (requires { kv.first.symbol == key.symbol; }) {\n")
            append(inner).append("            if (kv.first.symbol == key.symbol) return kv.second;\n")
            append(inner).append("        } else {\n")
            append(inner).append("            if (std::addressof(kv.first) == std::addressof(key)) return kv.second;\n")
            append(inner).append("        }\n")
            append(inner).append("    }\n")
            append(inner).append("    delegated_entries_.emplace_back(key, ").append(delegatedMap.second).append("{});\n")
            append(inner).append("    return delegated_entries_.back().second;\n")
            append(inner).append("}\n")
        }

        // Public constructor properties (`const std::string symbol;` on `Symbol`)
        val publicCtorProps =
            cls.primaryConstructorParams.filter {
                (it.isVal || it.isVar) && !isPrivateOrBackedOverrideCtorParam(cls, it)
            }
        for (cp in publicCtorProps) {
            val t =
                cp.typeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                } ?: "std::any"
            val constQual =
                if (!cp.isVar &&
                    !shouldOmitInstanceFieldConst(cls, cp.name, hasInlineInitializer = true)
                ) {
                    "const "
                } else {
                    ""
                }
            append(inner)
                .append(constQual)
                .append(t)
                .append(' ')
                .append(cp.cppName)
                .append(";\n")
        }

        // Backed `override` virtual getters/setters for primary constructor params and member properties
        val isStdErrCls = hasStdRuntimeErrorBase(cls)
        for (cp in cls.primaryConstructorParams) {
            if (!isBackedOverrideCtorParam(cls, cp)) continue
            val t =
                cp.typeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                } ?: "std::any"
            val fieldName = CppNamingPolicy.toPrivateFieldName(cp.name)
            val getterName = resolveOverridePropertyGetterName(cls, cp.name, cp.cppName)
            val overrideKw =
                if (isStdErrCls && (cp.name == "message" || cp.name == "cause")) "" else " override"
            append(inner)
                .append(t)
                .append(' ')
                .append(getterName)
                .append("() const")
                .append(overrideKw)
                .append(" { return ")
                .append(fieldName)
                .append("; }\n")
            if (cp.isVar) {
                val paramT =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.PARAMETER)
                    } ?: t
                val clean = cp.cppName.removeSuffix("_")
                append(inner)
                    .append("void set_")
                    .append(clean)
                    .append('(')
                    .append(paramT)
                    .append(" value)")
                    .append(overrideKw)
                    .append(" { ")
                    .append(fieldName)
                    .append(" = std::move(value); }\n")
            }
        }
        for (pIdx in cls.memberPropertyFrameIndices) {
            val prop = symbols.propertiesByFrame[pIdx] ?: continue
            val propFrame = stream.frames[pIdx]
            if (!isBackedOverrideMemberProp(cls, propFrame, prop)) continue
            val fieldName =
                if (prop.cppName.startsWith("kxs_atomic_")) prop.cppName else CppNamingPolicy.toPrivateFieldName(prop.kotlinName)
            val af = bundle.atomicPlan.atomicFieldsByPropFrame[pIdx]
            val t =
                if (af != null) {
                    resolveAtomicFieldRenderSpec(af, cls).elemType
                } else {
                    prop.returnTypeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN)
                    } ?: prop.initializerFrameIndex?.let {
                        inferExpressionBodyReturnType(stream.frames[it]).takeIf { r -> r != "auto" }
                    } ?: "std::any"
                }
            val getterName = resolveOverridePropertyGetterName(cls, prop.kotlinName, prop.cppName)
            val readExpr = if (af != null) "$fieldName.load(std::memory_order_seq_cst)" else fieldName
            val overrideKw =
                if (isStdErrCls && (prop.kotlinName == "message" || prop.kotlinName == "cause")) "" else " override"
            append(inner)
                .append(t)
                .append(' ')
                .append(getterName)
                .append("() const")
                .append(overrideKw)
                .append(" { return ")
                .append(readExpr)
                .append("; }\n")
            if (prop.isVar) {
                val clean = prop.cppName.removeSuffix("_")
                val writeStmt =
                    if (af != null) {
                        "$fieldName.store(std::move(value), std::memory_order_seq_cst);"
                    } else {
                        "$fieldName = std::move(value);"
                    }
                append(inner)
                    .append("void set_")
                    .append(clean)
                    .append('(')
                    .append(t)
                    .append(" value)")
                    .append(overrideKw)
                    .append(" { ")
                    .append(writeStmt)
                    .append(" }\n")
            }
        }

        // Companion object with supertypes (e.g. `Key : CoroutineContext.KeyTyped<E>`) must precede constructor initializers.
        cls.companionFrameIndex?.let { compIdx ->
            val compSym = symbols.classesByFrame[compIdx]
            if (compSym != null && compSym.superTypeFrameIndices.isNotEmpty()) {
                val renderedComp = renderClassOrObject(stream.frames[compIdx], fullQual, inner)
                if (renderedComp.isNotBlank()) append('\n').append(renderedComp)
            }
        }

        // Primary constructor declaration / inline body
        if (hasPrimaryCtor) {
            val ctorVis = if (cls.kind == KtClassKind.SEALED_CLASS) "${indent}protected:\n$inner" else "\n$inner"
            val explicitKw = if (totalCtorParams == 1) "explicit " else ""
            val outOfLineCtorQual =
                if (isHeaderMode && !emitCtorInCpp) resolveOutOfLineEnclosingQualification(cls) else null
            val ctorDepFrames =
                if (outOfLineCtorQual != null) {
                    buildList {
                        cls.delegatedSuperTypeFrameIndices.forEach { add(stream.frames[it]) }
                        cls.superCallArgsBySuperIndex.values.forEach { add(stream.frames[it]) }
                        cls.initBlockFrameIndices.forEach { add(stream.frames[it]) }
                        cls.memberPropertyFrameIndices.forEach { pIdx ->
                            val pSym = symbols.propertiesByFrame[pIdx] ?: return@forEach
                            val initIdx = pSym.initializerFrameIndex ?: return@forEach
                            val initF = stream.frames[initIdx]
                            if (referencesNonFieldCtorParam(cls, initF)) add(initF)
                        }
                    }
                } else {
                    emptyList()
                }
            val deferCtorToPostInclude =
                outOfLineCtorQual != null &&
                    ctorDepFrames.any { bodyReferencesExpectOrDeferredBottomHeader(it) }
            val anchorCtorIdx = outermostEnclosingClass(cls)?.frameIndex ?: cls.frameIndex
            val deferCtorToBottom =
                !deferCtorToPostInclude &&
                    outOfLineCtorQual != null &&
                    ctorDepFrames.any { frameReferencesLaterTopLevelDecl(it, anchorCtorIdx, cls) }
            val deferOutOfLineCtor = deferCtorToPostInclude || deferCtorToBottom
            val inlineInitList =
                if (!emitCtorInCpp) {
                    includes.addSystem("utility")
                    buildConstructorInitializerList(cls, suffix = "_value", delegatedInterfaces = delegatedInterfaces)
                } else {
                    null
                }
            val inlineCtorBody =
                if (!emitCtorInCpp) {
                    val bodyIndent = if (deferOutOfLineCtor) "" else inner
                    renderPrimaryConstructorBody(cls, suffix = "_value", indent = bodyIndent)
                } else {
                    null
                }
            val inlineInitAndBodyText =
                if (inlineInitList != null && inlineCtorBody != null) {
                    "${inlineInitList.joinToString(", ")}\n$inlineCtorBody"
                } else {
                    null
                }
            val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls, inDefaultParameterExpr = true)
            val sigParts = ArrayList<String>(totalCtorParams)
            val defSigParts = ArrayList<String>(totalCtorParams)
            if (outerCls != null) {
                val outerUnused =
                    if (inlineInitAndBodyText != null &&
                        !CppComments.containsWholeWordInCode(inlineInitAndBodyText, "outer_value")
                    ) {
                        "[[maybe_unused]] "
                    } else {
                        ""
                    }
                sigParts.add(
                    if (deferOutOfLineCtor) {
                        "${outerCls.cppName}* outer_value"
                    } else {
                        "$outerUnused${outerCls.cppName}* outer_value"
                    },
                )
                defSigParts.add("$outerUnused${outerCls.cppName}* outer_value")
            }
            for (cp in cls.primaryConstructorParams) {
                val t =
                    cp.typeFrameIndex?.let {
                        bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.VALUE)
                    } ?: "std::any"
                val defIdx = cp.defaultExprFrameIndex
                val def =
                    if (defIdx != null) {
                        val rawDef = emitter.emitExpression(stream.frames[defIdx])
                        val coercedDef =
                            if (rawDef == "nullptr" && t.startsWith("std::optional<")) {
                                includes.addSystem("optional")
                                "std::nullopt"
                            } else {
                                rawDef
                            }
                        " = $coercedDef"
                    } else {
                        ""
                    }
                val paramName = "${cp.cppName}_value"
                val unusedAttr =
                    if (inlineInitAndBodyText != null &&
                        !cp.isVal &&
                        !cp.isVar &&
                        !CppComments.containsWholeWordInCode(inlineInitAndBodyText, paramName)
                    ) {
                        "[[maybe_unused]] "
                    } else {
                        ""
                    }
                sigParts.add(
                    if (deferOutOfLineCtor) {
                        "$t $paramName$def"
                    } else {
                        "$unusedAttr$t $paramName$def"
                    },
                )
                defSigParts.add("$unusedAttr$t $paramName")
            }
            val ctorSigParams = sigParts.joinToString(", ")
            append(ctorVis)
                .append(explicitKw)
                .append(cls.cppName)
                .append('(')
                .append(ctorSigParams)
                .append(')')
            if (emitCtorInCpp) {
                append(";\n")
            } else if (deferOutOfLineCtor) {
                append(";\n")
                val ctorDef =
                    buildString {
                        val outerTps = outOfLineCtorQual.first
                        if (outerTps.isNotEmpty()) {
                            append(renderTemplatePrefix(outerTps, ""))
                        } else {
                            append("inline ")
                        }
                        append(outOfLineCtorQual.second)
                            .append("::")
                            .append(cls.cppName)
                            .append('(')
                            .append(defSigParts.joinToString(", "))
                            .append(')')
                        if (inlineInitList != null && inlineInitList.isNotEmpty()) {
                            append(" : ").append(inlineInitList.joinToString(", "))
                        }
                        append(' ').append(inlineCtorBody ?: "{}").append('\n')
                    }
                if (deferCtorToPostInclude) {
                    postIncludeHeaderBodies.add(ctorDef)
                } else {
                    deferredHeaderBodies.add(ctorDef)
                }
            } else {
                if (inlineInitList != null && inlineInitList.isNotEmpty()) {
                    append(" : ").append(inlineInitList.joinToString(", "))
                }
                append(' ').append(inlineCtorBody ?: "{}").append('\n')
            }
            if (cls.kind == KtClassKind.SEALED_CLASS) {
                append(inner)
                    .append(cls.cppName)
                    .append("(const ")
                    .append(cls.cppName)
                    .append("&) = default;\n")
                append(inner)
                    .append(cls.cppName)
                    .append('(')
                    .append(cls.cppName)
                    .append("&&) = default;\n")
                append(inner)
                    .append(cls.cppName)
                    .append("& operator=(const ")
                    .append(cls.cppName)
                    .append("&) = default;\n")
                append(inner)
                    .append(cls.cppName)
                    .append("& operator=(")
                    .append(cls.cppName)
                    .append("&&) = default;\n")
                append(indent).append("public:\n")
                if (cls.superTypeFrameIndices.isEmpty() && cls.primaryConstructorParams.isEmpty() && cls.memberPropertyFrameIndices.isEmpty()) {
                    append(inner).append("bool operator==(const ").append(cls.cppName).append("&) const = default;\n")
                }
            }
        } else if (cls.kind == KtClassKind.SEALED_CLASS) {
            append(indent).append("protected:\n")
            append(inner).append(cls.cppName).append("() = default;\n")
            append(inner)
                .append(cls.cppName)
                .append("(const ")
                .append(cls.cppName)
                .append("&) = default;\n")
            append(inner)
                .append(cls.cppName)
                .append('(')
                .append(cls.cppName)
                .append("&&) = default;\n")
            append(inner)
                .append(cls.cppName)
                .append("& operator=(const ")
                .append(cls.cppName)
                .append("&) = default;\n")
            append(inner)
                .append(cls.cppName)
                .append("& operator=(")
                .append(cls.cppName)
                .append("&&) = default;\n")
            append(indent).append("public:\n")
            if (cls.superTypeFrameIndices.isEmpty() && cls.memberPropertyFrameIndices.isEmpty()) {
                append(inner).append("bool operator==(const ").append(cls.cppName).append("&) const = default;\n")
            }
        } else if (!bundle.isDocExample && hasStdRuntimeErrorBase(cls)) {
            append('\n').append(inner).append(cls.cppName).append("() : std::runtime_error(\"\") {}\n")
        }

        // Secondary constructors
        val renderedSecondaryCtors = renderSecondaryConstructors(declFrame, cls, inner)
        if (renderedSecondaryCtors.isNotBlank()) {
            append(renderedSecondaryCtors)
        }

        // Runtime-error exception helper getters
        if (!bundle.isDocExample && hasStdRuntimeErrorBase(cls)) {
            val hasOwnMessage =
                cls.primaryConstructorParams.any { it.name == "message" } ||
                    cls.memberPropertyFrameIndices.any { symbols.propertiesByFrame[it]?.kotlinName == "message" }
            if (!hasOwnMessage) {
                includes.addSystem("string")
                append(inner).append("std::string message() const { return this->what(); }\n")
            }
        }

        // Delegated non-Map interface forwarding methods
        if (delegatedInterfaces.isNotEmpty()) {
            append(renderDelegatedInterfaceForwardingMethods(cls, delegatedInterfaces, inner))
        }
        if (!bundle.isDocExample && cls.kind != KtClassKind.INTERFACE) {
            append(renderSiblingBaseInterfaceForwarding(cls, delegatedInterfaces, inner))
        }
        if (!bundle.isDocExample && cls.superTypeFrameIndices.isNotEmpty()) {
            append(renderOverloadedVirtualUsings(cls, delegatedInterfaces, inner))
        }

        // Nested classes (forward-declare sibling nested classes referenced earlier & defer subclasses of `cls`)
        if (cls.memberClassFrameIndices.isNotEmpty()) {
            for ((pos, nestedIdx) in cls.memberClassFrameIndices.withIndex()) {
                val siblingCls = symbols.classesByFrame[nestedIdx] ?: continue
                if ((cls.kotlinName to siblingCls.kotlinName) in symbols.nonInnerNestedRewrites) continue
                val referencedInCtor =
                    cls.primaryConstructorParams.any { cp ->
                        cp.typeFrameIndex?.let { frameMentionsIdentifier(stream.frames[it], siblingCls.kotlinName) } == true ||
                            cp.defaultExprFrameIndex?.let { frameMentionsIdentifier(stream.frames[it], siblingCls.kotlinName) } == true
                    }
                val referencedInProps =
                    cls.memberPropertyFrameIndices.any { pIdx ->
                        frameMentionsIdentifier(stream.frames[pIdx], siblingCls.kotlinName)
                    }
                val referencedInFns =
                    cls.memberFunctionFrameIndices.any { fIdx ->
                        frameMentionsIdentifier(stream.frames[fIdx], siblingCls.kotlinName)
                    }
                val referencedInEarlierNested =
                    cls.memberClassFrameIndices.subList(0, pos).any { earlierIdx ->
                        frameMentionsIdentifier(stream.frames[earlierIdx], siblingCls.kotlinName)
                    }
                val referencedBefore = referencedInCtor || referencedInProps || referencedInFns || referencedInEarlierNested
                if (referencedBefore && !inheritsFromEnclosing(siblingCls, cls)) {
                    append('\n')
                    append(renderTemplatePrefix(siblingCls.typeParams, inner))
                    val kw =
                        if (siblingCls.kind == KtClassKind.SIMPLE_ENUM || siblingCls.kind == KtClassKind.RICH_ENUM) {
                            "enum class"
                        } else {
                            "class"
                        }
                    append(inner)
                        .append(kw)
                        .append(' ')
                        .append(siblingCls.cppName)
                        .append(";\n")
                }
            }
        }
        val deferredOutOfLineNested = ArrayList<Int>()
        for (nestedIdx in cls.memberClassFrameIndices) {
            val nestedCls = symbols.classesByFrame[nestedIdx]
            if (nestedCls != null && (cls.kotlinName to nestedCls.kotlinName) in symbols.nonInnerNestedRewrites) {
                append('\n')
                    .append(inner)
                    .append("using ")
                    .append(CppNamingPolicy.toTypeName(nestedCls.kotlinName))
                    .append(" = ")
                    .append(nestedCls.cppName)
                    .append(";\n")
            } else if (nestedCls != null && inheritsFromEnclosing(nestedCls, cls)) {
                append('\n')
                append(renderTemplatePrefix(nestedCls.typeParams, inner))
                val kw =
                    if (nestedCls.kind == KtClassKind.SIMPLE_ENUM || nestedCls.kind == KtClassKind.RICH_ENUM) {
                        "enum class"
                    } else {
                        "class"
                    }
                append(inner)
                    .append(kw)
                    .append(' ')
                    .append(nestedCls.cppName)
                    .append(";\n")
                deferredOutOfLineNested.add(nestedIdx)
            } else {
                val rendered = renderClassOrObject(stream.frames[nestedIdx], fullQual, inner)
                if (rendered.isNotBlank()) append('\n').append(rendered)
            }
        }

        val scopeSigs = collectClassScopeNormalizedSigs(cls)
        // Companion object static constants and factories lifted onto the enclosing class
        renderClassCompanionPropsAndFnsInHeader(cls, fullQual, inner, scopeSigs, this)

        // Public/non-private member properties
        for (pIdx in cls.memberPropertyFrameIndices) {
            if (pIdx in privateMemberProps) continue
            val rendered = renderProperty(stream.frames[pIdx], cls, inner)
            if (rendered.isNotBlank()) append('\n').append(rendered)
        }
        if (deferredPrivateMemberProps.isNotEmpty()) {
            append('\n').append(indent).append("private:\n")
            for (pIdx in privateMemberProps) {
                if (pIdx !in deferredPrivateMemberProps) continue
                val rendered = renderProperty(stream.frames[pIdx], cls, inner)
                if (rendered.isNotBlank()) append(rendered)
            }
            append('\n').append(indent).append("public:\n")
        }

        // Member functions
        for (mFnIdx in cls.memberFunctionFrameIndices) {
            val rendered = renderFunction(stream.frames[mFnIdx], cls, fullQual, inner, scopeSigs)
            if (rendered.isNotBlank()) append('\n').append(rendered)
        }
        val cmpHelpers = renderComparableClassHelpers(cls, inner)
        if (cmpHelpers.isNotBlank()) append('\n').append(cmpHelpers)

        // Disambiguate diamond virtual `key()` when inheriting both `AbstractCoroutineContextElement` and `ContinuationInterceptor`
        val superNames =
            cls.superTypeFrameIndices.mapNotNull {
                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it])
            }
        if ("AbstractCoroutineContextElement" in superNames && "ContinuationInterceptor" in superNames) {
            append('\n')
                .append(inner)
                .append("kotlin::coroutines::CoroutineContext::Key* key() const override { return AbstractCoroutineContextElement::key(); }\n")
        }
        if (cls.kotlinName == "Symbol" && symbols.namespaceSegments == listOf("kotlinx", "coroutines", "internal")) {
            append('\n')
                .append(inner)
                .append("bool operator==(const Symbol& other) const noexcept { return symbol == other.symbol; }\n")
        }
        if (cls.kotlinName == "JobImpl" && cls.enclosingClassFrameIndex == null) {
            includes.addSystem("memory")
            append('\n')
                .append(inner)
                .append("static std::shared_ptr<JobImpl> create(std::shared_ptr<Job> parent = nullptr) { return std::make_shared<JobImpl>(std::move(parent)); }\n")
        }
        if (!bundle.isDocExample &&
            isHeaderMode &&
            cls.enclosingClassFrameIndex == null &&
            cls.kind == KtClassKind.INTERFACE
        ) {
            when (cls.kotlinName) {
                "Job" -> {
                    includes.needsSuspendBoxHelpersDef = true
                    append('\n')
                        .append(inner)
                        .append("void* join(kotlin::coroutines::Continuation<void*>* completion) { ")
                        .append("return join(kxs_wrap_raw_continuation(completion)); }\n")
                }
                "Deferred" -> {
                    if (cls.typeParams.size == 1) {
                        includes.needsSuspendBoxHelpersDef = true
                        append('\n')
                            .append(inner)
                            .append("void* await(kotlin::coroutines::Continuation<void*>* completion) { ")
                            .append("return await(kxs_wrap_raw_continuation(completion)); }\n")
                    }
                }
            }
        }

        append(indent).append("};\n")
        if (isCancellableFlowMarker || isEventLoopImplPlatformGuard || isNotCompletedMarker) {
            append("#endif\n")
        }

        for (deferredIdx in deferredOutOfLineNested) {
            val rendered =
                renderClassOrObject(
                    declFrame = stream.frames[deferredIdx],
                    qualifierPrefix = fullQual,
                    indent = indent,
                    outOfLineOwnerQual = currentScopeQualForNested,
                )
            if (rendered.isNotBlank()) {
                append('\n').append(rendered)
            }
        }
    }
}

internal fun CppDeclarationRenderer.inheritsFromEnclosing(
    nestedCls: KtClassSymbol,
    enclosingCls: KtClassSymbol,
): Boolean =
    nestedCls.superTypeFrameIndices.any { trIdx ->
        val rawBase = bundle.typeTranslator.translateTypeFrame(stream.frames[trIdx], CppTypePosition.TYPE_ARGUMENT)
        val simpleBase = rawBase.substringBefore('<').substringAfterLast("::").trim()
        simpleBase == enclosingCls.cppName || simpleBase == enclosingCls.kotlinName
    }

internal fun CppDeclarationRenderer.extractDelegatedMapTypes(cls: KtClassSymbol): Pair<String, String>? {
    for (trIdx in cls.delegatedSuperTypeFrameIndices) {
        val trFrame = stream.frames[trIdx]
        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, trFrame)
        if (simple == "MutableMap" || simple == "Map") {
            val userType = FrameAstHelpers.firstChildFrame(stream, trFrame, KtNodeTypes.USER_TYPE_ID) ?: continue
            val argList = FrameAstHelpers.firstChildFrame(stream, userType, KtNodeTypes.TYPE_ARGUMENT_LIST_ID) ?: continue
            val projs = FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.TYPE_PROJECTION_ID)
            if (projs.size >= 2) {
                val kRef = FrameAstHelpers.firstChildFrame(stream, projs[0], KtNodeTypes.TYPE_REFERENCE_ID)
                val vRef = FrameAstHelpers.firstChildFrame(stream, projs[1], KtNodeTypes.TYPE_REFERENCE_ID)
                val kCpp = kRef?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) } ?: "std::any"
                val vCpp = vRef?.let { bundle.typeTranslator.translateTypeFrame(it, CppTypePosition.TYPE_ARGUMENT) } ?: "std::any"
                return kCpp to vCpp
            }
        }
    }
    return null
}

internal fun CppDeclarationRenderer.renderInheritanceClause(cls: KtClassSymbol): String {
    val nonDelegatedSupers =
        cls.superTypeFrameIndices.filterNot { trIdx ->
            if (trIdx !in cls.delegatedSuperTypeFrameIndices) {
                false
            } else {
                val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[trIdx])
                simple == "MutableMap" || simple == "Map"
            }
        }
    val bases =
        nonDelegatedSupers
            .map { trIdx ->
                val (translatedBase, isStdRuntimeError) = bundle.typeTranslator.translateSuperBaseType(stream.frames[trIdx])
                val rawBase =
                    if (!bundle.isDocExample &&
                        cls.kotlinName == "Segment" &&
                        cls.enclosingClassFrameIndex == null &&
                        symbols.namespaceSegments == listOf("kotlinx", "coroutines", "internal") &&
                        translatedBase == "NotCompleted"
                    ) {
                        "Segment<void*>"
                    } else {
                        translatedBase
                    }
                val isVirtualInterfaceBase =
                    !isStdRuntimeError &&
                        (cls.kind == KtClassKind.INTERFACE || trIdx !in cls.superCallArgsBySuperIndex) &&
                        !rawBase.startsWith("CoroutineContext::Key")
                if (isVirtualInterfaceBase) "public virtual $rawBase" else "public $rawBase"
            }.toMutableList()
    val needsSharedThis =
        !bundle.isDocExample &&
            cls.enclosingClassFrameIndex == null &&
            (
                cls.kotlinName == "CancellableContinuationImpl" ||
                    cls.kotlinName == "ConcurrentLinkedListNode" ||
                    cls.kotlinName == "LockFreeLinkedListNode"
            )
    if (needsSharedThis) {
        includes.addSystem("memory")
        val selfParam =
            if (cls.typeParams.isEmpty()) {
                cls.cppName
            } else {
                "${cls.cppName}<${cls.typeParams.joinToString(", ") { it.name }}>"
            }
        bases.add("public std::enable_shared_from_this<$selfParam>")
    }
    if (bases.isEmpty()) return ""
    return " : ${bases.joinToString(", ")}"
}

internal fun CppDeclarationRenderer.renderPrimaryConstructorBody(
    cls: KtClassSymbol,
    suffix: String,
    indent: String,
): String {
    if (cls.initBlockFrameIndices.isEmpty()) return "{}"
    val emitter = CppExprAndStmtEmitter(bundle, enclosingClass = cls)
    for (cp in cls.primaryConstructorParams) {
        emitter.bindConstructorParam(cp, suffix)
    }
    val stmts = ArrayList<String>()
    for (initIdx in cls.initBlockFrameIndices) {
        val initFrame = stream.frames[initIdx]
        val blockFrame = FrameAstHelpers.firstChildFrame(stream, initFrame, KtNodeTypes.BLOCK_ID) ?: continue
        val blockText = emitter.emitBlock(blockFrame, indent = indent, isValueBlock = false, resultTargetVar = null)
        val innerBody = blockText.removePrefix("{").removeSuffix("}").trim('\n')
        if (innerBody.isNotBlank()) {
            stmts.add(innerBody)
        }
    }
    if (stmts.isEmpty()) return "{}"
    return "{\n${stmts.joinToString("\n")}\n$indent}"
}
