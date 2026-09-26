package com.prismai.llmhost.chat

/** Serializes transcript publication with clear/delete and rejects stale snapshots. */
class TranscriptWriteGate {
    private val lock = Any()
    private val revisions = mutableMapOf<String, Long>()
    private val retiredOwners = mutableSetOf<String>()

    fun snapshotRevision(chatId: String): Long = synchronized(lock) {
        if (chatId in retiredOwners) return@synchronized -1L
        val next = (revisions[chatId] ?: 0L) + 1L
        revisions[chatId] = next
        next
    }

    fun publish(chatId: String, expectedRevision: Long, write: () -> Unit): Boolean =
        synchronized(lock) {
            if (chatId in retiredOwners) return@synchronized false
            if ((revisions[chatId] ?: 0L) != expectedRevision) return@synchronized false
            write()
            true
        }

    /** Invalidate queued snapshots, wait for any active publication, then clear/delete storage. */
    fun invalidateAndRun(
        chatId: String,
        retireOwner: Boolean = false,
        mutation: () -> Unit,
    ) = synchronized(lock) {
        revisions[chatId] = (revisions[chatId] ?: 0L) + 1L
        if (retireOwner) retiredOwners += chatId
        mutation()
    }
}
