package com.prismai.llmhost.export

import android.content.Context
import android.os.Build
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.GenerationSettings
import org.json.JSONArray
import org.json.JSONObject

data class DiagnosticReport(
    val timestamp: Long,
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val activeModelId: String?,
    val activeBackend: String,
    val lastPerformance: GenerationPerformance?,
    val settings: GenerationSettings,
    val recentErrorLogs: List<String>,
)

object DiagnosticsExporter {

    /**
     * Bundles hardware telemetry, generation performance, model configuration,
     * and runtime logs into a structured diagnostic report.
     */
    fun createReport(
        context: Context,
        activeModelId: String?,
        activeBackend: String,
        performance: GenerationPerformance?,
        settings: GenerationSettings,
        errorLogs: List<String> = emptyList()
    ): JSONObject {
        val root = JSONObject()
            .put("schema", "prism-local-diagnostics-v1")
            .put("timestamp", System.currentTimeMillis())
            .put("device_info", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android_version", Build.VERSION.RELEASE)
                .put("sdk_int", Build.VERSION.SDK_INT)
                .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS))
            )
            .put("runtime_info", JSONObject()
                .put("active_model_id", activeModelId ?: JSONObject.NULL)
                .put("backend_name", activeBackend)
                .put("threads", settings.threadCount)
                .put("context_length", settings.contextLength)
                .put("batch_size", settings.batchSize)
                .put("gpu_layers", settings.gpuLayers)
                .put("kv_type_k", settings.kvCacheTypeK)
                .put("kv_type_v", settings.kvCacheTypeV)
            )

        if (performance != null) {
            root.put("performance_metrics", JSONObject()
                .put("ttft_ms", performance.ttftMs)
                .put("tokens_per_sec", performance.tokensPerSecond)
                .put("generated_tokens", performance.generatedTokens)
                .put("prompt_tokens", performance.promptTokens)
                .put("terminal_reason", performance.terminalReason ?: "NONE")
                .put("active_threads", performance.activeThreads)
            )
        }

        val errorsArray = JSONArray()
        errorLogs.forEach { errorsArray.put(it) }
        root.put("recent_errors", errorsArray)

        return root
    }
}
