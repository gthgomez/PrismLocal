package com.prismai.llmhost.agent.tools

import com.prismai.llmhost.*
import com.prismai.llmhost.model.DeviceProfiler
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.util.FormatUtils
import org.json.JSONArray
import org.json.JSONObject

class RuntimeTools(
    private val uiState: ServiceUiState,
    private val deviceProfiler: DeviceProfiler,
    private val onUpdateSettings: (GenerationSettings) -> Unit,
    private val onContinueGeneration: () -> Unit,
    private val onCancelGeneration: () -> Unit,
    private val onCancelImport: () -> Unit,
    private val onRunBenchmarkPreset: (String) -> Unit,
    private val onRunNativePpTgBenchmark: () -> Unit,
    private val onRunThreadSweepBenchmark: () -> Unit,
    private val getPreviousSettings: () -> GenerationSettings?,
    private val setPreviousSettings: (GenerationSettings?) -> Unit,
    private val activeOperationJson: () -> JSONObject,
    private val importJobIsActive: () -> Boolean,
    private val importState: () -> ImportState,
    private val modelDownloadState: () -> ModelDownloadState,
) {
    fun setRuntimeSettings(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Runtime settings change requires confirmation")
        val before = uiState.generationSettings.value.clamped()
        val requested = FormatUtils.proposedRuntimeSettings(call.arguments, before)
        val validation = FormatUtils.validateRuntimeSettingsPayload(requested, deviceProfiler.getCachedProfile())
        setPreviousSettings(before)
        onUpdateSettings(requested)
        return toolSuccess(call,
            "Runtime settings updated: tokens ${requested.maxTokens}, threads ${requested.threadCount}, ctx ${requested.contextLength}, batch ${requested.batchSize}",
            JSONObject().put("before", before.toAgentJson()).put("after", requested.toAgentJson())
                .put("changes", JSONArray(FormatUtils.settingDiffLines(before, requested)))
                .put("estimated_risk", validation.first).put("warnings", JSONArray(validation.second)))
    }

    fun validateRuntimeSettings(call: AgentToolCall): AgentToolResult {
        val args = call.arguments.optJSONObject("proposed_settings") ?: call.arguments
        val current = uiState.generationSettings.value.clamped()
        val proposed = FormatUtils.proposedRuntimeSettings(args, current)
        val validation = FormatUtils.validateRuntimeSettingsPayload(proposed, deviceProfiler.getCachedProfile())
        return toolSuccess(call, "Runtime settings validation: ${validation.first} risk",
            JSONObject().put("valid", true).put("estimated_risk", validation.first)
                .put("current", current.toAgentJson()).put("proposed", proposed.toAgentJson())
                .put("recommended_changes", JSONArray(FormatUtils.settingDiffLines(current, proposed)))
                .put("warnings", JSONArray(validation.second)))
    }

    fun restorePreviousRuntimeSettings(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Restore requires confirmation")
        val previous = getPreviousSettings()
            ?: return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "No previous runtime settings snapshot is available")
        val before = uiState.generationSettings.value.clamped()
        onUpdateSettings(previous)
        setPreviousSettings(before)
        return toolSuccess(call, "Restored previous runtime settings",
            JSONObject().put("before", before.toAgentJson()).put("after", previous.toAgentJson())
                .put("changes", JSONArray(FormatUtils.settingDiffLines(before, previous))))
    }

    fun diagnosePerformance(call: AgentToolCall): AgentToolResult {
        com.prismai.llmhost.model.ModelManager // reference for context below
        val settings = uiState.generationSettings.value.clamped()
        val profile = uiState.deviceCapabilityProfile.value
        val currentModel = uiState.currentModel.value
        val readiness = uiState.modelReadiness.value.firstOrNull { it.info.id == currentModel }
        val currentRuns = uiState.benchmarkRuns.value.filter { it.modelId == currentModel && it.generatedTokens > 0 && it.decodeMs > 0L }
        val avgTps = currentRuns.takeIf { it.isNotEmpty() }?.map { it.tokensPerSecond }?.average()
        val recommendations = JSONArray()
        if (currentModel == null) {
            recommendations.put("Select or import a model before diagnosing performance.")
        }
        readiness?.let {
            if (it.fit.rating != ModelFitRating.SAFE) {
                recommendations.put("${FormatUtils.compactAgentModelName(it.info.id)}: ${it.fit.reason}; estimated RAM need ${FormatUtils.formatBytesForMessage(it.fit.requiredRamBytes)}.")
            }
            if (!it.performance.basedOnActualRuns) {
                recommendations.put("Run a short benchmark to replace predicted speed with actual on-device data.")
            }
        }
        profile?.let {
            if (it.lowMemory) recommendations.put("Android reports low memory; close other apps or choose a smaller quant.")
            if ((it.batteryPercent ?: 100) < 20 && it.isCharging != true) recommendations.put("Battery is low; expect throttling risk during longer generations.")
            if (it.thermalStatus?.contains("moderate", ignoreCase = true) == true ||
                it.thermalStatus?.contains("severe", ignoreCase = true) == true) {
                recommendations.put("Thermal state is ${it.thermalStatus}; reduce threads or pause benchmarking if speed drops.")
            }
        }
        if (settings.threadCount >= GenerationSettings.MAX_THREAD_COUNT) {
            recommendations.put("Threads are at ${settings.threadCount}; if UI stutters or thermals rise, try 4-6 threads.")
        }
        if (settings.contextLength > GenerationSettings.DEFAULT_CONTEXT_LENGTH) {
            recommendations.put("Context ${settings.contextLength} increases RAM use; lower it for faster short answers.")
        }
        if (recommendations.length() == 0) {
            recommendations.put("Current setup looks healthy. Use benchmark history to fine-tune threads and batch size.")
        }
        val summary = listOfNotNull(
            currentModel?.let { "Model ${FormatUtils.compactAgentModelName(it)}" } ?: "No model selected",
            avgTps?.let { "actual ${FormatUtils.formatAgentTps(it)} tok/s" } ?: readiness?.let { "expected ${FormatUtils.formatAgentTps(it.prediction.minTokensPerSecond)}-${FormatUtils.formatAgentTps(it.prediction.maxTokensPerSecond)} tok/s" },
            profile?.availableRamBytes?.let { "RAM ${FormatUtils.formatBytesForMessage(it)} free" },
        ).joinToString(" | ")
        return AgentToolResult(call = call, success = true, summary = summary,
            details = JSONObject()
                .put("current_model", currentModel ?: "none").put("settings", settings.toAgentJson())
                .put("actual_avg_tps", avgTps).put("sample_count", currentRuns.size)
                .put("fit", readiness?.fit?.rating?.name ?: "unknown")
                .put("fit_reason", readiness?.fit?.reason ?: "unknown")
                .put("device", JSONObject()
                    .put("available_ram_mb", profile?.availableRamBytes?.div(1024L * 1024L))
                    .put("battery_percent", profile?.batteryPercent)
                    .put("thermal", profile?.thermalStatus ?: "unknown")
                    .put("low_memory", profile?.lowMemory ?: false))
                .put("recommendations", recommendations))
    }

    fun listBenchmarkRuns(call: AgentToolCall): AgentToolResult {
        val limit = call.arguments.optInt("limit", 10).coerceIn(1, 100)
        val modelFilter = call.arguments.optString("model_id").takeIf { it.isNotBlank() }
        val presetFilter = call.arguments.optString("preset_id").takeIf { it.isNotBlank() }
        val terminalFilter = call.arguments.optString("terminal_reason").takeIf { it.isNotBlank() }
        val filtered = uiState.benchmarkRuns.value
            .asSequence()
            .filter { run -> modelFilter == null || run.modelId == modelFilter }
            .filter { run -> presetFilter == null || run.presetId == presetFilter }
            .filter { run -> terminalFilter == null || run.terminalReason.equals(terminalFilter, ignoreCase = true) }
            .sortedByDescending { it.createdAt }.take(limit).toList()
        val runsJson = JSONArray()
        filtered.forEach { run ->
            runsJson.put(JSONObject()
                .put("id", run.id).put("created_at", run.createdAt).put("model_id", run.modelId ?: "unknown")
                .put("source", run.source).put("preset_id", run.presetId ?: "none")
                .put("preset_name", run.presetName ?: run.source).put("tokens_per_second", run.tokensPerSecond)
                .put("generated_tokens", run.generatedTokens).put("max_tokens", run.maxTokens)
                .put("terminal_reason", run.terminalReason).put("prompt_eval_ms", run.promptEvalMs)
                .put("decode_ms", run.decodeMs).put("total_ms", run.totalMs)
                .put("context_length", run.contextLength).put("batch_size", run.batchSize)
                .put("threads", run.threadCount).put("gpu_layers", run.gpuLayers)
                .put("runtime_backend", run.runtimeBackend))
        }
        val completed = filtered.filter { it.generatedTokens > 0 && it.decodeMs > 0L }
        val avgTps = completed.takeIf { it.isNotEmpty() }?.map { it.tokensPerSecond }?.average()
        val summary = if (filtered.isEmpty()) "No benchmark runs matched"
        else "${filtered.size} benchmark run${if (filtered.size == 1) "" else "s"} | avg ${avgTps?.let { FormatUtils.formatAgentTps(it) } ?: "pending"} tok/s"
        return AgentToolResult(call = call, success = true, summary = summary,
            details = JSONObject().put("total_runs", uiState.benchmarkRuns.value.size)
                .put("returned", filtered.size).put("avg_completed_tps", avgTps).put("runs", runsJson))
    }

    fun runBenchmark(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Benchmark requires confirmation")
        val profile = deviceProfiler.getCachedProfile()
        if ((profile.batteryPercent ?: 100) < 10 && profile.isCharging != true) {
            return toolFailure(call, AgentToolErrorCode.BUSY, "Battery is too low for benchmark; plug in or run from the UI")
        }
        val presetId = call.arguments.optString("preset_id", "coding")
        when (presetId) {
            "native_pp_tg" -> onRunNativePpTgBenchmark()
            "thread_sweep" -> onRunThreadSweepBenchmark()
            else -> onRunBenchmarkPreset(presetId)
        }
        return toolSuccess(call, "Benchmark queued: $presetId",
            JSONObject().put("preset_id", presetId).put("battery_percent", profile.batteryPercent ?: JSONObject.NULL)
                .put("thermal", profile.thermalStatus ?: "unknown").put("saved_to_history", true))
    }

    fun getActiveOperation(call: AgentToolCall): AgentToolResult =
        toolSuccess(call, "Active operation: ${activeOperationJson().optString("operation_type")}", activeOperationJson())

    fun cancelGeneration(call: AgentToolCall): AgentToolResult {
        if (uiState.isGenerating.value) {
            onCancelGeneration()
            return toolSuccess(call, "Generation cancel requested", JSONObject().put("operation", "generation").put("cancelled", true))
        }
        return toolSuccess(call, "No active generation to cancel", JSONObject().put("operation", "generation").put("cancelled", false))
    }

    fun cancelActiveOperation(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (call.arguments.optString("target", "auto") == "generation") {
            return cancelGeneration(call.copy(name = "cancel_generation"))
        }
        return cancelActiveJob(call.copy(name = "cancel_active_job"), confirmed)
    }

    fun cancelActiveJob(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Cancelling active jobs requires confirmation")
        val target = call.arguments.optString("target", "auto")
        val actions = JSONArray()
        if (target == "auto" || target == "import" || target == "download") {
            if (importJobIsActive() || importState() is ImportState.Running || modelDownloadState() is ModelDownloadState.Running) {
                onCancelImport()
                actions.put("import_download")
            }
        }
        val count = actions.length()
        return toolSuccess(call,
            if (count == 0) "No active matching operation to cancel" else "Cancel requested for $count operation${if (count == 1) "" else "s"}",
            JSONObject().put("target", target).put("actions", actions))
    }

    fun continueGeneration(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        onContinueGeneration()
        return toolSuccess(call, "Continuation started", JSONObject().put("started", true))
    }
}
