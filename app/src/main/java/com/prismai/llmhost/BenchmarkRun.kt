package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

data class BenchmarkRun(
    val id: String,
    val createdAt: Long,
    val modelId: String?,
    val source: String,
    val presetId: String?,
    val presetName: String?,
    val promptChars: Int,
    val outputChars: Int,
    val promptEvalMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val generatedTokens: Int,
    val tokensPerSecond: Double,
    val maxTokens: Int,
    val threadCount: Int,
    val contextLength: Int,
    val batchSize: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val repeatPenalty: Float,
    val gpuLayers: Int,
    val runtimeBackend: String,
    val modelBytes: Long?,
    val modelSha256Prefix: String?,
    val availableMemoryMb: Long?,
    val modelLoadMs: Long?,
    val terminalReason: String,
)
