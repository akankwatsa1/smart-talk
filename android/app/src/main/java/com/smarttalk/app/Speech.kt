package com.smarttalk.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads answers aloud using the device's own speech engine.
 *
 * This is the default because it is free, offline, instant, and needs no API
 * account. A cloud voice can be routed through [Capability.Tts] instead, but a
 * cloud round-trip for every sentence of every answer is rarely worth it.
 */
class Speaker(private val context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var queued: String? = null

    /** True while audio is actually playing, so the UI can show a Stop affordance. */
    var isSpeaking: Boolean = false
        private set

    var onStateChange: ((Boolean) -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            isSpeaking = true
            onStateChange?.invoke(true)
        }

        override fun onDone(utteranceId: String?) {
            isSpeaking = false
            onStateChange?.invoke(false)
        }

        @Deprecated("Superseded by onError(String, Int), but still abstract in the API")
        override fun onError(utteranceId: String?) {
            isSpeaking = false
            onStateChange?.invoke(false)
            onMessage?.invoke("Read-aloud stopped unexpectedly.")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            isSpeaking = false
            onStateChange?.invoke(false)
            onMessage?.invoke(describeError(errorCode))
        }
    }

    private fun ensureEngine(): Boolean {
        if (engine != null) return true
        val created = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                engine?.language = Locale.getDefault()
                engine?.setOnUtteranceProgressListener(listener)
                queued?.let { text ->
                    queued = null
                    speakNow(text)
                }
            } else {
                onMessage?.invoke("No read-aloud voice is installed on this device.")
            }
        }
        engine = created
        return true
    }

    /** True when the platform reports at least one usable speech engine. */
    val isAvailable: Boolean
        get() = runCatching {
            val probe = TextToSpeech(context) { }
            val ok = probe.engines.isNotEmpty()
            probe.shutdown()
            ok
        }.getOrDefault(true)

    /**
     * Speak [markdown], replacing any speech already in progress. Markdown
     * markers are stripped first: nobody wants to hear "asterisk asterisk".
     */
    fun speak(markdown: String) {
        val text = markdownToSpeech(markdown)
        if (text.isBlank()) return
        ensureEngine()
        if (!ready) {
            queued = text
            return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val tts = engine ?: return
        stop()
        // Android's speech engine rejects very long single utterances, so long
        // answers are split on sentence boundaries and queued in order.
        val chunks = chunkForSpeech(text)
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(chunk, mode, null, "smarttalk-$index")
        }
        isSpeaking = true
        onStateChange?.invoke(true)
    }

    fun stop() {
        runCatching { engine?.stop() }
        isSpeaking = false
        onStateChange?.invoke(false)
    }

    /** Call from the host's onCleared/onDestroy: the engine holds native resources. */
    fun shutdown() {
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        ready = false
        isSpeaking = false
    }

    private fun describeError(code: Int): String = when (code) {
        TextToSpeech.ERROR_SYNTHESIS -> "The voice could not synthesise that text."
        TextToSpeech.ERROR_SERVICE -> "The speech service reported an error."
        TextToSpeech.ERROR_OUTPUT -> "No audio output was available."
        TextToSpeech.ERROR_NETWORK -> "The voice needs a network connection."
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "The voice data is still downloading."
        else -> "Read-aloud failed (code $code)."
    }

    companion object {
        /** Roughly the largest utterance Android's engine accepts comfortably. */
        private const val MAX_CHUNK = 2800

        /** Split into sentence-sized pieces without breaking mid-sentence. */
        fun chunkForSpeech(text: String): List<String> {
            if (text.length <= MAX_CHUNK) return listOf(text)
            val chunks = ArrayList<String>()
            val builder = StringBuilder()
            val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            for (sentence in sentences) {
                if (builder.length + sentence.length + 1 > MAX_CHUNK && builder.isNotEmpty()) {
                    chunks.add(builder.toString().trim())
                    builder.clear()
                }
                if (sentence.length > MAX_CHUNK) {
                    // A single enormous "sentence" (often a wall of code): hard split.
                    sentence.chunked(MAX_CHUNK).forEach { chunks.add(it) }
                    continue
                }
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(sentence)
            }
            if (builder.isNotEmpty()) chunks.add(builder.toString().trim())
            return chunks.filter { it.isNotBlank() }
        }
    }
}

/**
 * Flatten markdown into something worth hearing: no asterisks, no backticks, no
 * raw URLs read out character by character, and headings spoken as sentences.
 */
fun markdownToSpeech(source: String): String {
    val blocks = parseMarkdown(source)
    val out = StringBuilder()

    fun say(text: String) {
        val cleaned = stripForSpeech(text)
        if (cleaned.isBlank()) return
        if (out.isNotEmpty()) out.append(' ')
        out.append(cleaned)
        if (!cleaned.endsWith('.') && !cleaned.endsWith('!') && !cleaned.endsWith('?')) {
            out.append('.')
        }
    }

    for (block in blocks) {
        when (block) {
            is MdBlock.Heading -> say(block.text)

            is MdBlock.Paragraph -> say(block.text)

            is MdBlock.Bullets -> {
                if (block.items.size > 1) say("There are ${block.items.size} points.")
                block.items.forEach { say(it) }
            }

            is MdBlock.Quote -> say("Quote. " + block.text)

            is MdBlock.Code -> {
                // Reading a long code block aloud is unusable; describe it instead.
                val lines = block.code.count { it == '\n' } + 1
                if (block.code.length <= 200) {
                    say("Code. " + block.code.replace('\n', ' '))
                } else {
                    say("A code block of $lines lines follows on screen.")
                }
            }

            MdBlock.Rule -> Unit
        }
    }
    return out.toString().trim()
}

/** Remove inline markers and strip URLs, which are unbearable when spoken. */
private fun stripForSpeech(text: String): String {
    val parsed = parseInline(text)
    return parsed.text
        // "label (https://...)" becomes just the label.
        .replace(Regex("\\s*\\((https?://[^)\\s]+)\\)"), "")
        .replace(Regex("https?://\\S+"), "the link on screen")
        .replace(Regex("\\s+"), " ")
        .trim()
}
