package com.prismai.llmhost.generation

/**
 * Shared-engine ownership decisions used by [com.prismai.llmhost.service.InferenceService].
 * Background work keeps its source chat, and a deferred UI switch is applied only
 * after that work releases the engine.
 */
internal class ServiceGenerationOwnership(
    val engine: BackgroundGenerationOwnership = BackgroundGenerationOwnership(),
) {
    data class PreparedBackgroundChat(
        val ownerChatId: String,
        val restoreChatId: String?,
        val switchedToSource: Boolean,
    )

    fun mustDeferBackgroundTask(
        generationRunning: Boolean,
        confirmationPending: Boolean,
        followUpScheduled: Boolean,
        agentToolJobActive: Boolean,
    ): Boolean = BackgroundGenerationOwnership.shouldDeferBackgroundTask(
        generationRunning = generationRunning,
        confirmationPending = confirmationPending,
        followUpScheduled = followUpScheduled,
        agentToolJobActive = agentToolJobActive,
    )

    fun prepareBackgroundChat(
        taskId: String?,
        sourceChatId: String?,
        selectedChatId: String?,
        chatExists: (String) -> Boolean,
        switchToSource: (String) -> Boolean,
    ): PreparedBackgroundChat {
        if (!engine.isOwner(taskId)) {
            throw IllegalStateException("Background task generation ownership changed")
        }
        val ownerChatId = sourceChatId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Background task has no source chat owner")
        if (!chatExists(ownerChatId)) {
            throw IllegalStateException("Background task source chat was deleted")
        }
        val switchedToSource = selectedChatId != ownerChatId
        if (switchedToSource && !switchToSource(ownerChatId)) {
            throw IllegalStateException("Could not restore background task source chat")
        }
        return PreparedBackgroundChat(
            ownerChatId = ownerChatId,
            restoreChatId = if (switchedToSource) selectedChatId else null,
            switchedToSource = switchedToSource,
        )
    }

    fun chatToRestore(
        taskId: String?,
        restoreChatId: String?,
        generating: Boolean,
        sessionStillWaiting: Boolean,
        currentChatId: String?,
        sourceChatId: String?,
        chatExists: (String) -> Boolean,
    ): String? {
        if (generating || sessionStillWaiting) return null
        if (sourceChatId == null || currentChatId != sourceChatId) return null
        val requested = if (!taskId.isNullOrBlank()) {
            engine.takeDeferredChatSwitch(taskId) ?: restoreChatId
        } else {
            restoreChatId
        }
        return requested?.takeIf(chatExists)
    }
}
