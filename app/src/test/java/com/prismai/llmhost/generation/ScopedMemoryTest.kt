package com.prismai.llmhost.generation

import com.prismai.llmhost.MemoryFact
import com.prismai.llmhost.MemoryMatch
import com.prismai.llmhost.MemoryRetriever
import com.prismai.llmhost.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedMemoryTest {
    private class Store : MemoryStore {
        val facts = mutableListOf<MemoryFact>()
        override fun insert(fact: MemoryFact): MemoryFact = fact.also { facts.add(it) }
        override fun update(id: String, fact: String, confidence: Float) = false
        override fun markDecayed(id: String) = false
        override fun delete(id: String) = facts.removeAll { it.id == id }
        override fun getAllActive() = facts.toList()
        override fun queryRelevant(query: String, limit: Int): List<MemoryMatch> = error("unscoped search")
        override fun decayOldMemories(olderThanMillis: Long) = 0
        override fun findSimilar(fact: String, threshold: Float) = emptyList<MemoryFact>()
        override fun activeCount() = facts.size
        override fun totalCount() = facts.size
        override fun touch(id: String): MemoryFact? = null
    }

    @Test
    fun promptOnlyIncludesTheCurrentChatsFactsAndStopsAfterDeletion() {
        val store = Store()
        val mine = store.insert(MemoryFact(id = "one", fact = "Prefers mint tea", sourceChatId = "chat-a"))
        store.insert(MemoryFact(id = "two", fact = "Prefers black tea", sourceChatId = "chat-b"))
        store.insert(MemoryFact(id = "legacy", fact = "Prefers lemon tea", sourceChatId = null))
        val builder = PromptBuilder(store)
        val context = builder.buildMemoryContext("Which tea does the user prefer?", "chat-a")
        assertTrue(context.contains("mint tea"))
        assertFalse(context.contains("black tea"))
        assertFalse(context.contains("lemon tea"))
        assertTrue(builder.buildMemoryContext("tea", null).isEmpty())
        store.delete(mine.id)
        assertTrue(builder.buildMemoryContext("tea", "chat-a").isEmpty())
    }

    @Test
    fun scopedRankingFiltersBeforeApplyingLimit() {
        val facts = listOf(
            MemoryFact(id = "other", fact = "mint tea", sourceChatId = "chat-b"),
            MemoryFact(id = "mine", fact = "mint tea with honey", sourceChatId = "chat-a"),
        )
        assertEquals(listOf("mine"), MemoryRetriever.queryForChat("mint tea", facts, "chat-a", 1).map { it.fact.id })
        assertTrue(MemoryRetriever.queryForChat("mint tea", facts, null, 1).isEmpty())
    }
}
