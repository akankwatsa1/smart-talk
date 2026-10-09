package com.smarttalk.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * All persistence for SmartTalk. Two stores:
 *  - settings + API keys -> SharedPreferences (private to the app)
 *  - conversations       -> one JSON file each under filesDir/conversations
 *
 * Nothing here is ever backed up off the device: the manifest disables backup
 * and data extraction.
 */
class SmartTalkStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val conversationDir: File
        get() = File(context.filesDir, "conversations").apply { if (!exists()) mkdirs() }

    // ---------------------------------------------------------------- settings

    fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            val o = JSONObject(raw)
            val overridesMap = HashMap<String, ProviderOverride>()
            val ov = o.optJSONObject("overrides")
            if (ov != null) {
                for (key in ov.keys()) {
                    val e = ov.optJSONObject(key) ?: continue
                    overridesMap[key] = ProviderOverride(
                        baseUrl = e.optStringOrNull("baseUrl"),
                        apiKey = e.optStringOrNull("apiKey"),
                        model = e.optStringOrNull("model"),
                        maxTokens = if (e.has("maxTokens")) e.optInt("maxTokens") else null
                    )
                }
            }
            AppSettings(
                providerId = o.optString("providerId", "deepseek"),
                overrides = overridesMap,
                temperature = o.optDouble("temperature", 0.7),
                topP = o.optDouble("topP", 1.0),
                systemPrompt = o.optString("systemPrompt", DEFAULT_SYSTEM_PROMPT),
                webSearchEnabled = o.optBoolean("webSearchEnabled", false),
                darkTheme = o.optBoolean("darkTheme", true),
                maxSearchResults = o.optInt("maxSearchResults", 5)
            )
        } catch (t: Throwable) {
            AppSettings()
        }
    }

    fun saveSettings(s: AppSettings) {
        val o = JSONObject().apply {
            put("providerId", s.providerId)
            put("temperature", s.temperature)
            put("topP", s.topP)
            put("systemPrompt", s.systemPrompt)
            put("webSearchEnabled", s.webSearchEnabled)
            put("darkTheme", s.darkTheme)
            put("maxSearchResults", s.maxSearchResults)
            put("overrides", JSONObject().apply {
                s.overrides.forEach { (id, ov) ->
                    put(id, JSONObject().apply {
                        ov.baseUrl?.let { put("baseUrl", it) }
                        ov.apiKey?.let { put("apiKey", it) }
                        ov.model?.let { put("model", it) }
                        ov.maxTokens?.let { put("maxTokens", it) }
                    })
                }
            })
        }
        prefs.edit().putString(KEY_SETTINGS, o.toString()).apply()
    }

    // ----------------------------------------------------------- conversations

    fun listConversations(): List<Conversation> {
        val files = conversationDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.mapNotNull { readConversationFile(it) }
            .sortedByDescending { it.updatedAt }
    }

    fun loadConversation(id: String): Conversation? =
        readConversationFile(File(conversationDir, "$id.json"))

    private fun readConversationFile(f: File): Conversation? = try {
        if (f.exists()) Conversation.fromJson(JSONObject(f.readText())) else null
    } catch (t: Throwable) {
        null
    }

    fun saveConversation(c: Conversation) {
        try {
            File(conversationDir, "${c.id}.json").writeText(c.toJson().toString())
        } catch (t: Throwable) {
            // A failed save must never crash a conversation in progress.
        }
    }

    /**
     * Two doors, deliberately separate:
     *  - [deleteConversation] removes the saved chat but is the only destructive
     *    action, and the UI confirms it with a dialog before calling this.
     */
    fun deleteConversation(id: String) {
        try {
            File(conversationDir, "$id.json").delete()
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun deleteAllConversations() {
        conversationDir.listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------------ share/export

    /** Plain-text export used by the share sheet, so a chat can leave the app on purpose. */
    fun exportAsText(c: Conversation): String = buildString {
        appendLine("# ${c.title}")
        appendLine()
        c.messages.forEach { m ->
            val who = when (m.role) {
                Role.User -> "You"
                Role.Assistant -> "SmartTalk"
                Role.System -> "System"
            }
            appendLine("## $who")
            appendLine()
            appendLine(m.content)
            if (m.sources.isNotEmpty()) {
                appendLine()
                m.sources.forEach { appendLine("- ${it.title} (${it.url})") }
            }
            appendLine()
        }
    }

    companion object {
        private const val PREFS = "smarttalk_prefs"
        private const val KEY_SETTINGS = "settings_v1"
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

/** Convenience for building a JSON array from a list. */
fun <T> Iterable<T>.toJsonArray(mapper: (T) -> JSONObject): JSONArray =
    JSONArray().apply { forEach { put(mapper(it)) } }
