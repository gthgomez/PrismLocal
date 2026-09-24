package com.prismai.llmhost.service

import com.prismai.llmhost.lifecycle.ChatTransitionOutcome
import com.prismai.llmhost.tools.AgentToolErrorCode
import com.prismai.llmhost.tools.AgentToolResult

internal fun classifyConfirmedChatIdentityResult(result: AgentToolResult): ChatTransitionOutcome =
    if (result.success) {
        ChatTransitionOutcome.Applied
    } else if (result.errorCode == AgentToolErrorCode.BUSY) {
        ChatTransitionOutcome.Rejected(result.summary)
    } else {
        ChatTransitionOutcome.Failed(result.summary)
    }
