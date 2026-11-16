package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.kotlincpp.packet.SyntaxPacket
import io.github.kotlinmania.kotlincpp.packet.buildKotlinPacketStream
import io.github.kotlinmania.kotlincpp.packet.emitKotlinFromPackets
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

    @Test
    fun coroutinesInnerClassesExtensionsIntrinsicsAndKdocRoundTripFromPacketsOnly() {
        val source =
            """
            // port-lint: source kotlinx-coroutines-core/common/src/Await.kt
            package kotlinx.coroutines

            import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

            /**
             * Awaits completion of given [Deferred] values without blocking a thread.
             *
             * ```kotlin
             * val results = listOf(firstDeferred, secondDeferred).awaitAll()
             * ```
             *
             * @param deferreds vararg array of [Deferred] instances.
             */
            public suspend fun <T> awaitAll(vararg deferreds: Deferred<T>): List<T> =
                if (deferreds.isEmpty()) emptyList() else AwaitAll(deferreds).await()

            public suspend fun <T> Collection<Deferred<T>>.awaitAll(): List<T> =
                if (isEmpty()) emptyList() else AwaitAll(toTypedArray()).await()

            public suspend fun yield(): Unit = suspendCoroutineUninterceptedOrReturn sc@{ uCont ->
                val context = uCont.context
                val cont = uCont.intercepted() as? DispatchedContinuation<Unit> ?: return@sc Unit
                if (context === EmptyCoroutineContext && cont.isActive) {
                    return@sc Unit
                }
                COROUTINE_SUSPENDED
            }

            private class AwaitAll<T>(private val deferreds: Array<out Deferred<T>>) {
                private val notCompletedCount = atomic(deferreds.size)

                suspend fun await(): List<T> = suspendCancellableCoroutine { cont ->
                    val nodes = Array(deferreds.size) { i ->
                        val deferred = deferreds[i]
                        deferred.start()
                        AwaitAllNode(cont).apply {
                            handle = deferred.invokeOnCompletion(this)
                        }
                    }
                    val disposer = DisposeHandlersOnCancel(nodes)
                    nodes.forEach { it.disposer = disposer }
                }

                private inner class DisposeHandlersOnCancel(private val nodes: Array<AwaitAll<T>.AwaitAllNode>) {
                    fun disposeAll() {
                        val total = this@AwaitAll.deferreds.size
                        if (total > 0) {
                            nodes.forEach { it.disposeHandle() }
                        }
                    }

                    fun invoke(cause: Throwable?) { disposeAll() }
                }

                private inner class AwaitAllNode(private val continuation: CancellableContinuation<List<T>>) {
                    var handle: DisposableHandle? = null
                    var disposer: DisposeHandlersOnCancel? = null

                    fun disposeHandle() {
                        handle?.dispose()
                    }
                }
            }
            """.trimIndent() + "\n"

        val stream = buildKotlinPacketStream(source, sourcePath = "Await.kt")
        assertEquals(source, stream.renderRawLossless())

        val emitted = emitKotlinFromPackets(stream)
        assertEquals(source, emitted.text)

        val keywords =
            stream.packets
                .filterIsInstance<SyntaxPacket.KeywordPacket>()
                .associate { it.rawText to it.universalSynToken }
        assertEquals("Token![async]", keywords["suspend"])
        assertEquals("Token![fn]", keywords["fun"])
        assertEquals("Token![let]", keywords["val"])

        val operators =
            stream.packets
                .filterIsInstance<SyntaxPacket.OperatorPacket>()
                .associate { it.rawText to it.universalSynToken }
        assertEquals("Token![as]", operators["as?"])
    }
}
