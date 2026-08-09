package com.example.myapp.podcasts

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Where a podcast/episode comes from: a plain RSS feed (via the iTunes directory), or Deezer's own catalog. */
enum class PodcastSource { RSS, DEEZER }

/**
 * A podcast the user chose to follow. [id] is the RSS feed URL for [PodcastSource.RSS], or the
 * numeric Deezer show id (as a string) for [PodcastSource.DEEZER]; either way it is stable and
 * unique per podcast (Deezer's numeric ids never collide with an "http…" feed URL).
 */
@Entity(tableName = "podcast_favorites")
data class PodcastFavorite(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val addedAt: Long,
    val source: PodcastSource = PodcastSource.RSS
)

/** One episode marked heard. Episodes themselves aren't persisted: they're re-read from their
 *  source (RSS feed or Deezer's API) each time and joined against this table, keyed by episode id. */
@Entity(tableName = "podcast_seen_episodes")
data class PodcastSeenEpisode(
    @PrimaryKey val episodeId: String,
    val seenAt: Long
)

/**
 * Where an episode was left off, so playing it again picks up there. Only episodes actually started
 * and not finished have a row: a finished one is marked heard and its row dropped, so the absence of
 * a row means "from the beginning".
 */
@Entity(tableName = "podcast_episode_progress")
data class PodcastEpisodeProgress(
    @PrimaryKey val episodeId: String,
    val positionMs: Long,
    /** The player's own duration, 0 until it knows it. More reliable than the feed's durationSec. */
    val durationMs: Long,
    val updatedAt: Long
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

    @Query("SELECT * FROM podcast_episode_progress")
    fun observeProgress(): Flow<List<PodcastEpisodeProgress>>

    @Query("SELECT * FROM podcast_episode_progress WHERE episodeId = :id")
    suspend fun getProgress(id: String): PodcastEpisodeProgress?

    @Upsert suspend fun upsertProgress(row: PodcastEpisodeProgress)

    @Query("DELETE FROM podcast_episode_progress WHERE episodeId = :id")
    suspend fun deleteProgress(id: String)
}

/** A podcast from either directory, not yet (or already) a favorite. Powers both search results and recommendations. */
data class PodcastCatalogItem(
    val id: String,
    val source: PodcastSource,
    val title: String,
    val author: String,
    val artworkUrl: String?
)

/** One episode, joined from a favorite's source (RSS feed or Deezer's API). */
data class PodcastEpisode(
    val id: String,
    val podcastId: String,
    val podcastTitle: String,
    val podcastArtworkUrl: String?,
    val title: String,
    val pubDate: Long,
    val audioUrl: String,
    val durationSec: Int?,
    val seen: Boolean,
    val source: PodcastSource = PodcastSource.RSS
)
