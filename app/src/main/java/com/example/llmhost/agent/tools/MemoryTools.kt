package com.example.llmhost.agent.tools

import com.example.llmhost.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MemoryTools(
    private val memoryStore: SqlMemoryStore,
    private val currentChatId: () -> String?,
    private val refreshMemoriesList: () -> Unit,
) {
    fun rememberFact(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "User confirmation required to store a fact")
        val factText = call.arguments.optString("fact").trim().take(300)
        if (factText.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Fact text is required")
        val category = runCatching {
            MemoryCategory.valueOf(call.arguments.optString("category", "GENERAL").uppercase())
        }.getOrDefault(MemoryCategory.GENERAL)
        val existing = memoryStore.queryRelevant(factText, limit = 3)
        if (existing.isNotEmpty() && existing.first().score > 0.75f) {
            val updated = memoryStore.update(existing.first().fact.id, factText, existing.first().fact.confidence)
            if (updated) refreshMemoriesList()
            return if (updated) {
                toolSuccess(call, "Updated existing memory: ${factText.take(80)}",
                    JSONObject().put("stored", true).put("fact_id", existing.first().fact.id).put("category", category.name).put("merged", true))
            } else {
                toolFailure(call, AgentToolErrorCode.FAILED, "Failed to update memory")
            }
        }
        val fact = MemoryFact(fact = factText, category = category, confidence = 0.6f, sourceChatId = currentChatId())
        val stored = memoryStore.insert(fact)
        refreshMemoriesList()
        return toolSuccess(call, "Stored: ${factText.take(80)}",
            JSONObject().put("stored", true).put("fact_id", stored.id).put("category", category.name))
    }

    fun recallFacts(call: AgentToolCall): AgentToolResult {
        val query = call.arguments.optString("query").trim().take(200)
        if (query.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Query is required")
        val maxResults = call.arguments.optInt("max_results", 5).coerceIn(1, 10)
        val matches = memoryStore.queryRelevant(query, limit = maxResults)
        val resultsArray = JSONArray()
        matches.forEach { match ->
            resultsArray.put(JSONObject()
                .put("fact", match.fact.fact)
                .put("category", match.fact.category.name)
                .put("score", String.format(Locale.US, "%.2f", match.score).toDouble()))
        }
        return toolSuccess(call, "${matches.size} memory match(es) for \"${query.take(60)}\"",
            JSONObject().put("query", query).put("matches", resultsArray).put("untrusted_data", true))
    }

    fun forgetFact(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "User confirmation required to delete a memory")
        val factId = call.arguments.optString("fact_id").trim().take(20)
        if (factId.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "fact_id is required")
        val deleted = memoryStore.delete(factId)
        if (deleted) refreshMemoriesList()
        return if (deleted) {
            toolSuccess(call, "Memory deleted", JSONObject().put("deleted", true).put("fact_id", factId))
        } else {
            toolFailure(call, AgentToolErrorCode.NOT_FOUND, "No memory found with id: $factId")
        }
    }

    fun listMemories(call: AgentToolCall): AgentToolResult {
        val categoryFilter = call.arguments.optString("category", "all").lowercase()
        val limit = call.arguments.optInt("limit", 30).coerceIn(5, 100)
        val allActive = memoryStore.getAllActive()
        val filtered = if (categoryFilter == "all") {
            allActive
        } else {
            val cat = runCatching { MemoryCategory.valueOf(categoryFilter.uppercase()) }.getOrNull()
            if (cat != null) allActive.filter { it.category == cat } else allActive
        }.take(limit)
        val byCategory = mutableMapOf<String, Int>()
        filtered.forEach { fact ->
            val key = fact.category.name.lowercase()
            byCategory[key] = (byCategory[key] ?: 0) + 1
        }
        val memoriesArray = JSONArray()
        filtered.forEach { fact ->
            memoriesArray.put(JSONObject()
                .put("id", fact.id)
                .put("fact", fact.fact)
                .put("category", fact.category.name)
                .put("confidence", fact.confidence.toDouble()))
        }
        return toolSuccess(call, "${filtered.size} memories (${allActive.size} total)",
            JSONObject()
                .put("total", allActive.size)
                .put("returned", filtered.size)
                .put("by_category", JSONObject(byCategory))
                .put("memories", memoriesArray)
                .put("untrusted_data", true))
    }
}
