package com.prismai.llmhost
import com.prismai.llmhost.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the capability-policy invariants:
 *  - every registered tool has an explicit capability mapping (the policy fails closed otherwise),
 *  - network egress (web_search) is confirmation-gated.
 */
class ToolCapabilityMappingTest {

    @Test
    fun everyRegisteredToolHasACapabilityMapping() {
        val unmapped = AgentToolRegistry.definitions
            .map { it.name }
            .filterNot { ToolCapabilityMapping.isMapped(it) }

        assertTrue(
            "Registered tools missing a capability mapping: $unmapped",
            unmapped.isEmpty(),
        )
    }

    @Test
    fun webSearchRequiresConfirmation() {
        assertEquals(
            AgentToolRisk.CONFIRM,
            AgentToolRegistry.find("web_search")?.risk,
        )
    }
}
