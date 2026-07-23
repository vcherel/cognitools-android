package com.example.myapp.deezer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Locks DeezerCrypto against an independent Python (hashlib + pycryptodome) reference. If Deezer's
 * fixed constants were ever mistyped, or the chunk striping drifted, these vectors fail.
 */
class DeezerCryptoTest {

    // ---- Key derivation ----

    @Test
    fun blowfishKey_matchesReferenceVector_3135556() {
        // Python: bytes(ord(md5[i]) ^ ord(md5[i+16]) ^ ord(SECRET[i]) for i in range(16))
        val expected = intArrayOf(108, 108, 102, 107, 57, 102, 44, 55, 101, 37, 117, 96, 60, 100, 52, 57)
            .map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, DeezerCrypto.blowfishKey("3135556"))
    }

    @Test
    fun blowfishKey_matchesReferenceVector_916424() {
        val expected = intArrayOf(97, 51, 111, 106, 51, 105, 38, 96, 49, 126, 39, 100, 105, 106, 49, 102)
            .map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, DeezerCrypto.blowfishKey("916424"))
    }

    @Test
    fun blowfishKey_isSixteenBytes() {
        assertEquals(16, DeezerCrypto.blowfishKey("1").size)
    }

    // ---- Full stripe decrypt ----

    /** Rebuilds the exact byte buffer the Python reference (refstripe.py) fed through the routine. */
    private fun referenceInput(): ByteArray {
        val out = ByteArray(6 * DeezerCrypto.CHUNK_SIZE + 500)
        var pos = 0
        for (i in 0 until 6) {
            for (j in 0 until DeezerCrypto.CHUNK_SIZE) out[pos++] = ((i * 37 + j) and 0xFF).toByte()
        }
        for (j in 0 until 500) out[pos++] = ((100 + j) and 0xFF).toByte()
        return out
    }

    private fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun decryptFullTrack_matchesReferenceMd5() {
        val input = referenceInput()
        assertEquals("186e7938f32567ca0925b423fa0a8b5e", md5Hex(input)) // sanity: same input as reference
        val dec = DeezerCrypto.decryptFullTrack("3135556", input)
        assertEquals(12788, dec.size)
        assertEquals("b0d677ebe673b58efdb12b6d7c999f86", md5Hex(dec))
    }

    @Test
    fun decryptFullTrack_encryptsChunkZeroAndThree() {
        val dec = DeezerCrypto.decryptFullTrack("3135556", referenceInput())
        assertBytesEqual(intArrayOf(70, 14, 225, 219, 205, 221, 155, 12), dec, 0)      // chunk 0 encrypted
        assertBytesEqual(intArrayOf(64, 170, 55, 24, 40, 158, 90, 47), dec, 6144)      // chunk 3 encrypted
    }

    @Test
    fun decryptFullTrack_passesThroughChunksOneAndTwo() {
        val input = referenceInput()
        val dec = DeezerCrypto.decryptFullTrack("3135556", input)
        // Chunks 1 and 2 (index % 3 != 0) are untouched.
        assertTrue(input.copyOfRange(2048, 4096).contentEquals(dec.copyOfRange(2048, 4096)))
        assertTrue(input.copyOfRange(4096, 6144).contentEquals(dec.copyOfRange(4096, 6144)))
    }

    @Test
    fun decryptFullTrack_passesThroughShortFinalChunk() {
        val input = referenceInput()
        val dec = DeezerCrypto.decryptFullTrack("3135556", input)
        // Final 500 byte chunk (index 6, not full 2048) is passed through even though 6 % 3 == 0.
        assertTrue(input.copyOfRange(12288, 12788).contentEquals(dec.copyOfRange(12288, 12788)))
    }

    private fun assertBytesEqual(expected: IntArray, actual: ByteArray, offset: Int) {
        for (i in expected.indices) {
            assertEquals("byte at ${offset + i}", expected[i], actual[offset + i].toInt() and 0xFF)
        }
    }
}
