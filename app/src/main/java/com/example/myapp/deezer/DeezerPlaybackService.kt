package com.example.myapp.deezer

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.myapp.MyApplication
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: DeezerRepository

    override fun onCreate() {
        super.onCreate()
        repo = (application as MyApplication).deezerRepository

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
        player.addListener(TrackWatcher())
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setMediaButtonPreferences(actionButtons(liked = false, inPepites = false))
            .build()

        // The heart also flips when the track is liked from the app itself.
        scope.launch { repo.favoriteIds.collect { refreshActionButtons() } }
    }

    // ---- Notification actions (like / add to Best pépites) ----
    // Two extra buttons sit on either side of prev/play/next in the media notification and on the
    // lockscreen, so a track can be liked or pushed into Best pépites without unlocking the phone.

    /**
     * The heart takes the slot before the transport controls, the pépites button the one after, which
     * leaves previous/play/next untouched in the collapsed notification.
     */
    private fun actionButtons(liked: Boolean, inPepites: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setSessionCommand(SessionCommand(CMD_TOGGLE_LIKE, Bundle.EMPTY))
            .setDisplayName(if (liked) "Retirer des favoris" else "Ajouter aux favoris")
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(if (inPepites) CommandButton.ICON_CHECK_CIRCLE_FILLED else CommandButton.ICON_PLAYLIST_ADD)
            .setSessionCommand(SessionCommand(CMD_ADD_PEPITES, Bundle.EMPTY))
            .setDisplayName(if (inPepites) "Déjà dans Best pépites" else "Ajouter à Best pépites")
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    )

    /** Redraws the two buttons for whatever is playing now. Main thread only. */
    private fun refreshActionButtons() {
        val session = mediaSession ?: return
        val sngId = session.player.currentMediaItem?.mediaId
        session.setMediaButtonPreferences(
            actionButtons(
                liked = sngId != null && repo.isFavorite(sngId),
                inPepites = sngId != null && repo.bestPepitesContains(sngId) == true
            )
        )
    }

    /** The track behind the current MediaItem, falling back to its metadata if the queue map lost it. */
    private fun currentTrack(): DeezerTrack? {
        val item = mediaSession?.player?.currentMediaItem ?: return null
        repo.trackById(item.mediaId)?.let { return it }
        val m = item.mediaMetadata
        return DeezerTrack(
            sngId = item.mediaId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            album = m.albumTitle?.toString().orEmpty(),
            durationSec = 0,
            coverMd5 = null
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Keeps the two buttons in sync with the current track, warming the caches they read. */
    private inner class TrackWatcher : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshActionButtons()
            scope.launch {
                // Both are no-ops once loaded; without them the first notification tap would act on
                // an unknown like state and could re-add a track already in Best pépites.
                runCatching { repo.ensureFavorites() }
                runCatching { repo.ensureBestPepitesLoaded() }
                refreshActionButtons()
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_TOGGLE_LIKE, Bundle.EMPTY))
                        .add(SessionCommand(CMD_ADD_PEPITES, Bundle.EMPTY))
                        .build()
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val track = currentTrack()
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            when (customCommand.customAction) {
                CMD_TOGGLE_LIKE -> scope.launch {
                    runCatching { repo.toggleFavorite(track) }
                        .onFailure { toast("Échec, réessaie") }
                    refreshActionButtons()
                }
                CMD_ADD_PEPITES -> scope.launch {
                    runCatching { repo.addToBestPepites(track) }
                        .onSuccess {
                            toast(
                                when (it) {
                                    PlaylistAddResult.ADDED -> "Ajouté à Best pépites"
                                    PlaylistAddResult.DUPLICATE -> "Déjà dans Best pépites"
                                    PlaylistAddResult.NO_PLAYLIST -> "Playlist Best pépites introuvable"
                                }
                            )
                        }
                        .onFailure { toast("Échec de l'ajout") }
                    refreshActionButtons()
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
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
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val CMD_TOGGLE_LIKE = "com.example.myapp.deezer.TOGGLE_LIKE"
        const val CMD_ADD_PEPITES = "com.example.myapp.deezer.ADD_BEST_PEPITES"
    }
}
