package com.example.myapp.gallery

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.example.myapp.flashcards.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Entity(tableName = "pinned_media_items")
data class PinnedMediaItem(
    @PrimaryKey val mediaItemId: Long,
    // Ascending; the first one is the hero card. New pins go to the end, the viewer's arrows swap two.
    val position: Long
)

@Dao
interface PinnedMediaItemDao {
    @Query("SELECT * FROM pinned_media_items ORDER BY position ASC")
    fun observePinned(): Flow<List<PinnedMediaItem>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM pinned_media_items")
    suspend fun lastPosition(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<PinnedMediaItem>)

    @Query("SELECT * FROM pinned_media_items WHERE mediaItemId IN (:ids)")
    suspend fun pinsByIds(ids: List<Long>): List<PinnedMediaItem>

    @Query("UPDATE pinned_media_items SET position = :position WHERE mediaItemId = :id")
    suspend fun setPosition(id: Long, position: Long)

    @Query("DELETE FROM pinned_media_items WHERE mediaItemId = :id")
    suspend fun unpin(id: Long)
}

/** Pins whichever of the given ids aren't already pinned, after the others; existing pins are left untouched. */
suspend fun pinItems(context: Context, ids: List<Long>) {
    if (ids.isEmpty()) return
    val dao = AppDatabase.get(context).pinnedMediaItemDao()
    val last = dao.lastPosition()
    dao.insertAll(ids.mapIndexed { i, id -> PinnedMediaItem(mediaItemId = id, position = last + 1 + i) })
}

suspend fun unpinItem(context: Context, id: Long) {
    AppDatabase.get(context).pinnedMediaItemDao().unpin(id)
}

/** Exchanges the places of two pinned items. */
suspend fun swapPinned(context: Context, a: Long, b: Long) {
    val db = AppDatabase.get(context)
    val dao = db.pinnedMediaItemDao()
    db.withTransaction {
        val rows = dao.pinsByIds(listOf(a, b))
        if (rows.size != 2) return@withTransaction
        dao.setPosition(rows[0].mediaItemId, rows[1].position)
        dao.setPosition(rows[1].mediaItemId, rows[0].position)
    }
}

/**
 * Resolves pinned rows against MediaStore, in their order. Pins pointing at a since-deleted item are
 * dropped from the result and pruned from the database. An item living in a locked album is left out
 * too, without dropping its pin: a picture put behind a code must not come back through the hero card.
 */
suspend fun resolvedPinnedMediaItems(context: Context, rows: List<PinnedMediaItem>): List<MediaItem> {
    if (rows.isEmpty()) return emptyList()
    val orderedIds = rows.map { it.mediaItemId }
    val resolved = withContext(Dispatchers.IO) { queryMediaItemsByIds(context, orderedIds) }
    val resolvedIds = resolved.map { it.id }.toSet()
    val stale = orderedIds.filterNot { it in resolvedIds }
    if (stale.isNotEmpty()) {
        val dao = AppDatabase.get(context).pinnedMediaItemDao()
        stale.forEach { dao.unpin(it) }
    }
    val locked = GalleryLock.lockedBucketIdsNow(context)
    val rank = orderedIds.withIndex().associate { (i, id) -> id to i }
    return resolved.filterNot { it.bucketId in locked }.sortedBy { rank[it.id] }
}
