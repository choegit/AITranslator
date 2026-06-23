package com.example.aitranslator.model

/**
 * A language the app can recognize, translate, or speak.
 *
 * [code] is a BCP-47 language tag (e.g. "en", "es") used to drive the real
 * platform APIs (TextToSpeech locale) and to key offline models.
 */
data class Language(
    val code: String,
    val displayName: String,
) {
    companion object {
        val ENGLISH = Language("en", "English")
        val SPANISH = Language("es", "Spanish")
        val FRENCH = Language("fr", "French")
        val GERMAN = Language("de", "German")
        val JAPANESE = Language("ja", "Japanese")

        /** Every language the prototype knows about. */
        val ALL = listOf(ENGLISH, SPANISH, FRENCH, GERMAN, JAPANESE)
    }
}
