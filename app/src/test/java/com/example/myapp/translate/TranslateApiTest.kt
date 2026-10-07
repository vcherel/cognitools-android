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

    @Test
    fun `a single word comes with its dictionary, definitions and examples`() {
        val body = """[[["ancien","former",null,null,10]],[["adjectif",["ancien","premier"],[["ancien",["former","old","ancient"],null,0.3],["premier",["first","former"],null,0.03]],"former",3]],"en",null,null,[["former",null,[["ancien",null,true,false,[10]],["premier",null,true,false,[2]],["ex",null,true,false,[8]]],[[0,6]],"former",0,0]],0.69,[],[["en"],null,[0.69],["en"]],null,null,null,[["adjective",[["having previously filled a particular role.","m1","her former boyfriend"]],"former",3]],[[["her <b>former</b> boyfriend",null,null,null,null,"m1"],["in <b>former</b> times",null,null,null,null,"m2"]]]]"""

        val result = parseTranslation(body, "former", TranslateLang.FR)

        assertEquals("ancien", result.translation)
        assertEquals(TranslateLang.EN, result.from)
        assertEquals(listOf("former", "old", "ancient"), result.entries.single().terms.first().back)
        assertEquals("her former boyfriend", result.definitions.single().example)
        assertEquals(listOf("in former times"), result.examples)
        assertEquals(listOf("premier", "ex"), result.alternatives)
    }

    @Test
    fun `leading articles are dropped to find the word to look up`() {
        assertEquals("former", headWord("the former"))
        assertEquals("ancien", headWord("l'ancien"))
        assertEquals(null, headWord("former"))
        assertEquals(null, headWord("I like it"))
    }
}
