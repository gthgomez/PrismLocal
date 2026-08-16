package com.prismai.llmhost.cloud.prismatix

import org.json.JSONArray
import org.json.JSONObject

data class PrismatixMessage(
    val role: String, // "user" | "assistant"
    val content: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("role", role)
        .put("content", content)
}

data class PrismatixChatRequest(
    val conversationId: String,
    val query: String,
    val platform: String = "mobile",
    val history: List<PrismatixMessage> = emptyList(),
    val modelOverride: String? = null,
) {
    fun toJsonString(): String {
        val json = JSONObject()
            .put("conversationId", conversationId)
            .put("query", query)
            .put("platform", platform)
            .put("history", JSONArray().apply {
                history.forEach { put(it.toJson()) }
            })
        if (!modelOverride.isNullOrBlank()) {
            json.put("modelOverride", modelOverride)
        }
        return json.toString()
    }
}

sealed class PrismatixStreamEvent {
    data class Delta(val text: String) : PrismatixStreamEvent()

    data class Metadata(
        val routerModel: String? = null,
        val provider: String? = null,
        val costEstimateUsd: Double? = null,
        val rationale: String? = null,
        val complexityScore: Double? = null,
    ) : PrismatixStreamEvent()

    data object Done : PrismatixStreamEvent()

    data class Error(
        val statusCode: Int,
        val message: String,
        val retryAfterSeconds: Int? = null,
    ) : PrismatixStreamEvent()
}
