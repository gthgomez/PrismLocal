package com.prismai.llmhost.export

import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DpoDatasetExporterTest {

    @Test
    fun testExportsDpoChosenVsRejectedPairs() {
        val now = System.currentTimeMillis()
        val session = ChatSession(id = "dpo-123", title = "DPO Test", createdAt = now, updatedAt = now, modelId = "qwen2.5-1.5b", messageCount = 2)
        val messages = listOf(
            TranscriptMessage(id = 1L, role = TranscriptRole.USER, text = "Write a python function for fibonacci"),
            TranscriptMessage(
                id = 2L,
                role = TranscriptRole.ASSISTANT,
                text = "def fib(n):\n    return n if n <= 1 else fib(n-1) + fib(n-2)", // chosen
                regenerationHistory = listOf("def fib(n): return 0") // rejected attempt
            )
        )

        val exportedJsonStr = DpoDatasetExporter.exportDpoPairs(session, messages)
        val array = JSONArray(exportedJsonStr)

        assertEquals(1, array.length())
        val record = array.getJSONObject(0)
        assertEquals("Write a python function for fibonacci", record.getString("prompt"))
        assertTrue(record.getString("chosen").contains("def fib(n):"))
        val rejectedArray = record.getJSONArray("rejected")
        assertEquals(1, rejectedArray.length())
        assertEquals("def fib(n): return 0", rejectedArray.getString(0))
    }
}
