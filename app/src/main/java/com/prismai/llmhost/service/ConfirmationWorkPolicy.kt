package com.prismai.llmhost.service

import com.prismai.llmhost.agent.AgentToolConfirmation

internal fun hasPendingConfirmationWork(
    confirmation: AgentToolConfirmation,
    activeChainId: Long?,
    activeChatId: String?,
    hasActiveToolJob: Boolean,
): Boolean {
    if (hasActiveToolJob) return true
    return listOfNotNull(
        confirmation.pendingAuthorization,
        confirmation.consumedAuthorization,
    ).any { authorization ->
        confirmation.isAuthorizationCurrent(authorization, activeChainId, activeChatId)
    }
}
