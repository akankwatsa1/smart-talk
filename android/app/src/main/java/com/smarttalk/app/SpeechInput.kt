package com.smarttalk.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Voice input for the Android app.
 *
 * The desktop version recorded audio, shelled out to FFmpeg to resample it, and
 * then drove the Windows System.Speech engine through PowerShell. Android ships a
 * speech recogniser in the platform, so the entire FFmpeg/PowerShell pipeline is
 * deliberately not ported: this delegates to the on-device recogniser, which also
 * means it works offline on most modern phones.
 *
 * Must be created and driven from the main thread.
 */
class SpeechInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /** False when the device has no recogniser at all, so the UI can hide the control. */
    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isAvailable) {
            onError("Speech recognition is not available on this device.")
            return
        }
        stop()
        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = engine

        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.let { bundle ->
                    val text = firstResult(bundle)
                    if (text.isNotBlank()) onPartial(text)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.let { firstResult(it) }.orEmpty()
                if (text.isBlank()) {
                    onError("Nothing was recognised. Please try again.")
                } else {
                    onFinal(text)
                }
            }

            override fun onError(error: Int) {
                onError(describeError(error))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        engine.startListening(intent)
    }

    fun stop() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (t: Throwable) {
            // Recogniser already gone; nothing to clean up.
        }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle): String =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_CLIENT -> "The recogniser stopped."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is needed for voice input."
        SpeechRecognizer.ERROR_NETWORK -> "Network error during recognition."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Recognition timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognised. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy. Try again."
        SpeechRecognizer.ERROR_SERVER -> "The recognition service reported an error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard. Please try again."
        else -> "Voice input failed (code $code)."
    }
}
