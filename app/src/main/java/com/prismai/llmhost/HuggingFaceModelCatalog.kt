package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

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
    /**
     * True for entries shipped in the curated [HuggingFaceModelCatalog.entries] list, which
     * must never be imported without a verified SHA-256. False for user-supplied/dynamic
     * imports, which may proceed as explicitly unverified when no published hash is
     * available.
     */
    val curated: Boolean = true,
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
    data class Success(
        val modelId: String,
        val entryName: String,
        val integrityVerified: Boolean = true,
    ) : ModelDownloadState()
    data class Failure(val entryName: String, val message: String) : ModelDownloadState()
    data object Cancelled : ModelDownloadState()
}

object HuggingFaceModelCatalog {
    val entries: List<HuggingFaceModelEntry> = listOf(
        // SHA-256 values retrieved from the HF API (`lfs.sha256`) and confirmed against the raw LFS
        // pointer on 2026-09-14. Curated entries without a hash (gated repos or 404 file names as of
        // that date) fail closed at download time rather than importing unverified.
        HuggingFaceModelEntry(
            id = "qwen25_05b_q4km",
            name = "Qwen2.5 0.5B Instruct",
            repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            expectedBytes = 491L * 1024L * 1024L,
            expectedSha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
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
            expectedSha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
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
            expectedSha256 = "1d9614638d18024d0fbb36575a15f1302a3adf044df10345688ec4f6e1c4ff32",
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
            expectedSha256 = "75c4346ef9e855ed630f80078a2430cf63aaca599e340360998a313070fcdc47",
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
            expectedSha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            license = "Llama 3.2 Community",
            parameters = "1B",
            quantization = "Q4_K_M",
            notes = "Fast demo instruct baseline; not recommended for coding benchmarks. License is open-weight, not Apache/MIT style.",
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
            notes = "Fast on-device demo; not recommended for coding benchmarks (prefer 3B+). License requires review before redistribution.",
        ),
        // --- Expanded catalog: more sizes, architectures, and quants ---
        HuggingFaceModelEntry(
            id = "phi3_mini_38b_q4km",
            name = "Phi-3-mini 3.8B Instruct",
            repoId = "bartowski/Phi-3-mini-4k-instruct-GGUF",
            fileName = "Phi-3-mini-4k-instruct-Q4_K_M.gguf",
            expectedBytes = 2_300L * 1024L * 1024L,
            expectedSha256 = "28a89b4ddb5766355f24e362ae4078b4c35b9ca9568df5fc9e6d9aeee4dee834",
            license = "MIT",
            parameters = "3.8B",
            quantization = "Q4_K_M",
            notes = "Microsoft Phi-3-mini; strong reasoning for its size, MIT license.",
        ),
        HuggingFaceModelEntry(
            id = "phi4_mini_38b_q4km",
            name = "Phi-4-mini 3.8B Instruct",
            repoId = "bartowski/Phi-4-mini-instruct-GGUF",
            fileName = "Phi-4-mini-instruct-Q4_K_M.gguf",
            expectedBytes = 2_300L * 1024L * 1024L,
            license = "MIT",
            parameters = "3.8B",
            quantization = "Q4_K_M",
            notes = "Microsoft Phi-4-mini; improved reasoning over Phi-3, MIT license.",
        ),
        HuggingFaceModelEntry(
            id = "llama32_3b_q4km",
            name = "Llama 3.2 3B Instruct",
            repoId = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            expectedBytes = 2_020L * 1024L * 1024L,
            expectedSha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            license = "Llama 3.2 Community",
            parameters = "3B",
            quantization = "Q4_K_M",
            notes = "Meta Llama 3.2 3B; strong general-purpose instruct model with long-context support.",
        ),
        HuggingFaceModelEntry(
            id = "gemma3_4b_q4km",
            name = "Gemma 3 4B IT",
            repoId = "ggml-org/gemma-3-4b-it-GGUF",
            fileName = "gemma-3-4b-it-Q4_K_M.gguf",
            expectedBytes = 2_620L * 1024L * 1024L,
            expectedSha256 = "882e8d2db44dc554fb0ea5077cb7e4bc49e7342a1f0da57901c0802ea21a0863",
            license = "Gemma terms",
            parameters = "4B",
            quantization = "Q4_K_M",
            notes = "Google Gemma 3 4B; strong for its size, good multilingual support.",
        ),
        HuggingFaceModelEntry(
            id = "qwen25_3b_q4km",
            name = "Qwen2.5 3B Instruct",
            repoId = "bartowski/Qwen2.5-3B-Instruct-GGUF",
            fileName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            expectedBytes = 1_940L * 1024L * 1024L,
            expectedSha256 = "9c9f56a391a3abbd5b89d0245bf6106081bcc3173119d4229235dd9d23253f94",
            license = "Apache-2.0",
            parameters = "3B",
            quantization = "Q4_K_M",
            notes = "Alibaba Qwen2.5 3B; excellent coding and reasoning for its size, Apache-2.0.",
        ),
        HuggingFaceModelEntry(
            id = "qwen25_7b_iq3xxs",
            name = "Qwen2.5 7B Instruct",
            repoId = "bartowski/Qwen2.5-7B-Instruct-GGUF",
            fileName = "Qwen2.5-7B-Instruct-IQ3_XXS.gguf",
            expectedBytes = 3_240L * 1024L * 1024L,
            license = "Apache-2.0",
            parameters = "7B",
            quantization = "IQ3_XXS",
            notes = "Qwen2.5 7B with aggressive IQ3_XXS quant; fits flagship phones with >8 GiB RAM.",
        ),
        HuggingFaceModelEntry(
            id = "deepseek_r1_distill_qwen_15b_q4km",
            name = "DeepSeek-R1-Distill-Qwen 1.5B",
            repoId = "bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            expectedBytes = 1_060L * 1024L * 1024L,
            expectedSha256 = "1741e5b2d062b07acf048bf0d2c514dadf2a48f94e2b4aa0cfe069af3838ee2f",
            license = "MIT",
            parameters = "1.5B",
            quantization = "Q4_K_M",
            notes = "DeepSeek-R1 distilled reasoning model; chain-of-thought reasoning in a small package.",
        ),
        HuggingFaceModelEntry(
            id = "granite31_2b_q4km",
            name = "Granite 3.1 2B Instruct",
            repoId = "bartowski/granite-3.1-2b-instruct-GGUF",
            fileName = "granite-3.1-2b-instruct-Q4_K_M.gguf",
            expectedBytes = 1_490L * 1024L * 1024L,
            expectedSha256 = "774269c82fde2720ea18dcf457fb5bd028fe096139a0735f4ad59c0a270cfc9c",
            license = "Apache-2.0",
            parameters = "2B",
            quantization = "Q4_K_M",
            notes = "IBM Granite 3.1 2B; solid enterprise-friendly model, Apache-2.0.",
        ),
        HuggingFaceModelEntry(
            id = "granite31_3b_q4km",
            name = "Granite 3.1 3B Instruct",
            repoId = "bartowski/granite-3.1-3b-a800m-instruct-GGUF",
            fileName = "granite-3.1-3b-a800m-instruct-Q4_K_M.gguf",
            expectedBytes = 2_010L * 1024L * 1024L,
            expectedSha256 = "48e0edcd578fd4462f26127f04c651d0e650741110185297741089aea01a82b3",
            license = "Apache-2.0",
            parameters = "3B",
            quantization = "Q4_K_M",
            notes = "IBM Granite 3.1 3B MoE; mixture-of-experts for efficient inference.",
        ),
        HuggingFaceModelEntry(
            id = "ministral_3b_q4km",
            name = "Ministral 3B Instruct",
            repoId = "bartowski/Ministral-3B-instruct-GGUF",
            fileName = "Ministral-3B-instruct-Q4_K_M.gguf",
            expectedBytes = 2_050L * 1024L * 1024L,
            license = "Mistral Research License",
            parameters = "3B",
            quantization = "Q4_K_M",
            notes = "Mistral Ministral 3B; efficient edge-focused model from Mistral AI.",
        ),
        HuggingFaceModelEntry(
            id = "tinyllama_11b_q4km",
            name = "TinyLlama 1.1B Chat",
            repoId = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
            fileName = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            expectedBytes = 700L * 1024L * 1024L,
            expectedSha256 = "9fecc3b3cd76bba89d504f29b616eedf7da85b96540e490ca5824d3f7d2776a0",
            license = "Apache-2.0",
            parameters = "1.1B",
            quantization = "Q4_K_M",
            notes = "TinyLlama 1.1B; very fast on weak hardware for demos. Not recommended for coding benchmarks.",
        ),
        HuggingFaceModelEntry(
            id = "stablelm_zephyr_3b_q4km",
            name = "StableLM Zephyr 3B",
            repoId = "bartowski/stablelm-zephyr-3b-GGUF",
            fileName = "stablelm-zephyr-3b-Q4_K_M.gguf",
            expectedBytes = 1_870L * 1024L * 1024L,
            license = "CC-BY-NC-SA-4.0",
            parameters = "3B",
            quantization = "Q4_K_M",
            notes = "Stability AI StableLM Zephyr 3B; tuned for helpfulness, non-commercial license.",
        ),
        HuggingFaceModelEntry(
            id = "qwen25_05b_iq2xxs",
            name = "Qwen2.5 0.5B Instruct (compact)",
            repoId = "bartowski/Qwen2.5-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-0.5b-instruct-IQ2_XXS.gguf",
            expectedBytes = 189L * 1024L * 1024L,
            license = "Apache-2.0",
            parameters = "0.5B",
            quantization = "IQ2_XXS",
            notes = "Ultra-compact Qwen2.5 0.5B; under 200 MiB, fits any device.",
        ),
        HuggingFaceModelEntry(
            id = "qwen25_15b_iq3xxs",
            name = "Qwen2.5 1.5B Instruct (compact)",
            repoId = "bartowski/Qwen2.5-1.5B-Instruct-GGUF",
            fileName = "qwen2.5-1.5b-instruct-IQ3_XXS.gguf",
            expectedBytes = 550L * 1024L * 1024L,
            license = "Apache-2.0",
            parameters = "1.5B",
            quantization = "IQ3_XXS",
            notes = "Compact Qwen2.5 1.5B; ~550 MiB with IQ3_XXS, good quality/size tradeoff.",
        ),
        HuggingFaceModelEntry(
            id = "smollm2_17b_q4km",
            name = "SmolLM2 1.7B Instruct",
            repoId = "QuantFactory/SmolLM2-1.7B-Instruct-GGUF",
            fileName = "SmolLM2-1.7B-Instruct.Q4_K_M.gguf",
            expectedBytes = 1_110L * 1024L * 1024L,
            expectedSha256 = "bc8986d129483f44768b5fcc2bd2f148d6af000282cdb9ec7a92122226fa7921",
            license = "Apache-2.0",
            parameters = "1.7B",
            quantization = "Q4_K_M",
            notes = "HuggingFace SmolLM2 1.7B; strong Apache-2.0 model between 360M and 1.5B sizes.",
        ),
        HuggingFaceModelEntry(
            id = "bonsai_27b_q1_0",
            name = "Bonsai 27B (1-bit Q1_0)",
            repoId = "prism-ml/Bonsai-27B-gguf",
            fileName = "Bonsai-27B-Q1_0.gguf",
            // HF API blobs=true (2026-07-19): exact LFS size + sha256 for Bonsai-27B-Q1_0.gguf
            expectedBytes = 3_803_452_480L,
            expectedSha256 = "17ef842e47450caeb8eaa3ebfbbab5d2f2278b62b79be107985fb69a2f819aa0",
            license = "Apache-2.0",
            parameters = "27B",
            quantization = "Q1_0 (1.125 bpw)",
            notes = "Flagship devices only (prefer 12–16 GB total RAM; need ~6+ GiB free after unload). Peak memory exceeds file size (~5 GiB class at short context). Text-only in this build. Device load not yet smoke-verified on this project.",
        ),
        // --- Abliterated (uncensored) test entries: same quantization (Q4_K_M) as the aligned
        //     llama32_1b_q4km / qwen25_15b_q4km / llama32_3b_q4km entries for A/B refusal testing.
        //     File sizes are NOT matched (upstream GGUF conversions differ); measured size deltas
        //     vs the aligned entries are noted per entry below. ---
        HuggingFaceModelEntry(
            id = "llama32_1b_abliterated_q4km",
            name = "Llama 3.2 1B Instruct (abliterated)",
            repoId = "mradermacher/Llama-3.2-1B-Instruct-abliterated-GGUF",
            fileName = "Llama-3.2-1B-Instruct-abliterated.Q4_K_M.gguf",
            expectedBytes = 955_445_792L,
            expectedSha256 = "68336c25367576f49b29f01ee9034658b51bbded6a7f215e934d997de96301e0",
            license = "Llama 3.2 Community",
            parameters = "1B",
            quantization = "Q4_K_M",
            notes = "Uncensored (abliterated) Llama 3.2 1B. Same quantization (Q4_K_M) as llama32_1b_q4km; file is ~12.8% larger (911 MiB vs 808 MiB), so not size-matched. For abliteration A/B testing. Text-only.",
        ),
        HuggingFaceModelEntry(
            id = "qwen25_15b_abliterated_q4km",
            name = "Qwen2.5 1.5B Instruct (abliterated)",
            repoId = "mradermacher/Qwen2.5-1.5B-Instruct-abliterated-GGUF",
            fileName = "Qwen2.5-1.5B-Instruct-abliterated.Q4_K_M.gguf",
            expectedBytes = 986_049_088L,
            expectedSha256 = "59aa9f44bde5349dbe292d7024d197db605f422b8baf65f3246a59abbde4e8e9",
            license = "Apache-2.0",
            parameters = "1.5B",
            quantization = "Q4_K_M",
            notes = "Uncensored (abliterated) Qwen2.5 1.5B. Same quantization (Q4_K_M) as qwen25_15b_q4km; file is ~16.0% smaller (940 MiB vs 1120 MiB), so not size-matched. Exercises the ChatML template branch.",
        ),
        HuggingFaceModelEntry(
            id = "llama32_3b_abliterated_q4km",
            name = "Llama 3.2 3B Instruct (abliterated, i1)",
            repoId = "mradermacher/Llama-3.2-3B-Instruct-abliterated-i1-GGUF",
            fileName = "Llama-3.2-3B-Instruct-abliterated.i1-Q4_K_M.gguf",
            expectedBytes = 2_241_004_736L,
            expectedSha256 = "bb4c67ad696baa379bf572ebf753d48591fdc26035f4d19c5e60a4d570785b0c",
            license = "Llama 3.2 Community",
            parameters = "3B",
            quantization = "Q4_K_M",
            notes = "Uncensored (abliterated) Llama 3.2 3B, imatrix (i1) quant. Same quantization (Q4_K_M) as llama32_3b_q4km; file is ~5.8% larger (2137 MiB vs 2020 MiB), so not size-matched. Larger tier for quality vs the 1B on flagship phones. Text-only.",
        ),
    )

    private val customEntries = mutableMapOf<String, HuggingFaceModelEntry>()

    fun find(id: String): HuggingFaceModelEntry? = entries.firstOrNull { it.id == id } ?: customEntries[id]

    fun createCustomEntry(repoId: String, fileName: String): HuggingFaceModelEntry {
        val cleanRepo = repoId.trim().trim('/')
        val cleanFile = fileName.trim().removePrefix("/")
        val id = "custom_" + (cleanRepo + "_" + cleanFile).replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        val entry = HuggingFaceModelEntry(
            id = id,
            name = cleanFile.removeSuffix(".gguf"),
            repoId = cleanRepo,
            fileName = cleanFile,
            expectedBytes = -1L,
            license = "Community / Unspecified",
            parameters = "Custom",
            quantization = "Auto",
            notes = "User-submitted custom Hugging Face GGUF repository",
            curated = false,
        )
        customEntries[id] = entry
        return entry
    }
}
