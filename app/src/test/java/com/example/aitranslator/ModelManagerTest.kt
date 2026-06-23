package com.example.aitranslator

import com.example.aitranslator.model.Language
import com.example.aitranslator.model.ModelManager
import com.example.aitranslator.model.ModelState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManagerTest {

    @Test
    fun `english ships ready and others start not downloaded`() {
        val manager = ModelManager()
        assertTrue(manager.isReady(Language.ENGLISH))
        assertFalse(manager.isReady(Language.SPANISH))
    }

    @Test
    fun `download transitions through downloading to ready`() = runTest {
        val manager = ModelManager()
        manager.download(Language.SPANISH)
        assertTrue(manager.isReady(Language.SPANISH))
        val spanish = manager.models.value.first { it.language == Language.SPANISH }
        assertEquals(ModelState.Ready, spanish.state)
    }

    @Test
    fun `delete returns model to not downloaded`() = runTest {
        val manager = ModelManager()
        manager.download(Language.FRENCH)
        assertTrue(manager.isReady(Language.FRENCH))
        manager.delete(Language.FRENCH)
        assertFalse(manager.isReady(Language.FRENCH))
        val french = manager.models.value.first { it.language == Language.FRENCH }
        assertEquals(ModelState.NotDownloaded, french.state)
    }
}
