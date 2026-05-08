package com.example.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatUiTextTest {
    @Test
    fun compactModelNameHidesQuantizationTags() {
        val compact = compactModelName("Huihui-Qwen3-VL-4B-Instruct-abliterated-Q4_K_S.gguf")

        assertEquals("Huihui Qwen3 VL 4B Instruct abliter...", compact)
        assertFalse(compact.contains("Q4_K_S"))
    }

    @Test
    fun compactModelNameHandlesMissingModel() {
        assertEquals("No model selected", compactModelName(null))
    }

    @Test
    fun chatTitleComesFromFirstPrompt() {
        assertEquals(
            "How do you solve quadratics?",
            ChatTitles.fromPrompt("How do you solve quadratics?")
        )
    }
}
