package com.prismai.llmhost.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
        val queue = SequentialImportQueue { uri -> done += uri }
        queue.enqueueAll(listOf("a", "b", "c"))
        queue.drain()
        assertEquals(listOf("a", "b", "c"), done)
    }

    @Test
    fun queueContinuesAfterAFailure() = runBlocking {
        val done = mutableListOf<String>()
        val queue = SequentialImportQueue { uri ->
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
        val queue = SequentialImportQueue {
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
        val queue = SequentialImportQueue { uri ->
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
    fun emptyQueueIsANoOp() = runBlocking {
        val done = mutableListOf<String>()
        val queue = SequentialImportQueue { done += it }
        queue.enqueueAll(emptyList())
        queue.drain()
        assertEquals(emptyList<String>(), done)
    }
}
