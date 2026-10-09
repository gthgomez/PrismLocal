package com.prismai.llmhost.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunkerTest {

    @Test
    fun shortDocumentDoesNotDuplicateTail() {
        val text = "x".repeat(100)
        val chunks = DocumentChunker.chunk(text, chunkSize = 512, overlap = 64)
        assertEquals("chunking 100 chars at chunkSize 512 must produce exactly 1 chunk", 1, chunks.size)
        assertEquals(text, chunks[0].text)
    }

    @Test
    fun boundarySizesProduceNoRedundantTerminalChunks() {
        val testSizes = listOf(1, 63, 64, 65, 100, 512, 513)
        for (size in testSizes) {
            val text = "a".repeat(size)
            val chunks = DocumentChunker.chunk(text, chunkSize = 512, overlap = 64)
            if (size <= 512) {
                assertEquals("Text of size $size should produce exactly 1 chunk", 1, chunks.size)
                assertEquals(text, chunks[0].text)
            } else {
                assertEquals("Text of size $size should produce 2 chunks", 2, chunks.size)
                assertEquals(0, chunks[0].index)
                assertEquals(1, chunks[1].index)
                // Coverage: last chunk ends with end of text
                assertTrue(chunks[1].text.endsWith("a"))
                // Nonfinal chunk had overlap, but final chunk is not a duplicate
                assertTrue(chunks[0].text.length <= 512)
            }
        }
    }

    @Test
    fun paragraphBoundaryWithOverlapMaintainsOrderAndCoverage() {
        val p1 = "First paragraph content that is long enough to occupy substantial space.\n\n"
        val p2 = "Second paragraph content with details.\n\n"
        val p3 = "Third paragraph content wrapping up."
        val fullText = p1 + p2 + p3
        val chunks = DocumentChunker.chunk(fullText, chunkSize = 80, overlap = 16)
        assertTrue(chunks.isNotEmpty())
        for (i in 0 until chunks.size - 1) {
            assertEquals(i, chunks[i].index)
        }
        // Assert the last chunk's text covers the end of fullText
        assertTrue(chunks.last().text.endsWith("wrapping up."))
        // No two adjacent chunks are identical
        for (i in 0 until chunks.size - 1) {
            assertTrue(chunks[i].text != chunks[i + 1].text)
        }
    }
}
