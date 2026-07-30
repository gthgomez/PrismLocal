package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

data class BenchmarkPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val threadCountOverride: Int? = null,
    val maxTokensOverride: Int? = null,
    val temperatureOverride: Float? = null,
    val topPOverride: Float? = null,
    val topKOverride: Int? = null,
    val repeatPenaltyOverride: Float? = null,
    /** When true, mid-stream QualityGuard may stop degenerate output (slice B3). */
    val enableQualityGuard: Boolean = false,
    val suiteId: String? = null,
) {
    /**
     * Applies non-null preset overrides on top of [base] for a single generation.
     * Does not mutate persisted user settings — call sites must keep the result local.
     */
    fun applySettingsOverrides(base: GenerationSettings): GenerationSettings =
        base.copy(
            maxTokens = maxTokensOverride ?: base.maxTokens,
            threadCount = threadCountOverride ?: base.threadCount,
            temperature = temperatureOverride ?: base.temperature,
            topP = topPOverride ?: base.topP,
            topK = topKOverride ?: base.topK,
            repeatPenalty = repeatPenaltyOverride ?: base.repeatPenalty,
        ).clamped()
}

data class BenchmarkStatus(
    val isRunning: Boolean = false,
    val presetId: String? = null,
    val presetName: String? = null,
    val startedAt: Long? = null,
)

object BenchmarkPresets {
    private const val THREAD_SWEEP_PROMPT =
        "Benchmark thread scaling. Reply with one concise paragraph about local LLM performance on Android."

    /** Coding preset token cap (plan B1: 256–384 band). */
    const val CODING_MAX_TOKENS = 320
    const val CODING_TEMPERATURE = 0.25f
    const val CODING_TOP_P = 0.90f
    const val CODING_TOP_K = 40
    const val CODING_REPEAT_PENALTY = 1.18f

    val defaults: List<BenchmarkPreset> = listOf(
        BenchmarkPreset(
            id = "short_answer",
            name = "Short Answer",
            prompt = "In three concise bullet points, explain what makes local on-device AI useful.",
        ),
        BenchmarkPreset(
            id = "coding",
            name = "Python Coding",
            prompt = "Write a small Python function that returns the median value from a list of int values, include type hints, and explain the edge cases.",
            maxTokensOverride = CODING_MAX_TOKENS,
            temperatureOverride = CODING_TEMPERATURE,
            topPOverride = CODING_TOP_P,
            topKOverride = CODING_TOP_K,
            repeatPenaltyOverride = CODING_REPEAT_PENALTY,
            enableQualityGuard = true,
        ),
        BenchmarkPreset(
            id = "json",
            name = "JSON",
            prompt = "Return only valid JSON describing three benchmark metrics for a local LLM app: first_token_ms, tokens_per_second, and peak_memory_mb.",
        ),
        BenchmarkPreset(
            id = "long_form",
            name = "Long Form",
            prompt = "Draft a practical release-note section for an Android app that added local model import progress, loading indicators, and benchmark export.",
        ),
        BenchmarkPreset(
            id = "reasoning",
            name = "Reasoning",
            prompt = "A phone can run model A at 9 tokens per second with 2.2 GB RAM free, and model B at 14 tokens per second with 1.1 GB RAM free. Which model should a user choose for long chats, and why?",
        ),
    )

    val threadSweep: List<BenchmarkPreset> = listOf(2, 4, 6, 8).map { threads ->
        BenchmarkPreset(
            id = "thread_sweep_$threads",
            name = "Thread Sweep ${threads}T",
            prompt = THREAD_SWEEP_PROMPT,
            threadCountOverride = threads,
            maxTokensOverride = 64,
            suiteId = "thread_sweep",
        )
    }

    fun find(id: String): BenchmarkPreset? =
        (defaults + threadSweep).firstOrNull { it.id == id }
}
