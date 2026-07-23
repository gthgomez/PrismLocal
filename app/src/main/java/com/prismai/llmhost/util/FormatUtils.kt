package com.prismai.llmhost.util
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.GenerationSettings
import org.json.JSONObject
import java.util.Locale

/**
 * Shared formatting and validation utilities used by tool classes,
 * [com.prismai.llmhost.InferenceService], and confirmation builders.
 *
 * Centralized here so tool classes can call these directly instead of
 * receiving them as constructor lambdas.
 */
object FormatUtils {

    fun formatBytesForMessage(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        val mib = bytes / (1024.0 * 1024.0)
        return if (gib >= 1.0) {
            "%.2f GiB".format(gib)
        } else {
            "%.1f MiB".format(mib)
        }
    }

    fun compactAgentModelName(modelId: String): String =
        modelId.removeSuffix(".gguf")
            .replace('-', ' ')
            .replace('_', ' ')
            .let { if (it.length <= 42) it else "${it.take(39).trimEnd()}..." }

    fun formatAgentTps(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    fun proposedRuntimeSettings(
        args: JSONObject,
        base: GenerationSettings,
    ): GenerationSettings =
        base.copy(
            maxTokens = if (args.has("max_tokens")) args.optInt("max_tokens", base.maxTokens) else base.maxTokens,
            threadCount = when {
                args.has("threads") -> args.optInt("threads", base.threadCount)
                args.has("thread_count") -> args.optInt("thread_count", base.threadCount)
                else -> base.threadCount
            },
            contextLength = if (args.has("context_length")) args.optInt("context_length", base.contextLength) else base.contextLength,
            batchSize = if (args.has("batch_size")) args.optInt("batch_size", base.batchSize) else base.batchSize,
            temperature = if (args.has("temperature")) args.optDouble("temperature", base.temperature.toDouble()).toFloat() else base.temperature,
            topK = if (args.has("top_k")) args.optInt("top_k", base.topK) else base.topK,
            topP = if (args.has("top_p")) args.optDouble("top_p", base.topP.toDouble()).toFloat() else base.topP,
            repeatPenalty = if (args.has("repeat_penalty")) args.optDouble("repeat_penalty", base.repeatPenalty.toDouble()).toFloat() else base.repeatPenalty,
            gpuLayers = if (args.has("gpu_layers")) args.optInt("gpu_layers", base.gpuLayers) else base.gpuLayers,
        ).clamped()

    fun validateRuntimeSettingsPayload(
        settings: GenerationSettings,
        profile: DeviceCapabilityProfile?,
    ): Pair<String, List<String>> {
        val warnings = mutableListOf<String>()
        if (settings.contextLength > GenerationSettings.DEFAULT_CONTEXT_LENGTH) {
            warnings += "Higher context increases RAM use and may reduce speed."
        }
        if (settings.batchSize > GenerationSettings.DEFAULT_BATCH_SIZE) {
            warnings += "Higher batch can improve prompt processing but uses more memory."
        }
        if (settings.threadCount >= GenerationSettings.MAX_THREAD_COUNT) {
            warnings += "Maximum threads can increase heat and UI contention."
        }
        if ((profile?.batteryPercent ?: 100) < 20 && profile?.isCharging != true) {
            warnings += "Battery is low; long runs may throttle or drain quickly."
        }
        if (profile?.thermalStatus?.contains("moderate", ignoreCase = true) == true ||
            profile?.thermalStatus?.contains("severe", ignoreCase = true) == true
        ) {
            warnings += "Thermal state is ${profile.thermalStatus}; conservative settings are safer."
        }
        val risk = when {
            warnings.any { it.contains("Thermal", ignoreCase = true) } ||
                settings.contextLength >= 8192 ||
                settings.batchSize >= 2048 -> "high"
            warnings.isNotEmpty() -> "medium"
            else -> "low"
        }
        return risk to warnings
    }

    fun settingDiffLines(before: GenerationSettings, after: GenerationSettings): List<String> =
        buildList {
            fun addIfChanged(label: String, old: Any, new: Any) {
                if (old != new) add("$label: $old -> $new")
            }
            addIfChanged("Max tokens", before.maxTokens, after.maxTokens)
            addIfChanged("Threads", before.threadCount, after.threadCount)
            addIfChanged("Context", before.contextLength, after.contextLength)
            addIfChanged("Batch", before.batchSize, after.batchSize)
            addIfChanged("Temperature", "%.2f".format(Locale.US, before.temperature), "%.2f".format(Locale.US, after.temperature))
            addIfChanged("Top K", before.topK, after.topK)
            addIfChanged("Top P", "%.2f".format(Locale.US, before.topP), "%.2f".format(Locale.US, after.topP))
            addIfChanged("Repeat penalty", "%.2f".format(Locale.US, before.repeatPenalty), "%.2f".format(Locale.US, after.repeatPenalty))
            addIfChanged("GPU layers", before.gpuLayers, after.gpuLayers)
            if (isEmpty()) add("No setting changes detected.")
        }
}
