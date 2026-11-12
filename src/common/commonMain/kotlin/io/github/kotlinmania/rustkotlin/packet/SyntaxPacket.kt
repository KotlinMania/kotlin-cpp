package io.github.kotlinmania.rustkotlin.packet

public enum class CommentPacketKind {
    LINE,
    BLOCK,
    KDOC,
    SHEBANG,
}

public enum class LiteralPacketKind {
    INT,
    FLOAT,
    CHAR,
    BOOLEAN,
    NULL,
}

public enum class StringSegmentKind {
    INTERP_PREFIX,
    OPEN_QUOTE,
    LITERAL_PART,
    ESCAPE_SEQ,
    SHORT_ENTRY_START,
    LONG_ENTRY_START,
    LONG_ENTRY_END,
    CLOSE_QUOTE,
}

public enum class SymbolRole {
    UNCLASSIFIED,
    PACKAGE,
    IMPORT_ALIAS,
    TYPE,
    TYPE_PARAM,
    FUNCTION,
    METHOD,
    PROPERTY,
    PRIVATE_FIELD,
    CONSTANT,
    ENUM_ENTRY,
    PARAM,
    LOCAL,
    LABEL,
    C_FFI_SYMBOL,
}

/**
 * Relative character span and target path of a KDoc `[target]` or `[label][target]` link
 * extracted via `KDocLexer` + `KDocParser`.
 */
public data class KDocLinkSpan(
    public val relativeStart: Int,
    public val relativeEnd: Int,
    public val targetPath: String,
)

/**
 * Typed leaf packet in a lossless 1D [PacketStream].
 *
 * Every UTF-16 code unit of the source file belongs to exactly one [SyntaxPacket],
 * including [WhitespacePacket] and [CommentPacket]. Modeled as a `sealed class`
 * (never `sealed interface`) for clean Swift Export code generation.
 */
public sealed class SyntaxPacket {
    public abstract val packetIndex: Int
    public abstract val span: PacketSpan
    public abstract val rawText: String
    public abstract var flags: Long

    public val isSignificant: Boolean
        get() = PacketFlags.hasFlag(flags, PacketFlags.IS_SIGNIFICANT)

    /** Spaces, tabs, form-feeds, and line breaks preserved verbatim in stream order. */
    public data class WhitespacePacket(
        override val packetIndex: Int,
        override val span: PacketSpan,
        override val rawText: String,
        public val newlineCount: Int,
        public val trailingIndentColumns: Int,
        override var flags: Long = PacketFlags.NONE,
    ) : SyntaxPacket()

    /** First-class comment packet (`//`, `/* ... */`, `/** KDoc */`, `#!`). */
    public data class CommentPacket(
        override val packetIndex: Int,
        override val span: PacketSpan,
        override val rawText: String,
        public val kind: CommentPacketKind,
        public val portLintDirectiveKind: String? = null,
        public val portLintTarget: String? = null,
        public val kdocLinks: List<KDocLinkSpan> = emptyList(),
        override var flags: Long = PacketFlags.NONE,
    ) : SyntaxPacket()

    /** Identifier or backtick-escaped identifier with target C++ naming metadata. */
    public data class IdentPacket(
        override val packetIndex: Int,
        override val span: PacketSpan,
        override val rawText: String,
        public val unescapedName: String,
        public var role: SymbolRole = SymbolRole.UNCLASSIFIED,
        public var targetCppName: String? = null,
        override var flags: Long = PacketFlags.IS_SIGNIFICANT,
    ) : SyntaxPacket()

    /** Hard or contextual/soft keyword token. */
    public data class KeywordPacket(
        override val packetIndex: Int,
        override val span: PacketSpan,
        override val rawText: String,
        public val keywordId: Int,
        override var flags: Long = PacketFlags.IS_SIGNIFICANT,
    ) : SyntaxPacket()

    /** Numeric, character, boolean, or `null` literal with preserved width/radix metadata. */
    public data class LiteralPacket(
        override val packetIndex: Int,
        override val span: PacketSpan,
        override val rawText: String,
        public val literalKind: LiteralPacketKind,
        public val radix: Int = 10,
        public val numericWidthBits: Int = 32,
        public val isUnsigned: Boolean = false,
        override var flags: Long = PacketFlags.IS_SIGNIFICANT,
    ) : SyntaxPacket()

    /** String template segment (`"`, `"""`, `$`, `${`, literal text, escape, `}`). */
    public data class StringSegmentPacket(
        override val packetIndex: Int,
        override val span: PacketSpan,
        override val rawText: String,
        public val segmentKind: StringSegmentKind,
        override var flags: Long = PacketFlags.IS_SIGNIFICANT,
    ) : SyntaxPacket()

    /** Single- or multi-character operator (`?.`, `?:`, `!!`, `->`, `::`, `..<`, etc.). */
    public data class OperatorPacket(
        override val packetIndex: Int,
        override val span: PacketSpan,
        override val rawText: String,
        public val operatorId: Int,
        override var flags: Long = PacketFlags.IS_SIGNIFICANT,
    ) : SyntaxPacket()

    /** Grouping or punctuation delimiter (`(`, `)`, `{`, `}`, `[`, `]`, `<`, `>`, `,`, `.`, `:`, `;`, `@`). */
    public data class DelimiterPacket(
        override val packetIndex: Int,
        override val span: PacketSpan,
        override val rawText: String,
        public val delimiterId: Int,
        public val matchingPacketIndex: Int = -1,
        override var flags: Long = PacketFlags.IS_SIGNIFICANT,
    ) : SyntaxPacket()
}
