package com.prismai.llmhost.generation

import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.tools.AgentToolProtocol
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

    @Test
    fun memoryContextCanPushATurnOverTheLimit() {
        // Pre-flight parity: the orchestrator passes the retrieved memory context
        // into the same fit test, so the screen must too. A turn that fits alone
        // can be refused once the memory the orchestrator adds is counted.
        assertTrue(
            SendAcceptance.evaluate(
                currentModel = "m",
                contextLength = 300,
                maxTokens = 16,
                prompt = "hello",
            ).accepted
        )
        val withMemory = SendAcceptance.evaluate(
            currentModel = "m",
            contextLength = 300,
            maxTokens = 16,
            prompt = "hello",
            memoryContext = "word ".repeat(300),
        )
        assertFalse(withMemory.accepted)
        assertTrue(withMemory.reason!!.contains("too long for the context window"))
    }

    @Test
    fun agentInstructionBlockCountsTowardTheBudget() {
        // The orchestrator adds the agent instruction block when agents are
        // enabled; forChat must derive the same input so the pre-flight and the
        // refusal agree on agent turns.
        val prompt = "hello"
        val instructionTokens = GenerationBudget.estimateTokens(AgentToolProtocol.instructionBlock())
        assertTrue(instructionTokens > 0)
        val baseMaxTokens = GenerationSettings().maxTokens
        val fitsWithoutInstruction = GenerationBudget.estimateTokens(prompt) +
            GenerationBudget.MESSAGE_TEMPLATE_TOKENS * 2 +
            GenerationBudget.CONTEXT_HEADROOM_TOKENS +
            baseMaxTokens
        // Context that exactly fits the plain turn, one token short once the
        // instruction block is counted.
        val contextLength = fitsWithoutInstruction + instructionTokens - 1

        val plain = SendAcceptance.forChat(
            currentModel = "m",
            settings = GenerationSettings(agentEnabled = false, contextLength = contextLength),
            prompt = prompt,
            memoryContext = "",
        )
        assertTrue(plain.accepted)

        val agent = SendAcceptance.forChat(
            currentModel = "m",
            settings = GenerationSettings(agentEnabled = true, contextLength = contextLength),
            prompt = prompt,
            memoryContext = "",
        )
        assertFalse(agent.accepted)
        assertTrue(agent.reason!!.contains("too long for the context window"))
    }

    @Test
    fun budgetIsSkippedWhenNotEnforced() {
        // Benchmark presets were never budget-checked; skipping the fit test
        // keeps them accepted even for an over-limit prompt.
        val huge = "word ".repeat(200_000)
        assertTrue(
            SendAcceptance.evaluate("m", 512, 128, huge, enforceBudget = false).accepted
        )
        // The model check still applies regardless of the budget exemption.
        assertFalse(
            SendAcceptance.evaluate(null, 512, 128, "hi", enforceBudget = false).accepted
        )
    }
}
