package com.example.myapp.helper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.myapp.data.dataStore
import com.example.myapp.models.FlashcardList
import com.example.myapp.models.FlashcardElement
import com.example.myapp.models.isDue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class FlashcardReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Load flashcards from DataStore
            val flashcards = applicationContext.dataStore.data.map { prefs ->
                val listsJson = prefs[stringPreferencesKey("lists")] ?: "[]"
                val lists = FlashcardList.listFromJsonString(listsJson)

                lists.flatMap { list ->
                    val key = stringPreferencesKey("elements_${list.id}")
                    val cardsJson = prefs[key] ?: "[]"
                    FlashcardElement.listFromJsonString(cardsJson)
                }
            }.first()

            // Count due cards
            val dueCount = flashcards.count { isDue(it) }

            // Send notification if threshold is met
            if (dueCount >= 50) {
                sendReviewNotification(applicationContext, dueCount)
            }

            Result.success()
        } catch (_: Exception) {
            // Retry if something goes wrong
            Result.retry()
        }
    }
}

fun scheduleFlashcardReminders(context: Context) {
    // Create periodic work request - runs once per day
    val reminderWork = PeriodicWorkRequestBuilder<FlashcardReminderWorker>(
        6, TimeUnit.HOURS, // Repeat every 1 day
        30, TimeUnit.MINUTES // Flex interval - can run within 30 min of scheduled time
    )
        .setInitialDelay(1, TimeUnit.HOURS) // First run after 1 hour
        .build()

    // Schedule the work - it will replace any existing work with same name
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "flashcard_reminders",
        ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
        reminderWork
    )
}