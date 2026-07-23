package com.prismai.llmhost.agent.tools
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

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
