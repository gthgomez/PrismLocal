package com.prismai.llmhost.agent.tools

import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.tools.AgentToolErrorCode
import com.prismai.llmhost.tools.AgentToolResult
import org.json.JSONObject

/** Owner-safe callbacks shared by confirmed chat identity tools. */
internal object ChatIdentityOperations {
    fun clear(
        call: AgentToolCall,
        confirmed: Boolean,
        currentChatId: () -> String?,
        chatSessions: () -> List<ChatSession>,
        switchChat: (String) -> Boolean,
        clearTranscript: () -> Boolean,
    ): AgentToolResult {
        if (!confirmed) {
            return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Chat clear requires confirmation")
        }
        val chatIdArg = call.arguments.optString("chat_id", "current")
        val chatId = if (chatIdArg == "current" || chatIdArg.isBlank()) currentChatId() else chatIdArg
        val session = chatSessions().firstOrNull { it.id == chatId }
            ?: return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "Chat not found")
        if (session.id != currentChatId() && !switchChat(session.id)) {
            return toolFailure(call, AgentToolErrorCode.FAILED, "Chat switch was rejected")
        }
        if (!clearTranscript()) {
            return toolFailure(call, AgentToolErrorCode.FAILED, "Chat clear was rejected")
        }
        return toolSuccess(
            call = call,
            summary = "Cleared ${session.title}",
            details = JSONObject()
                .put("chat_id", session.id)
                .put("title", session.title)
                .put("action", "clear_messages"),
        )
    }

    fun delete(
        call: AgentToolCall,
        confirmed: Boolean,
        currentChatId: () -> String?,
        chatSessions: () -> List<ChatSession>,
        deleteChat: (String) -> Boolean,
    ): AgentToolResult {
        if (!confirmed) {
            return toolFailure(call, AgentToolErrorCode.CONFIRMATION_REQUIRED, "Chat delete requires confirmation")
        }
        val chatIdArg = call.arguments.optString("chat_id", "current")
        val chatId = if (chatIdArg == "current" || chatIdArg.isBlank()) currentChatId() else chatIdArg
        val session = chatSessions().firstOrNull { it.id == chatId }
            ?: return toolFailure(call, AgentToolErrorCode.NOT_FOUND, "Chat not found")
        if (!deleteChat(session.id)) {
            return toolFailure(call, AgentToolErrorCode.FAILED, "Chat delete was rejected")
        }
        return toolSuccess(
            call = call,
            summary = "Deleted ${session.title}",
            details = JSONObject()
                .put("chat_id", session.id)
                .put("title", session.title)
                .put("action", "delete_chat"),
        )
    }
}
