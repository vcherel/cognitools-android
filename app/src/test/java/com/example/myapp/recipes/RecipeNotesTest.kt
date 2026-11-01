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
    fun groceriesGoUnderTheirSectionOnce() {
        val courses = withCourseItems("--- Légumes\n[x] Carottes", groups, listOf("oignons", "Orange", "Oignons", "Carottes"))
        assertEquals("--- Légumes\n[x] Carottes\n[ ] Oignons\n--- Autres\n[ ] Orange", courses)
    }
}
