package com.example.myapp.recipes

import com.example.myapp.notes.parseCourseGroups
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeNotesTest {
    private val groups = parseCourseGroups("--- Légumes\nCarottes\nOignons\n--- Crèmerie\nLait\n--- Épicerie\nPâtes")
    private val note = "Carottes\n\nLait (entamé)\n\nPâtes"

    @Test
    fun stockIgnoresTheStateNotes() {
        assertEquals(setOf("carottes", "lait", "pâtes"), stockKeys(note))
    }

    @Test
    fun deletingKeepsTheOtherGroups() {
        assertEquals("Carottes\n\nPâtes", withoutIngredients(note, groups, listOf("Lait")))
    }

    @Test
    fun commentsReplaceTheOldOne() {
        val comments = mapOf("carottes" to STARTED_COMMENT, "Lait" to "half left", "Pâtes" to "")
        assertEquals("Carottes (entamé)\n\nLait (half left)\n\nPâtes", withCommentedIngredients(note, groups, comments))
        assertEquals("Carottes\n\nLait\n\nPâtes", withCommentedIngredients(note, groups, mapOf("Lait" to " ")))
        assertEquals("entamé", ingredientComment("Lait (entamé)"))
    }

    @Test
    fun stepDurationsReadMinutesHoursAndRanges() {
        assertEquals(listOf(600), stepDurations("Cuire les pâtes 10 à 12 min."))
        assertEquals(listOf(5400, 300), stepDurations("Bake for 1 h 30, then rest 5 minutes"))
        assertEquals(listOf(5400), stepDurations("Laisser mijoter 1h30"))
        assertEquals(emptyList<Int>(), stepDurations("Ajouter 200 g de farine et 5 hommes"))
    }

    @Test
    fun groceriesGoUnderTheirSectionOnce() {
        val courses = withCourseItems("--- Légumes\n[x] Carottes", groups, listOf("oignons", "Orange", "Oignons", "Carottes"))
        assertEquals("--- Légumes\n[x] Carottes\n[ ] Oignons\n--- Autres\n[ ] Orange", courses)
    }
}
