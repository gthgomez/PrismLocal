package com.prismai.llmhost

import com.prismai.llmhost.generation.GenerationBudget
import com.prismai.llmhost.generation.PromptBuilder
import com.prismai.llmhost.tools.AgentToolProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationBudgetTest {

    @Test
    fun nonAgentTokenBudgetScalesWithContextLength() {
        val budget2k = GenerationBudget.calculateNonAgentTokenBudget(contextLength = 2048, maxTokens = 512)
        val budget16k = GenerationBudget.calculateNonAgentTokenBudget(contextLength = 16384, maxTokens = 512)

        assertEquals(1180, budget2k)
        assertEquals(15516, budget16k)
        assertTrue(budget16k > budget2k)
    }

    @Test
    fun nonAgentTokenBudgetDoesNotOvershootSmallContext() {
        // 512 - 256 - 356 = -100 → clamp to 0, never force a 512 floor
        val budget = GenerationBudget.calculateNonAgentTokenBudget(
            contextLength = 512,
            maxTokens = 256,
        )
        assertEquals(0, budget)
    }

    @Test
    fun agentHistoryCharBudgetScalesWithContextLength() {
        val instructionChars = 2_400
        val charBudget2k = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = 2048,
            maxTokens = 512,
            userPromptChars = 100,
            instructionBlockChars = instructionChars,
        )
        val charBudget16k = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = 16384,
            maxTokens = 512,
            userPromptChars = 100,
            instructionBlockChars = instructionChars,
        )

        // remaining 2k: 2048 - 512 - 600 - 25 - 500 = 411 tokens → 1644 chars
        assertEquals(1644, charBudget2k)
        // 16k should be well above the old 4k hard ceiling
        assertTrue(charBudget16k > 40_000)
        assertTrue(charBudget16k > charBudget2k)
    }

    @Test
    fun agentHistoryUsesRealInstructionBlockSize() {
        val instructionChars = AgentToolProtocol.instructionBlock().length
        assertTrue(
            "instruction block should be much larger than the old 2400 default",
            instructionChars > 8_000,
        )

        val withRealInstruction = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = 4096,
            maxTokens = 512,
            userPromptChars = 80,
            instructionBlockChars = instructionChars,
        )
        val withUnderEstimate = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = 4096,
            maxTokens = 512,
            userPromptChars = 80,
            instructionBlockChars = 2_400,
        )

        // Real instruction leaves less room for history than the under-estimate
        assertTrue(withRealInstruction < withUnderEstimate)
    }

    @Test
    fun agentHistoryReturnsZeroWhenInstructionConsumesContext() {
        val budget = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = 2048,
            maxTokens = 512,
            userPromptChars = 100,
            instructionBlockChars = 20_000, // ~5k tokens, larger than context
        )
        assertEquals(0, budget)
    }

    @Test
    fun agentHistoryReservesExtraContextCharsForMemory() {
        val withoutMemory = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = 8192,
            maxTokens = 512,
            userPromptChars = 100,
            instructionBlockChars = 2_400,
            extraContextChars = 0,
        )
        val withMemory = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = 8192,
            maxTokens = 512,
            userPromptChars = 100,
            instructionBlockChars = 2_400,
            extraContextChars = 2_000, // 500 tokens
        )
        assertEquals(withoutMemory - 2_000, withMemory)
    }

    @Test
    fun agentFollowUpBudgetsAgainstInjectedToolPayload() {
        val largePayload = 8_000
        val smallPayload = 200
        val large = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = 8192,
            maxTokens = 512,
            userPromptChars = 100,
            toolResultChars = largePayload,
            instructionBlockChars = GenerationBudget.AGENT_FOLLOW_UP_PREAMBLE_CHARS,
        )
        val small = GenerationBudget.calculateAgentHistoryCharBudget(
            contextLength = 8192,
            maxTokens = 512,
            userPromptChars = 100,
            toolResultChars = smallPayload,
            instructionBlockChars = GenerationBudget.AGENT_FOLLOW_UP_PREAMBLE_CHARS,
        )
        assertTrue(small > large)
        assertEquals(largePayload - smallPayload, small - large)
    }

    @Test
    fun promptBuilderSelectsLongerHistoryWithLargerBudget() {
        val builder = PromptBuilder(
            memoryStore = EmptyMemoryStore,
            ragManager = null,
        )

        val transcript = (1..50).map { i ->
            TranscriptMessage(
                id = i.toLong(),
                role = TranscriptRole.USER,
                text = "Message $i: This is a test message to fill up transcript memory budget for testing.",
            )
        }

        val promptSmall = builder.buildPromptWithRecentContext(
            newPrompt = "Hello",
            transcript = transcript,
            activeAssistantTranscriptId = null,
            tokenBudget = 200,
        )

        val promptLarge = builder.buildPromptWithRecentContext(
            newPrompt = "Hello",
            transcript = transcript,
            activeAssistantTranscriptId = null,
            tokenBudget = 8192,
        )

        assertTrue(promptLarge.length > promptSmall.length)
    }

    /** Minimal no-op MemoryStore for pure PromptBuilder packing tests. */
    private object EmptyMemoryStore : MemoryStore {
        override fun insert(fact: MemoryFact): MemoryFact = fact
        override fun update(id: String, fact: String, confidence: Float): Boolean = false
        override fun markDecayed(id: String): Boolean = false
        override fun delete(id: String): Boolean = false
        override fun getAllActive(): List<MemoryFact> = emptyList()
        override fun queryRelevant(query: String, limit: Int): List<MemoryMatch> = emptyList()
        override fun decayOldMemories(olderThanMillis: Long): Int = 0
        override fun findSimilar(fact: String, threshold: Float): List<MemoryFact> = emptyList()
        override fun activeCount(): Int = 0
        override fun totalCount(): Int = 0
        override fun touch(id: String): MemoryFact? = null
    }
}
