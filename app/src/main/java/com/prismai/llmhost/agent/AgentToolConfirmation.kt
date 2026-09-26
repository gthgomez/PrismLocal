package com.prismai.llmhost.agent
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.SharedPreferences
import android.util.Log
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.util.FormatUtils
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Builds UI-facing tool-confirmation cards and manages the persistence
 * lifecycle of pending agent tool calls (save / restore / clear).
 *
 * Owns the immutable in-memory confirmation authorization snapshot and the
 * safe persistence lifecycle for pending tool cards.
 */
class AgentToolConfirmation(
    private val uiState: ServiceUiState,
    private val prefs: SharedPreferences,
    private val currentChatId: () -> String?,
    private val pendingActionKey: (String) -> String,
    private val getDeviceProfile: () -> DeviceCapabilityProfile?,
    private val capabilityRegistry: CapabilityRegistry = CapabilityRegistryHolder.registry,
) {
    companion object {
        private const val TAG = "AgentToolConfirm"

        fun fingerprint(call: AgentToolCall): String {
            val payload = buildString {
                append(call.name)
                append('\u0000')
                append(call.arguments.toString())
                append('\u0000')
                append(call.reason.orEmpty())
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        fun resolveTarget(call: AgentToolCall, sourceChatId: String): String = when (call.name) {
            "switch_model", "delete_model" -> call.arguments.optString("model_id").trim()
            "clear_chat", "delete_chat", "delete_or_clear_chat" ->
                call.arguments.optString("chat_id", "current").let { requested ->
                    if (requested.isBlank() || requested == "current") sourceChatId else requested
                }
            else -> sourceChatId
        }.ifBlank { sourceChatId }
    }

    data class PendingToolAuthorization(
        val token: String,
        val call: AgentToolCall,
        val originalPrompt: String,
        val depth: Int,
        val chainId: Long,
        val sourceChatId: String,
        val resolvedTarget: String,
        val callFingerprint: String,
        val capabilityAuthorization: CapabilityAuthorizationSnapshot,
    )

    // ── In-memory pending state ─────────────────────────────────────────

    @Volatile
    private var pending: PendingToolAuthorization? = null
    @Volatile
    private var consumedToken: String? = null

    val pendingAuthorization: PendingToolAuthorization?
        get() = pending?.let { immutableCopy(it) }

    /**
     * Publishes one immutable authorization snapshot. A confirmation without
     * both a chain owner and source chat is never made actionable.
     */
    fun stagePending(
        call: AgentToolCall,
        originalPrompt: String,
        depth: Int,
        chainId: Long?,
        sourceChatId: String?,
    ): PendingToolAuthorization? {
        if (consumedToken != null) return null
        val source = sourceChatId?.takeIf { it.isNotBlank() } ?: return null
        if (chainId == null || currentChatId() != source) return null
        val safeCall = copyCall(call)
        if (!ToolCapabilityMapping.isMapped(safeCall.name)) return null
        val capabilityAuthorization = capabilityRegistry.snapshot(
            ToolCapabilityMapping.capabilitiesFor(safeCall.name),
            requireAgentMode = true,
        )
        if (!capabilityAuthorization.grantedAtSnapshot) return null
        val authorization = PendingToolAuthorization(
            token = UUID.randomUUID().toString(),
            call = safeCall,
            originalPrompt = originalPrompt,
            depth = depth.coerceIn(0, (uiState.generationSettings.value.maxAgentIterations - 1).coerceAtLeast(0)),
            chainId = chainId,
            sourceChatId = source,
            resolvedTarget = resolveTarget(safeCall, source),
            callFingerprint = fingerprint(safeCall),
            capabilityAuthorization = capabilityAuthorization,
        )
        synchronized(this) {
            if (consumedToken != null) return null
            pending = authorization
        }
        return immutableCopy(authorization)
    }

    /** Atomically consumes the currently staged authorization, if any. */
    fun consumePending(): PendingToolAuthorization? {
        synchronized(this) {
            val snapshot = pending ?: return null
            pending = null
            return immutableCopy(snapshot)
        }
    }

    /** Atomically consumes only the exact opaque token that was staged. */
    fun consumePending(token: String): PendingToolAuthorization? {
        if (token.isBlank()) return null
        synchronized(this) {
            val snapshot = pending ?: return null
            if (snapshot.token != token) return null
            pending = null
            consumedToken = snapshot.token
            return immutableCopy(snapshot)
        }
    }

    /**
     * Atomically consumes and rechecks chain/chat ownership. Stale cards are
     * removed rather than left actionable.
     */
    fun consumePendingAndRevalidate(
        token: String,
        activeChainId: Long?,
        activeChatId: String?,
    ): PendingToolAuthorization? {
        if (token.isBlank()) return null
        synchronized(this) {
            val snapshot = pending ?: return null
            if (snapshot.token != token) return null
            if (!isAuthorizationCurrent(snapshot, activeChainId, activeChatId)) {
                pending = null
                consumedToken = null
                uiState._pendingAgentToolAction.value = null
                return null
            }
            pending = null
            consumedToken = snapshot.token
            return immutableCopy(snapshot)
        }
    }

    fun isAuthorizationCurrent(
        authorization: PendingToolAuthorization,
        activeChainId: Long?,
        activeChatId: String?,
    ): Boolean =
        authorization.token.isNotBlank() &&
            authorization.chainId > 0L &&
            activeChainId == authorization.chainId &&
            activeChatId == authorization.sourceChatId &&
            authorization.resolvedTarget.isNotBlank() &&
            authorization.resolvedTarget == resolveTarget(authorization.call, authorization.sourceChatId) &&
            authorization.callFingerprint == fingerprint(authorization.call) &&
            capabilityRegistry.isCurrent(authorization.capabilityAuthorization)

    /** The owner stays valid for reporting work admitted before a later revocation. */
    fun isOperationOwnerCurrent(
        authorization: PendingToolAuthorization,
        activeChainId: Long?,
        activeChatId: String?,
    ): Boolean =
        authorization.token.isNotBlank() &&
            authorization.chainId > 0L &&
            activeChainId == authorization.chainId &&
            activeChatId == authorization.sourceChatId &&
            authorization.resolvedTarget.isNotBlank() &&
            authorization.resolvedTarget == resolveTarget(authorization.call, authorization.sourceChatId) &&
            authorization.callFingerprint == fingerprint(authorization.call)

    /** Linearize admission of this operation against concurrent policy changes. */
    fun tryBeginDispatch(authorization: PendingToolAuthorization): Boolean =
        capabilityRegistry.tryBeginDispatch(authorization.capabilityAuthorization)

    /** Clears the consumed card without clobbering a newer staged token. */
    fun clearConsumedAuthorization(authorization: PendingToolAuthorization) {
        synchronized(this) {
            val current = pending
            if (current != null && current.token != authorization.token) return
            pending = null
            if (consumedToken == authorization.token) consumedToken = null
            uiState._pendingAgentToolAction.value = null
            removePersisted(authorization.sourceChatId)
        }
    }

    /** Discards a stale token only if no newer authorization has replaced it. */
    fun discardStaleAuthorization(token: String, sourceChatId: String) {
        synchronized(this) {
            val current = pending
            if (current != null && current.token != token) return
            pending = null
            if (consumedToken == token) consumedToken = null
            uiState._pendingAgentToolAction.value = null
            removePersisted(sourceChatId)
        }
    }

    // ── Confirmation builder ────────────────────────────────────────────

    fun build(
        id: String,
        call: AgentToolCall,
        definition: AgentToolDefinition,
    ): PendingAgentToolAction {
        val sanitizedName = com.prismai.llmhost.util.SanitizerUtils.stripControlCharacters(call.name)
        val sanitizedDescription = com.prismai.llmhost.util.SanitizerUtils.stripControlCharacters(definition.description)
        val sanitizedArguments = com.prismai.llmhost.util.SanitizerUtils.stripControlCharacters(call.arguments.toString(2))
        val sanitizedReason = call.reason?.takeIf { it.isNotBlank() }?.let { com.prismai.llmhost.util.SanitizerUtils.stripControlCharacters(it) }

        val base = PendingAgentToolAction(
            id = id,
            name = sanitizedName,
            description = sanitizedDescription,
            argumentsJson = sanitizedArguments,
            title = "Confirm $sanitizedName",
            summary = sanitizedDescription,
            riskNotes = sanitizedReason?.let { listOf("Agent reason: $it") }.orEmpty(),
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
            "delete_model" -> {
                val modelId = call.arguments.optString("model_id")
                base.copy(
                    title = "Delete installed model?",
                    summary = "Delete GGUF model $modelId from app storage.",
                    changes = listOf(
                        "Model: $modelId",
                    ),
                    riskNotes = listOf("Permanently removes the GGUF model binary from device storage."),
                    confirmLabel = "Delete Model",
                    destructive = true,
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

    fun persist(authorization: PendingToolAuthorization) {
        val chatId = authorization.sourceChatId
        try {
            // Chain ownership and the opaque token are intentionally not
            // persisted: a restored card cannot be safely authorized.
            val json = JSONObject()
                .put("chatId", chatId)
                .put("toolName", authorization.call.name)
                .put("arguments", authorization.call.arguments.toString())
                .put("reason", authorization.call.reason ?: "")
                .put("originalPrompt", authorization.originalPrompt)
                .put("depth", authorization.depth)

            val key = pendingActionKey(chatId)
            prefs.edit()
                .putString(key, json.toString())
                .apply()
            logDebug("Scheduled pending agent tool persistence: ${authorization.call.name} for chat $chatId")
        } catch (e: Exception) {
            logError("Failed to persist pending agent tool call", e)
        }
    }

    fun restore(): Boolean {
        val chatId = currentChatId()
        if (chatId.isNullOrBlank()) {
            clearMemory()
            return false
        }
        val key = pendingActionKey(chatId)
        if (prefs.getString(key, null) == null) {
            clearMemory()
            return false
        }
        // Persisted confirmations intentionally contain no chain owner or
        // opaque token. Treat every restored card as dead instead of creating
        // a confirmation that can execute against a newer chat/chain.
        clearPrefs(chatId)
        return false
    }

    fun clearMemory() {
        synchronized(this) {
            pending = null
            consumedToken = null
        }
        uiState._pendingAgentToolAction.value = null
    }

    fun clearPrefs(chatId: String? = null) {
        val targetChatId = chatId ?: currentChatId()
        clearMemory()
        removePersisted(targetChatId)
    }

    /** Removes only a source-chat preference; it cannot clobber a newer in-memory card. */
    fun clearPersistedForChat(chatId: String?) {
        removePersisted(chatId)
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
                        logDebug("Cleaned up stale pending agent tool pref for chat $chatId")
                    }
                } catch (_: Exception) {
                    prefs.edit().remove(key).apply()
                }
            }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun removePersisted(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        val key = pendingActionKey(chatId)
        prefs.edit()
            .remove(key)
            .apply()
        logDebug("Scheduled pending agent tool preference cleanup for chat $chatId")
    }

    private fun resolveToolChatSession(chatIdArg: String): ChatSession? {
        val chatId = if (chatIdArg == "current" || chatIdArg.isBlank()) currentChatId() else chatIdArg
        return uiState.chatSessions.value.firstOrNull { it.id == chatId }
    }

    private fun copyCall(call: AgentToolCall): AgentToolCall =
        AgentToolCall(
            name = call.name,
            arguments = JSONObject(call.arguments.toString()),
            reason = call.reason,
        )

    private fun immutableCopy(authorization: PendingToolAuthorization): PendingToolAuthorization =
        authorization.copy(
            call = copyCall(authorization.call),
            capabilityAuthorization = authorization.capabilityAuthorization.copy(
                required = authorization.capabilityAuthorization.required.toSet(),
            ),
        )

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logError(message: String, error: Throwable) {
        runCatching { Log.e(TAG, message, error) }
    }

}
