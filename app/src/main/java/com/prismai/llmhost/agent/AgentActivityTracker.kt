package com.prismai.llmhost.agent

import java.util.concurrent.ConcurrentHashMap

class AgentActivityTracker {
    private val followUpChainIds = ConcurrentHashMap.newKeySet<Long>()

    fun markFollowUp(chainId: Long) {
        require(chainId > 0L)
        followUpChainIds.add(chainId)
    }

    fun clearFollowUp(chainId: Long) {
        require(chainId > 0L)
        followUpChainIds.remove(chainId)
    }

    fun hasFollowUp(chainId: Long): Boolean {
        require(chainId > 0L)
        return followUpChainIds.contains(chainId)
    }
}
