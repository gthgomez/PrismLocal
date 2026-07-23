package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

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
