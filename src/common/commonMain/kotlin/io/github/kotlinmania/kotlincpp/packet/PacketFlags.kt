package io.github.kotlinmania.kotlincpp.packet

/**
 * 64-bit semantic, lexical, coroutine, ownership, Native GC, FFI, and C++ emission flags
 * attached to [SyntaxPacket] and [PacketFrame] nodes.
 *
 * Uses signed [Long] bitmasks on public surfaces so Swift Export maps flags directly to
 * `Int64` without inline value-class boxing wrappers.
 */
public object PacketFlags {
    public const val NONE: Long = 0L

    // Bits 0..7: Lexical, String & Comment Properties
    public const val IS_SIGNIFICANT: Long = 1L shl 0
    public const val RAW_MULTILINE_STRING: Long = 1L shl 1
    public const val INTERPOLATED_STRING: Long = 1L shl 2
    public const val HAS_ESCAPES: Long = 1L shl 3
    public const val HAS_LONE_SURROGATE: Long = 1L shl 4
    public const val BACKTICK_ESCAPED: Long = 1L shl 5
    public const val PORT_LINT_DIRECTIVE: Long = 1L shl 6
    public const val DOC_COMMENT_HAS_LINKS: Long = 1L shl 7

    // Bits 8..24: Modifiers & Callable Qualifiers
    public const val MOD_PUBLIC: Long = 1L shl 8
    public const val MOD_INTERNAL: Long = 1L shl 9
    public const val MOD_PROTECTED: Long = 1L shl 10
    public const val MOD_PRIVATE: Long = 1L shl 11
    public const val MOD_SUSPEND: Long = 1L shl 12
    public const val MOD_INLINE: Long = 1L shl 13
    public const val MOD_NOINLINE: Long = 1L shl 14
    public const val MOD_CROSSINLINE: Long = 1L shl 15
    public const val MOD_REIFIED: Long = 1L shl 16
    public const val MOD_OPEN: Long = 1L shl 17
    public const val MOD_ABSTRACT: Long = 1L shl 18
    public const val MOD_OVERRIDE: Long = 1L shl 19
    public const val MOD_SEALED: Long = 1L shl 20
    public const val MOD_DATA: Long = 1L shl 21
    public const val MOD_VALUE: Long = 1L shl 22
    public const val MOD_CONST: Long = 1L shl 23
    public const val MOD_COMPANION: Long = 1L shl 24

    // Bits 25..32: Coroutine State-Machine & Evaluation Order
    public const val SUSPEND_CALL_SITE: Long = 1L shl 25
    public const val IMPLICIT_CONTINUATION_INTRINSIC: Long = 1L shl 26
    public const val BOXED_RESULT_INTRINSIC: Long = 1L shl 27
    public const val HAS_SUSPEND_IN_SUBTREE: Long = 1L shl 28
    public const val EVAL_ORDER_HOIST_REQUIRED: Long = 1L shl 29
    public const val SPILL_ACROSS_SUSPEND: Long = 1L shl 30
    public const val DEAD_BEFORE_NEXT_SUSPEND: Long = 1L shl 31
    public const val RESTRICTS_SUSPENSION_SCOPE: Long = 1L shl 32

    // Bits 33..46: Ownership, Kotlin/Native GC Bridge & FFI / Inline C
    public const val OWNED_VALUE: Long = 1L shl 33
    public const val BORROWED_REF: Long = 1L shl 34
    public const val SHARED_OWNER: Long = 1L shl 35
    public const val GC_MANAGED_OBJECT_REF: Long = 1L shl 36
    public const val GC_PHYSICAL_STACK_ROOT: Long = 1L shl 37
    public const val GC_PERSISTENT_EXTERNAL_RC: Long = 1L shl 38
    public const val GC_HEAP_WRITE_BARRIER: Long = 1L shl 39
    public const val GC_VOLATILE_CAS_BARRIER: Long = 1L shl 40
    public const val GC_FOREIGN_THREAD_ENTRY: Long = 1L shl 41
    public const val CLEANER_DETACHED_RESOURCE: Long = 1L shl 42
    public const val FFI_C_ABI_SYMBOL: Long = 1L shl 43
    public const val FFI_PINNED_SCOPE: Long = 1L shl 44
    public const val FFI_MEM_SCOPED_ARENA: Long = 1L shl 45
    public const val INLINE_C_PASSTHROUGH: Long = 1L shl 46

    // Bits 47..57: Atomics, ThreadLocal, Nullability & Control Flow
    public const val ATOMICFU_FIELD: Long = 1L shl 47
    public const val ATOMICFU_ARRAY: Long = 1L shl 48
    public const val ATOMICFU_LOOP: Long = 1L shl 49
    public const val THREAD_LOCAL_SLOT: Long = 1L shl 50
    public const val NULLABLE_TYPE: Long = 1L shl 51
    public const val SMART_CAST_NON_NULL: Long = 1L shl 52
    public const val SMART_CAST_SUBTYPE: Long = 1L shl 53
    public const val EXPRESSION_VALUE_POSITION: Long = 1L shl 54
    public const val SINGLE_EVAL_SUBJECT_REQUIRED: Long = 1L shl 55
    public const val DIVERGING_EXPR: Long = 1L shl 56
    public const val KOTLIN_RESULT_CHANNEL: Long = 1L shl 57

    // Bits 58..62: Header/Source Co-Location & Provenance
    public const val EMIT_IN_HPP_DECL: Long = 1L shl 58
    public const val EMIT_IN_HPP_INLINE_BODY: Long = 1L shl 59
    public const val EMIT_IN_CPP_BODY: Long = 1L shl 60
    public const val NEEDS_FORWARD_DECL: Long = 1L shl 61
    public const val EMIT_PROVENANCE_COMMENT: Long = 1L shl 62
    public const val MOD_EXTERNAL_OR_EXTENSION: Long = 1L shl 63

    public fun hasFlag(
        flags: Long,
        mask: Long,
    ): Boolean = (flags and mask) != 0L
}
