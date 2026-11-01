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
import com.example.myapp.calendar.CalendarDao
import com.example.myapp.calendar.CalendarItem
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

    @Query("SELECT image FROM cards WHERE image IS NOT NULL")
    suspend fun getImages(): List<String>

    @Query("DELETE FROM cards WHERE interval > :maxInterval AND listId NOT IN (:keepListIds)")
    suspend fun purgeMasteredCards(maxInterval: Int, keepListIds: List<String>)
}

@Database(
    entities = [
        FlashcardList::class, FlashcardElement::class, Note::class, PinnedMediaItem::class,
        PodcastFavorite::class, PodcastSeenEpisode::class, PodcastEpisodeProgress::class,
        PodcastDownload::class, Book::class, NewsRead::class, NewsSaved::class,
        NewsProgress::class, CalendarItem::class
    ],
    version = 23
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao
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
                ).addMigrations(MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23).build().also { instance = it }
            }
    }
}

private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_items` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `date` TEXT NOT NULL, `time` TEXT, `endTime` TEXT, `place` TEXT, " +
                "`yearKnown` INTEGER NOT NULL, `reminderMinutes` INTEGER, `done` INTEGER NOT NULL, " +
                "`wishedYear` INTEGER, PRIMARY KEY(`id`))"
        )
    }
}

private val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `calendar_items` ADD COLUMN `weekly` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `calendar_items` ADD COLUMN `snoozedUntil` TEXT")
    }
}

private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `calendar_items` ADD COLUMN `endDate` TEXT")
    }
}

// Drops the event place and reminder choice: every event now reminds the day before.
private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = "`id`, `kind`, `title`, `date`, `time`, `endTime`, `yearKnown`, `done`, " +
            "`wishedYear`, `weekly`, `snoozedUntil`, `endDate`"
        db.execSQL(
            "CREATE TABLE `calendar_items_new` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `date` TEXT NOT NULL, `time` TEXT, `endTime` TEXT, " +
                "`yearKnown` INTEGER NOT NULL, `done` INTEGER NOT NULL, `wishedYear` INTEGER, " +
                "`weekly` INTEGER NOT NULL DEFAULT 0, `snoozedUntil` TEXT, `endDate` TEXT, PRIMARY KEY(`id`))"
        )
        db.execSQL("INSERT INTO `calendar_items_new` ($columns) SELECT $columns FROM `calendar_items`")
        db.execSQL("DROP TABLE `calendar_items`")
        db.execSQL("ALTER TABLE `calendar_items_new` RENAME TO `calendar_items`")
    }
}

// The weekly flag becomes a recurrence, so a task can also repeat monthly.
private val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = "`id`, `kind`, `title`, `date`, `time`, `endTime`, `yearKnown`, `done`, " +
            "`wishedYear`, `snoozedUntil`, `endDate`"
        db.execSQL(
            "CREATE TABLE `calendar_items_new` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `date` TEXT NOT NULL, `time` TEXT, `endTime` TEXT, " +
                "`yearKnown` INTEGER NOT NULL, `done` INTEGER NOT NULL, `wishedYear` INTEGER, " +
                "`recurrence` TEXT NOT NULL, `snoozedUntil` TEXT, `endDate` TEXT, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO `calendar_items_new` ($columns, `recurrence`) " +
                "SELECT $columns, CASE WHEN `weekly` = 1 THEN 'WEEKLY' ELSE 'NONE' END FROM `calendar_items`"
        )
        db.execSQL("DROP TABLE `calendar_items`")
        db.execSQL("ALTER TABLE `calendar_items_new` RENAME TO `calendar_items`")
    }
}
