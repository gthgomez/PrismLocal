package com.prismai.llmhost.model
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.util.Log
import com.prismai.llmhost.*
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.util.FormatUtils
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Assesses model readiness, performance prediction, and fit estimation.
 * Provides agent-facing tool handlers for model recommendation and comparison.
 */
class ModelReadinessAssessor(
    private val modelStorageManager: ModelStorageManager,
    private val deviceProfiler: DeviceProfiler,
    private val uiState: ServiceUiState,
) {
    private val TAG = "ModelReadinessAssessor"

    companion object {
        private const val MODEL_RUNTIME_MIN_OVERHEAD_BYTES = 640L * 1024L * 1024L
        private const val MODEL_CONTEXT_ESTIMATE_BYTES = 256L * 1024L * 1024L
        private const val MODEL_THREAD_SCRATCH_BYTES = 24L * 1024L * 1024L

        /**
         * Map GGUF `general.file_type` (llama_ftype) to a quant label.
         * Values from vendored `llama.h` — do not invent enums.
         */
        internal fun ggufFileTypeHint(fileType: Int?): String? = when (fileType) {
            0 -> "F32"
            1 -> "F16"
            2 -> "Q4_0"
            3 -> "Q4_1"
            7 -> "Q8_0"
            8 -> "Q5_0"
            9 -> "Q5_1"
            10 -> "Q2_K"
            11 -> "Q3_K_S"
            12 -> "Q3_K_M"
            13 -> "Q3_K_L"
            14 -> "Q4_K_S"
            15 -> "Q4_K_M"
            16 -> "Q5_K_S"
            17 -> "Q5_K_M"
            18 -> "Q6_K"
            19 -> "IQ2_XXS"
            20 -> "IQ2_XS"
            21 -> "Q2_K_S"
            22 -> "IQ3_XS"
            23 -> "IQ3_XXS"
            24 -> "IQ1_S"
            25 -> "IQ4_NL"
            26 -> "IQ3_S"
            27 -> "IQ3_M"
            28 -> "IQ2_S"
            29 -> "IQ2_M"
            30 -> "IQ4_XS"
            31 -> "IQ1_M"
            32 -> "BF16"
            36 -> "TQ1_0"
            37 -> "TQ2_0"
            40 -> "Q1_0" // LLAMA_FTYPE_MOSTLY_Q1_0 (Bonsai 1-bit)
            else -> null
        }

        /** Parse quant tokens from model id / filename (e.g. Bonsai-27B-Q1_0.gguf). */
        internal fun quantizationHint(value: String): String? =
            Regex("(?:^|[-_])((?:Q1_0|Q2_0|TQ1_0|TQ2_0|I?Q\\d(?:_[Kk])?_[A-Za-z0-9]+)|Q\\d_[A-Za-z0-9]+|F16|BF16)(?:[-_.]|$)")
                .find(value.uppercase(Locale.US))
                ?.groupValues
                ?.getOrNull(1)

        internal fun quantizationOverheadMultiplier(quantization: String?): Double = when {
            quantization == null -> 0.30
            quantization == "Q1_0" || quantization.contains("Q1_0") -> 0.18
            quantization == "Q2_0" || quantization.contains("Q2_0") -> 0.20
            quantization.startsWith("TQ") -> 0.18
            quantization.startsWith("IQ1") -> 0.20
            quantization.startsWith("Q2") || quantization.startsWith("Q3") || quantization.startsWith("IQ2") || quantization.startsWith("IQ3") -> 0.22
            quantization.startsWith("Q4") || quantization.startsWith("IQ4") -> 0.25
            quantization.startsWith("Q5") -> 0.30
            quantization.startsWith("Q6") -> 0.34
            quantization.startsWith("Q8") -> 0.40
            quantization == "F16" || quantization == "BF16" -> 0.48
            else -> 0.30
        }

        /**
         * Pure fitness rating used by [estimateModelFit] and unit tests.
         * Large-model tiers use available RAM after unload (not total device RAM).
         */
        internal fun rateModelFit(
            modelBytes: Long,
            requiredRamBytes: Long,
            availableAfterUnloadBytes: Long,
            lowMemory: Boolean,
        ): ModelFitRating {
            val isLargeModelClass = modelBytes > ModelLoadLimits.LARGE_MODEL_BYTES
            val safeBudget = (availableAfterUnloadBytes * 0.78).toLong()
            val riskyBudget = (availableAfterUnloadBytes * 0.98).toLong()
            return when {
                modelBytes > ModelLoadLimits.HARD_CAP_BYTES -> ModelFitRating.TOO_LARGE
                isLargeModelClass && availableAfterUnloadBytes < ModelLoadLimits.LARGE_MODEL_AVAILABLE_TOO_LARGE ->
                    ModelFitRating.TOO_LARGE
                isLargeModelClass && availableAfterUnloadBytes < ModelLoadLimits.LARGE_MODEL_AVAILABLE_RISKY ->
                    ModelFitRating.RISKY
                requiredRamBytes <= safeBudget && !lowMemory -> ModelFitRating.SAFE
                requiredRamBytes <= riskyBudget -> ModelFitRating.RISKY
                else -> ModelFitRating.TOO_LARGE
            }
        }
    }

    fun buildReadiness(
        info: ModelStorageManager.ActiveModelInfo,
        profile: DeviceCapabilityProfile,
    ): ModelReadiness {
        val fit = estimateModelFit(info, profile)
        val prediction = predictPerformance(info, fit, profile)
        return ModelReadiness(
            info = info,
            fit = fit,
            prediction = prediction,
            performance = summarizeModelPerformance(info, fit, prediction),
        )
    }

    private fun summarizeModelPerformance(
        info: ModelStorageManager.ActiveModelInfo,
        fit: ModelFitEstimate,
        prediction: PerformancePrediction,
    ): ModelPerformanceSummary {
        val exactRuns = uiState._benchmarkRuns.value.filter {
            it.modelId == info.id && it.generatedTokens > 0 && it.decodeMs > 0L
        }
        val actualAverage = exactRuns.takeIf { it.isNotEmpty() }
            ?.map { it.tokensPerSecond }
            ?.average()
        val score = actualAverage ?: ((prediction.minTokensPerSecond + prediction.maxTokensPerSecond) / 2.0)
        val tier = when {
            fit.rating == ModelFitRating.TOO_LARGE -> ModelPerformanceTier.NOT_RECOMMENDED
            actualAverage == null && prediction.sampleCount == 0 -> ModelPerformanceTier.UNKNOWN
            score < 0.5 -> ModelPerformanceTier.NOT_RECOMMENDED
            score < 2.0 -> ModelPerformanceTier.VERY_SLOW
            score < 6.0 -> ModelPerformanceTier.USABLE
            else -> ModelPerformanceTier.RECOMMENDED
        }
        val label = when (tier) {
            ModelPerformanceTier.UNKNOWN -> "Needs benchmark"
            ModelPerformanceTier.NOT_RECOMMENDED -> "Not recommended"
            ModelPerformanceTier.VERY_SLOW -> "Very slow"
            ModelPerformanceTier.USABLE -> "Usable"
            ModelPerformanceTier.RECOMMENDED -> "Recommended"
        }
        return ModelPerformanceSummary(
            tier = tier,
            label = label,
            averageTokensPerSecond = actualAverage,
            sampleCount = exactRuns.size.takeIf { it > 0 } ?: prediction.sampleCount,
            basedOnActualRuns = exactRuns.isNotEmpty(),
        )
    }

    // internal for access from ModelManager.nativeLoadRejection
    internal fun estimateModelFit(
        info: ModelStorageManager.ActiveModelInfo,
        profile: DeviceCapabilityProfile,
    ): ModelFitEstimate {
        val availableAfterCurrentUnload = profile.availableRamBytes + (uiState._activeModelInfo.value?.bytes ?: 0L)
        val settings = uiState._generationSettings.value.clamped()
        val quantization = ggufFileTypeHint(info.validation.metadata?.fileType)
            ?: quantizationHint(info.fileName)
            ?: quantizationHint(info.id)
        val runtimeOverhead = maxOf(
            MODEL_RUNTIME_MIN_OVERHEAD_BYTES,
            (info.bytes * quantizationOverheadMultiplier(quantization)).toLong(),
        )
        val declaredContext = info.validation.metadata?.contextLength ?: GenerationSettings.DEFAULT_CONTEXT_LENGTH
        val activeContext = minOf(settings.contextLength, declaredContext.coerceAtLeast(GenerationSettings.MIN_CONTEXT_LENGTH))
        val contextEstimate = (MODEL_CONTEXT_ESTIMATE_BYTES *
            (activeContext.toDouble() / GenerationSettings.DEFAULT_CONTEXT_LENGTH.toDouble()))
            .toLong()
            .coerceAtLeast(MODEL_CONTEXT_ESTIMATE_BYTES / 4L)
        val requiredRam = info.bytes +
            runtimeOverhead +
            contextEstimate +
            (settings.threadCount * MODEL_THREAD_SCRATCH_BYTES)
        val rating = rateModelFit(
            modelBytes = info.bytes,
            requiredRamBytes = requiredRam,
            availableAfterUnloadBytes = availableAfterCurrentUnload,
            lowMemory = profile.lowMemory,
        )
        val reason = when (rating) {
            ModelFitRating.SAFE -> "Recommended"
            ModelFitRating.RISKY -> if (profile.lowMemory) "May be slow; device reports low memory" else "May be slow; limited RAM headroom"
            ModelFitRating.TOO_LARGE -> "Likely too large for current RAM headroom"
        }
        return ModelFitEstimate(
            modelId = info.id,
            fileName = info.fileName,
            modelBytes = info.bytes,
            quantization = quantization,
            requiredRamBytes = requiredRam,
            availableRamAfterUnloadBytes = availableAfterCurrentUnload,
            storageFreeBytes = profile.storageFreeBytes,
            rating = rating,
            reason = reason,
        )
    }

    private fun predictPerformance(
        info: ModelStorageManager.ActiveModelInfo,
        fit: ModelFitEstimate,
        profile: DeviceCapabilityProfile,
    ): PerformancePrediction {
        val completedRuns = uiState._benchmarkRuns.value.filter { it.generatedTokens > 0 && it.decodeMs > 0L }
        val modelRuns = completedRuns.filter { it.modelId == info.id }
        if (modelRuns.isNotEmpty()) {
            val average = modelRuns.map { it.tokensPerSecond }.average().coerceAtLeast(0.1)
            val spread = if (modelRuns.size == 1) 0.25 else 0.18
            return PerformancePrediction(
                minTokensPerSecond = (average * (1.0 - spread)).coerceAtLeast(0.1),
                maxTokensPerSecond = (average * (1.0 + spread)).coerceAtLeast(0.2),
                basis = "based on exact model history",
                sampleCount = modelRuns.size,
            )
        }
        val sizeSimilarRuns = completedRuns.filter { run ->
            val runBytes = run.modelBytes ?: return@filter false
            val sizeRatio = runBytes.toDouble() / info.bytes.toDouble()
            val runQuant = quantizationHint(run.modelId.orEmpty())
            runQuant == fit.quantization && sizeRatio in 0.65..1.55
        }
        if (sizeSimilarRuns.isNotEmpty()) {
            val estimates = sizeSimilarRuns.mapNotNull { run -> adjustedTokensPerSecond(run, info, fit) }
            if (estimates.isNotEmpty()) {
                val average = estimates.average().coerceAtLeast(0.1)
                return PerformancePrediction(
                    minTokensPerSecond = (average * 0.72).coerceAtLeast(0.1),
                    maxTokensPerSecond = (average * 1.28).coerceAtLeast(0.2),
                    basis = "based on similar ${fit.quantization ?: "quant"} models",
                    sampleCount = estimates.size,
                )
            }
        }
        val globalBaselineRuns = completedRuns.filter { it.modelBytes != null }
        if (globalBaselineRuns.isNotEmpty()) {
            val estimates = globalBaselineRuns.mapNotNull { run -> adjustedTokensPerSecond(run, info, fit) }
            if (estimates.isNotEmpty()) {
                val average = estimates.average().coerceAtLeast(0.1)
                return PerformancePrediction(
                    minTokensPerSecond = (average * 0.55).coerceAtLeast(0.1),
                    maxTokensPerSecond = (average * 1.55).coerceAtLeast(0.2),
                    basis = "based on device baseline",
                    sampleCount = estimates.size,
                )
            }
        }
        val sizeGiB = (info.bytes / (1024.0 * 1024.0 * 1024.0)).coerceAtLeast(0.5)
        val cpuFactor = profile.cpuCoreCount.coerceIn(1, uiState._generationSettings.value.threadCount).toDouble()
        val quantFactor = quantizationSpeedMultiplier(fit.quantization)
        val memoryFactor = when (fit.rating) {
            ModelFitRating.SAFE -> 1.0
            ModelFitRating.RISKY -> 0.72
            ModelFitRating.TOO_LARGE -> 0.35
        }
        val rough = ((cpuFactor * 2.4 * quantFactor * memoryFactor) / sizeGiB.pow(0.82))
            .coerceIn(0.2, 35.0)
        return PerformancePrediction(
            minTokensPerSecond = (rough * 0.65).coerceAtLeast(0.1),
            maxTokensPerSecond = (rough * 1.30).coerceAtLeast(0.2),
            basis = "rough device estimate",
            sampleCount = 0,
        )
    }

    private fun adjustedTokensPerSecond(
        run: BenchmarkRun,
        target: ModelStorageManager.ActiveModelInfo,
        targetFit: ModelFitEstimate,
    ): Double? {
        val runBytes = run.modelBytes?.takeIf { it > 0L } ?: return null
        val runQuant = quantizationHint(run.modelId.orEmpty())
        val sizeFactor = (runBytes.toDouble() / target.bytes.toDouble()).pow(0.82)
        val quantFactor = quantizationSpeedMultiplier(targetFit.quantization) /
            quantizationSpeedMultiplier(runQuant).coerceAtLeast(0.1)
        val threadFactor = if (run.threadCount > 0) {
            (uiState._generationSettings.value.threadCount.toDouble() / run.threadCount.toDouble())
                .coerceIn(0.65, 1.35)
        } else {
            1.0
        }
        return (run.tokensPerSecond * sizeFactor * quantFactor * threadFactor)
            .takeIf { it.isFinite() && it > 0.0 }
    }

    private fun quantizationSpeedMultiplier(quantization: String?): Double = when {
        quantization == null -> 0.85
        quantization == "Q1_0" || quantization.contains("Q1_0") -> 1.15
        quantization == "Q2_0" || quantization.contains("Q2_0") -> 1.10
        quantization.startsWith("TQ") -> 1.12
        quantization.startsWith("Q2") || quantization.startsWith("Q3") || quantization.startsWith("IQ2") || quantization.startsWith("IQ3") -> 1.15
        quantization.startsWith("Q4") || quantization.startsWith("IQ4") -> 1.0
        quantization.startsWith("Q5") -> 0.86
        quantization.startsWith("Q6") -> 0.76
        quantization.startsWith("Q8") -> 0.60
        quantization == "F16" || quantization == "BF16" -> 0.42
        else -> 0.85
    }

    fun recommendModel(call: AgentToolCall): AgentToolResult {
        val prefer = call.arguments.optString("goal", call.arguments.optString("prefer", "chat"))
        val source = call.arguments.optString("source", "both")
        val maxSizeGb = call.arguments.optDouble("max_size_gb", Double.POSITIVE_INFINITY)
        val maxBytes = if (maxSizeGb.isFinite()) (maxSizeGb * 1024.0 * 1024.0 * 1024.0).toLong() else Long.MAX_VALUE
        val installed = uiState._modelReadiness.value
            .filter { it.fit.rating != ModelFitRating.TOO_LARGE }
            .sortedWith(
                compareByDescending<ModelReadiness> { it.performance.averageTokensPerSecond ?: it.prediction.maxTokensPerSecond }
                    .thenBy { it.info.bytes }
            )
        val bestInstalled = if (source == "curated_downloads") null else installed.firstOrNull()
        val catalog = when (prefer.lowercase(Locale.US)) {
            "coding" -> HuggingFaceModelCatalog.entries.filter { it.expectedBytes <= maxBytes }.firstOrNull { it.id.contains("coder") }
            "speed", "battery" -> HuggingFaceModelCatalog.entries.filter { it.expectedBytes <= maxBytes }.minByOrNull { it.expectedBytes }
            else -> HuggingFaceModelCatalog.entries.firstOrNull { it.expectedBytes <= maxBytes }
        }
            ?.takeUnless { source == "installed_only" }
        val reasonCodes = JSONArray()
        val tradeoffs = JSONArray()
        bestInstalled?.let {
            reasonCodes.put("fits_ram_${it.fit.rating.name.lowercase(Locale.US)}")
            if (it.performance.basedOnActualRuns) reasonCodes.put("has_completed_benchmark_evidence") else tradeoffs.put("Speed is predicted until benchmarks are run.")
            reasonCodes.put("stable_hash_match")
        } ?: catalog?.let {
            reasonCodes.put("curated_download_only")
            reasonCodes.put("fits_size_limit")
            tradeoffs.put("Download requires network and storage.")
        }
        val details = JSONObject()
            .put("prefer", prefer)
            .put("source", source)
            .put("installed_recommendation", bestInstalled?.info?.id)
            .put("download_recommendation", catalog?.id)
            .put("download_name", catalog?.name)
            .put("reason_codes", reasonCodes)
            .put("tradeoffs", tradeoffs)
        val summary = bestInstalled?.let {
            "Use ${FormatUtils.compactAgentModelName(it.info.id)}: ${it.performance.label}, expected ${FormatUtils.formatAgentTps(it.prediction.minTokensPerSecond)}-${FormatUtils.formatAgentTps(it.prediction.maxTokensPerSecond)} tok/s"
        } ?: catalog?.let {
            "Download ${it.name}: ${it.parameters}, ${it.quantization}, ${FormatUtils.formatBytesForMessage(it.expectedBytes)}"
        } ?: "No recommendation available"
        return AgentToolResult(call, success = true, summary = summary, details = details)
    }

    fun compareModels(call: AgentToolCall): AgentToolResult {
        val rows = uiState._modelReadiness.value
            .sortedByDescending { it.performance.averageTokensPerSecond ?: it.prediction.maxTokensPerSecond }
            .take(5)
        val models = JSONArray()
        rows.forEachIndexed { index, readiness ->
            models.put(
                JSONObject()
                    .put("rank", index + 1)
                    .put("id", readiness.info.id)
                    .put("hash_prefix", readiness.info.sha256.take(12))
                    .put("fit", readiness.fit.rating.name)
                    .put("label", readiness.performance.label)
                    .put("actual_tps", readiness.performance.averageTokensPerSecond)
                    .put("predicted_min_tps", readiness.prediction.minTokensPerSecond)
                    .put("predicted_max_tps", readiness.prediction.maxTokensPerSecond)
                    .put("samples", readiness.performance.sampleCount),
            )
        }
        val relevantRuns = rows.flatMap { readiness ->
            uiState._benchmarkRuns.value.filter { run -> run.modelId == readiness.info.id && run.generatedTokens > 0 && run.decodeMs > 0L }
        }
        val directGroups = relevantRuns.groupBy {
            listOf(
                it.presetId ?: "",
                it.contextLength.toString(),
                it.batchSize.toString(),
                it.threadCount.toString(),
                it.runtimeBackend,
                it.terminalReason,
            ).joinToString("|")
        }.values
        val directComparable = directGroups.any { group ->
            group.mapNotNull { it.modelSha256Prefix }.distinct().size > 1 && group.all { it.terminalReason == "EOF" || it.terminalReason == "MAX_TOKENS" }
        }
        val validity = when {
            rows.size < 2 -> "invalid"
            directComparable -> "direct"
            relevantRuns.isNotEmpty() -> "partial"
            else -> "partial"
        }
        val why = when (validity) {
            "direct" -> "At least one benchmark group matches preset, context, batch, threads, backend, and terminal status across models."
            "invalid" -> "Need at least two installed models to compare."
            else -> "Runs differ by settings/hash/status or rely on predictions, so speed comparison is approximate."
        }
        val summary = if (rows.isEmpty()) {
            "No installed models to compare"
        } else {
            rows.joinToString(" | ") {
                "${FormatUtils.compactAgentModelName(it.info.id)}: ${it.performance.label}"
            }
        }
        return AgentToolResult(
            call,
            success = rows.isNotEmpty(),
            summary = summary,
            details = JSONObject()
                .put("comparison_validity", validity)
                .put("why", why)
                .put("models", models),
            errorCode = if (rows.isNotEmpty()) AgentToolErrorCode.OK else AgentToolErrorCode.NOT_FOUND,
        )
    }
}
