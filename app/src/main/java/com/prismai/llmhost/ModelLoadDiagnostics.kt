package com.prismai.llmhost

data class ModelLoadDiagnostics(
    val modelId: String?,
    val state: String,
    val loadMs: Long,
    val modelBytes: Long?,
    val availableMemoryMb: Long?,
    val lowMemory: Boolean,
    val message: String?,
    val createdAt: Long = System.currentTimeMillis(),
)
