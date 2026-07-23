package com.prismai.llmhost

import com.prismai.llmhost.model.ModelLoadLimits
import com.prismai.llmhost.model.ModelReadinessAssessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog coordinates + pure readiness policy for Bonsai Q1_0 (no Android device required).
 */
class BonsaiModelFitTest {

    private val bonsaiQ10Bytes = 3_803_452_480L // HF LFS size for Bonsai-27B-Q1_0.gguf

    @Test
    fun catalogContainsCanonicalBonsaiQ10Entry() {
        val entry = HuggingFaceModelCatalog.find("bonsai_27b_q1_0")
        assertNotNull("Bonsai 27B Q1_0 model entry must exist", entry)
        assertEquals("27B", entry?.parameters)
        assertEquals("prism-ml/Bonsai-27B-gguf", entry?.repoId)
        assertEquals("Bonsai-27B-Q1_0.gguf", entry?.fileName)
        assertEquals("Q1_0 (1.125 bpw)", entry?.quantization)
        assertEquals(bonsaiQ10Bytes, entry?.expectedBytes)
        assertEquals(
            "17ef842e47450caeb8eaa3ebfbbab5d2f2278b62b79be107985fb69a2f819aa0",
            entry?.expectedSha256,
        )
        assertTrue(
            "Bonsai Q1_0 must fit under hard cap",
            entry!!.expectedBytes <= ModelLoadLimits.HARD_CAP_BYTES,
        )
    }

    @Test
    fun hardCapAllowsBonsaiQ10Weights() {
        assertTrue(bonsaiQ10Bytes <= ModelLoadLimits.HARD_CAP_BYTES)
        assertTrue(
            "Old 3584 MiB cap would reject Bonsai",
            bonsaiQ10Bytes > 3584L * 1024L * 1024L,
        )
    }

    @Test
    fun ggufFileTypeMapsQ1_0AndDoesNotConfuseIq1() {
        // llama.h: LLAMA_FTYPE_MOSTLY_IQ1_S = 24, LLAMA_FTYPE_MOSTLY_Q1_0 = 40
        assertEquals("IQ1_S", ModelReadinessAssessor.ggufFileTypeHint(24))
        assertEquals("IQ4_NL", ModelReadinessAssessor.ggufFileTypeHint(25))
        assertEquals("Q1_0", ModelReadinessAssessor.ggufFileTypeHint(40))
        assertEquals("TQ1_0", ModelReadinessAssessor.ggufFileTypeHint(36))
        assertEquals("TQ2_0", ModelReadinessAssessor.ggufFileTypeHint(37))
    }

    @Test
    fun quantizationHintParsesBonsaiFilename() {
        assertEquals("Q1_0", ModelReadinessAssessor.quantizationHint("Bonsai-27B-Q1_0.gguf"))
        assertEquals("Q1_0", ModelReadinessAssessor.quantizationHint("bonsai_27b_q1_0"))
        assertEquals("Q4_K_M", ModelReadinessAssessor.quantizationHint("qwen2.5-0.5b-instruct-q4_k_m.gguf"))
    }

    @Test
    fun q1_0OverheadIsPackedNotFp16Expand() {
        val q1 = ModelReadinessAssessor.quantizationOverheadMultiplier("Q1_0")
        val f16 = ModelReadinessAssessor.quantizationOverheadMultiplier("F16")
        assertTrue("Q1_0 overhead ($q1) should be well below F16 ($f16)", q1 < f16)
        assertTrue(q1 in 0.10..0.25)
    }

    @Test
    fun largeModelLowAvailableRamIsTooLarge() {
        // ~5 GiB peak estimate class: required ~5e9, available 4 GiB → TOO_LARGE via large-model gate
        val rating = ModelReadinessAssessor.rateModelFit(
            modelBytes = bonsaiQ10Bytes,
            requiredRamBytes = 5_000_000_000L,
            availableAfterUnloadBytes = 4_000_000_000L,
            lowMemory = false,
        )
        assertEquals(ModelFitRating.TOO_LARGE, rating)
    }

    @Test
    fun largeModelMidAvailableRamIsRiskyNotSafe() {
        val rating = ModelReadinessAssessor.rateModelFit(
            modelBytes = bonsaiQ10Bytes,
            requiredRamBytes = 5_000_000_000L,
            availableAfterUnloadBytes = 8_000_000_000L,
            lowMemory = false,
        )
        assertEquals(ModelFitRating.RISKY, rating)
    }

    @Test
    fun largeModelHighAvailableRamCanBeSafe() {
        // 12 GiB available, required 5 GiB → safeBudget ~9.36 GiB
        val rating = ModelReadinessAssessor.rateModelFit(
            modelBytes = bonsaiQ10Bytes,
            requiredRamBytes = 5_000_000_000L,
            availableAfterUnloadBytes = 12_000_000_000L,
            lowMemory = false,
        )
        assertEquals(ModelFitRating.SAFE, rating)
    }

    @Test
    fun overHardCapIsAlwaysTooLarge() {
        val rating = ModelReadinessAssessor.rateModelFit(
            modelBytes = ModelLoadLimits.HARD_CAP_BYTES + 1L,
            requiredRamBytes = 1_000_000_000L,
            availableAfterUnloadBytes = 32_000_000_000L,
            lowMemory = false,
        )
        assertEquals(ModelFitRating.TOO_LARGE, rating)
    }
}
