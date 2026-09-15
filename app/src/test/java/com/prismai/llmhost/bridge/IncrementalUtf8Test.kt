package com.prismai.llmhost.bridge

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Plain-JVM tests for [Utf8TextPipeline]'s incremental decoder. No Android
 * dependencies: the pipeline is a pure byte->text transform.
 *
 * The core invariant is that feeding a UTF-8 byte stream in arbitrary chunks and
 * then calling [Utf8TextPipeline.flush] reconstructs the canonical string
 * exactly, including when a multi-byte code point straddles a chunk boundary.
 */
class IncrementalUtf8Test {

    /**
     * Feeds [text] as exactly two chunks, splitting at every byte boundary, and
     * asserts the concatenation of both appends plus flush equals [text].
     */
    private fun assertReconstructsAtEverySplit(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        for (split in 0..bytes.size) {
            val pipeline = Utf8TextPipeline()
            val head = pipeline.append(bytes, split)
            val tail = pipeline.append(bytes.copyOfRange(split, bytes.size))
            val flushed = pipeline.flush()
            assertEquals(
                "split=$split expected=<$text> got=<${head + tail + flushed}>",
                text,
                head + tail + flushed,
            )
        }
    }

    // ---- 2-byte character (U+00E9, "é" = C3 A9) ----
    @Test
    fun reconstructsTwoByteCharacterAtEverySplit() {
        assertReconstructsAtEverySplit("caf\u00E9")
    }

    // ---- 3-byte character (CJK, "漢字" = E6 BC A2 E5 AD 97) ----
    @Test
    fun reconstructsThreeByteCharacterAtEverySplit() {
        assertReconstructsAtEverySplit("\u6F22\u5B57")
    }

    // ---- 4-byte emoji (U+1F600, "😀" = F0 9F 98 80) ----
    @Test
    fun reconstructsFourByteEmojiAtEverySplit() {
        assertReconstructsAtEverySplit("a\uD83D\uDE00b")
    }

    // ---- combining-mark sequence (e + U+0301 + U+0323) ----
    @Test
    fun reconstructsCombiningMarksAtEverySplit() {
        assertReconstructsAtEverySplit("e\u0301\u0323")
    }

    // ---- mixed ASCII + multi-byte + supplementary ----
    @Test
    fun reconstructsMixedAsciiAtEverySplit() {
        assertReconstructsAtEverySplit("Hello, \u4E16\u754C! caf\u00E9 \uD83D\uDE80 done.")
    }

    @Test
    fun byteByByteFeedingReconstructsText() {
        val text = "a\u00E9\u6F22\uD83D\uDE00e\u0301z"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val pipeline = Utf8TextPipeline()
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(pipeline.append(byteArrayOf(b)))
        }
        sb.append(pipeline.flush())
        assertEquals(text, sb.toString())
    }

    // ---- incomplete sequence is buffered, not emitted early ----
    @Test
    fun incompleteSequenceIsHeldUntilCompleted() {
        val pipeline = Utf8TextPipeline()
        // "é" split between its two bytes.
        assertEquals("", pipeline.append(byteArrayOf(0xC3.toByte())))
        assertEquals("\u00E9", pipeline.append(byteArrayOf(0xA9.toByte())))
        // "😀" split after its third byte.
        assertEquals("", pipeline.append(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte())))
        assertEquals("\uD83D\uDE00", pipeline.append(byteArrayOf(0x80.toByte())))
        assertEquals("", pipeline.flush())
    }

    // ---- flush emits a genuinely truncated trailing sequence ----
    @Test
    fun flushEmitsTruncatedSequenceAsSingleReplacement() {
        val pipeline = Utf8TextPipeline()
        // E2 82 is a valid prefix of "€" (E2 82 AC) but the stream ended.
        assertEquals("", pipeline.append(byteArrayOf(0xE2.toByte(), 0x82.toByte())))
        assertEquals("\uFFFD", pipeline.flush())
        // Buffer cleared: second flush is a no-op.
        assertEquals("", pipeline.flush())
    }

    @Test
    fun flushWithEmptyBufferReturnsEmptyString() {
        val pipeline = Utf8TextPipeline()
        assertEquals("", pipeline.append(ByteArray(0)))
        assertEquals("", pipeline.flush())
    }

    @Test
    fun flushPreservesLeadingValidTextBeforeTruncatedTail() {
        val pipeline = Utf8TextPipeline()
        val bytes = "ok".toByteArray(StandardCharsets.UTF_8) +
            byteArrayOf(0xF0.toByte(), 0x9F.toByte()) // truncated emoji
        assertEquals("ok", pipeline.append(bytes))
        assertEquals("\uFFFD", pipeline.flush())
    }

    // ---- literal U+FFFD bytes (EF BF BD) must round-trip, split or not ----
    @Test
    fun literalReplacementCharacterBytesArePreserved() {
        val text = "a\uFFFDb"
        assertReconstructsAtEverySplit(text)
        val pipeline = Utf8TextPipeline()
        assertEquals(text, pipeline.append(text.toByteArray(StandardCharsets.UTF_8)))
    }

    // ---- invalid bytes: replacement policy must match the JVM's ----
    @Test
    fun invalidByteSequencesMatchJvmReplacementPolicy() {
        val cases = listOf(
            byteArrayOf(0x80.toByte()), // lone continuation byte
            byteArrayOf(0xC0.toByte(), 0x80.toByte()), // overlong 2-byte lead
            byteArrayOf(0xC1.toByte(), 0xBF.toByte()), // overlong 2-byte lead
            byteArrayOf(0xE2.toByte(), 0x28.toByte(), 0xA1.toByte()), // bad continuation
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()), // overlong 3-byte
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // UTF-16 surrogate triplet
            byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte()), // overlong 4-byte
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // > U+10FFFF
            byteArrayOf(0xF0.toByte(), 0x90.toByte(), 0x80.toByte(), 0x28.toByte()), // bad 4th byte
            byteArrayOf(0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()), // invalid lead
            byteArrayOf(0x41, 0xFF.toByte(), 0x42), // valid bytes around invalid
            byteArrayOf(0xE2.toByte(), 0x82.toByte()), // truncated 3-byte prefix
            byteArrayOf(0xED.toByte(), 0xA0.toByte()), // truncated surrogate prefix
            byteArrayOf(0xE0.toByte(), 0x80.toByte()), // truncated overlong 3-byte
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte()), // truncated emoji
            byteArrayOf(0xF0.toByte(), 0x90.toByte()), // truncated 4-byte prefix
        )
        for (bytes in cases) {
            val expected = String(bytes, StandardCharsets.UTF_8)
            val pipeline = Utf8TextPipeline()
            val actual = pipeline.append(bytes) + pipeline.flush()
            assertEquals(
                "bytes=${bytes.joinToString(" ") { "%02X".format(it) }}",
                expected,
                actual,
            )
        }
    }

    @Test
    fun invalidByteDoesNotDropSurroundingValidBytes() {
        val pipeline = Utf8TextPipeline()
        val bytes = "A".toByteArray(StandardCharsets.UTF_8) +
            byteArrayOf(0xFF.toByte()) +
            "\u00E9\u6F22".toByteArray(StandardCharsets.UTF_8)
        assertEquals("A\uFFFD\u00E9\u6F22", pipeline.append(bytes) + pipeline.flush())
    }

    @Test
    fun appendHonoursExplicitLengthWithoutMutatingSource() {
        val bytes = "abc".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0xE2.toByte())
        // Only the first three bytes are fed, so the trailing lead byte at
        // index 3 is never seen and nothing is pending.
        val pipeline = Utf8TextPipeline()
        assertEquals("abc", pipeline.append(bytes, 3))
        assertEquals("", pipeline.flush())

        // Feeding the whole array buffers the incomplete trailing lead.
        val pipeline2 = Utf8TextPipeline()
        assertEquals("abc", pipeline2.append(bytes))
        assertEquals("\uFFFD", pipeline2.flush())

        assertEquals(0xE2.toByte(), bytes[3]) // source array is not mutated
    }

    @Test
    fun crossCallInvalidBytesMatchJvmReplacementPolicy() {
        // A sequence started in one append and invalidated by the next must
        // still match String(bytes, UTF_8) exactly.
        val cases = listOf(
            byteArrayOf(0xE0.toByte(), 0x80.toByte()) to byteArrayOf(0x41),
            byteArrayOf(0xE2.toByte(), 0x82.toByte()) to byteArrayOf(0x28),
            byteArrayOf(0xED.toByte(), 0xA0.toByte()) to byteArrayOf(0x80.toByte()),
            byteArrayOf(0xF0.toByte(), 0x90.toByte()) to byteArrayOf(0x28),
            byteArrayOf(0xF4.toByte(), 0x90.toByte()) to byteArrayOf(0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xC3.toByte()) to byteArrayOf(0xC3.toByte()),
        )
        for ((head, tail) in cases) {
            val whole = head + tail
            val expected = String(whole, StandardCharsets.UTF_8)
            val pipeline = Utf8TextPipeline()
            val actual = pipeline.append(head) + pipeline.append(tail) + pipeline.flush()
            assertEquals(
                "bytes=${whole.joinToString(" ") { "%02X".format(it) }}",
                expected,
                actual,
            )
        }
    }

    @Test
    fun truncatedFourByteSequenceWithBadThirdByteAtEof() {
        val bytes = byteArrayOf(0xF0.toByte(), 0x90.toByte(), 0x28)
        val expected = String(bytes, StandardCharsets.UTF_8)
        val pipeline = Utf8TextPipeline()
        assertEquals(expected, pipeline.append(bytes) + pipeline.flush())
    }
}
