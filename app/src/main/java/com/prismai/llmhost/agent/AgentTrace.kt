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
    ) {
        addChainTokens(generatedTokens)
        when (terminationAction(terminalReason, hasToolCall)) {
            TerminationAction.RESUMABLE -> Unit
            TerminationAction.FINALIZE_SUCCESS -> finalizeTrace(success = true, abortReason = abortReason)
            TerminationAction.FINALIZE_FAILURE -> finalizeTrace(success = false, abortReason = abortReason)
        }
    }

    @Synchronized
    fun recordStep(call: AgentToolCall, result: AgentToolResult, latencyMs: Long) {
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
        Log.i(
            TAG,
            "agent_chain_summary duration_ms=$chainDurationMs iterations=$iterationCount total_tokens=$totalTokens",
        )

        scope.launch(Dispatchers.IO) {
            runCatching {
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
                if (!tracesDir.exists()) {
                    tracesDir.mkdirs()
                }
                val file = File(tracesDir, "agent_trace_${trace.timestamp}.json")
                file.writeText(json.toString(2))
                uiState._lastAgentTracePath.value = file.absolutePath
                Log.d(TAG, "Agent trace saved: ${file.absolutePath}")
            }.onFailure { error ->
                Log.e(TAG, "Failed to serialize/save agent trace", error)
            }
        }
        activeAgentChainStartTime = 0L
        chainTokens.set(0)
    }

    /** Resets chain state for a new agent interaction. */
    @Synchronized
    fun reset() {
        activeAgentSteps.clear()
        activeAgentChainPrompt = null
        activeAgentChainStartTime = 0L
        chainTokens.set(0)
    }
}
