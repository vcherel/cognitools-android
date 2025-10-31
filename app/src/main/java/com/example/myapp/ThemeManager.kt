package com.example.myapp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ThemeDataStore by preferencesDataStore(name = "theme_preferences")

class ThemeManager(private val context: Context) {
    private val darkKey = booleanPreferencesKey("dark_mode")

    val isDarkMode: Flow<Boolean> = context.ThemeDataStore.data.map { preferences ->
        preferences[darkKey] ?: false
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.ThemeDataStore.edit { preferences ->
            preferences[darkKey] = enabled
        }
    }
}