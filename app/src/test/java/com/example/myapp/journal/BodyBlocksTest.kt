package com.example.myapp.journal

import org.junit.Assert.assertEquals
import org.junit.Test

class BodyBlocksTest {
    @Test
    fun drawnTablesGoAtTheirMarksAndUnplacedOnesAtTheEnd() {
        val blocks = bodyBlocks("Intro.\n\n[table 1]\n\nEnd.", listOf("data:a", "data:b"))
        assertEquals(
            listOf(
                BodyBlock.Text("Intro."),
                BodyBlock.Picture("data:a"),
                BodyBlock.Text("End."),
                BodyBlock.Picture("data:b")
            ),
            blocks
        )
    }

    @Test
    fun textTablesAreParsedWithoutTheSeparatorRow() {
        val blocks = bodyBlocks("Intro.\n| Model | Score |\n|---|---:|\n| **Neon** | 71 |\nEnd.", emptyList())
        assertEquals(
            listOf(
                BodyBlock.Text("Intro."),
                BodyBlock.Table(listOf(listOf("Model", "Score"), listOf("Neon", "71"))),
                BodyBlock.Text("End.")
            ),
            blocks
        )
    }

    @Test
    fun aMarkWithoutItsTableStaysText() {
        assertEquals(listOf(BodyBlock.Text("[table 2]")), bodyBlocks("[table 2]", emptyList()))
    }
}
