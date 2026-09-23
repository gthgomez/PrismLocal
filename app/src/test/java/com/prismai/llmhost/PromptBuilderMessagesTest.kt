package com.prismai.llmhost

import com.prismai.llmhost.bridge.ChatMessage
import com.prismai.llmhost.generation.PromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (no device) tests for [PromptBuilder.assembleChatMessages], the
 * role-preserving counterpart to the legacy string prompt path. These cover the
 * assembly contract: system framing, newest-first history budgeting, exclusion
 * of blank/active turns, and the always-present final user message.
 */
class PromptBuilderMessagesTest {

    private fun message(
        id: Long,
        role: TranscriptRole,
        text: String,
        summary: String? = null,
    ): TranscriptMessage = TranscriptMessage(id = id, role = role, text = text, summary = summary)

    @Test
    fun rolesAndOrderPreserveHistoryInterleaving() {
        val transcript = listOf(
            message(1, TranscriptRole.USER, "hello"),
            message(2, TranscriptRole.ASSISTANT, "hi there"),
            message(3, TranscriptRole.USER, "how are you?"),
        )

        val result = PromptBuilder.assembleChatMessages("what's up?", transcript, null, "", 4096)

        assertEquals(
            listOf(
                ChatMessage.ROLE_SYSTEM,
                ChatMessage.ROLE_USER,
                ChatMessage.ROLE_ASSISTANT,
                ChatMessage.ROLE_USER,
                ChatMessage.ROLE_USER,
            ),
            result.map { it.role },
        )
        assertEquals("hello", result[1].content)
        assertEquals("hi there", result[2].content)
        assertEquals("how are you?", result[3].content)
        assertEquals(ChatMessage.ROLE_USER, result.last().role)
        assertEquals("what's up?", result.last().content)
    }

    @Test
    fun activeAssistantTurnIsExcluded() {
        val transcript = listOf(
            message(1, TranscriptRole.USER, "question"),
            message(2, TranscriptRole.ASSISTANT, "partial answer"),
            message(3, TranscriptRole.USER, "follow up"),
        )

        val result = PromptBuilder.assembleChatMessages("next", transcript, 2L, "", 4096)

        assertTrue(result.none { it.content == "partial answer" })
        assertEquals(
            listOf(ChatMessage.ROLE_SYSTEM, ChatMessage.ROLE_USER, ChatMessage.ROLE_USER, ChatMessage.ROLE_USER),
            result.map { it.role },
        )
        assertEquals("question", result[1].content)
        assertEquals("follow up", result[2].content)
    }

    @Test
    fun blankTranscriptEntriesAreExcluded() {
        val transcript = listOf(
            message(1, TranscriptRole.USER, "real"),
            message(2, TranscriptRole.ASSISTANT, "   "),
            message(3, TranscriptRole.USER, ""),
        )

        val result = PromptBuilder.assembleChatMessages("q", transcript, null, "", 4096)

        assertEquals(
            listOf(ChatMessage.ROLE_SYSTEM, ChatMessage.ROLE_USER, ChatMessage.ROLE_USER),
            result.map { it.role },
        )
        assertEquals("real", result[1].content)
        assertEquals("q", result.last().content)
    }

    @Test
    fun toolTurnBecomesUserWithToolResultPrefix() {
        val transcript = listOf(message(1, TranscriptRole.TOOL, "42 degrees"))

        val result = PromptBuilder.assembleChatMessages("thanks", transcript, null, "", 4096)

        assertEquals(3, result.size)
        assertEquals(ChatMessage.ROLE_USER, result[1].role)
        assertTrue(result[1].content.startsWith("[tool result]"))
        assertEquals("[tool result] 42 degrees", result[1].content)
        assertEquals("thanks", result.last().content)
    }

    @Test
    fun emptyTranscriptAndMemoryStillFrameFinalUser() {
        val result = PromptBuilder.assembleChatMessages("just this", emptyList(), null, "", 4096)

        assertEquals(2, result.size)
        assertEquals(ChatMessage.ROLE_SYSTEM, result.first().role)
        assertEquals(ChatMessage.ROLE_USER, result.last().role)
        assertEquals("just this", result.last().content)
    }

    @Test
    fun tinyTokenBudgetKeepsFramingAndFinalUserLast() {
        val result = PromptBuilder.assembleChatMessages("hello world", emptyList(), null, "", 1)

        assertEquals(2, result.size)
        assertEquals(ChatMessage.ROLE_SYSTEM, result.first().role)
        assertEquals(ChatMessage.ROLE_USER, result.last().role)
        assertEquals("hello world", result.last().content)
    }

    @Test
    fun budgetDropsOlderTurnsButKeepsNewest() {
        val older = "x".repeat(400)  // ~100 estimated tokens
        val newest = "y".repeat(400) // ~100 estimated tokens
        val transcript = listOf(
            message(1, TranscriptRole.USER, older),
            message(2, TranscriptRole.USER, newest),
        )

        val result = PromptBuilder.assembleChatMessages("q", transcript, null, "", 150)

        // The newest turn fits and is always kept; the older turn would exceed the budget.
        assertEquals(3, result.size)
        assertEquals(newest, result[1].content)
        assertEquals("q", result.last().content)
    }

    @Test
    fun budgetExceededDropsHistoryWithoutViolatingBudget() {
        val largeMessage = "x".repeat(400) // ~100 estimated tokens
        val transcript = listOf(
            message(1, TranscriptRole.USER, largeMessage),
        )

        // Budget is 50, but largeMessage is ~100 tokens. It must NOT be forced in.
        val result = PromptBuilder.assembleChatMessages("q", transcript, null, "", 50)

        assertEquals(2, result.size)
        assertEquals(ChatMessage.ROLE_SYSTEM, result.first().role)
        assertEquals(ChatMessage.ROLE_USER, result.last().role)
        assertEquals("q", result.last().content)
    }
}
