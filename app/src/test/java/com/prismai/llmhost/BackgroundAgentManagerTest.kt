package com.prismai.llmhost

import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTestContext : ContextWrapper(null) {
    override fun getSystemService(name: String): Any? = null
}

class BackgroundAgentManagerTest {

    @Test
    fun deletingSourceChatDiscardsOnlyItsQueuedTasks() = runBlocking {
        val manager = BackgroundAgentManager(
            context = FakeTestContext(),
            isDeviceBusyWithUserGeneration = { true },
        )
        val owned = manager.enqueue("owned prompt", sourceChatId = "chat-a")!!
        val other = manager.enqueue("other prompt", sourceChatId = "chat-b")!!

        var deleted = false
        val removed = manager.deleteChatAndInvalidateTasks("chat-a") {
            deleted = true
            true
        }

        assertTrue(deleted)
        assertTrue(removed)
        assertEquals(listOf(other.id), manager.state.value.queuedTasks.map { it.id })
    }

    @Test
    fun sourceChatDeletionIsRejectedWhileItsTaskIsActive() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val holdTask = CompletableDeferred<String>()
        val manager = BackgroundAgentManager(
            context = FakeTestContext(),
            executeTask = {
                started.complete(Unit)
                holdTask.await()
            },
        )
        val task = manager.enqueue("active prompt", sourceChatId = "chat-a")!!
        started.await()
        var deleted = false

        val deletedAndInvalidated = manager.deleteChatAndInvalidateTasks("chat-a") {
            deleted = true
            true
        }

        assertFalse(deletedAndInvalidated)
        assertFalse(deleted)
        assertEquals(task.id, manager.state.value.activeTask?.id)
        manager.cancelTask(task.id)
        delay(50)
    }

    @Test
    fun enqueuedTaskExecutesViaRunner() = runBlocking {
        val testContext = FakeTestContext()
        var executedPrompt: String? = null

        val manager = BackgroundAgentManager(
            context = testContext,
            executeTask = { task ->
                executedPrompt = task.prompt
                "Task completed successfully"
            },
        )

        val task = manager.enqueue("Summarize research paper")
        assertNotNull(task)

        var attempts = 0
        while (manager.state.value.completedTasks.isEmpty() && attempts < 20) {
            delay(50)
            attempts++
        }

        assertEquals("Summarize research paper", executedPrompt)
        assertEquals(1, manager.state.value.completedTasks.size)
        assertEquals(BackgroundTaskStatus.COMPLETED, manager.state.value.completedTasks.first().status)
    }

    @Test
    fun cancelTaskTriggersNativeCancelAndCancelsJob() = runBlocking {
        val testContext = FakeTestContext()
        var nativeCancelTriggered = false

        val manager = BackgroundAgentManager(
            context = testContext,
            executeTask = { task ->
                delay(2000)
                "Done"
            },
            cancelNativeGeneration = {
                nativeCancelTriggered = true
            },
        )

        val task = manager.enqueue("Long running task")
        assertNotNull(task!!)

        var attempts = 0
        while (manager.state.value.activeTask == null && attempts < 20) {
            delay(20)
            attempts++
        }

        val cancelled = manager.cancelTask(task.id)
        assertTrue(cancelled)

        attempts = 0
        while (manager.state.value.completedTasks.isEmpty() && attempts < 20) {
            delay(50)
            attempts++
        }

        assertTrue(nativeCancelTriggered)
        assertEquals(1, manager.state.value.completedTasks.size)
        assertEquals(BackgroundTaskStatus.CANCELLED, manager.state.value.completedTasks.first().status)
    }

    @Test
    fun cancelWaitsForNativeCancelBeforeNextTask() = runBlocking {
        val testContext = FakeTestContext()
        var nativeCancelStart = false
        var nativeCancelDone = false
        var bStartedBeforeNativeCancel = false

        val manager = BackgroundAgentManager(
            context = testContext,
            executeTask = { task ->
                if (task.prompt == "Task A") {
                    delay(10_000)
                    "A done"
                } else {
                    if (!nativeCancelDone) {
                        bStartedBeforeNativeCancel = true
                    }
                    "B done"
                }
            },
            cancelNativeGeneration = {
                nativeCancelStart = true
                delay(200)
                nativeCancelDone = true
            },
        )

        val taskA = manager.enqueue("Task A")
        assertNotNull(taskA)

        var attempts = 0
        while (manager.state.value.activeTask?.id != taskA!!.id && attempts < 20) {
            delay(20)
            attempts++
        }

        val taskB = manager.enqueue("Task B")
        assertNotNull(taskB)

        val cancelled = manager.cancelTask(taskA!!.id)
        assertTrue(cancelled)

        attempts = 0
        while (manager.state.value.completedTasks.size < 2 && attempts < 40) {
            delay(50)
            attempts++
        }

        assertTrue("Native cancel should have been triggered", nativeCancelStart)
        assertTrue("Native cancel should have completed", nativeCancelDone)
        assertTrue("Task B should not start before native cancel finishes", !bStartedBeforeNativeCancel)
        assertEquals(2, manager.state.value.completedTasks.size)
        assertEquals(BackgroundTaskStatus.CANCELLED, manager.state.value.completedTasks.first { it.id == taskA!!.id }.status)
        assertEquals(BackgroundTaskStatus.COMPLETED, manager.state.value.completedTasks.first { it.id == taskB!!.id }.status)
    }

    @Test
    fun enqueueDuringActiveTaskCancelPreservesNewTask() = runBlocking {
        val testContext = FakeTestContext()
        var nativeCancelStarted = false
        var nativeCancelCompleted = false

        val manager = BackgroundAgentManager(
            context = testContext,
            executeTask = { task ->
                if (task.prompt == "Task A") {
                    delay(10_000)
                    "A done"
                } else {
                    "B done"
                }
            },
            cancelNativeGeneration = {
                nativeCancelStarted = true
                delay(300) // Native cancel takes 300ms
                nativeCancelCompleted = true
            },
        )

        val taskA = manager.enqueue("Task A")
        assertNotNull(taskA)

        var attempts = 0
        while (manager.state.value.activeTask?.id != taskA!!.id && attempts < 20) {
            delay(20)
            attempts++
        }

        // Cancel Task A
        val cancelled = manager.cancelTask(taskA!!.id)
        assertTrue(cancelled)

        // Wait until native cancel is in-flight
        while (!nativeCancelStarted && attempts < 20) {
            delay(20)
            attempts++
        }

        // Enqueue Task B WHILE cancel of Task A is in flight!
        val taskB = manager.enqueue("Task B")
        assertNotNull(taskB)

        // Wait until both tasks finish (Task A cancelled, Task B completed)
        attempts = 0
        while (manager.state.value.completedTasks.size < 2 && attempts < 40) {
            delay(50)
            attempts++
        }

        assertTrue("Native cancel should have completed", nativeCancelCompleted)
        assertEquals(2, manager.state.value.completedTasks.size)
        assertEquals(BackgroundTaskStatus.CANCELLED, manager.state.value.completedTasks.first { it.id == taskA!!.id }.status)
        assertEquals(BackgroundTaskStatus.COMPLETED, manager.state.value.completedTasks.first { it.id == taskB!!.id }.status)
    }

    @Test
    fun queueFullRejectsBeyondMaxQueuedTasks() = runBlocking {
        val testContext = FakeTestContext()
        val releaseTasks = AtomicBoolean(false)

        val manager = BackgroundAgentManager(
            context = testContext,
            executeTask = { _ ->
                while (!releaseTasks.get()) {
                    delay(20)
                }
                "Done"
            },
        )

        val acceptedIds = mutableListOf<String>()
        repeat(7) { index ->
            val task = manager.enqueue("Queue fill $index")
            if (index < 6) {
                assertNotNull(task)
                acceptedIds.add(task!!.id)
            } else {
                assertNull(task)
            }
        }

        assertEquals(6, acceptedIds.size)
        assertEquals(acceptedIds.size, acceptedIds.toSet().size)
        assertNotNull(manager.state.value.activeTask)
        assertEquals(5, manager.state.value.queuedTasks.size)

        releaseTasks.set(true)
        var attempts = 0
        while (manager.state.value.completedTasks.size < 6 && attempts < 100) {
            delay(50)
            attempts++
        }

        assertEquals(6, manager.state.value.completedTasks.size)
    }

    @Test
    fun rapidProcessNextTaskDoesNotExecuteTwice() = runBlocking {
        val testContext = FakeTestContext()
        val executedIds = mutableListOf<String>()

        val manager = BackgroundAgentManager(
            context = testContext,
            executeTask = { task ->
                executedIds.add(task.id)
                delay(150)
                "Slow done"
            },
        )

        val task = manager.enqueue("Only task")
        assertNotNull(task)

        manager.processNextTask()
        manager.processNextTask()

        var attempts = 0
        while (manager.state.value.completedTasks.isEmpty() && attempts < 40) {
            delay(50)
            attempts++
        }

        assertEquals(listOf(task!!.id), executedIds)
        assertEquals(1, manager.state.value.completedTasks.size)
        assertEquals(BackgroundTaskStatus.COMPLETED, manager.state.value.completedTasks.first().status)
        assertNull(manager.state.value.activeTask)
    }

    @Test
    fun busyDeviceHoldsQueuedTaskUntilUserGenerationFrees() = runBlocking {
        val testContext = FakeTestContext()
        val userGenerationActive = AtomicBoolean(true)
        val executed = AtomicBoolean(false)

        val manager = BackgroundAgentManager(
            context = testContext,
            executeTask = { _ ->
                executed.set(true)
                "Ran after user generation"
            },
            isDeviceBusyWithUserGeneration = { userGenerationActive.get() },
        )

        assertNotNull(manager.enqueue("Held while busy"))

        delay(200)
        assertTrue(!executed.get())
        assertNull(manager.state.value.activeTask)
        assertEquals(1, manager.state.value.queuedTasks.size)
        assertEquals(BackgroundTaskStatus.QUEUED, manager.state.value.queuedTasks.first().status)

        userGenerationActive.set(false)
        var attempts = 0
        while (!executed.get() && attempts < 80) {
            delay(100)
            attempts++
        }
        assertTrue(executed.get())

        attempts = 0
        while (manager.state.value.completedTasks.isEmpty() && attempts < 20) {
            delay(50)
            attempts++
        }

        assertEquals(1, manager.state.value.completedTasks.size)
        assertEquals(BackgroundTaskStatus.COMPLETED, manager.state.value.completedTasks.first().status)
        assertEquals(0, manager.state.value.queuedTasks.size)
        assertNull(manager.state.value.activeTask)
    }

    @Test
    fun taskDeferredAfterPromotionReturnsToQueueInsteadOfCompleting() = runBlocking {
        val testContext = FakeTestContext()
        val attempts = AtomicInteger()
        val deviceBusy = AtomicBoolean(false)
        val firstDeferred = CompletableDeferred<Unit>()
        val manager = BackgroundAgentManager(
            context = testContext,
            executeTask = {
                if (attempts.incrementAndGet() == 1) {
                    deviceBusy.set(true)
                    firstDeferred.complete(Unit)
                    throw BackgroundTaskDeferredException()
                }
                "completed after retry"
            },
            isDeviceBusyWithUserGeneration = { deviceBusy.get() },
        )

        val task = manager.enqueue("retry after admission race", sourceChatId = "chat-a")!!
        firstDeferred.await()
        var attemptsToRequeue = 0
        while (manager.state.value.activeTask != null && attemptsToRequeue < 40) {
            delay(25)
            attemptsToRequeue++
        }
        assertNull(manager.state.value.activeTask)
        assertEquals(task.id, manager.state.value.queuedTasks.firstOrNull()?.id)
        assertTrue(manager.state.value.completedTasks.none { it.id == task.id })

        deviceBusy.set(false)
        var attemptsToComplete = 0
        while (manager.state.value.completedTasks.none { it.id == task.id } && attemptsToComplete < 100) {
            delay(50)
            attemptsToComplete++
        }
        assertEquals(2, attempts.get())
        assertEquals(
            BackgroundTaskStatus.COMPLETED,
            manager.state.value.completedTasks.first { it.id == task.id }.status,
        )
    }

    @Test
    fun deferredTaskDoesNotRetryInATightLoop() = runBlocking {
        val attempts = AtomicInteger()
        val manager = BackgroundAgentManager(
            context = FakeTestContext(),
            executeTask = {
                attempts.incrementAndGet()
                throw BackgroundTaskDeferredException()
            },
            isDeviceBusyWithUserGeneration = { false },
        )

        manager.enqueue("stay queued", sourceChatId = "chat-a")
        delay(400)

        assertEquals(1, attempts.get())
        assertEquals(1, manager.state.value.queuedTasks.size)
        assertNull(manager.state.value.activeTask)
        manager.cancelTask(manager.state.value.queuedTasks.first().id)
        Unit
    }
}
