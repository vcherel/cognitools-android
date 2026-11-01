package com.example.myapp.recipes

import com.example.myapp.notes.parseCourseGroups
import com.example.myapp.notes.presentIngredients
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeNotesTest {
    private val groups = parseCourseGroups("--- Légumes\nCarottes\nOignons\n--- Crèmerie\nLait\n--- Épicerie\nPâtes")
    private val note = "Propose des recettes.\nIngrédients :\nCarottes\n\nLait (entamé)\n\nPâtes\nContraintes :\nRapide"

    @Test
    fun stockIgnoresTheStateNotes() {
        assertEquals(setOf("carottes", "lait", "pâtes"), stockKeys(note))
    }

    @Test
    fun deletingKeepsTheRestOfTheNote() {
        val after = withoutIngredients(note, groups, listOf("Lait"))
        assertEquals(listOf("Carottes", "Pâtes"), presentIngredients(after))
        assertEquals("Propose des recettes.", after.lines().first())
        assertEquals("Rapide", after.lines().last())
    }

    @Test
    fun groceriesGoUnderTheirSectionOnce() {
        val courses = withCourseItems("--- Légumes\n[x] Carottes", groups, listOf("oignons", "Orange", "Oignons", "Carottes"))
        assertEquals("--- Légumes\n[x] Carottes\n[ ] Oignons\n--- Autres\n[ ] Orange", courses)
    }
}
