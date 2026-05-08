package com.example.llmhost

data class HuggingFaceModelEntry(
    val id: String,
    val name: String,
    val repoId: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val license: String,
    val parameters: String,
    val quantization: String,
    val notes: String,
)

sealed class ModelDownloadState {
    data object Idle : ModelDownloadState()
    data class Running(
        val entry: HuggingFaceModelEntry,
        val stage: Stage,
        val bytesDone: Long,
        val totalBytes: Long?,
    ) : ModelDownloadState() {
        enum class Stage {
            DOWNLOADING,
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
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
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
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            expectedBytes = 1_050L * 1024L * 1024L,
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
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            expectedBytes = 491L * 1024L * 1024L,
            license = "Apache-2.0",
            parameters = "0.5B",
            quantization = "Q4_K_M",
            notes = "Tiny text/code model for fast coding-focused benchmark comparisons.",
        ),
    )

    fun find(id: String): HuggingFaceModelEntry? = entries.firstOrNull { it.id == id }
}
