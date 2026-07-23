package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

data class GenerationSettings(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val threadCount: Int = DEFAULT_THREAD_COUNT,
    val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    val repeatPenalty: Float = DEFAULT_REPEAT_PENALTY,
    val gpuLayers: Int = DEFAULT_GPU_LAYERS,
    val agentEnabled: Boolean = false,
    val maxAgentIterations: Int = DEFAULT_MAX_AGENT_ITERATIONS,
) {
    fun clamped(): GenerationSettings =
        GenerationSettings(
            maxTokens = maxTokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS),
            threadCount = threadCount.coerceIn(MIN_THREAD_COUNT, MAX_THREAD_COUNT),
            contextLength = snapToStep(contextLength, CONTEXT_LENGTH_STEP)
                .coerceIn(MIN_CONTEXT_LENGTH, MAX_CONTEXT_LENGTH),
            batchSize = snapToStep(batchSize, BATCH_SIZE_STEP)
                .coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE),
            temperature = temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE),
            topK = topK.coerceIn(MIN_TOP_K, MAX_TOP_K),
            topP = topP.coerceIn(MIN_TOP_P, MAX_TOP_P),
            repeatPenalty = repeatPenalty.coerceIn(MIN_REPEAT_PENALTY, MAX_REPEAT_PENALTY),
            gpuLayers = gpuLayers.coerceIn(MIN_GPU_LAYERS, MAX_GPU_LAYERS),
            agentEnabled = agentEnabled,
            maxAgentIterations = maxAgentIterations.coerceIn(MIN_MAX_AGENT_ITERATIONS, MAX_MAX_AGENT_ITERATIONS),
        )

    companion object {
        const val MIN_MAX_TOKENS = 1
        const val DEFAULT_MAX_TOKENS = 256
        const val MAX_MAX_TOKENS = 1024
        const val MAX_TOKEN_STEP = 32

        const val MIN_THREAD_COUNT = 1
        const val DEFAULT_THREAD_COUNT = 6
        const val MAX_THREAD_COUNT = 8

        const val MIN_CONTEXT_LENGTH = 512
        const val DEFAULT_CONTEXT_LENGTH = 2048
        const val MAX_CONTEXT_LENGTH = 16384
        const val CONTEXT_LENGTH_STEP = 512

        const val MIN_BATCH_SIZE = 128
        const val DEFAULT_BATCH_SIZE = 512
        const val MAX_BATCH_SIZE = 2048
        const val BATCH_SIZE_STEP = 128

        const val MIN_TEMPERATURE = 0.05f
        const val DEFAULT_TEMPERATURE = 0.70f
        const val MAX_TEMPERATURE = 1.50f

        const val MIN_TOP_K = 1
        const val DEFAULT_TOP_K = 40
        const val MAX_TOP_K = 100

        const val MIN_TOP_P = 0.05f
        const val DEFAULT_TOP_P = 0.95f
        const val MAX_TOP_P = 1.0f

        const val MIN_REPEAT_PENALTY = 1.0f
        const val DEFAULT_REPEAT_PENALTY = 1.10f
        const val MAX_REPEAT_PENALTY = 1.50f

        const val MIN_GPU_LAYERS = 0
        const val DEFAULT_GPU_LAYERS = 0
        const val MAX_GPU_LAYERS = 99

        const val MIN_MAX_AGENT_ITERATIONS = 1
        const val DEFAULT_MAX_AGENT_ITERATIONS = 5
        const val MAX_MAX_AGENT_ITERATIONS = 12

        private fun snapToStep(value: Int, step: Int): Int =
            ((value + step / 2) / step) * step
    }

    fun toAgentJson(): org.json.JSONObject =
        org.json.JSONObject()
            .put("max_tokens", maxTokens)
            .put("threads", threadCount)
            .put("context_length", contextLength)
            .put("batch_size", batchSize)
            .put("temperature", temperature.toDouble())
            .put("top_k", topK)
            .put("top_p", topP.toDouble())
            .put("repeat_penalty", repeatPenalty.toDouble())
            .put("gpu_layers", gpuLayers)
}
