package com.example.llmhost

import androidx.annotation.VisibleForTesting
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

class NativeLlmBridge private constructor(handle: Long, private val instanceId: Int) {
    companion object {
        private const val TAG = "NativeLlmBridge"
        private const val STATE_EOF = 3
        private const val STATE_CANCELLED = 4
        private const val STATE_ERROR = 5
        private const val STATE_TOMBSTONED = 6
        private const val STATE_MAX_TOKENS = 7
        private val EMPTY_INT_ARRAY = IntArray(0)
        private val bridgeInstanceCounter = AtomicInteger(0)

        init {
            System.loadLibrary("llmhost")
        }

        fun create(debugHooksEnabled: Boolean = BuildConfig.LLMHOST_DEBUG_HOOKS): NativeLlmBridge {
            val handle = nativeCreateEngine(debugHooksEnabled)
            check(handle != 0L) { "Failed to allocate native LLM host engine" }
            val instanceId = bridgeInstanceCounter.incrementAndGet()
            Log.d(TAG, "NativeLlmBridge instance created id=$instanceId handle=$handle")
            return NativeLlmBridge(handle, instanceId)
        }

        @JvmStatic
        private external fun nativeCreateEngine(debugHooksEnabled: Boolean): Long
    }

    // modelMutex serializes model lifecycle: load, unload, reset, destroy.
    // The drain/decode path does NOT acquire this lock — C++ handles its own
    // thread safety via atomics and internal mutexes.
    private val modelMutex = Mutex()
    // genMutex serializes generation start/cancel/ack to prevent concurrent
    // generations from racing on session state. The hot drain loop is NOT
    // serialized — it reads atomics lock-free.
    private val genMutex = Mutex()
    private val sessionCounter = AtomicInteger(1)

    @Volatile
    private var isDestroyed = false
    private var nativeHandle: Long = handle

    private external fun nativeDestroyEngine(handle: Long)
    private external fun nativeLoadModel(handle: Long, path: String): Boolean
    private external fun nativeLoadModelWithSettings(
        handle: Long,
        path: String,
        maxTokens: Int,
        threadCount: Int,
        contextLength: Int,
        batchSize: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        repeatPenalty: Float,
        gpuLayers: Int,
    ): Boolean
    private external fun nativeUnloadModel(handle: Long)
    private external fun nativeResetConversation(handle: Long)
    private external fun nativeStartGeneration(
        handle: Long,
        prompt: String,
        genId: Int,
        maxTokens: Int,
        threadCount: Int,
        contextLength: Int,
        batchSize: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        repeatPenalty: Float,
        gpuLayers: Int,
        continueFromContext: Boolean,
        grammar: String?,
    ): Int
    private external fun nativeRunBenchmark(
        handle: Long,
        maxTokens: Int,
        threadCount: Int,
        contextLength: Int,
        batchSize: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        repeatPenalty: Float,
        gpuLayers: Int,
        promptTokens: Int,
        generationTokens: Int,
        repetitions: Int,
    ): String
    private external fun nativeCancelGeneration(handle: Long, genId: Int)
    private external fun nativeDrainTokens(handle: Long, genId: Int, maxTokens: Int): IntArray
    private external fun nativeAckEof(handle: Long, genId: Int)
    private external fun nativeDecodeTokens(handle: Long, genId: Int, tokens: IntArray): String
    private external fun nativeGetState(handle: Long, genId: Int): Int
    private external fun nativeDrainDecodeAndState(handle: Long, genId: Int, maxTokens: Int, outResult: NativeDrainResult)
    private external fun nativeSetMemoryPressure(handle: Long, level: Int)

    suspend fun loadModel(path: String, settings: GenerationSettings = GenerationSettings()): Boolean = modelMutex.withLock {
        if (isDestroyed) return@withLock false
        val safeSettings = settings.clamped()
        nativeLoadModelWithSettings(nativeHandle, path, safeSettings)
    }

    suspend fun unloadModel() {
        modelMutex.withLock {
            if (!isDestroyed) {
                nativeUnloadModel(nativeHandle)
            }
        }
    }

    suspend fun resetConversation() {
        modelMutex.withLock {
            if (!isDestroyed) {
                nativeResetConversation(nativeHandle)
            }
        }
    }

    suspend fun runNativeBenchmark(
        settings: GenerationSettings = GenerationSettings(),
        promptTokens: Int = 512,
        generationTokens: Int = 128,
        repetitions: Int = 3,
    ): String = modelMutex.withLock {
        if (isDestroyed) return@withLock "{\"error\":\"destroyed\"}"
        nativeRunBenchmark(
            nativeHandle,
            settings.clamped(),
            promptTokens,
            generationTokens,
            repetitions,
        )
    }

    suspend fun setMemoryPressure(level: Int) {
        // Lock-free: C++ only writes an atomic (memory_pressure_level.store).
        // The volatile isDestroyed check + C++ nullptr guard make this safe without a mutex.
        if (!isDestroyed) {
            nativeSetMemoryPressure(nativeHandle, level)
        }
    }

    suspend fun destroySafely() {
        modelMutex.withLock {
            if (isDestroyed) return@withLock
            isDestroyed = true
            nativeDestroyEngine(nativeHandle)
            Log.d(TAG, "NativeLlmBridge instance destroyed id=$instanceId")
            nativeHandle = 0L
        }
    }

    fun generate(
        prompt: String,
        settings: GenerationSettings = GenerationSettings(),
        continueFromContext: Boolean = false,
        grammar: String? = null,
    ): Flow<GenerationChunk> = callbackFlow {
        val genId = sessionCounter.getAndIncrement()

        val startSuccess = genMutex.withLock {
            if (isDestroyed) {
                false
            } else {
                nativeStartGeneration(
                    nativeHandle,
                    prompt,
                    genId,
                    settings.maxTokens,
                    settings.threadCount,
                    settings.contextLength,
                    settings.batchSize,
                    settings.temperature,
                    settings.topK,
                    settings.topP,
                    settings.repeatPenalty,
                    settings.gpuLayers,
                    continueFromContext,
                    grammar,
                ) != -1
            }
        }
        if (!startSuccess) {
            close()
            return@callbackFlow
        }

        var observedTerminal = false
        val jniTimingsUs = mutableListOf<Long>()
        val reusableResult = NativeDrainResult()
        var pollDelay = 2L
        try {
            while (isActive && !isDestroyed) {
                // Lock-free drain: C++ handles thread safety via atomics on the
                // ControlBlock and internal decode_mu. Skipping the Kotlin mutex here
                // eliminates contention with setMemoryPressure and model operations.
                val jniStart = SystemClock.elapsedRealtimeNanos()
                if (isDestroyed) {
                    reusableResult.tokens = EMPTY_INT_ARRAY
                    reusableResult.text = ""
                    reusableResult.state = STATE_TOMBSTONED
                } else {
                    reusableResult.tokens = EMPTY_INT_ARRAY
                    reusableResult.text = ""
                    reusableResult.state = 0
                    nativeDrainDecodeAndState(nativeHandle, genId, 128, reusableResult)
                }
                jniTimingsUs.add((SystemClock.elapsedRealtimeNanos() - jniStart) / 1000)

                val tokens = reusableResult.tokens
                val text = reusableResult.text
                val state = reusableResult.state
                val promptTokens = reusableResult.promptTokens

                if (tokens.isNotEmpty()) {
                    pollDelay = 2L // reset backoff on active token receipt
                    val normalizedText = Utf8TextPipeline.normalizeNativeText(text)
                    if (normalizedText.isNotEmpty() || tokens.isNotEmpty()) {
                        trySend(GenerationChunk(normalizedText, tokens.size, genId, isTerminal = false, promptTokens = promptTokens))
                    }
                }

                if (state == STATE_EOF || state == STATE_CANCELLED || state == STATE_ERROR || state == STATE_MAX_TOKENS) {
                    val reason = when (state) {
                        STATE_EOF -> "EOF"
                        STATE_CANCELLED -> "CANCELLED"
                        STATE_ERROR -> "ERROR"
                        STATE_MAX_TOKENS -> "MAX_TOKENS"
                        else -> "UNKNOWN"
                    }
                    observedTerminal = true
                    trySend(GenerationChunk("", 0, genId, isTerminal = true, terminalReason = reason, promptTokens = promptTokens))
                    break
                }

                if (tokens.isEmpty()) {
                    delay(pollDelay)
                    pollDelay = (pollDelay * 2).coerceAtMost(64L) // backoff up to 64ms
                }
            }
        } finally {
            genMutex.withLock {
                if (!isDestroyed) {
                    if (!observedTerminal) {
                        nativeCancelGeneration(nativeHandle, genId)
                    }
                    nativeAckEof(nativeHandle, genId)
                }
            }
            val timingCount = jniTimingsUs.size
            if (timingCount > 0) {
                val sorted = jniTimingsUs.sorted()
                val p50 = sorted[timingCount / 2]
                val p99 = sorted[(timingCount * 99 / 100).coerceAtMost(timingCount - 1)]
                val max = sorted.last()
                Log.d(TAG, "jni_timing genId=$genId drains=$timingCount p50_us=$p50 p99_us=$p99 max_us=$max")
            }
        }
        close()
    }.flowOn(Dispatchers.IO)

    @VisibleForTesting
    suspend fun debugDrainTokensForTesting(generationId: Int, maxTokens: Int): IntArray =
        modelMutex.withLock {
            if (isDestroyed) IntArray(0) else nativeDrainTokens(nativeHandle, generationId, maxTokens)
        }

    @VisibleForTesting
    suspend fun debugDecodeTokensForTesting(generationId: Int, tokens: IntArray): String =
        modelMutex.withLock {
            if (isDestroyed) "" else nativeDecodeTokens(nativeHandle, generationId, tokens)
        }.let(Utf8TextPipeline::normalizeNativeText)

    @VisibleForTesting
    suspend fun debugStartGenerationForTesting(prompt: String, generationId: Int): Boolean =
        modelMutex.withLock {
            if (isDestroyed) {
                false
            } else {
                val settings = GenerationSettings()
                nativeStartGeneration(
                    nativeHandle,
                    prompt,
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
                    false,
                    null,
                ) != -1
            }
        }

    @VisibleForTesting
    suspend fun debugCancelGenerationForTesting(generationId: Int) {
        modelMutex.withLock {
            if (!isDestroyed) {
                nativeCancelGeneration(nativeHandle, generationId)
            }
        }
    }

    @VisibleForTesting
    suspend fun debugStateForTesting(generationId: Int): Int =
        modelMutex.withLock {
            if (isDestroyed) STATE_TOMBSTONED else nativeGetState(nativeHandle, generationId)
        }

    private fun nativeLoadModelWithSettings(handle: Long, path: String, settings: GenerationSettings): Boolean =
        nativeLoadModelWithSettings(
            handle,
            path,
            settings.maxTokens,
            settings.threadCount,
            settings.contextLength,
            settings.batchSize,
            settings.temperature,
            settings.topK,
            settings.topP,
            settings.repeatPenalty,
            settings.gpuLayers,
        )

    private fun nativeRunBenchmark(
        handle: Long,
        settings: GenerationSettings,
        promptTokens: Int,
        generationTokens: Int,
        repetitions: Int,
    ): String =
        nativeRunBenchmark(
            handle,
            settings.maxTokens,
            settings.threadCount,
            settings.contextLength,
            settings.batchSize,
            settings.temperature,
            settings.topK,
            settings.topP,
            settings.repeatPenalty,
            settings.gpuLayers,
            promptTokens,
            generationTokens,
            repetitions,
        )
}
