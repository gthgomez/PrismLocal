package com.prismai.llmhost.storage

/** Targeted filter for recognizable directives. It is not a tool-authorization boundary. */
internal object DocumentInstructionFilter {
    private val instructionPatterns = listOf(
        Regex("""<\|[^|\r\n]{1,80}\|>|\[/?INST\]|<</?SYS>>|</?(?:system|assistant|developer)>""", RegexOption.IGNORE_CASE),
        Regex("""(?im)^\s*(?:system|assistant|developer)\s*:"""),
        Regex("""(?is)\b(?:ignore|disregard|forget|override|bypass)\b.{0,240}\b(?:all|previous|prior|instructions?|rules?|prompt|question|system|developer|user)\b"""),
        Regex("""(?is)\b(?:instructions?|system prompt|user's question)\b.{0,160}\b(?:say|output|respond|reply|answer)\b"""),
        Regex("""(?is)\b(?:do not|don't|never)\s+(?:mention|reveal|disclose)\s+(?:this|the)\s+(?:note|instruction|prompt)\b"""),
    )
    private val paragraphBreak = Regex("""(?:\r?\n[ \t]*){2,}""")

    /** Mask whole original paragraphs without shifting the source offsets used for citations. */
    fun mask(text: String): String {
        var start = 0
        val filtered = StringBuilder(text)
        fun maskParagraph(end: Int) {
            val paragraph = text.substring(start, end)
            if (instructionPatterns.any { it.containsMatchIn(paragraph) }) {
                for (index in start until end) filtered[index] = ' '
            }
        }
        paragraphBreak.findAll(text).forEach { separator ->
            maskParagraph(separator.range.first)
            start = separator.range.last + 1
        }
        maskParagraph(text.length)
        return filtered.toString()
    }
}
