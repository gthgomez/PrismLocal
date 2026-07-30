package com.prismai.llmhost.storage

import kotlin.math.sqrt

object LightweightEmbeddingEngine {

    private const val EMBEDDING_DIMENSION = 384

    /**
     * Generates a 384-dimensional normalized text vector for local vector RAG indexing.
     * Uses feature hashing and character n-gram frequencies to provide fast, deterministic embeddings
     * without loading a heavy LLM context or thrashing RAM during active chat sessions.
     */
    fun embedText(text: String): FloatArray {
        val vector = FloatArray(EMBEDDING_DIMENSION)
        if (text.isBlank()) return vector

        val words = text.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
        for (word in words) {
            val wordHash = word.hashCode()
            val dim1 = (wordHash and 0x7FFFFFFF) % EMBEDDING_DIMENSION
            vector[dim1] += 1.0f

            // Sub-word character n-gram hashing
            for (i in 0 until word.length - 2) {
                val ngram = word.substring(i, i + 3)
                val ngramHash = ngram.hashCode()
                val dim2 = (ngramHash and 0x7FFFFFFF) % EMBEDDING_DIMENSION
                val weight = if ((ngramHash and 1) == 0) 0.5f else -0.5f
                vector[dim2] += weight
            }
        }

        // L2 Normalization
        var normSq = 0.0f
        for (v in vector) {
            normSq += v * v
        }
        if (normSq > 0.0f) {
            val norm = sqrt(normSq)
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }

        return vector
    }

    /**
     * Computes Cosine Similarity between two normalized embedding vectors.
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0.0f
        var dot = 0.0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
        }
        return dot
    }
}
