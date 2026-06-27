package com.example.aitranslator.asr

import android.content.Context
import android.speech.SpeechRecognizer as PlatformRecognizer
import com.example.aitranslator.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Availability of a language's on-device speech-recognition model. */
sealed interface SpeechModelState {
    /** Installed and usable offline. */
    data object Installed : SpeechModelState

    /** Supported on-device but not yet downloaded. */
    data object Available : SpeechModelState

    /** Download in progress; [progress] is 0f..1f, or null if indeterminate. */
    data class Downloading(val progress: Float?) : SpeechModelState

    /** Not offered for on-device recognition on this device. */
    data object Unsupported : SpeechModelState

    /** Support couldn't be determined (e.g. no on-device recognizer). */
    data object Unknown : SpeechModelState
}

/** A per-language on-device speech-recognition model. */
data class SpeechModel(val language: Language, val state: SpeechModelState)

/**
 * Manages on-device speech-recognition language packs (Soda) so they can be
 * pre-downloaded from the Models screen instead of only on first use. Backed by the
 * platform [PlatformRecognizer] support/download APIs (min SDK 34); state is held in
 * memory for the prototype. Mirrors [com.example.aitranslator.model.ModelManager],
 * which does the same for translation models.
 */
class SpeechModelManager(private val context: Context) {

    private val _models = MutableStateFlow(
        Language.ALL.map { SpeechModel(it, SpeechModelState.Unknown) },
    )
    val models: StateFlow<List<SpeechModel>> = _models.asStateFlow()

    /** Re-queries which language packs are installed vs. downloadable. */
    suspend fun refresh() {
        if (!PlatformRecognizer.isOnDeviceRecognitionAvailable(context)) {
            _models.update { models -> models.map { it.copy(state = SpeechModelState.Unsupported) } }
            return
        }
        val recognizer = createRecognizer()
        try {
            val support = recognizer.awaitRecognitionSupport(speechRecognitionIntent(EN_US))
            _models.update { models ->
                models.map { model ->
                    // Leave an in-flight download alone until it resolves.
                    if (model.state is SpeechModelState.Downloading) return@map model
                    val tag = speechRecognitionTag(model.language)
                    val state = when {
                        support == null -> SpeechModelState.Unknown
                        support.installedOnDeviceLanguages.containsLanguageTag(tag) -> SpeechModelState.Installed
                        (support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages)
                            .containsLanguageTag(tag) -> SpeechModelState.Available
                        else -> SpeechModelState.Unsupported
                    }
                    model.copy(state = state)
                }
            }
        } finally {
            destroy(recognizer)
        }
    }

    /**
     * Downloads [language]'s pack (the platform may show its own download dialog),
     * then refreshes so the row reflects the installed result.
     */
    suspend fun download(language: Language) {
        if (!PlatformRecognizer.isOnDeviceRecognitionAvailable(context)) return
        setState(language, SpeechModelState.Downloading(null))
        val recognizer = createRecognizer()
        try {
            val intent = speechRecognitionIntent(speechRecognitionTag(language))
            recognizer.awaitModelDownload(intent) { percent ->
                setState(language, SpeechModelState.Downloading(percent / 100f))
            }
        } finally {
            destroy(recognizer)
        }
        refresh()
    }

    private suspend fun createRecognizer(): PlatformRecognizer =
        withContext(Dispatchers.Main.immediate) {
            PlatformRecognizer.createOnDeviceSpeechRecognizer(context)
        }

    private suspend fun destroy(recognizer: PlatformRecognizer) =
        withContext(Dispatchers.Main.immediate) { runCatching { recognizer.destroy() } }

    private fun setState(language: Language, state: SpeechModelState) {
        _models.update { models -> models.map { if (it.language == language) it.copy(state = state) else it } }
    }

    private companion object {
        const val EN_US = "en-US"
    }
}
