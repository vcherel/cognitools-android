package com.example.myapp.news

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One article as a feed describes it. [link] is the article's URL and its identity everywhere: the
 * read table, the saved table and the deduplication all key on it, since nothing else is stable
 * across the outlets (a few feeds reuse their guid format, most don't).
 */
data class NewsArticle(
    val link: String,
    val title: String,
    val summary: String,
    val imageUrl: String?,
    val source: String,
    val categoryId: String,
    val publishedAt: Long
)

/** An article that was opened. Kept 30 days, then purged by [MyApplication.onCreate]. */
@Entity(tableName = "news_read")
data class NewsRead(
    @PrimaryKey val link: String,
    val readAt: Long
)

/**
 * A starred article, stored whole rather than by reference: feeds only carry the last few days, so a
 * saved article would be unreadable a week later if it were just a link. [text] is the extracted
 * body, filled in when the article was opened before being saved, which is what makes it readable
 * offline.
 */
@Entity(tableName = "news_saved")
data class NewsSaved(
    @PrimaryKey val link: String,
    val title: String,
    val summary: String,
    val imageUrl: String?,
    val source: String,
    val categoryId: String,
    val publishedAt: Long,
    val savedAt: Long,
    val text: String?
)

fun NewsArticle.toSaved(text: String?): NewsSaved = NewsSaved(
    link = link,
    title = title,
    summary = summary,
    imageUrl = imageUrl,
    source = source,
    categoryId = categoryId,
    publishedAt = publishedAt,
    savedAt = System.currentTimeMillis(),
    text = text
)

fun NewsSaved.toArticle(): NewsArticle = NewsArticle(
    link = link,
    title = title,
    summary = summary,
    imageUrl = imageUrl,
    source = source,
    categoryId = categoryId,
    publishedAt = publishedAt
)

/**
 * Where an article was left, as a 0..1 fraction of its scrollable height. A row exists only while
 * the article is unfinished: reaching the end deletes it, which keeps the table down to the handful
 * of articles actually in progress. The article's own fields are copied in the way [NewsSaved] does
 * them, so the resume card can be drawn whatever tab happens to be loaded.
 */
@Entity(tableName = "news_progress")
data class NewsProgress(
    @PrimaryKey val link: String,
    val title: String,
    val summary: String,
    val imageUrl: String?,
    val source: String,
    val categoryId: String,
    val publishedAt: Long,
    val ratio: Float,
    val updatedAt: Long
)

fun NewsArticle.toProgress(ratio: Float): NewsProgress = NewsProgress(
    link = link,
    title = title,
    summary = summary,
    imageUrl = imageUrl,
    source = source,
    categoryId = categoryId,
    publishedAt = publishedAt,
    ratio = ratio,
    updatedAt = System.currentTimeMillis()
)

fun NewsProgress.toArticle(): NewsArticle = NewsArticle(
    link = link,
    title = title,
    summary = summary,
    imageUrl = imageUrl,
    source = source,
    categoryId = categoryId,
    publishedAt = publishedAt
)

@Dao
interface NewsDao {
    @Query("SELECT link FROM news_read")
    fun observeReadLinks(): Flow<List<String>>

    @Upsert suspend fun markRead(row: NewsRead)

    @Query("DELETE FROM news_read WHERE link = :link")
    suspend fun markUnread(link: String)

    @Query("DELETE FROM news_read WHERE readAt < :cutoff")
    suspend fun purgeReadBefore(cutoff: Long)

    @Query("SELECT * FROM news_saved ORDER BY savedAt DESC")
    fun observeSaved(): Flow<List<NewsSaved>>

    @Query("SELECT * FROM news_saved WHERE link = :link")
    suspend fun getSaved(link: String): NewsSaved?

    @Upsert suspend fun upsertSaved(row: NewsSaved)

    @Query("DELETE FROM news_saved WHERE link = :link")
    suspend fun deleteSaved(link: String)

    @Query("SELECT * FROM news_progress ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestProgress(): Flow<NewsProgress?>

    @Query("SELECT * FROM news_progress WHERE link = :link")
    suspend fun getProgress(link: String): NewsProgress?

    @Upsert suspend fun upsertProgress(row: NewsProgress)

    @Query("DELETE FROM news_progress WHERE link = :link")
    suspend fun deleteProgress(link: String)

    @Query("DELETE FROM news_progress WHERE updatedAt < :cutoff")
    suspend fun purgeProgressBefore(cutoff: Long)
}

/**
 * How long an article stays marked read, and how long an unfinished one keeps its position. The
 * feeds carry a few days at most, so a week is already past anything still reachable, and it keeps
 * both tables from growing without bound.
 */
const val NEWS_READ_RETENTION_DAYS = 7
