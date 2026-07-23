package com.prismai.llmhost.engine
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.SharedPreferences
import com.prismai.llmhost.GenerationSettings

/**
 * Pure SharedPreferences wrapper for persisting and loading generation settings.
 * Extracted from InferenceService to isolate persistence from service lifecycle.
 */
class EngineConfigStore(private val prefs: SharedPreferences) {

    fun load(): GenerationSettings = GenerationSettings(
        maxTokens = prefs.getInt(KEY_MAX_TOKENS, GenerationSettings.DEFAULT_MAX_TOKENS),
        threadCount = prefs.getInt(KEY_THREAD_COUNT, GenerationSettings.DEFAULT_THREAD_COUNT),
        contextLength = prefs.getInt(KEY_CONTEXT_LENGTH, GenerationSettings.DEFAULT_CONTEXT_LENGTH),
        batchSize = prefs.getInt(KEY_BATCH_SIZE, GenerationSettings.DEFAULT_BATCH_SIZE),
        temperature = prefs.getFloat(KEY_TEMPERATURE, GenerationSettings.DEFAULT_TEMPERATURE),
        topK = prefs.getInt(KEY_TOP_K, GenerationSettings.DEFAULT_TOP_K),
        topP = prefs.getFloat(KEY_TOP_P, GenerationSettings.DEFAULT_TOP_P),
        repeatPenalty = prefs.getFloat(KEY_REPEAT_PENALTY, GenerationSettings.DEFAULT_REPEAT_PENALTY),
        gpuLayers = prefs.getInt(KEY_GPU_LAYERS, GenerationSettings.DEFAULT_GPU_LAYERS),
        agentEnabled = prefs.getBoolean(KEY_AGENT_ENABLED, false),
    ).clamped()

    fun save(settings: GenerationSettings) {
        prefs.edit()
            .putInt(KEY_MAX_TOKENS, settings.maxTokens)
            .putInt(KEY_THREAD_COUNT, settings.threadCount)
            .putInt(KEY_CONTEXT_LENGTH, settings.contextLength)
            .putInt(KEY_BATCH_SIZE, settings.batchSize)
            .putFloat(KEY_TEMPERATURE, settings.temperature)
            .putInt(KEY_TOP_K, settings.topK)
            .putFloat(KEY_TOP_P, settings.topP)
            .putFloat(KEY_REPEAT_PENALTY, settings.repeatPenalty)
            .putInt(KEY_GPU_LAYERS, settings.gpuLayers)
            .putBoolean(KEY_AGENT_ENABLED, settings.agentEnabled)
            .apply()
    }

    companion object {
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_THREAD_COUNT = "thread_count"
        const val KEY_CONTEXT_LENGTH = "context_length"
        const val KEY_BATCH_SIZE = "batch_size"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_K = "top_k"
        const val KEY_TOP_P = "top_p"
        const val KEY_REPEAT_PENALTY = "repeat_penalty"
        const val KEY_GPU_LAYERS = "gpu_layers"
        const val KEY_AGENT_ENABLED = "agent_enabled"

        fun requiresReload(previous: GenerationSettings, next: GenerationSettings): Boolean =
            previous.contextLength != next.contextLength ||
                previous.batchSize != next.batchSize ||
                previous.gpuLayers != next.gpuLayers
    }
}
