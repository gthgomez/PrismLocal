package com.prismai.llmhost.export

import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DatasetExporterTest {

    @Test
    fun testExportShareGptFormat() {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = "chat-123",
            title = "Test Chat",
            createdAt = now,
            updatedAt = now,
            modelId = "qwen2.5-1.5b",
            messageCount = 2
        )
        val messages = listOf(
            TranscriptMessage(id = 1L, role = TranscriptRole.USER, text = "Hello model"),
            TranscriptMessage(id = 2L, role = TranscriptRole.ASSISTANT, text = "Hello human")
        )

        val exportedJsonStr = DatasetExporter.exportDataset(session, messages, DatasetExportFormat.SHAREGPT)
        val json = JSONObject(exportedJsonStr)

        assertEquals("chat-123", json.getString("id"))
        assertEquals("qwen2.5-1.5b", json.getString("model"))
        val conversations = json.getJSONArray("conversations")
        assertEquals(2, conversations.length())
        assertEquals("human", conversations.getJSONObject(0).getString("from"))
        assertEquals("Hello model", conversations.getJSONObject(0).getString("value"))
        assertEquals("gpt", conversations.getJSONObject(1).getString("from"))
        assertEquals("Hello human", conversations.getJSONObject(1).getString("value"))
    }

    @Test
    fun testExportAlpacaFormat() {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = "chat-456",
            title = "Test Alpaca",
            createdAt = now,
            updatedAt = now,
            modelId = "llama3.2-1b",
            messageCount = 2
        )
        val messages = listOf(
            TranscriptMessage(id = 1L, role = TranscriptRole.USER, text = "Summarize LLM"),
            TranscriptMessage(id = 2L, role = TranscriptRole.ASSISTANT, text = "LLM stands for Large Language Model.")
        )

        val exportedJsonStr = DatasetExporter.exportDataset(session, messages, DatasetExportFormat.ALPACA)
        val array = org.json.JSONArray(exportedJsonStr)

        assertEquals(1, array.length())
        val firstObj = array.getJSONObject(0)
        assertEquals("Summarize LLM", firstObj.getString("instruction"))
        assertEquals("LLM stands for Large Language Model.", firstObj.getString("output"))
    }
}
