package com.prismai.llmhost

data class GenerationPerformance(
    val promptEvalMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val generatedTokens: Int,
    val promptTokens: Int = 0,
    val tokensPerSecond: Double,
    val settings: GenerationSettings,
    val terminalReason: String? = null,
) {
    val isComplete: Boolean = terminalReason != null
    // TODO: wire up promptTokens from native prompt_eval_done log line
}
