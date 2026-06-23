package com.example.aitranslator.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aitranslator.model.Language
import com.example.aitranslator.model.ModelManager
import com.example.aitranslator.model.TranslationModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    private val modelManager: ModelManager,
) : ViewModel() {

    val models: StateFlow<List<TranslationModel>> = modelManager.models.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = modelManager.models.value,
    )

    fun download(language: Language) {
        viewModelScope.launch { modelManager.download(language) }
    }

    fun delete(language: Language) = modelManager.delete(language)
}
