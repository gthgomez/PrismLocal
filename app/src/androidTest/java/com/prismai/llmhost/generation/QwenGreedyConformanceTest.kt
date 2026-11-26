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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in numerical comparison with tools/inference-conformance's Qwen fixture.
 * Pass -e prismQwenModelPath <app-readable-file>, or stage the optional test asset.
 * This checks the JNI/native token path, not the app UI or ARM/GPU qualification.
 */
@RunWith(AndroidJUnit4::class)
class QwenGreedyConformanceTest {
    @Test
    fun cancellationInsideRealPrefillIsReportedByTheWorkerAndCanRecover() = runBlocking {
        val model = requiredPinnedQwenModel()
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            // The entire prompt fits one decode batch. Observe llama's abort return,
            // so a between-batch cancellation cannot satisfy this regression.
            val settings = fixtureSettings().copy(batchSize = 1024)
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
            val runLogs = logs.substringAfter("stage=prefill_begin generation_id=$generationId ", missingDelimiterValue = "")
            assertTrue("llama.cpp was not interrupted inside a batch", runLogs.contains("llama_decode_aborted_by_request rc=2"))
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
        val command = "logcat -d --pid=${Process.myPid()} -s LlmHostNative:I llama:I"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    @Test
    fun pinnedQwenMatchesGreedyReference() = runBlocking {
        val model = requiredPinnedQwenModel()
        val settings = fixtureSettings()
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("Qwen fixture failed to load", engine.loadModel(model.absolutePath, settings))
            val logs = nativeLogs()
            assertTrue("CPU-only load was not applied", logs.contains("gpu_layers_requested=0 gpu_layers_verified=0") &&
                logs.contains("cpu_pinned=1 op_offload=0"))
            assertTrue("CPU KV and compute placement were not observed",
                Regex("""CPU\s+KV buffer""").containsMatchIn(logs) &&
                    Regex("""CPU\s+compute buffer""").containsMatchIn(logs))
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
        val model = requiredPinnedQwenModel()
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

            assertEquals("The capital of France is Paris.", firstText)
            val secondMessages = firstMessages + listOf(
                ChatMessage(ChatMessage.ROLE_ASSISTANT, firstText),
                ChatMessage(ChatMessage.ROLE_USER, "What is two plus two?"),
            )
            val second = withTimeout(180_000) { engine.generateChat(secondMessages, settings).toList() }
            val secondText = second.joinToString("") { it.text }
            Log.i(TAG, "public_turn=2 text=$secondText terminals=${second.filter { it.isTerminal }.map { it.terminalReason }}")

            assertEquals(listOf("EOF"), first.filter { it.isTerminal }.map { it.terminalReason })
            assertTrue("first turn reported an error", first.all { it.errorCode == 0 })
            assertEquals("Two plus two is four.", secondText)
            assertEquals(listOf("EOF"), second.filter { it.isTerminal }.map { it.terminalReason })
            assertTrue("second turn reported an error", second.all { it.errorCode == 0 })
        } finally {
            engine.destroySafely()
        }
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

    private companion object {
        const val TAG = "PrismQwenFixture"
        const val SYSTEM_PROMPT = "You are Assistant in a local Android chat. Use the recent conversation for context."
    }
}
