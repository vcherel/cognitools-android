package com.example.myapp.flashcards

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class CountRow(val listId: String, val c: Int)

@Dao
interface FlashcardDao {
    @Query("SELECT * FROM lists ORDER BY `order`")
    fun observeLists(): Flow<List<FlashcardList>>

    @Query("SELECT * FROM lists ORDER BY `order`")
    suspend fun getLists(): List<FlashcardList>

    @Query("SELECT * FROM cards WHERE listId = :listId")
    fun observeElements(listId: String): Flow<List<FlashcardElement>>

    @Query("SELECT * FROM cards WHERE listId = :listId")
    suspend fun getElements(listId: String): List<FlashcardElement>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun getElement(id: String): FlashcardElement?

    @Query("SELECT * FROM cards")                 // replaces N+1 getAllElements()
    suspend fun getAllElements(): List<FlashcardElement>

    @Query("SELECT COUNT(*) FROM lists")
    suspend fun listCount(): Int

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
    @Delete suspend fun deleteList(list: FlashcardList)
    @Query("DELETE FROM cards WHERE id = :id") suspend fun deleteElement(id: String)
}

@Database(entities = [FlashcardList::class, FlashcardElement::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flashcardDao(): FlashcardDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flashcards.db"
                ).build().also { instance = it }
            }
    }
}
