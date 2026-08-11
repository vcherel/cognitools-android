package com.example.myapp.translate

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

val Context.translateDataStore by preferencesDataStore("translate")

/** One past lookup, kept so a word looked up while reading can be found again later. */
data class LookupEntry(val source: String, val translation: String, val to: TranslateLang)

/**
 * The translator's small persisted state: which way it opens, which flashcard list a word goes to,
 * and the recent lookups. All of it fits in preferences, so there is no table for any of it.
 */
object TranslateStore {
    private val KEY_TARGET = stringPreferencesKey("target")
    private val KEY_LIST = stringPreferencesKey("flashcard_list")
    private val KEY_HISTORY = stringPreferencesKey("history")

    private const val MAX_HISTORY = 40

    fun target(context: Context): Flow<TranslateLang> =
        context.translateDataStore.data.map { TranslateLang.fromCode(it[KEY_TARGET]) ?: TranslateLang.FR }

    suspend fun setTarget(context: Context, lang: TranslateLang) {
        context.translateDataStore.edit { it[KEY_TARGET] = lang.code }
    }

    /** The list the last card went to, so the next one is a single tap. */
    fun flashcardListId(context: Context): Flow<String?> =
        context.translateDataStore.data.map { it[KEY_LIST] }

    suspend fun setFlashcardListId(context: Context, listId: String) {
        context.translateDataStore.edit { it[KEY_LIST] = listId }
    }

    fun history(context: Context): Flow<List<LookupEntry>> =
        context.translateDataStore.data.map { decode(it[KEY_HISTORY]) }

    /** Adds a lookup at the top, dropping an older identical one rather than repeating it. */
    suspend fun remember(context: Context, entry: LookupEntry) {
        if (entry.source.isBlank() || entry.translation.isBlank()) return
        val current = decode(context.translateDataStore.data.first()[KEY_HISTORY])
        val updated = (listOf(entry) + current.filterNot { it.source.equals(entry.source, ignoreCase = true) })
            .take(MAX_HISTORY)
        context.translateDataStore.edit { it[KEY_HISTORY] = encode(updated) }
    }

    suspend fun clearHistory(context: Context) {
        context.translateDataStore.edit { it.remove(KEY_HISTORY) }
    }

    private fun encode(entries: List<LookupEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("s", entry.source)
                put("t", entry.translation)
                put("l", entry.to.code)
            })
        }
        return array.toString()
    }

    private fun decode(raw: String?): List<LookupEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                LookupEntry(
                    source = obj.optString("s"),
                    translation = obj.optString("t"),
                    to = TranslateLang.fromCode(obj.optString("l")) ?: TranslateLang.FR
                )
            }
        }.getOrDefault(emptyList())
    }
}
