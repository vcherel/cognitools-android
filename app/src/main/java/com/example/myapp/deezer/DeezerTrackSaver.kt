package com.example.myapp.deezer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.example.myapp.notes.appendToDjNote
import com.example.myapp.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL

/**
 * Saves a track as a tagged MP3 in the phone's Download folder, for use outside the app (the DJ
 * set). Always asks for MP3 320, whatever the streaming quality, falling back to 128 when Deezer
 * refuses it. The file is fetched fresh from the CDN: the caches hold 128 only.
 *
 * "Already saved" is the file being there under its name, so deleting it from Download is enough
 * to allow a new save.
 */
class DeezerTrackSaver(private val appContext: Context, private val repo: DeezerRepository) {

    // Outlives the full player: a save started there finishes after it is collapsed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _saving = MutableStateFlow<Set<String>>(emptySet())
    /** The sngIds being downloaded right now. */
    val saving: StateFlow<Set<String>> = _saving

    fun isSaved(track: DeezerTrack): Boolean = File(downloadsDir(), fileName(track)).exists()

    fun save(track: DeezerTrack) {
        if (track.sngId in _saving.value) return
        _saving.update { it + track.sngId }
        scope.launch {
            val message = try {
                val audio = fetchAudio(track.sngId)
                val cover = track.coverUrl(1000)?.let { runCatching { URL(it).readBytes() }.getOrNull() }
                val tag = id3Tag(track.title, track.artist, track.album, cover)
                writeToDownloads(fileName(track), tag, audio)
                runCatching { appendToDjNote(appContext, track.artist, track.title, downloaded = true) }
                "Téléchargé dans Download"
            } catch (e: Exception) {
                repo.logError("save ${track.sngId}", e)
                userMessage(e)
            } finally {
                _saving.update { it - track.sngId }
            }
            withContext(Dispatchers.Main) { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
        }
    }

    /** The whole decrypted stream, without any ID3 tag it may already open with. */
    private fun fetchAudio(sngId: String): ByteArray {
        val source = DeezerDataSource.Factory(repo).createDataSource()
        val out = ByteArrayOutputStream()
        try {
            source.open(DataSpec(Uri.parse("dzr://$sngId?q=${DeezerQuality.MP3_320.name}")))
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
            }
        } finally {
            source.close()
        }
        val bytes = out.toByteArray()
        return bytes.copyOfRange(existingId3Length(bytes), bytes.size)
    }

    private fun writeToDownloads(name: String, tag: ByteArray, audio: ByteArray) {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Fichier inaccessible")
        try {
            resolver.openOutputStream(uri)?.use {
                it.write(tag)
                it.write(audio)
            } ?: error("Fichier inaccessible")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun downloadsDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
}

/** "Artiste - Titre.mp3", with the characters a file name can't hold replaced. */
internal fun fileName(track: DeezerTrack): String {
    val stem = if (track.artist.isBlank()) track.title else "${track.artist} - ${track.title}"
    return stem.replace(Regex("""[/\\:*?"<>|]"""), "_").trim() + ".mp3"
}

/** The size of the ID3v2 tag [bytes] opens with, footer included, or 0 when there is none. */
internal fun existingId3Length(bytes: ByteArray): Int {
    if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return 0
    val size = (6..9).fold(0) { acc, i -> (acc shl 7) or (bytes[i].toInt() and 0x7F) }
    val footer = if (bytes[5].toInt() and 0x10 != 0) 10 else 0
    return (10 + size + footer).coerceAtMost(bytes.size)
}

/** An ID3v2.3 tag: title, artist, album as UTF-16 text frames, plus the cover as a JPEG front cover. */
internal fun id3Tag(title: String, artist: String, album: String, cover: ByteArray?): ByteArray {
    val frames = ByteArrayOutputStream()
    fun frame(id: String, body: ByteArray) {
        frames.write(id.toByteArray(Charsets.ISO_8859_1))
        frames.write(bigEndian(body.size))
        frames.write(byteArrayOf(0, 0))
        frames.write(body)
    }
    fun text(id: String, value: String) {
        if (value.isNotBlank()) frame(id, byteArrayOf(1) + value.toByteArray(Charsets.UTF_16))
    }
    text("TIT2", title)
    text("TPE1", artist)
    text("TALB", album)
    if (cover != null) {
        frame("APIC", byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + cover)
    }
    val body = frames.toByteArray()
    val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) + synchsafe(body.size)
    return header + body
}

private fun bigEndian(value: Int) = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
)

private fun synchsafe(value: Int) = byteArrayOf(
    ((value ushr 21) and 0x7F).toByte(), ((value ushr 14) and 0x7F).toByte(),
    ((value ushr 7) and 0x7F).toByte(), (value and 0x7F).toByte()
)
