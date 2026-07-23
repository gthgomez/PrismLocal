package com.prismai.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Utf8TextPipelineTest {
    @Test
    fun normalizeNativeTextPreservesEmojiAndSpecialCharacters() {
        val text = "Hello 👋🏽 café ∞ λ 漢字 عربى 🇺🇸 \"quotes\" 'apostrophes' <tags> & symbols\nNext\tTab"

        assertEquals(text, Utf8TextPipeline.normalizeNativeText(text))
    }

    @Test
    fun normalizeNativeTextRemovesReplacementAndNullArtifacts() {
        val normalized = Utf8TextPipeline.normalizeNativeText("\u0000Hello\uFFFD world\u0000")

        assertEquals("Hello world", normalized)
        assertFalse(normalized.contains('\uFFFD'))
        assertFalse(normalized.contains('\u0000'))
    }

    @Test
    fun normalizeChunkPreservesGenerationMetadata() {
        val chunk = GenerationChunk(
            text = "Hi\uFFFD 👋",
            tokenCount = 3,
            generationId = 7,
            isTerminal = false,
        )

        assertEquals(
            GenerationChunk(
                text = "Hi 👋",
                tokenCount = 3,
                generationId = 7,
                isTerminal = false,
            ),
            Utf8TextPipeline.normalizeChunk(chunk),
        )
    }
}
