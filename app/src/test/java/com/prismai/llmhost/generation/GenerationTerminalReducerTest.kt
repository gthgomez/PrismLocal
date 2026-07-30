package com.prismai.llmhost.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationTerminalReducerTest {

    @Test
    fun qualityAbortNotOverwrittenByCancelled() {
        val aborted = GenerationTerminalReducer.TerminalState(
            reason = "QUALITY_ABORT",
            detail = "REPETITION_LOOP: phrase_repeats=6",
        )
        val merged = GenerationTerminalReducer.mergeTerminalChunk(aborted, "CANCELLED")
        assertEquals("QUALITY_ABORT", merged.reason)
        assertEquals("REPETITION_LOOP: phrase_repeats=6", merged.detail)
    }

    @Test
    fun qualityAbortNotOverwrittenByError() {
        val aborted = GenerationTerminalReducer.TerminalState(
            reason = "QUALITY_ABORT",
            detail = "SCRIPT_CHAOS: scripts=3",
        )
        val merged = GenerationTerminalReducer.mergeTerminalChunk(aborted, "ERROR")
        assertEquals("QUALITY_ABORT", merged.reason)
        assertEquals("SCRIPT_CHAOS: scripts=3", merged.detail)
    }

    @Test
    fun errorChunkSetsDefaultDetail() {
        val merged = GenerationTerminalReducer.mergeTerminalChunk(
            GenerationTerminalReducer.TerminalState(),
            "ERROR",
        )
        assertEquals("ERROR", merged.reason)
        assertEquals("native_runtime_error", merged.detail)
    }

    @Test
    fun emptyStartResolvesToErrorDetail() {
        val resolved = GenerationTerminalReducer.resolveFinal(
            current = GenerationTerminalReducer.TerminalState(),
            causeIsCancellation = false,
            checkEmptyStart = true,
            generatedTokens = 0,
        )
        assertEquals("ERROR", resolved.reason)
        assertEquals("generation_did_not_start", resolved.detail)
    }

    @Test
    fun cancellationResolvesToCancelledWithUserStop() {
        val resolved = GenerationTerminalReducer.resolveFinal(
            current = GenerationTerminalReducer.TerminalState(),
            causeIsCancellation = true,
            checkEmptyStart = true,
            generatedTokens = 10,
            userStopLikely = true,
        )
        assertEquals("CANCELLED", resolved.reason)
        assertEquals("user_stop", resolved.detail)
    }

    @Test
    fun qualityAbortWinsInResolveFinal() {
        val resolved = GenerationTerminalReducer.resolveFinal(
            current = GenerationTerminalReducer.TerminalState(
                reason = "QUALITY_ABORT",
                detail = "REPETITION_LOOP: x",
            ),
            causeIsCancellation = true,
            checkEmptyStart = true,
            generatedTokens = 80,
        )
        assertEquals("QUALITY_ABORT", resolved.reason)
        assertEquals("REPETITION_LOOP: x", resolved.detail)
    }

    @Test
    fun cleanEofWhenNoReason() {
        val resolved = GenerationTerminalReducer.resolveFinal(
            current = GenerationTerminalReducer.TerminalState(),
            causeIsCancellation = false,
            checkEmptyStart = true,
            generatedTokens = 50,
        )
        assertEquals("EOF", resolved.reason)
        assertNull(resolved.detail)
    }
}
