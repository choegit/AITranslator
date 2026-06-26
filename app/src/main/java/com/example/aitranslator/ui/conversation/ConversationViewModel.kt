package com.example.aitranslator.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aitranslator.model.Language
import com.example.aitranslator.pipeline.PipelineState
import com.example.aitranslator.pipeline.TranslationPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
        if (_uiState.value.isListening) return
        launchPipeline()
    }

    /**
     * (Re)starts the pipeline with the current source/target languages. Used both
     * to begin listening and to restart it in place when the languages change
     * mid-session, since [TranslationPipeline.run] captures its languages once for
     * the whole session. The previous job is cancelled and joined first so its
     * recognizer releases the microphone before the new one starts (half-duplex).
     * The [onCompletion] reset only fires for the still-active job, so a restart's
     * teardown can't clobber the fresh session's state.
     */
    private fun launchPipeline() {
        val previous = pipelineJob
        val source = _uiState.value.sourceLanguage
        val target = _uiState.value.targetLanguage
        pipelineJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            val self = coroutineContext[Job]
            _uiState.update {
                it.copy(isListening = true, statusLabel = "Listening…", partialTranscript = "", error = null)
            }
            pipeline.run(source, target)
                .onCompletion {
                    // Covers both Stop and a pipeline that ends itself (e.g. the
                    // model-not-ready gate emits an error then completes). Skip it
                    // when a restart has already replaced this job.
                    if (pipelineJob === self) {
                        _uiState.update {
                            it.copy(isListening = false, amplitude = 0f, statusLabel = "Idle", partialTranscript = "")
                        }
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
                is PipelineState.Preparing -> ui.copy(statusLabel = state.message)
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
    fun setSourceLanguage(language: Language) = changeLanguages {
        if (language == it.targetLanguage) {
            it.copy(sourceLanguage = language, targetLanguage = it.sourceLanguage)
        } else {
            it.copy(sourceLanguage = language)
        }
    }

    fun setTargetLanguage(language: Language) = changeLanguages {
        if (language == it.sourceLanguage) {
            it.copy(targetLanguage = language, sourceLanguage = it.targetLanguage)
        } else {
            it.copy(targetLanguage = language)
        }
    }

    fun swapLanguages() = changeLanguages {
        it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
    }

    /**
     * Applies a language change and, if a session is in progress, restarts the
     * pipeline so the new languages take effect immediately — otherwise the swap
     * would only relabel the UI while the running session kept the old direction.
     */
    private fun changeLanguages(transform: (ConversationUiState) -> ConversationUiState) {
        _uiState.update(transform)
        if (_uiState.value.isListening) launchPipeline()
    }

    fun clearError() = _uiState.update { it.copy(error = null, voiceToInstall = null) }

    override fun onCleared() {
        super.onCleared()
        pipelineJob?.cancel()
    }
}
