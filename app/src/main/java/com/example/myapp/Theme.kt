package com.example.myapp

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore("theme_preferences")

private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")

class ThemeManager(private val context: Context) {
    val isDarkMode: Flow<Boolean> = context.themeDataStore.data.map { prefs ->
        prefs[DARK_MODE_KEY] ?: false
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.themeDataStore.edit { prefs ->
            prefs[DARK_MODE_KEY] = enabled
        }
    }
}

// Read by the custom buttons and cards, which pick their own colors rather than the theme's.
val LocalIsDarkMode = compositionLocalOf { false }

@Composable
fun AppTheme(isDarkMode: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkMode) {
            darkColorScheme(
                primary = Color.White,
                secondary = Color.White,
                tertiary = Color.White,
                background = Color.Black,
                surface = Color(0xFF1C1C1C),
                onPrimary = Color.Black,
                onSecondary = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White
            )
        } else {
            lightColorScheme(
                primary = Color.Black,
                secondary = Color.Black,
                tertiary = Color.Black,
            )
        },
        content = content
    )
}
