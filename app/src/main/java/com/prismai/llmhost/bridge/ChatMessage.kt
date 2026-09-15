package com.prismai.llmhost.bridge

/**
 * A single chat turn for the structured (role-preserving) generation path.
 *
 * Unlike the legacy string path — which flattens history into one prose blob and
 * lets native code wrap everything as a single `user` message — these turns are
 * handed to the model's real chat template, so system/user/assistant structure
 * reaches the model.
 */
data class ChatMessage(
    val role: String,
    val content: String,
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
