package com.prismai.llmhost.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private class RecordingVectorIndex(
        private var identity: EmbeddingIdentity = EmbeddingIdentity("enc-1", VectorStore.EMBEDDING_REVISION),
    ) : VectorIndex {
        val events = mutableListOf<String>()
        var replacedDocumentId: String? = null
        var replacedChunks: List<VectorChunk> = emptyList()
        var replacedIdentity: EmbeddingIdentity? = null

        fun setIdentity(next: EmbeddingIdentity) {
            identity = next
        }

        override fun currentEmbeddingIdentity(): EmbeddingIdentity = identity

        override fun insertBatch(chunks: List<VectorChunk>): List<VectorChunk> {
            events += "insertBatch:${chunks.firstOrNull()?.documentId}"
            return chunks
        }

        override fun replaceDocument(
            documentId: String,
            chunks: List<VectorChunk>,
            identity: EmbeddingIdentity,
        ): List<VectorChunk> {
            events += "replaceDocument:$documentId"
            replacedDocumentId = documentId
            replacedChunks = chunks
            replacedIdentity = identity
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
        override fun setEmbeddingIdentityProvider(provider: () -> EmbeddingIdentity) = Unit
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
        assertEquals(result.embeddedCount, store.replacedChunks.size)
        assertTrue(
            "every replaced chunk must belong to the document",
            store.replacedChunks.all { it.documentId == "doc-1" },
        )
    }

    @Test
    fun failingReIngestLeavesAnExistingCompleteIndexUntouched() = runBlocking {
        val store = RecordingVectorIndex()
        var fail = false
        val rag = RagManager(store, DocumentChunker) { _ ->
            if (fail) FloatArray(0) else FloatArray(4) { 0.1f }
        }

        rag.ingestDocumentWithResult("doc-1", "title", longBody())
        assertEquals("doc-1", store.replacedDocumentId)
        store.events.clear()

        fail = true
        val result = rag.ingestDocumentWithResult("doc-1", "title", longBody())

        assertTrue("a failed re-ingest must not touch the store", store.events.isEmpty())
        assertFalse("a partial set must not report success", result.success)
        assertTrue("the result must say the previous index was preserved", result.preservedPrevious)
        assertTrue(result.failedCount > 0)
        assertEquals(0, result.embeddedCount)
    }

    @Test
    fun oneMismatchedDimensionAbortsTheWholeCommit() = runBlocking {
        val store = RecordingVectorIndex()
        var calls = 0
        val rag = RagManager(store, DocumentChunker) { _ ->
            calls++
            if (calls == 2) FloatArray(3) { 0.1f } else FloatArray(4) { 0.1f }
        }

        val result = rag.ingestDocumentWithResult("doc-1", "title", longBody())

        assertTrue("the body must produce multiple chunks", result.totalChunks >= 2)
        assertEquals(1, result.failedCount)
        assertFalse(result.committed)
        assertTrue("no rows may be written when the set is incomplete", store.events.isEmpty())
    }

    @Test
    fun fullyEmbeddedReIngestCommitsAndMarksSuccess() = runBlocking {
        val store = RecordingVectorIndex()
        val rag = RagManager(store, DocumentChunker) { FloatArray(4) { 0.1f } }

        val result = rag.ingestDocumentWithResult("doc-1", "title", longBody())

        assertTrue(result.success)
        assertTrue(result.committed)
        assertEquals(0, result.failedCount)
        assertEquals(listOf("replaceDocument:doc-1"), store.events)
    }

    // ── B: commit-aware counts ─────────────────────────────────────────

    @Test
    fun ingestDocumentReportsZeroWhenAPartialSetIsRejected() = runBlocking {
        val store = RecordingVectorIndex()
        var calls = 0
        val rag = RagManager(store, DocumentChunker) { _ ->
            calls++
            if (calls == 2) FloatArray(0) else FloatArray(4) { 0.1f }
        }

        val committed = rag.ingestDocument("doc-1", "title", longBody())

        assertEquals("a rejected partial set commits nothing and must report 0", 0, committed)
        assertTrue("nothing may be written", store.events.isEmpty())
    }

    // ── C: encoder identity is snapshotted for the whole ingest ────────

    @Test
    fun rowsAreStampedWithTheEncoderSnapshottedBeforeEncoding() = runBlocking {
        val snapshot = EmbeddingIdentity("enc-A", VectorStore.EMBEDDING_REVISION)
        val store = RecordingVectorIndex(snapshot)
        val rag = RagManager(store, DocumentChunker) { FloatArray(4) { 0.1f } }

        val result = rag.ingestDocumentWithResult("doc-1", "title", longBody())

        assertTrue(result.committed)
        assertEquals("every row must carry the snapshot encoder", snapshot, store.replacedIdentity)
    }

    @Test
    fun ingestAbortsWithoutWritingWhenTheEncoderChangesMidIngest() = runBlocking {
        val store = RecordingVectorIndex(EmbeddingIdentity("enc-A", VectorStore.EMBEDDING_REVISION))
        var calls = 0
        val rag = RagManager(store, DocumentChunker) { _ ->
            calls++
            if (calls == 2) store.setIdentity(EmbeddingIdentity("enc-B", VectorStore.EMBEDDING_REVISION))
            FloatArray(4) { 0.1f }
        }

        val result = rag.ingestDocumentWithResult("doc-1", "title", longBody())

        assertTrue("a mid-ingest encoder change must be reported", result.encoderChanged)
        assertFalse(result.committed)
        assertTrue("mixed-encoder rows must never be written", store.events.isEmpty())
    }

    @Test
    fun ingestAbortsWhenModelSwitchesAndReturnsToSameEncoderMidIngest() = runBlocking {
        val initialIdentity = EmbeddingIdentity("enc-A", VectorStore.EMBEDDING_REVISION, epoch = 1L)
        val store = RecordingVectorIndex(initialIdentity)
        var calls = 0
        val rag = RagManager(store, DocumentChunker) { _ ->
            calls++
            if (calls == 2) {
                // Encoder switches to B (epoch 2), then back to A (epoch 3)
                store.setIdentity(EmbeddingIdentity("enc-A", VectorStore.EMBEDDING_REVISION, epoch = 3L))
            }
            FloatArray(4) { 0.1f }
        }

        val result = rag.ingestDocumentWithResult("doc-1", "title", longBody())

        assertTrue("an A -> B -> A model transition must trigger encoderChanged abort", result.encoderChanged)
        assertFalse(result.committed)
        assertTrue("no rows may be written across epoch change", store.events.isEmpty())
    }

    // ── D: cancellation is not swallowed ───────────────────────────────

    @Test
    fun cancellationDuringEncodingPropagatesAndStopsFurtherChunks() = runBlocking {
        val store = RecordingVectorIndex()
        var encodeCalls = 0
        val rag = RagManager(store, DocumentChunker) { _ ->
            encodeCalls++
            throw CancellationException("cancelled by test")
        }

        var cancelled = false
        try {
            rag.ingestDocumentWithResult("doc-1", "title", longBody())
        } catch (e: CancellationException) {
            cancelled = true
        }

        assertTrue("cancellation must propagate out of ingest", cancelled)
        assertEquals("only the first chunk may be encoded before cancellation", 1, encodeCalls)
        assertTrue("a cancelled ingest may not commit", store.events.isEmpty())
    }

    // ── E: bounded ingest size ─────────────────────────────────────────

    @Test
    fun oversizedDocumentIsRejectedWithoutEncoding() = runBlocking {
        val store = RecordingVectorIndex()
        var encodeCalls = 0
        val rag = RagManager(store, DocumentChunker) { encodeCalls++; FloatArray(4) { 0.1f } }

        val result = rag.ingestDocumentWithResult(
            "doc-1",
            "title",
            "x".repeat(RagManager.MAX_INGEST_CHARS + 1),
        )

        assertTrue(result.tooLarge)
        assertFalse(result.committed)
        assertEquals("an oversized document must never reach the encoder", 0, encodeCalls)
        assertTrue(store.events.isEmpty())
        assertEquals(0, rag.ingestDocument("doc-1", "title", "x".repeat(RagManager.MAX_INGEST_CHARS + 1)))
    }

    // ── Messaging for the new abort reasons ────────────────────────────

    @Test
    fun messagingDistinguishesTooLargeAndEncoderChanged() {
        assertEquals(
            "Document is too large to index in one pass; split it into smaller documents",
            com.prismai.llmhost.ui.rag.DocumentIngestMessaging.describe(0, 0, 0, false, tooLarge = true),
        )
        assertTrue(
            com.prismai.llmhost.ui.rag.DocumentIngestMessaging
                .describe(0, 0, 0, false, encoderChanged = true)
                .contains("model changed"),
        )
    }

    private fun longBody(): String = "Sentence number one is reasonably long. ".repeat(40)
}
