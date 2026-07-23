package com.prismai.llmhost.generation

import com.prismai.llmhost.BenchmarkPreset
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.ui.ServiceUiState

/**
 * Tracks transient generation metrics during an active generation run and
 * publishes computed [GenerationPerformance] snapshots to UI state.
 *
 * Owns the mutable tracking fields that were previously top-level vars in
 * [InferenceService]; BenchmarkRunner and cancellation paths read them
 * directly for interruption recording.
 */
class GenerationMetrics(private val uiState: ServiceUiState) {

    var activeBenchmarkPreset: BenchmarkPreset? = null
    var activePrompt: String? = null
    var activeStartedAt: Long? = null
    var activeFirstTokenAt: Long? = null
    var activeTokens: Int = 0
    var activePromptTokens: Int = 0
    var activeSettings: GenerationSettings? = null

    fun publishPerformance(
        startedAt: Long,
        firstTokenAt: Long?,
        now: Long,
        generatedTokens: Int,
        settings: GenerationSettings,
        terminalReason: String?,
        promptTokens: Int = 0,
    ): GenerationPerformance {
        val firstTokenOrNow = firstTokenAt ?: now
        val promptEvalMs = (firstTokenOrNow - startedAt).coerceAtLeast(0L)
        val decodeMs = (now - firstTokenOrNow).coerceAtLeast(0L)
        val totalMs = (now - startedAt).coerceAtLeast(0L)
        val tokensPerSecond = if (decodeMs > 0L && generatedTokens > 0) {
            generatedTokens * 1000.0 / decodeMs
        } else {
            0.0
        }
        val performance = GenerationPerformance(
            promptEvalMs = promptEvalMs,
            decodeMs = decodeMs,
            totalMs = totalMs,
            generatedTokens = generatedTokens,
            promptTokens = promptTokens,
            tokensPerSecond = tokensPerSecond,
            settings = settings,
            terminalReason = terminalReason,
        )
        uiState._generationPerformance.value = performance
        return performance
    }

    fun clear() {
        activeBenchmarkPreset = null
        activePrompt = null
        activeStartedAt = null
        activeFirstTokenAt = null
        activeTokens = 0
        activePromptTokens = 0
        activeSettings = null
    }
}
