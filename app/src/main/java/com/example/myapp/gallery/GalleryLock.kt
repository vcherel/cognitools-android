package com.example.myapp.gallery

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.galleryLockDataStore by preferencesDataStore("gallery_lock")
private val LOCKED_BUCKETS_KEY = stringSetPreferencesKey("locked_bucket_ids")

/**
 * The albums put behind a code, kept as a set of MediaStore bucket ids. The code itself is the notes
 * PIN (see NoteLock): one code for the whole app, so the adb reset sentinel already covers this too.
 *
 * A gate, not encryption. The files stay where they are and every other app still sees them; locking
 * only stops this app from showing the album's content without the code, in the album grid as well as
 * in the pinned set and the hero card.
 */
object GalleryLock {

    fun lockedBucketIds(context: Context): Flow<Set<Long>> =
        context.galleryLockDataStore.data.map { prefs ->
            prefs[LOCKED_BUCKETS_KEY].orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }
        }

    suspend fun lockedBucketIdsNow(context: Context): Set<Long> = lockedBucketIds(context).first()

    suspend fun setLocked(context: Context, bucketId: Long, locked: Boolean) {
        context.galleryLockDataStore.edit { prefs ->
            val current = prefs[LOCKED_BUCKETS_KEY].orEmpty()
            prefs[LOCKED_BUCKETS_KEY] =
                if (locked) current + bucketId.toString() else current - bucketId.toString()
        }
    }
}
