package com.prismai.llmhost.agent
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.util.Log
import com.prismai.llmhost.ui.ServiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Records per-step agent tool execution and finalizes the full agent-chain
 * trace as a JSON file in the app's agent_traces directory.
 *
 * Owns the mutable agent-chain state that was previously top-level vars in
 * [InferenceService] ([activeAgentSteps], [activeAgentChainPrompt],
 * [activeAgentChainStartTime], [activeAgentChainTokens]).
 */
class AgentTrace(
    private val uiState: ServiceUiState,
    private val filesDir: File,
    private val scope: CoroutineScope,
    private val writeArtifact: (File, JSONObject) -> Unit = { file, json ->
        file.writeText(json.toString(2))
    },
) {
    enum class TerminationAction {
        RESUMABLE,
        FINALIZE_SUCCESS,
        FINALIZE_FAILURE,
    }

    companion object {
        private const val TAG = "AgentTrace"

        fun terminationAction(
            terminalReason: String,
            hasToolCall: Boolean,
        ): TerminationAction = when {
            terminalReason == "MAX_TOKENS" -> TerminationAction.RESUMABLE
            terminalReason == "EOF" && !hasToolCall -> TerminationAction.FINALIZE_SUCCESS
            terminalReason == "EOF" -> TerminationAction.RESUMABLE
            else -> TerminationAction.FINALIZE_FAILURE
        }
    }

    val activeAgentSteps = mutableListOf<AgentStep>()
    var activeAgentChainPrompt: String? = null
    var activeAgentChainStartTime: Long = 0L
    private val chainTokens = java.util.concurrent.atomic.AtomicInteger(0)
    private var nextChainId = 0L
    private var currentChainId: Long? = null

    /** Monotonic owner token for the currently active agent chain, if any. */
    val activeChainId: Long? get() = synchronized(this) { currentChainId }

    /** Starts a new chain and invalidates callbacks owned by the previous chain. */
    @Synchronized
    fun beginChain(prompt: String, startTime: Long = System.currentTimeMillis()): Long {
        val chainId = ++nextChainId
        activeAgentSteps.clear()
        activeAgentChainPrompt = prompt
        activeAgentChainStartTime = startTime
        chainTokens.set(0)
        currentChainId = chainId
        return chainId
    }

    /** Returns whether [chainId] still owns the active chain. Null preserves legacy callers. */
    @Synchronized
    fun isCurrentChain(chainId: Long?): Boolean =
        chainId == null || currentChainId == chainId

    /** Live token count for the active agent chain; mutate only via [addChainTokens]. */
    val activeAgentChainTokens: Int get() = chainTokens.get()

    /** Atomically accumulates generated tokens into the chain budget (thread-safe). */
    fun addChainTokens(delta: Int) {
        chainTokens.addAndGet(delta)
    }

    /**
     * Accounts for one generation turn and applies the agent-chain termination policy.
     *
     * MAX_TOKENS leaves the chain active so a continuation can finish it. EOF is
     * successful only when the turn is not an intermediate tool call. All other
     * terminal reasons, including cancellation and unknown future reasons, fail
     * the chain closed.
     */
    @Synchronized
    fun recordGenerationTurn(
        terminalReason: String,
        generatedTokens: Int,
        hasToolCall: Boolean,
        abortReason: String? = null,
        chainId: Long? = null,
    ): Boolean {
        if (!isCurrentChain(chainId)) return false
        addChainTokens(generatedTokens)
        when (terminationAction(terminalReason, hasToolCall)) {
            TerminationAction.RESUMABLE -> Unit
            TerminationAction.FINALIZE_SUCCESS -> finalizeTrace(success = true, abortReason = abortReason)
            TerminationAction.FINALIZE_FAILURE -> finalizeTrace(success = false, abortReason = abortReason)
        }
        return true
    }

    /** Finalizes only when [chainId] still owns the active chain. */
    @Synchronized
    fun finalizeOwnedTrace(
        chainId: Long,
        success: Boolean = false,
        abortReason: String? = null,
    ): Boolean {
        if (!isCurrentChain(chainId)) return false
        finalizeTrace(success = success, abortReason = abortReason)
        return true
    }

    /** Shared explicit failure path; unowned callbacks fail closed. */
    fun abortTrace(chainId: Long?, abortReason: String): Boolean {
        if (chainId == null) return false
        return finalizeOwnedTrace(chainId, success = false, abortReason = abortReason)
    }

    @Synchronized
    fun recordStep(
        call: AgentToolCall,
        result: AgentToolResult,
        latencyMs: Long,
        chainId: Long? = null,
    ) {
        if (!isCurrentChain(chainId)) return
        val index = activeAgentSteps.size
        activeAgentSteps.add(
            AgentStep(
                stepIndex = index,
                toolName = call.name,
                arguments = call.arguments.toString(),
                resultSummary = result.summary,
                latencyMs = latencyMs,
            ),
        )
    }

    @Synchronized
    fun finalizeTrace(success: Boolean, abortReason: String? = null) {
        val prompt = activeAgentChainPrompt ?: return
        val steps = activeAgentSteps.toList()
        val startTime = activeAgentChainStartTime
        if (startTime == 0L) return

        val chainDurationMs = System.currentTimeMillis() - startTime
        val iterationCount = steps.size
        val totalTokens = activeAgentChainTokens
        logInfo(
            "agent_chain_summary duration_ms=$chainDurationMs iterations=$iterationCount total_tokens=$totalTokens",
        )

        val trace = AgentTrace(
            timestamp = startTime,
            prompt = prompt,
            steps = steps,
            success = success,
            abortReason = abortReason,
        )
        val json = JSONObject()
            .put("timestamp", trace.timestamp)
            .put("prompt", trace.prompt)
            .put("success", trace.success)
            .put("total_tokens", totalTokens)
        if (trace.abortReason != null) {
            json.put("abort_reason", trace.abortReason)
        }

        val stepsArray = JSONArray()
        trace.steps.forEach { step ->
            val stepJson = JSONObject()
                .put("stepIndex", step.stepIndex)
                .put("toolName", step.toolName)
                .put("arguments", step.arguments)
                .put("resultSummary", step.resultSummary)
                .put("latencyMs", step.latencyMs)
            stepsArray.put(stepJson)
        }
        json.put("steps", stepsArray)

        val tracesDir = File(filesDir, "agent_traces")
        val file = File(tracesDir, "agent_trace_${trace.timestamp}.json")
        val persist = {
            runCatching {
                if (!tracesDir.exists()) {
                    tracesDir.mkdirs()
                }
                writeArtifact(file, json)
                uiState._lastAgentTracePath.value = file.absolutePath
                logDebug("Agent trace saved: ${file.absolutePath}")
            }.onFailure { error ->
                logError("Failed to serialize/save agent trace", error)
            }
        }
        if (scope.isActive) {
            scope.launch(Dispatchers.IO) { persist() }
        } else {
            persist()
        }
        activeAgentChainStartTime = 0L
        chainTokens.set(0)
        currentChainId = null
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logError(message: String, error: Throwable) {
        runCatching { Log.e(TAG, message, error) }
    }

    /** Resets chain state for a new agent interaction. */
    @Synchronized
    fun reset() {
        activeAgentSteps.clear()
        activeAgentChainPrompt = null
        activeAgentChainStartTime = 0L
        chainTokens.set(0)
        currentChainId = null
    }
}
