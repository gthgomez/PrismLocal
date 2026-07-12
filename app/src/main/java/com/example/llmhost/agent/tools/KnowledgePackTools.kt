package com.example.llmhost.agent.tools

import com.example.llmhost.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class KnowledgePackTools(
    private val knowledgePackManager: KnowledgePackManager,
    private val grokipediaClient: GrokipediaClient,
) {
    suspend fun searchKnowledge(call: AgentToolCall): AgentToolResult {
        val query = call.arguments.optString("query").trim().take(300)
        val topK = call.arguments.optInt("top_k", 5).coerceIn(1, 10)
        if (query.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Search query is required")
        val results = knowledgePackManager.search(query, topK = topK)
        val resultsArray = JSONArray()
        results.forEach { (chunk, score) ->
            val slug = chunk.documentId.removePrefix("grokipedia:").substringAfter(":").substringBefore(":")
            resultsArray.put(JSONObject()
                .put("title", chunk.documentId)
                .put("text", chunk.text.take(500))
                .put("score", String.format(Locale.US, "%.3f", score).toDouble())
                .put("slug", slug))
        }
        return toolSuccess(call, "Found ${results.size} relevant knowledge chunks",
            JSONObject().put("results", resultsArray).put("source", "grokipedia").put("untrusted_data", true).put("knowledge_base", "local"))
    }

    suspend fun fetchGrokipediaArticle(call: AgentToolCall): AgentToolResult {
        val slug = call.arguments.optString("slug").trim().take(200)
        if (slug.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Article slug is required")
        val chunks = runCatching { knowledgePackManager.fetchAndIndex(slug) }.getOrDefault(-1)
        if (chunks <= 0) return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "Could not fetch article '$slug' from Grokipedia.")
        val article = runCatching { grokipediaClient.fetchArticle(slug) }.getOrNull()
        return toolSuccess(call, "Indexed '$slug' ($chunks chunks)",
            JSONObject().put("indexed", true).put("slug", slug).put("chunks", chunks).put("title", article?.title ?: slug).put("source", "grokipedia"))
    }

    suspend fun listKnowledgePacks(call: AgentToolCall): AgentToolResult {
        val packs = knowledgePackManager.listAvailablePacks()
        val packsArray = JSONArray()
        packs.forEach { pack ->
            packsArray.put(JSONObject()
                .put("id", pack.id).put("name", pack.name).put("description", pack.description)
                .put("downloaded", pack.downloadStatus == KnowledgePackStatus.INDEXED)
                .put("chunks", pack.totalChunks).put("article_count", pack.topicSlugs.size).put("status", pack.downloadStatus.name))
        }
        val totalDownloaded = packs.count { it.downloadStatus == KnowledgePackStatus.INDEXED }
        return toolSuccess(call, "$totalDownloaded/${packs.size} knowledge packs downloaded",
            JSONObject().put("packs", packsArray).put("source", "grokipedia").put("total_packs", packs.size))
    }

    suspend fun downloadKnowledgePack(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Download requires confirmation")
        val packId = call.arguments.optString("pack_id").trim().take(100)
        if (packId.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Knowledge pack ID is required")
        val pack = KnowledgePackManager.CURATED_PACKS.firstOrNull { it.id == packId }
            ?: return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "Unknown knowledge pack: $packId")
        val chunks = knowledgePackManager.downloadPack(packId)
        if (chunks <= 0) return toolFailure(call, AgentToolErrorCode.FAILED, "Failed to download pack '$packId'")
        return toolSuccess(call, "Downloaded '$packId' ($chunks chunks)",
            JSONObject().put("downloaded", true).put("pack_id", packId).put("pack_name", pack.name).put("chunks", chunks).put("articles", pack.topicSlugs.size).put("source", "grokipedia"))
    }
}
