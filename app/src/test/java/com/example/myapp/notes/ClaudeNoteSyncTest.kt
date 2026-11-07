package com.example.myapp.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudeNoteSyncTest {
    private val note = "**__CogniTools__**\nFix the menu\nSlow gallery\n\n**__rpg-ai__**\nNew quest\n"

    @Test
    fun removesTheSolvedLinesAndKeepsTheTitles() {
        val (text, removed) = withSolvedRemoved(note, listOf("Fix the menu", " Slow gallery "))
        assertEquals("**__CogniTools__**\n\n**__rpg-ai__**\nNew quest\n", text)
        assertEquals(2, removed)
    }

    @Test
    fun mergesTheBlankLinesARemovalLeavesSideBySide() {
        val (text, _) = withSolvedRemoved("A\n\nB\n\nC", listOf("B"))
        assertEquals("A\n\nC", text)
    }

    @Test
    fun skipsLinesNoLongerInTheNote() {
        val (text, removed) = withSolvedRemoved(note, listOf("Gone", ""))
        assertEquals(note, text)
        assertEquals(0, removed)
    }

    @Test
    fun removesOneCopyPerSolvedLine() {
        val (text, _) = withSolvedRemoved("X\nX", listOf("X"))
        assertEquals("X", text)
    }
}
