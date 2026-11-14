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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** Compares the existing native embeddings to a simple word-overlap baseline on synthetic data. */
@RunWith(AndroidJUnit4::class)
class QwenRetrievalComparisonTest {
    @Test
    fun selectedCorpusAndLexicalBaseline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("prismQwenModelPath")
        assumeTrue("Pass prismQwenModelPath for real embedding comparison", !path.isNullOrBlank())
        val model = File(requireNotNull(path))
        assertTrue(model.isFile && model.canRead())
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertTrue("unexpected model hash: $hash", hash == "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db")
        val docs = listOf("cedar-revision-2.txt", "cedar-distractor.txt").map { name ->
            instrumentation.context.assets.open("qualification/$name").bufferedReader().use { it.readText() }
        }
        val suffix = UUID.randomUUID().toString()
        val ids = listOf("qualification-cedar-$suffix", "qualification-distractor-$suffix")
        val store = VectorStore(instrumentation.targetContext)
        val identity = EmbeddingIdentity(hash, VectorStore.EMBEDDING_REVISION)
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
                val native = store.search(embedding, topK = 20, minScore = -1f, expectedIdentity = identity)
                    .filter { it.first.documentId in ids }
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
        }
    }

    private fun overlap(question: String, text: String): Int {
        fun terms(s: String) = s.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }.toSet()
        return terms(question).intersect(terms(text)).size
    }
}
