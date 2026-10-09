package com.prismai.llmhost.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequentialImportQueueTest {

    @Test
    fun queueImportsEveryUriInOrder() = runBlocking {
        val done = mutableListOf<String>()
        val queue = SequentialImportQueue.simple { uri -> done += uri }
        queue.enqueueAll(listOf("a", "b", "c"))
        queue.drain()
        assertEquals(listOf("a", "b", "c"), done)
    }

    @Test
    fun queueContinuesAfterAFailure() = runBlocking {
        val done = mutableListOf<String>()
        val queue = SequentialImportQueue.simple { uri ->
            if (uri == "b") error("boom")
            done += uri
        }
        queue.enqueueAll(listOf("a", "b", "c"))
        queue.drain()
        assertEquals("a failed import must not abort the rest", listOf("a", "c"), done)
    }

    @Test
    fun queueIsNotConcurrent() = runBlocking {
        var inFlight = 0
        var maxConcurrent = 0
        // The import suspends, so this actually exercises sequencing: if drain()
        // started the next import without awaiting the previous one, all three
        // would be in flight together and maxConcurrent would be 3.
        val queue = SequentialImportQueue.simple {
            inFlight++
            maxConcurrent = maxOf(maxConcurrent, inFlight)
            delay(20)
            inFlight--
        }
        queue.enqueueAll(listOf("a", "b", "c"))
        queue.drain()
        assertEquals("ModelImportManager is single-flight", 1, maxConcurrent)
    }

    @Test
    fun drainPropagatesCancellationInsteadOfSwallowingIt() = runBlocking {
        val processed = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val queue = SequentialImportQueue.simple { uri ->
            processed += uri
            gate.await() // suspend until cancelled; never completes
        }
        queue.enqueueAll(listOf("a", "b", "c"))

        var thrown: Throwable? = null
        val job = launch {
            try {
                queue.drain()
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }

        // Wait until the first import is actually in flight, then cancel.
        while (processed.isEmpty()) yield()
        job.cancelAndJoin()

        assertEquals(
            "cancelled drain must not continue to the remaining imports",
            listOf("a"),
            processed,
        )
        assertTrue(
            "drain() must rethrow CancellationException, not swallow it",
            thrown is CancellationException,
        )
    }

    @Test
    fun cancellingTheBatchStopsTheLoopWhileAwaitingAChildJob() = runBlocking {
        // Models the service wiring: importOne() joins a separate per-file Job
        // (ModelImportManager.importModel returns one). Cancelling the batch's
        // coroutine must stop drain() from starting the next file even though
        // the joined child is a separate job.
        val processed = mutableListOf<String>()
        val childJobs = mutableListOf<kotlinx.coroutines.Job>()
        val queue = SequentialImportQueue.simple { uri ->
            processed += uri
            val child = launch { awaitCancellation() }
            childJobs += child
            child.join()
        }
        queue.enqueueAll(listOf("a", "b", "c"))

        val batch = launch { queue.drain() }
        while (processed.isEmpty()) yield()
        batch.cancelAndJoin()
        childJobs.forEach { it.cancelAndJoin() }

        assertEquals(
            "cancelling the batch must not start the next file",
            listOf("a"),
            processed,
        )
    }

    @Test
    fun emptyQueueIsANoOp() = runBlocking {
        val done = mutableListOf<String>()
        val queue = SequentialImportQueue.simple { done += it }
        queue.enqueueAll(emptyList())
        queue.drain()
        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun isEmptyReflectsPendingWork() {
        val queue = SequentialImportQueue.simple { }
        assertTrue("a fresh queue is empty", queue.isEmpty())
        queue.enqueueAll(listOf("a", "b"))
        assertTrue("enqueued URIs make the queue non-empty", !queue.isEmpty())
    }

    @Test
    fun clearDropsEverythingPending() = runBlocking {
        val done = mutableListOf<String>()
        val queue = SequentialImportQueue.simple { done += it }
        queue.enqueueAll(listOf("a", "b", "c"))

        queue.clear()

        assertTrue("clear() must empty the queue", queue.isEmpty())
        queue.drain()
        assertEquals("cleared URIs must never import", emptyList<String>(), done)
    }

    // --- PL-F17: fatal Error (OutOfMemoryError) must propagate and never be swallowed ---

    @Test(expected = OutOfMemoryError::class)
    fun drainPropagatesOutOfMemoryErrorImmediately() = runBlocking {
        val queue = SequentialImportQueue.simple { uri ->
            if (uri == "b") throw OutOfMemoryError("simulated OOM in import")
        }
        queue.enqueueAll(listOf("a", "b", "c"))
        queue.drain()
    }

    // --- PL-F09: retryable busy preserves the queued item without dropping or consuming it ---

    @Test
    fun retryableBusyRetriesItemWithoutConsumingOrDroppingIt() = runBlocking {
        val attempts = mutableListOf<String>()
        var busyCount = 2
        val queue = SequentialImportQueue { uri ->
            attempts += uri
            if (uri == "a" && busyCount > 0) {
                busyCount--
                ImportDispatchOutcome.RetryableBusy
            } else {
                ImportDispatchOutcome.Success
            }
        }
        queue.enqueueAll(listOf("a", "b"))
        queue.drain(retryDelayMs = 1L)

        // "a" was attempted 3 times (2 retryable busy + 1 success), then "b" succeeded
        assertEquals(listOf("a", "a", "a", "b"), attempts)
        assertTrue("queue must be empty once all items succeed", queue.isEmpty())
    }
}
