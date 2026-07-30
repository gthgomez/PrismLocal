package com.prismai.llmhost.generation
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import com.prismai.llmhost.MemoryRetriever
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
    private val memoryStore: MemoryStore,
    private val ragManager: RagManager? = null,
) {
    companion object {
        private const val DEFAULT_TOKEN_BUDGET = 3_072
    }

    // ── Memory context ──────────────────────────────────────────────────

    fun buildMemoryContext(userPrompt: String): String {
        val memories = runCatching { memoryStore.getAllActive() }.getOrDefault(emptyList())
        return MemoryRetriever.buildMemoryContext(userPrompt, memories)
    }

    // ── RAG context ─────────────────────────────────────────────────────

    suspend fun buildRagContext(userPrompt: String): String {
        if (userPrompt.isBlank()) return ""
        val rag = ragManager ?: return ""
        return runCatching {
            val chunks = rag.query(userPrompt, topK = 3)
            if (chunks.isEmpty()) "" else rag.buildRagContext(chunks, maxChars = 2000)
        }.getOrDefault("")
    }

    // ── Full prompt with recent context ─────────────────────────────────

    fun buildPromptWithRecentContext(
        newPrompt: String,
        transcript: List<TranscriptMessage>,
        activeAssistantTranscriptId: Long?,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    ): String {
        val memoryContext = buildMemoryContext(newPrompt)
        val history = transcript.filter { message ->
            message.text.isNotBlank() && message.id != activeAssistantTranscriptId
        }
        if (history.isEmpty() && memoryContext.isEmpty()) {
            return newPrompt
        }
        val selected = ArrayDeque<TranscriptMessage>()
        var estimatedTokens = (newPrompt.length + memoryContext.length) / 4
        for (message in history.asReversed()) {
            val formatted = message.asPromptLine()
            val tokens = (formatted.length / 4) + 1
            if (estimatedTokens + tokens <= tokenBudget || selected.isEmpty()) {
                selected.addFirst(message)
                estimatedTokens += tokens
            } else {
                break
            }
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
