package com.prismai.llmhost.chat

/**
 * Distinguishes the intelligence source for conversational inference.
 *
 * Orthogonal to [com.prismai.llmhost.work.ExecutionTargetId], which determines
 * where software engineering Work tasks execute.
 */
enum class ChatBackend(
    val id: String,
    val displayName: String,
    val description: String,
) {
    /** On-device inference via llama.cpp / GGUF engine */
    LOCAL_LLAMA(
        id = "local_llama",
        displayName = "Local Llama (On-Device)",
        description = "Runs privately on-device using local GGUF models",
    ),

    /** Multi-provider cloud intelligence routed through Prismatix */
    PRISMATIX_CLOUD(
        id = "prismatix_cloud",
        displayName = "Prismatix Cloud",
        description = "Frontier cloud intelligence with cost-effective multi-provider routing",
    ),
}
