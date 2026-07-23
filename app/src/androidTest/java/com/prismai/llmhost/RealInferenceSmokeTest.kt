package com.prismai.llmhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealInferenceSmokeTest {
    private companion object {
        const val TEST_MODEL_ID = "smoke-model-instrumentation"
    }

    @Test
    fun realTinyModelOneTokenSmoke() = runBlocking {
        val model = requireTinyModel()
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("real model failed to load: ${model.file.absolutePath}", engine.loadModel(model.file.absolutePath))

            val chunks = engine.generate(
                "Once upon a",
                GenerationSettings(maxTokens = 1, threadCount = 2),
            ).toList()
            val tokenCount = chunks.sumOf { it.tokenCount }
            val decodedText = chunks.joinToString(separator = "") { it.text }

            assertTrue("expected at least one real token", tokenCount >= 1)
            assertTrue("expected non-empty decoded text", decodedText.isNotBlank())
            assertTrue("expected EOF terminal", chunks.any { it.isTerminal && it.terminalReason == "EOF" })
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun realGenerationCancellationReachesCancelled() = runBlocking {
        val model = requireTinyModel()
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue(engine.loadModel(model.file.absolutePath))
            val generationId = 7001
            assertTrue(engine.debugStartGenerationForTesting("Once upon a", generationId))
            engine.debugCancelGenerationForTesting(generationId)

            val finalState = waitForState(engine, generationId, expected = 4)
            assertEquals("expected native CANCELLED state", 4, finalState)
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun criticalMemoryPressureCancelsRealGeneration() = runBlocking {
        val model = requireTinyModel()
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue(engine.loadModel(model.file.absolutePath))
            engine.setMemoryPressure(3)
            val generationId = 7002
            assertTrue(engine.debugStartGenerationForTesting("Once upon a", generationId))

            val finalState = waitForState(engine, generationId, expected = 4)
            assertEquals("expected native CANCELLED state under critical memory pressure", 4, finalState)
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun realCreateLoadGenerateDestroyStress() = runBlocking {
        val model = requireTinyModel()
        repeat(10) {
            val engine = NativeLlmBridge.create(debugHooksEnabled = false)
            try {
                assertTrue(engine.loadModel(model.file.absolutePath))
                val chunks = engine.generate(
                    "Once upon a",
                    GenerationSettings(maxTokens = 1, threadCount = 2),
                ).toList()
                assertTrue("iteration $it emitted no tokens", chunks.sumOf { chunk -> chunk.tokenCount } >= 1)
            } finally {
                engine.destroySafely()
            }
        }
    }

    private fun requireTinyModel(): ModelStorageManager.ActiveModelInfo {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        stageTinyModel(instrumentation)
        val context = instrumentation.targetContext
        val model = ModelStorageManager(context).resolveActiveModel(TEST_MODEL_ID)
        assertNotNull(
            "Missing real $TEST_MODEL_ID GGUF manifest/model under external app files",
            model
        )
        assertTrue("Expected resolved model, got $model", model is ModelStorageManager.ModelResolveResult.Success)
        return (model as ModelStorageManager.ModelResolveResult.Success).model
    }

    private fun stageTinyModel(instrumentation: android.app.Instrumentation) {
        val targetContext = instrumentation.targetContext
        val modelsDir = targetContext.getExternalFilesDir("models") ?: File(targetContext.filesDir, "models")
        val modelRoot = File(modelsDir, TEST_MODEL_ID)
        modelRoot.mkdirs()
        copyAssetIfNeeded(instrumentation, "smoke-model/manifest.json", File(modelRoot, "manifest.json"), 191)
        copyAssetIfNeeded(
            instrumentation,
            "smoke-model/tinyllama-v0.q8_0.gguf",
            File(modelRoot, "tinyllama-v0.q8_0.gguf"),
            6750304
        )
    }

    private fun copyAssetIfNeeded(
        instrumentation: android.app.Instrumentation,
        assetPath: String,
        destination: File,
        expectedBytes: Long
    ) {
        if (destination.exists() && destination.length() == expectedBytes) {
            return
        }
        destination.parentFile?.mkdirs()
        instrumentation.context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun waitForState(engine: NativeLlmBridge, generationId: Int, expected: Int): Int {
        var latest = engine.debugStateForTesting(generationId)
        repeat(200) {
            if (latest == expected) {
                return latest
            }
            delay(5)
            latest = engine.debugStateForTesting(generationId)
        }
        return latest
    }
}
