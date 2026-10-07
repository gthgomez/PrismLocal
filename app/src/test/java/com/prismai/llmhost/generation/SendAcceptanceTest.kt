package com.prismai.llmhost.generation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendAcceptanceTest {

    @Test
    fun noModelSelected_isRefused() {
        assertFalse(
            SendAcceptance.evaluate(currentModel = null, contextLength = 4096, maxTokens = 512, prompt = "hi")
                .accepted
        )
    }

    @Test
    fun promptTooLongForContext_isRefused() {
        val huge = "word ".repeat(200_000)
        val result = SendAcceptance.evaluate(
            currentModel = "some-model",
            contextLength = 512,
            maxTokens = 128,
            prompt = huge,
        )
        assertFalse(result.accepted)
        assertTrue(result.reason!!.contains("context window"))
    }

    @Test
    fun ordinaryTurn_isAccepted() {
        val result = SendAcceptance.evaluate(
            currentModel = "some-model",
            contextLength = 4096,
            maxTokens = 512,
            prompt = "hello",
        )
        assertTrue(result.accepted)
        assertTrue(result.reason == null)
    }

    @Test
    fun refusalMessages_matchTheOrchestrator() {
        // The screen must show what the orchestrator actually refuses with,
        // or the message contradicts what happens next.
        val noModel = SendAcceptance.evaluate(null, 4096, 512, "hi")
        assertTrue(noModel.reason!!.contains("Select a model"))
        val tooLong = SendAcceptance.evaluate("m", 512, 128, "word ".repeat(200_000))
        assertTrue(tooLong.reason!!.contains("too long for the context window"))
    }
}
