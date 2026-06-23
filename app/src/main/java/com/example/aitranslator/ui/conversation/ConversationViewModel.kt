package com.example.aitranslator.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aitranslator.model.Language
import com.example.aitranslator.pipeline.PipelineState
import com.example.aitranslator.pipeline.TranslationPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val pipeline: TranslationPipeline,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var pipelineJob: Job? = null

    fun toggleListening() {
        if (_uiState.value.isListening) stopListening() else startListening()
    }

    private fun startListening() {
        if (pipelineJob?.isActive == true) return
        val state = _uiState.value
        _uiState.update {
            it.copy(isListening = true, statusLabel = "Listening…", partialTranscript = "", error = null)
        }
        pipelineJob = viewModelScope.launch {
            pipeline.run(state.sourceLanguage, state.targetLanguage)
                .onCompletion {
                    // Covers both Stop and a pipeline that ends itself (e.g. the
                    // model-not-ready gate emits an error then completes).
                    _uiState.update {
                        it.copy(isListening = false, amplitude = 0f, statusLabel = "Idle", partialTranscript = "")
                    }
                }
                .collect(::onPipelineState)
        }
    }

    private fun stopListening() {
        pipelineJob?.cancel()
        pipelineJob = null
        _uiState.update {
            it.copy(isListening = false, amplitude = 0f, statusLabel = "Idle", partialTranscript = "")
        }
    }

    private fun onPipelineState(state: PipelineState) {
        _uiState.update { ui ->
            when (state) {
                is PipelineState.Idle -> ui.copy(statusLabel = "Idle")
                is PipelineState.Listening -> ui.copy(amplitude = state.amplitude, statusLabel = "Listening…")
                is PipelineState.Transcribing -> ui.copy(partialTranscript = state.partial, statusLabel = "Transcribing…")
                is PipelineState.Translating -> ui.copy(partialTranscript = state.sourceText, statusLabel = "Translating…")
                is PipelineState.Speaking -> ui.copy(statusLabel = "Speaking…")
                is PipelineState.Turn -> ui.copy(
                    history = ui.history + ConversationTurn(state.sourceText, state.translatedText),
                    partialTranscript = "",
                    statusLabel = "Listening…",
                )
                is PipelineState.Error -> ui.copy(error = state.message)
                is PipelineState.SpeechFailed -> ui.copy(
                    statusLabel = "Listening…",
                    error = state.message,
                    voiceToInstall = state.language,
                )
            }
        }
    }

    // Source and target must differ (you can't translate a language into itself),
    // so picking one that equals the other side swaps them, like common translators.
    fun setSourceLanguage(language: Language) = _uiState.update {
        if (language == it.targetLanguage) {
            it.copy(sourceLanguage = language, targetLanguage = it.sourceLanguage)
        } else {
            it.copy(sourceLanguage = language)
        }
    }

    fun setTargetLanguage(language: Language) = _uiState.update {
        if (language == it.sourceLanguage) {
            it.copy(targetLanguage = language, sourceLanguage = it.targetLanguage)
        } else {
            it.copy(targetLanguage = language)
        }
    }

    fun swapLanguages() = _uiState.update {
        it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
    }

    fun clearError() = _uiState.update { it.copy(error = null, voiceToInstall = null) }

    override fun onCleared() {
        super.onCleared()
        pipelineJob?.cancel()
    }
}
