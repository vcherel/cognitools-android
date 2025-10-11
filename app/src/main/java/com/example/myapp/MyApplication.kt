package com.example.myapp

import android.app.Application
import androidx.work.Configuration
import com.example.myapp.helper.createNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Create notification channel synchronously (lightweight)
        createNotificationChannel(this)

        // Defer WorkManager initialization to background thread
        applicationScope.launch {
            initializeWorkManager()
        }
    }

    private fun initializeWorkManager() {
        com.example.myapp.helper.scheduleFlashcardReminders(this)
    }

    // Provide custom WorkManager configuration for on-demand initialization
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}