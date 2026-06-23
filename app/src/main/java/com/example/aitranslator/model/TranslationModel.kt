package com.example.aitranslator.model

/** Lifecycle of an offline (per-language) translation model on the device. */
sealed interface ModelState {
    /** Not present locally; must be downloaded before it can be used. */
    data object NotDownloaded : ModelState

    /** Download in progress. [progress] is in 0f..1f. */
    data class Downloading(val progress: Float) : ModelState

    /** Present locally and ready for the pipeline to use. */
    data object Ready : ModelState
}

/** A downloadable offline model for a single [language]. */
data class TranslationModel(
    val language: Language,
    val sizeMb: Int,
    val state: ModelState,
)
