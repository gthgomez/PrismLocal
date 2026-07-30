package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

data class GenerationPerformance(
    val promptEvalMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val generatedTokens: Int,
    val promptTokens: Int = 0,
    val tokensPerSecond: Double,
    val settings: GenerationSettings,
    val terminalReason: String? = null,
    val ttftMs: Long = 0L,
    val activeThreads: Int = 0,
) {
    val isComplete: Boolean = terminalReason != null
    // TODO: wire up promptTokens from native prompt_eval_done log line
}
