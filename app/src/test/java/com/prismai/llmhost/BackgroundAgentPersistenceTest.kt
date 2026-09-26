package com.prismai.llmhost

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackgroundAgentPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun queuedTasksPersistAndRestoreAcrossInstances() {
        val storageDir = tempFolder.newFolder("bg_tasks_1")
        val testContext = FakeTestContext()

        // Instance 1: Keep tasks in QUEUED state by reporting device busy
        val manager1 = BackgroundAgentManager(
            context = testContext,
            storageDir = storageDir,
            isDeviceBusyWithUserGeneration = { true },
        )

        val task1 = manager1.enqueue("Research quantum gravity")
        val task2 = manager1.enqueue("Draft executive summary")
        assertNotNull(task1)
        assertNotNull(task2)
        assertEquals(2, manager1.state.value.queuedTasks.size)
        assertEquals(task1?.id, manager1.state.value.queuedTasks[0].id)
        assertEquals(task2?.id, manager1.state.value.queuedTasks[1].id)

        val tasksFile = File(storageDir, BackgroundAgentManager.TASKS_FILE_NAME)
        assertTrue("tasks file must exist", tasksFile.exists())

        // Instance 2: Simulate app restart by creating a new manager with same storageDir
        val manager2 = BackgroundAgentManager(
            context = testContext,
            storageDir = storageDir,
            isDeviceBusyWithUserGeneration = { true },
        )

        val restoredState = manager2.state.value
        assertEquals(2, restoredState.queuedTasks.size)
        assertEquals(task1?.id, restoredState.queuedTasks[0].id)
        assertEquals("Research quantum gravity", restoredState.queuedTasks[0].prompt)
        assertEquals(task2?.id, restoredState.queuedTasks[1].id)
        assertEquals("Draft executive summary", restoredState.queuedTasks[1].prompt)

        // Enqueueing in manager2 should produce a monotonically higher ID without collision
        val task3 = manager2.enqueue("Third task")
        assertNotNull(task3)
        assertEquals(3, manager2.state.value.queuedTasks.size)
        assertEquals(task1?.id, manager2.state.value.queuedTasks[0].id)
        assertEquals(task2?.id, manager2.state.value.queuedTasks[1].id)
        assertEquals(task3?.id, manager2.state.value.queuedTasks[2].id)
        assertTrue(task3!!.id != task1?.id && task3.id != task2?.id)
    }

    @Test
    fun interruptedActiveTaskIsRecoveredToQueueOnRestart() {
        val storageDir = tempFolder.newFolder("bg_tasks_2")
        val tasksFile = File(storageDir, BackgroundAgentManager.TASKS_FILE_NAME)

        // Fabricate a persistence file where a task was left RUNNING (simulating OS kill mid-run)
        val json = JSONObject().apply {
            put("version", 1)
            put(
                "activeTask",
                JSONObject().apply {
                    put("id", "bg_task_42")
                    put("prompt", "Analyze complex database schema")
                    put("createdAt", 1000L)
                    put("status", "RUNNING")
                }
            )
            put(
                "queuedTasks",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "bg_task_43")
                            put("prompt", "Next pending turn")
                            put("createdAt", 1005L)
                            put("status", "QUEUED")
                        }
                    )
                }
            )
            put("completedTasks", JSONArray())
        }
        tasksFile.writeText(json.toString())

        val testContext = FakeTestContext()
        val manager = BackgroundAgentManager(
            context = testContext,
            storageDir = storageDir,
        )

        val restored = manager.state.value.queuedTasks
        assertEquals(2, restored.size)
        // Interrupted active task should be recovered into QUEUED status at the head
        assertEquals("bg_task_42", restored[0].id)
        assertEquals(BackgroundTaskStatus.QUEUED, restored[0].status)
        assertEquals("bg_task_43", restored[1].id)
    }

    @Test
    fun completedTasksPersistAndCanBeCleared() = runBlocking {
        val storageDir = tempFolder.newFolder("bg_tasks_3")
        val testContext = FakeTestContext()
        val longResult = "R".repeat(240)

        val manager1 = BackgroundAgentManager(
            context = testContext,
            executeTask = { longResult },
            storageDir = storageDir,
        )

        val task = manager1.enqueue("Execute benchmark")
        assertNotNull(task)

        var attempts = 0
        while (manager1.state.value.completedTasks.isEmpty() && attempts < 20) {
            delay(50)
            attempts++
        }
        assertEquals(1, manager1.state.value.completedTasks.size)

        // Instance 2 verifies persistence of completed tasks
        val manager2 = BackgroundAgentManager(
            context = testContext,
            storageDir = storageDir,
        )
        assertEquals(1, manager2.state.value.completedTasks.size)
        assertEquals(longResult.take(120), manager2.state.value.completedTasks[0].resultSummary)
        val persisted = File(storageDir, BackgroundAgentManager.TASKS_FILE_NAME).readText()
        assertFalse(persisted.contains("Execute benchmark"))
        val completed = JSONObject(persisted).getJSONArray("completedTasks").getJSONObject(0)
        assertFalse(completed.has("prompt"))
        assertEquals(120, completed.getString("resultSummary").length)

        // Clear completed tasks
        manager2.clearCompletedTasks()
        assertTrue(manager2.state.value.completedTasks.isEmpty())

        // Instance 3 confirms clearance persisted
        val manager3 = BackgroundAgentManager(
            context = testContext,
            storageDir = storageDir,
        )
        assertTrue(manager3.state.value.completedTasks.isEmpty())
    }
}
