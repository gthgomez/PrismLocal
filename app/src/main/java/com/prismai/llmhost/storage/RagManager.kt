package com.prismai.llmhost.storage
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*
import com.prismai.llmhost.BuildConfig

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Orchestrates RAG (Retrieval-Augmented Generation) operations:
 * ingesting documents, querying for relevant chunks, and building
 * context blocks for prompt injection.
 *
 * Thread-safe by delegation to [VectorStore] (ReentrantLock) and
 * [DocumentChunker] (pure stateless).
 */
class RagManager(
    private val vectorStore: VectorIndex,
    private val chunker: DocumentChunker,
    /** Suspending function that returns float embedding for a text string. */
    private val encode: suspend (String) -> FloatArray,
) {
    /**
     * Outcome of an ingestion.
     *
     * [committed] is false when any chunk could not be embedded: the new chunk set is discarded and
     * the document's previous index is left untouched, so a partial failure can never destroy a
     * previously complete index.
     */
    data class IngestResult(
        /** Chunks that embedded successfully. */
        val embeddedCount: Int,
        /** Chunks that could not be embedded. */
        val failedCount: Int,
        /** True when the whole set embedded and the document's rows were replaced atomically. */
        val committed: Boolean,
    ) {
        /** Total chunks attempted (embedded + failed). */
        val totalChunks: Int get() = embeddedCount + failedCount

        /** True only when every chunk embedded and was committed. */
        val success: Boolean get() = committed && failedCount == 0 && embeddedCount > 0

        /** True when an incomplete chunk set was discarded and the previous index was preserved. */
        val preservedPrevious: Boolean get() = !committed && failedCount > 0
    }

    companion object {
        private const val TAG = "RagManager"

        /** Default max tokens for the RAG context block */
        const val DEFAULT_MAX_RAG_TOKENS = 2000

        /** Max characters per chunk when building context (safety limit) */
        private const val MAX_CONTEXT_CHARS = 12_000

        /** Separator between chunks in the context block */
        private const val CHUNK_SEPARATOR = "\n---\n"

        /** Minimum cosine similarity score threshold for RAG retrieval */
        const val DEFAULT_MIN_RAG_SCORE = 0.35f
    }

    /**
     * Ingest a document: split [text] into chunks, encode each chunk,
     * and store in the vector store.
     *
     * @param documentId unique identifier for the source document
     * @param title      human-readable title (logged but not currently stored)
     * @param text       full document text
     * @return number of chunks committed
     */
    suspend fun ingestDocument(documentId: String, title: String, text: String): Int =
        ingestDocumentWithResult(documentId, title, text).embeddedCount

    /**
     * Ingest a document and report both embedded and failed chunk counts.
     *
     * The whole chunk set is embedded and validated first; the store is replaced in one transaction
     * only when every chunk embedded successfully. If any chunk fails (e.g. the native size guard
     * rejects an oversized chunk), nothing is committed and the document's previous index is
     * retained.
     */
    suspend fun ingestDocumentWithResult(documentId: String, title: String, text: String): IngestResult =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) {
                Log.w(TAG, "ingestDocument skipped: empty text documentId=$documentId")
                return@withContext IngestResult(embeddedCount = 0, failedCount = 0, committed = false)
            }

            val chunks = chunker.chunk(text)
            if (chunks.isEmpty()) {
                Log.w(TAG, "ingestDocument no chunks produced documentId=$documentId")
                return@withContext IngestResult(embeddedCount = 0, failedCount = 0, committed = false)
            }

            // Generate the full chunk set first, then validate completeness and dimensions before
            // touching the store.
            val embeddings = chunks.map { chunk ->
                runCatching { encode(chunk.text) }.getOrNull()
            }
            val dimension = embeddings.firstOrNull { it != null && it.isNotEmpty() }?.size ?: 0
            val failedCount = embeddings.count { it == null || it.isEmpty() || it.size != dimension }

            if (failedCount > 0) {
                // Retain the previous index: replacing it with an incomplete set would destroy a
                // previously complete (and still usable) index.
                Log.w(
                    TAG,
                    "ingestDocument aborted documentId=$documentId chunks=${chunks.size} failed=$failedCount " +
                        "previousIndexPreserved=true",
                )
                return@withContext IngestResult(
                    embeddedCount = chunks.size - failedCount,
                    failedCount = failedCount,
                    committed = false,
                )
            }

            val vectorChunks = chunks.mapIndexed { index, chunk ->
                VectorChunk(
                    id = UUID.randomUUID().toString().take(12),
                    documentId = documentId,
                    chunkIndex = chunk.index,
                    text = chunk.text,
                    embedding = checkNotNull(embeddings[index]),
                )
            }
            // Replace rather than mix: a re-ingest must not leave the document's previous rows
            // (e.g. an older chunking or embedding identity) alongside the new batch. The
            // delete+insert is one transaction inside the store, so a concurrent search never sees
            // the document missing and a failed insert cannot lose the previous index.
            vectorStore.replaceDocument(documentId, vectorChunks)

            Log.i(TAG, "ingestDocument documentId=$documentId chunks=${vectorChunks.size} committed=true")
            IngestResult(embeddedCount = vectorChunks.size, failedCount = 0, committed = true)
        }

    /**
     * Query the vector store for chunks similar to [userPrompt].
     *
     * Encodes the [userPrompt], performs cosine similarity search,
     * and returns the top-K matching chunks with scores.
     */
    suspend fun query(userPrompt: String, topK: Int = 5): List<Pair<VectorChunk, Float>> =
        withContext(Dispatchers.IO) {
            if (userPrompt.isBlank()) return@withContext emptyList()

            // Bound the encoded text: Engine::encode runs a real decode pass over
            // every token, so an unbounded user prompt is unbounded work.
            val queryText = userPrompt.take(DocumentChunker.QUERY_MAX_CHARS)
            val queryEmbedding = runCatching {
                encode(queryText)
            }.getOrNull()

            if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "query failed: empty embedding for prompt=${userPrompt.take(80)}")
                }
                return@withContext emptyList()
            }

            vectorStore.search(queryEmbedding, topK = topK, minScore = DEFAULT_MIN_RAG_SCORE)
        }

    /**
     * Build a formatted context string from retrieved chunks for prompt injection.
     *
     * @param chunks    retrieved (chunk, score) pairs
     * @param maxChars  approximate character budget for the context block
     * @return formatted context string, or empty string if [chunks] is empty
     */
    fun buildRagContext(chunks: List<Pair<VectorChunk, Float>>, maxChars: Int = MAX_CONTEXT_CHARS): String {
        if (chunks.isEmpty()) return ""

        val safeMax = maxChars.coerceIn(256, 24_000)
        val sb = StringBuilder()
        sb.appendLine("Relevant document context:")
        sb.appendLine()

        for ((chunk, score) in chunks) {
            val entry = buildString {
                appendLine("[score=${"%.3f".format(score)}] [source=${chunk.documentId}]")
                appendLine(chunk.text)
            }

            if (sb.length + entry.length > safeMax && sb.length > 256) {
                // Budget exhausted; stop adding
                break
            }
            sb.append(entry)
            sb.appendLine(CHUNK_SEPARATOR)
        }

        sb.appendLine("Use the above context to inform your response when relevant. The context may be incomplete or outdated — rely on the user as the authority.")

        return sb.toString()
    }

    /**
     * Delete all chunks for a document. Returns number of rows deleted.
     */
    fun deleteDocument(documentId: String): Int {
        val deleted = vectorStore.deleteByDocument(documentId)
        Log.i(TAG, "deleteDocument documentId=$documentId deleted=$deleted")
        return deleted
    }

    /**
     * Returns the total number of documents in the store.
     */
    fun documentCount(): Int = vectorStore.documentCount()

    /**
     * Returns the total number of chunks in the store.
     */
    fun chunkCount(): Int = vectorStore.chunkCount()

    /**
     * Clear all stored vectors.
     */
    fun clear() {
        Log.i(TAG, "clear: removing all chunks (count=${vectorStore.chunkCount()})")
        vectorStore.clear()
    }
}
