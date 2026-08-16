package com.prismai.llmhost.work

import com.prismai.llmhost.work.portable.WorkflowRunV1

enum class WorkAuthority {
    LOCAL_PRISM_AUTHORITY,
    BABEL_NATIVE_AUTHORITY,
}

enum class WorkSessionState {
    CREATED,
    RUNNING,
    WAITING_FOR_CONFIRMATION,
    COMPLETED_VERIFIED,
    COMPLETED_UNVERIFIED,
    BLOCKED,
    FAILED,
    CANCELLED,
}

/**
 * Client-side session state for a Work operation in Prism.
 *
 * Explicitly records [authority] so that Prism never confuses client-synthesized
 * outcome with native authoritative proof.
 */
data class WorkSession(
    val sessionId: String,
    val objective: String,
    val targetId: ExecutionTargetId,
    val authority: WorkAuthority,
    val workspaceRootPath: String,
    val state: WorkSessionState,
    val currentWorkflowProjection: WorkflowRunV1? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val completedAtEpochMs: Long? = null,
) {
    /**
     * True if the session has concluded in an authoritative verified state.
     * When [authority] is [WorkAuthority.BABEL_NATIVE_AUTHORITY], this is ONLY true
     * if backed by a valid [WorkflowRunV1] terminal of kind `completed_verified`.
     */
    val isAuthoritativelyVerified: Boolean
        get() = when (authority) {
            WorkAuthority.LOCAL_PRISM_AUTHORITY -> false // Local Prism cannot grant SWE verified completion
            WorkAuthority.BABEL_NATIVE_AUTHORITY ->
                state == WorkSessionState.COMPLETED_VERIFIED &&
                    currentWorkflowProjection?.terminal is com.prismai.llmhost.work.portable.TerminalOutcomeV1.CompletedVerified
        }
}
