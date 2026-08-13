package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

data class GenerationChunk(
    val text: String,
    val tokenCount: Int,
    val generationId: Int,
    val isTerminal: Boolean,
    val terminalReason: String = "NONE",
    val promptTokens: Int = 0,
    val ttftMs: Long = 0L,
    val tokensPerSec: Float = 0.0f,
    val activeThreads: Int = 0,
    val errorCode: Int = 0,
)
