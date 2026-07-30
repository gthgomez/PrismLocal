package com.prismai.llmhost.engine

import com.prismai.llmhost.GenerationChunk
import com.prismai.llmhost.GenerationSettings
import kotlinx.coroutines.flow.Flow

enum class EngineBackendType {
    LLAMA_CPP_CPU_VULKAN,
    MEDIAPIPE_ANDROID_NPU_DELEGATE,
    EXECUTORCH_NPU_DELEGATE,
}

data class EngineCapabilities(
    val backendType: EngineBackendType,
    val isNpuAccelerated: Boolean,
    val supportsMultimodal: Boolean,
    val estimatedTps: Float,
)

interface InferenceEngine {
    val backendType: EngineBackendType
    val capabilities: EngineCapabilities

    suspend fun loadModel(modelPath: String, settings: GenerationSettings): Boolean
    fun generate(prompt: String, settings: GenerationSettings): Flow<GenerationChunk>
    suspend fun cancelGeneration()
    suspend fun unloadModel()
    fun isModelLoaded(): Boolean
}
