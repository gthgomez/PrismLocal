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
    /**
     * PIR-06: observed values are nullable and default to unknown. Only a state
     * that actually read them back from the native engine (e.g. "loaded"/
     * "current") populates them; loading/failed/rejected must not present a
     * default "CPU"/0 as if it were observed.
     */
    val gpuLayersOffloaded: Int? = null,
    val backendName: String? = null,
    val isKleidiAiEnabled: Boolean? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
