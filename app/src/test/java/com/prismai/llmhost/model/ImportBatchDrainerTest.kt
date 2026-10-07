package com.prismai.llmhost.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the batch-import drain.
 *
 * The old service gate (`batchImportJob?.isActive == true`) was not atomic with
 * the enqueue, so a second multi-select arriving during the post-drain
 * `refreshInstalledModels()` suspend appended its URIs and returned without
 * them ever being drained. [ImportBatchDrainer] makes enqueue/ensure-drain and
 * the loop's terminal check atomic.
 */
class ImportBatchDrainerTest {

    @Test
    fun urisEnqueuedDuringRefreshAreDrainedNotDropped() = runBlocking {
        val processed = mutableListOf<String>()
        val queue = SequentialImportQueue { processed += it }
        val refreshGate = CompletableDeferred<Unit>()
        var refreshCalls = 0
        val drainer = ImportBatchDrainer(queue) {
            refreshCalls++
            // First refresh suspends; once released, later refreshes no-op.
            refreshGate.await()
        }

        val token = drainer.enqueue(listOf("a"))
        assertNotNull(token)
        val job = launch { drainer.drainAll(token!!) }

        // Wait until the first file is imported and the drain is suspended in
        // the post-drain refresh — the exact window that used to drop a batch.
        while (refreshCalls == 0) yield()
        assertEquals(listOf("a"), processed)

        // A second multi-select lands here. It must not start its own drain
        // (that is the bug); the running drain must pick it up.
        assertNull("a running drain must absorb the new batch", drainer.enqueue(listOf("b")))

        refreshGate.complete(Unit)
        job.join()

        assertEquals("the batch enqueued during refresh must be drained", listOf("a", "b"), processed)
    }

    @Test
    fun cancelClearsPendingWorkSoCancelledFilesCannotImportLater() = runBlocking {
        val processed = mutableListOf<String>()
        val firstImportGate = CompletableDeferred<Unit>()
        val queue = SequentialImportQueue { uri ->
            processed += uri
            if (processed.size == 1) firstImportGate.await()
        }
        val drainer = ImportBatchDrainer(queue, refresh = {})
        val token = drainer.enqueue(listOf("a", "b", "c"))
        assertNotNull(token)
        val job = launch { drainer.drainAll(token!!) }

        while (processed.isEmpty()) yield()
        drainer.cancel()
        assertTrue("cancel must clear the persistent queue", queue.isEmpty())

        firstImportGate.complete(Unit)
        job.join()

        assertEquals(
            "cancelled files must not be imported later",
            listOf("a"),
            processed,
        )
    }

    @Test
    fun refreshFailureDoesNotWedgeTheDrainer() = runBlocking {
        val processed = mutableListOf<String>()
        val queue = SequentialImportQueue { processed += it }
        var failNextRefresh = true
        val drainer = ImportBatchDrainer(queue) {
            if (failNextRefresh) {
                failNextRefresh = false
                throw IllegalStateException("refresh blew up")
            }
        }

        val first = drainer.enqueue(listOf("a"))
        assertNotNull(first)
        try {
            drainer.drainAll(first!!)
        } catch (expected: IllegalStateException) {
            // The refresh failure must surface...
        }

        // ...but must not wedge the drainer: a later batch has to start a new
        // drain instead of being silently dropped because `draining` stayed true.
        val second = drainer.enqueue(listOf("b"))
        assertNotNull("a refresh failure must not wedge the drainer", second)
        drainer.drainAll(second!!)

        assertEquals(listOf("a", "b"), processed)
    }

    @Test
    fun enqueueAfterCancelStartsAFreshDrain() = runBlocking {
        val processed = mutableListOf<String>()
        val queue = SequentialImportQueue { processed += it }
        val drainer = ImportBatchDrainer(queue, refresh = {})

        drainer.enqueue(listOf("a"))
        drainer.cancel()

        val token = drainer.enqueue(listOf("b"))
        assertNotNull("cancel must allow a new drain to start", token)
        drainer.drainAll(token!!)

        assertEquals(listOf("b"), processed)
    }
}
