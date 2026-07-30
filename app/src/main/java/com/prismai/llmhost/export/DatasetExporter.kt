package com.prismai.llmhost.export

import android.util.JsonWriter
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer

enum class DatasetExportFormat {
    SHAREGPT,
    ALPACA,
    OPENAI_MESSAGES,
}

object DatasetExporter {

    /**
     * Exports a list of chat transcripts into a standardized fine-tuning dataset format,
     * applying quality filtration and PII redaction automatically.
     */
    fun exportDataset(
        session: ChatSession,
        messages: List<TranscriptMessage>,
        format: DatasetExportFormat,
        sanitizePii: Boolean = true,
        filterQuality: Boolean = true
    ): String {
        val validMessages = if (filterQuality) QualityDatasetFilter.filterValidMessages(messages) else messages
        return when (format) {
            DatasetExportFormat.SHAREGPT -> exportShareGpt(session, validMessages, sanitizePii)
            DatasetExportFormat.ALPACA -> exportAlpaca(session, validMessages, sanitizePii)
            DatasetExportFormat.OPENAI_MESSAGES -> exportOpenAiMessages(session, validMessages, sanitizePii)
        }
    }

    /**
     * Streams fine-tuning dataset directly to a Writer to protect 4GB/6GB RAM devices
     * against Large Heap OOM spikes during long chat exports.
     */
    fun exportDatasetStreaming(
        session: ChatSession,
        messages: List<TranscriptMessage>,
        writer: Writer,
        format: DatasetExportFormat,
        sanitizePii: Boolean = true,
        filterQuality: Boolean = true
    ) {
        val validMessages = if (filterQuality) QualityDatasetFilter.filterValidMessages(messages) else messages
        val jsonWriter = JsonWriter(writer)
        jsonWriter.setIndent("  ")

        when (format) {
            DatasetExportFormat.SHAREGPT -> streamShareGpt(session, validMessages, jsonWriter, sanitizePii)
            DatasetExportFormat.ALPACA -> streamAlpaca(session, validMessages, jsonWriter, sanitizePii)
            DatasetExportFormat.OPENAI_MESSAGES -> streamOpenAi(session, validMessages, jsonWriter, sanitizePii)
        }
        jsonWriter.flush()
    }

    private fun streamShareGpt(session: ChatSession, messages: List<TranscriptMessage>, jw: JsonWriter, sanitizePii: Boolean) {
        jw.beginObject()
        jw.name("id").value(session.id)
        jw.name("model").value(session.modelId ?: "unknown")
        jw.name("conversations")
        jw.beginArray()
        messages.forEach { msg ->
            val from = when (msg.role) {
                TranscriptRole.USER -> "human"
                TranscriptRole.ASSISTANT -> "gpt"
                TranscriptRole.TOOL -> "tool"
            }
            val text = ContextPruner.prune(if (sanitizePii) PiiSanitizer.sanitize(msg.text) else msg.text)
            jw.beginObject()
            jw.name("from").value(from)
            jw.name("value").value(text)
            jw.endObject()
        }
        jw.endArray()
        jw.endObject()
    }

    private fun streamAlpaca(session: ChatSession, messages: List<TranscriptMessage>, jw: JsonWriter, sanitizePii: Boolean) {
        jw.beginArray()
        var lastUserText = ""
        messages.forEach { msg ->
            val text = ContextPruner.prune(if (sanitizePii) PiiSanitizer.sanitize(msg.text) else msg.text)
            when (msg.role) {
                TranscriptRole.USER -> lastUserText = text
                TranscriptRole.ASSISTANT -> {
                    if (lastUserText.isNotBlank()) {
                        jw.beginObject()
                        jw.name("instruction").value(lastUserText)
                        jw.name("input").value("")
                        jw.name("output").value(text)
                        jw.name("model").value(session.modelId ?: "unknown")
                        jw.endObject()
                        lastUserText = ""
                    }
                }
                TranscriptRole.TOOL -> {}
            }
        }
        jw.endArray()
    }

    private fun streamOpenAi(session: ChatSession, messages: List<TranscriptMessage>, jw: JsonWriter, sanitizePii: Boolean) {
        jw.beginObject()
        jw.name("id").value(session.id)
        jw.name("model").value(session.modelId ?: "unknown")
        jw.name("messages")
        jw.beginArray()
        messages.forEach { msg ->
            val roleStr = when (msg.role) {
                TranscriptRole.USER -> "user"
                TranscriptRole.ASSISTANT -> "assistant"
                TranscriptRole.TOOL -> "tool"
            }
            val text = ContextPruner.prune(if (sanitizePii) PiiSanitizer.sanitize(msg.text) else msg.text)
            jw.beginObject()
            jw.name("role").value(roleStr)
            jw.name("content").value(text)
            jw.endObject()
        }
        jw.endArray()
        jw.endObject()
    }

    private fun exportShareGpt(session: ChatSession, messages: List<TranscriptMessage>, sanitizePii: Boolean): String {
        val conversations = JSONArray()
        messages.forEach { msg ->
            val from = when (msg.role) {
                TranscriptRole.USER -> "human"
                TranscriptRole.ASSISTANT -> "gpt"
                TranscriptRole.TOOL -> "tool"
            }
            val text = ContextPruner.prune(if (sanitizePii) PiiSanitizer.sanitize(msg.text) else msg.text)
            conversations.put(
                JSONObject()
                    .put("from", from)
                    .put("value", text)
            )
        }
        val record = JSONObject()
            .put("id", session.id)
            .put("model", session.modelId ?: "unknown")
            .put("conversations", conversations)
        return record.toString(2)
    }

    private fun exportAlpaca(session: ChatSession, messages: List<TranscriptMessage>, sanitizePii: Boolean): String {
        val records = JSONArray()
        var lastUserText = ""

        messages.forEach { msg ->
            val text = ContextPruner.prune(if (sanitizePii) PiiSanitizer.sanitize(msg.text) else msg.text)
            when (msg.role) {
                TranscriptRole.USER -> lastUserText = text
                TranscriptRole.ASSISTANT -> {
                    if (lastUserText.isNotBlank()) {
                        val record = JSONObject()
                            .put("instruction", lastUserText)
                            .put("input", "")
                            .put("output", text)
                            .put("model", session.modelId ?: "unknown")
                        records.put(record)
                        lastUserText = ""
                    }
                }
                TranscriptRole.TOOL -> {}
            }
        }
        return records.toString(2)
    }

    private fun exportOpenAiMessages(session: ChatSession, messages: List<TranscriptMessage>, sanitizePii: Boolean): String {
        val msgArray = JSONArray()
        messages.forEach { msg ->
            val roleStr = when (msg.role) {
                TranscriptRole.USER -> "user"
                TranscriptRole.ASSISTANT -> "assistant"
                TranscriptRole.TOOL -> "tool"
            }
            val text = ContextPruner.prune(if (sanitizePii) PiiSanitizer.sanitize(msg.text) else msg.text)
            msgArray.put(
                JSONObject()
                    .put("role", roleStr)
                    .put("content", text)
            )
        }
        val record = JSONObject()
            .put("id", session.id)
            .put("model", session.modelId ?: "unknown")
            .put("messages", msgArray)
        return record.toString(2)
    }
}
