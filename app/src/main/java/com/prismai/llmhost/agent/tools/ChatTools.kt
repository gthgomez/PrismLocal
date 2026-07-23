package com.prismai.llmhost.agent.tools
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import com.prismai.llmhost.*
import com.prismai.llmhost.chat.ChatManager
import com.prismai.llmhost.chat.ChatSearchIndex
import com.prismai.llmhost.chat.TranscriptStore
import com.prismai.llmhost.export.ChatExporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class ChatTools(
    private val chatManager: ChatManager,
    private val chatSearchIndex: ChatSearchIndex,
    private val transcriptStore: TranscriptStore,
    private val currentChatId: () -> String?,
    private val chatSessions: () -> List<ChatSession>,
    private val transcript: () -> List<TranscriptMessage>,
    private val isGenerating: () -> Boolean,
    private val filesDir: File,
    private val onPersistTranscript: () -> Unit,
    private val onSwitchChat: (String) -> Boolean,
    private val onClearTranscript: () -> Unit,
    private val onDeleteChat: (String) -> Unit,
    private val onRenameChat: (String, String) -> Unit,
    private val onResetNativeConversation: (String) -> Unit,
) {
    fun summarizeCurrentChat(call: AgentToolCall): AgentToolResult {
        val recentCount = call.arguments.optInt("recent_messages", 6).coerceIn(1, 30)
        val style = call.arguments.optString("summary_style", "brief").lowercase(Locale.US)
        val messages = transcript()
        val userTurns = messages.count { it.role == TranscriptRole.USER }
        val assistantTurns = messages.count { it.role == TranscriptRole.ASSISTANT && it.text.isNotBlank() }
        val latestUser = messages.lastOrNull { it.role == TranscriptRole.USER }?.text.orEmpty()
        val latestAssistant = messages.lastOrNull { it.role == TranscriptRole.ASSISTANT && it.text.isNotBlank() }?.text.orEmpty()
        val recent = JSONArray()
        messages.takeLast(recentCount).forEach { message ->
            recent.put(JSONObject()
                .put("role", message.role.name.lowercase(Locale.US))
                .put("text", message.text.compactForAgent(360)))
        }
        val currentTitle = chatSessions().firstOrNull { it.id == currentChatId() }?.title ?: ChatTitles.DEFAULT_TITLE
        val summary = if (messages.isEmpty()) "Current chat is empty"
        else "$currentTitle: $userTurns user turn${if (userTurns == 1) "" else "s"}, $assistantTurns assistant response${if (assistantTurns == 1) "" else "s"}"
        return AgentToolResult(call = call, success = true, summary = summary,
            details = JSONObject()
                .put("chat_id", currentChatId() ?: "none").put("untrusted_data", true)
                .put("summary_style", style).put("title", currentTitle)
                .put("message_count", messages.size).put("user_turns", userTurns)
                .put("assistant_turns", assistantTurns)
                .put("latest_user", latestUser.compactForAgent(500))
                .put("latest_assistant", latestAssistant.compactForAgent(500))
                .put("recent", recent))
    }

    fun renameCurrentChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return AgentToolResult(call, false, "Chat rename requires confirmation")
        val chatId = currentChatId() ?: return AgentToolResult(call, false, "No active chat")
        val messages = transcript()
        val requestedTitle = call.arguments.optString("title").takeIf { it.isNotBlank() }
            ?: messages.firstOrNull { it.role == TranscriptRole.USER }?.text?.let(ChatTitles::fromPrompt)
            ?: ChatTitles.DEFAULT_TITLE
        val safeTitle = requestedTitle.replace(Regex("\\s+"), " ").trim().take(64)
        if (safeTitle.isBlank()) return AgentToolResult(call, false, "Missing chat title")
        onRenameChat(chatId, safeTitle)
        return AgentToolResult(call = call, success = true,
            summary = "Renamed current chat to \"$safeTitle\"",
            details = JSONObject().put("chat_id", chatId).put("title", safeTitle))
    }

    fun searchChats(call: AgentToolCall): AgentToolResult {
        val query = call.arguments.optString("query").replace(Regex("\\s+"), " ").trim().take(120)
        val limit = call.arguments.optInt("limit", 10).coerceIn(1, 25)
        val snippetLength = call.arguments.optInt("snippet_length", 240).coerceIn(80, 360)
        if (query.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Missing search query")
        onPersistTranscript()
        val terms = query.lowercase(Locale.US).split(' ').filter { it.length > 1 }
        val matches = chatSessions().mapNotNull { session ->
            val haystack = chatSearchIndex.get(session.id) ?: buildString {
                append(session.title)
                append(' ')
                transcriptStore.readTranscriptFile(transcriptStore.transcriptFile(session.id)).forEach { append(it.text).append(' ') }
            }.lowercase(Locale.US)
            val score = terms.count { it in haystack } + if (query.lowercase(Locale.US) in session.title.lowercase(Locale.US)) 2 else 0
            if (score <= 0) null else {
                val messages = transcriptStore.readTranscriptFile(transcriptStore.transcriptFile(session.id))
                Triple(session, messages, score)
            }
        }.sortedByDescending { it.third }.take(limit)
        val results = JSONArray()
        matches.forEach { match ->
            val session = match.first
            val sessionMessages = match.second
            val snippet = sessionMessages.firstOrNull { message ->
                terms.any { it in message.text.lowercase(Locale.US) }
            }?.text?.compactForAgent(snippetLength).orEmpty()
            results.put(JSONObject()
                .put("chat_id", session.id).put("title", session.title)
                .put("updated_at", session.updatedAt).put("model_id", session.modelId ?: "unknown")
                .put("message_count", session.messageCount).put("snippet", snippet))
        }
        return AgentToolResult(call = call, success = true,
            summary = "${matches.size} chat match${if (matches.size == 1) "" else "es"} for \"$query\"",
            details = JSONObject().put("query", query).put("untrusted_data", true)
                .put("snippet_length", snippetLength).put("results", results))
    }

    fun exportChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Chat export requires confirmation")
        onPersistTranscript()
        val chatIdArg = call.arguments.optString("chat_id", "current")
        val chatId = if (chatIdArg == "current" || chatIdArg.isBlank()) currentChatId() else chatIdArg
        val session = chatSessions().firstOrNull { it.id == chatId }
            ?: return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "Chat not found")
        val messages = transcriptStore.readTranscriptFile(transcriptStore.transcriptFile(session.id))
        val format = call.arguments.optString("format", "markdown").lowercase(Locale.US)
        val extension = when (format) { "json" -> "json"; "text", "txt" -> "txt"; else -> "md" }
        val content = when (extension) {
            "json" -> ChatExporter.toJson(session, messages).toString(2)
            "txt" -> ChatExporter.toText(session, messages)
            else -> ChatExporter.toMarkdown(session, messages)
        }
        val dir = File(filesDir, "agent_exports").apply { mkdirs() }
        val safeName = AgentSanitizer.sanitizeFileName(session.title).take(48)
        val file = File(dir, "${safeName}_${System.currentTimeMillis()}.$extension")
        file.writeText(content)
        return AgentToolResult(call = call, success = true,
            summary = "Exported ${session.title} as $extension",
            details = JSONObject().put("chat_id", session.id).put("title", session.title)
                .put("format", extension).put("path", file.absolutePath)
                .put("message_count", messages.size).put("privacy_sensitive", true)
                .put("include_metadata", call.arguments.optBoolean("include_metadata", true)))
    }

    fun clearChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Chat clear requires confirmation")
        if (isGenerating()) return toolFailure(call, AgentToolErrorCode.BUSY, "Cancel generation before changing chats")
        val chatIdArg = call.arguments.optString("chat_id", "current")
        val chatId = if (chatIdArg == "current" || chatIdArg.isBlank()) currentChatId() else chatIdArg
        val session = chatSessions().firstOrNull { it.id == chatId }
            ?: return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "Chat not found")
        if (session.id != currentChatId()) onSwitchChat(session.id)
        onClearTranscript()
        return toolSuccess(call, "Cleared ${session.title}",
            JSONObject().put("chat_id", session.id).put("title", session.title).put("action", "clear_messages"))
    }

    fun deleteChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Chat delete requires confirmation")
        if (isGenerating()) return toolFailure(call, AgentToolErrorCode.BUSY, "Cancel generation before changing chats")
        val chatIdArg = call.arguments.optString("chat_id", "current")
        val chatId = if (chatIdArg == "current" || chatIdArg.isBlank()) currentChatId() else chatIdArg
        val session = chatSessions().firstOrNull { it.id == chatId }
            ?: return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "Chat not found")
        onDeleteChat(session.id)
        return toolSuccess(call, "Deleted ${session.title}",
            JSONObject().put("chat_id", session.id).put("title", session.title).put("action", "delete_chat"))
    }

    fun deleteOrClearChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        val action = call.arguments.optString("action", "clear_current")
        return if (action == "delete" || action == "delete_chat") {
            deleteChat(call.copy(name = "delete_chat"), confirmed)
        } else {
            clearChat(call.copy(name = "clear_chat"), confirmed)
        }
    }
}
