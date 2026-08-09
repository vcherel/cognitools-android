package com.example.myapp.podcasts

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.myapp.MainActivity
import com.example.myapp.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Background playback for the Podcasts tool. A plain MediaSessionService/ExoPlayer streaming
 * episode audio over https, with no crypto unlike DeezerPlaybackService. It streams through
 * [PodcastStreamCache], so what has already been fetched (including the stretch the sleep timer
 * secures ahead of time) plays with no connection. Media3 provides the media notification and
 * lockscreen controls for free.
 */
class PodcastPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(PodcastStreamCache.playerDataSourceFactory(this)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            // On a podcast, jumping back over a passage matters more than skipping to another
            // episode, so the two slots around play/pause hold the 30 s jumps and the episode
            // buttons fall back into the overflow.
            .setMediaButtonPreferences(seekButtons())
            .setSessionActivity(openPodcastsPendingIntent())
            .build()

        // Its own notification id and channel, distinct from DeezerPlaybackService's: sharing
        // Media3's defaults makes the two services fight over one notification, which ends with
        // Android killing whichever is still foreground without one.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.podcast_notification_channel)
                .build()
        )
    }

    /**
     * The -30 s / +30 s pair the notification and the lockscreen draw. They carry a player command
     * rather than a custom one, so Media3 runs them against the player itself and the service has
     * nothing to handle.
     */
    private fun seekButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName("Reculer de 30 s")
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName("Avancer de 30 s")
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
    )

    // Podcasts no longer has its own top-level route: it lives inside the Musique tool (followed
    // shows under Favoris, episodes nested there), so the notification opens that instead.
    private fun openPodcastsPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_ROUTE, "deezer")
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
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
            if (customCommand.customAction == CMD_STOP_ALL) {
                val player = session.player
                player.stop()
                player.clearMediaItems()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
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
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        // Not private: PodcastRepository sends this from the UI (the "stop everything" button).
        const val CMD_STOP_ALL = "com.example.myapp.podcasts.STOP_ALL"

        /** How far the notification's two jump buttons move, and the player's own seek increment. */
        private const val SEEK_INCREMENT_MS = 30_000L

        // Must differ from DeezerPlaybackService's, see the provider set up in onCreate.
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "podcast_playback"
    }
}
