package com.prismai.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GenerationSettingsClampTest {
    @Test
    fun zeroTemperatureSurvivesClampSoGreedySamplingCanBeRequested() {
        val clamped = GenerationSettings(temperature = 0f).clamped()
        assertEquals(0f, clamped.temperature)
    }

    @Test
    fun defaultLoadRequestsZeroGpuLayers() {
        val defaults = GenerationSettings()
        assertEquals(0, defaults.gpuLayers)
        assertEquals(0.70f, defaults.temperature)
        assertFalse(defaults.enableFlashAttn)
        assertEquals("f16", defaults.kvCacheTypeK)
        assertEquals("f16", defaults.kvCacheTypeV)
    }
}
