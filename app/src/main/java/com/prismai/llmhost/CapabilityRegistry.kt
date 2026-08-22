package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

/**
 * Capability-based security model replacing keyword-matching restrictedReason().
 *
 * Each capability represents a class of operations. Tools declare required capabilities.
 * The registry enforces: a tool without its required capabilities cannot execute.
 *
 * Capabilities are statically assigned — the model cannot self-escalate.
 */
enum class Capability(
    val description: String,
    val risk: AgentToolRisk, // minimum risk level required
) {
    // Core (always available)
    CHAT_READ("Read chat transcripts", AgentToolRisk.SAFE),
    CHAT_MANAGE("Create/rename/delete chats", AgentToolRisk.CONFIRM),

    // Model management
    MODEL_SWITCH("Switch active model", AgentToolRisk.CONFIRM),
    MODEL_IMPORT("Import new models", AgentToolRisk.RESTRICTED),
    MODEL_DELETE("Delete installed models", AgentToolRisk.CONFIRM),
    MODEL_DOWNLOAD("Download models from Hugging Face", AgentToolRisk.RESTRICTED),

    // Generation
    GENERATION_CONFIGURE("Change generation settings", AgentToolRisk.CONFIRM),
    BENCHMARK_RUN("Run performance benchmarks", AgentToolRisk.CONFIRM),

    // Data access
    FILE_READ("Read user files", AgentToolRisk.RESTRICTED),
    FILE_WRITE("Write/export files", AgentToolRisk.RESTRICTED),
    NETWORK_SEARCH("Search the web", AgentToolRisk.SAFE), // SAFE because sandboxed
    CONTACTS_READ("Read contacts", AgentToolRisk.CONFIRM),
    CALENDAR_READ("Read calendar", AgentToolRisk.CONFIRM),
    SMS_READ("Read SMS messages", AgentToolRisk.CONFIRM),

    // Memory
    MEMORY_READ("Read stored memories", AgentToolRisk.SAFE),
    MEMORY_WRITE("Store/update/delete memories", AgentToolRisk.CONFIRM),

    // Voice
    VOICE_INPUT("Use microphone for voice input", AgentToolRisk.SAFE),
    VOICE_OUTPUT("Use text-to-speech output", AgentToolRisk.SAFE),

    // System
    SYSTEM_INFO("Read device/system information", AgentToolRisk.SAFE),

    // Work / Software Engineering
    WORKSPACE_READ("Read files inside an active workspace", AgentToolRisk.SAFE),
    WORKSPACE_WRITE("Modify files inside an active workspace", AgentToolRisk.CONFIRM),
    WORKSPACE_DELETE("Delete files inside an active workspace", AgentToolRisk.CONFIRM),
    SHELL_EXEC("Execute processes in a developer workspace", AgentToolRisk.RESTRICTED),
    GIT_READ("Inspect Git state and history", AgentToolRisk.SAFE),
    GIT_WRITE("Create branches and commits", AgentToolRisk.CONFIRM),
    GIT_REMOTE_WRITE("Push or modify remote Git repositories", AgentToolRisk.RESTRICTED),
    SWE_TASK_SUBMIT("Submit a software engineering task to execution authority", AgentToolRisk.SAFE),
}

data class CapabilityCheck(
    val granted: Boolean,
    val missingCapabilities: Set<Capability> = emptySet(),
    val reason: String = "",
)

class CapabilityRegistry {
    /** Capabilities available in the current session */
    private val enabled = mutableSetOf<Capability>()

    init {
        // Auto-grant SAFE and CONFIRM capabilities.
        // SAFE: no gate needed. CONFIRM: gated by user confirmation dialog, not by capability check.
        // Only RESTRICTED capabilities are blocked by default — they require explicit opt-in.
        enabled.addAll(Capability.entries.filter { it.risk != AgentToolRisk.RESTRICTED })
    }

    fun grant(capability: Capability) { enabled.add(capability) }
    fun revoke(capability: Capability) { enabled.remove(capability) }
    fun isGranted(capability: Capability): Boolean = capability in enabled

    /**
     * Check if all required capabilities for a tool are available.
     * Returns CapabilityCheck with missing capabilities if not.
     */
    fun check(required: Set<Capability>): CapabilityCheck {
        val missing = required - enabled
        return if (missing.isEmpty()) {
            CapabilityCheck(granted = true)
        } else {
            CapabilityCheck(
                granted = false,
                missingCapabilities = missing,
                reason = "Missing capabilities: ${missing.joinToString { it.name }}",
            )
        }
    }

    /** Grant all capabilities for a given risk level */
    fun grantAllUpTo(maxRisk: AgentToolRisk) {
        Capability.entries.filter { it.risk <= maxRisk }.forEach { enabled.add(it) }
    }

    fun dump(): Set<Capability> = enabled.toSet()
}
