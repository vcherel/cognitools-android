package com.example.myapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.menuUsageDataStore by preferencesDataStore("menu_usage")

// How many times each of the small menu tools has been opened, so the grid can put the ones used
// most on top. Keyed by a stable id, never by the label.
class MenuUsageStore(private val context: Context) {
    val counts: Flow<Map<String, Int>> = context.menuUsageDataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            (value as? Int)?.let { key.name to it }
        }.toMap()
    }

    suspend fun recordClick(id: String) {
        context.menuUsageDataStore.edit { prefs ->
            val key = intPreferencesKey(id)
            prefs[key] = (prefs[key] ?: 0) + 1
        }
    }
}
