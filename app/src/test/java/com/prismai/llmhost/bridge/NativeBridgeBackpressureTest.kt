package com.prismai.llmhost.bridge

import com.prismai.llmhost.GenerationChunk
import com.prismai.llmhost.GenerationSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun tombstonedWithErrorRequestsImmediateErrorTerminal() {
        val decision = decideDrainState(NATIVE_STATE_TOMBSTONED, errorCode = 404)

        assertEquals(DrainStateAction.TERMINAL, decision.action)
        assertEquals("ERROR", decision.terminalReason)
        assertFalse(decision.waitForPending)
    }

    @Test
    fun tombstonedWithoutErrorStopsForCleanup() {
        val decision = decideDrainState(NATIVE_STATE_TOMBSTONED, errorCode = 0)

        assertEquals(DrainStateAction.STOP, decision.action)
        assertEquals(null, decision.terminalReason)
    }

    @Test
    fun partialStreamIdleEmitsCancellationTerminal() = runBlocking {
        val decision = decideDrainState(NATIVE_STATE_IDLE, errorCode = 0)
        val flow = callbackFlow {
            sendChunk(chunk(1))
            if (decision.action == DrainStateAction.TERMINAL) {
                sendChunk(
                    GenerationChunk(
                        text = "",
                        tokenCount = 0,
                        generationId = 42,
                        isTerminal = true,
                        terminalReason = decision.terminalReason.orEmpty(),
                    ),
                )
            }
            close()
        }.buffer(generationStreamBufferCapacity(1))

        val received = withTimeout(5_000) { flow.toList() }

        assertEquals(2, received.size)
        assertEquals("t1", received.first().text)
        assertTrue(received.last().isTerminal)
        assertEquals("CANCELLED", received.last().terminalReason)
    }

    @Test
    fun staleIdleWithErrorRequestsErrorTerminal() {
        val decision = decideDrainState(NATIVE_STATE_IDLE, errorCode = 404)

        assertEquals(DrainStateAction.TERMINAL, decision.action)
        assertEquals("ERROR", decision.terminalReason)
        assertFalse(decision.waitForPending)
    }

    @Test
    fun syntheticIdleTerminalRequiresExplicitNativeCancellation() {
        val decision = decideDrainState(NATIVE_STATE_IDLE, errorCode = 0)

        assertTrue(decision.requiresNativeCancellation)
        assertTrue(shouldCancelNative(observedTerminal = true, decision = decision))
    }

    @Test
    fun deliveredTrueTerminalsDoNotRequestSyntheticCancellation() {
        val trueTerminalStates = listOf(
            NATIVE_STATE_EOF,
            NATIVE_STATE_CANCELLED,
            NATIVE_STATE_ERROR,
            NATIVE_STATE_MAX_TOKENS,
        )

        for (state in trueTerminalStates) {
            val decision = decideDrainState(state, errorCode = 0)
            assertFalse(shouldCancelNative(observedTerminal = true, decision = decision))
        }
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
    fun closedTerminalSendIsExplicitAndStopsProducer() = runBlocking {
        val producerFinished = CompletableDeferred<Unit>()
        val terminalResult = CompletableDeferred<StreamSendResult>()
        val continuedAfterClose = CompletableDeferred<Unit>()
        val flow = callbackFlow {
            assertEquals(StreamSendResult.SENT, sendChunk(chunk(1)))
            close()
            val result = sendChunk(chunk(0, terminal = true))
            terminalResult.complete(result)
            if (result == StreamSendResult.SENT) {
                continuedAfterClose.complete(Unit)
            }
            producerFinished.complete(Unit)
        }.buffer(generationStreamBufferCapacity(1))

        val received = withTimeout(5_000) { flow.toList() }
        assertEquals(listOf("t1"), received.map { it.text })
        assertEquals(
            StreamSendResult.CLOSED,
            withTimeout(5_000) { terminalResult.await() },
        )
        assertFalse(continuedAfterClose.isCompleted)
        withTimeout(5_000) { producerFinished.await() }
    }

    @Test
    fun unexpectedCloseCauseIsNotSwallowed() = runBlocking {
        val expected = IllegalStateException("downstream failure")
        val producerFailure = CompletableDeferred<Throwable>()
        val flow = callbackFlow {
            close(expected)
            try {
                sendChunk(chunk(1))
            } catch (failure: Throwable) {
                producerFailure.complete(failure)
                throw failure
            }
        }

        withTimeout(5_000) { runCatching { flow.toList() } }
        assertSame(expected, withTimeout(5_000) { producerFailure.await() })
    }

    @Test
    fun cancellationDuringBackpressureCompletesProducerAndCleanup() = runBlocking {
        val firstReceived = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val producerFinished = CompletableDeferred<Unit>()
        val flow = callbackFlow {
            try {
                sendChunk(chunk(1))
                sendChunk(chunk(2))
            } finally {
                withStreamCleanup {
                    delay(10)
                    cleanupFinished.complete(Unit)
                }
                producerFinished.complete(Unit)
            }
        }.buffer(Channel.RENDEZVOUS)

        val collector = launch(Dispatchers.Default) {
            runCatching {
                flow.collect {
                    firstReceived.complete(Unit)
                    awaitCancellation()
                }
            }
        }

        withTimeout(5_000) { firstReceived.await() }
        withTimeout(5_000) { collector.cancelAndJoin() }
        withTimeout(5_000) { cleanupFinished.await() }
        withTimeout(5_000) { producerFinished.await() }
        assertTrue("collector should finish after cancellation", collector.isCompleted)
    }
}
