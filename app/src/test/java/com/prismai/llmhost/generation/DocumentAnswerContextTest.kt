package com.prismai.llmhost.generation

import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import com.prismai.llmhost.storage.DocumentSearchResult
import com.prismai.llmhost.storage.VectorChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAnswerContextTest {
    private fun chunk(document: String, text: String) =
        VectorChunk("id", document, 2, text, floatArrayOf(1f))

    @Test
    fun passageIsUntrustedAndSourceContainsTheSameVersionedText() {
        val result = DocumentSearchResult("a".repeat(64), listOf(chunk("cedar", "Owner: Mira Chen") to 0.8f))
        val context = DocumentAnswerContext.create("cedar", result)
        val messages = context.messages("Who owns Cedar?")
        assertEquals(2, messages.size)
        assertTrue(messages[0].content.contains("untrusted", ignoreCase = true))
        assertTrue(messages[0].content.contains("not stated", ignoreCase = true))
        assertTrue(messages[1].content.contains("Owner: Mira Chen"))
        assertTrue(context.sources().contains("Owner: Mira Chen"))
        assertTrue(context.sources().contains("a".repeat(64)))
        assertTrue(context.sources().contains("chunk 2"))
    }

    @Test
    fun emptyOrForeignPassagesCannotBecomeADocumentAnswer() {
        assertThrows(IllegalStateException::class.java) {
            DocumentAnswerContext.create("cedar", DocumentSearchResult("", emptyList()))
        }
        assertThrows(IllegalStateException::class.java) {
            DocumentAnswerContext.create("cedar", DocumentSearchResult("a".repeat(64), listOf(chunk("other", "Owen") to 1f)))
        }
    }

    @Test
    fun normalChatDoesNotReplayDocumentScopedTurns() {
        val transcript = listOf(
            TranscriptMessage(1, TranscriptRole.USER, "general question"),
            TranscriptMessage(2, TranscriptRole.ASSISTANT, "general answer"),
            TranscriptMessage(3, TranscriptRole.USER, "Cedar owner?", documentScope = "cedar"),
            TranscriptMessage(4, TranscriptRole.ASSISTANT, "Mira", documentScope = "cedar"),
        )
        val messages = PromptBuilder.assembleChatMessages("next", transcript, null, "", 4096)
        assertTrue(messages.any { it.content == "general answer" })
        assertFalse(messages.any { it.content.contains("Cedar") || it.content == "Mira" })
    }
}
