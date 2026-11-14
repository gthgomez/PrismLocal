package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

enum class TranscriptRole {
    USER,
    ASSISTANT,
    TOOL,
}

data class TranscriptMessage(
    val id: Long,
    val role: TranscriptRole,
    val text: String,
    val summary: String? = null,
    val regenerationHistory: List<String> = emptyList(),
    /** Non-null for document Q&A turns, which must not be replayed into another context. */
    val documentScope: String? = null,
    /** Keep this message visible, but begin future model context after it. */
    val contextBoundaryAfter: Boolean = false,
)

/** Preserve the transcript while excluding all turns before the most recent context reset. */
internal fun List<TranscriptMessage>.sinceContextBoundary(): List<TranscriptMessage> =
    drop(indexOfLast { it.contextBoundaryAfter } + 1)
