package com.example.aitranslator.tts

import com.example.aitranslator.model.Language

/**
 * Text-to-speech output stage. [speak] suspends until playback of [text]
 * completes (or fails), so the pipeline can sequence turns naturally.
 */
interface SpeechSynthesizer {
    suspend fun speak(text: String, language: Language)

    /** Release native resources. Call when the synthesizer is no longer needed. */
    fun shutdown()
}
