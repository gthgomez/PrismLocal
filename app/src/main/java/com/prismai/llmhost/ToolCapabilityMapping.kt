package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

/**
 * Declares which capabilities each tool requires.
 * This is the central security policy — tools cannot execute without their declared capabilities.
 *
 * Replaces: restrictedReason() keyword matching in AgentTools.kt
 */
object ToolCapabilityMapping {

    private val map: Map<String, Set<Capability>> = mapOf(
        // Chat tools
        "summarize_current_chat" to setOf(Capability.CHAT_READ),
        "search_chats" to setOf(Capability.CHAT_READ),
        "rename_current_chat" to setOf(Capability.CHAT_MANAGE),
        "delete_chat" to setOf(Capability.CHAT_MANAGE),
        "create_chat" to setOf(Capability.CHAT_MANAGE),
        "clear_chat" to setOf(Capability.CHAT_MANAGE),
        "delete_or_clear_chat" to setOf(Capability.CHAT_MANAGE),
        "export_chat" to setOf(Capability.FILE_WRITE),

        // Model tools
        "switch_model" to setOf(Capability.MODEL_SWITCH),
        "list_installed_models" to setOf(Capability.SYSTEM_INFO),
        "list_curated_downloadable_models" to setOf(Capability.SYSTEM_INFO),
        "download_model" to setOf(Capability.MODEL_DOWNLOAD),
        "delete_model" to setOf(Capability.MODEL_DELETE),
        "get_model_card" to setOf(Capability.SYSTEM_INFO),
        "import_model" to setOf(Capability.MODEL_IMPORT),

        // Generation tools
        "recommend_runtime_settings" to setOf(Capability.GENERATION_CONFIGURE),
        "set_runtime_settings" to setOf(Capability.GENERATION_CONFIGURE),
        "restore_previous_runtime_settings" to setOf(Capability.GENERATION_CONFIGURE),
        "run_benchmark" to setOf(Capability.BENCHMARK_RUN),
        "list_benchmark_runs" to setOf(Capability.SYSTEM_INFO),

        // Memory tools
        "remember_fact" to setOf(Capability.MEMORY_WRITE),
        "recall_facts" to setOf(Capability.MEMORY_READ),
        "forget_fact" to setOf(Capability.MEMORY_WRITE),
        "list_memories" to setOf(Capability.MEMORY_READ),

        // Network tools
        "web_search" to setOf(Capability.NETWORK_SEARCH),

        // Future tools (voice, data connectors) — declared here for forward compat
        "voice_input" to setOf(Capability.VOICE_INPUT),
        "speak_output" to setOf(Capability.VOICE_OUTPUT),
        "stop_speaking" to setOf(Capability.VOICE_OUTPUT),
        "search_contacts" to setOf(Capability.CONTACTS_READ),
        "get_calendar_events" to setOf(Capability.CALENDAR_READ),
        "list_sms_threads" to setOf(Capability.SMS_READ),

        // RAG tools (Phase 2)
        "ingest_document" to setOf(Capability.FILE_READ),
        "search_documents" to setOf(Capability.FILE_READ),
        "list_documents" to setOf(Capability.FILE_READ),
        "delete_document" to setOf(Capability.FILE_WRITE),

        // Phase 3 Knowledge Pack
        "search_knowledge" to setOf(Capability.FILE_READ),
        "fetch_grokipedia_article" to setOf(Capability.NETWORK_SEARCH),
        "list_knowledge_packs" to setOf(Capability.SYSTEM_INFO),
        "download_knowledge_pack" to setOf(Capability.NETWORK_SEARCH),

        // Phase 7a Background agent
        "run_in_background" to setOf(Capability.CHAT_MANAGE),
        "check_background_tasks" to setOf(Capability.SYSTEM_INFO),
        "cancel_background_task" to setOf(Capability.CHAT_MANAGE),
    )

    fun capabilitiesFor(toolName: String): Set<Capability> = map[toolName] ?: emptySet()

    /** Check if a tool's capabilities are all granted */
    fun check(toolName: String, registry: CapabilityRegistry): CapabilityCheck =
        registry.check(capabilitiesFor(toolName))
}
