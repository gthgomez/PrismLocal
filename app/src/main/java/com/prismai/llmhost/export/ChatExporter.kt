package com.prismai.llmhost.export
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pure formatting — no Android dependencies.
 * Extracted from InferenceService for independent testability.
 */
object ChatExporter {

    fun toMarkdown(session: ChatSession, messages: List<TranscriptMessage>): String =
        buildString {
            appendLine("# ${session.title}")
            appendLine()
            appendLine("- Chat ID: ${session.id}")
            appendLine("- Model: ${session.modelId ?: "unknown"}")
            appendLine("- Messages: ${messages.size}")
            appendLine()
            messages.forEach { message ->
                appendLine("## ${message.role.name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }}")
                appendLine()
                appendLine(message.text)
                appendLine()
            }
        }

    fun toText(session: ChatSession, messages: List<TranscriptMessage>): String =
        buildString {
            appendLine(session.title)
            appendLine("Chat ID: ${session.id}")
            appendLine("Model: ${session.modelId ?: "unknown"}")
            appendLine()
            messages.forEach { message ->
                appendLine("${message.role.name}: ${message.text}")
                appendLine()
            }
        }

    fun toJson(session: ChatSession, messages: List<TranscriptMessage>): JSONObject {
        val array = JSONArray()
        messages.forEach { message ->
            val obj = JSONObject()
                .put("id", message.id)
                .put("role", message.role.name)
                .put("text", message.text)
            if (message.summary != null) {
                obj.put("summary", message.summary)
            }
            array.put(obj)
        }
        return JSONObject()
            .put("schema", "prism-local-chat-v1")
            .put("chat_id", session.id)
            .put("title", session.title)
            .put("model_id", session.modelId ?: JSONObject.NULL)
            .put("created_at", session.createdAt)
            .put("updated_at", session.updatedAt)
            .put("messages", array)
    }
}
