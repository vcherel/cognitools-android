package com.example.myapp.deezer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class DeezerAudioAlignTest {
    private val rate = 44_100

    /** Noise whose loudness moves, like music, so the envelope search has something to follow. */
    private fun music(seconds: Int, seed: Int): ShortArray {
        val random = Random(seed)
        var loudness = 8_000
        return ShortArray(rate * seconds) { i ->
            if (i % 2_000 == 0) loudness = random.nextInt(1_000, 16_000)
            random.nextInt(-loudness, loudness).toShort()
        }
    }

    private fun clip(samples: ShortArray, from: Int, size: Int, startUs: Long) =
        PcmClip(samples.copyOfRange(from, from + size), startUs, rate)

    @Test
    fun findsTheOffsetOfALateSeekToTheSample() {
        val track = music(12, seed = 1)
        val reference = clip(track, 0, rate * 8, startUs = 1_000_000_000_000L)
        // Same audio, labelled 300 ms later than where it sits in the reference.
        val from = rate * 3
        val labelUs = 1_000_000_000_000L + 3_000_000L + 300_000L
        val match = findPcmOffset(reference, clip(track, from, rate * 3, startUs = labelUs))
        assertNotNull(match)
        assertEquals(300_000.0, match!!.offsetUs.toDouble(), 30.0)
    }

    @Test
    fun prefersTheTrueMatchOverARepeatedBar() {
        val bar = music(1, seed = 2)
        val noise = Random(3)
        // Every bar is the same but for a little noise, the true one is exact.
        val track = ShortArray(rate * 8) { i ->
            (bar[i % rate].toInt() + noise.nextInt(-400, 400)).coerceIn(-32768, 32767).toShort()
        }
        val match = findPcmOffset(clip(track, 0, rate * 8, startUs = 0L), clip(track, rate * 4, rate * 3, startUs = rate * 4L * 1_000_000L / rate))
        assertNotNull(match)
        assertEquals(0.0, match!!.offsetUs.toDouble(), 30.0)
    }

    @Test
    fun otherAudioDoesNotMatch() {
        assertNull(findPcmOffset(clip(music(8, 4), 0, rate * 8, 0L), clip(music(3, 5), 0, rate * 3, 0L)))
    }

    @Test
    fun silenceDoesNotMatch() {
        assertNull(findPcmOffset(PcmClip(ShortArray(rate * 8), 0L, rate), PcmClip(ShortArray(rate * 3), 0L, rate)))
    }

    @Test
    fun aTailTooShortDoesNotMatch() {
        val track = music(8, seed = 6)
        assertNull(findPcmOffset(clip(track, 0, rate * 8, 0L), clip(track, 0, rate, 0L)))
    }
}
