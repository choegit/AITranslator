package com.example.aitranslator.translation

import com.example.aitranslator.model.Language

/**
 * Text-to-text translation stage. A single [translate] call maps a finalized
 * source utterance into the target language.
 *
 * The real implementation would run an on-device NMT model; the interface lets
 * the pipeline depend only on the contract.
 */
interface Translator {
    suspend fun translate(text: String, from: Language, to: Language): String
}
