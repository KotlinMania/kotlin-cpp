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

        assertTrue(hpp.contains("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"))
        assertTrue(hpp.contains("void yield();"))
        assertTrue(hpp.contains("[[clang::annotate(\"suspend\")]]"))
        assertTrue(hpp.contains("void* yield(std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion);"))

        assertTrue(cpp.contains("[[clang::annotate(\"suspend\")]]"))
        assertTrue(cpp.contains("void* yield([[maybe_unused]] std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion)"))
        assertTrue(cpp.contains("return COROUTINE_SUSPENDED;"))
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

        val cpp = assertNotNull(result.sourceArtifact).content
        assertTrue(cpp.contains("auto kxs_arg0 = read_header();"))
        assertTrue(cpp.contains("combine_pair(kxs_arg0, fetch_chunk(10))"))
        assertTrue(cpp.contains("std::optional<std::int32_t> kxs_cf0;"))
        assertFalse(cpp.contains("[&]() {\n    if (flag)"))
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
        val cpp = assertNotNull(result.sourceArtifact).content

        assertTrue(cpp.contains("Kotlin_initRuntimeIfNeeded();"))
        assertTrue(cpp.contains("CalledFromNativeGuard kxs_native_guard{};"))
        assertTrue(cpp.contains("ObjHolder local_only_holder(make_node());"))
        assertTrue(cpp.contains("Kotlin_mm_UpdateHeapRef(std::addressof(target.next), child)"))

        assertTrue(cpp.contains("OwningExternalRCRef live_across_first(make_node());"))
        assertTrue(cpp.contains("live_across_first.reset();"))

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

        assertTrue(hpp.contains("mutable KxsCopyableAtomic<std::int32_t> state_{0};"))
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
        assertTrue(symHpp.contains("virtual std::string to_string() const;"))
        assertFalse(symHpp.contains("override"))
        assertTrue(symHpp.contains("std::any_cast<decltype(this)>(std::addressof(value)) != nullptr"))
        assertTrue(symHpp.contains("? T{} : ((!value.has_value() || value.type() == typeid(std::nullptr_t)) ? T{} : std::any_cast<T>(value));"))

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
        assertTrue(startHpp.contains("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")"))
        assertTrue(startHpp.contains("void* opaque_ptr(bool flag, std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion);"))
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

        // 1. Zero synthetic `Transliterated from:` or `@file` comments anywhere in `.hpp` or `.cpp`
        assertFalse(hpp.contains("Transliterated from:"))
        assertFalse(cpp.contains("Transliterated from:"))
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
        val cpp = assertNotNull(res.sourceArtifact).content

        // 1. `Collection<E>` extension receiver alias and signature preservation for `ast_distance`
        assertTrue(hpp.contains("template <typename E>\nusing Collection = std::vector<E>;"))
        assertTrue(hpp.contains("const Collection<std::shared_ptr<Deferred<T>>>& self"))
        assertTrue(hpp.contains("const Collection<std::shared_ptr<Job>>& self"))
        assertTrue(hpp.contains("self.empty()"))
        assertTrue(hpp.contains("AwaitAll(self)"))
        assertTrue(cpp.contains("for (auto&& it : self)"))

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
        assertTrue(hpp.contains("if (kxs_is_null(poll_next())) {"))
        assertTrue(hpp.contains("break;"))
        assertTrue(hpp.contains("return ((!state.has_value() || state.type() == typeid(std::nullptr_t)) ? T{} : std::any_cast<T>(state));"))
    }

    @Test
    fun lowersLabeledLeafIntrinsicsSuspendFunctionTypesCatchRethrowObjectLiteralsAndMultiplatformStems() {
        val yieldSource =
            """
            package kotlinx.coroutines

            import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

            public suspend fun yield(): Unit =
                suspendCoroutineUninterceptedOrReturn sc@ { uCont ->
                    if (uCont == null) return@sc Unit
                    COROUTINE_SUSPENDED
                }
            """.trimIndent() + "\n"
        val yieldRes = translateKotlinToCpp(yieldSource, sourcePath = "Yield.kt")
        val yieldCpp = assertNotNull(yieldRes.sourceArtifact).content
        assertTrue(yieldCpp.contains("return nullptr;"))
        assertTrue(yieldCpp.contains("return COROUTINE_SUSPENDED;"))
        assertFalse(yieldCpp.contains("Transliterated from:"))

        val cehSource =
            """
            package kotlinx.coroutines

            import kotlin.coroutines.*

            public fun reportFailure(t: Throwable) {}

            public fun guardBlock(action: () -> Unit) {
                try {
                    action.invoke()
                } catch (t: Throwable) {
                    reportFailure(t)
                    throw t
                }
            }

            public inline fun CoroutineExceptionHandler(
                crossinline handler: (CoroutineContext, Throwable) -> Unit
            ): CoroutineExceptionHandler =
                object : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
                    override fun handleException(context: CoroutineContext, exception: Throwable) =
                        handler.invoke(context, exception)
                }

            public interface CoroutineExceptionHandler : CoroutineContext.Element {
                public companion object Key : CoroutineContext.Key<CoroutineExceptionHandler>
                public fun handleException(context: CoroutineContext, exception: Throwable)
            }
            """.trimIndent() + "\n"
        val cehRes = translateKotlinToCpp(cehSource, sourcePath = "CoroutineExceptionHandler.kt")
        val cehHpp = cehRes.headerArtifact.content
        val cehCpp = assertNotNull(cehRes.sourceArtifact).content
        assertTrue(cehCpp.contains("const std::exception_ptr t = std::current_exception();"))
        assertTrue(cehCpp.contains("std::rethrow_exception(t);"))
        assertTrue(cehHpp.contains("class Key : public CoroutineContext::KeyTyped<CoroutineExceptionHandler>"))
        assertTrue(cehHpp.contains("CoroutineExceptionHandler::Key::instance()"))
        assertTrue(cehHpp.indexOf("class CoroutineExceptionHandler :") < cehHpp.indexOf("struct KxsAnonObject_"))

        val buildersSource =
            """
            package kotlinx.coroutines

            import kotlin.coroutines.*

            public fun CoroutineScope.launch(
                context: CoroutineContext = EmptyCoroutineContext,
                start: CoroutineStart = CoroutineStart.DEFAULT,
                block: suspend CoroutineScope.() -> Unit
            ): Job = makeJob(context)

            private class JobImpl(parent: Job?) {
                init {
                    attachParent(parent)
                }
            }
            """.trimIndent() + "\n"
        val buildersRes = translateKotlinToCpp(buildersSource, sourcePath = "Builders.common.kt")
        assertEquals("kotlinx/coroutines/Builders.common.hpp", buildersRes.headerArtifact.relativePath)
        val bHpp = buildersRes.headerArtifact.content
        val bCpp = assertNotNull(buildersRes.sourceArtifact).content
        assertTrue(bHpp.contains("SuspendFunction<"))
        assertTrue(bHpp.contains("inline std::shared_ptr<Job> launch(const std::shared_ptr<CoroutineScope>& self, SuspendFunction<"))
        assertTrue(bCpp.contains("attach_parent(parent_value);"))

        val tlSource =
            """
            package kotlinx.coroutines.internal

            import kotlin.native.concurrent.ThreadLocal

            internal actual class CommonThreadLocal<T>(private val name: Symbol) {
                actual fun get(): T = Storage[name] as T
                actual fun set(value: T) { Storage[name] = value }
            }

            @ThreadLocal
            private object Storage: MutableMap<Symbol, Any?> by mutableMapOf()
            """.trimIndent() + "\n"
        val tlRes = translateKotlinToCpp(tlSource, sourcePath = "internal/ThreadLocal.kt")
        val tlHpp = tlRes.headerArtifact.content
        assertTrue(tlHpp.contains("thread_local Storage inst{};"))
        assertTrue(tlHpp.contains("Storage::instance()[name_]"))
        assertTrue(tlHpp.indexOf("class Storage") < tlHpp.indexOf("T CommonThreadLocal<T>::get()"))
        assertFalse(tlHpp.contains("Transliterated from:"))
    }

    @Test
    fun translatesSameLineReturnElvisControlTransferLocalDeclsAndMultiplatformSuffixes() {
        val selectLikeSource =
            """
            package kotlinx.coroutines.selects

            internal class SelectState {
                private var internalResult: Any? = null

                fun trySelectInternal(ok: Boolean): Int {
                    if (ok) return TRY_SELECT_SUCCESSFUL
                    this.internalResult = NO_RESULT
                    return 0
                }

                suspend fun invokeClause(block: Any?, fallback: Int?): Int {
                    block as suspend () -> Int
                    val chosen = block()
                    return fallback ?: return chosen
                }

                fun drainLoop(items: List<Int?>): Int {
                    var sum = 0
                    var cur: Int = 0
                    for (item in items) {
                        cur = item ?: continue
                        sum += cur
                    }
                    return sum
                }
            }

            private const val TRY_SELECT_SUCCESSFUL: Int = 1
            private val NO_RESULT: Any = Any()
            """.trimIndent() + "\n"

        assertEquals(selectLikeSource, renderKotlinRoundTrip(selectLikeSource).text)
        val selRes =
            translateKotlinToCpp(
                selectLikeSource,
                sourcePath = "selects/Select.kt",
                config = CppTargetConfig(fileStemSuffix = ".native"),
            )
        assertEquals("kotlinx/coroutines/selects/Select.native.hpp", selRes.headerArtifact.relativePath)
        val selCpp = assertNotNull(selRes.sourceArtifact).content
        assertTrue(selCpp.contains("return kxs_priv_select_native_TRY_SELECT_SUCCESSFUL;"))
        assertTrue(selCpp.contains("this->internal_result_ = kxs_priv_select_native_NO_RESULT;"))
        assertTrue(selCpp.contains("continue;"))
        assertFalse(selCpp.contains("Transliterated from:"))

        val preservedCommonRes =
            translateKotlinToCpp(
                selectLikeSource,
                sourcePath = "selects/Select.common.kt",
                config = CppTargetConfig(fileStemSuffix = ".native"),
            )
        assertEquals("kotlinx/coroutines/selects/Select.common.hpp", preservedCommonRes.headerArtifact.relativePath)

        val localDeclsAndCrossinlineSource =
            """
            package kotlinx.coroutines

            import kotlin.concurrent.AtomicReference

            public interface DisposableHandle {
                public fun dispose()
            }

            public class Worker {
                public fun executeAfter(delayUs: Long, action: () -> Unit) {
                    action()
                }
            }

            public fun scheduleOnWorker(worker: Worker, delayUs: Long): DisposableHandle {
                class DisposableBlock(initial: Int) : DisposableHandle {
                    private val holder = AtomicReference<Int?>(initial)
                    override fun dispose() {
                        holder.value = null
                    }
                    fun isDisposed(): Boolean = holder.value == null
                }

                fun Worker.runAfterDelay(block: DisposableBlock, remaining: Long) {
                    if (block.isDisposed()) return
                    if (remaining > 100L) {
                        executeAfter(100L) { runAfterDelay(block, remaining - 100L) }
                    }
                }

                val handle = DisposableBlock(1)
                worker.runAfterDelay(handle, delayUs)
                return handle
            }

            public inline fun <T, R> transformSuspend(
                value: T,
                crossinline transform: suspend (T) -> R
            ): suspend () -> R = {
                transform(value)
            }
            """.trimIndent() + "\n"

        val localRes = translateKotlinToCpp(localDeclsAndCrossinlineSource, sourcePath = "MultithreadedDispatchers.kt")
        val localCpp = assertNotNull(localRes.sourceArtifact).content
        assertTrue(localCpp.contains("struct DisposableBlock : public DisposableHandle"))
        assertTrue(localCpp.contains("this auto&& kxs_fn_self"))
        assertTrue(localCpp.contains("std::make_shared<DisposableBlock>(1)"))

        val badCrossinlineNonSuspend =
            """
            package kotlinx.coroutines

            public suspend fun step(): Int = 1

            public inline fun runCrossinline(crossinline block: () -> Int): () -> Int = { block() }

            public suspend fun caller(): () -> Int = runCrossinline { step() }
            """.trimIndent() + "\n"
        assertFailsWith<IllegalArgumentException> {
            translateKotlinToCpp(badCrossinlineNonSuspend, sourcePath = "BadCrossinline.kt")
        }
    }

    @Test
    fun supportsSourceSetSubdirNoreturnContractsAtomicAnyAndInterfaceProperties() {
        val mutexLikeSource =
            """
            package kotlinx.coroutines.sync

            import kotlinx.atomicfu.atomic
            import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
            import kotlin.contracts.ExperimentalContracts
            import kotlin.contracts.InvocationKind
            import kotlin.contracts.contract

            public interface Mutex {
                public val isLocked: Boolean
                public suspend fun lock(owner: Any? = null)
                public fun unlock(owner: Any? = null)
            }

            @OptIn(ExperimentalContracts::class)
            public suspend inline fun <T> Mutex.withLock(owner: Any? = null, action: () -> T): T {
                contract {
                    callsInPlace(action, InvocationKind.EXACTLY_ONCE)
                }
                lock(owner)
                return try {
                    action()
                } finally {
                    unlock(owner)
                }
            }

            public class Symbol(public val symbol: String)
            private val NO_OWNER = Symbol("NO_OWNER")

            internal open class MutexImpl(locked: Boolean) : Mutex {
                private val owner = atomic<Any?>(if (locked) null else NO_OWNER)
                override val isLocked: Boolean get() = false
                override suspend fun lock(owner: Any?) {}
                override fun unlock(owner: Any?) {
                    // todo: verify owner invariant
                }
            }

            public suspend fun awaitForever(): Nothing {
                throw IllegalStateException("unreachable")
            }

            public fun buildSuspendBlock(): Int {
                val block = suspend {
                    awaitForever()
                }
                return 0
            }
            """.trimIndent() + "\n"

        val res =
            translateKotlinToCpp(
                mutexLikeSource,
                sourcePath = "sync/Mutex.kt",
                config = CppTargetConfig(sourceSetSubdir = "concurrent"),
            )
        assertEquals("kotlinx/coroutines/concurrent/sync/Mutex.hpp", res.headerArtifact.relativePath)
        val hpp = res.headerArtifact.content
        val cpp = assertNotNull(res.sourceArtifact).content

        assertTrue(hpp.contains("namespace kotlinx::coroutines::sync {"))
        assertTrue(hpp.contains("#include \"kotlinx/coroutines/channels/Channel.hpp\""))
        assertFalse(hpp.contains("channel/Factory.hpp"))
        assertFalse(hpp.contains("kotlin/contracts"))
        assertTrue(hpp.contains("virtual bool is_locked() const = 0;"))
        assertTrue(hpp.contains("bool is_locked() const override"))
        assertTrue(hpp.contains("((void)0);"))
        assertTrue(hpp.contains("self->lock(owner);"))
        assertTrue(hpp.contains("self->unlock(owner);"))
        assertTrue(hpp.contains("mutable KxsAtomicReference<std::any> owner_"))
        assertTrue(hpp.contains("[[noreturn]] void await_forever();"))
        assertTrue(cpp.contains("void* await_forever([[maybe_unused]] std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion)"))
        assertFalse(cpp.contains("todo:"))
        assertTrue(cpp.contains("Note: verify owner invariant"))
    }

    @Test
    fun emitsTopologicalSameFileClassesSuperCallsAuthoredSuspendAndCastContextHelpers() {
        val topoAndAuthoredSource =
            """
            package kotlinx.coroutines

            import kotlin.coroutines.CoroutineContext
            import kotlin.time.Duration
            import kotlin.time.Duration.Companion.nanoseconds

            public class DerivedTask : BaseTask() {
                override fun describe(): String = super.describe() + ":derived"
            }

            public open class BaseTask {
                public open fun describe(): String = "base"
            }

            public suspend fun pauseBriefly(tag: String, unusedFlag: Int): Unit {
                delay(10L)
            }

            public suspend fun waitForever(): Nothing {
                suspendCancellableCoroutine<Nothing> {}
            }

            internal val CoroutineContext.resolvedDelay: Delay
                get() = get(ContinuationInterceptor) as? Delay ?: DefaultDelay

            internal fun Duration.toDelayMillisHelper(): Long =
                when (isPositive()) {
                    true -> plus(999_999L.nanoseconds).inWholeMilliseconds
                    false -> 0L
                }

            internal fun inspectContextAndCast(
                ctx: CoroutineContext,
                candidate: Any?,
                fallback: Delay,
            ): Delay {
                val job = ctx[Job]
                val ok = candidate is Job
                val checked = candidate as? Delay
                val forced = fallback as Delay
                return if (ok && job != null) checked ?: forced else forced
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(topoAndAuthoredSource, sourcePath = "TopoAndAuthored.kt")
        val hpp = res.headerArtifact.content
        val cpp = assertNotNull(res.sourceArtifact).content

        assertTrue(hpp.indexOf("class BaseTask") < hpp.indexOf("class DerivedTask"))
        assertTrue(cpp.contains("BaseTask::describe()"))
        assertTrue(hpp.contains("[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\")]]"))
        assertTrue(hpp.contains("void pause_briefly(std::string tag, std::int32_t unused_flag);"))
        assertTrue(
            hpp.contains(
                "void* pause_briefly(std::string tag, std::int32_t unused_flag, " +
                    "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion);",
            ),
        )
        assertTrue(cpp.contains("[[clang::annotate(\"suspend\")]]"))
        assertTrue(cpp.contains("[[maybe_unused]] std::int32_t unused_flag"))
        assertTrue(cpp.contains("return nullptr;"))
        assertTrue(cpp.contains("kotlin::coroutines::kxs_ctx_get(self, &ContinuationInterceptor::key_instance)"))
        assertTrue(cpp.contains("self.is_positive()"))
        assertTrue(cpp.contains("(self + kotlin::time::nanoseconds(static_cast<long long>(999999LL))).in_whole_milliseconds()"))
        assertTrue(cpp.contains("kotlin::coroutines::kxs_ctx_get(ctx, Job::Key::instance())"))
        assertTrue(cpp.contains("kxs_is_instance<"))
        assertTrue(cpp.contains("kxs_safe_as_cast<"))
        assertTrue(cpp.contains("kxs_as_cast<"))
        assertTrue(cpp.contains("kxs_elvis("))
    }

    @Test
    fun emitsDualAbiSuspendBoxingIntrinsicsAndCpp23Fixes() {
        val source =
            """
            package kotlinx.coroutines.channels

            import kotlin.coroutines.*
            import kotlin.coroutines.intrinsics.*
            import kotlinx.coroutines.*

            public open class BaseChannelSuspender {
                public open suspend fun nextValue(x: Int): Int = x + 1
                public open suspend fun nextValue(tag: String): Int = 0
            }

            public class DerivedChannelSuspender(private val offset: Int) : BaseChannelSuspender() {
                override suspend fun nextValue(x: Int): Int = x + offset

                public suspend fun unitWithBranch(flag: Boolean, y: Int): Unit {
                    when (flag) {
                        true -> y
                        false -> y + 1
                    }
                    if (y > 0) return
                }

                public suspend fun nonLeafUnintercepted(): Int {
                    val seed = 7
                    return suspendCoroutineUninterceptedOrReturn { uCont ->
                        uCont.intercepted().resumeWith(Result.success(seed))
                        COROUTINE_SUSPENDED
                    }
                }

                public fun miscCpp23Checks(
                    condA: Boolean,
                    condB: Boolean,
                    condC: Boolean,
                    items: List<Int>,
                    cap: Int,
                ): Int {
                    require(cap == Channel.CONFLATED || cap == Channel.Factory.BUFFERED) { "bad cap" }
                    val ok = condA && condB || condC
                    val msg = buildString {
                        append("n=")
                        append(items.size)
                    }
                    val copied = (this as BufferedChannel<Int>).onReceiveOrNull
                    val sc = ScopeCoroutine(EmptyCoroutineContext, null)
                    return if (ok && msg.length > 0 && copied != null && sc != null) items.size else 0
                }
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "channels/DerivedChannelSuspender.kt")
        val hpp = res.headerArtifact.content
        val cpp = assertNotNull(res.sourceArtifact).content

        assertTrue(hpp.contains("using BaseChannelSuspender::next_value;"))
        assertTrue(
            hpp.contains(
                "[[clang::annotate(\"suspend\"), clang::annotate(\"kxs_implicit_continuation\"), " +
                    "clang::annotate(\"kxs_boxed_result\")]]",
            ),
        )
        assertTrue(hpp.contains("std::int32_t next_value(std::int32_t x);"))
        assertTrue(
            hpp.contains(
                "virtual void* next_value(std::int32_t x, " +
                    "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion);",
            ),
        )
        assertTrue(
            hpp.contains(
                "void* next_value(std::int32_t x, " +
                    "std::shared_ptr<kotlin::coroutines::Continuation<void*>> _kxs_completion) override;",
            ),
        )

        assertTrue(cpp.contains("kxs_box_suspend_result<std::int32_t>(x + offset_)"))
        assertTrue(cpp.contains("return nullptr;"))
        assertFalse(cpp.contains("switch (flag)"))
        assertTrue(cpp.contains("if ((flag == true))"))
        assertTrue(cpp.contains("kxs_suspend_coroutine_unintercepted_or_return("))
        assertTrue(cpp.contains("kxs_intercepted(u_cont)->resume_with("))
        assertTrue(cpp.contains("Channel<void*>::CONFLATED"))
        assertTrue(cpp.contains("Channel<void*>::BUFFERED"))
        assertTrue(cpp.contains("([&]() { return std::string(\"bad cap\"); })()"))
        assertTrue(cpp.contains("(cond_a && cond_b) || cond_c"))
        assertTrue(cpp.contains("static_cast<std::int32_t>(items.size())"))
        assertTrue(cpp.contains("static_cast<std::int32_t>(msg.size())"))
        assertTrue(cpp.contains("->on_receive_or_null()"))
        assertTrue(cpp.contains("std::shared_ptr(new kotlinx::coroutines::internal::ScopeCoroutine("))
    }

    @Test
    fun emitsAtomicAnyRefImplicitExtensionVarargValueClassComparableAndStarProjectionFixes() {
        val source =
            """
            package kotlinx.coroutines.internal

            import kotlinx.atomicfu.*
            import kotlin.coroutines.*

            internal inline fun AtomicInt.bumpUnless(target: Int, action: (Int) -> Unit): Boolean {
                loop { cur ->
                    if (cur == target) return false
                    if (compareAndSet(cur, cur + 1)) {
                        action(cur)
                        return true
                    }
                }
            }

            internal value class TicketId private constructor(private val holder: Any?) {
                val rawValue: Int get() = 42
                fun isEmpty(): Boolean = holder == null
                companion object {
                    const val DEFAULT_TAG: String = "ticket"
                    fun of(value: Any?): TicketId = TicketId(value)
                }
                object Sentinel {
                    const val CODE: Int = 7
                }
            }

            internal open class BaseBox<T>(protected val count: Int) {
                fun readCount(): Int = count
            }

            internal class DerivedBox<T>(
                count: Int,
                val orderKey: Long,
            ) : BaseBox<T>(count), Comparable<DerivedBox<*>> {
                private val owner = atomic<Any?>(null)
                private val state = atomic(0)

                fun tryLock(token: Any?): Boolean = owner.compareAndSet(null, token)
                fun bump(): Boolean = state.bumpUnless(1) { _ -> }
                fun readBaseCount(): Int = count
                override fun compareTo(other: DerivedBox<*>): Int =
                    compareValuesBy(this, other, DerivedBox<*>::readBaseCount, DerivedBox<*>::orderKey)
            }

            internal abstract class ContHolder(override val context: CoroutineContext) : Continuation<Int> {
                fun currentContext(): CoroutineContext = context
                override fun resumeWith(result: Result<Int>) {}
            }

            internal fun acceptAll(vararg items: Int): Int = items.size
            internal inline fun inspectCont(cont: Continuation<*>): CoroutineContext = cont.context
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "internal/AdvancedCoverage.kt")
        val hpp = res.headerArtifact.content
        val cpp = assertNotNull(res.sourceArtifact).content

        assertTrue(hpp.contains("using namespace kotlinx::coroutines;"))
        assertTrue(hpp.contains("class KxsAtomicReference"))
        assertTrue(hpp.contains("mutable KxsAtomicReference<std::any> owner_{};"))
        assertTrue(hpp.contains("mutable KxsCopyableAtomic<std::int32_t> state_{0};"))
        assertTrue(hpp.contains("class KxsVararg"))
        assertTrue(hpp.contains("std::int32_t accept_all(const KxsVararg<std::int32_t>& items);"))
        assertFalse(hpp.contains("std::initializer_list<std::int32_t> items"))
        assertTrue(hpp.contains("class TicketId"))
        assertTrue(hpp.contains("static inline const std::string DEFAULT_TAG"))
        assertTrue(hpp.contains("class Sentinel"))
        assertTrue(hpp.contains("bool is_empty() const"))
        assertTrue(hpp.contains("return holder_ == nullptr;"))
        assertTrue(hpp.contains("std::int32_t raw_value() const"))
        assertTrue(hpp.contains("int compare_to(const DerivedBox<std::any>& other) const override"))
        assertTrue(hpp.contains("auto operator<=>(const DerivedBox<std::any>& other) const"))
        assertTrue(hpp.contains("std::int32_t read_base_count() const"))
        assertTrue(hpp.contains("return this->count;"))
        assertTrue(hpp.contains("std::shared_ptr<CoroutineContext> current_context();"))
        assertTrue(cpp.contains("return context_;"))
        assertTrue(hpp.contains("template <typename KxsStar0>"))
        assertTrue(hpp.contains("inspect_cont(std::shared_ptr<Continuation<KxsStar0>> cont)"))
        assertTrue(hpp.contains("compare_exchange_strong("))
        assertTrue(hpp.contains("std::invoke"))
    }

    @Test
    fun coroutineFlowSynchronizedOverridesAndAccessorsTranslateCleanly() {
        val source =
            """
            package kotlinx.coroutines.flow

            import kotlin.coroutines.*
            import kotlinx.atomicfu.*
            import kotlinx.coroutines.*
            import kotlinx.coroutines.flow.internal.*

            public class SharedFlowSlot : AbstractSharedFlowSlot<SharedFlowImpl<*>>() {
                override fun allocateLocked(flow: SharedFlowImpl<*>): Boolean = true
                override fun freeLocked(flow: SharedFlowImpl<*>): Array<Continuation<Unit>?> = emptyArray()
            }

            public class SharedFlowImpl<T> : AbstractSharedFlow<SharedFlowSlot>(), MutableSharedFlow<T> {
                private val idx = atomic(0)
                public var cachedSize: Int = 0
                    private set

                override val replayCache: List<T> get() = emptyList()
                override suspend fun collect(collector: FlowCollector<T>): Nothing {
                    throw CancellationException("done")
                }
                override fun tryEmit(value: T): Boolean = true
                override suspend fun emit(value: T) {}
                override fun createSlot() = SharedFlowSlot()
                override fun createSlotArray(size: Int) = arrayOfNulls(size)
                override fun resetReplayCache() = synchronized(this) {
                    idx.incrementAndGet()
                    cachedSize = 0
                }
                override fun toString(): String = "${'$'}{this::classSimpleName}@1"
            }

            public class MyElement : AbstractCoroutineContextElement(MyElement) {
                public companion object Key : CoroutineContext.Key<MyElement>
                public val altKey: CoroutineContext.Key<*> get() = Key
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "flow/SharedFlowCheck.kt")
        val hpp = res.headerArtifact.content
        val cpp = assertNotNull(res.sourceArtifact).content

        assertTrue(hpp.contains("std::int32_t cached_size"))
        assertTrue(hpp.contains("std::shared_ptr<SharedFlowSlot> create_slot() override"))
        assertTrue(hpp.contains("std::vector<std::shared_ptr<SharedFlowSlot>> create_slot_array(std::int32_t size) override"))
        assertTrue(hpp.contains("void reset_replay_cache() override"))
        assertTrue(cpp.contains("-> std::recursive_mutex&") || hpp.contains("-> std::recursive_mutex&"))
        assertTrue(hpp.contains("fetch_add(1, std::memory_order_seq_cst) + 1)"))
        assertTrue(cpp.contains("class_simple_name(") || hpp.contains("class_simple_name("))
        assertTrue(hpp.contains("std::addressof(MyElement::Key::instance())") || cpp.contains("std::addressof(MyElement::Key::instance())"))
    }

    @Test
    fun testChannelResultValueClassInlineLambdaAndSegmentOrClosed() {
        val source =
            """
            package kotlinx.coroutines.channels

            import kotlinx.coroutines.channels.ChannelResult.Companion.failure
            import kotlinx.coroutines.channels.ChannelResult.Companion.success
            import kotlin.jvm.JvmInline

            public class ClosedCause(public val cause: Throwable?) {
                override fun hashCode(): Int = cause.hashCode()
            }

            @JvmInline
            public value class ChannelResult<out T>(public val holder: Any?) {
                public fun getOrNull(): T? = holder as? T
                public companion object {
                    public fun <E> success(value: E): ChannelResult<E> = ChannelResult(value)
                    public fun <E> failure(exception: Throwable?): ChannelResult<E> =
                        ChannelResult(ClosedCause(exception))
                }
            }

            public inline fun <T> ChannelResult<T>.onSuccess(action: (value: T) -> Unit): ChannelResult<T> {
                val v = holder as? T
                if (v != null) action(v)
                return this
            }

            @JvmInline
            public value class SegmentOrClosed<S>(private val value: Any?) {
                public val segment: S get() = value as S
            }

            public fun <S> wrapSegment(seg: S): SegmentOrClosed<S> = SegmentOrClosed(seg)

            public fun testChannelResultOps(res: ChannelResult<Int>, err: Throwable?): ChannelResult<Int> {
                val handled = res.onSuccess { _ -> }
                if (err != null) return failure(err)
                return handled
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "channels/ChannelResultCheck.kt")
        val hpp = res.headerArtifact.content
        val cpp = assertNotNull(res.sourceArtifact).content
        val combined = "$hpp\n$cpp"

        assertTrue(hpp.contains("class ChannelResult"))
        assertTrue(hpp.contains("holder()"))
        assertTrue(combined.contains("std::is_same_v<KxsH, std::exception_ptr>"))
        assertTrue(combined.contains("ChannelResult<std::int32_t>::failure(") || combined.contains("ChannelResult<void*>::failure("))
        assertTrue(combined.contains("SegmentOrClosed<S>("))
        assertTrue(!combined.contains("ChannelResult<T> handled{};"))
        assertTrue(combined.contains("[[maybe_unused]]"))
    }

    @Test
    fun testConcurrentLinkedListNodeSmartCastsSuperGettersAndBracelessIf() {
        val source =
            """
            package kotlinx.coroutines.internal

            import kotlinx.atomicfu.atomic

            public class ClosedNode(public val reason: Int)

            public abstract class ConcurrentLinkedListNode<N : ConcurrentLinkedListNode<N>>(prev: N?) {
                public val _next = atomic<Any?>(null)
                private val _prev = atomic(prev)
                public val next: N? get() = _next.value as? N
                public val prev: N? get() = _prev.value
                public val isTail: Boolean get() = next == null
                public open val isRemoved: Boolean get() = next != null && !isTail

                public fun unlink() {
                    val prev = prev
                    val next = next
                    if (prev != null) prev._next.value = next
                }

                public fun inspectNode(cur: Any): Boolean {
                    val isValidClosed = cur is ClosedNode && cur.reason > 0
                    val isBad = cur !is ClosedNode || cur.reason == 0
                    return isValidClosed && !isBad
                }
            }

            public abstract class Segment<S : Segment<S>>(
                public val id: Long,
                prev: S?,
            ) : ConcurrentLinkedListNode<S>(prev) {
                public fun probe(seg: S): Boolean = seg.isRemoved || isTail
            }

            public fun <S : Segment<S>> inspectWithScope(seg: S): Boolean =
                with(seg) {
                    val idCopy = id
                    idCopy >= 0L && isRemoved
                }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "internal/ConcurrentLinkedListCheck.kt")
        val hpp = res.headerArtifact.content
        val cpp = assertNotNull(res.sourceArtifact).content
        val combined = "$hpp\n$cpp"

        assertTrue(combined.contains("if (prev != nullptr)"))
        assertTrue(combined.contains("set_value("))
        assertTrue(combined.contains("seg->is_removed()"))
        assertTrue(combined.contains("this->is_tail()") || combined.contains("is_tail()"))
        assertTrue(combined.contains("cur_narrowed"))
        assertTrue(combined.contains("kxs_recv"))
        assertTrue(combined.contains("->id"))
        assertTrue(combined.contains("->is_removed()"))
    }

    @Test
    fun testSelectImplementationHoistedNestedClassAndClauseDataProjections() {
        val source =
            """
            package kotlinx.coroutines.selects

            import kotlin.coroutines.CoroutineContext

            public open class SelectImplementation<R>(
                public val context: CoroutineContext,
            ) {
                private val clauses: MutableList<ClauseData> = ArrayList()
                private var state: Any? = null

                public class ClauseData(
                    public val clauseObject: Any,
                    public val block: Any,
                )

                private fun Any.matchClause(): Boolean = this is ClauseData

                public fun collectClause(x: Any?): Boolean {
                    if (x is ClauseData) {
                        clauses.add(x)
                        return x.matchClause()
                    }
                    return when (state) {
                        is ClauseData -> true
                        else -> false
                    }
                }
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "selects/SelectCheck.kt")
        val hpp = res.headerArtifact.content
        val cpp = res.sourceArtifact?.content.orEmpty()
        val combined = "$hpp\n$cpp"

        assertTrue(hpp.contains("class SelectImplementation_ClauseData"))
        assertTrue(combined.contains("std::vector<std::shared_ptr<SelectImplementation_ClauseData>>{}") || combined.contains("std::vector<SelectImplementation_ClauseData>{}"))
        assertTrue(combined.contains("SelectImplementation_ClauseData"))
        assertTrue(combined.contains("this->match_clause(") || combined.contains("match_clause("))
    }

    @Test
    fun getterConstnessTracksMutatingEnclosingCalls() {
        val source =
            """
            package example.props

            class CounterHolder {
                private var count: Int = 0
                private val fixed: Int = 7

                fun bumpAndRead(): Int {
                    count += 1
                    return count
                }

                val stableVal: Int
                    get() = fixed

                val mutatingVal: Int
                    get() = bumpAndRead()
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "example/props/CounterHolder.kt")
        val hpp = res.headerArtifact.content
        val cpp = res.sourceArtifact?.content.orEmpty()
        val combined = "$hpp\n$cpp"

        assertTrue(hpp.contains("std::int32_t stable_val() const"))
        assertFalse(hpp.contains("std::int32_t mutating_val() const"))
        assertTrue(combined.contains("std::int32_t mutating_val()"))
    }

    @Test
    fun exceptionElvisAndCancellationPtrLowering() {
        val source =
            """
            package kotlinx.coroutines.channels

            import kotlinx.coroutines.CancellationException

            const val CLOSE_MSG: String = "Channel was closed"

            class SampleJob {
                fun cancelInternal(cause: Throwable) {}

                fun abort(cause: Throwable?) {
                    val exc = cause ?: CancellationException(CLOSE_MSG)
                    cancelInternal(exc)
                }
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "kotlinx/coroutines/channels/SampleJob.kt")
        val hpp = res.headerArtifact.content
        val cpp = res.sourceArtifact?.content.orEmpty()
        val combined = "$hpp\n$cpp"

        assertTrue(hpp.contains("#include <exception>"))
        assertTrue(combined.contains("const std::string CLOSE_MSG"))
        assertTrue(combined.contains("kxs_elvis("))
        assertTrue(combined.contains("kxs_to_exception_ptr("))
    }

    @Test
    fun broadcastCoroutineAndChannelScopeLowering() {
        val source =
            """
            package kotlinx.coroutines.channels

            import kotlinx.coroutines.*
            import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
            import kotlin.coroutines.*

            public fun CoroutineScope.sampleBroadcast(
                start: CoroutineStart = CoroutineStart.LAZY,
                block: suspend ProducerScope<Int>.() -> Unit,
            ): ReceiveChannel<Int> {
                val ctx = newCoroutineContext(Dispatchers.Unconfined)
                val ch = Channel<Int>(BUFFERED)
                if (start.isLazy) {
                    val u = block.createCoroutineUnintercepted(ch as ProducerScope<Int>, ch as Continuation<Unit>)
                }
                return ch
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "kotlinx/coroutines/channels/SampleBroadcast.kt")
        val hpp = res.headerArtifact.content
        val cpp = res.sourceArtifact?.content.orEmpty()
        val combined = "$hpp\n$cpp"

        assertTrue(combined.contains("Dispatchers::instance().unconfined()"))
        assertTrue(combined.contains("new_coroutine_context("))
        assertTrue(combined.contains("is_lazy("))
        assertTrue(combined.contains("BUFFERED"))
        assertTrue(combined.contains("create_coroutine_unintercepted("))
        assertFalse(combined.contains("auto u = block"))
    }

    @Test
    fun emitsBroadcastChannelDelegationDispatchExceptionThisTemplateDeductionAndTryReturnFixes() {
        val source =
            """
            package kotlinx.coroutines.channels

            import kotlinx.coroutines.*
            import kotlinx.coroutines.selects.*
            import kotlin.coroutines.*

            class DispatchException(override val cause: Throwable) : Exception()

            class DelegatedBroadcast<E>(
                private val channel: BroadcastChannel<E>,
            ) : BroadcastChannel<E> by channel {
                fun describeSelf(): String = "DelegatedBroadcast(${'$'}this)"
                fun probeState(state: Any?): Int =
                    when (state) {
                        REUSABLE -> 1
                        else -> 0
                    }
            }

            fun <R, T> startWithIdentity(
                start: CoroutineStart,
                receiver: R,
                completion: Continuation<T>,
                block: suspend R.() -> T,
            ) {
                start(block, receiver, completion)
            }

            fun tryCatchWithReturn(uCont: Continuation<Int>): Int {
                val value = try {
                    10
                } catch (e: Throwable) {
                    uCont.resumeWithException(e)
                    return -1
                }
                return value
            }
            """.trimIndent() + "\n"

        val res = translateKotlinToCpp(source, sourcePath = "kotlinx/coroutines/channels/SampleFixes.kt")
        val hpp = res.headerArtifact.content
        val cpp = res.sourceArtifact?.content.orEmpty()
        val combined = "$hpp\n$cpp"

        assertTrue(combined.contains("this_val") || combined.contains("(*this)"))
        assertFalse(hpp.contains("std::exception_ptr cause() const override"))
        assertTrue(hpp.contains("SelectClause2<E, std::shared_ptr<kotlinx::coroutines::channels::SendChannel<E>>>"))
        assertTrue(combined.contains("kxs_any_eq(state, REUSABLE)"))
        assertTrue(hpp.contains("std::type_identity_t<R>"))
        assertTrue(hpp.contains("std::type_identity_t<std::shared_ptr<Continuation<T>>>"))
        assertTrue(combined.contains("kxs_resume_with_exception("))
        assertTrue(combined.contains("std::optional<"))
    }

    @Test
    fun emitsCallableForwarderOverloadForWithCoroutineContextTemplate() {
        val source =
            """
            package kotlinx.coroutines

            import kotlin.coroutines.CoroutineContext

            inline fun <T> withCoroutineContext(
                context: CoroutineContext,
                countOrElement: Any?,
                block: () -> T,
            ): T = block()
            """.trimIndent() + "\n"

        val res =
            translateKotlinToCpp(
                source,
                sourcePath = "kotlinx/coroutines/CoroutineContext.common.kt",
            )
        val hpp = res.headerArtifact.content

        assertTrue(hpp.contains("#ifndef KXS_FN_CALLABLE_FWD_KOTLINX_COROUTINES_WITH_COROUTINE_CONTEXT_3"))
        assertTrue(hpp.contains("template <typename KxsCallable>"))
        assertTrue(hpp.contains("return with_coroutine_context<KxsRet>("))
    }

    @Test
    fun mergedCompanionAndDeferredStaticDefsInInterface() {
        val source =
            """
            package kotlinx.coroutines.flow

            public interface SharingStarted {
                public fun command(subscriptionCount: Int): Int

                public companion object {
                    public val Eagerly: SharingStarted = StartedEagerly()
                    public val Lazily: SharingStarted = StartedLazily()
                }
            }

            public fun SharingStarted.Companion.WhileSubscribed(): SharingStarted = StartedEagerly()

            private class StartedEagerly : SharingStarted {
                override fun command(subscriptionCount: Int): Int = 1
            }

            private class StartedLazily : SharingStarted {
                override fun command(subscriptionCount: Int): Int = 2
            }
            """.trimIndent()

        val res =
            translateKotlinToCpp(
                source,
                sourcePath = "kotlinx/coroutines/flow/SharingStarted.kt",
            )
        val hpp = res.headerArtifact.content

        assertEquals(1, Regex("""struct\s+Companion\s*\{""").findAll(hpp).count())
        assertFalse(hpp.contains("static inline const"))
        assertTrue(hpp.indexOf("SharingStarted::eagerly = std::make_shared<StartedEagerly>()") > hpp.indexOf("class StartedEagerly"))
        assertTrue(hpp.indexOf("SharingStarted::lazily = std::make_shared<StartedLazily>()") > hpp.indexOf("class StartedLazily"))
    }

    @Test
    fun anonObjectCapturesOuterThisAndSharedLocal() {
        val source =
            """
            package kotlinx.coroutines

            public interface DisposableHandle {
                public fun dispose()
            }

            public open class OuterJob {
                protected val flag: Boolean = false

                protected fun notifyOuter(x: Any?) {}

                public fun attach(child: DisposableHandle) {
                    val captured: DisposableHandle = child
                    val handler = object : DisposableHandle {
                        override fun dispose() {
                            captured.dispose()
                            if (flag) {
                                notifyOuter(this)
                                notifyOuter(this@OuterJob)
                            }
                        }
                    }
                    handler.dispose()
                }
            }
            """.trimIndent()

        val res =
            translateKotlinToCpp(
                source,
                sourcePath = "kotlinx/coroutines/OuterJob.kt",
            )
        val combined = res.headerArtifact.content + "\n" + res.sourceArtifact?.content.orEmpty()

        assertTrue(combined.contains("using KxsOuterPtr = decltype(this);"))
        assertTrue(combined.contains("KxsOuterPtr kxs_outer_;"))
        assertTrue(combined.contains("this->captured_cap->dispose()"))
        assertTrue(combined.contains("this->kxs_outer_->notify_outer((*this))"))
        assertTrue(combined.contains("this->kxs_outer_->notify_outer("))
    }

    @Test
    fun channelFactoryInfersUnitTypeInSubnamespace() {
        val flowSource =
            """
            package kotlinx.coroutines.flow

            import kotlinx.coroutines.channels.Channel

            public class Holder {
                public val updates: Channel<Unit> = Channel(Channel.CONFLATED)
            }
            """.trimIndent()

        val flowRes =
            translateKotlinToCpp(
                flowSource,
                sourcePath = "kotlinx/coroutines/flow/Holder.kt",
            )
        val flowCombined = flowRes.headerArtifact.content + "\n" + flowRes.sourceArtifact?.content.orEmpty()

        assertTrue(flowCombined.contains("using namespace kotlinx::coroutines;"))
        assertTrue(flowCombined.contains("Channel<Unit>"))
        assertTrue(flowCombined.contains("channel<Unit>("))

        val externalSource =
            """
            package app.sample

            import kotlinx.coroutines.channels.Channel

            public class Holder {
                public val updates: Channel<Unit> = Channel(Channel.CONFLATED)
            }
            """.trimIndent()

        val externalRes =
            translateKotlinToCpp(
                externalSource,
                sourcePath = "app/sample/Holder.kt",
            )
        val externalCombined = externalRes.headerArtifact.content + "\n" + externalRes.sourceArtifact?.content.orEmpty()

        assertTrue(externalCombined.contains("Channel<kotlinx::coroutines::Unit>"))
        assertTrue(externalCombined.contains("channel<kotlinx::coroutines::Unit>("))
    }

    @Test
    fun synchronizedInlineLocalDeclInfersTypesAndAvoidsStdAny() {
        val source =
            """
            package kotlinx.coroutines.flow

            import kotlinx.coroutines.DisposableHandle
            import kotlinx.coroutines.internal.SynchronizedObject
            import kotlinx.coroutines.internal.synchronized

            private class Emitter : DisposableHandle {
                override fun dispose() {}
            }

            public class ThreadSafeHeap<T> {
                public inline fun removeFirstIf(predicate: (T) -> Boolean): T? = null
            }

            public class TestDispatchEvent(
                public val time: Long,
                public val tag: Int,
            )

            public class Scheduler : SynchronizedObject() {
                private val lock = SynchronizedObject()
                private val events = ThreadSafeHeap<TestDispatchEvent>()

                public fun pickSlot(fallback: Int): Int {
                    val slot = synchronized(this) {
                        var candidate = 0
                        while (true) {
                            candidate = fallback
                            if (candidate > 0) break
                        }
                        candidate
                    }
                    return slot
                }

                public fun tryEmit(empty: Boolean): DisposableHandle? {
                    val emitter = synchronized(this) lock@{
                        if (empty) return@lock null
                        Emitter()
                    }
                    return emitter
                }

                public fun advance(targetTime: Long): Long {
                    val event = synchronized(lock) {
                        val event = events.removeFirstIf { it.time <= targetTime }
                        if (event == null) {
                            return targetTime
                        } else {
                            event
                        }
                    }
                    return event.time
                }
            }
            """.trimIndent()

        val res =
            translateKotlinToCpp(
                source,
                sourcePath = "kotlinx/coroutines/flow/Scheduler.kt",
            )
        val combined = res.headerArtifact.content + "\n" + res.sourceArtifact?.content.orEmpty()

        assertTrue(combined.contains("-> std::shared_ptr<Emitter>"))
        assertTrue(combined.contains("return nullptr;"))
        assertTrue(combined.contains("std::optional<TestDispatchEvent>"))
        assertFalse(combined.contains("std::any event"))
        assertFalse(combined.contains("std::any emitter"))
        assertFalse(combined.contains("std::any slot"))
    }

    @Test
    fun inlineTemplateDeductionSafeCallAndStdAnyNormalization() {
        val source =
            """
            package kotlinx.coroutines.channels

            public class Box<E>(public var item: E) {
                public inline fun inspect(block: (E) -> Unit) {
                    block(item)
                }

                public inline fun <R> runInner(block: Box<E>.() -> R): R = block()

                public fun outerTemplateCaller(other: Box<E>) {
                    other.inspect { if (it == item) return }
                    runInner { if (this === other) return }
                }
            }

            public fun callInlineOnConcreteBox(box: Box<Int>?, cond: Boolean): Any? {
                val concrete: Box<Int> = Box(1)
                concrete.inspect { if (it < 0) return null }
                box?.inspect { if (it < 0) return null }
                val normalized: Any? =
                    when {
                        cond -> 1
                        else -> null
                    }
                return normalized
            }
            """.trimIndent()

        val res = translateKotlinToCpp(source, sourcePath = "kotlinx/coroutines/channels/Box.kt")
        val combined = res.headerArtifact.content + "\n" + res.sourceArtifact?.content.orEmpty()

        assertTrue(combined.contains("template <typename...> class KxsC, typename KxsT0"))
        assertTrue(combined.contains("std::type_identity<KxsT0>"))
        assertTrue(combined.contains("!kxs_is_null("))
        assertTrue(combined.contains("-> std::any"))
        assertFalse(combined.contains("using E = std::remove_cvref_t<decltype"))
    }

    @Test
    fun exceptionCoercionSwitchBreakAndTernaryThrowGuards() {
        val source =
            """
            package kotlinx.coroutines.test

            public fun coerceAndControl(
                flag: Boolean,
                code: Int,
                err: Throwable,
            ): Throwable? {
                val e1: Throwable? = err
                val e2: Throwable = IllegalStateException("fail")
                var chosen: Throwable? = null
                chosen = if (flag) e1 else throw IllegalStateException("bad")
                while (flag) {
                    when (code) {
                        1 -> break
                        else -> break
                    }
                }
                val shadow = 1
                if (flag) {
                    val shadow = 2
                    if (shadow > 0) return chosen ?: e2
                }
                return e2
            }
            """.trimIndent()

        val res =
            translateKotlinToCpp(
                source,
                sourcePath = "kotlinx/coroutines/test/ExceptionCoercion.kt",
            )
        val combined = res.headerArtifact.content + "\n" + res.sourceArtifact?.content.orEmpty()

        assertTrue(combined.contains("kxs_to_exception_ptr("))
        assertFalse(combined.contains("switch (code)"))
        assertTrue(combined.contains("if ((code == 1))"))
        assertTrue(combined.contains("shadow_shadow_"))
        assertFalse(combined.contains("? e1 : throw"))
    }

    @Test
    fun workerEventLoopMutableSetAndCoerceMixedTypes() {
        val source =
            """
            package kotlinx.coroutines.native

            import kotlin.native.concurrent.Worker

            public fun useWorkerAndEventLoop(seen: MutableSet<Worker>, v: Long): Long {
                val cur = Worker.current
                seen.add(cur)
                val bg = Worker.start("bg")
                bg.requestTermination().result
                val loop = ThreadLocalEventLoop.eventLoop
                val loopViaGetter = loop
                val _unused = loopViaGetter
                return v.coerceAtLeast(0)
            }
            """.trimIndent()

        val res =
            translateKotlinToCpp(
                source,
                sourcePath = "kotlinx/coroutines/native/WorkerOps.kt",
            )
        val combined = res.headerArtifact.content + "\n" + res.sourceArtifact?.content.orEmpty()

        assertTrue(combined.contains("Worker::current"))
        assertTrue(combined.contains("consume_result()"))
        assertTrue(combined.contains("ThreadLocalEventLoop::instance().event_loop()"))
        assertTrue(combined.contains("kxs_c.insert("))
        assertTrue(combined.contains("std::common_type_t<"))
    }
}
