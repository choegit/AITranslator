package com.example.aitranslator.asr

import android.content.Intent
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as PlatformRecognizer
import com.example.aitranslator.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Shared helpers for the platform on-device speech models (Soda language packs),
 * used both by the live recognizer ([AndroidSpeechRecognizer]) and by the model
 * manager that pre-downloads packs from the Models screen ([SpeechModelManager]).
 * The support/download APIs require min SDK 34 (this app's floor).
 */

/**
 * Maps a bare language code to a region-qualified BCP-47 tag for
 * [RecognizerIntent.EXTRA_LANGUAGE]. Google's on-device (Soda) recognizer rejects a
 * country-less tag ("Country code is invalid or empty") and silently falls back to
 * the system default, so "es" alone would recognize Spanish speech as English. Each
 * language maps to a representative locale whose recognition pack the engine ships.
 */
internal fun speechRecognitionTag(language: Language): String = when (language.code) {
    "en" -> "en-US"
    "es" -> "es-ES"
    "fr" -> "fr-FR"
    "de" -> "de-DE"
    "ja" -> "ja-JP"
    else -> language.code
}

/** A free-form recognition intent targeting [tag]. */
internal fun speechRecognitionIntent(tag: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

/** True if any tag in this collection matches [tag] (case-insensitive). */
internal fun Collection<String>.containsLanguageTag(tag: String): Boolean =
    any { it.equals(tag, ignoreCase = true) }

/**
 * Queries on-device recognition support (which languages are installed vs.
 * downloadable). Returns null if support can't be determined. Marshalled to the
 * main thread, as the platform API requires.
 */
internal suspend fun PlatformRecognizer.awaitRecognitionSupport(intent: Intent): RecognitionSupport? =
    withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                checkRecognitionSupport(
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

/**
 * Triggers and awaits the on-device model download for [intent]'s language,
 * reporting progress (0..100) via [onProgress]. Returns true on success. On some
 * devices the platform shows its own download dialog; this resolves once that
 * completes. Marshalled to the main thread.
 */
internal suspend fun PlatformRecognizer.awaitModelDownload(
    intent: Intent,
    onProgress: (Int) -> Unit = {},
): Boolean = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine { cont ->
        runCatching {
            triggerModelDownload(
                intent,
                Executor { it.run() },
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = onProgress(completedPercent)
                    override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                    override fun onScheduled() {}
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(false) }
                },
            )
        }.onFailure { if (cont.isActive) cont.resume(false) }
    }
}
