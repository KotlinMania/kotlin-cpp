# kotlin-cpp

[![GitHub](https://img.shields.io/badge/GitHub-KotlinMania%2Fkotlin--cpp-blue.svg)](https://github.com/KotlinMania/kotlin-cpp)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kotlinmania/kotlin-cpp)](https://central.sonatype.com/artifact/io.github.kotlinmania/kotlin-cpp)
[![Build status](https://img.shields.io/github/actions/workflow/status/KotlinMania/kotlin-cpp/ci.yml?branch=main)](https://github.com/KotlinMania/kotlin-cpp/actions)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF.svg)](https://kotlinlang.org/docs/multiplatform.html)
[![C++23](https://img.shields.io/badge/C%2B%2B-23-00599C.svg?logo=cplusplus&logoColor=white)](https://en.cppreference.com/w/cpp/23)
[![Swift Export](https://img.shields.io/badge/Swift_Export-KotlinCpp-F05138.svg?logo=swift&logoColor=white)](#swift-usage-swift-export)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20IDEA-2025.3-000000.svg?logo=intellijidea&logoColor=white)](#intellij-idea-plugin)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE-APACHE)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE-MIT)

A deterministic, purely language-level **Kotlin-to-C++23** mechanical transliterator and lossless Kotlin `PacketStream` round-trip engine (`io.github.kotlinmania.kotlincpp`). One `.kt` file in, co-located `.hpp` / `.cpp` files out.

There is no language model in the loop: the same Kotlin input and flags always produce byte-identical output.

`kotlin-cpp` ships four front ends over one pure-KMP core:

- a **native command-line tool** (`kotlin-cpp` / `kotlin-cpp.kexe`), with no JVM at runtime
- a **Kotlin Multiplatform library** (`io.github.kotlinmania:kotlin-cpp`) targeting JVM, Android, Apple, Linux, Windows, Android Native, JS, Wasm-JS, and Wasm-WASI
- a **Swift Export module & XCFramework** (`KotlinCpp`) for direct use from Swift on Apple platforms
- a **JetBrains IntelliJ IDEA plugin** (`ide-plugin/`) that adds a *Convert Kotlin File to C++23* action (`k2cpp`) and tool window

---

## Architecture

1. **ANTLR4 Specification Frontend + Universal `syn-kotlin` Packet Stream (`io.github.kotlinmania.kotlincpp.packet`)**
   - Lexes and parses Kotlin `.kt` source through KotlinMania's pure-KMP [`antlr4-kotlin`](https://github.com/KotlinMania/antlr4-kotlin) runtime (`KotlinLexer` + `KotlinParser`).
   - Partitions input UTF-16 / UTF-8 spans without loss, extracting comments, KDoc `[link]` spans, `// port-lint:` provenance directives, string template segments, and balanced delimiter groups.
   - `UniversalSynTokenAssociation` binds Kotlin keywords and operators to canonical [`syn-kotlin`](https://github.com/KotlinMania/syn-kotlin) (`io.github.kotlinmania.syn.token.*`) and [`proc-macro2-kotlin`](https://github.com/KotlinMania/proc-macro2-kotlin) `TokenStream` / `TokenTree` tokens (`fun` -> `Token![fn]`, `suspend` -> `Token![async]`, `val`/`var` -> `Token![let]`, `when` -> `Token![match]`, `?:` -> `Token![?] + Token![:]`, etc.).
   - `KotlinPacketEmitter` (`renderKotlinRoundTrip`) regenerates canonical Kotlin directly from `PacketStream` to prove a byte-exact Kotlin -> `PacketStream` -> Kotlin round-trip.

2. **6-Pass Deterministic C++23 Transliterator (`io.github.kotlinmania.kotlincpp.kcpp`)**
   - **Pass 1 (`SameFileSymbolPass`)**: Same-file declaration, type, callable table, and forward-declaration discovery.
   - **Pass 2 (`SuspendStateMachinePass`)**: `FirSuspendCallChecker` validation, dual-ABI `[[clang::annotate("suspend")]]` continuation pairs, inline-lambda expansion, left-to-right argument hoisting before suspend points, and IIFE-free control-flow hoisting.
   - **Pass 3 (`GcAndOwnershipPass`)**: Standalone RAII (`CppGcMode.NONE`) or Kotlin/Native Docking-Ring GC (`CppGcMode.KONAN_GC`) stack `ObjHolder` roots, cross-suspend `OwningExternalRCRef` spills with dead-slot `.reset()`, `CalledFromNativeGuard`, and `Kotlin_mm_UpdateHeapRef` barriers.
   - **Pass 4 (`AtomicAndThreadLocalPass`)**: `kotlinx.atomicfu` fields, lvalue-safe `compare_exchange_strong` / `Kotlin_mm_CompareAndSetVolatileHeapRef`, `.loop { ... }` lowering, and `ThreadLocal<T>`.
   - **Pass 5 (`FfiAndCTypePass`)**: `@InlineCpp` / `inlineCpp(...)`, `kotlinx.cinterop` (`CPointer`, `memScoped`, `alloc`, `usePinned` with compile-time cross-suspend pin rejection), and `@CName` `extern "C"` plus optional `<Stem>_c_api.h` / `<Stem>.def` companion C-ABI artifacts.
   - **Pass 6 (`ControlFlowAndTypePass` + `CppCodeEmitter`)**: Emits co-located C++23 `.hpp` / `.cpp` files (`operator[](r, c)`, `std::unreachable()`, `sealed class` / `data class` / `enum class` lowering) through structured `CppToken`, `CppTokenStream`, `CppTokenEmitter`, and `sealed class CppType` tokens.

---

## Example

Input, `SumKernel.kt`:

```kotlin
// port-lint: source kernel/SumKernel.kt
package kotlinx.coroutines.kernel

/** Accumulates integer deltas with a fixed [bias]. */
public class SumBox(
    public val bias: Int,
) {
    public fun add(a: Int, b: Int): Int = bias + a + b
}
```

Translate with the native CLI:

```bash
./build/bin/kotlin-cpp -o out SumKernel.kt
```

Generated `out/kotlinx/coroutines/kernel/SumKernel.hpp`:

```cpp
#pragma once
// port-lint: source kernel/SumKernel.kt
#include <cstdint>

namespace kotlinx::coroutines::kernel {

using namespace kotlinx::coroutines;

class SumBox;

/** Accumulates integer deltas with a fixed \ref bias. */
class SumBox {
public:
    const std::int32_t bias;

    explicit SumBox(std::int32_t bias_value);

    std::int32_t add(std::int32_t a, std::int32_t b) const;
};

} // namespace kotlinx::coroutines::kernel
```

Generated `out/kotlinx/coroutines/kernel/SumKernel.cpp`:

```cpp
// port-lint: source kernel/SumKernel.kt
#include "kotlinx/coroutines/kernel/SumKernel.hpp"
#include <cstdint>
#include <utility>
#include "kotlinx/coroutines/kernel/SumKernel.hpp"

namespace kotlinx::coroutines::kernel {

SumBox::SumBox(std::int32_t bias_value) : bias(std::move(bias_value)) {}

std::int32_t SumBox::add(std::int32_t a, std::int32_t b) const {
    return bias + a + b;
}

} // namespace kotlinx::coroutines::kernel
```

Compile and execute the translated C++23 code with Clang:

```bash
clang++ -std=c++23 -Wall -Wextra -Werror -Iout \
  out/kotlinx/coroutines/kernel/SumKernel.cpp main.cpp -o sum_check
```

---

## Command-line usage

```text
kotlin-cpp [kcpp | translate] [options] <input.kt | source directory...>
kotlin-cpp roundtrip [-f|--force] <input.kt> <regenerated.kt>
kotlin-cpp packets [-r|--recursive] <input.kt | source directory...>
kotlin-cpp tokens [-r|--recursive] <input.kt | source directory...>
kotlin-cpp --help | --version
```

### Common invocations

Transliterate one Kotlin file to co-located `.hpp` / `.cpp` files under `build/cpp_out`:

```bash
kotlin-cpp -o build/cpp_out path/to/File.kt
```

Recursively transliterate a source tree with provenance prefix stripping, custom root namespace, Konan GC barriers, and `@CName` companion headers:

```bash
kotlin-cpp -r \
  --source-root src/commonMain/kotlin \
  --port-lint-prefix src/commonMain/kotlin \
  -n kotlinx::coroutines \
  --gc-mode konan \
  --companion-c-abi \
  -o build/cpp_out \
  src/commonMain/kotlin
```

Preview generated C++23 code to stdout or inspect planned output paths without writing files:

```bash
kotlin-cpp --stdout path/to/File.kt
kotlin-cpp -r --dry-run -o build/cpp_out src/commonMain/kotlin
```

Verify lossless Kotlin -> `PacketStream` (ANTLR4 + universal `syn-kotlin`) -> Kotlin round-trip:

```bash
kotlin-cpp roundtrip Input.kt Regenerated.kt
```

Inspect `PacketStream` frames, comments, KDoc `[link]` targets, and `syn-kotlin` token associations:

```bash
kotlin-cpp packets path/to/File.kt
kotlin-cpp tokens -r src/commonMain/kotlin
```

Existing target files are never overwritten unless you pass `-f` / `--force` / `--overwrite`; pass `--skip-existing` to leave existing files untouched and continue.

### Options

| Option | Meaning |
|---|---|
| `-o`, `--output <dir>` | Output root directory (default: `.`) |
| `-r`, `--recursive` | Recursively discover `.kt` files in input directories |
| `-f`, `--force`, `--overwrite` | Overwrite existing `.hpp` / `.cpp` / `.kt` target files |
| `--skip-existing` | Skip target files that already exist |
| `--stdout` | Print generated C++23 files to stdout without writing disk files |
| `--dry-run` | Print planned `input -> output` actions only |
| `--gc-mode <none\|konan>` | Memory/GC target mode: `none` (`raii`/`standalone`, default) or `konan` (`konan_gc`/`konan-gc`, Kotlin/Native Docking-Ring GC) |
| `--port-lint-prefix <prefix>` | Synthesize `// port-lint: source <prefix>/<relPath>` when absent |
| `--source-root <dir>` | Base directory stripped when computing provenance paths |
| `-n`, `--namespace <ns>` | Override root C++ namespace (e.g. `kotlinx::coroutines`) |
| `--file-stem-suffix <suffix>` | Append multiplatform stem suffix (e.g. `.native`, `.concurrent`) |
| `--source-set-subdir <dir>` | Insert source-set subdirectory under `kotlinx/coroutines` (e.g. `native`, `concurrent`) |
| `--companion-c-abi` | Emit `<Stem>_c_api.h` and `<Stem>.def` for `@CName` exports |
| `-q`, `--quiet` | Suppress progress summary lines |
| `-v`, `-V`, `--version` | Show version information |
| `-h`, `--help` | Show CLI help message |

Exit status is `0` on success and `2` on a usage, parse, or translation error.

---

## Kotlin Multiplatform Library Usage

Add the common dependency in `commonMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.kotlinmania:kotlin-cpp:1.1.0")
        }
    }
}
```

Use the lossless packet stream, Kotlin round-trip emitter, 6-pass C++23 transliterator, and structured `CppTokenEmitter` / `CppType` APIs directly in Kotlin:

```kotlin
import io.github.kotlinmania.kotlincpp.kcpp.CppGcMode
import io.github.kotlinmania.kotlincpp.kcpp.CppTargetConfig
import io.github.kotlinmania.kotlincpp.kcpp.CppTokenEmitter
import io.github.kotlinmania.kotlincpp.kcpp.CppType
import io.github.kotlinmania.kotlincpp.kcpp.translateKotlinToCpp
import io.github.kotlinmania.kotlincpp.packet.buildKotlinPacketStream
import io.github.kotlinmania.kotlincpp.renderKotlinRoundTrip

fun example(kotlinSource: String) {
    // 1. Parse into a lossless ANTLR4 + universal syn-kotlin PacketStream
    val stream = buildKotlinPacketStream(kotlinSource, sourcePath = "Example.kt")
    check(stream.renderRawLossless() == kotlinSource)

    // 2. Verify canonical Kotlin round-trip
    val roundTrip = renderKotlinRoundTrip(kotlinSource)
    check(roundTrip.text == kotlinSource)

    // 3. Lower to co-located C++23 .hpp / .cpp (plus optional C ABI files)
    val result =
        translateKotlinToCpp(
            source = kotlinSource,
            sourcePath = "Example.kt",
            config =
                CppTargetConfig(
                    cppStandard = 23,
                    gcMode = CppGcMode.NONE,
                    emitCompanionCAbiFiles = true,
                ),
        )
    println(result.headerArtifact.relativePath)
    println(result.sourceArtifact?.content.orEmpty())

    // 4. Construct C++23 tokens & structured types programmatically
    val parsedType = CppType.parse("const std::optional<std::int32_t>").stripCvRef()
    val cppDecl =
        CppTokenEmitter(baseIndent = "    ")
            .emitForwardingOverride(
                returnType = parsedType.unwrapOptional().render(),
                methodName = "read_value",
                paramsDecl = "",
                isConst = true,
                isVoidReturn = false,
                receiverExpr = "delegate_",
                memberAccessOp = "->",
                callArgsExpr = "",
            ).render()
    println(cppDecl)
}
```

---

## Swift Usage (Swift Export)

`kotlin-cpp` exports the `KotlinCpp` Swift module (plus `KotlinCpp.xcframework` for Apple platforms):

```swift
import KotlinCpp

func transliterateExample() {
    let kotlinSource = """
    package kotlinx.coroutines.kernel

    public class Counter(public val step: Int) {
        public fun next(value: Int): Int = value + step
    }
    """

    let roundTripped = renderKotlinRoundTrip(source: kotlinSource)
    assert(roundTripped.text == kotlinSource)

    let config = kcpp.CppTargetConfig(
        cppStandard: 23,
        gcMode: kcpp.CppGcMode.NONE,
        portLintPrefix: nil,
        sourceRootPrefix: nil,
        rootNamespaceOverride: nil,
        fileStemSuffix: nil,
        sourceSetSubdir: nil,
        emitCompanionCAbiFiles: false
    )
    let result = kcpp.translateKotlinToCpp(
        source: kotlinSource,
        sourcePath: "Counter.kt",
        config: config
    )
    print(result.headerArtifact.content)

    let emitter = kcpp.CppTokenEmitter(baseIndent: "    ")
    _ = emitter.emitTemplateDeclaration(typeParamClauses: ["typename T"], requiresClause: "")
    let parsed = kcpp.CppType.Companion.shared.parse(
        rendered: "const std::optional<std::int32_t>",
        typeParamNames: []
    ).stripCvRef()
    assert(parsed.isOptional)
}
```

---

## IntelliJ IDEA Plugin

The `ide-plugin` module packages the same transliteration core for IntelliJ IDEA `2025.3.6.1`:

- **Convert Kotlin File to C++23**: Available from the *Code* menu, Project view context menu, and Editor context menu (search abbreviation `k2cpp`). Translates a single `.kt` file or recursively converts a directory with overwrite/skip prompts.
- **Kotlin C++23 Transpiler** tool window: Provides **Verify Kotlin Round-Trip** and **Transpile Kotlin to C++23** actions directly on editor files.

Run a sandbox IDE with the plugin loaded:

```bash
./gradlew runIde
```

Build the distributable plugin ZIP:

```bash
./gradlew buildPlugin
```

---

## Building & Verification

Requirements: JDK 21 and, for Apple / Swift Export targets, macOS on Apple Silicon with Xcode. The Gradle wrapper (`9.8.2`) is checked in.

When building from source prior to `io.github.kotlinmania:antlr4-kotlin:0.2.1` Maven Central publication, check out sibling [`KotlinMania/antlr4-kotlin`](https://github.com/KotlinMania/antlr4-kotlin) alongside `kotlin-cpp` (`settings.gradle.kts` links `../antlr4-kotlin`):

```bash
git clone https://github.com/KotlinMania/antlr4-kotlin.git
git clone https://github.com/KotlinMania/kotlin-cpp.git
cd kotlin-cpp
./gradlew build
./gradlew test
```

The staged native macOS CLI binary is written to `build/bin/kotlin-cpp` (copied from `build/bin/macosArm64/releaseExecutable/kotlin-cpp.kexe`).

Verify the native CLI executable end-to-end (Mach-O architecture, zero JVM runtime linkage, `tokens`, `packets`, `roundtrip`, C++23 emission, and `clang++ -std=c++23 -Wall -Wextra -Werror` compile-and-run):

```bash
python3 tools/check-native-cli.py build/cli-check
```

### Supported targets

| Target | Role |
|---|---|
| `jvm` | Library target and host for the IntelliJ IDEA plugin |
| `android` | Android KMP library (API 24+, compiled against API 34) |
| `macosArm64` | Native CLI executable, XCFramework, shared library, and Swift Export (`KotlinCpp`) |
| `iosArm64`, `iosSimulatorArm64` | Apple iOS XCFramework targets |
| `tvosArm64`, `tvosSimulatorArm64` | Apple tvOS XCFramework targets |
| `watchosDeviceArm64`, `watchosSimulatorArm64` | Apple watchOS XCFramework targets |
| `linuxX64`, `linuxArm64` | Linux CLI executables, shared libraries, and native tests |
| `mingwX64` | Windows 64-bit CLI executable, shared library, and native tests |
| `androidNativeArm64`, `androidNativeX64` | Android NDK 64-bit shared libraries and native tests |
| `js` | Kotlin/JS library (Browser + Node.js) |
| `wasmJs` | Kotlin/Wasm-JS library (Browser + Node.js) |
| `wasmWasi` | Kotlin/Wasm-WASI library (Node.js) |

### Dependencies

All runtime dependencies are pure-KMP KotlinMania libraries under `io.github.kotlinmania`: `km-io`, `antlr4-kotlin`, `proc-macro2-kotlin`, and `syn-kotlin`, pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml). `maven.properties` controls whether Maven Local is consulted first.

---

## Status

Project version `1.1.0`. No release has been published to Maven Central or JetBrains Marketplace yet; build from source (with `../antlr4-kotlin` checked out alongside `kotlin-cpp`).

GitHub Actions workflows are configured for manual `workflow_dispatch` (`ci.yml` + reusable per-platform workflows) and `release: [released]` (`publish.yml`).

---

## Repository layout

```text
src/common/commonMain/        ANTLR4 Kotlin lexer/parser, PacketStream, and 6-pass C++23 emitter
src/common/commonTest/        Multiplatform packet, round-trip, token, and C++23 lowering tests
src/native/                   Native CLI entrypoint and POSIX / MinGW platform IO
src/jvm/                      JVM platform IO, JNI bridge, and host tests
src/android/                  Android platform IO
src/js/                       JS, Wasm-JS, and Wasm-WASI platform IO
src/apple/                    macOS C++23 runtime compile-and-execute tests
src/swift/swift-test-harness/ Swift Export (`KotlinCpp`) Swift Testing suite
ide-plugin/                   IntelliJ IDEA (`2025.3.6.1`) plugin
probes/                       Recorded generality probes
third_party/                  Provenance and licenses for adapted upstream sources
verification/                 All-target, Maven mode, and publication verification notes
tools/                        Native CLI end-to-end smoke check (`check-native-cli.py`)
```

Related documents: [CHANGELOG.md](CHANGELOG.md), [AGENTS.md](AGENTS.md), [SWIFT.md](SWIFT.md), and [verification/README.md](verification/README.md).

---

## Maintainer

Sydney Renee, The Solace Project
GitHub: [@sydneyrenee](https://github.com/sydneyrenee)
Email: [sydney@solace.ofharmony.ai](mailto:sydney@solace.ofharmony.ai)

Issues and pull requests are welcome at [KotlinMania/kotlin-cpp](https://github.com/KotlinMania/kotlin-cpp/issues).

---

## License

Copyright (c) 2026 Sydney Renee and The Solace Project.

`kotlin-cpp` is dual-licensed under either of:

- **Apache License, Version 2.0** ([LICENSE-APACHE](LICENSE-APACHE) or http://www.apache.org/licenses/LICENSE-2.0)
- **MIT License** ([LICENSE-MIT](LICENSE-MIT) or https://opensource.org/licenses/MIT)

at your option; see [LICENSE](LICENSE) for the full combined text. Adapted upstream components retain their own licenses and notices; see [third_party/](third_party/).
