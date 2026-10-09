package com.prismai.llmhost.ui.rag

/**
 * Explain an ingest result.
 *
 * When Engine::encode returns nothing - for example a model that cannot produce
 * embeddings - RagManager counted the chunk as failed and PromptBuilder swallowed
 * the error behind runCatching, so the user saw a silent no-op. State the counts.
 */
object DocumentIngestMessaging {
    fun describe(inserted: Int, failed: Int, total: Int): String = when {
        total == 0 -> "Nothing to index"
        inserted == 0 -> "Could not index this document: $failed of $total chunks could not be embedded"
        failed > 0 -> "Indexed $inserted of $total chunks; $failed could not be embedded"
        else -> "Indexed $inserted chunks"
    }
}
