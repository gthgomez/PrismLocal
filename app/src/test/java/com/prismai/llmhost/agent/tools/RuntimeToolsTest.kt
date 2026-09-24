package com.prismai.llmhost.agent.tools

import android.content.ContextWrapper
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.ImportState
import com.prismai.llmhost.ModelDownloadState
import com.prismai.llmhost.model.DeviceProfiler
import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.tools.AgentToolErrorCode
import com.prismai.llmhost.ui.ServiceUiState
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeToolsTest {

    @Test(timeout = 2_000)
    fun setRuntimeSettingsDoesNotReturnBeforeConfirmedApplicationCompletes() = runBlocking {
        val callbackStarted = CompletableDeferred<Unit>()
        val releaseApplication = CompletableDeferred<Unit>()
        val applied = CompletableDeferred<GenerationSettings>()
        val tools = newHarness(
            onUpdateSettings = {
                callbackStarted.complete(Unit)
                releaseApplication.await()
                applied.complete(it)
                true
            },
        )
        val before = GenerationSettings()
        val requested = before.copy(contextLength = before.contextLength + 512)

        val toolJob = async {
            tools.setRuntimeSettings(
                AgentToolCall(
                    "set_runtime_settings",
                    arguments = JSONObject().put("context_length", requested.contextLength),
                ),
                confirmed = true,
            )
        }

        callbackStarted.await()
        try {
            assertFalse(toolJob.isCompleted)
        } finally {
            releaseApplication.complete(Unit)
        }

        val result = toolJob.await()
        assertTrue(result.success)
        assertEquals(requested.contextLength, applied.await().contextLength)
    }

    @Test(timeout = 2_000)
    fun setRuntimeSettingsReportsFailedConfirmedApplication() = runBlocking {
        val tools = newHarness(onUpdateSettings = { false })

        val result = tools.setRuntimeSettings(
            AgentToolCall(
                "set_runtime_settings",
                arguments = JSONObject().put("context_length", GenerationSettings().contextLength + 512),
            ),
            confirmed = true,
        )

        assertFalse(result.success)
        assertEquals(AgentToolErrorCode.FAILED, result.errorCode)
    }

    private fun newHarness(
        onUpdateSettings: suspend (GenerationSettings) -> Boolean,
    ): RuntimeTools = RuntimeTools(
        uiState = ServiceUiState(),
        deviceProfiler = DeviceProfiler(ContextWrapper(null)),
        getCachedProfile = { safeProfile() },
        onUpdateSettings = onUpdateSettings,
        onContinueGeneration = {},
        onCancelGeneration = {},
        onCancelImport = {},
        onRunBenchmarkPreset = {},
        onRunNativePpTgBenchmark = {},
        onRunThreadSweepBenchmark = {},
        getPreviousSettings = { null },
        setPreviousSettings = {},
        activeOperationJson = { JSONObject() },
        importJobIsActive = { false },
        importState = { ImportState.Idle },
        modelDownloadState = { ModelDownloadState.Idle },
    )

    private fun safeProfile(): DeviceCapabilityProfile = DeviceCapabilityProfile(
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        availableRamBytes = 4L * 1024 * 1024 * 1024,
        lowMemory = false,
        cpuCoreCount = 4,
        androidSdk = 35,
        abis = listOf("arm64-v8a"),
        storageFreeBytes = 16L * 1024 * 1024 * 1024,
        batteryPercent = 100,
        isCharging = false,
        thermalStatus = "none",
        memoryClassMb = 256,
        largeMemoryClassMb = 512,
        appHeapMaxBytes = 512L * 1024 * 1024,
    )
}
