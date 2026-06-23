package com.example.aitranslator.asr

import com.example.aitranslator.audio.AudioCapture
import com.example.aitranslator.model.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A stand-in for a real streaming ASR model.
 *
 * It reads its injected [audioCapture] and performs naive voice-activity
 * detection on chunk amplitudes: each chunk emits an [RecognitionEvent.Rms] for
 * the meter; when the mic goes loud it begins an utterance and reveals a scripted
 * phrase word-by-word ([RecognitionEvent.Partial]); when the mic falls quiet (or
 * a length cap is hit) it emits the full phrase as [RecognitionEvent.Final]. This
 * exercises the exact streaming contract using real audio cadence, without any ML.
 */
class MockSpeechRecognizer(private val audioCapture: AudioCapture) : SpeechRecognizer {

    override fun recognize(source: Language): Flow<RecognitionEvent> = flow {
        val phrases = scriptFor(source)
        var phraseIndex = 0

        var speaking = false
        var silenceFrames = 0
        var framesIntoUtterance = 0
        var revealedWords = 0
        var words: List<String> = emptyList()

        audioCapture.audioFrames().collect { chunk ->
            emit(RecognitionEvent.Rms(chunk.amplitude))
            val loud = chunk.amplitude >= START_THRESHOLD

            if (!speaking) {
                if (loud) {
                    speaking = true
                    silenceFrames = 0
                    framesIntoUtterance = 0
                    revealedWords = 0
                    words = phrases[phraseIndex % phrases.size].split(" ")
                    phraseIndex++
                }
                return@collect
            }

            framesIntoUtterance++

            if (framesIntoUtterance % FRAMES_PER_WORD == 0 && revealedWords < words.size) {
                revealedWords++
                emit(RecognitionEvent.Partial(words.take(revealedWords).joinToString(" ")))
            }

            silenceFrames = if (chunk.amplitude < STOP_THRESHOLD) silenceFrames + 1 else 0
            val endedBySilence = silenceFrames >= SILENCE_FRAMES_TO_END
            val endedByLength = framesIntoUtterance >= MAX_UTTERANCE_FRAMES
            if (endedBySilence || endedByLength) {
                // Finalize with the complete phrase so the translator's phrasebook
                // reliably matches and yields real target-language text.
                emit(RecognitionEvent.Final(words.joinToString(" ")))
                speaking = false
            }
        }
    }

    private fun scriptFor(source: Language): List<String> = when (source.code) {
        "es" -> listOf("hola, ¿cómo estás?", "me gustaría un café", "¿dónde está la estación?")
        "fr" -> listOf("bonjour, comment ça va", "je voudrais un café", "où est la gare")
        "de" -> listOf("hallo, wie geht es dir", "ich möchte einen kaffee", "wo ist der bahnhof")
        "ja" -> listOf("こんにちは お元気ですか", "コーヒー を ください", "駅 は どこ ですか")
        else -> listOf(
            "hello how are you today",
            "I would like a cup of coffee",
            "where is the nearest train station",
        )
    }

    private companion object {
        const val START_THRESHOLD = 0.06f
        const val STOP_THRESHOLD = 0.04f
        const val FRAMES_PER_WORD = 3
        const val SILENCE_FRAMES_TO_END = 6
        const val MAX_UTTERANCE_FRAMES = 150
    }
}
