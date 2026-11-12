package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.packet.PacketFlags
import io.github.kotlinmania.rustkotlin.packet.SyntaxPacket
import io.github.kotlinmania.rustkotlin.packet.buildKotlinPacketStream
import io.github.kotlinmania.syn.kotlinKeywordRustToken
import io.github.kotlinmania.syn.parseKotlinTokenStream

internal fun inspectKotlinPackets(
    inputs: List<String>,
    read: (String) -> String,
    isDirectory: (String) -> Boolean,
    report: (String) -> Unit,
) {
    require(inputs.isNotEmpty()) { "usage: kotlin-cpp packets <input.kt | source directory...>" }
    require(inputs.none { it.startsWith("-") }) { "packets accepts Kotlin files and source directories, not options" }
    val files =
        inputs
            .flatMap { input ->
                if (isDirectory(input)) {
                    collectKotlinFilePaths(listOf(input))
                } else {
                    require(input.endsWith(".kt", ignoreCase = true)) { "not a Kotlin source file: $input" }
                    listOf(input)
                }
            }.distinct()
            .sorted()
    require(files.isNotEmpty()) { "no Kotlin source files found" }
    for (path in files) {
        val stream = buildKotlinPacketStream(read(path), sourcePath = path)
        var whitespaceCount = 0
        var commentCount = 0
        var kdocLinkCount = 0
        var significantCount = 0
        for (pkt in stream.packets) {
            when (pkt) {
                is SyntaxPacket.WhitespacePacket -> whitespaceCount += 1
                is SyntaxPacket.CommentPacket -> {
                    commentCount += 1
                    kdocLinkCount += pkt.kdocLinks.size
                }
                else -> if (pkt.isSignificant) significantCount += 1
            }
        }
        val suspendFrames =
            stream.frames.count { frame ->
                PacketFlags.hasFlag(frame.flags, PacketFlags.MOD_SUSPEND) &&
                    frame.semanticInfo?.symbolName != null
            }
        report(
            "packets\t$path\ttotal=${stream.packets.size}\tsignificant=$significantCount" +
                "\twhitespace=$whitespaceCount\tcomments=$commentCount\tkdocLinks=$kdocLinkCount" +
                "\tframes=${stream.frames.size}\tsuspendDecls=$suspendFrames",
        )
    }
}

internal fun inspectKotlinTokens(
    inputs: List<String>,
    read: (String) -> String,
    isDirectory: (String) -> Boolean,
    report: (String) -> Unit,
) {
    require(inputs.isNotEmpty()) { "usage: kotlin-cpp tokens <input.kt | source directory...>" }
    require(inputs.none { it.startsWith("-") }) { "tokens accepts Kotlin files and source directories, not options" }
    val files = inputs.flatMap { input ->
        if (isDirectory(input)) {
            collectKotlinFilePaths(listOf(input))
        } else {
            require(input.endsWith(".kt", ignoreCase = true)) { "not a Kotlin source file: $input" }
            listOf(input)
        }
    }.distinct().sorted()
    require(files.isNotEmpty()) { "no Kotlin source files found" }
    for (path in files) {
        val result = parseKotlinTokenStream(read(path))
        val stream = result.getOrNull() ?: error("$path: ${result.exceptionOrNull()}")
        var leaves = 0
        var groups = 0
        val associations = linkedMapOf<Pair<String, String>, Int>()
        fun visit(tokens: TokenStream) {
            for (token in tokens) {
                if (token is TokenTree.Group) {
                    groups++
                    visit(token.value.stream())
                } else {
                    leaves++
                    val shared = kotlinKeywordRustToken(token) ?: continue
                    val key = token.toString() to shared.toString()
                    associations[key] = (associations[key] ?: 0) + 1
                }
            }
        }
        visit(stream)
        report("tokens\t$path\tleaves=$leaves\tgroups=$groups")
        for ((key, count) in associations.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            report("association\t${key.first}\t${key.second}\t$count")
        }
    }
}
