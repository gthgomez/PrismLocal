package com.prismai.llmhost
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineStressTest {
    private companion object {
        const val DEBUG_SIMULATED_TOKEN_COUNT = 3072
    }

    @Test
    fun createDestroyLoop() = runBlocking {
        repeat(25) {
            val engine = NativeLlmBridge.create()
            engine.destroySafely()
        }
    }

    @Test
    fun ringBufferBeyondCapacityPreservesAllTokens() = runBlocking {
        val engine = NativeLlmBridge.create()
        assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))

        val chunks = engine.generate("DEBUG_SIMULATE_RING").toList()
        val totalTokens = chunks.sumOf { it.tokenCount }
        val sawEof = chunks.any { it.isTerminal && it.terminalReason == "EOF" }
        val highestBatch = chunks.maxOf { it.tokenCount }

        assertEquals(DEBUG_SIMULATED_TOKEN_COUNT, totalTokens)
        assertTrue(sawEof)
        assertTrue(highestBatch > 1)
        engine.destroySafely()
    }

    @Test
    fun slowConsumerAcknowledgesOnlyDeliveredBatchesBeyondRingCapacity() = runBlocking {
        val engine = NativeLlmBridge.create()
        try {
            assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))
            val deliveredText = StringBuilder()
            var tokenCount = 0
            var terminalReason: String? = null

            engine.generate("DEBUG_SIMULATE_RING").collect { chunk ->
                delay(8)
                tokenCount += chunk.tokenCount
                deliveredText.append(chunk.text)
                if (chunk.isTerminal) terminalReason = chunk.terminalReason
            }

            val deliveredTokens = Regex("T\\d+").findAll(deliveredText).map { it.value }.toList()
            assertEquals(DEBUG_SIMULATED_TOKEN_COUNT, tokenCount)
            assertEquals((10_000 until 10_000 + DEBUG_SIMULATED_TOKEN_COUNT).map { "T$it" }, deliveredTokens)
            assertEquals("EOF", terminalReason)
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun cancellationResetThenNewGenerationHasNoOldRingTokens() = runBlocking {
        val engine = NativeLlmBridge.create()
        try {
            assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))
            val cancelled = launch { engine.generate("DEBUG_SIMULATE_RING").collect { delay(2) } }
            delay(20)
            cancelled.cancelAndJoin()
            engine.resetConversation()

            val following = engine.generate("DEBUG_SIMULATE_RING").toList()
            val deliveredText = following.joinToString("") { it.text }
            val deliveredTokens = Regex("T\\d+").findAll(deliveredText).map { it.value }.toList()
            assertEquals(DEBUG_SIMULATED_TOKEN_COUNT, following.sumOf { it.tokenCount })
            assertEquals((10_000 until 10_000 + DEBUG_SIMULATED_TOKEN_COUNT).map { "T$it" }, deliveredTokens)
            assertTrue(following.any { it.isTerminal && it.terminalReason == "EOF" })
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun jniDrainCarrierAndAcknowledgementSchemaMatchProduction() = runBlocking {
        val engine = NativeLlmBridge.create()
        val generationId = 8123
        try {
            assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))
            assertTrue(engine.debugStartGenerationForTesting("DEBUG_SIMULATE_RING", generationId))
            var first = engine.debugDrainResultForTesting(generationId, 64)
            repeat(100) {
                if (first.tokensCount == 0) {
                    delay(5)
                    first = engine.debugDrainResultForTesting(generationId, 64)
                }
            }

            assertEquals(NativeDrainResult.SCHEMA_VERSION, first.schemaVersion)
            assertTrue("expected JNI token batch", first.tokensCount > 0)
            assertEquals(0, first.errorCode)
            assertEquals(1, first.state) // GENERATING

            val retry = engine.debugDrainResultForTesting(generationId, 64)
            assertEquals(first.tokensCount, retry.tokensCount)
            assertArrayEquals(
                first.tokensBuffer.copyOf(first.tokensCount),
                retry.tokensBuffer.copyOf(retry.tokensCount),
            )
            assertTrue(engine.debugAcknowledgeDrainedForTesting(generationId, first.drainTail, first.tokensCount))
            assertFalse(engine.debugAcknowledgeDrainedForTesting(generationId, first.drainTail, first.tokensCount))

            val next = engine.debugDrainResultForTesting(generationId, 64)
            if (next.tokensCount > 0) {
                assertEquals(first.drainTail + first.tokensCount, next.drainTail)
                assertEquals(10_000 + first.tokensCount, next.tokensBuffer[0])
            }
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun staleGenerationCannotReadReplacementMetadataOrTokens() = runBlocking {
        val engine = NativeLlmBridge.create()
        try {
            assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))
            assertTrue(engine.debugStartGenerationForTesting("DEBUG_SIMULATE_RING", 8201))
            delay(10)
            engine.debugCancelGenerationForTesting(8201)
            engine.resetConversation()
            assertTrue(engine.debugStartGenerationForTesting("DEBUG_SIMULATE_RING", 8202))
            delay(10)

            val replacement = engine.debugDrainResultForTesting(8202, 32)
            val stale = engine.debugDrainResultForTesting(8201, 32)
            assertTrue(replacement.tokensCount > 0)
            assertEquals(NativeDrainResult.SCHEMA_VERSION, stale.schemaVersion)
            assertEquals(0, stale.tokensCount)
            assertEquals(6, stale.state) // TOMBSTONED
            assertEquals(404, stale.errorCode)
            assertEquals(0L, stale.produced)
            assertEquals(0L, stale.drained)
            assertFalse(stale.pending)
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun unsupportedDebugPromptReportsDedicatedRejectionCode() = runBlocking {
        val engine = NativeLlmBridge.create()
        try {
            assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))

            val terminal = engine.generate("UNSUPPORTED_DEBUG_PROMPT").toList().last { it.isTerminal }
            assertEquals("ERROR", terminal.terminalReason)
            assertEquals(405, terminal.errorCode)
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun cancellationDoesNotRequireTerminalAfterCollectorCancel() = runBlocking {
        val engine = NativeLlmBridge.create()
        assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))
        var lastGenId = -1

        val job = launch {
            engine.generate("DEBUG_SIMULATE_RING").collect { chunk ->
                lastGenId = chunk.generationId
            }
        }

        delay(25)
        job.cancelAndJoin()

        if (lastGenId >= 0) {
            assertTrue(engine.debugDrainTokensForTesting(lastGenId, 128).isEmpty())
        }
        engine.destroySafely()
    }

    @Test
    fun loadUnloadRaceCancelsSafely() = runBlocking {
        val engine = NativeLlmBridge.create()
        assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))

        val job = launch {
            engine.generate("DEBUG_SIMULATE_RING").collect { }
        }

        delay(10)
        engine.unloadModel()
        job.cancelAndJoin()
        engine.destroySafely()
    }
}
