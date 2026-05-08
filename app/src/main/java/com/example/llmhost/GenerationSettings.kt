package com.example.llmhost

data class GenerationSettings(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val threadCount: Int = DEFAULT_THREAD_COUNT,
) {
    fun clamped(): GenerationSettings =
        GenerationSettings(
            maxTokens = maxTokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS),
            threadCount = threadCount.coerceIn(MIN_THREAD_COUNT, MAX_THREAD_COUNT),
        )

    companion object {
        const val MIN_MAX_TOKENS = 1
        const val DEFAULT_MAX_TOKENS = 128
        const val MAX_MAX_TOKENS = 512
        const val MAX_TOKEN_STEP = 32

        const val MIN_THREAD_COUNT = 1
        const val DEFAULT_THREAD_COUNT = 6
        const val MAX_THREAD_COUNT = 8
    }
}
