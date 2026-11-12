# kotlin-cpp

Deterministic, purely language-level **Kotlin-to-C++23** mechanical transliterator and lossless Kotlin `PacketStream` round-trip engine (`io.github.kotlinmania.kotlincpp`).

`kotlin-cpp` parses Kotlin `.kt` source files through KotlinMania's pure-KMP `antlr4-kotlin` specification grammar (`KotlinLexer` + `KotlinParser`), attaches universal `syn-kotlin` (`io.github.kotlinmania.syn.token.*`) and `proc-macro2-kotlin` (`TokenStream` / `TokenTree`) representations to every significant packet, proves a byte-exact Kotlin → `PacketStream` → Kotlin round-trip, and lowers the structured `PacketStream` through a 6-pass **C++23** pipeline into co-located `.hpp` / `.cpp` files (plus optional `@CName` C ABI `.h` / `.def` companions).

## Architecture

1. **ANTLR4 Specification Frontend + Universal `syn-kotlin` Packet Stream (`io.github.kotlinmania.kotlincpp.packet`)**:
   - Pure-KMP `KotlinLexer` and `KotlinParser` (generated via `antlr4-kotlin`) produce an exact partition of UTF-16 / UTF-8 spans, comments, KDoc `[link]` spans, `// port-lint:` directives, string template segments, and balanced `<T>` / `{...}` / `(...)` / `[...]` delimiter pairs.
   - `UniversalSynTokenAssociation` attaches canonical `syn-kotlin` `Token` instances (`fun` → `Token![fn]`, `suspend` → `Token![async]`, `val`/`var` → `Token![let]`, `when` → `Token![match]`, `?:` → `Token![?]+Token![:]`, etc.) and builds hierarchical `proc-macro2-kotlin` `TokenStream` trees.
   - `KotlinPacketEmitter` (`renderKotlinRoundTrip`) regenerates canonical Kotlin directly from `PacketStream` to verify lossless "like-for-like" structural fidelity.

2. **6-Pass Deterministic C++23 Transliterator (`io.github.kotlinmania.kotlincpp.kcpp`)**:
   - **Pass 1 (`SameFileSymbolPass`)**: Same-file declaration/type/callable table & forward-declaration discovery.
   - **Pass 2 (`SuspendStateMachinePass`)**: `FirSuspendCallChecker` validation, dual-ABI `[[clang::annotate("suspend")]]` continuation pairs, inline-lambda expansion, left-to-right argument hoisting before suspend points, and IIFE-free control-flow hoisting.
   - **Pass 3 (`GcAndOwnershipPass`)**: Standalone RAII (`CppGcMode.NONE`) or Kotlin/Native Docking-Ring GC (`CppGcMode.KONAN_GC`) stack `ObjHolder` roots, cross-suspend `OwningExternalRCRef` spills with dead-slot `.reset()`, `CalledFromNativeGuard`, and `Kotlin_mm_UpdateHeapRef` barriers.
   - **Pass 4 (`AtomicAndThreadLocalPass`)**: `kotlinx.atomicfu` fields, lvalue-safe `compare_exchange_strong` / `Kotlin_mm_CompareAndSetVolatileHeapRef`, `.loop { ... }` lowering, and `ThreadLocal<T>`.
   - **Pass 5 (`FfiAndCTypePass`)**: `@InlineCpp` / `inlineCpp(...)`, `kotlinx.cinterop` (`CPointer`, `memScoped`, `alloc`, `usePinned` with compile-time cross-suspend pin rejection), and `@CName` `extern "C"` + companion `<Stem>_c_api.h` / `<Stem>.def` generation.
   - **Pass 6 (`ControlFlowAndTypePass` + `CppCodeEmitter`)**: Co-located C++23 `.hpp` / `.cpp` emission (`operator[](r, c)`, `std::unreachable()`, `sealed class` / `data class` / `enum class` lowering).

## Native CLI Usage

```sh
./gradlew build
build/bin/macosArm64/releaseExecutable/kotlin-cpp.kexe --help

# Transliterate Kotlin to co-located C++23 .hpp/.cpp files (default command)
build/bin/macosArm64/releaseExecutable/kotlin-cpp.kexe -o build/cpp_out path/to/File.kt

# Verify lossless Kotlin -> PacketStream (ANTLR4 + universal syn-kotlin) -> Kotlin round-trip
build/bin/macosArm64/releaseExecutable/kotlin-cpp.kexe roundtrip Input.kt Regenerated.kt

# Inspect PacketStream frames, comments, KDoc links, and suspend declarations
build/bin/macosArm64/releaseExecutable/kotlin-cpp.kexe packets path/to/File.kt

# Inspect universal syn-kotlin + proc-macro2-kotlin token associations
build/bin/macosArm64/releaseExecutable/kotlin-cpp.kexe tokens path/to/File.kt
```

## Verification

- `./gradlew test` — Runs all multiplatform unit tests, JVM/POSIX/MinGW native IO tests, C++23 Clang compile-and-execute tests, Swift Export (`KotlinCpp`) tests, Android tests, foreign guest tests, and JetBrains IntelliJ IDEA (`2025.3.6.1`) plugin tests (`:ide-plugin:test`).
- `./gradlew build` — Builds all retained Kotlin Multiplatform targets (`allWarningsAsErrors = true`), `ktlintCheck`, and `detekt`.

See [AGENTS.md](AGENTS.md) and [SWIFT.md](SWIFT.md) for contributor and architecture invariants.
