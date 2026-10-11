package com.smarttalk.app

import android.media.MediaPlayer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Read-aloud through a cloud voice, for accounts that offer one.
 *
 * The device's own engine is the default: it is free, instant and works
 * offline. This exists for the cases where a better voice is worth a network
 * round-trip, and it is only used when the user routes the "Cloud read-aloud
 * voice" capability at an account.
 */
class CloudVoice(private val store: SmartTalkStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    var onStateChange: ((Boolean) -> Unit)? = null

    /** Derive the speech endpoint from a chat endpoint. */
    fun speechEndpointFor(baseUrl: String): String {
        val clean = baseUrl.trim().removeSuffix("/")
        if (clean.isEmpty()) return ""
        return when {
            clean.endsWith("/audio/speech") -> clean
            clean.endsWith("/chat/completions") ->
                clean.removeSuffix("/chat/completions") + "/audio/speech"
            clean.endsWith("/v1") -> "$clean/audio/speech"
            clean.contains("/v1/") -> clean.replace(Regex("/v1/.*$"), "/v1/audio/speech")
            else -> "$clean/v1/audio/speech"
        }
    }

    fun speak(
        account: ApiAccount,
        text: String,
        voice: String = "alloy",
        onError: (String) -> Unit
    ) {
        val endpoint = speechEndpointFor(account.baseUrl)
        if (endpoint.isBlank()) {
            onError("No base URL set for the read-aloud account.")
            return
        }
        if (account.apiKey.isBlank() && !account.isLocalEndpoint) {
            onError("No API key set for the read-aloud account.")
            return
        }

        val body = JSONObject().apply {
            put("model", account.model)
            put("input", text.take(4000))
            put("voice", voice)
            put("response_format", "mp3")
        }

        val builder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .header("User-Agent", "SmartTalk/1.0 (Android)")

        if (account.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${account.apiKey}")
            builder.header("x-api-key", account.apiKey)
        }

        try {
            val bytes = client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string()?.take(300).orEmpty()
                    onError("Read-aloud returned HTTP ${response.code}. $detail")
                    return
                }
                response.body?.bytes()
            }
            if (bytes == null || bytes.isEmpty()) {
                onError("The read-aloud service returned no audio.")
                return
            }
            play(bytes)
        } catch (t: Throwable) {
            onError("Cloud read-aloud failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun play(bytes: ByteArray) {
        stop()
        val file = store.writeMedia("speech_${System.currentTimeMillis()}.mp3", bytes)
        currentFile = file
        val media = MediaPlayer()
        player = media
        media.setDataSource(file.absolutePath)
        media.setOnPreparedListener {
            it.start()
            onStateChange?.invoke(true)
        }
        media.setOnCompletionListener {
            onStateChange?.invoke(false)
            stop()
        }
        media.setOnErrorListener { _, _, _ ->
            onStateChange?.invoke(false)
            stop()
            true
        }
        media.prepareAsync()
    }

    fun stop() {
        runCatching {
            player?.let { media ->
                if (media.isPlaying) media.stop()
                media.release()
            }
        }
        player = null
        onStateChange?.invoke(false)
        // Speech files are large and never needed again.
        currentFile?.let { file -> runCatching { file.delete() } }
        currentFile = null
    }
}
