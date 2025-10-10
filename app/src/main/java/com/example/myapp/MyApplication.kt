package com.example.myapp

import android.app.Application
import com.example.myapp.helper.createNotificationChannel
import com.example.myapp.helper.scheduleFlashcardReminders

// This is launched only once when the user first update/install the app, so that the background reminder is always active
class MyApplication: Application() {
    override fun onCreate() {
        super.onCreate()

        // Create notification channel
        createNotificationChannel(this)

        // Schedule background reminders
        scheduleFlashcardReminders(this)
    }
}