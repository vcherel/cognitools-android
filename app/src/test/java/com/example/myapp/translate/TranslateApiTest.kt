package com.example.myapp.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateApiTest {

    @Test
    fun `short text goes out in one piece`() {
        assertEquals(listOf("Bonjour"), splitForTranslation("Bonjour", max = 20))
    }

    @Test
    fun `long text is cut without losing anything`() {
        val text = "Une phrase. Une autre phrase un peu plus longue. Et une dernière."

        val chunks = splitForTranslation(text, max = 30)

        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 30 })
    }

    @Test
    fun `a sentence end late in the window is where the cut lands`() {
        val text = "a".repeat(20) + ". " + "b".repeat(20)

        val chunks = splitForTranslation(text, max = 30)

        assertEquals(text, chunks.joinToString(""))
        assertEquals("a".repeat(20) + ".", chunks.first())
    }

    @Test
    fun `a sentence with no break at all is cut on a space`() {
        val text = "mot ".repeat(20).trim()

        val chunks = splitForTranslation(text, max = 30)

        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 30 })
    }
}
