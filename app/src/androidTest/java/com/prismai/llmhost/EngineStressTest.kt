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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineStressTest {
    @Test
    fun createDestroyLoop() = runBlocking {
        repeat(25) {
            val engine = NativeLlmBridge.create()
            engine.destroySafely()
        }
    }

    @Test
    fun ringBuffer1000Tokens() = runBlocking {
        val engine = NativeLlmBridge.create()
        assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))

        val chunks = engine.generate("DEBUG_SIMULATE_RING").toList()
        val totalTokens = chunks.sumOf { it.tokenCount }
        val sawEof = chunks.any { it.isTerminal && it.terminalReason == "EOF" }
        val highestBatch = chunks.maxOf { it.tokenCount }

        assertEquals(1000, totalTokens)
        assertTrue(sawEof)
        assertTrue(highestBatch > 1)
        engine.destroySafely()
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
