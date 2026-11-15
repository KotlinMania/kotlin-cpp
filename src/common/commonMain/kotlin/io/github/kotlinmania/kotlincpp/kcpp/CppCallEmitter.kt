package io.github.kotlinmania.kotlincpp.kcpp

import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.KNOWN_EXTERNAL_RECEIVER_METHODS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.KNOWN_GENERIC_SHARED_CLASSES
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.KNOWN_PASCAL_FACTORY_FUNCTIONS
import io.github.kotlinmania.kotlincpp.kcpp.CppExprAndStmtEmitter.Companion.VALUE_CONTAINER_CALL_NAMES
import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.PacketFrame

internal fun CppExprAndStmtEmitter.emitCallExpr(
    receiverCode: String?,
    receiverIsPointer: Boolean,
    callFrame: PacketFrame,
    receiverFrame: PacketFrame? = null,
): String {
    val calleeKt = extractCalleeSimpleName(callFrame)
    // Kotlin `contract { callsInPlace(...) }` is a compile-time-only Kotlin intrinsic; elide cleanly in C++23.
    if (calleeKt == "contract" &&
        receiverCode == null &&
        resolveBoundName("contract") == null &&
        "contract" !in bundle.symbols.functionsByName &&
        FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID) != null
    ) {
        return "((void)0)"
    }
    val typeArgPos =
        if (receiverCode == null && calleeKt in VALUE_CONTAINER_CALL_NAMES) {
            CppTypePosition.VALUE
        } else {
            CppTypePosition.TYPE_ARGUMENT
        }
    val typeArgs = extractCallTypeArgs(callFrame, typeArgPos)
    val rawCallArgs = extractRawCallArgs(callFrame)
    val argFrames = rawCallArgs.map { it.exprFrame }
    val args by lazy(LazyThreadSafetyMode.NONE) {
        argFrames.map { withExpectedTargetType(null) { emitExpression(it) } }
    }
    val targetCppForInference = currentExpectedTargetType ?: enclosingFunctionReturnType

    // Builtin Kotlin standard functions / pre-conditions / collections
    if (receiverCode == null) {
        if (calleeKt in localPolymorphicClasses) {
            includes.addSystem("memory")
            val structName = CppNamingPolicy.toTypeName(calleeKt)
            return "std::make_shared<$structName>(${args.joinToString(", ")})"
        }
        val recvSub = receiverSubstitution
        val localExtCpp = localExtensionFunctions[calleeKt]
        if (localExtCpp != null && recvSub != null) {
            val allArgs = ArrayList<String>(args.size + 1)
            allArgs.add(recvSub)
            allArgs.addAll(args)
            return "$localExtCpp(${allArgs.joinToString(", ")})"
        }
        if (recvSub != null) {
            if (recvSub != "this->kxs_outer_" && recvSub != "kxs_outer_in") {
                if (!bundle.isDocExample &&
                    extensionReceiverSimpleName in setOf("Array", "List", "MutableList", "ArrayList")
                ) {
                    when (calleeKt) {
                        "get" ->
                            if (args.size == 1) {
                                includes.addSystem("cstddef")
                                return "$recvSub[static_cast<std::size_t>(${args[0]})]"
                            }
                        "set" ->
                            if (args.size == 2) {
                                includes.addSystem("cstddef")
                                return "($recvSub[static_cast<std::size_t>(${args[0]})] = ${args[1]})"
                            }
                    }
                }
                when (calleeKt) {
                    "isEmpty" -> return "$recvSub.empty()"
                    "isNotEmpty" -> return "(!$recvSub.empty())"
                    "toTypedArray" -> return recvSub
                    "getOrElse" ->
                        if (!bundle.isDocExample && extensionReceiverSimpleName == "Result" && args.size == 1) {
                            includes.needsResultHelpersDef = true
                            return "kxs_result_get_or_else($recvSub, ${args[0]})"
                        }
                    "inv" ->
                        if (!bundle.isDocExample && args.isEmpty()) {
                            return "(~$recvSub)"
                        }
                }
            }
            val extRecvCls = extensionReceiverClass
            if (extRecvCls != null &&
                calleeKt !in anonObjectOwnMethods &&
                classDeclaresMethod(extRecvCls, calleeKt)
            ) {
                val op = if (extensionReceiverIsPointer) "->" else "."
                val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                val tmplKw =
                    if (tSuffix.isNotEmpty() && isDependentTemplateReceiver(recvSub, null)) {
                        "template "
                    } else {
                        ""
                    }
                return "$recvSub$op$tmplKw${CppNamingPolicy.toSnakeCase(calleeKt)}$tSuffix(${args.joinToString(", ")})"
            }
            if (resolveBoundName(calleeKt) == null) {
                tryEmitCoroutineIntrinsicExtCall(calleeKt, recvSub) { args }?.let { return it }
            }
            val extSimple = extensionReceiverSimpleName
            if (extRecvCls == null &&
                extSimple != null &&
                resolveBoundName(calleeKt) == null &&
                calleeKt !in bundle.symbols.functionsByName
            ) {
                val op = if (extensionReceiverIsPointer) "->" else "."
                val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                if (extSimple == "CoroutineContext" && calleeKt == "get" && !bundle.isDocExample && args.size == 1) {
                    includes.needsContextOpsDef = true
                    return "kotlin::coroutines::kxs_ctx_get($recvSub, ${args[0]})"
                }
                if (extSimple == "Duration" &&
                    !bundle.isDocExample &&
                    args.size == 1 &&
                    calleeKt in setOf("plus", "minus", "times", "div")
                ) {
                    val binOp =
                        when (calleeKt) {
                            "plus" -> "+"
                            "minus" -> "-"
                            "times" -> "*"
                            else -> "/"
                        }
                    return "($recvSub $binOp ${args[0]})"
                }
                if (calleeKt in KNOWN_EXTERNAL_RECEIVER_METHODS[extSimple].orEmpty()) {
                    val tmplKw =
                        if (tSuffix.isNotEmpty() && isDependentTemplateReceiver(recvSub, null)) {
                            "template "
                        } else {
                            ""
                        }
                    val cppCallee = CppNamingPolicy.toSnakeCase(calleeKt)
                    return "$recvSub$op$tmplKw$cppCallee$tSuffix(${args.joinToString(", ")})"
                }
            }
        }
        when (calleeKt) {
            "Array" -> {
                if (args.size == 2) {
                    includes.addSystem("cstdint")
                    includes.addSystem("cstddef")
                    includes.addSystem("vector")
                    val countVar = freshTemp("kxs_n")
                    val initVar = freshTemp("kxs_init")
                    val arrVar = freshTemp("kxs_arr")
                    val idxVar = freshTemp("kxs_i")
                    val elemType = typeArgs.firstOrNull() ?: "decltype($initVar(0))"
                    return "([&]() { const auto $countVar = ${args[0]}; auto $initVar = ${args[1]}; " +
                        "std::vector<$elemType> $arrVar; $arrVar.reserve(static_cast<std::size_t>($countVar)); " +
                        "for (std::int32_t $idxVar = 0; $idxVar < $countVar; ++$idxVar) { $arrVar.push_back($initVar($idxVar)); } " +
                        "return $arrVar; }())"
                }
            }
            "check", "require" -> {
                includes.addSystem("stdexcept")
                val cond = args.getOrNull(0) ?: "true"
                val rawMsg = args.getOrNull(1)
                val msgIsLambda =
                    argFrames.getOrNull(1)?.let { unwrapTransparentFrame(it).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID } == true
                val msg =
                    when {
                        rawMsg == null -> "\"Failed $calleeKt\""
                        msgIsLambda -> "($rawMsg)()"
                        else -> rawMsg
                    }
                val excType = if (calleeKt == "require") "std::invalid_argument" else "std::logic_error"
                return "(($cond) ? void(0) : throw $excType($msg))"
            }
            "checkNotNull", "requireNotNull" -> {
                includes.addSystem("stdexcept")
                val v = args.getOrNull(0) ?: "nullptr"
                val rawMsg = args.getOrNull(1)
                val msgIsLambda =
                    argFrames.getOrNull(1)?.let { unwrapTransparentFrame(it).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID } == true
                val msg =
                    when {
                        rawMsg == null -> "\"Required value was null\""
                        msgIsLambda -> "($rawMsg)()"
                        else -> rawMsg
                    }
                return "([&]() { auto&& kxs_nn = $v; if (!kxs_nn) throw std::invalid_argument($msg); return *kxs_nn; }())"
            }
            "error" -> {
                includes.addSystem("stdexcept")
                val rawMsg = args.firstOrNull()
                val msgIsLambda =
                    argFrames.firstOrNull()?.let { unwrapTransparentFrame(it).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID } == true
                val msg =
                    when {
                        rawMsg == null -> "\"Illegal state\""
                        msgIsLambda -> "($rawMsg)()"
                        else -> rawMsg
                    }
                return "throw std::logic_error($msg)"
            }
            "println" -> {
                includes.addSystem("iostream")
                val v = args.firstOrNull()
                return if (v != null) "(std::cout << $v << '\\n')" else "(std::cout << '\\n')"
            }
            "print" -> {
                includes.addSystem("iostream")
                val v = args.firstOrNull() ?: "\"\""
                return "(std::cout << $v)"
            }
            "listOf", "mutableListOf", "arrayListOf" -> {
                includes.addSystem("vector")
                val elemT = typeArgs.firstOrNull()
                return if (elemT != null) {
                    "std::vector<$elemT>{${args.joinToString(", ")}}"
                } else {
                    "std::vector{${args.joinToString(", ")}}"
                }
            }
            "emptyList" -> {
                if (typeArgs.isEmpty() && !bundle.isDocExample) {
                    includes.needsEmptyContainersDef = true
                    return "KxsEmptyList{}"
                }
                includes.addSystem("vector")
                val elemT = typeArgs.firstOrNull() ?: "std::any"
                return "std::vector<$elemT>{}"
            }
            "setOf", "mutableSetOf", "hashSetOf" -> {
                includes.addSystem("unordered_set")
                val elemT = typeArgs.firstOrNull()
                return if (elemT != null) {
                    "std::unordered_set<$elemT>{${args.joinToString(", ")}}"
                } else {
                    "std::unordered_set{${args.joinToString(", ")}}"
                }
            }
            "emptySet" -> {
                if (typeArgs.isEmpty() && !bundle.isDocExample) {
                    includes.needsEmptyContainersDef = true
                    return "KxsEmptySet{}"
                }
                includes.addSystem("unordered_set")
                val elemT = typeArgs.firstOrNull() ?: "std::any"
                return "std::unordered_set<$elemT>{}"
            }
            "emptyMap" -> {
                if (typeArgs.isEmpty() && !bundle.isDocExample) {
                    includes.needsEmptyContainersDef = true
                    return "KxsEmptyMap{}"
                }
                includes.addSystem("unordered_map")
                val kT = typeArgs.getOrNull(0) ?: "std::string"
                val vT = typeArgs.getOrNull(1) ?: "std::any"
                return "std::unordered_map<$kT, $vT>{}"
            }
            "mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf" -> {
                includes.addSystem("unordered_map")
                val kT = typeArgs.getOrNull(0)
                val vT = typeArgs.getOrNull(1)
                return if (kT != null && vT != null) {
                    "std::unordered_map<$kT, $vT>{${args.joinToString(", ")}}"
                } else {
                    "std::unordered_map{${args.joinToString(", ")}}"
                }
            }
            "arrayOfNulls" -> {
                includes.addSystem("cstddef")
                includes.addSystem("vector")
                val inferredFromRet =
                    if (typeArgs.isEmpty() &&
                        enclosingFunctionReturnType != null &&
                        enclosingFunctionReturnType.startsWith("std::vector<") &&
                        enclosingFunctionReturnType.endsWith(">")
                    ) {
                        enclosingFunctionReturnType
                            .removePrefix("std::vector<")
                            .removeSuffix(">")
                            .trim()
                    } else {
                        null
                    }
                val elemT = typeArgs.firstOrNull() ?: inferredFromRet ?: "void*"
                val n = args.firstOrNull() ?: "0"
                return "std::vector<$elemT>(static_cast<std::size_t>($n))"
            }
            "arrayOf", "intArrayOf", "longArrayOf", "byteArrayOf" -> {
                includes.addSystem("cstdint")
                includes.addSystem("vector")
                val elemT =
                    when (calleeKt) {
                        "intArrayOf" -> "std::int32_t"
                        "longArrayOf" -> "std::int64_t"
                        "byteArrayOf" -> "std::int8_t"
                        else -> typeArgs.firstOrNull()
                    }
                return if (elemT != null) {
                    "std::vector<$elemT>{${args.joinToString(", ")}}"
                } else {
                    "std::vector{${args.joinToString(", ")}}"
                }
            }
            "atomic" -> {
                if (!bundle.isDocExample) {
                    val elemT = typeArgs.firstOrNull() ?: "void*"
                    val initV = args.firstOrNull() ?: "{}"
                    if (elemT.startsWith("std::shared_ptr<")) {
                        includes.needsAtomicReferenceDef = true
                        includes.addSystem("mutex")
                        return "KxsAtomicReference<$elemT>{$initV}"
                    }
                    includes.addSystem("atomic")
                    return "std::atomic<$elemT>{$initV}"
                }
            }
            "autoreleasepool" -> {
                if (!bundle.isDocExample) {
                    val blk = args.firstOrNull() ?: "[](){}"
                    return "($blk)()"
                }
            }
            "dispatch_async" -> {
                if (!bundle.isDocExample) {
                    includes.needsDarwinDispatchHelpers = true
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    return "kxs_dispatch_async(${args.joinToString(", ")})"
                }
            }
            "CFRunLoopTimerCreateWithHandler" -> {
                if (!bundle.isDocExample) {
                    includes.needsDarwinDispatchHelpers = true
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    return "kxs_cf_timer_create_with_handler(${args.joinToString(", ")})"
                }
            }
            "createCleaner" -> {
                includes.addProject("kotlinx/coroutines/KotlinGCBridge.hpp")
                return "create_cleaner(${args.joinToString(", ")})"
            }
            "suspendCoroutineUninterceptedOrReturn" ->
                if (!bundle.isDocExample && args.size == 1) {
                    includes.needsSuspendBoxHelpersDef = true
                    val tArg = typeArgs.firstOrNull()
                    return if (tArg != null) {
                        "kxs_suspend_coroutine_unintercepted_or_return<$tArg>(${args[0]})"
                    } else {
                        "kxs_suspend_coroutine_unintercepted_or_return(${args[0]})"
                    }
                }
            "sequence" ->
                if (!bundle.isDocExample &&
                    argFrames.size == 1 &&
                    unwrapTransparentFrame(argFrames[0]).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
                ) {
                    val lambdaFrame = unwrapTransparentFrame(argFrames[0])
                    val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
                    val lambdaBody =
                        if (fnLit != null) {
                            FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
                        } else {
                            lambdaFrame
                        }
                    includes.addSystem("vector")
                    includes.needsSequenceAlias = true
                    val elemT =
                        typeArgs.firstOrNull()
                            ?: extractOuterTemplateArgsForBase(targetCppForInference, "KxsSequence")?.firstOrNull()
                            ?: extractOuterTemplateArgsForBase(targetCppForInference, "Sequence")?.firstOrNull()
                            ?: "std::any"
                    if (elemT == "std::any") includes.addSystem("any")
                    val seqVar = freshTemp("kxs_seq")
                    val prevSeqVar = sequenceYieldTargetVar
                    sequenceYieldTargetVar = seqVar
                    return try {
                        withinExpressionIife {
                            buildString {
                                append("([&]() {\n")
                                append("    KxsSequence<$elemT> $seqVar{};\n")
                                appendInlineLambdaBodyStatements(
                                    this,
                                    lambdaBody,
                                    "    ",
                                    targetVar = null,
                                    targetReturn = false,
                                )
                                append("    return ").append(seqVar).append(";\n")
                                append("}())")
                            }
                        }
                    } finally {
                        sequenceYieldTargetVar = prevSeqVar
                    }
                }
            "yield" -> {
                val seqTarget = sequenceYieldTargetVar
                if (!bundle.isDocExample && args.size == 1 && seqTarget != null) {
                    return "$seqTarget.push_back(${args[0]})"
                }
            }
            "run" ->
                if (argFrames.size == 1 &&
                    unwrapTransparentFrame(argFrames[0]).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
                ) {
                    return "(${args[0]})()"
                }
            "with" ->
                if (argFrames.size == 2 &&
                    unwrapTransparentFrame(argFrames[1]).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
                ) {
                    val lambdaFrame = unwrapTransparentFrame(argFrames[1])
                    val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
                    val lambdaBody =
                        if (fnLit != null) {
                            FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
                        } else {
                            lambdaFrame
                        }
                    val recvTemp = freshTemp("kxs_recv")
                    return withinExpressionIife {
                        val nestedEmitter = buildInlineScopeEmitter(argFrames[0], args[0], recvTemp)
                        val code =
                            buildString {
                                append("([&]() {\n")
                                append("    auto&& $recvTemp = ${args[0]};\n")
                                nestedEmitter.appendInlineLambdaBodyStatements(
                                    this,
                                    lambdaBody,
                                    "    ",
                                    targetVar = null,
                                    targetReturn = true,
                                )
                                append("}())")
                            }
                        synthCounter = nestedEmitter.synthCounter
                        code
                    }
                }
            "StringBuilder" ->
                if (!bundle.isDocExample && args.size <= 1) {
                    includes.needsStringBuilderDef = true
                    return "KxsStringBuilder(${args.joinToString(", ")})"
                }
            "buildString" ->
                if (!bundle.isDocExample &&
                    argFrames.isNotEmpty() &&
                    argFrames.size <= 2 &&
                    unwrapTransparentFrame(argFrames.last()).nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID
                ) {
                    includes.addSystem("cstdint")
                    includes.addSystem("string")
                    includes.addSystem("type_traits")
                    val lambdaFrame = unwrapTransparentFrame(argFrames.last())
                    val fnLit = FrameAstHelpers.firstChildFrame(stream, lambdaFrame, KtNodeTypes.FUNCTION_LITERAL_ID)
                    val lambdaBody =
                        if (fnLit != null) {
                            FrameAstHelpers.firstChildFrame(stream, fnLit, KtNodeTypes.BLOCK_ID) ?: fnLit
                        } else {
                            lambdaFrame
                        }
                    val sbVar = freshTemp("kxs_sb")
                    val sbStruct = freshTemp("KxsSb")
                    val capReserve =
                        if (argFrames.size == 2) {
                            includes.addSystem("cstddef")
                            " $sbVar.s.reserve(static_cast<std::size_t>(${args[0]}));"
                        } else {
                            ""
                        }
                    return withinExpressionIife {
                        withLexicalScope {
                            receiverSubstitution = sbVar
                            extensionReceiverClass = null
                            extensionReceiverIsPointer = false
                            extensionReceiverSimpleName = "StringBuilder"
                            buildString {
                                append("([&]() {\n")
                                append("    struct ").append(sbStruct).append(" {\n")
                                append("        std::string s;\n")
                                append("        template <typename KxsV>\n")
                                append("        ").append(sbStruct).append("& append(const KxsV& v) {\n")
                                append("            using D = std::remove_cvref_t<KxsV>;\n")
                                append("            if constexpr (std::is_same_v<D, char> || std::is_same_v<D, char16_t>) s.push_back(static_cast<char>(v));\n")
                                append("            else if constexpr (std::is_same_v<D, bool>) s += (v ? \"true\" : \"false\");\n")
                                append("            else if constexpr (std::is_convertible_v<D, std::string>) s += v;\n")
                                append("            else if constexpr (std::is_arithmetic_v<D>) s += std::to_string(v);\n")
                                append("            else if constexpr (requires { v.to_string(); }) s += v.to_string();\n")
                                append("            else if constexpr (requires { v->to_string(); }) s += (v ? v->to_string() : std::string(\"null\"));\n")
                                append("            else if constexpr (requires { v.has_value(); *v; }) { if (v.has_value()) append(*v); else s += \"null\"; }\n")
                                append("            return *this;\n")
                                append("        }\n")
                                append("        ").append(sbStruct).append("& append_line() { s.push_back('\\n'); return *this; }\n")
                                append("        template <typename KxsV>\n")
                                append("        ").append(sbStruct).append("& append_line(const KxsV& v) { append(v); s.push_back('\\n'); return *this; }\n")
                                append("        void clear() { s.clear(); }\n")
                                append("        std::int32_t length() const noexcept { return static_cast<std::int32_t>(s.size()); }\n")
                                append("        std::string to_string() const { return s; }\n")
                                append("    } $sbVar;$capReserve\n")
                                appendInlineLambdaBodyStatements(this, lambdaBody, "    ", targetVar = null, targetReturn = false)
                                append("    return ").append(sbVar).append(".s;\n")
                                append("}())")
                            }
                        }
                    }
                }
            "min", "minOf" ->
                if (!bundle.isDocExample &&
                    args.size == 2 &&
                    (calleeKt == "minOf" || calleeKt !in bundle.symbols.functionsByName)
                ) {
                    includes.addSystem("algorithm")
                    return "std::min(${args[0]}, ${args[1]})"
                }
            "max", "maxOf" ->
                if (!bundle.isDocExample &&
                    args.size == 2 &&
                    (calleeKt == "maxOf" || calleeKt !in bundle.symbols.functionsByName)
                ) {
                    includes.addSystem("algorithm")
                    return "std::max(${args[0]}, ${args[1]})"
                }
            "compareValuesBy" ->
                if (!bundle.isDocExample && args.size >= 3) {
                    includes.addSystem("functional")
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    val steps =
                        args.drop(2).joinToString(" ") { sel ->
                            "{ auto&& kxs_sel = $sel; " +
                                "decltype(auto) kxs_va = std::invoke(kxs_sel, kxs_a); " +
                                "decltype(auto) kxs_vb = std::invoke(kxs_sel, kxs_b); " +
                                "if constexpr (requires { kxs_va == nullptr; kxs_va->compare_to(*kxs_vb); }) { " +
                                "if (kxs_va != kxs_vb) { if (kxs_va == nullptr) return -1; if (kxs_vb == nullptr) return 1; " +
                                "if (int kxs_c = kxs_va->compare_to(*kxs_vb); kxs_c != 0) return kxs_c; } " +
                                "} else if constexpr (requires { kxs_va.compare_to(kxs_vb); }) { " +
                                "if (int kxs_c = kxs_va.compare_to(kxs_vb); kxs_c != 0) return kxs_c; " +
                                "} else { " +
                                "if (kxs_va < kxs_vb) return -1; if (kxs_vb < kxs_va) return 1; " +
                                "} }"
                        }
                    return "([&](const auto& kxs_a_raw, const auto& kxs_b_raw) -> int { " +
                        "auto kxs_unwrap = [](const auto& kxs_x) -> decltype(auto) { " +
                        "using KxsX = std::remove_cvref_t<decltype(kxs_x)>; " +
                        "if constexpr (std::is_pointer_v<KxsX> || requires { kxs_x.get(); *kxs_x; }) return *kxs_x; " +
                        "else return kxs_x; }; " +
                        "const auto& kxs_a = kxs_unwrap(kxs_a_raw); " +
                        "const auto& kxs_b = kxs_unwrap(kxs_b_raw); " +
                        "$steps return 0; }(${args[0]}, ${args[1]}))"
                }
            "ArrayList" ->
                if (!bundle.isDocExample && args.size <= 1) {
                    includes.addSystem("vector")
                    val elemT =
                        typeArgs.firstOrNull()
                            ?: extractOuterTemplateArgsForBase(targetCppForInference, "std::vector")?.firstOrNull()
                            ?: "void*"
                    if (args.isEmpty()) {
                        return "std::vector<$elemT>{}"
                    }
                    includes.addSystem("cstddef")
                    includes.addSystem("type_traits")
                    return "([&](auto&& kxs_al_arg) { std::vector<$elemT> kxs_v; " +
                        "if constexpr (std::is_integral_v<std::decay_t<decltype(kxs_al_arg)>>) { " +
                        "kxs_v.reserve(static_cast<std::size_t>(kxs_al_arg)); " +
                        "} else { " +
                        "for (auto&& kxs_it : kxs_al_arg) kxs_v.push_back(kxs_it); " +
                        "} return kxs_v; }(${args[0]}))"
                }
            "HashSet", "LinkedHashSet" ->
                if (!bundle.isDocExample && args.size <= 1) {
                    includes.addSystem("unordered_set")
                    val elemT =
                        typeArgs.firstOrNull()
                            ?: extractOuterTemplateArgsForBase(targetCppForInference, "std::unordered_set")
                                ?.firstOrNull()
                            ?: "void*"
                    if (args.isEmpty()) {
                        return "std::unordered_set<$elemT>{}"
                    }
                    includes.addSystem("cstddef")
                    includes.addSystem("type_traits")
                    return "([&](auto&& kxs_hs_arg) { std::unordered_set<$elemT> kxs_s; " +
                        "if constexpr (std::is_integral_v<std::decay_t<decltype(kxs_hs_arg)>>) { " +
                        "kxs_s.reserve(static_cast<std::size_t>(kxs_hs_arg)); " +
                        "} else { " +
                        "for (auto&& kxs_it : kxs_hs_arg) kxs_s.insert(kxs_it); " +
                        "} return kxs_s; }(${args[0]}))"
                }
            "HashMap", "LinkedHashMap" ->
                if (!bundle.isDocExample && args.size <= 1) {
                    includes.addSystem("unordered_map")
                    val inferredMapArgs =
                        if (typeArgs.size < 2) {
                            extractOuterTemplateArgsForBase(targetCppForInference, "std::unordered_map")
                        } else {
                            null
                        }
                    val kT = typeArgs.getOrNull(0) ?: inferredMapArgs?.getOrNull(0) ?: "std::string"
                    val vT = typeArgs.getOrNull(1) ?: inferredMapArgs?.getOrNull(1) ?: "void*"
                    if (args.isEmpty()) {
                        return "std::unordered_map<$kT, $vT>{}"
                    }
                    includes.addSystem("cstddef")
                    includes.addSystem("type_traits")
                    return "([&](auto&& kxs_hm_arg) { std::unordered_map<$kT, $vT> kxs_m; " +
                        "if constexpr (std::is_integral_v<std::decay_t<decltype(kxs_hm_arg)>>) { " +
                        "kxs_m.reserve(static_cast<std::size_t>(kxs_hm_arg)); " +
                        "} else { " +
                        "kxs_m.insert(kxs_hm_arg.begin(), kxs_hm_arg.end()); " +
                        "} return kxs_m; }(${args[0]}))"
                }
        }
    } else {
        val localExtCpp = localExtensionFunctions[calleeKt]
        if (localExtCpp != null) {
            val allArgs = ArrayList<String>(args.size + 1)
            allArgs.add(receiverCode)
            allArgs.addAll(args)
            return "$localExtCpp(${allArgs.joinToString(", ")})"
        }
        // Check if calling an extension function or rich-enum helper declared in the same file (`fun Recv.ext(...)`)!
        val sameFileFns = bundle.symbols.functionsByName[calleeKt].orEmpty()
        val extCandidates =
            sameFileFns.filter { fn ->
                isAccessibleExtensionFn(fn) &&
                    (
                        fn.receiverTypeFrameIndex != null ||
                            fn.enclosingClassFrameIndex?.let {
                                bundle.symbols.classesByFrame[it]?.kind == KtClassKind.RICH_ENUM
                            } == true
                    )
            }
        if (extCandidates.isNotEmpty()) {
            val recvSimple = receiverFrame?.let { inferExpressionSimpleTypeName(it) }
            val matchingExt = extCandidates.filter { doesFnMatchRawArgs(it.valueParams, rawCallArgs) }
            val matchedExtFn =
                matchingExt.firstOrNull { fn ->
                    recvSimple != null &&
                        fn.receiverTypeFrameIndex?.let {
                            FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[it]) == recvSimple
                        } == true
                } ?: matchingExt.firstOrNull() ?: extCandidates.first()
            val boundMap = mapCallArgsToParamIndices(matchedExtFn.valueParams, rawCallArgs)
            val renderedArgs =
                if (boundMap != null) {
                    emitAlignedArgsForParams(matchedExtFn.valueParams, boundMap)
                } else {
                    args
                }
            val effTypeArgs =
                inferMissingFunctionTypeArgs(
                    fn = matchedExtFn,
                    explicitTypeArgs = typeArgs,
                    targetCppForInference = targetCppForInference,
                    receiverFrame = receiverFrame,
                )
            val allArgs = ArrayList<String>(renderedArgs.size + 1)
            allArgs.add(receiverCode)
            allArgs.addAll(renderedArgs)
            val callName =
                if (matchedExtFn.kotlinName == "invoke") "invoke" else formatExtensionFnCallTarget(matchedExtFn)
            val tSuffix = if (effTypeArgs.isNotEmpty()) "<${effTypeArgs.joinToString(", ")}>" else ""
            return "$callName$tSuffix(${allArgs.joinToString(", ")})"
        }
        if (!bundle.isDocExample && calleeKt == "toCancellationException" && enclosingClass != null) {
            val prefix = if (enclosingClass.typeParams.isNotEmpty()) "this->" else ""
            val extraArgs =
                if (rawCallArgs.size == 1 &&
                    unwrapTransparentFrame(rawCallArgs[0].exprFrame).nodeTypeId == KtNodeTypes.NULL_ID
                ) {
                    includes.addSystem("optional")
                    listOf("std::nullopt")
                } else {
                    args
                }
            val allArgs = listOf(receiverCode) + extraArgs
            return "${prefix}to_cancellation_exception(${allArgs.joinToString(", ")})"
        }
        if (receiverCode != "this") {
            tryEmitCoroutineIntrinsicExtCall(calleeKt, receiverCode) { args }?.let { return it }
        }

        // Standard member method mappings
        val op = if (receiverIsPointer) "->" else "."
        when (calleeKt) {
            "invoke" ->
                if (bundle.isDocExample) {
                    return if (receiverIsPointer) {
                        "(*$receiverCode)(${args.joinToString(", ")})"
                    } else {
                        "$receiverCode(${args.joinToString(", ")})"
                    }
                } else {
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    val callArgs = args.joinToString(", ")
                    return "([&](auto&& kxs_inv) -> decltype(auto) { " +
                        "if constexpr (requires { (*kxs_inv)($callArgs); }) return (*kxs_inv)($callArgs); " +
                        "else if constexpr (requires { kxs_inv->invoke($callArgs); }) " +
                        "return kxs_inv->invoke($callArgs); " +
                        "else if constexpr (requires { kxs_inv($callArgs); }) return kxs_inv($callArgs); " +
                        "else return kxs_inv.invoke($callArgs); }($receiverCode))"
                }
            "get" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    includes.addSystem("type_traits")
                    return "([&](auto&& kxs_gr) -> decltype(auto) { " +
                        "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_gr)>> || requires { kxs_gr.operator->(); }) { " +
                        "if constexpr (requires { kxs_gr->get_value(); }) return kxs_gr->get_value(); " +
                        "else return kxs_gr->get(); " +
                        "} else { " +
                        "if constexpr (requires { kxs_gr.get_value(); }) return kxs_gr.get_value(); " +
                        "else return kxs_gr.get(); } }($receiverCode))"
                }
            "set" ->
                if (!bundle.isDocExample && args.size == 1) {
                    includes.addSystem("type_traits")
                    includes.addSystem("utility")
                    return "([&](auto&& kxs_sr, auto&& kxs_sv) { " +
                        "if constexpr (std::is_pointer_v<std::remove_cvref_t<decltype(kxs_sr)>> || requires { kxs_sr.operator->(); }) { " +
                        "if constexpr (requires { kxs_sr->set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); }) kxs_sr->set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); " +
                        "else if constexpr (std::is_null_pointer_v<std::remove_cvref_t<decltype(kxs_sv)>> && requires { kxs_sr->set_value({}); }) kxs_sr->set_value({}); " +
                        "else kxs_sr->set(std::forward<decltype(kxs_sv)>(kxs_sv)); " +
                        "} else { " +
                        "if constexpr (requires { kxs_sr.set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); }) kxs_sr.set_value(std::forward<decltype(kxs_sv)>(kxs_sv)); " +
                        "else if constexpr (std::is_null_pointer_v<std::remove_cvref_t<decltype(kxs_sv)>> && requires { kxs_sr.set_value({}); }) kxs_sr.set_value({}); " +
                        "else kxs_sr.set(std::forward<decltype(kxs_sv)>(kxs_sv)); } }($receiverCode, ${args[0]}))"
                }
            "getAndSet" ->
                if (!bundle.isDocExample && args.size == 1) {
                    return "$receiverCode${op}get_and_set(${args[0]})"
                }
            "compareAndSet" ->
                if (!bundle.isDocExample && args.size == 2) {
                    return "$receiverCode${op}compare_and_set(${args[0]}, ${args[1]})"
                }
            "isEmpty" -> return "$receiverCode${op}empty()"
            "isNotEmpty" -> return "(!$receiverCode${op}empty())"
            "toTypedArray" -> return if (receiverIsPointer) "(*$receiverCode)" else receiverCode
            "add" ->
                if (!bundle.isDocExample && args.size == 1) {
                    includes.addSystem("type_traits")
                    return "([&](auto&& kxs_ac, auto&& kxs_av) -> bool { " +
                        "auto kxs_add_one = [&](auto& kxs_c) -> bool { " +
                        "if constexpr (requires { kxs_c.push_back(kxs_av); }) { " +
                        "kxs_c.push_back(kxs_av); return true; " +
                        "} else if constexpr (requires { kxs_c.insert(kxs_av); }) { " +
                        "auto kxs_ir = kxs_c.insert(kxs_av); " +
                        "if constexpr (requires { kxs_ir.second; }) return kxs_ir.second; " +
                        "else return true; " +
                        "} else { return kxs_c.add(kxs_av); } }; " +
                        "using KxsAC = std::remove_cvref_t<decltype(kxs_ac)>; " +
                        "if constexpr (std::is_pointer_v<KxsAC> || requires { kxs_ac.operator->(); }) { " +
                        "return kxs_add_one(*kxs_ac); " +
                        "} else { " +
                        "return kxs_add_one(kxs_ac); } }($receiverCode, ${args[0]}))"
                } else {
                    return "$receiverCode${op}push_back(${args.joinToString(", ")})"
                }
            "toString" -> return "$receiverCode${op}to_string()"
            "hashCode" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    return emitHashCodeExpr(receiverCode)
                }
            "getOrThrow" -> return "$receiverCode${op}get_or_throw()"
            "getOrElse" ->
                if (!bundle.isDocExample && args.size == 1 && calleeKt !in bundle.symbols.functionsByName) {
                    includes.needsResultHelpersDef = true
                    return "kxs_result_get_or_else($receiverCode, ${args[0]})"
                }
            "toState" ->
                if (!bundle.isDocExample && calleeKt !in bundle.symbols.functionsByName) {
                    includes.addProject("kotlinx/coroutines/CompletionState.hpp")
                    val fnName =
                        if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                            "to_state"
                        } else {
                            "kotlinx::coroutines::to_state"
                        }
                    return "$fnName(${(listOf(receiverCode) + args).joinToString(", ")})"
                }
            "unboxState" ->
                if (!bundle.isDocExample &&
                    args.isEmpty() &&
                    enclosingClass?.kotlinName != "JobSupport" &&
                    calleeKt !in bundle.symbols.functionsByName
                ) {
                    val qual =
                        CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                            kotlinName = "unboxState",
                            cppBaseName = "unbox_state",
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        ) ?: "kotlinx::coroutines::unbox_state"
                    return "$qual($receiverCode)"
                }
            "resumeWithException" ->
                if (!bundle.isDocExample &&
                    args.size == 1 &&
                    calleeKt !in bundle.symbols.functionsByName
                ) {
                    includes.needsCoroutineIntrinsicsBridgeDef = true
                    includes.needsResultHelpersDef = true
                    includes.addProject("kotlinx/coroutines/Continuation.hpp")
                    return "kxs_resume_with_exception($receiverCode, ${args[0]})"
                }
            "resume" ->
                if (!bundle.isDocExample &&
                    args.size == 1 &&
                    unwrapTransparentFrame(argFrames[0]).nodeTypeId != KtNodeTypes.LAMBDA_EXPRESSION_ID &&
                    calleeKt !in bundle.symbols.functionsByName
                ) {
                    includes.needsCoroutineIntrinsicsBridgeDef = true
                    includes.addProject("kotlinx/coroutines/Continuation.hpp")
                    return "kxs_resume_cont($receiverCode, ${args[0]})"
                }
            "inv" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    return "(~$receiverCode)"
                }
            "exceptionOrNull" -> return "$receiverCode${op}exception_or_null()"
            "addSuppressed" -> if (!bundle.isDocExample) return "((void)0)"
            "printStackTrace" -> if (!bundle.isDocExample && args.isEmpty()) return "((void)0)"
            "toBoolean" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    return emitStringToBooleanExpr(receiverCode)
                }
            "toIntOrNull" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    includes.addSystem("charconv")
                    includes.addSystem("cstdint")
                    includes.addSystem("optional")
                    includes.addSystem("string")
                    return "([&](const std::string& kxs_s) -> std::optional<std::int32_t> { " +
                        "std::int32_t kxs_v = 0; auto [kxs_p, kxs_ec] = std::from_chars(kxs_s.data(), kxs_s.data() + kxs_s.size(), kxs_v); " +
                        "if (kxs_ec == std::errc{} && kxs_p == kxs_s.data() + kxs_s.size()) return kxs_v; return std::nullopt; }($receiverCode))"
                }
            "toLongOrNull" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    includes.addSystem("charconv")
                    includes.addSystem("cstdint")
                    includes.addSystem("optional")
                    includes.addSystem("string")
                    return "([&](const std::string& kxs_s) -> std::optional<std::int64_t> { " +
                        "std::int64_t kxs_v = 0; auto [kxs_p, kxs_ec] = std::from_chars(kxs_s.data(), kxs_s.data() + kxs_s.size(), kxs_v); " +
                        "if (kxs_ec == std::errc{} && kxs_p == kxs_s.data() + kxs_s.size()) return kxs_v; return std::nullopt; }($receiverCode))"
                }
            "toLong" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    includes.addSystem("cstdint")
                    return "static_cast<std::int64_t>($receiverCode)"
                }
            "toInt" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    includes.addSystem("cstdint")
                    return "static_cast<std::int32_t>($receiverCode)"
                }
            "toDouble" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    return "static_cast<double>($receiverCode)"
                }
            "toFloat" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    return "static_cast<float>($receiverCode)"
                }
            "toShort" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    includes.addSystem("cstdint")
                    return "static_cast<std::int16_t>($receiverCode)"
                }
            "toByte" ->
                if (!bundle.isDocExample && args.isEmpty()) {
                    includes.addSystem("cstdint")
                    return "static_cast<std::int8_t>($receiverCode)"
                }
            "coerceAtLeast" ->
                if (!bundle.isDocExample && args.size == 1) {
                    includes.addSystem("algorithm")
                    includes.addSystem("type_traits")
                    return "([&](auto kxs_v, auto kxs_min) { " +
                        "using KxsCT = std::common_type_t<std::decay_t<decltype(kxs_v)>, " +
                        "std::decay_t<decltype(kxs_min)>>; " +
                        "return std::max<KxsCT>(static_cast<KxsCT>(kxs_v), " +
                        "static_cast<KxsCT>(kxs_min)); }($receiverCode, ${args[0]}))"
                }
            "coerceAtMost" ->
                if (!bundle.isDocExample && args.size == 1) {
                    includes.addSystem("algorithm")
                    includes.addSystem("type_traits")
                    return "([&](auto kxs_v, auto kxs_max) { " +
                        "using KxsCT = std::common_type_t<std::decay_t<decltype(kxs_v)>, " +
                        "std::decay_t<decltype(kxs_max)>>; " +
                        "return std::min<KxsCT>(static_cast<KxsCT>(kxs_v), " +
                        "static_cast<KxsCT>(kxs_max)); }($receiverCode, ${args[0]}))"
                }
            "coerceIn" ->
                if (!bundle.isDocExample && args.size == 2) {
                    includes.addSystem("algorithm")
                    includes.addSystem("type_traits")
                    return "([&](auto kxs_v, auto kxs_lo, auto kxs_hi) { " +
                        "using KxsCT = std::common_type_t<std::decay_t<decltype(kxs_v)>, " +
                        "std::decay_t<decltype(kxs_lo)>, std::decay_t<decltype(kxs_hi)>>; " +
                        "return std::clamp<KxsCT>(static_cast<KxsCT>(kxs_v), " +
                        "static_cast<KxsCT>(kxs_lo), static_cast<KxsCT>(kxs_hi)); }" +
                        "($receiverCode, ${args[0]}, ${args[1]}))"
                }
            "let" ->
                if (args.size == 1) {
                    return "(${args[0]})($receiverCode)"
                }
            "also" ->
                if (args.size == 1) {
                    val alsoTemp = freshTemp("kxs_also")
                    return "([&]() { auto&& $alsoTemp = $receiverCode; (${args[0]})($alsoTemp); return $alsoTemp; }())"
                }
            "apply", "run" ->
                if (argFrames.size == 1) {
                    emitScopeApplyOrRunCall(
                        calleeKt,
                        receiverFrame,
                        receiverCode,
                        argFrames,
                        lazy(LazyThreadSafetyMode.NONE) { args },
                    )?.let { return it }
                }
            "takeIf", "takeUnless" ->
                if (!bundle.isDocExample && args.size == 1) {
                    includes.addSystem("optional")
                    includes.addSystem("type_traits")
                    val condPrefix = if (calleeKt == "takeUnless") "!" else ""
                    return "([&]() { auto&& kxs_ti = $receiverCode; " +
                        "using KxsTi = std::decay_t<decltype(kxs_ti)>; " +
                        "if constexpr (std::is_pointer_v<KxsTi> || requires { kxs_ti.operator->(); } || requires { kxs_ti.has_value(); }) { " +
                        "return ($condPrefix(${args[0]})(kxs_ti)) ? kxs_ti : KxsTi{}; " +
                        "} else { " +
                        "return ($condPrefix(${args[0]})(kxs_ti)) ? std::optional<KxsTi>(kxs_ti) : std::optional<KxsTi>{}; " +
                        "} }())"
                }
            "forEach" ->
                if (args.size == 1) {
                    return emitForEachExpr(receiverCode, args[0])
                }
            "plus" ->
                if (!bundle.isDocExample && args.size == 1) {
                    includes.addSystem("type_traits")
                    return "([&](const auto& kxs_pl, const auto& kxs_pr) { " +
                        "if constexpr (requires { kxs_pl->plus(kxs_pr); }) return kxs_pl->plus(kxs_pr); " +
                        "else if constexpr (requires { kxs_pl.plus(kxs_pr); }) return kxs_pl.plus(kxs_pr); " +
                        "else return (kxs_pl + kxs_pr); }($receiverCode, ${args[0]}))"
                }
        }
    }

    // Constructor invocation vs function/method invocation
    val effectiveCalleeKt = bundle.symbols.importAliases[calleeKt] ?: calleeKt
    val sameFileClass = lookupAccessibleClassByName(calleeKt) ?: lookupAccessibleClassByName(effectiveCalleeKt)
    val sameFileFnExists =
        calleeKt in bundle.symbols.functionsByName || effectiveCalleeKt in bundle.symbols.functionsByName
    val isSameFileConcreteCtor =
        sameFileClass != null &&
            sameFileClass.kind != KtClassKind.INTERFACE &&
            (!PacketFlags.hasFlag(stream.frames[sameFileClass.frameIndex].flags, PacketFlags.MOD_ABSTRACT) || !sameFileFnExists)
    val isExternalTypeCtor =
        sameFileClass == null &&
            effectiveCalleeKt.firstOrNull()?.isUpperCase() == true &&
            !sameFileFnExists &&
            (bundle.isDocExample || effectiveCalleeKt !in KNOWN_PASCAL_FACTORY_FUNCTIONS)
    val isCtorCall = receiverCode == null && (isSameFileConcreteCtor || isExternalTypeCtor)
    val effectiveArgs by lazy(LazyThreadSafetyMode.NONE) {
        if (isCtorCall && sameFileClass != null && sameFileClass.isInner) {
            val outerPtr = resolveOuterPointerExpr(sameFileClass.enclosingClassFrameIndex)
            if (outerPtr != null) listOf(outerPtr) + args else args
        } else {
            args
        }
    }

    if (receiverCode == null && !isCtorCall && enclosingClass != null && enclosingClass.isInner) {
        val hasOwnMethod =
            enclosingClass.memberFunctionFrameIndices.any {
                bundle.symbols.functionsByFrame[it]?.kotlinName == calleeKt
            }
        if (!hasOwnMethod) {
            var cur = enclosingClass.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            while (cur != null) {
                val outerFn =
                    cur.memberFunctionFrameIndices
                        .mapNotNull { bundle.symbols.functionsByFrame[it] }
                        .firstOrNull { it.kotlinName == calleeKt }
                if (outerFn != null) {
                    val chain = resolveOuterChainPrefix(cur.frameIndex).orEmpty()
                    val tSuffix = if (typeArgs.isNotEmpty()) "<${typeArgs.joinToString(", ")}>" else ""
                    return "$chain${outerFn.cppName}$tSuffix(${effectiveArgs.joinToString(", ")})"
                }
                if (!cur.isInner) break
                cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
            }
        }
    }

    val activeRecvSub = receiverSubstitution
    if (receiverCode == null && !isCtorCall && activeRecvSub != null && resolveCallableBoundName(calleeKt) == null) {
        tryEmitCoroutineIntrinsicExtCall(calleeKt, activeRecvSub) { effectiveArgs }?.let { return it }
        val implicitCandidates =
            bundle.symbols.functionsByName[calleeKt]
                ?.filter { it.receiverTypeFrameIndex != null && isAccessibleExtensionFn(it) }
                .orEmpty()
        val implicitExtFn =
            implicitCandidates.firstOrNull { doesFnMatchRawArgs(it.valueParams, rawCallArgs) }
                ?: implicitCandidates.firstOrNull()
        if (implicitExtFn != null) {
            val boundMap = mapCallArgsToParamIndices(implicitExtFn.valueParams, rawCallArgs)
            val renderedArgs =
                if (boundMap != null) {
                    emitAlignedArgsForParams(implicitExtFn.valueParams, boundMap)
                } else {
                    effectiveArgs
                }
            val effTypeArgs =
                inferMissingFunctionTypeArgs(
                    fn = implicitExtFn,
                    explicitTypeArgs = typeArgs,
                    targetCppForInference = targetCppForInference,
                    receiverFrame = null,
                )
            val allArgs = ArrayList<String>(renderedArgs.size + 1)
            allArgs.add(activeRecvSub)
            allArgs.addAll(renderedArgs)
            val tSuffix = if (effTypeArgs.isNotEmpty()) "<${effTypeArgs.joinToString(", ")}>" else ""
            return "${formatExtensionFnCallTarget(implicitExtFn)}$tSuffix(${allArgs.joinToString(", ")})"
        }
    }
    if (receiverCode == null &&
        !isCtorCall &&
        activeRecvSub == null &&
        enclosingClass != null &&
        resolveCallableBoundName(calleeKt) == null
    ) {
        val hasOwnOrOuterOrCompanionMemberFn =
            generateSequence(enclosingClass) { cur ->
                if (cur.isInner) cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] } else null
            }.any { cur ->
                cur.memberFunctionFrameIndices.any { bundle.symbols.functionsByFrame[it]?.kotlinName == calleeKt }
            } ||
                enclosingClass.companionFrameIndex
                    ?.let { bundle.symbols.classesByFrame[it] }
                    ?.memberFunctionFrameIndices
                    ?.any { bundle.symbols.functionsByFrame[it]?.kotlinName == calleeKt } == true
        if (!hasOwnOrOuterOrCompanionMemberFn) {
            val intrinsicThis =
                if (!bundle.isDocExample && isSharedThisClass(enclosingClass.kotlinName)) {
                    includes.needsSharedFromThisDef = true
                    includes.addSystem("memory")
                    includes.addSystem("type_traits")
                    "kxs_shared_from_this(this)"
                } else {
                    "this"
                }
            tryEmitCoroutineIntrinsicExtCall(calleeKt, intrinsicThis) { effectiveArgs }?.let { return it }
            val sameFileThisExtCandidates =
                bundle.symbols.functionsByName[calleeKt]
                    ?.filter { fn ->
                        fn.receiverTypeFrameIndex != null &&
                            isAccessibleExtensionFn(fn) &&
                            doesClassMatchExtensionReceiver(enclosingClass, fn.receiverTypeFrameIndex)
                    }.orEmpty()
            val sameFileThisExtFn =
                sameFileThisExtCandidates.firstOrNull { doesFnMatchRawArgs(it.valueParams, rawCallArgs) }
                    ?: sameFileThisExtCandidates.firstOrNull()
            if (sameFileThisExtFn != null) {
                val recvCppT =
                    translateType(
                        stream.frames[sameFileThisExtFn.receiverTypeFrameIndex!!],
                        CppTypePosition.EXTENSION_RECEIVER,
                    )
                val selfArg =
                    when {
                        recvCppT.startsWith("std::shared_ptr<") -> {
                            includes.needsSharedFromThisDef = true
                            includes.addSystem("memory")
                            includes.addSystem("type_traits")
                            "kxs_shared_from_this(this)"
                        }
                        recvCppT.endsWith("*") -> "this"
                        else -> "(*this)"
                    }
                val boundMap = mapCallArgsToParamIndices(sameFileThisExtFn.valueParams, rawCallArgs)
                val renderedArgs =
                    if (boundMap != null) {
                        emitAlignedArgsForParams(sameFileThisExtFn.valueParams, boundMap)
                    } else {
                        effectiveArgs
                    }
                val effTypeArgs =
                    inferMissingFunctionTypeArgs(
                        fn = sameFileThisExtFn,
                        explicitTypeArgs = typeArgs,
                        targetCppForInference = targetCppForInference,
                        receiverFrame = null,
                    )
                val allArgs = ArrayList<String>(renderedArgs.size + 1)
                allArgs.add(selfArg)
                allArgs.addAll(renderedArgs)
                val tSuffix = if (effTypeArgs.isNotEmpty()) "<${effTypeArgs.joinToString(", ")}>" else ""
                return "${formatExtensionFnCallTarget(sameFileThisExtFn)}$tSuffix(${allArgs.joinToString(", ")})"
            }
        }
    }

    val callArgs by lazy(LazyThreadSafetyMode.NONE) {
        if (receiverCode != null &&
            (calleeKt == "minusKey" || calleeKt == "get") &&
            rawCallArgs.size == 1 &&
            effectiveArgs.size == 1 &&
            effectiveArgs[0].endsWith("::Key::instance()")
        ) {
            includes.addSystem("memory")
            listOf("std::addressof(${effectiveArgs[0]})")
        } else {
            effectiveArgs
        }
    }

    val privateZeroArgCollidingFnName: String? =
        if ((receiverCode == null || receiverCode == "this") &&
            !isCtorCall &&
            enclosingClass != null &&
            rawCallArgs.isEmpty()
        ) {
            val mFn =
                enclosingClass.memberFunctionFrameIndices
                    .asSequence()
                    .mapNotNull { bundle.symbols.functionsByFrame[it] }
                    .firstOrNull {
                        it.kotlinName == calleeKt &&
                            it.valueParams.isEmpty() &&
                            PacketFlags.hasFlag(stream.frames[it.frameIndex].flags, PacketFlags.MOD_PRIVATE)
                    }
            val hasSameNameProp =
                enclosingClass.primaryConstructorParams.any { (it.isVal || it.isVar) && it.name == calleeKt } ||
                    enclosingClass.memberPropertyFrameIndices.any {
                        bundle.symbols.propertiesByFrame[it]?.kotlinName == calleeKt
                    }
            val collidesWithProp = mFn != null && hasSameNameProp
            if (collidesWithProp) "${mFn.cppName}_fn" else null
        } else {
            null
        }

    var matchedCallFn: KtFunctionSymbol? = null
    val targetName =
        when {
            isCtorCall -> {
                val baseType = sameFileClass?.cppName ?: CppNamingPolicy.toTypeName(effectiveCalleeKt)
                if (sameFileClass == null && !bundle.isDocExample) {
                    CppTypeTranslator.includeAndQualifyKnownType(
                        simpleName = effectiveCalleeKt,
                        cppBaseName = baseType,
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: baseType
                } else {
                    baseType
                }
            }
            receiverCode == null -> {
                if (!bundle.isDocExample && resolveLocalTypeSimpleName(calleeKt) == "CoroutineStart") {
                    includes.addProject("kotlinx/coroutines/CoroutineStart.hpp")
                    val fnTarget =
                        if (bundle.symbols.namespaceSegments == listOf("kotlinx", "coroutines")) {
                            "invoke"
                        } else {
                            "kotlinx::coroutines::invoke"
                        }
                    val boundTarget =
                        resolveBoundName(calleeKt)
                            ?: resolveUnqualifiedPropertyAccess(calleeKt)
                            ?: CppNamingPolicy.toSnakeCase(calleeKt)
                    val allInvokeArgs = listOf(boundTarget) + callArgs
                    return "$fnTarget(${allInvokeArgs.joinToString(", ")})"
                }
                val sameFileFnList =
                    bundle.symbols.functionsByName[calleeKt] ?: bundle.symbols.functionsByName[effectiveCalleeKt]
                val callableBound = resolveCallableBoundName(calleeKt)
                if (callableBound != null) {
                    val recvParamArity = callableReceiverParamArity(calleeKt)
                    if (recvParamArity != null && rawCallArgs.size == recvParamArity) {
                        val implicitRecv =
                            receiverSubstitution
                                ?: if (enclosingClass != null) {
                                    if (!bundle.isDocExample && isSharedThisClass(enclosingClass.kotlinName)) {
                                        includes.needsSharedFromThisDef = true
                                        includes.addSystem("memory")
                                        includes.addSystem("type_traits")
                                        "kxs_shared_from_this(this)"
                                    } else {
                                        "(*this)"
                                    }
                                } else {
                                    null
                                }
                        if (implicitRecv != null) {
                            val allArgs = listOf(implicitRecv) + callArgs
                            return "$callableBound(${allArgs.joinToString(", ")})"
                        }
                    }
                }
                val callableProp = if (callableBound == null) resolveEnclosingCallableProperty(calleeKt) else null
                val enclosingMemberFns =
                    enclosingClass
                        ?.memberFunctionFrameIndices
                        ?.asSequence()
                        ?.mapNotNull { bundle.symbols.functionsByFrame[it] }
                        ?.toList()
                val matchedEnclosingMemberFn =
                    enclosingMemberFns?.firstOrNull {
                        it.kotlinName == calleeKt && doesFnMatchRawArgs(it.valueParams, rawCallArgs)
                    }
                val enclosingMemberFn =
                    matchedEnclosingMemberFn ?: enclosingMemberFns?.firstOrNull { it.kotlinName == calleeKt }
                val companionMemberFnIndices =
                    enclosingClass
                        ?.companionFrameIndex
                        ?.let { bundle.symbols.classesByFrame[it] }
                        ?.memberFunctionFrameIndices
                val isCompanionFn =
                    companionMemberFnIndices?.any {
                        bundle.symbols.functionsByFrame[it]?.kotlinName == calleeKt
                    } == true
                val importedCompanionOwner =
                    if (callableBound == null &&
                        callableProp == null &&
                        privateZeroArgCollidingFnName == null &&
                        enclosingMemberFn == null &&
                        !isCompanionFn
                    ) {
                        bundle.symbols.importedCompanionMembers[calleeKt]
                    } else {
                        null
                    }
                val importedCompanionQualified =
                    if (importedCompanionOwner != null) {
                        val ownerSameCls = lookupAccessibleClassByName(importedCompanionOwner)
                        val ownerBase =
                            ownerSameCls?.cppName
                                ?: if (!bundle.isDocExample) {
                                    CppTypeTranslator.includeAndQualifyKnownType(
                                        simpleName = importedCompanionOwner,
                                        cppBaseName = CppNamingPolicy.toTypeName(importedCompanionOwner),
                                        currentNs = bundle.symbols.namespaceSegments,
                                        includes = includes,
                                    ) ?: CppNamingPolicy.toTypeName(importedCompanionOwner)
                                } else {
                                    CppNamingPolicy.toTypeName(importedCompanionOwner)
                                }
                        val inferredOwnerArgs =
                            extractOuterTemplateArgsForBase(targetCppForInference, ownerBase)
                        val ownerQual =
                            when {
                                ownerSameCls != null && enclosingClass?.frameIndex == ownerSameCls.frameIndex ->
                                    null
                                inferredOwnerArgs != null ->
                                    "$ownerBase<${inferredOwnerArgs.joinToString(", ")}>"
                                ownerSameCls != null && ownerSameCls.typeParams.isNotEmpty() ->
                                    "$ownerBase<${ownerSameCls.typeParams.joinToString(", ") { "void*" }}>"
                                ownerSameCls == null &&
                                    importedCompanionOwner in
                                    setOf(
                                        "Channel",
                                        "ChannelResult",
                                        "SendChannel",
                                        "ReceiveChannel",
                                        "BufferedChannel",
                                        "BroadcastChannel",
                                    ) -> "$ownerBase<void*>"
                                else -> ownerBase
                            }
                        val compMemberCpp =
                            ownerSameCls
                                ?.companionFrameIndex
                                ?.let { bundle.symbols.classesByFrame[it] }
                                ?.memberFunctionFrameIndices
                                ?.asSequence()
                                ?.mapNotNull { bundle.symbols.functionsByFrame[it] }
                                ?.firstOrNull { it.kotlinName == calleeKt }
                                ?.cppName
                                ?: CppNamingPolicy.toSnakeCase(calleeKt)
                        if (ownerQual != null) "$ownerQual::$compMemberCpp" else compMemberCpp
                    } else {
                        null
                    }
                val sameFileTopCandidates =
                    sameFileFnList
                        ?.filter { it.enclosingClassFrameIndex == null && it.receiverTypeFrameIndex == null }
                        .orEmpty()
                val matchedSameFileTopFn =
                    sameFileTopCandidates.firstOrNull { doesFnMatchRawArgs(it.valueParams, rawCallArgs) }
                if (callableBound == null &&
                    callableProp == null &&
                    privateZeroArgCollidingFnName == null
                ) {
                    matchedCallFn =
                        when {
                            matchedEnclosingMemberFn != null -> matchedEnclosingMemberFn
                            enclosingMemberFn == null &&
                                importedCompanionQualified == null &&
                                !isCompanionFn &&
                                matchedSameFileTopFn != null -> matchedSameFileTopFn
                            else -> null
                        }
                }
                val knownQualifiedFn =
                    if (callableBound == null &&
                        callableProp == null &&
                        privateZeroArgCollidingFnName == null &&
                        enclosingMemberFn == null &&
                        importedCompanionQualified == null &&
                        sameFileFnList == null &&
                        !bundle.isDocExample &&
                        !isVerbatimCIdentifier(effectiveCalleeKt)
                    ) {
                        CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                            kotlinName = effectiveCalleeKt,
                            cppBaseName = CppNamingPolicy.toSnakeCase(effectiveCalleeKt),
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        )
                    } else {
                        null
                    }
                val rawTarget =
                    callableBound
                        ?: callableProp
                        ?: privateZeroArgCollidingFnName
                        ?: enclosingMemberFn?.cppName
                        ?: importedCompanionQualified
                        ?: matchedSameFileTopFn?.cppName
                        ?: sameFileFnList?.firstOrNull()?.cppName
                        ?: knownQualifiedFn
                        ?: if (isVerbatimCIdentifier(effectiveCalleeKt)) {
                            recordExternalCHeaderForIdentifier(effectiveCalleeKt)
                            effectiveCalleeKt
                        } else {
                            CppNamingPolicy.toSnakeCase(effectiveCalleeKt)
                        }
                val isSameFileTopLevelFn = sameFileFnList?.any { it.enclosingClassFrameIndex == null } == true
                val needsDependentThis =
                    !bundle.isDocExample &&
                        enclosingClass != null &&
                        enclosingClass.typeParams.isNotEmpty() &&
                        enclosingClass.superTypeFrameIndices.isNotEmpty() &&
                        callableBound == null &&
                        callableProp == null &&
                        importedCompanionQualified == null &&
                        !isSameFileTopLevelFn &&
                        !isCompanionFn &&
                        knownQualifiedFn == null &&
                        !isVerbatimCIdentifier(effectiveCalleeKt) &&
                        "::" !in rawTarget
                if (needsDependentThis) "this->$rawTarget" else rawTarget
            }
            else -> {
                privateZeroArgCollidingFnName ?: run {
                    val aliasedCallee = bundle.symbols.importAliases[calleeKt] ?: calleeKt
                    if (isVerbatimCIdentifier(aliasedCallee)) {
                        recordExternalCHeaderForIdentifier(aliasedCallee)
                        aliasedCallee
                    } else {
                        CppNamingPolicy.toSnakeCase(aliasedCallee)
                    }
                }
            }
        }

    val matchedCtorParams =
        if (isCtorCall &&
            sameFileClass != null &&
            doesFnMatchRawArgs(sameFileClass.primaryConstructorParams, rawCallArgs)
        ) {
            sameFileClass.primaryConstructorParams
        } else {
            null
        }
    val rawAlignedArgs =
        when {
            matchedCtorParams != null -> {
                val aligned =
                    emitAlignedArgsForParams(
                        matchedCtorParams,
                        mapCallArgsToParamIndices(matchedCtorParams, rawCallArgs)!!,
                    )
                if (sameFileClass?.isInner == true) {
                    val outerPtr = resolveOuterPointerExpr(sameFileClass.enclosingClassFrameIndex)
                    if (outerPtr != null) listOf(outerPtr) + aligned else aligned
                } else {
                    aligned
                }
            }
            matchedCallFn != null ->
                emitAlignedArgsForParams(
                    matchedCallFn.valueParams,
                    mapCallArgsToParamIndices(matchedCallFn.valueParams, rawCallArgs)!!,
                )
            else -> callArgs
        }
    val finalCallArgs =
        if (!bundle.isDocExample &&
            !isCtorCall &&
            calleeKt in setOf("cancelInternal", "cancelCoroutine") &&
            (receiverCode == null || receiverCode == "this") &&
            rawAlignedArgs.size == 1 &&
            !rawAlignedArgs[0].trimStart().startsWith("kxs_to_exception_ptr(")
        ) {
            includes.addSystem("exception")
            includes.addSystem("optional")
            includes.addSystem("type_traits")
            includes.addSystem("utility")
            includes.needsResultHelpersDef = true
            listOf("kxs_to_exception_ptr(${rawAlignedArgs[0]})")
        } else {
            rawAlignedArgs
        }
    val inferredCtorTypeArgs =
        if (isCtorCall && typeArgs.isEmpty()) {
            val directArgs = extractOuterTemplateArgsForBase(targetCppForInference, targetName)
            if (!directArgs.isNullOrEmpty()) {
                directArgs
            } else {
                val expectedArity =
                    sameFileClass?.typeParams?.size ?: CppTypeTranslator.knownTemplateArity(effectiveCalleeKt)
                val isSharedTarget =
                    effectiveCalleeKt in KNOWN_GENERIC_SHARED_CLASSES ||
                        sameFileClass?.let { isSharedThisClass(it.kotlinName) } == true
                if (expectedArity == 1) {
                    val unwrappedSharedArg =
                        targetCppForInference
                            ?.trim()
                            ?.removePrefix("const ")
                            ?.removeSuffix("&")
                            ?.trim()
                            ?.takeIf { isSharedTarget && it.startsWith("std::shared_ptr<") }
                            ?.let { extractAnyOuterTemplateArgs(it) }
                            ?.takeIf { it.size == 1 }
                    when {
                        unwrappedSharedArg != null -> unwrappedSharedArg
                        isSharedTarget && enclosingFunction?.typeParams?.size == 1 ->
                            listOf(CppNamingPolicy.toTypeName(enclosingFunction.typeParams[0].name))
                        isSharedTarget && enclosingClass?.typeParams?.size == 1 ->
                            listOf(CppNamingPolicy.toTypeName(enclosingClass.typeParams[0].name))
                        else -> emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        } else {
            emptyList()
        }
    val inferredFnTypeArgs =
        when {
            !isCtorCall && matchedCallFn != null ->
                inferMissingFunctionTypeArgs(
                    fn = matchedCallFn,
                    explicitTypeArgs = typeArgs,
                    targetCppForInference = targetCppForInference,
                    receiverFrame = null,
                )
            !isCtorCall &&
                calleeKt == "Channel" &&
                typeArgs.isEmpty() &&
                targetCppForInference != null ->
                inferChannelFactoryElementTypeArg(targetCppForInference)
            else -> emptyList()
        }
    val effectiveTypeArgs =
        when {
            typeArgs.isNotEmpty() -> typeArgs
            isCtorCall -> inferredCtorTypeArgs
            else -> inferredFnTypeArgs
        }
    val tSuffix = if (effectiveTypeArgs.isNotEmpty()) "<${effectiveTypeArgs.joinToString(", ")}>" else ""
    val isSharedCtorTarget =
        CppTypeTranslator.isSharedHierarchyType(effectiveCalleeKt) ||
            effectiveCalleeKt in bundle.symbols.sharedHierarchyTypeNames
    val isNonAbstractSameFileClass =
        sameFileClass == null ||
            !PacketFlags.hasFlag(stream.frames[sameFileClass.frameIndex].flags, PacketFlags.MOD_ABSTRACT)
    if (isCtorCall &&
        !bundle.isDocExample &&
        isSharedCtorTarget &&
        sameFileClass?.kind != KtClassKind.INTERFACE &&
        isNonAbstractSameFileClass
    ) {
        includes.addSystem("memory")
        val hasClassTypeParams =
            (sameFileClass != null && sameFileClass.typeParams.isNotEmpty()) ||
                (sameFileClass == null && effectiveCalleeKt in KNOWN_GENERIC_SHARED_CLASSES)
        val isGenericSharedWithoutArgs = effectiveTypeArgs.isEmpty() && hasClassTypeParams
        return if (isGenericSharedWithoutArgs) {
            "std::shared_ptr(new $targetName(${finalCallArgs.joinToString(", ")}))"
        } else {
            "std::make_shared<$targetName$tSuffix>(${finalCallArgs.joinToString(", ")})"
        }
    }
    return if (receiverCode == null) {
        "$targetName$tSuffix(${finalCallArgs.joinToString(", ")})"
    } else {
        val op = if (receiverIsPointer) "->" else "."
        val tmplKw =
            if (tSuffix.isNotEmpty() && isDependentTemplateReceiver(receiverCode, receiverFrame)) {
                "template "
            } else {
                ""
            }
        "$receiverCode$op$tmplKw$targetName$tSuffix(${finalCallArgs.joinToString(", ")})"
    }
}

internal fun CppExprAndStmtEmitter.isDependentTemplateReceiver(
    recvExpr: String,
    recvFrame: PacketFrame?,
): Boolean {
    if (bundle.isDocExample || activeTypeParams.isEmpty()) return false
    if (recvFrame != null && inferExpressionSimpleTypeName(recvFrame) in activeTypeParams) {
        return true
    }
    return activeTypeParams.any { tp ->
        Regex("""\b${Regex.escape(tp)}\b""").containsMatchIn(recvExpr)
    }
}

internal fun CppExprAndStmtEmitter.isEnclosingOrSuperClass(
    cls: KtClassSymbol?,
    targetClsFrameIdx: Int,
    visited: MutableSet<Int> = HashSet(),
): Boolean {
    if (cls == null || !visited.add(cls.frameIndex)) return false
    if (cls.frameIndex == targetClsFrameIdx) return true
    for (superIdx in cls.superTypeFrameIndices) {
        val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
        val superCls = lookupAccessibleClassByName(superSimple) ?: continue
        if (isEnclosingOrSuperClass(superCls, targetClsFrameIdx, visited)) return true
    }
    return false
}

internal fun CppExprAndStmtEmitter.isAccessibleExtensionFn(fn: KtFunctionSymbol): Boolean =
    fn.enclosingClassFrameIndex == null ||
        (enclosingClass != null && isEnclosingOrSuperClass(enclosingClass, fn.enclosingClassFrameIndex))

internal fun CppExprAndStmtEmitter.formatExtensionFnCallTarget(fn: KtFunctionSymbol): String {
    val isDependentMemberExt =
        !bundle.isDocExample &&
            enclosingClass != null &&
            fn.enclosingClassFrameIndex != null &&
            isEnclosingOrSuperClass(enclosingClass, fn.enclosingClassFrameIndex) &&
            enclosingClass.typeParams.isNotEmpty() &&
            enclosingClass.superTypeFrameIndices.isNotEmpty() &&
            !inDefaultParameterExpr
    return if (isDependentMemberExt) "this->${fn.cppName}" else fn.cppName
}

internal fun CppExprAndStmtEmitter.emitHashCodeExpr(recvExpr: String): String {
    includes.addSystem("any")
    includes.addSystem("cstdint")
    includes.addSystem("exception")
    includes.addSystem("functional")
    includes.addSystem("memory")
    includes.addSystem("type_traits")
    includes.addSystem("typeinfo")
    return "([&](const auto& kxs_hr) -> std::int32_t { " +
        "using KxsH = std::remove_cvref_t<decltype(kxs_hr)>; " +
        "if constexpr (std::is_same_v<KxsH, std::exception_ptr>) { " +
        "return kxs_hr ? static_cast<std::int32_t>(1) : static_cast<std::int32_t>(0); " +
        "} else if constexpr (std::is_same_v<KxsH, std::any>) { " +
        "return (kxs_hr.has_value() && kxs_hr.type() != typeid(std::nullptr_t)) ? " +
        "static_cast<std::int32_t>(kxs_hr.type().hash_code()) : static_cast<std::int32_t>(0); " +
        "} else if constexpr (requires { kxs_hr->hash_code(); }) { " +
        "return kxs_hr ? static_cast<std::int32_t>(kxs_hr->hash_code()) : static_cast<std::int32_t>(0); " +
        "} else if constexpr (requires { kxs_hr.hash_code(); }) { " +
        "return static_cast<std::int32_t>(kxs_hr.hash_code()); " +
        "} else if constexpr (std::is_pointer_v<KxsH>) { " +
        "return static_cast<std::int32_t>(std::hash<const void*>{}(kxs_hr)); " +
        "} else if constexpr (requires { kxs_hr.get(); }) { " +
        "return static_cast<std::int32_t>(std::hash<const void*>{}(kxs_hr.get())); " +
        "} else if constexpr (requires { std::hash<KxsH>{}(kxs_hr); }) { " +
        "return static_cast<std::int32_t>(std::hash<KxsH>{}(kxs_hr)); " +
        "} else { return static_cast<std::int32_t>(0); } }($recvExpr))"
}

internal fun CppExprAndStmtEmitter.splitTopLevelCommaList(inner: String): List<String> {
    val parts = ArrayList<String>()
    var angleDepth = 0
    var parenDepth = 0
    var bracketDepth = 0
    var start = 0
    for (i in 0 until inner.length) {
        when (inner[i]) {
            '<' -> angleDepth++
            '>' -> if (angleDepth > 0) angleDepth--
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            ',' ->
                if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                    parts.add(inner.substring(start, i).trim())
                    start = i + 1
                }
        }
    }
    val last = inner.substring(start).trim()
    if (last.isNotEmpty()) parts.add(last)
    return parts
}

internal fun CppExprAndStmtEmitter.extractOuterTemplateArgsForBase(
    rawCppType: String?,
    expectedBase: String,
): List<String>? {
    var cur =
        (rawCppType ?: return null)
            .trim()
            .removePrefix("const ")
            .removeSuffix("&")
            .trim()
    while (true) {
        val unwrapped =
            when {
                cur.startsWith("std::optional<") && cur.endsWith(">") ->
                    cur.removePrefix("std::optional<").dropLast(1).trim()
                cur.startsWith("std::shared_ptr<") && cur.endsWith(">") ->
                    cur.removePrefix("std::shared_ptr<").dropLast(1).trim()
                else -> break
            }
        cur = unwrapped
    }
    val ltIdx = cur.indexOf('<')
    if (ltIdx < 0 || !cur.endsWith(">")) return null
    val head = cur.substring(0, ltIdx).trim()
    val expectedSimple = expectedBase.substringAfterLast("::")
    if (head != expectedBase && head.substringAfterLast("::") != expectedSimple) return null
    val inner = cur.substring(ltIdx + 1, cur.length - 1).trim()
    if (inner.isEmpty()) return null
    return splitTopLevelCommaList(inner)
}

internal fun CppExprAndStmtEmitter.tryEmitCoroutineIntrinsicExtCall(
    calleeKt: String,
    recvExpr: String,
    argProvider: () -> List<String>,
): String? {
    if (bundle.isDocExample || calleeKt in bundle.symbols.functionsByName) return null
    val args by lazy(LazyThreadSafetyMode.NONE) { argProvider() }
    return when (calleeKt) {
        "intercepted" ->
            if (args.isEmpty()) {
                includes.needsCoroutineIntrinsicsBridgeDef = true
                "kxs_intercepted($recvExpr)"
            } else {
                null
            }
        "createCoroutineUnintercepted" ->
            if (args.size == 1 || args.size == 2) {
                includes.needsCoroutineIntrinsicsBridgeDef = true
                "kxs_create_coroutine_unintercepted($recvExpr, ${args.joinToString(", ")})"
            } else {
                null
            }
        "startCoroutineUninterceptedOrReturn" ->
            if (args.size == 1 || args.size == 2) {
                includes.needsCoroutineIntrinsicsBridgeDef = true
                "kxs_start_coroutine_unintercepted_or_return($recvExpr, ${args.joinToString(", ")})"
            } else {
                null
            }
        "resumeCancellableWith" ->
            if (args.size == 1) {
                includes.addProject("kotlinx/coroutines/internal/DispatchedContinuation.hpp")
                includes.addSystem("type_traits")
                "([&](auto&& kxs_rc, auto&& kxs_rv) { " +
                    "if constexpr (requires { kxs_rc->resume_cancellable_with(kxs_rv); }) kxs_rc->resume_cancellable_with(kxs_rv); " +
                    "else if constexpr (requires { kxs_rc.resume_cancellable_with(kxs_rv); }) kxs_rc.resume_cancellable_with(kxs_rv); " +
                    "else kotlinx::coroutines::internal::resume_cancellable_with(kxs_rc, kxs_rv); " +
                    "}($recvExpr, ${args[0]}))"
            } else {
                null
            }
        "startCoroutine" ->
            if (args.size == 1 || args.size == 2) {
                includes.needsCoroutineIntrinsicsBridgeDef = true
                "kxs_start_coroutine($recvExpr, ${args.joinToString(", ")})"
            } else {
                null
            }
        "startCoroutineCancellable" ->
            if (args.size == 1 || args.size == 2) {
                includes.addProject("kotlinx/coroutines/intrinsics/Cancellable.hpp")
                "kotlinx::coroutines::intrinsics::start_coroutine_cancellable($recvExpr, ${args.joinToString(", ")})"
            } else {
                null
            }
        "startCoroutineUndispatched" ->
            if (args.size == 2) {
                includes.addProject("kotlinx/coroutines/intrinsics/Undispatched.hpp")
                "kotlinx::coroutines::intrinsics::start_coroutine_undispatched($recvExpr, ${args.joinToString(", ")})"
            } else {
                null
            }
        "startUndispatchedOrReturn" ->
            if (args.size == 2) {
                includes.addProject("kotlinx/coroutines/intrinsics/Undispatched.hpp")
                "kotlinx::coroutines::intrinsics::start_undispatched_or_return($recvExpr, ${args.joinToString(", ")})"
            } else {
                null
            }
        "startUndispatchedOrReturnIgnoreTimeout" ->
            if (args.size == 2) {
                includes.addProject("kotlinx/coroutines/intrinsics/Undispatched.hpp")
                "kotlinx::coroutines::intrinsics::start_undispatched_or_return_ignore_timeout($recvExpr, ${args.joinToString(", ")})"
            } else {
                null
            }
        "newCoroutineContext" ->
            if (args.size == 1) {
                val qual =
                    CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                        kotlinName = "newCoroutineContext",
                        cppBaseName = "new_coroutine_context",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "kotlinx::coroutines::new_coroutine_context"
                "$qual($recvExpr, ${args[0]})"
            } else {
                null
            }
        "cancelConsumed" ->
            if (args.size == 1) {
                val qual =
                    CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                        kotlinName = "cancelConsumed",
                        cppBaseName = "cancel_consumed",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "kotlinx::coroutines::channels::cancel_consumed"
                includes.addSystem("exception")
                includes.addSystem("optional")
                includes.addSystem("type_traits")
                includes.addSystem("utility")
                includes.needsResultHelpersDef = true
                "$qual($recvExpr, kxs_to_exception_ptr(${args[0]}))"
            } else {
                null
            }
        "ensureActive" ->
            if (args.isEmpty()) {
                val qual =
                    CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                        kotlinName = "ensureActive",
                        cppBaseName = "ensure_active",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "kotlinx::coroutines::ensure_active"
                includes.addProject("kotlinx/coroutines/CoroutineScope.hpp")
                "$qual($recvExpr)"
            } else {
                null
            }
        "safeDispatch" ->
            if (args.size == 2) {
                val qual =
                    CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                        kotlinName = "safeDispatch",
                        cppBaseName = "safe_dispatch",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "kotlinx::coroutines::internal::safe_dispatch"
                "$qual($recvExpr, ${args[0]}, ${args[1]})"
            } else {
                null
            }
        "safeIsDispatchNeeded" ->
            if (args.size == 1) {
                val qual =
                    CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                        kotlinName = "safeIsDispatchNeeded",
                        cppBaseName = "safe_is_dispatch_needed",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "kotlinx::coroutines::internal::safe_is_dispatch_needed"
                "$qual($recvExpr, ${args[0]})"
            } else {
                null
            }
        "checkParallelism" ->
            if (args.isEmpty()) {
                val qual =
                    CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                        kotlinName = "checkParallelism",
                        cppBaseName = "check_parallelism",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "kotlinx::coroutines::internal::check_parallelism"
                "$qual($recvExpr)"
            } else {
                null
            }
        "namedOrThis" ->
            if (args.size == 1) {
                val qual =
                    CppTypeTranslator.includeAndQualifyKnownValueSymbol(
                        kotlinName = "namedOrThis",
                        cppBaseName = "named_or_this",
                        currentNs = bundle.symbols.namespaceSegments,
                        includes = includes,
                    ) ?: "kotlinx::coroutines::internal::named_or_this"
                "$qual($recvExpr, ${args[0]})"
            } else {
                null
            }
        "executeAfter" ->
            when (args.size) {
                1 -> "$recvExpr.execute_after(0LL, ${args[0]})"
                2 -> "$recvExpr.execute_after(${args[0]}, ${args[1]})"
                else -> null
            }
        else -> null
    }
}

internal fun CppExprAndStmtEmitter.doesClassMatchExtensionReceiver(
    cls: KtClassSymbol,
    recvTypeFrameIdx: Int,
    visited: MutableSet<Int> = HashSet(),
): Boolean {
    val recvSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[recvTypeFrameIdx]) ?: return false
    if (recvSimple == "Any") return true
    fun matchesHierarchy(cur: KtClassSymbol): Boolean {
        if (!visited.add(cur.frameIndex)) return false
        if (cur.kotlinName == recvSimple) return true
        for (superIdx in cur.superTypeFrameIndices) {
            val superSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, stream.frames[superIdx]) ?: continue
            if (superSimple == recvSimple) return true
            val sameFileSuper = bundle.symbols.classesByName[superSimple]
            if (sameFileSuper != null && matchesHierarchy(sameFileSuper)) return true
        }
        return false
    }
    return matchesHierarchy(cls)
}

internal fun CppExprAndStmtEmitter.resolveEnclosingCallableProperty(calleeKt: String): String? {
    var cur: KtClassSymbol? = enclosingClass ?: return null
    if (classDeclaresMethod(enclosingClass, calleeKt)) return null
    var prefix = ""
    while (cur != null) {
        val ctorProp = cur.primaryConstructorParams.firstOrNull { (it.isVal || it.isVar) && it.name == calleeKt }
        if (ctorProp != null) {
            val vpFrame = stream.frames[ctorProp.frameIndex]
            val fieldName =
                if (PacketFlags.hasFlag(vpFrame.flags, PacketFlags.MOD_PRIVATE)) {
                    CppNamingPolicy.toPrivateFieldName(calleeKt)
                } else {
                    ctorProp.cppName
                }
            return "$prefix$fieldName"
        }
        for (mPropIdx in cur.memberPropertyFrameIndices) {
            val mProp = bundle.symbols.propertiesByFrame[mPropIdx] ?: continue
            if (mProp.kotlinName == calleeKt) {
                val suffix = if (isGetterStyleClassProperty(cur, mProp)) "()" else ""
                return "$prefix${mProp.cppName}$suffix"
            }
        }
        if (!cur.isInner) break
        cur = cur.enclosingClassFrameIndex?.let { bundle.symbols.classesByFrame[it] }
        prefix += "outer_->"
    }
    return null
}

internal fun CppExprAndStmtEmitter.extractAnyOuterTemplateArgs(rawCppType: String?): List<String>? {
    var cur =
        (rawCppType ?: return null)
            .trim()
            .removePrefix("const ")
            .removeSuffix("&")
            .trim()
    while (true) {
        val unwrapped =
            when {
                cur.startsWith("std::optional<") && cur.endsWith(">") ->
                    cur.removePrefix("std::optional<").dropLast(1).trim()
                cur.startsWith("std::shared_ptr<") && cur.endsWith(">") ->
                    cur.removePrefix("std::shared_ptr<").dropLast(1).trim()
                else -> break
            }
        cur = unwrapped
    }
    val ltIdx = cur.indexOf('<')
    if (ltIdx < 0 || !cur.endsWith(">")) return null
    val inner = cur.substring(ltIdx + 1, cur.length - 1).trim()
    if (inner.isEmpty()) return null
    return splitTopLevelCommaList(inner)
}

internal fun CppExprAndStmtEmitter.extractUserTypeArgTypeFrames(typeFrame: PacketFrame): List<PacketFrame> {
    var cur: PacketFrame? = typeFrame
    while (cur != null && cur.nodeTypeId != KtNodeTypes.USER_TYPE_ID) {
        cur =
            FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.USER_TYPE_ID)
                ?: FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.NULLABLE_TYPE_ID)
                ?: FrameAstHelpers.firstChildFrame(stream, cur, KtNodeTypes.TYPE_REFERENCE_ID)
    }
    val argList =
        cur?.let { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_ARGUMENT_LIST_ID) }
            ?: return emptyList()
    return FrameAstHelpers
        .childFramesOfType(stream, argList, KtNodeTypes.TYPE_PROJECTION_ID)
        .mapNotNull { FrameAstHelpers.firstChildFrame(stream, it, KtNodeTypes.TYPE_REFERENCE_ID) }
}

internal fun CppExprAndStmtEmitter.resolveCallSiteReceiverTypeFrame(receiverFrame: PacketFrame?): PacketFrame? {
    if (receiverFrame == null) {
        return if (receiverSubstitution != null) {
            enclosingFunction?.receiverTypeFrameIndex?.let { stream.frames[it] }
        } else {
            null
        }
    }
    val u = unwrapTransparentFrame(receiverFrame)
    return when (u.nodeTypeId) {
        KtNodeTypes.THIS_EXPRESSION_ID ->
            enclosingFunction?.receiverTypeFrameIndex?.let { stream.frames[it] }
        KtNodeTypes.REFERENCE_EXPRESSION_ID -> {
            val refKt = FrameAstHelpers.directIdentPacket(stream, u)?.unescapedName ?: return null
            val fnParamTf = enclosingFunction?.valueParams?.firstOrNull { it.name == refKt }?.typeFrameIndex
            val clsParamTf = enclosingClass?.primaryConstructorParams?.firstOrNull { it.name == refKt }?.typeFrameIndex
            (fnParamTf ?: clsParamTf)?.let { stream.frames[it] }
        }
        else -> null
    }
}

internal fun CppExprAndStmtEmitter.fnUsesTypeParamInSuspendOrTemplate(
    fn: KtFunctionSymbol,
    tpName: String,
): Boolean {
    for (vp in fn.valueParams) {
        val tfIdx = vp.typeFrameIndex ?: continue
        val tf = stream.frames[tfIdx]
        if (PacketFlags.hasFlag(stream.frames[vp.frameIndex].flags, PacketFlags.MOD_SUSPEND) ||
            PacketFlags.hasFlag(tf.flags, PacketFlags.MOD_SUSPEND)
        ) {
            return true
        }
        val fnNode = extractFunctionTypeNode(tf)
        if (fnNode != null && PacketFlags.hasFlag(fnNode.flags, PacketFlags.MOD_SUSPEND)) {
            return true
        }
        if (extractUserTypeArgTypeFrames(tf).any {
                FrameAstHelpers.extractPrimaryTypeSimpleName(stream, it) == tpName
            }
        ) {
            return true
        }
    }
    return false
}

internal fun CppExprAndStmtEmitter.inferMissingFunctionTypeArgs(
    fn: KtFunctionSymbol,
    explicitTypeArgs: List<String>,
    targetCppForInference: String?,
    receiverFrame: PacketFrame?,
): List<String> {
    if (fn.typeParams.isEmpty() || explicitTypeArgs.size >= fn.typeParams.size) return explicitTypeArgs
    val inferred = arrayOfNulls<String>(fn.typeParams.size)
    for (i in explicitTypeArgs.indices) {
        if (i < inferred.size) inferred[i] = explicitTypeArgs[i]
    }
    val fnRecvIdx = fn.receiverTypeFrameIndex
    if (fnRecvIdx != null) {
        val fnRecvFrame = stream.frames[fnRecvIdx]
        val callSiteRecvFrame = resolveCallSiteReceiverTypeFrame(receiverFrame)
        if (callSiteRecvFrame != null) {
            val fnRecvArgs = extractUserTypeArgTypeFrames(fnRecvFrame)
            val siteRecvArgs = extractUserTypeArgTypeFrames(callSiteRecvFrame)
            if (fnRecvArgs.size == siteRecvArgs.size) {
                for (i in fnRecvArgs.indices) {
                    val fnArgSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, fnRecvArgs[i])
                    val tpIdx = fn.typeParams.indexOfFirst { it.name == fnArgSimple }
                    if (tpIdx >= 0 && inferred[tpIdx] == null) {
                        inferred[tpIdx] = translateType(siteRecvArgs[i], CppTypePosition.TYPE_ARGUMENT)
                    }
                }
            }
        }
    }
    val normTarget =
        targetCppForInference
            ?.trim()
            ?.removePrefix("[[noreturn]] ")
            ?.takeIf { it.isNotEmpty() && it != "auto" && it != "std::any" && it != "void*" }
    val retIdx = fn.returnTypeFrameIndex
    if (normTarget != null && retIdx != null) {
        val retFrame = stream.frames[retIdx]
        val retSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retFrame)
        if (retSimple != null) {
            val directIdx = fn.typeParams.indexOfFirst { it.name == retSimple }
            if (directIdx >= 0 && inferred[directIdx] == null) {
                val unwrapped = normTarget.removePrefix("const ").removeSuffix("&").trim()
                inferred[directIdx] =
                    if (unwrapped == "void" && fnUsesTypeParamInSuspendOrTemplate(fn, retSimple)) {
                        includes.addProject("kotlinx/coroutines/Unit.hpp")
                        "kotlin::Unit"
                    } else {
                        unwrapped
                    }
            } else {
                val cppBase =
                    lookupAccessibleClassByName(retSimple)?.cppName
                        ?: CppTypeTranslator.includeAndQualifyKnownType(
                            simpleName = retSimple,
                            cppBaseName = CppNamingPolicy.toTypeName(retSimple),
                            currentNs = bundle.symbols.namespaceSegments,
                            includes = includes,
                        )
                        ?: CppNamingPolicy.toTypeName(retSimple)
                val outerArgs = extractOuterTemplateArgsForBase(normTarget, cppBase)
                val retTypeArgFrames = extractUserTypeArgTypeFrames(retFrame)
                if (outerArgs != null && outerArgs.size == retTypeArgFrames.size) {
                    for (i in retTypeArgFrames.indices) {
                        val argSimple = FrameAstHelpers.extractPrimaryTypeSimpleName(stream, retTypeArgFrames[i])
                        val tpIdx = fn.typeParams.indexOfFirst { it.name == argSimple }
                        if (tpIdx >= 0 && inferred[tpIdx] == null) {
                            inferred[tpIdx] = outerArgs[i]
                        }
                    }
                }
            }
        }
    }
    return if (inferred.all { it != null }) inferred.filterNotNull() else explicitTypeArgs
}

internal data class RawCallArg(
    val name: String?,
    val exprFrame: PacketFrame,
    val isTrailingLambda: Boolean = false,
)

internal fun CppExprAndStmtEmitter.extractRawCallArgs(callFrame: PacketFrame): List<RawCallArg> {
    val out = ArrayList<RawCallArg>()
    val argList = FrameAstHelpers.firstChildFrame(stream, callFrame, KtNodeTypes.VALUE_ARGUMENT_LIST_ID)
    if (argList != null) {
        for (va in FrameAstHelpers.childFramesOfType(stream, argList, KtNodeTypes.VALUE_ARGUMENT_ID)) {
            val argNameFrame = FrameAstHelpers.firstChildFrame(stream, va, KtNodeTypes.VALUE_ARGUMENT_NAME_ID)
            val argName =
                argNameFrame?.let { nf ->
                    FrameAstHelpers
                        .firstChildFrame(stream, nf, KtNodeTypes.REFERENCE_EXPRESSION_ID)
                        ?.let { ref -> FrameAstHelpers.directIdentPacket(stream, ref)?.unescapedName }
                        ?: FrameAstHelpers.directIdentPacket(stream, nf)?.unescapedName
                }
            val expr =
                FrameAstHelpers
                    .childFrames(stream, va)
                    .firstOrNull { it.nodeTypeId != KtNodeTypes.VALUE_ARGUMENT_NAME_ID }
            if (expr != null) {
                out.add(RawCallArg(name = argName, exprFrame = expr))
            }
        }
    }
    for (tl in FrameAstHelpers.childFramesOfType(stream, callFrame, KtNodeTypes.LAMBDA_ARGUMENT_ID)) {
        val lambda = FrameAstHelpers.unwrapLambdaExpression(stream, tl)
        if (lambda != null) {
            out.add(RawCallArg(name = null, exprFrame = lambda, isTrailingLambda = true))
        }
    }
    return out
}

internal fun CppExprAndStmtEmitter.mapCallArgsToParamIndices(
    params: List<KtValueParamInfo>,
    rawArgs: List<RawCallArg>,
): Map<Int, List<RawCallArg>>? {
    if (rawArgs.isEmpty()) {
        val allOptional = params.all { it.defaultExprFrameIndex != null || it.isVararg }
        return if (allOptional) emptyMap() else null
    }
    val bound = LinkedHashMap<Int, MutableList<RawCallArg>>()
    var nextPositional = 0
    var seenNamed = false
    for (arg in rawArgs) {
        if (arg.isTrailingLambda) {
            if (params.isEmpty()) return null
            val targetIdx = params.lastIndex
            if (targetIdx in bound) return null
            bound.getOrPut(targetIdx) { ArrayList() }.add(arg)
        } else if (arg.name != null) {
            seenNamed = true
            val idx = params.indexOfFirst { it.name == arg.name }
            if (idx < 0) return null
            if (idx in bound && !params[idx].isVararg) return null
            bound.getOrPut(idx) { ArrayList() }.add(arg)
        } else {
            if (seenNamed || nextPositional >= params.size) return null
            bound.getOrPut(nextPositional) { ArrayList() }.add(arg)
            if (!params[nextPositional].isVararg) {
                nextPositional++
            }
        }
    }
    for (i in params.indices) {
        if (i !in bound && params[i].defaultExprFrameIndex == null && !params[i].isVararg) {
            return null
        }
    }
    return bound
}

internal fun CppExprAndStmtEmitter.doesFnMatchRawArgs(
    params: List<KtValueParamInfo>,
    rawArgs: List<RawCallArg>,
): Boolean = mapCallArgsToParamIndices(params, rawArgs) != null

internal fun CppExprAndStmtEmitter.isOptionalOrAnyParamType(paramTypeFrameIdx: Int?): String? {
    val tfIdx = paramTypeFrameIdx ?: return null
    val paramCppT = translateType(stream.frames[tfIdx], CppTypePosition.PARAMETER)
    return when {
        paramCppT.startsWith("std::optional<") || paramCppT.startsWith("const std::optional<") -> "optional"
        paramCppT == "std::any" || paramCppT == "const std::any&" -> "any"
        else -> null
    }
}

internal fun CppExprAndStmtEmitter.emitCallArgForParam(
    vp: KtValueParamInfo,
    rawArg: RawCallArg,
): String {
    val u = unwrapTransparentFrame(rawArg.exprFrame)
    if (u.nodeTypeId == KtNodeTypes.LAMBDA_EXPRESSION_ID) {
        val tf = vp.typeFrameIndex?.let { stream.frames[it] }
        val recvRef = tf?.let { extractFunctionTypeReceiverRef(it) }
        val expectedCount = tf?.let { extractFunctionTypeValueParamCount(it) }
        return emitLambdaClosureExpr(u, receiverTypeRef = recvRef, expectedValueParamCount = expectedCount)
    }
    if (u.nodeTypeId == KtNodeTypes.NULL_ID) {
        when (isOptionalOrAnyParamType(vp.typeFrameIndex)) {
            "optional" -> {
                includes.addSystem("optional")
                return "std::nullopt"
            }
            "any" -> {
                includes.addSystem("any")
                return "{}"
            }
        }
    }
    return withExpectedTargetType(null) { emitExpression(rawArg.exprFrame) }
}

internal fun CppExprAndStmtEmitter.emitDefaultValueForParam(vp: KtValueParamInfo): String {
    val defIdx = vp.defaultExprFrameIndex ?: return "{}"
    val defFrame = unwrapTransparentFrame(stream.frames[defIdx])
    if (defFrame.nodeTypeId == KtNodeTypes.NULL_ID) {
        when (isOptionalOrAnyParamType(vp.typeFrameIndex)) {
            "optional" -> {
                includes.addSystem("optional")
                return "std::nullopt"
            }
            "any" -> {
                includes.addSystem("any")
                return "{}"
            }
        }
    }
    return withExpectedTargetType(null) { emitExpression(defFrame) }
}

internal fun CppExprAndStmtEmitter.emitAlignedArgsForParams(
    params: List<KtValueParamInfo>,
    bound: Map<Int, List<RawCallArg>>,
): List<String> {
    if (bound.isEmpty()) return emptyList()
    val lastProvidedIdx = bound.keys.maxOrNull() ?: return emptyList()
    val out = ArrayList<String>(lastProvidedIdx + 1)
    for (i in 0..lastProvidedIdx) {
        val vp = params[i]
        val supplied = bound[i]
        if (supplied == null) {
            out.add(emitDefaultValueForParam(vp))
        } else if (vp.isVararg) {
            val rendered = supplied.map { emitCallArgForParam(vp, it) }
            if (i == lastProvidedIdx) {
                out.addAll(rendered)
            } else {
                out.add("{${rendered.joinToString(", ")}}")
            }
        } else {
            out.add(emitCallArgForParam(vp, supplied[0]))
        }
    }
    return out
}

internal fun CppExprAndStmtEmitter.extractCallArgFrames(callFrame: PacketFrame): List<PacketFrame> =
    extractRawCallArgs(callFrame).map { it.exprFrame }

internal fun CppExprAndStmtEmitter.extractCallArgExpressions(callFrame: PacketFrame): List<String> =
    extractCallArgFrames(callFrame).map { withExpectedTargetType(null) { emitExpression(it) } }
