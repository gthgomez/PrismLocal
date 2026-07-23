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
)
