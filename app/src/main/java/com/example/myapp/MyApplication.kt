package com.example.myapp

import android.app.Application
import androidx.work.Configuration
import com.example.myapp.flashcards.createNotificationChannel
import java.util.concurrent.Executors

class MyApplication : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()

        // Create notification channel synchronously (lightweight)
        createNotificationChannel(this)
    }

    // Provide custom WorkManager configuration for on-demand initialization
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.ERROR)
            .setExecutor(Executors.newFixedThreadPool(1)) // Limit threads
            .build()
}