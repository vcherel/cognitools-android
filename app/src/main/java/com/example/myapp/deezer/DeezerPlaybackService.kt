package com.example.myapp.deezer

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.myapp.MyApplication

/**
 * Background playback for the Deezer tool. A MediaSessionService owns the single ExoPlayer and its
 * MediaSession; Media3 provides the media notification and lockscreen controls for free. The UI
 * drives it through a MediaController (see DeezerScreen).
 *
 * Phase 1 plays a decrypted file:// URI produced by DeezerRepository. The on the fly streaming
 * DataSource and disk cache arrive in Phase 2.
 */
class DeezerPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val repo = (application as MyApplication).deezerRepository

        // dzr:// URIs -> DeezerDataSource (resolve CDN + decrypt on the fly) -> CacheDataSource (disk cache).
        val cacheFactory = CacheDataSource.Factory()
            .setCache(repo.streamCache)
            .setUpstreamDataSourceFactory(DeezerDataSource.Factory(repo))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(ErrorRecovery(player))
        mediaSession = MediaSession.Builder(this, player).build()
    }

    /**
     * Keeps the queue moving when an item fails to load. A dzr:// item can fail transiently (CDN
     * resolve hiccup, network drop between two tracks) or permanently (track not streamable): retry
     * the same one once, then skip to the next. Without this the player just parks in IDLE at the
     * end of a track and needs a manual next + play.
     */
    private class ErrorRecovery(private val player: ExoPlayer) : Player.Listener {

        private var failedMediaId: String? = null
        private var retriedCurrent = false
        private var consecutiveSkips = 0

        override fun onPlayerError(error: PlaybackException) {
            val mediaId = player.currentMediaItem?.mediaId
            if (mediaId != failedMediaId) {
                failedMediaId = mediaId
                retriedCurrent = false
            }
            Log.w(TAG, "Playback error on $mediaId (${error.errorCodeName})", error)

            if (!retriedCurrent) {
                retriedCurrent = true
                player.prepare() // resumes the same item at the position it died at
                player.play()
                return
            }
            if (consecutiveSkips >= MAX_SKIPS || !player.hasNextMediaItem()) {
                Log.w(TAG, "Giving up after $consecutiveSkips skipped tracks")
                return
            }
            consecutiveSkips++
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }

        // Audio actually coming out means the queue is healthy again: forget the failure history.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            failedMediaId = null
            retriedCurrent = false
            consecutiveSkips = 0
        }

        private companion object {
            const val TAG = "DeezerPlayback"
            const val MAX_SKIPS = 5
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // Stop the service if the user swipes the app away while nothing is playing.
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player
        if (player == null || (!player.playWhenReady) || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
