package com.example.aitranslator.ui.conversation

import com.example.aitranslator.model.Language

/** One completed exchange shown in the conversation history. */
data class ConversationTurn(
    val sourceText: String,
    val translatedText: String,
)

/** Everything the conversation screen renders. */
data class ConversationUiState(
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.SPANISH,
    val isListening: Boolean = false,
    val amplitude: Float = 0f,
    val partialTranscript: String = "",
    val statusLabel: String = "Idle",
    val history: List<ConversationTurn> = emptyList(),
    val error: String? = null,
    /** Set when [error] is due to a missing voice; the UI offers to install it. */
    val voiceToInstall: Language? = null,
)
