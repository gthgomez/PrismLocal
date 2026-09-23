package com.prismai.llmhost.agent

import android.content.SharedPreferences
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.TranscriptRole
import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.tools.AgentToolResult
import com.prismai.llmhost.ui.ServiceUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AgentToolRouterTraceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun rejectedToolFinalizesOwnedTraceWithReason() = runBlocking {
        val harness = newHarness()
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall("invented_tool")

        harness.router.handleToolCall(call, "prompt", depth = 0, chainId = chainId)
        val artifact = harness.awaitArtifact()

        assertFalse(artifact.getBoolean("success"))
        assertTrue(artifact.getString("abort_reason").contains("rejected", ignoreCase = true))
    }

    @Test
    fun restrictedToolFinalizesOwnedTraceWithReason() = runBlocking {
        val harness = newHarness()
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall(
            name = "export_chat",
            arguments = JSONObject().put("format", "markdown"),
        )

        harness.router.handleToolCall(call, "prompt", depth = 0, chainId = chainId)
        val artifact = harness.awaitArtifact()

        assertFalse(artifact.getBoolean("success"))
        assertTrue(artifact.getString("abort_reason").contains("restricted", ignoreCase = true))
    }

    @Test
    fun safeToolAtIterationLimitFinalizesWithoutFollowUp() = runBlocking {
        val followUps = AtomicInteger(0)
        val harness = newHarness(followUps = followUps)
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall("get_model_status")

        harness.router.handleToolCall(call, "prompt", depth = 4, chainId = chainId)
        val artifact = harness.awaitArtifact()

        assertFalse(artifact.getBoolean("success"))
        assertTrue(artifact.getString("abort_reason").contains("stopped", ignoreCase = true))
        assertEquals(0, followUps.get())
    }

    @Test
    fun cancelledConfirmationFinalizesOwnedTrace() = runBlocking {
        val harness = newHarness()
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall("restore_previous_runtime_settings")

        harness.router.handleToolCall(call, "prompt", depth = 0, chainId = chainId)
        assertNotNull(harness.confirmation.pendingCall)
        harness.router.cancelPendingTool(call, chainId, "Tool confirmation cancelled")
        val artifact = harness.awaitArtifact()

        assertFalse(artifact.getBoolean("success"))
        assertEquals("Tool confirmation cancelled", artifact.getString("abort_reason"))
    }

    @Test
    fun failedOrNonContinuingConfirmationFinalizesOwnedTrace() = runBlocking {
        val harness = newHarness()
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall(
            name = "rename_current_chat",
            arguments = JSONObject().put("title", "renamed"),
        )
        val failed = AgentToolResult(
            call = call,
            success = false,
            summary = "tool failed",
        )

        assertFalse(
            harness.router.completeConfirmedTool(
                call = call,
                result = failed,
                depth = 0,
                maxIterations = 5,
                chainId = chainId,
            ),
        )
        val artifact = harness.awaitArtifact()
        assertFalse(artifact.getBoolean("success"))
        assertTrue(artifact.getString("abort_reason").contains("failed", ignoreCase = true))
    }

    @Test
    fun successfulContinuingConfirmationLeavesChainActive() = runBlocking {
        val harness = newHarness()
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall(
            name = "rename_current_chat",
            arguments = JSONObject().put("title", "renamed"),
        )
        val succeeded = AgentToolResult(
            call = call,
            success = true,
            summary = "renamed",
        )

        assertTrue(
            harness.router.completeConfirmedTool(
                call = call,
                result = succeeded,
                depth = 0,
                maxIterations = 5,
                chainId = chainId,
            ),
        )
        assertEquals(chainId, harness.trace.activeChainId)
        assertTrue(harness.artifacts.tryReceive().isFailure)
    }

    @Test
    fun stalePendingCancellationCannotFinalizeNewChain() = runBlocking {
        val harness = newHarness()
        val oldChainId = harness.trace.beginChain("old prompt")
        val call = AgentToolCall("restore_previous_runtime_settings")
        harness.router.handleToolCall(call, "old prompt", depth = 0, chainId = oldChainId)

        val newChainId = harness.trace.beginChain("new prompt")
        harness.router.cancelPendingTool(call, oldChainId, "stale cancellation")

        assertEquals(newChainId, harness.trace.activeChainId)
        assertTrue(harness.artifacts.tryReceive().isFailure)
    }

    @Test
    fun staleAsyncToolCallbackCannotContaminateNewChain() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val followUps = AtomicInteger(0)
        val harness = newHarness(
            followUps = followUps,
            executeTool = { _, _ ->
                gate.await()
                AgentToolResult(
                    call = AgentToolCall("get_model_status"),
                    success = true,
                    summary = "late result",
                )
            },
        )
        val oldChainId = harness.trace.beginChain("old prompt")
        harness.router.handleToolCall(
            AgentToolCall("get_model_status"),
            "old prompt",
            depth = 0,
            chainId = oldChainId,
        )

        val newChainId = harness.trace.beginChain("new prompt")
        gate.complete(Unit)
        delay(20)

        assertEquals(newChainId, harness.trace.activeChainId)
        assertEquals(0, followUps.get())
        assertTrue(harness.artifacts.tryReceive().isFailure)
    }

    private fun newHarness(
        followUps: AtomicInteger = AtomicInteger(0),
        executeTool: suspend (AgentToolCall, Boolean) -> AgentToolResult = { call, _ ->
            AgentToolResult(call = call, success = true, summary = "ok")
        },
    ): RouterHarness {
        val uiState = ServiceUiState()
        uiState._generationSettings.value = GenerationSettings(maxAgentIterations = 5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        val artifacts = Channel<JSONObject>(Channel.UNLIMITED)
        val trace = AgentTrace(
            uiState = uiState,
            filesDir = tempFolder.newFolder(),
            scope = scope,
            writeArtifact = { _, json -> artifacts.trySend(json) },
        )
        val confirmation = AgentToolConfirmation(
            uiState = uiState,
            prefs = FakeSharedPreferences(),
            currentChatId = { null },
            pendingActionKey = { "pending_$it" },
            getDeviceProfile = { safeProfile() },
        )
        val transcriptIds = AtomicLong(0L)
        val router = AgentToolRouter(
            uiState = uiState,
            agentTrace = trace,
            confirmation = confirmation,
            getCachedProfile = { safeProfile() },
            clock = { 0L },
            executeTool = executeTool,
            onFollowUp = { _, _, _, _ -> followUps.incrementAndGet() },
            onAppendTranscriptMessage = { _: TranscriptRole, _: String -> transcriptIds.incrementAndGet() },
            onPublishUiEvent = {},
            scope = scope,
        )
        return RouterHarness(trace, confirmation, router, artifacts)
    }

    private suspend fun RouterHarness.awaitArtifact(): JSONObject =
        withTimeout(2_000) { artifacts.receive() }

    private fun safeProfile(): DeviceCapabilityProfile = DeviceCapabilityProfile(
        totalRamBytes = 8_000_000_000L,
        availableRamBytes = 4_000_000_000L,
        lowMemory = false,
        cpuCoreCount = 4,
        androidSdk = 35,
        abis = emptyList(),
        storageFreeBytes = 10_000_000_000L,
        batteryPercent = 80,
        isCharging = false,
        thermalStatus = "none",
        memoryClassMb = 256,
        largeMemoryClassMb = 256,
        appHeapMaxBytes = 256_000_000L,
    )

    private data class RouterHarness(
        val trace: AgentTrace,
        val confirmation: AgentToolConfirmation,
        val router: AgentToolRouter,
        val artifacts: Channel<JSONObject>,
    )
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = values
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removals.forEach(values::remove)
            values.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
