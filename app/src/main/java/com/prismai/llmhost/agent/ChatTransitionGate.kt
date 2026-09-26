package com.prismai.llmhost.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Serializes asynchronous chat-identity transitions.
 *
 * Claiming/invalidation happens on the caller thread; cleanup and the identity
 * mutation run on [scope]. Each request waits for the previous request's
 * cleanup, so a non-cooperative tool can delay the mutation without blocking
 * the UI caller.
 */
class ChatTransitionGate(
    private val scope: CoroutineScope,
) {
    private val sequenceLock = Any()
    private var tail: CompletableDeferred<Unit> = completedDeferred()

    fun enqueue(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> Unit,
    ): Job {
        val (previous, next) = synchronized(sequenceLock) {
            val prior = tail
            val successor = CompletableDeferred<Unit>()
            tail = successor
            prior to successor
        }
        return scope.launch {
            try {
                previous.join()
                cleanup()
                mutation()
            } finally {
                next.complete(Unit)
            }
        }
    }

    suspend fun enqueueAndAwait(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> Unit,
    ) {
        enqueue(cleanup, mutation).join()
    }

    private fun completedDeferred(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }
}
