package com.example.myapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The MediaController plumbing both playback tools share (DeezerRepository, PodcastRepository):
 * connecting to their MediaSessionService exactly once however many callers ask at the same time,
 * and ending that service for good.
 *
 * [onConnected] runs on the main thread the moment the controller is live, and is where the owning
 * repository adds its player listener and seeds whatever state it derives from the player.
 */
class MediaControllerHolder(
    private val appContext: Context,
    private val serviceClass: Class<out MediaSessionService>,
    private val stopCommand: String,
    private val onConnected: (MediaController) -> Unit
) {
    @Volatile
    var controller: MediaController? = null
        private set

    private val mutex = Mutex()
    private var connection: CompletableDeferred<MediaController>? = null

    suspend fun ensure(): MediaController = mutex.withLock {
        connection?.let { return it.await() }
        val deferred = CompletableDeferred<MediaController>()
        connection = deferred
        withContext(Dispatchers.Main) {
            val token = SessionToken(appContext, ComponentName(appContext, serviceClass))
            val future = MediaController.Builder(appContext, token).buildAsync()
            future.addListener({
                val c = future.get()
                controller = c
                onConnected(c)
                deferred.complete(c)
            }, MoreExecutors.directExecutor())
        }
        deferred.await()
    }

    /**
     * Pauses, removes the notification, and stops the playback service entirely. Also drops our own
     * MediaController: a service stays alive as long as any client is bound to it, so without this
     * the service's own stopSelf() would have no real effect until the app itself goes away.
     */
    fun stop() {
        val c = controller
        if (c != null) {
            c.sendCustomCommand(SessionCommand(stopCommand, Bundle.EMPTY), Bundle.EMPTY)
            c.release()
            controller = null
            connection = null
        } else {
            // The service outlives the process's controller when playback was started in an earlier
            // app session and kept going in the background. Nothing is bound to it then, so stopping
            // the service outright is what actually ends it.
            appContext.stopService(Intent(appContext, serviceClass))
        }
    }
}
