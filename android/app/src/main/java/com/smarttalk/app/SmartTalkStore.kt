package com.smarttalk.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * All persistence for SmartTalk. Three stores:
 *  - settings, accounts and API keys -> SharedPreferences, private to the app
 *  - conversations                   -> one JSON file each under filesDir/conversations
 *  - generated and attached images   -> files under filesDir/media
 *
 * Nothing here is ever backed up off the device: the manifest disables backup
 * and data extraction.
 */
class SmartTalkStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val conversationDir: File
        get() = File(context.filesDir, "conversations").apply { if (!exists()) mkdirs() }

    /** Images live here so they can be referenced by path rather than kept in memory. */
    val mediaDir: File
        get() = File(context.filesDir, "media").apply { if (!exists()) mkdirs() }

    // ---------------------------------------------------------------- settings

    fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            val o = JSONObject(raw)

            val accounts = ArrayList<ApiAccount>()
            o.optJSONArray("accounts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { accounts.add(ApiAccount.fromJson(it)) }
                }
            }

            val routes = HashMap<String, String>()
            o.optJSONObject("routes")?.let { r ->
                for (key in r.keys()) {
                    val value = r.optString(key)
                    if (value.isNotBlank()) routes[key] = value
                }
            }

            if (accounts.isEmpty()) return AppSettings()

            AppSettings(
                accounts = accounts,
                routes = routes,
                temperature = o.optDouble("temperature", 0.7),
                topP = o.optDouble("topP", 1.0),
                systemPrompt = o.optString("systemPrompt", DEFAULT_SYSTEM_PROMPT),
                searchMode = SearchMode.fromId(o.optString("searchMode", "auto")),
                fetchPageContent = o.optBoolean("fetchPageContent", true),
                maxSearchResults = o.optInt("maxSearchResults", 5),
                maxPagesToFetch = o.optInt("maxPagesToFetch", 3),
                darkTheme = o.optBoolean("darkTheme", true),
                useDeviceVoice = o.optBoolean("useDeviceVoice", true),
                imageSize = o.optString("imageSize", "1024x1024")
            )
        } catch (t: Throwable) {
            AppSettings()
        }
    }

    fun saveSettings(s: AppSettings) {
        val o = JSONObject().apply {
            put("accounts", JSONArray().apply { s.accounts.forEach { put(it.toJson()) } })
            put("routes", JSONObject().apply { s.routes.forEach { (k, v) -> put(k, v) } })
            put("temperature", s.temperature)
            put("topP", s.topP)
            put("systemPrompt", s.systemPrompt)
            put("searchMode", s.searchMode.id)
            put("fetchPageContent", s.fetchPageContent)
            put("maxSearchResults", s.maxSearchResults)
            put("maxPagesToFetch", s.maxPagesToFetch)
            put("darkTheme", s.darkTheme)
            put("useDeviceVoice", s.useDeviceVoice)
            put("imageSize", s.imageSize)
        }
        prefs.edit().putString(KEY_SETTINGS, o.toString()).apply()
    }

    /**
     * Import a key saved by an older build of this app, which stored a single
     * active provider plus per-provider overrides. Without this, anyone who had
     * already typed a key would silently lose it on upgrade.
     */
    fun migrateLegacySettingsIfNeeded(): AppSettings? {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return null
        return try {
            val o = JSONObject(raw)
            if (o.has("accounts")) return null

            val providerId = o.optString("providerId", "")
            if (providerId.isBlank()) return null

            val overrides = o.optJSONObject("overrides")?.optJSONObject(providerId)
            val base = BUILT_IN_PROVIDERS.firstOrNull { it.id == providerId }
                ?: BUILT_IN_PROVIDERS.last()

            val account = ApiAccount(
                id = ApiAccount.newId(),
                label = base.label,
                baseUrl = overrides?.optString("baseUrl")?.takeIf { it.isNotBlank() } ?: base.baseUrl,
                model = overrides?.optString("model")?.takeIf { it.isNotBlank() } ?: base.model,
                apiKey = overrides?.optString("apiKey").orEmpty(),
                maxTokens = overrides?.optInt("maxTokens") ?: base.maxTokens,
                presetId = base.id
            )

            val migrated = AppSettings(
                accounts = listOf(account),
                temperature = o.optDouble("temperature", 0.7),
                topP = o.optDouble("topP", 1.0),
                systemPrompt = o.optString("systemPrompt", DEFAULT_SYSTEM_PROMPT),
                searchMode = if (o.optBoolean("webSearchEnabled", false)) {
                    SearchMode.Always
                } else {
                    SearchMode.Auto
                },
                darkTheme = o.optBoolean("darkTheme", true)
            )
            saveSettings(migrated)
            migrated
        } catch (t: Throwable) {
            null
        }
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

    /** Destructive, and only called after the UI has confirmed it with the user. */
    fun deleteConversation(id: String) {
        runCatching { File(conversationDir, "$id.json").delete() }
    }

    // ------------------------------------------------------------------- media

    /** A new empty file in the media directory, for the camera to write into. */
    fun newImageFile(prefix: String): File {
        val stamp = System.currentTimeMillis()
        return File(mediaDir, "${prefix}_$stamp.jpg")
    }

    fun writeMedia(name: String, bytes: ByteArray): File {
        val file = File(mediaDir, name)
        file.writeBytes(bytes)
        return file
    }

    fun deleteMedia(path: String) {
        runCatching { File(path).takeIf { it.exists() && it.parentFile == mediaDir }?.delete() }
    }

    /** Remove media files no longer referenced by any saved conversation. */
    fun pruneOrphanedMedia(referenced: Set<String>) {
        runCatching {
            mediaDir.listFiles()?.forEach { file ->
                if (file.absolutePath !in referenced) file.delete()
            }
        }
    }

    // ------------------------------------------------------------ share/export

    /** Plain-text export used by the share sheet, so a chat can leave the app. */
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
            m.attachments.forEach { a ->
                appendLine()
                appendLine("[attachment: ${a.displayName}]")
            }
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
