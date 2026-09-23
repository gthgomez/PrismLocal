package com.prismai.llmhost.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTransitionGateTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test(timeout = 2_000)
    fun enqueueReturnsBeforeNonCooperativeCleanupAndMutatesAfterRelease() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        val gate = ChatTransitionGate(scope)
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val mutationCompleted = CompletableDeferred<Unit>()
        val nonCooperativeJob = scope.launch(Dispatchers.Default + NonCancellable) {
            releaseCleanup.await()
        }

        val transition = gate.enqueue(
            cleanup = {
                cleanupStarted.complete(Unit)
                nonCooperativeJob.join()
            },
            mutation = {
                mutationCompleted.complete(Unit)
            },
        )
        assertNotNull(transition)

        withTimeout(500) { cleanupStarted.await() }
        assertFalse(mutationCompleted.isCompleted)

        // The cleanup is intentionally waiting on a job that ignores cancellation.
        // The enqueue call above has already returned, so the test can release it.
        releaseCleanup.complete(Unit)
        withTimeout(1_000) { mutationCompleted.await() }
        transition.join()
        withContext(NonCancellable) { nonCooperativeJob.cancelAndJoin() }
        assertTrue(mutationCompleted.isCompleted)
    }
}
