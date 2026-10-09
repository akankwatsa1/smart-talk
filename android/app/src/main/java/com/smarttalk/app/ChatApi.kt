package com.smarttalk.app

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to any OpenAI-compatible /chat/completions endpoint with server-sent
 * event streaming.
 *
 * On the desktop this work was done by a local Python proxy that existed to
 * dodge browser CORS rules and to hide the key from page scripts. Android has
 * neither problem, so the proxy is deliberately not ported: the app talks to
 * the provider directly.
 */
class ChatApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Normalise whatever the user typed into a full chat-completions URL.
     * Ported from the desktop proxy's normalize_target_url so both apps accept
     * the same shorthand input.
     */
    fun normalizeEndpoint(url: String): String {
        val clean = url.trim().removeSuffix("/")
        if (clean.isEmpty()) return ""
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") || clean.endsWith("/v1beta/openai") -> "$clean/chat/completions"
            clean.contains("/v1/") -> clean.replace(Regex("/v1/.*$"), "/v1/chat/completions")
            !clean.contains("/chat/completions") -> "$clean/v1/chat/completions"
            else -> clean
        }
    }

    /** A handle the UI can cancel, so "Stop generating" genuinely stops the request. */
    private var activeCall: Call? = null

    fun cancel() {
        try {
            activeCall?.cancel()
        } catch (t: Throwable) {
            // already finished
        }
        activeCall = null
    }

    /**
     * Streams a completion. [onDelta] is invoked on the OkHttp worker thread for
     * each incremental piece of text; the caller is responsible for hopping to
     * the main thread.
     */
    fun streamChat(
        provider: ProviderConfig,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Double,
        topP: Double,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val endpoint = normalizeEndpoint(provider.baseUrl)
        if (endpoint.isEmpty()) {
            onError("No API base URL set for ${provider.label}.")
            return
        }
        if (provider.apiKey.isBlank() && !isLocalEndpoint(endpoint)) {
            onError("No API key set for ${provider.label}.")
            return
        }

        val messages = JSONArray().apply {
            if (systemPrompt.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            history.forEach { m ->
                if (m.role != Role.System && m.content.isNotBlank()) {
                    put(JSONObject().put("role", m.role.wire).put("content", m.content))
                }
            }
        }

        val body = JSONObject().apply {
            put("model", provider.model)
            put("messages", messages)
            put("stream", true)
            put("temperature", temperature)
            put("top_p", topP)
            if (provider.maxTokens > 0) put("max_tokens", provider.maxTokens)
        }

        val builder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")

        if (provider.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${provider.apiKey}")
            // Some gateways only honour x-api-key; the desktop proxy sent both.
            builder.header("x-api-key", provider.apiKey)
        }

        val call = client.newCall(builder.build())
        activeCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCall = null
                if (call.isCanceled()) onComplete() else onError(describe(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val payload = r.body
                        if (!r.isSuccessful) {
                            val detail = payload?.string()?.take(600).orEmpty()
                            activeCall = null
                            onError(httpMessage(r.code, detail))
                            return
                        }
                        if (payload == null) {
                            activeCall = null
                            onError("The provider returned an empty response.")
                            return
                        }

                        val contentType = r.header("Content-Type").orEmpty()
                        if (contentType.contains("text/event-stream", ignoreCase = true)) {
                            readEventStream(payload.source(), onDelta)
                        } else {
                            // Some gateways ignore stream=true and answer with one JSON
                            // body, or with unlabelled SSE lines. Handle both.
                            val text = payload.string()
                            if (text.contains("data:")) {
                                emitFromRawSse(text, onDelta)
                            } else {
                                extractWholeCompletion(text)?.let(onDelta)
                            }
                        }
                        activeCall = null
                        onComplete()
                    }
                } catch (t: Throwable) {
                    activeCall = null
                    if (call.isCanceled()) onComplete() else onError(describe(t))
                }
            }
        })
    }

    /** Reads an SSE body line by line, emitting each content delta. */
    private fun readEventStream(source: okio.BufferedSource, onDelta: (String) -> Unit) {
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty()) continue
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break
            val piece = extractDelta(data) ?: continue
            if (piece.isNotEmpty()) onDelta(piece)
        }
    }

    /** Fallback for a body containing SSE lines that was not labelled as such. */
    private fun emitFromRawSse(text: String, onDelta: (String) -> Unit) {
        text.lineSequence().forEach { line ->
            if (line.startsWith("data:")) {
                val data = line.removePrefix("data:").trim()
                if (data.isNotEmpty() && data != "[DONE]") {
                    extractDelta(data)?.let { if (it.isNotEmpty()) onDelta(it) }
                }
            }
        }
    }

    /** Pull the text out of one streamed chunk, tolerating both common shapes. */
    private fun extractDelta(json: String): String? = try {
        val o = JSONObject(json)
        val choices = o.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            null
        } else {
            val first = choices.optJSONObject(0)
            val delta = first?.optJSONObject("delta")
            val fromDelta = delta?.optString("content")
            val fromMessage = first?.optJSONObject("message")?.optString("content")
            val fromText = first?.optString("text")
            when {
                !fromDelta.isNullOrEmpty() -> fromDelta
                delta != null && delta.has("content") -> ""
                !fromMessage.isNullOrEmpty() -> fromMessage
                !fromText.isNullOrEmpty() -> fromText
                else -> null
            }
        }
    } catch (t: Throwable) {
        // A non-JSON keepalive line: not an error, just nothing to show.
        null
    }

    /** Read a complete, non-streaming chat-completions response. */
    private fun extractWholeCompletion(text: String): String? = try {
        val o = JSONObject(text)
        val choices = o.optJSONArray("choices")
        var out: String? = null
        if (choices != null && choices.length() > 0) {
            val first = choices.optJSONObject(0)
            out = first?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotEmpty() }
                ?: first?.optString("text")?.takeIf { it.isNotEmpty() }
        }
        if (out == null) {
            // Surface the provider's own error sentence rather than a blank bubble.
            val err = o.optJSONObject("error")?.optString("message")
            if (!err.isNullOrBlank()) out = "Provider error: $err"
        }
        out
    } catch (t: Throwable) {
        null
    }

    private fun isLocalEndpoint(endpoint: String): Boolean =
        endpoint.startsWith("http://localhost") ||
            endpoint.startsWith("http://127.0.0.1") ||
            endpoint.startsWith("http://10.0.2.2")

    private fun describe(t: Throwable): String = when (t) {
        is java.net.UnknownHostException ->
            "Cannot resolve that server address. Check the API base URL in Settings."
        is java.net.SocketTimeoutException ->
            "The provider took too long to respond. Try again, or pick a faster model."
        is javax.net.ssl.SSLException ->
            "Secure connection failed. Check the API base URL in Settings."
        else -> "Connection failed: ${t.message ?: t.javaClass.simpleName}"
    }

    private fun httpMessage(code: Int, detail: String): String {
        val hint = when (code) {
            401, 403 -> " The API key was rejected. Check it in Settings."
            404 -> " The endpoint URL looks wrong. Check the API base URL in Settings."
            429 -> " Rate limited or out of quota. Wait a moment and retry."
            in 500..599 -> " The provider had a server error. Retry shortly."
            else -> ""
        }
        val trimmed = detail.trim().let { if (it.length > 400) it.take(400) + "\u2026" else it }
        return "Provider returned HTTP $code.$hint" + if (trimmed.isNotEmpty()) "\n\n$trimmed" else ""
    }

    companion object {
        private const val READ_TIMEOUT_SECONDS = 180L
        private const val USER_AGENT = "SmartTalk/1.0 (Android)"
    }
}
