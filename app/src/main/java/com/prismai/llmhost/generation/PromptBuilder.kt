package com.prismai.llmhost.generation

import com.prismai.llmhost.MemoryRetriever
import com.prismai.llmhost.RagManager
import com.prismai.llmhost.SqlMemoryStore
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import java.util.ArrayDeque

/**
 * Builds prompt strings for the generation engine by assembling system
 * instructions, memory context, RAG context, and recent conversation history.
 *
 * Pure formatting logic — no Android lifecycle or coroutine dependencies.
 */
class PromptBuilder(
    private val memoryStore: SqlMemoryStore,
    private val ragManager: RagManager,
) {
    companion object {
        private const val MAX_PROMPT_CONTEXT_CHARS = 8_000
    }

    // ── Memory context ──────────────────────────────────────────────────

    fun buildMemoryContext(userPrompt: String): String {
        val memories = runCatching { memoryStore.getAllActive() }.getOrDefault(emptyList())
        return MemoryRetriever.buildMemoryContext(userPrompt, memories)
    }

    // ── RAG context ─────────────────────────────────────────────────────

    suspend fun buildRagContext(userPrompt: String): String {
        if (userPrompt.isBlank()) return ""
        return runCatching {
            val chunks = ragManager.query(userPrompt, topK = 3)
            if (chunks.isEmpty()) "" else ragManager.buildRagContext(chunks, maxChars = 2000)
        }.getOrDefault("")
    }

    // ── Full prompt with recent context ─────────────────────────────────

    fun buildPromptWithRecentContext(
        newPrompt: String,
        transcript: List<TranscriptMessage>,
        activeAssistantTranscriptId: Long?,
    ): String {
        val memoryContext = buildMemoryContext(newPrompt)
        val history = transcript.filter { message ->
            message.text.isNotBlank() && message.id != activeAssistantTranscriptId
        }
        if (history.isEmpty() && memoryContext.isEmpty()) {
            return newPrompt
        }
        val selected = ArrayDeque<TranscriptMessage>()
        var chars = newPrompt.length + memoryContext.length
        for (message in history.asReversed()) {
            val formatted = message.asPromptLine()
            if (chars + formatted.length > MAX_PROMPT_CONTEXT_CHARS && selected.isNotEmpty()) {
                break
            }
            selected.addFirst(message)
            chars += formatted.length
        }
        return buildString {
            appendLine("You are Assistant in a local Android chat. Use the recent conversation for context.")
            if (memoryContext.isNotEmpty()) {
                appendLine()
                appendLine(memoryContext)
            }
            appendLine()
            selected.forEach { message ->
                appendLine(message.asPromptLine())
            }
            append("User: ")
            appendLine(newPrompt)
            append("Assistant:")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun TranscriptMessage.asPromptLine(): String =
        when (role) {
            TranscriptRole.USER -> "User: $text"
            TranscriptRole.ASSISTANT -> "Assistant: $text"
            TranscriptRole.TOOL -> summary?.let { "Tool: $it" } ?: "Tool: $text"
        }
}
