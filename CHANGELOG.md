<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# kotlin-cpp Changelog

## [Unreleased]

## [1.1.0] - 2026-11-15

### Added
- Pure-KMP ANTLR4 Kotlin specification grammar frontend (`KotlinLexer` + `KotlinParser`) and lossless `PacketStream` (`buildKotlinPacketStream`, `renderKotlinRoundTrip`) with universal `syn-kotlin` and `proc-macro2-kotlin` token associations.
- 6-pass deterministic Kotlin-to-C++23 mechanical transliterator (`SameFileSymbolPass`, `SuspendStateMachinePass`, `GcAndOwnershipPass`, `AtomicAndThreadLocalPass`, `FfiAndCTypePass`, and `ControlFlowAndTypePass`) emitting co-located `.hpp` / `.cpp` files.
- Dual-ABI `[[clang::annotate("suspend")]]` continuation pairs, standalone RAII (`CppGcMode.NONE`) and Kotlin/Native Docking-Ring GC (`CppGcMode.KONAN_GC`) memory modes, `kotlinx.atomicfu` CAS lowering, and `@CName` companion C ABI (`.h` / `.def`) generation.
- Structured C++23 `CppToken`, `CppTokenStream`, `CppTokenEmitter`, and `sealed class CppType` token and type emission pipeline.
- Standalone native CLI (`kotlin-cpp`), Swift Export module & XCFramework (`KotlinCpp`), and JetBrains IntelliJ IDEA (`2025.3.6.1`) plugin (`k2cpp`).
