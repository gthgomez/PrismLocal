package com.prismai.llmhost.model

import com.prismai.llmhost.HuggingFaceModelCatalog

/**
 * Soft product guidance for model size vs task. Does not hard-block runs.
 */
object ModelTierHints {
    /** Prefer models at or above this scale for structured coding benchmarks. */
    const val CODING_MIN_PARAMS_B = 3.0

    /**
     * Best-effort parse of parameter scale from a model id / filename
     * (e.g. "gemma-3-1b-it", "Qwen2.5-3B", "8X0.6B", "360M").
     * MoE `NxM` uses active expert size [M], not total experts × size.
     * Falls back to curated catalog [parameters] when the id has no size token.
     */
    fun estimateParamsBillions(modelId: String?): Double? {
        if (modelId.isNullOrBlank()) return null
        // Prefer curated catalog parameters for exact id/fileName hits so compact
        // catalog ids like "qwen25_15b_q4km" are not misread as 15B.
        catalogParamsBillions(modelId, exactOnly = true)?.let { return it }

        val lower = modelId.lowercase()

        // MoE style: 8x0.6B → active expert size (0.6), not sum of experts
        Regex("""(\d+(?:\.\d+)?)\s*[x×]\s*(\d+(?:\.\d+)?)\s*b""").find(lower)?.let { match ->
            return match.groupValues[2].toDoubleOrNull()
        }

        Regex("""(\d+(?:\.\d+)?)\s*b""").find(lower)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }

        Regex("""(\d+(?:\.\d+)?)\s*m\b""").find(lower)?.let { match ->
            val millions = match.groupValues[1].toDoubleOrNull() ?: return@let
            return millions / 1000.0
        }

        return catalogParamsBillions(modelId, exactOnly = false)
    }

    fun isTinyForCoding(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        val lower = modelId.lowercase()
        if ("tinyllama" in lower || "tiny-" in lower) return true
        val params = estimateParamsBillions(modelId) ?: return false
        return params < CODING_MIN_PARAMS_B
    }

    fun codingBenchmarkWarning(modelId: String?): String? {
        if (!isTinyForCoding(modelId)) return null
        val params = estimateParamsBillions(modelId)
        val scale = params?.let { String.format(java.util.Locale.US, "~%.1fB", it) } ?: "small"
        return "Coding benchmarks are unreliable on $scale models (e.g. 1B). Prefer ~3B+ instruct/coder GGUFs for quality runs."
    }

    private fun catalogParamsBillions(modelId: String, exactOnly: Boolean): Double? {
        val lower = modelId.lowercase()
        val entry = HuggingFaceModelCatalog.entries.firstOrNull { entry ->
            entry.id.equals(modelId, ignoreCase = true) ||
                entry.fileName.equals(modelId, ignoreCase = true) ||
                entry.name.equals(modelId, ignoreCase = true) ||
                (
                    !exactOnly &&
                        lower.contains(entry.fileName.lowercase().removeSuffix(".gguf"))
                    )
        } ?: return null
        // Re-parse catalog parameters string (e.g. "1.5B", "360M") without recursive catalog lookup.
        val params = entry.parameters.lowercase().trim()
        Regex("""(\d+(?:\.\d+)?)\s*b""").find(params)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        Regex("""(\d+(?:\.\d+)?)\s*m\b""").find(params)?.let { match ->
            val millions = match.groupValues[1].toDoubleOrNull() ?: return@let
            return millions / 1000.0
        }
        return null
    }
}
