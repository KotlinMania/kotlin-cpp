package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustkotlin.packet.CommentPacketKind
import io.github.kotlinmania.rustkotlin.packet.LiteralPacketKind
import io.github.kotlinmania.rustkotlin.packet.PacketFlags
import io.github.kotlinmania.rustkotlin.packet.StringSegmentKind
import io.github.kotlinmania.rustkotlin.packet.SymbolRole
import io.github.kotlinmania.rustkotlin.packet.SyntaxPacket
import io.github.kotlinmania.rustkotlin.packet.buildKotlinPacketStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PacketStreamRoundTripTest {
    @Test
    fun packetStreamPreservesEveryByteCommentKDocLinkAndDelimiterPair() {
        val source =
            """
            #!/usr/bin/env kotlin
            // port-lint: source kotlinx-coroutines-core/common/src/Yield.kt
            package kotlinx.coroutines

            import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

            /**
             * Suspends via [COROUTINE_SUSPENDED] and notifies [Job.join].
             */
            public suspend fun <T> yieldValue(
                `when`: T,
                count: ULong = 0x10uL,
            ): String {
                /* interior block comment */
                val msg = "item=${'$'}`when`, next=${'$'}{count + 1uL}\n"
                if (msg.isEmpty()) { /* keep empty-block comment */ }
                return msg
            }
            """.trimIndent() + "\n"

        val stream = buildKotlinPacketStream(source, sourcePath = "YieldSample.kt")
        assertEquals(source, stream.renderRawLossless())
        assertEquals(source, renderKotlinRoundTrip(source).text)

        val comments = stream.commentPackets()
        assertEquals(5, comments.size)
        assertEquals(CommentPacketKind.SHEBANG, comments[0].kind)
        assertEquals(CommentPacketKind.LINE, comments[1].kind)
        assertEquals("source", comments[1].portLintDirectiveKind)
        assertEquals("kotlinx-coroutines-core/common/src/Yield.kt", comments[1].portLintTarget)
        assertTrue(PacketFlags.hasFlag(comments[1].flags, PacketFlags.PORT_LINT_DIRECTIVE))

        assertEquals(CommentPacketKind.KDOC, comments[2].kind)
        assertTrue(PacketFlags.hasFlag(comments[2].flags, PacketFlags.DOC_COMMENT_HAS_LINKS))
        assertEquals(
            listOf("COROUTINE_SUSPENDED", "Job.join"),
            comments[2].kdocLinks.map { it.targetPath },
        )
        assertEquals(CommentPacketKind.BLOCK, comments[3].kind)
        assertEquals(CommentPacketKind.BLOCK, comments[4].kind)

        val escapedParams =
            stream.packets
                .filterIsInstance<SyntaxPacket.IdentPacket>()
                .filter { PacketFlags.hasFlag(it.flags, PacketFlags.BACKTICK_ESCAPED) }
        assertEquals(listOf("when", "when"), escapedParams.map { it.unescapedName })
        assertEquals(SymbolRole.PARAM, escapedParams.first().role)

        val hexUnsignedLit =
            stream.packets
                .filterIsInstance<SyntaxPacket.LiteralPacket>()
                .first { it.rawText == "0x10uL" }
        assertEquals(LiteralPacketKind.INT, hexUnsignedLit.literalKind)
        assertEquals(16, hexUnsignedLit.radix)
        assertEquals(64, hexUnsignedLit.numericWidthBits)
        assertTrue(hexUnsignedLit.isUnsigned)

        val stringSegments = stream.packets.filterIsInstance<SyntaxPacket.StringSegmentPacket>()
        assertTrue(stringSegments.any { it.segmentKind == StringSegmentKind.SHORT_ENTRY_START })
        assertTrue(stringSegments.any { it.segmentKind == StringSegmentKind.LONG_ENTRY_START })
        assertTrue(stringSegments.any { it.segmentKind == StringSegmentKind.ESCAPE_SEQ })

        // Verify angle-bracket delimiter pairing on `<T>` and brace pairing on `{ ... }`.
        val angleDelimiters =
            stream.packets
                .filterIsInstance<SyntaxPacket.DelimiterPacket>()
                .filter { it.rawText == "<" || it.rawText == ">" }
        assertEquals(2, angleDelimiters.size)
        assertEquals(angleDelimiters[1].packetIndex, angleDelimiters[0].matchingPacketIndex)
        assertEquals(angleDelimiters[0].packetIndex, angleDelimiters[1].matchingPacketIndex)

        val fnFrame = stream.frames.first { it.semanticInfo?.symbolName == "yieldValue" }
        assertTrue(PacketFlags.hasFlag(fnFrame.flags, PacketFlags.MOD_PUBLIC))
        assertTrue(PacketFlags.hasFlag(fnFrame.flags, PacketFlags.MOD_SUSPEND))
    }

    @Test
    fun richCoroutineAndAtomicConstructsRoundTripByteForByte() {
        val source =
            """
            // port-lint: source tmp/rust_core/sync/atomic.rs
            package kotlinx.coroutines.channels

            import kotlinx.cinterop.CPointer

            /**
             * Strategy for [BufferOverflow] handling:
             * - [SUSPEND] suspends sender
             * - [DROP_OLDEST] drops oldest element
             */
            public enum class BufferOverflow {
                SUSPEND,
                DROP_OLDEST,
                DROP_LATEST,
            }

            public sealed class ChannelResult<out T> {
                public data class Value<out T>(
                    public val item: T,
                ) : ChannelResult<T>()

                public data class Closed(
                    public val cause: Throwable?,
                ) : ChannelResult<Nothing>()

                public companion object {
                    public const val DEFAULT_CAPACITY: Int = 64
                }
            }

            public class AtomicBox<T>(
                private var current: CPointer<T>?,
            ) {
                public fun snapshot(): CPointer<T>? = current

                public suspend fun emitOrSuspend(
                    mode: BufferOverflow,
                    block: suspend (BufferOverflow) -> Int,
                ): Int =
                    suspendCoroutineUninterceptedOrReturn sc@ { uCont ->
                        val picked =
                            when (mode) {
                                BufferOverflow.SUSPEND -> 1
                                BufferOverflow.DROP_OLDEST -> 2
                                BufferOverflow.DROP_LATEST -> 3
                            }
                        try {
                            if (picked == 0) return@sc 0
                            picked
                        } catch (err: Throwable) {
                            throw err
                        } finally {
                            // deterministic cleanup remark
                        }
                    }
            }
            """.trimIndent() + "\n"

        val roundTripped = renderKotlinRoundTrip(source)
        assertEquals(source, roundTripped.text)
        assertEquals("3 items round-tripped", roundTripped.summary)

        val writes = linkedMapOf<String, String>()
        val reports = mutableListOf<String>()
        runSingleFileCommand(
            args = arrayOf("roundtrip", "ChannelSample.kt", "ChannelSample.out.kt"),
            read = { path ->
                assertEquals("ChannelSample.kt", path)
                source
            },
            write = { path, content -> writes[path] = content },
            report = reports::add,
        )
        assertEquals(source, writes["ChannelSample.out.kt"])
        assertEquals(listOf("roundtrip\tChannelSample.kt\tChannelSample.out.kt\tMATCH"), reports)
    }

    @Test
    fun packetsCliCommandReportsCountsLinksAndSuspendDeclarationsWithoutWriting() {
        val source =
            """
            /** Links to [Alpha] and [Beta]. */
            public suspend fun compute(): Int {
                // line note
                return 7
            }
            """.trimIndent() + "\n"

        val messages = mutableListOf<String>()
        runSingleFileCommand(
            args = arrayOf("packets", "Compute.kt"),
            read = { path ->
                assertEquals("Compute.kt", path)
                source
            },
            isDirectory = { false },
            write = { _, _ -> error("packets command must not write files") },
            writeForce = { _, _ -> error("packets command must not overwrite files") },
            report = messages::add,
        )
        assertEquals(1, messages.size)
        val line = messages.single()
        assertTrue(line.startsWith("packets\tCompute.kt\t"))
        assertTrue(line.contains("\tcomments=2\t"))
        assertTrue(line.contains("\tkdocLinks=2\t"))
        assertTrue(line.contains("\tsuspendDecls=1"))
    }

    @Test
    fun loneSurrogateEscapeAndUtf8SupplementaryOffsetsAreTrackedAccurately() {
        val source = "public val surrogate: String = \"\\uD800\"\npublic val wave: String = \"👋\"\n"
        val stream = buildKotlinPacketStream(source, sourcePath = "Surrogate.kt")
        assertEquals(source, stream.renderRawLossless())
        assertEquals(source, renderKotlinRoundTrip(source).text)

        val loneEscape =
            stream.packets
                .filterIsInstance<SyntaxPacket.StringSegmentPacket>()
                .first { it.rawText == "\\uD800" }
        assertTrue(PacketFlags.hasFlag(loneEscape.flags, PacketFlags.HAS_ESCAPES))
        assertTrue(PacketFlags.hasFlag(loneEscape.flags, PacketFlags.HAS_LONE_SURROGATE))

        val emojiPart =
            stream.packets
                .filterIsInstance<SyntaxPacket.StringSegmentPacket>()
                .first { it.rawText == "👋" }
        assertEquals(2, emojiPart.span.utf16Length)
        assertEquals(4, emojiPart.span.utf8ByteLength)
    }

    @Test
    fun forbiddenPortLintIgnoreAndMalformedKotlinSyntaxFailLoudly() {
        assertFailsWith<IllegalArgumentException> {
            buildKotlinPacketStream("// port-lint: ignore foo.rs\npublic fun x(): Int = 1\n")
        }
        assertFailsWith<IllegalArgumentException> {
            renderKotlinRoundTrip("public fun broken(: Int {")
        }
        val unnormalized = "public fun compact(): Int { val x = 1; return x + 2 }"
        val regenerated = renderKotlinRoundTrip(unnormalized).text
        assertNotEquals(unnormalized, regenerated)
        assertEquals(
            "public fun compact(): Int {\n    val x = 1\n    return x + 2\n}\n",
            regenerated,
        )
    }
}
