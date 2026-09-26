package com.prismai.llmhost.agent.tools
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import com.prismai.llmhost.BackgroundAgentManager
import org.json.JSONArray
import org.json.JSONObject

class BackgroundAgentTools(
    private val backgroundAgentManager: BackgroundAgentManager,
) {
    suspend fun runInBackground(
        call: AgentToolCall,
        confirmed: Boolean,
        sourceChatId: String?,
    ): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Background task requires confirmation")
        if (sourceChatId.isNullOrBlank()) {
            return toolFailure(call, AgentToolErrorCode.FAILED, "Cannot queue a background task without its source chat")
        }
        val prompt = call.arguments.optString("prompt").trim()
        if (prompt.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Prompt is required")
        val task = backgroundAgentManager.enqueue(prompt, sourceChatId)
            ?: return toolFailure(call, AgentToolErrorCode.BUSY, "Background task queue is full (max 5)")
        backgroundAgentManager.startBackgroundMode()
        return toolSuccess(call, "Queued background task ${task.id}: ${prompt.take(80)}",
            JSONObject().put("task_id", task.id).put("prompt_preview", prompt.take(80))
                .put("queue_position", backgroundAgentManager.state.value.queuedTasks.size).put("max_queue_size", 5))
    }

    suspend fun checkBackgroundTasks(call: AgentToolCall): AgentToolResult {
        val state = backgroundAgentManager.state.value
        val tasksJson = JSONObject().apply {
            put("is_background_mode", state.isBackgroundMode)
            put("battery_ok", state.batteryOk)
            put("thermal_ok", state.thermalOk)
        }
        val queuedArray = JSONArray()
        state.queuedTasks.forEach { task ->
            queuedArray.put(JSONObject().put("id", task.id).put("prompt_preview", task.prompt.take(80)).put("created_at", task.createdAt))
        }
        tasksJson.put("queued_tasks", queuedArray)
        val completedArray = JSONArray()
        state.completedTasks.forEach { task ->
            completedArray.put(JSONObject().put("id", task.id).put("status", task.status.name).put("result_summary", (task.resultSummary ?: "").take(80)))
        }
        tasksJson.put("completed_tasks", completedArray)
        return toolSuccess(call, "${state.queuedTasks.size} queued, ${state.completedTasks.size} completed", tasksJson)
    }

    suspend fun cancelBackgroundTask(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        if (!confirmed) return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Task cancellation requires confirmation")
        val taskId = call.arguments.optString("task_id").trim()
        if (taskId.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "task_id is required")
        val cancelled = backgroundAgentManager.cancelTask(taskId)
        return if (cancelled) toolSuccess(call, "Cancelled background task $taskId")
        else toolFailure(call, AgentToolErrorCode.NOT_FOUND, "No background task found with id: $taskId")
    }
}
