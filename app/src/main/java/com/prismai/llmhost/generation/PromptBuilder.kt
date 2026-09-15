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
        private const val SYSTEM_PROMPT =
            "You are Assistant in a local Android chat. Use the recent conversation for context."

        /**
         * Pure, Android-free assembly of the structured (role-preserving) message
         * list. Mirrors the recent-history token budgeting of
         * [buildPromptWithRecentContext] so both paths select the same turns.
         */
        internal fun assembleChatMessages(
            newPrompt: String,
            transcript: List<TranscriptMessage>,
            activeAssistantTranscriptId: Long?,
            memoryContext: String,
            tokenBudget: Int,
        ): List<ChatMessage> {
            val system = buildString {
                append(SYSTEM_PROMPT)
                if (memoryContext.isNotBlank()) {
                    append("\n\n")
                    append(memoryContext)
                }
            }
            val history = transcript.filter { message ->
                message.text.isNotBlank() && message.id != activeAssistantTranscriptId
            }
            val selected = ArrayDeque<TranscriptMessage>()
            var estimatedTokens = (newPrompt.length + memoryContext.length) / 4
            for (message in history.asReversed()) {
                val tokens = message.toChatMessage().content.length / 4
                if (estimatedTokens + tokens <= tokenBudget || selected.isEmpty()) {
                    selected.addFirst(message)
                    estimatedTokens += tokens
                } else {
                    break
                }
            }
            val messages = ArrayList<ChatMessage>(selected.size + 2)
            messages += ChatMessage(ChatMessage.ROLE_SYSTEM, system)
            selected.forEach { message -> messages += message.toChatMessage() }
            messages += ChatMessage(ChatMessage.ROLE_USER, newPrompt)
            return messages
        }

        private fun TranscriptMessage.toChatMessage(): ChatMessage =
            when (role) {
                TranscriptRole.USER -> ChatMessage(ChatMessage.ROLE_USER, text)
                TranscriptRole.ASSISTANT -> ChatMessage(ChatMessage.ROLE_ASSISTANT, text)
                TranscriptRole.TOOL ->
                    ChatMessage(ChatMessage.ROLE_USER, "[tool result] ${if (text.isBlank()) summary.orEmpty() else text}")
            }
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

    // ── Structured messages ─────────────────────────────────────────────

    /**
     * Role-preserving counterpart to [buildPromptWithRecentContext] for the
     * structured generation path. Delegates the pure assembly to
     * [assembleChatMessages] so it can be unit-tested without Android deps.
     */
    fun buildMessages(
        newPrompt: String,
        transcript: List<TranscriptMessage>,
        activeAssistantTranscriptId: Long?,
        memoryContext: String = "",
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    ): List<ChatMessage> = assembleChatMessages(
        newPrompt = newPrompt,
        transcript = transcript,
        activeAssistantTranscriptId = activeAssistantTranscriptId,
        memoryContext = memoryContext,
        tokenBudget = tokenBudget,
    )

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun TranscriptMessage.asPromptLine(): String =
        when (role) {
            TranscriptRole.USER -> "User: $text"
            TranscriptRole.ASSISTANT -> "Assistant: $text"
            TranscriptRole.TOOL -> summary?.let { "Tool: $it" } ?: "Tool: $text"
        }
}
