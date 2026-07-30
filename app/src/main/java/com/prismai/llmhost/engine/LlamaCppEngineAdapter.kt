package com.prismai.llmhost.engine

import com.prismai.llmhost.GenerationChunk
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.bridge.NativeLlmBridge
import kotlinx.coroutines.flow.Flow

class LlamaCppEngineAdapter(
    private val bridge: NativeLlmBridge = NativeLlmBridge.create()
) : InferenceEngine {

    override val backendType: EngineBackendType = EngineBackendType.LLAMA_CPP_CPU_VULKAN

    override val capabilities: EngineCapabilities = EngineCapabilities(
        backendType = EngineBackendType.LLAMA_CPP_CPU_VULKAN,
        isNpuAccelerated = false,
        supportsMultimodal = false,
        estimatedTps = 8.5f,
    )

    private var modelLoaded = false

    override suspend fun loadModel(modelPath: String, settings: GenerationSettings): Boolean {
        modelLoaded = bridge.loadModel(modelPath, settings)
        return modelLoaded
    }

    override fun generate(prompt: String, settings: GenerationSettings): Flow<GenerationChunk> {
        return bridge.generate(prompt = prompt, settings = settings)
    }

    override suspend fun cancelGeneration() {
        bridge.cancelGeneration()
    }

    override suspend fun unloadModel() {
        bridge.unloadModel()
        modelLoaded = false
    }

    override fun isModelLoaded(): Boolean = modelLoaded
}
