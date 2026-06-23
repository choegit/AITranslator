package com.example.aitranslator.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.aitranslator.model.Language
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Real text-to-speech backed by the platform [TextToSpeech] engine.
 *
 * The Google TTS engine is requested explicitly when present, because the device
 * may have no default engine configured (which otherwise makes init/speak fail
 * silently). Initialization is asynchronous, so [speak] awaits readiness, then
 * bridges the callback-based [UtteranceProgressListener] into a suspending call
 * that completes when playback finishes.
 */
class AndroidTextToSpeech(context: Context) : SpeechSynthesizer {

    private val ready = CompletableDeferred<Boolean>()
    private val utteranceCounter = AtomicLong(0)

    // Maps an in-flight utterance id to the continuation awaiting its completion.
    private val pending = HashMap<String, (Result<Unit>) -> Unit>()

    private val tts = TextToSpeech(
        context.applicationContext,
        { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (!ok) Log.w(TAG, "TextToSpeech init failed: status=$status")
            ready.complete(ok)
        },
        preferredEngine(context),
    ).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                finish(utteranceId, Result.success(Unit))
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finish(utteranceId, Result.failure(IllegalStateException("TTS error")))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finish(utteranceId, Result.failure(IllegalStateException("TTS error $errorCode")))
            }
        })
    }

    override suspend fun speak(text: String, language: Language) {
        // Never let a stuck engine hang the pipeline: bound init and playback.
        val initialized = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!initialized) error("TextToSpeech engine unavailable")

        selectVoice(language)

        val id = "utt-${utteranceCounter.incrementAndGet()}"
        val completed = withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                synchronized(pending) { pending[id] = { r -> cont.resumeWith(r) } }
                cont.invokeOnCancellation {
                    synchronized(pending) { pending.remove(id) }
                    tts.stop()
                }
                val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (queued != TextToSpeech.SUCCESS) {
                    finish(id, Result.failure(IllegalStateException("TTS could not queue utterance")))
                }
            }
            true
        }
        if (completed == null) {
            synchronized(pending) { pending.remove(id) }
            tts.stop()
            Log.w(TAG, "TTS playback timed out for ${language.code}")
            error("Speech timed out")
        }
    }

    /**
     * Pick a voice for [language], preferring an installed offline one. The engine
     * otherwise defaults to a network "-server" voice (e.g. es-US), which fails
     * with synthesis error -4 when offline. Falls back to [TextToSpeech.setLanguage]
     * if no offline voice is found.
     */
    private fun selectVoice(language: Language) {
        val candidates = runCatching {
            tts.voices?.filter { it.locale.language.equals(language.code, ignoreCase = true) }
        }.getOrNull().orEmpty()

        // Prefer a non-network "-local" voice (the "notInstalled" feature flag is
        // advertised on every voice here, so it isn't a reliable filter). Among
        // those, favor the country matching the language code, then quality.
        val offline = candidates
            .filter { !it.isNetworkConnectionRequired && it.name.contains("-local", ignoreCase = true) }
            .maxWithOrNull(
                compareBy<Voice>(
                    { if (it.locale.country.equals(language.code, ignoreCase = true)) 1 else 0 },
                    { it.quality },
                ),
            )

        if (offline != null) {
            tts.voice = offline
            return
        }

        val result = tts.setLanguage(Locale.forLanguageTag(language.code))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "No offline voice for ${language.code} (setLanguage=$result)")
            error("No installed voice for ${language.displayName}")
        }
    }

    private fun finish(utteranceId: String?, result: Result<Unit>) {
        // QUEUE_FLUSH means a single active utterance, so if the engine reports a
        // null/unknown id (some engines do on error), still resume the pending one.
        val callback = synchronized(pending) {
            pending.remove(utteranceId) ?: pending.keys.firstOrNull()?.let { pending.remove(it) }
        } ?: return
        callback(result)
    }

    override fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun preferredEngine(context: Context): String? {
        val installed = runCatching {
            context.packageManager.getPackageInfo(GOOGLE_TTS, 0)
        }.isSuccess
        return GOOGLE_TTS.takeIf { installed }
    }

    private companion object {
        const val TAG = "AndroidTextToSpeech"
        const val GOOGLE_TTS = "com.google.android.tts"
        const val INIT_TIMEOUT_MS = 5_000L
        const val SPEAK_TIMEOUT_MS = 10_000L
    }
}
