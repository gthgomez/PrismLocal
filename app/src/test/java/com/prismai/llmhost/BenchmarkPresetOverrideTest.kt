package com.prismai.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkPresetOverrideTest {

    private val hotBase = GenerationSettings(
        maxTokens = 1024,
        threadCount = 6,
        temperature = 0.70f,
        topP = 0.95f,
        topK = 40,
        repeatPenalty = 1.10f,
    )

    @Test
    fun codingPresetAppliesSamplerAndTokenCap() {
        val coding = BenchmarkPresets.find("coding")!!
        val applied = coding.applySettingsOverrides(hotBase)

        assertEquals(BenchmarkPresets.CODING_MAX_TOKENS, applied.maxTokens)
        assertEquals(BenchmarkPresets.CODING_TEMPERATURE, applied.temperature, 0.0001f)
        assertEquals(BenchmarkPresets.CODING_TOP_P, applied.topP, 0.0001f)
        assertEquals(BenchmarkPresets.CODING_TOP_K, applied.topK)
        assertEquals(BenchmarkPresets.CODING_REPEAT_PENALTY, applied.repeatPenalty, 0.0001f)
        assertEquals(hotBase.threadCount, applied.threadCount)
        assertTrue(coding.enableQualityGuard)
    }

    @Test
    fun shortAnswerPresetInheritsGlobalSampler() {
        val shortAnswer = BenchmarkPresets.find("short_answer")!!
        val applied = shortAnswer.applySettingsOverrides(hotBase)

        assertEquals(hotBase.maxTokens, applied.maxTokens)
        assertEquals(hotBase.temperature, applied.temperature, 0.0001f)
        assertEquals(hotBase.topP, applied.topP, 0.0001f)
        assertEquals(hotBase.topK, applied.topK)
        assertEquals(hotBase.repeatPenalty, applied.repeatPenalty, 0.0001f)
        assertFalse(shortAnswer.enableQualityGuard)
    }

    @Test
    fun nullOverridesLeaveBaseUnchangedExceptClamping() {
        val preset = BenchmarkPreset(
            id = "custom",
            name = "Custom",
            prompt = "hi",
        )
        val applied = preset.applySettingsOverrides(hotBase)
        assertEquals(hotBase.maxTokens, applied.maxTokens)
        assertEquals(hotBase.temperature, applied.temperature, 0.0001f)
        assertEquals(hotBase.topP, applied.topP, 0.0001f)
        assertEquals(hotBase.repeatPenalty, applied.repeatPenalty, 0.0001f)
    }

    @Test
    fun threadSweepStillOverridesThreadsAndMaxTokensOnly() {
        val sweep = BenchmarkPresets.find("thread_sweep_4")!!
        val applied = sweep.applySettingsOverrides(hotBase)
        assertEquals(4, applied.threadCount)
        assertEquals(64, applied.maxTokens)
        assertEquals(hotBase.temperature, applied.temperature, 0.0001f)
    }

    @Test
    fun codingMaxTokensIsWithinPlannedBand() {
        assertTrue(BenchmarkPresets.CODING_MAX_TOKENS in 256..384)
    }
}
