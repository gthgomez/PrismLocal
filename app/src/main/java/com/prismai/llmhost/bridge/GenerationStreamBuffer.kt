package com.prismai.llmhost.bridge

import com.prismai.llmhost.GenerationChunk
import com.prismai.llmhost.GenerationSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.withContext

private const val STREAM_BUFFER_HEADROOM = 64

private fun ClosedSendChannelException.unexpectedCloseCause(): Throwable? {
    val nested = cause ?: return null
    if (nested === this) return null
    return if (nested is ClosedSendChannelException) nested.unexpectedCloseCause() else nested
}

private fun ClosedSendChannelException.rethrowUnexpectedCause() {
    unexpectedCloseCause()?.let { throw it }
}

internal enum class StreamSendResult {
    SENT,
    CLOSED,
}

/** Bounds the stream by the native token limit while reserving terminal headroom. */
internal fun generationStreamBufferCapacity(maxTokens: Int): Int =
    maxTokens.coerceIn(GenerationSettings.MIN_MAX_TOKENS, GenerationSettings.MAX_MAX_TOKENS) +
        STREAM_BUFFER_HEADROOM

/**
 * Sends a stream chunk without dropping it when the bounded buffer is full.
 * The fast path stays non-blocking; the suspending fallback is what applies
 * backpressure to a slow consumer. A normal downstream close is reported as
 * [StreamSendResult.CLOSED], while cancellation and unexpected close causes
 * remain exceptional.
 */
internal suspend fun ProducerScope<GenerationChunk>.sendChunk(chunk: GenerationChunk): StreamSendResult {
    val immediate = try {
        trySend(chunk)
    } catch (closed: ClosedSendChannelException) {
        closed.rethrowUnexpectedCause()
        return StreamSendResult.CLOSED
    }
    if (immediate.isSuccess) return StreamSendResult.SENT
    if (immediate.isClosed) {
        val closeCause = immediate.exceptionOrNull()
        if (closeCause is ClosedSendChannelException) {
            closeCause.rethrowUnexpectedCause()
        } else if (closeCause != null) {
            throw closeCause
        }
        return StreamSendResult.CLOSED
    }

    return try {
        send(chunk)
        StreamSendResult.SENT
    } catch (closed: ClosedSendChannelException) {
        closed.rethrowUnexpectedCause()
        StreamSendResult.CLOSED
    }
}

/** Runs the native-generation cleanup required after stream cancellation. */
internal suspend fun withStreamCleanup(block: suspend () -> Unit) {
    withContext(NonCancellable) { block() }
}
