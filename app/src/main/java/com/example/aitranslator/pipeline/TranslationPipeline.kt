package com.example.aitranslator.pipeline

import com.example.aitranslator.asr.RecognitionEvent
import com.example.aitranslator.asr.SpeechRecognizer
import com.example.aitranslator.model.Language
import com.example.aitranslator.model.ModelManager
import com.example.aitranslator.translation.Translator
import com.example.aitranslator.tts.SpeechSynthesizer
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive

/**
 * The spine of the app: composes the swappable [SpeechRecognizer] → [Translator]
 * → [SpeechSynthesizer] stages into a single observable stream of [PipelineState].
 *
 * It runs turn-based (half-duplex): it listens for one utterance, then **stops the
 * recognizer** before translating and speaking, then resumes listening. This
 * releases the microphone during TTS playback — recording and speaking at the same
 * time stalls audio on most devices. RMS maps to live [PipelineState.Listening],
 * partials to [PipelineState.Transcribing], and each final to
 * [PipelineState.Translating] → [PipelineState.Turn] → [PipelineState.Speaking].
 * It loops until the collector is cancelled.
 */
class TranslationPipeline(
    private val recognizer: SpeechRecognizer,
    private val translator: Translator,
    private val synthesizer: SpeechSynthesizer,
    private val modelManager: ModelManager,
) {

    fun run(source: Language, target: Language): Flow<PipelineState> = channelFlow {
        if (!modelManager.isReady(target)) {
            send(PipelineState.Error("Download the ${target.displayName} model first."))
            return@channelFlow
        }

        while (isActive) {
            val utterance = listenForUtterance(source) ?: continue
            if (utterance.isNotBlank()) onFinal(utterance, source, target)
        }
    }

    /**
     * Collects one recognition session, forwarding RMS/partials, and returns the
     * finalized text. Stopping at the final cancels the recognizer flow, which
     * releases the microphone before the caller translates and speaks.
     */
    private suspend fun ProducerScope<PipelineState>.listenForUtterance(source: Language): String? {
        var finalText: String? = null
        try {
            recognizer.recognize(source)
                .takeWhile { event ->
                    when (event) {
                        is RecognitionEvent.Preparing -> { send(PipelineState.Preparing(event.message)); true }
                        is RecognitionEvent.Rms -> { send(PipelineState.Listening(event.amplitude)); true }
                        is RecognitionEvent.Partial -> { send(PipelineState.Transcribing(event.text)); true }
                        is RecognitionEvent.Final -> { finalText = event.text; false }
                    }
                }
                .collect {}
        } catch (e: Exception) {
            send(PipelineState.Error(e.message ?: "Recognition failed"))
        }
        return finalText
    }

    private suspend fun ProducerScope<PipelineState>.onFinal(
        text: String,
        source: Language,
        target: Language,
    ) {
        try {
            send(PipelineState.Translating(text))
            val translated = translator.translate(text, source, target)
            // Commit the translation to history first, so a TTS problem (e.g.
            // missing voice data) can never hide a successful translation.
            send(PipelineState.Turn(text, translated))
            try {
                send(PipelineState.Speaking(text, translated))
                synthesizer.speak(translated, target)
            } catch (e: Exception) {
                // Playback is best-effort; the translation is already shown. Carry
                // the language so the UI can offer to install its voice.
                send(PipelineState.SpeechFailed(target, "Couldn't speak ${target.displayName}: ${e.message}"))
            }
        } catch (e: Exception) {
            send(PipelineState.Error(e.message ?: "Translation failed"))
        }
    }
}
