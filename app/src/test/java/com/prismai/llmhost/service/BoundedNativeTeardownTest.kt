package com.prismai.llmhost.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedNativeTeardownTest {

    @Test(timeout = 2_000)
    fun lockedLifecycleFallsBackToNativeDestroyAfterTimeout() = runBlocking {
        val lifecycleLock = Mutex()
        lifecycleLock.lock()
        val events = mutableListOf<String>()

        val acquired = withTimeout(500) {
            runBoundedNativeTeardown(
                lifecycleLock = lifecycleLock,
                lockTimeoutMs = 50L,
                cancelAndJoinGeneration = { events += "cancel" },
                destroyNative = { events += "destroy" },
                onLockTimeout = { timeoutMs -> events += "fallback:$timeoutMs" },
            )
        }

        assertFalse(acquired)
        assertEquals(listOf("fallback:50", "destroy"), events)
        lifecycleLock.unlock()
    }

    @Test(timeout = 2_000)
    fun acquiredLifecycleLockCancelsGenerationBeforeNativeDestroy() = runBlocking {
        val events = mutableListOf<String>()

        val acquired = runBoundedNativeTeardown(
            lifecycleLock = Mutex(),
            lockTimeoutMs = 50L,
            cancelAndJoinGeneration = { events += "cancel" },
            destroyNative = { events += "destroy" },
            onLockTimeout = { events += "fallback" },
        )

        assertTrue(acquired)
        assertEquals(listOf("cancel", "destroy"), events)
    }
}
