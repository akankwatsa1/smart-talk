package com.smarttalk.app

/**
 * A deliberately small markdown parser. The desktop app leaned on a JavaScript
 * markdown library; pulling an equivalent Android dependency would add weight and
 * another thing to break, so this handles the subset the assistant actually
 * emits: headings, fenced code, lists, quotes, rules, and inline emphasis.
 *
 * Links are kept as literal text plus a separate source list, because real
 * buttons are far friendlier to TalkBack than a tappable span inside a sentence.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val FENCE = Regex("^\\s*```(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])\\1{2,}\\s*$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")

fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val lines = source.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block
        val fence = FENCE.find(line)
        if (fence != null) {
            flushParagraph()
            val language = fence.groupValues[1].trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !FENCE.containsMatchIn(lines[i])) {
                if (code.isNotEmpty()) code.append("\n")
                code.append(lines[i])
                i++
            }
            if (i < lines.size) i++ // consume closing fence
            blocks.add(MdBlock.Code(language, code.toString()))
            continue
        }

        if (line.isBlank()) {
            flushParagraph()
            i++
            continue
        }

        val heading = HEADING.find(line)
        if (heading != null) {
            flushParagraph()
            blocks.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
            i++
            continue
        }

        if (RULE.matches(line)) {
            flushParagraph()
            blocks.add(MdBlock.Rule)
            i++
            continue
        }

        val quote = QUOTE.find(line)
        if (quote != null) {
            flushParagraph()
            val quoteLines = StringBuilder()
            while (i < lines.size) {
                val q = QUOTE.find(lines[i]) ?: break
                if (quoteLines.isNotEmpty()) quoteLines.append("\n")
                quoteLines.append(q.groupValues[1])
                i++
            }
            blocks.add(MdBlock.Quote(quoteLines.toString()))
            continue
        }

        val bullet = BULLET.find(line)
        val ordered = ORDERED.find(line)
        if (bullet != null || ordered != null) {
            flushParagraph()
            val isOrdered = bullet == null
            val items = ArrayList<String>()
            while (i < lines.size) {
                val b = BULLET.find(lines[i])
                val o = ORDERED.find(lines[i])
                if (b != null) {
                    items.add(b.groupValues[1].trim())
                } else if (o != null) {
                    items.add(o.groupValues[1].trim())
                } else if (lines[i].isNotBlank() && lines[i].startsWith("  ") && items.isNotEmpty()) {
                    items[items.size - 1] = items[items.size - 1] + " " + lines[i].trim()
                } else {
                    break
                }
                i++
            }
            blocks.add(MdBlock.Bullets(items, isOrdered))
            continue
        }

        if (paragraph.isNotEmpty()) paragraph.append("\n")
        paragraph.append(line.trim())
        i++
    }
    flushParagraph()
    return blocks
}

/** Inline emphasis ranges, resolved to plain text plus markers for styling. */
data class InlineSpan(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind { Bold, Italic, Code }
}

data class InlineText(val text: String, val spans: List<InlineSpan>)

private val INLINE = Regex(
    "(`[^`\\n]+`)" +          // 1 code
        "|(\\*\\*[^*\\n]+\\*\\*)" + // 2 bold
        "|(__[^_\\n]+__)" +        // 3 bold
        "|(\\*[^*\\n]+\\*)" +      // 4 italic
        "|(_[^_\\n]+_)" +          // 5 italic
        "|(\\[[^\\]\\n]+]\\([^)\\s]+\\))" // 6 link
)

/**
 * Strips markdown inline markers and records which output ranges should be
 * styled. Stripping first means the plain string is what a screen reader reads,
 * with no stray asterisks or backticks in the middle of a sentence.
 */
fun parseInline(source: String): InlineText {
    val out = StringBuilder()
    val spans = ArrayList<InlineSpan>()

    var index = 0
    while (index < source.length) {
        val match = INLINE.find(source, index)
        if (match == null) {
            out.append(source, index, source.length)
            break
        }
        if (match.range.first > index) {
            out.append(source, index, match.range.first)
        }
        val token = match.value
        val start = out.length
        when {
            match.groupValues[1].isNotEmpty() -> {
                out.append(token.removeSurrounding("`"))
                spans.add(InlineSpan(start, out.length, InlineSpan.Kind.Code))
            }

            match.groupValues[2].isNotEmpty() -> {
                out.append(token.removeSurrounding("**"))
                spans.add(InlineSpan(start, out.length, InlineSpan.Kind.Bold))
            }

            match.groupValues[3].isNotEmpty() -> {
                out.append(token.removeSurrounding("__"))
                spans.add(InlineSpan(start, out.length, InlineSpan.Kind.Bold))
            }

            match.groupValues[4].isNotEmpty() -> {
                out.append(token.removeSurrounding("*"))
                spans.add(InlineSpan(start, out.length, InlineSpan.Kind.Italic))
            }

            match.groupValues[5].isNotEmpty() -> {
                out.append(token.removeSurrounding("_"))
                spans.add(InlineSpan(start, out.length, InlineSpan.Kind.Italic))
            }

            else -> {
                // [label](url) -> label (url), so the address stays visible and copyable.
                val label = token.substringAfter("[").substringBefore("]")
                val url = token.substringAfter("(").removeSuffix(")")
                out.append(if (label == url) url else "$label ($url)")
                spans.add(InlineSpan(start, out.length, InlineSpan.Kind.Italic))
            }
        }
        index = match.range.last + 1
    }
    return InlineText(out.toString(), spans)
}

/** Every http(s) URL mentioned anywhere in a message, de-duplicated in order. */
fun extractUrls(source: String): List<String> {
    val found = Regex("https?://[^\\s)\\]]+").findAll(source).map { it.value.trimEnd('.', ',', ';', ':') }
    return found.distinct().toList()
}

/**
 * Replace raw web addresses in an answer with a readable label.
 *
 * A reply that pastes bare URLs is not an analysis, and long addresses wreck the
 * reading experience and are painful under TalkBack. Addresses that match a cited
 * source become that source's name; anything else becomes its domain. The real,
 * tappable links are rendered as buttons beneath the message, so nothing is lost.
 */
fun replaceUrlsWithLabels(text: String, sources: List<SearchResult>): String {
    if (text.isEmpty() || !text.contains("http")) return text
    val pattern = Regex("https?://[^\\s)\\]>]+")
    return pattern.replace(text) { match ->
        val withJunk = match.value
        val clean = withJunk.trimEnd('.', ',', ';', ':', '!', '?')
        val trailing = withJunk.removePrefix(clean)
        val known = sources.firstOrNull {
            it.url.trimEnd('/').equals(clean.trimEnd('/'), ignoreCase = true)
        }
        val label = known?.source?.takeIf { it.isNotBlank() }
            ?: known?.title?.take(40)?.takeIf { it.isNotBlank() }
            ?: domainLabel(clean)
        "$label$trailing"
    }
}

private fun domainLabel(url: String): String =
    runCatching { java.net.URI(url).host?.removePrefix("www.").orEmpty() }
        .getOrDefault("")
        .ifBlank { "source" }
