package com.smarttalk.app

import android.util.Base64
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
 * Every rule below came from probing the real endpoints, and several exist
 * because a plausible-looking implementation silently returned nothing:
 *
 *  1. DuckDuckGo Lite REJECTS plain GET, answering HTTP 202 with an empty shell.
 *     It only answers a POSTed form. Worse, it blocks this kind of traffic after
 *     a burst of queries, so it is treated as best-effort with a cooldown rather
 *     than as the primary source.
 *  2. Bing wraps every result in a click-tracking redirect
 *     (`bing.com/ck/a?...&u=a1<base64>`). Fetching that redirect returns HTTP 204
 *     with no body, so Bing looked useless until the `u=` parameter was decoded.
 *  3. Search engines cannot resolve "yesterday". Sent literally, "yesterday's epl
 *     results" made Bing return Windows File Explorer pages. Substituting the
 *     resolved date turned the same question into relevant results.
 *  4. Snippets are not answers, and Wikipedia's text-extract API strips tables
 *     out entirely, so pages are downloaded and their tables preserved.
 *  5. Top results are often unfetchable (sportytrader answers 403), so more
 *     candidates are tried than are needed and failures are skipped.
 */
class WebSearch {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** DuckDuckGo cooldown, so a blocking engine is not retried on every turn. */
    private var duckDuckGoBlockedUntil = 0L

    // ---------------------------------------------------------------- searching

    fun search(query: String, limit: Int, perSource: Int = 6): List<SearchResult> =
        search(listOf(query), limit, perSource)

    /**
     * Search every query in [queries] and merge the results in order.
     *
     * More than one query is normal: the literal question, plus a date-anchored
     * variant when the question uses a relative date.
     */
    fun search(queries: List<String>, limit: Int, perSource: Int = 6): List<SearchResult> {
        val cleaned = queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return emptyList()

        val combined = ArrayList<SearchResult>()
        val seen = HashSet<String>()

        fun addAll(items: List<SearchResult>) {
            for (item in items) {
                val key = item.url.substringBefore('#').trimEnd('/')
                if (item.url.isBlank() || !seen.add(key)) continue
                combined.add(item)
            }
        }

        for (q in cleaned) {
            // News is timeliest, then the two general engines interleaved, so a
            // single blocked source cannot starve the model of context.
            addAll(attempt { searchGoogleNews(q, 3) })
            addAll(
                interleave(
                    attempt { searchDuckDuckGo(q, perSource) },
                    attempt { searchBing(q, perSource) }
                )
            )
            if (combined.size >= limit) break
        }

        if (combined.size < 3) addAll(attempt { searchWikipedia(cleaned.first(), 3) })

        return combined.take(limit)
    }

    /**
     * Build the queries sent to the engines.
     *
     * A search engine cannot resolve "yesterday", so the resolved date is
     * substituted as well as the original question being sent unchanged.
     */
    fun buildQueries(question: String, today: LocalDate): List<String> {
        val q = question.trim()
        if (q.isEmpty()) return emptyList()

        val relative = listOf(
            "yesterday" to today.minusDays(1),
            "last night" to today.minusDays(1),
            "last week" to today.minusDays(7),
            "today" to today,
            "tonight" to today,
            "this morning" to today,
            "tomorrow" to today.plusDays(1)
        )

        for ((word, date) in relative) {
            val pattern = Regex("(?i)\\b" + Regex.escape(word) + "\\b")
            if (!pattern.containsMatchIn(q)) continue
            return listOf(q, pattern.replace(q, date.format(LONG_DATE))).distinct()
        }
        return listOf(q)
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
     * DuckDuckGo Lite, by POST. GET is answered with HTTP 202 and no results,
     * and repeated queries earn a block, so a cooldown is applied afterwards.
     */
    private fun searchDuckDuckGo(query: String, limit: Int): List<SearchResult> {
        if (System.currentTimeMillis() < duckDuckGoBlockedUntil) return emptyList()

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

        val response = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) {
                // 202 means "go away": back off rather than hammering it.
                if (r.code == 202) {
                    duckDuckGoBlockedUntil = System.currentTimeMillis() + DDG_COOLDOWN_MS
                }
                return emptyList()
            }
            r.body?.string().orEmpty()
        }
        if (response.isBlank()) return emptyList()

        val results = ArrayList<SearchResult>()
        for (match in Regex(
            "<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(response)) {
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
            results.add(SearchResult(title, "", href, domainOf(href).ifBlank { "Web" }))
        }

        if (results.isEmpty()) {
            duckDuckGoBlockedUntil = System.currentTimeMillis() + DDG_COOLDOWN_MS
            return emptyList()
        }

        // Snippets sit in following rows; attach them where the markup allows.
        val snippetRows = Regex(
            "class=[\"'][^\"']*result-snippet[^\"']*[\"'][^>]*>(.*?)</td>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(response).map { decodeEntities(stripTags(it.groupValues[1])) }.toList()
        results.forEachIndexed { index, result ->
            snippetRows.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                results[index] = result.copy(snippet = it)
            }
        }
        return results
    }

    /**
     * Bing, whose click-tracking redirects must be decoded before use.
     * Fetching `bing.com/ck/a?...` directly returns HTTP 204 with no body.
     */
    private fun searchBing(query: String, limit: Int): List<SearchResult> {
        val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&setlang=en"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        val html = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            r.body?.string().orEmpty()
        }

        val results = ArrayList<SearchResult>()
        val blocks = Regex(
            "<li class=[\"'][^\"']*b_algo[^\"']*[\"'][\\s\\S]*?(?=<li class=[\"'][^\"']*b_algo|</ol>)",
            RegexOption.IGNORE_CASE
        ).findAll(html)

        for (block in blocks) {
            if (results.size >= limit) break
            val chunk = block.value
            val link = Regex(
                "<h2[^>]*>\\s*<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(chunk) ?: continue

            val href = decodeBingUrl(decodeEntities(link.groupValues[1])) ?: continue
            val title = decodeEntities(stripTags(link.groupValues[2]))
            if (!href.startsWith("http") || isNoiseUrl(href, "bing.com")) continue
            if (title.length < 3) continue

            val snippet = Regex(
                "<p[^>]*>(.*?)</p>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(chunk)?.let { decodeEntities(stripTags(it.groupValues[1])) }.orEmpty()

            results.add(SearchResult(title, snippet, href, domainOf(href).ifBlank { "Web" }))
        }
        return results
    }

    /**
     * Turn a Bing tracking link back into the real destination.
     * The target is the `u=` parameter: an "a1" marker plus base64url.
     */
    fun decodeBingUrl(href: String): String? {
        if (!href.contains("bing.com/ck/a") && !href.contains("bing.com/ck/")) return href
        val marker = href.indexOf("u=")
        if (marker < 0) return null
        var raw = href.substring(marker + 2).substringBefore('&')
        raw = urlDecode(raw)
        var encoded = if (raw.startsWith("a1")) raw.substring(2) else raw
        encoded = encoded.replace('-', '+').replace('_', '/')
        while (encoded.length % 4 != 0) encoded += "="
        val decoded = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return if (decoded.startsWith("http")) decoded else null
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

        val xml = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            r.body?.string().orEmpty()
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
                SearchResult(title, desc.ifBlank { title }, link, source.ifBlank { "News" })
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

        val json = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            r.body?.string().orEmpty()
        }

        return runCatching {
            val arr = JSONObject(json).optJSONObject("query")?.optJSONArray("search")
                ?: return emptyList()
            (0 until minOf(arr.length(), limit)).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = item.optString("title")
                if (title.isBlank()) return@mapNotNull null
                SearchResult(
                    title,
                    stripTags(item.optString("snippet")),
                    "https://en.wikipedia.org/wiki/" +
                        URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                    "Wikipedia"
                )
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------- page fetching

    /**
     * Download candidate pages until [maxPages] have succeeded.
     *
     * More candidates are tried than are needed because popular result pages are
     * frequently unfetchable - sportytrader answers HTTP 403, and Bing's raw
     * redirects answer 204 with no body. Skipping failures is what turns a search
     * that found the right page into an answer that actually contains the facts.
     */
    fun fetchPages(
        results: List<SearchResult>,
        maxPages: Int,
        budgetChars: Int
    ): List<PageContent> {
        if (maxPages <= 0) return emptyList()
        val out = ArrayList<PageContent>()
        var attempted = 0
        val attemptLimit = minOf(results.size, maxPages * 3)

        for (result in results) {
            if (out.size >= maxPages || attempted >= attemptLimit) break
            if (result.url.isBlank()) continue
            attempted++
            val page = runCatching { fetchOne(result, budgetChars) }.getOrNull() ?: continue
            if (page.content.length > 200) out.add(page)
        }
        return out
    }

    private fun fetchOne(result: SearchResult, budgetChars: Int): PageContent? {
        val origin = runCatching {
            val u = java.net.URI(result.url)
            "${u.scheme}://${u.host}/"
        }.getOrDefault("")

        val request = Request.Builder()
            .url(result.url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { if (origin.isNotBlank()) header("Referer", origin) }
            .get()
            .build()

        val html = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            val type = r.header("Content-Type").orEmpty()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("text") &&
                !type.contains("json")
            ) {
                return null
            }
            r.body?.string().orEmpty()
        }
        if (html.isBlank()) return null
        val text = condense(extractReadableText(html), budgetChars)
        if (text.isBlank()) return null
        return PageContent(result.url, result.title, text)
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
            .map { line -> line.replace(Regex("[\\u00A0\\t]+"), " ").replace(Regex(" {2,}"), " ").trim() }
            .filter { it.length > 1 }

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

    /** Convert a table into one line per row. Exposed so it can be exercised directly. */
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

    private fun cellText(cellHtml: String): String {
        val withoutTags = cellHtml.replace(Regex("(?is)<[^>]+>"), " ")
        return decodeEntities(withoutTags).replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Fit a long page into a budget without losing the reason we fetched it.
     *
     * Data rows (pipe-separated lines carrying digits) are kept in full, and the
     * opening prose fills whatever budget remains. Selection happens on a set of
     * line numbers, then output preserves the page's original order.
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

        val rowBudget = (budgetChars * 0.7).toInt()
        for ((index, line) in lines.withIndex()) {
            if (!isDataRow(line)) continue
            if (used + line.length > rowBudget) break
            keep.add(index)
            used += line.length
        }
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
     * Build the context block handed to the model.
     *
     * The date is stated explicitly because a model asked for "the current table"
     * will otherwise answer from its training data and be confidently wrong, and
     * the formatting rules exist because a wall of pasted URLs is not an answer.
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
            appendLine("How to answer:")
            appendLine("- Answer the question directly from the material above.")
            appendLine("- Analyse and summarise it in your own words. Do not dump the raw")
            appendLine("  page text back at the user.")
            appendLine("- Name your sources inside the sentences where they are used, for")
            appendLine("  example \"According to BBC Sport, ...\". The app already shows the")
            appendLine("  links underneath the answer, so NEVER paste a web address into the")
            appendLine("  body of your reply.")
            appendLine("- Quote figures, dates, scores and prices exactly as they appear.")
            appendLine("- If the specific item asked about is not present but related current")
            appendLine("  information is, give what you found and say clearly what is missing.")
            appendLine("  Do not reply only that you do not know.")
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
        private const val DDG_COOLDOWN_MS = 10 * 60 * 1000L

        val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")

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
            s = Regex("&#(\\d{1,5});").replace(s) { m ->
                val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
                if (code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            }
            return s
        }
    }
}
