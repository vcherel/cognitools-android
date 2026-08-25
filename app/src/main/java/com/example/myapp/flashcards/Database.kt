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
    version = 15
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

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (" +
                        "`id` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `color` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `locked` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pinned_media_items` (" +
                        "`mediaItemId` INTEGER NOT NULL, " +
                        "`pinnedAt` INTEGER NOT NULL, " +
                        "`isHero` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`mediaItemId`))"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_favorites` (" +
                        "`id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`author` TEXT NOT NULL, " +
                        "`artworkUrl` TEXT, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_seen_episodes` (" +
                        "`episodeId` TEXT NOT NULL, " +
                        "`seenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`episodeId`))"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `podcast_favorites` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'RSS'")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_episode_progress` (" +
                        "`episodeId` TEXT NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`episodeId`))"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `lists` ADD COLUMN `fixedSide` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `books` (" +
                        "`id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`author` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`coverFileName` TEXT, " +
                        "`chapterCount` INTEGER NOT NULL, " +
                        "`chapterIndex` INTEGER NOT NULL, " +
                        "`blockIndex` INTEGER NOT NULL, " +
                        "`blockOffset` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`lastOpenedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_downloads` (" +
                        "`episodeId` TEXT NOT NULL, " +
                        "`podcastId` TEXT NOT NULL, " +
                        "`podcastTitle` TEXT NOT NULL, " +
                        "`podcastArtworkUrl` TEXT, " +
                        "`title` TEXT NOT NULL, " +
                        "`pubDate` INTEGER NOT NULL, " +
                        "`audioUrl` TEXT NOT NULL, " +
                        "`durationSec` INTEGER, " +
                        "`downloadedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`episodeId`))"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `news_read` (" +
                        "`link` TEXT NOT NULL, " +
                        "`readAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`link`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `news_saved` (" +
                        "`link` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`imageUrl` TEXT, " +
                        "`source` TEXT NOT NULL, " +
                        "`categoryId` TEXT NOT NULL, " +
                        "`publishedAt` INTEGER NOT NULL, " +
                        "`savedAt` INTEGER NOT NULL, " +
                        "`text` TEXT, " +
                        "PRIMARY KEY(`link`))"
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `news_progress` (" +
                        "`link` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`imageUrl` TEXT, " +
                        "`source` TEXT NOT NULL, " +
                        "`categoryId` TEXT NOT NULL, " +
                        "`publishedAt` INTEGER NOT NULL, " +
                        "`ratio` REAL NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`link`))"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flashcards.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14, MIGRATION_14_15
                )
                    .build().also { instance = it }
            }
    }
}
