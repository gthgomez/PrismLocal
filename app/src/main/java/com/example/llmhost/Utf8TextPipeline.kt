package com.example.llmhost

import java.nio.charset.StandardCharsets

internal object Utf8TextPipeline {
    fun normalizeNativeText(text: String): String {
        if (text.isEmpty()) {
            return text
        }
        val explicitUtf8 = String(text.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
        return buildString(explicitUtf8.length) {
            explicitUtf8.forEach { char ->
                when {
                    char == '\u0000' -> Unit
                    char == '\uFFFD' -> Unit
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
