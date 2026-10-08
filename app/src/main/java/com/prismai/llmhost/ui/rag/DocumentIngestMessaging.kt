package com.prismai.llmhost.ui.rag

/**
 * Explain an ingest result.
 *
 * When Engine::encode returns nothing - for example a model that cannot produce
 * embeddings - RagManager counts the chunk as failed and now discards the whole
 * new chunk set so a previously complete index is never destroyed. The message
 * must say the counts and whether the previous index survived.
 */
object DocumentIngestMessaging {
    fun describe(embedded: Int, failed: Int, total: Int, committed: Boolean): String = when {
        total == 0 -> "Nothing to index"
        !committed -> "Could not index this document: $failed of $total chunks could not be embedded; the previous index was preserved"
        embedded == 0 -> "Could not index this document: $failed of $total chunks could not be embedded"
        failed > 0 -> "Indexed $embedded of $total chunks; $failed could not be embedded"
        else -> "Indexed $embedded chunks"
    }
}
