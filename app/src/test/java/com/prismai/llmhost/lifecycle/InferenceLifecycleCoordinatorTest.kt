package com.prismai.llmhost.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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
