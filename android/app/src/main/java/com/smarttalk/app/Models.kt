package com.smarttalk.app

import org.json.JSONArray
import org.json.JSONObject

/** Who authored a message, mapped to the wire value used by chat-completions APIs. */
enum class Role(val wire: String) {
    System("system"),
    User("user"),
    Assistant("assistant");

    companion object {
        fun fromWire(value: String): Role =
            entries.firstOrNull { it.wire == value } ?: Assistant
    }
}

/** A single web result used to ground an answer. */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("snippet", snippet)
        put("url", url)
        put("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject): SearchResult = SearchResult(
            title = o.optString("title"),
            snippet = o.optString("snippet"),
            url = o.optString("url"),
            source = o.optString("source")
        )
    }
}

/**
 * One turn in a conversation. [sources] is non-empty only for answers that were
 * grounded by a web search, so the UI can offer the citations as real buttons.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    val sources: List<SearchResult> = emptyList(),
    val failed: Boolean = false,
    val id: Long = nextId()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role.wire)
        put("content", content)
        put("failed", failed)
        if (sources.isNotEmpty()) {
            put("sources", JSONArray().apply { sources.forEach { put(it.toJson()) } })
        }
    }

    companion object {
        private var counter = 0L
        fun nextId(): Long = System.nanoTime() + (counter++)

        fun fromJson(o: JSONObject): ChatMessage {
            val rawSources = o.optJSONArray("sources")
            val list = ArrayList<SearchResult>()
            if (rawSources != null) {
                for (i in 0 until rawSources.length()) {
                    rawSources.optJSONObject(i)?.let { list.add(SearchResult.fromJson(it)) }
                }
            }
            return ChatMessage(
                role = Role.fromWire(o.optString("role", "assistant")),
                content = o.optString("content"),
                sources = list,
                failed = o.optBoolean("failed", false)
            )
        }
    }
}

/** A saved conversation. [id] is a stable filesystem-safe identifier. */
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("updatedAt", updatedAt)
        put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Conversation {
            val arr = o.optJSONArray("messages")
            val msgs = ArrayList<ChatMessage>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { msgs.add(ChatMessage.fromJson(it)) }
                }
            }
            return Conversation(
                id = o.optString("id", newId()),
                title = o.optString("title", "New chat"),
                messages = msgs,
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }

        fun newId(): String = "c" + System.currentTimeMillis() + "-" +
            (1000..9999).random()
    }
}

/** Derive a short conversation title from its first user message. */
fun titleFrom(text: String): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    if (flat.isEmpty()) return "New chat"
    return if (flat.length <= 48) flat else flat.take(47).trimEnd() + "\u2026"
}
