package com.example.llmhost

data class HuggingFaceModelEntry(
    val id: String,
    val name: String,
    val repoId: String,
    val fileName: String,
    val expectedBytes: Long,
    val expectedSha256: String? = null,
    val license: String,
    val parameters: String,
    val quantization: String,
    val notes: String,
) {
    val revision: String = "main"
    val downloadUrl: String
        get() = "https://huggingface.co/$repoId/resolve/$revision/$fileName"
    val apiUrl: String
        get() = "https://huggingface.co/api/models/$repoId?blobs=true"
    val rawPointerUrl: String
        get() = "https://huggingface.co/$repoId/raw/$revision/$fileName"
}

sealed class ModelDownloadState {
    data object Idle : ModelDownloadState()
    data class Running(
        val entry: HuggingFaceModelEntry,
        val stage: Stage,
        val bytesDone: Long,
        val totalBytes: Long?,
        val message: String? = null,
    ) : ModelDownloadState() {
        enum class Stage {
            QUEUED,
            VERIFYING_METADATA,
            DOWNLOADING,
            VERIFYING_FILE,
            IMPORTING,
        }
    }
    data class Success(val modelId: String, val entryName: String) : ModelDownloadState()
    data class Failure(val entryName: String, val message: String) : ModelDownloadState()
    data object Cancelled : ModelDownloadState()
}

object HuggingFaceModelCatalog {
    val entries: List<HuggingFaceModelEntry> = listOf(
        HuggingFaceModelEntry(
            id = "qwen25_05b_q4km",
            name = "Qwen2.5 0.5B Instruct",
            repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            expectedBytes = 491L * 1024L * 1024L,
            license = "Apache-2.0",
            parameters = "0.5B",
            quantization = "Q4_K_M",
            notes = "Small text/chat model; best first candidate for phone CPU testing.",
        ),
        HuggingFaceModelEntry(
            id = "qwen25_15b_q4km",
            name = "Qwen2.5 1.5B Instruct",
            repoId = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            expectedBytes = 1_120L * 1024L * 1024L,
            license = "Apache-2.0",
            parameters = "1.5B",
            quantization = "Q4_K_M",
            notes = "Better quality than 0.5B while still much smaller than 4B VL models.",
        ),
        HuggingFaceModelEntry(
            id = "qwen25_coder_05b_q4km",
            name = "Qwen2.5 Coder 0.5B",
            repoId = "Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            expectedBytes = 491L * 1024L * 1024L,
            license = "Apache-2.0",
            parameters = "0.5B",
            quantization = "Q4_K_M",
            notes = "Tiny text/code model for fast coding-focused benchmark comparisons.",
        ),
        HuggingFaceModelEntry(
            id = "smollm2_360m_q4km",
            name = "SmolLM2 360M Instruct",
            repoId = "QuantFactory/SmolLM2-360M-Instruct-GGUF",
            fileName = "SmolLM2-360M-Instruct.Q4_K_M.gguf",
            expectedBytes = 271L * 1024L * 1024L,
            license = "Apache-2.0",
            parameters = "360M",
            quantization = "Q4_K_M",
            notes = "Very small Apache-licensed chat model; good for quick sanity and latency baselines.",
        ),
        HuggingFaceModelEntry(
            id = "lfm2_350m_q4km",
            name = "LFM2 350M",
            repoId = "LiquidAI/LFM2-350M-GGUF",
            fileName = "LFM2-350M-Q4_K_M.gguf",
            expectedBytes = 229L * 1024L * 1024L,
            expectedSha256 = "a4d000c7064bd3b2e42c6845836286a899a4e79cf1791da1a6797b58d575957d",
            license = "LFM1.0",
            parameters = "350M",
            quantization = "Q4_K_M",
            notes = "Tiny edge-focused model; useful to test whether the app is hardware-bound or model-bound.",
        ),
        HuggingFaceModelEntry(
            id = "llama32_1b_q4km",
            name = "Llama 3.2 1B Instruct",
            repoId = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            expectedBytes = 808L * 1024L * 1024L,
            license = "Llama 3.2 Community",
            parameters = "1B",
            quantization = "Q4_K_M",
            notes = "Popular small instruct baseline; license is open-weight, not Apache/MIT style.",
        ),
        HuggingFaceModelEntry(
            id = "gemma3_1b_q4km",
            name = "Gemma 3 1B IT",
            repoId = "ggml-org/gemma-3-1b-it-GGUF",
            fileName = "gemma-3-1b-it-Q4_K_M.gguf",
            expectedBytes = 806_058_240L,
            expectedSha256 = "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",
            license = "Gemma terms",
            parameters = "1B",
            quantization = "Q4_K_M",
            notes = "Useful Gemma-family baseline; license requires review before redistribution.",
        ),
    )

    fun find(id: String): HuggingFaceModelEntry? = entries.firstOrNull { it.id == id }
}
