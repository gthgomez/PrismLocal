package com.prismai.llmhost.model

import com.prismai.llmhost.ModelFitRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (no device) tests for the local-model admission math:
 *  - H2: KV-cache size derived from the GGUF attention shape, not a flat constant.
 *  - H1: fail-open ratings — anything that physically fits is SAFE or RISKY, never
 *        TOO_LARGE, and RISKY is an allowed (slow) state rather than a block.
 */
class ModelKvEstimateTest {

    // Shapes read from the real test GGUFs' GGUF headers.
    private val llama1b = Shape(layers = 16, embd = 2048, heads = 32, kvHeads = 8)
    private val qwen15b = Shape(layers = 28, embd = 1536, heads = 12, kvHeads = 2)

    private data class Shape(val layers: Int, val embd: Int, val heads: Int, val kvHeads: Int)

    private fun kv(shape: Shape, ctx: Int, k: String, v: String): Long =
        ModelReadinessAssessor.estimateKvCacheBytes(
            contextLength = ctx,
            blockCount = shape.layers,
            embeddingLength = shape.embd,
            attentionHeadCount = shape.heads,
            attentionHeadCountKv = shape.kvHeads,
            kvTypeK = k,
            kvTypeV = v,
        )

    @Test
    fun llama1bQ8KvIsTensOfMiBNotHundreds() {
        // 16 * 8 * 64 * (1.0625 + 1.0625) * 2048 = 35_651_584
        assertEquals(35_651_584L, kv(llama1b, 2048, "q8_0", "q8_0"))
        // Old flat estimate was 256 KiB/token = 512 MiB at 2048.
        val oldFlat = 2048L * 256L * 1024L
        assertTrue("shape-derived KV must be far below the old flat estimate", kv(llama1b, 2048, "q8_0", "q8_0") < oldFlat / 10)
    }

    @Test
    fun qwen15bGqaKvUsesKvHeadCount() {
        // 28 * 2 * 128 * 2.125 * 2048 = 31_195_136
        assertEquals(31_195_136L, kv(qwen15b, 2048, "q8_0", "q8_0"))
    }

    @Test
    fun f16KvIsLargerThanQ8() {
        val q8 = kv(llama1b, 2048, "q8_0", "q8_0")
        val f16 = kv(llama1b, 2048, "f16", "f16")
        // f16 is 2.0 bytes/elem vs q8_0's 34/32 = 1.0625, so ~1.88x (not exactly 2x).
        assertEquals(67_108_864L, f16)
        assertTrue(f16 > q8)
    }

    @Test
    fun kvBytesPerElementOrdering() {
        val f16 = ModelReadinessAssessor.kvBytesPerElement("f16")
        val q8 = ModelReadinessAssessor.kvBytesPerElement("q8_0")
        val q4 = ModelReadinessAssessor.kvBytesPerElement("q4_0")
        assertTrue(f16 > q8)
        assertTrue(q8 > q4)
    }

    @Test
    fun kvScalesLinearlyWithContext() {
        val at2048 = kv(llama1b, 2048, "q8_0", "q8_0")
        val at4096 = kv(llama1b, 4096, "q8_0", "q8_0")
        assertEquals(at2048 * 2, at4096)
    }

    @Test
    fun missingShapeFallsBackToCoarsePerToken() {
        val bytes = ModelReadinessAssessor.estimateKvCacheBytes(
            contextLength = 2048,
            blockCount = null,
            embeddingLength = null,
            attentionHeadCount = null,
            attentionHeadCountKv = null,
            kvTypeK = "q8_0",
            kvTypeV = "q8_0",
        )
        assertEquals(2048L * 64L * 1024L, bytes)
    }

    @Test
    fun fittingLargeModelIsRiskyNotBlocked() {
        // 3.8 GiB model, 5 GiB required, 8 GiB available after unload: it fits, so it
        // must be RISKY (loadable), never TOO_LARGE.
        val rating = ModelReadinessAssessor.rateModelFit(
            modelBytes = 3_803_452_480L,
            requiredRamBytes = 5_000_000_000L,
            availableAfterUnloadBytes = 8_000_000_000L,
            lowMemory = false,
        )
        assertEquals(ModelFitRating.RISKY, rating)
    }

    @Test
    fun modelThatCannotFitIsTooLarge() {
        val rating = ModelReadinessAssessor.rateModelFit(
            modelBytes = 3_803_452_480L,
            requiredRamBytes = 5_000_000_000L,
            availableAfterUnloadBytes = 4_000_000_000L,
            lowMemory = false,
        )
        assertEquals(ModelFitRating.TOO_LARGE, rating)
    }

    @Test
    fun comfortableFitIsSafe() {
        val rating = ModelReadinessAssessor.rateModelFit(
            modelBytes = 955_445_792L,
            requiredRamBytes = 2_000_000_000L,
            availableAfterUnloadBytes = 8_000_000_000L,
            lowMemory = false,
        )
        assertEquals(ModelFitRating.SAFE, rating)
    }

    @Test
    fun lowMemoryDowngradesToRiskyButDoesNotBlock() {
        val rating = ModelReadinessAssessor.rateModelFit(
            modelBytes = 955_445_792L,
            requiredRamBytes = 2_000_000_000L,
            availableAfterUnloadBytes = 8_000_000_000L,
            lowMemory = true,
        )
        assertEquals(ModelFitRating.RISKY, rating)
    }

    @Test
    fun hardCapStillBlocks() {
        val rating = ModelReadinessAssessor.rateModelFit(
            modelBytes = com.prismai.llmhost.model.ModelLoadLimits.HARD_CAP_BYTES + 1L,
            requiredRamBytes = 1_000_000_000L,
            availableAfterUnloadBytes = 32_000_000_000L,
            lowMemory = false,
        )
        assertEquals(ModelFitRating.TOO_LARGE, rating)
    }
}
