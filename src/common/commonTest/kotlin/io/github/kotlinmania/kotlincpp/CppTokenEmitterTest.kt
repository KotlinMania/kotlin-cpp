package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.kotlincpp.kcpp.CppIncludeCollector
import io.github.kotlinmania.kotlincpp.kcpp.CppToken
import io.github.kotlinmania.kotlincpp.kcpp.CppTokenStream
import io.github.kotlinmania.kotlincpp.kcpp.CppType
import io.github.kotlinmania.kotlincpp.kcpp.buildCppTokens
import io.github.kotlinmania.kotlincpp.kcpp.emitIncludeGuard
import io.github.kotlinmania.kotlincpp.kcpp.emitNamespaceBlock
import io.github.kotlinmania.kotlincpp.kcpp.indented
import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.TokenTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CppTokenEmitterTest {
    @Test
    fun tokenizeCppCodeRoundTripsAllLexicalConstructsLosslessly() {
        val source =
            """
            #ifndef FOO_H
            #define FOO_H
            #include <vector>
            /** Doxygen T comment */
            /* block T comment */
            // line T comment
            const char* raw_s = R"kxs(raw T content)kxs";
            const char* esc_s = "escaped \"T\" string\n";
            char ch_lit = 'a';
            double d = 1'000.5e-2;
            float f = 3.14f;
            template <typename T>
            auto fn(const std::vector<T>& xs) -> decltype(xs.size()) {
                auto x = 1;
                x <<= 1;
                auto cmp = (x <=> 0);
                return ptr->T;
            }
            #endif // FOO_H
            """.trimIndent()

        val stream = CppTokenStream.tokenizeCppCode(source)
        assertFalse(stream.isEmpty())
        val firstDirective = assertIs<CppToken.Preprocessor>(stream.tokens.first())
        assertEquals("ifndef", firstDirective.directive)
        assertTrue(stream.tokens.any { it is CppToken.Comment })
        assertEquals(source, stream.render())
    }

    @Test
    fun tokenStreamIdentKeywordAndPunctQueriesIgnoreCommentsStringsAndPreprocessor() {
        val concealed =
            """
            // foo
            /* foo */
            #define foo 1
            const char* s = "foo";
            const char* r = R"(foo)";
            """.trimIndent()

        assertFalse(CppTokenStream.tokenizeCppCode(concealed).containsIdent("foo"))
        assertTrue(CppTokenStream.tokenizeCppCode("$concealed\nauto x = foo;").containsIdent("foo"))

        assertTrue(CppTokenStream.tokenizeCppCode("  /* lead */ throw err").startsWithKeyword("throw"))
        assertFalse(CppTokenStream.tokenizeCppCode("throw_helper()").startsWithKeyword("throw"))
        assertFalse(CppTokenStream.tokenizeCppCode("return throw_val").startsWithKeyword("throw"))

        assertTrue(CppTokenStream.tokenizeCppCode("void f() { return; } // done").endsWithPunct("}"))
        assertFalse(CppTokenStream.tokenizeCppCode("void f();").endsWithPunct("}"))
        assertTrue(CppTokenStream.tokenizeCppCode("int x = 1; /* end */").endsWithPunct(";"))
    }

    @Test
    fun renameIdentsSkipsMemberAccessRhsCommentsAndLiterals() {
        val src =
            "/* T */ auto p = make(\"T\", R\"(T)\"); p->T = 1; obj.T = 2; Ns::T x = 3; auto fn(T a) -> T { return a; }"
        val renamed = CppTokenStream.tokenizeCppCode(src).renameIdents(mapOf("T" to "T_fn")).render()
        val expected =
            "/* T */ auto p = make(\"T\", R\"(T)\"); p->T = 1; obj.T = 2; Ns::T x = 3; " +
                "auto fn(T_fn a) -> T_fn { return a; }"
        assertEquals(expected, renamed)

        val multiTokenStream = CppTokenStream.tokenizeCppCode("std::optional<T> val = {};")
        val multiTokenRenamed = multiTokenStream.renameIdents(mapOf("T" to "std::vector<int>")).render()
        assertEquals("std::optional<std::vector<int>> val = {};", multiTokenRenamed)
    }

    @Test
    fun toProcMacroTokenStreamPreservesBalancedDelimiterGroups() {
        val pm = CppTokenStream.tokenizeCppCode("fn_call((a + 1), arr[0], { return; })").toProcMacroTokenStream()
        val topTrees = pm.toList()
        assertIs<TokenTree.Ident>(topTrees[0])
        val callGroup = assertIs<TokenTree.Group>(topTrees[1])
        assertEquals(Delimiter.Parenthesis, callGroup.value.delimiter())

        val argTrees = callGroup.value.stream().toList()
        val nestedGroups = argTrees.filterIsInstance<TokenTree.Group>()
        assertEquals(
            listOf(Delimiter.Parenthesis, Delimiter.Bracket, Delimiter.Brace),
            nestedGroups.map { it.value.delimiter() },
        )
    }

    @Test
    fun tokenEmitterBuildsIndentedAndFlushedPreprocessorConstructs() {
        val guarded =
            buildCppTokens(baseIndent = "    ") {
                emitIncludeGuard("KXS_TEST", trailingComment = true) {
                    emitNamespaceBlock("a::b", blankLineInside = false) {
                        indented {
                            emitTemplateDeclaration(listOf("typename T"))
                            emitSuspendAnnotations(implicitContinuation = true, boxedResult = true)
                            newline()
                            ident("T")
                            space()
                            ident("run")
                            punct("(")
                            punct(");", jointToNext = false)
                            newline()
                        }
                    }
                }
            }
        val expectedGuarded =
            """
            #ifndef KXS_TEST
            #define KXS_TEST
                namespace a::b {
                    template <typename T>
                    [[clang::annotate("suspend"), clang::annotate("kxs_implicit_continuation"), clang::annotate("kxs_boxed_result")]]
                    T run();
                } // namespace a::b
            #endif // KXS_TEST

            """.trimIndent()
        assertEquals(expectedGuarded, guarded)

        val sealedAndOverrides =
            buildCppTokens(baseIndent = "    ") {
                emitSealedSpecialMembers("Node", includeDefaultCtor = true, includeDefaultEq = true)
                newline()
                emitForwardingOverride(
                    returnType = "bool",
                    methodName = "is_active",
                    paramsDecl = "",
                    isConst = true,
                    isVoidReturn = false,
                    receiverExpr = "delegate_",
                    memberAccessOp = "->",
                    callArgsExpr = "",
                )
                newline()
                emitDualAbiSuspendForwardingOverride(
                    authoringReturnType = "void",
                    boxedResult = false,
                    methodName = "emit",
                    authoringParamsDecl = "std::int32_t value",
                    loweredLeadingParamsDecl = "std::int32_t value",
                    receiverExpr = "collector_",
                    memberAccessOp = ".",
                    loweredLeadingArgs = listOf("value"),
                )
            }
        val expectedSealed =
            listOf(
                "    protected:",
                "        Node() = default;",
                "        Node(const Node&) = default;",
                "        Node(Node&&) = default;",
                "        Node& operator=(const Node&) = default;",
                "        Node& operator=(Node&&) = default;",
                "    public:",
                "        bool operator==(const Node&) const = default;",
                "",
                "    bool is_active() const override { return delegate_->is_active(); }",
                "",
                "    [[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]",
                "    void emit(std::int32_t value);",
                "",
                "    [[clang::annotate(\"suspend\")]]",
                "    void* emit(std::int32_t value, " +
                    "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion) override " +
                    "{ return collector_.emit(value, std::move(_kxs_completion)); }",
                "",
            ).joinToString("\n")
        assertEquals(expectedSealed, sealedAndOverrides)

        val iife =
            buildCppTokens {
                emitIife(
                    capture = "&",
                    paramsDecl = "auto&& v",
                    explicitReturnType = "int",
                    invokeArgs = "x",
                    bodyCode = "return v;",
                )
            }
        assertEquals("([&](auto&& v) -> int { return v; }(x))", iife)
    }

    @Test
    fun cppTypeParsesTransformsAndCoercesDefaultsAccurately() {
        val noReturnVoid = CppType.parse("[[noreturn]] void")
        assertTrue(noReturnVoid.isNoReturn)
        assertEquals("[[noreturn]] void", noReturnVoid.render())
        assertTrue(noReturnVoid.stripNoReturn().isVoid)

        val constSharedRef = CppType.parse("const std::shared_ptr<Job>&")
        val strippedShared = constSharedRef.stripCvRef()
        assertTrue(strippedShared.isSharedPtr)
        assertTrue(strippedShared.isPointerLike)
        assertEquals("std::shared_ptr<Job>", strippedShared.render())

        val constOptional = CppType.parse("const std::optional<std::int32_t>")
        val unwrappedConstOpt = constOptional.stripCvRef()
        assertTrue(unwrappedConstOpt.isOptional)
        assertEquals("std::int32_t", unwrappedConstOpt.unwrapOptional().render())

        val includes = CppIncludeCollector("Test.hpp")
        assertEquals("std::nullopt", unwrappedConstOpt.coerceNullDefaultExpr("nullptr", includes))
        assertEquals(
            "{}",
            CppType.parse("std::any").coerceNullDefaultExpr("nullptr", includes, coerceStdAnyToBraces = true),
        )
        assertEquals("nullptr", CppType.parse("std::any").coerceNullDefaultExpr("nullptr", includes))

        val voidPtr = CppType.parse("void*")
        assertTrue(voidPtr.isVoidPtr)
        assertTrue(voidPtr.isPointerLike)

        val starType = CppType.parse("std::shared_ptr<Result<std::any, void*>>")
        val starReplaced = starType.substituteStarProjections(listOf("T_0", "T_1"))
        assertEquals("std::shared_ptr<Result<T_0, T_1>>", starReplaced.render())

        val suspendType = CppType.parse("kotlinx::coroutines::SuspendFunction<void, F>&", listOf("F"))
        val suspendArg = suspendType.withNonDeducedSuspendArg(setOf("F"), includes)
        assertEquals(
            "kotlinx::coroutines::SuspendFunction<void, std::type_identity_t<F>>&",
            suspendArg.render(),
        )

        val contType = CppType.parse("std::shared_ptr<kotlin::coroutines::Continuation<T>>", listOf("T"))
        val contArg = contType.withNonDeducedContinuation(setOf("T"), includes)
        assertEquals(
            "std::type_identity_t<std::shared_ptr<kotlin::coroutines::Continuation<T>>>",
            contArg.render(),
        )
    }
}
