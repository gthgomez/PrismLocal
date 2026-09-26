package com.prismai.llmhost.generation

/** Serializes chat identity mutations against binding a generation to its chat. */
class GenerationStartGate {
    private var startInProgress = false

    @Synchronized
    fun beginStart(): Boolean {
        if (startInProgress) return false
        startInProgress = true
        return true
    }

    @Synchronized
    fun finishStart() {
        startInProgress = false
    }

    @Synchronized
    fun isStartInProgress(): Boolean = startInProgress

    @Synchronized
    fun <T> runChatTransition(block: () -> T): T? {
        if (startInProgress) return null
        return block()
    }
}
