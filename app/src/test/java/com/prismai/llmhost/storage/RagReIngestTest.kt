package com.prismai.llmhost.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: re-ingesting a document must REPLACE its previous rows rather than
 * appending a second set alongside them, and it must do so atomically.
 *
 * [RagManager] previously called `deleteByDocument` then `insertBatch` as two
 * separately-locked operations: a concurrent search could see the rows missing,
 * and a failure in insert lost the previous index. It now makes a single
 * [VectorIndex.replaceDocument] call, which the store executes in one
 * transaction.
 *
 * JVM-pure: [RagManager] takes its store through [VectorIndex] and its encoder
 * as a function, so no SQLite database or model is required.
 */
class RagReIngestTest {

    /** Records the store mutations [RagManager] performs. */
    private class RecordingVectorIndex : VectorIndex {
        val events = mutableListOf<String>()
        var replacedDocumentId: String? = null
        var replacedChunks: List<VectorChunk> = emptyList()

        override fun insertBatch(chunks: List<VectorChunk>): List<VectorChunk> {
            events += "insertBatch:${chunks.firstOrNull()?.documentId}"
            return chunks
        }

        override fun replaceDocument(
            documentId: String,
            chunks: List<VectorChunk>,
        ): List<VectorChunk> {
            events += "replaceDocument:$documentId"
            replacedDocumentId = documentId
            replacedChunks = chunks
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
    fun reIngestReplacesPriorRowsInASingleAtomicCall() = runBlocking {
        val store = RecordingVectorIndex()
        val rag = RagManager(store, DocumentChunker) { FloatArray(4) { 0.1f } }

        rag.ingestDocumentWithResult("doc-1", "title", "some document body text")

        assertEquals(
            "re-ingest must be one atomic replace, not a separate delete then insert",
            listOf("replaceDocument:doc-1"),
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

    @Test
    fun replaceDocumentReceivesEveryStoredChunkForTheDocument() = runBlocking {
        val store = RecordingVectorIndex()
        val rag = RagManager(store, DocumentChunker) { FloatArray(4) { 0.1f } }

        val result = rag.ingestDocumentWithResult("doc-1", "title", "some document body text")

        assertEquals("doc-1", store.replacedDocumentId)
        assertEquals(result.storedCount, store.replacedChunks.size)
        assertTrue(
            "every replaced chunk must belong to the document",
            store.replacedChunks.all { it.documentId == "doc-1" },
        )
    }
}
