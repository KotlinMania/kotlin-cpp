package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.kotlincpp.packet.buildKotlinPacketStream
import io.github.kotlinmania.kotlincpp.packet.emitKotlinFromPackets

/**
 * Parses [source] with the ANTLR4 Kotlin specification grammar into a universal
 * `syn-kotlin`-backed `PacketStream` and regenerates canonical Kotlin source via [emitKotlinFromPackets].
 */
public fun renderKotlinRoundTrip(source: String): SingleFileTranslation {
    val stream = buildKotlinPacketStream(source, sourcePath = "<roundtrip>")
    return emitKotlinFromPackets(stream)
}
