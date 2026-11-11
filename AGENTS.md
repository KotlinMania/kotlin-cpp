# kotlin-cpp Agent & Architecture Contract

This is an independent clean copy of `rust-kotlin`, initialized from commit `8ef4cdadcc5129096cc4f5ac55cf168f360edf0f`. Its initial commit preserves the exact source tree. The inherited Rust-to-Kotlin engine remains intact; Kotlin input now has a lexical Syn entry point and native `tokens` inspection, not a completed Kotlin parser or C++ emitter.

## Current direction — Kotlin input through the existing token system

- Reuse the `syn-kotlin` / `proc-macro2-kotlin` token system already used here. Investigate associating Kotlin spellings and constructs with the same token/declaration representations used by the established Rust-to-token-to-Kotlin mapping.
- Build on the actual KotlinMania parser, scope, trivia and emission work, including lalrpop-kotlin, starlark-kotlin and antlr4-kotlin where applicable. Do not replace it with a parallel C++ lexer/parser, a C++ wrapper or another unrelated helper layer.
- Preserve source declarations, package identity, comments, branch/evaluation order and structure. The goal is deterministic, source-faithful transliteration with exact output fixtures, not merely a different implementation that passes a behavioral example.
- Keep the Kotlin Multiplatform/native CLI structure. C++23 is the output/runtime target; it does not require rewriting the translation tool in C++.
- Retain the inherited engine and tests as an inspectable baseline. Do not claim Kotlin tokenization, reverse mapping or C++ generation works until exercised with real source and checked output. Surface ambiguous or unsupported mappings rather than inventing missing source information.

The contract below describes the inherited Rust-to-Kotlin baseline. Its fidelity, single-path and verification rules still apply; Rust-specific input/output rules do not redefine this new project's Kotlin-to-C++ goal.

## Inherited Rust-to-Kotlin architecture

`rust-kotlin` (`rust-transpiler`) is KotlinMania's deterministic, purely language-level Rust-to-Kotlin mechanical translator modeled on IntelliJ's J2K (`NewJavaToKotlinConverter`), packaged as a JetBrains IntelliJ IDEA plugin.

## Core Invariants

1. **KMP Native CLI + JetBrains IntelliJ Platform Plugin Structure**:
   - Shared translator logic and AST emission live in KMP `src/common/commonMain` (with tests in `src/common/commonTest`), standalone native CLI entrypoints live in `src/native/nativeMain`, and the JetBrains IntelliJ IDEA (`2025.3.6.1`) plugin lives in `ide-plugin/`.

2. **Strict `1 Rust File → 1 Kotlin File` Language-Level Boundary (Zero Cross-File Scope/Type Resolution)**:
   - Every regular Rust `.rs` source file is parsed via our own KotlinMania libraries (`proc-macro2-kotlin` for lexical `TokenStream` / spans, `syn-kotlin` for full Rust AST `File` / `Item` / `Stmt` / `Expr` / `Type` / `Pat`, and `starlark-kotlin`'s `FormatParser` for format string templates) and deterministically emitted as one `.kt` file.
   - Focus strictly on **Rust-to-Kotlin language differences** within a single file using consistent deterministic naming and type mappings. **Never** build cross-file type/trait symbol tables, cross-file `use`/`mod` scope resolvers, cross-file `impl` mergers, or automatic `lib.rs`/`mod.rs` multi-file splitters; leave cross-file type checking to `kotlinc` and IntelliJ.
   - **Explicit C/FFI Companion Exception**: Extracting inline C / FFI declarations into companion `.def`, `.c`/`.cpp`, and `nativeMain` interop bridge files is explicitly permitted and excluded from the single-output-file rule because Kotlin cannot inline C source.
   - `lib.rs` and `mod.rs` are crate/module roots and must **never** be emitted as `Lib.kt` or `Mod.kt` (nor carry `// port-lint: source .../lib.rs` or `.../mod.rs`).

3. **Single Code Path Only — Zero Fallbacks, Zero Mode Gates**:
   - **Do NOT create fallbacks or gates that allow two different ways of working.**
   - **Never** write code that tries one direction, fails (or catches an exception), and falls back to another method (such as emitting raw unparsed Rust text or switching between span-rewriting and AST emission).
   - **Do NOT** add CLI flags, profile switches, or environment variables that toggle between different translation engines or code paths.
   - If you ever need to test a different approach, do it on a separate git branch — **never** on `main` branch code.
   - Actively remove any legacy paths that attempt one method, fail, and try another.

4. **Build & Test**:
   - Always verify with `./gradlew build` and `./gradlew test`.
   - Validate generated Kotlin files across `kotlinmania/*-kotlin` crate ports using `/Volumes/games/ASTDistance/build/ast_distance --deep`.

## Platform & Target Emission Invariants

1. **`sealed class` (Never `sealed interface`) for Exported Sum Types**:
   - Swift Export's `_SealedType` code generator emits Swift protocols for `sealed interface` whose nested `data object` / `data class` wrappers fail Swift type checking in `_SealedType.value` without explicit casts, whereas `sealed class` exports cleanly as an `open class` hierarchy. Always emit `sealed class` for Rust `enum`s and synthesized bound sum types.

2. **Collection Interfaces (`List`, `MutableList`, `Map`, `MutableMap`) Instead of Concrete `ArrayList` / `HashMap` on Public Signatures**:
   - Exposing `ArrayList<T>` in public/exported Kotlin signatures forces Swift Export to generate a `KotlinStdlib` wrapper for `kotlin.collections.ArrayList` whose `set(index:element:)` calls `_set(index:element:)` without discarding its return value, failing `xcodebuild` with `error: result of call to '_set(index:element:)' is unused`. Always emit collection interface types on public return and parameter signatures.

3. **Unpaired UTF-16 Surrogates in Constant `String` Literals (Kotlin/JS Incremental Klib Corruption)**:
   - Kotlin/JS's incremental klib serializer stores constant `String` literals in Protobuf `string` fields (UTF-8), which silently replaces unpaired UTF-16 surrogates (`\uD800`..`\uDFFF`) with `'?'` (`\u003F`) on incremental rebuilds. Moreover, Kotlin's `IrInterpreter` constant-folds `'c'.toString()` back into a compile-time `IrConst<String>`. Never emit constant `String` literals or `'c'.toString()` for lone surrogates; always construct unpaired surrogate strings via `charArrayOf(...).concatToString()`.

