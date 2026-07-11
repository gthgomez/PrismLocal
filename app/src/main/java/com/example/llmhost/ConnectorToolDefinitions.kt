package com.example.llmhost

/**
 * Tool definitions for read-only data connectors (contacts, calendar, SMS).
 * ALL tools are CONFIRM-gated — the model must request and user must approve
 * before any personal data is accessed.
 */
object ConnectorToolDefinitions {

    val SEARCH_CONTACTS = AgentToolDefinition(
        name = "search_contacts",
        description = "Search the user's contacts by name. Requires user confirmation before each search. Results are read-only and marked untrusted.",
        risk = AgentToolRisk.CONFIRM,
        argumentSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Name or search query"}},"required":["query"]}""",
        requiredArguments = setOf("query"),
        maxStringLengths = mapOf("query" to 100),
        returnContract = """{"untrusted_data":true,"results":[{"name":"string","has_phone":true,"has_email":false,"lookup_key":"string"}]}""",
    )

    val GET_CALENDAR_EVENTS = AgentToolDefinition(
        name = "get_calendar_events",
        description = "Get upcoming calendar events. Requires user confirmation. Results are read-only.",
        risk = AgentToolRisk.CONFIRM,
        argumentSchema = """{"type":"object","properties":{"days":{"type":"integer","description":"Number of days to look ahead (default 7, max 30)"}},"required":[]}""",
        intRanges = mapOf("days" to AgentToolIntRange(1, 30)),
        returnContract = """{"untrusted_data":true,"results":[{"title":"string","start":"string","end":"string","is_all_day":false,"location":"string|null"}]}""",
    )

    val LIST_SMS_THREADS = AgentToolDefinition(
        name = "list_sms_threads",
        description = "List recent SMS conversation threads (metadata only — address and snippet, not full messages). Requires user confirmation.",
        risk = AgentToolRisk.CONFIRM,
        argumentSchema = """{"type":"object","properties":{"limit":{"type":"integer","description":"Max threads to return (default 10, max 20)"}},"required":[]}""",
        intRanges = mapOf("limit" to AgentToolIntRange(1, 20)),
        returnContract = """{"untrusted_data":true,"results":[{"address":"string","snippet":"string","message_count":0,"date":"string"}]}""",
    )

    val ALL: List<AgentToolDefinition> = listOf(
        SEARCH_CONTACTS,
        GET_CALENDAR_EVENTS,
        LIST_SMS_THREADS,
    )

    /** Names of the data connector tools, for quick lookup. */
    val ALL_NAMES: Set<String> = ALL.map { it.name }.toSet()
}
