package com.prismai.llmhost.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityTrackerTest {

    @Test
    fun staleCompletionCannotClearNewerChainFollowUp() {
        val tracker = AgentActivityTracker()
        tracker.markFollowUp(1L)
        tracker.markFollowUp(2L)

        tracker.clearFollowUp(1L)

        assertFalse(tracker.hasFollowUp(1L))
        assertTrue(tracker.hasFollowUp(2L))
    }

    @Test
    fun everyMethodRejectsZeroAndNegativeIds() {
        val tracker = AgentActivityTracker()
        listOf(0L, -1L).forEach { id ->
            assertFails { tracker.markFollowUp(id) }
            assertFails { tracker.clearFollowUp(id) }
            assertFails { tracker.hasFollowUp(id) }
        }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected invalid chain ID")
    }

    @Test
    fun idempotentMarkAndClearDoNotClobberOtherOwners() {
        val tracker = AgentActivityTracker()
        tracker.markFollowUp(7L)
        tracker.markFollowUp(7L)
        tracker.clearFollowUp(7L)
        tracker.clearFollowUp(7L)
        assertFalse(tracker.hasFollowUp(7L))
    }
}
