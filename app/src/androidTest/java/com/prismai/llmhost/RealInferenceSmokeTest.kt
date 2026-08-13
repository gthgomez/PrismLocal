package com.prismai.llmhost
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.os.SystemClock
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
    fun realGenerationCompactionPreservesExplicitSystemPrefix() = runBlocking {
        val model = requireTinyModel()
        val settings = GenerationSettings(
            maxTokens = 1,
            threadCount = 2,
            contextLength = 512,
            batchSize = 128,
        )
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("real model failed to load", engine.loadModel(model.file.absolutePath, settings))
            val repeatedContext = "Remember this context item for the next response. ".repeat(40)
            val explicitSystemPrompt =
                "<|im_start|>system\nYou are a concise assistant.<|im_end|>\n" +
                    "<|im_start|>user\n$repeatedContext<|im_end|>\n" +
                    "<|im_start|>assistant\n"

            val first = engine.generate(explicitSystemPrompt, settings).toList()
            assertTrue("first generation did not terminate normally", first.any { it.isTerminal && it.terminalReason != "ERROR" })

            val continued = engine.generate("", settings, continueFromContext = true).toList()
            assertTrue("continued generation emitted no tokens", continued.sumOf { it.tokenCount } >= 1)
            assertTrue("continued generation entered native error", continued.none { it.isTerminal && it.terminalReason == "ERROR" })
            assertEquals("continued generation reported a native error code", 0, continued.maxOf { it.errorCode })
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun realUserOnlyPromptDoesNotInferSystemPrefix() = runBlocking {
        val model = requireTinyModel()
        val settings = GenerationSettings(
            maxTokens = 1,
            threadCount = 2,
            contextLength = 512,
            batchSize = 128,
        )
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("real model failed to load", engine.loadModel(model.file.absolutePath, settings))
            val userPrompt =
                "<|im_start|>user\n" +
                    "This is a user-only prompt with no system role. " .repeat(40) +
                    "<|im_end|>\n<|im_start|>assistant\n"
            val first = engine.generate(userPrompt, settings).toList()
            assertTrue("user-only generation did not terminate normally", first.any { it.isTerminal && it.terminalReason != "ERROR" })

            val continued = engine.generate("", settings, continueFromContext = true).toList()
            assertTrue("user-only continuation emitted no tokens", continued.sumOf { it.tokenCount } >= 1)
            assertTrue("user-only continuation entered native error", continued.none { it.isTerminal && it.terminalReason == "ERROR" })
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun realCancellationClearsAbortCallbackAndMeetsLatencyTarget() = runBlocking {
        val model = requireTinyModel()
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("real model failed to load", engine.loadModel(model.file.absolutePath))
            val cancellationLatencies = mutableListOf<Long>()
            val longPrompt = "Generate a short continuation for this repeated context. ".repeat(30)

            repeat(5) { index ->
                val generationId = 7100 + index
                assertTrue(engine.debugStartGenerationForTesting(longPrompt, generationId))
                delay(5)
                val cancelStartedAt = SystemClock.elapsedRealtime()
                engine.debugCancelGenerationForTesting(generationId)
                val finalState = waitForState(engine, generationId, expected = 4)
                cancellationLatencies += SystemClock.elapsedRealtime() - cancelStartedAt
                assertEquals("expected native CANCELLED state", 4, finalState)
            }

            val sorted = cancellationLatencies.sorted()
            val p95Index = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)
            assertTrue(
                "cancellation p95 exceeded 100ms: latencies=$cancellationLatencies",
                sorted[p95Index] <= 100L,
            )

            val followUp = engine.generate(
                "Once upon a",
                GenerationSettings(maxTokens = 1, threadCount = 2),
            ).toList()
            assertTrue("generation after cancellation emitted no tokens", followUp.sumOf { it.tokenCount } >= 1)
            assertTrue("generation after cancellation entered native error", followUp.none { it.isTerminal && it.terminalReason == "ERROR" })
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
