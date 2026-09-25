package com.example.myapp.deezer

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.myapp.AppSnackbar
import com.example.myapp.copyToClipboard
import com.example.myapp.MainActivity
import com.example.myapp.MyApplication
import com.example.myapp.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background playback for the Deezer tool. A MediaSessionService owns the single ExoPlayer and its
 * MediaSession; Media3 provides the media notification and lockscreen controls for free. The UI
 * drives it through a MediaController (see DeezerScreen).
 *
 * Plays `dzr://` URIs, resolved to a fresh CDN URL and decrypted on the fly by DeezerDataSource,
 * with a disk cache in front of it.
 */
/** True when [TrackUnavailableException] is anywhere in the cause chain (Media3 wraps load errors). */
private fun Throwable?.isTrackUnavailable(): Boolean =
    generateSequence(this) { it.cause }.take(10).any { it is TrackUnavailableException }

class DeezerPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: DeezerRepository

    override fun onCreate() {
        super.onCreate()
        repo = (application as MyApplication).deezerRepository

        // Read order: offline mirror -> LRU stream cache -> DeezerDataSource (resolve CDN + decrypt).
        // A track downloaded by DeezerOfflineLibrary is therefore served from disk with no network at
        // all. The offline layer is read-only here: only the sync writes into it, so ordinary playback
        // can't fill the uncapped store with tracks nobody asked to keep. The stream cache's write sink
        // is gated the same way, on being liked rather than being in Best pépites (see LikedOnlyCacheDataSink).
        val streamFactory = CacheDataSource.Factory()
            .setCache(repo.streamCache)
            .setCacheWriteDataSinkFactory(LikedOnlyCacheDataSinkFactory(repo.streamCache, repo))
            .setUpstreamDataSourceFactory(DeezerDataSource.Factory(repo))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(repo.offline.cache)
            .setUpstreamDataSourceFactory(streamFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // A track Deezer has retired fails identically every time, so the default policy's three
        // backed-off retries are three pointless CDN resolves (and a good ten seconds of silence)
        // before the replacement search even starts. Genuine network errors keep the full budget.
        val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (loadErrorInfo.exception.isTrackUnavailable()) C.TIME_UNSET
                else super.getRetryDelayMsFor(loadErrorInfo)
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(cacheFactory).setLoadErrorHandlingPolicy(loadErrorPolicy)
            )
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
        player.addListener(TrackWatcher(player))
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setMediaButtonPreferences(actionButtons(liked = false, inPepites = false))
            .setSessionActivity(openDeezerPendingIntent())
            .build()

        // Media3's default provider gives every MediaSessionService the same notification id and
        // channel. With two of them in one app (this one and PodcastPlaybackService), whichever
        // started second steals the other's notification, and the first one tearing its own down
        // then pulls the notification out from under a service that is still foreground, which
        // Android kills the process for. Each service therefore gets its own id and channel.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.music_notification_channel)
                .build()
        )

        // The heart also flips when the track is liked from the app itself.
        scope.launch { repo.favoriteIds.collect { refreshActionButtons() } }
    }

    // Two extra buttons sit on either side of prev/play/next in the media notification and on the
    // lockscreen, so a track can be liked or pushed into Best pépites without unlocking the phone.

    /**
     * The heart takes the slot before the transport controls, the pépites button the one after, which
     * leaves previous/play/next untouched in the collapsed notification.
     */
    private fun actionButtons(liked: Boolean, inPepites: Boolean, removesFromPepites: Boolean = false): List<CommandButton> = listOf(
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setSessionCommand(SessionCommand(CMD_TOGGLE_LIKE, Bundle.EMPTY))
            .setDisplayName(if (liked) "Retirer des favoris" else "Ajouter aux favoris")
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        // Media3 has no diamond among its icon constants, so the notification draws our own vector
        // (the same Material diamond as the now playing screen); the constant stays as the fallback
        // for surfaces that only understand the predefined set, like Android Auto.
        CommandButton.Builder(if (inPepites) CommandButton.ICON_CHECK_CIRCLE_FILLED else CommandButton.ICON_PLAYLIST_ADD)
            .setCustomIconResId(if (inPepites) R.drawable.ic_diamond_filled else R.drawable.ic_diamond)
            .setSessionCommand(SessionCommand(CMD_TOGGLE_PEPITES, Bundle.EMPTY))
            .setDisplayName(if (removesFromPepites) "Retirer de Best pépites" else "Ajouter à Best pépites")
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    )

    /** Redraws the two buttons for whatever is playing now. Main thread only. */
    private fun refreshActionButtons() {
        val session = mediaSession ?: return
        val sngId = session.player.currentMediaItem?.mediaId
        val inPepites = sngId != null && repo.bestPepitesContains(sngId) == true
        session.setMediaButtonPreferences(
            actionButtons(
                liked = sngId != null && repo.isFavorite(sngId),
                inPepites = inPepites,
                removesFromPepites = inPepites && repo.isBestPepites(repo.player.playerState.value.source)
            )
        )
    }

    /** The track behind the current MediaItem, falling back to its metadata if the queue map lost it. */
    private fun currentTrack(): DeezerTrack? {
        val item = mediaSession?.player?.currentMediaItem ?: return null
        repo.player.trackById(item.mediaId)?.let { return it }
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

    /** Tapping the notification body opens the app straight into the Deezer tool, not the main menu. */
    private fun openDeezerPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_DEEZER_NOW_PLAYING)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Keeps the two buttons in sync with the current track, warming the caches they read. Also
     * resumes playback on a skip: a next/previous while paused is a request to hear that track,
     * whichever button sent it (app, notification, headphones).
     */
    private inner class TrackWatcher(private val player: ExoPlayer) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !player.playWhenReady) player.play()
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
                        .add(SessionCommand(CMD_TOGGLE_PEPITES, Bundle.EMPTY))
                        .add(SessionCommand(CMD_STOP_ALL, Bundle.EMPTY))
                        .build()
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            // Doesn't need a current track: it acts on the player/service itself, and must still work
            // even if the queue metadata is gone.
            if (customCommand.customAction == CMD_STOP_ALL) {
                val player = session.player
                player.stop()
                player.clearMediaItems()
                // Force the notification gone right away instead of waiting on Media3's own
                // "user engaged" grace period, which otherwise leaves it (and the service) around
                // for several seconds after a pause.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            val track = currentTrack()
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            when (customCommand.customAction) {
                CMD_TOGGLE_LIKE -> scope.launch {
                    runCatching { repo.toggleFavorite(track) }
                        .onFailure { toast("Échec, réessaie") }
                    refreshActionButtons()
                }
                CMD_TOGGLE_PEPITES -> scope.launch {
                    toast(bestPepitesMessage(applicationContext, repo, track, repo.player.playerState.value.source))
                    refreshActionButtons()
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * Keeps the queue moving when an item fails to load. A dzr:// item can fail transiently (CDN
     * resolve hiccup, network drop between two tracks) or permanently (an artist who re-released the
     * same song can leave the specific sngId a favorite/playlist pinned unstreamable): retry the same
     * one once, then either swap in a working release of the same song (see [findAndApplyReplacement])
     * or skip to the next queued item. Without this the player just parks in IDLE at the end of a
     * track and needs a manual next + play.
     */
    private inner class ErrorRecovery(private val player: ExoPlayer) : Player.Listener {

        private var failedMediaId: String? = null
        private var retriedCurrent = false
        private var consecutiveSkips = 0
        private var lastErrorLine = ""
        private var awaitingQueue: Job? = null
        private var awaitingTitle: String? = null

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (awaitingQueue == null) return
            // A new list started meanwhile clears the error: that queue is not ours to skip.
            if (player.playerError == null) {
                awaitingQueue?.cancel()
                awaitingQueue = null
                return
            }
            if (!player.hasNextMediaItem()) return
            awaitingQueue?.cancel()
            awaitingQueue = null
            skipToNext(awaitingTitle)
        }

        override fun onPlayerError(error: PlaybackException) {
            val mediaItem = player.currentMediaItem
            val mediaId = mediaItem?.mediaId
            if (mediaId != failedMediaId) {
                failedMediaId = mediaId
                retriedCurrent = false
            }
            Log.w(TAG, "Playback error on $mediaId (${error.errorCodeName})", error)
            lastErrorLine = repo.logError("playback $mediaId (${error.errorCodeName})", error)

            // Nothing to retry when Deezer itself says the track is gone: go looking for another
            // release of the same song right away.
            if (error.isTrackUnavailable()) retriedCurrent = true

            if (!retriedCurrent) {
                retriedCurrent = true
                player.prepare() // resumes the same item at the position it died at
                player.play()
                return
            }

            val track = currentTrack()
            if (consecutiveSkips >= MAX_SKIPS) {
                giveUp(track?.title)
                return
            }
            consecutiveSkips++

            val source = mediaItem?.let { repo.player.sourceOf(it) }
            if (track != null && source != null) {
                findAndApplyReplacement(track, source)
            } else {
                skipToNext(track?.title)
            }
        }

        private fun giveUp(title: String?) {
            Log.w(TAG, "Giving up after $consecutiveSkips skipped tracks")
            showError(
                if (title.isNullOrBlank()) "Lecture interrompue, impossible de reprendre"
                else "Lecture de « $title » interrompue, impossible de reprendre"
            )
        }

        /** The failure snackbar, with the logged cause one tap away on the clipboard. */
        private fun showError(message: String) {
            val line = lastErrorLine
            AppSnackbar.show(message, actionLabel = "Copier") {
                copyToClipboard(this@DeezerPlaybackService, line, "Erreur Deezer")
            }
        }

        private fun skipToNext(title: String?) {
            if (!player.hasNextMediaItem()) {
                // playTracks sets the playing track alone and adds the rest a moment later, so a
                // track refused on its first resolve can fail before its queue exists.
                awaitingQueue?.cancel()
                awaitingQueue = scope.launch {
                    delay(QUEUE_FILL_WAIT_MS)
                    awaitingQueue = null
                    giveUp(title)
                }
                awaitingTitle = title
                return
            }
            showError(
                if (title.isNullOrBlank()) "Échec du chargement, passage au morceau suivant"
                else "Échec du chargement de « $title », passage au morceau suivant"
            )
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }

        /**
         * A favorited/playlisted track's pinned sngId can be a release Deezer refuses to serve while a
         * different release of the same song streams fine. Searches for one and, if it actually
         * resolves, swaps it into the queue right now so playback isn't interrupted, and makes the
         * swap permanent at its source straight away: [DeezerRepository.findReplacement] only accepts
         * a candidate whose normalized artist + title are identical, so there is nothing to confirm.
         */
        private fun findAndApplyReplacement(track: DeezerTrack, source: TrackSource) {
            scope.launch {
                val replacement = runCatching { repo.findReplacement(track) }.getOrNull()
                if (replacement == null || player.currentMediaItem?.mediaId != track.sngId) {
                    skipToNext(track.title)
                    return@launch
                }
                val index = player.currentMediaItemIndex
                player.replaceMediaItem(index, repo.player.buildMediaItem(replacement, source = source))
                player.prepare()
                player.play()
                val applied = runCatching { repo.applyReplacement(track, replacement, source) }.isSuccess
                // If the write failed (offline, stale token), the swapped-in release still isn't in
                // the real favorites list: without this the heart (and the notification's like button)
                // would read as "not liked" for a song the user does like, just under another sngId.
                if (!applied && source is TrackSource.Favorites) repo.markTemporaryFavorite(replacement.sngId)
                AppSnackbar.show("« ${track.title} » indisponible, remplacé par une autre version")
                refreshActionButtons()
            }
        }

        // Audio actually coming out means the queue is healthy again: forget the failure history.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            awaitingQueue?.cancel()
            awaitingQueue = null
            failedMediaId = null
            retriedCurrent = false
            consecutiveSkips = 0
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // Stop the service if the user swipes the app away while nothing is playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
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

    companion object {
        private const val TAG = "DeezerPlayback"
        private const val MAX_SKIPS = 5
        private const val QUEUE_FILL_WAIT_MS = 3_000L
        private const val CMD_TOGGLE_LIKE = "com.example.myapp.deezer.TOGGLE_LIKE"
        private const val CMD_TOGGLE_PEPITES = "com.example.myapp.deezer.TOGGLE_BEST_PEPITES"

        // Must differ from PodcastPlaybackService's, see the provider set up in onCreate.
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "music_playback"

        // Not private: DeezerRepository sends this from the UI (the "stop everything" button), so it
        // needs the same action string the session was told to accept above.
        const val CMD_STOP_ALL = "com.example.myapp.deezer.STOP_ALL"
    }
}

/**
 * Wraps a real [CacheDataSink] but only actually writes when the track being fetched is currently
 * liked, so a track played once out of curiosity never eats into the stream cache's 5 GB cap. Reads
 * are unaffected: CacheDataSource still serves a cache hit regardless of who wrote it.
 */
private class LikedOnlyCacheDataSink(private val delegate: DataSink, private val repo: DeezerRepository) : DataSink {
    private var writing = false

    override fun open(dataSpec: DataSpec) {
        val sngId = sngIdFromCacheKey(dataSpec.key ?: dataSpec.uri.toString())
        writing = sngId != null && repo.isFavorite(sngId)
        if (writing) delegate.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (writing) delegate.write(buffer, offset, length)
    }

    override fun close() {
        if (writing) delegate.close()
    }
}

private class LikedOnlyCacheDataSinkFactory(cache: Cache, private val repo: DeezerRepository) : DataSink.Factory {
    private val delegateFactory = CacheDataSink.Factory().setCache(cache)
    override fun createDataSink(): DataSink = LikedOnlyCacheDataSink(delegateFactory.createDataSink(), repo)
}
