package com.prismai.llmhost.generation

import android.os.SystemClock
import android.util.Log
import com.prismai.llmhost.*
import com.prismai.llmhost.agent.AgentToolConfirmation
import com.prismai.llmhost.agent.AgentToolRouter
import com.prismai.llmhost.agent.AgentTrace
import com.prismai.llmhost.chat.ChatManager
import com.prismai.llmhost.model.DeviceProfiler
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
    private val onRecordBenchmarkRun: (String, String, GenerationPerformance, String) -> Unit,
    private val onDeferredReload: suspend (String) -> Boolean,
    private val getReloadPending: () -> Boolean,
    private val setReloadPending: (Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "GenOrchestrator"
        private const val MAX_CHAT_MESSAGES_BEFORE_CONTINUATION = 60
        private const val MAX_CHAT_CHARS_BEFORE_CONTINUATION = 24_000
    }

    // ── Flow deduplication types ─────────────────────────────────────────

    private data class GenerationFlowConfig(
        val errorLabel: String,
        val hasTerminalErrorEvent: Boolean = true,
        val checkEmptyStart: Boolean = true,
        val logMemory: Boolean = false,
        val baselineMemoryUsed: Long = 0L,
        val checkReload: Boolean = false,
    )

    data class GenerationFlowResult(
        val finalReason: String,
        val finalPerformance: GenerationPerformance,
        val finalOutput: String,
        val generatedTokens: Int,
    )

    // ── Public API ──────────────────────────────────────────────────────

    /** Main entry point for chat generation. Called from within [InferenceService]'s mutex lock. */
    fun generate(
        prompt: String,
        benchmarkPreset: BenchmarkPreset? = null,
    ) {
        // ── Direct NL → agent tool call path ────────────────────────────
        if (benchmarkPreset == null) {
            val directToolCall = agentToolRouter.directToolCall(prompt)
            if (directToolCall != null) {
                ensureChatWithinLengthBudget()
                chatManager.appendTranscriptMessage(TranscriptRole.USER, prompt)
                agentTrace.reset()
                agentTrace.activeAgentChainPrompt = prompt
                agentTrace.activeAgentChainStartTime = System.currentTimeMillis()
                agentTrace.activeAgentChainTokens = 0
                agentToolRouter.activeAgentToolHistory.clear()
                agentToolRouter.handleToolCall(directToolCall, prompt, depth = 0)
                return
            }
        }

        if (uiState.currentModel.value == null) {
            eventBus.publish("Select a model before sending a prompt")
            return
        }

        if (benchmarkPreset != null) {
            startBenchmarkChat(benchmarkPreset.name)
        } else {
            ensureChatWithinLengthBudget()
        }

        if (benchmarkPreset == null && uiState.transcript.value.isEmpty()) {
            scope.launch { runCatching { engine.resetConversation() } }
        }

        val agentEnabled = uiState.generationSettings.value.agentEnabled
        if (agentEnabled) {
            agentTrace.reset()
            agentTrace.activeAgentChainPrompt = prompt
            agentTrace.activeAgentChainStartTime = System.currentTimeMillis()
            agentTrace.activeAgentChainTokens = 0
            agentToolRouter.activeAgentToolHistory.clear()
        }

        val memoryContext = if (benchmarkPreset == null) promptBuilder.buildMemoryContext(prompt) else ""
        val enginePrompt = if (benchmarkPreset == null) {
            if (agentEnabled) {
                AgentToolProtocol.buildPrompt(prompt, getFormattedHistoryForAgent(emptySet()) + memoryContext)
            } else {
                promptBuilder.buildPromptWithRecentContext(
                    newPrompt = prompt,
                    transcript = uiState.transcript.value,
                    activeAssistantTranscriptId = null,
                )
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
        val baseSettings = uiState.generationSettings.value.clamped()
        val settings = benchmarkPreset?.overrideSettings(baseSettings) ?: baseSettings
        if (baseSettings != uiState.generationSettings.value) {
            uiState._generationSettings.value = baseSettings
        }

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
            config = GenerationFlowConfig(
                errorLabel = "generation",
                checkReload = true,
                logMemory = true,
                baselineMemoryUsed = baselineMemoryUsed,
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
                )
                if (agentEnabled) {
                    agentTrace.activeAgentChainTokens += result.generatedTokens
                    agentTrace.finalizeTrace(success = (result.finalReason == "EOF" || result.finalReason == "MAX_TOKENS"))
                }
            } else if (result.finalReason == "ERROR" && agentEnabled) {
                agentTrace.activeAgentChainTokens += result.generatedTokens
                agentTrace.finalizeTrace(success = false)
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
                agentToolRouter.handleToolCall(agentToolCall, prompt, depth = 0)
            }
        }

        storeGenerationJob(job)
    }

    /** Continuation generation. Called from within [InferenceService]'s mutex lock. */
    fun continueGeneration() {
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
            startedAt = startedAt,
            config = GenerationFlowConfig(errorLabel = "continuation"),
        ) { result ->
            val agentToolCall = if (agentEnabled && result.finalReason == "EOF") {
                AgentToolProtocol.parseToolCall(result.finalOutput)
            } else null

            if (agentToolCall == null) {
                onRecordBenchmarkRun("[continue]", result.finalOutput, result.finalPerformance, result.finalReason)
            }
            val reasoningPrefix = if (agentToolCall != null) {
                AgentToolProtocol.extractReasoningPrefix(result.finalOutput)
            } else ""

            metrics.clear()
            chatManager.updateTranscriptMessage(assistantMessage.id, if (agentToolCall == null) result.finalOutput else reasoningPrefix)

            if (agentToolCall != null) {
                agentToolRouter.handleToolCall(agentToolCall, "[continue]", depth = 0)
            }
        }

        storeGenerationJob(job)
    }

    /** Agent follow-up generation after a tool result. Called from within [InferenceService]'s mutex lock. */
    fun startFollowUp(
        originalPrompt: String,
        toolResult: AgentToolResult,
        depth: Int,
    ) {
        if (uiState.currentModel.value == null) return
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
            agentTrace.finalizeTrace(success = false, abortReason = "Follow-up aborted: $reason")
            chatManager.appendTranscriptMessage(
                TranscriptRole.ASSISTANT,
                "⚠️ Agent follow-up aborted: $reason. Stopping execution to protect device resources.",
            )
            return
        }

        uiState._generationPerformance.value = null
        val assistantMessageId = chatManager.appendTranscriptMessage(TranscriptRole.ASSISTANT, "")
        chatManager.activeAssistantTranscriptId = assistantMessageId
        uiState.streamState.beginGeneration()
        uiState._isGenerating.value = true
        uiState._runtimeStatus.value = RuntimeStatus.GENERATING
        onStartForeground()

        val startedAt = SystemClock.elapsedRealtime()
        metrics.activePrompt = "[agent tool follow-up]"
        metrics.activeStartedAt = startedAt
        metrics.activeFirstTokenAt = null
        metrics.activeTokens = 0
        metrics.activePromptTokens = 0
        metrics.activeSettings = settings
        val budgetedResult = truncateToolResultForBudget(toolResult, originalPrompt, settings)
        val enginePrompt = AgentToolProtocol.buildToolResultPrompt(
            originalPrompt,
            budgetedResult,
            getFormattedHistoryForAgent(setOf(assistantMessageId)),
        )

        val job = runGenerationFlow(
            session = session,
            enginePrompt = enginePrompt,
            settings = settings,
            assistantMessageId = assistantMessageId,
            grammar = if (settings.agentEnabled) AgentToolProtocol.toolGrammar else null,
            startedAt = startedAt,
            config = GenerationFlowConfig(
                errorLabel = "agent follow-up",
                hasTerminalErrorEvent = false,
                checkEmptyStart = false,
                checkReload = true,
            ),
        ) { result ->
            val nextToolCall = if (result.finalReason == "EOF") AgentToolProtocol.parseToolCall(result.finalOutput) else null
            agentTrace.activeAgentChainTokens += result.generatedTokens
            if (nextToolCall == null) {
                agentTrace.finalizeTrace(success = (result.finalReason == "EOF" || result.finalReason == "MAX_TOKENS"))
            } else if (result.finalReason == "ERROR") {
                agentTrace.finalizeTrace(success = false)
            }
            metrics.clear()
            val reasoningPrefix = if (nextToolCall != null) {
                AgentToolProtocol.extractReasoningPrefix(result.finalOutput)
            } else ""

            chatManager.updateTranscriptMessage(assistantMessageId, if (nextToolCall == null) result.finalOutput else reasoningPrefix)

            if (nextToolCall != null) {
                agentToolRouter.handleToolCall(nextToolCall, originalPrompt, depth)
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
        continueFromContext: Boolean = false,
        onComplete: (GenerationFlowResult) -> Unit,
    ): Job {
        var firstTokenAt: Long? = null
        var generatedTokens = 0
        var promptTokens = 0
        var terminalReason: String? = null
        var lastPerformancePublishAt = 0L
        var lastTranscriptUpdateAt = 0L

        return engine.generate(enginePrompt, settings, continueFromContext = continueFromContext, grammar = grammar)
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
                    terminalReason = uiChunk.terminalReason
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
                        terminalReason = if (uiChunk.isTerminal) uiChunk.terminalReason else null,
                        promptTokens = promptTokens,
                    )
                }
                if (config.hasTerminalErrorEvent && uiChunk.isTerminal && uiChunk.terminalReason == "ERROR") {
                    uiState._runtimeStatus.value = RuntimeStatus.ERROR
                    eventBus.publish("${config.errorLabel.replaceFirstChar { it.uppercase() }} failed in native runtime")
                }
            }
            .catch { error ->
                if (error !is CancellationException && session == getActiveSession()) {
                    Log.e(TAG, "${config.errorLabel} failed", error)
                    uiState._runtimeStatus.value = RuntimeStatus.ERROR
                    terminalReason = "ERROR"
                    metrics.publishPerformance(
                        startedAt = startedAt,
                        firstTokenAt = firstTokenAt,
                        now = SystemClock.elapsedRealtime(),
                        generatedTokens = generatedTokens,
                        settings = settings,
                        terminalReason = terminalReason,
                        promptTokens = promptTokens,
                    )
                    eventBus.publish("${config.errorLabel.replaceFirstChar { it.uppercase() }} failed: ${error.message ?: error::class.java.simpleName}")
                }
            }
            .onCompletion { cause ->
                if (session != getActiveSession()) {
                    Log.d(TAG, "ignored stale ${config.errorLabel} completion session=$session active=${getActiveSession()}")
                    return@onCompletion
                }
                if (cause is CancellationException) {
                    Log.d(TAG, "${config.errorLabel} cancelled session=$session")
                }
                if (config.checkEmptyStart && terminalReason == null && cause == null && generatedTokens == 0) {
                    terminalReason = "ERROR"
                    uiState._runtimeStatus.value = RuntimeStatus.ERROR
                    eventBus.publish("${config.errorLabel.replaceFirstChar { it.uppercase() }} did not start")
                }
                val finalReason = terminalReason ?: if (cause is CancellationException) "CANCELLED" else "EOF"
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

                onComplete(GenerationFlowResult(finalReason, finalPerformance, finalOutput, generatedTokens))

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
            }
            .launchIn(scope)
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private fun ensureChatWithinLengthBudget() {
        val messages = uiState.transcript.value
        val totalChars = messages.sumOf { it.text.length }
        if (messages.size >= MAX_CHAT_MESSAGES_BEFORE_CONTINUATION ||
            totalChars >= MAX_CHAT_CHARS_BEFORE_CONTINUATION
        ) {
            agentToolConfirmation.clearMemory()
            chatManager.createChatInternal("Continued chat", publishEvent = true)
            scope.launch {
                runCatching { engine.resetConversation() }
            }
        }
    }

    private fun getFormattedHistoryForAgent(excludeIds: Set<Long>): String {
        val history = uiState.transcript.value.filter { message ->
            message.text.isNotBlank() && message.id !in excludeIds
        }
        if (history.isEmpty()) return ""
        val selected = ArrayDeque<TranscriptMessage>()
        var chars = 0
        for (message in history.asReversed()) {
            val formatted = message.asPromptLine()
            if (chars + formatted.length > 4000) break
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

    private fun startBenchmarkChat(name: String) {
        chatManager.createChatInternal("Benchmark - $name", publishEvent = false)
        scope.launch { runCatching { engine.resetConversation() } }
    }

    private fun BenchmarkPreset.overrideSettings(base: GenerationSettings): GenerationSettings =
        base.copy(
            maxTokens = maxTokensOverride ?: base.maxTokens,
            threadCount = threadCountOverride ?: base.threadCount,
        ).clamped()

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
                val value = details.opt(key)
                when {
                    value is String && value.length > 150 ->
                        fallback.put(key, value.substring(0, 150) + " (Truncated for context size)")
                    value is JSONArray && value.length() > 5 -> {
                        val arr = JSONArray()
                        for (i in 0 until 5) arr.put(value.opt(i))
                        fallback.put(key, arr)
                    }
                    else -> fallback.put(key, value)
                }
            }
            fallback.put("truncated", true)
            fallback.put("note", "Results truncated for context. Full data available in app if needed.")
            fallback
        }

        return result.copy(details = finalDetails)
    }
}
