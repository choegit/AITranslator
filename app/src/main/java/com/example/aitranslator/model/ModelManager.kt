package com.example.aitranslator.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks the offline-model lifecycle for each supported [Language].
 *
 * State is held in memory for the prototype (DataStore would back it in
 * production). [download] simulates a progressing download; the pipeline gates on
 * [isReady] before attempting to translate into a language.
 */
class ModelManager {

    private val _models = MutableStateFlow(seedModels())
    val models: StateFlow<List<TranslationModel>> = _models.asStateFlow()

    fun isReady(language: Language): Boolean =
        _models.value.firstOrNull { it.language == language }?.state is ModelState.Ready

    /** Simulate downloading [language]'s model, emitting progress, then marking it Ready. */
    suspend fun download(language: Language) {
        if (isReady(language)) return
        var progress = 0f
        while (progress < 1f) {
            progress = (progress + PROGRESS_STEP).coerceAtMost(1f)
            setState(language, ModelState.Downloading(progress))
            delay(DOWNLOAD_TICK_MS)
        }
        setState(language, ModelState.Ready)
    }

    /** Remove [language]'s model, returning it to [ModelState.NotDownloaded]. */
    fun delete(language: Language) {
        setState(language, ModelState.NotDownloaded)
    }

    private fun setState(language: Language, state: ModelState) {
        _models.update { models ->
            models.map { if (it.language == language) it.copy(state = state) else it }
        }
    }

    private companion object {
        const val PROGRESS_STEP = 0.1f
        const val DOWNLOAD_TICK_MS = 200L

        fun seedModels(): List<TranslationModel> = Language.ALL.map { language ->
            // English ships with the app; everything else must be downloaded.
            val state = if (language == Language.ENGLISH) ModelState.Ready else ModelState.NotDownloaded
            TranslationModel(language = language, sizeMb = 40 + language.code.hashCode().mod(80), state = state)
        }
    }
}
