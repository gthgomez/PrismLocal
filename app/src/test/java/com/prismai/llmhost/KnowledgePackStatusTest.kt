package com.prismai.llmhost

import com.prismai.llmhost.storage.DocumentChunker
import com.prismai.llmhost.storage.EmbeddingIdentity
import com.prismai.llmhost.storage.KnowledgePackChunkStore
import com.prismai.llmhost.storage.RagManager
import com.prismai.llmhost.storage.VectorChunk
import com.prismai.llmhost.storage.VectorDocumentSummary
import com.prismai.llmhost.storage.VectorIndex
import com.prismai.llmhost.storage.VectorStore
import com.prismai.llmhost.tools.GrokipediaArticle
import com.prismai.llmhost.tools.GrokipediaClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: a knowledge pack must only be reported INDEXED when every article's chunks were
 * actually committed. A partially stored pack (one article failed to embed, or the encoder changed
 * mid-ingest) writes nothing for that article, and must not be counted as indexed.
 */
class KnowledgePackStatusTest {

    /** In-memory store implementing both the ingest and the maintenance contracts. */
    private class InMemoryVectorStore : VectorIndex, KnowledgePackChunkStore {
        val rows = mutableListOf<VectorChunk>()

        override fun currentEmbeddingIdentity(): EmbeddingIdentity =
            EmbeddingIdentity("enc-1", VectorStore.EMBEDDING_REVISION)

        override fun insertBatch(chunks: List<VectorChunk>): List<VectorChunk> {
            rows.addAll(chunks)
            return chunks
        }

        override fun replaceDocument(
            documentId: String,
            chunks: List<VectorChunk>,
            identity: EmbeddingIdentity,
        ): List<VectorChunk> {
            rows.removeAll { it.documentId == documentId }
            rows.addAll(chunks)
            return chunks
        }

        override fun search(queryEmbedding: FloatArray, topK: Int, minScore: Float): List<Pair<VectorChunk, Float>> =
            emptyList()

        override fun deleteByDocument(documentId: String): Int {
            val n = rows.count { it.documentId == documentId }
            rows.removeAll { it.documentId == documentId }
            return n
        }

        override fun documentCount(): Int = rows.map { it.documentId }.distinct().size
        override fun chunkCount(): Int = rows.size
        override fun clear() {
            rows.clear()
        }

        override fun setEmbeddingIdentityProvider(provider: () -> EmbeddingIdentity) {}

        override fun getDocumentSummaries(): List<VectorDocumentSummary> =
            rows.groupBy { it.documentId }.map { (docId, chunks) ->
                VectorDocumentSummary(
                    documentId = docId,
                    storedChunkCount = chunks.size,
                    searchableChunkCount = chunks.size,
                    previewText = chunks.first().text.take(VectorStore.PREVIEW_MAX_CHARS),
                    createdAt = chunks.minOf { it.createdAt },
                )
            }

        override fun deleteByDocumentPrefix(prefix: String): Int {
            val n = rows.count { it.documentId.startsWith(prefix) }
            rows.removeAll { it.documentId.startsWith(prefix) }
            return n
        }
    }

    private class FakeGrokipedia(
        private val articles: Map<String, GrokipediaArticle>,
    ) : GrokipediaClient() {
        override suspend fun fetchArticle(slug: String): GrokipediaArticle? = articles[slug]
    }

    private val pack = KnowledgePackManager.CURATED_PACKS.first { it.id == "python-ref" }

    private fun summariesFor(slugs: List<String>): List<VectorDocumentSummary> =
        slugs.mapIndexed { i, slug ->
            VectorDocumentSummary(
                documentId = "grokipedia:${pack.id}:$slug",
                storedChunkCount = 2,
                searchableChunkCount = 2,
                previewText = "text $slug",
                createdAt = i.toLong(),
            )
        }

    private class SummaryStore(
        private val documents: List<VectorDocumentSummary>,
    ) : KnowledgePackChunkStore {
        override fun getDocumentSummaries(): List<VectorDocumentSummary> = documents
        override fun deleteByDocumentPrefix(prefix: String): Int = 0
    }

    private fun manager(store: KnowledgePackChunkStore): KnowledgePackManager =
        KnowledgePackManager(
            grokipediaClient = GrokipediaClient(),
            vectorStore = store,
            ragManager = RagManager(InMemoryVectorStore(), DocumentChunker) { FloatArray(0) },
        )

    @Test
    fun missingOneSlugIsNotIndexed() {
        val store = SummaryStore(summariesFor(pack.topicSlugs.dropLast(1)))
        val mgr = manager(store)

        assertNotEquals(
            KnowledgePackStatus.INDEXED,
            mgr.listAvailablePacks().first { it.id == pack.id }.downloadStatus,
        )
    }

    @Test
    fun everySlugStoredIsIndexed() {
        val store = SummaryStore(summariesFor(pack.topicSlugs))
        val mgr = manager(store)

        val listed = mgr.listAvailablePacks().first { it.id == pack.id }
        assertEquals(KnowledgePackStatus.INDEXED, listed.downloadStatus)
        assertEquals(pack.topicSlugs.size * 2, listed.totalChunks)
    }

    @Test
    fun partialPackIngestIsNotIndexedAndReportsZeroCommittedChunks() = runBlocking {
        val store = InMemoryVectorStore()
        // A pre-existing document that must survive a failed pack ingest.
        store.rows += VectorChunk(
            id = "existing-1",
            documentId = "existing-doc",
            chunkIndex = 0,
            text = "previous text",
            embedding = FloatArray(4) { 0.1f },
        )

        var calls = 0
        // Fail the second chunk of every article: ingest is not committed, so nothing is written.
        val rag = RagManager(store, DocumentChunker) { _ ->
            calls++
            if (calls % 2 == 0) FloatArray(0) else FloatArray(4) { 0.1f }
        }
        val grokipedia = FakeGrokipedia(
            pack.topicSlugs.associateWith { slug ->
                GrokipediaArticle(
                    slug = slug,
                    title = slug,
                    content = "A sufficiently long article body. ".repeat(40),
                    summary = "",
                    categories = emptyList(),
                    lastModified = null,
                    citations = emptyList(),
                )
            },
        )
        val mgr = KnowledgePackManager(grokipedia, store, rag)

        val result = mgr.downloadPack(pack.id)

        assertEquals("a partially embedded pack must report zero indexed chunks", 0, result.storedChunks)
        assertEquals(KnowledgePackStatus.FAILED, result.status)
        assertTrue("result isComplete must be false on failure", !result.isComplete)
        assertEquals(KnowledgePackStatus.FAILED, mgr.packStatus(pack.id))
        assertTrue(
            "no pack rows may be written when every article's ingest failed",
            store.rows.none { it.documentId.startsWith("grokipedia:${pack.id}:") },
        )
        assertEquals(
            "the previously stored document must be untouched",
            1,
            store.rows.count { it.documentId == "existing-doc" },
        )
    }

    @Test
    fun deletePackRemovesAllPackRowsInOnePass() {
        val store = InMemoryVectorStore()
        pack.topicSlugs.forEachIndexed { i, slug ->
            store.rows += VectorChunk(
                id = "$slug-$i",
                documentId = "grokipedia:${pack.id}:$slug",
                chunkIndex = 0,
                text = "text $slug",
                embedding = FloatArray(4) { 0f },
            )
        }

        val mgr = manager(store)
        val removed = mgr.deletePack(pack.id)

        assertEquals(pack.topicSlugs.size, removed)
        assertTrue("all pack rows must be gone", store.rows.isEmpty())
    }

    @Test
    fun clearAllKnowledgePacksRemovesBothCuratedAndOnDemandDocuments() {
        val store = InMemoryVectorStore()
        store.rows += VectorChunk(
            id = "curated-1",
            documentId = "grokipedia:${pack.id}:some-slug",
            chunkIndex = 0,
            text = "curated pack text",
            embedding = FloatArray(4) { 0.1f },
        )
        store.rows += VectorChunk(
            id = "ondemand-1",
            documentId = "grokipedia:on-demand:python-intro",
            chunkIndex = 0,
            text = "on demand article text",
            embedding = FloatArray(4) { 0.1f },
        )
        store.rows += VectorChunk(
            id = "user-1",
            documentId = "user-doc-notes",
            chunkIndex = 0,
            text = "user personal note",
            embedding = FloatArray(4) { 0.2f },
        )

        val mgr = manager(store)
        val removed = mgr.clearAllKnowledgePacks()

        assertEquals(2, removed)
        assertEquals(1, store.rows.size)
        assertEquals("user-doc-notes", store.rows.first().documentId)
        assertEquals(KnowledgePackStatus.NOT_DOWNLOADED, mgr.packStatus(pack.id))
    }
}
