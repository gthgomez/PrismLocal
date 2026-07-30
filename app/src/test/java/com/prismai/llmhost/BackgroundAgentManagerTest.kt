package com.prismai.llmhost

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTestContext : ContextWrapper(null) {
    override fun getSystemService(name: String): Any? = null
}

class BackgroundAgentManagerTest {

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
}
