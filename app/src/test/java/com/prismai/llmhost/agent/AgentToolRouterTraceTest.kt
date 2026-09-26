package com.prismai.llmhost.agent

import android.content.SharedPreferences
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.Capability
import com.prismai.llmhost.CapabilityRegistry
import com.prismai.llmhost.TranscriptRole
import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.tools.AgentToolResult
import com.prismai.llmhost.ui.ServiceUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
        val harness = newHarness(capabilityRegistry = agentEnabledRegistry())
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall("restore_previous_runtime_settings")

        harness.router.handleToolCall(call, "prompt", depth = 0, chainId = chainId)
        assertNotNull(harness.confirmation.pendingAuthorization)
        assertEquals(
            harness.confirmation.pendingAuthorization?.token,
            harness.uiState.pendingAgentToolAction.value?.id,
        )
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
    fun safeToolJobIsTrackedUntilItFinishes() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val harness = newHarness(
            executeTool = { _, _ ->
                gate.await()
                AgentToolResult(
                    call = AgentToolCall("get_model_status"),
                    success = true,
                    summary = "done",
                )
            },
        )
        val chainId = harness.trace.beginChain("prompt")
        harness.router.handleToolCall(
            AgentToolCall("get_model_status"),
            "prompt",
            depth = 0,
            chainId = chainId,
        )

        assertTrue(harness.router.hasActiveToolJobs(chainId))
        gate.complete(Unit)
        withTimeout(2_000) {
            while (harness.router.hasActiveToolJobs(chainId)) delay(1)
        }
        assertFalse(harness.router.hasActiveToolJobs(chainId))
    }

    @Test
    fun confirmedOwnedJobIsTrackedPerChain() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val harness = newHarness()
        val chainId = harness.trace.beginChain("confirmed prompt")
        val job = harness.router.launchOwnedToolJob(chainId) {
            gate.await()
        }

        assertTrue(harness.router.hasActiveToolJobs(chainId))
        gate.complete(Unit)
        job.join()
        assertFalse(harness.router.hasActiveToolJobs(chainId))
    }

    @Test
    fun boundedCleanupDoesNotWaitForeverForNonCooperativeJob() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val harness = newHarness()
        val chainId = harness.trace.beginChain("prompt")
        val job = harness.router.launchOwnedToolJob(chainId) {
            withContext(NonCancellable) {
                release.await()
            }
        }
        assertNotNull(harness.router.claimChatTransition(abortReason = "chat switched"))

        val joined = withTimeout(500) {
            harness.router.joinInvalidatedChainBounded(chainId, timeoutMs = 50L)
        }
        assertFalse(joined)
        release.complete(Unit)
        withTimeout(500) { job.join() }
    }

    @Test
    fun safeCallbackRacingChatSwitchIsDiscardedAfterChainInvalidation() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val followUps = AtomicInteger(0)
        val harness = newHarness(
            followUps = followUps,
            executeTool = { _, _ ->
                gate.await()
                AgentToolResult(
                    call = AgentToolCall("get_model_status"),
                    success = true,
                    summary = "late safe result",
                )
            },
        )
        val chainId = harness.trace.beginChain("old chat")
        harness.router.handleToolCall(
            AgentToolCall("get_model_status"),
            "old prompt",
            depth = 0,
            chainId = chainId,
        )
        assertTrue(harness.router.hasActiveToolJobs(chainId))

        val claim = harness.router.claimChatTransition(abortReason = "chat switched")
        assertNotNull(claim)
        harness.switchChat("chat-2")
        gate.complete(Unit)
        withTimeout(2_000) {
            while (harness.router.hasActiveToolJobs(chainId)) delay(1)
        }
        harness.router.joinInvalidatedChain(chainId)

        assertEquals("chat-2", harness.currentChatId())
        assertEquals(0, harness.resultAppends.get())
        assertEquals(0, followUps.get())
        val artifact = harness.awaitArtifact()
        assertEquals("old chat", artifact.getString("prompt"))
    }

    @Test
    fun confirmedCallbackRacingChatSwitchIsDiscardedAfterChainInvalidation() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val followUps = AtomicInteger(0)
        val harness = newHarness(followUps = followUps)
        val chainId = harness.trace.beginChain("old chat")
        val job = harness.router.launchConfirmedToolJob(
            chainId = chainId,
            sourceChatId = "chat-1",
        ) {
            gate.await()
            if (harness.router.isCurrentOwner(chainId, "chat-1")) {
                harness.router.appendOwnedToolResult(
                    chainId = chainId,
                    sourceChatId = "chat-1",
                    result = AgentToolResult(
                        call = AgentToolCall("rename_current_chat"),
                        success = true,
                        summary = "late confirmed result",
                    ),
                )
                followUps.incrementAndGet()
            }
        }
        assertTrue(harness.router.hasActiveToolJobs(chainId))

        val claim = harness.router.claimChatTransition(abortReason = "chat switched")
        assertNotNull(claim)
        harness.switchChat("chat-2")
        gate.complete(Unit)
        harness.router.joinInvalidatedChain(chainId)
        job.join()

        assertEquals("chat-2", harness.currentChatId())
        assertEquals(0, harness.resultAppends.get())
        assertEquals(0, followUps.get())
    }

    @Test
    fun pendingConfirmationUsesOpaqueTokenAndRejectsStaleConsumption() = runBlocking {
        val harness = newHarness(capabilityRegistry = agentEnabledRegistry())
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall("switch_model", JSONObject().put("model_id", "next-model"))

        val authorization = harness.confirmation.stagePending(
            call = call,
            originalPrompt = "prompt",
            depth = 0,
            chainId = chainId,
            sourceChatId = "chat-1",
        )

        assertNotNull(authorization)
        assertTrue(authorization!!.token.isNotBlank())
        assertNull(harness.confirmation.consumePending("stale-token"))
        assertNotNull(harness.confirmation.consumePending(authorization.token))
        assertNull(harness.confirmation.consumePending(authorization.token))
        harness.confirmation.clearConsumedAuthorization(authorization)
        val newer = harness.confirmation.stagePending(
            call = call,
            originalPrompt = "new prompt",
            depth = 0,
            chainId = chainId,
            sourceChatId = "chat-1",
        )
        assertNotNull(newer)
        assertEquals(newer!!.token, harness.confirmation.pendingAuthorization?.token)
        harness.confirmation.clearMemory()
        assertFalse(
            harness.confirmation.isAuthorizationCurrent(
                authorization,
                activeChainId = chainId,
                activeChatId = "chat-2",
            ),
        )

        val staleAuthorization = harness.confirmation.stagePending(
            call = call,
            originalPrompt = "prompt",
            depth = 0,
            chainId = chainId,
            sourceChatId = "chat-1",
        )
        assertNotNull(staleAuthorization)
        harness.switchChat("chat-2")
        assertNull(
            harness.confirmation.consumePendingAndRevalidate(
                token = staleAuthorization!!.token,
                activeChainId = chainId,
                activeChatId = "chat-2",
            ),
        )
        assertNull(harness.confirmation.pendingAuthorization)
    }

    @Test
    fun capabilityRevocationBetweenConsumptionAndDispatchRejectsTheOperation() {
        val policy = CapabilityRegistry().apply { setAgentModeEnabled(true) }
        val harness = newHarness(capabilityRegistry = policy)
        val chainId = harness.trace.beginChain("download")
        val authorization = harness.confirmation.stagePending(
            call = AgentToolCall("download_model", JSONObject().put("entry_id", "small-model")),
            originalPrompt = "download",
            depth = 0,
            chainId = chainId,
            sourceChatId = "chat-1",
        )
        assertNotNull(authorization)
        val staged = authorization!!
        val consumed = harness.confirmation.consumePendingAndRevalidate(
            token = staged.token,
            activeChainId = chainId,
            activeChatId = "chat-1",
        )
        assertNotNull(consumed)

        policy.setAgentModeEnabled(false)

        assertFalse(harness.confirmation.tryBeginDispatch(consumed!!))
    }

    @Test
    fun agentDisablementBetweenConsumptionAndDispatchRejectsStillGrantedCapability() {
        val policy = CapabilityRegistry().apply { setAgentModeEnabled(true) }
        val harness = newHarness(capabilityRegistry = policy)
        val chainId = harness.trace.beginChain("rename chat")
        val authorization = harness.confirmation.stagePending(
            call = AgentToolCall("rename_current_chat", JSONObject().put("title", "renamed")),
            originalPrompt = "rename this chat",
            depth = 0,
            chainId = chainId,
            sourceChatId = "chat-1",
        )
        assertNotNull(authorization)
        val consumed = harness.confirmation.consumePendingAndRevalidate(
            token = authorization!!.token,
            activeChainId = chainId,
            activeChatId = "chat-1",
        )
        assertNotNull(consumed)
        assertTrue(policy.check(setOf(Capability.CHAT_MANAGE)).granted)

        policy.setAgentModeEnabled(false)

        assertFalse(harness.confirmation.tryBeginDispatch(consumed!!))
        assertTrue(policy.check(setOf(Capability.CHAT_MANAGE)).granted)
    }

    @Test
    fun agentDisablementInvalidatesPendingConfirmationBeforeConsumption() {
        val policy = CapabilityRegistry().apply { setAgentModeEnabled(true) }
        val harness = newHarness(capabilityRegistry = policy)
        val chainId = harness.trace.beginChain("download")
        val authorization = harness.confirmation.stagePending(
            call = AgentToolCall("download_model", JSONObject().put("entry_id", "small-model")),
            originalPrompt = "download",
            depth = 0,
            chainId = chainId,
            sourceChatId = "chat-1",
        )!!

        policy.setAgentModeEnabled(false)

        assertNull(
            harness.confirmation.consumePendingAndRevalidate(
                token = authorization.token,
                activeChainId = chainId,
                activeChatId = "chat-1",
            ),
        )
    }

    @Test
    fun pendingConfirmationRejectsNullChainOwner() = runBlocking {
        val harness = newHarness()
        val call = AgentToolCall("switch_model", JSONObject().put("model_id", "next-model"))

        assertNull(
            harness.confirmation.stagePending(
                call = call,
                originalPrompt = "prompt",
                depth = 0,
                chainId = null,
                sourceChatId = "chat-1",
            ),
        )
        assertNull(harness.confirmation.pendingAuthorization)
    }

    @Test
    fun staleTransitionTokenCannotClaimNewConfirmation() = runBlocking {
        val harness = newHarness(capabilityRegistry = agentEnabledRegistry())
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall("restore_previous_runtime_settings")
        harness.router.handleToolCall(call, "prompt", depth = 0, chainId = chainId)
        val token = harness.confirmation.pendingAuthorization?.token

        assertNotNull(token)
        assertNull(
            harness.router.claimChatTransition(
                expectedToken = "stale-token",
                abortReason = "chat switched",
            ),
        )
        assertEquals(chainId, harness.trace.activeChainId)

        val claim = harness.router.claimChatTransition(
            expectedToken = token,
            abortReason = "chat switched",
        )
        assertNotNull(claim)
        harness.router.joinInvalidatedChain(chainId)
        val artifact = harness.awaitArtifact()
        assertEquals("chat switched", artifact.getString("abort_reason"))
    }

    @Test
    fun successfulConfirmedModelOperationLeavesChainForFollowUp() = runBlocking {
        val harness = newHarness()
        val chainId = harness.trace.beginChain("model switch")
        assertTrue(harness.trace.preserveChainForCancellation(chainId))
        assertFalse(harness.trace.finalizeStaleOwnedTrace(chainId, "model switch setup"))
        assertTrue(harness.trace.releaseChainPreservation(chainId))

        val call = AgentToolCall(
            name = "switch_model",
            arguments = JSONObject().put("model_id", "next-model"),
        )
        val result = AgentToolResult(call = call, success = true, summary = "switched")
        assertTrue(
            harness.router.completeConfirmedTool(
                call = call,
                result = result,
                depth = 0,
                maxIterations = 5,
                chainId = chainId,
            ),
        )
        assertEquals(chainId, harness.trace.activeChainId)
        assertTrue(harness.artifacts.tryReceive().isFailure)
    }

    @Test
    fun failedConfirmedModelOperationFinalizesAfterPreservation() = runBlocking {
        val harness = newHarness()
        val chainId = harness.trace.beginChain("model delete")
        assertTrue(harness.trace.preserveChainForCancellation(chainId))
        assertTrue(harness.trace.releaseChainPreservation(chainId))

        val call = AgentToolCall(
            name = "delete_model",
            arguments = JSONObject().put("model_id", "old-model"),
        )
        val result = AgentToolResult(call = call, success = false, summary = "delete failed")
        assertFalse(
            harness.router.completeConfirmedTool(
                call = call,
                result = result,
                depth = 0,
                maxIterations = 5,
                chainId = chainId,
            ),
        )
        val artifact = harness.awaitArtifact()
        assertFalse(artifact.getBoolean("success"))
    }

    @Test
    fun successfulNonContinuingConfirmationFinalizesWithTerminalReason() = runBlocking {
        val harness = newHarness()
        val chainId = harness.trace.beginChain("prompt")
        val call = AgentToolCall(
            name = "run_benchmark",
            arguments = JSONObject().put("preset_id", "coding"),
        )
        val result = AgentToolResult(call = call, success = true, summary = "queued")
        assertFalse(
            harness.router.completeConfirmedTool(
                call = call,
                result = result,
                depth = 0,
                maxIterations = 5,
                chainId = chainId,
            ),
        )
        val artifact = harness.awaitArtifact()
        assertTrue(artifact.getBoolean("success"))
        assertTrue(artifact.getString("abort_reason").contains("follow-up", ignoreCase = true))
    }

    @Test
    fun restoreDiscardsPersistedConfirmationWithoutChainOwnership() {
        val prefs = FakeSharedPreferences()
        val confirmation = AgentToolConfirmation(
            uiState = ServiceUiState(),
            prefs = prefs,
            currentChatId = { "chat-1" },
            pendingActionKey = { "pending_$it" },
            getDeviceProfile = { safeProfile() },
        )
        prefs.edit().putString(
            "pending_chat-1",
            """{"chatId":"chat-1","toolName":"restore_previous_runtime_settings","arguments":"{}","originalPrompt":"prompt","depth":0}""",
        ).commit()

        assertFalse(confirmation.restore())
        assertNull(prefs.getString("pending_chat-1", null))
        assertNull(confirmation.pendingAuthorization)
    }

    @Test
    fun clearingPreferenceWithoutPendingDoesNotCommitOnCallerThread() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("pending_chat-1", "stale").commit()
        prefs.resetCommitCount()
        val confirmation = AgentToolConfirmation(
            uiState = ServiceUiState(),
            prefs = prefs,
            currentChatId = { "chat-1" },
            pendingActionKey = { "pending_$it" },
            getDeviceProfile = { safeProfile() },
        )

        confirmation.clearPersistedForChat("chat-1")

        assertEquals(0, prefs.commitCount)
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
        capabilityRegistry: CapabilityRegistry = CapabilityRegistry(),
        executeTool: suspend (AgentToolCall, Boolean) -> AgentToolResult = { call, _ ->
            AgentToolResult(call = call, success = true, summary = "ok")
        },
    ): RouterHarness {
        val uiState = ServiceUiState()
        uiState._generationSettings.value = GenerationSettings(maxAgentIterations = 5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        val artifacts = Channel<JSONObject>(Channel.UNLIMITED)
        val currentChatId = AtomicReference("chat-1")
        val resultAppends = AtomicInteger(0)
        val trace = AgentTrace(
            uiState = uiState,
            filesDir = tempFolder.newFolder(),
            scope = scope,
            rawContentOptIn = true,
            writeArtifact = { _, json -> artifacts.trySend(json) },
        )
        val confirmation = AgentToolConfirmation(
            uiState = uiState,
            prefs = FakeSharedPreferences(),
            currentChatId = { currentChatId.get() },
            pendingActionKey = { "pending_$it" },
            getDeviceProfile = { safeProfile() },
            capabilityRegistry = capabilityRegistry,
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
            onAppendTranscriptMessage = { role: TranscriptRole, text: String ->
                transcriptIds.incrementAndGet()
                if (role == TranscriptRole.TOOL && text.contains("status\":\"done\"")) {
                    resultAppends.incrementAndGet()
                }
                transcriptIds.get()
            },
            onPublishUiEvent = {},
            scope = scope,
            currentChatId = { currentChatId.get() },
        )
        return RouterHarness(
            uiState = uiState,
            trace = trace,
            confirmation = confirmation,
            router = router,
            artifacts = artifacts,
            currentChatId = { currentChatId.get() },
            switchChat = { chatId -> currentChatId.set(chatId) },
            resultAppends = resultAppends,
            capabilityRegistry = capabilityRegistry,
        )
    }

    private fun agentEnabledRegistry() = CapabilityRegistry().apply {
        setAgentModeEnabled(true)
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
        val uiState: ServiceUiState,
        val trace: AgentTrace,
        val confirmation: AgentToolConfirmation,
        val router: AgentToolRouter,
        val artifacts: Channel<JSONObject>,
        val currentChatId: () -> String?,
        val switchChat: (String) -> Unit,
        val resultAppends: AtomicInteger,
        val capabilityRegistry: CapabilityRegistry,
    )
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    var commitCount: Int = 0
        private set

    fun resetCommitCount() {
        commitCount = 0
    }

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
            commitCount++
            applyPending()
            return true
        }

        override fun apply() {
            applyPending()
        }

        private fun applyPending() {
            if (clearAll) values.clear()
            removals.forEach(values::remove)
            values.putAll(pending)
        }
    }
}
