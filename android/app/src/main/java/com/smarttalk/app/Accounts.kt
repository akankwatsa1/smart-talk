package com.smarttalk.app

import org.json.JSONObject

/**
 * A task the app can delegate to an API account. Each one can be pointed at a
 * different account, so a cheap fast model can serve chat while a vision model
 * serves photos and an image model serves generation.
 */
enum class Capability(val id: String, val label: String, val hint: String) {
    Chat(
        "chat",
        "Chat",
        "Text conversation"
    ),
    Vision(
        "vision",
        "Image understanding",
        "Describe a photo or read text out of one"
    ),
    ImageGen(
        "imagegen",
        "Image generation",
        "Create a picture from a written description"
    ),
    Tts(
        "tts",
        "Cloud read-aloud voice",
        "Optional. Leave unset to use the free on-device voice"
    );

    companion object {
        fun fromId(id: String): Capability? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One API account: an endpoint plus a key plus a model.
 *
 * Accounts are separate from providers on purpose. A provider is a company; an
 * account is one credential you hold with them. Holding two keys with the same
 * provider - to spread rate limits, or to use different models - is normal, and
 * the old single-provider model could not express that.
 */
data class ApiAccount(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val maxTokens: Int = 8192,
    /** Which preset this was created from, for display only. */
    val presetId: String = "custom"
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() &&
            (apiKey.isNotBlank() || isLocalEndpoint)

    val isLocalEndpoint: Boolean
        get() = baseUrl.startsWith("http://localhost") ||
            baseUrl.startsWith("http://127.0.0.1") ||
            baseUrl.startsWith("http://10.0.2.2")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("baseUrl", baseUrl)
        put("model", model)
        put("apiKey", apiKey)
        put("maxTokens", maxTokens)
        put("presetId", presetId)
    }

    companion object {
        fun fromJson(o: JSONObject) = ApiAccount(
            id = o.optString("id", newId()),
            label = o.optString("label", "Account"),
            baseUrl = o.optString("baseUrl"),
            model = o.optString("model"),
            apiKey = o.optString("apiKey"),
            maxTokens = o.optInt("maxTokens", 8192),
            presetId = o.optString("presetId", "custom")
        )

        fun newId(): String = "a" + System.currentTimeMillis() + "-" + (1000..9999).random()

        fun fromPreset(preset: ProviderConfig, labelSuffix: String = ""): ApiAccount = ApiAccount(
            id = newId(),
            label = preset.label + labelSuffix,
            baseUrl = preset.baseUrl,
            model = preset.model,
            apiKey = "",
            maxTokens = preset.maxTokens,
            presetId = preset.id
        )
    }
}

/**
 * Derive the image-generation endpoint from a chat endpoint.
 *
 * Almost every OpenAI-compatible gateway exposes generation at
 * `/v1/images/generations` beside `/v1/chat/completions`, so the account's base
 * URL can be reused rather than asking the user for a second URL.
 */
fun imagesEndpointFor(baseUrl: String): String {
    val clean = baseUrl.trim().removeSuffix("/")
    if (clean.isEmpty()) return ""
    return when {
        clean.endsWith("/images/generations") -> clean
        clean.endsWith("/chat/completions") ->
            clean.removeSuffix("/chat/completions") + "/images/generations"
        clean.endsWith("/v1") -> "$clean/images/generations"
        clean.contains("/v1/") ->
            clean.replace(Regex("/v1/.*$"), "/v1/images/generations")
        else -> "$clean/v1/images/generations"
    }
}

/** How eagerly the app should ground an answer in live web results. */
enum class SearchMode(val id: String, val label: String) {
    Off("off", "Off"),
    Auto("auto", "Automatic"),
    Always("always", "Always");

    companion object {
        fun fromId(id: String): SearchMode = entries.firstOrNull { it.id == id } ?: Auto
    }
}

/**
 * Words that reliably signal a question whose answer changes over time. Used by
 * [SearchMode.Auto] so a question about "the current table" is grounded without
 * the user having to remember to switch search on.
 */
private val TIME_SENSITIVE = listOf(
    "current", "currently", "today", "tonight", "yesterday", "tomorrow",
    "latest", "recent", "recently", "now", "this week", "this month", "this year",
    "standings", "table", "league", "score", "scores", "result", "results",
    "fixture", "fixtures", "match", "matches", "who won", "winner",
    "news", "update", "up to date", "up-to-date", "live",
    "price", "prices", "cost", "costs", "rate", "rates", "exchange",
    "weather", "forecast", "stock", "share price", "market",
    "release", "released", "launch", "launched", "version", "election",
    "president", "prime minister", "ceo", "ceo of", "how many", "population",
    "as of", "in 2025", "in 2026", "in 2027"
)

/**
 * Whole-word patterns, deliberately not a substring test.
 *
 * A substring test looks harmless and is wrong: "now" occurs inside "know" and
 * "knowledge", so "I don't know, explain recursion" used to fire a pointless web
 * search and slow down every answer containing those words. Verified against a
 * table of should-search and should-not-search cases.
 */
private val TIME_SENSITIVE_PATTERNS: List<Regex> = TIME_SENSITIVE.map { keyword ->
    Regex("(?<![a-z])" + Regex.escape(keyword) + "(?![a-z])")
}

/** A bare four-digit year from 2024 onward usually means "tell me current facts". */
private val RECENT_YEAR = Regex("\\b20(2[4-9]|[3-9]\\d)\\b")

fun looksTimeSensitive(prompt: String): Boolean {
    val p = prompt.lowercase()
    if (TIME_SENSITIVE_PATTERNS.any { it.containsMatchIn(p) }) return true
    return RECENT_YEAR.containsMatchIn(p)
}

/**
 * Everything the user can configure.
 *
 * Note [routes]: each capability maps to an account id. A missing entry means
 * "use the chat account", so an untouched install behaves sensibly.
 */
data class AppSettings(
    val accounts: List<ApiAccount> = defaultAccounts(),
    val routes: Map<String, String> = emptyMap(),
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val searchMode: SearchMode = SearchMode.Auto,
    val fetchPageContent: Boolean = true,
    val maxSearchResults: Int = 5,
    val maxPagesToFetch: Int = 3,
    val darkTheme: Boolean = true,
    val useDeviceVoice: Boolean = true,
    /** Requested pixel size for generated images, e.g. "1024x1024". */
    val imageSize: String = "1024x1024"
) {
    fun accountById(id: String?): ApiAccount? =
        id?.let { target -> accounts.firstOrNull { it.id == target } }

    /** The account serving [capability], falling back to the chat account. */
    fun accountFor(capability: Capability): ApiAccount? {
        accountById(routes[capability.id])?.let { return it }
        if (capability == Capability.Chat) return accounts.firstOrNull()
        return accounts.firstOrNull()
    }

    fun chatAccount(): ApiAccount = accounts.firstOrNull()
        ?: ApiAccount.fromPreset(BUILT_IN_PROVIDERS.first { it.id == "deepseek" })

    /** True when [capability] has its own dedicated account rather than the default. */
    fun hasDedicatedAccount(capability: Capability): Boolean =
        accountById(routes[capability.id]) != null

    fun withRoute(capability: Capability, accountId: String?): AppSettings {
        val next = routes.toMutableMap()
        if (accountId == null) next.remove(capability.id) else next[capability.id] = accountId
        return copy(routes = next)
    }

    fun withAccountReplaced(account: ApiAccount): AppSettings {
        val idx = accounts.indexOfFirst { it.id == account.id }
        val next = if (idx >= 0) {
            accounts.toMutableList().also { it[idx] = account }
        } else {
            accounts + account
        }
        return copy(accounts = next)
    }

    fun withAccountRemoved(accountId: String): AppSettings {
        val next = accounts.filterNot { it.id == accountId }.ifEmpty { defaultAccounts() }
        // Any capability pointing at the deleted account falls back to the default.
        val nextRoutes = routes.filterValues { it != accountId }
        return copy(accounts = next, routes = nextRoutes)
    }
}

/** One account per major provider, with no keys, so the app is usable immediately. */
fun defaultAccounts(): List<ApiAccount> = listOf(
    ApiAccount.fromPreset(BUILT_IN_PROVIDERS.first { it.id == "deepseek" })
)
