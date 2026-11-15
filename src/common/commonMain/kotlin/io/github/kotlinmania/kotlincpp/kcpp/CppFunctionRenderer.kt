package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.STAR_STD_ANY_IN_ANGLE_REGEX
import io.github.kotlinmania.kotlincpp.kcpp.CppDeclarationRenderer.Companion.VALUE_PASS_CPP_TYPES
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.KtTokens
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame

internal fun CppDeclarationRenderer.renderFunction(
    fnFrame: PacketFrame,
    enclosingClass: KtClassSymbol?,
    qualifierPrefix: String,
    indent: String,
    scopeSeenNormalizedSigs: MutableSet<String> = HashSet(),
): String {
    val sym = symbols.functionsByFrame[fnFrame.frameIndex] ?: return ""
    val liveFlags = fnFrame.flags

    // Special dual-ABI leaf `suspendCoroutineUninterceptedOrReturn` intrinsic (`Yield.hpp` + `Yield.cpp`)
    bundle.suspendPlan.leafIntrinsicsByFunFrame[fnFrame.frameIndex]?.let { leaf ->
        return renderLeafContinuationIntrinsicFunction(fnFrame, sym, enclosingClass, leaf, qualifierPrefix, indent)
    }

    val emitInCpp = PacketFlags.hasFlag(liveFlags, PacketFlags.EMIT_IN_CPP_BODY)
    val emitInlineInHpp = PacketFlags.hasFlag(liveFlags, PacketFlags.EMIT_IN_HPP_INLINE_BODY)
    val isPureDecl = sym.bodyFrameIndex == null && fnFrame.frameIndex !in bundle.ffiPlan.inlineCppByFrame
    val isExpectFun = FrameAstHelpers.hasModifierKeyword(stream, fnFrame, KtTokens.EXPECT_MODIFIER_ID)

    if (!isHeaderMode && !emitInCpp) return ""
    if (isExpectFun && isPureDecl) {
        includes.hasExpectDeclarations = true
        if (sym.receiverTypeFrameIndex != null) {
            val recvSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[sym.receiverTypeFrameIndex])
            val recvCls = recvSimple?.let { symbols.classesByName[it] }
            if (recvCls != null && isOmittedExpectClass(recvCls)) {
                val prefix = renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true)
                return "$prefix$indent// expect fun ${sym.cppName}\n"
            }
        }
    }

    val collapsedVarianceTp = resolveCollapsedVarianceEscapeTypeParam(sym, enclosingClass)
    val baseTypeParams = if (collapsedVarianceTp != null) emptyList() else sym.typeParams

    val comparableElem = resolveComparableElemType(enclosingClass)
    val isComparableOverride =
        !bundle.isDocExample &&
            comparableElem != null &&
            sym.kotlinName == "compareTo" &&
            sym.valueParams.size == 1 &&
            sym.typeParams.isEmpty() &&
            PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE)

    val initialRawRetType =
        when {
            isComparableOverride -> "int"
            !bundle.isDocExample && sym.kotlinName == "createTestResult" -> "TestResult"
            sym.returnTypeFrameIndex != null ->
                bundle.typeTranslator.translateTypeFrame(
                    stream.frames[sym.returnTypeFrameIndex],
                    CppTypePosition.RETURN,
                )
            sym.isExpressionBody && sym.bodyFrameIndex != null ->
                inferFunctionExpressionBodyReturnType(sym, enclosingClass, stream.frames[sym.bodyFrameIndex])
            else -> "void"
        }
    val isNoreturn = initialRawRetType.startsWith("[[noreturn]] ")
    val baseRetType = initialRawRetType.removePrefix("[[noreturn]] ")
    val overrideNonVoidRet =
        if ((baseRetType == "auto" || (isNoreturn && baseRetType == "void")) &&
            PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE) &&
            enclosingClass != null
        ) {
            resolveSuperMethodReturnType(enclosingClass, sym, allowVoid = baseRetType == "auto")
        } else {
            null
        }
    val preCopyRetType = overrideNonVoidRet ?: baseRetType
    val copyableRawRetType =
        if (!bundle.isDocExample &&
            sym.kotlinName == "createCopy" &&
            sym.valueParams.isEmpty() &&
            PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE) &&
            !preCopyRetType.startsWith("std::shared_ptr<") &&
            preCopyRetType != "void" &&
            preCopyRetType != "auto"
        ) {
            preCopyRetType
        } else {
            null
        }
    val unwrappedRetType =
        if (copyableRawRetType != null) {
            includes.addSystem("memory")
            "std::shared_ptr<$copyableRawRetType>"
        } else {
            preCopyRetType
        }
    val rawRetType = if (isNoreturn) "[[noreturn]] $unwrappedRetType" else unwrappedRetType
    val retTypeNoShadow = unwrappedRetType
    val isVoidReturn = unwrappedRetType == "void"

    val isDepHidden = isDeprecatedHiddenFun(sym)
    val baseMethodName = resolveCppFunctionOrOperatorName(sym, enclosingClass)
    val hasClassPropCollision =
        enclosingClass != null &&
            !isDepHidden &&
            sym.valueParams.isEmpty() &&
            PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_PRIVATE) &&
            (
                enclosingClass.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == sym.kotlinName } ||
                    enclosingClass.memberPropertyFrameIndices.any {
                        symbols.propertiesByFrame[it]?.kotlinName == sym.kotlinName
                    }
            )
    val methodName =
        when {
            isDepHidden -> "${baseMethodName}_deprecated_hidden_${sym.valueParams.size}"
            hasClassPropCollision -> "${baseMethodName}_fn"
            else -> baseMethodName
        }
    val isInterfaceMethod = enclosingClass?.kind == KtClassKind.INTERFACE
    val isCAbi = sym.cAbiSymbolName != null
    val isHashCodeOverrideOnCancellationExc =
        sym.kotlinName == "hashCode" &&
            sym.valueParams.isEmpty() &&
            enclosingClass != null &&
            isCancellationExceptionSubclass(enclosingClass)
    val isEqualsOverrideOnCancellationExc =
        sym.kotlinName == "equals" &&
            sym.valueParams.size == 1 &&
            enclosingClass != null &&
            isCancellationExceptionSubclass(enclosingClass)
    val superDeclaresToString =
        sym.kotlinName == "toString" &&
            sym.valueParams.isEmpty() &&
            enclosingClass != null &&
            superDeclaresMethod(enclosingClass, "toString")
    val superDeclaresHashCode =
        sym.kotlinName == "hashCode" &&
            sym.valueParams.isEmpty() &&
            enclosingClass != null &&
            superDeclaresMethod(enclosingClass, "hashCode")
    val superDeclaresEquals =
        sym.kotlinName == "equals" &&
            sym.valueParams.size == 1 &&
            enclosingClass != null &&
            superDeclaresMethod(enclosingClass, "equals")
    val isToStringRoot =
        sym.kotlinName == "toString" &&
            sym.valueParams.isEmpty() &&
            !superDeclaresToString
    val isHashCodeRoot =
        sym.kotlinName == "hashCode" &&
            sym.valueParams.isEmpty() &&
            !isHashCodeOverrideOnCancellationExc &&
            !superDeclaresHashCode
    val isEqualsRoot =
        sym.kotlinName == "equals" &&
            sym.valueParams.size == 1 &&
            !isEqualsOverrideOnCancellationExc &&
            !superDeclaresEquals
    val isKotlinAnyRootMethod = isToStringRoot || isHashCodeRoot || isEqualsRoot
    val hasBaseVirtualTrait =
        isInterfaceMethod ||
            PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
            PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) ||
            PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE)
    val isSuspend = PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_SUSPEND)

    val starPlan =
        if (!bundle.isDocExample &&
            !isCAbi &&
            (emitInlineInHpp || (isPureDecl && isExpectFun)) &&
            !isSuspend &&
            baseTypeParams.isEmpty() &&
            !hasBaseVirtualTrait
        ) {
            buildStarProjectionPlan(sym)
        } else {
            StarProjectionPlan(emptyList(), null, emptyMap())
        }
    val effectiveTypeParams = baseTypeParams + starPlan.synthesizedTypeParams
    val enclosingTpNames = enclosingClass?.typeParams?.mapTo(HashSet()) { it.name }.orEmpty()
    val shadowTpRenames = LinkedHashMap<String, String>()
    val renderedTypeParams =
        effectiveTypeParams.map { tp ->
            if (tp.name in enclosingTpNames) {
                val renamed = "KxsFn${tp.name}"
                shadowTpRenames[tp.name] = renamed
                tp.copy(name = renamed)
            } else {
                tp
            }
        }
    val retType = replaceWholeIdentifierOutsideStrings(retTypeNoShadow, shadowTpRenames)
    val rawRetTypeEffective = replaceWholeIdentifierOutsideStrings(rawRetType, shadowTpRenames)
    val isDualAbiSuspend = isSuspend && !isCAbi && !bundle.isDocExample
    val forceInlineAuto = retType == "auto" && !isPureDecl && !isDualAbiSuspend
    if (!isHeaderMode && forceInlineAuto) return ""
    val effectiveEmitInlineInHpp = emitInlineInHpp || forceInlineAuto

    val activeTps = LinkedHashSet<String>()
    collectEnclosingClassTypeParamNames(enclosingClass, activeTps)
    effectiveTypeParams.forEach { activeTps.add(it.name) }

    val canBeVirtual = renderedTypeParams.isEmpty() && retType != "auto" && !isDepHidden
    val isVirtualMethod =
        canBeVirtual &&
            (
                isInterfaceMethod ||
                    PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
                    PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) ||
                    (isKotlinAnyRootMethod && PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE))
            )
    val isOverride =
        isComparableOverride ||
            isEqualsOverrideOnCancellationExc ||
            (
                canBeVirtual &&
                    (
                        !isKotlinAnyRootMethod ||
                            isHashCodeOverrideOnCancellationExc ||
                            superDeclaresToString ||
                            superDeclaresHashCode ||
                            superDeclaresEquals
                    ) &&
                    PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE) &&
                    enclosingClass != null &&
                    enclosingClass.superTypeFrameIndices.isNotEmpty()
            )
    val isStaticOnObject =
        (enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT) &&
            !isOverride &&
            !(isKotlinAnyRootMethod && PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE))
    val isConstMethod =
        isComparableOverride || isEqualsOverrideOnCancellationExc || shouldMarkMethodConst(sym, enclosingClass)
    var suspendBoxType: String? = null
    var suspendAuthoringRetType = retTypeNoShadow
    var hasBoxedResult = false
    if (isDualAbiSuspend) {
        includes.needsSuspendBoxHelpersDef = true
        val isRetUnit =
            isVoidReturn ||
                retTypeNoShadow == "Unit" ||
                retTypeNoShadow == "kotlin::Unit" ||
                retTypeNoShadow == "kotlinx::coroutines::Unit"
        val mentionsTp =
            effectiveTypeParams.any { containsWholeWord(retTypeNoShadow, it.name) } ||
                enclosingClass?.typeParams?.any { containsWholeWord(retTypeNoShadow, it.name) } == true
        if (retTypeNoShadow == "void*") {
            suspendBoxType = null
            suspendAuthoringRetType = "void*"
            hasBoxedResult = false
        } else if (mentionsTp) {
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            val safeBox = "std::conditional_t<std::is_void_v<$retTypeNoShadow>, kotlin::Unit, $retTypeNoShadow>"
            suspendBoxType = safeBox
            suspendAuthoringRetType = safeBox
            hasBoxedResult = true
        } else if (isRetUnit) {
            suspendBoxType = null
            suspendAuthoringRetType = "void"
            hasBoxedResult = false
        } else {
            val safeRet =
                if (retTypeNoShadow == "auto") {
                    includes.addSystem("any")
                    "std::any"
                } else {
                    retTypeNoShadow
                }
            suspendBoxType = safeRet
            suspendAuthoringRetType = safeRet
            hasBoxedResult = true
        }
    }
    val renderedSuspendAuthoringRetType =
        replaceWholeIdentifierOutsideStrings(suspendAuthoringRetType, shadowTpRenames)
    val authoringAnn =
        if (hasBoxedResult) {
            "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]"
        } else {
            "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"
        }

    fun appendCompletionParam(
        baseParams: String,
        completionUnused: Boolean,
    ): String {
        val contDecl =
            if (completionUnused) {
                "[[maybe_unused]] std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
            } else {
                "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
            }
        return if (baseParams.isEmpty()) contDecl else "$baseParams, $contDecl"
    }

    if (isHeaderMode) {
        val outOfLineFnQual = resolveOutOfLineEnclosingQualification(enclosingClass)
        val canDeferReturnType = isDualAbiSuspend || retType != "auto"
        val deferToPostInclude =
            canDeferReturnType &&
                effectiveEmitInlineInHpp &&
                !isPureDecl &&
                !isCAbi &&
                sym.bodyFrameIndex != null &&
                (enclosingClass == null || outOfLineFnQual != null) &&
                bodyReferencesExpectOrDeferredBottomHeader(fnFrame)
        val deferHeaderBody =
            canDeferReturnType &&
                !deferToPostInclude &&
                effectiveEmitInlineInHpp &&
                !isPureDecl &&
                !isCAbi &&
                sym.bodyFrameIndex != null &&
                (enclosingClass == null || outOfLineFnQual != null) &&
                bodyReferencesLaterTopLevelDecl(
                    bodyFrame = stream.frames[sym.bodyFrameIndex],
                    anchorDeclFrameIndex = outermostEnclosingClass(enclosingClass)?.frameIndex ?: fnFrame.frameIndex,
                    sym = sym,
                    enclosingClass = enclosingClass,
                )
        val hasInlineHeaderBody =
            (!isPureDecl && effectiveEmitInlineInHpp) || (isPureDecl && isDepHidden && enclosingClass != null)
        val preRenderedHeaderBody =
            if (!isPureDecl && effectiveEmitInlineInHpp) {
                val headerIndent = if (deferToPostInclude || deferHeaderBody) "" else indent
                val rawBody =
                    renderFunctionBody(
                        sym = sym,
                        enclosingClass = enclosingClass,
                        activeTypeParams = activeTps,
                        isVoidReturn = isVoidReturn,
                        indent = headerIndent,
                        collapsedVarianceTp = collapsedVarianceTp,
                        isSuspendBody = isDualAbiSuspend,
                        suspendBoxType = suspendBoxType,
                        forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                        comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                        wrapReturnInMakeSharedOf = copyableRawRetType,
                    )
                val ensuredBody = ensureCreateTestResultReturn(rawBody, sym, isDualAbiSuspend, headerIndent)
                val noreturnSafeBody =
                    ensureNoreturnUnreachable(ensuredBody, isNoreturn && !isDualAbiSuspend, headerIndent)
                replaceWholeIdentifierOutsideStrings(noreturnSafeBody, shadowTpRenames)
            } else {
                null
            }
        val isTemplateInterfaceStub =
            isPureDecl &&
                (isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                !canBeVirtual &&
                renderedTypeParams.isNotEmpty()
        val unusedHeaderParamNames =
            if (isTemplateInterfaceStub || (isPureDecl && isDepHidden && enclosingClass != null)) {
                val unused = sym.valueParams.mapTo(HashSet()) { it.cppName }
                if (sym.receiverTypeFrameIndex != null) unused.add("self")
                unused
            } else if (!hasInlineHeaderBody) {
                emptySet()
            } else if (preRenderedHeaderBody != null) {
                val unused =
                    sym.valueParams
                        .filter { vp -> !CppComments.containsWholeWordInCode(preRenderedHeaderBody, vp.cppName) }
                        .mapTo(HashSet()) { it.cppName }
                if (sym.receiverTypeFrameIndex != null &&
                    !CppComments.containsWholeWordInCode(preRenderedHeaderBody, "self")
                ) {
                    unused.add("self")
                }
                unused
            } else {
                emptySet()
            }
        val rawParamsRendered =
            buildFunctionParameterList(
                sym = sym,
                includeDefaults = isHeaderMode && !isDepHidden,
                starPlan = starPlan,
                unusedParamCppNames =
                    if (!isTemplateInterfaceStub && (isDualAbiSuspend || deferToPostInclude || deferHeaderBody)) {
                        emptySet()
                    } else {
                        unusedHeaderParamNames
                    },
                isSuspend = isDualAbiSuspend,
                forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
            )
        val paramsRendered =
            rawParamsRendered
                .let { p ->
                    if (collapsedVarianceTp != null) {
                        replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                    } else {
                        p
                    }
                }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
        val declDefaultedVoidPtrTps =
            if (isHeaderMode &&
                !isPureDecl &&
                !isOverride &&
                !isDualAbiSuspend &&
                !deferToPostInclude &&
                !deferHeaderBody &&
                sym.receiverTypeFrameIndex == null &&
                enclosingClass?.kind == KtClassKind.COMPANION_OBJECT &&
                renderedTypeParams.isNotEmpty()
            ) {
                val trailing = LinkedHashSet<String>()
                for (tp in renderedTypeParams.asReversed()) {
                    if (containsWholeWord(paramsRendered, tp.name)) break
                    trailing.add(tp.name)
                }
                trailing
            } else {
                emptySet()
            }
        return buildString {
            append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
            if (isDualAbiSuspend) {
                // 1. Authoring overload (non-virtual declaration-only `;` with `kxs_implicit_continuation`)
                append(renderTemplatePrefix(renderedTypeParams, indent))
                append(indent).append(authoringAnn).append('\n')
                append(indent)
                if (isNoreturn) append("[[noreturn]] ")
                if (isStaticOnObject) append("static ")
                append(renderedSuspendAuthoringRetType)
                    .append(' ')
                    .append(methodName)
                    .append('(')
                    .append(paramsRendered)
                    .append(')')
                if (isConstMethod) append(" const")
                append(";\n\n")

                // 2. Lowered ABI overload (`void*` + `_kxs_completion`)
                val rawLoweredParams =
                    buildFunctionParameterList(
                        sym = sym,
                        includeDefaults = false,
                        starPlan = starPlan,
                        unusedParamCppNames =
                            if (!isTemplateInterfaceStub && (deferToPostInclude || deferHeaderBody || !hasInlineHeaderBody)) {
                                emptySet()
                            } else {
                                unusedHeaderParamNames
                            },
                        isSuspend = true,
                        forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                        comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                    )
                val loweredBaseParams =
                    rawLoweredParams
                        .let { p ->
                            if (collapsedVarianceTp != null) {
                                replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                            } else {
                                p
                            }
                        }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
                val loweredDeclParams =
                    appendCompletionParam(loweredBaseParams, completionUnused = isTemplateInterfaceStub)
                val completionUnusedInInline =
                    hasInlineHeaderBody &&
                        !deferToPostInclude &&
                        !deferHeaderBody &&
                        (
                            preRenderedHeaderBody == null ||
                                !CppComments.containsWholeWordInCode(preRenderedHeaderBody, "_kxs_completion")
                        )
                val loweredInlineParams =
                    appendCompletionParam(loweredBaseParams, completionUnused = completionUnusedInInline)
                val loweredUsedParams =
                    if (!isPureDecl && effectiveEmitInlineInHpp && !deferToPostInclude && !deferHeaderBody) {
                        loweredInlineParams
                    } else {
                        loweredDeclParams
                    }

                append(renderTemplatePrefix(renderedTypeParams, indent))
                append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                append(indent)
                if (isStaticOnObject) {
                    append("static ")
                } else if (isVirtualMethod && !isOverride) {
                    append("virtual ")
                } else if (effectiveEmitInlineInHpp && enclosingClass == null && renderedTypeParams.isEmpty()) {
                    append("inline ")
                }
                append("void* ")
                    .append(methodName)
                    .append('(')
                    .append(loweredUsedParams)
                    .append(')')
                if (isConstMethod) append(" const")
                if (isOverride && !isStaticOnObject) append(" override")

                if (isPureDecl) {
                    if (isDepHidden && enclosingClass != null) {
                        append(" { return nullptr; }\n")
                    } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                        canBeVirtual
                    ) {
                        append(" = 0;\n")
                    } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                        renderedTypeParams.isNotEmpty()
                    ) {
                        includes.addSystem("stdexcept")
                        append(" { throw std::logic_error(\"unimplemented template interface method\"); }\n")
                    } else {
                        append(";\n")
                    }
                } else if (deferToPostInclude || deferHeaderBody) {
                    append(";\n")
                    val rawDefBaseParams =
                        buildFunctionParameterList(
                            sym = sym,
                            includeDefaults = false,
                            starPlan = starPlan,
                            unusedParamCppNames = unusedHeaderParamNames,
                            isSuspend = true,
                            forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                            comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                        ).let { p ->
                            if (collapsedVarianceTp != null) {
                                replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                            } else {
                                p
                            }
                        }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
                    val defCompletionUnused =
                        preRenderedHeaderBody == null ||
                            !CppComments.containsWholeWordInCode(preRenderedHeaderBody, "_kxs_completion")
                    val defParams = appendCompletionParam(rawDefBaseParams, completionUnused = defCompletionUnused)
                    val defText =
                        buildString {
                            if (enclosingClass != null && enclosingClass.typeParams.isNotEmpty()) {
                                append(renderTemplatePrefix(enclosingClass.typeParams, ""))
                            }
                            append(renderTemplatePrefix(renderedTypeParams, ""))
                            append("[[clang::annotate(\"suspend\")]]\n")
                            val noFuncOrClsTps =
                                (enclosingClass == null || enclosingClass.typeParams.isEmpty()) &&
                                    renderedTypeParams.isEmpty()
                            if (noFuncOrClsTps) {
                                append("inline ")
                            }
                            val qualMethod =
                                if (enclosingClass == null) {
                                    methodName
                                } else {
                                    val clsHead =
                                        if (enclosingClass.typeParams.isEmpty()) {
                                            enclosingClass.cppName
                                        } else {
                                            val tpList = enclosingClass.typeParams.joinToString(", ") { it.name }
                                            "${enclosingClass.cppName}<$tpList>"
                                        }
                                    "$clsHead::$methodName"
                                }
                            append("void* ")
                                .append(qualMethod)
                                .append('(')
                                .append(defParams)
                                .append(')')
                            if (isConstMethod) append(" const")
                            append(' ').append(preRenderedHeaderBody).append('\n')
                        }
                    if (deferToPostInclude) {
                        postIncludeHeaderBodies.add(defText)
                    } else {
                        deferredHeaderBodies.add(defText)
                    }
                } else if (effectiveEmitInlineInHpp) {
                    append(' ').append(preRenderedHeaderBody).append('\n')
                } else {
                    append(";\n")
                }
            } else {
                append(renderTemplatePrefix(renderedTypeParams, indent, declDefaultedVoidPtrTps))
                append(indent)
                if (isNoreturn) append("[[noreturn]] ")
                if (isCAbi) {
                    append("extern \"C\" ")
                } else if (isStaticOnObject) {
                    append("static ")
                } else if (isVirtualMethod && !isOverride) {
                    append("virtual ")
                } else if (
                    effectiveEmitInlineInHpp &&
                    enclosingClass == null &&
                    renderedTypeParams.isEmpty() &&
                    !(bundle.isDocExample && isSuspend)
                ) {
                    append("inline ")
                }
                if (bundle.isDocExample && isSuspend) append("suspend ")
                append(retType)
                    .append(' ')
                    .append(methodName)
                    .append('(')
                    .append(paramsRendered)
                    .append(')')
                if (isConstMethod) append(" const")
                if (isCAbi) append(" noexcept")
                if (isOverride && !isStaticOnObject) append(" override")

                if (isPureDecl) {
                    if (isDepHidden && enclosingClass != null) {
                        if (isVoidReturn) append(" {}\n") else append(" { return {}; }\n")
                    } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                        canBeVirtual
                    ) {
                        append(" = 0;\n")
                    } else if ((isInterfaceMethod || PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)) &&
                        renderedTypeParams.isNotEmpty()
                    ) {
                        includes.addSystem("stdexcept")
                        append(" { throw std::logic_error(\"unimplemented template interface method\"); }\n")
                    } else {
                        append(";\n")
                    }
                } else if (deferToPostInclude || deferHeaderBody) {
                    append(";\n")
                    val defNoDefaultsParams =
                        buildFunctionParameterList(
                            sym = sym,
                            includeDefaults = false,
                            starPlan = starPlan,
                            unusedParamCppNames = unusedHeaderParamNames,
                            forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                            comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                        ).let { p ->
                            if (collapsedVarianceTp != null) {
                                replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                            } else {
                                p
                            }
                        }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
                    val defText =
                        buildString {
                            val outerTps = outOfLineFnQual?.first.orEmpty()
                            if (outerTps.isNotEmpty()) {
                                append(renderTemplatePrefix(outerTps, ""))
                            }
                            append(renderTemplatePrefix(renderedTypeParams, ""))
                            if (isNoreturn) append("[[noreturn]] ")
                            val noFuncOrClsTps =
                                outerTps.isEmpty() && renderedTypeParams.isEmpty()
                            if (noFuncOrClsTps && !(bundle.isDocExample && isSuspend)) {
                                append("inline ")
                            }
                            val qualMethod =
                                if (outOfLineFnQual == null || outOfLineFnQual.second.isEmpty()) {
                                    methodName
                                } else {
                                    "${outOfLineFnQual.second}::$methodName"
                                }
                            if (bundle.isDocExample && isSuspend) append("suspend ")
                            append(retType)
                                .append(' ')
                                .append(qualMethod)
                                .append('(')
                                .append(defNoDefaultsParams)
                                .append(')')
                            if (isConstMethod) append(" const")
                            append(' ').append(preRenderedHeaderBody).append('\n')
                        }
                    if (deferToPostInclude) {
                        postIncludeHeaderBodies.add(defText)
                    } else {
                        deferredHeaderBodies.add(defText)
                    }
                } else if (effectiveEmitInlineInHpp) {
                    append(' ').append(preRenderedHeaderBody).append('\n')
                } else {
                    append(";\n")
                }
                appendCallableFunctionForwarderIfEligible(
                    out = this,
                    sym = sym,
                    enclosingClass = enclosingClass,
                    renderedTypeParams = renderedTypeParams,
                    retType = retType,
                    methodName = methodName,
                    indent = indent,
                    isPureOrInlineDecl = isPureDecl || effectiveEmitInlineInHpp,
                    starPlan = starPlan,
                    collapsedVarianceTp = collapsedVarianceTp,
                    shadowTpRenames = shadowTpRenames,
                )
            }
            val allowDefaultOverloads =
                !isPureDecl &&
                    !isDepHidden &&
                    !isInterfaceMethod &&
                    !PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT) &&
                    !isCAbi &&
                    !sym.isOperator
            if (allowDefaultOverloads) {
                val overloadsText =
                    renderPrefixDefaultOverloads(
                        sym = sym,
                        enclosingClass = enclosingClass,
                        retType = rawRetTypeEffective,
                        isVoidReturn = isVoidReturn,
                        methodName = methodName,
                        isConstMethod = isConstMethod,
                        indent = indent,
                        effectiveTypeParams = renderedTypeParams,
                        starPlan = starPlan,
                        isDualAbiSuspend = isDualAbiSuspend,
                        suspendAuthoringRetType = renderedSuspendAuthoringRetType,
                        hasBoxedResult = hasBoxedResult,
                        isEqualsOverrideOnCancellationExc = isEqualsOverrideOnCancellationExc,
                        comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                        scopeSeenNormalizedSigs = scopeSeenNormalizedSigs,
                    )
                append(replaceWholeIdentifierOutsideStrings(overloadsText, shadowTpRenames))
            }
            val allowVarargListOverload =
                bundle.isDocExample &&
                    !isPureDecl &&
                    !isDepHidden &&
                    !hasBaseVirtualTrait &&
                    !isCAbi &&
                    !sym.isOperator
            if (allowVarargListOverload) {
                val varargText =
                    renderVarargInitializerListOverload(
                        sym = sym,
                        enclosingClass = enclosingClass,
                        retType = rawRetTypeEffective,
                        isVoidReturn = isVoidReturn,
                        methodName = methodName,
                        isConstMethod = isConstMethod,
                        isDualAbiSuspend = isDualAbiSuspend,
                        suspendAuthoringRetType = renderedSuspendAuthoringRetType,
                        hasBoxedResult = hasBoxedResult,
                        indent = indent,
                        effectiveTypeParams = renderedTypeParams,
                        starPlan = starPlan,
                    )
                append(replaceWholeIdentifierOutsideStrings(varargText, shadowTpRenames))
            }
            if (!isPureDecl &&
                enclosingClass != null &&
                sym.kotlinName == "interceptContinuation" &&
                sym.typeParams.size == 1 &&
                sym.valueParams.size == 1
            ) {
                includes.addSystem("memory")
                includes.addSystem("utility")
                includes.addProject("kotlinx/coroutines/Continuation.hpp")
                append(indent)
                    .append("std::shared_ptr<Continuation<void*>> intercept_continuation(")
                    .append("std::shared_ptr<Continuation<void*>> continuation) override ")
                    .append("{ return this->intercept_continuation<void*>(std::move(continuation)); }\n")
            }
        }
    }

    val rawCppBody =
        ensureCreateTestResultReturn(
            body =
                renderFunctionBody(
                    sym = sym,
                    enclosingClass = enclosingClass,
                    activeTypeParams = activeTps,
                    isVoidReturn = isVoidReturn,
                    indent = indent,
                    collapsedVarianceTp = collapsedVarianceTp,
                    isSuspendBody = isDualAbiSuspend,
                    suspendBoxType = suspendBoxType,
                    forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
                    comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
                    wrapReturnInMakeSharedOf = copyableRawRetType,
                ),
            sym = sym,
            isDualAbiSuspend = isDualAbiSuspend,
            indent = indent,
        )
    val cppBody =
        ensureNoreturnUnreachable(rawCppBody, isNoreturn && !isDualAbiSuspend, indent)
            .let { b -> replaceWholeIdentifierOutsideStrings(b, shadowTpRenames) }
    val unusedCppParamNames =
        sym.valueParams
            .filter { vp -> !CppComments.containsWholeWordInCode(cppBody, vp.cppName) }
            .mapTo(HashSet()) { it.cppName }
            .apply {
                if (sym.receiverTypeFrameIndex != null && !CppComments.containsWholeWordInCode(cppBody, "self")) {
                    add("self")
                }
            }
    val rawParamsRendered =
        buildFunctionParameterList(
            sym = sym,
            includeDefaults = false,
            starPlan = starPlan,
            unusedParamCppNames = unusedCppParamNames,
            isSuspend = isDualAbiSuspend,
            forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
            comparableOverrideParamElem = comparableElem.takeIf { isComparableOverride },
        )
    val paramsRendered =
        rawParamsRendered
            .let { p ->
                if (collapsedVarianceTp != null) {
                    replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                } else {
                    p
                }
            }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
    // `.cpp` out-of-line implementation
    val qualifiedName = if (qualifierPrefix.isEmpty()) methodName else "$qualifierPrefix::$methodName"
    return buildString {
        append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = false))
        append(renderTemplatePrefix(renderedTypeParams, indent))
        if (isDualAbiSuspend) {
            val cppContUnused = !CppComments.containsWholeWordInCode(cppBody, "_kxs_completion")
            val cppLoweredParams = appendCompletionParam(paramsRendered, completionUnused = cppContUnused)
            append(indent).append("[[clang::annotate(\"suspend\")]]\n")
            append(indent)
                .append("void* ")
                .append(qualifiedName)
                .append('(')
                .append(cppLoweredParams)
                .append(')')
            if (isConstMethod) append(" const")
            append(' ').append(cppBody).append('\n')
        } else {
            append(indent)
            if (isNoreturn) append("[[noreturn]] ")
            if (isCAbi) append("extern \"C\" ")
            if (bundle.isDocExample && isSuspend) append("suspend ")
            append(retType)
                .append(' ')
                .append(qualifiedName)
                .append('(')
                .append(paramsRendered)
                .append(')')
            if (isConstMethod) append(" const")
            if (isCAbi) append(" noexcept")
            append(' ').append(cppBody).append('\n')
        }
    }
}

internal fun CppDeclarationRenderer.renderLeafContinuationIntrinsicFunction(
    fnFrame: PacketFrame,
    sym: KtFunctionSymbol,
    enclosingClass: KtClassSymbol?,
    leaf: LeafContinuationIntrinsic,
    qualifierPrefix: String,
    indent: String,
): String {
    includes.needsSuspendBoxHelpersDef = true
    includes.addProject("kotlinx/coroutines/Continuation.hpp")
    includes.addSystem("memory")
    val authoringRetType =
        if (sym.returnTypeFrameIndex != null) {
            bundle.typeTranslator.translateTypeFrame(stream.frames[sym.returnTypeFrameIndex], CppTypePosition.RETURN)
        } else {
            "void"
        }

    val isTemplate = sym.typeParams.isNotEmpty()
    val isCancellableIntrinsic =
        isTemplate &&
            (sym.kotlinName == "suspendCancellableCoroutine" || sym.kotlinName == "suspendCancellableCoroutineReusable")

    if (isCancellableIntrinsic) {
        if (!isHeaderMode) return ""
        includes.addProject("kotlinx/coroutines/ContinuationBindings.hpp")
        includes.addProject("kotlinx/coroutines/Result.hpp")
        includes.addProject("kotlinx/coroutines/Unit.hpp")
        includes.addProject("kotlinx/coroutines/intrinsics/Intrinsics.hpp")
        includes.addSystem("any")
        includes.addSystem("type_traits")
        includes.addSystem("utility")

        val tp0 = sym.typeParams[0].name
        val declTps =
            if (sym.typeParams.size == 1) {
                "template <typename $tp0 = kotlin::Unit, typename KxsBlock>\n"
            } else {
                "template <${sym.typeParams.joinToString(", ") { "typename ${it.name}" }}, typename KxsBlock>\n"
            }
        val defTps = "template <${sym.typeParams.joinToString(", ") { "typename ${it.name}" }}, typename KxsBlock>\n"
        val blockParamName = sym.valueParams.lastOrNull()?.cppName ?: "block"
        val isReusable = sym.kotlinName == "suspendCancellableCoroutineReusable"
        val boxedAuthoringRet = "std::conditional_t<std::is_void_v<$tp0>, kotlin::Unit, $tp0>"

        val declHeader =
            buildString {
                append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
                append(indent).append(declTps)
                append(indent).append("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]\n")
                append(indent)
                    .append(boxedAuthoringRet)
                    .append(' ')
                    .append(sym.cppName)
                    .append("(KxsBlock&& ")
                    .append(blockParamName)
                    .append(");\n\n")
                append(indent).append(declTps)
                append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                append(indent)
                    .append("void* ")
                    .append(sym.cppName)
                    .append("(KxsBlock&& ")
                    .append(blockParamName)
                    .append(", std::shared_ptr<kotlin::coroutines::Continuation<void*>> completion);\n\n")
                append(indent).append(declTps)
                append(indent)
                    .append("void* ")
                    .append(sym.cppName)
                    .append("(KxsBlock&& ")
                    .append(blockParamName)
                    .append(", kotlin::coroutines::Continuation<void*>* completion);\n")
            }

        val setupBlock =
            if (isReusable) {
                """
                |    const auto cancellable = get_or_create_cancellable_continuation<$tp0>(kxs_typed_cont);
                |    try {
                |        if constexpr (std::is_invocable_v<KxsBlock&, decltype(cancellable)>) {
                |            (void)$blockParamName(cancellable);
                |        } else {
                |            (void)$blockParamName();
                |        }
                |    } catch (...) {
                |        cancellable->release_claimed_reusable_continuation();
                |        throw;
                |    }
                """.trimMargin()
            } else {
                """
                |    const auto cancellable = std::make_shared<CancellableContinuationImpl<$tp0>>(kxs_typed_cont, MODE_CANCELLABLE);
                |    cancellable->init_cancellability();
                |    if constexpr (std::is_invocable_v<KxsBlock&, decltype(cancellable)>) {
                |        (void)$blockParamName(cancellable);
                |    } else {
                |        (void)$blockParamName();
                |    }
                """.trimMargin()
            }

        val postDefShared =
            buildString {
                append(defTps)
                append("inline void* ")
                    .append(sym.cppName)
                    .append("(KxsBlock&& ")
                    .append(blockParamName)
                    .append(", std::shared_ptr<kotlin::coroutines::Continuation<void*>> completion) {\n")
                append("    auto kxs_intercepted = kotlin::coroutines::intrinsics::intercepted(std::move(completion));\n")
                append("    std::shared_ptr<kotlin::coroutines::Continuation<").append(tp0).append(">> kxs_typed_cont;\n")
                append("    if constexpr (std::is_same_v<").append(tp0).append(", void*>) {\n")
                append("        kxs_typed_cont = kxs_intercepted;\n")
                append("    } else {\n")
                append("        kxs_typed_cont = std::make_shared<kotlin::coroutines::FunctionalContinuation<").append(tp0).append(">>(\n")
                append("            kxs_intercepted ? kxs_intercepted->get_context() : nullptr,\n")
                append("            [kxs_intercepted](kotlin::coroutines::Result<").append(tp0).append("> r) {\n")
                append("                if (!kxs_intercepted) return;\n")
                append("                if (r.is_failure()) {\n")
                append("                    kxs_intercepted->resume_with(kotlin::coroutines::Result<void*>::failure(r.exception_or_null()));\n")
                append(
                    "                } else if constexpr (std::is_same_v<$tp0, " +
                        "kotlin::Unit> || std::is_void_v<$tp0>) {\n",
                )
                append("                    kxs_intercepted->resume_with(kotlin::coroutines::Result<void*>::success(new kotlin::Unit{}));\n")
                append("                } else {\n")
                append("                    kxs_intercepted->resume_with(kotlin::coroutines::Result<void*>::success(new ").append(tp0).append("(r.get_or_throw())));\n")
                append("                }\n")
                append("            });\n")
                append("    }\n")
                append(setupBlock).append('\n')
                append("    auto kxs_res = cancellable->get_result();\n")
                append("    return kxs_box_suspend_result<").append(boxedAuthoringRet).append(">(std::move(kxs_res));\n")
                append("}\n")
            }

        val postDefRaw =
            buildString {
                append(defTps)
                append("inline void* ")
                    .append(sym.cppName)
                    .append("(KxsBlock&& ")
                    .append(blockParamName)
                    .append(", kotlin::coroutines::Continuation<void*>* completion) {\n")
                append("    return ")
                    .append(sym.cppName)
                    .append('<')
                    .append(tp0)
                    .append(">(std::forward<KxsBlock>(")
                    .append(blockParamName)
                    .append("), kxs_wrap_raw_continuation(completion));\n")
                append("}\n")
            }

        postIncludeHeaderBodies.add(postDefShared)
        postIncludeHeaderBodies.add(postDefRaw)
        return declHeader
    }

    val liveFlags = fnFrame.flags
    val isVoidReturn =
        sym.returnTypeFrameIndex == null ||
            authoringRetType == "void" ||
            authoringRetType == "Unit" ||
            authoringRetType == "kotlin::Unit" ||
            authoringRetType == "kotlinx::coroutines::Unit"
    val mentionsTp =
        sym.typeParams.any { containsWholeWord(authoringRetType, it.name) } ||
            enclosingClass?.typeParams?.any { containsWholeWord(authoringRetType, it.name) } == true
    val suspendBoxType: String?
    val suspendAuthoringRetType: String
    val hasBoxedResult: Boolean
    if (authoringRetType == "void*") {
        suspendBoxType = null
        suspendAuthoringRetType = "void*"
        hasBoxedResult = false
    } else if (mentionsTp) {
        includes.addProject("kotlinx/coroutines/Unit.hpp")
        val safeBox = "std::conditional_t<std::is_void_v<$authoringRetType>, kotlin::Unit, $authoringRetType>"
        suspendBoxType = safeBox
        suspendAuthoringRetType = safeBox
        hasBoxedResult = true
    } else if (isVoidReturn) {
        suspendBoxType = null
        suspendAuthoringRetType = "void"
        hasBoxedResult = false
    } else {
        suspendBoxType = authoringRetType
        suspendAuthoringRetType = authoringRetType
        hasBoxedResult = true
    }
    val authoringAnn =
        if (hasBoxedResult) {
            "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]"
        } else {
            "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"
        }
    val contElemType =
        if (sym.returnTypeFrameIndex != null && leaf.isBoxedResult) {
            bundle.typeTranslator.translateTypeFrame(stream.frames[sym.returnTypeFrameIndex], CppTypePosition.TYPE_ARGUMENT)
        } else {
            "void*"
        }

    val isInterfaceMethod = enclosingClass?.kind == KtClassKind.INTERFACE
    val isOverride =
        sym.typeParams.isEmpty() &&
            PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OVERRIDE) &&
            enclosingClass != null &&
            enclosingClass.superTypeFrameIndices.isNotEmpty()
    val isStaticOnObject =
        (enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT) &&
            !isOverride
    val canBeVirtual = sym.typeParams.isEmpty() && authoringRetType != "auto"
    val isVirtualMethod =
        canBeVirtual &&
            (
                isInterfaceMethod ||
                    PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_OPEN) ||
                    PacketFlags.hasFlag(liveFlags, PacketFlags.MOD_ABSTRACT)
            )

    fun appendCompletionParam(
        base: String,
        completionUnused: Boolean,
    ): String {
        val contDecl =
            if (completionUnused) {
                "[[maybe_unused]] std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
            } else {
                "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion"
            }
        return if (base.isEmpty()) contDecl else "$base, $contDecl"
    }

    val adapterLines: List<String> =
        if (contElemType == "void*") {
            emptyList()
        } else {
            includes.addProject("kotlinx/coroutines/Continuation.hpp")
            includes.addProject("kotlinx/coroutines/Result.hpp")
            includes.addProject("kotlinx/coroutines/Unit.hpp")
            includes.addSystem("memory")
            includes.addSystem("type_traits")
            val safeBoxElem = "std::conditional_t<std::is_void_v<$contElemType>, kotlin::Unit, $contElemType>"
            listOf(
                "auto kxs_typed_ucont = std::make_shared<kotlin::coroutines::FunctionalContinuation<$contElemType>>(",
                "    _kxs_completion ? _kxs_completion->get_context() : nullptr,",
                "    [_kxs_completion](kotlin::coroutines::Result<$contElemType> r) {",
                "        if (!_kxs_completion) return;",
                "        if (r.is_failure()) {",
                "            _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::failure(r.exception_or_null()));",
                "        } else if constexpr (std::is_same_v<$contElemType, kotlin::Unit> || std::is_void_v<$contElemType>) {",
                "            _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::success(new kotlin::Unit{}));",
                "        } else {",
                "            _kxs_completion->resume_with(kotlin::coroutines::Result<void*>::success(new $safeBoxElem(r.get_or_throw())));",
                "        }",
                "    });",
            )
        }

    fun renderLeafBody(bodyIndent: String): String {
        val hasRecv = sym.receiverTypeFrameIndex != null
        val (extRecvCls, extRecvPtr, extRecvSimple) = resolveExtensionReceiverContext(sym)
        val emitter =
            CppExprAndStmtEmitter(
                bundle = bundle,
                activeTypeParams =
                    buildSet {
                        enclosingClass?.typeParams?.forEach { add(it.name) }
                        sym.typeParams.forEach { add(it.name) }
                    },
                enclosingClass = enclosingClass,
                enclosingFunction = sym,
                receiverSubstitution = if (hasRecv) "self" else null,
                extensionReceiverClass = extRecvCls,
                extensionReceiverIsPointer = extRecvPtr,
                extensionReceiverSimpleName = extRecvSimple,
                isVoidReturnContext = isVoidReturn,
                enclosingFunctionReturnType = authoringRetType.removePrefix("[[noreturn]] "),
                unitReturnExprOverride = if (suspendBoxType == null) "nullptr" else null,
                isSuspendBody = true,
                suspendBoxType = suspendBoxType,
            )
        emitter.bindLocalName(leaf.uContParamKotlinName, if (contElemType == "void*") "_kxs_completion" else "kxs_typed_ucont")
        for (vp in sym.valueParams) emitter.bindValueParam(vp)
        val lambdaBody = stream.frames[leaf.lambdaBodyFrameIndex]
        return if (lambdaBody.nodeTypeId == KtNodeTypes.BLOCK_ID) {
            emitter.emitBlock(lambdaBody, indent = bodyIndent, isValueBlock = true, resultTargetVar = null, extraEntryLines = adapterLines)
        } else {
            emitter.emitBlockOrExprBody(
                lambdaBody,
                isExpressionBody = true,
                isVoidReturn = false,
                indent = bodyIndent,
                extraEntryLines = adapterLines,
            )
        }
    }

    fun collectUnusedLeafParams(body: String): HashSet<String> =
        sym.valueParams
            .filter { vp -> !CppComments.containsWholeWordInCode(body, vp.cppName) }
            .mapTo(HashSet()) { it.cppName }
            .apply {
                if (sym.receiverTypeFrameIndex != null && !CppComments.containsWholeWordInCode(body, "self")) {
                    add("self")
                }
            }

    if (isHeaderMode) {
        val authoringParams = buildFunctionParameterList(sym, includeDefaults = true, isSuspend = true)
        val loweredBaseParams = buildFunctionParameterList(sym, includeDefaults = false, isSuspend = true)
        val lambdaBody = stream.frames[leaf.lambdaBodyFrameIndex]
        val deferToPostInclude =
            isTemplate &&
                enclosingClass?.enclosingClassFrameIndex == null &&
                bodyReferencesExpectOrDeferredBottomHeader(fnFrame)
        val deferHeaderBody =
            isTemplate &&
                !deferToPostInclude &&
                enclosingClass?.enclosingClassFrameIndex == null &&
                bodyReferencesLaterTopLevelDecl(
                    bodyFrame = lambdaBody,
                    anchorDeclFrameIndex = enclosingClass?.frameIndex ?: fnFrame.frameIndex,
                    sym = sym,
                    enclosingClass = enclosingClass,
                )
        return buildString {
            append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = true))
            append(renderTemplatePrefix(sym.typeParams, indent))
            append(indent).append(authoringAnn).append('\n')
            append(indent)
            if (isStaticOnObject) append("static ")
            append(suspendAuthoringRetType)
                .append(' ')
                .append(sym.cppName)
                .append('(')
                .append(authoringParams)
                .append(");\n\n")

            if (isTemplate) {
                if (deferToPostInclude || deferHeaderBody) {
                    append(renderTemplatePrefix(sym.typeParams, indent))
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                    append(indent)
                    if (isStaticOnObject) append("static ")
                    append("void* ")
                        .append(sym.cppName)
                        .append('(')
                        .append(appendCompletionParam(loweredBaseParams, completionUnused = false))
                        .append(");\n")

                    val renderedBody = renderLeafBody("")
                    val unusedTemplateCppParams = collectUnusedLeafParams(renderedBody)
                    val templateDefBaseParams =
                        buildFunctionParameterList(
                            sym = sym,
                            includeDefaults = false,
                            unusedParamCppNames = unusedTemplateCppParams,
                            isSuspend = true,
                        )
                    val completionUnused = !CppComments.containsWholeWordInCode(renderedBody, "_kxs_completion")
                    val defParams =
                        appendCompletionParam(templateDefBaseParams, completionUnused = completionUnused)
                    val qualMethod =
                        if (enclosingClass == null) {
                            sym.cppName
                        } else {
                            val clsHead =
                                if (enclosingClass.typeParams.isEmpty()) {
                                    enclosingClass.cppName
                                } else {
                                    "${enclosingClass.cppName}<${enclosingClass.typeParams.joinToString(", ") { it.name }}>"
                                }
                            "$clsHead::${sym.cppName}"
                        }
                    val defText =
                        buildString {
                            if (enclosingClass != null && enclosingClass.typeParams.isNotEmpty()) {
                                append(renderTemplatePrefix(enclosingClass.typeParams, ""))
                            }
                            append(renderTemplatePrefix(sym.typeParams, ""))
                            append("[[clang::annotate(\"suspend\")]]\n")
                            append("void* ")
                                .append(qualMethod)
                                .append('(')
                                .append(defParams)
                                .append(") ")
                                .append(renderedBody)
                                .append('\n')
                        }
                    if (deferToPostInclude) {
                        postIncludeHeaderBodies.add(defText)
                    } else {
                        deferredHeaderBodies.add(defText)
                    }
                } else {
                    val renderedBody = renderLeafBody(indent)
                    val unusedTemplateCppParams = collectUnusedLeafParams(renderedBody)
                    val templateDefBaseParams =
                        buildFunctionParameterList(
                            sym = sym,
                            includeDefaults = false,
                            unusedParamCppNames = unusedTemplateCppParams,
                            isSuspend = true,
                        )
                    val completionUnused = !CppComments.containsWholeWordInCode(renderedBody, "_kxs_completion")
                    append(renderTemplatePrefix(sym.typeParams, indent))
                    append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                    append(indent)
                    if (isStaticOnObject) append("static ")
                    append("void* ")
                        .append(sym.cppName)
                        .append('(')
                        .append(appendCompletionParam(templateDefBaseParams, completionUnused = completionUnused))
                        .append(") ")
                        .append(renderedBody)
                        .append('\n')
                }
            } else {
                append(renderTemplatePrefix(sym.typeParams, indent))
                append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                append(indent)
                if (isStaticOnObject) {
                    append("static ")
                } else if (isVirtualMethod && !isOverride) {
                    append("virtual ")
                }
                append("void* ")
                    .append(sym.cppName)
                    .append('(')
                    .append(appendCompletionParam(loweredBaseParams, completionUnused = false))
                    .append(')')
                if (isOverride && !isStaticOnObject) append(" override")
                append(";\n")
            }
        }
    }

    if (isTemplate) return ""
    val renderedBody = renderLeafBody(indent)
    val unusedCppParams = collectUnusedLeafParams(renderedBody)
    val baseCppParams =
        buildFunctionParameterList(
            sym = sym,
            includeDefaults = false,
            unusedParamCppNames = unusedCppParams,
            isSuspend = true,
        )
    val completionUnused = !CppComments.containsWholeWordInCode(renderedBody, "_kxs_completion")
    val qualName = if (qualifierPrefix.isEmpty()) sym.cppName else "$qualifierPrefix::${sym.cppName}"
    return buildString {
        append(renderProvenanceAndLeadingComments(fnFrame, indent, includeLeadingKdoc = false))
        append(indent).append("[[clang::annotate(\"suspend\")]]\n")
        append(indent)
            .append("void* ")
            .append(qualName)
            .append('(')
            .append(appendCompletionParam(baseCppParams, completionUnused = completionUnused))
            .append(") ")
            .append(renderedBody)
            .append('\n')
    }
}

internal fun CppDeclarationRenderer.renderFunctionBody(
    sym: KtFunctionSymbol,
    enclosingClass: KtClassSymbol?,
    activeTypeParams: Set<String>,
    isVoidReturn: Boolean,
    indent: String,
    collapsedVarianceTp: Pair<String, String>? = null,
    isSuspendBody: Boolean = false,
    suspendBoxType: String? = null,
    forceCancellationEqualsParam: Boolean = false,
    comparableOverrideParamElem: String? = null,
    wrapReturnInMakeSharedOf: String? = null,
): String {
    val liveFlags = stream.frames[sym.frameIndex].flags
    val rawInlineCpp = bundle.ffiPlan.inlineCppByFrame[sym.frameIndex]
    if (rawInlineCpp != null && sym.bodyFrameIndex == null) {
        return buildString {
            append("{\n")
            if (PacketFlags.hasFlag(liveFlags, PacketFlags.GC_FOREIGN_THREAD_ENTRY)) {
                includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                append(indent).append("    Kotlin_initRuntimeIfNeeded();\n")
                append(indent).append("    CalledFromNativeGuard kxs_native_guard{};\n")
            }
            for (line in rawInlineCpp.trimIndent().lines()) {
                if (line.isBlank()) append('\n') else append(indent).append("    ").append(line).append('\n')
            }
            append(indent).append('}')
        }
    }

    val bodyIdx =
        sym.bodyFrameIndex
            ?: return if (isSuspendBody) {
                if (suspendBoxType == "kotlin::Unit") "{ return new kotlin::Unit{}; }" else "{ return nullptr; }"
            } else {
                "{}"
            }
    val bodyFrame = stream.frames[bodyIdx]
    val hasRecv = sym.receiverTypeFrameIndex != null
    val (extRecvCls, extRecvPtr, extRecvSimple) = resolveExtensionReceiverContext(sym)
    val fnRetType =
        sym.returnTypeFrameIndex?.let {
            bundle.typeTranslator.translateTypeFrame(stream.frames[it], CppTypePosition.RETURN).removePrefix("[[noreturn]] ")
        }
    val emitter =
        CppExprAndStmtEmitter(
            bundle = bundle,
            activeTypeParams = activeTypeParams,
            enclosingClass = enclosingClass,
            enclosingFunction = sym,
            receiverSubstitution = if (hasRecv) "self" else null,
            extensionReceiverClass = extRecvCls,
            extensionReceiverIsPointer = extRecvPtr,
            extensionReceiverSimpleName = extRecvSimple,
            isVoidReturnContext = isVoidReturn,
            enclosingFunctionReturnType = fnRetType,
            isSuspendBody = isSuspendBody,
            suspendBoxType = suspendBoxType,
            wrapReturnInMakeSharedOf = wrapReturnInMakeSharedOf,
        )
    for ((pIdx, vp) in sym.valueParams.withIndex()) {
        if (comparableOverrideParamElem != null && sym.valueParams.size == 1 && pIdx == 0) {
            emitter.bindLocalName(vp.name, vp.cppName)
        } else {
            emitter.bindValueParam(
                vp = vp,
                forcePolyPointer = forceCancellationEqualsParam && sym.valueParams.size == 1 && pIdx == 0,
            )
        }
    }

    val entryGuards = ArrayList<String>()
    if (collapsedVarianceTp != null) {
        entryGuards.add("using ${collapsedVarianceTp.first} = ${collapsedVarianceTp.second};")
    }
    if (PacketFlags.hasFlag(liveFlags, PacketFlags.GC_FOREIGN_THREAD_ENTRY)) {
        includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
        entryGuards.add("Kotlin_initRuntimeIfNeeded();")
        entryGuards.add("CalledFromNativeGuard kxs_native_guard{};")
    }

    val coreBody =
        emitter.emitBlockOrExprBody(
            bodyFrame = bodyFrame,
            isExpressionBody = sym.isExpressionBody,
            isVoidReturn = isVoidReturn,
            indent = if (sym.cAbiSymbolName != null) "$indent    " else indent,
            extraEntryLines = entryGuards,
        )

    if (sym.cAbiSymbolName != null) {
        includes.addSystem("exception")
        return buildString {
            append("{\n")
            append(indent)
                .append("    try ")
                .append(coreBody)
                .append(" catch (...) {\n")
            append(indent).append("        std::terminate();\n")
            append(indent).append("    }\n")
            append(indent).append('}')
        }
    }
    return coreBody
}

internal fun CppDeclarationRenderer.computeFirstTrailingDefaultIndex(sym: KtFunctionSymbol): Int {
    var idx = sym.valueParams.size
    while (idx > 0 && sym.valueParams[idx - 1].defaultExprFrameIndex != null) {
        idx -= 1
    }
    return idx
}

internal data class StarProjectionPlan(
    val synthesizedTypeParams: List<KtTypeParamInfo>,
    val receiverStarNames: List<String>?,
    val paramStarNamesByIdx: Map<Int, List<String>>,
)

internal fun CppDeclarationRenderer.buildStarProjectionPlan(sym: KtFunctionSymbol): StarProjectionPlan {
    val synth = ArrayList<KtTypeParamInfo>()
    var nextIdx = 0
    fun allocNames(count: Int): List<String>? {
        if (count <= 0) return null
        val list = ArrayList<String>(count)
        repeat(count) {
            val name = "KxsStar${nextIdx++}"
            list.add(name)
            synth.add(KtTypeParamInfo(name = name, isReified = false, upperBoundFrameIndex = null, frameIndex = sym.frameIndex))
        }
        return list
    }
    val recvStars =
        sym.receiverTypeFrameIndex?.let { allocNames(countNonVoidPtrStarProjections(stream.frames[it])) }
    val paramStars = LinkedHashMap<Int, List<String>>()
    for ((pIdx, vp) in sym.valueParams.withIndex()) {
        val tfIdx = vp.typeFrameIndex ?: continue
        allocNames(countNonVoidPtrStarProjections(stream.frames[tfIdx]))?.let { paramStars[pIdx] = it }
    }
    return StarProjectionPlan(synth, recvStars, paramStars)
}

internal fun CppDeclarationRenderer.applyStarNamesToRenderedType(
    renderedType: String,
    starNames: List<String>?,
): String {
    if (starNames.isNullOrEmpty()) return renderedType
    var result = renderedType
    for (starName in starNames) {
        val match = STAR_STD_ANY_IN_ANGLE_REGEX.find(result) ?: break
        val anyEnd = match.range.last + 1
        val anyStart = anyEnd - match.groups[1]!!.value.length
        result = result.replaceRange(anyStart, anyEnd, starName)
    }
    return result
}

internal fun CppDeclarationRenderer.countNonVoidPtrStarProjections(frame: PacketFrame): Int {
    if (frame.nodeTypeId == KtNodeTypes.USER_TYPE_ID) {
        val simple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, frame)
        val argList = FrameAstHelpers.firstChildFrame(stream, frame, KtNodeTypes.TYPE_ARGUMENT_LIST_ID)
        var count = 0
        if (argList != null) {
            for (proj in FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.TYPE_PROJECTION_ID)) {
                val innerType = FrameAstHelpers.firstChildFrame(stream, proj, KtNodeTypes.TYPE_REFERENCE_ID)
                count +=
                    if (innerType == null) {
                        if (!bundle.isDocExample || simple !in CppTypeTranslator.VOID_PTR_STAR_PROJECTION_TYPES) 1 else 0
                    } else {
                        countNonVoidPtrStarProjections(innerType)
                    }
            }
        }
        return count
    }
    var sum = 0
    for (child in FrameAstHelpers.childFrames(stream, frame)) {
        sum += countNonVoidPtrStarProjections(child)
    }
    return sum
}

internal fun CppDeclarationRenderer.resolveFunctionParamRawType(
    sym: KtFunctionSymbol,
    pIdx: Int,
    vp: KtValueParamInfo,
    starPlan: StarProjectionPlan? = null,
    forceCancellationEqualsParam: Boolean = false,
    comparableOverrideParamElem: String? = null,
): String =
    when {
        comparableOverrideParamElem != null && sym.valueParams.size == 1 && pIdx == 0 ->
            "const $comparableOverrideParamElem&"
        forceCancellationEqualsParam && sym.valueParams.size == 1 && pIdx == 0 -> {
            includes.addSystem("exception")
            "const std::exception*"
        }
        else -> {
            val unstarredRawT =
                vp.typeFrameIndex?.let {
                    bundle.typeTranslator.translateTypeFrame(
                        stream.frames[it],
                        CppTypePosition.PARAMETER,
                        stream.frames[vp.frameIndex].flags,
                    )
                } ?: "auto"
            applyStarNamesToRenderedType(unstarredRawT, starPlan?.paramStarNamesByIdx?.get(pIdx))
        }
    }

internal fun CppDeclarationRenderer.wrapVarargParamType(
    rawT: String,
    isVararg: Boolean,
): String {
    if (!isVararg) return rawT
    includes.addSystem("vector")
    return if (!bundle.isDocExample) {
        includes.needsVarargDef = true
        "const KxsVararg<$rawT>&"
    } else {
        "const std::vector<$rawT>&"
    }
}

internal fun CppDeclarationRenderer.rewriteSuspendFunctionSecondArg(
    cppType: String,
    bareParamTpNames: Set<String>,
): String {
    if (bareParamTpNames.isEmpty()) return cppType
    val token = "SuspendFunction<"
    val start = cppType.indexOf(token)
    if (start < 0) return cppType
    var depth = 1
    var commaIdx = -1
    var closeIdx = -1
    var i = start + token.length
    while (i < cppType.length) {
        when (cppType[i]) {
            '<' -> depth += 1
            '>' -> {
                depth -= 1
                if (depth == 0) {
                    closeIdx = i
                    break
                }
            }
            ',' -> if (depth == 1 && commaIdx < 0) commaIdx = i
        }
        i += 1
    }
    if (commaIdx < 0 || closeIdx < 0) return cppType
    val secondArg = cppType.substring(commaIdx + 1, closeIdx).trim()
    if (secondArg !in bareParamTpNames) return cppType
    includes.addSystem("type_traits")
    val prefix = cppType.substring(0, commaIdx + 1)
    val suffix = cppType.substring(closeIdx)
    return "$prefix std::type_identity_t<$secondArg>$suffix"
}

internal fun CppDeclarationRenderer.rewriteContinuationParamType(
    cppType: String,
    callableTpNames: Set<String>,
): String {
    if (callableTpNames.isEmpty()) return cppType
    for (tp in callableTpNames) {
        if (cppType == "std::shared_ptr<Continuation<$tp>>" ||
            cppType == "std::shared_ptr<kotlin::coroutines::Continuation<$tp>>" ||
            cppType == "std::shared_ptr<kotlinx::coroutines::Continuation<$tp>>"
        ) {
            includes.addSystem("type_traits")
            return "std::type_identity_t<$cppType>"
        }
    }
    return cppType
}

internal fun CppDeclarationRenderer.computeDeductionSensitiveTpSets(
    sym: KtFunctionSymbol,
    rawRecvType: String?,
    rawParamTypes: List<String>,
): Pair<Set<String>, Set<String>> {
    if (bundle.isDocExample || sym.typeParams.isEmpty()) {
        return emptySet<String>() to emptySet()
    }
    val fnTpNames = sym.typeParams.mapTo(HashSet()) { it.name }
    val bareParamTpNames =
        rawParamTypes.filterTo(HashSet()) { it in fnTpNames }
    val callableTpNames = HashSet<String>()
    val callableSources = ArrayList<String>(rawParamTypes.size + 1)
    if (rawRecvType != null &&
        (rawRecvType.contains("SuspendFunction<") || rawRecvType.contains("std::function<"))
    ) {
        callableSources.add(rawRecvType)
    }
    for (pT in rawParamTypes) {
        if (pT.contains("SuspendFunction<") || pT.contains("std::function<")) {
            callableSources.add(pT)
        }
    }
    for (tp in fnTpNames) {
        if (callableSources.any { containsWholeWord(it, tp) }) {
            callableTpNames.add(tp)
        }
    }
    return bareParamTpNames to callableTpNames
}

internal fun CppDeclarationRenderer.appendCallableFunctionForwarderIfEligible(
    out: StringBuilder,
    sym: KtFunctionSymbol,
    enclosingClass: KtClassSymbol?,
    renderedTypeParams: List<KtTypeParamInfo>,
    retType: String,
    methodName: String,
    indent: String,
    isPureOrInlineDecl: Boolean,
    starPlan: StarProjectionPlan?,
    collapsedVarianceTp: Pair<String, String>?,
    shadowTpRenames: Map<String, String>,
) {
    if (bundle.isDocExample ||
        enclosingClass != null ||
        sym.isOperator ||
        !isPureOrInlineDecl ||
        renderedTypeParams.size != 1 ||
        sym.receiverTypeFrameIndex != null ||
        sym.valueParams.isEmpty()
    ) {
        return
    }
    val tpName = renderedTypeParams[0].name
    val cleanRet = retType.removePrefix("[[noreturn]] ")
    if (cleanRet != tpName) return
    val lastIdx = sym.valueParams.lastIndex
    val rawParamTypes =
        sym.valueParams.mapIndexed { pIdx, vp ->
            resolveFunctionParamRawType(
                sym = sym,
                pIdx = pIdx,
                vp = vp,
                starPlan = starPlan,
            ).let { p ->
                if (collapsedVarianceTp != null) {
                    replaceWholeIdentifier(p, collapsedVarianceTp.first, collapsedVarianceTp.second)
                } else {
                    p
                }
            }.let { p -> replaceWholeIdentifierOutsideStrings(p, shadowTpRenames) }
        }
    val expectedFnType = "std::function<$tpName()>"
    if (rawParamTypes[lastIdx] != expectedFnType) return
    if ((0 until lastIdx).any { idx -> containsWholeWord(rawParamTypes[idx], tpName) }) return
    includes.addSystem("functional")
    includes.addSystem("type_traits")
    includes.addSystem("utility")
    val nsMacro =
        bundle.symbols.namespaceSegments
            .ifEmpty { listOf("ROOT") }
            .joinToString("_") { it.uppercase() }
    val fnMacro = methodName.uppercase()
    val guardName = "KXS_FN_CALLABLE_FWD_${nsMacro}_${fnMacro}_${sym.valueParams.size}"
    val priorParamDecls =
        (0 until lastIdx).map { idx -> "${rawParamTypes[idx]} ${sym.valueParams[idx].cppName}" }
    val lastVp = sym.valueParams[lastIdx]
    val fwdParams = (priorParamDecls + "KxsCallable&& ${lastVp.cppName}").joinToString(", ")
    val priorCallArgs =
        (0 until lastIdx).map { idx ->
            val pT = rawParamTypes[idx]
            val pName = sym.valueParams[idx].cppName
            if (pT.endsWith("&") || pT in VALUE_PASS_CPP_TYPES) pName else "std::move($pName)"
        }
    val fnArg = "std::function<KxsRet()>(std::forward<KxsCallable>(${lastVp.cppName}))"
    val allCallArgs = (priorCallArgs + fnArg).joinToString(", ")
    out.append('\n')
    out.append("#ifndef ").append(guardName).append('\n')
    out.append("#define ").append(guardName).append('\n')
    out.append(indent).append("template <typename KxsCallable>\n")
    out
        .append(indent)
        .append("    requires (std::is_invocable_v<KxsCallable&> && ")
        .append("!std::is_same_v<std::remove_cvref_t<KxsCallable>, ")
        .append("std::function<std::invoke_result_t<KxsCallable&>()>>)\n")
    out
        .append(indent)
        .append("inline std::invoke_result_t<KxsCallable&> ")
        .append(methodName)
        .append('(')
        .append(fwdParams)
        .append(") {\n")
    out
        .append(indent)
        .append("    using KxsRet = std::invoke_result_t<KxsCallable&>;\n")
    out
        .append(indent)
        .append("    return ")
        .append(methodName)
        .append("<KxsRet>(")
        .append(allCallArgs)
        .append(");\n")
    out.append(indent).append("}\n")
    out.append("#endif\n")
}

internal fun CppDeclarationRenderer.buildFunctionParameterList(
    sym: KtFunctionSymbol,
    includeDefaults: Boolean,
    starPlan: StarProjectionPlan? = null,
    unusedParamCppNames: Set<String> = emptySet(),
    isSuspend: Boolean = false,
    forceCancellationEqualsParam: Boolean = false,
    comparableOverrideParamElem: String? = null,
): String {
    val parts = ArrayList<String>(sym.valueParams.size + 1)
    val rawRType =
        sym.receiverTypeFrameIndex?.let { rIdx ->
            val translated =
                bundle.typeTranslator.translateTypeFrame(
                    stream.frames[rIdx],
                    CppTypePosition.EXTENSION_RECEIVER,
                )
            applyStarNamesToRenderedType(translated, starPlan?.receiverStarNames)
        }
    val rawParamTypes =
        sym.valueParams.mapIndexed { pIdx, vp ->
            val rawT =
                resolveFunctionParamRawType(
                    sym = sym,
                    pIdx = pIdx,
                    vp = vp,
                    starPlan = starPlan,
                    forceCancellationEqualsParam = forceCancellationEqualsParam,
                    comparableOverrideParamElem = comparableOverrideParamElem,
                )
            wrapVarargParamType(rawT, vp.isVararg)
        }
    val (bareParamTpNames, callableTpNames) =
        computeDeductionSensitiveTpSets(sym, rawRType, rawParamTypes)
    if (rawRType != null) {
        val rType = rewriteSuspendFunctionSecondArg(rawRType, bareParamTpNames)
        val passType =
            formatExtensionReceiverPassType(
                rType = rType,
                mutatesVectorReceiver = extensionFunctionMutatesVectorReceiver(sym, rType),
            )
        val extRecvAttr = if (isSuspend) "[[clang::annotate(\"kotlin.ir.ExtensionReceiver\")]] " else ""
        val unusedSelfAttr = if ("self" in unusedParamCppNames) "[[maybe_unused]] " else ""
        parts.add("$extRecvAttr$unusedSelfAttr$passType self")
    }
    val emitter = CppExprAndStmtEmitter(bundle, inDefaultParameterExpr = true)
    val firstTrailingDefaultIdx = computeFirstTrailingDefaultIndex(sym)
    for ((pIdx, vp) in sym.valueParams.withIndex()) {
        val paramT =
            rawParamTypes[pIdx]
                .let { rewriteSuspendFunctionSecondArg(it, bareParamTpNames) }
                .let { rewriteContinuationParamType(it, callableTpNames) }
        val defaultSuffix =
            if (includeDefaults && pIdx >= firstTrailingDefaultIdx && vp.defaultExprFrameIndex != null) {
                val rawDef = emitter.emitExpression(stream.frames[vp.defaultExprFrameIndex])
                val coercedDef =
                    if (rawDef == "nullptr" && paramT.startsWith("std::optional<")) {
                        includes.addSystem("optional")
                        "std::nullopt"
                    } else {
                        rawDef
                    }
                " = $coercedDef"
            } else {
                ""
            }
        val unusedAttr = if (vp.cppName in unusedParamCppNames) "[[maybe_unused]] " else ""
        parts.add("$unusedAttr$paramT ${vp.cppName}$defaultSuffix")
        emitter.bindValueParam(vp)
    }
    return parts.joinToString(", ")
}

internal fun CppDeclarationRenderer.renderPrefixDefaultOverloads(
    sym: KtFunctionSymbol,
    enclosingClass: KtClassSymbol?,
    retType: String,
    isVoidReturn: Boolean,
    methodName: String,
    isConstMethod: Boolean,
    indent: String,
    effectiveTypeParams: List<KtTypeParamInfo> = sym.typeParams,
    starPlan: StarProjectionPlan? = null,
    isDualAbiSuspend: Boolean = false,
    suspendAuthoringRetType: String = retType,
    hasBoxedResult: Boolean = false,
    isEqualsOverrideOnCancellationExc: Boolean = false,
    comparableOverrideParamElem: String? = null,
    scopeSeenNormalizedSigs: MutableSet<String> = HashSet(),
): String {
    val firstTrailingDefaultIdx = computeFirstTrailingDefaultIndex(sym)
    var endDefaultExclusive = firstTrailingDefaultIdx
    while (endDefaultExclusive > 0 && sym.valueParams[endDefaultExclusive - 1].defaultExprFrameIndex == null) {
        endDefaultExclusive -= 1
    }
    if (endDefaultExclusive <= 0) return ""
    var startDefaultInclusive = endDefaultExclusive
    while (startDefaultInclusive > 0 && sym.valueParams[startDefaultInclusive - 1].defaultExprFrameIndex != null) {
        startDefaultInclusive -= 1
    }
    if (startDefaultInclusive >= endDefaultExclusive) return ""

    val isStaticOnObject =
        enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT
    val rawRType =
        sym.receiverTypeFrameIndex?.let { rIdx ->
            val translated =
                bundle.typeTranslator.translateTypeFrame(
                    stream.frames[rIdx],
                    CppTypePosition.EXTENSION_RECEIVER,
                )
            applyStarNamesToRenderedType(translated, starPlan?.receiverStarNames)
        }
    fun resolveParamRawType(pIdx: Int, vp: KtValueParamInfo): String =
        resolveFunctionParamRawType(
            sym = sym,
            pIdx = pIdx,
            vp = vp,
            starPlan = starPlan,
            forceCancellationEqualsParam = isEqualsOverrideOnCancellationExc,
            comparableOverrideParamElem = comparableOverrideParamElem,
        )
    val rawParamTypes =
        sym.valueParams.mapIndexed { pIdx, vp ->
            wrapVarargParamType(resolveParamRawType(pIdx, vp), vp.isVararg)
        }
    val (bareParamTpNames, callableTpNames) =
        computeDeductionSensitiveTpSets(sym, rawRType, rawParamTypes)
    val receiverPassType: String? =
        rawRType?.let { baseRType ->
            val rType = rewriteSuspendFunctionSecondArg(baseRType, bareParamTpNames)
            if (rType in VALUE_PASS_CPP_TYPES || rType.endsWith("&") || rType.endsWith("*")) {
                rType
            } else if (isMutableExtensionReceiverCppType(rType)) {
                "$rType&"
            } else {
                "const $rType&"
            }
        }

    val primaryNormTypes = ArrayList<String>(sym.valueParams.size + 1)
    if (receiverPassType != null) primaryNormTypes.add(receiverPassType)
    for (pRawT in rawParamTypes) {
        val rewritten =
            pRawT
                .let { rewriteSuspendFunctionSecondArg(it, bareParamTpNames) }
                .let { rewriteContinuationParamType(it, callableTpNames) }
        primaryNormTypes.add(rewritten)
    }
    val primaryKey = computeNormalizedOverloadKey(methodName, primaryNormTypes, isConstMethod)
    scopeSeenNormalizedSigs.add(primaryKey)
    val seenNormalizedSigs = HashSet<String>(scopeSeenNormalizedSigs)

    val authoringAnn =
        if (hasBoxedResult) {
            "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]"
        } else {
            "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"
        }

    return buildString {
        for (keepPrefixCount in startDefaultInclusive until endDefaultExclusive) {
            val sigParts = ArrayList<String>()
            val authoringSigParts = ArrayList<String>()
            val loweredSigParts = ArrayList<String>()
            val normTypes = ArrayList<String>()
            val callArgs = ArrayList<String>()
            if (receiverPassType != null) {
                sigParts.add("$receiverPassType self")
                authoringSigParts.add("[[clang::annotate(\"kotlin.ir.ExtensionReceiver\")]] $receiverPassType self")
                loweredSigParts.add("[[clang::annotate(\"kotlin.ir.ExtensionReceiver\")]] $receiverPassType self")
                normTypes.add(receiverPassType)
                callArgs.add("self")
            }
            val (extRecvCls, extRecvPtr, extRecvSimple) = resolveExtensionReceiverContext(sym)
            val emitter =
                CppExprAndStmtEmitter(
                    bundle = bundle,
                    enclosingClass = enclosingClass,
                    enclosingFunction = sym,
                    receiverSubstitution = if (sym.receiverTypeFrameIndex != null) "self" else null,
                    extensionReceiverClass = extRecvCls,
                    extensionReceiverIsPointer = extRecvPtr,
                    extensionReceiverSimpleName = extRecvSimple,
                    inDefaultParameterExpr = true,
                )
            for ((pIdx, vp) in sym.valueParams.withIndex()) {
                val rawT = resolveParamRawType(pIdx, vp)
                if (pIdx in keepPrefixCount until endDefaultExclusive) {
                    val defFrame = stream.frames[vp.defaultExprFrameIndex!!]
                    val rawDef = emitter.emitExpression(defFrame)
                    callArgs.add(if (rawDef == "nullptr" && rawT.startsWith("std::optional<")) "std::nullopt" else rawDef)
                } else {
                    val paramT =
                        rawParamTypes[pIdx]
                            .let { rewriteSuspendFunctionSecondArg(it, bareParamTpNames) }
                            .let { rewriteContinuationParamType(it, callableTpNames) }
                    val defSuffix =
                        if (pIdx >= firstTrailingDefaultIdx && vp.defaultExprFrameIndex != null) {
                            val rawDef = emitter.emitExpression(stream.frames[vp.defaultExprFrameIndex])
                            val coercedDef =
                                if (rawDef == "nullptr" && paramT.startsWith("std::optional<")) {
                                    includes.addSystem("optional")
                                    "std::nullopt"
                                } else {
                                    rawDef
                                }
                            " = $coercedDef"
                        } else {
                            ""
                        }
                    sigParts.add("$paramT ${vp.cppName}$defSuffix")
                    authoringSigParts.add("$paramT ${vp.cppName}$defSuffix")
                    loweredSigParts.add("$paramT ${vp.cppName}")
                    normTypes.add(paramT)
                    includes.addSystem("utility")
                    val fwdArg = if (paramT.endsWith("&") || paramT in VALUE_PASS_CPP_TYPES) vp.cppName else "std::move(${vp.cppName})"
                    callArgs.add(fwdArg)
                    emitter.bindValueParam(vp)
                }
            }
            val normSig = computeNormalizedOverloadKey(methodName, normTypes, isConstMethod)
            if (!seenNormalizedSigs.add(normSig)) continue
            scopeSeenNormalizedSigs.add(normSig)

            val isNoreturn = retType.startsWith("[[noreturn]] ")
            val cleanRetType = retType.removePrefix("[[noreturn]] ")
            val tArgs = if (effectiveTypeParams.isNotEmpty()) "<${effectiveTypeParams.joinToString(", ") { it.name }}>" else ""
            if (isDualAbiSuspend) {
                includes.addSystem("memory")
                includes.addSystem("utility")
                includes.addProject("kotlinx/coroutines/Continuation.hpp")
                val cleanAuthRet = suspendAuthoringRetType.removePrefix("[[noreturn]] ")
                append('\n')
                append(renderTemplatePrefix(effectiveTypeParams, indent))
                append(indent).append(authoringAnn).append('\n')
                append(indent)
                if (isNoreturn) append("[[noreturn]] ")
                if (isStaticOnObject) append("static ")
                append(cleanAuthRet)
                    .append(' ')
                    .append(methodName)
                    .append('(')
                    .append(authoringSigParts.joinToString(", "))
                    .append(')')
                if (isConstMethod) append(" const")
                append(";\n")

                val loweredParams =
                    (loweredSigParts + "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion")
                        .joinToString(", ")
                val loweredCallArgs = (callArgs + "std::move(_kxs_completion)").joinToString(", ")
                append('\n')
                append(renderTemplatePrefix(effectiveTypeParams, indent))
                append(indent).append("[[clang::annotate(\"suspend\")]]\n")
                append(indent)
                if (isStaticOnObject) {
                    append("static ")
                } else if (enclosingClass == null && effectiveTypeParams.isEmpty()) {
                    append("inline ")
                }
                append("void* ")
                    .append(methodName)
                    .append('(')
                    .append(loweredParams)
                    .append(')')
                if (isConstMethod) append(" const")
                append(" { return ")
                    .append(methodName)
                    .append(tArgs)
                    .append('(')
                    .append(loweredCallArgs)
                    .append("); }\n")
            } else {
                append('\n')
                append(renderTemplatePrefix(effectiveTypeParams, indent))
                append(indent)
                if (isNoreturn) append("[[noreturn]] ")
                if (isStaticOnObject) {
                    append("static ")
                } else if (enclosingClass == null && effectiveTypeParams.isEmpty()) {
                    append("inline ")
                }
                val retPrefix = if (isVoidReturn) "" else "return "
                append(cleanRetType)
                    .append(' ')
                    .append(methodName)
                    .append('(')
                    .append(sigParts.joinToString(", "))
                    .append(')')
                if (isConstMethod) append(" const")
                append(" { ")
                    .append(retPrefix)
                    .append(methodName)
                    .append(tArgs)
                    .append('(')
                    .append(callArgs.joinToString(", "))
                    .append("); }\n")
            }
        }
    }
}

internal fun CppDeclarationRenderer.renderVarargInitializerListOverload(
    sym: KtFunctionSymbol,
    enclosingClass: KtClassSymbol?,
    retType: String,
    isVoidReturn: Boolean,
    methodName: String,
    isConstMethod: Boolean,
    isDualAbiSuspend: Boolean,
    suspendAuthoringRetType: String,
    hasBoxedResult: Boolean,
    indent: String,
    effectiveTypeParams: List<KtTypeParamInfo> = sym.typeParams,
    starPlan: StarProjectionPlan? = null,
): String {
    val varargIdx = sym.valueParams.indexOfFirst { it.isVararg }
    if (varargIdx < 0 || sym.valueParams.count { it.isVararg } != 1) return ""
    val firstTrailingDefaultIdx = computeFirstTrailingDefaultIndex(sym)
    if (firstTrailingDefaultIdx <= varargIdx) return ""

    includes.addSystem("initializer_list")
    includes.addSystem("utility")
    includes.addSystem("vector")

    val isStaticOnObject =
        enclosingClass?.kind == KtClassKind.OBJECT_SINGLETON || enclosingClass?.kind == KtClassKind.COMPANION_OBJECT
    val sigParts = ArrayList<String>(sym.valueParams.size + 1)
    val authoringSigParts = ArrayList<String>(sym.valueParams.size + 1)
    val callArgs = ArrayList<String>(sym.valueParams.size + 1)

    if (sym.receiverTypeFrameIndex != null) {
        val rawRType = bundle.typeTranslator.translateTypeFrame(stream.frames[sym.receiverTypeFrameIndex], CppTypePosition.EXTENSION_RECEIVER)
        val rType = applyStarNamesToRenderedType(rawRType, starPlan?.receiverStarNames)
        val passType =
            if (rType in VALUE_PASS_CPP_TYPES || rType.endsWith("&") || rType.endsWith("*")) {
                rType
            } else if (isMutableExtensionReceiverCppType(rType)) {
                "$rType&"
            } else {
                "const $rType&"
            }
        sigParts.add("$passType self")
        authoringSigParts.add("[[clang::annotate(\"kotlin.ir.ExtensionReceiver\")]] $passType self")
        callArgs.add("self")
    }

    for ((pIdx, vp) in sym.valueParams.withIndex()) {
        val unstarredRawT =
            vp.typeFrameIndex?.let {
                bundle.typeTranslator.translateTypeFrame(
                    stream.frames[it],
                    CppTypePosition.PARAMETER,
                    stream.frames[vp.frameIndex].flags,
                )
            } ?: "auto"
        val rawT = applyStarNamesToRenderedType(unstarredRawT, starPlan?.paramStarNamesByIdx?.get(pIdx))
        if (pIdx == varargIdx) {
            val declPart = "std::initializer_list<$rawT> ${vp.cppName}"
            sigParts.add(declPart)
            authoringSigParts.add(declPart)
            callArgs.add("std::vector<$rawT>(${vp.cppName})")
        } else {
            val declPart = "$rawT ${vp.cppName}"
            sigParts.add(declPart)
            authoringSigParts.add(declPart)
            val fwdArg = if (rawT.endsWith("&") || rawT in VALUE_PASS_CPP_TYPES) vp.cppName else "std::move(${vp.cppName})"
            callArgs.add(fwdArg)
        }
    }

    val isNoreturn = retType.startsWith("[[noreturn]] ")
    val cleanRetType = retType.removePrefix("[[noreturn]] ")
    val tArgs = if (effectiveTypeParams.isNotEmpty()) "<${effectiveTypeParams.joinToString(", ") { it.name }}>" else ""

    return buildString {
        if (isDualAbiSuspend) {
            includes.addSystem("memory")
            includes.addProject("kotlinx/coroutines/Continuation.hpp")
            val authoringAnn =
                if (hasBoxedResult) {
                    "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), clang::annotate(\"kxs_boxed_result\")]]"
                } else {
                    "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"
                }
            val cleanAuthRet = suspendAuthoringRetType.removePrefix("[[noreturn]] ")
            append('\n')
            append(renderTemplatePrefix(effectiveTypeParams, indent))
            append(indent).append(authoringAnn).append('\n')
            append(indent)
            if (isNoreturn) append("[[noreturn]] ")
            if (isStaticOnObject) append("static ")
            append(cleanAuthRet)
                .append(' ')
                .append(methodName)
                .append('(')
                .append(authoringSigParts.joinToString(", "))
                .append(')')
            if (isConstMethod) append(" const")
            append(";\n")

            val loweredParams =
                (authoringSigParts + "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion")
                    .joinToString(", ")
            val loweredCallArgs = (callArgs + "std::move(_kxs_completion)").joinToString(", ")
            append('\n')
            append(renderTemplatePrefix(effectiveTypeParams, indent))
            append(indent).append("[[clang::annotate(\"suspend\")]]\n")
            append(indent)
            if (isStaticOnObject) {
                append("static ")
            } else if (enclosingClass == null && effectiveTypeParams.isEmpty()) {
                append("inline ")
            }
            append("void* ")
                .append(methodName)
                .append('(')
                .append(loweredParams)
                .append(')')
            if (isConstMethod) append(" const")
            append(" { return ")
                .append(methodName)
                .append(tArgs)
                .append('(')
                .append(loweredCallArgs)
                .append("); }\n")
        } else {
            append('\n')
            append(renderTemplatePrefix(effectiveTypeParams, indent))
            append(indent)
            if (isNoreturn) append("[[noreturn]] ")
            if (isStaticOnObject) {
                append("static ")
            } else if (enclosingClass == null && effectiveTypeParams.isEmpty()) {
                append("inline ")
            }
            val retPrefix = if (isVoidReturn) "" else "return "
            append(cleanRetType)
                .append(' ')
                .append(methodName)
                .append('(')
                .append(sigParts.joinToString(", "))
                .append(')')
            if (isConstMethod) append(" const")
            append(" { ")
                .append(retPrefix)
                .append(methodName)
                .append(tArgs)
                .append('(')
                .append(callArgs.joinToString(", "))
                .append("); }\n")
        }
    }
}
