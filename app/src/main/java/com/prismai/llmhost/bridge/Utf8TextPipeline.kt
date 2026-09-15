package com.prismai.llmhost.bridge
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

/**
 * Incremental UTF-8 decoder for the streaming text path.
 *
 * The native layer detokenizes each drain independently, so a single multi-byte
 * code point can be split across two [append] calls (e.g. the lead byte arrives
 * on drain N and the continuation byte(s) on drain N+1). Decoding each drain in
 * isolation turns both fragments into U+FFFD and the real character is lost.
 *
 * One instance must be used per generation. [append] decodes as many complete
 * code points as possible and buffers an incomplete trailing sequence (1-3
 * bytes) until the next call. [flush] finalizes the stream: a genuinely
 * truncated trailing sequence yields replacement characters per the standard
 * UTF-8 REPLACE policy; complete code points are never dropped or normalized.
 *
 * Decoding is implemented directly over the bytes and mirrors the JVM UTF-8
 * REPLACE policy (maximal malformed subparts, surrogate-triplet handling) so
 * invalid input renders identically to `String(bytes, UTF_8)`. Delegating to
 * `String(bytes, UTF_8)` is not possible here because the trailing partial
 * sequence must be carried across calls.
 *
 * The legacy [normalizeNativeText]/[normalizeChunk] helpers live in the
 * companion so existing call sites keep compiling; they are display-time
 * filtering and are intentionally NOT applied to this decoder's output.
 */
internal class Utf8TextPipeline {

    /** Trailing bytes of an incomplete multi-byte sequence, carried across calls. */
    private val pending = ByteArray(MAX_PENDING_BYTES)
    private var pendingLength = 0

    /**
     * Decode [length] bytes from [bytes] (defaults to the whole array). Returns
     * every complete code point that can be decoded now; an incomplete trailing
     * sequence is retained for the next call. Never mutates [bytes].
     */
    @JvmOverloads
    fun append(bytes: ByteArray, length: Int = bytes.size): String {
        require(length in 0..bytes.size) {
            "length $length out of bounds for ${bytes.size} bytes"
        }
        if (length == 0) return ""

        if (pendingLength == 0) {
            return decode(bytes, 0, length, final = false)
        }

        val combined = ByteArray(pendingLength + length)
        System.arraycopy(pending, 0, combined, 0, pendingLength)
        System.arraycopy(bytes, 0, combined, pendingLength, length)
        pendingLength = 0
        return decode(combined, 0, combined.size, final = false)
    }

    /**
     * Finalize the stream. Emits any buffered trailing bytes. A truncated
     * sequence produces exactly one U+FFFD (per the maximal-subpart policy)
     * unless earlier bytes of the buffer are themselves invalid, in which case
     * the standard per-subpart replacement count applies. Clears the buffer, so
     * a second call returns "".
     */
    fun flush(): String {
        if (pendingLength == 0) return ""
        val trailing = pending.copyOf(pendingLength)
        pendingLength = 0
        return decode(trailing, 0, trailing.size, final = true)
    }

    private fun decode(bytes: ByteArray, start: Int, end: Int, final: Boolean): String {
        val sb = StringBuilder(end - start)
        var i = start
        while (i < end) {
            val lead = bytes[i].toInt() and 0xFF

            if (lead < 0x80) {
                sb.append(lead.toChar())
                i++
                continue
            }

            val need = sequenceLength(lead)
            if (need == 0) {
                // Stray continuation byte or invalid lead (0x80-0xC1, 0xF5-0xFF).
                sb.append(REPLACEMENT)
                i++
                continue
            }

            if (i + need > end) {
                if (!final) {
                    // Incomplete trailing sequence: retain raw bytes verbatim.
                    val remaining = end - i
                    System.arraycopy(bytes, i, pending, 0, remaining)
                    pendingLength = remaining
                    return sb.toString()
                }
                // End of stream: consume the maximal malformed unit.
                val consume = truncatedMalformedLength(bytes, i, end, need).coerceAtLeast(1)
                sb.append(REPLACEMENT)
                i += consume
                continue
            }

            val malformed = malformedLength(bytes, i, need)
            if (malformed != 0) {
                sb.append(REPLACEMENT)
                i += malformed
                continue
            }

            appendCodePoint(sb, codePoint(bytes, i, need))
            i += need
        }
        return sb.toString()
    }

    /** Expected total sequence length for a valid lead byte, or 0 if invalid. */
    private fun sequenceLength(lead: Int): Int = when (lead) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0
    }

    /**
     * Validates a complete [need]-byte sequence at [start]. Returns 0 when the
     * sequence is valid, otherwise the number of bytes (1..need) forming the
     * maximal malformed subpart. Mirrors the JVM REPLACE policy, notably:
     *  - E0 80..9F overlong, F0 80..8F overlong and F4 90..BF out-of-range
     *    consume only the lead byte;
     *  - a non-continuation byte ends the subpart at the previous byte;
     *  - a 3-byte UTF-16 surrogate (ED A0..BF xx) consumes the whole triplet.
     */
    private fun malformedLength(bytes: ByteArray, start: Int, need: Int): Int {
        val b0 = bytes[start].toInt() and 0xFF
        val b1 = bytes[start + 1].toInt() and 0xFF
        return when (need) {
            2 -> if (b1 and 0xC0 != 0x80) 1 else 0
            3 -> {
                if (b0 == 0xE0 && (b1 and 0xE0) == 0x80) 1
                else if (b1 and 0xC0 != 0x80) 1
                else {
                    val b2 = bytes[start + 2].toInt() and 0xFF
                    when {
                        b2 and 0xC0 != 0x80 -> 2
                        b0 == 0xED && b1 >= 0xA0 -> 3 // surrogate → one replacement
                        else -> 0
                    }
                }
            }
            else -> {
                if (b0 == 0xF0 && (b1 < 0x90 || b1 > 0xBF)) 1
                else if (b0 == 0xF4 && (b1 and 0xF0) != 0x80) 1
                else if (b1 and 0xC0 != 0x80) 1
                else {
                    val b2 = bytes[start + 2].toInt() and 0xFF
                    if (b2 and 0xC0 != 0x80) 2
                    else {
                        val b3 = bytes[start + 3].toInt() and 0xFF
                        if (b3 and 0xC0 != 0x80) 3 else 0
                    }
                }
            }
        }
    }

    /**
     * Handles the final, truncated sequence at end of stream. Mirrors the JVM
     * decoder's behaviour when it runs out of input mid-sequence: if an invalid
     * continuation is already detectable, only the maximal subpart is
     * consumed; otherwise the whole available suffix is consumed and emitted as
     * a single U+FFFD. The returned value is always >= 1 so the caller's index
     * strictly advances.
     */
    private fun truncatedMalformedLength(bytes: ByteArray, start: Int, end: Int, need: Int): Int {
        val available = end - start
        val b0 = bytes[start].toInt() and 0xFF
        if (available < 2) return available
        val b1 = bytes[start + 1].toInt() and 0xFF
        if (need == 3) {
            if (b0 == 0xE0 && (b1 and 0xE0) == 0x80) return 1
            if (b1 and 0xC0 != 0x80) return 1
            return available
        }
        // need == 4
        if (b0 == 0xF0 && (b1 < 0x90 || b1 > 0xBF)) return 1
        if (b0 == 0xF4 && (b1 and 0xF0) != 0x80) return 1
        if (b1 and 0xC0 != 0x80) return 1
        if (available < 3) return available
        val b2 = bytes[start + 2].toInt() and 0xFF
        if (b2 and 0xC0 != 0x80) return 2
        return available
    }

    /** Assumes the sequence at [start] has already been validated. */
    private fun codePoint(bytes: ByteArray, start: Int, need: Int): Int {
        val b0 = bytes[start].toInt()
        return when (need) {
            2 -> ((b0 and 0x1F) shl 6) or (bytes[start + 1].toInt() and 0x3F)
            3 -> ((b0 and 0x0F) shl 12) or
                ((bytes[start + 1].toInt() and 0x3F) shl 6) or
                (bytes[start + 2].toInt() and 0x3F)
            else -> ((b0 and 0x07) shl 18) or
                ((bytes[start + 1].toInt() and 0x3F) shl 12) or
                ((bytes[start + 2].toInt() and 0x3F) shl 6) or
                (bytes[start + 3].toInt() and 0x3F)
        }
    }

    /** Encodes supplementary code points as a UTF-16 surrogate pair. */
    private fun appendCodePoint(sb: StringBuilder, codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            sb.append(codePoint.toChar())
        } else {
            val v = codePoint - 0x10000
            sb.append(((v ushr 10) + 0xD800).toChar())
            sb.append(((v and 0x3FF) + 0xDC00).toChar())
        }
    }

    companion object {
        private const val REPLACEMENT = '\uFFFD'

        /** An incomplete 4-byte sequence carries at most 3 trailing bytes. */
        private const val MAX_PENDING_BYTES = 3

        fun normalizeNativeText(text: String): String {
            if (text.isEmpty()) return text
            // Fast path: check if filtering is even needed
            if (text.all { it == '\t' || it == '\n' || it == '\r' || (!it.isISOControl() && it != '\u0000' && it != '\uFFFD') }) {
                return text
            }
            return buildString(text.length) {
                for (char in text) {
                    when {
                        char == '\u0000' || char == '\uFFFD' -> Unit
                        char == '\t' || char == '\n' || char == '\r' -> append(char)
                        !char.isISOControl() -> append(char)
                    }
                }
            }
        }

        fun normalizeChunk(chunk: GenerationChunk): GenerationChunk {
            val normalized = normalizeNativeText(chunk.text)
            return if (normalized == chunk.text) {
                chunk
            } else {
                chunk.copy(text = normalized)
            }
        }
    }
}
