package com.example.myapp.gallery

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Entity(tableName = "pinned_media_items")
data class PinnedMediaItem(
    @PrimaryKey val mediaItemId: Long,
    val pinnedAt: Long,
    // Manually promoted to the big hero spot; at most one row has this set at a time. When unset,
    // the hero is whichever pinned item was pinned first.
    val isHero: Boolean = false
)

@Dao
interface PinnedMediaItemDao {
    @Query("SELECT * FROM pinned_media_items ORDER BY pinnedAt ASC")
    fun observePinned(): Flow<List<PinnedMediaItem>>

    @Upsert
    suspend fun upsertAll(items: List<PinnedMediaItem>)

    @Query("DELETE FROM pinned_media_items WHERE mediaItemId = :id")
    suspend fun unpin(id: Long)

    @Query("UPDATE pinned_media_items SET isHero = 0")
    suspend fun clearHero()

    @Query("UPDATE pinned_media_items SET isHero = 1 WHERE mediaItemId = :id")
    suspend fun markHero(id: Long)
}

/** Pins whichever of the given ids aren't already pinned; existing pins are left untouched. */
suspend fun pinItems(context: Context, ids: List<Long>) {
    if (ids.isEmpty()) return
    val dao = AppDatabase.get(context).pinnedMediaItemDao()
    val now = System.currentTimeMillis()
    dao.upsertAll(ids.map { PinnedMediaItem(mediaItemId = it, pinnedAt = now) })
}

suspend fun unpinItem(context: Context, id: Long) {
    AppDatabase.get(context).pinnedMediaItemDao().unpin(id)
}

/** Pins the item if needed, then promotes it to the hero spot. */
suspend fun setHero(context: Context, id: Long) {
    val dao = AppDatabase.get(context).pinnedMediaItemDao()
    dao.upsertAll(listOf(PinnedMediaItem(mediaItemId = id, pinnedAt = System.currentTimeMillis())))
    dao.clearHero()
    dao.markHero(id)
}

/** Pinned item ids, manual hero first (if any), then the rest in pin order. */
fun orderedPinnedIds(rows: List<PinnedMediaItem>): List<Long> {
    val hero = rows.firstOrNull { it.isHero } ?: rows.minByOrNull { it.pinnedAt }
    val rest = rows.filter { it.mediaItemId != hero?.mediaItemId }.sortedBy { it.pinnedAt }
    return listOfNotNull(hero?.mediaItemId) + rest.map { it.mediaItemId }
}

/**
 * Resolves pinned rows against MediaStore, hero first. Pins pointing at a since-deleted item are
 * dropped from the result and pruned from the database. An item living in a locked album is left out
 * too, without dropping its pin: a picture put behind a code must not come back through the hero card.
 */
suspend fun resolvedPinnedMediaItems(context: Context, rows: List<PinnedMediaItem>): List<MediaItem> {
    if (rows.isEmpty()) return emptyList()
    val orderedIds = orderedPinnedIds(rows)
    val resolved = withContext(Dispatchers.IO) { queryMediaItemsByIds(context, orderedIds) }
    val resolvedIds = resolved.map { it.id }.toSet()
    val stale = orderedIds.filterNot { it in resolvedIds }
    if (stale.isNotEmpty()) {
        val dao = AppDatabase.get(context).pinnedMediaItemDao()
        stale.forEach { dao.unpin(it) }
    }
    val locked = GalleryLock.lockedBucketIdsNow(context)
    return resolved.filterNot { it.bucketId in locked }.sortedBy { orderedIds.indexOf(it.id) }
}
