package com.prismai.llmhost
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.Process
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
            // maxTokens = 1 means the engine stops on the token budget and emits
            // MAX_TOKENS. EOF only occurs if the model produces a stop token
            // first, which this budget does not allow. Assert the real contract.
            assertTrue(
                "expected MAX_TOKENS terminal when the token budget is reached",
                chunks.any { it.isTerminal && it.terminalReason == "MAX_TOKENS" },
            )
            assertTrue(
                "generation must end with a terminal chunk",
                chunks.any { it.isTerminal },
            )
            assertTrue(
                "terminal reason must be a known value",
                chunks.filter { it.isTerminal }
                    .all { it.terminalReason == "EOF" || it.terminalReason == "MAX_TOKENS" },
            )
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
            maxTokens = 8,
            threadCount = 2,
            contextLength = 512,
            batchSize = 128,
            temperature = 0f,
            topK = 1,
            repeatPenalty = 1f,
        )
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("real model failed to load", engine.loadModel(model.file.absolutePath, settings))
            // Keep the initial prompt inside the native headroom while filling enough of the
            // context that the continuation must use the sliding-window path.
            val repeatedContext = "Remember this context item for the next response. ".repeat(49)
            val explicitSystemPrompt =
                "<|im_start|>system\nYou are a concise assistant.<|im_end|>\n" +
                    "<|im_start|>user\n$repeatedContext<|im_end|>\n" +
                    "<|im_start|>assistant\n"

            val first = engine.generate(explicitSystemPrompt, settings).toList()
            assertTrue("first generation did not terminate normally", first.any { it.isTerminal && it.terminalReason != "ERROR" })
            assertTrue("first generation did not fill enough context", first.sumOf { it.tokenCount } >= 5)
            assertEquals("unexpected system-prompt fixture token count", 492, first.maxOf { it.promptTokens })

            val previousShiftCount = observedShiftPrefixLengths().size
            val continued = engine.generate("", settings, continueFromContext = true).toList()
            assertTrue("continued generation emitted no tokens", continued.sumOf { it.tokenCount } >= 1)
            assertTrue("continued generation entered native error", continued.none { it.isTerminal && it.terminalReason == "ERROR" })
            assertEquals("continued generation reported a native error code", 0, continued.maxOf { it.errorCode })
            assertEquals("the actual KV shift must preserve the complete system prefix", 21, newShiftPrefixLength(previousShiftCount))
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun realUserOnlyPromptDoesNotInferSystemPrefix() = runBlocking {
        val model = requireTinyModel()
        val settings = GenerationSettings(
            maxTokens = 8,
            threadCount = 2,
            contextLength = 512,
            batchSize = 128,
            temperature = 0f,
            topK = 1,
            repeatPenalty = 1f,
        )
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("real model failed to load", engine.loadModel(model.file.absolutePath, settings))
            val userPrompt =
                "<|im_start|>user\n" +
                    "This is a user-only prompt with no system role. ".repeat(39) +
                    "<|im_end|>\n<|im_start|>assistant\n"
            val first = engine.generate(userPrompt, settings).toList()
            assertTrue("user-only generation did not terminate normally", first.any { it.isTerminal && it.terminalReason != "ERROR" })
            assertTrue("user-only generation emitted no tokens", first.sumOf { it.tokenCount } >= 1)
            assertEquals("unexpected boundary fixture token count", 496, first.maxOf { it.promptTokens })

            val previousShiftCount = observedShiftPrefixLengths().size
            val continued = engine.generate("", settings, continueFromContext = true).toList()
            assertTrue("user-only continuation emitted no tokens", continued.sumOf { it.tokenCount } >= 1)
            assertTrue("user-only continuation entered native error", continued.none { it.isTerminal && it.terminalReason == "ERROR" })
            assertEquals("a user-only prompt must not acquire a system prefix", 0, newShiftPrefixLength(previousShiftCount))
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun realInsertedBosStillPreservesExplicitSystemPrefix() = runBlocking {
        val model = requireTinyModel()
        val settings = GenerationSettings(
            maxTokens = 7,
            threadCount = 2,
            contextLength = 512,
            batchSize = 128,
            temperature = 0f,
            topK = 1,
            repeatPenalty = 1f,
        )
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("real model failed to load", engine.loadModel(model.file.absolutePath, settings))
            // This template causes the tokenizer to insert a BOS before the system role.
            val prompt = "<|start_header_id|>system<|end_header_id|>\n" +
                "You are a concise assistant.<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
                "Remember this context item for the next response. ".repeat(46) +
                "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n"
            val first = engine.generate(prompt, settings).toList()
            assertEquals("unexpected BOS fixture token count", 497, first.maxOf { it.promptTokens })
            assertTrue("first generation emitted no tokens", first.sumOf { it.tokenCount } >= 1)
            assertTrue("first generation entered native error", first.none { it.isTerminal && it.terminalReason == "ERROR" })

            val previousShiftCount = observedShiftPrefixLengths().size
            val continued = engine.generate("", settings, continueFromContext = true).toList()
            assertTrue("continued generation emitted no tokens", continued.sumOf { it.tokenCount } >= 1)
            assertTrue("continued generation entered native error", continued.none { it.isTerminal && it.terminalReason == "ERROR" })
            assertEquals("the KV shift must retain BOS and the complete system prefix", 34, newShiftPrefixLength(previousShiftCount))
        } finally {
            engine.destroySafely()
        }
    }

    private fun observedShiftPrefixLengths(): List<Int> {
        val command = "logcat -d --pid=${Process.myPid()} -s LlmHostNative:I"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        val logs = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        return Regex("kv_cache_sliding_window_shift .*?prefix=(\\d+)")
            .findAll(logs).map { it.groupValues[1].toInt() }.toList()
    }

    private fun newShiftPrefixLength(previousCount: Int): Int {
        val shifts = observedShiftPrefixLengths()
        assertTrue("expected a new native KV-cache shift", shifts.size > previousCount)
        return shifts.last()
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
