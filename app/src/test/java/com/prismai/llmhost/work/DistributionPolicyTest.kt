package com.prismai.llmhost.work

import com.prismai.llmhost.Capability
import com.prismai.llmhost.CapabilityRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionPolicyTest {

    private val playConfig = object : DistributionConfig {
        override val developerWorkMode: Boolean = false
        override val localProcessExecutionAvailable: Boolean = false
        override val babelHostExecutionAvailable: Boolean = false
    }

    private val devConfig = object : DistributionConfig {
        override val developerWorkMode: Boolean = true
        override val localProcessExecutionAvailable: Boolean = true
        override val babelHostExecutionAvailable: Boolean = true
    }

    @Test
    fun playDistributionHardDeniesAllDeveloperAndSweCapabilities() {
        val policy = DistributionCapabilityPolicy(playConfig)
        val registry = CapabilityRegistry()

        val developerCapabilities = listOf(
            Capability.SHELL_EXEC,
            Capability.GIT_READ,
            Capability.GIT_WRITE,
            Capability.GIT_REMOTE_WRITE,
            Capability.SWE_TASK_SUBMIT,
            Capability.WORKSPACE_DELETE,
        )

        for (cap in developerCapabilities) {
            registry.grant(cap)
            assertTrue("Registry grant succeeded", registry.isGranted(cap))

            val check = policy.check(cap, registry)
            assertFalse("Play distribution MUST hard-deny ${cap.name}", check.granted)
            assertTrue(check.reason.contains("prohibited in the Play distribution"))
        }
    }

    @Test
    fun playDistributionPermitsConsumerAndAppPrivateWorkspaceCapabilities() {
        val policy = DistributionCapabilityPolicy(playConfig)
        val registry = CapabilityRegistry()

        val consumerCapabilities = listOf(
            Capability.CHAT_READ,
            Capability.CHAT_MANAGE,
            Capability.MODEL_SWITCH,
            Capability.FILE_READ,
            Capability.FILE_WRITE,
            Capability.WORKSPACE_READ,
            Capability.WORKSPACE_WRITE,
            Capability.VOICE_INPUT,
            Capability.SYSTEM_INFO,
        )

        for (cap in consumerCapabilities) {
            registry.grant(cap)
            val check = policy.check(cap, registry)
            assertTrue("Play distribution MUST permit ${cap.name}", check.granted)
        }
    }

    @Test
    fun devDistributionPermitsShellExecWhenGrantedAndEnabled() {
        val policy = DistributionCapabilityPolicy(devConfig)
        val registry = CapabilityRegistry()

        // When not granted in registry, check fails
        val checkBeforeGrant = policy.check(Capability.SHELL_EXEC, registry)
        assertFalse(checkBeforeGrant.granted)

        // When granted in registry, check succeeds
        registry.grant(Capability.SHELL_EXEC)
        val checkAfterGrant = policy.check(Capability.SHELL_EXEC, registry)
        assertTrue("Dev distribution allows SHELL_EXEC when granted", checkAfterGrant.granted)
    }

    @Test
    fun devDistributionBlocksShellExecIfProcessExecutionDisabled() {
        val devWithoutProcess = object : DistributionConfig {
            override val developerWorkMode: Boolean = true
            override val localProcessExecutionAvailable: Boolean = false
            override val babelHostExecutionAvailable: Boolean = true
        }
        val policy = DistributionCapabilityPolicy(devWithoutProcess)
        val registry = CapabilityRegistry().apply { grant(Capability.SHELL_EXEC) }

        val check = policy.check(Capability.SHELL_EXEC, registry)
        assertFalse("Must block SHELL_EXEC when localProcessExecutionAvailable is false", check.granted)
        assertTrue(check.reason.contains("Local process execution is disabled"))
    }
}
