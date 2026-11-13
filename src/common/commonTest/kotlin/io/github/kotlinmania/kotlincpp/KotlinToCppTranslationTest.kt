package io.github.kotlinmania.kotlincpp

import io.github.kotlinmania.kotlincpp.kcpp.CppGcMode
import io.github.kotlinmania.kotlincpp.kcpp.CppTargetConfig
import io.github.kotlinmania.kotlincpp.kcpp.translateKotlinToCpp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinToCppTranslationTest {
    @Test
    fun translatesHeaderOnlySimpleEnumBufferOverflowPreservingKDocAndPortLint() {
        val source =
            """
            // port-lint: source kotlinx-coroutines-core/common/src/channels/BufferOverflow.kt
            package kotlinx.coroutines.channels

            /**
             * Buffer overflow strategy for [Channel]:
             * - [SUSPEND] suspends sender
             * - [DROP_OLDEST] drops oldest value
             */
            public enum class BufferOverflow {
                /** Suspend until space appears. */
                SUSPEND,

                /** Drop oldest item in buffer. */
                DROP_OLDEST,

                /** Drop latest item offered. */
                DROP_LATEST,
            }
            """.trimIndent() + "\n"

        val result = translateKotlinToCpp(source, sourcePath = "channels/BufferOverflow.kt")
        assertEquals("kotlinx/coroutines/channels/BufferOverflow.hpp", result.headerArtifact.relativePath)
        assertNull(result.sourceArtifact)

        val hpp = result.headerArtifact.content
        assertTrue(hpp.startsWith("#pragma once\n// port-lint: source kotlinx-coroutines-core/common/src/channels/BufferOverflow.kt\n"))
        assertTrue(hpp.contains("namespace kotlinx::coroutines::channels {"))
        assertTrue(hpp.contains("enum class BufferOverflow {"))
        assertTrue(hpp.contains("/** Suspend until space appears. */"))
        assertTrue(hpp.contains("SUSPEND,"))
        assertTrue(hpp.contains("DROP_OLDEST,"))
        assertTrue(hpp.contains("DROP_LATEST"))
        assertTrue(hpp.contains("} // namespace kotlinx::coroutines::channels"))
    }

    @Test
    fun translatesSymbolClassIntoCoLocatedHeaderAndCppSourceWithNamingConventions() {
        val source =
            """
            // port-lint: source internal/Symbol.kt
            package kotlinx.coroutines.internal

            /** Debugger-visible sentinel token. */
            public class Symbol(
                public val symbol: String,
                private val debugTag: Int = 0,
            ) {
                public fun labelText(): String {
                    // interior remark preserved
                    return "<" + symbol + ">"
                }

                public fun <T> unbox(value: Any?): T = value as T
            }
            """.trimIndent() + "\n"

        val result = translateKotlinToCpp(source, sourcePath = "internal/Symbol.kt")
        assertEquals("kotlinx/coroutines/internal/Symbol.hpp", result.headerArtifact.relativePath)
        val cppArtifact = assertNotNull(result.sourceArtifact)
        assertEquals("kotlinx/coroutines/internal/Symbol.cpp", cppArtifact.relativePath)

        val hpp = result.headerArtifact.content
        assertTrue(hpp.contains("class Symbol {"))
        assertTrue(hpp.contains("const std::int32_t debug_tag_;"))
        assertTrue(hpp.contains("const std::string symbol;"))
        assertTrue(hpp.contains("Symbol(std::string symbol_value, std::int32_t debug_tag_value = 0);"))
        assertTrue(hpp.contains("std::string label_text() const;"))
        assertTrue(hpp.contains("template <typename T>"))

        val cpp = cppArtifact.content
        assertTrue(cpp.contains("#include \"kotlinx/coroutines/internal/Symbol.hpp\""))
        assertTrue(cpp.contains("Symbol::Symbol(std::string symbol_value, std::int32_t debug_tag_value)"))
        assertTrue(cpp.contains("symbol(std::move(symbol_value))"))
        assertTrue(cpp.contains("debug_tag_(std::move(debug_tag_value))"))
        assertTrue(cpp.contains("std::string Symbol::label_text() const"))
        assertTrue(cpp.contains("// interior remark preserved"))
    }

    @Test
    fun translatesRichEnumWithPropertiesAndMethodsToCpp23EnumAndFreeHelpers() {
        val source =
            """
            // port-lint: source kotlinx-coroutines-core/common/src/CoroutineStart.kt
            package kotlinx.coroutines

            public enum class CoroutineStart(public val code: Int) {
                DEFAULT(0),
                LAZY(1),
                ATOMIC(2),
                UNDISPATCHED(3);

                public val isLazy: Boolean
                    get() = this == LAZY
            }
            """.trimIndent() + "\n"

        val result = translateKotlinToCpp(source, sourcePath = "CoroutineStart.kt")
        assertNull(result.sourceArtifact)
        val hpp = result.headerArtifact.content
        assertTrue(hpp.contains("enum class CoroutineStart {"))
        assertTrue(hpp.contains("inline constexpr std::int32_t code(CoroutineStart self)"))
        assertTrue(hpp.contains("std::unreachable();"))
        assertTrue(hpp.contains("inline bool is_lazy(CoroutineStart self)"))
    }

    @Test
    fun emitsDualAbiExplicitAndImplicitContinuationPairForLeafSuspendIntrinsic() {
        val source =
            """
            // port-lint: source kotlinx-coroutines-core/common/src/Yield.kt
            package kotlinx.coroutines

            import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

            public suspend fun yield(): Unit =
                suspendCoroutineUninterceptedOrReturn { uCont ->
                    COROUTINE_SUSPENDED
                }
            """.trimIndent() + "\n"

        val result = translateKotlinToCpp(source, sourcePath = "Yield.kt")
        val hpp = result.headerArtifact.content
        val cpp = assertNotNull(result.sourceArtifact).content

        assertTrue(hpp.contains("[[clang::annotate(\"suspend\")]]"))
        assertTrue(hpp.contains("void* yield(std::shared_ptr<Continuation<void*>> completion);"))
        assertTrue(hpp.contains("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"))
        assertTrue(
            hpp.contains(
                "__attribute__((error(\"yield() requires suspend lowering; use yield(completion) at an explicit ABI boundary\")))",
            ),
        )
        assertTrue(hpp.contains("void yield();"))

        assertTrue(cpp.contains("void* yield(std::shared_ptr<Continuation<void*>> completion)"))
        assertTrue(cpp.contains("return intrinsics::COROUTINE_SUSPENDED;"))
    }

    @Test
    fun hoistsLeftToRightArgsBeforeTailSuspendAndExpandsSuspendingControlFlowWithoutIife() {
        val source =
            """
            package kotlinx.coroutines.flow

            public fun readHeader(): Int = 1

            public suspend fun fetchChunk(tag: Int): Int = tag + 1

            public fun combinePair(left: Int, right: Int): Int = left + right

            public suspend fun pipelineStep(flag: Boolean): Int {
                val combined = combinePair(readHeader(), fetchChunk(10))
                val chosen =
                    if (flag) {
                        fetchChunk(combined)
                    } else {
                        0
                    }
                val bonus =
                    chosen.let { cur ->
                        fetchChunk(cur)
                    }
                return bonus
            }
            """.trimIndent() + "\n"

        val result = translateKotlinToCpp(source, sourcePath = "FlowPipeline.kt")
        assertEquals(1, result.hoistedSuspendArgCount)
        assertEquals(1, result.inlineExpandedCallCount)

        val hpp = result.headerArtifact.content
        assertTrue(hpp.contains("auto kxs_arg0 = read_header();"))
        assertTrue(hpp.contains("combine_pair(kxs_arg0, fetch_chunk(10))"))
        assertTrue(hpp.contains("std::optional<std::int32_t> kxs_cf0;"))
        assertFalse(hpp.contains("[&]() {\n    if (flag)"))
    }

    @Test
    fun enforcesFirSuspendCallCheckerRulesRejectingIllegalContexts() {
        assertFailsWith<IllegalArgumentException> {
            translateKotlinToCpp(
                """
                package demo
                public fun notSuspend() {
                    yield()
                }
                """.trimIndent(),
                sourcePath = "BadNonSuspend.kt",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            translateKotlinToCpp(
                """
                package demo
                public inline fun runCross(crossinline block: () -> Unit) {
                    block()
                }
                public suspend fun caller() {
                    runCross {
                        yield()
                    }
                }
                """.trimIndent(),
                sourcePath = "BadCrossinline.kt",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            translateKotlinToCpp(
                """
                package demo
                public suspend fun caller() {
                    val cb = { yield() }
                }
                """.trimIndent(),
                sourcePath = "BadOrdinaryLambda.kt",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            translateKotlinToCpp(
                """
                package demo
                public fun badSequence() {
                    sequence {
                        delay(50)
                    }
                }
                """.trimIndent(),
                sourcePath = "BadRestricted.kt",
            )
        }

        val okRestricted =
            translateKotlinToCpp(
                """
                package demo
                public fun validSequence() {
                    sequence {
                        yield(1)
                    }
                }
                """.trimIndent(),
                sourcePath = "OkRestricted.kt",
            )
        assertTrue(okRestricted.headerArtifact.content.contains("sequence"))
    }

    @Test
    fun lowersKonanGcBridgeStackRootsExternalRcSpillsDeadResetsAndBarriers() {
        val source =
            """
            package kotlinx.coroutines.gc

            public class Node(public var next: Node?)

            public fun makeNode(): Node = Node(null)

            @CName("kxs_foreign_entry")
            public fun foreignEntry(target: Node, child: Node) {
                val localOnly = makeNode()
                target.next = child
            }

            public suspend fun processAcrossSuspends(): Int {
                val liveAcrossFirst = makeNode()
                yield()
                val usedOnce = liveAcrossFirst
                yield()
                return 1
            }
            """.trimIndent() + "\n"

        val konanConfig = CppTargetConfig(gcMode = CppGcMode.KONAN_GC)
        val result = translateKotlinToCpp(source, sourcePath = "GcSample.kt", config = konanConfig)
        val hpp = result.headerArtifact.content
        val cpp = assertNotNull(result.sourceArtifact).content

        assertTrue(cpp.contains("Kotlin_initRuntimeIfNeeded();"))
        assertTrue(cpp.contains("CalledFromNativeGuard kxs_native_guard{};"))
        assertTrue(cpp.contains("ObjHolder local_only_holder(make_node());"))
        assertTrue(cpp.contains("Kotlin_mm_UpdateHeapRef(std::addressof(target.next), child)"))

        assertTrue(hpp.contains("OwningExternalRCRef live_across_first(make_node());"))
        assertTrue(hpp.contains("live_across_first.reset();"))

        assertFailsWith<IllegalArgumentException> {
            translateKotlinToCpp(
                """
                package demo
                public class LeakyResource {
                    public fun register() {
                        createCleaner(1) {
                            println(this)
                        }
                    }
                }
                """.trimIndent(),
                sourcePath = "LeakyCleaner.kt",
                config = konanConfig,
            )
        }
    }

    @Test
    fun lowersAtomicfuFieldsLvalueSafeCasLoopsAndThreadLocal() {
        val source =
            """
            package kotlinx.coroutines.sync

            import kotlinx.atomicfu.atomic
            import kotlinx.coroutines.internal.CommonThreadLocal

            public class CounterGate {
                private val state = atomic(0)
                private val currentRef = atomic<CounterGate?>(null)
                public val slot: CommonThreadLocal<CounterGate> = CommonThreadLocal()

                public fun tryAdvance(expectedVal: Int): Boolean {
                    state.lazySet(1)
                    val next = state.incrementAndGet()
                    return state.compareAndSet(expectedVal + 0, next)
                }

                public fun spinLoop() {
                    state.loop { cur ->
                        if (state.compareAndSet(cur, cur + 1)) return
                    }
                }

                public fun casRef(expectNode: CounterGate?, nextNode: CounterGate?): Boolean =
                    currentRef.compareAndSet(expectNode, nextNode)
            }
            """.trimIndent() + "\n"

        val standalone = translateKotlinToCpp(source, sourcePath = "CounterGate.kt")
        val hpp = standalone.headerArtifact.content
        val cpp = assertNotNull(standalone.sourceArtifact).content

        assertTrue(hpp.contains("mutable std::atomic<std::int32_t> state_{0};"))
        assertTrue(hpp.contains("static inline ThreadLocal<CounterGate> slot{};"))
        assertTrue(cpp.contains("state_.store(1, std::memory_order_release)"))
        assertTrue(cpp.contains("(state_.fetch_add(1, std::memory_order_seq_cst) + 1)"))
        assertTrue(cpp.contains("compare_exchange_strong(kxs_expected_0, next"))
        assertTrue(cpp.contains("while (true) {"))
        assertTrue(cpp.contains("auto cur = state_.load(std::memory_order_seq_cst);"))

        val konan = translateKotlinToCpp(source, sourcePath = "CounterGate.kt", config = CppTargetConfig(gcMode = CppGcMode.KONAN_GC))
        assertTrue(assertNotNull(konan.sourceArtifact).content.contains("Kotlin_mm_CompareAndSetVolatileHeapRef"))
    }

    @Test
    fun lowersInlineCppCInteropMemScopedUsePinnedAndCompanionCAbiAndRejectsPinnedAcrossSuspend() {
        val source =
            """
            @file:InlineCpp("#include <cstring>")
            package kotlinx.coroutines.ffi

            import kotlinx.cinterop.CPointer
            import kotlinx.cinterop.IntVar
            import kotlinx.cinterop.alloc
            import kotlinx.cinterop.memScoped
            import kotlinx.cinterop.pointed
            import kotlinx.cinterop.ptr
            import kotlinx.cinterop.usePinned

            @CName("kxs_copy_header")
            public fun copyHeader(buf: ByteArray, outPtr: CPointer<IntVar>): Int {
                memScoped {
                    val tempSlot = alloc<IntVar>()
                    val p = tempSlot.ptr
                    outPtr.pointed = p.pointed
                }
                buf.usePinned { pinned ->
                    val rawAddr = pinned.addressOf(0)
                    inlineCpp("std::memset(raw_addr, 0, 1);")
                }
                return 0
            }
            """.trimIndent() + "\n"

        val config = CppTargetConfig(emitCompanionCAbiFiles = true)
        val result = translateKotlinToCpp(source, sourcePath = "FfiBridge.kt", config = config)
        val hpp = result.headerArtifact.content
        val cpp = assertNotNull(result.sourceArtifact).content

        assertTrue(hpp.contains("#include <cstring>"))
        assertTrue(
            hpp.contains(
                "extern \"C\" std::int32_t kxs_copy_header(std::vector<std::uint8_t> buf, std::int32_t* out_ptr) noexcept;",
            ),
        )
        assertTrue(cpp.contains("std::int32_t temp_slot{};"))
        assertTrue(cpp.contains("std::addressof(temp_slot)"))
        assertTrue(cpp.contains("(*out_ptr) = (*p);"))
        assertTrue(cpp.contains("(kxs_pinned_0.data() + 0)"))
        assertTrue(cpp.contains("std::memset(raw_addr, 0, 1);"))

        assertEquals(2, result.companionArtifacts.size)
        assertEquals("FfiBridge_c_api.h", result.companionArtifacts[0].relativePath)
        assertTrue(result.companionArtifacts[0].content.contains("int32_t kxs_copy_header("))
        assertEquals("FfiBridge.def", result.companionArtifacts[1].relativePath)

        assertFailsWith<IllegalArgumentException> {
            translateKotlinToCpp(
                """
                package kotlinx.coroutines.ffi
                public suspend fun illegalPinAcrossSuspend(buf: ByteArray) {
                    buf.usePinned {
                        yield()
                    }
                }
                """.trimIndent(),
                sourcePath = "IllegalPin.kt",
            )
        }
    }

    @Test
    fun enforcesCpp23MinimumAndEmitsMultidimensionalSubscriptDataSealedAndForwardDecls() {
        assertFailsWith<IllegalArgumentException> {
            CppTargetConfig(cppStandard = 20)
        }

        val source =
            """
            package kotlinx.coroutines.grid

            public fun touchForward(node: MatrixCell): Int = node.row

            public sealed class GridEvent {
                public data class Updated(public val row: Int, public val col: Int) : GridEvent()
            }

            public class MatrixCell(public val row: Int) {
                public operator fun get(r: Int, c: Int): Int = r + c
            }

            public fun readMatrix(m: MatrixCell, ev: GridEvent): Int {
                if (ev is GridEvent.Updated) {
                    return m[ev.row, ev.col]
                }
                return 0
            }
            """.trimIndent() + "\n"

        val result = translateKotlinToCpp(source, sourcePath = "MatrixCell.kt")
        val hpp = result.headerArtifact.content
        val cpp = assertNotNull(result.sourceArtifact).content

        assertTrue(hpp.contains("class MatrixCell;\n"))
        assertTrue(hpp.contains("std::int32_t operator[](std::int32_t r, std::int32_t c) const;"))
        assertTrue(cpp.contains("m[(*ev_narrowed).row, (*ev_narrowed).col]"))
        assertTrue(hpp.contains("bool operator==(const Updated&) const = default;"))
        assertTrue(hpp.contains("Updated copy("))
    }

    @Test
    fun cliDefaultAndExplicitKcppCommandsWriteDeterministicArtifactsAndSupportDryRun() {
        val ktSource =
            """
            // port-lint: source internal/Symbol.kt
            package kotlinx.coroutines.internal

            public class FlagToken(public val id: Int) {
                public fun doubled(): Int = id * 2
            }
            """.trimIndent() + "\n"

        // Default subcommand-free CLI mode: `kotlin-cpp -o gen_cpp FlagToken.kt`
        val writtenFiles = LinkedHashMap<String, String>()
        val reports = ArrayList<String>()
        runSingleFileCommand(
            arrayOf("-o", "gen_cpp", "FlagToken.kt"),
            read = { path ->
                assertEquals("FlagToken.kt", path)
                ktSource
            },
            write = { path, text -> writtenFiles[path] = text },
            writeForce = { path, text -> writtenFiles[path] = text },
            exists = { it in writtenFiles },
            isDirectory = { false },
            report = reports::add,
        )

        assertEquals(
            setOf(
                "gen_cpp/kotlinx/coroutines/internal/FlagToken.hpp",
                "gen_cpp/kotlinx/coroutines/internal/FlagToken.cpp",
            ),
            writtenFiles.keys,
        )
        assertEquals(2, reports.size)

        // Dry-run writes zero files
        val dryRunReports = ArrayList<String>()
        runSingleFileCommand(
            arrayOf("kcpp", "--dry-run", "-o", "gen_cpp", "FlagToken.kt"),
            read = { ktSource },
            write = { _, _ -> error("dry-run must not write") },
            writeForce = { _, _ -> error("dry-run must not overwrite") },
            exists = { false },
            isDirectory = { false },
            report = dryRunReports::add,
        )
        assertEquals(2, dryRunReports.size)
        assertTrue(dryRunReports.all { it.startsWith("dry-run\t") })
    }

    @Test
    fun emitsSwitchTernaryDirectIdentityImplicitSuspendAndSynthesizedPortLint() {
        val symbolSource =
            """
            package kotlinx.coroutines.internal

            public class Symbol(public val symbol: String) {
                override fun toString(): String = "<" + symbol + ">"

                public fun <T> unbox(value: Any?): T = if (value === this) null as T else value as T
            }
            """.trimIndent() + "\n"

        val cfg = CppTargetConfig(sourceRootPrefix = "/repo/tmp/kotlinx.coroutines/")
        val symRes =
            translateKotlinToCpp(
                symbolSource,
                sourcePath = "/repo/tmp/kotlinx.coroutines/kotlinx-coroutines-core/common/src/internal/Symbol.kt",
                config = cfg,
            )
        val symHpp = symRes.headerArtifact.content
        assertTrue(symHpp.startsWith("#pragma once\n// port-lint: source kotlinx-coroutines-core/common/src/internal/Symbol.kt\n"))
        assertTrue(symHpp.contains("std::string to_string() const;"))
        assertFalse(symHpp.contains("override"))
        assertTrue(symHpp.contains("return (value == this) ? static_cast<T>(nullptr) : static_cast<T>(value);"))

        val startSource =
            """
            package kotlinx.coroutines

            public enum class CoroutineStart {
                DEFAULT,
                LAZY,
                ATOMIC,
                UNDISPATCHED;

                public fun dispatch(tag: Int): Unit =
                    when (this) {
                        DEFAULT -> runDefault(tag)
                        LAZY -> Unit
                        else -> runFallback(tag)
                    }

                public val isLazy: Boolean get() = this === LAZY
            }

            public suspend fun opaquePtr(flag: Boolean): Any? {
                yield()
                return if (flag) null else null
            }
            """.trimIndent() + "\n"

        val startRes = translateKotlinToCpp(startSource, sourcePath = "CoroutineStart.kt")
        val startHpp = startRes.headerArtifact.content
        assertTrue(startHpp.contains("switch (self) {"))
        assertTrue(startHpp.contains("case CoroutineStart::DEFAULT:"))
        assertTrue(startHpp.contains("case CoroutineStart::LAZY:"))
        assertFalse(startHpp.contains("Unit;"))
        assertTrue(startHpp.contains("return (self == CoroutineStart::LAZY);"))
        assertTrue(startHpp.contains("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_authored_suspend\")]]"))
    }

    @Test
    fun translatesCodeInKdocAndLineCommentsWithoutSyntheticDocBlocks() {
        val ktSource =
            """
            // port-lint: source kotlinx-coroutines-core/common/src/Yield.kt
            package kotlinx.coroutines

            /**
             * Suspends and performs [check for cancellation][ensureActive] via [CoroutineDispatcher.isDispatchNeeded].
             *
             * ```
             * fun updateProgressBar(stepCount: Int) {
             *     println(stepCount)
             * }
             * val maxRetries = 3
             * repeat(maxRetries) {
             *     updateProgressBar(it)
             * }
             * coroutineScope {
             *     delay(10L)
             * }
             * ```
             *
             * When `currentValue == null` or `withContext(NonCancellable) { ... }`, see [Job.join] and [Unit].
             * @param timeMillis delay duration
             */
            public fun ensureActive(timeMillis: Long): Unit {
                // Fast check when `timeMillis` exceeds `Long.MAX_VALUE`
                if (timeMillis <= 0L) return
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(ktSource, sourcePath = "Yield.kt")
        val hpp = res.headerArtifact.content
        val cpp = res.sourceArtifact!!.content

        // 1. Zero synthetic `/** Transliterated from: ... */` or `@file` doc blocks
        assertFalse(hpp.contains("/** Transliterated from:"))
        assertFalse(cpp.contains("/** Transliterated from:"))
        assertFalse(hpp.contains("@file"))

        // 2. Fenced Kotlin code block inside KDoc is translated to C++23 (` ```cpp `)
        assertTrue(hpp.contains("```cpp"))
        assertTrue(hpp.contains("auto update_progress_bar = [&](std::int32_t step_count) -> void"))
        assertTrue(hpp.contains("const auto max_retries = 3;"))
        assertTrue(hpp.contains("update_progress_bar("))
        assertFalse(hpp.contains("fun updateProgressBar"))
        assertFalse(hpp.contains("val maxRetries"))

        // 3. KDoc links, @param tags, and inline code spans in KDoc & // comments are translated
        assertTrue(hpp.contains("\\ref ensure_active \"check for cancellation\""))
        assertTrue(hpp.contains("\\ref CoroutineDispatcher::is_dispatch_needed"))
        assertTrue(hpp.contains("`current_value == nullptr`"))
        assertTrue(hpp.contains("`with_context(NonCancellable) { ... }`"))
        assertTrue(hpp.contains("coroutine_scope("))
        assertFalse(hpp.contains("kxs_implicit_continuation"))
        assertTrue(hpp.contains("\\ref Job::join"))
        assertTrue(hpp.contains("\\c void"))
        assertTrue(hpp.contains("@param time_millis delay duration"))
        assertTrue(cpp.contains("`time_millis`"))
        assertTrue(cpp.contains("`Long::MAX_VALUE`"))
    }

    @Test
    fun lowersInnerClassesAndCollectionExtensionReceivers() {
        val ktSource =
            """
            package kotlinx.coroutines

            import kotlinx.atomicfu.atomic

            public suspend fun <T> Collection<Deferred<T>>.awaitAll(): List<T> =
                if (isEmpty()) emptyList() else AwaitAll(toTypedArray()).await()

            public suspend fun Collection<Job>.joinAll(): Unit = forEach { it.join() }

            private class AwaitAll<T>(private val deferreds: Array<out Deferred<T>>) {
                private val notCompletedCount = atomic(deferreds.size)

                fun notifyDone() {}

                fun createNode(): AwaitAllNode = AwaitAllNode(0)

                private inner class DisposeHandlersOnCancel(private val nodes: Array<AwaitAllNode>) {
                    fun disposeAll() {
                        this@AwaitAll.notifyDone()
                    }
                }

                private inner class AwaitAllNode(val index: Int) {
                    fun step(): Int {
                        notifyDone()
                        return notCompletedCount.decrementAndGet() + deferreds.size
                    }
                }
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(ktSource, sourcePath = "Await.kt")
        val hpp = res.headerArtifact.content

        // 1. `Collection<E>` extension receiver alias and signature preservation for `ast_distance`
        assertTrue(hpp.contains("template <typename E>\nusing Collection = std::vector<E>;"))
        assertTrue(hpp.contains("const Collection<std::shared_ptr<Deferred<T>>>& self"))
        assertTrue(hpp.contains("const Collection<std::shared_ptr<Job>>& self"))
        assertTrue(hpp.contains("self.empty()"))
        assertTrue(hpp.contains("AwaitAll(self)"))
        assertTrue(hpp.contains("for (auto&& it : self)"))

        // 2. Sibling forward declaration + `inner class` outer pointer lowering
        assertTrue(hpp.contains("class AwaitAllNode;\n"))
        assertTrue(hpp.contains("AwaitAll* outer_;"))
        assertTrue(hpp.contains("DisposeHandlersOnCancel(AwaitAll* outer_value, std::vector<AwaitAllNode> nodes_value)"))
        assertTrue(hpp.contains("AwaitAllNode(AwaitAll* outer_value, std::int32_t index_value)"))
        assertTrue(hpp.contains(": outer_(outer_value), nodes_(std::move(nodes_value))"))
        assertTrue(hpp.contains("AwaitAllNode(this, 0)"))
        assertTrue(hpp.contains("outer_->notify_done()"))
        assertTrue(hpp.contains("outer_->not_completed_count_.fetch_sub(1, std::memory_order_seq_cst) - 1"))
        assertTrue(hpp.contains("outer_->deferreds_.size()"))
    }

    @Test
    fun translatesKdocInterpolatedClassFenceAnnotatedReturnAndStandaloneElvisBreak() {
        val ktSource =
            """
            package kotlinx.coroutines

            /**
             * Example of the implementation:
             *
             * ```
             * class CountingListFlow(private val values: List<Int>) {
             *     fun toDiagnosticString(): String = "Flow was collected ${"$"}{values.size} times"
             * }
             * ```
             */
            public class EventQueue<T> {
                public fun drainReady(state: Any?): T {
                    while (true) {
                        pollNext() ?: break
                    }
                    @Suppress("UNCHECKED_CAST")
                    return state as T
                }

                private fun pollNext(): Any? = null
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(ktSource, sourcePath = "EventQueue.kt")
        val hpp = res.headerArtifact.content

        assertTrue(hpp.contains("```cpp"))
        assertTrue(hpp.contains("class CountingListFlow"))
        assertTrue(hpp.contains("if (!(poll_next())) {"))
        assertTrue(hpp.contains("break;"))
        assertTrue(hpp.contains("return static_cast<T>(state);"))
    }
}
