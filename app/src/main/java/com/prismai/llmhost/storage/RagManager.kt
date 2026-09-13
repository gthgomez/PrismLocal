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
    private val vectorStore: VectorStore,
    private val chunker: DocumentChunker,
    /** Suspending function that returns float embedding for a text string. */
    private val encode: suspend (String) -> FloatArray,
) {
    /**
     * Outcome of an ingestion. [failedCount] counts chunks that could not be
     * embedded (e.g. oversized chunks rejected by the native size guard), which
     * would otherwise be silently dropped.
     */
    data class IngestResult(
        val storedCount: Int,
        val failedCount: Int,
    ) {
        /** True only when at least one chunk stored and none failed. */
        val success: Boolean get() = storedCount > 0 && failedCount == 0

        /** True when some chunks stored but others failed. */
        val partial: Boolean get() = storedCount > 0 && failedCount > 0

        /** Total chunks attempted (stored + failed). */
        val totalChunks: Int get() = storedCount + failedCount
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
     * @return number of chunks stored
     */
    suspend fun ingestDocument(documentId: String, title: String, text: String): Int =
        ingestDocumentWithResult(documentId, title, text).storedCount

    /**
     * Ingest a document and report both stored and failed chunk counts.
     *
     * Chunks whose embedding is empty (e.g. rejected by the native size guard)
     * are counted in [IngestResult.failedCount] instead of being silently dropped.
     */
    suspend fun ingestDocumentWithResult(documentId: String, title: String, text: String): IngestResult =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) {
                Log.w(TAG, "ingestDocument skipped: empty text documentId=$documentId")
                return@withContext IngestResult(storedCount = 0, failedCount = 0)
            }

            val chunks = chunker.chunk(text)
            if (chunks.isEmpty()) {
                Log.w(TAG, "ingestDocument no chunks produced documentId=$documentId")
                return@withContext IngestResult(storedCount = 0, failedCount = 0)
            }

            val vectorChunks = mutableListOf<VectorChunk>()
            var failedCount = 0

            for (chunk in chunks) {
                val embedding = runCatching {
                    encode(chunk.text)
                }.getOrNull()

                if (embedding == null || embedding.isEmpty()) {
                    failedCount++
                    continue
                }

                vectorChunks.add(
                    VectorChunk(
                        id = UUID.randomUUID().toString().take(12),
                        documentId = documentId,
                        chunkIndex = chunk.index,
                        text = chunk.text,
                        embedding = embedding,
                    )
                )
            }

            if (vectorChunks.isNotEmpty()) {
                vectorStore.insertBatch(vectorChunks)
            }

            val stored = vectorChunks.size
            Log.i(TAG, "ingestDocument documentId=$documentId chunks=$stored failed=$failedCount")
            IngestResult(storedCount = stored, failedCount = failedCount)
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

            val queryEmbedding = runCatching {
                encode(userPrompt)
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
