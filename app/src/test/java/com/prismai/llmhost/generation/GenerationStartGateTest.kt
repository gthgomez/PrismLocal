package com.prismai.llmhost.generation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStartGateTest {
    @Test
    fun chatTransitionAndGenerationBindingCannotPassEachOther() {
        val gate = GenerationStartGate()
        val transitionEntered = CountDownLatch(1)
        val releaseTransition = CountDownLatch(1)
        val startAttempted = CountDownLatch(1)
        val startAccepted = AtomicBoolean(false)
        val transitionFinished = AtomicBoolean(false)
        val transition = thread {
            gate.runChatTransition {
                transitionEntered.countDown()
                releaseTransition.await(1, TimeUnit.SECONDS)
                transitionFinished.set(true)
                "switched"
            }
        }

        assertTrue(transitionEntered.await(1, TimeUnit.SECONDS))
        val startResult = thread {
            startAttempted.countDown()
            startAccepted.set(gate.beginStart())
        }
        assertTrue(startAttempted.await(1, TimeUnit.SECONDS))
        val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (startResult.state != Thread.State.BLOCKED && System.nanoTime() < blockedDeadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, startResult.state)
        assertFalse(startAccepted.get())
        releaseTransition.countDown()
        transition.join(1_000)
        startResult.join(1_000)

        assertTrue(transitionFinished.get())
        assertTrue(startAccepted.get())
        assertEquals(null, gate.runChatTransition { "must-not-switch" })
        gate.finishStart()
    }
}
