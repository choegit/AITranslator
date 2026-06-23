package com.example.aitranslator.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as PlatformRecognizer
import com.example.aitranslator.model.Language
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real recognizer backed by the platform [android.speech.SpeechRecognizer].
 *
 * The platform engine captures the microphone itself and recognizes one
 * utterance per session, so this class runs it in a continuous loop: it restarts
 * listening after each final result (or recoverable error) and emits
 * [RecognitionEvent]s until the flow is cancelled. All engine calls are marshalled
 * to the main thread, as the platform API requires.
 */
class AndroidSpeechRecognizer(private val context: Context) : SpeechRecognizer {

    private val main = Handler(Looper.getMainLooper())

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class) // isClosedForSend guards restarts
    override fun recognize(source: Language): Flow<RecognitionEvent> = callbackFlow {
        check(PlatformRecognizer.isRecognitionAvailable(context)) {
            "No speech recognition service available on this device"
        }

        val recognizer = if (PlatformRecognizer.isOnDeviceRecognitionAvailable(context)) {
            PlatformRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            PlatformRecognizer.createSpeechRecognizer(context)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, source.code)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        // Begin (or resume) a single recognition session.
        fun startSession() = main.post { recognizer.startListening(intent) }

        val listener = object : RecognitionListener {
            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB is roughly -2..10; map to a 0..1 meter level.
                trySend(RecognitionEvent.Rms((rmsdB.coerceIn(0f, 10f)) / 10f))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { trySend(RecognitionEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = firstResult(results)
                if (text != null) {
                    // Emit the final and stop. The pipeline cancels this session,
                    // freeing the mic so TTS can play, then starts a fresh session
                    // for the next turn. Restarting here would re-grab the mic and
                    // block playback (half-duplex).
                    trySend(RecognitionEvent.Final(text))
                } else if (!isClosedForSend) {
                    startSession() // empty result: keep waiting for speech
                }
            }

            override fun onError(error: Int) {
                when (error) {
                    // Recoverable: just listen again.
                    PlatformRecognizer.ERROR_NO_MATCH,
                    PlatformRecognizer.ERROR_SPEECH_TIMEOUT,
                    -> if (!isClosedForSend) startSession()
                    // Fatal: surface to the collector.
                    else -> close(IllegalStateException("Speech recognition error $error"))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        main.post {
            recognizer.setRecognitionListener(listener)
            recognizer.startListening(intent)
        }

        awaitClose {
            main.post {
                recognizer.stopListening()
                recognizer.destroy()
            }
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(PlatformRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
}
