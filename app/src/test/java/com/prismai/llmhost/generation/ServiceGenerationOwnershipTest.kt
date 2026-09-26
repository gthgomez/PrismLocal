package com.prismai.llmhost.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceGenerationOwnershipTest {
    @Test
    fun backgroundExecutionStaysOnSourceChatAndDefersSwitchUntilRelease() {
        val ownership = ServiceGenerationOwnership()
        val chats = linkedSetOf("chat-a", "chat-b", "chat-c")
        var selected = "chat-b"
        assertTrue(ownership.engine.begin("task-a"))
        assertTrue(
            ownership.mustDeferBackgroundTask(
                generationRunning = true,
                confirmationPending = false,
                followUpScheduled = false,
                agentToolJobActive = false,
            ),
        )

        val prepared = ownership.prepareBackgroundChat(
            taskId = "task-a",
            sourceChatId = "chat-a",
            selectedChatId = selected,
            chatExists = { it in chats },
            switchToSource = { chatId ->
                selected = chatId
                true
            },
        )

        assertEquals("chat-a", prepared.ownerChatId)
        assertEquals("chat-b", prepared.restoreChatId)
        assertTrue(prepared.switchedToSource)
        assertEquals("chat-a", selected)
        ownership.engine.bindAgentChain("task-a", 8L)
        assertFalse(ownership.engine.allowsAgentFollowUp(4L))
        assertTrue(ownership.engine.allowsAgentFollowUp(8L))
        assertTrue(ownership.engine.deferChatSwitch("chat-c"))

        val results = GenerationResultStore(maxSessionResults = 1)
        results.retain(sessionId = 1L, agentChainId = 8L)
        results.record(sessionId = 1L, agentChainId = 8L, output = "owned output")
        results.record(sessionId = 2L, agentChainId = null, output = "later user output")
        assertEquals("owned output", results.outputFor(sessionId = 1L, agentChainId = 8L))

        assertEquals(
            "chat-c",
            ownership.chatToRestore(
                taskId = "task-a",
                restoreChatId = prepared.restoreChatId,
                generating = false,
                sessionStillWaiting = false,
                currentChatId = selected,
                sourceChatId = "chat-a",
                chatExists = { it in chats },
            ),
        )
        results.release(sessionId = 1L, agentChainId = 8L)
    }

    @Test
    fun deletedSourceChatAndLostOwnershipFailClosed() {
        val ownership = ServiceGenerationOwnership()
        assertTrue(ownership.engine.begin("task-a"))

        assertThrows(IllegalStateException::class.java) {
            ownership.prepareBackgroundChat(
                taskId = "task-b",
                sourceChatId = "chat-a",
                selectedChatId = "chat-a",
                chatExists = { true },
                switchToSource = { true },
            )
        }
        assertThrows(IllegalStateException::class.java) {
            ownership.prepareBackgroundChat(
                taskId = "task-a",
                sourceChatId = "chat-a",
                selectedChatId = "chat-a",
                chatExists = { false },
                switchToSource = { true },
            )
        }
    }

    @Test
    fun waitingSessionDoesNotConsumeDeferredChatSwitch() {
        val ownership = ServiceGenerationOwnership()
        assertTrue(ownership.engine.begin("task-a"))
        assertTrue(ownership.engine.deferChatSwitch("chat-c"))

        assertNull(
            ownership.chatToRestore(
                taskId = "task-a",
                restoreChatId = "chat-b",
                generating = false,
                sessionStillWaiting = true,
                currentChatId = "chat-a",
                sourceChatId = "chat-a",
                chatExists = { true },
            ),
        )
        assertEquals("chat-c", ownership.engine.finish("task-a"))
    }
}
