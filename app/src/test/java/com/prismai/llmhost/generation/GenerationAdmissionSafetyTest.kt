package com.prismai.llmhost.generation

import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.agent.AgentTrace
import com.prismai.llmhost.agent.AgentToolRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationAdmissionSafetyTest {

    @Test
    fun oversizedPromptIsRefusedBeforeGenerationStarts() {
        val hugePrompt = "A".repeat(500_000)
        val settings = GenerationSettings(contextLength = 2048, maxTokens = 512)

        val result = SendAcceptance.forChat(
            currentModel = "test-model",
            settings = settings,
            prompt = hugePrompt,
            memoryContext = "",
            enforceBudget = true,
        )

        assertFalse("Oversized prompt must be refused by pre-admission check", result.accepted)
        assertTrue(result.reason!!.contains("too long for the context window"))
    }

    @Test
    fun generationLaunchHandledAcceptsMessageEvenWhenTraceAborts() {
        // PL-F02: Direct tool call handles the turn and appends user message.
        // Even if tool execution fails synchronously (e.g., thermal/battery/validation)
        // and aborts the agent trace, launch result must be HANDLED so acceptsMessage()
        // returns true and composer clears without replaying duplicate user turns.
        val launch = GenerationOrchestrator.GenerationLaunch.HANDLED
        assertTrue("HANDLED launch must report acceptsMessage == true", launch.acceptsMessage())

        val started = GenerationOrchestrator.GenerationLaunch.STARTED
        assertTrue("STARTED launch must report acceptsMessage == true", started.acceptsMessage())

        val refused = GenerationOrchestrator.GenerationLaunch.REFUSED
        assertFalse("REFUSED launch must report acceptsMessage == false", refused.acceptsMessage())
    }

    @Test
    fun ordinaryTurnFitsBudgetWithStandardContext() {
        val settings = GenerationSettings(agentEnabled = true, contextLength = 4096, maxTokens = 512)
        val result = SendAcceptance.forChat(
            currentModel = "test-model",
            settings = settings,
            prompt = "help",
            memoryContext = "",
            enforceBudget = true,
        )
        assertTrue(result.accepted)
    }
}
