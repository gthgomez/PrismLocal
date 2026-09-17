package com.prismai.llmhost.generation

import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.bridge.NativeLlmBridge
import com.prismai.llmhost.storage.ModelStorageManager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PIR-05 device-side context-replay test.
 *
 * Requires a real device/emulator with a small GGUF fixture available (an
 * installed model from [ModelStorageManager] or any `.gguf` pushed under the
 * app's external `models/` directory). Without one the test marks itself
 * skipped via [assumeTrue] — it never fakes a pass.
 *
 * What it exercises, through the real [NativeLlmBridge]:
 *  - a clean-cache reference for a distinct continuation prompt;
 *  - a baseline full prefill from a clean KV cache;
 *  - an identical second turn, which the engine serves from the committed
 *    prefix (all-cached reuse);
 *  - [NativeLlmBridge.resetConversation], after which the engine must replay the
 *    whole DISTINCT continuation prompt rather than evaluate a suffix against a
 *    stale prefix;
 *  - a reset followed by an extended prompt.
 *
 * The native layer does not expose a reusable-token counter to Kotlin, so the
 * "no suffix-only evaluation after a reset" property is asserted behaviorally as
 * a proxy, not a proof: with greedy sampling the continuation is a pure function
 * of the prompt logits, so generating the distinct continuation after a reset
 * must reproduce the clean-cache reference. Unlike comparing an identical prompt
 * (which a no-op reset would also pass), a stale prefix whose tail diverges from
 * this continuation would make a suffix-only evaluation sample different logits
 * and drift from the reference.
 */
@RunWith(AndroidJUnit4::class)
class ContextReplayTest {

    private companion object {
        val GREEDY_SETTINGS = GenerationSettings(
            maxTokens = 4,
            threadCount = 2,
            contextLength = 512,
            batchSize = 128,
            topK = 1,
            topP = GenerationSettings.MIN_TOP_P,
            temperature = GenerationSettings.MIN_TEMPERATURE,
        )
    }

    @Test
    fun multiTurnReuseMatchesFullReplayAndResetReplaysCleanly() = runBlocking {
        val model = smallestAvailableModel()
        assumeTrue(
            "No small GGUF model fixture installed or pushed under the app's models dir; " +
                "skipping device context-replay test",
            model != null,
        )
        val fixture = model!! // assumeTrue above throws when null

        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assumeTrue(
                "GGUF fixture failed to load; skipping device context-replay test",
                engine.loadModel(fixture.absolutePath, GREEDY_SETTINGS),
            )

            val basePrompt =
                "<|im_start|>user\n" +
                    "Remember the sequence alpha beta gamma.<|im_end|>\n" +
                    "<|im_start|>assistant\n"

            // A distinct continuation: it shares `basePrompt` but diverges in the
            // suffix, so its clean-cache continuation is not the baseline's.
            val continuationPrompt = basePrompt + "Now repeat alpha.\n"

            // Clean-cache reference for the distinct continuation. It is produced
            // first, while the KV cache is empty, so it is a genuine full replay.
            val cleanContinuation = generate(engine, continuationPrompt)
            assertTrue(
                "clean continuation entered native error",
                !cleanContinuation.sawTerminalError,
            )
            assertTrue("clean continuation emitted no tokens", cleanContinuation.tokenCount >= 1)

            // Baseline: a genuine full prefill from a clean KV cache (the reset
            // immediately below clears the reference's cache entries).
            engine.resetConversation()
            val baseline = generate(engine, basePrompt)
            assertTrue("baseline entered native error", !baseline.sawTerminalError)
            assertTrue("baseline reported a native error code", baseline.errorCode == 0)
            assertTrue("baseline emitted no tokens", baseline.tokenCount >= 1)

            // Turn 2, identical prompt: the whole prompt is served from the
            // committed prefix. Greedy sampling makes the continuation a pure
            // function of the prompt logits, so it must equal the baseline.
            val reused = generate(engine, basePrompt)
            assertTrue("prefix-cache reuse entered native error", !reused.sawTerminalError)
            assertEquals(
                "prefix reuse changed the sampled continuation",
                baseline.text,
                reused.text,
            )

            // Reset invalidates the cache: the next turn must replay the WHOLE
            // DISTINCT continuation prompt, reproducing the clean-cache reference.
            //
            // Behavioural proxy: no native reuse counter is exposed, and a correct
            // prefix reuse is logit-equivalent to a full replay, so this cannot
            // literally prove the KV cache was cleared. It is discriminating
            // where it matters: had a reset left a stale prefix whose tail
            // diverges from this continuation, a suffix-only evaluation would
            // sample different logits and drift from the reference (an identical
            // prompt could not expose that, since even a no-op reset would pass).
            engine.resetConversation()
            val afterReset = generate(engine, continuationPrompt)
            assertTrue("post-reset generation entered native error", !afterReset.sawTerminalError)
            assertEquals(
                "reset did not replay the distinct prompt from a clean KV cache",
                cleanContinuation.text,
                afterReset.text,
            )

            // Reset + extended prompt must decode the whole new prompt cleanly.
            engine.resetConversation()
            val extended = generate(engine, continuationPrompt + "Now repeat beta.\n")
            assertTrue(
                "extended post-reset generation entered native error",
                !extended.sawTerminalError,
            )
            assertTrue(
                "extended post-reset generation emitted no tokens",
                extended.tokenCount >= 1,
            )
        } finally {
            engine.destroySafely()
        }
    }

    private data class TurnResult(
        val text: String,
        val tokenCount: Int,
        val errorCode: Int,
        val sawTerminalError: Boolean,
    )

    private suspend fun generate(engine: NativeLlmBridge, prompt: String): TurnResult {
        val chunks = engine.generate(prompt, GREEDY_SETTINGS).toList()
        return TurnResult(
            text = chunks.joinToString(separator = "") { chunk -> chunk.text },
            tokenCount = chunks.sumOf { chunk -> chunk.tokenCount },
            errorCode = chunks.maxOfOrNull { chunk -> chunk.errorCode } ?: 0,
            sawTerminalError = chunks.any { chunk -> chunk.isTerminal && chunk.terminalReason == "ERROR" },
        )
    }

    /** An installed model if present, else the smallest `.gguf` pushed on device. */
    private fun smallestAvailableModel(): File? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val installed = ModelStorageManager(context)
            .listInstalledModelInfos()
            .map { info -> info.file }
            .filter { file -> file.isFile }
        if (installed.isNotEmpty()) {
            return installed.minByOrNull { file -> file.length() }
        }

        val modelsDir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        if (!modelsDir.isDirectory) {
            return null
        }
        return modelsDir.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("gguf", ignoreCase = true) }
            .minByOrNull { file -> file.length() }
    }
}
