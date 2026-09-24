package com.prismai.llmhost.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
 * A transition's cleanup and mutation run in enqueue order. Generation
 * admission runs only for the admission/start section of a generation; it does
 * not own native generation execution.
 */
class InferenceLifecycleCoordinator(scope: CoroutineScope) {
    private val commands = Channel<Command>(capacity = Channel.UNLIMITED)
    private val stateLock = Any()
    private var acceptingRequests = true

    init {
        scope.launch {
            for (command in commands) {
                try {
                    execute(command)
                } catch (failure: Throwable) {
                    failCommand(command, failure)
                }
            }
        }
    }

    fun requestChatTransition(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> ChatTransitionOutcome,
    ): ChatTransitionRequestResult = enqueueTransition(cleanup, mutation).requestResult

    suspend fun requestAndAwaitChatTransition(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> ChatTransitionOutcome,
    ): ChatTransitionOutcome {
        val submission = enqueueTransition(cleanup, mutation)
        return when (val requestResult = submission.requestResult) {
            ChatTransitionRequestResult.Queued -> submission.completion!!.await()
            is ChatTransitionRequestResult.Rejected -> ChatTransitionOutcome.Rejected(requestResult.reason)
        }
    }

    suspend fun <T> runGenerationAdmission(block: suspend () -> T): T {
        val completion = CompletableDeferred<T>()
        synchronized(stateLock) {
            check(acceptingRequests) { CLOSED_REASON }
            check(commands.trySend(Command.GenerationAdmission(block, completion)).isSuccess) {
                CLOSED_REASON
            }
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

    private fun enqueueTransition(
        cleanup: suspend () -> Unit,
        mutation: suspend () -> ChatTransitionOutcome,
    ): TransitionSubmission {
        val completion = CompletableDeferred<ChatTransitionOutcome>()
        val command = Command.ChatTransition(cleanup, mutation, completion)
        return synchronized(stateLock) {
            if (!acceptingRequests) {
                TransitionSubmission(ChatTransitionRequestResult.Rejected(CLOSED_REASON), null)
            } else {
                val sent = commands.trySend(command).isSuccess
                if (sent) {
                    TransitionSubmission(ChatTransitionRequestResult.Queued, completion)
                } else {
                    TransitionSubmission(ChatTransitionRequestResult.Rejected(CLOSED_REASON), null)
                }
            }
        }
    }

    private suspend fun execute(command: Command) {
        when (command) {
            is Command.ChatTransition -> {
                val outcome = try {
                    command.cleanup()
                    command.mutation()
                } catch (failure: Throwable) {
                    ChatTransitionOutcome.Failed(TRANSITION_FAILURE_REASON, failure)
                }
                command.completion.complete(outcome)
            }

            is Command.GenerationAdmission<*> -> {
                @Suppress("UNCHECKED_CAST")
                val completion = command.completion as CompletableDeferred<Any?>
                try {
                    completion.complete((command.block as suspend () -> Any?)())
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }
        }
    }

    private fun failCommand(command: Command, failure: Throwable) {
        when (command) {
            is Command.ChatTransition -> {
                command.completion.complete(ChatTransitionOutcome.Failed(TRANSITION_FAILURE_REASON, failure))
            }

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

    private sealed interface Command {
        data class ChatTransition(
            val cleanup: suspend () -> Unit,
            val mutation: suspend () -> ChatTransitionOutcome,
            val completion: CompletableDeferred<ChatTransitionOutcome>,
        ) : Command

        data class GenerationAdmission<T>(
            val block: suspend () -> T,
            val completion: CompletableDeferred<T>,
        ) : Command
    }

    private companion object {
        const val CLOSED_REASON = "Inference lifecycle coordinator is closed"
        const val TRANSITION_FAILURE_REASON = "Chat transition failed"
    }
}
