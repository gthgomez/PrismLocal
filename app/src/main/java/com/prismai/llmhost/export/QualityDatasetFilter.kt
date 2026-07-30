package com.prismai.llmhost.export

import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.generation.QualityGuard

object QualityDatasetFilter {

    private const val MIN_TEXT_LENGTH = 8

    /**
     * Filters transcript messages to ensure high training density and zero error/noise pollution.
     */
    fun filterValidMessages(messages: List<TranscriptMessage>): List<TranscriptMessage> {
        return messages.filter { msg ->
            val text = msg.text.trim()
            if (text.length < MIN_TEXT_LENGTH) return@filter false
            if (isErrorOrInterrupted(text)) return@filter false
            if (QualityGuard.looksDegenerate(text)) return@filter false
            true
        }
    }

    private fun isErrorOrInterrupted(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("generation failed in native runtime") ||
               lower.contains("streamstate::error") ||
               lower.contains("tokenize_failed") ||
               lower.contains("context_too_small") ||
               lower.startsWith("error:")
    }
}
