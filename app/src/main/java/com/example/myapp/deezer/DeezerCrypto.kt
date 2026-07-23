package com.example.myapp.deezer

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Blowfish "stripe" decryption for Deezer streams. Pure functions, no Android deps, so they run in
 * a plain JVM unit test. The algorithm is fixed and well known (see deemix / deezer-py); the test
 * vectors in DeezerCryptoTest lock it against an independent Python reference.
 *
 * A Deezer CDN file is split into 2048 byte chunks. Every third chunk (index % 3 == 0), if it is a
 * full 2048 bytes, is Blowfish/CBC encrypted with a per track key and a fixed IV. All other chunks,
 * and the final short chunk, are stored in the clear.
 */
object DeezerCrypto {

    private const val SECRET = "g4el58wc0zvf9na1" // 16 ASCII bytes, fixed by Deezer
    const val CHUNK_SIZE = 2048
    private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    /**
     * Derives the 16 byte Blowfish key for a track from its SNG_ID. The XOR is over the ASCII
     * character codes of the md5 hex digits and the secret, not over raw nibble values.
     */
    fun blowfishKey(sngId: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest(sngId.toByteArray(Charsets.US_ASCII))
        val hex = md5.joinToString("") { "%02x".format(it) } // 32 lowercase hex chars
        return ByteArray(16) { i ->
            (hex[i].code xor hex[i + 16].code xor SECRET[i].code).toByte()
        }
    }

    /** Decrypts one full 2048 byte encrypted chunk. Fresh cipher (fresh IV) every call: CBC state must not carry over. */
    fun decryptChunk(key: ByteArray, chunk: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(IV))
        return cipher.doFinal(chunk)
    }

    /**
     * Decrypts a whole downloaded track buffer in one pass. Used by the download-then-decrypt path;
     * the streaming DataSource reuses [blowfishKey] and [decryptChunk] with its own chunk alignment.
     */
    fun decryptFullTrack(sngId: String, data: ByteArray): ByteArray {
        val key = blowfishKey(sngId)
        val out = ByteArray(data.size)
        var pos = 0
        var chunkIndex = 0
        while (pos < data.size) {
            val len = minOf(CHUNK_SIZE, data.size - pos)
            if (chunkIndex % 3 == 0 && len == CHUNK_SIZE) {
                val decrypted = decryptChunk(key, data.copyOfRange(pos, pos + CHUNK_SIZE))
                decrypted.copyInto(out, pos)
            } else {
                data.copyInto(out, pos, pos, pos + len)
            }
            pos += len
            chunkIndex++
        }
        return out
    }
}
