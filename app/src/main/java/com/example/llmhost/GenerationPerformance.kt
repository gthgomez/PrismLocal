package com.example.llmhost

data class GenerationPerformance(
    val promptEvalMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val generatedTokens: Int,
    val tokensPerSecond: Double,
    val settings: GenerationSettings,
    val terminalReason: String? = null,
) {
    val isComplete: Boolean = terminalReason != null
}
