package com.prismai.llmhost.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

sealed interface ChatTransitionRequestResult {
    data object Queued : ChatTransitionRequestResult
    data class Rejected(val reason: String) : ChatTransitionRequestResult
}

sealed interface ChatTransitionOutcome {
    data object Applied : ChatTransitionOutcome
    data class Rejected(val reason: String) : ChatTransitionOutcome
    data class Failed(val reason: String, val cause: Throwable? = null) : ChatTransitionOutcome
}

/**
 * Serializes chat transitions and generation admission through one actor queue.
 *
 * Awaited commands remember the caller's Job only until the actor starts the
 * command. Cancellation before that linearization point removes the command;
 * cancellation after it cannot interrupt an already-admitted mutation.
 */
class InferenceLifecycleCoordinator(scope: CoroutineScope) {
    private val commands = Channel<Command>(capacity = Channel.UNLIMITED)
    private val stateLock = Any()
    private var acceptingRequests = true
    private lateinit var actorJob: Job

    init {
        actorJob = scope.launch {
            for (command in commands) {
                try {
                    execute(command)
                } catch (failure: Throwable) {
                    failCommand(command, failure)
                }
            }
        }
        actorJob.invokeOnCompletion { failPendingCommands() }
    }

    fun requestChatTransition(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> ChatTransitionOutcome,
        onOutcome: (ChatTransitionOutcome) -> Unit = {},
    ): ChatTransitionRequestResult = enqueueTransition(
        cleanup = cleanup,
        mutation = mutation,
        callerJob = null,
        onOutcome = onOutcome,
    ).requestResult

    suspend fun requestAndAwaitChatTransition(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> ChatTransitionOutcome,
    ): ChatTransitionOutcome {
        if (currentCoroutineContext()[LifecycleActorContext]?.coordinator === this) {
            return try {
                cleanup()
                currentCoroutineContext().ensureActive()
                mutation()
            } catch (failure: CancellationException) {
                if (currentCoroutineContext().isActive) throw failure
                ChatTransitionOutcome.Failed(TRANSITION_FAILURE_REASON, failure)
            } catch (failure: Throwable) {
                ChatTransitionOutcome.Failed(TRANSITION_FAILURE_REASON, failure)
            }
        }
        val submission = enqueueTransition(
            cleanup = cleanup,
            mutation = mutation,
            callerJob = currentCoroutineContext()[Job],
            onOutcome = {},
        )
        return when (val requestResult = submission.requestResult) {
            ChatTransitionRequestResult.Queued -> submission.completion!!.await()
            is ChatTransitionRequestResult.Rejected -> ChatTransitionOutcome.Rejected(requestResult.reason)
        }
    }

    suspend fun <T> runGenerationAdmission(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val completion = CompletableDeferred<T>()
        val command = Command.GenerationAdmission(
            block = block,
            completion = completion,
            callerJob = currentCoroutineContext()[Job],
        )
        bindCaller(command)
        synchronized(stateLock) {
            check(acceptingRequests) { CLOSED_REASON }
            check(commands.trySend(command).isSuccess) { CLOSED_REASON }
        }
        return completion.await()
    }

    fun close() {
        synchronized(stateLock) {
            if (acceptingRequests) {
                acceptingRequests = false
                commands.close()
            }
        }
    }

    suspend fun closeAndJoin(
        drainTimeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS,
        cancelTimeoutMs: Long = DEFAULT_CANCEL_TIMEOUT_MS,
    ): Boolean {
        require(drainTimeoutMs >= 0L) { "drainTimeoutMs must be non-negative" }
        require(cancelTimeoutMs >= 0L) { "cancelTimeoutMs must be non-negative" }
        close()
        val drained = try {
            withTimeoutOrNull(drainTimeoutMs) { actorJob.join() } != null
        } catch (failure: CancellationException) {
            actorJob.cancel(CancellationException(ACTOR_CANCEL_REASON))
            failPendingCommands()
            throw failure
        }
        if (drained) {
            failPendingCommands()
            return true
        }

        actorJob.cancel(CancellationException(ACTOR_CANCEL_REASON))
        val stopped = try {
            withTimeoutOrNull(cancelTimeoutMs) { actorJob.join() } != null
        } catch (failure: CancellationException) {
            failPendingCommands()
            throw failure
        }
        failPendingCommands()
        return stopped
    }

    private fun failPendingCommands() {
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: return
            failCommand(command, CancellationException(ACTOR_CANCEL_REASON))
        }
    }

    private fun enqueueTransition(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> ChatTransitionOutcome,
        callerJob: Job?,
        onOutcome: (ChatTransitionOutcome) -> Unit,
    ): TransitionSubmission {
        val completion = CompletableDeferred<ChatTransitionOutcome>()
        val command = Command.ChatTransition(cleanup, mutation, completion, callerJob, onOutcome)
        bindCaller(command)
        return synchronized(stateLock) {
            if (!acceptingRequests) {
                val outcome = ChatTransitionOutcome.Rejected(CLOSED_REASON)
                completeTransition(command, outcome)
                TransitionSubmission(ChatTransitionRequestResult.Rejected(CLOSED_REASON), null)
            } else if (commands.trySend(command).isSuccess) {
                TransitionSubmission(ChatTransitionRequestResult.Queued, completion)
            } else {
                val outcome = ChatTransitionOutcome.Rejected(CLOSED_REASON)
                completeTransition(command, outcome)
                TransitionSubmission(ChatTransitionRequestResult.Rejected(CLOSED_REASON), null)
            }
        }
    }

    /**
     * Cancellation is a pre-start skip, not an actor cancellation. The small
     * command gate makes the start/cancel order explicit and race-free.
     */
    private fun bindCaller(command: Command) {
        val caller = command.callerJob ?: return
        caller.invokeOnCompletion { cause ->
            if (command.cancelBeforeStart()) {
                failCommand(command, cause ?: CancellationException(CALLER_CANCEL_REASON))
            }
        }
    }

    private suspend fun execute(command: Command) {
        if (command.markStartedAndCheckCancellation()) {
            failCommand(command, CancellationException(CALLER_CANCEL_REASON))
            return
        }
        when (command) {
            is Command.ChatTransition -> {
                val outcome = try {
                    command.cleanup()
                    currentCoroutineContext().ensureActive()
                    command.mutation()
                } catch (failure: Throwable) {
                    ChatTransitionOutcome.Failed(TRANSITION_FAILURE_REASON, failure)
                }
                completeTransition(command, outcome)
            }

            is Command.GenerationAdmission<*> -> {
                @Suppress("UNCHECKED_CAST")
                val completion = command.completion as CompletableDeferred<Any?>
                try {
                    completion.complete(
                        withContext(LifecycleActorContext(this@InferenceLifecycleCoordinator)) {
                            (command.block as suspend () -> Any?)()
                        }.also {
                            currentCoroutineContext().ensureActive()
                        },
                    )
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }
        }
    }

    private fun completeTransition(command: Command.ChatTransition, outcome: ChatTransitionOutcome) {
        command.completion.complete(outcome)
        runCatching { command.onOutcome(outcome) }
    }

    private fun failCommand(command: Command, failure: Throwable) {
        when (command) {
            is Command.ChatTransition -> completeTransition(
                command,
                ChatTransitionOutcome.Failed(TRANSITION_FAILURE_REASON, failure),
            )
            is Command.GenerationAdmission<*> -> {
                @Suppress("UNCHECKED_CAST")
                (command.completion as CompletableDeferred<Any?>).completeExceptionally(failure)
            }
        }
    }

    private data class TransitionSubmission(
        val requestResult: ChatTransitionRequestResult,
        val completion: CompletableDeferred<ChatTransitionOutcome>?,
    )

    private sealed class Command(val callerJob: Job?) {
        private val gate = Any()
        private var started = false
        private var cancelledBeforeStart = false

        fun markStartedAndCheckCancellation(): Boolean = synchronized(gate) {
            if (cancelledBeforeStart) return true
            started = true
            false
        }

        fun cancelBeforeStart(): Boolean = synchronized(gate) {
            if (started) return false
            cancelledBeforeStart = true
            true
        }

        class ChatTransition(
            val cleanup: suspend () -> Unit,
            val mutation: suspend () -> ChatTransitionOutcome,
            val completion: CompletableDeferred<ChatTransitionOutcome>,
            callerJob: Job?,
            val onOutcome: (ChatTransitionOutcome) -> Unit,
        ) : Command(callerJob)

        class GenerationAdmission<T>(
            val block: suspend () -> T,
            val completion: CompletableDeferred<T>,
            callerJob: Job?,
        ) : Command(callerJob)
    }

    private class LifecycleActorContext(
        val coordinator: InferenceLifecycleCoordinator,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<LifecycleActorContext>
    }

    private companion object {
        const val CLOSED_REASON = "Inference lifecycle coordinator is closed"
        const val ACTOR_CANCEL_REASON = "Inference lifecycle coordinator drained after timeout"
        const val CALLER_CANCEL_REASON = "Lifecycle command cancelled before admission"
        const val TRANSITION_FAILURE_REASON = "Chat transition failed"
        const val DEFAULT_DRAIN_TIMEOUT_MS = 1_000L
        const val DEFAULT_CANCEL_TIMEOUT_MS = 1_000L
    }
}
