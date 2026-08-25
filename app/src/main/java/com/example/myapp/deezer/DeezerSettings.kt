package com.example.myapp.deezer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore backed Deezer settings: the ARL credential and whether playback shuffles. The ARL is
 * sensitive: it is only ever read here and sent to Deezer's own hosts, never logged.
 */
val Context.deezerDataStore by preferencesDataStore("deezer_prefs")

private val KEY_ARL = stringPreferencesKey("arl")
private val KEY_SHUFFLE = booleanPreferencesKey("shuffle")

class DeezerSettings(private val context: Context) {

    val arl: Flow<String> = context.deezerDataStore.data.map { it[KEY_ARL].orEmpty() }

    suspend fun setArl(value: String) {
        context.deezerDataStore.edit { it[KEY_ARL] = value.trim() }
    }

    /** Whether starting a list plays it in a random order. On by default. */
    val shuffle: Flow<Boolean> = context.deezerDataStore.data.map { it[KEY_SHUFFLE] ?: true }

    suspend fun setShuffle(value: Boolean) {
        context.deezerDataStore.edit { it[KEY_SHUFFLE] = value }
    }
}
