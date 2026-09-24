package com.prismai.llmhost.agent.tools

import android.content.ContextWrapper
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.ImportState
import com.prismai.llmhost.ModelDownloadState
import com.prismai.llmhost.model.DeviceProfiler
import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.ui.ServiceUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeToolsTest {

    @Test(timeout = 2_000)
    fun setRuntimeSettingsDoesNotReturnBeforeConfirmedApplicationCompletes() = runBlocking {
        val applied = CompletableDeferred<GenerationSettings>()
        val tools = newHarness(onUpdateSettings = { applied.complete(it) })
        val before = GenerationSettings()
        val requested = before.copy(contextLength = before.contextLength + 512)

        val result = tools.setRuntimeSettings(
            AgentToolCall(
                "set_runtime_settings",
                arguments = JSONObject().put("context_length", requested.contextLength),
            ),
            confirmed = true,
        )

        assertTrue(result.success)
        assertEquals(requested.contextLength, applied.await().contextLength)
    }

    private fun newHarness(
        onUpdateSettings: suspend (GenerationSettings) -> Unit,
    ): RuntimeTools = RuntimeTools(
        uiState = ServiceUiState(),
        deviceProfiler = DeviceProfiler(ContextWrapper(null)),
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
}
