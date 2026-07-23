package com.prismai.llmhost.benchmark
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.os.SystemClock
import android.util.Log
import com.prismai.llmhost.*
import com.prismai.llmhost.generation.GenerationMetrics
import com.prismai.llmhost.generation.GenerationOrchestrator
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.ui.UiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Runs benchmarks — preset, thread-sweep, and native PP/TG — and manages
 * the benchmark queue. Delegates generation to [GenerationOrchestrator].
 */
class BenchmarkRunner(
    private val uiState: ServiceUiState,
    private val eventBus: UiEventBus,
    private val engine: NativeLlmBridge,
    private val metrics: GenerationMetrics,
    private val store: BenchmarkStore,
    private val orchestrator: GenerationOrchestrator,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "BenchmarkRunner"
    }

    val queue = ArrayDeque<BenchmarkPreset>()

    // ── Public API ──────────────────────────────────────────────────────

    fun runPreset(presetId: String) {
        val preset = BenchmarkPresets.find(presetId)
        if (preset == null) {
            eventBus.publish("Benchmark preset not found")
            return
        }
        if (uiState.isGenerating.value) {
            eventBus.publish("Stop the current generation before running a benchmark")
            return
        }
        orchestrator.generate(prompt = preset.prompt, benchmarkPreset = preset)
    }

    fun runThreadSweep() {
        if (uiState.isGenerating.value) {
            eventBus.publish("Stop the current generation before running a thread sweep")
            return
        }
        queue.clear()
        BenchmarkPresets.threadSweep.forEach { preset -> queue.add(preset) }
        eventBus.publish("Thread sweep queued: 2, 4, 6, and 8 threads")
        runNextQueued()
    }

    fun runNativePpTg() {
        if (uiState.isGenerating.value) {
            eventBus.publish("Stop the current generation before running a native benchmark")
            return
        }
        if (uiState.currentModel.value == null) {
            eventBus.publish("Select a model before running a native benchmark")
            return
        }
        scope.launch {
            uiState._benchmarkStatus.value = BenchmarkStatus(
                isRunning = true,
                presetId = "native_pp_tg",
                presetName = "Native PP/TG",
                startedAt = System.currentTimeMillis(),
            )
            runCatching {
                startBenchmarkChat("Native PP/TG")
                val settings = uiState.generationSettings.value.clamped()
                val raw = engine.runNativeBenchmark(settings)
                store.recordNative(raw, settings)
                eventBus.publish("Native benchmark complete")
            }.onFailure { error ->
                Log.e(TAG, "native benchmark failed", error)
                eventBus.publish("Native benchmark failed: ${error.message ?: error::class.java.simpleName}")
            }
            uiState._benchmarkStatus.value = BenchmarkStatus()
        }
    }

    fun runNextQueued() {
        val next = queue.pollFirst() ?: return
        orchestrator.generate(prompt = next.prompt, benchmarkPreset = next)
    }

    fun cancelGeneration() {
        Log.d(TAG, "cancelGeneration requested")
        uiState._runtimeStatus.value = RuntimeStatus.CANCELLING
        // The actual cancellation is handled by InferenceService's mutex + cancelAndJoinGenerationLocked
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun startBenchmarkChat(name: String) {
        // Called from native benchmark path only
        engine.resetConversation()
    }
}
