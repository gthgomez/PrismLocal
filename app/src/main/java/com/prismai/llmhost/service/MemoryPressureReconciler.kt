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
    /**
     * Invoked exactly once each time an observation transitions the reconciler
     * INTO CRITICAL, from either the push or the poll path. The service uses it
     * to save the transcript and raise the UI alert on a real transition rather
     * than on every repeated CRITICAL observation. Declared first so [apply]
     * remains the trailing lambda.
     */
    private val onCriticalTransition: () -> Unit = {},
    private val apply: (Int) -> Unit,
) {
    // Guards lastApplied/critical and the order in which levels are handed to
    // apply(). The transition callback runs OUTSIDE this lock so a slow
    // transcript save on one path can never block the other (including the main
    // thread, which receives the push callback).
    private val stateLock = Any()
    private var lastApplied: Int? = null
    private var critical = false

    /** Push-based trim/low-memory callback. Escalates immediately. */
    fun onPush(state: MemoryState) {
        val transitioned = synchronized(stateLock) {
            if (state.level >= MemoryState.CRITICAL.level) {
                escalateLocked()
            } else if (critical) {
                // A push that is milder than an active CRITICAL must not clear it.
                false
            } else {
                write(state.level)
                false
            }
        }
        if (transitioned) onCriticalTransition()
    }

    /**
     * Polling observation. A NORMAL poll is the only signal that can mean
     * "memory recovered", so it is allowed to clear an escalation - including
     * one that came from the push path. Any milder observation while CRITICAL
     * is sticky and is not written.
     */
    fun onPoll(state: MemoryState) {
        val transitioned = synchronized(stateLock) {
            when {
                state.level >= MemoryState.CRITICAL.level -> escalateLocked()
                critical && state.level == MemoryState.NORMAL.level -> {
                    critical = false
                    write(MemoryState.NORMAL.level)
                    false
                }
                critical -> false
                else -> {
                    write(state.level)
                    false
                }
            }
        }
        if (transitioned) onCriticalTransition()
    }

    fun currentLevel(): Int = synchronized(stateLock) { lastApplied ?: MemoryState.NORMAL.level }

    fun isCritical(): Boolean = synchronized(stateLock) { critical }

    /**
     * Enter CRITICAL while holding [stateLock]. Returns true only on the
     * transition edge, so repeated CRITICAL observations do not re-run the
     * transition work.
     */
    private fun escalateLocked(): Boolean {
        val wasCritical = critical
        critical = true
        write(MemoryState.CRITICAL.level)
        return !wasCritical
    }

    private fun write(level: Int) {
        if (lastApplied == level) return
        lastApplied = level
        apply(level)
    }
}
