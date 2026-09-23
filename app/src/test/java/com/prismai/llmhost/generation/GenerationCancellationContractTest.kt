package com.prismai.llmhost.generation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationCancellationContractTest {

    @Test
    fun qualityAbortRetryUsesTheActiveChunkGenerationId() = runBlocking {
        val requestedIds = mutableListOf<Int>()
        var attempts = 0

        val canceled = cancelActiveGeneration(
            generationId = 73,
            cancel = { generationId ->
                requestedIds += generationId
                attempts += 1
                if (attempts == 1) error("retry cancellation")
            },
        )

        assertTrue(canceled)
        assertEquals(listOf(73, 73), requestedIds)
    }
}
