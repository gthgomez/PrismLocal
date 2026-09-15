package com.prismai.llmhost.bridge
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.annotation.VisibleForTesting
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
        kvCacheTypeK: String,
        kvCacheTypeV: String,
        enableFlashAttn: Boolean,
        useVulkan: Boolean,
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
    private external fun nativeStartGenerationChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
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
    private external fun nativeSetThreadCount(handle: Long, threadCount: Int)
    private external fun nativeEncode(handle: Long, text: String): FloatArray
    private external fun nativeLoadVisionProjector(handle: Long, path: String): Boolean
    private external fun nativeApplyLoraAdapters(handle: Long, paths: Array<String>, scales: FloatArray): Boolean
    private external fun nativeClearLoraAdapters(handle: Long)
    private external fun nativeProcessImage(handle: Long, buffer: java.nio.ByteBuffer, width: Int, height: Int): Boolean
    private external fun nativeGetBackendName(handle: Long): String
    private external fun nativeGetGpuLayersOffloaded(handle: Long): Int
    private external fun nativeIsKleidiAiEnabled(handle: Long): Boolean
    private external fun nativeIsVulkanEnabled(handle: Long): Boolean

    suspend fun isVulkanEnabled(): Boolean = modelMutex.withLock {
        if (isDestroyed) return@withLock false
        nativeIsVulkanEnabled(nativeHandle)
    }

    suspend fun loadVisionProjector(path: String): Boolean = modelMutex.withLock {
        if (isDestroyed) return@withLock false
        nativeLoadVisionProjector(nativeHandle, path)
    }

    suspend fun processImage(buffer: java.nio.ByteBuffer, width: Int, height: Int): Boolean = modelMutex.withLock {
        if (isDestroyed) return@withLock false
        nativeProcessImage(nativeHandle, buffer, width, height)
    }

    suspend fun getBackendName(): String = modelMutex.withLock {
        if (isDestroyed) "CPU" else nativeGetBackendName(nativeHandle)
    }

    suspend fun getGpuLayersOffloaded(): Int = modelMutex.withLock {
        if (isDestroyed) 0 else nativeGetGpuLayersOffloaded(nativeHandle)
    }

    suspend fun isKleidiAiEnabled(): Boolean = modelMutex.withLock {
        if (isDestroyed) false else nativeIsKleidiAiEnabled(nativeHandle)
    }

    suspend fun setThreadCount(threadCount: Int) {
        if (!isDestroyed) {
            nativeSetThreadCount(nativeHandle, threadCount)
        }
    }

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

    /**
     * Encode [text] into a float embedding vector using the loaded model.
     * Returns empty FloatArray on failure (no model loaded, encode error, etc.).
     * Thread-safe via modelMutex.
     */
    suspend fun encode(text: String): FloatArray = withContext(Dispatchers.Default) {
        modelMutex.withLock {
            if (isDestroyed) return@withLock floatArrayOf()
            val h = nativeHandle
            if (h == 0L) return@withLock floatArrayOf()
            nativeEncode(h, text)
        }
    }

    suspend fun applyLoraAdapters(adapters: List<Pair<String, Float>>): Boolean = modelMutex.withLock {
        if (isDestroyed || nativeHandle == 0L) return@withLock false
        val paths = adapters.map { it.first }.toTypedArray()
        val scales = adapters.map { it.second }.toFloatArray()
        nativeApplyLoraAdapters(nativeHandle, paths, scales)
    }

    suspend fun clearLoraAdapters() {
        modelMutex.withLock {
            if (!isDestroyed && nativeHandle != 0L) {
                nativeClearLoraAdapters(nativeHandle)
            }
        }
    }

    suspend fun cancelGeneration(genId: Int = 0) {
        genMutex.withLock {
            if (!isDestroyed && nativeHandle != 0L) {
                nativeCancelGeneration(nativeHandle, genId)
            }
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

    /**
     * Backpressure-aware emission. Silently dropped chunks previously corrupted
     * transcripts and could lose the terminal event entirely, degrading real
     * ERROR/CANCELLED outcomes into the EOF heuristic. Terminal chunks retry
     * until delivered; data chunks cap retries at ~100 ms, then drop with a
     * logged warning rather than stalling the drain loop forever.
     */
    private suspend fun ProducerScope<GenerationChunk>.emitChunk(chunk: GenerationChunk) {
        var attempt = 0
        while (true) {
            if (trySend(chunk).isSuccess) return
            if (!isActive) return
            if (!chunk.isTerminal && attempt >= 50) {
                Log.w(TAG, "chunk_dropped_backpressure genId=${chunk.generationId} tokens=${chunk.tokenCount}")
                return
            }
            attempt++
            delay(2)
        }
    }

    /**
     * Shared drain/emit/finally body for the string path ([generate]) and the
     * structured chat path ([generateChat]). [start] performs the native start
     * call under [genMutex] and reports whether the generation was accepted.
     */
    private fun streamGeneration(start: (Int) -> Boolean): Flow<GenerationChunk> = callbackFlow {
        val genId = sessionCounter.getAndIncrement()

        val startSuccess = genMutex.withLock {
            if (isDestroyed) {
                false
            } else {
                start(genId)
            }
        }
        if (!startSuccess) {
            close()
            return@callbackFlow
        }

        var observedTerminal = false
        // Pre-sized primitive array avoids Long boxing and list growth.
        // Max drains ≈ max_tokens (1024) + overhead polls.
        val jniTimingsUs = LongArray(2048)
        var jniTimingsCount = 0
        val reusableResult = NativeDrainResult()
        // One incremental decoder per generation: carries a multi-byte UTF-8
        // sequence split across drains instead of decoding each drain in
        // isolation (which produced U+FFFD and lost the character).
        val utf8 = Utf8TextPipeline()
        var pollDelay = 2L
        try {
            while (isActive && !isDestroyed) {
                // Lock-free drain: C++ handles thread safety via atomics on the
                // ControlBlock and internal decode_mu. Skipping the Kotlin mutex here
                // eliminates contention with setMemoryPressure and model operations.
                val jniStart = SystemClock.elapsedRealtimeNanos()
                if (isDestroyed) {
                    reusableResult.tokensCount = 0
                    reusableResult.textCount = 0
                    reusableResult.textOverflow = ""
                    reusableResult.state = STATE_TOMBSTONED
                    reusableResult.errorCode = 0
                } else {
                    reusableResult.tokensCount = 0
                    reusableResult.textCount = 0
                    reusableResult.textOverflow = ""
                    reusableResult.state = 0
                    reusableResult.errorCode = 0
                    nativeDrainDecodeAndState(nativeHandle, genId, 128, reusableResult)
                }
                if (jniTimingsCount < jniTimingsUs.size) {
                    jniTimingsUs[jniTimingsCount++] = (SystemClock.elapsedRealtimeNanos() - jniStart) / 1000
                }

                val tokenCount = reusableResult.tokensCount
                val state = reusableResult.state
                val promptTokens = reusableResult.promptTokens

                if (tokenCount > 0) {
                    pollDelay = 2L // reset backoff on active token receipt

                    // Decode text incrementally: prefer buffer bytes, fall back
                    // to the already-decoded overflow string. Multi-byte code
                    // points split across drains are carried until complete.
                    val decodedText = if (reusableResult.textOverflow.isNotEmpty()) {
                        utf8.append(reusableResult.textOverflow.toByteArray(Charsets.UTF_8))
                    } else if (reusableResult.textCount > 0) {
                        utf8.append(reusableResult.textBuffer, reusableResult.textCount)
                    } else {
                        ""
                    }

                    if (decodedText.isNotEmpty() || tokenCount > 0) {
                        emitChunk(
                            GenerationChunk(
                                text = decodedText,
                                tokenCount = tokenCount,
                                generationId = genId,
                                isTerminal = false,
                                promptTokens = promptTokens,
                                ttftMs = reusableResult.ttftMs,
                                tokensPerSec = reusableResult.tokensPerSec,
                                activeThreads = reusableResult.activeThreads,
                                errorCode = reusableResult.errorCode,
                            )
                        )
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
                    // Finalize the decoder. Any buffered trailing bytes (e.g. a
                    // truncated final multi-byte sequence) are delivered *inside*
                    // the terminal chunk: terminal chunks are retried until sent
                    // (see emitChunk), so the remainder cannot be dropped under
                    // backpressure the way a separate non-terminal chunk could.
                    val trailingText = utf8.flush()
                    emitChunk(
                        GenerationChunk(
                            text = trailingText,
                            tokenCount = 0,
                            generationId = genId,
                            isTerminal = true,
                            terminalReason = reason,
                            promptTokens = promptTokens,
                            ttftMs = reusableResult.ttftMs,
                            tokensPerSec = reusableResult.tokensPerSec,
                            activeThreads = reusableResult.activeThreads,
                            errorCode = reusableResult.errorCode,
                        )
                    )
                    break
                }

                if (tokenCount == 0) {
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
            if (jniTimingsCount > 0) {
                val slice = jniTimingsUs.copyOfRange(0, jniTimingsCount)
                slice.sort()
                val p50 = slice[jniTimingsCount / 2]
                val p99 = slice[(jniTimingsCount * 99 / 100).coerceAtMost(jniTimingsCount - 1)]
                val max = slice.last()
                Log.d(TAG, "jni_timing genId=$genId drains=$jniTimingsCount p50_us=$p50 p99_us=$p99 max_us=$max")
            }
        }
        close()
    }

    fun generate(
        prompt: String,
        settings: GenerationSettings = GenerationSettings(),
        continueFromContext: Boolean = false,
        grammar: String? = null,
    ): Flow<GenerationChunk> = streamGeneration { genId ->
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
    }.flowOn(Dispatchers.IO)

    /**
     * Structured chat generation that preserves system/user/assistant roles so
     * native code can apply the model's real chat template. The legacy string
     * path ([generate]) is unaffected and remains the path for agent tool turns,
     * benchmark presets, and continuation.
     */
    fun generateChat(
        messages: List<ChatMessage>,
        settings: GenerationSettings = GenerationSettings(),
        grammar: String? = null,
    ): Flow<GenerationChunk> {
        if (messages.isEmpty()) return emptyFlow()
        val roles = messages.map { it.role }.toTypedArray()
        val contents = messages.map { it.content }.toTypedArray()
        return streamGeneration { genId ->
            nativeStartGenerationChat(
                nativeHandle,
                roles,
                contents,
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
                grammar,
            ) != -1
        }.flowOn(Dispatchers.IO)
    }

    @VisibleForTesting
    suspend fun debugDrainTokensForTesting(generationId: Int, maxTokens: Int): IntArray =
        modelMutex.withLock {
            if (isDestroyed) IntArray(0) else nativeDrainTokens(nativeHandle, generationId, maxTokens)
        }

    @VisibleForTesting
    suspend fun debugDecodeTokensForTesting(generationId: Int, tokens: IntArray): String =
        modelMutex.withLock {
            if (isDestroyed) "" else nativeDecodeTokens(nativeHandle, generationId, tokens)
        }.let { Utf8TextPipeline.normalizeNativeText(it) }

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
            settings.kvCacheTypeK,
            settings.kvCacheTypeV,
            settings.enableFlashAttn,
            settings.useVulkan,
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
