package com.prismai.llmhost.agent.tools
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import com.prismai.llmhost.*
import com.prismai.llmhost.model.ModelDownloadManager
import com.prismai.llmhost.model.ModelImportManager
import com.prismai.llmhost.model.ModelManager
import com.prismai.llmhost.model.ModelReadinessAssessor
import com.prismai.llmhost.util.FormatUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ModelTools(
    private val modelManager: ModelManager,
    private val modelStorageManager: ModelStorageManager,
    private val modelReadinessAssessor: ModelReadinessAssessor,
    private val modelImportManager: ModelImportManager,
    private val modelDownloadManager: ModelDownloadManager,
    private val uiState: com.prismai.llmhost.ui.ServiceUiState,
    private val onRefreshReadiness: () -> Unit,
    private val filesDir: java.io.File,
    private val chatDirectory: () -> java.io.File,
    private val benchmarkFileSize: () -> Long,
    private val chatIndexFile: () -> java.io.File,
) {
    fun listInstalledModels(call: AgentToolCall): AgentToolResult {
        onRefreshReadiness()
        val limit = call.arguments.optInt("limit", 10).coerceIn(1, 50)
        val rows = uiState._modelReadiness.value
            .sortedWith(compareByDescending<ModelReadiness> { it.performance.averageTokensPerSecond ?: it.prediction.maxTokensPerSecond }
                .thenBy { it.info.bytes })
            .take(limit)
        val modelsJson = JSONArray()
        rows.forEach { readiness ->
            modelsJson.put(JSONObject()
                .put("id", readiness.info.id).put("file_name", readiness.info.fileName)
                .put("bytes", readiness.info.bytes).put("size", FormatUtils.formatBytesForMessage(readiness.info.bytes))
                .put("hash_prefix", readiness.info.sha256.take(12))
                .put("fit", readiness.fit.rating.name).put("fit_reason", readiness.fit.reason)
                .put("quantization", readiness.fit.quantization ?: "unknown")
                .put("required_ram_bytes", readiness.fit.requiredRamBytes)
                .put("performance_label", readiness.performance.label)
                .put("actual_tps", readiness.performance.averageTokensPerSecond)
                .put("samples", readiness.performance.sampleCount)
                .put("predicted_min_tps", readiness.prediction.minTokensPerSecond)
                .put("predicted_max_tps", readiness.prediction.maxTokensPerSecond)
                .put("prediction_basis", readiness.prediction.basis))
        }
        val summary = if (rows.isEmpty()) "No installed models found"
        else "${rows.size} installed model${if (rows.size == 1) "" else "s"} listed"
        return AgentToolResult(call = call, success = true, summary = summary,
            details = JSONObject().put("count", uiState._modelReadiness.value.size).put("returned", rows.size).put("models", modelsJson))
    }

    fun getModelCard(call: AgentToolCall): AgentToolResult {
        onRefreshReadiness()
        val modelIdArg = call.arguments.optString("model_id", "current")
        val modelId = if (modelIdArg == "current" || modelIdArg.isBlank()) uiState.currentModel.value else modelIdArg
        val readiness = uiState._modelReadiness.value.firstOrNull { it.info.id == modelId }
            ?: return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "Model not found: ${modelId ?: "current"}")
        val metadata = readiness.info.validation.metadata
        val missingFields = JSONArray()
        if (metadata?.architecture == null) missingFields.put("architecture")
        if (metadata?.sizeLabel == null) missingFields.put("parameters")
        if (metadata?.contextLength == null) missingFields.put("context_length")
        if (metadata?.fileType == null) missingFields.put("file_type")
        val details = JSONObject()
            .put("id", readiness.info.id)
            .put("metadata_source", if (metadata == null) "unknown" else "gguf_metadata")
            .put("confidence", if (metadata == null) "low" else "high")
            .put("missing_fields", missingFields)
            .put("file_name", readiness.info.fileName)
            .put("bytes", readiness.info.bytes).put("size", FormatUtils.formatBytesForMessage(readiness.info.bytes))
            .put("sha256_prefix", readiness.info.sha256.take(12))
            .put("format", "GGUF v${readiness.info.validation.ggufVersion}")
            .put("validation", readiness.info.validation.status)
            .put("architecture", metadata?.architecture ?: "unknown")
            .put("parameters", metadata?.sizeLabel ?: "unknown")
            .put("context_length", metadata?.contextLength ?: JSONObject.NULL)
            .put("file_type", metadata?.fileType ?: JSONObject.NULL)
            .put("has_chat_template", metadata?.hasChatTemplate ?: false)
            .put("quantization", readiness.fit.quantization ?: "unknown")
            .put("fit", readiness.fit.rating.name).put("fit_reason", readiness.fit.reason)
            .put("required_ram_bytes", readiness.fit.requiredRamBytes)
            .put("available_ram_after_unload_bytes", readiness.fit.availableRamAfterUnloadBytes)
            .put("performance_label", readiness.performance.label)
            .put("actual_tps", readiness.performance.averageTokensPerSecond)
            .put("predicted_min_tps", readiness.prediction.minTokensPerSecond)
            .put("predicted_max_tps", readiness.prediction.maxTokensPerSecond)
            .put("prediction_basis", readiness.prediction.basis)
        return AgentToolResult(call = call, success = true,
            summary = "${FormatUtils.compactAgentModelName(readiness.info.id)}: ${readiness.performance.label}, ${readiness.fit.reason}",
            details = details)
    }

    fun recommendModel(call: AgentToolCall): AgentToolResult {
        onRefreshReadiness()
        return modelReadinessAssessor.recommendModel(call)
    }

    fun compareModels(call: AgentToolCall): AgentToolResult {
        onRefreshReadiness()
        return modelReadinessAssessor.compareModels(call)
    }

    fun listCuratedDownloadableModels(call: AgentToolCall): AgentToolResult {
        val limit = call.arguments.optInt("limit", 10).coerceIn(1, 50)
        val models = JSONArray()
        HuggingFaceModelCatalog.entries.take(limit).forEach { entry ->
            models.put(JSONObject()
                .put("entry_id", entry.id).put("name", entry.name).put("repo_id", entry.repoId)
                .put("file_name", entry.fileName).put("bytes", entry.expectedBytes)
                .put("size", FormatUtils.formatBytesForMessage(entry.expectedBytes))
                .put("license", entry.license).put("parameters", entry.parameters)
                .put("quantization", entry.quantization)
                .put("hash_verification", entry.expectedSha256 != null)
                .put("notes", entry.notes))
        }
        return toolSuccess(call, "${models.length()} curated downloadable model${if (models.length() == 1) "" else "s"} listed",
            JSONObject().put("returned", models.length())
                .put("total", HuggingFaceModelCatalog.entries.size)
                .put("arbitrary_urls_allowed", false).put("models", models))
    }

    fun getDownloadStatus(call: AgentToolCall): AgentToolResult {
        val details = when (val state = uiState.modelDownloadState.value) {
            ModelDownloadState.Idle -> JSONObject().put("status", "idle")
            ModelDownloadState.Cancelled -> JSONObject().put("status", "cancelled")
            is ModelDownloadState.Success -> JSONObject().put("status", "success").put("model_id", state.modelId).put("entry_name", state.entryName)
            is ModelDownloadState.Failure -> JSONObject().put("status", "failure").put("entry_name", state.entryName).put("message", state.message)
            is ModelDownloadState.Running -> JSONObject().put("status", "running")
                .put("entry_id", state.entry.id).put("entry_name", state.entry.name)
                .put("stage", state.stage.name).put("bytes_done", state.bytesDone)
                .put("total_bytes", state.totalBytes ?: JSONObject.NULL).put("message", state.message ?: "")
        }
        return toolSuccess(call, "Download status: ${details.optString("status")}", details)
    }

    fun downloadModel(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Download requires confirmation")
        val entryId = call.arguments.optString("entry_id").trim()
        val entry: HuggingFaceModelEntry? = if (entryId.isNotBlank()) HuggingFaceModelCatalog.find(entryId)
        else HuggingFaceModelCatalog.entries.firstOrNull { it.name.contains(call.arguments.optString("name", ""), ignoreCase = true) }
        if (entry == null) return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "Model not found in the curated catalog")
        modelDownloadManager.downloadHuggingFaceModel(entry.id)
        return toolSuccess(call, "Download queued for ${entry.name}",
            JSONObject().put("queued", true).put("entry_id", entry.id).put("entry_name", entry.name)
                .put("repo_id", entry.repoId).put("bytes", entry.expectedBytes))
    }

    fun recommendRuntimeSettings(call: AgentToolCall): AgentToolResult {
        onRefreshReadiness()
        val goal = call.arguments.optString("goal", "fast").lowercase(Locale.US)
        val current = uiState.generationSettings.value.clamped()
        val profile = uiState.deviceCapabilityProfile.value
        val recommended = when (goal) {
            "battery_saver" -> current.copy(maxTokens = 128, threadCount = minOf(4, current.threadCount), contextLength = 2048, batchSize = 512, temperature = 0.6f)
            "long_context" -> current.copy(maxTokens = 256, threadCount = minOf(6, current.threadCount), contextLength = 4096, batchSize = 512, temperature = 0.7f)
            "coding" -> current.copy(maxTokens = 256, threadCount = minOf(6, profile?.cpuCoreCount ?: 6), contextLength = 4096, batchSize = 512, temperature = 0.25f, topP = 0.90f)
            "quality" -> current.copy(maxTokens = 256, threadCount = minOf(6, profile?.cpuCoreCount ?: 6), contextLength = 4096, batchSize = 512, temperature = 0.8f, topP = 0.95f)
            else -> current.copy(maxTokens = 128, threadCount = minOf(6, profile?.cpuCoreCount ?: 6), contextLength = 2048, batchSize = 512, temperature = 0.7f)
        }.clamped()
        val notes = JSONArray()
        if (recommended.contextLength > current.contextLength) notes.put("Higher context improves long-chat memory but uses more RAM.")
        if (recommended.threadCount < current.threadCount) notes.put("Fewer threads may reduce heat and UI contention.")
        if (goal == "coding") notes.put("Lower temperature improves deterministic code output.")
        if (goal == "battery_saver") notes.put("Battery saver keeps context and threads conservative.")
        return AgentToolResult(call = call, success = true,
            summary = "Recommended runtime settings for $goal: tokens ${recommended.maxTokens}, threads ${recommended.threadCount}, ctx ${recommended.contextLength}",
            details = JSONObject().put("goal", goal).put("current", current.toAgentJson())
                .put("recommended", recommended.toAgentJson())
                .put("recommended_changes", JSONArray(FormatUtils.settingDiffLines(current, recommended)))
                .put("requires_confirmation_to_apply", true).put("notes", notes))
    }

    fun explainRuntimeSettings(call: AgentToolCall): AgentToolResult {
        val settings = uiState.generationSettings.value.clamped()
        val explanations = JSONArray()
            .put("max_tokens controls response length; higher values take longer and use more battery.")
            .put("threads controls CPU parallelism; too many can increase heat or reduce UI smoothness.")
            .put("context_length controls how much chat history the model can use; higher values use more RAM.")
            .put("batch_size affects prompt processing throughput and memory use.")
            .put("temperature/top_p/top_k control randomness; lower temperature is better for code and factual answers.")
            .put("repeat_penalty discourages repeated phrasing.")
            .put("gpu_layers is currently useful only when the runtime backend supports GPU offload.")
        return AgentToolResult(call = call, success = true,
            summary = "Runtime settings explained for current ctx ${settings.contextLength}, threads ${settings.threadCount}, tokens ${settings.maxTokens}",
            details = JSONObject().put("settings", settings.toAgentJson()).put("explanations", explanations))
    }
}
