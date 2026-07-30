package com.prismai.llmhost.benchmark
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.os.SystemClock
import android.util.Log
import com.prismai.llmhost.*
import com.prismai.llmhost.generation.GenerationMetrics
import com.prismai.llmhost.model.DeviceProfiler
import com.prismai.llmhost.ui.ServiceUiState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Persists, loads, and exports benchmark runs. Pure data management — no
 * engine access or generation lifecycle coupling.
 */
class BenchmarkStore(
    private val uiState: ServiceUiState,
    private val metrics: GenerationMetrics,
    private val deviceProfiler: DeviceProfiler,
    private val filesDir: File,
    private val onRefreshReadiness: () -> Unit,
) {
    companion object {
        private const val BENCHMARK_RUNS_FILE_NAME = "benchmark_runs.json"
        private const val MAX_BENCHMARK_RUNS = 250
        private const val TAG = "BenchmarkStore"

        /** Export JSON schema id; bump when column/field set changes. */
        const val JSON_SCHEMA = "prism-local-benchmarks-v4"

        /** Shared CSV header contract — used by [csv] and unit tests. */
        val CSV_COLUMNS: List<String> = listOf(
            "created_at_ms", "model_id", "source", "preset_id", "preset_name",
            "prompt_chars", "output_chars", "prompt_eval_ms", "decode_ms", "total_ms",
            "generated_tokens", "tokens_per_second", "max_tokens", "thread_count",
            "context_length", "batch_size", "temperature", "top_k", "top_p",
            "repeat_penalty", "gpu_layers", "runtime_backend", "model_bytes",
            "model_sha256_prefix", "available_memory_mb", "model_load_ms", "terminal_reason",
            "terminal_detail",
        )

        fun csvHeader(): String = CSV_COLUMNS.joinToString(",")

        /**
         * Pure JSON → [BenchmarkRun] parse for load path and unit tests.
         * Missing `terminal_detail` yields null (legacy v3 rows).
         */
        fun parseRun(item: JSONObject, index: Int = 0): BenchmarkRun =
            BenchmarkRun(
                id = item.optString("id", "bench_${item.optLong("created_at_ms", 0L)}_$index"),
                createdAt = item.optLong("created_at_ms", 0L),
                modelId = item.optString("model_id").takeIf { it.isNotBlank() },
                source = item.optString("source", "chat"),
                presetId = item.optString("preset_id").takeIf { it.isNotBlank() },
                presetName = item.optString("preset_name").takeIf { it.isNotBlank() },
                promptChars = item.optInt("prompt_chars", 0),
                outputChars = item.optInt("output_chars", 0),
                promptEvalMs = item.optLong("prompt_eval_ms", 0L),
                decodeMs = item.optLong("decode_ms", 0L),
                totalMs = item.optLong("total_ms", 0L),
                generatedTokens = item.optInt("generated_tokens", 0),
                tokensPerSecond = item.optDouble("tokens_per_second", 0.0),
                maxTokens = item.optInt("max_tokens", GenerationSettings.DEFAULT_MAX_TOKENS),
                threadCount = item.optInt("thread_count", GenerationSettings.DEFAULT_THREAD_COUNT),
                contextLength = item.optInt("context_length", GenerationSettings.DEFAULT_CONTEXT_LENGTH),
                batchSize = item.optInt("batch_size", GenerationSettings.DEFAULT_BATCH_SIZE),
                temperature = item.optDouble("temperature", GenerationSettings.DEFAULT_TEMPERATURE.toDouble()).toFloat(),
                topK = item.optInt("top_k", GenerationSettings.DEFAULT_TOP_K),
                topP = item.optDouble("top_p", GenerationSettings.DEFAULT_TOP_P.toDouble()).toFloat(),
                repeatPenalty = item.optDouble("repeat_penalty", GenerationSettings.DEFAULT_REPEAT_PENALTY.toDouble()).toFloat(),
                gpuLayers = item.optInt("gpu_layers", GenerationSettings.DEFAULT_GPU_LAYERS),
                runtimeBackend = item.optString("runtime_backend", "unknown"),
                modelBytes = item.optLongOrNull("model_bytes"),
                modelSha256Prefix = item.optString("model_sha256_prefix").takeIf { it.isNotBlank() },
                availableMemoryMb = item.optLongOrNull("available_memory_mb"),
                modelLoadMs = item.optLongOrNull("model_load_ms"),
                terminalReason = item.optString("terminal_reason", "UNKNOWN"),
                terminalDetail = item.optString("terminal_detail").takeIf { it.isNotBlank() },
            )

        private fun csvCell(value: String): String {
            val escaped = value.replace("\"", "\"\"")
            return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"$escaped\""
            } else {
                escaped
            }
        }

        private fun BenchmarkRun.toJson(): JSONObject =
            JSONObject()
                .put("id", id).put("created_at_ms", createdAt)
                .put("model_id", modelId).put("source", source)
                .put("preset_id", presetId).put("preset_name", presetName)
                .put("prompt_chars", promptChars).put("output_chars", outputChars)
                .put("prompt_eval_ms", promptEvalMs).put("decode_ms", decodeMs).put("total_ms", totalMs)
                .put("generated_tokens", generatedTokens).put("tokens_per_second", tokensPerSecond)
                .put("max_tokens", maxTokens).put("thread_count", threadCount)
                .put("context_length", contextLength).put("batch_size", batchSize)
                .put("temperature", temperature.toDouble()).put("top_k", topK)
                .put("top_p", topP.toDouble()).put("repeat_penalty", repeatPenalty.toDouble())
                .put("gpu_layers", gpuLayers).put("runtime_backend", runtimeBackend)
                .put("model_bytes", modelBytes).put("model_sha256_prefix", modelSha256Prefix)
                .put("available_memory_mb", availableMemoryMb).put("model_load_ms", modelLoadMs)
                .put("terminal_reason", terminalReason)
                .put("terminal_detail", terminalDetail ?: JSONObject.NULL)

        private fun JSONObject.optLongOrNull(name: String): Long? =
            if (has(name) && !isNull(name)) optLong(name) else null
    }

    val benchmarkRunsFile: File get() = File(filesDir, BENCHMARK_RUNS_FILE_NAME)

    fun clear() {
        uiState._benchmarkRuns.value = emptyList()
        runCatching { benchmarkRunsFile.delete() }
            .onFailure { error -> Log.w(TAG, "failed to clear benchmark runs", error) }
        onRefreshReadiness()
    }

    // ── Recording ───────────────────────────────────────────────────────

    fun record(
        prompt: String,
        output: String,
        performance: GenerationPerformance,
        terminalReason: String,
        terminalDetail: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val preset = metrics.activeBenchmarkPreset
        val activeModel = uiState.activeModelInfo.value
        val memory = deviceProfiler.deviceMemorySnapshot()
        val loadMs = uiState.modelLoadDiagnostics.value
            ?.takeIf { it.modelId == uiState.currentModel.value && it.state in setOf("loaded", "current") }
            ?.loadMs
        val run = BenchmarkRun(
            id = "bench_${now}_${SystemClock.uptimeMillis()}",
            createdAt = now, modelId = uiState.currentModel.value,
            source = if (preset == null) "chat" else "preset",
            presetId = preset?.id, presetName = preset?.name,
            promptChars = prompt.length, outputChars = output.length,
            promptEvalMs = performance.promptEvalMs, decodeMs = performance.decodeMs,
            totalMs = performance.totalMs, generatedTokens = performance.generatedTokens,
            tokensPerSecond = performance.tokensPerSecond,
            maxTokens = performance.settings.maxTokens, threadCount = performance.settings.threadCount,
            contextLength = performance.settings.contextLength, batchSize = performance.settings.batchSize,
            temperature = performance.settings.temperature, topK = performance.settings.topK,
            topP = performance.settings.topP, repeatPenalty = performance.settings.repeatPenalty,
            gpuLayers = performance.settings.gpuLayers,
            runtimeBackend = BuildConfig.LLMHOST_RUNTIME_BACKEND,
            modelBytes = activeModel?.bytes, modelSha256Prefix = activeModel?.sha256?.take(12),
            availableMemoryMb = memory.availableMb, modelLoadMs = loadMs,
            terminalReason = terminalReason,
            terminalDetail = terminalDetail?.takeIf { it.isNotBlank() }?.take(400),
        )
        uiState._benchmarkRuns.value = (uiState.benchmarkRuns.value + run)
            .sortedByDescending { it.createdAt }.take(MAX_BENCHMARK_RUNS)
        persist()
        onRefreshReadiness()
    }

    fun recordNative(rawJson: String, settings: GenerationSettings) {
        val json = JSONObject(rawJson)
        json.optString("error").takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
        val now = System.currentTimeMillis()
        val activeModel = uiState.activeModelInfo.value
        val memory = deviceProfiler.deviceMemorySnapshot()
        val pp = json.optInt("pp", 0)
        val tg = json.optInt("tg", 0)
        val run = BenchmarkRun(
            id = "native_${now}_${SystemClock.uptimeMillis()}", createdAt = now,
            modelId = uiState.currentModel.value, source = "native",
            presetId = "native_pp_tg", presetName = "Native PP/TG ${pp}/${tg}",
            promptChars = pp, outputChars = 0,
            promptEvalMs = json.optDouble("prompt_ms", 0.0).toLong(),
            decodeMs = json.optDouble("decode_ms", 0.0).toLong(),
            totalMs = json.optDouble("prompt_ms", 0.0).toLong() + json.optDouble("decode_ms", 0.0).toLong(),
            generatedTokens = tg * json.optInt("nr", 1),
            tokensPerSecond = json.optDouble("decode_tps", 0.0),
            maxTokens = settings.maxTokens, threadCount = settings.threadCount,
            contextLength = settings.contextLength, batchSize = settings.batchSize,
            temperature = settings.temperature, topK = settings.topK,
            topP = settings.topP, repeatPenalty = settings.repeatPenalty,
            gpuLayers = settings.gpuLayers,
            runtimeBackend = BuildConfig.LLMHOST_RUNTIME_BACKEND,
            modelBytes = activeModel?.bytes, modelSha256Prefix = activeModel?.sha256?.take(12),
            availableMemoryMb = memory.availableMb,
            modelLoadMs = uiState.modelLoadDiagnostics.value
                ?.takeIf { it.modelId == uiState.currentModel.value && it.state in setOf("loaded", "current") }
                ?.loadMs,
            terminalReason = "NATIVE_PP_TG",
            terminalDetail = null,
        )
        uiState._benchmarkRuns.value = (uiState.benchmarkRuns.value + run)
            .sortedByDescending { it.createdAt }.take(MAX_BENCHMARK_RUNS)
        persist()
        onRefreshReadiness()
    }

    /**
     * Record a benchmark interrupted by service cancel/session bump.
     * UI stop ("user cancel" / "user cancellation") stores [terminalDetail] = `user_stop`
     * so analytics align with in-flow CANCELLED + user_stop taxonomy.
     */
    fun recordInterrupted(reason: String, streamSnapshot: String) {
        if (metrics.activeBenchmarkPreset == null) return
        val prompt = metrics.activePrompt ?: return
        val startedAt = metrics.activeStartedAt ?: return
        val settings = metrics.activeSettings ?: uiState.generationSettings.value.clamped()
        val terminalReason = "INTERRUPTED_${reason.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "_")}"
        val performance = metrics.publishPerformance(
            startedAt = startedAt, firstTokenAt = metrics.activeFirstTokenAt,
            now = SystemClock.elapsedRealtime(), generatedTokens = metrics.activeTokens,
            settings = settings, terminalReason = terminalReason,
            promptTokens = metrics.activePromptTokens,
        )
        val detail = if (isUserCancelReason(reason)) {
            "user_stop"
        } else {
            "interrupted:$reason"
        }
        record(prompt, streamSnapshot, performance, terminalReason, terminalDetail = detail)
    }

    private fun isUserCancelReason(reason: String): Boolean {
        val lower = reason.lowercase(Locale.US)
        return lower.contains("user cancel") || lower == "user cancellation"
    }

    // ── Persistence ─────────────────────────────────────────────────────

    fun load() {
        uiState._benchmarkRuns.value = runCatching {
            val file = benchmarkRunsFile
            if (!file.isFile) return@runCatching emptyList<BenchmarkRun>()
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    add(parseRun(array.getJSONObject(index), index))
                }
            }.sortedByDescending { it.createdAt }.take(MAX_BENCHMARK_RUNS)
        }.onFailure { error -> Log.w(TAG, "failed to load benchmark runs", error) }
            .getOrDefault(emptyList())
    }

    fun persist() {
        runCatching {
            val array = JSONArray()
            uiState.benchmarkRuns.value.forEach { run -> array.put(run.toJson()) }
            val target = benchmarkRunsFile
            val temp = File(filesDir, "$BENCHMARK_RUNS_FILE_NAME.tmp")
            temp.writeText(array.toString())
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }.onFailure { error -> Log.w(TAG, "failed to persist benchmark runs", error) }
    }

    // ── Export ──────────────────────────────────────────────────────────

    fun csv(): String {
        val header = csvHeader()
        val rows = uiState.benchmarkRuns.value.sortedBy { it.createdAt }
            .joinToString("\n") { run ->
                listOf(
                    run.createdAt.toString(), csvCell(run.modelId.orEmpty()), csvCell(run.source),
                    csvCell(run.presetId.orEmpty()), csvCell(run.presetName.orEmpty()),
                    run.promptChars.toString(), run.outputChars.toString(),
                    run.promptEvalMs.toString(), run.decodeMs.toString(), run.totalMs.toString(),
                    run.generatedTokens.toString(),
                    String.format(Locale.US, "%.4f", run.tokensPerSecond),
                    run.maxTokens.toString(), run.threadCount.toString(), run.contextLength.toString(),
                    run.batchSize.toString(),
                    String.format(Locale.US, "%.3f", run.temperature),
                    run.topK.toString(),
                    String.format(Locale.US, "%.3f", run.topP),
                    String.format(Locale.US, "%.3f", run.repeatPenalty),
                    run.gpuLayers.toString(), csvCell(run.runtimeBackend),
                    run.modelBytes?.toString().orEmpty(), csvCell(run.modelSha256Prefix.orEmpty()),
                    run.availableMemoryMb?.toString().orEmpty(), run.modelLoadMs?.toString().orEmpty(),
                    csvCell(run.terminalReason),
                    csvCell(run.terminalDetail.orEmpty()),
                ).joinToString(",")
            }
        return if (rows.isBlank()) "$header\n" else "$header\n$rows\n"
    }

    fun json(): String {
        val array = JSONArray()
        uiState.benchmarkRuns.value.sortedBy { it.createdAt }
            .forEach { run -> array.put(run.toJson()) }
        return JSONObject()
            .put("schema", JSON_SCHEMA)
            .put("exported_at_ms", System.currentTimeMillis())
            .put("runs", array).toString(2)
    }
}
