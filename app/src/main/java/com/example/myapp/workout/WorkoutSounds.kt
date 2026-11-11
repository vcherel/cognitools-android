package com.example.myapp.workout

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val RATE = 44100

/** One voice of a sound: a tone gliding from [from] to [to] Hz, or noise, starting [at] ms in. */
private data class Voice(
    val at: Int,
    val ms: Int,
    val from: Float,
    val to: Float = from,
    val gain: Float = 0.5f,
    val noise: Boolean = false
)

private fun note(at: Int, ms: Int, hz: Float, gain: Float = 0.45f) = Voice(at, ms, hz, hz, gain)

/** Mixes the voices into 16 bit PCM, each with a short attack and an exponential fade. */
private fun render(voices: List<Voice>): ShortArray {
    val total = voices.maxOf { it.at + it.ms } * RATE / 1000 + 1
    val mix = FloatArray(total)
    val random = Random(1)
    for (v in voices) {
        val start = v.at * RATE / 1000
        val length = v.ms * RATE / 1000
        var phase = 0.0
        for (i in 0 until length) {
            val t = i / length.toFloat()
            val hz = v.from + (v.to - v.from) * t
            phase += 2 * PI * hz / RATE
            val attack = min(1f, i / (RATE * 0.004f))
            val envelope = attack * exp(-4f * t)
            val sample = if (v.noise) random.nextFloat() * 2 - 1 else (sin(phase) + 0.25 * sin(2 * phase)).toFloat() / 1.25f
            mix[start + i] += sample * envelope * v.gain
        }
    }
    return ShortArray(total) { (mix[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
}

private val C5 = 523.25f
private val E5 = 659.25f
private val G5 = 783.99f
private val C6 = 1046.5f
private val D6 = 1174.66f
private val E6 = 1318.51f
private val G6 = 1567.98f
private val C7 = 2093f

private val TAP = listOf(Voice(0, 55, 1100f, 1700f, 0.35f))
private val TICK = listOf(note(0, 40, 1000f, 0.35f))
private val GO = listOf(note(0, 160, G6, 0.4f))
private val SET = listOf(note(0, 80, G5), note(70, 160, D6))
private val BEST = listOf(note(0, 80, C6), note(65, 80, E6), note(130, 80, G6), note(195, 320, C7, 0.4f))
private val STRIKE = listOf(Voice(0, 90, 0f, gain = 0.35f, noise = true), Voice(0, 180, 260f, 70f, 0.7f), note(60, 140, D6, 0.25f))
private val CRITICAL = listOf(
    Voice(0, 90, 0f, gain = 0.35f, noise = true), Voice(0, 180, 260f, 70f, 0.7f),
    note(80, 70, G6, 0.35f), note(140, 70, C7, 0.35f), note(200, 70, G6, 0.35f), note(260, 300, C7, 0.4f)
)
private val ROUND =listOf(note(0, 120, C5), note(90, 120, E5), note(180, 120, G5), note(270, 420, C6))
private val END = listOf(note(0, 130, 880f), note(220, 260, 880f))
private val VICTORY = listOf(
    note(0, 110, C5), note(100, 110, E5), note(200, 110, G5), note(300, 160, C6),
    note(460, 700, E6, 0.35f), note(460, 700, G6, 0.3f), note(460, 700, C6, 0.3f)
)

/**
 * The session's vibrations and sounds. The sounds are synthesized once and played on the
 * notification stream without audio focus, so music keeps playing under them.
 */
class Cues(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val tracks = mutableListOf<AudioTrack>()

    private fun track(voices: List<Voice>): AudioTrack? = runCatching {
        val pcm = render(voices)
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size); tracks += it }
    }.getOrNull()

    private val tapSound = track(TAP)
    private val tickSound = track(TICK)
    private val goSound = track(GO)
    private val setSound = track(SET)
    private val bestSound = track(BEST)
    private val strikeSound = track(STRIKE)
    private val criticalSound = track(CRITICAL)
    private val roundSound = track(ROUND)
    private val endSound = track(END)
    private val victorySound = track(VICTORY)

    private fun AudioTrack?.fire() {
        this ?: return
        runCatching {
            if (playState != AudioTrack.PLAYSTATE_STOPPED) stop()
            reloadStaticData()
            play()
        }
    }

    private fun buzz(vararg timings: Long) = vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, *timings), -1))

    /** A plain button: +15 s, Skip, the number pad. */
    fun tap() {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        tapSound.fire()
    }

    fun tick() {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        tickSound.fire()
    }

    /** The timed exercise's clock starts, after the lead in. */
    fun go() {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        goSound.fire()
    }

    fun set() {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        setSound.fire()
    }

    fun best() {
        buzz(40, 40, 40, 40, 120)
        bestSound.fire()
    }

    fun strike() {
        buzz(90)
        strikeSound.fire()
    }

    /** A set that rolled double XP. */
    fun critical() {
        buzz(90, 50, 40, 40, 40, 40, 160)
        criticalSound.fire()
    }

    fun round() {
        buzz(60, 60, 60, 60, 300)
        roundSound.fire()
    }

    /** A countdown reached zero, or a hold its target. */
    fun end() {
        buzz(250, 120, 250)
        endSound.fire()
    }

    fun victory() {
        buzz(80, 80, 80, 80, 400)
        victorySound.fire()
    }

    fun release() = tracks.forEach { it.release() }
}
