package com.example.myapp.podcasts

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.myapp.USER_AGENT
import java.io.File
import java.io.IOException

/**
 * The cache the podcast player streams through, keyed by the episode's audio URL. Whatever bytes it
 * holds play with no connection, and the rest still streams normally, which is what lets the sleep
 * timer secure just the stretch it needs instead of downloading the whole episode.
 *
 * Nothing here is a download: full downloads are files under `podcast_downloads/` (see
 * PodcastRepository), they alone drive the downloaded icon, and they are read straight from disk.
 * This cache is capped and evicts its oldest bytes on its own.
 */
object PodcastStreamCache {

    private const val LIMIT_BYTES = 256L * 1024 * 1024

    // filesDir, not cacheDir: Android empties cacheDir whenever storage runs low, which would be
    // exactly the moment an episode secured for the night quietly stops being playable offline.
    private var cache: SimpleCache? = null

    @Synchronized
    fun cache(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.applicationContext.filesDir, "podcast_stream_cache"),
        LeastRecentlyUsedCacheEvictor(LIMIT_BYTES),
        StandaloneDatabaseProvider(context.applicationContext)
    ).also { cache = it }

    /** Cache-backed source for an episode's http(s) audio, used by playback and by the sleep pre-fetch. */
    fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    // Podcast enclosures bounce through a tracking prefix (Podtrac, Megaphone…) that
                    // often switches between http and https on the way to the real CDN.
                    .setAllowCrossProtocolRedirects(true)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** What the player reads through: a downloaded file straight from disk, anything else via the cache. */
    fun playerDataSourceFactory(context: Context): DataSource.Factory {
        val fileFactory = FileDataSource.Factory()
        val networkFactory = cacheDataSourceFactory(context)
        return DataSource.Factory { SchemeRoutedDataSource(fileFactory, networkFactory) }
    }

    /** Size of [url]'s audio if the cache learned it while streaming, else [androidx.media3.common.C.LENGTH_UNSET]. */
    fun knownContentLength(context: Context, url: String): Long =
        ContentMetadata.getContentLength(cache(context).getContentMetadata(url))

    /**
     * Throws away everything held for [url]. Called when an episode is finished: its bytes will never
     * be read again, and left alone they would sit there until the LRU evictor happens to need the
     * room, keeping fresher episodes out of the cache in the meantime.
     */
    fun remove(context: Context, url: String) {
        if (url.isBlank()) return
        runCatching { cache(context).removeResource(url) }
    }
}

/**
 * Routes each open() by scheme. A downloaded episode plays from a file:// URI and must be read
 * straight from disk: sending it through the cache would copy the whole episode onto the phone a
 * second time.
 */
private class SchemeRoutedDataSource(
    private val fileFactory: DataSource.Factory,
    private val networkFactory: DataSource.Factory
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val factory = if (dataSpec.uri.scheme.equals("file", ignoreCase = true)) fileFactory else networkFactory
        val source = factory.createDataSource()
        listeners.forEach(source::addTransferListener)
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (current ?: throw IOException("read() before open()")).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}
