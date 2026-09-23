package com.prismai.llmhost.bridge

import com.prismai.llmhost.GenerationChunk
import com.prismai.llmhost.GenerationSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the bridge's production stream-buffer seam. The native
 * engine is intentionally not mocked: the channel, producer, and slow consumer
 * are real coroutine components, while only the JNI boundary is omitted.
 */
class NativeBridgeBackpressureTest {

    private fun chunk(index: Int, terminal: Boolean = false): GenerationChunk =
        GenerationChunk(
            text = if (terminal) "terminal" else "t$index",
            tokenCount = if (terminal) 0 else 1,
            generationId = 42,
            isTerminal = terminal,
            terminalReason = if (terminal) "EOF" else "NONE",
        )

    @Test
    fun productionCapacityIsClampedAndLeavesTerminalHeadroom() {
        assertEquals(65, generationStreamBufferCapacity(0))
        assertEquals(65, generationStreamBufferCapacity(1))
        assertEquals(1088, generationStreamBufferCapacity(1024))
        assertEquals(1088, generationStreamBufferCapacity(2048))
    }

    @Test
    fun losslessDeliveryAndTerminalWithSlowConsumer() = runBlocking {
        val maxTokens = GenerationSettings.MAX_MAX_TOKENS
        val flow = callbackFlow {
            for (index in 1..maxTokens) {
                sendChunk(chunk(index))
            }
            sendChunk(chunk(0, terminal = true))
            close()
        }.buffer(generationStreamBufferCapacity(maxTokens))

        val received = mutableListOf<GenerationChunk>()
        withTimeout(10_000) {
            flow.collect { received += it; delay(1) }
        }

        assertEquals(maxTokens + 1, received.size)
        assertEquals(
            (1..maxTokens).map { "t$it" },
            received.take(maxTokens).map { it.text },
        )
        assertEquals(1, received.count { it.isTerminal })
        val terminal = received.last()
        assertTrue(terminal.isTerminal)
        assertEquals("EOF", terminal.terminalReason)
        assertEquals("terminal", terminal.text)
    }

    @Test
    fun fullBufferAppliesBackpressureWithoutDroppingChunks() = runBlocking {
        val totalDataChunks = 32
        val flow = callbackFlow {
            for (index in 1..totalDataChunks) {
                sendChunk(chunk(index))
            }
            sendChunk(chunk(0, terminal = true))
            close()
        }.buffer(1)

        val received = withTimeout(5_000) {
            flow.toList()
        }

        assertEquals(totalDataChunks + 1, received.size)
        assertEquals(
            (1..totalDataChunks).map { "t$it" },
            received.take(totalDataChunks).map { it.text },
        )
        assertTrue(received.last().isTerminal)
        assertEquals("EOF", received.last().terminalReason)
    }

    @Test
    fun emissionAfterCloseCompletesGracefully() = runBlocking {
        val producerFinished = CompletableDeferred<Unit>()
        val flow = callbackFlow {
            sendChunk(chunk(1))
            close()
            sendChunk(chunk(2))
            producerFinished.complete(Unit)
        }.buffer(generationStreamBufferCapacity(1))

        val received = withTimeout(5_000) { flow.toList() }
        assertEquals(listOf("t1"), received.map { it.text })
        withTimeout(5_000) { producerFinished.await() }
    }

    @Test
    fun consumerCancellationStopsBackpressuredProducer() = runBlocking {
        val firstReceived = CompletableDeferred<Unit>()
        val flow = callbackFlow {
            repeat(10_000) { index -> sendChunk(chunk(index + 1)) }
            close()
        }.buffer(1)

        val collector = launch(Dispatchers.Default) {
            flow.collect {
                firstReceived.complete(Unit)
                awaitCancellation()
            }
        }

        withTimeout(5_000) { firstReceived.await() }
        collector.cancelAndJoin()
        assertTrue("collector should finish after cancellation", collector.isCompleted)
    }
}
