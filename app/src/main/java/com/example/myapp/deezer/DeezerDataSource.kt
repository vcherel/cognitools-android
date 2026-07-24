package com.example.myapp.deezer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.IOException
import kotlin.math.min

/**
 * Resolves a SNG_ID to a fresh CDN URL at open() time. The MediaItem carries only a stable
 * `dzr://<sngId>?q=<quality>` URI (CDN URLs are ephemeral, so they must never be cached or embedded);
 * this looks the real URL up on demand.
 */
interface CdnResolver {
    /** Blocking; called on ExoPlayer's loading thread. Returns the encrypted CDN URL for the track. */
    fun resolve(sngId: String, quality: String): String
}

/**
 * A Media3 DataSource that streams the encrypted Deezer CDN file and decrypts BF_CBC_STRIPE on the
 * fly, 2048 byte chunk by chunk, so playback starts without downloading the whole track. Wrapped by
 * a CacheDataSource (see DeezerPlaybackService) so decrypted bytes are cached for instant replay.
 */
class DeezerDataSource(
    private val resolver: CdnResolver,
    private val httpFactory: DefaultHttpDataSource.Factory
) : BaseDataSource(/* isNetwork = */ true) {

    private var inner: DataSource? = null
    private var uri: Uri? = null
    private var key: ByteArray? = null
    private var chunkIndex = 0
    private var skipRemaining = 0
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    // Decrypted output not yet handed to the caller.
    private var decBuf: ByteArray = ByteArray(0)
    private var decPos = 0
    private val chunkTmp = ByteArray(DeezerCrypto.CHUNK_SIZE)

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val sngId = dataSpec.uri.host ?: dataSpec.uri.lastPathSegment ?: error("No SNG_ID in ${dataSpec.uri}")
        val quality = dataSpec.uri.getQueryParameter("q") ?: DeezerQuality.MP3_128.name
        key = DeezerCrypto.blowfishKey(sngId)

        // Media3 only retries IOExceptions; anything else is an "unexpected" fatal error that stops
        // the whole queue, so a transient API failure must never escape as its own type.
        val cdnUrl = try {
            resolver.resolve(sngId, quality)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("CDN resolve failed for $sngId", e)
        }

        val alignedStart = (dataSpec.position / DeezerCrypto.CHUNK_SIZE) * DeezerCrypto.CHUNK_SIZE
        skipRemaining = (dataSpec.position - alignedStart).toInt()
        chunkIndex = (alignedStart / DeezerCrypto.CHUNK_SIZE).toInt()

        val innerLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else dataSpec.length + skipRemaining
        val innerSpec = DataSpec.Builder()
            .setUri(Uri.parse(cdnUrl))
            .setPosition(alignedStart)
            .setLength(innerLength)
            .build()

        val source = httpFactory.createDataSource().also { inner = it }
        transferInitializing(dataSpec)
        val opened = source.open(innerSpec) // bytes available from alignedStart in the (length-preserving) stream
        bytesRemaining = if (opened == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else opened - skipRemaining
        this.opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        while (decPos >= decBuf.size) {
            if (!fillNextChunk()) return C.RESULT_END_OF_INPUT
        }
        val toCopy = min(length, decBuf.size - decPos).let {
            if (bytesRemaining == C.LENGTH_UNSET.toLong()) it else min(it.toLong(), bytesRemaining).toInt()
        }
        System.arraycopy(decBuf, decPos, target, offset, toCopy)
        decPos += toCopy
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    /** Pulls one full 2048 byte chunk from the CDN, decrypts or passes it through, applies any seek skip. */
    private fun fillNextChunk(): Boolean {
        val source = inner ?: return false
        var got = 0
        while (got < DeezerCrypto.CHUNK_SIZE) {
            val r = source.read(chunkTmp, got, DeezerCrypto.CHUNK_SIZE - got)
            if (r == C.RESULT_END_OF_INPUT) break
            got += r
        }
        // A CDN connection that drops mid track must be reported, not silently reported as EOF:
        // otherwise the track ends early and a truncated span gets written to the cache.
        if (got == 0) {
            if (bytesRemaining > 0) throw IOException("CDN stream ended $bytesRemaining bytes early")
            return false
        }

        decBuf = if (chunkIndex % 3 == 0 && got == DeezerCrypto.CHUNK_SIZE) {
            DeezerCrypto.decryptChunk(key!!, chunkTmp.copyOf(DeezerCrypto.CHUNK_SIZE))
        } else {
            chunkTmp.copyOf(got)
        }
        chunkIndex++
        decPos = 0
        if (skipRemaining > 0) {
            val s = min(skipRemaining, decBuf.size)
            decPos += s
            skipRemaining -= s
        }
        return true
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            inner?.close()
        } finally {
            inner = null
            key = null
            decBuf = ByteArray(0)
            decPos = 0
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val resolver: CdnResolver) : DataSource.Factory {
        private val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

        override fun createDataSource(): DataSource = DeezerDataSource(resolver, httpFactory)
    }
}
