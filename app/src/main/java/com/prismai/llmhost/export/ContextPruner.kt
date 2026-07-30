package com.prismai.llmhost.export

object ContextPruner {

    private val TOOL_CALL_TAG_REGEX = Regex("(?s)<tool_call>.*?</tool_call>")
    private val SYSTEM_TAG_REGEX = Regex("(?s)<system>.*?</system>")
    private val JSON_BLOCK_REGEX = Regex("(?s)\\{[^{}]*\"action\"[^{}]*\\}")

    /**
     * Compresses prompt context while strictly preserving protected tool call schemas
     * and system prompt instructions.
     */
    fun prune(prompt: String): String {
        if (prompt.isBlank()) return ""

        // Preserve tool call and system blocks as protected placeholders
        val protectedBlocks = mutableListOf<String>()
        var protectedText = prompt

        protectedText = TOOL_CALL_TAG_REGEX.replace(protectedText) { match ->
            protectedBlocks.add(match.value)
            "___PROTECTED_BLOCK_${protectedBlocks.size - 1}___"
        }

        protectedText = SYSTEM_TAG_REGEX.replace(protectedText) { match ->
            protectedBlocks.add(match.value)
            "___PROTECTED_BLOCK_${protectedBlocks.size - 1}___"
        }

        protectedText = JSON_BLOCK_REGEX.replace(protectedText) { match ->
            protectedBlocks.add(match.value)
            "___PROTECTED_BLOCK_${protectedBlocks.size - 1}___"
        }

        // Compress whitespace and redundant line breaks in non-protected text
        var cleaned = protectedText
            .replace(Regex("\\r\\n|\\r"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .replace(Regex("[ \\t]+"), " ")
            .trim()

        // Restore protected blocks exactly
        protectedBlocks.forEachIndexed { idx, block ->
            cleaned = cleaned.replace("___PROTECTED_BLOCK_${idx}___", block)
        }

        return cleaned
    }
}
