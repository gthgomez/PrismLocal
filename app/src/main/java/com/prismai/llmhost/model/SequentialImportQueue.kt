package com.prismai.llmhost.model

import kotlinx.coroutines.CancellationException

/**
 * Runs model imports strictly one at a time.
 *
 * The multi-select picker reported "Importing N" but called importModel only
 * for the first URI; the rest were silently dropped. ModelImportManager is
 * single-flight by design, so a naive forEach would have every call after the
 * first rejected with "A model import is already running".
 */
class SequentialImportQueue(private val importOne: suspend (String) -> Unit) {

    private val pending = mutableListOf<String>()

    @Synchronized
    fun enqueueAll(uris: List<String>) {
        pending += uris
    }

    /**
     * Import everything queued, one at a time, continuing past ordinary
     * failures. Cancellation is NOT an ordinary failure: it is rethrown so the
     * surrounding [CoroutineScope] unwinds instead of continuing to import on a
     * cancelled scope and leaving `_importState` stuck at `Running`.
     */
    suspend fun drain() {
        while (true) {
            val next = synchronized(this) { pending.removeFirstOrNull() } ?: return
            try {
                importOne(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Continue past ordinary import failures.
            }
        }
    }
}
