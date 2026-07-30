package com.prismai.llmhost.generation

import java.util.Locale

/**
 * Mid-stream / offline heuristics for detecting degenerate model output
 * (repetition loops, multi-script chaos). Pure Kotlin — unit-testable.
 */
data class QualityVerdict(
    val abort: Boolean,
    val reasonCode: String = "",
    val detail: String = "",
) {
    companion object {
        val Ok: QualityVerdict = QualityVerdict(abort = false)
    }
}

object QualityGuard {
    const val MIN_TOKENS_BEFORE_CHECK = 48
    /** Coding stubs often reuse short constructs; delay phrase-repeat until more tokens. */
    const val MIN_TOKENS_BEFORE_PHRASE_CODING = 96
    private const val WINDOW_CHARS = 360
    private const val MIN_WORDS_FOR_RATIO = 12
    private const val UNIQUE_WORD_RATIO_ABORT = 0.25
    private const val PHRASE_REPEAT_MIN = 6
    private const val SCRIPT_CHAOS_MIN_SCRIPTS = 3
    private const val SCRIPT_CHAOS_NON_ASCII_RATIO = 0.35

    /**
     * Evaluate streamed text. Call only when [generatedTokens] >= [MIN_TOKENS_BEFORE_CHECK]
     * for live generation (callers may enforce that).
     */
    fun evaluate(
        text: String,
        generatedTokens: Int,
        isCodingPreset: Boolean = false,
    ): QualityVerdict {
        if (text.isBlank()) return QualityVerdict.Ok
        if (generatedTokens > 0 && generatedTokens < MIN_TOKENS_BEFORE_CHECK) {
            return QualityVerdict.Ok
        }

        val window = text.takeLast(WINDOW_CHARS)
        val checkPhraseRepeat = !isCodingPreset ||
            generatedTokens <= 0 ||
            generatedTokens >= MIN_TOKENS_BEFORE_PHRASE_CODING
        repetitionLoop(window, checkPhraseRepeat = checkPhraseRepeat)?.let { return it }
        scriptChaos(window)?.let { return it }

        // Soft coding boost: chaos already handled; structure-only does not abort alone (v1).
        if (isCodingPreset && generatedTokens >= 128) {
            // reserved for future composite scoring
        }
        return QualityVerdict.Ok
    }

    /** Export-time helper shared with [com.prismai.llmhost.export.QualityDatasetFilter]. */
    fun looksDegenerate(text: String): Boolean =
        evaluate(text, generatedTokens = MIN_TOKENS_BEFORE_CHECK + 1).abort

    private fun repetitionLoop(window: String, checkPhraseRepeat: Boolean): QualityVerdict? {
        val words = window
            .lowercase()
            .split(Regex("\\s+"))
            .map { it.trim().trim(',', '.', '!', '?', '"', '\'', '“', '”', '…', ';', ':') }
            .filter { it.isNotEmpty() }
        if (words.size >= MIN_WORDS_FOR_RATIO) {
            val uniqueRatio = words.toSet().size.toDouble() / words.size
            if (uniqueRatio < UNIQUE_WORD_RATIO_ABORT) {
                return QualityVerdict(
                    abort = true,
                    reasonCode = "REPETITION_LOOP",
                    detail = "unique_word_ratio=${String.format(Locale.US, "%.2f", uniqueRatio)} words=${words.size}",
                )
            }
        }
        if (!checkPhraseRepeat) return null
        // Non-overlapping short phrases stamped repeatedly (e.g. "and so on." loops).
        for (phraseLen in 2..6) {
            if (words.size < phraseLen * PHRASE_REPEAT_MIN) continue
            val counts = HashMap<String, Int>()
            var i = 0
            while (i + phraseLen <= words.size) {
                val key = words.subList(i, i + phraseLen).joinToString(" ")
                counts[key] = (counts[key] ?: 0) + 1
                i += phraseLen
            }
            val worst = counts.maxByOrNull { it.value }
            if (worst != null && worst.value >= PHRASE_REPEAT_MIN) {
                return QualityVerdict(
                    abort = true,
                    reasonCode = "REPETITION_LOOP",
                    detail = "phrase_repeats=${worst.value} phrase=\"${worst.key.take(60)}\"",
                )
            }
        }
        return null
    }

    private fun scriptChaos(window: String): QualityVerdict? {
        val letters = window.filter { it.isLetter() }
        if (letters.length < 40) return null

        var latin = 0
        var cjk = 0
        var arabic = 0
        var cyrillic = 0
        var other = 0
        for (ch in letters) {
            when {
                ch in '\u0041'..'\u007A' || ch in '\u00C0'..'\u024F' -> latin++
                ch in '\u4E00'..'\u9FFF' || ch in '\u3040'..'\u30FF' || ch in '\uAC00'..'\uD7AF' -> cjk++
                ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' -> arabic++
                ch in '\u0400'..'\u04FF' -> cyrillic++
                else -> other++
            }
        }
        val buckets = listOf(latin, cjk, arabic, cyrillic, other).count { it >= 8 }
        val nonAsciiRatio = letters.count { it.code > 127 }.toDouble() / letters.length
        if (buckets >= SCRIPT_CHAOS_MIN_SCRIPTS && nonAsciiRatio >= SCRIPT_CHAOS_NON_ASCII_RATIO) {
            return QualityVerdict(
                abort = true,
                reasonCode = "SCRIPT_CHAOS",
                detail = "scripts=$buckets non_ascii_ratio=${String.format(Locale.US, "%.2f", nonAsciiRatio)}",
            )
        }
        return null
    }
}
