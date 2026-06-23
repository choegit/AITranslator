package com.example.aitranslator.di

import android.content.Context
import com.example.aitranslator.asr.AndroidSpeechRecognizer
import com.example.aitranslator.asr.MockSpeechRecognizer
import com.example.aitranslator.asr.SpeechRecognizer
import com.example.aitranslator.audio.AudioCapture
import com.example.aitranslator.audio.AudioRecordCapture
import com.example.aitranslator.model.ModelManager
import com.example.aitranslator.pipeline.TranslationPipeline
import com.example.aitranslator.translation.MLKitTranslator
import com.example.aitranslator.translation.MockTranslator
import com.example.aitranslator.translation.Translator
import com.example.aitranslator.tts.AndroidTextToSpeech
import com.example.aitranslator.tts.SpeechSynthesizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Single source of truth for how the pipeline is assembled.
 *
 * Each ML stage is provided behind its interface, so moving from mock to a real
 * on-device model is a one-line change here (swap the returned implementation)
 * with no impact on the pipeline, viewmodels, or UI.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAudioCapture(): AudioCapture = AudioRecordCapture()

    @Provides
    @Singleton
    fun provideSpeechRecognizer(@ApplicationContext context: Context): SpeechRecognizer =
        // Real on-device recognizer. Swap to MockSpeechRecognizer(audioCapture) to
        // use the scripted mock that runs entirely offline without speech models.
        AndroidSpeechRecognizer(context)

    /** Retained so the mock recognizer (or a future real ASR fed PCM) can be wired in. */
    @Suppress("unused")
    fun provideMockSpeechRecognizer(audioCapture: AudioCapture): SpeechRecognizer =
        MockSpeechRecognizer(audioCapture)

    @Provides
    @Singleton
    fun provideTranslator(): Translator =
        // Real on-device translation. Swap to MockTranslator() for the offline
        // canned phrasebook that needs no model downloads.
        MLKitTranslator()

    /** Retained as the offline, no-download fallback translator. */
    @Suppress("unused")
    fun provideMockTranslator(): Translator = MockTranslator()

    @Provides
    @Singleton
    fun provideSpeechSynthesizer(@ApplicationContext context: Context): SpeechSynthesizer =
        AndroidTextToSpeech(context)

    @Provides
    @Singleton
    fun provideModelManager(): ModelManager = ModelManager()

    @Provides
    @Singleton
    fun provideTranslationPipeline(
        recognizer: SpeechRecognizer,
        translator: Translator,
        synthesizer: SpeechSynthesizer,
        modelManager: ModelManager,
    ): TranslationPipeline = TranslationPipeline(
        recognizer = recognizer,
        translator = translator,
        synthesizer = synthesizer,
        modelManager = modelManager,
    )
}
