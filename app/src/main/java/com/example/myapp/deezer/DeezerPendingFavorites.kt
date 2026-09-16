package com.example.myapp.deezer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.example.myapp.writeAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The likes and unlikes made with no connection. Each one is applied to the local favorites right
 * away by the repository and its intent (add or remove) recorded here, keyed by sngId, so the app
 * can resend it later without the user having to redo anything. Persisted to disk so it survives
 * the app being killed while still offline.
 *
 * [send] is the Deezer call for one intent; it throws [DeezerApiException] when Deezer refuses it.
 */
class DeezerPendingFavorites(
    private val appContext: Context,
    private val hasNetwork: () -> Boolean,
    private val send: suspend (sngId: String, add: Boolean) -> Unit
) {
    private val file: File by lazy { File(appContext.filesDir, "deezer_pending_favorites.json") }
    private val mutex = Mutex()
    private var cache: LinkedHashMap<String, Boolean>? = null

    private suspend fun map(): LinkedHashMap<String, Boolean> {
        cache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val map = LinkedHashMap<String, Boolean>()
                if (file.exists()) {
                    val root = DeezerLibraryCache.json.parseToJsonElement(file.readText()).jsonObject
                    root.forEach { (sngId, add) -> map[sngId] = add.jsonPrimitive.content == "add" }
                }
                map
            }.getOrDefault(LinkedHashMap())
        }
        cache = loaded
        return loaded
    }

    private suspend fun write(map: Map<String, Boolean>) = withContext(Dispatchers.IO) {
        runCatching {
            if (map.isEmpty()) file.delete()
            else file.writeAtomically(
                buildJsonObject { map.forEach { (sngId, add) -> put(sngId, if (add) "add" else "remove") } }.toString()
            )
        }
    }

    /** Queues a whole run of like/unlike intents in one disk write. */
    suspend fun queue(sngIds: List<String>, add: Boolean): Unit = mutex.withLock {
        val map = map()
        sngIds.forEach { map[it] = add }
        write(map)
    }

    suspend fun clear(sngId: String): Unit = mutex.withLock {
        val map = map()
        if (map.remove(sngId) != null) write(map)
    }

    /**
     * Resends every queued like/unlike. Called opportunistically whenever a Deezer screen refreshes
     * the library or the favorites cache, so a change made offline reaches Deezer the next time the
     * tool is opened with a connection. Stops at the first network failure so the rest stays queued.
     */
    suspend fun flush(): Unit = mutex.withLock {
        if (!hasNetwork()) return@withLock
        val map = map()
        if (map.isEmpty()) return@withLock
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val (sngId, add) = iter.next()
            try {
                send(sngId, add)
                iter.remove()
            } catch (e: DeezerApiException) {
                // A dead session is not the track's fault: everything still queued waits for a
                // working one instead of being thrown away one by one.
                if (e.tokenError) break
                Log.w(TAG, "Dropping pending favorite $sngId after API error", e)
                iter.remove()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pending favorites left queued after a network failure", e)
                break
            }
        }
        write(map)
    }

    /**
     * Flushes once now and again as soon as the phone has real internet. Without this a like made
     * offline waits for the next visit to a Deezer screen, which can be days.
     */
    fun flushOnNetwork(scope: CoroutineScope) {
        scope.launch { runCatching { flush() } }
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // Capabilities change constantly (bandwidth estimates), so only the transition into
                // validated internet counts, not every notification.
                private var validated = false

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (now && !validated) scope.launch { runCatching { flush() } }
                    validated = now
                }

                override fun onLost(network: Network) {
                    validated = false
                }
            })
        }.onFailure { Log.w(TAG, "Could not watch the network for pending favorites", it) }
    }

    private companion object {
        const val TAG = "DeezerPendingFavorites"
    }
}
