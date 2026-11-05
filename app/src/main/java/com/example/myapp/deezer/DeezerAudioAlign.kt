package com.example.myapp.deezer

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Mono PCM with the presentation time of its first sample. */
class PcmClip(val samples: ShortArray, val startUs: Long, val sampleRate: Int)

/** [offsetUs] is the tail's presentation time minus the reference's for the same audio. */
class PcmMatch(val offsetUs: Long, val score: Double)

/**
 * Finds where the end of [tail] sits in [reference]. Both are decodes of one file, so the true match
 * scores about 1, while a repeated bar of the music scores a little less. A rough search on the
 * loudness envelope picks a few candidates and a search on the samples settles them. Null when the
 * clips are too short, too quiet or do not match.
 */
fun findPcmOffset(reference: PcmClip, tail: PcmClip): PcmMatch? {
    val rate = tail.sampleRate
    if (reference.sampleRate != rate) return null
    val windowSize = rate * MATCH_WINDOW_SECONDS
    val windowStart = tail.samples.size - rate / 2 - windowSize
    if (windowStart < 0 || reference.samples.size < windowSize) return null
    val excerptStart = windowStart + (windowSize - EXCERPT_SIZE) / 2

    val envelopeScores = slidingScores(
        envelope(reference.samples, 0, reference.samples.size),
        envelope(tail.samples, windowStart, windowSize)
    )
    var best: PcmMatch? = null
    for (blockLag in topPeaks(envelopeScores)) {
        val around = blockLag * BLOCK_SIZE + (excerptStart - windowStart)
        val first = (around - BLOCK_SIZE * 2).coerceAtLeast(0)
        val last = (around + BLOCK_SIZE * 2).coerceAtMost(reference.samples.size - EXCERPT_SIZE)
        for (index in first..last) {
            val score = sampleScore(reference.samples, index, tail.samples, excerptStart)
            if (score > (best?.score ?: MIN_MATCH_SCORE)) {
                val tailUs = tail.startUs + excerptStart * C.MICROS_PER_SECOND / rate
                val referenceUs = reference.startUs + index * C.MICROS_PER_SECOND / rate
                best = PcmMatch(tailUs - referenceUs, score)
            }
        }
    }
    return best
}

private fun envelope(samples: ShortArray, start: Int, size: Int): DoubleArray =
    DoubleArray(size / BLOCK_SIZE) { block ->
        var sum = 0L
        for (i in start + block * BLOCK_SIZE until start + (block + 1) * BLOCK_SIZE) sum += abs(samples[i].toInt())
        sum.toDouble() / BLOCK_SIZE
    }

/** Normalized correlation of [window] against [reference] at every lag. */
private fun slidingScores(reference: DoubleArray, window: DoubleArray): DoubleArray {
    val n = window.size
    if (reference.size < n || n == 0) return DoubleArray(0)
    val mean = window.average()
    val centered = DoubleArray(n) { window[it] - mean }
    val windowEnergy = centered.sumOf { it * it }
    val sums = DoubleArray(reference.size + 1)
    val squares = DoubleArray(reference.size + 1)
    for (i in reference.indices) {
        sums[i + 1] = sums[i] + reference[i]
        squares[i + 1] = squares[i] + reference[i] * reference[i]
    }
    return DoubleArray(reference.size - n + 1) { lag ->
        var product = 0.0
        for (i in 0 until n) product += centered[i] * reference[lag + i]
        val sum = sums[lag + n] - sums[lag]
        val energy = squares[lag + n] - squares[lag] - sum * sum / n
        val norm = sqrt(windowEnergy * energy)
        if (norm > 0) product / norm else 0.0
    }
}

/** The best lags, kept apart so that one peak does not fill the whole list. */
private fun topPeaks(scores: DoubleArray): List<Int> {
    val peaks = ArrayList<Int>()
    for (lag in scores.indices.sortedByDescending { scores[it] }) {
        if (peaks.none { abs(it - lag) < PEAK_SEPARATION_BLOCKS }) peaks += lag
        if (peaks.size == CANDIDATES) break
    }
    return peaks
}

private fun sampleScore(a: ShortArray, aStart: Int, b: ShortArray, bStart: Int): Double {
    var product = 0.0
    var aEnergy = 0.0
    var bEnergy = 0.0
    for (i in 0 until EXCERPT_SIZE) {
        val x = a[aStart + i].toDouble()
        val y = b[bStart + i].toDouble()
        product += x * y
        aEnergy += x * x
        bEnergy += y * y
    }
    val norm = sqrt(aEnergy * bEnergy)
    return if (norm > 0) product / norm else 0.0
}

/**
 * The last seconds of decoded audio a player handed to its sink, with their presentation times, and
 * the sink's latest position on the same clock. The sink calls the writers on the playback thread.
 */
class PcmTap {
    private class Position(val us: Long, val atNanos: Long)

    private val ring = ShortArray(MAX_SAMPLE_RATE * RING_SECONDS)
    private var writeIndex = 0
    private var count = 0
    private var endUs = 0L
    private var sampleRate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    @Volatile private var position: Position? = null

    @Synchronized
    fun configure(sampleRate: Int, channels: Int, encoding: Int) {
        if (sampleRate != this.sampleRate) clear()
        this.sampleRate = sampleRate.coerceAtMost(MAX_SAMPLE_RATE)
        this.channels = channels.coerceAtLeast(1)
        this.encoding = encoding
    }

    @Synchronized
    fun clear() {
        count = 0
        writeIndex = 0
        position = null
    }

    @Synchronized
    fun append(buffer: ByteBuffer, presentationUs: Long) {
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        if (count > 0 && abs(presentationUs - endUs) > MAX_GAP_US) count = 0
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = data.position()
        val frames = data.remaining() / bytesPerSample / channels
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                val at = start + (frame * channels + channel) * bytesPerSample
                sum += if (encoding == C.ENCODING_PCM_16BIT) data.getShort(at).toInt()
                else (data.getFloat(at) * Short.MAX_VALUE).roundToInt().coerceIn(-32768, 32767)
            }
            ring[writeIndex] = (sum / channels).toShort()
            writeIndex = (writeIndex + 1) % capacity
        }
        count = (count + frames).coerceAtMost(capacity)
        endUs = presentationUs + frames * C.MICROS_PER_SECOND / sampleRate
    }

    private val capacity get() = sampleRate * RING_SECONDS

    /** A copy of what is held, oldest first. */
    @Synchronized
    fun clip(): PcmClip? {
        if (count == 0) return null
        val first = (writeIndex - count + capacity) % capacity
        return PcmClip(
            ShortArray(count) { ring[(first + it) % capacity] },
            endUs - count * C.MICROS_PER_SECOND / sampleRate,
            sampleRate
        )
    }

    fun notePosition(us: Long) {
        position = Position(us, SystemClock.elapsedRealtimeNanos())
    }

    /** The output position now, on the presentation time clock; null when the sink has stopped reporting. */
    fun positionNowUs(): Long? {
        val latest = position ?: return null
        val elapsedUs = (SystemClock.elapsedRealtimeNanos() - latest.atNanos) / 1_000
        return if (elapsedUs > MAX_POSITION_AGE_US) null else latest.us + elapsedUs
    }
}

/**
 * Feeds a [PcmTap] from the sink's input. Also turns off the encoder delay trim: it applies only to
 * the audio right after a sink is configured, which would leave a player that started the track and
 * one that seeked into it some tens of ms apart at the same position. Tracks blend anyway, so nothing
 * is lost at their ends.
 */
private class PcmTapSink(sink: AudioSink, private val tap: PcmTap) : ForwardingAudioSink(sink) {
    private var lastPresentationUs = C.TIME_UNSET

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        tap.configure(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding)
        super.configure(
            inputFormat.buildUpon().setEncoderDelay(0).setEncoderPadding(0).build(),
            specifiedBufferSize,
            outputChannels
        )
    }

    override fun flush() {
        lastPresentationUs = C.TIME_UNSET
        tap.clear()
        super.flush()
    }

    // A full sink is offered the same buffer again until it took all of it.
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (presentationTimeUs != lastPresentationUs) {
            lastPresentationUs = presentationTimeUs
            tap.append(buffer, presentationTimeUs)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also {
            if (it != AudioSink.CURRENT_POSITION_NOT_SET) tap.notePosition(it)
        }
}

/** Renderers whose audio sink feeds [tap], with [processors] in its chain. */
class PcmTapRenderersFactory(
    context: Context,
    private val tap: PcmTap,
    private val processors: Array<AudioProcessor> = emptyArray()
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean
    ): AudioSink = PcmTapSink(
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            .setAudioProcessors(processors)
            .build(),
        tap
    )
}

private const val MATCH_WINDOW_SECONDS = 2
private const val BLOCK_SIZE = 64
private const val EXCERPT_SIZE = 8_192
private const val CANDIDATES = 3
private const val PEAK_SEPARATION_BLOCKS = 100
private const val MIN_MATCH_SCORE = 0.9
private const val MAX_SAMPLE_RATE = 48_000
private const val RING_SECONDS = 8
private const val MAX_GAP_US = 50_000L
private const val MAX_POSITION_AGE_US = 200_000L
