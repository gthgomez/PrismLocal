package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import java.io.File

data class SecurityEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String, // "TOOL_DENIED", "CAPABILITY_CHECK", "CONFIRMATION_SKIPPED"
    val toolName: String?,
    val detail: String,
)

/**
 * Bounded security audit trail. Keeps the last [maxEvents] events in memory ([recent],
 * [countByType]) and, once [init] has been given the app files directory, appends every
 * event as one JSON line {"ts":<epochMillis>,"eventType":...,"toolName":...,"detail":...}
 * to <filesDir>/security_audit.jsonl.
 *
 * Persistence policy: append-only JSONL; when the file exceeds ~512 KB it is truncated
 * to empty before the next append (oldest history dropped, no rotation rename).
 * [clear] resets only the in-memory buffer. All entry points are failure-isolated:
 * calls before [init] or after an I/O error still update memory and never throw.
 */
class SecurityAuditLog(private val maxEvents: Int = 200) {
    private val events = mutableListOf<SecurityEvent>()
    private var auditFile: File? = null

    /** Idempotent: the first non-null directory wins; null keeps memory-only mode. */
    fun init(filesDir: File?) {
        if (filesDir == null || auditFile != null) return
        runCatching {
            synchronized(events) {
                if (auditFile == null) {
                    filesDir.mkdirs()
                    auditFile = File(filesDir, AUDIT_FILE_NAME)
                }
            }
        }
    }

    fun record(eventType: String, toolName: String, detail: String) {
        record(SecurityEvent(eventType = eventType, toolName = toolName, detail = detail))
    }

    fun record(event: SecurityEvent) {
        runCatching {
            synchronized(events) {
                events.add(event)
                if (events.size > maxEvents) events.removeAt(0)
                auditFile?.let { persistLocked(it, event) }
            }
        }
    }

    fun recent(limit: Int = 50): List<SecurityEvent> = synchronized(events) {
        events.takeLast(limit)
    }

    fun countByType(): Map<String, Int> = synchronized(events) {
        events.groupingBy { it.eventType }.eachCount()
    }

    fun clear() { synchronized(events) { events.clear() } }

    // Called under the buffer monitor so file order always matches memory order.
    private fun persistLocked(file: File, event: SecurityEvent) {
        runCatching {
            if (file.length() > MAX_LOG_BYTES) file.writeText("")
            file.appendText(jsonLine(event) + "\n")
        }
    }

    private fun jsonLine(event: SecurityEvent): String =
        StringBuilder(160)
            .append("{\"ts\":").append(event.timestamp)
            .append(",\"eventType\":\"").append(jsonEscape(event.eventType))
            .append("\",\"toolName\":\"").append(jsonEscape(event.toolName.orEmpty()))
            .append("\",\"detail\":\"").append(jsonEscape(event.detail))
            .append("\"}")
            .toString()

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        value.forEach { c ->
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.toString()
    }

    companion object {
        private const val AUDIT_FILE_NAME = "security_audit.jsonl"
        private const val MAX_LOG_BYTES = 512L * 1024L
    }
}
