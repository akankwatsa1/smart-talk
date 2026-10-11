package com.smarttalk.app

/**
 * A provider preset, used as a starting template when creating an account.
 *
 * These mirror the desktop app's providerConfigs block but carry NO API key:
 * keys are typed by the user on the device and stored only in this app's
 * private storage.
 */
data class ProviderConfig(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val maxTokens: Int
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
 * Model names offered as suggestions in Settings. These are hints, not a
 * restriction: the field is free text, because gateways rename models
 * constantly and a hard-coded list would rot within weeks.
 */
val SUGGESTED_CHAT_MODELS = listOf(
    "deepseek-chat",
    "deepseek-reasoner",
    "gpt-4o",
    "gpt-4o-mini",
    "gemini-1.5-flash",
    "anthropic/claude-3.5-sonnet",
    "llama3.2:latest"
)

val SUGGESTED_VISION_MODELS = listOf(
    "gpt-4o",
    "gpt-4o-mini",
    "gemini-1.5-flash",
    "anthropic/claude-3.5-sonnet",
    "qwen2.5-vl-72b-instruct"
)

val SUGGESTED_IMAGE_MODELS = listOf(
    "dall-e-3",
    "gpt-image-1",
    "flux-schnell",
    "flux-dev",
    "stable-diffusion-3.5-large",
    "sdxl"
)

/**
 * Image sizes offered by the generator. The API expects an explicit WxH string
 * for most models, so these are the widely supported values rather than a free
 * text field that would fail at request time.
 */
val IMAGE_SIZES = listOf("1024x1024", "1024x1792", "1792x1024", "512x512")
