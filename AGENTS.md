# kotlin-cpp Agent & Architecture Contract

`kotlin-cpp` (`io.github.kotlinmania.kotlincpp`) is KotlinMania's deterministic, purely language-level Kotlin-to-C++23 mechanical transliterator and lossless Kotlin `PacketStream` engine, packaged as a Kotlin Multiplatform library, standalone native CLI (`kotlin-cpp`), Swift Export library (`KotlinCpp`), and JetBrains IntelliJ IDEA (`2025.3.6.1`) plugin (`ide-plugin/`).

## Core Invariants

1. **KMP Native CLI + JetBrains IntelliJ Platform Plugin Structure**:
   - Shared ANTLR4 Kotlin grammar frontend, universal `syn-kotlin` / `proc-macro2-kotlin` packet stream, lossless Kotlin round-trip emitter, and 6-pass C++23 lowering/emitter live in `src/common/commonMain/kotlin/io/github/kotlinmania/kotlincpp/` (with tests in `src/common/commonTest`), standalone native CLI entrypoints live in `src/native/nativeMain`, and the JetBrains IntelliJ IDEA (`2025.3.6.1`) plugin lives in `ide-plugin/`.

2. **ANTLR4 Kotlin Specification Grammar + Universal `syn-kotlin` Token Stream (`PacketStream`)**:
   - Every Kotlin `.kt` file is lexed and parsed strictly via KotlinMania's pure-KMP `antlr4-kotlin` runtime (`KotlinLexer` + `KotlinParser` generated from the official Kotlin specification grammars) and mapped 1:1 onto universal `syn-kotlin` (`io.github.kotlinmania.syn.token.*`) and `proc-macro2-kotlin` (`Ident`, `Literal`, `Punct`, `Group`, `Delimiter`, `Spacing`, `Span`, `TokenStream`, `TokenTree`) tokens inside `PacketStream`.
   - `renderKotlinRoundTrip` (`kotlin-cpp roundtrip`) proves the lossless Kotlin → `PacketStream` (ANTLR4 + universal `syn-kotlin` tokens) → Kotlin round-trip before C++23 lowering.
   - Preserve source declarations, package identity, comments, KDoc links, `// port-lint:` provenance directives, branch/evaluation order, and structure. Reject `// port-lint: ignore` directives loudly.

3. **Strict `1 Kotlin File → Co-Located C++23 .hpp/.cpp` Language-Level Boundary (Zero Cross-File Scope/Type Resolution)**:
   - Target C++ standard is **C++23** (`cppStandard = 23`), supporting multidimensional `operator[]`, `std::unreachable()`, `constexpr`, defaulted comparisons, `[[clang::annotate("suspend")]]` dual-ABI continuation pairs, `atomicfu` lvalue-safe CAS lowering, and optional Kotlin/Native Docking-Ring GC (`CppGcMode.KONAN_GC`) stack roots / external RC spills / write barriers.
   - Never build cross-file symbol tables or cross-file scope resolvers; leave cross-file type checking to C++23 / Clang and `KotlinxSuspendPlugin` / `KotlinxCoroutinePass`.
   - **Explicit C/FFI Companion Exception**: Emitting `<Stem>_c_api.h` and `<Stem>.def` companion C ABI artifacts for `@CName` exports when requested is explicitly permitted.

4. **Single Code Path Only — Zero Fallbacks, Zero Mode Gates**:
   - **Do NOT create fallbacks or gates that allow two different ways of working.**
   - **Never** write code that tries one direction, fails (or catches an exception), and falls back to another method.
   - **Do NOT** add CLI flags, profile switches, or environment variables that toggle between different parsing or translation engines.

5. **Build & Test**:
   - Always verify with `./gradlew test` and `./gradlew build` (`allWarningsAsErrors = true`).

## Platform & Target Emission Invariants

1. **`sealed class` (Never `sealed interface`) for Exported Sum Types**:
   - Always use `sealed class` for exported KMP sum types (`SyntaxPacket`, `CppType`, `CppExpr`, `CppStmt`, `CppDecl`) so Swift Export generates clean `open class` hierarchies.

2. **Collection Interfaces (`List`, `MutableList`, `Map`, `MutableMap`) Instead of Concrete `ArrayList` / `HashMap` on Public Signatures**:
   - Always expose collection interface types on public return and parameter signatures so Swift Export does not emit unused-result `_set` wrappers on `ArrayList`.

3. **Unpaired UTF-16 Surrogates in Constant `String` Literals (Kotlin/JS Incremental Klib Corruption)**:
   - Never emit constant `String` literals or `'c'.toString()` for lone surrogates (`\uD800`..`\uDFFF`); always construct unpaired surrogate strings via `charArrayOf(...).concatToString()`.
