package com.example.llmhost.agent.tools

import com.example.llmhost.AgentToolCall
import com.example.llmhost.AgentToolErrorCode
import com.example.llmhost.AgentToolResult
import org.json.JSONObject

/** Shared factory helpers for tool handler results. */

/** Truncates a string for agent-facing display, preserving word boundaries where possible. */
fun String.compactForAgent(maxLength: Int): String {
    val compact = replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= maxLength) compact else "${compact.take(maxLength - 3).trimEnd()}..."
}

fun toolFailure(
    call: AgentToolCall,
    code: AgentToolErrorCode,
    summary: String,
    details: JSONObject = JSONObject(),
): AgentToolResult = AgentToolResult(call, success = false, summary = summary, details = details, errorCode = code)

fun toolSuccess(
    call: AgentToolCall,
    summary: String,
    details: JSONObject = JSONObject(),
): AgentToolResult = AgentToolResult(call, success = true, summary = summary, details = details)
