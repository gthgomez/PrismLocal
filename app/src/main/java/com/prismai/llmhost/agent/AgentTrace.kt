package com.prismai.llmhost.agent

import android.util.Log
import com.prismai.llmhost.AgentStep
import com.prismai.llmhost.AgentToolCall
import com.prismai.llmhost.AgentToolResult
import com.prismai.llmhost.AgentTrace
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
    companion object {
        private const val TAG = "AgentTrace"
    }

    val activeAgentSteps = mutableListOf<AgentStep>()
    var activeAgentChainPrompt: String? = null
    var activeAgentChainStartTime: Long = 0L
    var activeAgentChainTokens: Int = 0

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
    }

    /** Resets chain state for a new agent interaction. */
    fun reset() {
        activeAgentSteps.clear()
        activeAgentChainPrompt = null
        activeAgentChainStartTime = 0L
        activeAgentChainTokens = 0
    }
}
