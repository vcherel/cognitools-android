package com.example.myapp.flashcards

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

const val LIMIT_DUE_COUNT = 50

class FlashcardReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Load flashcards from DataStore
            val flashcards = applicationContext.flashcardDataStore.data.map { prefs ->
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
            if (dueCount >= LIMIT_DUE_COUNT) {
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
        1, TimeUnit.HOURS, // Repeat every 1 day
        15, TimeUnit.MINUTES // Flex interval - can run within 30 min of scheduled time
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

const val CHANNEL_ID = "flashcard_reminders"
const val NOTIFICATION_ID = 1001

fun createNotificationChannel(context: Context) {
    val name = "Rappels de révision"
    val descriptionText = "Notifications pour les cartes à réviser"
    val importance = NotificationManager.IMPORTANCE_DEFAULT
    val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
        description = descriptionText
    }

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(channel)
}

fun sendReviewNotification(context: Context, dueCount: Int) {
    // Create an intent to open the app
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }

    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
        .setContentTitle("Viens là, viens lààà")
        .setContentText("$dueCount cartes à réviser")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // For Android 13+, check permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    } else {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}