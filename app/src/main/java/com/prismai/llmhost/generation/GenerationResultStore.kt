package com.prismai.llmhost.generation

class GenerationOutputAccumulator(initialOutput: String = "") {
    private val output = StringBuilder(initialOutput)

    @Synchronized
    fun append(text: String) {
        output.append(text)
    }

    @Synchronized
    fun snapshot(): String = output.toString()
}

/** Retains completed output by the generation and agent-chain owners that produced it. */
class GenerationResultStore(
    private val maxSessionResults: Int = 64,
    private val maxChainResults: Int = 32,
) {
    private val sessionOutputs = LinkedHashMap<Long, String>()
    private val chainOutputs = LinkedHashMap<Long, Pair<Long, String>>()
    private val retainedSessions = mutableSetOf<Long>()
    private val retainedChains = mutableSetOf<Long>()

    @Synchronized
    fun retain(sessionId: Long?, agentChainId: Long?) {
        sessionId?.let(retainedSessions::add)
        agentChainId?.let(retainedChains::add)
    }

    @Synchronized
    fun release(sessionId: Long?, agentChainId: Long?) {
        sessionId?.let(retainedSessions::remove)
        agentChainId?.let(retainedChains::remove)
        trimToLimit(sessionOutputs, maxSessionResults, retainedSessions)
        trimToLimit(chainOutputs, maxChainResults, retainedChains)
    }

    @Synchronized
    fun record(sessionId: Long, agentChainId: Long?, output: String) {
        sessionOutputs[sessionId] = output
        trimToLimit(sessionOutputs, maxSessionResults, retainedSessions)
        if (agentChainId != null) {
            val previous = chainOutputs[agentChainId]
            if (previous == null || sessionId >= previous.first) {
                chainOutputs.remove(agentChainId)
                chainOutputs[agentChainId] = sessionId to output
            }
            trimToLimit(chainOutputs, maxChainResults, retainedChains)
        }
    }

    @Synchronized
    fun outputFor(sessionId: Long, agentChainId: Long? = null): String? =
        if (agentChainId != null) chainOutputs[agentChainId]?.second else sessionOutputs[sessionId]

    private fun <K, V> trimToLimit(
        values: LinkedHashMap<K, V>,
        limit: Int,
        retained: Set<K>,
    ) {
        val safeLimit = limit.coerceAtLeast(1)
        while (values.size > safeLimit) {
            val newestKey = values.keys.lastOrNull()
            val removable = values.keys.firstOrNull { it !in retained && it != newestKey } ?: return
            values.remove(removable)
        }
    }
}
