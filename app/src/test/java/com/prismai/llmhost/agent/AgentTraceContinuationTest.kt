package com.prismai.llmhost.agent

import com.prismai.llmhost.ui.ServiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val harness = newActiveTrace()
        val chainId = harness.trace.beginChain("original prompt")
        val startTime = harness.trace.activeAgentChainStartTime
        harness.trace.addChainTokens(4)

        harness.trace.recordGenerationTurn(
            terminalReason = "MAX_TOKENS",
            generatedTokens = 11,
            hasToolCall = false,
            chainId = chainId,
        )
        harness.trace.recordGenerationTurn(
            terminalReason = "MAX_TOKENS",
            generatedTokens = 7,
            hasToolCall = false,
            chainId = chainId,
        )

        assertEquals("original prompt", harness.trace.activeAgentChainPrompt)
        assertEquals(startTime, harness.trace.activeAgentChainStartTime)
        assertEquals(chainId, harness.trace.activeChainId)
        assertEquals(22, harness.trace.activeAgentChainTokens)
        assertTrue(harness.artifacts.tryReceive().isFailure)
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
        val harness = newActiveTrace()
        val chainId = harness.trace.beginChain("original prompt")
        val startTime = harness.trace.activeAgentChainStartTime

        harness.trace.recordGenerationTurn(
            terminalReason = "EOF",
            generatedTokens = 9,
            hasToolCall = true,
            chainId = chainId,
        )

        assertEquals(startTime, harness.trace.activeAgentChainStartTime)
        assertEquals(chainId, harness.trace.activeChainId)
        assertEquals(9, harness.trace.activeAgentChainTokens)
        assertTrue(harness.artifacts.tryReceive().isFailure)
    }

    @Test
    fun staleCancellationCannotFinalizeNewChain() = runBlocking {
        val harness = newActiveTrace()
        val oldChainId = harness.trace.beginChain("old prompt", startTime = 1L)
        val newChainId = harness.trace.beginChain("new prompt", startTime = 2L)

        assertFalse(harness.trace.finalizeOwnedTrace(oldChainId, abortReason = "cancelled"))
        assertEquals(newChainId, harness.trace.activeChainId)

        assertTrue(harness.trace.finalizeOwnedTrace(newChainId, abortReason = "cancelled"))
        val artifact = withTimeout(2_000) { harness.artifacts.receive() }
        assertEquals("new prompt", artifact.getString("prompt"))
        assertFalse(artifact.getBoolean("success"))
        assertEquals("cancelled", artifact.getString("abort_reason"))
    }

    @Test
    fun continuationPreservationSuppressesStaleFinalizationUntilReleased() = runBlocking {
        val harness = newActiveTrace()
        val chainId = harness.trace.beginChain("continuation chain")

        assertTrue(harness.trace.preserveChainForCancellation(chainId))
        assertFalse(harness.trace.finalizeStaleOwnedTrace(chainId, "stale completion"))
        assertEquals(chainId, harness.trace.activeChainId)

        assertTrue(harness.trace.releaseChainPreservation(chainId))
        assertTrue(harness.trace.finalizeStaleOwnedTrace(chainId, "stale completion"))
        val artifact = withTimeout(2_000) { harness.artifacts.receive() }
        assertFalse(artifact.getBoolean("success"))
        assertEquals("stale completion", artifact.getString("abort_reason"))
    }

    @Test
    fun persistedArtifactIncludesTotalChainTokens() = runBlocking {
        val harness = newActiveTrace()
        val chainId = harness.trace.beginChain("prompt")
        harness.trace.addChainTokens(12)

        assertTrue(harness.trace.finalizeOwnedTrace(chainId, success = true))
        val artifact = withTimeout(2_000) { harness.artifacts.receive() }

        assertEquals(12, artifact.getInt("total_tokens"))
        assertTrue(artifact.getBoolean("success"))
    }

    @Test
    fun canceledPersistenceScopeStillWritesArtifact() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        scope.cancel()
        val artifacts = Channel<JSONObject>(Channel.UNLIMITED)
        val trace = AgentTrace(
            uiState = ServiceUiState(),
            filesDir = tempFolder.newFolder(),
            scope = scope,
            writeArtifact = { _, json -> artifacts.trySend(json) },
        )
        val chainId = trace.beginChain("cancelled chain")

        assertTrue(trace.finalizeOwnedTrace(chainId, abortReason = "service destroy"))
        val artifact = withTimeout(2_000) { artifacts.receive() }
        assertEquals("service destroy", artifact.getString("abort_reason"))
        assertFalse(artifact.getBoolean("success"))
    }

    private fun newActiveTrace(): TraceHarness {
        val uiState = ServiceUiState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        val artifacts = Channel<JSONObject>(Channel.UNLIMITED)
        val trace = AgentTrace(
            uiState = uiState,
            filesDir = tempFolder.newFolder(),
            scope = scope,
            writeArtifact = { _, json -> artifacts.trySend(json) },
        )
        return TraceHarness(trace, uiState, artifacts)
    }

    private data class TraceHarness(
        val trace: AgentTrace,
        val uiState: ServiceUiState,
        val artifacts: Channel<JSONObject>,
    )
}
