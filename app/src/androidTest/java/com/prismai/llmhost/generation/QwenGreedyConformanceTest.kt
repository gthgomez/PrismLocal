package com.prismai.llmhost.generation

import android.util.Log
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.bridge.ChatMessage
import com.prismai.llmhost.bridge.NativeLlmBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in numerical comparison with tools/inference-conformance's Qwen fixture.
 * Pass -e prismQwenModelPath <app-readable-file>; weights never enter the APK.
 * This checks the JNI/native token path, not the app UI or ARM/GPU qualification.
 */
@RunWith(AndroidJUnit4::class)
class QwenGreedyConformanceTest {
    @Test
    fun cancellationInsideRealPrefillIsReportedByTheWorkerAndCanRecover() = runBlocking {
        val model = requiredModel()
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            val settings = fixtureSettings()
            assertTrue(engine.loadModel(model.absolutePath, settings))
            val generationId = 100_000 + (SystemClock.elapsedRealtime() % 1_000_000).toInt()
            val prompt = "Please summarize this repeated cancellation test context. ".repeat(80)
            assertTrue(engine.debugStartGenerationForTesting(prompt, generationId))
            withTimeout(30_000) {
                while (!nativeLogs().contains("stage=prefill_begin generation_id=$generationId ")) delay(100)
            }
            delay(200)
            assertTrue(
                "fixture completed prefill before it could be cancelled",
                !nativeLogs().contains("stage=prefill_complete generation_id=$generationId "),
            )
            engine.debugCancelGenerationForTesting(generationId)
            val logs = nativeLogs()
            assertTrue("worker did not classify interrupted prefill as cancellation", logs.contains("stage=prefill_complete generation_id=$generationId rc=-999"))
            assertTrue("worker did not publish CANCELLED", logs.contains("stage=terminal terminal=CANCELLED terminal_code=2 generation_id=$generationId"))
            val followUp = withTimeout(180_000) {
                engine.generate("Once upon a", settings.copy(maxTokens = 1)).toList()
            }
            assertTrue("generation did not recover after cancellation", followUp.sumOf { it.tokenCount } > 0)
            assertTrue("generation after cancellation failed", followUp.none { it.isTerminal && it.terminalReason == "ERROR" })
        } finally {
            engine.destroySafely()
        }
    }

    private fun nativeLogs(): String {
        val command = "logcat -d --pid=${Process.myPid()} -s LlmHostNative:I"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    @Test
    fun pinnedQwenMatchesGreedyReference() = runBlocking {
        val model = requiredModel()
        val settings = fixtureSettings()
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("Qwen fixture failed to load", engine.loadModel(model.absolutePath, settings))
            val handleField = NativeLlmBridge::class.java.getDeclaredField("nativeHandle").apply { isAccessible = true }
            val start = NativeLlmBridge::class.java.declaredMethods.single { it.name == "nativeStartGenerationChat" }
                .apply { isAccessible = true }
            // The test invokes the same JNI entry point as generateChat to inspect its token IDs.
            // It owns this engine exclusively and does not exercise the public Flow's lifecycle.
            val generationId = 7101
            val started = start.invoke(
                engine,
                handleField.getLong(engine),
                arrayOf("system", "user"),
                arrayOf(SYSTEM_PROMPT, "The capital of France is"),
                generationId,
                settings.maxTokens,
                settings.threadCount,
                settings.contextLength,
                settings.batchSize,
                settings.temperature,
                settings.topK,
                settings.topP,
                settings.repeatPenalty,
                settings.gpuLayers,
                null,
            ) as Int
            assertTrue("native structured generation was refused", started != -1)

            val ids = mutableListOf<Int>()
            val text = ByteArrayOutputStream()
            var promptTokens = 0
            var terminal = -1
            var errorCode = 0
            withTimeout(180_000) {
                while (true) {
                    val result = engine.debugDrainResultForTesting(generationId, 128)
                    promptTokens = maxOf(promptTokens, result.promptTokens)
                    if (result.tokensCount > 0) {
                        val batchIds = result.tokensBuffer.take(result.tokensCount)
                        ids.addAll(batchIds)
                        Log.i(TAG, "batch_ids=${batchIds.joinToString(",")} state=${result.state} error=${result.errorCode}")
                        val overflowBytes = result.textOverflowBytes
                        when {
                            overflowBytes != null && overflowBytes.isNotEmpty() -> text.write(overflowBytes)
                            !result.textOverflow.isNullOrEmpty() -> text.write(result.textOverflow!!.toByteArray(Charsets.UTF_8))
                            result.textCount > 0 -> text.write(result.textBuffer, 0, result.textCount)
                        }
                        assertTrue(
                            "native token acknowledgement rejected",
                            engine.debugAcknowledgeDrainedForTesting(generationId, result.drainTail, result.tokensCount),
                        )
                    }
                    if (result.state in listOf(3, 4, 5, 6, 7) && result.tokensCount == 0 && !result.pending) {
                        terminal = result.state
                        errorCode = result.errorCode
                        break
                    }
                    delay(20)
                }
            }
            val observedText = text.toString("UTF-8")
            Log.i(TAG, "prompt_tokens=$promptTokens ids=${ids.joinToString(",")} text=$observedText terminal=$terminal error=$errorCode")
            assertEquals("unexpected prompt token count", 34, promptTokens)
            assertEquals("unexpected native terminal/error", 0, errorCode)
            assertEquals("expected native EOF", 3, terminal)
            assertEquals("greedy token IDs diverged", listOf(785, 6722, 315, 9625, 374, 12095, 13), ids)
            assertEquals("The capital of France is Paris.", observedText)
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun publicChatAndWarmContextMatchReference() = runBlocking {
        val model = requiredModel()
        val settings = fixtureSettings()
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("Qwen fixture failed to load", engine.loadModel(model.absolutePath, settings))
            val firstMessages = listOf(
                ChatMessage(ChatMessage.ROLE_SYSTEM, SYSTEM_PROMPT),
                ChatMessage(ChatMessage.ROLE_USER, "The capital of France is"),
            )
            val first = withTimeout(180_000) { engine.generateChat(firstMessages, settings).toList() }
            val firstText = first.joinToString("") { it.text }
            Log.i(TAG, "public_turn=1 text=$firstText terminals=${first.filter { it.isTerminal }.map { it.terminalReason }}")

            // Use the fixture's known assistant turn so the second comparison is teacher-forced.
            val secondMessages = firstMessages + listOf(
                ChatMessage(ChatMessage.ROLE_ASSISTANT, "The capital of France is Paris."),
                ChatMessage(ChatMessage.ROLE_USER, "What is two plus two?"),
            )
            val second = withTimeout(180_000) { engine.generateChat(secondMessages, settings).toList() }
            val secondText = second.joinToString("") { it.text }
            Log.i(TAG, "public_turn=2 text=$secondText terminals=${second.filter { it.isTerminal }.map { it.terminalReason }}")

            assertEquals("The capital of France is Paris.", firstText)
            assertEquals(listOf("EOF"), first.filter { it.isTerminal }.map { it.terminalReason })
            assertTrue("first turn reported an error", first.all { it.errorCode == 0 })
            assertEquals("Two plus two is four.", secondText)
            assertEquals(listOf("EOF"), second.filter { it.isTerminal }.map { it.terminalReason })
            assertTrue("second turn reported an error", second.all { it.errorCode == 0 })
        } finally {
            engine.destroySafely()
        }
    }

    private fun requiredModel(): File {
        val path = InstrumentationRegistry.getArguments().getString("prismQwenModelPath")
        assumeTrue("Pass prismQwenModelPath to run the real Qwen fixture", !path.isNullOrBlank())
        val model = File(requireNotNull(path))
        assertTrue("Qwen fixture is not readable: $model", model.isFile && model.canRead())
        val actualHash = sha256(model)
        Log.i(TAG, "model_sha256=$actualHash bytes=${model.length()}")
        assertEquals(EXPECTED_HASH, actualHash)
        return model
    }

    private fun fixtureSettings() = GenerationSettings(
        maxTokens = 12,
        threadCount = 1,
        contextLength = 2048,
        batchSize = 256,
        temperature = 0f,
        topK = 1,
        topP = 1f,
        repeatPenalty = 1f,
        gpuLayers = 0,
        useVulkan = true,
        kvCacheTypeK = "f16",
        kvCacheTypeV = "f16",
        enableFlashAttn = false,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val TAG = "PrismQwenFixture"
        const val EXPECTED_HASH = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
        const val SYSTEM_PROMPT = "You are Assistant in a local Android chat. Use the recent conversation for context."
    }
}
