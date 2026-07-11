package com.example.llmhost

/**
 * Tool definitions for Phase 3 Offline Knowledge Pack with Grokipedia integration.
 *
 * These definitions follow the same pattern as the existing [AgentToolRegistry] definitions
 * in [AgentTools.kt] and should be appended to the `AgentToolRegistry.definitions` list.
 *
 * All Grokipedia-sourced content is marked with:
 *   - `"source": "grokipedia"`
 *   - `"untrusted_data": true`
 */
object KnowledgePackToolDefinitions {

    /**
     * Search the local Grokipedia knowledge base for factual information.
     *
     * Queries the on-device [VectorStore] for chunks from previously downloaded
     * knowledge packs. Returns relevant text chunks with similarity scores.
     */
    val SEARCH_KNOWLEDGE = AgentToolDefinition(
        name = "search_knowledge",
        description = "Search the local Grokipedia knowledge base for factual information. Returns relevant article chunks with scores. Use this for fact-checking and general knowledge questions.",
        risk = AgentToolRisk.SAFE,
        argumentSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Search query"},"top_k":{"type":"integer","description":"Max results (1-10, default 5)"}},"required":["query"]}""",
        requiredArguments = setOf("query"),
        intRanges = mapOf("top_k" to AgentToolIntRange(1, 10)),
        maxStringLengths = mapOf("query" to 300),
        returnContract = """{"results":[{"title":"string","text":"chunk","score":0.85,"slug":"string"}],"source":"grokipedia","untrusted_data":true}""",
    )

    /**
     * Fetch a specific Grokipedia article by slug and index it for future searches.
     *
     * Useful for deep-diving into a specific topic that may not be covered by
     * existing downloaded knowledge packs.
     */
    val FETCH_ARTICLE = AgentToolDefinition(
        name = "fetch_grokipedia_article",
        description = "Fetch a specific Grokipedia article by slug and index it for future searches. Useful for deep-diving into a topic.",
        risk = AgentToolRisk.SAFE,
        argumentSchema = """{"type":"object","properties":{"slug":{"type":"string","description":"Article slug (e.g. 'artificial-intelligence')"}},"required":["slug"]}""",
        requiredArguments = setOf("slug"),
        maxStringLengths = mapOf("slug" to 200),
        returnContract = """{"indexed":true,"slug":"string","chunks":5,"title":"string"}""",
    )

    /**
     * List available curated knowledge packs that can be downloaded for offline use.
     *
     * Returns pack metadata including ID, name, description, download status,
     * and the number of chunks already indexed.
     */
    val LIST_KNOWLEDGE_PACKS = AgentToolDefinition(
        name = "list_knowledge_packs",
        description = "List available curated knowledge packs that can be downloaded for offline use.",
        risk = AgentToolRisk.SAFE,
        argumentSchema = """{"type":"object","properties":{}}""",
        returnContract = """{"packs":[{"id":"string","name":"string","description":"string","downloaded":false,"chunks":0}]}""",
    )

    /**
     * Download and index a curated knowledge pack from Grokipedia for offline search.
     *
     * Requires user confirmation because it uses the network and app storage.
     * After download, all articles in the pack are chunked, embedded, and stored
     * in the local [VectorStore] for semantic search.
     */
    val DOWNLOAD_KNOWLEDGE_PACK = AgentToolDefinition(
        name = "download_knowledge_pack",
        description = "Download and index a curated knowledge pack from Grokipedia for offline search.",
        risk = AgentToolRisk.CONFIRM,
        argumentSchema = """{"type":"object","properties":{"pack_id":{"type":"string","description":"Knowledge pack ID"}},"required":["pack_id"]}""",
        requiredArguments = setOf("pack_id"),
        maxStringLengths = mapOf("pack_id" to 100),
        returnContract = """{"downloaded":true,"pack_id":"string","chunks":50}""",
    )

    /**
     * All knowledge pack tool definitions in a single list for easy registration.
     */
    val ALL: List<AgentToolDefinition> = listOf(
        SEARCH_KNOWLEDGE,
        FETCH_ARTICLE,
        LIST_KNOWLEDGE_PACKS,
        DOWNLOAD_KNOWLEDGE_PACK,
    )
}
