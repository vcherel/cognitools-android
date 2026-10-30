package com.example.myapp.deezer

import android.content.Context
import com.example.myapp.writeAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * The sngIds Deezer refused to stream ("no sufficient rights"), with when. A queue leaves them out
 * and the track lists grey them, instead of each play failing again. Rights do come back, so an
 * entry expires after [EXPIRY_MS], and any successful resolve forgets it at once.
 */
class DeezerRefusedTracks(appContext: Context, scope: CoroutineScope) {
    private val file = File(appContext.filesDir, "deezer_refused_tracks.json")
    private val refusedAt = HashMap<String, Long>()
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids

    init {
        scope.launch {
            val now = System.currentTimeMillis()
            val loaded = runCatching {
                DeezerLibraryCache.json.parseToJsonElement(file.readText()).jsonObject
                    .mapValues { it.value.jsonPrimitive.long }
            }.getOrDefault(emptyMap())
            synchronized(refusedAt) {
                loaded.forEach { (sngId, at) -> if (now - at < EXPIRY_MS) refusedAt.putIfAbsent(sngId, at) }
                publish()
            }
        }
    }

    fun contains(sngId: String): Boolean = sngId in _ids.value

    /** Called on the loading thread, like the resolve that found out. */
    fun mark(sngId: String) = synchronized(refusedAt) {
        refusedAt[sngId] = System.currentTimeMillis()
        publish()
    }

    fun forget(sngId: String) = synchronized(refusedAt) {
        if (refusedAt.remove(sngId) != null) publish()
    }

    private fun publish() {
        _ids.value = refusedAt.keys.toSet()
        runCatching {
            file.writeAtomically(buildJsonObject { refusedAt.forEach { (sngId, at) -> put(sngId, at) } }.toString())
        }
    }

    private companion object {
        const val EXPIRY_MS = 30L * 24 * 60 * 60 * 1000
    }
}
