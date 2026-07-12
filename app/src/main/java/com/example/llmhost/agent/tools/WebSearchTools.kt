package com.example.llmhost.agent.tools

import com.example.llmhost.AgentToolCall
import com.example.llmhost.AgentToolErrorCode
import com.example.llmhost.AgentToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class SearchResult(val title: String, val snippet: String, val url: String)

class WebSearchTools(
    private val onFirstUse: () -> Unit,
) {
    var webSearchUsedThisSession = false
        private set

    fun webSearch(call: AgentToolCall): AgentToolResult {
        if (!webSearchUsedThisSession) {
            webSearchUsedThisSession = true
            onFirstUse()
        }
        val query = call.arguments.optString("query").trim().take(200)
        val maxResults = call.arguments.optInt("max_results", 5).coerceIn(1, 10)
        if (query.isBlank()) return toolFailure(call, AgentToolErrorCode.INVALID_ARGUMENT, "Search query is empty")

        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PrismLocalAndroid/1.0")
            }
            val html = try {
                val code = connection.responseCode
                if (code !in 200..299) throw java.io.IOException("HTTP $code from DuckDuckGo")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val results = parseDuckDuckGoResults(html, maxResults)
            toolSuccess(call, "${results.size} web result(s) for \"$query\"",
                JSONObject().put("query", query).put("untrusted_data", true)
                    .put("result_count", results.size)
                    .put("results", JSONArray(results.map { r ->
                        JSONObject().put("title", r.title).put("snippet", r.snippet).put("url", r.url)
                    })))
        } catch (e: Exception) {
            toolFailure(call, AgentToolErrorCode.FAILED, "Web search failed: ${e.message ?: "network error"}")
        }
    }

    private fun parseDuckDuckGoResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val linkRegex = Regex(
            """<a[^>]*class="result__a"[^>]*href="(https?://[^"]*)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val snippetRegex = Regex(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        val linkMatches = linkRegex.findAll(html).take(maxResults).toList()
        val snippetMatches = snippetRegex.findAll(html).take(maxResults).toList()

        for (i in linkMatches.indices) {
            val title = linkMatches[i].groupValues[2]
                .replace(Regex("""<[^>]+>"""), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .trim()
            val url = linkMatches[i].groupValues[1].trim()
            val snippet = if (i < snippetMatches.size) {
                snippetMatches[i].groupValues[1]
                    .replace(Regex("""<[^>]+>"""), "")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                    .trim()
            } else ""
            if (title.isNotBlank()) {
                results.add(SearchResult(title = title, snippet = snippet, url = url))
            }
        }
        return results
    }
}
