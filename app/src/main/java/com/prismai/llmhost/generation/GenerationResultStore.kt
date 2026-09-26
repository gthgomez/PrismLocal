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
    private data class StoredGeneration(
        val output: String,
        val terminalReason: String,
    )

    private val sessionOutputs = LinkedHashMap<Long, StoredGeneration>()
    private val chainOutputs = LinkedHashMap<Long, Pair<Long, StoredGeneration>>()
    private val retainedSessions = mutableMapOf<Long, Int>()
    private val retainedChains = mutableMapOf<Long, Int>()

    @Synchronized
    fun retain(sessionId: Long?, agentChainId: Long?) {
        sessionId?.let { retainedSessions[it] = (retainedSessions[it] ?: 0) + 1 }
        agentChainId?.let { retainedChains[it] = (retainedChains[it] ?: 0) + 1 }
    }

    @Synchronized
    fun release(sessionId: Long?, agentChainId: Long?) {
        sessionId?.let { releaseRetention(retainedSessions, it) }
        agentChainId?.let { releaseRetention(retainedChains, it) }
        trimToLimit(sessionOutputs, maxSessionResults, retainedSessions.keys)
        trimToLimit(chainOutputs, maxChainResults, retainedChains.keys)
    }

    @Synchronized
    fun record(
        sessionId: Long,
        agentChainId: Long?,
        output: String,
        terminalReason: String = "EOF",
    ) {
        val stored = StoredGeneration(output, terminalReason)
        sessionOutputs[sessionId] = stored
        trimToLimit(sessionOutputs, maxSessionResults, retainedSessions.keys)
        if (agentChainId != null) {
            val previous = chainOutputs[agentChainId]
            if (previous == null || sessionId >= previous.first) {
                chainOutputs.remove(agentChainId)
                chainOutputs[agentChainId] = sessionId to stored
            }
            trimToLimit(chainOutputs, maxChainResults, retainedChains.keys)
        }
    }

    @Synchronized
    fun outputFor(sessionId: Long, agentChainId: Long? = null): String? =
        storedFor(sessionId, agentChainId)?.output

    @Synchronized
    fun terminalReasonFor(sessionId: Long, agentChainId: Long? = null): String? =
        storedFor(sessionId, agentChainId)?.terminalReason

    private fun storedFor(sessionId: Long, agentChainId: Long?): StoredGeneration? =
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

    private fun <K> releaseRetention(retained: MutableMap<K, Int>, key: K) {
        val count = retained[key] ?: return
        if (count <= 1) retained.remove(key) else retained[key] = count - 1
    }
}
