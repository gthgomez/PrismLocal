package com.prismai.llmhost.generation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.bridge.NativeLlmBridge
import com.prismai.llmhost.storage.DocumentChunker
import com.prismai.llmhost.storage.EmbeddingIdentity
import com.prismai.llmhost.storage.RagManager
import com.prismai.llmhost.storage.VectorStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Compares the existing native embeddings to a simple word-overlap baseline on synthetic data. */
@RunWith(AndroidJUnit4::class)
class QwenRetrievalComparisonTest {
    @Test
    fun selectedCorpusAndLexicalBaseline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = requiredPinnedQwenModel()
        val docs = listOf("cedar-revision-2.txt", "cedar-distractor.txt").map { name ->
            instrumentation.context.assets.open("qualification/$name").bufferedReader().use { it.readText() }
        }
        val suffix = UUID.randomUUID().toString()
        val ids = listOf("qualification-cedar-$suffix", "qualification-distractor-$suffix")
        val database = "qualification-$suffix.db"
        val store = VectorStore(instrumentation.targetContext, database)
        val identity = EmbeddingIdentity(PINNED_QWEN_SHA256, VectorStore.EMBEDDING_REVISION)
        store.setEmbeddingIdentityProvider { identity }
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            val settings = GenerationSettings(threadCount = 1, contextLength = 2048, batchSize = 256, gpuLayers = 0)
            assertTrue("Qwen fixture failed to load", engine.loadModel(model.absolutePath, settings))
            val rag = RagManager(store, DocumentChunker) { engine.encode(it) }
            ids.indices.forEach { i ->
                assertTrue("document indexing failed", rag.ingestDocumentWithResult(ids[i], ids[i], docs[i]).success)
            }
            val questions = listOf(
                "Who is the Cedar Project owner?",
                "How many blue and green filters were requested?",
                "What is the revised delivery date?",
                "What is the approval code?",
            )
            for (question in questions) {
                val embedding = engine.encode(question)
                assertTrue("query embedding failed", embedding.isNotEmpty())
                val native = store.search(embedding, topK = store.chunkCount(), minScore = -1f, expectedIdentity = identity)
                assertTrue("comparison must include both fixture documents", native.map { it.first.documentId }.toSet() == ids.toSet())
                val selected = store.searchDocument(ids[0], embedding, 3, RagManager.DEFAULT_MIN_RAG_SCORE, identity)
                assertTrue(selected.matches.isNotEmpty())
                assertTrue(selected.matches.all { it.first.documentId == ids[0] })
                val lexical = docs.mapIndexed { index, body -> index to overlap(question, body) }
                    .sortedByDescending { it.second }
                Log.i("PrismRetrievalEval", "question=$question native=" +
                    native.joinToString { "${ids.indexOf(it.first.documentId)}:${it.second}" } +
                    " lexical=" + lexical.joinToString { "${it.first}:${it.second}" })
            }
        } finally {
            ids.forEach { store.deleteByDocument(it) }
            engine.destroySafely()
            instrumentation.targetContext.deleteDatabase(database)
        }
    }

    private fun overlap(question: String, text: String): Int {
        fun terms(s: String) = s.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }.toSet()
        return terms(question).intersect(terms(text)).size
    }
}
