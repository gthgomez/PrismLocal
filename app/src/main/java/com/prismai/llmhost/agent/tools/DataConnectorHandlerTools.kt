package com.prismai.llmhost.agent.tools

import com.prismai.llmhost.AgentToolCall
import com.prismai.llmhost.AgentToolErrorCode
import com.prismai.llmhost.AgentToolResult
import com.prismai.llmhost.DataConnectorTools
import org.json.JSONArray
import org.json.JSONObject

/** Agent-facing tool handlers for data connectors (contacts, calendar, SMS). */
class DataConnectorHandlerTools(
    private val dataConnectorTools: DataConnectorTools,
) {
    suspend fun searchContacts(call: AgentToolCall): AgentToolResult {
        val query = call.arguments.optString("query").trim().take(100)
        if (query.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Search query is empty")
        if (!dataConnectorTools.hasPermission(android.Manifest.permission.READ_CONTACTS)) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "READ_CONTACTS permission not granted. Grant it in Settings > Apps > Prism Local > Permissions.")
        }
        return try {
            val results = dataConnectorTools.searchContacts(query)
            val jsonResults = JSONArray()
            results.forEach { contact ->
                jsonResults.put(JSONObject()
                    .put("name", contact.name)
                    .put("has_phone", contact.hasPhone)
                    .put("has_email", contact.hasEmail)
                    .put("lookup_key", contact.lookupKey))
            }
            toolSuccess(call, "${jsonResults.length()} contact(s) matching \"$query\"",
                JSONObject().put("query", query).put("untrusted_data", true)
                    .put("result_count", jsonResults.length()).put("results", jsonResults))
        } catch (e: SecurityException) {
            toolFailure(call, AgentToolErrorCode.FAILED, "Contacts access denied: ${e.message}")
        }
    }

    suspend fun getCalendarEvents(call: AgentToolCall): AgentToolResult {
        if (!dataConnectorTools.hasPermission(android.Manifest.permission.READ_CALENDAR)) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "READ_CALENDAR permission not granted. Grant it in Settings > Apps > Prism Local > Permissions.")
        }
        val days = call.arguments.optInt("days", 7).coerceIn(1, 30)
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 60L * 60L * 1000L
        return try {
            val events = dataConnectorTools.getCalendarEvents(startMillis = now, endMillis = end)
            val jsonResults = JSONArray()
            events.forEach { event ->
                jsonResults.put(JSONObject()
                    .put("title", event.title)
                    .put("start", DataConnectorTools.formatDate(event.startMillis))
                    .put("start_millis", event.startMillis)
                    .put("end", DataConnectorTools.formatDate(event.endMillis))
                    .put("end_millis", event.endMillis)
                    .put("is_all_day", event.isAllDay)
                    .put("location", event.location ?: JSONObject.NULL))
            }
            toolSuccess(call, "${jsonResults.length()} event(s) in next $days day(s)",
                JSONObject().put("days", days).put("untrusted_data", true)
                    .put("result_count", jsonResults.length()).put("results", jsonResults))
        } catch (e: SecurityException) {
            toolFailure(call, AgentToolErrorCode.FAILED, "Calendar access denied: ${e.message}")
        }
    }

    suspend fun listSmsThreads(call: AgentToolCall): AgentToolResult {
        if (!dataConnectorTools.hasPermission(android.Manifest.permission.READ_SMS)) {
            return toolFailure(call, AgentToolErrorCode.FAILED,
                "READ_SMS permission not granted. Grant it in Settings > Apps > Prism Local > Permissions.")
        }
        val limit = call.arguments.optInt("limit", 10).coerceIn(1, 20)
        return try {
            val threads = dataConnectorTools.listSmsThreads(limit)
            val jsonResults = JSONArray()
            threads.forEach { thread ->
                jsonResults.put(JSONObject()
                    .put("address", thread.address)
                    .put("snippet", thread.snippet)
                    .put("message_count", thread.messageCount)
                    .put("date", DataConnectorTools.formatDate(thread.dateMillis))
                    .put("date_millis", thread.dateMillis))
            }
            toolSuccess(call, "${jsonResults.length()} SMS thread(s)",
                JSONObject().put("untrusted_data", true)
                    .put("result_count", jsonResults.length()).put("results", jsonResults))
        } catch (e: SecurityException) {
            toolFailure(call, AgentToolErrorCode.FAILED, "SMS access denied: ${e.message}")
        }
    }
}
