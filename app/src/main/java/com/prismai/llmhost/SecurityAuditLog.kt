package com.prismai.llmhost

data class SecurityEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String, // "TOOL_DENIED", "CAPABILITY_CHECK", "CONFIRMATION_SKIPPED"
    val toolName: String?,
    val detail: String,
)

class SecurityAuditLog(private val maxEvents: Int = 200) {
    private val events = mutableListOf<SecurityEvent>()

    fun record(event: SecurityEvent) {
        synchronized(events) {
            events.add(event)
            if (events.size > maxEvents) events.removeAt(0)
        }
    }

    fun recent(limit: Int = 50): List<SecurityEvent> = synchronized(events) {
        events.takeLast(limit)
    }

    fun countByType(): Map<String, Int> = synchronized(events) {
        events.groupingBy { it.eventType }.eachCount()
    }

    fun clear() { synchronized(events) { events.clear() } }
}
