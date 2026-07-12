package com.example.llmhost.agent

import android.os.SystemClock
import com.example.llmhost.*
import com.example.llmhost.model.DeviceProfiler
import com.example.llmhost.ui.ServiceUiState
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Routes validated agent tool calls through safety gates, loop guards, and
 * the confirmation system, then dispatches execution (delegated back to
 * [InferenceService] until Phase D extracts individual tool handlers).
 *
 * Owns [activeAgentToolHistory] for repetition detection.
 */
class AgentToolRouter(
    private val uiState: ServiceUiState,
    private val agentTrace: AgentTrace,
    private val confirmation: AgentToolConfirmation,
    private val deviceProfiler: DeviceProfiler,
    private val executeTool: suspend (AgentToolCall, Boolean) -> AgentToolResult,
    private val onFollowUp: (String, AgentToolResult, Int) -> Unit,
    private val onAppendTranscriptMessage: (TranscriptRole, String) -> Long,
    private val onPublishUiEvent: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        // Tool sets
        val cheapTools: Set<String> = setOf(
            "get_model_status", "get_storage_status", "get_app_version_info", "get_tool_capabilities",
            "search_chats", "list_installed_models", "list_curated_downloadable_models", "get_download_status",
            "get_active_operation", "get_privacy_summary", "explain_runtime_settings", "diagnose_performance",
            "recommend_runtime_settings", "get_model_card", "summarize_current_chat", "web_search", "recall_facts", "list_memories",
            // Phase 2 RAG
            "search_documents", "list_documents",
            // Phase 4 Voice
            "voice_input", "speak_output", "stop_speaking",
            // Phase 5 Data connectors (read-only)
            "search_contacts", "get_calendar_events", "list_sms_threads",
            // Phase 3 Knowledge Pack (read-only)
            "search_knowledge", "fetch_grokipedia_article", "list_knowledge_packs",
            // Phase 7a Background
            "check_background_tasks",
        )
    }

    val activeAgentToolHistory = mutableListOf<AgentToolCall>()

    // ── Main routing entry point ────────────────────────────────────────

    fun handleToolCall(call: AgentToolCall, originalPrompt: String, depth: Int) {
        val validation = AgentToolRegistry.validate(call)
        val validatedCall = validation.call
        val definition = validation.definition
        if (!validation.valid || definition == null) {
            appendToolResult(
                AgentToolResult(
                    call = validatedCall,
                    success = false,
                    summary = validation.message.ifBlank { "Tool request rejected: ${validatedCall.name}" },
                    errorCode = validation.errorCode,
                ),
            )
            return
        }

        // ── Battery & Thermal Safety Gates ──────────────────────────────
        val profile = deviceProfiler.getCachedProfile()
        val thermalStatus = profile.thermalStatus?.lowercase()
        val isHot = thermalStatus in setOf("severe", "critical", "emergency", "shutdown")
        if (isHot) {
            val safetyResult = AgentToolResult(
                call = validatedCall,
                success = false,
                summary = "Agent execution paused: Device is too hot (thermal state: $thermalStatus)",
                errorCode = AgentToolErrorCode.BUSY,
            )
            appendToolResult(safetyResult)
            agentTrace.finalizeTrace(success = false, abortReason = "Device thermal state is $thermalStatus")
            onAppendTranscriptMessage(
                TranscriptRole.ASSISTANT,
                "⚠️ Agent execution paused: Device is too hot (thermal state: $thermalStatus). Stopping tool execution to protect device hardware.",
            )
            return
        }
        if ((profile.batteryPercent ?: 100) < 15 && profile.isCharging != true) {
            val safetyResult = AgentToolResult(
                call = validatedCall,
                success = false,
                summary = "Agent execution paused: Battery is low (${profile.batteryPercent}%)",
                errorCode = AgentToolErrorCode.BUSY,
            )
            appendToolResult(safetyResult)
            agentTrace.finalizeTrace(success = false, abortReason = "Battery is low (${profile.batteryPercent}%)")
            onAppendTranscriptMessage(
                TranscriptRole.ASSISTANT,
                "⚠️ Agent execution paused: Battery level is low (${profile.batteryPercent}%). Stopping tool execution to preserve battery life.",
            )
            return
        }

        // ── Loop / Repetition Guards ────────────────────────────────────
        val maxIterations = uiState.generationSettings.value.maxAgentIterations
        if (depth >= maxIterations) {
            val maxStepsResult = AgentToolResult(
                call = validatedCall,
                success = false,
                summary = "Tool loop stopped after $maxIterations steps",
                errorCode = AgentToolErrorCode.FAILED,
            )
            appendToolResult(maxStepsResult)
            agentTrace.finalizeTrace(success = false, abortReason = "Reached maximum tool depth of $maxIterations")
            onAppendTranscriptMessage(
                TranscriptRole.ASSISTANT,
                "⚠️ Agent loop stopped: Reached the maximum execution depth limit of $maxIterations operations.",
            )
            return
        }

        val isExactOrSimilarLoop = activeAgentToolHistory.any {
            it.name == validatedCall.name && AgentToolProtocol.areArgumentsSimilar(it.arguments, validatedCall.arguments)
        }
        val isStatelessLoop = validatedCall.name in setOf(
            "get_model_status", "get_storage_status", "get_app_version_info", "get_tool_capabilities",
        ) && activeAgentToolHistory.count { it.name == validatedCall.name } >= 3

        val historicalCount = activeAgentToolHistory.count { it.name == validatedCall.name }
        val tooManyInvocations = if (validatedCall.name in cheapTools) {
            historicalCount >= 5
        } else {
            historicalCount >= 4
        }

        if (isExactOrSimilarLoop || isStatelessLoop || tooManyInvocations) {
            val reason = when {
                isExactOrSimilarLoop -> "Repetitive or near-identical operation detected"
                isStatelessLoop -> "Repetitive stateless query detected"
                else -> "Tool ${validatedCall.name} requested too many times (${historicalCount + 1} times)"
            }
            val loopResult = AgentToolResult(
                call = validatedCall,
                success = false,
                summary = "Agent loop aborted: $reason",
                errorCode = AgentToolErrorCode.FAILED,
            )
            appendToolResult(loopResult)
            agentTrace.finalizeTrace(success = false, abortReason = reason)
            onAppendTranscriptMessage(
                TranscriptRole.ASSISTANT,
                "⚠️ Agent execution aborted: $reason. Stopping loop execution to protect device battery and CPU resources.",
            )
            return
        }
        activeAgentToolHistory.add(validatedCall)

        // ── Token budget guard ──────────────────────────────────────────
        val tokenBudgetExceeded = agentTrace.activeAgentChainTokens >= AgentToolRegistry.MAX_AGENT_CHAIN_TOKENS
        if (tokenBudgetExceeded) {
            val budgetResult = AgentToolResult(
                call = validatedCall,
                success = false,
                summary = "Agent token budget exceeded: ${agentTrace.activeAgentChainTokens} tokens used (limit: ${AgentToolRegistry.MAX_AGENT_CHAIN_TOKENS})",
                errorCode = AgentToolErrorCode.FAILED,
            )
            appendToolResult(budgetResult)
            agentTrace.finalizeTrace(
                success = false,
                abortReason = "Token budget exceeded: ${agentTrace.activeAgentChainTokens} tokens",
            )
            onAppendTranscriptMessage(
                TranscriptRole.ASSISTANT,
                "⚠️ Agent execution paused: Reached the maximum token budget of ${AgentToolRegistry.MAX_AGENT_CHAIN_TOKENS} tokens. The conversation may be too long for additional tool operations.",
            )
            return
        }

        // ── Announce tool event ─────────────────────────────────────────
        onAppendTranscriptMessage(
            TranscriptRole.TOOL,
            AgentToolProtocol.toolEventJson(
                status = if (definition.risk == AgentToolRisk.CONFIRM) "pending" else "running",
                toolName = validatedCall.name,
                summary = "Prism requested ${validatedCall.name}",
                details = JSONObject()
                    .put("arguments", validatedCall.arguments)
                    .put("reason", validatedCall.reason ?: ""),
            ),
        )

        // ── Dispatch by risk level ──────────────────────────────────────
        when (definition.risk) {
            AgentToolRisk.SAFE -> {
                scope.launch {
                    val stepStart = SystemClock.elapsedRealtime()
                    val result = executeTool(validatedCall, false)
                    val latency = SystemClock.elapsedRealtime() - stepStart
                    agentTrace.recordStep(validatedCall, result, latency)
                    appendToolResult(result)
                    val maxIter = uiState.generationSettings.value.maxAgentIterations
                    if (result.success && depth + 1 < maxIter) {
                        onFollowUp(originalPrompt, result, depth + 1)
                    }
                }
            }
            AgentToolRisk.CONFIRM -> {
                val id = "agent_tool_${SystemClock.uptimeMillis()}"
                confirmation.pendingCall = validatedCall
                confirmation.pendingOriginalPrompt = originalPrompt
                confirmation.pendingDepth = depth
                confirmation.persist(validatedCall, originalPrompt, depth)
                uiState._pendingAgentToolAction.value = confirmation.build(id, validatedCall, definition)
                onPublishUiEvent("Confirm tool: ${validatedCall.name}")
            }
            AgentToolRisk.RESTRICTED -> {
                val restrictedResult = AgentToolResult(
                    call = validatedCall,
                    success = false,
                    summary = "Restricted tool blocked: ${validatedCall.name}",
                    errorCode = AgentToolErrorCode.RESTRICTED_TOOL,
                )
                appendToolResult(restrictedResult)
                agentTrace.recordStep(validatedCall, restrictedResult, 0L)
            }
        }
    }

    // ── Continue-after-tool decision ────────────────────────────────────

    fun shouldContinueAfterTool(call: AgentToolCall): Boolean =
        when (call.name) {
            "set_runtime_settings",
            "restore_previous_runtime_settings",
            "rename_current_chat",
            "clear_chat",
            "delete_or_clear_chat",
            "download_model",
            "switch_model",
            "cancel_active_job",
            "cancel_active_operation",
            "remember_fact",
            "forget_fact",
            // Phase 2 RAG
            "ingest_document",
            "delete_document",
            // Phase 4 Voice I/O
            "voice_input",
            "speak_output",
            "stop_speaking",
            // Phase 3 Knowledge Pack
            "download_knowledge_pack",
            // Phase 7a Background
            "run_in_background",
            "check_background_tasks" -> true
            else -> false
        }

    // ── NL → Tool call mapping ──────────────────────────────────────────

    fun directToolCall(prompt: String): AgentToolCall? {
        val lower = prompt.lowercase(Locale.US)
        return when {
            ("tool" in lower || "capabilities" in lower) && ("what" in lower || "list" in lower || "show" in lower) ->
                AgentToolCall("get_tool_capabilities", JSONObject().put("include_schemas", true))
            ("version" in lower || "build info" in lower || "app info" in lower) ->
                AgentToolCall("get_app_version_info")
            ("storage" in lower || "space" in lower) && ("status" in lower || "free" in lower || "used" in lower) ->
                AgentToolCall("get_storage_status")
            ("download" in lower && "status" in lower) ->
                AgentToolCall("get_download_status")
            ("active" in lower && "operation" in lower) || ("what" in lower && "running" in lower) ->
                AgentToolCall("get_active_operation")
            ("privacy" in lower || "private" in lower) && ("summary" in lower || "explain" in lower || "data" in lower) ->
                AgentToolCall("get_privacy_summary")
            ("list" in lower || "show" in lower) && ("curated" in lower || "downloadable" in lower) && "model" in lower ->
                AgentToolCall("list_curated_downloadable_models")
            ("validate" in lower || "check" in lower) && ("runtime" in lower || "settings" in lower) ->
                AgentToolCall("validate_runtime_settings", JSONObject().put("proposed_settings", parseRuntimeSettingsFromPrompt(prompt)))
            "status" in lower || "loaded" in lower || "current model" in lower || "model info" in lower ->
                AgentToolCall("get_model_status")
            ("list" in lower || "show" in lower || "what" in lower) && "installed" in lower && "model" in lower ->
                AgentToolCall("list_installed_models")
            ("benchmark" in lower || "run" in lower) && ("history" in lower || "runs" in lower || "recent" in lower) ->
                AgentToolCall("list_benchmark_runs")
            "diagnose" in lower || ("why" in lower && ("slow" in lower || "performance" in lower)) || "performance diagnosis" in lower ->
                AgentToolCall("diagnose_performance")
            ("summarize" in lower || "summary" in lower) && ("chat" in lower || "conversation" in lower) ->
                AgentToolCall("summarize_current_chat")
            ("search" in lower || "find" in lower) && ("chat" in lower || "conversation" in lower) ->
                AgentToolCall("search_chats", JSONObject().put("query", prompt.substringAfter(" ", prompt).take(80)))
            "export" in lower && ("chat" in lower || "conversation" in lower) ->
                AgentToolCall("export_chat", JSONObject().put("format", if ("json" in lower) "json" else if ("text" in lower) "text" else "markdown"))
            ("clear" in lower || "delete" in lower) && ("chat" in lower || "conversation" in lower) ->
                AgentToolCall(
                    if ("delete" in lower) "delete_chat" else "clear_chat",
                    JSONObject().put("chat_id", "current"),
                )
            ("model card" in lower || ("details" in lower && "model" in lower) || ("metadata" in lower && "model" in lower)) ->
                AgentToolCall("get_model_card", JSONObject().put("model_id", "current"))
            ("recommend" in lower || "suggest" in lower) && ("runtime" in lower || "settings" in lower) ->
                AgentToolCall("recommend_runtime_settings", JSONObject().put("goal", runtimeGoalFromPrompt(lower)))
            ("explain" in lower || "what do" in lower) && ("runtime" in lower || "settings" in lower || "temperature" in lower || "context" in lower) ->
                AgentToolCall("explain_runtime_settings")
            ("open" in lower || "show" in lower) && ("model manager" in lower || "benchmarks" in lower || "settings" in lower || "chats" in lower) ->
                AgentToolCall("open_app_panel", JSONObject().put("panel", panelFromPrompt(lower)))
            ("cancel" in lower || "stop" in lower) && ("generation" in lower || "responding" in lower || "answer" in lower) ->
                AgentToolCall("cancel_generation")
            ("cancel" in lower || "stop" in lower) && ("operation" in lower || "download" in lower || "import" in lower || "benchmark" in lower) ->
                AgentToolCall("cancel_active_job", JSONObject().put("target", operationTargetFromPrompt(lower)))
            ("skill" in lower || "help me with" in lower) &&
                ("performance" in lower || "model" in lower || "benchmark" in lower || "workspace" in lower || "privacy" in lower || "safety" in lower) ->
                AgentToolCall("use_guidance_skill", JSONObject().put("skill", skillFromPrompt(lower)))
            ("rename" in lower || "title" in lower) && ("chat" in lower || "conversation" in lower) -> {
                val title = prompt
                    .replace(Regex("(?i).*?(?:rename|title)(?:\\s+the)?(?:\\s+current)?\\s+(?:chat|conversation)?\\s*(?:to|as)?\\s*"), "")
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= 80 }
                AgentToolCall("rename_current_chat", JSONObject().apply {
                    title?.let { put("title", it) }
                })
            }
            ("set" in lower || "change" in lower || "update" in lower) &&
                ("runtime" in lower || "tokens" in lower || "threads" in lower || "temperature" in lower || "context" in lower) ->
                AgentToolCall("set_runtime_settings", parseRuntimeSettingsFromPrompt(prompt))
            "compare" in lower && "model" in lower ->
                AgentToolCall("compare_models")
            "recommend" in lower || ("best" in lower && "model" in lower) ->
                AgentToolCall(
                    "recommend_model",
                    JSONObject().put(
                        "prefer",
                        when {
                            "code" in lower || "python" in lower -> "coding"
                            "fast" in lower || "speed" in lower -> "speed"
                            else -> "chat"
                        },
                    ),
                )
            "benchmark" in lower || ("run" in lower && "test" in lower) -> {
                val presetId = when {
                    "native" in lower || "pp" in lower || "tg" in lower -> "native_pp_tg"
                    "thread" in lower || "sweep" in lower -> "thread_sweep"
                    "short" in lower -> "short_answer"
                    "json" in lower -> "json"
                    "long" in lower -> "long_form"
                    "reason" in lower -> "reasoning"
                    "python" in lower || "coding" in lower || "code" in lower -> "coding"
                    else -> "coding"
                }
                AgentToolCall("run_benchmark", JSONObject().put("preset_id", presetId))
            }
            "download" in lower -> {
                val entry = HuggingFaceModelCatalog.entries.firstOrNull { entry ->
                    entry.id.lowercase(Locale.US) in lower ||
                        entry.name.lowercase(Locale.US).split(' ').all { part -> part.length < 3 || part in lower }
                } ?: HuggingFaceModelCatalog.entries.firstOrNull()
                entry?.let { AgentToolCall("download_model", JSONObject().put("entry_id", it.id)) }
            }
            else -> null
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun appendToolResult(result: AgentToolResult) {
        onAppendTranscriptMessage(
            TranscriptRole.TOOL,
            AgentToolProtocol.toolEventJson(
                status = if (result.success) "done" else "failed",
                toolName = result.call.name,
                summary = result.summary,
                details = result.details,
            ),
        )
    }

    // ── NL parsing helpers ──────────────────────────────────────────────

    private fun parseRuntimeSettingsFromPrompt(prompt: String): JSONObject {
        val lower = prompt.lowercase(Locale.US)
        fun intAfter(vararg names: String): Int? {
            names.forEach { name ->
                Regex("""\b$name(?:\s+count)?\s*(?:to|=|:)?\s*(\d+)""").find(lower)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { return it }
            }
            return null
        }
        fun floatAfter(vararg names: String): Double? {
            names.forEach { name ->
                Regex("""\b$name\s*(?:to|=|:)?\s*(\d+(?:\.\d+)?)""").find(lower)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
                    ?.let { return it }
            }
            return null
        }
        return JSONObject().apply {
            intAfter("tokens", "max tokens", "max_tokens")?.let { put("max_tokens", it) }
            intAfter("threads", "thread")?.let { put("threads", it) }
            intAfter("context", "ctx", "context length")?.let { put("context_length", it) }
            intAfter("batch", "batch size")?.let { put("batch_size", it) }
            intAfter("top k", "top_k")?.let { put("top_k", it) }
            intAfter("gpu", "gpu layers", "gpu_layers")?.let { put("gpu_layers", it) }
            floatAfter("temperature", "temp")?.let { put("temperature", it) }
            floatAfter("top p", "top_p")?.let { put("top_p", it) }
            floatAfter("repeat penalty", "repeat_penalty")?.let { put("repeat_penalty", it) }
        }
    }

    private fun runtimeGoalFromPrompt(lower: String): String = when {
        "battery" in lower || "cool" in lower -> "battery_saver"
        "long" in lower || "context" in lower -> "long_context"
        "code" in lower || "coding" in lower || "python" in lower -> "coding"
        "quality" in lower || "creative" in lower -> "quality"
        "fast" in lower || "speed" in lower -> "fast"
        else -> "fast"
    }

    private fun panelFromPrompt(lower: String): String = when {
        "benchmark" in lower -> "benchmarks"
        "chat" in lower -> "chats"
        "setting" in lower -> "settings"
        else -> "model_manager"
    }

    private fun operationTargetFromPrompt(lower: String): String = when {
        "download" in lower -> "download"
        "import" in lower -> "import"
        "generation" in lower || "benchmark" in lower -> "generation"
        else -> "auto"
    }

    private fun skillFromPrompt(lower: String): String = when {
        "privacy" in lower -> "local_privacy"
        "safety" in lower || "risk" in lower -> "runtime_safety"
        "workspace" in lower || "chat" in lower -> "chat_workspace"
        "benchmark" in lower -> "benchmark_analysis"
        "model" in lower -> "model_selection"
        else -> "performance_tuning"
    }
}
