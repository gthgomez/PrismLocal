package com.prismai.llmhost.service

import com.prismai.llmhost.lifecycle.ChatTransitionOutcome
import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.tools.AgentToolErrorCode
import com.prismai.llmhost.tools.AgentToolResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmedChatIdentityPolicyTest {

    @Test
    fun busyConcreteResultIsRejected() {
        assertEquals(
            ChatTransitionOutcome.Rejected("busy"),
            classifyConfirmedChatIdentityResult(failure(AgentToolErrorCode.BUSY, "busy")),
        )
    }

    @Test
    fun concreteOperationFailuresRemainFailures() {
        listOf(
            AgentToolErrorCode.FAILED,
            AgentToolErrorCode.NOT_FOUND,
            AgentToolErrorCode.CONFIRMATION_REQUIRED,
            AgentToolErrorCode.INVALID_ARGUMENT,
        ).forEach { code ->
            assertEquals(
                ChatTransitionOutcome.Failed(code.name),
                classifyConfirmedChatIdentityResult(failure(code, code.name)),
            )
        }
    }

    private fun failure(code: AgentToolErrorCode, summary: String): AgentToolResult =
        AgentToolResult(
            call = AgentToolCall("clear_chat"),
            success = false,
            summary = summary,
            errorCode = code,
        )
}
