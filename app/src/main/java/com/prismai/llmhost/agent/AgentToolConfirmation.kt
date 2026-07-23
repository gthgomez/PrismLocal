package com.prismai.llmhost.agent

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.prismai.llmhost.AgentToolCall
import com.prismai.llmhost.AgentToolDefinition
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.HuggingFaceModelCatalog
import com.prismai.llmhost.PendingAgentToolAction
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.util.FormatUtils
import org.json.JSONObject
import java.util.Locale

/**
 * Builds UI-facing tool-confirmation cards and manages the persistence
 * lifecycle of pending agent tool calls (save / restore / clear).
 *
 * Owns the in-memory pending state that was previously top-level vars in
 * [InferenceService]: [pendingCall], [pendingOriginalPrompt], [pendingDepth].
 */
class AgentToolConfirmation(
    private val uiState: ServiceUiState,
    private val prefs: SharedPreferences,
    private val currentChatId: () -> String?,
    private val pendingActionKey: (String) -> String,
    private val getDeviceProfile: () -> DeviceCapabilityProfile?,
) {
    companion object {
        private const val TAG = "AgentToolConfirm"
    }

    // ── In-memory pending state ─────────────────────────────────────────

    var pendingCall: AgentToolCall? = null
    var pendingOriginalPrompt: String? = null
    var pendingDepth: Int = 0

    // ── Confirmation builder ────────────────────────────────────────────

    fun build(
        id: String,
        call: AgentToolCall,
        definition: AgentToolDefinition,
    ): PendingAgentToolAction {
        val base = PendingAgentToolAction(
            id = id,
            name = call.name,
            description = definition.description,
            argumentsJson = call.arguments.toString(2),
            title = "Confirm ${call.name}",
            summary = definition.description,
            riskNotes = call.reason?.takeIf { it.isNotBlank() }?.let { listOf("Agent reason: $it") }.orEmpty(),
            confirmLabel = "Run",
        )
        return when (call.name) {
            "set_runtime_settings" -> {
                val before = uiState.generationSettings.value.clamped()
                val after = FormatUtils.proposedRuntimeSettings(call.arguments, before)
                val validation = FormatUtils.validateRuntimeSettingsPayload(after, getDeviceProfile())
                base.copy(
                    title = "Change runtime settings?",
                    summary = "Update local inference settings.",
                    changes = FormatUtils.settingDiffLines(before, after),
                    riskNotes = validation.second,
                    confirmLabel = "Apply settings",
                )
            }
            "restore_previous_runtime_settings" -> {
                base.copy(
                    title = "Restore previous runtime settings?",
                    summary = "Restore the saved settings from before the last confirmed runtime change.",
                    confirmLabel = "Restore",
                )
            }
            "export_chat" -> {
                val session = resolveToolChatSession(call.arguments.optString("chat_id", "current"))
                val format = call.arguments.optString("format", "markdown")
                base.copy(
                    title = "Export chat?",
                    summary = "Export ${session?.title ?: "selected chat"} as $format.",
                    changes = listOf(
                        "Chat: ${session?.title ?: "unknown"}",
                        "Messages: ${session?.messageCount ?: 0}",
                        "Destination: app-local agent_exports folder",
                    ),
                    riskNotes = listOf("Exported files can expose private chat content if shared outside the app."),
                    confirmLabel = "Export",
                    privacySensitive = true,
                )
            }
            "clear_chat", "delete_chat", "delete_or_clear_chat" -> {
                val mode = when (call.name) {
                    "delete_chat" -> "delete"
                    "clear_chat" -> "clear"
                    else -> call.arguments.optString("action", "clear_current")
                }
                val session = resolveToolChatSession(call.arguments.optString("chat_id", "current"))
                val deleting = mode == "delete" || mode == "delete_chat"
                base.copy(
                    title = if (deleting) "Delete chat?" else "Clear chat messages?",
                    summary = "${if (deleting) "Delete" else "Clear"} ${session?.title ?: "selected chat"}.",
                    changes = listOf(
                        "Chat: ${session?.title ?: "unknown"}",
                        "Messages: ${session?.messageCount ?: 0}",
                    ),
                    riskNotes = listOf(if (deleting) "This removes the chat from the local chat list." else "This removes messages from the selected local chat."),
                    confirmLabel = if (deleting) "Delete" else "Clear",
                    destructive = true,
                    privacySensitive = true,
                )
            }
            "download_model" -> {
                val entry = HuggingFaceModelCatalog.find(call.arguments.optString("entry_id"))
                base.copy(
                    title = "Download model?",
                    summary = entry?.let { "Download ${it.name} from the curated Hugging Face catalog." }
                        ?: "Download a curated model.",
                    changes = listOfNotNull(
                        entry?.let { "Model: ${it.name}" },
                        entry?.let { "Size: ${FormatUtils.formatBytesForMessage(it.expectedBytes)}" },
                        entry?.let { "License: ${it.license}" },
                        entry?.expectedSha256?.let { "Hash verification: SHA-256 expected" }
                            ?: "Hash verification: metadata/pointer verification when available",
                    ),
                    riskNotes = listOf("Uses network and app storage. Only curated catalog entries are allowed."),
                    confirmLabel = "Download",
                    networkRequired = true,
                )
            }
            "switch_model" -> {
                val target = call.arguments.optString("model_id")
                base.copy(
                    title = "Switch model?",
                    summary = "Switch from ${uiState.currentModel.value ?: "none"} to ${target.ifBlank { "selected model" }}.",
                    changes = listOf(
                        "Current: ${uiState.currentModel.value ?: "none"}",
                        "Target: ${target.ifBlank { "unknown" }}",
                    ),
                    riskNotes = listOf("Active generation may need to stop before switching. Runtime settings are preserved unless changed separately."),
                    confirmLabel = "Switch",
                )
            }
            "run_benchmark" -> {
                val presetId = call.arguments.optString("preset_id", "coding")
                base.copy(
                    title = "Run benchmark?",
                    summary = "Run benchmark preset: $presetId.",
                    changes = listOf(
                        "Preset: $presetId",
                        "Model: ${uiState.currentModel.value ?: "none"}",
                        "Result will be saved to local benchmark history.",
                    ),
                    riskNotes = listOf("Benchmarks can use battery, increase heat, and take time."),
                    confirmLabel = "Run benchmark",
                )
            }
            "cancel_active_job", "cancel_active_operation" -> base.copy(
                title = "Cancel active job?",
                summary = "Cancel active import, download, or benchmark work.",
                changes = listOf("Target: ${call.arguments.optString("target", "auto")}"),
                riskNotes = listOf("Partial download/import work may be stopped and need to be restarted later."),
                confirmLabel = "Cancel job",
            )
            "rename_current_chat" -> base.copy(
                title = "Rename chat?",
                summary = "Rename the current local chat.",
                changes = listOf("New title: ${call.arguments.optString("title", "auto title").take(64)}"),
                confirmLabel = "Rename",
            )
            else -> base
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────

    fun persist(call: AgentToolCall, originalPrompt: String, depth: Int) {
        val chatId = currentChatId() ?: return
        try {
            val json = JSONObject()
                .put("chatId", chatId)
                .put("toolName", call.name)
                .put("arguments", call.arguments.toString())
                .put("reason", call.reason ?: "")
                .put("originalPrompt", originalPrompt)
                .put("depth", depth)

            val key = pendingActionKey(chatId)
            val success = prefs.edit()
                .putString(key, json.toString())
                .commit()
            if (success) {
                Log.d(TAG, "Persisted pending agent tool call: ${call.name} for chat $chatId")
            } else {
                Log.w(TAG, "Failed to commit pending agent tool call: ${call.name} for chat $chatId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist pending agent tool call", e)
        }
    }

    fun restore(): Boolean {
        val chatId = currentChatId()
        if (chatId.isNullOrBlank()) {
            clearMemory()
            return false
        }
        val key = pendingActionKey(chatId)
        val rawJson = prefs.getString(key, null)
        if (rawJson == null) {
            clearMemory()
            return false
        }
        return try {
            val json = JSONObject(rawJson)
            val storedChatId = json.optString("chatId")
            if (storedChatId == chatId) {
                val toolName = json.optString("toolName")
                val argumentsStr = json.optString("arguments")
                val reason = json.optString("reason").takeIf { it.isNotBlank() }
                val originalPrompt = json.optString("originalPrompt")
                val depth = json.optInt("depth", 0)

                val arguments = if (argumentsStr.isNotBlank()) JSONObject(argumentsStr) else JSONObject()
                val call = AgentToolCall(toolName, arguments, reason)
                val definition = com.prismai.llmhost.AgentToolRegistry.find(toolName)

                if (definition != null) {
                    pendingCall = call
                    pendingOriginalPrompt = originalPrompt
                    pendingDepth = depth
                    val actionId = "agent_tool_${SystemClock.uptimeMillis()}"
                    uiState._pendingAgentToolAction.value = build(actionId, call, definition)
                    Log.d(TAG, "Restored pending agent tool call: $toolName for chat $chatId")
                    true
                } else {
                    Log.w(TAG, "Restored pending agent tool call failed: Unknown tool $toolName")
                    clearPrefs(chatId)
                    false
                }
            } else {
                Log.d(TAG, "Discarded stale pending agent tool call for chat $storedChatId (current: $chatId)")
                clearPrefs(chatId)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore pending agent tool call", e)
            clearPrefs(chatId)
            false
        }
    }

    fun clearMemory() {
        pendingCall = null
        pendingOriginalPrompt = null
        pendingDepth = 0
        uiState._pendingAgentToolAction.value = null
    }

    fun clearPrefs(chatId: String? = null) {
        val targetChatId = chatId ?: currentChatId()
        clearMemory()
        if (!targetChatId.isNullOrBlank()) {
            val key = pendingActionKey(targetChatId)
            val success = prefs.edit()
                .remove(key)
                .commit()
            if (success) {
                Log.d(TAG, "Cleared pending agent tool SharedPreferences for chat $targetChatId")
            } else {
                Log.w(TAG, "Failed to commit clearing pending agent tool SharedPreferences for chat $targetChatId")
            }
        }
    }

    fun cleanupStale(validChatIds: Set<String>) {
        prefs.all.keys
            .filter { it.startsWith(pendingActionKey("")) }
            .forEach { key ->
                val rawJson = prefs.getString(key, null) ?: return@forEach
                try {
                    val chatId = JSONObject(rawJson).optString("chatId")
                    if (chatId !in validChatIds) {
                        prefs.edit().remove(key).apply()
                        Log.d(TAG, "Cleaned up stale pending agent tool pref for chat $chatId")
                    }
                } catch (_: Exception) {
                    prefs.edit().remove(key).apply()
                }
            }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun resolveToolChatSession(chatIdArg: String): ChatSession? {
        val chatId = if (chatIdArg == "current" || chatIdArg.isBlank()) currentChatId() else chatIdArg
        return uiState.chatSessions.value.firstOrNull { it.id == chatId }
    }

}
