package com.example.llmhost

enum class TranscriptRole {
    USER,
    ASSISTANT,
    TOOL,
}

data class TranscriptMessage(
    val id: Long,
    val role: TranscriptRole,
    val text: String,
    val summary: String? = null,
)
