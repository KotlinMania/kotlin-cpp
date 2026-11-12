package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustkotlin.packet.buildKotlinPacketStream
import io.github.kotlinmania.rustkotlin.packet.emitKotlinFromPackets

/**
 * Regenerates Kotlin through the lossless [io.github.kotlinmania.rustkotlin.packet.PacketStream]
 * protocol (`KotlinLexer` + `KotlinParser` + `LightSyntaxTree` + `KDocParser` -> `KotlinPacketEmitter`).
 *
 * Single deterministic code path with zero fallbacks: syntax errors fail immediately,
 * comments/KDoc/string templates are preserved as first-class packets, and non-canonical
 * single-line function blocks or missing trailing newlines are normalized canonically.
 */
public fun renderKotlinRoundTrip(source: String): SingleFileTranslation {
    val stream = buildKotlinPacketStream(source)
    return emitKotlinFromPackets(stream)
}
