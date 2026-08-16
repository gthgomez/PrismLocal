package com.prismai.llmhost.work

import com.prismai.llmhost.Capability
import com.prismai.llmhost.CapabilityCheck
import com.prismai.llmhost.CapabilityRegistry

/**
 * Distribution-aware policy that enforces compile-time and distribution boundaries
 * across capabilities using a fail-closed allowlist architecture.
 *
 * In Play distribution:
 *   Only consumer/mobile capabilities are permitted. All developer/SWE capabilities
 *   (SHELL_EXEC, GIT_READ, GIT_WRITE, GIT_REMOTE_WRITE, SWE_TASK_SUBMIT, WORKSPACE_DELETE)
 *   are hard-denied regardless of session grants.
 * In Dev distribution:
 *   All capabilities are permitted subject to session registry grants and risk confirmations.
 */
class DistributionCapabilityPolicy(
    private val config: DistributionConfig = DistributionConfigProvider,
) {
    companion object {
        /**
         * Positive allowlist for Play Store distribution.
         * Any capability not explicitly present in this set is denied on Play builds.
         */
        val PLAY_ALLOWED_CAPABILITIES: Set<Capability> = setOf(
            Capability.CHAT_READ,
            Capability.CHAT_MANAGE,
            Capability.MODEL_SWITCH,
            Capability.MODEL_IMPORT,
            Capability.MODEL_DELETE,
            Capability.MODEL_DOWNLOAD,
            Capability.GENERATION_CONFIGURE,
            Capability.BENCHMARK_RUN,
            Capability.FILE_READ,
            Capability.FILE_WRITE,
            Capability.NETWORK_SEARCH,
            Capability.CONTACTS_READ,
            Capability.CALENDAR_READ,
            Capability.SMS_READ,
            Capability.MEMORY_READ,
            Capability.MEMORY_WRITE,
            Capability.VOICE_INPUT,
            Capability.VOICE_OUTPUT,
            Capability.SYSTEM_INFO,
            Capability.WORKSPACE_READ,
            Capability.WORKSPACE_WRITE,
        )

        /**
         * Explicit deny list for developer / SWE capabilities on Play distribution.
         */
        val PLAY_HARD_DENIED_CAPABILITIES: Set<Capability> = setOf(
            Capability.SHELL_EXEC,
            Capability.GIT_READ,
            Capability.GIT_WRITE,
            Capability.GIT_REMOTE_WRITE,
            Capability.SWE_TASK_SUBMIT,
            Capability.WORKSPACE_DELETE,
        )
    }

    fun check(capability: Capability, registry: CapabilityRegistry): CapabilityCheck {
        // 1. Fail-closed distribution check for Play builds
        if (!config.developerWorkMode) {
            if (capability !in PLAY_ALLOWED_CAPABILITIES || capability in PLAY_HARD_DENIED_CAPABILITIES) {
                return CapabilityCheck(
                    granted = false,
                    missingCapabilities = setOf(capability),
                    reason = "Capability '${capability.name}' is prohibited in the Play distribution",
                )
            }
        }

        // 2. Process execution availability gate
        if (capability == Capability.SHELL_EXEC && !config.localProcessExecutionAvailable) {
            return CapabilityCheck(
                granted = false,
                missingCapabilities = setOf(capability),
                reason = "Local process execution is disabled in this runtime",
            )
        }

        // 3. Session-level registry grant check
        return registry.check(setOf(capability))
    }

    fun checkAll(required: Set<Capability>, registry: CapabilityRegistry): CapabilityCheck {
        for (cap in required) {
            val check = check(cap, registry)
            if (!check.granted) return check
        }
        return CapabilityCheck(granted = true)
    }
}
