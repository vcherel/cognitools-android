package com.example.myapp.podcasts

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import com.example.myapp.USER_AGENT
import java.io.File
import java.util.TreeSet

/**
 * The one place podcast audio lives, keyed by the episode's audio URL: playing, pre-fetching for the
 * sleep timer and downloading all read and write the same bytes. Whatever is held plays with no
 * connection, and the rest still streams normally, so an episode half downloaded is already half
 * secured for the night, and a download started while streaming only fetches what is missing.
 *
 * A download is nothing more than the whole resource being held plus a *protected* key: protected
 * bytes are never evicted and don't count against the cache's limit, so downloads stay put for as
 * long as they are kept while everything else is capped and evicted oldest first.
 */
object PodcastStreamCache {

    /** Applies to the unprotected bytes only: downloads are kept until they are deleted by hand. */
    private const val LIMIT_BYTES = 256L * 1024 * 1024

    // filesDir, not cacheDir: Android empties cacheDir whenever storage runs low, which would be
    // exactly the moment an episode secured for the night quietly stops being playable offline.
    private var cache: SimpleCache? = null
    private val evictor = ProtectedLruEvictor(LIMIT_BYTES)

    @Synchronized
    fun cache(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.applicationContext.filesDir, "podcast_stream_cache"),
        evictor,
        StandaloneDatabaseProvider(context.applicationContext)
    ).also { cache = it }

    /** Cache-backed source for an episode's http(s) audio: what playback, the pre-fetch and the
     *  downloads all go through, so none of them fetches bytes another one already holds. */
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

    /** What the player reads through. Same cache as everything else, so a running download feeds it. */
    fun playerDataSourceFactory(context: Context): DataSource.Factory = cacheDataSourceFactory(context)

    /** Size of [url]'s audio if it was learned while streaming, else [androidx.media3.common.C.LENGTH_UNSET]. */
    fun knownContentLength(context: Context, url: String): Long =
        ContentMetadata.getContentLength(cache(context).getContentMetadata(url))

    /**
     * True when all [length] bytes from [position] are held for [url], with no hole. What the sleep
     * timer checks before promising the night is covered: writing a range and holding it are not the
     * same thing, since the evictor is free to drop spans in between.
     */
    fun isFullyCached(context: Context, url: String, position: Long, length: Long): Boolean =
        length > 0 && cache(context).isCached(url, position, length)

    /** True when the whole resource is held: what "downloaded" means now that there is no file. */
    fun holdsWholeResource(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val total = knownContentLength(context, url)
        return isFullyCached(context, url, 0L, total)
    }

    /** How much of [url]'s audio is on the phone, 0..1, or null while its size is unknown. */
    fun cachedFraction(context: Context, url: String): Float? {
        val total = knownContentLength(context, url).takeIf { it > 0 } ?: return null
        return (cache(context).getCachedBytes(url, 0L, total).toFloat() / total).coerceIn(0f, 1f)
    }

    /** Keeps [url]'s bytes from ever being evicted, or gives them back to the LRU pool. */
    fun setProtected(context: Context, url: String, protectedNow: Boolean) {
        if (url.isBlank()) return
        evictor.setProtected(cache(context), url, protectedNow)
    }

    /**
     * Throws away everything held for [url]. Called when an episode is finished or its download is
     * deleted: its bytes will never be read again, and left alone a protected one would sit there
     * forever while an unprotected one keeps fresher episodes out of the cache.
     */
    fun remove(context: Context, url: String) {
        if (url.isBlank()) return
        setProtected(context, url, false)
        runCatching { cache(context).removeResource(url) }
    }

    /**
     * Files an already downloaded file into the cache under [url], for the downloads made back when
     * they were plain files under `podcast_downloads/`. Returns true once its bytes are held.
     */
    fun importFile(context: Context, url: String, file: File): Boolean {
        val length = file.length()
        if (url.isBlank() || length <= 0) return false
        val store = cache(context)
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setKey(url)
            .setPosition(0)
            .setLength(length)
            .build()
        val ok = runCatching {
            val sink = CacheDataSink(store, CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            sink.open(spec)
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
            sink.close()
            // Without the content length the cache can't tell a whole resource from a big chunk of one.
            store.applyContentMetadataMutations(
                url,
                ContentMetadataMutations().apply { ContentMetadataMutations.setContentLength(this, length) }
            )
        }.isSuccess
        return ok && holdsWholeResource(context, url)
    }
}

/**
 * Least recently used eviction, except for the keys held as downloads: those are never evicted and
 * their bytes don't count towards [maxBytes], which keeps the streaming cache capped without the
 * downloads either being thrown away or squeezing streaming out of the budget.
 *
 * Locking: media3 calls every [CacheEvictor] method while holding the cache's own lock, so this must
 * never take the cache's lock while holding its own. [setProtected] reads the cache's spans *before*
 * locking, and never evicts: making room is left to the next span added.
 */
private class ProtectedLruEvictor(private val maxBytes: Long) : CacheEvictor {

    private val leastRecentlyUsed = TreeSet<CacheSpan>(::compareSpans)
    private val protectedKeys = mutableSetOf<String>()
    private var currentSize = 0L

    fun setProtected(cache: Cache, key: String, protectedNow: Boolean) {
        val spans = cache.getCachedSpans(key)
        synchronized(this) {
            if (protectedNow) {
                if (!protectedKeys.add(key)) return
                spans.forEach { if (leastRecentlyUsed.remove(it)) currentSize -= it.length }
            } else {
                if (!protectedKeys.remove(key)) return
                spans.forEach { if (leastRecentlyUsed.add(it)) currentSize += it.length }
            }
        }
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong() && !isProtected(key)) {
            evictCache(cache, length)
        }
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (isProtected(span.key)) return
        synchronized(this) {
            leastRecentlyUsed.add(span)
            currentSize += span.length
        }
        evictCache(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(this) {
            if (leastRecentlyUsed.remove(span)) currentSize -= span.length
        }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun isProtected(key: String): Boolean = synchronized(this) { key in protectedKeys }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (true) {
            val span = synchronized(this) {
                if (currentSize + requiredSpace <= maxBytes) null else leastRecentlyUsed.firstOrNull()
            } ?: return
            try {
                cache.removeSpan(span)
            } catch (e: Cache.CacheException) {
                return
            }
        }
    }

    private fun compareSpans(lhs: CacheSpan, rhs: CacheSpan): Int {
        val delta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
        return if (delta == 0L) lhs.compareTo(rhs) else if (delta < 0) -1 else 1
    }
}
