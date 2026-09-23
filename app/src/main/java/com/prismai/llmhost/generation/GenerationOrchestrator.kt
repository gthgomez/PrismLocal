package com.prismai.llmhost.generation
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.os.SystemClock
import android.util.Log
import com.prismai.llmhost.*
import com.prismai.llmhost.agent.AgentToolConfirmation
import com.prismai.llmhost.agent.AgentToolRouter
import com.prismai.llmhost.agent.AgentTrace
import com.prismai.llmhost.agent.ToolInputSanitizer
import com.prismai.llmhost.chat.ChatManager
import com.prismai.llmhost.model.DeviceProfiler
import com.prismai.llmhost.model.ModelTierHints
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.ui.UiEventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cancels the exact native generation that produced the active chunk. A retry
 * repeats the same ID; cancelling generation 0 would leave the active session
 * running.
 */
internal suspend fun cancelActiveGeneration(
    generationId: Int,
    cancel: suspend (Int) -> Unit,
    onRetry: (Throwable) -> Unit = {},
    onFailure: (Throwable) -> Unit = {},
): Boolean = runCatching { cancel(generationId) }
    .recoverCatching { error ->
        onRetry(error)
        cancel(generationId)
    }
    .onFailure(onFailure)
    .isSuccess

/**
 * Orchestrates the prompt → token-stream generation lifecycle for chat,
 * continuation, and agent follow-up paths.
 *
 * Owns prompt building, metrics tracking, and the generation Flow pipeline.
 * Lifecycle-coupled operations (mutex locking, foreground notifications,
 * job storage, session counter) are injected as callbacks so
 * [InferenceService] retains control over Android lifecycle boundaries.
 */
class GenerationOrchestrator(
    private val engine: NativeLlmBridge,
    private val uiState: ServiceUiState,
    private val eventBus: UiEventBus,
    private val chatManager: ChatManager,
    private val promptBuilder: PromptBuilder,
    private val metrics: GenerationMetrics,
    private val agentToolRouter: AgentToolRouter,
    private val agentTrace: AgentTrace,
    private val agentToolConfirmation: AgentToolConfirmation,
    private val deviceProfiler: DeviceProfiler,
    private val scope: CoroutineScope,
    // ── Service-coupled callbacks ───────────────────────────────────────
    private val onStartForeground: () -> Unit,
    private val onStopForeground: () -> Unit,
    private val storeGenerationJob: (Job?) -> Unit,
    private val getActiveSession: () -> Long,
    private val incrementSession: () -> Long,
    private val onSaveTranscript: () -> Unit,
    private val onRecordBenchmarkRun: (String, String, GenerationPerformance, String, String?) -> Unit,
    private val onDeferredReload: suspend (String) -> Boolean,
    private val getReloadPending: () -> Boolean,
    private val setReloadPending: (Boolean) -> Unit,
    /** Fired exactly once after a benchmark-preset generation fully completes/cleans up. */
    private val onBenchmarkComplete: () -> Unit = {},
    private val onBeforeChatIdentityChange: suspend (String, suspend () -> Unit) -> Unit = { _, mutation -> mutation() },
) {
    companion object {
        private const val TAG = "GenOrchestrator"
        /** Soft warning threshold only — does not hard-block generation (B4). */
        private const val SOFT_LOW_RAM_MB = 1024L
        private const val TRUNCATION_MAX_DEPTH = 4
        private const val TRUNCATED_STRING_LIMIT = 150
        private const val TRUNCATED_ARRAY_LIMIT = 5

        internal fun mapErrorCodeToUserMessage(errorCode: Int, detail: String?): String {
            return when (NativeErrorCode.fromValue(errorCode)) {
                NativeErrorCode.OK -> detail ?: "native runtime error"
                NativeErrorCode.DEBUG_HOOKS_REJECTED -> "Debug hooks rejected operation"
                NativeErrorCode.HANDLE_INVALID_OR_CLOSED -> "Model is not loaded"
                NativeErrorCode.DEBUG_GENERATION_REJECTED -> "Debug generation rejected"
                NativeErrorCode.PROMPT_DOES_NOT_FIT -> "Prompt exceeds context window limit"
                NativeErrorCode.TOKENIZE_SIZE_FAILED -> "Prompt token count estimation failed"
                NativeErrorCode.CONTEXT_WINDOW_TOO_SMALL -> "Context window too small for prompt"
                NativeErrorCode.TOKENIZE_FAILED -> "Tokenization failed"
                NativeErrorCode.SAMPLER_INIT_FAILED -> "Sampler initialization failed"
                NativeErrorCode.NULL_TOKEN_PRODUCED -> "Null token produced"
                NativeErrorCode.CONTEXT_SHIFT_FAILED -> "Context shift operation failed"
                NativeErrorCode.GRAMMAR_COMPILE_FAILED -> "Grammar compilation failed"
                NativeErrorCode.NO_CONTINUATION_CONTEXT -> "Cannot continue without prior context"
                NativeErrorCode.NATIVE_EXCEPTION -> "Native exception"
                NativeErrorCode.MODEL_LOAD_FAILED -> "Failed to load model"
                null -> when {
                    errorCode in 5000..5999 -> "Native decode error (code $errorCode)"
                    else -> if (errorCode > 0) {
                        "Error code $errorCode ($detail)"
                    } else {
                        detail ?: "native runtime error"
                    }
                }
            }
        }

        internal fun resolveFollowUpChainOrAbort(
            agentTrace: AgentTrace,
            suppliedChainId: Long?,
            modelAvailable: Boolean,
        ): Long? {
            val resolved = suppliedChainId ?: agentTrace.activeChainId
            if (!modelAvailable) {
                agentTrace.abortTrace(resolved, "Follow-up aborted: no model selected")
                return null
            }
            return resolved
        }
    }

    // ── Flow deduplication types ─────────────────────────────────────────

    private data class GenerationFlowConfig(
        val errorLabel: String,
        val hasTerminalErrorEvent: Boolean = true,
        val checkEmptyStart: Boolean = true,
        val logMemory: Boolean = false,
        val baselineMemoryUsed: Long = 0L,
        val checkReload: Boolean = false,
        val enableQualityGuard: Boolean = false,
        val isCodingPreset: Boolean = false,
        val notifyBenchmarkComplete: Boolean = false,
    )

    data class GenerationFlowResult(
        val finalReason: String,
        val finalPerformance: GenerationPerformance,
        val finalOutput: String,
        val generatedTokens: Int,
        val terminalDetail: String? = null,
    )

    // ── Public API ──────────────────────────────────────────────────────

    /** Main entry point for chat generation. Acquires [InferenceService]'s operation mutex itself. */
    suspend fun generate(
        prompt: String,
        benchmarkPreset: BenchmarkPreset? = null,
    ) {
        val baseSettings = uiState.generationSettings.value.clamped()

        // ── Direct NL → agent tool call path ────────────────────────────
        if (benchmarkPreset == null && baseSettings.agentEnabled && uiState.currentModel.value != null) {
            val directToolCall = agentToolRouter.directToolCall(prompt)
            if (directToolCall != null) {
                chatManager.appendTranscriptMessage(TranscriptRole.USER, prompt)
                val chainId = agentTrace.beginChain(prompt)
                agentToolRouter.activeAgentToolHistory.clear()
                agentToolRouter.handleToolCall(directToolCall, prompt, depth = 0, chainId = chainId)
                return
            }
        }

        if (uiState.currentModel.value == null) {
            eventBus.publish("Select a model before sending a prompt")
            return
        }

        // Soft preflight: do not block, but surface low-RAM risk (B4).
        val freeMb = deviceProfiler.deviceMemorySnapshot().availableMb
        if (freeMb in 1 until SOFT_LOW_RAM_MB) {
            eventBus.publish(
                "Low free RAM (${freeMb} MB). Generation may fail or ERROR; free memory if possible.",
            )
        }
        if (benchmarkPreset?.id == "coding") {
            ModelTierHints.codingBenchmarkWarning(uiState.currentModel.value)
                ?.let { eventBus.publish(it) }
        }

        if (benchmarkPreset != null) {
            startBenchmarkChat(benchmarkPreset.name)
        }

        if (benchmarkPreset == null && uiState.transcript.value.isEmpty()) {
            runCatching { engine.resetConversation() }
        }

        // Preset overrides apply only to this generation call — never write them into persisted UI settings.
        val settings = benchmarkPreset?.applySettingsOverrides(baseSettings) ?: baseSettings
        if (baseSettings != uiState.generationSettings.value) {
            uiState._generationSettings.value = baseSettings
        }

        val agentEnabled = settings.agentEnabled
        val agentChainId = if (agentEnabled) {
            agentTrace.beginChain(prompt)
        } else {
            null
        }
        if (agentEnabled) {
            agentToolRouter.activeAgentToolHistory.clear()
        }

        val memoryContext = if (benchmarkPreset == null) promptBuilder.buildMemoryContext(prompt) else ""
        // Structured, role-preserving messages for normal chat. The legacy string
        // path stays for agent turns and benchmark presets, which build their own
        // protocol prompts and must keep byte-identical behavior.
        val chatMessages: List<ChatMessage>? = if (benchmarkPreset == null && !agentEnabled) {
            promptBuilder.buildMessages(
                newPrompt = prompt,
                transcript = uiState.transcript.value,
                activeAssistantTranscriptId = null,
                memoryContext = memoryContext,
                tokenBudget = GenerationBudget.calculateNonAgentTokenBudget(
                    contextLength = settings.contextLength,
                    maxTokens = settings.maxTokens,
                ),
            )
        } else {
            null
        }
        val enginePrompt = if (benchmarkPreset == null) {
            if (agentEnabled) {
                val maxHistoryChars = GenerationBudget.calculateAgentHistoryCharBudget(
                    contextLength = settings.contextLength,
                    maxTokens = settings.maxTokens,
                    userPromptChars = prompt.length,
                    instructionBlockChars = AgentToolProtocol.instructionBlock().length,
                    extraContextChars = memoryContext.length,
                )
                val history = getFormattedHistoryForAgent(emptySet(), maxHistoryChars = maxHistoryChars)
                val historyWithMemory = buildString {
                    append(history)
                    if (memoryContext.isNotEmpty()) {
                        if (history.isNotEmpty()) appendLine()
                        append(memoryContext)
                    }
                }
                AgentToolProtocol.buildPrompt(prompt, historyWithMemory)
            } else {
                // Telemetry/label for the structured path; roles are carried by chatMessages.
                chatMessages.orEmpty().joinToString("\n") { "${it.role}: ${it.content}" }
            }
        } else {
            prompt
        }

        metrics.activeBenchmarkPreset = benchmarkPreset
        uiState._benchmarkStatus.value = benchmarkPreset?.let { preset ->
            BenchmarkStatus(
                isRunning = true,
                presetId = preset.id,
                presetName = preset.name,
                startedAt = System.currentTimeMillis(),
            )
        } ?: BenchmarkStatus()

        val session = incrementSession()

        uiState._generationPerformance.value = null
        chatManager.appendTranscriptMessage(TranscriptRole.USER, prompt)
        val assistantMessageId = chatManager.appendTranscriptMessage(TranscriptRole.ASSISTANT, "")
        chatManager.activeAssistantTranscriptId = assistantMessageId
        uiState.streamState.beginGeneration()
        val baselineMemoryUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        uiState._isGenerating.value = true
        uiState._runtimeStatus.value = RuntimeStatus.GENERATING
        onStartForeground()

        val startedAt = SystemClock.elapsedRealtime()
        metrics.activePrompt = enginePrompt
        metrics.activeStartedAt = startedAt
        metrics.activeFirstTokenAt = null
        metrics.activeTokens = 0
        metrics.activePromptTokens = 0
        metrics.activeSettings = settings

        Log.d(TAG, "generate start promptLength=${enginePrompt.length} model=${uiState.currentModel.value} session=$session settings=$settings")

        val job = runGenerationFlow(
            session = session,
            enginePrompt = enginePrompt,
            settings = settings,
            assistantMessageId = assistantMessageId,
            grammar = if (agentEnabled) AgentToolProtocol.toolGrammar else null,
            startedAt = startedAt,
            messages = chatMessages,
            agentChainId = agentChainId,
            config = GenerationFlowConfig(
                errorLabel = "generation",
                checkReload = true,
                logMemory = true,
                baselineMemoryUsed = baselineMemoryUsed,
                enableQualityGuard = benchmarkPreset?.enableQualityGuard == true,
                isCodingPreset = benchmarkPreset?.id == "coding",
                notifyBenchmarkComplete = benchmarkPreset != null,
            ),
        ) { result ->
            val agentToolCall = if (agentEnabled && result.finalReason == "EOF") {
                AgentToolProtocol.parseToolCall(result.finalOutput)
            } else null

            if (agentToolCall == null) {
                onRecordBenchmarkRun(
                    if (agentEnabled) prompt else enginePrompt,
                    result.finalOutput,
                    result.finalPerformance,
                    result.finalReason,
                    result.terminalDetail,
                )
            }
            if (agentEnabled) {
                agentTrace.recordGenerationTurn(
                    terminalReason = result.finalReason,
                    generatedTokens = result.generatedTokens,
                    hasToolCall = agentToolCall != null,
                    chainId = agentChainId,
                )
            }

            val completedPreset = metrics.activeBenchmarkPreset
            metrics.clear()
            uiState._benchmarkStatus.value = BenchmarkStatus()
            val reasoningPrefix = if (agentToolCall != null) {
                AgentToolProtocol.extractReasoningPrefix(result.finalOutput)
            } else ""

            chatManager.updateTranscriptMessage(assistantMessageId, if (agentToolCall == null) result.finalOutput else reasoningPrefix)

            // Benchmark queue drain handled by InferenceService

            if (agentToolCall != null) {
                agentToolRouter.handleToolCall(
                    agentToolCall,
                    prompt,
                    depth = 0,
                    chainId = agentChainId,
                )
            }
        }

        storeGenerationJob(job)
    }

    /** Continuation generation. Acquires [InferenceService]'s operation mutex via [com.prismai.llmhost.InferenceService.continueGenerationSafely]. */
    fun continueGeneration() {
        val agentChainId = agentTrace.activeChainId
        agentChainId?.let { agentTrace.releaseChainPreservation(it) }
        if (uiState.currentModel.value == null) {
            eventBus.publish("Select a model before continuing")
            return
        }
        val lastPerformance = uiState.generationPerformance.value
        if (lastPerformance?.terminalReason != "MAX_TOKENS") {
            eventBus.publish("The last response did not stop at the token limit")
            return
        }
        val transcript = uiState.transcript.value
        val assistantMessage = transcript.lastOrNull {
            it.role == TranscriptRole.ASSISTANT && it.text.isNotBlank()
        }
        if (assistantMessage == null) {
            eventBus.publish("No assistant response to continue")
            return
        }

        metrics.activeBenchmarkPreset = null
        uiState._benchmarkStatus.value = BenchmarkStatus()
        val session = incrementSession()
        val settings = uiState.generationSettings.value.clamped()
        uiState._generationPerformance.value = null
        chatManager.activeAssistantTranscriptId = assistantMessage.id
        uiState.streamState.beginGeneration(initialText = assistantMessage.text)
        uiState._isGenerating.value = true
        uiState._runtimeStatus.value = RuntimeStatus.GENERATING
        onStartForeground()

        val startedAt = SystemClock.elapsedRealtime()
        metrics.activePrompt = "[continue]"
        metrics.activeStartedAt = startedAt
        metrics.activeFirstTokenAt = null
        metrics.activeTokens = 0
        metrics.activePromptTokens = 0
        metrics.activeSettings = settings

        val agentEnabled = settings.agentEnabled
        val job = runGenerationFlow(
            session = session,
            enginePrompt = "",
            settings = settings,
            assistantMessageId = assistantMessage.id,
            continueFromContext = true,
            grammar = if (agentEnabled) AgentToolProtocol.toolGrammar else null,
            agentChainId = agentChainId,
            startedAt = startedAt,
            config = GenerationFlowConfig(errorLabel = "continuation"),
        ) { result ->
            val agentToolCall = if (agentEnabled && result.finalReason == "EOF") {
                AgentToolProtocol.parseToolCall(result.finalOutput)
            } else null

            if (agentToolCall == null) {
                onRecordBenchmarkRun(
                    "[continue]",
                    result.finalOutput,
                    result.finalPerformance,
                    result.finalReason,
                    result.terminalDetail,
                )
            }
            if (agentEnabled) {
                agentTrace.recordGenerationTurn(
                    terminalReason = result.finalReason,
                    generatedTokens = result.generatedTokens,
                    hasToolCall = agentToolCall != null,
                    chainId = agentChainId,
                )
            }
            val reasoningPrefix = if (agentToolCall != null) {
                AgentToolProtocol.extractReasoningPrefix(result.finalOutput)
            } else ""

            metrics.clear()
            chatManager.updateTranscriptMessage(assistantMessage.id, if (agentToolCall == null) result.finalOutput else reasoningPrefix)

            if (agentToolCall != null) {
                agentToolRouter.handleToolCall(
                    agentToolCall,
                    "[continue]",
                    depth = 0,
                    chainId = agentChainId,
                )
            }
        }

        storeGenerationJob(job)
    }

    /** Agent follow-up generation after a tool result. Acquires [InferenceService]'s operation mutex itself. */
    fun startFollowUp(
        originalPrompt: String,
        toolResult: AgentToolResult,
        depth: Int,
        chainId: Long? = null,
    ) {
        val modelAvailable = uiState.currentModel.value != null
        if (!modelAvailable) {
            resolveFollowUpChainOrAbort(
                agentTrace = agentTrace,
                suppliedChainId = chainId,
                modelAvailable = false,
            )
            return
        }
        val agentChainId = chainId ?: agentTrace.activeChainId
        if (agentChainId != null && !agentTrace.isCurrentChain(agentChainId)) return
        // Reserve the chat identity before any follow-up setup or transcript
        // append. Chat transitions must either see this reservation or make
        // the chain owner check fail before the append below.
        uiState._isGenerating.value = true
        if (agentChainId != null && !agentTrace.isCurrentChain(agentChainId)) {
            uiState._isGenerating.value = false
            return
        }
        val session = incrementSession()
        val settings = uiState.generationSettings.value.clamped()

        // ── Safety Verification Gate ────────────────────────────────────
        val profile = deviceProfiler.getCachedProfile()
        val thermalStatus = profile.thermalStatus?.lowercase()
        val isHot = thermalStatus in setOf("severe", "critical", "emergency", "shutdown")
        val isBatteryLow = (profile.batteryPercent ?: 100) < 15 && profile.isCharging != true
        val isAgentDisabled = !settings.agentEnabled

        if (isHot || isBatteryLow || isAgentDisabled) {
            val reason = when {
                isAgentDisabled -> "Agentic tools toggled off"
                isHot -> "Device is too hot (thermal state: $thermalStatus)"
                else -> "Battery level is low (${profile.batteryPercent}%)"
            }
            agentTrace.abortTrace(agentChainId, "Follow-up aborted: $reason")
            chatManager.appendTranscriptMessage(
                TranscriptRole.ASSISTANT,
                "⚠️ Agent follow-up aborted: $reason. Stopping execution to protect device resources.",
            )
            uiState._isGenerating.value = false
            return
        }

        uiState._generationPerformance.value = null
        val assistantMessageId = chatManager.appendTranscriptMessage(TranscriptRole.ASSISTANT, "")
        chatManager.activeAssistantTranscriptId = assistantMessageId
        uiState.streamState.beginGeneration()
        uiState._runtimeStatus.value = RuntimeStatus.GENERATING
        onStartForeground()

        val startedAt = SystemClock.elapsedRealtime()
        metrics.activePrompt = "[agent tool follow-up]"
        metrics.activeStartedAt = startedAt
        metrics.activeFirstTokenAt = null
        metrics.activeTokens = 0
        metrics.activePromptTokens = 0
        metrics.activeSettings = settings
        val truncatedRaw = truncateToolResultForBudget(toolResult, originalPrompt, settings)      // truncate RAW first
        val sanitized = ToolInputSanitizer.sanitizeResult(truncatedRaw) // sanitize ONCE
        val toolPayloadChars = with(AgentToolProtocol) { sanitized.toJson().toString().length }   // measure SANITIZED payload
        val maxHistoryChars = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = settings.contextLength,
            maxTokens = settings.maxTokens,
            userPromptChars = originalPrompt.length,
            toolResultChars = toolPayloadChars,
            instructionBlockChars = GenerationBudget.AGENT_FOLLOW_UP_PREAMBLE_CHARS,
        )
        val enginePrompt = AgentToolProtocol.buildToolResultPrompt(
            originalPrompt,
            sanitized,
            getFormattedHistoryForAgent(setOf(assistantMessageId), maxHistoryChars = maxHistoryChars),
        )

        val job = runGenerationFlow(
            session = session,
            enginePrompt = enginePrompt,
            settings = settings,
            assistantMessageId = assistantMessageId,
            grammar = if (settings.agentEnabled) AgentToolProtocol.toolGrammar else null,
            startedAt = startedAt,
            agentChainId = agentChainId,
            config = GenerationFlowConfig(
                errorLabel = "agent follow-up",
                hasTerminalErrorEvent = false,
                checkEmptyStart = false,
                checkReload = true,
            ),
        ) { result ->
            val nextToolCall = if (result.finalReason == "EOF") AgentToolProtocol.parseToolCall(result.finalOutput) else null
            agentTrace.recordGenerationTurn(
                terminalReason = result.finalReason,
                generatedTokens = result.generatedTokens,
                hasToolCall = nextToolCall != null,
                chainId = agentChainId,
            )
            metrics.clear()
            val reasoningPrefix = if (nextToolCall != null) {
                AgentToolProtocol.extractReasoningPrefix(result.finalOutput)
            } else ""

            chatManager.updateTranscriptMessage(assistantMessageId, if (nextToolCall == null) result.finalOutput else reasoningPrefix)

            if (nextToolCall != null) {
                agentToolRouter.handleToolCall(
                    nextToolCall,
                    originalPrompt,
                    depth,
                    chainId = agentChainId,
                )
            }
        }

        storeGenerationJob(job)
    }

    // ── Shared generation flow ─────────────────────────────────────────

    /**
     * Runs the common [engine.generate] → .onEach → .catch → .onCompletion
     * pipeline shared by [generate], [continueGeneration], and [startFollowUp].
     *
     * @param onComplete receives the terminal [GenerationFlowResult]; called
     *   from within [onCompletion] *before* the shared cleanup tail so the
     *   caller can update the transcript, finalize agent traces, record
     *   benchmarks, and dispatch follow-up tool calls.
     */
    private fun runGenerationFlow(
        session: Long,
        enginePrompt: String,
        settings: GenerationSettings,
        assistantMessageId: Long,
        grammar: String?,
        startedAt: Long,
        config: GenerationFlowConfig,
        messages: List<ChatMessage>? = null,
        continueFromContext: Boolean = false,
        agentChainId: Long? = null,
        onComplete: (GenerationFlowResult) -> Unit,
    ): Job {
        var firstTokenAt: Long? = null
        var generatedTokens = 0
        var promptTokens = 0
        var terminalState = GenerationTerminalReducer.TerminalState()
        var qualityAbortRequested = false
        var lastPerformancePublishAt = 0L
        var lastTranscriptUpdateAt = 0L
        var lastQualityCheckAt = 0L

        val generationStream = if (messages != null) {
            engine.generateChat(messages, settings, grammar = grammar)
        } else {
            engine.generate(enginePrompt, settings, continueFromContext = continueFromContext, grammar = grammar)
        }

        return generationStream
            .onEach { chunk ->
                if (session != getActiveSession()) {
                    Log.d(TAG, "ignored stale ${config.errorLabel} chunk session=$session active=${getActiveSession()}")
                    return@onEach
                }
                val uiChunk = Utf8TextPipeline.normalizeChunk(chunk)
                val now = SystemClock.elapsedRealtime()
                if (promptTokens == 0 && chunk.promptTokens > 0) {
                    promptTokens = chunk.promptTokens
                    metrics.activePromptTokens = promptTokens
                }
                if (!uiChunk.isTerminal && uiChunk.tokenCount > 0) {
                    if (firstTokenAt == null) {
                        firstTokenAt = now
                        metrics.activeFirstTokenAt = now
                    }
                    generatedTokens += uiChunk.tokenCount
                    metrics.activeTokens = generatedTokens
                }
                if (uiChunk.isTerminal) {
                    terminalState = GenerationTerminalReducer.mergeTerminalChunk(
                        terminalState,
                        uiChunk.terminalReason,
                    )
                }
                val publishedText = uiState.streamState.append(uiChunk)
                if (uiChunk.isTerminal || now - lastTranscriptUpdateAt >= 75L) {
                    lastTranscriptUpdateAt = now
                    chatManager.updateTranscriptMessage(assistantMessageId, publishedText ?: uiState.streamState.snapshotText())
                }
                if (uiChunk.isTerminal || now - lastPerformancePublishAt >= 1000L) {
                    lastPerformancePublishAt = now
                    metrics.publishPerformance(
                        startedAt = startedAt,
                        firstTokenAt = firstTokenAt,
                        now = now,
                        generatedTokens = generatedTokens,
                        settings = settings,
                        terminalReason = if (uiChunk.isTerminal) {
                            terminalState.reason ?: uiChunk.terminalReason
                        } else {
                            null
                        },
                        promptTokens = promptTokens,
                        ttftMs = chunk.ttftMs,
                        activeThreads = chunk.activeThreads,
                        ndkTps = chunk.tokensPerSec,
                    )
                }
                // Mid-stream quality guard (benchmark presets only when enabled).
                if (
                    config.enableQualityGuard &&
                    !qualityAbortRequested &&
                    !uiChunk.isTerminal &&
                    generatedTokens >= QualityGuard.MIN_TOKENS_BEFORE_CHECK &&
                    now - lastQualityCheckAt >= 200L
                ) {
                    lastQualityCheckAt = now
                    val snapshot = publishedText ?: uiState.streamState.snapshotText()
                    val verdict = QualityGuard.evaluate(
                        text = snapshot,
                        generatedTokens = generatedTokens,
                        isCodingPreset = config.isCodingPreset,
                    )
                    if (verdict.abort) {
                        qualityAbortRequested = true
                        val baseDetail = "${verdict.reasonCode}: ${verdict.detail}".take(400)
                        // Sticky before cancel so a native CANCELLED chunk cannot overwrite it.
                        terminalState = GenerationTerminalReducer.TerminalState(
                            reason = "QUALITY_ABORT",
                            detail = baseDetail,
                        )
                        Log.w(TAG, "quality_abort session=$session detail=$baseDetail")
                        eventBus.publish("Benchmark stopped: quality abort (${verdict.reasonCode})")
                        val cancelOk = cancelActiveGeneration(
                            generationId = uiChunk.generationId,
                            cancel = { generationId -> engine.cancelGeneration(generationId) },
                            onRetry = { error -> Log.w(TAG, "quality abort cancel retry", error) },
                            onFailure = { error -> Log.w(TAG, "quality abort cancel failed", error) },
                        )
                        if (!cancelOk) {
                            // Keep QUALITY_ABORT; annotate that native cancel failed.
                            terminalState = terminalState.copy(
                                detail = listOf(baseDetail, "quality_abort_cancel_failed")
                                    .joinToString(";")
                                    .take(400),
                            )
                        }
                    }
                }
                if (
                    config.hasTerminalErrorEvent &&
                    uiChunk.isTerminal &&
                    uiChunk.terminalReason == "ERROR" &&
                    terminalState.reason != "QUALITY_ABORT"
                ) {
                    uiState._runtimeStatus.value = RuntimeStatus.ERROR
                    val mappedMessage = mapErrorCodeToUserMessage(uiChunk.errorCode, terminalState.detail)
                    eventBus.publish(
                        "${config.errorLabel.replaceFirstChar { it.uppercase() }} failed: $mappedMessage",
                    )
                }
            }
            .catch { error ->
                if (error !is CancellationException && session == getActiveSession()) {
                    Log.e(TAG, "${config.errorLabel} failed", error)
                    if (terminalState.reason != "QUALITY_ABORT") {
                        uiState._runtimeStatus.value = RuntimeStatus.ERROR
                        terminalState = GenerationTerminalReducer.TerminalState(
                            reason = "ERROR",
                            detail = "exception:${error::class.java.simpleName}:${error.message?.take(160).orEmpty()}",
                        )
                    }
                    metrics.publishPerformance(
                        startedAt = startedAt,
                        firstTokenAt = firstTokenAt,
                        now = SystemClock.elapsedRealtime(),
                        generatedTokens = generatedTokens,
                        settings = settings,
                        terminalReason = terminalState.reason,
                        promptTokens = promptTokens,
                    )
                    val detail = terminalState.detail ?: error.message ?: error::class.java.simpleName
                    eventBus.publish(
                        "${config.errorLabel.replaceFirstChar { it.uppercase() }} failed: $detail",
                    )
                }
            }
            .onCompletion { cause ->
                val ownsAgentChain = agentChainId == null || agentTrace.isCurrentChain(agentChainId)
                if (session != getActiveSession() || !ownsAgentChain) {
                    if (agentChainId != null && agentTrace.isCurrentChain(agentChainId)) {
                        agentTrace.addChainTokens(generatedTokens)
                        val abortReason = if (cause is CancellationException) {
                            "Generation cancelled"
                        } else {
                            "Generation superseded"
                        }
                        agentTrace.finalizeStaleOwnedTrace(
                            chainId = agentChainId,
                            abortReason = abortReason,
                        )
                    }
                    Log.d(TAG, "ignored stale ${config.errorLabel} completion session=$session active=${getActiveSession()}")
                    return@onCompletion
                }
                if (cause is CancellationException) {
                    Log.d(TAG, "${config.errorLabel} cancelled session=$session")
                }
                val resolved = GenerationTerminalReducer.resolveFinal(
                    current = terminalState,
                    causeIsCancellation = cause is CancellationException,
                    checkEmptyStart = config.checkEmptyStart,
                    generatedTokens = generatedTokens,
                    userStopLikely = uiState.runtimeStatus.value == RuntimeStatus.CANCELLING,
                )
                terminalState = resolved
                val finalReason = resolved.reason ?: "EOF"
                val terminalDetail = resolved.detail
                if (finalReason == "ERROR" && terminalDetail == "generation_did_not_start") {
                    uiState._runtimeStatus.value = RuntimeStatus.ERROR
                    eventBus.publish(
                        "${config.errorLabel.replaceFirstChar { it.uppercase() }} did not start (generation_did_not_start)",
                    )
                }
                val finalPerformance = metrics.publishPerformance(
                    startedAt = startedAt,
                    firstTokenAt = firstTokenAt,
                    now = SystemClock.elapsedRealtime(),
                    generatedTokens = generatedTokens,
                    settings = settings,
                    terminalReason = finalReason,
                    promptTokens = promptTokens,
                )
                val finalOutput = uiState.streamState.snapshotText()

                onComplete(
                    GenerationFlowResult(
                        finalReason = finalReason,
                        finalPerformance = finalPerformance,
                        finalOutput = finalOutput,
                        generatedTokens = generatedTokens,
                        terminalDetail = terminalDetail,
                    ),
                )

                // ── Shared cleanup tail ────────────────────────────────────
                if (config.logMemory) {
                    val runtime = Runtime.getRuntime()
                    val usedMem = runtime.totalMemory() - runtime.freeMemory()
                    Log.d(TAG, "generation_mem used_kb=${usedMem / 1024} max_kb=${runtime.maxMemory() / 1024} baseline_kb=${config.baselineMemoryUsed / 1024}")
                }
                uiState._isGenerating.value = false
                storeGenerationJob(null)
                chatManager.activeAssistantTranscriptId = null
                onSaveTranscript()
                onStopForeground()
                if (uiState.runtimeStatus.value == RuntimeStatus.GENERATING ||
                    uiState.runtimeStatus.value == RuntimeStatus.CANCELLING
                ) {
                    uiState._runtimeStatus.value = RuntimeStatus.IDLE
                }
                if (config.checkReload && getReloadPending()) {
                    setReloadPending(false)
                    val modelId = uiState.currentModel.value
                    if (modelId != null) {
                        eventBus.publish("Reloading model for context/backend settings")
                        scope.launch { onDeferredReload(modelId) }
                    }
                }
                // Benchmark queue drain: invoke only from the preset path (never
                // from agent follow-ups) and only after cleanup so the next
                // queued preset cannot race this generation's teardown.
                if (config.notifyBenchmarkComplete) {
                    onBenchmarkComplete()
                }
            }
            .launchIn(scope)
    }

    private fun getFormattedHistoryForAgent(excludeIds: Set<Long>, maxHistoryChars: Int): String {
        if (maxHistoryChars <= 0) return ""
        val history = uiState.transcript.value.filter { message ->
            message.text.isNotBlank() && message.id !in excludeIds
        }
        if (history.isEmpty()) return ""
        val selected = ArrayDeque<TranscriptMessage>()
        var chars = 0
        val safeCharCap = maxHistoryChars
        for (message in history.asReversed()) {
            val formatted = message.asPromptLine()
            if (chars + formatted.length > safeCharCap) break
            selected.addFirst(message)
            chars += formatted.length
        }
        return buildString {
            selected.forEach { message -> appendLine(message.asPromptLine()) }
        }
    }

    private fun TranscriptMessage.asPromptLine(): String =
        when (role) {
            TranscriptRole.USER -> "User: $text"
            TranscriptRole.ASSISTANT -> "Assistant: $text"
            TranscriptRole.TOOL -> summary?.let { "Tool: $it" } ?: "Tool: $text"
        }

    private suspend fun startBenchmarkChat(name: String) {
        onBeforeChatIdentityChange("Benchmark chat changed") {
            chatManager.createChatInternal("Benchmark - $name", publishEvent = false)
            runCatching { engine.resetConversation() }
        }
    }

    // ── Tool result truncation ──────────────────────────────────────────

    private fun truncateToolResultForBudget(
        result: AgentToolResult,
        originalPrompt: String,
        settings: GenerationSettings,
    ): AgentToolResult {
        val transcriptHistoryChars = uiState.transcript.value.sumOf { it.text.length }
        val estimatedHistoryTokens = transcriptHistoryChars / 4.0
        val systemPromptOverhead = AgentToolProtocol.instructionBlock().length
        val fixedOverhead = originalPrompt.length + 200
        val overheadTokens = (systemPromptOverhead + fixedOverhead) / 4.0
        val remainingBudget = settings.contextLength - estimatedHistoryTokens - overheadTokens
        val limit = (settings.contextLength * 0.45).coerceIn(800.0, 3000.0)
        val maxResultTokens = kotlin.math.max(100.0, remainingBudget * 0.6).coerceAtMost(limit).toInt()
        val maxResultChars = maxResultTokens * 4

        val details = result.details
        val serializedDetails = details.toString()
        if (serializedDetails.length <= maxResultChars) return result

        val name = result.call.name
        val truncatedDetails = JSONObject()
        var wasTruncated = false

        val searchLimit = if (maxResultTokens > 1500) 6 else if (maxResultTokens > 800) 4 else 3
        val searchSnippetLimit = if (maxResultTokens > 1500) 250 else if (maxResultTokens > 800) 180 else 120
        val listLimit = if (maxResultTokens > 1500) 15 else if (maxResultTokens > 800) 10 else 5

        when (name) {
            "search_chats" -> {
                val results = details.optJSONArray("results")
                if (results != null) {
                    val truncatedResults = JSONArray()
                    val limitCount = minOf(results.length(), searchLimit)
                    for (i in 0 until limitCount) {
                        val item = results.optJSONObject(i) ?: continue
                        truncatedResults.put(
                            JSONObject()
                                .put("chat_id", item.optString("chat_id"))
                                .put("title", item.optString("title"))
                                .put("snippet", item.optString("snippet").let { s ->
                                    if (s.length > searchSnippetLimit) s.substring(0, searchSnippetLimit) + " (Truncated for context size)" else s
                                }),
                        )
                    }
                    truncatedDetails.put("results", truncatedResults)
                    truncatedDetails.put("truncated", true)
                    truncatedDetails.put("note", "Results truncated for context. Full data available in app if needed.")
                    wasTruncated = true
                }
            }
            "list_installed_models", "list_curated_downloadable_models" -> {
                val models = details.optJSONArray("models")
                if (models != null) {
                    val truncatedModels = JSONArray()
                    val limitCount = minOf(models.length(), listLimit)
                    for (i in 0 until limitCount) {
                        val item = models.optJSONObject(i) ?: continue
                        truncatedModels.put(
                            JSONObject()
                                .put("model_id", item.optString("model_id"))
                                .put("name", item.optString("name"))
                                .put("size_bytes", item.optLong("size_bytes", 0L))
                                .put("is_active", item.optBoolean("is_active", false))
                                .put("readiness", item.optString("readiness")),
                        )
                    }
                    truncatedDetails.put("models", truncatedModels)
                    truncatedDetails.put("count", details.optInt("count", models.length()))
                    truncatedDetails.put("truncated", true)
                    truncatedDetails.put("note", "Results truncated for context. Full data available in app if needed.")
                    wasTruncated = true
                }
            }
            "list_benchmark_runs" -> {
                val runs = details.optJSONArray("runs")
                if (runs != null) {
                    val truncatedRuns = JSONArray()
                    val limitCount = minOf(runs.length(), listLimit)
                    for (i in 0 until limitCount) {
                        val item = runs.optJSONObject(i) ?: continue
                        truncatedRuns.put(
                            JSONObject()
                                .put("preset_id", item.optString("preset_id"))
                                .put("model_id", item.optString("model_id"))
                                .put("tokens_per_second", item.optDouble("tokens_per_second", 0.0))
                                .put("terminal_reason", item.optString("terminal_reason"))
                                .put("completed_at", item.optLong("completed_at")),
                        )
                    }
                    truncatedDetails.put("runs", truncatedRuns)
                    truncatedDetails.put("total_runs", details.optInt("total_runs", runs.length()))
                    truncatedDetails.put("truncated", true)
                    truncatedDetails.put("note", "Results truncated for context. Full data available in app if needed.")
                    wasTruncated = true
                }
            }
        }

        val finalDetails = if (wasTruncated) {
            truncatedDetails
        } else {
            val fallback = JSONObject()
            details.keys().forEach { key ->
                fallback.put(key, truncateBudgetValue(details.opt(key), depth = 0))
            }
            fallback.put("truncated", true)
            fallback.put("note", "Results truncated for context. Full data available in app if needed.")
            fallback
        }

        return result.copy(details = finalDetails)
    }

    private fun truncateBudgetValue(value: Any?, depth: Int): Any? = when {
        value is String && value.length > TRUNCATED_STRING_LIMIT ->
            value.substring(0, TRUNCATED_STRING_LIMIT) + " (Truncated for context size)"
        value is JSONObject && depth < TRUNCATION_MAX_DEPTH -> {
            val nested = JSONObject()
            value.keys().forEach { key ->
                nested.put(key, truncateBudgetValue(value.opt(key), depth + 1))
            }
            nested
        }
        value is JSONArray -> {
            val arr = JSONArray()
            for (i in 0 until minOf(value.length(), TRUNCATED_ARRAY_LIMIT)) {
                arr.put(truncateBudgetValue(value.opt(i), depth + 1))
            }
            arr
        }
        else -> value
    }

}
