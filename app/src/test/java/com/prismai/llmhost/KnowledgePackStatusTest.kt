package com.prismai.llmhost

import com.prismai.llmhost.storage.DocumentChunker
import com.prismai.llmhost.storage.KnowledgePackChunkStore
import com.prismai.llmhost.storage.RagManager
import com.prismai.llmhost.storage.VectorChunk
import com.prismai.llmhost.storage.VectorIndex
import com.prismai.llmhost.tools.GrokipediaClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression: after the embedding revision bump, a pack whose stored chunks are
 * all stale must NOT report INDEXED, or downloadPack never re-ingests and search
 * returns nothing for a pack the UI calls "downloaded".
 */
class KnowledgePackStatusTest {

    /** Unfiltered read returns every row; the current read returns only revision 2. */
    private class FakeChunkStore(
        private val all: List<VectorChunk>,
        private val current: List<VectorChunk>,
    ) : KnowledgePackChunkStore {
        override fun getAllChunks(): List<VectorChunk> = all
        override fun getCurrentChunks(): List<VectorChunk> = current
        override fun deleteByDocument(documentId: String): Int = 0
    }

    private class FakeVectorIndex : VectorIndex {
        override fun insertBatch(chunks: List<VectorChunk>) = chunks
        override fun search(queryEmbedding: FloatArray, topK: Int, minScore: Float) =
            emptyList<Pair<VectorChunk, Float>>()
        override fun deleteByDocument(documentId: String) = 0
        override fun documentCount() = 0
        override fun chunkCount() = 0
        override fun clear() {}
    }

    private val pack = KnowledgePackManager.CURATED_PACKS.first { it.id == "python-ref" }

    private fun chunksFor(slugs: List<String>): List<VectorChunk> =
        slugs.mapIndexed { i, slug ->
            VectorChunk(
                id = "$slug-$i",
                documentId = "grokipedia:${pack.id}:$slug",
                chunkIndex = i,
                text = "text $slug",
                embedding = FloatArray(4) { 0f },
            )
        }

    private fun manager(store: KnowledgePackChunkStore): KnowledgePackManager =
        KnowledgePackManager(
            grokipediaClient = GrokipediaClient(),
            vectorStore = store,
            ragManager = RagManager(FakeVectorIndex(), DocumentChunker) { FloatArray(0) },
        )

    @Test
    fun staleOnlyPackIsNotIndexed() {
        // getAllChunks still shows the old rows; getCurrentChunks shows none.
        val stale = chunksFor(pack.topicSlugs)
        val mgr = manager(FakeChunkStore(all = stale, current = emptyList()))

        assertNotEquals(
            KnowledgePackStatus.INDEXED,
            mgr.listAvailablePacks().first { it.id == pack.id }.downloadStatus,
        )
        assertNotEquals(KnowledgePackStatus.INDEXED, mgr.packStatus(pack.id))
    }

    @Test
    fun everySlugAtCurrentRevisionIsIndexed() {
        val current = chunksFor(pack.topicSlugs)
        val mgr = manager(FakeChunkStore(all = current, current = current))

        val listed = mgr.listAvailablePacks().first { it.id == pack.id }
        assertEquals(KnowledgePackStatus.INDEXED, listed.downloadStatus)
        assertEquals(current.size, listed.totalChunks)
    }

    @Test
    fun missingOneSlugIsNotIndexed() {
        val current = chunksFor(pack.topicSlugs.dropLast(1))
        val mgr = manager(FakeChunkStore(all = current, current = current))

        assertNotEquals(
            KnowledgePackStatus.INDEXED,
            mgr.listAvailablePacks().first { it.id == pack.id }.downloadStatus,
        )
    }
}
