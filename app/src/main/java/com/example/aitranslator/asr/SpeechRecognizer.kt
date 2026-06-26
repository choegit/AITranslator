package com.example.aitranslator.asr

import com.example.aitranslator.model.Language
import kotlinx.coroutines.flow.Flow

/** Events produced by a recognizer for a continuous listening session. */
sealed interface RecognitionEvent {
    /**
     * A one-time setup step before listening can begin (e.g. downloading the
     * source language's on-device model). [message] is a human-readable status.
     */
    data class Preparing(val message: String) : RecognitionEvent

    /** Live input loudness in 0f..1f, for the VU meter. */
    data class Rms(val amplitude: Float) : RecognitionEvent

    /** A non-final hypothesis that updates live. */
    data class Partial(val text: String) : RecognitionEvent

    /** A finalized utterance; closes a turn. */
    data class Final(val text: String) : RecognitionEvent
}

/**
 * Streaming speech recognizer. A recognizer owns its own audio source (the
 * platform recognizer captures the mic directly; the mock reads an injected
 * [com.example.aitranslator.audio.AudioCapture]) and emits a continuous stream of
 * [RecognitionEvent]s until the collector is cancelled.
 *
 * Owning audio here — rather than passing frames in — is what lets the real
 * Android [android.speech.SpeechRecognizer], which fuses capture and recognition,
 * drop in behind the same interface as the mock.
 */
interface SpeechRecognizer {
    fun recognize(source: Language): Flow<RecognitionEvent>
}
