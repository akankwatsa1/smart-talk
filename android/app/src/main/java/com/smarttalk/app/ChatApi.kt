package com.smarttalk.app

import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to any OpenAI-compatible /chat/completions endpoint with server-sent
 * event streaming, including image inputs for vision accounts.
 *
 * On the desktop this work was done by a local Python proxy that existed to
 * dodge browser CORS rules and to hide the key from page scripts. Android has
 * neither problem, so the proxy is deliberately not ported.
 */
class ChatApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private var activeCall: Call? = null

    /** Stop the in-flight request, so "Stop" genuinely stops. */
    fun cancel() {
        runCatching { activeCall?.cancel() }
        activeCall = null
    }

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

    /**
     * Streams a completion. [onDelta] is invoked on the OkHttp worker thread for
     * each incremental piece of text; the caller hops to the main thread.
     */
    fun streamChat(
        account: ApiAccount,
        systemPrompt: String,
        history: List<ChatMessage>,
        temperature: Double,
        topP: Double,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val endpoint = normalizeEndpoint(account.baseUrl)
        if (endpoint.isEmpty()) {
            onError("No API base URL set for ${account.label}.")
            return
        }
        if (account.apiKey.isBlank() && !account.isLocalEndpoint) {
            onError("No API key set for ${account.label}.")
            return
        }
        if (account.model.isBlank()) {
            onError("No model set for ${account.label}. Open Settings and name one.")
            return
        }

        val messages = buildMessages(systemPrompt, history)

        val body = JSONObject().apply {
            put("model", account.model)
            put("messages", messages)
            put("stream", true)
            put("temperature", temperature)
            put("top_p", topP)
            if (account.maxTokens > 0) put("max_tokens", account.maxTokens)
        }

        val builder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")

        if (account.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${account.apiKey}")
            // Some gateways only honour x-api-key; the desktop proxy sent both.
            builder.header("x-api-key", account.apiKey)
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

    /**
     * Build the wire messages.
     *
     * Text attachments are inlined into the user's own message so the model sees
     * file content directly. Images become OpenAI-style content parts, and only
     * the most recent few are re-sent: pushing every image of a long chat on
     * every turn would waste tokens and hit request-size limits.
     */
    fun buildMessages(systemPrompt: String, history: List<ChatMessage>): JSONArray {
        val messages = JSONArray()

        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }

        val imageBudget = MAX_IMAGES_PER_REQUEST
        var imagesUsed = 0

        // Walk backwards so the newest images win the budget, then emit forward.
        val imageAllowance = HashMap<Long, Boolean>()
        for (message in history.asReversed()) {
            val images = message.attachments.count { it.kind == AttachmentKind.Image }
            if (images == 0) continue
            if (imagesUsed + images <= imageBudget) {
                imageAllowance[message.id] = true
                imagesUsed += images
            }
        }

        for (message in history) {
            if (message.role == Role.System) continue

            val textAttachments = message.attachments.filter { it.kind == AttachmentKind.Text }
            val imageAttachments = message.attachments
                .filter { it.kind == AttachmentKind.Image }
                .takeIf { imageAllowance[message.id] == true }
                .orEmpty()

            val composed = buildString {
                append(message.content)
                textAttachments.forEach { attachment ->
                    if (isNotEmpty()) append("\n\n")
                    append("<attached_file name=\"${attachment.displayName}\">\n")
                    append(attachment.text)
                    append("\n</attached_file>")
                }
            }

            if (imageAttachments.isEmpty()) {
                if (composed.isBlank()) continue
                messages.put(
                    JSONObject().put("role", message.role.wire).put("content", composed)
                )
            } else {
                val parts = JSONArray()
                if (composed.isNotBlank()) {
                    parts.put(JSONObject().put("type", "text").put("text", composed))
                } else {
                    parts.put(
                        JSONObject().put("type", "text")
                            .put("text", "Describe or read this image.")
                    )
                }
                imageAttachments.forEach { attachment ->
                    val dataUrl = toDataUrl(attachment) ?: return@forEach
                    parts.put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", dataUrl))
                    )
                }
                messages.put(
                    JSONObject().put("role", message.role.wire).put("content", parts)
                )
            }
        }
        return messages
    }

    /** Read an attachment off disk as a data URL, or null if it is gone. */
    private fun toDataUrl(attachment: Attachment): String? {
        val file = File(attachment.path)
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val mime = attachment.mimeType.ifBlank { "image/jpeg" }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mime;base64,$encoded"
    }

    // --------------------------------------------------------------- streaming

    private fun readEventStream(source: okio.BufferedSource, onDelta: (String) -> Unit) {
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty() || !line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break
            extractDelta(data)?.let { if (it.isNotEmpty()) onDelta(it) }
        }
    }

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

    /** Pull text out of one streamed chunk, tolerating the common shapes. */
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
            out = first?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotEmpty() && it != "null" }
                ?: first?.optString("text")?.takeIf { it.isNotEmpty() }
        }
        if (out == null) {
            val err = o.optJSONObject("error")?.optString("message")
            if (!err.isNullOrBlank()) out = "Provider error: $err"
        }
        out
    } catch (t: Throwable) {
        null
    }

    private fun describe(t: Throwable): String = when (t) {
        is java.net.UnknownHostException ->
            "Cannot resolve that server address. Check the account's base URL in Settings."
        is java.net.SocketTimeoutException ->
            "The provider took too long to respond. Try again, or pick a faster model."
        is javax.net.ssl.SSLException ->
            "Secure connection failed. Check the account's base URL in Settings."
        else -> "Connection failed: ${t.message ?: t.javaClass.simpleName}"
    }

    private fun httpMessage(code: Int, detail: String): String {
        val hint = when (code) {
            400 -> " The request was rejected. If you attached an image, the model may not accept images."
            401, 403 -> " The API key was rejected. Check it in Settings."
            404 -> " The endpoint or model was not found. Check the base URL and model name."
            413 -> " The request was too large. Try a smaller image or fewer attachments."
            429 -> " Rate limited or out of quota. Wait a moment and retry."
            in 500..599 -> " The provider had a server error. Retry shortly."
            else -> ""
        }
        val trimmed = detail.trim().let { if (it.length > 400) it.take(400) + "\u2026" else it }
        return "Provider returned HTTP $code.$hint" +
            if (trimmed.isNotEmpty()) "\n\n$trimmed" else ""
    }

    companion object {
        private const val READ_TIMEOUT_SECONDS = 240L
        private const val USER_AGENT = "SmartTalk/1.0 (Android)"
        private const val MAX_IMAGES_PER_REQUEST = 4
    }
}
