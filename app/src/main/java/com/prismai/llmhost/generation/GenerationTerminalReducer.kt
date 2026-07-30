package com.prismai.llmhost.generation

/**
 * Pure terminal-reason merge/resolve for generation flows.
 * Keeps QUALITY_ABORT precedence testable without a Flow engine.
 */
object GenerationTerminalReducer {

    data class TerminalState(
        val reason: String? = null,
        val detail: String? = null,
    )

    /**
     * Merge a native terminal chunk into the current reason/detail.
     * QUALITY_ABORT is sticky and is never overwritten by CANCELLED/ERROR.
     */
    fun mergeTerminalChunk(
        current: TerminalState,
        chunkReason: String,
    ): TerminalState {
        if (current.reason == "QUALITY_ABORT") return current
        val detail = if (chunkReason == "ERROR" && current.detail.isNullOrBlank()) {
            "native_runtime_error"
        } else {
            current.detail
        }
        return TerminalState(reason = chunkReason, detail = detail)
    }

    /**
     * Resolve the final terminal reason after stream completion.
     * When [checkEmptyStart] is true and no reason/cancel and zero tokens,
     * returns ERROR with generation_did_not_start.
     */
    fun resolveFinal(
        current: TerminalState,
        causeIsCancellation: Boolean,
        checkEmptyStart: Boolean,
        generatedTokens: Int,
        userStopLikely: Boolean = false,
    ): TerminalState {
        var reason = current.reason
        var detail = current.detail

        if (checkEmptyStart && reason == null && !causeIsCancellation && generatedTokens == 0) {
            reason = "ERROR"
            detail = "generation_did_not_start"
        }

        val finalReason = when {
            reason == "QUALITY_ABORT" -> "QUALITY_ABORT"
            reason != null -> reason
            causeIsCancellation -> "CANCELLED"
            else -> "EOF"
        }

        if (finalReason == "CANCELLED" && detail.isNullOrBlank()) {
            detail = if (userStopLikely) "user_stop" else "user_or_system_cancel"
        }

        return TerminalState(reason = finalReason, detail = detail)
    }
}
