package com.example.myapp.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeBumpTest {
    @Test
    fun nextEpisode() {
        assertEquals("- Breaking bad S1E11", "- Breaking bad S1E10".withEpisodeBumped(season = false))
    }

    @Test
    fun nextSeasonResetsEpisode() {
        assertEquals("- Breaking bad S2E1", "- Breaking bad S1E10".withEpisodeBumped(season = true))
    }

    @Test
    fun lowercaseAndSpacedAreRecognized() {
        assertEquals("- Dark S3E5", "- Dark s3 e4".withEpisodeBumped(season = false))
    }

    @Test
    fun onlySeriesLinesMatch() {
        assertTrue("- Severance S2E3".hasEpisode())
        assertFalse("- Oppenheimer".hasEpisode())
        assertFalse("- SE7EN".hasEpisode())
    }
}
