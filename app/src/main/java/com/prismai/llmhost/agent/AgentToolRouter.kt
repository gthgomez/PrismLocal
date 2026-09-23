package com.prismai.llmhost.agent
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import com.prismai.llmhost.util.SanitizerUtils
import android.os.SystemClock
import com.prismai.llmhost.*
import com.prismai.llmhost.model.DeviceProfiler
import com.prismai.llmhost.ui.ServiceUiState
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
    private val getCachedProfile: () -> DeviceCapabilityProfile,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val executeTool: suspend (AgentToolCall, Boolean) -> AgentToolResult,
    private val onFollowUp: (String, AgentToolResult, Int, Long?) -> Unit,
    private val onAppendTranscriptMessage: (TranscriptRole, String) -> Long,
    private val onPublishUiEvent: (String) -> Unit,
    private val scope: CoroutineScope,
    private val currentChatId: () -> String? = { null },
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

    val activeAgentToolHistory = java.util.Collections.synchronizedList(mutableListOf<AgentToolCall>())

    private data class OwnedToolJob(
        val owner: AgentToolJobOwner,
        val job: Job,
    )

    data class AgentToolJobOwner(
        val chainId: Long,
        val chatId: String,
    )

    private val toolJobsLock = Any()
    private val toolJobsByChain = mutableMapOf<Long, MutableSet<OwnedToolJob>>()
    private val invalidatedChains = mutableSetOf<Long>()

    // ── Owned tool-job lifecycle ────────────────────────────────────────

    fun hasActiveToolJobs(chainId: Long?): Boolean {
        if (chainId == null) return false
        synchronized(toolJobsLock) {
            return toolJobsByChain[chainId]?.any { it.job.isActive } == true
        }
    }

    fun isCurrentOwner(chainId: Long?, sourceChatId: String?): Boolean {
        if (chainId == null || sourceChatId.isNullOrBlank()) return false
        synchronized(toolJobsLock) {
            if (chainId in invalidatedChains) return false
            if (!agentTrace.isCurrentChain(chainId)) return false
        }
        return currentChatId() == sourceChatId
    }

    /** Launches a SAFE-path job and keeps it attached to its chain/chat owner. */
    fun launchOwnedToolJob(
        chainId: Long?,
        sourceChatId: String? = currentChatId(),
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val owner = if (chainId != null && !sourceChatId.isNullOrBlank()) {
            AgentToolJobOwner(chainId, sourceChatId)
        } else {
            null
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        if (owner != null) {
            synchronized(toolJobsLock) {
                val canLaunch = owner.chainId !in invalidatedChains &&
                    agentTrace.isCurrentChain(owner.chainId) &&
                    currentChatId() == owner.chatId
                if (canLaunch) {
                    toolJobsByChain.getOrPut(owner.chainId) { mutableSetOf() }
                        .add(OwnedToolJob(owner, job))
                } else {
                    job.cancel()
                }
            }
        }
        job.invokeOnCompletion {
            synchronized(toolJobsLock) {
                val jobs = toolJobsByChain[chainId] ?: return@invokeOnCompletion
                jobs.removeAll { it.job === job }
                if (jobs.isEmpty()) toolJobsByChain.remove(chainId)
            }
        }
        job.start()
        return job
    }

    /** Launches a confirmed-path job with an explicit source-chat owner. */
    fun launchConfirmedToolJob(
        chainId: Long,
        sourceChatId: String,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = launchOwnedToolJob(chainId, sourceChatId, block)

    data class ChatTransitionClaim(
        val chainIds: List<Long>,
        val pending: AgentToolConfirmation.PendingToolAuthorization?,
        val jobs: List<Job>,
    )

    /**
     * Atomically claims a chat transition, invalidating all affected owners
     * before cancellation. A non-null [expectedToken] makes stale UI actions
     * fail closed without touching a newer confirmation.
     */
    fun claimChatTransition(
        expectedToken: String? = null,
        abortReason: String,
    ): ChatTransitionClaim? {
        val claim = synchronized(toolJobsLock) {
            val staged = confirmation.pendingAuthorization
            if (expectedToken != null && staged?.token != expectedToken) return@synchronized null
            val pending = if (expectedToken == null) {
                confirmation.consumePending()
            } else {
                confirmation.consumePending(expectedToken)
            }
            if (expectedToken != null && pending == null) return@synchronized null
            val chainIds = buildList {
                agentTrace.activeChainId?.let(::add)
                pending?.chainId?.let { if (it !in this) add(it) }
            }
            chainIds.forEach { invalidatedChains += it }
            val jobs = chainIds.flatMap { toolJobsByChain[it]?.map { entry -> entry.job }.orEmpty() }
            ChatTransitionClaim(chainIds, pending, jobs)
        } ?: return null
        claim.chainIds.forEach { chainId ->
            agentTrace.abortTrace(chainId, abortReason)
        }
        claim.jobs.forEach { it.cancel() }
        return claim
    }

    /** Waits until every job invalidated for [chainId] has stopped. */
    suspend fun joinInvalidatedChain(chainId: Long?) {
        if (chainId == null) return
        val jobs = synchronized(toolJobsLock) {
            toolJobsByChain[chainId]?.map { it.job }.orEmpty()
        }
        jobs.forEach { it.join() }
        synchronized(toolJobsLock) {
            toolJobsByChain.remove(chainId)
            if (!agentTrace.isCurrentChain(chainId)) {
                invalidatedChains.remove(chainId)
            }
        }
    }

    fun stageConfirmation(
        call: AgentToolCall,
        originalPrompt: String,
        depth: Int,
        chainId: Long?,
        sourceChatId: String?,
        definition: AgentToolDefinition,
    ): AgentToolConfirmation.PendingToolAuthorization? = synchronized(toolJobsLock) {
        if (!isCurrentOwner(chainId, sourceChatId)) return@synchronized null
        if (confirmation.pendingAuthorization != null) return@synchronized null
        val authorization = confirmation.stagePending(
            call = call,
            originalPrompt = originalPrompt,
            depth = depth,
            chainId = chainId,
            sourceChatId = sourceChatId,
        ) ?: return@synchronized null
        confirmation.persist(authorization)
        uiState._pendingAgentToolAction.value = confirmation.build(
            id = authorization.token,
            call = authorization.call,
            definition = definition,
        )
        authorization
    }

    fun appendOwnedToolResult(
        chainId: Long?,
        sourceChatId: String?,
        result: AgentToolResult,
    ): Boolean = appendOwnedTranscriptMessage(
        chainId = chainId,
        sourceChatId = sourceChatId,
        role = TranscriptRole.TOOL,
        text = AgentToolProtocol.toolEventJson(
            status = if (result.success) "done" else "failed",
            toolName = result.call.name,
            summary = result.summary,
            details = result.details,
        ),
    )

    fun appendOwnedAssistantMessage(
        chainId: Long?,
        sourceChatId: String?,
        text: String,
    ): Boolean = appendOwnedTranscriptMessage(
        chainId = chainId,
        sourceChatId = sourceChatId,
        role = TranscriptRole.ASSISTANT,
        text = text,
    )

    private fun appendOwnedTranscriptMessage(
        chainId: Long?,
        sourceChatId: String?,
        role: TranscriptRole,
        text: String,
    ): Boolean {
        if (!isCurrentOwner(chainId, sourceChatId)) return false
        synchronized(toolJobsLock) {
            if (!isCurrentOwner(chainId, sourceChatId)) return false
            onAppendTranscriptMessage(role, text)
        }
        return true
    }

    // ── Main routing entry point ────────────────────────────────────────

    fun handleToolCall(
        call: AgentToolCall,
        originalPrompt: String,
        depth: Int,
        chainId: Long? = null,
    ) {
        val sourceChatId = currentChatId()
        if (chainId == null || sourceChatId.isNullOrBlank() || !isCurrentOwner(chainId, sourceChatId)) return
        val validation = AgentToolRegistry.validate(call)
        val validatedCall = validation.call
        val definition = validation.definition
        if (!validation.valid || definition == null) {
            val reason = validation.message.ifBlank { "Tool request rejected: ${validatedCall.name}" }
            appendOwnedToolResult(
                chainId = chainId,
                sourceChatId = sourceChatId,
                result = AgentToolResult(
                    call = validatedCall,
                    success = false,
                    summary = reason,
                    errorCode = validation.errorCode,
                ),
            )
            val abortReason = if (validation.errorCode == AgentToolErrorCode.RESTRICTED_TOOL) {
                "Restricted tool rejected: $reason"
            } else {
                "Tool request rejected: $reason"
            }
            failTrace(chainId, abortReason)
            return
        }

        // ── Battery & Thermal Safety Gates ──────────────────────────────
        val profile = getCachedProfile()
        val thermalStatus = profile.thermalStatus?.lowercase()
        val isHot = thermalStatus in setOf("severe", "critical", "emergency", "shutdown")
        if (isHot) {
            val safetyResult = AgentToolResult(
                call = validatedCall,
                success = false,
                summary = "Agent execution paused: Device is too hot (thermal state: $thermalStatus)",
                errorCode = AgentToolErrorCode.BUSY,
            )
            appendOwnedToolResult(chainId, sourceChatId, safetyResult)
            CapabilityRegistryHolder.auditLog.record(
                SecurityEvent(
                    eventType = "RESOURCE_GATE",
                    toolName = validatedCall.name,
                    detail = "Device thermal state is $thermalStatus".take(160),
                ),
            )
            appendOwnedAssistantMessage(
                chainId,
                sourceChatId,
                "⚠️ Agent execution paused: Device is too hot (thermal state: $thermalStatus). Stopping tool execution to protect device hardware.",
            )
            failTrace(chainId, "Device thermal state is $thermalStatus")
            return
        }
        if ((profile.batteryPercent ?: 100) < 15 && profile.isCharging != true) {
            val safetyResult = AgentToolResult(
                call = validatedCall,
                success = false,
                summary = "Agent execution paused: Battery is low (${profile.batteryPercent}%)",
                errorCode = AgentToolErrorCode.BUSY,
            )
            appendOwnedToolResult(chainId, sourceChatId, safetyResult)
            CapabilityRegistryHolder.auditLog.record(
                SecurityEvent(
                    eventType = "RESOURCE_GATE",
                    toolName = validatedCall.name,
                    detail = "Battery is low (${profile.batteryPercent}%)".take(160),
                ),
            )
            appendOwnedAssistantMessage(
                chainId,
                sourceChatId,
                "⚠️ Agent execution paused: Battery level is low (${profile.batteryPercent}%). Stopping tool execution to preserve battery life.",
            )
            failTrace(chainId, "Battery is low (${profile.batteryPercent}%)")
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
            appendOwnedToolResult(chainId, sourceChatId, maxStepsResult)
            CapabilityRegistryHolder.auditLog.record(
                SecurityEvent(
                    eventType = "RESOURCE_GATE",
                    toolName = validatedCall.name,
                    detail = "Reached maximum tool depth of $maxIterations".take(160),
                ),
            )
            appendOwnedAssistantMessage(
                chainId,
                sourceChatId,
                "⚠️ Agent loop stopped: Reached the maximum execution depth limit of $maxIterations operations.",
            )
            failTrace(chainId, "Reached maximum tool depth of $maxIterations")
            return
        }

        val (isExactOrSimilarLoop, isStatelessLoop, historicalCount) = synchronized(activeAgentToolHistory) {
            val exactOrSimilar = validatedCall.name !in setOf(
                "get_download_status",
                "get_active_operation",
                "check_background_tasks",
            ) && activeAgentToolHistory.any {
                it.name == validatedCall.name && AgentToolProtocol.areArgumentsSimilar(it.arguments, validatedCall.arguments)
            }
            val count = activeAgentToolHistory.count { it.name == validatedCall.name }
            val stateless = validatedCall.name in setOf(
                "get_model_status", "get_storage_status", "get_app_version_info", "get_tool_capabilities",
            ) && count >= 3
            Triple(exactOrSimilar, stateless, count)
        }
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
            appendOwnedToolResult(chainId, sourceChatId, loopResult)
            CapabilityRegistryHolder.auditLog.record(
                SecurityEvent(
                    eventType = "LOOP_ABORT",
                    toolName = validatedCall.name,
                    detail = reason.take(160),
                ),
            )
            appendOwnedAssistantMessage(
                chainId,
                sourceChatId,
                "⚠️ Agent execution aborted: $reason. Stopping loop execution to protect device battery and CPU resources.",
            )
            failTrace(chainId, reason)
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
            appendOwnedToolResult(chainId, sourceChatId, budgetResult)
            CapabilityRegistryHolder.auditLog.record(
                SecurityEvent(
                    eventType = "LOOP_ABORT",
                    toolName = validatedCall.name,
                    detail = "Token budget exceeded: ${agentTrace.activeAgentChainTokens} tokens".take(160),
                ),
            )
            appendOwnedAssistantMessage(
                chainId,
                sourceChatId,
                "⚠️ Agent execution paused: Reached the maximum token budget of ${AgentToolRegistry.MAX_AGENT_CHAIN_TOKENS} tokens. The conversation may be too long for additional tool operations.",
            )
            failTrace(
                chainId,
                "Token budget exceeded: ${agentTrace.activeAgentChainTokens} tokens",
            )
            return
        }

        // ── Announce tool event ─────────────────────────────────────────
        appendOwnedTranscriptMessage(
            chainId = chainId,
            sourceChatId = sourceChatId,
            role = TranscriptRole.TOOL,
            text = AgentToolProtocol.toolEventJson(
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
                launchOwnedToolJob(chainId, sourceChatId) toolJob@{
                    val stepStart = clock()
                    val result = try {
                        executeTool(validatedCall, false)
                    } catch (e: CancellationException) {
                        failTrace(chainId, "Tool execution cancelled")
                        throw e
                    } catch (e: Exception) {
                        AgentToolResult(
                            call = validatedCall,
                            success = false,
                            summary = "Tool execution failed with exception: ${e.message ?: "unknown error"}",
                            errorCode = AgentToolErrorCode.FAILED,
                        )
                    }
                    if (!isCurrentOwner(chainId, sourceChatId)) return@toolJob
                    val latency = clock() - stepStart
                    val sanitizedResult = ToolInputSanitizer.sanitizeResult(result)
                    agentTrace.recordStep(validatedCall, sanitizedResult, latency, chainId)
                    if (!isCurrentOwner(chainId, sourceChatId)) return@toolJob
                    appendOwnedToolResult(chainId, sourceChatId, sanitizedResult)
                    if (!isCurrentOwner(chainId, sourceChatId)) return@toolJob
                    val maxIter = uiState.generationSettings.value.maxAgentIterations
                    if (depth + 1 < maxIter) {
                        onFollowUp(originalPrompt, result, depth + 1, chainId)
                    } else {
                        failTrace(chainId, "Tool loop stopped after $maxIter steps")
                    }
                }
            }
            AgentToolRisk.CONFIRM -> {
                val authorization = stageConfirmation(
                    call = validatedCall,
                    originalPrompt = originalPrompt,
                    depth = depth,
                    chainId = chainId,
                    sourceChatId = sourceChatId,
                    definition = definition,
                )
                if (authorization == null) {
                    val rejected = AgentToolResult(
                        call = validatedCall,
                        success = false,
                        summary = "Confirmation requires an active chain and source chat",
                        errorCode = AgentToolErrorCode.CONFIRMATION_REQUIRED,
                    )
                    appendOwnedToolResult(chainId, sourceChatId, rejected)
                    failTrace(chainId, "Confirmation authorization rejected: missing chain or source chat")
                    return
                }
                onPublishUiEvent("Confirm tool: ${validatedCall.name}")
            }
            AgentToolRisk.RESTRICTED -> {
                val restrictedResult = AgentToolResult(
                    call = validatedCall,
                    success = false,
                    summary = "Restricted tool blocked: ${validatedCall.name}",
                    errorCode = AgentToolErrorCode.RESTRICTED_TOOL,
                )
                appendOwnedToolResult(chainId, sourceChatId, restrictedResult)
                agentTrace.recordStep(validatedCall, restrictedResult, 0L, chainId)
                failTrace(chainId, "Restricted tool blocked: ${validatedCall.name}")
            }
        }
    }

    /** Fails the chain only when the supplied owner token is still current. */
    fun failTrace(chainId: Long?, abortReason: String): Boolean =
        agentTrace.abortTrace(chainId, abortReason.take(220))

    /** Cancels a pending confirmation and closes its chain if it is still owned. */
    fun cancelPendingTool(call: AgentToolCall, chainId: Long?, abortReason: String) {
        if (chainId == null || !agentTrace.isCurrentChain(chainId)) return
        removeFromHistory(call)
        failTrace(chainId, abortReason)
    }

    /**
     * Applies the post-confirmation continuation policy. Successful terminal
     * confirmations close as success; only failures and aborted loop policies
     * close as failure.
     */
    fun completeConfirmedTool(
        call: AgentToolCall,
        result: AgentToolResult,
        depth: Int,
        maxIterations: Int,
        chainId: Long?,
    ): Boolean {
        if (chainId == null || !agentTrace.isCurrentChain(chainId)) return false
        val shouldContinue = result.success &&
            shouldContinueAfterTool(call) &&
            depth + 1 < maxIterations
        if (!shouldContinue) {
            when {
                !result.success -> failTrace(
                    chainId,
                    "Tool confirmation failed: ${result.summary}",
                )
                depth + 1 >= maxIterations -> failTrace(
                    chainId,
                    "Tool loop stopped after $maxIterations steps",
                )
                else -> agentTrace.finalizeOwnedTrace(
                    chainId = chainId,
                    success = true,
                    abortReason = "Tool confirmation completed without a follow-up",
                )
            }
        }
        return shouldContinue
    }

    // ── History maintenance ─────────────────────────────────────────────

    /** Removes history entries matching name + similar arguments; used when a pending CONFIRM call is cancelled. */
    fun removeFromHistory(call: AgentToolCall) {
        synchronized(activeAgentToolHistory) {
            activeAgentToolHistory.removeAll {
                it.name == call.name && AgentToolProtocol.areArgumentsSimilar(it.arguments, call.arguments)
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
            "delete_model",
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
            "fetch_grokipedia_article",
            // Phase 5 Workspace Files
            "list_workspace_files",
            "read_workspace_file",
            "search_workspace_files",
            // Network egress (confirmation-gated; results feed the model)
            "web_search",
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
            ("storage status" in lower || "disk space" in lower || "app storage" in lower || ("storage" in lower && ("model" in lower || "app" in lower))) ->
                AgentToolCall("get_storage_status")
            ("download" in lower && "status" in lower) ->
                AgentToolCall("get_download_status")
            ("active" in lower && "operation" in lower) || ("what" in lower && "running" in lower && "task" in lower) ->
                AgentToolCall("get_active_operation")
            ("privacy summary" in lower || "app privacy" in lower || ("privacy" in lower && "app" in lower)) ->
                AgentToolCall("get_privacy_summary")
            ("list" in lower || "show" in lower) && ("curated" in lower || "downloadable" in lower) && "model" in lower ->
                AgentToolCall("list_curated_downloadable_models")
            ("validate" in lower || "check" in lower) && ("runtime" in lower || "settings" in lower) ->
                AgentToolCall("validate_runtime_settings", JSONObject().put("proposed_settings", parseRuntimeSettingsFromPrompt(prompt)))
            "model status" in lower || "current model" in lower || "model info" in lower || ("status" in lower && "model" in lower) ->
                AgentToolCall("get_model_status")
            ("list" in lower || "show" in lower || "what" in lower) && "installed" in lower && "model" in lower ->
                AgentToolCall("list_installed_models")
            ("benchmark" in lower || "run" in lower) && ("history" in lower || "runs" in lower || "recent" in lower) ->
                AgentToolCall("list_benchmark_runs")
            ("diagnose performance" in lower || "performance diagnosis" in lower) ->
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
            ("guidance skill" in lower || "use skill" in lower) ->
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
                }
                entry?.let { AgentToolCall("download_model", JSONObject().put("entry_id", it.id)) }
            }
            else -> null
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

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
