package com.prismai.llmhost.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SelectedDocumentQueryTest {
    private class Index : VectorIndex {
        var identity = EmbeddingIdentity("model-a", VectorStore.EMBEDDING_REVISION)
        var requestedDocument: String? = null
        var searchCalls = 0
        val selected = VectorChunk("a", "cedar", 0, "Owner: Mira Chen", floatArrayOf(1f))

        override fun currentEmbeddingIdentity() = identity
        override fun insertBatch(chunks: List<VectorChunk>) = chunks
        override fun replaceDocument(documentId: String, chunks: List<VectorChunk>, identity: EmbeddingIdentity) = chunks
        override fun search(queryEmbedding: FloatArray, topK: Int, minScore: Float) =
            error("A selected-document query must never use the global search")
        override fun searchDocument(
            documentId: String,
            queryEmbedding: FloatArray,
            topK: Int,
            minScore: Float,
            expectedIdentity: EmbeddingIdentity,
        ): DocumentSearchResult {
            requestedDocument = documentId
            searchCalls++
            assertEquals(identity, expectedIdentity)
            return DocumentSearchResult("snapshot", listOf(selected to 0.8f))
        }
        override fun deleteByDocument(documentId: String) = 0
        override fun documentCount() = 0
        override fun chunkCount() = 0
        override fun clear() = Unit
        override fun setEmbeddingIdentityProvider(provider: () -> EmbeddingIdentity) = Unit
    }

    @Test
    fun queryPassesSelectionAndEncoderSnapshotToScopedSearch() = runBlocking {
        val index = Index()
        val rag = RagManager(index, DocumentChunker) { floatArrayOf(1f) }

        val result = rag.queryDocument("Who owns the project?", "cedar", topK = 1)

        assertEquals("cedar", index.requestedDocument)
        assertEquals("snapshot", result.documentVersion)
        assertEquals(listOf("cedar"), result.matches.map { it.first.documentId })
    }

    @Test
    fun changedEncoderAndFailedEmbeddingRefuseWithoutSearching() {
        val index = Index()
        val switched = RagManager(index, DocumentChunker) {
            index.identity = index.identity.copy(epoch = 1)
            floatArrayOf(1f)
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { switched.queryDocument("owner?", "cedar") }
        }
        val failed = RagManager(index, DocumentChunker) { floatArrayOf() }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { failed.queryDocument("owner?", "cedar") }
        }
        assertEquals(0, index.searchCalls)
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingAnEmptyResult() {
        val index = Index()
        val rag = RagManager(index, DocumentChunker) { throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) {
            runBlocking { rag.queryDocument("owner?", "cedar") }
        }
        assertEquals(0, index.searchCalls)
    }
}
