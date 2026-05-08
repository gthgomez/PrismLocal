package com.example.llmhost

data class DeviceCapabilityProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val lowMemory: Boolean,
    val cpuCoreCount: Int,
    val androidSdk: Int,
    val abis: List<String>,
    val storageFreeBytes: Long,
    val batteryPercent: Int?,
    val isCharging: Boolean?,
    val thermalStatus: String?,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val appHeapMaxBytes: Long,
    val capturedAt: Long = System.currentTimeMillis(),
)

enum class ModelFitRating {
    SAFE,
    RISKY,
    TOO_LARGE,
}

enum class ModelPerformanceTier {
    UNKNOWN,
    NOT_RECOMMENDED,
    VERY_SLOW,
    USABLE,
    RECOMMENDED,
}

data class ModelFitEstimate(
    val modelId: String,
    val fileName: String,
    val modelBytes: Long,
    val quantization: String?,
    val requiredRamBytes: Long,
    val availableRamAfterUnloadBytes: Long,
    val storageFreeBytes: Long,
    val rating: ModelFitRating,
    val reason: String,
)

data class PerformancePrediction(
    val minTokensPerSecond: Double,
    val maxTokensPerSecond: Double,
    val basis: String,
    val sampleCount: Int,
)

data class ModelPerformanceSummary(
    val tier: ModelPerformanceTier,
    val label: String,
    val averageTokensPerSecond: Double?,
    val sampleCount: Int,
    val basedOnActualRuns: Boolean,
)

data class ModelReadiness(
    val info: ModelStorageManager.ActiveModelInfo,
    val fit: ModelFitEstimate,
    val prediction: PerformancePrediction,
    val performance: ModelPerformanceSummary,
)
