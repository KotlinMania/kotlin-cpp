import Foundation
import Testing
import KotlinCpp

@Suite("KotlinCpp Swift Export Tests")
struct KotlinCppExportTests {
    @Test("Builds lossless ANTLR4 + universal syn-kotlin PacketStream and round-trips Kotlin byte-for-byte")
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

    @Test("Associates Kotlin syntax with universal syn-kotlin tokens without false positives in comments or strings")
    func testUniversalSynTokenAssociations() {
        let source = """
        public suspend fun choose(mode: Int): Int {
            /* when return */
            val `when` = "if fun return"
            return when (mode) {
                0 -> 1
                else -> 2
            }
        }
        """
        let summary = summarizeKotlinTokens(source: source, sourcePath: "Choose.kt")
        #expect(summary.packetCount > 0)
        #expect(summary.leafTokenCount > 0)
        #expect(summary.groupTokenCount >= 3)

        let pairs = summary.associations.map { "\($0.kotlinSpelling)->\($0.universalSynToken):\($0.count)" }
        #expect(pairs.contains("public->Token![pub]:1"))
        #expect(pairs.contains("suspend->Token![async]:1"))
        #expect(pairs.contains("fun->Token![fn]:1"))
        #expect(pairs.contains("val->Token![let]:1"))
        #expect(pairs.contains("return->Token![return]:1"))
        #expect(pairs.contains("when->Token![match]:1"))
        #expect(pairs.contains("else->Token![else]:1"))
        #expect(!pairs.contains { $0.hasPrefix("if->") })
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
            fileStemSuffix: nil,
            sourceSetSubdir: nil,
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

    @Test("Emits Konan Docking-Ring GC barriers and companion C ABI headers via Swift Export")
    func testKonanGcAndCompanionCAbiExport() {
        let kotlinSource = """
        package kotlinx.coroutines.ffi

        public class BoxNode(public var next: BoxNode?)

        @CName("kxs_link_nodes")
        public fun linkNodes(head: BoxNode, tail: BoxNode): Int {
            head.next = tail
            return 0
        }

        """
        let config = kcpp.CppTargetConfig(
            cppStandard: 23,
            gcMode: kcpp.CppGcMode.KONAN_GC,
            portLintPrefix: nil,
            sourceRootPrefix: nil,
            rootNamespaceOverride: nil,
            fileStemSuffix: nil,
            sourceSetSubdir: nil,
            emitCompanionCAbiFiles: true
        )
        let result = kcpp.translateKotlinToCpp(
            source: kotlinSource,
            sourcePath: "BoxNode.kt",
            config: config
        )
        #expect(result.sourceArtifact?.content.contains("Kotlin_initRuntimeIfNeeded();") == true)
        #expect(result.sourceArtifact?.content.contains("Kotlin_mm_UpdateHeapRef") == true)
        #expect(result.companionArtifacts.count == 2)
        #expect(result.companionArtifacts[0].relativePath == "BoxNode_c_api.h")
        #expect(result.companionArtifacts[1].relativePath == "BoxNode.def")
    }
}
