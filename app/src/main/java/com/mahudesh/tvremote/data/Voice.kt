package com.mahudesh.tvremote.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speech-to-text on the phone for the Voice button, in order of preference:
 *  1. The system voice prompt (Google app and most OEM assistants) — handled by the activity.
 *  2. [SpeechRecognizer] with our own "Listening…" sheet — any installed recognition service,
 *     or Android 12+'s on-device one when no service is installed.
 *  3. Nothing available — the caller falls back to the TV's own voice search.
 */
class Voice(context: Context) {
    private val app = context.applicationContext

    sealed interface State {
        data object Idle : State
        data class Listening(val partial: String = "", val level: Float = 0f) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null

    /** Is there a recognizer we can drive ourselves (option 2)? */
    fun canListenInApp(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(app) ||
            (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app))

    /** The system voice prompt intent (option 1), or null if no app on the phone handles it. */
    fun promptIntent(): Intent? {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to search on your TV")
        return if (intent.resolveActivity(app.packageManager) != null) intent else null
    }

    /** Option 2. Needs RECORD_AUDIO already granted. Must be called on the main thread. */
    fun listen(onText: (String) -> Unit) {
        stop()
        val r = when {
            SpeechRecognizer.isRecognitionAvailable(app) -> SpeechRecognizer.createSpeechRecognizer(app)
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            else -> { _state.value = State.Error("No speech recognition on this phone"); return }
        }
        recognizer = r
        _state.value = State.Listening()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                (_state.value as? State.Listening)?.let { _state.value = it.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                (_state.value as? State.Listening)?.let { _state.value = it.copy(partial = text) }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                stop()
                if (text.isNotEmpty()) onText(text) else _state.value = State.Error("Didn't catch that. Try again")
            }
            override fun onError(error: Int) {
                stop()
                _state.value = State.Error(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs internet on this phone"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone to use voice"
                        else -> "Voice isn't working right now. Try again"
                    },
                )
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName),
        )
    }

    /** Stops listening (user tapped away, or done). */
    fun stop() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        if (_state.value is State.Listening) _state.value = State.Idle
    }

    fun clearError() { if (_state.value is State.Error) _state.value = State.Idle }
}
