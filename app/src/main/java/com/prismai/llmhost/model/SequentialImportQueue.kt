package com.prismai.llmhost.model

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

    /** Import everything queued, one at a time, continuing past failures. */
    suspend fun drain() {
        while (true) {
            val next = synchronized(this) { pending.removeFirstOrNull() } ?: return
            runCatching { importOne(next) }
        }
    }
}
