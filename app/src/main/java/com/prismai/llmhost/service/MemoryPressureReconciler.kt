package com.prismai.llmhost.service

/**
 * Single owner of the memory-pressure level written to native inference.
 *
 * Memory pressure reaches us from two independent paths: the push-based
 * `onTrimMemory` / `onLowMemory` callback, and the polling flow. Previously
 * each wrote to native itself, and only the polling flow was deduplicated, so a
 * CRITICAL push was followed by a NORMAL poll that was filtered as "unchanged"
 * and never cleared it — leaving native pressure pinned at CRITICAL and
 * cancelling generation indefinitely.
 *
 * The rule is: an observation from either path is applied unless it is identical
 * to the last applied level. Recovery therefore always writes explicitly, and
 * CRITICAL is never masked by a milder observation of the other kind.
 */
class MemoryPressureReconciler(
    private val apply: (Int) -> Unit,
) {
    private var lastApplied: Int? = null
    private var critical = false

    /** Push-based trim/low-memory callback. Escalates immediately. */
    fun onPush(state: MemoryState) {
        if (state.level >= MemoryState.CRITICAL.level) {
            critical = true
            write(state.level)
            return
        }
        // A push that is milder than an active CRITICAL must not clear it.
        if (critical) return
        write(state.level)
    }

    /**
     * Polling observation. A NORMAL poll is the only signal that can mean
     * "memory recovered", so it is allowed to clear an escalation - including
     * one that came from the push path. Any milder observation while CRITICAL
     * is sticky and is not written.
     */
    fun onPoll(state: MemoryState) {
        if (state.level >= MemoryState.CRITICAL.level) {
            critical = true
            write(state.level)
            return
        }
        if (critical) {
            if (state.level == MemoryState.NORMAL.level) {
                critical = false
                write(MemoryState.NORMAL.level)
            }
            return
        }
        write(state.level)
    }

    fun currentLevel(): Int = lastApplied ?: MemoryState.NORMAL.level

    fun isCritical(): Boolean = critical

    private fun write(level: Int) {
        if (lastApplied == level) return
        lastApplied = level
        apply(level)
    }
}
