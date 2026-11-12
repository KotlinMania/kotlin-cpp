package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.rustkotlin.kcpp.CppGcMode
import io.github.kotlinmania.rustkotlin.kcpp.CppTargetConfig
import io.github.kotlinmania.rustkotlin.kcpp.translateKotlinToCpp
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
        // `readHeader()` MUST be hoisted to `kxs_arg0` before `fetch_chunk(10)` runs!
        assertTrue(hpp.contains("auto kxs_arg0 = read_header();"))
        assertTrue(hpp.contains("combine_pair(kxs_arg0, fetch_chunk(10))"))
        // Expression-valued `if` containing `fetchChunk` MUST hoist `std::optional<...> kxs_cf0;` without `[&]()` IIFE!
        assertTrue(hpp.contains("std::optional<std::int32_t> kxs_cf0;"))
        assertFalse(hpp.contains("[&]() {\n    if (flag)"))
    }

    @Test
    fun enforcesFirSuspendCallCheckerRulesRejectingIllegalContexts() {
        // 1. Suspend call in non-suspend function
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

        // 2. Suspend call in crossinline lambda
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

        // 3. Suspend call in ordinary non-inline lambda variable
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

        // 4. Arbitrary external suspend call inside @RestrictsSuspension SequenceScope
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

        // Allowed restricted member `yield` inside `sequence { ... }` succeeds!
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

        // Synchronous local uses physical stack root `ObjHolder`; foreign entry emits `CalledFromNativeGuard` + `UpdateHeapRef`!
        assertTrue(cpp.contains("Kotlin_initRuntimeIfNeeded();"))
        assertTrue(cpp.contains("CalledFromNativeGuard kxs_native_guard{};"))
        assertTrue(cpp.contains("ObjHolder local_only_holder(make_node());"))
        assertTrue(cpp.contains("Kotlin_mm_UpdateHeapRef(std::addressof(target.next), child)"))

        // Cross-suspend local uses `OwningExternalRCRef` (NEVER `ObjHolder`!) and clears dead slot before second `yield()`!
        assertTrue(hpp.contains("OwningExternalRCRef live_across_first(make_node());"))
        assertTrue(hpp.contains("live_across_first.reset();"))

        // Reject `createCleaner` capturing `this`
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

        // In KONAN_GC mode, reference CAS emits `Kotlin_mm_CompareAndSetVolatileHeapRef`!
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

        // Hard safety gate: `usePinned` spanning `suspend` MUST fail at translation time!
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

        // Forward declaration emitted before `touchForward`
        assertTrue(hpp.contains("class MatrixCell;\n"))
        // C++23 multidimensional `operator[](std::int32_t r, std::int32_t c)` + call `m[...]`
        assertTrue(hpp.contains("std::int32_t operator[](std::int32_t r, std::int32_t c) const;"))
        assertTrue(cpp.contains("m[(*ev_narrowed).row, (*ev_narrowed).col]"))
        // `data class` C++20/23 defaulted equality + `copy`
        assertTrue(hpp.contains("bool operator==(const Updated&) const = default;"))
        assertTrue(hpp.contains("Updated copy("))
    }

    @Test
    fun cliKcppCommandWritesDeterministicArtifactsAndSupportsDryRunAndStdout() {
        val ktSource =
            """
            // port-lint: source internal/Symbol.kt
            package kotlinx.coroutines.internal

            public class FlagToken(public val id: Int) {
                public fun doubled(): Int = id * 2
            }
            """.trimIndent() + "\n"

        val writtenFiles = LinkedHashMap<String, String>()
        val reports = ArrayList<String>()

        runSingleFileCommand(
            arrayOf("kcpp", "-o", "gen_cpp", "FlagToken.kt"),
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
}
