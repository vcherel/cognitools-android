package com.example.myapp.podcasts

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A podcast the user chose to follow. [id] is the RSS feed URL, stable and unique per podcast. */
@Entity(tableName = "podcast_favorites")
data class PodcastFavorite(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val addedAt: Long
)

/** One episode marked heard. Episodes themselves aren't persisted: they're re-read from the RSS
 *  feed each time and joined against this table, keyed by the episode's audio URL. */
@Entity(tableName = "podcast_seen_episodes")
data class PodcastSeenEpisode(
    @PrimaryKey val episodeId: String,
    val seenAt: Long
)

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcast_favorites ORDER BY addedAt")
    fun observeFavorites(): Flow<List<PodcastFavorite>>

    @Query("SELECT * FROM podcast_favorites ORDER BY addedAt")
    suspend fun getFavorites(): List<PodcastFavorite>

    @Upsert suspend fun upsertFavorite(favorite: PodcastFavorite)

    @Query("DELETE FROM podcast_favorites WHERE id = :id")
    suspend fun deleteFavorite(id: String)

    @Query("SELECT episodeId FROM podcast_seen_episodes")
    suspend fun getSeenIds(): List<String>

    @Upsert suspend fun markSeen(row: PodcastSeenEpisode)

    @Query("DELETE FROM podcast_seen_episodes WHERE episodeId = :id")
    suspend fun unmarkSeen(id: String)
}

/** A podcast search result from the directory, not yet (or already) a favorite. */
data class PodcastSearchResult(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artworkUrl: String?
)

/** One episode, joined from a favorite's RSS feed. [id] is the enclosure (audio file) URL. */
data class PodcastEpisode(
    val id: String,
    val podcastId: String,
    val podcastTitle: String,
    val podcastArtworkUrl: String?,
    val title: String,
    val pubDate: Long,
    val audioUrl: String,
    val durationSec: Int?,
    val seen: Boolean
)
