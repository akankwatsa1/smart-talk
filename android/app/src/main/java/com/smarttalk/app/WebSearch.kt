package com.smarttalk.app

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One web result. */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
) {
    /** Persisted with the message, so citations survive a restart. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("snippet", snippet)
        put("url", url)
        put("source", source)
    }
}

/** The readable body of a fetched page, with table rows preserved. */
data class PageContent(
    val url: String,
    val title: String,
    val content: String
)

/**
 * Live web grounding.
 *
 * Three findings from probing the real endpoints drove this rewrite:
 *
 *  1. DuckDuckGo Lite BLOCKS plain GET requests, answering HTTP 202 with an
 *     empty shell. It only returns results for a POSTed form. The previous
 *     implementation used GET, so this source was silently returning nothing at
 *     all - which is why questions needing current facts had no grounding.
 *  2. Sending only search-result SNIPPETS is not enough. Asked for a league
 *     table, snippets do not contain it and the model either guessed or refused.
 *  3. Wikipedia's plain-text extract API strips tables out entirely, so even a
 *     perfect Wikipedia lookup returned no standings. The HTML must be parsed
 *     and the table rows kept.
 *
 * So results are now retrieved by POST, the top pages are actually downloaded,
 * and tables survive as pipe-separated rows that a model can read.
 */
class WebSearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ---------------------------------------------------------------- searching

    fun search(query: String, limit: Int, perSource: Int = 6): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val combined = ArrayList<SearchResult>()
        val seen = HashSet<String>()

        fun addAll(items: List<SearchResult>) {
            for (item in items) {
                val key = item.url.substringBefore('#').trimEnd('/')
                if (item.url.isBlank() || !seen.add(key)) continue
                combined.add(item)
            }
        }

        val news = attempt { searchGoogleNews(q, 3) }
        val ddg = attempt { searchDuckDuckGo(q, perSource) }
        val bing = attempt { searchBing(q, perSource) }

        // News first (timeliest), then the two general engines interleaved, so a
        // single blocked source cannot starve the model of context.
        addAll(news)
        addAll(interleave(ddg, bing))

        if (combined.size < 3) addAll(attempt { searchWikipedia(q, 3) })

        return combined.take(limit)
    }

    private inline fun attempt(block: () -> List<SearchResult>): List<SearchResult> =
        runCatching(block).getOrDefault(emptyList())

    private fun interleave(a: List<SearchResult>, b: List<SearchResult>): List<SearchResult> {
        val out = ArrayList<SearchResult>(a.size + b.size)
        var i = 0
        while (i < a.size || i < b.size) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
            i++
        }
        return out
    }

    /**
     * DuckDuckGo Lite. POST is required: the GET form is answered with HTTP 202
     * and no results, which is the bug this method exists to avoid repeating.
     */
    private fun searchDuckDuckGo(query: String, limit: Int): List<SearchResult> {
        val body = FormBody.Builder().add("q", query).build()
        val request = Request.Builder()
            .url("https://lite.duckduckgo.com/lite/")
            .post(body)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", "https://lite.duckduckgo.com")
            .header("Referer", "https://lite.duckduckgo.com/")
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        if (html.isBlank()) return emptyList()

        val results = ArrayList<SearchResult>()

        val anchors = Regex(
            "<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(html)

        for (match in anchors) {
            if (results.size >= limit) break
            var href = match.groupValues[1]
            val title = decodeEntities(stripTags(match.groupValues[2]))
            if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("http")) continue
            if (isNoiseUrl(href, "duckduckgo.com")) continue
            if (title.length < 3) continue
            if (href.contains("uddg=")) {
                href = Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)
                    ?.let { urlDecode(it) } ?: href
            }
            results.add(
                SearchResult(
                    title = title,
                    snippet = "",
                    url = href,
                    source = domainOf(href).ifBlank { "Web" }
                )
            )
        }

        // Snippets sit in following rows; attach them where the markup allows.
        if (results.isNotEmpty()) {
            val snippetRows = Regex(
                "class=[\"'][^\"']*result-snippet[^\"']*[\"'][^>]*>(.*?)</td>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).findAll(html).map { decodeEntities(stripTags(it.groupValues[1])) }.toList()
            results.forEachIndexed { index, result ->
                val snippet = snippetRows.getOrNull(index)
                if (!snippet.isNullOrBlank()) results[index] = result.copy(snippet = snippet)
            }
        }
        return results
    }

    /** Bing's HTML still parses reliably and is a useful second opinion. */
    private fun searchBing(query: String, limit: Int): List<SearchResult> {
        val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&setlang=en"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }

        val blocks = Regex(
            "<li class=[\"'][^\"']*b_algo[^\"']*[\"'][\\s\\S]*?(?=<li class=[\"'][^\"']*b_algo|</ol>)",
            RegexOption.IGNORE_CASE
        ).findAll(html)

        val results = ArrayList<SearchResult>()
        for (block in blocks) {
            if (results.size >= limit) break
            val chunk = block.value
            val link = Regex(
                "<h2[^>]*>\\s*<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(chunk) ?: continue
            val href = link.groupValues[1]
            val title = decodeEntities(stripTags(link.groupValues[2]))
            if (!href.startsWith("http") || isNoiseUrl(href, "bing.com")) continue
            if (title.length < 3) continue
            val snippet = Regex(
                "<p[^>]*>(.*?)</p>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(chunk)?.let { decodeEntities(stripTags(it.groupValues[1])) }.orEmpty()
            results.add(
                SearchResult(
                    title = title,
                    snippet = snippet,
                    url = href,
                    source = domainOf(href).ifBlank { "Web" }
                )
            )
        }
        return results
    }

    private fun searchGoogleNews(query: String, limit: Int): List<SearchResult> {
        val url = "https://news.google.com/rss/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&hl=en-US&gl=US&ceid=US:en"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/rss+xml,application/xml,text/xml")
            .get()
            .build()

        val xml = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        if (xml.isBlank()) return emptyList()

        return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .take(limit)
            .mapNotNull { match ->
                val item = match.groupValues[1]
                val title = stripTags(tag(item, "title"))
                val link = tag(item, "link")
                val source = stripTags(tag(item, "source"))
                val desc = stripTags(tag(item, "description"))
                if (title.isBlank() || link.isBlank()) return@mapNotNull null
                SearchResult(
                    title = title,
                    snippet = desc.ifBlank { title },
                    url = link,
                    source = source.ifBlank { "News" }
                )
            }
            .toList()
    }

    private fun searchWikipedia(query: String, limit: Int): List<SearchResult> {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=" +
            limit + "&srsearch=" + URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }

        return runCatching {
            val arr = JSONObject(json).optJSONObject("query")?.optJSONArray("search")
                ?: return emptyList()
            (0 until minOf(arr.length(), limit)).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = item.optString("title")
                if (title.isBlank()) return@mapNotNull null
                SearchResult(
                    title = title,
                    snippet = stripTags(item.optString("snippet")),
                    url = "https://en.wikipedia.org/wiki/" +
                        URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                    source = "Wikipedia"
                )
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------- page fetching

    /**
     * Download the top results so the model sees real content, not teasers.
     * This is what makes a question like "the current league table" answerable.
     */
    fun fetchPages(
        results: List<SearchResult>,
        maxPages: Int,
        budgetChars: Int
    ): List<PageContent> {
        val out = ArrayList<PageContent>()
        for (result in results.take(maxPages)) {
            if (result.url.isBlank()) continue
            val page = runCatching { fetchOne(result, budgetChars) }.getOrNull()
            if (page != null && page.content.length > 200) out.add(page)
        }
        return out
    }

    private fun fetchOne(result: SearchResult, budgetChars: Int): PageContent? {
        val request = Request.Builder()
            .url(result.url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type").orEmpty()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("text") &&
                !type.contains("json")
            ) {
                return null
            }
            response.body?.string().orEmpty()
        }
        if (html.isBlank()) return null
        val text = condense(extractReadableText(html), budgetChars)
        if (text.isBlank()) return null
        return PageContent(url = result.url, title = result.title, content = text)
    }

    /**
     * Turn HTML into readable text while KEEPING table structure.
     *
     * Tables are handled first and separately: each `<tr>` becomes exactly ONE
     * pipe-separated line, with everything inside a cell collapsed to spaces.
     *
     * This ordering is the whole point. Sites nest `<div>`s inside table cells,
     * and treating `</div>` as a line break is correct for prose but shatters a
     * single cell across several lines. When that happened, a league table came
     * back as bare numbers with the club name stranded on its own line - and a
     * model handed numbers with no names will confidently map them to the wrong
     * teams. Prose is still split on block tags; cells are never split.
     */
    fun extractReadableText(html: String): String {
        val tableRows = ArrayList<String>()
        for (table in Regex("(?is)<table.*?</table>").findAll(html)) {
            tableRows.addAll(convertTableToRows(table.value))
        }

        var s = html.replace(Regex("(?is)<table.*?</table>"), "\n")
        s = s.replace(Regex("(?is)<script.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style.*?</style>"), " ")
        s = s.replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        s = s.replace(Regex("(?is)<svg.*?</svg>"), " ")
        s = s.replace(Regex("(?is)<head.*?</head>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")

        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(
            Regex("(?i)</(p|div|li|h[1-6]|section|article|header|footer|nav|blockquote)\\s*>"),
            "\n"
        )
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decodeEntities(s)

        val lines = s.lines()
            .map { line ->
                line.replace(Regex("[\\u00A0\\t]+"), " ")
                    .replace(Regex(" {2,}"), " ")
                    .trim()
            }
            .filter { it.length > 1 }

        // Collapse runs of identical lines (common in menus and repeated headers).
        val prose = ArrayList<String>(lines.size)
        var previous = ""
        for (line in lines) {
            if (line != previous) prose.add(line)
            previous = line
        }

        if (tableRows.isEmpty()) return prose.joinToString("\n")

        return buildString {
            append(prose.joinToString("\n"))
            append("\n\n")
            appendLine("--- table data from this page, one row per line ---")
            tableRows.forEach { appendLine(it) }
        }.trim()
    }

    /**
     * Convert a table into one line per row. Exposed so it can be exercised
     * directly: this is the function that decides whether a league table arrives
     * readable or as a column of anonymous numbers.
     */
    fun convertTableToRows(tableHtml: String): List<String> {
        val out = ArrayList<String>()
        for (row in Regex("(?is)<tr[^>]*>.*?</tr>").findAll(tableHtml)) {
            val cells = Regex("(?is)<t[dh][^>]*>.*?</t[dh]>")
                .findAll(row.value)
                .map { cellText(it.value) }
                .toList()
            if (cells.isEmpty() || cells.all { it.isBlank() }) continue
            out.add(cells.joinToString(" | "))
        }
        return out
    }

    /** One cell, flattened onto a single line however deeply it is nested. */
    private fun cellText(cellHtml: String): String {
        val withoutTags = cellHtml.replace(Regex("(?is)<[^>]+>"), " ")
        return decodeEntities(withoutTags).replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Fit a long page into a budget without losing the reason we fetched it.
     *
     * Data rows (pipe-separated lines carrying digits) are kept in full, and the
     * opening prose fills whatever budget remains. Selection happens on a set of
     * line numbers, then output preserves the page's original order so the text
     * still reads coherently.
     */
    fun condense(text: String, budgetChars: Int): String {
        if (text.length <= budgetChars) return text
        val lines = text.lines()

        fun isDataRow(line: String): Boolean {
            val pipes = line.count { it == '|' }
            return pipes >= 2 && line.any { it.isDigit() } && line.length < 400
        }

        val keep = HashSet<Int>()
        var used = 0

        // 1. Every data row, up to 70% of the budget: tables are usually the answer.
        val rowBudget = (budgetChars * 0.7).toInt()
        for ((index, line) in lines.withIndex()) {
            if (!isDataRow(line)) continue
            if (used + line.length > rowBudget) break
            keep.add(index)
            used += line.length
        }

        // 2. Then the opening prose, which carries the context around the table.
        for ((index, line) in lines.withIndex()) {
            if (keep.contains(index)) continue
            if (used + line.length > budgetChars) break
            keep.add(index)
            used += line.length
        }

        return keep.sorted().joinToString("\n") { lines[it] }
    }

    // -------------------------------------------------------------- grounding

    /**
     * Build the context block handed to the model. The date is stated explicitly
     * because a model asked for "the current table" will otherwise answer from
     * its training data and be confidently wrong.
     */
    fun buildGrounding(
        results: List<SearchResult>,
        pages: List<PageContent>,
        todayIso: String
    ): String {
        if (results.isEmpty() && pages.isEmpty()) return ""
        return buildString {
            appendLine("LIVE WEB CONTEXT")
            appendLine("Today's date is $todayIso. Your training data may be out of date;")
            appendLine("the material below was retrieved today and takes precedence.")
            appendLine()
            if (pages.isNotEmpty()) {
                appendLine("Fetched page content:")
                pages.forEachIndexed { i, page ->
                    appendLine("--- page ${i + 1}: ${page.title}")
                    appendLine("URL: ${page.url}")
                    appendLine(page.content)
                    appendLine()
                }
            }
            if (results.isNotEmpty()) {
                appendLine("Other search results:")
                results.forEachIndexed { i, r ->
                    appendLine("[${i + 1}] ${r.title} (${r.source})")
                    appendLine("    ${r.url}")
                    if (r.snippet.isNotBlank()) appendLine("    ${r.snippet}")
                }
                appendLine()
            }
            appendLine("Instructions for answering:")
            appendLine("- Answer the question directly using the material above.")
            appendLine("- When the material contains figures, tables or dates, quote them exactly.")
            appendLine("- Do not say you lack current information if the answer is present above.")
            appendLine("- If the material genuinely does not contain it, say so plainly and say")
            appendLine("  that it could not be confirmed from today's sources.")
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun tag(item: String, name: String): String =
        Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(item)?.groupValues?.get(1).orEmpty()

    private fun stripTags(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun isNoiseUrl(url: String, host: String): Boolean {
        if (url.contains(host)) return true
        return Regex(
            "\\.(css|js|png|jpe?g|svg|gif|ico|woff2?|pdf)(\\?|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(url)
    }

    private fun urlDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun domainOf(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.").orEmpty() }.getOrDefault("")

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val USER_AGENT = "SmartTalk/1.0 (Android)"

        fun decodeEntities(input: String): String {
            var s = input
            s = s.replace("&nbsp;", " ").replace("&#160;", " ").replace("&#xA0;", " ")
            s = s.replace("&amp;", "&")
            s = s.replace("&lt;", "<").replace("&gt;", ">")
            s = s.replace("&quot;", "\"").replace("&#34;", "\"")
            s = s.replace("&#39;", "'").replace("&apos;", "'")
            s = s.replace("&mdash;", "-").replace("&ndash;", "-").replace("&minus;", "-")
            s = s.replace("&hellip;", "...").replace("&rsquo;", "'").replace("&lsquo;", "'")
            s = s.replace("&ldquo;", "\"").replace("&rdquo;", "\"")
            s = s.replace("&pound;", "GBP").replace("&euro;", "EUR").replace("&deg;", " degrees")
            // Numeric entities, so prices and symbols survive.
            s = Regex("&#(\\d{1,5});").replace(s) { m ->
                val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
                if (code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            }
            return s
        }
    }
}
