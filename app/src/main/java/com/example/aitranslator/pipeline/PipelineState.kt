package com.example.aitranslator.pipeline

import com.example.aitranslator.model.Language

/** Observable state of a single end-to-end translation turn. */
sealed interface PipelineState {
    /** Not running. */
    data object Idle : PipelineState

    /** Capturing audio; [amplitude] (0f..1f) drives a VU meter. */
    data class Listening(val amplitude: Float) : PipelineState

    /** Recognizing speech; [partial] is the latest (non-final) transcript. */
    data class Transcribing(val partial: String) : PipelineState

    /** A final transcript is being translated. */
    data class Translating(val sourceText: String) : PipelineState

    /** The translation is being spoken aloud. */
    data class Speaking(val sourceText: String, val translatedText: String) : PipelineState

    /** A completed turn; the UI appends this to the conversation history. */
    data class Turn(val sourceText: String, val translatedText: String) : PipelineState

    /** A recoverable error; the pipeline keeps running. */
    data class Error(val message: String) : PipelineState

    /**
     * The translation succeeded but couldn't be spoken in [language] (e.g. no
     * on-device voice installed). The pipeline keeps running; the UI can offer to
     * install the voice.
     */
    data class SpeechFailed(val language: Language, val message: String) : PipelineState
}
