package com.prismai.llmhost.generation

/** Tracks the task allowed to own the shared generation engine and stream. */
class BackgroundGenerationOwnership {
    private var ownerTaskId: String? = null
    private var deferredChatId: String? = null
    private var ownerAgentChainId: Long? = null

    companion object {
        fun shouldDeferBackgroundTask(
            generationRunning: Boolean,
            confirmationPending: Boolean,
            followUpScheduled: Boolean,
            agentToolJobActive: Boolean,
        ): Boolean = generationRunning || confirmationPending || followUpScheduled || agentToolJobActive
    }

    @Synchronized
    fun begin(taskId: String): Boolean {
        if (taskId.isBlank() || ownerTaskId != null) return false
        ownerTaskId = taskId
        deferredChatId = null
        ownerAgentChainId = null
        return true
    }

    @Synchronized
    fun isOwner(taskId: String?): Boolean = !taskId.isNullOrBlank() && ownerTaskId == taskId

    @Synchronized
    fun hasOwner(): Boolean = ownerTaskId != null

    @Synchronized
    fun ownerId(): String? = ownerTaskId

    @Synchronized
    fun bindAgentChain(taskId: String, chainId: Long?) {
        if (ownerTaskId == taskId) ownerAgentChainId = chainId
    }

    @Synchronized
    fun allowsAgentFollowUp(chainId: Long?): Boolean =
        ownerTaskId == null || (chainId != null && ownerAgentChainId == chainId)

    @Synchronized
    fun deferChatSwitch(chatId: String): Boolean {
        if (ownerTaskId == null) return false
        deferredChatId = chatId
        return true
    }

    @Synchronized
    fun takeDeferredChatSwitch(taskId: String): String? =
        if (ownerTaskId == taskId) deferredChatId.also { deferredChatId = null } else null

    @Synchronized
    fun finish(taskId: String): String? {
        if (ownerTaskId != taskId) return null
        ownerTaskId = null
        ownerAgentChainId = null
        return deferredChatId.also { deferredChatId = null }
    }
}
