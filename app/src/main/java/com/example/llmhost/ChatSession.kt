package com.example.llmhost

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,
    val messageCount: Int,
)

internal object ChatTitles {
    const val DEFAULT_TITLE = "New chat"

    fun fromPrompt(prompt: String): String {
        val collapsed = prompt
            .replace(Regex("\\s+"), " ")
            .trim()
        if (collapsed.isBlank()) {
            return DEFAULT_TITLE
        }
        return if (collapsed.length <= 42) {
            collapsed
        } else {
            "${collapsed.take(39).trimEnd()}..."
        }
    }
}
