package com.prismai.llmhost.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoadAdmissionTest {
    @Test
    fun memoryEstimateBlocksOnlyWhenItExceedsAvailableHeadroom() {
        assertFalse(ModelLoadLimits.isLoadBlocked(1_000, 2_000, 2_000, false))
        assertTrue(ModelLoadLimits.isLoadBlocked(1_000, 2_001, 2_000, false))
    }

    @Test
    fun previouslyProvenModelCanPassEstimateButNeverTheHardSizeCap() {
        assertFalse(ModelLoadLimits.isLoadBlocked(1_000, 2_001, 2_000, true))
        assertTrue(ModelLoadLimits.isLoadBlocked(ModelLoadLimits.HARD_CAP_BYTES + 1, 1, 2, true))
    }

    @Test
    fun largeKvEstimateCannotWrapIntoAnAdmissibleLoad() {
        val estimate = ModelReadinessAssessor.requiredMemoryWithKv(4_000, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, estimate)
        assertTrue(ModelLoadLimits.isLoadBlocked(1_000, estimate, 3_000, false))
        assertEquals(6_000L, ModelReadinessAssessor.requiredMemoryWithKv(4_000, 2_000))
    }

    @Test
    fun varyingKvHeadsSizeKeysPerLayerAndPadValuesToTheWidestLayer() {
        val mib = 1024L * 1024
        val estimate = ModelReadinessAssessor.estimateKvCacheBytes(
            contextLength = 16_384,
            blockCount = 32,
            embeddingLength = 1024,
            attentionHeadCount = 16,
            attentionHeadCountKv = 4,
            kvTypeK = "f16",
            kvTypeV = "f16",
            attentionKvHeadLayerSum = 96,
        )
        assertEquals(448 * mib, estimate)
        assertFalse(ModelLoadLimits.isLoadBlocked(100 * mib, 100 * mib + estimate, 600 * mib, false))
    }
}
