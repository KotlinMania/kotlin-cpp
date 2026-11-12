package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket
import io.github.kotlinmania.kotlincpp.packet.buildKotlinPacketStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KotlinRoundTripTest {
    @Test
    fun kotlinDeclarationsOperatorsMultilineStringsAndLabelsRoundTripLikeForLike() {
        val source =
            """
            @file:Suppress("NOTHING_TO_INLINE")

            package kotlinx.coroutines.selects

            import kotlinx.coroutines.Job

            public typealias SelectClauseId = Long

            @JvmInline
            public value class ClauseToken(public val rawId: SelectClauseId)

            public interface Selectable<out R> {
                public fun pollValue(): R?
            }

            public class DefaultSelectable<T : Any>(
                private val fallback: T,
            ) : Selectable<T> where T : Comparable<T> {
                public var attempts: Int = 0
                    private set

                public override fun pollValue(): T? {
                    attempts += 1
                    val labelCheck = listOf(1, 2, 3)
                    outer@ for (item in labelCheck) {
                        if (item == 2) continue@outer
                        if (item > 2) break@outer
                    }
                    val banner =
                        ${"\"\"\""}
                        raw multiline banner: ${'$'}attempts
                        ${"\"\"\""}.trimIndent()
                    return if (banner.isNotEmpty()) fallback else null
                }
            }
            """.trimIndent() + "\n"

        val stream = buildKotlinPacketStream(source, sourcePath = "Selectable.kt")
        assertEquals(source, stream.renderRawLossless())

        val roundTripped = renderKotlinRoundTrip(source)
        assertEquals(source, roundTripped.text)
        assertEquals("4 items round-tripped", roundTripped.summary)

        val operators =
            stream.packets
                .filterIsInstance<SyntaxPacket.OperatorPacket>()
                .associate { it.rawText to it.universalSynToken }
        assertEquals("Token![+=]", operators["+="])
        assertEquals("Token![==]", operators["=="])
        assertEquals("Token![>]", operators[">"])
    }

    @Test
    fun roundTripCliKeepsRegeneratedFileAndFailsOnUnformattedInput() {
        val unformatted = "package demo\npublic fun f(): Int { val a = 1; return a }\n"
        val written = LinkedHashMap<String, String>()

        assertFailsWith<IllegalStateException> {
            runSingleFileCommand(
                args = arrayOf("roundtrip", "Unformatted.kt", "Unformatted.regen.kt"),
                read = { unformatted },
                write = { path, text -> written[path] = text },
                report = {},
            )
        }
        assertEquals(
            "package demo\npublic fun f(): Int {\n    val a = 1\n    return a\n}\n",
            written["Unformatted.regen.kt"],
        )
    }
}
