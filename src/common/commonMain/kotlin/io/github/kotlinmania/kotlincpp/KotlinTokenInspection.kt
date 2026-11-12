package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.kotlincpp.packet.KtNodeTypes
import io.github.kotlinmania.kotlincpp.packet.PacketFlags
import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket
import io.github.kotlinmania.kotlincpp.packet.buildKotlinPacketStream
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree

public data class KotlinTokenAssociationEntry(
    public val kotlinSpelling: String,
    public val universalSynToken: String,
    public val count: Int,
)

public data class KotlinTokenSummary(
    public val packetCount: Int,
    public val leafTokenCount: Int,
    public val groupTokenCount: Int,
    public val associations: List<KotlinTokenAssociationEntry>,
)

public fun summarizeKotlinTokens(
    source: String,
    sourcePath: String = "<memory>",
): KotlinTokenSummary {
    val stream = buildKotlinPacketStream(source, sourcePath = sourcePath)
    val universalStream = stream.toUniversalTokenStream()
    var leafTokens = 0
    var groupTokens = 0

    fun countStream(ts: TokenStream) {
        for (tree in ts) {
            when (tree) {
                is TokenTree.Group -> {
                    groupTokens += 1
                    countStream(tree.group.stream())
                }
                is TokenTree.Ident,
                is TokenTree.Literal,
                is TokenTree.Punct,
                -> leafTokens += 1
            }
        }
    }
    countStream(universalStream)

    val counts = LinkedHashMap<Pair<String, String>, Int>()
    for (pkt in stream.packets) {
        if (pkt is SyntaxPacket.KeywordPacket) {
            val synName = pkt.universalSynToken
            if (synName != null && pkt.synToken != null) {
                val key = pkt.rawText to synName
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
    }

    val associations =
        counts.entries.map { (key, count) ->
            KotlinTokenAssociationEntry(
                kotlinSpelling = key.first,
                universalSynToken = key.second,
                count = count,
            )
        }

    return KotlinTokenSummary(
        packetCount = stream.packets.size,
        leafTokenCount = leafTokens,
        groupTokenCount = groupTokens,
        associations = associations,
    )
}

public fun inspectKotlinTokens(
    source: String,
    sourcePath: String = "<memory>",
): List<String> {
    val summary = summarizeKotlinTokens(source, sourcePath = sourcePath)
    val lines = ArrayList<String>(1 + summary.associations.size)
    lines.add(
        "tokens\t$sourcePath\tleaves=${summary.leafTokenCount}\tgroups=${summary.groupTokenCount}\tpackets=${summary.packetCount}",
    )
    for (assoc in summary.associations) {
        lines.add("association\t${assoc.kotlinSpelling}\t${assoc.universalSynToken}\t${assoc.count}")
    }
    return lines
}

public fun inspectKotlinPackets(
    source: String,
    sourcePath: String = "<memory>",
): String {
    val stream = buildKotlinPacketStream(source, sourcePath = sourcePath)
    val comments = stream.commentPackets()
    val kdocLinks = comments.sumOf { it.kdocLinks.size }
    val suspendDecls =
        stream.frames.count { frame ->
            (frame.nodeTypeId == KtNodeTypes.FUN_ID || frame.nodeTypeId == KtNodeTypes.PROPERTY_ID) &&
                PacketFlags.hasFlag(frame.flags, PacketFlags.MOD_SUSPEND)
        }
    return "packets\t$sourcePath\tpackets=${stream.packets.size}\tframes=${stream.frames.size}\t" +
        "comments=${comments.size}\tkdocLinks=$kdocLinks\tsuspendDecls=$suspendDecls"
}
