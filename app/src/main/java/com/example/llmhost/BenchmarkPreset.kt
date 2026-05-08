package com.example.llmhost

data class BenchmarkPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val threadCountOverride: Int? = null,
    val maxTokensOverride: Int? = null,
    val suiteId: String? = null,
)

data class BenchmarkStatus(
    val isRunning: Boolean = false,
    val presetId: String? = null,
    val presetName: String? = null,
    val startedAt: Long? = null,
)

object BenchmarkPresets {
    private const val THREAD_SWEEP_PROMPT =
        "Benchmark thread scaling. Reply with one concise paragraph about local LLM performance on Android."

    val defaults: List<BenchmarkPreset> = listOf(
        BenchmarkPreset(
            id = "short_answer",
            name = "Short Answer",
            prompt = "In three concise bullet points, explain what makes local on-device AI useful.",
        ),
        BenchmarkPreset(
            id = "coding",
            name = "Coding",
            prompt = "Write a small Kotlin function that returns the median value from a list of Int values, and explain the edge cases.",
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
