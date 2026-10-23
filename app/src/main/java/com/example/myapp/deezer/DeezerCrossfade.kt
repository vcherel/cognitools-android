package com.example.myapp.deezer

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Loudness of what the main player is about to output, measured on its PCM as a tee in the audio
 * sink (playback thread). [shortLevel] follows the last half second, [fastLevel] the last 50 ms,
 * [trackLevel] is the mean over the whole track so far, all as mean squares; [Crossfader] compares
 * them to spot an outro or a silence, and reads [fastLevel] to hear when the next track starts.
 * Works on the decoded buffers before the player's volume is applied, so the fades don't fool it.
 */
class LevelMeter : TeeAudioProcessor.AudioBufferSink {
    @Volatile var shortLevel = 0.0
        private set
    @Volatile var fastLevel = 0.0
        private set
    @Volatile private var trackSum = 0.0
    @Volatile private var trackFrames = 0L
    @Volatile private var resetPending = false

    private var sampleRate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    /** Mean square over the track so far, or null until it has heard enough of it to mean anything. */
    val trackLevel: Double?
        get() = trackFrames.takeIf { it >= sampleRate * MIN_TRACK_SECONDS }?.let { trackSum / it }

    /** Called on the main thread when the track changes; applied by the playback thread on its next buffer. */
    fun resetTrack() {
        resetPending = true
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        // A seek or a skip: whatever comes next is new audio, the old level must not linger.
        fastLevel = 0.0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (resetPending) {
            resetPending = false
            trackSum = 0.0
            trackFrames = 0
            shortLevel = 0.0
            fastLevel = 0.0
        }
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        val samples: Int
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                samples = b.remaining() / 2
                val start = b.position()
                for (i in 0 until samples) {
                    val v = b.getShort(start + i * 2) / 32768.0
                    sum += v * v
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                samples = b.remaining() / 4
                val start = b.position()
                for (i in 0 until samples) {
                    val v = b.getFloat(start + i * 4).toDouble()
                    sum += v * v
                }
            }
            else -> return
        }
        if (samples == 0) return
        val frames = samples / channels.coerceAtLeast(1)
        val meanSquare = sum / samples
        val seconds = frames.toDouble() / sampleRate
        shortLevel += (1 - exp(-seconds / SHORT_WINDOW_SECONDS)) * (meanSquare - shortLevel)
        fastLevel += (1 - exp(-seconds / FAST_WINDOW_SECONDS)) * (meanSquare - fastLevel)
        trackSum += meanSquare * frames
        trackFrames += frames
    }

    private companion object {
        const val SHORT_WINDOW_SECONDS = 0.5
        const val FAST_WINDOW_SECONDS = 0.05
        const val MIN_TRACK_SECONDS = 20
    }
}

/**
 * Crossfades the main player into its next queued track. The media session owns one player, so the
 * blend is done backwards: a second, session-less [fader] takes over the end of the *outgoing* track
 * at the main player's exact position, and the main player jumps to the next track's start right away
 * and fades in. Any hiccup at the handoff therefore lands on a track that is fading out, while the
 * session, the notification and the queue move on as if the track had ended normally.
 *
 * When it starts is where the smarts are, using [meter]: by default [FADE_MS] before the end, but
 * earlier when the outgoing track is already fading out on its own (a long blend over that outro) or
 * has gone silent (a straight jump, skipping the dead air). The outgoing tail stays at full volume
 * until the next track is actually heard, so a lead in silence never opens a hole in the middle of
 * the blend. Only automatic transitions are touched:
 * any skip, seek or pause from the user cancels a blend in progress and restores the volume.
 */
class Crossfader(
    context: Context,
    private val player: ExoPlayer,
    private val meter: LevelMeter,
    faderSourceFactory: MediaSource.Factory,
    private val scope: CoroutineScope
) {
    private val fader: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(faderSourceFactory)
        // No audio focus: requesting it would make the main player pause for its own fader.
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            false
        )
        .build()

    private var watchJob: Job? = null
    private var fadeJob: Job? = null
    private var faderItemId: String? = null
    private var ownSeek = false
    private var lowSince = 0L
    private var silentSince = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startWatching() else watchJob?.cancel()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) cancel()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !ownSeek) cancel()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                meter.resetTrack()
                lowSince = 0L
                silentSince = 0L
                if (fadeJob?.isActive != true) unloadFader()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) cancel()
            }
        })
        fader.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Whatever the outgoing tail was doing, the next track is what matters: let it play.
                fader.stop()
                faderItemId = null
            }
        })
    }

    fun release() {
        cancel()
        fader.release()
    }

    private fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (isActive) {
                val remaining = tick()
                delay(if (remaining != null && remaining < PREPARE_MS) FINE_POLL_MS else COARSE_POLL_MS)
            }
        }
    }

    /** One look at the main player; returns the time left in its track when a blend is possible. */
    private fun tick(): Long? {
        if (fadeJob?.isActive == true) return null
        if (!player.isPlaying || !player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE) return null
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration < MIN_TRACK_MS) return null
        val remaining = duration - player.currentPosition
        if (remaining > PREPARE_MS) return remaining

        val item = player.currentMediaItem ?: return null
        if (faderItemId != item.mediaId) loadFader(item)

        val now = SystemClock.elapsedRealtime()
        val trackLevel = meter.trackLevel
        val silent = trackLevel != null && meter.fastLevel / trackLevel < SILENCE_RATIO
        val low = trackLevel != null && meter.shortLevel / trackLevel < OUTRO_RATIO
        silentSince = if (silent) silentSince.takeIf { it > 0 } ?: now else 0L
        lowSince = if (low) lowSince.takeIf { it > 0 } ?: now else 0L

        val inWindow = remaining <= SMART_WINDOW_MS
        when {
            inWindow && silentSince > 0 && now - silentSince >= SILENCE_HOLD_MS -> jumpOverSilence()
            remaining <= FADE_MS + HANDOFF_MS -> crossfade(FADE_MS)
            inWindow && lowSince > 0 && now - lowSince >= OUTRO_HOLD_MS ->
                crossfade(remaining.coerceIn(FADE_MS, MAX_FADE_MS))
        }
        return remaining
    }

    /** Buffers the outgoing track's tail in the fader, paused and muted, so the handoff seeks inside it. */
    private fun loadFader(item: MediaItem) {
        faderItemId = item.mediaId
        fader.volume = 0f
        fader.playWhenReady = false
        fader.setMediaItem(item, player.currentPosition)
        fader.prepare()
    }

    private fun unloadFader() {
        faderItemId = null
        fader.stop()
        fader.clearMediaItems()
    }

    /** The outgoing track has nothing left to hear: go straight to the next one, no blend needed. */
    private fun jumpOverSilence() {
        unloadFader()
        seekToNext()
    }

    private fun seekToNext() {
        ownSeek = true
        try {
            player.seekToNextMediaItem()
        } finally {
            ownSeek = false
        }
    }

    private fun crossfade(fadeMs: Long) {
        if (fader.playbackState != Player.STATE_READY || faderItemId != player.currentMediaItem?.mediaId) {
            // Not buffered in time: the track just ends and the next one starts, as before.
            return
        }
        fadeJob = scope.launch {
            try {
                // 1. The fader joins the outgoing track muted. Each seek costs it a restart the main
                // player doesn't pay, so it aims ahead by what the last try came out behind, and only
                // takes over once its settled position matches: a swap between two copies a few tens
                // of ms apart is heard as a stutter. Not aligned in time: the next tick tries again.
                var lead = 0L
                var aligned = false
                for (attempt in 1..ALIGN_TRIES) {
                    if (player.currentMediaItem?.mediaId != faderItemId) return@launch
                    fader.seekTo(player.currentPosition + lead)
                    fader.play()
                    if (!awaitPlaying(fader, HANDOFF_TIMEOUT_MS)) return@launch
                    delay(SETTLE_MS)
                    val drift = measureDrift()
                    if (abs(drift) <= MAX_DRIFT_MS) {
                        aligned = true
                        break
                    }
                    lead += drift
                }
                if (!aligned) return@launch

                // 2. A quick swap over the same audio hands the outgoing track to the fader.
                ramp(SWAP_MS) { t ->
                    fader.volume = t
                    player.volume = 1f - t
                }

                // 3. The main player moves on to the next track and the two blend, equal power.
                seekToNext()
                awaitIncoming()
                ramp(minOf(fadeMs, tailLeft()).coerceAtLeast(MIN_FADE_MS)) { t ->
                    fader.volume = cos(t * PI / 2).toFloat()
                    player.volume = sin(t * PI / 2).toFloat()
                }
            } finally {
                player.volume = 1f
                unloadFader()
            }
        }
    }

    private fun cancel() {
        fadeJob?.cancel()
        fadeJob = null
        player.volume = 1f
        if (faderItemId != null) unloadFader()
    }

    /** Main minus fader position, averaged over a few reads since each one jitters by some ms. */
    private suspend fun measureDrift(): Long {
        var sum = 0L
        repeat(DRIFT_SAMPLES) {
            sum += player.currentPosition - fader.currentPosition
            delay(DRIFT_SAMPLE_MS)
        }
        return sum / DRIFT_SAMPLES
    }

    /** Time left in the outgoing tail the fader plays, 0 once it ended or failed. */
    private fun tailLeft(): Long {
        if (fader.playbackState != Player.STATE_READY) return 0L
        val duration = fader.duration
        return if (duration == C.TIME_UNSET) Long.MAX_VALUE else duration - fader.currentPosition
    }

    /**
     * Holds the fader at full volume until the next track is heard: until then the main player is
     * buffering or playing its lead in silence. Gives up when the tail is about to run out, or when
     * the next track has played a while without getting louder than a whisper.
     */
    private suspend fun awaitIncoming() {
        var playingSince = 0L
        while (tailLeft() > MIN_FADE_MS) {
            if (player.isPlaying && player.playbackState == Player.STATE_READY) {
                val now = SystemClock.elapsedRealtime()
                if (playingSince == 0L) playingSince = now
                if (meter.fastLevel >= AUDIBLE_LEVEL || now - playingSince >= MAX_LEAD_IN_MS) return
            }
            delay(FINE_POLL_MS)
        }
    }

    private suspend fun awaitPlaying(p: Player, timeoutMs: Long): Boolean {
        delay(FINE_POLL_MS)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!(p.isPlaying && p.playbackState == Player.STATE_READY)) {
            if (SystemClock.elapsedRealtime() > deadline) return false
            delay(FINE_POLL_MS)
        }
        return true
    }

    private suspend fun ramp(durationMs: Long, apply: (Float) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val t = ((SystemClock.elapsedRealtime() - start).toFloat() / durationMs).coerceIn(0f, 1f)
            apply(t)
            if (t >= 1f) return
            delay(RAMP_STEP_MS)
        }
    }

    private companion object {
        const val FADE_MS = 6_000L
        const val MAX_FADE_MS = 10_000L
        /** How far before the end an outro or silence is looked for. */
        const val SMART_WINDOW_MS = 16_000L
        /** When the fader starts buffering the tail, a little ahead of that window. */
        const val PREPARE_MS = 20_000L
        const val MIN_TRACK_MS = 30_000L
        /** Time the handoff and the next track's lead in take, so the default blend still gets its full length. */
        const val HANDOFF_MS = 2_000L
        const val HANDOFF_TIMEOUT_MS = 1_500L
        const val MIN_FADE_MS = 1_500L
        const val ALIGN_TRIES = 3
        /** Lets a freshly started audio track's position estimate settle before trusting it. */
        const val SETTLE_MS = 400L
        const val DRIFT_SAMPLES = 5
        const val DRIFT_SAMPLE_MS = 10L
        const val MAX_DRIFT_MS = 10L
        const val SWAP_MS = 80L
        const val RAMP_STEP_MS = 30L
        const val COARSE_POLL_MS = 500L
        const val FINE_POLL_MS = 20L

        /** Short level (outro) and fast level (silence) under the track's mean, as mean square ratios: about -8 dB, and -25 dB. */
        const val OUTRO_RATIO = 0.15
        const val SILENCE_RATIO = 0.003
        const val OUTRO_HOLD_MS = 1_500L
        const val SILENCE_HOLD_MS = 800L
        /** Absolute mean square the next track must reach to count as started, about -40 dBFS. */
        const val AUDIBLE_LEVEL = 1e-4
        const val MAX_LEAD_IN_MS = 3_000L
    }
}
