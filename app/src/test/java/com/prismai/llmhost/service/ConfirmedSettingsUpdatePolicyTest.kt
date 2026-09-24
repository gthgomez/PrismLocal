package com.prismai.llmhost.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedSettingsUpdatePolicyTest {

    @Test
    fun successfulReloadFinalizesAgentDisableAfterReload() = runBlocking {
        val events = mutableListOf<String>()

        val success = completeConfirmedSettingsUpdate(
            reloadModelId = "model-a",
            reload = {
                events += "reload"
                true
            },
            onReloadFailed = { events += "rollback" },
            finalizeDisabledAgent = { events += "finalize" },
        )

        assertTrue(success)
        assertEquals(listOf("reload", "finalize"), events)
    }

    @Test
    fun failedReloadRollsBackWithoutFinalizingAgentDisable() = runBlocking {
        val events = mutableListOf<String>()

        val success = completeConfirmedSettingsUpdate(
            reloadModelId = "model-a",
            reload = {
                events += "reload"
                false
            },
            onReloadFailed = { events += "rollback" },
            finalizeDisabledAgent = { events += "finalize" },
        )

        assertFalse(success)
        assertEquals(listOf("reload", "rollback"), events)
    }

    @Test
    fun thrownReloadRollsBackWithoutFinalizingAgentDisable() = runBlocking {
        val events = mutableListOf<String>()

        val success = completeConfirmedSettingsUpdate(
            reloadModelId = "model-a",
            reload = {
                events += "reload"
                error("reload failed")
            },
            onReloadFailed = { events += "rollback" },
            finalizeDisabledAgent = { events += "finalize" },
        )

        assertFalse(success)
        assertEquals(listOf("reload", "rollback"), events)
    }

    @Test
    fun noReloadFinalizesWithoutNativeReload() = runBlocking {
        val events = mutableListOf<String>()

        val success = completeConfirmedSettingsUpdate(
            reloadModelId = null,
            reload = {
                events += "reload"
                true
            },
            onReloadFailed = { events += "rollback" },
            finalizeDisabledAgent = { events += "finalize" },
        )

        assertTrue(success)
        assertEquals(listOf("finalize"), events)
    }
}
