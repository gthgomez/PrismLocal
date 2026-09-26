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
            GenerationOrchestrator.mapErrorCodeToUserMessage(
                NativeErrorCode.DEBUG_GENERATION_REJECTED.value,
                null,
            ),
        )
        assertEquals(
            "Failed to load model",
            GenerationOrchestrator.mapErrorCodeToUserMessage(
                NativeErrorCode.MODEL_LOAD_FAILED.value,
                null,
            ),
        )
        assertNotEquals(
            NativeErrorCode.MODEL_LOAD_FAILED.value,
            NativeErrorCode.DEBUG_GENERATION_REJECTED.value,
        )
        assertTrue(NativeErrorCode.DEBUG_GENERATION_REJECTED.value !in 5000..5999)
    }

    @Test
    fun everyDefinedNativeCodeHasADirectMapping() {
        val expectedValues = linkedMapOf(
            NativeErrorCode.OK to 0,
            NativeErrorCode.DEBUG_HOOKS_REJECTED to 403,
            NativeErrorCode.HANDLE_INVALID_OR_CLOSED to 404,
            NativeErrorCode.DEBUG_GENERATION_REJECTED to 405,
            NativeErrorCode.PROMPT_DOES_NOT_FIT to 420,
            NativeErrorCode.TOKENIZE_SIZE_FAILED to 421,
            NativeErrorCode.CONTEXT_WINDOW_TOO_SMALL to 422,
            NativeErrorCode.TOKENIZE_FAILED to 423,
            NativeErrorCode.SAMPLER_INIT_FAILED to 424,
            NativeErrorCode.NULL_TOKEN_PRODUCED to 425,
            NativeErrorCode.CONTEXT_SHIFT_FAILED to 426,
            NativeErrorCode.GRAMMAR_COMPILE_FAILED to 427,
            NativeErrorCode.NO_CONTINUATION_CONTEXT to 428,
            NativeErrorCode.NATIVE_EXCEPTION to 500,
            NativeErrorCode.MODEL_LOAD_FAILED to 501,
        )
        assertEquals(expectedValues, NativeErrorCode.entries.associate { it to it.value })

        val expectedMessages = linkedMapOf(
            NativeErrorCode.OK to "native runtime error",
            NativeErrorCode.DEBUG_HOOKS_REJECTED to "Debug hooks rejected operation",
            NativeErrorCode.HANDLE_INVALID_OR_CLOSED to "Model is not loaded",
            NativeErrorCode.DEBUG_GENERATION_REJECTED to "Debug generation rejected",
            NativeErrorCode.PROMPT_DOES_NOT_FIT to "Prompt exceeds context window limit",
            NativeErrorCode.TOKENIZE_SIZE_FAILED to "Prompt token count estimation failed",
            NativeErrorCode.CONTEXT_WINDOW_TOO_SMALL to "Context window too small for prompt",
            NativeErrorCode.TOKENIZE_FAILED to "Tokenization failed",
            NativeErrorCode.SAMPLER_INIT_FAILED to "Sampler initialization failed",
            NativeErrorCode.NULL_TOKEN_PRODUCED to "Null token produced",
            NativeErrorCode.CONTEXT_SHIFT_FAILED to "Context shift operation failed",
            NativeErrorCode.GRAMMAR_COMPILE_FAILED to "Grammar compilation failed",
            NativeErrorCode.NO_CONTINUATION_CONTEXT to "Cannot continue without prior context",
            NativeErrorCode.MODEL_LOAD_FAILED to "Failed to load model",
            NativeErrorCode.NATIVE_EXCEPTION to "Native exception",
        )

        assertEquals(NativeErrorCode.entries.toSet(), expectedMessages.keys)
        expectedMessages.forEach { (code, expected) ->
            assertEquals(
                "native error code ${code.name}",
                expected,
                GenerationOrchestrator.mapErrorCodeToUserMessage(code.value, null),
            )
        }
    }
}
