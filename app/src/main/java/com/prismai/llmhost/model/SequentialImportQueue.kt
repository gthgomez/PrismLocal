package com.prismai.llmhost.model

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Outcome of attempting to dispatch an import in [SequentialImportQueue].
 */
sealed interface ImportDispatchOutcome {
    object Success : ImportDispatchOutcome
    object RetryableBusy : ImportDispatchOutcome
    data class PermanentError(val message: String? = null, val cause: Throwable? = null) : ImportDispatchOutcome
}

/**
 * Runs model imports strictly one at a time.
 *
 * The multi-select picker reported "Importing N" but called importModel only
 * for the first URI; the rest were silently dropped. ModelImportManager is
 * single-flight by design, so a naive forEach would have every call after the
 * first rejected with "A model import is already running".
 */
class SequentialImportQueue(
    private val importOne: suspend (String) -> ImportDispatchOutcome,
) {

    companion object {
        private const val TAG = "SequentialImportQueue"

        fun simple(simpleImport: suspend (String) -> Unit): SequentialImportQueue =
            SequentialImportQueue { uri ->
                simpleImport(uri)
                ImportDispatchOutcome.Success
            }
    }

    private val pending = mutableListOf<String>()

    @Synchronized
    fun enqueueAll(uris: List<String>) {
        pending += uris
    }

    /** True when nothing is queued. Used by the drain loop under its lock. */
    @Synchronized
    fun isEmpty(): Boolean = pending.isEmpty()

    /** Discard everything queued. Used by cancelImport() so cancelled files cannot import later. */
    @Synchronized
    fun clear() {
        pending.clear()
    }

    /**
     * Import everything queued, one at a time, continuing past ordinary
     * failures. Cancellation is NOT an ordinary failure: it is rethrown so the
     * surrounding [CoroutineScope] unwinds instead of continuing to import on a
     * cancelled scope and leaving `_importState` stuck at `Running`.
     */
    suspend fun drain(retryDelayMs: Long = 50L) {
        while (true) {
            val next = synchronized(this) { pending.firstOrNull() } ?: return
            try {
                when (importOne(next)) {
                    ImportDispatchOutcome.Success,
                    is ImportDispatchOutcome.PermanentError -> {
                        synchronized(this) {
                            if (pending.firstOrNull() == next) {
                                pending.removeFirst()
                            }
                        }
                    }
                    ImportDispatchOutcome.RetryableBusy -> {
                        delay(retryDelayMs)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Continue past ordinary import failures, but record them:
                // silently swallowing a programming error hides broken imports.
                Log.w(TAG, "import failed for $next", e)
                synchronized(this) {
                    if (pending.firstOrNull() == next) {
                        pending.removeFirst()
                    }
                }
            }
        }
    }
}
