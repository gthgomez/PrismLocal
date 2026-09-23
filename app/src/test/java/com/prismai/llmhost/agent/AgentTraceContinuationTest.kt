package com.prismai.llmhost.agent

import com.prismai.llmhost.ui.ServiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTraceContinuationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun maxTokensKeepsTraceActiveAndAccumulatesContinuationTokens() {
        val (trace, uiState) = newActiveTrace()
        val startTime = trace.activeAgentChainStartTime
        trace.addChainTokens(4)

        trace.recordGenerationTurn(
            terminalReason = "MAX_TOKENS",
            generatedTokens = 11,
            hasToolCall = false,
        )
        trace.recordGenerationTurn(
            terminalReason = "MAX_TOKENS",
            generatedTokens = 7,
            hasToolCall = false,
        )

        assertEquals("original prompt", trace.activeAgentChainPrompt)
        assertEquals(startTime, trace.activeAgentChainStartTime)
        assertEquals(22, trace.activeAgentChainTokens)
        assertNull(uiState.lastAgentTracePath.value)
    }

    @Test
    fun terminalPolicyFinalizesOnlyOnCleanEof() {
        assertEquals(
            AgentTrace.TerminationAction.RESUMABLE,
            AgentTrace.terminationAction("MAX_TOKENS", hasToolCall = false),
        )
        assertEquals(
            AgentTrace.TerminationAction.FINALIZE_SUCCESS,
            AgentTrace.terminationAction("EOF", hasToolCall = false),
        )
        assertEquals(
            AgentTrace.TerminationAction.RESUMABLE,
            AgentTrace.terminationAction("EOF", hasToolCall = true),
        )
        listOf("ERROR", "CANCELLED", "FUTURE_REASON").forEach { reason ->
            assertEquals(
                "reason=$reason",
                AgentTrace.TerminationAction.FINALIZE_FAILURE,
                AgentTrace.terminationAction(reason, hasToolCall = false),
            )
        }
    }

    @Test
    fun toolCallTurnAccumulatesTokensWithoutFinalizing() {
        val (trace, uiState) = newActiveTrace()
        val startTime = trace.activeAgentChainStartTime

        trace.recordGenerationTurn(
            terminalReason = "EOF",
            generatedTokens = 9,
            hasToolCall = true,
        )

        assertEquals(startTime, trace.activeAgentChainStartTime)
        assertEquals(9, trace.activeAgentChainTokens)
        assertNull(uiState.lastAgentTracePath.value)
    }

    private fun newActiveTrace(
        startTime: Long = System.currentTimeMillis(),
    ): Pair<AgentTrace, ServiceUiState> {
        val uiState = ServiceUiState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        val trace = AgentTrace(uiState, tempFolder.newFolder(), scope)
        trace.activeAgentChainPrompt = "original prompt"
        trace.activeAgentChainStartTime = startTime
        return trace to uiState
    }
}
