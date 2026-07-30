package com.prismai.llmhost.export

import org.junit.Assert.*
import org.junit.Test

class ContextPrunerTest {

    @Test
    fun testPrunesWhitespaceWhilePreservingToolCallAndSystemBlocks() {
        val rawInput = """
            <system>You are a helpful AI assistant.</system>
            
            
            Please process this request.
            
            <tool_call>{"action": "read_file", "args": {"path": "test.txt"}}</tool_call>
        """.trimIndent()

        val pruned = ContextPruner.prune(rawInput)

        assertTrue(pruned.contains("<system>You are a helpful AI assistant.</system>"))
        assertTrue(pruned.contains("<tool_call>{\"action\": \"read_file\", \"args\": {\"path\": \"test.txt\"}}</tool_call>"))
        assertFalse(pruned.contains("\n\n\n"))
    }
}
