package com.example.myapp.deezer

import org.junit.Assert.assertEquals
import org.junit.Test

class DeezerTrackSaverTest {
    @Test
    fun writtenTagIsSkippedWhole() {
        val tag = id3Tag("Titre", "Artiste", "Album", byteArrayOf(1, 2, 3))
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())
        assertEquals(tag.size, existingId3Length(tag + audio))
    }

    @Test
    fun untaggedStreamKeepsItsFirstByte() {
        assertEquals(0, existingId3Length(ByteArray(20) { 0xFF.toByte() }))
    }

    @Test
    fun fileNameDropsForbiddenCharacters() {
        assertEquals("Artiste - AC_DC_ Live_.mp3", fileName(listOf("Artiste"), "AC/DC: Live?"))
    }

    @Test
    fun fileNameJoinsEveryArtist() {
        assertEquals("A & B - Titre.mp3", fileName(listOf("A", "B"), "Titre"))
    }
}
