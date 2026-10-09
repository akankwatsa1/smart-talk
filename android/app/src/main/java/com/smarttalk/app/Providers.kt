package com.smarttalk.app

/**
 * A provider preset. These mirror the desktop app's providerConfigs block, but
 * deliberately carry NO API key: keys are typed by the user on the device and
 * stored only in this app's private storage.
 */
data class ProviderConfig(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val maxTokens: Int,
    val apiKey: String = ""
)

/** Per-provider user overrides, so switching providers remembers each key. */
data class ProviderOverride(
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val maxTokens: Int? = null
)

val BUILT_IN_PROVIDERS: List<ProviderConfig> = listOf(
    ProviderConfig(
        id = "deepseek",
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com/chat/completions",
        model = "deepseek-chat",
        maxTokens = 8192
    ),
    ProviderConfig(
        id = "tokenharbor",
        label = "TokenHarbor",
        baseUrl = "https://tokenharbor.ai/v1/chat/completions",
        model = "deepseek-v4.1-flash:free",
        maxTokens = 8192
    ),
    ProviderConfig(
        id = "apinex",
        label = "APINex",
        baseUrl = "https://api.apinex.bond/v1/chat/completions",
        model = "deepseek-v4.1-flash",
        maxTokens = 8192
    ),
    ProviderConfig(
        id = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1/chat/completions",
        model = "gpt-4o",
        maxTokens = 4096
    ),
    ProviderConfig(
        id = "gemini",
        label = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        model = "gemini-1.5-flash",
        maxTokens = 8192
    ),
    ProviderConfig(
        id = "openrouter",
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/chat/completions",
        model = "anthropic/claude-3.5-sonnet",
        maxTokens = 8192
    ),
    ProviderConfig(
        id = "anthropic",
        label = "Anthropic (via OpenRouter)",
        baseUrl = "https://openrouter.ai/api/v1/chat/completions",
        model = "anthropic/claude-3.5-sonnet",
        maxTokens = 8192
    ),
    ProviderConfig(
        id = "ollama",
        label = "Ollama (on this device or LAN)",
        baseUrl = "http://localhost:11434/v1/chat/completions",
        model = "llama3.2:latest",
        maxTokens = 4096
    ),
    ProviderConfig(
        id = "custom",
        label = "Custom (OpenAI-compatible)",
        baseUrl = "",
        model = "",
        maxTokens = 8192
    )
)

const val DEFAULT_SYSTEM_PROMPT =
    "You are SmartTalk, a knowledgeable, concise, and helpful conversational AI " +
        "assistant. You answer thoroughly with structured formatting, clear hierarchical " +
        "markdown headings (use ## and ### where appropriate to structure your responses " +
        "for readability and screen readers), clear code blocks, and precise explanations."

/**
 * Everything the user can configure. Persisted as JSON in SharedPreferences.
 */
data class AppSettings(
    val providerId: String = "deepseek",
    val overrides: Map<String, ProviderOverride> = emptyMap(),
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val webSearchEnabled: Boolean = false,
    val darkTheme: Boolean = true,
    val maxSearchResults: Int = 5
) {
    /** The active provider with the user's overrides applied. */
    fun activeProvider(): ProviderConfig {
        val base = BUILT_IN_PROVIDERS.firstOrNull { it.id == providerId }
            ?: BUILT_IN_PROVIDERS[0]
        val o = overrides[providerId]
        return base.copy(
            baseUrl = o?.baseUrl?.takeIf { it.isNotBlank() } ?: base.baseUrl,
            apiKey = o?.apiKey ?: base.apiKey,
            model = o?.model?.takeIf { it.isNotBlank() } ?: base.model,
            maxTokens = o?.maxTokens ?: base.maxTokens
        )
    }

    fun overrideFor(id: String): ProviderOverride = overrides[id] ?: ProviderOverride()

    fun withOverride(id: String, o: ProviderOverride): AppSettings =
        copy(overrides = overrides + (id to o))
}
