package com.example.myapp.deezer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** The last fetched library (favorites + playlists), persisted so a fresh launch can render before revalidating. */
data class DeezerLibrarySnapshot(val favorites: List<DeezerTrack>, val playlists: List<DeezerPlaylist>)

/**
 * Reads/writes the library snapshot as a compact JSON file. Hand (de)serialized with short keys, the
 * same JsonElement-navigation style DeezerApi uses for gw responses, so we stay off the
 * kotlinx-serialization gradle plugin.
 */
object DeezerLibraryCache {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(file: File): DeezerLibrarySnapshot? {
        if (!file.exists()) return null
        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val favorites = root["favorites"]?.jsonArray.orEmpty().map { el ->
                val o = el.jsonObject
                DeezerTrack(
                    sngId = o["i"]?.jsonPrimitive?.content.orEmpty(),
                    title = o["t"]?.jsonPrimitive?.content.orEmpty(),
                    artist = o["a"]?.jsonPrimitive?.content.orEmpty(),
                    album = o["b"]?.jsonPrimitive?.content.orEmpty(),
                    durationSec = o["d"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    coverMd5 = o["c"]?.jsonPrimitive?.content?.ifBlank { null }
                )
            }
            val playlists = root["playlists"]?.jsonArray.orEmpty().map { el ->
                val o = el.jsonObject
                DeezerPlaylist(
                    id = o["i"]?.jsonPrimitive?.content.orEmpty(),
                    title = o["t"]?.jsonPrimitive?.content.orEmpty(),
                    trackCount = o["n"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    pictureMd5 = o["p"]?.jsonPrimitive?.content?.ifBlank { null },
                    pictureType = o["y"]?.jsonPrimitive?.content?.ifBlank { null } ?: "playlist"
                )
            }
            DeezerLibrarySnapshot(favorites, playlists)
        }.getOrNull()
    }

    fun write(file: File, snapshot: DeezerLibrarySnapshot) {
        val root = buildJsonObject {
            put("favorites", buildJsonArray {
                snapshot.favorites.forEach { t ->
                    add(buildJsonObject {
                        put("i", t.sngId)
                        put("t", t.title)
                        put("a", t.artist)
                        put("b", t.album)
                        put("d", t.durationSec)
                        t.coverMd5?.let { put("c", it) }
                    })
                }
            })
            put("playlists", buildJsonArray {
                snapshot.playlists.forEach { p ->
                    add(buildJsonObject {
                        put("i", p.id)
                        put("t", p.title)
                        put("n", p.trackCount)
                        p.pictureMd5?.let { put("p", it) }
                        put("y", p.pictureType)
                    })
                }
            })
        }
        runCatching { file.writeText(root.toString()) }
    }
}
