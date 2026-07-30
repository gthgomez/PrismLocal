package com.prismai.llmhost.benchmark

import com.prismai.llmhost.BenchmarkRun

/**
 * Pure helpers for classifying runs and computing averages (unit-testable).
 */
object BenchmarkRunAnalytics {

    fun isSuccessfulForAverages(run: BenchmarkRun): Boolean {
        if (run.generatedTokens <= 0 || run.decodeMs <= 0L) return false
        return when {
            run.terminalReason == "EOF" -> true
            run.terminalReason == "MAX_TOKENS" -> true
            run.terminalReason == "ERROR" -> false
            run.terminalReason == "QUALITY_ABORT" -> false
            run.terminalReason.contains("INTERRUPTED", ignoreCase = true) -> false
            run.terminalReason == "CANCELLED" -> false
            else -> true // NATIVE_PP_TG and unknown partial successes
        }
    }

    fun isErrorLike(run: BenchmarkRun): Boolean =
        run.terminalReason == "ERROR"

    fun isQualityAbort(run: BenchmarkRun): Boolean =
        run.terminalReason == "QUALITY_ABORT"

    fun isInterrupted(run: BenchmarkRun): Boolean =
        run.terminalReason.contains("INTERRUPTED", ignoreCase = true)

    fun isCancelled(run: BenchmarkRun): Boolean =
        run.terminalReason == "CANCELLED"

    enum class RunStatus {
        Clean,
        Truncated,
        QualityAbort,
        Error,
        Interrupted,
        Partial,
        Unknown,
    }

    fun statusOf(run: BenchmarkRun): RunStatus = when {
        run.terminalReason == "EOF" -> RunStatus.Clean
        run.terminalReason == "MAX_TOKENS" -> RunStatus.Truncated
        run.terminalReason == "QUALITY_ABORT" -> RunStatus.QualityAbort
        run.terminalReason == "ERROR" -> RunStatus.Error
        run.terminalReason.contains("INTERRUPTED", ignoreCase = true) -> RunStatus.Interrupted
        // In-flow CANCELLED is user/system stop — surface as Interrupted, not Partial.
        run.terminalReason == "CANCELLED" -> RunStatus.Interrupted
        run.generatedTokens > 0 && run.decodeMs > 0L -> RunStatus.Partial
        else -> RunStatus.Unknown
    }

    data class Summary(
        val totalCount: Int,
        val completedCount: Int,
        val cleanCount: Int,
        val truncatedCount: Int,
        val errorCount: Int,
        val qualityAbortCount: Int,
        val interruptedCount: Int,
        val cancelledCount: Int,
        /** Average TPS over successful (EOF/MAX_TOKENS) runs only. */
        val avgCompletedTokensPerSecond: Double?,
        val bestCompletedTokensPerSecond: Double?,
        val avgPromptMs: Long?,
        val avgTotalMs: Long?,
    ) {
        val failedCount: Int
            get() = errorCount + interruptedCount + qualityAbortCount + cancelledCount
    }

    fun summarize(runs: List<BenchmarkRun>): Summary {
        val successful = runs.filter(::isSuccessfulForAverages)
        return Summary(
            totalCount = runs.size,
            completedCount = successful.size,
            cleanCount = runs.count { it.terminalReason == "EOF" },
            truncatedCount = runs.count { it.terminalReason == "MAX_TOKENS" },
            errorCount = runs.count(::isErrorLike),
            qualityAbortCount = runs.count(::isQualityAbort),
            interruptedCount = runs.count(::isInterrupted),
            cancelledCount = runs.count(::isCancelled),
            avgCompletedTokensPerSecond = successful.takeIf { it.isNotEmpty() }?.map { it.tokensPerSecond }?.average(),
            bestCompletedTokensPerSecond = successful.maxOfOrNull { it.tokensPerSecond },
            avgPromptMs = successful.takeIf { it.isNotEmpty() }
                ?.map { it.promptEvalMs }?.average()?.toLong(),
            avgTotalMs = successful.takeIf { it.isNotEmpty() }
                ?.map { it.totalMs }?.average()?.toLong(),
        )
    }
}
