package com.example.llmhost

data class GenerationChunk(
    val text: String,
    val tokenCount: Int,
    val generationId: Int,
    val isTerminal: Boolean,
    val terminalReason: String = "NONE",
)
