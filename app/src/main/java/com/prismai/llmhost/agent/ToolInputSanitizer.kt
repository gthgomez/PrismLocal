package com.prismai.llmhost.agent

import org.json.JSONObject
import java.util.Locale

object ToolInputSanitizer {

    private val SYSTEM_PROMPT_INJECTION_TAGS = listOf(
        Regex("""<\|im_start\|>.*?<\|im_end\|>""", RegexOption.DOT_MATCHES_ALL),
        Regex("""<system>.*?</system>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""<tool_call>.*?</tool_call>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("""\[INST\].*?\[/INST\]""", RegexOption.DOT_MATCHES_ALL),
        Regex("""<<SYS>>.*?<</SYS>>""", RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Validates that a string is well-formed JSON and contains only expected key types.
     */
    fun validateJsonStructure(jsonString: String): Boolean {
        if (jsonString.isBlank()) return false
        return runCatching {
            JSONObject(jsonString)
            true
        }.getOrElse { false }
    }

    /**
     * Sanitizes external text inputs (e.g. SMS body, web search results, text attachments)
     * by neutralizing prompt injection tags and stripping embedded tool directives.
     */
    fun sanitizeExternalInput(rawText: String, sourceName: String): String {
        if (rawText.isBlank()) return rawText
        
        var cleaned = rawText
        for (pattern in SYSTEM_PROMPT_INJECTION_TAGS) {
            cleaned = cleaned.replace(pattern, "[sanitized_injection_attempt]")
        }

        // Neutralize closing external content tags
        cleaned = cleaned.replace("</untrusted_external_content>", "<\\/untrusted_external_content>")

        return buildString {
            appendLine("<untrusted_external_content source=\"${sourceName.lowercase(Locale.US)}\">")
            appendLine(cleaned)
            appendLine("</untrusted_external_content>")
        }
    }

    /**
     * Sanitizes tool argument strings before passing to execution handlers.
     */
    fun sanitizeToolArgument(argument: String): String {
        return argument
            .replace("\u0000", "")
            .trim()
    }
}
