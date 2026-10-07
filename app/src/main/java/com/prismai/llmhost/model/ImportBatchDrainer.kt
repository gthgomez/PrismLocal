package com.prismai.llmhost.model

/**
 * Coordinates a single batch-import drain over a [SequentialImportQueue].
 *
 * The original service code gated a second multi-select with
 * `batchImportJob?.isActive == true`. That check and the job assignment were
 * not atomic with the enqueue, and `drain()` returns as soon as the queue is
 * *momentarily* empty — before the post-drain `refreshInstalledModels()`
 * suspend resumes. A second `importModels` arriving during that refresh saw the
 * still-active (but idle) drain job, appended its URIs, and returned; nothing
 * ever drained them. The picker reported files that were never imported.
 *
 * This class makes enqueue/ensure-drain and the drain-loop's terminal check
 * atomic under one lock:
 *  - [enqueue] appends and only starts a new drain (returning its token) when
 *    no drain is running. While a drain is running it appends and returns
 *    `null`, relying on the running drain to observe the new work.
 *  - [drainAll] loops `drain()` -> `refresh()` -> re-check. It clears
 *    `draining` and stops only when the queue is empty *under the lock*;
 *    otherwise it loops again. Any URI enqueued during `refresh()` is caught.
 *  - [cancel] invalidates the current drain token, clears the queue, and resets
 *    `draining`, so cancelled files cannot be resurrected or duplicated.
 */
class ImportBatchDrainer(
    private val queue: SequentialImportQueue,
    private val refresh: suspend () -> Unit,
) {
    private val lock = Any()
    private var draining = false
    private var generation = 0L

    /**
     * Append [uris] and report whether the caller should launch [drainAll].
     *
     * @return the drain token to pass to [drainAll], or `null` when a drain is
     *   already running and will pick the new URIs up on its next pass.
     */
    fun enqueue(uris: List<String>): Long? = synchronized(lock) {
        queue.enqueueAll(uris)
        if (draining) return@synchronized null
        draining = true
        generation++
        generation
    }

    /**
     * Drain the queue, republishing installed models between passes.
     *
     * @param token the value returned by [enqueue]. A token that no longer
     *   matches the current generation (superseded by [cancel] or a newer
     *   drain) makes this a no-op, so a stale coroutine cannot steal work.
     */
    suspend fun drainAll(token: Long) {
        while (true) {
            if (!isCurrent(token)) return
            queue.drain()
            refresh()
            val finished = synchronized(lock) {
                if (generation != token) return
                if (queue.isEmpty()) {
                    draining = false
                    true
                } else {
                    // URIs arrived during refresh(); loop and drain them.
                    false
                }
            }
            if (finished) return
        }
    }

    /** Invalidate any running drain and discard everything queued. */
    fun cancel() = synchronized(lock) {
        generation++
        draining = false
        queue.clear()
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lock) { generation == token }
}
