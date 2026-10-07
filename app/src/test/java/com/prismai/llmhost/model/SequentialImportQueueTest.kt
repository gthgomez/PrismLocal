package com.prismai.llmhost.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        val queue = SequentialImportQueue {
            inFlight++
            maxConcurrent = maxOf(maxConcurrent, inFlight)
            inFlight--
        }
        queue.enqueueAll(listOf("a", "b", "c"))
        queue.drain()
        assertEquals("ModelImportManager is single-flight", 1, maxConcurrent)
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
