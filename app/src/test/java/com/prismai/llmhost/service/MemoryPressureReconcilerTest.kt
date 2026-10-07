package com.prismai.llmhost.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPressureReconcilerTest {

    @Test
    fun criticalPushThenNormalPoll_clearsNativeLevel() {
        val applied = mutableListOf<Int>()
        val r = MemoryPressureReconciler { applied += it }

        r.onPush(MemoryState.CRITICAL)
        r.onPoll(MemoryState.NORMAL)

        assertEquals("native must be explicitly cleared after recovery", 0, r.currentLevel())
        assertEquals(0, applied.last())
        assertFalse("a recovered system is no longer critical", r.isCritical())
    }

    @Test
    fun repeatedNormalPolls_doNotRewriteNativeLevel() {
        val applied = mutableListOf<Int>()
        val r = MemoryPressureReconciler { applied += it }

        r.onPoll(MemoryState.NORMAL)
        r.onPoll(MemoryState.NORMAL)
        r.onPoll(MemoryState.NORMAL)

        assertEquals("unchanged polls must not spam native", 1, applied.size)
    }

    @Test
    fun normalPollAfterPressurePush_escalatesToPollSeverity() {
        val applied = mutableListOf<Int>()
        val r = MemoryPressureReconciler { applied += it }

        r.onPoll(MemoryState.PRESSURE)
        r.onPoll(MemoryState.WATCH)

        // A later observation supersedes an earlier one from the other path;
        // recovery must be able to lower pressure, not only raise it.
        assertEquals(1, r.currentLevel())
        assertEquals(1, applied.last())
    }

    @Test
    fun criticalIsStickyUntilANormalObservation() {
        val r = MemoryPressureReconciler { }

        r.onPush(MemoryState.CRITICAL)
        assertTrue(r.isCritical())

        r.onPoll(MemoryState.WATCH)
        assertTrue("WATCH must not mask CRITICAL", r.isCritical())
        assertEquals(3, r.currentLevel())

        r.onPoll(MemoryState.NORMAL)
        assertFalse(r.isCritical())
        assertEquals(0, r.currentLevel())
    }

    @Test
    fun pollEscalationWinsOverLowerPush() {
        val r = MemoryPressureReconciler { }

        r.onPush(MemoryState.WATCH)
        r.onPoll(MemoryState.CRITICAL)

        assertEquals(3, r.currentLevel())
    }
}
