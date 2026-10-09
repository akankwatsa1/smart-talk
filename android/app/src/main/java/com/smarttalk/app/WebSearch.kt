package com.smarttalk.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Port of the desktop proxy's unified_web_search: DuckDuckGo Lite for general
 * results, Google News RSS for recent items, and Wikipedia as a fallback when
 * both come back thin.
 *
 * Runs on a background thread; the caller supplies the results to the model as
 * grounding context. No key is needed because all three endpoints are public.
 */
class WebSearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun search(query: String, limit: Int = 5): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val combined = ArrayList<SearchResult>()
        val seen = HashSet<String>()

        fun addAll(items: List<SearchResult>) {
            for (item in items) {
                if (item.url.isNotBlank() && seen.add(item.url)) combined.add(item)
            }
        }

        addAll(runCatching { searchGoogleNews(q, 3) }.getOrDefault(emptyList()))
        addAll(runCatching { searchDuckDuckGoLite(q, 8) }.getOrDefault(emptyList()))

        if (combined.size < 2) {
            addAll(runCatching { searchWikipedia(q, 3) }.getOrDefault(emptyList()))
        }
        return combined.take(limit)
    }

    fun formatForModel(results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        return buildString {
            appendLine("Live web results for this question, retrieved just now.")
            appendLine("Use them where they are relevant, cite the source names inline,")
            appendLine("and say plainly if they do not answer the question.")
            appendLine()
            results.forEachIndexed { i, r ->
                appendLine("[${i + 1}] ${r.title}")
                appendLine("Source: ${r.source}")
                appendLine("URL: ${r.url}")
                if (r.snippet.isNotBlank()) appendLine("Summary: ${r.snippet}")
                appendLine()
            }
        }
    }

    // ------------------------------------------------------------------ sources

    private fun get(url: String, accept: String = "text/html,application/xhtml+xml"): String? {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }

    private fun searchDuckDuckGoLite(query: String, limit: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = get("https://lite.duckduckgo.com/lite/?q=$encoded") ?: return emptyList()
        val results = ArrayList<SearchResult>()
        val rows = Regex("<tr>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.groupValues[1] }.toList()

        rows.forEachIndexed { index, row ->
            if (results.size >= limit) return@forEachIndexed
            val link = Regex(
                "<a[^>]+class=[\"']result-link[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                RegexOption.DOT_MATCHES_ALL
            ).find(row) ?: Regex(
                "<a[^>]+rel=[\"']nofollow[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                RegexOption.DOT_MATCHES_ALL
            ).find(row) ?: return@forEachIndexed

            var href = link.groupValues[1]
            val title = stripTags(link.groupValues[2])
            var snippet = ""
            if (index + 1 < rows.size && rows[index + 1].contains("result-snippet")) {
                snippet = stripTags(rows[index + 1])
            }
            if (href.isBlank() || href.contains("duckduckgo.com")) return@forEachIndexed
            if (href.contains("uddg=")) {
                href = Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)
                    ?.let { decode(it) } ?: href
            }
            val domain = domainOf(href)
            results.add(
                SearchResult(
                    title = title.ifBlank { domain },
                    snippet = snippet,
                    url = href,
                    source = domain.ifBlank { "Web" }
                )
            )
        }
        return results
    }

    private fun searchGoogleNews(query: String, limit: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val xml = get(
            "https://news.google.com/rss/search?q=$encoded&hl=en-US&gl=US&ceid=US:en",
            accept = "application/rss+xml,application/xml,text/xml"
        ) ?: return emptyList()

        return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .take(limit)
            .mapNotNull { match ->
                val item = match.groupValues[1]
                val title = unescape(stripTags(Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1).orEmpty()))
                val link = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1).orEmpty()
                val source = unescape(stripTags(Regex("<source[^>]*>(.*?)</source>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1).orEmpty()))
                val desc = unescape(stripTags(Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1).orEmpty()))
                if (title.isBlank() || link.isBlank()) {
                    null
                } else {
                    SearchResult(
                        title = title,
                        snippet = desc.ifBlank { title },
                        url = link,
                        source = source.ifBlank { "News" }
                    )
                }
            }
            .toList()
    }

    private fun searchWikipedia(query: String, limit: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = get(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json",
            accept = "application/json"
        ) ?: return emptyList()

        return try {
            val root = org.json.JSONObject(json)
            val arr = root.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
            (0 until minOf(arr.length(), limit)).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = item.optString("title")
                if (title.isBlank()) return@mapNotNull null
                SearchResult(
                    title = title,
                    snippet = unescape(stripTags(item.optString("snippet"))),
                    url = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                    source = "Wikipedia"
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun stripTags(html: String): String =
        unescape(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun unescape(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private fun decode(value: String): String = try {
        java.net.URLDecoder.decode(value, "UTF-8")
    } catch (t: Throwable) {
        value
    }

    private fun domainOf(url: String): String = try {
        java.net.URI(url).host?.removePrefix("www.").orEmpty()
    } catch (t: Throwable) {
        ""
    }
}
