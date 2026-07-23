package com.prismai.llmhost

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
    fun unknownToolsRejectedBeforeArgumentValidation() {
        // "run_shell_command" is not in the tool registry → UNKNOWN_TOOL.
        // (Capability check passes for unknown tools since they map to no capabilities.)
        val result = AgentToolRegistry.validate(
            AgentToolCall(
                name = "run_shell_command",
            )
        )

        assertFalse(result.valid)
        assertEquals(AgentToolErrorCode.UNKNOWN_TOOL, result.errorCode)
    }

    @Test
    fun restrictedCapabilityToolsAreBlocked() {
        // "export_chat" requires FILE_WRITE → RESTRICTED, not auto-granted
        val result = AgentToolRegistry.validate(
            AgentToolCall(
                name = "export_chat",
                arguments = org.json.JSONObject().put("format", "markdown"),
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

    @Test
    fun capabilityCheckBlocksRestrictedToolsByNameNotArguments() {
        // "download_model" requires MODEL_DOWNLOAD → RESTRICTED capability, not auto-granted.
        // Capability check uses tool name only — arguments are not inspected for security.
        val call = AgentToolCall(
            name = "download_model",
            arguments = org.json.JSONObject().put("entry_id", "test-entry"),
        )
        val result = AgentToolRegistry.validate(call)
        assertFalse(result.valid)
        assertEquals(AgentToolErrorCode.RESTRICTED_TOOL, result.errorCode)
    }

    @Test
    fun parserHandlesPreProseAndPostProse() {
        val rawText = """
            Certainly! I will now list the installed models for you.
            ```json
            {
              "tool_call": {
                "name": "list_installed_models",
                "arguments": {
                  "limit": 5
                },
                "reason": "checking installed models"
              }
            }
            ```
            Hope this helps!
        """.trimIndent()
        val call = AgentToolProtocol.parseToolCall(rawText)
        org.junit.Assert.assertNotNull(call)
        assertEquals("list_installed_models", call?.name)
        assertEquals(5, call?.arguments?.optInt("limit"))
        assertEquals("checking installed models", call?.reason)
    }

    @Test
    fun reasoningPrefixExtraction() {
        val rawText = """
            Here is my reasoning: I need to check model capability.
            {"tool_call":{"name":"get_model_status","arguments":{},"reason":"status info"}}
        """.trimIndent()
        val prefix = AgentToolProtocol.extractReasoningPrefix(rawText)
        assertEquals("Here is my reasoning: I need to check model capability.", prefix)
    }

    @Test
    fun argumentSimilarityCheck() {
        val json1 = org.json.JSONObject().put("query", "llama").put("limit", 5)
        val json2 = org.json.JSONObject().put("limit", 5).put("query", "llama ")
        
        // Exact and whitespace normalized match
        assertTrue(AgentToolProtocol.areArgumentsSimilar(json1, json2))

        // Semantic substring match
        val json3 = org.json.JSONObject().put("query", "llama 3")
        val json4 = org.json.JSONObject().put("query", "llama 3.1")
        assertTrue(AgentToolProtocol.areArgumentsSimilar(json3, json4))

        // Different query terms should be rejected
        val json5 = org.json.JSONObject().put("query", "alpaca")
        assertFalse(AgentToolProtocol.areArgumentsSimilar(json1, json5))
        
        // Different keys should be rejected
        val json6 = org.json.JSONObject().put("name", "llama")
        assertFalse(AgentToolProtocol.areArgumentsSimilar(json3, json6))
    }
}
