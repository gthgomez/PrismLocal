package com.example.llmhost

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
    val modelBytes: Long?,
    val modelSha256Prefix: String?,
    val availableMemoryMb: Long?,
    val modelLoadMs: Long?,
    val terminalReason: String,
)
