package com.prismai.llmhost.generation

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class GenerationIdleInputs(
    val generationRunning: Boolean,
    val generationJobActive: Boolean,
    val agentToolActive: Boolean,
    val confirmationPending: Boolean,
    val agentChainActive: Boolean,
    val continuationAvailable: Boolean,
)

object GenerationIdlePolicy {
    /**
     * A completed MAX_TOKENS turn with a resumable trace is idle for background
     * queue purposes, but remains available for an explicit user continuation.
     */
    fun shouldWait(inputs: GenerationIdleInputs): Boolean =
        inputs.generationRunning ||
            inputs.generationJobActive ||
            inputs.agentToolActive ||
            inputs.confirmationPending ||
            (inputs.agentChainActive && !inputs.continuationAvailable)

    fun shouldAbortUnresumableChain(inputs: GenerationIdleInputs): Boolean =
        inputs.agentChainActive &&
            !inputs.generationRunning &&
            !inputs.generationJobActive &&
            !inputs.agentToolActive &&
            !inputs.confirmationPending &&
            !inputs.continuationAvailable
}

object GenerationSessionWait {
    /**
     * Waits until no generation job is active and isGenerating returns false.
     * Handles agent multi-step chains that replace generationJob after each turn.
     */
    suspend fun awaitSessionIdle(
        isGenerating: () -> Boolean,
        getJob: () -> Job?,
        pollMs: Long = 20L,
        maxWaitMs: Long = 30 * 60 * 1000L,
        elapsedTimeMs: () -> Long = { System.currentTimeMillis() },
        onTimeout: () -> Unit = {},
    ): Boolean {
        val completed = withTimeoutOrNull(maxWaitMs.coerceAtLeast(0L)) {
            val deadline = elapsedTimeMs() + maxWaitMs
            while (elapsedTimeMs() < deadline) {
                val job = getJob()
                if (job != null) {
                    // join is cancellable, so the same deadline bounds a stuck
                    // generation job instead of only bounding the polling loop.
                    job.join()
                    // If job completed but getter still returns the same completed job,
                    // yield briefly to allow completion handlers to clear job / update state.
                    if (!job.isActive && getJob() === job) {
                        delay(pollMs)
                    }
                    continue
                }
                if (!isGenerating()) {
                    // Brief settle window: follow-up may flip isGenerating true
                    // on another dispatcher right after job clear.
                    delay(pollMs)
                    if (getJob() == null && !isGenerating()) return@withTimeoutOrNull true
                    continue
                }
                delay(pollMs)
            }
            false
        } ?: false
        if (!completed) {
            onTimeout()
        }
        return completed
    }
}
