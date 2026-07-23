package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Status of a knowledge pack download/indexing operation.
 */
enum class KnowledgePackStatus {
    /** Pack definition exists but has not been downloaded. */
    NOT_DOWNLOADED,
    /** Articles are being downloaded from Grokipedia. */
    DOWNLOADING,
    /** All articles have been fetched, chunked, embedded, and stored. */
    INDEXED,
    /** Download or indexing failed. */
    FAILED,
}

/**
 * A curated knowledge pack: a named collection of Grokipedia article slugs
 * on a specific topic, designed for offline semantic search after download.
 */
data class KnowledgePack(
    val id: String,                   // e.g., "android-dev"
    val name: String,                 // Human-readable name
    val description: String,          // Brief description
    val topicSlugs: List<String>,     // Grokipedia article slugs in this pack
    val totalChunks: Int = 0,        // Number of chunks stored after indexing
    val downloadStatus: KnowledgePackStatus = KnowledgePackStatus.NOT_DOWNLOADED,
)

/**
 * Manages downloadable knowledge packs sourced from Grokipedia.
 *
 * Each pack is a curated set of article slugs. Packs are downloaded ON DEMAND
 * (lazy) — no articles are fetched until [downloadPack] is called.
 *
 * After download, articles are chunked, embedded via [NativeLlmBridge.encode],
 * and stored in the [VectorStore] using the existing [RagManager] pipeline.
 * Pack chunks are tagged with a document ID prefix of `"grokipedia:{packId}:{slug}"`.
 *
 * Thread safety is inherited from [VectorStore] (ReentrantLock) and
 * [RagManager] (stateless + VectorStore lock).
 */
class KnowledgePackManager(
    private val grokipediaClient: GrokipediaClient,
    private val vectorStore: VectorStore,
    private val ragManager: RagManager,
    private val chunker: DocumentChunker = DocumentChunker,
) {
    companion object {
        private const val TAG = "KnowledgePackManager"

        /** Document ID prefix used for all Grokipedia knowledge pack chunks. */
        const val GROKIPEDIA_DOC_ID_PREFIX = "grokipedia"

        /**
         * Pre-defined curated knowledge packs.
         *
         * These are hardcoded — no external download is needed for pack definitions.
         * Each pack bundles a curated set of Grokipedia article slugs on a specific topic.
         */
        val CURATED_PACKS: List<KnowledgePack> = listOf(
            KnowledgePack(
                id = "android-dev",
                name = "Android Development",
                description = "Android SDK, Jetpack Compose, Kotlin, Gradle, and Android development fundamentals.",
                topicSlugs = listOf(
                    "android-software-development",
                    "kotlin-programming-language",
                    "jetpack-compose",
                    "gradle-build-tool",
                    "android-sdk",
                ),
            ),
            KnowledgePack(
                id = "python-ref",
                name = "Python Reference",
                description = "Python programming language, standard library, package management, and common patterns.",
                topicSlugs = listOf(
                    "python-programming-language",
                    "pip-package-manager",
                    "python-standard-library",
                ),
            ),
            KnowledgePack(
                id = "javascript-ref",
                name = "JavaScript/TypeScript",
                description = "JavaScript, TypeScript, Node.js, and React front-end framework.",
                topicSlugs = listOf(
                    "javascript",
                    "typescript",
                    "node-js",
                    "react-front-end-framework",
                ),
            ),
            KnowledgePack(
                id = "ai-ml-basics",
                name = "AI & Machine Learning",
                description = "Large language models, neural networks, transformers, word embeddings, and retrieval-augmented generation.",
                topicSlugs = listOf(
                    "large-language-model",
                    "neural-network",
                    "transformer-machine-learning-model",
                    "word-embedding",
                    "retrieval-augmented-generation",
                ),
            ),
            KnowledgePack(
                id = "general-science",
                name = "General Science",
                description = "Physics, chemistry, biology fundamentals, and the scientific method.",
                topicSlugs = listOf(
                    "physics",
                    "chemistry",
                    "biology",
                    "scientific-method",
                ),
            ),
        )

        private val packMap: Map<String, KnowledgePack> by lazy {
            CURATED_PACKS.associateBy { it.id }
        }
    }

    // In-memory state: pack id -> current status (persisted across downloads within a session).
    private val packStatuses = mutableMapOf<String, KnowledgePackStatus>()
    private val packChunkCounts = mutableMapOf<String, Int>()

    init {
        // Initialize all packs as NOT_DOWNLOADED
        CURATED_PACKS.forEach { pack ->
            packStatuses[pack.id] = KnowledgePackStatus.NOT_DOWNLOADED
            packChunkCounts[pack.id] = 0
        }
    }

    /**
     * List all available curated knowledge packs with their current download/index status.
     *
     * @return list of [KnowledgePack] objects with live status and chunk counts
     */
    fun listAvailablePacks(): List<KnowledgePack> {
        return CURATED_PACKS.map { pack ->
            val fetched = checkPackAlreadyIndexed(pack)
            pack.copy(
                downloadStatus = fetched ?: packStatuses[pack.id] ?: KnowledgePackStatus.NOT_DOWNLOADED,
                totalChunks = packChunkCounts[pack.id] ?: 0,
            )
        }
    }

    /**
     * Download and index a knowledge pack from Grokipedia.
     *
     * Fetches all articles in the pack, chunks them, computes embeddings,
     * and stores them in the [VectorStore].
     *
     * @param packId     the ID of the pack to download (e.g., "android-dev")
     * @param onProgress callback with progress fraction (0.0 to 1.0)
     * @return total number of chunks indexed, or -1 on failure
     */
    suspend fun downloadPack(packId: String, onProgress: (Float) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val pack = packMap[packId]
        if (pack == null) {
            Log.w(TAG, "downloadPack unknown packId=$packId")
            packStatuses[packId] = KnowledgePackStatus.FAILED
            return@withContext -1
        }

        // Check if already indexed
        val existing = checkPackAlreadyIndexed(pack)
        if (existing == KnowledgePackStatus.INDEXED) {
            Log.i(TAG, "downloadPack packId=$packId already indexed with ${packChunkCounts[packId]} chunks")
            return@withContext packChunkCounts[packId] ?: 0
        }

        Log.i(TAG, "downloadPack starting packId=$packId slugs=${pack.topicSlugs}")
        packStatuses[packId] = KnowledgePackStatus.DOWNLOADING
        onProgress(0.0f)

        val slugs = pack.topicSlugs
        var totalIndexed = 0
        var failedSlugs = 0

        for ((index, slug) in slugs.withIndex()) {
            val result = runCatching {
                val article = grokipediaClient.fetchArticle(slug)
                if (article == null) {
                    Log.w(TAG, "downloadPack slug=$slug returned null")
                    failedSlugs++
                    return@runCatching null
                }

                // Use the article content or fall back to the summary if content is empty
                val content = if (article.content.isNotBlank()) article.content else article.summary
                if (content.isBlank()) {
                    Log.w(TAG, "downloadPack slug=$slug has empty content")
                    failedSlugs++
                    return@runCatching null
                }

                // Chunk the article
                val chunks = chunker.chunk(content)

                // Compute document ID: grokipedia:{packId}:{slug}
                val docId = "$GROKIPEDIA_DOC_ID_PREFIX:$packId:${article.slug}"

                // Ingest each chunk via RagManager
                val chunkCount = ragManager.ingestDocument(
                    documentId = docId,
                    title = article.title,
                    text = content,
                )

                totalIndexed += chunkCount
                Log.d(TAG, "downloadPack slug=$slug title=\"${article.title}\" chunks=$chunkCount")
            }

            if (result.isFailure) {
                Log.w(TAG, "downloadPack slug=$slug failed", result.exceptionOrNull())
                failedSlugs++
            }

            // Report progress
            val progress = (index + 1).toFloat() / slugs.size.toFloat()
            onProgress(progress.coerceIn(0.0f, 1.0f))
        }

        // Update status
        if (totalIndexed > 0) {
            packStatuses[packId] = KnowledgePackStatus.INDEXED
            packChunkCounts[packId] = totalIndexed
            Log.i(TAG, "downloadPack complete packId=$packId indexed=$totalIndexed failed=$failedSlugs")
        } else {
            packStatuses[packId] = KnowledgePackStatus.FAILED
            Log.w(TAG, "downloadPack failed packId=$packId no chunks indexed")
        }

        onProgress(1.0f)
        return@withContext totalIndexed
    }

    /**
     * Search the knowledge base for relevant articles.
     *
     * Queries the local [VectorStore] for chunks matching [query].
     * Results include chunks from all previously downloaded knowledge packs.
     *
     * @param query search query in natural language
     * @param topK  maximum number of results to return (default 5)
     * @return list of (chunk, similarityScore) pairs, sorted by descending score
     */
    suspend fun search(query: String, topK: Int = 5): List<Pair<VectorChunk, Float>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val safeTopK = topK.coerceIn(1, 20)
        val results = ragManager.query(query, topK = safeTopK)

        // Filter to only Grokipedia-sourced chunks
        val filtered = results.filter { it.first.documentId.startsWith("$GROKIPEDIA_DOC_ID_PREFIX:") }
        Log.d(TAG, "search query=\"${query.take(60)}\" total=${results.size} filtered=${filtered.size}")
        return@withContext filtered
    }

    /**
     * Fetch a specific article from Grokipedia and index it on-demand.
     *
     * Useful for deep-diving into a topic that is not covered by existing packs.
     *
     * @param slug the Grokipedia article slug (e.g., "artificial-intelligence")
     * @return total number of chunks indexed, or -1 on failure
     */
    suspend fun fetchAndIndex(slug: String): Int = withContext(Dispatchers.IO) {
        if (slug.isBlank()) return@withContext -1
        val result = runCatching {
            val article = grokipediaClient.fetchArticle(slug)
            if (article == null) return@runCatching -1

            val content = if (article.content.isNotBlank()) article.content else article.summary
            if (content.isBlank()) return@runCatching -1

            val docId = "$GROKIPEDIA_DOC_ID_PREFIX:on-demand:${article.slug}"
            val chunkCount = ragManager.ingestDocument(
                documentId = docId,
                title = article.title,
                text = content,
            )
            Log.i(TAG, "fetchAndIndex slug=$slug title=\"${article.title}\" chunks=$chunkCount")
            chunkCount
        }

        result.getOrDefault(-1)
    }

    /**
     * Get the current download/index status of a specific pack.
     *
     * @param packId the pack ID
     * @return [KnowledgePackStatus] — [KnowledgePackStatus.NOT_DOWNLOADED] if unknown
     */
    fun packStatus(packId: String): KnowledgePackStatus {
        // Check if already indexed in the vector store
        val pack = packMap[packId]
        if (pack != null) {
            val stored = checkPackAlreadyIndexed(pack)
            if (stored != null) return stored
        }
        return packStatuses[packId] ?: KnowledgePackStatus.NOT_DOWNLOADED
    }

    /**
     * Delete a knowledge pack's chunks from the vector store.
     *
     * Removes all chunks whose document ID starts with `grokipedia:{packId}:`.
     *
     * @param packId the pack ID to delete
     * @return number of chunks removed, or -1 on failure
     */
    fun deletePack(packId: String): Int {
        if (packId.isBlank()) return -1
        val prefix = "$GROKIPEDIA_DOC_ID_PREFIX:$packId:"
        var removed = 0

        val allChunks = vectorStore.getAllChunks()
        val toDelete = allChunks.filter { it.documentId.startsWith(prefix) }

        for (chunk in toDelete) {
            val deleted = vectorStore.deleteByDocument(chunk.documentId)
            removed += deleted
        }

        // Reset in-memory status
        packStatuses[packId] = KnowledgePackStatus.NOT_DOWNLOADED
        packChunkCounts[packId] = 0

        Log.i(TAG, "deletePack packId=$packId removed=$removed")
        return removed
    }

    /**
     * Clean up all Grokipedia chunks from the vector store.
     *
     * @return number of chunks removed
     */
    fun clearAllKnowledgePacks(): Int {
        var totalRemoved = 0
        for (pack in CURATED_PACKS) {
            totalRemoved += deletePack(pack.id)
        }
        Log.i(TAG, "clearAllKnowledgePacks removed=$totalRemoved")
        return totalRemoved
    }

    // ---- Internal helpers ----

    /**
     * Check whether a pack's articles are already stored in the vector store.
     *
     * Scans the store for chunks with document IDs matching the `grokipedia:{packId}:{slug}` prefix.
     * If all slugs in the pack have at least one chunk, the pack is considered INDEXED.
     *
     * @return [KnowledgePackStatus.INDEXED] if fully stored, null if not found
     */
    private fun checkPackAlreadyIndexed(pack: KnowledgePack): KnowledgePackStatus? {
        val prefix = "$GROKIPEDIA_DOC_ID_PREFIX:${pack.id}:"
        val allChunks = vectorStore.getAllChunks()
        val packChunks = allChunks.filter { it.documentId.startsWith(prefix) }

        if (packChunks.isEmpty()) return null

        // Check that every slug in the pack has at least one chunk
        val foundSlugs = packChunks.map { chunk ->
            chunk.documentId.removePrefix(prefix).substringBefore(":")
        }.toSet()

        val allSlugsPresent = pack.topicSlugs.all { slug -> foundSlugs.any { it == slug } }

        if (allSlugsPresent) {
            packStatuses[pack.id] = KnowledgePackStatus.INDEXED
            packChunkCounts[pack.id] = packChunks.size
            return KnowledgePackStatus.INDEXED
        }

        // Partial download — not yet indexed
        return null
    }
}
