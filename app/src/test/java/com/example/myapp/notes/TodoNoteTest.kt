package com.example.myapp.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class TodoNoteTest {
    private val note = "[ ] Milk\n[x] Bread\n--- Attente\n[ ] Call\n--- Week-end"

    @Test
    fun placeholdersGoWhereTheViewShowsAddRows() {
        val (text, _) = withTodoPlaceholders(note, 0)
        assertEquals(
            "[ ] \n[ ] Milk\n[x] Bread\n[ ] \n--- Attente\n[ ] Call\n[ ] \n--- Week-end\n[ ] ",
            text
        )
    }

    @Test
    fun caretFollowsItsLine() {
        val caret = note.indexOf("Call")
        val (text, moved) = withTodoPlaceholders(note, caret)
        assertEquals(text.indexOf("Call"), moved)
    }

    @Test
    fun noPlaceholderNextToAnEmptyCheckbox() {
        val (text, _) = withTodoPlaceholders("[ ] \n--- Attente\n[ ] Call\n[ ] ", 0)
        assertEquals("[ ] \n--- Attente\n[ ] Call\n[ ] ", text)
    }

    @Test
    fun strippingRemovesOnlyEmptyUncheckedItems() {
        val (text, _) = withTodoPlaceholders(note, 0)
        assertEquals(note, withoutEmptyCheckboxes(text))
        assertEquals("[x] \n[ ] A", withoutEmptyCheckboxes("[ ]  \n[x] \n[ ] A"))
    }
}
