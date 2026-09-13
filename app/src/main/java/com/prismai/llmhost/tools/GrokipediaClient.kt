package com.prismai.llmhost.tools
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*
import com.prismai.llmhost.BuildConfig

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Data class representing a Grokipedia article fetched from grokipedia.com.
 */
data class GrokipediaArticle(
    val slug: String,
    val title: String,
    val content: String,           // Markdown/text content
    val summary: String,           // Short excerpt/summary
    val categories: List<String>,
    val lastModified: String?,
    val citations: List<String>,   // citation URLs
)

/**
 * Data class representing a search result from Grokipedia.
 */
data class GrokipediaSearchResult(
    val slug: String,
    val title: String,
    val snippet: String,
    val relevanceScore: Float,
)

/**
 * HTTP client for fetching and searching Grokipedia (grokipedia.com) articles.
 *
 * Uses [java.net.HttpURLConnection] — no external dependencies.
 * All network operations run on [Dispatchers.IO].
 *
 * Since the exact HTML structure of Grokipedia cannot be verified at dev time,
 * the parser uses multiple strategies in order of preference:
 *   1. CSS-class-based heuristics (<article>, <main>, .content, .article-body)
 *   2. Meta tag extraction (description, keywords)
 *   3. Raw text fallback if all strategies fail
 */
class GrokipediaClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    companion object {
        private const val TAG = "GrokipediaClient"
        private const val BASE_URL = "https://grokipedia.com"
        private const val PAGE_PATH = "/page"
        private const val SEARCH_PATH = "/search"
        private const val USER_AGENT = "PrismLocalAndroid/1.0 (Grokipedia Knowledge Pack)"
    }

    /**
     * Search Grokipedia for articles matching [query].
     *
     * Uses the search endpoint at `https://grokipedia.com/search?q={query}`
     * and parses result links from the HTML response.
     *
     * @param query     the search query
     * @param maxResults maximum number of results to return (1-20, default 10)
     * @return list of [GrokipediaSearchResult] sorted by decreasing relevance
     */
    suspend fun search(query: String, maxResults: Int = 10): List<GrokipediaSearchResult> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val safeMax = maxResults.coerceIn(1, 20)
            runCatching {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("$BASE_URL$SEARCH_PATH?q=$encoded")
                val html = fetchUrl(url)

                val results = parseSearchResults(html, safeMax)
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "search query=\"$query\" results=${results.size}")
                }
                results
            }.getOrDefault(emptyList())
        }

    /**
     * Fetch a full Grokipedia article by its [slug].
     *
     * Fetches `https://grokipedia.com/page/{slug}` and parses the HTML
     * to extract title, content, summary, categories, and citations.
     *
     * @param slug article slug (e.g., "artificial-intelligence")
     * @return parsed [GrokipediaArticle] or null if the page cannot be fetched/parsed
     */
    suspend fun fetchArticle(slug: String): GrokipediaArticle? = withContext(Dispatchers.IO) {
        if (slug.isBlank()) return@withContext null
        runCatching {
            val safeSlug = slug.trim().lowercase()
                .replace(Regex("[^a-z0-9-]"), "")
                .take(200)
            if (safeSlug.isBlank()) return@withContext null

            val url = URL("$BASE_URL$PAGE_PATH/$safeSlug")
            val html = fetchUrl(url)

            val article = parseArticle(safeSlug, html)
            Log.i(TAG, "fetchArticle slug=$safeSlug contentLen=${article.content.length}")
            article
        }.getOrNull()
    }

    /**
     * Fetch multiple articles in batch.
     *
     * @param slugs list of article slugs to fetch
     * @return list of successfully fetched articles (failures are silently skipped)
     */
    suspend fun fetchArticles(slugs: List<String>): List<GrokipediaArticle> = withContext(Dispatchers.IO) {
        if (slugs.isEmpty()) return@withContext emptyList()
        val articles = slugs.map { slug ->
            async {
                runCatching { fetchArticle(slug) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        Log.i(TAG, "fetchArticles requested=${slugs.size} fetched=${articles.size}")
        articles
    }

    // ---- HTTP ----

    /**
     * Fetch the raw text (HTML) from a URL.
     * Throws on non-2xx responses or I/O errors.
     */
    private fun fetchUrl(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.5")
        }
        return try {
            val code = connection.responseCode
            if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else if (code == 404) {
                throw IOException("Article not found (HTTP 404)")
            } else if (code == 403) {
                throw IOException("Access forbidden (HTTP 403)")
            } else {
                throw IOException("HTTP $code from Grokipedia")
            }
        } finally {
            connection.disconnect()
        }
    }

    // ---- Search HTML Parsing ----

    /**
     * Parse search results from Grokipedia HTML.
     *
     * Searches for result links matching common patterns:
     * - `<a href="/page/{slug}">Title</a>`
     * - `<a class="result-link" href="/page/{slug}">`
     * - `<h3 class="result-title"><a href="/page/{slug}">`
     */
    private fun parseSearchResults(html: String, maxResults: Int): List<GrokipediaSearchResult> {
        val results = mutableListOf<GrokipediaSearchResult>()
        val seen = mutableSetOf<String>()

        // Strategy 1: Match links pointing to /page/{slug} with anchor text
        val linkRegex = Regex(
            """<a[^>]*href="(/page/[^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // Strategy 2: Match result cards with class-based heuristics
        val cardRegex = Regex(
            """<div[^>]*class="[^"]*(?:result|search-result|card|entry)[^"]*"[^>]*>.*?<a[^>]*href="(/page/[^"]+)"[^>]*>(.*?)</a>.*?</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // Strategy 3: Match snippet elements
        val snippetRegex = Regex(
            """<p[^>]*class="[^"]*(?:snippet|description|excerpt)[^"]*"[^>]*>(.*?)</p>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // Try card-based matching first (more specific)
        val snippets = snippetRegex.findAll(html).toList()
        val snippetTexts = snippets.map { stripHtml(it.groupValues[1]) }

        // Collect result links
        val cardLinks = cardRegex.findAll(html).toList()
        val allLinks = linkRegex.findAll(html).toList()

        // Process card matches (more reliable positional data)
        var snippetIndex = 0
        for (match in cardLinks) {
            if (results.size >= maxResults) break
            val href = match.groupValues[1].trim()
            val rawTitle = stripHtml(match.groupValues[2]).trim()
            val slug = extractSlug(href)
            if (slug != null && rawTitle.isNotBlank() && slug !in seen) {
                seen.add(slug)
                val snippet = if (snippetIndex < snippetTexts.size) {
                    snippetTexts[snippetIndex++]
                } else {
                    ""
                }
                results.add(
                    GrokipediaSearchResult(
                        slug = slug,
                        title = rawTitle,
                        snippet = snippet,
                        relevanceScore = 1.0f - (results.size * 0.05f).coerceAtMost(0.5f),
                    )
                )
            }
        }

        // Fall back to generic link matching
        if (results.isEmpty()) {
            for (match in allLinks) {
                if (results.size >= maxResults) break
                val href = match.groupValues[1].trim()
                val rawTitle = stripHtml(match.groupValues[2]).trim()
                val slug = extractSlug(href)
                if (slug != null && rawTitle.isNotBlank() && slug !in seen) {
                    seen.add(slug)
                    val snippet = if (snippetIndex < snippetTexts.size) {
                        snippetTexts[snippetIndex++]
                    } else {
                        ""
                    }
                    results.add(
                        GrokipediaSearchResult(
                            slug = slug,
                            title = rawTitle,
                            snippet = snippet,
                            relevanceScore = 1.0f - (results.size * 0.05f).coerceAtMost(0.5f),
                        )
                    )
                }
            }
        }

        return results
    }

    // ---- Article HTML Parsing ----

    /**
     * Parse a full article page from Grokipedia HTML.
     *
     * Uses a flexible multi-strategy parser:
     *   1. Extract title from <h1> or <title>
     *   2. Extract main content from <article>, <main>, or .content/.article-body
     *   3. Extract summary from <meta name="description"> or first <p>
     *   4. Extract categories from tag/category links
     *   5. Extract citations from reference/citation links
     */
    private fun parseArticle(slug: String, html: String): GrokipediaArticle {
        val title = extractTitle(html, slug)
        val content = extractContent(html)
        val summary = extractSummary(html, content)
        val categories = extractCategories(html)
        val lastModified = extractLastModified(html)
        val citations = extractCitations(html)

        return GrokipediaArticle(
            slug = slug,
            title = title,
            content = content,
            summary = summary,
            categories = categories,
            lastModified = lastModified,
            citations = citations,
        )
    }

    /**
     * Extract article title from HTML.
     *
     * Strategy order:
     *   1. <h1 class="article-title">
     *   2. <h1> (first h1 with substantial text)
     *   3. <title> tag (strip site name suffix)
     *   4. Fallback: slug
     */
    private fun extractTitle(html: String, fallbackSlug: String = "article"): String {
        // Strategy 1: <h1> with specific classes
        val h1ClassRegex = Regex(
            """<h1[^>]*class="[^"]*(?:article-title|page-title|entry-title|title)[^"]*"[^>]*>(.*?)</h1>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val h1ClassMatch = h1ClassRegex.find(html)
        if (h1ClassMatch != null) {
            val text = stripHtml(h1ClassMatch.groupValues[1]).trim()
            if (text.isNotBlank() && text.length > 2) return text
        }

        // Strategy 2: First <h1>
        val h1Regex = Regex(
            """<h1[^>]*>(.*?)</h1>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val h1Matches = h1Regex.findAll(html).toList()
        for (match in h1Matches) {
            val text = stripHtml(match.groupValues[1]).trim()
            if (text.isNotBlank() && text.length > 2) return text
        }

        // Strategy 3: <title>
        val titleRegex = Regex(
            """<title[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val titleMatch = titleRegex.find(html)
        if (titleMatch != null) {
            val text = stripHtml(titleMatch.groupValues[1]).trim()
            // Strip common site name suffixes
            return text
                .replace(Regex("""\s*[|–—-]\s*Grokipedia.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*[|–—-]\s*grokipedia\.com.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .takeIf { it.isNotBlank() } ?: text
        }

        return fallbackSlug.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    /**
     * Extract main article content from HTML.
     *
     * Strategy order:
     *   1. <article> tag content
     *   2. <main> tag content
     *   3. <div class="content"> or <div class="article-body">
     *   4. <div class="entry-content">
     *   5. Entire body text as fallback
     */
    private fun extractContent(html: String): String {
        // Strategy 1: <article>
        val articleRegex = Regex(
            """<article[^>]*>(.*?)</article>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val articleMatch = articleRegex.find(html)
        if (articleMatch != null) {
            val text = htmlToText(articleMatch.groupValues[1])
            if (text.length > 100) return text
        }

        // Strategy 2: <main>
        val mainRegex = Regex(
            """<main[^>]*>(.*?)</main>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val mainMatch = mainRegex.find(html)
        if (mainMatch != null) {
            val text = htmlToText(mainMatch.groupValues[1])
            if (text.length > 100) return text
        }

        // Strategy 3: Content divs
        val contentDivRegex = Regex(
            """<div[^>]*class="[^"]*(?:content|article-body|entry-content|post-content|article-content)[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val contentDivMatch = contentDivRegex.find(html)
        if (contentDivMatch != null) {
            val text = htmlToText(contentDivMatch.groupValues[1])
            if (text.length > 100) return text
        }

        // Strategy 4: Body content as fallback
        val bodyRegex = Regex(
            """<body[^>]*>(.*?)</body>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val bodyMatch = bodyRegex.find(html)
        if (bodyMatch != null) {
            val text = htmlToText(bodyMatch.groupValues[1])
            if (text.length > 50) return text
        }

        // Fallback: strip all tags from entire HTML
        return stripHtml(html).trim()
    }

    /**
     * Extract article summary/excerpt.
     *
     * Strategy order:
     *   1. <meta name="description">
     *   2. <meta property="og:description">
     *   3. First <p> inside <article> or <main>
     *   4. First <p> in document
     *   5. First 200 chars of content
     */
    private fun extractSummary(html: String, content: String): String {
        // Strategy 1: Meta description
        val metaDescRegex = Regex(
            """<meta\s+name\s*=\s*["']description["']\s+content\s*=\s*["']([^"']*)["']""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val metaDescMatch = metaDescRegex.find(html)
        if (metaDescMatch != null) {
            val text = htmlDecode(metaDescMatch.groupValues[1]).trim()
            if (text.isNotBlank()) return text
        }

        // Strategy 2: Open Graph description
        val ogDescRegex = Regex(
            """<meta\s+property\s*=\s*["']og:description["']\s+content\s*=\s*["']([^"']*)["']""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val ogDescMatch = ogDescRegex.find(html)
        if (ogDescMatch != null) {
            val text = htmlDecode(ogDescMatch.groupValues[1]).trim()
            if (text.isNotBlank()) return text
        }

        // Strategy 3: First paragraph in article/main
        val paraInArticleRegex = Regex(
            """<article[^>]*>.*?<p[^>]*>(.*?)</p>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val paraMatch = paraInArticleRegex.find(html)
        if (paraMatch != null) {
            val text = stripHtml(paraMatch.groupValues[1]).trim()
            if (text.length > 20) return text
        }

        // Strategy 4: First <p> in document
        val firstParaRegex = Regex(
            """<p[^>]*>(.*?)</p>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val firstPara = firstParaRegex.find(html)
        if (firstPara != null) {
            val text = stripHtml(firstPara.groupValues[1]).trim()
            if (text.length > 20) return text
        }

        // Strategy 5: First 200 chars of content
        if (content.length > 30) {
            return content.take(200).trimEnd('.', ' ') + "..."
        }

        return ""
    }

    /**
     * Extract categories/tags from article HTML.
     *
     * Looks for:
     *   - Links with class containing "category" or "tag"
     *   - <a href="/category/...">
     *   - <a href="/tag/...">
     *   - <span class="categories"> or <div class="tags">
     */
    private fun extractCategories(html: String): List<String> {
        val categories = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // Strategy 1: Category links with class
        val catLinkRegex = Regex(
            """<a[^>]*href="/category/([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (match in catLinkRegex.findAll(html)) {
            val name = stripHtml(match.groupValues[2]).trim()
            if (name.isNotBlank() && name !in seen) {
                seen.add(name)
                categories.add(name)
            }
        }

        // Strategy 2: Tag links
        val tagLinkRegex = Regex(
            """<a[^>]*href="/tag/([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (match in tagLinkRegex.findAll(html)) {
            val name = stripHtml(match.groupValues[2]).trim()
            if (name.isNotBlank() && name !in seen) {
                seen.add(name)
                categories.add(name)
            }
        }

        // Strategy 3: Category/tag container spans
        val catSpanRegex = Regex(
            """<(?:span|div)[^>]*class="[^"]*(?:categories|tags|meta-categories)[^"]*"[^>]*>(.*?)</(?:span|div)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (match in catSpanRegex.findAll(html)) {
            val innerLinks = Regex(
                """<a[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            for (link in innerLinks.findAll(match.groupValues[1])) {
                val name = stripHtml(link.groupValues[1]).trim()
                if (name.isNotBlank() && name !in seen) {
                    seen.add(name)
                    categories.add(name)
                }
            }
        }

        return categories
    }

    /**
     * Extract last-modified timestamp from article HTML.
     *
     * Looks for:
     *   - <meta name="article:modified_time">
     *   - <time> tag content
     *   - <span class="last-modified">
     *   - <meta name="last-modified">
     */
    private fun extractLastModified(html: String): String? {
        // Strategy 1: <meta name="article:modified_time">
        val metaRegex = Regex(
            """<meta\s+(?:name|property)\s*=\s*["'](?:article:modified_time|last-modified)["']\s+content\s*=\s*["']([^"']*)["']""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val metaMatch = metaRegex.find(html)
        if (metaMatch != null) {
            val value = metaMatch.groupValues[1].trim()
            if (value.isNotBlank()) return value
        }

        // Strategy 2: <time> tag
        val timeRegex = Regex(
            """<time[^>]*datetime\s*=\s*["']([^"']*)["'][^>]*>(.*?)</time>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val timeMatch = timeRegex.find(html)
        if (timeMatch != null) {
            val datetime = timeMatch.groupValues[1].trim()
            if (datetime.isNotBlank()) return datetime
            val text = stripHtml(timeMatch.groupValues[2]).trim()
            if (text.isNotBlank()) return text
        }

        // Strategy 3: Last-modified text span
        val textRegex = Regex(
            """<(?:span|div)[^>]*class="[^"]*(?:last-modified|updated|last-updated)[^"]*"[^>]*>(.*?)</(?:span|div)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val textMatch = textRegex.find(html)
        if (textMatch != null) {
            val text = stripHtml(textMatch.groupValues[1]).trim()
            if (text.isNotBlank()) return text
        }

        return null
    }

    /**
     * Extract citation/reference URLs from article HTML.
     *
     * Looks for:
     *   - <sup> or <a> with class "citation" or "reference"
     *   - Sections with id "references" or "citations"
     *   - <a> elements containing "cite" or "ref" in class
     */
    private fun extractCitations(html: String): List<String> {
        val citations = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // Strategy 1: Citation/reference links
        val citeLinkRegex = Regex(
            """<a[^>]*class="[^"]*(?:citation|reference|cite)[^"]*"[^>]*href="(https?://[^"]+)"[^>]*>""",
            setOf(RegexOption.IGNORE_CASE),
        )
        for (match in citeLinkRegex.findAll(html)) {
            val url = match.groupValues[1].trim()
            if (url.isNotBlank() && url !in seen) {
                seen.add(url)
                citations.add(url)
            }
        }

        // Strategy 2: <sup> tags containing links (footnote references)
        val supLinkRegex = Regex(
            """<sup[^>]*>.*?<a[^>]*href="(https?://[^"]+)"[^>]*>.*?</a>.*?</sup>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (match in supLinkRegex.findAll(html)) {
            val url = match.groupValues[1].trim()
            if (url.isNotBlank() && url !in seen) {
                seen.add(url)
                citations.add(url)
            }
        }

        // Strategy 3: References section links
        val refSectionRegex = Regex(
            """<(?:div|section|ol)[^>]*(?:id|class)\s*=\s*["'](?:references|citations|footnotes)[^"']*["'][^>]*>(.*?)</(?:div|section|ol)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val refSection = refSectionRegex.find(html)
        if (refSection != null) {
            val refLinks = Regex(
                """<a[^>]*href="(https?://[^"]+)"[^>]*>""",
                setOf(RegexOption.IGNORE_CASE),
            )
            for (match in refLinks.findAll(refSection.groupValues[1])) {
                val url = match.groupValues[1].trim()
                if (url.isNotBlank() && url !in seen) {
                    seen.add(url)
                    citations.add(url)
                }
            }
        }

        return citations
    }

    // ---- HTML Utilities ----

    /**
     * Convert HTML to plain text while preserving paragraph structure.
     *
     * Replaces block tags with newlines, strips inline tags,
     * decodes HTML entities, and normalizes whitespace.
     */
    private fun htmlToText(html: String): String {
        return html
            // Block-level tags become newlines
            .replace(Regex("""</?(?:p|div|h[1-6]|blockquote|li|tr|section|article|main|header|footer|nav)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
            // Line breaks
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            // Strip remaining tags
            .replace(Regex("""<[^>]+>"""), "")
            // Decode common entities
            .let { htmlDecode(it) }
            // Normalize whitespace
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    /**
     * Strip all HTML tags, decode entities, and return plain text.
     */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("""<[^>]+>"""), "")
            .let { htmlDecode(it) }
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Decode common HTML entities.
     */
    private fun htmlDecode(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&#x2F;", "/")
            .replace("&nbsp;", " ")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
            .replace("&hellip;", "…")
            .replace(Regex("&#(\\d+);")) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null) code.toChar().toString() else match.value
            }
    }

    /**
     * Extract the slug from a Grokipedia URL path.
     * E.g., "/page/artificial-intelligence" -> "artificial-intelligence"
     */
    private fun extractSlug(href: String): String? {
        val match = Regex("""/page/([a-z0-9][a-z0-9-]*)""", RegexOption.IGNORE_CASE).find(href)
        return match?.groupValues?.getOrNull(1)?.trim()?.lowercase()
    }
}
