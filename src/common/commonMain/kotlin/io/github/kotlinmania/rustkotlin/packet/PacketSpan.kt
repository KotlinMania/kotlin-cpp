package io.github.kotlinmania.rustkotlin.packet

/**
 * Exact UTF-16, UTF-8 byte, and 1-based line/column coordinates for a leaf [SyntaxPacket]
 * or composite [PacketFrame] within a source file.
 */
public data class PacketSpan(
    public val startOffset: Int,
    public val endOffset: Int,
    public val startByteOffset: Int,
    public val endByteOffset: Int,
    public val startLine: Int,
    public val startColumn: Int,
    public val endLine: Int,
    public val endColumn: Int,
) {
    public val utf16Length: Int
        get() = endOffset - startOffset

    public val utf8ByteLength: Int
        get() = endByteOffset - startByteOffset
}

/**
 * Single-pass O(N) lookup table mapping any UTF-16 code-unit offset in `0..source.length`
 * to its exact UTF-8 byte offset and 1-based `(line, column)` coordinates.
 */
internal class SourceOffsetTable(
    source: CharSequence,
) {
    private val length: Int = source.length
    private val byteOffsets: IntArray = IntArray(length + 1)
    private val lines: IntArray = IntArray(length + 1)
    private val columns: IntArray = IntArray(length + 1)

    init {
        var byteCursor = 0
        var line = 1
        var col = 1
        var i = 0
        while (i < length) {
            byteOffsets[i] = byteCursor
            lines[i] = line
            columns[i] = col

            val ch = source[i]
            if (ch.isHighSurrogate() && i + 1 < length && source[i + 1].isLowSurrogate()) {
                // 4-byte UTF-8 supplementary code point spanning two UTF-16 code units.
                byteOffsets[i + 1] = byteCursor + 2
                lines[i + 1] = line
                columns[i + 1] = col + 1
                byteCursor += 4
                col += 2
                i += 2
                continue
            }

            byteCursor +=
                when {
                    ch.code <= 0x7F -> 1
                    ch.code <= 0x7FF -> 2
                    else -> 3
                }

            if (ch == '\n') {
                line += 1
                col = 1
            } else {
                col += 1
            }
            i += 1
        }
        byteOffsets[length] = byteCursor
        lines[length] = line
        columns[length] = col
    }

    fun spanOf(
        startOffset: Int,
        endOffset: Int,
    ): PacketSpan {
        val clampedStart = startOffset.coerceIn(0, length)
        val clampedEnd = endOffset.coerceIn(clampedStart, length)
        return PacketSpan(
            startOffset = clampedStart,
            endOffset = clampedEnd,
            startByteOffset = byteOffsets[clampedStart],
            endByteOffset = byteOffsets[clampedEnd],
            startLine = lines[clampedStart],
            startColumn = columns[clampedStart],
            endLine = lines[clampedEnd],
            endColumn = columns[clampedEnd],
        )
    }
}
