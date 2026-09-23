package com.prismai.llmhost.bridge

import com.prismai.llmhost.GenerationChunk
import com.prismai.llmhost.GenerationSettings
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.isActive

private const val STREAM_BUFFER_HEADROOM = 64

/** Bounds the stream by the native token limit while reserving terminal headroom. */
internal fun generationStreamBufferCapacity(maxTokens: Int): Int =
    maxTokens.coerceIn(GenerationSettings.MIN_MAX_TOKENS, GenerationSettings.MAX_MAX_TOKENS) +
        STREAM_BUFFER_HEADROOM

/**
 * Sends a stream chunk without dropping it when the bounded buffer is full.
 * The fast path stays non-blocking; the suspending fallback is what applies
 * backpressure to a slow consumer. A normal downstream close ends the producer,
 * while coroutine cancellation is allowed to propagate to the bridge cleanup.
 */
internal suspend fun ProducerScope<GenerationChunk>.sendChunk(chunk: GenerationChunk) {
    if (trySend(chunk).isSuccess) return
    if (!isActive) return

    try {
        send(chunk)
    } catch (_: ClosedSendChannelException) {
        // The collector closed the flow normally; there is no consumer left to receive.
    }
}
