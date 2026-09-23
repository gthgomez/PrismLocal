package com.prismai.llmhost.bridge

import com.prismai.llmhost.GenerationChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests verifying the lossless flow buffering invariant for [NativeLlmBridge].
 *
 * Ensures that with a bounded channel capacity >= maxTokens + headroom, rapid non-blocking
 * emissions via trySend() never drop chunks under backpressure from a slow consumer.
 */
class NativeBridgeBackpressureTest {

    private fun ProducerScope<GenerationChunk>.emitChunk(chunk: GenerationChunk): Boolean {
        val result = trySend(chunk)
        return result.isSuccess
    }

    @Test
    fun testLosslessBufferingUnderSlowConsumer() = runBlocking(Dispatchers.Default) {
        val totalTokens = 1024
        val headroom = 64
        val capacity = totalTokens + headroom

        val flow = callbackFlow {
            // Rapidly emit 1,024 non-terminal chunks + 1 terminal chunk
            for (i in 1..totalTokens) {
                val ok = emitChunk(
                    GenerationChunk(
                        text = "t$i ",
                        tokenCount = 1,
                        generationId = 1,
                        isTerminal = false,
                    )
                )
                assertTrue("Emission $i failed unexpectedly on buffered channel", ok)
            }
            val termOk = emitChunk(
                GenerationChunk(
                    text = "",
                    tokenCount = 0,
                    generationId = 1,
                    isTerminal = true,
                    terminalReason = "EOF",
                )
            )
            assertTrue("Terminal emission failed unexpectedly", termOk)
            close()
        }.buffer(capacity)

        val received = mutableListOf<GenerationChunk>()
        flow.collect { chunk ->
            received.add(chunk)
            // Simulate slow consumer (e.g. UI rendering or transcript formatting delay)
            if (received.size % 100 == 0) {
                delay(2)
            }
        }

        assertEquals("All chunks must be delivered without loss", totalTokens + 1, received.size)
        for (i in 1..totalTokens) {
            assertEquals("Chunk $i text mismatch", "t$i ", received[i - 1].text)
            assertEquals("Chunk $i token count mismatch", 1, received[i - 1].tokenCount)
            assertFalse("Chunk $i should not be terminal", received[i - 1].isTerminal)
        }
        val terminal = received.last()
        assertTrue("Last chunk must be terminal", terminal.isTerminal)
        assertEquals("Terminal reason must be EOF", "EOF", terminal.terminalReason)
    }

    @Test
    fun testCancellationHandlesTrySendGracefully() = runBlocking(Dispatchers.Default) {
        val capacity = 16
        val flow = callbackFlow {
            val ok1 = emitChunk(
                GenerationChunk(
                    text = "start",
                    tokenCount = 1,
                    generationId = 1,
                    isTerminal = false,
                )
            )
            assertTrue("Initial emission should succeed", ok1)
            close()
            // After close(), trySend must return failure (isSuccess == false) without throwing
            val ok2 = emitChunk(
                GenerationChunk(
                    text = "after_close",
                    tokenCount = 1,
                    generationId = 1,
                    isTerminal = false,
                )
            )
            assertFalse("trySend must report failure on closed channel", ok2)
        }.buffer(capacity)

        val list = flow.toList()
        assertEquals(1, list.size)
        assertEquals("start", list[0].text)
    }
}
