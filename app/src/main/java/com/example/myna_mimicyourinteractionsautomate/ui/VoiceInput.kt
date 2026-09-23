package com.example.myna_mimicyourinteractionsautomate.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * In-app listening (no Google popup). Shows live words via [onPartial]; [onFinal] gets the best result.
 * [hints] bias the recogniser toward words MYNA knows (dish names, restaurants) on Android 13+.
 */
class VoiceInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun start(hints: List<String>, onPartial: (String) -> Unit, onFinal: (String?) -> Unit) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onFinal(null); return }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle) { b.best()?.let(onPartial) }
            override fun onResults(b: Bundle) { onFinal(b.best()); stop() }
            override fun onError(error: Int) { onFinal(null); stop() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hints.isNotEmpty())
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints.take(50)))
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun Bundle.best() = getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
