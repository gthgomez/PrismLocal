package com.prismai.llmhost.model

import android.util.Log
import kotlinx.coroutines.CancellationException

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
 *    otherwise it loops again. Any URI enqueued during `refresh()` is caught,
 *    even when that refresh fails: ordinary refresh failures are logged and the
 *    loop continues, and only cancellation unwinds it.
 *  - [cancel] invalidates the current drain token, clears the queue, and resets
 *    `draining`, so cancelled files cannot be resurrected or duplicated.
 */
class ImportBatchDrainer(
    private val queue: SequentialImportQueue,
    private val refresh: suspend () -> Unit,
) {
    private companion object {
        const val TAG = "ImportBatchDrainer"
    }

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
        try {
            while (true) {
                if (!isCurrent(token)) return
                queue.drain()
                refreshNonFatal()
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
        } finally {
            // Raw cancellation must not leave `draining` set, or every later
            // enqueue returns null and silently drops batches until the service
            // is recreated. Ordinary refresh failures are handled in-loop by
            // refreshNonFatal(), so this is the safety net for cancellation (and
            // anything else that escapes the loop). A normal finish has already
            // cleared `draining`, and cancel() bumped the generation, so both of
            // those paths are no-ops here.
            synchronized(lock) {
                if (generation == token && draining) {
                    draining = false
                }
            }
        }
    }

    /**
     * Refresh installed models without letting a failure strand queued work.
     *
     * If [refresh] throws while a batch arrived during the same pass, that batch
     * is still in the queue but [draining] is set — so [enqueue] returned null
     * and no new drain was started. Propagating the failure would clear
     * `draining` in the `finally` and leave the queued URIs stranded until some
     * later `enqueue`. Swallowing ordinary failures here keeps the loop alive so
     * it re-checks `queue.isEmpty()` and drains the new work. Cancellation is
     * not an ordinary failure and is rethrown so the scope unwinds normally.
     */
    private suspend fun refreshNonFatal() {
        try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "refresh after import drain failed; continuing", e)
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
