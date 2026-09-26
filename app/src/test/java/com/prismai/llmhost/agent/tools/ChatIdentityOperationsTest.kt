package com.prismai.llmhost.agent.tools

import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.tools.AgentToolErrorCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIdentityOperationsTest {

    private val sessions = listOf(
        ChatSession(
            id = "chat-1",
            title = "One",
            createdAt = 1L,
            updatedAt = 1L,
            modelId = null,
            messageCount = 0,
        ),
    )

    @Test
    fun confirmedClearUsesSerializedSwitchAndClearCallbacks() {
        var switchedTo: String? = null
        var clearCalled = false
        val result = ChatIdentityOperations.clear(
            call = AgentToolCall(
                "clear_chat",
                org.json.JSONObject().put("chat_id", "chat-1"),
            ),
            confirmed = true,
            currentChatId = { "chat-2" },
            chatSessions = { sessions },
            switchChat = { id ->
                switchedTo = id
                true
            },
            clearTranscript = {
                clearCalled = true
                true
            },
        )

        assertTrue(result.success)
        assertTrue(switchedTo == "chat-1")
        assertTrue(clearCalled)
    }

    @Test
    fun confirmedClearReportsRejectedTransition() {
        val result = ChatIdentityOperations.clear(
            call = AgentToolCall("clear_chat"),
            confirmed = true,
            currentChatId = { "chat-1" },
            chatSessions = { sessions },
            switchChat = { true },
            clearTranscript = { false },
        )

        assertFalse(result.success)
        assertTrue(result.errorCode == AgentToolErrorCode.FAILED)
    }

    @Test
    fun confirmedDeleteReportsAcceptedTransition() {
        var deleted: String? = null
        val result = ChatIdentityOperations.delete(
            call = AgentToolCall("delete_chat"),
            confirmed = true,
            currentChatId = { "chat-1" },
            chatSessions = { sessions },
            deleteChat = { id ->
                deleted = id
                true
            },
        )

        assertTrue(result.success)
        assertTrue(deleted == "chat-1")
    }

    @Test
    fun confirmedDeleteReportsRejectedTransition() {
        val result = ChatIdentityOperations.delete(
            call = AgentToolCall("delete_chat"),
            confirmed = true,
            currentChatId = { "chat-1" },
            chatSessions = { sessions },
            deleteChat = { false },
        )

        assertFalse(result.success)
        assertTrue(result.errorCode == AgentToolErrorCode.FAILED)
    }
}
