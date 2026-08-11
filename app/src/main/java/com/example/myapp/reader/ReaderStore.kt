package com.example.myapp.reader

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.readerDataStore by preferencesDataStore("reader")

/** How the pages are drawn, the same for every book: one comfortable size, found once. */
object ReaderPrefs {
    private val KEY_FONT_SIZE = intPreferencesKey("font_size")

    const val MIN_FONT_SIZE = 14
    const val MAX_FONT_SIZE = 30
    const val DEFAULT_FONT_SIZE = 19

    fun fontSize(context: Context): Flow<Int> =
        context.readerDataStore.data.map { it[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE }

    suspend fun setFontSize(context: Context, size: Int) {
        context.readerDataStore.edit { it[KEY_FONT_SIZE] = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) }
    }
}
