package com.prismai.llmhost.generation

import com.prismai.llmhost.agent.AgentTrace
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenerationFollowUpLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun missingModelAbortsSuppliedChainBeforeFollowUpReturns() = runBlocking {
        val artifacts = Channel<JSONObject>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        val trace = AgentTrace(
            uiState = ServiceUiState(),
            filesDir = tempFolder.newFolder(),
            scope = scope,
            rawContentOptIn = true,
            writeArtifact = { _, json -> artifacts.trySend(json) },
        )
        val chainId = trace.beginChain("original prompt")

        val resolved = GenerationOrchestrator.resolveFollowUpChainOrAbort(
            agentTrace = trace,
            suppliedChainId = chainId,
            modelAvailable = false,
        )

        assertNull(resolved)
        val artifact = withTimeout(2_000) { artifacts.receive() }
        assertFalse(artifact.getBoolean("success"))
        assertEquals("Follow-up aborted: no model selected", artifact.getString("abort_reason"))
    }
}
