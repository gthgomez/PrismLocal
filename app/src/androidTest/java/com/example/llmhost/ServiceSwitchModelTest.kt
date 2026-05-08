package com.example.llmhost

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServiceSwitchModelTest {
    @Test
    fun switchModelLoadsStagedModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        stageModel(instrumentation, "modelA")

        val service = bindService(targetContext)
        try {
            assertTrue(service.service.switchModel("modelA"))
        } finally {
            targetContext.unbindService(service.connection)
        }
    }

    @Test
    fun serviceImportReportsProgressAndSuccess() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        File(targetContext.getExternalFilesDir("models"), "service-import").deleteRecursively()
        val source = File(targetContext.cacheDir, "service-import.gguf")
        source.writeBytes(validGgufBytes())

        val service = bindService(targetContext)
        try {
            service.service.importModel(Uri.fromFile(source))
            val state = waitForImportTerminal(service.service)
            assertTrue("expected import success, got $state", state is ImportState.Success)
            assertEquals("service-import", (state as ImportState.Success).modelId)
        } finally {
            targetContext.unbindService(service.connection)
        }
    }

    @Test
    fun cancelGenerationStopsForegroundState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        stageModel(instrumentation, "modelA")

        val service = bindService(targetContext)
        try {
            assertTrue(service.service.switchModel("modelA"))
            service.service.generateSafely("Once upon a time in a small local runtime")
            assertTrue("foreground state did not become active", waitForForeground(service.service, expected = true))

            service.service.cancelGeneration()
            assertTrue("foreground state did not stop after cancel", waitForForeground(service.service, expected = false))
        } finally {
            service.service.cancelGeneration()
            targetContext.unbindService(service.connection)
        }
    }

    @Test
    fun switchModelDuringGenerationCancelsBeforeReload() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        stageModel(instrumentation, "modelA")
        stageModel(instrumentation, "modelB")

        val service = bindService(targetContext)
        try {
            assertTrue(service.service.switchModel("modelA"))
            service.service.generateSafely("Once upon a time in a deterministic service")
            assertTrue("generation did not start", waitForGenerating(service.service, expected = true))

            assertTrue(service.service.switchModel("modelB"))

            assertEquals("modelB", service.service.currentModel.value)
            assertFalse(service.service.isGenerating.value)
            assertFalse(service.service.debugIsGenerationForegroundActive())
        } finally {
            service.service.cancelGeneration()
            targetContext.unbindService(service.connection)
        }
    }

    @Test
    fun oversizedModelIsRejectedWithoutUnloadingCurrentModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        stageModel(instrumentation, "modelA")
        stageOversizedModel(instrumentation, "oversizedModel")

        val service = bindService(targetContext)
        try {
            assertTrue(service.service.switchModel("modelA"))

            assertFalse(service.service.switchModel("oversizedModel"))

            assertEquals("modelA", service.service.currentModel.value)
            assertFalse(service.service.isGenerating.value)
            assertFalse(service.service.debugIsGenerationForegroundActive())
        } finally {
            service.service.cancelGeneration()
            targetContext.unbindService(service.connection)
        }
    }

    @Test
    fun cancelThenNewGenerationLeavesServiceConsistent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        stageModel(instrumentation, "modelA")

        val service = bindService(targetContext)
        try {
            assertTrue(service.service.switchModel("modelA"))
            service.service.generateSafely("Once upon a time")
            assertTrue("first generation did not start or finish", waitForGenerationStartedOrFinished(service.service))
            service.service.cancelGeneration()
            assertTrue("first generation did not cancel", waitForGenerating(service.service, expected = false))

            service.service.generateSafely("A new prompt after cancellation")
            assertTrue("second generation did not start or finish", waitForGenerationStartedOrFinished(service.service))
            service.service.cancelGeneration()
            assertTrue("second generation did not cancel", waitForGenerating(service.service, expected = false))
            assertFalse(service.service.debugIsGenerationForegroundActive())
        } finally {
            service.service.cancelGeneration()
            targetContext.unbindService(service.connection)
        }
    }

    @Test
    fun transcriptPersistsAfterGeneration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        stageModel(instrumentation, "modelTranscript")

        val service = bindService(targetContext)
        try {
            service.service.clearTranscript()
            assertTrue(service.service.switchModel("modelTranscript"))
            service.service.generateSafely("Hello from transcript persistence")

            val transcript = waitForTranscript(service.service)
            assertTrue(transcript.any { it.role == TranscriptRole.USER && it.text.contains("Hello from transcript") })
            assertTrue(transcript.any { it.role == TranscriptRole.ASSISTANT && it.text.isNotBlank() })

            val currentChatId = service.service.currentChatId.value
            assertTrue("expected an active chat id", currentChatId != null)
            val chatIndexFile = File(targetContext.filesDir, "chat_index.json")
            val transcriptFile = File(File(targetContext.filesDir, "chats"), "$currentChatId.json")
            assertTrue("expected chat index to be persisted", chatIndexFile.isFile)
            assertTrue("expected chat index to include active chat", chatIndexFile.readText().contains(currentChatId!!))
            assertTrue("expected transcript file to be persisted", transcriptFile.isFile)
            assertTrue("expected persisted transcript to include prompt", transcriptFile.readText().contains("Hello from transcript"))
        } finally {
            service.service.cancelGeneration()
            targetContext.unbindService(service.connection)
        }
    }

    @Test
    fun generationSettingsReachRuntimeAndPublishPerformance() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        stageModel(instrumentation, "modelPerf")

        val service = bindService(targetContext)
        try {
            assertTrue(service.service.switchModel("modelPerf"))
            service.service.updateGenerationSettings(GenerationSettings(maxTokens = 64, threadCount = 2))
            service.service.generateSafely("Hello from performance telemetry")

            val performance = waitForPerformance(service.service)
            assertEquals(64, performance.settings.maxTokens)
            assertEquals(2, performance.settings.threadCount)
            assertTrue("expected generated tokens, got $performance", performance.generatedTokens > 0)
            assertTrue("expected terminal performance, got $performance", performance.isComplete)
        } finally {
            service.service.cancelGeneration()
            targetContext.unbindService(service.connection)
        }
    }

    @Test
    fun unbindDuringGenerationDestroysServiceSafely() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        stageModel(instrumentation, "modelDestroy")

        val first = bindService(targetContext)
        assertTrue(first.service.switchModel("modelDestroy"))
        first.service.generateSafely("A generation that will be interrupted by service destroy")
        assertTrue("generation did not start before unbind", waitForGenerating(first.service, expected = true))
        targetContext.unbindService(first.connection)

        delay(500)

        val second = bindService(targetContext)
        try {
            assertTrue(second.service.switchModel("modelDestroy"))
            assertFalse(second.service.isGenerating.value)
            assertFalse(second.service.debugIsGenerationForegroundActive())
        } finally {
            second.service.cancelGeneration()
            targetContext.unbindService(second.connection)
        }
    }

    private suspend fun bindService(context: Context): BoundService {
        val deferred = CompletableDeferred<BoundService>()
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val service = (binder as InferenceService.LocalBinder).getService()
                deferred.complete(BoundService(service, connection))
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }

        val bound = context.bindService(
            Intent(context, InferenceService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        check(bound) { "Failed to bind InferenceService" }
        return withTimeout(15000) { deferred.await() }
    }

    private fun stageModel(instrumentation: Instrumentation, modelId: String) {
        val targetContext = instrumentation.targetContext
        val modelsDir = targetContext.getExternalFilesDir("models") ?: File(targetContext.filesDir, "models")
        val modelRoot = File(modelsDir, modelId)
        modelRoot.mkdirs()
        copyAsset(instrumentation, "smoke-model/manifest.json", File(modelRoot, "manifest.json"))
        copyAsset(instrumentation, "smoke-model/tinyllama-v0.q8_0.gguf", File(modelRoot, "tinyllama-v0.q8_0.gguf"))
    }

    private fun stageOversizedModel(instrumentation: Instrumentation, modelId: String) {
        val targetContext = instrumentation.targetContext
        val modelsDir = targetContext.getExternalFilesDir("models") ?: File(targetContext.filesDir, "models")
        val modelRoot = File(modelsDir, modelId)
        modelRoot.deleteRecursively()
        modelRoot.mkdirs()
        copyAsset(instrumentation, "smoke-model/tinyllama-v0.q8_0.gguf", File(modelRoot, "tinyllama-v0.q8_0.gguf"))
        File(modelRoot, "manifest.json").writeText(
            """
            {
              "active_version": "v1",
              "versions": {
                "v1": {
                  "file": "tinyllama-v0.q8_0.gguf",
                  "original_file_name": "oversized.gguf",
                  "sha256": "0cbe6769faaa77f4cdbf2f39dbc5f0fb9b3d62f7f9bc5760aa364946e6f2d0f5",
                  "bytes": 3221225473
                }
              }
            }
            """.trimIndent()
        )
    }

    private fun copyAsset(instrumentation: Instrumentation, assetPath: String, destination: File) {
        destination.parentFile?.mkdirs()
        instrumentation.context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun waitForImportTerminal(service: InferenceService): ImportState {
        repeat(200) {
            val state = service.importState.value
            if (state is ImportState.Success || state is ImportState.Failure || state is ImportState.Cancelled) {
                return state
            }
            delay(10)
        }
        return service.importState.value
    }

    private suspend fun waitForForeground(service: InferenceService, expected: Boolean): Boolean {
        repeat(200) {
            if (service.debugIsGenerationForegroundActive() == expected) {
                return true
            }
            delay(10)
        }
        return service.debugIsGenerationForegroundActive() == expected
    }

    private suspend fun waitForGenerating(service: InferenceService, expected: Boolean): Boolean {
        repeat(300) {
            if (service.isGenerating.value == expected) {
                return true
            }
            delay(10)
        }
        return service.isGenerating.value == expected
    }

    private suspend fun waitForGenerationStartedOrFinished(service: InferenceService): Boolean {
        repeat(500) {
            if (service.isGenerating.value) {
                return true
            }
            val assistantHasText = service.transcript.value.any { message ->
                message.role == TranscriptRole.ASSISTANT && message.text.isNotBlank()
            }
            if (assistantHasText && !service.isGenerating.value) {
                return true
            }
            delay(10)
        }
        return service.isGenerating.value || service.transcript.value.any { message ->
            message.role == TranscriptRole.ASSISTANT && message.text.isNotBlank()
        }
    }

    private suspend fun waitForTranscript(service: InferenceService): List<TranscriptMessage> {
        repeat(500) {
            val transcript = service.transcript.value
            val hasUser = transcript.any { message -> message.role == TranscriptRole.USER }
            val hasAssistant = transcript.any { message ->
                message.role == TranscriptRole.ASSISTANT && message.text.isNotBlank()
            }
            if (hasUser && hasAssistant && !service.isGenerating.value) {
                return transcript
            }
            delay(20)
        }
        return service.transcript.value
    }

    private suspend fun waitForPerformance(service: InferenceService): GenerationPerformance {
        repeat(500) {
            service.generationPerformance.value?.let { performance ->
                if (performance.isComplete) {
                    return performance
                }
            }
            delay(20)
        }
        return requireNotNull(service.generationPerformance.value) {
            "generation performance was not published"
        }
    }

    private fun validGgufBytes(): ByteArray {
        val bytes = ByteArray(64) { index -> (3 + index).toByte() }
        bytes[0] = 'G'.code.toByte()
        bytes[1] = 'G'.code.toByte()
        bytes[2] = 'U'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 3
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 0
        return bytes
    }

    private data class BoundService(
        val service: InferenceService,
        val connection: ServiceConnection,
    )
}
