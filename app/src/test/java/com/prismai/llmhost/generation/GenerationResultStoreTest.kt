package com.prismai.llmhost.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationResultStoreTest {
    @Test
    fun streamAccumulatorsKeepConcurrentSessionTextSeparate() {
        val firstSession = GenerationOutputAccumulator("first ")
        val secondSession = GenerationOutputAccumulator("second ")

        firstSession.append("tail")
        secondSession.append("result")

        assertEquals("first tail", firstSession.snapshot())
        assertEquals("second result", secondSession.snapshot())
    }

    @Test
    fun priorWaiterReadsItsSessionAfterANewerSessionCompletes() {
        val results = GenerationResultStore()
        results.record(sessionId = 10L, agentChainId = null, output = "first result")
        results.record(sessionId = 11L, agentChainId = null, output = "second result")

        assertEquals("first result", results.outputFor(sessionId = 10L))
        assertEquals("second result", results.outputFor(sessionId = 11L))
        assertNull(results.outputFor(sessionId = 12L))
    }

    @Test
    fun agentWaiterReadsItsLatestOwnedFollowUpOutput() {
        val results = GenerationResultStore()
        results.record(sessionId = 20L, agentChainId = 5L, output = "initial turn")
        results.record(sessionId = 21L, agentChainId = 5L, output = "follow-up turn")
        results.record(sessionId = 22L, agentChainId = 6L, output = "other chain")

        assertEquals("follow-up turn", results.outputFor(sessionId = 20L, agentChainId = 5L))
        assertEquals("other chain", results.outputFor(sessionId = 22L, agentChainId = 6L))
    }

    @Test
    fun lateOldSessionCannotReplaceNewerChainOutput() {
        val results = GenerationResultStore()
        results.record(sessionId = 21L, agentChainId = 5L, output = "follow-up turn")
        results.record(sessionId = 20L, agentChainId = 5L, output = "late old turn")

        assertEquals("follow-up turn", results.outputFor(sessionId = 20L, agentChainId = 5L))
    }

    @Test
    fun completedResultsAreBounded() {
        val results = GenerationResultStore(maxSessionResults = 2, maxChainResults = 1)
        results.record(sessionId = 1L, agentChainId = 1L, output = "one")
        results.record(sessionId = 2L, agentChainId = 1L, output = "two")
        results.record(sessionId = 3L, agentChainId = 2L, output = "three")

        assertNull(results.outputFor(sessionId = 1L))
        assertEquals("three", results.outputFor(sessionId = 3L))
        assertNull(results.outputFor(sessionId = 1L, agentChainId = 1L))
    }

    @Test
    fun activeWaiterRetainsOwnedOutputBeyondHistoryLimit() {
        val results = GenerationResultStore(maxSessionResults = 1, maxChainResults = 1)
        results.record(sessionId = 1L, agentChainId = null, output = "owned output")
        results.retain(sessionId = 1L, agentChainId = null)
        results.record(sessionId = 2L, agentChainId = null, output = "new output")

        assertEquals("owned output", results.outputFor(sessionId = 1L))
        assertEquals("new output", results.outputFor(sessionId = 2L))

        results.release(sessionId = 1L, agentChainId = null)
        assertNull(results.outputFor(sessionId = 1L))
        assertEquals("new output", results.outputFor(sessionId = 2L))
    }
}
