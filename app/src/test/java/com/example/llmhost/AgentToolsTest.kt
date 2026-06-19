package com.example.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsTest {
    @Test
    fun unknownToolIsRejectedWithUnknownCode() {
        val result = AgentToolRegistry.validate(AgentToolCall("invented_tool"))

        assertFalse(result.valid)
        assertEquals(AgentToolErrorCode.UNKNOWN_TOOL, result.errorCode)
    }

    @Test
    fun restrictedToolRequestIsBlockedBeforeUnknownHandling() {
        val result = AgentToolRegistry.validate(
            AgentToolCall(
                name = "run_shell_command",
            )
        )

        assertFalse(result.valid)
        assertEquals(AgentToolErrorCode.RESTRICTED_TOOL, result.errorCode)
    }

    @Test
    fun searchChatsContractRequiresBoundedQuery() {
        val definition = AgentToolRegistry.find("search_chats")

        assertEquals(setOf("query"), definition?.requiredArguments)
        assertEquals(120, definition?.maxStringLengths?.get("query"))
        assertEquals(25, definition?.intRanges?.get("limit")?.max)
    }

    @Test
    fun contractListsRestrictedCategoriesAndNewDiagnostics() {
        val names = AgentToolRegistry.definitions.map { it.name }

        assertTrue("get_tool_capabilities" in names)
        assertTrue("validate_runtime_settings" in names)
        assertTrue("get_active_operation" in names)
        assertTrue("shell_or_terminal" in AgentToolRegistry.restrictedCategories())
    }
}
