package com.prismai.llmhost.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-pure coverage for the query-length clamp. [RagManager] takes its encoder
 * as a function and its store through [VectorIndex], so no model and no SQLite
 * database are needed.
 */
class RagQueryClampTest {

    private class FakeVectorStore : VectorIndex {
        override fun insertBatch(chunks: List<VectorChunk>): List<VectorChunk> = chunks

        override fun replaceDocument(
            documentId: String,
            chunks: List<VectorChunk>,
        ): List<VectorChunk> = chunks

        override fun search(
            queryEmbedding: FloatArray,
            topK: Int,
            minScore: Float,
        ): List<Pair<VectorChunk, Float>> = emptyList()

        override fun deleteByDocument(documentId: String): Int = 0
        override fun documentCount(): Int = 0
        override fun chunkCount(): Int = 0
        override fun clear() = Unit
    }

    @Test
    fun query_encodesTextClampedToBound() = runBlocking {
        val encoded = mutableListOf<String>()
        // encode is injected, so this asserts the clamp without a model.
        val rag = RagManager(FakeVectorStore(), DocumentChunker) { text ->
            encoded += text
            FloatArray(4) { 0.1f }
        }

        val huge = "x".repeat(DocumentChunker.QUERY_MAX_CHARS * 3)
        rag.query(huge, topK = 1)

        assertTrue("expected a clamp to have been applied", encoded.isNotEmpty())
        assertTrue(
            "encoded text exceeded the bound: ${encoded.first().length}",
            encoded.all { it.length <= DocumentChunker.QUERY_MAX_CHARS },
        )
    }

    @Test
    fun query_leavesShortTextIntact() = runBlocking {
        val encoded = mutableListOf<String>()
        val rag = RagManager(FakeVectorStore(), DocumentChunker) { text ->
            encoded += text
            FloatArray(4) { 0.1f }
        }

        rag.query("short question", topK = 1)

        assertEquals(listOf("short question"), encoded)
    }
}
