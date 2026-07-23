package com.example.myapp.deezer

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
        mediaSession = MediaSession.Builder(this, player).build()
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
