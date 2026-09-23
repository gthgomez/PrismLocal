package com.prismai.llmhost.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationErrorMappingTest {

    @Test
    fun debugGenerationRejectionIsNotReportedAsModelLoadFailure() {
        assertEquals(
            "Debug generation rejected",
            GenerationOrchestrator.mapErrorCodeToUserMessage(405, null),
        )
        assertEquals(
            "Failed to load model",
            GenerationOrchestrator.mapErrorCodeToUserMessage(501, null),
        )
        assertNotEquals(501, 405)
        assertTrue(405 !in 5000..5999)
    }

    @Test
    fun everyDefinedNativeCodeHasADirectMapping() {
        val expectedMessages = linkedMapOf(
            0 to "native runtime error",
            403 to "Debug hooks rejected operation",
            404 to "Model is not loaded",
            405 to "Debug generation rejected",
            420 to "Prompt exceeds context window limit",
            421 to "Prompt token count estimation failed",
            422 to "Context window too small for prompt",
            423 to "Tokenization failed",
            424 to "Sampler initialization failed",
            425 to "Null token produced",
            426 to "Context shift operation failed",
            427 to "Grammar compilation failed",
            428 to "Cannot continue without prior context",
            501 to "Failed to load model",
        )

        expectedMessages.forEach { (code, expected) ->
            assertEquals(
                "native error code $code",
                expected,
                GenerationOrchestrator.mapErrorCodeToUserMessage(code, null),
            )
        }
    }
}
