package com.example.myapp.flashcards

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.myapp.gallery.PinnedMediaItem
import com.example.myapp.gallery.PinnedMediaItemDao
import com.example.myapp.notes.Note
import com.example.myapp.news.NewsDao
import com.example.myapp.news.NewsProgress
import com.example.myapp.news.NewsRead
import com.example.myapp.news.NewsSaved
import com.example.myapp.notes.NoteDao
import com.example.myapp.podcasts.PodcastDao
import com.example.myapp.podcasts.PodcastDownload
import com.example.myapp.podcasts.PodcastEpisodeProgress
import com.example.myapp.podcasts.PodcastFavorite
import com.example.myapp.podcasts.PodcastSeenEpisode
import com.example.myapp.reader.Book
import com.example.myapp.reader.BookDao
import kotlinx.coroutines.flow.Flow

data class CountRow(val listId: String, val c: Int)

@Dao
interface FlashcardDao {
    @Query("SELECT * FROM lists ORDER BY `order`")
    fun observeLists(): Flow<List<FlashcardList>>

    @Query("SELECT * FROM lists ORDER BY `order`")
    suspend fun getLists(): List<FlashcardList>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun getList(id: String): FlashcardList?

    @Query("SELECT * FROM cards WHERE listId = :listId")
    fun observeElements(listId: String): Flow<List<FlashcardElement>>

    @Query("SELECT * FROM cards WHERE listId = :listId")
    suspend fun getElements(listId: String): List<FlashcardElement>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun getElement(id: String): FlashcardElement?

    @Query("SELECT * FROM cards")
    fun observeAllElements(): Flow<List<FlashcardElement>>

    @Query("SELECT * FROM cards")
    suspend fun getAllElements(): List<FlashcardElement>

    // total count per list
    @Query("SELECT listId, COUNT(*) AS c FROM cards GROUP BY listId")
    fun observeTotalCounts(): Flow<List<CountRow>>

    // due count per list, computed in SQL (mirrors isDue: now - lastReview >= interval * 60000)
    @Query("""
        SELECT listId, COUNT(*) AS c FROM cards
        WHERE (:now - lastReview) >= interval * 60000
        GROUP BY listId
    """)
    fun observeDueCounts(now: Long): Flow<List<CountRow>>

    @Upsert suspend fun upsertList(list: FlashcardList)
    @Upsert suspend fun upsertLists(lists: List<FlashcardList>)
    @Upsert suspend fun upsertElement(el: FlashcardElement)
    @Upsert suspend fun upsertElements(els: List<FlashcardElement>)
    // Cards are removed by the ON DELETE CASCADE foreign key.
    @Query("DELETE FROM lists WHERE id = :id") suspend fun deleteList(id: String)
    @Query("DELETE FROM cards WHERE id = :id") suspend fun deleteElement(id: String)

    @Query("DELETE FROM cards WHERE interval > :maxInterval AND listId NOT IN (:keepListIds)")
    suspend fun purgeMasteredCards(maxInterval: Int, keepListIds: List<String>)
}

@Database(
    entities = [
        FlashcardList::class, FlashcardElement::class, Note::class, PinnedMediaItem::class,
        PodcastFavorite::class, PodcastSeenEpisode::class, PodcastEpisodeProgress::class,
        PodcastDownload::class, Book::class, NewsRead::class, NewsSaved::class,
        NewsProgress::class
    ],
    version = 17
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flashcardDao(): FlashcardDao
    abstract fun noteDao(): NoteDao
    abstract fun pinnedMediaItemDao(): PinnedMediaItemDao
    abstract fun podcastDao(): PodcastDao
    abstract fun bookDao(): BookDao
    abstract fun newsDao(): NewsDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flashcards.db"
                ).addMigrations(MIGRATION_16_17).build().also { instance = it }
            }
    }
}

// The pins trade their hero flag and pin time for a plain position, numbered in the order they were
// shown: the hero first, then by pin time.
private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pinned_media_items_new (mediaItemId INTEGER NOT NULL, position INTEGER NOT NULL, " +
                "PRIMARY KEY(mediaItemId))"
        )
        db.execSQL(
            """
            INSERT INTO pinned_media_items_new (mediaItemId, position)
            SELECT p.mediaItemId, (
                SELECT COUNT(*) FROM pinned_media_items o
                WHERE o.isHero > p.isHero
                    OR (o.isHero = p.isHero AND (o.pinnedAt < p.pinnedAt
                        OR (o.pinnedAt = p.pinnedAt AND o.mediaItemId < p.mediaItemId)))
            )
            FROM pinned_media_items p
            """
        )
        db.execSQL("DROP TABLE pinned_media_items")
        db.execSQL("ALTER TABLE pinned_media_items_new RENAME TO pinned_media_items")
    }
}
