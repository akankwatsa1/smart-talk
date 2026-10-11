package com.smarttalk.app

import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Text-to-image generation against an OpenAI-compatible /images/generations
 * endpoint.
 *
 * Generation is slow and expensive compared with chat, so this deliberately
 * does not stream: the UI shows a progress state and the finished images appear
 * together.
 */
class ImageGenApi(private val store: SmartTalkStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private var activeCall: Call? = null

    fun cancel() {
        runCatching { activeCall?.cancel() }
        activeCall = null
    }

    fun generate(
        account: ApiAccount,
        prompt: String,
        size: String,
        count: Int,
        onDone: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        val endpoint = imagesEndpointFor(account.baseUrl)
        if (endpoint.isBlank()) {
            onError("No base URL set for ${account.label}, so images cannot be generated.")
            return
        }
        if (account.apiKey.isBlank() && !account.isLocalEndpoint) {
            onError("No API key set for ${account.label}.")
            return
        }
        if (account.model.isBlank()) {
            onError("No image model set for ${account.label}. Open Settings and name one.")
            return
        }

        val body = JSONObject().apply {
            put("model", account.model)
            put("prompt", prompt)
            put("n", count.coerceIn(1, 4))
            put("size", size)
            // Ask for inline bytes where supported. Models that only return URLs
            // are handled in the response, so requesting this is safe.
            put("response_format", "b64_json")
        }

        val builder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "SmartTalk/1.0 (Android)")

        if (account.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${account.apiKey}")
            builder.header("x-api-key", account.apiKey)
        }

        val call = client.newCall(builder.build())
        activeCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCall = null
                if (call.isCanceled()) {
                    onError("Image generation was stopped.")
                } else {
                    onError("Could not reach ${account.label}: ${e.message ?: "network error"}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val payload = r.body?.string().orEmpty()
                        activeCall = null

                        if (!r.isSuccessful) {
                            onError(explain(r.code, payload))
                            return
                        }

                        val saved = extractImages(payload)
                        if (saved.isEmpty()) {
                            val providerSaid = runCatching {
                                JSONObject(payload).optJSONObject("error")?.optString("message")
                            }.getOrNull()
                            onError(
                                providerSaid?.takeIf { it.isNotBlank() }
                                    ?: "The provider returned no image. Check that " +
                                    "\"${account.model}\" is an image model."
                            )
                        } else {
                            onDone(saved)
                        }
                    }
                } catch (t: Throwable) {
                    activeCall = null
                    onError("Image generation failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        })
    }

    /**
     * Save every image in the response and return their local paths.
     * Providers return either base64 bytes or a temporary URL; both are handled.
     */
    fun extractImages(payload: String): List<String> {
        val out = ArrayList<String>()
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return out
        val data = root.optJSONArray("data") ?: return out

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue

            val b64 = item.optString("b64_json").takeIf { it.isNotBlank() }
            if (b64 != null) {
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    val file = store.writeMedia("gen_${System.currentTimeMillis()}_$i.jpg", bytes)
                    out.add(file.absolutePath)
                }
                continue
            }

            val url = item.optString("url").takeIf { it.isNotBlank() }
            if (url != null) {
                val bytes = download(url)
                if (bytes != null && bytes.isNotEmpty()) {
                    val extension = url.substringBefore('?').substringAfterLast('.', "png")
                        .take(4).ifBlank { "png" }
                    val file = store.writeMedia(
                        "gen_${System.currentTimeMillis()}_$i.$extension",
                        bytes
                    )
                    out.add(file.absolutePath)
                }
            }
        }
        return out
    }

    private fun download(url: String): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SmartTalk/1.0 (Android)")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }.getOrNull()

    private fun explain(code: Int, payload: String): String {
        val providerSaid = runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val hint = when (code) {
            400 -> " The request was rejected - the model may not be an image model, " +
                "or the size may be unsupported."
            401, 403 -> " The API key was rejected."
            404 -> " The images endpoint or model was not found. Base URLs normally end " +
                "in /v1/images/generations."
            429 -> " Rate limited or out of quota."
            in 500..599 -> " The provider had a server error."
            else -> ""
        }
        return "Image generation returned HTTP $code.$hint" +
            if (providerSaid != null) "\n\n$providerSaid" else ""
    }

    private companion object {
        const val GENERATION_TIMEOUT_SECONDS = 300L
    }
}
