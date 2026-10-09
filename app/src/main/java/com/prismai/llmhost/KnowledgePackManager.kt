package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.util.Log
import kotlinx.coroutines.CancellationException
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
    val searchableChunks: Int = 0,   // Number of chunks searchable under currently loaded encoder
    val downloadStatus: KnowledgePackStatus = KnowledgePackStatus.NOT_DOWNLOADED,
)

data class PackDownloadResult(
    val packId: String,
    val committedSlugs: Int,
    val totalSlugs: Int,
    val status: KnowledgePackStatus,
    val storedChunks: Int,
    val searchableChunks: Int,
) {
    val isComplete: Boolean get() = status == KnowledgePackStatus.INDEXED
}

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
    private val vectorStore: KnowledgePackChunkStore,
    private val ragManager: RagManager,
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
    private val packSearchableChunkCounts = mutableMapOf<String, Int>()

    init {
        // Initialize all packs as NOT_DOWNLOADED
        CURATED_PACKS.forEach { pack ->
            packStatuses[pack.id] = KnowledgePackStatus.NOT_DOWNLOADED
            packChunkCounts[pack.id] = 0
            packSearchableChunkCounts[pack.id] = 0
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
            val stored = packChunkCounts[pack.id] ?: 0
            val searchable = packSearchableChunkCounts[pack.id] ?: 0
            pack.copy(
                downloadStatus = fetched ?: packStatuses[pack.id] ?: KnowledgePackStatus.NOT_DOWNLOADED,
                totalChunks = stored,
                searchableChunks = searchable,
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
    suspend fun downloadPack(packId: String, onProgress: (Float) -> Unit = {}): PackDownloadResult = withContext(Dispatchers.IO) {
        val pack = packMap[packId]
        if (pack == null) {
            Log.w(TAG, "downloadPack unknown packId=$packId")
            packStatuses[packId] = KnowledgePackStatus.FAILED
            return@withContext PackDownloadResult(
                packId = packId,
                committedSlugs = 0,
                totalSlugs = 0,
                status = KnowledgePackStatus.FAILED,
                storedChunks = 0,
                searchableChunks = 0,
            )
        }

        // Check if already indexed
        val existing = checkPackAlreadyIndexed(pack)
        if (existing == KnowledgePackStatus.INDEXED) {
            val stored = packChunkCounts[packId] ?: 0
            val searchable = packSearchableChunkCounts[packId] ?: 0
            Log.i(TAG, "downloadPack packId=$packId already indexed with $stored chunks ($searchable searchable)")
            return@withContext PackDownloadResult(
                packId = packId,
                committedSlugs = pack.topicSlugs.size,
                totalSlugs = pack.topicSlugs.size,
                status = KnowledgePackStatus.INDEXED,
                storedChunks = stored,
                searchableChunks = searchable,
            )
        }

        Log.i(TAG, "downloadPack starting packId=$packId slugs=${pack.topicSlugs}")
        packStatuses[packId] = KnowledgePackStatus.DOWNLOADING
        onProgress(0.0f)

        val slugs = pack.topicSlugs
        var totalIndexed = 0
        var failedSlugs = 0
        var committedSlugs = 0

        for ((index, slug) in slugs.withIndex()) {
            val committed = try {
                val article = grokipediaClient.fetchArticle(slug)
                if (article == null) {
                    Log.w(TAG, "downloadPack slug=$slug returned null")
                    false
                } else {
                    // Use the article content or fall back to the summary if content is empty
                    val content = if (article.content.isNotBlank()) article.content else article.summary
                    if (content.isBlank()) {
                        Log.w(TAG, "downloadPack slug=$slug has empty content")
                        false
                    } else {
                        // Compute document ID: grokipedia:{packId}:{slug}
                        val docId = "$GROKIPEDIA_DOC_ID_PREFIX:$packId:${article.slug}"
                        val ingest = ragManager.ingestDocumentWithResult(
                            documentId = docId,
                            title = article.title,
                            text = content,
                        )
                        // Only a committed ingest wrote rows. A partial embed, an encoder switch, or
                        // an oversized article wrote nothing; do not count it as indexed.
                        if (ingest.committed) {
                            totalIndexed += ingest.embeddedCount
                            Log.d(TAG, "downloadPack slug=$slug chunks=${ingest.embeddedCount}")
                            true
                        } else {
                            Log.w(
                                TAG,
                                "downloadPack slug=$slug not committed chunks=${ingest.totalChunks} " +
                                    "failed=${ingest.failedCount} encoderChanged=${ingest.encoderChanged} " +
                                    "tooLarge=${ingest.tooLarge}",
                            )
                            false
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "downloadPack slug=$slug failed", e)
                false
            }

            if (committed) committedSlugs++ else failedSlugs++

            // Report progress
            val progress = (index + 1).toFloat() / slugs.size.toFloat()
            onProgress(progress.coerceIn(0.0f, 1.0f))
        }

        val prefix = "$GROKIPEDIA_DOC_ID_PREFIX:$packId:"
        val packDocs = vectorStore.getDocumentSummaries().filter { it.documentId.startsWith(prefix) }
        val storedChunks = packDocs.sumOf { it.storedChunkCount }
        val searchableChunks = packDocs.sumOf { it.searchableChunkCount }

        // Update status. INDEXED only when every slug committed; a partially stored pack is reported
        // as FAILED rather than pretending the whole pack is searchable.
        val finalStatus = if (committedSlugs == slugs.size && storedChunks > 0) {
            KnowledgePackStatus.INDEXED
        } else {
            KnowledgePackStatus.FAILED
        }

        packStatuses[packId] = finalStatus
        packChunkCounts[packId] = storedChunks
        packSearchableChunkCounts[packId] = searchableChunks

        if (finalStatus == KnowledgePackStatus.INDEXED) {
            Log.i(TAG, "downloadPack complete packId=$packId stored=$storedChunks searchable=$searchableChunks failed=$failedSlugs")
        } else {
            Log.w(
                TAG,
                "downloadPack failed packId=$packId committed=$committedSlugs/${slugs.size} " +
                    "stored=$storedChunks searchable=$searchableChunks failed=$failedSlugs",
            )
        }

        onProgress(1.0f)
        return@withContext PackDownloadResult(
            packId = packId,
            committedSlugs = committedSlugs,
            totalSlugs = slugs.size,
            status = finalStatus,
            storedChunks = storedChunks,
            searchableChunks = searchableChunks,
        )
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
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "search query=\"${query.take(60)}\" total=${results.size} filtered=${filtered.size}")
        }
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
        try {
            val article = grokipediaClient.fetchArticle(slug)
            if (article == null) return@withContext -1

            val content = if (article.content.isNotBlank()) article.content else article.summary
            if (content.isBlank()) return@withContext -1

            val docId = "$GROKIPEDIA_DOC_ID_PREFIX:on-demand:${article.slug}"
            val ingest = ragManager.ingestDocumentWithResult(
                documentId = docId,
                title = article.title,
                text = content,
            )
            if (!ingest.committed) {
                Log.w(
                    TAG,
                    "fetchAndIndex slug=$slug not committed failed=${ingest.failedCount} " +
                        "encoderChanged=${ingest.encoderChanged} tooLarge=${ingest.tooLarge}",
                )
                return@withContext -1
            }
            Log.i(TAG, "fetchAndIndex slug=$slug chunks=${ingest.embeddedCount}")
            ingest.embeddedCount
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchAndIndex slug=$slug failed", e)
            -1
        }
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

        // One targeted DELETE instead of loading every row (and decoding every embedding) just to
        // discover the pack's document ids.
        val removed = vectorStore.deleteByDocumentPrefix(prefix)

        // Reset in-memory status
        packStatuses[packId] = KnowledgePackStatus.NOT_DOWNLOADED
        packChunkCounts[packId] = 0
        packSearchableChunkCounts[packId] = 0

        Log.i(TAG, "deletePack packId=$packId removed=$removed")
        return removed
    }

    /**
     * Clean up all Grokipedia chunks from the vector store, including curated packs
     * and on-demand imported articles.
     *
     * @return number of chunks removed
     */
    fun clearAllKnowledgePacks(): Int {
        val removed = vectorStore.deleteByDocumentPrefix("$GROKIPEDIA_DOC_ID_PREFIX:")
        for (pack in CURATED_PACKS) {
            packStatuses[pack.id] = KnowledgePackStatus.NOT_DOWNLOADED
            packChunkCounts[pack.id] = 0
            packSearchableChunkCounts[pack.id] = 0
        }
        Log.i(TAG, "clearAllKnowledgePacks removed=$removed")
        return removed
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
        // Stored, encoder-independent view: a pack is indexed when its rows are on disk, regardless
        // of which model is currently loaded. Bounded to one row per document.
        val packDocuments = vectorStore.getDocumentSummaries().filter { it.documentId.startsWith(prefix) }

        if (packDocuments.isEmpty()) return null

        // Check that every slug in the pack has at least one stored chunk
        val foundSlugs = packDocuments.map { summary ->
            summary.documentId.removePrefix(prefix).substringBefore(":")
        }.toSet()

        val allSlugsPresent = pack.topicSlugs.all { slug -> slug in foundSlugs }

        if (allSlugsPresent) {
            val stored = packDocuments.sumOf { it.storedChunkCount }
            val searchable = packDocuments.sumOf { it.searchableChunkCount }
            packStatuses[pack.id] = KnowledgePackStatus.INDEXED
            packChunkCounts[pack.id] = stored
            packSearchableChunkCounts[pack.id] = searchable
            return KnowledgePackStatus.INDEXED
        }

        // Partial download — not yet indexed
        return null
    }
}
