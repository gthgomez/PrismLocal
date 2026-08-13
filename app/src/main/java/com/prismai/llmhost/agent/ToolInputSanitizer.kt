package com.prismai.llmhost.agent

import org.json.JSONObject
import java.util.Locale
import com.prismai.llmhost.tools.AgentToolResult

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

    /**
     * Sanitizes an AgentToolResult (both summary and recursively all String values in details JSON)
     * before it is budgeted, formatted for the model, or appended to transcript history.
     */
    fun sanitizeResult(result: AgentToolResult): AgentToolResult {
        val sanitizedSummary = sanitizeExternalInput(result.summary, "tool:${result.call.name}")
        val sanitizedDetails = sanitizeJsonRecursive(result.details, "tool:${result.call.name}")
        return result.copy(summary = sanitizedSummary, details = sanitizedDetails)
    }

    private fun sanitizeJsonRecursive(obj: JSONObject, sourceName: String): JSONObject {
        val result = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = obj.opt(key)) {
                is String -> result.put(key, sanitizeExternalInput(value, sourceName))
                is JSONObject -> result.put(key, sanitizeJsonRecursive(value, sourceName))
                is org.json.JSONArray -> result.put(key, sanitizeJsonArrayRecursive(value, sourceName))
                else -> result.put(key, value)
            }
        }
        return result
    }

    private fun sanitizeJsonArrayRecursive(arr: org.json.JSONArray, sourceName: String): org.json.JSONArray {
        val result = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            when (val value = arr.opt(i)) {
                is String -> result.put(sanitizeExternalInput(value, sourceName))
                is JSONObject -> result.put(sanitizeJsonRecursive(value, sourceName))
                is org.json.JSONArray -> result.put(sanitizeJsonArrayRecursive(value, sourceName))
                else -> result.put(value)
            }
        }
        return result
    }
}
