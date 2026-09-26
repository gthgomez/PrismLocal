package com.prismai.llmhost.generation

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GenerationSessionWaitTest {

    @Test
    fun returnsImmediatelyWhenIdle() = runBlocking {
        val completed = GenerationSessionWait.awaitSessionIdle(
            isGenerating = { false },
            getJob = { null },
            pollMs = 10L,
            maxWaitMs = 1000L,
        )
        assertTrue(completed)
    }

    @Test
    fun timesOutWhenOwnedJobDoesNotFinishBeforeDeadline() = runBlocking {
        val neverCompletes = Job()
        var timedOut = false

        val completed = withTimeoutOrNull(250L) {
            GenerationSessionWait.awaitSessionIdle(
                isGenerating = { true },
                getJob = { neverCompletes },
                pollMs = 5L,
                maxWaitMs = 30L,
                onTimeout = { timedOut = true },
            )
        } ?: false

        assertFalse("stuck job should produce a bounded timeout", completed)
        assertTrue(timedOut)
        assertTrue(neverCompletes.isActive)
        neverCompletes.cancel()
    }

    @Test
    fun waitsForFirstJobAndFollowUpTurn() = runBlocking {
        val isGenerating = AtomicBoolean(true)
        val currentJob = AtomicReference<Job?>(null)
        val followUpRan = AtomicBoolean(false)

        val job1 = Job()
        currentJob.set(job1)
        val job2 = Job()

        launch {
            job1.join()
            currentJob.set(null)
            delay(10)
            currentJob.set(job2)
            followUpRan.set(true)
            delay(10)
            job2.complete()
            currentJob.set(null)
            isGenerating.set(false)
        }

        launch {
            delay(10)
            job1.complete()
        }

        GenerationSessionWait.awaitSessionIdle(
            isGenerating = { isGenerating.get() },
            getJob = { currentJob.get() },
            pollMs = 10L,
            maxWaitMs = 1000L,
        )

        assertTrue("Follow up generation turn should have completed before await finished", followUpRan.get())
        assertEquals(false, isGenerating.get())
    }

    @Test
    fun resumableMaxTokensTraceIsIdleWhileRemainingAvailableForUserContinuation() {
        val inputs = GenerationIdleInputs(
            generationRunning = false,
            generationJobActive = false,
            agentToolActive = false,
            confirmationPending = false,
            agentChainActive = true,
            continuationAvailable = true,
        )

        assertFalse(GenerationIdlePolicy.shouldWait(inputs))
        assertFalse(GenerationIdlePolicy.shouldAbortUnresumableChain(inputs))
    }

    @Test
    fun agentDisabledTraceRequestsAbortInsteadOfWaitingForContinuation() {
        val inputs = GenerationIdleInputs(
            generationRunning = false,
            generationJobActive = false,
            agentToolActive = false,
            confirmationPending = false,
            agentChainActive = true,
            continuationAvailable = false,
        )

        assertTrue(GenerationIdlePolicy.shouldAbortUnresumableChain(inputs))
        assertTrue(GenerationIdlePolicy.shouldWait(inputs))
    }

    @Test
    fun waitsThroughToolExecutionLatency() = runBlocking {
        val isChainActive = AtomicBoolean(true)
        val isGenerating = AtomicBoolean(true)
        val currentJob = AtomicReference<Job?>(null)
        val followUpRan = AtomicBoolean(false)

        val job1 = Job()
        currentJob.set(job1)
        val job2 = Job()

        launch {
            job1.join()
            // Generation 1 ends, tool execution begins (job is null, isGenerating is false, but chain is active)
            currentJob.set(null)
            isGenerating.set(false)
            delay(100) // 100ms tool execution latency (greater than pollMs)
            currentJob.set(job2)
            isGenerating.set(true)
            followUpRan.set(true)
            delay(20)
            job2.complete()
            currentJob.set(null)
            isGenerating.set(false)
            isChainActive.set(false)
        }

        launch {
            delay(10)
            job1.complete()
        }

        GenerationSessionWait.awaitSessionIdle(
            isGenerating = { isGenerating.get() || isChainActive.get() },
            getJob = { currentJob.get() },
            pollMs = 20L,
            maxWaitMs = 1000L,
        )

        assertTrue("Follow up generation turn should complete despite tool latency", followUpRan.get())
        assertEquals(false, isChainActive.get())
    }
}
