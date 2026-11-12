package io.github.kotlinmania.kotlincpp.packet

import io.github.kotlinmania.procmacro2.TokenStream

/**
 * Semantic enrichment attached to a [PacketFrame] by lowering passes.
 */
public data class FrameSemanticInfo(
    public val symbolName: String? = null,
    public val resolvedTypeText: String? = null,
    public val hoistedPrefixFrameIndices: List<Int> = emptyList(),
    public val inlineCppBody: String? = null,
)

/**
 * Hierarchical syntax production slice (`firstPacketIndex..lastPacketIndex`) overlaid
 * on a [PacketStream] without copying or stripping trivia packets.
 *
 * Child node references use signed-integer encoding:
 * - `ref >= 0` addresses a child [PacketFrame] at `stream.frames[ref]`.
 * - `ref < 0` addresses a leaf [SyntaxPacket] at `stream.packets[-(ref + 1)]`
 *   (including [SyntaxPacket.WhitespacePacket] and [SyntaxPacket.CommentPacket]).
 */
public class PacketFrame internal constructor(
    public val frameIndex: Int,
    public val nodeTypeId: Int,
    public val debugName: String,
    public val firstPacketIndex: Int,
    public val lastPacketIndex: Int,
    public val span: PacketSpan,
    public val parentFrameIndex: Int,
    internal val rawChildNodeRefs: IntArray,
    public var flags: Long = PacketFlags.NONE,
    public var semanticInfo: FrameSemanticInfo? = null,
) {
    public val childCount: Int
        get() = rawChildNodeRefs.size

    public fun childRefAt(index: Int): Int = rawChildNodeRefs[index]

    public fun isChildFrame(ref: Int): Boolean = ref >= 0

    public fun childFrameIndex(ref: Int): Int {
        require(ref >= 0) { "child ref $ref is a leaf packet, not a composite frame" }
        return ref
    }

    public fun childPacketIndex(ref: Int): Int {
        require(ref < 0) { "child ref $ref is a composite frame, not a leaf packet" }
        return -(ref + 1)
    }
}

/**
 * Complete lossless 1D [SyntaxPacket] tape plus hierarchical [PacketFrame] syntax envelope
 * grounded in the ANTLR4 Kotlin specification grammar and universal `syn-kotlin` tokens.
 */
public class PacketStream(
    public val sourcePath: String,
    public val sourceText: String,
    public val packets: List<SyntaxPacket>,
    public val frames: List<PacketFrame>,
    public val rootFrameIndex: Int,
) {
    public val rootFrame: PacketFrame
        get() = frames[rootFrameIndex]

    /** Concatenates [SyntaxPacket.rawText] across all leaf packets; always equals [sourceText]. */
    public fun renderRawLossless(): String =
        buildString(sourceText.length) {
            for (packet in packets) {
                append(packet.rawText)
            }
        }

    /** Returns all non-whitespace, non-comment packets inside [frame]'s slice. */
    public fun significantPacketsIn(frame: PacketFrame): List<SyntaxPacket> {
        if (frame.firstPacketIndex > frame.lastPacketIndex || packets.isEmpty()) {
            return emptyList()
        }
        val out = mutableListOf<SyntaxPacket>()
        for (idx in frame.firstPacketIndex..frame.lastPacketIndex) {
            val packet = packets[idx]
            if (packet.isSignificant) {
                out.add(packet)
            }
        }
        return out
    }

    /** Returns all [SyntaxPacket.CommentPacket] entries in stream order. */
    public fun commentPackets(): List<SyntaxPacket.CommentPacket> {
        val out = mutableListOf<SyntaxPacket.CommentPacket>()
        for (packet in packets) {
            if (packet is SyntaxPacket.CommentPacket) {
                out.add(packet)
            }
        }
        return out
    }

    /** Materializes a balanced universal `proc-macro2-kotlin` [TokenStream] over significant packets. */
    internal fun toUniversalTokenStream(): TokenStream =
        UniversalSynTokenAssociation.buildTokenStream(this)
}
