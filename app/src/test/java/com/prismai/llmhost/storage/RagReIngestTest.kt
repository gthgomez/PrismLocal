package com.prismai.llmhost.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: re-ingesting a document must REPLACE its previous rows rather than
 * appending a second set alongside them. [RagManager] deletes the document's
 * existing rows before inserting the newly embedded batch.
 *
 * JVM-pure: [RagManager] takes its store through [VectorIndex] and its encoder
 * as a function, so no SQLite database or model is required.
 */
class RagReIngestTest {

    /** Records the order in which the store is mutated. */
    private class RecordingVectorIndex : VectorIndex {
        val events = mutableListOf<String>()

        override fun insertBatch(chunks: List<VectorChunk>): List<VectorChunk> {
            events += "insertBatch:${chunks.firstOrNull()?.documentId}"
            return chunks
        }

        override fun search(
            queryEmbedding: FloatArray,
            topK: Int,
            minScore: Float,
        ): List<Pair<VectorChunk, Float>> = emptyList()

        override fun deleteByDocument(documentId: String): Int {
            events += "deleteByDocument:$documentId"
            return 1
        }

        override fun documentCount(): Int = 0
        override fun chunkCount(): Int = 0
        override fun clear() {}
    }

    @Test
    fun reIngestDeletesPriorRowsBeforeInsertingNewOnes() = runBlocking {
        val store = RecordingVectorIndex()
        val rag = RagManager(store, DocumentChunker) { FloatArray(4) { 0.1f } }

        rag.ingestDocumentWithResult("doc-1", "title", "some document body text")

        assertEquals(
            "re-ingest must clear the document's prior rows before the new batch",
            listOf("deleteByDocument:doc-1", "insertBatch:doc-1"),
            store.events,
        )
    }

    @Test
    fun blankDocumentDoesNotDeleteOrInsert() = runBlocking {
        val store = RecordingVectorIndex()
        val rag = RagManager(store, DocumentChunker) { FloatArray(4) { 0.1f } }

        rag.ingestDocumentWithResult("doc-1", "title", "   ")

        assertTrue("blank ingest is a no-op", store.events.isEmpty())
    }
}
