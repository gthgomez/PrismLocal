package com.prismai.llmhost.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class InferenceLifecycleCoordinatorTest {

    @Test(timeout = 2_000)
    fun transitionQueuedBeforeGenerationRunsFirst() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val events = mutableListOf<String>()
        val releaseCleanup = CompletableDeferred<Unit>()

        val transition = coordinator.requestChatTransition(
            cleanup = {
                events += "cleanup"
                releaseCleanup.await()
            },
            mutation = {
                events += "mutation"
                ChatTransitionOutcome.Applied
            },
        )
        val generation = async {
            coordinator.runGenerationAdmission {
                events += "generation"
            }
        }

        withTimeout(500) { while (events.isEmpty()) yield() }
        releaseCleanup.complete(Unit)
        assertEquals(ChatTransitionRequestResult.Queued, transition)
        generation.await()
        assertEquals(listOf("cleanup", "mutation", "generation"), events)
        coordinator.close()
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun generationQueuedFirstMakesLaterTransitionGuardReject() = runBlocking {
        val generating = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)

        coordinator.runGenerationAdmission { generating.set(true) }
        val outcome = coordinator.requestAndAwaitChatTransition(
            cleanup = {},
            mutation = {
                if (generating.get()) ChatTransitionOutcome.Rejected("Cancel generation before changing chats")
                else ChatTransitionOutcome.Applied
            },
        )
        assertEquals(ChatTransitionOutcome.Rejected("Cancel generation before changing chats"), outcome)
        coordinator.close()
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun nestedTransitionFromGenerationAdmissionExecutesWithoutSelfAwait() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val events = mutableListOf<String>()

        try {
            withTimeout(500) {
                coordinator.runGenerationAdmission {
                    events += "generation"
                    val outcome = coordinator.requestAndAwaitChatTransition(
                        cleanup = { events += "cleanup" },
                        mutation = {
                            events += "mutation"
                            ChatTransitionOutcome.Applied
                        },
                    )
                    events += outcome.toString()
                }
            }

            assertEquals(
                listOf("generation", "cleanup", "mutation", ChatTransitionOutcome.Applied.toString()),
                events,
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test(timeout = 2_000)
    fun nestedTransitionShutdownSuppressesMutationAndReturnsFailedOutcome() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val mutationCalled = CompletableDeferred<Unit>()
        val nestedOutcome = CompletableDeferred<ChatTransitionOutcome>()

        val admission = async {
            coordinator.runGenerationAdmission {
                coordinator.requestAndAwaitChatTransition(
                    cleanup = {
                        cleanupStarted.complete(Unit)
                        withContext(NonCancellable) {
                            releaseCleanup.await()
                        }
                    },
                    mutation = {
                        mutationCalled.complete(Unit)
                        ChatTransitionOutcome.Applied
                    },
                ).also { nestedOutcome.complete(it) }
            }
        }
        withTimeout(500) { cleanupStarted.await() }

        val stopped = withTimeout(500) {
            coordinator.closeAndJoin(drainTimeoutMs = 50L, cancelTimeoutMs = 100L)
        }
        assertFalse(stopped)
        assertFalse(mutationCalled.isCompleted)

        releaseCleanup.complete(Unit)
        assertTrue(withTimeout(500) { nestedOutcome.await() } is ChatTransitionOutcome.Failed)
        assertFalse(mutationCalled.isCompleted)
        assertTrue(runCatching { admission.await() }.isFailure)
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun closeAndJoinWaitsForQueuedTransitionToDrain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        coordinator.requestChatTransition(
            cleanup = {
                events += "cleanup"
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
            },
            mutation = {
                events += "mutation"
                ChatTransitionOutcome.Applied
            },
        )
        withTimeout(500) { cleanupStarted.await() }

        val shutdown = async { coordinator.closeAndJoin() }
        yield()
        assertTrue(shutdown.isCompleted.not())
        releaseCleanup.complete(Unit)
        shutdown.await()

        assertEquals(listOf("cleanup", "mutation"), events)
        assertTrue(
            coordinator.requestChatTransition(
                cleanup = {},
                mutation = { ChatTransitionOutcome.Applied },
            ) is ChatTransitionRequestResult.Rejected,
        )
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun closeAndJoinTimesOutAndCancelsNonCooperativeActor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val mutationCalled = CompletableDeferred<Unit>()

        val transition = async {
            coordinator.requestAndAwaitChatTransition(
                cleanup = {
                    cleanupStarted.complete(Unit)
                    withContext(NonCancellable) {
                        releaseCleanup.await()
                    }
                },
                mutation = {
                    mutationCalled.complete(Unit)
                    ChatTransitionOutcome.Applied
                },
            )
        }
        withTimeout(500) { cleanupStarted.await() }

        val completed = withTimeout(500) {
            coordinator.closeAndJoin(drainTimeoutMs = 50L, cancelTimeoutMs = 100L)
        }

        assertFalse(completed)
        assertFalse(mutationCalled.isCompleted)
        releaseCleanup.complete(Unit)
        delay(100)
        assertFalse(mutationCalled.isCompleted)
        assertTrue(withTimeout(500) { transition.await() } is ChatTransitionOutcome.Failed)
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun callerCancellationBeforeActorStartSkipsQueuedAdmission() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val actorBlocked = CompletableDeferred<Unit>()
        val releaseActor = CompletableDeferred<Unit>()
        val admitted = AtomicBoolean(false)
        coordinator.requestChatTransition(
            cleanup = {
                actorBlocked.complete(Unit)
                releaseActor.await()
            },
            mutation = { ChatTransitionOutcome.Applied },
        )
        actorBlocked.await()

        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runGenerationAdmission { admitted.set(true) }
        }
        caller.cancel()
        caller.join()
        releaseActor.complete(Unit)

        assertTrue(runCatching { caller.await() }.isFailure)
        assertFalse(admitted.get())
        coordinator.close()
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun callerCancellationAfterActorStartCannotInterruptAdmission() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val actorStarted = CompletableDeferred<Unit>()
        val releaseActor = CompletableDeferred<Unit>()
        val completed = AtomicBoolean(false)
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runGenerationAdmission {
                actorStarted.complete(Unit)
                releaseActor.await()
                completed.set(true)
            }
        }
        withTimeout(500) { actorStarted.await() }
        caller.cancel()
        releaseActor.complete(Unit)

        assertTrue(runCatching { caller.await() }.isFailure)
        withTimeout(500) { while (!completed.get()) yield() }
        assertTrue(completed.get())
        coordinator.close()
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun publicTransitionObserverReceivesRejectedOutcomeWithoutChangingRequestResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val outcomes = mutableListOf<ChatTransitionOutcome>()
        val result = coordinator.requestChatTransition(
            cleanup = {},
            mutation = { ChatTransitionOutcome.Rejected("busy") },
            onOutcome = { synchronized(outcomes) { outcomes += it } },
        )
        assertEquals(ChatTransitionRequestResult.Queued, result)
        withTimeout(500) { while (synchronized(outcomes) { outcomes.isEmpty() }) yield() }
        assertEquals(listOf<ChatTransitionOutcome>(ChatTransitionOutcome.Rejected("busy")), outcomes)
        coordinator.close()
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun followUpAdmissionAndLaterTransitionRemainOrdered() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val owner = java.util.concurrent.atomic.AtomicReference("chat-1")
        val events = mutableListOf<String>()
        val followUp = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runGenerationAdmission {
                events += "follow-up-admission"
                if (owner.get() == "chat-1") events += "follow-up-start"
            }
        }
        coordinator.requestChatTransition(
            cleanup = { events += "transition-cleanup" },
            mutation = {
                owner.set("chat-2")
                events += "transition-mutation"
                ChatTransitionOutcome.Applied
            },
        )
        followUp.await()
        withTimeout(500) { while (events.size < 3) yield() }
        assertEquals(
            listOf("follow-up-admission", "follow-up-start", "transition-cleanup", "transition-mutation"),
            events,
        )
        coordinator.close()
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun parentActorShutdownCompletesOutstandingCommands() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val firstStarted = CompletableDeferred<Unit>()
        val first = async {
            coordinator.requestAndAwaitChatTransition(
                cleanup = { firstStarted.complete(Unit); delay(Long.MAX_VALUE) },
                mutation = { ChatTransitionOutcome.Applied },
            )
        }
        withTimeout(500) { firstStarted.await() }
        val second = async {
            coordinator.requestAndAwaitChatTransition(
                cleanup = { error("queued cleanup must be skipped") },
                mutation = { error("queued mutation must be skipped") },
            )
        }
        yield()
        scope.cancel()

        assertTrue(withTimeout(500) { first.await() } is ChatTransitionOutcome.Failed)
        assertTrue(withTimeout(500) { second.await() } is ChatTransitionOutcome.Failed)
    }

    @Test(timeout = 2_000)
    fun closeRejectsNewRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        coordinator.close()
        val result = coordinator.requestChatTransition(
            cleanup = {},
            mutation = { ChatTransitionOutcome.Applied },
        )
        assertTrue(result is ChatTransitionRequestResult.Rejected)
        scope.cancel()
    }

    @Test(timeout = 2_000)
    fun failedTransitionOutcomeDoesNotStopActorAndNextGenerationRuns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InferenceLifecycleCoordinator(scope)
        val events = mutableListOf<String>()

        val outcome = coordinator.requestAndAwaitChatTransition(
            cleanup = { events += "cleanup" },
            mutation = {
                events += "mutation"
                ChatTransitionOutcome.Failed("transition failed")
            },
        )

        assertEquals(ChatTransitionOutcome.Failed("transition failed"), outcome)
        assertEquals("admitted", coordinator.runGenerationAdmission {
            events += "generation"
            "admitted"
        })
        assertEquals(listOf("cleanup", "mutation", "generation"), events)
        coordinator.close()
        scope.cancel()
    }
}
