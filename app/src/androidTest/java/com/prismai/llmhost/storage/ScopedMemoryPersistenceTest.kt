package com.prismai.llmhost.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.MemoryFact
import com.prismai.llmhost.MemoryRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScopedMemoryPersistenceTest {
    @Test
    fun savedFactSurvivesReopenIsChatScopedAndDisappearsAfterDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val chat = "test-chat-${UUID.randomUUID()}"
        val first = SqlMemoryStore(context)
        try {
            first.insert(MemoryFact(id = id, fact = "Prefers mint tea", sourceChatId = chat))
            val reopened = SqlMemoryStore(context)
            val saved = reopened.getAllActive().filter { it.id == id }
            assertEquals(listOf(id), MemoryRetriever.forChat(saved, chat).map { it.id })
            assertTrue(MemoryRetriever.forChat(saved, "other-chat").isEmpty())
            assertTrue(reopened.delete(id))
            assertTrue(SqlMemoryStore(context).getAllActive().none { it.id == id })
        } finally {
            first.delete(id)
        }
    }
}
