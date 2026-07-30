package com.prismai.llmhost.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTierHintsTest {

    @Test
    fun gemma1bIsTinyForCoding() {
        assertTrue(ModelTierHints.isTinyForCoding("gemma-3-1b-it-Q4_K_M"))
        assertNotNull(ModelTierHints.codingBenchmarkWarning("gemma-3-1b-it-Q4_K_M"))
    }

    @Test
    fun onePointFiveBIsTinyForCoding() {
        assertTrue(ModelTierHints.isTinyForCoding("Qwen2.5-1.5B-Instruct-Q4_K_M"))
        assertTrue(ModelTierHints.isTinyForCoding("DeepSeek-R1-Distill-Qwen-1.5B"))
        assertEquals(1.5, ModelTierHints.estimateParamsBillions("Qwen2.5-1.5B")!!, 0.01)
    }

    @Test
    fun threeBIsNotTiny() {
        assertFalse(ModelTierHints.isTinyForCoding("Llama-3.2-3B-Instruct-Q4_K_M"))
        assertNull(ModelTierHints.codingBenchmarkWarning("Llama-3.2-3B-Instruct-Q4_K_M"))
    }

    @Test
    fun estimatesParamsFromId() {
        assertEquals(1.0, ModelTierHints.estimateParamsBillions("gemma-3-1b-it")!!, 0.01)
        assertEquals(3.0, ModelTierHints.estimateParamsBillions("Qwen2.5-3B-Instruct")!!, 0.01)
        assertEquals(0.36, ModelTierHints.estimateParamsBillions("smollm-360M")!!, 0.01)
        // MoE: active expert size, not experts × size
        assertEquals(0.6, ModelTierHints.estimateParamsBillions("Qwen3-Desert.Coder.MoE-8X0.6B")!!, 0.01)
    }

    @Test
    fun moeActiveExpertIsTinyForCoding() {
        assertTrue(ModelTierHints.isTinyForCoding("Qwen3-Desert.Coder.MoE-8X0.6B"))
    }

    @Test
    fun tinyLlamaFlaggedByName() {
        assertTrue(ModelTierHints.isTinyForCoding("TinyLlama-1.1B-Chat-v1.0"))
    }

    @Test
    fun catalogIdWithoutNbTokenUsesParameters() {
        // Curated entry id has no "1.5B" token; fallback reads catalog parameters.
        assertEquals(1.5, ModelTierHints.estimateParamsBillions("qwen25_15b_q4km")!!, 0.01)
        assertTrue(ModelTierHints.isTinyForCoding("qwen25_15b_q4km"))
    }
}
