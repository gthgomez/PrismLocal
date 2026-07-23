package com.prismai.llmhost.bridge
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import java.nio.charset.StandardCharsets

internal object Utf8TextPipeline {
    fun normalizeNativeText(text: String): String {
        if (text.isEmpty()) return text
        // Fast path: check if filtering is even needed
        if (text.all { it == '\t' || it == '\n' || it == '\r' || (!it.isISOControl() && it != '\u0000' && it != '\uFFFD') }) {
            return text
        }
        return buildString(text.length) {
            for (char in text) {
                when {
                    char == '\u0000' || char == '\uFFFD' -> Unit
                    char == '\t' || char == '\n' || char == '\r' -> append(char)
                    !char.isISOControl() -> append(char)
                }
            }
        }
    }

    fun normalizeChunk(chunk: GenerationChunk): GenerationChunk {
        val normalized = normalizeNativeText(chunk.text)
        return if (normalized == chunk.text) {
            chunk
        } else {
            chunk.copy(text = normalized)
        }
    }
}
