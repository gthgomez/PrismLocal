package com.prismai.llmhost.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundGenerationOwnershipTest {
    @Test
    fun activeTaskExcludesOtherTaskAndDefersChatSwitchUntilRelease() {
        val ownership = BackgroundGenerationOwnership()

        assertTrue(ownership.begin("task-a"))
        assertTrue(ownership.isOwner("task-a"))
        assertFalse(ownership.isOwner("task-b"))
        assertFalse(ownership.begin("task-b"))
        assertTrue(ownership.deferChatSwitch("chat-c"))
        assertEquals("chat-c", ownership.takeDeferredChatSwitch("task-a"))
        assertNull(ownership.takeDeferredChatSwitch("task-b"))
        assertNull(ownership.finish("task-b"))
        assertTrue(ownership.hasOwner())
        assertNull(ownership.finish("task-a"))
        assertFalse(ownership.hasOwner())
    }

    @Test
    fun chatSwitchRequestedAtReleaseRemainsOwnedByThatTask() {
        val ownership = BackgroundGenerationOwnership()
        assertTrue(ownership.begin("task-a"))
        assertTrue(ownership.deferChatSwitch("chat-b"))

        assertEquals("chat-b", ownership.finish("task-a"))
        assertNull(ownership.ownerId())
        assertFalse(ownership.deferChatSwitch("chat-c"))
    }
}
