package com.example.aitranslator.translation

import com.example.aitranslator.model.Language
import kotlinx.coroutines.delay

/**
 * A stand-in for a real NMT model. It looks up a small canned phrasebook and
 * otherwise falls back to a clearly-marked pseudo-translation, simulating model
 * latency with a [delay] so the pipeline's "Translating" state is observable.
 */
class MockTranslator : Translator {

    override suspend fun translate(text: String, from: Language, to: Language): String {
        delay(LATENCY_MS)
        val normalized = text.trim().lowercase().removeSuffix("?").removeSuffix(".").trim()
        phrasebook[normalized to to.code]?.let { return it }
        return "[${to.code}] $text"
    }

    private companion object {
        const val LATENCY_MS = 350L

        // (sourceTextNormalized, targetLangCode) -> translation
        val phrasebook: Map<Pair<String, String>, String> = mapOf(
            ("hello how are you today" to "es") to "hola, ¿cómo estás hoy?",
            ("hello how are you today" to "fr") to "bonjour, comment allez-vous aujourd'hui ?",
            ("i would like a cup of coffee" to "es") to "me gustaría una taza de café",
            ("i would like a cup of coffee" to "fr") to "je voudrais une tasse de café",
            ("where is the nearest train station" to "es") to "¿dónde está la estación de tren más cercana?",
            ("where is the nearest train station" to "fr") to "où est la gare la plus proche ?",
            ("hola, ¿cómo estás" to "en") to "hello, how are you",
            ("me gustaría un café" to "en") to "I would like a coffee",
            ("¿dónde está la estación" to "en") to "where is the station",
        )
    }
}
