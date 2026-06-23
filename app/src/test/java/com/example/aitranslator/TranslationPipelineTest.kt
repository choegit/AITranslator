package com.example.aitranslator

import com.example.aitranslator.asr.RecognitionEvent
import com.example.aitranslator.asr.SpeechRecognizer
import com.example.aitranslator.model.Language
import com.example.aitranslator.model.ModelManager
import com.example.aitranslator.pipeline.PipelineState
import com.example.aitranslator.pipeline.TranslationPipeline
import com.example.aitranslator.translation.Translator
import com.example.aitranslator.tts.SpeechSynthesizer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPipelineTest {

    /** Emits one full utterance on the first session, then idles (mirrors a real
     *  recognizer the pipeline restarts per turn) so tests see exactly one turn. */
    private class FakeRecognizer : SpeechRecognizer {
        private var first = true
        override fun recognize(source: Language): Flow<RecognitionEvent> = flow {
            if (first) {
                first = false
                emit(RecognitionEvent.Rms(0.5f))
                emit(RecognitionEvent.Partial("hello"))
                emit(RecognitionEvent.Partial("hello world"))
                emit(RecognitionEvent.Final("hello world"))
            }
            awaitCancellation()
        }
    }

    private class FakeTranslator : Translator {
        override suspend fun translate(text: String, from: Language, to: Language) = "translated:$text"
    }

    private class RecordingSynthesizer : SpeechSynthesizer {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: Language) { spoken += text }
        override fun shutdown() {}
    }

    @Test
    fun `maps recognition events to transcribe translate turn speak`() = runTest {
        val synth = RecordingSynthesizer()
        val pipeline = TranslationPipeline(
            recognizer = FakeRecognizer(),
            translator = FakeTranslator(),
            synthesizer = synth,
            modelManager = ModelManager(), // English target is ready by default
        )

        // The pipeline loops turn-after-turn, so bound it to one turn's states.
        val states = pipeline.run(Language.ENGLISH, Language.ENGLISH).take(6).toList()

        assertEquals(
            listOf(
                PipelineState.Listening(0.5f),
                PipelineState.Transcribing("hello"),
                PipelineState.Transcribing("hello world"),
                PipelineState.Translating("hello world"),
                // Turn is committed before Speaking, so playback can't hide it.
                PipelineState.Turn("hello world", "translated:hello world"),
                PipelineState.Speaking("hello world", "translated:hello world"),
            ),
            states,
        )
        assertEquals(listOf("translated:hello world"), synth.spoken)
    }

    @Test
    fun `still emits the translated turn when speech playback fails`() = runTest {
        val failingSynth = object : SpeechSynthesizer {
            override suspend fun speak(text: String, language: Language) =
                throw IllegalStateException("no voice data")
            override fun shutdown() {}
        }
        val pipeline = TranslationPipeline(
            recognizer = FakeRecognizer(),
            translator = FakeTranslator(),
            synthesizer = failingSynth,
            modelManager = ModelManager(),
        )

        val states = pipeline.run(Language.ENGLISH, Language.ENGLISH).take(7).toList()

        assertTrue(states.contains(PipelineState.Turn("hello world", "translated:hello world")))
        assertTrue(states.any { it is PipelineState.SpeechFailed && it.language == Language.ENGLISH })
    }

    @Test
    fun `errors immediately when target model is not downloaded`() = runTest {
        val pipeline = TranslationPipeline(
            recognizer = FakeRecognizer(),
            translator = FakeTranslator(),
            synthesizer = RecordingSynthesizer(),
            modelManager = ModelManager(),
        )

        val states = pipeline.run(Language.ENGLISH, Language.SPANISH).toList()
        assertEquals(1, states.size)
        assertTrue(states.first() is PipelineState.Error)
    }
}
