import Foundation
import Testing
import RustKotlin

@Suite("RustKotlin Swift Export Tests")
struct RustKotlinExportTests {
    @Test("Translates Rust function to idiomatic Kotlin via exported API")
    func testRenderSingleKotlinFile() {
        let props = parseGradleProperties(
            content: """
            project.group=io.github.kotlinmania
            project.name=sample-kotlin
            """
        )
        #expect(props.projectGroup == "io.github.kotlinmania")
        #expect(props.projectNamespace == "io.github.kotlinmania.sample")

        let header = FileHeaderContext(
            portLintSourcePath: "math_ops.rs",
            packageName: props.projectNamespace,
            projectGroup: props.projectGroup,
            projectNamespace: props.projectNamespace,
            crateNamespaces: [:]
        )
        let translation = renderSingleKotlinFile(
            source: "pub fn add_values(left_num: i32, right_num: i32) -> i32 { left_num + right_num }\n",
            headerContext: header
        )
        #expect(translation.text.contains("// port-lint: source math_ops.rs"))
        #expect(translation.text.contains("package io.github.kotlinmania.sample"))
        #expect(translation.text.contains("public fun addValues(leftNum: Int, rightNum: Int): Int"))
    }

    @Test("Naming policy converts Rust identifiers to Kotlin conventions")
    func testNamingPolicy() {
        let policy = KotlinNamingPolicy.Companion.shared.Default
        #expect(policy.toTypeIdentifier(raw: "token_stream") == "TokenStream")
        #expect(policy.toValueIdentifier(raw: "next_item", preserveScreamingSnake: false) == "nextItem")
        #expect(policy.toConstIdentifier(raw: "SYS_exit") == "SYS_EXIT")
        #expect(rustModuleIdentToKotlin(raw: "__private") == "priv")
    }

    @Test("Generates companion CInterop files for inline C++ and extern C declarations")
    func testCompanionInteropEmission() {
        let header = FileHeaderContext(
            portLintSourcePath: "inline_demo.rs",
            packageName: "io.github.kotlinmania.demo",
            projectGroup: "io.github.kotlinmania",
            projectNamespace: "io.github.kotlinmania.demo",
            crateNamespaces: [:]
        )
        let rustSource = """
        cpp! {{
            #include <stdint.h>
        }}
        pub fn double_num(val_in: i32) -> i32 {
            cpp!([val_in as "int32_t"] -> i32 as "int32_t" {
                return val_in * 2;
            })
        }
        """
        let result = renderSingleKotlinFile(source: rustSource, headerContext: header)
        #expect(result.text.contains("internal expect fun doubleNumCpp(valIn: Int): Int"))
        #expect(result.companionFiles.count == 4)
        let paths = result.companionFiles.map { $0.relativePath }
        #expect(paths.contains("InlineDemo.def"))
        #expect(paths.contains("inline_demo_wrapper.h"))
        #expect(paths.contains("inline_demo_wrapper.cpp"))
        #expect(paths.contains("InlineDemoNativeActuals.kt"))
    }

    @Test("Builds lossless Kotlin PacketStream with KDoc links, suspend flags, and byte-exact round-trip")
    func testKotlinPacketStreamAndRoundTrip() {
        let kotlinSource = """
        // port-lint: source Yield.kt
        package kotlinx.coroutines

        /** Suspends caller and notifies [Job]. */
        public suspend fun yieldNow(step: Int): String {
            // keep inline note
            return "step=$step"
        }

        """
        let stream = packet.buildKotlinPacketStream(source: kotlinSource, sourcePath: "Yield.kt")
        #expect(stream.renderRawLossless() == kotlinSource)
        #expect(stream.commentPackets().count == 3)
        let kdocPacket = stream.commentPackets()[1]
        #expect(kdocPacket.kind == packet.CommentPacketKind.KDOC)
        #expect(kdocPacket.kdocLinks.count == 1)
        #expect(kdocPacket.kdocLinks[0].targetPath == "Job")

        let roundTripped = renderKotlinRoundTrip(source: kotlinSource)
        #expect(roundTripped.text == kotlinSource)
        #expect(roundTripped.summary == "1 items round-tripped")
    }

    @Test("Transliterates Kotlin source into C++23 .hpp/.cpp artifacts via exported kcpp API")
    func testKotlinToCppTranslationExport() {
        let kotlinSource = """
        // port-lint: source internal/Symbol.kt
        package kotlinx.coroutines.internal

        public class Symbol(public val symbol: String) {
            public fun labelText(): String = "<" + symbol + ">"
        }

        """
        let config = kcpp.CppTargetConfig(
            cppStandard: 23,
            gcMode: kcpp.CppGcMode.NONE,
            portLintPrefix: nil,
            sourceRootPrefix: nil,
            rootNamespaceOverride: nil,
            emitCompanionCAbiFiles: false
        )
        let result = kcpp.translateKotlinToCpp(
            source: kotlinSource,
            sourcePath: "internal/Symbol.kt",
            config: config
        )
        #expect(result.headerArtifact.relativePath == "kotlinx/coroutines/internal/Symbol.hpp")
        #expect(result.headerArtifact.content.contains("#pragma once"))
        #expect(result.headerArtifact.content.contains("namespace kotlinx::coroutines::internal"))
        #expect(result.headerArtifact.content.contains("explicit Symbol(std::string symbol_value);"))
        #expect(result.sourceArtifact?.relativePath == "kotlinx/coroutines/internal/Symbol.cpp")
        #expect(result.translatedDeclarationCount == 1)
    }
}
