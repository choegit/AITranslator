package com.example.aitranslator.di

import android.content.Context
import com.example.aitranslator.BuildConfig
import com.example.aitranslator.asr.AndroidSpeechRecognizer
import com.example.aitranslator.asr.MockSpeechRecognizer
import com.example.aitranslator.asr.SpeechModelManager
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
 * Each ML stage is provided behind its interface. The `OFFLINE_DEMO` build flag
 * selects the scripted mock stages (no speech models / no ML Kit downloads —
 * fully offline and deterministic) vs. the real on-device engines, with no
 * impact on the pipeline, viewmodels, or UI.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAudioCapture(): AudioCapture = AudioRecordCapture()

    @Provides
    @Singleton
    fun provideSpeechRecognizer(
        @ApplicationContext context: Context,
        audioCapture: AudioCapture,
    ): SpeechRecognizer =
        if (BuildConfig.OFFLINE_DEMO) {
            MockSpeechRecognizer(audioCapture) // scripted phrases over the real mic
        } else {
            AndroidSpeechRecognizer(context) // real on-device recognition (SODA)
        }

    @Provides
    @Singleton
    fun provideTranslator(): Translator =
        if (BuildConfig.OFFLINE_DEMO) {
            MockTranslator() // canned phrasebook, no model downloads
        } else {
            MLKitTranslator() // real on-device translation
        }

    @Provides
    @Singleton
    fun provideSpeechSynthesizer(@ApplicationContext context: Context): SpeechSynthesizer =
        AndroidTextToSpeech(context)

    @Provides
    @Singleton
    fun provideModelManager(): ModelManager = ModelManager()

    @Provides
    @Singleton
    fun provideSpeechModelManager(@ApplicationContext context: Context): SpeechModelManager =
        SpeechModelManager(context)

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
