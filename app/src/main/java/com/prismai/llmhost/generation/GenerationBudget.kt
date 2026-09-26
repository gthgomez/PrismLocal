package com.prismai.llmhost.generation

/**
 * Pure functions for context window budgeting across chat and agent flows.
 *
 * Token estimates use a simple chars/4 heuristic (no tokenizer dependency).
 * Callers must pass real instruction / memory / tool payload sizes — defaults
 * never invent a fake instruction budget that under-reserves the tool registry.
 */
object GenerationBudget {

    /** Rough chars-per-token for prompt packing estimates. */
    const val CHARS_PER_TOKEN = 4

    /**
     * Non-agent reserve for the fixed system lines in [PromptBuilder] plus a
     * small safety pad. Memory and the user prompt are counted inside
     * [PromptBuilder.buildPromptWithRecentContext] against the returned budget.
     */
    const val DEFAULT_NON_AGENT_RESERVED_TOKENS = 356

    /** General safety pad for agent packing (template labels, grammar noise). */
    const val DEFAULT_AGENT_PAD_TOKENS = 500

    /**
     * Approximate fixed preamble for [com.prismai.llmhost.tools.AgentToolProtocol.buildToolResultPrompt]
     * (excluding history, user prompt, and tool JSON).
     */
    const val AGENT_FOLLOW_UP_PREAMBLE_CHARS = 512

    const val CONTEXT_HEADROOM_TOKENS = 8
    const val MESSAGE_TEMPLATE_TOKENS = 8

    fun estimateTokensFromChars(chars: Int): Int =
        (chars.coerceAtLeast(0) / CHARS_PER_TOKEN)

    /** ASCII stays near chars/4. Non-ASCII counts as at least one token per character. */
    fun estimateTokens(text: String): Int {
        var tokens = 0
        var ascii = 0
        for (ch in text) {
            if (ch.code <= 0x7F) {
                ascii++
            } else {
                tokens += (ascii + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
                ascii = 0
                tokens += 1
            }
        }
        tokens += (ascii + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
        return tokens
    }

    /**
     * True when the new user turn, memory, and instruction text fit with no history.
     * History can be dropped; this turn cannot.
     */
    fun userTurnFits(
        contextLength: Int,
        maxTokens: Int,
        userPrompt: String,
        memoryContext: String = "",
        instructionText: String = "",
        reservedTokens: Int = 0,
    ): Boolean {
        val needed = estimateTokens(userPrompt) +
            estimateTokens(memoryContext) +
            estimateTokens(instructionText) +
            MESSAGE_TEMPLATE_TOKENS * 2 +
            CONTEXT_HEADROOM_TOKENS +
            reservedTokens.coerceAtLeast(0) +
            maxTokens.coerceAtLeast(0)
        return needed <= contextLength
    }

    /**
     * Token budget for non-agent packing in [PromptBuilder].
     *
     * Returns remaining room for (user prompt + memory + history + system lines
     * already partly reserved). Never forces a floor that exceeds remaining room.
     */
    fun calculateNonAgentTokenBudget(
        contextLength: Int,
        maxTokens: Int,
        reservedTokens: Int = DEFAULT_NON_AGENT_RESERVED_TOKENS,
    ): Int {
        val remaining = contextLength - maxTokens.coerceAtLeast(0) - reservedTokens.coerceAtLeast(0)
        return remaining.coerceAtLeast(0)
    }

    /**
     * Character budget for agent transcript history only.
     *
     * [instructionBlockChars], [userPromptChars], [toolResultChars], and
     * [extraContextChars] (e.g. memory) are reserved outside the history window.
     * When remaining room is exhausted, returns 0 (no forced 1600-char floor).
     */
    fun calculateAgentHistoryCharBudget(
        contextLength: Int,
        maxTokens: Int,
        userPromptChars: Int,
        toolResultChars: Int = 0,
        instructionBlockChars: Int = 0,
        extraContextChars: Int = 0,
        reservedPadTokens: Int = DEFAULT_AGENT_PAD_TOKENS,
    ): Int {
        val remainingTokens = contextLength -
            maxTokens.coerceAtLeast(0) -
            estimateTokensFromChars(instructionBlockChars) -
            estimateTokensFromChars(userPromptChars) -
            estimateTokensFromChars(toolResultChars) -
            estimateTokensFromChars(extraContextChars) -
            reservedPadTokens.coerceAtLeast(0)
        return remainingTokens.coerceAtLeast(0) * CHARS_PER_TOKEN
    }
}
