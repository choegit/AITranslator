package com.example.aitranslator.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as PlatformRecognizer
import com.example.aitranslator.model.Language
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

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

        val onDevice = PlatformRecognizer.isOnDeviceRecognitionAvailable(context)
        val recognizer = if (onDevice) {
            PlatformRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            PlatformRecognizer.createSpeechRecognizer(context)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionTag(source))
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

        try {
            // On-device recognition needs the source language's model installed.
            // Download it once (offline thereafter) so a not-yet-installed language
            // doesn't fail the session (e.g. error 11 when only en-US is present).
            if (onDevice) {
                ensureOnDeviceModel(recognizer, intent) { trySend(RecognitionEvent.Preparing(it)) }
            }

            main.post {
                recognizer.setRecognitionListener(listener)
                recognizer.startListening(intent)
            }

            awaitClose {
                main.post { runCatching { recognizer.stopListening() } }
            }
        } finally {
            // Reached on normal close and on cancellation during preparation, so the
            // recognizer is always released even if we never started listening.
            main.post { runCatching { recognizer.destroy() } }
        }
    }

    /**
     * Ensures the on-device model for the [intent]'s language is installed,
     * downloading it (and reporting progress via [onStatus]) when missing. If
     * support can't be determined or the language isn't on-device-downloadable,
     * returns quietly and lets [PlatformRecognizer.startListening] surface any
     * problem. Uses the API 33/34 support/download APIs (min SDK is 34).
     */
    private suspend fun ensureOnDeviceModel(
        recognizer: PlatformRecognizer,
        intent: Intent,
        onStatus: (String) -> Unit,
    ) {
        val lang = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: return
        val support = checkSupport(recognizer, intent) ?: return
        fun Collection<String>.hasLang() = any { it.equals(lang, ignoreCase = true) }
        if (support.installedOnDeviceLanguages.hasLang()) return // already offline-ready
        val downloadable = support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages
        if (!downloadable.hasLang()) return // not on-device-downloadable; let startListening report it
        onStatus("Downloading speech model…")
        downloadModel(recognizer, intent, onStatus)
    }

    private suspend fun checkSupport(recognizer: PlatformRecognizer, intent: Intent): RecognitionSupport? =
        suspendCancellableCoroutine { cont ->
            main.post {
                runCatching {
                    recognizer.checkRecognitionSupport(
                        intent,
                        Executor { it.run() },
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                                if (cont.isActive) cont.resume(recognitionSupport)
                            }

                            override fun onError(error: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    private suspend fun downloadModel(
        recognizer: PlatformRecognizer,
        intent: Intent,
        onStatus: (String) -> Unit,
    ): Unit = suspendCancellableCoroutine { cont ->
        main.post {
            runCatching {
                recognizer.triggerModelDownload(
                    intent,
                    Executor { it.run() },
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            onStatus("Downloading speech model… $completedPercent%")
                        }

                        override fun onSuccess() {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onScheduled() {}

                        override fun onError(error: Int) {
                            // Proceed anyway; startListening will give a concrete error
                            // if the model is still unusable.
                            onStatus("Couldn't download speech model (error $error)")
                            if (cont.isActive) cont.resume(Unit)
                        }
                    },
                )
            }.onFailure { if (cont.isActive) cont.resume(Unit) }
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(PlatformRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Maps a bare language code to a region-qualified BCP-47 tag for
     * [RecognizerIntent.EXTRA_LANGUAGE]. Google's on-device (Soda) recognizer
     * rejects a country-less tag ("Country code is invalid or empty") and silently
     * falls back to the system default (en-US), so a plain "es" would recognize
     * Spanish speech as English. Each language maps to a representative locale whose
     * recognition pack the engine ships (es-ES, fr-FR, de-DE, ja-JP, en-US).
     */
    private fun recognitionTag(language: Language): String = when (language.code) {
        "en" -> "en-US"
        "es" -> "es-ES"
        "fr" -> "fr-FR"
        "de" -> "de-DE"
        "ja" -> "ja-JP"
        else -> language.code
    }
}
