package com.prismai.llmhost.ui

import androidx.compose.ui.graphics.Color
import com.prismai.llmhost.ChatTitles
import com.prismai.llmhost.ModelFitRating
import com.prismai.llmhost.ModelPerformanceTier
import com.prismai.llmhost.PerformancePrediction
import com.prismai.llmhost.ui.theme.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun compactModelName(modelId: String?): String {
    if (modelId.isNullOrBlank()) {
        return "No model selected"
    }
    val withoutExtension = modelId.removeSuffix(".gguf")
    val withoutQuant = withoutExtension
        .replace(Regex("[-_](?:I?Q\\d(?:_[Kk])?_[A-Za-z0-9]+|F16|BF16|Q\\d_\\d)$"), "")
        .replace(Regex("[-_](?:GGUF|gguf)$"), "")
    return withoutQuant
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .ifBlank { withoutExtension }
        .let { name ->
            if (name.length <= 38) {
                name
            } else {
                "${name.take(35).trimEnd()}..."
            }
        }
}

internal fun polishedModelName(modelId: String?): String {
    val compact = compactModelName(modelId)
    if (compact == "No model selected") {
        return compact
    }
    return compact
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            when {
                token.equals("it", ignoreCase = true) -> "IT"
                token.equals("llm", ignoreCase = true) -> "LLM"
                token.equals("gguf", ignoreCase = true) -> "GGUF"
                token.equals("cpu", ignoreCase = true) -> "CPU"
                token.equals("gpu", ignoreCase = true) -> "GPU"
                token.matches(Regex("\\d+[a-zA-Z]?")) -> token.uppercase(Locale.US)
                token.any { it.isDigit() } -> token.uppercase(Locale.US)
                token.length <= 2 -> token.uppercase(Locale.US)
                else -> token.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
                }
            }
        }
        .ifBlank { compact }
}

internal fun polishedChatTitle(title: String): String =
    title
        .replace("Benchmark - ", "Benchmark: ")
        .trim()
        .ifBlank { ChatTitles.DEFAULT_TITLE }

internal fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        "%.1f MB".format(mb)
    } else {
        "$bytes B"
    }
}

internal fun formatTokensPerSecond(value: Double): String =
    String.format(Locale.US, "%.2f", value)

internal fun formatOptionalTps(value: Double?): String =
    value?.let { "${formatTokensPerSecond(it)} tok/s" } ?: "pending"

internal fun formatOptionalMs(value: Long?): String =
    value?.let { "$it ms" } ?: "pending"

internal fun shortHash(hash: String): String =
    if (hash.length <= 18) hash else "${hash.take(10)}...${hash.takeLast(8)}"

internal fun formatChatTimestamp(updatedAt: Long): String {
    val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
    val minuteMs = 60_000L
    val hourMs = 60L * minuteMs
    val dayMs = 24L * hourMs
    return when {
        ageMs < minuteMs -> "Just now"
        ageMs < hourMs -> "${ageMs / minuteMs}m ago"
        ageMs < dayMs -> "${ageMs / hourMs}h ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.US).format(Date(updatedAt))
    }
}

internal fun predictionRange(prediction: PerformancePrediction): String =
    "${formatTokensPerSecond(prediction.minTokensPerSecond)}-${formatTokensPerSecond(prediction.maxTokensPerSecond)} tok/s"

internal fun fitLabel(rating: ModelFitRating): String = when (rating) {
    ModelFitRating.SAFE -> "Recommended"
    ModelFitRating.RISKY -> "May be slow"
    ModelFitRating.TOO_LARGE -> "Likely too large"
}

internal fun fitColor(rating: ModelFitRating): Color = when (rating) {
    ModelFitRating.SAFE -> PrismGreen
    ModelFitRating.RISKY -> PrismAmber
    ModelFitRating.TOO_LARGE -> PrismRed
}

internal fun performanceColor(tier: ModelPerformanceTier): Color = when (tier) {
    ModelPerformanceTier.UNKNOWN -> PrismSlate
    ModelPerformanceTier.NOT_RECOMMENDED -> PrismRed
    ModelPerformanceTier.VERY_SLOW -> PrismAmber
    ModelPerformanceTier.USABLE -> PrismBlue
    ModelPerformanceTier.RECOMMENDED -> PrismGreen
}
