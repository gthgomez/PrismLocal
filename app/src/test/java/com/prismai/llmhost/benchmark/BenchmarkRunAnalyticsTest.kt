package com.prismai.llmhost.benchmark

import com.prismai.llmhost.BenchmarkRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkRunAnalyticsTest {

    private fun run(
        reason: String,
        tokens: Int = 100,
        decodeMs: Long = 1000L,
        tps: Double = 30.0,
        detail: String? = null,
    ) = BenchmarkRun(
        id = "t",
        createdAt = 1L,
        modelId = "m",
        source = "preset",
        presetId = "coding",
        presetName = "Python Coding",
        promptChars = 10,
        outputChars = 100,
        promptEvalMs = 50,
        decodeMs = decodeMs,
        totalMs = 1050,
        generatedTokens = tokens,
        tokensPerSecond = tps,
        maxTokens = 320,
        threadCount = 6,
        contextLength = 2048,
        batchSize = 512,
        temperature = 0.25f,
        topK = 40,
        topP = 0.9f,
        repeatPenalty = 1.18f,
        gpuLayers = 0,
        runtimeBackend = "CPU",
        modelBytes = null,
        modelSha256Prefix = null,
        availableMemoryMb = 3000,
        modelLoadMs = 0,
        terminalReason = reason,
        terminalDetail = detail,
    )

    @Test
    fun averagesExcludeErrorAndQualityAbort() {
        val runs = listOf(
            run("EOF", tps = 40.0),
            run("MAX_TOKENS", tps = 20.0),
            run("ERROR", tokens = 0, decodeMs = 0, tps = 0.0, detail = "generation_did_not_start"),
            run("QUALITY_ABORT", tokens = 80, tps = 5.0, detail = "REPETITION_LOOP"),
        )
        val summary = BenchmarkRunAnalytics.summarize(runs)
        assertEquals(4, summary.totalCount)
        assertEquals(2, summary.completedCount)
        assertEquals(1, summary.errorCount)
        assertEquals(1, summary.qualityAbortCount)
        assertEquals(30.0, summary.avgCompletedTokensPerSecond!!, 0.01)
        assertEquals(2, summary.failedCount)
        assertFalse(BenchmarkRunAnalytics.isSuccessfulForAverages(runs[2]))
        assertFalse(BenchmarkRunAnalytics.isSuccessfulForAverages(runs[3]))
        assertTrue(BenchmarkRunAnalytics.isSuccessfulForAverages(runs[0]))
    }

    @Test
    fun statusMapsQualityAbort() {
        assertEquals(
            BenchmarkRunAnalytics.RunStatus.QualityAbort,
            BenchmarkRunAnalytics.statusOf(run("QUALITY_ABORT")),
        )
        assertEquals(
            BenchmarkRunAnalytics.RunStatus.Error,
            BenchmarkRunAnalytics.statusOf(run("ERROR", tokens = 0, decodeMs = 0)),
        )
    }

    @Test
    fun cancelledIsInterruptedStatusAndCountsAsFailed() {
        val cancelled = run("CANCELLED", tokens = 40, tps = 8.0, detail = "user_stop")
        assertFalse(BenchmarkRunAnalytics.isSuccessfulForAverages(cancelled))
        assertTrue(BenchmarkRunAnalytics.isCancelled(cancelled))
        assertEquals(
            BenchmarkRunAnalytics.RunStatus.Interrupted,
            BenchmarkRunAnalytics.statusOf(cancelled),
        )
        val summary = BenchmarkRunAnalytics.summarize(
            listOf(
                run("EOF", tps = 40.0),
                cancelled,
            ),
        )
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.cancelledCount)
        assertEquals(1, summary.failedCount)
        assertEquals(40.0, summary.avgCompletedTokensPerSecond!!, 0.01)
    }

    @Test
    fun interruptedAndCancelledBothInFailedCount() {
        val summary = BenchmarkRunAnalytics.summarize(
            listOf(
                run("INTERRUPTED_USER_CANCEL", tokens = 10, detail = "user_stop"),
                run("CANCELLED", tokens = 5, detail = "user_or_system_cancel"),
                run("ERROR", tokens = 0, decodeMs = 0),
            ),
        )
        assertEquals(1, summary.interruptedCount)
        assertEquals(1, summary.cancelledCount)
        assertEquals(1, summary.errorCount)
        assertEquals(3, summary.failedCount)
        assertEquals(0, summary.completedCount)
    }
}
