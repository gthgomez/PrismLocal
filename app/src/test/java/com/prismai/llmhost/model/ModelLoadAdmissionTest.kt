package com.prismai.llmhost.model

import org.junit.Assert.assertFalse
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
}
