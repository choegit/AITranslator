package com.example.aitranslator

import com.example.aitranslator.model.Language
import com.example.aitranslator.translation.MockTranslator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MockTranslatorTest {

    private val translator = MockTranslator()

    @Test
    fun `known phrase uses phrasebook`() = runTest {
        val result = translator.translate(
            "hello how are you today",
            Language.ENGLISH,
            Language.SPANISH,
        )
        assertEquals("hola, ¿cómo estás hoy?", result)
    }

    @Test
    fun `phrasebook lookup ignores case punctuation and whitespace`() = runTest {
        val result = translator.translate(
            "  Hello how are you today?  ",
            Language.ENGLISH,
            Language.SPANISH,
        )
        assertEquals("hola, ¿cómo estás hoy?", result)
    }

    @Test
    fun `unknown phrase falls back to tagged passthrough`() = runTest {
        val result = translator.translate("xyzzy", Language.ENGLISH, Language.FRENCH)
        assertEquals("[fr] xyzzy", result)
    }
}
